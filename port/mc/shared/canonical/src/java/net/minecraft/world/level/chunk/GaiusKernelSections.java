package net.minecraft.world.level.chunk;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bulk install of worldgen kernel sections into a proto chunk. A kernel section (palette plus one
 * palette index per block) becomes a block state container the way a saved section is loaded
 * ({@link PalettedContainer#unpack}: palette list and bit-packed indices), instead of 4096
 * {@code setBlockState} calls with their palette lookups and resizes. The caller's new section
 * keeps the old section's biomes and counts its blocks once ({@code recalcBlockCounts}).
 *
 * <p>Lives in the vanilla package for {@code Strategy.getConfigurationForPaletteSize}, which
 * decides the storage bits {@code unpack} expects. Identical copies exist for 26.2 and 26.3.
 */
public final class GaiusKernelSections {
    private static Strategy<BlockState> strategy;

    private GaiusKernelSections() {
    }

    private static Strategy<BlockState> strategy() {
        Strategy<BlockState> s = strategy;
        if (s == null) {
            s = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
            strategy = s;
        }
        return s;
    }

    /**
     * The block state container of the kernel section {@code palette[from .. from + size)};
     * {@code indices[offset .. offset + 4096)} holds the palette index (u16) of each block in
     * y, z, x order, unless {@code uniform}. {@code null} when the palette has duplicates (the disk
     * format cannot express those), so the caller can set the blocks one by one instead. Builds a
     * new container only: the caller swaps the section in once every section of the chunk is
     * built.
     */
    public static PalettedContainer<BlockState> container(BlockState[] palette, int from, int size,
            short[] indices, int offset, boolean uniform) {
        if (size <= 0) {
            throw new IllegalArgumentException("empty kernel section palette");
        }
        int entries = uniform ? 1 : size;
        if (entries > 1 && !distinct(palette, from, entries)) {
            return null;
        }
        Strategy<BlockState> s = strategy();
        List<BlockState> list = Arrays.asList(Arrays.copyOfRange(palette, from, from + entries));
        Configuration configuration = s.getConfigurationForPaletteSize(entries);
        PalettedContainerRO.PackedData<BlockState> packed;
        if (configuration.bitsInMemory() == 0) {
            packed = new PalettedContainerRO.PackedData<>(list, Optional.empty(), configuration.bitsInStorage());
        } else {
            long[] raw = pack(indices, offset, configuration.bitsInStorage(), s.entryCount());
            packed = new PalettedContainerRO.PackedData<>(list, Optional.of(LongStream.of(raw)),
                    configuration.bitsInStorage());
        }
        return PalettedContainer.unpack(s, packed)
                .getOrThrow(message -> new IllegalStateException("kernel section: " + message));
    }

    /** The {@code SimpleBitStorage} layout: values never span two longs. */
    private static long[] pack(short[] indices, int offset, int bits, int count) {
        int perLong = 64 / bits;
        int longs = (count + perLong - 1) / perLong;
        long[] raw = new long[longs];
        long mask = (1L << bits) - 1L;
        int i = 0;
        for (int cell = 0; cell < longs; cell++) {
            long word = 0L;
            int end = Math.min(count, i + perLong);
            for (int shift = 0; i < end; i++, shift += bits) {
                word |= ((long) (indices[offset + i] & 0xFFFF) & mask) << shift;
            }
            raw[cell] = word;
        }
        return raw;
    }

    private static boolean distinct(BlockState[] palette, int from, int size) {
        if (size <= 32) {
            for (int i = from + 1; i < from + size; i++) {
                for (int j = from; j < i; j++) {
                    if (palette[i] == palette[j]) {
                        return false;
                    }
                }
            }
            return true;
        }
        IdentityHashMap<BlockState, Boolean> seen = new IdentityHashMap<>(size * 2);
        for (int i = from; i < from + size; i++) {
            if (seen.put(palette[i], Boolean.TRUE) != null) {
                return false;
            }
        }
        return true;
    }
}
