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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Selects Guava's built-in synchronized future state helper for the browser.
 */
public final class GuavaFutureStatePatcher {
    private static final String CLASS_ENTRY =
            "com/google/common/util/concurrent/AbstractFutureState.class";
    private static final String UNSAFE_HELPER =
            "com/google/common/util/concurrent/AbstractFutureState$UnsafeAtomicHelper";
    private static final String SYNCHRONIZED_HELPER =
            "com/google/common/util/concurrent/AbstractFutureState$SynchronizedHelper";
    private static final String VAR_HANDLE_MAKER =
            "com/google/common/util/concurrent/AbstractFutureState$VarHandleAtomicHelperMaker";

    private GuavaFutureStatePatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: GuavaFutureStatePatcher INPUT_JAR OUTPUT_CLASS");
        }

        byte[] input;
        try (ZipFile jar = new ZipFile(args[0])) {
            var entry = jar.getEntry(CLASS_ENTRY);
            if (entry == null) {
                throw new IllegalStateException(CLASS_ENTRY + " not found in " + args[0]);
            }
            try (var stream = jar.getInputStream(entry)) {
                input = stream.readAllBytes();
            }
        }

        ClassNode classNode = new ClassNode();
        new ClassReader(input).accept(classNode, 0);
        int replacements = 0;
        for (MethodNode method : classNode.methods) {
            if (!method.name.equals("<clinit>")) {
                continue;
            }
            for (var instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode typeInsn
                        && typeInsn.desc.equals(UNSAFE_HELPER)) {
                    typeInsn.desc = SYNCHRONIZED_HELPER;
                    replacements++;
                } else if (instruction instanceof MethodInsnNode methodInsn
                        && methodInsn.owner.equals(UNSAFE_HELPER)) {
                    methodInsn.owner = SYNCHRONIZED_HELPER;
                    replacements++;
                } else if (instruction instanceof FieldInsnNode fieldInsn
                        && fieldInsn.owner.equals(VAR_HANDLE_MAKER)
                        && fieldInsn.name.equals("INSTANCE")) {
                    // VarHandle helpers are unavailable in the browser; make the
                    // selection return null so guava falls back to the next helper.
                    method.instructions.set(instruction, new InsnNode(Opcodes.ACONST_NULL));
                    replacements++;
                } else if (instruction instanceof MethodInsnNode methodInsn
                        && methodInsn.owner.equals(VAR_HANDLE_MAKER)) {
                    // Keep iteration stable: neutralize in place; the preceding
                    // INSTANCE reference was already replaced by ACONST_NULL.
                    method.instructions.set(instruction, new InsnNode(Opcodes.NOP));
                    replacements++;
                }
            }
        }
        if (replacements != 4) {
            throw new IllegalStateException(
                    "Expected two Unsafe and two VarHandle maker references, found "
                            + replacements);
        }

        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
