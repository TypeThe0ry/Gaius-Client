package dev.gaius.browser.render;

import dev.gaius.browser.BrowserChunkPipelineTelemetry;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import org.teavm.jso.JSBody;

/**
 * Install queue for asynchronously produced terrain meshes.
 *
 * <p>Protocol for a producer (for example the mesh Worker pool):</p>
 * <ol>
 *   <li>{@link #beginRequest} when a section is sent off for meshing; stamp the job with the
 *       returned sequence and with {@link #levelEpoch()} and {@link #resourceEpoch()};</li>
 *   <li>{@link #enqueue} the finished {@link BrowserMeshInstall} when the result arrives
 *       (Worker message handler), or {@link #cancel} when the job failed;</li>
 *   <li>BrowserRenderScheduler drains the queue at the start of every rendered frame within a
 *       byte and time budget ({@link #drain}); stale results are discarded there.</li>
 * </ol>
 *
 * <p>A result is current only while its sequence is still the newest revision for its section
 * and both epochs are unchanged. LevelRenderer.resetLevelRenderData and LevelExtractor.setLevel
 * bump the level epoch, LevelExtractor.onResourceManagerReload the resource epoch (patched by
 * TerrainBatchPatches).</p>
 */
public final class BrowserMeshInstallQueue {
    public static final int DISCARD_SUPERSEDED = 1;
    public static final int DISCARD_LEVEL = 2;
    public static final int DISCARD_RESOURCES = 3;
    public static final int DISCARD_FAILED = 4;
    /** The result exceeded the opt-in rebuildable READY admission budget. */
    public static final int DISCARD_BUDGET = 5;

    private static final Long2IntOpenHashMap LATEST_REQUEST = new Long2IntOpenHashMap();
    private static final Long2IntOpenHashMap PENDING_REQUEST = new Long2IntOpenHashMap();
    private static final ArrayDeque<BrowserMeshInstall> READY = new ArrayDeque<>();
    /** Latest queued result per section; superseded arrivals are released immediately. */
    private static final Long2ObjectOpenHashMap<BrowserMeshInstall> READY_BY_SECTION = new Long2ObjectOpenHashMap<>();
    /** Allocated only for the opt-in priority path; FIFO keeps the old zero-state fast path. */
    private static IdentityHashMap<BrowserMeshInstall, Long> readyEnqueueFrame;
    /** Arrival order for aging; stale heads are discarded lazily as results leave READY. */
    private static ArrayDeque<BrowserMeshInstall> readyAgeOrder;
    private static long priorityFrame;
    private static long readyMaxWaitFrames;
    private static long observedReadyMaxWaitFrames;
    private static final long READY_MAX_WAIT_LIMIT_FRAMES = 8L;
    private static int levelEpoch = 1;
    private static int resourceEpoch = 1;
    private static long requested;
    private static long installed;
    private static long installedBytes;
    private static long discardedStale;
    private static long retries;
    private static long failures;
    private static long discardedBudget;
    private static long readyBytes;
    private static long readyBudgetEvictions;
    private static boolean priorityMode;
    private static boolean priorityModeInitialized;
    private static final long READY_BYTE_BUDGET = configuredReadyByteBudget();
    private static final int READY_ENTRY_BUDGET = configuredReadyEntryBudget();
    /** Producer hook run on every epoch bump (MeshKernelBridge cancels the jobs still out). */
    private static Runnable epochBumpHook;

    static {
        LATEST_REQUEST.defaultReturnValue(0);
        PENDING_REQUEST.defaultReturnValue(0);
    }

    private BrowserMeshInstallQueue() {
    }

    /** Registers a new request for {@code sectionNode}; older requests for it become stale. */
    public static int beginRequest(long sectionNode) {
        int sequence = LATEST_REQUEST.get(sectionNode) + 1;
        if (sequence <= 0) {
            sequence = 1;
        }
        LATEST_REQUEST.put(sectionNode, sequence);
        PENDING_REQUEST.put(sectionNode, sequence);
        requested++;
        return sequence;
    }

