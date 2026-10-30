package dev.gaius.gpu.webgpu;

import java.util.List;

/**
 * Everything the core needs to build a render pipeline, in core types only. A seam converts its
 * SPI's pipeline description (renderpearl {@code CreateInfo}, blaze3d {@code RenderPipeline}) into
 * this class.
 */
public final class CorePipelineDesc {
    /** A vertex buffer slot; {@code stepRate} 0 steps per vertex, more per instance. */
    public record VertexBuffer(int slot, int stride, int stepRate) {
    }

    public record Attribute(int slot, int location, int offset, CoreFormat format) {
    }

    /** A uniform in binding order; {@code texelFormat} is set for texel buffers. */
    public record Uniform(String name, CoreUniformType type, CoreFormat texelFormat) {
    }

    public record DepthState(CoreCompareOp compare, boolean write, float biasConstant, float biasSlope) {
    }

    public record BlendComponent(CoreBlendFactor source, CoreBlendFactor destination, CoreBlendOp op) {
    }

    /**
     * A colour target; {@code writeMask} uses the WebGPU bits (red 1, green 2, blue 4, alpha 8);
     * {@code color} and {@code alpha} are both {@code null} without blending.
     */
    public record ColorTarget(CoreFormat format, int writeMask, BlendComponent color, BlendComponent alpha) {
    }

    private final String name;
    private final CoreShaderSource shaders;
    private final List<VertexBuffer> vertexBuffers;
    private final List<Attribute> attributes;
    private final List<Uniform> uniforms;
    private final DepthState depth;
    private final CorePolygonMode polygonMode;
    private final boolean cull;
    private final List<ColorTarget> colorTargets;
    private final CoreTopology topology;
    private final int pushConstantsSize;

    /**
     * @param depth {@code null} for a pipeline without depth test and depth writes
     * @param colorTargets may contain {@code null} for unused attachment slots
     */
    public CorePipelineDesc(String name, CoreShaderSource shaders, List<VertexBuffer> vertexBuffers,
            List<Attribute> attributes, List<Uniform> uniforms, DepthState depth, CorePolygonMode polygonMode, boolean cull,
            List<ColorTarget> colorTargets, CoreTopology topology, int pushConstantsSize) {
        this.name = name;
        this.shaders = shaders;
        this.vertexBuffers = vertexBuffers;
        this.attributes = attributes;
        this.uniforms = uniforms;
        this.depth = depth;
        this.polygonMode = polygonMode;
        this.cull = cull;
        this.colorTargets = colorTargets;
        this.topology = topology;
        this.pushConstantsSize = pushConstantsSize;
    }

    public String name() {
        return this.name;
    }

    public CoreShaderSource shaders() {
        return this.shaders;
    }

    public List<VertexBuffer> vertexBuffers() {
        return this.vertexBuffers;
    }

    public List<Attribute> attributes() {
        return this.attributes;
    }

    public List<Uniform> uniforms() {
        return this.uniforms;
    }

    /** The depth state, or {@code null}. */
    public DepthState depth() {
        return this.depth;
    }

    public CorePolygonMode polygonMode() {
        return this.polygonMode;
    }

    public boolean cull() {
        return this.cull;
    }

    public List<ColorTarget> colorTargets() {
        return this.colorTargets;
    }

    public CoreTopology topology() {
        return this.topology;
    }

    public int pushConstantsSize() {
        return this.pushConstantsSize;
    }
}
