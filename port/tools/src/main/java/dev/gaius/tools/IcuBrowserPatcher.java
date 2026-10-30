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
import org.objectweb.asm.tree.MethodNode;

/** Removes ICU's optional memory-mapped desktop data path. */
public final class IcuBrowserPatcher {
    private static final String ENTRY = "com/ibm/icu/impl/ICUBinary.class";

    private IcuBrowserPatcher() {
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
            if (method.name.equals("mapFile")
                    && method.desc.equals("(Ljava/io/File;)Ljava/nio/ByteBuffer;")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
                method.instructions = code;
                method.tryCatchBlocks.clear();
                method.maxStack = 1;
                method.maxLocals = 1;
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("ICUBinary.mapFile was not found");
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
