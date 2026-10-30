package dev.gaius.tools.patches.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The ASM helpers that more than one domain of the Minecraft262BrowserPatcher step uses (DESIGN
 * D16 {@code patches/<domain>}), the {@code modern} patch set arm: class reads and writes, method
 * lookup and replacement, call rewrites, instruction navigation, worldgen loop pulses, and the
 * browser classes several domains call. Not a domain: it holds no patch. Moved verbatim out of
 * Minecraft262BrowserPatcher, which keeps the registrations; the domain classes import these
 * helpers statically.
 */
public final class ModernPatchSupport {
    private ModernPatchSupport() {
    }

    public static final String JDK_COMPAT = "dev/gaius/browser/BrowserJdkCompat";
    public static final String WORLDGEN_SCHEDULER =
            "dev/gaius/browser/BrowserWorldgenScheduler";

    public static FieldInsnNode fieldBefore(AbstractInsnNode instruction) {
        AbstractInsnNode previous = previousOpcode(instruction);
        return previous instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETFIELD ? field : null;
    }

    public static void requireReceiverLoad(
            String target, AbstractInsnNode receiver, FieldInsnNode field) {
        if (!(receiver instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 0
                || field == null) {
            throw new IllegalStateException(target + " receiver shape changed");
        }
    }

    public static int replaceCall(
            MethodNode method,
            int oldOpcode,
            String oldOwner,
            String oldName,
            String oldDescriptor,
            int newOpcode,
            String newOwner,
            String newName,
            String newDescriptor) {
        int replacements = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == oldOpcode
                    && call.owner.equals(oldOwner)
                    && call.name.equals(oldName)
                    && call.desc.equals(oldDescriptor)) {
                call.setOpcode(newOpcode);
                call.owner = newOwner;
                call.name = newName;
                call.desc = newDescriptor;
                call.itf = false;
                replacements++;
            }
        }
        return replacements;
    }

    public static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    public static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    public static void replaceVoid(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 0, method.maxLocals);
    }

    public static void replaceBoolean(MethodNode method, boolean value) {
        InsnList code = new InsnList();
        code.add(new InsnNode(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 1, method.maxLocals);
    }

    public static void replace(
            MethodNode method, InsnList code, int maxStack, int maxLocals) {
        method.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
        method.instructions = code;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = maxStack;
        method.maxLocals = maxLocals;
    }

    public static void requireOne(String target, int count) {
        if (count != 1) {
            throw new IllegalStateException(target + " changed: " + count);
        }
    }

    public static void requireWorldgenLoopPulses(
            String target, MethodNode method, int expectedPulses, String pulseMethod) {
        int pulses = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof JumpInsnNode jump
                    && method.instructions.indexOf(jump.label)
                            < method.instructions.indexOf(instruction)) {
                AbstractInsnNode previous = previousOpcode(jump);
                if (!(previous instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !call.owner.equals(WORLDGEN_SCHEDULER)
                        || !call.name.equals(pulseMethod)
                        || !call.desc.equals("()V")) {
                    method.instructions.insertBefore(
                            jump,
                            new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    WORLDGEN_SCHEDULER,
                                    pulseMethod,
                                    "()V",
                                    false));
                }
                pulses++;
            }
        }
        if (pulses != expectedPulses) {
            throw new IllegalStateException(
                    target + " loop backedges changed: " + pulses
                            + " (expected " + expectedPulses + ")");
        }
    }

    public static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + descriptor + " was not found"));
    }

    public static ClassNode read(String jarPath, String entryName) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " was not found");
            }
            try (var stream = jar.getInputStream(entry)) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    public static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    public static void writeComputeFrames(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
