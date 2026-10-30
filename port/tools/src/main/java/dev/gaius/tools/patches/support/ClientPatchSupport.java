package dev.gaius.tools.patches.support;

import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.VersionProfile;
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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The ASM helpers that more than one domain of the MinecraftClientPatcher step uses
 * ({@code patches/<domain>}): class reads and writes, method lookup and replacement, instruction
 * navigation, and the browser calls several domains weave (startup phases, Minecraft events, the
 * channel pump, deep worldgen pulses), plus the modern patch set test of the profile. Not a
 * domain: it holds no patch. Moved verbatim out of MinecraftClientPatcher, which keeps the
 * registrations; the domain classes import these helpers statically.
 */
public final class ClientPatchSupport {
    private ClientPatchSupport() {
    }

    /**
     * Whether the profile takes the modern patch set: {@code "patchSet": "modern"} in its
     * port/versions JSON ({@link VersionProfile}), the field build-overlays.sh picks the patch
     * chain from. Modern profiles share the named, Java 25 client layout; differences between
     * modern jars are probed with {@link ModernSymbols}, not derived from the version id.
     */
    public static boolean isModernProfile(String minecraftVersion) {
        return VersionProfile.forId(minecraftVersion).modernPatchSet();
    }

    public static void verifyBrowserUuidCalls(MethodNode method, int expectedRandomUuidCalls) {
        int randomUuidCalls = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC) {
                continue;
            }
            if (call.owner.equals("net/minecraft/util/Mth")
                    && call.name.equals("createInsecureUUID")
                    && call.desc.equals("()Ljava/util/UUID;")) {
                throw new IllegalStateException(
                        "Browser patch introduced missing Mth.createInsecureUUID() in "
                                + method.name + method.desc);
            }
            if (call.owner.equals("java/util/UUID")
                    && call.name.equals("randomUUID")
                    && call.desc.equals("()Ljava/util/UUID;")) {
                randomUuidCalls++;
            }
        }
        if (randomUuidCalls != expectedRandomUuidCalls) {
            throw new IllegalStateException(
                    "Expected " + expectedRandomUuidCalls + " UUID.randomUUID calls in "
                            + method.name + method.desc + ", got " + randomUuidCalls);
        }
    }

    public static boolean jarHasClass(String jar, String owner) throws IOException {
        try (ZipFile input = new ZipFile(jar)) {
            return input.getEntry(owner + ".class") != null;
        }
    }

    public static AbstractInsnNode previousRealInstruction(AbstractInsnNode instruction) {
        if (instruction == null) {
            return null;
        }
        var previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    public static AbstractInsnNode nextRealInstruction(AbstractInsnNode instruction) {
        if (instruction == null) {
            return null;
        }
        var next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    public static InsnList serverStartupPhase(String phase) {
        InsnList checkpoint = new InsnList();
        checkpoint.add(new LdcInsnNode(phase));
        checkpoint.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserStartupScheduler",
                "phase",
                "(Ljava/lang/String;)V",
                false));
        return checkpoint;
    }

    /**
     * The name of the 26.2 render class {@code key} in {@code symbols}' jar (for example the
     * renderpearl name on 26.3), or {@code key} itself when the jar has no such class (26.2, or
     * an older blaze3d generation that lacks it). Used by the P3 render patches.
     */
    public static String renderName(ModernSymbols symbols, String key) {
        return symbols.renderTypePresent(key) ? symbols.renderType(key) : key;
    }

    public static boolean replaceLocalServerCheck(MethodNode method) {
        int replacements = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/client/Minecraft")
                    || !call.name.equals("isLocalServer")
                    || !call.desc.equals("()Z")) {
                continue;
            }
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = "dev/gaius/browser/BrowserSingleplayerClient";
            call.name = "isLocalSession";
            call.desc = "(Lnet/minecraft/client/Minecraft;)Z";
            replacements++;
        }
        return replacements == 1;
    }

    public static MethodInsnNode pumpBrowserChannels() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserClientNetwork",
                "pumpBrowserChannelsAtFrameBoundary",
                "()V",
                false);
    }

    private static MethodInsnNode browserWorldgenDeepCheckpoint() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenDeepCheckpoint",
                "checkpoint",
                "()V",
                false);
    }

    public static MethodInsnNode browserWorldgenDeepPulse() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenDeepCheckpoint",
                "pulse",
                "()V",
                false);
    }

    /** Like {@link #patchDeepWorldgenPulseBackEdges} but requires an exact pulse count. */
    public static void patchDeepWorldgenPulseBackEdges(
            MethodNode method, String label, int expectedPulses) {
        int pulses = patchDeepWorldgenPulseBackEdges(method, label);
        if (pulses != expectedPulses) {
            throw new IllegalStateException(label + " deep worldgen back-edges changed: "
                    + pulses + " (expected " + expectedPulses + ")");
        }
    }

    /** Inserts budget-aware pulses at all backwards control-flow edges in one deep loop. */
    public static int patchDeepWorldgenPulseBackEdges(MethodNode method, String label) {
        int pulses = 0;
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = 0; index < instructions.length; index++) {
            AbstractInsnNode instruction = instructions[index];
            if (!(instruction instanceof JumpInsnNode jump)
                    || method.instructions.indexOf(jump.label) >= index) {
                continue;
            }
            method.instructions.insertBefore(jump, browserWorldgenDeepPulse());
            pulses++;
        }
        if (pulses == 0) {
            throw new IllegalStateException(label + " has no deep worldgen loop back-edge");
        }
        return pulses;
    }

    public static void requireWorldgenSchedulerCalls(
            String label, MethodNode method, int expectedCalls) {
        int pulses = 0;
        for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("dev/gaius/browser/BrowserWorldgenScheduler")
                    && call.desc.equals("()V")) {
                pulses++;
            }
        }
        if (pulses != expectedCalls) {
            throw new IllegalStateException(
                    label + " browser scheduler calls changed: " + pulses
                            + " (expected " + expectedCalls + ")");
        }
    }

    public static MethodNode findOrCreateMethod(ClassNode node, int access, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        MethodNode method = new MethodNode(access, name, desc, null, null);
        node.methods.add(method);
        return method;
    }

    public static void putConstructorField(
            org.objectweb.asm.MethodVisitor method, String owner,
            String field, String descriptor, int loadOpcode, int local) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(loadOpcode, local);
        method.visitFieldInsn(Opcodes.PUTFIELD, owner, field, descriptor);
    }

    public static void putField(
            InsnList code, String owner, String field, String descriptor,
            int loadOpcode, int local) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(loadOpcode, local));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, descriptor));
    }

    public static InsnList minecraftEvent(String event) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(event));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportMinecraftEvent",
                "(Ljava/lang/String;)V",
                false));
        return code;
    }

    public static void getter(
            ClassWriter writer, String owner, String name, String descriptor,
            String field, String fieldDescriptor, int returnOpcode) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, field, fieldDescriptor);
        method.visitInsn(returnOpcode);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }

    public static void writeBytes(Path output, byte[] bytes) throws IOException {
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
    }

    private static void replaceEmptyBytes(ClassNode node, String name, String descriptor) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(find(node, name, descriptor), code, 1, Type.getArgumentsAndReturnSizes(descriptor) >> 2);
    }

    public static void replace(MethodNode method, InsnList code, int maxStack, int maxLocals) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = maxStack;
        method.maxLocals = maxLocals;
    }

    public static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + descriptor + " was not found"));
    }

    public static MethodNode findNullable(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElse(null);
    }

    public static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    public static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    public static ClassNode read(String jarPath, String entryName) throws IOException {
        byte[] bytes = readEntry(jarPath, entryName);
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    public static byte[] readEntry(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IOException("Missing class entry " + entryName + " in " + jarPath);
            }
            try (var stream = jar.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        }
    }

    public static ClassNode read(Path path) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(path)).accept(node, 0);
        return node;
    }

    public static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    public static void writeComputeFrames(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
