package dev.gaius.browser.kernel.mesh;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

final class ExportModels {
    private static BlockStateModelSet modelSet;
    private static FluidStateModelSet fluidSet;
    @SuppressWarnings("unchecked")
    static Object compiler(Map<BlockState, Object> models, Object missing, Object sprite, Object baked, Object colors) {
        modelSet = new BlockStateModelSet((Map<BlockState, BlockStateModel>) (Map<?, ?>) models, (BlockStateModel) missing);
        Material.Baked material = (Material.Baked) baked;
        BlockTintSource tint = new BlockTintSource() {
            @Override public int color(BlockState state) { return -1; }
            @Override public int colorInWorld(BlockState state, BlockAndTintGetter region, BlockPos position) {
                return region.getBlockTint(position, BiomeColors.WATER_COLOR_RESOLVER);
            }
        };
        FluidModel water = new FluidModel(ChunkSectionLayer.TRANSLUCENT, material, material, material, tint);
        FluidModel lava = new FluidModel(ChunkSectionLayer.SOLID, material, material, null, null);
        Map<Fluid, FluidModel> fluids = new HashMap<>();
        fluids.put(Fluids.WATER, water); fluids.put(Fluids.FLOWING_WATER, water);
        fluids.put(Fluids.LAVA, lava); fluids.put(Fluids.FLOWING_LAVA, lava);
        fluidSet = new FluidStateModelSet(fluids, water);
        return new SectionCompiler(true, true, modelSet, fluidSet, (BlockColors) colors);
    }
    static byte[] table(Map<BlockState, Object> models, Object missing, Object sprite, Object baked, Object colors, int semantics) {
        ModelTableExporter exporter = new ModelTableExporter(modelSet, fluidSet, (BlockColors) colors, 1);
        while (!exporter.step(Long.MAX_VALUE)) {}
        return exporter.finish().bytes;
    }
    static void options(Object compiler, boolean ambient, boolean cutout) throws Exception {
        MeshGolden.field(compiler, "ambientOcclusion", ambient);
        MeshGolden.field(compiler, "cutoutLeaves", cutout);
        net.minecraft.world.level.block.LeavesBlock.setCutoutLeaves(cutout);
    }
}
