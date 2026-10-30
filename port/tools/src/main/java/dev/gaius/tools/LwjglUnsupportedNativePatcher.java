package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Replaces unsupported desktop-native entry points with explicit browser errors. */
public final class LwjglUnsupportedNativePatcher {
    private LwjglUnsupportedNativePatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: LwjglUnsupportedNativePatcher INPUT_JAR OUTPUT_ROOT BACKEND");
        }
        Path jarPath = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
        String message = args[2] + " is unavailable in the browser runtime";
        int patched = 0;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode node = new ClassNode();
                try (var stream = jar.getInputStream(entry)) {
                    new ClassReader(stream.readAllBytes()).accept(node, 0);
                }
                boolean changed = false;
                for (MethodNode method : node.methods) {
                    if ((method.access & Opcodes.ACC_NATIVE) == 0
                            && !callsLwjglJni(method)) {
                        continue;
                    }
                    replaceWithFailure(method, message);
                    changed = true;
                    patched++;
                }
                if (!changed) {
                    continue;
                }
                ClassWriter writer = new ClassWriter(0);
                node.accept(writer);
                Path output = ArchivePaths.resolveEntry(outputRoot, entry.getName());
                Files.createDirectories(output.getParent());
                Files.write(output, writer.toByteArray());
            }
        }
        System.out.println("Patched " + patched + " unsupported " + args[2]
                + " native/JNI entry points with fail-fast browser errors");
    }

    private static boolean callsLwjglJni(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("org/lwjgl/system/JNI")) {
                return true;
            }
        }
        return false;
    }

    private static void replaceWithFailure(MethodNode method, String message) {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode(message));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/UnsupportedOperationException",
                "<init>",
                "(Ljava/lang/String;)V",
                false));
        code.add(new InsnNode(Opcodes.ATHROW));
        method.access &= ~Opcodes.ACC_NATIVE;
        method.instructions = code;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = 3;
        int locals = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            locals += argument.getSize();
        }
        method.maxLocals = locals;
    }
}
