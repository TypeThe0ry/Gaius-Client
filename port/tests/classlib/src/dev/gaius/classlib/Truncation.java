package dev.gaius.classlib;

/**
 * Java's narrowing conversions (JLS 5.1.3): float/double to int/long saturate, NaN becomes 0.
 * WebAssembly has saturating truncation (trunc_sat) and trapping truncation (trunc); a build
 * that emits the trapping form for a Java cast traps on NaN, infinities and out-of-range
 * values. The TeaVM JavaScript output wraps instead of saturating (BrowserStrictMath covers the
 * 26.3 worldgen classes). Values are read through {@link #opaque} so that neither javac nor
 * TeaVM folds the casts at compile time; the inputs are double literals (no parsing, which the
 * FloatStrings section checks on its own).
 */
final class Truncation {
    static volatile long sink;
    static volatile double cell;
    private static final double[] DOUBLES = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0,
        -0.0, 0.9999999, -0.9999999, 1.5, -1.5, 2147483647.0, 2147483647.5, 2147483648.0, -2147483648.0,
        -2147483648.9, -2147483649.0, 4294967296.0, 4294967297.5, 1e10, -1e10, 9.223372036854775807E18,
        9.2233720368547748E18, -9.223372036854775808E18, -9.2233720368547779E18, 1e19, -1e19, 1e300, -1e300,
        4.9E-324, 65535.9, 65536.0, -32768.5, 127.9, 128.0, -129.0, 1.8446744073709552E19, 0x1.fffffffffffffp52,
        -0x1p63, 0x1p31, -0x1p31 - 1, 0x1p63};

    private Truncation() {
    }

    static double opaque(double value) {
        cell = value;
        return cell;
    }

    static double opaque(String text) {
        return Double.parseDouble(text);
    }

    static void run() {
        for (double literal : DOUBLES) {
            String text = Out.hex(literal);
            double d = opaque(literal);
            float f = (float) d;
            Out.check("d2i(" + text + ")", () -> (int) d);
            Out.check("d2l(" + text + ")", () -> (long) d);
            Out.check("f2i(" + text + ")", () -> (int) f);
            Out.check("f2l(" + text + ")", () -> (long) f);
            Out.check("d2c(" + text + ")", () -> (int) (char) d);
            Out.check("d2s(" + text + ")", () -> (short) d);
            Out.check("d2b(" + text + ")", () -> (byte) d);
            Out.check("f2c(" + text + ")", () -> (int) (char) f);
            Out.check("d2f(" + text + ")", () -> Out.hex(f));
            Out.check("d2i2d(" + text + ")", () -> Out.hex((double) (int) d));
        }
        long[] longs = {0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL,
            0x100000000L, -0x80000001L, (1L << 53) + 1, (1L << 54) + 3, (1L << 62) + (1L << 38) + 1,
            0x7FFFFF7FFFFFFFFFL, -0x7FFFFF7FFFFFFFFFL};
        for (long raw : longs) {
            long v = Long.parseLong(Long.toString(raw));
            Out.check("l2i(" + v + ")", () -> (int) v);
            Out.check("l2s(" + v + ")", () -> (short) v);
            Out.check("l2c(" + v + ")", () -> (int) (char) v);
            Out.check("l2f(" + v + ")", () -> Out.hex((float) v));
            Out.check("l2d(" + v + ")", () -> Out.hex((double) v));
        }
        int[] ints = {0, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 16777217, -16777217, 0x7FFFFFC0, 65535, 65536,
            -32769, 128, -129};
        for (int raw : ints) {
            int v = Integer.parseInt(Integer.toString(raw));
            Out.check("i2f(" + v + ")", () -> Out.hex((float) v));
            Out.check("i2s(" + v + ")", () -> (short) v);
            Out.check("i2c(" + v + ")", () -> (int) (char) v);
            Out.check("i2b(" + v + ")", () -> (byte) v);
            Out.check("i2d(" + v + ")", () -> Out.hex((double) v));
        }
        // Integer arithmetic that JavaScript numbers would not keep exact.
        int a = Integer.parseInt("2147483647");
        int b = Integer.parseInt("65537");
        Out.check("imul", () -> a * b);
        Out.check("iadd.overflow", () -> a + 1);
        // MIN_VALUE / -1 and division by zero are trap sections (Traps): wasm's i32.div_s traps.
        Out.check("idiv", () -> (Integer.parseInt("-7") / 2) + "," + (Integer.parseInt("-7") % 2) + ","
                + (Integer.parseInt("7") / -2) + "," + (Integer.parseInt("7") % -2));
        Out.check("ishift", () -> (1 << Integer.parseInt("33")) + "," + (-1 >>> Integer.parseInt("28")) + ","
                + (-16 >> Integer.parseInt("2")));
        long la = Long.parseLong("9223372036854775807");
        long lb = Long.parseLong("3037000500");
        Out.check("lmul", () -> la * lb);
        Out.check("ldiv", () -> (Long.parseLong("-7000000000") / 3) + "," + (Long.parseLong("-7000000000") % 3));
        Out.check("lshift", () -> (1L << Integer.parseInt("65")) + "," + (-1L >>> Integer.parseInt("60")));
        Out.check("lcmp", () -> Long.compare(Long.MIN_VALUE, Long.parseLong("1")));
        Out.check("dcmp.nan", () -> (opaque("NaN") < 1) + "," + (opaque("NaN") > 1) + "," + Double.compare(opaque("NaN"), 1)
                + "," + Double.compare(0.0, opaque("-0.0")));
        Out.check("drem", () -> Out.hex(opaque("5.5") % opaque("-2")) + "," + Out.hex(opaque("-0.0") % opaque("1")));
        Out.check("ddiv.zero", () -> Out.hex(opaque("1") / opaque("0")) + "," + Out.hex(opaque("-1") / opaque("0"))
                + "," + Out.hex(opaque("0") / opaque("0")));
    }
}
