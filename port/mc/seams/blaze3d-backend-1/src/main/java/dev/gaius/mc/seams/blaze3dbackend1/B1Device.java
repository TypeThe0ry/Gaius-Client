package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.DeviceType;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.HintsAndWorkarounds;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.gaius.gpu.webgpu.CoreCaps;
import dev.gaius.gpu.webgpu.CoreDevice;
import dev.gaius.gpu.webgpu.FanIndices;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The blaze3d device backend: a delegating adapter from vanilla 26.2's {@link GpuDeviceBackend}
 * to the WebGPU core's {@link CoreDevice}. Pipelines are compiled once per {@link RenderPipeline}
 * (vanilla's {@code pipelineCache}, by identity) through the core's GLSL front-end.
 */
public final class B1Device implements GpuDeviceBackend {
    private final CoreDevice core;
    private final ShaderSource defaultShaderSource;
    private final boolean frameYield;
    private final DeviceInfo deviceInfo;
    private final B1Encoder encoder;
    private final FanIndices fanIndices;
    private final Map<RenderPipeline, B1Pipeline> pipelines = new IdentityHashMap<>();

    /** frameYield: present() ends the browser task through the GLFW shim (the game, not harnesses). */
    B1Device(CoreDevice core, ShaderSource defaultShaderSource, boolean frameYield) {
        this.core = core;
        this.defaultShaderSource = defaultShaderSource;
        this.frameYield = frameYield;
        this.deviceInfo = deviceInfo(core);
        this.fanIndices = new FanIndices(core);
        this.encoder = new B1Encoder(core.encoder(), this);
    }

    public CoreDevice core() {
        return this.core;
    }

    /** The triangle fan index buffer of the device (TRIANGLE_FAN pipelines). */
    FanIndices fanIndices() {
        return this.fanIndices;
    }

    /** Triangle fans drawn as triangle lists so far (diagnostics). */
    public long fanDraws() {
        return this.fanIndices.fans();
    }

    private static DeviceInfo deviceInfo(CoreDevice core) {
        CoreCaps caps = core.caps();
        DeviceLimits limits = new DeviceLimits(
                16,
                caps.minUniformOffsetAlignment(),
                caps.maxTextureDimension2D(),
                caps.maxBufferSize(),
                0, // no multi-draw: vanilla draws separately
                caps.maxColorAttachments());
        DeviceFeatures features = features(core::supportsCapability);
        HintsAndWorkarounds hints = new HintsAndWorkarounds(false, false);
        // isZZeroToOne: vanilla 26.2 builds 0..1 projection matrices, WebGPU's clip range.
        return new DeviceInfo(caps.name(), caps.vendor(), caps.architecture(), true, "WebGPU", 1.0F, limits, features,
                caps.features(), hints, caps.fallbackAdapter() ? DeviceType.CPU : DeviceType.OTHER);
    }

    /**
     * The vanilla 26.2 feature set for the core's executable capabilities.  26.2 issues no indirect
     * draws of its own; drawIndirect still reports the single-record indexed path the core can run
     * (RenderPass.drawIndexedIndirect checks it first), and multiDrawIndirect stays off because this
     * seam's limits carry no indirect batch size.
     */
    static DeviceFeatures features(Predicate<String> supports) {
        return new DeviceFeatures(
                supports.test("shader-draw-parameters"),
                supports.test("multi-draw-direct-interleaved"),
                supports.test("multi-draw-direct-separate"),
                false, // multiDrawIndirect: no 26.2 caller, and drawCount > 1 is then rejected by vanilla
                supports.test("draw-indirect"),
                supports.test("non-zero-first-instance"),
                supports.test("persistent-mapping"));
    }

    @Override
    public GpuSurfaceBackend createSurface(long window) {
        return new B1Surface(this.core.createSurface(
                () -> window != 0L && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) != 0,
                this.frameYield ? () -> GLFW.glfwSwapBuffers(window) : null),
                this.frameYield ? GLFW::glfwSwapInterval : null);
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return this.encoder;
    }

    @Override
    public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter,
            FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        return new B1Sampler(addressModeU, addressModeV, minFilter, magFilter, maxLod,
                this.core.createSampler(B1Enums.ADDRESS_MODE.toCore(addressModeU), B1Enums.ADDRESS_MODE.toCore(addressModeV),
                        B1Enums.FILTER_MODE.toCore(minFilter), B1Enums.FILTER_MODE.toCore(magFilter), maxAnisotropy,
                        maxLod.isPresent() ? maxLod.getAsDouble() : -1.0));
    }

    @Override
    public GpuTexture createTexture(@Nullable Supplier<String> label, @GpuTexture.Usage int usage, GpuFormat format,
            int width, int height, int depthOrLayers, int mipLevels) {
        return this.createTexture(this.label(label), usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int width,
            int height, int depthOrLayers, int mipLevels) {
        return new B1Texture(usage, label, format, width, height, depthOrLayers, mipLevels,
                this.core.createTexture(label, B1Enums.textureUsage(usage), B1Enums.FORMAT.toCore(format), width, height,
                        depthOrLayers, mipLevels));
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        return this.createTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        B1Texture b1Texture = (B1Texture) texture;
        return new B1TextureView(b1Texture, baseMipLevel, mipLevels,
                this.core.createView(b1Texture.core(), baseMipLevel, mipLevels));
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
        return new B1Buffer(usage, size, this.core.createBuffer(this.label(label), B1Enums.bufferUsage(usage), size));
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
        int length = data.remaining();
        return new B1Buffer(usage, length, this.core.createBuffer(this.label(label), B1Enums.bufferUsage(usage), data));
    }

    private @Nullable String label(@Nullable Supplier<String> label) {
        return this.core.debug() && label != null ? label.get() : null;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return this.core.takeDebugMessages();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.core.debug();
    }

    /**
     * Compiles the pipeline now (D11: WebGPU compiles in the GPU process and a pipeline is usable
     * immediately) and caches it by identity, like vanilla. A pipeline the core cannot build is
     * cached as invalid; the reason is in the debug messages.
     */
    @Override
    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, @Nullable ShaderSource shaderSource) {
        B1Pipeline compiled = this.pipelines.get(pipeline);
        if (compiled == null) {
            ShaderSource source = shaderSource != null ? shaderSource : this.defaultShaderSource;
            compiled = new B1Pipeline(pipeline, this.core.compile(B1Pipelines.describe(pipeline, source)).finish());
            this.pipelines.put(pipeline, compiled);
        }
        return compiled;
    }

    /** The compiled pipeline for a render pass ({@code setPipeline}), compiled on first use. */
    B1Pipeline pipeline(RenderPipeline pipeline) {
        return (B1Pipeline) this.precompilePipeline(pipeline, null);
    }

    @Override
    public void clearPipelineCache() {
        for (B1Pipeline pipeline : this.pipelines.values()) {
            pipeline.close();
        }
        this.pipelines.clear();
    }

    @Override
    public void close() {
        this.clearPipelineCache();
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new GpuQueryPool() {
            @Override
            public int size() {
                return size;
            }

            @Override
            public OptionalLong getValue(int index) {
                return OptionalLong.empty();
            }

            @Override
            public OptionalLong[] getValues(int index, int count) {
                OptionalLong[] values = new OptionalLong[count];
                Arrays.fill(values, OptionalLong.empty());
                return values;
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public long getTimestampNow() {
        return System.nanoTime();
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }
}
