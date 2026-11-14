package dev.gaius.browser;

import java.util.Arrays;

/**
 * Exact {@code long} implementation of biome zoom's nearest-corner calculation, the part of
 * {@code BiomeManager.getBiome} that picks which of the eight surrounding quart corners is
 * nearest to a position after each corner is moved by its pseudo-random "fiddle".
 *
 * <p>Vanilla derives the fiddles of a corner with nine
 * {@code LinearCongruentialGenerator.next(value, salt)} steps, each {@code value * (value *
 * 6364136223846793005 + 1442695040888963407) + salt} in wrapping 64-bit arithmetic, and
 * {@code getFiddle(value) = (floorMod(value >> 24, 1024) / 1024.0 - 0.5) * 0.9}. This class
 * performs the same integer steps, reads the same ten bits and builds the same doubles (from a
 * table of the 1024 possible values, computed with the vanilla expression), adds them to the
 * same fractions, sums the squares in the vanilla order (z, then y, then x) and keeps the first
 * strictly smaller distance, so the corner it returns equals the vanilla one for every seed and
 * every position. {@code port/scripts/biome-corner-parity.mjs} checks that against the real
 * {@code BiomeManager} of each profile.
 *
 * <p>Neighbouring lookups share corners, so the three ten-bit fiddles of a corner are kept in a
 * direct-mapped cache of 4096 corners. A slot is only used when all three quart coordinates of
 * its key match, and the whole cache is dropped when the seed changes; a collision merely
 * recomputes. The state is static and plain: the callers never suspend between reading and
 * writing it, and a WebAssembly module (or a Worker) runs one Java thread at a time.
 *
 * <p>This class has no JavaScript interop so that it runs on a JVM unchanged. The WebAssembly
 * GC build uses it through {@link BrowserBiomeManager}; its native {@code i64} multiply and add
 * are far cheaper than the BigInt chains the JavaScript implementation needs, and it avoids the
 * WebAssembly-to-JavaScript call.
 */
public final class BrowserBiomeNearestCorner {
    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;
    private static final int SLOTS = 4096;
    private static final int SLOT_MASK = SLOTS - 1;
    // Four ints per slot: quart x, y, z and the packed fiddles (z << 20 | y << 10 | x), or -1
    // when the slot is empty (a packed value never has the sign bit).
    private static final int STRIDE = 4;
    private static final int EMPTY = -1;
    private static final int[] CORNERS = new int[SLOTS * STRIDE];
    // getFiddle for every value of the ten bits floorMod(value >> 24, 1024) can take.
    private static final double[] FIDDLE = new double[1024];

    private static long cachedSeed;
    private static boolean primed;

    static {
        for (int bits = 0; bits < FIDDLE.length; bits++) {
            double value = (double) bits / 1024.0;
            FIDDLE[bits] = (value - 0.5) * 0.9;
        }
    }

    private BrowserBiomeNearestCorner() {
    }

    /**
     * Index (0..7, bit 2 = x, bit 1 = y, bit 0 = z, set meaning the higher quart) of the corner
     * that {@code BiomeManager.getBiome} picks for the block position
     * {@code (shiftedX + 2, shiftedY + 2, shiftedZ + 2)} under the zoom seed {@code seed}.
     */
    public static int nearestCorner(long seed, int shiftedX, int shiftedY, int shiftedZ) {
        if (!primed || seed != cachedSeed) {
            Arrays.fill(CORNERS, EMPTY);
            cachedSeed = seed;
            primed = true;
        }
        int baseX = shiftedX >> 2;
        int baseY = shiftedY >> 2;
        int baseZ = shiftedZ >> 2;
        double fractionX = (double) (shiftedX & 3) / 4.0;
        double fractionY = (double) (shiftedY & 3) / 4.0;
        double fractionZ = (double) (shiftedZ & 3) / 4.0;
        int nearest = 0;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            boolean highX = (corner & 4) != 0;
            boolean highY = (corner & 2) != 0;
            boolean highZ = (corner & 1) != 0;
            int packed = fiddles(seed, highX ? baseX + 1 : baseX, highY ? baseY + 1 : baseY,
                    highZ ? baseZ + 1 : baseZ);
            double distanceX = (highX ? fractionX - 1.0 : fractionX) + FIDDLE[packed & 1023];
            double distanceY = (highY ? fractionY - 1.0 : fractionY)
                    + FIDDLE[(packed >>> 10) & 1023];
            double distanceZ = (highZ ? fractionZ - 1.0 : fractionZ)
                    + FIDDLE[(packed >>> 20) & 1023];
            double distance = distanceZ * distanceZ + distanceY * distanceY
                    + distanceX * distanceX;
            if (nearestDistance > distance) {
                nearest = corner;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /** The packed ten-bit fiddles of quart corner (x, y, z), from the cache when present. */
    private static int fiddles(long seed, int x, int y, int z) {
        int slot = ((x * 73856093 ^ y * 19349663 ^ z * 83492791) & SLOT_MASK) * STRIDE;
        int[] corners = CORNERS;
        if (corners[slot + 3] != EMPTY && corners[slot] == x && corners[slot + 1] == y
                && corners[slot + 2] == z) {
            return corners[slot + 3];
        }
        long qx = x;
        long qy = y;
        long qz = z;
        long value = next(seed, qx);
        value = next(value, qy);
        value = next(value, qz);
        value = next(value, qx);
        value = next(value, qy);
        value = next(value, qz);
        int packed = ten(value);
        value = next(value, seed);
        packed |= ten(value) << 10;
        value = next(value, seed);
        packed |= ten(value) << 20;
        corners[slot] = x;
        corners[slot + 1] = y;
        corners[slot + 2] = z;
        corners[slot + 3] = packed;
        return packed;
    }

    /** {@code LinearCongruentialGenerator.next}. */
    private static long next(long value, long salt) {
        return value * (value * MULTIPLIER + INCREMENT) + salt;
    }

    /** {@code Math.floorMod(value >> 24, 1024)}: the low ten bits of a two's complement shift. */
    private static int ten(long value) {
        return (int) (value >> 24) & 1023;
    }
}
