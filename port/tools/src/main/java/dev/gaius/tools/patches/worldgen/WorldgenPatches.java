package dev.gaius.tools.patches.worldgen;

import static dev.gaius.tools.patches.support.ClientPatchSupport.browserWorldgenDeepPulse;
import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findNullable;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findOrCreateMethod;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextRealInstruction;
import static dev.gaius.tools.patches.support.ClientPatchSupport.patchDeepWorldgenPulseBackEdges;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousRealInstruction;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.requireWorldgenSchedulerCalls;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import dev.gaius.tools.PatchRegistry;
import dev.gaius.tools.VersionProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The worldgen domain of the MinecraftClientPatcher step ({@code patches/worldgen}):
 * noise, aquifers, beardifier, density functions, climate trees, surface rules, carvers,
 * structures and jigsaw placement, heightmaps and the generator's non-suspending JavaScript paths
 * with their cooperative checkpoints.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically. The worldgen
 * kernel hook is {@link WorldgenKernelPatches}.
 */
public final class WorldgenPatches {
    private WorldgenPatches() {
    }

    private static final String CHUNK_GENERATOR_STRUCTURE_STATE =
            "net/minecraft/world/level/chunk/ChunkGeneratorStructureState";
    private static final String BROWSER_RING_POSITIONS = "dev/gaius/browser/BrowserRingPositions";

