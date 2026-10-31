package dev.gaius.classlib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * java.lang.Math and StrictMath, bit for bit. StrictMath is fdlibm by specification, so every
 * runtime must match it exactly; Math may differ from StrictMath by the ulp bound of each method
 * (HotSpot uses x86 intrinsics for sin, cos, tan, exp, log, log10 and pow), which the register
 * of expected differences names per method.
 */
final class MathFunctions {
    private MathFunctions() {
    }

    static void run() {
        List<Double> inputs = new ArrayList<>(List.of(0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 2.0, 10.0, 0.1, 1e-300,
            -1e-300, 1e300, 4.9e-324, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Math.PI, Math.PI / 2, Math.PI / 4, 3 * Math.PI / 2, 1e6, 1e15 + 0.5,
            2.5, 3.5, -2.5, 0.49999999999999994, 4503599627370497.0, 9.223372036854775807E18, 700.0, -745.0,
            1e-5, 22.0, 1.0000001, 0.9999999));
        Rand random = new Rand(1234);
        for (int i = 0; i < 24; i++) {
            inputs.add((random.nextDouble() - 0.5) * Rand.pow2(random.nextInt(24) - 8));
        }
        for (int i = 0; i < 16; i++) {
            inputs.add((random.nextDouble() - 0.5) * 2000);
        }
        for (int i = 0; i < 8; i++) {
            inputs.add(random.nextDouble());
        }
        Map<String, DoubleUnaryOperator> unary = new LinkedHashMap<>();
        unary.put("Math.sin", Math::sin);
        unary.put("Math.cos", Math::cos);
        unary.put("Math.tan", Math::tan);
        unary.put("Math.asin", Math::asin);
        unary.put("Math.acos", Math::acos);
        unary.put("Math.atan", Math::atan);
        unary.put("Math.exp", Math::exp);
        unary.put("Math.log", Math::log);
        unary.put("Math.log10", Math::log10);
        unary.put("Math.sqrt", Math::sqrt);
        unary.put("Math.cbrt", Math::cbrt);
        unary.put("Math.sinh", Math::sinh);
        unary.put("Math.cosh", Math::cosh);
        unary.put("Math.tanh", Math::tanh);
        unary.put("Math.expm1", Math::expm1);
        unary.put("Math.log1p", Math::log1p);
        unary.put("Math.floor", Math::floor);
        unary.put("Math.ceil", Math::ceil);
        unary.put("Math.rint", Math::rint);
        unary.put("Math.abs", Math::abs);
        unary.put("Math.signum", Math::signum);
        unary.put("Math.ulp", Math::ulp);
        unary.put("Math.nextUp", Math::nextUp);
        unary.put("Math.nextDown", Math::nextDown);
        unary.put("Math.toRadians", Math::toRadians);
        unary.put("Math.toDegrees", Math::toDegrees);
        unary.put("Math.round", x -> Math.round(x));
        unary.put("Math.getExponent", x -> Math.getExponent(x));
        unary.put("Math.scalb3", x -> Math.scalb(x, 3));
        unary.put("Math.scalbM1080", x -> Math.scalb(x, -1080));
        unary.put("StrictMath.sin", StrictMath::sin);
        unary.put("StrictMath.cos", StrictMath::cos);
        unary.put("StrictMath.tan", StrictMath::tan);
        unary.put("StrictMath.asin", StrictMath::asin);
        unary.put("StrictMath.acos", StrictMath::acos);
        unary.put("StrictMath.atan", StrictMath::atan);
        unary.put("StrictMath.exp", StrictMath::exp);
        unary.put("StrictMath.log", StrictMath::log);
        unary.put("StrictMath.log10", StrictMath::log10);
        unary.put("StrictMath.sqrt", StrictMath::sqrt);
        unary.put("StrictMath.cbrt", StrictMath::cbrt);
        unary.put("StrictMath.sinh", StrictMath::sinh);
        unary.put("StrictMath.cosh", StrictMath::cosh);
        unary.put("StrictMath.tanh", StrictMath::tanh);
        unary.put("StrictMath.expm1", StrictMath::expm1);
        unary.put("StrictMath.log1p", StrictMath::log1p);
        unary.put("StrictMath.floor", StrictMath::floor);
        unary.put("StrictMath.ceil", StrictMath::ceil);
        unary.put("StrictMath.rint", StrictMath::rint);
        for (Map.Entry<String, DoubleUnaryOperator> function : unary.entrySet()) {
            DoubleUnaryOperator f = function.getValue();
            for (double x : inputs) {
                Out.check(function.getKey() + "(" + Out.hex(x) + ")", () -> Out.hex(f.applyAsDouble(x)));
            }
        }
        Map<String, DoubleBinaryOperator> binary = new LinkedHashMap<>();
        binary.put("Math.pow", Math::pow);
        binary.put("Math.atan2", Math::atan2);
        binary.put("Math.hypot", Math::hypot);
        binary.put("Math.IEEEremainder", Math::IEEEremainder);
        binary.put("Math.min", Math::min);
        binary.put("Math.max", Math::max);
        binary.put("Math.copySign", Math::copySign);
        binary.put("Math.nextAfter", Math::nextAfter);
        binary.put("rem", (a, b) -> a % b);
        binary.put("StrictMath.pow", StrictMath::pow);
        binary.put("StrictMath.atan2", StrictMath::atan2);
        binary.put("StrictMath.hypot", StrictMath::hypot);
        binary.put("StrictMath.IEEEremainder", StrictMath::IEEEremainder);
        double[][] pairs = {{2, 10}, {2, 0.5}, {-8, 1.0 / 3}, {0, -1}, {-0.0, -1}, {-0.0, -3}, {Double.NaN, 0},
            {1, Double.NaN}, {-1, Double.POSITIVE_INFINITY}, {1e300, 1e300}, {3e-200, 4e-200}, {0.0, -0.0},
            {-0.0, 0.0}, {Double.NaN, 1}, {10, 3}, {-10, 3}, {5.5, -2}, {1.0000001, 1e9}, {Math.E, Math.PI},
            {0.1, 0.2}, {7.5, 2.5}, {-7.5, 2.5}, {Double.MAX_VALUE, Double.MIN_VALUE}, {1, 0}, {-1, -0.0},
            {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}, {1.5, 1e-300}};
        for (Map.Entry<String, DoubleBinaryOperator> function : binary.entrySet()) {
            DoubleBinaryOperator f = function.getValue();
            for (double[] p : pairs) {
                Out.check(function.getKey() + "(" + Out.hex(p[0]) + "," + Out.hex(p[1]) + ")",
                        () -> Out.hex(f.applyAsDouble(p[0], p[1])));
            }
            for (int i = 0; i < 12; i++) {
                double a = (random.nextDouble() - 0.5) * Rand.pow2(random.nextInt(20) - 4);
                double b = (random.nextDouble() - 0.5) * Rand.pow2(random.nextInt(8) - 2);
                Out.check(function.getKey() + "(" + Out.hex(a) + "," + Out.hex(b) + ")",
                        () -> Out.hex(f.applyAsDouble(a, b)));
            }
        }
        double[][] fmas = {{2, 3, 4}, {0.1, 0.2, 0.3}, {1e308, 10, -1e308}, {Double.NaN, 0, 1},
            {Double.POSITIVE_INFINITY, 0, 1}, {0x1.0000000000001p0, 0x1.ffffffffffffep-1, -1}, {-0.0, 0.0, 0.0}};
        for (double[] t : fmas) {
            Out.check("Math.fma(" + Out.hex(t[0]) + "," + Out.hex(t[1]) + "," + Out.hex(t[2]) + ")",
                    () -> Out.hex(Math.fma(t[0], t[1], t[2])));
            float a = (float) t[0];
            float b = (float) t[1];
            float c = (float) t[2];
            Out.check("Math.fmaf(" + Out.hex(a) + "," + Out.hex(b) + "," + Out.hex(c) + ")",
                    () -> Out.hex(Math.fma(a, b, c)));
        }
        float[] floats = {0f, -0f, 0.5f, -0.5f, 1.5f, 2.5f, -2.5f, 0.49999997f, 8388609f, 1e10f, Float.NaN,
            Float.POSITIVE_INFINITY, 1.4E-45f, 3.4028235E38f, 0.1f, 16777215f};
        for (float x : floats) {
            String key = Out.hex(x);
            Out.check("Math.roundf(" + key + ")", () -> Math.round(x));
            Out.check("Math.absf(" + key + ")", () -> Out.hex(Math.abs(x)));
            Out.check("Math.signumf(" + key + ")", () -> Out.hex(Math.signum(x)));
            Out.check("Math.ulpf(" + key + ")", () -> Out.hex(Math.ulp(x)));
            Out.check("Math.nextUpf(" + key + ")", () -> Out.hex(Math.nextUp(x)));
            Out.check("Math.minf(" + key + ",-0)", () -> Out.hex(Math.min(x, -0f)));
            Out.check("Math.maxf(" + key + ",0)", () -> Out.hex(Math.max(x, 0f)));
            Out.check("sqrtf(" + key + ")", () -> Out.hex((float) Math.sqrt(x)));
            Out.check("mulf(" + key + ")", () -> Out.hex(x * 1.1f));
            Out.check("divf(" + key + ")", () -> Out.hex(x / 3f));
            Out.check("addf(" + key + ")", () -> Out.hex(x + 0.1f));
            Out.check("remf(" + key + ")", () -> Out.hex(x % 0.3f));
            Out.check("Math.getExponentf(" + key + ")", () -> Math.getExponent(x));
        }
        int[] ints = {0, 1, -1, 7, -7, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int a : ints) {
            for (int b : new int[] {3, -3, 1, -1}) {
                // MIN_VALUE / -1 is the trap section trap.floordiv-overflow (wasm's i32.div_s traps).
                if (a != Integer.MIN_VALUE || b != -1) {
                    Out.check("floorDiv(" + a + "," + b + ")", () -> Math.floorDiv(a, b));
                    Out.check("ceilDiv(" + a + "," + b + ")", () -> Math.ceilDiv(a, b));
                }
                Out.check("floorMod(" + a + "," + b + ")", () -> Math.floorMod(a, b));
                Out.check("addExact(" + a + "," + b + ")", () -> Math.addExact(a, b));
                Out.check("multiplyExact(" + a + "," + b + ")", () -> Math.multiplyExact(a, b));
            }
            Out.check("abs(" + a + ")", () -> Math.abs(a));
            Out.check("absExact(" + a + ")", () -> Math.absExact(a));
            Out.check("negateExact(" + a + ")", () -> Math.negateExact(a));
            Out.check("toIntExact(" + a + "L*3)", () -> Math.toIntExact(a * 3L));
        }
        long[] longs = {0, -1, Long.MAX_VALUE, Long.MIN_VALUE, 0x123456789ABCDEFL, -0x123456789ABCDEFL};
        for (long a : longs) {
            for (long b : longs) {
                // Math.multiplyHigh/unsignedMultiplyHigh are missing in TeaVM 0.15 (README).
                Out.check("lmul(" + a + "," + b + ")", () -> (a * b) + " " + Long.compareUnsigned(a, b) + " "
                        + Long.divideUnsigned(a, b == 0 ? 1 : b));
            }
            Out.check("floorDivL(" + a + ",-7)", () -> Math.floorDiv(a, -7L));
            Out.check("floorModL(" + a + ",7)", () -> Math.floorMod(a, 7L));
            Out.check("floorModLI(" + a + ",7)", () -> Math.floorMod(a, 7));
        }
        Out.check("clamp", () -> Math.clamp(5L, 1, 3) + "," + Math.clamp(2.5, 0.0, 1.0) + "," + Math.clamp(-0.0f, 0.0f, 1.0f));
    }
}
