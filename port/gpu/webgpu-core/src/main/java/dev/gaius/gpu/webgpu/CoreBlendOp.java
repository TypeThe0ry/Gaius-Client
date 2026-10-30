package dev.gaius.gpu.webgpu;

/** Blend operations (renderpearl {@code BlendOp} names). */
public enum CoreBlendOp {
    ADD("add"),
    SUBTRACT("subtract"),
    REVERSE_SUBTRACT("reverse-subtract"),
    MIN("min"),
    MAX("max");

    private final String web;

    CoreBlendOp(String web) {
        this.web = web;
    }

    public String webName() {
        return this.web;
    }
}
