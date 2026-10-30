package dev.gaius.golden;

import java.util.Random;
import java.util.function.Function;
import net.minecraft.util.Mth;

/**
 * "mth" fixtures: params {"fn", "precision": "f64"|"f32"}; each input row holds the
 * function arguments in Java parameter order and each output is the result
 * (floor: int, lfloor: long, everything else: double or widened float).
 * Functions whose signature differs between profiles live in {@link VariantMth}.
 */
final class MthFixtures {
    static final int ROWS = 96;

    private static final double[] EXTREMES = {
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0E10, -1.0E10,
        2147483647.5, -2147483648.5, 9.3E18, -9.3E18, -0.5, -1.0E-300, -0.0, 4.999999999999999,
    };

    private MthFixtures() {
    }

    static void write(FixtureSink sink) throws ReflectiveOperationException {
        Random r = Inputs.random("mth");

        Case floor = fn("floor", "f64");
        Case lfloor = fn("lfloor", "f64");
        Case floorF = fn("floor", "f32");
        for (int i = 0; i < ROWS; i++) {
            double x = i < EXTREMES.length ? EXTREMES[i] : Inputs.coord(r);
            floor.input(x).output(Mth.floor(x));
            lfloor.input(x).output(Mth.lfloor(x));
            float xf = (float) x;
            floorF.input(xf).output(Mth.floor(xf));
        }
        sink.write(floor);
        sink.write(lfloor);
        sink.write(floorF);

        sink.write(rows("lerp", "f64", r, 3, a -> Mth.lerp(a[0], a[1], a[2])));
        sink.write(rows("lerp2", "f64", r, 6, a -> Mth.lerp2(a[0], a[1], a[2], a[3], a[4], a[5])));
        sink.write(rows("lerp3", "f64", r, 11,
                a -> Mth.lerp3(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10])));
        sink.write(rows("clampedMap", "f64", r, 5, a -> Mth.clampedMap(a[0], a[1], a[2], a[3], a[4])));
        sink.write(rows("map", "f64", r, 5, a -> Mth.map(a[0], a[1], a[2], a[3], a[4])));

        sink.write(rowsF("lerp", r, 3, a -> Mth.lerp(a[0], a[1], a[2])));
        sink.write(rowsF("clampedMap", r, 5, a -> Mth.clampedMap(a[0], a[1], a[2], a[3], a[4])));
        sink.write(rowsF("map", r, 5, a -> Mth.map(a[0], a[1], a[2], a[3], a[4])));

        VariantMth.write(sink, r);
    }

    static Case fn(String name, String precision) {
        return new Case("mth").param("fn", name).param("precision", precision);
    }

    /** Lerp-style rows: the leading delta argument(s) mostly stay in [0, 1]. */
    static Case rows(String name, String precision, Random r, int arity, Function<double[], Double> f) {
        Case c = fn(name, precision);
        int deltas = name.startsWith("lerp") ? arity - (1 << (arity == 3 ? 1 : arity == 6 ? 2 : 3)) : 0;
        for (int i = 0; i < ROWS; i++) {
            double[] args = new double[arity];
            for (int k = 0; k < arity; k++) {
                args[k] = k < deltas && r.nextInt(4) != 0 ? r.nextDouble() : Inputs.scalar(r);
            }
            Object[] row = new Object[arity];
            for (int k = 0; k < arity; k++) {
                row[k] = args[k];
            }
            c.input(row).output(f.apply(args));
        }
        return c;
    }

    /** Float counterpart of {@link #rows}; arguments are rounded to float before the call. */
    static Case rowsF(String name, Random r, int arity, Function<float[], Float> f) {
        Case c = fn(name, "f32");
        int deltas = name.startsWith("lerp") ? arity - (1 << (arity == 3 ? 1 : arity == 6 ? 2 : 3)) : 0;
        for (int i = 0; i < ROWS; i++) {
            float[] args = new float[arity];
            Object[] row = new Object[arity];
            for (int k = 0; k < arity; k++) {
                args[k] = (float) (k < deltas && r.nextInt(4) != 0 ? r.nextDouble() : Inputs.scalar(r));
                row[k] = args[k];
            }
            c.input(row).output(f.apply(args));
        }
        return c;
    }
}
