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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes desktop file-attribute and FileChannel branches from Apache Commons. */
public final class CommonsBrowserPatcher {
    private CommonsBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path ioJar = Path.of(args[0]);
        Path compressJar = Path.of(args[1]);
        Path ioRoot = Path.of(args[2]);
        Path compressRoot = Path.of(args[3]);
        patchPathUtils(ioJar, ioRoot.resolve("org/apache/commons/io/file/PathUtils.class"));
        patchTarEntry(compressJar,
                compressRoot.resolve(
                        "org/apache/commons/compress/archivers/tar/TarArchiveEntry.class"));
        patchFixedBlockOutput(compressJar,
                compressRoot.resolve(
                        "org/apache/commons/compress/utils/FixedLengthBlockOutputStream.class"));
    }

    private static void patchPathUtils(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/apache/commons/io/file/PathUtils.class");
        replaceFalse(find(node, "setDosReadOnly",
                "(Ljava/nio/file/Path;Z[Ljava/nio/file/LinkOption;)Z"));
        replaceFalse(find(node, "setPosixDeletePermissions",
                "(Ljava/nio/file/Path;Z[Ljava/nio/file/LinkOption;)Z"));
        replaceVoid(find(node, "setPosixReadOnlyFile",
                "(Ljava/nio/file/Path;Z[Ljava/nio/file/LinkOption;)V"));
        replaceFalse(find(node, "isPosix",
                "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"));
        MethodNode withPosix = find(node, "withPosixFileAttributes",
                "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;Z"
                        + "Lorg/apache/commons/io/function/IOFunction;)Ljava/lang/Object;");
        InsnList withoutPosix = new InsnList();
        withoutPosix.add(new VarInsnNode(Opcodes.ALOAD, 3));
        withoutPosix.add(new InsnNode(Opcodes.ACONST_NULL));
        withoutPosix.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "org/apache/commons/io/function/IOFunction",
                "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                true));
        withoutPosix.add(new InsnNode(Opcodes.ARETURN));
        replace(withPosix, withoutPosix);
        MethodNode setReadOnly = find(node, "setReadOnly",
                "(Ljava/nio/file/Path;Z[Ljava/nio/file/LinkOption;)Ljava/nio/file/Path;");
        InsnList returnPath = new InsnList();
        returnPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
        returnPath.add(new InsnNode(Opcodes.ARETURN));
        replace(setReadOnly, returnPath);

        MethodNode delete = find(node, "deleteFile",
                "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;"
                        + "[Lorg/apache/commons/io/file/DeleteOption;)"
                        + "Lorg/apache/commons/io/file/Counters$PathCounters;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/nio/file/Files",
                "deleteIfExists",
                "(Ljava/nio/file/Path;)Z",
                false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/apache/commons/io/file/Counters",
                "noopPathCounters",
                "()Lorg/apache/commons/io/file/Counters$PathCounters;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(delete, code);
        write(node, output);
    }

    private static void patchTarEntry(Path jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "org/apache/commons/compress/archivers/tar/TarArchiveEntry.class");
        replaceVoid(find(node, "readOsSpecificProperties",
                "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)V"));
        write(node, output);
    }

    private static void patchFixedBlockOutput(Path jar, Path output) throws IOException {
        String owner = "org/apache/commons/compress/utils/FixedLengthBlockOutputStream";
        ClassNode node = read(jar, owner + ".class");
        MethodNode constructor = find(node, "<init>", "(Ljava/io/OutputStream;I)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/io/OutputStream", "<init>", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/atomic/AtomicBoolean"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/concurrent/atomic/AtomicBoolean",
                "<init>",
                "()V",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "closed",
                "Ljava/util/concurrent/atomic/AtomicBoolean;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(
                Opcodes.NEW, owner + "$BufferAtATimeOutputChannel"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner + "$BufferAtATimeOutputChannel",
                "<init>",
                "(Ljava/io/OutputStream;"
                        + "Lorg/apache/commons/compress/utils/"
                        + "FixedLengthBlockOutputStream$1;)V",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "out", "Ljava/nio/channels/WritableByteChannel;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/nio/ByteBuffer",
                "allocate",
                "(I)Ljava/nio/ByteBuffer;",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "buffer", "Ljava/nio/ByteBuffer;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "blockSize", "I"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, code);
        write(node, output);
    }

    private static void replaceFalse(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code);
    }

    private static void replaceVoid(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code);
    }

    private static void replace(MethodNode method, InsnList code) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = 5;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + descriptor + " not found"));
    }

    private static ClassNode read(Path jarPath, String entryName) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            try (var stream = jar.getInputStream(jar.getEntry(entryName))) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