    /** Records the revision when vanilla creates a task and copies its block region. */
    public static int snapshotSequence(long sectionNode) {
        if (!LATEST_REQUEST.containsKey(sectionNode)) {
            LATEST_REQUEST.put(sectionNode, 1);
        }
        return LATEST_REQUEST.get(sectionNode);
    }

    /** A block, light or neighbor update invalidates snapshots, even before a replacement task exists. */
    public static void invalidateSection(int x, int y, int z) {
        long node = ((long) x << 42) | (((long) z & 0x3fffffL) << 20) | ((long) y & 0xfffffL);
        if (!LATEST_REQUEST.containsKey(node)) {
            return;
        }
        int sequence = LATEST_REQUEST.get(node) + 1;
        LATEST_REQUEST.put(node, sequence <= 0 ? 1 : sequence);
        // Keep the outstanding slot until its answer is discarded. A replacement task can
        // still choose vanilla when updates arrive faster than the Worker round trip.
    }

    /** The producer gave up on a request (Worker failure); the section is no longer pending. */
    public static void cancel(long sectionNode, int requestSeq) {
        if (PENDING_REQUEST.get(sectionNode) == requestSeq) {
            PENDING_REQUEST.remove(sectionNode);
        }
        failures++;
    }

    /** True while a request for the section is out (requested and not installed or cancelled). */
    public static boolean isPending(long sectionNode) {
        return PENDING_REQUEST.containsKey(sectionNode);
    }

    /** True while {@code requestSeq} is the newest request for the section in this level. */
    public static boolean isLatest(long sectionNode, int requestSeq) {
        return LATEST_REQUEST.get(sectionNode) == requestSeq;
    }

    /** Requests out plus results waiting for installation. */
    public static int inFlight() {
        return PENDING_REQUEST.size();
    }

    public static int readyCount() {
        return READY.size();
    }

    /** Bytes retained by finished results waiting for the render-thread install step. */
    public static long readyBytes() {
        return readyBytes;
    }

    /** Number of results released by the opt-in admission budget. */
    public static long readyBudgetEvictions() {
        return readyBudgetEvictions;
    }

    public static int levelEpoch() {
        return levelEpoch;
    }

    public static int resourceEpoch() {
        return resourceEpoch;
    }

    /**
     * Registers the producer's hook for epoch bumps: the jobs it still has out can only produce
     * stale results, so it should cancel them instead of keeping workers busy.
     */
    public static void setEpochBumpHook(Runnable hook) {
        epochBumpHook = hook;
    }

    /**
     * The level was left or its render data reset: every outstanding result is stale. Request
     * sequences restart per level, so a producer must not use them to order jobs across levels.
     */
    public static void bumpLevelEpoch() {
        levelEpoch = levelEpoch == Integer.MAX_VALUE ? 1 : levelEpoch + 1;
        BrowserChunkPipelineTelemetry.setEpochs(levelEpoch, resourceEpoch);
        discardReady(DISCARD_LEVEL);
        LATEST_REQUEST.clear();
        PENDING_REQUEST.clear();
        BrowserNeighborReadiness.clear();
        runEpochBumpHook();
        publishTelemetry();
    }

    /** Models, tints or atlases changed: every outstanding result is stale. */
    public static void bumpResourceEpoch() {
        resourceEpoch = resourceEpoch == Integer.MAX_VALUE ? 1 : resourceEpoch + 1;
        BrowserChunkPipelineTelemetry.setEpochs(levelEpoch, resourceEpoch);
        PENDING_REQUEST.clear();
        discardReady(DISCARD_RESOURCES);
        runEpochBumpHook();
        publishTelemetry();
    }

    private static void runEpochBumpHook() {
        Runnable hook = epochBumpHook;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (RuntimeException error) {
            // Cancelling is an optimisation: stale results are dropped by their epochs anyway.
            failures++;
        }
    }

