package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Disables the desktop Tracy profiler before TeaVM can reach StackWalker. */
public final class JtracyBrowserPatcher {
    private static final String ENTRY = "com/mojang/jtracy/TracyClient.class";

    private JtracyBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        byte[] input;
        try (ZipFile jar = new ZipFile(args[0])) {
            var entry = jar.getEntry(ENTRY);
            if (entry == null) {
                throw new IllegalStateException("TracyClient.class was not found");
            }
            try (var stream = jar.getInputStream(entry)) {
                input = stream.readAllBytes();
            }
        }

        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        int beginZoneMethods = 0;
        boolean availabilityMethod = false;
        boolean sectionCategories = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("beginZone")
                    && method.desc.endsWith(")Lcom/mojang/jtracy/Zone;")) {
                replaceWithUnavailableZone(method);
                beginZoneMethods++;
            } else if (method.name.equals("isAvailable") && method.desc.equals("()Z")) {
                replaceWithFalse(method);
                availabilityMethod = true;
            } else if (method.name.equals("createSectionCategory")
                    && method.desc.equals(
                            "(Ljava/lang/String;)Lcom/mojang/jtracy/SectionCategory;")) {
                // jtracy 1.14 (Minecraft 26.3): the unloaded profiler already
                // returns UNAVAILABLE; make that unconditional so TeaVM never
                // reaches the TracyBindings natives behind the loaded branch.
                replaceWithUnavailableSectionCategory(method);
                sectionCategories = true;
            }
        }
        if (beginZoneMethods == 0 || !availabilityMethod) {
            throw new IllegalStateException(
                    "Expected TracyClient browser entry points were not found");
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]).resolve(ENTRY);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
        System.out.println("Patched TracyClient for browser use"
                + (sectionCategories ? " (including section categories)" : ""));
    }

    private static void replaceWithUnavailableSectionCategory(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "com/mojang/jtracy/SectionCategory",
                "UNAVAILABLE",
                "Lcom/mojang/jtracy/SectionCategory;"));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1);
    }

    private static void replaceWithUnavailableZone(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "com/mojang/jtracy/Zone",
                "UNAVAILABLE",
                "Lcom/mojang/jtracy/Zone;"));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1);
    }

    private static void replaceWithFalse(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 1);
    }

    private static void replace(MethodNode method, InsnList code, int maxStack) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.maxStack = maxStack;
    }
}
