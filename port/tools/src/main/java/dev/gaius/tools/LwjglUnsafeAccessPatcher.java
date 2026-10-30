package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Redirects generated LWJGL struct Unsafe loads/stores to BrowserMemory. */
public final class LwjglUnsafeAccessPatcher {
    private static final Set<String> SUPPORTED = Set.of(
            "getByte", "getShort", "getInt", "getLong", "getFloat", "getDouble",
            "putByte", "putShort", "putInt", "putLong", "putFloat", "putDouble");

    private LwjglUnsafeAccessPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path outputRoot = Path.of(args[1]);
        int changedClasses = 0;
        int changedCalls = 0;
        try (ZipFile jar = new ZipFile(args[0])) {
            boolean leaveVersioned = MultiReleaseEntries.leaveVersionedEntries(jar);
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                if (leaveVersioned && MultiReleaseEntries.isVersioned(entry)) {
                    continue;
                }
                if (entry.getName().equals("org/lwjgl/system/Pointer$Default.class")
                        || entry.getName().equals(
                                "org/lwjgl/system/MultiReleaseTextDecoding.class")
                        || entry.getName().equals("org/lwjgl/system/Library.class")
                        || entry.getName().equals("org/lwjgl/Version.class")) {
                    continue;
                }
                byte[] bytes;
                try (var stream = jar.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                int classChanges = 0;
                for (MethodNode method : node.methods) {
                    for (AbstractInsnNode instruction = method.instructions.getFirst();
                            instruction != null; ) {
                        AbstractInsnNode next = instruction.getNext();
                        if (instruction instanceof MethodInsnNode call
                                && call.owner.equals("sun/misc/Unsafe")
                                && SUPPORTED.contains(call.name)
                                && removeUnsafeReceiver(method.instructions, call)) {
                            Type[] arguments = Type.getArgumentTypes(call.desc);
                            Type[] browserArguments = new Type[arguments.length - 1];
                            System.arraycopy(arguments, 1, browserArguments, 0,
                                    browserArguments.length);
                            call.setOpcode(Opcodes.INVOKESTATIC);
                            call.owner = "org/lwjgl/system/BrowserMemory";
                            call.desc = Type.getMethodDescriptor(
                                    Type.getReturnType(call.desc), browserArguments);
                            call.itf = false;
                            classChanges++;
                        }
                        instruction = next;
                    }
                }
                if (classChanges > 0) {
                    ClassWriter writer = new ClassWriter(0);
                    node.accept(writer);
                    Path output = ArchivePaths.resolveEntry(outputRoot, entry.getName());
                    Files.createDirectories(output.getParent());
                    Files.write(output, writer.toByteArray());
                    changedClasses++;
                    changedCalls += classChanges;
                }
            }
        }
        System.out.println("Patched " + changedCalls + " Unsafe calls in "
                + changedClasses + " LWJGL classes");
    }

    private static boolean removeUnsafeReceiver(
            org.objectweb.asm.tree.InsnList instructions, MethodInsnNode call) {
        AbstractInsnNode cursor = call.getPrevious();
        FieldInsnNode unsafeField = null;
        while (cursor != null) {
            if (cursor instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.desc.equals("Lsun/misc/Unsafe;")) {
                unsafeField = field;
                break;
            }
            cursor = cursor.getPrevious();
        }
        if (unsafeField == null) {
            return false;
        }
        AbstractInsnNode nullArgument = nextReal(unsafeField);
        if (!(nullArgument instanceof InsnNode)
                || nullArgument.getOpcode() != Opcodes.ACONST_NULL) {
            return false;
        }
        // Both instructions only contribute the receiver and base object.
        instructions.remove(unsafeField);
        instructions.remove(nullArgument);
        return true;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && (next.getType() == AbstractInsnNode.LABEL
                || next.getType() == AbstractInsnNode.LINE
                || next.getType() == AbstractInsnNode.FRAME)) {
            next = next.getNext();
        }
        return next;
    }
}
