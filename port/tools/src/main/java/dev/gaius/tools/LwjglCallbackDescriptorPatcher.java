package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Removes native libffi descriptor initialization from LWJGL callback interfaces. */
public final class LwjglCallbackDescriptorPatcher {
    private static final String DESCRIPTOR_TYPE = "Lorg/lwjgl/system/Callback$Descriptor;";

    private LwjglCallbackDescriptorPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: LwjglCallbackDescriptorPatcher INPUT_JAR OUTPUT_ROOT");
        }
        Path outputRoot = Path.of(args[1]);
        List<String> patched = new ArrayList<>();
        try (ZipFile jar = new ZipFile(args[0])) {
            boolean leaveVersioned = MultiReleaseEntries.leaveVersionedEntries(jar);
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                if (leaveVersioned && MultiReleaseEntries.isVersioned(entry)) {
                    continue;
                }
                ClassNode node = new ClassNode();
                try (var stream = jar.getInputStream(entry)) {
                    new ClassReader(stream.readAllBytes()).accept(node, 0);
                }
                if (!patchDescriptorInitializer(node)) {
                    continue;
                }
                write(node, ArchivePaths.resolveEntry(outputRoot, entry.getName()));
                patched.add(node.name);
            }
        }
        System.out.println("Patched " + patched.size()
                + " LWJGL callback descriptors in " + Path.of(args[0]).getFileName());
    }

    private static boolean patchDescriptorInitializer(ClassNode node) {
        boolean hasDescriptor = node.fields.stream().anyMatch(field ->
                field.name.equals("DESCRIPTOR") && field.desc.equals(DESCRIPTOR_TYPE));
        if (!hasDescriptor) {
            return false;
        }
        MethodNode initializer = node.methods.stream()
                .filter(method -> method.name.equals("<clinit>") && method.desc.equals("()V"))
                .findFirst()
                .orElse(null);
        if (initializer == null || !usesMethodHandlesLookup(initializer)) {
            return false;
        }
        for (var instruction = initializer.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && (!field.owner.equals(node.name)
                            || !field.name.equals("DESCRIPTOR")
                            || !field.desc.equals(DESCRIPTOR_TYPE))) {
                throw new IllegalStateException(
                        "Unsupported callback initializer side effect: " + node.name + "." + field.name);
            }
        }
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, node.name, "DESCRIPTOR", DESCRIPTOR_TYPE));
        code.add(new InsnNode(Opcodes.RETURN));
        initializer.instructions = code;
        initializer.tryCatchBlocks.clear();
        if (initializer.localVariables != null) {
            initializer.localVariables.clear();
        }
        initializer.visibleLocalVariableAnnotations = null;
        initializer.invisibleLocalVariableAnnotations = null;
        initializer.maxStack = 1;
        initializer.maxLocals = 0;
        return true;
    }

    private static boolean usesMethodHandlesLookup(MethodNode method) {
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/invoke/MethodHandles")
                    && call.name.equals("lookup")
                    && call.desc.equals("()Ljava/lang/invoke/MethodHandles$Lookup;")) {
                return true;
            }
        }
        return false;
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
