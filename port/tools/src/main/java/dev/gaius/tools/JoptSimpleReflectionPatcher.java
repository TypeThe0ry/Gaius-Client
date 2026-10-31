package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Bypasses reflection when jopt-simple converts browser launch File options. */
public final class JoptSimpleReflectionPatcher {
    private static final String ENTRY = "joptsimple/internal/Reflection.class";

    private JoptSimpleReflectionPatcher() {
    }

    public static void main(String[] args) throws IOException {
        byte[] input;
        try (ZipFile jar = new ZipFile(args[0])) {
            var entry = jar.getEntry(ENTRY);
            if (entry == null) {
                throw new IllegalStateException("jopt-simple Reflection.class was not found");
            }
            try (var stream = jar.getInputStream(entry)) {
                input = stream.readAllBytes();
            }
        }

        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        boolean alreadyPatched = node.methods.stream().anyMatch(method ->
                method.name.equals("converter")
                        && method.desc.equals(
                                "(Ljava/lang/Class;Ljoptsimple/internal/Reflection$Parser;)"
                                        + "Ljoptsimple/ValueConverter;"));
        boolean found = alreadyPatched;
        for (MethodNode method : node.methods) {
            if (!alreadyPatched
                    && method.name.equals("findConverter")
                    && method.desc.equals("(Ljava/lang/Class;)Ljoptsimple/ValueConverter;")) {
                LabelNode checkInteger = new LabelNode();
                LabelNode vanilla = new LabelNode();
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new LdcInsnNode(Type.getObjectType("java/io/File")));
                code.add(new JumpInsnNode(Opcodes.IF_ACMPNE, checkInteger));
                code.add(new TypeInsnNode(
                        Opcodes.NEW, "joptsimple/internal/BrowserFileValueConverter"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "joptsimple/internal/BrowserFileValueConverter",
                        "<init>",
                        "()V",
                        false));
                code.add(new InsnNode(Opcodes.ARETURN));
                code.add(checkInteger);
                code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new LdcInsnNode(Type.getObjectType("java/lang/Integer")));
                code.add(new JumpInsnNode(Opcodes.IF_ACMPNE, vanilla));
                code.add(new TypeInsnNode(
                        Opcodes.NEW, "joptsimple/internal/BrowserIntegerValueConverter"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "joptsimple/internal/BrowserIntegerValueConverter",
                        "<init>",
                        "()V",
                        false));
                code.add(new InsnNode(Opcodes.ARETURN));
                code.add(vanilla);
                code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(code);
                method.maxStack = Math.max(method.maxStack, 2);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("Reflection.findConverter was not found");
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
        System.out.println(alreadyPatched
                ? "Verified jopt-simple browser value conversion"
                : "Patched jopt-simple File conversion for browser use");
    }
}
