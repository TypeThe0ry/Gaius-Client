package dev.gaius.tools.patches.serverworker;

import static dev.gaius.tools.patches.support.ModernPatchSupport.WORLDGEN_SCHEDULER;
import static dev.gaius.tools.patches.support.ModernPatchSupport.fieldBefore;
import static dev.gaius.tools.patches.support.ModernPatchSupport.find;
import static dev.gaius.tools.patches.support.ModernPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ModernPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ModernPatchSupport.read;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replace;
import static dev.gaius.tools.patches.support.ModernPatchSupport.requireOne;
import static dev.gaius.tools.patches.support.ModernPatchSupport.requireReceiverLoad;
import static dev.gaius.tools.patches.support.ModernPatchSupport.requireWorldgenLoopPulses;
import static dev.gaius.tools.patches.support.ModernPatchSupport.writeComputeFrames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
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
 * The server-worker domain of the Minecraft262BrowserPatcher step, the {@code modern} patch set
 * arm ({@code patches/server-worker}): cooperation of the distance manager, chunk map
 * movement, chunk tracking views and chunk broadcasts with the browser's server work turns.
 *
 * <p>{@code Minecraft262BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft262BrowserPatcher.<method>}); the ASM helpers that other
 * domains of that step share live in ModernPatchSupport and are imported statically.
 */
public final class ModernServerWorkerPatches {
    private ModernServerWorkerPatches() {
    }

    /**
     * Makes the vanilla DistanceManager work queue resumable. The snapshots are essential:
     * worldgen yields pump urgent packets on this same logical server thread, and those packets
     * may enqueue more ticket work. Clearing the live sets before walking their snapshots leaves
     * that new work in the live sets for the next tick instead of invalidating an iterator or
     * clearing it at the end of the pass.
     */
    public static void patchDistanceManagerCooperation(String jar, Path root)
            throws IOException {
        patchLoadingChunkTrackerCooperation(jar, root);

        String owner = "net/minecraft/server/level/DistanceManager";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "runAllUpdates",
                "(Lnet/minecraft/server/level/ChunkMap;)Z");

        snapshotChunkFutureUpdates(method, owner);
        snapshotTicketReleases(method, owner);
        requireWorldgenLoopPulses(
                "DistanceManager.runAllUpdates",
                method,
                3,
                "pulseDistanceManager");

