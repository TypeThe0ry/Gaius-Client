package dev.gaius.gpu.webgpu;

public final class CoreFence implements AutoCloseable {
    private final int handle;
    private boolean closed;

    CoreFence(int handle) {
        this.handle = handle;
    }

    public boolean isComplete() {
        if (this.closed) {
            throw new IllegalStateException("Fence already closed");
        }
        int status = WebGpuGlue.fenceStatus(this.handle);
        if (status < 0) {
            throw new IllegalStateException("WebGPU fence completion failed");
        }
        return status > 0;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            WebGpuGlue.closeFence(this.handle);
        }
    }
}
