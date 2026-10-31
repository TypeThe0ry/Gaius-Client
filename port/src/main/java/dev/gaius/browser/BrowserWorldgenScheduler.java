package dev.gaius.browser;

import io.netty.channel.browser.BrowserWebSocketChannel;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;

/** Keeps integrated-server world generation cooperative in browser Workers. */
public final class BrowserWorldgenScheduler {
    private static final double DEFAULT_SLICE_MILLIS = 8.0;
    private static final double MIN_ADAPTIVE_SLICE_MILLIS = 2.0;
    private static final double BUDGET_RECOVERY_MILLIS = 0.5;
    private static final double MODERATE_YIELD_DELAY_MILLIS = 4.0;
    private static final double BUSY_YIELD_DELAY_MILLIS = 8.0;
    // A pulse may include synchronous chunk work. Check every pulse so one slice cannot
    // accumulate several heavy pulses after its cooperative deadline.
    private static final int CLOCK_CHECK_INTERVAL = 1;
    // Queue depth is maintained in Java, so checking it each pulse also preserves packet
    // fairness without crossing the Worker/page boundary.
    private static final int NETWORK_CHECK_INTERVAL = 1;
    private static final int MIN_PROGRESS_PULSES_BEFORE_NETWORK_PREEMPTION = 2;
    // A one-packet transient must not cut a terrain slice. The old >0 check
    // turned ordinary chunk traffic into a preemption storm (39k network
    // preemptions for 42k slices in the last runtime capture).
    private static final int MIN_NETWORK_QUEUE_DEPTH_FOR_PREEMPTION = 8;
    // A retained queue entry can stay visible while its handler waits for this
    // generation continuation to return.  Do not yield every two pulses to the
    // same observation; ordinary deadline slices still service the event loop,
    // and a bounded retry keeps genuinely stuck input from being ignored.
    private static final int MIN_RETAINED_PRESSURE_PROGRESS_PULSES = 64;
    private static final int MAX_NETWORK_WAIT_PULSES = 2;
    // Explicit patch points now represent bounded rows, columns, queues, or stage units.
    private static final int MAX_PULSES_PER_TURN = 512;
    // A changed-chunk broadcast pulses once per ChunkHolder, but the broadcast of one holder is
    // a few packets (tens of microseconds) while a full pulse reads the packet queues and the
    // clock through four JavaScript calls.  Run the full pulse for every Nth holder and charge
    // the elided ones to the same turn counters, so the hard pulse cap and the network pressure
    // checks see the vanilla holder count and only their check granularity (at most N holders)
    // changes.  The broadcast is not inside a task scope, so no deadline reads its holders.
    private static final int CHUNK_BROADCAST_PULSE_STRIDE = 16;
    private static final int DEFAULT_DISTANCE_MANAGER_UPDATE_BUDGET = 64;
    private static final int MIN_DISTANCE_MANAGER_UPDATE_BUDGET = 8;
    private static final int MAX_DISTANCE_MANAGER_UPDATE_BUDGET = 512;

    private static final int YIELD_DEADLINE = 0;
    private static final int YIELD_NETWORK = 1;
    private static final int YIELD_HARD_CAP = 2;
    private static final int YIELD_CHECKPOINT = 3;

    // Task-scope entry points are instrumented into methods which can be
    // resumed independently by TeaVM.  Keep the token deliberately small and
    // categorical: NONE means this invocation is nested inside an already
    // active normal scope, NORMAL owns the active-work clock, and REENTRANT is
    // isolated from that clock while a scheduler continuation is pumping.
    private static final int TASK_SCOPE_NONE = 0;
    private static final int TASK_SCOPE_NORMAL = 1;
    private static final int TASK_SCOPE_REENTRANT = 2;

    private static double sliceStartedAtMillis;
    // ``sliceStartedAtMillis`` is the start of the current scheduler slice, not a
    // license to charge every wall-clock interval after the server tick begins.
    // Task scopes accumulate only the intervals in which a world-generation task
    // is actually running.  This is intentionally separate from the pulse
    // counters: a task can yield and resume without resetting either budget.
    private static double activeWorkElapsedMillis;
    // -1 marks a paused segment; zero is a valid performance.now() value.
    private static double activeWorkStartedAtMillis = -1.0;
    private static int taskWorkDepth;
    // A packet callback can enter a task while requestYield is pumping.  Keep
    // those scopes in a separate counter so they cannot move the outer task's
    // active segment or deadline underneath its continuation.
    private static int reentrantTaskWorkDepth;
    private static int deferredTaskScopeEnds;
    private static boolean taskYielded;
    private static double deadlineMillis;
    private static double currentBudgetMillis;
    private static double previousYieldDelayMillis;
    private static double previousOverrunMillis;
    private static int pulsesUntilClockCheck = 1;
    private static int pulsesUntilNetworkCheck = 1;
    private static int pulsesInTurn;
    private static int maxPulsesInTurn;
    private static int progressPulsesInSlice;
    private static int progressPulsesSinceNetworkPreemption;
    private static int networkWaitPulses;
    private static int reentrantYieldRequests;
    private static int reentrantYieldDepth;
    private static int maxReentrantYieldDepth;
    // Maximum nested continuation depth observed by the currently active
    // requestYield call.  The cumulative max above remains the ordinary slice
    // contract; this per-yield value keeps checkpoint-only telemetry scoped to
    // the checkpoint that produced it.
    private static int maxReentrantYieldDepthInYield;
    // Mob AI is not wrapped in a worldgen task scope, so its CPU time cannot use
    // the task active-work clock.  Keep a separate monotonic hook window instead.
    private static double mobAiNextYieldAtMillis;
    private static boolean networkPreemptionPending;
    private static int lastNetworkPreemptionDepth;
    private static double lastNetworkPreemptionInputEpoch = -1.0;
    private static boolean yieldActive;
    private static boolean deferredYield;
    // While one continuation is suspended in requestYield, the browser runs other Java
    // threads (JSPI activations: the server thread, dispatcher pumps, biome and terrain
    // tasks) that reach pulse() with yieldActive still set.  They are concurrent, not nested
    // on the suspended stack.  Yielding them on every pulse, and forcing the suspended
    // continuation to yield again right after it resumes, turned each slice into a
    // one-pulse ping-pong (1.4M reentrant yields for 229k slices in a 26.3 entry profile).
    // Instead the suspended yield opens one shared window of the configured slice length;
    // concurrent pulses keep running inside it and only park once it is used up.
    // The page query ``worldgenConcurrentYield=legacy`` (kernel configuration
    // ``concurrentYield``) restores the old policy; the client page always keeps it.
    // 1 window, -1 legacy, 0 before the first suspended yield.
    private static int concurrentYieldPolicyState;
    private static double concurrentWindowStartedAtMillis;
    // 0 marks a closed window: no outer yield is suspended, or its window was used up.
    private static double concurrentWindowBudgetMillis;
    private static double concurrentWindowLengthMillis;
    private static double concurrentWindowInputEpoch;
    private static int concurrentWindowPulses;
    private static int concurrentWindowContinuedPulses;
    private static int concurrentWindowParks;
    private static int lastDistanceManagerUpdateBudget = DEFAULT_DISTANCE_MANAGER_UPDATE_BUDGET;
    // Changed-chunk broadcast batch: holders until the next full pulse (1 = the next holder),
    // pulses elided since the last full one that are not yet charged to the turn counters, and
    // holders not yet added to the chunkBroadcastItems statistic (flushed with each full pulse
    // and at the end).
    private static int chunkBroadcastUntilPulse;
    private static int chunkBroadcastElided;
    private static int chunkBroadcastUnrecorded;
    /**
     * {@link #isWorkerRuntime()} once known: 1 on the integrated server's Worker, -1 on the
     * page, 0 before the first query (filled on first use, not by the class initializer).
     */
    private static int workerRuntimeState;
    /** Cached diagnostic opt-in; disabled releases do not call marker JS bodies. */
    private static int slowProbeTelemetryState;
    /** 0 until a generation scope is instrumented, 1 completed, 2 returned pending. */
    private static int generationSuspensionState;
    /**
     * Whether the Worker counts Mob-AI pulses (the {@code __gaiusMobAiTelemetry} diagnostic
     * opt-in, set before the server starts): 1 yes, -1 no, 0 not read yet. Read once, so a
     * pulse without the opt-in makes no telemetry call into JavaScript.
     */
    private static int mobAiTelemetryState;
    private static int worldgenStatusTelemetryState;
    private static double taskTelemetryStartedAtMillis;
    private static long taskTelemetryElapsedStartedAtNanos;
    private static String taskTelemetryLabel = "";

    private BrowserWorldgenScheduler() {
    }

    /**
     * True on the integrated server's browser Worker. The realm of a running program never
     * changes, so the JavaScript check runs once.
     */
    private static boolean isWorkerRuntime() {
        int state = workerRuntimeState;
        if (state == 0) {
            state = isWorkerRuntimeJs() ? 1 : -1;
            workerRuntimeState = state;
        }
        return state > 0;
    }

    private static boolean slowProbeTelemetryEnabled() {
        int state = slowProbeTelemetryState;
        if (state == 0) {
            state = slowProbeTelemetryOptIn() ? 1 : -1;
            slowProbeTelemetryState = state;
        }
        return state > 0;
    }

    private static boolean mobAiTelemetryEnabled() {
        int state = mobAiTelemetryState;
        if (state == 0) {
            state = mobAiTelemetryOptIn() ? 1 : -1;
            mobAiTelemetryState = state;
        }
        return state > 0;
    }

    public static boolean worldgenStatusTelemetryEnabled() {
        int state = worldgenStatusTelemetryState;
        if (state == 0) {
            state = worldgenStatusTelemetryOptIn() ? 1 : -1;
            worldgenStatusTelemetryState = state;
            BrowserChunkStatusTelemetry.setEnabled(state > 0);
        }
        return state > 0;
    }

    /** Records whether the current dispatcher generation returned a pending continuation. */
    public static void recordGenerationCompletion(boolean pending) {
        generationSuspensionState = pending ? 2 : 1;
    }

    /**
     * Patched ChunkGenerationTask.runUntilWait return hook. Vanilla returns {@code null} once the
     * task has released its claim (target status reached or cancelled); that generation is
     * finished and owns no continuation.
     */
    public static void recordGenerationResult(java.util.concurrent.CompletableFuture<?> result) {
        recordGenerationCompletion(result != null && !result.isDone());
    }

    /** Dispatcher hook used to keep a non-suspending generation in the current turn. */
    public static boolean generationSuspended() {
        // Unknown callers retain the old isolated-generation safety policy until the patched
        // runUntilWait scope has supplied a completion state.
        return generationSuspensionState == 0 || generationSuspensionState == 2;
    }

