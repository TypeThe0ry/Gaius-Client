package dev.gaius.browser;

import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Float64Array;
import org.teavm.jso.typedarrays.Int16Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Typed-array views of Java arrays for JS calls, shared by the JavaScript and the WebAssembly GC
 * builds (TeaVM's {@code @JSByRef} only exists in the JavaScript backend).
 *
 * <p>This is the JavaScript implementation: a Java primitive array is backed by a typed array,
 * so {@code int8(array)} returns that typed array itself (zero copy) and JS writes into it are
 * Java writes; {@code readBack} has nothing to do. The WebAssembly GC implementation
 * ({@code src/target-wasmgc/java}) copies the array into a new typed array, and
 * {@code readBack} copies a typed array the JS side wrote back into the Java array. Every call
 * site therefore uses the same pattern:
 * <ul>
 * <li>JS only reads the data: pass {@code int8(array)};</li>
 * <li>JS writes the result: {@code Int8Array view = int8Output(array); call(view);
 * readBack(view, array);}.</li>
 * </ul>
 * A view must not be kept across calls: on WebAssembly GC it is a detached copy.
 *
 * <p>Per-frame paths use the {@code (ptr,len)} upload API instead (design D7): on WebAssembly GC
 * the data is written to (or already lives in) linear memory and the JS body receives a byte
 * address and an element count, which it reads through the cached views of
 * {@code globalThis.__gaiusLinear} without creating a typed array. The JavaScript target has no
 * linear memory; its per-frame paths keep passing the zero-copy views above. Call sites choose
 * with the compile-time constant {@link #LINEAR_MEMORY}:
 * <pre>{@code
 * if (JsArrays.LINEAR_MEMORY) {
 *     uploadLinearJs(JsArrays.stageFloat32(values, 0, count), count);
 *     return;
 * }
 * uploadJs(JsArrays.float32(values));
 * }</pre>
 * The overlays compile against the class library of their target, so javac drops the branch the
 * target does not take: the JavaScript output keeps its typed-array call unchanged and the
 * WebAssembly GC output never reaches {@code copyFromJavaArray} there.
 */
public final class JsArrays {
    /**
     * Compile-time constant: true when JS bodies read Java data from linear memory through
     * {@code (ptr,len)} arguments (WebAssembly GC), false on the JavaScript target.
     */
    public static final boolean LINEAR_MEMORY = false;

    private JsArrays() {
    }

    public static Int8Array int8(byte[] array) {
        return array == null ? null : Int8Array.fromJavaArray(array);
    }

    public static Int16Array int16(short[] array) {
        return array == null ? null : Int16Array.fromJavaArray(array);
    }

    public static Int32Array int32(int[] array) {
        return array == null ? null : Int32Array.fromJavaArray(array);
    }

    public static Float32Array float32(float[] array) {
        return array == null ? null : Float32Array.fromJavaArray(array);
    }

    public static Float64Array float64(double[] array) {
        return array == null ? null : Float64Array.fromJavaArray(array);
    }

    /**
     * A view of {@code array} for JS to fill, followed by {@link #readBack}. Here the array's own
     * storage (as {@link #int8}); on WebAssembly GC a new typed array of the same length whose
     * initial contents are not defined (the array is not copied in).
     */
    public static Int8Array int8Output(byte[] array) {
        return int8(array);
    }

    public static void readBack(Int8Array view, byte[] array) {
    }

    public static void readBack(Int16Array view, short[] array) {
    }

    public static void readBack(Int32Array view, int[] array) {
    }

    public static void readBack(Float32Array view, float[] array) {
    }

    public static void readBack(Float64Array view, double[] array) {
    }

    /** True when {@link #int8} and the other views share the Java array's storage. */
    public static boolean zeroCopy() {
        return true;
    }

    // (ptr,len) upload API. Only the WebAssembly GC implementation has linear memory; the
    // JavaScript target never takes a LINEAR_MEMORY branch, these exist so that the shared
    // sources compile.

    /** Installs {@code globalThis.__gaiusLinear} (WebAssembly GC); nothing to do here. */
    public static void installLinearViews() {
    }

    /** The upload scratch area of at least {@code byteCount} bytes (WebAssembly GC only). */
    public static int scratch(int byteCount) {
        throw noLinearMemory();
    }

    /** Stages {@code array[offset, offset + count)} in the scratch area (WebAssembly GC only). */
    public static int stageInt8(byte[] array, int offset, int count) {
        throw noLinearMemory();
    }

    /** Stages {@code array[offset, offset + count)} in the scratch area (WebAssembly GC only). */
    public static int stageInt16(short[] array, int offset, int count) {
        throw noLinearMemory();
    }

    /** Stages {@code array[offset, offset + count)} in the scratch area (WebAssembly GC only). */
    public static int stageInt32(int[] array, int offset, int count) {
        throw noLinearMemory();
    }

    /** Stages {@code array[offset, offset + count)} in the scratch area (WebAssembly GC only). */
    public static int stageFloat32(float[] array, int offset, int count) {
        throw noLinearMemory();
    }

    private static UnsupportedOperationException noLinearMemory() {
        return new UnsupportedOperationException(
                "The JavaScript target has no linear memory; use the zero-copy views");
    }
}
