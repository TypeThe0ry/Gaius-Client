package dev.gaius.browser.kernel.worldgen;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import dev.gaius.browser.kernel.KernelStatusFrontier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.GaiusKernelSections;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/**
 * 26.3 seam between the server's chunk generation and the worldgen kernel.
 * {@code dev.gaius.tools.patches.worldgen.WorldgenKernelPatches} renames {@code NoiseBasedChunkGenerator.buildTerrain}
 * and {@code ChunkGenerator.createBiomes} to {@code gaius$buildTerrainVanilla} /
 * {@code gaius$createBiomesVanilla}; the new methods first call {@link #buildTerrain} /
 * {@link #createBiomes} and run the vanilla method when those return {@code null}.
 *
 * <p>The kernel takes the noise fill, aquifers and material (surface and ore vein) rules of
 * {@code buildTerrain}; the result is installed and the carvers run on the vanilla background
 * executor, like the rest of vanilla's {@code buildTerrain}. The surface rules read the biomes the
 * neighbouring chunks store (sent with the job, see {@link #ringBiomes}) like vanilla's
 * {@code BiomeManager} does. Blended, upgrading and below-zero-retrogen chunks, generators the
 * exporter does not model and any kernel failure stay on (or fall back to) the vanilla path; a
 * result is built off the chunk first, so one that cannot be installed leaves the chunk untouched
 * for vanilla. A transient refusal (memory budget, cancelled job, a neighbour biome the kernel does
 * not know) only sends that chunk to the vanilla path; anything else also stops using the kernel
 * for the generator.
 */