    /** The biome-search lambda of generateRingPositions: (startChunkX, startChunkZ, biomes, random) -> ChunkPos. */
    private static MethodNode ringPositionSearchLambda(ClassNode node) {
        String descriptor =
                "(IILnet/minecraft/core/HolderSet;Lnet/minecraft/util/RandomSource;)"
                        + "Lnet/minecraft/world/level/ChunkPos;";
        MethodNode method = null;
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.startsWith("lambda$generateRingPositions$")
                    || !candidate.desc.equals(descriptor)) {
                continue;
            }
            if (method != null) {
                throw new IllegalStateException(
                        "Multiple ChunkGeneratorStructureState ring-position lambdas found");
            }
            method = candidate;
        }
        if (method == null) {
            throw new IllegalStateException(
                    "ChunkGeneratorStructureState ring-position lambda was not found");
        }
        return method;
    }

    /**
     * Both TeaVM targets: vanilla's concentric-ring (stronghold) positions, each biome search run
     * when a caller first needs its result instead of all of them before the first chunk.
     *
     * <p>{@code generateRingPositions} keeps its loop, every random draw and the search lambda
     * with its forked RandomSource; only the two calls that schedule and join the searches change:
     * {@code CompletableFuture.supplyAsync(search, Util.backgroundExecutor().forName(...))}
     * becomes {@code BrowserRingPositions.defer(search, startChunkX, startChunkZ)} (the start
     * chunk the lambda captured) and {@code Util.sequence(list)} becomes
     * {@code BrowserRingPositions.sequence(list, blockRadius)}, an already completed future of a
     * lazy list. Its contains, which isPlacementChunk asks once per generated chunk, runs only the
     * searches whose start lies within the search square's reach of the queried chunk, so the
     * world no longer waits for the whole stronghold search before its first chunk, and every
     * position and answer stays vanilla's (port/scripts/structure-rings-parity.mjs).
     *
     * <p>The reach rests on the lambda's shape, checked here: the search centre is
     * {@code sectionToBlockCoord(startChunk, 8)}, its radius one int constant, the result the
     * chunk of the found block or the start chunk, with no other arithmetic. Any other shape
     * fails the patch (PERF: disabled, vanilla runs).
     */
    public static void patchChunkGeneratorStructureStateBrowserLazyRings(
            String jar, Path output) throws IOException {
        ClassNode node = read(jar, CHUNK_GENERATOR_STRUCTURE_STATE + ".class");
        MethodNode search = ringPositionSearchLambda(node);
        int blockRadius = ringPositionSearchRadius(search);
        MethodNode generate = find(node, "generateRingPositions",
                "(Lnet/minecraft/core/Holder;"
                        + "Lnet/minecraft/world/level/levelgen/structure/placement/"
                        + "ConcentricRingsStructurePlacement;)"
                        + "Ljava/util/concurrent/CompletableFuture;");
        InvokeDynamicInsnNode supplier = null;
        MethodInsnNode sequence = null;
        for (AbstractInsnNode instruction : generate.instructions.toArray()) {
            if (instruction instanceof InvokeDynamicInsnNode dynamic
                    && dynamic.bsmArgs.length > 1
                    && dynamic.bsmArgs[1] instanceof org.objectweb.asm.Handle handle
                    && handle.getOwner().equals(CHUNK_GENERATOR_STRUCTURE_STATE)
                    && handle.getName().equals(search.name)
                    && handle.getDesc().equals(search.desc)) {
                if (supplier != null) {
                    throw new IllegalStateException("generateRingPositions creates the search twice");
                }
                supplier = dynamic;
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/util/Util")
                    && call.name.equals("sequence")) {
                if (sequence != null
                        || !call.desc.equals("(Ljava/util/List;)Ljava/util/concurrent/CompletableFuture;")) {
                    throw new IllegalStateException("generateRingPositions joins its searches in another shape");
                }
                sequence = call;
            }
        }
        if (supplier == null || sequence == null) {
            throw new IllegalStateException("generateRingPositions search submission or join was not found");
        }
        // The lambda captures (this, startChunkX, startChunkZ, biomes, random): the start chunk is
        // in the two int locals loaded right before it.
        AbstractInsnNode random = previousRealInstruction(supplier);
        AbstractInsnNode biomes = previousRealInstruction(random);
        AbstractInsnNode startZ = previousRealInstruction(biomes);
        AbstractInsnNode startX = previousRealInstruction(startZ);
        AbstractInsnNode self = previousRealInstruction(startX);
        if (!(startX instanceof VarInsnNode x) || x.getOpcode() != Opcodes.ILOAD
                || !(startZ instanceof VarInsnNode z) || z.getOpcode() != Opcodes.ILOAD
                || biomes.getOpcode() != Opcodes.ALOAD || random.getOpcode() != Opcodes.ALOAD
                || !(self instanceof VarInsnNode receiver) || receiver.getOpcode() != Opcodes.ALOAD
                || receiver.var != 0) {
            throw new IllegalStateException("generateRingPositions search captures changed");
        }
        AbstractInsnNode executor = nextRealInstruction(supplier);
        AbstractInsnNode name = nextRealInstruction(executor);
        AbstractInsnNode named = nextRealInstruction(name);
        AbstractInsnNode submit = nextRealInstruction(named);
        if (!(executor instanceof MethodInsnNode background)
                || background.getOpcode() != Opcodes.INVOKESTATIC
                || !background.owner.equals("net/minecraft/util/Util")
                || !background.name.equals("backgroundExecutor")
                || !(name instanceof LdcInsnNode)
                || !(named instanceof MethodInsnNode forName)
                || !forName.name.equals("forName")
                || !forName.desc.equals("(Ljava/lang/String;)Ljava/util/concurrent/Executor;")
                || !(submit instanceof MethodInsnNode supplyAsync)
                || supplyAsync.getOpcode() != Opcodes.INVOKESTATIC
                || !supplyAsync.owner.equals("java/util/concurrent/CompletableFuture")
                || !supplyAsync.name.equals("supplyAsync")
                || !supplyAsync.desc.equals("(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)"
                        + "Ljava/util/concurrent/CompletableFuture;")) {
            throw new IllegalStateException("generateRingPositions submits its search in another shape");
        }
        InsnList defer = new InsnList();
        defer.add(new VarInsnNode(Opcodes.ILOAD, x.var));
        defer.add(new VarInsnNode(Opcodes.ILOAD, z.var));
        defer.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BROWSER_RING_POSITIONS, "defer",
                "(Ljava/util/function/Supplier;II)Ljava/lang/Object;", false));
        generate.instructions.insert(submit, defer);
        generate.instructions.remove(executor);
        generate.instructions.remove(name);
        generate.instructions.remove(named);
        generate.instructions.remove(submit);
        InsnList lazy = new InsnList();
        lazy.add(new LdcInsnNode(blockRadius));
        lazy.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BROWSER_RING_POSITIONS, "sequence",
                "(Ljava/util/List;I)Ljava/util/concurrent/CompletableFuture;", false));
        generate.instructions.insert(sequence, lazy);
        generate.instructions.remove(sequence);
        // Same locals and branch targets; the stack at the replaced calls is no deeper than the
        // captures before the search, so vanilla's frames and maxima stay valid.
        write(node, output);
    }

    /**
     * The block radius of the ring-position search, after checking the lambda's shape: the
     * centre {@code sectionToBlockCoord(startChunkX, 8), 0, sectionToBlockCoord(startChunkZ, 8)},
     * one radius constant, one findBiomeHorizontal, two ChunkPos results (the found block's
     * chunk through blockToSectionCoord, or the start chunk) and no arithmetic of its own.
     */
    private static int ringPositionSearchRadius(MethodNode search) {
        String sectionPos = "net/minecraft/core/SectionPos";
        AbstractInsnNode cursor = search.instructions.getFirst();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        AbstractInsnNode[] head = new AbstractInsnNode[11];
        for (int index = 0; index < head.length; index++) {
            if (cursor == null) {
                throw new IllegalStateException("ring-position search lambda is too short");
            }
            head[index] = cursor;
            cursor = nextRealInstruction(cursor);
        }
        boolean centre = head[0] instanceof VarInsnNode self && self.getOpcode() == Opcodes.ALOAD
                && self.var == 0
                && head[1] instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && field.desc.equals("Lnet/minecraft/world/level/biome/BiomeSource;")
                && head[2] instanceof VarInsnNode x && x.getOpcode() == Opcodes.ILOAD && x.var == 1
                && head[3] instanceof IntInsnNode xMiddle && xMiddle.operand == 8
                && head[4] instanceof MethodInsnNode xBlock && xBlock.owner.equals(sectionPos)
                && xBlock.name.equals("sectionToBlockCoord") && xBlock.desc.equals("(II)I")
                && head[5].getOpcode() == Opcodes.ICONST_0
                && head[6] instanceof VarInsnNode z && z.getOpcode() == Opcodes.ILOAD && z.var == 2
                && head[7] instanceof IntInsnNode zMiddle && zMiddle.operand == 8
                && head[8] instanceof MethodInsnNode zBlock && zBlock.owner.equals(sectionPos)
                && zBlock.name.equals("sectionToBlockCoord") && zBlock.desc.equals("(II)I");
        Integer radius = head[9] instanceof IntInsnNode push
                && (push.getOpcode() == Opcodes.BIPUSH || push.getOpcode() == Opcodes.SIPUSH)
                ? Integer.valueOf(push.operand)
                : head[9] instanceof LdcInsnNode constant && constant.cst instanceof Integer value
                        ? value : null;
        int searches = 0;
        int results = 0;
        int chunkCoordinates = 0;
        for (AbstractInsnNode instruction : search.instructions.toArray()) {
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.IADD && opcode <= Opcodes.LXOR || opcode == Opcodes.IINC) {
                throw new IllegalStateException("ring-position search lambda computes its own offsets");
            }
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/world/level/biome/BiomeSource")
                        && call.name.equals("findBiomeHorizontal")) {
                    searches++;
                } else if (call.owner.equals("net/minecraft/world/level/ChunkPos")
                        && call.name.equals("<init>") && call.desc.equals("(II)V")) {
                    results++;
                } else if (call.owner.equals(sectionPos) && call.name.equals("blockToSectionCoord")
                        && call.desc.equals("(I)I")) {
                    chunkCoordinates++;
                }
            }
        }
        if (!centre || radius == null || radius < 0 || searches != 1 || results != 2
                || chunkCoordinates != 2) {
            throw new IllegalStateException("ring-position search lambda shape changed: centre=" + centre
                    + " radius=" + radius + " searches=" + searches + " results=" + results
                    + " chunkCoordinates=" + chunkCoordinates);
        }
        return radius;
    }

    public static void patchNoiseBasedChunkGeneratorBrowserSynchronous(
            String jar, Path output, boolean deepWorldgenCheckpoints) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.class");
        MethodNode compiledDoFill = findNullable(node, "doFill", NOISE_CHUNK_GENERATOR_DO_FILL_263);
        if (compiledDoFill != null) {
            patchNoiseBasedChunkGenerator263(node, compiledDoFill, output, deepWorldgenCheckpoints);
            return;
        }
        MethodNode method = find(
                node,
                "doFill",
                "(Lnet/minecraft/world/level/levelgen/blending/Blender;"
                        + "Lnet/minecraft/world/level/StructureManager;"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;II)"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;");
        cacheNoiseBasedChunkGeneratorDoFillConstants(method);
        requireWorldgenSchedulerCalls("NoiseBasedChunkGenerator.doFill", method, 0);
        if (deepWorldgenCheckpoints) {
            patchDeepWorldgenPulseBackEdges(
                    method, "NoiseBasedChunkGenerator.doFill");
        }
        MethodNode applyCarvers = find(
                node,
                "applyCarvers",
                "(Lnet/minecraft/server/level/WorldGenRegion;J"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/biome/BiomeManager;"
                        + "Lnet/minecraft/world/level/StructureManager;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;)V");
        requireWorldgenSchedulerCalls(
                "NoiseBasedChunkGenerator.applyCarvers", applyCarvers, 0);
        if (deepWorldgenCheckpoints) {
            patchDeepWorldgenPulseBackEdges(
                    applyCarvers, "NoiseBasedChunkGenerator.applyCarvers");
        }
        writeComputeFrames(node, output);
    }

    private static final String NOISE_CHUNK_GENERATOR_DO_FILL_263 =
            "(Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                    + "Lnet/minecraft/world/level/chunk/ChunkAccess;)V";

    private static final String NOISE_CHUNK_GENERATOR_GENERATE_CARVERS_263 =
            "(Lnet/minecraft/world/level/chunk/ChunkAccess;"
                    + "Lnet/minecraft/world/level/levelgen/blending/Blender;"
                    + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                    + "Lnet/minecraft/world/level/levelgen/RandomState;"
                    + "Lnet/minecraft/world/level/biome/BiomeManager;"
                    + "Lnet/minecraft/server/level/WorldGenRegion;"
                    + "Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;)V";

    /**
     * Minecraft 26.3 merged NOISE, SURFACE and CARVERS into one TERRAIN step:
     * {@code doFill(NoiseChunk, ChunkAccess)} samples the whole chunk volume once and then fills
     * blocks in three nested loops, and {@code generateCarvers} replaces {@code applyCarvers}
     * (carved blocks are applied later through CarvingMask, which WorldgenPatches263 pulses).
     * The debugVoidTerrain check moved to buildTerrain, so only the default block is hoisted.
     * The volume sample before the first loop stays non-preemptible (measured separately).
     */
    private static void patchNoiseBasedChunkGenerator263(
            ClassNode node, MethodNode doFill, Path output, boolean deepWorldgenCheckpoints)
            throws IOException {
        hoistNoiseBasedChunkGeneratorDefaultBlock(doFill);
        requireWorldgenSchedulerCalls("NoiseBasedChunkGenerator.doFill", doFill, 0);
        MethodNode generateCarvers = find(
                node, "generateCarvers", NOISE_CHUNK_GENERATOR_GENERATE_CARVERS_263);
        requireWorldgenSchedulerCalls(
                "NoiseBasedChunkGenerator.generateCarvers", generateCarvers, 0);
        if (deepWorldgenCheckpoints) {
            patchDeepWorldgenPulseBackEdges(doFill, "NoiseBasedChunkGenerator.doFill", 3);
            patchDeepWorldgenPulseBackEdges(
                    generateCarvers, "NoiseBasedChunkGenerator.generateCarvers", 3);
        }
        writeComputeFrames(node, output);
        System.out.println("Patched 26.3 NoiseBasedChunkGenerator doFill/generateCarvers"
                + (deepWorldgenCheckpoints ? " with deep worldgen pulses" : ""));
    }

    /** Evaluates {@code settings.value().defaultBlock()} once per doFill instead of per block. */
    private static void hoistNoiseBasedChunkGeneratorDefaultBlock(MethodNode method) {
        String generator = "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator";
        String settings = "net/minecraft/world/level/levelgen/NoiseGeneratorSettings";
        int defaultBlockLocal = method.maxLocals++;
        int replacements = 0;
        for (var instruction = method.instructions.getFirst(); instruction != null;) {
            var next = instruction.getNext();
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(settings)
                    && call.name.equals("defaultBlock")
                    && call.desc.equals("()Lnet/minecraft/world/level/block/state/BlockState;")) {
                var cast = previousRealInstruction(call);
                var value = previousRealInstruction(cast);
                var field = previousRealInstruction(value);
                var owner = previousRealInstruction(field);
                if (cast instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.CHECKCAST
                        && type.desc.equals(settings)
                        && value instanceof MethodInsnNode valueCall
                        && valueCall.getOpcode() == Opcodes.INVOKEINTERFACE
                        && valueCall.owner.equals("net/minecraft/core/Holder")
                        && valueCall.name.equals("value")
                        && field instanceof FieldInsnNode settingsField
                        && settingsField.getOpcode() == Opcodes.GETFIELD
                        && settingsField.owner.equals(generator)
                        && settingsField.name.equals("settings")
                        && owner instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD
                        && load.var == 0) {
                    method.instructions.insertBefore(owner, new VarInsnNode(
                            Opcodes.ALOAD, defaultBlockLocal));
                    method.instructions.remove(owner);
                    method.instructions.remove(field);
                    method.instructions.remove(value);
                    method.instructions.remove(cast);
                    method.instructions.remove(call);
                    replacements++;
                }
            }
            instruction = next;
        }
        if (replacements != 1) {
            throw new IllegalStateException(
                    "NoiseBasedChunkGenerator.doFill default-block cache points: " + replacements);
        }
        InsnList constants = new InsnList();
        constants.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constants.add(new FieldInsnNode(
                Opcodes.GETFIELD, generator, "settings", "Lnet/minecraft/core/Holder;"));
        constants.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/core/Holder",
                "value",
                "()Ljava/lang/Object;",
                true));
        constants.add(new TypeInsnNode(Opcodes.CHECKCAST, settings));
        constants.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                settings,
                "defaultBlock",
                "()Lnet/minecraft/world/level/block/state/BlockState;",
                false));
        constants.add(new VarInsnNode(Opcodes.ASTORE, defaultBlockLocal));
        method.instructions.insert(constants);
    }

    private static void cacheNoiseBasedChunkGeneratorDoFillConstants(MethodNode method) {
        String generator = "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator";
        String settings = "net/minecraft/world/level/levelgen/NoiseGeneratorSettings";
        int debugVoidLocal = method.maxLocals++;
        int defaultBlockLocal = method.maxLocals++;
        VarInsnNode chunkPosStore = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ASTORE
                    && previousRealInstruction(store) instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/world/level/chunk/ChunkAccess")
                    && call.name.equals("getPos")
                    && call.desc.equals("()Lnet/minecraft/world/level/ChunkPos;")) {
                chunkPosStore = store;
                break;
            }
        }
        if (chunkPosStore == null) {
            throw new IllegalStateException(
                    "NoiseBasedChunkGenerator.doFill chunk-position cache point was not found");
        }

        InsnList constants = new InsnList();
        constants.add(new VarInsnNode(Opcodes.ALOAD, chunkPosStore.var));
        constants.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/SharedConstants",
                "debugVoidTerrain",
                "(Lnet/minecraft/world/level/ChunkPos;)Z",
                false));
        constants.add(new VarInsnNode(Opcodes.ISTORE, debugVoidLocal));
        constants.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constants.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                generator,
                "settings",
                "Lnet/minecraft/core/Holder;"));
        constants.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/core/Holder",
                "value",
                "()Ljava/lang/Object;",
                true));
        constants.add(new TypeInsnNode(Opcodes.CHECKCAST, settings));
        constants.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                settings,
                "defaultBlock",
                "()Lnet/minecraft/world/level/block/state/BlockState;",
                false));
        constants.add(new VarInsnNode(Opcodes.ASTORE, defaultBlockLocal));

        int debugVoidReplacements = 0;
        int defaultBlockReplacements = 0;
        for (var instruction = method.instructions.getFirst(); instruction != null;) {
            var next = instruction.getNext();
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/SharedConstants")
                    && call.name.equals("debugVoidTerrain")
                    && call.desc.equals("(Lnet/minecraft/world/level/ChunkPos;)Z")) {
                var getPos = previousRealInstruction(call);
                var chunk = previousRealInstruction(getPos);
                if (getPos instanceof MethodInsnNode getPosCall
                        && getPosCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && getPosCall.owner.equals("net/minecraft/world/level/chunk/ChunkAccess")
                        && getPosCall.name.equals("getPos")
                        && getPosCall.desc.equals("()Lnet/minecraft/world/level/ChunkPos;")
                        && chunk instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD
                        && load.var == 4) {
                    method.instructions.insertBefore(chunk, new VarInsnNode(
                            Opcodes.ILOAD, debugVoidLocal));
                    method.instructions.remove(chunk);
                    method.instructions.remove(getPos);
                    method.instructions.remove(call);
                    debugVoidReplacements++;
                }
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(settings)
                    && call.name.equals("defaultBlock")
                    && call.desc.equals("()Lnet/minecraft/world/level/block/state/BlockState;")) {
                var cast = previousRealInstruction(call);
                var value = previousRealInstruction(cast);
                var field = previousRealInstruction(value);
                var owner = previousRealInstruction(field);
                if (cast instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.CHECKCAST
                        && type.desc.equals(settings)
                        && value instanceof MethodInsnNode valueCall
                        && valueCall.getOpcode() == Opcodes.INVOKEINTERFACE
                        && valueCall.owner.equals("net/minecraft/core/Holder")
                        && valueCall.name.equals("value")
                        && field instanceof FieldInsnNode settingsField
                        && settingsField.getOpcode() == Opcodes.GETFIELD
                        && settingsField.owner.equals(generator)
                        && settingsField.name.equals("settings")
                        && owner instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD
                        && load.var == 0) {
                    method.instructions.insertBefore(owner, new VarInsnNode(
                            Opcodes.ALOAD, defaultBlockLocal));
                    method.instructions.remove(owner);
                    method.instructions.remove(field);
                    method.instructions.remove(value);
                    method.instructions.remove(cast);
                    method.instructions.remove(call);
                    defaultBlockReplacements++;
                }
            }
            instruction = next;
        }
        if (debugVoidReplacements != 1 || defaultBlockReplacements != 1) {
            throw new IllegalStateException(
                    "NoiseBasedChunkGenerator.doFill constant caches mismatch: debugVoid="
                            + debugVoidReplacements + ", defaultBlock=" + defaultBlockReplacements);
        }
        method.instructions.insert(chunkPosStore, constants);
    }

    public static void patchImprovedNoiseBrowserHotPath(String jar, Path output) throws IOException {
        // 26.3: single-octave noise is the float synth/PerlinNoise (GradientNoise) kernel.
        if (droppedForCompiledDensityRuntime(
                "MinecraftClientPatcher.patchImprovedNoiseBrowserHotPath", jar,
                "net/minecraft/world/level/levelgen/synth/ImprovedNoise.class")) {
            return;
        }
        String owner = "net/minecraft/world/level/levelgen/synth/ImprovedNoise";
        ClassNode node = read(jar, owner + ".class");
        MethodNode noise = find(node, "noise", "(DDDDD)D");
        InsnList noiseCode = new InsnList();
        noiseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        noiseCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "p", "[B"));
        noiseCode.add(new VarInsnNode(Opcodes.DLOAD, 1));
        noiseCode.add(new VarInsnNode(Opcodes.DLOAD, 3));
        noiseCode.add(new VarInsnNode(Opcodes.DLOAD, 5));
        noiseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        noiseCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "xo", "D"));
        noiseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        noiseCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "yo", "D"));
        noiseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        noiseCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "zo", "D"));
        noiseCode.add(new VarInsnNode(Opcodes.DLOAD, 7));
        noiseCode.add(new VarInsnNode(Opcodes.DLOAD, 9));
        noiseCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserImprovedNoise",
                "noise",
                "([BDDDDDDDD)D",
                false));
        noiseCode.add(new InsnNode(Opcodes.DRETURN));
        replace(noise, noiseCode, 17, 11);

        MethodNode method = find(node, "sampleAndLerp", "(IIIDDDD)D");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "p", "[B"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.DLOAD, 4));
        code.add(new VarInsnNode(Opcodes.DLOAD, 6));
        code.add(new VarInsnNode(Opcodes.DLOAD, 8));
        code.add(new VarInsnNode(Opcodes.DLOAD, 10));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserImprovedNoise",
                "sampleAndLerp",
                "([BIIIDDDD)D",
                false));
        code.add(new InsnNode(Opcodes.DRETURN));
        replace(method, code, 12, 12);
        write(node, output);
    }

    public static void patchBiomeManagerBrowserNearestCorner(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/biome/BiomeManager";
        ClassNode node = read(jar, owner + ".class");
        MethodNode coordinates = findNullable(node, "getBiome", "(III)Lnet/minecraft/core/Holder;");
        if (coordinates != null) {
            patchBiomeManagerNearestCornerCoordinates(node, coordinates, owner, output);
            return;
        }
        MethodNode method = find(
                node,
                "getBiome",
                "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;");
        InsnList code = new InsnList();
        for (int coordinate = 0; coordinate < 3; coordinate++) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/core/BlockPos",
                    coordinate == 0 ? "getX" : coordinate == 1 ? "getY" : "getZ",
                    "()I",
                    false));
            code.add(new InsnNode(Opcodes.ICONST_2));
            code.add(new InsnNode(Opcodes.ISUB));
            code.add(new VarInsnNode(Opcodes.ISTORE, coordinate + 2));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "biomeZoomSeed", "J"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBiomeManager",
                "nearestCorner",
                "(JIII)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "noiseBiomeSource",
                "Lnet/minecraft/world/level/biome/BiomeManager$NoiseBiomeSource;"));
        for (int coordinate = 0; coordinate < 3; coordinate++) {
            code.add(new VarInsnNode(Opcodes.ILOAD, coordinate + 2));
            code.add(new InsnNode(Opcodes.ICONST_2));
            code.add(new InsnNode(Opcodes.ISHR));
            code.add(new VarInsnNode(Opcodes.ILOAD, 5));
            if (coordinate < 2) {
                code.add(new InsnNode(coordinate == 0 ? Opcodes.ICONST_2 : Opcodes.ICONST_1));
                code.add(new InsnNode(Opcodes.IUSHR));
            }
            code.add(new InsnNode(Opcodes.ICONST_1));
            code.add(new InsnNode(Opcodes.IAND));
            code.add(new InsnNode(Opcodes.IADD));
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/world/level/biome/BiomeManager$NoiseBiomeSource",
                "getNoiseBiome",
                "(III)Lnet/minecraft/core/Holder;",
                true));
        code.add(new InsnNode(Opcodes.ARETURN));
        // Peak stack: source, the x and y arguments, then z >> 2, the corner bits and the mask.
        replace(method, code, 6, 6);
        write(node, output);
    }

    /**
     * Minecraft 26.3 moved the zoom algorithm into {@code getBiome(int, int, int)} (getBiome(BlockPos)
     * delegates to it, and EnvironmentAttributeSystem calls it directly), removed
     * {@code BiomeManager$NoiseBiomeSource} and stores the source as a {@code BiomeResolver}.
     * The replacement body is the 26.2 one with coordinates read from locals 1..3.
     */
    private static void patchBiomeManagerNearestCornerCoordinates(
            ClassNode node, MethodNode method, String owner, Path output) throws IOException {
        String resolver = "net/minecraft/world/level/biome/BiomeResolver";
        boolean resolverField = node.fields.stream().anyMatch(field ->
                field.name.equals("noiseBiomeSource") && field.desc.equals("L" + resolver + ";")
                        && (field.access & Opcodes.ACC_STATIC) == 0);
        if (!resolverField || (method.access & Opcodes.ACC_STATIC) != 0) {
            throw new IllegalStateException(
                    "BiomeManager.getBiome(III) no longer reads a BiomeResolver noiseBiomeSource");
        }
        int resolverCalls = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals(resolver)
                    && call.name.equals("getNoiseBiome")
                    && call.desc.equals("(III)Lnet/minecraft/core/Holder;")) {
                resolverCalls++;
            }
        }
        if (resolverCalls != 1) {
            throw new IllegalStateException(
                    "BiomeManager.getBiome(III) resolver call shape changed: " + resolverCalls);
        }
        InsnList code = new InsnList();
        for (int coordinate = 0; coordinate < 3; coordinate++) {
            code.add(new VarInsnNode(Opcodes.ILOAD, coordinate + 1));
            code.add(new InsnNode(Opcodes.ICONST_2));
            code.add(new InsnNode(Opcodes.ISUB));
            code.add(new VarInsnNode(Opcodes.ISTORE, coordinate + 4));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "biomeZoomSeed", "J"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new VarInsnNode(Opcodes.ILOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBiomeManager",
                "nearestCorner",
                "(JIII)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 7));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "noiseBiomeSource", "L" + resolver + ";"));
        for (int coordinate = 0; coordinate < 3; coordinate++) {
            code.add(new VarInsnNode(Opcodes.ILOAD, coordinate + 4));
            code.add(new InsnNode(Opcodes.ICONST_2));
            code.add(new InsnNode(Opcodes.ISHR));
            code.add(new VarInsnNode(Opcodes.ILOAD, 7));
            if (coordinate < 2) {
                code.add(new InsnNode(coordinate == 0 ? Opcodes.ICONST_2 : Opcodes.ICONST_1));
                code.add(new InsnNode(Opcodes.IUSHR));
            }
            code.add(new InsnNode(Opcodes.ICONST_1));
            code.add(new InsnNode(Opcodes.IAND));
            code.add(new InsnNode(Opcodes.IADD));
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                resolver,
                "getNoiseBiome",
                "(III)Lnet/minecraft/core/Holder;",
                true));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 6, 8);
        write(node, output);
    }

    public static void patchAquiferBrowserNearestCenters(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/levelgen/Aquifer$NoiseBasedAquifer";
        ClassNode node = read(jar, owner + ".class");
        String resultField = "browserNearestResult";
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                resultField,
                "[I",
                null,
                null));

        // 26.3: the aquifer is built from a DensitySamplerSet and a DensityVolume, and
        // computeSubstance takes the block coordinates directly: x/y/z are locals 1/2/3 instead
        // of 4/5/6, and the four nearest distances and indices are locals 10..17 instead of
        // 11..18. The grid, cache and nearest-center loop are unchanged, so the same helper
        // fills the eight locals.
        MethodNode compiledConstructor = findNullable(
                node,
                "<init>",
                "(Lnet/minecraft/world/level/levelgen/densityfunction/DensitySamplerSet;"
                        + "Lnet/minecraft/world/level/levelgen/Aquifer$Config;"
                        + "Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;"
                        + "Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;"
                        + "Lnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)V");
        boolean blockCoordinates = compiledConstructor != null;
        int coordinateLocal = blockCoordinates ? 1 : 4;
        int nearestLocal = blockCoordinates ? 10 : 11;
        MethodNode constructor = blockCoordinates ? compiledConstructor : find(
                node,
                "<init>",
                "(Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Lnet/minecraft/world/level/ChunkPos;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseRouter;"
                        + "Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;II"
                        + "Lnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("Aquifer browser result-cache constructor return was not found");
        }
        InsnList initializeResult = new InsnList();
        initializeResult.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializeResult.add(new IntInsnNode(Opcodes.BIPUSH, 8));
        initializeResult.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        initializeResult.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, resultField, "[I"));
        constructor.instructions.insertBefore(constructorReturn, initializeResult);

        MethodNode method = find(
                node,
                "computeSubstance",
                blockCoordinates
                        ? "(IIID)Lnet/minecraft/world/level/block/state/BlockState;"
                        : "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;D)"
                                + "Lnet/minecraft/world/level/block/state/BlockState;");
        MethodInsnNode firstGridX = null;
        MethodInsnNode firstStatus = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (firstGridX == null
                    && call.owner.equals(owner)
                    && call.name.equals("gridX")
                    && call.desc.equals("(I)I")) {
                firstGridX = call;
            }
            if (firstStatus == null
                    && call.owner.equals(owner)
                    && call.name.equals("getAquiferStatus")
                    && call.desc.equals("(I)Lnet/minecraft/world/level/levelgen/Aquifer$FluidStatus;")) {
                firstStatus = call;
            }
        }
        if (firstGridX == null || firstStatus == null) {
            throw new IllegalStateException("Aquifer browser nearest-center patch points were not found");
        }
        AbstractInsnNode originalPathStart = previousRealInstruction(firstGridX);
        originalPathStart = previousRealInstruction(originalPathStart);
        originalPathStart = previousRealInstruction(originalPathStart);
        AbstractInsnNode statusReceiver = previousRealInstruction(firstStatus);
        statusReceiver = previousRealInstruction(statusReceiver);
        if (!(originalPathStart instanceof VarInsnNode loadX)
                || loadX.getOpcode() != Opcodes.ILOAD
                || loadX.var != coordinateLocal
                || !(statusReceiver instanceof VarInsnNode loadThis)
                || loadThis.getOpcode() != Opcodes.ALOAD
                || loadThis.var != 0) {
            throw new IllegalStateException("Aquifer browser nearest-center bytecode shape changed");
        }

        LabelNode originalPath = new LabelNode();
        LabelNode nearestReady = new LabelNode();
        method.instructions.insertBefore(statusReceiver, nearestReady);
        InsnList fastPath = new InsnList();
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "aquiferLocationCache", "[J"));
        for (String field : new String[] {
                "minGridX", "minGridY", "minGridZ", "gridSizeX", "gridSizeZ"
        }) {
            fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "I"));
        }
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, coordinateLocal));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, coordinateLocal + 1));
        fastPath.add(new VarInsnNode(Opcodes.ILOAD, coordinateLocal + 2));
        fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, owner, resultField, "[I"));
        fastPath.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserAquifer",
                "selectNearestCached",
                "([JIIIIIIII[I)Z",
                false));
        fastPath.add(new JumpInsnNode(Opcodes.IFEQ, originalPath));
        for (int index = 0; index < 8; index++) {
            fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, owner, resultField, "[I"));
            fastPath.add(new IntInsnNode(Opcodes.BIPUSH, index));
            fastPath.add(new InsnNode(Opcodes.IALOAD));
            fastPath.add(new VarInsnNode(Opcodes.ISTORE, nearestLocal + index));
        }
        fastPath.add(new JumpInsnNode(Opcodes.GOTO, nearestReady));
        fastPath.add(originalPath);
        method.instructions.insertBefore(originalPathStart, fastPath);
        writeComputeFrames(node, output);
    }

    public static void patchPerlinNoiseBrowserDoubleWrap(String jar, Path output)
            throws IOException {
        // 26.3: octaves moved to synth/NoiseStack and GradientNoise.wrap uses Math.floor, so
        // neither the amplitude array nor the long-free wrap has anything left to replace.
        if (droppedForCompiledDensityRuntime(
                "MinecraftClientPatcher.patchPerlinNoiseBrowserDoubleWrap", jar,
                "net/minecraft/world/level/levelgen/synth/PerlinNoise#<init>("
                        + "Lnet/minecraft/util/RandomSource;Lcom/mojang/datafixers/util/Pair;Z)V",
                "net/minecraft/world/level/levelgen/synth/PerlinNoise#amplitudes",
                "net/minecraft/world/level/levelgen/synth/PerlinNoise#getValue",
                "net/minecraft/world/level/levelgen/synth/PerlinNoise#wrap(D)D")) {
            return;
        }
        String owner = "net/minecraft/world/level/levelgen/synth/PerlinNoise";
        ClassNode node = read(jar, owner + ".class");
        String browserAmplitudes = "browserAmplitudes";
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                browserAmplitudes,
                "[D",
                null,
                null));

        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/util/RandomSource;Lcom/mojang/datafixers/util/Pair;Z)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("Perlin browser amplitude constructor return was not found");
        }
        InsnList initializeAmplitudes = new InsnList();
        initializeAmplitudes.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializeAmplitudes.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializeAmplitudes.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "amplitudes",
                "Lit/unimi/dsi/fastutil/doubles/DoubleList;"));
        initializeAmplitudes.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPerlinNoise",
                "copyAmplitudes",
                "(Lit/unimi/dsi/fastutil/doubles/DoubleList;)[D",
                false));
        initializeAmplitudes.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                browserAmplitudes,
                "[D"));
        constructor.instructions.insertBefore(constructorReturn, initializeAmplitudes);

        MethodNode getValue = null;
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.equals("getValue")
                    || (!candidate.desc.equals("(DDDDDZ)D")
                            && !candidate.desc.equals("(DDDDD)D"))) {
                continue;
            }
            if (getValue != null) {
                throw new IllegalStateException("Multiple PerlinNoise detailed getValue methods found");
            }
            getValue = candidate;
        }
        if (getValue == null) {
            throw new IllegalStateException("PerlinNoise detailed getValue method was not found");
        }
        int amplitudeReads = 0;
        for (AbstractInsnNode instruction = getValue.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals(owner)
                    || !field.name.equals("amplitudes")
                    || !field.desc.equals("Lit/unimi/dsi/fastutil/doubles/DoubleList;")) {
                continue;
            }
            AbstractInsnNode index = nextRealInstruction(field);
            AbstractInsnNode read = nextRealInstruction(index);
            if (!(index instanceof VarInsnNode loadIndex)
                    || loadIndex.getOpcode() != Opcodes.ILOAD
                    || !(read instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !call.owner.equals("it/unimi/dsi/fastutil/doubles/DoubleList")
                    || !call.name.equals("getDouble")
                    || !call.desc.equals("(I)D")) {
                continue;
            }
            field.name = browserAmplitudes;
            field.desc = "[D";
            getValue.instructions.set(call, new InsnNode(Opcodes.DALOAD));
            amplitudeReads++;
        }
        if (amplitudeReads != 1) {
            throw new IllegalStateException(
                    "Perlin browser amplitude read patch point changed: " + amplitudeReads);
        }

        MethodNode method = find(node, "wrap", "(D)D");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.DLOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserPerlinNoise",
                "wrap",
                "(D)D",
                false));
        code.add(new InsnNode(Opcodes.DRETURN));
        replace(method, code, 2, 2);

        MethodNode compatibilityValue = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$getValue",
                "(DDDDDZ)D",
                null,
                null);
        compatibilityValue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int local = 1; local <= 9; local += 2) {
            compatibilityValue.instructions.add(new VarInsnNode(Opcodes.DLOAD, local));
        }
        if (getValue.desc.endsWith("Z)D")) {
            compatibilityValue.instructions.add(new VarInsnNode(Opcodes.ILOAD, 11));
        }
        compatibilityValue.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                getValue.name,
                getValue.desc,
                false));
        compatibilityValue.instructions.add(new InsnNode(Opcodes.DRETURN));
        compatibilityValue.maxStack = getValue.desc.endsWith("Z)D") ? 12 : 11;
        compatibilityValue.maxLocals = 12;
        node.methods.add(compatibilityValue);

        MethodNode hasOriginY = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "gaius$hasOriginY",
                "()Z",
                null,
                null);
        hasOriginY.instructions.add(new InsnNode(
                getValue.desc.endsWith("Z)D") ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        hasOriginY.instructions.add(new InsnNode(Opcodes.IRETURN));
        hasOriginY.maxStack = 1;
        hasOriginY.maxLocals = 0;
        node.methods.add(hasOriginY);
        writeComputeFrames(node, output);
    }

    public static void patchBeardifierBrowserPackedCompute(String jar, Path output)
            throws IOException {
        // 26.3: Beardifier is a DensitySampler whose sampleVolume already skips volumes outside
        // the affected box; its float kernel must not be replaced by the double JS kernel.
        if (droppedForCompiledDensityRuntime(
                "MinecraftClientPatcher.patchBeardifierBrowserPackedCompute", jar,
                "net/minecraft/world/level/levelgen/Beardifier#compute("
                        + "Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D",
                "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext")) {
            return;
        }
        String owner = "net/minecraft/world/level/levelgen/Beardifier";
        ClassNode node = read(jar, owner + ".class");
        String piecesField = "browserPackedPieces";
        String junctionsField = "browserPackedJunctions";
        for (String field : new String[] {piecesField, junctionsField}) {
            node.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                    field,
                    "[I",
                    null,
                    null));
        }

        MethodNode constructor = find(
                node,
                "<init>",
                "(Ljava/util/List;Ljava/util/List;"
                        + "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("Beardifier browser constructor return was not found");
        }
        InsnList initializePacked = new InsnList();
        initializePacked.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializePacked.add(new VarInsnNode(Opcodes.ALOAD, 1));
        initializePacked.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBeardifier",
                "packPieces",
                "(Ljava/util/List;)[I",
                false));
        initializePacked.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, piecesField, "[I"));
        initializePacked.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializePacked.add(new VarInsnNode(Opcodes.ALOAD, 2));
        initializePacked.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBeardifier",
                "packJunctions",
                "(Ljava/util/List;)[I",
                false));
        initializePacked.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, junctionsField, "[I"));
        constructor.instructions.insertBefore(constructorReturn, initializePacked);
        constructor.maxStack = Math.max(constructor.maxStack, 2);

        MethodNode compute = find(
                node,
                "compute",
                "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D");
        LabelNode populated = new LabelNode();
        LabelNode inside = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "affectedBox",
                "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;"));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, populated));
        code.add(new InsnNode(Opcodes.DCONST_0));
        code.add(new InsnNode(Opcodes.DRETURN));
        code.add(populated);
        for (int coordinate = 0; coordinate < 3; coordinate++) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext",
                    coordinate == 0 ? "blockX" : coordinate == 1 ? "blockY" : "blockZ",
                    "()I",
                    true));
            code.add(new VarInsnNode(Opcodes.ISTORE, coordinate + 2));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "affectedBox",
                "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/levelgen/structure/BoundingBox",
                "isInside",
                "(III)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFNE, inside));
        code.add(new InsnNode(Opcodes.DCONST_0));
        code.add(new InsnNode(Opcodes.DRETURN));
        code.add(inside);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, piecesField, "[I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, junctionsField, "[I"));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "BEARD_KERNEL", "[F"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBeardifier",
                "compute",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;III)D",
                false));
        code.add(new InsnNode(Opcodes.DRETURN));
        replace(compute, code, 6, 5);
        write(node, output);
    }

    public static void patchSurfaceSystemBrowserSynchronous(
            String jar, Path output, boolean deepWorldgenCheckpoints) throws IOException {
        if (jarHasEntry(jar, MATERIAL_SYSTEM + ".class")) {
            // The dispatcher names the 26.2 class file; write MaterialSystem next to it instead.
            Path levelgenRoot = output.getParent();
            if (levelgenRoot == null || !output.getFileName().toString().equals("SurfaceSystem.class")) {
                throw new IllegalStateException("Unexpected SurfaceSystem output path " + output);
            }
            patchMaterialSystemBuildSurface263(jar,
                    levelgenRoot.resolve("material/MaterialSystem.class"), deepWorldgenCheckpoints);
            return;
        }
        ClassNode node = read(jar, "net/minecraft/world/level/levelgen/SurfaceSystem.class");
        String legacyDescriptor =
                "(Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/biome/BiomeManager;"
                        + "Lnet/minecraft/core/Registry;Z"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V";
        String currentDescriptor =
                "(Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/biome/BiomeManager;Z"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;"
                        + "Ljava/util/Set;)V";
        MethodNode method = null;
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.equals("buildSurface")
                    || (!candidate.desc.equals(legacyDescriptor)
                            && !candidate.desc.equals(currentDescriptor))) {
                continue;
            }
            if (method != null) {
                throw new IllegalStateException("Multiple SurfaceSystem.buildSurface methods found");
            }
            method = candidate;
        }
        if (method == null) {
            throw new IllegalStateException("SurfaceSystem.buildSurface method was not found");
        }
        requireWorldgenSchedulerCalls("SurfaceSystem.buildSurface", method, 0);
        if (deepWorldgenCheckpoints) {
            patchDeepWorldgenPulseBackEdges(method, "SurfaceSystem.buildSurface");
            writeComputeFrames(node, output);
        } else {
            write(node, output);
        }
    }

    private static final String MATERIAL_SYSTEM =
            "net/minecraft/world/level/levelgen/material/MaterialSystem";

    /**
     * Minecraft 26.3 renamed SurfaceSystem to material/MaterialSystem; buildSurface lost the
     * boolean parameter and takes a MaterialRule instead of a SurfaceRules$RuleSource. Its four
     * column/section loops keep the deep pulses of the 26.2 patch (TERRAIN also runs the carvers
     * right after it, so the step is heavier than 26.2 SURFACE).
     */
    private static void patchMaterialSystemBuildSurface263(
            String jar, Path output, boolean deepWorldgenCheckpoints) throws IOException {
        ClassNode node = read(jar, MATERIAL_SYSTEM + ".class");
        MethodNode method = find(
                node,
                "buildSurface",
                "(Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/biome/BiomeManager;"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;"
                        + "Ljava/util/Set;)V");
        requireWorldgenSchedulerCalls("MaterialSystem.buildSurface", method, 0);
        if (deepWorldgenCheckpoints) {
            patchDeepWorldgenPulseBackEdges(method, "MaterialSystem.buildSurface", 4);
            writeComputeFrames(node, output);
        } else {
            write(node, output);
        }
    }

    public static void patchSurfaceRulesContextBrowserReusableBiomeSupplier(
            String jar, Path output) throws IOException {
        // 26.3: SurfaceRules$Context became material/MaterialRuleContext, which already resolves
        // the biome lazily; its int shadow counters are WorldgenPatches263's job.
        if (droppedForCompiledDensityRuntime(
                "MinecraftClientPatcher.patchSurfaceRulesContextBrowserReusableBiomeSupplier", jar,
                "net/minecraft/world/level/levelgen/SurfaceRules$Context.class")) {
            return;
        }
        String owner = "net/minecraft/world/level/levelgen/SurfaceRules$Context";
        String helper = "dev/gaius/browser/BrowserSurfaceBiomeSupplier";
        String helperDescriptor = "L" + helper + ";";
        ClassNode node = read(jar, owner + ".class");
        boolean currentContext = node.methods.stream()
                .anyMatch(method -> method.name.equals("updateY")
                        && method.desc.equals("(IIII)V"));
        node.fields.add(new FieldNode(0, "browserUpdateXZ", "I", null, null));
        node.fields.add(new FieldNode(0, "browserUpdateY", "I", null, null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserBiomeSupplier",
                helperDescriptor,
                null,
                null));

        String constructorDescriptor = currentContext
                ? "(Lnet/minecraft/world/level/levelgen/SurfaceSystem;"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Ljava/util/function/Function;"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                        + "Ljava/util/Set;)V"
                : "(Lnet/minecraft/world/level/levelgen/SurfaceSystem;"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Ljava/util/function/Function;"
                        + "Lnet/minecraft/core/Registry;"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;)V";
        MethodNode constructor = find(node, "<init>", constructorDescriptor);
        int returns = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList initialize = new InsnList();
            initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
            initialize.add(new TypeInsnNode(Opcodes.NEW, helper));
            initialize.add(new InsnNode(Opcodes.DUP));
            initialize.add(new VarInsnNode(Opcodes.ALOAD, 5));
            initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
            initialize.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "pos",
                    "Lnet/minecraft/core/BlockPos$MutableBlockPos;"));
            initialize.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    helper,
                    "<init>",
                    "(Ljava/util/function/Function;"
                            + "Lnet/minecraft/core/BlockPos$MutableBlockPos;)V",
                    false));
            initialize.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "browserBiomeSupplier",
                    helperDescriptor));
            constructor.instructions.insertBefore(instruction, initialize);
            returns++;
        }
        if (returns != 1) {
            throw new IllegalStateException(
                    "SurfaceRules.Context constructor RETURN shape changed: " + returns);
        }

        MethodNode updateXZ = find(node, "updateXZ", "(II)V");
        InsnList updateXZCounters = new InsnList();
        appendIntFieldIncrement(updateXZCounters, owner, "browserUpdateXZ");
        appendIntFieldIncrement(updateXZCounters, owner, "browserUpdateY");
        updateXZ.instructions.insertBefore(updateXZ.instructions.getFirst(), updateXZCounters);

        MethodNode updateY = find(node, "updateY", currentContext ? "(IIII)V" : "(IIIIII)V");
        if (currentContext) {
            InsnList updateYCounter = new InsnList();
            appendIntFieldIncrement(updateYCounter, owner, "browserUpdateY");
            updateYCounter.add(new VarInsnNode(Opcodes.ALOAD, 0));
            updateYCounter.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserBiomeSupplier", helperDescriptor));
            updateYCounter.add(new VarInsnNode(Opcodes.ALOAD, 0));
            updateYCounter.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "blockX", "I"));
            updateYCounter.add(new VarInsnNode(Opcodes.ILOAD, 4));
            updateYCounter.add(new VarInsnNode(Opcodes.ALOAD, 0));
            updateYCounter.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "blockZ", "I"));
            updateYCounter.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, helper, "reset", "(III)V", false));
            updateY.instructions.insertBefore(updateY.instructions.getFirst(), updateYCounter);
            MethodNode getBiome = find(
                    node,
                    "getBiome",
                    "()Lnet/minecraft/core/Holder;");
            LabelNode cached = new LabelNode();
            InsnList getBiomeCode = new InsnList();
            getBiomeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getBiomeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "biome", "Lnet/minecraft/core/Holder;"));
            getBiomeCode.add(new JumpInsnNode(Opcodes.IFNONNULL, cached));
            getBiomeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getBiomeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getBiomeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserBiomeSupplier", helperDescriptor));
            getBiomeCode.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, helper, "get", "()Lnet/minecraft/core/Holder;", false));
            getBiomeCode.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "biome", "Lnet/minecraft/core/Holder;"));
            getBiomeCode.add(cached);
            getBiomeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getBiomeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "biome", "Lnet/minecraft/core/Holder;"));
            getBiomeCode.add(new InsnNode(Opcodes.ARETURN));
            replace(getBiome, getBiomeCode, 2, 1);
            writeComputeFrames(node, output);
            return;
        }
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "lastUpdateY", "J"));
        code.add(new InsnNode(Opcodes.LCONST_1));
        code.add(new InsnNode(Opcodes.LADD));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "lastUpdateY", "J"));
        appendIntFieldIncrement(code, owner, "browserUpdateY");
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserBiomeSupplier", helperDescriptor));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new VarInsnNode(Opcodes.ILOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, helper, "reset", "(III)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserBiomeSupplier", helperDescriptor));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "biome", "Ljava/util/function/Supplier;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "blockY", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "waterHeight", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "stoneDepthBelow", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "stoneDepthAbove", "I"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(updateY, code, 5, 7);
        writeComputeFrames(node, output);
    }

    private static void appendIntFieldIncrement(InsnList code, String owner, String field) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, "I"));
    }

    public static void patchSurfaceRulesLazyConditionBrowserPrimitiveCache(
            String jar, Path root) throws IOException {
        // 26.3: the lazy conditions are MaterialRuleContext$LazyXZCondition/$LazyYCondition
        // without a common base class; WorldgenPatches263 replaces their test() methods.
        if (droppedForCompiledDensityRuntime(
                "MinecraftClientPatcher.patchSurfaceRulesLazyConditionBrowserPrimitiveCache", jar,
                "net/minecraft/world/level/levelgen/SurfaceRules$LazyCondition.class",
                "net/minecraft/world/level/levelgen/SurfaceRules$LazyXZCondition.class",
                "net/minecraft/world/level/levelgen/SurfaceRules$LazyYCondition.class")) {
            return;
        }
        String lazyOwner = "net/minecraft/world/level/levelgen/SurfaceRules$LazyCondition";
        ClassNode lazy = read(jar, lazyOwner + ".class");
        lazy.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "browserLastUpdate", "I", null, null));
        lazy.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "browserResult", "Z", null, null));
        lazy.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "browserResultInitialized", "Z", null, null));
        lazy.methods.add(new MethodNode(
                Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT,
                "browserContextLastUpdate",
                "()I",
                null,
                null));

        MethodNode test = find(lazy, "test", "()Z");
        LabelNode refresh = new LabelNode();
        LabelNode cached = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                lazyOwner,
                "browserContextLastUpdate",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, lazyOwner, "browserResultInitialized", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, refresh));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, lazyOwner, "browserLastUpdate", "I"));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, cached));
        code.add(refresh);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, lazyOwner, "browserResultInitialized", "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, lazyOwner, "browserLastUpdate", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, lazyOwner, "compute", "()Z", false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, lazyOwner, "browserResult", "Z"));
        code.add(cached);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, lazyOwner, "browserResult", "Z"));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(test, code, 3, 2);
        writeComputeFrames(lazy, root.resolve(lazyOwner + ".class"));

        patchSurfaceRulesLazyCounterAccessor(
                jar, root, lazyOwner, "SurfaceRules$LazyXZCondition", "browserUpdateXZ");
        patchSurfaceRulesLazyCounterAccessor(
                jar, root, lazyOwner, "SurfaceRules$LazyYCondition", "browserUpdateY");
    }

    private static void patchSurfaceRulesLazyCounterAccessor(
            String jar, Path root, String lazyOwner, String simpleName, String counterField)
            throws IOException {
        String owner = "net/minecraft/world/level/levelgen/" + simpleName;
        String context = "net/minecraft/world/level/levelgen/SurfaceRules$Context";
        ClassNode node = read(jar, owner + ".class");
        MethodNode accessor = new MethodNode(
                Opcodes.ACC_PROTECTED,
                "browserContextLastUpdate",
                "()I",
                null,
                null);
        accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        accessor.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, lazyOwner, "context", "L" + context + ";"));
        accessor.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, context, counterField, "I"));
        accessor.instructions.add(new InsnNode(Opcodes.IRETURN));
        accessor.maxStack = 1;
        accessor.maxLocals = 1;
        node.methods.add(accessor);
        writeComputeFrames(node, root.resolve(owner + ".class"));
    }

    public static void patchDensityFunctionsPureTransformersBrowserDirect(
            String jar, Path root) throws IOException {
        // 26.3: every transformer is a monomorphic float sampler record (ClampFunction$Sampler,
        // BinaryFunction$Const*Sampler, UnaryFunction$*Sampler), which is what this emulated.
        if (droppedForCompiledDensityRuntime(
                "MinecraftClientPatcher.patchDensityFunctionsPureTransformersBrowserDirect", jar,
                "net/minecraft/world/level/levelgen/DensityFunctions$Clamp.class",
                "net/minecraft/world/level/levelgen/DensityFunctions$MulOrAdd.class",
                "net/minecraft/world/level/levelgen/DensityFunctions$Mapped.class")) {
            return;
        }
        String densityFunction = "net/minecraft/world/level/levelgen/DensityFunction";
        String context = densityFunction + "$FunctionContext";
        String provider = densityFunction + "$ContextProvider";
        String[] owners = {
            "net/minecraft/world/level/levelgen/DensityFunctions$Clamp",
            "net/minecraft/world/level/levelgen/DensityFunctions$MulOrAdd",
            "net/minecraft/world/level/levelgen/DensityFunctions$Mapped"
        };

        for (String owner : owners) {
            ClassNode node = read(jar, owner + ".class");
            MethodNode compute = findOrCreateMethod(
                    node,
                    Opcodes.ACC_PUBLIC,
                    "compute",
                    "(L" + context + ";)D");
            InsnList computeCode = new InsnList();
            computeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            computeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "input", "L" + densityFunction + ";"));
            computeCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            computeCode.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    densityFunction,
                    "compute",
                    "(L" + context + ";)D",
                    true));
            computeCode.add(new VarInsnNode(Opcodes.DSTORE, 2));
            appendBrowserDensityTransformFromLocal(computeCode, owner, 2);
            computeCode.add(new InsnNode(Opcodes.DRETURN));
            replace(compute, computeCode, 6, 4);

            MethodNode fillArray = findOrCreateMethod(
                    node,
                    Opcodes.ACC_PUBLIC,
                    "fillArray",
                    "([DL" + provider + ";)V");
            LabelNode loop = new LabelNode();
            LabelNode done = new LabelNode();
            InsnList fillCode = new InsnList();
            fillCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fillCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "input", "L" + densityFunction + ";"));
            fillCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fillCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
            fillCode.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    densityFunction,
                    "fillArray",
                    "([DL" + provider + ";)V",
                    true));
            fillCode.add(new InsnNode(Opcodes.ICONST_0));
            fillCode.add(new VarInsnNode(Opcodes.ISTORE, 3));
            fillCode.add(loop);
            fillCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
            fillCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fillCode.add(new InsnNode(Opcodes.ARRAYLENGTH));
            fillCode.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
            fillCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fillCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
            appendBrowserDensityTransformFromArray(fillCode, owner, 1, 3);
            fillCode.add(new InsnNode(Opcodes.DASTORE));
            fillCode.add(new IincInsnNode(3, 1));
            fillCode.add(new JumpInsnNode(Opcodes.GOTO, loop));
            fillCode.add(done);
            fillCode.add(new InsnNode(Opcodes.RETURN));
            replace(fillArray, fillCode, 8, 4);

            writeComputeFrames(node, root.resolve(owner + ".class"));
        }
    }

    /**
     * Density-function records hashed on every 26.2 graph-mapping lookup. 1.21.11 also still has
     * {@code DensityFunctions$WeirdScaledSampler}, which 26.2 no longer has.
     */
    private static final String[] WORLDGEN_HASH_CACHE_DENSITY_RECORDS = {
        "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder",
        "net/minecraft/world/level/levelgen/DensityFunctions$Ap2",
        "net/minecraft/world/level/levelgen/DensityFunctions$Clamp",
        "net/minecraft/world/level/levelgen/DensityFunctions$Constant",
        "net/minecraft/world/level/levelgen/DensityFunctions$HolderHolder",
        "net/minecraft/world/level/levelgen/DensityFunctions$Mapped",
        "net/minecraft/world/level/levelgen/DensityFunctions$Marker",
        "net/minecraft/world/level/levelgen/DensityFunctions$MulOrAdd",
        "net/minecraft/world/level/levelgen/DensityFunctions$Noise",
        "net/minecraft/world/level/levelgen/DensityFunctions$RangeChoice",
        "net/minecraft/world/level/levelgen/DensityFunctions$Shift",
        "net/minecraft/world/level/levelgen/DensityFunctions$ShiftA",
        "net/minecraft/world/level/levelgen/DensityFunctions$ShiftB",
        "net/minecraft/world/level/levelgen/DensityFunctions$ShiftedNoise",
        "net/minecraft/world/level/levelgen/DensityFunctions$Spline",
        "net/minecraft/world/level/levelgen/DensityFunctions$Spline$Coordinate",
        "net/minecraft/world/level/levelgen/DensityFunctions$YClampedGradient"
    };

    private static final String WORLDGEN_HASH_CACHE_WEIRD_SCALED_SAMPLER =
            "net/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler";

    private static final String[] WORLDGEN_HASH_CACHE_SPLINES = {
        "net/minecraft/util/CubicSpline$Constant",
        "net/minecraft/util/CubicSpline$Multipoint"
    };

    /**
     * The explicit hash-cache target list of a profile, from its version profile; every listed
     * class must exist. The legacy patch set (1.21.11) also caches WeirdScaledSampler; a modern
     * profile whose density graph is a router of records (kernelSemantics.densityGraph router-f64,
     * 26.2) caches the density records and the splines; one that compiles each density graph once
     * per RandomState (sampler-f32, 26.3: DensityFunctionCompiler) has no density records left and
     * caches the spline records only (retargeting the cache to the compiled density functions is
     * left to measurement, migration plan D6).
     */
    private static String[] worldgenRecordHashCacheTargets(String profile) {
        if (profile == null || profile.isBlank()) {
            throw new IllegalStateException("No profile for the worldgen hash cache target list");
        }
        VersionProfile version = VersionProfile.forId(profile);
        java.util.List<String> owners = new java.util.ArrayList<>();
        if (version.legacyPatchSet()) {
            owners.addAll(java.util.List.of(WORLDGEN_HASH_CACHE_DENSITY_RECORDS));
            owners.add(owners.indexOf(
                    "net/minecraft/world/level/levelgen/DensityFunctions$YClampedGradient"),
                    WORLDGEN_HASH_CACHE_WEIRD_SCALED_SAMPLER);
        } else if (VersionProfile.ROUTER_DENSITY_GRAPH.equals(version.densityGraph())) {
            owners.addAll(java.util.List.of(WORLDGEN_HASH_CACHE_DENSITY_RECORDS));
        }
        owners.addAll(java.util.List.of(WORLDGEN_HASH_CACHE_SPLINES));
        return owners.toArray(String[]::new);
    }

    public static void patchWorldgenRecordHashCodeCaches(String jar, Path root)
            throws IOException {
        String profile = PatchRegistry.profile();
        String[] owners = worldgenRecordHashCacheTargets(profile);

        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry(COMPILED_DENSITY_SAMPLER) != null) {
                // A 26.2 density record reappearing in a compiled-runtime jar means the list is stale.
                for (String removed : WORLDGEN_HASH_CACHE_DENSITY_RECORDS) {
                    if (input.getEntry(removed + ".class") != null) {
                        throw new IllegalStateException("Worldgen hash cache list for profile "
                                + profile + " omits the present density record " + removed);
                    }
                }
            }
            for (String owner : owners) {
                Path output = root.resolve(owner + ".class");
                if (!Files.isRegularFile(output) && input.getEntry(owner + ".class") == null) {
                    throw new IllegalStateException("Worldgen hash cache target " + owner
                            + " of profile " + profile + " is missing from " + jar);
                }
                ClassNode node = Files.isRegularFile(output)
                        ? read(output)
                        : read(jar, owner + ".class");
                cacheImmutableRecordHashCode(node, owner);
                writeComputeFrames(node, output);
            }
        }
        System.out.println("Cached worldgen record hash codes for profile " + profile + ": "
                + owners.length + " targets");
    }

    private static void cacheImmutableRecordHashCode(ClassNode node, String owner) {
        boolean record = (node.access & Opcodes.ACC_RECORD) != 0;
        boolean finalImmutableClass = (node.access & Opcodes.ACC_FINAL) != 0
                && node.fields.stream()
                        .filter(field -> (field.access & Opcodes.ACC_STATIC) == 0)
                        .allMatch(field -> (field.access & Opcodes.ACC_FINAL) != 0);
        if (!record && !finalImmutableClass) {
            throw new IllegalStateException(
                    "Worldgen hash cache target is not immutable: " + owner);
        }
        if (node.fields.stream().anyMatch(field -> field.name.startsWith("browserHashCode"))) {
            throw new IllegalStateException("Worldgen hash cache fields already exist: " + owner);
        }

        MethodNode hashCode = find(node, "hashCode", "()I");
        if ((hashCode.access & Opcodes.ACC_FINAL) == 0
                && (node.access & Opcodes.ACC_FINAL) == 0) {
            throw new IllegalStateException("Final worldgen hashCode was not found: " + owner);
        }
        AbstractInsnNode[] originalInstructions = hashCode.instructions.toArray();
        int returns = 0;
        int resultLocal = hashCode.maxLocals++;
        for (AbstractInsnNode instruction : originalInstructions) {
            if (instruction.getOpcode() != Opcodes.IRETURN) {
                continue;
            }
            InsnList remember = new InsnList();
            remember.add(new VarInsnNode(Opcodes.ISTORE, resultLocal));
            remember.add(new VarInsnNode(Opcodes.ALOAD, 0));
            remember.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
            remember.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "browserHashCode", "I"));
            remember.add(new VarInsnNode(Opcodes.ALOAD, 0));
            remember.add(new InsnNode(Opcodes.ICONST_1));
            remember.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "browserHashCodeComputed", "Z"));
            remember.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
            hashCode.instructions.insertBefore(instruction, remember);
            returns++;
        }
        if (returns == 0) {
            throw new IllegalStateException("Worldgen record hashCode has no return: " + owner);
        }

        LabelNode compute = new LabelNode();
        InsnList cached = new InsnList();
        cached.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cached.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserHashCodeComputed", "Z"));
        cached.add(new JumpInsnNode(Opcodes.IFEQ, compute));
        cached.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cached.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "browserHashCode", "I"));
        cached.add(new InsnNode(Opcodes.IRETURN));
        cached.add(compute);
        hashCode.instructions.insert(cached);

        int fieldAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
        node.fields.add(new FieldNode(fieldAccess, "browserHashCode", "I", null, null));
        node.fields.add(new FieldNode(
                fieldAccess, "browserHashCodeComputed", "Z", null, null));
    }

    private static void appendBrowserDensityTransformFromLocal(
            InsnList code, String owner, int valueLocal) {
        code.add(new VarInsnNode(Opcodes.DLOAD, valueLocal));
        appendBrowserDensityTransform(code, owner);
    }

    private static void appendBrowserDensityTransformFromArray(
            InsnList code, String owner, int arrayLocal, int indexLocal) {
        code.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        code.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        code.add(new InsnNode(Opcodes.DALOAD));
        appendBrowserDensityTransform(code, owner);
    }

    private static void appendBrowserDensityTransform(InsnList code, String owner) {
        String helper = "dev/gaius/browser/BrowserDensityFunctions";
        if (owner.endsWith("$Clamp")) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "minValue", "D"));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "maxValue", "D"));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, helper, "clamp", "(DDD)D", false));
            return;
        }

        String typeField;
        String typeOwner;
        String helperMethod;
        String helperDescriptor;
        if (owner.endsWith("$MulOrAdd")) {
            typeField = "specificType";
            typeOwner = owner + "$Type";
            helperMethod = "transformMulOrAdd";
            helperDescriptor = "(DID)D";
        } else if (owner.endsWith("$Mapped")) {
            typeField = "type";
            typeOwner = owner + "$Type";
            helperMethod = "transformMapped";
            helperDescriptor = "(DI)D";
        } else {
            throw new IllegalArgumentException("Unsupported density transformer: " + owner);
        }

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, typeField, "L" + typeOwner + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, typeOwner, "ordinal", "()I", false));
        if (owner.endsWith("$MulOrAdd")) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "argument", "D"));
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, helper, helperMethod, helperDescriptor, false));
    }

    public static void patchSurfaceRulesSequenceBrowserIndexed(String jar, Path output)
            throws IOException {
        // 26.3: material/rule/SequenceRule already compiles to an indexed RuleEvaluator array.
        if (droppedForCompiledDensityRuntime(
                "MinecraftClientPatcher.patchSurfaceRulesSequenceBrowserIndexed", jar,
                "net/minecraft/world/level/levelgen/SurfaceRules$SequenceRule.class",
                "net/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule.class")) {
            return;
        }
        String owner = "net/minecraft/world/level/levelgen/SurfaceRules$SequenceRule";
        String rule = "net/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "tryApply",
                "(III)Lnet/minecraft/world/level/block/state/BlockState;");
        LabelNode loop = new LabelNode();
        LabelNode next = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "rules", "Ljava/util/List;"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 6));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 6));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "get",
                "(I)Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, rule));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                rule,
                "tryApply",
                "(III)Lnet/minecraft/world/level/block/state/BlockState;",
                true));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNULL, next));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(next);
        code.add(new InsnNode(Opcodes.POP));
        code.add(new IincInsnNode(6, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4, 7);
        writeComputeFrames(node, output);
    }

    public static void patchNoiseChunkBrowserSynchronous(String jar, Path output)
            throws IOException {
        // 26.3: NoiseChunk is a holder of the chunk's DensityVolume, aquifer and sampler set; the
        // slice fill, interpolators and counters are gone. WorldgenPatches263 instead asserts
        // that the compiled density kernels stay free of scheduler pulses.
        if (droppedForCompiledDensityRuntime(
                "MinecraftClientPatcher.patchNoiseChunkBrowserSynchronous", jar,
                "net/minecraft/world/level/levelgen/NoiseChunk#fillSlice",
                "net/minecraft/world/level/levelgen/NoiseChunk#fillAllDirectly",
                "net/minecraft/world/level/levelgen/NoiseChunk#interpolationCounter",
                "net/minecraft/world/level/levelgen/NoiseChunk#arrayInterpolationCounter",
                "net/minecraft/world/level/levelgen/NoiseChunk#interpolators")) {
            return;
        }
        String owner = "net/minecraft/world/level/levelgen/NoiseChunk";
        ClassNode node = read(jar, owner + ".class");
        convertNoiseChunkCountersToInt(node, false);
        addNoiseInterpolatorArrayCache(node, owner);
        MethodNode fillSlice = find(node, "fillSlice", "(ZI)V");
        requireWorldgenSchedulerCalls("NoiseChunk.fillSlice", fillSlice, 0);
        requireWorldgenSchedulerCalls(
                "NoiseChunk.fillAllDirectly",
                find(
                        node,
                        "fillAllDirectly",
                        "([DLnet/minecraft/world/level/levelgen/DensityFunction;)V"),
                0);
        requireWorldgenSchedulerCalls(
                "NoiseChunk.selectCellYZ", find(node, "selectCellYZ", "(II)V"), 0);
        replaceNoiseInterpolatorUpdate(node, owner, "updateForY", "cellStartBlockY", "inCellY", false);
        replaceNoiseInterpolatorUpdate(node, owner, "updateForX", "cellStartBlockX", "inCellX", false);
        replaceNoiseInterpolatorUpdate(node, owner, "updateForZ", "cellStartBlockZ", "inCellZ", true);
        writeComputeFrames(node, output);
    }

    public static void patchNoiseInterpolatorBrowserLerp(String jar, Path output)
            throws IOException {
        // 26.3: interpolation is densityfunction/op/InterpolatedFunction$Sampler (float lerp).
        if (droppedForCompiledDensityRuntime(
                "MinecraftClientPatcher.patchNoiseInterpolatorBrowserLerp", jar,
                "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator.class")) {
            return;
        }
        String owner = "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator";
        ClassNode node = read(jar, owner + ".class");
        replaceNoiseInterpolatorLerpMethod(
                node,
                owner,
                "updateForY",
                new String[][] {
                    {"valueXZ00", "noise000", "noise010"},
                    {"valueXZ10", "noise100", "noise110"},
                    {"valueXZ01", "noise001", "noise011"},
                    {"valueXZ11", "noise101", "noise111"}
                });
        replaceNoiseInterpolatorLerpMethod(
                node,
                owner,
                "updateForX",
                new String[][] {
                    {"valueZ0", "valueXZ00", "valueXZ10"},
                    {"valueZ1", "valueXZ01", "valueXZ11"}
                });
        replaceNoiseInterpolatorLerpMethod(
                node,
                owner,
                "updateForZ",
                new String[][] {{"value", "valueZ0", "valueZ1"}});
        MethodNode compute = find(
                node,
                "compute",
                "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D");
        int patched = 0;
        for (AbstractInsnNode instruction = compute.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("net/minecraft/util/Mth")
                    || !call.name.equals("lerp3")
                    || !call.desc.equals("(DDDDDDDDDDD)D")) {
                continue;
            }
            call.owner = "dev/gaius/browser/BrowserNoiseInterpolator";
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "NoiseInterpolator browser lerp patch point was not found: " + patched);
        }
        write(node, output);
    }

    private static void replaceNoiseInterpolatorLerpMethod(
            ClassNode node,
            String owner,
            String methodName,
            String[][] fields) {
        InsnList code = new InsnList();
        for (String[] assignment : fields) {
            String target = assignment[0];
            String start = assignment[1];
            String end = assignment[2];
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, start, "D"));
            code.add(new VarInsnNode(Opcodes.DLOAD, 1));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, end, "D"));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, start, "D"));
            code.add(new InsnNode(Opcodes.DSUB));
            code.add(new InsnNode(Opcodes.DMUL));
            code.add(new InsnNode(Opcodes.DADD));
            code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, target, "D"));
        }
        code.add(new InsnNode(Opcodes.RETURN));
        replace(find(node, methodName, "(D)V"), code, 9, 3);
    }

    public static void patchNoiseChunkContextBrowserIntCounters(
            String jar, Path output) throws IOException {
        if (droppedForCompiledDensityRuntime(
                "MinecraftClientPatcher.patchNoiseChunkContextBrowserIntCounters", jar,
                "net/minecraft/world/level/levelgen/NoiseChunk$1.class")) {
            return;
        }
        String owner = "net/minecraft/world/level/levelgen/NoiseChunk$1";
        ClassNode node = read(jar, owner + ".class");
        int rewrites = convertNoiseChunkCountersToInt(node, false);
        if (rewrites < 6) {
            throw new IllegalStateException(
                    "NoiseChunk context int-counter patch points changed: " + rewrites);
        }
        writeComputeFrames(node, output);
    }

    public static void patchNoiseChunkCacheOnceBrowserIntCounters(
            String jar, Path output) throws IOException {
        if (droppedForCompiledDensityRuntime(
                "MinecraftClientPatcher.patchNoiseChunkCacheOnceBrowserIntCounters", jar,
                "net/minecraft/world/level/levelgen/NoiseChunk$CacheOnce.class")) {
            return;
        }
        String owner = "net/minecraft/world/level/levelgen/NoiseChunk$CacheOnce";
        ClassNode node = read(jar, owner + ".class");
        int rewrites = convertNoiseChunkCountersToInt(node, true);
        if (rewrites < 12) {
            throw new IllegalStateException(
                    "NoiseChunk cache int-counter patch points changed: " + rewrites);
        }
        writeComputeFrames(node, output);
    }

    private static int convertNoiseChunkCountersToInt(ClassNode node, boolean cacheFields) {
        int rewrites = 0;
        for (FieldNode field : node.fields) {
            if (field.desc.equals("J")
                    && (isNoiseChunkCounter(field.name)
                            || (cacheFields && isNoiseCacheCounter(field.name)))) {
                field.desc = "I";
                rewrites++;
            }
        }
        for (MethodNode method : node.methods) {
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof FieldInsnNode field
                        && field.desc.equals("J")
                        && ((field.owner.equals("net/minecraft/world/level/levelgen/NoiseChunk")
                                        && isNoiseChunkCounter(field.name))
                                || (field.owner.equals(
                                                "net/minecraft/world/level/levelgen/NoiseChunk$CacheOnce")
                                        && isNoiseCacheCounter(field.name)))) {
                    field.desc = "I";
                    rewrites++;
                }
            }
            for (var instruction = method.instructions.getFirst();
                    instruction != null;) {
                var nextInstruction = instruction.getNext();
                if ((instruction.getOpcode() == Opcodes.LCONST_0
                                || instruction.getOpcode() == Opcodes.LCONST_1)
                        && (isCounterField(previousRealInstruction(instruction))
                                || isCounterField(nextRealInstruction(instruction)))) {
                    method.instructions.set(
                            instruction,
                            new InsnNode(instruction.getOpcode() == Opcodes.LCONST_0
                                    ? Opcodes.ICONST_0
                                    : Opcodes.ICONST_1));
                    rewrites++;
                } else if (instruction.getOpcode() == Opcodes.LADD
                        && isCounterField(previousRealInstruction(
                                previousRealInstruction(instruction)))) {
                    method.instructions.set(instruction, new InsnNode(Opcodes.IADD));
                    rewrites++;
                } else if (instruction.getOpcode() == Opcodes.LCMP
                        && (isCounterField(previousRealInstruction(instruction))
                                || isCounterField(previousRealInstruction(
                                        previousRealInstruction(instruction))))) {
                    var next = nextRealInstruction(instruction);
                    if (!(next instanceof JumpInsnNode jump)
                            || (jump.getOpcode() != Opcodes.IFEQ
                                    && jump.getOpcode() != Opcodes.IFNE)) {
                        throw new IllegalStateException(
                                "NoiseChunk counter comparison shape changed in " + method.name);
                    }
                    nextInstruction = jump.getNext();
                    method.instructions.insertBefore(
                            jump,
                            new JumpInsnNode(
                                    jump.getOpcode() == Opcodes.IFEQ
                                            ? Opcodes.IF_ICMPEQ
                                            : Opcodes.IF_ICMPNE,
                                    jump.label));
                    method.instructions.remove(jump);
                    method.instructions.remove(instruction);
                    rewrites++;
                }
                instruction = nextInstruction;
            }
        }
        return rewrites;
    }

    private static boolean isNoiseChunkCounter(String name) {
        return name.equals("interpolationCounter") || name.equals("arrayInterpolationCounter");
    }

    private static boolean isNoiseCacheCounter(String name) {
        return name.equals("lastCounter") || name.equals("lastArrayCounter");
    }

    private static boolean isCounterField(AbstractInsnNode instruction) {
        return instruction instanceof FieldInsnNode field
                && (isNoiseChunkCounter(field.name) || isNoiseCacheCounter(field.name));
    }

    private static void addNoiseInterpolatorArrayCache(ClassNode node, String owner) {
        String interpolator = "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator";
        String arrayDesc = "[L" + interpolator + ";";
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                "browserInterpolators",
                arrayDesc,
                null,
                null));

        MethodNode cache = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserInterpolators",
                "()" + arrayDesc,
                null,
                null);
        LabelNode ready = new LabelNode();
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cache.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserInterpolators", arrayDesc));
        cache.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cache.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cache.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "interpolators", "Ljava/util/List;"));
        cache.instructions.add(new InsnNode(Opcodes.ICONST_0));
        cache.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, interpolator));
        cache.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "toArray",
                "([Ljava/lang/Object;)[Ljava/lang/Object;",
                true));
        cache.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, arrayDesc));
        cache.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cache.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserInterpolators", arrayDesc));
        cache.instructions.add(ready);
        cache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cache.instructions.add(new InsnNode(Opcodes.ARETURN));
        cache.maxStack = 3;
        cache.maxLocals = 2;
        node.methods.add(cache);
    }

    private static void replaceNoiseInterpolatorUpdate(
            ClassNode node,
            String owner,
            String methodName,
            String startField,
            String positionField,
            boolean incrementCounter) {
        String interpolator = "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator";
        String arrayDesc = "[L" + interpolator + ";";
        MethodNode method = find(node, methodName, "(ID)V");
        InsnList code = new InsnList();
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, startField, "I"));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, positionField, "I"));
        if (incrementCounter) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "interpolationCounter", "I"));
            code.add(new InsnNode(Opcodes.ICONST_1));
            code.add(new InsnNode(Opcodes.IADD));
            code.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, "interpolationCounter", "I"));
        }
        LabelNode cached = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserInterpolators", arrayDesc));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, cached));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "browserInterpolators",
                "()" + arrayDesc,
                false));
        code.add(cached);
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new VarInsnNode(Opcodes.DLOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                interpolator,
                methodName,
                "(D)V",
                false));
        code.add(new IincInsnNode(5, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 5, 6);
    }

    public static void patchClimateRTreeBrowserSynchronous(
            String jar, Path output, boolean deepWorldgenCheckpoints) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/biome/Climate$RTree$SubTree.class");
        MethodNode method = find(
                node,
                "search",
                "([JLnet/minecraft/world/level/biome/Climate$RTree$Leaf;"
                        + "Lnet/minecraft/world/level/biome/Climate$DistanceMetric;)"
                        + "Lnet/minecraft/world/level/biome/Climate$RTree$Leaf;");
        requireWorldgenSchedulerCalls("Climate.RTree.SubTree.search", method, 0);
        if (!deepWorldgenCheckpoints) {
            write(node, output);
            return;
        }

        int checkpoints = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof IincInsnNode increment) || increment.var != 9) {
                continue;
            }
            method.instructions.insert(instruction, browserWorldgenDeepPulse());
            checkpoints++;
        }
        if (checkpoints != 1) {
            throw new IllegalStateException(
                    "Climate RTree subtree search loop shape changed: checkpoints=" + checkpoints);
        }
        writeComputeFrames(node, output);
    }

    /**
     * Routes the multi-noise biome lookup through {@code GaiusClimateIndex}, an exact flat copy
     * of the vanilla RTree (see that class for why the results, including the per-thread
     * {@code lastResult} history, are the vanilla ones). Three edits:
     * <ul>
     *   <li>{@code SubTree} implements {@code GaiusClimateSubTree} so the index can read its
     *       children; its vanilla search (with the deep pulse) stays for the fallback path;</li>
     *   <li>{@code ParameterList.findValueIndex(TargetPoint)} passes the shared
     *       {@code GaiusClimateIndex.NODE_DISTANCE} instead of a fresh {@code Node::distance}
     *       lambda, which is how the index recognises vanilla's metric;</li>
     *   <li>{@code RTree.search(TargetPoint, DistanceMetric)} asks
     *       {@code GaiusClimateIndex.find(root, values, candidate, metric)} for the leaf and runs
     *       vanilla's {@code root.search} when it declines; {@code lastResult} is read and written
     *       as vanilla does, except that storing the value just read is skipped (it cannot change
     *       what any thread reads later).</li>
     * </ul>
     * Must run after {@link #patchClimateRTreeBrowserSynchronous}, which writes {@code SubTree}.
     */
    public static void patchClimateRTreeBrowserFlatIndex(String jar, Path root) throws IOException {
        String biome = "net/minecraft/world/level/biome/";
        String tree = biome + "Climate$RTree";
        String node = tree + "$Node";
        String leaf = tree + "$Leaf";
        String subTree = tree + "$SubTree";
        String parameterList = biome + "Climate$ParameterList";
        String targetPoint = biome + "Climate$TargetPoint";
        String metric = biome + "Climate$DistanceMetric";
        String index = biome + "GaiusClimateIndex";
        String access = biome + "GaiusClimateSubTree";
        requireTwoNodeSubclasses(jar, node, leaf, subTree);

        // SubTree: expose the children array through the access interface.
        Path subTreeOutput = root.resolve(subTree + ".class");
        ClassNode subTreeNode = Files.isRegularFile(subTreeOutput)
                ? read(subTreeOutput)
                : read(jar, subTree + ".class");
        if (subTreeNode.interfaces.contains(access)) {
            throw new IllegalStateException("Climate RTree SubTree already implements " + access);
        }
        boolean childrenField = subTreeNode.fields.stream().anyMatch(field -> field.name.equals("children")
                && field.desc.equals("[L" + node + ";")
                && (field.access & Opcodes.ACC_STATIC) == 0);
        if (!childrenField) {
            throw new IllegalStateException("Climate RTree SubTree children field shape changed");
        }
        subTreeNode.interfaces.add(access);
        if (subTreeNode.signature != null) {
            subTreeNode.signature = subTreeNode.signature + "L" + access + ";";
        }
        MethodNode children = new MethodNode(
                Opcodes.ACC_PUBLIC, "gaius$children", "()[L" + node + ";", null, null);
        children.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        children.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, subTree, "children", "[L" + node + ";"));
        children.instructions.add(new InsnNode(Opcodes.ARETURN));
        children.maxStack = 1;
        children.maxLocals = 1;
        subTreeNode.methods.add(children);
        write(subTreeNode, subTreeOutput);

        // ParameterList.findValueIndex(TargetPoint): the shared Node::distance metric.
        Path listOutput = root.resolve(parameterList + ".class");
        ClassNode listNode = Files.isRegularFile(listOutput)
                ? read(listOutput)
                : read(jar, parameterList + ".class");
        MethodNode findValueIndex = find(
                listNode, "findValueIndex", "(L" + targetPoint + ";)Ljava/lang/Object;");
        int metrics = 0;
        for (AbstractInsnNode instruction : findValueIndex.instructions.toArray()) {
            if (!(instruction instanceof InvokeDynamicInsnNode dynamic)) {
                continue;
            }
            if (!dynamic.desc.equals("()L" + metric + ";")
                    || dynamic.bsmArgs.length < 2
                    || !(dynamic.bsmArgs[1] instanceof org.objectweb.asm.Handle handle)
                    || !handle.getOwner().equals(node)
                    || !handle.getName().equals("distance")
                    || !handle.getDesc().equals("([J)J")) {
                throw new IllegalStateException(
                        "Climate ParameterList.findValueIndex metric is not Node::distance: " + dynamic.desc);
            }
            findValueIndex.instructions.set(dynamic, new FieldInsnNode(
                    Opcodes.GETSTATIC, index, "NODE_DISTANCE", "L" + metric + ";"));
            metrics++;
        }
        if (metrics != 1) {
            throw new IllegalStateException(
                    "Climate ParameterList.findValueIndex metric shape changed: " + metrics);
        }
        write(listNode, listOutput);

        // RTree.search: delegate to the index with vanilla's root and lastResult.
        Path treeOutput = root.resolve(tree + ".class");
        ClassNode treeNode = Files.isRegularFile(treeOutput)
                ? read(treeOutput)
                : read(jar, tree + ".class");
        MethodNode search = find(
                treeNode, "search", "(L" + targetPoint + ";L" + metric + ";)Ljava/lang/Object;");
        int toArray = 0;
        int threadLocalGet = 0;
        int threadLocalSet = 0;
        int nodeSearch = 0;
        int leafValue = 0;
        for (AbstractInsnNode instruction : search.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals(targetPoint) && call.name.equals("toParameterArray")
                        && call.desc.equals("()[J")) {
                    toArray++;
                } else if (call.owner.equals("java/lang/ThreadLocal") && call.name.equals("get")) {
                    threadLocalGet++;
                } else if (call.owner.equals("java/lang/ThreadLocal") && call.name.equals("set")) {
                    threadLocalSet++;
                } else if (call.owner.equals(node) && call.name.equals("search")) {
                    nodeSearch++;
                } else {
                    throw new IllegalStateException(
                            "Climate RTree.search calls an unexpected method: " + call.owner + "." + call.name);
                }
            } else if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(leaf) && field.name.equals("value")) {
                leafValue++;
            }
        }
        boolean rootField = treeNode.fields.stream().anyMatch(field -> field.name.equals("root")
                && field.desc.equals("L" + node + ";"));
        boolean lastResultField = treeNode.fields.stream().anyMatch(field -> field.name.equals("lastResult")
                && field.desc.equals("Ljava/lang/ThreadLocal;"));
        if (toArray != 1 || threadLocalGet != 1 || threadLocalSet != 1 || nodeSearch != 1
                || leafValue != 1 || !rootField || !lastResultField) {
            throw new IllegalStateException("Climate RTree.search shape changed: toParameterArray="
                    + toArray + " get=" + threadLocalGet + " set=" + threadLocalSet
                    + " search=" + nodeSearch + " value=" + leafValue
                    + " root=" + rootField + " lastResult=" + lastResultField);
        }
        // long[] values = target.toParameterArray();
        // Leaf candidate = (Leaf) this.lastResult.get();
        // Leaf leaf = (Leaf) GaiusClimateIndex.find(this.root, values, candidate, metric);
        // if (leaf == null) leaf = this.root.search(values, candidate, metric);
        // if (leaf != candidate) this.lastResult.set(leaf);
        // return leaf.value;
        LabelNode found = new LabelNode();
        LabelNode unchanged = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, targetPoint, "toParameterArray", "()[J", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, tree, "lastResult", "Ljava/lang/ThreadLocal;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, leaf));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, tree, "root", "L" + node + ";"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                index,
                "find",
                "(L" + node + ";[JLjava/lang/Object;L" + metric + ";)Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, leaf));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, found));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, tree, "root", "L" + node + ";"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                node,
                "search",
                "([JL" + leaf + ";L" + metric + ";)L" + leaf + ";",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(found);
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, unchanged));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, tree, "lastResult", "Ljava/lang/ThreadLocal;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V", false));
        code.add(unchanged);
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, leaf, "value", "Ljava/lang/Object;"));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(search, code, 5, 6);
        writeComputeFrames(treeNode, treeOutput);
    }

    /**
     * GaiusClimateIndex treats every node that is not a SubTree as a Leaf; that holds while these
     * two are the only subclasses of Climate.RTree.Node in the jar.
     */
    private static void requireTwoNodeSubclasses(String jar, String node, String leaf, String subTree)
            throws IOException {
        java.util.Set<String> subclasses = new java.util.TreeSet<>();
        String prefix = "net/minecraft/world/level/biome/";
        try (ZipFile input = new ZipFile(jar)) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith(prefix) || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (var stream = input.getInputStream(entry)) {
                    if (node.equals(new org.objectweb.asm.ClassReader(stream.readAllBytes()).getSuperName())) {
                        subclasses.add(entry.getName().substring(0, entry.getName().length() - 6));
                    }
                }
            }
        }
        if (!subclasses.equals(new java.util.TreeSet<>(java.util.List.of(leaf, subTree)))) {
            throw new IllegalStateException("Climate RTree Node subclasses changed: " + subclasses);
        }
    }

    public static void patchClimateRTreeNodeBrowserDoubleDistance(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/biome/Climate$RTree$Node";
        String parameter = "net/minecraft/world/level/biome/Climate$Parameter";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserBounds",
                "[D",
                null,
                null));

        MethodNode constructor = find(node, "<init>", "(Ljava/util/List;)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("Climate RTree Node constructor return was not found");
        }
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "parameterSpace",
                "[L" + parameter + ";"));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserClimate",
                "prepareBounds",
                "([L" + parameter + ";)[D",
                false));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "browserBounds",
                "[D"));
        constructor.instructions.insertBefore(constructorReturn, constructorCode);

        MethodNode method = find(node, "distance", "([J)J");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserBounds",
                "[D"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserClimate",
                "distance",
                "([D[J)J",
                false));
        code.add(new InsnNode(Opcodes.LRETURN));
        replace(method, code, 2, 2);
        writeComputeFrames(node, output);
    }

    public static void patchChunkGeneratorBrowserSynchronous(
            String jar, Path output, boolean deepWorldgenCheckpoints) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/chunk/ChunkGenerator.class");
        MethodNode decoration = find(
                node,
                "applyBiomeDecoration",
                "(Lnet/minecraft/world/level/WorldGenLevel;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/StructureManager;)V");
        requireWorldgenSchedulerCalls("ChunkGenerator.applyBiomeDecoration", decoration, 0);
        if (deepWorldgenCheckpoints) {
            patchDeepWorldgenPulseBackEdges(
                    decoration, "ChunkGenerator.applyBiomeDecoration");
        }

        String structureSetDescriptor =
                "(Lnet/minecraft/world/level/StructureManager;"
                        + "Lnet/minecraft/core/SectionPos;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;"
                        + "Lnet/minecraft/world/level/ChunkPos;"
                        + "Lnet/minecraft/core/RegistryAccess;"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/"
                        + "StructureTemplateManager;"
                        + "Lnet/minecraft/resources/ResourceKey;"
                        + "Lnet/minecraft/core/Holder;)V";
        MethodNode structureSets = null;
        for (MethodNode candidate : node.methods) {
            if (candidate.name.startsWith("lambda$createStructures$")
                    && candidate.desc.equals(structureSetDescriptor)) {
                if (structureSets != null) {
                    throw new IllegalStateException(
                            "ChunkGenerator has multiple createStructures lambdas");
                }
                structureSets = candidate;
            }
        }
        if (structureSets == null) {
            structureSets = findCreateStructuresLambdaByStructureCalls(node);
        }
        if (structureSets == null) {
            throw new IllegalStateException("ChunkGenerator createStructures lambda was not found");
        }
        requireWorldgenSchedulerCalls("ChunkGenerator.createStructures", structureSets, 0);
        if (deepWorldgenCheckpoints) {
            int checkpoints = 0;
            for (AbstractInsnNode instruction : structureSets.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !call.owner.equals("net/minecraft/world/level/chunk/ChunkGenerator")
                        || !call.name.equals("tryGenerateStructure")) {
                    continue;
                }
                structureSets.instructions.insertBefore(
                        instruction, browserWorldgenDeepPulse());
                checkpoints++;
            }
            if (checkpoints < 2) {
                throw new IllegalStateException(
                        "ChunkGenerator structure selection call shape changed: checkpoints="
                                + checkpoints);
            }
            patchDeepWorldgenPulseBackEdges(
                    structureSets, "ChunkGenerator.createStructures");
        }

        MethodNode references = find(
                node,
                "createReferences",
                "(Lnet/minecraft/world/level/WorldGenLevel;"
                        + "Lnet/minecraft/world/level/StructureManager;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;)V");
        requireWorldgenSchedulerCalls("ChunkGenerator.createReferences", references, 0);
        if (deepWorldgenCheckpoints) {
            writeComputeFrames(node, output);
        } else {
            write(node, output);
        }
    }

    /**
     * Minecraft 26.3 changed the per-structure-set lambda of createStructures (SectionPos gone,
     * Climate$Sampler added, parameters reordered). Match it by name prefix and by its exactly
     * two ChunkGenerator.tryGenerateStructure calls instead of a fixed descriptor; returns null
     * when no lambda qualifies and throws when several do.
     */
    private static MethodNode findCreateStructuresLambdaByStructureCalls(ClassNode node) {
        MethodNode match = null;
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.startsWith("lambda$createStructures$")
                    || (candidate.access & Opcodes.ACC_STATIC) != 0
                    || !candidate.desc.endsWith(")V")) {
                continue;
            }
            int structureCalls = 0;
            for (AbstractInsnNode instruction : candidate.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals("net/minecraft/world/level/chunk/ChunkGenerator")
                        && call.name.equals("tryGenerateStructure")) {
                    structureCalls++;
                }
            }
            if (structureCalls != 2) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException(
                        "ChunkGenerator has multiple createStructures lambdas with two "
                                + "tryGenerateStructure calls: " + match.desc + ", " + candidate.desc);
            }
            match = candidate;
        }
        return match;
    }

    public static void patchJigsawPlacementBrowserDeepCheckpoints(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/levelgen/structure/pools/JigsawPlacement$Placer";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "tryPlacingChildren",
                "(Lnet/minecraft/world/level/levelgen/structure/PoolElementStructurePiece;"
                        + "Lorg/apache/commons/lang3/mutable/MutableObject;IZ"
                        + "Lnet/minecraft/world/level/LevelHeightAccessor;"
                        + "Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;"
                        + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)V");
        int checkpoints = 0;
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = 0; index < instructions.length; index++) {
            AbstractInsnNode instruction = instructions[index];
            if (!(instruction instanceof JumpInsnNode jump)
                    || method.instructions.indexOf(jump.label) >= index) {
                continue;
            }
            method.instructions.insertBefore(instruction, browserWorldgenDeepPulse());
            checkpoints++;
        }
        if (checkpoints == 0) {
            throw new IllegalStateException(
                    "Jigsaw placement child loop shape changed: no back-edge checkpoint");
        }
        writeComputeFrames(node, output);
    }

    public static void patchWorldCarverBrowserSynchronous(
            String jar, Path output, boolean deepWorldgenCheckpoints) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/levelgen/carver/WorldCarver.class");
        // 26.3: WorldCarver is an interface and carveEllipsoid a static method that only writes a
        // CarverOutput (the CarvingMask); blocks are applied later by NoiseBasedChunkGenerator.
        MethodNode modern = findNullable(
                node,
                "carveEllipsoid",
                "(Lnet/minecraft/world/level/ChunkPos;DDDDD"
                        + "Lnet/minecraft/world/level/chunk/CarverOutput;"
                        + "Lnet/minecraft/world/level/levelgen/carver/WorldCarver$CarveSkipChecker;)V");
        if (modern != null) {
            if ((node.access & Opcodes.ACC_INTERFACE) == 0
                    || (modern.access & Opcodes.ACC_STATIC) == 0) {
                throw new IllegalStateException(
                        "26.3 WorldCarver.carveEllipsoid is expected to be a static interface method");
            }
            requireWorldgenSchedulerCalls("WorldCarver.carveEllipsoid", modern, 0);
            if (deepWorldgenCheckpoints) {
                patchDeepWorldgenPulseBackEdges(modern, "WorldCarver.carveEllipsoid", 3);
                writeComputeFrames(node, output);
            } else {
                write(node, output);
            }
            return;
        }
        MethodNode method = find(
                node,
                "carveEllipsoid",
                "(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;"
                        + "Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/function/Function;"
                        + "Lnet/minecraft/world/level/levelgen/Aquifer;DDDDD"
                        + "Lnet/minecraft/world/level/chunk/CarvingMask;"
                        + "Lnet/minecraft/world/level/levelgen/carver/WorldCarver$CarveSkipChecker;)Z");
        requireWorldgenSchedulerCalls("WorldCarver.carveEllipsoid", method, 0);
        if (deepWorldgenCheckpoints) {
            patchDeepWorldgenPulseBackEdges(method, "WorldCarver.carveEllipsoid");
            writeComputeFrames(node, output);
        } else {
            write(node, output);
        }
    }

    /**
     * Present only in jars whose density functions are compiled into float samplers
     * (Minecraft 26.3 and later); 26.2 and 1.21.11 evaluate {@code levelgen/DensityFunction}
     * graphs directly.
     */
    private static final String COMPILED_DENSITY_SAMPLER =
            "net/minecraft/world/level/levelgen/densityfunction/DensitySampler.class";

    /**
     * Minecraft 26.3 replaced the 26.2 density-function runtime (NoiseChunk slices, the
     * interpolators, the DensityFunctions records, SurfaceRules) with compiled float samplers,
     * so the 26.2 performance patches for that runtime have no target left, and their
     * double-precision helpers would change the generated terrain (migration plan D6). On a jar
     * with the compiled runtime this registers {@code patchId} as dropped, which asserts that
     * every listed 26.2 target is absent, and returns true; otherwise it returns false and the
     * caller patches the 26.2 or 1.21.11 target exactly as before.
     */
    private static boolean droppedForCompiledDensityRuntime(
            String patchId, String jar, String... absentTargets) throws IOException {
        if (!jarHasEntry(jar, COMPILED_DENSITY_SAMPLER)) {
            return false;
        }
        PatchRegistry.dropped(patchId, jar, absentTargets);
        return true;
    }

    private static boolean jarHasEntry(String jar, String entry) throws IOException {
        try (ZipFile input = new ZipFile(jar)) {
            return input.getEntry(entry) != null;
        }
    }

    public static void patchProtoChunkBrowserHeightmapCache(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/chunk/ProtoChunk";
        String chunkAccess = "net/minecraft/world/level/chunk/ChunkAccess";
        String levelHeightAccessor = "net/minecraft/world/level/LevelHeightAccessor";
        String section = "net/minecraft/world/level/chunk/LevelChunkSection";
        String blockPos = "net/minecraft/core/BlockPos";
        String blockState = "net/minecraft/world/level/block/state/BlockState";
        String fluidState = "net/minecraft/world/level/material/FluidState";
        String chunkStatus = "net/minecraft/world/level/chunk/status/ChunkStatus";
        String heightmap = "net/minecraft/world/level/levelgen/Heightmap";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserMinY",
                "I",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserMaxY",
                "I",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserMinSectionY",
                "I",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserSections",
                "[L" + section + ";",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                "browserHeightmapStatus",
                "L" + chunkStatus + ";",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                "browserHeightmaps",
                "[L" + heightmap + ";",
                null,
                null));

        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/world/level/ChunkPos;"
                        + "Lnet/minecraft/world/level/chunk/UpgradeData;"
                        + "[L" + section + ";"
                        + "Lnet/minecraft/world/ticks/ProtoChunkTicks;"
                        + "Lnet/minecraft/world/ticks/ProtoChunkTicks;"
                        + "L" + levelHeightAccessor + ";"
                        + "Lnet/minecraft/world/level/chunk/PalettedContainerFactory;"
                        + "Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("ProtoChunk constructor return was not found");
        }
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 6));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                levelHeightAccessor,
                "getMinY",
                "()I",
                true));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserMinY", "I"));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 6));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                levelHeightAccessor,
                "getMaxY",
                "()I",
                true));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserMaxY", "I"));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 6));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                levelHeightAccessor,
                "getMinSectionY",
                "()I",
                true));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserMinSectionY", "I"));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, chunkAccess, "sections", "[L" + section + ";"));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "browserSections", "[L" + section + ";"));
        constructor.instructions.insertBefore(constructorReturn, constructorCode);

        String blockPosDescriptor = "L" + blockPos + ";";
        String blockStateDescriptor = "L" + blockState + ";";
        String sectionArrayDescriptor = "[L" + section + ";";
        MethodNode getBlockState = find(
                node,
                "getBlockState",
                "(" + blockPosDescriptor + ")" + blockStateDescriptor);
        LabelNode blockOutside = new LabelNode();
        LabelNode blockNotEmpty = new LabelNode();
        InsnList getBlockStateCode = new InsnList();
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getY", "()I", false));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ISTORE, 2));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinY", "I"));
        getBlockStateCode.add(new JumpInsnNode(Opcodes.IF_ICMPLT, blockOutside));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMaxY", "I"));
        getBlockStateCode.add(new JumpInsnNode(Opcodes.IF_ICMPGT, blockOutside));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserSections", sectionArrayDescriptor));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getBlockStateCode.add(new InsnNode(Opcodes.ICONST_4));
        getBlockStateCode.add(new InsnNode(Opcodes.ISHR));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinSectionY", "I"));
        getBlockStateCode.add(new InsnNode(Opcodes.ISUB));
        getBlockStateCode.add(new InsnNode(Opcodes.AALOAD));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ASTORE, 3));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, section, "hasOnlyAir", "()Z", false));
        getBlockStateCode.add(new JumpInsnNode(Opcodes.IFEQ, blockNotEmpty));
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/block/Blocks",
                "AIR",
                "Lnet/minecraft/world/level/block/Block;"));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/block/Block",
                "defaultBlockState",
                "()" + blockStateDescriptor,
                false));
        getBlockStateCode.add(new InsnNode(Opcodes.ARETURN));
        getBlockStateCode.add(blockNotEmpty);
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getX", "()I", false));
        getBlockStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getBlockStateCode.add(new InsnNode(Opcodes.IAND));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getBlockStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getBlockStateCode.add(new InsnNode(Opcodes.IAND));
        getBlockStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getZ", "()I", false));
        getBlockStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getBlockStateCode.add(new InsnNode(Opcodes.IAND));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                section,
                "getBlockState",
                "(III)" + blockStateDescriptor,
                false));
        getBlockStateCode.add(new InsnNode(Opcodes.ARETURN));
        getBlockStateCode.add(blockOutside);
        getBlockStateCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/block/Blocks",
                "VOID_AIR",
                "Lnet/minecraft/world/level/block/Block;"));
        getBlockStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/block/Block",
                "defaultBlockState",
                "()" + blockStateDescriptor,
                false));
        getBlockStateCode.add(new InsnNode(Opcodes.ARETURN));
        replace(getBlockState, getBlockStateCode, 4, 4);

        MethodNode getFluidState = find(
                node,
                "getFluidState",
                "(" + blockPosDescriptor + ")L" + fluidState + ";");
        LabelNode fluidEmpty = new LabelNode();
        LabelNode fluidNotEmpty = new LabelNode();
        InsnList getFluidStateCode = new InsnList();
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getY", "()I", false));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ISTORE, 2));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getFluidStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinY", "I"));
        getFluidStateCode.add(new JumpInsnNode(Opcodes.IF_ICMPLT, fluidEmpty));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getFluidStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMaxY", "I"));
        getFluidStateCode.add(new JumpInsnNode(Opcodes.IF_ICMPGT, fluidEmpty));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getFluidStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserSections", sectionArrayDescriptor));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getFluidStateCode.add(new InsnNode(Opcodes.ICONST_4));
        getFluidStateCode.add(new InsnNode(Opcodes.ISHR));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getFluidStateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinSectionY", "I"));
        getFluidStateCode.add(new InsnNode(Opcodes.ISUB));
        getFluidStateCode.add(new InsnNode(Opcodes.AALOAD));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ASTORE, 3));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, section, "hasOnlyAir", "()Z", false));
        getFluidStateCode.add(new JumpInsnNode(Opcodes.IFEQ, fluidNotEmpty));
        getFluidStateCode.add(new JumpInsnNode(Opcodes.GOTO, fluidEmpty));
        getFluidStateCode.add(fluidNotEmpty);
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getX", "()I", false));
        getFluidStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getFluidStateCode.add(new InsnNode(Opcodes.IAND));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getFluidStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getFluidStateCode.add(new InsnNode(Opcodes.IAND));
        getFluidStateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getZ", "()I", false));
        getFluidStateCode.add(new IntInsnNode(Opcodes.BIPUSH, 15));
        getFluidStateCode.add(new InsnNode(Opcodes.IAND));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                section,
                "getFluidState",
                "(III)L" + fluidState + ";",
                false));
        getFluidStateCode.add(new InsnNode(Opcodes.ARETURN));
        getFluidStateCode.add(fluidEmpty);
        getFluidStateCode.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/material/Fluids",
                "EMPTY",
                "Lnet/minecraft/world/level/material/Fluid;"));
        getFluidStateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/material/Fluid",
                "defaultFluidState",
                "()L" + fluidState + ";",
                false));
        getFluidStateCode.add(new InsnNode(Opcodes.ARETURN));
        replace(getFluidState, getFluidStateCode, 4, 4);

        MethodNode method = find(
                node,
                "setBlockState",
                "(Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/level/block/state/BlockState;I)"
                        + "Lnet/minecraft/world/level/block/state/BlockState;");
        MethodInsnNode firstHasOnlyAir = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(section)
                    && call.name.equals("hasOnlyAir")
                    && call.desc.equals("()Z")) {
                firstHasOnlyAir = call;
                break;
            }
        }
        if (firstHasOnlyAir == null) {
            throw new IllegalStateException("ProtoChunk setBlockState section prefix was not found");
        }
        AbstractInsnNode sectionReceiver = previousOpcode(firstHasOnlyAir);
        if (!(sectionReceiver instanceof VarInsnNode receiverLoad)
                || receiverLoad.getOpcode() != Opcodes.ALOAD
                || receiverLoad.var != 8) {
            throw new IllegalStateException("ProtoChunk setBlockState section receiver changed");
        }
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != sectionReceiver;) {
            AbstractInsnNode next = instruction.getNext();
            method.instructions.remove(instruction);
            instruction = next;
        }
        LabelNode writeOutside = new LabelNode();
        LabelNode writeSectionReady = new LabelNode();
        InsnList writePrefix = new InsnList();
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
        writePrefix.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getX", "()I", false));
        writePrefix.add(new VarInsnNode(Opcodes.ISTORE, 4));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
        writePrefix.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getY", "()I", false));
        writePrefix.add(new VarInsnNode(Opcodes.ISTORE, 5));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
        writePrefix.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, blockPos, "getZ", "()I", false));
        writePrefix.add(new VarInsnNode(Opcodes.ISTORE, 6));
        writePrefix.add(new VarInsnNode(Opcodes.ILOAD, 5));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        writePrefix.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinY", "I"));
        writePrefix.add(new JumpInsnNode(Opcodes.IF_ICMPLT, writeOutside));
        writePrefix.add(new VarInsnNode(Opcodes.ILOAD, 5));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        writePrefix.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMaxY", "I"));
        writePrefix.add(new JumpInsnNode(Opcodes.IF_ICMPGT, writeOutside));
        writePrefix.add(new VarInsnNode(Opcodes.ILOAD, 5));
        writePrefix.add(new InsnNode(Opcodes.ICONST_4));
        writePrefix.add(new InsnNode(Opcodes.ISHR));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        writePrefix.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinSectionY", "I"));
        writePrefix.add(new InsnNode(Opcodes.ISUB));
        writePrefix.add(new VarInsnNode(Opcodes.ISTORE, 7));
        writePrefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        writePrefix.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserSections", sectionArrayDescriptor));
        writePrefix.add(new VarInsnNode(Opcodes.ILOAD, 7));
        writePrefix.add(new InsnNode(Opcodes.AALOAD));
        writePrefix.add(new VarInsnNode(Opcodes.ASTORE, 8));
        writePrefix.add(new JumpInsnNode(Opcodes.GOTO, writeSectionReady));
        writePrefix.add(writeOutside);
        writePrefix.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "net/minecraft/world/level/block/Blocks",
                "VOID_AIR",
                "Lnet/minecraft/world/level/block/Block;"));
        writePrefix.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/level/block/Block",
                "defaultBlockState",
                "()" + blockStateDescriptor,
                false));
        writePrefix.add(new InsnNode(Opcodes.ARETURN));
        writePrefix.add(writeSectionReady);
        method.instructions.insertBefore(sectionReceiver, writePrefix);

        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/core/SectionPos")
                    && call.name.equals("sectionRelative")
                    && call.desc.equals("(I)I")) {
                InsnList relative = new InsnList();
                relative.add(new IntInsnNode(Opcodes.BIPUSH, 15));
                relative.add(new InsnNode(Opcodes.IAND));
                method.instructions.insertBefore(call, relative);
                method.instructions.remove(call);
            }
            instruction = next;
        }
        MethodInsnNode getPersistedStatus = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals("getPersistedStatus")
                    && call.desc.equals("()L" + chunkStatus + ";")) {
                getPersistedStatus = call;
                break;
            }
        }
        if (getPersistedStatus == null) {
            throw new IllegalStateException("ProtoChunk heightmap update tail was not found");
        }

        AbstractInsnNode tailStart = previousOpcode(getPersistedStatus);
        if (!(tailStart instanceof VarInsnNode loadThis)
                || loadThis.getOpcode() != Opcodes.ALOAD
                || loadThis.var != 0) {
            throw new IllegalStateException("ProtoChunk heightmap update tail start changed");
        }
        for (AbstractInsnNode instruction = tailStart; instruction != null;) {
            AbstractInsnNode next = instruction.getNext();
            method.instructions.remove(instruction);
            instruction = next;
        }

        LabelNode cacheReady = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "getPersistedStatus",
                "()L" + chunkStatus + ";",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 14));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserHeightmapStatus",
                "L" + chunkStatus + ";"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 14));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, cacheReady));

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 14));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                chunkStatus,
                "heightmapsAfter",
                "()Ljava/util/EnumSet;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserProtoChunk",
                "prepareHeightmaps",
                "(Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/EnumSet;)"
                        + "[L" + heightmap + ";",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "browserHeightmaps",
                "[L" + heightmap + ";"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 14));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "browserHeightmapStatus",
                "L" + chunkStatus + ";"));

        code.add(cacheReady);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "browserHeightmaps",
                "[L" + heightmap + ";"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 10));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new VarInsnNode(Opcodes.ILOAD, 12));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserProtoChunk",
                "updateHeightmaps",
                "([L" + heightmap + ";IIILnet/minecraft/world/level/block/state/BlockState;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 13));
        code.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(code);
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxLocals = Math.max(method.maxLocals, 15);
        writeComputeFrames(node, output);
    }

    public static void patchHeightmapBrowserStorage(String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/levelgen/Heightmap";
        String simpleStorage = "net/minecraft/util/SimpleBitStorage";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserMinY",
                "I",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserData",
                "[J",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserBits",
                "I",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserValuesPerLong",
                "I",
                null,
                null));
        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/Heightmap$Types;)V");
        int initialized = 0;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList initialize = new InsnList();
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 1));
                initialize.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/world/level/chunk/ChunkAccess",
                        "getMinY",
                        "()I",
                        false));
                initialize.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "browserMinY", "I"));
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initialize.add(new FieldInsnNode(
                        Opcodes.GETFIELD, owner, "data", "Lnet/minecraft/util/BitStorage;"));
                initialize.add(new TypeInsnNode(Opcodes.CHECKCAST, simpleStorage));
                initialize.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, simpleStorage, "getRaw", "()[J", false));
                initialize.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "browserData", "[J"));
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initialize.add(new VarInsnNode(Opcodes.ILOAD, 3));
                initialize.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "browserBits", "I"));
                initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initialize.add(new IntInsnNode(Opcodes.BIPUSH, 64));
                initialize.add(new VarInsnNode(Opcodes.ILOAD, 3));
                initialize.add(new InsnNode(Opcodes.IDIV));
                initialize.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "browserValuesPerLong", "I"));
                constructor.instructions.insertBefore(instruction, initialize);
                initialized++;
            }
        }
        if (initialized != 1) {
            throw new IllegalStateException(
                    "Heightmap browser minY initialization mismatch: " + initialized);
        }
        constructor.maxStack = Math.max(constructor.maxStack, 4);

        MethodNode indexedGet = find(node, "getFirstAvailable", "(I)I");
        InsnList indexedGetCode = new InsnList();
        indexedGetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        indexedGetCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserData", "[J"));
        indexedGetCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        indexedGetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        indexedGetCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserValuesPerLong", "I"));
        indexedGetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        indexedGetCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserBits", "I"));
        indexedGetCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "get",
                "([JIII)I",
                false));
        indexedGetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        indexedGetCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinY", "I"));
        indexedGetCode.add(new InsnNode(Opcodes.IADD));
        indexedGetCode.add(new InsnNode(Opcodes.IRETURN));
        replace(indexedGet, indexedGetCode, 4, 2);

        for (String name : new String[] {"getFirstAvailable", "getHighestTaken"}) {
            MethodNode method = find(node, name, "(II)I");
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserData", "[J"));
            code.add(new VarInsnNode(Opcodes.ILOAD, 1));
            code.add(new VarInsnNode(Opcodes.ILOAD, 2));
            code.add(new IntInsnNode(Opcodes.BIPUSH, 16));
            code.add(new InsnNode(Opcodes.IMUL));
            code.add(new InsnNode(Opcodes.IADD));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserValuesPerLong", "I"));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserBits", "I"));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserBitStorage",
                    "get",
                    "([JIII)I",
                    false));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "browserMinY", "I"));
            code.add(new InsnNode(Opcodes.IADD));
            if (name.equals("getHighestTaken")) {
                code.add(new InsnNode(Opcodes.ICONST_1));
                code.add(new InsnNode(Opcodes.ISUB));
            }
            code.add(new InsnNode(Opcodes.IRETURN));
            replace(method, code, 4, 3);
        }

        MethodNode setHeight = find(node, "setHeight", "(III)V");
        InsnList setCode = new InsnList();
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserData", "[J"));
        setCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        setCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        setCode.add(new IntInsnNode(Opcodes.BIPUSH, 16));
        setCode.add(new InsnNode(Opcodes.IMUL));
        setCode.add(new InsnNode(Opcodes.IADD));
        setCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserMinY", "I"));
        setCode.add(new InsnNode(Opcodes.ISUB));
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserValuesPerLong", "I"));
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserBits", "I"));
        setCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "getAndSet",
                "([JIIII)I",
                false));
        setCode.add(new InsnNode(Opcodes.POP));
        setCode.add(new InsnNode(Opcodes.RETURN));
        replace(setHeight, setCode, 5, 4);
        writeComputeFrames(node, output);
    }
}
