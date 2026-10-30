package dev.gaius.browser;

import java.util.EnumSet;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** Keeps ProtoChunk heightmap updates allocation-free between status changes. */
public final class BrowserProtoChunk {
    private static final Heightmap.Types[] TYPES = {
        Heightmap.Types.WORLD_SURFACE_WG,
        Heightmap.Types.WORLD_SURFACE,
        Heightmap.Types.OCEAN_FLOOR_WG,
        Heightmap.Types.OCEAN_FLOOR,
        Heightmap.Types.MOTION_BLOCKING,
        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
    };

    private BrowserProtoChunk() {
    }

    public static Heightmap[] prepareHeightmaps(
            ChunkAccess chunk, EnumSet<Heightmap.Types> required) {
        EnumSet<Heightmap.Types> missing = EnumSet.noneOf(Heightmap.Types.class);
        for (int i = 0; i < TYPES.length; i++) {
            Heightmap.Types type = TYPES[i];
            if (required.contains(type) && !chunk.hasPrimedHeightmap(type)) {
                missing.add(type);
            }
        }
        if (!missing.isEmpty()) {
            Heightmap.primeHeightmaps(chunk, missing);
        }

        Heightmap[] heightmaps = new Heightmap[required.size()];
        int index = 0;
        for (int i = 0; i < TYPES.length; i++) {
            Heightmap.Types type = TYPES[i];
            if (required.contains(type)) {
                heightmaps[index++] = chunk.getOrCreateHeightmapUnprimed(type);
            }
        }
        if (index != heightmaps.length) {
            throw new IllegalStateException("Unsupported heightmap type set");
        }
        return heightmaps;
    }

    public static void updateHeightmaps(
            Heightmap[] heightmaps, int x, int y, int z, BlockState state) {
        for (int i = 0; i < heightmaps.length; i++) {
            heightmaps[i].update(x, y, z, state);
        }
    }
}
