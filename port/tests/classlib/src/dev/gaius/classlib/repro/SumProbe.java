package dev.gaius.classlib.repro;

import dev.gaius.browser.BrowserStrictMath;
import java.util.stream.DoubleStream;

/**
 * DoubleStream.sum (JDK: compensated, Collectors.sumWithCompensation) and
 * BrowserStrictMath.sum, which StrictMath263 calls in the 26.3 worldgen classes instead.
 * TeaVM sums naively; BrowserStrictMath.sum must equal the JVM on every runtime.
 */
public final class SumProbe {
    private SumProbe() {
    }

    public static void main(String[] args) {
        double[][] cases = {
            {1.0, 1e-16, 1e-16, 1e-16, 1e-16},
            {0.5, 0.25, 0.125, 0.0625, 0.03125, 0.015625, 0.0078125, 0.00390625, 0.001953125},
            {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6},
            {1e308, 1e308, -1e308},
            {},
            {Double.NaN, 1.0},
            {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY},
            {Double.MAX_VALUE, Double.MAX_VALUE, -1.0},
            {-0.0, -0.0},
        };
        for (int i = 0; i < cases.length; i++) {
            System.out.println("case" + i + " stream=" + Double.doubleToRawLongBits(DoubleStream.of(cases[i]).sum())
                    + " strict=" + Double.doubleToRawLongBits(BrowserStrictMath.sum(DoubleStream.of(cases[i]))));
        }
    }
}