    /** Accepts a finished result; a stale one is discarded at once. */
    public static void enqueue(BrowserMeshInstall result) {
        if (result == null) {
            return;
        }
        BrowserChunkPipelineTelemetry.installQueueEnter(
                result.telemetryTicket(), result.sectionNode(), result.requestSeq(), result.byteSize());
        int staleReason = staleReason(result);
        if (staleReason != 0) {
            discard(result, staleReason);
            return;
        }
        BrowserMeshInstall previous = READY_BY_SECTION.put(result.sectionNode(), result);
        if (previous != null && previous != result) {
            READY.remove(previous);
            readyBytes -= Math.max(0, previous.byteSize());
            if (readyBytes < 0) readyBytes = 0;
            forgetEnqueueFrame(previous);
            discard(previous, DISCARD_SUPERSEDED);
        }
        if (priorityMode()) {
            // Selection is done once per drain. Reordering here duplicates priority work and
            // allocates a temporary deque while results are arriving during camera movement.
            READY.addLast(result);
            rememberEnqueueFrame(result);
        } else {
            READY.addLast(result);
        }
        readyBytes += Math.max(0, result.byteSize());
        trimReadyBudget();
    }

    /** Opt-in until paired measurements prove the priority order on every profile. */
    private static boolean priorityMode() {
        if (!priorityModeInitialized) {
            priorityMode = browserInstallPriorityEnabled();
            priorityModeInitialized = true;
            if (priorityMode) {
                readyEnqueueFrame = new IdentityHashMap<>();
                readyAgeOrder = new ArrayDeque<>();
            }
        }
        return priorityMode;
    }

    @JSBody(script = "var g=typeof globalThis!=='undefined'?globalThis:self;"
            + "var flags=g.__gaiusLauncherFlags;"
            + "return !!(flags&&flags.meshInstallPriority===true);")
    private static native boolean browserInstallPriorityEnabled();

    /**
     * Installs ready results until {@code byteBudget} bytes were written or
     * {@code deadlineNanos} passed; the first result always installs so a single large mesh
     * cannot stall forever. Returns the bytes installed.
     */
    public static long drain(long byteBudget, long deadlineNanos) {
        long bytes = 0L;
        int count = 0;
        if (priorityMode()) {
            priorityFrame++;
            updateReadyWaitTelemetry();
        }
        while (!READY.isEmpty()) {
            BrowserMeshInstall next = priorityMode() ? pollPriorityReady() : READY.peekFirst();
            int staleReason = staleReason(next);
            if (staleReason != 0) {
                if (!priorityMode()) {
                    removeFirstReady();
                }
                discard(next, staleReason);
                continue;
            }
            int size = Math.max(0, next.byteSize());
            if (count > 0 && (bytes + size > byteBudget || System.nanoTime() >= deadlineNanos)) {
                if (priorityMode()) {
                    addLastReady(next);
                }
                break;
            }
            if (!priorityMode()) {
                removeFirstReady();
            }
            boolean done;
            try {
                done = next.install();
            } catch (RuntimeException failure) {
                failures++;
                if (PENDING_REQUEST.get(next.sectionNode()) == next.requestSeq()) {
                    PENDING_REQUEST.remove(next.sectionNode());
                }
                discard(next, DISCARD_FAILED);
                continue;
            }
            if (!done) {
                if (priorityMode()) {
                    addLastReady(next);
                } else {
                    addFirstReady(next);
                }
                retries++;
                break;
            }
            BrowserChunkPipelineTelemetry.installComplete(
                    next.telemetryTicket(), next.telemetryMeshInstalled());
            if (PENDING_REQUEST.get(next.sectionNode()) == next.requestSeq()) {
                PENDING_REQUEST.remove(next.sectionNode());
            }
            forgetEnqueueFrame(next);
            installed++;
            installedBytes += size;
            bytes += size;
            count++;
        }
        if (count > 0 || !READY.isEmpty()) {
            publishTelemetry();
        }
        return bytes;
    }

