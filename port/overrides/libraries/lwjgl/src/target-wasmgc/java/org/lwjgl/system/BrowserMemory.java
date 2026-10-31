package org.lwjgl.system;

import dev.gaius.browser.JsArrays;
import dev.gaius.browser.wasm.LinearMemory;
import java.lang.ref.WeakReference;
import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import org.joml.Matrix4fc;
import org.teavm.interop.Address;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Float64Array;
import org.teavm.runtime.heap.Heap;

/**
 * LWJGL native memory for the WebAssembly GC build: real pointers into TeaVM linear memory
 * (design D6).
 *
 * <p>The JavaScript build ({@code src/main/java}) emulates native memory with virtual addresses
 * over Java byte buffers. Here an address is a linear-memory byte offset: {@code nmemAlloc},
 * {@code nmemRealloc} and {@code nmemFree} map to TeaVM's {@link Heap} allocator, scalar access
 * uses {@link Address}, and {@code memByteBuffer(address, length)} is a direct buffer view of
 * that memory (the class library's {@code java.nio.GaiusLinearMemory}, reached through the
 * {@link LinearMemory} stand-in), so the WebGL and WebAudio layers can hand it to JS without a
 * copy ({@code fromJavaBuffer}). The public API is the same as the JavaScript build's, so
 * {@code BrowserMemoryBackend} and the patched LWJGL classes are shared.
 *
 * <p>Allocations are 8-byte aligned (TeaVM's allocator aligns to 4, and a Float64Array or
 * BigInt64Array view needs 8). A heap {@link ByteBuffer} has no linear-memory address; asking
 * for one copies it into a detached allocation, like the JavaScript build's automatic regions,
 * which is only valid for data the native side reads.
 */
public final class BrowserMemory {
    private static final long HARD_MAX_LIVE_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int HARD_MAX_TEMPORARY_BYTES = 16 * 1024 * 1024;
    private static final long MAX_LIVE_BYTES = configuredMaxLiveBytes();
    private static final int MAX_TEMPORARY_BYTES = configuredMaxTemporaryBytes();
    private static final int TEMP_BYTES_SIZE = 65536;
    private static final int HEAP_COPY_SLOTS = 64;
    private static final boolean IS_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    /** Live allocation sizes by (aligned) address. */
    private static final IntMap SIZES = new IntMap();
    /** Allocator base of an allocation whose returned address was moved for 8-byte alignment. */
    private static final IntMap BASES = new IntMap();
    private static final IntMap CAPACITIES = new IntMap();
    private static final IntMap RESERVATIONS = new IntMap();
    private static final IntMap OWNERS = new IntMap();
    private static final int POOL_MAX_SHIFT = 16;
    private static final int POOL_SLOTS = 8;
    private static final long POOL_MAX_BYTES = configuredPoolBytes();
    private static final int[][] FREE_LISTS = new int[POOL_MAX_SHIFT + 1][POOL_SLOTS];
    private static final int[] FREE_COUNTS = new int[POOL_MAX_SHIFT + 1];
    private static final long[] OWNER_LIVE = new long[5];
    private static final long[] OWNER_PEAK = new long[5];
    private static long reservedBytes;
    private static long peakReservedBytes;
    private static long pooledBytes;
    private static long reuseHits;
    private static long inPlaceReallocations;
    private static final ThreadLocal<byte[]> BYTE_ARRAYS = ThreadLocal.withInitial(() -> new byte[TEMP_BYTES_SIZE]);
    private static final ThreadLocal<char[]> CHAR_ARRAYS = ThreadLocal.withInitial(() -> new char[8192]);
    private static final Class<?>[] BUFFER_CLASSES = new Class<?>[7];
    @SuppressWarnings("unchecked")
    private static final WeakReference<Buffer>[] HEAP_COPY_SOURCES = new WeakReference[HEAP_COPY_SLOTS];
    private static final long[] HEAP_COPY_ADDRESSES = new long[HEAP_COPY_SLOTS];
    private static int nextHeapCopySlot;
    private static long threadJniEnv;
    private static long threadJniFunctionTable;
    private static long liveBytes;
    private static long peakLiveBytes;
    private static int peakLiveRegions;
    private static long allocationCount;
    private static long freeCount;
    private static long reallocationCount;
    private static long registrationCount;
    private static long allocationFailureCount;
    private static int peakTemporaryBytes;
    private static long temporaryAllocationFailureCount;
    private static int addressLookupMisses;
    private static boolean telemetryBridgeAvailable = true;
    /**
     * Allocations and frees publish at most this often; failures publish at once. The clock is
     * read once per TELEMETRY_CHECK_OPERATIONS calls (System.currentTimeMillis crosses to JS
     * and comes back as a BigInt).
     */
    private static final long TELEMETRY_INTERVAL_MILLIS = 250L;
    private static final int TELEMETRY_CHECK_OPERATIONS = 64;
    private static long lastTelemetryMillis = Long.MIN_VALUE / 2L;
    private static int telemetryOperations;
    private static final int SIZE_BUCKETS = 8;
    private static final long[] SIZE_LIVE = new long[SIZE_BUCKETS];
    private static final long[] SIZE_COUNTS = new long[SIZE_BUCKETS];
    private static final double[] TELEMETRY = new double[49];
    private static final long PRESSURE_STEP_BYTES = Math.max(1L, MAX_LIVE_BYTES / 32L);
    private static final long PRESSURE_FLOOR_BYTES = MAX_LIVE_BYTES / 2L;
    private static final long PRESSURE_MIN_INTERVAL_MS = 1000L;
    private static long nextPressureBytes = PRESSURE_FLOOR_BYTES;
    private static long lastPressureReportMillis = Long.MIN_VALUE / 2L;
    private static boolean pressureBridgeAvailable = true;

    private static int sizeBucket(int bytes) {
        if (bytes <= 64 * 1024) return 0;
        if (bytes <= 256 * 1024) return 1;
        if (bytes <= 1024 * 1024) return 2;
        if (bytes <= 4 * 1024 * 1024) return 3;
        if (bytes <= 16 * 1024 * 1024) return 4;
        if (bytes <= 64 * 1024 * 1024) return 5;
        if (bytes <= 256 * 1024 * 1024) return 6;
        return 7;
    }