    public static void checkpoint() {
        recordSchedulerMarker(
                "server-work-turn-checkpoint",
                YIELD_CHECKPOINT,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                yieldActive,
                activeWorkElapsedMillis);
        requestYield(YIELD_CHECKPOINT, networkQueueDepth());
        recordSchedulerMarker(
                "server-work-turn-end",
                YIELD_CHECKPOINT,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                yieldActive,
                activeWorkElapsedMillis);
    }

    /**
     * Bounds the vanilla all-updates distance pass without changing its queue ordering. Any
     * remaining ticket work stays in the vanilla tracker and is resumed by the next server tick.
     */
    public static int distanceManagerUpdateBudget() {
        int budget = configuredDistanceManagerUpdateBudget(
                DEFAULT_DISTANCE_MANAGER_UPDATE_BUDGET);
        lastDistanceManagerUpdateBudget = budget;
        return budget;
    }

    /** Records the actual number of distance updates completed in one vanilla pass. */
    public static void recordDistanceManagerUpdates(int processed) {
        recordDistanceManagerUpdatesJs(
                lastDistanceManagerUpdateBudget,
                Math.max(0, processed));
    }

    /** Returns a probe timestamp, or -1 when the opt-in probe is disabled. */
    @JSBody(script = """
            if (globalThis.__gaiusSlowProbeTelemetryEnabled !== true) return -1;
            return (typeof performance !== 'undefined' && performance.now)
              ? performance.now() : Date.now();
            """)
    public static native double beginChunkHolderProbe();

    /** Records opt-in holder timings; callers must avoid status conversion when token is -1. */
    public static void endChunkHolderProbe(
            Object status, int x, int z, boolean needsGeneration, double startedAt, boolean result) {
        if (startedAt < 0.0) return;
        double endedAt = nowMillis();
        recordChunkHolderProbeJs(String.valueOf(status), x, z, needsGeneration,
                startedAt, endedAt, Math.max(0.0, endedAt - startedAt), result);
    }

    @JSBody(params = {"status", "x", "z", "needsGeneration", "startedAt", "endedAt", "durationMillis", "result"}, script = """
            if (globalThis.__gaiusSlowProbeTelemetryEnabled !== true) return;
            const root = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            const capacity = Math.max(16, Math.min(512, Math.floor(
              Number(globalThis.__gaiusSlowProbeHolderCapacity) || 256)));
            let ring = root.chunkHolderProbe;
            if (!ring || ring.capacity !== capacity) {
              ring = root.chunkHolderProbe = {capacity: capacity, entries: [], writeIndex: 0, count: 0};
            }
            const entry = {
              status: String(status).slice(0, 120), x: x | 0, z: z | 0,
              needsGeneration: !!needsGeneration,
              startAt: Number(startedAt) || 0,
              endAt: Number(endedAt) || 0,
              durationMillis: Math.max(0, Number(durationMillis) || 0),
              result: !!result
            };
            if (ring.entries.length < ring.capacity) ring.entries.push(entry);
            else ring.entries[ring.writeIndex] = entry;
            ring.writeIndex = (ring.writeIndex + 1) % ring.capacity;
            ring.count = Math.min(ring.capacity, ring.count + 1);
            root.chunkHolderProbeSamples = (Number(root.chunkHolderProbeSamples) || 0) + 1;
            const previousMaxDuration = Number(root.chunkHolderProbeMaxDurationMillis) || 0;
            root.chunkHolderProbeMaxDurationMillis = Math.max(
              previousMaxDuration, entry.durationMillis);
            if (entry.durationMillis >= previousMaxDuration) {
              root.chunkHolderProbeMaxContext = JSON.stringify({
                status: entry.status,
                x: entry.x,
                z: entry.z,
                needsGeneration: entry.needsGeneration,
                startAt: entry.startAt,
                endAt: entry.endAt,
                durationMillis: entry.durationMillis,
                result: entry.result
              });
            }
            if (!entry.result) {
              root.chunkHolderProbeFalseResults =
                (Number(root.chunkHolderProbeFalseResults) || 0) + 1;
            }
            """)
    private static native void recordChunkHolderProbeJs(
            String status, int x, int z, boolean needsGeneration,
            double startedAt, double endedAt, double durationMillis, boolean result);

    /** Keeps the remaining vanilla distance-manager loops cooperative on the server thread. */
    public static void pulseDistanceManager() {
        if (worldgenStatusTelemetryEnabled()) {
            recordDistanceManagerPulseJs();
        }
        pulse();
    }

    /** Starts a snapshot-backed changed-chunk broadcast batch. */
    public static void beginChunkBroadcast(int entries) {
        // The first holder takes a full pulse, so packet pressure that is already queued when
        // the batch starts is seen as early as before.
        chunkBroadcastUntilPulse = 1;
        chunkBroadcastElided = 0;
        chunkBroadcastUnrecorded = 0;
        if (worldgenStatusTelemetryEnabled()) {
            recordChunkBroadcastStartJs(Math.max(0, entries));
        }
    }

    /**
     * Allows long changed-chunk broadcasts to yield without retaining a live set iterator.
     * Called once per holder; only every {@link #CHUNK_BROADCAST_PULSE_STRIDE}th call runs
     * {@link #pulse()}, the others only count (no JavaScript call).
     */
    public static void pulseChunkBroadcast() {
        chunkBroadcastUnrecorded++;
        if (--chunkBroadcastUntilPulse > 0) {
            chunkBroadcastElided++;
            return;
        }
        chunkBroadcastUntilPulse = CHUNK_BROADCAST_PULSE_STRIDE;
        creditElidedPulses(chunkBroadcastElided);
        chunkBroadcastElided = 0;
        flushChunkBroadcastItems();
        pulse();
    }

    /** Marks a fully processed broadcast snapshot; updates queued during it remain for next tick. */
    public static void finishChunkBroadcast(int entries) {
        // The holders after the last full pulse count towards the turn like the others.
        creditElidedPulses(chunkBroadcastElided);
        chunkBroadcastElided = 0;
        flushChunkBroadcastItems();
        // A sparse broadcast can elide enough holders to cross the slice deadline between full
        // pulses.  Sample once at the vanilla batch boundary before the caller starts its next
        // work unit; this keeps the fast path bounded without restoring per-holder clock reads.
        if (!yieldActive && isWorkerRuntime() && deadlineMillis > 0.0
                && activeSliceElapsedMillis(nowMillis()) >= currentBudgetMillis) {
            requestYield(YIELD_DEADLINE, networkQueueDepth());
        }
        if (worldgenStatusTelemetryEnabled()) {
            recordChunkBroadcastFinishJs(Math.max(0, entries));
        }
    }

    private static void flushChunkBroadcastItems() {
        int items = chunkBroadcastUnrecorded;
        if (items > 0) {
            chunkBroadcastUnrecorded = 0;
            if (worldgenStatusTelemetryEnabled()) {
                recordChunkBroadcastItemsJs(items);
            }
        }
    }

    /**
     * Charges pulses that were elided in favour of one full {@link #pulse()} to the counters a
     * pulse advances, so the turn's hard cap, the network-preemption progress gates and a
     * suspended yield's concurrent window count them as if each had run.
     */
    private static void creditElidedPulses(int count) {
        if (count <= 0) {
            return;
        }
        progressPulsesInSlice = saturatingAdd(progressPulsesInSlice, count);
        progressPulsesSinceNetworkPreemption =
                saturatingAdd(progressPulsesSinceNetworkPreemption, count);
        pulsesInTurn = saturatingAdd(pulsesInTurn, count);
        maxPulsesInTurn = Math.max(maxPulsesInTurn, pulsesInTurn);
        if (yieldActive && concurrentWindowBudgetMillis > 0.0) {
            concurrentWindowPulses = saturatingAdd(concurrentWindowPulses, count);
        }
    }

    private static int saturatingAdd(int value, int count) {
        return value > Integer.MAX_VALUE - count ? Integer.MAX_VALUE : value + count;
    }

    /**
     * Starts the synchronous server work turn containing its chunk-generation tasks.
     *
     * <p>This is called once immediately before {@code processPacketsAndTick}, not from every
     * {@code ChunkGenerationTask.runUntilWait} invocation. A task can be resumed by the server
     * on a later tick after returning a pending future; the elapsed wall-clock time between those
     * ticks is idle time, not work in the current slice. Advance the wall-clock origin here while
     * retaining any active task milliseconds left by the wait loop; pulse counters and the
     * adaptive budget remain shared across task scopes. The reentrancy guard is important because
     * an urgent-packet callback can enter another task while a TeaVM continuation is suspended.
     * In that case the active continuation owns the clock and a nested task must not move its
     * deadline underneath it.
     */
    public static void beginServerWorkTurn() {
        if (yieldActive) {
            return;
        }

        double now = nowMillis();
        // A server work-turn boundary is outside the task scopes.  It may reset
        // the timestamp for a new tick, but never resets the adaptive budget or
        // pulse counters shared by all tasks in that tick.  If an unusual
        // continuation re-enters here while a task is still active, leave its
        // active segment intact rather than moving the deadline underneath it.
        if (taskWorkDepth > 0) {
            return;
        }
        if (currentBudgetMillis <= 0.0) {
            beginSlice(now, networkQueueDepth());
        } else {
            sliceStartedAtMillis = now;
            // Do not clear activeWorkElapsedMillis here.  A task can run from
            // waitUntilNextTick after the previous checkpoint and return before
            // this next server-turn boundary; that work belongs to the same
            // adaptive slice and must be combined with the next task.
            activeWorkStartedAtMillis = -1.0;
            deadlineMillis = now + currentBudgetMillis;
            pulsesUntilClockCheck = CLOCK_CHECK_INTERVAL;
        }
        recordSchedulerMarker(
                "server-work-turn-start",
                0,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                false,
                activeWorkElapsedMillis);
    }

    /** Starts an opt-in measurement of one integrated-server tick. */
    public static void beginServerTickTelemetry() {
        recordServerTickBegin(nowMillis());
    }

    /** Completes the opt-in measurement started immediately before a tick. */
    public static void endServerTickTelemetry() {
        recordServerTickEnd(nowMillis());
    }

    /** Starts the opt-in measurement of the runServer waitUntilNextTick phase. */
    public static void beginServerWaitTelemetry() {
        recordServerWaitBegin(nowMillis());
    }

    /** Completes the opt-in measurement of the runServer waitUntilNextTick phase. */
    public static void endServerWaitTelemetry() {
        recordServerWaitEnd(nowMillis());
    }