    private static void rememberEnqueueFrame(BrowserMeshInstall result) {
        if (readyEnqueueFrame != null && !readyEnqueueFrame.containsKey(result)) {
            readyEnqueueFrame.put(result, priorityFrame);
            readyAgeOrder.addLast(result);
        }
    }

    private static void updateReadyWaitTelemetry() {
        if (readyAgeOrder != null) {
            while (!readyAgeOrder.isEmpty() && !readyEnqueueFrame.containsKey(readyAgeOrder.peekFirst())) {
                readyAgeOrder.pollFirst();
            }
        }
        BrowserMeshInstall oldest = readyAgeOrder == null ? null : readyAgeOrder.peekFirst();
        Long enqueued = oldest == null ? null : readyEnqueueFrame.get(oldest);
        readyMaxWaitFrames = enqueued == null ? 0L : Math.max(0L, priorityFrame - enqueued);
        observedReadyMaxWaitFrames = Math.max(observedReadyMaxWaitFrames, readyMaxWaitFrames);
    }

    private static int staleReason(BrowserMeshInstall result) {
        if (result.levelEpoch() != levelEpoch) {
            return DISCARD_LEVEL;
        }
        if (result.resourceEpoch() != resourceEpoch) {
            return DISCARD_RESOURCES;
        }
        if (LATEST_REQUEST.get(result.sectionNode()) != result.requestSeq()) {
            return DISCARD_SUPERSEDED;
        }
        return 0;
    }

    private static void discardReady(int reason) {
        while (!READY.isEmpty()) {
            discard(removeFirstReady(), reason);
        }
    }

    private static BrowserMeshInstall removeFirstReady() {
        BrowserMeshInstall result = READY.pollFirst();
        if (result != null) {
            forgetReadyIndex(result);
            forgetEnqueueFrame(result);
            readyBytes -= Math.max(0, result.byteSize());
            if (readyBytes < 0) {
                readyBytes = 0;
            }
        }
        return result;
    }

    /** Re-evaluate queued work against the camera that will install it, without retaining stale P0. */
    private static BrowserMeshInstall pollHighestPriorityReady() {
        Iterator<BrowserMeshInstall> iterator = READY.iterator();
        BrowserMeshInstall selected = null;
        int selectedPriority = Integer.MAX_VALUE;
        while (iterator.hasNext()) {
            BrowserMeshInstall candidate = iterator.next();
            int candidatePriority = candidate.priority();
            if (selected == null || candidatePriority < selectedPriority) {
                selected = candidate;
                selectedPriority = candidatePriority;
            }
        }
        if (selected != null) {
            READY.remove(selected);
            forgetReadyIndex(selected);
            readyBytes -= Math.max(0, selected.byteSize());
            if (readyBytes < 0) {
                readyBytes = 0;
            }
        }
        return selected;
    }

    private static BrowserMeshInstall pollLowestPriorityReady() {
        Iterator<BrowserMeshInstall> iterator = READY.iterator();
        BrowserMeshInstall selected = null;
        int selectedPriority = Integer.MIN_VALUE;
        while (iterator.hasNext()) {
            BrowserMeshInstall candidate = iterator.next();
            int candidatePriority = candidate.priority();
            if (selected == null || candidatePriority > selectedPriority) {
                selected = candidate;
                selectedPriority = candidatePriority;
            }
        }
        if (selected != null) {
            READY.remove(selected);
            forgetReadyIndex(selected);
            readyBytes -= Math.max(0, selected.byteSize());
            if (readyBytes < 0) {
                readyBytes = 0;
            }
        }
        return selected;
    }

    private static void forgetEnqueueFrame(BrowserMeshInstall result) {
        if (readyEnqueueFrame != null) {
            readyEnqueueFrame.remove(result);
        }
    }

    private static void forgetReadyIndex(BrowserMeshInstall result) {
        if (READY_BY_SECTION.get(result.sectionNode()) == result) {
            READY_BY_SECTION.remove(result.sectionNode());
        }
    }