    private static void sizeLive(int bytes, long delta, long countDelta) {
        int bucket = sizeBucket(bytes);
        SIZE_LIVE[bucket] += delta;
        SIZE_COUNTS[bucket] += countDelta;
    }

    private BrowserMemory() {
    }

    public static ThreadLocal<byte[]> byteArrays() {
        return BYTE_ARRAYS;
    }

    public static ThreadLocal<char[]> charArrays() {
        return CHAR_ARRAYS;
    }

    public static int liveRegions() {
        return SIZES.size();
    }

    public static long liveBytes() {
        return liveBytes;
    }

    public static int associatedBuffers() {
        return 0;
    }

    public static int peakLiveRegions() {
        return peakLiveRegions;
    }

    public static long peakLiveBytes() {
        return peakLiveBytes;
    }

    public static int peakAssociatedBuffers() {
        return 0;
    }

    public static long allocations() {
        return allocationCount;
    }

    public static long frees() {
        return freeCount;
    }

    public static long reallocations() {
        return reallocationCount;
    }

    public static long registrations() {
        return registrationCount;
    }

    public static long collectedAssociations() {
        return 0L;
    }

    public static long maxLiveBytes() {
        return MAX_LIVE_BYTES;
    }

    public static long allocationFailures() {
        return allocationFailureCount;
    }

    public static int maxTemporaryBytes() {
        return MAX_TEMPORARY_BYTES;
    }

    public static int peakTemporaryBytes() {
        return peakTemporaryBytes;
    }

    public static long temporaryAllocationFailures() {
        return temporaryAllocationFailureCount;
    }

    /** Heap buffers whose address was requested (each one is copied). */
    public static int addressLookupMisses() {
        return addressLookupMisses;
    }

    /** Direct (linear-memory) buffer classes: 0 byte, 1 short, 2 char, 3 int, 4 long, 5 float, 6 double. */
    public static Class<?> bufferClass(int kind) {
        int index = kind < 0 || kind > 6 ? 0 : kind;
        Class<?> cached = BUFFER_CLASSES[index];
        if (cached != null) {
            return cached;
        }
        ByteBuffer bytes = LinearMemory.wrap(8, 0).order(ByteOrder.nativeOrder());
        Class<?> type = switch (index) {
            case 1 -> bytes.asShortBuffer().getClass();
            case 2 -> bytes.asCharBuffer().getClass();
            case 3 -> bytes.asIntBuffer().getClass();
            case 4 -> bytes.asLongBuffer().getClass();
            case 5 -> bytes.asFloatBuffer().getClass();
            case 6 -> bytes.asDoubleBuffer().getClass();
            default -> bytes.getClass();
        };
        BUFFER_CLASSES[index] = type;
        return type;
    }

    public static long allocate(long byteCount) {
        return allocateOwned(byteCount, 0);
    }

    /** Records the vanilla section builder sizing inputs and applies the low tier cap. */
    public static int sectionBuilderCount(int requested, int perPackBytes) {
        long maxMemory = Runtime.getRuntime().maxMemory();
        int processors = Runtime.getRuntime().availableProcessors();
        int budgetCount = (int) Math.max(1L, (long) (maxMemory * 0.3d) / Math.max(1, perPackBytes));
        // WasmGC uses cooperative scheduling: only one section compiler executes at a time.
        // Keep vanilla's maxMemory budget as an upper bound, but do not preallocate one pack
        // per host processor as the browser's Runtime.availableProcessors() reports.
        int cooperativeWorkers = sectionPackOverride();
        int count = Math.max(1, Math.min(cooperativeWorkers, Math.min(requested, budgetCount)));
        publishSectionBuilderTelemetry(count, requested, perPackBytes, maxMemory, processors);
        return count;
    }

    @JSBody(script = """
            try {
              const raw = new URLSearchParams(globalThis.location?.search || '').get('gaiusSectionPacks');
              const value = Number(raw);
              return Number.isFinite(value) && value >= 1 ? Math.min(64, Math.floor(value)) : 1;
            } catch (ignored) { return 1; }
            """)
    private static native int sectionPackOverride();

    @JSBody(params = {"count", "requested", "perPack", "maxMemory", "processors"}, script = """
            if (globalThis.__gaiusMemoryTelemetryEnabled !== true) return;
            const root = globalThis.__gaiusMemoryTelemetry || (globalThis.__gaiusMemoryTelemetry = {});
            root.sectionBuffers = {count, requested, perPackBytes: perPack, maxMemory, availableProcessors: processors,
              totalBytes: count * perPack};
            """)
    private static native void publishSectionBuilderTelemetry(int count, int requested, int perPack,
            long maxMemory, int processors);

    public static void claimOwner(long address, int owner) {
        if (owner < 0 || owner >= OWNER_LIVE.length) throw new IllegalArgumentException("Unknown memory owner");
        int key = (int) address;
        int previous = OWNERS.get(key);
        if (previous == IntMap.MISSING || previous == owner || previous == 2) return;
        int size = SIZES.get(key);
        OWNER_LIVE[previous] -= size;
        OWNERS.put(key, owner);
        OWNER_LIVE[owner] += size;
        OWNER_PEAK[owner] = Math.max(OWNER_PEAK[owner], OWNER_LIVE[owner]);
        publishTelemetry();
    }

