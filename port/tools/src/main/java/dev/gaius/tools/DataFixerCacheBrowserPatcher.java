package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Replaces only the completed DataFixerUpper rewrite cache's allocation. */
public final class DataFixerCacheBrowserPatcher {
    private static final String OWNER = "com/mojang/datafixers/types/Type";
    private static final String HELPER = "dev/gaius/browser/BrowserDataFixerCaches";

    private DataFixerCacheBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: DataFixerCacheBrowserPatcher <datafixerupper.jar> <patch-output>");
        }
        ClassNode node = new ClassNode();
        try (ZipFile jar = new ZipFile(args[0]);
                var stream = jar.getInputStream(jar.getEntry(OWNER + ".class"))) {
            new ClassReader(stream).accept(node, 0);
        }
        int replacements = 0;
        for (var method : node.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (var instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTSTATIC
                        || !field.owner.equals(OWNER)
                        || !field.name.equals("REWRITE_CACHE")
                        || !field.desc.equals("Ljava/util/Map;")) continue;
                AbstractInsnNode previous = instruction.getPrevious();
                while (previous != null && previous.getOpcode() < 0) {
                    previous = previous.getPrevious();
                }
                if (!(previous instanceof MethodInsnNode factory)
                        || factory.getOpcode() != Opcodes.INVOKESTATIC
                        || !factory.owner.equals("com/google/common/collect/Maps")
                        || !factory.name.equals("newConcurrentMap")
                        || !factory.desc.equals("()Ljava/util/concurrent/ConcurrentMap;")) {
                    throw new IllegalStateException("Unexpected completed rewrite cache initializer");
                }
                factory.owner = HELPER;
                factory.name = "completedRewrites";
                factory.desc = "()Ljava/util/Map;";
                replacements++;
            }
        }
        if (replacements != 1) {
            throw new IllegalStateException("Expected one completed rewrite cache, got " + replacements);
        }
        Path output = Path.of(args[1]).resolve(OWNER + ".class");
        Files.createDirectories(output.getParent());
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.write(output, writer.toByteArray());
        System.out.println("Bounded the completed DataFixerUpper rewrite cache");
    }
}
