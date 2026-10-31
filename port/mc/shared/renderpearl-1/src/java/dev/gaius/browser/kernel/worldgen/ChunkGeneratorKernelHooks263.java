package dev.gaius.browser.kernel.worldgen;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Added to the 26.3 {@code ChunkGenerator} by {@code dev.gaius.tools.patches.worldgen.WorldgenKernelPatches}: the
 * vanilla {@code createBiomes}, renamed when the kernel hook took its place.
 */
public interface ChunkGeneratorKernelHooks263 {
    CompletableFuture<ChunkAccess> gaius$createBiomesVanilla(
            RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk);
}
