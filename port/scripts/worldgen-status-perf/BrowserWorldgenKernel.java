package dev.gaius.browser.kernel.worldgen;

import dev.gaius.wgperf.KernelHost;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BrowserWorldgenKernel {
    public static final int FLAG_SURFACE = 1;
    public static final int FLAG_BIOMES = 2;
    public static final int FLAG_KEEP_NOISE = 1 << 8;
    private static final AtomicInteger NEXT = new AtomicInteger(1);
    private static final Map<Integer, byte[]> GENERATORS = new ConcurrentHashMap<>();
    private static final Map<Integer, KernelChunk> KEPT = new ConcurrentHashMap<>();
    private static volatile String disabled = "";
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "worldgen-status-perf-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public static boolean available() {
        if (!disabled.isEmpty() || System.getProperty("gaius.perf.kernels", "off").equals("off")) return false;
        String hooks = System.getProperty("gaius.correctness.hooks", "all");
        if (hooks.equals("all")) return true;
        if (hooks.equals("off")) return false;
        for (StackTraceElement caller : Thread.currentThread().getStackTrace()) {
            if (!caller.getClassName().startsWith("dev.gaius.browser.kernel.worldgen.WorldgenKernel")) continue;
            switch (caller.getMethodName()) {
                case "createBiomes": return hooks.equals("biomes");
                case "fillFromNoise": return hooks.equals("noise") || hooks.equals("terrain");
                case "buildTerrain": return hooks.equals("terrain");
                case "generateSurface": return hooks.equals("terrain");
                default: break;
            }
        }
        return true;
    }
    public static void disable(String reason) { disabled = reason; }
    public static String disabledReason() { return disabled; }
    public static boolean isTransient(String message) { return message.startsWith("transient:"); }
    public static int allocateKey() { return NEXT.getAndIncrement(); }
    public static boolean generatorUsable(int key) { return available() && GENERATORS.containsKey(key); }
    public static boolean runOnJavaThread(Runnable task) { task.run(); return true; }
    public static boolean scheduleTimeout(Runnable task, int delayMillis) {
        TIMER.schedule(task, Math.max(1, delayMillis), TimeUnit.MILLISECONDS);
        return true;
    }
    public static void noteHostFallback(String stage, String reason) { }
    public static boolean surfaceSupported() {
        return !System.getProperty("gaius.correctness.hooks", "all").equals("noise") && available();
    }
    public static void dropKeptNoise(int token) { KEPT.remove(token); }
    public static int pendingJobs() { return 0; }
    public static boolean registerGenerator(int key, byte[] ir) {
        try {
            GENERATORS.put(key, ir.clone());
            KernelHost.WORKER.submit(() -> {
                ByteBuffer body = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0);
                checked(KernelHost.run(0x0401, frame(key, 0x0401, body.array(), true)), 0x0401);
            }).get();
            return true;
        } catch (Exception error) {
            GENERATORS.remove(key);
            throw new IllegalStateException("Generator registration failed", error);
        }
    }

    public interface ChunkCallback { void accept(KernelChunk result); }
    public interface IdsCallback { void accept(int[] result); }
    public interface ErrorCallback { void accept(String message); }
    public static final class KernelChunk {
        final Map<String, Object> fields = new HashMap<>();
        byte[] bytes;
    }
    public static int chunkInt(KernelChunk chunk, String field) { return (int) chunk.fields.get(field); }
    public static int[] chunkInts(KernelChunk chunk, String field) { return (int[]) chunk.fields.get(field); }
    public static short[] chunkIndices(KernelChunk chunk) { return (short[]) chunk.fields.get("indices"); }
    public static byte[] chunkBytes(KernelChunk chunk, String field) { return (byte[]) chunk.fields.get(field); }
    public static int[] ids(int[] result) { return result.clone(); }

    private static long hash(byte[] bytes) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) hash = (hash ^ (value & 255)) * 0x100000001b3L;
        return hash;
    }
    private static byte[] frame(int key, int kind, byte[] body, boolean inline) {
        byte[] ir = GENERATORS.get(key);
        if (ir == null) throw new IllegalStateException("Missing generator");
        int length = inline ? ir.length : 0;
        int reference = (24 + length + 7) & ~7;
        ByteBuffer buffer = ByteBuffer.allocate(16 + reference + body.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x424a4b47).putShort((short) 1).putShort((short) kind).putInt(1).putInt(reference + body.length);
        buffer.putInt(key).putInt(inline ? 1 : 0).putLong(hash(ir)).putInt(length).putInt(0);
        if (inline) buffer.put(ir);
        buffer.position(16 + reference);
        buffer.put(body);
        return buffer.array();
    }
    private static ByteBuffer checked(byte[] bytes, int kind) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 16 || buffer.getInt() != 0x53524b47 || buffer.getShort() != 1
                || buffer.getShort() != 0 || buffer.getInt() != 1
                || buffer.getInt() != bytes.length - 16) throw new IllegalStateException("Invalid result frame");
        return buffer;
    }
    private static int[] integers(ByteBuffer buffer, int count) {
        if (count < 0 || count > buffer.remaining() / 4) throw new IllegalStateException("Invalid array length");
        int[] values = new int[count];
        for (int index = 0; index < count; index++) values[index] = buffer.getInt();
        return values;
    }
    private static KernelChunk decode(byte[] bytes, int kind) {
        ByteBuffer buffer = checked(bytes, kind);
        KernelChunk result = new KernelChunk();
        result.bytes = java.util.Arrays.copyOfRange(bytes, 16, bytes.length);
        result.fields.put("chunkX", buffer.getInt());
        result.fields.put("chunkZ", buffer.getInt());
        result.fields.put("minY", buffer.getInt());
        int sections = buffer.getInt();
        if (sections < 1 || sections > 64) throw new IllegalStateException("Invalid section count");
        result.fields.put("sectionCount", sections);
        int flags = buffer.getInt();
        if ((flags & ~3) != 0 || buffer.getInt() != 0) throw new IllegalStateException("Invalid chunk flags");
        int[] offsets = new int[sections + 1];
        ArrayList<Integer> palettes = new ArrayList<>();
        short[] indices = new short[sections * 4096];
        byte[] uniform = new byte[sections];
        for (int section = 0; section < sections; section++) {
            int length = buffer.getShort() & 65535;
            int bits = buffer.get() & 255;
            buffer.get();
            if (length == 0 || length > 4096 || bits != 0 && bits != 8 && bits != 16) {
                throw new IllegalStateException("Invalid section palette");
            }
            offsets[section] = palettes.size();
            for (int value : integers(buffer, length)) palettes.add(value);
            uniform[section] = (byte) (bits == 0 ? 1 : 0);
            if (bits != 0) for (int index = 0; index < 4096; index++) {
                int value = bits == 8 ? buffer.get() & 255 : buffer.getShort() & 65535;
                if (value >= length) throw new IllegalStateException("Invalid palette index");
                indices[section * 4096 + index] = (short) value;
            }
            buffer.position((buffer.position() + 3) & ~3);
        }
        offsets[sections] = palettes.size();
        result.fields.put("paletteOffsets", offsets);
        result.fields.put("palettes", palettes.stream().mapToInt(Integer::intValue).toArray());
        result.fields.put("indices", indices);
        result.fields.put("uniform", uniform);
        result.fields.put("worldSurface", integers(buffer, 256));
        result.fields.put("oceanFloor", integers(buffer, 256));
        result.fields.put("postProcessing", integers(buffer, buffer.getInt()));
        if ((flags & 1) != 0) result.fields.put("biomes", integers(buffer, sections * 64));
        if (buffer.hasRemaining()) throw new IllegalStateException("Trailing chunk bytes");
        result.fields.put("token", 0);
        return result;
    }
    private static byte[] body(int chunkX, int chunkZ, int flags, int[] beard, int[] ring, byte[] chunk) {
        int[] neighbours = ring == null ? new int[0] : ring;
        int length = 12 + beard.length * 4 + (neighbours.length == 0 ? 0 : 4 + neighbours.length * 4);
        int aligned = (length + 7) & ~7;
        ByteBuffer buffer = ByteBuffer.allocate(chunk == null ? length : aligned + chunk.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(chunkX).putInt(chunkZ).putInt(flags | (neighbours.length == 0 ? 0 : 4));
        for (int value : beard) buffer.putInt(value);
        if (neighbours.length != 0) {
            buffer.putInt(neighbours.length);
            for (int value : neighbours) buffer.putInt(value);
        }
        if (chunk != null) { buffer.position(aligned); buffer.put(chunk); }
        return buffer.array();
    }
    private static void error(ErrorCallback callback, RuntimeException error) {
        callback.accept(error.getMessage());
    }
    public static void submitBiomes(int key, int chunkX, int chunkZ, IdsCallback result, ErrorCallback failure) {
        KernelHost.WORKER.execute(() -> {
            try {
                byte[] body = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkX).putInt(chunkZ).array();
                ByteBuffer buffer = checked(KernelHost.run(0x0402, frame(key, 0x0402, body, false)), 0x0402);
                int sections = buffer.getInt();
                buffer.getInt();
                int[] values = integers(buffer, sections * 64);
                if (buffer.hasRemaining()) throw new IllegalStateException("Trailing biome bytes");
                result.accept(values);
            } catch (RuntimeException error) { error(failure, error); }
        });
    }
    public static void submitTerrain(int key, int chunkX, int chunkZ, int flags, int[] beard, int[] ring,
            ChunkCallback result, ErrorCallback failure) {
        KernelHost.WORKER.execute(() -> {
            try {
                KernelChunk chunk = decode(KernelHost.run(0x0403, frame(key, 0x0403,
                        body(chunkX, chunkZ, flags & 3, beard, ring, null), false)), 0x0403);
                if ((flags & FLAG_KEEP_NOISE) != 0) {
                    int token = NEXT.getAndIncrement();
                    chunk.fields.put("token", token);
                    KEPT.put(token, chunk);
                }
                result.accept(chunk);
            } catch (RuntimeException error) { error(failure, error); }
        });
    }
    private static int state(KernelChunk chunk, int position) {
        int section = position >>> 12;
        return chunkInts(chunk, "palettes")[chunkInts(chunk, "paletteOffsets")[section]
                + (chunkIndices(chunk)[position] & 65535)];
    }
    public static void submitSurface(int key, int token, int chunkX, int chunkZ, int[] beard, int[] ring,
            ChunkCallback result, ErrorCallback failure) {
        KernelHost.WORKER.execute(() -> {
            try {
                KernelChunk previous = KEPT.remove(token);
                if (previous == null) throw new IllegalStateException("Missing kept noise");
                KernelChunk next = decode(KernelHost.run(0x0404, frame(key, 0x0404,
                        body(chunkX, chunkZ, 0, beard, ring, previous.bytes), false)), 0x0404);
                ArrayList<Integer> positions = new ArrayList<>();
                ArrayList<Integer> states = new ArrayList<>();
                for (int position = 0; position < chunkIndices(previous).length; position++) {
                    int replacement = state(next, position);
                    if (replacement != state(previous, position)) {
                        positions.add(position);
                        states.add(replacement);
                    }
                }
                next.fields.put("positions", positions.stream().mapToInt(Integer::intValue).toArray());
                next.fields.put("states", states.stream().mapToInt(Integer::intValue).toArray());
                next.fields.put("count", positions.size());
                result.accept(next);
            } catch (RuntimeException error) { error(failure, error); }
        });
    }
}
