package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Makes DataFixerUpper TypeToken witnesses independent of generic reflection metadata. */
public final class GuavaTypeTokenBrowserPatcher {
    private static final String TYPE_TOKEN = "com/google/common/reflect/TypeToken";
    private static final String SIGNATURE_PREFIX = "L" + TYPE_TOKEN + "<";

    private GuavaTypeTokenBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args[2]);
        patchTypeTokenConstructor(args[0], output.resolve(TYPE_TOKEN + ".class"));

        int patched = 0;
        try (ZipFile jar = new ZipFile(args[1])) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode node = new ClassNode();
                try (var stream = jar.getInputStream(entry)) {
                    new ClassReader(stream).accept(node, 0);
                }
                if (!TYPE_TOKEN.equals(node.superName)) {
                    continue;
                }
                String rawType = rawTypeArgument(node);
                int constructorCalls = 0;
                for (MethodNode constructor : node.methods) {
                    if (!constructor.name.equals("<init>")) {
                        continue;
                    }
                    for (var instruction = constructor.instructions.getFirst();
                            instruction != null;
                            instruction = instruction.getNext()) {
                        if (!(instruction instanceof MethodInsnNode call)
                                || call.getOpcode() != Opcodes.INVOKESPECIAL
                                || !call.owner.equals(TYPE_TOKEN)
                                || !call.name.equals("<init>")
                                || !call.desc.equals("()V")) {
                            continue;
                        }
                        constructor.instructions.insertBefore(
                                call, new LdcInsnNode(Type.getObjectType(rawType)));
                        call.desc = "(Ljava/lang/reflect/Type;)V";
                        constructor.maxStack = Math.max(constructor.maxStack, 2);
                        constructorCalls++;
                    }
                }
                if (constructorCalls == 0) {
                    throw new IllegalStateException(
                            "TypeToken super constructor was not found in " + node.name);
                }
                write(node, output.resolve(node.name + ".class"));
                patched++;
            }
        }
        if (patched == 0) {
            throw new IllegalStateException("No DataFixerUpper TypeToken witnesses were found");
        }
        System.out.println("Patched " + patched + " Guava TypeToken witnesses for browser use");
    }

    private static void patchTypeTokenConstructor(String jarPath, Path output)
            throws IOException {
        ClassNode node = read(jarPath, TYPE_TOKEN + ".class");
        MethodNode constructor = find(node, "<init>", "(Ljava/lang/reflect/Type;)V");
        constructor.access &= ~Opcodes.ACC_PRIVATE;
        constructor.access |= Opcodes.ACC_PROTECTED;
        write(node, output);
    }

    private static String rawTypeArgument(ClassNode node) {
        String signature = node.signature;
        if (signature == null || !signature.startsWith(SIGNATURE_PREFIX)) {
            throw new IllegalStateException("Unexpected TypeToken signature on " + node.name
                    + ": " + signature);
        }
        int start = SIGNATURE_PREFIX.length();
        if (start >= signature.length() || signature.charAt(start) != 'L') {
            throw new IllegalStateException("TypeToken argument is not a concrete class on "
                    + node.name + ": " + signature);
        }
        start++;
        int generic = signature.indexOf('<', start);
        int end = signature.indexOf(';', start);
        if (generic >= 0 && generic < end) {
            end = generic;
        }
        if (end < 0) {
            throw new IllegalStateException("Malformed TypeToken signature on " + node.name);
        }
        return signature.substring(start, end);
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        throw new IllegalStateException(name + descriptor + " was not found in " + node.name);
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " was not found");
            }
            ClassNode node = new ClassNode();
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream).accept(node, 0);
            }
            return node;
        }
    }

    private static void write(ClassNode node, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.write(output, writer.toByteArray());
    }
}
