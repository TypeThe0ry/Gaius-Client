package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.gaius.gpu.webgpu.CorePass;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/** The open renderpearl render pass over the core pass (one instance per encoder). */
final class RpRenderPass implements RenderPassBackend {
    private final CorePass core;

    RpRenderPass(CorePass core) {
        this.core = core;
    }

    CorePass core() {
        return this.core;
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
    }

    @Override
    public void popDebugGroup() {
    }

    @Override
    public void setPipeline(BackendRenderPipeline pipeline) {
        if (!(pipeline instanceof RpPipeline rpPipeline)) {
            throw new IllegalArgumentException("Pipeline must be a WebGPU pipeline");
        }
        this.core.setPipeline(rpPipeline.core());
    }

    /**
     * The last buffer slice bound per uniform index. A slice of the same buffer with the same
     * length only moves the dynamic offset: one long-to-int conversion per draw instead of two
     * (every long operation allocates on the TeaVM JavaScript target).
     */
    private GpuBufferSlice[] lastSlices = new GpuBufferSlice[16];

    @Override
    public void setUniform(int index, @Nullable Object value) {
        if (index >= this.lastSlices.length) {
            this.lastSlices = java.util.Arrays.copyOf(this.lastSlices, index + 16);
        }
        if (value instanceof GpuBufferSlice slice) {
            GpuBufferSlice last = this.lastSlices[index];
            this.lastSlices[index] = slice;
            if (last != null && last.buffer() == slice.buffer() && last.length() == slice.length()
                    && this.core.moveBuffer(index, (int) slice.offset())) {
                return;
            }
            this.core.setBuffer(index, RpBuffer.core(slice.buffer()), (int) slice.offset(), (int) slice.length());
        } else {
            this.lastSlices[index] = null;
            if (value instanceof TextureViewAndSampler pair) {
                this.core.setTexture(index, ((RpTextureView) pair.view()).core(), ((RpSampler) pair.sampler()).core());
            } else {
                this.core.clearUniform(index);
            }
        }
    }

    @Override
    public void pushConstants(ByteBuffer value) {
        throw new UnsupportedOperationException("Push constants are not used by vanilla and not implemented for WebGPU");
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        this.core.enableScissor(x, y, width, height);
    }

    @Override
    public void disableScissor() {
        this.core.disableScissor();
    }

    @Override
    public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
        if (vertexBuffer != null) {
            this.core.setVertexBuffer(slot, RpBuffer.core(vertexBuffer.buffer()), (int) vertexBuffer.offset(),
                    (int) vertexBuffer.length());
        }
    }

    @Override
    public void setIndexBuffer(@Nullable GpuBuffer indexBuffer, IndexType indexType) {
        if (indexBuffer != null) {
            this.core.setIndexBuffer(RpBuffer.core(indexBuffer), RpEnums.INDEX_TYPE.toCore(indexType), 0);
        }
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        this.core.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("WebGPU does not support the multiDrawDirectInterleaved device feature");
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets,
            int drawCount) {
        throw new UnsupportedOperationException("WebGPU does not support the multiDrawDirectSeparate device feature");
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        this.core.drawIndexedIndirect(RpBuffer.core(commands.buffer()), (int) commands.offset(), drawCount);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        this.core.draw(vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("WebGPU does not support the multiDrawDirectInterleaved device feature");
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        throw new UnsupportedOperationException("WebGPU does not support the multiDrawDirectSeparate device feature");
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        throw new UnsupportedOperationException("Indirect draws are not enabled on the WebGPU backend yet");
    }

    /** Timestamps are not recorded on WebGPU (the pool reports no values); counted. */
    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        this.core.encoder().timestampWritten();
    }
}