    @JSBody(params = "startedAt", script = """
            try {
              if (globalThis.__gaiusServerTickTelemetryEnabled !== true) return;
              const stats = globalThis.__gaiusServerTickTelemetry ||
                (globalThis.__gaiusServerTickTelemetry = {});
              const now = Number(startedAt) || 0;
              const previous = Number(stats.lastTickStartedAtMillis);
              stats.schemaVersion = 1;
              stats.tickCount = (Number(stats.tickCount) || 0) + 1;
              if (Number.isFinite(previous) && previous > 0 && now >= previous) {
                const interval = now - previous;
                stats.intervalCount = (Number(stats.intervalCount) || 0) + 1;
                stats.intervalOver100MillisCount =
                  (Number(stats.intervalOver100MillisCount) || 0) +
                  (interval > 100 ? 1 : 0);
                stats.intervalOver500MillisCount =
                  (Number(stats.intervalOver500MillisCount) || 0) +
                  (interval > 500 ? 1 : 0);
                stats.lastTickIntervalMillis = interval;
                stats.maxTickIntervalMillis = Math.max(
                  Number(stats.maxTickIntervalMillis) || 0, interval);
                stats.totalTickIntervalMillis =
                  (Number(stats.totalTickIntervalMillis) || 0) + interval;
              }
              stats.lastTickStartedAtMillis = now;
            } catch (_) {
              // Optional diagnostics must never perturb server ticks.
            }
            """)
    private static native void recordServerTickBegin(double startedAt);

    @JSBody(params = "endedAt", script = """
            try {
              if (globalThis.__gaiusServerTickTelemetryEnabled !== true) return;
              const stats = globalThis.__gaiusServerTickTelemetry ||
                (globalThis.__gaiusServerTickTelemetry = {});
              const started = Number(stats.lastTickStartedAtMillis);
              const ended = Number(endedAt) || 0;
              if (Number.isFinite(started) && started > 0 && ended >= started) {
                const duration = ended - started;
                stats.completedTickCount = (Number(stats.completedTickCount) || 0) + 1;
                stats.tickWorkOver500MillisCount =
                  (Number(stats.tickWorkOver500MillisCount) || 0) +
                  (duration > 500 ? 1 : 0);
                stats.lastTickDurationMillis = duration;
                stats.maxTickDurationMillis = Math.max(
                  Number(stats.maxTickDurationMillis) || 0, duration);
                stats.totalTickDurationMillis =
                  (Number(stats.totalTickDurationMillis) || 0) + duration;
              }
            } catch (_) {
              // Optional diagnostics must never perturb server ticks.
            }
            """)
    private static native void recordServerTickEnd(double endedAt);

    @JSBody(params = "startedAt", script = """
            try {
              if (globalThis.__gaiusServerTickTelemetryEnabled !== true) return;
              const started = Number(startedAt);
              if (!Number.isFinite(started)) return;
              const stats = globalThis.__gaiusServerTickTelemetry ||
                (globalThis.__gaiusServerTickTelemetry = {});
              stats.waitPhaseCount = (Number(stats.waitPhaseCount) || 0) + 1;
              stats.lastWaitPhaseStartedAtMillis = started;
              stats.waitPhaseOpen = true;
            } catch (_) {
              // Optional diagnostics must never perturb server ticks.
            }
            """)
    private static native void recordServerWaitBegin(double startedAt);

    @JSBody(params = "endedAt", script = """
            try {
              if (globalThis.__gaiusServerTickTelemetryEnabled !== true) return;
              const stats = globalThis.__gaiusServerTickTelemetry;
              if (!stats || stats.waitPhaseOpen !== true) return;
              const started = Number(stats.lastWaitPhaseStartedAtMillis);
              const ended = Number(endedAt);
              if (Number.isFinite(started) && Number.isFinite(ended) && ended >= started) {
                const duration = ended - started;
                stats.completedWaitPhaseCount =
                  (Number(stats.completedWaitPhaseCount) || 0) + 1;
                stats.waitPhaseOver500MillisCount =
                  (Number(stats.waitPhaseOver500MillisCount) || 0) +
                  (duration > 500 ? 1 : 0);
                stats.lastWaitPhaseDurationMillis = duration;
                stats.maxWaitPhaseDurationMillis = Math.max(
                  Number(stats.maxWaitPhaseDurationMillis) || 0, duration);
                stats.totalWaitPhaseDurationMillis =
                  (Number(stats.totalWaitPhaseDurationMillis) || 0) + duration;
              }
              stats.waitPhaseOpen = false;
              delete stats.lastWaitPhaseStartedAtMillis;
            } catch (_) {
              // Optional diagnostics must never perturb server ticks.
            }
            """)
    private static native void recordServerWaitEnd(double endedAt);

    /**
     * Enters a world-generation task scope.  Scopes are deliberately nestable:
     * a task may synchronously invoke another task while processing an urgent
     * packet, but only the outermost scope owns the active wall-clock segment.
     */
    public static int beginTaskWork() {
        return beginTaskWork("unlabeled");
    }

    /**
     * Enters a labelled task scope for opt-in slow-probe attribution.
     *
     * <p>The label is diagnostic only: the release browser leaves the telemetry flag unset, and
     * the scheduler token/depth/deadline behavior remains identical to {@link #beginTaskWork()}.
     */
    public static int beginTaskWork(String taskLabel) {
        return beginTaskWork(taskLabel, worldgenStatusTelemetryEnabled());
    }

    public static int beginTaskWork(String taskLabel, boolean statusTelemetryEnabled) {
        if ("ChunkGenerationTask.runUntilWait".equals(taskLabel)) {
            generationSuspensionState = 1;
        }
        recordSchedulerTaskLabel(taskLabel);
        if (yieldActive) {
            if (reentrantTaskWorkDepth < Integer.MAX_VALUE) {
                reentrantTaskWorkDepth++;
            }
            recordSchedulerMarker(
                    "task-start-reentrant",
                    TASK_SCOPE_REENTRANT,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    true,
                    activeWorkElapsedMillis);
            return TASK_SCOPE_REENTRANT;
        }
        if (taskWorkDepth > 0) {
            recordSchedulerMarker(
                    "task-start-nested",
                    TASK_SCOPE_NONE,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    false,
                    activeWorkElapsedMillis);
            return TASK_SCOPE_NONE;
        }
        taskWorkDepth = 1;
        taskYielded = false;
        if (statusTelemetryEnabled) {
            taskTelemetryStartedAtMillis = nowMillis();
            taskTelemetryElapsedStartedAtNanos = System.nanoTime();
            taskTelemetryLabel = taskLabel == null ? "unlabeled" : taskLabel;
            recordWorldgenStatusStart(taskTelemetryLabel);
        }

        double now = nowMillis();
        if (deadlineMillis == 0.0) {
            beginSlice(now, networkQueueDepth());
            recordSchedulerMarker(
                    "task-start-normal",
                    TASK_SCOPE_NORMAL,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    false,
                    activeWorkElapsedMillis);
            return TASK_SCOPE_NORMAL;
        }
        // The clock was paused at endTaskWork (or at the previous checkpoint).
        // Starting a new scope records a fresh active segment; the paused gap is
        // intentionally absent from activeWorkElapsedMillis.
        activeWorkStartedAtMillis = now;
        recordSchedulerMarker(
                "task-start-normal",
                TASK_SCOPE_NORMAL,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                false,
                activeWorkElapsedMillis);
        return TASK_SCOPE_NORMAL;
    }

    /**
     * Leaves a world-generation task scope.  The defensive underflow guard is
     * useful on browser shutdown/error paths where a continuation can be
     * cancelled after its normal return has already been observed.
     */
    public static void endTaskWork(int token) {
        if (token == TASK_SCOPE_NONE) {
            if (taskWorkDepth <= 0) {
                recordSchedulerMarker(
                        "task-end-underflow",
                        token,
                        taskWorkDepth,
                        reentrantTaskWorkDepth,
                        yieldActive,
                        activeWorkElapsedMillis);
                return;
            }
            recordSchedulerMarker(
                    "task-end-nested",
                    token,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    yieldActive,
                    activeWorkElapsedMillis);
            return;
        }
        if (token == TASK_SCOPE_REENTRANT) {
            // A reentrant callback may resume after the outer requestYield has
            // already dropped yieldActive.  Its token still closes only its
            // own isolated scope and can never decrement the outer task.
            if (reentrantTaskWorkDepth <= 0) {
                recordSchedulerMarker(
                        "task-end-underflow",
                        token,
                        taskWorkDepth,
                        reentrantTaskWorkDepth,
                        yieldActive,
                        activeWorkElapsedMillis);
                return;
            }
            reentrantTaskWorkDepth--;
            recordSchedulerMarker(
                    "task-end-reentrant",
                    token,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    yieldActive,
                    activeWorkElapsedMillis);
            return;
        }
        if (token != TASK_SCOPE_NORMAL) {
            recordSchedulerMarker(
                    "task-end-invalid",
                    token,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    yieldActive,
                    activeWorkElapsedMillis);
            return;
        }
        if (yieldActive) {
            // Only a live NORMAL scope may be deferred.  In particular, an
            // unmatched REENTRANT/NONE close must not leave a stale deferred
            // close which a later continuation could apply to another task.
            if (taskWorkDepth <= 0) {
                recordSchedulerMarker(
                        "task-end-underflow",
                        token,
                        taskWorkDepth,
                        reentrantTaskWorkDepth,
                        true,
                        activeWorkElapsedMillis);
                return;
            }
            if (deferredTaskScopeEnds == 0) {
                deferredTaskScopeEnds++;
            }
            recordSchedulerMarker(
                    "task-end-deferred",
                    token,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    true,
                    activeWorkElapsedMillis);
            return;
        }
        if (taskWorkDepth <= 0) {
            recordSchedulerMarker(
                    "task-end-underflow",
                    token,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    false,
                    activeWorkElapsedMillis);
            return;
        }
        taskWorkDepth = 0;

        double now = nowMillis();
        if (taskTelemetryElapsedStartedAtNanos != 0L) {
            long wallElapsed = Math.max(0L,
                    Math.round(Math.max(0.0, now - taskTelemetryStartedAtMillis) * 1_000_000.0));
            long elapsedNanos = Math.max(0L, System.nanoTime() - taskTelemetryElapsedStartedAtNanos);
            BrowserChunkStatusTelemetry.record(
                    taskTelemetryLabel,
                    wallElapsed,
                    elapsedNanos);
            recordWorldgenStatusEnd(taskTelemetryLabel, wallElapsed, elapsedNanos);
        }
        taskTelemetryLabel = "";
        taskTelemetryStartedAtMillis = 0.0;
        taskTelemetryElapsedStartedAtNanos = 0L;
        activeWorkElapsedMillis += activeSegmentElapsedMillis(now);
        activeWorkStartedAtMillis = -1.0;
        recordSchedulerMarker(
                "task-end-normal",
                token,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                false,
                activeWorkElapsedMillis);
        boolean checkTaskBoundary = !taskYielded;
        taskYielded = false;
        // Deep generation loops sample the clock every few pulses.  A task can still cross a
        // server-turn boundary without another pulse, so close the accounting gap here before
        // the dispatcher starts the next task.  The scope is already closed, making this yield
        // non-reentrant while preserving the shared adaptive budget.
        if (checkTaskBoundary && isWorkerRuntime()
                && deadlineMillis > 0.0
                && activeWorkElapsedMillis >= currentBudgetMillis) {
            requestYield(YIELD_DEADLINE, networkQueueDepth());
        }
    }

