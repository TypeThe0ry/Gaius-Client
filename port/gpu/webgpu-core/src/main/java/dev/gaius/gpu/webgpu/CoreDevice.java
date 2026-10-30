package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The WebGPU device of the core: resources, the command encoder, pipelines and the surface. The
 * device itself is created by the glue before the game starts ({@code GaiusWebGpu.init}); this
 * class reads its capabilities and creates resources through handles.
 *
 * <p>The core API uses JDK types, Core enums and plain numbers only. A render seam
 * ({@code port/mc/seams/<generation>}) translates one vanilla render SPI onto it.</p>
 */
public final class CoreDevice {
    private static final String[] REPORTED_FEATURES = {
        "float32-blendable", "float32-filterable", "indirect-first-instance", "timestamp-query",
        "depth32float-stencil8", "rg11b10ufloat-renderable", "texture-compression-bc"
    };
    private static final String[] CAPABILITIES = {
        "wireframe-fill-mode", "shader-draw-parameters", "multi-draw-direct-interleaved",
        "multi-draw-direct-separate", "multi-draw-indirect", "draw-indirect",
        "non-zero-first-instance", "persistent-mapping"
    };

    private final boolean debug;
    private final CoreCaps caps;
    private final Set<String> capabilities;
    private final StagingRing ring;
    private final CoreEncoder encoder;
    private final List<String> debugMessages = new ArrayList<>();
    private final HashMap<String, String> layoutKeys = new HashMap<>();
    private long pipelines;
    private long pipelineNanos;

    private CoreDevice(boolean debug) {
        this.debug = debug;
        this.caps = readCaps();
        this.capabilities = readCapabilities();
        this.ring = new StagingRing();
        this.encoder = new CoreEncoder(this, this.ring);
    }

    /** Whether the glue has a ready device ({@code GaiusWebGpu.init} succeeded). */
    public static boolean isAvailable() {
        return WebGpuGlue.state() == 2;
    }

    /** Why the device is not available. */
    public static String failure() {
        return WebGpuGlue.failure();
    }

    /** Whether this device can still issue GPU work; becomes false when the browser reports loss. */
    public boolean isUsable() {
        return WebGpuGlue.deviceUsable();
    }

    /** The device; throws {@link IllegalStateException} when {@link #isAvailable()} is false. */
    public static CoreDevice create(boolean debug) {
        if (!isAvailable()) {
            throw new IllegalStateException("WebGPU is not available: " + failure());
        }
        return new CoreDevice(debug);
    }

    private static CoreCaps readCaps() {
        Set<String> features = new HashSet<>();
        for (String feature : REPORTED_FEATURES) {
            if (WebGpuGlue.hasFeature(feature)) {
                features.add(feature);
            }
        }
        return new CoreCaps(WebGpuGlue.adapterString("vendor"), WebGpuGlue.adapterString("architecture"),
                WebGpuGlue.adapterString("description"), "true".equals(WebGpuGlue.adapterString("isFallbackAdapter")),
                features, (int) WebGpuGlue.limit("minUniformBufferOffsetAlignment"),
                (int) WebGpuGlue.limit("maxTextureDimension2D"), (long) WebGpuGlue.limit("maxBufferSize"),
                (int) WebGpuGlue.limit("maxColorAttachments"), (int) WebGpuGlue.limit("maxUniformBufferBindingSize"));
    }

    private static Set<String> readCapabilities() {
        Set<String> capabilities = new HashSet<>();
        for (String capability : CAPABILITIES) {
            if (WebGpuGlue.hasCapability(capability)) {
                capabilities.add(capability);
            }
        }
        return consistentCapabilities(capabilities);
    }

    /**
     * Drops capability combinations the vanilla render passes reject: they refuse every indirect
     * draw without drawIndirect, so multi-draw-indirect never stands without draw-indirect, even
     * when an older glue reports it on its own.
     */
    static Set<String> consistentCapabilities(Set<String> capabilities) {
        if (!capabilities.contains("draw-indirect")) {
            capabilities.remove("multi-draw-indirect");
        }
        return capabilities;
    }

    public CoreCaps caps() {
        return this.caps;
    }

    /** Whether the glue and the active WebGPU device can execute the named render capability. */
    public boolean supportsCapability(String name) {
        return this.capabilities.contains(name);
    }

    public boolean debug() {
        return this.debug;
    }

    /** The single command encoder of the device. */
    public CoreEncoder encoder() {
        return this.encoder;
    }

    /** Records a debug message for the seam to report (bounded). */
    public void debugMessage(String message) {
        if (this.debugMessages.size() < 256) {
            this.debugMessages.add(message);
        }
    }

    /** The core's and the glue's debug messages since the last call. */
    public List<String> takeDebugMessages() {
        List<String> messages = new ArrayList<>(this.debugMessages);
        this.debugMessages.clear();
        for (String message = WebGpuGlue.takeMessage(); message != null; message = WebGpuGlue.takeMessage()) {
            messages.add(message);
        }
        return messages;
    }

    /**
     * The canvas surface. {@code frameYield} runs after each present (the game's per-frame yield),
     * or is {@code null} (harnesses).
     */
    public CoreSurface createSurface(BooleanSupplier iconified, Runnable frameYield) {
        return new CoreSurface(this, iconified, frameYield);
    }

