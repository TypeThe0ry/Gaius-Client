package dev.gaius.browser.kernel.light;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** The canonical light engine API: {@code getLightDampening} and the record {@code ChunkPos}. */
final class DampeningLightOps implements LightVersionOps {
    @Override
    public int lightDampening(BlockState state) {
        return state.getLightDampening();
    }

    @Override
    public int chunkX(ChunkPos pos) {
        return pos.x();
    }

    @Override
    public int chunkZ(ChunkPos pos) {
        return pos.z();
    }

    @Override
    public long packChunk(int chunkX, int chunkZ) {
        return ChunkPos.pack(chunkX, chunkZ);
    }
}
