package dev.gaius.gpu.webgpu;

/** A WebGPU texture view; cube-compatible textures get a cube view. */
public final class CoreTextureView {
    private final CoreTexture texture;
    private final int baseMipLevel;
    private final int mipLevels;
    final int handle;
    private boolean closed;
    private Object facade;

    CoreTextureView(CoreTexture texture, int baseMipLevel, int mipLevels, int handle) {
        this.texture = texture;
        this.baseMipLevel = baseMipLevel;
        this.mipLevels = mipLevels;
        this.handle = handle;
    }

    public CoreTexture texture() {
        return this.texture;
    }

    public int baseMipLevel() {
        return this.baseMipLevel;
    }

    public int mipLevels() {
        return this.mipLevels;
    }

    /** Width of mip level {@code mip} of the view (relative to its base level). */
    public int width(int mip) {
        return this.texture.width(this.baseMipLevel + mip);
    }

    public int height(int mip) {
        return this.texture.height(this.baseMipLevel + mip);
    }

    public Object facade() {
        return this.facade;
    }

    public void setFacade(Object facade) {
        this.facade = facade;
    }

    /** The single-mip view handle a render pass attachment needs. */
    int attachmentHandle() {
        return this.mipLevels == 1 ? this.handle : this.texture.attachmentView(this.baseMipLevel);
    }

    public boolean isClosed() {
        return this.closed || this.texture.isClosed();
    }

    public void close() {
        if (!this.closed) {
            this.closed = true;
            WebGpuGlue.releaseHandle(this.handle);
        }
    }
}
