package dev.gaius.wgperf;

public final class ProfilerExceptionFixture {
    public static int generateBiomes(int divisor) {
        return 17 / divisor;
    }

    public static int generateNoise() {
        return generateBiomes(0);
    }

    public static int generateSurface() {
        try {
            throw new IllegalArgumentException("caught");
        } catch (IllegalArgumentException expected) {
            return expected.getMessage().length();
        }
    }

    public static int generateCarvers() {
        throw new IllegalStateException("original failure");
    }

    public static int generateFeatures() {
        try {
            generateNoise();
        } catch (ArithmeticException expected) {
            return generateSurface();
        }
        throw new AssertionError("Expected the nested exception");
    }
}
