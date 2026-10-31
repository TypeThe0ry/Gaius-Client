package dev.gaius.golden;

import java.util.Random;

/**
 * Deterministic input generators. Every generator draws from a caller-owned
 * java.util.Random with a fixed seed, so a fixture file is reproducible
 * byte for byte.
 */
final class Inputs {
    /** Seeds shared by every noise kind; covers zero, sign extremes and ordinary values. */
    static final long[] SEEDS = {0L, 12345L, -8424127163862190849L, Long.MAX_VALUE};

    /** World seed used for the worldgen-style positional forks. */
    static final long WORLD_SEED = 7_331_008_642L;

    private static final double[] SPECIAL = {
        0.0, -0.0, 0.5, -0.5, 1.0, -1.0, 1.0E-7, -1.0E-7,
        15.999999999, -16.000000001, 255.5, -255.5,
        3.0E7, -3.0E7, 29_999_999.5, -29_999_999.5,
        33_554_432.0, -33_554_432.0, 33_554_431.999, 1.0E-300,
    };

    private static final int[] SPECIAL_BLOCK = {
        0, -1, 1, 15, 16, -16, -17, -64, 319, 320, 30_000_000, -30_000_000, 29_999_999, -29_999_984,
    };

    private Inputs() {
    }

    static Random random(String salt) {
        return new Random(salt.hashCode() * 0x9E3779B97F4A7C15L);
    }

    /** A coordinate mixing negatives, fractions, integers, quarter steps and ±3e7 magnitudes. */
    static double coord(Random r) {
        return switch (r.nextInt(7)) {
            case 0 -> r.nextInt(513) - 256;
            case 1 -> r.nextDouble() * 512.0 - 256.0;
            case 2 -> r.nextInt(60_000_001) - 30_000_000;
            case 3 -> (r.nextDouble() * 2.0 - 1.0) * 3.0E7;
            case 4 -> (r.nextDouble() * 2.0 - 1.0) * 4.0;
            case 5 -> (r.nextInt(2049) - 1024) * 0.25;
            default -> SPECIAL[r.nextInt(SPECIAL.length)];
        };
    }

    /** An integer block coordinate: small, world-height, ±3e7 or a boundary value. */
    static int block(Random r) {
        return switch (r.nextInt(4)) {
            case 0 -> r.nextInt(1025) - 512;
            case 1 -> r.nextInt(385) - 64;
            case 2 -> r.nextInt(60_000_001) - 30_000_000;
            default -> SPECIAL_BLOCK[r.nextInt(SPECIAL_BLOCK.length)];
        };
    }

    /** An integer y in the overworld build range with a few out-of-range values. */
    static int blockY(Random r) {
        return r.nextInt(8) == 0 ? SPECIAL_BLOCK[r.nextInt(SPECIAL_BLOCK.length)] : r.nextInt(385) - 64;
    }

    /** A plain finite double of moderate size, used for Mth arguments. */
    static double scalar(Random r) {
        return switch (r.nextInt(5)) {
            case 0 -> r.nextDouble();
            case 1 -> (r.nextDouble() * 2.0 - 1.0) * 100.0;
            case 2 -> r.nextInt(201) - 100;
            case 3 -> (r.nextDouble() * 2.0 - 1.0) * 3.0E7;
            default -> SPECIAL[r.nextInt(SPECIAL.length)];
        };
    }
}
