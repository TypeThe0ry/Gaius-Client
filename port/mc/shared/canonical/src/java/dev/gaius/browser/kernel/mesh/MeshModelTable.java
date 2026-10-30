package dev.gaius.browser.kernel.mesh;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;

/**
 * One exported model table: its bytes (kept so the page facade can be given the table again),
 * the resource objects it was exported from, and the per-state bits the main-thread snapshot
 * needs (air for the non-air count, block entities to collect them in vanilla order).
 */
final class MeshModelTable {
    final int epoch;
    final byte[] bytes;
    final int stateCount;
    final BlockStateModelSet models;
    final FluidStateModelSet fluidModels;
    final BlockColors colors;
    private final long[] blockEntityStates;
    private final long[] airStates;
    final int airId;

    MeshModelTable(int epoch, byte[] bytes, int stateCount, BlockStateModelSet models,
            FluidStateModelSet fluidModels, BlockColors colors, long[] blockEntityStates, long[] airStates,
            int airId) {
        this.epoch = epoch;
        this.bytes = bytes;
        this.stateCount = stateCount;
        this.models = models;
        this.fluidModels = fluidModels;
        this.colors = colors;
        this.blockEntityStates = blockEntityStates;
        this.airStates = airStates;
        this.airId = Math.max(airId, 0);
    }

    /**
     * Rehydrates the metadata that the section snapshot needs around an already encoded table.
     * The table bytes contain all model data; the two bitsets are cheap registry-derived indexes
     * and are rebuilt here so a cache hit never runs ModelTableExporter or changes model bytes.
     */
    static MeshModelTable fromCached(byte[] bytes, BlockStateModelSet models,
            FluidStateModelSet fluidModels, BlockColors colors) {
        if (bytes == null || bytes.length < MeshTableWriter.HEADER_LEN
                || bytes.length > 64 * 1024 * 1024
                || readInt(bytes, 0) != MeshTableWriter.MAGIC
                || readShort(bytes, 4) != MeshTableWriter.VERSION
                || readShort(bytes, 6) != MeshProfile.PROFILE) {
            return null;
        }
        int epoch = readInt(bytes, 8);
        int stateCount = readInt(bytes, 12);
        if (epoch < 0 || stateCount <= 0 || stateCount != Block.BLOCK_STATE_REGISTRY.size()
                || readInt(bytes, 60) != 0 || readInt(bytes, 56) > MeshTableWriter.MAX_AIR_IDS
                || !hasExactLayout(bytes)) {
            return null;
        }
        long[] blockEntityStates = new long[(stateCount + 63) >>> 6];
        long[] airStates = new long[(stateCount + 63) >>> 6];
        int airId = -1;
        for (int id = 0; id < stateCount; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null) {
                continue;
            }
            if (state.hasBlockEntity()) {
                blockEntityStates[id >>> 6] |= 1L << (id & 63);
            }
            if (state.isAir()) {
                airStates[id >>> 6] |= 1L << (id & 63);
            }
            if (state == Blocks.AIR.defaultBlockState()) {
                airId = id;
            }
        }
        return new MeshModelTable(epoch, bytes, stateCount, models, fluidModels, colors,
                blockEntityStates, airStates, airId);
    }

    private static boolean hasExactLayout(byte[] bytes) {
        final int[] lengths = {56, 12, 8, 20, 4, 16, 48, 32, 8, 32, 56, 4};
        long expected = MeshTableWriter.HEADER_LEN;
        for (int i = 0; i < lengths.length; i++) {
            int count = readInt(bytes, 12 + i * 4);
            if (count < 0) {
                return false;
            }
            long section = (long) count * lengths[i];
            expected += (section + 7L) & ~7L;
            if (expected > bytes.length) {
                return false;
            }
        }
        return expected == bytes.length;
    }

    private static int readInt(byte[] bytes, int at) {
        return (bytes[at] & 0xff) | ((bytes[at + 1] & 0xff) << 8)
                | ((bytes[at + 2] & 0xff) << 16) | (bytes[at + 3] << 24);
    }

    private static int readShort(byte[] bytes, int at) {
        return (bytes[at] & 0xff) | ((bytes[at + 1] & 0xff) << 8);
    }

    boolean matches(BlockStateModelSet models, FluidStateModelSet fluidModels, BlockColors colors) {
        return this.models == models && this.fluidModels == fluidModels && this.colors == colors;
    }

    /** State ids above 65535 need the wide id layout. */
    boolean wideIds() {
        return stateCount > 0x10000;
    }

    boolean hasBlockEntity(int id) {
        return id >= 0 && id < stateCount && (blockEntityStates[id >>> 6] & (1L << (id & 63))) != 0;
    }

    boolean isAir(int id) {
        return id >= 0 && id < stateCount && (airStates[id >>> 6] & (1L << (id & 63))) != 0;
    }
}
