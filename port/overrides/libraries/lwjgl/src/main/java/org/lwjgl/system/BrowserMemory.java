package org.lwjgl.system;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.BufferOverflowException;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4fc;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;

/**
 * Managed-memory implementation of the pointer operations used by LWJGL.
 *
 * <p>An address stores a region id in its high 32 bits and a byte offset in
 * its low 32 bits. It never exposes a JVM or JavaScript object address.</p>
 */
public final class BrowserMemory {
    private static final long HARD_MAX_LIVE_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long DEFAULT_MAX_LIVE_BYTES = HARD_MAX_LIVE_BYTES;
    private static final int HARD_MAX_TEMPORARY_BYTES = 16 * 1024 * 1024;
    private static final int DEFAULT_MAX_TEMPORARY_BYTES = HARD_MAX_TEMPORARY_BYTES;
    private static final long MAX_LIVE_BYTES = configuredMaxLiveBytes();
    private static final int MAX_TEMPORARY_BYTES = configuredMaxTemporaryBytes();
    private static final int TEMP_BYTES_SIZE = 65536;
    private static final int ADDRESS_CACHE_SIZE = 16;
    private static final int ADDRESS_CACHE_MASK = ADDRESS_CACHE_SIZE - 1;
    private static final boolean IS_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static final Map<Integer, Region> REGIONS = new HashMap<>();
    private static final Map<BufferReference, Long> ADDRESSES = new HashMap<>();
    private static final ReferenceQueue<Buffer> COLLECTED_BUFFERS = new ReferenceQueue<>();
    private static final ThreadLocal<byte[]> BYTE_ARRAYS = ThreadLocal.withInitial(() -> new byte[TEMP_BYTES_SIZE]);
    private static final ThreadLocal<char[]> CHAR_ARRAYS = ThreadLocal.withInitial(() -> new char[8192]);
    private static int nextRegionId = 1;
    private static int cachedRegionId;
    private static Region cachedRegion;
    private static final WeakReference<?>[] CACHED_ADDRESS_BUFFERS =
            new WeakReference<?>[ADDRESS_CACHE_SIZE];
    private static final long[] CACHED_BUFFER_ADDRESSES = new long[ADDRESS_CACHE_SIZE];
    private static int addressLookupMisses;
    private static long threadJniEnv;
    private static long threadJniFunctionTable;
    private static long liveBytes;
    private static long peakLiveBytes;
    private static int associatedBuffers;
    private static int peakLiveRegions;
    private static int peakAssociatedBuffers;
    private static long allocationCount;
    private static long freeCount;
    private static long reallocationCount;
    private static long registrationCount;
    private static long collectedAssociationCount;
    private static long allocationFailureCount;
    private static int peakTemporaryBytes;
    private static long temporaryAllocationFailureCount;
    private static boolean telemetryBridgeAvailable = true;
    /**
     * Memory pressure for the wasm kernel runtime ({@code window.__gaiusKernels.reportPressure}):
     * live bytes are reported as a level of {@link #MAX_LIVE_BYTES} from half of it upwards, once
     * per {@link #PRESSURE_STEP_BYTES} of growth and at most once a second, and an allocation
     * failure reports 1. The allocation path only compares two longs.
     */
    private static final long PRESSURE_STEP_BYTES = Math.max(1L, MAX_LIVE_BYTES / 32L);
    private static final long PRESSURE_FLOOR_BYTES = MAX_LIVE_BYTES / 2L;
    private static final long PRESSURE_MIN_INTERVAL_MS = 1000L;
    private static long nextPressureBytes = PRESSURE_FLOOR_BYTES;
    private static long lastPressureReportMillis = Long.MIN_VALUE / 2L;
    private static boolean pressureBridgeAvailable = true;

    private BrowserMemory() {
    }

    static {
        for (int slot = 0; slot < ADDRESS_CACHE_SIZE; slot++) {
            CACHED_ADDRESS_BUFFERS[slot] = new WeakReference<>(null);
        }
    }

    public static ThreadLocal<byte[]> byteArrays() {
        return BYTE_ARRAYS;
    }

    public static ThreadLocal<char[]> charArrays() {
        return CHAR_ARRAYS;
    }

    /** Current number of live virtual native-memory regions. */
    public static int liveRegions() {
        purgeCollectedBuffers();
        publishTelemetry();
        return REGIONS.size();
    }

    /** Current byte capacity retained by live regions. */
    public static long liveBytes() {
        purgeCollectedBuffers();
        publishTelemetry();
        return liveBytes;
    }

    /** Current number of live Buffer-to-address associations. */
    public static int associatedBuffers() {
        purgeCollectedBuffers();
        publishTelemetry();
        return associatedBuffers;
    }

    public static int peakLiveRegions() {
        publishTelemetry();
        return peakLiveRegions;
    }

    public static long peakLiveBytes() {
        publishTelemetry();
        return peakLiveBytes;
    }

    public static int peakAssociatedBuffers() {
        publishTelemetry();
        return peakAssociatedBuffers;
    }

    public static long allocations() {
        publishTelemetry();
        return allocationCount;
    }

    public static long frees() {
        publishTelemetry();
        return freeCount;
    }

    public static long reallocations() {
        publishTelemetry();
        return reallocationCount;
    }

    public static long registrations() {
        publishTelemetry();
        return registrationCount;
    }

    /** Associations removed after their Buffer referents became unreachable. */
    public static long collectedAssociations() {
        purgeCollectedBuffers();
        publishTelemetry();
        return collectedAssociationCount;
    }

    public static long maxLiveBytes() {
        return MAX_LIVE_BYTES;
    }

    public static long allocationFailures() {
        publishTelemetry();
        return allocationFailureCount;
    }

    public static int maxTemporaryBytes() {
        return MAX_TEMPORARY_BYTES;
    }

    public static int peakTemporaryBytes() {
        publishTelemetry();
        return peakTemporaryBytes;
    }

    public static long temporaryAllocationFailures() {
        publishTelemetry();
        return temporaryAllocationFailureCount;
    }

    /** Number of cold Buffer identity-map probes; hot-cache hits do not allocate a key. */
    public static int addressLookupMisses() {
        return addressLookupMisses;
    }

    public static Class<?> bufferClass(int kind) {
        ByteBuffer bytes = ByteBuffer.allocate(0).order(ByteOrder.nativeOrder());
        return switch (kind) {
            case 1 -> bytes.asShortBuffer().getClass();
            case 2 -> bytes.asCharBuffer().getClass();
            case 3 -> bytes.asIntBuffer().getClass();
            case 4 -> bytes.asLongBuffer().getClass();
            case 5 -> bytes.asFloatBuffer().getClass();
            case 6 -> bytes.asDoubleBuffer().getClass();
            default -> bytes.getClass();
        };
    }

