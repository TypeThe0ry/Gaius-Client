import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Checks the two REQUIRED 1.21.11 patches of {@code Minecraft12111BrowserPatcher} with the patched
 * classes ahead of the 1.21.11 jar on the class path (run by {@code verify-patches.sh}, under
 * {@code -Xverify:all}): the rewritten {@code RenderSystem} passes the JVM's verifier, and the
 * vertex formats have the layout the page's backend needs. With {@code -Dgaius.d0.webgpu=true}
 * (the stand-in {@code D0Devices.lineWidthPadding} returns 1) every vanilla vertex format is
 * addressable by WebGPU (4-byte stride and attribute offsets) with
 * {@code POSITION_COLOR_NORMAL_LINE_WIDTH} at stride 24 and LineWidth at 20; without it (the GL
 * path) {@code POSITION_COLOR_NORMAL_LINE_WIDTH} keeps vanilla's stride 23 and LineWidth at 19.
 * Prints every format's stride and offsets (the script compares the GL run with the unpatched
 * jar's). Exit status 1 on a failure.
 */
public final class VerifyRequiredPatches {
    private VerifyRequiredPatches() {
    }

    public static void main(String[] args) throws Exception {
        boolean webGpu = Boolean.getBoolean("gaius.d0.webgpu");
        int failures = 0;
        if (args.length == 0 || !args[0].equals("--layout-only")) {
            // Loading and linking runs the verifier over the rewritten initRenderer.
            Class<?> renderSystem = Class.forName(RenderSystem.class.getName(), false,
                    VerifyRequiredPatches.class.getClassLoader());
            renderSystem.getDeclaredMethods();
            Method init = renderSystem.getDeclaredMethod("initRenderer", long.class, int.class, boolean.class,
                    Class.forName("com.mojang.blaze3d.shaders.ShaderSource"), boolean.class);
            System.out.println((Modifier.isStatic(init.getModifiers()) ? "ok   " : "FAIL ") + "RenderSystem verifies");
        }
        for (Field field : DefaultVertexFormat.class.getFields()) {
            if (field.getType() != VertexFormat.class) {
                continue;
            }
            VertexFormat format = (VertexFormat) field.get(null);
            boolean aligned = format.getElements().isEmpty() || (format.getVertexSize() & 3) == 0;
            StringBuilder offsets = new StringBuilder();
            for (VertexFormatElement element : format.getElements()) {
                aligned &= (format.getOffset(element) & 3) == 0;
                offsets.append(' ').append(format.getOffset(element));
            }
            boolean ok = !webGpu || aligned;
            System.out.println("layout " + field.getName() + " stride " + format.getVertexSize() + " offsets" + offsets
                    + (ok ? "" : " NOT ALIGNED"));
            failures += ok ? 0 : 1;
        }
        VertexFormat lines = DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH;
        int stride = webGpu ? 24 : 23;
        int lineWidth = webGpu ? 20 : 19;
        if (lines.getVertexSize() != stride || lines.getOffset(VertexFormatElement.LINE_WIDTH) != lineWidth) {
            System.out.println("FAIL POSITION_COLOR_NORMAL_LINE_WIDTH: stride " + lines.getVertexSize() + ", LineWidth at "
                    + lines.getOffset(VertexFormatElement.LINE_WIDTH) + " (expected " + stride + ", " + lineWidth + ")");
            failures++;
        }
        System.out.println(failures == 0 ? "patched layout verified (" + (webGpu ? "WebGPU" : "GL") + ")"
                : failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
