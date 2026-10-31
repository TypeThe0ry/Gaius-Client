package dev.gaius.shadercorpus;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.opengl.GlPipelineRecompiler;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.util.shaderc.BrowserShaderc;
import org.lwjgl.util.spvc.BrowserSpvc;

/**
 * ShaderCorpusHarness (PLAN D5): compiles every Minecraft 26.3 render pipeline
 * the way the GL backend does - the vanilla frontend (PipelineBuilder,
 * GlslCompiler, SPIRVModule) and GlPipelineRecompiler.decompileShaders - on the
 * JVM, without a GL context, with the browser's device characteristics
 * (isZZeroToOne=false, shaderDrawParameters=false, no explicit-depth hint).
 * The pipelines are the static RenderPipelines (required + optional) and every
 * pass of assets/minecraft/post_effect/*.json, captured from the vanilla
 * PostChain.createPass.
 *
 * <p>Modes:
 * <ul>
 *   <li>native: the vanilla LWJGL shaderc/spvc bindings (the reference);</li>
 *   <li>shim: the browser-patched Shaderc/Spvc, whose bodies call
 *       BrowserShaderc/BrowserSpvc, over native backends that record the SPI
 *       tapes the Node smoke replays against WebAssembly; GLSL comes out as
 *       WebGL2 ESSL;</li>
 *   <li>shim-raw: shim with the ESSL post-processing off (GLSL must equal native).</li>
 * </ul>
 *
 * <p>Writes pipelines.json, summary.json, spirv/ and glsl/ blobs (and in shim
 * modes tape-*.jsonl with tape-* blobs).  With -Dgaius.translate.check=true it
 * also checks that each GLSL output is a fixed point of
 * BrowserOpenGL.translateShaderSource (class on the classpath).
 *
 * <p>usage: ShaderCorpusHarness native|shim|shim-raw CLIENT_JAR OUTPUT_DIR
 */
public final class ShaderCorpusHarness {
    private ShaderCorpusHarness() {
    }

    /** ShaderSource over the client jar with ShaderManager's eager include loading. */
    static final class JarShaderSource implements ShaderSource {
        final ZipFile zip;
        final Map<Identifier, ShaderSource.CachedIncludeSource> includes = new HashMap<>();

        JarShaderSource(ZipFile zip) {
            this.zip = zip;
            String prefix = "assets/minecraft/shaders/include/";
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (name.startsWith(prefix) && name.endsWith(".glsl")) {
                    Identifier id = Identifier.fromNamespaceAndPath("minecraft", name.substring(prefix.length()));
                    includes.put(id, ShaderSource.CachedIncludeSource.create(id, read(name)));
                }
            }
        }