    public static void claimOwner(long address, int owner) {
    }

    public static long allocate(long byteCount) {
        return allocate(byteCount, RegionOwnership.EXPLICIT);
    }

    private static long allocate(long byteCount, RegionOwnership ownership) {
        if (byteCount < 0 || byteCount > Integer.MAX_VALUE) {
            throw allocationFailure("Unsupported browser allocation size: " + byteCount);
        }
        ensureLiveByteCapacity(byteCount);
        int id = nextRegionId++;
        ByteBuffer bytes = allocateByteBuffer((int) byteCount);
        return installRegion(id, bytes, ownership);
    }

    /** Alignment is a native-allocation concern; virtual browser addresses do not need it. */
    public static long alignedAllocate(long alignment, long byteCount) {
        return allocate(byteCount);
    }

    /**
     * Registers an existing byte buffer as a virtual native-memory region.
     *
     * <p>This is required for WebGL mapped buffers: Minecraft writes to mapped
     * buffers through LWJGL {@code MemoryUtil.memAddress/memCopy}. If the mapped
     * {@link ByteBuffer} is not registered here, {@link #address0(Buffer)} would
     * create a detached copy and later unmap would upload the still-empty mapped
     * buffer.</p>
     */
    public static long register(ByteBuffer bytes) {
        if (bytes == null) {
            return 0L;
        }
        long existing = findAddress(bytes);
        if (existing != 0L) {
            return existing;
        }
        int id = nextRegionId++;
        ByteBuffer region = bytes.duplicate().order(ByteOrder.nativeOrder());
        region.clear();
        ensureLiveByteCapacity(region.capacity());
        long address = installRegion(id, region, RegionOwnership.EXPLICIT);
        registrationCount++;
        remember(bytes, address);
        publishTelemetry();
        return address;
    }

    public static long calloc(long count, long size) {
        return allocate(Math.multiplyExact(count, size));
    }

    /** Returns the managed stand-in for LWJGL's browser-side JNIEnv pointer. */
    public static long threadJniEnv() {
        if (threadJniEnv == 0L) {
            long functionTable = allocate(32L * 1024L);
            long environment = 0L;
            try {
                environment = allocate(Long.BYTES);
                putLong(environment, functionTable);
                threadJniFunctionTable = functionTable;
                threadJniEnv = environment;
            } catch (RuntimeException | Error failure) {
                free(environment);
                free(functionTable);
                throw failure;
            }
        }
        return threadJniEnv;
    }

    /** Allocates and installs a per-thread JNI table stand-in for LWJGL. */
    public static long setupThreadEnv(int functionCount) {
        long entries = Math.max(4L, (long) functionCount + 4L);
        long byteCount = Math.multiplyExact(entries, Long.BYTES);
        if (threadJniEnv == 0L) {
            long functionTable = allocate(byteCount);
            long environment = 0L;
            try {
                environment = allocate(Long.BYTES);
                putLong(environment, functionTable);
                threadJniFunctionTable = functionTable;
                threadJniEnv = environment;
                return functionTable;
            } catch (RuntimeException | Error failure) {
                free(environment);
                free(functionTable);
                throw failure;
            }
        }
        long functionTable = allocate(byteCount);
        long previousTable = threadJniFunctionTable;
        try {
            putLong(threadJniEnv, functionTable);
        } catch (RuntimeException | Error failure) {
            free(functionTable);
            throw failure;
        }
        threadJniFunctionTable = functionTable;
        if (previousTable != 0L && previousTable != functionTable) {
            free(previousTable);
        }
        return functionTable;
    }

    public static long reallocate(long address, long byteCount) {
        if (address == 0) {
            return allocate(byteCount);
        }
        requireRegionBase(address);
        reallocationCount++;
        Region old = region(address);
        long replacement = allocate(byteCount);
        ByteBuffer source = old.bytes.duplicate();
        ByteBuffer target = region(replacement).bytes.duplicate();
        source.clear();
        source.limit(Math.min(source.capacity(), target.capacity()));
        target.put(source);
        free(address);
        return replacement;
    }

    public static void free(long address) {
        if (address == 0) {
            return;
        }
        requireRegionBase(address);
        int id = regionId(address);
        releaseRegion(id, null);
        purgeCollectedBuffers();
        publishTelemetry();
    }

    public static void free(Buffer buffer) {
        if (buffer != null) {
            long address = findAddress(buffer);
            if (address != 0L) {
                free(address);
            }
        }
    }

    public static long address0(Buffer buffer) {
        long address = findAddress(buffer);
        if (address != 0L) {
            return address;
        }
        if (buffer instanceof ByteBuffer bytes) {
            ensureLiveByteCapacity(bytes.capacity());
            ByteBuffer target = allocateByteBuffer(bytes.capacity());
            ByteBuffer source = bytes.duplicate();
            source.clear();
            target.put(source);
            target.clear();
            int id = nextRegionId++;
            long installedAddress = installRegion(id, target, RegionOwnership.AUTO_BUFFER_OWNED);
            try {
                remember(buffer, installedAddress);
                return installedAddress;
            } catch (RuntimeException | Error failure) {
                releaseRegion(id, null);
                publishTelemetry();
                throw failure;
            }
        }
        throw new IllegalArgumentException("Unregistered buffer view: " + buffer.getClass().getName());
    }

    /** Returns the address at the buffer's current element position. */
    public static long address(Buffer buffer) {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        return addressAt(buffer, buffer.position());
    }

    /** Null-preserving counterpart used by LWJGL's memAddressSafe overloads. */
    public static long addressSafe(Buffer buffer) {
        return buffer == null ? 0L : address(buffer);
    }

    /** Returns the address at an element index relative to the buffer origin. */
    public static long addressAt(Buffer buffer, int index) {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        return address0(buffer) + (Integer.toUnsignedLong(index) << elementShift(buffer));
    }

    private static int elementShift(Buffer buffer) {
        if (buffer instanceof ByteBuffer) {
            return 0;
        }
        if (buffer instanceof ShortBuffer || buffer instanceof CharBuffer) {
            return 1;
        }
        if (buffer instanceof IntBuffer || buffer instanceof FloatBuffer) {
            return 2;
        }
        return 3;
    }

    public static Buffer wrap(Class<?> type, long address, int capacity) {
        ByteBuffer bytes = transientSlice(address, byteCapacity(type, capacity));
        Buffer result;
        String name = type.getName();
        if (name.contains("Short")) {
            result = bytes.asShortBuffer();
        } else if (name.contains("Char")) {
            result = bytes.asCharBuffer();
        } else if (name.contains("Int")) {
            result = bytes.asIntBuffer();
        } else if (name.contains("Long")) {
            result = bytes.asLongBuffer();
        } else if (name.contains("Float")) {
            result = bytes.asFloatBuffer();
        } else if (name.contains("Double")) {
            result = bytes.asDoubleBuffer();
        } else {
            result = bytes;
        }
        remember(result, address);
        return result;
    }

