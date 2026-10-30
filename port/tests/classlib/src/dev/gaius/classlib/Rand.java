package dev.gaius.classlib;

/**
 * The input generator of the suite: java.util.Random's 48-bit linear congruential generator
 * as its specification defines {@code next}, {@code nextInt}, {@code nextInt(bound)},
 * {@code nextLong} and {@code nextDouble}, written out here so that every runtime draws the
 * same inputs even where its own java.util.Random differs (TeaVM's does: the Randoms section).
 * {@link #nextGaussianLike} replaces nextGaussian, whose polar method depends on
 * StrictMath.log, with a sum of twelve uniforms (exact double arithmetic everywhere).
 */
final class Rand {
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long MASK = (1L << 48) - 1;
    private long seed;

    Rand(long seed) {
        this.seed = (seed ^ MULTIPLIER) & MASK;
    }

    int next(int bits) {
        seed = (seed * MULTIPLIER + 0xBL) & MASK;
        return (int) (seed >>> (48 - bits));
    }

    int nextInt() {
        return next(32);
    }

    int nextInt(int bound) {
        int r = next(31);
        int m = bound - 1;
        if ((bound & m) == 0) {
            return (int) ((bound * (long) r) >> 31);
        }
        for (int u = r; u - (r = u % bound) + m < 0; u = next(31)) {
            // Rejects the values of the last, partial interval.
        }
        return r;
    }

    long nextLong() {
        return ((long) next(32) << 32) + next(32);
    }

    double nextDouble() {
        return (((long) next(26) << 27) + next(27)) * 0x1.0p-53;
    }

    /** Approximately standard normal: the sum of twelve uniforms minus six. */
    double nextGaussianLike() {
        double sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += nextDouble();
        }
        return sum - 6;
    }

    /** 2^exponent for -1074 <= exponent <= 1023, exactly (no Math.pow). */
    static double pow2(int exponent) {
        if (exponent < -1022) {
            return Double.longBitsToDouble(1L << (exponent + 1074));
        }
        return Double.longBitsToDouble((long) (exponent + 1023) << 52);
    }

    private static final double[] POW10 = {1e-20, 1e-19, 1e-18, 1e-17, 1e-16, 1e-15, 1e-14, 1e-13, 1e-12, 1e-11,
        1e-10, 1e-9, 1e-8, 1e-7, 1e-6, 1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8,
        1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20};

    /** 10^exponent for -20 <= exponent <= 20, the correctly rounded literal (no Math.pow). */
    static double pow10(int exponent) {
        return POW10[exponent + 20];
    }
}
