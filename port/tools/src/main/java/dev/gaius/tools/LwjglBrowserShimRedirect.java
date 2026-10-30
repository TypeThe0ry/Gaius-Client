package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Method-body redirection of an LWJGL binding class to a browser shim that was
 * compiled into the same jar (the module's library overlay, PLAN D5):
 *
 * <ul>
 *   <li>the static initializer only clears the SharedLibrary field, so no
 *       native library is loaded;</li>
 *   <li>every public static method whose name and descriptor the shim also
 *       declares (public static) calls the shim with the same arguments;</li>
 *   <li>every other public static method throws UnsupportedOperationException
 *       naming itself, so no path reaches JNI with a missing function address.</li>
 * </ul>
 *
 * The redirection set is read from the shim class itself; the caller passes the
 * methods Minecraft needs, which must all be redirected.
 */
final class LwjglBrowserShimRedirect {
    private static final String SHARED_LIBRARY = "Lorg/lwjgl/system/SharedLibrary;";

    private LwjglBrowserShimRedirect() {
    }

    /**
     * Patches owner (for example org/lwjgl/util/shaderc/Shaderc) in jar and writes
     * it below outputRoot.  Returns a one-line summary.
     */
    static String redirect(String jar, Path outputRoot, String owner, String shim, String libraryField,
            Set<String> required) throws IOException {
        ClassNode target = read(jar, owner + ".class");
        ClassNode shimNode = read(jar, shim + ".class");
        Set<String> shimMethods = new TreeSet<>();
        for (MethodNode method : shimNode.methods) {
            if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                    && !method.name.startsWith("<")) {
                shimMethods.add(method.name + method.desc);
            }
        }
        boolean hasLibraryField = false;
        for (FieldNode field : target.fields) {
            hasLibraryField |= field.name.equals(libraryField) && field.desc.equals(SHARED_LIBRARY)
                    && (field.access & Opcodes.ACC_STATIC) != 0;
        }
        if (!hasLibraryField) {
            throw new IllegalStateException(owner + " has no static SharedLibrary field " + libraryField);
        }

        Set<String> delegated = new TreeSet<>();
        List<String> unsupported = new ArrayList<>();
        boolean initializer = false;
        for (MethodNode method : target.methods) {
            String key = method.name + method.desc;
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, libraryField, SHARED_LIBRARY));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1);
                initializer = true;
                continue;
            }
            boolean publicStatic = (method.access & Opcodes.ACC_STATIC) != 0
                    && (method.access & Opcodes.ACC_PUBLIC) != 0;
            if (!publicStatic || method.name.equals("getLibrary")) {
                continue;
            }
            if (shimMethods.contains(key)) {
                delegate(method, shim);
                delegated.add(key);
            } else {
                throwUnsupported(method, owner);
                unsupported.add(key);
            }
        }
        if (!initializer) {
            throw new IllegalStateException(owner + " has no static initializer");
        }
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(delegated);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(shim + " does not implement " + owner + " methods Minecraft calls: "
                    + missing);
        }
        String functions = owner + "$Functions";
        for (MethodNode method : target.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.owner.equals(functions)) {
                    throw new IllegalStateException(owner + "." + method.name + method.desc
                            + " still reads " + functions + "." + field.name);
                }
            }
        }
        write(target, outputRoot.resolve(owner + ".class"));
        return "Redirected " + delegated.size() + " " + simpleName(owner) + " methods to " + simpleName(shim)
                + "; " + unsupported.size() + " other methods throw UnsupportedOperationException";
    }

    private static void delegate(MethodNode method, String shim) {
        InsnList code = new InsnList();
        int local = 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, shim, method.name, method.desc, false));
        code.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        int returnSize = Type.getReturnType(method.desc).getSize();
        replace(method, code, Math.max(local, returnSize));
    }

    private static void throwUnsupported(MethodNode method, String owner) {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode("The Gaius browser runtime does not implement "
                + simpleName(owner) + "." + method.name + method.desc));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>",
                "(Ljava/lang/String;)V", false));
        code.add(new InsnNode(Opcodes.ATHROW));
        replace(method, code, 3);
    }

    private static void replace(MethodNode method, InsnList code, int maxStack) {
        method.access &= ~Opcodes.ACC_NATIVE;
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = maxStack;
        method.maxLocals = Math.max(method.maxLocals, Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " not found in " + jarPath
                        + " (is the browser shim overlay compiled into the jar?)");
            }
            ClassNode node = new ClassNode();
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
