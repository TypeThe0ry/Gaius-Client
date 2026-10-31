package net.minecraft.world.level.levelgen;

import java.util.IdentityHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Real 26.2 named-jar DensityFunctions DAG regression. */
public final class RealNoiseGraphMapperFixture {
    private static final DensityFunction.FunctionContext[] POINTS = {
        new DensityFunction.FunctionContext() { public int blockX() { return 0; } public int blockY() { return 64; } public int blockZ() { return 0; } },
        new DensityFunction.FunctionContext() { public int blockX() { return 137; } public int blockY() { return 96; } public int blockZ() { return -211; } },
        new DensityFunction.FunctionContext() { public int blockX() { return -401; } public int blockY() { return 12; } public int blockZ() { return 733; } },
    };

    private static final class CountingVisitor implements DensityFunction.Visitor {
        int applyCount;
        int noiseCount;
        final IdentityHashMap<DensityFunction, Boolean> identities = new IdentityHashMap<>();
        public DensityFunction apply(DensityFunction input) {
            applyCount++;
            identities.put(input, Boolean.TRUE);
            return input;
        }
        public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
            noiseCount++;
            return noise;
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static DensityFunction graph() {
        DensityFunction shared = DensityFunctions.yClampedGradient(-64, 320, -1.0, 1.0);
        DensityFunction root = DensityFunctions.add(shared, shared);
        for (int i = 0; i < 12; i++) {
            root = (i & 1) == 0 ? DensityFunctions.add(root, root) : DensityFunctions.mul(root, root);
        }
        return root;
    }

    private static void compare(DensityFunction expected, DensityFunction actual) {
        for (DensityFunction.FunctionContext point : POINTS) {
            double value = expected.compute(point);
            check(Double.isFinite(value), "fixture must compare finite values");
            check(Double.compare(value, actual.compute(point)) == 0, "compute changed");
        }
        check(Double.compare(expected.minValue(), actual.minValue()) == 0, "min changed");
        check(Double.compare(expected.maxValue(), actual.maxValue()) == 0, "max changed");
    }

    private static void checkRouter() {
        DensityFunction[] values = new DensityFunction[15];
        for (int i = 0; i < values.length; i++) {
            values[i] = DensityFunctions.yClampedGradient(-64, 320, i, i + 0.5);
        }
        NoiseRouter router = new NoiseRouter(values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8], values[9], values[10], values[11],
                values[12], values[13], values[14]);
        CountingVisitor visitor = new CountingVisitor();
        NoiseRouter mapped = BrowserNoiseGraphMapper.mapRouter(router, visitor);
        DensityFunction[] actual = {mapped.barrierNoise(), mapped.fluidLevelFloodednessNoise(),
                mapped.fluidLevelSpreadNoise(), mapped.lavaNoise(), mapped.temperature(),
                mapped.vegetation(), mapped.continents(), mapped.erosion(), mapped.depth(),
                mapped.ridges(), mapped.preliminarySurfaceLevel(), mapped.finalDensity(),
                mapped.veinToggle(), mapped.veinRidged(), mapped.veinGap()};
        for (int i = 0; i < values.length; i++) compare(values[i], actual[i]);
        check(visitor.applyCount == 15, "router lost a distinct root");
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DensityFunction root = graph();
        CountingVisitor vanillaVisitor = new CountingVisitor();
        long vanillaStart = System.nanoTime();
        DensityFunction vanilla = root.mapAll(vanillaVisitor);
        long vanillaNanos = System.nanoTime() - vanillaStart;
        CountingVisitor mapperVisitor = new CountingVisitor();
        long mapperStart = System.nanoTime();
        DensityFunction mapped = BrowserNoiseGraphMapper.map(root, mapperVisitor);
        long mapperNanos = System.nanoTime() - mapperStart;
        compare(vanilla, mapped);
        check(vanillaVisitor.noiseCount == mapperVisitor.noiseCount, "visitNoise count changed");
        check(mapperVisitor.applyCount < vanillaVisitor.applyCount, "shared DAG was not memoized");
        CountingVisitor second = new CountingVisitor();
        BrowserNoiseGraphMapper.map(root, second);
        check(second.applyCount == mapperVisitor.applyCount, "memo leaked across invocations");
        DensityFunction.Visitor transform = input -> input.clamp(-0.75, 0.75);
        compare(root.mapAll(transform), BrowserNoiseGraphMapper.map(root, transform));
        checkRouter();
        System.out.println("REAL_NOISE_GRAPH_MAPPER_OK depth=12 vanillaApply=" + vanillaVisitor.applyCount
                + " memoApply=" + mapperVisitor.applyCount + " noise=" + mapperVisitor.noiseCount
                + " vanillaNanos=" + vanillaNanos + " mapperNanos=" + mapperNanos);
    }
}
