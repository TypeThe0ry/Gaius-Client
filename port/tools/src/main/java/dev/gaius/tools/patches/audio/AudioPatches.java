package dev.gaius.tools.patches.audio;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findNullable;
import static dev.gaius.tools.patches.support.ClientPatchSupport.getter;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.putConstructorField;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeBytes;

import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The audio domain of the MinecraftClientPatcher step ({@code patches/audio}): the
 * blaze3d OpenAL library and listener on the browser audio bridge, the {@code javax.sound} API
 * stubs the client links against, and the browser mining hit sound volume.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically.
 */
public final class AudioPatches {
    private AudioPatches() {
    }

    public static void patchBrowserAudio(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/audio/Library.class");
        String owner = "com/mojang/blaze3d/audio/Library";
        String poolDescriptor = "Lcom/mojang/blaze3d/audio/Library$ChannelPool;";
        String countingPool = "com/mojang/blaze3d/audio/Library$CountingChannelPool";
        boolean trackedDevices = node.methods.stream()
                .anyMatch(method -> method.name.equals("createDeviceTracker")
                        && method.desc.equals("()Lcom/mojang/blaze3d/audio/DeviceTracker;"));

        MethodNode init = find(
                node,
                "init",
                trackedDevices
                        ? "(Ljava/lang/String;Lcom/mojang/blaze3d/audio/DeviceList;Z)V"
                        : "(Ljava/lang/String;Z)V");
        int initLocals = init.maxLocals;
        InsnList initCode = new InsnList();
        initCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/openal/BrowserOpenAL",
                "init",
                "()V",
                false));
        initCode.add(new VarInsnNode(Opcodes.ILOAD, trackedDevices ? 3 : 2));
        initCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/openal/BrowserOpenAL",
                "setDirectionalAudio",
                "(Z)V",
                false));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new TypeInsnNode(Opcodes.NEW, countingPool));
        initCode.add(new InsnNode(Opcodes.DUP));
        initCode.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 30));
        initCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                countingPool,
                "<init>",
                "(I)V",
                false));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "staticChannels", poolDescriptor));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new TypeInsnNode(Opcodes.NEW, countingPool));
        initCode.add(new InsnNode(Opcodes.DUP));
        initCode.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 8));
        initCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                countingPool,
                "<init>",
                "(I)V",
                false));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "streamingChannels", poolDescriptor));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.LCONST_1));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "currentDevice", "J"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.LCONST_1));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "context", "J"));
        if (trackedDevices) {
            initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            initCode.add(new LdcInsnNode("Gaius Browser OpenAL"));
            initCode.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "currentDeviceName", "Ljava/lang/String;"));
        }
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.ICONST_0));
        initCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "supportsDisconnections", "Z"));
        initCode.add(new InsnNode(Opcodes.RETURN));
        replace(init, initCode, 4, initLocals);

        MethodNode getCurrentDeviceName = find(
                node,
                trackedDevices ? "currentDeviceName" : "getCurrentDeviceName",
                "()Ljava/lang/String;");
        InsnList currentName = new InsnList();
        currentName.add(new VarInsnNode(Opcodes.ALOAD, 0));
        currentName.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                trackedDevices ? "currentDeviceName" : "defaultDeviceName",
                "Ljava/lang/String;"));
        currentName.add(new InsnNode(Opcodes.ARETURN));
        replace(getCurrentDeviceName, currentName, 1, 1);

        if (trackedDevices) {
            MethodNode createDeviceTracker = find(
                    node,
                    "createDeviceTracker",
                    "()Lcom/mojang/blaze3d/audio/DeviceTracker;");
            InsnList tracker = new InsnList();
            tracker.add(new TypeInsnNode(
                    Opcodes.NEW, "dev/gaius/browser/BrowserDeviceTracker"));
            tracker.add(new InsnNode(Opcodes.DUP));
            tracker.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "dev/gaius/browser/BrowserDeviceTracker",
                    "<init>",
                    "()V",
                    false));
            tracker.add(new InsnNode(Opcodes.ARETURN));
            replace(createDeviceTracker, tracker, 2, 0);
        } else {
            MethodNode getDefaultDeviceName = find(
                    node, "getDefaultDeviceName", "()Ljava/lang/String;");
            InsnList defaultName = new InsnList();
            defaultName.add(new LdcInsnNode("Gaius Browser OpenAL"));
            defaultName.add(new InsnNode(Opcodes.ARETURN));
            replace(getDefaultDeviceName, defaultName, 1, 0);

            MethodNode hasDefaultDeviceChanged = find(
                    node, "hasDefaultDeviceChanged", "()Z");
            InsnList falseResult = new InsnList();
            falseResult.add(new InsnNode(Opcodes.ICONST_0));
            falseResult.add(new InsnNode(Opcodes.IRETURN));
            replace(hasDefaultDeviceChanged, falseResult, 1, 1);

            MethodNode getAvailableSoundDevices = find(
                    node, "getAvailableSoundDevices", "()Ljava/util/List;");
            InsnList emptyList = new InsnList();
            emptyList.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/Collections",
                    "emptyList",
                    "()Ljava/util/List;",
                    false));
            emptyList.add(new InsnNode(Opcodes.ARETURN));
            replace(getAvailableSoundDevices, emptyList, 1, 1);
        }

        MethodNode isCurrentDeviceDisconnected = find(
                node, "isCurrentDeviceDisconnected", "()Z");
        InsnList connected = new InsnList();
        connected.add(new InsnNode(Opcodes.ICONST_0));
        connected.add(new InsnNode(Opcodes.IRETURN));
        replace(isCurrentDeviceDisconnected, connected, 1, 1);

        MethodNode cleanup = find(node, "cleanup", "()V");
        InsnList cleanupCode = new InsnList();
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "staticChannels", poolDescriptor));
        LabelNode skipStaticCleanup = new LabelNode();
        cleanupCode.add(new JumpInsnNode(Opcodes.IFNULL, skipStaticCleanup));
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "staticChannels", poolDescriptor));
        cleanupCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "com/mojang/blaze3d/audio/Library$ChannelPool",
                "cleanup",
                "()V",
                true));
        cleanupCode.add(skipStaticCleanup);
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "streamingChannels", poolDescriptor));
        LabelNode skipStreamingCleanup = new LabelNode();
        cleanupCode.add(new JumpInsnNode(Opcodes.IFNULL, skipStreamingCleanup));
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "streamingChannels", poolDescriptor));
        cleanupCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "com/mojang/blaze3d/audio/Library$ChannelPool",
                "cleanup",
                "()V",
                true));
        cleanupCode.add(skipStreamingCleanup);
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC, owner, "EMPTY", poolDescriptor));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "staticChannels", poolDescriptor));
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC, owner, "EMPTY", poolDescriptor));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "streamingChannels", poolDescriptor));
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new InsnNode(Opcodes.LCONST_0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "currentDevice", "J"));
        cleanupCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanupCode.add(new InsnNode(Opcodes.LCONST_0));
        cleanupCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "context", "J"));
        cleanupCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/openal/BrowserOpenAL",
                "cleanup",
                "()V",
                false));
        cleanupCode.add(new InsnNode(Opcodes.RETURN));
        replace(cleanup, cleanupCode, 3, 1);

        write(node, output);
    }

    public static void patchBrowserAudioListener(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/audio/Listener.class");
        String owner = "com/mojang/blaze3d/audio/Listener";
        String transform = "com/mojang/blaze3d/audio/ListenerTransform";
        MethodNode setTransform = find(
                node,
                "setTransform",
                "(Lcom/mojang/blaze3d/audio/ListenerTransform;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "transform",
                "Lcom/mojang/blaze3d/audio/ListenerTransform;"));
        // One Web Audio update per transform (listener3f AL_POSITION followed by
        // listenerOrientation, in one JavaScript call; see BrowserOpenAL.listenerTransform).
        appendListenerVector(code, transform, "position");
        appendListenerVector(code, transform, "forward");
        appendListenerVector(code, transform, "up");
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/openal/BrowserOpenAL",
                "listenerTransform",
                "(FFFFFFFFF)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(setTransform, code, 11, 2);
        write(node, output);
    }

    private static void appendListenerVector(
            InsnList code, String transformOwner, String accessor) {
        for (String component : new String[] {"x", "y", "z"}) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    transformOwner,
                    accessor,
                    "()Lnet/minecraft/world/phys/Vec3;",
                    false));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/world/phys/Vec3",
                    component,
                    "D"));
            code.add(new InsnNode(Opcodes.D2F));
        }
    }

    public static void patchMultiPlayerGameModeBrowserHitSound(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/multiplayer/MultiPlayerGameMode.class");
        if (!invokesMethod(node, "net/minecraft/world/level/block/SoundType", "getHitSound",
                "()Lnet/minecraft/sounds/SoundEvent;")) {
            dropMultiPlayerGameModeBrowserHitSound(jar);
            return;
        }
        MethodNode method = find(
                node,
                "continueDestroyBlock",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z");
        boolean foundHitSound = false;
        int patchedVolumeDivisors = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/world/level/block/SoundType")
                    && call.name.equals("getHitSound")
                    && call.desc.equals("()Lnet/minecraft/sounds/SoundEvent;")) {
                foundHitSound = true;
                continue;
            }
            if (foundHitSound
                    && instruction instanceof LdcInsnNode constant
                    && Float.valueOf(8.0f).equals(constant.cst)
                    && nextOpcode(instruction) != null
                    && nextOpcode(instruction).getOpcode() == Opcodes.FDIV) {
                constant.cst = Float.valueOf(4.0f);
                patchedVolumeDivisors++;
                break;
            }
        }
        if (!foundHitSound || patchedVolumeDivisors != 1) {
            throw new IllegalStateException(
                    "MultiPlayerGameMode browser hit sound volume patch point was not found");
        }
        write(node, output);
    }

    /**
     * 26.3 moved the mining hit sound out of {@code MultiPlayerGameMode.continueDestroyBlock}:
     * {@code ClientLevel.addBreakingBlockEffects(BlockPos, Direction, boolean)} (which replaced
     * 26.2's {@code addBreakingBlockEffect(BlockPos, Direction)}) plays it through the private
     * {@code playBreakingSound(BlockPos, BlockState)}, for the first hit and for the server's
     * periodic level event 2020. This patch is therefore dropped; UiPatches263 applies the same
     * 8.0f to 4.0f volume change to playBreakingSound at the tail of the chain, reading the
     * ClientLevel that patchClientLevelBrowserBlockBreakEffects wrote. Both facts are checked
     * here, so neither a leftover MultiPlayerGameMode hit sound nor a missing new target can pass.
     */
    private static void dropMultiPlayerGameModeBrowserHitSound(String jar) throws IOException {
        String patchId = "MinecraftClientPatcher.patchMultiPlayerGameModeBrowserHitSound";
        ClassNode level = read(jar, "net/minecraft/client/multiplayer/ClientLevel.class");
        MethodNode breakingSound = findNullable(level, "playBreakingSound",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V");
        if (breakingSound == null || !invokesMethod(breakingSound,
                "net/minecraft/world/level/block/SoundType", "getHitSound",
                "()Lnet/minecraft/sounds/SoundEvent;")) {
            throw new IllegalStateException("MultiPlayerGameMode no longer plays the mining hit"
                    + " sound, but ClientLevel.playBreakingSound(BlockPos, BlockState) with"
                    + " SoundType.getHitSound was not found either");
        }
        PatchRegistry.dropped(patchId, jar,
                "net/minecraft/client/multiplayer/ClientLevel#addBreakingBlockEffect"
                        + "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)V");
    }

    private static boolean invokesMethod(ClassNode node, String owner, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (invokesMethod(method, owner, name, desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean invokesMethod(MethodNode method, String owner, String name, String desc) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    public static void generateSoundApiStubs(Path root) throws IOException {
        ClassWriter encoding = new ClassWriter(0);
        encoding.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "javax/sound/sampled/AudioFormat$Encoding", null,
                "java/lang/Object", null);
        encoding.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "PCM_SIGNED", "Ljavax/sound/sampled/AudioFormat$Encoding;", null, null).visitEnd();
        encoding.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "PCM_UNSIGNED", "Ljavax/sound/sampled/AudioFormat$Encoding;", null, null).visitEnd();
        var constructor = encoding.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        var init = encoding.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        for (String field : new String[] {"PCM_SIGNED", "PCM_UNSIGNED"}) {
            init.visitTypeInsn(Opcodes.NEW, "javax/sound/sampled/AudioFormat$Encoding");
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "javax/sound/sampled/AudioFormat$Encoding",
                    "<init>",
                    "()V",
                    false);
            init.visitFieldInsn(
                    Opcodes.PUTSTATIC,
                    "javax/sound/sampled/AudioFormat$Encoding",
                    field,
                    "Ljavax/sound/sampled/AudioFormat$Encoding;");
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 0);
        init.visitEnd();
        encoding.visitEnd();
        writeBytes(root.resolve("javax/sound/sampled/AudioFormat$Encoding.class"),
                encoding.toByteArray());

        ClassWriter format = new ClassWriter(0);
        String owner = "javax/sound/sampled/AudioFormat";
        format.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                owner, null, "java/lang/Object", null);
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "encoding",
                "Ljavax/sound/sampled/AudioFormat$Encoding;", null, null).visitEnd();
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "sampleRate",
                "F", null, null).visitEnd();
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "sampleSize",
                "I", null, null).visitEnd();
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "channels",
                "I", null, null).visitEnd();
        var formatConstructor = format.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(FIIZZ)V", null, null);
        formatConstructor.visitCode();
        formatConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        formatConstructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        formatConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        formatConstructor.visitVarInsn(Opcodes.ILOAD, 4);
        org.objectweb.asm.Label unsigned = new org.objectweb.asm.Label();
        org.objectweb.asm.Label encodingDone = new org.objectweb.asm.Label();
        formatConstructor.visitJumpInsn(Opcodes.IFEQ, unsigned);
        formatConstructor.visitFieldInsn(
                Opcodes.GETSTATIC,
                "javax/sound/sampled/AudioFormat$Encoding",
                "PCM_SIGNED",
                "Ljavax/sound/sampled/AudioFormat$Encoding;");
        formatConstructor.visitJumpInsn(Opcodes.GOTO, encodingDone);
        formatConstructor.visitLabel(unsigned);
        formatConstructor.visitFieldInsn(
                Opcodes.GETSTATIC,
                "javax/sound/sampled/AudioFormat$Encoding",
                "PCM_UNSIGNED",
                "Ljavax/sound/sampled/AudioFormat$Encoding;");
        formatConstructor.visitLabel(encodingDone);
        formatConstructor.visitFieldInsn(
                Opcodes.PUTFIELD, owner, "encoding",
                "Ljavax/sound/sampled/AudioFormat$Encoding;");
        putConstructorField(formatConstructor, owner, "sampleRate", "F", Opcodes.FLOAD, 1);
        putConstructorField(formatConstructor, owner, "sampleSize", "I", Opcodes.ILOAD, 2);
        putConstructorField(formatConstructor, owner, "channels", "I", Opcodes.ILOAD, 3);
        formatConstructor.visitInsn(Opcodes.RETURN);
        formatConstructor.visitMaxs(3, 6);
        formatConstructor.visitEnd();
        getter(format, owner, "getEncoding",
                "()Ljavax/sound/sampled/AudioFormat$Encoding;",
                "encoding", "Ljavax/sound/sampled/AudioFormat$Encoding;", Opcodes.ARETURN);
        getter(format, owner, "getSampleRate", "()F", "sampleRate", "F", Opcodes.FRETURN);
        getter(format, owner, "getSampleSizeInBits", "()I", "sampleSize", "I", Opcodes.IRETURN);
        getter(format, owner, "getChannels", "()I", "channels", "I", Opcodes.IRETURN);
        format.visitEnd();
        writeBytes(root.resolve("javax/sound/sampled/AudioFormat.class"), format.toByteArray());
    }
}
