package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.gaius.gpu.webgpu.CoreTextureView;

/** A blaze3d texture view over a core view (vanilla's abstract {@link GpuTextureView} keeps the range). */
public final class B1TextureView extends GpuTextureView {
    private final CoreTextureView core;

    B1TextureView(B1Texture texture, int baseMipLevel, int mipLevels, CoreTextureView core) {
        super(texture, baseMipLevel, mipLevels);
        this.core = core;
        core.setFacade(this);
    }

    public CoreTextureView core() {
        return this.core;
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