    private static long allocateOwned(long byteCount, int owner) {
        if (byteCount < 0 || byteCount > Integer.MAX_VALUE - 16) {
            throw allocationFailure("Unsupported browser allocation size: " + byteCount);
        }
        ensureLiveByteCapacity(byteCount);
        int size = (int) byteCount;
        int shift = poolShift(size);
        int capacity = shift < 0 ? size : 1 << shift;
        int baseAddress;
        if (shift >= 0 && FREE_COUNTS[shift] > 0) {
            baseAddress = FREE_LISTS[shift][--FREE_COUNTS[shift]];
            pooledBytes -= capacity;
            reuseHits++;
        } else {
            if (shift < 0 || reservedBytes + capacity > MAX_LIVE_BYTES) {
                trimPool();
            }
            baseAddress = Heap.alloc(Math.max(8, capacity + 4)).toInt();
            if (baseAddress != 0) {
                reservedBytes += capacity;
                peakReservedBytes = Math.max(peakReservedBytes, reservedBytes);
            }
        }
        if (baseAddress == 0) {
            throw allocationFailure("TeaVM heap exhausted: requested=" + byteCount
                    + " live=" + liveBytes);
        }
        int address = baseAddress;
        if ((address & 7) != 0) {
            address = (address + 7) & ~7;
            BASES.put(address, baseAddress);
        }
        SIZES.put(address, size);
        CAPACITIES.put(address, capacity);
        RESERVATIONS.put(address, capacity);
        OWNERS.put(address, owner);
        sizeLive(size, size, 1L);
        OWNER_LIVE[owner] += size;
        OWNER_PEAK[owner] = Math.max(OWNER_PEAK[owner], OWNER_LIVE[owner]);
        allocationCount++;
        liveBytes += size;
        if (liveBytes > peakLiveBytes) {
            peakLiveBytes = liveBytes;
        }
        if (SIZES.size() > peakLiveRegions) {
            peakLiveRegions = SIZES.size();
        }
        if (liveBytes >= nextPressureBytes) {
            reportPressure((double) liveBytes / MAX_LIVE_BYTES, false);
        }
        publishTelemetry();
        return Integer.toUnsignedLong(address);
    }

    /** Alignments up to 8 are free; larger ones over-allocate. */
    public static long alignedAllocate(long alignment, long byteCount) {
        if (alignment <= 8) {
            return allocateOwned(byteCount, 2);
        }
        if (alignment > 65536 || Long.bitCount(alignment) != 1) {
            throw new IllegalArgumentException("Unsupported alignment: " + alignment);
        }
        long raw = allocateOwned(byteCount + alignment, 2);
        int size = SIZES.remove((int) raw);
        int capacity = CAPACITIES.remove((int) raw);
        int reservation = RESERVATIONS.remove((int) raw);
        int owner = OWNERS.remove((int) raw);
        int base = BASES.containsKey((int) raw) ? BASES.remove((int) raw) : (int) raw;
        int aligned = (int) ((raw + alignment - 1) & -alignment);
        SIZES.put(aligned, (int) byteCount);
        CAPACITIES.put(aligned, capacity - (aligned - (int) raw));
        RESERVATIONS.put(aligned, reservation);
        OWNERS.put(aligned, owner);
        liveBytes -= size - byteCount;
        OWNER_LIVE[owner] -= size - byteCount;
        sizeLive(size, -size, -1L);
        sizeLive((int) byteCount, byteCount, 1L);
        if (aligned != base) {
            BASES.put(aligned, base);
        }
        return Integer.toUnsignedLong(aligned);
    }

    /**
     * Returns the address of a direct buffer. A heap buffer gets a detached copy (see the class
     * comment); writes through that address do not reach the buffer.
     */
    public static long register(ByteBuffer bytes) {
        if (bytes == null) {
            return 0L;
        }
        registrationCount++;
        return address0(bytes);
    }

    public static long calloc(long count, long size) {
        long byteCount = Math.multiplyExact(count, size);
        long address = allocate(byteCount);
        Address.fillZero(Address.fromInt((int) address), (int) byteCount);
        return address;
    }

    /** Returns the managed stand-in for LWJGL's browser-side JNIEnv pointer. */
    public static long threadJniEnv() {
        if (threadJniEnv == 0L) {
            long functionTable = calloc(32L * 1024L, 1L);
            long environment = allocate(Long.BYTES);
            putLong(environment, functionTable);
            threadJniFunctionTable = functionTable;
            threadJniEnv = environment;
        }
        return threadJniEnv;
    }

    /** Allocates and installs a per-thread JNI table stand-in for LWJGL. */
    public static long setupThreadEnv(int functionCount) {
        long entries = Math.max(4L, (long) functionCount + 4L);
        long byteCount = Math.multiplyExact(entries, Long.BYTES);
        if (threadJniEnv == 0L) {
            long functionTable = calloc(byteCount, 1L);
            long environment = allocate(Long.BYTES);
            putLong(environment, functionTable);
            threadJniFunctionTable = functionTable;
            threadJniEnv = environment;
            return functionTable;
        }
        long functionTable = calloc(byteCount, 1L);
        long previousTable = threadJniFunctionTable;
        putLong(threadJniEnv, functionTable);
        threadJniFunctionTable = functionTable;
        if (previousTable != 0L && previousTable != functionTable) {
            free(previousTable);
        }
        return functionTable;
    }

