package dev.gaius.mc.seams.blaze3ddirect0;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.gaius.gpu.webgpu.CoreAddressMode;
import dev.gaius.gpu.webgpu.CoreBlendFactor;
import dev.gaius.gpu.webgpu.CoreCompareOp;
import dev.gaius.gpu.webgpu.CoreFilterMode;
import dev.gaius.gpu.webgpu.CoreFormat;
import dev.gaius.gpu.webgpu.CoreIndexType;
import dev.gaius.gpu.webgpu.CorePolygonMode;
import dev.gaius.gpu.webgpu.CoreShaderStage;
import dev.gaius.gpu.webgpu.CoreTopology;
import dev.gaius.gpu.webgpu.CoreUniformType;
import dev.gaius.gpu.webgpu.CoreUsage;
import dev.gaius.gpu.webgpu.EnumBridge;
import java.util.List;
import java.util.Map;

/**
 * The 1.21.11 enums mapped to the core's. Unlike 26.2 and 26.3, 1.21.11 names some differently:
 * {@code TextureFormat} (4 formats) and {@code DepthTestFunction} (5 of the 8 compare ops) go
 * through alias tables, vertex elements through {@link #vertexFormat} (type, count and usage, as
 * GL reads them). {@code D0EnumsTest} checks every constant against the 1.21.11 jar.
 */
public final class D0Enums {
    public static final EnumBridge<TextureFormat, CoreFormat> TEXTURE_FORMAT = EnumBridge.byName("TextureFormat",
            TextureFormat.values(), CoreFormat.values(),
            Map.of("RGBA8", "RGBA8_UNORM", "RED8", "R8_UNORM", "RED8I", "R8_SINT", "DEPTH32", "D32_FLOAT"));
    public static final EnumBridge<DepthTestFunction, CoreCompareOp> DEPTH_TEST = EnumBridge.byName("DepthTestFunction",
            DepthTestFunction.values(), CoreCompareOp.values(),
            Map.of("NO_DEPTH_TEST", "ALWAYS_PASS", "EQUAL_DEPTH_TEST", "EQUAL", "LEQUAL_DEPTH_TEST", "LESS_THAN_OR_EQUAL",
                    "LESS_DEPTH_TEST", "LESS_THAN", "GREATER_DEPTH_TEST", "GREATER_THAN"));
    public static final EnumBridge<SourceFactor, CoreBlendFactor> SOURCE_FACTOR =
            EnumBridge.byName("SourceFactor", SourceFactor.values(), CoreBlendFactor.values());
    public static final EnumBridge<DestFactor, CoreBlendFactor> DEST_FACTOR =
            EnumBridge.byName("DestFactor", DestFactor.values(), CoreBlendFactor.values());
    public static final EnumBridge<VertexFormat.Mode, CoreTopology> MODE =
            EnumBridge.byName("VertexFormat.Mode", VertexFormat.Mode.values(), CoreTopology.values());
    public static final EnumBridge<VertexFormat.IndexType, CoreIndexType> INDEX_TYPE =
            EnumBridge.byName("VertexFormat.IndexType", VertexFormat.IndexType.values(), CoreIndexType.values());
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

    private D0Enums() {
    }

    /** Every bridge of the seam (the completeness test walks this list). */
    public static List<EnumBridge<?, ?>> all() {
        return List.of(TEXTURE_FORMAT, DEPTH_TEST, SOURCE_FACTOR, DEST_FACTOR, MODE, INDEX_TYPE, POLYGON_MODE,
                ADDRESS_MODE, FILTER_MODE, UNIFORM_TYPE, SHADER_TYPE);
    }

    /**
     * The format a vertex element reaches the shader with, as 1.21.11's GL backend reads it:
     * {@code COLOR} and {@code NORMAL} bytes normalized ({@code glVertexAttribFormat} with
     * normalized), {@code UV} shorts and ints as integers ({@code glVertexAttribIFormat}), floats as
     * floats. Three-byte normals read four bytes (the formats pad them). {@code null}: no WebGPU
     * vertex format (the pipeline is then unavailable).
     */
    public static CoreFormat vertexFormat(VertexFormatElement element) {
        int count = element.count();
        return switch (element.type()) {
            case FLOAT -> switch (count) {
                case 1 -> CoreFormat.R32_FLOAT;
                case 2 -> CoreFormat.RG32_FLOAT;
                case 3 -> CoreFormat.RGB32_FLOAT;
                default -> CoreFormat.RGBA32_FLOAT;
            };
            case UBYTE -> element.usage() == VertexFormatElement.Usage.COLOR ? CoreFormat.RGBA8_UNORM : CoreFormat.RGBA8_UINT;
            case BYTE -> CoreFormat.RGBA8_SNORM;
            case SHORT -> count == 2 ? CoreFormat.RG16_SINT : CoreFormat.RGBA16_SINT;
            case USHORT -> count == 2 ? CoreFormat.RG16_UINT : CoreFormat.RGBA16_UINT;
            case INT -> count == 1 ? CoreFormat.R32_SINT : count == 2 ? CoreFormat.RG32_SINT : CoreFormat.RGBA32_SINT;
            case UINT -> count == 1 ? CoreFormat.R32_UINT : count == 2 ? CoreFormat.RG32_UINT : CoreFormat.RGBA32_UINT;
        };
    }

    /** {@link GpuBuffer} usage bits to {@link CoreUsage} buffer bits (1.21.11 has no indirect usage). */
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
}
