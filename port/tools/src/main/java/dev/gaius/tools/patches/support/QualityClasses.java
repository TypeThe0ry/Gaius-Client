package dev.gaius.tools.patches.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Class I/O and shape assertions shared by the graphics-quality patches of the render and ui
 * domains ({@code QualityTierPatches}, {@code GraphicsPresetStartupPatcher}). Not a domain: it
 * holds no patch.
 *
 * <p>Classes are read from {@code root} when an earlier patch of the same chain already wrote
 * them there, otherwise from {@code jar}, the same composition rule as the domain classes of the
 * Minecraft263BrowserPatcher step, so quality patches compose with the patches before and after
 * them. Classes are written without frame recomputation: every patch either inserts
 * straight-line code or adds new methods that carry their own (single, {@code F_SAME}) stack map
 * frame.
 */
public final class QualityClasses {
    private QualityClasses() {
    }

    public static boolean present(String jar, Path root, String name) throws IOException {
        if (Files.isRegularFile(root.resolve(name + ".class"))) {
            return true;
        }
        try (ZipFile zip = new ZipFile(jar)) {
            return zip.getEntry(name + ".class") != null;
        }
    }

    public static ClassNode read(String jar, Path root, String name) throws IOException {
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

    public static void write(ClassNode node, Path root) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    public static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new IllegalStateException(node.name + "." + name + desc + " was not found");
    }

    public static boolean has(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    public static void requireAbsent(ClassNode node, String name, String desc) {
        if (has(node, name, desc)) {
            throw new IllegalStateException(node.name + "." + name + desc
                    + " already exists; the quality patch ran twice on this class");
        }
    }

    public static void requireField(ClassNode node, String name, String desc) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return;
            }
        }
        throw new IllegalStateException(node.name + "." + name + ":" + desc + " was not found");
    }

    public static void requireMethod(ClassNode node, String name, String desc) {
        find(node, name, desc);
    }

    /** Every call in {@code method} matching opcode/owner/name/desc ({@code null} = any). */
    public static List<MethodInsnNode> calls(MethodNode method, int opcode, String owner, String name,
            String desc) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && (opcode < 0 || call.getOpcode() == opcode)
                    && (owner == null || call.owner.equals(owner))
                    && (name == null || call.name.equals(name))
                    && (desc == null || call.desc.equals(desc))) {
                found.add(call);
            }
        }
        return found;
    }

    public static MethodInsnNode single(List<MethodInsnNode> calls, String what) {
        if (calls.size() != 1) {
            throw new IllegalStateException(what + ": expected exactly one call, found "
                    + calls.size());
        }
        return calls.get(0);
    }

    /** The previous real instruction (labels, line numbers and frames skipped). */
    public static AbstractInsnNode previous(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    /** The next real instruction (labels, line numbers and frames skipped). */
    public static AbstractInsnNode next(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    public static boolean isStatic(MethodNode method) {
        return (method.access & Opcodes.ACC_STATIC) != 0;
    }
}