    public static long reallocate(long address, long byteCount) {
        if (address == 0L) {
            return allocate(byteCount);
        }
        int oldSize = SIZES.get((int) address);
        if (oldSize == IntMap.MISSING) {
            throw new IllegalArgumentException("Not an allocation base: 0x" + Long.toHexString(address));
        }
        reallocationCount++;
        if (byteCount < 0 || byteCount > Integer.MAX_VALUE - 16) {
            throw allocationFailure("Unsupported browser allocation size: " + byteCount);
        }
        int capacity = CAPACITIES.get((int) address);
        if (POOL_MAX_BYTES > 0 && byteCount <= capacity
                && (capacity <= 1 << POOL_MAX_SHIFT || byteCount >= capacity / 2)) {
            ensureLiveByteCapacity(Math.max(0, byteCount - oldSize));
            SIZES.put((int) address, (int) byteCount);
            liveBytes += byteCount - oldSize;
            peakLiveBytes = Math.max(peakLiveBytes, liveBytes);
            int owner = OWNERS.get((int) address);
            OWNER_LIVE[owner] += byteCount - oldSize;
            sizeLive(oldSize, -oldSize, -1L);
            sizeLive((int) byteCount, byteCount, 1L);
            OWNER_PEAK[owner] = Math.max(OWNER_PEAK[owner], OWNER_LIVE[owner]);
            inPlaceReallocations++;
            publishTelemetry();
            return address;
        }
        int owner = OWNERS.get((int) address);
        if (POOL_MAX_BYTES > 0 && owner != 2 && byteCount > 1 << POOL_MAX_SHIFT) {
            ensureLiveByteCapacity(Math.max(0, byteCount - oldSize));
            trimPool();
            int oldBase = BASES.get((int) address);
            if (oldBase == IntMap.MISSING) oldBase = (int) address;
            int reservation = RESERVATIONS.get((int) address);
            int newBase = Heap.realloc(Address.fromInt(oldBase), (int) byteCount + 4).toInt();
            if (newBase == 0) throw allocationFailure("TeaVM heap exhausted during realloc: " + byteCount);
            int replacement = (newBase + 7) & ~7;
            int copiedData = newBase + (int) address - oldBase;
            if (replacement != copiedData) {
                Address.moveMemoryBlock(Address.fromInt(copiedData), Address.fromInt(replacement),
                        (int) Math.min(oldSize, byteCount));
            }
            SIZES.remove((int) address);
            CAPACITIES.remove((int) address);
            RESERVATIONS.remove((int) address);
            OWNERS.remove((int) address);
            BASES.remove((int) address);
            SIZES.put(replacement, (int) byteCount);
            CAPACITIES.put(replacement, (int) byteCount);
            RESERVATIONS.put(replacement, (int) byteCount);
            OWNERS.put(replacement, owner);
            if (replacement != newBase) BASES.put(replacement, newBase);
            if (newBase == oldBase) inPlaceReallocations++;
            peakReservedBytes = Math.max(peakReservedBytes,
                    reservedBytes + (newBase == oldBase ? byteCount - reservation : byteCount));
            reservedBytes += byteCount - reservation;
            liveBytes += byteCount - oldSize;
            peakLiveBytes = Math.max(peakLiveBytes, liveBytes);
            OWNER_LIVE[owner] += byteCount - oldSize;
            sizeLive(oldSize, -oldSize, -1L);
            sizeLive((int) byteCount, byteCount, 1L);
            OWNER_PEAK[owner] = Math.max(OWNER_PEAK[owner], OWNER_LIVE[owner]);
            publishTelemetry();
            return replacement;
        }
        long replacement = allocateOwned(byteCount, OWNERS.get((int) address));
        Address.moveMemoryBlock(Address.fromInt((int) address), Address.fromInt((int) replacement),
                (int) Math.min(oldSize, byteCount));
        free(address);
        return replacement;
    }

    public static void free(long address) {
        if (address == 0L) {
            return;
        }
        int key = (int) address;
        int size = SIZES.remove(key);
        if (size == IntMap.MISSING) {
            throw new IllegalArgumentException("Not an allocation base: 0x" + Long.toHexString(address));
        }
        int base = BASES.remove(key);
        int capacity = CAPACITIES.remove(key);
        int reservation = RESERVATIONS.remove(key);
        int owner = OWNERS.remove(key);
        OWNER_LIVE[owner] -= size;
        sizeLive(size, -size, -1L);
        int shift = poolShift(capacity);
        int baseAddress = base != IntMap.MISSING ? base : key;
        if (shift >= 0 && capacity == reservation && capacity == 1 << shift && FREE_COUNTS[shift] < POOL_SLOTS
                && pooledBytes + capacity <= POOL_MAX_BYTES) {
            FREE_LISTS[shift][FREE_COUNTS[shift]++] = baseAddress;
            pooledBytes += capacity;
        } else {
            Heap.release(Address.fromInt(baseAddress));
            reservedBytes -= reservation;
        }
        freeCount++;
        liveBytes -= size;
        publishTelemetry();
    }

    /** Frees the allocation a direct buffer starts at; other buffers own no allocation here. */
    public static void free(Buffer buffer) {
        if (buffer != null) {
            int address = LinearMemory.address(buffer);
            if (address != 0 && SIZES.containsKey(address)) {
                free(Integer.toUnsignedLong(address));
            }
        }
    }

    public static long address0(Buffer buffer) {
        int address = LinearMemory.address(buffer);
        if (address != 0) {
            return Integer.toUnsignedLong(address);
        }
        return heapCopyAddress(buffer);
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
        int kind = bufferKind(type);
        ByteBuffer bytes = LinearMemory.wrap((int) address, capacity << kindShift(kind))
                .order(ByteOrder.nativeOrder());
        return switch (kind) {
            case 1 -> bytes.asShortBuffer();
            case 2 -> bytes.asCharBuffer();
            case 3 -> bytes.asIntBuffer();
            case 4 -> bytes.asLongBuffer();
            case 5 -> bytes.asFloatBuffer();
            case 6 -> bytes.asDoubleBuffer();
            default -> bytes;
        };
    }

    private static int bufferKind(Class<?> type) {
        for (int kind = 0; kind < BUFFER_CLASSES.length; kind++) {
            if (bufferClass(kind) == type) {
                return kind;
            }
        }
        String name = type.getName();
        if (name.contains("Short")) {
            return 1;
        }
        if (name.contains("Char")) {
            return 2;
        }
        if (name.contains("Int")) {
            return 3;
        }
        if (name.contains("Long")) {
            return 4;
        }
        if (name.contains("Float")) {
            return 5;
        }
        if (name.contains("Double")) {
            return 6;
        }
        return 0;
    }

    private static int kindShift(int kind) {
        return switch (kind) {
            case 1, 2 -> 1;
            case 3, 5 -> 2;
            case 4, 6 -> 3;
            default -> 0;
        };
    }

    public static boolean getBoolean(long address) {
        return getByte(address) != 0;
    }

    public static ByteBuffer byteBuffer(long address, int capacity) {
        return LinearMemory.wrap((int) address, capacity).order(ByteOrder.nativeOrder());
    }

    /**
     * Linear memory is not array-backed: always null (the JavaScript build throws
     * IllegalStateException for such regions; both callers, BrowserMemoryBackend and
     * MeshKernelHooks, fall back to address access on null, without an exception per call).
     */
    public static byte[] data(long address) {
        return null;
    }

