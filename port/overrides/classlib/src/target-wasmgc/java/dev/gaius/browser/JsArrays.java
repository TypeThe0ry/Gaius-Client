package dev.gaius.browser;

import org.teavm.interop.Address;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Float64Array;
import org.teavm.jso.typedarrays.Int16Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.runtime.heap.Heap;

/**
 * WebAssembly GC implementation of the typed-array views (see the JavaScript one in
 * {@code src/main/java} for the call-site contract). A WebAssembly GC array lives in the GC
 * heap, not in a JS ArrayBuffer, so {@code int8(array)} copies the array into a new typed array
 * and {@code readBack} copies a view that JS wrote back into the array. Per-frame paths use the
 * {@code (ptr,len)} upload API instead (design D7).
 *
 * <p>{@code (ptr,len)} uploads: the data of a direct buffer already lives in linear memory, so a
 * caller passes its address ({@code MemoryUtil.memAddress}); a Java array is staged into one
 * reusable scratch allocation ({@link #stageFloat32} and the other stage methods). The JS body
 * receives the byte address and the element count and reads them through
 * {@code globalThis.__gaiusLinear}, which holds one view of each element type over the whole
 * memory. Growing the memory detaches the old buffer, so a view that reads length 0 is replaced
 * ({@code __gaiusLinear.refresh()}, which the loader may also call from the TeaVM
 * {@code memory.onResize} hook). The scratch area holds one staged upload: it is valid until the
 * next {@code scratch} or stage call. Staging and the JS call it feeds run without a suspension
 * point in between, so JSPI threads cannot interleave there.
 *
 * <p>{@link #float64} is the exception among the views: its callers are the packed telemetry
 * snapshots (one array per call, read synchronously), so it stages the doubles in a separate
 * linear-memory area and returns a cached {@code Float64Array} view of it per length instead of
 * a new copy. Pass at most one {@code float64} view per JS call and never keep it.
 */
public final class JsArrays {
    /**
     * Compile-time constant: true when JS bodies read Java data from linear memory through
     * {@code (ptr,len)} arguments (WebAssembly GC), false on the JavaScript target.
     */
    public static final boolean LINEAR_MEMORY = true;

    private static final int SCRATCH_MIN_BYTES = 4096;
    private static int scratchBase;
    private static int scratchAddress;
    private static int scratchCapacity;
    private static boolean linearViewsInstalled;
    private static int float64Base;
    private static int float64Address;
    private static int float64Capacity;

    private JsArrays() {
    }

    public static Int8Array int8(byte[] array) {
        return array == null ? null : Int8Array.copyFromJavaArray(array);
    }

    public static Int16Array int16(short[] array) {
        return array == null ? null : Int16Array.copyFromJavaArray(array);
    }

    public static Int32Array int32(int[] array) {
        return array == null ? null : Int32Array.copyFromJavaArray(array);
    }

    public static Float32Array float32(float[] array) {
        return array == null ? null : Float32Array.copyFromJavaArray(array);
    }

    /**
     * The doubles of {@code array} in a cached view of a linear-memory staging area (see the
     * class comment): no typed array is created per call once a view of that length exists.
     */
    public static Float64Array float64(double[] array) {
        if (array == null) {
            return null;
        }
        int length = array.length;
        if (!linearViewsInstalled) {
            installLinearViews();
        }
        if (length > float64Capacity) {
            growFloat64(length);
        }
        Address target = Address.fromInt(float64Address);
        for (int index = 0; index < length; index++) {
            target.add(index << 3).putDouble(array[index]);
        }
        return float64ViewJs(float64Address, length);
    }

    private static void growFloat64(int length) {
        int capacity = Math.max(64, float64Capacity);
        while (capacity < length) {
            capacity <<= 1;
        }
        if (float64Base != 0) {
            Heap.release(Address.fromInt(float64Base));
            float64Base = 0;
            float64Address = 0;
            float64Capacity = 0;
        }
        Address base = Heap.alloc(byteSize(capacity, 3) + 8);
        if (base.toInt() == 0) {
            throw new OutOfMemoryError("Linear-memory float64 staging: " + capacity + " doubles");
        }
        float64Base = base.toInt();
        float64Address = (float64Base + 7) & ~7;
        float64Capacity = capacity;
    }

    @JSBody(params = {"address", "length"}, script = """
            const linear=globalThis.__gaiusLinear;
            if (!linear.u8.length) linear.refresh();
            const views=linear.float64Views || (linear.float64Views=[]);
            let view=views[length];
            if (!view || view.buffer!==linear.buffer || view.byteOffset!==(address>>>0)) {
              view=new Float64Array(linear.buffer,address>>>0,length);
              views[length]=view;
            }
            return view;
            """)
    private static native Float64Array float64ViewJs(int address, int length);

    /** A new typed array of the array's length for JS to fill (the array is not copied in). */
    public static Int8Array int8Output(byte[] array) {
        return array == null ? null : Int8Array.create(array.length);
    }

    public static void readBack(Int8Array view, byte[] array) {
        if (view != null && array != null) {
            byte[] copy = view.copyToJavaArray();
            System.arraycopy(copy, 0, array, 0, Math.min(copy.length, array.length));
        }
    }

    public static void readBack(Int16Array view, short[] array) {
        if (view != null && array != null) {
            short[] copy = view.copyToJavaArray();
            System.arraycopy(copy, 0, array, 0, Math.min(copy.length, array.length));
        }
    }