    private static BrowserMeshInstall pollPriorityReady() {
        // Age order is FIFO, so only its live head can be the oldest result. This avoids a
        // second READY scan on ordinary frames and skips camera/frustum priority reads when an
        // overdue result is due.
        if (readyAgeOrder != null) {
            while (!readyAgeOrder.isEmpty() && !readyEnqueueFrame.containsKey(readyAgeOrder.peekFirst())) {
                readyAgeOrder.pollFirst();
            }
            BrowserMeshInstall oldest = readyAgeOrder.peekFirst();
            Long oldestFrame = oldest == null ? null : readyEnqueueFrame.get(oldest);
            if (oldestFrame != null && priorityFrame - oldestFrame >= READY_MAX_WAIT_LIMIT_FRAMES) {
                READY.remove(oldest);
                forgetReadyIndex(oldest);
                readyBytes -= Math.max(0, oldest.byteSize());
                if (readyBytes < 0) readyBytes = 0;
                return oldest;
            }
        }
        BrowserMeshInstall selected = null;
        int selectedPriority = Integer.MAX_VALUE;
        for (BrowserMeshInstall candidate : READY) {
            int candidatePriority = candidate.priority();
            if (selected == null || candidatePriority < selectedPriority) {
                selected = candidate;
                selectedPriority = candidatePriority;
            }
        }
        if (selected != null) {
            READY.remove(selected);
            forgetReadyIndex(selected);
            readyBytes -= Math.max(0, selected.byteSize());
            if (readyBytes < 0) {
                readyBytes = 0;
            }
        }
        return selected;
    }

    private static void addFirstReady(BrowserMeshInstall result) {
        READY.addFirst(result);
        READY_BY_SECTION.put(result.sectionNode(), result);
        readyBytes += Math.max(0, result.byteSize());
    }

    private static void addLastReady(BrowserMeshInstall result) {
        READY.addLast(result);
        READY_BY_SECTION.put(result.sectionNode(), result);
        readyBytes += Math.max(0, result.byteSize());
    }

    /**
     * Finished results are rebuildable: releasing one and asking vanilla to compile the task
     * again preserves world content while bounding renderer-side result ownership. The switch is
     * deliberately off unless a run supplies gaiusMeshReadyBudgetMiB or gaiusMeshReadyMaxEntries.
     */
    private static void trimReadyBudget() {
        if (READY_BYTE_BUDGET <= 0 && READY_ENTRY_BUDGET <= 0) {
            return;
        }
        boolean evictedAny = false;
        while (!READY.isEmpty()
                && ((READY_BYTE_BUDGET > 0 && readyBytes > READY_BYTE_BUDGET)
                || (READY_ENTRY_BUDGET > 0 && READY.size() > READY_ENTRY_BUDGET))) {
            BrowserMeshInstall evicted = priorityMode() ? pollLowestPriorityReady() : removeFirstReady();
            readyBudgetEvictions++;
            discard(evicted, DISCARD_BUDGET);
            evictedAny = true;
        }
        if (evictedAny) {
            publishTelemetry();
        }
    }

    private static void publishTelemetry() {
        boolean priority = priorityModeInitialized && priorityMode;
        if (priority) {
            updateReadyWaitTelemetry();
        }
        publish(READY.size(), PENDING_REQUEST.size(), (double) requested, (double) installed,
                (double) installedBytes, (double) discardedStale, (double) retries,
                (double) failures, levelEpoch, resourceEpoch, (double) readyBytes,
                (double) readyBudgetEvictions, (double) discardedBudget,
                (double) READY_BYTE_BUDGET, (double) READY_ENTRY_BUDGET,
                priority ? (double) readyMaxWaitFrames : 0.0,
                priority ? (double) observedReadyMaxWaitFrames : 0.0,
                priority ? (double) READY_MAX_WAIT_LIMIT_FRAMES : 0.0);
    }