    public static boolean getBoolean(long address) {
        return getByte(address) != 0;
    }

    public static ByteBuffer byteBuffer(long address, int capacity) {
        Region region = region(address);
        int offset = offset(address);
        if (capacity < 0 || offset < 0 || offset > region.bytes.capacity() - capacity) {
            throw new IndexOutOfBoundsException("Invalid virtual memory range");
        }
        ByteBuffer view = region.bytes.duplicate().order(ByteOrder.nativeOrder());
        view.position(offset);
        view.limit(offset + capacity);
        ByteBuffer slice = view.slice().order(ByteOrder.nativeOrder());
        remember(slice, address);
        return slice;
    }

    /** Returns the backing array for a managed allocation used by browser vertex writers. */
    public static byte[] data(long address) {
        Region region = region(address);
        if (region.data == null) {
            throw new IllegalStateException("Managed browser allocation is not array-backed");
        }
        return region.data;
    }

    /** Returns the array index represented by a managed address. */
    public static int dataOffset(long address) {
        Region region = region(address);
        if (region.data == null) {
            throw new IllegalStateException("Managed browser allocation is not array-backed");
        }
        int offset = offset(address);
        checkRange(region, offset, 0);
        return region.arrayOffset + offset;
    }

    public static ByteBuffer reallocate(ByteBuffer old, int size) {
        int position = old == null ? 0 : Math.min(old.position(), size);
        long address = reallocate(old == null ? 0 : address0(old), size);
        ByteBuffer result = byteBuffer(address, size);
        result.position(position);
        return result;
    }

    public static ShortBuffer reallocate(ShortBuffer old, int size) {
        return (ShortBuffer) reallocateView(old, size, 2, "Short");
    }

    public static IntBuffer reallocate(IntBuffer old, int size) {
        return (IntBuffer) reallocateView(old, size, 4, "Int");
    }

    public static LongBuffer reallocate(LongBuffer old, int size) {
        return (LongBuffer) reallocateView(old, size, 8, "Long");
    }

    public static FloatBuffer reallocate(FloatBuffer old, int size) {
        return (FloatBuffer) reallocateView(old, size, 4, "Float");
    }

    public static DoubleBuffer reallocate(DoubleBuffer old, int size) {
        return (DoubleBuffer) reallocateView(old, size, 8, "Double");
    }

    private static Buffer reallocateView(Buffer old, int size, int shift, String type) {
        int position = old == null ? 0 : Math.min(old.position(), size);
        long address = reallocate(old == null ? 0 : address0(old), (long) size * shift);
        ByteBuffer bytes = transientSlice(address, Math.multiplyExact(size, shift));
        Buffer result = switch (type) {
            case "Short" -> bytes.asShortBuffer();
            case "Int" -> bytes.asIntBuffer();
            case "Long" -> bytes.asLongBuffer();
            case "Float" -> bytes.asFloatBuffer();
            default -> bytes.asDoubleBuffer();
        };
        result.position(position);
        remember(result, address);
        return result;
    }

    public static byte getByte(long address) {
        return buffer(address).get(offset(address));
    }

    public static short getShort(long address) {
        return buffer(address).getShort(offset(address));
    }

    public static int getInt(long address) {
        return buffer(address).getInt(offset(address));
    }

    public static long getLong(long address) {
        return buffer(address).getLong(offset(address));
    }

    public static float getFloat(long address) {
        return buffer(address).getFloat(offset(address));
    }

    public static double getDouble(long address) {
        return buffer(address).getDouble(offset(address));
    }

    public static void putByte(long address, byte value) {
        buffer(address).put(offset(address), value);
    }

    public static void putShort(long address, short value) {
        buffer(address).putShort(offset(address), value);
    }

    public static void putInt(long address, int value) {
        buffer(address).putInt(offset(address), value);
    }

    public static void putLong(long address, long value) {
        buffer(address).putLong(offset(address), value);
    }

    public static void putFloat(long address, float value) {
        buffer(address).putFloat(offset(address), value);
    }

    public static void putDouble(long address, double value) {
        buffer(address).putDouble(offset(address), value);
    }

    /**
     * Browser fast path for the common GUI/text vertex start.
     */
    public static void putPosition(long pointer, float x, float y, float z) {
        Region region = region(pointer);
        int base = offset(pointer);
        checkRange(region, base, 12);
        if (region.data != null) {
            putPositionBytes(region.data, region.arrayOffset + base, x, y, z);
            return;
        }
        ByteBuffer bytes = region.bytes;
        bytes.putFloat(base, x);
        bytes.putFloat(base + 4, y);
        bytes.putFloat(base + 8, z);
    }

    /**
     * Browser fast path for {@code VertexConsumer.addVertex(Matrix4fc, x, y, z)}.
     *
     * <p>Vanilla's default interface method allocates a temporary vector and
     * then emits the transformed position through several pointer writes. Text
     * and GUI rendering call this for every vertex, so resolving the browser
     * address once and transforming directly is materially cheaper.</p>
     */
    public static void putTransformedPosition(long pointer, Matrix4fc pose, float x, float y, float z) {
        float tx = pose.m00() * x + pose.m10() * y + pose.m20() * z + pose.m30();
        float ty = pose.m01() * x + pose.m11() * y + pose.m21() * z + pose.m31();
        float tz = pose.m02() * x + pose.m12() * y + pose.m22() * z + pose.m32();
        putPosition(pointer, tx, ty, tz);
    }

    public static void putRgba(long pointer, int argb) {
        Region region = region(pointer);
        int base = offset(pointer);
        checkRange(region, base, 4);
        if (region.data != null) {
            putRgbaBytes(region.data, region.arrayOffset + base, argb);
            return;
        }
        putRgba(region.bytes, base, argb);
    }

    public static void putFloatPair(long pointer, float x, float y) {
        Region region = region(pointer);
        int base = offset(pointer);
        checkRange(region, base, 8);
        if (region.data != null) {
            putFloatPairBytes(region.data, region.arrayOffset + base, x, y);
            return;
        }
        ByteBuffer bytes = region.bytes;
        bytes.putFloat(base, x);
        bytes.putFloat(base + 4, y);
    }

    public static void putShortPair(long pointer, short x, short y) {
        Region region = region(pointer);
        int base = offset(pointer);
        checkRange(region, base, 4);
        if (region.data != null) {
            putShortPairBytes(region.data, region.arrayOffset + base, x, y);
            return;
        }
        ByteBuffer bytes = region.bytes;
        bytes.putShort(base, x);
        bytes.putShort(base + 2, y);
    }