    public static void pulse() {
        progressPulsesInSlice++;
        if (progressPulsesSinceNetworkPreemption < Integer.MAX_VALUE) {
            progressPulsesSinceNetworkPreemption++;
        }
        pulsesInTurn++;
        maxPulsesInTurn = Math.max(maxPulsesInTurn, pulsesInTurn);
        if (yieldActive) {
            yieldConcurrentContinuation();
            return;
        }

        if (isWorkerRuntime() && networkPreemptionPending) {
            networkWaitPulses++;
            if (progressPulsesInSlice >= MIN_PROGRESS_PULSES_BEFORE_NETWORK_PREEMPTION) {
                requestNetworkYield(
                        Math.max(1, networkQueueDepth()), networkInputEpoch());
                return;
            }
        } else if (isWorkerRuntime() && --pulsesUntilNetworkCheck <= 0) {
            pulsesUntilNetworkCheck = NETWORK_CHECK_INTERVAL;
            int queueDepth = networkQueueDepth();
            boolean pendingInput = queueDepth >= MIN_NETWORK_QUEUE_DEPTH_FOR_PREEMPTION
                    || hasPendingNetworkInput();
            if (!pendingInput) {
                // A later 0 -> non-zero transition is new actionable pressure,
                // even when its bytes were already counted in the arrival epoch.
                lastNetworkPreemptionDepth = 0;
                lastNetworkPreemptionInputEpoch = -1.0;
            } else {
                int pressureDepth = Math.max(1, queueDepth);
                double inputEpoch = networkInputEpoch();
                boolean freshPressure = lastNetworkPreemptionDepth == 0
                        || pressureDepth > lastNetworkPreemptionDepth
                        || inputEpoch != lastNetworkPreemptionInputEpoch;
                boolean retainedPressureRetryDue = progressPulsesSinceNetworkPreemption
                        >= MIN_RETAINED_PRESSURE_PROGRESS_PULSES;
                // The same retained observation already received an event-loop
                // turn. Let useful generation proceed until the bounded retry;
                // deadline and hard-cap yields remain fully active below.
                if (freshPressure || retainedPressureRetryDue) {
                    networkPreemptionPending = true;
                    networkWaitPulses = 1;
                    if (progressPulsesInSlice
                            >= MIN_PROGRESS_PULSES_BEFORE_NETWORK_PREEMPTION) {
                        requestNetworkYield(pressureDepth, inputEpoch);
                        return;
                    }
                }
            }
        }

        if (pulsesInTurn >= MAX_PULSES_PER_TURN) {
            requestYield(YIELD_HARD_CAP, networkQueueDepth());
            return;
        }
        if (--pulsesUntilClockCheck > 0) {
            return;
        }
        pulsesUntilClockCheck = CLOCK_CHECK_INTERVAL;

        double now = nowMillis();
        if (deadlineMillis == 0.0) {
            beginSlice(now, networkQueueDepth());
        } else if (activeSliceElapsedMillis(now) >= currentBudgetMillis) {
            requestYield(YIELD_DEADLINE, networkQueueDepth());
        }
    }

    /**
     * Movement packets can trigger collision/chunk work on the integrated server.  When the
     * worldgen queue is already under pressure, let the current generation slice yield before
     * executing another movement packet.  The packet processor keeps the item in its FIFO, so
     * this is a scheduling decision rather than packet loss or coalescing.
     */
    public static boolean shouldDeferMovementPackets() {
        if (!isWorkerRuntime()) {
            return false;
        }
        if (taskWorkDepth > 0) {
            return true;
        }
        return worldgenQueueDepthForPacketDefer() >= 32;
    }

    @org.teavm.jso.JSBody(script = """
            const stats = globalThis.__gaiusWorldgenStats;
            return stats ? Math.max(0, Number(stats.queueDepth) || 0) : 0;
            """)
    private static native int worldgenQueueDepthForPacketDefer();

    private static void requestNetworkYield(int observedQueueDepth, double inputEpoch) {
        lastNetworkPreemptionDepth = Math.max(1, observedQueueDepth);
        lastNetworkPreemptionInputEpoch = inputEpoch;
        progressPulsesSinceNetworkPreemption = 0;
        requestYield(YIELD_NETWORK, observedQueueDepth);
    }

    /**
     * Cooperative checkpoint for the vanilla {@code Mob.serverAiStep} pipeline.
     *
     * <p>Mob sensing, goal selection, navigation, and custom AI all run on the
     * integrated server's browser Worker.  The patcher calls this only between
     * those existing vanilla stages; it does not alter goal ordering or skip a
     * stage.  Keeping the hook separate from the generic pulse gives the
     * diagnostics an opt-in, bounded counter while the release path remains a
     * single scheduler call when telemetry is disabled.
     *
     * <p>Only the server Worker yields here. The client page runs the same patched
     * classes and reaches these hooks from its own entity tick (LivingEntity.tick,
     * Mob.aiStep), where a yield would split a client frame: the browser commits a frame
     * in the middle of the tick and the game loop drifts away from the display's animation
     * frames. Vanilla's client tick never pauses there, so on the page the hook returns
     * before any JavaScript call.
     */
    public static void mobAiPulse() {
        if (!isWorkerRuntime()) {
            return;
        }
        if (mobAiTelemetryEnabled()) {
            recordMobAiPulse();
        }
        double now = nowMillis();
        if (now < mobAiNextYieldAtMillis) {
            return;
        }
        mobAiNextYieldAtMillis = now + DEFAULT_SLICE_MILLIS;
        requestYield(YIELD_DEADLINE, networkQueueDepth());
        // requestYield resumes the same continuation after the event-loop turn.
        // Start the next Mob window from the actual resume time, not the old call time.
        mobAiNextYieldAtMillis = nowMillis() + DEFAULT_SLICE_MILLIS;
    }

    /**
     * Entity-tick fallback for profiles where TeaVM folds or drops Mob's
     * override methods.  LivingEntity.tick is the common live boundary for
     * every Mob instance; the instanceof guard keeps players and other
     * living entities out of the Mob-AI checkpoint path.
     */
    public static void mobEntityPulse(Object entity) {
        // EnderDragon.aiStep owns a multipart entity graph and its phase/pathfinding state.
        // Suspending from the generic LivingEntity.tick fallback leaves that graph across a
        // JSPI continuation boundary and the WasmGC server reports a bare java.lang.Error.
        // Mob.aiStep checkpoints remain available for ordinary mobs; keep the dragon tick
        // contiguous so its vanilla phase and part updates retain their ordering.
        if (isWorkerRuntime()
                && entity instanceof net.minecraft.world.entity.Mob
                && !(entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)) {
            mobAiPulse();
        }
    }

    @JSBody(script = """
            try {
              if (globalThis.__gaiusMobAiTelemetry !== true) return;
              const stats = globalThis.__gaiusWorldgenStats ||
                (globalThis.__gaiusWorldgenStats = {});
              stats.mobAiPulses = (Number(stats.mobAiPulses) || 0) + 1;
              stats.mobAiMaxPulses = Math.max(
                Number(stats.mobAiMaxPulses) || 0,
                stats.mobAiPulses
              );
            } catch (_) {
              // Optional diagnostics must never perturb the AI or scheduler.
            }
            """)
    private static native void recordMobAiPulse();

    @JSBody(script = "return globalThis.__gaiusMobAiTelemetry === true;")
    private static native boolean mobAiTelemetryOptIn();

