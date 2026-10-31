package dev.gaius.browser.kernel.worldgen;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;

/**
 * Added to the 26.3 {@code NoiseBasedChunkGenerator} by {@code dev.gaius.tools.patches.worldgen.WorldgenKernelPatches}:
 * the vanilla {@code buildTerrain} (renamed when the kernel hook took its place) and bridges to the
 * private {@code createNoiseChunk} / {@code generateCarvers}, which run the carvers on a chunk the
 * kernel filled.
 */
public interface WorldgenKernelHooks263 {
    CompletableFuture<ChunkAccess> gaius$buildTerrainVanilla(
            ChunkAccess chunk,
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            BiomeManager biomeManager,
            WorldGenRegion carverBiomeRegion,
            Set<Holder<Biome>> possibleBiomes);

    NoiseChunk gaius$createNoiseChunk(
            ChunkAccess chunk,
            StructureManager structureManager,
            Blender blender,
            RandomState randomState,
            NoiseSettings noiseSettings);

    void gaius$doFill(NoiseChunk noiseChunk, ChunkAccess chunk);

    void gaius$generateCarvers(
            ChunkAccess chunk,
            Blender blender,
            NoiseChunk noiseChunk,
            RandomState randomState,
            BiomeManager biomeManager,
            WorldGenRegion carverBiomeRegion,
            MaterialRule materialRule);
}