    /** 0: there is no backing array (see {@link #data}). */
    public static int dataOffset(long address) {
        return 0;
    }

    public static ByteBuffer reallocate(ByteBuffer old, int size) {
        int position = old == null ? 0 : Math.min(old.position(), size);
        long address = reallocate(old == null ? 0L : address0(old), size);
        ByteBuffer result = byteBuffer(address, size);
        result.position(position);
        return result;
    }

    public static ShortBuffer reallocate(ShortBuffer old, int size) {
        return (ShortBuffer) reallocateView(old, size, 1);
    }

    public static IntBuffer reallocate(IntBuffer old, int size) {
        return (IntBuffer) reallocateView(old, size, 3);
    }

    public static LongBuffer reallocate(LongBuffer old, int size) {
        return (LongBuffer) reallocateView(old, size, 4);
    }

    public static FloatBuffer reallocate(FloatBuffer old, int size) {
        return (FloatBuffer) reallocateView(old, size, 5);
    }

    public static DoubleBuffer reallocate(DoubleBuffer old, int size) {
        return (DoubleBuffer) reallocateView(old, size, 6);
    }

    private static Buffer reallocateView(Buffer old, int size, int kind) {
        int position = old == null ? 0 : Math.min(old.position(), size);
        long address = reallocate(old == null ? 0L : address0(old), (long) size << kindShift(kind));
        Buffer result = wrap(bufferClass(kind), address, size);
        result.position(position);
        return result;
    }

    private static Address at(long address) {
        return Address.fromInt((int) address);
    }

    public static byte getByte(long address) {
        return at(address).getByte();
    }

    public static short getShort(long address) {
        return at(address).getShort();
    }

    public static int getInt(long address) {
        return at(address).getInt();
    }

    public static long getLong(long address) {
        return at(address).getLong();
    }

    public static float getFloat(long address) {
        return at(address).getFloat();
    }

    public static double getDouble(long address) {
        return at(address).getDouble();
    }

    public static void putByte(long address, byte value) {
        at(address).putByte(value);
    }

    public static void putShort(long address, short value) {
        at(address).putShort(value);
    }

    public static void putInt(long address, int value) {
        at(address).putInt(value);
    }

    public static void putLong(long address, long value) {
        at(address).putLong(value);
    }

    public static void putFloat(long address, float value) {
        at(address).putFloat(value);
    }

    public static void putDouble(long address, double value) {
        at(address).putDouble(value);
    }

    public static void putPosition(long pointer, float x, float y, float z) {
        Address target = at(pointer);
        target.putFloat(x);
        target.add(4).putFloat(y);
        target.add(8).putFloat(z);
    }

    public static void putTransformedPosition(long pointer, Matrix4fc pose, float x, float y, float z) {
        float tx = pose.m00() * x + pose.m10() * y + pose.m20() * z + pose.m30();
        float ty = pose.m01() * x + pose.m11() * y + pose.m21() * z + pose.m31();
        float tz = pose.m02() * x + pose.m12() * y + pose.m22() * z + pose.m32();
        putPosition(pointer, tx, ty, tz);
    }

    public static void putRgba(long pointer, int argb) {
        at(pointer).putInt(abgr(argb));
    }

    public static void putFloatPair(long pointer, float x, float y) {
        Address target = at(pointer);
        target.putFloat(x);
        target.add(4).putFloat(y);
    }

    public static void putShortPair(long pointer, short x, short y) {
        Address target = at(pointer);
        target.putShort(x);
        target.add(2).putShort(y);
    }

    public static void putPackedUv(long pointer, int packedUv) {
        at(pointer).putInt(packedUv);
    }

