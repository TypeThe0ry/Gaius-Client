package dev.gaius.browser;

import dev.gaius.browser.render.BrowserMeshInstallQueue;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Float64Array;

/**
 * Defers expensive renderer work so one client frame cannot drain the whole compile queue.
 *
 * <p>Work is driven by completion, not by requestAnimationFrame: one pump continuation drains
 * the queue in short slices and yields a single MessageChannel turn between slices, so input,
 * network and the (uncapped, MessageChannel-paced) game frames interleave with it. Each rendered
 * frame ({@link #beginFrame}) grants a budget of tasks and work time; when it is used up the pump
 * waits for the next frame, or for {@link #FRAME_WATCHDOG_MILLIS} when no frame arrives (world
 * rendering paused), so a throttled or hidden page no longer stalls compilation.</p>
 *
 * <p>Budgets per frame ({@link Budget}): compile tasks and their time, staged upload entries,
 * bytes and time, compile runs allowed while uploads are queued, and bytes and time for
 * installing asynchronously produced meshes ({@link BrowserMeshInstallQueue}). The fast profile
 * (four or more cores, or {@code ?gaiusRenderFast=1}) keeps the values measured since v0.3.
 * {@link #canScheduleSection} caps the work in flight: queued runner tasks, the dispatcher's
 * compile backlog, staged uploads and asynchronous mesh requests together.</p>
 */
public final class BrowserRenderScheduler {
    /** Per-frame budgets of one device class. */
    private record Budget(
            int maxTasksPerFrame,
            int queueHighWater,
            long frameWorkNanos,
            int maxUploadAllocationsPerFrame,
            long uploadWorkNanos,
            long uploadBytesPerFrame,
            int compileRunsDuringUploadPerFrame,
            int maxPlannedSectionsPerExtract,
            long installBytesPerFrame,
            long installNanosPerFrame) {
    }

