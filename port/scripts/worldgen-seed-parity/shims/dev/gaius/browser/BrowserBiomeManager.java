package dev.gaius.browser;

/**
 * JVM transcription of the JavaScript body of {@code BrowserBiomeManager.nearestCorner}
 * (port/src/main/java/dev/gaius/browser/BrowserBiomeManager.java) for worldgen-seed-parity.mjs.
 */
public final class BrowserBiomeManager {
    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;

    private BrowserBiomeManager() {
    }

    public static int nearestCorner(long seed, int shiftedX, int shiftedY, int shiftedZ) {
        int baseX = shiftedX >> 2;
        int baseY = shiftedY >> 2;
        int baseZ = shiftedZ >> 2;
        double fractionX = (shiftedX & 3) / 4.0;
        double fractionY = (shiftedY & 3) / 4.0;
        double fractionZ = (shiftedZ & 3) / 4.0;
        int nearest = 0;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            boolean highX = (corner & 4) != 0;
            boolean highY = (corner & 2) != 0;
            boolean highZ = (corner & 1) != 0;
            long quartX = highX ? (long) baseX + 1 : baseX;
            long quartY = highY ? (long) baseY + 1 : baseY;
            long quartZ = highZ ? (long) baseZ + 1 : baseZ;
            long value = next(seed, quartX);
            value = next(value, quartY);
            value = next(value, quartZ);
            value = next(value, quartX);
            value = next(value, quartY);
            value = next(value, quartZ);
            double distanceX = (highX ? fractionX - 1.0 : fractionX) + fiddle(value);
            value = next(value, seed);
            double distanceY = (highY ? fractionY - 1.0 : fractionY) + fiddle(value);
            value = next(value, seed);
            double distanceZ = (highZ ? fractionZ - 1.0 : fractionZ) + fiddle(value);
            double distance = distanceZ * distanceZ + distanceY * distanceY
                    + distanceX * distanceX;
            if (nearestDistance > distance) {
                nearest = corner;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static long next(long value, long addend) {
        return value * (value * MULTIPLIER + INCREMENT) + addend;
    }

    private static double fiddle(long value) {
        return (((value >> 24) & 1023) / 1024.0 - 0.5) * 0.9;
    }
}