        writeComputeFrames(node, root.resolve(owner + ".class"));
        System.out.println(
                "Bounded Minecraft 26.2 DistanceManager updates, ticket releases, and futures");
    }

    private static void patchLoadingChunkTrackerCooperation(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/server/level/LoadingChunkTracker";
        String graph = "net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "runDistanceUpdates", "(I)I");

        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        LabelNode continueWithoutYield = new LabelNode();
        InsnList code = new InsnList();
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFLE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                graph,
                "hasWork",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "distanceManagerUpdateBudget",
                "()I",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Math",
                "min",
                "(II)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                graph,
                "runUpdates",
                "(I)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "recordDistanceManagerUpdates",
                "(I)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                graph,
                "hasWork",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFLE, done));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new JumpInsnNode(Opcodes.IFGT, continueWithoutYield));
        code.add(new JumpInsnNode(Opcodes.GOTO, done));
        code.add(continueWithoutYield);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "pulseDistanceManager",
                "()V",
                false));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 2, 5);
        writeComputeFrames(node, root.resolve(owner + ".class"));
    }

    private static void snapshotChunkFutureUpdates(MethodNode method, String owner) {
        java.util.List<MethodInsnNode> setIterators = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/Set")
                    && call.name.equals("iterator")
                    && call.desc.equals("()Ljava/util/Iterator;")) {
                FieldInsnNode field = fieldBefore(call);
                if (field != null
                        && field.owner.equals(owner)
                        && field.name.equals("chunksToUpdateFutures")) {
                    setIterators.add(call);
                }
            }
        }
        if (setIterators.size() != 2) {
            throw new IllegalStateException(
                    "DistanceManager chunks-to-update iterator count changed: "
                            + setIterators.size());
        }

        MethodInsnNode originalClear = null;
        MethodInsnNode secondIterator = setIterators.get(1);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/Set")
                    && call.name.equals("clear")
                    && call.desc.equals("()V")) {
                FieldInsnNode field = fieldBefore(call);
                if (field != null
                        && field.owner.equals(owner)
                        && field.name.equals("chunksToUpdateFutures")
                        && method.instructions.indexOf(call)
                                > method.instructions.indexOf(secondIterator)) {
                    originalClear = call;
                    break;
                }
            }
        }
        if (originalClear == null) {
            throw new IllegalStateException("DistanceManager futures clear was not found");
        }

        int snapshotLocal = method.maxLocals++;
        MethodInsnNode first = setIterators.get(0);
        FieldInsnNode firstField = fieldBefore(first);
        AbstractInsnNode firstOwner = previousOpcode(firstField);
        requireReceiverLoad("DistanceManager first futures iterator", firstOwner, firstField);
        InsnList firstSnapshot = new InsnList();
        firstSnapshot.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        firstSnapshot.add(new InsnNode(Opcodes.DUP));
        firstSnapshot.add(new VarInsnNode(Opcodes.ALOAD, 0));
        firstSnapshot.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "chunksToUpdateFutures", "Ljava/util/Set;"));
        firstSnapshot.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/ArrayList",
                "<init>",
                "(Ljava/util/Collection;)V",
                false));
        firstSnapshot.add(new VarInsnNode(Opcodes.ASTORE, snapshotLocal));
        firstSnapshot.add(new VarInsnNode(Opcodes.ALOAD, 0));
        firstSnapshot.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "chunksToUpdateFutures", "Ljava/util/Set;"));
        firstSnapshot.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "clear",
                "()V",
                true));
        firstSnapshot.add(new VarInsnNode(Opcodes.ALOAD, snapshotLocal));
        firstSnapshot.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        method.instructions.insertBefore(firstOwner, firstSnapshot);
        method.instructions.remove(first);
        method.instructions.remove(firstField);
        method.instructions.remove(firstOwner);

        MethodInsnNode second = secondIterator;
        FieldInsnNode secondField = fieldBefore(second);
        AbstractInsnNode secondOwner = previousOpcode(secondField);
        requireReceiverLoad("DistanceManager second futures iterator", secondOwner, secondField);
        InsnList secondSnapshot = new InsnList();
        secondSnapshot.add(new VarInsnNode(Opcodes.ALOAD, snapshotLocal));
        secondSnapshot.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        method.instructions.insertBefore(secondOwner, secondSnapshot);
        method.instructions.remove(second);
        method.instructions.remove(secondField);
        method.instructions.remove(secondOwner);

        FieldInsnNode clearField = fieldBefore(originalClear);
        AbstractInsnNode clearOwner = previousOpcode(clearField);
        requireReceiverLoad("DistanceManager futures clear", clearOwner, clearField);
        method.instructions.remove(originalClear);
        method.instructions.remove(clearField);
        method.instructions.remove(clearOwner);
    }

    private static void snapshotTicketReleases(MethodNode method, String owner) {
        MethodInsnNode iterator = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("it/unimi/dsi/fastutil/longs/LongSet")
                    && call.name.equals("iterator")
                    && call.desc.equals("()Lit/unimi/dsi/fastutil/longs/LongIterator;")) {
                FieldInsnNode field = fieldBefore(call);
                if (field != null
                        && field.owner.equals(owner)
                        && field.name.equals("ticketsToRelease")) {
                    iterator = call;
                    break;
                }
            }
        }
        if (iterator == null) {
            throw new IllegalStateException("DistanceManager ticket iterator was not found");
        }
        FieldInsnNode iteratorField = fieldBefore(iterator);
        AbstractInsnNode iteratorOwner = previousOpcode(iteratorField);
        requireReceiverLoad("DistanceManager ticket iterator", iteratorOwner, iteratorField);
        AbstractInsnNode iteratorStore = nextOpcode(iterator);
        AbstractInsnNode hasNextLoad = nextOpcode(iteratorStore);
        AbstractInsnNode hasNext = nextOpcode(hasNextLoad);
        AbstractInsnNode endJump = nextOpcode(hasNext);
        AbstractInsnNode nextLongLoad = nextOpcode(endJump);
        AbstractInsnNode nextLong = nextOpcode(nextLongLoad);
        AbstractInsnNode ticketStore = nextOpcode(nextLong);
        if (!(iteratorStore instanceof VarInsnNode storeIterator)
                || storeIterator.getOpcode() != Opcodes.ASTORE
                || !(hasNextLoad instanceof VarInsnNode loadIterator)
                || loadIterator.getOpcode() != Opcodes.ALOAD
                || loadIterator.var != storeIterator.var
                || !(hasNext instanceof MethodInsnNode hasNextCall)
                || !hasNextCall.owner.equals("it/unimi/dsi/fastutil/longs/LongIterator")
                || !hasNextCall.name.equals("hasNext")
                || !(endJump instanceof JumpInsnNode end)
                || end.getOpcode() != Opcodes.IFEQ
                || !(nextLongLoad instanceof VarInsnNode loadNext)
                || loadNext.getOpcode() != Opcodes.ALOAD
                || loadNext.var != storeIterator.var
                || !(nextLong instanceof MethodInsnNode nextLongCall)
                || !nextLongCall.owner.equals("it/unimi/dsi/fastutil/longs/LongIterator")
                || !nextLongCall.name.equals("nextLong")
                || !(ticketStore instanceof VarInsnNode storeTicket)
                || storeTicket.getOpcode() != Opcodes.LSTORE) {
            throw new IllegalStateException("DistanceManager ticket loop shape changed");
        }

        JumpInsnNode backedge = null;
        for (AbstractInsnNode instruction = ticketStore.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO
                    && method.instructions.indexOf(jump.label)
                            <= method.instructions.indexOf(hasNextLoad)) {
                backedge = jump;
                break;
            }
        }
        if (backedge == null) {
            throw new IllegalStateException("DistanceManager ticket loop backedge was not found");
        }

        int ticketsLocal = method.maxLocals++;
        int ticketIndexLocal = method.maxLocals++;
        InsnList snapshot = new InsnList();
        snapshot.add(new VarInsnNode(Opcodes.ALOAD, 0));
        snapshot.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "ticketsToRelease",
                "Lit/unimi/dsi/fastutil/longs/LongSet;"));
        snapshot.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/longs/LongSet",
                "toLongArray",
                "()[J",
                true));
        snapshot.add(new VarInsnNode(Opcodes.ASTORE, ticketsLocal));
        snapshot.add(new VarInsnNode(Opcodes.ALOAD, 0));
        snapshot.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "ticketsToRelease",
                "Lit/unimi/dsi/fastutil/longs/LongSet;"));
        snapshot.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/longs/LongSet",
                "clear",
                "()V",
                true));
        snapshot.add(new InsnNode(Opcodes.ICONST_0));
        snapshot.add(new VarInsnNode(Opcodes.ISTORE, ticketIndexLocal));
        method.instructions.insertBefore(iteratorOwner, snapshot);
        method.instructions.remove(iterator);
        method.instructions.remove(iteratorField);
        method.instructions.remove(iteratorOwner);
        method.instructions.remove(iteratorStore);

        LabelNode loopCheck = new LabelNode();
        InsnList condition = new InsnList();
        condition.add(loopCheck);
        condition.add(new VarInsnNode(Opcodes.ILOAD, ticketIndexLocal));
        condition.add(new VarInsnNode(Opcodes.ALOAD, ticketsLocal));
        condition.add(new InsnNode(Opcodes.ARRAYLENGTH));
        condition.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end.label));
        method.instructions.insertBefore(hasNextLoad, condition);
        method.instructions.remove(hasNextLoad);
        method.instructions.remove(hasNext);
        method.instructions.remove(endJump);

        InsnList ticketLoad = new InsnList();
        ticketLoad.add(new VarInsnNode(Opcodes.ALOAD, ticketsLocal));
        ticketLoad.add(new VarInsnNode(Opcodes.ILOAD, ticketIndexLocal));
        ticketLoad.add(new InsnNode(Opcodes.LALOAD));
        method.instructions.insertBefore(nextLongLoad, ticketLoad);
        method.instructions.remove(nextLongLoad);
        method.instructions.remove(nextLong);

        InsnList advance = new InsnList();
        advance.add(new org.objectweb.asm.tree.IincInsnNode(ticketIndexLocal, 1));
        advance.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "pulseDistanceManager",
                "()V",
                false));
        advance.add(new JumpInsnNode(Opcodes.GOTO, loopCheck));
        method.instructions.insertBefore(backedge, advance);
        method.instructions.remove(backedge);

        MethodInsnNode clear = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("it/unimi/dsi/fastutil/longs/LongSet")
                    && call.name.equals("clear")
                    && call.desc.equals("()V")) {
                FieldInsnNode field = fieldBefore(call);
                if (field != null
                        && field.owner.equals(owner)
                        && field.name.equals("ticketsToRelease")
                        && method.instructions.indexOf(call)
                                > method.instructions.indexOf(end.label)) {
                    clear = call;
                    break;
                }
            }
        }
        if (clear == null) {
            throw new IllegalStateException("DistanceManager ticket clear was not found");
        }
        FieldInsnNode clearField = fieldBefore(clear);
        AbstractInsnNode clearOwner = previousOpcode(clearField);
        requireReceiverLoad("DistanceManager ticket clear", clearOwner, clearField);
        method.instructions.remove(clear);
        method.instructions.remove(clearField);
        method.instructions.remove(clearOwner);
    }

    public static void patchServerChunkBroadcastCooperation(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/server/level/ServerChunkCache";
        String holder = "net/minecraft/server/level/ChunkHolder";
        String levelChunk = "net/minecraft/world/level/chunk/LevelChunk";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "broadcastChangedChunks",
                "(Lnet/minecraft/util/profiling/ProfilerFiller;)V");

        LabelNode loop = new LabelNode();
        LabelNode skip = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new LdcInsnNode("broadcast"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/util/profiling/ProfilerFiller",
                "push",
                "(Ljava/lang/String;)V",
                true));
        // Snapshot before a cooperative pulse. New dirty holders stay in the live set for next tick.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "chunkHoldersToBroadcast", "Ljava/util/Set;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "toArray",
                "()[Ljava/lang/Object;",
                true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "chunkHoldersToBroadcast", "Ljava/util/Set;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "clear",
                "()V",
                true));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "beginChunkBroadcast",
                "(I)V",
                false));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, holder));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                holder,
                "getTickingChunk",
                "()Lnet/minecraft/world/level/chunk/LevelChunk;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new JumpInsnNode(Opcodes.IFNULL, skip));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                holder,
                "broadcastChanges",
                "(L" + levelChunk + ";)V",
                false));
        code.add(skip);
        code.add(new org.objectweb.asm.tree.IincInsnNode(3, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "pulseChunkBroadcast",
                "()V",
                false));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "finishChunkBroadcast",
                "(I)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/util/profiling/ProfilerFiller",
                "pop",
                "()V",
                true));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 3, 6);
        writeComputeFrames(node, root.resolve(owner + ".class"));
        System.out.println("Made Minecraft 26.2 changed-chunk broadcasts cooperative");
    }

    /**
     * Keeps player movement/tracking cooperative while the browser worldgen queue is under
     * pressure.  Vanilla 26.2 walks every tracked entity synchronously and then performs the
     * complete chunk-view difference in one call.  A flight packet can therefore monopolize the
     * integrated server and leave pending chunk tracking until the next tick.  Pulse only at
     * proven loop/backedge and difference boundaries; never change the tracking view ordering.
     */
    public static void patchChunkMapMovementCooperation(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/server/level/ChunkMap";
        // build-overlays runs after the client patcher.  Preserve the already patched class
        // (notably the initial view-distance rewrite) instead of re-reading the vanilla jar.
        Path output = root.resolve(owner + ".class");
        ClassNode node;
        if (Files.exists(output)) {
            node = new ClassNode();
            new ClassReader(Files.readAllBytes(output)).accept(node, 0);
        } else {
            node = read(jar, owner + ".class");
        }

        MethodNode move = find(node, "move", "(Lnet/minecraft/server/level/ServerPlayer;)V");
        int backedges = 0;
        for (AbstractInsnNode instruction : move.instructions.toArray()) {
            if (!(instruction instanceof JumpInsnNode jump)
                    || move.instructions.indexOf(jump.label)
                            >= move.instructions.indexOf(instruction)) {
                continue;
            }
            backedges++;
            AbstractInsnNode previous = previousOpcode(jump);
            if (!(previous instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals(WORLDGEN_SCHEDULER)
                    || !call.name.equals("pulse")
                    || !call.desc.equals("()V")) {
                move.instructions.insertBefore(
                        jump,
                        new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                WORLDGEN_SCHEDULER,
                                "pulse",
                                "()V",
                                false));
            }
        }
        requireOne("ChunkMap.move tracking loop", backedges);

        MethodNode apply = find(
                node,
                "applyChunkTrackingView",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ChunkTrackingView;)V");
        int differences = 0;
        for (AbstractInsnNode instruction : apply.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("net/minecraft/server/level/ChunkTrackingView")
                    || !call.name.equals("difference")
                    || !call.desc.equals(
                            "(Lnet/minecraft/server/level/ChunkTrackingView;"
                                    + "Lnet/minecraft/server/level/ChunkTrackingView;"
                                    + "Ljava/util/function/Consumer;"
                                    + "Ljava/util/function/Consumer;)V")) {
                continue;
            }
            differences++;
            AbstractInsnNode next = call.getNext();
            if (!(next instanceof MethodInsnNode pulse)
                    || pulse.getOpcode() != Opcodes.INVOKESTATIC
                    || !pulse.owner.equals(WORLDGEN_SCHEDULER)
                    || !pulse.name.equals("pulse")
                    || !pulse.desc.equals("()V")) {
                apply.instructions.insert(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        WORLDGEN_SCHEDULER,
                        "pulse",
                        "()V",
                        false));
            }
        }
        requireOne("ChunkMap.applyChunkTrackingView difference", differences);
        patchChunkTrackingLambda(
                node,
                "lambda$applyChunkTrackingView$0",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V",
                "markChunkPendingToSend",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V",
                Opcodes.INVOKEVIRTUAL);
        patchChunkTrackingLambda(
                node,
                "lambda$applyChunkTrackingView$1",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V",
                "dropChunk",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V",
                Opcodes.INVOKESTATIC);
        writeComputeFrames(node, output);
        System.out.println("Made Minecraft 26.2 ChunkMap movement/tracking cooperative");
    }

    /**
     * Yields while the view difference is enumerated.  ChunkMap.move() delegates the actual
     * rectangular add/drop walk to this static interface helper, so a pulse after the call is
     * too late: the entire (old view union new view) rectangle has already run on the Worker.
     * Keep the exact vanilla row-major ordering and only add scheduler checkpoints at the two
     * existing loop backedges.  The callbacks remain untouched, which preserves the pending
     * chunk set and PlayerChunkSender ordering while allowing a long view transition to return
     * to the browser event loop.
     */
    public static void patchChunkTrackingViewDifferenceCooperation(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/server/level/ChunkTrackingView";
        Path output = root.resolve(owner + ".class");
        ClassNode node;
        if (Files.exists(output)) {
            node = new ClassNode();
            new ClassReader(Files.readAllBytes(output)).accept(node, 0);
        } else {
            node = read(jar, owner + ".class");
        }

        MethodNode difference = find(
                node,
                "difference",
                "(Lnet/minecraft/server/level/ChunkTrackingView;"
                        + "Lnet/minecraft/server/level/ChunkTrackingView;"
                        + "Ljava/util/function/Consumer;"
                        + "Ljava/util/function/Consumer;)V");
        requireWorldgenLoopPulses(
                owner + ".difference",
                difference,
                2,
                "pulse");
        writeComputeFrames(node, output);
        System.out.println("Made Minecraft 26.2 ChunkTrackingView difference cooperative");
    }

    private static void patchChunkTrackingLambda(
            ClassNode node,
            String methodName,
            String methodDescriptor,
            String targetName,
            String targetDescriptor,
            int targetOpcode) {
        MethodNode method = find(node, methodName, methodDescriptor);
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != targetOpcode
                    || !call.owner.equals("net/minecraft/server/level/ChunkMap")
                    || !call.name.equals(targetName)
                    || !call.desc.equals(targetDescriptor)) {
                continue;
            }
            calls++;
            AbstractInsnNode next = call.getNext();
            if (!(next instanceof MethodInsnNode pulse)
                    || pulse.getOpcode() != Opcodes.INVOKESTATIC
                    || !pulse.owner.equals(WORLDGEN_SCHEDULER)
                    || !pulse.name.equals("pulse")
                    || !pulse.desc.equals("()V")) {
                method.instructions.insert(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        WORLDGEN_SCHEDULER,
                        "pulse",
                        "()V",
                        false));
            }
        }
        requireOne("ChunkMap." + methodName + " " + targetName, calls);
    }
}
