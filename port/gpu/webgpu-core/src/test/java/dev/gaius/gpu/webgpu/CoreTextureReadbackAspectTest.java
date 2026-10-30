package dev.gaius.gpu.webgpu;

/** Readback sizes describe the selected aspect, not the packed vanilla format. */
public final class CoreTextureReadbackAspectTest {
    public static void main(String[] args) {
        expectLength(CoreFormat.D32_FLOAT_S8_UINT, 16);
        expectLength(CoreFormat.D32_FLOAT, 16);
        expectLength(CoreFormat.D16_UNORM, 8);
        expectLength(CoreFormat.S8_UINT, 4);
        expectLength(CoreFormat.RGBA8_UNORM, 16);
        CoreEncoder encoder = new CoreEncoder(null, null);
        CoreBuffer target = new CoreBuffer(null, CoreUsage.BUFFER_MAP_READ, 16, 2, false);
        try {
            encoder.copyTextureToBuffer(texture(CoreFormat.D24_UNORM_S8_UINT), target, 0, () -> {}, 0, 0, 0, 2, 2);
            throw new AssertionError("depth24plus readback accepted");
        } catch (UnsupportedOperationException expected) {
            if (!expected.getMessage().contains("depth24plus")) throw new AssertionError(expected);
        }
        // Overflowing offsets must be rejected before narrowing to the glue's int offsets.
        try {
            encoder.copyTextureToBuffer(texture(CoreFormat.RGBA8_UNORM), target, Long.MAX_VALUE - 3,
                    () -> {}, 0, 0, 0, 2, 2);
            throw new AssertionError("overflowing readback offset accepted");
        } catch (IllegalArgumentException expected) {
            // Expected before any native call.
        }
        System.out.println("CoreTextureReadbackAspectTest passed");
    }

    private static CoreTexture texture(CoreFormat format) {
        return new CoreTexture(null, CoreUsage.TEXTURE_COPY_SRC, "aspect", format, 2, 2, 1, 1, 1);
    }

    private static void expectLength(CoreFormat format, int bytes) {
        CoreEncoder encoder = new CoreEncoder(null, null);
        CoreBuffer empty = new CoreBuffer(null, CoreUsage.BUFFER_MAP_READ, 0, 2, false);
        try {
            encoder.copyTextureToBuffer(texture(format), empty, 0, () -> {}, 0, 0, 0, 2, 2);
            throw new AssertionError("readback into empty buffer accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("Readback of " + bytes + " bytes")) {
                throw new AssertionError(format + " must read " + bytes + " bytes", expected);
            }
        }
    }
}