        String read(String entry) {
            try {
                ZipEntry found = zip.getEntry(entry);
                return found == null ? null
                        : new String(zip.getInputStream(found).readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String getShader(Identifier id, ShaderType type) {
            String extension = type == ShaderType.VERTEX ? ".vsh" : ".fsh";
            return read("assets/" + id.getNamespace() + "/shaders/" + id.getPath() + extension);
        }

        @Override
        public ShaderSource.CachedIncludeSource getInclude(Identifier id) {
            return includes.get(id);
        }

        @Override
        public void close() {
            includes.values().forEach(ShaderSource.CachedIncludeSource::close);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(ShaderCorpusHarness.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 3 || !Set.of("native", "shim", "shim-raw").contains(args[0])) {
            throw new IllegalArgumentException("usage: ShaderCorpusHarness native|shim|shim-raw CLIENT_JAR OUTPUT_DIR");
        }
        String mode = args[0];
        Path clientJar = Path.of(args[1]);
        Corpus corpus = new Corpus(Path.of(args[2]));
        long started = System.nanoTime();

        NativeShadercToolchain shaderc = null;
        NativeSpvcToolchain spvc = null;
        if (!mode.equals("native")) {
            shaderc = new NativeShadercToolchain(corpus);
            spvc = new NativeSpvcToolchain(corpus);
            BrowserShaderc.setToolchain(shaderc);
            BrowserSpvc.setToolchain(spvc);
            BrowserSpvc.setEsslPostProcessing(mode.equals("shim"));
        }
        Method translate = null;
        if (Boolean.getBoolean("gaius.translate.check")) {
            translate = Class.forName("org.lwjgl.opengl.BrowserOpenGL")
                    .getDeclaredMethod("translateShaderSource", String.class);
            translate.setAccessible(true);
        }

        // Browser (BrowserOpenGL / WebGL2) device characteristics that reach the shader toolchain.
        DeviceFeatures features = new DeviceFeatures(false, false, false, true, false, false, false, false);
        HintsAndWorkarounds hints = new HintsAndWorkarounds(false, false, false, false);
        DeviceLimits limits = new DeviceLimits(1, 256, 4096, Long.MAX_VALUE, 0, 4, 0);
        DeviceInfo info = new DeviceInfo("WebKit WebGL", "WebKit", "OpenGL ES 3.0 (WebGL 2.0)", false, "OpenGL",
                1.0f, limits, features, Set.of(), hints, DeviceType.OTHER);
        List<RenderPipeline> capturedPost = new ArrayList<>();
        GpuDevice device = proxy(GpuDevice.class, (self, method, arguments) -> switch (method.getName()) {
            case "getDeviceInfo" -> info;
            case "compilePipeline" -> {
                capturedPost.add((RenderPipeline) arguments[0]);
                yield CompletableFuture.failedFuture(new IllegalStateException("captured by the corpus harness"));
            }
            case "toString" -> "ShaderCorpusGpuDevice";
            case "hashCode" -> 1;
            case "equals" -> self == arguments[0];
            default -> throw new UnsupportedOperationException("GpuDevice." + method.getName());
        });
        Field deviceField = RenderSystem.class.getDeclaredField("DEVICE");
        deviceField.setAccessible(true);
        deviceField.set(null, device);
        GpuDeviceBackend backend = proxy(GpuDeviceBackend.class, (self, method, arguments) -> switch (method.getName()) {
            case "getDeviceInfo" -> info;
            case "toString" -> "ShaderCorpusBackend";
            case "hashCode" -> 2;
            case "equals" -> self == arguments[0];
            default -> throw new UnsupportedOperationException("GpuDeviceBackend." + method.getName());
        });

        int ok = 0;
        int failed = 0;
        int fixedPointFailures = 0;
        int shaderCount = 0;
        StringBuilder index = new StringBuilder("[\n");
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            JarShaderSource source = new JarShaderSource(zip);
            List<RenderPipeline> required = RenderPipelines.requiredPipelines();
            List<RenderPipeline> optional = RenderPipelines.optionalPipelines();
            RenderSystem.setCurrentPipelineCache(new PipelineCache(device, source));
            Method createPass = Class.forName("net.minecraft.client.renderer.PostChain").getDeclaredMethod(
                    "createPass", Class.forName("net.minecraft.client.renderer.texture.TextureManager"),
                    PostChainConfig.Pass.class, Identifier.class);
            createPass.setAccessible(true);
            List<String> postEffects = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (name.startsWith("assets/minecraft/post_effect/") && name.endsWith(".json")) {
                    postEffects.add(name);
                }
            }
            postEffects.sort(null);
            for (String name : postEffects) {
                String chain = name.substring("assets/minecraft/post_effect/".length(), name.length() - 5);
                Identifier chainId = Identifier.fromNamespaceAndPath("minecraft", chain);
                PostChainConfig config = PostChainConfig.CODEC.parse(JsonOps.INSTANCE,
                        JsonParser.parseString(source.read(name))).getOrThrow();
                for (int i = 0; i < config.passes().size(); i++) {
                    try {
                        createPass.invoke(null, null, config.passes().get(i), chainId.withSuffix("/" + i));
                    } catch (InvocationTargetException expected) {
                        // The captured compile future fails on purpose.
                    }
                }
            }
            LinkedHashMap<RenderPipeline, String> all = new LinkedHashMap<>();
            required.forEach(pipeline -> all.put(pipeline, "required"));
            optional.forEach(pipeline -> all.putIfAbsent(pipeline, "optional"));
            capturedPost.forEach(pipeline -> all.putIfAbsent(pipeline, "post_effect"));

            PipelineBuilder builder = new PipelineBuilder(backend);
            Method generate = PipelineBuilder.class.getDeclaredMethod("generateBackendCreateInfo",
                    RenderPipeline.class, ShaderSource.class, ReferenceArrayList.class, Object2IntOpenHashMap.class);
            generate.setAccessible(true);
            GlPipelineRecompiler recompiler = new GlPipelineRecompiler(null, false);
            int n = 0;
            for (Map.Entry<RenderPipeline, String> entry : all.entrySet()) {
                RenderPipeline pipeline = entry.getKey();
                String location = pipeline.getLocation().toString();
                ReferenceArrayList<BackendRenderPipeline.CreateInfo.Shader> shaders = new ReferenceArrayList<>();
                BackendRenderPipeline.CreateInfo createInfo = null;
                Map<BackendRenderPipeline.CreateInfo.Shader, String> glsl = null;
                String error = null;
                if (spvc != null) {
                    spvc.phase = location;
                }
                try {
                    createInfo = (BackendRenderPipeline.CreateInfo) generate.invoke(builder, pipeline, source, shaders,
                            new Object2IntOpenHashMap<String>());
                } catch (InvocationTargetException e) {
                    error = "frontend: " + e.getCause();
                }
                if (createInfo != null) {
                    glsl = recompiler.decompileShaders(createInfo);
                    if (glsl == null) {
                        error = "backend: decompileShaders returned null";
                    }
                }
                if (error == null) {
                    ok++;
                } else {
                    failed++;
                    System.out.println("FAIL " + location + ": " + error);
                }
                index.append(n++ == 0 ? "" : ",\n").append("{\"location\":").append(Corpus.q(location))
                        .append(",\"set\":").append(Corpus.q(entry.getValue()))
                        .append(",\"defines\":").append(Corpus.q(String.valueOf(pipeline.getShaderDefines())))
                        .append(",\"error\":").append(Corpus.q(error));
                if (createInfo != null) {
                    index.append(",\"uniforms\":[");
                    for (int i = 0; i < createInfo.uniforms().size(); i++) {
                        BindGroupLayout.UniformDescription uniform = createInfo.uniforms().get(i);
                        index.append(i == 0 ? "" : ",").append("{\"binding\":").append(i)
                                .append(",\"glName\":")
                                .append(Corpus.q(String.format(Locale.ROOT, "_uniform_%02d_%02d", 0, i)))
                                .append(",\"name\":").append(Corpus.q(uniform.name()))
                                .append(",\"type\":").append(Corpus.q(String.valueOf(uniform.type())))
                                .append(",\"format\":").append(Corpus.q(String.valueOf(uniform.gpuFormat())))
                                .append('}');
                    }
                    index.append("],\"pushConstantsSize\":").append(createInfo.pushConstantsSize())
                            .append(",\"attribBindings\":").append(Corpus.q(String.valueOf(createInfo.attribBindings())))
                            .append(",\"vertexBuffers\":").append(Corpus.q(String.valueOf(createInfo.vertexBuffers())))
                            .append(",\"shaders\":[");
                    int s = 0;
                    for (BackendRenderPipeline.CreateInfo.Shader shader : createInfo.shaders()) {
                        byte[] spirv = new byte[shader.module().spv().remaining()];
                        shader.module().spv().duplicate().get(spirv);
                        String text = glsl == null ? null : glsl.get(shader);
                        String glslHash = text == null ? null
                                : corpus.blob("glsl", ".glsl", text.getBytes(StandardCharsets.UTF_8));
                        index.append(s++ == 0 ? "" : ",").append("{\"name\":").append(Corpus.q(shader.name()))
                                .append(",\"type\":").append(Corpus.q(String.valueOf(shader.module().type())))
                                .append(",\"spirv\":\"").append(corpus.blob("spirv", ".spv", spirv)).append('"')
                                .append(",\"glsl\":").append(glslHash == null ? "null" : "\"" + glslHash + "\"");
                        if (translate != null && text != null) {
                            boolean fixed = text.equals(translate.invoke(null, text));
                            fixedPointFailures += fixed ? 0 : 1;
                            index.append(",\"translateFixedPoint\":").append(fixed);
                        }
                        index.append('}');
                        shaderCount++;
                    }
                    index.append(']');
                    for (BackendRenderPipeline.CreateInfo.Shader shader : createInfo.shaders()) {
                        shader.module().close();
                    }
                } else {
                    for (BackendRenderPipeline.CreateInfo.Shader shader : shaders) {
                        shader.module().close();
                    }
                }
                index.append('}');
            }
            index.append("\n]\n");
            if (builder instanceof AutoCloseable closeable) {
                closeable.close();
            }
            source.close();
        }
        corpus.write("pipelines.json", index.toString());
        if (spvc != null) {
            spvc.flushOpen();
        }
        if (shaderc != null) {
            shaderc.close();
        }
        corpus.close();
        double totalMs = (System.nanoTime() - started) / 1e6;
        StringBuilder summary = new StringBuilder("{\"mode\":").append(Corpus.q(mode))
                .append(",\"pipelines\":").append(ok + failed).append(",\"ok\":").append(ok)
                .append(",\"failed\":").append(failed).append(",\"shaders\":").append(shaderCount)
                .append(",\"translateChecked\":").append(translate != null)
                .append(",\"translateFixedPointFailures\":").append(fixedPointFailures)
                .append(",\"totalMs\":").append(String.format(Locale.ROOT, "%.1f", totalMs));
        if (shaderc != null) {
            summary.append(",\"shadercJobs\":").append(shaderc.jobs)
                    .append(",\"includeMisses\":").append(shaderc.includeMisses)
                    .append(",\"shadercNativeMs\":").append(String.format(Locale.ROOT, "%.1f", shaderc.nativeMs))
                    .append(",\"spvcSessions\":").append(spvc.sessions)
                    .append(",\"spvcCalls\":").append(spvc.calls)
                    .append(",\"spvcCompiles\":").append(spvc.compiles)
                    .append(",\"liveShadercHandles\":").append(BrowserShaderc.liveHandles())
                    .append(",\"liveSpvcContexts\":").append(BrowserSpvc.liveContexts());
        }
        summary.append("}\n");
        corpus.write("summary.json", summary.toString());
        System.out.print(summary);
        System.exit(0);
    }
}
