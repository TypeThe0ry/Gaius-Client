package dev.gaius.classlib;

import dev.gaius.browser.BrowserStrictMath;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.DoubleStream;

/**
 * The BrowserStrictMath helpers that StrictMath263 calls in the 26.3 worldgen classes, on the
 * cases of port/native/golden/strict/StrictMathCheck.java: the same boundary values and the
 * same Random(263) draws for the conversions (through {@link Rand}, the JDK algorithm, as TeaVM's
 * java.util.Random differs); the sum cases use Rand.nextGaussianLike and exact powers of ten
 * instead of nextGaussian and Math.pow, which are not bit-exact on every runtime. The
 * {@code helper.*} lines must equal the JVM's own casts and DoubleStream.sum on every runtime:
 * on the JVM the helper's JSBody natives cannot run, so the JVM prints the casts themselves,
 * which StrictMathCheck proves equal to the helper's JVM twin. The {@code raw.*} lines are the
 * plain casts of each runtime, which the TeaVM JavaScript output does not saturate or round.
 */
final class StrictMathHelpers {
    private StrictMathHelpers() {
    }

    interface Casts {
        float fromDouble(double value);

        float fromLong(long value);

        float fromInt(int value);

        int toInt(double value);

        int toInt(float value);

        long toLong(double value);

        long toLong(float value);

        double sum(DoubleStream stream);
    }

    static final class Raw implements Casts {
        @Override
        public float fromDouble(double value) {
            return (float) value;
        }

        @Override
        public float fromLong(long value) {
            return (float) value;
        }

        @Override
        public float fromInt(int value) {
            return (float) value;
        }

        @Override
        public int toInt(double value) {
            return (int) value;
        }

        @Override
        public int toInt(float value) {
            return (int) value;
        }

        @Override
        public long toLong(double value) {
            return (long) value;
        }

        @Override
        public long toLong(float value) {
            return (long) value;
        }

        @Override
        public double sum(DoubleStream stream) {
            return stream.sum();
        }
    }

    static final class Helper implements Casts {
        @Override
        public float fromDouble(double value) {
            return BrowserStrictMath.fromDouble(value);
        }

        @Override
        public float fromLong(long value) {
            return BrowserStrictMath.fromLong(value);
        }

        @Override
        public float fromInt(int value) {
            return BrowserStrictMath.fromInt(value);
        }

        @Override
        public int toInt(double value) {
            return BrowserStrictMath.toInt(value);
        }

        @Override
        public int toInt(float value) {
            return BrowserStrictMath.toInt(value);
        }

        @Override
        public long toLong(double value) {
            return BrowserStrictMath.toLong(value);
        }

        @Override
        public long toLong(float value) {
            return BrowserStrictMath.toLong(value);
        }

        @Override
        public double sum(DoubleStream stream) {
            return BrowserStrictMath.sum(stream);
        }
    }

    static boolean helperRuns() {
        try {
            return BrowserStrictMath.round(1.5f) == 1.5f;
        } catch (Throwable e) {
            return false;
        }
    }

    static void run() {
        boolean helper = helperRuns();
        Out.note("helper natives " + (helper ? "run (TeaVM)" : "unavailable (JVM): helper lines are the JVM casts"));
        Cases cases = new Cases();
        Out.put("inputs.longs.digest", digestLongs(cases.longs));
        Out.put("inputs.ints.digest", digestLongs(cases.ints));
        Out.put("inputs.doubles.digest", digestDoubles(cases.doubles));
        Out.Digest sumInputs = new Out.Digest();
        for (double[] values : cases.sums) {
            for (double value : values) {
                sumInputs.add(Double.doubleToRawLongBits(value));
            }
            sumInputs.add(values.length);
        }
        Out.put("inputs.sums.digest", sumInputs);
        emit("helper", helper ? new Helper() : new Raw(), cases);
        emit("raw", new Raw(), cases);
    }

    /** The cases of StrictMathCheck.checkTwin, generated once from Rand(263). */
    static final class Cases {
        final List<Long> longs = new ArrayList<>(List.of(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L << 53,
                -(1L << 53), (1L << 53) + 1, (1L << 62) + (1L << 38) + 1, (1L << 62) + (1L << 38),
                (1L << 62) + (1L << 38) - 1));
        final List<Long> ints = new ArrayList<>();
        final List<Double> doubles = new ArrayList<>(List.of(Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 0.0, -0.0, 2147483647.0, 2147483647.5, 2147483648.0, -2147483648.0,
                -2147483648.5, -2147483649.0, 9.223372036854775807E18, -9.223372036854775808E18, 1e19, -1e19,
                1e300, -0.5));
        final int special = doubles.size();
        final List<double[]> sums = new ArrayList<>();

