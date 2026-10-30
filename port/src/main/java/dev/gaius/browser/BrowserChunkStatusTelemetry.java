package dev.gaius.browser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Optional, allocation-free-at-callsite status timing helper for JVM diagnostics. */
public final class BrowserChunkStatusTelemetry {
    private static volatile boolean enabled;
    private static final Map<String, Bucket> buckets = new LinkedHashMap<>();

    private BrowserChunkStatusTelemetry() {}

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) buckets.clear();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static String bucket(String label) {
        String name = String.valueOf(label).toUpperCase(Locale.ROOT);
        if (name.contains("STRUCTURE")) return "STRUCTURE_STARTS";
        if (name.contains("BIOME")) return "BIOMES";
        if (name.contains("NOISE") || name.contains("TERRAIN")) return "NOISE";
        if (name.contains("SURFACE")) return "SURFACE";
        if (name.contains("CARVER")) return "CARVERS";
        if (name.contains("FEATURE")) return "FEATURES";
        if (name.contains("LIGHT")) return "LIGHT";
        if (name.contains("SPAWN")) return "SPAWN";
        if (name.contains("FULL")) return "FULL";
        return "OTHER";
    }

    public static void record(String label, long wallNanos, long elapsedNanos) {
        if (!enabled) return;
        String key = bucket(label);
        Bucket value = buckets.computeIfAbsent(key, ignored -> new Bucket());
        value.count++;
        value.wallNanos += Math.max(0L, wallNanos);
        value.elapsedNanos += Math.max(0L, elapsedNanos);
    }

    public static Map<String, Bucket> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(buckets));
    }

    public static final class Bucket {
        public long count;
        public long wallNanos;
        public long elapsedNanos;
    }
}
