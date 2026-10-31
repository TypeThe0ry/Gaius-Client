package dev.gaius.browser.kernel.mesh;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

final class Blaze3dMeshOps implements MeshVersionOps {
    @Override
    public boolean lightPermeable(BlockState state) {
        return !state.isViewBlocking(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) || state.getLightDampening() == 0;
    }

    @Override
    public boolean blocksFluidFlow(BlockState state) {
        return state.blocksMotion();
    }

    @Override
    public int shadeFace(BakedQuad quad) {
        return quad.materialInfo().shade() ? quad.direction().ordinal() : Direction.UP.ordinal();
    }

    @Override
    public void uploadUniform(RenderPass.UniformUploader uploader, GpuBufferSlice slice) {
        uploader.upload("ChunkSection", slice);
    }
}
