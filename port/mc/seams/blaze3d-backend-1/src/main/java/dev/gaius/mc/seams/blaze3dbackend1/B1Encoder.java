package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.gaius.gpu.webgpu.CoreEncoder;
import dev.gaius.gpu.webgpu.CorePass;
import dev.gaius.gpu.webgpu.CoreSlice;
import dev.gaius.gpu.webgpu.CoreTexture;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;

/**
 * The blaze3d command encoder over the core encoder. Vanilla's frontend refuses buffer and
 * texture commands while a render pass is open; write mappings committed inside a pass are split
 * by the core (see {@link CoreEncoder#writeBuffer}).
 */
final class B1Encoder implements CommandEncoderBackend {
    private final CoreEncoder core;
    private final B1Device device;
    private final B1TransientMemory transientMemory;
    private B1RenderPass renderPass;

    B1Encoder(CoreEncoder core, B1Device device) {
        this.core = core;
        this.device = device;
        this.transientMemory = new B1TransientMemory(core.transientMemory());
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
        GpuTextureView sizeSource = null;
        for (int i = 0; i < colors.size(); i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
            if (attachment == null) {
                this.core.passColor(i, null, false, 0.0F, 0.0F, 0.0F, 0.0F);
                continue;
            }
            sizeSource = sizeSource != null ? sizeSource : attachment.textureView();
            Vector4fc color = attachment.clearValue().orElse(null);
            this.core.passColor(i, ((B1TextureView) attachment.textureView()).core(), color != null,
                    color != null ? color.x() : 0.0F, color != null ? color.y() : 0.0F,
                    color != null ? color.z() : 0.0F, color != null ? color.w() : 0.0F);
        }
        RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
        if (depth != null) {
            sizeSource = sizeSource != null ? sizeSource : depth.textureView();
            OptionalDouble clear = depth.clearValue();
            this.core.passDepth(((B1TextureView) depth.textureView()).core(), clear.isPresent(), clear.orElse(0.0));
        }
        RenderPass.RenderArea area = descriptor.renderArea;
        CorePass pass = area != null
                ? this.core.beginPass(colors.size(), area.x(), area.y(), area.width(), area.height())
                : this.core.beginPass(colors.size(), 0, 0, sizeSource.getWidth(0), sizeSource.getHeight(0));
        // The core reuses one pass object per encoder, and so does the seam.
        if (this.renderPass == null || this.renderPass.core() != pass) {
            this.renderPass = new B1RenderPass(pass, this.device);
        }
        this.renderPass.begin();
        return this.renderPass;
    }

    @Override
    public void submitRenderPass() {
        this.core.endPass();
    }

    private static CoreTexture texture(GpuTexture texture) {
        return ((B1Texture) texture).core();
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
            double depth, int regionX, int regionY, int regionWidth, int regionHeight) {
        this.core.clearColorAndDepthRegion(texture(colorTexture), color.x(), color.y(), color.z(), color.w(),
                texture(depthTexture), depth, regionX, regionY, regionWidth, regionHeight, 0);
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double depth) {
        this.core.clearDepth(texture(depthTexture), depth);
    }

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        this.core.writeBuffer(B1Buffer.core(destination.buffer()), destination.offset(), data);
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        this.core.copyBuffer(B1Buffer.core(source.buffer()), source.offset(), B1Buffer.core(target.buffer()),
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
        // Vanilla's GlCommandEncoder: UNPACK_ROW_LENGTH rowLength, UNPACK_IMAGE_HEIGHT imageHeight,
        // the texels from (sourceX, sourceY) to (destX, destY) of mip level mipLevel, layer layer.
        this.core.copyBufferToTexture(
                new CoreSlice(B1Buffer.core(source.buffer()), source.offset(), source.length()), sourceX, sourceY,
                rowLength, imageHeight, texture(destination), destX, destY, width, height, mipLevel, layer);
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
            int mipLevel) {
        this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel),
                source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
            int mipLevel, int x, int y, int width, int height) {
        this.core.copyTextureToBuffer(texture(source), B1Buffer.core(destination), offset, callback, mipLevel, x, y,
                width, height);
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY,
            int sourceX, int sourceY, int width, int height) {
        this.core.copyTextureToTexture(texture(source), texture(destination), mipLevel, destX, destY, sourceX, sourceY,
                width, height);
    }

    /**
     * CPU-visible data is captured when it is queued, never read back from the GPU, so nothing a
     * fence guards in the native backends can still be in flight: the fence is always signalled.
     */
    @Override
    public GpuFence createFence() {
        return new GpuFence() {
            @Override
            public void close() {
            }

            @Override
            public boolean awaitCompletion(long timeoutNS) {
                return true;
            }
        };
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
