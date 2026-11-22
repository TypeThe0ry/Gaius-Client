package dev.gaius.tools;

import dev.gaius.tools.patches.network.NetworkPatches;
import dev.gaius.tools.patches.ui.UiPatches;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** JVM regression fixture for ServerData transport persistence and old-entry migration. */
public final class ServerDataTransportPatchTest {
    private static final String SERVER_DATA = "net/minecraft/client/multiplayer/ServerData";
    private static final String NBT = "net/minecraft/nbt/CompoundTag";
    private static final String KIND = "dev/gaius/browser/transport/TransportKind";

    private ServerDataTransportPatchTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("expected output root and three profile jars");
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        for (int index = 0; index < 3; index++) {
            String profile = "named-jar-" + index;
            Path jar = Path.of(args[index + 1]);
            if (!Files.isRegularFile(jar)) throw new IOException("missing profile jar: " + jar);
            Path overlay = root.resolve("port/src/main/java").resolve(profile).resolve("overlay");
            Path output = overlay.resolve(SERVER_DATA + ".class");
            NetworkPatches.patchServerDataTransportProtocol(jar.toString(), output);
            ClassNode patched = new ClassNode();
            new ClassReader(Files.readAllBytes(output)).accept(patched, 0);
            check(patched.fields.stream().anyMatch(field -> field.name.equals("gaiusProtocol")
                    && field.desc.equals("Ljava/lang/String;")), profile + " field added");
            MethodNode constructor = method(patched, "<init>");
            check(callsField(constructor, Opcodes.PUTFIELD, "gaiusProtocol"),
                    profile + " defaults new entries to relay");
            MethodNode write = method(patched, "write");
            check(calls(write, NBT, "putString", "(Ljava/lang/String;Ljava/lang/String;)V")
                    && calls(write, KIND, "fromProtocol", "(Ljava/lang/String;)L" + KIND + ";"),
                    profile + " writes a normalized protocol value");
            MethodNode read = method(patched, "read");
            check(calls(read, NBT, "getStringOr",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                    && callsField(read, Opcodes.PUTFIELD, "gaiusProtocol")
                    && calls(read, KIND, "fromProtocol", "(Ljava/lang/String;)L" + KIND + ";"),
                    profile + " missing or unknown protocol migrates through relay default");
            check(callsField(method(patched, "copyFrom"), Opcodes.PUTFIELD, "gaiusProtocol"),
                    profile + " copies transport on server entry replacement");

            Path connectionOutput = overlay.resolve("net/minecraft/network/Connection.class");
            Path connectThreadOutput = overlay.resolve("net/minecraft/client/gui/screens/ConnectScreen$1.class");
            NetworkPatches.patchConnectionTransportMetadata(jar.toString(), connectionOutput,
                    connectThreadOutput);
            ClassNode connection = new ClassNode();
            new ClassReader(Files.readAllBytes(connectionOutput)).accept(connection, 0);
            check(connection.fields.stream().anyMatch(field -> field.name.equals("gaiusTransport")
                    && field.desc.equals("Ljava/lang/String;")), profile + " stores active transport");
            check("getGaiusTransport".equals(method(connection, "getGaiusTransport").name),
                    profile + " exposes read-only active transport");
            ClassNode connectThread = new ClassNode();
            new ClassReader(Files.readAllBytes(connectThreadOutput)).accept(connectThread, 0);
            check(calls(method(connectThread, "run"), "net/minecraft/network/Connection",
                    "bindGaiusTransport"), profile + " binds transport before handshake");

            Path manageScreenOutput = overlay.resolve("net/minecraft/client/gui/screens/ManageServerScreen.class");
            UiPatches.patchEaglerServerProtocolUi(jar.toString(), manageScreenOutput);
            ClassNode manageScreen = new ClassNode();
            new ClassReader(Files.readAllBytes(manageScreenOutput)).accept(manageScreen, 0);
            new Analyzer<>(new BasicVerifier()).analyze(manageScreen.name, method(manageScreen, "init"));
            check(calls(method(manageScreen, "init"), "dev/gaius/browser/GaiusServerProtocolUi",
                    "createSelector"), profile + " creates the Add/Edit Server protocol selector");
            check(hasProtocolSelectorReceiverSequence(method(manageScreen, "init")),
                    profile + " loads receivers in order for protocol selector creation");
            check(calls(method(manageScreen, "gaiusAddProtocolWidget"),
                    "net/minecraft/client/gui/screens/Screen", "addRenderableWidget"),
                    profile + " adds the selector through Screen's protected widget API");

            Path connectScreenOutput = overlay.resolve("net/minecraft/client/gui/screens/ConnectScreen.class");
            // Apply the preceding recovery patch exactly as MinecraftClientPatcher does.
            NetworkPatches.patchConnectScreenBrowserRecovery(jar.toString(), overlay);
            UiPatches.patchEaglerConnectionStatusUi(jar.toString(), connectScreenOutput);
            ClassNode connectScreen = new ClassNode();
            new ClassReader(Files.readAllBytes(connectScreenOutput)).accept(connectScreen, 0);
            MethodNode startConnecting = method(connectScreen, "startConnecting");
            new Analyzer<>(new BasicVerifier()).analyze(connectScreen.name, startConnecting);
            check(calls(startConnecting, "dev/gaius/browser/BrowserMultiplayerRecovery", "beginConnection",
                    "(Lnet/minecraft/client/multiplayer/ServerData;)V"),
                    profile + " preserves the preceding recovery hook");
            new Analyzer<>(new BasicVerifier()).analyze(connectScreen.name, method(connectScreen, "updateStatus"));
            check(calls(method(connectScreen, "updateStatus"),
                    "dev/gaius/browser/GaiusConnectionStatusUi", "decorate"),
                    profile + " decorates the connection status with transport");

        }
        System.out.println("SERVER_DATA_TRANSPORT_PATCH_TEST PASS profiles=3");
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(candidate -> candidate.name.equals(name))
                .findFirst().orElseThrow();
    }

    private static boolean calls(MethodNode method, String owner, String name, String descriptor) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(descriptor)) return true;
        }
        return false;
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean callsField(MethodNode method, int opcode, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && field.owner.equals(SERVER_DATA) && field.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean hasProtocolSelectorReceiverSequence(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.name.equals("createSelector")) continue;
            AbstractInsnNode cursor = previousReal(instruction);
            if (!(cursor instanceof FieldInsnNode height) || !height.name.equals("height")) return false;
            cursor = previousReal(cursor);
            if (!isThisLoad(cursor)) return false;
            cursor = previousReal(cursor);
            if (!(cursor instanceof FieldInsnNode width) || !width.name.equals("width")) return false;
            cursor = previousReal(cursor);
            if (!isThisLoad(cursor)) return false;
            cursor = previousReal(cursor);
            if (!(cursor instanceof FieldInsnNode data) || !data.name.equals("serverData")) return false;
            return isThisLoad(previousReal(cursor));
        }
        return false;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static boolean isThisLoad(AbstractInsnNode instruction) {
        return instruction instanceof org.objectweb.asm.tree.VarInsnNode variable
                && variable.getOpcode() == Opcodes.ALOAD && variable.var == 0;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
