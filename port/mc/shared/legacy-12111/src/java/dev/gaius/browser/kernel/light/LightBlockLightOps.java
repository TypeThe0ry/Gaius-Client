package dev.gaius.browser.kernel.light;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The light engine API of the legacy patch set: {@code getLightBlock} (renamed
 * {@code getLightDampening} later, same body) and the {@code ChunkPos} class with public
 * {@code x}/{@code z} fields and {@code asLong} (later a record with {@code pack}).
 */
final class LightBlockLightOps implements LightVersionOps {
    @Override
    public int lightDampening(BlockState state) {
        return state.getLightBlock();
    }

    @Override
    public int chunkX(ChunkPos pos) {
        return pos.x;
    }

    @Override
    public int chunkZ(ChunkPos pos) {
        return pos.z;
    }

    @Override
    public long packChunk(int chunkX, int chunkZ) {
        return ChunkPos.asLong(chunkX, chunkZ);
    }
}
