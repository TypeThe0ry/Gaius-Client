package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Translates one shader stage to WGSL, synchronously (D11: {@code finishCompile} never suspends).
 * The default translator ({@link GlueTranslator}) calls the {@code gaius-shaderxlat} kernel that
 * the page installs on the glue; {@link WgslSources#setTranslator(WgslTranslator)} replaces it.
 */
public interface WgslTranslator {
    /**
     * Translates {@code spirv} (position to limit; not retained).
     *
     * @param texelBuffers the pipeline's texel buffers as {@code "index:FORMAT,..."} (core format
     *        names), empty for none
     * @param flipVertexY negate the vertex position's y (the core's orientation contract)
     * @throws IllegalArgumentException when the module cannot be translated; the message names
     *         the failing step
     */
    Translation translate(String pipelineName, CoreShaderStage stage, ByteBuffer spirv, String texelBuffers,
            boolean flipVertexY);

    /**
     * {@link #translate(String, CoreShaderStage, ByteBuffer, String, boolean)} that also maps GL's
     * -1..1 clip depth to WebGPU's 0..1 at the vertex output ({@code glDepthRemap}: z becomes
     * (z + w) / 2, so depth values and tests are GL's), for GLSL written for GL's depth range
     * (1.21.11, {@link CoreShaderSource.Glsl#glDepthRange()}). A translator without the remap
     * refuses such a stage.
     */
    default Translation translate(String pipelineName, CoreShaderStage stage, ByteBuffer spirv, String texelBuffers,
            boolean flipVertexY, boolean glDepthRemap) {
        if (glDepthRemap) {
            throw new IllegalArgumentException("this translator cannot remap GL's depth range");
        }
        return this.translate(pipelineName, stage, spirv, texelBuffers, flipVertexY);
    }

    /** A translated stage: its WGSL and the reflection of every binding it declares. */
    record Translation(String wgsl, List<Binding> bindings) {
    }

    /**
     * One binding of a translated stage, in the record layout of the kernel ABI
     * ({@code port/native/crates/gaius-shaderxlat/wasm}): group, binding, the vanilla uniform
     * index, the kind and three kind-specific words, and whether the stage uses the binding.
     * <ul>
     * <li>{@link #KIND_UNIFORM}: {@code a} is the block size;</li>
     * <li>{@link #KIND_STORAGE}: a read-only storage buffer (a texel buffer);</li>
     * <li>{@link #KIND_TEXTURE}: {@code a} the dimension (0 1d, 1 2d, 2 2d-array, 3 3d, 4 cube,
     * 5 cube-array), {@code b} the sample kind (0 float, 1 sint, 2 uint, 3 depth), {@code c} bit 0
     * multisampled, bit 1 sampled by {@code textureSample*};</li>
     * <li>{@link #KIND_SAMPLER}: {@code a} 1 for a comparison sampler.</li>
     * </ul>
     */
    record Binding(int group, int binding, int uniform, int kind, int a, int b, int c, boolean used) {
        public static final int KIND_UNIFORM = 0;
        public static final int KIND_STORAGE = 1;
        public static final int KIND_TEXTURE = 2;
        public static final int KIND_SAMPLER = 3;
    }
}
