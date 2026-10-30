package dev.gaius.browser;

import net.minecraft.world.level.biome.Climate;

/**
 * JVM form of {@code BrowserClimate} (port/src/main/java/dev/gaius/browser/BrowserClimate.java)
 * for worldgen-seed-parity.mjs: prepareBounds is copied, distance transcribes the JavaScript.
 */
public final class BrowserClimate {
    private BrowserClimate() {
    }

    public static double[] prepareBounds(Climate.Parameter[] parameterSpace) {
        double[] bounds = new double[14];
        for (int index = 0; index < 7; index++) {
            Climate.Parameter parameter = parameterSpace[index];
            long first = parameter.min();
            long second = parameter.max();
            bounds[index * 2] = Math.min(first, second);
            bounds[index * 2 + 1] = Math.max(first, second);
        }
        return bounds;
    }

    public static long distance(double[] bounds, long[] target) {
        if (bounds == null || target == null || bounds.length < 14 || target.length < 7) {
            return 0L;
        }
        double total = 0;
        for (int index = 0; index < 7; index++) {
            double value = (double) target[index];
            double low = bounds[index * 2];
            double high = bounds[index * 2 + 1];
            double distance = value > high ? value - high : (value < low ? low - value : 0);
            total += distance * distance;
        }
        return (long) Math.floor(total);
    }
}