    public static void putPackedUv(long pointer, int packedUv) {
        Region region = region(pointer);
        int base = offset(pointer);
        checkRange(region, base, 4);
        if (region.data != null) {
            putPackedUvBytes(region.data, region.arrayOffset + base, packedUv);
            return;
        }
        putPackedUv(region.bytes, base, packedUv);
    }

    public static void putNormal(long pointer, float x, float y, float z) {
        Region region = region(pointer);
        int base = offset(pointer);
        checkRange(region, base, 3);
        if (region.data != null) {
            putNormalBytes(region.data, region.arrayOffset + base, x, y, z);
            return;
        }
        ByteBuffer bytes = region.bytes;
        bytes.put(base, normalIntValue(x));
        bytes.put(base + 1, normalIntValue(y));
        bytes.put(base + 2, normalIntValue(z));
    }

    /**
     * Browser fast path for {@code BufferBuilder.addVertex(...)}.
     *
     * <p>The vanilla fast-format path writes a single vertex through many
     * {@code MemoryUtil.memPut*} calls. In the browser backend every virtual
     * address write has to decode the high 32-bit region id and look up the
     * backing {@link ByteBuffer}. Block/entity chunk builders call this path
     * for every vertex, so repeated address decoding becomes a dominant CPU and
     * GC cost. This method keeps the vanilla byte layout, but resolves the
     * virtual address once and performs the consecutive writes against the
     * backing buffer directly.</p>
     */
    public static void putFastVertex(
            long pointer,
            float x,
            float y,
            float z,
            int color,
            float u,
            float v,
            int overlayCoords,
            int lightCoords,
            float nx,
            float ny,
            float nz,
            boolean fullFormat) {
        Region region = region(pointer);
        int base = offset(pointer);
        checkRange(region, base, fullFormat ? 35 : 28);
        if (region.data != null) {
            putFastVertexBytes(
                    region.data,
                    region.arrayOffset + base,
                    x,
                    y,
                    z,
                    color,
                    u,
                    v,
                    overlayCoords,
                    lightCoords,
                    nx,
                    ny,
                    nz,
                    fullFormat);
            return;
        }
        ByteBuffer bytes = region.bytes;

        bytes.putFloat(base, x);
        bytes.putFloat(base + 4, y);
        bytes.putFloat(base + 8, z);
        putRgba(bytes, base + 12, color);
        bytes.putFloat(base + 16, u);
        bytes.putFloat(base + 20, v);

        int lightStart;
        if (fullFormat) {
            putPackedUv(bytes, base + 24, overlayCoords);
            lightStart = base + 28;
        } else {
            lightStart = base + 24;
        }

        putPackedUv(bytes, lightStart, lightCoords);
        if (fullFormat) {
            bytes.put(lightStart + 4, normalIntValue(nx));
            bytes.put(lightStart + 5, normalIntValue(ny));
            bytes.put(lightStart + 6, normalIntValue(nz));
        }
    }

    public static void set(long address, int value, long byteCount) {
        if (byteCount < 0 || byteCount > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Invalid memory fill size");
        }
        ByteBuffer target = transientView(address, (int) byteCount);
        byte fill = (byte) value;
        long fillLong = (long) (fill & 0xff) * 0x0101_0101_0101_0101L;
        while (target.remaining() >= Long.BYTES) {
            target.putLong(fillLong);
        }
        while (target.hasRemaining()) {
            target.put(fill);
        }
    }

    public static void copy(long source, long target, long byteCount) {
        if (byteCount < 0 || byteCount > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Invalid memory copy size");
        }
        int count = (int) byteCount;
        if (count == 0 || source == target) {
            return;
        }

        Region sourceRegion = region(source);
        Region targetRegion = region(target);
        int sourceOffset = offset(source);
        int targetOffset = offset(target);
        checkRange(sourceRegion, sourceOffset, count);
        checkRange(targetRegion, targetOffset, count);

        if (sourceRegion == targetRegion && rangesOverlap(sourceOffset, targetOffset, count)) {
            copyOverlapping(sourceRegion.bytes, sourceOffset, targetOffset, count);
            return;
        }
        ByteBuffer sourceView = transientView(sourceRegion, sourceOffset, count);
        ByteBuffer targetView = transientView(targetRegion, targetOffset, count);
        targetView.put(sourceView);
    }

    /** Browser implementation of libc's destination-first memset contract. */
    public static long cMemset(long target, int value, long byteCount) {
        set(target, value, byteCount);
        return target;
    }

    /** Browser implementation of libc's destination-first memcpy contract. */
    public static long cMemcpy(long target, long source, long byteCount) {
        copy(source, target, byteCount);
        return target;
    }

    /** BrowserMemory.copy already preserves memmove overlap semantics. */
    public static long cMemmove(long target, long source, long byteCount) {
        copy(source, target, byteCount);
        return target;
    }

    public static int lengthNt1(long address, int maximum) {
        int length = 0;
        while (length < maximum && getByte(address + length) != 0) {
            length++;
        }
        return length;
    }

    public static int lengthNt2(long address, int maximum) {
        int length = 0;
        while (length + Short.BYTES <= maximum && getShort(address + length) != 0) {
            length += Short.BYTES;
        }
        return length;
    }

    public static String decodeAscii(long address, int length) {
        byte[] bytes = temporaryBytes(length);
        transientView(address, length).get(bytes, 0, length);
        return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
    }

    public static long getCLong(long address) {
        return getLong(address);
    }

    public static void putCLong(long address, long value) {
        putLong(address, value);
    }

    public static ByteBuffer slice(ByteBuffer buffer) {
        int offset = buffer.position();
        ByteBuffer result = buffer.slice().order(buffer.order());
        registerDerived(result, buffer, offset);
        return result;
    }

    public static ShortBuffer slice(ShortBuffer buffer) {
        ShortBuffer result = buffer.slice();
        registerDerived(result, buffer, buffer.position() * 2);
        return result;
    }

    public static CharBuffer slice(CharBuffer buffer) {
        CharBuffer result = buffer.slice();
        registerDerived(result, buffer, buffer.position() * 2);
        return result;
    }

    public static IntBuffer slice(IntBuffer buffer) {
        IntBuffer result = buffer.slice();
        registerDerived(result, buffer, buffer.position() * 4);
        return result;
    }

    public static LongBuffer slice(LongBuffer buffer) {
        LongBuffer result = buffer.slice();
        registerDerived(result, buffer, buffer.position() * 8);
        return result;
    }

    public static FloatBuffer slice(FloatBuffer buffer) {
        FloatBuffer result = buffer.slice();
        registerDerived(result, buffer, buffer.position() * 4);
        return result;
    }

    public static DoubleBuffer slice(DoubleBuffer buffer) {
        DoubleBuffer result = buffer.slice();
        registerDerived(result, buffer, buffer.position() * 8);
        return result;
    }

