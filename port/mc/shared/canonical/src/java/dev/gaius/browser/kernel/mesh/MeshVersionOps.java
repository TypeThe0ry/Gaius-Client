package dev.gaius.browser.kernel.mesh;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.level.block.state.BlockState;

public interface MeshVersionOps {
    MeshVersionOps INSTANCE = new dev.gaius.browser.kernel.mesh.Blaze3dMeshOps();

    boolean lightPermeable(BlockState state);

    boolean blocksFluidFlow(BlockState state);

    int shadeFace(BakedQuad quad);

    void uploadUniform(RenderPass.UniformUploader uploader, GpuBufferSlice slice);
}
