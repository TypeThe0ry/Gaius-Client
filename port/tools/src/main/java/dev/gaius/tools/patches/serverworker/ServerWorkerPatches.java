package dev.gaius.tools.patches.serverworker;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findNullable;
import static dev.gaius.tools.patches.support.ClientPatchSupport.minecraftEvent;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextRealInstruction;
import static dev.gaius.tools.patches.support.ClientPatchSupport.patchDeepWorldgenPulseBackEdges;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousRealInstruction;
import static dev.gaius.tools.patches.support.ClientPatchSupport.pumpBrowserChannels;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.requireWorldgenSchedulerCalls;
import static dev.gaius.tools.patches.support.ClientPatchSupport.serverStartupPhase;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import dev.gaius.tools.BuildTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
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
 * The server-worker domain of the MinecraftClientPatcher step
 * ({@code patches/server-worker}): the integrated server in its browser Worker, from the server
 * main, dedicated server services and settings to the connection listener, server packet
 * listeners, chunk sending, spawn preparation, chunk cache waits, mob AI cooperation, chase
 * services and the singleplayer login.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically.
 */
public final class ServerWorkerPatches {
    private ServerWorkerPatches() {
    }

    public static void patchChunkMapBrowserInitialViewDistance(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/level/ChunkMap.class");
        MethodNode setDistance = find(node, "setServerViewDistance", "(I)V");
        int setDistancePatched = 0;
        for (AbstractInsnNode instruction = setDistance.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.ICONST_2) {
                continue;
            }
            AbstractInsnNode maximum = nextRealInstruction(instruction);
            AbstractInsnNode clamp = nextRealInstruction(maximum);
            if (!(maximum instanceof IntInsnNode integer)
                    || integer.getOpcode() != Opcodes.BIPUSH
                    || integer.operand != 32
                    || !(clamp instanceof MethodInsnNode call)
                    || !call.owner.equals("net/minecraft/util/Mth")
                    || !call.name.equals("clamp")
                    || !call.desc.equals("(III)I")) {
                continue;
            }
            setDistance.instructions.set(instruction, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "minimumServerViewDistance",
                    "()I",
                    false));
            setDistancePatched++;
        }

        MethodNode playerDistance = find(
                node,
                "getPlayerViewDistance",
                "(Lnet/minecraft/server/level/ServerPlayer;)I");
        int playerDistancePatched = 0;
        for (AbstractInsnNode instruction = playerDistance.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.ICONST_2) {
                continue;
            }
            AbstractInsnNode requestedDistance = previousRealInstruction(instruction);
            AbstractInsnNode owner = nextRealInstruction(instruction);
            AbstractInsnNode maximum = nextRealInstruction(owner);
            AbstractInsnNode clamp = nextRealInstruction(maximum);
            if (!(requestedDistance instanceof MethodInsnNode requestedCall)
                    || !requestedCall.owner.equals("net/minecraft/server/level/ServerPlayer")
                    || !requestedCall.name.equals("requestedViewDistance")
                    || !requestedCall.desc.equals("()I")
                    || !(owner instanceof VarInsnNode ownerLoad)
                    || ownerLoad.getOpcode() != Opcodes.ALOAD
                    || ownerLoad.var != 0
                    || !(maximum instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals("net/minecraft/server/level/ChunkMap")
                    || !field.name.equals("serverViewDistance")
                    || !field.desc.equals("I")
                    || !(clamp instanceof MethodInsnNode call)
                    || !call.owner.equals("net/minecraft/util/Mth")
                    || !call.name.equals("clamp")
                    || !call.desc.equals("(III)I")) {
                continue;
            }
            playerDistance.instructions.set(instruction, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "minimumServerViewDistance",
                    "()I",
                    false));
            playerDistancePatched++;
        }
        if (setDistancePatched != 1 || playerDistancePatched != 1) {
            throw new IllegalStateException(
                    "ChunkMap browser initial view-distance patch points were not found: set="
                            + setDistancePatched
                            + ", player="
                            + playerDistancePatched);
        }
        MethodNode updatePlayerPos = find(
                node,
                "updatePlayerPos",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        InsnList recordPlayerPosition = new InsnList();
        recordPlayerPosition.add(new VarInsnNode(Opcodes.ALOAD, 1));
        recordPlayerPosition.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerPlayer",
                "getX",
                "()D",
                false));
        recordPlayerPosition.add(new VarInsnNode(Opcodes.ALOAD, 1));
        recordPlayerPosition.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerPlayer",
                "getZ",
                "()D",
                false));
        recordPlayerPosition.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserChunkTaskPriority",
                "recordPlayerPosition",
                "(DD)V",
                false));
        updatePlayerPos.instructions.insert(recordPlayerPosition);
        updatePlayerPos.maxStack = Math.max(updatePlayerPos.maxStack, 4);
        write(node, output);
    }

    public static void patchChunkTaskPriorityQueueBrowserNearestFirst(
            String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/level/ChunkTaskPriorityQueue.class");
        MethodNode pop = find(
                node,
                "pop",
                "()Lnet/minecraft/server/level/ChunkTaskPriorityQueue$TasksForChunk;");
        int selectedKeyLocal = -1;
        int chooserPatched = 0;
        for (AbstractInsnNode instruction = pop.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(
                            "it/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap")
                    || !call.name.equals("firstLongKey")
                    || !call.desc.equals("()J")) {
                continue;
            }
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = "dev/gaius/browser/BrowserChunkTaskPriority";
            call.name = "chooseNext";
            call.desc = "(Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;)J";
            call.itf = false;
            AbstractInsnNode store = nextRealInstruction(call);
            if (!(store instanceof VarInsnNode variable)
                    || variable.getOpcode() != Opcodes.LSTORE) {
                throw new IllegalStateException(
                        "ChunkTaskPriorityQueue selected-key local changed");
            }
            selectedKeyLocal = variable.var;
            chooserPatched++;
        }
        int removalPatched = 0;
        for (AbstractInsnNode instruction = pop.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(
                            "it/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap")
                    || !call.name.equals("removeFirst")
                    || !call.desc.equals("()Ljava/lang/Object;")) {
                continue;
            }
            pop.instructions.insertBefore(
                    call,
                    new VarInsnNode(Opcodes.LLOAD, selectedKeyLocal));
            call.name = "remove";
            call.desc = "(J)Ljava/lang/Object;";
            removalPatched++;
        }
        if (chooserPatched != 1 || removalPatched != 1 || selectedKeyLocal < 0) {
            throw new IllegalStateException(
                    "ChunkTaskPriorityQueue nearest-first patch points changed: choose="
                            + chooserPatched
                            + ", remove="
                            + removalPatched);
        }
        write(node, output);
    }

    public static void patchSingleplayerLogin(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/network/ServerLoginPacketListenerImpl.class");
        MethodNode hello = find(node, "handleHello",
                "(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/login/ServerboundHelloPacket",
                "name",
                "()Ljava/lang/String;",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                "net/minecraft/server/network/ServerLoginPacketListenerImpl",
                "requestedUsername",
                "Ljava/lang/String;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.NEW, "com/mojang/authlib/GameProfile"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/network/protocol/login/ServerboundHelloPacket",
                "profileId",
                "()Ljava/util/UUID;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/server/network/ServerLoginPacketListenerImpl",
                "requestedUsername",
                "Ljava/lang/String;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "com/mojang/authlib/GameProfile",
                "<init>",
                "(Ljava/util/UUID;Ljava/lang/String;)V",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserSkinProfile",
                "apply",
                "(Lcom/mojang/authlib/GameProfile;)Lcom/mojang/authlib/GameProfile;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/network/ServerLoginPacketListenerImpl",
                "startClientVerification",
                "(Lcom/mojang/authlib/GameProfile;)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(hello, code, 5, 2);
        write(node, output);
    }

    /**
     * Skips ClientboundLoginCompressionPacket for the host player's own connection, like vanilla
     * singleplayer's memory connection. Continues from the ServerLoginPacketListenerImpl.class
     * rewritten by {@link #patchSingleplayerLogin}. Only the compression gate of
     * verifyLoginAndFinishConnectionSetup changes: Connection.isMemoryConnection is replaced by
     * BrowserHostConnection.skipsLoginCompression, which also accepts the host's tunnel channel;
     * Open to LAN guests keep the dedicated server's compression threshold, and handleHello's
     * authentication gate and every other isMemoryConnection caller stay vanilla.
     */
    public static void patchSingleplayerHostCompression(Path loginClass) throws IOException {
        ClassNode node = read(loginClass);
        MethodNode verify = find(node, "verifyLoginAndFinishConnectionSetup",
                "(Lcom/mojang/authlib/GameProfile;)V");
        int gates = 0;
        int thresholds = 0;
        for (AbstractInsnNode instruction : verify.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/network/Connection")
                    && call.name.equals("isMemoryConnection")
                    && call.desc.equals("()Z")) {
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = "dev/gaius/browser/BrowserHostConnection";
                call.name = "skipsLoginCompression";
                call.desc = "(Lnet/minecraft/network/Connection;)Z";
                call.itf = false;
                gates++;
            } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/server/MinecraftServer")
                    && call.name.equals("getCompressionThreshold")
                    && call.desc.equals("()I")) {
                thresholds++;
            }
        }
        if (gates != 1 || thresholds < 1) {
            throw new IllegalStateException(
                    "Login compression gate patch point changed: isMemoryConnection=" + gates
                            + ", getCompressionThreshold=" + thresholds);
        }
        write(node, loginClass);
    }

    public static void patchDedicatedServerBrowserConsole(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/dedicated/DedicatedServer$1.class");
        MethodNode run = find(node, "run", "()V");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(run, code, 0, 1);
        write(node, output);
    }

    /**
     * {@code options = BrowserIntegratedServerMain.resumeAfterBootstrap(parser, options)}, placed
     * directly after {@code Bootstrap.validate()}. Vanilla parses its arguments once, before
     * Bootstrap, into one OptionSet local and reads the world options only after Bootstrap. A
     * parked prewarm (an idle Worker running vanilla Main from its first instruction) waits in
     * that call for the start handshake and returns the same parser's parse of the real
     * arguments; the regular start returns the options unchanged. The parser and OptionSet locals
     * keep their types, so the method's frames stay valid.
     */
    static InsnList serverMainResumeAfterBootstrap(MethodNode main) {
        String parser = "joptsimple/OptionParser";
        String optionSet = "joptsimple/OptionSet";
        int parserLocal = -1;
        int optionsLocal = -1;
        for (AbstractInsnNode instruction = main.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            AbstractInsnNode store = nextOpcode(call);
            if (call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals(parser)
                    && call.name.equals("<init>")
                    && call.desc.equals("()V")) {
                if (parserLocal >= 0 || !(store instanceof VarInsnNode variable)
                        || store.getOpcode() != Opcodes.ASTORE) {
                    throw new IllegalStateException("Server Main option parser shape changed");
                }
                parserLocal = variable.var;
            } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(parser)
                    && call.name.equals("parse")
                    && call.desc.equals("([Ljava/lang/String;)L" + optionSet + ";")) {
                if (optionsLocal >= 0 || !(store instanceof VarInsnNode variable)
                        || store.getOpcode() != Opcodes.ASTORE) {
                    throw new IllegalStateException("Server Main option parse shape changed");
                }
                optionsLocal = variable.var;
            }
        }
        if (parserLocal < 0 || optionsLocal < 0) {
            throw new IllegalStateException("Server Main option parser or parsed options not found");
        }
        InsnList resume = new InsnList();
        resume.add(new VarInsnNode(Opcodes.ALOAD, parserLocal));
        resume.add(new VarInsnNode(Opcodes.ALOAD, optionsLocal));
        resume.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "resumeAfterBootstrap",
                "(L" + parser + ";L" + optionSet + ";)L" + optionSet + ";",
                false));
        resume.add(new VarInsnNode(Opcodes.ASTORE, optionsLocal));
        main.maxStack = Math.max(main.maxStack, 2);
        return resume;
    }

    public static void patchServerMainBrowser(String jar, Path output) throws IOException {
        String owner = "net/minecraft/server/Main";
        ClassNode node = read(jar, owner + ".class");

        MethodNode pidFile = find(node, "writePidFile", "(Ljava/nio/file/Path;)V");
        InsnList pidCode = new InsnList();
        pidCode.add(new InsnNode(Opcodes.RETURN));
        replace(pidFile, pidCode, 0, 1);

        MethodNode main = find(node, "main", "([Ljava/lang/String;)V");
        String authenticationService =
                "com/mojang/authlib/yggdrasil/YggdrasilAuthenticationService";
        boolean offlineAuthenticationPatched = false;
        for (AbstractInsnNode instruction = main.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !constructor.owner.equals(authenticationService)
                    || !constructor.name.equals("<init>")
                    || !constructor.desc.equals("(Ljava/net/Proxy;)V")) {
                continue;
            }
            AbstractInsnNode proxy = previousOpcode(constructor);
            AbstractInsnNode duplicate = previousOpcode(proxy);
            AbstractInsnNode allocation = previousOpcode(duplicate);
            if (!(proxy instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETSTATIC
                    || !field.owner.equals("java/net/Proxy")
                    || !field.name.equals("NO_PROXY")
                    || duplicate == null
                    || duplicate.getOpcode() != Opcodes.DUP
                    || !(allocation instanceof TypeInsnNode type)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !type.desc.equals(authenticationService)) {
                throw new IllegalStateException(
                        "Server authentication-service constructor shape changed");
            }
            main.instructions.remove(allocation);
            main.instructions.remove(duplicate);
            main.instructions.set(constructor, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    authenticationService,
                    "createOffline",
                    "(Ljava/net/Proxy;)L" + authenticationService + ";",
                    false));
            offlineAuthenticationPatched = true;
            break;
        }
        if (!offlineAuthenticationPatched) {
            offlineAuthenticationPatched = patchServerMainDiscoveryServiceBrowser(main);
        }
        if (!offlineAuthenticationPatched) {
            throw new IllegalStateException(
                    "Server offline authentication-service patch point was not found");
        }

        boolean startupFailurePatched = false;
        for (AbstractInsnNode instruction = main.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof LdcInsnNode constant)
                    || !"Failed to start the minecraft server".equals(constant.cst)) {
                continue;
            }
            AbstractInsnNode loggerLoad = instruction.getPrevious();
            while (loggerLoad != null
                    && !(loggerLoad instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETSTATIC
                            && field.owner.equals(owner)
                            && field.name.equals("LOGGER"))) {
                loggerLoad = loggerLoad.getPrevious();
            }
            AbstractInsnNode throwableLoad = instruction.getNext();
            while (throwableLoad != null && throwableLoad.getOpcode() < 0) {
                throwableLoad = throwableLoad.getNext();
            }
            if (loggerLoad == null
                    || !(throwableLoad instanceof VarInsnNode variable)
                    || variable.getOpcode() != Opcodes.ALOAD) {
                throw new IllegalStateException("Server startup failure handler shape changed");
            }
            InsnList propagate = new InsnList();
            propagate.add(new VarInsnNode(Opcodes.ALOAD, variable.var));
            propagate.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "rethrowStartupFailure",
                    "(Ljava/lang/Throwable;)V",
                    false));
            main.instructions.insertBefore(loggerLoad, propagate);
            main.maxStack = Math.max(main.maxStack, 1);
            startupFailurePatched = true;
            break;
        }
        if (!startupFailurePatched) {
            throw new IllegalStateException("Server startup failure handler was not found");
        }

        boolean bootstrapCheckpointPatched = false;
        boolean bootstrapValidatedPatched = false;
        boolean settingsCheckpointPatched = false;
        boolean packRepositoryCheckpointPatched = false;
        boolean worldLoadConfigCheckpointPatched = false;
        boolean worldLoadStartedPatched = false;
        boolean datapackCheckpointPatched = false;
        for (AbstractInsnNode instruction = main.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (!bootstrapCheckpointPatched
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/server/Bootstrap")
                    && call.name.equals("bootStrap")
                    && call.desc.equals("()V")) {
                InsnList checkpoint = new InsnList();
                checkpoint.add(new LdcInsnNode("bootstrap-complete"));
                checkpoint.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserStartupScheduler",
                        "phase",
                        "(Ljava/lang/String;)V",
                        false));
                main.instructions.insert(call, checkpoint);
                bootstrapCheckpointPatched = true;
                continue;
            }
            if (!bootstrapValidatedPatched
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/server/Bootstrap")
                    && call.name.equals("validate")
                    && call.desc.equals("()V")) {
                InsnList validated = serverStartupPhase("bootstrap-validated");
                validated.add(serverMainResumeAfterBootstrap(main));
                main.instructions.insert(call, validated);
                bootstrapValidatedPatched = true;
                continue;
            }
            if (!settingsCheckpointPatched
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/server/dedicated/DedicatedServerSettings")
                    && call.name.equals("forceSave")
                    && call.desc.equals("()V")) {
                main.instructions.insert(call, serverStartupPhase("server-settings-ready"));
                settingsCheckpointPatched = true;
                continue;
            }
            if (!packRepositoryCheckpointPatched
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/server/packs/repository/ServerPacksSource")
                    && call.name.equals("createPackRepository")) {
                AbstractInsnNode store = nextOpcode(call);
                if (!(store instanceof VarInsnNode variable)
                        || variable.getOpcode() != Opcodes.ASTORE) {
                    throw new IllegalStateException(
                            "Server pack repository checkpoint shape changed");
                }
                main.instructions.insert(store, serverStartupPhase("server-pack-repository-ready"));
                packRepositoryCheckpointPatched = true;
                continue;
            }
            if (!worldLoadConfigCheckpointPatched
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(owner)
                    && call.name.equals("loadOrCreateConfig")) {
                AbstractInsnNode store = nextOpcode(call);
                if (!(store instanceof VarInsnNode variable)
                        || variable.getOpcode() != Opcodes.ASTORE) {
                    throw new IllegalStateException(
                            "Server world load config checkpoint shape changed");
                }
                main.instructions.insert(store, serverStartupPhase("server-world-load-config-ready"));
                worldLoadConfigCheckpointPatched = true;
                continue;
            }
            if (!worldLoadStartedPatched
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/util/Util")
                    && call.name.equals("blockUntilDone")
                    && call.desc.equals("(Ljava/util/function/Function;)"
                            + "Ljava/util/concurrent/CompletableFuture;")) {
                main.instructions.insertBefore(call, serverStartupPhase("server-world-load-started"));
                worldLoadStartedPatched = true;
                continue;
            }
            if (!datapackCheckpointPatched
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("java/util/concurrent/CompletableFuture")
                    && call.name.equals("get")
                    && call.desc.equals("()Ljava/lang/Object;")) {
                AbstractInsnNode cast = nextOpcode(call);
                AbstractInsnNode store = cast == null ? null : nextOpcode(cast);
                if (!(cast instanceof TypeInsnNode type)
                        || cast.getOpcode() != Opcodes.CHECKCAST
                        || !type.desc.equals("net/minecraft/server/WorldStem")
                        || !(store instanceof VarInsnNode variable)
                        || variable.getOpcode() != Opcodes.ASTORE) {
                    throw new IllegalStateException("Server datapack completion shape changed");
                }
                InsnList checkpoint = new InsnList();
                checkpoint.add(new LdcInsnNode("datapacks-loaded"));
                checkpoint.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserStartupScheduler",
                        "phase",
                        "(Ljava/lang/String;)V",
                        false));
                main.instructions.insert(store, checkpoint);
                datapackCheckpointPatched = true;
            }
        }
        if (!bootstrapCheckpointPatched
                || !bootstrapValidatedPatched
                || !settingsCheckpointPatched
                || !packRepositoryCheckpointPatched
                || !worldLoadConfigCheckpointPatched
                || !worldLoadStartedPatched
                || !datapackCheckpointPatched) {
            throw new IllegalStateException("Server startup checkpoints were not found");
        }
        main.maxStack = Math.max(main.maxStack, 8);

        patchServerWorldLoaderCooperativeExecutor(node);

        String legacyFactoryDescriptor = "(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                + "Lnet/minecraft/server/packs/repository/PackRepository;"
                + "Lnet/minecraft/server/WorldStem;"
                + "Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                + "Lnet/minecraft/server/Services;"
                + "Ljoptsimple/OptionSet;"
                + "Ljoptsimple/OptionSpec;"
                + "Ljoptsimple/OptionSpec;"
                + "Ljoptsimple/OptionSpec;"
                + "Ljoptsimple/OptionSpec;"
                + "Ljoptsimple/OptionSpec;"
                + "Ljava/lang/Thread;)Lnet/minecraft/server/dedicated/DedicatedServer;";
        String currentFactoryDescriptor =
                "(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                        + "Lnet/minecraft/server/packs/repository/PackRepository;"
                        + "Lnet/minecraft/server/WorldStem;"
                        + "Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                        + "Lnet/minecraft/server/Services;"
                        + "Lnet/minecraft/server/jsonrpc/ManagementServer;"
                        + "Lnet/minecraft/server/notifications/NotificationManager;"
                        + "Ljoptsimple/OptionSet;"
                        + "Ljoptsimple/OptionSpec;"
                        + "Ljoptsimple/OptionSpec;"
                        + "Ljoptsimple/OptionSpec;"
                        + "Ljoptsimple/OptionSpec;"
                        + "Ljoptsimple/OptionSpec;"
                        + "Ljava/lang/Thread;)Lnet/minecraft/server/dedicated/DedicatedServer;";
        MethodNode factory = null;
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("lambda$main$")
                    || !Type.getReturnType(method.desc).getDescriptor()
                            .equals("Lnet/minecraft/server/dedicated/DedicatedServer;")) {
                continue;
            }
            if (factory != null) {
                throw new IllegalStateException("Multiple dedicated-server factory lambdas found");
            }
            factory = method;
        }
        if (factory == null
                || (!factory.desc.equals(legacyFactoryDescriptor)
                        && !factory.desc.equals(currentFactoryDescriptor))) {
            throw new IllegalStateException("Unsupported dedicated-server factory descriptor: "
                    + (factory == null ? "missing" : factory.desc));
        }
        String server = "net/minecraft/server/dedicated/DedicatedServer";
        boolean currentFactory = factory.desc.equals(currentFactoryDescriptor);
        int optionSetLocal = currentFactory ? 7 : 5;
        int firstOptionLocal = currentFactory ? 8 : 6;
        int serverThreadLocal = currentFactory ? 13 : 11;
        int serverLocal = currentFactory ? 14 : 12;
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, server));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, serverThreadLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        if (currentFactory) {
            // The Optional<GameRules> parameter of the modern DedicatedServer constructor is what
            // vanilla's integrated server receives from the Create World screen. The page sends it
            // in the Worker start message; an ordinary dedicated start has none. Decoding needs the
            // features enabled for the world, which the WorldStem carries.
            String worldStem = "net/minecraft/server/WorldStem";
            String worldDataAndGenSettings =
                    "net/minecraft/world/level/storage/LevelDataAndDimensions$WorldDataAndGenSettings";
            find(read(jar, worldStem + ".class"), "worldDataAndGenSettings",
                    "()L" + worldDataAndGenSettings + ";");
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    worldStem,
                    "worldDataAndGenSettings",
                    "()L" + worldDataAndGenSettings + ";",
                    false));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    worldDataAndGenSettings,
                    "data",
                    "()Lnet/minecraft/world/level/storage/WorldData;",
                    false));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "net/minecraft/world/level/storage/WorldData",
                    "enabledFeatures",
                    "()Lnet/minecraft/world/flag/FeatureFlagSet;",
                    true));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "initialGameRules",
                    "(Lnet/minecraft/world/flag/FeatureFlagSet;)Ljava/util/Optional;",
                    false));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "dataFixer",
                "()Lcom/mojang/datafixers/DataFixer;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        if (currentFactory) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 5));
            code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                server,
                "<init>",
                currentFactory
                        ? "(Ljava/lang/Thread;"
                                + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                                + "Lnet/minecraft/server/packs/repository/PackRepository;"
                                + "Lnet/minecraft/server/WorldStem;"
                                + "Ljava/util/Optional;"
                                + "Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                                + "Lcom/mojang/datafixers/DataFixer;"
                                + "Lnet/minecraft/server/Services;"
                                + "Lnet/minecraft/server/jsonrpc/ManagementServer;"
                                + "Lnet/minecraft/server/notifications/NotificationManager;)V"
                        : "(Ljava/lang/Thread;"
                                + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                                + "Lnet/minecraft/server/packs/repository/PackRepository;"
                                + "Lnet/minecraft/server/WorldStem;"
                                + "Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                                + "Lcom/mojang/datafixers/DataFixer;"
                                + "Lnet/minecraft/server/Services;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, serverLocal));
        if (currentFactory) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 6));
            code.add(new VarInsnNode(Opcodes.ALOAD, serverLocal));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/server/notifications/NotificationManager",
                    "setServer",
                    "(Lnet/minecraft/server/dedicated/DedicatedServer;)V",
                    false));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, serverLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, optionSetLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, firstOptionLocal));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "joptsimple/OptionSet",
                "valueOf",
                "(Ljoptsimple/OptionSpec;)Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, server, "setPort", "(I)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, serverLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, optionSetLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, firstOptionLocal + 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "joptsimple/OptionSet",
                "has",
                "(Ljoptsimple/OptionSpec;)Z",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, server, "setDemo", "(Z)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, serverLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, optionSetLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, firstOptionLocal + 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "joptsimple/OptionSet",
                "valueOf",
                "(Ljoptsimple/OptionSpec;)Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                server,
                "setId",
                "(Ljava/lang/String;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, serverLocal));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(factory, code, currentFactory ? 12 : 9, serverLocal + 1);
        writeComputeFrames(node, output);
    }

    /**
     * A browser Worker has one JavaScript execution thread, so Minecraft's background executor
     * cannot provide real parallelism here. Route WorldLoader's background stages through the
     * queue supplied by Util.blockUntilDone instead. This avoids deeply nested direct-executor
     * continuations and gives every registry stage an explicit cooperative resume point.
     */
    private static void patchServerWorldLoaderCooperativeExecutor(ClassNode node) {
        MethodNode target = null;
        MethodInsnNode backgroundExecutor = null;
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("lambda$main$")
                    || (method.access & Opcodes.ACC_STATIC) == 0
                    || !Type.getReturnType(method.desc).getDescriptor()
                            .equals("Ljava/util/concurrent/CompletableFuture;")) {
                continue;
            }
            MethodInsnNode candidateBackground = null;
            boolean worldLoaderCall = false;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) {
                    continue;
                }
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("net/minecraft/util/Util")
                        && call.name.equals("backgroundExecutor")
                        && call.desc.equals("()Lnet/minecraft/TracingExecutor;")) {
                    candidateBackground = call;
                } else if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("net/minecraft/server/WorldLoader")
                        && call.name.equals("load")
                        && call.desc.endsWith("Ljava/util/concurrent/CompletableFuture;")) {
                    worldLoaderCall = true;
                }
            }
            if (!worldLoaderCall || candidateBackground == null) {
                continue;
            }
            if (target != null) {
                throw new IllegalStateException(
                        "Multiple server WorldLoader executor lambdas were found");
            }
            target = method;
            backgroundExecutor = candidateBackground;
        }
        if (target == null || backgroundExecutor == null) {
            throw new IllegalStateException(
                    "Server WorldLoader background executor patch point was not found");
        }

        Type[] arguments = Type.getArgumentTypes(target.desc);
        if (arguments.length == 0
                || !arguments[arguments.length - 1].getDescriptor()
                        .equals("Ljava/util/concurrent/Executor;")) {
            throw new IllegalStateException(
                    "Server WorldLoader lambda no longer ends with its cooperative executor: "
                            + target.desc);
        }
        int executorLocal = 0;
        for (int index = 0; index < arguments.length - 1; index++) {
            executorLocal += arguments[index].getSize();
        }
        target.instructions.set(
                backgroundExecutor,
                new VarInsnNode(Opcodes.ALOAD, executorLocal));

        MethodInsnNode worldLoaderCall = null;
        for (AbstractInsnNode instruction : target.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/server/WorldLoader")
                    && call.name.equals("load")) {
                worldLoaderCall = call;
                break;
            }
        }
        if (worldLoaderCall == null) {
            throw new IllegalStateException("Server WorldLoader call disappeared during patching");
        }
        target.instructions.insertBefore(
                worldLoaderCall,
                serverStartupPhase("world-loader-cooperative-executor"));
        target.maxStack = Math.max(target.maxStack, 8);
    }

    /**
     * authlib 10 (Minecraft 26.3): server Main calls
     * {@code MinecraftServicesDiscoveryService.create(Proxy.NO_PROXY)}, which enables the
     * services key set (a public-key fetch) on top of the discovery fetch. Insert
     * {@code ICONST_0} and call {@code create(Proxy, boolean)} instead: the key set stays off.
     * {@code ServerPatches263.patchDiscoveryServiceBrowser} later retargets that call to
     * {@code BrowserDiscoveryServices.create}, so the Worker (never an online session) gets
     * {@code createOffline} and no discovery fetch at all (PLAN D7). Returns {@code false} when
     * the 26.3 shape is absent so the caller fails closed.
     */
    private static boolean patchServerMainDiscoveryServiceBrowser(MethodNode main) {
        String discoveryService = "com/mojang/authlib/services/MinecraftServicesDiscoveryService";
        String createDescriptor = "(Ljava/net/Proxy;)L" + discoveryService + ";";
        MethodInsnNode target = null;
        for (AbstractInsnNode instruction = main.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals(discoveryService)
                    || !call.name.equals("create")
                    || !call.desc.equals(createDescriptor)) {
                continue;
            }
            if (target != null) {
                throw new IllegalStateException(
                        "Server Main calls MinecraftServicesDiscoveryService.create(Proxy) twice");
            }
            target = call;
        }
        if (target == null) {
            return false;
        }
        if (!(previousOpcode(target) instanceof FieldInsnNode proxy)
                || proxy.getOpcode() != Opcodes.GETSTATIC
                || !proxy.owner.equals("java/net/Proxy")
                || !proxy.name.equals("NO_PROXY")
                || !proxy.desc.equals("Ljava/net/Proxy;")) {
            throw new IllegalStateException(
                    "Server discovery-service create(Proxy) is not preceded by Proxy.NO_PROXY");
        }
        main.instructions.insertBefore(target, new InsnNode(Opcodes.ICONST_0));
        target.desc = "(Ljava/net/Proxy;Z)L" + discoveryService + ";";
        // NO_PROXY and the flag: the operand stack is empty before NO_PROXY in 26.3 Main.
        main.maxStack = Math.max(main.maxStack, 2);
        return true;
    }

    public static void patchDedicatedServerBrowser(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/dedicated/DedicatedServer";
        String properties = "net/minecraft/server/dedicated/DedicatedServerProperties";
        ClassNode node = read(jar, owner + ".class");
        MethodNode init = find(node, "initServer", "()Z");
        removeDedicatedServerGameTypeOverride(init, properties);
        tryRemoveBooleanFieldBlock(
                init, properties, "managementServerEnabled", 2, Opcodes.IFEQ);
        removeBooleanFieldBlock(init, properties, "enableQuery", 1, Opcodes.IFEQ);
        removeBooleanFieldBlock(init, properties, "enableRcon", 1, Opcodes.IFEQ);
        removeMethodConditionBlock(
                init, owner, "getMaxTickLength", "()J", 1, Opcodes.IFLE);
        removeBooleanFieldBlock(init, properties, "enableJmxMonitoring", 1, Opcodes.IFEQ);

        boolean playerListConfigured = false;
        for (AbstractInsnNode instruction = init.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(owner)
                    || !call.name.equals("setPlayerList")
                    || !call.desc.equals("(Lnet/minecraft/server/players/PlayerList;)V")) {
                continue;
            }
            InsnList configure = new InsnList();
            configure.add(new VarInsnNode(Opcodes.ALOAD, 0));
            configure.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    "getPlayerList",
                    "()Lnet/minecraft/server/dedicated/DedicatedPlayerList;",
                    false));
            configure.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "configurePlayerList",
                    "(Lnet/minecraft/server/players/PlayerList;)V",
                    false));
            init.instructions.insert(call, configure);
            init.maxStack = Math.max(init.maxStack, 3);
            playerListConfigured = true;
            break;
        }
        if (!playerListConfigured) {
            throw new IllegalStateException("DedicatedServer player list configuration point was not found");
        }

        MethodNode tick = find(node, "tickServer", "(Ljava/util/function/BooleanSupplier;)V");
        removeNullableFieldBlock(
                tick, owner, "jsonRpcServer", "Lnet/minecraft/server/jsonrpc/ManagementServer;");

        MethodNode exit = find(node, "onServerExit", "()V");
        removeNullableFieldBlock(
                exit, owner, "gui", "Lnet/minecraft/server/gui/MinecraftServerGui;");
        removeNullableFieldBlock(
                exit, owner, "rconThread", "Lnet/minecraft/server/rcon/thread/RconThread;");
        removeNullableFieldBlock(
                exit, owner, "queryThreadGs4", "Lnet/minecraft/server/rcon/thread/QueryThreadGs4;");
        removeNullableFieldBlock(
                exit, owner, "jsonRpcServer", "Lnet/minecraft/server/jsonrpc/ManagementServer;");
        writeComputeFrames(node, output);
    }

    /**
     * Keep the game type serialized by CreateWorldScreen's WorldData.  The
     * browser worker writes a dedicated server.properties file, but that file
     * must not replace the selected game type while DedicatedServer initializes
     * an already-created world.
     */
    private static void removeDedicatedServerGameTypeOverride(
            MethodNode init, String properties) {
        int removed = 0;
        for (AbstractInsnNode instruction : init.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !call.owner.equals("net/minecraft/world/level/storage/WorldData")
                    || !call.name.equals("setGameType")
                    || !call.desc.equals("(Lnet/minecraft/world/level/GameType;)V")) {
                continue;
            }
            AbstractInsnNode gameType = previousRealInstruction(call);
            AbstractInsnNode propertyGet = previousRealInstruction(gameType);
            AbstractInsnNode propertyField = previousRealInstruction(propertyGet);
            AbstractInsnNode propertiesObject = previousRealInstruction(propertyField);
            AbstractInsnNode worldDataField = previousRealInstruction(propertiesObject);
            AbstractInsnNode receiver = previousRealInstruction(worldDataField);
            if (!(gameType instanceof TypeInsnNode cast)
                    || cast.getOpcode() != Opcodes.CHECKCAST
                    || !cast.desc.equals("net/minecraft/world/level/GameType")
                    || !(propertyGet instanceof MethodInsnNode get)
                    || get.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !get.owner.equals("net/minecraft/server/dedicated/Settings$MutableValue")
                    || !get.name.equals("get")
                    || !get.desc.equals("()Ljava/lang/Object;")
                    || !(propertyField instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals(properties)
                    || !field.name.equals("gameMode")
                    || !(propertiesObject instanceof VarInsnNode propertyReceiver)
                    || propertyReceiver.getOpcode() != Opcodes.ALOAD
                    || !(worldDataField instanceof FieldInsnNode dataField)
                    || dataField.getOpcode() != Opcodes.GETFIELD
                    || !dataField.name.equals("worldData")
                    || !(receiver instanceof VarInsnNode worldReceiver)
                    || worldReceiver.getOpcode() != Opcodes.ALOAD) {
                continue;
            }
            // Keep the original receiver/value evaluation and discard both
            // references.  This preserves the method's existing CFG/frame
            // shape while removing only the WorldData mutation.
            InsnList discard = new InsnList();
            discard.add(new InsnNode(Opcodes.POP));
            discard.add(new InsnNode(Opcodes.POP));
            init.instructions.insertBefore(call, discard);
            init.instructions.remove(call);
            removed++;
        }
        if (removed != 1) {
            throw new IllegalStateException(
                    "DedicatedServer game type override patch point changed: " + removed);
        }
    }

    public static void patchDedicatedSettingsBrowser(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/dedicated/Settings.class");
        MethodNode store = find(node, "store", "(Ljava/nio/file/Path;)V");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(store, code, 0, 2);
        write(node, output);
    }

    public static void patchServerTextFilterBrowser(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/network/ServerTextFilter.class");
        MethodNode create = find(
                node,
                "createFromConfig",
                "(Lnet/minecraft/server/dedicated/DedicatedServerProperties;)"
                        + "Lnet/minecraft/server/network/ServerTextFilter;");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(create, code, 1, 1);
        write(node, output);
    }

    private static void removeBooleanFieldBlock(
            MethodNode method,
            String owner,
            String fieldName,
            int setupInstructionCount,
            int jumpOpcode) {
        if (!tryRemoveBooleanFieldBlock(
                method, owner, fieldName, setupInstructionCount, jumpOpcode)) {
            throw new IllegalStateException(
                    method.name + " browser block field was not found: " + fieldName);
        }
    }

    private static boolean tryRemoveBooleanFieldBlock(
            MethodNode method,
            String owner,
            String fieldName,
            int setupInstructionCount,
            int jumpOpcode) {
        FieldInsnNode marker = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(owner)
                    && field.name.equals(fieldName)) {
                marker = field;
                break;
            }
        }
        if (marker == null) {
            return false;
        }
        removeConditionalBlock(method, marker, setupInstructionCount, jumpOpcode);
        return true;
    }

    private static void removeMethodConditionBlock(
            MethodNode method,
            String owner,
            String methodName,
            String descriptor,
            int setupInstructionCount,
            int jumpOpcode) {
        MethodInsnNode marker = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(methodName)
                    && call.desc.equals(descriptor)) {
                marker = call;
                break;
            }
        }
        if (marker == null) {
            throw new IllegalStateException(
                    method.name + " browser condition was not found: " + methodName);
        }
        removeConditionalBlock(method, marker, setupInstructionCount, jumpOpcode);
    }

    private static void removeNullableFieldBlock(
            MethodNode method, String owner, String fieldName, String descriptor) {
        FieldInsnNode marker = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(owner)
                    && field.name.equals(fieldName)
                    && field.desc.equals(descriptor)) {
                marker = field;
                break;
            }
        }
        if (marker == null) {
            throw new IllegalStateException(
                    method.name + " nullable browser field was not found: " + fieldName);
        }
        removeConditionalBlock(method, marker, 1, Opcodes.IFNULL);
    }

    private static void removeConditionalBlock(
            MethodNode method,
            AbstractInsnNode marker,
            int setupInstructionCount,
            int jumpOpcode) {
        AbstractInsnNode start = marker;
        for (int index = 0; index < setupInstructionCount; index++) {
            start = previousRealInstruction(start);
            if (start == null) {
                throw new IllegalStateException(method.name + " browser block start was not found");
            }
        }
        JumpInsnNode exit = null;
        for (var instruction = nextRealInstruction(marker);
                instruction != null;
                instruction = nextRealInstruction(instruction)) {
            if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == jumpOpcode) {
                exit = jump;
                break;
            }
        }
        if (exit == null) {
            throw new IllegalStateException(method.name + " browser block exit was not found");
        }

        var removed = new java.util.HashSet<AbstractInsnNode>();
        for (var instruction = start; instruction != null && instruction != exit.label;) {
            var next = instruction.getNext();
            removed.add(instruction);
            method.instructions.remove(instruction);
            instruction = next;
        }
        method.tryCatchBlocks.removeIf(block -> removed.contains(block.start)
                || removed.contains(block.end)
                || removed.contains(block.handler));
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
    }

    public static void patchServerConnectionListenerBrowserWorker(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/network/ServerConnectionListener";
        ClassNode node = read(jar, owner + ".class");
        if (node.fields.stream().noneMatch(field -> "browserInstance".equals(field.name))) {
            node.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "browserInstance",
                    "L" + owner + ";",
                    null,
                    null));
        }
        MethodNode method = find(node, "startTcpServerListener", "(Ljava/net/InetAddress;I)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                owner,
                "browserInstance",
                "L" + owner + ";"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "channels",
                "Ljava/util/List;"));
        code.add(new TypeInsnNode(Opcodes.NEW, "io/netty/bootstrap/Bootstrap"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "io/netty/bootstrap/Bootstrap",
                "<init>",
                "()V",
                false));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/server/network/EventLoopGroupHolder",
                "remote",
                "(Z)Lnet/minecraft/server/network/EventLoopGroupHolder;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/network/EventLoopGroupHolder",
                "eventLoopGroup",
                "()Lio/netty/channel/EventLoopGroup;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/AbstractBootstrap",
                "group",
                "(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new LdcInsnNode(Type.getObjectType(
                "io/netty/channel/browser/BrowserWebSocketChannel")));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/AbstractBootstrap",
                "channel",
                "(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new TypeInsnNode(Opcodes.NEW, owner + "$1"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner + "$1",
                "<init>",
                "(L" + owner + ";)V",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/AbstractBootstrap",
                "handler",
                "(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/AbstractBootstrap;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "io/netty/resolver/NoopAddressResolverGroup",
                "INSTANCE",
                "Lio/netty/resolver/NoopAddressResolverGroup;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "resolver",
                "(Lio/netty/resolver/AddressResolverGroup;)Lio/netty/bootstrap/Bootstrap;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "tunnelAddress",
                "()Ljava/net/InetSocketAddress;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap",
                "connect",
                "(Ljava/net/SocketAddress;)Lio/netty/channel/ChannelFuture;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "io/netty/channel/ChannelFuture",
                "syncUninterruptibly",
                "()Lio/netty/channel/ChannelFuture;",
                true));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "add",
                "(Ljava/lang/Object;)Z",
                true));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "markServerListenerReady",
                "()V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 5, 3);

        MethodNode opener = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "openAdditionalBrowserConnection",
                "(Ljava/lang/String;)V",
                null,
                null);
        InsnList open = opener.instructions;
        LabelNode noListener = new LabelNode();
        open.add(new FieldInsnNode(
                Opcodes.GETSTATIC, owner, "browserInstance", "L" + owner + ";"));
        open.add(new JumpInsnNode(Opcodes.IFNULL, noListener));
        open.add(new FieldInsnNode(
                Opcodes.GETSTATIC, owner, "browserInstance", "L" + owner + ";"));
        open.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "channels", "Ljava/util/List;"));
        open.add(new TypeInsnNode(Opcodes.NEW, "io/netty/bootstrap/Bootstrap"));
        open.add(new InsnNode(Opcodes.DUP));
        open.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "io/netty/bootstrap/Bootstrap", "<init>", "()V", false));
        open.add(new InsnNode(Opcodes.ICONST_0));
        open.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "net/minecraft/server/network/EventLoopGroupHolder", "remote",
                "(Z)Lnet/minecraft/server/network/EventLoopGroupHolder;", false));
        open.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/network/EventLoopGroupHolder", "eventLoopGroup",
                "()Lio/netty/channel/EventLoopGroup;", false));
        // Use the same concrete Bootstrap group path as the startup listener.
        // The server Worker is compiled separately from the client runtime and
        // does not necessarily retain the optional gaiusGroup bridge method.
        open.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/AbstractBootstrap", "group",
                "(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;", false));
        open.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        open.add(new LdcInsnNode(Type.getObjectType("io/netty/channel/browser/BrowserWebSocketChannel")));
        open.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/AbstractBootstrap", "channel",
                "(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", false));
        open.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        open.add(new TypeInsnNode(Opcodes.NEW, owner + "$1"));
        open.add(new InsnNode(Opcodes.DUP));
        open.add(new FieldInsnNode(
                Opcodes.GETSTATIC, owner, "browserInstance", "L" + owner + ";"));
        open.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner + "$1", "<init>",
                "(L" + owner + ";)V", false));
        open.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/AbstractBootstrap", "handler",
                "(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/AbstractBootstrap;", false));
        open.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/bootstrap/Bootstrap"));
        open.add(new FieldInsnNode(Opcodes.GETSTATIC,
                "io/netty/resolver/NoopAddressResolverGroup", "INSTANCE",
                "Lio/netty/resolver/NoopAddressResolverGroup;"));
        open.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap", "resolver",
                "(Lio/netty/resolver/AddressResolverGroup;)Lio/netty/bootstrap/Bootstrap;", false));
        open.add(new VarInsnNode(Opcodes.ALOAD, 0));
        open.add(new IntInsnNode(Opcodes.SIPUSH, 25565));
        open.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/net/InetSocketAddress", "createUnresolved",
                "(Ljava/lang/String;I)Ljava/net/InetSocketAddress;", false));
        open.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "io/netty/bootstrap/Bootstrap", "connect",
                "(Ljava/net/SocketAddress;)Lio/netty/channel/ChannelFuture;", false));
        // BrowserWebSocketChannel completes its WebSocket handshake on a
        // later browser task. Waiting synchronously here makes the relay-backed
        // LAN channel fail before that task can run; the Channel object is
        // already available and can be registered immediately.
        open.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "io/netty/channel/ChannelFuture", "channel",
                "()Lio/netty/channel/Channel;", true));
        open.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
        open.add(new InsnNode(Opcodes.POP));
        LabelNode opened = new LabelNode();
        open.add(new JumpInsnNode(Opcodes.GOTO, opened));
        // A missing startup listener is a hard failure.  Returning silently
        // makes Open to LAN report a false success while no relay endpoint is
        // registered, leaving joiners stuck in the connect screen.
        open.add(noListener);
        open.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        open.add(new InsnNode(Opcodes.DUP));
        open.add(new LdcInsnNode("Browser server listener is not initialized"));
        open.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V", false));
        open.add(new InsnNode(Opcodes.ATHROW));
        open.add(opened);
        open.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(opener);
        writeComputeFrames(node, output);
    }

    public static void patchLevelChunkSectionBrowserSynchronous(
            String jar, Path output, boolean deepWorldgenCheckpoints) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/chunk/LevelChunkSection.class");
        MethodNode method = findNullable(
                node,
                "fillBiomesFromNoise",
                "(Lnet/minecraft/world/level/biome/BiomeResolver;"
                        + "Lnet/minecraft/world/level/biome/Climate$Sampler;III)V");
        if (method == null) {
            // 26.3 dropped the Climate$Sampler parameter; the three quart loops are unchanged.
            method = find(
                    node,
                    "fillBiomesFromNoise",
                    "(Lnet/minecraft/world/level/biome/BiomeResolver;III)V");
        }
        requireWorldgenSchedulerCalls("LevelChunkSection.fillBiomesFromNoise", method, 0);
        if (deepWorldgenCheckpoints) {
            patchDeepWorldgenPulseBackEdges(method, "LevelChunkSection.fillBiomesFromNoise");
            writeComputeFrames(node, output);
        } else {
            write(node, output);
        }
    }

    private static MethodInsnNode browserWorldgenCheckpoint() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "checkpoint",
                "()V",
                false);
    }

    private static MethodInsnNode browserWorldgenBeginTaskWork() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "beginTaskWork",
                "(Ljava/lang/String;)I",
                false);
    }

    private static MethodInsnNode browserWorldgenEndTaskWork() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "endTaskWork",
                "(I)V",
                false);
    }

    private static MethodInsnNode browserWorldgenMobAiPulse() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "mobAiPulse",
                "()V",
                false);
    }

    private static MethodInsnNode browserWorldgenMobEntityPulse() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "mobEntityPulse",
                "(Ljava/lang/Object;)V",
                false);
    }

    /**
     * Instruments a task-layer entry without touching the deep synchronous
     * worldgen methods.  Normal returns are explicit so the scope closes before
     * {@code pollTask} hands control back to {@code waitUntilNextTick}; the
     * per-invocation token is loaded at every close, and the catch-all is a
     * Java-bytecode finally for exceptions which escape existing handlers.
     */
    private static void instrumentBrowserTaskScope(
            MethodNode method, String target, int returnOpcode) {
        instrumentBrowserTaskScope(method, target, returnOpcode, null);
    }

    /**
     * Adds a task-scope token and a caller-supplied entry operation.  The
     * optional operation is placed after the token store but inside the
     * catch-all range, so generic pollTask keeps its urgent packet pump while
     * every exceptional path still closes the exact invocation that began.
     */
    private static void instrumentBrowserTaskScope(
            MethodNode method,
            String target,
            int returnOpcode,
            InsnList afterTokenEntry) {
        if (method.instructions.getFirst() == null) {
            throw new IllegalStateException(target + " has no instructions");
        }

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        int taskScopeLocal = method.maxLocals++;
        int throwableLocal = method.maxLocals++;
        InsnList entry = new InsnList();
        entry.add(new LdcInsnNode(target));
        entry.add(browserWorldgenBeginTaskWork());
        entry.add(new VarInsnNode(Opcodes.ISTORE, taskScopeLocal));
        // Start the catch range only after beginTaskWork has returned its
        // token.  Any generic entry pump after this point is therefore closed
        // with the same token if it throws.
        entry.add(start);
        if (afterTokenEntry != null) {
            entry.add(afterTokenEntry);
        }
        method.instructions.insertBefore(method.instructions.getFirst(), entry);

        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != returnOpcode) {
                continue;
            }
            InsnList close = new InsnList();
            close.add(new VarInsnNode(Opcodes.ILOAD, taskScopeLocal));
            close.add(browserWorldgenEndTaskWork());
            method.instructions.insertBefore(instruction, close);
            returns++;
        }
        if (returns == 0) {
            throw new IllegalStateException(target + " has no normal return for task scope");
        }

        InsnList cleanup = new InsnList();
        cleanup.add(end);
        cleanup.add(handler);
        cleanup.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, taskScopeLocal));
        cleanup.add(browserWorldgenEndTaskWork());
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        cleanup.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(cleanup);
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                start, end, handler, "java/lang/Throwable"));
        method.maxStack = Math.max(method.maxStack, 1);
        System.out.println("Instrumented " + target + " active-work scope: returns=" + returns);
    }

    private static MethodInsnNode browserWorldgenBeginServerWorkTurn() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "beginServerWorkTurn",
                "()V",
                false);
    }

    private static MethodInsnNode browserServerTickTelemetryBegin() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "beginServerTickTelemetry",
                "()V",
                false);
    }

    private static MethodInsnNode browserServerTickTelemetryEnd() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "endServerTickTelemetry",
                "()V",
                false);
    }

    private static MethodInsnNode browserServerWaitTelemetryBegin() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "beginServerWaitTelemetry",
                "()V",
                false);
    }

    private static MethodInsnNode browserServerWaitTelemetryEnd() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserWorldgenScheduler",
                "endServerWaitTelemetry",
                "()V",
                false);
    }

    public static void patchMinecraftServer(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/MinecraftServer.class");
        String owner = "net/minecraft/server/MinecraftServer";
        boolean patchedPrepareLevels = false;
        boolean patchedInitialSpawn = false;
        boolean patchedRunServerReady = false;
        boolean patchedRunServerTickYield = false;
        boolean patchedRunServerTickTelemetry = false;
        boolean patchedRunServerWaitTelemetry = false;
        boolean patchedRunServerStopDiagnostics = false;
        boolean patchedRunServerBrowserCatchupReset = false;
        boolean patchedRunServerStoppedSignal = false;
        boolean patchedSpinRegistration = false;
        boolean patchedSaveBeforeWorldInitialization = false;
        boolean patchedStructurePreload = false;
        boolean patchedBrowserPacketPump = false;
        boolean patchedBrowserWaitingPacketPump = false;
        boolean patchedBrowserMetricsRecorder = false;
        boolean patchedCooperativeServerExecutor = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (!(instruction instanceof MethodInsnNode call)
                            || call.getOpcode() != Opcodes.INVOKESTATIC
                            || !call.owner.equals("net/minecraft/util/Util")
                            || !call.name.equals("backgroundExecutor")
                            || !call.desc.equals("()Lnet/minecraft/TracingExecutor;")) {
                        continue;
                    }
                    AbstractInsnNode next = nextOpcode(call);
                    if (!(next instanceof FieldInsnNode field)
                            || field.getOpcode() != Opcodes.PUTFIELD
                            || !field.owner.equals(owner)
                            || !field.name.equals("executor")
                            || !field.desc.equals("Ljava/util/concurrent/Executor;")) {
                        throw new IllegalStateException(
                                "MinecraftServer background executor assignment shape changed");
                    }
                    method.instructions.insert(call, new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "dev/gaius/browser/BrowserCooperativeExecutor",
                            "defer",
                            "(Ljava/util/concurrent/Executor;)Ljava/util/concurrent/Executor;",
                            false));
                    method.maxStack = Math.max(method.maxStack, 1);
                    if (patchedCooperativeServerExecutor) {
                        throw new IllegalStateException(
                                "Multiple MinecraftServer cooperative executor patch points found");
                    }
                    patchedCooperativeServerExecutor = true;
                }
            } else if (method.name.equals("spin")
                    && method.desc.equals("(Ljava/util/function/Function;)"
                            + "Lnet/minecraft/server/MinecraftServer;")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && call.owner.equals("java/util/concurrent/atomic/AtomicReference")
                            && call.name.equals("set")
                            && call.desc.equals("(Ljava/lang/Object;)V")) {
                        InsnList registration = new InsnList();
                        registration.add(new VarInsnNode(Opcodes.ALOAD, 3));
                        registration.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "dev/gaius/browser/BrowserIntegratedServerMain",
                                "registerServer",
                                "(Lnet/minecraft/server/MinecraftServer;)V",
                                false));
                        method.instructions.insert(call, registration);
                        method.maxStack = Math.max(method.maxStack, 2);
                        patchedSpinRegistration = true;
                        break;
                    }
                }
            } else if (method.name.equals("dumpThreads")
                    && method.desc.equals("(Ljava/nio/file/Path;)V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 2);
            } else if (method.name.equals("saveAllChunks")
                    && method.desc.equals("(ZZZ)Z")) {
                LabelNode worldInitialized = new LabelNode();
                InsnList guard = new InsnList();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
                guard.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        owner,
                        "overworld",
                        "()Lnet/minecraft/server/level/ServerLevel;",
                        false));
                guard.add(new JumpInsnNode(Opcodes.IFNONNULL, worldInitialized));
                guard.add(new InsnNode(Opcodes.ICONST_0));
                guard.add(new InsnNode(Opcodes.IRETURN));
                guard.add(worldInitialized);
                method.instructions.insert(guard);
                method.maxStack = Math.max(method.maxStack, 1);
                patchedSaveBeforeWorldInitialization = true;
            } else if (method.name.equals("loadLevel") && method.desc.equals("()V")) {
                InsnList preload = new InsnList();
                preload.add(new VarInsnNode(Opcodes.ALOAD, 0));
                preload.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserStructurePreloader",
                        "preload",
                        "(Lnet/minecraft/server/MinecraftServer;)V",
                        false));
                method.instructions.insert(preload);
                method.maxStack = Math.max(method.maxStack, 1);
                patchedStructurePreload = true;
            } else if (method.name.equals("prepareLevels") && method.desc.equals("()V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "levelLoadListener",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener;"));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                        "LOAD_INITIAL_CHUNKS",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "net/minecraft/server/level/progress/LevelLoadListener",
                        "start",
                        "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;I)V",
                        true));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "levelLoadListener",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener;"));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                        "LOAD_INITIAL_CHUNKS",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "net/minecraft/server/level/progress/LevelLoadListener",
                        "finish",
                        "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;)V",
                        true));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "levelLoadListener",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener;"));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                        "LOAD_PLAYER_CHUNKS",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "net/minecraft/server/level/progress/LevelLoadListener",
                        "start",
                        "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;I)V",
                        true));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "levelLoadListener",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener;"));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                        "LOAD_PLAYER_CHUNKS",
                        "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "net/minecraft/server/level/progress/LevelLoadListener",
                        "finish",
                        "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;)V",
                        true));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        owner,
                        "updateMobSpawningFlags",
                        "()V",
                        false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        owner,
                        "updateEffectiveRespawnData",
                        "()V",
                        false));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 3, 1);
                patchedPrepareLevels = true;
            } else if (method.name.equals("setInitialSpawn")
                    && method.desc.equals("(Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/world/level/storage/ServerLevelData;"
                            + "ZZLnet/minecraft/server/level/progress/LevelLoadListener;)V")) {
                replaceInitialSpawnForBrowser(method);
                patchedInitialSpawn = true;
            } else if (method.name.equals("processPacketsAndTick")
                    && method.desc.equals("(Z)V")) {
                InsnList browserPackets = new InsnList();
                browserPackets.add(pumpBrowserChannels());
                browserPackets.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserIntegratedServerMain",
                        "tickIntegratedServerDistances",
                        "()V",
                        false));
                method.instructions.insert(browserPackets);
                method.maxStack = Math.max(method.maxStack, 1);
                patchedBrowserPacketPump = true;
            } else if (method.name.equals("pollTask") && method.desc.equals("()Z")) {
                InsnList taskEntry = new InsnList();
                taskEntry.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserIntegratedServerMain",
                        "pumpUrgentPacketsIfPending",
                        "()V",
                        false));
                instrumentBrowserTaskScope(
                        method,
                        "MinecraftServer.pollTask",
                        Opcodes.IRETURN,
                        taskEntry);
                patchedBrowserWaitingPacketPump = true;
            } else if (method.name.equals("createProfiler")
                    && method.desc.equals("()Lnet/minecraft/util/profiling/ProfilerFiller;")) {
                // ServerMetricsSamplersProvider probes OSHI/JNA for desktop CPU and hardware
                // metrics. It is neither available nor useful in an in-browser integrated server.
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/util/profiling/metrics/profiling/InactiveMetricsRecorder",
                        "INSTANCE",
                        "Lnet/minecraft/util/profiling/metrics/profiling/MetricsRecorder;"));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        owner,
                        "metricsRecorder",
                        "Lnet/minecraft/util/profiling/metrics/profiling/MetricsRecorder;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "metricsRecorder",
                        "Lnet/minecraft/util/profiling/metrics/profiling/MetricsRecorder;"));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "net/minecraft/util/profiling/metrics/profiling/MetricsRecorder",
                        "getProfiler",
                        "()Lnet/minecraft/util/profiling/ProfilerFiller;",
                        true));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, 1);
                patchedBrowserMetricsRecorder = true;
            } else if (method.name.equals("runServer") && method.desc.equals("()V")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction.getOpcode() == Opcodes.IFEQ) {
                        InsnList code = new InsnList();
                        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        code.add(new InsnNode(Opcodes.ICONST_1));
                        code.add(new FieldInsnNode(
                                Opcodes.PUTFIELD,
                                owner,
                                "isReady",
                                "Z"));
                        method.instructions.insert(instruction, code);
                        method.maxStack = Math.max(method.maxStack, 2);
                        patchedRunServerReady = true;
                        break;
                    }
                }
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && call.owner.equals(owner)
                            && call.name.equals("processPacketsAndTick")
                            && call.desc.equals("(Z)V")) {
                        // The server's waitUntilNextTick runs after processPacketsAndTick.
                        // Start the scheduler clock immediately before active tick work, then
                        // checkpoint after that work. This excludes the inter-tick idle gap
                        // without resetting the cumulative budget between chunk tasks.
                        method.instructions.insertBefore(instruction, browserServerTickTelemetryBegin());
                        method.instructions.insertBefore(instruction, browserWorldgenBeginServerWorkTurn());
                        method.instructions.insert(instruction, browserServerTickTelemetryEnd());
                        method.instructions.insert(instruction, browserWorldgenCheckpoint());
                        patchedRunServerTickYield = true;
                        patchedRunServerTickTelemetry = true;
                        break;
                    }
                }
                int waitUntilNextTickCalls = 0;
                for (var instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode call)
                            || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                            || !call.owner.equals(owner)
                            || !call.name.equals("waitUntilNextTick")
                            || !call.desc.equals("()V")) {
                        continue;
                    }
                    method.instructions.insertBefore(call, browserServerWaitTelemetryBegin());
                    method.instructions.insert(call, browserServerWaitTelemetryEnd());
                    waitUntilNextTickCalls++;
                }
                if (waitUntilNextTickCalls != 1) {
                    throw new IllegalStateException(
                            "MinecraftServer runServer waitUntilNextTick shape changed: "
                                    + waitUntilNextTickCalls);
                }
                patchedRunServerWaitTelemetry = true;
                patchedRunServerStopDiagnostics = hookMinecraftServerStopDiagnostics(method);
                patchedRunServerBrowserCatchupReset = patchMinecraftServerBrowserCatchupReset(method, owner);
                int stoppedSignals = 0;
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction.getOpcode() != Opcodes.RETURN) {
                        continue;
                    }
                    InsnList stopped = new InsnList();
                    stopped.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    stopped.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "dev/gaius/browser/BrowserIntegratedServerMain",
                            "markIntegratedServerStopped",
                            "(Lnet/minecraft/server/MinecraftServer;)V",
                            false));
                    method.instructions.insertBefore(instruction, stopped);
                    stoppedSignals++;
                }
                if (stoppedSignals != 1) {
                    throw new IllegalStateException(
                            "MinecraftServer runServer exit shape changed: " + stoppedSignals);
                }
                method.maxStack = Math.max(method.maxStack, 1);
                patchedRunServerStoppedSignal = true;
            }
        }
        if (!patchedPrepareLevels
                || !patchedInitialSpawn
                || !patchedRunServerReady
                || !patchedRunServerTickYield
                || !patchedRunServerTickTelemetry
                || !patchedRunServerWaitTelemetry
                || !patchedRunServerStopDiagnostics
                || !patchedRunServerBrowserCatchupReset
                || !patchedRunServerStoppedSignal
                || !patchedSpinRegistration
                || !patchedSaveBeforeWorldInitialization
                || !patchedStructurePreload
                || !patchedBrowserPacketPump
                || !patchedBrowserWaitingPacketPump
                || !patchedBrowserMetricsRecorder
                || !patchedCooperativeServerExecutor) {
            throw new IllegalStateException("MinecraftServer browser patch points were not found");
        }
        writeComputeFrames(node, output);
    }

    /**
     * Keeps the vanilla Mob.serverAiStep ordering intact while making each
     * expensive AI stage yieldable on the browser Worker.  Vanilla calls the
     * two selector variants in mutually exclusive branches, so there are seven
     * stable stage boundaries in the method: sensing, four selector calls,
     * navigation, and custom mob AI.  The hook is deliberately inserted after
     * each call; no goal is skipped, reordered, or executed on another thread.
     */
    public static void patchMobBrowserAiCooperation(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/entity/Mob";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "serverAiStep", "()V");
        int checkpoints = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                continue;
            }
            boolean stage = (call.owner.equals(
                    "net/minecraft/world/entity/ai/sensing/Sensing")
                    && call.name.equals("tick")
                    && call.desc.equals("()V"))
                    || (call.owner.equals("net/minecraft/world/entity/ai/goal/GoalSelector")
                    && ((call.name.equals("tick") && call.desc.equals("()V"))
                    || (call.name.equals("tickRunningGoals")
                    && call.desc.equals("(Z)V"))))
                    || (call.owner.equals(
                    "net/minecraft/world/entity/ai/navigation/PathNavigation")
                    && call.name.equals("tick")
                    && call.desc.equals("()V"))
                    || (call.owner.equals(owner)
                    && call.name.equals("customServerAiStep")
                    && call.desc.equals(
                    "(Lnet/minecraft/server/level/ServerLevel;)V"));
            if (stage) {
                method.instructions.insert(call, browserWorldgenMobAiPulse());
                checkpoints++;
            }
        }
        if (checkpoints != 7) {
            throw new IllegalStateException(
                    "Mob.serverAiStep AI stage shape changed: " + checkpoints
                            + " (expected 7 checkpoints)");
        }
        // TeaVM can legitimately prove the protected serverAiStep body
        // unreachable in a server-only entrypoint even though the class is
        // present in the shared client-named jar.  Keep one explicit
        // boundary at Mob.aiStep as well: this is the method reached from
        // LivingEntity.tick for every live mob, and it gives the Worker a
        // cooperative turn boundary even when the optimizer folds the
        // inner serverAiStep method.  The two calls sit immediately around
        // the vanilla super call, so no AI stage is reordered or skipped.
        MethodNode aiStep = find(node, "aiStep", "()V");
        MethodInsnNode livingAiStep = null;
        for (AbstractInsnNode instruction : aiStep.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("net/minecraft/world/entity/LivingEntity")
                    && call.name.equals("aiStep")
                    && call.desc.equals("()V")) {
                if (livingAiStep != null) {
                    throw new IllegalStateException(
                            "Mob.aiStep super-call shape changed: multiple LivingEntity.aiStep calls");
                }
                livingAiStep = call;
            }
        }
        if (livingAiStep == null) {
            throw new IllegalStateException(
                    "Mob.aiStep super-call shape changed: LivingEntity.aiStep not found");
        }
        aiStep.instructions.insertBefore(livingAiStep, browserWorldgenMobAiPulse());
        aiStep.instructions.insert(livingAiStep, browserWorldgenMobAiPulse());

        // Mob.aiStep and Mob.tick overrides can both be folded out of a
        // server-only TeaVM entry point.  LivingEntity.tick is the common
        // live-entity boundary, so use a typed fallback there and keep the
        // Mob-specific hook out of the hot override.  The guard in
        // mobEntityPulse() excludes players and other non-Mob entities.
        String livingOwner = "net/minecraft/world/entity/LivingEntity";
        ClassNode livingNode = read(jar, livingOwner + ".class");
        MethodNode livingTickMethod = find(livingNode, "tick", "()V");
        MethodInsnNode entityTick = null;
        for (AbstractInsnNode instruction : livingTickMethod.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("net/minecraft/world/entity/Entity")
                    && call.name.equals("tick")
                    && call.desc.equals("()V")) {
                if (entityTick != null) {
                    throw new IllegalStateException(
                            "LivingEntity.tick super-call shape changed: multiple Entity.tick calls");
                }
                entityTick = call;
            }
        }
        if (entityTick == null) {
            throw new IllegalStateException(
                    "LivingEntity.tick super-call shape changed: Entity.tick not found");
        }
        InsnList entity = new InsnList();
        entity.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entity.add(browserWorldgenMobEntityPulse());
        livingTickMethod.instructions.insert(entityTick, entity);
        writeComputeFrames(livingNode, output.resolveSibling("LivingEntity.class"));
        writeComputeFrames(node, output);
        System.out.println(
                "Instrumented Mob.serverAiStep browser AI checkpoints=" + checkpoints
                        + ", Mob.aiStep boundaries=2, LivingEntity.tick Mob fallback=1");
    }

    public static void patchPlayerSpawnFinderBrowser(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/level/PlayerSpawnFinder";
        ClassNode node = read(jar, owner + ".class");
        MethodNode findSpawn = find(node, "findSpawn",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)"
                        + "Ljava/util/concurrent/CompletableFuture;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/world/phys/Vec3",
                "atBottomCenterOf",
                "(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/concurrent/CompletableFuture",
                "completedFuture",
                "(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(findSpawn, code, 2, 2);

        MethodNode fixupLoadedSpawn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "gaius$fixupLoadedSpawn",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;)"
                        + "Lnet/minecraft/world/phys/Vec3;",
                null,
                null);
        LabelNode heightmapFallback = new LabelNode();
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/core/BlockPos",
                "containing",
                "(Lnet/minecraft/core/Position;)Lnet/minecraft/core/BlockPos;",
                false));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixupLoadedSpawn.instructions.add(new TypeInsnNode(
                Opcodes.NEW,
                "net/minecraft/world/level/ChunkPos"));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.DUP));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/Vec3i",
                "getX",
                "()I",
                false));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ICONST_4));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ISHR));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/Vec3i",
                "getZ",
                "()I",
                false));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ICONST_4));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ISHR));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/world/level/ChunkPos",
                "<init>",
                "(II)V",
                false));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                "getSpawnPosInChunk",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)"
                        + "Lnet/minecraft/core/BlockPos;",
                false));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.DUP));
        fixupLoadedSpawn.instructions.add(new JumpInsnNode(
                Opcodes.IFNULL,
                heightmapFallback));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/world/phys/Vec3",
                "atBottomCenterOf",
                "(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;",
                false));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ARETURN));
        fixupLoadedSpawn.instructions.add(heightmapFallback);
        // The class is written without frame computation: the branch target needs its own frame
        // (the JVM verifier of the patcher smoke's fixture checks it; TeaVM ignores frames).
        fixupLoadedSpawn.instructions.add(new FrameNode(
                Opcodes.F_FULL,
                3,
                new Object[] {
                    "net/minecraft/server/level/ServerLevel",
                    "net/minecraft/world/phys/Vec3",
                    "net/minecraft/core/BlockPos"
                },
                1,
                new Object[] {"net/minecraft/core/BlockPos"}));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.POP));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixupLoadedSpawn.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/levelgen/Heightmap$Types",
                "MOTION_BLOCKING_NO_LEAVES",
                "Lnet/minecraft/world/level/levelgen/Heightmap$Types;"));
        fixupLoadedSpawn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/core/BlockPos",
                "containing",
                "(Lnet/minecraft/core/Position;)Lnet/minecraft/core/BlockPos;",
                false));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "getHeightmapPos",
                "(Lnet/minecraft/world/level/levelgen/Heightmap$Types;"
                        + "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;",
                false));
        fixupLoadedSpawn.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/world/phys/Vec3",
                "atBottomCenterOf",
                "(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;",
                false));
        fixupLoadedSpawn.instructions.add(new InsnNode(Opcodes.ARETURN));
        // level, the new ChunkPos twice, x >> 4 and z's two shift operands.
        fixupLoadedSpawn.maxStack = 6;
        fixupLoadedSpawn.maxLocals = 3;
        node.methods.add(fixupLoadedSpawn);
        write(node, output);
    }

    public static void patchPrepareSpawnTaskBrowser(String jar, Path root)
            throws IOException {
        String chunkCacheOwner = "net/minecraft/server/level/ServerChunkCache";
        ClassNode chunkCache = read(jar, chunkCacheOwner + ".class");
        MethodNode getChunkFutureMainThread = find(
                chunkCache,
                "getChunkFutureMainThread",
                "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                        + "Ljava/util/concurrent/CompletableFuture;");
        if ((getChunkFutureMainThread.access & Opcodes.ACC_PRIVATE) == 0) {
            throw new IllegalStateException(
                    "ServerChunkCache.getChunkFutureMainThread access changed");
        }
        getChunkFutureMainThread.access =
                (getChunkFutureMainThread.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC;
        write(chunkCache, root.resolve(chunkCacheOwner + ".class"));

        String preparingOwner = "net/minecraft/server/network/config/PrepareSpawnTask$Preparing";
        ClassNode preparing = read(jar, preparingOwner + ".class");
        MethodNode loadSpawnChunks = find(preparing, "lambda$tick$0",
                "(Lnet/minecraft/world/level/ChunkPos;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                preparingOwner,
                "spawnLevel",
                "Lnet/minecraft/server/level/ServerLevel;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "getChunkSource",
                "()Lnet/minecraft/server/level/ServerChunkCache;",
                false));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/server/level/TicketType",
                "PLAYER_SPAWN",
                "Lnet/minecraft/server/level/TicketType;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        // WasmGC keeps vanilla's radius-three spawn ticket because it has no
        // JavaScript-only entity gate. The JavaScript target uses the smaller
        // radius-one path and its explicit entity gate below.
        code.add(new InsnNode(BuildTarget.wasmGc() ? Opcodes.ICONST_3 : Opcodes.ICONST_1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerChunkCache",
                "addTicketWithRadius",
                "(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/world/level/ChunkPos",
                "x",
                "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/world/level/ChunkPos",
                "z",
                "I"));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/chunk/status/ChunkStatus",
                "FULL",
                "Lnet/minecraft/world/level/chunk/status/ChunkStatus;"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerChunkCache",
                "getChunkFutureMainThread",
                "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                        + "Ljava/util/concurrent/CompletableFuture;",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                preparingOwner,
                "chunkLoadFuture",
                "Ljava/util/concurrent/CompletableFuture;"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(loadSpawnChunks, code, 6, 2);

        // The fixup below stands in for the spawn search that patchPlayerSpawnFinderBrowser
        // takes out of PlayerSpawnFinder.findSpawn, so it applies only where vanilla searches:
        // a player without a saved position. A returning player's saved position is used as
        // stored, as vanilla does. PrepareSpawnTask.start records which case this is.
        String spawnSearchedField = "gaius$spawnSearched";
        preparing.fields.add(new FieldNode(
                Opcodes.ACC_SYNTHETIC, spawnSearchedField, "Z", null, null));
        markSpawnSearched(jar, root, preparingOwner, spawnSearchedField);

        MethodNode prepareTick = find(preparing, "tick",
                "()Lnet/minecraft/server/network/config/PrepareSpawnTask$Ready;");
        int loadedSpawnFixups = 0;
        for (var instruction = prepareTick.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !allocation.desc.equals(
                            "net/minecraft/server/network/config/PrepareSpawnTask$Ready")) {
                continue;
            }
            LabelNode savedPosition = new LabelNode();
            InsnList fixup = new InsnList();
            fixup.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fixup.add(new FieldInsnNode(
                    Opcodes.GETFIELD, preparingOwner, spawnSearchedField, "Z"));
            fixup.add(new JumpInsnNode(Opcodes.IFEQ, savedPosition));
            fixup.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fixup.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    preparingOwner,
                    "spawnLevel",
                    "Lnet/minecraft/server/level/ServerLevel;"));
            fixup.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fixup.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "net/minecraft/server/level/PlayerSpawnFinder",
                    "gaius$fixupLoadedSpawn",
                    "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;)"
                            + "Lnet/minecraft/world/phys/Vec3;",
                    false));
            fixup.add(new VarInsnNode(Opcodes.ASTORE, 1));
            fixup.add(savedPosition);
            prepareTick.instructions.insertBefore(instruction, fixup);
            loadedSpawnFixups++;
        }
        if (loadedSpawnFixups != 1) {
            throw new IllegalStateException(
                    "PrepareSpawnTask loaded-spawn fixup point changed: "
                            + loadedSpawnFixups);
        }
        prepareTick.maxStack = Math.max(prepareTick.maxStack, 2);
        // The entity gate is the JavaScript radius-one workaround. WasmGC keeps vanilla's
        // radius-three PrepareSpawnTask completion and lets Ready.spawn wait cooperatively.
        if (!BuildTarget.wasmGc()) {
            addPrepareSpawnEntityGate(jar, root, preparing, prepareTick);
        }
        // The fixup guard and the entity gate add branch targets: rebuild the stack map frames.
        writeComputeFrames(preparing, root.resolve(preparingOwner + ".class"));

        String readyOwner = "net/minecraft/server/network/config/PrepareSpawnTask$Ready";
        ClassNode ready = read(jar, readyOwner + ".class");
        int readyRadius = BuildTarget.wasmGc() ? 3 : 1;
        replaceIntArgumentBeforeCall(
                find(ready, "keepAlive", "()V"),
                "net/minecraft/server/level/ServerChunkCache",
                "addTicketWithRadius",
                3,
                readyRadius);
        replaceIntArgumentBeforeCall(
                find(ready, "spawn", "(Lnet/minecraft/network/Connection;"
                        + "Lnet/minecraft/server/network/CommonListenerCookie;)"
                        + "Lnet/minecraft/server/level/ServerPlayer;"),
                "net/minecraft/server/level/ServerLevel", "waitForEntities", 3,
                BuildTarget.wasmGc() ? 3 : 0);
        write(ready, root.resolve(readyOwner + ".class"));
    }

    /**
     * {@code ServerChunkCache.getChunk} called off the server thread hands the lookup to the
     * server thread and blocks on it: {@code CompletableFuture.supplyAsync(() -> getChunk(..),
     * mainThreadProcessor).join()}. World generation reaches that branch on the Worker's
     * generation threads, for example when a structure places an entity whose variant spawn
     * condition (StructureCheck) asks the ServerLevel's StructureManager about its position.
     * TeaVM's browser CompletableFuture.join cannot block and throws on the incomplete future,
     * which failed the chunk with "Exception generating new chunk" and ended the server Worker
     * (JavaScript) or its generation (WebAssembly GC). The join becomes
     * BrowserFuturePump.joinFromWorker, which yields the generation thread until the server
     * thread has run the lookup, as the blocked JVM thread waits for it.
     */
    public static void patchServerChunkCacheBrowserOffThreadWait(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/level/ServerChunkCache";
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        MethodNode getChunk = find(node, "getChunk",
                "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;");
        int waits = 0;
        for (AbstractInsnNode instruction : getChunk.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode join
                    && join.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && join.owner.equals("java/util/concurrent/CompletableFuture")
                    && join.name.equals("join")
                    && join.desc.equals("()Ljava/lang/Object;")
                    && previousOpcode(join) instanceof MethodInsnNode supply
                    && supply.getOpcode() == Opcodes.INVOKESTATIC
                    && supply.owner.equals("java/util/concurrent/CompletableFuture")
                    && supply.name.equals("supplyAsync")
                    && supply.desc.equals("(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)"
                            + "Ljava/util/concurrent/CompletableFuture;")) {
                getChunk.instructions.set(join, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserFuturePump",
                        "joinFromWorker",
                        "(Ljava/util/concurrent/CompletableFuture;)Ljava/lang/Object;",
                        false));
                waits++;
            }
        }
        if (waits != 1) {
            throw new IllegalStateException(
                    "ServerChunkCache.getChunk off-thread wait shape changed: " + waits);
        }
        // Same operand stack and no new branch: the frames and max stack stay valid.
        write(node, output);
    }

    /**
     * Sets {@code Preparing.<field>} in {@code PrepareSpawnTask.start} right after the task
     * enters Preparing: true when the player has no saved position, which is when vanilla
     * start() takes the spawn from {@code PlayerSpawnFinder.findSpawn} instead of the stored
     * position ({@code SavedPosition.position().map(CompletableFuture::completedFuture)
     * .orElseGet(findSpawn)}).
     */
    private static void markSpawnSearched(
            String jar, Path root, String preparingOwner, String field) throws IOException {
        String taskOwner = "net/minecraft/server/network/config/PrepareSpawnTask";
        String savedPositionOwner = "net/minecraft/server/level/ServerPlayer$SavedPosition";
        String stateDescriptor = "Lnet/minecraft/server/network/config/PrepareSpawnTask$State;";
        ClassNode task = read(jar, taskOwner + ".class");
        MethodNode start = find(task, "start", "(Ljava/util/function/Consumer;)V");
        int savedPositionLocal = -1;
        int savedPositionStores = 0;
        FieldInsnNode enterPreparing = null;
        int preparingStores = 0;
        for (AbstractInsnNode instruction : start.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode cast
                    && cast.getOpcode() == Opcodes.CHECKCAST
                    && cast.desc.equals(savedPositionOwner)
                    && nextOpcode(cast) instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ASTORE) {
                savedPositionLocal = store.var;
                savedPositionStores++;
            }
            if (instruction instanceof FieldInsnNode put
                    && put.getOpcode() == Opcodes.PUTFIELD
                    && put.owner.equals(taskOwner)
                    && put.name.equals("state")
                    && put.desc.equals(stateDescriptor)
                    && previousOpcode(put) instanceof MethodInsnNode init
                    && init.getOpcode() == Opcodes.INVOKESPECIAL
                    && init.owner.equals(preparingOwner)
                    && init.name.equals("<init>")) {
                enterPreparing = put;
                preparingStores++;
            }
        }
        if (savedPositionStores != 1 || preparingStores != 1) {
            throw new IllegalStateException("PrepareSpawnTask.start shape changed: SavedPosition stores="
                    + savedPositionStores + ", Preparing states=" + preparingStores);
        }
        InsnList mark = new InsnList();
        mark.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mark.add(new FieldInsnNode(Opcodes.GETFIELD, taskOwner, "state", stateDescriptor));
        mark.add(new TypeInsnNode(Opcodes.CHECKCAST, preparingOwner));
        mark.add(new VarInsnNode(Opcodes.ALOAD, savedPositionLocal));
        mark.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, savedPositionOwner, "position",
                "()Ljava/util/Optional;", false));
        mark.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
        mark.add(new InsnNode(Opcodes.ICONST_1));
        mark.add(new InsnNode(Opcodes.IXOR));
        mark.add(new FieldInsnNode(Opcodes.PUTFIELD, preparingOwner, field, "Z"));
        start.instructions.insert(enterPreparing, mark);
        start.maxStack = Math.max(start.maxStack, 3);
        // No new branch: the existing stack map frames stay valid.
        write(task, root.resolve(taskOwner + ".class"));
    }

    /**
     * Keeps PrepareSpawnTask in Preparing until the spawn chunk's entities are loaded.
     * Entities load only once the centre chunk is accessible, which
     * ChunkMap.prepareAccessibleChunk ties to its radius-1 neighbours; turning Ready at FULL
     * left that wait to Ready.spawn's waitForEntities, a managedBlock inside the
     * ServerboundFinishConfiguration handler. Waiting here spans ordinary server ticks, so
     * the handler's radius-0 wait passes on its first check. While waiting, the
     * PLAYER_SPAWN ticket is refreshed as Ready.keepAlive does: its 20-tick timeout runs once
     * the centre holder is ready for saving, which can precede the entity read.
     *
     * <p>The gate is live without level ticks: like waitForEntities' own predicate it drains
     * the entity manager's pending loads before checking, through a public ServerLevel
     * accessor added here. Vanilla IntegratedServer, reached when the Worker cannot start,
     * runs only tickConnection while its player list is empty, so its levels never tick
     * during the host's configuration. The gate is also active only on the Worker server;
     * elsewhere it passes at once and Ready.spawn keeps its handler wait. Returns true once the
     * gate is added; throws when this version lacks the entity or ChunkPos shape, which every
     * supported profile (1.21.11, 26.2, 26.3) has, instead of silently keeping the handler
     * wait.</p>
     */
    private static boolean addPrepareSpawnEntityGate(
            String jar, Path root, ClassNode preparing, MethodNode prepareTick)
            throws IOException {
        String chunkPosOwner = "net/minecraft/world/level/ChunkPos";
        String serverLevelOwner = "net/minecraft/server/level/ServerLevel";
        String entityManagerOwner =
                "net/minecraft/world/level/entity/PersistentEntitySectionManager";
        String entityManagerDescriptor = "L" + entityManagerOwner + ";";
        ClassNode chunkPos = read(jar, chunkPosOwner + ".class");
        ClassNode serverLevel = read(jar, serverLevelOwner + ".class");
        MethodNode processPendingLoads = findNullable(
                read(jar, entityManagerOwner + ".class"), "processPendingLoads", "()V");
        // 26.2 renamed ChunkPos.toLong() to pack(); both return the entity manager's key.
        String packChunkPos = findNullable(chunkPos, "pack", "()J") != null
                ? "pack"
                : findNullable(chunkPos, "toLong", "()J") != null ? "toLong" : null;
        if (packChunkPos == null
                || findNullable(serverLevel, "areEntitiesLoaded", "(J)Z") == null
                || serverLevel.fields.stream().noneMatch(field ->
                        field.name.equals("entityManager")
                                && field.desc.equals(entityManagerDescriptor)
                                && (field.access & Opcodes.ACC_STATIC) == 0)
                || processPendingLoads == null
                || (processPendingLoads.access & Opcodes.ACC_PUBLIC) == 0
                || (processPendingLoads.access & Opcodes.ACC_STATIC) != 0) {
            // Every supported profile (1.21.11, 26.2, 26.3) has this shape; a jar without it
            // must not silently fall back to the handler wait (gate G4).
            throw new IllegalStateException("PrepareSpawnTask entity gate shape changed: "
                    + "ChunkPos key=" + packChunkPos
                    + ", ServerLevel.areEntitiesLoaded(J)Z="
                    + (findNullable(serverLevel, "areEntitiesLoaded", "(J)Z") != null)
                    + ", PersistentEntitySectionManager.processPendingLoads="
                    + (processPendingLoads == null ? "missing"
                            : Integer.toHexString(processPendingLoads.access)));
        }
        if (findNullable(serverLevel, "gaius$processPendingEntityLoads", "()V") != null) {
            throw new IllegalStateException(
                    "ServerLevel.gaius$processPendingEntityLoads already exists");
        }

        LabelNode chunkLoaded = null;
        int gatePoints = 0;
        for (AbstractInsnNode instruction : prepareTick.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(preparing.name)
                    && field.name.equals("chunkLoadFuture")
                    && nextOpcode(field) instanceof MethodInsnNode isDone
                    && isDone.owner.equals("java/util/concurrent/CompletableFuture")
                    && isDone.name.equals("isDone")
                    && nextOpcode(isDone) instanceof JumpInsnNode loaded
                    && loaded.getOpcode() == Opcodes.IFNE) {
                chunkLoaded = loaded.label;
                gatePoints++;
            }
        }
        if (gatePoints != 1) {
            throw new IllegalStateException(
                    "PrepareSpawnTask entity gate point changed: " + gatePoints);
        }

        String entitiesLoadedDescriptor =
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;)Z";
        // Local 1 holds the joined spawn position on every path to this point. Stay in
        // Preparing, before LevelLoadListener.finish, until the gate reports loaded entities.
        LabelNode entitiesLoaded = new LabelNode();
        InsnList gate = new InsnList();
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                preparing.name,
                "spawnLevel",
                "Lnet/minecraft/server/level/ServerLevel;"));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 1));
        gate.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                preparing.name,
                "gaius$spawnEntitiesLoaded",
                entitiesLoadedDescriptor,
                false));
        gate.add(new JumpInsnNode(Opcodes.IFNE, entitiesLoaded));
        gate.add(new InsnNode(Opcodes.ACONST_NULL));
        gate.add(new InsnNode(Opcodes.ARETURN));
        gate.add(entitiesLoaded);
        prepareTick.instructions.insertBefore(nextOpcode(chunkLoaded), gate);

        MethodNode spawnEntitiesLoaded = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "gaius$spawnEntitiesLoaded",
                entitiesLoadedDescriptor,
                null,
                null);
        InsnList code = spawnEntitiesLoaded.instructions;
        LabelNode workerServer = new LabelNode();
        LabelNode waiting = new LabelNode();
        // Only the Worker server waits here. Any other server passes at once, so Preparing
        // turns Ready at FULL and Ready.spawn's waitForEntities keeps the entity wait.
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "isWorkerServer",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFNE, workerServer));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(workerServer);
        // Same chunk as Ready.spawn's waitForEntities: gaius$fixupLoadedSpawn only moves
        // the position within this chunk.
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/core/BlockPos",
                "containing",
                "(Lnet/minecraft/core/Position;)Lnet/minecraft/core/BlockPos;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/Vec3i",
                "getX",
                "()I",
                false));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new InsnNode(Opcodes.ISHR));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/Vec3i",
                "getZ",
                "()I",
                false));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new InsnNode(Opcodes.ISHR));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new TypeInsnNode(Opcodes.NEW, chunkPosOwner));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                chunkPosOwner,
                "<init>",
                "(II)V",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        // Drain finished entity reads first, as waitForEntities' predicate does. Only
        // ServerLevel.tick does this otherwise, and a paused server never ticks its levels.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                serverLevelOwner,
                "gaius$processPendingEntityLoads",
                "()V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                chunkPosOwner,
                packChunkPos,
                "()J",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                serverLevelOwner,
                "areEntitiesLoaded",
                "(J)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, waiting));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "markSpawnEntitiesLoaded",
                "(II)V",
                false));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(waiting);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                serverLevelOwner,
                "getChunkSource",
                "()Lnet/minecraft/server/level/ServerChunkCache;",
                false));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/server/level/TicketType",
                "PLAYER_SPAWN",
                "Lnet/minecraft/server/level/TicketType;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerChunkCache",
                "addTicketWithRadius",
                "(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V",
                false));
        // Counts waiting ticks and reports a long wait once, so a stalled join stays visible.
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "markSpawnEntitiesWaiting",
                "(II)V",
                false));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        spawnEntitiesLoaded.maxStack = 4;
        spawnEntitiesLoaded.maxLocals = 6;
        preparing.methods.add(spawnEntitiesLoaded);

        // ServerLevel.entityManager is private; this accessor is the only non-tick way to
        // run the processPendingLoads call that waitForEntities' predicate makes.
        MethodNode processPendingEntityLoads = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "gaius$processPendingEntityLoads",
                "()V",
                null,
                null);
        processPendingEntityLoads.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        processPendingEntityLoads.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                serverLevelOwner,
                "entityManager",
                entityManagerDescriptor));
        processPendingEntityLoads.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                entityManagerOwner,
                "processPendingLoads",
                "()V",
                false));
        processPendingEntityLoads.instructions.add(new InsnNode(Opcodes.RETURN));
        processPendingEntityLoads.maxStack = 1;
        processPendingEntityLoads.maxLocals = 1;
        serverLevel.methods.add(processPendingEntityLoads);
        write(serverLevel, root.resolve(serverLevelOwner + ".class"));
        return true;
    }

    private static void replaceIntArgumentBeforeCall(
            MethodNode method,
            String callOwner,
            String callName,
            int expected,
            int replacement) {
        int patches = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals(callOwner)
                    || !call.name.equals(callName)) {
                continue;
            }
            AbstractInsnNode argument = call.getPrevious();
            while (argument != null && (argument.getType() == AbstractInsnNode.LABEL
                    || argument.getType() == AbstractInsnNode.LINE
                    || argument.getType() == AbstractInsnNode.FRAME)) {
                argument = argument.getPrevious();
            }
            if (argument == null || argument.getOpcode() != Opcodes.ICONST_0 + expected) {
                throw new IllegalStateException(callOwner + "." + callName
                        + " browser radius argument changed");
            }
            method.instructions.set(argument, new InsnNode(Opcodes.ICONST_0 + replacement));
            patches++;
        }
        if (patches != 1) {
            throw new IllegalStateException(callOwner + "." + callName
                    + " browser radius patch point was not found");
        }
    }

    public static void patchServerPlayerGameModeBrowserWorker(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/level/ServerPlayerGameMode";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                "browserDestroyStartMillis",
                "J",
                null,
                null));
        MethodNode handle = find(
                node,
                "handleBlockBreakAction",
                "(Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;"
                        + "Lnet/minecraft/core/Direction;II)V");
        int startMarkers = 0;
        int stopAdjustments = 0;
        int stopCompletions = 0;
        for (var instruction = handle.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode field)) {
                continue;
            }
            if (field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(owner)
                    && field.name.equals("destroyProgressStart")
                    && field.desc.equals("I")) {
                InsnList markStart = new InsnList();
                markStart.add(new VarInsnNode(Opcodes.ALOAD, 0));
                markStart.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/System",
                        "currentTimeMillis",
                        "()J",
                        false));
                markStart.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        owner,
                        "browserDestroyStartMillis",
                        "J"));
                handle.instructions.insert(instruction, markStart);
                startMarkers++;
            } else if (field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(owner)
                    && field.name.equals("destroyProgressStart")
                    && field.desc.equals("I")) {
                AbstractInsnNode subtract = nextRealInstruction(instruction);
                AbstractInsnNode store = nextRealInstruction(subtract);
                if (subtract == null || subtract.getOpcode() != Opcodes.ISUB
                        || !(store instanceof VarInsnNode variable)
                        || store.getOpcode() != Opcodes.ISTORE) {
                    continue;
                }
                InsnList adjust = new InsnList();
                adjust.add(new VarInsnNode(Opcodes.ILOAD, variable.var));
                adjust.add(new VarInsnNode(Opcodes.ALOAD, 0));
                adjust.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        owner,
                        "browserDestroyStartMillis",
                        "J"));
                adjust.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserIntegratedServerMain",
                        "adjustDestroyTicks",
                        "(IJ)I",
                        false));
                adjust.add(new VarInsnNode(Opcodes.ISTORE, variable.var));
                handle.instructions.insert(store, adjust);
                stopAdjustments++;
            }
        }
        for (var instruction = handle.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/world/level/block/state/BlockState")
                    || !call.name.equals("getDestroyProgress")
                    || !call.desc.equals("(Lnet/minecraft/world/entity/player/Player;"
                            + "Lnet/minecraft/world/level/BlockGetter;"
                            + "Lnet/minecraft/core/BlockPos;)F")) {
                continue;
            }
            AbstractInsnNode loadTicks = nextRealInstruction(instruction);
            AbstractInsnNode addOne = nextRealInstruction(loadTicks);
            AbstractInsnNode add = nextRealInstruction(addOne);
            AbstractInsnNode toFloat = nextRealInstruction(add);
            AbstractInsnNode multiply = nextRealInstruction(toFloat);
            if (!(loadTicks instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ILOAD
                    || addOne == null
                    || addOne.getOpcode() != Opcodes.ICONST_1
                    || add == null
                    || add.getOpcode() != Opcodes.IADD
                    || toFloat == null
                    || toFloat.getOpcode() != Opcodes.I2F
                    || multiply == null
                    || multiply.getOpcode() != Opcodes.FMUL) {
                continue;
            }
            handle.instructions.insert(multiply, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "completeLocalDestroyProgress",
                    "(F)F",
                    false));
            stopCompletions++;
        }
        if (startMarkers != 1 || stopAdjustments != 1 || stopCompletions != 1) {
            throw new IllegalStateException(
                    "ServerPlayerGameMode wall-clock break patch points changed: "
                            + startMarkers + "/" + stopAdjustments + "/" + stopCompletions);
        }
        handle.maxStack = Math.max(handle.maxStack, 3);
        writeComputeFrames(node, output);
    }

    public static void patchServerCommonPacketListenerBrowserWorker(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/server/network/ServerCommonPacketListenerImpl";
        ClassNode node = read(jar, owner + ".class");
        MethodNode isSingleplayerOwner = find(node, "isSingleplayerOwner", "()Z");
        // In the Worker the owner is the player on the local startup channel; an Open to LAN
        // guest (relay-backed lan-server channel) is not. Vanilla's GameProfile comparison
        // cannot work there, but answering true for every connection made a guest's
        // disconnect halt the host's world.
        String connectionDesc = "Lnet/minecraft/network/Connection;";
        boolean hasConnectionField = node.fields.stream()
                .anyMatch(field -> field.name.equals("connection") && field.desc.equals(connectionDesc));
        if (!hasConnectionField) {
            throw new IllegalStateException(owner + ".connection (" + connectionDesc + ") not found");
        }
        LabelNode vanillaCheck = new LabelNode();
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "isWorkerServer",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, vanillaCheck));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "connection", connectionDesc));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "isWorkerOwnerConnection",
                "(" + connectionDesc + ")Z",
                false));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(vanillaCheck);
        isSingleplayerOwner.instructions.insert(code);
        isSingleplayerOwner.maxStack = Math.max(isSingleplayerOwner.maxStack, 1);
        writeComputeFrames(node, output);
    }

    public static void patchServerGamePacketListenerBrowserWorker(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/server/network/ServerGamePacketListenerImpl";
        ClassNode node = read(jar, owner + ".class");
        MethodNode chunkBatch = find(
                node,
                "handleChunkBatchReceived",
                "(Lnet/minecraft/network/protocol/game/ServerboundChunkBatchReceivedPacket;)V");
        int activations = 0;
        for (var instruction = chunkBatch.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList activate = new InsnList();
                activate.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserIntegratedServerMain",
                        "acknowledgeChunkBatch",
                        "()V",
                        false));
                chunkBatch.instructions.insertBefore(instruction, activate);
                activations++;
            }
        }
        if (activations != 1) {
            throw new IllegalStateException(
                    "ServerGamePacketListenerImpl chunk-batch acknowledgement point changed: "
                            + activations);
        }
        chunkBatch.maxStack = Math.max(chunkBatch.maxStack, 1);

        MethodNode movePlayer = find(
                node,
                "handleMovePlayer",
                "(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V");
        LabelNode vanillaMovement = new LabelNode();
        InsnList browserMovement = new InsnList();
        browserMovement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "isWorkerServer",
                "()Z",
                false));
        browserMovement.add(new JumpInsnNode(Opcodes.IFEQ, vanillaMovement));
        browserMovement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserMovement.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "player",
                "Lnet/minecraft/server/level/ServerPlayer;"));
        browserMovement.add(new VarInsnNode(Opcodes.ALOAD, 1));
        browserMovement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "applyWorkerMovement",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
                false));
        browserMovement.add(new InsnNode(Opcodes.RETURN));
        browserMovement.add(vanillaMovement);
        movePlayer.instructions.insert(browserMovement);
        // The Worker branch above consumes the packet through applyWorkerMovement and returns.
        // Do not put a worldgen-pressure return at the method head: InsnList.insert prepends the
        // gate before that branch, which silently drops movement packets while the client is
        // flying.  The resulting client/server position desync strands ChunkMap tracking at the
        // last accepted section and renders only sky.  Vanilla/non-Worker movement remains
        // untouched below the Worker fast path.
        //
        // Minecraft 26.3 moved the movement body, including both ServerChunkCache.move calls,
        // into handlePlayerPositionChange(DDDFFZZ)V; handleMovePlayer only validates the packet
        // and calls it (so does handleAcceptTeleportPacket). The Worker fast path above stays at
        // the head of handleMovePlayer; the chunk-tracking redirect follows the calls.
        MethodNode positionChange = findNullable(
                node, "handlePlayerPositionChange", "(DDDFFZZ)V");
        MethodNode moveTracking = positionChange != null ? positionChange : movePlayer;
        if (positionChange != null) {
            for (AbstractInsnNode instruction = movePlayer.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/server/level/ServerChunkCache")
                        && call.name.equals("move")) {
                    throw new IllegalStateException("ServerGamePacketListenerImpl.handleMovePlayer"
                            + " still moves chunk tracking next to handlePlayerPositionChange");
                }
            }
        }
        int moveCalls = 0;
        for (AbstractInsnNode instruction = moveTracking.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/server/level/ServerChunkCache")
                    || !call.name.equals("move")
                    || !call.desc.equals(
                            "(Lnet/minecraft/server/level/ServerPlayer;)V")) {
                continue;
            }
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = "dev/gaius/browser/BrowserIntegratedServerMain";
            call.name = "moveServerPlayerChunkTracking";
            call.desc = "(Lnet/minecraft/server/level/ServerChunkCache;"
                    + "Lnet/minecraft/server/level/ServerPlayer;)V";
            call.itf = false;
            moveCalls++;
        }
        if (moveCalls != 2) {
            throw new IllegalStateException(
                    "ServerGamePacketListenerImpl movement chunk-tracking points changed: "
                            + moveCalls);
        }
        movePlayer.maxStack = Math.max(movePlayer.maxStack, 2);
        moveTracking.maxStack = Math.max(moveTracking.maxStack, 2);
        writeComputeFrames(node, output);
    }

    private static void addChunkPosCoordinate(
            InsnList instructions, int local, String coordinate, boolean record) {
        instructions.add(new VarInsnNode(Opcodes.ALOAD, local));
        if (record) {
            instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/world/level/ChunkPos",
                    coordinate,
                    "()I",
                    false));
        } else {
            instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/world/level/ChunkPos",
                    coordinate,
                    "I"));
        }
    }

    public static void patchPlayerChunkSenderBrowserWorker(
            String jar, Path output, boolean chunkPosRecord)
            throws IOException {
        String owner = "net/minecraft/server/network/PlayerChunkSender";
        ClassNode node = read(jar, owner + ".class");
        MethodNode constructor = find(
                node,
                "<init>",
                "(Z)V");
        int maxUnacknowledgedAssignments = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.owner.equals(owner)
                    || !field.name.equals("maxUnacknowledgedBatches")
                    || !field.desc.equals("I")) {
                continue;
            }
            AbstractInsnNode value = previousOpcode(instruction);
            if (!(value instanceof InsnNode constant) || constant.getOpcode() != Opcodes.ICONST_1) {
                throw new IllegalStateException(
                        "PlayerChunkSender max-unacknowledged batch constant changed");
            }
            constructor.instructions.set(constant, new InsnNode(Opcodes.ICONST_4));
            maxUnacknowledgedAssignments++;
        }
        if (maxUnacknowledgedAssignments != 1) {
            throw new IllegalStateException(
                    "PlayerChunkSender max-unacknowledged batch assignment changed: "
                            + maxUnacknowledgedAssignments);
        }
        MethodNode sendNextChunks = find(
                node,
                "sendNextChunks",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        // Keep sender-side state visible in the browser Worker.  The normal packet counters
        // only show batch boundaries; they cannot distinguish an empty ready set from a
        // transport that dropped a selected chunk.  Use a fresh local for the player's chunk
        // position so this remains valid when Mojang changes the method's existing locals.
        int playerChunkLocal = sendNextChunks.maxLocals;
        sendNextChunks.maxLocals = playerChunkLocal + 1;
        InsnList senderEntry = new InsnList();
        senderEntry.add(new VarInsnNode(Opcodes.ALOAD, 1));
        senderEntry.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerPlayer",
                "chunkPosition",
                "()Lnet/minecraft/world/level/ChunkPos;",
                false));
        senderEntry.add(new VarInsnNode(Opcodes.ASTORE, playerChunkLocal));
        senderEntry.add(new InsnNode(Opcodes.ICONST_0));
        senderEntry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        senderEntry.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "pendingChunks",
                "Lit/unimi/dsi/fastutil/longs/LongSet;"));
        senderEntry.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/longs/LongSet",
                "size",
                "()I",
                true));
        senderEntry.add(new IntInsnNode(Opcodes.BIPUSH, -1));
        senderEntry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        senderEntry.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "unacknowledgedBatches",
                "I"));
        addChunkPosCoordinate(senderEntry, playerChunkLocal, "x", chunkPosRecord);
        addChunkPosCoordinate(senderEntry, playerChunkLocal, "z", chunkPosRecord);
        senderEntry.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "recordChunkSenderState",
                "(IIIIII)V",
                false));
        sendNextChunks.instructions.insert(senderEntry);
        int records = 0;
        AbstractInsnNode batchFinishedSendTarget = null;
        for (AbstractInsnNode instruction = sendNextChunks.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !allocation.desc.equals(
                            "net/minecraft/network/protocol/game/ClientboundChunkBatchFinishedPacket")) {
                continue;
            }
            AbstractInsnNode batchFinishedSend = instruction.getNext();
            while (batchFinishedSend != null
                    && (!(batchFinishedSend instanceof MethodInsnNode call)
                            || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                            || !call.owner.equals(
                                    "net/minecraft/server/network/ServerGamePacketListenerImpl")
                            || !call.name.equals("send")
                            || !call.desc.equals(
                                    "(Lnet/minecraft/network/protocol/Packet;)V"))) {
                batchFinishedSend = batchFinishedSend.getNext();
            }
            if (batchFinishedSend == null) {
                continue;
            }
            batchFinishedSendTarget = batchFinishedSend;
            InsnList record = new InsnList();
            record.add(new VarInsnNode(Opcodes.ALOAD, 5));
            record.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/List",
                    "size",
                    "()I",
                    true));
            record.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserIntegratedServerMain",
                    "recordChunkBatchSent",
                    "(I)V",
                    false));
            // Record before invoking send: the browser transport may synchronously re-enter
            // the ACK path while send() is dispatching the finished-batch packet.
            sendNextChunks.instructions.insertBefore(batchFinishedSend, record);
            records++;
        }
        if (records != 1) {
            throw new IllegalStateException(
                    "PlayerChunkSender chunk-batch size point changed: " + records);
        }
        // The list returned by collectChunksToSend contains only ready LevelChunk instances;
        // record it both before the send loop and immediately before the batch-finished packet.
        // This preserves vanilla selection/removal order while exposing pending/ready/ACK state.
        InsnList selectedState = new InsnList();
        selectedState.add(new InsnNode(Opcodes.ICONST_1));
        selectedState.add(new VarInsnNode(Opcodes.ALOAD, 0));
        selectedState.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "pendingChunks",
                "Lit/unimi/dsi/fastutil/longs/LongSet;"));
        selectedState.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/longs/LongSet",
                "size",
                "()I",
                true));
        selectedState.add(new VarInsnNode(Opcodes.ALOAD, 5));
        selectedState.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "size",
                "()I",
                true));
        selectedState.add(new VarInsnNode(Opcodes.ALOAD, 0));
        selectedState.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "unacknowledgedBatches",
                "I"));
        addChunkPosCoordinate(selectedState, playerChunkLocal, "x", chunkPosRecord);
        addChunkPosCoordinate(selectedState, playerChunkLocal, "z", chunkPosRecord);
        selectedState.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "recordChunkSenderState",
                "(IIIIII)V",
                false));
        AbstractInsnNode firstListSize = null;
        for (AbstractInsnNode instruction = sendNextChunks.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/List")
                    && call.name.equals("isEmpty")
                    && call.desc.equals("()Z")) {
                // The List.isEmpty() immediately follows the collectChunksToSend ASTORE 5.
                firstListSize = instruction;
                break;
            }
        }
        if (firstListSize == null) {
            throw new IllegalStateException(
                    "PlayerChunkSender ready-list accounting point changed");
        }
        sendNextChunks.instructions.insertBefore(firstListSize, selectedState);
        InsnList batchState = new InsnList();
        batchState.add(new InsnNode(Opcodes.ICONST_2));
        batchState.add(new VarInsnNode(Opcodes.ALOAD, 0));
        batchState.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "pendingChunks",
                "Lit/unimi/dsi/fastutil/longs/LongSet;"));
        batchState.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/longs/LongSet",
                "size",
                "()I",
                true));
        batchState.add(new VarInsnNode(Opcodes.ALOAD, 5));
        batchState.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "size",
                "()I",
                true));
        batchState.add(new VarInsnNode(Opcodes.ALOAD, 0));
        batchState.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "unacknowledgedBatches",
                "I"));
        addChunkPosCoordinate(batchState, playerChunkLocal, "x", chunkPosRecord);
        addChunkPosCoordinate(batchState, playerChunkLocal, "z", chunkPosRecord);
        batchState.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "recordChunkSenderState",
                "(IIIIII)V",
                false));
        if (batchFinishedSendTarget == null) {
            throw new IllegalStateException(
                    "PlayerChunkSender batch-finished send target changed");
        }
        sendNextChunks.instructions.insertBefore(batchFinishedSendTarget, batchState);
        sendNextChunks.maxStack = Math.max(sendNextChunks.maxStack, 3);
        writeComputeFrames(node, output);
    }

    private static void replaceInitialSpawnForBrowser(MethodNode method) {
        // 26.3 removed RandomState.sampler() and Climate$Sampler.findSpawnPosition(); vanilla
        // asks the generator for the spawn chunk (ChunkGenerator.getOrigin, NoiseSpawnFinder).
        boolean generatorOrigin = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/world/level/chunk/ChunkGenerator")
                    && call.name.equals("getOrigin")
                    && call.desc.equals("(Lnet/minecraft/world/level/levelgen/RandomState;)"
                            + "Lnet/minecraft/world/level/ChunkPos;")) {
                generatorOrigin = true;
            }
        }
        InsnList code = new InsnList();

        code.add(minecraftEvent("server.browserFastInitialSpawn"));

        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                "PREPARE_GLOBAL_SPAWN",
                "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/server/level/progress/LevelLoadListener",
                "start",
                "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;I)V",
                true));

        if (generatorOrigin) {
            // Same spawn chunk as vanilla 26.3: getGenerator().getOrigin(randomState()).
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/server/level/ServerLevel",
                    "getChunkSource",
                    "()Lnet/minecraft/server/level/ServerChunkCache;",
                    false));
            code.add(new VarInsnNode(Opcodes.ASTORE, 7));
            code.add(new VarInsnNode(Opcodes.ALOAD, 7));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/server/level/ServerChunkCache",
                    "getGenerator",
                    "()Lnet/minecraft/world/level/chunk/ChunkGenerator;",
                    false));
            code.add(new VarInsnNode(Opcodes.ALOAD, 7));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/server/level/ServerChunkCache",
                    "randomState",
                    "()Lnet/minecraft/world/level/levelgen/RandomState;",
                    false));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/world/level/chunk/ChunkGenerator",
                    "getOrigin",
                    "(Lnet/minecraft/world/level/levelgen/RandomState;)"
                            + "Lnet/minecraft/world/level/ChunkPos;",
                    false));
            code.add(new VarInsnNode(Opcodes.ASTORE, 5));
            appendBrowserInitialSpawnFromChunk(code);
            replace(method, code, 9, 8);
            verifyChunkPosIntConstructor(method, 0);
            return;
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "getChunkSource",
                "()Lnet/minecraft/server/level/ServerChunkCache;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerChunkCache",
                "randomState",
                "()Lnet/minecraft/world/level/levelgen/RandomState;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/levelgen/RandomState",
                "sampler",
                "()Lnet/minecraft/world/level/biome/Climate$Sampler;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/biome/Climate$Sampler",
                "findSpawnPosition",
                "()Lnet/minecraft/core/BlockPos;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 7));
        code.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/world/level/ChunkPos"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/Vec3i",
                "getX",
                "()I",
                false));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new InsnNode(Opcodes.ISHR));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/Vec3i",
                "getZ",
                "()I",
                false));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new InsnNode(Opcodes.ISHR));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/world/level/ChunkPos",
                "<init>",
                "(II)V",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));

        appendBrowserInitialSpawnFromChunk(code);
        replace(method, code, 9, 8);
        verifyChunkPosIntConstructor(method, 1);
    }

    /**
     * Shared tail of the browser setInitialSpawn: focus the load listener on the spawn chunk in
     * local 5, set the world spawn to the chunk's middle column at the top of the level (the
     * player spawn finder settles the height later) and finish the PREPARE_GLOBAL_SPAWN stage.
     */
    private static void appendBrowserInitialSpawnFromChunk(InsnList code) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "dimension",
                "()Lnet/minecraft/resources/ResourceKey;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/server/level/progress/LevelLoadListener",
                "updateFocus",
                "(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/ChunkPos;)V",
                true));

        code.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/core/BlockPos"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/ChunkPos",
                "getMiddleBlockX",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "getMaxY",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/ChunkPos",
                "getMiddleBlockZ",
                "()I",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/core/BlockPos",
                "<init>",
                "(III)V",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));

        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/level/ServerLevel",
                "dimension",
                "()Lnet/minecraft/resources/ResourceKey;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new InsnNode(Opcodes.FCONST_0));
        code.add(new InsnNode(Opcodes.FCONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/world/level/storage/LevelData$RespawnData",
                "of",
                "(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;FF)"
                        + "Lnet/minecraft/world/level/storage/LevelData$RespawnData;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/world/level/storage/ServerLevelData",
                "setSpawn",
                "(Lnet/minecraft/world/level/storage/LevelData$RespawnData;)V",
                true));

        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/server/level/progress/LevelLoadListener$Stage",
                "PREPARE_GLOBAL_SPAWN",
                "Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/server/level/progress/LevelLoadListener",
                "finish",
                "(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;)V",
                true));
        code.add(new InsnNode(Opcodes.RETURN));
    }

    private static void verifyChunkPosIntConstructor(
            MethodNode method, int expectedIntConstructors) {
        int intConstructors = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals("net/minecraft/world/level/ChunkPos")
                    || !call.name.equals("<init>")) {
                continue;
            }
            if (call.desc.equals("(Lnet/minecraft/core/BlockPos;)V")) {
                throw new IllegalStateException(
                        "Browser patch introduced removed ChunkPos(BlockPos) in "
                                + method.name + method.desc);
            }
            if (call.desc.equals("(II)V")) {
                intConstructors++;
            }
        }
        if (intConstructors != expectedIntConstructors) {
            throw new IllegalStateException(
                    "Expected " + expectedIntConstructors + " ChunkPos(int,int) calls in "
                            + method.name + method.desc + ", got " + intConstructors);
        }
    }

    private static boolean patchMinecraftServerBrowserCatchupReset(MethodNode method, String owner) {
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof LdcInsnNode constant)
                    || !"Can't keep up! Is the server overloaded? Running {}ms or {} ticks behind"
                            .equals(constant.cst)) {
                continue;
            }
            var loggerLoad = previousOpcode(instruction);
            if (!(loggerLoad instanceof FieldInsnNode logger)
                    || logger.getOpcode() != Opcodes.GETSTATIC
                    || !"Lorg/slf4j/Logger;".equals(logger.desc)) {
                continue;
            }
            AbstractInsnNode end = null;
            for (var candidate = instruction.getNext(); candidate != null; candidate = candidate.getNext()) {
                if (candidate instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(owner)
                        && field.name.equals("lastOverloadWarningNanos")
                        && field.desc.equals("J")) {
                    end = field;
                    break;
                }
            }
            if (end == null) {
                continue;
            }

            LabelNode afterVanillaCatchup = new LabelNode();
            method.instructions.insert(end, afterVanillaCatchup);

            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "net/minecraft/util/Util",
                    "getNanos",
                    "()J",
                    false));
            code.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "nextTickTimeNanos",
                    "J"));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "nextTickTimeNanos",
                    "J"));
            code.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "lastOverloadWarningNanos",
                    "J"));
            code.add(new JumpInsnNode(Opcodes.GOTO, afterVanillaCatchup));
            method.instructions.insertBefore(loggerLoad, code);
            method.maxStack = Math.max(method.maxStack, 4);
            return true;
        }
        return false;
    }

    private static boolean hookMinecraftServerStopDiagnostics(MethodNode method) {
        int hooked = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof LdcInsnNode constant)
                    || !"Exception stopping the server".equals(constant.cst)) {
                continue;
            }
            var throwableLoad = nextOpcode(instruction);
            if (!(throwableLoad instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD) {
                continue;
            }
            var loggerLoad = previousOpcode(instruction);
            if (!(loggerLoad instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETSTATIC
                    || !"Lorg/slf4j/Logger;".equals(field.desc)) {
                continue;
            }
            InsnList code = new InsnList();
            code.add(new LdcInsnNode("server.stop"));
            code.add(new VarInsnNode(Opcodes.ALOAD, load.var));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/opengl/BrowserOpenGL",
                    "reportMinecraftThrowable",
                    "(Ljava/lang/String;Ljava/lang/Throwable;)V",
                    false));
            method.instructions.insertBefore(loggerLoad, code);
            method.maxStack = Math.max(method.maxStack, 2);
            hooked++;
        }
        return hooked >= 1;
    }

    public static void patchChaseClient(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/chase/ChaseClient.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("start") || method.name.equals("stop")
                    || method.name.equals("run")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    public static void patchChaseServer(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/chase/ChaseServer.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("start") || method.name.equals("stop")
                    || method.name.equals("runSender") || method.name.equals("runAcceptor")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }
}