    private static long configuredReadyByteBudget() {
        String value = browserReadyBudgetMiB();
        try {
            long mib = Long.parseLong(value.trim());
            if (mib <= 0) {
                return 0;
            }
            return Math.multiplyExact(Math.min(mib, 4096L), 1024L * 1024L);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static int configuredReadyEntryBudget() {
        String value = browserReadyMaxEntries();
        try {
            int entries = Integer.parseInt(value.trim());
            return entries > 0 ? Math.min(entries, 4096) : 0;
        } catch (RuntimeException ignored) {
            // A byte-only switch still needs a finite object/ref admission bound for empty
            // meshes. An explicit entry switch always wins; otherwise scale a conservative cap
            // from the byte budget and keep it bounded independently of result sizes.
            if (READY_BYTE_BUDGET > 0) {
                return (int) Math.min(4096L, Math.max(64L,
                        (READY_BYTE_BUDGET / (1024L * 1024L)) * 64L));
            }
            return 0;
        }
    }

    @JSBody(script = "var g=typeof globalThis!=='undefined'?globalThis:self;"
            + "var flags=g.__gaiusLauncherFlags;"
            + "var v=flags&&flags.meshReadyBudgetMiB;"
            + "return v==null?'':String(v);")
    private static native String browserReadyBudgetMiB();

    @JSBody(script = "var g=typeof globalThis!=='undefined'?globalThis:self;"
            + "var flags=g.__gaiusLauncherFlags;"
            + "var v=flags&&flags.meshReadyMaxEntries;"
            + "return v==null?'':String(v);")
    private static native String browserReadyMaxEntries();

    private static void discard(BrowserMeshInstall result, int reason) {
        forgetEnqueueFrame(result);
        BrowserChunkPipelineTelemetry.discard(result.telemetryTicket(), reason);
        discardedStale++;
        if (reason == DISCARD_BUDGET) {
            discardedBudget++;
        }
        if (PENDING_REQUEST.get(result.sectionNode()) == result.requestSeq()) {
            PENDING_REQUEST.remove(result.sectionNode());
        }
        try {
            result.discard(reason);
        } catch (RuntimeException ignored) {
            failures++;
        }
    }

    @JSBody(params = {"ready", "pending", "requested", "installed", "installedBytes",
            "discarded", "retries", "failures", "levelEpoch", "resourceEpoch", "readyBytes",
            "budgetEvictions", "discardedBudget", "readyByteBudget", "readyEntryBudget",
            "readyMaxWaitFrames", "observedReadyMaxWaitFrames", "readyMaxWaitLimitFrames"}, script = """
            const state=globalThis.__gaiusChunkPipelineTelemetry
              || (globalThis.__gaiusChunkPipelineTelemetry={});
            state.meshInstallReady=ready;
            state.meshInstallPending=pending;
            state.meshInstallRequested=requested;
            state.meshInstalled=installed;
            state.meshInstalledBytes=installedBytes;
            state.meshInstallDiscarded=discarded;
            state.meshInstallRetries=retries;
            state.meshInstallFailures=failures;
            state.meshInstallLevelEpoch=levelEpoch;
            state.meshInstallResourceEpoch=resourceEpoch;
            state.meshInstallReadyBytes=readyBytes;
            state.meshInstallReadyBudgetEvictions=budgetEvictions;
            state.meshInstallBudgetDiscarded=discardedBudget;
            state.meshInstallReadyByteBudget=readyByteBudget;
            state.meshInstallReadyEntryBudget=readyEntryBudget;
            if (readyMaxWaitLimitFrames > 0) {
              state.meshInstallReadyMaxWaitFrames=readyMaxWaitFrames;
              state.meshInstallObservedReadyMaxWaitFrames=observedReadyMaxWaitFrames;
              state.meshInstallReadyMaxWaitLimitFrames=readyMaxWaitLimitFrames;
            }
            """)
    private static native void publish(int ready, int pending, double requested,
            double installed, double installedBytes, double discarded, double retries,
            double failures, int levelEpoch, int resourceEpoch, double readyBytes,
            double budgetEvictions, double discardedBudget, double readyByteBudget,
            double readyEntryBudget, double readyMaxWaitFrames,
            double observedReadyMaxWaitFrames, double readyMaxWaitLimitFrames);
}
