package dev.gaius.tools;

import dev.gaius.tools.patches.network.NetworkPatches;
import dev.gaius.tools.patches.telemetry.TelemetryPatches;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Runs the two patches that rewrite ClientPacketListener
 * (NetworkPatches.patchSuppressInsecureServerToast and
 * TelemetryPatches.patchClientPacketListenerLoadingDiagnostics) on a fixture with the vanilla
 * handleLogin toast shape, in both orders. Each must build on the other's output: the
 * diagnostics patch used to re-read vanilla from the jar and so brought back the "Chat messages
 * can't be verified" toast on every multiplayer join.
 */
final class ClientPacketListenerPatchChainTest {
    private static final String LISTENER = "net/minecraft/client/multiplayer/ClientPacketListener";
    private static final String CHUNK_PACKET =
            "net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket";
    private static final String TRACKER = "net/minecraft/client/multiplayer/LevelLoadTracker";
    private static final String TOAST = "net/minecraft/client/gui/components/toasts/SystemToast";
    private static final String TOAST_ID = TOAST + "$SystemToastId";
    private static final String TOAST_MANAGER = "net/minecraft/client/gui/components/toasts/ToastManager";
    private static final String COMPONENT = "Lnet/minecraft/network/chat/Component;";
    private static int checks;

    private ClientPacketListenerPatchChainTest() {
    }

    static int run(Path scratch) throws IOException {
        checks = 0;
        Path root = scratch.resolve("client-packet-listener-chain");
        Files.createDirectories(root);
        Path jar = root.resolve("client.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(zip, LISTENER, listener());
            put(zip, CHUNK_PACKET, empty(CHUNK_PACKET));
        }
        for (boolean toastFirst : new boolean[] {true, false}) {
            String order = toastFirst ? "toast then diagnostics" : "diagnostics then toast";
            Path output = root.resolve(toastFirst ? "toast-first" : "diagnostics-first");
            Path listener = output.resolve(LISTENER + ".class");
            if (toastFirst) {
                NetworkPatches.patchSuppressInsecureServerToast(jar.toString(), output);
                TelemetryPatches.patchClientPacketListenerLoadingDiagnostics(jar.toString(), listener);
            } else {
                TelemetryPatches.patchClientPacketListenerLoadingDiagnostics(jar.toString(), listener);
                NetworkPatches.patchSuppressInsecureServerToast(jar.toString(), output);
            }
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(listener)).accept(node, 0);
            MethodNode login = method(node, "handleLogin");
            check(!references(login, TOAST_ID, "UNSECURE_SERVER_WARNING")
                    && !calls(login, TOAST_MANAGER, "addToast"),
                    order + ": the insecure-chat toast stays removed");
            check(references(login, LISTENER, "seenInsecureChatWarning"),
                    order + ": the seen-warning state is still recorded");
            check(literal(login, "client.handleLogin") && literal(method(node, "tick"), "client.tickClientLoad"),
                    order + ": the loading diagnostics are kept");
        }
        return checks;
    }

    /** handleLogin with 26.3's toast shape, and the members the loading diagnostics hook. */
    private static byte[] listener() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = LISTENER;
        node.superName = "java/lang/Object";

        MethodNode login = new MethodNode(Opcodes.ACC_PUBLIC, "handleLogin",
                "(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V", null, null);
        InsnList code = login.instructions;
        code.add(new TypeInsnNode(Opcodes.NEW, TOAST));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, TOAST_ID, "UNSECURE_SERVER_WARNING", "L" + TOAST_ID + ";"));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TOAST, "<init>",
                "(L" + TOAST_ID + ";" + COMPONENT + COMPONENT + ")V", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, LISTENER, "toastManager", "L" + TOAST_MANAGER + ";"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TOAST_MANAGER, "addToast",
                "(Lnet/minecraft/client/gui/components/toasts/Toast;)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, LISTENER, "seenInsecureChatWarning", "Z"));
        code.add(new InsnNode(Opcodes.RETURN));
        login.maxStack = 5;
        login.maxLocals = 3;
        node.methods.add(login);

        node.methods.add(empty("startWaitingForNewLevel", "(Lnet/minecraft/client/player/LocalPlayer;"
                + "Lnet/minecraft/client/multiplayer/ClientLevel;"
                + "Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;)V", 4));
        node.methods.add(empty("handleLevelChunkWithLight", "(L" + CHUNK_PACKET + ";)V", 2));
        node.methods.add(empty("handleChunkBatchStart",
                "(Lnet/minecraft/network/protocol/game/ClientboundChunkBatchStartPacket;)V", 2));
        node.methods.add(empty("handleChunkBatchFinished",
                "(Lnet/minecraft/network/protocol/game/ClientboundChunkBatchFinishedPacket;)V", 2));
        node.methods.add(empty("notifyPlayerLoaded", "()V", 1));

        MethodNode tick = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        LabelNode notReady = new LabelNode();
        code = tick.instructions;
        code.add(tracker());
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TRACKER, "tickClientLoad", "()V", false));
        code.add(tracker());
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TRACKER, "loadingPacketsReceived", "()V", false));
        code.add(tracker());
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TRACKER, "isLevelReady", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, notReady));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LISTENER, "notifyPlayerLoaded", "()V", false));
        code.add(notReady);
        code.add(new InsnNode(Opcodes.RETURN));
        tick.maxStack = 1;
        tick.maxLocals = 1;
        node.methods.add(tick);
        return bytes(node);
    }

    private static InsnList tracker() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, LISTENER, "levelLoadTracker", "L" + TRACKER + ";"));
        return code;
    }

    private static MethodNode empty(String name, String descriptor, int locals) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxLocals = locals;
        return method;
    }

    private static byte[] empty(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        return bytes(node);
    }

    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name + ".class"));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static boolean references(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.owner.equals(owner) && field.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean literal(MethodNode method, String value) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldc && value.equals(ldc.cst)) {
                return true;
            }
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
