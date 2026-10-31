package dev.gaius.browser;

/**
 * JVM transcription of the JavaScript body of {@code BrowserAquifer.selectNearestCached}
 * (port/src/main/java/dev/gaius/browser/BrowserAquifer.java) for worldgen-seed-parity.mjs:
 * the same grid, cache sentinel, BlockPos unpacking and nearest-four insertion order.
 */
public final class BrowserAquifer {
    private BrowserAquifer() {
    }

    public static boolean selectNearestCached(long[] packed, int minGridX, int minGridY,
            int minGridZ, int gridSizeX, int gridSizeZ, int blockX, int blockY, int blockZ,
            int[] output) {
        if (packed == null || output == null || output.length < 8) {
            return false;
        }
        int baseGridX = (blockX - 5) >> 4;
        int baseGridY = Math.floorDiv(blockY + 1, 12);
        int baseGridZ = (blockZ - 5) >> 4;
        int distance0 = Integer.MAX_VALUE;
        int distance1 = Integer.MAX_VALUE;
        int distance2 = Integer.MAX_VALUE;
        int distance3 = Integer.MAX_VALUE;
        int index0 = 0;
        int index1 = 0;
        int index2 = 0;
        int index3 = 0;
        for (int offsetX = 0; offsetX <= 1; offsetX++) {
            int gridX = baseGridX + offsetX;
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                int gridY = baseGridY + offsetY;
                for (int offsetZ = 0; offsetZ <= 1; offsetZ++) {
                    int gridZ = baseGridZ + offsetZ;
                    int index = ((gridY - minGridY) * gridSizeZ + (gridZ - minGridZ)) * gridSizeX
                            + (gridX - minGridX);
                    long position = packed[index];
                    if (position == Long.MAX_VALUE) {
                        return false;
                    }
                    int centerX = (int) (position >> 38);
                    int centerY = (int) (position << 52 >> 52);
                    int centerZ = (int) (position << 26 >> 38);
                    int dx = centerX - blockX;
                    int dy = centerY - blockY;
                    int dz = centerZ - blockZ;
                    int distance = dx * dx + dy * dy + dz * dz;
                    if (distance0 >= distance) {
                        distance3 = distance2;
                        distance2 = distance1;
                        distance1 = distance0;
                        distance0 = distance;
                        index3 = index2;
                        index2 = index1;
                        index1 = index0;
                        index0 = index;
                    } else if (distance1 >= distance) {
                        distance3 = distance2;
                        distance2 = distance1;
                        distance1 = distance;
                        index3 = index2;
                        index2 = index1;
                        index1 = index;
                    } else if (distance2 >= distance) {
                        distance3 = distance2;
                        distance2 = distance;
                        index3 = index2;
                        index2 = index;
                    } else if (distance3 >= distance) {
                        distance3 = distance;
                        index3 = index;
                    }
                }
            }
        }
        output[0] = distance0;
        output[1] = distance1;
        output[2] = distance2;
        output[3] = distance3;
        output[4] = index0;
        output[5] = index1;
        output[6] = index2;
        output[7] = index3;
        return true;
    }
}
