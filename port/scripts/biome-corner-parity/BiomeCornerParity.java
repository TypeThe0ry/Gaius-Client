package dev.gaius.parity;

import dev.gaius.browser.BrowserBiomeNearestCorner;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Compares BrowserBiomeNearestCorner with the vanilla BiomeManager of the profile.
 *
 * <p>Two references. "vanilla" calls the real {@code BiomeManager.getBiome(BlockPos)} with a
 * recording biome source and reads the corner back from the quart it asked for. "replica"
 * rebuilds getBiome/getFiddledDistance/getFiddle from LinearCongruentialGenerator.next, Mth.square
 * and the same bit operations. Both must equal the implementation under test for every case,
 * including cases that revisit corners of the same seed (the cache) and cases that collide.
 *
 * <p>Arguments: {@code --count N} cases per random family (default 1000000), {@code --seed S},
 * {@code --emit FILE N} writes N vectors "seed x y z corner" (shifted coordinates) for the
 * JavaScript implementation, {@code --bench} prints timings.
 */
public final class BiomeCornerParity {
    private static final long[] SEEDS = {
            0L, 1L, -1L, 2L, -2L, Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE + 1,
            Long.MAX_VALUE - 1, 6364136223846793005L, 1442695040888963407L, 0x5DEECE66DL,
            1L << 32, -(1L << 32), (1L << 53) + 1, -(1L << 53) - 1, 1L << 24, 0xFFFFFFFFL,
            0x80000000L, 0x7FFFFFFFL, 0xDEADBEEFCAFEBABEL, 8682522807148012L, 1181783497276652981L,
    };

    /** Shifted coordinates (block - 2) that sit on quart and sign boundaries. */
    private static final int[] EDGES = buildEdges();

    /** A coarser set of the same boundaries, small enough to be crossed three times over. */
    private static final int[] GRID = {
            Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -(1 << 30) - 1, -(1 << 30), -(1 << 29),
            -(1 << 28) - 1, -(1 << 28), -(1 << 28) + 1, -29999984, -65, -5, -4, -3, -2, -1, 0, 1, 2,
            3, 4, 5, 63, 64, 319, 320, (1 << 28) - 1, 1 << 28, (1 << 28) + 1, 29999984, 1 << 29,
            1 << 30, Integer.MAX_VALUE - 1, Integer.MAX_VALUE,
    };

    private final Object source;
    private final Constructor<BiomeManager> constructor;
    private final int[] lastQuart = new int[3];

    // The private per-corner fiddle lookup of the implementation (packed ten-bit values), which
    // exposes the whole LCG chain and the cache directly: the nearest corner alone is insensitive
    // to a wrong low-order bit of a fiddle in most cases.
    private final MethodHandle fiddles;

    private long cases;
    private long mismatches;
    private final List<String> examples = new ArrayList<>();

