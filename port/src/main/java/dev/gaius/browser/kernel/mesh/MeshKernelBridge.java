package dev.gaius.browser.kernel.mesh;

import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int16Array;
import org.teavm.jso.typedarrays.Float32Array;
import dev.gaius.browser.JsArrays;
import dev.gaius.browser.HostLoop;
import dev.gaius.browser.BrowserRenderScheduler;
import dev.gaius.browser.render.BrowserMeshInstallQueue;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Java side of the page's mesh kernel facade ({@code port/web/kernels/mesh-kernel-job.js},
 * {@code window.__gaiusMeshKernel}). Profile independent: the 26.2 and 26.3 section compiler
 * hooks ({@code MeshKernelHooks} in their version source sets) build the snapshot arrays and
 * call {@link #submit}; finished jobs come back through {@link #poll} as opaque records whose
 * fields the accessors below read.
 *
 * <p>Runtime switch and fallback: {@link #ready()} is false when the facade is missing, the
 * kernel runtime or the mesher kernel is unavailable, {@code ?meshKernel=0} (or
 * {@code ?gaiusMesher=0}) is set, or after {@value #MAX_FAILURES} kernel failures in a row; every
 * caller then keeps the vanilla Java compiler.</p>
 *
 * <p>Results are pulled, never pushed into Java from a JS callback: the facade wakes Java once
 * when a result lands in an empty queue, and the wake starts a TeaVM thread that runs the
 * registered {@link #setDrainer drainer} and then installs within the frame's remaining
 * install budget (BrowserRenderScheduler.installReadyMeshes), so a result that lands while
 * the compile pipeline is idle is installed without waiting for an unrelated frame.
 * {@link #pumpResults()} also runs from every frame (BrowserRenderScheduler.beginFrame), which
 * is where the drainer times out jobs the kernel never answered.</p>
 */
public final class MeshKernelBridge {
    /** Header ints of a submitted job (mirrors HEADER in mesh-kernel-job.js). */
    public static final int H_TICKET = 0;
    public static final int H_EPOCH = 1;
    public static final int H_WIDE = 2;
    public static final int H_PALETTE_COUNT = 3;
    public static final int H_FLAGS = 4;
    public static final int H_SX = 5;
    public static final int H_SY = 6;
    public static final int H_SZ = 7;
    public static final int H_SEED_HI = 8;
    public static final int H_SEED_LO = 9;
    public static final int H_REQUEST_SEQ = 10;
    public static final int H_NON_AIR = 11;
    public static final int H_DISTANCE = 12;
    /** Options.biomeBlendRadius, 0..{@value #MAX_BIOME_BLEND}. */
    public static final int H_BLEND = 13;
    public static final int HEADER_LENGTH = 14;

    /** Largest biome blend radius the kernel reproduces; a larger one keeps the vanilla compiler. */
    public static final int MAX_BIOME_BLEND = 2;

    public static final int FLAG_AO = 1;
    public static final int FLAG_CUTOUT_LEAVES = 2;
    public static final int FLAG_NEARBY = 4;

    /** Record status: meshed, the kernel asked for the vanilla compiler, or the job failed. */
    public static final int STATUS_MESHED = 0;
    public static final int STATUS_NEEDS_VANILLA = 2;
    public static final int STATUS_FAILED = -1;

    public static final int LAYER_SOLID = 0;
    public static final int LAYER_CUTOUT = 1;
    public static final int LAYER_TRANSLUCENT = 2;

    private static final int MAX_FAILURES = 3;
    private static final int PUBLISH_INTERVAL = 64;

    private static boolean disabled;
    private static boolean facadeMissingLogged;
    private static int failuresInARow;
    private static boolean wakeInstalled;
    private static Runnable drainer;
    private static boolean draining;
    private static long submitted;
    private static long meshed;
    private static long vanillaFallbacks;
    private static long failures;
    private static long blendSkips;
    private static long snapshotNanos;
    private static long installNanos;
    private static int sinceLastPublish;

    private MeshKernelBridge() {
    }

    @JSFunctor
    interface Wake extends JSObject {
        void wake();
    }

    /** True when sections should go to the mesh kernel. */
    public static boolean ready() {
        if (disabled || !dev.gaius.browser.kernel.KernelMirror.enabled("mesher")) {
            return false;
        }
        boolean ready;
        try {
            ready = facadeReady();
        } catch (RuntimeException error) {
            if (!facadeMissingLogged) {
                facadeMissingLogged = true;
                System.out.println("[mesh-kernel] facade check failed, sections use the vanilla compiler: "
                        + error);
            }
            return false;
        }
        if (ready && !wakeInstalled) {
            wakeInstalled = true;
            installWake(MeshKernelBridge::onWake);
        }
        return ready;
    }

    /** True once {@link #disable} turned the kernel off for this page (repeated failures). */
    public static boolean disabled() {
        return disabled;
    }

    /** Disables the kernel for this page; sections use the vanilla compiler from now on. */
    public static void disable(String reason) {
        if (!disabled) {
            disabled = true;
            System.out.println("[mesh-kernel] disabled, sections use the vanilla compiler: " + reason);
            try {
                facadeDisable(reason);
            } catch (RuntimeException ignored) {
                // The facade is gone; the Java flag is enough.
            }
            publishNow();
        }
    }

    /** Publish disable/fallback state immediately; timeout evidence must not wait for the batch. */
    private static void publishNow() {
        try {
            publish((double) submitted, (double) meshed, (double) vanillaFallbacks, (double) failures,
                    (double) blendSkips, snapshotNanos / 1.0e6, installNanos / 1.0e6, disabled);
        } catch (RuntimeException ignored) {
            // Telemetry is optional.
        }
    }

    /** A kernel job or its installation failed; three in a row disable the kernel. */
    public static void noteFailure(String reason) {
        failures++;
        failuresInARow++;
        if (failuresInARow >= MAX_FAILURES) {
            disable(failuresInARow + " failures in a row, last: " + reason);
        } else {
            System.out.println("[mesh-kernel] job failed, section compiled by vanilla: " + reason);
        }
    }

    public static void noteMeshed() {
        failuresInARow = 0;
        meshed++;
        maybePublish();
    }

    public static void noteVanillaFallback() {
        vanillaFallbacks++;
        maybePublish();
    }

    /** A section kept the vanilla compiler because the biome blend radius is out of range. */
    public static void noteBlendSkip() {
        blendSkips++;
        maybePublish();
    }

    public static void noteSnapshotNanos(long nanos) {
        snapshotNanos += nanos;
    }

    public static void noteInstallNanos(long nanos) {
        installNanos += nanos;
    }

    /**
     * Registers the version-specific result drainer (MeshKernelHooks.drainResults) and hooks
     * {@link #pumpResults()} into every frame of BrowserRenderScheduler.
     */
    public static void setDrainer(Runnable runnable) {
        drainer = runnable;
        BrowserRenderScheduler.setMeshResultPump(runnable == null ? null : MeshKernelBridge::pumpResults);
        // Resource reload invalidates old jobs but leaves the mesher enabled for the new epoch.
        BrowserMeshInstallQueue.setEpochBumpHook(runnable == null ? null : MeshKernelBridge::cancelAll);
    }

    /**
     * Cancels every section job still out (level reset or left, resources reloaded). Each one
     * still comes back as a "cancelled" record, so the drainer settles its bookkeeping.
     */
    public static void cancelAll() {
        try {
            facadeCancelAll();
        } catch (RuntimeException ignored) {
            // The facade is gone; the answer timeout covers the jobs.
        }
    }

    /** Moves finished kernel results into the install queue; cheap when nothing finished. */
    public static void pumpResults() {
        Runnable current = drainer;
        if (current == null || draining) {
            return;
        }
        draining = true;
        try {
            current.run();
        } finally {
            draining = false;
        }
    }

    private static void onWake() {
        HostLoop.startThread(MeshKernelBridge::deliverResults);
    }

    /** Wake path: results into the install queue, then install what this frame's budget allows. */
    private static void deliverResults() {
        pumpResults();
        BrowserRenderScheduler.installReadyMeshes();
    }

    public static boolean hasTable(int epoch) {
        return facadeHasTable(epoch);
    }

    /**
     * Returns one validated model table loaded by the page cache, or null when the cache is not
     * ready or has no entry for the current asset hash and this resource pack scope. The facade
     * consumes the entry exactly once.
     */
    public static byte[] takeCachedTable(String scope) {
        try {
            Int8Array view = facadeTakeCachedTable(scope);
            return view == null ? null : view.copyToJavaArray();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** True once the asynchronous page cache lookup has completed. */
    public static boolean tableCacheReady() {
        try {
            return facadeTableCacheReady();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /**
     * Persists the facade's current model table under {@code scope} (a resource pack stack whose
     * contents the asset hash identifies). Fresh exports only; a cached table is never stored back.
     */
    public static void storeTable(String scope) {
        try {
            facadeStoreTable(scope);
        } catch (RuntimeException ignored) {
            // The cache is an optimisation only.
        }
    }

    /** Hands a new model table to the facade (copied there); false when the facade is missing. */
    public static boolean setTable(byte[] bytes, int length, int epoch) {
        if (disabled || !dev.gaius.browser.kernel.KernelMirror.enabled("mesher")) {
            return false;
        }
        try {
            return facadeSetTable(bytes, length, epoch);
        } catch (RuntimeException error) {
            noteFailure("model table rejected: " + error);
            return false;
        }
    }

    /**
     * Submits one section job. {@code ids16} is used unless {@code header[H_WIDE]} is set, then
     * {@code ids32}. All arrays are reused by the caller; the facade copies them before returning.
     */
    public static boolean submit(int[] header, short[] ids16, int[] ids32, byte[] light, byte[] quarts,
            int[] palette, byte[] swamp, float[] floats) {
        if (!ready()) {
            return false;
        }
        boolean sent;
        if (header[H_WIDE] != 0) {
            sent = facadeSubmitWide(header, ids32, light, quarts, palette, swamp, floats);
        } else {
            sent = facadeSubmit(header, ids16, light, quarts, palette, swamp, floats);
        }
        if (sent) {
            submitted++;
        }
        return sent;
    }

    /** Next finished record, or null. */
    public static JSObject poll() {
        return facadePoll();
    }

    /**
     * Java is done with {@code record} (its vertices and order were copied out, or it will never be
     * installed): the facade hands the result buffer back to a kernel worker for reuse. No accessor
     * may read the record afterwards.
     */
    @JSBody(params = "record", script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "if (record && k && typeof k.release === 'function') k.release(record);")
    public static native void release(JSObject record);

    // --- record accessors ----------------------------------------------------------------------

    @JSBody(params = "record", script = "return record.ticket | 0;")
    public static native int ticket(JSObject record);

    @JSBody(params = "record", script = "return record.status | 0;")
    public static native int status(JSObject record);

    @JSBody(params = "record", script = "return record.code ? String(record.code) : '';")
    public static native String code(JSObject record);

    @JSBody(params = "record", script = "return record.message ? String(record.message) : '';")
    public static native String message(JSObject record);

    @JSBody(params = {"record", "layer"}, script = "return record.quads[layer] | 0;")
    public static native int quadCount(JSObject record, int layer);

    @JSBody(params = "record", script = "return record.visLo | 0;")
    public static native int visibilityLo(JSObject record);

    @JSBody(params = "record", script = "return record.visHi | 0;")
    public static native int visibilityHi(JSObject record);

    @JSBody(params = "record", script = "return record.detail | 0;")
    public static native int detail(JSObject record);

    /**
     * Copies the vanilla BLOCK vertices of {@code layer} into {@code target} at {@code offset};
     * returns the bytes copied, or -1 when the record has no vertices for the layer.
     */
    @JSBody(params = {"record", "layer", "target", "offset"}, script = ""
            + "var names = ['solid', 'cutout', 'translucent'];"
            + "if (!record.result) return -1;"
            + "var l = record.result.layers[names[layer]];"
            + "if (!l || !l.vertices) return -1;"
            + "var v = l.vertices;"
            + "if (offset < 0 || offset + v.byteLength > target.length) return -1;"
            + "target.set(new Int8Array(v.buffer, v.byteOffset, v.byteLength), offset);"
            + "return v.byteLength;")
    private static native int copyVerticesJs(JSObject record, int layer, Int8Array target, int offset);

    public static int copyVertices(JSObject record, int layer, byte[] target, int offset) {
        Int8Array targetView = JsArrays.int8(target);
        int result = copyVerticesJs(record, layer, targetView, offset);
        JsArrays.readBack(targetView, target);
        return result;
    }

    /**
     * WebAssembly GC: copies the vanilla BLOCK vertices of {@code layer} straight into linear
     * memory at {@code address}, a direct buffer's address of at least {@code capacity} bytes
     * (BrowserMemory); returns the bytes copied, or -1 when the record has no vertices for the
     * layer or they do not fit. One copy, where {@link #copyVertices} needs a Java array (on
     * WebAssembly GC: a typed-array copy in, one out and the copy into the buffer).
     */
    public static int copyVerticesToLinear(JSObject record, int layer, long address, int capacity) {
        JsArrays.installLinearViews();
        return copyVerticesLinearJs(record, layer, (int) address, capacity);
    }

    @JSBody(params = {"record", "layer", "address", "capacity"}, script = ""
            + "var names = ['solid', 'cutout', 'translucent'];"
            + "if (!record.result) return -1;"
            + "var l = record.result.layers[names[layer]];"
            + "if (!l || !l.vertices) return -1;"
            + "var v = l.vertices;"
            + "if (v.byteLength > capacity) return -1;"
            + "var linear = globalThis.__gaiusLinear;"
            + "if (!linear.u8.length) { linear.refresh(); }"
            + "linear.u8.set(new Uint8Array(v.buffer, v.byteOffset, v.byteLength), address >>> 0);"
            + "return v.byteLength;")
    private static native int copyVerticesLinearJs(JSObject record, int layer, int address, int capacity);

    /** Bytes of vanilla BLOCK vertices the record holds for {@code layer}; -1 when absent. */
    @JSBody(params = {"record", "layer"}, script = ""
            + "var names = ['solid', 'cutout', 'translucent'];"
            + "if (!record.result) return -1;"
            + "var l = record.result.layers[names[layer]];"
            + "return l && l.vertices ? l.vertices.byteLength : -1;")
    public static native int vertexBytes(JSObject record, int layer);

    /** Copies the translucent quad order (farthest first) into {@code target}; -1 when absent. */
    @JSBody(params = {"record", "target"}, script = ""
            + "if (!record.result) return -1;"
            + "var o = record.result.layers.translucent.order;"
            + "if (!o || o.length > target.length) return -1;"
            + "target.set(new Int32Array(o.buffer, o.byteOffset, o.length));"
            + "return o.length;")
    private static native int copyTranslucentOrderJs(JSObject record, Int32Array target);

    public static int copyTranslucentOrder(JSObject record, int[] target) {
        Int32Array targetView = JsArrays.int32(target);
        int result = copyTranslucentOrderJs(record, targetView);
        JsArrays.readBack(targetView, target);
        return result;
    }

    // --- facade ---------------------------------------------------------------------------------

    @JSBody(script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return !!k && typeof k.ready === 'function' && k.ready() === true;")
    private static native boolean facadeReady();

    @JSBody(params = "reason", script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "if (k && typeof k.disable === 'function') k.disable(reason);")
    private static native void facadeDisable(String reason);

    @JSBody(params = "wake", script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "if (k && typeof k.setWake === 'function') k.setWake(wake);")
    private static native void installWake(Wake wake);

    @JSBody(params = "epoch", script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return !!k && k.hasTable(epoch) === true;")
    private static native boolean facadeHasTable(int epoch);

    @JSBody(params = "scope", script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return k && typeof k.takeCachedTable === 'function' ? k.takeCachedTable(scope) : null;")
    private static native Int8Array facadeTakeCachedTable(String scope);

    @JSBody(params = "scope", script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "if (k && typeof k.storeTable === 'function') k.storeTable(scope);")
    private static native void facadeStoreTable(String scope);

    @JSBody(script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return !k || typeof k.tableCacheReady !== 'function' || k.tableCacheReady() === true;")
    private static native boolean facadeTableCacheReady();

    @JSBody(params = {"bytes", "length", "epoch"}, script = ""
            + "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return !!k && k.setTable(bytes, length, epoch) === true;")
    private static native boolean facadeSetTableJs(Int8Array bytes, int length, int epoch);

    private static boolean facadeSetTable(byte[] bytes, int length, int epoch) {
        return facadeSetTableJs(JsArrays.int8(bytes), length, epoch);
    }

    @JSBody(params = {"header", "ids", "light", "quarts", "palette", "swamp", "floats"}, script = ""
            + "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return !!k && k.submitJob(header, ids, light, quarts, palette, swamp, floats) === true;")
    private static native boolean facadeSubmitJs(Int32Array header, Int16Array ids, Int8Array light, Int8Array quarts, Int32Array palette, Int8Array swamp, Float32Array floats);

    private static boolean facadeSubmit(int[] header, short[] ids, byte[] light, byte[] quarts, int[] palette, byte[] swamp, float[] floats) {
        return facadeSubmitJs(JsArrays.int32(header), JsArrays.int16(ids), JsArrays.int8(light), JsArrays.int8(quarts), JsArrays.int32(palette), JsArrays.int8(swamp), JsArrays.float32(floats));
    }

    @JSBody(params = {"header", "ids", "light", "quarts", "palette", "swamp", "floats"}, script = ""
            + "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return !!k && k.submitJob(header, ids, light, quarts, palette, swamp, floats) === true;")
    private static native boolean facadeSubmitWideJs(Int32Array header, Int32Array ids, Int8Array light, Int8Array quarts, Int32Array palette, Int8Array swamp, Float32Array floats);

    private static boolean facadeSubmitWide(int[] header, int[] ids, byte[] light, byte[] quarts, int[] palette, byte[] swamp, float[] floats) {
        return facadeSubmitWideJs(JsArrays.int32(header), JsArrays.int32(ids), JsArrays.int8(light), JsArrays.int8(quarts), JsArrays.int32(palette), JsArrays.int8(swamp), JsArrays.float32(floats));
    }

    @JSBody(script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "if (k && typeof k.cancelAll === 'function') k.cancelAll();")
    private static native void facadeCancelAll();

    @JSBody(script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "if (!k) return null;"
            + "var r = k.poll();"
            + "return r ? r : null;")
    private static native JSObject facadePoll();

    // --- telemetry ------------------------------------------------------------------------------

    private static void maybePublish() {
        if (++sinceLastPublish < PUBLISH_INTERVAL) {
            return;
        }
        sinceLastPublish = 0;
        try {
            publish((double) submitted, (double) meshed, (double) vanillaFallbacks, (double) failures,
                    (double) blendSkips, snapshotNanos / 1.0e6, installNanos / 1.0e6, disabled);
        } catch (RuntimeException ignored) {
            // Telemetry is optional.
        }
    }

    @JSBody(params = {"submitted", "meshed", "fallbacks", "failures", "blendSkips", "snapshotMs", "installMs",
            "disabled"},
            script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
                    + "var state = g.__gaiusChunkPipelineTelemetry || (g.__gaiusChunkPipelineTelemetry = {});"
                    + "state.meshKernelSubmitted = submitted;"
                    + "state.meshKernelMeshed = meshed;"
                    + "state.meshKernelVanillaFallbacks = fallbacks;"
                    + "state.meshKernelFailures = failures;"
                    + "state.meshKernelBlendSkips = blendSkips;"
                    + "state.meshKernelSnapshotMs = snapshotMs;"
                    + "state.meshKernelInstallMs = installMs;"
                    + "state.meshKernelDisabled = disabled;")
    private static native void publish(double submitted, double meshed, double fallbacks, double failures,
            double blendSkips, double snapshotMs, double installMs, boolean disabled);
}
