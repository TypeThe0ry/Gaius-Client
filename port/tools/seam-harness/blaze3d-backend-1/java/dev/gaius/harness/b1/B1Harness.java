package dev.gaius.harness.b1;

import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.gaius.gpu.webgpu.CoreDevice;
import dev.gaius.gpu.webgpu.CoreShaderStage;
import dev.gaius.gpu.webgpu.GlslFrontEnd;
import dev.gaius.harness.common.Glyphs;
import dev.gaius.harness.common.Page;
import dev.gaius.mc.seams.blaze3dbackend1.B1Backend;
import dev.gaius.mc.seams.blaze3dbackend1.B1Device;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The blaze3d-backend-1 seam harness: vanilla 26.2's blaze3d frontend, the seam,
 * the WebGPU core and vanilla's pipelines and shaders draw a title-screen-like frame
 * ({@link TitleScene}). {@code mode=webgpu} runs it on the seam ({@code B1Backend}),
 * {@code mode=webgl2} on vanilla's {@code GlBackend} over Gaius's WebGL 2 LWJGL shim: the same
 * sequence on both, compared pixel by pixel by {@code run.mjs}.
 */
public final class B1Harness {
    private B1Harness() {
    }

    public static void main(String[] args) {
        String mode = Page.mode();
        try {
            run(mode);
            Page.status("ok");
        } catch (Throwable t) {
            Page.status("error: " + t);
            t.printStackTrace();
        }
    }

    /** {@code GlslCompiler.globalDefines}, as the seam injects them. */
    private static final ShaderDefines GLOBAL_DEFINES = ShaderDefines.builder()
            .define("gl_VertexID", "gl_VertexIndex")
            .define("gl_InstanceID", "gl_InstanceIndex")
            .build();

    private static void stageModule(String location, RenderPipeline pipeline, Identifier id, ShaderType type,
            ShaderSource shaders) {
        String text = GlslPreprocessor.injectDefines(
                GlslPreprocessor.injectDefines(shaders.get(id, type), pipeline.getShaderDefines()), GLOBAL_DEFINES);
        Page.module(location + "|" + type.name(), GlslFrontEnd.stage(id.toDebugFileName(), text,
                type == ShaderType.VERTEX ? CoreShaderStage.VERTEX : CoreShaderStage.FRAGMENT));
    }

    private static void run(String mode) throws Exception {
        ShaderSource shaders = (id, type) -> Page.shader(id + "|" + type.name());
        double start = Page.now();
        RenderSystem.initRenderThread();
        GpuDevice device;
        long window = 0L;
        B1Device seamDevice = null;
        if (mode.equals("webgpu")) {
            seamDevice = B1Backend.createBackendDevice(shaders, true);
            device = new GpuDevice(seamDevice, () -> {
            });
        } else {
            GLFW.glfwInit();
            window = GLFW.glfwCreateWindow(TitleScene.WIDTH, TitleScene.HEIGHT, "blaze3d-backend-1 harness", 0L, 0L);
            GLFW.glfwMakeContextCurrent(window);
            device = new GlBackend().createDevice(window, shaders, new GpuDebugOptions(0, false, false, false), () -> {
            });
        }
        RenderSystem.initRenderer(device);
        Page.info("backend", device.getDeviceInfo().backendName());
        Page.info("device", device.getDeviceInfo().name());
        Page.info("zZeroToOne", device.getDeviceInfo().isZZeroToOne() ? "true" : "false");
        double ready = Page.now();

        TitleScene scene = new TitleScene(device);
        scene.render();
        GpuSurface surface = device.createSurface(window);
        surface.configure(new GpuSurface.Configuration(TitleScene.WIDTH, TitleScene.HEIGHT, GpuSurface.PresentMode.FIFO));
        surface.acquireNextTexture();
        CommandEncoder encoder = device.createCommandEncoder();
        surface.blitFromTexture(encoder, scene.output());
        encoder.submit();
        double rendered = Page.now();
        surface.present();

        Page.info("draws", scene.draws());
        Page.info("deviceMs", ready - start);
        Page.info("frameMs", rendered - ready);
        List<String> messages = device.getLastDebugMessages();
        Page.info("debugMessages", String.join("\n", messages));
        if (seamDevice != null) {
            CoreDevice core = seamDevice.core();
            Page.info("skippedDraws", core.encoder().pass().drawsSkipped());
            Page.info("fanDraws", seamDevice.fanDraws());
            Page.info("outputTexture", ((dev.gaius.mc.seams.blaze3dbackend1.B1Texture) scene.outputTexture()).core()
                    .handle());
            long[] stats = GlslFrontEnd.stats();
            Page.info("shadercStages", stats[0]);
            Page.info("links", stats[2]);
            Page.info("shadercMs", stats[3] / 1e6);
            Page.info("linkMs", stats[4] / 1e6);
            Page.info("pipelinesCreated", core.uploadStats().toString());
            // The browser front-end's shaderc output of the title pipelines (from its cache: the
            // modules the frame used), hashed by the page and compared with the native corpus.
            // Every static vanilla pipeline through the browser front-end and WebGPU pipeline
            // creation (the title frame uses five of them).
            int valid = 0;
            StringBuilder invalid = new StringBuilder();
            double precompileStart = Page.now();
            for (RenderPipeline pipeline : RenderPipelines.getStaticPipelines()) {
                if (device.precompilePipeline(pipeline).isValid()) {
                    valid++;
                } else {
                    invalid.append(pipeline.getLocation()).append(' ');
                }
            }
            Page.info("staticPipelines", RenderPipelines.getStaticPipelines().size());
            Page.info("staticPipelinesValid", valid);
            Page.info("staticPipelinesInvalid", invalid.toString().trim());
            Page.info("staticPipelinesMs", Page.now() - precompileStart);
            Page.info("precompileMessages", String.join(System.lineSeparator(), device.getLastDebugMessages()));
            for (RenderPipeline pipeline : List.of(RenderPipelines.PANORAMA, RenderPipelines.GUI,
                    RenderPipelines.GUI_TEXTURED, RenderPipelines.GUI_TEXT, scene.blurPipeline())) {
                String location = pipeline == scene.blurPipeline() ? "minecraft:post_effect/blur/0"
                        : pipeline.getLocation().toString();
                stageModule(location, pipeline, pipeline.getVertexShader(), ShaderType.VERTEX, shaders);
                stageModule(location, pipeline, pipeline.getFragmentShader(), ShaderType.FRAGMENT, shaders);
            }
        }
    }
}
