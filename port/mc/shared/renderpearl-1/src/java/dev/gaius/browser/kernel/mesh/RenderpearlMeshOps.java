package dev.gaius.browser.kernel.mesh;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

final class RenderpearlMeshOps implements MeshVersionOps {
    @Override
    public boolean lightPermeable(BlockState state) {
        return state.isLightPermeable();
    }

    @Override
    public boolean blocksFluidFlow(BlockState state) {
        return state.is(BlockTags.BLOCKS_FLUID_FLOW);
    }

    @Override
    public int shadeFace(BakedQuad quad) {
        Direction override = quad.materialInfo().shadeDirectionOverride();
        return (override != null ? override : quad.direction()).ordinal();
    }

    @Override
    public void uploadUniform(RenderPass.UniformUploader uploader, GpuBufferSlice slice) {
        uploader.setUniform("ChunkSection", slice);
    }
}