    private static final Budget FAST_BUDGET = new Budget(
            32, 64, 6_000_000L, 32, 6_000_000L, 4L * 1024L * 1024L, 4, 8,
            4L * 1024L * 1024L, 2_000_000L);
    private static final Budget NORMAL_BUDGET = new Budget(
            8, 16, 3_000_000L, 16, 3_000_000L, 2L * 1024L * 1024L, 2, 4,
            2L * 1024L * 1024L, 1_000_000L);
    private static final Budget FRAME_TAIL_BUDGET = new Budget(
            4, 64, 1_000_000L, 4, 1_000_000L, 512L * 1024L, 1, 2,
            1024L * 1024L, 500_000L);
    /** Longest pump slice before yielding one event-loop turn. */
    private static final long SLICE_NANOS = 2_000_000L;
    /** A paused pump resumes after this long even when no frame is rendered. */
    private static final int FRAME_WATCHDOG_MILLIS = 50;
    private static final int MAX_LOGGED_TASK_FAILURES = 8;
    private static final int MAX_UBER_NODE_CLEANUP_SCANS_PER_FRAME = 8;
    private static final long UBER_NODE_CLEANUP_BUDGET_NANOS = 250_000L;
    private static final int MAX_UPLOAD_RETRY_YIELDS = 2_048;
    private static final long MAX_UPLOAD_RETRY_NANOS = 5_000_000_000L;
    private static final long UPLOAD_RETRY_SWEEP_INTERVAL_NANOS = 1_000_000_000L;
    private static final long UPLOAD_RETRY_TOMBSTONE_IDLE_NANOS = 5_000_000_000L;
    /** Field indices of the packed telemetry snapshot (see publishTelemetryJs). */
    private static final int T_PENDING_TASKS = 0;
    private static final int T_QUEUE_CAPACITY = 1;
    private static final int T_TASK_RUNNING = 2;
    private static final int T_PEAK_PENDING_TASKS = 3;
    private static final int T_COMPILE_BACKLOG = 4;
    private static final int T_PEAK_COMPILE_BACKLOG = 5;
    private static final int T_UPLOAD_BACKLOG = 6;
    private static final int T_PEAK_UPLOAD_BACKLOG = 7;
    private static final int T_ENQUEUED_TASKS = 8;
    private static final int T_COMPLETED_TASKS = 9;
    private static final int T_BACKPRESSURE_EVENTS = 10;
    private static final int T_OVER_BUDGET_TASKS = 11;
    private static final int T_LAST_TASK_MILLIS = 12;
    private static final int T_LONGEST_TASK_MILLIS = 13;
    private static final int T_TOTAL_TASK_MILLIS = 14;
    private static final int T_UPLOAD_PASSES = 15;
    private static final int T_LAST_UPLOAD_PASS_MILLIS = 16;
    private static final int T_LONGEST_UPLOAD_PASS_MILLIS = 17;
    private static final int T_TOTAL_UPLOAD_PASS_MILLIS = 18;
    private static final int T_UPLOAD_ALLOCATIONS_QUEUED = 19;
    private static final int T_UPLOAD_ALLOCATIONS_DRAINED = 20;
    private static final int T_HIGH_WATER_ACTIVE = 21;
    private static final int T_CURRENT_HIGH_WATER_MILLIS = 22;
    private static final int T_TOTAL_HIGH_WATER_MILLIS = 23;
    private static final int T_LONGEST_HIGH_WATER_MILLIS = 24;
    private static final int T_RENDER_FRAMES = 25;
    private static final int T_LAST_TASK_DRAIN_COUNT = 26;
    private static final int T_PEAK_TASK_DRAIN_COUNT = 27;
    private static final int T_LAST_TASK_BUDGET_EXHAUSTED = 28;
    private static final int T_TASK_BUDGET_EXHAUSTIONS = 29;
    private static final int T_CURRENT_UPLOAD_DRAIN_COUNT = 30;
    private static final int T_LAST_UPLOAD_DRAIN_COUNT = 31;
    private static final int T_PEAK_UPLOAD_DRAIN_COUNT = 32;
    private static final int T_MAX_UPLOAD_ALLOCATIONS_PER_FRAME = 33;
    private static final int T_UPLOAD_WORK_BUDGET_MILLIS = 34;
    private static final int T_UPLOAD_BUDGET_EXHAUSTED = 35;
    private static final int T_UPLOAD_BUDGET_EXHAUSTIONS = 36;
    private static final int T_UPLOAD_ENTRY_BUDGET_EXHAUSTIONS = 37;
    private static final int T_UPLOAD_TIME_BUDGET_EXHAUSTIONS = 38;
    private static final int T_DISPATCHER_RUNNER_REQUESTS = 39;
    private static final int T_DISPATCHER_RUNNER_ENQUEUED = 40;
    private static final int T_DISPATCHER_RUNNER_COALESCED = 41;
    private static final int T_DISPATCHER_RUNNER_DISPOSALS = 42;
    private static final int T_DISPATCHER_UPLOAD_DEFERRALS = 43;
    private static final int T_ACTIVE_DISPATCHERS = 44;
    private static final int T_UPLOAD_ALLOCATIONS_DISCARDED = 45;
    private static final int T_COMPILE_RUNS_DURING_UPLOAD_THIS_FRAME = 46;
    private static final int T_COMPILE_RUNS_DURING_UPLOAD = 47;
    private static final int T_UPLOAD_FAIR_SHARE_DEFERRALS = 48;
    private static final int T_ACTIVE_UPLOAD_BUFFERS = 49;
    private static final int T_EMERGENCY_UPLOAD_REQUESTS = 50;
    private static final int T_EMERGENCY_UPLOAD_DRAINS = 51;
    private static final int T_EMERGENCY_UPLOAD_DEFERRALS = 52;
    private static final int T_UPLOAD_RETRY_YIELDS = 53;
    private static final int T_UPLOAD_RETRY_NO_PROGRESS_RESUMES = 54;
    private static final int T_UPLOAD_RETRY_CANCELLATIONS = 55;
    private static final int T_UPLOAD_RETRY_EXPIRED_STATES = 56;
    private static final int T_ACTIVE_UPLOAD_RETRY_TASKS = 57;
    private static final int T_MAX_UPLOAD_RETRY_YIELDS = 58;
    private static final int T_MAX_UPLOAD_RETRY_MILLIS = 59;
    private static final int T_UPLOAD_PROGRESS_EPOCH = 60;
    private static final int T_CURRENT_UBER_NODE_CLEANUP_SCANS = 61;
    private static final int T_LAST_UBER_NODE_CLEANUP_SCANS = 62;
    private static final int T_PEAK_UBER_NODE_CLEANUP_SCANS = 63;
    private static final int T_MAX_UBER_NODE_CLEANUP_SCANS_PER_FRAME = 64;
    private static final int T_UBER_NODE_CLEANUP_BUDGET_MILLIS = 65;
    private static final int T_UBER_NODE_CLEANUP_PASSES = 66;
    private static final int T_UBER_NODE_CLEANUP_NODES_SCANNED = 67;
    private static final int T_UBER_NODE_CLEANUP_NODES_RELEASED = 68;
    private static final int T_UBER_NODE_CLEANUP_DEFERRALS = 69;
    private static final int T_ACTIVE_UBER_NODE_CLEANUP_CURSORS = 70;
    private static final int T_FRAME_TASKS = 71;
    private static final int T_FRAME_WORK_MILLIS = 72;
    private static final int T_PUMP_WAITING_FOR_FRAME = 73;
    private static final int T_FRAME_WATCHDOG_RESUMES = 74;
    private static final int T_PUMP_SLICES = 75;
    private static final int T_UPLOAD_BYTES_THIS_FRAME = 76;
    private static final int T_UPLOAD_BYTES_PER_FRAME = 77;
    private static final int T_UPLOAD_BYTE_BUDGET_EXHAUSTIONS = 78;
    private static final int T_LAST_INSTALL_BYTES = 79;
    private static final int T_INSTALL_BYTES_PER_FRAME = 80;
    private static final int T_MESH_REQUESTS_IN_FLIGHT = 81;
    private static final int T_MESH_RESULTS_READY = 82;
    private static final int T_FAILED_TASKS = 83;
    private static final int T_COUNT = 84;
    private static final double[] TELEMETRY = new double[T_COUNT];
    /**
     * Without a diagnostic opt-in ({@link #telemetryEveryCallRequested}) the snapshot is written
     * at most once per this interval: readers poll it (acceptance, the singleplayer CDP probe)
     * and a fresher copy every frame only cost frame time.
     */
    private static final long TELEMETRY_INTERVAL_NANOS = 100_000_000L;
    private static long telemetryPublishedAtNanos;
    private static boolean telemetryPublishedOnce;
    /** The opt-in as last read; re-read at most once per interval, when a snapshot is due. */
    private static boolean telemetryEveryCall;
    /** A skipped snapshot is written when its interval ends, so the last state is not lost. */
    private static boolean telemetryFlushArmed;
    private static boolean fastProfileInitialized;
    private static boolean fastProfile;
    private static boolean frameTailProfileInitialized;
    private static boolean frameTailProfile;
    private static int frameTasks;
    private static long frameWorkNanos;
    private static boolean waitingForFrame;
    private static boolean frameWatchdogArmed;
    private static long frameWatchdogResumes;
    private static long currentUploadBytes;
    private static long uploadByteBudgetExhaustions;
    private static long lastInstallBytes;
    private static long frameInstallBytes;
    private static long frameInstallNanos;
    private static boolean installWaitingForFrame;
    private static boolean installing;
    private static Runnable meshResultPump;
    private static long pumpSlices;
    private static long failedTasks;
    private static final Deque<Runnable> QUEUE = new ArrayDeque<>();
    private static final Map<Object, Integer> UPLOAD_BACKLOGS = new IdentityHashMap<>();
    private static final Map<Object, Integer> UPLOAD_FRAME_DRAIN_COUNTS = new IdentityHashMap<>();
    private static final Map<Object, UploadRetryState> UPLOAD_RETRY_STATES = new IdentityHashMap<>();
    private static final Map<Object, Integer> UBER_NODE_CLEANUP_CURSORS = new IdentityHashMap<>();
    private static final Map<Object, DispatcherState> DISPATCHER_STATES =
            new IdentityHashMap<>();
    private static final Executor DEFERRED_EXECUTOR = BrowserRenderScheduler::enqueue;
    private static boolean pumpScheduled;
    private static boolean runningTask;
    private static int peakQueuedTasks;
    private static int compileBacklog;
    private static int peakCompileBacklog;
    private static int uploadBacklog;
    private static int peakUploadBacklog;
    private static long enqueuedTasks;
    private static long completedTasks;
    private static long backpressureEvents;
    private static long overBudgetTasks;
    private static long totalTaskNanos;
    private static long lastTaskNanos;
    private static long longestTaskNanos;
    private static long uploadPasses;
    private static long uploadPassStartedAt;
    private static long totalUploadPassNanos;
    private static long lastUploadPassNanos;
    private static long longestUploadPassNanos;
    private static long uploadAllocationsQueued;
    private static long uploadAllocationsDrained;
    private static boolean highWaterActive;
    private static long highWaterStartedAt;
    private static long totalHighWaterNanos;
    private static long longestHighWaterNanos;
    private static long renderFrames;
    private static int lastTaskDrainCount;
    private static int peakTaskDrainCount;
    private static boolean lastTaskBudgetExhausted;
    private static long taskBudgetExhaustions;
    private static boolean uploadFrameInitialized;
    private static int currentUploadDrainCount;
    private static int lastUploadDrainCount;
    private static int peakUploadDrainCount;
    private static long uploadDrainDeadlineNanos;
    private static boolean uploadBudgetExhaustedThisFrame;
    private static long uploadBudgetExhaustions;
    private static long uploadEntryBudgetExhaustions;
    private static long uploadTimeBudgetExhaustions;
    private static long dispatcherRunnerRequests;
    private static long dispatcherRunnerEnqueued;
    private static long dispatcherRunnerCoalesced;
    private static long dispatcherRunnerDisposals;
    private static long dispatcherUploadDeferrals;
    private static int compileRunsDuringUploadThisFrame;
    private static long compileRunsDuringUpload;
    private static long uploadFairShareDeferrals;
    private static long uploadAllocationsDiscarded;
    private static int emergencyUploadEntriesRemaining;
    private static boolean emergencyUploadGrantedThisFrame;
    private static long emergencyUploadRequests;
    private static long emergencyUploadDrains;
    private static long emergencyUploadDeferrals;
    private static long uploadRetryYields;
    private static long uploadRetryNoProgressResumes;
    private static long uploadRetryCancellations;
    private static long uploadRetryExpiredStates;
    private static long nextUploadRetrySweepNanos;
    private static long uploadProgressEpoch;
    private static int currentUberNodeCleanupScans;
    private static int lastUberNodeCleanupScans;
    private static int peakUberNodeCleanupScans;
    private static long uberNodeCleanupPasses;
    private static long uberNodeCleanupNodesScanned;
    private static long uberNodeCleanupNodesReleased;
    private static long uberNodeCleanupDeferrals;
    private static long uberNodeCleanupDeadlineNanos;

    private BrowserRenderScheduler() {
    }

    private static boolean fastProfile() {
        if (!fastProfileInitialized) {
            fastProfile = detectFastProfile();
            fastProfileInitialized = true;
        }
        return fastProfile;
    }

    private static Budget budget() {
        if (!frameTailProfileInitialized) {
            frameTailProfile = JsArrays.LINEAR_MEMORY && frameTailRequested();
            frameTailProfileInitialized = true;
        }
        if (frameTailProfile) return FRAME_TAIL_BUDGET;
        return fastProfile() ? FAST_BUDGET : NORMAL_BUDGET;
    }

    private static int effectiveMaxTasksPerFrame() {
        return budget().maxTasksPerFrame();
    }

    private static int effectiveQueueHighWater() {
        return budget().queueHighWater();
    }

    private static long effectiveFrameWorkBudgetNanos() {
        return budget().frameWorkNanos();
    }

