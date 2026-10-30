package dev.gaius.mc.seams.blaze3ddirect0;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuQuery;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.gaius.gpu.webgpu.CoreBuffer;
import dev.gaius.gpu.webgpu.CoreEncoder;
import dev.gaius.gpu.webgpu.CorePass;
import dev.gaius.gpu.webgpu.CoreSurface;
import dev.gaius.gpu.webgpu.CoreTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryUtil;

/**
 * The 1.21.11 command encoder with the mini-frontend 26.2 moved into vanilla's own
 * {@code CommandEncoder}: render pass state, ARGB clear colours, NativeImage uploads, write
 * mappings at encoder level, presentation and the submit policy.
 *
 * <p>Submits: 1.21.11's API has no submit (GL executes as it goes). The core's command encoder is
 * submitted when a frame is presented ({@link #presentTexture}), the only point vanilla calls once
 * per frame; buffer writes and mappings are command-ordered inside it, texture writes are
 * queue-ordered (vanilla only appends texels nothing earlier in the frame samples, as on 26.3).</p>
 */
final class D0Encoder implements CommandEncoder {
    private final CoreEncoder core;
    private final D0Device device;
    private D0RenderPass renderPass;
    private boolean inRenderPass;
    private CoreSurface surface;
    private int surfaceWidth;
    private int surfaceHeight;

    D0Encoder(CoreEncoder core, D0Device device) {
        this.core = core;
        this.device = device;
    }

