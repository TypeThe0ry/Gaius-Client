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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Makes desktop-native LWJGL entry points compilable by TeaVM.
 *
 * <p>Managed memory calls retain real behavior. Other native backends return
 * conservative defaults until their browser implementation is supplied.</p>
 */
public final class NativeMethodFallbackPatcher {
    private NativeMethodFallbackPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path jarPath = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
        int patched = 0;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
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
                ClassNode node = new ClassNode();
                try (var stream = jar.getInputStream(entry)) {
                    new ClassReader(stream.readAllBytes()).accept(node, 0);
                }
                if (node.name.contains("/Browser")) {
                    continue;
                }
                boolean changed = false;
                for (MethodNode method : node.methods) {
                    if ((method.access & Opcodes.ACC_NATIVE) == 0) {
                        continue;
                    }
                    method.access &= ~Opcodes.ACC_NATIVE;
                    if (!replaceSpecialNative(node.name, method)
                            && !replaceMemoryCall(node.name, method)) {
                        replaceDefault(method);
                    }
                    changed = true;
                    patched++;
                }
                if (changed) {
                    ClassWriter writer = new ClassWriter(0);
                    node.accept(writer);
                    Path output = ArchivePaths.resolveEntry(outputRoot, entry.getName());
                    Files.createDirectories(output.getParent());
                    Files.write(output, writer.toByteArray());
                }
            }
        }
        System.out.println("Patched " + patched + " desktop native methods");
    }

    private static boolean replaceSpecialNative(String owner, MethodNode method) {
        if (owner.equals("org/lwjgl/system/MemoryAccessJNI")
                && method.name.equals("getPointerSize")
                && method.desc.equals("()I")) {
            InsnList code = new InsnList();
            code.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 8));
            code.add(new InsnNode(Opcodes.IRETURN));
            method.instructions = code;
            method.tryCatchBlocks.clear();
            method.maxStack = 1;
            method.maxLocals = 0;
            return true;
        }
        if (owner.equals("org/lwjgl/system/ThreadLocalUtil")
                && method.name.equals("getThreadJNIEnv")
                && method.desc.equals("()J")) {
            replaceWithBrowserMemory(method, "threadJniEnv");
            return true;
        }
        if (owner.equals("org/lwjgl/system/ThreadLocalUtil")
                && method.name.equals("nsetupEnvData")
                && method.desc.equals("(I)J")) {
            replaceWithBrowserMemory(method, "setupThreadEnv");
            return true;
        }
        if (owner.equals("org/lwjgl/system/libc/LibCString")) {
            String target = switch (method.name + method.desc) {
                case "nmemset(JIJ)J" -> "cMemset";
                case "nmemcpy(JJJ)J" -> "cMemcpy";
                case "nmemmove(JJJ)J" -> "cMemmove";
                default -> null;
            };
            if (target != null) {
                replaceWithBrowserMemory(method, target);
                return true;
            }
        }
        return false;
    }

    private static void replaceWithBrowserMemory(MethodNode method, String target) {
        InsnList code = new InsnList();
        int local = 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                target,
                method.desc,
                false));
        code.add(new InsnNode(
                Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        method.instructions = code;
        method.tryCatchBlocks.clear();
        method.maxStack = Math.max(2, local);
        method.maxLocals = local;
    }

    private static boolean replaceMemoryCall(String owner, MethodNode method) {
        if (!owner.equals("org/lwjgl/system/libc/LibCStdlib")) {
            return false;
        }
        String target = switch (method.name + method.desc) {
            case "nmalloc(J)J" -> "allocate";
            case "ncalloc(JJ)J" -> "calloc";
            case "nrealloc(JJ)J" -> "reallocate";
            case "nfree(J)V" -> "free";
            default -> null;
        };
        if (target == null) {
            return false;
        }
        InsnList code = new InsnList();
        int local = 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                target,
                method.desc,
                false));
        code.add(new InsnNode(
                Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        method.instructions = code;
        method.maxStack = Math.max(2, local);
        method.maxLocals = local;
        return true;
    }

    private static void replaceDefault(MethodNode method) {
        InsnList code = new InsnList();
        Type result = Type.getReturnType(method.desc);
        switch (result.getSort()) {
            case Type.VOID -> code.add(new InsnNode(Opcodes.RETURN));
            case Type.LONG -> {
                code.add(new InsnNode(Opcodes.LCONST_0));
                code.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                code.add(new InsnNode(Opcodes.FCONST_0));
                code.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                code.add(new InsnNode(Opcodes.DCONST_0));
                code.add(new InsnNode(Opcodes.DRETURN));
            }
            case Type.OBJECT, Type.ARRAY -> {
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
            }
            default -> {
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new InsnNode(Opcodes.IRETURN));
            }
        }
        method.instructions = code;
        method.tryCatchBlocks.clear();
        method.maxStack = 2;
        method.maxLocals = Math.max(
                method.maxLocals,
                Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }
}
