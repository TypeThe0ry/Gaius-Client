package dev.gaius.tools.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * WebAssembly GC: {@code Math} and {@code StrictMath} floor, ceil, sqrt, rint, abs, min and max
 * of {@code double} (abs, min and max also of {@code float}) as single WebAssembly instructions.
 *
 * <p>TeaVM 0.15 compiles floor, ceil and sqrt as imports of JavaScript's {@code Math}
 * ({@code teavmMath}), so every call crossed from WebAssembly into JavaScript and back; abs, min
 * and max are short Java formulas with branches, and rint is {@code (double) round(x)}, which
 * rounds halves away from zero. The WebAssembly instructions compute exactly what Java
 * specifies: {@code f64.floor}, {@code f64.ceil} and {@code f64.sqrt} are the IEEE 754
 * operations (as JavaScript's floor, ceil and sqrt), {@code f64.nearest} rounds halves to even
 * as {@code Math.rint} does, {@code fNN.abs} clears the sign bit, and {@code fNN.min/max}
 * return NaN when either operand is NaN and order -0.0 below +0.0, as {@code Math.min/max}
 * require. Only NaN payloads can differ, which Java leaves unspecified. sin, cos, tan, exp, log,
 * pow and the other imports are left alone.
 *
 * <p>The patched {@code WasmGCIntrinsics.apply} calls {@link #register} with the inline
 * intrinsic registry of the code generation (see {@code TeaVMCoreBrowserPatcher}); the patcher
 * copies this class into the patched teavm-core jar. Like {@link GaiusTeaVMOptions} it only
 * depends on the JDK: the TeaVM interfaces are implemented with a {@link Proxy} and every TeaVM
 * object is handled reflectively. The JavaScript backend never loads it.
 */
public final class GaiusWasmMath {
    private static final String PACKAGE = "org.teavm.backend.wasm.";
    /** Method, descriptor, WasmFloatType, instruction (unary or binary), operation. */
    private static final String[][] OPERATIONS = {
        {"floor", "(D)D", "FLOAT64", "floatUnary", "FLOOR"},
        {"ceil", "(D)D", "FLOAT64", "floatUnary", "CEIL"},
        {"sqrt", "(D)D", "FLOAT64", "floatUnary", "SQRT"},
        {"rint", "(D)D", "FLOAT64", "floatUnary", "NEAREST"},
        {"abs", "(D)D", "FLOAT64", "floatUnary", "ABS"},
        {"abs", "(F)F", "FLOAT32", "floatUnary", "ABS"},
        {"min", "(DD)D", "FLOAT64", "floatBinary", "MIN"},
        {"max", "(DD)D", "FLOAT64", "floatBinary", "MAX"},
        {"min", "(FF)F", "FLOAT32", "floatBinary", "MIN"},
        {"max", "(FF)F", "FLOAT32", "floatBinary", "MAX"},
    };
    private static final String[] OWNERS = {"java.lang.Math", "java.lang.StrictMath"};

    private GaiusWasmMath() {
    }

    /** The {@code Class.method(descriptor)} keys {@link #register} adds, for checks and logs. */
    public static String[] methods() {
        String[] keys = new String[OWNERS.length * OPERATIONS.length];
        int index = 0;
        for (String owner : OWNERS) {
            for (String[] operation : OPERATIONS) {
                keys[index++] = owner + "." + operation[0] + operation[1];
            }
        }
        return keys;
    }

    /**
     * Registers the intrinsics in {@code inlineRegistry}, an
     * {@code IntrinsicRegistry<WasmGCInlineIntrinsic>}.
     */
    public static void register(Object inlineRegistry) {
        try {
            ClassLoader loader = inlineRegistry.getClass().getClassLoader();
            Class<?> intrinsic = load(loader, PACKAGE + "intrinsics.WasmGCInlineIntrinsic");
            Class<?> context = load(loader, PACKAGE + "intrinsics.WasmGCInlineIntrinsicContext");
            Class<?> builder = load(loader, PACKAGE + "model.instruction.WasmInstructionBuilder");
            Class<?> floatType = load(loader, PACKAGE + "model.instruction.WasmFloatType");
            Class<?> expr = load(loader, "org.teavm.ast.Expr");
            Class<?> invocation = load(loader, "org.teavm.ast.InvocationExpr");
            Class<?> methodReference = load(loader, "org.teavm.model.MethodReference");
            Class<?> registry = load(loader, "org.teavm.vm.intrinsic.IntrinsicRegistry");
            Method generate = context.getMethod("generate", builder, expr);
            Method arguments = invocation.getMethod("getArguments");
            Method parse = methodReference.getMethod("parse", String.class);
            Method registerIntrinsic = registry.getMethod("registerIntrinsic", methodReference, Object.class);
            for (String[] operation : OPERATIONS) {
                Class<?> operationType = load(loader, PACKAGE + "model.instruction.Wasm"
                        + (operation[3].equals("floatUnary") ? "FloatUnaryOperation" : "FloatBinaryOperation"));
                Method emit = builder.getMethod(operation[3], floatType, operationType);
                Object type = enumConstant(floatType, operation[2]);
                Object code = enumConstant(operationType, operation[4]);
                String label = "GaiusWasmMath." + operation[0] + operation[1];
                InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                    case "apply" -> {
                        // apply(InvocationExpr, WasmGCInlineIntrinsicContext, WasmInstructionBuilder):
                        // the operands in order, then the instruction.
                        for (Object argument : (List<?>) arguments.invoke(args[0])) {
                            generate.invoke(args[1], args[2], argument);
                        }
                        emit.invoke(args[2], type, code);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> label;
                    default -> throw new UnsupportedOperationException(method.toString());
                };
                Object instance = Proxy.newProxyInstance(intrinsic.getClassLoader(),
                        new Class<?>[] {intrinsic}, handler);
                for (String owner : OWNERS) {
                    Object reference = parse.invoke(null, owner + "." + operation[0] + operation[1]);
                    registerIntrinsic.invoke(inlineRegistry, reference, instance);
                }
            }
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("[gaius-teavm] wasm Math intrinsics: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[gaius-teavm] wasm Math intrinsics: the TeaVM API changed: " + e, e);
        }
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Object enumConstant(Class<?> type, String name) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalStateException("[gaius-teavm] wasm Math intrinsics: " + type.getName()
                + " has no " + name);
    }
}
