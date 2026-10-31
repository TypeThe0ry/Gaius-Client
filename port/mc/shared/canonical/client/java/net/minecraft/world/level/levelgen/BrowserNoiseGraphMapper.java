package net.minecraft.world.level.levelgen;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Maps shared density subgraphs once while wrapping one NoiseChunk.
 * Vanilla mapAll visits children before NoiseChunk.wrap consults its cache;
 * caching by original node here also avoids repeating that child traversal.
 * Only the chunk's deterministic wrap visitor may use this helper. General
 * visitors can depend on visit counts and must retain vanilla mapAll semantics.
 * Each operation owns its map: interpolators and cell caches never cross chunk
 * or random-state boundaries.
 */
final class BrowserNoiseGraphMapper implements DensityFunction.Visitor {
    private final DensityFunction.Visitor delegate;
    private final Map<DensityFunction, DensityFunction> mapped = new IdentityHashMap<>();

    private BrowserNoiseGraphMapper(DensityFunction.Visitor delegate) {
        this.delegate = delegate;
    }

    static DensityFunction map(DensityFunction root, DensityFunction.Visitor visitor) {
        return new BrowserNoiseGraphMapper(visitor).apply(root);
    }

    static NoiseRouter mapRouter(NoiseRouter router, DensityFunction.Visitor visitor) {
        BrowserNoiseGraphMapper mapper = new BrowserNoiseGraphMapper(visitor);
        return new NoiseRouter(
                mapper.apply(router.barrierNoise()),
                mapper.apply(router.fluidLevelFloodednessNoise()),
                mapper.apply(router.fluidLevelSpreadNoise()),
                mapper.apply(router.lavaNoise()),
                mapper.apply(router.temperature()),
                mapper.apply(router.vegetation()),
                mapper.apply(router.continents()),
                mapper.apply(router.erosion()),
                mapper.apply(router.depth()),
                mapper.apply(router.ridges()),
                mapper.apply(router.preliminarySurfaceLevel()),
                mapper.apply(router.finalDensity()),
                mapper.apply(router.veinToggle()),
                mapper.apply(router.veinRidged()),
                mapper.apply(router.veinGap()));
    }

    @Override
    public DensityFunction apply(DensityFunction input) {
        DensityFunction cached = mapped.get(input);
        if (cached != null) {
            return cached;
        }
        DensityFunction result = delegate.apply(input.mapChildren(this));
        mapped.put(input, result);
        return result;
    }

    @Override
    public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
        return delegate.visitNoise(noise);
    }
}
