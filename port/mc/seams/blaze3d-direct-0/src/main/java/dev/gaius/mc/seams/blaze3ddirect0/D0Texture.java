package dev.gaius.mc.seams.blaze3ddirect0;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.gaius.gpu.webgpu.CoreTexture;
import org.jspecify.annotations.Nullable;

/** A 1.21.11 texture over a core texture (vanilla's abstract {@link GpuTexture} keeps its description). */
public final class D0Texture extends GpuTexture {
    private final CoreTexture core;

    D0Texture(@GpuTexture.Usage int usage, @Nullable String label, TextureFormat format, int width, int height,
            int depthOrLayers, int mipLevels, CoreTexture core) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.core = core;
        core.setFacade(this);
    }

    public CoreTexture core() {
        return this.core;
    }

    static int physicalMipLevels(int width, int height, int mipLevels) {
        if (width < 1 || height < 1 || mipLevels < 1) {
            throw new IllegalArgumentException("Texture dimensions and mipLevels must be positive");
        }
        return Math.min(mipLevels, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(width, height)));
    }

    static int viewMipLevels(int physicalMipLevels, int baseMipLevel, int mipLevels) {
        if (baseMipLevel < 0 || mipLevels < 1) {
            throw new IllegalArgumentException("Texture view mips out of range");
        }
        if (baseMipLevel >= physicalMipLevels) {
            throw new UnsupportedOperationException("Zero-sized mip levels cannot be used as texture views");
        }
        return Math.min(mipLevels, physicalMipLevels - baseMipLevel);
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
