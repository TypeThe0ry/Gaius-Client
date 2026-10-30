package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.gaius.gpu.webgpu.CoreAddressMode;
import dev.gaius.gpu.webgpu.CoreBlendFactor;
import dev.gaius.gpu.webgpu.CoreBlendOp;
import dev.gaius.gpu.webgpu.CoreCompareOp;
import dev.gaius.gpu.webgpu.CoreFilterMode;
import dev.gaius.gpu.webgpu.CoreFormat;
import dev.gaius.gpu.webgpu.CoreIndexType;
import dev.gaius.gpu.webgpu.CorePolygonMode;
import dev.gaius.gpu.webgpu.CorePresentMode;
import dev.gaius.gpu.webgpu.CoreShaderStage;
import dev.gaius.gpu.webgpu.CoreTopology;
import dev.gaius.gpu.webgpu.CoreUniformType;
import dev.gaius.gpu.webgpu.CoreUsage;
import dev.gaius.gpu.webgpu.EnumBridge;
import java.util.List;

/**
 * The blaze3d (26.2) enums and flag bits mapped to the core's. 26.2 already uses renderpearl's
 * constant names (the core's), so every bridge is by name without aliases; {@code B1EnumsTest}
 * checks that each vanilla constant has a counterpart for every profile that selects this seam.
 */
public final class B1Enums {
    public static final EnumBridge<GpuFormat, CoreFormat> FORMAT =
            EnumBridge.byName("GpuFormat", GpuFormat.values(), CoreFormat.values());
    public static final EnumBridge<CompareOp, CoreCompareOp> COMPARE =
            EnumBridge.byName("CompareOp", CompareOp.values(), CoreCompareOp.values());
    public static final EnumBridge<BlendFactor, CoreBlendFactor> BLEND_FACTOR =
            EnumBridge.byName("BlendFactor", BlendFactor.values(), CoreBlendFactor.values());
    public static final EnumBridge<BlendOp, CoreBlendOp> BLEND_OP =
            EnumBridge.byName("BlendOp", BlendOp.values(), CoreBlendOp.values());
    public static final EnumBridge<PrimitiveTopology, CoreTopology> TOPOLOGY =
            EnumBridge.byName("PrimitiveTopology", PrimitiveTopology.values(), CoreTopology.values());
    public static final EnumBridge<IndexType, CoreIndexType> INDEX_TYPE =
            EnumBridge.byName("IndexType", IndexType.values(), CoreIndexType.values());
    public static final EnumBridge<PolygonMode, CorePolygonMode> POLYGON_MODE =
            EnumBridge.byName("PolygonMode", PolygonMode.values(), CorePolygonMode.values());
    public static final EnumBridge<AddressMode, CoreAddressMode> ADDRESS_MODE =
            EnumBridge.byName("AddressMode", AddressMode.values(), CoreAddressMode.values());
    public static final EnumBridge<FilterMode, CoreFilterMode> FILTER_MODE =
            EnumBridge.byName("FilterMode", FilterMode.values(), CoreFilterMode.values());
    public static final EnumBridge<UniformType, CoreUniformType> UNIFORM_TYPE =
            EnumBridge.byName("UniformType", UniformType.values(), CoreUniformType.values());
    public static final EnumBridge<ShaderType, CoreShaderStage> SHADER_TYPE =
            EnumBridge.byName("ShaderType", ShaderType.values(), CoreShaderStage.values());
    public static final EnumBridge<GpuSurface.PresentMode, CorePresentMode> PRESENT_MODE =
            EnumBridge.byName("GpuSurface.PresentMode", GpuSurface.PresentMode.values(), CorePresentMode.values());

    private B1Enums() {
    }

    /** Every bridge of the seam (the completeness test walks this list). */
    public static List<EnumBridge<?, ?>> all() {
        return List.of(FORMAT, COMPARE, BLEND_FACTOR, BLEND_OP, TOPOLOGY, INDEX_TYPE, POLYGON_MODE, ADDRESS_MODE,
                FILTER_MODE, UNIFORM_TYPE, SHADER_TYPE, PRESENT_MODE);
    }

    /** {@link GpuBuffer} usage bits to {@link CoreUsage} buffer bits. */
    public static int bufferUsage(@GpuBuffer.Usage int usage) {
        int result = 0;
        result |= (usage & GpuBuffer.USAGE_MAP_READ) != 0 ? CoreUsage.BUFFER_MAP_READ : 0;
        result |= (usage & GpuBuffer.USAGE_MAP_WRITE) != 0 ? CoreUsage.BUFFER_MAP_WRITE : 0;
        result |= (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0 ? CoreUsage.BUFFER_CLIENT_STORAGE : 0;
        result |= (usage & GpuBuffer.USAGE_COPY_DST) != 0 ? CoreUsage.BUFFER_COPY_DST : 0;
        result |= (usage & GpuBuffer.USAGE_COPY_SRC) != 0 ? CoreUsage.BUFFER_COPY_SRC : 0;
        result |= (usage & GpuBuffer.USAGE_VERTEX) != 0 ? CoreUsage.BUFFER_VERTEX : 0;
        result |= (usage & GpuBuffer.USAGE_INDEX) != 0 ? CoreUsage.BUFFER_INDEX : 0;
        result |= (usage & GpuBuffer.USAGE_UNIFORM) != 0 ? CoreUsage.BUFFER_UNIFORM : 0;
        result |= (usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0 ? CoreUsage.BUFFER_TEXEL : 0;
        result |= (usage & GpuBuffer.USAGE_INDIRECT_PARAMETERS) != 0 ? CoreUsage.BUFFER_INDIRECT : 0;
        return result;
    }

    /** {@link GpuTexture} usage bits to {@link CoreUsage} texture bits. */
    public static int textureUsage(@GpuTexture.Usage int usage) {
        int result = 0;
        result |= (usage & GpuTexture.USAGE_COPY_DST) != 0 ? CoreUsage.TEXTURE_COPY_DST : 0;
        result |= (usage & GpuTexture.USAGE_COPY_SRC) != 0 ? CoreUsage.TEXTURE_COPY_SRC : 0;
        result |= (usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 ? CoreUsage.TEXTURE_BINDING : 0;
        result |= (usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0 ? CoreUsage.TEXTURE_RENDER_ATTACHMENT : 0;
        result |= (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? CoreUsage.TEXTURE_CUBEMAP : 0;
        return result;
    }

    /** {@link ColorTargetState} write mask bits to WebGPU's (red 1, green 2, blue 4, alpha 8). */
    public static int writeMask(int mask) {
        int result = 0;
        result |= (mask & ColorTargetState.WRITE_RED) != 0 ? 1 : 0;
        result |= (mask & ColorTargetState.WRITE_GREEN) != 0 ? 2 : 0;
        result |= (mask & ColorTargetState.WRITE_BLUE) != 0 ? 4 : 0;
        result |= (mask & ColorTargetState.WRITE_ALPHA) != 0 ? 8 : 0;
        return result;
    }
}
