package dev.gaius.golden;

import java.util.Random;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Random source fixtures. These classes have the same shape in every profile.
 *
 * <ul>
 *   <li>xoroshiro_next_long / xoroshiro_next_double: params {"ctor": "seed", "seed"} for
 *       {@code new XoroshiroRandomSource(seed)} or {"ctor": "seed128", "seed_lo", "seed_hi"} for
 *       {@code new XoroshiroRandomSource(lo, hi)}; no inputs; outputs are consecutive draws.</li>
 *   <li>legacy_next_int_bound: params {"seed"}; inputs [[bound]...]; outputs
 *       {@code nextInt(bound)} drawn in order from one {@code LegacyRandomSource(seed)}.</li>
 *   <li>legacy_next_double: params {"seed"}; outputs consecutive {@code nextDouble()}.</li>
 *   <li>positional_from_hash: params {"random", "seed", "name"}; outputs the first four
 *       {@code nextLong()} of {@code base.forkPositional().fromHashOf(name)}.</li>
 * </ul>
 */
final class RandomFixtures {
    private static final int DRAWS = 64;

    private static final long[] SEEDS = {
        0L, 1L, -1L, 42L, 12345L, Long.MIN_VALUE, Long.MAX_VALUE,
        0x123456789ABCDEFL, -8424127163862190849L, Inputs.WORLD_SEED,
    };

    private static final long[][] SEEDS_128 = {
        {0L, 0L}, {1L, 0L}, {0L, 1L}, {-1L, -1L},
        {0x6A09E667F3BCC909L, 0x9E3779B97F4A7C15L}, {Long.MIN_VALUE, Long.MAX_VALUE},
    };

    private static final int[] BOUNDS = {
        1, 2, 3, 7, 10, 16, 100, 256, 1000, 4096, 65_535, 1 << 24, 1 << 30,
        (1 << 30) + 1, 1_500_000_000, Integer.MAX_VALUE,
    };

    private static final String[] NAMES = {
        "", "a", "minecraft:terrain", "minecraft:aquifer", "minecraft:ore", "minecraft:temperature",
        "minecraft:vegetation", "minecraft:continentalness", "minecraft:erosion", "minecraft:ridge",
        "minecraft:offset", "minecraft:cave_cheese", "minecraft:surface", "minecraft:noodle",
        "octave_-15", "octave_-7", "octave_0", "octave_3", "minecraft:end_islands",
        "a much longer name to exercise multi-block md5 input 0123456789abcdef0123456789",
    };

    private RandomFixtures() {
    }

    static void write(FixtureSink sink) {
        writeXoroshiro(sink);
        writeLegacy(sink);
        writePositional(sink);
    }

    private static void writeXoroshiro(FixtureSink sink) {
        for (long seed : SEEDS) {
            Case longs = new Case("xoroshiro_next_long").param("ctor", "seed").param("seed", seed);
            Case doubles = new Case("xoroshiro_next_double").param("ctor", "seed").param("seed", seed);
            fillXoroshiro(longs, doubles, new XoroshiroRandomSource(seed), new XoroshiroRandomSource(seed));
            sink.write(longs);
            sink.write(doubles);
        }
        for (long[] pair : SEEDS_128) {
            Case longs = new Case("xoroshiro_next_long")
                    .param("ctor", "seed128").param("seed_lo", pair[0]).param("seed_hi", pair[1]);
            Case doubles = new Case("xoroshiro_next_double")
                    .param("ctor", "seed128").param("seed_lo", pair[0]).param("seed_hi", pair[1]);
            fillXoroshiro(longs, doubles,
                    new XoroshiroRandomSource(pair[0], pair[1]), new XoroshiroRandomSource(pair[0], pair[1]));
            sink.write(longs);
            sink.write(doubles);
        }
    }

    private static void fillXoroshiro(Case longs, Case doubles, RandomSource forLongs, RandomSource forDoubles) {
        for (int i = 0; i < DRAWS; i++) {
            longs.output(forLongs.nextLong());
            doubles.output(forDoubles.nextDouble());
        }
    }

    private static void writeLegacy(FixtureSink sink) {
        Random pick = Inputs.random("legacy_next_int_bound");
        for (long seed : SEEDS) {
            Case ints = new Case("legacy_next_int_bound").param("seed", seed);
            RandomSource random = new LegacyRandomSource(seed);
            for (int i = 0; i < DRAWS; i++) {
                int bound = i < BOUNDS.length ? BOUNDS[i] : BOUNDS[pick.nextInt(BOUNDS.length)];
                ints.input(bound).output(random.nextInt(bound));
            }
            sink.write(ints);

            Case doubles = new Case("legacy_next_double").param("seed", seed);
            RandomSource forDoubles = new LegacyRandomSource(seed);
            for (int i = 0; i < DRAWS; i++) {
                doubles.output(forDoubles.nextDouble());
            }
            sink.write(doubles);
        }
    }

    private static void writePositional(FixtureSink sink) {
        long[] seeds = {0L, Inputs.WORLD_SEED, -8424127163862190849L};
        for (String type : new String[] {Rngs.XOROSHIRO, Rngs.LEGACY}) {
            for (long seed : seeds) {
                for (String name : NAMES) {
                    Case c = new Case("positional_from_hash")
                            .param("random", type).param("seed", seed).param("name", name);
                    RandomSource random = Rngs.base(type, seed).forkPositional().fromHashOf(name);
                    for (int i = 0; i < 4; i++) {
                        c.output(random.nextLong());
                    }
                    sink.write(c);
                }
            }
        }
    }
}
