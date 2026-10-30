package dev.gaius.tools.patches.worldgen;

import static dev.gaius.tools.patches.support.LegacyPatchSupport.find;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.read;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.replace;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.write;

import dev.gaius.tools.BuildTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The worldgen domain of the Minecraft12111BrowserPatcher step, the {@code legacy-12111} patch
 * set arm ({@code patches/worldgen}): chunk generation task cooperation (layer
 * scheduling and the pending-batch yield gate).
 *
 * <p>{@code Minecraft12111BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft12111BrowserPatcher.<method>}); the ASM helpers that
 * other domains of that step share live in LegacyPatchSupport and are imported
 * statically.
 */
public final class LegacyWorldgenPatches {
    private LegacyWorldgenPatches() {
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
        patchRunUntilWaitYieldGate(runUntilWait, owner);
        replaceChunkGenerationScheduleNextLayer(
                find(node, "scheduleNextLayer", "()V"), owner);
        replaceChunkGenerationScheduleLayer(
                find(node, "scheduleLayer",
                        "(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Z)V"),
                owner);

        // Recompute StackMapTable frames before emitting the overlaid class;
        // the cursor has catch-all cleanup and the yield gate adds new control
        // flow on both ARETURN and ATHROW paths.
        writeComputeFrames(node, root.resolve(owner + ".class"));
        writeChunkGenerationYieldHelper(root);
        System.out.println(
                "Bounded Minecraft 1.21.11 chunk-generation layer at holder boundaries");
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

    private static void patchRunUntilWaitYieldGate(MethodNode method, String owner) {
        LabelNode noYield = new LabelNode();
        LabelNode returnPending = new LabelNode();
        LabelNode continueVanilla = new LabelNode();
        InsnList gate = new InsnList();
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        gate.add(new JumpInsnNode(Opcodes.IFNULL, noYield));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        gate.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/CompletableFuture",
                "isDone",
                "()Z",
                false));
        gate.add(new JumpInsnNode(Opcodes.IFEQ, returnPending));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new InsnNode(Opcodes.ACONST_NULL));
        gate.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        gate.add(new JumpInsnNode(Opcodes.GOTO, noYield));
        gate.add(returnPending);
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        gate.add(new InsnNode(Opcodes.ARETURN));
        gate.add(noYield);
        // Preserve vanilla's layer barrier: submit the whole cursor before waiting on any
        // scheduled holder future, otherwise a pending holder can wait on an unscheduled peer.
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        gate.add(new JumpInsnNode(Opcodes.IFEQ, continueVanilla));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "scheduleNextLayer", "()V", false));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        gate.add(new JumpInsnNode(Opcodes.IFNULL, continueVanilla));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                BROWSER_LAYER_YIELD,
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
        if (entryLabel == null) {
            throw new IllegalStateException("ChunkGenerationTask.runUntilWait has no entry label for yield gate");
        }
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
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode start = new LabelNode();
        InsnList code = new InsnList();
        code.add(start);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 7));

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
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, CHUNK_POS, "x", "I"));
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
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, CHUNK_POS, "x", "I"));
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
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, CHUNK_POS, "z", "I"));
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
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, CHUNK_POS, "z", "I"));
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

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "markedForCancellation", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, cancel));
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
        code.add(new JumpInsnNode(Opcodes.GOTO, successful));

        code.add(cancel);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        code.add(new JumpInsnNode(Opcodes.GOTO, normalReturn));

        // The checkpoint-only profile yields only through the holder continuation below.
        code.add(successful);
        code.add(new VarInsnNode(Opcodes.ILOAD, 7));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 7));

        // Advance the cursor.  The final holder marks the layer complete but still
        // schedules a continuation so the following layer starts on a later turn.
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
        // browserLayerYield = new CompletableFuture<>();
        code.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/CompletableFuture"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/concurrent/CompletableFuture",
                "<init>",
                "()V",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 8));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 8));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        code.add(new TypeInsnNode(Opcodes.NEW, CHUNK_GENERATION_YIELD));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 8));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                CHUNK_GENERATION_YIELD,
                "<init>",
                "(Ljava/util/concurrent/CompletableFuture;)V",
                false));
        // Completing the future can resume a suspending continuation. A raw timer
        // lacks the TeaVM native thread context required by that continuation.
        code.add(startThreadCall());
        code.add(new JumpInsnNode(Opcodes.GOTO, normalReturn));

        code.add(normalReturn);
        code.add(tryEnd);
        code.add(new InsnNode(Opcodes.RETURN));
        // A holder or thread-start failure must not leave a live cursor or a
        // never-completing browser future attached to the task.
        code.add(handler);
        code.add(new VarInsnNode(Opcodes.ASTORE, 9));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                BROWSER_LAYER_YIELD,
                "Ljava/util/concurrent/CompletableFuture;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 9));
        code.add(new InsnNode(Opcodes.ATHROW));
        replace(method, code, 5, 10);
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                start, tryEnd, handler, "java/lang/Throwable"));
    }

    /**
     * Starts the batch continuation on a new cooperative thread: TeaVM's
     * {@code Platform.startThread} for the JavaScript target, which the WebAssembly GC target
     * does not support, and {@code HostLoop.startThread} (a daemon fiber) there, as the 26.x
     * patch does ({@code Minecraft262BrowserPatcher.startThreadCall}).
     */
    private static MethodInsnNode startThreadCall() {
        if (BuildTarget.wasmGc()) {
            return new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "dev/gaius/browser/HostLoop", "startThread",
                    "(Ljava/lang/Runnable;)V", false);
        }
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/teavm/platform/Platform",
                "startThread",
                "(Lorg/teavm/platform/PlatformRunnable;)V",
                false);
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
                Opcodes.ACC_PUBLIC,
                "<init>",
                "(Ljava/util/concurrent/CompletableFuture;)V",
                null,
                null);
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                CHUNK_GENERATION_YIELD,
                "future",
                "Ljava/util/concurrent/CompletableFuture;"));
        constructorCode.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, constructorCode, 2, 2);
        node.methods.add(constructor);

        MethodNode run = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "run",
                "()V",
                null,
                null);
        InsnList runCode = new InsnList();
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                CHUNK_GENERATION_YIELD,
                "future",
                "Ljava/util/concurrent/CompletableFuture;"));
        runCode.add(new InsnNode(Opcodes.ACONST_NULL));
        runCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/CompletableFuture",
                "complete",
                "(Ljava/lang/Object;)Z",
                false));
        runCode.add(new InsnNode(Opcodes.POP));
        runCode.add(new InsnNode(Opcodes.RETURN));
        replace(run, runCode, 2, 1);
        node.methods.add(run);

        writeComputeFrames(node, root.resolve(CHUNK_GENERATION_YIELD + ".class"));
    }

    private static void writeComputeFrames(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
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