    private static int effectiveMaxUploadAllocationsPerFrame() {
        return budget().maxUploadAllocationsPerFrame();
    }

    private static int effectiveCompileRunsDuringUploadPerFrame() {
        return budget().compileRunsDuringUploadPerFrame();
    }

    private static long effectiveUploadWorkBudgetNanos() {
        return budget().uploadWorkNanos();
    }

    public static Executor defer(Executor ignored) {
        return DEFERRED_EXECUTOR;
    }

    public static void executeDeferred(Executor ignored, Runnable command) {
        enqueue(command);
    }

    /** Starts the hard upload budget shared by all terrain upload calls in one rendered frame. */
    public static void beginFrame() {
        sweepExpiredUploadRetries();
        if (uploadFrameInitialized) {
            lastUploadDrainCount = currentUploadDrainCount;
            peakUploadDrainCount = Math.max(peakUploadDrainCount, currentUploadDrainCount);
        }
        uploadFrameInitialized = true;
        currentUploadDrainCount = 0;
        UPLOAD_FRAME_DRAIN_COUNTS.clear();
        compileRunsDuringUploadThisFrame = 0;
        uploadDrainDeadlineNanos = 0L;
        uploadBudgetExhaustedThisFrame = false;
        emergencyUploadEntriesRemaining = 0;
        emergencyUploadGrantedThisFrame = false;
        lastUberNodeCleanupScans = currentUberNodeCleanupScans;
        peakUberNodeCleanupScans = Math.max(
                peakUberNodeCleanupScans, currentUberNodeCleanupScans);
        currentUberNodeCleanupScans = 0;
        uberNodeCleanupDeadlineNanos = 0L;
        currentUploadBytes = 0L;
        renderFrames++;
        frameTasks = 0;
        frameWorkNanos = 0L;
        frameInstallBytes = 0L;
        frameInstallNanos = 0L;
        installWaitingForFrame = false;
        // Kernel mesh results that finished since the last frame join the install queue now.
        pumpMeshResults();
        installReadyMeshes();
        resumeAfterFrame();
    }

    /**
     * Registers the step that moves finished asynchronous meshes into BrowserMeshInstallQueue
     * (the mesh kernel's result drain, MeshKernelBridge.pumpResults). It runs at the start of
     * every frame and on the frame watchdog, ahead of the installs.
     */
    public static void setMeshResultPump(Runnable pump) {
        meshResultPump = pump;
    }

    private static void pumpMeshResults() {
        Runnable pump = meshResultPump;
        if (pump != null) {
            pump.run();
        }
    }

    /**
     * Installs ready asynchronous meshes ({@link BrowserMeshInstallQueue}) within what is left
     * of this frame's install budget, bytes and time. Runs at the start of every frame and
     * whenever a mesh producer delivers results between frames, so a result never waits for an
     * unrelated frame; whatever does not fit waits for the next frame, or for the frame
     * watchdog when no frame is rendered.
     */
    public static void installReadyMeshes() {
        if (installing || BrowserMeshInstallQueue.readyCount() == 0) {
            return;
        }
        Budget budget = budget();
        long bytesLeft = budget.installBytesPerFrame() - frameInstallBytes;
        long nanosLeft = budget.installNanosPerFrame() - frameInstallNanos;
        if (bytesLeft <= 0L || nanosLeft <= 0L) {
            waitForInstallFrame();
            return;
        }
        long startedAt = System.nanoTime();
        installing = true;
        try {
            frameInstallBytes += BrowserMeshInstallQueue.drain(bytesLeft, startedAt + nanosLeft);
        } finally {
            installing = false;
            frameInstallNanos += Math.max(0L, System.nanoTime() - startedAt);
            lastInstallBytes = frameInstallBytes;
        }
        if (BrowserMeshInstallQueue.readyCount() > 0) {
            waitForInstallFrame();
        }
    }

    /** Ready meshes are left over: install them on the next frame or the frame watchdog. */
    private static void waitForInstallFrame() {
        installWaitingForFrame = true;
        armFrameWatchdog();
    }

    /** A new frame budget is available: restart deferred dispatchers and the paused pump. */
    private static void resumeAfterFrame() {
        boolean queued = false;
        if (!DISPATCHER_STATES.isEmpty()) {
            for (DispatcherState state : DISPATCHER_STATES.values()) {
                if (state.waitingForFrame) {
                    state.waitingForFrame = false;
                    if (!state.disposed && state.requested && queueDispatcher(state)) {
                        queued = true;
                    }
                }
            }
        }
        if (waitingForFrame || queued) {
            waitingForFrame = false;
            if (!QUEUE.isEmpty()) {
                schedulePump();
            }
        }
    }

    /** Coalesces every 26.2 dispatcher onto one queued or running drain token. */
    public static void scheduleDispatcher(
            Executor ignored,
            Runnable command,
            Object dispatcher,
            int currentCompileBacklog) {
        if (dispatcher == null || command == null) {
            enqueue(command);
            return;
        }
        compileBacklog = Math.max(0, currentCompileBacklog);
        peakCompileBacklog = Math.max(peakCompileBacklog, compileBacklog);
        dispatcherRunnerRequests++;
        DispatcherState state = DISPATCHER_STATES.get(dispatcher);
        if (state == null) {
            state = new DispatcherState(dispatcher);
            DISPATCHER_STATES.put(dispatcher, state);
        }
        if (state.disposed) {
            return;
        }
        state.command = command;
        state.requested = true;
        if (state.queued || state.running) {
            dispatcherRunnerCoalesced++;
            return;
        }
        enqueueDispatcher(state);
    }

    /** Remembers vanilla's continuation without enqueuing the empty tail runner it normally adds. */
    public static void rememberDispatcherContinuation(
            Executor ignored,
            Runnable command,
            Object dispatcher) {
        DispatcherState state = DISPATCHER_STATES.get(dispatcher);
        if (state == null || state.disposed) {
            return;
        }
        state.command = command;
    }

    /** Requests another runner only when the dispatcher still owns real compile work. */
    public static void finishDispatcherRun(Object dispatcher, int currentCompileBacklog) {
        compileBacklog = Math.max(0, currentCompileBacklog);
        peakCompileBacklog = Math.max(peakCompileBacklog, compileBacklog);
        DispatcherState state = DISPATCHER_STATES.get(dispatcher);
        if (state != null && !state.disposed && compileBacklog > 0) {
            state.requested = true;
        }
    }

    /** Releases queued closures and the strong dispatcher reference during renderer disposal. */
    public static void disposeDispatcher(Object dispatcher) {
        DispatcherState state = DISPATCHER_STATES.remove(dispatcher);
        if (state == null) {
            return;
        }
        state.disposed = true;
        state.requested = false;
        state.command = null;
        state.waitingForFrame = false;
        if (state.queued) {
            QUEUE.remove(state.runner);
            state.queued = false;
        }
        if (DISPATCHER_STATES.isEmpty()) {
            compileBacklog = 0;
        }
        dispatcherRunnerDisposals++;
        updateHighWaterState();
    }

    /** Stops LevelRenderer from creating work faster than browser frames can consume it. */
    public static boolean canScheduleSection() {
        return canScheduleSection(0);
    }

    /** Accounts for section compiles selected earlier in the current extraction pass. */
    public static boolean canScheduleSection(int alreadyPlanned) {
        updateHighWaterState();
        int planned = Math.max(0, alreadyPlanned);
        int queuedWork = queuedSectionWork();
        boolean allowed = planned < budget().maxPlannedSectionsPerExtract()
                && queuedWork + planned < effectiveQueueHighWater();
        if (!allowed) {
            backpressureEvents++;
        }
        return allowed;
    }

    public static int pendingTasks() {
        return QUEUE.size() + (runningTask ? 1 : 0);
    }

    /**
     * Compile or upload work still queued anywhere in the section pipeline, including mesh
     * requests out to asynchronous producers and their results waiting for installation.
     */
    public static int queuedSectionWork() {
        return Math.max(Math.max(pendingTasks(), compileBacklog), uploadBacklog)
                + BrowserMeshInstallQueue.inFlight();
    }

