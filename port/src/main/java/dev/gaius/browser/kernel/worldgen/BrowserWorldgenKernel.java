package dev.gaius.browser.kernel.worldgen;

import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int16Array;
import dev.gaius.browser.JsArrays;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Java side of the worldgen kernel facade ({@code port/web/kernels/worldgen-kernel.js},
 * {@code globalThis.GaiusWorldgenKernel}) in the integrated server worker.
 *
 * <p>The facade owns the kernel worker pool; this class registers generator IRs and submits
 * jobs. Results arrive on a JS callback and are handed to a fresh Java thread, so installing
 * them into a proto chunk (and completing the generation future) never runs inside the JS
 * callback's stack.
 *
 * <p>Every submitted job settles exactly once (result, error, or a {@code transient:timeout} once
 * its deadline passes), so a seam that falls back to the Java path on errors never leaves a chunk
 * future pending; the server worker drains the facade before the final save.
 *
 * <p>Switches: {@code worldgenKernel=0} in the page or worker URL (also {@code gaiusKernels=0} /
 * {@code gaiusKernelsOff=worldgen} for the shared kernel runtime),
 * {@code __gaiusWorldgenKernelConfig.enabled = false}, or {@link #disable(String)} (called after a
 * rejected result) keep worldgen on the Java path. The facade also disables itself after repeated
 * kernel failures.
 */
public final class BrowserWorldgenKernel {
    private static boolean disabled;
    private static String disabledReason = "";
    private static int nextKey = 1;

    private BrowserWorldgenKernel() {
    }

    /** Whether new jobs may go to the kernel. Cheap enough to call per chunk. */
    public static boolean available() {
        return !disabled && dev.gaius.browser.kernel.KernelMirror.enabled("worldgen") && facadeUsable();
    }

    /** Keeps every later chunk on the Java path. */
    public static void disable(String reason) {
        if (!disabled) {
            disabled = true;
            disabledReason = reason == null ? "" : reason;
            logOnce("[Gaius] worldgen kernel disabled: " + disabledReason);
        }
    }

    public static String disabledReason() {
        return disabledReason;
    }

    /**
     * Whether a job error only refused that one job (memory budget, cancelled or superseded job,
     * runtime briefly unavailable): the chunk takes the Java path but the generator stays on the
     * kernel. The facade prefixes such messages with {@code transient:}.
     */
    public static boolean isTransient(String message) {
        return message != null && message.startsWith("transient:");
    }

    /** A new generator slot key (one per dimension and seed). */
    public static int allocateKey() {
        return nextKey++;
    }

    /** Registers a generator IR under {@code key} and starts the pool. */
    public static boolean registerGenerator(int key, byte[] ir) {
        if (!available()) {
            return false;
        }
        boolean ok = registerGeneratorNative(key, ir);
        startNative();
        return ok;
    }

    /** Whether the facade still accepts jobs for {@code key} (false after a kernel failure). */
    public static boolean generatorUsable(int key) {
        return available() && generatorUsableNative(key);
    }

