package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Uses JOML's allocation-free multiply-add fallback in the browser runtime. */
public final class JomlMathPatcher {
    private static final String CLASS_ENTRY = "org/joml/Math.class";

    private JomlMathPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: JomlMathPatcher INPUT_JAR OUTPUT_CLASS");
        }

        byte[] input;
        try (ZipFile jar = new ZipFile(args[0])) {
            var entry = jar.getEntry(CLASS_ENTRY);
            if (entry == null) {
                throw new IllegalStateException(CLASS_ENTRY + " not found in " + args[0]);
            }
            try (var stream = jar.getInputStream(entry)) {
                input = stream.readAllBytes();
            }
        }

        ClassNode classNode = new ClassNode();
        new ClassReader(input).accept(classNode, 0);
        MethodNode floatFma = find(classNode, "fma", "(FFF)F");
        MethodNode doubleFma = find(classNode, "fma", "(DDD)D");
        replace(floatFma, floatMultiplyAdd(), 2, 3);
        replace(doubleFma, doubleMultiplyAdd(), 4, 6);

        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static MethodNode find(ClassNode classNode, String name, String descriptor) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        throw new IllegalStateException(classNode.name + "." + name + descriptor + " was not found");
    }

    private static void replace(MethodNode method, InsnList instructions, int maxStack, int maxLocals) {
        method.instructions = instructions;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = maxStack;
        method.maxLocals = maxLocals;
    }

    private static InsnList floatMultiplyAdd() {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.FLOAD, 0));
        instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        instructions.add(new InsnNode(Opcodes.FMUL));
        instructions.add(new VarInsnNode(Opcodes.FLOAD, 2));
        instructions.add(new InsnNode(Opcodes.FADD));
        instructions.add(new InsnNode(Opcodes.FRETURN));
        return instructions;
    }

    private static InsnList doubleMultiplyAdd() {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.DLOAD, 0));
        instructions.add(new VarInsnNode(Opcodes.DLOAD, 2));
        instructions.add(new InsnNode(Opcodes.DMUL));
        instructions.add(new VarInsnNode(Opcodes.DLOAD, 4));
        instructions.add(new InsnNode(Opcodes.DADD));
        instructions.add(new InsnNode(Opcodes.DRETURN));
        return instructions;
    }
}