    /** Staged terrain upload entries not yet copied to the GPU. */
    public static int uploadBacklog() {
        return uploadBacklog;
    }

    /**
     * Counts the bytes of one staged entry that {@link #shouldUploadNext} admitted
     * (UberGpuBuffer.uploadStagedAllocations, after the entry's size is read). Further entries
     * of the frame are refused once the frame's upload byte budget is used up.
     */
    public static void noteUploadBytes(Object buffer, long bytes) {
        if (bytes > 0L) {
            currentUploadBytes += bytes;
        }
    }

    public static int peakQueuedTasks() {
        return peakQueuedTasks;
    }

    public static long longestTaskNanos() {
        return longestTaskNanos;
    }

    /** Records the dispatcher queue and begins timing one terrain upload pass. */
    public static void beginUploadPass(int currentCompileBacklog) {
        ensureUploadFrameBudget();
        compileBacklog = Math.max(0, currentCompileBacklog);
        peakCompileBacklog = Math.max(peakCompileBacklog, compileBacklog);
        uploadPassStartedAt = System.nanoTime();
    }

    /** Finishes terrain upload timing and publishes one coherent pipeline snapshot. */
    public static void endUploadPass() {
        if (uploadPassStartedAt != 0L) {
            lastUploadPassNanos = Math.max(0L, System.nanoTime() - uploadPassStartedAt);
            uploadPassStartedAt = 0L;
            uploadPasses++;
            totalUploadPassNanos += lastUploadPassNanos;
            longestUploadPassNanos = Math.max(longestUploadPassNanos, lastUploadPassNanos);
        }
        if (uploadBacklog > 0
                && currentUploadDrainCount >= effectiveMaxUploadAllocationsPerFrame()) {
            markUploadBudgetExhausted(false);
        } else if (uploadBacklog > 0
                && uploadDrainDeadlineNanos != 0L
                && System.nanoTime() >= uploadDrainDeadlineNanos) {
            markUploadBudgetExhausted(true);
        }
        emergencyUploadEntriesRemaining = 0;
        publishTelemetry();
    }

    /** Guarantees one staged entry can drain when a full staging buffer blocks compilation. */
    public static void requestEmergencyUpload() {
        ensureUploadFrameBudget();
        emergencyUploadRequests++;
        long now = System.nanoTime();
        boolean hardLimitReached =
                currentUploadDrainCount >= effectiveMaxUploadAllocationsPerFrame();
        boolean timeLimitReached = uploadDrainDeadlineNanos != 0L
                && now >= uploadDrainDeadlineNanos;
        if (emergencyUploadGrantedThisFrame || hardLimitReached || timeLimitReached) {
            emergencyUploadDeferrals++;
            if (hardLimitReached || timeLimitReached) {
                markUploadBudgetExhausted(timeLimitReached);
            }
            return;
        }
        emergencyUploadGrantedThisFrame = true;
        emergencyUploadEntriesRemaining = 1;
    }

    /** Suspends a failed staging retry and bounds how long its mesh can remain retained. */
    public static boolean awaitUploadRetry(Object task) {
        if (task == null) {
            return false;
        }
        long now = System.nanoTime();
        UploadRetryState state = UPLOAD_RETRY_STATES.get(task);
        if (state == null) {
            state = new UploadRetryState(now);
            UPLOAD_RETRY_STATES.put(task, state);
        }
        if (state.terminal) {
            state.lastTouchedAtNanos = now;
            return false;
        }
        state.lastTouchedAtNanos = now;
        long progressBeforeYield = uploadProgressEpoch;
        uploadRetryYields++;
        state.yields++;
        TModernRuntimeSupport.yieldToEventLoop(1);
        if (uploadProgressEpoch == progressBeforeYield) {
            uploadRetryNoProgressResumes++;
        }
        now = System.nanoTime();
        state.lastTouchedAtNanos = now;
        if (state.yields >= MAX_UPLOAD_RETRY_YIELDS
                || now - state.startedAtNanos >= MAX_UPLOAD_RETRY_NANOS) {
            terminateUploadRetry(state, now, false);
            return false;
        }
        return true;
    }

    public static void clearUploadRetry(Object task) {
        if (task != null) {
            UPLOAD_RETRY_STATES.remove(task);
        }
    }

    private static void sweepExpiredUploadRetries() {
        if (UPLOAD_RETRY_STATES.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextUploadRetrySweepNanos) {
            return;
        }
        nextUploadRetrySweepNanos = now + UPLOAD_RETRY_SWEEP_INTERVAL_NANOS;
        sweepExpiredUploadRetries(now);
    }

