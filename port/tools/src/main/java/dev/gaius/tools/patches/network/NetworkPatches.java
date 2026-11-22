package dev.gaius.tools.patches.network;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findNullable;
import static dev.gaius.tools.patches.support.ClientPatchSupport.minecraftEvent;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.putField;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.readEntry;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import dev.gaius.tools.ModernSymbols;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The network domain of the MinecraftClientPatcher step ({@code patches/network}):
 * the client connection over the browser WebSocket bridge, address resolution and redirects,
 * the packet codecs, compression decoder and processing slices, keep-alive and loading
 * timeouts, LAN discovery, the HTTP utility, realms transfers, chat signing and the
 * singleplayer crypto, and the relay entries of the multiplayer screens.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically.
 */
public final class NetworkPatches {
    private NetworkPatches() {
    }

    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String JOIN_SCREEN = "net/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen";
    private static final String SERVER_SELECTION = "net/minecraft/client/gui/screens/multiplayer/ServerSelectionList";
    private static final String SERVER_SELECTION_DESC = "L" + SERVER_SELECTION + ";";
    private static final String CONNECT_SCREEN = "net/minecraft/client/gui/screens/ConnectScreen";
    private static final String RELAY_SCREEN = "dev/gaius/browser/BrowserRelaysScreen";
    private static final String SERVER_DATA = "Lnet/minecraft/client/multiplayer/ServerData;";
    private static final String SERVER_ADDRESS = "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;";
    private static final String TRANSFER_STATE = "Lnet/minecraft/client/multiplayer/TransferState;";
    private static final String MINECRAFT = "Lnet/minecraft/client/Minecraft;";
    private static final String START_CONNECT = "(L" + SCREEN + ";" + MINECRAFT + SERVER_ADDRESS
            + SERVER_DATA + "Z" + TRANSFER_STATE + ")V";
    private static final String CONNECTION = "net/minecraft/network/Connection";
    private static final String REMOTE_CONNECT_ARGS = "(Ljava/net/InetSocketAddress;"
            + "Lnet/minecraft/server/network/EventLoopGroupHolder;L" + CONNECTION + ";";
    private static final String CHANNEL_FUTURE = ")Lio/netty/channel/ChannelFuture;";
    private static final String RELAY_CONNECT = "gaius$connectWithRelayWarmup";
    private static final String ATTEMPT_ID = "gaius$relayWarmupAttempt";

