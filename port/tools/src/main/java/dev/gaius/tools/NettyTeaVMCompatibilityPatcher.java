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

/** Selects Netty's portable byte-array paths before TeaVM reachability analysis. */
public final class NettyTeaVMCompatibilityPatcher {
    private static final String PLATFORM = "io/netty/util/internal/PlatformDependent";
    private static final String HTTP_DECODER = "io/netty/handler/codec/http/HttpObjectDecoder";

    private NettyTeaVMCompatibilityPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path commonJar = Path.of(args[0]);
        Path httpJar = Path.of(args[1]);
        Path commonOutputRoot = Path.of(args[2]);
        Path httpOutputRoot = Path.of(args[3]);
        patchPlatformDependent(commonJar, commonOutputRoot.resolve(PLATFORM + ".class"));
        patchHttpObjectDecoder(httpJar, httpOutputRoot.resolve(HTTP_DECODER + ".class"));
    }

    private static void patchPlatformDependent(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, PLATFORM + ".class");

        InsnList getByte = new InsnList();
        getByte.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getByte.add(new VarInsnNode(Opcodes.ILOAD, 1));
        getByte.add(new InsnNode(Opcodes.BALOAD));
        getByte.add(new InsnNode(Opcodes.IRETURN));
        replace(find(node, "getByte", "([BI)B"), getByte);

        InsnList equals = new InsnList();
        loadArguments(equals, "([BI[BII)Z");
        equals.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, PLATFORM, "equalsSafe", "([BI[BII)Z", false));
        equals.add(new InsnNode(Opcodes.IRETURN));
        replace(find(node, "equals", "([BI[BII)Z"), equals);

        InsnList hashCode = new InsnList();
        loadArguments(hashCode, "([BII)I");
        hashCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, PLATFORM, "hashCodeAsciiSafe", "([BII)I", false));
        hashCode.add(new InsnNode(Opcodes.IRETURN));
        replace(find(node, "hashCodeAscii", "([BII)I"), hashCode);

        write(node, output);
        System.out.println("Forced Netty byte-array operations onto portable implementations");
    }

    private static void patchHttpObjectDecoder(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, HTTP_DECODER + ".class");
        MethodNode method = find(node, "langAsciiString", "([BII)Ljava/lang/String;");
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "java/nio/charset/StandardCharsets",
                "ISO_8859_1",
                "Ljava/nio/charset/Charset;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/String",
                "<init>",
                "([BIILjava/nio/charset/Charset;)V",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code);

        write(node, output);
        System.out.println("Replaced Netty's legacy high-byte String constructor");
    }

    private static void loadArguments(InsnList code, String descriptor) {
        int local = 0;
        for (org.objectweb.asm.Type argument
                : org.objectweb.asm.Type.getArgumentTypes(descriptor)) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
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
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " not found in " + jarPath);
            }
            try (var stream = jar.getInputStream(entry)) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void replace(MethodNode method, InsnList code) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