    private void requireNoPass() {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        }
    }

    private static CoreTexture texture(GpuTexture texture) {
        return ((D0Texture) texture).core();
    }

    private static float channel(int argb, int shift) {
        return ((argb >>> shift) & 0xFF) / 255.0F;
    }

    // ---- render passes ------------------------------------------------------------------------

    @Override
    public RenderPass createRenderPass(Supplier<String> label, GpuTextureView colorTexture, OptionalInt clearColor) {
        return this.createRenderPass(label, colorTexture, clearColor, null, OptionalDouble.empty());
    }

    @Override
    public RenderPass createRenderPass(Supplier<String> label, GpuTextureView colorTexture, OptionalInt clearColor,
            GpuTextureView depthTexture, OptionalDouble clearDepth) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        checkAttachment(colorTexture, "Color");
        if (depthTexture != null) {
            checkAttachment(depthTexture, "Depth");
        }
        int argb = clearColor.orElse(0);
        this.core.passColor(0, ((D0TextureView) colorTexture).core(), clearColor.isPresent(), channel(argb, 16),
                channel(argb, 8), channel(argb, 0), channel(argb, 24));
        if (depthTexture != null) {
            this.core.passDepth(((D0TextureView) depthTexture).core(), clearDepth.isPresent(), clearDepth.orElse(0.0));
        }
        CorePass pass = this.core.beginPass(1, 0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0));
        if (this.renderPass == null || this.renderPass.core() != pass) {
            this.renderPass = new D0RenderPass(pass, this.device, this);
        }
        this.renderPass.begin();
        this.inRenderPass = true;
        return this.renderPass;
    }

    private static void checkAttachment(GpuTextureView view, String what) {
        if (view.isClosed()) {
            throw new IllegalStateException(what + " texture is closed");
        }
        if ((view.texture().usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
            throw new IllegalStateException(what + " texture must have USAGE_RENDER_ATTACHMENT");
        }
        if (view.texture().getDepthOrLayers() > 1) {
            throw new UnsupportedOperationException("Textures with multiple depths or layers are not yet supported as an attachment");
        }
    }

    /** Called by the pass's {@code close()}. */
    void finishRenderPass() {
        this.core.endPass();
        this.inRenderPass = false;
    }

    // ---- clears -------------------------------------------------------------------------------

    @Override
    public void clearColorTexture(GpuTexture texture, int argb) {
        this.requireNoPass();
        this.core.clearColor(texture(texture), channel(argb, 16), channel(argb, 8), channel(argb, 0), channel(argb, 24));
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int argb, GpuTexture depthTexture, double depth) {
        this.requireNoPass();
        this.core.clearColorAndDepth(texture(colorTexture), channel(argb, 16), channel(argb, 8), channel(argb, 0),
                channel(argb, 24), texture(depthTexture), depth);
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, int argb, GpuTexture depthTexture, double depth,
            int regionX, int regionY, int regionWidth, int regionHeight) {
        this.requireNoPass();
        this.core.clearColorAndDepthRegion(texture(colorTexture), channel(argb, 16), channel(argb, 8), channel(argb, 0),
                channel(argb, 24), texture(depthTexture), depth, regionX, regionY, regionWidth, regionHeight, 0);
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double depth) {
        this.requireNoPass();
        this.core.clearDepth(texture(depthTexture), depth);
    }

    // ---- buffers ------------------------------------------------------------------------------

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        this.requireNoPass();
        if (data.remaining() > destination.length()) {
            throw new IllegalArgumentException("Cannot write more data than the slice allows");
        }
        this.core.writeBuffer(D0Buffer.core(destination.buffer()), destination.offset(), data);
    }

    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBuffer buffer, boolean read, boolean write) {
        return this.mapBuffer(buffer.slice(), read, write);
    }

    /**
     * Write mappings are CPU scratch committed in command order on close (26.2 and 26.3 map
     * through the buffer); read mappings need the core's readback (M3).
     */
    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBufferSlice slice, boolean read, boolean write) {
        if (read) {
            throw new UnsupportedOperationException("WebGPU read mapping is not implemented yet");
        }
        CoreBuffer.MappedRange range = D0Buffer.core(slice.buffer()).mapWrite(slice.offset(), slice.length());
        return new GpuBuffer.MappedView() {
            @Override
            public ByteBuffer data() {
                return range.data();
            }

            @Override
            public void close() {
                range.commit();
            }
        };
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        this.requireNoPass();
        this.core.copyBuffer(D0Buffer.core(source.buffer()), source.offset(), D0Buffer.core(target.buffer()),
                target.offset(), source.length());
    }

    // ---- textures -----------------------------------------------------------------------------

    @Override
    public void writeToTexture(GpuTexture destination, NativeImage image) {
        this.writeToTexture(destination, image, 0, 0, 0, 0, image.getWidth(), image.getHeight(), 0, 0);
    }

    /**
     * A region of a NativeImage: its rows packed into a scratch buffer (WebGPU takes no row length
     * or skip as GL's unpack state does), then one queue write. An image with other channels than
     * the texture is expanded the way GL unpacks it ({@link #expand}).
     */
    @Override
    public void writeToTexture(GpuTexture destination, NativeImage image, int mipLevel, int depthOrLayer, int destX,
            int destY, int width, int height, int sourceX, int sourceY) {
        this.requireNoPass();
        checkWrite(destination, mipLevel, destX, destY, width, height);
        if (width == 0 || height == 0) {
            return;
        }
        NativeImage.Format format = image.format();
        int pixel = format.components();
        int imageRow = image.getWidth() * pixel;
        ByteBuffer pixels = MemoryUtil.memByteBuffer(image.getPointer(), imageRow * image.getHeight());
        this.writeRows(destination, pixels, imageRow, format, mipLevel, depthOrLayer, destX, destY, width, height,
                sourceX, sourceY);
    }

    @Override
    public void writeToTexture(GpuTexture destination, ByteBuffer source, NativeImage.Format format, int mipLevel,
            int depthOrLayer, int destX, int destY, int width, int height) {
        this.requireNoPass();
        checkWrite(destination, mipLevel, destX, destY, width, height);
        if (width == 0 || height == 0) {
            return;
        }
        if (format.components() == destination.getFormat().pixelSize()) {
            this.core.writeTexture(texture(destination), source, mipLevel, depthOrLayer, destX, destY, width, height);
            return;
        }
        ByteBuffer pixels = source.slice().order(ByteOrder.nativeOrder());
        this.writeRows(destination, pixels, width * format.components(), format, mipLevel, depthOrLayer, destX, destY,
                width, height, 0, 0);
    }

    private void writeRows(GpuTexture destination, ByteBuffer pixels, int sourceRow, NativeImage.Format format,
            int mipLevel, int layer, int destX, int destY, int width, int height, int sourceX, int sourceY) {
        int pixel = format.components();
        TextureFormat target = destination.getFormat();
        ByteBuffer packed;
        if (pixel != target.pixelSize()) {
            packed = expand(pixels, sourceRow, pixel, target, width, height, sourceX, sourceY);
        } else if (sourceX == 0 && sourceY == 0 && width * pixel == sourceRow) {
            packed = pixels.duplicate();
            packed.limit(sourceRow * height).position(0);
        } else {
            int row = width * pixel;
            packed = ByteBuffer.allocateDirect(row * height).order(ByteOrder.nativeOrder());
            for (int y = 0; y < height; y++) {
                ByteBuffer line = pixels.duplicate();
                int start = (sourceY + y) * sourceRow + sourceX * pixel;
                line.limit(start + row).position(start);
                packed.put(line);
            }
            packed.flip();
        }
        this.core.writeTexture(texture(destination), packed, mipLevel, layer, destX, destY, width, height);
    }

    /**
     * Texels of {@code components} channels in the texture's channels, as GL unpacks them:
     * 1.21.11's GL backend uploads a NativeImage with the format {@code GlConst.toGl} gives it
     * (LUMINANCE as {@code GL_RED}, LUMINANCE_ALPHA as {@code GL_RG}, RGB as {@code GL_RGB}), and
     * GL fills a missing green or blue with 0 and a missing alpha with 1; a single-channel texture
     * keeps the first channel. Only {@code RGBA8} and {@code RED8} take such uploads (the integer
     * and depth formats need GL formats a NativeImage never has).
     */
    static ByteBuffer expand(ByteBuffer pixels, int sourceRow, int components, TextureFormat target, int width,
            int height, int sourceX, int sourceY) {
        int out = target.pixelSize();
        if (target != TextureFormat.RGBA8 && target != TextureFormat.RED8) {
            throw new IllegalArgumentException("Cannot upload " + components + "-channel texels into " + target);
        }
        ByteBuffer packed = ByteBuffer.allocateDirect(width * height * out).order(ByteOrder.nativeOrder());
        for (int y = 0; y < height; y++) {
            int at = (sourceY + y) * sourceRow + sourceX * components;
            for (int x = 0; x < width; x++, at += components) {
                byte red = pixels.get(at);
                if (out == 1) {
                    packed.put(red);
                    continue;
                }
                packed.put(red);
                packed.put(components >= 2 ? pixels.get(at + 1) : 0);
                packed.put(components >= 3 ? pixels.get(at + 2) : 0);
                packed.put(components >= 4 ? pixels.get(at + 3) : (byte) 0xFF);
            }
        }
        packed.flip();
        return packed;
    }

    private static void checkWrite(GpuTexture destination, int mipLevel, int x, int y, int width, int height) {
        if (destination.isClosed()) {
            throw new IllegalStateException("Destination texture is closed");
        }
        if ((destination.usage() & GpuTexture.USAGE_COPY_DST) == 0) {
            throw new IllegalStateException("Color texture must have USAGE_COPY_DST to be a destination for a write");
        }
        if (mipLevel < 0 || mipLevel >= destination.getMipLevels()) {
            throw new IllegalArgumentException("Invalid mipLevel " + mipLevel);
        }
        if (x < 0 || y < 0 || width < 0 || height < 0 || width > destination.getWidth(mipLevel) - x
                || height > destination.getHeight(mipLevel) - y) {
            throw new IllegalArgumentException("Dest texture region out of range");
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
            int mipLevel) {
        this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel),
                source.getHeight(mipLevel));
    }

    /** Screenshots and readback: the core's copy-to-buffer with a completion callback (M3). */
    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
            int mipLevel, int x, int y, int width, int height) {
        this.requireNoPass();
        this.core.copyTextureToBuffer(texture(source), D0Buffer.core(destination), offset, callback, mipLevel, x, y,
                width, height);
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY,
            int sourceX, int sourceY, int width, int height) {
        this.requireNoPass();
        this.core.copyTextureToTexture(texture(source), texture(destination), mipLevel, destX, destY, sourceX, sourceY,
                width, height);
    }

    // ---- presentation -------------------------------------------------------------------------

    /**
     * The frame's end: the canvas sized to the view, the view blitted (flipped once, GL row
     * order), the frame submitted. The canvas presents when the browser task ends, which
     * {@code RenderSystem.flipFrame}'s {@code glfwSwapBuffers} (the GLFW shim's frame yield) brings
     * about right after.
     */
    @Override
    public void presentTexture(GpuTextureView view) {
        this.requireNoPass();
        if (!view.texture().getFormat().hasColorAspect()) {
            throw new IllegalStateException("Cannot present a non-color texture!");
        }
        if ((view.texture().usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
            throw new IllegalStateException("Color texture must have USAGE_RENDER_ATTACHMENT to presented to the screen");
        }
        if (this.surface == null) {
            this.surface = this.device.core().createSurface(() -> false, null);
        }
        int width = view.getWidth(0);
        int height = view.getHeight(0);
        if (width != this.surfaceWidth || height != this.surfaceHeight) {
            if (!this.surface.configure(width, height)) {
                throw new IllegalStateException("The WebGPU canvas context is not available");
            }
            this.surfaceWidth = width;
            this.surfaceHeight = height;
        }
        if (this.surface.acquire()) {
            this.surface.blit(((D0TextureView) view).core());
        }
        this.core.submit();
        this.surface.present();
    }

    /**
     * CPU-visible data is captured when it is queued, never read back from the GPU, so nothing a
     * fence guards in GL can still be in flight: the fence is always signalled.
     */
    @Override
    public GpuFence createFence() {
        return new GpuFence() {
            @Override
            public void close() {
            }

            @Override
            public boolean awaitCompletion(long timeoutNanos) {
                return true;
            }
        };
    }

    /** GPU timer queries ({@code TimerQuery}, the debug frame-time graph): timestamp queries (M5). */
    @Override
    public GpuQuery timerQueryBegin() {
        return new GpuQuery() {
            @Override
            public OptionalLong getValue() {
                return OptionalLong.empty();
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public void timerQueryEnd(GpuQuery query) {
    }
}
