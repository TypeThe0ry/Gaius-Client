package dev.gaius.parity;

import com.mojang.datafixers.util.Pair;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * JVM parity and speed check of the WasmGC multi-noise lookup patch
 * (MinecraftClientPatcher.patchClimateRTreeBrowserFlatIndex, GaiusClimateIndex).
 *
 * <p>The same harness class is loaded twice, in isolated class loaders: once over the vanilla
 * client jar and once over the patched Climate classes plus GaiusClimateIndex in front of the
 * same jar. Each copy runs identical, seeded lookup sequences through the public
 * {@code Climate.ParameterList.findValue} (so through {@code RTree.search} and its per-thread
 * {@code lastResult}) and records, for every lookup, the index of the list entry it returned.
 * Every entry carries its own index as value, so the comparison is per leaf, not per biome.
 * The two runs must be identical lookup for lookup.
 *
 * <p>Each case also counts the lookups whose answer depends on the {@code lastResult} history:
 * the same target asked of a second tree whose {@code lastResult} was cleared first gives another
 * leaf (an exact tie decided by the candidate). A case with such lookups proves that the history,
 * not only the nearest distance, is reproduced.
 *
 * <p>Usage: {@code java -cp <harness>:<vanilla jar>:<libraries> dev.gaius.parity.ClimateRTreeParity
 * --vanilla <cp> --patched <cp> --harness <dir> [--scale N] [--bench-rounds N]}; the class paths
 * use the platform path separator. Prints {@code CLIMATE_RTREE_PARITY PASS|FAIL} and a JSON line.
 */
public final class ClimateRTreeParity {
    private static final int CHUNK_QUARTS_XZ = 4;
    private static final int CHUNK_QUARTS_Y = 96;

    private ClimateRTreeParity() {
    }

    // ---------------------------------------------------------------- orchestrator

    public static void main(String[] args) throws Exception {
        String vanilla = null;
        String patched = null;
        String harness = null;
        int scale = 1;
        int benchRounds = 7;
        int survey = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--survey" -> survey = Integer.parseInt(args[++i]);
                case "--vanilla" -> vanilla = args[++i];
                case "--patched" -> patched = args[++i];
                case "--harness" -> harness = args[++i];
                case "--scale" -> scale = Integer.parseInt(args[++i]);
                case "--bench-rounds" -> benchRounds = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (vanilla == null || patched == null || harness == null) {
            throw new IllegalArgumentException("--vanilla, --patched and --harness are required");
        }
        ClassLoader vanillaLoader = loader(harness, vanilla);
        ClassLoader patchedLoader = loader(harness, patched);

        Map<String, Object> vanillaRun = invoke(vanillaLoader, "cases", scale);
        Map<String, Object> patchedRun = invoke(patchedLoader, "cases", scale);
        boolean patchedActive = (Boolean) invoke(patchedLoader, "flatIndexActive");
        boolean vanillaActive = (Boolean) invoke(vanillaLoader, "flatIndexActive");

        boolean pass = patchedActive && !vanillaActive;
        StringBuilder json = new StringBuilder("{\"patchedUsesFlatIndex\":").append(patchedActive)
                .append(",\"vanillaUsesFlatIndex\":").append(vanillaActive).append(",\"cases\":{");
        boolean first = true;
        for (String name : vanillaRun.keySet()) {
            Object[] expected = (Object[]) vanillaRun.get(name);
            Object[] actual = (Object[]) patchedRun.get(name);
            int[] expectedIds = (int[]) expected[0];
            int[] actualIds = actual == null ? new int[0] : (int[]) actual[0];
            int firstDifference = firstDifference(expectedIds, actualIds);
            long expectedHistory = (Long) expected[1];
            long actualHistory = actual == null ? -1 : (Long) actual[1];
            boolean match = firstDifference < 0 && expectedHistory == actualHistory;
            pass &= match;
            json.append(first ? "" : ",").append('"').append(name).append("\":{\"lookups\":")
                    .append(expectedIds.length).append(",\"match\":").append(match)
                    .append(",\"firstDifference\":").append(firstDifference)
                    .append(",\"historyDependentLookups\":").append(expectedHistory)
                    .append(",\"patchedHistoryDependentLookups\":").append(actualHistory)
                    .append(",\"sha256\":\"").append(sha256(expectedIds)).append("\"}");
            first = false;
            int repeats = 0;
            for (int i = 1; i < expectedIds.length; i++) {
                if (expectedIds[i] == expectedIds[i - 1]) {
                    repeats++;
                }
            }
            System.out.println("CLIMATE_RTREE_CASE " + name + " lookups=" + expectedIds.length
                    + " match=" + match + " firstDifference=" + firstDifference
                    + " historyDependent=" + expectedHistory + "/" + actualHistory
                    + " sameLeafAsPrevious=" + repeats);
        }
        json.append("}");

        if (benchRounds > 0) {
            long[] vanillaNanos = (long[]) invoke(vanillaLoader, "bench", benchRounds);
            long[] patchedNanos = (long[]) invoke(patchedLoader, "bench", benchRounds);
            long lookups = vanillaNanos[0];
            double vanillaNs = Arrays.stream(vanillaNanos, 1, vanillaNanos.length).min().orElseThrow()
                    / (double) lookups;
            double patchedNs = Arrays.stream(patchedNanos, 1, patchedNanos.length).min().orElseThrow()
                    / (double) lookups;
            System.out.printf("CLIMATE_RTREE_BENCH lookups=%d vanilla=%.1fns patched=%.1fns speedup=%.2fx%n",
                    lookups, vanillaNs, patchedNs, vanillaNs / patchedNs);
            json.append(String.format(",\"bench\":{\"lookups\":%d,\"vanillaNsPerLookup\":%.1f,"
                    + "\"patchedNsPerLookup\":%.1f,\"speedup\":%.3f}", lookups, vanillaNs, patchedNs,
                    vanillaNs / patchedNs));
            long[] vanillaReal = (long[]) invoke(vanillaLoader, "benchReal", benchRounds);
            long[] patchedReal = (long[]) invoke(patchedLoader, "benchReal", benchRounds);
            double vanillaRealNs = Arrays.stream(vanillaReal, 1, vanillaReal.length).min().orElseThrow()
                    / (double) vanillaReal[0];
            double patchedRealNs = Arrays.stream(patchedReal, 1, patchedReal.length).min().orElseThrow()
                    / (double) vanillaReal[0];
            System.out.printf("CLIMATE_RTREE_BENCH_REAL lookups=%d vanilla=%.1fns patched=%.1fns speedup=%.2fx%n",
                    vanillaReal[0], vanillaRealNs, patchedRealNs, vanillaRealNs / patchedRealNs);
            json.append(String.format(",\"benchReal\":{\"lookups\":%d,\"vanillaNsPerLookup\":%.1f,"
                    + "\"patchedNsPerLookup\":%.1f,\"speedup\":%.3f}", vanillaReal[0], vanillaRealNs,
                    patchedRealNs, vanillaRealNs / patchedRealNs));
            long[] work = (long[]) invoke(vanillaLoader, "searchWork");
            System.out.printf("CLIMATE_RTREE_WORK lookups=%d vanillaMetricCalls=%.2f flatDistances=%.2f"
                    + " subtreeVisits=%.2f (per lookup)%n", work[0], work[1] / (double) work[0],
                    work[2] / (double) work[0], work[3] / (double) work[0]);
            json.append(String.format(",\"work\":{\"lookups\":%d,\"vanillaMetricCallsPerLookup\":%.3f,"
                    + "\"flatDistancesPerLookup\":%.3f,\"subtreeVisitsPerLookup\":%.3f}",
                    work[0], work[1] / (double) work[0], work[2] / (double) work[0],
                    work[3] / (double) work[0]));
        }
        if (survey > 0) {
            long[] found = (long[]) invoke(vanillaLoader, "survey", survey);
            System.out.printf("CLIMATE_RTREE_SURVEY chunks=%d lookups=%d nearestBoxContainsTarget=%.3f%%"
                    + " previousResultInsideBox=%.3f%% previousResultNearest=%.2f%%"
                    + " lookupsWithTiedLeaves=%d(%.4f%%) lookupsWithTiedBiomes=%d(%.4f%%)"
                    + " chunksWithTiedLeaves=%d(%.2f%%) chunksWithTiedBiomes=%d(%.2f%%)%n",
                    found[0], found[1], 100.0 * found[2] / found[1], 100.0 * found[3] / found[1],
                    100.0 * found[4] / found[1], found[5], 100.0 * found[5] / found[1], found[6],
                    100.0 * found[6] / found[1], found[7], 100.0 * found[7] / found[0], found[8],
                    100.0 * found[8] / found[0]);
        }
        json.append("}");
        System.out.println("CLIMATE_RTREE_JSON " + json);
        System.out.println("CLIMATE_RTREE_PARITY " + (pass ? "PASS" : "FAIL"));
        System.exit(pass ? 0 : 1);
    }

