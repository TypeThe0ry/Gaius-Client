package dev.gaius.gpu.webgpu;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** The explicit-layer entry validates a face before encoding, while legacy calls select layer 0. */
public final class CoreTextureReadbackLayerTest {
    public static void main(String[] args) throws Exception {
        Method copy = CoreEncoder.class.getMethod("copyTextureToBuffer", CoreTexture.class, CoreBuffer.class,
                long.class, Runnable.class, int.class, int.class, int.class, int.class, int.class, int.class);
        CoreEncoder encoder = new CoreEncoder(null, null);
        CoreTexture cube = new CoreTexture(null, CoreUsage.TEXTURE_COPY_SRC | CoreUsage.TEXTURE_CUBEMAP,
                "cube", CoreFormat.RGBA8_UNORM, 4, 4, 6, 2, 1);
        CoreBuffer target = new CoreBuffer(null, CoreUsage.BUFFER_MAP_READ, 64, 2, false);
        for (int layer : new int[] {-1, 6}) {
            try {
                copy.invoke(encoder, cube, target, 0L, (Runnable) () -> {}, 0, layer, 0, 0, 4, 4);
                throw new AssertionError("accepted layer " + layer);
            } catch (InvocationTargetException expected) {
                if (!(expected.getCause() instanceof IllegalArgumentException)
                        || !expected.getCause().getMessage().contains("layer")) {
                    throw new AssertionError("invalid layer reached glue", expected.getCause());
                }
            }
        }
        // A valid nonzero layer must pass Java validation and reach the native entry on a JVM.
        try {
            copy.invoke(encoder, cube, target, 0L, (Runnable) () -> {}, 1, 5, 0, 0, 2, 2);
            throw new AssertionError("JVM unexpectedly executed native glue");
        } catch (InvocationTargetException expected) {
            if (!(expected.getCause() instanceof UnsatisfiedLinkError)) throw expected;
        }
        System.out.println("CoreTextureReadbackLayerTest passed");
    }
}