    private static void sweepExpiredUploadRetries(long now) {
        Iterator<Map.Entry<Object, UploadRetryState>> iterator =
                UPLOAD_RETRY_STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            UploadRetryState state = iterator.next().getValue();
            if (state == null) {
                iterator.remove();
                continue;
            }
            if (!state.terminal) {
                if (now - state.startedAtNanos >= MAX_UPLOAD_RETRY_NANOS) {
                    terminateUploadRetry(state, now, true);
                }
                continue;
            }
            if (now - state.lastTouchedAtNanos >= UPLOAD_RETRY_TOMBSTONE_IDLE_NANOS) {
                iterator.remove();
            }
        }
    }

    private static void terminateUploadRetry(
            UploadRetryState state,
            long now,
            boolean expiredBySweep) {
        if (state.terminal) {
            return;
        }
        state.terminal = true;
        state.lastTouchedAtNanos = now;
        uploadRetryCancellations++;
        if (expiredBySweep) {
            uploadRetryExpiredStates++;
        }
    }

    /** Called immediately before consuming one 26.2 staged-allocation map entry. */
    public static boolean shouldUploadNext(Object buffer) {
        if (buffer == null) {
            return false;
        }
        ensureUploadFrameBudget();
        long now = System.nanoTime();
        if (currentUploadDrainCount >= effectiveMaxUploadAllocationsPerFrame()) {
            emergencyUploadEntriesRemaining = 0;
            markUploadBudgetExhausted(false);
            return false;
        }
        if (currentUploadBytes >= budget().uploadBytesPerFrame()
                && emergencyUploadEntriesRemaining == 0) {
            if (!uploadBudgetExhaustedThisFrame) {
                uploadByteBudgetExhaustions++;
            }
            markUploadBudgetExhausted(false);
            return false;
        }
        if (uploadDrainDeadlineNanos == 0L) {
            uploadDrainDeadlineNanos = now + effectiveUploadWorkBudgetNanos();
        } else if (now >= uploadDrainDeadlineNanos) {
            emergencyUploadEntriesRemaining = 0;
            markUploadBudgetExhausted(true);
            return false;
        }
        if (emergencyUploadEntriesRemaining > 0) {
            emergencyUploadEntriesRemaining--;
            emergencyUploadDrains++;
            currentUploadDrainCount++;
            int bufferDrainCount = UPLOAD_FRAME_DRAIN_COUNTS.getOrDefault(buffer, 0) + 1;
            UPLOAD_FRAME_DRAIN_COUNTS.put(buffer, bufferDrainCount);
            peakUploadDrainCount = Math.max(peakUploadDrainCount, currentUploadDrainCount);
            return true;
        }
        int activeUploadBuffers = 0;
        for (Integer backlog : UPLOAD_BACKLOGS.values()) {
            if (backlog != null && backlog > 0) {
                activeUploadBuffers++;
            }
        }
        activeUploadBuffers = Math.max(1, activeUploadBuffers);
        int fairShare = Math.max(
                1, effectiveMaxUploadAllocationsPerFrame() / activeUploadBuffers);
        int bufferDrainCount = UPLOAD_FRAME_DRAIN_COUNTS.getOrDefault(buffer, 0);
        if (activeUploadBuffers > 1 && bufferDrainCount >= fairShare) {
            uploadFairShareDeferrals++;
            return false;
        }
        currentUploadDrainCount++;
        UPLOAD_FRAME_DRAIN_COUNTS.put(buffer, bufferDrainCount + 1);
        peakUploadDrainCount = Math.max(peakUploadDrainCount, currentUploadDrainCount);
        return true;
    }

    /** Keeps skipped markers only for entries that remain staged for a later frame. */
    public static void finishUploadBuffer(
            Object buffer,
            Map<?, ?> stagedAllocations,
            Set<?> skippedStagedAllocations) {
        if (stagedAllocations == null || skippedStagedAllocations == null) {
            return;
        }
        skippedStagedAllocations.retainAll(stagedAllocations.keySet());
    }

    /** Drops the telemetry map's strong reference before UberGpuBuffer closes its allocations. */
    public static void releaseUploadBuffer(Object buffer) {
        Integer previousBacklog = UPLOAD_BACKLOGS.remove(buffer);
        UPLOAD_FRAME_DRAIN_COUNTS.remove(buffer);
        UBER_NODE_CLEANUP_CURSORS.remove(buffer);
        if (previousBacklog == null) {
            return;
        }
        int released = Math.max(0, previousBacklog);
        uploadBacklog = Math.max(0, uploadBacklog - released);
        uploadAllocationsDiscarded += released;
    }

    /**
     * Tracks the exact staged-allocation map size for each 26.2 UberGpuBuffer while preserving
     * the boolean already on that method's operand stack.
     */
    public static boolean recordUploadBacklogResult(
            boolean result,
            Object buffer,
            int currentBacklog) {
        if (buffer == null) {
            return result;
        }
        int boundedBacklog = Math.max(0, currentBacklog);
        Integer previousValue = UPLOAD_BACKLOGS.get(buffer);
        int previousBacklog = previousValue == null ? 0 : previousValue;
        int delta = boundedBacklog - previousBacklog;
        if (delta > 0) {
            uploadAllocationsQueued += delta;
        } else if (delta < 0) {
            uploadAllocationsDrained -= delta;
            uploadProgressEpoch -= delta;
        }
        uploadBacklog = Math.max(0, uploadBacklog + delta);
        peakUploadBacklog = Math.max(peakUploadBacklog, uploadBacklog);
        if (boundedBacklog == 0) {
            UPLOAD_BACKLOGS.remove(buffer);
        } else {
            UPLOAD_BACKLOGS.put(buffer, boundedBacklog);
        }
        return result;
    }

    /** Returns the stable cleanup cursor for one UberGpuBuffer's reusable heap list. */
    public static int beginUberNodeCleanup(Object buffer, int nodeCount) {
        int safeCount = Math.max(0, nodeCount);
        if (buffer == null || safeCount == 0) {
            if (buffer != null) {
                UBER_NODE_CLEANUP_CURSORS.remove(buffer);
            }
            return 0;
        }
        Integer previous = UBER_NODE_CLEANUP_CURSORS.get(buffer);
        int cursor = previous == null ? 0 : Math.max(0, previous);
        return cursor % safeCount;
    }

    /** Enforces one frame-wide time and entry ceiling across all UberGpuBuffer heap cleanup. */
    public static boolean shouldCleanUberNode(Object buffer) {
        if (buffer == null) {
            return false;
        }
        ensureUploadFrameBudget();
        long now = System.nanoTime();
        if (currentUberNodeCleanupScans >= MAX_UBER_NODE_CLEANUP_SCANS_PER_FRAME) {
            uberNodeCleanupDeferrals++;
            return false;
        }
        if (uberNodeCleanupDeadlineNanos == 0L) {
            uberNodeCleanupDeadlineNanos = now + UBER_NODE_CLEANUP_BUDGET_NANOS;
        } else if (now >= uberNodeCleanupDeadlineNanos) {
            uberNodeCleanupDeferrals++;
            return false;
        }
        currentUberNodeCleanupScans++;
        peakUberNodeCleanupScans = Math.max(
                peakUberNodeCleanupScans, currentUberNodeCleanupScans);
        return true;
    }

    /** Persists the next index only while the owning UberGpuBuffer still retains heap nodes. */
    public static void finishUberNodeCleanup(
            Object buffer,
            int nextCursor,
            int remainingNodes,
            int scanned,
            int released) {
        if (buffer == null) {
            return;
        }
        int safeRemaining = Math.max(0, remainingNodes);
        int safeScanned = Math.max(0, scanned);
        int safeReleased = Math.max(0, Math.min(released, safeScanned));
        uberNodeCleanupPasses++;
        uberNodeCleanupNodesScanned += safeScanned;
        uberNodeCleanupNodesReleased += safeReleased;
        if (safeRemaining == 0) {
            UBER_NODE_CLEANUP_CURSORS.remove(buffer);
            return;
        }
        UBER_NODE_CLEANUP_CURSORS.put(
                buffer,
                Math.floorMod(Math.max(0, nextCursor), safeRemaining));
    }

    private static void enqueue(Runnable command) {
        if (command == null) {
            return;
        }
        QUEUE.addLast(command);
        enqueuedTasks++;
        peakQueuedTasks = Math.max(peakQueuedTasks, QUEUE.size());
        updateHighWaterState();
        if (QUEUE.size() == 1) {
            publishTelemetry();
        }
        schedulePump();
    }

    private static void enqueueDispatcher(DispatcherState state) {
        if (queueDispatcher(state)) {
            schedulePump();
        }
    }

    private static boolean queueDispatcher(DispatcherState state) {
        if (state.disposed || state.queued || state.running
                || state.waitingForFrame || state.command == null) {
            return false;
        }
        state.queued = true;
        QUEUE.addFirst(state.runner);
        enqueuedTasks++;
        dispatcherRunnerEnqueued++;
        peakQueuedTasks = Math.max(peakQueuedTasks, QUEUE.size());
        updateHighWaterState();
        return true;
    }

    private static void runDispatcher(DispatcherState state) {
        state.queued = false;
        if (state.disposed || DISPATCHER_STATES.get(state.dispatcher) != state) {
            return;
        }
        Runnable command = state.command;
        state.requested = false;
        state.running = true;
        try {
            if (uploadBacklog > 0
                    && compileRunsDuringUploadThisFrame
                    >= effectiveCompileRunsDuringUploadPerFrame()) {
                state.requested = true;
                deferDispatcherUntilNextFrame(state);
                dispatcherUploadDeferrals++;
            } else if (command != null) {
                if (uploadBacklog > 0) {
                    compileRunsDuringUploadThisFrame++;
                    compileRunsDuringUpload++;
                }
                command.run();
            }
        } finally {
            state.running = false;
            if (!state.disposed
                    && DISPATCHER_STATES.get(state.dispatcher) == state
                    && !state.waitingForFrame
                    && state.requested) {
                enqueueDispatcher(state);
            }
        }
    }

    /** Holds a dispatcher back until the next frame grants compile runs again. */
    private static void deferDispatcherUntilNextFrame(DispatcherState state) {
        if (state.waitingForFrame) {
            return;
        }
        state.waitingForFrame = true;
        armFrameWatchdog();
    }

    /** Starts the pump continuation unless it runs already or the frame budget is used up. */
    private static void schedulePump() {
        if (pumpScheduled) {
            return;
        }
        if (frameBudgetExhausted()) {
            waitForFrame();
            return;
        }
        pumpScheduled = true;
        // A TeaVM thread, not a plain timeout callback: the pump yields between slices and
        // queued tasks may suspend (upload retries), which needs a coroutine context.
        HostLoop.startThread(BrowserRenderScheduler::runPump);
    }

    /**
     * The pump: slices of queued work separated by one MessageChannel turn each, until the
     * queue is empty or the frame budget is used up.
     */
    private static void runPump() {
        try {
            while (!QUEUE.isEmpty()) {
                if (frameBudgetExhausted()) {
                    waitForFrame();
                    break;
                }
                runSlice();
                if (QUEUE.isEmpty()) {
                    break;
                }
                TModernRuntimeSupport.yieldToEventLoop(0);
            }
        } finally {
            pumpScheduled = false;
            publishTelemetry();
            // A slice that ended early on an exception must not strand the remaining work.
            if (!QUEUE.isEmpty() && !waitingForFrame) {
                schedulePump();
            }
        }
    }

    private static void runSlice() {
        long sliceStartedAt = System.nanoTime();
        int completed = 0;
        pumpSlices++;
        try {
            while (!QUEUE.isEmpty()
                    && shouldContinueDrain(frameTasks, frameWorkNanos)) {
                Runnable command = QUEUE.pollFirst();
                long taskStartedAt = System.nanoTime();
                runningTask = true;
                updateHighWaterState();
                try {
                    command.run();
                } catch (Throwable error) {
                    // One failing task must not end the slice; keep it visible in the log.
                    failedTasks++;
                    if (failedTasks <= MAX_LOGGED_TASK_FAILURES) {
                        System.err.println("[Gaius] render task failed (" + failedTasks + "): " + error);
                        error.printStackTrace();
                    }
                } finally {
                    runningTask = false;
                    lastTaskNanos = Math.max(0L, System.nanoTime() - taskStartedAt);
                    completedTasks++;
                    totalTaskNanos += lastTaskNanos;
                    longestTaskNanos = Math.max(longestTaskNanos, lastTaskNanos);
                    if (lastTaskNanos > effectiveFrameWorkBudgetNanos()) {
                        overBudgetTasks++;
                    }
                    frameTasks++;
                    frameWorkNanos += lastTaskNanos;
                    updateHighWaterState();
                }
                completed++;
                if (System.nanoTime() - sliceStartedAt >= SLICE_NANOS) {
                    break;
                }
            }
        } finally {
            lastTaskDrainCount = completed;
            peakTaskDrainCount = Math.max(peakTaskDrainCount, completed);
            lastTaskBudgetExhausted = !QUEUE.isEmpty();
            if (lastTaskBudgetExhausted) {
                taskBudgetExhaustions++;
            }
        }
    }

    /**
     * Frame budget rule: up to the task cap per frame, and after the first task only while
     * the frame's accumulated work time is below the budget (one long compile always runs).
     */
    static boolean shouldContinueDrain(int completed, long elapsedNanos) {
        return completed < effectiveMaxTasksPerFrame()
                && (completed == 0 || elapsedNanos < effectiveFrameWorkBudgetNanos());
    }

    private static boolean frameBudgetExhausted() {
        return !shouldContinueDrain(frameTasks, frameWorkNanos);
    }

    private static void waitForFrame() {
        waitingForFrame = true;
        armFrameWatchdog();
    }

    /**
     * Resumes paused work, compile runs and mesh installs, when no frame is rendered for a
     * while (paused world rendering).
     */
    private static void armFrameWatchdog() {
        if (frameWatchdogArmed) {
            return;
        }
        frameWatchdogArmed = true;
        long framesAtArm = renderFrames;
        HostLoop.schedule(() -> {
            frameWatchdogArmed = false;
            if (renderFrames != framesAtArm) {
                // A rendered frame reset the install budget and installed what fit.
                if (waitingForFrame || anyDispatcherWaiting()) {
                    armFrameWatchdog();
                }
                return;
            }
            if (!waitingForFrame && !anyDispatcherWaiting() && !installWaitingForFrame) {
                return;
            }
            frameWatchdogResumes++;
            frameTasks = 0;
            frameWorkNanos = 0L;
            compileRunsDuringUploadThisFrame = 0;
            if (installWaitingForFrame) {
                installWaitingForFrame = false;
                frameInstallBytes = 0L;
                frameInstallNanos = 0L;
                pumpMeshResults();
                installReadyMeshes();
            }
            resumeAfterFrame();
        }, FRAME_WATCHDOG_MILLIS);
    }

    private static boolean anyDispatcherWaiting() {
        for (DispatcherState state : DISPATCHER_STATES.values()) {
            if (state.waitingForFrame && !state.disposed) {
                return true;
            }
        }
        return false;
    }

    private static void ensureUploadFrameBudget() {
        if (uploadFrameInitialized) {
            return;
        }
        uploadFrameInitialized = true;
        currentUploadDrainCount = 0;
        UPLOAD_FRAME_DRAIN_COUNTS.clear();
        compileRunsDuringUploadThisFrame = 0;
        uploadDrainDeadlineNanos = 0L;
        uploadBudgetExhaustedThisFrame = false;
        emergencyUploadEntriesRemaining = 0;
        emergencyUploadGrantedThisFrame = false;
        currentUberNodeCleanupScans = 0;
        uberNodeCleanupDeadlineNanos = 0L;
        currentUploadBytes = 0L;
    }

    private static void markUploadBudgetExhausted(boolean timeBudget) {
        if (uploadBudgetExhaustedThisFrame) {
            return;
        }
        uploadBudgetExhaustedThisFrame = true;
        uploadBudgetExhaustions++;
        if (timeBudget) {
            uploadTimeBudgetExhaustions++;
        } else {
            uploadEntryBudgetExhaustions++;
        }
    }

    /**
     * Writes the snapshot when one is due: on every call while a diagnostic run asks for it
     * (the chunk benchmark's per-frame histograms), otherwise at most once per
     * {@link #TELEMETRY_INTERVAL_NANOS}; a skipped call arms one write at the end of the
     * interval, so the snapshot never lags the state by more than the interval. The counters
     * themselves are kept on every call.
     */
    private static void publishTelemetry() {
        updateHighWaterState();
        long now = System.nanoTime();
        boolean intervalDue = !telemetryPublishedOnce
                || now - telemetryPublishedAtNanos >= TELEMETRY_INTERVAL_NANOS;
        if (intervalDue) {
            telemetryEveryCall = telemetryEveryCallRequested();
            telemetryPublishedAtNanos = now;
            telemetryPublishedOnce = true;
        } else if (!telemetryEveryCall) {
            armTelemetryFlush(now);
            return;
        }
        long activeHighWaterNanos = highWaterActive
                ? Math.max(0L, System.nanoTime() - highWaterStartedAt)
                : 0L;
        Budget budget = budget();
        double[] t = TELEMETRY;
        t[T_PENDING_TASKS] = pendingTasks();
        t[T_QUEUE_CAPACITY] = effectiveQueueHighWater();
        t[T_TASK_RUNNING] = runningTask ? 1 : 0;
        t[T_PEAK_PENDING_TASKS] = peakQueuedTasks;
        t[T_COMPILE_BACKLOG] = compileBacklog;
        t[T_PEAK_COMPILE_BACKLOG] = peakCompileBacklog;
        t[T_UPLOAD_BACKLOG] = uploadBacklog;
        t[T_PEAK_UPLOAD_BACKLOG] = peakUploadBacklog;
        t[T_ENQUEUED_TASKS] = enqueuedTasks;
        t[T_COMPLETED_TASKS] = completedTasks;
        t[T_BACKPRESSURE_EVENTS] = backpressureEvents;
        t[T_OVER_BUDGET_TASKS] = overBudgetTasks;
        t[T_LAST_TASK_MILLIS] = nanosToMillis(lastTaskNanos);
        t[T_LONGEST_TASK_MILLIS] = nanosToMillis(longestTaskNanos);
        t[T_TOTAL_TASK_MILLIS] = nanosToMillis(totalTaskNanos);
        t[T_UPLOAD_PASSES] = uploadPasses;
        t[T_LAST_UPLOAD_PASS_MILLIS] = nanosToMillis(lastUploadPassNanos);
        t[T_LONGEST_UPLOAD_PASS_MILLIS] = nanosToMillis(longestUploadPassNanos);
        t[T_TOTAL_UPLOAD_PASS_MILLIS] = nanosToMillis(totalUploadPassNanos);
        t[T_UPLOAD_ALLOCATIONS_QUEUED] = uploadAllocationsQueued;
        t[T_UPLOAD_ALLOCATIONS_DRAINED] = uploadAllocationsDrained;
        t[T_HIGH_WATER_ACTIVE] = highWaterActive ? 1 : 0;
        t[T_CURRENT_HIGH_WATER_MILLIS] = nanosToMillis(activeHighWaterNanos);
        t[T_TOTAL_HIGH_WATER_MILLIS] = nanosToMillis(totalHighWaterNanos + activeHighWaterNanos);
        t[T_LONGEST_HIGH_WATER_MILLIS] = nanosToMillis(Math.max(longestHighWaterNanos, activeHighWaterNanos));
        t[T_RENDER_FRAMES] = renderFrames;
        t[T_LAST_TASK_DRAIN_COUNT] = lastTaskDrainCount;
        t[T_PEAK_TASK_DRAIN_COUNT] = peakTaskDrainCount;
        t[T_LAST_TASK_BUDGET_EXHAUSTED] = lastTaskBudgetExhausted ? 1 : 0;
        t[T_TASK_BUDGET_EXHAUSTIONS] = taskBudgetExhaustions;
        t[T_CURRENT_UPLOAD_DRAIN_COUNT] = currentUploadDrainCount;
        t[T_LAST_UPLOAD_DRAIN_COUNT] = lastUploadDrainCount;
        t[T_PEAK_UPLOAD_DRAIN_COUNT] = peakUploadDrainCount;
        t[T_MAX_UPLOAD_ALLOCATIONS_PER_FRAME] = effectiveMaxUploadAllocationsPerFrame();
        t[T_UPLOAD_WORK_BUDGET_MILLIS] = nanosToMillis(effectiveUploadWorkBudgetNanos());
        t[T_UPLOAD_BUDGET_EXHAUSTED] = uploadBudgetExhaustedThisFrame ? 1 : 0;
        t[T_UPLOAD_BUDGET_EXHAUSTIONS] = uploadBudgetExhaustions;
        t[T_UPLOAD_ENTRY_BUDGET_EXHAUSTIONS] = uploadEntryBudgetExhaustions;
        t[T_UPLOAD_TIME_BUDGET_EXHAUSTIONS] = uploadTimeBudgetExhaustions;
        t[T_DISPATCHER_RUNNER_REQUESTS] = dispatcherRunnerRequests;
        t[T_DISPATCHER_RUNNER_ENQUEUED] = dispatcherRunnerEnqueued;
        t[T_DISPATCHER_RUNNER_COALESCED] = dispatcherRunnerCoalesced;
        t[T_DISPATCHER_RUNNER_DISPOSALS] = dispatcherRunnerDisposals;
        t[T_DISPATCHER_UPLOAD_DEFERRALS] = dispatcherUploadDeferrals;
        t[T_ACTIVE_DISPATCHERS] = DISPATCHER_STATES.size();
        t[T_UPLOAD_ALLOCATIONS_DISCARDED] = uploadAllocationsDiscarded;
        t[T_COMPILE_RUNS_DURING_UPLOAD_THIS_FRAME] = compileRunsDuringUploadThisFrame;
        t[T_COMPILE_RUNS_DURING_UPLOAD] = compileRunsDuringUpload;
        t[T_UPLOAD_FAIR_SHARE_DEFERRALS] = uploadFairShareDeferrals;
        t[T_ACTIVE_UPLOAD_BUFFERS] = UPLOAD_FRAME_DRAIN_COUNTS.size();
        t[T_EMERGENCY_UPLOAD_REQUESTS] = emergencyUploadRequests;
        t[T_EMERGENCY_UPLOAD_DRAINS] = emergencyUploadDrains;
        t[T_EMERGENCY_UPLOAD_DEFERRALS] = emergencyUploadDeferrals;
        t[T_UPLOAD_RETRY_YIELDS] = uploadRetryYields;
        t[T_UPLOAD_RETRY_NO_PROGRESS_RESUMES] = uploadRetryNoProgressResumes;
        t[T_UPLOAD_RETRY_CANCELLATIONS] = uploadRetryCancellations;
        t[T_UPLOAD_RETRY_EXPIRED_STATES] = uploadRetryExpiredStates;
        t[T_ACTIVE_UPLOAD_RETRY_TASKS] = UPLOAD_RETRY_STATES.size();
        t[T_MAX_UPLOAD_RETRY_YIELDS] = MAX_UPLOAD_RETRY_YIELDS;
        t[T_MAX_UPLOAD_RETRY_MILLIS] = nanosToMillis(MAX_UPLOAD_RETRY_NANOS);
        t[T_UPLOAD_PROGRESS_EPOCH] = uploadProgressEpoch;
        t[T_CURRENT_UBER_NODE_CLEANUP_SCANS] = currentUberNodeCleanupScans;
        t[T_LAST_UBER_NODE_CLEANUP_SCANS] = lastUberNodeCleanupScans;
        t[T_PEAK_UBER_NODE_CLEANUP_SCANS] = peakUberNodeCleanupScans;
        t[T_MAX_UBER_NODE_CLEANUP_SCANS_PER_FRAME] = MAX_UBER_NODE_CLEANUP_SCANS_PER_FRAME;
        t[T_UBER_NODE_CLEANUP_BUDGET_MILLIS] = nanosToMillis(UBER_NODE_CLEANUP_BUDGET_NANOS);
        t[T_UBER_NODE_CLEANUP_PASSES] = uberNodeCleanupPasses;
        t[T_UBER_NODE_CLEANUP_NODES_SCANNED] = uberNodeCleanupNodesScanned;
        t[T_UBER_NODE_CLEANUP_NODES_RELEASED] = uberNodeCleanupNodesReleased;
        t[T_UBER_NODE_CLEANUP_DEFERRALS] = uberNodeCleanupDeferrals;
        t[T_ACTIVE_UBER_NODE_CLEANUP_CURSORS] = UBER_NODE_CLEANUP_CURSORS.size();
        t[T_FRAME_TASKS] = frameTasks;
        t[T_FRAME_WORK_MILLIS] = nanosToMillis(frameWorkNanos);
        t[T_PUMP_WAITING_FOR_FRAME] = waitingForFrame ? 1 : 0;
        t[T_FRAME_WATCHDOG_RESUMES] = frameWatchdogResumes;
        t[T_PUMP_SLICES] = pumpSlices;
        t[T_UPLOAD_BYTES_THIS_FRAME] = currentUploadBytes;
        t[T_UPLOAD_BYTES_PER_FRAME] = budget.uploadBytesPerFrame();
        t[T_UPLOAD_BYTE_BUDGET_EXHAUSTIONS] = uploadByteBudgetExhaustions;
        t[T_LAST_INSTALL_BYTES] = lastInstallBytes;
        t[T_INSTALL_BYTES_PER_FRAME] = budget.installBytesPerFrame();
        t[T_MESH_REQUESTS_IN_FLIGHT] = BrowserMeshInstallQueue.inFlight();
        t[T_MESH_RESULTS_READY] = BrowserMeshInstallQueue.readyCount();
        t[T_FAILED_TASKS] = failedTasks;
        publishTelemetryJs(JsArrays.float64(t));
    }

    private static void armTelemetryFlush(long now) {
        if (telemetryFlushArmed) {
            return;
        }
        telemetryFlushArmed = true;
        long remainingNanos = TELEMETRY_INTERVAL_NANOS - (now - telemetryPublishedAtNanos);
        int delayMillis = (int) Math.max(1L, remainingNanos / 1_000_000L + 1L);
        HostLoop.schedule(() -> {
            telemetryFlushArmed = false;
            publishTelemetry();
        }, delayMillis);
    }

    private static void updateHighWaterState() {
        boolean atHighWater = pendingTasks() >= effectiveQueueHighWater();
        if (atHighWater == highWaterActive) {
            return;
        }
        long now = System.nanoTime();
        if (atHighWater) {
            highWaterActive = true;
            highWaterStartedAt = now;
            return;
        }
        long elapsed = Math.max(0L, now - highWaterStartedAt);
        totalHighWaterNanos += elapsed;
        longestHighWaterNanos = Math.max(longestHighWaterNanos, elapsed);
        highWaterActive = false;
        highWaterStartedAt = 0L;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * The per-call snapshot opt-in: a benchmark run ({@code __gaiusBenchmarkEnabled}, which also
     * enables the frame and targeting telemetry), enabled frame telemetry, or
     * {@code __gaiusChunkPipelineTelemetryEveryFrame} for a manual diagnosis.
     */
    @JSBody(script = """
            const frame = globalThis.__gaiusFrameTelemetry;
            return globalThis.__gaiusBenchmarkEnabled === true
              || globalThis.__gaiusChunkPipelineTelemetryEveryFrame === true
              || !!(frame && frame.enabled === true);
            """)
    private static native boolean telemetryEveryCallRequested();

    @JSBody(script = """
            const query = String(globalThis.location && globalThis.location.search || '');
            if (query.includes('gaiusRenderFast=1')) return true;
            const cores = Number(globalThis.navigator && globalThis.navigator.hardwareConcurrency || 0);
            return cores >= 4 && !query.includes('gaiusRenderFast=0');
            """)
    private static native boolean detectFastProfile();

    @JSBody(script = """
            const params=new URLSearchParams(globalThis.location ? location.search : '');
            return /^(1|true|on)$/i.test(params.get('gaiusGlFrameBudget') || '');
            """)
    private static native boolean frameTailRequested();

    /**
     * Writes {@code globalThis.__gaiusChunkPipelineTelemetry} from one packed snapshot: index
     * {@code T_X} holds field {@code x} (booleans as 0/1). One typed array instead of 84
     * arguments keeps the call under TeaVM's WebAssembly GC limit of 32 JSBody parameters, so
     * both targets publish the same object.
     */
    @JSBody(params = "v", script = """
            const state=globalThis.__gaiusChunkPipelineTelemetry ||
              (globalThis.__gaiusChunkPipelineTelemetry={});
            const recordDuration=function(kind,count,duration) {
              const countKey=kind+'HistogramCount';
              const previous=Number(state[countKey])||0;
              const current=Number(count)||0;
              const added=Math.max(0,current-previous);
              let histogram=state[kind+'Histogram'];
              if (!histogram || histogram.length!==4001) {
                histogram=new Uint32Array(4001);
                state[kind+'Histogram']=histogram;
              }
              if (added>0 && Number.isFinite(duration) && duration>=0) {
                const bucket=Math.min(4000,Math.floor(Number(duration)*4));
                histogram[bucket]=histogram[bucket]+added;
              }
              state[countKey]=current;
            };
            recordDuration('task',v[9],v[12]);
            recordDuration('uploadPass',v[15],v[16]);
            state.pendingTasks=v[0]||0;
            state.queueCapacity=v[1]||0;
            state.taskRunning=!!v[2];
            state.peakPendingTasks=v[3]||0;
            state.compileBacklog=v[4]||0;
            state.peakCompileBacklog=v[5]||0;
            state.uploadBacklog=v[6]||0;
            state.peakUploadBacklog=v[7]||0;
            state.enqueuedTasks=v[8]||0;
            state.completedTasks=v[9]||0;
            state.backpressureEvents=v[10]||0;
            state.overBudgetTasks=v[11]||0;
            state.lastTaskMillis=v[12]||0;
            state.longestTaskMillis=v[13]||0;
            state.totalTaskMillis=v[14]||0;
            state.uploadPasses=v[15]||0;
            state.lastUploadPassMillis=v[16]||0;
            state.longestUploadPassMillis=v[17]||0;
            state.totalUploadPassMillis=v[18]||0;
            state.uploadAllocationsQueued=v[19]||0;
            state.uploadAllocationsDrained=v[20]||0;
            state.highWaterActive=!!v[21];
            state.currentHighWaterMillis=v[22]||0;
            state.totalHighWaterMillis=v[23]||0;
            state.longestHighWaterMillis=v[24]||0;
            state.renderFrames=v[25]||0;
            state.lastTaskDrainCount=v[26]||0;
            state.peakTaskDrainCount=v[27]||0;
            state.lastTaskBudgetExhausted=!!v[28];
            state.taskBudgetExhaustions=v[29]||0;
            state.currentUploadDrainCount=v[30]||0;
            state.lastUploadDrainCount=v[31]||0;
            state.peakUploadDrainCount=v[32]||0;
            state.maxUploadAllocationsPerFrame=v[33]||0;
            state.uploadWorkBudgetMillis=v[34]||0;
            state.uploadBudgetExhausted=!!v[35];
            state.uploadBudgetExhaustions=v[36]||0;
            state.uploadEntryBudgetExhaustions=v[37]||0;
            state.uploadTimeBudgetExhaustions=v[38]||0;
            state.dispatcherRunnerRequests=v[39]||0;
            state.dispatcherRunnerEnqueued=v[40]||0;
            state.dispatcherRunnerCoalesced=v[41]||0;
            state.dispatcherRunnerDisposals=v[42]||0;
            state.dispatcherUploadDeferrals=v[43]||0;
            state.activeDispatchers=v[44]||0;
            state.uploadAllocationsDiscarded=v[45]||0;
            state.compileRunsDuringUploadThisFrame=v[46]||0;
            state.compileRunsDuringUpload=v[47]||0;
            state.uploadFairShareDeferrals=v[48]||0;
            state.activeUploadBuffers=v[49]||0;
            state.emergencyUploadRequests=v[50]||0;
            state.emergencyUploadDrains=v[51]||0;
            state.emergencyUploadDeferrals=v[52]||0;
            state.uploadRetryYields=v[53]||0;
            state.uploadRetryNoProgressResumes=v[54]||0;
            state.uploadRetryCancellations=v[55]||0;
            state.uploadRetryExpiredStates=v[56]||0;
            state.activeUploadRetryTasks=v[57]||0;
            state.maxUploadRetryYields=v[58]||0;
            state.maxUploadRetryMillis=v[59]||0;
            state.uploadProgressEpoch=v[60]||0;
            state.currentUberNodeCleanupScans=v[61]||0;
            state.lastUberNodeCleanupScans=v[62]||0;
            state.peakUberNodeCleanupScans=v[63]||0;
            state.maxUberNodeCleanupScansPerFrame=v[64]||0;
            state.uberNodeCleanupBudgetMillis=v[65]||0;
            state.uberNodeCleanupPasses=v[66]||0;
            state.uberNodeCleanupNodesScanned=v[67]||0;
            state.uberNodeCleanupNodesReleased=v[68]||0;
            state.uberNodeCleanupDeferrals=v[69]||0;
            state.activeUberNodeCleanupCursors=v[70]||0;
            state.droppedTasks=0;
            state.updatedAt=(typeof performance!=='undefined' && performance.now)
              ? performance.now() : Date.now();
            state.frameTasks=v[71]||0;
            state.frameWorkMillis=v[72]||0;
            state.pumpWaitingForFrame=!!v[73];
            state.frameWatchdogResumes=v[74]||0;
            state.pumpSlices=v[75]||0;
            state.uploadBytesThisFrame=v[76]||0;
            state.uploadBytesPerFrame=v[77]||0;
            state.uploadByteBudgetExhaustions=v[78]||0;
            state.lastInstallBytes=v[79]||0;
            state.installBytesPerFrame=v[80]||0;
            state.meshRequestsInFlight=v[81]||0;
            state.meshResultsReady=v[82]||0;
            state.failedTasks=v[83]||0;
            state.pumpDriver='completion';
            """)
    private static native void publishTelemetryJs(Float64Array values);

    private static final class UploadRetryState {
        final long startedAtNanos;
        long lastTouchedAtNanos;
        int yields;
        boolean terminal;

        UploadRetryState(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
            this.lastTouchedAtNanos = startedAtNanos;
        }
    }

    private static final class DispatcherState {
        final Object dispatcher;
        final Runnable runner;
        Runnable command;
        boolean queued;
        boolean running;
        boolean requested;
        boolean disposed;
        boolean waitingForFrame;

        DispatcherState(Object dispatcher) {
            this.dispatcher = dispatcher;
            this.runner = () -> runDispatcher(this);
        }
    }
}
