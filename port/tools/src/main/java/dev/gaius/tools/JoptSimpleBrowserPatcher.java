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
import org.objectweb.asm.tree.VarInsnNode;

/** Removes jopt-simple's dependency on the desktop BreakIterator API. */
public final class JoptSimpleBrowserPatcher {
    private static final String ENTRY = "joptsimple/internal/Columns.class";

    private JoptSimpleBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        byte[] input;
        try (ZipFile jar = new ZipFile(args[0])) {
            var entry = jar.getEntry(ENTRY);
            if (entry == null) {
                throw new IllegalStateException("jopt-simple Columns.class was not found");
            }
            try (var stream = jar.getInputStream(entry)) {
                input = stream.readAllBytes();
            }
        }

        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        boolean found = false;
        boolean browserPiecesFound = false;
        boolean browserWrapFound = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("piecesOfEmbeddedLine")
                    && method.desc.equals("(Ljava/lang/String;I)Ljava/util/List;")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/util/Collections",
                        "singletonList",
                        "(Ljava/lang/Object;)Ljava/util/List;",
                        false));
                code.add(new InsnNode(Opcodes.ARETURN));
                method.instructions = code;
                method.tryCatchBlocks.clear();
                method.localVariables = null;
                method.maxStack = 1;
                method.maxLocals = 3;
                found = true;
            } else if (method.name.equals("piecesOf")
                    && method.desc.equals("(Ljava/lang/String;I)Ljava/util/List;")
                    && (method.access & Opcodes.ACC_STATIC) != 0) {
                browserPiecesFound = true;
            } else if (method.name.equals("wrapLine")
                    && method.desc.equals("(Ljava/lang/String;ILjava/util/List;)V")
                    && (method.access & Opcodes.ACC_STATIC) != 0) {
                browserWrapFound = true;
            }
        }
        boolean alreadyPatched = browserPiecesFound && browserWrapFound;
        if (!found && !alreadyPatched) {
            throw new IllegalStateException("Columns.piecesOfEmbeddedLine was not found");
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
        System.out.println(alreadyPatched
                ? "Verified jopt-simple browser line wrapping"
                : "Patched jopt-simple browser line wrapping");
        JoptSimpleReflectionPatcher.main(new String[] {
                args[0], output.resolveSibling("Reflection.class").toString()
        });
    }
}
