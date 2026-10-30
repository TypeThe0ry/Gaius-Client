package dev.gaius.gpu.webgpu;

/**
 * Texel and vertex formats of the WebGPU core. The constant names are the renderpearl
 * {@code GpuFormat} names, so a seam maps a vanilla format with {@link EnumBridge} by name; older
 * render SPIs (blaze3d) map their format enums through an alias table.
 *
 * <p>Each constant carries the WebGPU texture format ({@code null} when WebGPU has none: the
 * three-component formats need an expanding upload path, and the 16-bit snorm formats need a
 * feature), the WebGPU vertex format ({@code null} for depth and packed formats) and the block
 * size and aspects of the vanilla format.</p>
 */
public enum CoreFormat {
    R8_UNORM(1, ASPECT.COLOR, "r8unorm", "unorm8"),
    R8_SNORM(1, ASPECT.COLOR, "r8snorm", "snorm8"),
    RG8_UNORM(2, ASPECT.COLOR, "rg8unorm", "unorm8x2"),
    RG8_SNORM(2, ASPECT.COLOR, "rg8snorm", "snorm8x2"),
    // Vanilla pads three-component 8- and 16-bit attributes to a four-component group.
    RGB8_UNORM(3, ASPECT.COLOR, null, "unorm8x4"),
    RGB8_SNORM(3, ASPECT.COLOR, null, "snorm8x4"),
    RGBA8_UNORM(4, ASPECT.COLOR, "rgba8unorm", "unorm8x4"),
    RGBA8_SNORM(4, ASPECT.COLOR, "rgba8snorm", "snorm8x4"),
    R16_UNORM(2, ASPECT.COLOR, "r16unorm", "unorm16"),
    R16_SNORM(2, ASPECT.COLOR, null, "snorm16"),
    RG16_UNORM(4, ASPECT.COLOR, "rg16unorm", "unorm16x2"),
    RG16_SNORM(4, ASPECT.COLOR, null, "snorm16x2"),
    RGB16_UNORM(6, ASPECT.COLOR, null, "unorm16x4"),
    RGB16_SNORM(6, ASPECT.COLOR, null, "snorm16x4"),
    RGBA16_UNORM(8, ASPECT.COLOR, "rgba16unorm", "unorm16x4"),
    RGBA16_SNORM(8, ASPECT.COLOR, null, "snorm16x4"),
    R8_UINT(1, ASPECT.COLOR, "r8uint", "uint8"),
    R8_SINT(1, ASPECT.COLOR, "r8sint", "sint8"),
    RG8_UINT(2, ASPECT.COLOR, "rg8uint", "uint8x2"),
    RG8_SINT(2, ASPECT.COLOR, "rg8sint", "sint8x2"),
    RGB8_UINT(3, ASPECT.COLOR, null, "uint8x4"),
    RGB8_SINT(3, ASPECT.COLOR, null, "sint8x4"),
    RGBA8_UINT(4, ASPECT.COLOR, "rgba8uint", "uint8x4"),
    RGBA8_SINT(4, ASPECT.COLOR, "rgba8sint", "sint8x4"),
    R16_UINT(2, ASPECT.COLOR, "r16uint", "uint16"),
    R16_SINT(2, ASPECT.COLOR, "r16sint", "sint16"),
    RG16_UINT(4, ASPECT.COLOR, "rg16uint", "uint16x2"),
    RG16_SINT(4, ASPECT.COLOR, "rg16sint", "sint16x2"),
    RGB16_UINT(6, ASPECT.COLOR, null, "uint16x4"),
    RGB16_SINT(6, ASPECT.COLOR, null, "sint16x4"),
    RGBA16_UINT(8, ASPECT.COLOR, "rgba16uint", "uint16x4"),
    RGBA16_SINT(8, ASPECT.COLOR, "rgba16sint", "sint16x4"),
    R32_UINT(4, ASPECT.COLOR, "r32uint", "uint32"),
    R32_SINT(4, ASPECT.COLOR, "r32sint", "sint32"),
    RG32_UINT(8, ASPECT.COLOR, "rg32uint", "uint32x2"),
    RG32_SINT(8, ASPECT.COLOR, "rg32sint", "sint32x2"),
    RGB32_UINT(12, ASPECT.COLOR, null, "uint32x3"),
    RGB32_SINT(12, ASPECT.COLOR, null, "sint32x3"),
    RGBA32_UINT(16, ASPECT.COLOR, "rgba32uint", "uint32x4"),
    RGBA32_SINT(16, ASPECT.COLOR, "rgba32sint", "sint32x4"),
    R16_FLOAT(2, ASPECT.COLOR, "r16float", "float16"),
    RG16_FLOAT(4, ASPECT.COLOR, "rg16float", "float16x2"),
    RGB16_FLOAT(6, ASPECT.COLOR, null, "float16x4"),
    RGBA16_FLOAT(8, ASPECT.COLOR, "rgba16float", "float16x4"),
    R32_FLOAT(4, ASPECT.COLOR, "r32float", "float32"),
    RG32_FLOAT(8, ASPECT.COLOR, "rg32float", "float32x2"),
    RGB32_FLOAT(12, ASPECT.COLOR, null, "float32x3"),
    RGBA32_FLOAT(16, ASPECT.COLOR, "rgba32float", "float32x4"),
    RGB10A2_UNORM(4, ASPECT.COLOR, "rgb10a2unorm", "unorm10-10-10-2"),
    RGB10A2_UINT(4, ASPECT.COLOR, "rgb10a2uint", null),
    RG11B10_FLOAT(4, ASPECT.COLOR, "rg11b10ufloat", null),
    D32_FLOAT(4, ASPECT.DEPTH, "depth32float", null),
    D32_FLOAT_S8_UINT(8, ASPECT.DEPTH | ASPECT.STENCIL, "depth32float-stencil8", null),
    D24_UNORM_S8_UINT(4, ASPECT.DEPTH | ASPECT.STENCIL, "depth24plus-stencil8", null),
    D16_UNORM(2, ASPECT.DEPTH, "depth16unorm", null),
    S8_UINT(1, ASPECT.STENCIL, "stencil8", null);

