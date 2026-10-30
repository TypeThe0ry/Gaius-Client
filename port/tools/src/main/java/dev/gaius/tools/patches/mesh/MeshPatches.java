package dev.gaius.tools.patches.mesh;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findNullable;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findOrCreateMethod;
import static dev.gaius.tools.patches.support.ClientPatchSupport.isModernProfile;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.renderName;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.PatchRegistry;
import dev.gaius.tools.VersionProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
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
 * The mesh domain of the MinecraftClientPatcher step ({@code patches/mesh}): section
 * compile scheduling and throttles, the section task queue priorities, neighbor readiness, the
 * section vertex heaps, compiled mesh buffer reuse and the vertex builders' fast paths.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically. The mesh kernel
 * hook is {@link MeshKernelPatches}.
 */
public final class MeshPatches {
    private MeshPatches() {
    }

    private static final double BROWSER_SECTION_QUEUE_DISTANCE_EPSILON = 1.0E-6D;

    private static final double BROWSER_SECTION_QUEUE_REBUILD_DISTANCE = 16.0D;

    private static final int BROWSER_SECTION_UPLOAD_BUDGET = 8;

    private static final int BROWSER_SECTION_CLOSE_BUDGET = 16;

    private static final int BROWSER_SECTION_VERTEX_HEAP_BYTES = 16 * 1024 * 1024;

    private static final int BROWSER_SECTION_INDEX_HEAP_BYTES = 4 * 1024 * 1024;

    private static final int BROWSER_SECTION_STAGING_BYTES = 16 * 1024 * 1024;

    private static final String TERRAIN_BATCH = "dev/gaius/browser/render/BrowserTerrainBatch";

    private static final String NEIGHBOR_READINESS =
            "dev/gaius/browser/render/BrowserNeighborReadiness";

    /** Legacy profiles only: {@link MeshKernelPatches} leaves the section compiler alone. */
    public static void meshKernelPatches(String jar, Path root, String minecraftVersion)
            throws IOException {
        if (MeshKernelPatches.apply(jar, root, minecraftVersion)) {
            throw new IllegalStateException(
                    "Minecraft " + minecraftVersion + " must keep the vanilla section compiler");
        }
    }

