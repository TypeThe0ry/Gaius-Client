package dev.gaius.gpu.webgpu;

/** Sampler address modes (renderpearl {@code AddressMode} names). */
public enum CoreAddressMode {
    REPEAT("repeat"),
    CLAMP_TO_EDGE("clamp-to-edge");

    private final String web;

    CoreAddressMode(String web) {
        this.web = web;
    }

    public String webName() {
        return this.web;
    }
}
