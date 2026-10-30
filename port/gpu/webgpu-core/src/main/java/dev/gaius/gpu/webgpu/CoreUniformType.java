package dev.gaius.gpu.webgpu;

/**
 * Kinds of pipeline uniforms (renderpearl {@code UniformType} names). The binding contract of
 * the core ("Split" layout) places uniform {@code i} by kind:
 * <ul>
 * <li>{@link #UNIFORM_BUFFER}: {@code @group(0) @binding(i)}, dynamic offset;</li>
 * <li>{@link #COMBINED_IMAGE_SAMPLER}: texture at {@code @group(1) @binding(2i)}, the split-off
 * sampler at {@code @group(1) @binding(2i + 1)};</li>
 * <li>{@link #TEXEL_BUFFER}: read-only storage buffer at {@code @group(1) @binding(2i)}.</li>
 * </ul>
 */
public enum CoreUniformType {
    COMBINED_IMAGE_SAMPLER,
    UNIFORM_BUFFER,
    TEXEL_BUFFER
}
