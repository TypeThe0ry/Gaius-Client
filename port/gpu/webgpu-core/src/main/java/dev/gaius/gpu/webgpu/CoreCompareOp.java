package dev.gaius.gpu.webgpu;

/** Depth compare functions (renderpearl {@code CompareOp} names) and their WebGPU values. */
public enum CoreCompareOp {
    ALWAYS_PASS("always"),
    LESS_THAN("less"),
    LESS_THAN_OR_EQUAL("less-equal"),
    EQUAL("equal"),
    NOT_EQUAL("not-equal"),
    GREATER_THAN_OR_EQUAL("greater-equal"),
    GREATER_THAN("greater"),
    NEVER_PASS("never");

    private final String web;

    CoreCompareOp(String web) {
        this.web = web;
    }

    public String webName() {
        return this.web;
    }
}