    /** Adds the Relays button and scopes warmup to each successful activation of the saved list. */
    public static void patchJoinMultiplayerScreenRelays(String jar, Path output) throws IOException {
        String owner = JOIN_SCREEN;
        boolean present;
        try (ZipFile input = new ZipFile(jar)) {
            present = input.getEntry(owner + ".class") != null;
        }
        if (!present) {
            throw new IllegalStateException("JoinMultiplayerScreen class is absent");
        }
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        MethodNode init = find(node, "init", "()V");
        int returns = 0;
        for (AbstractInsnNode instruction : init.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList button = new InsnList();
            button.add(new VarInsnNode(Opcodes.ALOAD, 0));
            button.add(new VarInsnNode(Opcodes.ALOAD, 0));
            button.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRelaysScreen",
                    "joinButton",
                    "(Lnet/minecraft/client/gui/screens/Screen;)"
                            + "Lnet/minecraft/client/gui/components/Button;",
                    false));
            button.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    "addRenderableWidget",
                    "(Lnet/minecraft/client/gui/components/events/GuiEventListener;)"
                            + "Lnet/minecraft/client/gui/components/events/GuiEventListener;",
                    false));
            button.add(new InsnNode(Opcodes.POP));
            button.add(relayScreenHook("serverListInitialized", JOIN_SCREEN));
            button.add(serverListTargetHook(JOIN_SCREEN));
            init.instructions.insertBefore(instruction, button);
            returns++;
        }
        if (returns != 1) {
            throw new IllegalStateException("JoinMultiplayerScreen.init return shape changed: " + returns);
        }
        // Screen.init(II) calls protected init only once, and repositionElements on return.
        MethodNode added = new MethodNode(Opcodes.ACC_PUBLIC, "added", "()V", null, null);
        if (findNullable(node, "added", "()V") != null) {
            throw new IllegalStateException("JoinMultiplayerScreen now overrides added");
        }
        added.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        added.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "added", "()V", false));
        added.instructions.add(relayScreenHook("serverListAdded", JOIN_SCREEN));
        added.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(added);
        MethodNode reposition = find(node, "repositionElements", "()V");
        for (AbstractInsnNode instruction : reposition.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList hook = relayScreenHook("serverListRepositioned", JOIN_SCREEN);
                hook.add(serverListTargetHook(JOIN_SCREEN));
                reposition.instructions.insertBefore(instruction, hook);
            }
        }
        find(node, "onClose", "()V").instructions.insert(relayScreenHook("serverListRemoved", JOIN_SCREEN));
        find(node, "removed", "()V").instructions.insert(relayScreenHook("serverListRemoved", JOIN_SCREEN));

        MethodNode join = find(node, "join", "(" + SERVER_DATA + ")V");
        MethodInsnNode start = onlyCall(join, CONNECT_SCREEN, "startConnecting", START_CONNECT);
        start.owner = RELAY_SCREEN;
        patchServerListTarget(jar, output.resolveSibling("ServerSelectionList.class"));
        writeComputeFrames(node, output);
        System.out.println("Added the Relays button and warmup lifecycle to JoinMultiplayerScreen");
    }

    private static void patchServerListTarget(String jar, Path output) throws IOException {
        ClassNode list = Files.exists(output) ? read(output) : read(jar, SERVER_SELECTION + ".class");
        // Both mouse and keyboard selection converge on the typed setter (including via its bridge).
        // refreshEntries may clear the selected field while replacing entries without calling the setter.
        for (MethodNode method : List.of(find(list, "setSelected", "(L" + SERVER_SELECTION + "$Entry;)V"),
                find(list, "refreshEntries", "()V"))) {
            int returns = 0;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.RETURN) {
                    method.instructions.insertBefore(instruction, serverListTargetHook(SERVER_SELECTION));
                    returns++;
                }
            }
            if (returns != 1) {
                throw new IllegalStateException("ServerSelectionList." + method.name + " return shape changed: " + returns);
            }
        }
        writeComputeFrames(list, output);
    }

    private static InsnList serverListTargetHook(String owner) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "minecraft", MINECRAFT));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (owner.equals(SERVER_SELECTION)) {
            hook.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "screen", "L" + JOIN_SCREEN + ";"));
        }
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (owner.equals(JOIN_SCREEN)) {
            hook.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "serverSelectionList", SERVER_SELECTION_DESC));
        }
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY_SCREEN, "serverListTargetChanged",
                "(" + MINECRAFT + "L" + JOIN_SCREEN + ";" + SERVER_SELECTION_DESC + ")V", false));
        return hook;
    }

    private static InsnList relayScreenHook(String name, String screenType) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY_SCREEN, name, "(L" + screenType + ";)V", false));
        return hook;
    }

    private static MethodInsnNode onlyCall(MethodNode method, String owner, String name, String desc) {
        MethodInsnNode match = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(desc)) {
                if (match != null) {
                    throw new IllegalStateException("Duplicate " + owner + "." + name + " in " + method.name);
                }
                match = call;
            }
        }
        if (match == null) {
            throw new IllegalStateException("Missing " + owner + "." + name + " in " + method.name);
        }
        return match;
    }

    public static void patchNetworkEncoderMatchers(String jar, Path root) throws IOException {
        patchAcceptOutboundMessage(jar,
                root.resolve("net/minecraft/network/PacketEncoder.class"),
                "net/minecraft/network/PacketEncoder",
                "net/minecraft/network/protocol/Packet");
        patchAcceptOutboundMessage(jar,
                root.resolve("net/minecraft/network/PacketBundleUnpacker.class"),
                "net/minecraft/network/PacketBundleUnpacker",
                "net/minecraft/network/protocol/Packet");
        patchAcceptOutboundMessage(jar,
                root.resolve("net/minecraft/network/Varint21LengthFieldPrepender.class"),
                "net/minecraft/network/Varint21LengthFieldPrepender",
                "io/netty/buffer/ByteBuf");
        patchAcceptOutboundMessage(jar,
                root.resolve("net/minecraft/network/CipherEncoder.class"),
                "net/minecraft/network/CipherEncoder",
                "io/netty/buffer/ByteBuf");
        patchAcceptOutboundMessage(jar,
                root.resolve("net/minecraft/network/CompressionEncoder.class"),
                "net/minecraft/network/CompressionEncoder",
                "io/netty/buffer/ByteBuf");
    }

    private static void patchAcceptOutboundMessage(
            String jar, Path output, String owner, String acceptedInternalName) throws IOException {
        ClassNode node = read(jar, owner + ".class");
        node.methods.removeIf(method -> method.name.equals("acceptOutboundMessage")
                && method.desc.equals("(Ljava/lang/Object;)Z"));
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "acceptOutboundMessage",
                "(Ljava/lang/Object;)Z",
                null,
                null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, acceptedInternalName));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 2;
        node.methods.add(method);
        write(node, output);
    }

    public static void patchSingleplayerCrypto(String jar, Path root) throws IOException {
        ClassNode crypt = read(jar, "net/minecraft/util/Crypt.class");
        MethodNode generate = find(crypt, "generateKeyPair", "()Ljava/security/KeyPair;");
        InsnList unavailable = new InsnList();
        unavailable.add(new InsnNode(Opcodes.ACONST_NULL));
        unavailable.add(new InsnNode(Opcodes.ARETURN));
        replace(generate, unavailable, 1, 0);
        MethodNode generateSecretKey = find(
                crypt, "generateSecretKey", "()Ljavax/crypto/SecretKey;");
        InsnList browserSecretKey = new InsnList();
        browserSecretKey.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "generateSecretKey",
                "()Ljavax/crypto/SecretKey;",
                false));
        browserSecretKey.add(new InsnNode(Opcodes.ARETURN));
        replace(generateSecretKey, browserSecretKey, 1, 0);

        MethodNode digestData = find(
                crypt,
                "digestData",
                "(Ljava/lang/String;Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B");
        InsnList browserDigest = new InsnList();
        browserDigest.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserDigest.add(new VarInsnNode(Opcodes.ALOAD, 1));
        browserDigest.add(new VarInsnNode(Opcodes.ALOAD, 2));
        browserDigest.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "digestData",
                "(Ljava/lang/String;Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B",
                false));
        browserDigest.add(new InsnNode(Opcodes.ARETURN));
        replace(digestData, browserDigest, 3, 3);

        replaceNull(crypt, "decryptByteToSecretKey",
                "(Ljava/security/PrivateKey;[B)Ljavax/crypto/SecretKey;");
        MethodNode encryptUsingKey = find(
                crypt, "encryptUsingKey", "(Ljava/security/Key;[B)[B");
        InsnList browserEncrypt = new InsnList();
        browserEncrypt.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserEncrypt.add(new VarInsnNode(Opcodes.ALOAD, 1));
        browserEncrypt.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "encryptUsingKey",
                "(Ljava/security/Key;[B)[B",
                false));
        browserEncrypt.add(new InsnNode(Opcodes.ARETURN));
        replace(encryptUsingKey, browserEncrypt, 2, 2);
        replaceSecondByteArray(crypt, "decryptUsingKey", "(Ljava/security/Key;[B)[B");

        MethodNode privateKeyParser = find(
                crypt, "byteToPrivateKey", "([B)Ljava/security/PrivateKey;");
        InsnList browserPrivateKey = new InsnList();
        browserPrivateKey.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserPrivateKey.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "parseRsaPrivateKey",
                "([B)Ljava/security/PrivateKey;",
                false));
        browserPrivateKey.add(new InsnNode(Opcodes.ARETURN));
        replace(privateKeyParser, browserPrivateKey, 1, 1);

        MethodNode publicKeyParser = find(
                crypt, "byteToPublicKey", "([B)Ljava/security/PublicKey;");
        InsnList browserPublicKey = new InsnList();
        browserPublicKey.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserPublicKey.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "parseRsaPublicKey",
                "([B)Ljava/security/PublicKey;",
                false));
        browserPublicKey.add(new InsnNode(Opcodes.ARETURN));
        replace(publicKeyParser, browserPublicKey, 1, 1);
        write(crypt, root.resolve("net/minecraft/util/Crypt.class"));

        ClassNode data = read(
                jar, "net/minecraft/world/entity/player/ProfilePublicKey$Data.class");
        MethodNode equals = find(data, "equals", "(Ljava/lang/Object;)Z");
        InsnList identity = new InsnList();
        identity.add(new VarInsnNode(Opcodes.ALOAD, 0));
        identity.add(new VarInsnNode(Opcodes.ALOAD, 1));
        org.objectweb.asm.tree.LabelNode different = new org.objectweb.asm.tree.LabelNode();
        identity.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IF_ACMPNE, different));
        identity.add(new InsnNode(Opcodes.ICONST_1));
        identity.add(new InsnNode(Opcodes.IRETURN));
        identity.add(different);
        identity.add(new InsnNode(Opcodes.ICONST_0));
        identity.add(new InsnNode(Opcodes.IRETURN));
        replace(equals, identity, 2, 2);
        write(data, root.resolve(
                "net/minecraft/world/entity/player/ProfilePublicKey$Data.class"));
    }

    public static void patchChatSigning(String jar, Path root) throws IOException {
        // authlib 10 (Minecraft 26.3) moved ServicesKeySet/ServicesKeyType from
        // com/mojang/authlib/yggdrasil to com/mojang/authlib/services; the owner package comes
        // from the jar's probed authlib flavour (unchanged on 26.2 and 1.21.11).
        ModernSymbols symbols = ModernSymbols.cached(jar);
        ClassNode validator = read(jar, "net/minecraft/util/SignatureValidator.class");
        replaceStaticFieldReturn(
                validator,
                "from",
                "(Ljava/security/PublicKey;Ljava/lang/String;)Lnet/minecraft/util/SignatureValidator;",
                "net/minecraft/util/SignatureValidator",
                "NO_VALIDATION",
                "Lnet/minecraft/util/SignatureValidator;");
        replaceStaticFieldReturn(
                validator,
                "from",
                symbols.authlibDesc("(Lcom/mojang/authlib/yggdrasil/ServicesKeySet;"
                        + "Lcom/mojang/authlib/yggdrasil/ServicesKeyType;)"
                        + "Lnet/minecraft/util/SignatureValidator;"),
                "net/minecraft/util/SignatureValidator",
                "NO_VALIDATION",
                "Lnet/minecraft/util/SignatureValidator;");
        write(validator, root.resolve("net/minecraft/util/SignatureValidator.class"));

        ClassNode signer = read(jar, "net/minecraft/util/Signer.class");
        MethodNode from = find(
                signer,
                "from",
                "(Ljava/security/PrivateKey;Ljava/lang/String;)Lnet/minecraft/util/Signer;");
        InsnList browserSigner = new InsnList();
        browserSigner.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserSigner.add(new VarInsnNode(Opcodes.ALOAD, 1));
        browserSigner.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserSigner",
                "create",
                "(Ljava/security/PrivateKey;Ljava/lang/String;)Lnet/minecraft/util/Signer;",
                false));
        browserSigner.add(new InsnNode(Opcodes.ARETURN));
        replace(from, browserSigner, 2, 2);
        write(signer, root.resolve("net/minecraft/util/Signer.class"));
    }

    /**
     * Suppress only vanilla's insecure-server chat warning toast. The browser's
     * offline singleplayer session has no signed-server identity, so this notice
     * is expected on every login. Keep chat validation and signing paths intact.
     */
    public static void patchSuppressInsecureServerToast(String jar, Path root) throws IOException {
        // patchClientPacketListenerLoadingDiagnostics also rewrites this class. Each of the two
        // consumes the other's output, so neither can restore vanilla's toast or drop the probes.
        Path output = root.resolve("net/minecraft/client/multiplayer/ClientPacketListener.class");
        ClassNode listener = Files.exists(output)
                ? read(output)
                : read(jar, "net/minecraft/client/multiplayer/ClientPacketListener.class");
        int patched = 0;
        for (MethodNode method : listener.methods) {
            AbstractInsnNode insecureId = null;
            AbstractInsnNode toastAllocation = null;
            AbstractInsnNode toastSubmission = null;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals(
                                "net/minecraft/client/gui/components/toasts/SystemToast$SystemToastId")
                        && field.name.equals("UNSECURE_SERVER_WARNING")) {
                    insecureId = instruction;
                }
                if (instruction instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW
                        && type.desc.equals("net/minecraft/client/gui/components/toasts/SystemToast")) {
                    toastAllocation = instruction;
                }
                if (insecureId != null && instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("net/minecraft/client/gui/components/toasts/SystemToast")
                        && call.name.equals("multiline")
                        && call.desc.endsWith(")Lnet/minecraft/client/gui/components/toasts/SystemToast;")) {
                    // The factory consumes this.minecraft before the toast id. Remove
                    // the complete receiver expression, or the branch leaves `this`
                    // on the stack and no longer merges with the no-warning path.
                    AbstractInsnNode minecraft = previousOpcode(insecureId);
                    AbstractInsnNode receiver = minecraft == null ? null : previousOpcode(minecraft);
                    if (!call.desc.equals("(Lnet/minecraft/client/Minecraft;"
                            + "Lnet/minecraft/client/gui/components/toasts/SystemToast$SystemToastId;"
                            + "Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;)"
                            + "Lnet/minecraft/client/gui/components/toasts/SystemToast;")
                            || !(minecraft instanceof FieldInsnNode field)
                            || field.getOpcode() != Opcodes.GETFIELD
                            || !field.owner.equals(listener.name) || !field.name.equals("minecraft")
                            || !field.desc.equals("Lnet/minecraft/client/Minecraft;")
                            || !(receiver instanceof VarInsnNode load)
                            || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
                        throw new IOException("insecure-server toast factory receiver shape changed");
                    }
                    toastAllocation = receiver;
                }
                if (toastAllocation != null && instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals("net/minecraft/client/gui/components/toasts/ToastManager")
                        && call.name.equals("addToast")
                        && call.desc.equals("(Lnet/minecraft/client/gui/components/toasts/Toast;)V")) {
                    toastSubmission = instruction;
                    break;
                }
            }
            if (toastAllocation == null || toastSubmission == null) {
                continue;
            }
            AbstractInsnNode cursor = toastAllocation;
            while (cursor != null) {
                AbstractInsnNode next = cursor.getNext();
                method.instructions.remove(cursor);
                if (cursor == toastSubmission) {
                    break;
                }
                cursor = next;
            }
            patched++;
        }
        if (patched > 1) {
            throw new IOException("expected at most one insecure-server toast submission, found " + patched);
        }
        if (patched == 1) {
            write(listener, output);
        }
    }

    public static void patchMultiplayerExecutor(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/screens/multiplayer/"
                        + "ServerSelectionList$OnlineServerEntry.class");
        MethodNode render = null;
        for (MethodNode candidate : node.methods) {
            boolean hasThreadPool = false;
            boolean hasSubmit = false;
            for (AbstractInsnNode instruction : candidate.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals(
                                "net/minecraft/client/gui/screens/multiplayer/ServerSelectionList")
                        && field.name.equals("THREAD_POOL")) {
                    hasThreadPool = true;
                } else if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("java/util/concurrent/ThreadPoolExecutor")
                        && call.name.equals("submit")
                        && call.desc.equals(
                                "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;")) {
                    hasSubmit = true;
                }
            }
            if (!hasThreadPool || !hasSubmit) {
                continue;
            }
            if (render != null) {
                throw new IllegalStateException(
                        "Multiple multiplayer server ping executor methods were found");
            }
            render = candidate;
        }
        if (render == null) {
            throw new IllegalStateException("Multiplayer server ping executor method was not found");
        }
        boolean fieldRemoved = false;
        boolean submitReplaced = false;
        for (var instruction = render.instructions.getFirst();
                instruction != null;) {
            var next = instruction.getNext();
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(
                            "net/minecraft/client/gui/screens/multiplayer/ServerSelectionList")
                    && field.name.equals("THREAD_POOL")) {
                render.instructions.remove(field);
                fieldRemoved = true;
            } else if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("java/util/concurrent/ThreadPoolExecutor")
                    && call.name.equals("submit")
                    && call.desc.equals(
                            "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;")) {
                MethodInsnNode run = new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/lang/Runnable",
                        "run",
                        "()V",
                        true);
                render.instructions.set(call, run);
                if (run.getNext() instanceof InsnNode pop
                        && pop.getOpcode() == Opcodes.POP) {
                    render.instructions.remove(pop);
                }
                submitReplaced = true;
            }
            instruction = next;
        }
        if (!fieldRemoved || !submitReplaced) {
            throw new IllegalStateException("Multiplayer executor call was not found");
        }
        write(node, output);
    }

    public static void patchEventLoopGroupHolder(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/network/EventLoopGroupHolder.class");
        boolean clinitFound = false;
        boolean remoteFound = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new TypeInsnNode(
                        Opcodes.NEW,
                        "net/minecraft/server/network/EventLoopGroupHolder$4"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode("Local"));
                code.add(new LdcInsnNode(Type.getObjectType("io/netty/channel/local/LocalChannel")));
                code.add(new LdcInsnNode(
                        Type.getObjectType("io/netty/channel/local/LocalServerChannel")));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "net/minecraft/server/network/EventLoopGroupHolder$4",
                        "<init>",
                        "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;)V",
                        false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 0));
                for (String field : new String[] {"NIO", "EPOLL", "KQUEUE", "LOCAL"}) {
                    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    code.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC,
                            "net/minecraft/server/network/EventLoopGroupHolder",
                            field,
                            "Lnet/minecraft/server/network/EventLoopGroupHolder;"));
                }
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 5, 1);
                clinitFound = true;
            } else if (method.name.equals("remote")
                    && method.desc.equals("(Z)Lnet/minecraft/server/network/EventLoopGroupHolder;")) {
                InsnList code = new InsnList();
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/server/network/EventLoopGroupHolder",
                        "LOCAL",
                        "Lnet/minecraft/server/network/EventLoopGroupHolder;"));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, 1);
                remoteFound = true;
            }
        }
        if (!clinitFound || !remoteFound) {
            throw new IllegalStateException("EventLoopGroupHolder patch points were not found");
        }
        write(node, output);
    }

    public static void patchBrowserServerAddressResolver(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/multiplayer/resolver/ServerAddressResolver.class");
        MethodNode method = find(node, "lambda$static$0",
                "(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)"
                        + "Ljava/util/Optional;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/resolver/ServerAddress",
                "getHost",
                "()Ljava/lang/String;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/resolver/ServerAddress",
                "getPort",
                "()I",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/net/InetSocketAddress",
                "createUnresolved",
                "(Ljava/lang/String;I)Ljava/net/InetSocketAddress;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/client/multiplayer/resolver/ResolvedServerAddress",
                "from",
                "(Ljava/net/InetSocketAddress;)"
                        + "Lnet/minecraft/client/multiplayer/resolver/ResolvedServerAddress;",
                true));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Optional",
                "of",
                "(Ljava/lang/Object;)Ljava/util/Optional;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 3, 1);
        write(node, output);
    }

    public static void patchResolvedServerAddressBrowserUnresolved(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/multiplayer/resolver/ResolvedServerAddress$1.class");
        for (String methodName : new String[] {"getHostName", "getHostIp"}) {
            MethodNode method = find(node, methodName, "()Ljava/lang/String;");
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/multiplayer/resolver/ResolvedServerAddress$1",
                    "val$address",
                    "Ljava/net/InetSocketAddress;"));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/net/InetSocketAddress",
                    "getHostString",
                    "()Ljava/lang/String;",
                    false));
            code.add(new InsnNode(Opcodes.ARETURN));
            replace(method, code, 1, 1);
        }
        write(node, output);
    }

    public static void patchBrowserServerRedirectHandler(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/multiplayer/resolver/ServerRedirectHandler.class");
        MethodNode method = find(node, "createDnsSrvRedirectHandler",
                "()Lnet/minecraft/client/multiplayer/resolver/ServerRedirectHandler;");
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/client/multiplayer/resolver/ServerRedirectHandler",
                "EMPTY",
                "Lnet/minecraft/client/multiplayer/resolver/ServerRedirectHandler;"));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 0);
        write(node, output);
    }

    public static void patchConnectionBrowserWebSocket(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/network/Connection.class");
        MethodNode connect = find(node, "connect", REMOTE_CONNECT_ARGS + CHANNEL_FUTURE);
        MethodNode relayConnect = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                RELAY_CONNECT, REMOTE_CONNECT_ARGS + "I" + CHANNEL_FUTURE, null, null);
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, "io/netty/bootstrap/Bootstrap"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "io/netty/bootstrap/Bootstrap",
                "<init>",
                "()V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/network/EventLoopGroupHolder",
                "eventLoopGroup",
                "()Lio/netty/channel/EventLoopGroup;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "group",
                "(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/network/Connection$1"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/network/Connection$1",
                "<init>",
                "(Lnet/minecraft/network/Connection;)V",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "handler",
                "(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new LdcInsnNode(Type.getObjectType(
                "io/netty/channel/browser/BrowserWebSocketChannel")));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "channel",
                "(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "disableResolver",
                "()Lio/netty/bootstrap/Bootstrap;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY_SCREEN, "connectChannel",
                "(Lio/netty/bootstrap/Bootstrap;Ljava/net/SocketAddress;I" + CHANNEL_FUTURE, false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(relayConnect, code, 4, 4);
        node.methods.add(relayConnect);

        InsnList normalConnect = new InsnList();
        normalConnect.add(new VarInsnNode(Opcodes.ALOAD, 0));
        normalConnect.add(new VarInsnNode(Opcodes.ALOAD, 1));
        normalConnect.add(new VarInsnNode(Opcodes.ALOAD, 2));
        normalConnect.add(new InsnNode(Opcodes.ICONST_0));
        normalConnect.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONNECTION, RELAY_CONNECT, relayConnect.desc, false));
        normalConnect.add(new InsnNode(Opcodes.ARETURN));
        replace(connect, normalConnect, 4, 3);

        // The integrated-server entry point does not call the remote
        // Connection.connect(InetSocketAddress, ... ) helper above.  It calls
        // connectToLocalServer(SocketAddress), whose vanilla implementation
        // explicitly selects LocalChannel and the in-memory frame codec.  In a
        // browser the server lives in a Worker and the only usable transport is
        // the MessagePort-backed BrowserWebSocketChannel.  Patch this method as
        // well; otherwise the client never reaches the page-side bridge and the
        // Worker listener correctly reports "ready" while the client receives a
        // LocalChannel connection-refused screen.
        MethodNode local = find(node, "connectToLocalServer",
                "(Ljava/net/SocketAddress;)Lnet/minecraft/network/Connection;");
        InsnList localCode = new InsnList();
        localCode.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/network/Connection"));
        localCode.add(new InsnNode(Opcodes.DUP));
        localCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/network/protocol/PacketFlow",
                "CLIENTBOUND",
                "Lnet/minecraft/network/protocol/PacketFlow;"));
        localCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/network/Connection",
                "<init>",
                "(Lnet/minecraft/network/protocol/PacketFlow;)V",
                false));
        localCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
        localCode.add(new TypeInsnNode(Opcodes.NEW, "io/netty/bootstrap/Bootstrap"));
        localCode.add(new InsnNode(Opcodes.DUP));
        localCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "io/netty/bootstrap/Bootstrap",
                "<init>",
                "()V",
                false));
        localCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/server/network/EventLoopGroupHolder",
                "local",
                "()Lnet/minecraft/server/network/EventLoopGroupHolder;",
                false));
        localCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/network/EventLoopGroupHolder",
                "eventLoopGroup",
                "()Lio/netty/channel/EventLoopGroup;",
                false));
        localCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "group",
                "(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        localCode.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        localCode.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/network/Connection$1"));
        localCode.add(new InsnNode(Opcodes.DUP));
        localCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        localCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/network/Connection$1",
                "<init>",
                "(Lnet/minecraft/network/Connection;)V",
                false));
        localCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "handler",
                "(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        localCode.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        localCode.add(new LdcInsnNode(Type.getObjectType(
                "io/netty/channel/browser/BrowserWebSocketChannel")));
        localCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "channel",
                "(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        localCode.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        localCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "disableResolver",
                "()Lio/netty/bootstrap/Bootstrap;",
                false));
        localCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        localCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "connect",
                "(Ljava/net/SocketAddress;)Lio/netty/channel/ChannelFuture;",
                false));
        localCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "io/netty/channel/ChannelFuture",
                "syncUninterruptibly",
                "()Lio/netty/channel/ChannelFuture;",
                true));
        localCode.add(new InsnNode(Opcodes.POP));
        localCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        localCode.add(new InsnNode(Opcodes.ARETURN));
        // The local method originally has one argument and one temporary.  The
        // replacement uses slot 1 for the Connection and never needs the old
        // local variables or exception table.
        replace(local, localCode, 5, 2);

        writeComputeFrames(node, output);
    }

    /** Adds the Eagler pre-Minecraft handshake to the shared connection initializer. */
    public static void patchEaglerConnectionInitializer(String jar, Path output, Path pingerOutput) throws IOException {
        String owner = "net/minecraft/network/Connection$1";
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        MethodNode init = find(node, "initChannel", "(Lio/netty/channel/Channel;)V");
        for (AbstractInsnNode instruction : init.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("net/minecraft/network/Connection")
                    || !call.name.equals("configurePacketHandler")) {
                continue;
            }
            InsnList insert = new InsnList();
            insert.add(new VarInsnNode(Opcodes.ALOAD, 1));
            insert.add(new VarInsnNode(Opcodes.ALOAD, 0));
            insert.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "val$connection",
                    "Lnet/minecraft/network/Connection;"));
            insert.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/transport/ConnectionTransports", "install",
                    "(Lio/netty/channel/Channel;Lnet/minecraft/network/Connection;)V", false));
            init.instructions.insert(call, insert);
            init.maxStack = Math.max(init.maxStack, 4);
            writeComputeFrames(node, output);
            skipEaglerVanillaStatusPing(jar, pingerOutput);
            return;
        }
        throw new IOException("Connection initializer no longer configures the packet handler");
    }

    private static void skipEaglerVanillaStatusPing(String jar, Path output) throws IOException {
        String serverData = "net/minecraft/client/multiplayer/ServerData";
        ClassNode node = read(jar, "net/minecraft/client/multiplayer/ServerStatusPinger.class");
        MethodNode ping = find(node, "pingServer", "(L" + serverData
                + ";Ljava/lang/Runnable;Ljava/lang/Runnable;Lnet/minecraft/server/network/EventLoopGroupHolder;)V");
        LabelNode vanilla = new LabelNode();
        InsnList code = new InsnList();
        code.add(new LdcInsnNode("eagler"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, serverData, "gaiusProtocol", "Ljava/lang/String;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, serverData + "$State", "UNREACHABLE",
                "L" + serverData + "$State;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, serverData, "setState",
                "(L" + serverData + "$State;)V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(vanilla);
        ping.instructions.insert(code);
        writeComputeFrames(node, output);
    }

    /** Logs the complete outbound/inbound Netty failure chain in opt-in browser diagnostics. */
    public static void patchConnectionExceptionDiagnostics(Path connectionClass)
            throws IOException {
        ClassNode node = read(connectionClass);
        MethodNode method = find(node, "exceptionCaught",
                "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Throwable;)V");
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "reportConnectionException",
                "(Ljava/lang/Throwable;)V",
                false));
        method.instructions.insert(hook);
        writeComputeFrames(node, connectionClass);
    }

    /** Logs Errors escaping Brigadier execution before they are converted to a disconnect. */
    public static void patchCommandErrorDiagnostics(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/commands/Commands.class");
        MethodNode method = find(node, "performCommand",
                "(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V");
        if (method == null) {
            throw new IOException("Commands.performCommand diagnostic patch point was not found");
        }
        MethodInsnNode execute = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("dev/gaius/browser/BrowserIntegratedServerMain")
                    && call.name.equals("reportCommandFailure")) {
                throw new IOException("Commands.performCommand already has diagnostics");
            }
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/commands/Commands")
                    && call.name.equals("executeCommandInContext")) {
                if (execute != null || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !call.desc.equals("(Lnet/minecraft/commands/CommandSourceStack;"
                                + "Ljava/util/function/Consumer;)V")) {
                    throw new IOException("Commands.performCommand execution call shape changed");
                }
                execute = call;
            }
        }
        if (execute == null) {
            throw new IOException("Commands.performCommand execution call was not found");
        }
        int at = method.instructions.indexOf(execute);
        var enclosing = method.tryCatchBlocks.stream().filter(block ->
                method.instructions.indexOf(block.start) <= at
                        && at < method.instructions.indexOf(block.end)).toList();
        if (enclosing.size() != 2 || !"java/lang/Exception".equals(enclosing.get(0).type)
                || enclosing.get(1).type != null) {
            throw new IOException("Commands.performCommand conversion/finally shape changed");
        }
        // Catch only the execution call, before vanilla's handlers. Keep the report and
        // rethrow inline, inside those original ranges, so Exception conversion and finally
        // still run. Appending the handler outside them would bypass cleanup on rethrow.
        int failureLocal = method.maxLocals++;
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        LabelNode reportStart = new LabelNode(), reportEnd = new LabelNode();
        LabelNode reportFailure = new LabelNode(), rethrow = new LabelNode(), normal = new LabelNode();
        method.instructions.insertBefore(execute, start);
        InsnList code = new InsnList();
        code.add(end);
        code.add(new JumpInsnNode(Opcodes.GOTO, normal));
        code.add(handler);
        code.add(new VarInsnNode(Opcodes.ASTORE, failureLocal));
        code.add(reportStart);
        code.add(new VarInsnNode(Opcodes.ALOAD, failureLocal));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain", "reportCommandFailure",
                "(Ljava/lang/Throwable;)V", false));
        code.add(reportEnd);
        code.add(new JumpInsnNode(Opcodes.GOTO, rethrow));
        code.add(reportFailure);
        code.add(new InsnNode(Opcodes.POP)); // Telemetry must not replace the original failure.
        code.add(rethrow);
        code.add(new VarInsnNode(Opcodes.ALOAD, failureLocal));
        code.add(new InsnNode(Opcodes.ATHROW));
        code.add(normal);
        method.instructions.insert(execute, code);
        method.tryCatchBlocks.add(0, new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        method.tryCatchBlocks.add(1, new TryCatchBlockNode(reportStart, reportEnd,
                reportFailure, "java/lang/Throwable"));
        method.maxStack = Math.max(method.maxStack, 2);
        writeComputeFrames(node, output);
    }

    /** Logs fatal failures escaping the server's unsigned chat-command packet handler. */
    public static void patchServerCommandPacketErrorDiagnostics(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/network/ServerGamePacketListenerImpl.class");
        MethodNode method = find(node, "lambda$handleChatCommand$0",
                "(Lnet/minecraft/network/protocol/game/ServerboundChatCommandPacket;)V");
        if (method == null) {
            throw new IOException("ServerGamePacketListenerImpl command packet handler was not found");
        }
        MethodInsnNode command = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/server/network/ServerGamePacketListenerImpl")
                    && call.name.equals("performUnsignedChatCommand")) {
                command = call;
                break;
            }
        }
        if (command == null) {
            throw new IOException("Unsigned chat command invocation was not found");
        }
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.insertBefore(command, start);
        method.instructions.insert(command, end);
        InsnList report = new InsnList();
        report.add(handler);
        report.add(new VarInsnNode(Opcodes.ASTORE, 2));
        report.add(new VarInsnNode(Opcodes.ALOAD, 2));
        report.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain", "reportPacketFailure",
                "(Ljava/lang/Throwable;)V", false));
        report.add(new VarInsnNode(Opcodes.ALOAD, 2));
        report.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(report);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        writeComputeFrames(node, output);
    }

    /**
     * Uses the cooperative browser decoder for the compression handler while retaining the
     * vanilla Connection.setupCompression lifecycle and setThreshold contract.
     */
    public static void patchCompressionDecoderBrowser(Path connectionClass)
            throws IOException {
        // Continue from the Connection.class already rewritten by
        // patchConnectionBrowserWebSocket, including connectToLocalServer.
        // Reading the input JAR here would silently discard that transport patch.
        ClassNode node = read(connectionClass);
        MethodNode setup = find(node, "setupCompression", "(IZ)V");
        int replacements = 0;
        for (AbstractInsnNode instruction : setup.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("net/minecraft/network/CompressionDecoder")) {
                type.desc = "dev/gaius/browser/BrowserCompressionDecoder";
                replacements++;
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("net/minecraft/network/CompressionDecoder")
                    && call.name.equals("<init>")
                    && call.desc.equals("(IZ)V")) {
                call.owner = "dev/gaius/browser/BrowserCompressionDecoder";
                replacements++;
            }
        }
        if (replacements != 2) {
            throw new IllegalStateException(
                    "CompressionDecoder browser constructor patch point changed: " + replacements);
        }
        writeComputeFrames(node, connectionClass);
    }

    /**
     * Resource-pack reloads can keep the browser renderer frozen long enough for vanilla's
     * deferred configuration keepalive queue to miss every listener tick. Browser transport
     * callbacks already run on the page event loop, so reply immediately instead of waiting
     * for RenderSystem's desktop poll-events gate. Required server packs must also start
     * immediately: quick-connect pages do not have a reliable opportunity to answer vanilla's
     * modal prompt before servers enforce their configuration timeout.
     */
    public static void patchClientKeepAliveBrowser(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.class");
        MethodNode keepAlive = find(
                node,
                "handleKeepAlive",
                "(Lnet/minecraft/network/protocol/common/ClientboundKeepAlivePacket;)V");
        int keepAliveIdCalls = 0;
        int keepAliveConstructors = 0;
        int sendWhenCalls = 0;
        int packetThreadChecks = 0;
        int existingInlineAccountingCalls = 0;
        for (AbstractInsnNode instruction : keepAlive.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.owner.equals(
                    "net/minecraft/network/protocol/common/ClientboundKeepAlivePacket")
                    && call.name.equals("getId")
                    && call.desc.equals("()J")) {
                keepAliveIdCalls++;
            } else if (call.owner.equals(
                    "net/minecraft/network/protocol/common/ServerboundKeepAlivePacket")
                    && call.name.equals("<init>")
                    && call.desc.equals("(J)V")) {
                keepAliveConstructors++;
            } else if (call.owner.equals(node.name)
                    && call.name.equals("sendWhen")
                    && call.desc.equals("(Lnet/minecraft/network/protocol/Packet;"
                            + "Ljava/util/function/BooleanSupplier;Ljava/time/Duration;)V")) {
                sendWhenCalls++;
            } else if (call.owner.equals("net/minecraft/network/protocol/PacketUtils")
                    && call.name.equals("ensureRunningOnSameThread")) {
                packetThreadChecks++;
            } else if (call.owner.equals("io/netty/channel/browser/BrowserWebSocketChannel")
                    && call.name.equals("recordInlineDecodedPacket")
                    && call.desc.equals("()V")) {
                existingInlineAccountingCalls++;
            }
        }
        if (keepAliveIdCalls != 1
                || keepAliveConstructors != 1
                || sendWhenCalls != 1
                || packetThreadChecks != 0
                || existingInlineAccountingCalls != 0) {
            throw new IOException(
                    "ClientCommonPacketListenerImpl keepalive direct-send CFG changed: id="
                            + keepAliveIdCalls
                            + ", constructors=" + keepAliveConstructors
                            + ", sendWhen=" + sendWhenCalls
                            + ", packetThreadChecks=" + packetThreadChecks
                            + ", inlineAccounting=" + existingInlineAccountingCalls);
        }
        InsnList keepAliveAccounting = new InsnList();
        keepAliveAccounting.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "io/netty/channel/browser/BrowserWebSocketChannel",
                "recordInlineDecodedPacket",
                "()V",
                false));
        keepAlive.instructions.insert(keepAliveAccounting);

        MethodNode predicate = null;
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("lambda$handleKeepAlive$")
                    || !method.desc.equals("()Z")) {
                continue;
            }
            if (predicate != null) {
                throw new IOException(
                        "Multiple ClientCommonPacketListenerImpl keepalive predicates found");
            }
            predicate = method;
        }
        if (predicate == null) {
            throw new IOException("ClientCommonPacketListenerImpl keepalive predicate was not found");
        }
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(predicate, code, 1, 0);

        MethodNode resourcePack = find(
                node,
                "handleResourcePackPush",
                "(Lnet/minecraft/network/protocol/common/ClientboundResourcePackPushPacket;)V");
        if (resourcePack == null) {
            throw new IOException("ClientCommonPacketListenerImpl resource-pack handler was not found");
        }
        MethodInsnNode threadCheck = null;
        for (var instruction = resourcePack.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/network/protocol/PacketUtils")
                    && call.name.equals("ensureRunningOnSameThread")) {
                threadCheck = call;
                break;
            }
        }
        if (threadCheck == null) {
            throw new IOException("ClientCommonPacketListenerImpl resource-pack thread check was not found");
        }
        int urlLocal = resourcePack.maxLocals++;
        LabelNode vanillaResourcePackHandling = new LabelNode();
        InsnList requiredPack = new InsnList();
        // Recommended packs retain vanilla consent; required packs use the real loader.
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/common/ClientboundResourcePackPushPacket",
                "required",
                "()Z",
                false));
        requiredPack.add(new JumpInsnNode(Opcodes.IFEQ, vanillaResourcePackHandling));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/common/ClientboundResourcePackPushPacket",
                "url",
                "()Ljava/lang/String;",
                false));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "parseResourcePackUrl",
                "(Ljava/lang/String;)Ljava/net/URL;",
                false));
        requiredPack.add(new VarInsnNode(Opcodes.ASTORE, urlLocal));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, urlLocal));
        requiredPack.add(new JumpInsnNode(Opcodes.IFNULL, vanillaResourcePackHandling));
        LabelNode pushRequiredPack = new LabelNode();
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requiredPack.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "connection",
                "Lnet/minecraft/network/Connection;"));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, urlLocal));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserServerPackReuse",
                "handleRequiredPack",
                "(Lnet/minecraft/network/Connection;"
                        + "Lnet/minecraft/network/protocol/common/"
                        + "ClientboundResourcePackPushPacket;Ljava/net/URL;)Z",
                false));
        requiredPack.add(new JumpInsnNode(Opcodes.IFEQ, pushRequiredPack));
        requiredPack.add(new InsnNode(Opcodes.RETURN));
        requiredPack.add(pushRequiredPack);
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requiredPack.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "getDownloadedPackSource",
                "()Lnet/minecraft/client/resources/server/DownloadedPackSource;",
                false));
        requiredPack.add(new InsnNode(Opcodes.DUP));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/resources/server/DownloadedPackSource",
                "allowServerPacks",
                "()V",
                false));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/common/ClientboundResourcePackPushPacket",
                "id",
                "()Ljava/util/UUID;",
                false));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, urlLocal));
        requiredPack.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/common/ClientboundResourcePackPushPacket",
                "hash",
                "()Ljava/lang/String;",
                false));
        requiredPack.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/resources/server/DownloadedPackSource",
                "pushPack",
                "(Ljava/util/UUID;Ljava/net/URL;Ljava/lang/String;)V",
                false));
        requiredPack.add(new InsnNode(Opcodes.RETURN));
        requiredPack.add(vanillaResourcePackHandling);
        // Download/reload state belongs to the client thread, like vanilla.
        resourcePack.instructions.insert(threadCheck, requiredPack);
        resourcePack.maxStack = Math.max(resourcePack.maxStack, 4);

        MethodNode onDisconnect = find(
                node,
                "onDisconnect",
                "(Lnet/minecraft/network/DisconnectionDetails;)V");
        if (onDisconnect == null) {
            throw new IOException("ClientCommonPacketListenerImpl disconnect handler was not found");
        }
        MethodInsnNode disconnectCall = null;
        for (var instruction = onDisconnect.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/Minecraft")
                    && call.name.equals("disconnect")
                    && call.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;Z)V")) {
                disconnectCall = call;
                break;
            }
        }
        if (disconnectCall == null) {
            throw new IOException("ClientCommonPacketListenerImpl disconnect call was not found");
        }
        InsnList prepareColdPackRecovery = new InsnList();
        prepareColdPackRecovery.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepareColdPackRecovery.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "serverData",
                "Lnet/minecraft/client/multiplayer/ServerData;"));
        prepareColdPackRecovery.add(new VarInsnNode(Opcodes.ALOAD, 1));
        prepareColdPackRecovery.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/DisconnectionDetails",
                "reason",
                "()Lnet/minecraft/network/chat/Component;",
                false));
        prepareColdPackRecovery.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/network/chat/Component",
                "getString",
                "()Ljava/lang/String;",
                true));
        prepareColdPackRecovery.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserMultiplayerRecovery",
                "prepareDisconnect",
                "(Lnet/minecraft/client/multiplayer/ServerData;Ljava/lang/String;)Z",
                false));
        prepareColdPackRecovery.add(new InsnNode(Opcodes.POP));
        onDisconnect.instructions.insert(prepareColdPackRecovery);
        InsnList recoverColdPackTimeout = new InsnList();
        recoverColdPackTimeout.add(new VarInsnNode(Opcodes.ALOAD, 0));
        recoverColdPackTimeout.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        recoverColdPackTimeout.add(new VarInsnNode(Opcodes.ALOAD, 0));
        recoverColdPackTimeout.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
                "serverData",
                "Lnet/minecraft/client/multiplayer/ServerData;"));
        recoverColdPackTimeout.add(new VarInsnNode(Opcodes.ALOAD, 1));
        recoverColdPackTimeout.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/DisconnectionDetails",
                "reason",
                "()Lnet/minecraft/network/chat/Component;",
                false));
        recoverColdPackTimeout.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/network/chat/Component",
                "getString",
                "()Ljava/lang/String;",
                true));
        recoverColdPackTimeout.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserMultiplayerRecovery",
                "maybeReconnect",
                "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/ServerData;"
                        + "Ljava/lang/String;)Z",
                false));
        recoverColdPackTimeout.add(new InsnNode(Opcodes.POP));
        onDisconnect.instructions.insert(disconnectCall, recoverColdPackTimeout);
        onDisconnect.maxStack = Math.max(onDisconnect.maxStack, 3);
        writeComputeFrames(node, output);
    }

    /**
     * Lets the Add Server screen accept ws:// and wss:// addresses: ServerAddress.isValidAddress and
     * parseString first pass their argument through BrowserServerAddress, which strips the scheme.
     */
    public static void patchServerAddressWebSocketScheme(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/multiplayer/resolver/ServerAddress.class");
        String[][] entries = {
            {"isValidAddress", "(Ljava/lang/String;)Z", "normalize"},
            {"parseString", "(Ljava/lang/String;)"
                    + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;",
                    "normalizeAndRemember"},
        };
        for (String[] entry : entries) {
            MethodNode method = find(node, entry[0], entry[1]);
            if (method == null) {
                throw new IOException("ServerAddress." + entry[0] + " patch point was not found");
            }
            InsnList prologue = new InsnList();
            prologue.add(new VarInsnNode(Opcodes.ALOAD, 0));
            prologue.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserServerAddress",
                    entry[2],
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false));
            prologue.add(new VarInsnNode(Opcodes.ASTORE, 0));
            method.instructions.insert(prologue);
            method.maxStack = Math.max(method.maxStack, 1);
        }
        writeComputeFrames(node, output);
    }

    public static void patchConnectScreenBrowserRecovery(String jar, Path root) throws IOException {
        ClassNode node = read(jar, CONNECT_SCREEN + ".class");
        MethodNode startConnecting = find(node, "startConnecting", START_CONNECT);
        InsnList beginAttempt = new InsnList();
        beginAttempt.add(new VarInsnNode(Opcodes.ALOAD, 3));
        beginAttempt.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/gaius/browser/BrowserMultiplayerRecovery",
                "beginConnection", "(" + SERVER_DATA + ")V", false));
        startConnecting.instructions.insert(beginAttempt);

        node.fields.add(new FieldNode(Opcodes.ACC_SYNTHETIC, ATTEMPT_ID, "I", null, null));
        MethodNode connect = find(node, "connect", "(" + MINECRAFT + SERVER_ADDRESS + SERVER_DATA + TRANSFER_STATE + ")V");
        // Capture the scoped attempt before starting the connector thread. Its identity then
        // travels on this ConnectScreen and the Bootstrap's channel, including async registration.
        InsnList bind = new InsnList();
        bind.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bind.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bind.add(new FieldInsnNode(Opcodes.GETFIELD, CONNECT_SCREEN, "parent", "L" + SCREEN + ";"));
        bind.add(new VarInsnNode(Opcodes.ALOAD, 2));
        bind.add(new VarInsnNode(Opcodes.ALOAD, 3));
        bind.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY_SCREEN, "connectingAttempt",
                "(L" + SCREEN + ";" + SERVER_ADDRESS + SERVER_DATA + ")I", false));
        bind.add(new FieldInsnNode(Opcodes.PUTFIELD, CONNECT_SCREEN, ATTEMPT_ID, "I"));
        connect.instructions.insert(bind);

        MethodNode removed = findNullable(node, "removed", "()V");
        if (removed == null) {
            removed = new MethodNode(Opcodes.ACC_PUBLIC, "removed", "()V", null, null);
            removed.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            removed.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "removed", "()V", false));
            removed.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(removed);
        }
        removed.instructions.insert(releaseAttempt(false));

        ClassNode connector = read(jar, CONNECT_SCREEN + "$1.class");
        MethodNode run = find(connector, "run", "()V");
        MethodInsnNode open = onlyCall(run, CONNECTION, "connect", REMOTE_CONNECT_ARGS + CHANNEL_FUTURE);
        run.instructions.insertBefore(open, loadAttempt(true));
        open.name = RELAY_CONNECT;
        open.desc = REMOTE_CONNECT_ARGS + "I" + CHANNEL_FUTURE;
        // Cover failed DNS, aborted connectors and exceptions leaving vanilla's own handlers.
        // A consumed reservation has a channel owner and this release then does nothing.
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode failure = new LabelNode();
        run.instructions.insert(start);
        for (AbstractInsnNode instruction : run.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                run.instructions.insertBefore(instruction, releaseAttempt(true));
            }
        }
        run.instructions.add(end);
        run.instructions.add(failure);
        run.instructions.add(releaseAttempt(true));
        run.instructions.add(new InsnNode(Opcodes.ATHROW));
        run.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failure, null));

        writeComputeFrames(node, root.resolve(node.name + ".class"));
        writeComputeFrames(connector, root.resolve(connector.name + ".class"));
    }

    private static InsnList loadAttempt(boolean connector) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (connector) {
            code.add(new FieldInsnNode(Opcodes.GETFIELD, CONNECT_SCREEN + "$1", "this$0", "L" + CONNECT_SCREEN + ";"));
        }
        code.add(new FieldInsnNode(Opcodes.GETFIELD, CONNECT_SCREEN, ATTEMPT_ID, "I"));
        return code;
    }

    private static InsnList releaseAttempt(boolean connector) {
        InsnList code = loadAttempt(connector);
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY_SCREEN, "releaseRelayWarmup", "(I)V", false));
        return code;
    }

    private static boolean allExactlyOne(int[] values) {
        for (int value : values) {
            if (value != 1) {
                return false;
            }
        }
        return true;
    }

    private static String counts(int[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(values[index]);
        }
        return result.append(']').toString();
    }

    /**
     * Browser WebSocket callbacks already share the client page's event loop. Treating
     * configuration packets as foreign Java threads makes them requeue forever while a resource
     * reload owns the normal client tick, but treating PLAY packets as same-thread lets chunk and
     * entity handlers monopolize the decoder turn. Inline configuration transitions and the
     * small common-control handlers whose ordering/liveness contract is independent of terrain.
     * Force ordinary ClientPacketListener traffic through PacketProcessor even when its Java
     * thread identity matches, so the bounded client-tick packet slice is the authoritative PLAY
     * work queue. The drain guard is mandatory: a queued ListenerAndPacket re-enters this method
     * and must return from this thread check so the enclosing packet handler continues without
     * consulting a Java thread identity that has no useful meaning in TeaVM.
     */
    public static void patchClientPacketUtilsBrowserInline(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/network/protocol/PacketUtils.class");
        MethodNode method = find(node, "ensureRunningOnSameThread",
                "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
                        + "Lnet/minecraft/network/PacketProcessor;)V");
        if (method == null) {
            throw new IOException("PacketUtils client packet scheduler patch point was not found");
        }
        LabelNode inline = new LabelNode();
        LabelNode queuedHandleReturn = new LabelNode();
        LabelNode vanillaScheduling = new LabelNode();
        LabelNode clientDispatch = new LabelNode();
        LabelNode workerServerQueueGuard = new LabelNode();
        LabelNode playListener = new LabelNode();
        LabelNode commonBacklogCheck = new LabelNode();
        String[] transitionPacketTypes = {
            "net/minecraft/network/protocol/game/ClientboundStartConfigurationPacket",
            "net/minecraft/network/protocol/game/ClientboundLoginPacket"
        };
        String[] commonInlinePacketTypes = {
            "net/minecraft/network/protocol/common/ClientboundPingPacket",
            "net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket",
            "net/minecraft/network/protocol/common/ClientboundResourcePackPushPacket",
            "net/minecraft/network/protocol/common/ClientboundResourcePackPopPacket",
            "net/minecraft/network/protocol/cookie/ClientboundCookieRequestPacket",
            "net/minecraft/network/protocol/common/ClientboundStoreCookiePacket",
            "net/minecraft/network/protocol/common/ClientboundCustomReportDetailsPacket",
            "net/minecraft/network/protocol/common/ClientboundServerLinksPacket",
            "net/minecraft/network/protocol/common/ClientboundShowDialogPacket",
            "net/minecraft/network/protocol/common/ClientboundClearDialogPacket",
            "net/minecraft/network/protocol/common/ClientboundTransferPacket"
        };
        LabelNode transitionBacklogCheck = new LabelNode();
        LabelNode forcedPlayQueue = new LabelNode();
        MethodInsnNode forcedPlaySchedule = new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/PacketProcessor",
                "scheduleIfPossible",
                "(Lnet/minecraft/network/PacketListener;"
                        + "Lnet/minecraft/network/protocol/Packet;)V",
                false);
        FieldInsnNode forcedPlayAbort = new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/server/RunningOnDifferentThreadException",
                "RUNNING_ON_DIFFERENT_THREAD",
                "Lnet/minecraft/server/RunningOnDifferentThreadException;");
        InsnList code = new InsnList();
        /*
         * A Worker decoder and the integrated server continuation share one TeaVM Java-thread
         * identity. Vanilla's isSameThread fast path would therefore run every serverbound
         * handler inside the raw WebSocket pump. Force those packets onto the server's exact
         * PacketProcessor FIFO first. The owner-specific guard is deliberately before the
         * listener classifiers: ListenerAndPacket.handle re-enters PacketUtils and must return
         * for this same processor instead of appending a duplicate packet.
         */
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "isWorkerServer",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, clientDispatch));
        code.add(workerServerQueueGuard);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "isProcessingQueuedPacket",
                "(Ljava/lang/Object;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFNE, queuedHandleReturn));
        code.add(new JumpInsnNode(Opcodes.GOTO, forcedPlayQueue));
        code.add(clientDispatch);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF,
                "net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl"));
        code.add(new JumpInsnNode(Opcodes.IFNE, inline));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF,
                "net/minecraft/client/multiplayer/ClientPacketListener"));
        code.add(new JumpInsnNode(Opcodes.IFNE, playListener));
        for (String packetType : commonInlinePacketTypes) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new TypeInsnNode(Opcodes.INSTANCEOF, packetType));
            code.add(new JumpInsnNode(Opcodes.IFNE, inline));
        }
        code.add(new JumpInsnNode(Opcodes.GOTO, vanillaScheduling));
        code.add(playListener);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "isProcessingQueuedPacket",
                "(Ljava/lang/Object;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFNE, queuedHandleReturn));
        for (String packetType : transitionPacketTypes) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new TypeInsnNode(Opcodes.INSTANCEOF, packetType));
            code.add(new JumpInsnNode(Opcodes.IFNE, transitionBacklogCheck));
        }
        for (String packetType : commonInlinePacketTypes) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new TypeInsnNode(Opcodes.INSTANCEOF, packetType));
            code.add(new JumpInsnNode(Opcodes.IFNE, commonBacklogCheck));
        }
        code.add(new JumpInsnNode(Opcodes.GOTO, forcedPlayQueue));
        code.add(commonBacklogCheck);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "hasPendingPackets",
                "(Ljava/lang/Object;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, inline));
        code.add(new JumpInsnNode(Opcodes.GOTO, forcedPlayQueue));
        /*
         * The PLAY guard above must run before common packets can take the
         * inline path.  This preserves FIFO when a control packet arrives
         * while a decoded PLAY packet is already queued, while keeping the
         * empty-queue fast path and the configuration listener bypass.
         */
        code.add(transitionBacklogCheck);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "hasPendingPackets",
                "(Ljava/lang/Object;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, inline));
        code.add(forcedPlayQueue);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(forcedPlaySchedule);
        code.add(forcedPlayAbort);
        code.add(new InsnNode(Opcodes.ATHROW));
        code.add(queuedHandleReturn);
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(inline);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "io/netty/channel/browser/BrowserWebSocketChannel",
                "recordInlineDecodedPacket",
                "()V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(vanillaScheduling);
        method.instructions.insert(code);
        int inlineHooks = 0;
        int configurationBranches = 0;
        int[] transitionPacketBranches = new int[transitionPacketTypes.length];
        int[] commonInlinePacketBranches = new int[commonInlinePacketTypes.length];
        int[] commonPlayPacketBranches = new int[commonInlinePacketTypes.length];
        int clientPlayBranches = 0;
        int queuedDrainGuardCalls = 0;
        int workerServerChecks = 0;
        int workerServerDrainGuardCalls = 0;
        int transitionBacklogChecks = 0;
        int commonBacklogChecks = 0;
        MethodInsnNode inlineHook = null;
        LabelNode currentBlock = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof LabelNode label) {
                currentBlock = label;
                continue;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("io/netty/channel/browser/BrowserWebSocketChannel")
                    && call.name.equals("recordInlineDecodedPacket")
                    && call.desc.equals("()V")) {
                inlineHooks++;
                inlineHook = call;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("dev/gaius/browser/BrowserIntegratedServerMain")
                    && call.name.equals("isWorkerServer")
                    && call.desc.equals("()Z")
                    && nextOpcode(call) instanceof JumpInsnNode branch
                    && branch.getOpcode() == Opcodes.IFEQ
                    && branch.label == clientDispatch) {
                workerServerChecks++;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("dev/gaius/browser/BrowserPacketScheduler")
                    && call.name.equals("isProcessingQueuedPacket")
                    && call.desc.equals("(Ljava/lang/Object;)Z")
                    && nextOpcode(call) instanceof JumpInsnNode branch
                    && branch.getOpcode() == Opcodes.IFNE
                    && branch.label == queuedHandleReturn) {
                if (currentBlock == workerServerQueueGuard) {
                    workerServerDrainGuardCalls++;
                } else {
                    queuedDrainGuardCalls++;
                }
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("dev/gaius/browser/BrowserPacketScheduler")
                    && call.name.equals("hasPendingPackets")
                    && call.desc.equals("(Ljava/lang/Object;)Z")
                    && nextOpcode(call) instanceof JumpInsnNode branch
                    && branch.getOpcode() == Opcodes.IFEQ) {
                if (currentBlock == transitionBacklogCheck) {
                    transitionBacklogChecks++;
                } else if (currentBlock == commonBacklogCheck && branch.label == inline) {
                    commonBacklogChecks++;
                }
            }
            if (!(instruction instanceof TypeInsnNode type)
                    || type.getOpcode() != Opcodes.INSTANCEOF
                    || !(nextOpcode(type) instanceof JumpInsnNode branch)) {
                continue;
            }
            if (type.desc.equals(
                    "net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl")
                    && branch.getOpcode() == Opcodes.IFNE
                    && branch.label == inline) {
                configurationBranches++;
            } else if (type.desc.equals(
                    "net/minecraft/client/multiplayer/ClientPacketListener")
                    && branch.getOpcode() == Opcodes.IFNE
                    && branch.label == playListener) {
                clientPlayBranches++;
            } else if (branch.getOpcode() == Opcodes.IFNE && branch.label == inline) {
                for (int index = 0; index < commonInlinePacketTypes.length; index++) {
                    if (type.desc.equals(commonInlinePacketTypes[index])) {
                        commonInlinePacketBranches[index]++;
                    }
                }
            } else if (branch.getOpcode() == Opcodes.IFNE && branch.label == commonBacklogCheck) {
                for (int index = 0; index < commonInlinePacketTypes.length; index++) {
                    if (type.desc.equals(commonInlinePacketTypes[index])) {
                        commonPlayPacketBranches[index]++;
                    }
                }
            } else if (branch.getOpcode() == Opcodes.IFNE
                    && branch.label == transitionBacklogCheck) {
                for (int index = 0; index < transitionPacketTypes.length; index++) {
                    if (type.desc.equals(transitionPacketTypes[index])) {
                        transitionPacketBranches[index]++;
                    }
                }
            }
        }
        AbstractInsnNode inlineStart = nextOpcode(inline);
        AbstractInsnNode inlineReturn = inlineHook == null ? null : nextOpcode(inlineHook);
        AbstractInsnNode queuedReturn = nextOpcode(queuedHandleReturn);
        AbstractInsnNode vanillaStart = nextOpcode(vanillaScheduling);
        AbstractInsnNode forcedAbort = nextOpcode(forcedPlaySchedule);
        AbstractInsnNode forcedThrow = forcedAbort == null ? null : nextOpcode(forcedAbort);
        if (inlineHooks != 1
                || inlineStart != inlineHook
                || inlineReturn == null
                || inlineReturn.getOpcode() != Opcodes.RETURN
                || configurationBranches != 1
                || !allExactlyOne(transitionPacketBranches)
                || !allExactlyOne(commonInlinePacketBranches)
                || !allExactlyOne(commonPlayPacketBranches)
                || clientPlayBranches != 1
                || queuedDrainGuardCalls != 1
                || workerServerChecks != 1
                || workerServerDrainGuardCalls != 1
                || transitionBacklogChecks != 1
                || commonBacklogChecks != 1
                || queuedReturn == null
                || queuedReturn.getOpcode() != Opcodes.RETURN
                || forcedAbort != forcedPlayAbort
                || forcedThrow == null
                || forcedThrow.getOpcode() != Opcodes.ATHROW
                || vanillaStart == inlineHook) {
            throw new IllegalStateException(
                    "PacketUtils inline decoder accounting CFG changed: hooks=" + inlineHooks
                            + ", configuration=" + configurationBranches
                            + ", transitionPackets=" + counts(transitionPacketBranches)
                            + ", commonInlinePackets=" + counts(commonInlinePacketBranches)
                            + ", commonPlayPackets=" + counts(commonPlayPacketBranches)
                            + ", clientPlay=" + clientPlayBranches
                            + ", drainGuard=" + queuedDrainGuardCalls
                            + ", workerServer=" + workerServerChecks
                            + ", workerServerDrainGuard=" + workerServerDrainGuardCalls
                            + ", transitionBacklog=" + transitionBacklogChecks
                            + ", commonBacklog=" + commonBacklogChecks);
        }
        method.maxStack = Math.max(method.maxStack, 3);
        writeComputeFrames(node, output);
    }

    /** Drains a short packet batch so actions stay responsive without monopolizing a browser turn. */
    public static void patchPacketProcessorBrowserSlice(String jar, Path output) throws IOException {
        String owner = "net/minecraft/network/PacketProcessor";
        ClassNode node = read(jar, owner + ".class");
        patchPacketProcessorLifecycle(node);
        MethodNode schedule = find(
                node,
                "scheduleIfPossible",
                "(Lnet/minecraft/network/PacketListener;"
                        + "Lnet/minecraft/network/protocol/Packet;)V");
        patchPacketProcessorQueuedAccounting(schedule);
        MethodNode method = find(node, "processQueuedPackets", "()V");
        if (method == null) {
            throw new IOException("PacketProcessor browser slice patch point was not found");
        }
        String vanillaFallbackName = "gaius$vanillaProcessQueuedPackets";
        if (findNullable(node, vanillaFallbackName, "()V") != null) {
            throw new IllegalStateException(
                    "PacketProcessor already contains " + vanillaFallbackName);
        }
        MethodNode vanillaFallback = new MethodNode(
                (method.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                        | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                vanillaFallbackName,
                method.desc,
                method.signature,
                method.exceptions == null ? null : method.exceptions.toArray(new String[0]));
        method.accept(vanillaFallback);
        node.methods.add(vanillaFallback);
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        LabelNode vanilla = new LabelNode();
        LabelNode reentrantReturn = new LabelNode();
        LabelNode handleStart = new LabelNode();
        LabelNode handleEnd = new LabelNode();
        LabelNode handleFailure = new LabelNode();

        InsnList code = new InsnList();
        // A queued packet handler may synchronously pump the same PacketProcessor.  Returning
        // here preserves the outer FIFO drain; falling through to the vanilla method would
        // recurse into the same queue while its owner ledger is still active.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "isProcessingQueuedPacket",
                "(Ljava/lang/Object;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFNE, reentrantReturn));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "beginBatch",
                "(Ljava/lang/Object;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "closed", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "shouldProcessNext",
                "(Ljava/lang/Object;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "packetsToBeHandled", "Ljava/util/Queue;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Queue", "poll", "()Ljava/lang/Object;", true));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST, "net/minecraft/network/PacketProcessor$ListenerAndPacket"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(handleStart);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/PacketProcessor$ListenerAndPacket",
                "packet",
                "()Lnet/minecraft/network/protocol/Packet;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "beginQueuedPacket",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/PacketProcessor$ListenerAndPacket",
                "handle",
                "()V",
                false));
        code.add(handleEnd);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "packetProcessed",
                "(Ljava/lang/Object;)V",
                false));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(handleFailure);
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPacketScheduler",
                "packetProcessed",
                "(Ljava/lang/Object;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ATHROW));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(vanilla);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                vanillaFallbackName,
                "()V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(reentrantReturn);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 2, 3);
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                handleStart,
                handleEnd,
                handleFailure,
                null));
        patchPacketProcessorCloseAccounting(node, owner);
        verifyPacketProcessorAccounting(node);
        writeComputeFrames(node, output);
    }

    /** Persists the selected browser transport on multiplayer server entries. */
    public static void patchServerDataTransportProtocol(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/multiplayer/ServerData";
        ClassNode node = read(jar, owner + ".class");
        String protocolField = "gaiusProtocol";
        String protocolDescriptor = "Ljava/lang/String;";
        for (var field : node.fields) {
            if (field.name.equals(protocolField)) {
                if (!field.desc.equals(protocolDescriptor)) {
                    throw new IOException("ServerData.gaiusProtocol has an unexpected descriptor");
                }
                throw new IOException("ServerData transport protocol patch was already applied");
            }
        }
        node.fields.add(new org.objectweb.asm.tree.FieldNode(
                Opcodes.ACC_PUBLIC, protocolField, protocolDescriptor, null, null));

        MethodNode constructor = find(node, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;"
                        + "Lnet/minecraft/client/multiplayer/ServerData$Type;)V");
        int constructorReturns = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList initialize = new InsnList();
            initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
            initialize.add(new LdcInsnNode("relay"));
            initialize.add(new FieldInsnNode(Opcodes.PUTFIELD, owner,
                    protocolField, protocolDescriptor));
            constructor.instructions.insertBefore(instruction, initialize);
            constructorReturns++;
        }
        if (constructorReturns == 0) {
            throw new IOException("ServerData constructor return was not found");
        }
        constructor.maxStack = Math.max(constructor.maxStack, 2);

        MethodNode write = find(node, "write", "()Lnet/minecraft/nbt/CompoundTag;");
        int writeReturns = 0;
        for (AbstractInsnNode instruction : write.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            InsnList persist = new InsnList();
            persist.add(new VarInsnNode(Opcodes.ALOAD, 1));
            persist.add(new LdcInsnNode("protocol"));
            persist.add(new VarInsnNode(Opcodes.ALOAD, 0));
            persist.add(new FieldInsnNode(Opcodes.GETFIELD, owner,
                    protocolField, protocolDescriptor));
            persist.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/transport/TransportKind", "fromProtocol",
                    "(Ljava/lang/String;)Ldev/gaius/browser/transport/TransportKind;", false));
            persist.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "dev/gaius/browser/transport/TransportKind", "protocol",
                    "()Ljava/lang/String;", false));
            persist.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/nbt/CompoundTag", "putString",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false));
            write.instructions.insertBefore(instruction, persist);
            writeReturns++;
        }
        if (writeReturns == 0) {
            throw new IOException("ServerData.write return was not found");
        }
        write.maxStack = Math.max(write.maxStack, 4);

        MethodNode read = find(node, "read",
                "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/client/multiplayer/ServerData;");
        int readReturns = 0;
        for (AbstractInsnNode instruction : read.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            InsnList restore = new InsnList();
            restore.add(new VarInsnNode(Opcodes.ALOAD, 1));
            restore.add(new VarInsnNode(Opcodes.ALOAD, 0));
            restore.add(new LdcInsnNode("protocol"));
            restore.add(new LdcInsnNode("relay"));
            restore.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/nbt/CompoundTag", "getStringOr",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false));
            restore.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/transport/TransportKind", "fromProtocol",
                    "(Ljava/lang/String;)Ldev/gaius/browser/transport/TransportKind;", false));
            restore.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "dev/gaius/browser/transport/TransportKind", "protocol",
                    "()Ljava/lang/String;", false));
            restore.add(new FieldInsnNode(Opcodes.PUTFIELD, owner,
                    protocolField, protocolDescriptor));
            restore.add(new VarInsnNode(Opcodes.ALOAD, 1));
            read.instructions.insertBefore(instruction, restore);
            readReturns++;
        }
        if (readReturns == 0) {
            throw new IOException("ServerData.read return was not found");
        }
        read.maxStack = Math.max(read.maxStack, 4);

        MethodNode copyFrom = find(node, "copyFrom",
                "(Lnet/minecraft/client/multiplayer/ServerData;)V");
        int copyReturns = 0;
        for (AbstractInsnNode instruction : copyFrom.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList copy = new InsnList();
            copy.add(new VarInsnNode(Opcodes.ALOAD, 0));
            copy.add(new VarInsnNode(Opcodes.ALOAD, 1));
            copy.add(new FieldInsnNode(Opcodes.GETFIELD, owner,
                    protocolField, protocolDescriptor));
            copy.add(new FieldInsnNode(Opcodes.PUTFIELD, owner,
                    protocolField, protocolDescriptor));
            copyFrom.instructions.insertBefore(instruction, copy);
            copyReturns++;
        }
        if (copyReturns == 0) {
            throw new IOException("ServerData.copyFrom return was not found");
        }
        copyFrom.maxStack = Math.max(copyFrom.maxStack, 2);
        writeComputeFrames(node, output);
    }

    /** Stores transport metadata on the active Connection and binds it before handshake. */
    public static void patchConnectionTransportMetadata(String jar, Path connectionOutput,
            Path connectThreadOutput) throws IOException {
        String connectionOwner = "net/minecraft/network/Connection";
        ClassNode connection = Files.isRegularFile(connectionOutput)
                ? read(connectionOutput) : read(jar, connectionOwner + ".class");
        if (connection.fields.stream().anyMatch(field -> field.name.equals("gaiusTransport"))) {
            throw new IOException("Connection.gaiusTransport is already present");
        }
        connection.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PRIVATE,
                "gaiusTransport", "Ljava/lang/String;", null, null));
        MethodNode constructor = find(connection, "<init>",
                "(Lnet/minecraft/network/protocol/PacketFlow;)V");
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList initialize = new InsnList();
            initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
            initialize.add(new LdcInsnNode("relay"));
            initialize.add(new FieldInsnNode(Opcodes.PUTFIELD, connectionOwner,
                    "gaiusTransport", "Ljava/lang/String;"));
            constructor.instructions.insertBefore(instruction, initialize);
        }
        connection.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PRIVATE,
                "gaiusTransportAddress", "Ljava/lang/String;", null, null));
        MethodNode address = new MethodNode(Opcodes.ACC_PUBLIC, "getGaiusTransportAddress",
                "()Ljava/lang/String;", null, null);
        address.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        address.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, connectionOwner,
                "gaiusTransportAddress", "Ljava/lang/String;"));
        address.instructions.add(new InsnNode(Opcodes.ARETURN));
        address.maxStack = address.maxLocals = 1;
        connection.methods.add(address);

        String loginOwner = "net/minecraft/network/protocol/login/ClientboundLoginFinishedPacket";
        ClassNode loginClass = read(jar, loginOwner + ".class");
        String sessionCtor = "(Lcom/mojang/authlib/GameProfile;Ljava/util/UUID;)V";
        boolean hasSession = loginClass.methods.stream().anyMatch(m -> m.name.equals("<init>")
                && m.desc.equals(sessionCtor));
        MethodNode loginFactory = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "gaiusEaglerLoginFinished", "(Lcom/mojang/authlib/GameProfile;)L" + loginOwner + ";", null, null);
        loginFactory.instructions.add(new TypeInsnNode(Opcodes.NEW, loginOwner));
        loginFactory.instructions.add(new InsnNode(Opcodes.DUP));
        loginFactory.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (hasSession) {
            loginFactory.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/UUID"));
            loginFactory.instructions.add(new InsnNode(Opcodes.DUP));
            loginFactory.instructions.add(new InsnNode(Opcodes.LCONST_0));
            loginFactory.instructions.add(new InsnNode(Opcodes.LCONST_0));
            loginFactory.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/util/UUID", "<init>", "(JJ)V", false));
        }
        loginFactory.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, loginOwner, "<init>",
                hasSession ? sessionCtor : "(Lcom/mojang/authlib/GameProfile;)V", false));
        loginFactory.instructions.add(new InsnNode(Opcodes.ARETURN));
        loginFactory.maxStack = 9;
        loginFactory.maxLocals = 1;
        connection.methods.add(loginFactory);

        MethodNode getter = new MethodNode(Opcodes.ACC_PUBLIC, "getGaiusTransport",
                "()Ljava/lang/String;", null, null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, connectionOwner,
                "channel", "Lio/netty/channel/Channel;"));
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, connectionOwner,
                "gaiusTransport", "Ljava/lang/String;"));
        getter.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/transport/ConnectionTransports", "active",
                "(Lio/netty/channel/Channel;Ljava/lang/String;)Ljava/lang/String;", false));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        getter.maxStack = 2;
        getter.maxLocals = 1;
        connection.methods.add(getter);
        MethodNode binder = new MethodNode(Opcodes.ACC_PUBLIC, "bindGaiusTransport",
                "(Ljava/lang/String;Ljava/lang/String;)V", null, null);
        binder.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        binder.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        binder.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/transport/TransportKind", "fromProtocol",
                "(Ljava/lang/String;)Ldev/gaius/browser/transport/TransportKind;", false));
        binder.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "dev/gaius/browser/transport/TransportKind", "protocol",
                "()Ljava/lang/String;", false));
        binder.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, connectionOwner,
                "gaiusTransport", "Ljava/lang/String;"));
        binder.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        binder.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        binder.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, connectionOwner,
                "gaiusTransportAddress", "Ljava/lang/String;"));
        binder.instructions.add(new InsnNode(Opcodes.RETURN));
        binder.maxStack = 2;
        binder.maxLocals = 3;
        connection.methods.add(binder);
        writeComputeFrames(connection, connectionOutput);

        String threadOwner = "net/minecraft/client/gui/screens/ConnectScreen$1";
        ClassNode thread = Files.isRegularFile(connectThreadOutput)
                ? read(connectThreadOutput) : read(jar, threadOwner + ".class");
        MethodNode run = find(thread, "run", "()V");
        int constructors = 0;
        for (AbstractInsnNode instruction : run.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals(connectionOwner) || !call.name.equals("<init>")) continue;
            InsnList bind = new InsnList();
            bind.add(new InsnNode(Opcodes.DUP));
            bind.add(new VarInsnNode(Opcodes.ALOAD, 0));
            bind.add(new FieldInsnNode(Opcodes.GETFIELD, threadOwner, "val$server",
                    "Lnet/minecraft/client/multiplayer/ServerData;"));
            bind.add(new FieldInsnNode(Opcodes.GETFIELD,
                    "net/minecraft/client/multiplayer/ServerData", "gaiusProtocol",
                    "Ljava/lang/String;"));
            bind.add(new VarInsnNode(Opcodes.ALOAD, 0));
            bind.add(new FieldInsnNode(Opcodes.GETFIELD, threadOwner, "val$server",
                    "Lnet/minecraft/client/multiplayer/ServerData;"));
            bind.add(new FieldInsnNode(Opcodes.GETFIELD,
                    "net/minecraft/client/multiplayer/ServerData", "ip", "Ljava/lang/String;"));
            bind.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, connectionOwner,
                    "bindGaiusTransport", "(Ljava/lang/String;Ljava/lang/String;)V", false));
            run.instructions.insert(call, bind);
            constructors++;
        }
        if (constructors != 1) throw new IOException(
                "ConnectScreen connection construction shape changed: " + constructors);
        run.maxStack = Math.max(run.maxStack, 5);
        writeComputeFrames(thread, connectThreadOutput);
    }

    /**
     * Binds PacketProcessor ownership at the real construction boundary.  The frame-boundary
     * bind is intentionally a claim-only operation: a late callback from a closed processor must
     * not clear its retired tombstone and resurrect the static accounting epoch.  Constructor
     * reuse is the only explicit lifecycle operation that may release that tombstone.
     */
    private static void patchPacketProcessorLifecycle(ClassNode node) {
        MethodNode constructor = find(node, "<init>", "(Ljava/lang/Thread;)V");
        int existing = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("dev/gaius/browser/BrowserPacketScheduler")
                    && call.name.equals("bindPacketProcessorLifecycle")
                    && call.desc.equals("(Ljava/lang/Object;)Z")) {
                existing++;
            }
        }
        if (existing != 0) {
            throw new IllegalStateException(
                    "PacketProcessor constructor already contains lifecycle ownership hook: "
                            + existing);
        }
        int returns = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserPacketScheduler",
                    "bindPacketProcessorLifecycle",
                    "(Ljava/lang/Object;)Z",
                    false));
            hook.add(new InsnNode(Opcodes.POP));
            constructor.instructions.insertBefore(instruction, hook);
            returns++;
        }
        if (returns != 1) {
            throw new IllegalStateException(
                    "PacketProcessor constructor lifecycle return shape changed: " + returns);
        }
        constructor.maxStack = Math.max(constructor.maxStack, 1);
    }

    private static void patchPacketProcessorQueuedAccounting(MethodNode schedule) {
        int patched = 0;
        for (AbstractInsnNode instruction : schedule.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode add)
                    || add.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !add.owner.equals("java/util/Queue")
                    || !add.name.equals("add")
                    || !add.desc.equals("(Ljava/lang/Object;)Z")) {
                continue;
            }
            AbstractInsnNode discard = nextOpcode(add);
            if (discard == null || discard.getOpcode() != Opcodes.POP) {
                throw new IllegalStateException(
                        "PacketProcessor.scheduleIfPossible Queue.add result is no longer discarded");
            }
            LabelNode notQueued = new LabelNode();
            InsnList accounting = new InsnList();
            accounting.add(new InsnNode(Opcodes.DUP));
            accounting.add(new JumpInsnNode(Opcodes.IFEQ, notQueued));
            accounting.add(new VarInsnNode(Opcodes.ALOAD, 0));
            accounting.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserPacketScheduler",
                    "packetQueued",
                    "(Ljava/lang/Object;)V",
                    false));
            accounting.add(notQueued);
            schedule.instructions.insert(add, accounting);
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "PacketProcessor.scheduleIfPossible Queue.add telemetry changed: " + patched);
        }
        schedule.maxStack = Math.max(schedule.maxStack, 4);
    }

    private static void patchPacketProcessorCloseAccounting(ClassNode node, String owner) {
        MethodNode close = find(node, "close", "()V");
        int patched = 0;
        for (AbstractInsnNode instruction : close.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList cleanup = new InsnList();
            cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cleanup.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "packetsToBeHandled",
                    "Ljava/util/Queue;"));
            cleanup.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/Queue",
                    "clear",
                    "()V",
                    true));
            cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cleanup.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserPacketScheduler",
                    "reset",
                    "(Ljava/lang/Object;)V",
                    false));
            close.instructions.insertBefore(instruction, cleanup);
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "PacketProcessor.close cleanup return shape changed: " + patched);
        }
        close.maxStack = Math.max(close.maxStack, 1);
    }

    private static void verifyPacketProcessorAccounting(ClassNode node) {
        int queued = 0;
        int processed = 0;
        int reset = 0;
        int shouldProcess = 0;
        int beginQueued = 0;
        int beginBatch = 0;
        int lifecycle = 0;
        int legacySchedulerCalls = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("dev/gaius/browser/BrowserPacketScheduler")) {
                    continue;
                }
                if (call.name.equals("packetQueued")
                        && call.desc.equals("(Ljava/lang/Object;)V")) {
                    queued++;
                } else if (call.name.equals("packetProcessed")
                        && call.desc.equals("(Ljava/lang/Object;)V")) {
                    processed++;
                } else if (call.name.equals("reset")
                        && call.desc.equals("(Ljava/lang/Object;)V")) {
                    reset++;
                } else if (call.name.equals("shouldProcessNext")
                        && call.desc.equals("(Ljava/lang/Object;)Z")) {
                    shouldProcess++;
                } else if (call.name.equals("beginBatch")
                        && call.desc.equals("(Ljava/lang/Object;)Z")) {
                    beginBatch++;
                } else if (call.name.equals("beginQueuedPacket")
                        && call.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)V")) {
                    beginQueued++;
                } else if (call.name.equals("bindPacketProcessorLifecycle")
                        && call.desc.equals("(Ljava/lang/Object;)Z")) {
                    lifecycle++;
                } else if ((call.name.equals("packetQueued")
                        || call.name.equals("packetProcessed")
                        || call.name.equals("reset")
                        || call.name.equals("shouldProcessNext")
                        || call.name.equals("beginBatch")
                        || call.name.equals("beginQueuedPacket"))
                        && (call.desc.equals("()V")
                        || call.desc.equals("()Z")
                        || call.desc.equals("(Ljava/lang/Object;)V"))) {
                    legacySchedulerCalls++;
                }
            }
        }
        if (queued != 1 || processed != 2 || reset != 1 || shouldProcess != 1
                || beginBatch != 1 || beginQueued != 1 || lifecycle != 1
                || legacySchedulerCalls != 0) {
            throw new IllegalStateException(
                    "PacketProcessor accounting hooks changed: queued=" + queued
                            + ", processed=" + processed
                            + ", reset=" + reset
                            + ", shouldProcess=" + shouldProcess
                            + ", beginBatch=" + beginBatch
                            + ", beginQueued=" + beginQueued
                            + ", lifecycle=" + lifecycle
                            + ", legacy=" + legacySchedulerCalls);
        }
        System.out.println("Instrumented exact decoded-packet queue accounting");
    }

    public static void patchLevelLoadTrackerBrowserTimeout(String jar, Path root) throws IOException {
        // The previous browser fast-path forced the client into PLAY before the
        // initial chunk section meshes existed. Keep the vanilla state machine
        // until terrain readiness is independently proven; diagnostics remain in
        // ClientPacketListener above.
        if (!Boolean.getBoolean("gaius.enableUnsafeLevelLoadFastPath")) {
            for (String entry : new String[] {
                    "net/minecraft/client/multiplayer/LevelLoadTracker.class",
                    "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer.class",
                    "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForPlayerChunk.class" }) {
                Path output = root.resolve(entry);
                Files.createDirectories(output.getParent());
                Files.write(output, readEntry(jar, entry));
            }
            return;
        }
        ClassNode tracker = read(jar, "net/minecraft/client/multiplayer/LevelLoadTracker.class");
        boolean patchedClientWaitTimeout = false;
        for (MethodNode method : tracker.methods) {
            if (!method.name.equals("<clinit>")) {
                continue;
            }
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof LdcInsnNode constant
                        && constant.cst instanceof Long value
                        && value == 30L) {
                    constant.cst = 5L;
                    patchedClientWaitTimeout = true;
                    break;
                }
            }
        }
        if (!patchedClientWaitTimeout) {
            throw new IllegalStateException("LevelLoadTracker timeout patch point was not found");
        }
        MethodNode isLevelReady = find(tracker, "isLevelReady", "()Z");
        LabelNode notClientReady = new LabelNode();
        LabelNode notPlayerChunk = new LabelNode();
        LabelNode notServerWait = new LabelNode();
        InsnList levelReadyCode = new InsnList();
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        levelReadyCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/LevelLoadTracker",
                "clientState",
                "Lnet/minecraft/client/multiplayer/LevelLoadTracker$ClientState;"));
        levelReadyCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        levelReadyCode.add(new TypeInsnNode(
                Opcodes.INSTANCEOF,
                "net/minecraft/client/multiplayer/LevelLoadTracker$ClientLevelReady"));
        levelReadyCode.add(new JumpInsnNode(Opcodes.IFEQ, notClientReady));
        levelReadyCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/util/Util",
                "getMillis",
                "()J",
                false));
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        levelReadyCode.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/multiplayer/LevelLoadTracker$ClientLevelReady"));
        levelReadyCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/LevelLoadTracker$ClientLevelReady",
                "readyAt",
                "()J",
                false));
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        levelReadyCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/LevelLoadTracker",
                "closeDelayMs",
                "J"));
        levelReadyCode.add(new InsnNode(Opcodes.LADD));
        levelReadyCode.add(new InsnNode(Opcodes.LCMP));
        levelReadyCode.add(new JumpInsnNode(Opcodes.IFLT, notServerWait));
        levelReadyCode.add(new InsnNode(Opcodes.ICONST_1));
        levelReadyCode.add(new InsnNode(Opcodes.IRETURN));
        levelReadyCode.add(notClientReady);
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        levelReadyCode.add(new TypeInsnNode(
                Opcodes.INSTANCEOF,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForPlayerChunk"));
        levelReadyCode.add(new JumpInsnNode(Opcodes.IFEQ, notPlayerChunk));
        levelReadyCode.add(new InsnNode(Opcodes.ICONST_1));
        levelReadyCode.add(new InsnNode(Opcodes.IRETURN));
        levelReadyCode.add(notPlayerChunk);
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        levelReadyCode.add(new TypeInsnNode(
                Opcodes.INSTANCEOF,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer"));
        levelReadyCode.add(new JumpInsnNode(Opcodes.IFEQ, notServerWait));
        levelReadyCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/util/Util",
                "getMillis",
                "()J",
                false));
        levelReadyCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        levelReadyCode.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer"));
        levelReadyCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer",
                "timeoutAfter",
                "()J",
                false));
        levelReadyCode.add(new InsnNode(Opcodes.LCMP));
        levelReadyCode.add(new JumpInsnNode(Opcodes.IFLT, notServerWait));
        levelReadyCode.add(minecraftEvent("client.levelReady.timeoutFallback"));
        levelReadyCode.add(new InsnNode(Opcodes.ICONST_1));
        levelReadyCode.add(new InsnNode(Opcodes.IRETURN));
        levelReadyCode.add(notServerWait);
        levelReadyCode.add(new InsnNode(Opcodes.ICONST_0));
        levelReadyCode.add(new InsnNode(Opcodes.IRETURN));
        replace(isLevelReady, levelReadyCode, 6, 2);
        write(tracker, root.resolve("net/minecraft/client/multiplayer/LevelLoadTracker.class"));

        ClassNode waiting = read(jar, "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer.class");
        MethodNode tick = null;
        for (MethodNode method : waiting.methods) {
            if (method.name.equals("tick")
                    && method.desc.equals("()Lnet/minecraft/client/multiplayer/LevelLoadTracker$ClientState;")) {
                tick = method;
                break;
            }
        }
        if (tick == null) {
            tick = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "tick",
                    "()Lnet/minecraft/client/multiplayer/LevelLoadTracker$ClientState;",
                    null,
                    null);
            waiting.methods.add(tick);
        }
        LabelNode notTimedOut = new LabelNode();
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/util/Util",
                "getMillis",
                "()J",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer",
                "timeoutAfter",
                "J"));
        code.add(new InsnNode(Opcodes.LCMP));
        code.add(new JumpInsnNode(Opcodes.IFLE, notTimedOut));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/client/multiplayer/LevelLoadTracker",
                "LOGGER",
                "Lorg/slf4j/Logger;"));
        code.add(new LdcInsnNode(
                "Timed out while waiting for initial level loading packets in the browser, continuing anyway"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "org/slf4j/Logger",
                "warn",
                "(Ljava/lang/String;)V",
                true));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer",
                "loadingPacketsReceived",
                "()Lnet/minecraft/client/multiplayer/LevelLoadTracker$ClientState;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(notTimedOut);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(tick, code, 4, 1);
        write(waiting, root.resolve("net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForServer.class"));

        ClassNode waitingForPlayerChunk = read(
                jar,
                "net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForPlayerChunk.class");
        MethodNode isReady = find(waitingForPlayerChunk, "isReady", "()Z");
        InsnList readyCode = new InsnList();
        readyCode.add(new InsnNode(Opcodes.ICONST_1));
        readyCode.add(new InsnNode(Opcodes.IRETURN));
        replace(isReady, readyCode, 1, 1);
        write(waitingForPlayerChunk,
                root.resolve("net/minecraft/client/multiplayer/LevelLoadTracker$WaitingForPlayerChunk.class"));
    }

    public static void patchFriendlyByteBufBrowserLongArray(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/network/FriendlyByteBuf.class");
        MethodNode method = find(node, "readFixedSizeLongArray",
                "(Lio/netty/buffer/ByteBuf;[J)[J");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserLongArrayCodec",
                "readFixedSizeLongArray",
                "(Lio/netty/buffer/ByteBuf;[J)[J",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 2, 2);
        write(node, output);
    }

    public static void patchLanServerPinger(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/server/LanServerPinger.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")
                    && method.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new LdcInsnNode("LanServerPinger-browser"));
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                        "java/lang/Thread", "<init>", "(Ljava/lang/String;)V", false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "isRunning", "Z"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "motd", "Ljava/lang/String;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "serverAddress", "Ljava/lang/String;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "socket", "Ljava/net/DatagramSocket;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, 3);
            } else if (method.name.equals("run")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 1);
            }
        }
        write(node, output);
    }

    public static void patchHttpUtil(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/HttpUtil.class");
        boolean patchedDownload = false;
        boolean removedJavaProxy = false;
        boolean responseValidation = false;
        InsnList availablePort = new InsnList();
        availablePort.add(new IntInsnNode(Opcodes.SIPUSH, 25564));
        availablePort.add(new InsnNode(Opcodes.IRETURN));
        replace(find(node, "getAvailablePort", "()I"), availablePort, 1, 0);
        InsnList portAvailable = new InsnList();
        portAvailable.add(new InsnNode(Opcodes.ICONST_1));
        portAvailable.add(new InsnNode(Opcodes.IRETURN));
        replace(find(node, "isPortAvailable", "(I)Z"), portAvailable, 1, 1);
        for (MethodNode method : node.methods) {
            if (method.name.equals("downloadFile")
                    && method.desc.startsWith("(Ljava/nio/file/Path;Ljava/net/URL;")) {
                // Keep the vanilla body intact under a private name.  The public entry point
                // performs one direct CORS attempt and retries through the relay only when that
                // attempt fails, so a partially consumed body is never continued.
                String descriptor = method.desc;
                String vanillaName = "gaiusDownloadFileVanilla";
                int originalAccess = method.access;
                method.name = vanillaName;
                method.access = (method.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                        | Opcodes.ACC_PRIVATE;
                method.access |= Opcodes.ACC_STATIC;
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (!(instruction instanceof MethodInsnNode call)
                            || !call.owner.equals("java/net/URL")
                            || !call.name.equals("openConnection")
                            || !call.desc.equals("(Ljava/net/Proxy;)Ljava/net/URLConnection;")) {
                        continue;
                    }
                    method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                    call.desc = "()Ljava/net/URLConnection;";
                    InsnList timeout = new InsnList();
                    timeout.add(new InsnNode(Opcodes.DUP));
                    timeout.add(new IntInsnNode(Opcodes.SIPUSH, 5000));
                    timeout.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                            "java/net/URLConnection", "setConnectTimeout", "(I)V", false));
                    timeout.add(new InsnNode(Opcodes.DUP));
                    timeout.add(new IntInsnNode(Opcodes.SIPUSH, 5000));
                    timeout.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                            "java/net/URLConnection", "setReadTimeout", "(I)V", false));
                    method.instructions.insert(call, timeout);
                    removedJavaProxy = true;
                }
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (!(instruction instanceof MethodInsnNode call)
                            || !call.owner.equals("java/net/HttpURLConnection")
                            || !call.name.equals("getContentLengthLong")
                            || !call.desc.equals("()J")) {
                        continue;
                    }
                    AbstractInsnNode lengthStore = call.getNext();
                    if (!(lengthStore instanceof VarInsnNode store)
                            || store.getOpcode() != Opcodes.LSTORE) {
                        throw new IOException("HttpUtil content-length store was not found");
                    }
                    InsnList validation = new InsnList();
                    validation.add(new VarInsnNode(Opcodes.ALOAD, 8));
                    validation.add(new VarInsnNode(Opcodes.ALOAD, 9));
                    validation.add(new VarInsnNode(Opcodes.LLOAD, store.var));
                    validation.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "dev/gaius/browser/BrowserHttpProxy",
                            "validateResourcePackResponse",
                            "(Ljava/net/HttpURLConnection;Ljava/io/InputStream;J)Ljava/io/InputStream;",
                            false));
                    validation.add(new VarInsnNode(Opcodes.ASTORE, 9));
                    method.instructions.insert(lengthStore, validation);
                    responseValidation = true;
                    break;
                }
                MethodNode wrapper = new MethodNode(
                        originalAccess, "downloadFile", descriptor, method.signature,
                        method.exceptions == null ? null : method.exceptions.toArray(new String[0]));
                InsnList code = wrapper.instructions;
                LabelNode start = new LabelNode();
                LabelNode fallback = new LabelNode();
                LabelNode done = new LabelNode();
                int originalUrl = method.maxLocals + 1;
                int originalHeaders = originalUrl + 1;
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new VarInsnNode(Opcodes.ASTORE, originalUrl));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new VarInsnNode(Opcodes.ASTORE, originalHeaders));
                // The catch handler uses both saved arguments. Keep their stores outside
                // the protected range so an exception from either direct proxy lookup
                // cannot expose an uninitialized local to the fallback path.
                code.add(start);
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserHttpProxy", "directResourcePack",
                        "(Ljava/net/URL;)Ljava/net/URL;", false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 1));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserHttpProxy", "directResourcePackHeaders",
                        "()Ljava/util/Map;", false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 2));
                loadDownloadArgs(code);
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "net/minecraft/util/HttpUtil", vanillaName, descriptor, false));
                code.add(new JumpInsnNode(Opcodes.GOTO, done));
                LabelNode rethrow = new LabelNode();
                code.add(fallback);
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserHttpProxy", "shouldFallbackResourcePack",
                        "(Ljava/lang/Throwable;)Z", false));
                code.add(new JumpInsnNode(Opcodes.IFEQ, rethrow));
                code.add(new InsnNode(Opcodes.POP));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserHttpProxy", "resourcePackDirectFallback",
                        "()V", false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserHttpProxy", "resetResourcePackFile",
                        "(Ljava/nio/file/Path;)V", false));
                code.add(new VarInsnNode(Opcodes.ALOAD, originalUrl));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserHttpProxy", "proxyResourcePack",
                        "(Ljava/net/URL;)Ljava/net/URL;", false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 1));
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new VarInsnNode(Opcodes.ALOAD, originalHeaders));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserHttpProxy", "browserSafeResourcePackHeaders",
                        "(Ljava/net/URL;Ljava/util/Map;)Ljava/util/Map;", false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 2));
                loadDownloadArgs(code);
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "net/minecraft/util/HttpUtil", vanillaName, descriptor, false));
                code.add(done);
                code.add(new InsnNode(Opcodes.ARETURN));
                code.add(rethrow);
                code.add(new InsnNode(Opcodes.ATHROW));
                wrapper.tryCatchBlocks.add(new TryCatchBlockNode(
                        start, fallback, fallback, "java/lang/Throwable"));
                wrapper.maxLocals = originalHeaders + 1;
                wrapper.maxStack = 10;
                node.methods.add(wrapper);
                patchedDownload = true;
                break;
            }
        }
                if (!patchedDownload || !removedJavaProxy || !responseValidation) {
            throw new IllegalStateException(
                    "HttpUtil browser download patch points were not found: method="
                                    + patchedDownload + " proxy=" + removedJavaProxy
                                    + " response=" + responseValidation);
        }
        write(node, output);
    }

    private static void loadDownloadArgs(InsnList code) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
    }

    public static void patchLanServerDetector(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/server/LanServerDetection$LanServerDetector.class");
        String owner = "net/minecraft/client/server/LanServerDetection$LanServerDetector";
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "()V", false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "serverList",
                        "Lnet/minecraft/client/server/LanServerDetection$LanServerList;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "pingGroup", "Ljava/net/InetAddress;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "socket", "Ljava/net/MulticastSocket;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, 2);
            } else if (method.name.equals("run") && method.desc.equals("()V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 1);
            }
        }
        write(node, output);
    }

    public static void patchRealmsNetwork(String jar, Path root) throws IOException {
        ClassNode request = read(jar, "com/mojang/realmsclient/client/Request.class");
        MethodNode requestConstructor = find(request, "<init>", "(Ljava/lang/String;II)V");
        int proxiedUrls = 0;
        int proxyCalls = 0;
        for (AbstractInsnNode instruction = requestConstructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.owner.equals("java/net/URL")
                    && call.name.equals("<init>")
                    && call.desc.equals("(Ljava/lang/String;)V")) {
                requestConstructor.instructions.insertBefore(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserHttpProxy",
                        "proxyRealms",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        false));
                proxiedUrls++;
            } else if (call.owner.equals("java/net/URL")
                    && call.name.equals("openConnection")
                    && call.desc.equals("(Ljava/net/Proxy;)Ljava/net/URLConnection;")) {
                requestConstructor.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                call.desc = "()Ljava/net/URLConnection;";
                proxyCalls++;
            }
        }
        if (proxiedUrls != 2 || proxyCalls != 1) {
            throw new IllegalStateException(
                    "Realms Request URL patch points changed: urls=" + proxiedUrls
                            + " proxies=" + proxyCalls);
        }
        MethodNode cookie = find(
                request,
                "cookie",
                "(Ljava/net/HttpURLConnection;Ljava/lang/String;Ljava/lang/String;)V");
        InsnList cookieCode = new InsnList();
        cookieCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cookieCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cookieCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        cookieCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserHttpProxy",
                "addRealmsCookie",
                "(Ljava/net/HttpURLConnection;Ljava/lang/String;Ljava/lang/String;)V",
                false));
        cookieCode.add(new InsnNode(Opcodes.RETURN));
        replace(cookie, cookieCode, 3, 3);
        write(request, root.resolve("com/mojang/realmsclient/client/Request.class"));

        ClassNode download = read(jar, "com/mojang/realmsclient/client/FileDownload.class");
        for (MethodNode method : download.methods) {
            if (method.name.equals("contentLength")
                    && method.desc.equals("(Ljava/lang/String;)Ljava/util/OptionalLong;")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, "java/util/OptionalLong", "empty",
                        "()Ljava/util/OptionalLong;", false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, method.maxLocals);
            } else if (method.name.equals("download")
                    && method.desc.startsWith("(Lcom/mojang/realmsclient/dto/WorldDownload;")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ICONST_1));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        "com/mojang/realmsclient/client/FileDownload",
                        "error",
                        "Z"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ICONST_1));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        "com/mojang/realmsclient/client/FileDownload",
                        "finished",
                        "Z"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, method.maxLocals);
            }
        }
        write(download, root.resolve("com/mojang/realmsclient/client/FileDownload.class"));

        ClassNode upload = read(jar, "com/mojang/realmsclient/client/FileUpload.class");
        for (MethodNode method : upload.methods) {
            if (method.name.equals("<init>")) {
                String owner = "com/mojang/realmsclient/client/FileUpload";
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                putField(code, owner, "file", "Ljava/io/File;", Opcodes.ALOAD, 1);
                putField(code, owner, "realmId", "J", Opcodes.LLOAD, 2);
                putField(code, owner, "slotId", "I", Opcodes.ILOAD, 4);
                putField(code, owner, "uploadInfo",
                        "Lcom/mojang/realmsclient/dto/UploadInfo;", Opcodes.ALOAD, 5);
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 6));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/client/User",
                        "getSessionId",
                        "()Ljava/lang/String;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "sessionId", "Ljava/lang/String;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 6));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/client/User",
                        "getName",
                        "()Ljava/lang/String;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "username", "Ljava/lang/String;"));
                putField(code, owner, "clientVersion", "Ljava/lang/String;", Opcodes.ALOAD, 7);
                putField(code, owner, "worldVersion", "Ljava/lang/String;", Opcodes.ALOAD, 8);
                putField(code, owner, "uploadStatus",
                        "Lcom/mojang/realmsclient/client/UploadStatus;", Opcodes.ALOAD, 9);
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 3, method.maxLocals);
            } else if (method.name.equals("close")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            } else if (method.name.equals("startUpload")) {
                InsnList code = new InsnList();
                code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode("Realms upload is unavailable in the browser"));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "java/lang/UnsupportedOperationException",
                        "<init>",
                        "(Ljava/lang/String;)V",
                        false));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/util/concurrent/CompletableFuture",
                        "failedFuture",
                        "(Ljava/lang/Throwable;)Ljava/util/concurrent/CompletableFuture;",
                        false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 3, method.maxLocals);
            }
        }
        write(upload, root.resolve("com/mojang/realmsclient/client/FileUpload.class"));

        ClassNode ping = read(jar, "com/mojang/realmsclient/client/Ping.class");
        for (MethodNode method : ping.methods) {
            if ((method.name.equals("ping") && method.access == (method.access | Opcodes.ACC_STATIC))
                    || method.name.equals("pingAllRegions")) {
                if (Type.getReturnType(method.desc).getDescriptor().equals("Ljava/util/List;")) {
                    InsnList code = new InsnList();
                    code.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList",
                            "()Ljava/util/List;", false));
                    code.add(new InsnNode(Opcodes.ARETURN));
                    replace(method, code, 1, method.maxLocals);
                }
            }
        }
        write(ping, root.resolve("com/mojang/realmsclient/client/Ping.class"));
    }

    private static void replaceNull(ClassNode node, String name, String descriptor) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(find(node, name, descriptor), code, 1, Type.getArgumentsAndReturnSizes(descriptor) >> 2);
    }

    private static void replaceSecondByteArray(ClassNode node, String name, String descriptor) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(find(node, name, descriptor), code, 1, Type.getArgumentsAndReturnSizes(descriptor) >> 2);
    }

    private static void replaceStaticFieldReturn(
            ClassNode node, String name, String descriptor,
            String owner, String field, String fieldDescriptor) {
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, field, fieldDescriptor));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(find(node, name, descriptor), code, 1,
                Type.getArgumentsAndReturnSizes(descriptor) >> 2);
    }
}
