package dev.gaius.mc.seams.blaze3ddirect0;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.gaius.gpu.webgpu.CorePass;
import dev.gaius.gpu.webgpu.CorePipeline;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The 1.21.11 render pass over the core pass (one instance per encoder, reset by
 * {@link #begin()}); {@code close()} ends the pass, as in vanilla's GL pass. Uniforms and
 * samplers bind by name, kept per pass and resolved through the current pipeline's slots like
 * the 26.2 seam's pass. An unavailable pipeline skips its draws (D11).
 *
 * <p>1.21.11's draw calls: {@code drawIndexed(baseVertex, firstIndex, indexCount, instanceCount)},
 * {@code draw(firstVertex, vertexCount)}; vertex buffers are bound whole.</p>
 *
 * <p>TRIANGLE_FAN pipelines are triangle lists in the core: their draws go through the device's
 * {@link dev.gaius.gpu.webgpu.FanIndices}, which binds its own index buffer, so vanilla's index
 * buffer is bound again before the next ordinary indexed draw. 1.21.11 draws fans without indices
 * (the sky, the sunrise) or through the shared sequential index buffer
 * ({@code RenderSystem.getSequentialBuffer}, immediate-mode fans); a fan through any other index
 * buffer is skipped and reported in the debug messages.</p>
 */
final class D0RenderPass implements RenderPass {
    private final CorePass core;
    private final D0Device device;
    private final D0Encoder encoder;
    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<Object> values = new ArrayList<>();
    private D0Pipeline pipeline;
    private CorePipeline corePipeline;
    private boolean drawable;
    private boolean fan;
    private boolean closed;
    private boolean indexed;
    /** vanilla's index buffer as last bound, and whether the fan indices replaced it in the core. */
    private @Nullable GpuBuffer indexBuffer;
    private VertexFormat.IndexType indexType;
    private boolean indexReplaced;
    private int unconvertedFans;

    private record TextureBinding(D0TextureView view, D0Sampler sampler) {
    }

    D0RenderPass(CorePass core, D0Device device, D0Encoder encoder) {
        this.core = core;
        this.device = device;
        this.encoder = encoder;
    }

    CorePass core() {
        return this.core;
    }

    void begin() {
        this.names.clear();
        this.values.clear();
        this.pipeline = null;
        this.corePipeline = null;
        this.drawable = false;
        this.fan = false;
        this.closed = false;
        this.indexed = false;
        this.indexBuffer = null;
        this.indexReplaced = false;
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Can't use a closed render pass");
        }
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
    }

    @Override
    public void popDebugGroup() {
    }

    @Override
    public void setPipeline(RenderPipeline pipeline) {
        this.requireOpen();
        D0Pipeline compiled = this.device.pipeline(pipeline);
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
            for (int i = 0; i < this.names.size(); i++) {
                this.apply(this.names.get(i), this.values.get(i));
            }
        }
    }

    private void bind(String name, @Nullable Object value) {
        this.requireOpen();
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
            this.core.setBuffer(slot, D0Buffer.core(slice.buffer()), (int) slice.offset(), (int) slice.length());
        } else if (value instanceof TextureBinding texture) {
            this.core.setTexture(slot, texture.view().core(), texture.sampler().core());
        } else {
            this.core.clearUniform(slot);
        }
    }

    @Override
    public void bindTexture(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
        this.bind(name, textureView == null || sampler == null ? null
                : new TextureBinding((D0TextureView) textureView, (D0Sampler) sampler));
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
        this.requireOpen();
        this.core.enableScissor(x, y, width, height);
    }

    @Override
    public void disableScissor() {
        this.requireOpen();
        this.core.disableScissor();
    }

    @Override
    public void setVertexBuffer(int slot, GpuBuffer buffer) {
        this.requireOpen();
        this.core.setVertexBuffer(slot, D0Buffer.core(buffer), 0, (int) buffer.size());
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, VertexFormat.IndexType type) {
        this.requireOpen();
        this.core.setIndexBuffer(D0Buffer.core(buffer), D0Enums.INDEX_TYPE.toCore(type), 0);
        this.indexed = true;
        this.indexBuffer = buffer;
        this.indexType = type;
        this.indexReplaced = false;
    }

    @Override
    public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        this.requireOpen();
        if (!this.indexed) {
            throw new IllegalStateException("Missing index buffer");
        }
        if (!this.drawable) {
            return;
        }
        if (this.fan) {
            this.drawSequentialFan(baseVertex, firstIndex, indexCount, instanceCount);
            return;
        }
        if (this.indexReplaced) {
            this.indexReplaced = false;
            this.core.setIndexBuffer(D0Buffer.core(this.indexBuffer), D0Enums.INDEX_TYPE.toCore(this.indexType), 0);
        }
        this.core.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, 0);
    }

    /**
     * An indexed fan: through the sequential index buffer, index {@code firstIndex + j} is vertex
     * {@code baseVertex + firstIndex + j}, so the fan starts at that vertex.
     */
    private void drawSequentialFan(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        GpuBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.TRIANGLE_FAN).getBuffer(0);
        if (this.indexBuffer != sequential) {
            if (this.unconvertedFans++ == 0) {
                this.device.core().debugMessage("pipeline " + this.pipeline.info().getLocation()
                        + ": a triangle fan through a non-sequential index buffer is not drawn on WebGPU");
            }
            return;
        }
        this.device.fanIndices().draw(this.core, indexCount, instanceCount, baseVertex + firstIndex, 0);
        this.indexReplaced = true;
    }

    /** GL's executeDrawMultiple: per draw the uploader, the buffers and one indexed draw. */
    @Override
    public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer,
            VertexFormat.@Nullable IndexType defaultIndexType, Collection<String> dynamicUniforms, T userData) {
        for (RenderPass.Draw<T> draw : draws) {
            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(userData, this::setUniform);
            }
            this.setIndexBuffer(draw.indexBuffer() != null ? draw.indexBuffer() : defaultIndexBuffer,
                    draw.indexType() != null ? draw.indexType() : defaultIndexType);
            this.setVertexBuffer(draw.slot(), draw.vertexBuffer());
            this.drawIndexed(0, draw.firstIndex(), draw.indexCount(), 1);
        }
    }

    @Override
    public void draw(int firstVertex, int vertexCount) {
        this.requireOpen();
        if (!this.drawable) {
            return;
        }
        if (this.fan) {
            this.device.fanIndices().draw(this.core, vertexCount, 1, firstVertex, 0);
            this.indexReplaced = this.indexBuffer != null;
            return;
        }
        this.core.draw(vertexCount, 1, firstVertex, 0);
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.encoder.finishRenderPass();
        }
    }
}
