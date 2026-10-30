package dev.gaius.shaderxlat;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.serialization.JsonOps;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Minecraft 1.21.11 shader corpus, step 1 (on the 1.21.11 classpath): every static render
 * pipeline (RenderPipelines.getStaticPipelines) and every pass of assets/minecraft/post_effect/*.json
 * (built exactly as PostChain.createPass builds it), with the GLSL each stage would hand to the
 * GL driver (ShaderManager's GlslPreprocessor over the jar's includes, then
 * GlslPreprocessor.injectDefines with the pipeline's defines, as GlDevice does), plus the
 * pipeline state.
 *
 * <p>Writes meta.json and glsl/&lt;sha256&gt;.glsl; ShaderXlatCorpusDump262 (mode replay, on the 26.2
 * classpath) compiles them. usage: ShaderXlatCorpusDump12111 CLIENT_JAR OUTPUT_DIR
 */
public final class ShaderXlatCorpusDump12111 {
    private ShaderXlatCorpusDump12111() {
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

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    static String read(ZipFile zip, String entry) throws IOException {
        ZipEntry found = zip.getEntry(entry);
        return found == null ? null : new String(zip.getInputStream(found).readAllBytes(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Throwable {
        Path clientJar = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Path glslDir = out.resolve("glsl");
        Files.createDirectories(glslDir);
        net.minecraft.SharedConstants.tryDetectVersion();
        StringBuilder json = new StringBuilder("{\"profile\":")
                .append(quote(net.minecraft.SharedConstants.getCurrentVersion().id())).append(",\"pipelines\":[\n");
        int n = 0;
        int missing = 0;
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            // Every shader resource as ShaderManager sees it (Identifier -> Resource).
            Map<Identifier, Resource> files = new HashMap<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (name.startsWith("assets/minecraft/shaders/") && !name.endsWith("/")) {
                    byte[] bytes = zip.getInputStream(zip.getEntry(name)).readAllBytes();
                    files.put(Identifier.fromNamespaceAndPath("minecraft", name.substring("assets/minecraft/".length())),
                            new Resource(null, () -> new ByteArrayInputStream(bytes)));
                }
            }
            Method createPreprocessor = net.minecraft.client.renderer.ShaderManager.class
                    .getDeclaredMethod("createPreprocessor", Map.class, Identifier.class);
            createPreprocessor.setAccessible(true);

            LinkedHashMap<RenderPipeline, String> all = new LinkedHashMap<>();
            for (RenderPipeline p : RenderPipelines.getStaticPipelines()) {
                all.put(p, "static");
            }
            Field snippetField = RenderPipelines.class.getDeclaredField("POST_PROCESSING_SNIPPET");
            snippetField.setAccessible(true);
            RenderPipeline.Snippet postSnippet = (RenderPipeline.Snippet) snippetField.get(null);
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
                PostChainConfig config = PostChainConfig.CODEC.parse(JsonOps.INSTANCE,
                        JsonParser.parseString(read(zip, name))).getOrThrow();
                for (int i = 0; i < config.passes().size(); i++) {
                    PostChainConfig.Pass pass = config.passes().get(i);
                    // PostChain.createPass (1.21.11), bytecode 0..148.
                    RenderPipeline.Builder builder = RenderPipeline.builder(postSnippet)
                            .withFragmentShader(pass.fragmentShaderId())
                            .withVertexShader(pass.vertexShaderId())
                            .withLocation(Identifier.fromNamespaceAndPath("minecraft", "post_effect/" + chain + "/" + i));
                    for (PostChainConfig.Input input : pass.inputs()) {
                        builder.withSampler(input.samplerName() + "Sampler");
                    }
                    builder.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER);
                    for (String uniform : pass.uniforms().keySet()) {
                        builder.withUniform(uniform, UniformType.UNIFORM_BUFFER);
                    }
                    all.putIfAbsent(builder.build(), "post_effect");
                }
            }

            Map<String, String> sourceCache = new HashMap<>();
            for (Map.Entry<RenderPipeline, String> entry : all.entrySet()) {
                RenderPipeline p = entry.getKey();
                String[] hashes = new String[2];
                String[] errors = new String[2];
                for (int s = 0; s < 2; s++) {
                    Identifier id = s == 0 ? p.getVertexShader() : p.getFragmentShader();
                    String ext = s == 0 ? ".vsh" : ".fsh";
                    Identifier file = id.withPath("shaders/" + id.getPath() + ext);
                    String key = file.toString();
                    String processed = sourceCache.get(key);
                    if (processed == null) {
                        Resource resource = files.get(file);
                        if (resource == null) {
                            errors[s] = "missing " + file;
                            missing++;
                            continue;
                        }
                        String raw = new String(resource.open().readAllBytes(), StandardCharsets.UTF_8);
                        GlslPreprocessor pre = (GlslPreprocessor) createPreprocessor.invoke(null, files, file);
                        processed = String.join("", pre.process(raw));
                        sourceCache.put(key, processed);
                    }
                    String source = GlslPreprocessor.injectDefines(processed, p.getShaderDefines());
                    byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
                    hashes[s] = sha256(bytes);
                    Path blob = glslDir.resolve(hashes[s] + ".glsl");
                    if (!Files.exists(blob)) {
                        Files.write(blob, bytes);
                    }
                }
                json.append(n++ == 0 ? "" : ",\n").append('{');
                json.append("\"location\":").append(quote(p.getLocation().toString()));
                json.append(",\"set\":").append(quote(entry.getValue()));
                json.append(",\"defines\":").append(quote(String.valueOf(p.getShaderDefines())));
                json.append(",\"vertexShader\":").append(quote(p.getVertexShader().toString()));
                json.append(",\"fragmentShader\":").append(quote(p.getFragmentShader().toString()));
                json.append(",\"vertexGlsl\":").append(quote(hashes[0]));
                json.append(",\"fragmentGlsl\":").append(quote(hashes[1]));
                json.append(",\"errors\":[").append(quote(errors[0])).append(',').append(quote(errors[1])).append(']');
                json.append(",\"uniforms\":[");
                int u = 0;
                for (RenderPipeline.UniformDescription d : p.getUniforms()) {
                    json.append(u++ == 0 ? "" : ",").append("{\"name\":").append(quote(d.name()))
                            .append(",\"type\":").append(quote(d.type().name()))
                            .append(",\"textureFormat\":").append(quote(d.textureFormat() == null ? null : d.textureFormat().name()))
                            .append('}');
                }
                json.append("],\"samplers\":[");
                int sm = 0;
                for (String sampler : p.getSamplers()) {
                    json.append(sm++ == 0 ? "" : ",").append(quote(sampler));
                }
                VertexFormat format = p.getVertexFormat();
                json.append("],\"vertexSize\":").append(format.getVertexSize()).append(",\"vertexElements\":[");
                List<String> names = format.getElementAttributeNames();
                List<VertexFormatElement> elements = format.getElements();
                for (int i = 0; i < elements.size(); i++) {
                    VertexFormatElement el = elements.get(i);
                    json.append(i == 0 ? "" : ",").append("{\"name\":").append(quote(names.get(i)))
                            .append(",\"type\":").append(quote(el.type().name()))
                            .append(",\"usage\":").append(quote(el.usage().name()))
                            .append(",\"count\":").append(el.count())
                            .append(",\"offset\":").append(format.getOffset(el)).append('}');
                }
                json.append("],\"mode\":").append(quote(p.getVertexFormatMode().name()));
                BlendFunction blend = p.getBlendFunction().orElse(null);
                json.append(",\"blend\":");
                if (blend == null) {
                    json.append("null");
                } else {
                    json.append("{\"sourceColor\":").append(quote(blend.sourceColor().name()))
                            .append(",\"destColor\":").append(quote(blend.destColor().name()))
                            .append(",\"sourceAlpha\":").append(quote(blend.sourceAlpha().name()))
                            .append(",\"destAlpha\":").append(quote(blend.destAlpha().name())).append('}');
                }
                json.append(",\"depthTest\":").append(quote(p.getDepthTestFunction().name()));
                json.append(",\"writeDepth\":").append(p.isWriteDepth());
                json.append(",\"writeColor\":").append(p.isWriteColor());
                json.append(",\"writeAlpha\":").append(p.isWriteAlpha());
                json.append(",\"cull\":").append(p.isCull());
                json.append(",\"colorLogic\":").append(quote(p.getColorLogic().name()));
                json.append(",\"polygonMode\":").append(quote(p.getPolygonMode().name()));
                json.append(",\"depthBiasScaleFactor\":").append(p.getDepthBiasScaleFactor());
                json.append(",\"depthBiasConstant\":").append(p.getDepthBiasConstant());
                json.append(",\"wantsDepthTexture\":").append(p.wantsDepthTexture());
                json.append('}');
            }
        }
        json.append("\n],\"summary\":{\"pipelines\":").append(n).append(",\"missingSources\":").append(missing).append("}}\n");
        Files.writeString(out.resolve("meta.json"), json.toString());
        System.out.println("1.21.11 pipelines=" + n + " missingSources=" + missing);
    }
}
