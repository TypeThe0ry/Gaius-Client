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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Converts JDK AbstractSpliterator subclasses into direct Spliterator implementations. */
public final class AbstractSpliteratorBrowserPatcher {
    private static final String ABSTRACT = "java/util/Spliterators$AbstractSpliterator";

    private AbstractSpliteratorBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path jarPath = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
        PatchRegistry.configureOutput(outputRoot);
        PatchRegistry.runPart("AbstractSpliteratorBrowserPatcher.classOverrides", "REQUIRED OVERRIDE js wasmgc",
                () -> patchJar(jarPath, outputRoot));
    }

    private static void patchJar(Path jarPath, Path outputRoot) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (var stream = jar.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                if (entry.getName().equals(
                        "com/google/common/hash/ChecksumHashFunction$ChecksumMethodHandles.class")) {
                    patchChecksumMethodHandles(node);
                } else if (ABSTRACT.equals(node.superName)) {
                    patch(node);
                } else {
                    continue;
                }
                ClassWriter writer = new ClassWriter(0);
                node.accept(writer);
                Path output = ArchivePaths.resolveEntry(outputRoot, entry.getName());
                Files.createDirectories(output.getParent());
                Files.write(output, writer.toByteArray());
            }
        }
    }

    private static void patchChecksumMethodHandles(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("updateByteBuffer")
                    && method.desc.equals("(Ljava/util/zip/Checksum;Ljava/nio/ByteBuffer;)Z")) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                method.instructions.add(new InsnNode(Opcodes.ICONST_0));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
                method.maxStack = 1;
                method.maxLocals = 2;
            } else if (method.name.equals("updateByteBuffer")
                    && method.desc.equals("()Ljava/lang/invoke/MethodHandle;")) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
                method.maxStack = 1;
                method.maxLocals = 0;
            } else if (method.name.equals("<clinit>")) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                method.instructions.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "com/google/common/hash/ChecksumHashFunction$ChecksumMethodHandles",
                        "UPDATE_BB",
                        "Ljava/lang/invoke/MethodHandle;"));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
                method.maxStack = 1;
                method.maxLocals = 0;
            }
        }
    }

    private static void patch(ClassNode node) {
        String owner = node.name;
        node.superName = "java/lang/Object";
        node.signature = null;
        if (!node.interfaces.contains("java/util/Spliterator")) {
            node.interfaces.add("java/util/Spliterator");
        }
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "gaiusRemaining", "J", null, null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "gaiusCharacteristics", "I", null, null));
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>") || !method.desc.startsWith("(JI")) {
                continue;
            }
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.owner.equals(ABSTRACT)
                        && call.name.equals("<init>")
                        && call.desc.equals("(JI)V")) {
                    InsnList replacement = new InsnList();
                    replacement.add(new InsnNode(Opcodes.POP));
                    replacement.add(new InsnNode(Opcodes.POP2));
                    replacement.add(new MethodInsnNode(
                            Opcodes.INVOKESPECIAL,
                            "java/lang/Object",
                            "<init>",
                            "()V",
                            false));
                    replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    replacement.add(new VarInsnNode(Opcodes.LLOAD, 1));
                    replacement.add(new FieldInsnNode(
                            Opcodes.PUTFIELD, owner, "gaiusRemaining", "J"));
                    replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    replacement.add(new VarInsnNode(Opcodes.ILOAD, 3));
                    replacement.add(new FieldInsnNode(
                            Opcodes.PUTFIELD, owner, "gaiusCharacteristics", "I"));
                    method.instructions.insertBefore(call, replacement);
                    method.instructions.remove(call);
                    break;
                }
            }
        }
        node.methods.add(objectMethod("trySplit", "()Ljava/util/Spliterator;"));
        node.methods.add(fieldGetter(
                owner, "estimateSize", "()J", "gaiusRemaining", "J", Opcodes.LRETURN));
        node.methods.add(fieldGetter(
                owner, "characteristics", "()I",
                "gaiusCharacteristics", "I", Opcodes.IRETURN));
    }

    private static MethodNode objectMethod(String name, String descriptor) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        return method;
    }

    private static MethodNode fieldGetter(
            String owner, String name, String descriptor,
            String field, String fieldDescriptor, int returnOpcode) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, field, fieldDescriptor));
        method.instructions.add(new InsnNode(returnOpcode));
        method.maxStack = returnOpcode == Opcodes.LRETURN ? 2 : 1;
        method.maxLocals = 1;
        return method;
    }
}
