package dev.gaius.wgperf;

import com.google.gson.GsonBuilder;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public final class StatusProfiler {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean PROCESS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final ThreadLocal<Frame> TOP = new ThreadLocal<>();
    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, String> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, String> FAILURES = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> PLACED = new ConcurrentHashMap<>();
    private static final ClassValue<String> FEATURE_BUCKET = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            String name = type.getSimpleName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("ore")) return "features_ore";
            if (name.contains("tree")) return "features_tree";
            return !name.contains("sculk") && name.matches(
                    ".*(vegetation|flower|grass|patch|mushroom|bamboo|root|vines|seagrass|kelp).*")
                    ? "features_vegetation" : "features_other";
        }
    };
    private static final LongAdder OPEN = new LongAdder();
    private static final ClassValue<java.util.Optional<java.lang.reflect.Method>> FEATURE_ACCESSOR = new ClassValue<>() {
        @Override
        protected java.util.Optional<java.lang.reflect.Method> computeValue(Class<?> type) {
            try { return java.util.Optional.of(type.getMethod("feature")); }
            catch (NoSuchMethodException expected) { return java.util.Optional.empty(); }
        }
    };
    private static volatile boolean active;
    private static long processStart;
    private static long processNanos;
    private static long wallStart;
    private static long wallNanos;
    private static int chunks;

    public static final class Frame {
        final String key;
        final String feature;
        final Frame parent;
        final long cpu;
        final long wall;
        long childCpu;
        long childWall;

        Frame(String key, String feature, Frame parent) {
            this.key = key;
            this.feature = feature;
            this.parent = parent;
            cpu = THREADS.getCurrentThreadCpuTime();
            wall = System.nanoTime();
        }
    }

    private static final class Counter {
        final LongAdder calls = new LongAdder();
        final LongAdder cpu = new LongAdder();
        final LongAdder wall = new LongAdder();
    }

    public static void instrumented(String method, String key) {
        METHODS.put(method, key);
    }

    public static void failed(String owner, Throwable error) {
        FAILURES.put(owner, error.toString());
    }

    public static Frame enter(String key, Object placed) {
        if (!active) return null;
        String feature = null;
        if (placed != null) {
            try {
                Object value = placed instanceof PlacedFeature placement ? placement.feature().value() : placed;
                var accessor = FEATURE_ACCESSOR.get(value.getClass());
                if (accessor.isPresent()) value = accessor.get().invoke(value);
                feature = value.getClass().getSimpleName();
                key = FEATURE_BUCKET.get(value.getClass());
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot classify placed feature", error);
            }
        }
        Frame frame = new Frame(key, feature, TOP.get());
        TOP.set(frame);
        OPEN.increment();
        return frame;
    }

    public static void exit(Frame frame) {
        if (frame == null) return;
        long cpu = THREADS.getCurrentThreadCpuTime() - frame.cpu;
        long wall = System.nanoTime() - frame.wall;
        while (TOP.get() != frame) {
            Frame nested = TOP.get();
            if (nested == null) throw new IllegalStateException("Profiler stack mismatch");
            FAILURES.put("exceptional-unwind", "Implicit exception escaped an instrumented child");
            exit(nested);
        }
        TOP.set(frame.parent);
        OPEN.decrement();
        if (frame.parent != null) {
            frame.parent.childCpu += cpu;
            frame.parent.childWall += wall;
        }
        Counter counter = COUNTERS.computeIfAbsent(frame.key, ignored -> new Counter());
        counter.calls.increment();
        counter.cpu.add(cpu - frame.childCpu);
        counter.wall.add(wall - frame.childWall);
        if (frame.feature != null) PLACED.computeIfAbsent(frame.feature, ignored -> new LongAdder())
                .add(cpu - frame.childCpu);
    }

    public static void beginChunk() {
        if (!THREADS.isThreadCpuTimeSupported()) throw new IllegalStateException("Thread CPU clock unavailable");
        if (!THREADS.isThreadCpuTimeEnabled()) THREADS.setThreadCpuTimeEnabled(true);
        if (chunks == 0) {
            KernelHost.resetMeasurement();
            OreShadow.resetMeasurement();
        }
        processStart = PROCESS.getProcessCpuTime();
        wallStart = System.nanoTime();
        active = true;
    }

    public static void endChunk() {
        active = false;
        wallNanos += System.nanoTime() - wallStart;
        processNanos += PROCESS.getProcessCpuTime() - processStart;
        chunks++;
    }

    public static void write(String path) throws Exception {
        if (!FAILURES.isEmpty()) throw new IllegalStateException("Instrumentation failed: " + FAILURES);
        if (OPEN.sum() != 0) throw new IllegalStateException("Unclosed profiler frames: " + OPEN.sum());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("chunks", chunks);
        report.put("generationWallNanos", wallNanos);
        report.put("processCpuNanos", processNanos);
        report.put("kernels", System.getProperty("gaius.perf.kernels", "off"));
        report.put("kernelHost", KernelHost.report());
        if (!System.getProperty("gaius.perf.ore", "off").equals("off")) report.put("ore", OreShadow.report());
        Map<String, Object> statuses = new TreeMap<>();
        long tracked = 0;
        for (var entry : COUNTERS.entrySet()) {
            Counter counter = entry.getValue();
            long cpu = counter.cpu.sum();
            tracked += cpu;
            statuses.put(entry.getKey(), Map.of("calls", counter.calls.sum(), "selfCpuNanos", cpu,
                    "selfWallNanos", counter.wall.sum()));
        }
        report.put("trackedCpuNanos", tracked);
        report.put("untrackedCpuNanos", processNanos - tracked);
        report.put("statuses", statuses);
        report.put("instrumentedMethods", new TreeMap<>(METHODS));
        Map<String, Long> features = new TreeMap<>();
        PLACED.forEach((name, counter) -> features.put(name, counter.sum()));
        report.put("placedFeatureCpuNanos", features);
        Files.writeString(Path.of(path), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
    }
}
