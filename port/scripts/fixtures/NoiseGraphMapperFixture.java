package net.minecraft.world.level.levelgen;

import java.util.IdentityHashMap;
import java.util.Map;

/** Minimal real-JVM contract fixture for BrowserNoiseGraphMapper. */
public final class NoiseGraphMapperFixture {
    private static final class Node implements DensityFunction {
        final String name;
        final double value;
        final DensityFunction.NoiseHolder noise;
        final DensityFunction[] children;

        Node(String name, double value, DensityFunction.NoiseHolder noise, DensityFunction... children) {
            this.name = name;
            this.value = value;
            this.noise = noise;
            this.children = children;
        }

        @Override
        public DensityFunction mapChildren(Visitor visitor) {
            DensityFunction[] mapped = new DensityFunction[children.length];
            for (int i = 0; i < children.length; i++) mapped[i] = visitor.apply(children[i]);
            return new Node(name, value, noise == null ? null : visitor.visitNoise(noise), mapped);
        }

        @Override
        public double compute(double x, double y, double z) {
            double result = value;
            for (DensityFunction child : children) result += child.compute(x, y, z);
            return result;
        }

        @Override
        public double minValue() {
            double result = value;
            for (DensityFunction child : children) result += child.minValue();
            return result;
        }

        @Override
        public double maxValue() {
            double result = value;
            for (DensityFunction child : children) result += child.maxValue();
            return result;
        }
    }

    private static final class CountingVisitor implements DensityFunction.Visitor {
        int applyCount;
        int noiseCount;
        final Map<DensityFunction, Integer> seen = new IdentityHashMap<>();

        @Override
        public DensityFunction apply(DensityFunction input) {
            applyCount++;
            seen.merge(input, 1, Integer::sum);
            return input;
        }

        @Override
        public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
            noiseCount++;
            return noise;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        DensityFunction.NoiseHolder sharedNoise = new DensityFunction.NoiseHolder("shared");
        Node shared = new Node("shared", 3.0, sharedNoise);
        Node left = new Node("left", 2.0, null, shared, shared);
        Node right = new Node("right", 5.0, null, shared);
        Node root = new Node("root", 7.0, null, left, right, shared);
        CountingVisitor originalVisitor = new CountingVisitor();
        DensityFunction original = root.mapAll(originalVisitor);
        double originalValue = original.compute(11, 13, 17);
        double originalMin = original.minValue();
        double originalMax = original.maxValue();

        CountingVisitor mapperVisitor = new CountingVisitor();
        DensityFunction mapped = BrowserNoiseGraphMapper.map(root, mapperVisitor);
        check(mapped.compute(11, 13, 17) == originalValue, "mapped compute changed");
        check(mapped.minValue() == originalMin, "mapped min changed");
        check(mapped.maxValue() == originalMax, "mapped max changed");
        check(mapperVisitor.noiseCount == 1, "shared noise was not visited once");
        check(mapperVisitor.applyCount == 4, "shared DAG node was not memoized per identity");

        CountingVisitor secondVisitor = new CountingVisitor();
        BrowserNoiseGraphMapper.map(root, secondVisitor);
        check(secondVisitor.applyCount == mapperVisitor.applyCount, "memo leaked across invocations");
        check(secondVisitor.noiseCount == 1, "noise memo leaked across invocations");

        NoiseRouter router = new NoiseRouter(root, shared, root, shared, root, shared, root,
                shared, root, shared, root, shared, root, shared, root);
        CountingVisitor routerVisitor = new CountingVisitor();
        NoiseRouter mappedRouter = BrowserNoiseGraphMapper.mapRouter(router, routerVisitor);
        check(mappedRouter != router, "mapRouter did not create a new router");
        check(mappedRouter.finalDensity().compute(11, 13, 17) == shared.compute(11, 13, 17),
                "mapped router final density changed");
        check(routerVisitor.applyCount == 4, "mapRouter did not share one per-call memo");
        check(routerVisitor.noiseCount == 1, "mapRouter noise visit count changed");
        System.out.println("NOISE_GRAPH_MAPPER_OK compute=" + originalValue
                + " min=" + originalMin + " max=" + originalMax
                + " apply=" + mapperVisitor.applyCount + " noise=" + mapperVisitor.noiseCount);
    }
}
