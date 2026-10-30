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

/** Selects the library's no-op narrator; WebSpeech is provided separately. */
public final class Text2SpeechBrowserPatcher {
    private Text2SpeechBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(args[0])) {
            try (var stream = jar.getInputStream(
                    jar.getEntry("com/mojang/text2speech/Narrator.class"))) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("getNarrator")
                    && method.desc.equals("()Lcom/mojang/text2speech/Narrator;")) {
                InsnList code = new InsnList();
                code.add(new FieldInsnNode(Opcodes.GETSTATIC,
                        "com/mojang/text2speech/Narrator", "EMPTY",
                        "Lcom/mojang/text2speech/Narrator;"));
                code.add(new InsnNode(Opcodes.ARETURN));
                method.instructions = code;
                method.tryCatchBlocks.clear();
                method.localVariables = null;
                method.maxStack = 1;
                method.maxLocals = 0;
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("Narrator.getNarrator was not found");
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