    private static void requestYield(int reason, int observedQueueDepth) {
        if (yieldActive) {
            // A server-turn checkpoint keeps the strict policy: the turn boundary always
            // suspends and the outer continuation yields again once it resumes.
            if (reason == YIELD_CHECKPOINT) {
                yieldReentrantContinuation();
            } else {
                yieldConcurrentContinuation();
            }
            return;
        }

        double beforePumpAt = nowMillis();
        double sliceElapsedMillis = activeSliceElapsedMillis(beforePumpAt);
        // Freeze the active segment while the scheduler pumps packets and yields
        // to the browser event loop.  The continuation starts a new segment only
        // after it resumes, so the event-loop delay is never charged to work.
        activeWorkElapsedMillis = sliceElapsedMillis;
        activeWorkStartedAtMillis = -1.0;
        taskYielded = true;
        yieldActive = true;
        recordSchedulerMarker(
                "yield-start",
                reason,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                true,
                activeWorkElapsedMillis);
        maxReentrantYieldDepthInYield = 0;
        try {
            double configuredBudgetMillis = sliceMillis();
            if (sliceStartedAtMillis == 0.0) {
                sliceStartedAtMillis = beforePumpAt;
            }
            if (currentBudgetMillis <= 0.0) {
                currentBudgetMillis = adaptiveBudgetMillis(
                        configuredBudgetMillis,
                        observedQueueDepth,
                        previousYieldDelayMillis,
                        previousOverrunMillis,
                        true);
            }

            double completedBudgetMillis = currentBudgetMillis;
            double overrunMillis = Math.max(0.0, sliceElapsedMillis - completedBudgetMillis);
            // Only the integrated server Worker owns this network pressure signal.  A client
            // invocation can see the shared bridge queue, but that queue is drained by the
            // client packet path and must not relabel a deadline yield or shrink its budget.
            int queueDepthBefore = isWorkerRuntime()
                    ? Math.max(observedQueueDepth, networkQueueDepth())
                    : 0;
            boolean pendingBefore = isWorkerRuntime()
                    && (queueDepthBefore > 0 || hasPendingNetworkInput());
            if (pendingBefore && queueDepthBefore == 0) {
                queueDepthBefore = 1;
            }
            // Keep packet handlers outside this suspended generation continuation. A finish-
            // configuration handler can wait for entity readiness which depends on this very
            // task returning and completing its chunk future. Synchronously draining here
            // would make that handler wait on its own outer stack. Incoming messages already
            // wake the server; its normal packet/poll boundaries drain the retained input.
            // A zero-delay continuation yields to MessagePort and heartbeat callbacks without
            // relying on the browser's clamp-prone positive timers.
            double yieldStartedAt = nowMillis();
            openConcurrentWindow(yieldStartedAt, configuredBudgetMillis);
            TModernRuntimeSupport.yieldToEventLoop(0);
            double resumedAt = nowMillis();
            double yieldDelayMillis = Math.max(0.0, resumedAt - yieldStartedAt);
            // Time other threads spent inside the window is work, not a busy event loop:
            // keep it out of the adaptive controller so it does not shrink this budget.
            double controllerYieldDelayMillis = Math.max(
                    0.0, yieldDelayMillis - closeConcurrentWindow(yieldDelayMillis));

            int queueDepthAfter = isWorkerRuntime() ? networkQueueDepth() : 0;
            boolean pendingAfter = isWorkerRuntime()
                    && (queueDepthAfter > 0 || hasPendingNetworkInput());
            if (pendingAfter && queueDepthAfter == 0) {
                queueDepthAfter = 1;
            }
            // A checkpoint is a server-turn boundary, not evidence that a
            // worldgen slice completed.  Decide this only after both pending
            // input observations and the event-loop continuation: a callback may
            // have produced a pulse while yieldActive was true, in which case
            // the checkpoint is an ordinary progress-bearing slice.
            boolean madeProgress = progressPulsesInSlice > 0;
            boolean checkpointOnly = reason == YIELD_CHECKPOINT
                    && progressPulsesInSlice == 0;
            int pressureDepth = Math.max(queueDepthBefore, queueDepthAfter);
            boolean networkPreemption = reason == YIELD_NETWORK || pendingBefore || pendingAfter;
            if (checkpointOnly) {
                // Keep checkpoint-only waits out of the adaptive controller and
                // ordinary slice counters.  They still record the pressure and
                // event-loop delay needed by the checkpoint-only evaluator.
                recordCheckpointOnlyYield(
                        networkWaitPulses,
                        yieldDelayMillis,
                        queueDepthBefore,
                        queueDepthAfter,
                        maxReentrantYieldDepthInYield);
            } else {
                previousYieldDelayMillis = controllerYieldDelayMillis;
                previousOverrunMillis = overrunMillis;
                currentBudgetMillis = adaptiveBudgetMillis(
                        configuredBudgetMillis,
                        pressureDepth,
                        controllerYieldDelayMillis,
                        overrunMillis,
                        madeProgress);
                reportSlice(
                        reason,
                        networkPreemption,
                        progressPulsesInSlice,
                        networkWaitPulses,
                        sliceElapsedMillis,
                        completedBudgetMillis,
                        currentBudgetMillis,
                        overrunMillis,
                        yieldDelayMillis,
                        queueDepthBefore,
                        queueDepthAfter,
                        reentrantYieldRequests,
                        MAX_NETWORK_WAIT_PULSES,
                        maxPulsesInTurn,
                        maxReentrantYieldDepth);
            }

            double nextSliceStartedAt = nowMillis();
            sliceStartedAtMillis = nextSliceStartedAt;
            activeWorkElapsedMillis = 0.0;
            // A checkpoint-only boundary is intentionally a paused clock.  The
            // following server turn will explicitly start a task segment; an
            // ordinary progress-bearing yield retains the existing nested-task
            // behavior.
            activeWorkStartedAtMillis = !checkpointOnly && taskWorkDepth > 0
                    ? nextSliceStartedAt
                    : -1.0;
            deadlineMillis = nextSliceStartedAt + currentBudgetMillis;
            pulsesUntilClockCheck = CLOCK_CHECK_INTERVAL;
            pulsesUntilNetworkCheck = NETWORK_CHECK_INTERVAL;
            pulsesInTurn = 0;
            maxPulsesInTurn = 0;
            progressPulsesInSlice = 0;
            networkWaitPulses = 0;
            networkPreemptionPending = false;
        } finally {
            yieldActive = false;
            concurrentWindowBudgetMillis = 0.0;
            boolean appliedDeferredTaskEnd = false;
            while (deferredTaskScopeEnds > 0 && taskWorkDepth > 0) {
                deferredTaskScopeEnds--;
                taskWorkDepth = 0;
                activeWorkStartedAtMillis = -1.0;
                appliedDeferredTaskEnd = true;
            }
            if (appliedDeferredTaskEnd) {
                recordSchedulerMarker(
                        "task-end-deferred-applied",
                        TASK_SCOPE_NORMAL,
                        taskWorkDepth,
                        reentrantTaskWorkDepth,
                        false,
                        activeWorkElapsedMillis);
            } else {
                recordSchedulerMarker(
                        "yield-end",
                        reason,
                        taskWorkDepth,
                        reentrantTaskWorkDepth,
                        false,
                        activeWorkElapsedMillis);
            }
            // A continuation can be cancelled after its normal close.  Do not
            // carry unmatched deferred closes into the next invocation.
            deferredTaskScopeEnds = 0;
            if (deferredYield) {
                deferredYield = false;
                // A nested callback requested another yield while the outer
                // continuation was suspended.  Carry that request into the
                // active-work clock so the next pulse cannot clear it merely
                // because the event-loop delay was excluded from elapsed work.
                activeWorkElapsedMillis = Math.max(
                        activeWorkElapsedMillis,
                        currentBudgetMillis);
                deadlineMillis = sliceStartedAtMillis;
                pulsesUntilClockCheck = 1;
            }
        }
    }

    private static void beginSlice(double now, int queueDepth) {
        double configuredBudgetMillis = sliceMillis();
        currentBudgetMillis = adaptiveBudgetMillis(
                configuredBudgetMillis,
                queueDepth,
                previousYieldDelayMillis,
                previousOverrunMillis,
                true);
        sliceStartedAtMillis = now;
        activeWorkElapsedMillis = 0.0;
        activeWorkStartedAtMillis = taskWorkDepth > 0 ? now : -1.0;
        deadlineMillis = now + currentBudgetMillis;
    }

    private static double activeSegmentElapsedMillis(double now) {
        if (activeWorkStartedAtMillis < 0.0) {
            return 0.0;
        }
        return Math.max(0.0, now - activeWorkStartedAtMillis);
    }

    private static double activeSliceElapsedMillis(double now) {
        return activeWorkElapsedMillis
                + (taskWorkDepth > 0 ? activeSegmentElapsedMillis(now) : 0.0);
    }

    private static double adaptiveBudgetMillis(
            double configuredBudgetMillis,
            int queueDepth,
            double yieldDelayMillis,
            double overrunMillis,
            boolean madeProgress) {
        double floorMillis = Math.min(configuredBudgetMillis, MIN_ADAPTIVE_SLICE_MILLIS);
        double targetMillis = configuredBudgetMillis;
        if (queueDepth >= MIN_NETWORK_QUEUE_DEPTH_FOR_PREEMPTION) {
            targetMillis = Math.min(targetMillis, configuredBudgetMillis * 0.65);
        }
        if (yieldDelayMillis >= BUSY_YIELD_DELAY_MILLIS || overrunMillis >= 2.0) {
            targetMillis = Math.min(targetMillis, configuredBudgetMillis * 0.25);
        } else if (yieldDelayMillis >= MODERATE_YIELD_DELAY_MILLIS || overrunMillis > 0.0) {
            targetMillis = Math.min(targetMillis, configuredBudgetMillis * 0.5);
        }
        if (!madeProgress) {
            targetMillis = Math.min(targetMillis, configuredBudgetMillis * 0.5);
        }
        targetMillis = Math.max(floorMillis, targetMillis);
        if (currentBudgetMillis <= 0.0 || targetMillis < currentBudgetMillis) {
            return targetMillis;
        }
        return Math.min(targetMillis, currentBudgetMillis + BUDGET_RECOVERY_MILLIS);
    }

    private static void deferReentrantYield() {
        deferredYield = true;
        if (reentrantYieldRequests < Integer.MAX_VALUE) {
            reentrantYieldRequests++;
        }
    }

    private static void yieldReentrantContinuation() {
        deferReentrantYield();
        // A nested packet callback must not continue world generation inside the active
        // slice. Suspending this continuation prevents the guard from hiding a long task.
        parkReentrantContinuation();
    }

    /**
     * A pulse (or a non-checkpoint yield request) reached while another continuation is
     * suspended in {@link #requestYield}. On the integrated-server Worker the caller is a
     * concurrent Java thread: it keeps running while the suspended yield's window is open
     * and parks only once the window is used up. Parking here does not ask the suspended
     * continuation to yield again after it resumes; it already gave up its slice.
     */
    private static void yieldConcurrentContinuation() {
        if (!concurrentYieldWindowEnabled()) {
            yieldReentrantContinuation();
            return;
        }
        if (concurrentWindowOpen()) {
            concurrentWindowPulses++;
            if (concurrentWindowContinuedPulses < Integer.MAX_VALUE) {
                concurrentWindowContinuedPulses++;
            }
            return;
        }
        if (reentrantYieldRequests < Integer.MAX_VALUE) {
            reentrantYieldRequests++;
        }
        if (concurrentWindowParks < Integer.MAX_VALUE) {
            concurrentWindowParks++;
        }
        parkReentrantContinuation();
    }

    private static boolean concurrentYieldWindowEnabled() {
        return concurrentYieldPolicyState > 0;
    }

    private static void openConcurrentWindow(double startedAtMillis, double budgetMillis) {
        concurrentWindowPulses = 0;
        // The opt-out arrives with the kernel configuration, a message after the start
        // message, so it is read again for every suspended yield rather than cached once.
        concurrentYieldPolicyState = isWorkerRuntime() && !concurrentYieldLegacyOptIn() ? 1 : -1;
        if (!concurrentYieldWindowEnabled()) {
            concurrentWindowBudgetMillis = 0.0;
            return;
        }
        concurrentWindowStartedAtMillis = startedAtMillis;
        concurrentWindowBudgetMillis = Math.max(MIN_ADAPTIVE_SLICE_MILLIS, budgetMillis);
        concurrentWindowLengthMillis = concurrentWindowBudgetMillis;
        concurrentWindowInputEpoch = networkInputEpoch();
    }

