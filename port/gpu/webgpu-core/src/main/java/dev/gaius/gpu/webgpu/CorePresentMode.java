package dev.gaius.gpu.webgpu;

/**
 * Surface present modes (renderpearl {@code GpuSurface.PresentMode} names). The canvas presents
 * when the browser task ends, so the core reports {@link #FIFO} and {@link #IMMEDIATE} to keep
 * vanilla's vsync option working; frame pacing is the frame yield's job.
 */
public enum CorePresentMode {
    IMMEDIATE,
    MAILBOX,
    FIFO,
    FIFO_RELAXED;

    public boolean supported() {
        return this == FIFO || this == IMMEDIATE;
    }
}
