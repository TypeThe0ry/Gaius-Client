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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Redirects the OpenAL subset used by Minecraft 1.21.11 to Web Audio. */
public final class LwjglOpenALBrowserPatcher {
    private static final String BROWSER = "org/lwjgl/openal/BrowserOpenAL";

    private LwjglOpenALBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LwjglOpenALBrowserPatcher INPUT_JAR OUTPUT_ROOT");
        }
        int replacements = patchAlc10(args[0], Path.of(args[1]));
        Map<String, String> delegates = delegates();
        Set<String> owners = new HashSet<>();
        delegates.keySet().forEach(key -> owners.add(key.substring(0, key.indexOf('#'))));
        owners.add("org/lwjgl/openal/AL10");
        owners.add("org/lwjgl/openal/AL11");
        for (String owner : owners) {
            ClassNode node = read(args[0], owner + ".class");
            boolean changed = false;
            for (MethodNode method : node.methods) {
                if (method.name.equals("<clinit>") || (method.access & Opcodes.ACC_STATIC) == 0) {
                    continue;
                }
                String key = owner + "#" + method.name + method.desc;
                String target = delegates.get(key);
                if (target != null) {
                    delegate(method, target);
                    changed = true;
                    replacements++;
                } else if (owner.equals("org/lwjgl/openal/AL10")
                        || owner.equals("org/lwjgl/openal/AL11")) {
                    replaceDefault(method);
                    changed = true;
                    replacements++;
                }
            }
            if (changed) {
                write(node, Path.of(args[1]).resolve(owner + ".class"));
            }
        }
        if (replacements < 120) {
            throw new IllegalStateException("Too few OpenAL methods patched: " + replacements);
        }
        System.out.println("Patched " + replacements + " OpenAL methods");
    }

    private static int patchAlc10(String jar, Path root) throws IOException {
        ClassNode alc10 = read(jar, "org/lwjgl/openal/ALC10.class");
        int replacements = 0;
        for (MethodNode method : alc10.methods) {
            if (method.name.equals("<clinit>") || (method.access & Opcodes.ACC_STATIC) == 0) {
                continue;
            }
            String key = method.name + method.desc;
            switch (key) {
                case "nalcOpenDevice(J)J",
                        "alcOpenDevice(Ljava/nio/ByteBuffer;)J",
                        "alcOpenDevice(Ljava/lang/CharSequence;)J",
                        "nalcCreateContext(JJ)J",
                        "alcCreateContext(JLjava/nio/IntBuffer;)J",
                        "alcCreateContext(J[I)J",
                        "alcGetCurrentContext()J",
                        "alcGetContextsDevice(J)J" -> replaceConstant(method, Type.LONG_TYPE, 1L);
                case "alcCloseDevice(J)Z",
                        "alcMakeContextCurrent(J)Z" ->
                        replaceConstant(method, Type.BOOLEAN_TYPE, 1);
                case "alcIsExtensionPresent(JLjava/lang/CharSequence;)Z",
                        "alcIsExtensionPresent(JLjava/nio/ByteBuffer;)Z" ->
                        delegate(method, "alcIsExtensionPresent");
                case "alcGetError(J)I",
                        "nalcGetEnumValue(JJ)I",
                        "alcGetEnumValue(JLjava/nio/ByteBuffer;)I",
                        "alcGetEnumValue(JLjava/lang/CharSequence;)I" ->
                        replaceConstant(method, Type.INT_TYPE, 0);
                case "alcGetInteger(JI)I" -> delegate(method, "alcGetInteger");
                case "alcGetString(JI)Ljava/lang/String;" ->
                        replaceString(method, "Gaius Browser OpenAL");
                case "alcProcessContext(J)V",
                        "alcSuspendContext(J)V",
                        "alcDestroyContext(J)V",
                        "nalcGetIntegerv(JIIJ)V" -> replaceVoid(method);
                case "alcGetIntegerv(JILjava/nio/IntBuffer;)V",
                        "alcGetIntegerv(JI[I)V" -> delegate(method, "alcGetIntegerv");
                default -> replaceDefault(method);
            }
            replacements++;
        }
        write(alc10, root.resolve("org/lwjgl/openal/ALC10.class"));
        return replacements;
    }

    private static Map<String, String> delegates() {
        Map<String, String> methods = new HashMap<>();
        add(methods, "AL10", "alGetError", "()I", "getError");
        add(methods, "AL10", "alGetInteger", "(I)I", "getInteger");
        add(methods, "AL10", "alGetFloat", "(I)F", "getFloat");
        add(methods, "AL10", "alGetDouble", "(I)D", "getDouble");
        add(methods, "AL10", "alGetString", "(I)Ljava/lang/String;", "getString");
        add(methods, "AL10", "alDistanceModel", "(I)V", "distanceModel");
        add(methods, "AL10", "alDopplerFactor", "(F)V", "dopplerFactor");
        add(methods, "AL10", "alDopplerVelocity", "(F)V", "dopplerVelocity");
        add(methods, "AL10", "alListenerf", "(IF)V", "listenerf");
        add(methods, "AL10", "alListeneri", "(II)V", "listeneri");
        add(methods, "AL10", "alListener3f", "(IFFF)V", "listener3f");
        add(methods, "AL10", "alListenerfv", "(ILjava/nio/FloatBuffer;)V", "listenerfv");
        add(methods, "AL10", "alListenerfv", "(I[F)V", "listenerfv");
        add(methods, "AL10", "alGenSources", "(Ljava/nio/IntBuffer;)V", "genSources");
        add(methods, "AL10", "alGenSources", "()I", "genSource");
        add(methods, "AL10", "alGenSources", "([I)V", "genSources");
        add(methods, "AL10", "alDeleteSources", "(Ljava/nio/IntBuffer;)V", "deleteSources");
        add(methods, "AL10", "alDeleteSources", "(I)V", "deleteSource");
        add(methods, "AL10", "alDeleteSources", "([I)V", "deleteSources");
        add(methods, "AL10", "alIsSource", "(I)Z", "isSource");
        add(methods, "AL10", "alSourcef", "(IIF)V", "sourcef");
        add(methods, "AL10", "alSource3f", "(IIFFF)V", "source3f");
        add(methods, "AL10", "alSourcefv", "(IILjava/nio/FloatBuffer;)V", "sourcefv");
        add(methods, "AL10", "alSourcefv", "(II[F)V", "sourcefv");
        add(methods, "AL10", "alSourcei", "(III)V", "sourcei");
        add(methods, "AL10", "alGetSourcei", "(II)I", "getSourcei");
        add(methods, "AL10", "alGetSourcei", "(IILjava/nio/IntBuffer;)V", "getSourcei");
        add(methods, "AL10", "alGetSourcei", "(II[I)V", "getSourcei");
        add(methods, "AL10", "alGetSourceiv", "(IILjava/nio/IntBuffer;)V", "getSourceiv");
        add(methods, "AL10", "alGetSourceiv", "(II[I)V", "getSourceiv");
        add(methods, "AL10", "alSourceQueueBuffers", "(ILjava/nio/IntBuffer;)V", "sourceQueueBuffers");
        add(methods, "AL10", "alSourceQueueBuffers", "(II)V", "sourceQueueBuffers");
        add(methods, "AL10", "alSourceQueueBuffers", "(I[I)V", "sourceQueueBuffers");
        add(methods, "AL10", "alSourceUnqueueBuffers", "(ILjava/nio/IntBuffer;)V", "sourceUnqueueBuffers");
        add(methods, "AL10", "alSourceUnqueueBuffers", "(I)I", "sourceUnqueueBuffer");
        add(methods, "AL10", "alSourceUnqueueBuffers", "(I[I)V", "sourceUnqueueBuffers");
        add(methods, "AL10", "alSourcePlay", "(I)V", "sourcePlay");
        add(methods, "AL10", "alSourcePause", "(I)V", "sourcePause");
        add(methods, "AL10", "alSourceStop", "(I)V", "sourceStop");
        add(methods, "AL10", "alSourceRewind", "(I)V", "sourceRewind");
        add(methods, "AL10", "alSourcePlayv", "(Ljava/nio/IntBuffer;)V", "sourcePlayv");
        add(methods, "AL10", "alSourcePlayv", "([I)V", "sourcePlayv");
        add(methods, "AL10", "alSourcePausev", "(Ljava/nio/IntBuffer;)V", "sourcePausev");
        add(methods, "AL10", "alSourcePausev", "([I)V", "sourcePausev");
        add(methods, "AL10", "alSourceStopv", "(Ljava/nio/IntBuffer;)V", "sourceStopv");
        add(methods, "AL10", "alSourceStopv", "([I)V", "sourceStopv");
        add(methods, "AL10", "alGenBuffers", "(Ljava/nio/IntBuffer;)V", "genBuffers");
        add(methods, "AL10", "alGenBuffers", "()I", "genBuffer");
        add(methods, "AL10", "alGenBuffers", "([I)V", "genBuffers");
        add(methods, "AL10", "alDeleteBuffers", "(Ljava/nio/IntBuffer;)V", "deleteBuffers");
        add(methods, "AL10", "alDeleteBuffers", "(I)V", "deleteBuffer");
        add(methods, "AL10", "alDeleteBuffers", "([I)V", "deleteBuffers");
        add(methods, "AL10", "alIsBuffer", "(I)Z", "isBuffer");
        add(methods, "AL10", "alGetBufferi", "(II)I", "getBufferi");
        add(methods, "AL10", "alGetBufferi", "(IILjava/nio/IntBuffer;)V", "getBufferi");
        add(methods, "AL10", "alGetBufferi", "(II[I)V", "getBufferi");
        add(methods, "AL10", "alBufferData", "(IILjava/nio/ByteBuffer;I)V", "bufferData");
        add(methods, "AL10", "alBufferData", "(IILjava/nio/ShortBuffer;I)V", "bufferData");
        add(methods, "AL10", "alBufferData", "(IILjava/nio/IntBuffer;I)V", "bufferData");
        add(methods, "AL10", "alBufferData", "(IILjava/nio/FloatBuffer;I)V", "bufferData");
        add(methods, "AL10", "alBufferData", "(II[SI)V", "bufferData");
        add(methods, "AL10", "alBufferData", "(II[II)V", "bufferData");
        add(methods, "AL10", "alBufferData", "(II[FI)V", "bufferData");
        add(methods, "AL10", "alIsExtensionPresent", "(Ljava/lang/CharSequence;)Z", "isExtensionPresent");
        add(methods, "AL10", "alGetEnumValue", "(Ljava/lang/CharSequence;)I", "getEnumValue");
        add(methods, "AL10", "alGetProcAddress", "(Ljava/lang/CharSequence;)J", "getProcAddress");

        add(methods, "AL11", "alSource3i", "(IIIII)V", "source3i");
        add(methods, "AL11", "alSourceiv", "(IILjava/nio/IntBuffer;)V", "sourceiv");
        add(methods, "AL11", "alSourceiv", "(II[I)V", "sourceiv");
        add(methods, "AL11", "alBufferf", "(IIF)V", "bufferf");
        add(methods, "AL11", "alBuffer3f", "(IIFFF)V", "buffer3f");
        add(methods, "AL11", "alBufferfv", "(IILjava/nio/FloatBuffer;)V", "bufferfv");
        add(methods, "AL11", "alBufferfv", "(II[F)V", "bufferfv");
        add(methods, "AL11", "alBufferi", "(III)V", "bufferi");
        add(methods, "AL11", "alBuffer3i", "(IIIII)V", "buffer3i");
        add(methods, "AL11", "alBufferiv", "(IILjava/nio/IntBuffer;)V", "bufferiv");
        add(methods, "AL11", "alBufferiv", "(II[I)V", "bufferiv");
        add(methods, "AL11", "alSpeedOfSound", "(F)V", "speedOfSound");
        return methods;
    }

    private static void add(
            Map<String, String> methods, String owner, String method, String descriptor, String target) {
        methods.put("org/lwjgl/openal/" + owner + "#" + method + descriptor, target);
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IOException("Missing class in jar: " + entryName);
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

    private static void delegate(MethodNode method, String target) {
        InsnList code = new InsnList();
        int local = 0;
        int maxStack = 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
            maxStack += argument.getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BROWSER, target, method.desc, false));
        Type result = Type.getReturnType(method.desc);
        code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        replace(method, code, Math.max(1, Math.max(maxStack, result.getSize())), local);
    }

    private static void replaceConstant(MethodNode method, Type type, Object value) {
        InsnList code = new InsnList();
        if (type == Type.LONG_TYPE) {
            if (Long.valueOf(1L).equals(value)) {
                code.add(new InsnNode(Opcodes.LCONST_1));
            } else {
                code.add(new InsnNode(Opcodes.LCONST_0));
            }
            code.add(new InsnNode(Opcodes.LRETURN));
        } else {
            if (Integer.valueOf(1).equals(value)) {
                code.add(new InsnNode(Opcodes.ICONST_1));
            } else {
                code.add(new InsnNode(Opcodes.ICONST_0));
            }
            code.add(new InsnNode(type.getOpcode(Opcodes.IRETURN)));
        }
        replace(method, code, type == Type.LONG_TYPE ? 2 : 1,
                Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }

    private static void replaceString(MethodNode method, String value) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(value));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }

    private static void replaceVoid(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 0, Type.getArgumentsAndReturnSizes(method.desc) >> 2);
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
        replace(method, code, result.getSize(), Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }

    private static void replace(MethodNode method, InsnList code, int maxStack, int maxLocals) {
        method.access &= ~Opcodes.ACC_NATIVE;
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = Math.max(1, maxStack);
        method.maxLocals = Math.max(method.maxLocals, maxLocals);
    }
}
