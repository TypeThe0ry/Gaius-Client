package dev.gaius.tools.patches.worldgen;

import static dev.gaius.tools.patches.support.ModernPatchSupport.WORLDGEN_SCHEDULER;
import static dev.gaius.tools.patches.support.ModernPatchSupport.find;
import static dev.gaius.tools.patches.support.ModernPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ModernPatchSupport.read;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replace;
import static dev.gaius.tools.patches.support.ModernPatchSupport.requireWorldgenLoopPulses;
import static dev.gaius.tools.patches.support.ModernPatchSupport.write;
import static dev.gaius.tools.patches.support.ModernPatchSupport.writeComputeFrames;

import dev.gaius.tools.BuildTarget;
import dev.gaius.tools.PatchRegistry;
import dev.gaius.tools.patches.telemetry.TickPacketParityPatches;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The worldgen domain of the Minecraft262BrowserPatcher step, the {@code modern} patch set arm
 * ({@code patches/worldgen}): chunk generation task cooperation (layer scheduling and
 * the pending-batch yield gate) and the noise chunk graph mapper.
 *
 * <p>{@code Minecraft262BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft262BrowserPatcher.<method>}); the ASM helpers that other
 * domains of that step share live in ModernPatchSupport and are imported statically.
 */
public final class ModernWorldgenPatches {
    private ModernWorldgenPatches() {
    }

    private static final String CHUNK_GENERATION_TASK =
            "net/minecraft/server/level/ChunkGenerationTask";

    private static final String CHUNK_STATUS =
            "net/minecraft/world/level/chunk/status/ChunkStatus";

    private static final String CHUNK_POS = "net/minecraft/world/level/ChunkPos";

    private static final String GENERATION_HOLDER =
            "net/minecraft/server/level/GenerationChunkHolder";

    private static final String BROWSER_LAYER_STATUS = "browserLayerStatus";

    private static final String BROWSER_LAYER_NEEDS_GENERATION =
            "browserLayerNeedsGeneration";

    private static final String BROWSER_LAYER_ACTIVE = "browserLayerActive";

    private static final String BROWSER_LAYER_X = "browserLayerX";

    private static final String BROWSER_LAYER_Z = "browserLayerZ";

    private static final String BROWSER_LAYER_START_Z = "browserLayerStartZ";

    private static final String BROWSER_LAYER_END_X = "browserLayerEndX";

    private static final String BROWSER_LAYER_END_Z = "browserLayerEndZ";

    private static final String BROWSER_LAYER_YIELD = "browserLayerYield";

    private static final String CHUNK_GENERATION_YIELD =
            "dev/gaius/browser/BrowserChunkGenerationYield";

    private static final int BROWSER_HOLDERS_PER_TURN = 16;

