package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import dev.gaius.gpu.webgpu.CoreEncoder;
import dev.gaius.gpu.webgpu.CoreFence;
import dev.gaius.gpu.webgpu.CorePass;
import dev.gaius.gpu.webgpu.CoreSlice;
import dev.gaius.gpu.webgpu.CoreTexture;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;

/**
 * The renderpearl command encoder over the core encoder. Vanilla's frontend refuses buffer and
 * texture commands while a render pass is open; write mappings committed inside a pass are split
 * by the core (see {@link CoreEncoder#writeBuffer}).
 */
final class RpEncoder implements CommandEncoderBackend {
    private final CoreEncoder core;
    private final RpTransientMemory transientMemory;
    private RpRenderPass renderPass;

    RpEncoder(CoreEncoder core) {
        this.core = core;
        this.transientMemory = new RpTransientMemory(core.transientMemory());
    }

    CoreEncoder core() {
        return this.core;
    }

    @Override
    public void submit() {
        this.core.submit();
    }

    @Override
    public TransientMemory transientMemory() {
        return this.transientMemory;
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        for (int i = 0; i < colors.size(); i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
            if (attachment == null) {
                this.core.passColor(i, null, false, 0.0F, 0.0F, 0.0F, 0.0F);
                continue;
            }
            Vector4fc color = attachment.clearValue().orElse(null);
            this.core.passColor(i, ((RpTextureView) attachment.textureView()).core(), color != null,
                    color != null ? color.x() : 0.0F, color != null ? color.y() : 0.0F,
                    color != null ? color.z() : 0.0F, color != null ? color.w() : 0.0F);
        }
        RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
        if (depth != null) {
            OptionalDouble clear = depth.clearValue();
            this.core.passDepth(((RpTextureView) depth.textureView()).core(), clear.isPresent(), clear.orElse(0.0));
        }
        RenderPass.RenderArea area = descriptor.renderArea();
        CorePass pass = this.core.beginPass(colors.size(), area.x(), area.y(), area.width(), area.height());
        // The core reuses one pass object per encoder, and so does the seam.
        if (this.renderPass == null || this.renderPass.core() != pass) {
            this.renderPass = new RpRenderPass(pass);
        }
        return this.renderPass;
    }

    @Override
    public void submitRenderPass() {
        this.core.endPass();
    }

    private static CoreTexture texture(GpuTexture texture) {
        return ((RpTexture) texture).core();
    }

    @Override
    public void clearColorTexture(GpuTexture texture, Vector4fc color) {
        this.core.clearColor(texture(texture), color.x(), color.y(), color.z(), color.w());
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color, GpuTexture depthTexture,
            double depth) {
        this.core.clearColorAndDepth(texture(colorTexture), color.x(), color.y(), color.z(), color.w(),
                texture(depthTexture), depth);
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color, GpuTexture depthTexture,
            double depth, int regionX, int regionY, int regionWidth, int regionHeight, int mipLevel) {
        this.core.clearColorAndDepthRegion(texture(colorTexture), color.x(), color.y(), color.z(), color.w(),
                texture(depthTexture), depth, regionX, regionY, regionWidth, regionHeight, mipLevel);
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double depth) {
        this.core.clearDepth(texture(depthTexture), depth);
    }

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        this.core.writeBuffer(RpBuffer.core(destination.buffer()), destination.offset(), data);
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        this.core.copyBuffer(RpBuffer.core(source.buffer()), source.offset(), RpBuffer.core(target.buffer()),
                target.offset(), source.length());
    }

    @Override
    public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX,
            int destY, int width, int height) {
        this.core.writeTexture(texture(destination), source, mipLevel, depthOrLayer, destX, destY, width, height);
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int rowLength, int imageHeight,
            GpuTexture destination, int destX, int destY, int width, int height, int mipLevel, int layer) {
        this.core.copyBufferToTexture(new CoreSlice(RpBuffer.core(source.buffer()), source.offset(), source.length()),
                sourceX, sourceY, rowLength, imageHeight, texture(destination), destX, destY, width, height, mipLevel,
                layer);
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
            int mipLevel) {
        this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel),
                source.getHeight(mipLevel));
    }

    /**
     * Readback: the core copies the texels in command order and runs {@code callback} at a later
     * submit once they arrived, as vanilla's Vulkan backend runs it when the frame retires; a read
     * mapping of the destination then sees them ({@link RpBuffer#map}).
     */
    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
            int mipLevel, int x, int y, int width, int height) {
        this.core.copyTextureToBuffer(texture(source), RpBuffer.core(destination), offset, callback, mipLevel, x, y,
                width, height);
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY,
            int sourceX, int sourceY, int width, int height) {
        this.core.copyTextureToTexture(texture(source), texture(destination), mipLevel, destX, destY, sourceX, sourceY,
                width, height);
    }

    @Override
    public GpuFence createFence() {
        CoreFence fence = this.core.createFence();
        return new GpuFence() {
            @Override
            public void close() {
                fence.close();
            }

            @Override
            public boolean awaitCompletion(long timeoutNS) {
                return fence.isComplete();
            }
        };
    }

    /** Timestamps are not recorded on WebGPU (the pool reports no values); counted. */
    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        this.core.timestampWritten();
    }
}
