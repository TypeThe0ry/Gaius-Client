package dev.gaius.gpu.webgpu;

/** A WebGPU texture plus lazily created single-mip views used for clears and attachments. */
public final class CoreTexture {
    private final CoreDevice device;
    final int handle;
    private final int usage;
    private final String label;
    private final CoreFormat format;
    private final int width;
    private final int height;
    private final int depthOrLayers;
    private final int mipLevels;
    /** Single-mip views for attachments and clears, created on first use. */
    private final int[] mipViews;
    private boolean closed;
    private Object facade;

    CoreTexture(CoreDevice device, int usage, String label, CoreFormat format, int width, int height, int depthOrLayers,
            int mipLevels, int handle) {
        this.device = device;
        this.usage = usage;
        this.label = label;
        this.format = format;
        this.width = width;
        this.height = height;
        this.depthOrLayers = depthOrLayers;
        this.mipLevels = mipLevels;
        this.handle = handle;
        this.mipViews = new int[mipLevels];
    }

    /** {@link CoreUsage} texture bits. */
    public int usage() {
        return this.usage;
    }

    public String label() {
        return this.label;
    }

    public CoreFormat format() {
        return this.format;
    }

    /** Width of mip level {@code mip} ({@code width >> mip}, as vanilla's base textures). */
    public int width(int mip) {
        return Math.max(1, this.width >> mip);
    }

    public int height(int mip) {
        return Math.max(1, this.height >> mip);
    }

    public int depthOrLayers() {
        return this.depthOrLayers;
    }

    public int mipLevels() {
        return this.mipLevels;
    }

    /** The glue handle (diagnostics and harnesses). */
    public int handle() {
        return this.handle;
    }

    public Object facade() {
        return this.facade;
    }

    public void setFacade(Object facade) {
        this.facade = facade;
    }

    /** View of mip level {@code mip} only, as render pass attachments require. */
    int attachmentView(int mip) {
        if (this.mipViews[mip] == 0) {
            this.mipViews[mip] = WebGpuGlue.createView(this.handle, mip, 1, false);
        }
        return this.mipViews[mip];
    }

    public boolean isClosed() {
        return this.closed;
    }

    public void close() {
        if (!this.closed) {
            this.closed = true;
            for (int i = 0; i < this.mipViews.length; i++) {
                if (this.mipViews[i] != 0) {
                    WebGpuGlue.releaseHandle(this.mipViews[i]);
                    this.mipViews[i] = 0;
                }
            }
            this.device.encoder().destroyTextureAfterSubmit(this.handle);
        }
    }
}