    /**
     * Closes the window of the yield that just resumed and returns how much of its delay the
     * window can account for: up to the window length when concurrent threads used it.
     */
    private static double closeConcurrentWindow(double yieldDelayMillis) {
        concurrentWindowBudgetMillis = 0.0;
        double windowLengthMillis = concurrentWindowLengthMillis;
        concurrentWindowLengthMillis = 0.0;
        if (concurrentWindowContinuedPulses == 0 && concurrentWindowParks == 0) {
            return 0.0;
        }
        recordConcurrentWindow(concurrentWindowContinuedPulses, concurrentWindowParks);
        concurrentWindowContinuedPulses = 0;
        concurrentWindowParks = 0;
        return Math.min(yieldDelayMillis, windowLengthMillis);
    }

    /**
     * The window closes for the rest of the suspended yield when its time or pulse budget is
     * used up, or when a new network frame arrived since it opened (the suspended
     * continuation may be the one that handles it).
     */
    private static boolean concurrentWindowOpen() {
        if (concurrentWindowBudgetMillis <= 0.0) {
            return false;
        }
        if (concurrentWindowPulses >= MAX_PULSES_PER_TURN
                || nowMillis() - concurrentWindowStartedAtMillis >= concurrentWindowBudgetMillis
                || networkInputEpoch() != concurrentWindowInputEpoch) {
            concurrentWindowBudgetMillis = 0.0;
            return false;
        }
        return true;
    }

    @JSBody(script = """
            const config = globalThis.__gaiusWorldgenKernelConfig;
            const mode = globalThis.__gaiusWorldgenConcurrentYield ||
              (config && config.concurrentYield);
            return mode === 'legacy';
            """)
    private static native boolean concurrentYieldLegacyOptIn();

    @JSBody(params = {"continuedPulses", "parks"}, script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            stats.concurrentWindows = (Number(stats.concurrentWindows) || 0) + 1;
            stats.concurrentWindowPulses =
              (Number(stats.concurrentWindowPulses) || 0) + continuedPulses;
            stats.concurrentWindowParks =
              (Number(stats.concurrentWindowParks) || 0) + parks;
            """)
    private static native void recordConcurrentWindowJs(int continuedPulses, int parks);

    private static void recordConcurrentWindow(int continuedPulses, int parks) {
        if (worldgenStatusTelemetryEnabled()) {
            recordConcurrentWindowJs(continuedPulses, parks);
        }
    }

    private static void parkReentrantContinuation() {
        reentrantYieldDepth++;
        maxReentrantYieldDepth = Math.max(maxReentrantYieldDepth, reentrantYieldDepth);
        maxReentrantYieldDepthInYield = Math.max(
                maxReentrantYieldDepthInYield,
                reentrantYieldDepth);
        try {
            TModernRuntimeSupport.yieldToEventLoop(0);
        } finally {
            reentrantYieldDepth--;
        }
    }

    @JSBody(params = "fallback", script = """
            const configured = Number(globalThis.__gaiusWorldgenSliceMillis);
            return Number.isFinite(configured) && configured >= 2 && configured <= 50
              ? configured
              : fallback;
            """)
    private static native double configuredSliceMillis(double fallback);

    private static double sliceMillis() {
        return configuredSliceMillis(DEFAULT_SLICE_MILLIS);
    }

    @JSBody(params = "fallback", script = """
            const configured = Number(globalThis.__gaiusDistanceManagerUpdateBudget);
            if (!Number.isFinite(configured)) return fallback;
            return Math.max(8, Math.min(512, Math.floor(configured)));
            """)
    private static native int configuredDistanceManagerUpdateBudget(int fallback);

    @JSBody(params = {"budget", "processed"}, script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            const safeBudget = Math.max(1, Number(budget) || 0);
            const safeProcessed = Math.max(0, Number(processed) || 0);
            stats.distanceManagerBatches = (Number(stats.distanceManagerBatches) || 0) + 1;
            stats.distanceManagerUpdateBudget = safeBudget;
            stats.lastDistanceManagerUpdates = safeProcessed;
            stats.totalDistanceManagerUpdates =
              (Number(stats.totalDistanceManagerUpdates) || 0) + safeProcessed;
            stats.maxDistanceManagerUpdates = Math.max(
              Number(stats.maxDistanceManagerUpdates) || 0,
              safeProcessed
            );
            if (safeProcessed >= safeBudget) {
              stats.distanceManagerBudgetExhaustions =
                (Number(stats.distanceManagerBudgetExhaustions) || 0) + 1;
            }
            """)
    private static native void recordDistanceManagerUpdatesJs(int budget, int processed);

    @JSBody(script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            stats.distanceManagerLoopPulses =
              (Number(stats.distanceManagerLoopPulses) || 0) + 1;
            """)
    private static native void recordDistanceManagerPulseJs();

    @JSBody(params = "entries", script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            const safeEntries = Math.max(0, Number(entries) || 0);
            stats.chunkBroadcastBatches = (Number(stats.chunkBroadcastBatches) || 0) + 1;
            stats.lastChunkBroadcastEntries = safeEntries;
            stats.totalChunkBroadcastEntries =
              (Number(stats.totalChunkBroadcastEntries) || 0) + safeEntries;
            stats.maxChunkBroadcastEntries = Math.max(
              Number(stats.maxChunkBroadcastEntries) || 0,
              safeEntries
            );
            """)
    private static native void recordChunkBroadcastStartJs(int entries);

    @JSBody(params = "items", script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            stats.chunkBroadcastItems =
              (Number(stats.chunkBroadcastItems) || 0) + Math.max(0, Number(items) || 0);
            """)
    private static native void recordChunkBroadcastItemsJs(int items);

    @JSBody(params = "entries", script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            stats.lastChunkBroadcastCompleted = Math.max(0, Number(entries) || 0);
            stats.completedChunkBroadcastBatches =
              (Number(stats.completedChunkBroadcastBatches) || 0) + 1;
            """)
    private static native void recordChunkBroadcastFinishJs(int entries);

    @JSBody(script = """
            return typeof performance !== 'undefined' && performance.now
              ? performance.now()
              : Date.now();
            """)
    private static native double nowMillis();

    /** Stores one bounded label for the immediately following task-start marker. */
    @JSBody(params = {"taskLabel"}, script = """
            try {
              if (globalThis.__gaiusSlowProbeTelemetryEnabled !== true) return;
              const marker = globalThis.__gaiusWorldgenSchedulerMarker ||
                (globalThis.__gaiusWorldgenSchedulerMarker = {});
              const label = String(taskLabel || 'unlabeled').slice(0, 160);
              Object.defineProperty(marker, '__pendingTaskLabel', {
                configurable: true,
                enumerable: false,
                writable: true,
                value: label
              });
            } catch (_) {
              // Diagnostic telemetry is fail-open and may never perturb the scheduler.
            }
            """)
    private static native void recordSchedulerTaskLabelJs(String taskLabel);

    private static void recordSchedulerTaskLabel(String taskLabel) {
        if (slowProbeTelemetryEnabled()) {
            recordSchedulerTaskLabelJs(taskLabel);
        }
    }

    @JSBody(script = "return globalThis.__gaiusSlowProbeTelemetryEnabled === true;")
    private static native boolean slowProbeTelemetryOptIn();

    @JSBody(script = """
            return globalThis.__gaiusWorldgenTelemetry === true;
            """)
    private static native boolean worldgenStatusTelemetryOptIn();

    @JSBody(params = {"label"}, script = """
            try {
              const root = globalThis.__gaiusWorldgenStats ||
                (globalThis.__gaiusWorldgenStats = {});
              const name = String(label || 'unlabeled').toUpperCase();
              const key = name.indexOf('BIOME') >= 0 ? 'BIOMES' :
                name.indexOf('NOISE') >= 0 || name.indexOf('TERRAIN') >= 0 ? 'NOISE' :
                name.indexOf('SURFACE') >= 0 ? 'SURFACE' :
                name.indexOf('CARVER') >= 0 ? 'CARVERS' :
                name.indexOf('FEATURE') >= 0 ? 'FEATURES' :
                name.indexOf('STRUCTURE') >= 0 ? 'STRUCTURE_STARTS' :
                name.indexOf('LIGHT') >= 0 ? 'LIGHT' :
                name.indexOf('SPAWN') >= 0 ? 'SPAWN' :
                name.indexOf('FULL') >= 0 ? 'FULL' : 'OTHER';
              const stages = root.chunkStatus || (root.chunkStatus = {});
              const stat = stages[key] || (stages[key] = {count: 0, wallMs: 0, cpuMs: 0, maxWallMs: 0});
            } catch (_) {}
            """)
    private static native void recordWorldgenStatusStart(String label);

    @JSBody(params = {"label", "wallNanos", "elapsedNanos"}, script = """
            try {
              const root = globalThis.__gaiusWorldgenStats;
              if (!root || !root.chunkStatus) return;
              const name = String(label || 'unlabeled').toUpperCase();
              const key = name.indexOf('BIOME') >= 0 ? 'BIOMES' : name.indexOf('NOISE') >= 0 || name.indexOf('TERRAIN') >= 0 ? 'NOISE' : name.indexOf('SURFACE') >= 0 ? 'SURFACE' : name.indexOf('CARVER') >= 0 ? 'CARVERS' : name.indexOf('FEATURE') >= 0 ? 'FEATURES' : name.indexOf('STRUCTURE') >= 0 ? 'STRUCTURE_STARTS' : name.indexOf('LIGHT') >= 0 ? 'LIGHT' : name.indexOf('SPAWN') >= 0 ? 'SPAWN' : name.indexOf('FULL') >= 0 ? 'FULL' : 'OTHER';
              const stat = root.chunkStatus[key];
              const wall = Math.max(0, Number(wallNanos) / 1000000 || 0);
              const elapsed = Math.max(0, Number(elapsedNanos) / 1000000 || 0);
              stat.count = (Number(stat.count) || 0) + 1;
              stat.wallMs = (Number(stat.wallMs) || 0) + wall;
              stat.elapsedMs = (Number(stat.elapsedMs) || 0) + elapsed;
              stat.maxWallMs = Math.max(Number(stat.maxWallMs) || 0, wall);
            } catch (_) {}
            """)
    private static native void recordWorldgenStatusEnd(String label, long wallNanos, long elapsedNanos);

