package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Replaces LWJGL's JVM Unsafe memory primitives with BrowserMemory.
 *
 * <p>Two input shapes are supported, told apart by the MemoryUtil fields
 * rather than by a version number:</p>
 * <ul>
 * <li>Unsafe mode (LWJGL 3.4.1 and older): MemoryUtil reads Unsafe
 * directly; its initializer and memory methods are replaced by BrowserMemory
 * calls.  This path must keep producing identical bytes (26.2, G1).</li>
 * <li>Backend mode (LWJGL 3.4.2+, detected by the
 * {@code MemoryUtil.BACKEND:Lorg/lwjgl/system/MemoryBackend;} field): most
 * MemoryUtil and MemoryStack operations go through that field.  The
 * initializer assigns {@code BrowserMemoryBackend.INSTANCE} (from the
 * versioned lwjgl overlay) and writes only fields that exist, the reflective
 * {@code createBackend()} returns the same instance, and
 * {@code MemoryUtil$LazyInit} uses BrowserMemoryAllocator instead of
 * MemoryManage's reflective allocator lookup.</li>
 * </ul>
 */
public final class LwjglMemoryPatcher {
    private static final String MEMORY_UTIL = "org/lwjgl/system/MemoryUtil";
    private static final String MEMORY_UTIL_LAZY_INIT = "org/lwjgl/system/MemoryUtil$LazyInit";
    private static final String MEMORY_BACKEND = "org/lwjgl/system/MemoryBackend";
    private static final String BROWSER_MEMORY = "org/lwjgl/system/BrowserMemory";
    private static final String BROWSER_MEMORY_BACKEND =
            "org/lwjgl/system/BrowserMemoryBackend";
    private static final String BROWSER_MEMORY_ALLOCATOR =
            "org/lwjgl/system/BrowserMemoryAllocator";
    private static final String MEMORY_ALLOCATOR_DESC =
            "Lorg/lwjgl/system/MemoryUtil$MemoryAllocator;";
    private static final Map<String, String> DELEGATES = delegates();
    /**
     * Backend-mode MemoryUtil methods delegated in addition to DELEGATES.
     * LWJGL 3.4.3 measures NUL-terminated strings a long word at a time; a
     * virtual region ends exactly at its allocation size, so a word read
     * past the terminator of a tightly allocated string would fail.
     */
    private static final Map<String, String> BACKEND_DELEGATES = Map.of(
            "strlenNT1(JI)I", "lengthNt1",
            "strlenNT2(JI)I", "lengthNt2");
    /** Minimum backend-mode MemoryUtil replacements (LWJGL 3.4.3: 106). */
    private static final int MIN_BACKEND_REPLACEMENTS = 100;