    public static ByteBuffer slice(ByteBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        ByteBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        ByteBuffer result = view.slice().order(buffer.order());
        registerDerived(result, buffer, start);
        return result;
    }

    public static ShortBuffer slice(ShortBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        ShortBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        ShortBuffer result = view.slice();
        registerDerived(result, buffer, start * 2);
        return result;
    }

    public static CharBuffer slice(CharBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        CharBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        CharBuffer result = view.slice();
        registerDerived(result, buffer, start * 2);
        return result;
    }

    public static IntBuffer slice(IntBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        IntBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        IntBuffer result = view.slice();
        registerDerived(result, buffer, start * 4);
        return result;
    }

    public static LongBuffer slice(LongBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        LongBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        LongBuffer result = view.slice();
        registerDerived(result, buffer, start * 8);
        return result;
    }

    public static FloatBuffer slice(FloatBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        FloatBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        FloatBuffer result = view.slice();
        registerDerived(result, buffer, start * 4);
        return result;
    }

    public static DoubleBuffer slice(DoubleBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        DoubleBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        DoubleBuffer result = view.slice();
        registerDerived(result, buffer, start * 8);
        return result;
    }

    public static ByteBuffer duplicate(ByteBuffer buffer) {
        ByteBuffer result = buffer.duplicate().order(buffer.order());
        registerDerived(result, buffer, 0);
        return result;
    }

    public static ShortBuffer duplicate(ShortBuffer buffer) {
        ShortBuffer result = buffer.duplicate();
        registerDerived(result, buffer, 0);
        return result;
    }

    public static CharBuffer duplicate(CharBuffer buffer) {
        CharBuffer result = buffer.duplicate();
        registerDerived(result, buffer, 0);
        return result;
    }

    public static IntBuffer duplicate(IntBuffer buffer) {
        IntBuffer result = buffer.duplicate();
        registerDerived(result, buffer, 0);
        return result;
    }

    public static LongBuffer duplicate(LongBuffer buffer) {
        LongBuffer result = buffer.duplicate();
        registerDerived(result, buffer, 0);
        return result;
    }

    public static FloatBuffer duplicate(FloatBuffer buffer) {
        FloatBuffer result = buffer.duplicate();
        registerDerived(result, buffer, 0);
        return result;
    }

    public static DoubleBuffer duplicate(DoubleBuffer buffer) {
        DoubleBuffer result = buffer.duplicate();
        registerDerived(result, buffer, 0);
        return result;
    }

    public static int write8(long target, int offset, int value) {
        putByte(target + Integer.toUnsignedLong(offset), (byte) value);
        return offset + 1;
    }

    public static int write8Safe(long target, int offset, int limit, int value) {
        if (offset == limit) {
            throw new BufferOverflowException();
        }
        return write8(target, offset, value);
    }

    public static int write16(long target, int offset, char value) {
        putShort(target + Integer.toUnsignedLong(offset), (short) value);
        return offset + 2;
    }

    public static ByteBuffer slice(ByteBuffer source, long address, int capacity) {
        return byteBuffer(address, capacity).order(source.order());
    }

    public static Buffer slice(
            Class<?> type, Buffer source, long address, int capacity, long parentAddress) {
        Buffer result = wrap(type, address, capacity);
        if (result instanceof ByteBuffer bytes && source instanceof ByteBuffer sourceBytes) {
            bytes.order(sourceBytes.order());
        }
        return result;
    }

    public static Buffer duplicate(Class<?> type, Buffer source, long address) {
        Buffer result = wrap(type, address, source.capacity());
        result.limit(source.limit());
        result.position(source.position());
        if (result instanceof ByteBuffer bytes && source instanceof ByteBuffer sourceBytes) {
            bytes.order(sourceBytes.order());
        }
        return result;
    }

