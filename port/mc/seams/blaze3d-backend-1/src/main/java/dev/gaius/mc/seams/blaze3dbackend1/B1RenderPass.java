package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.gaius.gpu.webgpu.CorePass;
import dev.gaius.gpu.webgpu.CorePipeline;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/**
 * The open blaze3d render pass over the core pass (one instance per encoder, reset by
 * {@link #begin()}).
 *
 * <p>blaze3d binds uniforms and samplers by name, independently of the pipeline (vanilla's Vulkan
 * pass keeps them in name maps and resolves them against the pipeline's layout at draw time).
 * This pass keeps the same name table and hands each binding to the core under the current
 * pipeline's slot ({@link CorePipeline#slotOf(String)}), on {@code setPipeline} for everything
 * bound so far and on every later bind for that one name.</p>
 *
 * <p>TRIANGLE_FAN pipelines are triangle lists in the core: their draws go through the device's
 * {@link dev.gaius.gpu.webgpu.FanIndices}, which binds its own index buffer, so vanilla's index
 * buffer is bound again before the next ordinary indexed draw. blaze3d draws fans either without
 * indices (the sky) or through the shared sequential index buffer
 * ({@code RenderSystem.getSequentialBuffer}, debug fans); a fan through any other index buffer is
 * skipped and reported in the debug messages.</p>
 */
final class B1RenderPass implements RenderPassBackend {
    private final CorePass core;
    private final B1Device device;
    /** Bound names in bind order, and per name a GpuBufferSlice or a TextureBinding. */
    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<Object> values = new ArrayList<>();
    private B1Pipeline pipeline;
    private CorePipeline corePipeline;
    private boolean drawable;
    private boolean fan;
    private int skippedDraws;
    /** vanilla's index buffer as last bound, and whether the fan indices replaced it in the core. */
    private @Nullable GpuBuffer indexBuffer;
    private IndexType indexType;
    private boolean indexReplaced;
    private int unconvertedFans;

    private record TextureBinding(B1TextureView view, B1Sampler sampler) {
    }

    B1RenderPass(CorePass core, B1Device device) {
        this.core = core;
        this.device = device;
    }

    CorePass core() {
        return this.core;
    }

    /** A new pass: no pipeline, nothing bound. */
    void begin() {
        this.names.clear();
        this.values.clear();
        this.pipeline = null;
        this.corePipeline = null;
        this.drawable = false;
        this.fan = false;
        this.indexBuffer = null;
        this.indexReplaced = false;
    }

    /** Draws skipped because the pipeline is unavailable on WebGPU. */
    int skippedDraws() {
        return this.skippedDraws;
    }

    /** Fan draws skipped because their index buffer was not the sequential one. */
    int unconvertedFans() {
        return this.unconvertedFans;
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
    }

    @Override
    public void popDebugGroup() {
    }

    /**
     * An unavailable pipeline (shaders the front-end or WebGPU refuses) skips its draws instead of
     * failing the frame (D11 disables only that pipeline; vanilla's GL backend does the same).
     */
    @Override
    public void setPipeline(RenderPipeline pipeline) {
        B1Pipeline compiled = this.device.pipeline(pipeline);
        this.pipeline = compiled;
        this.drawable = compiled.isValid();
        this.fan = compiled.fan();
        if (!this.drawable) {
            this.corePipeline = null;
            return;
        }
        CorePipeline next = compiled.core();
        this.core.setPipeline(next);
        if (next != this.corePipeline) {
            this.corePipeline = next;
            // A new pipeline starts without bindings in the core: hand over every name it uses.
            for (int i = 0; i < this.names.size(); i++) {
                this.apply(this.names.get(i), this.values.get(i));
            }
        }
    }

    private void bind(String name, @Nullable Object value) {
        int index = this.names.indexOf(name);
        if (value == null) {
            if (index >= 0) {
                this.names.remove(index);
                this.values.remove(index);
            }
        } else if (index >= 0) {
            this.values.set(index, value);
        } else {
            this.names.add(name);
            this.values.add(value);
        }
        if (this.corePipeline != null) {
            this.apply(name, value);
        }
    }

    private void apply(String name, @Nullable Object value) {
        int slot = this.corePipeline.slotOf(name);
        if (slot < 0) {
            return;
        }
        if (value instanceof GpuBufferSlice slice) {
            // Same buffer and length as before in this slot: only the dynamic offset moves (one
            // long-to-int conversion per draw instead of two; longs are slow on the JS target).
            boolean same = this.sameBuffer(slot, slice);
            if (!(same && this.core.moveBuffer(slot, (int) slice.offset()))) {
                this.core.setBuffer(slot, B1Buffer.core(slice.buffer()), (int) slice.offset(), (int) slice.length());
            }
        } else if (value instanceof TextureBinding texture) {
            this.core.setTexture(slot, texture.view().core(), texture.sampler().core());
        } else {
            this.core.clearUniform(slot);
        }
    }

