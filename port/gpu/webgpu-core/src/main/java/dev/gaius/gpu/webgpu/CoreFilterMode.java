package dev.gaius.gpu.webgpu;

/** Sampler filters (renderpearl {@code FilterMode} names). */
public enum CoreFilterMode {
    NEAREST("nearest"),
    LINEAR("linear");

    private final String web;

    CoreFilterMode(String web) {
        this.web = web;
    }

    public String webName() {
        return this.web;
    }
}
