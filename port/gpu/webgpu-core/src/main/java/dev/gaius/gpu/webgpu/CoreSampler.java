package dev.gaius.gpu.webgpu;

/** A WebGPU sampler with the GL backend's linear interpolation between mip levels. */
public final class CoreSampler {
    final int handle;
    private final CoreAddressMode addressModeU;
    private final CoreAddressMode addressModeV;
    private final CoreFilterMode minFilter;
    private final CoreFilterMode magFilter;
    private final int maxAnisotropy;
    private final double maxLod;
    private boolean closed;
    private Object facade;
    private int nonFiltering;

    CoreSampler(CoreAddressMode addressModeU, CoreAddressMode addressModeV, CoreFilterMode minFilter,
            CoreFilterMode magFilter, int maxAnisotropy, double maxLod) {
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        this.handle = WebGpuGlue.createSampler(addressModeU.webName(), addressModeV.webName(), minFilter.webName(),
                magFilter.webName(), CoreFilterMode.LINEAR.webName(), maxAnisotropy, maxLod);
    }

    public CoreAddressMode addressModeU() {
        return this.addressModeU;
    }

    public CoreAddressMode addressModeV() {
        return this.addressModeV;
    }

    public CoreFilterMode minFilter() {
        return this.minFilter;
    }

    public CoreFilterMode magFilter() {
        return this.magFilter;
    }

    public int maxAnisotropy() {
        return this.maxAnisotropy;
    }

    /** The LOD clamp, or a negative value when there is none. */
    public double maxLod() {
        return this.maxLod;
    }

    public Object facade() {
        return this.facade;
    }

    public void setFacade(Object facade) {
        this.facade = facade;
    }

    public boolean isClosed() {
        return this.closed;
    }

    /**
     * A sampler with this one's address modes and LOD clamp but nearest filtering, for a depth
     * texture the shaders sample through a filtering sampler ({@link BindingLayout#unfilterable}):
     * WebGPU does not filter depth textures outside comparisons. Created on first use.
     */
    int nonFilteringHandle() {
        if (this.nonFiltering == 0) {
            this.nonFiltering = WebGpuGlue.createSampler(this.addressModeU.webName(), this.addressModeV.webName(),
                    CoreFilterMode.NEAREST.webName(), CoreFilterMode.NEAREST.webName(), CoreFilterMode.NEAREST.webName(),
                    1, this.maxLod);
        }
        return this.nonFiltering;
    }

    public void close() {
        if (!this.closed) {
            this.closed = true;
            WebGpuGlue.releaseHandle(this.handle);
            if (this.nonFiltering != 0) {
                WebGpuGlue.releaseHandle(this.nonFiltering);
            }
        }
    }
}