    /**
     * Publishes fixed scalar task/turn markers only for the Node runtime smoke.
     * The browser release leaves the flag unset; malformed diagnostic state is
     * swallowed so evidence collection can never alter scheduler control flow.
     */
    @JSBody(params = {
            "event",
            "token",
            "taskDepth",
            "reentrantDepth",
            "yielding",
            "activeWorkMillis"
    }, script = """
            try {
              if (globalThis.__gaiusSlowProbeTelemetryEnabled !== true) return;
              const marker = globalThis.__gaiusWorldgenSchedulerMarker ||
                (globalThis.__gaiusWorldgenSchedulerMarker = {});
              const eventName = String(event || 'unknown');
              const now = Date.now();
              const workMillis = Math.max(0, Number(activeWorkMillis) || 0);
              const pendingTaskLabel = String(
                marker.__pendingTaskLabel || 'unlabeled'
              ).slice(0, 160);
              if (eventName.startsWith('task-start-')) {
                delete marker.__pendingTaskLabel;
              }
              marker.schemaVersion = 2;
              marker.eventSequence = (Number(marker.eventSequence) || 0) + 1;
              marker.lastEvent = eventName;
              marker.lastEventAtEpochMs = now;
              marker.token = Number(token) || 0;
              marker.taskWorkDepth = Math.max(0, Number(taskDepth) || 0);
              marker.reentrantTaskWorkDepth = Math.max(0, Number(reentrantDepth) || 0);
              marker.normalTaskScopeActive = marker.taskWorkDepth > 0;
              marker.activeTaskScope = marker.normalTaskScopeActive ||
                marker.reentrantTaskWorkDepth > 0;
              marker.yieldActive = !!yielding;
              marker.activeWorkMillis = workMillis;
            if (eventName === 'server-work-turn-start') {
              marker.serverWorkTurnSequence =
                (Number(marker.serverWorkTurnSequence) || 0) + 1;
              marker.serverWorkTurnActive = true;
              marker.lastServerWorkTurnStartedAtEpochMs = now;
            } else if (eventName === 'server-work-turn-end') {
              const serverWorkTurnWallMillis = Math.max(
                0,
                now - (Number(marker.lastServerWorkTurnStartedAtEpochMs) || now)
              );
              marker.serverWorkTurnActive = false;
              marker.lastServerWorkTurnEndedAtEpochMs = now;
              marker.lastServerWorkTurnWallMillis = serverWorkTurnWallMillis;
              marker.maxServerWorkTurnWallMillis = Math.max(
                Number(marker.maxServerWorkTurnWallMillis) || 0,
                serverWorkTurnWallMillis
              );
            } else if (eventName === 'task-start-normal') {
              marker.taskScopesStarted = (Number(marker.taskScopesStarted) || 0) + 1;
              marker.taskScopeSequence = (Number(marker.taskScopeSequence) || 0) + 1;
              marker.currentTaskScopeId = marker.taskScopeSequence;
              marker.currentTaskLabel = pendingTaskLabel;
              marker.currentNestedTaskLabel = '';
              marker.currentReentrantTaskLabel = '';
              marker.lastTaskStartedAtEpochMs = now;
              marker.__taskActiveWorkBaselineMillis = workMillis;
              marker.__taskActiveWorkAccumulatedMillis = 0;
            } else if (eventName === 'task-start-nested') {
              marker.currentNestedTaskLabel = pendingTaskLabel;
            } else if (eventName === 'task-end-nested') {
              marker.currentNestedTaskLabel = '';
            } else if (eventName === 'task-start-reentrant') {
              marker.reentrantTaskScopesStarted =
                (Number(marker.reentrantTaskScopesStarted) || 0) + 1;
              marker.currentReentrantTaskLabel = pendingTaskLabel;
            } else if (eventName === 'task-end-reentrant') {
              marker.reentrantTaskScopesEnded =
                (Number(marker.reentrantTaskScopesEnded) || 0) + 1;
              marker.currentReentrantTaskLabel = '';
            } else if (eventName === 'yield-start' && marker.normalTaskScopeActive) {
              marker.__taskActiveWorkAccumulatedMillis =
                Math.max(0, Number(marker.__taskActiveWorkAccumulatedMillis) || 0) +
                Math.max(0, workMillis -
                  (Number(marker.__taskActiveWorkBaselineMillis) || 0));
              marker.__taskActiveWorkBaselineMillis = workMillis;
            } else if (eventName === 'yield-end' && marker.normalTaskScopeActive) {
              // requestYield starts the resumed active segment from zero.  Keep
              // the next delta scoped to this task rather than the whole slice.
              marker.__taskActiveWorkBaselineMillis = workMillis;
            } else if (eventName === 'task-end-normal' ||
                       eventName === 'task-end-deferred-applied') {
              marker.__taskActiveWorkAccumulatedMillis =
                Math.max(0, Number(marker.__taskActiveWorkAccumulatedMillis) || 0) +
                Math.max(0, workMillis -
                  (Number(marker.__taskActiveWorkBaselineMillis) || 0));
              const taskActiveWorkMillis =
                Math.max(0, Number(marker.__taskActiveWorkAccumulatedMillis) || 0);
              const taskScopeWallMillis = Math.max(
                0,
                now - (Number(marker.lastTaskStartedAtEpochMs) || now)
              );
              marker.taskScopesEnded = (Number(marker.taskScopesEnded) || 0) + 1;
              marker.lastTaskEndedAtEpochMs = now;
              marker.lastTaskActiveWorkMillis = taskActiveWorkMillis;
              const previousMaxTaskActiveWorkMillis =
                Number(marker.maxTaskActiveWorkMillis) || 0;
              marker.maxTaskActiveWorkMillis = Math.max(
                previousMaxTaskActiveWorkMillis, taskActiveWorkMillis);
              marker.lastTaskScopeWallMillis = taskScopeWallMillis;
              const previousMaxTaskScopeWallMillis =
                Number(marker.maxTaskScopeWallMillis) || 0;
              marker.maxTaskScopeWallMillis = Math.max(
                previousMaxTaskScopeWallMillis, taskScopeWallMillis);
              if (taskActiveWorkMillis >= previousMaxTaskActiveWorkMillis ||
                  taskScopeWallMillis >= previousMaxTaskScopeWallMillis) {
                marker.maxTaskContext = JSON.stringify({
                  schemaVersion: 1,
                  taskScopeId: Math.max(0, Number(marker.currentTaskScopeId) || 0),
                  taskLabel: String(marker.currentTaskLabel || 'unlabeled').slice(0, 160),
                  taskActiveWorkMillis: taskActiveWorkMillis,
                  taskScopeWallMillis: taskScopeWallMillis
                }).slice(0, 512);
              }
              marker.currentTaskScopeId = 0;
              marker.currentTaskLabel = '';
              marker.currentNestedTaskLabel = '';
              marker.currentReentrantTaskLabel = '';
              delete marker.__taskActiveWorkBaselineMillis;
              delete marker.__taskActiveWorkAccumulatedMillis;
            } else if (eventName === 'task-end-underflow') {
              marker.taskScopeUnderflows =
                (Number(marker.taskScopeUnderflows) || 0) + 1;
            } else if (eventName === 'task-end-invalid') {
              marker.taskScopeInvalidEnds =
                (Number(marker.taskScopeInvalidEnds) || 0) + 1;
            }
            } catch (_) {
              // Diagnostic telemetry is fail-open and may never perturb the scheduler.
            }
            """)
    private static native void recordSchedulerMarkerJs(
            String event,
            int token,
            int taskDepth,
            int reentrantDepth,
            boolean yielding,
            double activeWorkMillis);

    private static void recordSchedulerMarker(
            String event,
            int token,
            int taskDepth,
            int reentrantDepth,
            boolean yielding,
            double activeWorkMillis) {
        if (slowProbeTelemetryEnabled()) {
            recordSchedulerMarkerJs(
                    event, token, taskDepth, reentrantDepth, yielding, activeWorkMillis);
        }
    }

    private static boolean hasPendingNetworkInput() {
        // The finish-configuration handler waits on this very generation; yielding to it as
        // "pending input" only delays the chunks it needs.
        return BrowserWebSocketChannel.hasPendingInput()
                || BrowserPacketScheduler.hasActionablePendingPackets();
    }

    private static int networkQueueDepth() {
        int queued = BrowserPacketScheduler.queuedPacketCount();
        return Math.max(queued, BrowserPacketScheduler.hasActionablePendingPackets() ? 1 : 0);
    }

    /**
     * Monotonic frame-arrival evidence distinguishes a new frame from an unchanged
     * retained queue observation whose Java handler cannot run inside requestYield.
     */
    @JSBody(script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const stats = bridge && (bridge.stats || globalThis.__gaiusNetworkStats) ||
              globalThis.__gaiusNetworkStats;
            if (!stats) return 0;
            const received = Number(stats.receivedFrames);
            return Number.isFinite(received) && received >= 0 ? received : 0;
            """)
    private static native double networkInputEpoch();

    @JSBody(script = "return typeof WorkerGlobalScope !== 'undefined' && globalThis instanceof WorkerGlobalScope;")
    private static native boolean isWorkerRuntimeJs();

    @JSBody(params = {
            "networkWaitPulses",
            "yieldDelayMillis",
            "queueDepthBefore",
            "queueDepthAfter",
            "maximumReentrantYieldDepth"
    }, script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            if (!stats.__checkpointOnlyYieldDelayHistogram) {
              Object.defineProperty(stats, '__checkpointOnlyYieldDelayHistogram', {
                value: new Uint32Array(256),
                enumerable: false
              });
            }
            const histogram = stats.__checkpointOnlyYieldDelayHistogram;
            const safeDelay = Math.max(0, Number(yieldDelayMillis) || 0);
            const bucket = Math.min(histogram.length - 1, Math.floor(safeDelay));
            if (histogram[bucket] < 0xffffffff) histogram[bucket]++;

            stats.checkpointOnlyYields =
              (Number(stats.checkpointOnlyYields) || 0) + 1;
            stats.checkpointOnlyYieldDelayMillis = safeDelay;
            stats.totalCheckpointOnlyYieldDelayMillis =
              (Number(stats.totalCheckpointOnlyYieldDelayMillis) || 0) + safeDelay;
            stats.averageCheckpointOnlyYieldDelayMillis =
              stats.totalCheckpointOnlyYieldDelayMillis / stats.checkpointOnlyYields;
            stats.checkpointOnlyMaxYieldDelayMillis = Math.max(
              Number(stats.checkpointOnlyMaxYieldDelayMillis) || 0,
              safeDelay
            );
            const percentile = function(values, fraction) {
              const target = Math.max(
                1,
                Math.ceil(stats.checkpointOnlyYields * fraction)
              );
              let seen = 0;
              for (let index = 0; index < values.length; index++) {
                seen += values[index];
                if (seen >= target) return index + 1;
              }
              return values.length;
            };
            stats.checkpointOnlyP99YieldDelayMillis = percentile(histogram, 0.99);
            stats.checkpointOnlyQueueDepth = Math.max(
              0,
              Number(queueDepthAfter) || 0
            );
            stats.checkpointOnlyMaxQueueDepth = Math.max(
              Number(stats.checkpointOnlyMaxQueueDepth) || 0,
              Number(queueDepthBefore) || 0,
              Number(queueDepthAfter) || 0
            );
            stats.checkpointOnlyMaxNetworkWaitPulses = Math.max(
              Number(stats.checkpointOnlyMaxNetworkWaitPulses) || 0,
              Number(networkWaitPulses) || 0
            );
            stats.checkpointOnlyMaxReentrantYieldDepth = Math.max(
              Number(stats.checkpointOnlyMaxReentrantYieldDepth) || 0,
              Number(maximumReentrantYieldDepth) || 0
            );
            stats.checkpointOnlyLastYieldAt = Date.now();
            """)
    private static native void recordCheckpointOnlyYieldJs(
            int networkWaitPulses,
            double yieldDelayMillis,
            int queueDepthBefore,
            int queueDepthAfter,
            int maximumReentrantYieldDepth);

