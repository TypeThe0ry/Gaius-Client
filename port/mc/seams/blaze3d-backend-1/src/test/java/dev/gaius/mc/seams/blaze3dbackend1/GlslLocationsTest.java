package dev.gaius.mc.seams.blaze3dbackend1;

import dev.gaius.gpu.webgpu.CoreShaderStage;
import dev.gaius.gpu.webgpu.GlslLocations;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Unit checks of the core's {@link GlslLocations} (the blaze3d front-end's replacement for
 * shaderc's {@code auto_map_locations}), run with the seam's tests. The equivalence on the whole
 * 26.2 and 1.21.11 corpora, against native shaderc, is {@code port/tools/glsl-locations}.
 * Exits with status 1 on any failure.
 */
public final class GlslLocationsTest {
    private static int failures;

    private GlslLocationsTest() {
    }

    public static void main(String[] args) {
        vertexStage();
        conditionals();
        sizes();
        ownLocations();
        declarators();
        blocksAndStructs();
        restore();
        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("all GlslLocations checks passed");
    }

    private static void vertexStage() {
        String source = "#version 330\n#define X 1\n\nin vec3 Position;\nflat in ivec2 UV1;\nout vec4 color;\n"
                + "void f(in vec3 a, out vec4 b) { b = vec4(a, 1.0); }\nvoid main() { color = vec4(Position, 1.0); }\n";
        GlslLocations.Result result = GlslLocations.inject(source, CoreShaderStage.VERTEX);
        expect("vertex stage", "#version 330\n#extension GL_ARB_separate_shader_objects : enable\n#line 2\n#define X 1\n\n"
                + "layout(location = 0) in vec3 Position;\nflat layout(location = 1) in ivec2 UV1;\n"
                + "layout(location = 0) out vec4 color;\n"
                + "void f(in vec3 a, out vec4 b) { b = vec4(a, 1.0); }\nvoid main() { color = vec4(Position, 1.0); }\n",
                result.source());
        check("vertex stage counts", result.inputs() == 2 && result.outputs() == 1 && result.extension());
        // A fragment stage whose only located variable is its output needs no extension in 3.30.
        GlslLocations.Result fragment = GlslLocations.inject("#version 330\nout vec4 fragColor;\nvoid main() {}\n",
                CoreShaderStage.FRAGMENT);
        expect("fragment output only", "#version 330\nlayout(location = 0) out vec4 fragColor;\nvoid main() {}\n",
                fragment.source());
        check("fragment output only, no extension", !fragment.extension());
    }

    private static void conditionals() {
        String source = "#version 330\n#define PER_FACE\n#ifdef PER_FACE\nout vec4 back;\nout vec4 front;\n#else\n"
                + "out vec4 color;\n#endif\n#if defined(NOPE) || 2 > 3\nout vec4 never;\n#elif 1\nout float yes;\n#endif\n"
                + "/* out vec4 commented; */\n// out vec4 commented2;\nout vec2 uv;\n";
        GlslLocations.Result result = GlslLocations.inject(source, CoreShaderStage.VERTEX);
        check("conditionals: active branch", result.source().contains("layout(location = 0) out vec4 back;")
                && result.source().contains("layout(location = 1) out vec4 front;")
                && result.source().contains("layout(location = 2) out float yes;")
                && result.source().contains("layout(location = 3) out vec2 uv;"));
        check("conditionals: inactive branches untouched", result.source().contains("\nout vec4 color;")
                && result.source().contains("\nout vec4 never;") && result.source().contains("/* out vec4 commented; */"));
    }

    private static void sizes() {
        String source = "#version 330\n#define N 2\nout vec4 coeff[N];\nout mat3 m;\nout vec4 next;\n";
        GlslLocations.Result result = GlslLocations.inject(source, CoreShaderStage.FRAGMENT);
        check("array and matrix sizes", result.source().contains("layout(location = 0) out vec4 coeff[N];")
                && result.source().contains("layout(location = 2) out mat3 m;")
                && result.source().contains("layout(location = 5) out vec4 next;") && result.outputs() == 6);
    }