    private BiomeCornerParity() throws Throwable {
        Constructor<?>[] all = BiomeManager.class.getConstructors();
        Constructor<BiomeManager> found = null;
        for (Constructor<?> candidate : all) {
            Class<?>[] types = candidate.getParameterTypes();
            if (types.length == 2 && types[0].isInterface() && types[1] == long.class) {
                @SuppressWarnings("unchecked")
                Constructor<BiomeManager> typed = (Constructor<BiomeManager>) candidate;
                found = typed;
            }
        }
        if (found == null) {
            throw new IllegalStateException("BiomeManager has no (source, long) constructor");
        }
        constructor = found;
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                BrowserBiomeNearestCorner.class, MethodHandles.lookup());
        fiddles = lookup.findStatic(BrowserBiomeNearestCorner.class, "fiddles",
                MethodType.methodType(int.class, long.class, int.class, int.class, int.class));
        java.lang.reflect.Field table = BrowserBiomeNearestCorner.class.getDeclaredField("FIDDLE");
        table.setAccessible(true);
        double[] fiddleTable = (double[]) table.get(null);
        for (int bits = 0; bits < 1024; bits++) {
            double expected = fiddle((long) bits << 24);
            if (Double.doubleToRawLongBits(fiddleTable[bits]) != Double.doubleToRawLongBits(expected)) {
                throw new AssertionError("fiddle table differs at " + bits);
            }
        }
        Class<?> sourceType = found.getParameterTypes()[0];
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("getNoiseBiome") && args != null && args.length == 3) {
                lastQuart[0] = (Integer) args[0];
                lastQuart[1] = (Integer) args[1];
                lastQuart[2] = (Integer) args[2];
                return null;
            }
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }
            throw new UnsupportedOperationException(method.toString());
        };
        source = Proxy.newProxyInstance(sourceType.getClassLoader(), new Class<?>[] {sourceType}, handler);
    }

    private static int[] buildEdges() {
        List<Integer> values = new ArrayList<>();
        int[] anchors = {0, 1 << 10, 1 << 16, 1 << 20, 1 << 24, 29999984, 1 << 28, (1 << 28) + (1 << 27),
                1 << 29, 1 << 30, Integer.MAX_VALUE};
        for (int anchor : anchors) {
            for (int delta = -9; delta <= 9; delta++) {
                values.add(anchor + delta);
                values.add(-anchor + delta);
            }
        }
        values.add(Integer.MIN_VALUE);
        values.add(Integer.MIN_VALUE + 1);
        values.add(Integer.MIN_VALUE + 2);
        values.add(Integer.MIN_VALUE + 3);
        values.add(Integer.MIN_VALUE + 4);
        values.add(Integer.MIN_VALUE + 5);
        return values.stream().distinct().mapToInt(Integer::intValue).toArray();
    }

    /** Vanilla BiomeManager: the corner of the quart it asked its biome source for. */
    private int vanilla(long seed, int shiftedX, int shiftedY, int shiftedZ) throws Exception {
        BiomeManager manager = constructor.newInstance(source, seed);
        manager.getBiome(new BlockPos(shiftedX + 2, shiftedY + 2, shiftedZ + 2));
        int cornerX = lastQuart[0] - (shiftedX >> 2);
        int cornerY = lastQuart[1] - (shiftedY >> 2);
        int cornerZ = lastQuart[2] - (shiftedZ >> 2);
        if ((cornerX | cornerY | cornerZ) < 0 || cornerX > 1 || cornerY > 1 || cornerZ > 1) {
            throw new AssertionError("vanilla asked for a quart outside the cell: "
                    + cornerX + "," + cornerY + "," + cornerZ);
        }
        return cornerX << 2 | cornerY << 1 | cornerZ;
    }

    /** The vanilla algorithm rebuilt from LinearCongruentialGenerator and Mth. */
    private static int replica(long seed, int shiftedX, int shiftedY, int shiftedZ) {
        int baseX = shiftedX >> 2;
        int baseY = shiftedY >> 2;
        int baseZ = shiftedZ >> 2;
        double fractionX = (double) (shiftedX & 3) / 4.0;
        double fractionY = (double) (shiftedY & 3) / 4.0;
        double fractionZ = (double) (shiftedZ & 3) / 4.0;
        int nearest = 0;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            boolean lowX = (corner & 4) == 0;
            boolean lowY = (corner & 2) == 0;
            boolean lowZ = (corner & 1) == 0;
            double distance = fiddledDistance(seed, lowX ? baseX : baseX + 1,
                    lowY ? baseY : baseY + 1, lowZ ? baseZ : baseZ + 1,
                    lowX ? fractionX : fractionX - 1.0, lowY ? fractionY : fractionY - 1.0,
                    lowZ ? fractionZ : fractionZ - 1.0);
            if (nearestDistance > distance) {
                nearest = corner;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static double fiddledDistance(long seed, int x, int y, int z,
            double fractionX, double fractionY, double fractionZ) {
        long value = LinearCongruentialGenerator.next(seed, x);
        value = LinearCongruentialGenerator.next(value, y);
        value = LinearCongruentialGenerator.next(value, z);
        value = LinearCongruentialGenerator.next(value, x);
        value = LinearCongruentialGenerator.next(value, y);
        value = LinearCongruentialGenerator.next(value, z);
        double fiddleX = fiddle(value);
        value = LinearCongruentialGenerator.next(value, seed);
        double fiddleY = fiddle(value);
        value = LinearCongruentialGenerator.next(value, seed);
        double fiddleZ = fiddle(value);
        return Mth.square(fractionZ + fiddleZ) + Mth.square(fractionY + fiddleY)
                + Mth.square(fractionX + fiddleX);
    }

    /** The three ten-bit values of getFiddle for a corner, packed like the implementation. */
    private static int expectedFiddles(long seed, int x, int y, int z) {
        long value = LinearCongruentialGenerator.next(seed, x);
        value = LinearCongruentialGenerator.next(value, y);
        value = LinearCongruentialGenerator.next(value, z);
        value = LinearCongruentialGenerator.next(value, x);
        value = LinearCongruentialGenerator.next(value, y);
        value = LinearCongruentialGenerator.next(value, z);
        int packed = Math.floorMod(value >> 24, 1024);
        value = LinearCongruentialGenerator.next(value, seed);
        packed |= Math.floorMod(value >> 24, 1024) << 10;
        value = LinearCongruentialGenerator.next(value, seed);
        packed |= Math.floorMod(value >> 24, 1024) << 20;
        return packed;
    }

    private static double fiddle(long value) {
        double bits = (double) Math.floorMod(value >> 24, 1024) / 1024.0;
        return (bits - 0.5) * 0.9;
    }

    private void check(String family, boolean real, long seed, int x, int y, int z) throws Throwable {
        int actual = BrowserBiomeNearestCorner.nearestCorner(seed, x, y, z);
        int expected = replica(seed, x, y, z);
        cases++;
        boolean bad = actual != expected;
        // The cache now holds the eight corners of this position: each must equal the vanilla
        // chain bit for bit, whether it was computed just now or found in the cache.
        int baseX = x >> 2;
        int baseY = y >> 2;
        int baseZ = z >> 2;
        for (int corner = 0; corner < 8; corner++) {
            int qx = (corner & 4) != 0 ? baseX + 1 : baseX;
            int qy = (corner & 2) != 0 ? baseY + 1 : baseY;
            int qz = (corner & 1) != 0 ? baseZ + 1 : baseZ;
            int packed = (int) fiddles.invokeExact(seed, qx, qy, qz);
            if (packed != expectedFiddles(seed, qx, qy, qz)) {
                bad = true;
            }
        }
        int vanillaCorner = -1;
        if (real) {
            vanillaCorner = vanilla(seed, x, y, z);
            bad |= vanillaCorner != expected;
        }
        if (bad) {
            mismatches++;
            if (examples.size() < 12) {
                examples.add(family + " seed=" + seed + " shifted=" + x + "," + y + "," + z
                        + " impl=" + actual + " replica=" + expected + " vanilla=" + vanillaCorner);
            }
        }
    }

    private void report(String family, long before, long mismatchesBefore) {
        System.out.println("BIOME_CORNER_FAMILY " + family + " cases=" + (cases - before)
                + " mismatches=" + (mismatches - mismatchesBefore));
    }

    private int worldCoordinate(SplittableRandom random, boolean vertical) {
        return vertical ? random.nextInt(-72, 330) : random.nextInt(-30_000_000, 30_000_000);
    }

    private void run(long count, long randomSeed) throws Throwable {
        SplittableRandom random = new SplittableRandom(randomSeed);

        // 1. Edge grid: every special seed against every boundary coordinate of the three axes
        // (replica always, the real BiomeManager for a thinned subset of the grid).
        long before = cases;
        long bad = mismatches;
        for (long seed : SEEDS) {
            for (int x : GRID) {
                for (int y : GRID) {
                    for (int z : GRID) {
                        check("edge-grid", ((x ^ y ^ z) & 7) == 0, seed, x, y, z);
                    }
                }
            }
        }
        report("edge-grid", before, bad);

        // 2. Edge coordinates with random seeds (every call flushes the cache).
        before = cases;
        bad = mismatches;
        for (long index = 0; index < count / 4; index++) {
            check("edge-random-seed", true, random.nextLong(),
                    EDGES[random.nextInt(EDGES.length)], EDGES[random.nextInt(EDGES.length)],
                    EDGES[random.nextInt(EDGES.length)]);
        }
        report("edge-random-seed", before, bad);

        // 3. Uniform over the whole int range and random seeds: no cache hit is possible.
        before = cases;
        bad = mismatches;
        for (long index = 0; index < count; index++) {
            check("uniform-int", true, random.nextLong(), random.nextInt(), random.nextInt(),
                    random.nextInt());
        }
        report("uniform-int", before, bad);

        // 4. World-like block positions, random seed per case.
        before = cases;
        bad = mismatches;
        for (long index = 0; index < count; index++) {
            check("world-random-seed", true, random.nextLong(), worldCoordinate(random, false) - 2,
                    worldCoordinate(random, true) - 2, worldCoordinate(random, false) - 2);
        }
        report("world-random-seed", before, bad);

        // 5. One seed, positions near each other (many cache hits): a chunk-like random walk.
        before = cases;
        bad = mismatches;
        for (long index = 0; index < count; ) {
            long seed = random.nextBoolean() ? random.nextLong() : SEEDS[random.nextInt(SEEDS.length)];
            int x = worldCoordinate(random, false);
            int y = worldCoordinate(random, true);
            int z = worldCoordinate(random, false);
            for (int step = 0; step < 4096 && index < count; step++, index++) {
                x += random.nextInt(-5, 6);
                y += random.nextInt(-3, 4);
                z += random.nextInt(-5, 6);
                check("same-seed-walk", (index & 3) == 0, seed, x, y, z);
            }
        }
        report("same-seed-walk", before, bad);

        // 6. One seed, random positions over a window that overflows the 4096 slots (collisions
        // and evictions), at the world edge, around the origin and over the whole int range.
        before = cases;
        bad = mismatches;
        int[] centers = {0, 1 << 28, -(1 << 28), 29_999_984, Integer.MAX_VALUE - 300, Integer.MIN_VALUE + 40};
        for (long index = 0; index < count; ) {
            long seed = SEEDS[random.nextInt(SEEDS.length)] ^ (random.nextBoolean() ? 0 : random.nextLong());
            int center = centers[random.nextInt(centers.length)];
            int span = 1 << random.nextInt(4, 14);
            for (int step = 0; step < 20_000 && index < count; step++, index++) {
                check("same-seed-window", (index & 3) == 1, seed,
                        center + random.nextInt(-span, span), random.nextInt(-80, 340),
                        center + random.nextInt(-span, span));
            }
        }
        report("same-seed-window", before, bad);

        // 7. Seeds alternating with cached positions: a stale entry must never leak across seeds.
        before = cases;
        bad = mismatches;
        for (long index = 0; index < count / 4; index++) {
            int x = random.nextInt(-64, 64);
            int y = random.nextInt(-64, 64);
            int z = random.nextInt(-64, 64);
            for (int variant = 0; variant < 3; variant++) {
                long seed = variant == 1 ? SEEDS[(int) (index % SEEDS.length)] : (variant == 0 ? 42L : 43L);
                check("alternating-seed", variant != 1, seed, x, y, z);
            }
        }
        report("alternating-seed", before, bad);
    }

    private static void emit(Path file, long count, long randomSeed) throws IOException {
        SplittableRandom random = new SplittableRandom(randomSeed ^ 0x9E3779B97F4A7C15L);
        try (BufferedWriter out = Files.newBufferedWriter(file)) {
            long written = 0;
            while (written < count) {
                long seed = random.nextInt(4) == 0 ? SEEDS[random.nextInt(SEEDS.length)] : random.nextLong();
                int center = EDGES[random.nextInt(EDGES.length)];
                int span = 1 << random.nextInt(2, 12);
                int groupSize = 1 + random.nextInt(2000);
                for (int step = 0; step < groupSize && written < count; step++, written++) {
                    int x = random.nextInt(8) == 0 ? EDGES[random.nextInt(EDGES.length)]
                            : center + random.nextInt(-span, span);
                    int y = random.nextInt(8) == 0 ? EDGES[random.nextInt(EDGES.length)]
                            : random.nextInt(-80, 340);
                    int z = random.nextInt(8) == 0 ? EDGES[random.nextInt(EDGES.length)]
                            : center + random.nextInt(-span, span);
                    out.write(seed + " " + x + " " + y + " " + z + " " + replica(seed, x, y, z) + "\n");
                }
            }
        }
    }

    // ---- timings (JIT-compiled HotSpot, not V8 and not WebAssembly: ratios, not milliseconds) ----

    private static volatile int sink;

    private static void bench() {
        long seed = 8682522807148012L;
        int[][] patterns = new int[3][];
        String[] names = {"scan(16x16 blocks, y step 4)", "sparse(random 64x64x64 window)", "cold(random 60M range)"};
        int n = 1 << 20;
        for (int pattern = 0; pattern < 3; pattern++) {
            SplittableRandom random = new SplittableRandom(7);
            int[] data = new int[n * 3];
            for (int i = 0; i < n; i++) {
                int x;
                int y;
                int z;
                if (pattern == 0) {
                    int column = i % 256;
                    int layer = i / 256;
                    x = 1000 + column % 16 + (layer / 90) * 16;
                    z = 3000 + column / 16;
                    y = (layer % 90) * 4;
                } else if (pattern == 1) {
                    x = random.nextInt(64);
                    y = random.nextInt(64);
                    z = random.nextInt(64);
                } else {
                    x = random.nextInt(-30_000_000, 30_000_000);
                    y = random.nextInt(-64, 320);
                    z = random.nextInt(-30_000_000, 30_000_000);
                }
                data[i * 3] = x;
                data[i * 3 + 1] = y;
                data[i * 3 + 2] = z;
            }
            patterns[pattern] = data;
        }
        for (int pattern = 0; pattern < 3; pattern++) {
            int[] data = patterns[pattern];
            double best = Double.MAX_VALUE;
            double bestReplica = Double.MAX_VALUE;
            for (int round = 0; round < 12; round++) {
                long start = System.nanoTime();
                int acc = 0;
                for (int i = 0; i < n; i++) {
                    acc += BrowserBiomeNearestCorner.nearestCorner(seed, data[i * 3], data[i * 3 + 1], data[i * 3 + 2]);
                }
                sink = acc;
                best = Math.min(best, (System.nanoTime() - start) / (double) n);
                start = System.nanoTime();
                acc = 0;
                for (int i = 0; i < n; i++) {
                    acc += replica(seed, data[i * 3], data[i * 3 + 1], data[i * 3 + 2]);
                }
                sink = acc;
                bestReplica = Math.min(bestReplica, (System.nanoTime() - start) / (double) n);
            }
            System.out.printf("BIOME_CORNER_BENCH %s cached-java=%.1f ns/call vanilla-replica=%.1f ns/call%n",
                    names[pattern], best, bestReplica);
        }
    }

    public static void main(String[] args) throws Throwable {
        long count = 1_000_000;
        long randomSeed = 20261113L;
        Path emitFile = null;
        long emitCount = 0;
        boolean bench = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--count" -> count = Long.parseLong(args[++i]);
                case "--seed" -> randomSeed = Long.parseLong(args[++i]);
                case "--emit" -> {
                    emitFile = Path.of(args[++i]);
                    emitCount = Long.parseLong(args[++i]);
                }
                case "--bench" -> bench = true;
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        if (emitFile != null) {
            emit(emitFile, emitCount, randomSeed);
            System.out.println("BIOME_CORNER_EMIT " + emitCount);
            return;
        }
        if (bench) {
            bench();
            return;
        }
        BiomeCornerParity parity = new BiomeCornerParity();
        parity.run(count, randomSeed);
        for (String example : parity.examples) {
            System.out.println("BIOME_CORNER_MISMATCH " + example);
        }
        System.out.println("BIOME_CORNER_TOTAL cases=" + parity.cases + " mismatches=" + parity.mismatches);
        if (parity.mismatches != 0) {
            System.exit(1);
        }
    }
}