    public static String decodeUtf8(long address, int length) {
        if (length <= 0) {
            return "";
        }
        byte[] bytes = temporaryBytes(length);
        transientView(address, length).get(bytes, 0, length);
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private static int byteCapacity(Class<?> type, int capacity) {
        String name = type.getName();
        if (name.contains("Short") || name.contains("Char")) {
            return Math.multiplyExact(capacity, 2);
        }
        if (name.contains("Int") || name.contains("Float")) {
            return Math.multiplyExact(capacity, 4);
        }
        if (name.contains("Long") || name.contains("Double")) {
            return Math.multiplyExact(capacity, 8);
        }
        return capacity;
    }

    private static void registerDerived(Buffer result, Buffer source, int byteOffset) {
        long base = findAddress(source);
        if (base == 0L) {
            return;
        }
        remember(result, base + Integer.toUnsignedLong(byteOffset));
    }

    private static int sliceStart(Buffer buffer, int offset, int capacity) {
        if (offset < 0 || capacity < 0) {
            throw new IllegalArgumentException();
        }
        int start = Math.addExact(buffer.position(), offset);
        if (start > buffer.limit() || capacity > buffer.capacity() - start) {
            throw new IllegalArgumentException();
        }
        return start;
    }

    private static void remember(Buffer buffer, long address) {
        purgeCollectedBuffers();
        int id = regionId(address);
        Region targetRegion = region(address);
        BufferReference reference = new BufferReference(buffer, id);
        if (ADDRESSES.put(reference, address) != null) {
            throw new IllegalStateException("Buffer already has a virtual address");
        }
        targetRegion.buffers.add(reference);
        associatedBuffers++;
        peakAssociatedBuffers = Math.max(peakAssociatedBuffers, associatedBuffers);
        cacheAddress(buffer, address);
    }

    private static long findAddress(Buffer buffer) {
        int cacheSlot = addressCacheSlot(buffer);
        long cachedAddress = CACHED_BUFFER_ADDRESSES[cacheSlot];
        if (cachedAddress != 0L && CACHED_ADDRESS_BUFFERS[cacheSlot].get() == buffer) {
            return cachedAddress;
        }
        purgeCollectedBuffers();
        addressLookupMisses++;
        Long address = ADDRESSES.get(new BufferReference(buffer));
        if (address == null) {
            return 0L;
        }
        cacheAddress(buffer, address.longValue());
        return address.longValue();
    }

    private static void cacheAddress(Buffer buffer, long address) {
        int slot = addressCacheSlot(buffer);
        CACHED_ADDRESS_BUFFERS[slot] = new WeakReference<>(buffer);
        CACHED_BUFFER_ADDRESSES[slot] = address;
    }

    private static int addressCacheSlot(Buffer buffer) {
        return System.identityHashCode(buffer) & ADDRESS_CACHE_MASK;
    }

    private static void clearAddressCacheRegion(int regionId) {
        for (int slot = 0; slot < ADDRESS_CACHE_SIZE; slot++) {
            long address = CACHED_BUFFER_ADDRESSES[slot];
            if (address == 0L || regionId(address) != regionId) {
                continue;
            }
            CACHED_ADDRESS_BUFFERS[slot].clear();
            CACHED_BUFFER_ADDRESSES[slot] = 0L;
        }
    }

    private static long installRegion(int id, ByteBuffer bytes, RegionOwnership ownership) {
        purgeCollectedBuffers();
        Region region = new Region(bytes, ownership);
        ensureLiveByteCapacity(region.capacity);
        if (REGIONS.put(id, region) != null) {
            throw new IllegalStateException("Virtual memory region id collision: " + id);
        }
        liveBytes = Math.addExact(liveBytes, region.capacity);
        allocationCount++;
        peakLiveRegions = Math.max(peakLiveRegions, REGIONS.size());
        peakLiveBytes = Math.max(peakLiveBytes, liveBytes);
        publishTelemetry();
        if (liveBytes >= nextPressureBytes) {
            reportPressure((double) liveBytes / (double) MAX_LIVE_BYTES, false);
        }
        return (long) id << 32;
    }

    private static void purgeCollectedBuffers() {
        BufferReference reference;
        boolean changed = false;
        while ((reference = (BufferReference) COLLECTED_BUFFERS.poll()) != null) {
            if (ADDRESSES.remove(reference) == null) {
                continue;
            }
            Region owner = REGIONS.get(reference.regionId);
            if (owner != null) {
                owner.buffers.remove(reference);
            }
            associatedBuffers--;
            collectedAssociationCount++;
            releaseAutomaticRegionIfUnreferenced(reference.regionId, owner);
            changed = true;
        }
        if (changed) {
            publishTelemetry();
        }
    }

    private static void releaseAutomaticRegionIfUnreferenced(int id, Region region) {
        if (region != null
                && region.ownership == RegionOwnership.AUTO_BUFFER_OWNED
                && region.buffers.isEmpty()) {
            releaseRegion(id, region);
        }
    }

    private static boolean releaseRegion(int id, Region expected) {
        Region removed = REGIONS.get(id);
        if (removed == null || (expected != null && removed != expected)) {
            return false;
        }
        REGIONS.remove(id);
        if (id == cachedRegionId) {
            cachedRegionId = 0;
            cachedRegion = null;
        }
        clearAddressCacheRegion(id);
        liveBytes -= removed.capacity;
        freeCount++;
        if (liveBytes + 2L * PRESSURE_STEP_BYTES < nextPressureBytes) {
            // Memory went well below the last report: report again once it grows back.
            nextPressureBytes = Math.max(PRESSURE_FLOOR_BYTES, liveBytes + PRESSURE_STEP_BYTES);
        }
        for (BufferReference reference : removed.buffers) {
            if (ADDRESSES.remove(reference) != null) {
                associatedBuffers--;
            }
            reference.clear();
        }
        removed.buffers.clear();
        return true;
    }

    private static void publishTelemetry() {
        if (!telemetryBridgeAvailable) {
            return;
        }
        try {
            publishTelemetryBrowser(
                    REGIONS.size(),
                    liveBytes,
                    associatedBuffers,
                    peakLiveRegions,
                    peakLiveBytes,
                    peakAssociatedBuffers,
                    allocationCount,
                    freeCount,
                    registrationCount,
                    reallocationCount,
                    collectedAssociationCount,
                    addressLookupMisses,
                    MAX_LIVE_BYTES,
                    allocationFailureCount,
                    MAX_TEMPORARY_BYTES,
                    peakTemporaryBytes,
                    temporaryAllocationFailureCount);
        } catch (Throwable ignored) {
            // JVM-side lifecycle tests do not install TeaVM JS bodies.
            telemetryBridgeAvailable = false;
        }
    }

    @JSBody(params = {
            "liveRegions", "liveBytes", "associatedBuffers",
            "peakLiveRegions", "peakLiveBytes", "peakAssociatedBuffers",
            "allocations", "frees", "registrations", "reallocations",
            "collectedAssociations", "addressLookupMisses",
            "maxLiveBytes", "allocationFailures", "maxTemporaryBytes",
            "peakTemporaryBytes", "temporaryAllocationFailures"
    }, script = """
            let root = globalThis.__gaiusMemoryTelemetry;
            if (!root || typeof root !== 'object' || Array.isArray(root)) {
              root = {};
              globalThis.__gaiusMemoryTelemetry = root;
            }
            let memory = root.browserMemory;
            if (!memory || typeof memory !== 'object' || Array.isArray(memory)) {
              memory = {};
              root.browserMemory = memory;
            }
            memory.liveRegions = liveRegions | 0;
            memory.liveBytes = Number(liveBytes);
            memory.associatedBuffers = associatedBuffers | 0;
            memory.peakLiveRegions = peakLiveRegions | 0;
            memory.peakLiveBytes = Number(peakLiveBytes);
            memory.peakAssociatedBuffers = peakAssociatedBuffers | 0;
            memory.allocations = Number(allocations);
            memory.frees = Number(frees);
            memory.registrations = Number(registrations);
            memory.reallocations = Number(reallocations);
            memory.collectedAssociations = Number(collectedAssociations);
            memory.addressLookupMisses = addressLookupMisses | 0;
            memory.maxLiveBytes = Number(maxLiveBytes);
            memory.allocationFailures = Number(allocationFailures);
            memory.maxTemporaryBytes = maxTemporaryBytes | 0;
            memory.peakTemporaryBytes = peakTemporaryBytes | 0;
            memory.temporaryAllocationFailures = Number(temporaryAllocationFailures);
            """)
    private static native void publishTelemetryBrowser(
            int liveRegions,
            long liveBytes,
            int associatedBuffers,
            int peakLiveRegions,
            long peakLiveBytes,
            int peakAssociatedBuffers,
            long allocations,
            long frees,
            long registrations,
            long reallocations,
            long collectedAssociations,
            int addressLookupMisses,
            long maxLiveBytes,
            long allocationFailures,
            int maxTemporaryBytes,
            int peakTemporaryBytes,
            long temporaryAllocationFailures);

    private static long configuredMaxLiveBytes() {
        String configured = System.getProperty("gaius.browser.memory.maxBytes");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_LIVE_BYTES;
        }
        try {
            return Math.min(HARD_MAX_LIVE_BYTES, Math.max(1L, Long.parseLong(configured)));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_LIVE_BYTES;
        }
    }

