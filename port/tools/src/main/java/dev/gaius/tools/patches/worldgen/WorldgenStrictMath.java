package dev.gaius.tools.patches.worldgen;

import dev.gaius.tools.ArchivePaths;
import dev.gaius.tools.BuildTarget;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Java float and cast semantics for the worldgen classes of the compiled-sampler generation: the
 * worldgen domain of the Minecraft263BrowserPatcher step ({@code patches/worldgen}),
 * work package P6. The step registers it under its historical id
 * ({@code StrictMath263.roundWorldgenArithmetic}) after its package domains, before the JDK API
 * redirects, so it also rounds what they inserted; the profiles come from the spec.
 *
 * <p>26.3 samples noise and density in float. TeaVM compiles float to a JS number without
 * rounding and casts to int/long without saturation, so the browser's Java worldgen computed in
 * double precision and generated different terrain than vanilla (and than the bit-exact wasm
 * kernels). This pass rewrites every class in {@link #SCOPE}:
 * <ul>
 *   <li>after {@code fadd fsub fmul fdiv} it calls {@code BrowserStrictMath.round(F)F};</li>
 *   <li>{@code d2f l2f i2f} become {@code fromDouble/fromLong/fromInt}, {@code d2i f2i d2l f2l}
 *       become the saturating {@code toInt/toLong};</li>
 *   <li>floats entering from outside the scope are rounded: results of calls to methods and
 *       loads of fields owned outside it (unboxing a codec's {@code Float}, for example), and
 *       the float parameters of methods callable from outside (not private, or synthetic);</li>
 *   <li>{@code DoubleStream.sum()} becomes the JDK's compensated sum ({@code BrowserStrictMath.sum}).</li>
 * </ul>
 * {@code frem}, {@code fneg}, float constants, float array loads (Float32Array) and float
 * comparisons are already exact in TeaVM and are left alone. Every rewrite keeps the operand
 * stack shape, so the existing stack map frames stay valid.
 *
 * <p>The WebAssembly GC build ({@link BuildTarget}) computes float in f32 and converts with
 * saturation (nontrapping conversions), so only the sum rewrite applies there: TeaVM's class
 * library sums a DoubleStream naively on both backends.
 *
 * <p>Standalone: {@code java dev.gaius.tools.patches.worldgen.WorldgenStrictMath <client.jar>
 * <output-root>} rewrites the scope of a jar into {@code output-root} (used by
 * {@code port/native/golden/strict-math-check.sh}, which proves the result is still vanilla on
 * the JVM).
 */
public final class WorldgenStrictMath {
    public static final String HELPER = "dev/gaius/browser/BrowserStrictMath";

    /** Class name prefixes of the worldgen code whose float math must be vanilla. */
    public static final List<String> SCOPE = List.of(
            "net/minecraft/world/level/levelgen/",
            "net/minecraft/world/level/biome/",
            "net/minecraft/data/worldgen/",
            "net/minecraft/util/valueproviders/",
            "net/minecraft/util/Mth",
            "net/minecraft/util/RandomSource");

    private WorldgenStrictMath() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: WorldgenStrictMath INPUT_JAR OUTPUT_ROOT");
        }
        Stats stats = rewrite(args[0], Path.of(args[1]));
        System.out.println("StrictMath263: " + stats);
    }

    /**
     * The registered patch: rewrites the scope of the chain's current classes and fails when it
     * found nothing to round. {@code sumsOnly} (the WebAssembly GC build, {@link BuildTarget})
     * keeps only the DoubleStream.sum rewrite.
     */
    public static void roundWorldgenArithmetic(String jar, Path root, boolean sumsOnly) throws IOException {
        Stats stats = rewrite(jar, root, sumsOnly);
        if (stats.classes == 0 || (sumsOnly ? stats.sums == 0 : stats.rounded == 0)) {
            throw new IllegalStateException("StrictMath263: no "
                    + (sumsOnly ? "DoubleStream sum" : "float arithmetic")
                    + " in the worldgen scope of " + jar + " (" + stats + ")");
        }
        System.out.println("StrictMath263: " + (sumsOnly ? "wasmgc, DoubleStream sums only: " : "")
                + stats);
    }

    public static boolean inScope(String owner) {
        for (String prefix : SCOPE) {
            if (owner.startsWith(prefix) && (prefix.endsWith("/") || owner.length() == prefix.length()
                    || owner.charAt(prefix.length()) == '$')) {
                return true;
            }
        }
        return false;
    }

    /** Rewrites every class of the scope, reading the chain's current version (root, then jar). */
    static Stats rewrite(String jar, Path root) throws IOException {
        return rewrite(jar, root, false);
    }

    /** As {@link #rewrite(String, Path)}; {@code sumsOnly} keeps only the DoubleStream.sum rewrite. */
    static Stats rewrite(String jar, Path root, boolean sumsOnly) throws IOException {
        Stats stats = new Stats();
        try (ZipFile zip = new ZipFile(jar)) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class") || !inScope(name.substring(0, name.length() - 6))) {
                    continue;
                }
                Path patched = ArchivePaths.resolveEntry(root, name);
                byte[] bytes;
                if (Files.isRegularFile(patched)) {
                    bytes = Files.readAllBytes(patched);
                } else {
                    try (InputStream input = zip.getInputStream(entry)) {
                        bytes = input.readAllBytes();
                    }
                }
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                int before = stats.total();
                for (MethodNode method : node.methods) {
                    if (sumsOnly) {
                        rewriteSums(method, stats);
                    } else {
                        rewrite(method, stats);
                    }
                }
                if (stats.total() > before) {
                    stats.classes++;
                    ClassWriter writer = new ClassWriter(0);
                    node.accept(writer);
                    Files.createDirectories(patched.getParent());
                    Files.write(patched, writer.toByteArray());
                }
            }
        }
        return stats;
    }

    static void rewrite(MethodNode method, Stats stats) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return;
        }
        List<AbstractInsnNode> instructions = new ArrayList<>();
        method.instructions.forEach(instructions::add);
        for (AbstractInsnNode instruction : instructions) {
            switch (instruction.getOpcode()) {
                case Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV -> {
                    roundAfter(method, instruction);
                    stats.rounded++;
                }
                case Opcodes.D2F -> replace(method, instruction, "fromDouble", "(D)F", stats);
                case Opcodes.L2F -> replace(method, instruction, "fromLong", "(J)F", stats);
                case Opcodes.I2F -> replace(method, instruction, "fromInt", "(I)F", stats);
                case Opcodes.D2I -> replace(method, instruction, "toInt", "(D)I", stats);
                case Opcodes.F2I -> replace(method, instruction, "toInt", "(F)I", stats);
                case Opcodes.D2L -> replace(method, instruction, "toLong", "(D)J", stats);
                case Opcodes.F2L -> replace(method, instruction, "toLong", "(F)J", stats);
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC,
                        Opcodes.INVOKEINTERFACE -> {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (isDoubleStreamSum(call)) {
                        method.instructions.set(call, helper("sum", "(Ljava/util/stream/DoubleStream;)D"));
                        stats.sums++;
                    } else if (call.desc.endsWith(")F") && !inScope(call.owner) && !call.owner.equals(HELPER)) {
                        roundAfter(method, instruction);
                        stats.boundary++;
                    }
                }
                case Opcodes.INVOKEDYNAMIC -> {
                    if (((InvokeDynamicInsnNode) instruction).desc.endsWith(")F")) {
                        roundAfter(method, instruction);
                        stats.boundary++;
                    }
                }
                case Opcodes.GETFIELD, Opcodes.GETSTATIC -> {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (field.desc.equals("F") && !inScope(field.owner)) {
                        roundAfter(method, instruction);
                        stats.boundary++;
                    }
                }
                default -> {
                }
            }
        }
        roundParameters(method, stats);
    }

    /** The DoubleStream.sum rewrite of {@link #rewrite(MethodNode, Stats)} alone. */
    static void rewriteSums(MethodNode method, Stats stats) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && isDoubleStreamSum(call)) {
                method.instructions.set(call, helper("sum", "(Ljava/util/stream/DoubleStream;)D"));
                stats.sums++;
            }
        }
    }

    private static boolean isDoubleStreamSum(MethodInsnNode call) {
        return call.owner.equals("java/util/stream/DoubleStream") && call.name.equals("sum")
                && call.desc.equals("()D");
    }

    /** Rounds the float parameters of a method that code outside the scope may call. */
    private static void roundParameters(MethodNode method, Stats stats) {
        boolean external = (method.access & Opcodes.ACC_PRIVATE) == 0
                || (method.access & Opcodes.ACC_SYNTHETIC) != 0;
        if (!external) {
            return;
        }
        InsnList entry = new InsnList();
        int slot = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.getSort() == Type.FLOAT) {
                entry.add(new VarInsnNode(Opcodes.FLOAD, slot));
                entry.add(helper("round", "(F)F"));
                entry.add(new VarInsnNode(Opcodes.FSTORE, slot));
                stats.parameters++;
            }
            slot += argument.getSize();
        }
        if (entry.size() > 0) {
            method.instructions.insert(entry);
            method.maxStack = Math.max(method.maxStack, 1);
        }
    }

    private static void roundAfter(MethodNode method, AbstractInsnNode instruction) {
        method.instructions.insert(instruction, helper("round", "(F)F"));
    }

    private static void replace(MethodNode method, AbstractInsnNode instruction, String name, String desc,
            Stats stats) {
        method.instructions.set(instruction, helper(name, desc));
        stats.casts++;
    }

    private static MethodInsnNode helper(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, name, desc, false);
    }

    static final class Stats {
        int classes;
        int rounded;
        int casts;
        int boundary;
        int parameters;
        int sums;

        int total() {
            return rounded + casts + boundary + parameters + sums;
        }

        @Override
        public String toString() {
            return classes + " classes, " + rounded + " float operations rounded, " + casts
                    + " conversions, " + boundary + " boundary floats, " + parameters
                    + " float parameters, " + sums + " DoubleStream sums";
        }
    }
}
