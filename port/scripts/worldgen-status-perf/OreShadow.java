package dev.gaius.wgperf;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.server.level.WorldGenRegion;

public final class OreShadow {
    private static final ThreadLocal<Capture> CAPTURE = new ThreadLocal<>();
    private static final ClassValue<Method> ORIGINAL = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            return Arrays.stream(type.getDeclaredMethods()).filter(method -> method.getName().equals("gaius$oreVanilla"))
                    .findFirst().orElseThrow();
        }
    };
    private static long accepted;
    private static long declined;
    private static long geometryNanos;
    private static long snapshotNanos;
    private static long vanillaNanos;
    private static long installNanos;
    private static long deltaNanos;
    private static final java.util.Set<String> ACCEPTED_CHUNKS = new java.util.TreeSet<>();
    private static final Map<String, Long> REASONS = new java.util.TreeMap<>();
    private static final Map<Object, Settings> SETTINGS = new IdentityHashMap<>();
    private static final java.util.Set<String> RULES = java.util.Set.of(
            System.getProperty("gaius.perf.oreRules", "").split(","));
    private static OutputStream fixtures;

    private record Target(RuleTest rule, BlockState state, Method test) {
        boolean matches(BlockState source, BlockPos position, RandomSource random) throws ReflectiveOperationException {
            return (boolean) test.invoke(rule, test.getParameterCount() == 3
                    ? new Object[] {source, position, random} : new Object[] {source, random});
        }
    }
    private record Settings(int size, float discard, List<Target> targets) {}
    private static final class Capture {
        final int[] minimum;
        final int width;
        final int height;
        final List<Integer> visits = new ArrayList<>();
        final List<Integer> writes = new ArrayList<>();
        final Map<LevelChunkSection, int[]> origins = new IdentityHashMap<>();
        Capture(int[] minimum, int width, int height) {
            this.minimum = minimum;
            this.width = width;
            this.height = height;
        }
    }

    private static Object member(Object object, String name) throws ReflectiveOperationException {
        try { return object.getClass().getMethod(name).invoke(object); }
        catch (NoSuchMethodException missing) { return object.getClass().getField(name).get(object); }
    }

    private static boolean supported(RuleTest rule, int depth) throws ReflectiveOperationException {
        if (depth > 8) return false;
        String name = rule.getClass().getSimpleName();
        if (RULES.contains(name)) return true;
        if (!name.equals(System.getProperty("gaius.perf.oreUnionRule", ""))) return false;
        var field = rule.getClass().getDeclaredField("rules");
        field.setAccessible(true);
        var children = (List<?>) field.get(rule);
        if (children.isEmpty() || children.size() > 32) return false;
        for (Object child : children) if (!supported((RuleTest) child, depth + 1)) return false;
        return true;
    }

    private static Settings readSettings(Object object) throws ReflectiveOperationException {
        int size = ((Number) member(object, "size")).intValue();
        float discard = ((Number) member(object, "discardChanceOnAirExposure")).floatValue();
        if (size < 1 || size > 64 || discard != 0.0F) {
            REASONS.merge("size-or-air:" + size + ":" + discard, 1L, Long::sum);
            return null;
        }
        List<Target> targets = new ArrayList<>();
        for (Object target : (List<?>) member(object, "targetStates")) {
            RuleTest rule = (RuleTest) member(target, "target");
            String name = rule.getClass().getSimpleName();
            if (!supported(rule, 0)) {
                REASONS.merge("rule:" + name, 1L, Long::sum);
                return null;
            }
            Method test = Arrays.stream(rule.getClass().getMethods()).filter(method -> method.getName().equals("test"))
                    .findFirst().orElseThrow();
            targets.add(new Target(rule, (BlockState) member(target, "state"), test));
        }
        return targets.isEmpty() ? null : new Settings(size, discard, List.copyOf(targets));
    }

    private static Settings settings(Object object) throws ReflectiveOperationException {
        if (!SETTINGS.containsKey(object)) SETTINGS.put(object, readSettings(object));
        return SETTINGS.get(object);
    }

    private static boolean original(Object feature, Object[] arguments) throws Throwable {
        try { return (boolean) ORIGINAL.get(feature.getClass()).invoke(feature, arguments); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] job(byte[] payload) {
        return buffer(16 + payload.length).putInt(0x424a4b47).putShort((short) 1).putShort((short) 0x0405)
                .putInt(1).putInt(payload.length).put(payload).array();
    }

    private static ByteBuffer result(byte[] bytes) {
        ByteBuffer result = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 20 || result.getInt() != 0x53524b47 || result.getShort() != 1
                || result.getShort() != 0 || result.getInt() != 1 || result.getInt() != bytes.length - 16) {
            throw new IllegalStateException("Invalid ore result frame");
        }
        return result;
    }

    private static byte[] expected(List<Integer> values, int stride) {
        ByteBuffer bytes = buffer(20 + values.size() * 4);
        bytes.putInt(0x53524b47).putShort((short) 1).putShort((short) 0).putInt(1)
                .putInt(4 + values.size() * 4).putInt(values.size() / stride);
        for (int value : values) bytes.putInt(value);
        return bytes.array();
    }

    private static synchronized void fixture(byte[] input, byte[] output) throws Exception {
        if (fixtures == null) fixtures = new BufferedOutputStream(Files.newOutputStream(
                Path.of(System.getProperty("gaius.perf.oreFixtures"))));
        fixtures.write(buffer(8).putInt(input.length).putInt(output.length).array());
        fixtures.write(input);
        fixtures.write(output);
    }

    public static void visit(BitSet bits, int index) {
        bits.set(index);
        Capture capture = CAPTURE.get();
        if (capture == null) return;
        capture.visits.add(capture.minimum[0] + index % capture.width);
        capture.visits.add(capture.minimum[1] + index / capture.width % capture.height);
        capture.visits.add(capture.minimum[2] + index / capture.width / capture.height);
    }

    public static BlockState write(LevelChunkSection section, int x, int y, int z, BlockState state, boolean lock) {
        Capture capture = CAPTURE.get();
        if (capture != null) {
            int[] origin = capture.origins.get(section);
            if (origin == null) throw new IllegalStateException("Ore write outside immutable snapshot");
            capture.writes.add(origin[0] + x);
            capture.writes.add(origin[1] + y);
            capture.writes.add(origin[2] + z);
            capture.writes.add(Block.getId(state));
        }
        return section.setBlockState(x, y, z, state, lock);
    }

    public static boolean dispatch(Object feature, Object[] arguments) throws Throwable {
        WorldGenLevel level = (WorldGenLevel) arguments[0];
        RandomSource random = (RandomSource) arguments[1];
        boolean legacy = arguments.length == 14;
        int offset = legacy ? 3 : 2;
        Settings settings = settings(legacy ? arguments[2] : feature);
        if (settings == null || !(level instanceof WorldGenRegion)
                || !level.getLevel().dimension().equals(Level.OVERWORLD)) {
            REASONS.merge(settings == null ? "settings" : "dimension", 1L, Long::sum);
            declined++;
            return original(feature, arguments);
        }
        int[] minimum = {(int) arguments[offset + 6], (int) arguments[offset + 7], (int) arguments[offset + 8]};
        int width = (int) arguments[offset + 9];
        int height = (int) arguments[offset + 10];
        if (width < 1 || width > 64 || height < 1 || height > 64) {
            REASONS.merge("bounds", 1L, Long::sum);
            declined++;
            return original(feature, arguments);
        }
        for (int chunkX = minimum[0] >> 4; chunkX <= (minimum[0] + width - 1) >> 4; chunkX++) {
            for (int chunkZ = minimum[2] >> 4; chunkZ <= (minimum[2] + width - 1) >> 4; chunkZ++) {
                var chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.EMPTY, false);
                if (chunk == null || chunk.isUpgrading() || chunk.getBelowZeroRetrogen() != null
                        || chunk.getBlendingData() != null) {
                    REASONS.merge(chunk == null ? "missing-chunk" : chunk.isUpgrading() ? "upgrading"
                            : chunk.getBelowZeroRetrogen() != null ? "retrogen" : "blending", 1L, Long::sum);
                    declined++;
                    return original(feature, arguments);
                }
            }
        }
        double[] draws = new double[settings.size];
        int[] consumed = {0};
        RandomSource replay = (RandomSource) Proxy.newProxyInstance(RandomSource.class.getClassLoader(),
                new Class<?>[] {RandomSource.class}, (proxy, method, values) -> {
                    if (method.getName().equals("nextDouble")) {
                        if (consumed[0] >= draws.length) throw new IllegalStateException("Unexpected ore RNG draw");
                        return draws[consumed[0]++];
                    }
                    throw new IllegalStateException("Unsupported ore RNG method: " + method.getName());
                });
        ByteBuffer geometry = buffer(40 + settings.size * 32);
        geometry.putInt(0).putInt(1).putInt(settings.size);
        for (int coordinate : minimum) geometry.putInt(coordinate);
        geometry.putInt(width).putInt(height).putInt(level.getMinY()).putInt(level.getMinY() + level.getHeight());
        for (int sphere = 0; sphere < settings.size; sphere++) {
            float fraction = (float) sphere / (float) settings.size;
            double centerX = Mth.lerp((double) fraction, (double) arguments[offset], (double) arguments[offset + 1]);
            double centerY = Mth.lerp((double) fraction, (double) arguments[offset + 4], (double) arguments[offset + 5]);
            double centerZ = Mth.lerp((double) fraction, (double) arguments[offset + 2], (double) arguments[offset + 3]);
            draws[sphere] = random.nextDouble();
            double variation = draws[sphere] * (double) settings.size / 16.0;
            double radius = ((double) (Mth.sin((float) Math.PI * fraction) + 1.0F) * variation + 1.0) / 2.0;
            geometry.putDouble(centerX).putDouble(centerY).putDouble(centerZ).putDouble(radius);
        }
        byte[] geometryJob = job(geometry.array());
        long start = System.nanoTime();
        byte[] geometryResult;
        try { geometryResult = KernelHost.run(0x0405, geometryJob); }
        catch (RuntimeException declinedGeometry) {
            REASONS.merge("geometry", 1L, Long::sum);
            declined++;
            Object[] fallback = arguments.clone();
            fallback[1] = replay;
            return original(feature, fallback);
        }
        geometryNanos += System.nanoTime() - start;
        ByteBuffer points = result(geometryResult);
        int count = points.getInt();
        if (count < 0 || count > 262144 || points.remaining() != count * 12) {
            throw new IllegalStateException("Invalid ore point count");
        }
        ByteBuffer snapshot = buffer(12 + count * 16).putInt(1).putInt(1).putInt(count);
        Capture capture = new Capture(minimum, width, height);
        start = System.nanoTime();
        try (BulkSectionAccess access = new BulkSectionAccess(level)) {
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            for (int point = 0; point < count; point++) {
                int x = points.getInt();
                int y = points.getInt();
                int z = points.getInt();
                position.set(x, y, z);
                int replacement = -1;
                if (level.ensureCanWrite(position)) {
                    LevelChunkSection section = access.getSection(position);
                    if (section != null) {
                        capture.origins.putIfAbsent(section, new int[] {x & ~15, y & ~15, z & ~15});
                        BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                        for (Target target : settings.targets) {
                            if (target.matches(state, position, replay)) { replacement = Block.getId(target.state); break; }
                        }
                    }
                }
                snapshot.putInt(x).putInt(y).putInt(z).putInt(replacement);
            }
            snapshotNanos += System.nanoTime() - start;
            if (System.getProperty("gaius.perf.ore").equals("kernel")) {
                start = System.nanoTime();
                byte[] deltaResult = KernelHost.run(0x0405, job(snapshot.array()));
                deltaNanos += System.nanoTime() - start;
                return installDelta(level, access, deltaResult);
            }
        }
        byte[] deltaJob = job(snapshot.array());
        start = System.nanoTime();
        byte[] deltaResult = KernelHost.run(0x0405, deltaJob);
        deltaNanos += System.nanoTime() - start;
        ByteBuffer delta = result(deltaResult);
        int changed = delta.getInt();
        if (changed < 0 || delta.remaining() != changed * 16) throw new IllegalStateException("Invalid ore delta");
        Object[] vanilla = arguments.clone();
        vanilla[1] = replay;
        boolean placed;
        start = System.nanoTime();
        CAPTURE.set(capture);
        try { placed = original(feature, vanilla); }
        finally { CAPTURE.remove(); }
        vanillaNanos += System.nanoTime() - start;
        if (consumed[0] != draws.length || placed != (changed > 0)
                || !Arrays.equals(geometryResult, expected(capture.visits, 3))
                || !Arrays.equals(deltaResult, expected(capture.writes, 4))) {
            Path directory = Path.of(System.getProperty("gaius.perf.report")).getParent();
            Files.write(directory.resolve("ore-mismatch-input.bin"), geometryJob);
            Files.write(directory.resolve("ore-mismatch-rust.bin"), geometryResult);
            Files.write(directory.resolve("ore-mismatch-java.bin"), expected(capture.visits, 3));
            Files.write(directory.resolve("ore-mismatch-snapshot.bin"), deltaJob);
            Files.write(directory.resolve("ore-mismatch-delta.bin"), expected(capture.writes, 4));
            throw new IllegalStateException("Vanilla ore mismatch: draws=" + consumed[0] + "/" + draws.length
                    + " placed=" + placed + " changed=" + changed + " visits=" + capture.visits.size() / 3
                    + "/" + count + " geometryByte=" + Arrays.mismatch(geometryResult, expected(capture.visits, 3))
                    + " deltaByte=" + Arrays.mismatch(deltaResult, expected(capture.writes, 4)));
        }
        for (int entry = 0; entry < changed; entry++) {
            int x = delta.getInt();
            int y = delta.getInt();
            int z = delta.getInt();
            int state = delta.getInt();
            if (Block.getId(level.getBlockState(new BlockPos(x, y, z))) != state) {
                throw new IllegalStateException("Ore install mismatch");
            }
        }
        fixture(geometryJob, expected(capture.visits, 3));
        fixture(deltaJob, expected(capture.writes, 4));
        accepted++;
        ACCEPTED_CHUNKS.add(((WorldGenRegion) level).getCenter().toString());
        return placed;
    }

    private static boolean installDelta(WorldGenLevel level, BulkSectionAccess access, byte[] bytes) {
        ByteBuffer delta = result(bytes);
        int changed = delta.getInt();
        if (changed < 0 || delta.remaining() != changed * 16) throw new IllegalStateException("Invalid ore delta");
        long start = System.nanoTime();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int entry = 0; entry < changed; entry++) {
            int x = delta.getInt();
            int y = delta.getInt();
            int z = delta.getInt();
            BlockState state = Block.stateById(delta.getInt());
            position.set(x, y, z);
            access.getSection(position).setBlockState(x & 15, y & 15, z & 15, state, false);
        }
        installNanos += System.nanoTime() - start;
        accepted++;
        ACCEPTED_CHUNKS.add(((WorldGenRegion) level).getCenter().toString());
        return changed > 0;
    }

    public static void resetMeasurement() {
        accepted = 0;
        declined = 0;
        geometryNanos = 0;
        snapshotNanos = 0;
        vanillaNanos = 0;
        installNanos = 0;
        deltaNanos = 0;
        ACCEPTED_CHUNKS.clear();
        REASONS.clear();
    }

    public static Map<String, Object> report() throws Exception {
        if (fixtures != null) fixtures.flush();
        return Map.of("accepted", accepted, "declined", declined, "geometryHostNanos", geometryNanos,
                "snapshotNanos", snapshotNanos, "vanillaNanos", vanillaNanos, "installNanos", installNanos,
                "declineReasons", REASONS, "deltaHostNanos", deltaNanos, "acceptedChunkCount", ACCEPTED_CHUNKS.size());
    }
}