    public static void patchNoiseChunkGraphMapper(String jar, Path root) throws IOException {
        // 26.2 only. 26.3 compiles each density graph once per RandomState
        // (densityfunction/DensityFunctionCompiler) and binds it per chunk through a
        // SamplerContext: NoiseChunk has no NoiseRouter/DensityFunction.mapAll call sites and
        // DensityFunction$Visitor is gone, so BrowserNoiseGraphMapper has nothing to replace.
        if (jarHasEntry(jar,
                "net/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler.class")) {
            PatchRegistry.dropped("Minecraft262BrowserPatcher.patchNoiseChunkGraphMapper", jar,
                    "net/minecraft/world/level/levelgen/DensityFunction$Visitor.class",
                    "net/minecraft/world/level/levelgen/NoiseRouter#mapAll",
                    "net/minecraft/world/level/levelgen/NoiseChunk#wrap");
            return;
        }
        String owner = "net/minecraft/world/level/levelgen/NoiseChunk";
        Path output = root.resolve(owner + ".class");
        ClassNode node;
        if (Files.exists(output)) {
            node = new ClassNode();
            new ClassReader(Files.readAllBytes(output)).accept(node, 0);
        } else {
            node = read(jar, owner + ".class");
        }
        int routerRewrites = 0;
        int densityRewrites = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || (call.getOpcode() != Opcodes.INVOKEINTERFACE
                            && call.getOpcode() != Opcodes.INVOKEVIRTUAL)
                        || !call.owner.equals("net/minecraft/world/level/levelgen/NoiseRouter")
                        || !call.name.equals("mapAll")) {
                    if (instruction instanceof MethodInsnNode call2
                            && (call2.getOpcode() == Opcodes.INVOKEINTERFACE
                                || call2.getOpcode() == Opcodes.INVOKEVIRTUAL)
                            && call2.owner.equals("net/minecraft/world/level/levelgen/DensityFunction")
                            && call2.name.equals("mapAll")) {
                        requireNoiseChunkWrapVisitor(method, call2, owner);
                        call2.setOpcode(Opcodes.INVOKESTATIC);
                        call2.owner = "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper";
                        call2.name = "map";
                        call2.desc = "(Lnet/minecraft/world/level/levelgen/DensityFunction;"
                                + "Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)"
                                + "Lnet/minecraft/world/level/levelgen/DensityFunction;";
                        call2.itf = false;
                        densityRewrites++;
                    }
                    continue;
                }
                requireNoiseChunkWrapVisitor(method, call, owner);
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper";
                call.name = "mapRouter";
                call.desc = "(Lnet/minecraft/world/level/levelgen/NoiseRouter;"
                        + "Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)"
                        + "Lnet/minecraft/world/level/levelgen/NoiseRouter;";
                call.itf = false;
                routerRewrites++;
            }
        }
        if (routerRewrites != 1 || densityRewrites != 7) {
            throw new IllegalStateException(
                    "NoiseChunk graph mapper callsites changed: router=" + routerRewrites
                            + ", density=" + densityRewrites + " (expected 1,7)");
        }
        System.out.println("NoiseChunk graph mapper rewrites: router=" + routerRewrites
                + ", density=" + densityRewrites);
        // Receiver becomes the first static argument: stack shape and existing
        // control flow are unchanged, including all preceding overlay patches.
        write(node, output);
    }

    private static boolean jarHasEntry(String jar, String entry) throws IOException {
        try (ZipFile input = new ZipFile(jar)) {
            return input.getEntry(entry) != null;
        }
    }

    private static void requireNoiseChunkWrapVisitor(
            MethodNode method, MethodInsnNode call, String owner) {
        AbstractInsnNode previous = previousOpcode(call);
        if (!(previous instanceof InvokeDynamicInsnNode factory)
                || !factory.desc.equals("(L" + owner + ";)"
                        + "Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;")
                || factory.bsmArgs.length < 2
                || !(factory.bsmArgs[1] instanceof Handle target)
                || !target.getOwner().equals(owner)
                || !target.getName().equals("wrap")
                || !target.getDesc().equals(
                        "(Lnet/minecraft/world/level/levelgen/DensityFunction;)"
                                + "Lnet/minecraft/world/level/levelgen/DensityFunction;")) {
            throw new IllegalStateException(
                    "NoiseChunk graph mapping visitor changed in " + method.name);
        }
        String result = call.owner;
        if (!call.desc.equals("(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)L"
                + result + ";")) {
            throw new IllegalStateException("NoiseChunk mapAll descriptor changed in " + method.name);
        }
    }

    public static void patchChunkGenerationCooperation(String jar, Path root)
            throws IOException {
        String owner = CHUNK_GENERATION_TASK;
        ClassNode node = read(jar, owner + ".class");

        addPrivateField(node, BROWSER_LAYER_STATUS, "L" + CHUNK_STATUS + ";");
        addPrivateField(node, BROWSER_LAYER_NEEDS_GENERATION, "Z");
        addPrivateField(node, BROWSER_LAYER_ACTIVE, "Z");
        addPrivateField(node, BROWSER_LAYER_X, "I");
        addPrivateField(node, BROWSER_LAYER_Z, "I");
        addPrivateField(node, BROWSER_LAYER_START_Z, "I");
        addPrivateField(node, BROWSER_LAYER_END_X, "I");
        addPrivateField(node, BROWSER_LAYER_END_Z, "I");
        addPrivateField(node, BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;");
        MethodNode runUntilWait = find(
                node,
                "runUntilWait",
                "()Ljava/util/concurrent/CompletableFuture;");
        requireNoServerWorkTurnReset(
                "ChunkGenerationTask.runUntilWait",
                runUntilWait);
        patchRunUntilWaitYieldGate(runUntilWait, owner);
        replaceChunkGenerationScheduleNextLayer(
                find(node, "scheduleNextLayer", "()V"), owner);
        replaceChunkGenerationScheduleLayer(
                find(node, "scheduleLayer",
                        "(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Z)V"),
                owner);
        instrumentBrowserTaskScope(runUntilWait, "ChunkGenerationTask.runUntilWait", Opcodes.ARETURN);
        requireWorldgenLoopPulses(
                "ChunkGenerationTask.runUntilWait",
                runUntilWait,
                1,
                "pulse");
        requireWorldgenLoopPulses(
                "ChunkGenerationTask.waitForScheduledLayer",
                find(node, "waitForScheduledLayer", "()Ljava/util/concurrent/CompletableFuture;"),
                1,
                "pulse");
        requireWorldgenLoopPulses(
                "ChunkGenerationTask.canLoadWithoutGeneration",
                find(node, "canLoadWithoutGeneration", "()Z"),
                2,
                "pulse");

        // runUntilWait now has a catch-all task-scope finally plus token and
        // Throwable locals.  Recompute StackMapTable frames before emitting
        // the overlaid class; a plain writer leaves verifier frames stale on
        // the ARETURN/ATHROW paths.
        writeComputeFrames(node, root.resolve(owner + ".class"));
        writeChunkGenerationYieldHelper(root);
        if (BuildTarget.wasmGc() && TickPacketParityPatches.requested()) {
            // A packet-parity debug build records with vanilla's unbounded layer (the JVM capture's
            // order); BrowserTickParityExecutors routes the cursor back to the Worker's batches
            // whenever no recording runs.
            TickPacketParityPatches.applyBrowserLayerCursor(jar, root, "dev/gaius/parity/BrowserTickParityExecutors");
        }
        System.out.println(
                "Bounded Minecraft 26.2 chunk-generation layer at holder boundaries"
                        + " with pending macrotask continuation");
    }

    private static void addPrivateField(ClassNode node, String name, String descriptor) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(descriptor)) {
                return;
            }
        }
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                name,
                descriptor,
                null,
                null));
    }

    /**
     * Keeps the vanilla layer barrier intact while a browser cursor is active.
     *
     * <p>{@code waitForScheduledLayer()} may only run after every holder in the current layer has
     * been submitted. Waiting after the first cursor batch serializes the layer and can starve
     * progress; it is also unsafe if a pending holder depends on one not submitted yet. Re-enter
     * {@code scheduleNextLayer()} while the cursor is active. Return each batch future to
     * ChunkMap before another scheduler pulse; only the completed cursor reaches the vanilla
     * wait/status loop after its completion callback.
     */
    private static void patchRunUntilWaitYieldGate(MethodNode method, String owner) {
        LabelNode noYield = new LabelNode();
        LabelNode returnPending = new LabelNode();
        LabelNode continueVanilla = new LabelNode();
        JumpInsnNode originalBackedge = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof JumpInsnNode jump)
                    || jump.getOpcode() != Opcodes.GOTO
                    || method.instructions.indexOf(jump.label)
                            >= method.instructions.indexOf(jump)) {
                continue;
            }
            if (originalBackedge != null) {
                throw new IllegalStateException(
                        "ChunkGenerationTask.runUntilWait has multiple vanilla backedges");
            }
            originalBackedge = jump;
        }
        if (originalBackedge == null) {
            throw new IllegalStateException(
                    "ChunkGenerationTask.runUntilWait has no vanilla backedge");
        }
        // The active branch returns one pending future per bounded batch.  ChunkMap's existing
        // thenRun callback re-enters runGenerationTask after the macrotask completes.
        // The vanilla fresh path calls scheduleNextLayer and then reaches the original
        // task-layer pulse/backedge.  A newly published batch future must be returned before
        // that pulse: otherwise a fast Platform.startThread callback can complete it and the
        // method can continue inline through the same Java invocation.
        LabelNode activeResume = new LabelNode();
        method.instructions.insertBefore(originalBackedge, activeResume);
        InsnList freshReturn = new InsnList();
        freshReturn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        freshReturn.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        LabelNode noFreshYield = new LabelNode();
        freshReturn.add(new JumpInsnNode(Opcodes.IFNULL, noFreshYield));
        freshReturn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        freshReturn.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        freshReturn.add(new InsnNode(Opcodes.ARETURN));
        freshReturn.add(noFreshYield);
        method.instructions.insertBefore(activeResume, freshReturn);

        InsnList gate = new InsnList();
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        gate.add(new JumpInsnNode(Opcodes.IFNULL, noYield));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        gate.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture", "isDone",
                "()Z", false));
        gate.add(new JumpInsnNode(Opcodes.IFEQ, returnPending));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new InsnNode(Opcodes.ACONST_NULL));
        gate.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        gate.add(new JumpInsnNode(Opcodes.GOTO, noYield));
        gate.add(returnPending);
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        gate.add(new InsnNode(Opcodes.ARETURN));
        gate.add(noYield);
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        gate.add(new JumpInsnNode(Opcodes.IFEQ, continueVanilla));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "scheduleNextLayer", "()V", false));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        gate.add(new JumpInsnNode(Opcodes.IFNULL, continueVanilla));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        gate.add(new InsnNode(Opcodes.ARETURN));
        gate.add(continueVanilla);
        // Keep the original loop target label in front of the gate.  The vanilla
        // runUntilWait backedge jumps to that label, so inserting before the label
        // would let the backedge bypass the yield check on every subsequent holder.
        LabelNode entryLabel = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LabelNode label) { entryLabel = label; break; }
        }
        if (entryLabel == null) throw new IllegalStateException("ChunkGenerationTask.runUntilWait has no entry label for yield gate");
        method.instructions.insert(entryLabel, gate);
    }

    private static void replaceChunkGenerationScheduleNextLayer(
            MethodNode method, String owner) {
        String statusDescriptor = "L" + CHUNK_STATUS + ";";
        LabelNode resumeActive = new LabelNode();
        LabelNode freshStatus = new LabelNode();
        LabelNode existingStatus = new LabelNode();
        LabelNode nextStatus = new LabelNode();
        LabelNode computed = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();

        // Resume the same status when a holder cursor was split across browser turns.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, resumeActive));
        code.add(new JumpInsnNode(Opcodes.GOTO, freshStatus));

        code.add(resumeActive);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_STATUS, statusDescriptor));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_NEEDS_GENERATION, "Z"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "scheduleLayer",
                "(" + statusDescriptor + "Z)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_STATUS, statusDescriptor));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "scheduledStatus", statusDescriptor));
        code.add(new JumpInsnNode(Opcodes.GOTO, done));

        code.add(freshStatus);

        // This is the vanilla status-selection logic, kept in the task layer.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "scheduledStatus", statusDescriptor));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, existingStatus));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC, CHUNK_STATUS, "EMPTY", statusDescriptor));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, computed));

        code.add(existingStatus);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "needsGeneration", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, nextStatus));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "scheduledStatus", statusDescriptor));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC, CHUNK_STATUS, "EMPTY", statusDescriptor));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPNE, nextStatus));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "canLoadWithoutGeneration",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFNE, nextStatus));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "needsGeneration", "Z"));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC, CHUNK_STATUS, "EMPTY", statusDescriptor));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, computed));

        // The next generation status is selected exactly as in the named client.
        code.add(nextStatus);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                CHUNK_STATUS,
                "getStatusList",
                "()Ljava/util/List;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "scheduledStatus", statusDescriptor));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, CHUNK_STATUS, "getIndex", "()I", false));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "get",
                "(I)Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, CHUNK_STATUS));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, computed));

        code.add(computed);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_STATUS, statusDescriptor));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "needsGeneration", "Z"));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_NEEDS_GENERATION, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "needsGeneration", "Z"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "scheduleLayer",
                "(" + statusDescriptor + "Z)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "scheduledStatus", statusDescriptor));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 3, 2);
    }

    /**
     * Replaces the vanilla nested holder scan with a bounded task-layer cursor.  The holder
     * remains the minimum unit of work: no deep generation method is changed. Each invocation
     * submits at most {@link #BROWSER_HOLDERS_PER_TURN} holders and publishes a pending future.
     * The caller returns that future to ChunkMap, allowing the server to finish the current
     * task before a macrotask schedules its continuation. Existing holder pulses retain their
     * shared budget accounting. The layer barrier is reached only after every holder is submitted.
     */
    private static void replaceChunkGenerationScheduleLayer(
            MethodNode method, String owner) {
        String statusDescriptor = "L" + CHUNK_STATUS + ";";
        String holderDescriptor = "L" + GENERATION_HOLDER + ";";
        LabelNode resume = new LabelNode();
        LabelNode cancel = new LabelNode();
        LabelNode successful = new LabelNode();
        LabelNode nextColumn = new LabelNode();
        LabelNode continueBatch = new LabelNode();
        LabelNode scheduleYield = new LabelNode();
        LabelNode normalReturn = new LabelNode();
        LabelNode rethrow = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode start = new LabelNode();
        InsnList code = new InsnList();
        code.add(start);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 7));
        // A cancelled holder never enters the timed call; seed the probe token with its
        // disabled sentinel so the shared cleanup path can retain the original CFG.
        code.add(new LdcInsnNode(-1.0D));
        code.add(new VarInsnNode(Opcodes.DSTORE, 10));

        // Initialize the cursor only for a new status.  On resume, the saved x/z pair
        // already points at the next holder and the status arguments are unchanged.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, resume));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_STATUS, statusDescriptor));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_NEEDS_GENERATION, "Z"));

        // radius = getRadiusForLayer(status, needsGeneration)
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "getRadiusForLayer",
                "(" + statusDescriptor + "Z)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));

        // x = pos.x - radius; endX = pos.x + radius
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "pos", "L" + CHUNK_POS + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, CHUNK_POS, "x", "()I", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_X, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "pos", "L" + CHUNK_POS + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, CHUNK_POS, "x", "()I", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_END_X, "I"));

        // z = pos.z - radius; startZ/endZ are retained for column transitions.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "pos", "L" + CHUNK_POS + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, CHUNK_POS, "z", "()I", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_START_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "pos", "L" + CHUNK_POS + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, CHUNK_POS, "z", "()I", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_END_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));

        code.add(resume);
        // holder = cache.get(browserLayerX, browserLayerZ)
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "cache", "Lnet/minecraft/util/StaticCache2D;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_X, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_Z, "I"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/util/StaticCache2D",
                "get",
                "(II)Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, GENERATION_HOLDER));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));

        // Each holder gets a fresh token.  Without this reset, a later cancelled holder
        // could reuse the previous holder's enabled token and emit a false sample.
        code.add(new LdcInsnNode(-1.0D));
        code.add(new VarInsnNode(Opcodes.DSTORE, 10));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "markedForCancellation", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, cancel));
        // Keep the timing probe entirely outside the release telemetry path: the target call
        // still has its original result and exception behavior, while the scheduler helper
        // drops the sample when slow-probe telemetry is disabled.
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "beginChunkHolderProbe",
                "()D",
                false));
        code.add(new VarInsnNode(Opcodes.DSTORE, 10));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "scheduleChunkInLayer",
                "(" + statusDescriptor + "Z" + holderDescriptor + ")Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, cancel));
        // The original call result is consumed by the existing branch.  A successful
        // submission is represented explicitly for the opt-in probe after that branch.
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_X, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.DLOAD, 10));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "endChunkHolderProbe",
                "(Ljava/lang/Object;IIZDZ)V",
                false));
        code.add(new JumpInsnNode(Opcodes.GOTO, successful));

        code.add(cancel);
        // Cancellation and a rejected holder intentionally share this cleanup label.  The
        // The sentinel token makes this call a no-op for cancellation.  A holder rejected by
        // scheduleChunkInLayer retains its real start token and is recorded with result=false.
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_X, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.DLOAD, 10));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "endChunkHolderProbe",
                "(Ljava/lang/Object;IIZDZ)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new JumpInsnNode(Opcodes.GOTO, normalReturn));

        // Keep one inexpensive pulse at the holder boundary for scheduler telemetry.
        code.add(successful);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "pulse",
                "()V",
                false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 7));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 7));

        // Advance the cursor. The final holder also crosses the macrotask boundary so the next
        // layer cannot begin inline on the same server turn.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_Z, "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_END_Z, "I"));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPLE, nextColumn));

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_X, "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_X, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_START_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_END_X, "I"));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPLE, continueBatch));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
         code.add(new JumpInsnNode(Opcodes.GOTO, scheduleYield));

        code.add(nextColumn);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_Z, "I"));

        code.add(continueBatch);
        code.add(new VarInsnNode(Opcodes.ILOAD, 7));
        code.add(new LdcInsnNode(BROWSER_HOLDERS_PER_TURN));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPLT, resume));
        code.add(scheduleYield);
        // One future per bounded batch. The helper must enter a TeaVM native-thread
        // continuation before completing the future: Platform.schedule(0) invokes a
        // PlatformRunnable from a raw timer callback, so a thenRun continuation that
        // suspends would have no current TeaVM thread. startThread keeps the same
        // macrotask boundary while installing the continuation context.
        code.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/CompletableFuture"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/concurrent/CompletableFuture", "<init>",
                "()V", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 8));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 8));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        code.add(new TypeInsnNode(Opcodes.NEW, CHUNK_GENERATION_YIELD));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 8));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, CHUNK_GENERATION_YIELD, "<init>",
                "(Ljava/util/concurrent/CompletableFuture;)V", false));
        code.add(startThreadCall());
        code.add(new JumpInsnNode(Opcodes.GOTO, normalReturn));

        code.add(normalReturn);
        code.add(tryEnd);
        code.add(new InsnNode(Opcodes.RETURN));
        // A holder or scheduler failure must not leave a live cursor attached to the task.
        code.add(handler);
        code.add(new VarInsnNode(Opcodes.ASTORE, 9));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        code.add(rethrow);
        code.add(new VarInsnNode(Opcodes.ALOAD, 9));
        code.add(new InsnNode(Opcodes.ATHROW));
        replace(method, code, 5, 15);
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                start, tryEnd, handler, "java/lang/Throwable"));
    }

    /**
     * Starts the batch continuation on a new cooperative thread: TeaVM's
     * {@code Platform.startThread} for the JavaScript target, which the WebAssembly GC target
     * does not support, and {@code HostLoop.startThread} (a daemon fiber) there.
     */
    private static MethodInsnNode startThreadCall() {
        if (BuildTarget.wasmGc()) {
            return new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "dev/gaius/browser/HostLoop", "startThread",
                    "(Ljava/lang/Runnable;)V", false);
        }
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC, "org/teavm/platform/Platform", "startThread",
                "(Lorg/teavm/platform/PlatformRunnable;)V", false);
    }

    private static void writeChunkGenerationYieldHelper(Path root) throws IOException {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = CHUNK_GENERATION_YIELD;
        node.superName = "java/lang/Object";
        node.interfaces.add(BuildTarget.wasmGc()
                ? "java/lang/Runnable" : "org/teavm/platform/PlatformRunnable");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "future",
                "Ljava/util/concurrent/CompletableFuture;",
                null,
                null));

        MethodNode constructor = new MethodNode(
                Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/util/concurrent/CompletableFuture;)V", null, null);
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, CHUNK_GENERATION_YIELD, "future",
                "Ljava/util/concurrent/CompletableFuture;"));
        constructorCode.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, constructorCode, 2, 2);
        node.methods.add(constructor);

        MethodNode run = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        InsnList runCode = new InsnList();
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, CHUNK_GENERATION_YIELD, "future",
                "Ljava/util/concurrent/CompletableFuture;"));
        runCode.add(new InsnNode(Opcodes.ACONST_NULL));
        runCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture", "complete",
                "(Ljava/lang/Object;)Z", false));
        runCode.add(new InsnNode(Opcodes.POP));
        runCode.add(new InsnNode(Opcodes.RETURN));
        replace(run, runCode, 2, 1);
        node.methods.add(run);
        writeComputeFrames(node, root.resolve(CHUNK_GENERATION_YIELD + ".class"));
    }

    private static MethodInsnNode browserWorldgenBeginTaskWork() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "beginTaskWork",
                "(Ljava/lang/String;)I",
                false);
    }

    private static MethodInsnNode browserWorldgenEndTaskWork() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "endTaskWork",
                "(I)V",
                false);
    }

    /**
     * Scope only the task-layer method.  A pending future returns before the
     * server's waitUntilNextTick path, so closing the scope on every ARETURN is
     * what keeps that wait's wall-clock idle time out of the scheduler slice.
     */
    private static void instrumentBrowserTaskScope(
            MethodNode method, String target, int returnOpcode) {
        if (method.instructions.getFirst() == null) {
            throw new IllegalStateException(target + " has no instructions");
        }

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        int taskScopeLocal = method.maxLocals++;
        int throwableLocal = method.maxLocals++;
        InsnList entry = new InsnList();
        if (BuildTarget.wasmGc()) {
            LabelNode statusLabel = new LabelNode();
            LabelNode taskLabelReady = new LabelNode();
            int statusTelemetryLocal = method.maxLocals++;
            entry.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, WORLDGEN_SCHEDULER,
                    "worldgenStatusTelemetryEnabled", "()Z", false));
            entry.add(new InsnNode(Opcodes.DUP));
            entry.add(new VarInsnNode(Opcodes.ISTORE, statusTelemetryLocal));
            entry.add(new JumpInsnNode(Opcodes.IFEQ, statusLabel));
            entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
            entry.add(new FieldInsnNode(
                    Opcodes.GETFIELD, CHUNK_GENERATION_TASK, "targetStatus",
                    "L" + CHUNK_STATUS + ";"));
            entry.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, CHUNK_STATUS, "getName",
                    "()Ljava/lang/String;", false));
            entry.add(new JumpInsnNode(Opcodes.GOTO, taskLabelReady));
            entry.add(statusLabel);
            entry.add(new LdcInsnNode(target));
            entry.add(taskLabelReady);
            entry.add(new VarInsnNode(Opcodes.ILOAD, statusTelemetryLocal));
            entry.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, WORLDGEN_SCHEDULER, "beginTaskWork",
                    "(Ljava/lang/String;Z)I", false));
        } else {
            entry.add(new LdcInsnNode(target));
            entry.add(browserWorldgenBeginTaskWork());
        }
        entry.add(new VarInsnNode(Opcodes.ISTORE, taskScopeLocal));
        // The catch range starts only after beginTaskWork has initialized the
        // per-invocation token.  This also keeps cleanup valid if the first
        // task-layer instruction throws.
        entry.add(start);
        method.instructions.insertBefore(method.instructions.getFirst(), entry);

        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != returnOpcode) {
                continue;
            }
            InsnList close = new InsnList();
            if (target.equals("ChunkGenerationTask.runUntilWait")) {
                // The dispatcher may continue immediately only when the generation returned a
                // completed future. A pending future owns a continuation and must end the turn.
                // Vanilla returns null after releaseClaim() once the task reached its target
                // status, so the recorder must accept null (finished, nothing pending); calling
                // isDone() on that null traps the whole server thread on the Wasm GC target.
                close.add(new InsnNode(Opcodes.DUP));
                close.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        WORLDGEN_SCHEDULER,
                        "recordGenerationResult",
                        "(Ljava/util/concurrent/CompletableFuture;)V",
                        false));
            }
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
        method.maxStack = Math.max(method.maxStack, 2);
        System.out.println("Instrumented " + target + " active-work scope: returns=" + returns);
    }

    /**
     * The scheduler clock is owned by MinecraftServer's work-turn boundary. Do not let a
     * future 26.2 task patch reset it for every runUntilWait invocation: several tasks can run
     * in one tick and must consume one cumulative pulse budget.
     */
    private static void requireNoServerWorkTurnReset(String target, MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(WORLDGEN_SCHEDULER)
                    && call.name.equals("beginServerWorkTurn")
                    && call.desc.equals("()V")) {
                throw new IllegalStateException(
                        target + " must not reset the shared server work-turn clock");
            }
        }
    }
}