    public static void readBack(Int32Array view, int[] array) {
        if (view != null && array != null) {
            int[] copy = view.copyToJavaArray();
            System.arraycopy(copy, 0, array, 0, Math.min(copy.length, array.length));
        }
    }

    public static void readBack(Float32Array view, float[] array) {
        if (view != null && array != null) {
            float[] copy = view.copyToJavaArray();
            System.arraycopy(copy, 0, array, 0, Math.min(copy.length, array.length));
        }
    }

    public static void readBack(Float64Array view, double[] array) {
        if (view != null && array != null) {
            double[] copy = view.copyToJavaArray();
            System.arraycopy(copy, 0, array, 0, Math.min(copy.length, array.length));
        }
    }

    /** True when {@link #int8} and the other views share the Java array's storage. */
    public static boolean zeroCopy() {
        return false;
    }

    /**
     * Installs {@code globalThis.__gaiusLinear}, the cached views of the linear memory that
     * {@code (ptr,len)} JS bodies read. Idempotent; the stage methods call it, and a caller that
     * only passes direct-buffer addresses calls it once before its first upload.
     */
    public static void installLinearViews() {
        if (!linearViewsInstalled) {
            installLinearViewsJs();
            linearViewsInstalled = true;
        }
    }

    /**
     * The 8-byte aligned address of the upload scratch area, at least {@code byteCount} bytes.
     * Its contents are not preserved across calls.
     */
    public static int scratch(int byteCount) {
        if (byteCount < 0) {
            throw new IllegalArgumentException("Negative scratch size: " + byteCount);
        }
        if (!linearViewsInstalled) {
            installLinearViews();
        }
        if (byteCount > scratchCapacity) {
            growScratch(byteCount);
        }
        return scratchAddress;
    }

    public static int stageInt8(byte[] array, int offset, int count) {
        checkRange(array.length, offset, count);
        int address = scratch(count);
        Address target = Address.fromInt(address);
        for (int index = 0; index < count; index++) {
            target.add(index).putByte(array[offset + index]);
        }
        return address;
    }

    public static int stageInt16(short[] array, int offset, int count) {
        checkRange(array.length, offset, count);
        int address = scratch(byteSize(count, 1));
        Address target = Address.fromInt(address);
        for (int index = 0; index < count; index++) {
            target.add(index << 1).putShort(array[offset + index]);
        }
        return address;
    }

    public static int stageInt32(int[] array, int offset, int count) {
        checkRange(array.length, offset, count);
        int address = scratch(byteSize(count, 2));
        Address target = Address.fromInt(address);
        for (int index = 0; index < count; index++) {
            target.add(index << 2).putInt(array[offset + index]);
        }
        return address;
    }

    public static int stageFloat32(float[] array, int offset, int count) {
        checkRange(array.length, offset, count);
        int address = scratch(byteSize(count, 2));
        Address target = Address.fromInt(address);
        for (int index = 0; index < count; index++) {
            target.add(index << 2).putFloat(array[offset + index]);
        }
        return address;
    }

    private static void checkRange(int length, int offset, int count) {
        if (offset < 0 || count < 0 || offset > length - count) {
            throw new IndexOutOfBoundsException("Invalid upload range: offset=" + offset
                    + " count=" + count + " length=" + length);
        }
    }

    private static int byteSize(int count, int shift) {
        if (count > (Integer.MAX_VALUE >> shift) - 16) {
            throw new IllegalArgumentException("Upload too large: " + count);
        }
        return count << shift;
    }

    private static void growScratch(int byteCount) {
        int capacity = Math.max(SCRATCH_MIN_BYTES, scratchCapacity);
        while (capacity < byteCount) {
            capacity = capacity > (Integer.MAX_VALUE >> 1) - 16 ? byteCount : capacity << 1;
        }
        if (scratchBase != 0) {
            Heap.release(Address.fromInt(scratchBase));
            scratchBase = 0;
            scratchAddress = 0;
            scratchCapacity = 0;
        }
        // TeaVM's allocator aligns to 4; Float64Array reads need 8.
        Address base = Heap.alloc(capacity + 8);
        if (base.toInt() == 0) {
            throw new OutOfMemoryError("Linear-memory upload scratch: " + capacity + " bytes");
        }
        scratchBase = base.toInt();
        scratchAddress = (scratchBase + 7) & ~7;
        scratchCapacity = capacity;
    }

    @JSBody(script = """
            if (globalThis.__gaiusLinear) return;
            const memory=globalThis.__gaiusWasmMemory;
            if (!memory || !memory.buffer) {
              throw new Error('Gaius: no WebAssembly memory (__gaiusWasmMemory) for linear uploads');
            }
            const views={
              memory:memory,
              buffer:null,
              u8:null,
              i8:null,
              i16:null,
              u16:null,
              i32:null,
              u32:null,
              f32:null,
              f64:null,
              dv:null,
              refreshes:0,
              refresh:function() {
                const buffer=this.memory.buffer;
                if (buffer!==this.buffer) {
                  this.buffer=buffer;
                  this.u8=new Uint8Array(buffer);
                  this.i8=new Int8Array(buffer);
                  this.i16=new Int16Array(buffer);
                  this.u16=new Uint16Array(buffer);
                  this.i32=new Int32Array(buffer);
                  this.u32=new Uint32Array(buffer);
                  this.f32=new Float32Array(buffer);
                  this.f64=new Float64Array(buffer);
                  this.dv=new DataView(buffer);
                  this.refreshes=(this.refreshes+1)|0;
                }
                return this;
              }
            };
            views.refresh();
            globalThis.__gaiusLinear=views;
            """)
    private static native void installLinearViewsJs();
}
