package dev.gaius.golden;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Builds the random source a noise is seeded from and records it in the case
 * params as {"random": "xoroshiro"|"legacy", "seed": long, "fork": name?}.
 * With "fork" the source is {@code base.forkPositional().fromHashOf(fork)},
 * the way worldgen derives per-noise randoms from the world seed.
 */
final class Rngs {
    static final String XOROSHIRO = "xoroshiro";
    static final String LEGACY = "legacy";

    private Rngs() {
    }

    static RandomSource base(String type, long seed) {
        return switch (type) {
            case XOROSHIRO -> new XoroshiroRandomSource(seed);
            case LEGACY -> new LegacyRandomSource(seed);
            default -> throw new IllegalArgumentException(type);
        };
    }

    static RandomSource seeded(Case c, String type, long seed) {
        return forked(c, type, seed, null);
    }

    static RandomSource forked(Case c, String type, long seed, String fork) {
        c.param("random", type).param("seed", seed);
        RandomSource base = base(type, seed);
        if (fork == null) {
            return base;
        }
        c.param("fork", fork);
        return base.forkPositional().fromHashOf(fork);
    }
}
