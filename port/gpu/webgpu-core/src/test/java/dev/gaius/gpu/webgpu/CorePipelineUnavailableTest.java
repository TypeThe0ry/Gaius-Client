package dev.gaius.gpu.webgpu;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sun.misc.Unsafe;

/** A source-less bootstrap probe is unavailable; a real shader failure is still reported. */
public final class CorePipelineUnavailableTest {
    public static void main(String[] args) throws Exception {
        CoreDevice device = device();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        int[] translations = {0};
        WgslSources.setTranslator((name, stage, spirv, texels, flip) -> {
            translations[0]++;
            throw new IllegalArgumentException("test translation failure");
        });
        GlslFrontEnd.setCompiler((name, source, stage) -> {
            throw new IllegalArgumentException("test GLSL failure");
        });
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            unavailable(device, "minecraft:pipeline/mojang_logo", new CoreShaderSource.Registered());
            require(output.size() == 0, "a missing bootstrap source must not log a shader failure");
            require(translations[0] == 0, "a missing source must not enter the translator");
            require(messages(device).equals(List.of("no WGSL for pipeline minecraft:pipeline/mojang_logo")),
                    "preserve the unavailable-source debug diagnostic");

            CoreShaderSource.Spirv spirv = new CoreShaderSource.Spirv(
                    ByteBuffer.allocateDirect(4), "main", ByteBuffer.allocateDirect(4), "main");
            CorePipelineDesc spirvDesc = desc("test:spirv", spirv);
            CorePipeline cached = pipeline();
            set(cached, "closed", false);
            set(cached, "owners", 1);
            set(device, "builtPipelines", new HashMap<>(Map.of("test:spirv", cached)));
            set(device, "builtDescs", new HashMap<>(Map.of("test:spirv", spirvDesc)));
            require(CorePipeline.compile(device, spirvDesc) == null,
                    "a resource-pack SPIR-V failure must remain unavailable even with a cached vanilla pipeline");
            require(owners(cached) == 1, "a failed resource-pack pipeline must not retain its cached vanilla pipeline");
            require(translations[0] == 1, "SPIR-V must still enter the translator");
            require(messages(device).contains(
                    "pipeline test:spirv could not be built: test translation failure"),
                    "real translation failures must remain visible");
            output.reset();

            unavailable(device, "test:glsl", new CoreShaderSource.Glsl(
                    "test.vsh", "void main() {}", "test.fsh", "void main() {}",
                    List.of(), List.of(), false, false));
            require(output.toString(StandardCharsets.UTF_8).contains(
                    "pipeline test:glsl could not be built: test GLSL failure"),
                    "real GLSL failures must remain visible");
        } finally {
            System.setErr(previous);
            WgslSources.setTranslator(null);
            GlslFrontEnd.setCompiler(null);
        }
        System.out.println("Core pipeline availability passed: quiet bootstrap probe and visible shader failures");
    }

    private static CorePipelineDesc desc(String name, CoreShaderSource source) {
        return new CorePipelineDesc(name, source, List.of(), List.of(), List.of(),
                null, CorePolygonMode.FILL, false, List.of(), CoreTopology.TRIANGLES, 0);
    }

    private static void unavailable(CoreDevice device, String name, CoreShaderSource source) {
        require(CorePipeline.compile(device, desc(name, source)) == null,
                "the unavailable pipeline must remain unavailable");
    }

    private static CoreDevice device() throws Exception {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        CoreDevice device = (CoreDevice) ((Unsafe) singleton.get(null)).allocateInstance(CoreDevice.class);
        set(device, "debugMessages", new ArrayList<String>());
        set(device, "builtPipelines", new HashMap<String, CorePipeline>());
        set(device, "builtDescs", new HashMap<String, CorePipelineDesc>());
        return device;
    }

    private static CorePipeline pipeline() throws Exception {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return (CorePipeline) ((Unsafe) singleton.get(null)).allocateInstance(CorePipeline.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> messages(CoreDevice device) throws Exception {
        Field field = CoreDevice.class.getDeclaredField("debugMessages");
        field.setAccessible(true);
        return (List<String>) field.get(device);
    }

    private static void set(CoreDevice device, String name, Object value) throws Exception {
        Field field = CoreDevice.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(device, value);
    }

    private static void set(CorePipeline pipeline, String name, int value) throws Exception {
        Field field = CorePipeline.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(pipeline, value);
    }

    private static void set(CorePipeline pipeline, String name, boolean value) throws Exception {
        Field field = CorePipeline.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(pipeline, value);
    }

    private static int owners(CorePipeline pipeline) throws Exception {
        Field field = CorePipeline.class.getDeclaredField("owners");
        field.setAccessible(true);
        return field.getInt(pipeline);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