    private static void ownLocations() {
        String source = "#version 330\nlayout(location = 3) in vec3 a;\nin vec3 b;\nlayout(origin_upper_left) in vec4 gl_FragCoord;\n"
                + "layout(index = 0) out vec4 c;\nout float gl_FragDepth;\n";
        GlslLocations.Result result = GlslLocations.inject(source, CoreShaderStage.FRAGMENT);
        check("own location kept, not counted", result.source().contains("layout(location = 3) in vec3 a;")
                && result.source().contains("layout(location = 0) in vec3 b;"));
        check("built-ins untouched", result.source().contains("layout(origin_upper_left) in vec4 gl_FragCoord;")
                && result.source().contains("\nout float gl_FragDepth;"));
        check("merged into an existing layout", result.source().contains("layout(location = 0, index = 0) out vec4 c;"));
    }

    private static void declarators() {
        GlslLocations.Result result = GlslLocations.inject("#version 330\nflat in vec2 a, b[2], c;\n",
                CoreShaderStage.FRAGMENT);
        expect("several declarators", "#version 330\n#extension GL_ARB_separate_shader_objects : enable\n#line 2\n"
                + "flat layout(location = 0) in vec2 a; flat layout(location = 1) in vec2 b[2]; flat layout(location = 3) in vec2 c;\n",
                result.source());
    }

    private static void blocksAndStructs() {
        String source = "#version 330\nstruct Light { vec3 dir; vec4 color[2]; };\nout Light light;\n"
                + "out Block { vec4 a; mat2 b; } blocks[2];\nout vec4 after;\nout gl_PerVertex { vec4 gl_Position; };\n";
        GlslLocations.Result result = GlslLocations.inject(source, CoreShaderStage.VERTEX);
        check("struct and block sizes", result.source().contains("layout(location = 0) out Light light;")
                && result.source().contains("layout(location = 3) out Block")
                && result.source().contains("layout(location = 9) out vec4 after;")
                && result.source().contains("\nout gl_PerVertex"));
    }

    // ---- restore -------------------------------------------------------------------------------

    private static void restore() {
        String original = "#version 330\nout vec4 v;\n";
        GlslLocations.Result result = GlslLocations.inject(original, CoreShaderStage.VERTEX);
        byte[] injected = module(result.source(), true, new String[] { "entry-point main", "auto-map-bindings",
                "client vulkan100", "target-env spirv1.5" });
        byte[] expected = module(original, false, new String[] { "entry-point main", "auto-map-bindings",
                "auto-map-locations", "client vulkan100", "target-env spirv1.5" });
        check("restore gives the auto-mapped module", Arrays.equals(result.restore(injected), expected));
        byte[] noDebug = module(null, false, new String[0]);
        check("restore leaves a module without debug info alone", Arrays.equals(result.restore(noDebug), noDebug));
    }

    /** A minimal module: header, OpSourceExtension(s), OpSource with text, OpModuleProcessed, OpCapability. */
    private static byte[] module(String text, boolean extension, String[] processes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        word(out, 0x07230203);
        word(out, 0x00010500);
        word(out, 0x000B000D);
        word(out, 10);
        word(out, 0);
        word(out, (2 << 16) | 17); // OpCapability Shader
        word(out, 1);
        if (extension) {
            string(out, 4, new int[0], "GL_ARB_separate_shader_objects");
        }
        string(out, 4, new int[0], "GL_GOOGLE_cpp_style_line_directive");
        if (text != null) {
            string(out, 3, new int[] { 2, 330, 1 }, text);
        }
        for (String process : processes) {
            string(out, 330, new int[0], process);
        }
        return out.toByteArray();
    }

    private static void string(ByteArrayOutputStream out, int op, int[] operands, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int words = bytes.length / 4 + 1;
        word(out, ((1 + operands.length + words) << 16) | op);
        for (int operand : operands) {
            word(out, operand);
        }
        byte[] padded = Arrays.copyOf(bytes, words * 4);
        out.write(padded, 0, padded.length);
    }

    private static void word(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static void expect(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            System.out.println("ok   " + name);
        } else {
            fail(name + ":\n--- expected\n" + expected + "\n--- actual\n" + actual);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("ok   " + name);
        } else {
            fail(name);
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL " + message);
    }
}
