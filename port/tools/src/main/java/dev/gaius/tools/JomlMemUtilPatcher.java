package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Removes JOML's unreachable JVM Unsafe backend from TeaVM's closed-world graph.
 */
public final class JomlMemUtilPatcher {
    private static final String CLASS_ENTRY = "org/joml/MemUtil.class";

    private JomlMemUtilPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: JomlMemUtilPatcher INPUT_JAR OUTPUT_CLASS");
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
        MethodNode target = null;
        for (MethodNode method : classNode.methods) {
            if (method.name.equals("createInstance")
                    && method.desc.equals("()Lorg/joml/MemUtil;")) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("MemUtil.createInstance() was not found");
        }

        target.instructions = browserSafeImplementation();
        target.tryCatchBlocks.clear();
        if (target.localVariables != null) {
            target.localVariables.clear();
        }
        target.visibleLocalVariableAnnotations = null;
        target.invisibleLocalVariableAnnotations = null;
        target.maxStack = 2;
        target.maxLocals = 0;

        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static InsnList browserSafeImplementation() {
        InsnList instructions = new InsnList();
        instructions.add(new TypeInsnNode(Opcodes.NEW, "org/joml/MemUtil$MemUtilNIO"));
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "org/joml/MemUtil$MemUtilNIO",
                "<init>",
                "()V",
                false));
        instructions.add(new InsnNode(Opcodes.ARETURN));
        return instructions;
    }
}