    private static int configuredMaxTemporaryBytes() {
        String configured = System.getProperty("gaius.browser.memory.maxTemporaryBytes");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_TEMPORARY_BYTES;
        }
        try {
            return (int) Math.min(
                    HARD_MAX_TEMPORARY_BYTES,
                    Math.max(1L, Long.parseLong(configured)));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_TEMPORARY_BYTES;
        }
    }

    private static void ensureLiveByteCapacity(long additionalBytes) {
        if (additionalBytes < 0L || liveBytes > MAX_LIVE_BYTES - additionalBytes) {
            throw allocationFailure(
                    "Browser native memory budget exceeded: live=" + liveBytes
                            + " requested=" + additionalBytes
                            + " limit=" + MAX_LIVE_BYTES);
        }
    }

    private static ByteBuffer allocateByteBuffer(int byteCount) {
        try {
            return ByteBuffer.allocate(byteCount).order(ByteOrder.nativeOrder());
        } catch (OutOfMemoryError failure) {
            recordAllocationFailure();
            throw failure;
        }
    }

    private static OutOfMemoryError allocationFailure(String message) {
        recordAllocationFailure();
        return new OutOfMemoryError(message);
    }

    private static void recordAllocationFailure() {
        allocationFailureCount++;
        publishTelemetry();
        reportPressure(1.0, true);
    }

    private static void reportPressure(double level, boolean urgent) {
        nextPressureBytes = Math.max(PRESSURE_FLOOR_BYTES, liveBytes + PRESSURE_STEP_BYTES);
        if (!pressureBridgeAvailable) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!urgent && now - lastPressureReportMillis < PRESSURE_MIN_INTERVAL_MS) {
            return;
        }
        lastPressureReportMillis = now;
        try {
            reportPressureBrowser(Math.min(1.0, level));
        } catch (Throwable ignored) {
            // JVM-side lifecycle tests do not install TeaVM JS bodies.
            pressureBridgeAvailable = false;
        }
    }

    @JSBody(params = "level", script = """
            const kernels = globalThis.__gaiusKernels;
            if (kernels && typeof kernels.reportPressure === 'function') {
              try {
                kernels.reportPressure(level);
              } catch (ignored) {
              }
            }
            """)
    private static native void reportPressureBrowser(double level);

    private static ByteBuffer buffer(long address) {
        return region(address).bytes;
    }

    private static byte[] temporaryBytes(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Invalid temporary byte length: " + length);
        }
        if (length > MAX_TEMPORARY_BYTES) {
            temporaryAllocationFailureCount++;
            throw allocationFailure(
                    "Browser temporary decode budget exceeded: requested=" + length
                            + " limit=" + MAX_TEMPORARY_BYTES);
        }
        if (length > peakTemporaryBytes) {
            peakTemporaryBytes = length;
            publishTelemetry();
        }
        byte[] bytes = BYTE_ARRAYS.get();
        if (length <= bytes.length) {
            return bytes;
        }
        try {
            return new byte[length];
        } catch (OutOfMemoryError failure) {
            temporaryAllocationFailureCount++;
            recordAllocationFailure();
            throw failure;
        }
    }

    private static void putRgba(ByteBuffer bytes, int offset, int argb) {
        int abgr = (argb & 0xff00_ff00)
                | ((argb >>> 16) & 0x0000_00ff)
                | ((argb & 0x0000_00ff) << 16);
        bytes.putInt(offset, IS_LITTLE_ENDIAN ? abgr : Integer.reverseBytes(abgr));
    }

    private static void putPackedUv(ByteBuffer bytes, int offset, int packedUv) {
        if (IS_LITTLE_ENDIAN) {
            bytes.putInt(offset, packedUv);
        } else {
            bytes.putShort(offset, (short) (packedUv & 0xffff));
            bytes.putShort(offset + 2, (short) ((packedUv >>> 16) & 0xffff));
        }
    }

    private static byte normalIntValue(float value) {
        float clamped = value < -1.0f ? -1.0f : Math.min(value, 1.0f);
        return (byte) (((int) (clamped * 127.0f)) & 0xff);
    }

    @JSBody(params = {"bytes", "base", "x", "y", "z"}, script = """
            const view=bytes.__gaiusDataView
              || (bytes.__gaiusDataView=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength));
            view.setFloat32(base,x,true);
            view.setFloat32(base+4,y,true);
            view.setFloat32(base+8,z,true);
            """)
    public static native void putPositionBytes(
            @JSByRef byte[] bytes, int base, float x, float y, float z);

    public static void putTransformedPositionBytes(
            byte[] bytes, int base, Matrix4fc pose, float x, float y, float z) {
        putPositionBytes(
                bytes,
                base,
                pose.m00() * x + pose.m10() * y + pose.m20() * z + pose.m30(),
                pose.m01() * x + pose.m11() * y + pose.m21() * z + pose.m31(),
                pose.m02() * x + pose.m12() * y + pose.m22() * z + pose.m32());
    }

    @JSBody(params = {"bytes", "base", "argb"}, script = """
            const view=bytes.__gaiusDataView
              || (bytes.__gaiusDataView=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength));
            const abgr=(argb&-16711936)|((argb>>>16)&255)|((argb&255)<<16);
            view.setInt32(base,abgr,true);
            """)
    public static native void putRgbaBytes(@JSByRef byte[] bytes, int base, int argb);

    @JSBody(params = {"bytes", "base", "x", "y"}, script = """
            const view=bytes.__gaiusDataView
              || (bytes.__gaiusDataView=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength));
            view.setFloat32(base,x,true);
            view.setFloat32(base+4,y,true);
            """)
    public static native void putFloatPairBytes(
            @JSByRef byte[] bytes, int base, float x, float y);

    @JSBody(params = {"bytes", "base", "x", "y"}, script = """
            const view=bytes.__gaiusDataView
              || (bytes.__gaiusDataView=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength));
            view.setInt16(base,x,true);
            view.setInt16(base+2,y,true);
            """)
    public static native void putShortPairBytes(
            @JSByRef byte[] bytes, int base, short x, short y);

    @JSBody(params = {"bytes", "base", "packedUv"}, script = """
            const view=bytes.__gaiusDataView
              || (bytes.__gaiusDataView=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength));
            view.setInt32(base,packedUv,true);
            """)
    public static native void putPackedUvBytes(
            @JSByRef byte[] bytes, int base, int packedUv);

    @JSBody(params = {"bytes", "base", "x", "y", "z"}, script = """
            bytes[base]=(Math.max(-1,Math.min(x,1))*127)|0;
            bytes[base+1]=(Math.max(-1,Math.min(y,1))*127)|0;
            bytes[base+2]=(Math.max(-1,Math.min(z,1))*127)|0;
            """)
    public static native void putNormalBytes(
            @JSByRef byte[] bytes, int base, float x, float y, float z);

    @JSBody(params = {
            "bytes", "base", "x", "y", "z", "color", "u", "v",
            "overlayCoords", "lightCoords", "nx", "ny", "nz", "fullFormat"
    }, script = """
            const view=bytes.__gaiusDataView
              || (bytes.__gaiusDataView=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength));
            view.setFloat32(base,x,true);
            view.setFloat32(base+4,y,true);
            view.setFloat32(base+8,z,true);
            const abgr=(color&-16711936)|((color>>>16)&255)|((color&255)<<16);
            view.setInt32(base+12,abgr,true);
            view.setFloat32(base+16,u,true);
            view.setFloat32(base+20,v,true);
            let lightStart=base+24;
            if (fullFormat) {
              view.setInt32(lightStart,overlayCoords,true);
              lightStart+=4;
            }
            view.setInt32(lightStart,lightCoords,true);
            if (fullFormat) {
              bytes[lightStart+4]=(Math.max(-1,Math.min(nx,1))*127)|0;
              bytes[lightStart+5]=(Math.max(-1,Math.min(ny,1))*127)|0;
              bytes[lightStart+6]=(Math.max(-1,Math.min(nz,1))*127)|0;
            }
            """)
    public static native void putFastVertexBytes(
            @JSByRef byte[] bytes,
            int base,
            float x,
            float y,
            float z,
            int color,
            float u,
            float v,
            int overlayCoords,
            int lightCoords,
            float nx,
            float ny,
            float nz,
            boolean fullFormat);

    /**
     * Returns an unregistered view for short-lived internal operations.
     *
     * <p>Public {@link #byteBuffer(long, int)} and {@link #slice(ByteBuffer)}
     * must preserve LWJGL address lookup semantics by registering the returned
     * buffer. High-frequency implementation details such as memCopy, memSet,
     * and string decoding do not need an addressable returned object. Avoiding
     * registration here avoids even temporary weak-association metadata and
     * reduces TeaVM/JS garbage pressure.</p>
     */
    private static ByteBuffer transientView(long address, int capacity) {
        Region region = region(address);
        return transientView(region, offset(address), capacity);
    }

    private static ByteBuffer transientSlice(long address, int capacity) {
        return transientView(address, capacity).slice().order(ByteOrder.nativeOrder());
    }

    private static ByteBuffer transientView(Region region, int offset, int capacity) {
        return transientView(region.bytes, offset, capacity);
    }

    private static ByteBuffer transientView(ByteBuffer bytes, int offset, int capacity) {
        checkRange(bytes, offset, capacity);
        ByteBuffer view = bytes.duplicate().order(ByteOrder.nativeOrder());
        view.position(offset);
        view.limit(offset + capacity);
        return view;
    }

    private static void checkRange(Region region, int offset, int capacity) {
        if (capacity < 0 || offset < 0 || offset > region.capacity - capacity) {
            throw new IndexOutOfBoundsException("Invalid virtual memory range");
        }
    }

    private static void checkRange(ByteBuffer bytes, int offset, int capacity) {
        if (capacity < 0 || offset < 0 || offset > bytes.capacity() - capacity) {
            throw new IndexOutOfBoundsException("Invalid virtual memory range");
        }
    }

    private static boolean rangesOverlap(int sourceOffset, int targetOffset, int count) {
        return sourceOffset < targetOffset + count && targetOffset < sourceOffset + count;
    }

    private static void copyOverlapping(ByteBuffer bytes, int sourceOffset, int targetOffset, int count) {
        byte[] temporary = BYTE_ARRAYS.get();
        if (targetOffset > sourceOffset) {
            int remaining = count;
            while (remaining > 0) {
                int chunk = Math.min(temporary.length, remaining);
                int chunkOffset = remaining - chunk;
                ByteBuffer sourceView = transientView(bytes, sourceOffset + chunkOffset, chunk);
                ByteBuffer targetView = transientView(bytes, targetOffset + chunkOffset, chunk);
                sourceView.get(temporary, 0, chunk);
                targetView.put(temporary, 0, chunk);
                remaining -= chunk;
            }
            return;
        }

        int copied = 0;
        while (copied < count) {
            int chunk = Math.min(temporary.length, count - copied);
            ByteBuffer sourceView = transientView(bytes, sourceOffset + copied, chunk);
            ByteBuffer targetView = transientView(bytes, targetOffset + copied, chunk);
            sourceView.get(temporary, 0, chunk);
            targetView.put(temporary, 0, chunk);
            copied += chunk;
        }
    }

    private static Region region(long address) {
        int id = regionId(address);
        if (id == cachedRegionId && cachedRegion != null) {
            return cachedRegion;
        }
        Region region = REGIONS.get(id);
        if (region == null) {
            throw new IllegalStateException("Unknown virtual address: " + Long.toUnsignedString(address));
        }
        cachedRegionId = id;
        cachedRegion = region;
        return region;
    }

    private static int regionId(long address) {
        return (int) (address >>> 32);
    }

    private static int offset(long address) {
        long offset = address & 0xffff_ffffL;
        if (offset > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Virtual address offset is too large");
        }
        return (int) offset;
    }

    private static void requireRegionBase(long address) {
        if (offset(address) != 0) {
            throw new IllegalArgumentException(
                    "Cannot release an interior virtual address: " + Long.toUnsignedString(address));
        }
    }

    private enum RegionOwnership {
        EXPLICIT,
        AUTO_BUFFER_OWNED
    }

    private static final class Region {
        private final ByteBuffer bytes;
        private final byte[] data;
        private final int arrayOffset;
        private final int capacity;
        private final RegionOwnership ownership;
        private final Set<BufferReference> buffers = new HashSet<>();

        private Region(ByteBuffer bytes, RegionOwnership ownership) {
            this.bytes = bytes;
            this.data = bytes.hasArray() ? bytes.array() : null;
            this.arrayOffset = bytes.hasArray() ? bytes.arrayOffset() : 0;
            this.capacity = bytes.capacity();
            this.ownership = ownership;
        }
    }

    /**
     * Identity key that never keeps a derived Buffer alive.
     *
     * <p>TeaVM implements {@link WeakReference} with JavaScript {@code WeakRef}
     * and enqueues it through {@code FinalizationRegistry} in supported modern
     * browsers. Current Chrome provides both APIs. Explicit region release does
     * not depend on GC: {@link #free(long)} removes and clears every key owned
     * by that region synchronously.</p>
     */
    private static final class BufferReference extends WeakReference<Buffer> {
        private final int identityHash;
        private final int regionId;

        private BufferReference(Buffer buffer) {
            super(buffer);
            this.identityHash = System.identityHashCode(buffer);
            this.regionId = 0;
        }

        private BufferReference(Buffer buffer, int regionId) {
            super(buffer, COLLECTED_BUFFERS);
            this.identityHash = System.identityHashCode(buffer);
            this.regionId = regionId;
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof BufferReference reference)) {
                return false;
            }
            Buffer buffer = get();
            return buffer != null && buffer == reference.get();
        }
    }
}