    private LwjglMemoryPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LwjglMemoryPatcher INPUT_JAR OUTPUT_ROOT");
        }
        Path root = Path.of(args[1]);
        boolean backendMode = hasBackendField(read(args[0], MEMORY_UTIL + ".class"));
        if (backendMode) {
            System.out.println("LWJGL MemoryUtil uses a MemoryBackend: installing BrowserMemoryBackend");
        }
        patchMemoryUtil(args[0], root.resolve("org/lwjgl/system/MemoryUtil.class"), backendMode);
        if (backendMode) {
            patchMemoryUtilLazyInit(
                    args[0], root.resolve("org/lwjgl/system/MemoryUtil$LazyInit.class"));
        }
        patchMemoryUtilTunables(
                args[0], root.resolve("org/lwjgl/system/MemoryUtilTunables.class"));
        patchPointer(
                args[0], root.resolve("org/lwjgl/system/Pointer$Default.class"), backendMode);
        patchDecoder(
                args[0],
                root.resolve("org/lwjgl/system/MultiReleaseTextDecoding.class"),
                backendMode);
        patchLibrary(args[0], root.resolve("org/lwjgl/system/Library.class"));
        patchVersion(args[0], root.resolve("org/lwjgl/Version.class"));
        patchCallback(args[0], root.resolve("org/lwjgl/system/Callback.class"));
        patchCallbackInterface(args[0], root.resolve("org/lwjgl/system/CallbackI.class"));
        patchPlatform(args[0], root.resolve("org/lwjgl/system/Platform.class"));
        patchPlatformArchitecture(
                args[0], root.resolve("org/lwjgl/system/Platform$Architecture.class"));
        patchMemCopy(args[0], root.resolve("org/lwjgl/system/MultiReleaseMemCopy.class"));
    }

    private static boolean hasBackendField(ClassNode memoryUtil) {
        for (FieldNode field : memoryUtil.fields) {
            if (field.name.equals("BACKEND")) {
                if (!field.desc.equals("L" + MEMORY_BACKEND + ";")
                        || (field.access & Opcodes.ACC_STATIC) == 0) {
                    throw new IllegalStateException(
                            "Unexpected MemoryUtil.BACKEND field: " + field.desc);
                }
                return true;
            }
        }
        return false;
    }

    private static void patchMemoryUtil(String jar, Path output, boolean backendMode)
            throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/MemoryUtil.class");
        int replaced = 0;
        boolean backendCreated = false;
        Set<String> backendDelegates = new HashSet<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                replace(
                        method,
                        backendMode ? backendMemoryUtilInitializer() : memoryUtilInitializer(),
                        2);
                replaced++;
                continue;
            }
            if (backendMode
                    && method.name.equals("createBackend")
                    && method.desc.equals("()L" + MEMORY_BACKEND + ";")) {
                InsnList code = new InsnList();
                code.add(browserMemoryBackendInstance());
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1);
                backendCreated = true;
                replaced++;
                continue;
            }
            if (backendMode) {
                String backendTarget = BACKEND_DELEGATES.get(method.name + method.desc);
                if (backendTarget != null) {
                    replaceWithDelegate(method, backendTarget);
                    backendDelegates.add(method.name + method.desc);
                    replaced++;
                    continue;
                }
            }
            if (method.name.equals("getUnsafeInstance")
                    && method.desc.equals("()Lsun/misc/Unsafe;")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1);
                replaced++;
                continue;
            }
            if (method.name.equals("memGlobalRefToObject")
                    && method.desc.equals("(J)Ljava/lang/Object;")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1);
                replaced++;
                continue;
            }
            if (method.name.equals("getAllocator")
                    && method.desc.equals("(Z)Lorg/lwjgl/system/MemoryUtil$MemoryAllocator;")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        BROWSER_MEMORY_ALLOCATOR,
                        "instance",
                        "()Lorg/lwjgl/system/MemoryUtil$MemoryAllocator;",
                        false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1);
                replaced++;
                continue;
            }
            if (isUnsafeOffsetHelper(method)) {
                replaceZeroReturn(method);
                replaced++;
                continue;
            }
            if (method.name.equals("memFree")
                    && method.desc.startsWith("(Ljava/nio/")
                    && method.desc.endsWith("Buffer;)V")) {
                replaceBufferFree(method);
                replaced++;
                continue;
            }
            if (method.name.equals("memAlignedFree")
                    && method.desc.equals("(Ljava/nio/ByteBuffer;)V")) {
                replaceBufferFree(method);
                replaced++;
                continue;
            }
            if (method.name.equals("memAddress0")
                    && hasNioBufferArguments(method, 1)
                    && Type.getReturnType(method.desc).getSort() == Type.LONG) {
                replaceBufferAddress(method, "address0");
                replaced++;
                continue;
            }
            if ((method.name.equals("memAddress") || method.name.equals("memAddressSafe"))
                    && hasNioBufferArguments(method, 1)
                    && Type.getArgumentTypes(method.desc).length == 1
                    && Type.getReturnType(method.desc).getSort() == Type.LONG) {
                replaceBufferAddress(
                        method, method.name.equals("memAddressSafe") ? "addressSafe" : "address");
                replaced++;
                continue;
            }
            if (method.name.equals("memAddress")
                    && hasNioBufferArguments(method, 1)
                    && hasIntOffsetArgument(method)
                    && Type.getReturnType(method.desc).getSort() == Type.LONG) {
                replaceBufferAddressAt(method);
                replaced++;
                continue;
            }
            if (method.name.equals("memDuplicate")
                    && hasNioBufferArguments(method, 1)
                    && Type.getArgumentTypes(method.desc).length == 1) {
                replaceBufferDuplicate(method);
                replaced++;
                continue;
            }
            if (method.name.startsWith("wrapBuffer")
                    && method.desc.startsWith("(JI)Ljava/nio/")
                    && method.desc.endsWith("Buffer;")) {
                replaceBufferWrap(method);
                replaced++;
                continue;
            }
            String target = DELEGATES.get(method.name + method.desc);
            if (target != null) {
                replaceWithDelegate(method, target);
                replaced++;
            }
        }
        if (replaced < 20) {
            throw new IllegalStateException("Too few MemoryUtil methods replaced: " + replaced);
        }
        if (backendMode) {
            if (!backendCreated) {
                throw new IllegalStateException("MemoryUtil.createBackend() not found");
            }
            if (!backendDelegates.equals(BACKEND_DELEGATES.keySet())) {
                throw new IllegalStateException(
                        "MemoryUtil string-length helpers not found: expected "
                                + BACKEND_DELEGATES.keySet() + ", replaced " + backendDelegates);
            }
            if (replaced < MIN_BACKEND_REPLACEMENTS) {
                throw new IllegalStateException(
                        "Too few backend-mode MemoryUtil methods replaced: " + replaced);
            }
            verifyBackendMemoryUtil(node);
            System.out.println("Patched " + replaced
                    + " LWJGL MemoryUtil methods (BACKEND = BrowserMemoryBackend.INSTANCE)");
        }
        write(node, output);
    }

    /**
     * Backend-mode self-check: the new initializer assigns exactly the
     * listed static fields, BACKEND among them, and every MemoryUtil field
     * the class still references exists (LWJGL 3.4.3 removed the Unsafe
     * field offsets that the Unsafe-mode initializer writes).
     */
    private static void verifyBackendMemoryUtil(ClassNode node) {
        Set<String> declared = new HashSet<>();
        for (FieldNode field : node.fields) {
            declared.add(field.name + ":" + field.desc);
        }
        Set<String> assigned = new HashSet<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (!(instruction instanceof FieldInsnNode field)
                        || !field.owner.equals(MEMORY_UTIL)) {
                    continue;
                }
                if (!declared.contains(field.name + ":" + field.desc)) {
                    throw new IllegalStateException("MemoryUtil." + method.name
                            + " references missing field " + field.name + ":" + field.desc);
                }
                if (method.name.equals("<clinit>") && field.getOpcode() == Opcodes.PUTSTATIC) {
                    assigned.add(field.name);
                }
            }
        }
        Set<String> expected = Set.of(
                "ARRAY_TLC_SIZE", "ARRAY_TLC_BYTE", "ARRAY_TLC_CHAR", "UTF16",
                "PAGE_SIZE", "CACHE_LINE_SIZE", "BACKEND");
        if (!assigned.equals(expected)) {
            throw new IllegalStateException(
                    "MemoryUtil initializer assigns " + assigned + ", expected " + expected);
        }
    }

    /**
     * LWJGL 3.4.3 {@code memUTF8(CharSequence, boolean)} allocates through
     * {@code MemoryUtil$LazyInit.ALLOCATOR}, whose initializer picks a native
     * allocator (jemalloc, rpmalloc, ...) by reflection.
     */
    private static void patchMemoryUtilLazyInit(String jar, Path output) throws IOException {
        ClassNode node = read(jar, MEMORY_UTIL_LAZY_INIT + ".class");
        for (String field : new String[] {"ALLOCATOR_IMPL", "ALLOCATOR"}) {
            boolean found = node.fields.stream().anyMatch(candidate ->
                    candidate.name.equals(field) && candidate.desc.equals(MEMORY_ALLOCATOR_DESC)
                            && (candidate.access & Opcodes.ACC_STATIC) != 0);
            if (!found) {
                throw new IllegalStateException("MemoryUtil$LazyInit." + field + " not found");
            }
        }
        MethodNode initializer = node.methods.stream()
                .filter(method -> method.name.equals("<clinit>") && method.desc.equals("()V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "MemoryUtil$LazyInit initializer not found"));
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY_ALLOCATOR,
                "instance",
                "()" + MEMORY_ALLOCATOR_DESC,
                false));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL_LAZY_INIT, "ALLOCATOR_IMPL", MEMORY_ALLOCATOR_DESC));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL_LAZY_INIT, "ALLOCATOR", MEMORY_ALLOCATOR_DESC));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(initializer, code, 2);
        write(node, output);
    }

    private static void patchMemoryUtilTunables(String jar, Path output) throws IOException {
        String entry = "org/lwjgl/system/MemoryUtilTunables.class";
        if (!contains(jar, entry)) {
            return;
        }
        ClassNode node = read(jar, entry);
        MethodNode initializer = node.methods.stream()
                .filter(method -> method.name.equals("<clinit>") && method.desc.equals("()V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "MemoryUtilTunables initializer not found"));
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(0x01010101));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, "org/lwjgl/system/MemoryUtilTunables",
                "FILL_PATTERN_32", "I"));
        code.add(new LdcInsnNode(0x0101010101010101L));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, "org/lwjgl/system/MemoryUtilTunables",
                "FILL_PATTERN_64", "J"));
        for (String field : new String[] {
                "BASE_OFFSET_BYTE", "BASE_OFFSET_SHORT", "BASE_OFFSET_INT",
                "BASE_OFFSET_LONG", "BASE_OFFSET_FLOAT", "BASE_OFFSET_DOUBLE"
        }) {
            code.add(new InsnNode(Opcodes.LCONST_0));
            code.add(new FieldInsnNode(
                    Opcodes.PUTSTATIC, "org/lwjgl/system/MemoryUtilTunables", field, "J"));
        }
        code.add(new InsnNode(Opcodes.RETURN));
        replace(initializer, code, 2);

        boolean copied = false;
        boolean filled = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("memcpy") && method.desc.equals("(JJJ)V")) {
                replaceWithDelegate(method, "copy");
                copied = true;
            } else if (method.name.equals("memset") && method.desc.equals("(JIJ)V")) {
                replaceWithDelegate(method, "set");
                filled = true;
            }
        }
        if (!copied || !filled) {
            throw new IllegalStateException(
                    "MemoryUtilTunables browser copy/fill entry points not found");
        }
        write(node, output);
    }

    private static void replaceBufferFree(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                "free",
                "(Ljava/nio/Buffer;)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 1);
    }

    private static void replaceBufferAddress(MethodNode method, String target) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                target,
                "(Ljava/nio/Buffer;)J",
                false));
        code.add(new InsnNode(Opcodes.LRETURN));
        replace(method, code, 2);
    }

    private static void replaceBufferAddressAt(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                "addressAt",
                "(Ljava/nio/Buffer;I)J",
                false));
        code.add(new InsnNode(Opcodes.LRETURN));
        replace(method, code, 3);
    }

    private static void replaceBufferDuplicate(MethodNode method) {
        Type argument = Type.getArgumentTypes(method.desc)[0];
        Type result = Type.getReturnType(method.desc);
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                "duplicate",
                "(" + argument.getDescriptor() + ")" + result.getDescriptor(),
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 2);
    }

    private static boolean hasNioBufferArguments(MethodNode method, int expected) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        return arguments.length >= expected && isNioBuffer(arguments[0]);
    }

    private static boolean hasIntOffsetArgument(MethodNode method) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        return arguments.length == 2 && arguments[1].getSort() == Type.INT;
    }

    private static boolean isNioBuffer(Type type) {
        return type.getSort() == Type.OBJECT
                && type.getInternalName().startsWith("java/nio/")
                && type.getInternalName().endsWith("Buffer");
    }

    private static void replaceBufferWrap(MethodNode method) {
        String bufferName = method.name.substring("wrapBuffer".length());
        int kind = switch (bufferName) {
            case "Short" -> 1;
            case "Char" -> 2;
            case "Int" -> 3;
            case "Long" -> 4;
            case "Float" -> 5;
            case "Double" -> 6;
            default -> 0;
        };
        Type result = Type.getReturnType(method.desc);
        InsnList code = new InsnList();
        code.add(new IntInsnNode(Opcodes.BIPUSH, kind));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                "bufferClass",
                "(I)Ljava/lang/Class;",
                false));
        code.add(new VarInsnNode(Opcodes.LLOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                "wrap",
                "(Ljava/lang/Class;JI)Ljava/nio/Buffer;",
                false));
        code.add(new org.objectweb.asm.tree.TypeInsnNode(
                Opcodes.CHECKCAST, result.getInternalName()));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4);
    }

    private static boolean isUnsafeOffsetHelper(MethodNode method) {
        if (method.name.equals("getFieldOffset")
                || method.name.equals("getFieldOffsetInt")
                || method.name.equals("getFieldOffsetObject")
                || method.name.equals("getAddressOffset")
                || method.name.equals("getMarkOffset")
                || method.name.equals("getPositionOffset")
                || method.name.equals("getLimitOffset")
                || method.name.equals("getCapacityOffset")) {
            return Type.getReturnType(method.desc).getSort() == Type.LONG;
        }
        return method.name.startsWith("lambda$get")
                && Type.getReturnType(method.desc).getSort() == Type.BOOLEAN;
    }

    private static void replaceZeroReturn(MethodNode method) {
        InsnList code = new InsnList();
        Type result = Type.getReturnType(method.desc);
        if (result.getSort() == Type.LONG) {
            code.add(new InsnNode(Opcodes.LCONST_0));
            code.add(new InsnNode(Opcodes.LRETURN));
        } else if (result.getSort() == Type.BOOLEAN) {
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new InsnNode(Opcodes.IRETURN));
        } else {
            throw new IllegalArgumentException("Unexpected Unsafe helper return type: " + result);
        }
        replace(method, code, 2);
    }

    private static void patchPointer(String jar, Path output, boolean backendMode)
            throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/Pointer$Default.class");
        if (backendMode) {
            // Neither 3.4.1 nor 3.4.3 has a Pointer$Default initializer.  The
            // Unsafe mode still rewrites the class unchanged, which keeps its
            // output byte-identical; the backend mode leaves the original.
            if (node.methods.stream().anyMatch(method -> method.name.equals("<clinit>"))) {
                throw new IllegalStateException(
                        "Unexpected Pointer$Default initializer in a MemoryBackend LWJGL");
            }
            return;
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC, "org/lwjgl/system/Pointer$Default",
                        "UNSAFE", "Lsun/misc/Unsafe;"));
                for (String field : new String[] {
                        "ADDRESS", "BUFFER_CONTAINER", "BUFFER_MARK", "BUFFER_POSITION",
                        "BUFFER_LIMIT", "BUFFER_CAPACITY"
                }) {
                    code.add(new InsnNode(Opcodes.LCONST_0));
                    code.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC, "org/lwjgl/system/Pointer$Default", field, "J"));
                }
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2);
            }
        }
        write(node, output);
    }

    private static void patchDecoder(String jar, Path output, boolean backendMode)
            throws IOException {
        String entry = "org/lwjgl/system/MultiReleaseTextDecoding.class";
        if (backendMode && !contains(jar, entry)) {
            // LWJGL 3.4.3 decodes through MemoryBackend.getStringUTF8, which
            // BrowserMemoryBackend implements with BrowserMemory.decodeUtf8.
            return;
        }
        ClassNode node = read(jar, entry);
        for (MethodNode method : node.methods) {
            if (method.name.equals("decodeUTF8") && method.desc.equals("(JI)Ljava/lang/String;")) {
                replaceWithDelegate(method, "decodeUtf8");
            }
        }
        write(node, output);
    }

    private static void patchLibrary(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/Library.class");
        int replaced = 0;
        for (MethodNode method : node.methods) {
            Type result = Type.getReturnType(method.desc);
            if (method.name.startsWith("loadSystem") && result.getSort() == Type.VOID) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0);
                replaced++;
            } else if (method.name.startsWith("loadNative")
                    && result.getDescriptor().equals("Lorg/lwjgl/system/SharedLibrary;")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/system/BrowserSharedLibrary",
                        "open",
                        "()Lorg/lwjgl/system/SharedLibrary;",
                        false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1);
                replaced++;
            }
        }
        if (replaced < 10) {
            throw new IllegalStateException("Too few Library methods replaced: " + replaced);
        }
        write(node, output);
    }

    private static void patchVersion(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/Version.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("findImplementationFromManifest")
                    && method.desc.equals("()Ljava/lang/String;")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("Version.findImplementationFromManifest not found");
        }
        write(node, output);
    }

    private static void patchCallback(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/Callback.class");
        Set<String> expected = new HashSet<>();
        Set<String> replaced = new HashSet<>();
        for (MethodNode method : node.methods) {
            String signature = method.name + method.desc;
            if (method.name.equals("<clinit>")
                    || method.name.equals("<init>")
                    || method.name.equals("free")
                    || method.name.equals("create")
                    || method.name.equals("get")
                    || method.name.equals("getSafe")
                    || method.name.equals("getCallbackHandler")) {
                expected.add(signature);
            }
            InsnList code = new InsnList();
            if (method.name.equals("<clinit>")) {
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC, "org/lwjgl/system/Callback", "DEBUG_ALLOCATOR", "Z"));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "org/lwjgl/system/Callback",
                        "CLOSURE_REGISTRY",
                        "Lorg/lwjgl/system/Callback$ClosureRegistry;"));
                code.add(new InsnNode(Opcodes.LCONST_0));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC, "org/lwjgl/system/Callback", "CALLBACK_HANDLER", "J"));
                code.add(new InsnNode(Opcodes.RETURN));
            } else if (method.name.equals("<init>")) {
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                if (method.desc.equals("(J)V")) {
                    code.add(new VarInsnNode(Opcodes.LLOAD, 1));
                } else {
                    code.add(new InsnNode(Opcodes.LCONST_1));
                }
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, "org/lwjgl/system/Callback", "address", "J"));
                code.add(new InsnNode(Opcodes.RETURN));
            } else if (method.name.equals("free") && method.desc.equals("()V")) {
                code.add(new InsnNode(Opcodes.RETURN));
            } else if (method.name.equals("free") && method.desc.equals("(J)V")) {
                code.add(new InsnNode(Opcodes.RETURN));
            } else if (method.name.equals("create")
                    && method.desc.equals("(Lorg/lwjgl/system/libffi/FFICIF;Ljava/lang/Object;)J")) {
                code.add(new InsnNode(Opcodes.LCONST_1));
                code.add(new InsnNode(Opcodes.LRETURN));
            } else if ((method.name.equals("get") || method.name.equals("getSafe"))
                    && method.desc.equals("(J)Lorg/lwjgl/system/CallbackI;")) {
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
            } else if (method.name.equals("getCallbackHandler")
                    && method.desc.equals("(Ljava/lang/reflect/Method;)J")) {
                code.add(new InsnNode(Opcodes.LCONST_0));
                code.add(new InsnNode(Opcodes.LRETURN));
            } else {
                continue;
            }
            replace(method, code, 4);
            replaced.add(signature);
        }
        Set<String> missed = new HashSet<>(expected);
        missed.removeAll(replaced);
        if (!missed.isEmpty()) {
            throw new IllegalStateException("Unsupported Callback methods: " + missed);
        }
        for (String required : new String[] {
                "<init>(J)V",
                "free()V",
                "free(J)V",
                "get(J)Lorg/lwjgl/system/CallbackI;",
                "getSafe(J)Lorg/lwjgl/system/CallbackI;"
        }) {
            if (!replaced.contains(required)) {
                throw new IllegalStateException("Required Callback method not found: " + required);
            }
        }
        boolean patchedDescriptorConstructor = replaced.stream()
                .anyMatch(signature -> signature.startsWith("<init>(L")
                        && signature.endsWith(")V"));
        if (!patchedDescriptorConstructor) {
            throw new IllegalStateException("Callback descriptor constructor not found");
        }
        System.out.println("Patched " + replaced.size() + " LWJGL Callback methods");
        write(node, output);
    }

    private static void patchCallbackInterface(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/CallbackI.class");
        MethodNode address = node.methods.stream()
                .filter(method -> method.name.equals("address") && method.desc.equals("()J"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("CallbackI.address not found"));
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.LCONST_1));
        code.add(new InsnNode(Opcodes.LRETURN));
        replace(address, code, 2);
        write(node, output);
    }

    /**
     * Makes {@code Platform.get()} report Linux by replacing the result of
     * {@code System.getProperty("os.name")}.  The lookup is found by its
     * immediately preceding {@code LDC "os.name"}: LWJGL 3.4.3 reads
     * java.version first, and replacing that value instead makes
     * {@code Platform.<clinit>} fail ("Failed to parse java.version").
     */
    private static void patchPlatform(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/Platform.class");
        MethodNode initializer = node.methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Platform initializer not found"));
        MethodInsnNode osName = null;
        for (var instruction = initializer.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/System")
                    && call.name.equals("getProperty")
                    && call.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")
                    && previousReal(call) instanceof LdcInsnNode key
                    && "os.name".equals(key.cst)) {
                if (osName != null) {
                    throw new IllegalStateException("Platform reads os.name more than once");
                }
                osName = call;
            }
        }
        if (osName == null) {
            throw new IllegalStateException("Platform os.name lookup not found");
        }
        InsnList browserOs = new InsnList();
        browserOs.add(new InsnNode(Opcodes.POP));
        browserOs.add(new LdcInsnNode("Linux"));
        initializer.instructions.insert(osName, browserOs);
        write(node, output);
    }

    /** The previous instruction, skipping labels, line numbers and frames. */
    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static void patchPlatformArchitecture(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/Platform$Architecture.class");
        MethodNode initializer = node.methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Platform.Architecture initializer not found"));
        boolean replaced = false;
        for (var instruction = initializer.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/System")
                    && call.name.equals("getProperty")
                    && call.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
                InsnList browserArchitecture = new InsnList();
                browserArchitecture.add(new InsnNode(Opcodes.POP));
                browserArchitecture.add(new LdcInsnNode("wasm64"));
                initializer.instructions.insert(call, browserArchitecture);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            throw new IllegalStateException("Platform.Architecture os.arch lookup not found");
        }
        write(node, output);
    }

    private static void patchMemCopy(String jar, Path output) throws IOException {
        if (!contains(jar, "org/lwjgl/system/MultiReleaseMemCopy.class")) {
            ClassNode memoryUtil = read(jar, "org/lwjgl/system/MemoryUtil.class");
            boolean delegatedByMemoryUtil = memoryUtil.methods.stream()
                    .anyMatch(method -> method.name.equals("memCopy")
                            && method.desc.equals("(JJJ)V"))
                    && DELEGATES.containsKey("memCopy(JJJ)V");
            if (!delegatedByMemoryUtil) {
                throw new IllegalStateException(
                        "No browser-safe LWJGL memory-copy entry point found");
            }
            System.out.println("Verified MemoryUtil.memCopy browser delegation");
            return;
        }
        ClassNode node = read(jar, "org/lwjgl/system/MultiReleaseMemCopy.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("copy") && method.desc.equals("(JJJ)V")) {
                replaceWithDelegate(method, "copy");
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("MultiReleaseMemCopy.copy not found");
        }
        write(node, output);
    }

    private static void replaceWithDelegate(MethodNode method, String targetName) {
        InsnList code = new InsnList();
        Type[] arguments = Type.getArgumentTypes(method.desc);
        int local = 0;
        for (Type argument : arguments) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, BROWSER_MEMORY, targetName, method.desc, false));
        Type result = Type.getReturnType(method.desc);
        code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        replace(method, code, Math.max(2, local));
    }

    private static InsnList memoryUtilInitializer() {
        InsnList code = new InsnList();
        putInt(code, "ARRAY_TLC_SIZE", 8192);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, BROWSER_MEMORY, "byteArrays",
                "()Ljava/lang/ThreadLocal;", false));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "ARRAY_TLC_BYTE", "Ljava/lang/ThreadLocal;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, BROWSER_MEMORY, "charArrays",
                "()Ljava/lang/ThreadLocal;", false));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "ARRAY_TLC_CHAR", "Ljava/lang/ThreadLocal;"));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "UNSAFE", "Lsun/misc/Unsafe;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/nio/ByteOrder", "nativeOrder",
                "()Ljava/nio/ByteOrder;", false));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "NATIVE_ORDER", "Ljava/nio/ByteOrder;"));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets",
                "UTF_16LE", "Ljava/nio/charset/Charset;"));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "UTF16", "Ljava/nio/charset/Charset;"));

        String[] bufferFields = {
            "BUFFER_BYTE", "BUFFER_SHORT", "BUFFER_CHAR", "BUFFER_INT",
            "BUFFER_LONG", "BUFFER_FLOAT", "BUFFER_DOUBLE"
        };
        for (int index = 0; index < bufferFields.length; index++) {
            code.add(new IntInsnNode(Opcodes.BIPUSH, index));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, BROWSER_MEMORY, "bufferClass",
                    "(I)Ljava/lang/Class;", false));
            code.add(new FieldInsnNode(
                    Opcodes.PUTSTATIC, MEMORY_UTIL, bufferFields[index], "Ljava/lang/Class;"));
        }
        for (String field : new String[] {
                "MARK", "POSITION", "LIMIT", "CAPACITY", "ADDRESS", "PARENT_BYTE",
                "PARENT_SHORT", "PARENT_CHAR", "PARENT_INT", "PARENT_LONG",
                "PARENT_FLOAT", "PARENT_DOUBLE"
        }) {
            code.add(new InsnNode(Opcodes.LCONST_0));
            code.add(new FieldInsnNode(Opcodes.PUTSTATIC, MEMORY_UTIL, field, "J"));
        }
        putInt(code, "PAGE_SIZE", 65536);
        putInt(code, "CACHE_LINE_SIZE", 64);
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }

    /** LWJGL 3.4.2+ initializer: only fields that exist, no native page-size query. */
    private static InsnList backendMemoryUtilInitializer() {
        InsnList code = new InsnList();
        putInt(code, "ARRAY_TLC_SIZE", 8192);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, BROWSER_MEMORY, "byteArrays",
                "()Ljava/lang/ThreadLocal;", false));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "ARRAY_TLC_BYTE", "Ljava/lang/ThreadLocal;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, BROWSER_MEMORY, "charArrays",
                "()Ljava/lang/ThreadLocal;", false));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "ARRAY_TLC_CHAR", "Ljava/lang/ThreadLocal;"));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets",
                "UTF_16LE", "Ljava/nio/charset/Charset;"));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "UTF16", "Ljava/nio/charset/Charset;"));
        putInt(code, "PAGE_SIZE", 65536);
        putInt(code, "CACHE_LINE_SIZE", 64);
        code.add(browserMemoryBackendInstance());
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "BACKEND", "L" + MEMORY_BACKEND + ";"));
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }

    private static FieldInsnNode browserMemoryBackendInstance() {
        return new FieldInsnNode(
                Opcodes.GETSTATIC,
                BROWSER_MEMORY_BACKEND,
                "INSTANCE",
                "L" + BROWSER_MEMORY_BACKEND + ";");
    }

    private static void putInt(InsnList code, String field, int value) {
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            code.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            code.add(new org.objectweb.asm.tree.LdcInsnNode(value));
        }
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, MEMORY_UTIL, field, "I"));
    }

    private static void replace(MethodNode method, InsnList code, int maxStack) {
        method.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
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

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " not found");
            }
            try (var stream = jar.getInputStream(entry)) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean contains(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            return jar.getEntry(entryName) != null;
        }
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static Map<String, String> delegates() {
        Map<String, String> methods = new HashMap<>();
        methods.put("nmemAlloc(J)J", "allocate");
        methods.put("nmemAllocChecked(J)J", "allocate");
        methods.put("nmemCalloc(JJ)J", "calloc");
        methods.put("nmemCallocChecked(JJ)J", "calloc");
        methods.put("nmemRealloc(JJ)J", "reallocate");
        methods.put("nmemReallocChecked(JJ)J", "reallocate");
        methods.put("nmemAlignedAlloc(JJ)J", "alignedAllocate");
        methods.put("nmemAlignedAllocChecked(JJ)J", "alignedAllocate");
        methods.put("nmemFree(J)V", "free");
        methods.put("nmemAlignedFree(J)V", "free");
        methods.put("memFree(Ljava/nio/Buffer;)V", "free");
        methods.put("memAddress0(Ljava/nio/Buffer;)J", "address0");
        methods.put("memByteBuffer(JI)Ljava/nio/ByteBuffer;", "byteBuffer");
        methods.put("memRealloc(Ljava/nio/ByteBuffer;I)Ljava/nio/ByteBuffer;", "reallocate");
        methods.put("memRealloc(Ljava/nio/ShortBuffer;I)Ljava/nio/ShortBuffer;", "reallocate");
        methods.put("memRealloc(Ljava/nio/IntBuffer;I)Ljava/nio/IntBuffer;", "reallocate");
        methods.put("memRealloc(Ljava/nio/LongBuffer;I)Ljava/nio/LongBuffer;", "reallocate");
        methods.put("memRealloc(Ljava/nio/FloatBuffer;I)Ljava/nio/FloatBuffer;", "reallocate");
        methods.put("memRealloc(Ljava/nio/DoubleBuffer;I)Ljava/nio/DoubleBuffer;", "reallocate");
        methods.put("memGetByte(J)B", "getByte");
        methods.put("memGetBoolean(J)Z", "getBoolean");
        methods.put("memGetShort(J)S", "getShort");
        methods.put("memGetInt(J)I", "getInt");
        methods.put("memGetLong(J)J", "getLong");
        methods.put("memGetFloat(J)F", "getFloat");
        methods.put("memGetDouble(J)D", "getDouble");
        methods.put("memGetAddress(J)J", "getLong");
        methods.put("memPutByte(JB)V", "putByte");
        methods.put("memPutShort(JS)V", "putShort");
        methods.put("memPutInt(JI)V", "putInt");
        methods.put("memPutLong(JJ)V", "putLong");
        methods.put("memPutFloat(JF)V", "putFloat");
        methods.put("memPutDouble(JD)V", "putDouble");
        methods.put("memPutAddress(JJ)V", "putLong");
        methods.put("memSet(JIJ)V", "set");
        methods.put("memCopy(JJJ)V", "copy");
        methods.put("memLengthNT1(JI)I", "lengthNt1");
        methods.put("memLengthNT2(JI)I", "lengthNt2");
        methods.put("strlen64NT1(JI)I", "lengthNt1");
        methods.put("strlen32NT1(JI)I", "lengthNt1");
        methods.put("strlen64NT2(JI)I", "lengthNt2");
        methods.put("strlen32NT2(JI)I", "lengthNt2");
        methods.put("memASCII(JI)Ljava/lang/String;", "decodeAscii");
        methods.put("memGetCLong(J)J", "getCLong");
        methods.put("memPutCLong(JJ)V", "putCLong");
        methods.put("memSlice(Ljava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/ShortBuffer;)Ljava/nio/ShortBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/CharBuffer;)Ljava/nio/CharBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/IntBuffer;)Ljava/nio/IntBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/LongBuffer;)Ljava/nio/LongBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/FloatBuffer;)Ljava/nio/FloatBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/DoubleBuffer;)Ljava/nio/DoubleBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/ByteBuffer;II)Ljava/nio/ByteBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/ShortBuffer;II)Ljava/nio/ShortBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/CharBuffer;II)Ljava/nio/CharBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/IntBuffer;II)Ljava/nio/IntBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/LongBuffer;II)Ljava/nio/LongBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/FloatBuffer;II)Ljava/nio/FloatBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/DoubleBuffer;II)Ljava/nio/DoubleBuffer;", "slice");
        methods.put("write8(JII)I", "write8");
        methods.put("write8Safe(JIII)I", "write8Safe");
        methods.put("write16(JIC)I", "write16");
        methods.put(
                "slice(Ljava/nio/ByteBuffer;JI)Ljava/nio/ByteBuffer;", "slice");
        methods.put(
                "slice(Ljava/lang/Class;Ljava/nio/Buffer;JIJ)Ljava/nio/Buffer;", "slice");
        methods.put(
                "duplicate(Ljava/lang/Class;Ljava/nio/Buffer;J)Ljava/nio/Buffer;", "duplicate");
        methods.put("wrap(Ljava/lang/Class;JI)Ljava/nio/Buffer;", "wrap");
        return methods;
    }
}
