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

/** Selects Netty's SLF4J adapter without probing desktop logging frameworks. */
public final class NettyLoggerPatcher {
    private static final String ENTRY =
            "io/netty/util/internal/logging/InternalLoggerFactory.class";

    private NettyLoggerPatcher() {
    }

    public static void main(String[] args) throws IOException {
        byte[] input;
        try (ZipFile jar = new ZipFile(args[0])) {
            try (var stream = jar.getInputStream(jar.getEntry(ENTRY))) {
                input = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("newDefaultFactory")
                    && method.desc.equals(
                            "(Ljava/lang/String;)Lio/netty/util/internal/logging/InternalLoggerFactory;")) {
                InsnList code = new InsnList();
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "io/netty/util/internal/logging/Slf4JLoggerFactory",
                        "INSTANCE",
                        "Lio/netty/util/internal/logging/InternalLoggerFactory;"));
                code.add(new InsnNode(Opcodes.ARETURN));
                method.instructions = code;
                method.tryCatchBlocks.clear();
                method.localVariables = null;
                method.maxStack = 1;
                method.maxLocals = 1;
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("InternalLoggerFactory.newDefaultFactory was not found");
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