    /** Runs {@code task} on a new Java thread (outside the JS callback that delivered a result). */
    public static boolean runOnJavaThread(Runnable task) {
        try {
            Thread thread = new Thread(task, "gaius-worldgen-kernel");
            thread.setDaemon(true);
            thread.start();
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** Schedules a host timeout; false means the caller must take its fallback immediately. */
    public static boolean scheduleTimeout(Runnable task, int delayMillis) {
        try {
            if (task == null) return false;
            scheduleTimeoutNative(() -> {
                if (!runOnJavaThread(task)) {
                    // The caller's watchdog treats a failed timer dispatch as an immediate
                    // fallback; never execute a suspendable Runnable on the raw JS stack.
                }
            }, Math.max(1, delayMillis));
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /** Counts a Java-side fallback in the shared worldgen telemetry. */
    public static void noteHostFallback(String stage, String reason) {
        try {
            noteHostFallbackNative(stage == null ? "unknown" : stage,
                    reason == null ? "unknown" : reason);
        } catch (Throwable ignored) {
            // Telemetry is diagnostic and must never alter the fallback path.
        }
    }

    // ---- jobs ----

    /** Terrain job flags. */
    public static final int FLAG_SURFACE = 1;
    public static final int FLAG_BIOMES = 2;
    /**
     * Facade-side flag: keep the decoded noise chunk for a later {@link #submitSurface} (26.2,
     * where SURFACE is its own step); the result's {@code token} field names it (0: not kept).
     */
    public static final int FLAG_KEEP_NOISE = 1 << 8;
    private static final int[] NO_RING = new int[0];

    /**
     * Submits a terrain job. {@code beard} is the packed beardifier
     * ({@code [flags, affected x6, rigidCount, (minX, minY, minZ, maxX, maxY, maxZ, adjustment,
     * groundDelta)*, junctionCount, (x, groundY, z)*]}). {@code ringBiomes} are the biome registry
     * ids the neighbouring chunks store around the chunk (20 quart columns, each over the level
     * height; {@code gaius-worldgen-wasm} job.rs documents the order) for the surface rules, or
     * {@code null} to let the kernel compute them. A neighbour biome the kernel does not know is a
     * transient refusal ({@code transient:worldgen-fallback:...}).
     */
    public static void submitTerrain(int key, int chunkX, int chunkZ, int flags, int[] beard, int[] ringBiomes,
            ChunkCallback onResult, ErrorCallback onError) {
        if (!available()) {
            onError.accept("transient:needs-vanilla:NEEDS_VANILLA");
            return;
        }
        submitTerrainNative(key, chunkX, chunkZ, flags, beard, ringBiomes == null ? NO_RING : ringBiomes,
                onResult, onError);
    }

    public static void submitBiomes(int key, int chunkX, int chunkZ, IdsCallback onResult, ErrorCallback onError) {
        if (!available()) {
            onError.accept("transient:needs-vanilla:NEEDS_VANILLA");
            return;
        }
        submitBiomesNative(key, chunkX, chunkZ, onResult, onError);
    }

    /** Whether the facade can run the surface rules on a kept noise chunk. */
    public static boolean surfaceSupported() {
        return available() && surfaceSupportedNative();
    }

    /**
     * Runs the surface rules on the noise chunk kept under {@code token}. The result lists the
     * changed blocks: {@code count}, {@code positions} ({@code section << 12 | y << 8 | z << 4 | x})
     * and {@code states} (block state ids). The kept chunk is consumed. {@code ringBiomes} as for
     * {@link #submitTerrain}.
     */
    public static void submitSurface(int key, int token, int chunkX, int chunkZ, int[] beard, int[] ringBiomes,
            ChunkCallback onResult, ErrorCallback onError) {
        if (!available()) {
            onError.accept("transient:needs-vanilla:NEEDS_VANILLA");
            return;
        }
        submitSurfaceNative(key, token, chunkX, chunkZ, beard, ringBiomes == null ? NO_RING : ringBiomes,
                onResult, onError);
    }

    /** Releases a kept noise chunk that will not reach the kernel surface step. */
    public static void dropKeptNoise(int token) {
        if (token != 0) {
            dropKeptNoiseNative(token);
        }
    }

    /** Kernel jobs that have not settled yet (telemetry). */
    public static int pendingJobs() {
        return pendingNative();
    }

    /** A decoded chunk result (flat arrays; see {@code worldgen-kernel.js#flatten}). */
    public interface KernelChunk extends JSObject {
    }

    @JSFunctor
    public interface ChunkCallback extends JSObject {
        void accept(KernelChunk chunk);
    }

    @JSFunctor
    public interface IdsCallback extends JSObject {
        void accept(JSObject ids);
    }

    @JSFunctor
    public interface ErrorCallback extends JSObject {
        void accept(String message);
    }

    /** Timer callback is a JS function; it must re-enter through HostLoop before running Java. */
    @JSFunctor
    private interface TimeoutCallback extends JSObject {
        void accept();
    }

    // ---- reading results into Java arrays ----

    public static int chunkInt(KernelChunk chunk, String field) {
        return intField(chunk, field);
    }

    /** Copies an Int32Array field of a chunk result; {@code null} when absent. */
    public static int[] chunkInts(KernelChunk chunk, String field) {
        int length = arrayLength(chunk, field);
        if (length < 0) {
            return null;
        }
        if (JsArrays.LINEAR_MEMORY) {
            // WebAssembly GC: one copy out of the result (a typed-array view of a Java array is
            // itself a copy there, and readBack copies once more).
            return int32Field(chunk, field).copyToJavaArray();
        }
        int[] out = new int[length];
        copyInts(chunk, field, out);
        return out;
    }

    /** Copies the section index array (u16 values, read them with {@code & 0xFFFF}). */
    public static short[] chunkIndices(KernelChunk chunk) {
        int length = arrayLength(chunk, "indices");
        if (JsArrays.LINEAR_MEMORY) {
            return length > 0 ? int16Field(chunk, "indices").copyToJavaArray() : new short[0];
        }
        short[] out = new short[Math.max(0, length)];
        if (length > 0) {
            copyShorts(chunk, "indices", out);
        }
        return out;
    }

    public static byte[] chunkBytes(KernelChunk chunk, String field) {
        int length = arrayLength(chunk, field);
        if (JsArrays.LINEAR_MEMORY) {
            return length > 0 ? int8Field(chunk, field).copyToJavaArray() : new byte[0];
        }
        byte[] out = new byte[Math.max(0, length)];
        if (length > 0) {
            copyBytes(chunk, field, out);
        }
        return out;
    }

    /** Copies a biome id result. */
    public static int[] ids(JSObject ids) {
        int length = typedLength(ids);
        if (JsArrays.LINEAR_MEMORY) {
            return length > 0 ? int32View(ids).copyToJavaArray() : new int[0];
        }
        int[] out = new int[Math.max(0, length)];
        if (length > 0) {
            copyTypedInts(ids, out);
        }
        return out;
    }

    // ---- JS bindings (Rhino-compatible scripts) ----

    @JSBody(script = "var k = globalThis.GaiusWorldgenKernel;"
            + "return !!k && k.usable();")
    private static native boolean facadeUsable();

    @JSBody(script = "var k = globalThis.GaiusWorldgenKernel; if (k) { k.start(); }")
    private static native void startNative();

    @JSBody(params = {"task", "delay"}, script =
            "setTimeout(function () { task(); }, delay); return true;")
    private static native boolean scheduleTimeoutNative(TimeoutCallback task, int delay);

    @JSBody(params = {"stage", "reason"}, script =
            "var k = globalThis.GaiusWorldgenKernel;"
                    + "if (k && k.recordHostFallback) k.recordHostFallback(stage, reason);")
    private static native void noteHostFallbackNative(String stage, String reason);

    @JSBody(params = {"key", "ir"}, script = "var k = globalThis.GaiusWorldgenKernel;"
            + "if (!k) { return false; }"
            + "return k.registerGenerator(key, new Uint8Array(ir.buffer, ir.byteOffset, ir.length).slice());")
    private static native boolean registerGeneratorNativeJs(int key, Int8Array ir);

    private static boolean registerGeneratorNative(int key, byte[] ir) {
        return registerGeneratorNativeJs(key, JsArrays.int8(ir));
    }

    @JSBody(params = "key", script = "var k = globalThis.GaiusWorldgenKernel;"
            + "return !!k && k.generatorUsable(key);")
    private static native boolean generatorUsableNative(int key);

    @JSBody(params = {"key", "chunkX", "chunkZ", "flags", "beard", "ring", "onResult", "onError"},
            script = "var k = globalThis.GaiusWorldgenKernel;"
            + "if (!k) { onError('worldgen kernel facade missing'); return; }"
            + "k.submitTerrain(key, chunkX, chunkZ, flags, new Int32Array(beard),"
            + " function (chunk) { onResult(chunk); }, function (message) { onError(String(message)); },"
            + " ring.length > 0 ? new Int32Array(ring) : null);")
    private static native void submitTerrainNativeJs(int key, int chunkX, int chunkZ, int flags, Int32Array beard, Int32Array ring, ChunkCallback onResult, ErrorCallback onError);

    private static void submitTerrainNative(int key, int chunkX, int chunkZ, int flags, int[] beard, int[] ring, ChunkCallback onResult, ErrorCallback onError) {
        submitTerrainNativeJs(key, chunkX, chunkZ, flags, JsArrays.int32(beard), JsArrays.int32(ring), onResult, onError);
    }

    @JSBody(params = {"key", "chunkX", "chunkZ", "onResult", "onError"},
            script = "var k = globalThis.GaiusWorldgenKernel;"
            + "if (!k) { onError('worldgen kernel facade missing'); return; }"
            + "k.submitBiomes(key, chunkX, chunkZ,"
            + " function (ids) { onResult(ids); }, function (message) { onError(String(message)); });")
    private static native void submitBiomesNative(int key, int chunkX, int chunkZ, IdsCallback onResult,
            ErrorCallback onError);

    @JSBody(script = "var k = globalThis.GaiusWorldgenKernel;"
            + "return !!k && typeof k.submitSurfaceStored === 'function';")
    private static native boolean surfaceSupportedNative();

    @JSBody(params = {"key", "token", "chunkX", "chunkZ", "beard", "ring", "onResult", "onError"},
            script = "var k = globalThis.GaiusWorldgenKernel;"
            + "if (!k || !k.submitSurfaceStored) { onError('transient:worldgen kernel surface missing'); return; }"
            + "k.submitSurfaceStored(key, token, chunkX, chunkZ, new Int32Array(beard),"
            + " function (diff) { onResult(diff); }, function (message) { onError(String(message)); },"
            + " ring.length > 0 ? new Int32Array(ring) : null);")
    private static native void submitSurfaceNativeJs(int key, int token, int chunkX, int chunkZ, Int32Array beard, Int32Array ring, ChunkCallback onResult, ErrorCallback onError);

    private static void submitSurfaceNative(int key, int token, int chunkX, int chunkZ, int[] beard, int[] ring, ChunkCallback onResult, ErrorCallback onError) {
        submitSurfaceNativeJs(key, token, chunkX, chunkZ, JsArrays.int32(beard), JsArrays.int32(ring), onResult, onError);
    }

    @JSBody(params = "token", script = "var k = globalThis.GaiusWorldgenKernel;"
            + "if (k && k.dropStored) { k.dropStored(token); }")
    private static native void dropKeptNoiseNative(int token);

    @JSBody(script = "var k = globalThis.GaiusWorldgenKernel;"
            + "return k && k.pending ? k.pending() : 0;")
    private static native int pendingNative();

    @JSBody(params = {"chunk", "field"}, script = "return chunk[field] | 0;")
    private static native int intField(KernelChunk chunk, String field);

    @JSBody(params = {"chunk", "field"}, script = "var a = chunk[field]; return a ? a.length : -1;")
    private static native int arrayLength(KernelChunk chunk, String field);

    @JSBody(params = {"chunk", "field", "out"}, script = "out.set(chunk[field]);")
    private static native void copyIntsJs(KernelChunk chunk, String field, Int32Array out);

    private static void copyInts(KernelChunk chunk, String field, int[] out) {
        Int32Array outView = JsArrays.int32(out);
        copyIntsJs(chunk, field, outView);
        JsArrays.readBack(outView, out);
    }

    @JSBody(params = {"chunk", "field", "out"}, script = "out.set(chunk[field]);")
    private static native void copyShortsJs(KernelChunk chunk, String field, Int16Array out);

    private static void copyShorts(KernelChunk chunk, String field, short[] out) {
        Int16Array outView = JsArrays.int16(out);
        copyShortsJs(chunk, field, outView);
        JsArrays.readBack(outView, out);
    }

    @JSBody(params = {"chunk", "field", "out"}, script = "out.set(chunk[field]);")
    private static native void copyBytesJs(KernelChunk chunk, String field, Int8Array out);

    private static void copyBytes(KernelChunk chunk, String field, byte[] out) {
        Int8Array outView = JsArrays.int8(out);
        copyBytesJs(chunk, field, outView);
        JsArrays.readBack(outView, out);
    }

    @JSBody(params = "a", script = "return a ? a.length : -1;")
    private static native int typedLength(JSObject a);

    // WebAssembly GC result reads: the field as a typed array of the Java element type, with the
    // conversion of the JavaScript build's out.set(field) (unsigned arrays are reinterpreted,
    // which is the same two's complement wrap).
    @JSBody(params = {"chunk", "field"}, script = "var a = chunk[field];"
            + "return a instanceof Int32Array ? a : a instanceof Uint32Array"
            + " ? new Int32Array(a.buffer, a.byteOffset, a.length) : new Int32Array(a);")
    private static native Int32Array int32Field(KernelChunk chunk, String field);

    @JSBody(params = {"chunk", "field"}, script = "var a = chunk[field];"
            + "return a instanceof Int16Array ? a : a instanceof Uint16Array"
            + " ? new Int16Array(a.buffer, a.byteOffset, a.length) : new Int16Array(a);")
    private static native Int16Array int16Field(KernelChunk chunk, String field);

    @JSBody(params = {"chunk", "field"}, script = "var a = chunk[field];"
            + "return a instanceof Int8Array ? a : (a instanceof Uint8Array || a instanceof Uint8ClampedArray)"
            + " ? new Int8Array(a.buffer, a.byteOffset, a.length) : new Int8Array(a);")
    private static native Int8Array int8Field(KernelChunk chunk, String field);

    @JSBody(params = "a", script = "return a instanceof Int32Array ? a : a instanceof Uint32Array"
            + " ? new Int32Array(a.buffer, a.byteOffset, a.length) : new Int32Array(a);")
    private static native Int32Array int32View(JSObject a);

    @JSBody(params = {"a", "out"}, script = "out.set(a);")
    private static native void copyTypedIntsJs(JSObject a, Int32Array out);

    private static void copyTypedInts(JSObject a, int[] out) {
        Int32Array outView = JsArrays.int32(out);
        copyTypedIntsJs(a, outView);
        JsArrays.readBack(outView, out);
    }

    @JSBody(params = "message", script = "if (typeof console !== 'undefined') { console.warn(message); }")
    private static native void logOnce(String message);
}
