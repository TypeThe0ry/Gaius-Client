package dev.gaius.tools.patches.telemetry;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
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
import org.objectweb.asm.tree.VarInsnNode;

/** Standalone JVM instrumentation; browser registration uses the identical hooks. */
public final class TickPacketParityPatches {
    private static final String RECORDER = "dev/gaius/parity/TickPacketRecorder";
    private static final String CONNECTION = "net/minecraft/network/Connection";
    private static final String PACKET = "Lnet/minecraft/network/protocol/Packet;";
    private static final String PROTOCOL = "Lnet/minecraft/network/ProtocolInfo;";
    private static final String SERVER = "net/minecraft/server/MinecraftServer";

    private TickPacketParityPatches() {
    }

    public static boolean requested() {
        return Boolean.getBoolean("gaius.debug.packetParity")
                || "1".equals(System.getenv("GAIUS_DEBUG_PACKET_PARITY"));
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("expected vanilla jar and overlay directory");
        apply(args[0], Path.of(args[1]));
        applyJvmSchedule(args[0], Path.of(args[1]));
    }

    /**
     * JVM capture host only (this entry point; the browser registration calls {@link #apply} and
     * {@link #applyBrowser}): vanilla's executor factories return the harness's deterministic
     * schedule, dev.gaius.parity.TickParityExecutors. Every vanilla task still runs unchanged.
     */
    public static void applyJvmSchedule(String jar, Path output) throws IOException {
        String tracing = "net/minecraft/TracingExecutor";
        String schedule = "dev/gaius/parity/TickParityExecutors";
        patch(jar, output, "net/minecraft/util/Util", node -> {
            replaceBody(find(node, "makeExecutor", "(Ljava/lang/String;)L" + tracing + ";"), tracing, schedule,
                    "background", "(Ljava/lang/String;)Ljava/util/concurrent/ExecutorService;", 0);
            replaceBody(find(node, "makeIoExecutor", "(Ljava/lang/String;Z)L" + tracing + ";"), tracing, schedule,
                    "io", "(Ljava/lang/String;Z)Ljava/util/concurrent/ExecutorService;", 0, 1);
        });
        patch(jar, output, "net/minecraft/server/level/ChunkGenerationTask", node -> {
            String owner = "net/minecraft/server/level/ChunkGenerationTask";
            MethodNode method = find(node, "runUntilWait", "()Ljava/util/concurrent/CompletableFuture;");
            method.instructions.insertBefore(method.instructions.getFirst(), chunkOrderCall(owner, "begin"));
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.ARETURN) {
                    method.instructions.insertBefore(instruction, chunkOrderCall(owner, "complete"));
                }
            }
        });
    }

    private static InsnList chunkOrderCall(String owner, String phase) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "pos", "Lnet/minecraft/world/level/ChunkPos;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/ChunkPos", "x", "()I", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "pos", "Lnet/minecraft/world/level/ChunkPos;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/ChunkPos", "z", "()I", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "targetStatus", "Lnet/minecraft/world/level/chunk/status/ChunkStatus;"));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/gaius/parity/TickParityChunkOrder", phase,
                "(Ljava/lang/Object;IILjava/lang/Object;)V", false));
        return code;
    }

    /**
     * WasmGC Worker only (registration MinecraftClientPatcher.patchTickPacketParity): the executor
     * schedule of the settle probe. Vanilla's executor factories first ask
     * dev.gaius.parity.BrowserTickParityExecutors, which returns null (vanilla's own executor follows
     * unchanged) unless the Worker requested a parity capture. Every consecutive executor's name
     * passes through it as well: in a Worker that requested a capture the worldgen dispatcher keeps
     * vanilla's name, so MinecraftServerWorkerPatcher's patched {@code run()} (which keys on the name it
     * gives that dispatcher) takes its inline, trampolined re-registration instead of a deferred pump.
     */
    public static void applyBrowserSchedule(String jar, Path output) throws IOException {
        String tracing = "net/minecraft/TracingExecutor";
        String schedule = "dev/gaius/parity/BrowserTickParityExecutors";
        patch(jar, output, "net/minecraft/util/Util", node -> {
            preferFactory(find(node, "makeExecutor", "(Ljava/lang/String;)L" + tracing + ";"), tracing, schedule,
                    "background", "(Ljava/lang/String;)Ljava/util/concurrent/ExecutorService;", 0);
            preferFactory(find(node, "makeIoExecutor", "(Ljava/lang/String;Z)L" + tracing + ";"), tracing, schedule,
                    "io", "(Ljava/lang/String;Z)Ljava/util/concurrent/ExecutorService;", 0, 1);
        });
        patch(jar, output, "net/minecraft/util/thread/AbstractConsecutiveExecutor", node -> {
            MethodNode constructor = find(node, "<init>",
                    "(Lnet/minecraft/util/thread/StrictQueue;Ljava/util/concurrent/Executor;Ljava/lang/String;)V");
            InsnList name = new InsnList();
            name.add(new VarInsnNode(Opcodes.ALOAD, 3));
            name.add(new MethodInsnNode(Opcodes.INVOKESTATIC, schedule, "consecutiveName",
                    "(Ljava/lang/String;)Ljava/lang/String;", false));
            name.add(new VarInsnNode(Opcodes.ASTORE, 3));
            constructor.instructions.insert(name);
        });
        applyBrowserVanillaPaths(jar, output, schedule);
    }

    /**
     * The Worker's bounded chunk-generation layer (ModernWorldgenPatches.replaceChunkGenerationScheduleLayer)
     * submits at most 16 holders per call and hands the rest of the layer to a new activation of the
     * host run queue, so the dispatcher can pick other generation tasks between two batches; vanilla
     * submits a whole layer in one call. During a recording the layer is therefore vanilla's: the batch
     * limit becomes unbounded ({@code holdersPerTurn}) and no continuation is started
     * ({@code boundedLayers}), so a layer is one uninterrupted submission, in the order the JVM capture
     * has. Outside a recording both routes return what the Worker used before.
     *
     * <p>Called by ModernWorldgenPatches.patchChunkGenerationCooperation right after it writes the
     * cursor (MinecraftClientPatcher.patchTickPacketParity runs before that patcher and would find
     * vanilla's class), for a WasmGC packet-parity debug build only. A class without the cursor (the
     * field {@code browserLayerYield}) needs no route.
     */
    public static void applyBrowserLayerCursor(String jar, Path output, String schedule) throws IOException {
        String owner = "net/minecraft/server/level/ChunkGenerationTask";
        ClassNode current = new ClassNode();
        new ClassReader(read(jar, output, owner)).accept(current, 0);
        if (current.fields.stream().noneMatch(field -> field.name.equals("browserLayerYield"))) return;
        patch(jar, output, owner, node -> {
            MethodNode layer = find(node, "scheduleLayer", "(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Z)V");
            int limits = 0;
            AbstractInsnNode continuation = null;
            AbstractInsnNode starter = null;
            for (AbstractInsnNode instruction : layer.instructions.toArray()) {
                if (instruction instanceof LdcInsnNode constant && Integer.valueOf(16).equals(constant.cst)) {
                    layer.instructions.insert(constant, new MethodInsnNode(Opcodes.INVOKESTATIC, schedule,
                            "holdersPerTurn", "(I)I", false));
                    limits++;
                } else if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW && type.desc.equals("java/util/concurrent/CompletableFuture")) {
                    continuation = type;
                } else if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("dev/gaius/browser/HostLoop") && call.name.equals("startThread")) {
                    starter = call;
                }
            }
            if (limits != 1 || continuation == null || starter == null) {
                throw new IllegalStateException("ChunkGenerationTask.scheduleLayer cursor shape changed: limits=" + limits
                        + " continuation=" + (continuation != null) + " startThread=" + (starter != null));
            }
            AbstractInsnNode exit = nextOpcode(starter);
            if (!(exit instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.GOTO) {
                throw new IllegalStateException("ChunkGenerationTask.scheduleLayer: the continuation start is not followed by the return jump");
            }
            LabelNode bounded = new LabelNode();
            InsnList route = new InsnList();
            route.add(new MethodInsnNode(Opcodes.INVOKESTATIC, schedule, "boundedLayers", "()Z", false));
            route.add(new JumpInsnNode(Opcodes.IFNE, bounded));
            route.add(new JumpInsnNode(Opcodes.GOTO, jump.label));
            route.add(bounded);
            layer.instructions.insertBefore(continuation, route);
            layer.maxStack = Math.max(layer.maxStack, 2);
        });
    }

    /** Keeps replayed serverbound handlers inside the recorder's synchronous apply scope. */
    public static void applyBrowserPacketApplyInline(String jar, Path output) throws IOException {
        String packetUtils = "net/minecraft/network/protocol/PacketUtils";
        patch(jar, output, packetUtils, node -> {
            MethodNode method = find(node, "ensureRunningOnSameThread",
                    "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
                            + "Lnet/minecraft/network/PacketProcessor;)V");
            LabelNode normalDispatch = new LabelNode();
            InsnList code = new InsnList();
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, "isApplying", "()Z", false));
            code.add(new JumpInsnNode(Opcodes.IFEQ, normalDispatch));
            code.add(new InsnNode(Opcodes.RETURN));
            code.add(normalDispatch);
            method.instructions.insert(code);
            method.maxStack = Math.max(method.maxStack, 1);

            int recorderChecks = 0;
            boolean branchBeforeWorkerGuard = false;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(RECORDER) && call.name.equals("isApplying")
                        && call.desc.equals("()Z")) {
                    recorderChecks++;
                    AbstractInsnNode branch = nextOpcode(call);
                    branchBeforeWorkerGuard = branch instanceof JumpInsnNode jump
                            && jump.getOpcode() == Opcodes.IFEQ && jump.label == normalDispatch;
                }
            }
            if (recorderChecks != 1 || !branchBeforeWorkerGuard) {
                throw new IllegalStateException("PacketUtils parity apply inline CFG changed: recorder="
                        + recorderChecks
                        + " branchBeforeWorkerGuard=" + branchBeforeWorkerGuard);
            }
        });
    }

    /**
     * During a recording (TickPacketRecorder.enabled) the server Worker's own movement and chunk
     * paths step aside for vanilla's, which the JVM capture runs: the Worker fast path at the head of
     * {@code handleMovePlayer} (it returns before vanilla's thread barrier and the parity input hook),
     * the deferred chunk-tracking walk that replaces {@code ServerChunkCache.move}, the nearest-first
     * choice in {@code ChunkTaskPriorityQueue.pop}, and the four unacknowledged chunk batches a new
     * {@code PlayerChunkSender} starts with. Each call that MinecraftClientPatcher wrote earlier is
     * routed through dev.gaius.parity.BrowserTickParityExecutors, which forwards to it outside a
     * recording. A PERF patch that was disabled left vanilla code behind, which needs no route.
     */
    private static void applyBrowserVanillaPaths(String jar, Path output, String schedule) throws IOException {
        String server = "dev/gaius/browser/BrowserIntegratedServerMain";
        patch(jar, output, "net/minecraft/server/network/ServerGamePacketListenerImpl", node -> {
            MethodNode movePlayer = find(node, "handleMovePlayer",
                    "(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V");
            int guards = reroute(movePlayer, server, "isWorkerServer", "()Z", schedule, "workerMovement");
            int moves = 0;
            for (MethodNode method : node.methods) {
                moves += reroute(method, server, "moveServerPlayerChunkTracking",
                        "(Lnet/minecraft/server/level/ServerChunkCache;Lnet/minecraft/server/level/ServerPlayer;)V",
                        schedule, "moveChunkTracking");
            }
            if (guards != 1 || moves != 2) {
                throw new IllegalStateException("Worker movement paths changed: guards=" + guards + " moves=" + moves);
            }
        });
        String queue = "net/minecraft/server/level/ChunkTaskPriorityQueue";
        String popDescriptor = "()Lnet/minecraft/server/level/ChunkTaskPriorityQueue$TasksForChunk;";
        if (calls(jar, output, queue, "pop", popDescriptor, "dev/gaius/browser/BrowserChunkTaskPriority", "chooseNext")) {
            patch(jar, output, queue, node -> {
                int chosen = reroute(find(node, "pop", popDescriptor), "dev/gaius/browser/BrowserChunkTaskPriority",
                        "chooseNext", "(Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;)J", schedule, "chunkTaskKey");
                if (chosen != 1) throw new IllegalStateException("chunk task chooser calls: " + chosen);
            });
        } else if (!calls(jar, output, queue, "pop", popDescriptor, "it/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap",
                "firstLongKey")) {
            throw new IllegalStateException("missing vanilla shape " + queue + ".pop");
        }
        String sender = "net/minecraft/server/network/PlayerChunkSender";
        patch(jar, output, sender, node -> {
            MethodNode constructor = find(node, "<init>", "(Z)V");
            int routed = 0;
            for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(sender) && field.name.equals("maxUnacknowledgedBatches") && field.desc.equals("I")) {
                    constructor.instructions.insertBefore(field, new MethodInsnNode(Opcodes.INVOKESTATIC, schedule,
                            "unacknowledgedBatches", "(I)I", false));
                    routed++;
                }
            }
            if (routed != 1) throw new IllegalStateException("PlayerChunkSender batch limit assignments: " + routed);
        });
    }

    private static int reroute(MethodNode method, String owner, String name, String descriptor, String target,
            String replacement) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) {
                call.owner = target;
                call.name = replacement;
                count++;
            }
        }
        return count;
    }

    /** Whether the current (already patched, else vanilla) class calls owner.name in the method. */
    private static boolean calls(String jar, Path output, String className, String method, String descriptor, String owner,
            String name) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(read(jar, output, className)).accept(node, 0);
        for (AbstractInsnNode instruction : find(node, method, descriptor).instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
        }
        return false;
    }

    /** Prepends {@code service = factory(args); if (service != null) return new TracingExecutor(service);}. */
    private static void preferFactory(MethodNode method, String tracing, String owner, String factory, String descriptor,
            int... arguments) {
        int local = method.maxLocals;
        InsnList head = new InsnList();
        org.objectweb.asm.Type[] types = org.objectweb.asm.Type.getArgumentTypes(method.desc);
        for (int argument : arguments) head.add(new VarInsnNode(types[argument].getOpcode(Opcodes.ILOAD), argument));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, factory, descriptor, false));
        head.add(new VarInsnNode(Opcodes.ASTORE, local));
        head.add(new VarInsnNode(Opcodes.ALOAD, local));
        LabelNode vanilla = new LabelNode();
        head.add(new JumpInsnNode(Opcodes.IFNULL, vanilla));
        head.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, tracing));
        head.add(new InsnNode(Opcodes.DUP));
        head.add(new VarInsnNode(Opcodes.ALOAD, local));
        head.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, tracing, "<init>", "(Ljava/util/concurrent/ExecutorService;)V", false));
        head.add(new InsnNode(Opcodes.ARETURN));
        head.add(vanilla);
        method.instructions.insert(head);
        method.maxLocals = local + 1;
    }

    private static void replaceBody(MethodNode method, String tracing, String owner, String factory, String descriptor,
            int... arguments) {
        InsnList body = new InsnList();
        body.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, tracing));
        body.add(new InsnNode(Opcodes.DUP));
        org.objectweb.asm.Type[] types = org.objectweb.asm.Type.getArgumentTypes(method.desc);
        for (int argument : arguments) body.add(new VarInsnNode(types[argument].getOpcode(Opcodes.ILOAD), argument));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, factory, descriptor, false));
        body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, tracing, "<init>", "(Ljava/util/concurrent/ExecutorService;)V", false));
        body.add(new InsnNode(Opcodes.ARETURN));
        method.instructions = body;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        method.invisibleAnnotations = null;
    }

    public static void apply(String jar, Path output) throws IOException {
        patch(jar, output, SERVER, node -> {
            MethodNode method = find(node, "tickServer", "(Ljava/util/function/BooleanSupplier;)V");
            method.instructions.insert(guarded(call("beforeTick", "(L" + SERVER + ";)V", 0)));
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.RETURN) {
                    method.instructions.insertBefore(instruction,
                            guarded(call("afterTick", "(L" + SERVER + ";)V", 0)));
                }
            }
        });
        patch(jar, output, CONNECTION, node -> {
            MethodNode outbound = find(node, "setupOutboundProtocol", "(" + PROTOCOL + ")V");
            outbound.instructions.insert(guarded(call("outboundProtocol", "(L" + CONNECTION + ";"
                    + PROTOCOL + ")V", 0, 1)));
            MethodNode inbound = find(node, "setupInboundProtocol", "(" + PROTOCOL
                    + "Lnet/minecraft/network/PacketListener;)V");
            inbound.instructions.insert(guarded(call("inboundProtocol", "(L" + CONNECTION + ";"
                    + PROTOCOL + "Lnet/minecraft/network/PacketListener;)V", 0, 1, 2)));
            MethodNode send = find(node, "send", "(" + PACKET
                    + "Lio/netty/channel/ChannelFutureListener;Z)V");
            send.instructions.insert(guarded(call("outgoing", "(L" + CONNECTION + ";" + PACKET + ")V", 0, 1)));
        });
        patch(jar, output, "net/minecraft/server/network/ServerGamePacketListenerImpl", node -> {
            int count = 0;
            for (MethodNode method : node.methods) {
                if (!method.name.startsWith("handle") || !method.desc.startsWith(
                        "(Lnet/minecraft/network/protocol/game/Serverbound") || !method.desc.endsWith(";)V")) continue;
                MethodInsnNode barrier = null;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode invocation
                            && invocation.name.equals("ensureRunningOnSameThread")) {
                        barrier = invocation;
                        break;
                    }
                }
                InsnList record = new InsnList();
                record.add(new VarInsnNode(Opcodes.ALOAD, 0));
                record.add(new FieldInsnNode(Opcodes.GETFIELD,
                        "net/minecraft/server/network/ServerCommonPacketListenerImpl", "connection", "L" + CONNECTION + ";"));
                record.add(new VarInsnNode(Opcodes.ALOAD, 1));
                record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, "input",
                        "(L" + CONNECTION + ";" + PACKET + ")V", false));
                if (barrier == null) method.instructions.insert(guarded(record));
                else method.instructions.insert(barrier, guarded(record));
                count++;
            }
            if (count < 10) throw new IllegalStateException("too few input barriers: " + count);
            System.out.println("TICK_PARITY_INPUT_BARRIERS " + count);
        });
        patch(jar, output, "net/minecraft/server/network/ServerCommonPacketListenerImpl", node -> {
            for (MethodNode method : node.methods) {
                if (!method.name.startsWith("handle") || !method.desc.startsWith("(Lnet/minecraft/network/protocol/common/Serverbound")
                        || !method.desc.endsWith(";)V") || org.objectweb.asm.Type.getArgumentTypes(method.desc).length != 1) continue;
                MethodInsnNode barrier = null;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode invocation && invocation.name.equals("ensureRunningOnSameThread")) {
                        barrier = invocation;
                        break;
                    }
                }
                InsnList record = new InsnList();
                record.add(new VarInsnNode(Opcodes.ALOAD, 0));
                record.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "connection", "L" + CONNECTION + ";"));
                record.add(new VarInsnNode(Opcodes.ALOAD, 1));
                record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, "input", "(L" + CONNECTION + ";" + PACKET + ")V", false));
                if (barrier == null) method.instructions.insert(guarded(record));
                else method.instructions.insert(barrier, guarded(record));
            }
        });
        patch(jar, output, "net/minecraft/server/level/ChunkHolder", node -> {
            // Brackets the packets one holder broadcasts; vanilla iterates the holders to broadcast in
            // identity-hash order, so the comparator needs each holder's group boundary.
            MethodNode broadcast = find(node, "broadcastChanges", "(Lnet/minecraft/world/level/chunk/LevelChunk;)V");
            broadcast.instructions.insert(guarded(chunkBroadcast(true)));
            for (AbstractInsnNode instruction : broadcast.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.RETURN) broadcast.instructions.insertBefore(instruction, guarded(chunkBroadcast(false)));
            }
        });
        patch(jar, output, "net/minecraft/world/entity/Entity", node -> {
            MethodNode constructor = find(node, "<init>",
                    "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V");
            for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.RETURN) {
                    constructor.instructions.insertBefore(instruction, guarded(call("entity",
                            "(Lnet/minecraft/world/entity/Entity;)V", 0)));
                }
            }
            MethodNode identity = find(node, "setUUID", "(Ljava/util/UUID;)V");
            for (AbstractInsnNode instruction : identity.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.RETURN) {
                    identity.instructions.insertBefore(instruction, guarded(call("entityIdentity",
                            "(Lnet/minecraft/world/entity/Entity;)V", 0)));
                }
            }
        });
    }

    public static void applyBrowser(String jar, Path output) throws IOException {
        String bridge = "dev/gaius/parity/BrowserTickPacketParity";
        patch(jar, output, "net/minecraft/server/dedicated/DedicatedServerProperties", node -> {
            MethodNode constructor = find(node, "<init>", "(Ljava/util/Properties;)V");
            InsnList instructions = new InsnList();
            instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "properties", "(Ljava/util/Properties;)V", false));
            constructor.instructions.insert(instructions);
        });
        patch(jar, output, SERVER, node -> {
            MethodNode load = find(node, "loadLevel", "()V");
            for (AbstractInsnNode instruction : load.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.RETURN) {
                    InsnList instructions = new InsnList();
                    instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "prepare", "(L" + SERVER + ";)V", false));
                    load.instructions.insertBefore(instruction, guarded(instructions));
                }
            }
        });
        patch(jar, output, "net/minecraft/server/players/PlayerList", node -> {
            distanceHook(node, "setViewDistance", "viewDistance", false);
            distanceHook(node, "setSimulationDistance", "simulationDistance", true);
        });
    }

    private static void distanceHook(ClassNode node, String name, String field, boolean simulation) {
        MethodNode method = find(node, name, "(I)V");
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getServer", "()L" + SERVER + ";", false));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        instructions.add(new InsnNode(simulation ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, "fixedDistance", "(L" + SERVER + ";IZ)I", false));
        instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, "I"));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        LabelNode changed = new LabelNode();
        instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, changed));
        instructions.add(new InsnNode(Opcodes.RETURN));
        instructions.add(changed);
        method.instructions.insert(guarded(instructions));
    }

    private static InsnList chunkBroadcast(boolean start) {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new InsnNode(start ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, "chunkBroadcast",
                "(Lnet/minecraft/server/level/ChunkHolder;Z)V", false));
        return instructions;
    }

    private static InsnList call(String name, String descriptor, int... locals) {
        InsnList instructions = new InsnList();
        for (int local : locals) instructions.add(new VarInsnNode(Opcodes.ALOAD, local));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, name, descriptor, false));
        return instructions;
    }

    private static InsnList guarded(InsnList body) {
        InsnList instructions = new InsnList();
        LabelNode disabled = new LabelNode();
        instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, RECORDER, "enabled", "Z"));
        instructions.add(new JumpInsnNode(Opcodes.IFEQ, disabled));
        instructions.add(body);
        instructions.add(disabled);
        return instructions;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        }
        throw new IllegalStateException("missing vanilla shape " + node.name + "." + name + descriptor);
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static byte[] read(String jar, Path output, String owner) throws IOException {
        Path destination = output.resolve(owner + ".class");
        if (Files.exists(destination)) return Files.readAllBytes(destination);
        try (ZipFile source = new ZipFile(jar)) {
            var entry = source.getEntry(owner + ".class");
            if (entry == null) throw new IOException("missing vanilla class " + owner);
            try (var input = source.getInputStream(entry)) { return input.readAllBytes(); }
        }
    }

    private static void patch(String jar, Path output, String owner, Consumer editor) throws IOException {
        Path destination = output.resolve(owner + ".class");
        byte[] bytes = read(jar, output, owner);
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        editor.accept(node);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {Path.of(jar).toUri().toURL()},
                TickPacketParityPatches.class.getClassLoader())) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() { return loader; }

                // The JVM harness runs with vanilla and its libraries on the class path, so every
                // merge resolves. An overlay build sees only the client jar; a merge of types it
                // cannot load becomes Object, as MinecraftClientPatcher.writeComputeFrames does for
                // TeaVM, which infers types itself and does not verify stack map frames.
                @Override
                protected String getCommonSuperClass(String first, String second) {
                    try {
                        return super.getCommonSuperClass(first, second);
                    } catch (RuntimeException | LinkageError unavailable) {
                        return "java/lang/Object";
                    }
                }
            };
            node.accept(writer);
            Files.createDirectories(destination.getParent());
            Files.write(destination, writer.toByteArray());
        }
    }

    private interface Consumer {
        void accept(ClassNode node);
    }
}
