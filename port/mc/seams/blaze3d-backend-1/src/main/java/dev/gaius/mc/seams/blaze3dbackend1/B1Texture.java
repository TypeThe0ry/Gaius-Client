package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.gaius.gpu.webgpu.CoreTexture;
import org.jspecify.annotations.Nullable;

/** A blaze3d texture over a core texture (vanilla's abstract {@link GpuTexture} keeps its description). */
public final class B1Texture extends GpuTexture {
    private final CoreTexture core;

    B1Texture(@GpuTexture.Usage int usage, @Nullable String label, GpuFormat format, int width, int height,
            int depthOrLayers, int mipLevels, CoreTexture core) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.core = core;
        core.setFacade(this);
    }

    public CoreTexture core() {
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