        Cases() {
            Rand random = new Rand(263);
            for (int i = 0; i < 200_000; i++) {
                long value = random.nextLong() >> random.nextInt(64);
                longs.add(value);
                longs.add(value | 1);
                longs.add(value & ~0xFFFFFFFFFFL | 0x8000000000L);
            }
            for (int i = 0; i < 100_000; i++) {
                ints.add((long) (random.nextInt() >> random.nextInt(32)));
            }
            for (int i = 0; i < 100_000; i++) {
                doubles.add((random.nextDouble() - 0.5) * Rand.pow2(random.nextInt(140) - 4));
            }
            for (int i = 0; i < 2_000; i++) {
                double[] values = new double[random.nextInt(40)];
                for (int j = 0; j < values.length; j++) {
                    values[j] = random.nextGaussianLike() * Rand.pow10(random.nextInt(30) - 15);
                }
                sums.add(values);
            }
        }
    }

    static Out.Digest digestLongs(List<Long> values) {
        Out.Digest digest = new Out.Digest();
        for (long value : values) {
            digest.add(value);
        }
        return digest;
    }

    static Out.Digest digestDoubles(List<Double> values) {
        Out.Digest digest = new Out.Digest();
        for (double value : values) {
            digest.add(Double.doubleToRawLongBits(value));
        }
        return digest;
    }

    static void emit(String tag, Casts casts, Cases cases) {
        Out.Digest fromLong = new Out.Digest();
        int shown = 0;
        for (long value : cases.longs) {
            float result = casts.fromLong(value);
            fromLong.add(Float.floatToRawIntBits(result));
            if (shown++ < 12) {
                Out.put(tag + ".fromLong(" + value + ")", Out.hex(result));
            }
        }
        Out.put(tag + ".fromLong.digest", fromLong);
        Out.Digest fromInt = new Out.Digest();
        for (long value : cases.ints) {
            fromInt.add(Float.floatToRawIntBits(casts.fromInt((int) value)));
        }
        Out.put(tag + ".fromInt.digest", fromInt);
        Out.Digest toIntD = new Out.Digest();
        Out.Digest toIntF = new Out.Digest();
        Out.Digest toLongD = new Out.Digest();
        Out.Digest toLongF = new Out.Digest();
        int exceptions = 0;
        for (int i = 0; i < cases.doubles.size(); i++) {
            double value = cases.doubles.get(i);
            // The float operand as StrictMath263 makes it: one rounding (TeaVM JS keeps floats unrounded).
            float single = casts.fromDouble(value);
            int a = casts.toInt(value);
            int b = casts.toInt(single);
            // The unpatched TeaVM JavaScript cast of NaN or an infinity to long throws a RangeError
            // (BigInt); the product's postprocess-teavm-js.py makes it saturate.
            long c;
            long d;
            try {
                c = casts.toLong(value);
            } catch (RuntimeException e) {
                c = 0xBADL;
                exceptions++;
            }
            try {
                d = casts.toLong(single);
            } catch (RuntimeException e) {
                d = 0xBADL;
                exceptions++;
            }
            toIntD.add(a);
            toIntF.add(b);
            toLongD.add(c);
            toLongF.add(d);
            if (i < cases.special) {
                Out.put(tag + ".conv(" + Out.hex(value) + ")", a + " " + b + " " + c + " " + d);
            }
        }
        Out.put(tag + ".toInt(double).digest", toIntD);
        Out.put(tag + ".toInt(float).digest", toIntF);
        Out.put(tag + ".toLong(double).digest", toLongD);
        Out.put(tag + ".toLong(float).digest", toLongF);
        Out.put(tag + ".toLong.exceptions", exceptions);
        Out.Digest sums = new Out.Digest();
        for (double[] values : cases.sums) {
            sums.add(Double.doubleToRawLongBits(casts.sum(DoubleStream.of(values))));
        }
        Out.put(tag + ".sum.digest", sums);
        double[] overflow = {Double.MAX_VALUE, Double.MAX_VALUE, -1.0};
        Out.put(tag + ".sum.overflow", Out.hex(casts.sum(DoubleStream.of(overflow))));
    }
}