    private static ClassLoader loader(String harness, String classPath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(Path.of(harness).toUri().toURL());
        for (String entry : classPath.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(ClassLoader loader, String method, Object... args) throws Exception {
        Class<?> type = Class.forName(ClimateRTreeParity.class.getName(), true, loader);
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] instanceof Integer ? int.class : args[i].getClass();
        }
        Method target = type.getDeclaredMethod(method, types);
        target.setAccessible(true);
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(loader);
        try {
            return (T) target.invoke(null, args);
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    private static int firstDifference(int[] expected, int[] actual) {
        int length = Math.min(expected.length, actual.length);
        for (int i = 0; i < length; i++) {
            if (expected[i] != actual[i]) {
                return i;
            }
        }
        return expected.length == actual.length ? -1 : length;
    }

    private static String sha256(int[] ids) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int id : ids) {
            digest.update(new byte[] {(byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id});
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // ---------------------------------------------------------------- per class loader

    /** True when RTree.search of this class loader calls GaiusClimateIndex. */
    static Boolean flatIndexActive() throws Exception {
        try {
            Class.forName("net.minecraft.world.level.biome.GaiusClimateIndex", false,
                    ClimateRTreeParity.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            return false;
        }
        Class<?> subTree = Class.forName("net.minecraft.world.level.biome.Climate$RTree$SubTree");
        return Arrays.stream(subTree.getInterfaces())
                .anyMatch(type -> type.getSimpleName().equals("GaiusClimateSubTree"));
    }

    static Map<String, Object> cases(int scale) throws Exception {
        Map<String, Object> results = new LinkedHashMap<>();
        List<Climate.ParameterPoint> overworld = overworldPoints();
        results.put("overworld-chunks", singleThread(overworld, chunkTargets(new Random(1L), 200 * scale)));
        results.put("overworld-uniform", singleThread(overworld, uniformTargets(new Random(2L), 200_000 * scale, 30_000)));
        results.put("overworld-boundaries", singleThread(overworld, boundaryTargets(new Random(3L), overworld, 200_000 * scale)));
        results.put("overworld-threads", threads(overworld, new Random(4L), 3, 60 * scale));
        List<Climate.ParameterPoint> grid = gridPoints(new Random(5L), 600);
        results.put("grid-ties", singleThread(grid, gridTargets(new Random(6L), 200_000 * scale)));
        results.put("grid-ties-threads", threads(grid, new Random(7L), 4, 40 * scale));
        results.put("nether", singleThread(netherPoints(), uniformTargets(new Random(8L), 50_000 * scale, 15_000)));
        results.put("single-point", singleThread(List.of(Climate.parameters(0.1F, -0.2F, 0.3F, 0.0F, 0.0F, 0.5F, 0.0F)),
                uniformTargets(new Random(9L), 1_000, 20_000)));
        results.put("rebuilt-8-children", singleThreadRebuilt(overworld, 8, chunkTargets(new Random(10L), 40 * scale)));
        // The real overworld preset (7594 entries) with climate targets sampled from real noise
        // in generation order: this is where biome boundaries and their exact ties occur.
        results.put("overworld-real", singleThread(overworld, realTargets(REAL_SEEDS, 12 * scale)));
        List<Climate.ParameterPoint> overlap = overlapPoints(new Random(12L), 300);
        results.put("overlap-runs", singleThread(overlap, overlapTargets(new Random(13L), 200_000 * scale)));
        results.put("overlap-runs-threads", threads(overlap, new Random(14L), 3, 40 * scale));
        // Squared distances that wrap around in 64 bits (2^31 squared is 2^62): the early-exit
        // distance of GaiusClimateIndex must stay off there, with in-range and out-of-range bounds.
        results.put("wrap-narrow-bounds", singleThread(wrapPoints(new Random(15L), false),
                wrapTargets(new Random(16L), 200_000 * scale)));
        results.put("wrap-wide-bounds", singleThread(wrapPoints(new Random(17L), true),
                wrapTargets(new Random(18L), 200_000 * scale)));
        // Few leaves: all of them are children of the root, so none hides in a subtree whose box
        // distance would not wrap.
        results.put("wrap-flat-tree", singleThread(wrapPoints(new Random(19L), false).subList(0, 6),
                wrapTargets(new Random(20L), 200_000 * scale)));
        return results;
    }

    /** Nanoseconds of {@code rounds} timed passes over 600 overworld chunk fills; [0] = lookups per pass. */
    static long[] bench(int rounds) {
        List<Climate.ParameterPoint> overworld = overworldPoints();
        Climate.ParameterList<Integer> list = parameterList(overworld);
        Climate.TargetPoint[] targets = chunkTargets(new Random(11L), 600);
        long sink = 0;
        for (int warmup = 0; warmup < 3; warmup++) {
            for (Climate.TargetPoint target : targets) {
                sink += list.findValue(target);
            }
        }
        long[] nanos = new long[rounds + 1];
        nanos[0] = targets.length;
        for (int round = 1; round <= rounds; round++) {
            long started = System.nanoTime();
            for (Climate.TargetPoint target : targets) {
                sink += list.findValue(target);
            }
            nanos[round] = System.nanoTime() - started;
        }
        if (sink == 42) {
            System.out.println("unlikely");
        }
        return nanos;
    }

    /** Like {@link #bench} on the real preset and real sampled climate (24 chunks per seed, 3 seeds). */
    static long[] benchReal(int rounds) {
        Climate.ParameterList<Integer> list = parameterList(overworldPoints());
        Climate.TargetPoint[] targets = realTargets(REAL_SEEDS, 24);
        long sink = 0;
        for (int warmup = 0; warmup < 3; warmup++) {
            for (Climate.TargetPoint target : targets) {
                sink += list.findValue(target);
            }
        }
        long[] nanos = new long[rounds + 1];
        nanos[0] = targets.length;
        for (int round = 1; round <= rounds; round++) {
            long started = System.nanoTime();
            for (Climate.TargetPoint target : targets) {
                sink += list.findValue(target);
            }
            nanos[round] = System.nanoTime() - started;
        }
        if (sink == 42) {
            System.out.println("unlikely");
        }
        return nanos;
    }

    /**
     * Brute-force statistics of real overworld lookups ({@code chunksPerSeed} chunks of each of
     * three seeds, in {@code fillBiomesFromNoise} order) against the real preset: how often the
     * nearest box contains the target, how often the previous result is still nearest (the case
     * vanilla's warm start exists for), and how many lookups have several equally near leaves,
     * i.e. an answer that depends on the thread's previous result, with and without different
     * biomes among them. Returns {chunks, lookups, nearestBoxContains, previousInside,
     * previousNearest, tiedLeafLookups, tiedBiomeLookups, chunksWithTiedLeaves, chunksWithTiedBiomes}.
     */
    static long[] survey(int chunksPerSeed) throws Exception {
        List<Pair<Climate.ParameterPoint, Object>> entries = overworldEntries();
        int n = entries.size();
        long[][] lo = new long[n][7];
        long[][] hi = new long[n][7];
        int[] biome = new int[n];
        Map<Object, Integer> biomeIds = new java.util.HashMap<>();
        List<Climate.ParameterPoint> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Climate.ParameterPoint point = entries.get(i).getFirst();
            points.add(point);
            Climate.Parameter[] space = {point.temperature(), point.humidity(), point.continentalness(),
                point.erosion(), point.depth(), point.weirdness(), new Climate.Parameter(point.offset(), point.offset())};
            for (int d = 0; d < 7; d++) {
                lo[i][d] = space[d].min();
                hi[i][d] = space[d].max();
            }
            biome[i] = biomeIds.computeIfAbsent(entries.get(i).getSecond(), key -> biomeIds.size());
        }
        Climate.ParameterList<Integer> list = parameterList(points);
        Method toArray = Climate.TargetPoint.class.getDeclaredMethod("toParameterArray");
        toArray.setAccessible(true);
        long[] out = new long[9];
        for (long seed : REAL_SEEDS) {
            Climate.TargetPoint[] targets = realTargets(new long[] {seed}, chunksPerSeed);
            int previous = -1;
            boolean tiedLeaves = false;
            boolean tiedBiomes = false;
            for (int t = 0; t < targets.length; t++) {
                long[] v = (long[]) toArray.invoke(targets[t]);
                long best = Long.MAX_VALUE;
                int tied = 0;
                java.util.BitSet biomes = new java.util.BitSet();
                long previousDistance = -1;
                for (int i = 0; i < n; i++) {
                    long distance = 0;
                    for (int d = 0; d < 7; d++) {
                        long above = v[d] - hi[i][d];
                        long below = lo[i][d] - v[d];
                        long delta = above > 0 ? above : Math.max(below, 0);
                        distance += delta * delta;
                    }
                    if (i == previous) {
                        previousDistance = distance;
                    }
                    if (distance < best) {
                        best = distance;
                        tied = 1;
                        biomes.clear();
                        biomes.set(biome[i]);
                    } else if (distance == best) {
                        tied++;
                        biomes.set(biome[i]);
                    }
                }
                out[1]++;
                if (best == 0) {
                    out[2]++;
                }
                if (previous >= 0 && previousDistance == 0) {
                    out[3]++;
                }
                if (previous >= 0 && previousDistance == best) {
                    out[4]++;
                }
                tiedLeaves |= tied > 1;
                tiedBiomes |= biomes.cardinality() > 1;
                if (tied > 1) {
                    out[5]++;
                }
                if (biomes.cardinality() > 1) {
                    out[6]++;
                }
                previous = list.findValue(targets[t]);
                if ((t + 1) % (CHUNK_QUARTS_XZ * CHUNK_QUARTS_XZ * CHUNK_QUARTS_Y) == 0) {
                    out[0]++;
                    out[7] += tiedLeaves ? 1 : 0;
                    out[8] += tiedBiomes ? 1 : 0;
                    tiedLeaves = false;
                    tiedBiomes = false;
                }
            }
        }
        return out;
    }

    /**
     * Counts, on the chunk-fill workload, the DistanceMetric calls vanilla's recursive search makes
     * and the distances the flat search computes. Both walks are transcribed from the vanilla
     * bytecode over the real tree (read reflectively); this is reporting, not the parity proof.
     */
    static long[] searchWork() throws Exception {
        Climate.ParameterList<Integer> list = parameterList(overworldPoints());
        Field indexField = Climate.ParameterList.class.getDeclaredField("index");
        indexField.setAccessible(true);
        Object tree = indexField.get(list);
        Field rootField = tree.getClass().getDeclaredField("root");
        rootField.setAccessible(true);
        WorkNode root = WorkNode.of(rootField.get(tree));
        Climate.TargetPoint[] targets = chunkTargets(new Random(11L), 600);
        long[] work = new long[4];
        WorkNode last = null;
        Method toArray = Climate.TargetPoint.class.getDeclaredMethod("toParameterArray");
        toArray.setAccessible(true);
        for (Climate.TargetPoint target : targets) {
            long[] values = (long[]) toArray.invoke(target);
            work[0]++;
            last = root.vanillaSearch(values, last, work);
        }
        return work;
    }

    private static final class WorkNode {
        final long[] bounds = new long[14];
        WorkNode[] children;

        static WorkNode of(Object node) throws Exception {
            WorkNode work = new WorkNode();
            Field space = Climate.class.getClassLoader()
                    .loadClass("net.minecraft.world.level.biome.Climate$RTree$Node")
                    .getDeclaredField("parameterSpace");
            space.setAccessible(true);
            Climate.Parameter[] parameters = (Climate.Parameter[]) space.get(node);
            for (int i = 0; i < 7; i++) {
                work.bounds[2 * i] = parameters[i].min();
                work.bounds[2 * i + 1] = parameters[i].max();
            }
            if (node.getClass().getSimpleName().equals("SubTree")) {
                Field childrenField = node.getClass().getDeclaredField("children");
                childrenField.setAccessible(true);
                Object[] children = (Object[]) childrenField.get(node);
                work.children = new WorkNode[children.length];
                for (int i = 0; i < children.length; i++) {
                    work.children[i] = of(children[i]);
                }
            }
            return work;
        }

        long distance(long[] values) {
            long sum = 0;
            for (int i = 0; i < 7; i++) {
                long above = values[i] - bounds[2 * i + 1];
                long below = bounds[2 * i] - values[i];
                long d = above > 0 ? above : Math.max(below, 0);
                sum += d * d;
            }
            return sum;
        }

        /**
         * Vanilla SubTree.search transcribed; work[1] counts its DistanceMetric calls, work[2] the
         * distances the flat search computes for the same walk (each examined child, plus the
         * candidate once per lookup), work[3] the subtrees visited.
         */
        WorkNode vanillaSearch(long[] values, WorkNode candidate, long[] work) {
            if (children == null) {
                return this;
            }
            if (candidate != null) {
                work[2]++;
            }
            return search(values, candidate, work);
        }

        private WorkNode search(long[] values, WorkNode candidate, long[] work) {
            work[3]++;
            if (candidate != null) {
                work[1]++;
            }
            long min = candidate == null ? Long.MAX_VALUE : candidate.distance(values);
            WorkNode best = candidate;
            for (WorkNode child : children) {
                work[1]++;
                work[2]++;
                long d = child.distance(values);
                if (min <= d) {
                    continue;
                }
                WorkNode leaf = child.children == null ? child : child.search(values, best, work);
                long ld;
                if (leaf == child) {
                    ld = d;
                } else {
                    work[1]++;
                    ld = leaf.distance(values);
                }
                if (min <= ld) {
                    continue;
                }
                min = ld;
                best = leaf;
            }
            return best;
        }
    }

    // ---------------------------------------------------------------- runs

    private static Object[] singleThread(List<Climate.ParameterPoint> points, Climate.TargetPoint[] targets)
            throws Exception {
        return singleThreadList(parameterList(points), parameterList(points), targets);
    }

    private static Object[] singleThreadRebuilt(
            List<Climate.ParameterPoint> points, int children, Climate.TargetPoint[] targets) throws Exception {
        return singleThreadList(parameterList(points).rebuildWithChildrenPerNode(children),
                parameterList(points).rebuildWithChildrenPerNode(children), targets);
    }

    private static Object[] singleThreadList(
            Climate.ParameterList<Integer> list, Climate.ParameterList<Integer> fresh,
            Climate.TargetPoint[] targets) throws Exception {
        ThreadLocal<?> freshLastResult = lastResult(fresh);
        int[] ids = new int[targets.length];
        long history = 0;
        for (int i = 0; i < targets.length; i++) {
            ids[i] = list.findValue(targets[i]);
            freshLastResult.remove();
            if (fresh.findValue(targets[i]) != ids[i]) {
                history++;
            }
        }
        return new Object[] {ids, history};
    }

    /**
     * Several Java threads share one list; the main thread hands each a batch in a seeded order and
     * waits for it, so the global lookup order (and each thread's own history) is fixed.
     */
    private static Object[] threads(List<Climate.ParameterPoint> points, Random random, int threadCount, int chunks)
            throws Exception {
        Climate.ParameterList<Integer> list = parameterList(points);
        List<Integer> ids = new ArrayList<>();
        List<BlockingQueue<Runnable>> inboxes = new ArrayList<>();
        BlockingQueue<Boolean> done = new ArrayBlockingQueue<>(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            BlockingQueue<Runnable> inbox = new ArrayBlockingQueue<>(1);
            inboxes.add(inbox);
            Thread worker = new Thread(() -> {
                try {
                    while (true) {
                        Runnable task = inbox.take();
                        if (task == STOP) {
                            return;
                        }
                        task.run();
                        done.put(Boolean.TRUE);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "climate-parity-" + t);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
        Climate.TargetPoint[] targets = chunkTargets(random, chunks);
        int position = 0;
        while (position < targets.length) {
            int thread = random.nextInt(threadCount);
            int batch = 1 + random.nextInt(random.nextBoolean() ? 8 : 700);
            int from = position;
            int to = Math.min(targets.length, position + batch);
            inboxes.get(thread).put(() -> {
                for (int i = from; i < to; i++) {
                    ids.add(list.findValue(targets[i]));
                }
            });
            done.take();
            position = to;
        }
        for (BlockingQueue<Runnable> inbox : inboxes) {
            inbox.put(STOP);
        }
        for (Thread worker : workers) {
            worker.join();
        }
        return new Object[] {ids.stream().mapToInt(Integer::intValue).toArray(), 0L};
    }

    private static final Runnable STOP = () -> { };

    private static ThreadLocal<?> lastResult(Climate.ParameterList<?> list) throws Exception {
        Field indexField = Climate.ParameterList.class.getDeclaredField("index");
        indexField.setAccessible(true);
        Object tree = indexField.get(list);
        Field lastResultField = tree.getClass().getDeclaredField("lastResult");
        lastResultField.setAccessible(true);
        return (ThreadLocal<?>) lastResultField.get(tree);
    }

    // ---------------------------------------------------------------- inputs

    private static Climate.ParameterList<Integer> parameterList(List<Climate.ParameterPoint> points) {
        List<Pair<Climate.ParameterPoint, Integer>> values = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            values.add(Pair.of(points.get(i), i));
        }
        return new Climate.ParameterList<>(values);
    }

    private static List<Climate.ParameterPoint> overworldPoints() {
        List<Climate.ParameterPoint> points = new ArrayList<>();
        for (Pair<Climate.ParameterPoint, Object> entry : overworldEntries()) {
            points.add(entry.getFirst());
        }
        return points;
    }

    /** The overworld preset's entries (point and biome key), in preset order. */
    @SuppressWarnings("unchecked")
    private static List<Pair<Climate.ParameterPoint, Object>> overworldEntries() {
        try {
            List<Pair<Climate.ParameterPoint, Object>> entries = new ArrayList<>();
            Method addBiomes = OverworldBiomeBuilder.class.getDeclaredMethod("addBiomes", Consumer.class);
            addBiomes.setAccessible(true);
            addBiomes.invoke(new OverworldBiomeBuilder(),
                    (Consumer<Pair<Climate.ParameterPoint, Object>>) entries::add);
            return entries;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static List<Climate.ParameterPoint> netherPoints() {
        return List.of(
                Climate.parameters(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                Climate.parameters(0.0F, -0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                Climate.parameters(0.4F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                Climate.parameters(0.0F, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.375F),
                Climate.parameters(-0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.175F));
    }

    /** Boxes on a coarse grid: many exact ties and nested boxes. */
    private static List<Climate.ParameterPoint> gridPoints(Random random, int count) {
        List<Climate.ParameterPoint> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Climate.Parameter[] parameters = new Climate.Parameter[6];
            for (int d = 0; d < 6; d++) {
                float a = (random.nextInt(9) - 4) * 0.25F;
                float b = a + random.nextInt(3) * 0.25F;
                parameters[d] = Climate.Parameter.span(a, b);
            }
            points.add(Climate.parameters(parameters[0], parameters[1], parameters[2], parameters[3],
                    parameters[4], parameters[5], random.nextInt(3) * 0.25F));
        }
        return points;
    }

    private static Climate.TargetPoint[] gridTargets(Random random, int count) {
        Climate.TargetPoint[] targets = new Climate.TargetPoint[count];
        for (int i = 0; i < count; i++) {
            long[] v = new long[6];
            for (int d = 0; d < 6; d++) {
                v[d] = (random.nextInt(21) - 10) * 1250L + (random.nextInt(4) == 0 ? random.nextInt(3) - 1 : 0);
            }
            targets[i] = new Climate.TargetPoint(v[0], v[1], v[2], v[3], v[4], v[5]);
        }
        return targets;
    }

    private static final long[] REAL_SEEDS = {12345L, 987654321L, 20261112L};

    /**
     * Climate targets of real overworld noise (the vanilla sampler), for {@code chunksPerSeed}
     * chunks per seed, in the lookup order of {@code fillBiomesFromNoise}: sections bottom-up, x,
     * y, z quarts within them. Chunks of a seed are adjacent (row-major around the origin). The
     * sampler API differs between generations, so it is reached by reflection; the preset points
     * are those of {@link #overworldPoints()}.
     */
    private static Climate.TargetPoint[] realTargets(long[] seeds, int chunksPerSeed) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            Class<?> vanillaRegistries = Class.forName("net.minecraft.data.registries.VanillaRegistries");
            HolderLookup.Provider lookup = null;
            for (String factory : new String[] {"createWorldLookup", "createLookup"}) {
                try {
                    lookup = (HolderLookup.Provider) vanillaRegistries.getMethod(factory).invoke(null);
                    break;
                } catch (NoSuchMethodException next) {
                    // The other generation's name.
                }
            }
            if (lookup == null) {
                throw new IllegalStateException("no VanillaRegistries lookup factory");
            }
            NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
            Object noises = lookup.lookupOrThrow(Registries.NOISE);
            Class<?> randomState = Class.forName("net.minecraft.world.level.levelgen.RandomState");
            int side = (int) Math.ceil(Math.sqrt(chunksPerSeed));
            List<Climate.TargetPoint> targets = new ArrayList<>();
            for (long seed : seeds) {
                Climate.Sampler sampler = climateSampler(randomState, createRandomState(randomState, seed, settings, noises));
                for (int chunk = 0; chunk < chunksPerSeed; chunk++) {
                    int chunkX = chunk % side - side / 2;
                    int chunkZ = chunk / side - side / 2;
                    for (int section = 0; section < CHUNK_QUARTS_Y / 4; section++) {
                        for (int qx = 0; qx < 4; qx++) {
                            for (int qy = 0; qy < 4; qy++) {
                                for (int qz = 0; qz < 4; qz++) {
                                    targets.add(sampler.sample(
                                            chunkX * 4 + qx, -16 + section * 4 + qy, chunkZ * 4 + qz));
                                }
                            }
                        }
                    }
                }
            }
            return targets.toArray(Climate.TargetPoint[]::new);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("cannot sample real climate on this generation", failure);
        }
    }

    /** The 3-argument {@code RandomState.create} of the generation, arguments matched by type. */
    private static Object createRandomState(Class<?> randomState, long seed, Object settings, Object noises)
            throws ReflectiveOperationException {
        for (Method method : randomState.getMethods()) {
            if (!method.getName().equals("create") || method.getParameterCount() != 3
                    || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            Object[] arguments = new Object[3];
            boolean usable = true;
            for (int i = 0; i < 3 && usable; i++) {
                if (types[i] == long.class) {
                    arguments[i] = seed;
                } else if (types[i].isInstance(settings)) {
                    arguments[i] = settings;
                } else if (types[i].isInstance(noises) && types[i] == net.minecraft.core.HolderGetter.class) {
                    arguments[i] = noises;
                } else {
                    usable = false;
                }
            }
            if (usable) {
                return method.invoke(null, arguments);
            }
        }
        throw new NoSuchMethodException("RandomState.create(settings, noises, seed)");
    }

    /** {@code RandomState.sampler()}, or {@code createClimateSampler(SamplerContext with caches)}. */
    private static Climate.Sampler climateSampler(Class<?> randomState, Object state)
            throws ReflectiveOperationException {
        try {
            return (Climate.Sampler) randomState.getMethod("sampler").invoke(state);
        } catch (NoSuchMethodException newer) {
            Class<?> contextType = Class.forName("net.minecraft.world.level.levelgen.densityfunction.SamplerContext");
            Object builder = contextType.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("enableCaches").invoke(builder);
            Object context = builder.getClass().getMethod("build").invoke(builder);
            return (Climate.Sampler) randomState.getMethod("createClimateSampler", contextType).invoke(state, context);
        }
    }

    /** Large overlapping boxes: most targets lie inside several of them (zero-distance ties). */
    private static List<Climate.ParameterPoint> overlapPoints(Random random, int count) {
        List<Climate.ParameterPoint> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Climate.Parameter[] parameters = new Climate.Parameter[6];
            for (int d = 0; d < 6; d++) {
                float a = (random.nextInt(9) - 4) * 0.25F;
                parameters[d] = Climate.Parameter.span(a, a + (1 + random.nextInt(5)) * 0.25F);
            }
            points.add(Climate.parameters(parameters[0], parameters[1], parameters[2], parameters[3],
                    parameters[4], parameters[5], random.nextInt(3) == 0 ? 0.25F : 0.0F));
        }
        return points;
    }

    /**
     * Runs of nearby targets (so the previous result is often at distance zero) with jumps and
     * coordinates on box edges (so it is sometimes just outside).
     */
    private static Climate.TargetPoint[] overlapTargets(Random random, int count) {
        Climate.TargetPoint[] targets = new Climate.TargetPoint[count];
        long[] v = new long[6];
        for (int i = 0; i < count; i++) {
            if (i % 37 == 0) {
                for (int d = 0; d < 6; d++) {
                    v[d] = (random.nextInt(21) - 10) * 1250L;
                }
            } else {
                int d = random.nextInt(6);
                v[d] += random.nextInt(3) == 0 ? (random.nextInt(5) - 2) * 1250L : random.nextInt(7) - 3;
            }
            targets[i] = new Climate.TargetPoint(v[0], v[1], v[2], v[3], v[4], v[5]);
        }
        return targets;
    }

    /**
     * Boxes at and around the origin (some with bounds beyond the early exit's range when
     * {@code wide}), so that targets around 2^31 give the previous result a wrapped distance of
     * zero (four squares of 2^62) while another leaf's wrapped distance is negative.
     */
    private static List<Climate.ParameterPoint> wrapPoints(Random random, boolean wide) {
        List<Climate.ParameterPoint> points = new ArrayList<>();
        points.add(rawPoint(new long[] {0, 0, 0, 0, 0, 0}, new long[] {0, 0, 0, 0, 0, 0}, 0L));
        points.add(rawPoint(new long[] {0, 0, 0, 1L << 28, 0, 0}, new long[] {0, 0, 0, 1L << 28, 0, 0}, 0L));
        points.add(rawPoint(new long[] {0, 0, 0, 0, 0, 0}, new long[] {0, 0, 0, 0, 0, 0}, 1L << 27));
        for (int i = 0; i < 60; i++) {
            long[] min = new long[6];
            long[] max = new long[6];
            for (int d = 0; d < 6; d++) {
                long a = (random.nextInt(2001) - 1000) * 100L;
                min[d] = a;
                max[d] = a + random.nextInt(4) * 5000L;
            }
            // Away from the origin, so that the origin target has the first leaf as its only nearest.
            min[0] = 30_000L + (i % 7) * 4000L;
            max[0] = min[0] + 3000L;
            points.add(rawPoint(min, max, random.nextInt(3) * 1000L));
        }
        if (wide) {
            points.add(rawPoint(new long[] {-(1L << 40), 0, 0, 0, 0, 0}, new long[] {1L << 40, 0, 0, 0, 0, 0}, 0L));
            points.add(rawPoint(new long[] {0, 0, 0, Long.MIN_VALUE, 0, 0},
                    new long[] {0, 0, 0, Long.MAX_VALUE, 0, 0}, 0L));
            points.add(rawPoint(new long[] {1L << 31, 1L << 31, 1L << 31, 0, 0, 0},
                    new long[] {1L << 31, 1L << 31, 1L << 31, 0, 0, 0}, 0L));
        }
        return points;
    }

    private static Climate.ParameterPoint rawPoint(long[] min, long[] max, long offset) {
        return new Climate.ParameterPoint(
                new Climate.Parameter(min[0], max[0]), new Climate.Parameter(min[1], max[1]),
                new Climate.Parameter(min[2], max[2]), new Climate.Parameter(min[3], max[3]),
                new Climate.Parameter(min[4], max[4]), new Climate.Parameter(min[5], max[5]), offset);
    }

    /**
     * Every other target is the origin (the first leaf is its only nearest, so it becomes the
     * previous result); in between come targets with two to six coordinates at plus or minus 2^31
     * (the square wraps the 64-bit sum to zero for four of them, to a negative value for three)
     * and targets around the early exit's range limit.
     */
    private static Climate.TargetPoint[] wrapTargets(Random random, int count) {
        long[] interesting = {0, 1, -1, 1L << 28, -(1L << 28), (1L << 28) + 1, -(1L << 28) - 1, 1L << 29,
            1L << 30, 1L << 31, -(1L << 31), (1L << 31) + 1, 1L << 32, -(1L << 32), 1L << 40, 1L << 62,
            Long.MAX_VALUE, Long.MIN_VALUE, 3037000500L, 3037000499L};
        Climate.TargetPoint[] targets = new Climate.TargetPoint[count];
        for (int i = 0; i < count; i++) {
            if ((i & 1) == 0) {
                targets[i] = new Climate.TargetPoint(0, 0, 0, 0, 0, 0);
                continue;
            }
            long[] v = new long[6];
            int kind = random.nextInt(3);
            if (kind == 0) {
                int wrapped = 2 + random.nextInt(5);
                for (int placed = 0; placed < wrapped; ) {
                    int d = random.nextInt(6);
                    if (v[d] == 0) {
                        v[d] = random.nextBoolean() ? 1L << 31 : -(1L << 31);
                        placed++;
                    }
                }
            } else if (kind == 1) {
                for (int d = 0; d < 6; d++) {
                    v[d] = interesting[random.nextInt(interesting.length)];
                }
            } else {
                for (int d = 0; d < 6; d++) {
                    v[d] = random.nextBoolean() ? 0 : (random.nextInt(2001) - 1000) * 100L;
                }
            }
            targets[i] = new Climate.TargetPoint(v[0], v[1], v[2], v[3], v[4], v[5]);
        }
        return targets;
    }

    private static Climate.TargetPoint[] uniformTargets(Random random, int count, int range) {
        Climate.TargetPoint[] targets = new Climate.TargetPoint[count];
        for (int i = 0; i < count; i++) {
            targets[i] = new Climate.TargetPoint(
                    random.nextInt(2 * range + 1) - range, random.nextInt(2 * range + 1) - range,
                    random.nextInt(2 * range + 1) - range, random.nextInt(2 * range + 1) - range,
                    random.nextInt(2 * range + 1) - range, random.nextInt(2 * range + 1) - range);
        }
        return targets;
    }

    /** Coordinates drawn from the parameter bounds of the list (and their neighbours): tie-heavy. */
    private static Climate.TargetPoint[] boundaryTargets(Random random, List<Climate.ParameterPoint> points, int count) {
        List<long[]> perDimension = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            TreeSet<Long> values = new TreeSet<>();
            for (Climate.ParameterPoint point : points) {
                Climate.Parameter parameter = switch (d) {
                    case 0 -> point.temperature();
                    case 1 -> point.humidity();
                    case 2 -> point.continentalness();
                    case 3 -> point.erosion();
                    case 4 -> point.depth();
                    default -> point.weirdness();
                };
                for (long base : new long[] {parameter.min(), parameter.max()}) {
                    values.add(base - 1);
                    values.add(base);
                    values.add(base + 1);
                }
            }
            perDimension.add(values.stream().mapToLong(Long::longValue).toArray());
        }
        Climate.TargetPoint[] targets = new Climate.TargetPoint[count];
        for (int i = 0; i < count; i++) {
            long[] v = new long[6];
            for (int d = 0; d < 6; d++) {
                long[] options = perDimension.get(d);
                v[d] = options[random.nextInt(options.length)];
            }
            targets[i] = new Climate.TargetPoint(v[0], v[1], v[2], v[3], v[4], v[5]);
        }
        return targets;
    }

    /**
     * Lookups in the order LevelChunkSection.fillBiomesFromNoise makes them (x, then y, then z
     * quarts of each section, sections bottom-up) over smooth climate fields, so neighbouring
     * lookups mostly hit the same or an adjacent leaf, as in real generation.
     */
    private static Climate.TargetPoint[] chunkTargets(Random random, int chunks) {
        double[][] phases = new double[6][6];
        for (double[] row : phases) {
            for (int i = 0; i < row.length; i++) {
                row[i] = random.nextDouble() * Math.PI * 2;
            }
        }
        Climate.TargetPoint[] targets = new Climate.TargetPoint[chunks * CHUNK_QUARTS_XZ * CHUNK_QUARTS_XZ * CHUNK_QUARTS_Y];
        int n = 0;
        for (int chunk = 0; chunk < chunks; chunk++) {
            int chunkX = random.nextInt(2000) - 1000;
            int chunkZ = random.nextInt(2000) - 1000;
            for (int section = 0; section < CHUNK_QUARTS_Y / 4; section++) {
                for (int qx = 0; qx < 4; qx++) {
                    for (int qy = 0; qy < 4; qy++) {
                        for (int qz = 0; qz < 4; qz++) {
                            double x = (chunkX * 4 + qx) / 64.0;
                            double z = (chunkZ * 4 + qz) / 64.0;
                            int y = section * 4 + qy;
                            float temperature = field(phases[0], x, z, 1.0);
                            float humidity = field(phases[1], x, z, 1.0);
                            float continentalness = field(phases[2], x, z, 1.15);
                            float erosion = field(phases[3], x, z, 1.0);
                            float weirdness = field(phases[5], x, z, 1.0);
                            float depth = (float) (0.2 * field(phases[4], x, z, 1.0) - (y - 70) / 32.0);
                            targets[n++] = Climate.target(temperature, humidity, continentalness, erosion, depth, weirdness);
                        }
                    }
                }
            }
        }
        return targets;
    }

    private static float field(double[] phase, double x, double z, double amplitude) {
        double value = 0.55 * Math.sin(x * 0.9 + phase[0]) * Math.cos(z * 0.7 + phase[1])
                + 0.3 * Math.sin(x * 2.3 + z * 1.7 + phase[2])
                + 0.15 * Math.sin(x * 5.1 - z * 4.3 + phase[3]);
        return (float) (amplitude * value);
    }
}