    public static void patchRenderSectionRegionBrowserDirectSectionCoordinates(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/renderer/chunk/RenderSectionRegion";
        ClassNode node = read(jar, owner + ".class");
        int replacements = 0;
        for (String[] target : new String[][] {
                {"getBlockState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"},
                {"getFluidState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"},
                {"getBlockEntity", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"}
        }) {
            MethodNode method = find(node, target[0], target[1]);
            int methodReplacements = replaceSectionCoordinateCalls(
                    method, "blockToSectionCoord", Opcodes.ICONST_4, Opcodes.ISHR);
            if (methodReplacements != 3) {
                throw new IllegalStateException(
                        owner + "." + target[0]
                                + " expected 3 blockToSectionCoord calls, found "
                                + methodReplacements);
            }
            replacements += methodReplacements;
        }
        if (replacements != 9) {
            throw new IllegalStateException(
                    "RenderSectionRegion expected 9 direct section coordinate replacements, found "
                            + replacements);
        }
        writeComputeFrames(node, output);
    }

    public static void patchSectionCompilerBrowserDirectRelativeCoordinates(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/renderer/chunk/SectionCompiler";
        ClassNode node = read(jar, owner + ".class");
        MethodNode compile = find(
                node,
                "compile",
                "(Lnet/minecraft/core/SectionPos;"
                        + "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;"
                        + "Lcom/mojang/blaze3d/vertex/VertexSorting;"
                        + "Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)"
                        + "Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;");
        int replacements = replaceSectionCoordinateCalls(
                compile, "sectionRelative", Opcodes.BIPUSH, Opcodes.IAND);
        if (replacements != 3) {
            throw new IllegalStateException(
                    "SectionCompiler.compile expected 3 sectionRelative calls, found "
                            + replacements);
        }
        writeComputeFrames(node, output);
    }

    /**
     * Lets a lit FULL center chunk compile before sparse browser delivery has supplied all eight
     * horizontal neighbors. Vanilla invalidates the surrounding section range when another chunk's
     * light becomes ready, so provisional edge meshes are rebuilt when those neighbors arrive.
     *
     * <p>26.2 and 26.3 keep vanilla's eight-neighbor check as gaius$allNeighborsReady and route
     * hasAllNeighbors through BrowserNeighborReadiness.ready(center, allNeighbors, node), which
     * compiles when all neighbors are ready, when the section is within two chunks of the player
     * or when a neighbor has been missing for 300 ms; that removes most of the up to nine
     * compiles a streaming section got under the unconditional center-only gate. 1.21.11 keeps
     * the center-only gate.</p>
     */
    public static void patchSectionNeighborReadiness(
            String jar, Path outputRoot, String minecraftVersion) throws IOException {
        boolean current;
        if (isModernProfile(minecraftVersion)) {
            current = true;
        } else if (VersionProfile.forId(minecraftVersion).legacyPatchSet()) {
            current = false;
        } else {
            throw new IOException("Unsupported section readiness profile " + minecraftVersion);
        }
        String owner = current
                ? "net/minecraft/client/SectionUpdateTracker"
                : "net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection";
        String entry = owner + ".class";
        ClassNode node = read(jar, entry);
        String gateDescriptor = current
                ? "(Lnet/minecraft/client/multiplayer/ClientLevel;J)Z"
                : "()Z";
        String readinessDescriptor = current
                ? "(Lnet/minecraft/client/multiplayer/ClientLevel;J)Z"
                : "(J)Z";
        MethodNode gate = requireSingleMethod(node, "hasAllNeighbors", gateDescriptor);
        MethodNode readiness = requireSingleMethod(node, "doesChunkExistAt", readinessDescriptor);

        int readinessCalls = 0;
        int directionOffsets = 0;
        int diagonalOffsets = 0;
        int conditionalFailures = 0;
        int sectionNodeReads = 0;
        int cardinalDirections = 0;
        for (AbstractInsnNode instruction : gate.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(owner)
                        && call.name.equals("doesChunkExistAt")
                        && call.desc.equals(readinessDescriptor)) {
                    readinessCalls++;
                } else if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("net/minecraft/core/SectionPos")
                        && call.name.equals("offset")) {
                    if (call.desc.equals("(JLnet/minecraft/core/Direction;)J")) {
                        directionOffsets++;
                    } else if (call.desc.equals("(JIII)J")) {
                        diagonalOffsets++;
                    }
                }
            } else if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IFEQ) {
                conditionalFailures++;
            } else if (instruction instanceof FieldInsnNode field) {
                if (field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals("net/minecraft/core/Direction")
                        && field.desc.equals("Lnet/minecraft/core/Direction;")
                        && (field.name.equals("WEST") || field.name.equals("NORTH")
                                || field.name.equals("EAST") || field.name.equals("SOUTH"))) {
                    cardinalDirections++;
                }
                if (!current
                        && field.getOpcode() == Opcodes.GETFIELD
                        && field.owner.equals(owner)
                        && field.name.equals("sectionNode")
                        && field.desc.equals("J")) {
                    sectionNodeReads++;
                }
            }
        }
        if (readinessCalls != 8 || directionOffsets != 4 || diagonalOffsets != 4
                || conditionalFailures != 8 || cardinalDirections != 4
                || (!current && sectionNodeReads != 8)) {
            throw new IOException("Section neighbor readiness shape changed for " + minecraftVersion
                    + ": readinessCalls=" + readinessCalls
                    + ", directionOffsets=" + directionOffsets
                    + ", diagonalOffsets=" + diagonalOffsets
                    + ", conditionalFailures=" + conditionalFailures
                    + ", cardinalDirections=" + cardinalDirections
                    + ", sectionNodeReads=" + sectionNodeReads);
        }

        int chunkLookups = 0;
        int lightLookups = 0;
        for (AbstractInsnNode instruction : readiness.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/multiplayer/ClientLevel")
                    && call.name.equals("getChunk")
                    && call.desc.equals("(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                            + "Lnet/minecraft/world/level/chunk/ChunkAccess;")) {
                chunkLookups++;
            } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/world/level/lighting/LevelLightEngine")
                    && call.name.equals("lightOnInColumn")
                    && call.desc.equals("(J)Z")) {
                lightLookups++;
            }
        }
        if (chunkLookups != 1 || lightLookups != 1) {
            throw new IOException("Center chunk readiness helper changed for " + minecraftVersion
                    + ": chunkLookups=" + chunkLookups + ", lightLookups=" + lightLookups);
        }

        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (current) {
            String vanillaGate = "gaius$allNeighborsReady";
            if (findNullable(node, vanillaGate, gateDescriptor) != null) {
                throw new IOException("SectionUpdateTracker already has " + vanillaGate);
            }
            MethodNode vanilla = new MethodNode(
                    Opcodes.ACC_PRIVATE, vanillaGate, gateDescriptor, null, null);
            gate.accept(vanilla);
            vanilla.access = Opcodes.ACC_PRIVATE;
            vanilla.name = vanillaGate;
            node.methods.add(vanilla);
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new VarInsnNode(Opcodes.LLOAD, 2));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    "doesChunkExistAt",
                    readinessDescriptor,
                    false));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new VarInsnNode(Opcodes.LLOAD, 2));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    owner,
                    vanillaGate,
                    gateDescriptor,
                    false));
            code.add(new VarInsnNode(Opcodes.LLOAD, 2));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    NEIGHBOR_READINESS,
                    "ready",
                    "(ZZJ)Z",
                    false));
            code.add(new InsnNode(Opcodes.IRETURN));
            replace(gate, code, 6, 4);
            writeComputeFrames(node, outputRoot.resolve(entry));
            System.out.println("Patched section neighbor readiness: all neighbors, nearby, or "
                    + "300 ms missing grace");
            return;
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "sectionNode", "J"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "doesChunkExistAt",
                readinessDescriptor,
                false));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(gate, code, 3, 1);
        writeComputeFrames(node, outputRoot.resolve(entry));
        System.out.println("Section neighbor readiness: " + minecraftVersion
                + " keeps the center-only gate (the timed neighbor gate covers 26.2 and 26.3)");
    }

    private static MethodNode requireSingleMethod(
            ClassNode node, String name, String descriptor) throws IOException {
        MethodNode match = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                match = method;
                matches++;
            }
        }
        if (matches != 1) {
            throw new IOException(node.name + "." + name + descriptor
                    + " expected exactly once but found " + matches);
        }
        return match;
    }

    private static int replaceSectionCoordinateCalls(
            MethodNode method, String name, int constantOpcode, int operationOpcode) {
        int replacements = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !"net/minecraft/core/SectionPos".equals(call.owner)
                    || !name.equals(call.name)
                    || !"(I)I".equals(call.desc)) {
                continue;
            }
            InsnList direct = new InsnList();
            if (constantOpcode == Opcodes.BIPUSH) {
                direct.add(new IntInsnNode(Opcodes.BIPUSH, 15));
            } else {
                direct.add(new InsnNode(constantOpcode));
            }
            direct.add(new InsnNode(operationOpcode));
            method.instructions.insertBefore(call, direct);
            method.instructions.remove(call);
            replacements++;
        }
        return replacements;
    }

    /**
     * Applies backpressure before 26.2 clears SectionDirtyState. Skipped sections therefore stay
     * dirty and are extracted again on the next frame instead of disappearing from the pipeline.
     */
    public static void patchCurrentLevelExtractorBrowserSectionCompileThrottle(
            String jar,
            Path output) throws IOException {
        String entry = "net/minecraft/client/renderer/extract/LevelExtractor.class";
        boolean present;
        try (ZipFile input = new ZipFile(jar)) {
            present = input.getEntry(entry) != null;
        }
        if (!present) {
            if (ModernSymbols.cached(jar).renderpearl()) {
                throw new IllegalStateException("26.3 client has no " + entry);
            }
            return;
        }

        ClassNode node = read(jar, entry);
        MethodNode extract = find(
                node,
                "extract",
                "(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V");
        patchCurrentLevelExtractorBlockBreakingTelemetry(node);
        patchLevelExtractorBrowserConsumeFrustumBeforeCapturedGuard(node, extract);
        int patched = 0;
        for (AbstractInsnNode instruction : extract.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode add)
                    || add.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !add.owner.equals("java/util/List")
                    || !add.name.equals("add")
                    || !add.desc.equals("(Ljava/lang/Object;)Z")) {
                continue;
            }
            AbstractInsnNode creation = previousOpcode(add);
            if (!(creation instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !constructor.owner.equals(
                            "net/minecraft/client/renderer/state/level/SectionUpdateRenderState")
                    || !constructor.name.equals("<init>")) {
                continue;
            }
            AbstractInsnNode allocation = findPreviousNew(creation, constructor.owner);
            AbstractInsnNode listFieldInstruction = previousOpcode(allocation);
            if (!(listFieldInstruction instanceof FieldInsnNode listField)
                    || listField.getOpcode() != Opcodes.GETFIELD
                    || !listField.owner.equals(
                            "net/minecraft/client/renderer/state/level/LevelRenderState")
                    || !listField.name.equals("sectionUpdateRenderStates")
                    || !listField.desc.equals("Ljava/util/List;")) {
                throw new IllegalStateException(
                        "Current section update list load shape changed");
            }
            AbstractInsnNode levelStateFieldInstruction = previousOpcode(listField);
            AbstractInsnNode thisLoadInstruction = previousOpcode(levelStateFieldInstruction);
            if (!(levelStateFieldInstruction instanceof FieldInsnNode levelStateField)
                    || levelStateField.getOpcode() != Opcodes.GETFIELD
                    || !levelStateField.owner.equals(node.name)
                    || !levelStateField.name.equals("levelRenderState")
                    || !(thisLoadInstruction instanceof VarInsnNode thisLoad)
                    || thisLoad.getOpcode() != Opcodes.ALOAD
                    || thisLoad.var != 0) {
                throw new IllegalStateException(
                        "Current section update list owner load shape changed");
            }

            AbstractInsnNode setNotDirty = nextOpcode(add);
            while (setNotDirty != null
                    && (!(setNotDirty instanceof MethodInsnNode call)
                            || !call.owner.equals(
                                    "net/minecraft/client/SectionUpdateTracker$SectionDirtyState")
                            || !call.name.equals("setNotDirty")
                            || !call.desc.equals("()V"))) {
                setNotDirty = nextOpcode(setNotDirty);
            }
            if (setNotDirty == null) {
                throw new IllegalStateException(
                        "Current section dirty clear after update extraction was not found");
            }

            LabelNode retryNextFrame = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            guard.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    node.name,
                    "levelRenderState",
                    "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"));
            guard.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/renderer/state/level/LevelRenderState",
                    "sectionUpdateRenderStates",
                    "Ljava/util/List;"));
            guard.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/List",
                    "size",
                    "()I",
                    true));
            guard.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "canScheduleSection",
                    "(I)Z",
                    false));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, retryNextFrame));
            extract.instructions.insertBefore(thisLoad, guard);
            extract.instructions.insert(setNotDirty, retryNextFrame);
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "Current section dirty-preserving throttle changed: " + patched);
        }
        boolean trackerField = node.fields.stream().anyMatch(field ->
                field.name.equals("sectionUpdateTracker")
                        && field.desc.equals("Lnet/minecraft/client/SectionUpdateTracker;"));
        if (!trackerField) {
            throw new IllegalStateException("LevelExtractor section update tracker field changed");
        }
        // Before LevelRenderState.reset() drops them, put updates that the previous frame
        // extracted but never compiled back into the dirty set (see LevelRenderer.compileSections).
        InsnList requeue = new InsnList();
        requeue.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requeue.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                node.name,
                "levelRenderState",
                "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"));
        requeue.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requeue.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                node.name,
                "sectionUpdateTracker",
                "Lnet/minecraft/client/SectionUpdateTracker;"));
        requeue.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserSectionAudit",
                "requeueUnconsumed",
                "(Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                        + "Lnet/minecraft/client/SectionUpdateTracker;)V",
                false));
        extract.instructions.insert(requeue);
        extract.maxStack = Math.max(extract.maxStack, 2);
        int audits = 0;
        for (AbstractInsnNode instruction : extract.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList audit = new InsnList();
            audit.add(new VarInsnNode(Opcodes.ALOAD, 0));
            audit.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    node.name,
                    "sectionUpdateTracker",
                    "Lnet/minecraft/client/SectionUpdateTracker;"));
            audit.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserSectionAudit",
                    "afterExtract",
                    "(Lnet/minecraft/client/SectionUpdateTracker;)V",
                    false));
            extract.instructions.insertBefore(instruction, audit);
            audits++;
        }
        if (audits == 0) {
            throw new IllegalStateException("LevelExtractor.extract has no return for the section audit");
        }
        requireLevelRendererUpdateConsumption(output);
        writeComputeFrames(node, output);
        System.out.println("Patched current section extraction with dirty-preserving backpressure");
    }

    /**
     * requeueUnconsumed re-dirties every extracted update that LevelRenderer.compileSections did
     * not mark consumed. Without that consumption hook each update would be recompiled on every
     * frame, so the requeue half may only be written next to a LevelRenderer that carries it:
     * the LevelRenderer patch runs earlier in main() and writes into the same output root.
     */
    private static void requireLevelRendererUpdateConsumption(Path levelExtractorOutput)
            throws IOException {
        Path levelRenderer = levelExtractorOutput.toAbsolutePath().getParent().getParent()
                .resolve("LevelRenderer.class");
        if (!Files.isRegularFile(levelRenderer)) {
            throw new IllegalStateException("LevelExtractor requeue needs the LevelRenderer "
                    + "compileSections consumption hook, but " + levelRenderer
                    + " was not patched (patchLevelRendererBrowserBlockBreakProgress)");
        }
        MethodNode compile = find(read(levelRenderer), "compileSections",
                "(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V");
        int consumptions = 0;
        for (AbstractInsnNode instruction : compile.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(
                            "net/minecraft/client/renderer/state/level/LevelRenderState")
                    && field.name.equals("sectionUpdateRenderStates")
                    && nextOpcode(field) instanceof MethodInsnNode clear
                    && clear.owner.equals("java/util/List")
                    && clear.name.equals("clear")) {
                consumptions++;
            }
        }
        if (consumptions != 1) {
            throw new IllegalStateException("LevelExtractor requeue and LevelRenderer update "
                    + "consumption must be applied together; consumption hooks=" + consumptions);
        }
    }

    /**
     * The captured-frustum fast path skips the normal consume/apply branch.  Browser camera
     * movement commonly changes only position, so force the graph's pending update through the
     * same applyFrustum path before that guard.  This preserves the normal frustum and section
     * lists while avoiding any render-distance or texture-quality changes.
     */
    private static void patchLevelExtractorBrowserConsumeFrustumBeforeCapturedGuard(
            ClassNode node, MethodNode extract) {
        MethodInsnNode captured = null;
        for (AbstractInsnNode instruction : extract.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/Camera")
                    && call.name.equals("getCapturedFrustum")
                    && call.desc.equals("()Lnet/minecraft/client/renderer/culling/Frustum;")) {
                if (captured != null) {
                    throw new IllegalStateException(
                            "LevelExtractor has multiple captured-frustum guards");
                }
                captured = call;
            }
        }
        if (captured == null) {
            throw new IllegalStateException(
                    "LevelExtractor captured-frustum guard was not found");
        }
        AbstractInsnNode guard = nextOpcode(captured);
        if (!(guard instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNONNULL) {
            throw new IllegalStateException("LevelExtractor captured-frustum guard shape changed");
        }
        // The browser's captured frustum is retained across camera translation and makes
        // vanilla skip the existing consume/apply path. Remove only this stale-cache short
        // circuit; the normal graph and frustum implementation remains unchanged.
        // Drop the camera load that fed getCapturedFrustum together with the call. Leaving
        // that receiver strands one reference on the operand stack, so the merge after the
        // frustum block has inconsistent stack heights and the method fails verification.
        AbstractInsnNode receiver = previousOpcode(captured);
        if (!(receiver instanceof VarInsnNode cameraLoad)
                || cameraLoad.getOpcode() != Opcodes.ALOAD
                || cameraLoad.var != 2) {
            throw new IllegalStateException(
                    "LevelExtractor captured-frustum receiver is no longer the camera local 2");
        }
        extract.instructions.remove(receiver);
        extract.instructions.remove(captured);
        extract.instructions.remove(guard);
        System.out.println("Patched LevelExtractor captured-frustum short circuit");
    }

    private static void patchCurrentLevelExtractorBlockBreakingTelemetry(ClassNode node) {
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("extractBlockDestroyAnimation")
                        && candidate.desc.equals("(Lnet/minecraft/client/Camera;"
                                + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "LevelExtractor.extractBlockDestroyAnimation telemetry target was not found"));
        InsnList entry = new InsnList();
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBlockBreakingTelemetry",
                "recordExtraction", "()V", false));
        method.instructions.insert(entry);

        int added = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode add)
                    || add.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !add.owner.equals("java/util/List")
                    || !add.name.equals("add")
                    || !add.desc.equals("(Ljava/lang/Object;)Z")) {
                continue;
            }
            InsnList emitted = new InsnList();
            emitted.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserBlockBreakingTelemetry",
                    "recordEmitted", "()V", false));
            method.instructions.insertBefore(add, emitted);
            added++;
        }
        if (added != 1) {
            throw new IllegalStateException(
                    "Block-breaking extraction list shape changed: " + added);
        }
    }

    private static AbstractInsnNode findPreviousNew(
            AbstractInsnNode instruction,
            String owner) {
        for (AbstractInsnNode cursor = previousOpcode(instruction);
                cursor != null;
                cursor = previousOpcode(cursor)) {
            if (cursor instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals(owner)) {
                return cursor;
            }
        }
        throw new IllegalStateException("Constructor allocation was not found for " + owner);
    }

    public static void patchSectionRenderDispatcherBrowserThrottles(String jar, Path output)
            throws IOException {
        ClassNode node = read(
                jar,
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher.class");
        patchSectionRenderDispatcherBrowserExecutor(node);
        MethodNode currentUpload = node.methods.stream()
                .filter(candidate -> candidate.name.equals("uploadTerrainBuffersToGpu")
                        && candidate.desc.equals("()V"))
                .findFirst()
                .orElse(null);
        if (currentUpload != null) {
            PatchRegistry.runPart("MinecraftClientPatcher.sectionVertexHeaps",
                    "REQUIRED NOT_APPLICABLE js wasmgc", node,
                    () -> patchCurrentSectionRenderDispatcherBufferBudgets(node));
            patchCurrentSectionRenderDispatcherTelemetry(currentUpload);
            int uploadCalls = 0;
            for (AbstractInsnNode instruction : currentUpload.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("com/mojang/blaze3d/vertex/UberGpuBuffer")
                        && call.name.equals("uploadStagedAllocations")) {
                    uploadCalls++;
                }
            }
            if (uploadCalls != 2) {
                throw new IllegalStateException(
                        "Current terrain upload batching shape changed: " + uploadCalls);
            }
            System.out.println(
                    "Verified current staged terrain uploads behind the browser compile budget");
            writeComputeFrames(node, output);
            return;
        }
        PatchRegistry.runPart("MinecraftClientPatcher.sectionVertexHeaps",
                "REQUIRED NOT_APPLICABLE js wasmgc", node,
                () -> patchCurrentSectionRenderDispatcherBufferBudgets(node));
        MethodNode method = find(node, "uploadAllPendingUploads", "()V");
        InsnList code = new InsnList();
        LabelNode uploadLoop = new LabelNode();
        LabelNode uploadDone = new LabelNode();
        LabelNode closeLoop = new LabelNode();
        LabelNode closeDone = new LabelNode();

        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "beginUploadPass",
                "(I)V",
                false));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(uploadLoop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new IntInsnNode(Opcodes.BIPUSH, BROWSER_SECTION_UPLOAD_BUDGET));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, uploadDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher",
                "toUpload",
                "Ljava/util/Queue;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Queue",
                "poll",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Runnable"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new JumpInsnNode(Opcodes.IFNULL, uploadDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/lang/Runnable",
                "run",
                "()V",
                true));
        code.add(new IincInsnNode(1, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, uploadLoop));

        code.add(uploadDone);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(closeLoop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new IntInsnNode(Opcodes.BIPUSH, BROWSER_SECTION_CLOSE_BUDGET));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, closeDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher",
                "toClose",
                "Ljava/util/Queue;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Queue",
                "poll",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/renderer/chunk/SectionMesh"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new JumpInsnNode(Opcodes.IFNULL, closeDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/client/renderer/chunk/SectionMesh",
                "close",
                "()V",
                true));
        code.add(new IincInsnNode(3, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, closeLoop));
        code.add(closeDone);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "endUploadPass",
                "()V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));

        replace(method, code, 3, 5);
        writeComputeFrames(node, output);
    }

    private static void patchSectionRenderDispatcherBrowserExecutor(ClassNode node) {
        String currentConstructorDescriptor =
                "(Lnet/minecraft/TracingExecutor;"
                        + "Lnet/minecraft/client/renderer/RenderBuffers;"
                        + "Lnet/minecraft/client/renderer/chunk/SectionCompiler;"
                        + "Ljava/util/function/Consumer;)V";
        boolean current = node.methods.stream()
                .anyMatch(candidate -> candidate.name.equals("<init>")
                        && candidate.desc.equals(currentConstructorDescriptor));
        if (current) {
            MethodNode schedule = find(
                    node,
                    "schedule",
                    "(Lnet/minecraft/client/renderer/chunk/"
                            + "SectionRenderDispatcher$RenderSection$SectionTask;)V");
            MethodNode runTask = find(node, "runTask", "()V");
            MethodInsnNode scheduleCall = findTracingExecutorCall(schedule);
            MethodInsnNode continuationCall = findTracingExecutorCall(runTask);

            InsnList scheduleContext = new InsnList();
            scheduleContext.add(new VarInsnNode(Opcodes.ALOAD, 0));
            scheduleContext.add(new VarInsnNode(Opcodes.ALOAD, 0));
            scheduleContext.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    node.name,
                    "getCompileQueueSize",
                    "()I",
                    false));
            schedule.instructions.insertBefore(scheduleCall, scheduleContext);
            schedule.instructions.set(scheduleCall, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "scheduleDispatcher",
                    "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Object;I)V",
                    false));

            runTask.instructions.insertBefore(
                    continuationCall,
                    new VarInsnNode(Opcodes.ALOAD, 0));
            runTask.instructions.set(continuationCall, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "rememberDispatcherContinuation",
                    "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/lang/Object;)V",
                    false));

            int finishedReturns = 0;
            for (AbstractInsnNode instruction : runTask.instructions.toArray()) {
                if (instruction.getOpcode() != Opcodes.RETURN) {
                    continue;
                }
                InsnList finish = new InsnList();
                finish.add(new VarInsnNode(Opcodes.ALOAD, 0));
                finish.add(new VarInsnNode(Opcodes.ALOAD, 0));
                finish.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        node.name,
                        "getCompileQueueSize",
                        "()I",
                        false));
                finish.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserRenderScheduler",
                        "finishDispatcherRun",
                        "(Ljava/lang/Object;I)V",
                        false));
                runTask.instructions.insertBefore(instruction, finish);
                finishedReturns++;
            }
            if (finishedReturns != 3) {
                throw new IllegalStateException(
                        "Current section runner return shape changed: " + finishedReturns);
            }

            MethodNode dispose = find(node, "dispose", "()V");
            InsnList release = new InsnList();
            release.add(new VarInsnNode(Opcodes.ALOAD, 0));
            release.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "disposeDispatcher",
                    "(Ljava/lang/Object;)V",
                    false));
            dispose.instructions.insertBefore(dispose.instructions.getFirst(), release);
            System.out.println("Patched current section renderer with one lifecycle-owned runner");
            return;
        }

        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/client/multiplayer/ClientLevel;"
                        + "Lnet/minecraft/client/renderer/LevelRenderer;"
                        + "Lnet/minecraft/TracingExecutor;"
                        + "Lnet/minecraft/client/renderer/RenderBuffers;"
                        + "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;"
                        + "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;)V");
        MethodNode runTask = find(node, "runTask", "()V");
        int patched = 0;

        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals("net/minecraft/util/thread/ConsecutiveExecutor")
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(Ljava/util/concurrent/Executor;Ljava/lang/String;)V")) {
                continue;
            }
            AbstractInsnNode executorLoad = previousOpcode(previousOpcode(call));
            if (!(executorLoad instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD
                    || load.var != 3) {
                throw new IllegalStateException(
                        "SectionRenderDispatcher consecutive executor load was not found");
            }
            constructor.instructions.insert(executorLoad, deferBrowserRenderExecutor());
            patched++;
            break;
        }

        for (AbstractInsnNode instruction = runTask.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/TracingExecutor")
                    || !call.name.equals("forName")
                    || !call.desc.equals("(Ljava/lang/String;)Ljava/util/concurrent/Executor;")) {
                continue;
            }
            runTask.instructions.insert(call, deferBrowserRenderExecutor());
            patched++;
            break;
        }

        if (patched != 2) {
            throw new IllegalStateException(
                    "SectionRenderDispatcher browser deferred executor patch points were not found");
        }
    }

    private static MethodInsnNode findTracingExecutorCall(MethodNode method) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/TracingExecutor")
                    || !call.name.equals("execute")
                    || !call.desc.equals("(Ljava/lang/Runnable;)V")) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException(
                        method.name + method.desc + " has multiple TracingExecutor calls");
            }
            found = call;
        }
        if (found == null) {
            throw new IllegalStateException(
                    method.name + method.desc + " TracingExecutor call was not found");
        }
        return found;
    }

    public static void patchCurrentSectionTaskQueueBrowserPriorities(String jar, Path output)
            throws IOException {
        String entry = "net/minecraft/client/renderer/chunk/SectionTaskDynamicQueue.class";
        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry(entry) == null) {
                if (ModernSymbols.cached(jar).renderpearl()) {
                    throw new IllegalStateException("26.3 client has no " + entry);
                }
                return;
            }
        }
        ClassNode node = read(jar, entry);
        MethodNode poll = findNullable(
                node,
                "poll",
                "(Lnet/minecraft/world/phys/Vec3;)"
                        + "Lnet/minecraft/client/renderer/chunk/"
                        + "SectionRenderDispatcher$RenderSection$SectionTask;");
        if (poll == null) {
            if (ModernSymbols.cached(jar).renderpearl()) {
                throw new IllegalStateException("26.3 SectionTaskDynamicQueue has no poll(Vec3)");
            }
            return;
        }
        String owner = "net/minecraft/client/renderer/chunk/SectionTaskDynamicQueue";
        String task = "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher$RenderSection$SectionTask";
        String compileTask = "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher$RenderSection$CompileTask";
        String taskDescriptor = "L" + task + ";";
        String queueDescriptor = "Ljava/util/ArrayList;";
        String cameraDescriptor = "Lnet/minecraft/world/phys/Vec3;";

        FieldNode tasks = node.fields.stream()
                .filter(field -> field.name.equals("tasks")
                        && field.desc.equals("Ljava/util/List;"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Current section task list field shape changed"));
        tasks.desc = queueDescriptor;
        tasks.signature = "Ljava/util/ArrayList<" + taskDescriptor + ">;";
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserDirtyTasks",
                queueDescriptor,
                "Ljava/util/ArrayList<" + taskDescriptor + ">;",
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;",
                "Ljava/util/IdentityHashMap<" + taskDescriptor + "[J>;",
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserScratchTasks",
                "Ljava/util/ArrayList;",
                "Ljava/util/ArrayList<" + taskDescriptor + ">;",
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "browserCamera", cameraDescriptor, null, null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "browserNextOrder", "J", null, null));
        MethodNode constructor = find(node, "<init>", "()V");
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new InsnNode(Opcodes.ICONST_2));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "recompileQuota", "I"));
        addSectionTaskArrayListInitialization(
                constructorCode, owner, "tasks", queueDescriptor);
        addSectionTaskArrayListInitialization(
                constructorCode, owner, "browserDirtyTasks", queueDescriptor);
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new TypeInsnNode(Opcodes.NEW, "java/util/IdentityHashMap"));
        constructorCode.add(new InsnNode(Opcodes.DUP));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/IdentityHashMap",
                "<init>",
                "()V",
                false));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        constructorCode.add(new InsnNode(Opcodes.DUP));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "browserScratchTasks",
                "Ljava/util/ArrayList;"));
        constructorCode.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, constructorCode, 5, 1);

        MethodNode add = find(node, "add", "(" + taskDescriptor + ")V");
        InsnList addCode = new InsnList();
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserNextOrder", "J"));
        addCode.add(new VarInsnNode(Opcodes.LSTORE, 2));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new VarInsnNode(Opcodes.LLOAD, 2));
        addCode.add(new InsnNode(Opcodes.LCONST_1));
        addCode.add(new InsnNode(Opcodes.LADD));
        addCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserNextOrder", "J"));
        addCode.add(new InsnNode(Opcodes.ICONST_5));
        addCode.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG));
        addCode.add(new VarInsnNode(Opcodes.ASTORE, 4));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        addCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                task,
                "getRenderOrigin",
                "()Lnet/minecraft/core/BlockPos;",
                false));
        addCode.add(new VarInsnNode(Opcodes.ASTORE, 5));
        int coordinateIndex = 0;
        for (String coordinate : new String[] {"getX", "getY", "getZ"}) {
            addCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
            addCode.add(new InsnNode(Opcodes.ICONST_0 + coordinateIndex++));
            addCode.add(new VarInsnNode(Opcodes.ALOAD, 5));
            addCode.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/core/BlockPos",
                    coordinate,
                    "()I",
                    false));
            addCode.add(new InsnNode(Opcodes.I2L));
            addCode.add(new InsnNode(Opcodes.LASTORE));
        }
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        addCode.add(new InsnNode(Opcodes.ICONST_3));
        addCode.add(new VarInsnNode(Opcodes.LLOAD, 2));
        addCode.add(new InsnNode(Opcodes.LASTORE));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        addCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        addCode.add(new InsnNode(Opcodes.POP));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        addCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserUpdateDistance",
                "(" + taskDescriptor + ")V",
                false));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        addCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                "browserIsDirtyCompile",
                "(" + taskDescriptor + ")Z",
                false));
        LabelNode addRegular = new LabelNode();
        LabelNode addSelected = new LabelNode();
        addCode.add(new JumpInsnNode(Opcodes.IFEQ, addRegular));
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserDirtyTasks", queueDescriptor));
        addCode.add(new JumpInsnNode(Opcodes.GOTO, addSelected));
        addCode.add(addRegular);
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "tasks", queueDescriptor));
        addCode.add(addSelected);
        addCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        addCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserHeapAdd",
                "(" + queueDescriptor + taskDescriptor + ")V",
                false));
        addCode.add(new InsnNode(Opcodes.RETURN));
        replace(add, addCode, 6, 6);

        InsnList pollCode = new InsnList();
        LabelNode rebuildCamera = new LabelNode();
        LabelNode heapsReady = new LabelNode();
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserCamera", cameraDescriptor));
        pollCode.add(new JumpInsnNode(Opcodes.IFNULL, rebuildCamera));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserCamera", cameraDescriptor));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/phys/Vec3",
                "distanceToSqr",
                "(Lnet/minecraft/world/phys/Vec3;)D",
                false));
        pollCode.add(new VarInsnNode(Opcodes.DSTORE, 2));
        pollCode.add(new VarInsnNode(Opcodes.DLOAD, 2));
        pollCode.add(new LdcInsnNode(
                BROWSER_SECTION_QUEUE_REBUILD_DISTANCE
                        * BROWSER_SECTION_QUEUE_REBUILD_DISTANCE));
        pollCode.add(new InsnNode(Opcodes.DCMPG));
        pollCode.add(new JumpInsnNode(Opcodes.IFGE, rebuildCamera));
        pollCode.add(new JumpInsnNode(Opcodes.GOTO, heapsReady));
        pollCode.add(rebuildCamera);
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserCamera", cameraDescriptor));
        addSectionTaskQueueHelperCall(
                pollCode, owner, "tasks", "browserRebuild", "(" + queueDescriptor + ")V");
        addSectionTaskQueueHelperCall(
                pollCode,
                owner,
                "browserDirtyTasks",
                "browserRebuild",
                "(" + queueDescriptor + ")V");
        pollCode.add(new InsnNode(Opcodes.DCONST_0));
        pollCode.add(new VarInsnNode(Opcodes.DSTORE, 2));
        pollCode.add(heapsReady);
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserDirtyTasks", queueDescriptor));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new VarInsnNode(Opcodes.DLOAD, 2));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserTakeNearest",
                "(" + queueDescriptor + cameraDescriptor + "D)" + taskDescriptor,
                false));
        pollCode.add(new VarInsnNode(Opcodes.ASTORE, 4));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "tasks", queueDescriptor));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new VarInsnNode(Opcodes.DLOAD, 2));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserTakeNearest",
                "(" + queueDescriptor + cameraDescriptor + "D)" + taskDescriptor,
                false));
        pollCode.add(new VarInsnNode(Opcodes.ASTORE, 5));
        LabelNode chooseRegular = new LabelNode();
        LabelNode chooseDirty = new LabelNode();
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        pollCode.add(new JumpInsnNode(Opcodes.IFNULL, chooseRegular));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 5));
        pollCode.add(new JumpInsnNode(Opcodes.IFNULL, chooseDirty));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "recompileQuota", "I"));
        pollCode.add(new JumpInsnNode(Opcodes.IFLE, chooseRegular));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistanceFrom",
                "(" + taskDescriptor + cameraDescriptor + ")D",
                false));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 5));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistanceFrom",
                "(" + taskDescriptor + cameraDescriptor + ")D",
                false));
        pollCode.add(new InsnNode(Opcodes.DCMPG));
        pollCode.add(new JumpInsnNode(Opcodes.IFLT, chooseDirty));
        pollCode.add(chooseRegular);
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserDirtyTasks", queueDescriptor));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserRequeue",
                "(" + queueDescriptor + taskDescriptor + ")V",
                false));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new InsnNode(Opcodes.ICONST_2));
        pollCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "recompileQuota", "I"));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 5));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserFinishTask",
                "(" + taskDescriptor + ")" + taskDescriptor,
                false));
        pollCode.add(new InsnNode(Opcodes.ARETURN));
        pollCode.add(chooseDirty);
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "tasks", queueDescriptor));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 5));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserRequeue",
                "(" + queueDescriptor + taskDescriptor + ")V",
                false));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new InsnNode(Opcodes.DUP));
        pollCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "recompileQuota", "I"));
        pollCode.add(new InsnNode(Opcodes.ICONST_1));
        pollCode.add(new InsnNode(Opcodes.ISUB));
        pollCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "recompileQuota", "I"));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pollCode.add(new VarInsnNode(Opcodes.ALOAD, 4));
        pollCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserFinishTask",
                "(" + taskDescriptor + ")" + taskDescriptor,
                false));
        pollCode.add(new InsnNode(Opcodes.ARETURN));
        replace(poll, pollCode, 6, 6);

        MethodNode size = find(node, "size", "()I");
        InsnList sizeCode = new InsnList();
        sizeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        sizeCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "tasks", queueDescriptor));
        sizeCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        sizeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        sizeCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserDirtyTasks", queueDescriptor));
        sizeCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        sizeCode.add(new InsnNode(Opcodes.IADD));
        sizeCode.add(new InsnNode(Opcodes.IRETURN));
        replace(size, sizeCode, 2, 1);

        MethodNode clear = find(node, "clear", "()V");
        InsnList clearCode = new InsnList();
        addSectionTaskQueueHelperCall(
                clearCode,
                owner,
                "tasks",
                "browserCancelAndClear",
                "(" + queueDescriptor + ")V");
        addSectionTaskQueueHelperCall(
                clearCode,
                owner,
                "browserDirtyTasks",
                "browserCancelAndClear",
                "(" + queueDescriptor + ")V");
        clearCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        clearCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        clearCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "clear",
                "()V",
                false));
        clearCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        clearCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        clearCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "clear", "()V", false));
        clearCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        clearCode.add(new InsnNode(Opcodes.LCONST_0));
        clearCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserNextOrder", "J"));
        clearCode.add(new InsnNode(Opcodes.RETURN));
        replace(clear, clearCode, 3, 1);

        MethodNode removeByIndex = find(
                node, "removeTaskByIndex", "(I)" + taskDescriptor);
        node.methods.remove(removeByIndex);
        addSectionTaskQueueHelpers(
                node, owner, task, compileTask, taskDescriptor, queueDescriptor, cameraDescriptor);
        writeComputeFrames(node, output);
        System.out.println(
                "Patched current section queue with linear-rebuild dirty/regular heaps");
    }

    private static void addSectionTaskArrayListInitialization(
            InsnList code, String owner, String field, String queueDescriptor) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 11));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/ArrayList",
                "<init>",
                "(I)V",
                false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, queueDescriptor));
    }

    private static void addSectionTaskQueueHelperCall(
            InsnList code,
            String owner,
            String field,
            String method,
            String descriptor) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, field, "Ljava/util/ArrayList;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner, method, descriptor, false));
    }

    private static void addSectionTaskQueueHelpers(
            ClassNode node,
            String owner,
            String task,
            String compileTask,
            String taskDescriptor,
            String queueDescriptor,
            String cameraDescriptor) {
        MethodNode dirty = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "browserIsDirtyCompile",
                "(" + taskDescriptor + ")Z",
                null,
                null);
        LabelNode notDirty = new LabelNode();
        dirty.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dirty.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, task, "isRecompile", "()Z", false));
        dirty.instructions.add(new JumpInsnNode(Opcodes.IFEQ, notDirty));
        dirty.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dirty.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, compileTask));
        dirty.instructions.add(new InsnNode(Opcodes.IRETURN));
        dirty.instructions.add(notDirty);
        dirty.instructions.add(new InsnNode(Opcodes.ICONST_0));
        dirty.instructions.add(new InsnNode(Opcodes.IRETURN));
        dirty.maxStack = 1;
        dirty.maxLocals = 1;
        node.methods.add(dirty);

        MethodNode distance = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserDistance",
                "(" + taskDescriptor + ")D",
                null,
                null);
        distance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        distance.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        distance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        distance.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        distance.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        distance.instructions.add(new InsnNode(Opcodes.ICONST_4));
        distance.instructions.add(new InsnNode(Opcodes.LALOAD));
        distance.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Double",
                "longBitsToDouble",
                "(J)D",
                false));
        distance.instructions.add(new InsnNode(Opcodes.DRETURN));
        distance.maxStack = 2;
        distance.maxLocals = 2;
        node.methods.add(distance);

        MethodNode updateDistance = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserUpdateDistance",
                "(" + taskDescriptor + ")V",
                null,
                null);
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        updateDistance.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        updateDistance.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        updateDistance.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        updateDistance.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserCamera", cameraDescriptor));
        LabelNode updateHasCamera = new LabelNode();
        LabelNode updateStore = new LabelNode();
        updateDistance.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, updateHasCamera));
        updateDistance.instructions.add(new InsnNode(Opcodes.DCONST_0));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.DSTORE, 3));
        updateDistance.instructions.add(new JumpInsnNode(Opcodes.GOTO, updateStore));
        updateDistance.instructions.add(updateHasCamera);
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        updateDistance.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserCamera", cameraDescriptor));
        int updateCoordinateIndex = 0;
        for (int ignored = 0; ignored < 3; ignored++) {
            updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            updateDistance.instructions.add(new InsnNode(
                    Opcodes.ICONST_0 + updateCoordinateIndex++));
            updateDistance.instructions.add(new InsnNode(Opcodes.LALOAD));
            updateDistance.instructions.add(new InsnNode(Opcodes.L2I));
            updateDistance.instructions.add(new InsnNode(Opcodes.I2D));
            updateDistance.instructions.add(new LdcInsnNode(0.5D));
            updateDistance.instructions.add(new InsnNode(Opcodes.DADD));
        }
        updateDistance.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/phys/Vec3",
                "distanceToSqr",
                "(DDD)D",
                false));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.DSTORE, 3));
        updateDistance.instructions.add(updateStore);
        updateDistance.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        updateDistance.instructions.add(new InsnNode(Opcodes.ICONST_4));
        updateDistance.instructions.add(new VarInsnNode(Opcodes.DLOAD, 3));
        updateDistance.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Double",
                "doubleToRawLongBits",
                "(D)J",
                false));
        updateDistance.instructions.add(new InsnNode(Opcodes.LASTORE));
        updateDistance.instructions.add(new InsnNode(Opcodes.RETURN));
        updateDistance.maxStack = 9;
        updateDistance.maxLocals = 5;
        node.methods.add(updateDistance);

        MethodNode distanceFrom = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserDistanceFrom",
                "(" + taskDescriptor + cameraDescriptor + ")D",
                null,
                null);
        distanceFrom.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        distanceFrom.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        distanceFrom.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        distanceFrom.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        distanceFrom.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        distanceFrom.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        distanceFrom.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        int currentCoordinateIndex = 0;
        for (int ignored = 0; ignored < 3; ignored++) {
            distanceFrom.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            distanceFrom.instructions.add(new InsnNode(
                    Opcodes.ICONST_0 + currentCoordinateIndex++));
            distanceFrom.instructions.add(new InsnNode(Opcodes.LALOAD));
            distanceFrom.instructions.add(new InsnNode(Opcodes.L2I));
            distanceFrom.instructions.add(new InsnNode(Opcodes.I2D));
            distanceFrom.instructions.add(new LdcInsnNode(0.5D));
            distanceFrom.instructions.add(new InsnNode(Opcodes.DADD));
        }
        distanceFrom.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/phys/Vec3",
                "distanceToSqr",
                "(DDD)D",
                false));
        distanceFrom.instructions.add(new InsnNode(Opcodes.DRETURN));
        distanceFrom.maxStack = 9;
        distanceFrom.maxLocals = 4;
        node.methods.add(distanceFrom);

        MethodNode order = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserOrder",
                "(" + taskDescriptor + ")J",
                null,
                null);
        order.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        order.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        order.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        order.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        order.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        order.instructions.add(new InsnNode(Opcodes.ICONST_3));
        order.instructions.add(new InsnNode(Opcodes.LALOAD));
        order.instructions.add(new InsnNode(Opcodes.LRETURN));
        order.maxStack = 2;
        order.maxLocals = 2;
        node.methods.add(order);

        MethodNode compare = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserCompare",
                "(Ljava/lang/Object;Ljava/lang/Object;)I",
                null,
                null);
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        compare.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        compare.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        compare.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        compare.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistance",
                "(" + taskDescriptor + ")D",
                false));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistance",
                "(" + taskDescriptor + ")D",
                false));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false));
        compare.instructions.add(new VarInsnNode(Opcodes.ISTORE, 5));
        LabelNode compareOrder = new LabelNode();
        compare.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        compare.instructions.add(new JumpInsnNode(Opcodes.IFEQ, compareOrder));
        compare.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        compare.instructions.add(new InsnNode(Opcodes.IRETURN));
        compare.instructions.add(compareOrder);
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserOrder",
                "(" + taskDescriptor + ")J",
                false));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        compare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserOrder",
                "(" + taskDescriptor + ")J",
                false));
        compare.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Long", "compare", "(JJ)I", false));
        compare.instructions.add(new InsnNode(Opcodes.IRETURN));
        compare.maxStack = 5;
        compare.maxLocals = 6;
        node.methods.add(compare);

        MethodNode heapAdd = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserHeapAdd",
                "(" + queueDescriptor + taskDescriptor + ")V",
                null,
                null);
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapAdd.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        heapAdd.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "add",
                "(Ljava/lang/Object;)Z",
                false));
        heapAdd.instructions.add(new InsnNode(Opcodes.POP));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapAdd.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        heapAdd.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserSiftUp",
                "(" + queueDescriptor + "I)V",
                false));
        heapAdd.instructions.add(new InsnNode(Opcodes.RETURN));
        heapAdd.maxStack = 3;
        heapAdd.maxLocals = 4;
        node.methods.add(heapAdd);

        MethodNode heapPoll = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserHeapPoll",
                "(" + queueDescriptor + ")" + taskDescriptor,
                null,
                null);
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapPoll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        heapPoll.instructions.add(new InsnNode(Opcodes.ICONST_1));
        heapPoll.instructions.add(new InsnNode(Opcodes.ISUB));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        LabelNode heapPollEmpty = new LabelNode();
        LabelNode heapPollDone = new LabelNode();
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        heapPoll.instructions.add(new JumpInsnNode(Opcodes.IFLT, heapPollEmpty));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapPoll.instructions.add(new InsnNode(Opcodes.ICONST_0));
        heapPoll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        heapPoll.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        heapPoll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "remove",
                "(I)Ljava/lang/Object;",
                false));
        heapPoll.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        heapPoll.instructions.add(new JumpInsnNode(Opcodes.IFLE, heapPollDone));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapPoll.instructions.add(new InsnNode(Opcodes.ICONST_0));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        heapPoll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "set",
                "(ILjava/lang/Object;)Ljava/lang/Object;",
                false));
        heapPoll.instructions.add(new InsnNode(Opcodes.POP));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heapPoll.instructions.add(new InsnNode(Opcodes.ICONST_0));
        heapPoll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserSiftDown",
                "(" + queueDescriptor + "I)V",
                false));
        heapPoll.instructions.add(heapPollDone);
        heapPoll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        heapPoll.instructions.add(new InsnNode(Opcodes.ARETURN));
        heapPoll.instructions.add(heapPollEmpty);
        heapPoll.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        heapPoll.instructions.add(new InsnNode(Opcodes.ARETURN));
        heapPoll.maxStack = 3;
        heapPoll.maxLocals = 5;
        node.methods.add(heapPoll);

        MethodNode siftUp = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserSiftUp",
                "(" + queueDescriptor + "I)V",
                null,
                null);
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftUp.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        siftUp.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        LabelNode siftUpLoop = new LabelNode();
        LabelNode siftUpDone = new LabelNode();
        siftUp.instructions.add(siftUpLoop);
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftUp.instructions.add(new JumpInsnNode(Opcodes.IFLE, siftUpDone));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftUp.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftUp.instructions.add(new InsnNode(Opcodes.ISUB));
        siftUp.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftUp.instructions.add(new InsnNode(Opcodes.IUSHR));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ISTORE, 4));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        siftUp.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        siftUp.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        siftUp.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserCompare",
                "(Ljava/lang/Object;Ljava/lang/Object;)I",
                false));
        siftUp.instructions.add(new JumpInsnNode(Opcodes.IFGE, siftUpDone));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        siftUp.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "set",
                "(ILjava/lang/Object;)Ljava/lang/Object;",
                false));
        siftUp.instructions.add(new InsnNode(Opcodes.POP));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        siftUp.instructions.add(new JumpInsnNode(Opcodes.GOTO, siftUpLoop));
        siftUp.instructions.add(siftUpDone);
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftUp.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        siftUp.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "set",
                "(ILjava/lang/Object;)Ljava/lang/Object;",
                false));
        siftUp.instructions.add(new InsnNode(Opcodes.POP));
        siftUp.instructions.add(new InsnNode(Opcodes.RETURN));
        siftUp.maxStack = 3;
        siftUp.maxLocals = 6;
        node.methods.add(siftUp);

        MethodNode siftDown = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserSiftDown",
                "(" + queueDescriptor + "I)V",
                null,
                null);
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        siftDown.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        siftDown.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftDown.instructions.add(new InsnNode(Opcodes.IUSHR));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 5));
        LabelNode siftDownLoop = new LabelNode();
        LabelNode siftDownDone = new LabelNode();
        LabelNode siftDownChildReady = new LabelNode();
        siftDown.instructions.add(siftDownLoop);
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        siftDown.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, siftDownDone));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftDown.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftDown.instructions.add(new InsnNode(Opcodes.ISHL));
        siftDown.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftDown.instructions.add(new InsnNode(Opcodes.IADD));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 6));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        siftDown.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ASTORE, 7));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        siftDown.instructions.add(new InsnNode(Opcodes.ICONST_1));
        siftDown.instructions.add(new InsnNode(Opcodes.IADD));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 8));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 8));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        siftDown.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, siftDownChildReady));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 8));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        siftDown.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ASTORE, 9));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserCompare",
                "(Ljava/lang/Object;Ljava/lang/Object;)I",
                false));
        siftDown.instructions.add(new JumpInsnNode(Opcodes.IFGE, siftDownChildReady));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 8));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 6));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ASTORE, 7));
        siftDown.instructions.add(siftDownChildReady);
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserCompare",
                "(Ljava/lang/Object;Ljava/lang/Object;)I",
                false));
        siftDown.instructions.add(new JumpInsnNode(Opcodes.IFLE, siftDownDone));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "set",
                "(ILjava/lang/Object;)Ljava/lang/Object;",
                false));
        siftDown.instructions.add(new InsnNode(Opcodes.POP));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        siftDown.instructions.add(new JumpInsnNode(Opcodes.GOTO, siftDownLoop));
        siftDown.instructions.add(siftDownDone);
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        siftDown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        siftDown.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "set",
                "(ILjava/lang/Object;)Ljava/lang/Object;",
                false));
        siftDown.instructions.add(new InsnNode(Opcodes.POP));
        siftDown.instructions.add(new InsnNode(Opcodes.RETURN));
        siftDown.maxStack = 3;
        siftDown.maxLocals = 10;
        node.methods.add(siftDown);

        MethodNode rebuild = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserRebuild",
                "(" + queueDescriptor + ")V",
                null,
                null);
        rebuild.instructions.add(new InsnNode(Opcodes.ICONST_0));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        LabelNode updateLoop = new LabelNode();
        LabelNode updateDone = new LabelNode();
        rebuild.instructions.add(updateLoop);
        rebuild.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rebuild.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "size",
                "()I",
                false));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, updateDone));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rebuild.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        rebuild.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        rebuild.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserUpdateDistance",
                "(" + taskDescriptor + ")V",
                false));
        rebuild.instructions.add(new IincInsnNode(2, 1));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.GOTO, updateLoop));
        rebuild.instructions.add(updateDone);
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rebuild.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        rebuild.instructions.add(new InsnNode(Opcodes.ICONST_1));
        rebuild.instructions.add(new InsnNode(Opcodes.IUSHR));
        rebuild.instructions.add(new InsnNode(Opcodes.ICONST_1));
        rebuild.instructions.add(new InsnNode(Opcodes.ISUB));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        LabelNode heapifyLoop = new LabelNode();
        LabelNode heapifyDone = new LabelNode();
        rebuild.instructions.add(heapifyLoop);
        rebuild.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.IFLT, heapifyDone));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rebuild.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserSiftDown",
                "(" + queueDescriptor + "I)V",
                false));
        rebuild.instructions.add(new IincInsnNode(2, -1));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.GOTO, heapifyLoop));
        rebuild.instructions.add(heapifyDone);
        rebuild.instructions.add(new InsnNode(Opcodes.RETURN));
        rebuild.maxStack = 3;
        rebuild.maxLocals = 3;
        node.methods.add(rebuild);

        // A squared triangle-inequality bound keeps selection exact without sqrt while the
        // heap remains ordered around its last camera anchor.
        MethodNode takeNearest = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserTakeNearest",
                "(" + queueDescriptor + cameraDescriptor + "D)" + taskDescriptor,
                null,
                null);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "clear", "()V", false));
        takeNearest.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
        takeNearest.instructions.add(new LdcInsnNode(Double.MAX_VALUE));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DSTORE, 6));
        LabelNode takeLoop = new LabelNode();
        LabelNode takeLive = new LabelNode();
        LabelNode takeInspect = new LabelNode();
        LabelNode takeUpdate = new LabelNode();
        LabelNode takeContinue = new LabelNode();
        LabelNode takeDone = new LabelNode();
        takeNearest.instructions.add(takeLoop);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "isEmpty",
                "()Z",
                false));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFNE, takeDone));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        takeNearest.instructions.add(new InsnNode(Opcodes.ICONST_0));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        takeNearest.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ASTORE, 8));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                task,
                "isCancelled",
                "Ljava/util/concurrent/atomic/AtomicBoolean;"));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/atomic/AtomicBoolean",
                "get",
                "()Z",
                false));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFEQ, takeLive));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserHeapPoll",
                "(" + queueDescriptor + ")" + taskDescriptor,
                false));
        takeNearest.instructions.add(new InsnNode(Opcodes.POP));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "remove",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        takeNearest.instructions.add(new InsnNode(Opcodes.POP));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.GOTO, takeLoop));
        takeNearest.instructions.add(takeLive);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistance",
                "(" + taskDescriptor + ")D",
                false));
        takeNearest.instructions.add(new LdcInsnNode(0.5D));
        takeNearest.instructions.add(new InsnNode(Opcodes.DMUL));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 3));
        takeNearest.instructions.add(new InsnNode(Opcodes.DSUB));
        takeNearest.instructions.add(new LdcInsnNode(BROWSER_SECTION_QUEUE_DISTANCE_EPSILON));
        takeNearest.instructions.add(new InsnNode(Opcodes.DSUB));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DSTORE, 9));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFNULL, takeInspect));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 9));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 6));
        takeNearest.instructions.add(new InsnNode(Opcodes.DCMPG));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFGT, takeDone));
        takeNearest.instructions.add(takeInspect);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserHeapPoll",
                "(" + queueDescriptor + ")" + taskDescriptor,
                false));
        takeNearest.instructions.add(new InsnNode(Opcodes.POP));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "add",
                "(Ljava/lang/Object;)Z",
                false));
        takeNearest.instructions.add(new InsnNode(Opcodes.POP));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserDistanceFrom",
                "(" + taskDescriptor + cameraDescriptor + ")D",
                false));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DSTORE, 11));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 11));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 6));
        takeNearest.instructions.add(new InsnNode(Opcodes.DCMPG));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ISTORE, 13));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ILOAD, 13));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFLT, takeUpdate));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ILOAD, 13));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFGT, takeContinue));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserOrder",
                "(" + taskDescriptor + ")J",
                false));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserOrder",
                "(" + taskDescriptor + ")J",
                false));
        takeNearest.instructions.add(new InsnNode(Opcodes.LCMP));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IFGE, takeContinue));
        takeNearest.instructions.add(takeUpdate);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DLOAD, 11));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.DSTORE, 6));
        takeNearest.instructions.add(takeContinue);
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.GOTO, takeLoop));
        takeNearest.instructions.add(takeDone);
        takeNearest.instructions.add(new InsnNode(Opcodes.ICONST_0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ISTORE, 13));
        LabelNode restoreLoop = new LabelNode();
        LabelNode restoreDone = new LabelNode();
        LabelNode skipBest = new LabelNode();
        takeNearest.instructions.add(restoreLoop);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ILOAD, 13));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, restoreDone));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ILOAD, 13));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "get",
                "(I)Ljava/lang/Object;",
                false));
        takeNearest.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ASTORE, 8));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, skipBest));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserHeapAdd",
                "(" + queueDescriptor + taskDescriptor + ")V",
                false));
        takeNearest.instructions.add(skipBest);
        takeNearest.instructions.add(new IincInsnNode(13, 1));
        takeNearest.instructions.add(new JumpInsnNode(Opcodes.GOTO, restoreLoop));
        takeNearest.instructions.add(restoreDone);
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        takeNearest.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserScratchTasks", "Ljava/util/ArrayList;"));
        takeNearest.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "clear", "()V", false));
        takeNearest.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        takeNearest.instructions.add(new InsnNode(Opcodes.ARETURN));
        takeNearest.maxStack = 5;
        takeNearest.maxLocals = 14;
        node.methods.add(takeNearest);

        MethodNode requeue = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserRequeue",
                "(" + queueDescriptor + taskDescriptor + ")V",
                null,
                null);
        LabelNode requeueDone = new LabelNode();
        requeue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        requeue.instructions.add(new JumpInsnNode(Opcodes.IFNULL, requeueDone));
        requeue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requeue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        requeue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        requeue.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserHeapAdd",
                "(" + queueDescriptor + taskDescriptor + ")V",
                false));
        requeue.instructions.add(requeueDone);
        requeue.instructions.add(new InsnNode(Opcodes.RETURN));
        requeue.maxStack = 2;
        requeue.maxLocals = 3;
        node.methods.add(requeue);

        MethodNode finishTask = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserFinishTask",
                "(" + taskDescriptor + ")" + taskDescriptor,
                null,
                null);
        LabelNode finishDone = new LabelNode();
        finishTask.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        finishTask.instructions.add(new JumpInsnNode(Opcodes.IFNULL, finishDone));
        finishTask.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finishTask.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserTaskOrder",
                "Ljava/util/IdentityHashMap;"));
        finishTask.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        finishTask.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/IdentityHashMap",
                "remove",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        finishTask.instructions.add(new InsnNode(Opcodes.POP));
        finishTask.instructions.add(finishDone);
        finishTask.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        finishTask.instructions.add(new InsnNode(Opcodes.ARETURN));
        finishTask.maxStack = 2;
        finishTask.maxLocals = 2;
        node.methods.add(finishTask);

        MethodNode cancelAndClear = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserCancelAndClear",
                "(" + queueDescriptor + ")V",
                null,
                null);
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cancelAndClear.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList",
                "toArray",
                "()[Ljava/lang/Object;",
                false));
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        cancelAndClear.instructions.add(new InsnNode(Opcodes.ICONST_0));
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        LabelNode cancelLoop = new LabelNode();
        LabelNode cancelDone = new LabelNode();
        cancelAndClear.instructions.add(cancelLoop);
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        cancelAndClear.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
        cancelAndClear.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, cancelDone));
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        cancelAndClear.instructions.add(new InsnNode(Opcodes.AALOAD));
        cancelAndClear.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, task));
        cancelAndClear.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, task, "cancel", "()V", false));
        cancelAndClear.instructions.add(new IincInsnNode(3, 1));
        cancelAndClear.instructions.add(new JumpInsnNode(Opcodes.GOTO, cancelLoop));
        cancelAndClear.instructions.add(cancelDone);
        cancelAndClear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cancelAndClear.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "clear", "()V", false));
        cancelAndClear.instructions.add(new InsnNode(Opcodes.RETURN));
        cancelAndClear.maxStack = 2;
        cancelAndClear.maxLocals = 4;
        node.methods.add(cancelAndClear);
    }

    public static void patchCurrentSectionRenderDispatcherBufferBudgets(ClassNode node) {
        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/TracingExecutor;"
                        + "Lnet/minecraft/client/renderer/RenderBuffers;"
                        + "Lnet/minecraft/client/renderer/chunk/SectionCompiler;"
                        + "Ljava/util/function/Consumer;)V");
        int constructorVertexHeap = 0;
        int constructorIndexHeap = 0;
        int staging = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (!(instruction instanceof LdcInsnNode constant)
                    || !(constant.cst instanceof Integer value)) {
                continue;
            }
            if (value == 134217728) {
                constant.cst = BROWSER_SECTION_VERTEX_HEAP_BYTES;
                constructorVertexHeap++;
            } else if (value == 33554432) {
                constant.cst = BROWSER_SECTION_INDEX_HEAP_BYTES;
                constructorIndexHeap++;
            } else if (value == 102760448) {
                constant.cst = BROWSER_SECTION_STAGING_BYTES;
                staging++;
            }
        }
        if (constructorVertexHeap != 1 || constructorIndexHeap != 1 || staging != 2) {
            throw new IllegalStateException(
                    "Current section renderer constructor budget constants changed: vertex="
                            + constructorVertexHeap + ", index=" + constructorIndexHeap
                            + ", staging=" + staging);
        }

        String uberOwner = "com/mojang/blaze3d/vertex/UberGpuBuffer";
        String uberConstructorDescriptor =
                "(Ljava/lang/String;IIILcom/mojang/blaze3d/vertex/StagingBuffer;)V";
        MethodNode heapFactory = find(
                node,
                "lambda$new$0",
                "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)"
                        + "Lnet/minecraft/client/renderer/chunk/"
                        + "SectionRenderDispatcher$SectionUberBuffers;");
        String vertexFormat = sectionHeapFactoryVertexFormat(heapFactory);
        int uberConstructors = 0;
        int vertexHeap = 0;
        int indexHeap = 0;
        for (AbstractInsnNode instruction : heapFactory.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals(uberOwner)
                    || !call.name.equals("<init>")
                    || !call.desc.equals(uberConstructorDescriptor)) {
                continue;
            }
            uberConstructors++;
            AbstractInsnNode allocation = findPreviousNew(call, uberOwner);
            java.util.List<AbstractInsnNode> shape = new java.util.ArrayList<>();
            for (AbstractInsnNode cursor = allocation;
                    cursor != null;
                    cursor = nextOpcode(cursor)) {
                shape.add(cursor);
                if (cursor == call) {
                    break;
                }
            }
            AbstractInsnNode stagingLoad = previousOpcode(call);
            AbstractInsnNode dispatcherLoad = stagingLoad == null
                    ? null : previousOpcode(stagingLoad);
            if ((shape.size() != 10 && shape.size() != 11)
                    || shape.get(shape.size() - 1) != call
                    || !(shape.get(0) instanceof TypeInsnNode allocationType)
                    || allocationType.getOpcode() != Opcodes.NEW
                    || !allocationType.desc.equals(uberOwner)
                    || shape.get(1).getOpcode() != Opcodes.DUP
                    || !(shape.get(2) instanceof VarInsnNode layerLoad)
                    || layerLoad.getOpcode() != Opcodes.ALOAD
                    || layerLoad.var != 1
                    || !(shape.get(3) instanceof MethodInsnNode labelCall)
                    || labelCall.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !labelCall.owner.equals(
                            "net/minecraft/client/renderer/chunk/ChunkSectionLayer")
                    || !labelCall.name.equals("label")
                    || !labelCall.desc.equals("()Ljava/lang/String;")
                    || !(shape.get(4) instanceof IntInsnNode usage)
                    || usage.getOpcode() != Opcodes.BIPUSH
                    || !(shape.get(5) instanceof LdcInsnNode capacity)
                    || !(capacity.cst instanceof Integer)
                    || !(dispatcherLoad instanceof VarInsnNode dispatcherOwnerLoad)
                    || dispatcherOwnerLoad.getOpcode() != Opcodes.ALOAD
                    || dispatcherOwnerLoad.var != 0
                    || !(stagingLoad instanceof FieldInsnNode stagingField)
                    || stagingField.getOpcode() != Opcodes.GETFIELD
                    || !stagingField.owner.equals(node.name)
                    || !stagingField.name.equals("stagingBuffer")
                    || !stagingField.desc.equals(
                            "Lcom/mojang/blaze3d/vertex/StagingBuffer;")) {
                throw new IllegalStateException(
                        "Current section renderer lambda UberGpuBuffer constructor shape changed");
            }
            if (usage.operand == 32
                    && shape.size() == 11
                    && Integer.valueOf(134217728).equals(capacity.cst)
                    && shape.get(6) instanceof VarInsnNode vertexFormatLoad
                    && vertexFormatLoad.getOpcode() == Opcodes.ALOAD
                    && vertexFormatLoad.var == 2
                    && shape.get(7) instanceof MethodInsnNode vertexSizeCall
                    && vertexSizeCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && vertexSizeCall.owner.equals(vertexFormat)
                    && vertexSizeCall.name.equals("getVertexSize")
                    && vertexSizeCall.desc.equals("()I")) {
                capacity.cst = BROWSER_SECTION_VERTEX_HEAP_BYTES;
                // Align vertex allocations to four vertices (112 bytes for BLOCK) so every
                // section's base vertex is a multiple of four: its quads then line up with
                // the shared quad index buffer of the GL layer and need no index copy.
                heapFactory.instructions.insert(vertexSizeCall, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        TERRAIN_BATCH,
                        "vertexHeapAlignment",
                        "(I)I",
                        false));
                vertexHeap++;
            } else if (usage.operand == 64
                    && shape.size() == 10
                    && Integer.valueOf(33554432).equals(capacity.cst)
                    && shape.get(6) instanceof IntInsnNode indexStride
                    && indexStride.getOpcode() == Opcodes.BIPUSH
                    && indexStride.operand == 8
                    && shape.get(7) == dispatcherLoad) {
                capacity.cst = BROWSER_SECTION_INDEX_HEAP_BYTES;
                indexHeap++;
            } else {
                throw new IllegalStateException(
                        "Current section renderer lambda UberGpuBuffer usage changed: "
                                + usage.operand + "/" + capacity.cst);
            }
        }
        if (uberConstructors != 2 || vertexHeap != 1 || indexHeap != 1) {
            throw new IllegalStateException(
                    "Current section renderer lambda heap budgets changed: constructors="
                            + uberConstructors + ", vertex=" + vertexHeap
                            + ", index=" + indexHeap);
        }
        System.out.println("Reduced current section renderer browser allocation units and "
                + "aligned vertex heaps to four vertices");
    }

    /**
     * The VertexFormat type of the section heap factory's local 2: the return type of the call
     * stored first into it (26.2 blaze3d, 26.3 renderpearl/api/vertex). getVertexSize must be
     * invoked on exactly that owner.
     */
    private static String sectionHeapFactoryVertexFormat(MethodNode heapFactory) {
        for (AbstractInsnNode instruction : heapFactory.instructions.toArray()) {
            if (!(instruction instanceof VarInsnNode store)
                    || store.getOpcode() != Opcodes.ASTORE
                    || store.var != 2) {
                continue;
            }
            if (previousOpcode(store) instanceof MethodInsnNode source) {
                String type = Type.getReturnType(source.desc).getInternalName();
                if (type.equals("com/mojang/blaze3d/vertex/VertexFormat")
                        || type.equals("com/mojang/renderpearl/api/vertex/VertexFormat")) {
                    return type;
                }
            }
            break;
        }
        throw new IllegalStateException(
                "Current section renderer lambda vertex format local changed");
    }

    private static void patchCurrentSectionRenderDispatcherTelemetry(MethodNode upload) {
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 0));
        begin.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/renderer/chunk/SectionRenderDispatcher",
                "getCompileQueueSize",
                "()I",
                false));
        begin.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "beginUploadPass",
                "(I)V",
                false));
        upload.instructions.insert(begin);

        int returns = 0;
        int terminalThrows = 0;
        for (AbstractInsnNode instruction : upload.instructions.toArray()) {
            int opcode = instruction.getOpcode();
            if (opcode != Opcodes.RETURN && opcode != Opcodes.ATHROW) {
                continue;
            }
            InsnList end = new InsnList();
            end.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "endUploadPass",
                    "()V",
                    false));
            upload.instructions.insertBefore(instruction, end);
            if (opcode == Opcodes.RETURN) {
                returns++;
            } else {
                terminalThrows++;
            }
        }
        if (returns != 1 || terminalThrows != 1) {
            throw new IllegalStateException(
                    "Current terrain upload telemetry control flow changed: returns="
                            + returns + ", throws=" + terminalThrows);
        }
        upload.maxStack = Math.max(upload.maxStack, 1);
        System.out.println("Instrumented current section compile and upload telemetry");
    }

    private static MethodInsnNode deferBrowserRenderExecutor() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "defer",
                "(Ljava/util/concurrent/Executor;)Ljava/util/concurrent/Executor;",
                false);
    }

    public static void patchBufferBuilderBrowserFastVertex(String jar, Path output) throws IOException {
        String owner = "com/mojang/blaze3d/vertex/BufferBuilder";
        ClassNode node = read(jar, owner + ".class");
        boolean semanticElementIds = node.methods.stream()
                .anyMatch(method -> method.name.equals("beginElement")
                        && method.desc.equals("(I)J"));
        // Semantic ids and the VertexFormatElement owner come from the probed jar: 26.3 inserted
        // UV3=5 (NORMAL moved to 6) and moved VertexFormatElement to renderpearl.
        ModernSymbols symbols = ModernSymbols.cached(jar);
        addBrowserBeginElementOffset(node, owner, semanticElementIds);
        patchBufferBuilderBrowserGuiWriters(node, owner, semanticElementIds, symbols);
        MethodNode method = find(node, "addVertex", "(FFFIFFIIFFF)V");
        LabelNode fallback = new LabelNode();
        LabelNode fastPath = new LabelNode();
        InsnList code = new InsnList();

        if (semanticElementIds) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "blockFormat", "Z"));
            code.add(new JumpInsnNode(Opcodes.IFNE, fastPath));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "entityFormat", "Z"));
            code.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
            code.add(fastPath);
        } else {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "fastFormat", "Z"));
            code.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        }

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "beginVertex",
                "()J",
                false));
        code.add(new InsnNode(Opcodes.POP2));
        loadBrowserCurrentVertexTarget(code, owner);
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.FLOAD, 5));
        code.add(new VarInsnNode(Opcodes.FLOAD, 6));
        code.add(new VarInsnNode(Opcodes.ILOAD, 7));
        code.add(new VarInsnNode(Opcodes.ILOAD, 8));
        code.add(new VarInsnNode(Opcodes.FLOAD, 9));
        code.add(new VarInsnNode(Opcodes.FLOAD, 10));
        code.add(new VarInsnNode(Opcodes.FLOAD, 11));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                semanticElementIds ? "entityFormat" : "fullFormat",
                "Z"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putFastVertexBytes",
                "([BIFFFIFFIIFFFZ)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));

        code.add(fallback);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.FLOAD, 5));
        code.add(new VarInsnNode(Opcodes.FLOAD, 6));
        code.add(new VarInsnNode(Opcodes.ILOAD, 7));
        code.add(new VarInsnNode(Opcodes.ILOAD, 8));
        code.add(new VarInsnNode(Opcodes.FLOAD, 9));
        code.add(new VarInsnNode(Opcodes.FLOAD, 10));
        code.add(new VarInsnNode(Opcodes.FLOAD, 11));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "com/mojang/blaze3d/vertex/VertexConsumer",
                "addVertex",
                "(FFFIFFIIFFF)V",
                true));
        code.add(new InsnNode(Opcodes.RETURN));

        replace(method, code, 16, 12);
        writeComputeFrames(node, output);
    }

    public static void patchByteBufferBuilderBrowserReserve(String jar, Path output) throws IOException {
        String owner = "com/mojang/blaze3d/vertex/ByteBufferBuilder";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(0, "browserData", "[B", null, null));
        node.fields.add(new FieldNode(0, "browserDataOffset", "I", null, null));
        node.fields.add(new FieldNode(0, "browserLastReserveOffset", "I", null, null));

        MethodNode constructor = find(node, "<init>", "(IJ)V");
        injectBrowserBufferCacheBeforeReturn(constructor, owner);

        MethodNode resize = find(node, "resize", "(J)V");
        injectBrowserBufferCacheBeforeReturn(resize, owner);

        MethodNode method = find(node, "reserve", "(I)J");
        int replacements = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !"java/lang/Math".equals(call.owner)
                    || !"addExact".equals(call.name)
                    || !"(JJ)J".equals(call.desc)) {
                continue;
            }
            method.instructions.set(call, new InsnNode(Opcodes.LADD));
            replacements++;
        }
        if (replacements != 2) {
            throw new IllegalStateException(
                    "ByteBufferBuilder.reserve expected 2 Math.addExact calls, found " + replacements);
        }
        int reserveOffsets = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.LSTORE
                    && store.var == 2) {
                InsnList cacheOffset = new InsnList();
                cacheOffset.add(new VarInsnNode(Opcodes.ALOAD, 0));
                cacheOffset.add(new VarInsnNode(Opcodes.LLOAD, 2));
                cacheOffset.add(new InsnNode(Opcodes.L2I));
                cacheOffset.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "browserLastReserveOffset", "I"));
                method.instructions.insert(instruction, cacheOffset);
                reserveOffsets++;
            }
        }
        if (reserveOffsets != 1) {
            throw new IllegalStateException(
                    "ByteBufferBuilder.reserve expected one starting offset, found " + reserveOffsets);
        }
        writeComputeFrames(node, output);
    }

    private static void injectBrowserBufferCacheBeforeReturn(MethodNode method, String owner) {
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList cache = new InsnList();
            cache.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cache.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cache.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "pointer", "J"));
            cache.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/system/BrowserMemory",
                    "data",
                    "(J)[B",
                    false));
            cache.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "browserData", "[B"));
            cache.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cache.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cache.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "pointer", "J"));
            cache.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/system/BrowserMemory",
                    "dataOffset",
                    "(J)I",
                    false));
            cache.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "browserDataOffset", "I"));
            method.instructions.insertBefore(instruction, cache);
            returns++;
        }
        if (returns != 1) {
            throw new IllegalStateException(
                    method.name + method.desc + " expected one return, found " + returns);
        }
    }

    public static void patchCompiledSectionMeshBrowserVertexBufferReuse(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/renderer/chunk/CompiledSectionMesh";
        String meshOwner = "com/mojang/blaze3d/vertex/MeshData";
        String helperOwner = "dev/gaius/browser/BrowserMeshUpload";
        ClassNode node = read(jar, owner + ".class");
        String uploadDescriptor =
                "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"
                        + "Lcom/mojang/blaze3d/vertex/MeshData;J)V";
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("uploadMeshLayer")
                        && candidate.desc.equals(uploadDescriptor))
                .findFirst()
                .orElse(null);
        if (method == null) {
            verifyCurrentSectionMeshSingleViewUpload(jar, meshOwner);
            return;
        }

        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 2));
        begin.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                helperOwner,
                "begin",
                "(Lcom/mojang/blaze3d/vertex/MeshData;)V",
                false));
        method.instructions.insert(begin);

        int vertexBufferCalls = 0;
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && meshOwner.equals(call.owner)
                    && "vertexBuffer".equals(call.name)
                    && "()Ljava/nio/ByteBuffer;".equals(call.desc)) {
                method.instructions.set(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        helperOwner,
                        "vertexBuffer",
                        "(Lcom/mojang/blaze3d/vertex/MeshData;)Ljava/nio/ByteBuffer;",
                        false));
                vertexBufferCalls++;
            } else if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList end = new InsnList();
                end.add(new VarInsnNode(Opcodes.ALOAD, 2));
                end.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        helperOwner,
                        "end",
                        "(Lcom/mojang/blaze3d/vertex/MeshData;)V",
                        false));
                method.instructions.insertBefore(instruction, end);
                returns++;
            }
        }
        if (vertexBufferCalls != 4 || returns != 1) {
            throw new IllegalStateException(
                    "CompiledSectionMesh.uploadMeshLayer expected 4 vertexBuffer calls and 1 return, found "
                            + vertexBufferCalls + " and " + returns);
        }
        writeComputeFrames(node, output);
    }

    private static void verifyCurrentSectionMeshSingleViewUpload(String jar, String meshOwner)
            throws IOException {
        String compileTask =
                "net/minecraft/client/renderer/chunk/"
                        + "SectionRenderDispatcher$RenderSection$CompileTask";
        ClassNode node = read(jar, compileTask + ".class");
        MethodNode method = find(
                node,
                "doTask",
                "(Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)"
                        + "Lnet/minecraft/client/renderer/chunk/"
                        + "SectionRenderDispatcher$RenderSection$SectionTask$SectionTaskResult;");
        int vertexViews = 0;
        int indexViews = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(meshOwner)
                    || !call.desc.equals("()Ljava/nio/ByteBuffer;")) {
                continue;
            }
            if (call.name.equals("vertexBuffer")) {
                vertexViews++;
            } else if (call.name.equals("indexBuffer")) {
                indexViews++;
            }
        }
        if (vertexViews != 1 || indexViews != 1) {
            throw new IllegalStateException(
                    "Current section upload expected one vertex and one index view, found "
                            + vertexViews + " and " + indexViews);
        }
        System.out.println("Verified current single-view section mesh upload");
    }

    private static void patchBufferBuilderBrowserGuiWriters(
            ClassNode node, String owner, boolean semanticElementIds, ModernSymbols symbols) {
        patchBufferBuilderFloatPosition(node, owner, semanticElementIds, symbols);
        patchBufferBuilderMatrixPosition(node, owner, semanticElementIds, symbols);
        patchBufferBuilderSetColor(node, owner, semanticElementIds, symbols);
        patchBufferBuilderSetUv(node, owner, semanticElementIds, symbols);
        patchBufferBuilderSetOverlayOrLight(
                node, owner, "setOverlay", semanticElementIds, symbols);
        patchBufferBuilderSetOverlayOrLight(
                node, owner, "setLight", semanticElementIds, symbols);
        patchBufferBuilderUvShort(node, owner, semanticElementIds);
        patchBufferBuilderSetNormal(node, owner, semanticElementIds, symbols);
    }

    private static void addBrowserBeginElementOffset(
            ClassNode node, String owner, boolean semanticElementIds) {
        String element = "com/mojang/blaze3d/vertex/VertexFormatElement";
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserBeginElementOffset",
                semanticElementIds ? "(I)I" : "(L" + element + ";)I",
                null,
                null);
        LabelNode present = new LabelNode();
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(semanticElementIds ? Opcodes.ILOAD : Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "beginElement",
                semanticElementIds ? "(I)J" : "(L" + element + ";)J",
                false));
        code.add(new VarInsnNode(Opcodes.LSTORE, 2));
        code.add(new VarInsnNode(Opcodes.LLOAD, 2));
        code.add(new LdcInsnNode(Long.valueOf(-1L)));
        code.add(new InsnNode(Opcodes.LCMP));
        code.add(new JumpInsnNode(Opcodes.IFNE, present));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(present);
        if (semanticElementIds) {
            code.add(new VarInsnNode(Opcodes.LLOAD, 2));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/system/BrowserMemory",
                    "dataOffset",
                    "(J)I",
                    false));
            code.add(new InsnNode(Opcodes.IRETURN));
            method.maxStack = 4;
            method.maxLocals = 4;
            node.methods.add(method);
            return;
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "buffer", "Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/vertex/ByteBufferBuilder",
                "browserDataOffset",
                "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "buffer", "Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/vertex/ByteBufferBuilder",
                "browserLastReserveOffset",
                "I"));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "offsetsByElement", "[I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, element, "id", "()I", false));
        code.add(new InsnNode(Opcodes.IALOAD));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 4;
        method.maxLocals = 4;
        node.methods.add(method);
    }

    private static void loadBrowserCurrentVertexTarget(InsnList code, String owner) {
        String buffer = "com/mojang/blaze3d/vertex/ByteBufferBuilder";
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "buffer", "L" + buffer + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, buffer, "browserData", "[B"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "buffer", "L" + buffer + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, buffer, "browserDataOffset", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "buffer", "L" + buffer + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, buffer, "browserLastReserveOffset", "I"));
        code.add(new InsnNode(Opcodes.IADD));
    }

    private static void patchBufferBuilderFloatPosition(
            ClassNode node, String owner, boolean semanticElementIds, ModernSymbols symbols) {
        MethodNode method = find(node, "addVertex", "(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = beginBufferBuilderVertexPosition(owner, 4, semanticElementIds, symbols);
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putPositionBytes",
                "([BIFFF)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 6, semanticElementIds ? 5 : 4);
    }

    private static void patchBufferBuilderMatrixPosition(
            ClassNode node, String owner, boolean semanticElementIds, ModernSymbols symbols) {
        MethodNode method = findOrCreateMethod(
                node,
                Opcodes.ACC_PUBLIC,
                "addVertex",
                "(Lorg/joml/Matrix4fc;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = beginBufferBuilderVertexPosition(owner, 5, semanticElementIds, symbols);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new VarInsnNode(Opcodes.FLOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putTransformedPositionBytes",
                "([BILorg/joml/Matrix4fc;FFF)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 7, semanticElementIds ? 6 : 5);
    }

    private static InsnList beginBufferBuilderVertexPosition(
            String owner, int offsetLocal, boolean semanticElementIds, ModernSymbols symbols) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "beginVertex",
                "()J",
                false));
        if (semanticElementIds) {
            String element = renderName(symbols, "com/mojang/blaze3d/vertex/VertexFormatElement");
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "elements",
                    "[L" + element + ";"));
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new InsnNode(Opcodes.AALOAD));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    element,
                    "offset",
                    "()I",
                    false));
            code.add(new InsnNode(Opcodes.I2L));
            code.add(new InsnNode(Opcodes.LADD));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/system/BrowserMemory",
                    "dataOffset",
                    "(J)I",
                    false));
            code.add(new VarInsnNode(Opcodes.ISTORE, offsetLocal));
        } else {
            code.add(new InsnNode(Opcodes.POP2));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "initialElementsToFill",
                "I"));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "elementsToFill",
                "I"));
        if (semanticElementIds) {
            loadBrowserData(code, owner);
            code.add(new VarInsnNode(Opcodes.ILOAD, offsetLocal));
        } else {
            loadBrowserCurrentVertexTarget(code, owner);
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "offsetsByElement", "[I"));
            code.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "com/mojang/blaze3d/vertex/VertexFormatElement",
                    "POSITION",
                    "Lcom/mojang/blaze3d/vertex/VertexFormatElement;"));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "com/mojang/blaze3d/vertex/VertexFormatElement",
                    "id",
                    "()I",
                    false));
            code.add(new InsnNode(Opcodes.IALOAD));
            code.add(new InsnNode(Opcodes.IADD));
        }
        return code;
    }

    private static void patchBufferBuilderSetColor(
            ClassNode node, String owner, boolean semanticElementIds, ModernSymbols symbols) {
        MethodNode method = find(node, "setColor", "(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = new InsnList();
        LabelNode done = beginBrowserElementWrite(
                code, owner, "COLOR", 2, semanticElementIds, symbols);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putRgbaBytes",
                "([BII)V",
                false));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4, 3);
    }

    private static void patchBufferBuilderSetUv(
            ClassNode node, String owner, boolean semanticElementIds, ModernSymbols symbols) {
        MethodNode method = find(node, "setUv", "(FF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = new InsnList();
        LabelNode done = beginBrowserElementWrite(
                code, owner, "UV0", 3, semanticElementIds, symbols);
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putFloatPairBytes",
                "([BIFF)V",
                false));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4, 4);
    }

    private static void patchBufferBuilderSetOverlayOrLight(
            ClassNode node, String owner, String methodName, boolean semanticElementIds,
            ModernSymbols symbols) {
        MethodNode method = find(node, methodName, "(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        String element = methodName.equals("setOverlay") ? "UV1" : "UV2";
        InsnList code = new InsnList();
        LabelNode done = beginBrowserElementWrite(
                code, owner, element, 2, semanticElementIds, symbols);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putPackedUvBytes",
                "([BII)V",
                false));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4, 3);
    }

    private static void patchBufferBuilderUvShort(
            ClassNode node, String owner, boolean semanticElementIds) {
        MethodNode method = find(node, "uvShort",
                semanticElementIds
                        ? "(SSI)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                        : "(SSLcom/mojang/blaze3d/vertex/VertexFormatElement;)"
                                + "Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(semanticElementIds ? Opcodes.ILOAD : Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "browserBeginElementOffset",
                semanticElementIds
                        ? "(I)I"
                        : "(Lcom/mojang/blaze3d/vertex/VertexFormatElement;)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, done));
        loadBrowserData(code, owner);
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putShortPairBytes",
                "([BISS)V",
                false));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4, 5);
    }

    private static void patchBufferBuilderSetNormal(
            ClassNode node, String owner, boolean semanticElementIds, ModernSymbols symbols) {
        MethodNode method = find(node, "setNormal", "(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
        InsnList code = new InsnList();
        LabelNode done = beginBrowserElementWrite(
                code, owner, "NORMAL", 4, semanticElementIds, symbols);
        code.add(new VarInsnNode(Opcodes.FLOAD, 1));
        code.add(new VarInsnNode(Opcodes.FLOAD, 2));
        code.add(new VarInsnNode(Opcodes.FLOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/system/BrowserMemory",
                "putNormalBytes",
                "([BIFFF)V",
                false));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 5, 5);
    }

    private static LabelNode beginBrowserElementWrite(
            InsnList code,
            String owner,
            String elementName,
            int offsetLocal,
            boolean semanticElementIds,
            ModernSymbols symbols) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (semanticElementIds) {
            int semanticId = bufferBuilderSemanticId(symbols, elementName);
            code.add(semanticId <= 5
                    ? new InsnNode(Opcodes.ICONST_0 + semanticId)
                    : new IntInsnNode(Opcodes.BIPUSH, semanticId));
        } else {
            code.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "com/mojang/blaze3d/vertex/VertexFormatElement",
                    elementName,
                    "Lcom/mojang/blaze3d/vertex/VertexFormatElement;"));
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "browserBeginElementOffset",
                semanticElementIds
                        ? "(I)I"
                        : "(Lcom/mojang/blaze3d/vertex/VertexFormatElement;)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, offsetLocal));
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ILOAD, offsetLocal));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, done));
        loadBrowserData(code, owner);
        code.add(new VarInsnNode(Opcodes.ILOAD, offsetLocal));
        return done;
    }

    /**
     * {@code BufferBuilder.<elementName>_SEMANTIC_ID} of the probed jar (26.3: UV3=5, NORMAL=6),
     * cross-checked against the 26.2 table for the ids 26.2 defines.
     */
    private static int bufferBuilderSemanticId(ModernSymbols symbols, String elementName) {
        int id = symbols.vertexSemantic(elementName);
        if (!symbols.vertexSemantics.containsKey("UV3") && id != bufferBuilderSemanticId(elementName)) {
            throw new IllegalStateException("BufferBuilder." + elementName + "_SEMANTIC_ID=" + id
                    + " but the 26.2 layout expects " + bufferBuilderSemanticId(elementName));
        }
        return id;
    }

    private static int bufferBuilderSemanticId(String elementName) {
        if (elementName.equals("POSITION")) {
            return 0;
        }
        if (elementName.equals("COLOR")) {
            return 1;
        }
        if (elementName.equals("UV0")) {
            return 2;
        }
        if (elementName.equals("UV1")) {
            return 3;
        }
        if (elementName.equals("UV2")) {
            return 4;
        }
        if (elementName.equals("NORMAL")) {
            return 5;
        }
        throw new IllegalArgumentException("Unknown BufferBuilder semantic " + elementName);
    }

    private static void loadBrowserData(InsnList code, String owner) {
        String buffer = "com/mojang/blaze3d/vertex/ByteBufferBuilder";
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "buffer", "L" + buffer + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, buffer, "browserData", "[B"));
    }
}