    /** The last slice applied per slot, to only move the dynamic offset of the same buffer range. */
    private GpuBufferSlice[] lastSlices = new GpuBufferSlice[16];

    private boolean sameBuffer(int slot, GpuBufferSlice slice) {
        if (slot >= this.lastSlices.length) {
            this.lastSlices = java.util.Arrays.copyOf(this.lastSlices, slot + 16);
        }
        GpuBufferSlice last = this.lastSlices[slot];
        this.lastSlices[slot] = slice;
        return last != null && last.buffer() == slice.buffer() && last.length() == slice.length();
    }

    @Override
    public void bindTexture(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
        if ((textureView == null) != (sampler == null)) {
            throw new IllegalArgumentException("A texture binding needs both a view and a sampler");
        }
        this.bind(name, textureView == null ? null
                : new TextureBinding((B1TextureView) textureView, (B1Sampler) sampler));
    }

    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        this.bind(name, buffer.slice());
    }

    @Override
    public void setUniform(String name, GpuBufferSlice slice) {
        this.bind(name, slice);
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
            this.core.setVertexBuffer(slot, B1Buffer.core(vertexBuffer.buffer()), (int) vertexBuffer.offset(),
                    (int) vertexBuffer.length());
        }
    }

    @Override
    public void setIndexBuffer(@Nullable GpuBuffer indexBuffer, IndexType indexType) {
        if (indexBuffer != null) {
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
            this.indexReplaced = false;
            this.core.setIndexBuffer(B1Buffer.core(indexBuffer), B1Enums.INDEX_TYPE.toCore(indexType), 0);
        }
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        if (!this.drawable) {
            this.skippedDraws++;
            return;
        }
        if (this.fan) {
            this.drawSequentialFan(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
            return;
        }
        if (this.indexReplaced && this.indexBuffer != null) {
            this.indexReplaced = false;
            this.core.setIndexBuffer(B1Buffer.core(this.indexBuffer), B1Enums.INDEX_TYPE.toCore(this.indexType), 0);
        }
        this.core.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    /**
     * An indexed fan: through the sequential index buffer, index {@code firstIndex + j} is vertex
     * {@code vertexOffset + firstIndex + j}, so the fan starts at that vertex.
     */
    private void drawSequentialFan(int indexCount, int instanceCount, int firstIndex, int vertexOffset,
            int firstInstance) {
        GpuBuffer sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.TRIANGLE_FAN).getBuffer(0);
        if (this.indexBuffer == null || this.indexBuffer != sequential) {
            this.skippedDraws++;
            if (this.unconvertedFans++ == 0) {
                this.device.core().debugMessage("pipeline " + this.pipeline.info().getLocation()
                        + ": a triangle fan through a non-sequential index buffer is not drawn on WebGPU");
            }
            return;
        }
        this.device.fanIndices().draw(this.core, indexCount, instanceCount, vertexOffset + firstIndex, firstInstance);
        this.indexReplaced = true;
    }

    /** Vanilla's Vulkan loop: per draw the uploader, the buffers and one indexed draw. */
    @Override
    public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer,
            @Nullable IndexType defaultIndexType, Collection<String> dynamicUniforms, T userData) {
        for (RenderPass.Draw<T> draw : draws) {
            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(userData, this::setUniform);
            }
            this.setIndexBuffer(draw.indexBuffer() != null ? draw.indexBuffer() : defaultIndexBuffer,
                    draw.indexType() != null ? draw.indexType() : defaultIndexType);
            this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
            this.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (!this.drawable) {
            this.skippedDraws++;
            return;
        }
        if (this.fan) {
            this.device.fanIndices().draw(this.core, vertexCount, instanceCount, firstVertex, firstInstance);
            this.indexReplaced = true;
            return;
        }
        this.core.draw(vertexCount, instanceCount, firstVertex, firstInstance);
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
        if (!this.drawable) {
            this.skippedDraws += drawCount;
            return;
        }
        if (this.fan) {
            // The fan conversion needs the counts on the CPU; indirect records live on the GPU.
            this.skippedDraws += drawCount;
            if (this.unconvertedFans++ == 0) {
                this.device.core().debugMessage("pipeline " + this.pipeline.info().getLocation()
                        + ": an indirect triangle fan is not drawn on WebGPU");
            }
            return;
        }
        if (this.indexReplaced && this.indexBuffer != null) {
            this.indexReplaced = false;
            this.core.setIndexBuffer(B1Buffer.core(this.indexBuffer), B1Enums.INDEX_TYPE.toCore(this.indexType), 0);
        }
        this.core.drawIndexedIndirect(B1Buffer.core(commands.buffer()), (int) commands.offset(), drawCount);
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

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
