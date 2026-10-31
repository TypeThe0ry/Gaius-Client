package dev.gaius.browser.kernel.worldgen;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * JVM transcription of the 26.3 worldgen kernel seam (port/src/versions/26.3/.../WorldgenKernel263)
 * for the patched seed-parity run: the WebAssembly kernel is never available on the JVM, so both
 * hooks decline ({@code null}) and the renamed vanilla methods generate, as in a browser where the
 * kernel is off.
 */
public final class WorldgenKernel263 {
    private WorldgenKernel263() {
    }

    public static CompletableFuture<ChunkAccess> buildTerrain(NoiseBasedChunkGenerator generator, ChunkAccess chunk,
            Blender blender, RandomState randomState, StructureManager structureManager, BiomeManager biomeManager,
            WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes) {
        return null;
    }

    public static CompletableFuture<ChunkAccess> createBiomes(ChunkGenerator generator, RandomState randomState,
            Blender blender, StructureManager structureManager, ChunkAccess chunk) {
        return null;
    }
}