    public static void putNormal(long pointer, float x, float y, float z) {
        Address target = at(pointer);
        target.putByte(normalIntValue(x));
        target.add(1).putByte(normalIntValue(y));
        target.add(2).putByte(normalIntValue(z));
    }

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
        Address target = at(pointer);
        target.putFloat(x);
        target.add(4).putFloat(y);
        target.add(8).putFloat(z);
        target.add(12).putInt(abgr(color));
        target.add(16).putFloat(u);
        target.add(20).putFloat(v);
        int lightStart;
        if (fullFormat) {
            target.add(24).putInt(overlayCoords);
            lightStart = 28;
        } else {
            lightStart = 24;
        }
        target.add(lightStart).putInt(lightCoords);
        if (fullFormat) {
            target.add(lightStart + 4).putByte(normalIntValue(nx));
            target.add(lightStart + 5).putByte(normalIntValue(ny));
            target.add(lightStart + 6).putByte(normalIntValue(nz));
        }
    }

    public static void set(long address, int value, long byteCount) {
        if (byteCount < 0 || byteCount > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Invalid memory fill size");
        }
        if (byteCount > 0) {
            Address.fill(at(address), (byte) value, (int) byteCount);
        }
    }

    public static void copy(long source, long target, long byteCount) {
        if (byteCount < 0 || byteCount > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Invalid memory copy size");
        }
        if (byteCount == 0 || source == target) {
            return;
        }
        Address.moveMemoryBlock(at(source), at(target), (int) byteCount);
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

    /** {@link #copy} preserves memmove overlap semantics. */
    public static long cMemmove(long target, long source, long byteCount) {
        copy(source, target, byteCount);
        return target;
    }

    public static int lengthNt1(long address, int maximum) {
        Address cursor = at(address);
        int length = 0;
        while (length < maximum && cursor.add(length).getByte() != 0) {
            length++;
        }
        return length;
    }

    public static int lengthNt2(long address, int maximum) {
        Address cursor = at(address);
        int length = 0;
        while (length + Short.BYTES <= maximum && cursor.add(length).getShort() != 0) {
            length += Short.BYTES;
        }
        return length;
    }

    public static String decodeAscii(long address, int length) {
        byte[] bytes = temporaryBytes(length);
        readBytes(address, bytes, length);
        return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
    }

    public static long getCLong(long address) {
        return getLong(address);
    }

    public static void putCLong(long address, long value) {
        putLong(address, value);
    }

    public static ByteBuffer slice(ByteBuffer buffer) {
        return buffer.slice().order(buffer.order());
    }

    public static ShortBuffer slice(ShortBuffer buffer) {
        return buffer.slice();
    }

    public static CharBuffer slice(CharBuffer buffer) {
        return buffer.slice();
    }

    public static IntBuffer slice(IntBuffer buffer) {
        return buffer.slice();
    }

    public static LongBuffer slice(LongBuffer buffer) {
        return buffer.slice();
    }

    public static FloatBuffer slice(FloatBuffer buffer) {
        return buffer.slice();
    }

    public static DoubleBuffer slice(DoubleBuffer buffer) {
        return buffer.slice();
    }

    public static ByteBuffer slice(ByteBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        ByteBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        return view.slice().order(buffer.order());
    }

    public static ShortBuffer slice(ShortBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        ShortBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        return view.slice();
    }

    public static CharBuffer slice(CharBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        CharBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        return view.slice();
    }

    public static IntBuffer slice(IntBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        IntBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        return view.slice();
    }

    public static LongBuffer slice(LongBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        LongBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        return view.slice();
    }

    public static FloatBuffer slice(FloatBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        FloatBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        return view.slice();
    }

    public static DoubleBuffer slice(DoubleBuffer buffer, int offset, int capacity) {
        int start = sliceStart(buffer, offset, capacity);
        DoubleBuffer view = buffer.duplicate();
        view.position(start).limit(start + capacity);
        return view.slice();
    }

    public static ByteBuffer duplicate(ByteBuffer buffer) {
        return buffer.duplicate().order(buffer.order());
    }

    public static ShortBuffer duplicate(ShortBuffer buffer) {
        return buffer.duplicate();
    }

    public static CharBuffer duplicate(CharBuffer buffer) {
        return buffer.duplicate();
    }

    public static IntBuffer duplicate(IntBuffer buffer) {
        return buffer.duplicate();
    }

    public static LongBuffer duplicate(LongBuffer buffer) {
        return buffer.duplicate();
    }

    public static FloatBuffer duplicate(FloatBuffer buffer) {
        return buffer.duplicate();
    }

    public static DoubleBuffer duplicate(DoubleBuffer buffer) {
        return buffer.duplicate();
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
        readBytes(address, bytes, length);
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    // The *Bytes writers of the JavaScript build (array-backed vertex fast paths). The wasmgc
    // patch chain does not reach them (MinecraftClientPatcher skips its BufferBuilder fast
    // paths for this target); they stay for API parity, in plain Java.

    public static void putPositionBytes(byte[] bytes, int base, float x, float y, float z) {
        putIntBytes(bytes, base, Float.floatToRawIntBits(x));
        putIntBytes(bytes, base + 4, Float.floatToRawIntBits(y));
        putIntBytes(bytes, base + 8, Float.floatToRawIntBits(z));
    }

    public static void putTransformedPositionBytes(
            byte[] bytes, int base, Matrix4fc pose, float x, float y, float z) {
        float tx = pose.m00() * x + pose.m10() * y + pose.m20() * z + pose.m30();
        float ty = pose.m01() * x + pose.m11() * y + pose.m21() * z + pose.m31();
        float tz = pose.m02() * x + pose.m12() * y + pose.m22() * z + pose.m32();
        putPositionBytes(bytes, base, tx, ty, tz);
    }

    public static void putRgbaBytes(byte[] bytes, int base, int argb) {
        putIntBytes(bytes, base, abgr(argb));
    }

    public static void putFloatPairBytes(byte[] bytes, int base, float x, float y) {
        putIntBytes(bytes, base, Float.floatToRawIntBits(x));
        putIntBytes(bytes, base + 4, Float.floatToRawIntBits(y));
    }

    public static void putShortPairBytes(byte[] bytes, int base, short x, short y) {
        bytes[base] = (byte) x;
        bytes[base + 1] = (byte) (x >> 8);
        bytes[base + 2] = (byte) y;
        bytes[base + 3] = (byte) (y >> 8);
    }

    public static void putPackedUvBytes(byte[] bytes, int base, int packedUv) {
        putIntBytes(bytes, base, packedUv);
    }

    public static void putNormalBytes(byte[] bytes, int base, float x, float y, float z) {
        bytes[base] = normalIntValue(x);
        bytes[base + 1] = normalIntValue(y);
        bytes[base + 2] = normalIntValue(z);
    }

    public static void putFastVertexBytes(
            byte[] bytes,
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
            boolean fullFormat) {
        putPositionBytes(bytes, base, x, y, z);
        putIntBytes(bytes, base + 12, abgr(color));
        putFloatPairBytes(bytes, base + 16, u, v);
        int lightStart;
        if (fullFormat) {
            putIntBytes(bytes, base + 24, overlayCoords);
            lightStart = base + 28;
        } else {
            lightStart = base + 24;
        }
        putIntBytes(bytes, lightStart, lightCoords);
        if (fullFormat) {
            putNormalBytes(bytes, lightStart + 4, nx, ny, nz);
        }
    }

    private static void putIntBytes(byte[] bytes, int base, int value) {
        bytes[base] = (byte) value;
        bytes[base + 1] = (byte) (value >> 8);
        bytes[base + 2] = (byte) (value >> 16);
        bytes[base + 3] = (byte) (value >> 24);
    }

    private static void readBytes(long address, byte[] target, int length) {
        Address source = at(address);
        for (int index = 0; index < length; index++) {
            target[index] = source.add(index).getByte();
        }
    }

    private static int sliceStart(Buffer buffer, int offset, int capacity) {
        int start = buffer.position() + offset;
        if (offset < 0 || capacity < 0 || start > buffer.limit() - capacity) {
            throw new IndexOutOfBoundsException("Invalid slice: offset=" + offset
                    + " capacity=" + capacity + " remaining=" + buffer.remaining());
        }
        return start;
    }

    /**
     * Address of a detached linear-memory copy of a heap buffer. The copy of the same buffer
     * object is reused while it stays in the small recent-copy table; an evicted copy is freed.
     */
    private static long heapCopyAddress(Buffer buffer) {
        if (!(buffer instanceof ByteBuffer bytes)) {
            throw new IllegalArgumentException("Heap buffer view has no linear-memory address: "
                    + buffer.getClass().getName());
        }
        for (int slot = 0; slot < HEAP_COPY_SLOTS; slot++) {
            WeakReference<Buffer> reference = HEAP_COPY_SOURCES[slot];
            if (reference != null && reference.get() == buffer) {
                return HEAP_COPY_ADDRESSES[slot];
            }
        }
        addressLookupMisses++;
        int capacity = bytes.capacity();
        long address = allocateOwned(capacity, 1);
        Address target = at(address);
        for (int index = 0; index < capacity; index++) {
            target.add(index).putByte(bytes.get(index));
        }
        int slot = nextHeapCopySlot;
        nextHeapCopySlot = (slot + 1) % HEAP_COPY_SLOTS;
        if (HEAP_COPY_SOURCES[slot] != null) {
            free(HEAP_COPY_ADDRESSES[slot]);
        }
        HEAP_COPY_SOURCES[slot] = new WeakReference<>(buffer);
        HEAP_COPY_ADDRESSES[slot] = address;
        return address;
    }

    /** Publishes the counters when the last publish is at least the interval old. */
    private static void publishTelemetry() {
        if (!telemetryBridgeAvailable) {
            return;
        }
        if (++telemetryOperations < TELEMETRY_CHECK_OPERATIONS) {
            return;
        }
        telemetryOperations = 0;
        long now = System.currentTimeMillis();
        if (now - lastTelemetryMillis < TELEMETRY_INTERVAL_MILLIS) {
            return;
        }
        lastTelemetryMillis = now;
        publishTelemetryNow();
    }

    private static void publishTelemetryNow() {
        if (!telemetryBridgeAvailable) {
            return;
        }
        double[] t = TELEMETRY;
        t[0] = SIZES.size();
        t[1] = liveBytes;
        t[2] = 0;
        t[3] = peakLiveRegions;
        t[4] = peakLiveBytes;
        t[5] = 0;
        t[6] = allocationCount;
        t[7] = freeCount;
        t[8] = registrationCount;
        t[9] = reallocationCount;
        t[10] = 0;
        t[11] = addressLookupMisses;
        t[12] = MAX_LIVE_BYTES;
        t[13] = allocationFailureCount;
        t[14] = MAX_TEMPORARY_BYTES;
        t[15] = peakTemporaryBytes;
        t[16] = temporaryAllocationFailureCount;
        t[17] = reservedBytes;
        t[18] = peakReservedBytes;
        t[19] = pooledBytes;
        t[20] = reuseHits;
        t[21] = inPlaceReallocations;
        t[22] = POOL_MAX_BYTES;
        for (int owner = 0; owner < OWNER_LIVE.length; owner++) {
            t[23 + owner * 2] = OWNER_LIVE[owner];
            t[24 + owner * 2] = OWNER_PEAK[owner];
        }
        for (int bucket = 0; bucket < SIZE_BUCKETS; bucket++) {
            t[33 + bucket * 2] = SIZE_LIVE[bucket];
            t[34 + bucket * 2] = SIZE_COUNTS[bucket];
        }
        try {
            publishTelemetryBrowser(JsArrays.float64(t));
        } catch (Throwable ignored) {
            telemetryBridgeAvailable = false;
        }
    }

    /**
     * Writes {@code __gaiusMemoryTelemetry.browserMemory} (the shape of the JavaScript build's)
     * from one packed snapshot: one typed array instead of separate scalar arguments that
     * would cross as BigInts.
     */
    @JSBody(params = "v", script = """
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
            memory.backend = 'linear-memory';
            memory.liveRegions = v[0] | 0;
            memory.liveBytes = v[1];
            memory.associatedBuffers = v[2] | 0;
            memory.peakLiveRegions = v[3] | 0;
            memory.peakLiveBytes = v[4];
            memory.peakAssociatedBuffers = v[5] | 0;
            memory.allocations = v[6];
            memory.frees = v[7];
            memory.registrations = v[8];
            memory.reallocations = v[9];
            memory.collectedAssociations = v[10];
            memory.addressLookupMisses = v[11] | 0;
            memory.maxLiveBytes = v[12];
            memory.allocationFailures = v[13];
            memory.maxTemporaryBytes = v[14] | 0;
            memory.peakTemporaryBytes = v[15] | 0;
            memory.temporaryAllocationFailures = v[16];
            memory.reservedBytes = v[17];
            memory.peakReservedBytes = v[18];
            memory.pooledBytes = v[19];
            memory.reuseHits = v[20];
            memory.inPlaceReallocations = v[21];
            memory.poolLimitBytes = v[22];
            const owners = memory.owners || (memory.owners = {});
            for (let index = 0; index < 5; index++) {
              const name = ['native', 'heap-buffer-copy', 'aligned', 'decoded-image', 'mapped-staging'][index];
              const entry = owners[name] || (owners[name] = {});
              entry.liveBytes = v[23 + index * 2];
              entry.peakBytes = v[24 + index * 2];
            }
            const sizes = memory.sizeBuckets || (memory.sizeBuckets = []);
            for (let index = 0; index < 8; index++) {
              const entry = sizes[index] || (sizes[index] = {});
              entry.liveBytes = v[33 + index * 2];
              entry.count = v[34 + index * 2];
            }
            """)
    private static native void publishTelemetryBrowser(Float64Array values);

    private static int poolShift(int size) {
        if (POOL_MAX_BYTES == 0 || size > 1 << POOL_MAX_SHIFT) {
            return -1;
        }
        return Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, size) - 1));
    }

    public static void trimPool() {
        for (int shift = 4; shift <= POOL_MAX_SHIFT; shift++) {
            while (FREE_COUNTS[shift] > 0) {
                Heap.release(Address.fromInt(FREE_LISTS[shift][--FREE_COUNTS[shift]]));
                reservedBytes -= 1L << shift;
            }
        }
        pooledBytes = 0;
    }

    @JSBody(script = """
            try {
              const params = new URLSearchParams(globalThis.location?.search || '');
              if (params.get('gaiusMemoryReuse') === '0') return 0;
              const low = typeof globalThis.__gaiusIsLowMemoryTier === 'function'
                    ? globalThis.__gaiusIsLowMemoryTier()
                    : params.get('gaiusMemoryTier') === 'low'
                      || !!(globalThis.__gaiusMemoryTier && globalThis.__gaiusMemoryTier.lowMemory);
              if (low) return 512 * 1024;
              return 2 * 1024 * 1024;
            } catch (ignored) { return 2 * 1024 * 1024; }
            """)
    private static native int runtimePoolBytes();

    private static long configuredPoolBytes() {
        try {
            String value = System.getProperty("gaius.browser.memory.poolBytes");
            return value == null ? runtimePoolBytes() : Math.max(0, Math.min(2 * 1024 * 1024, Long.parseLong(value)));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long configuredMaxLiveBytes() {
        String configured = System.getProperty("gaius.browser.memory.maxBytes");
        if (configured == null || configured.isBlank()) {
            return HARD_MAX_LIVE_BYTES;
        }
        try {
            return Math.min(HARD_MAX_LIVE_BYTES, Math.max(1L, Long.parseLong(configured)));
        } catch (NumberFormatException ignored) {
            return HARD_MAX_LIVE_BYTES;
        }
    }

    private static int configuredMaxTemporaryBytes() {
        String configured = System.getProperty("gaius.browser.memory.maxTemporaryBytes");
        if (configured == null || configured.isBlank()) {
            return HARD_MAX_TEMPORARY_BYTES;
        }
        try {
            return (int) Math.min(HARD_MAX_TEMPORARY_BYTES, Math.max(1L, Long.parseLong(configured)));
        } catch (NumberFormatException ignored) {
            return HARD_MAX_TEMPORARY_BYTES;
        }
    }

    private static void ensureLiveByteCapacity(long additionalBytes) {
        if (additionalBytes < 0L || liveBytes > MAX_LIVE_BYTES - additionalBytes) {
            throw allocationFailure("Browser native memory budget exceeded: live=" + liveBytes
                    + " requested=" + additionalBytes + " limit=" + MAX_LIVE_BYTES);
        }
    }

    private static OutOfMemoryError allocationFailure(String message) {
        allocationFailureCount++;
        publishTelemetryNow();
        reportPressure(1.0, true);
        return new OutOfMemoryError(message);
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

    private static byte[] temporaryBytes(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Invalid temporary byte length: " + length);
        }
        if (length > MAX_TEMPORARY_BYTES) {
            temporaryAllocationFailureCount++;
            throw allocationFailure("Browser temporary decode budget exceeded: requested=" + length
                    + " limit=" + MAX_TEMPORARY_BYTES);
        }
        if (length > peakTemporaryBytes) {
            peakTemporaryBytes = length;
        }
        byte[] bytes = BYTE_ARRAYS.get();
        return length <= bytes.length ? bytes : new byte[length];
    }

    private static int abgr(int argb) {
        int abgr = (argb & 0xff00_ff00)
                | ((argb >>> 16) & 0x0000_00ff)
                | ((argb & 0x0000_00ff) << 16);
        return IS_LITTLE_ENDIAN ? abgr : Integer.reverseBytes(abgr);
    }

    private static byte normalIntValue(float value) {
        float clamped = value < -1.0f ? -1.0f : Math.min(value, 1.0f);
        return (byte) (((int) (clamped * 127.0f)) & 0xff);
    }

    /**
     * An int to int map for the allocation tables, keyed by nonzero linear-memory addresses:
     * open addressing with linear probing and backward-shift deletion, so allocate and free
     * neither box nor allocate (a HashMap of Integer cost 50 ns per call with few live
     * allocations and 400 ns with 40000, port/tests/micro). Values are never negative.
     */
    private static final class IntMap {
        static final int MISSING = -1;
        private int[] keys = new int[64];
        private int[] values = new int[64];
        private int mask = 63;
        private int size;

        int size() {
            return size;
        }

        private int slot(int key) {
            int hash = (key >>> 3) * 0x9E3779B1;
            return (hash ^ (hash >>> 16)) & mask;
        }

        int get(int key) {
            for (int index = slot(key);; index = (index + 1) & mask) {
                int current = keys[index];
                if (current == key) {
                    return values[index];
                }
                if (current == 0) {
                    return MISSING;
                }
            }
        }

        boolean containsKey(int key) {
            return key != 0 && get(key) != MISSING;
        }

        void put(int key, int value) {
            if (key == 0) {
                throw new IllegalArgumentException("Address 0 is not an allocation");
            }
            if ((size + 1) * 4 > (mask + 1) * 3) {
                grow();
            }
            int index = slot(key);
            while (keys[index] != 0 && keys[index] != key) {
                index = (index + 1) & mask;
            }
            if (keys[index] == 0) {
                keys[index] = key;
                size++;
            }
            values[index] = value;
        }

        int remove(int key) {
            int index = slot(key);
            while (keys[index] != key) {
                if (keys[index] == 0) {
                    return MISSING;
                }
                index = (index + 1) & mask;
            }
            int value = values[index];
            size--;
            // Backward shift: move later entries of the probe run into the hole.
            int hole = index;
            for (int next = (hole + 1) & mask; keys[next] != 0; next = (next + 1) & mask) {
                int home = slot(keys[next]);
                if (((next - home) & mask) >= ((next - hole) & mask)) {
                    keys[hole] = keys[next];
                    values[hole] = values[next];
                    hole = next;
                }
            }
            keys[hole] = 0;
            return value;
        }

        private void grow() {
            int[] oldKeys = keys;
            int[] oldValues = values;
            keys = new int[oldKeys.length * 2];
            values = new int[oldKeys.length * 2];
            mask = keys.length - 1;
            size = 0;
            for (int index = 0; index < oldKeys.length; index++) {
                if (oldKeys[index] != 0) {
                    put(oldKeys[index], oldValues[index]);
                }
            }
        }
    }
}
