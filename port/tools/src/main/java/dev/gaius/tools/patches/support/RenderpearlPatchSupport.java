package dev.gaius.tools.patches.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

/**
 * Class access that more than one domain of the Minecraft263BrowserPatcher step uses (the
 * render, input, platform, server-worker, network, audio and resources domain classes of that
 * step; {@code patches/<domain>}). Not a domain: it holds no patch.
 *
 * <p>Every patch of that step reads a class as the chain has patched it so far: the class an
 * earlier patch of the step wrote under {@code root} first, then the chain's current client jar
 * (contract C2), so two patches of one class compose instead of the later one overwriting the
 * earlier. The writers differ in what ASM recomputes; each patch keeps the one it was written
 * for, because its output bytes depend on it.
 */
public final class RenderpearlPatchSupport {
    private RenderpearlPatchSupport() {
    }

    /**
     * Reads {@code owner} as the chain has patched it so far: the class an earlier patch of the
     * step wrote under {@code root} first, then the jar (contract C2).
     */
    public static ClassNode read(String jar, Path root, String owner) throws IOException {
        Path written = root.resolve(owner + ".class");
        if (Files.isRegularFile(written)) {
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(written)).accept(node, 0);
            return node;
        }
        try (ZipFile zip = new ZipFile(jar)) {
            var entry = zip.getEntry(owner + ".class");
            if (entry == null) {
                throw new IOException("Missing class entry " + owner + ".class in " + jar);
            }
            try (var stream = zip.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(stream.readAllBytes()).accept(node, 0);
                return node;
            }
        }
    }

    /**
     * As {@link #read}, failing with an {@link IllegalStateException} when the jar lacks the
     * class. The root holds the earlier patchers' outputs (already folded into the jar, so
     * identical to its entries) and the classes earlier patches of the step wrote in this run,
     * which the jar does not have yet.
     */
    public static ClassNode readClass(String jar, Path root, String name) throws IOException {
        Path written = root.resolve(name + ".class");
        byte[] bytes;
        if (Files.isRegularFile(written)) {
            bytes = Files.readAllBytes(written);
        } else {
            try (ZipFile zip = new ZipFile(jar)) {
                var entry = zip.getEntry(name + ".class");
                if (entry == null) {
                    throw new IllegalStateException(name + ".class was not found in " + jar);
                }
                try (var input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    public static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new IllegalStateException(node.name + "." + name + desc + " was not found");
    }

    /**
     * Replaces a method body. Callers write the class with {@code computeFrames} when the new
     * body branches; straight-line bodies need no frames. For classes written by
     * {@link #writeClass}, which recomputes the maxima.
     */
    public static void replace(MethodNode method, InsnList code) {
        method.access &= ~(Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT);
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        int argumentSlots = Type.getArgumentsAndReturnSizes(method.desc) >> 2;
        if ((method.access & Opcodes.ACC_STATIC) != 0) {
            argumentSlots--;
        }
        method.maxLocals = Math.max(argumentSlots, 1);
        method.maxStack = 16;
    }

    /** Replaces a method body with its own maximum stack, for classes written without maxima. */
    public static void replace(MethodNode method, InsnList code, int maxStack) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = maxStack;
        method.maxLocals = Math.max(method.maxLocals, Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }

    /**
     * Writes without recomputing frames or maxima: the patch keeps both unchanged or, for an
     * added method, supplies them.
     */
    public static void write(ClassNode node, Path root) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    /** Writes {@code node} with the given ClassWriter flags (0 keeps its frames and maxima). */
    public static void write(ClassNode node, Path root, int flags) throws IOException {
        ClassWriter writer = new ClassWriter(flags);
        node.accept(writer);
        store(node, root, writer.toByteArray());
    }

    /**
     * Writes recomputing the maxima, and the frames too when {@code computeFrames} (with
     * {@code java/lang/Object} as every common superclass).
     */
    public static void writeClass(ClassNode node, Path root, boolean computeFrames) throws IOException {
        ClassWriter writer = computeFrames
                ? new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                    @Override
                    protected String getCommonSuperClass(String type1, String type2) {
                        return "java/lang/Object";
                    }
                }
                : new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    /** Stores the bytes of {@code node} under {@code root}. */
    public static void store(ClassNode node, Path root, byte[] bytes) throws IOException {
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
    }
}
