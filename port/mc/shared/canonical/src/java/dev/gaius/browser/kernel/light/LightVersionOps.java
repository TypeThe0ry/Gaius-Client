package dev.gaius.browser.kernel.light;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The vanilla members of the light kernel bridge ({@link BrowserLightKernel},
 * {@link BrowserLightStateTable}) whose names differ between generations of the light engine
 * API while their meaning does not: the light a block state absorbs, and the coordinates and
 * packed key of a chunk position. The instance is selected statically: the canonical
 * implementation is relocated to a generation's own by the {@code relocations.tsv} of the source
 * set that generation selects (port/mc/shared/README.md, single-source ops).
 */
public interface LightVersionOps {
    LightVersionOps INSTANCE = new dev.gaius.browser.kernel.light.DampeningLightOps();

    /** The light dampening of a state, as vanilla's light engine reads it (0..15). */
    int lightDampening(BlockState state);

    int chunkX(ChunkPos pos);

    int chunkZ(ChunkPos pos);

    /** The key vanilla packs a chunk position into. */
    long packChunk(int chunkX, int chunkZ);
}