    private static void recordCheckpointOnlyYield(
            int networkWaitPulses,
            double yieldDelayMillis,
            int queueDepthBefore,
            int queueDepthAfter,
            int maximumReentrantYieldDepth) {
        if (worldgenStatusTelemetryEnabled()) {
            recordCheckpointOnlyYieldJs(
                    networkWaitPulses,
                    yieldDelayMillis,
                    queueDepthBefore,
                    queueDepthAfter,
                    maximumReentrantYieldDepth);
        }
    }

    @JSBody(params = {
            "reason",
            "networkPreemption",
            "progressPulses",
            "networkWaitPulses",
            "sliceElapsedMillis",
            "completedBudgetMillis",
            "nextBudgetMillis",
            "overrunMillis",
            "yieldDelayMillis",
            "queueDepthBefore",
            "queueDepthAfter",
            "reentrantRequests",
            "networkWaitPulseLimit",
            "maximumPulsesInTurn",
            "maximumReentrantYieldDepth"
    }, script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            if (!stats.__sliceHistogram) {
              Object.defineProperty(stats, '__sliceHistogram', {
                value: new Uint32Array(128),
                enumerable: false
              });
            }
            if (!stats.__yieldDelayHistogram) {
              Object.defineProperty(stats, '__yieldDelayHistogram', {
                value: new Uint32Array(256),
                enumerable: false
              });
            }
            const histogram = stats.__sliceHistogram;
            const yieldDelayHistogram = stats.__yieldDelayHistogram;
            const bucket = Math.min(histogram.length - 1, Math.floor(sliceElapsedMillis));
            if (histogram[bucket] < 0xffffffff) histogram[bucket]++;
            const yieldDelayBucket = Math.min(
              yieldDelayHistogram.length - 1,
              Math.floor(yieldDelayMillis)
            );
            if (yieldDelayHistogram[yieldDelayBucket] < 0xffffffff) {
              yieldDelayHistogram[yieldDelayBucket]++;
            }

            stats.slices = (Number(stats.slices) || 0) + 1;
            stats.sliceElapsedMillis = sliceElapsedMillis;
            stats.totalSliceElapsedMillis =
              (Number(stats.totalSliceElapsedMillis) || 0) + sliceElapsedMillis;
            stats.averageSliceElapsedMillis = stats.totalSliceElapsedMillis / stats.slices;
            const previousMaxSliceElapsedMillis =
              Number(stats.maxSliceElapsedMillis) || 0;
            stats.maxSliceElapsedMillis = Math.max(
              previousMaxSliceElapsedMillis, sliceElapsedMillis);
            const percentile = function(values, fraction) {
              const target = Math.max(1, Math.ceil(stats.slices * fraction));
              let seen = 0;
              for (let index = 0; index < values.length; index++) {
                seen += values[index];
                if (seen >= target) return index + 1;
              }
              return values.length;
            };
            stats.p95SliceElapsedMillis = percentile(histogram, 0.95);
            stats.p99SliceElapsedMillis = percentile(histogram, 0.99);
            stats.p95YieldDelayMillis = percentile(yieldDelayHistogram, 0.95);
            stats.p99YieldDelayMillis = percentile(yieldDelayHistogram, 0.99);

            stats.configuredBudgetMillis = Number(globalThis.__gaiusWorldgenSliceMillis) ||
              completedBudgetMillis;
            stats.completedBudgetMillis = completedBudgetMillis;
            stats.budgetMillis = nextBudgetMillis;
            stats.minimumBudgetMillis = Number.isFinite(stats.minimumBudgetMillis)
              ? Math.min(stats.minimumBudgetMillis, nextBudgetMillis)
              : nextBudgetMillis;
            stats.budgetOverruns = (Number(stats.budgetOverruns) || 0) +
              (overrunMillis > 0 ? 1 : 0);
            stats.lastBudgetOverrunMillis = overrunMillis;
            stats.maxBudgetOverrunMillis = Math.max(
              Number(stats.maxBudgetOverrunMillis) || 0,
              overrunMillis
            );

            stats.yieldDelayMillis = yieldDelayMillis;
            stats.totalYieldDelayMillis =
              (Number(stats.totalYieldDelayMillis) || 0) + yieldDelayMillis;
            stats.averageYieldDelayMillis = stats.totalYieldDelayMillis / stats.slices;
            stats.maxYieldDelayMillis = Math.max(
              Number(stats.maxYieldDelayMillis) || 0,
              yieldDelayMillis
            );
            stats.queueDepth = queueDepthAfter;
            stats.maxQueueDepth = Math.max(
              Number(stats.maxQueueDepth) || 0,
              queueDepthBefore,
              queueDepthAfter
            );

            stats.progressPulses = progressPulses;
            stats.maxTurnPulses = Math.max(
              Number(stats.maxTurnPulses) || 0,
              maximumPulsesInTurn
            );
            stats.totalProgressPulses =
              (Number(stats.totalProgressPulses) || 0) + progressPulses;
            if (progressPulses > 0) {
              stats.progressSlices = (Number(stats.progressSlices) || 0) + 1;
              stats.consecutiveNoProgressSlices = 0;
            } else {
              stats.noProgressSlices = (Number(stats.noProgressSlices) || 0) + 1;
              stats.consecutiveNoProgressSlices =
                (Number(stats.consecutiveNoProgressSlices) || 0) + 1;
              stats.maxConsecutiveNoProgressSlices = Math.max(
                Number(stats.maxConsecutiveNoProgressSlices) || 0,
                stats.consecutiveNoProgressSlices
              );
            }

            if (networkPreemption) {
              stats.networkPreemptions = (Number(stats.networkPreemptions) || 0) + 1;
            }
            stats.lastNetworkWaitPulses = networkWaitPulses;
            stats.maxNetworkWaitPulses = Math.max(
              Number(stats.maxNetworkWaitPulses) || 0,
              networkWaitPulses
            );
            stats.networkWaitPulseLimit = networkWaitPulseLimit;
            stats.deadlineYields = (Number(stats.deadlineYields) || 0) +
              (reason === 0 ? 1 : 0);
            stats.hardCapYields = (Number(stats.hardCapYields) || 0) +
              (reason === 2 ? 1 : 0);
            stats.checkpointYields = (Number(stats.checkpointYields) || 0) +
              (reason === 3 ? 1 : 0);
            stats.reentrantYieldRequests = reentrantRequests;
            stats.maxReentrantYieldDepth = Math.max(
              Number(stats.maxReentrantYieldDepth) || 0,
              maximumReentrantYieldDepth
            );
            stats.lastYieldAt = Date.now();
            try {
              if (globalThis.__gaiusSlowProbeTelemetryEnabled === true &&
                  sliceElapsedMillis >= previousMaxSliceElapsedMillis) {
                const marker = globalThis.__gaiusWorldgenSchedulerMarker ||
                  (globalThis.__gaiusWorldgenSchedulerMarker = {});
                const reasonNames = ['deadline', 'network', 'hard-cap', 'checkpoint'];
                const taskLabel = String(
                  marker.currentNestedTaskLabel ||
                  marker.currentReentrantTaskLabel ||
                  marker.currentTaskLabel ||
                  'unlabeled'
                ).slice(0, 160);
                marker.maxSliceContext = JSON.stringify({
                  schemaVersion: 1,
                  sequence: Math.max(0, Number(stats.slices) || 0),
                  reason: reasonNames[Number(reason)] || 'unknown',
                  reasonCode: Number(reason) || 0,
                  taskScopeId: Math.max(0, Number(marker.currentTaskScopeId) || 0),
                  taskLabel: taskLabel,
                  sliceElapsedMillis: Math.max(0, Number(sliceElapsedMillis) || 0),
                  budgetOverrunMillis: Math.max(0, Number(overrunMillis) || 0),
                  progressPulses: Math.max(0, Number(progressPulses) || 0),
                  networkWaitPulses: Math.max(0, Number(networkWaitPulses) || 0),
                  queueDepthBefore: Math.max(0, Number(queueDepthBefore) || 0),
                  queueDepthAfter: Math.max(0, Number(queueDepthAfter) || 0),
                  networkPreemption: !!networkPreemption
                }).slice(0, 512);
              }
            } catch (_) {
              // Slow-probe attribution is diagnostic and fail-open.
            }
            """)
    private static native void reportSliceJs(
            int reason,
            boolean networkPreemption,
            int progressPulses,
            int networkWaitPulses,
            double sliceElapsedMillis,
            double completedBudgetMillis,
            double nextBudgetMillis,
            double overrunMillis,
            double yieldDelayMillis,
            int queueDepthBefore,
            int queueDepthAfter,
            int reentrantRequests,
            int networkWaitPulseLimit,
            int maximumPulsesInTurn,
            int maximumReentrantYieldDepth);

    private static void reportSlice(
            int reason,
            boolean networkPreemption,
            int progressPulses,
            int networkWaitPulses,
            double sliceElapsedMillis,
            double completedBudgetMillis,
            double nextBudgetMillis,
            double overrunMillis,
            double yieldDelayMillis,
            int queueDepthBefore,
            int queueDepthAfter,
            int reentrantRequests,
            int networkWaitPulseLimit,
            int maximumPulsesInTurn,
            int maximumReentrantYieldDepth) {
        if (worldgenStatusTelemetryEnabled()) {
            reportSliceJs(
                    reason,
                    networkPreemption,
                    progressPulses,
                    networkWaitPulses,
                    sliceElapsedMillis,
                    completedBudgetMillis,
                    nextBudgetMillis,
                    overrunMillis,
                    yieldDelayMillis,
                    queueDepthBefore,
                    queueDepthAfter,
                    reentrantRequests,
                    networkWaitPulseLimit,
                    maximumPulsesInTurn,
                    maximumReentrantYieldDepth);
        }
    }
}
