package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.systems.DeviceFeatures;
import java.util.Map;
import java.util.Set;

/**
 * JVM checks that the advertised 26.2 DeviceFeatures never form a combination vanilla rejects
 * (run by {@code port/mc/seams/test-seam.sh blaze3d-backend-1}).  The capability sets match
 * gpu-capabilities-smoke.mjs.  26.2's RenderPass.drawIndexedIndirect (javap of the clean jar)
 * throws without drawIndirect, and for drawCount > 1 without multiDrawIndirect; the game itself
 * issues no indirect draws.
 * Exits with status 1 on any failure.
 */
public final class B1DeviceFeaturesTest {
    private static int failures;

    private B1DeviceFeaturesTest() {
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
            DeviceFeatures features = B1Device.features(caps::contains);
            String name = scenario.getKey();
            check(!features.multiDrawIndirect() || features.drawIndirect(),
                    name + ": multiDrawIndirect is never advertised without drawIndirect");
            check(features.drawIndirect() == caps.contains("draw-indirect"),
                    name + ": drawIndirect follows the executable indexed indirect path");
            check(!features.multiDrawIndirect(), name + ": 26.2 keeps multiDrawIndirect off");
            System.out.println("ok   " + name + ": " + features);
        }
        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("all device feature checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("FAIL " + message);
        }
    }
}
