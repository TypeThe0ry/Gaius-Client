package dev.gaius.gpu.webgpu;

/** Invalid readback usage must fail before reaching the native glue. */
public final class CoreTextureReadbackUsageTest {
    public static void main(String[] args) {
        CoreEncoder encoder = new CoreEncoder(null, null);
        CoreTexture source = new CoreTexture(null, CoreUsage.TEXTURE_BINDING, "sample-only",
                CoreFormat.RGBA8_UNORM, 2, 2, 1, 1, 1);
        CoreBuffer target = new CoreBuffer(null, CoreUsage.BUFFER_MAP_READ, 16, 2, false);
        try {
            encoder.copyTextureToBuffer(source, target, 0, () -> {}, 0, 0, 0, 2, 2);
            throw new AssertionError("readback accepted a texture without COPY_SRC");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("sample-only") || !expected.getMessage().contains("COPY_SRC")) {
                throw new AssertionError("missing resource/usage diagnostic", expected);
            }
        }
        System.out.println("CoreTextureReadbackUsageTest passed");
    }
}
