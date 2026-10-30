package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.gaius.gpu.webgpu.CoreSampler;
import java.util.OptionalDouble;

/** A blaze3d sampler over a core sampler; the getters answer with vanilla's own values. */
public final class B1Sampler extends GpuSampler {
    private final CoreSampler core;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final OptionalDouble maxLod;

    B1Sampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter,
            OptionalDouble maxLod, CoreSampler core) {
        this.core = core;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxLod = maxLod;
        core.setFacade(this);
    }

    public CoreSampler core() {
        return this.core;
    }

    @Override
    public AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.core.maxAnisotropy();
    }

    @Override
    public OptionalDouble getMaxLod() {
        return this.maxLod;
    }

    @Override
    public void close() {
        this.core.close();
    }
}
