package net.minecraft.client.renderer.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

public final class SnapshotRegion extends RenderSectionRegion {
    public dev.gaius.browser.kernel.mesh.SnapshotTint tint;
    public BlockState[] states;
    public byte[] light;
    public int sectionX, sectionY, sectionZ;
    private SnapshotRegion() { super(null, 0, 0, 0, null); }
    private int index(BlockPos position) {
        int horizontal = position.getX() - sectionX * 16 + 2;
        int vertical = position.getY() - sectionY * 16 + 2;
        int depth = position.getZ() - sectionZ * 16 + 2;
        return horizontal < 0 || horizontal >= 20 || vertical < 0 || vertical >= 20 || depth < 0 || depth >= 20
            ? -1 : (vertical * 20 + depth) * 20 + horizontal;
    }
    @Override public BlockState getBlockState(BlockPos position) {
        int offset = index(position);
        return offset < 0 ? Blocks.AIR.defaultBlockState() : states[offset];
    }
    @Override public FluidState getFluidState(BlockPos position) { return getBlockState(position).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos position) { return null; }
    @Override public CardinalLighting cardinalLighting() { return CardinalLighting.DEFAULT; }
    @Override public LevelLightEngine getLightEngine() { return LevelLightEngine.EMPTY; }
    @Override public int getBrightness(LightLayer layer, BlockPos position) {
        int offset = index(position);
        int value = offset < 0 ? 0 : Byte.toUnsignedInt(light[offset]);
        return layer == LightLayer.SKY ? value >>> 4 : value & 15;
    }
    @Override public int getBlockTint(BlockPos position, ColorResolver resolver) {
        return tint.color(position, resolver);
    }
    @Override public int getMinY() { return -64; }
    @Override public int getHeight() { return 384; }
}
