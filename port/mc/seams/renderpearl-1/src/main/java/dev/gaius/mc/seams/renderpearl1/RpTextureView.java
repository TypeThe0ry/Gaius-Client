package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.gaius.gpu.webgpu.CoreTextureView;

/** A renderpearl texture view over a core view (composition, like {@link RpTexture}). */
public final class RpTextureView implements GpuTextureView {
    private final RpTexture texture;
    private final CoreTextureView core;

    RpTextureView(RpTexture texture, CoreTextureView core) {
        this.texture = texture;
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
    public GpuTexture texture() {
        return this.texture;
    }

    @Override
    public int baseMipLevel() {
        return this.core.baseMipLevel();
    }

    @Override
    public int mipLevels() {
        return this.core.mipLevels();
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
    public void close() {
        this.core.close();
    }
}
