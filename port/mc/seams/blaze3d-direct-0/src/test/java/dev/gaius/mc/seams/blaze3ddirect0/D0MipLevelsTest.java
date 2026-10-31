package dev.gaius.mc.seams.blaze3ddirect0;

public final class D0MipLevelsTest {
    private D0MipLevelsTest() {
    }

    public static void main(String[] args) {
        expect(5, D0Texture.physicalMipLevels(16, 16, 6));
        expect(4, D0Texture.physicalMipLevels(16, 16, 4));
        expect(1, D0Texture.physicalMipLevels(1, 1, 6));
        expect(5, D0Texture.physicalMipLevels(8, 16, 6));
        expect(4, D0Texture.physicalMipLevels(15, 15, 6));
        expect(31, D0Texture.physicalMipLevels(Integer.MAX_VALUE, 1, 32));
        expect(5, D0Texture.viewMipLevels(5, 0, 6));
        expect(2, D0Texture.viewMipLevels(5, 3, 3));
        expect(1, D0Texture.viewMipLevels(5, 4, 1));
        rejected(IllegalArgumentException.class, () -> D0Texture.physicalMipLevels(0, 16, 6));
        rejected(IllegalArgumentException.class, () -> D0Texture.physicalMipLevels(16, -1, 6));
        rejected(IllegalArgumentException.class, () -> D0Texture.physicalMipLevels(16, 16, 0));
        rejected(IllegalArgumentException.class, () -> D0Texture.viewMipLevels(5, 0, 0));
        rejected(IllegalArgumentException.class, () -> D0Texture.viewMipLevels(5, -1, 1));
        rejected(UnsupportedOperationException.class, () -> D0Texture.viewMipLevels(5, 5, 1));
        System.out.println("D0MipLevelsTest: 15 checks passed");
    }

    private static void expect(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static void rejected(Class<? extends RuntimeException> expected, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw failure;
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
}
