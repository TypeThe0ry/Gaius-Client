import dev.gaius.tools.patches.worldgen.WorldgenStrictMath;
import dev.gaius.tools.ArchivePaths;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.DoubleStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Checks the WorldgenStrictMath output of a client jar and builds the JVM twin of BrowserStrictMath.
 *
 * <pre>java StrictMathCheck CLIENT_JAR REWRITTEN_ROOT BrowserStrictMath.class TWIN_ROOT</pre>
 *
 * <ol>
 *   <li>every scope class with float arithmetic, a float conversion or a DoubleStream sum was
 *       rewritten; the rewritten methods pass ASM's BasicVerifier, keep no raw conversion, and
 *       round every float operation and every float that comes from outside the scope;</li>
 *   <li>the twin replaces the two JSBody natives (Math.fround) with float casts, which round
 *       exactly the same way, and keeps the Java helpers as compiled;</li>
 *   <li>the twin's conversions and compensated sum equal the JVM's casts and DoubleStream.sum.</li>
 * </ol>
 * strict-math-check.sh then runs the golden harness on the rewritten classes and the twin.
 */
public final class StrictMathCheck {
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: StrictMathCheck CLIENT_JAR REWRITTEN_ROOT HELPER_CLASS TWIN_ROOT");
        }
        Path rewritten = Path.of(args[1]);
        int classes = checkRewrite(args[0], rewritten);
        Path twin = Path.of(args[3]);
        writeTwin(Path.of(args[2]), twin);
        int conversions = checkTwin(twin);
        if (!failures.isEmpty()) {
            failures.stream().limit(40).forEach(failure -> System.err.println("strict math: " + failure));
            System.err.println("strict math: " + failures.size() + " problem(s)");
            System.exit(1);
        }
        System.out.println("strict math: " + classes + " rewritten classes verified, " + conversions
                + " helper cases equal the JVM");
    }

    private static int checkRewrite(String jar, Path root) throws IOException {
        int classes = 0;
        try (ZipFile zip = new ZipFile(jar)) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class") || !WorldgenStrictMath.inScope(name.substring(0, name.length() - 6))) {
                    continue;
                }
                ClassNode original;
                try (InputStream input = zip.getInputStream(entry)) {
                    original = read(input.readAllBytes());
                }
                Path patched = ArchivePaths.resolveEntry(root, name);
                if (!Files.isRegularFile(patched)) {
                    if (needsRewrite(original)) {
                        failures.add(original.name + " has float math but was not rewritten");
                    }
                    continue;
                }
                classes++;
                ClassNode node = read(Files.readAllBytes(patched));
                for (MethodNode method : node.methods) {
                    checkMethod(node.name, method);
                }
            }
        }
        if (classes < 100) {
            failures.add("only " + classes + " rewritten classes");
        }
        return classes;
    }

    private static boolean needsRewrite(ClassNode node) {
        for (MethodNode method : node.methods) {
            boolean external = (method.access & Opcodes.ACC_PRIVATE) == 0 || (method.access & Opcodes.ACC_SYNTHETIC) != 0;
            if (external && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                    && hasFloatParameter(method.desc)) {
                return true;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                int opcode = instruction.getOpcode();
                if (rounded(opcode) || conversion(opcode) || fromOutside(instruction)
                        || instruction instanceof MethodInsnNode sum && sum.name.equals("sum")
                                && sum.owner.equals("java/util/stream/DoubleStream")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasFloatParameter(String desc) {
        for (Type type : Type.getArgumentTypes(desc)) {
            if (type.getSort() == Type.FLOAT) {
                return true;
            }
        }
        return false;
    }

    /** A float produced by a method or field owned outside the scope. */
    private static boolean fromOutside(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return instruction instanceof MethodInsnNode call && call.desc.endsWith(")F")
                && !WorldgenStrictMath.inScope(call.owner) && !call.owner.equals(WorldgenStrictMath.HELPER)
                || instruction instanceof FieldInsnNode field && field.desc.equals("F")
                        && (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC)
                        && !WorldgenStrictMath.inScope(field.owner);
    }

    private static void checkMethod(String owner, MethodNode method) {
        String label = owner + "." + method.name + method.desc;
        try {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(owner, method);
        } catch (AnalyzerException exception) {
            failures.add(label + ": " + exception.getMessage());
        }
        checkParameters(label, method);
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (conversion(opcode)) {
                failures.add(label + ": raw conversion opcode " + opcode + " left");
            }
            if ((rounded(opcode) || fromOutside(instruction)) && !roundsNext(instruction)) {
                failures.add(label + ": float result at " + method.instructions.indexOf(instruction)
                        + " is not rounded");
            }
            if (instruction instanceof MethodInsnNode call && call.owner.equals("java/util/stream/DoubleStream")
                    && call.name.equals("sum")) {
                failures.add(label + ": DoubleStream.sum left");
            }
        }
    }

    /** Methods callable from outside the scope must start by rounding each float parameter. */
    private static void checkParameters(String label, MethodNode method) {
        boolean external = (method.access & Opcodes.ACC_PRIVATE) == 0 || (method.access & Opcodes.ACC_SYNTHETIC) != 0;
        if (!external || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return;
        }
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) {
                code.add(instruction);
            }
        }
        int slot = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        int at = 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.getSort() == Type.FLOAT) {
                boolean ok = at + 2 < code.size()
                        && code.get(at) instanceof VarInsnNode load && load.getOpcode() == Opcodes.FLOAD && load.var == slot
                        && roundsNext(code.get(at))
                        && code.get(at + 2) instanceof VarInsnNode store && store.getOpcode() == Opcodes.FSTORE
                        && store.var == slot;
                if (!ok) {
                    failures.add(label + ": float parameter in slot " + slot + " is not rounded on entry");
                }
                at += 3;
            }
            slot += argument.getSize();
        }
    }

    private static boolean roundsNext(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next instanceof MethodInsnNode call && call.owner.equals(WorldgenStrictMath.HELPER)
                && call.name.equals("round") && call.desc.equals("(F)F");
    }

    private static boolean rounded(int opcode) {
        return opcode == Opcodes.FADD || opcode == Opcodes.FSUB || opcode == Opcodes.FMUL || opcode == Opcodes.FDIV;
    }

    private static boolean conversion(int opcode) {
        return switch (opcode) {
            case Opcodes.D2F, Opcodes.L2F, Opcodes.I2F, Opcodes.D2I, Opcodes.F2I, Opcodes.D2L, Opcodes.F2L -> true;
            default -> false;
        };
    }

    /** The helper with its JSBody natives (Math.fround) turned into float casts. */
    private static void writeTwin(Path helper, Path root) throws IOException {
        ClassNode node = read(Files.readAllBytes(helper));
        int natives = 0;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_NATIVE) == 0) {
                continue;
            }
            InsnList code = new InsnList();
            switch (method.desc) {
                case "(F)F" -> code.add(new VarInsnNode(Opcodes.FLOAD, 0));
                case "(D)F" -> {
                    code.add(new VarInsnNode(Opcodes.DLOAD, 0));
                    code.add(new InsnNode(Opcodes.D2F));
                }
                default -> throw new IllegalStateException("unexpected native " + method.name + method.desc);
            }
            code.add(new InsnNode(Opcodes.FRETURN));
            method.access &= ~Opcodes.ACC_NATIVE;
            method.instructions = code;
            method.maxStack = 2;
            method.maxLocals = 2;
            natives++;
        }
        if (natives != 2) {
            failures.add("BrowserStrictMath has " + natives + " natives, expected round and fromDouble");
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static int checkTwin(Path root) throws Exception {
        int cases = 0;
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            Class<?> helper = loader.loadClass(WorldgenStrictMath.HELPER.replace('/', '.'));
            Method fromLong = helper.getMethod("fromLong", long.class);
            Method fromInt = helper.getMethod("fromInt", int.class);
            Method toIntD = helper.getMethod("toInt", double.class);
            Method toIntF = helper.getMethod("toInt", float.class);
            Method toLongD = helper.getMethod("toLong", double.class);
            Method toLongF = helper.getMethod("toLong", float.class);
            Method sum = helper.getMethod("sum", DoubleStream.class);
            Random random = new Random(263);
            List<Long> longs = new ArrayList<>(List.of(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L << 53, -(1L << 53),
                    (1L << 53) + 1, (1L << 62) + (1L << 38) + 1, (1L << 62) + (1L << 38), (1L << 62) + (1L << 38) - 1));
            for (int i = 0; i < 200_000; i++) {
                long value = random.nextLong() >> random.nextInt(64);
                longs.add(value);
                longs.add(value | 1);
                longs.add(value & ~0xFFFFFFFFFFL | 0x8000000000L);
            }
            for (long value : longs) {
                cases++;
                expect("fromLong(" + value + ")", (float) value, (Float) fromLong.invoke(null, value));
            }
            for (int i = 0; i < 100_000; i++) {
                int value = random.nextInt() >> random.nextInt(32);
                cases++;
                expect("fromInt(" + value + ")", (float) value, (Float) fromInt.invoke(null, value));
            }
            List<Double> doubles = new ArrayList<>(List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    0.0, -0.0, 2147483647.0, 2147483647.5, 2147483648.0, -2147483648.0, -2147483648.5,
                    -2147483649.0, 9.223372036854775807E18, -9.223372036854775808E18, 1e19, -1e19, 1e300, -0.5));
            for (int i = 0; i < 100_000; i++) {
                doubles.add((random.nextDouble() - 0.5) * Math.pow(2, random.nextInt(140) - 4));
            }
            for (double value : doubles) {
                cases += 4;
                float single = (float) value;
                expect("toInt(" + value + ")", (int) value, (Integer) toIntD.invoke(null, value));
                expect("toInt(" + single + "f)", (int) single, (Integer) toIntF.invoke(null, single));
                expect("toLong(" + value + ")", (long) value, (Long) toLongD.invoke(null, value));
                expect("toLong(" + single + "f)", (long) single, (Long) toLongF.invoke(null, single));
            }
            double[][] sumEdges = {
                    { Double.NaN },
                    { Double.POSITIVE_INFINITY },
                    { Double.NEGATIVE_INFINITY },
                    { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY },
                    { Double.MAX_VALUE, Double.MAX_VALUE },
                    { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE },
                    { Double.MAX_VALUE, -Double.MAX_VALUE, 1.0 },
                    { Double.MIN_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE },
                    { Double.MIN_NORMAL, Double.MIN_VALUE, -Double.MIN_NORMAL },
                    { 1e-300, -1e-300, Double.MIN_VALUE },
                    { 1e300, -1e300, 1e-300 },
                    { -0.0, -0.0 },
                    { Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE, -Double.MAX_VALUE }
            };
            for (int i = 0; i < sumEdges.length; i++) {
                double[] values = sumEdges[i];
                cases++;
                expect("sum edge " + i, DoubleStream.of(values).sum(),
                        (Double) sum.invoke(null, DoubleStream.of(values)));
            }
            for (int i = 0; i < 50_000; i++) {
                double[] values = new double[random.nextInt(129)];
                for (int j = 0; j < values.length; j++) {
                    switch (i & 3) {
                        case 0 -> values[j] = random.nextGaussian()
                                * Math.pow(10, random.nextInt(617) - 308);
                        case 1 -> {
                            double candidate = Double.longBitsToDouble(random.nextLong());
                            values[j] = Double.isFinite(candidate) ? candidate : 0.0;
                        }
                        case 2 -> values[j] = Math.scalb(random.nextDouble() * 2.0 - 1.0,
                                random.nextInt(2099) - 1074);
                        default -> values[j] = sumEdges[random.nextInt(sumEdges.length)][0];
                    }
                }
                cases++;
                expect("random sum " + i, DoubleStream.of(values).sum(),
                        (Double) sum.invoke(null, DoubleStream.of(values)));
            }
        }
        return cases;
    }

    private static void expect(String label, Object expected, Object actual) {
        // Java defines a canonical bit representation for NaN; its raw sign/payload may vary.
        boolean same = expected instanceof Float f ? Float.floatToRawIntBits(f) == Float.floatToRawIntBits((Float) actual)
                : expected instanceof Double d ? Double.doubleToLongBits(d) == Double.doubleToLongBits((Double) actual)
                : expected.equals(actual);
        if (!same) {
            String bits = expected instanceof Double d
                    ? " (bits " + Long.toHexString(Double.doubleToRawLongBits(d)) + " vs "
                            + Long.toHexString(Double.doubleToRawLongBits((Double) actual)) + ")"
                    : "";
            failures.add(label + ": JVM " + expected + ", helper " + actual + bits);
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
