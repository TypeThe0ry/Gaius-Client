package dev.gaius.tools;

import dev.gaius.browser.BrowserChunkStatusTelemetry;
import java.util.Map;

/** JVM-level contract test for status classification and the disabled fast path. */
public final class BrowserChunkStatusTelemetryTest {
    public static void main(String[] args) {
        BrowserChunkStatusTelemetry.setEnabled(false);
        BrowserChunkStatusTelemetry.record("features", 10, 7);
        require(BrowserChunkStatusTelemetry.snapshot().isEmpty(), "disabled telemetry recorded data");

        require("STRUCTURE_STARTS".equals(BrowserChunkStatusTelemetry.bucket("structure_starts")), "structure bucket");
        require("BIOMES".equals(BrowserChunkStatusTelemetry.bucket("biomes")), "biome bucket");
        require("NOISE".equals(BrowserChunkStatusTelemetry.bucket("noise")), "noise bucket");
        require("SURFACE".equals(BrowserChunkStatusTelemetry.bucket("surface")), "surface bucket");
        require("CARVERS".equals(BrowserChunkStatusTelemetry.bucket("carvers")), "carver bucket");
        require("FEATURES".equals(BrowserChunkStatusTelemetry.bucket("features")), "feature bucket");
        require("LIGHT".equals(BrowserChunkStatusTelemetry.bucket("light")), "light bucket");
        require("SPAWN".equals(BrowserChunkStatusTelemetry.bucket("spawn")), "spawn bucket");
        require("FULL".equals(BrowserChunkStatusTelemetry.bucket("full")), "full bucket");

        BrowserChunkStatusTelemetry.setEnabled(true);
        String[] labels = {
                "structure_starts", "biomes", "noise", "surface", "carvers",
                "features", "light", "spawn", "full"
        };
        for (String label : labels) {
            BrowserChunkStatusTelemetry.record(label, 10, 7);
        }
        Map<String, BrowserChunkStatusTelemetry.Bucket> buckets = BrowserChunkStatusTelemetry.snapshot();
        require(buckets.size() == labels.length, "expected all nine stage buckets");
        for (String label : labels) {
            BrowserChunkStatusTelemetry.Bucket bucket = buckets.get(BrowserChunkStatusTelemetry.bucket(label));
            require(bucket != null && bucket.count == 1 && bucket.wallNanos == 10 && bucket.elapsedNanos == 7,
                    "enabled bucket contents for " + label);
        }
        BrowserChunkStatusTelemetry.setEnabled(false);
        require(BrowserChunkStatusTelemetry.snapshot().isEmpty(), "disable did not clear buckets");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
