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
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;

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
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
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
            long[] work = (long[]) invoke(vanillaLoader, "searchWork");
            System.out.printf("CLIMATE_RTREE_WORK lookups=%d vanillaMetricCalls=%.2f flatDistances=%.2f"
                    + " subtreeVisits=%.2f (per lookup)%n", work[0], work[1] / (double) work[0],
                    work[2] / (double) work[0], work[3] / (double) work[0]);
            json.append(String.format(",\"work\":{\"lookups\":%d,\"vanillaMetricCallsPerLookup\":%.3f,"
                    + "\"flatDistancesPerLookup\":%.3f,\"subtreeVisitsPerLookup\":%.3f}",
                    work[0], work[1] / (double) work[0], work[2] / (double) work[0],
                    work[3] / (double) work[0]));
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

    @SuppressWarnings("unchecked")
    private static List<Climate.ParameterPoint> overworldPoints() {
        try {
            List<Climate.ParameterPoint> points = new ArrayList<>();
            Method addBiomes = OverworldBiomeBuilder.class.getDeclaredMethod("addBiomes", Consumer.class);
            addBiomes.setAccessible(true);
            addBiomes.invoke(new OverworldBiomeBuilder(),
                    (Consumer<Pair<Climate.ParameterPoint, ?>>) pair -> points.add(pair.getFirst()));
            return points;
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
