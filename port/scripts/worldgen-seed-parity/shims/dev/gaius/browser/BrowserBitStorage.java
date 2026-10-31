package dev.gaius.browser;

/**
 * JVM form of the classlib {@code BrowserBitStorage} JavaScript bodies
 * (port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserBitStorage.java) for
 * worldgen-seed-parity.mjs; as in vanilla SimpleBitStorage, a value never spans two longs.
 */
public final class BrowserBitStorage {
    private BrowserBitStorage() {
    }

    public static int get(long[] packed, int index, int valuesPerLong, int bits) {
        int cell = index / valuesPerLong;
        int offset = (index - cell * valuesPerLong) * bits;
        long mask = (1L << bits) - 1L;
        return (int) ((packed[cell] >> offset) & mask);
    }

    public static int getAndSet(long[] packed, int index, int value, int valuesPerLong, int bits) {
        int cell = index / valuesPerLong;
        int offset = (index - cell * valuesPerLong) * bits;
        long mask = (1L << bits) - 1L;
        long word = packed[cell];
        int previous = (int) ((word >> offset) & mask);
        packed[cell] = (word & ~(mask << offset)) | (((long) value & mask) << offset);
        return previous;
    }
}
