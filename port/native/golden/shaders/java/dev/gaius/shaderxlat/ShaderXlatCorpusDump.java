package dev.gaius.shaderxlat;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Dumps the SPIR-V a renderpearl backend receives for every Minecraft 26.3 render
 * pipeline (the static RenderPipelines, required and optional, plus every pass of
 * assets/minecraft/post_effect/*.json), together with the pipeline state a WebGPU
 * backend needs to build it: bind group uniforms, vertex buffers and attributes,
 * color targets, depth state and topology.
 *
 * <p>The SPIR-V comes from the vanilla frontend (PipelineBuilder, GlslCompiler,
 * SPIRVModule) over the vanilla LWJGL shaderc natives, which the browser shader
 * toolchain reproduces byte for byte (shader-corpus-smoke.mjs, stage wasm). The
 * device profile selects the characteristics that reach the shader compiler:
 * <ul>
 *   <li>webgpu: depth range [0, 1] (RENDERPEARL_DEPTH_IS_ZERO_TO_ONE), no explicit
 *       depth invariance;</li>
 *   <li>gl: the WebGL2 device of BrowserOpenGL (depth range [-1, 1]).</li>
 * </ul>
 *
 * <p>Writes corpus.json and spirv/&lt;sha256&gt;.spv.
 *
 * <p>usage: ShaderXlatCorpusDump webgpu|gl CLIENT_JAR OUTPUT_DIR
 */
public final class ShaderXlatCorpusDump {
    private ShaderXlatCorpusDump() {
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
            } catch (IOException e) {
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
        return (T) Proxy.newProxyInstance(ShaderXlatCorpusDump.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Records, enums, optionals, collections, maps and scalars as JSON. */
    static void json(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Optional<?> optional) {
            json(out, optional.orElse(null));
        } else if (value instanceof Enum<?> constant) {
            out.append(quote(constant.name()));
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            out.append(value);
        } else if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            out.append(Double.isFinite(number) ? Double.toString(number) : quote(Double.toString(number)));
        } else if (value instanceof CharSequence text) {
            out.append(quote(text.toString()));
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.append(first ? "" : ",").append(quote(String.valueOf(entry.getKey()))).append(':');
                json(out, entry.getValue());
                first = false;
            }
            out.append('}');
        } else if (value instanceof Collection<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                out.append(first ? "" : ",");
                json(out, item);
                first = false;
            }
            out.append(']');
        } else if (value.getClass().isRecord()) {
            out.append('{');
            boolean first = true;
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                Object field;
                try {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    field = accessor.invoke(value);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                out.append(first ? "" : ",").append(quote(component.getName())).append(':');
                json(out, field);
                first = false;
            }
            out.append('}');
        } else {
            out.append(quote(String.valueOf(value)));
        }
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 3 || !Set.of("webgpu", "gl").contains(args[0])) {
            throw new IllegalArgumentException("usage: ShaderXlatCorpusDump webgpu|gl CLIENT_JAR OUTPUT_DIR");
        }
        String profile = args[0];
        Path clientJar = Path.of(args[1]);
        Path out = Path.of(args[2]);
        Path spirvDir = out.resolve("spirv");
        Files.createDirectories(spirvDir);
        long started = System.nanoTime();

        boolean zeroToOne = profile.equals("webgpu");
        DeviceFeatures features = new DeviceFeatures(false, false, false, true, false, false, false, false);
        HintsAndWorkarounds hints = new HintsAndWorkarounds(false, false, false, false);
        DeviceLimits limits = new DeviceLimits(1, 256, 4096, Long.MAX_VALUE, 0, 4, 0);
        DeviceInfo info = zeroToOne
                ? new DeviceInfo("WebGPU", "Gaius", "WebGPU", true, "WebGPU", 1.0f, limits, features, Set.of(), hints,
                        DeviceType.OTHER)
                : new DeviceInfo("WebKit WebGL", "WebKit", "OpenGL ES 3.0 (WebGL 2.0)", false, "OpenGL", 1.0f, limits,
                        features, Set.of(), hints, DeviceType.OTHER);
        List<RenderPipeline> capturedPost = new ArrayList<>();
        GpuDevice device = proxy(GpuDevice.class, (self, method, arguments) -> switch (method.getName()) {
            case "getDeviceInfo" -> info;
            case "compilePipeline" -> {
                capturedPost.add((RenderPipeline) arguments[0]);
                yield CompletableFuture.failedFuture(new IllegalStateException("captured by the corpus dump"));
            }
            case "toString" -> "ShaderXlatCorpusDevice";
            case "hashCode" -> 1;
            case "equals" -> self == arguments[0];
            default -> throw new UnsupportedOperationException("GpuDevice." + method.getName());
        });
        Field deviceField = RenderSystem.class.getDeclaredField("DEVICE");
        deviceField.setAccessible(true);
        deviceField.set(null, device);
        GpuDeviceBackend backend = proxy(GpuDeviceBackend.class, (self, method, arguments) -> switch (method.getName()) {
            case "getDeviceInfo" -> info;
            case "toString" -> "ShaderXlatCorpusBackend";
            case "hashCode" -> 2;
            case "equals" -> self == arguments[0];
            default -> throw new UnsupportedOperationException("GpuDeviceBackend." + method.getName());
        });

        int ok = 0;
        int failed = 0;
        int shaderCount = 0;
        Set<String> uniqueModules = new java.util.HashSet<>();
        StringBuilder index = new StringBuilder("{\"profile\":").append(quote(profile)).append(",\"pipelines\":[\n");
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
            int n = 0;
            for (Map.Entry<RenderPipeline, String> entry : all.entrySet()) {
                RenderPipeline pipeline = entry.getKey();
                ReferenceArrayList<BackendRenderPipeline.CreateInfo.Shader> shaders = new ReferenceArrayList<>();
                BackendRenderPipeline.CreateInfo createInfo = null;
                String error = null;
                try {
                    createInfo = (BackendRenderPipeline.CreateInfo) generate.invoke(builder, pipeline, source, shaders,
                            new Object2IntOpenHashMap<String>());
                } catch (InvocationTargetException e) {
                    error = "frontend: " + e.getCause();
                }
                if (error == null) {
                    ok++;
                } else {
                    failed++;
                    System.out.println("FAIL " + pipeline.getLocation() + ": " + error);
                }
                index.append(n++ == 0 ? "" : ",\n").append("{\"location\":")
                        .append(quote(pipeline.getLocation().toString()))
                        .append(",\"set\":").append(quote(entry.getValue()))
                        .append(",\"defines\":").append(quote(String.valueOf(pipeline.getShaderDefines())))
                        .append(",\"shaderIds\":");
                json(index, pipeline.getShaders());
                index.append(",\"error\":").append(quote(error));
                if (createInfo != null) {
                    index.append(",\"uniforms\":");
                    json(index, createInfo.uniforms());
                    index.append(",\"pushConstantsSize\":").append(createInfo.pushConstantsSize())
                            .append(",\"vertexBuffers\":");
                    json(index, createInfo.vertexBuffers());
                    index.append(",\"attribBindings\":");
                    json(index, createInfo.attribBindings());
                    index.append(",\"depthStencil\":");
                    json(index, createInfo.depthStencilState());
                    index.append(",\"polygonMode\":");
                    json(index, createInfo.polygonMode());
                    index.append(",\"cull\":").append(createInfo.cull()).append(",\"colorTargets\":");
                    json(index, createInfo.colorTargetStates());
                    index.append(",\"topology\":");
                    json(index, createInfo.primitiveTopology());
                    index.append(",\"wantsDepthTexture\":").append(pipeline.wantsDepthTexture())
                            .append(",\"shaders\":[");
                    int s = 0;
                    for (BackendRenderPipeline.CreateInfo.Shader shader : createInfo.shaders()) {
                        byte[] spirv = new byte[shader.module().spv().remaining()];
                        shader.module().spv().duplicate().get(spirv);
                        String hash = sha256(spirv);
                        Path blob = spirvDir.resolve(hash + ".spv");
                        if (uniqueModules.add(hash) && !Files.exists(blob)) {
                            Files.write(blob, spirv);
                        }
                        index.append(s++ == 0 ? "" : ",").append("{\"name\":").append(quote(shader.name()))
                                .append(",\"entryPoint\":").append(quote(shader.entryPoint()))
                                .append(",\"type\":").append(quote(String.valueOf(shader.module().type())))
                                .append(",\"spirv\":").append(quote(hash))
                                .append(",\"bytes\":").append(spirv.length).append('}');
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
            if (builder instanceof AutoCloseable closeable) {
                closeable.close();
            }
            source.close();
        }
        double totalMs = (System.nanoTime() - started) / 1e6;
        index.append("\n],\"summary\":{\"pipelines\":").append(ok + failed).append(",\"ok\":").append(ok)
                .append(",\"failed\":").append(failed).append(",\"shaders\":").append(shaderCount)
                .append(",\"uniqueModules\":").append(uniqueModules.size())
                .append(",\"totalMs\":").append(String.format(java.util.Locale.ROOT, "%.1f", totalMs)).append("}}\n");
        Files.writeString(out.resolve("corpus.json"), index.toString());
        System.out.println("corpus " + profile + ": pipelines=" + (ok + failed) + " ok=" + ok + " failed=" + failed
                + " shaders=" + shaderCount + " unique=" + uniqueModules.size());
        System.exit(failed == 0 ? 0 : 1);
    }
}
