package dev.gaius.parity;

import dev.gaius.browser.BrowserChunkTaskPriority;
import dev.gaius.browser.BrowserIntegratedServerMain;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import org.teavm.jso.JSBody;

/**
 * Entry points of the executor schedule of the TELEMETRY patch MinecraftClientPatcher.patchTickPacketParity
 * (TickPacketParityPatches.applyBrowserSchedule; a WasmGC packet-parity debug build only, nothing calls
 * them otherwise). With the Worker global {@code __gaiusTickParityDebug} other than true every factory
 * returns null, so the patched vanilla code keeps its own executors, and every name stays as it is.
 */
public final class BrowserTickParityExecutors {
    private static final long HOLD_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(60);
    static final String WORLDGEN_DISPATCHER = "worldgen-dispatcher";
    static final String VANILLA_DISPATCHER = "dispatcher";
    /** PlayerChunkSender's own initial batch limit (patchPlayerChunkSenderBrowserWorker requires it to be 1). */
    static final int VANILLA_MAX_UNACKNOWLEDGED_BATCHES = 1;
    private static TickParitySerialExecutor background;
    private static TickParityDirectExecutor.Gauge io;
    private static BrowserSettleProbe probe;
    private static int requested = -1;

    private BrowserTickParityExecutors() {
    }

    /** Head of {@code Util.makeExecutor}: the parity background executor, or null for vanilla's. */
    public static ExecutorService background(String name) {
        if (!requested()) return null;
        if (background != null) throw new IllegalStateException("second vanilla background executor");
        background = new TickParitySerialExecutor("Worker-" + name + "-1", HOLD_LIMIT_NANOS);
        return background;
    }

    /** Head of {@code Util.makeIoExecutor}: a counting calling-thread executor, or null for vanilla's. */
    public static ExecutorService io(String name, boolean daemon) {
        if (!requested()) return null;
        if (io == null) io = new TickParityDirectExecutor.Gauge();
        return new TickParityDirectExecutor(io);
    }

    /**
     * Head of the {@code AbstractConsecutiveExecutor} constructor. MinecraftServerWorkerPatcher names
     * the worldgen dispatcher {@value #WORLDGEN_DISPATCHER} (vanilla: {@value #VANILLA_DISPATCHER}),
     * and its patched {@code run()} defers that executor's re-registration through a new host
     * activation (BrowserWorldgenDispatcherScheduler), which no executor sees and which queues the
     * next turn behind other background work. In a Worker that requested a capture the dispatcher
     * keeps vanilla's name, and the patched {@code run()} takes the path of every other consecutive
     * executor: one task per run, re-registered at once on the running thread as a loop, which under
     * the parity background executor is the order of vanilla's inline re-registration.
     */
    public static String consecutiveName(String name) {
        return WORLDGEN_DISPATCHER.equals(name) && requested() ? VANILLA_DISPATCHER : name;
    }

    /**
     * Replaces BrowserIntegratedServerMain.isWorkerServer at the head of {@code handleMovePlayer}:
     * during a recording the Worker fast path (applyWorkerMovement, which returns before vanilla's
     * thread barrier and the parity input hook) gives way to vanilla's handler.
     */
    public static boolean workerMovement() {
        return !TickPacketRecorder.enabled && BrowserIntegratedServerMain.isWorkerServer();
    }

    /** Replaces the Worker's deferred same-section tracking walk with vanilla's during a recording. */
    public static void moveChunkTracking(ServerChunkCache chunkCache, ServerPlayer player) {
        if (TickPacketRecorder.enabled) chunkCache.move(player);
        else BrowserIntegratedServerMain.moveServerPlayerChunkTracking(chunkCache, player);
    }

    /** Vanilla's FIFO choice within a priority bucket during a recording, nearest-first otherwise. */
    public static long chunkTaskKey(Long2ObjectLinkedOpenHashMap<?> queuedChunks) {
        return TickPacketRecorder.enabled ? queuedChunks.firstLongKey() : BrowserChunkTaskPriority.chooseNext(queuedChunks);
    }

