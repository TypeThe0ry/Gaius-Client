package dev.gaius.gpu.webgpu;

/** Depth/stencil copies must cover the complete selected mip subresource. */
public final class CoreTextureReadbackRegionTest {
    public static void main(String[] args) {
        for (CoreFormat format : new CoreFormat[] {CoreFormat.D32_FLOAT, CoreFormat.D32_FLOAT_S8_UINT,
                CoreFormat.D16_UNORM, CoreFormat.S8_UINT}) {
            rejectPartial(format, 0, 0, 0, 2, 4);
            rejectPartial(format, 0, 1, 0, 3, 4);
            rejectPartial(format, 0, 0, 1, 4, 3);
            rejectPartial(format, 1, 0, 0, 1, 2);
            // A full nonzero mip must reach byte-size validation, before any native glue call.
            CoreEncoder encoder = new CoreEncoder(null, null);
            try {
                encoder.copyTextureToBuffer(texture(format), target(), 0, () -> {}, 1, 0, 0, 2, 2);
                throw new AssertionError("empty destination accepted");
            } catch (IllegalArgumentException expected) {
                if (!expected.getMessage().startsWith("Readback of ")) throw new AssertionError(expected);
            }
        }
        System.out.println("CoreTextureReadbackRegionTest passed");
    }

    private static void rejectPartial(CoreFormat format, int mip, int x, int y, int width, int height) {
        try {
            new CoreEncoder(null, null).copyTextureToBuffer(texture(format), target(), 0, () -> {},
                    mip, x, y, width, height);
            throw new AssertionError("partial depth/stencil readback accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("complete mip")) {
                throw new AssertionError(format + " partial region must fail before destination-size validation", expected);
            }
        }
    }

    private static CoreTexture texture(CoreFormat format) {
        return new CoreTexture(null, CoreUsage.TEXTURE_COPY_SRC, "region", format, 4, 4, 1, 2, 1);
    }

    private static CoreBuffer target() {
        return new CoreBuffer(null, CoreUsage.BUFFER_MAP_READ, 0, 2, false);
    }
}