    private final int blockSize;
    private final int aspects;
    private final String webTexture;
    private final String webVertex;

    CoreFormat(int blockSize, int aspects, String webTexture, String webVertex) {
        this.blockSize = blockSize;
        this.aspects = aspects;
        this.webTexture = webTexture;
        this.webVertex = webVertex;
    }

    /** Bytes per texel (per element for vertex data). */
    public int blockSize() {
        return this.blockSize;
    }

    /**
     * Bytes copied for one texel of the readback aspect: depth for depth/stencil formats,
     * stencil for stencil-only formats. WebGPU exposes no copyable depth24plus representation.
     */
    public int readbackTexelBytes() {
        if (this == D24_UNORM_S8_UINT) {
            throw new UnsupportedOperationException("WebGPU depth24plus depth readback is not supported");
        }
        return this == D32_FLOAT_S8_UINT ? 4 : this.blockSize;
    }

    public boolean hasColorAspect() {
        return (this.aspects & ASPECT.COLOR) != 0;
    }

    public boolean hasDepthAspect() {
        return (this.aspects & ASPECT.DEPTH) != 0;
    }

    public boolean hasStencilAspect() {
        return (this.aspects & ASPECT.STENCIL) != 0;
    }

    /** The WebGPU texture format, or {@code null} when WebGPU has none. */
    public String webTextureFormat() {
        return this.webTexture;
    }

    /** The WebGPU vertex format, or {@code null} when the format cannot feed a vertex attribute. */
    public String webVertexFormat() {
        return this.webVertex;
    }

    private static final class ASPECT {
        static final int COLOR = 1;
        static final int DEPTH = 2;
        static final int STENCIL = 4;
    }
}