public final class WorldgenKernel263 {
    private static final Map<RandomState, Slot> SLOTS = new IdentityHashMap<>();
    private static final int[] NO_BEARD = {0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final int JOB_TIMEOUT_MS = 15_000;

    private WorldgenKernel263() {
    }

    private static final class Slot {
        int key;
        boolean failed;
        IdMap<Holder<Biome>> biomeIds;
        /**
         * Vanilla enters BIOMES and the merged 26.3 buildTerrain step through one dependency
         * frontier. Kernel callbacks arrive independently, so separate queues let a later
         * terrain commit cross an earlier biome commit while downstream FEATURES is already
         * observing the chunk. Keep every Java-visible kernel commit on one frontier; worker
         * jobs remain parallel, but status consumers see the same order as vanilla.
         */
        final KernelStatusFrontier<ChunkAccess> statusFrontier = new KernelStatusFrontier<>();
    }

    /** The kernel generator slot of a RandomState, exporting it on first use. */
    private static Slot slot(NoiseBasedChunkGenerator generator, RandomState randomState, StructureManager structures,
            ChunkAccess chunk) {
        // Worldgen tasks run cooperatively on one JS thread and the export never suspends, so the
        // map needs no lock (TeaVM monitors would only make this path async).
        Slot slot = SLOTS.get(randomState);
        if (slot == null) {
            slot = new Slot();
            SLOTS.put(randomState, slot);
            try {
                net.minecraft.core.Registry<Biome> biomeRegistry =
                        structures.registryAccess().lookupOrThrow(Registries.BIOME);
                slot.biomeIds = biomeRegistry.asHolderIdMap();
                byte[] ir = WorldgenExport263.export(generator, randomState, structures.registryAccess(),
                        biomeRegistry::getId, chunk.getHeightAccessorForGeneration());
                slot.key = BrowserWorldgenKernel.allocateKey();
                slot.failed = !BrowserWorldgenKernel.registerGenerator(slot.key, ir);
            } catch (RuntimeException e) {
                slot.failed = true;
                System.out.println("[Gaius] worldgen kernel export skipped: " + e);
            }
        }
        return slot.failed || !BrowserWorldgenKernel.generatorUsable(slot.key) ? null : slot;
    }

    private static boolean eligible(ChunkAccess chunk, Blender blender) {
        return chunk instanceof ProtoChunk
                && blender.isEmpty()
                && !chunk.isUpgrading()
                && chunk.getBelowZeroRetrogen() == null;
    }

    /** Marks the slot failed unless the error was a transient refusal for this one job. */
    private static void failed(Slot slot, String what, String message) {
        BrowserWorldgenKernel.noteHostFallback(what, message != null && message.contains("timeout")
                ? "timeout" : "kernel-error");
        if (!BrowserWorldgenKernel.isTransient(message)) {
            slot.failed = true;
            System.out.println("[Gaius] worldgen kernel " + what + " failed, using the Java path: " + message);
        }
    }

    private static void dispatch(Runnable task, Runnable fallback) {
        try {
            if (!BrowserWorldgenKernel.runOnJavaThread(task)) {
                BrowserWorldgenKernel.noteHostFallback("dispatch", "thread-start-failure");
                fallback.run();
            }
        } catch (Throwable error) {
            BrowserWorldgenKernel.noteHostFallback("dispatch", "thread-start-failure");
            fallback.run();
        }
    }

    /** JVM golden-test seam: replay a vanilla terrain result through the production commit path. */
    static void installTerrainForJvmTest(ChunkAccess target, ChunkAccess source) {
        TerrainData data = TerrainData.fromVanilla(source);
        LevelChunkSection[] built = new LevelChunkSection[data.sectionCount];
        for (int section = 0; section < built.length; section++) {
            built[section] = source.getSection(section).copy();
        }
        data.commit(target, built);
    }

    /** Fast-path prototype seam: install the complete kernel terrain result without doFill. */
    static void installTerrainFastForJvmTest(ChunkAccess target, ChunkAccess source) {
        installTerrainForJvmTest(target, source);
    }

    /** Hook in place of {@code NoiseBasedChunkGenerator.buildTerrain}; {@code null} runs vanilla. */
    public static CompletableFuture<ChunkAccess> buildTerrain(
            NoiseBasedChunkGenerator generator,
            ChunkAccess chunk,
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            BiomeManager biomeManager,
            WorldGenRegion carverBiomeRegion,
            Set<Holder<Biome>> possibleBiomes) {
        if (!BrowserWorldgenKernel.available() || !eligible(chunk, blender)) {
            return null;
        }
        NoiseSettings noise = generator.generatorSettings().value().noiseSettings()
                .clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        if (noise.height() <= 0) {
            return null;
        }
        Slot slot = slot(generator, randomState, structureManager, chunk);
        if (slot == null) {
            return null;
        }
        ChunkPos pos = chunk.getPos();
        Supplier<CompletableFuture<ChunkAccess>> vanilla = () -> ((WorldgenKernelHooks263) (Object) generator)
                .gaius$buildTerrainVanilla(chunk, blender, randomState, structureManager, biomeManager,
                        carverBiomeRegion, possibleBiomes);
        KernelStatusFrontier.Ticket<ChunkAccess> ticket = slot.statusFrontier.register(vanilla, JOB_TIMEOUT_MS,
                reason -> {
                    if ("timeout".equals(reason)) {
                        failed(slot, "terrain watchdog", "transient:worldgen timeout");
                    }
                });
        CompletableFuture<ChunkAccess> future = ticket.future();
        ticket.consumeAtTask();
        int[] beard;
        int[] ring;
        try {
            beard = beard(structureManager, pos);
            ring = ringBiomes(biomeManager, slot.biomeIds, chunk);
        } catch (RuntimeException e) {
            failed(slot, "terrain preparation", String.valueOf(e));
            ticket.fallback("terrain-preparation");
            return future;
        }
        try {
            BrowserWorldgenKernel.submitTerrain(slot.key, pos.x(), pos.z(), BrowserWorldgenKernel.FLAG_SURFACE, beard,
                    ring,
                    // The result object holds fresh typed arrays (worldgen-kernel.js flatten), so it can
                    // be read later on a Java thread instead of inside the JS callback.
                    result -> dispatch(() -> {
                        TerrainData data;
                        try {
                            data = TerrainData.read(chunk, result);
                        } catch (RuntimeException e) {
                            slot.failed = true;
                            BrowserWorldgenKernel.noteHostFallback("terrain", "decode-failure");
                            BrowserWorldgenKernel.disable("terrain result rejected: " + e);
                            ticket.fallback("decode-failure");
                            return;
                        }
                        ticket.prefetch(() -> CompletableFuture.supplyAsync(() -> {
                            LevelChunkSection[] built;
                            try {
                                built = data.buildSections(chunk);
                            } catch (RuntimeException e) {
                                // Nothing of the chunk changed yet: vanilla builds it from scratch.
                                slot.failed = true;
                                BrowserWorldgenKernel.noteHostFallback("terrain", "decode-failure");
                                BrowserWorldgenKernel.disable("terrain result install failed: " + e);
                                return null;
                            }
                            WorldgenKernelHooks263 hooks = (WorldgenKernelHooks263) (Object) generator;
                            NoiseGeneratorSettings settings = generator.generatorSettings().value();
                            NoiseSettings terrainNoise = settings.noiseSettings()
                                    .clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
                            try (NoiseChunk noiseChunk = hooks.gaius$createNoiseChunk(
                                    chunk, structureManager, blender, randomState, terrainNoise)) {
                                // Carvers only observe the lazily evaluated aquifer and material
                                // samplers. The kernel already supplied terrain, heightmaps and
                                // post-processing, so warming this NoiseChunk with doFill would
                                // duplicate work and mutate the authoritative post list.
                                data.commit(chunk, built);
                                hooks.gaius$generateCarvers(chunk, blender, noiseChunk, randomState,
                                        biomeManager, carverBiomeRegion, settings.materialRule().value());
                            }
                            return chunk;
                        }, Util.backgroundExecutor().forName("buildTerrain"))
                                .thenCompose(c -> c != null ? CompletableFuture.completedFuture(c) : vanilla.get()));
                    }, () -> ticket.fallback("dispatch-failure")),
                    message -> dispatch(() -> {
                        failed(slot, "terrain", message);
                        ticket.fallback("kernel-error");
                    }, () -> ticket.fallback("dispatch-failure")));
        } catch (RuntimeException e) {
            failed(slot, "terrain submit", String.valueOf(e));
            ticket.fallback("sync-submit");
            return future;
        }
        return future;
    }

    /**
     * Runs a result handler; if it throws before completing {@code future}, the chunk falls back to
     * the vanilla future (and to an exceptional completion when that throws too), so a dispatched
     * future is never left pending.
     */
    private static void guarded(CompletableFuture<ChunkAccess> future, Supplier<CompletableFuture<ChunkAccess>> vanilla,
            Runnable handler) {
        try {
            handler.run();
        } catch (Throwable t) {
            if (!future.isDone()) {
                System.out.println("[Gaius] worldgen kernel result handler failed, using the Java path: " + t);
                forward(future, vanilla);
            }
        }
    }

    /** Completes {@code future} with the outcome of {@code source} (which may also throw). */
    private static void forward(CompletableFuture<ChunkAccess> future, Supplier<CompletableFuture<ChunkAccess>> source) {
        CompletableFuture<ChunkAccess> inner;
        try {
            inner = source.get();
        } catch (Throwable t) {
            future.completeExceptionally(t);
            return;
        }
        inner.whenComplete((c, t) -> {
            if (t != null) {
                future.completeExceptionally(t);
            } else {
                future.complete(c);
            }
        });
    }

    /** Hook in place of {@code ChunkGenerator.createBiomes}; {@code null} runs vanilla. */
    public static CompletableFuture<ChunkAccess> createBiomes(
            ChunkGenerator generator, RandomState randomState, Blender blender, StructureManager structureManager,
            ChunkAccess chunk) {
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)
                || !biomeSourceSupportsKernel(noiseGenerator.getBiomeSource())
                || !BrowserWorldgenKernel.available() || !eligible(chunk, blender)) {
            return null;
        }
        Slot slot = slot(noiseGenerator, randomState, structureManager, chunk);
        if (slot == null) {
            return null;
        }
        IdMap<Holder<Biome>> biomes = slot.biomeIds;
        ChunkPos pos = chunk.getPos();
        Supplier<CompletableFuture<ChunkAccess>> vanilla = () ->
                ((ChunkGeneratorKernelHooks263) (Object) generator)
                        .gaius$createBiomesVanilla(randomState, blender, structureManager, chunk);
        KernelStatusFrontier.Ticket<ChunkAccess> ticket = slot.statusFrontier.register(vanilla, JOB_TIMEOUT_MS,
                reason -> {
                    if ("timeout".equals(reason)) {
                        failed(slot, "biomes watchdog", "transient:worldgen timeout");
                    }
                });
        CompletableFuture<ChunkAccess> future = ticket.future();
        ticket.consumeAtTask();
        try {
            BrowserWorldgenKernel.submitBiomes(slot.key, pos.x(), pos.z(),
                    ids -> dispatch(() -> guarded(future, vanilla, () -> {
                        int[] values = BrowserWorldgenKernel.ids(ids);
                        Holder<Biome>[] holders = resolveBiomes(biomes, values);
                        if (holders == null || values.length != chunk.getSectionsCount() * 64) {
                            slot.failed = true;
                            BrowserWorldgenKernel.noteHostFallback("biomes", "decode-failure");
                            BrowserWorldgenKernel.disable("biome result rejected");
                            ticket.fallback("decode-failure");
                            return;
                        }
                        ticket.prefetch(() -> CompletableFuture.supplyAsync(() -> {
                            int minQuartY = QuartPos.fromBlock(chunk.getMinY());
                            chunk.fillBiomesFromNoise((qx, qy, qz) -> {
                                int y = qy - minQuartY;
                                return holders[((y >> 2) << 6) | ((y & 3) << 4) | ((qz & 3) << 2) | (qx & 3)];
                            });
                            return chunk;
                        }, Util.backgroundExecutor().forName("wgen_biomes")));
                    }), () -> ticket.fallback("dispatch-failure")),
                    message -> dispatch(() -> guarded(future, vanilla, () -> {
                        failed(slot, "biomes", message);
                        ticket.fallback("kernel-error");
                    }), () -> ticket.fallback("dispatch-failure")));
        } catch (RuntimeException e) {
            failed(slot, "biomes submit", String.valueOf(e));
            ticket.fallback("sync-submit");
            return future;
        }
        return future;
    }

    /**
     * Multi-noise BIOMES jobs always decline in the Rust kernel because vanilla's RTree search
     * warm-start depends on the calling thread's previous biome. Keep the source classification
     * aligned with {@link WorldgenExport263#biomes()} so known declines use vanilla immediately.
     */
    static boolean biomeSourceSupportsKernel(BiomeSource source) {
        return !(source instanceof MultiNoiseBiomeSource);
    }

    /** Stored quart columns in the chunk's complete 6 x 6 biome context. */
    private static final int RING_COLUMNS = 36;

    /**
     * The biomes stored in and around {@code chunk}, as the kernel's context:
     * for each quart column of the complete 6 x 6 grid (x outer, z inner,
     * starting one quart before the chunk), every quart y from the bottom of the level, as biome
     * registry ids ({@code -1} for a biome without one; the kernel then refuses the chunk). Read
     * through {@code biomeManager} (the region's), like vanilla's surface rules.
     */
    static int[] ringBiomes(BiomeManager biomeManager, IdMap<Holder<Biome>> biomeIds, ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int minQx = (pos.x() << 2) - 1;
        int minQz = (pos.z() << 2) - 1;
        int minQy = QuartPos.fromBlock(chunk.getMinY());
        int quartsY = chunk.getSectionsCount() * 4;
        int[] ring = new int[RING_COLUMNS * quartsY];
        Holder<Biome> last = null;
        int lastId = -1;
        int k = 0;
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                for (int y = 0; y < quartsY; y++) {
                    Holder<Biome> holder = biomeManager.getNoiseBiomeAtQuart(minQx + x, minQy + y, minQz + z);
                    if (holder != last) {
                        last = holder;
                        lastId = biomeIds.getId(holder);
                    }
                    ring[k++] = lastId;
                }
            }
        }
        return ring;
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] resolveBiomes(IdMap<Holder<Biome>> biomes, int[] ids) {
        Holder<Biome>[] holders = new Holder[ids.length];
        for (int i = 0; i < ids.length; i++) {
            holders[i] = biomes.byId(ids[i]);
            if (holders[i] == null) {
                return null;
            }
        }
        return holders;
    }

    // ---- beardifier ----

    /**
     * {@code Beardifier.forStructuresInChunk}, packed for the kernel:
     * {@code [flags, affected x6, rigidCount, rigid x8 ..., junctionCount, junction x3 ...]}.
     */
    static int[] beard(StructureManager structures, ChunkPos chunkPos) {
        List<StructureStart> starts = structures.startsForStructure(
                chunkPos.x(), chunkPos.z(), s -> s.terrainAdaptation() != TerrainAdjustment.NONE);
        if (starts.isEmpty()) {
            return NO_BEARD;
        }
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        IntBuffer rigids = new IntBuffer();
        IntBuffer junctions = new IntBuffer();
        int rigidCount = 0;
        int junctionCount = 0;
        BoundingBox any = null;
        for (StructureStart start : starts) {
            TerrainAdjustment adjustment = start.getStructure().terrainAdaptation();
            for (StructurePiece piece : start.getPieces()) {
                if (!piece.isCloseToChunk(chunkPos, 12)) {
                    continue;
                }
                if (piece instanceof PoolElementStructurePiece pool) {
                    if (pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
                        rigid(rigids, pool.getBoundingBox(), adjustment, pool.getGroundLevelDelta());
                        rigidCount++;
                        any = include(any, piece.getBoundingBox());
                    }
                    for (JigsawJunction junction : pool.getJunctions()) {
                        int jx = junction.getSourceX();
                        int jz = junction.getSourceZ();
                        if (jx > minX - 12 && jz > minZ - 12 && jx < minX + 15 + 12 && jz < minZ + 15 + 12) {
                            junctions.add(jx).add(junction.getSourceGroundY()).add(jz);
                            junctionCount++;
                            any = include(any, new BoundingBox(new BlockPos(jx, junction.getSourceGroundY(), jz)));
                        }
                    }
                } else {
                    rigid(rigids, piece.getBoundingBox(), adjustment, 0);
                    rigidCount++;
                    any = include(any, piece.getBoundingBox());
                }
            }
        }
        if (any == null) {
            return NO_BEARD;
        }
        BoundingBox affected = any.inflatedBy(24);
        IntBuffer out = new IntBuffer();
        out.add(1).add(affected.minX()).add(affected.minY()).add(affected.minZ())
                .add(affected.maxX()).add(affected.maxY()).add(affected.maxZ());
        out.add(rigidCount).addAll(rigids).add(junctionCount).addAll(junctions);
        return out.toArray();
    }

    private static BoundingBox include(BoundingBox box, BoundingBox add) {
        return box == null ? add : BoundingBox.encapsulating(box, add);
    }

    private static void rigid(IntBuffer out, BoundingBox box, TerrainAdjustment adjustment, int groundDelta) {
        int code = switch (adjustment) {
            case NONE -> 0;
            case BURY -> 1;
            case BEARD_THIN -> 2;
            case BEARD_BOX -> 3;
            case ENCAPSULATE -> 4;
        };
        out.add(box.minX()).add(box.minY()).add(box.minZ()).add(box.maxX()).add(box.maxY()).add(box.maxZ())
                .add(code).add(groundDelta);
    }

    private static final class IntBuffer {
        private int[] data = new int[32];
        private int size;

        IntBuffer add(int v) {
            if (size == data.length) {
                int[] grown = new int[size * 2];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = v;
            return this;
        }

        IntBuffer addAll(IntBuffer other) {
            for (int i = 0; i < other.size; i++) {
                add(other.data[i]);
            }
            return this;
        }

        int[] toArray() {
            int[] out = new int[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    // ---- result installation ----

    /** A validated terrain result; {@link #read} rejects anything that does not fit the chunk. */
    private static final class TerrainData {
        int sectionCount;
        int[] paletteOffsets;
        BlockState[] palette;
        short[] indices;
        byte[] uniform;
        int[] worldSurface;
        int[] oceanFloor;
        int[] post;

        static TerrainData fromVanilla(ChunkAccess source) {
            TerrainData d = new TerrainData();
            d.sectionCount = source.getSectionsCount();
            d.worldSurface = new int[256];
            d.oceanFloor = new int[256];
            for (int column = 0; column < 256; column++) {
                int x = column & 15;
                int z = column >>> 4;
                // The kernel ABI carries the first free Y; ChunkAccess.getHeight returns the
                // highest occupied Y, so mirror vanilla's +1 conversion at the bridge boundary.
                d.worldSurface[column] = source.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;
                d.oceanFloor[column] = source.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) + 1;
            }
            IntBuffer packed = new IntBuffer();
            it.unimi.dsi.fastutil.shorts.ShortList[] postProcessing = source.getPostProcessing();
            for (int section = 0; section < postProcessing.length; section++) {
                it.unimi.dsi.fastutil.shorts.ShortList list = postProcessing[section];
                if (list == null) {
                    continue;
                }
                for (int index = 0; index < list.size(); index++) {
                    packed.add((section << 16) | (list.getShort(index) & 0xFFFF));
                }
            }
            d.post = packed.toArray();
            return d;
        }

        static TerrainData read(ChunkAccess chunk, BrowserWorldgenKernel.KernelChunk result) {
            TerrainData d = new TerrainData();
            ChunkPos pos = chunk.getPos();
            if (BrowserWorldgenKernel.chunkInt(result, "chunkX") != pos.x()
                    || BrowserWorldgenKernel.chunkInt(result, "chunkZ") != pos.z()
                    || BrowserWorldgenKernel.chunkInt(result, "minY") != chunk.getMinY()) {
                throw new IllegalStateException("kernel chunk does not match " + pos);
            }
            d.sectionCount = BrowserWorldgenKernel.chunkInt(result, "sectionCount");
            if (d.sectionCount != chunk.getSectionsCount()) {
                throw new IllegalStateException("kernel chunk has " + d.sectionCount + " sections");
            }
            d.paletteOffsets = BrowserWorldgenKernel.chunkInts(result, "paletteOffsets");
            int[] ids = BrowserWorldgenKernel.chunkInts(result, "palettes");
            d.indices = BrowserWorldgenKernel.chunkIndices(result);
            d.uniform = BrowserWorldgenKernel.chunkBytes(result, "uniform");
            d.worldSurface = BrowserWorldgenKernel.chunkInts(result, "worldSurface");
            d.oceanFloor = BrowserWorldgenKernel.chunkInts(result, "oceanFloor");
            d.post = BrowserWorldgenKernel.chunkInts(result, "postProcessing");
            if (d.paletteOffsets == null || ids == null || d.worldSurface == null || d.oceanFloor == null
                    || d.post == null || d.paletteOffsets.length != d.sectionCount + 1
                    || d.indices.length != d.sectionCount * 4096 || d.uniform.length != d.sectionCount
                    || d.worldSurface.length != 256 || d.oceanFloor.length != 256) {
                throw new IllegalStateException("kernel chunk arrays have unexpected sizes");
            }
            d.palette = new BlockState[ids.length];
            for (int i = 0; i < ids.length; i++) {
                d.palette[i] = Block.BLOCK_STATE_REGISTRY.byId(ids[i]);
                if (d.palette[i] == null) {
                    throw new IllegalStateException("unknown block state id " + ids[i]);
                }
            }
            for (int s = 0; s < d.sectionCount; s++) {
                int size = d.paletteOffsets[s + 1] - d.paletteOffsets[s];
                if (size <= 0) {
                    throw new IllegalStateException("empty section palette");
                }
                if (d.uniform[s] == 0) {
                    for (int i = s * 4096, end = i + 4096; i < end; i++) {
                        if ((d.indices[i] & 0xFFFF) >= size) {
                            throw new IllegalStateException("palette index out of range");
                        }
                    }
                }
            }
            return d;
        }

        /**
         * Every section the result changes, built off the chunk (bulk: {@code GaiusKernelSections}
         * builds each block state container in one step, like a loaded section; block by block
         * like {@code doFill}, on a copy of the old section, only for a palette the bulk path
         * refuses); {@code null} entries keep the old section. Throws without touching the chunk,
         * so a result that cannot be installed leaves it to vanilla.
         */
        LevelChunkSection[] buildSections(ChunkAccess chunk) {
            LevelChunkSection[] built = new LevelChunkSection[sectionCount];
            for (int s = 0; s < sectionCount; s++) {
                int base = paletteOffsets[s];
                int size = paletteOffsets[s + 1] - base;
                boolean isUniform = uniform[s] != 0;
                if (isUniform && palette[base].isAir()) {
                    continue;
                }
                LevelChunkSection old = chunk.getSection(s);
                PalettedContainer<BlockState> states =
                        GaiusKernelSections.container(palette, base, size, indices, s * 4096, isUniform);
                if (states != null) {
                    built[s] = new LevelChunkSection(states, old.getBiomes());
                    continue;
                }
                LevelChunkSection section = old.copy();
                if (isUniform) {
                    BlockState state = palette[base];
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                section.setBlockState(x, y, z, state, false);
                            }
                        }
                    }
                } else {
                    int offset = s * 4096;
                    for (int i = 0; i < 4096; i++) {
                        BlockState state = palette[base + (indices[offset + i] & 0xFFFF)];
                        section.setBlockState(i & 15, i >>> 8, (i >>> 4) & 15, state, false);
                    }
                }
                built[s] = section;
            }
            return built;
        }

        /**
         * Installs {@link #buildSections}'s sections (plain assignments), then the heightmaps and
         * post-processing marks (vanilla bounds-checks both).
         */
        void commit(ChunkAccess chunk, LevelChunkSection[] built) {
            LevelChunkSection[] sections = chunk.getSections();
            for (int s = 0; s < sectionCount; s++) {
                if (built[s] != null) {
                    sections[s] = built[s];
                }
            }
            ChunkPos pos = chunk.getPos();
            int minX = pos.getMinBlockX();
            int minZ = pos.getMinBlockZ();
            int minY = chunk.getMinY();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            Heightmap oceanFloorMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
            Heightmap worldSurfaceMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
            for (int column = 0; column < 256; column++) {
                int x = column & 15;
                int z = column >>> 4;
                if (oceanFloor[column] > minY) {
                    int y = oceanFloor[column] - 1;
                    oceanFloorMap.update(x, y, z, chunk.getBlockState(cursor.set(minX + x, y, minZ + z)));
                }
                if (worldSurface[column] > minY) {
                    int y = worldSurface[column] - 1;
                    worldSurfaceMap.update(x, y, z, chunk.getBlockState(cursor.set(minX + x, y, minZ + z)));
                }
            }
            int minSection = chunk.getMinSectionY();
            for (int entry : post) {
                int section = entry >>> 16;
                int packed = entry & 0xFFFF;
                chunk.markPosForPostProcessing(new BlockPos(
                        minX + (packed & 15), ((minSection + section) << 4) + ((packed >>> 4) & 15),
                        minZ + ((packed >>> 8) & 15)));
            }
        }
    }
}
