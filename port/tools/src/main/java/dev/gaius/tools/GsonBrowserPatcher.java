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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Avoids generic Class metadata that TeaVM does not retain for Gson TypeTokens. */
public final class GsonBrowserPatcher {
    private static final String ENTRY = "com/google/gson/reflect/TypeToken.class";

    private GsonBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        ClassNode node = read(args[0]);
        MethodNode method = find(
                node,
                "getParameterized",
                "(Ljava/lang/reflect/Type;[Ljava/lang/reflect/Type;)"
                        + "Lcom/google/gson/reflect/TypeToken;");

        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, "com/google/gson/reflect/TypeToken"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Class"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/google/gson/internal/GsonTypes",
                "newParameterizedTypeWithOwner",
                "(Ljava/lang/reflect/Type;Ljava/lang/Class;[Ljava/lang/reflect/Type;)"
                        + "Ljava/lang/reflect/ParameterizedType;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "com/google/gson/reflect/TypeToken",
                "<init>",
                "(Ljava/lang/reflect/Type;)V",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));

        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.instructions.add(code);
        method.maxStack = 6;
        method.maxLocals = 2;

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
        System.out.println("Patched Gson TypeToken.getParameterized for browser use");
    }

    private static ClassNode read(String jarPath) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(ENTRY);
            if (entry == null) {
                throw new IllegalStateException(ENTRY + " was not found");
            }
            ClassNode node = new ClassNode();
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream).accept(node, 0);
            }
            return node;
        }
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        throw new IllegalStateException(name + descriptor + " was not found in " + node.name);
    }
}
