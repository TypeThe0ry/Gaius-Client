package dev.gaius.browser.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayDeque;
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

    private static final Long2IntOpenHashMap LATEST_REQUEST = new Long2IntOpenHashMap();
    private static final Long2IntOpenHashMap PENDING_REQUEST = new Long2IntOpenHashMap();
    private static final ArrayDeque<BrowserMeshInstall> READY = new ArrayDeque<>();
    private static int levelEpoch = 1;
    private static int resourceEpoch = 1;
    private static long requested;
    private static long installed;
    private static long installedBytes;
    private static long discardedStale;
    private static long retries;
    private static long failures;
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
        discardReady(DISCARD_LEVEL);
        LATEST_REQUEST.clear();
        PENDING_REQUEST.clear();
        BrowserNeighborReadiness.clear();
        runEpochBumpHook();
    }

    /** Models, tints or atlases changed: every outstanding result is stale. */
    public static void bumpResourceEpoch() {
        resourceEpoch = resourceEpoch == Integer.MAX_VALUE ? 1 : resourceEpoch + 1;
        PENDING_REQUEST.clear();
        discardReady(DISCARD_RESOURCES);
        runEpochBumpHook();
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
        int staleReason = staleReason(result);
        if (staleReason != 0) {
            discard(result, staleReason);
            return;
        }
        READY.addLast(result);
    }

    /**
     * Installs ready results until {@code byteBudget} bytes were written or
     * {@code deadlineNanos} passed; the first result always installs so a single large mesh
     * cannot stall forever. Returns the bytes installed.
     */
    public static long drain(long byteBudget, long deadlineNanos) {
        long bytes = 0L;
        int count = 0;
        while (!READY.isEmpty()) {
            BrowserMeshInstall next = READY.peekFirst();
            int staleReason = staleReason(next);
            if (staleReason != 0) {
                READY.pollFirst();
                discard(next, staleReason);
                continue;
            }
            int size = Math.max(0, next.byteSize());
            if (count > 0 && (bytes + size > byteBudget || System.nanoTime() >= deadlineNanos)) {
                break;
            }
            READY.pollFirst();
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
                READY.addFirst(next);
                retries++;
                break;
            }
            if (PENDING_REQUEST.get(next.sectionNode()) == next.requestSeq()) {
                PENDING_REQUEST.remove(next.sectionNode());
            }
            installed++;
            installedBytes += size;
            bytes += size;
            count++;
        }
        if (count > 0 || !READY.isEmpty()) {
            publish(READY.size(), PENDING_REQUEST.size(), (double) requested, (double) installed,
                    (double) installedBytes, (double) discardedStale, (double) retries,
                    (double) failures, levelEpoch, resourceEpoch);
        }
        return bytes;
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
            discard(READY.pollFirst(), reason);
        }
    }

    private static void discard(BrowserMeshInstall result, int reason) {
        discardedStale++;
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
            "discarded", "retries", "failures", "levelEpoch", "resourceEpoch"}, script = """
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
            """)
    private static native void publish(int ready, int pending, double requested,
            double installed, double installedBytes, double discarded, double retries,
            double failures, int levelEpoch, int resourceEpoch);
}