    /** {@code maxLod} negative: no LOD clamp. */
    public CoreSampler createSampler(CoreAddressMode addressModeU, CoreAddressMode addressModeV, CoreFilterMode minFilter,
            CoreFilterMode magFilter, int maxAnisotropy, double maxLod) {
        return new CoreSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    /**
     * A texture; {@code usage} has {@link CoreUsage} texture bits. Throws
     * {@link UnsupportedOperationException} for formats WebGPU cannot store.
     */
    public CoreTexture createTexture(String label, int usage, CoreFormat format, int width, int height, int depthOrLayers,
            int mipLevels) {
        String webFormat = format.webTextureFormat();
        if (webFormat == null) {
            throw new UnsupportedOperationException("WebGPU has no texture format for " + format);
        }
        int handle = WebGpuGlue.createTexture(width, height, depthOrLayers, mipLevels, webFormat,
                CoreUsage.webTextureUsage(usage), label);
        return new CoreTexture(this, usage, label, format, width, height, depthOrLayers, mipLevels, handle);
    }

    /** A view of {@code mipLevels} levels; cube-compatible textures get a cube view. */
    public CoreTextureView createView(CoreTexture texture, int baseMipLevel, int mipLevels) {
        boolean cube = (texture.usage() & CoreUsage.TEXTURE_CUBEMAP) != 0;
        return new CoreTextureView(texture, baseMipLevel, mipLevels,
                WebGpuGlue.createView(texture.handle, baseMipLevel, mipLevels, cube));
    }

    /**
     * The bytes a buffer of {@code size} occupies: whole 4-byte words (WebGPU copies words), and
     * whole 16-byte rows for uniform buffers, so a uniform binding can always cover its std140
     * block (see {@code CorePass.bindingSize}).
     */
    static long allocatedSize(int usage, long size) {
        return (usage & CoreUsage.BUFFER_UNIFORM) != 0 ? (size + 15L) & ~15L : (size + 3L) & ~3L;
    }

    /** A buffer; {@code usage} has {@link CoreUsage} buffer bits. */
    public CoreBuffer createBuffer(String label, int usage, long size) {
        int allocated = (int) allocatedSize(usage, size);
        int handle = WebGpuGlue.createBuffer(allocated, CoreUsage.webBufferUsage(usage), label);
        return new CoreBuffer(this, usage, size, handle, false);
    }

    /** A buffer holding {@code data} (position to limit). */
    public CoreBuffer createBuffer(String label, int usage, ByteBuffer data) {
        int length = data.remaining();
        CoreBuffer buffer = this.createBuffer(label, usage, length);
        // A new buffer is not referenced by any recorded command yet, so a direct queue write is
        // ordered correctly.
        ByteBuffer source = data;
        if ((length & 3) != 0 || !data.isDirect()) {
            source = ByteBuffer.allocateDirect((length + 3) & ~3).order(ByteOrder.nativeOrder());
            if (data.isDirect()) {
                WebGpuGlue.copyBytes(source, 0, data);
            } else {
                source.put(data.duplicate()).clear();
            }
        }
        WebGpuGlue.writeBuffer(buffer.handle, 0, source);
        if ((usage & CoreUsage.BUFFER_INDEX) != 0) {
            buffer.scanIdentity(data);
        }
        return buffer;
    }

    /**
     * Captures a pipeline description ({@code compilePipeline}); SPIR-V goes to the
     * {@link WgslSources.Prefetcher}. The pipeline is built synchronously by
     * {@link CorePipeline.Pending#finish()} ({@code finishCompile}, D11).
     */
    public CorePipeline.Pending compile(CorePipelineDesc desc) {
        if (desc.shaders() instanceof CoreShaderSource.Spirv spirv) {
            StringBuilder texel = new StringBuilder();
            for (int i = 0; i < desc.uniforms().size(); i++) {
                CorePipelineDesc.Uniform uniform = desc.uniforms().get(i);
                if (uniform.type() == CoreUniformType.TEXEL_BUFFER && uniform.texelFormat() != null) {
                    texel.append(texel.length() > 0 ? "," : "").append(i).append(':').append(uniform.texelFormat().name());
                }
            }
            WgslSources.prefetch(desc.name(), spirv, texel.toString());
        }
        return () -> CorePipeline.compile(this, desc);
    }

    /** Upload, ring and pipeline telemetry (cumulative counters and the current ring state). */
    public CoreUploadStats uploadStats() {
        return new CoreUploadStats(this.encoder.submits, (long) WebGpuGlue.stat("ringUploads"),
                (long) WebGpuGlue.stat("ringUploadBytes"), this.ring.orderedWrites, this.ring.orderedBytes,
                this.ring.orderedStaged, (long) WebGpuGlue.stat("ringFallbacks"),
                (long) WebGpuGlue.stat("ringFallbackBytes"), (long) WebGpuGlue.stat("largeWrites"),
                (long) WebGpuGlue.stat("largeWriteBytes"), (long) WebGpuGlue.stat("ringBlocks"),
                (long) WebGpuGlue.stat("ringBytes"), (long) WebGpuGlue.stat("ringPending"),
                WebGpuGlue.stat("ringMapP95"), (long) WebGpuGlue.stat("lastSubmitUploadBytes"),
                this.encoder.passSplits, this.pipelines, this.pipelineNanos, WgslSources.translations(),
                WgslSources.cacheHits(), WgslSources.translateNanos());
    }

    /** A short key per distinct bind group layout: pipelines with equal keys share bind groups. */
    String layoutKey(String layoutJson) {
        String key = this.layoutKeys.get(layoutJson);
        if (key == null) {
            key = "L" + this.layoutKeys.size();
            this.layoutKeys.put(layoutJson, key);
        }
        return key;
    }

    void pipelineCreated(long nanos) {
        this.pipelines++;
        this.pipelineNanos += nanos;
    }

    /** A POINTS pipeline: drawn as one-pixel points (downgrade table, {@link CoreTopology}). */
    void pointPipelineCreated(String name) {
        WebGpuGlue.count("pointPipelines", 1);
        this.debugMessage("pipeline " + name + " draws POINTS one pixel wide (gl_PointSize is ignored on WebGPU)");
    }
}
