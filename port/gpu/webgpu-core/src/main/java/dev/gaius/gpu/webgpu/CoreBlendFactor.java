package dev.gaius.gpu.webgpu;

/**
 * Blend factors (renderpearl {@code BlendFactor} names). WebGPU has a single blend constant, so
 * the colour and alpha constant factors share it.
 */
public enum CoreBlendFactor {
    CONSTANT_ALPHA("constant"),
    CONSTANT_COLOR("constant"),
    DST_ALPHA("dst-alpha"),
    DST_COLOR("dst"),
    ONE("one"),
    ONE_MINUS_CONSTANT_ALPHA("one-minus-constant"),
    ONE_MINUS_CONSTANT_COLOR("one-minus-constant"),
    ONE_MINUS_DST_ALPHA("one-minus-dst-alpha"),
    ONE_MINUS_DST_COLOR("one-minus-dst"),
    ONE_MINUS_SRC_ALPHA("one-minus-src-alpha"),
    ONE_MINUS_SRC_COLOR("one-minus-src"),
    SRC_ALPHA("src-alpha"),
    SRC_ALPHA_SATURATE("src-alpha-saturated"),
    SRC_COLOR("src"),
    ZERO("zero");

    private final String web;

    CoreBlendFactor(String web) {
        this.web = web;
    }

    public String webName() {
        return this.web;
    }
}
