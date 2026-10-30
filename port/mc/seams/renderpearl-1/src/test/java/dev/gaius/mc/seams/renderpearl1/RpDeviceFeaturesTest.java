package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.device.DeviceFeatures;
import java.util.Map;
import java.util.Set;

/**
 * JVM checks that the advertised 26.3 DeviceFeatures/limit never form a combination vanilla
 * rejects (run by {@code port/mc/seams/test-seam.sh renderpearl-1}).  The capability sets are the
 * ones gpu-capabilities-smoke.mjs asserts for the glue: a device with indirect-first-instance,
 * one without it, a glue missing the indirect imports, and an older glue that reported
 * multi-draw-indirect on its own.
 * <p>
 * The vanilla rules mirrored here come from the clean 26.3 jar (javap):
 * LevelRenderer picks indirect terrain when maxDrawIndirectDrawCount > 0 and nonZeroFirstInstance
 * holds, ChunkSectionsToRender$DrawIndirect then calls drawIndexedIndirect with up to that many
 * records, and FrontendRenderPass.drawIndexedIndirect throws without drawIndirect, and for
 * drawCount > 1 without multiDrawIndirect.
 * Exits with status 1 on any failure.
 */
public final class RpDeviceFeaturesTest {
    private static int failures;

    private RpDeviceFeaturesTest() {
    }

    public static void main(String[] args) {
        Map<String, Set<String>> scenarios = Map.of(
                "indirect-first-instance", Set.of("shader-draw-parameters", "non-zero-first-instance", "draw-indirect",
                        "multi-draw-indirect"),
                "no indirect-first-instance", Set.of("shader-draw-parameters", "non-zero-first-instance",
                        "draw-indirect"),
                "glue without indirect imports", Set.of("shader-draw-parameters", "non-zero-first-instance"),
                "older glue: multi without draw", Set.of("shader-draw-parameters", "non-zero-first-instance",
                        "multi-draw-indirect"));
        for (Map.Entry<String, Set<String>> scenario : scenarios.entrySet()) {
            Set<String> caps = scenario.getValue();
            DeviceFeatures features = RpDevice.features(caps::contains);
            int limit = RpDevice.maxDrawIndirectDrawCount(caps::contains);
            vanillaAccepts(scenario.getKey(), features, limit);
        }

        DeviceFeatures with = RpDevice.features(scenarios.get("indirect-first-instance")::contains);
        check(with.drawIndirect() && with.multiDrawIndirect(), "feature present: drawIndirect and multiDrawIndirect");
        check(RpDevice.maxDrawIndirectDrawCount(scenarios.get("indirect-first-instance")::contains) == 256,
                "feature present: indirect terrain batches of 256");

        DeviceFeatures without = RpDevice.features(scenarios.get("no indirect-first-instance")::contains);
        check(without.drawIndirect() && !without.multiDrawIndirect(),
                "feature absent: single indexed indirect draws only");
        check(RpDevice.maxDrawIndirectDrawCount(scenarios.get("no indirect-first-instance")::contains) == 0,
                "feature absent: terrain stays on direct draws");

        DeviceFeatures missing = RpDevice.features(scenarios.get("glue without indirect imports")::contains);
        check(!missing.drawIndirect() && !missing.multiDrawIndirect(), "missing imports: no indirect features");

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("all device feature checks passed");
    }

    /** The FrontendRenderPass / LevelRenderer rules for the 26.3 indirect paths. */
    private static void vanillaAccepts(String name, DeviceFeatures features, int limit) {
        check(!features.multiDrawIndirect() || features.drawIndirect(),
                name + ": multiDrawIndirect is never advertised without drawIndirect");
        boolean indirectTerrain = limit > 0 && features.nonZeroFirstInstance();
        if (indirectTerrain) {
            for (int sections : new int[] {1, 2, 255, 256, 257, 4096}) {
                int drawCount = Math.min(sections, limit);
                check(features.drawIndirect(), name + ": terrain drawIndexedIndirect needs drawIndirect");
                check(drawCount == 1 || features.multiDrawIndirect(),
                        name + ": terrain drawCount " + drawCount + " needs multiDrawIndirect");
            }
        }
        check(limit == 0 || (features.drawIndirect() && features.multiDrawIndirect()),
                name + ": maxDrawIndirectDrawCount " + limit + " matches the indirect features");
        System.out.println("ok   " + name + ": " + features + " maxDrawIndirectDrawCount=" + limit);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("FAIL " + message);
        }
    }
}
