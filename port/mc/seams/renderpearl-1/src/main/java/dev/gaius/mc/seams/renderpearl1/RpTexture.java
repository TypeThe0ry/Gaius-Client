package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.gaius.gpu.webgpu.CoreTexture;
import dev.gaius.gpu.webgpu.CoreTextureOwner;

/**
 * A renderpearl texture over a core texture. Composition, not vanilla's {@code BaseGpuTexture}:
 * nothing in 26.3 casts textures to a base class. The quality hooks find the core texture through
 * {@link CoreTextureOwner}.
 */
public final class RpTexture implements GpuTexture, CoreTextureOwner {
    private final CoreTexture core;
    private final @GpuTexture.Usage int usage;
    private final GpuFormat format;

    RpTexture(@GpuTexture.Usage int usage, GpuFormat format, CoreTexture core) {
        this.core = core;
        this.usage = usage;
        this.format = format;
        core.setFacade(this);
    }

    public CoreTexture core() {
        return this.core;
    }

    @Override
    public CoreTexture coreTexture() {
        return this.core;
    }

    @Override
    public int getWidth(int mipLevel) {
        return this.core.width(mipLevel);
    }

    @Override
    public int getHeight(int mipLevel) {
        return this.core.height(mipLevel);
    }

    @Override
    public int getDepthOrLayers() {
        return this.core.depthOrLayers();
    }

    @Override
    public int getMipLevels() {
        return this.core.mipLevels();
    }

    @Override
    public GpuFormat getFormat() {
        return this.format;
    }

    @Override
    public int usage() {
        return this.usage;
    }

    @Override
    public String getLabel() {
        return this.core.label();
    }

    @Override
    public boolean isClosed() {
        return this.core.isClosed();
    }

    @Override
    public void close() {
        this.core.close();
    }
}