    /**
     * The number of holders ChunkGenerationTask.scheduleLayer submits before it hands the rest of the
     * layer to a host run-queue activation: unbounded during a recording (vanilla submits a whole
     * layer in one call), {@code worker} otherwise.
     */
    public static int holdersPerTurn(int worker) {
        return TickPacketRecorder.enabled ? Integer.MAX_VALUE : worker;
    }

    /** Whether a layer may end with a host run-queue continuation (not during a recording). */
    public static boolean boundedLayers() {
        return !TickPacketRecorder.enabled;
    }

    /** A new PlayerChunkSender's batch limit: vanilla's during a recording, the Worker's otherwise. */
    public static int unacknowledgedBatches(int worker) {
        return TickPacketRecorder.enabled ? VANILLA_MAX_UNACKNOWLEDGED_BATCHES : worker;
    }

    /**
     * The settle probe, or null when this Worker's vanilla executors are not the parity schedule
     * (an ordinary build, or a hook that a later patcher lost): the first settle then fails closed.
     */
    static BrowserSettleProbe probe(Consumer<String> report) {
        if (probe != null) return probe;
        if (background == null || io == null || Util.backgroundExecutor().service() != background
                || !(Util.ioPool().service() instanceof TickParityDirectExecutor)) {
            return null;
        }
        probe = new BrowserSettleProbe(background, io, new KernelHost(), report);
        return probe;
    }

    /** Whether the Worker asked for a parity capture before vanilla's executors were created. */
    static boolean requested() {
        if (requested < 0) requested = requestedNative() ? 1 : 0;
        return requested == 1;
    }

    /** Kernel and dispatcher state that only the Worker's JavaScript host can see. */
    private static final class KernelHost implements BrowserSettleProbe.Host {
        @Override
        public boolean kernelsPresent() {
            return kernelsPresentNative();
        }

        @Override
        public int kernelJobsInFlight() {
            return kernelJobsInFlightNative();
        }

        @Override
        public long kernelJobsSubmitted() {
            return (long) kernelJobsSubmittedNative();
        }

        @Override
        public long dispatcherPumps() {
            return (long) dispatcherHopsNative();
        }

        @Override
        public long runQueuePending() {
            return (long) runQueuePendingNative();
        }
    }

    @JSBody(script = "return globalThis.__gaiusTickParityDebug === true;")
    private static native boolean requestedNative();

    @JSBody(script = "var s = globalThis;"
            + "return !!(s.__gaiusKernelClient || s.GaiusWorldgenKernel || s.GaiusLightKernelHost || s.GaiusKernelMirror);")
    private static native boolean kernelsPresentNative();

    @JSBody(script = "var s = globalThis; var n = 0;"
            + "var c = s.__gaiusKernelClient; var t = c && c.stats;"
            + "if (t) { n += Math.max(0, (Number(t.submitted) || 0) - (Number(t.completed) || 0) - (Number(t.failed) || 0)); }"
            + "var w = s.GaiusWorldgenKernel;"
            + "if (w && typeof w.pending === 'function') { n += Math.max(0, Number(w.pending()) || 0); }"
            + "return n | 0;")
    private static native int kernelJobsInFlightNative();

    @JSBody(script = "var s = globalThis; var c = s.__gaiusKernelClient; var t = c && c.stats;"
            + "var n = t ? (Number(t.submitted) || 0) : 0;"
            + "var w = s.GaiusWorldgenKernel; var wt = w && typeof w.telemetry === 'function' ? w.telemetry() : null;"
            + "if (wt) { n += Number(wt.submitted) || 0; }"
            + "return n;")
    private static native double kernelJobsSubmittedNative();

    @JSBody(script = "var j = globalThis.GaiusJspi;"
            + "return j && typeof j.pendingRuns === 'function' ? (Number(j.pendingRuns()) || 0) : 0;")
    private static native double runQueuePendingNative();

    @JSBody(script = "var r = globalThis.__gaiusWorldgenStats; var d = r && r.dispatcher;"
            + "return d ? (Number(d.deferredHops) || 0) : 0;")
    private static native double dispatcherHopsNative();
}
