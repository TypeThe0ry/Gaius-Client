package dev.gaius.mc.seams.blaze3ddirect0;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.gaius.gpu.webgpu.CoreCaps;
import dev.gaius.gpu.webgpu.CoreDevice;
import dev.gaius.gpu.webgpu.FanIndices;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The 1.21.11 device: vanilla's {@link GpuDevice} implemented directly over the WebGPU core.
 * 1.21.11 has no frontend between the game and the device (26.2 added {@code GpuDevice} as a
 * frontend class over {@code GpuDeviceBackend}), so this class and {@link D0Encoder} and
 * {@link D0RenderPass} also do the frontend's share: argument checks, the device information
 * getters, the pipeline cache.
 */
public final class D0Device implements GpuDevice {
    private final CoreDevice core;
    private final ShaderSource defaultShaderSource;
    private final long window;
    private final D0Encoder encoder;
    private final FanIndices fanIndices;
    private final Map<RenderPipeline, D0Pipeline> pipelines = new IdentityHashMap<>();

    D0Device(CoreDevice core, ShaderSource defaultShaderSource, long window) {
        this.core = core;
        this.defaultShaderSource = defaultShaderSource;
        this.window = window;
        this.fanIndices = new FanIndices(core);
        this.encoder = new D0Encoder(core.encoder(), this);
    }

    /** The triangle fan index buffer of the device (TRIANGLE_FAN pipelines). */
    FanIndices fanIndices() {
        return this.fanIndices;
    }

    /** Triangle fans drawn as triangle lists so far (diagnostics). */
    public long fanDraws() {
        return this.fanIndices.fans();
    }

    public CoreDevice core() {
        return this.core;
    }

    long window() {
        return this.window;
    }

    @Override
    public CommandEncoder createCommandEncoder() {
        return this.encoder;
    }

    @Override
    public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter,
            FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        if (maxAnisotropy < 1 || maxAnisotropy > this.getMaxSupportedAnisotropy()) {
            throw new IllegalArgumentException("maxAnisotropy out of range: " + maxAnisotropy);
        }
        return new D0Sampler(addressModeU, addressModeV, minFilter, magFilter, maxLod,
                this.core.createSampler(D0Enums.ADDRESS_MODE.toCore(addressModeU), D0Enums.ADDRESS_MODE.toCore(addressModeV),
                        D0Enums.FILTER_MODE.toCore(minFilter), D0Enums.FILTER_MODE.toCore(magFilter), maxAnisotropy,
                        maxLod.isPresent() ? maxLod.getAsDouble() : -1.0));
    }

    @Override
    public GpuTexture createTexture(@Nullable Supplier<String> label, @GpuTexture.Usage int usage, TextureFormat format,
            int width, int height, int depthOrLayers, int mipLevels) {
        return this.createTexture(this.label(label), usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, TextureFormat format, int width,
            int height, int depthOrLayers, int mipLevels) {
        // The checks vanilla 26.2 moved into its GpuDevice frontend.
        if (mipLevels < 1) {
            throw new IllegalArgumentException("mipLevels must be at least 1");
        }
        if (depthOrLayers < 1) {
            throw new IllegalArgumentException("depthOrLayers must be at least 1");
        }
        boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
        if (cube ? depthOrLayers != 6 || width != height : depthOrLayers > 1) {
            throw new UnsupportedOperationException("Array or 3D textures are not yet supported");
        }
        return new D0Texture(usage, label, format, width, height, depthOrLayers, mipLevels,
                this.core.createTexture(label, D0Enums.textureUsage(usage), D0Enums.TEXTURE_FORMAT.toCore(format), width,
                        height, depthOrLayers, D0Texture.physicalMipLevels(width, height, mipLevels)));
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        return this.createTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        if (texture.isClosed()) {
            throw new IllegalArgumentException("Can't create texture view with closed texture");
        }
        if (baseMipLevel < 0 || mipLevels < 1 || baseMipLevel > texture.getMipLevels() - mipLevels) {
            throw new IllegalArgumentException("Texture view mips out of range");
        }
        D0Texture d0Texture = (D0Texture) texture;
        return new D0TextureView(d0Texture, baseMipLevel, mipLevels,
                this.core.createView(d0Texture.core(), baseMipLevel,
                        D0Texture.viewMipLevels(d0Texture.core().mipLevels(), baseMipLevel, mipLevels)));
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be greater than zero");
        }
        return new D0Buffer(usage, size, this.core.createBuffer(this.label(label), D0Enums.bufferUsage(usage), size));
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
        if (!data.hasRemaining()) {
            throw new IllegalArgumentException("Buffer source must not be empty");
        }
        int length = data.remaining();
        return new D0Buffer(usage, length, this.core.createBuffer(this.label(label), D0Enums.bufferUsage(usage), data));
    }

    private @Nullable String label(@Nullable Supplier<String> label) {
        return this.core.debug() && label != null ? label.get() : null;
    }

    // ---- device information (26.2 folds these into DeviceInfo) ----------------------------------

    @Override
    public String getImplementationInformation() {
        CoreCaps caps = this.core.caps();
        return "WebGPU " + caps.vendor() + " " + caps.architecture();
    }

    @Override
    public List<String> getLastDebugMessages() {
        return this.core.takeDebugMessages();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.core.debug();
    }

    @Override
    public String getVendor() {
        return this.core.caps().vendor();
    }

    @Override
    public String getBackendName() {
        return "WebGPU";
    }

    @Override
    public String getVersion() {
        return "WebGPU 1.0";
    }

    @Override
    public String getRenderer() {
        return this.core.caps().name();
    }

    @Override
    public int getMaxTextureSize() {
        return this.core.caps().maxTextureDimension2D();
    }

    @Override
    public int getUniformOffsetAlignment() {
        return this.core.caps().minUniformOffsetAlignment();
    }

    @Override
    public List<String> getEnabledExtensions() {
        return new ArrayList<>(this.core.caps().features());
    }

    @Override
    public int getMaxSupportedAnisotropy() {
        return 16;
    }

    // ---- pipelines ------------------------------------------------------------------------------

    /**
     * Compiles the pipeline now through the core's GLSL front-end (lenient link, GL depth range)
     * and caches it by identity; one the front-end or WebGPU refuses is cached as invalid.
     */
    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, @Nullable ShaderSource shaderSource) {
        D0Pipeline compiled = this.pipelines.get(pipeline);
        if (compiled == null) {
            ShaderSource source = shaderSource != null ? shaderSource : this.defaultShaderSource;
            compiled = new D0Pipeline(pipeline, D0Pipelines.compile(this.core, pipeline, source));
            this.pipelines.put(pipeline, compiled);
        }
        return compiled;
    }

    D0Pipeline pipeline(RenderPipeline pipeline) {
        return (D0Pipeline) this.precompilePipeline(pipeline, null);
    }

    @Override
    public void clearPipelineCache() {
        for (D0Pipeline pipeline : this.pipelines.values()) {
            pipeline.close();
        }
        this.pipelines.clear();
    }

    @Override
    public void close() {
        this.clearPipelineCache();
    }
}
