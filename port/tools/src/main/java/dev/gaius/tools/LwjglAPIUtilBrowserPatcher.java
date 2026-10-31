package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Browser-safe LWJGL APIUtil patches for dynamic native function lookup. */
public final class LwjglAPIUtilBrowserPatcher {
    private LwjglAPIUtilBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LwjglAPIUtilBrowserPatcher INPUT_JAR OUTPUT_ROOT");
        }
        ClassNode node = read(args[0], "org/lwjgl/system/APIUtil.class");
        int requiredReplacements = 0;
        int optionalReplacements = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("apiGetFunctionAddress")
                    && method.desc.equals("(Lorg/lwjgl/system/FunctionProvider;Ljava/lang/String;)J")) {
                replaceApiGetFunctionAddress(method);
                requiredReplacements++;
            } else if (method.name.equals("apiGetFunctionAddressOptional")
                    && method.desc.equals("(Lorg/lwjgl/system/SharedLibrary;Ljava/lang/String;)J")) {
                guardOptionalLibrary(method);
                optionalReplacements++;
            }
        }
        if (requiredReplacements != 1 || optionalReplacements != 1) {
            throw new IllegalStateException(
                    "Expected one required and one optional APIUtil method, patched "
                            + requiredReplacements + " required and " + optionalReplacements + " optional");
        }
        write(node, Path.of(args[1]).resolve("org/lwjgl/system/APIUtil.class"));
        System.out.println("Patched required and optional LWJGL APIUtil methods");
    }

    /**
     * LWJGL's optional lookup normally receives a native SharedLibrary.  The
     * browser GLFW path deliberately has no native library, so a null handle
     * is a valid "optional symbol unavailable" result rather than a reason to
     * dereference the handle.  Keep the original lookup/debug behavior for a
     * non-null handle and only add the null guard.
     */
    private static void guardOptionalLibrary(MethodNode method) {
        LabelNode libraryNonNull = new LabelNode(new Label());
        InsnList prefix = new InsnList();
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new JumpInsnNode(Opcodes.IFNONNULL, libraryNonNull));
        prefix.add(new InsnNode(Opcodes.LCONST_0));
        prefix.add(new InsnNode(Opcodes.LRETURN));
        prefix.add(libraryNonNull);
        prefix.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(prefix);
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static void replaceApiGetFunctionAddress(MethodNode method) {
        LabelNode providerNotNull = new LabelNode(new Label());
        LabelNode addressNonZero = new LabelNode(new Label());
        InsnList code = new InsnList();

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, providerNotNull));
        code.add(new InsnNode(Opcodes.LCONST_1));
        code.add(new InsnNode(Opcodes.LRETURN));

        code.add(providerNotNull);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "org/lwjgl/system/FunctionProvider",
                "getFunctionAddress",
                "(Ljava/lang/CharSequence;)J",
                true));
        code.add(new InsnNode(Opcodes.DUP2));
        code.add(new InsnNode(Opcodes.LCONST_0));
        code.add(new InsnNode(Opcodes.LCMP));
        code.add(new JumpInsnNode(Opcodes.IFNE, addressNonZero));
        code.add(new InsnNode(Opcodes.POP2));
        code.add(new InsnNode(Opcodes.LCONST_1));
        code.add(new InsnNode(Opcodes.LRETURN));

        code.add(addressNonZero);
        code.add(new InsnNode(Opcodes.LRETURN));

        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = 4;
        method.maxLocals = 2;
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IOException("Missing class in jar: " + entryName);
            }
            ClassNode node = new ClassNode();
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
