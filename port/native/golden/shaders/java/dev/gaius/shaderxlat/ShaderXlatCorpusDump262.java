package dev.gaius.shaderxlat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.serialization.JsonOps;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

/**
 * The blaze3d shader corpus (26.2 and 1.21.11) for the GLSL front-end of gaius-shaderxlat
 * ({@code frontend::link}), on the 26.2 classpath.
 *
 * <p>Every pipeline's two stages go through the vanilla 26.2 Vulkan front half up to shaderc
 * exactly as VulkanDevice.compileShader + GlslCompiler.createIntermediary run it: ShaderSource
 * (ShaderManager's preprocessor over the jar's includes), GlslPreprocessor.injectDefines with the
 * pipeline's defines, then with GlslCompiler's global defines (gl_VertexID -> gl_VertexIndex,
 * gl_InstanceID -> gl_InstanceIndex), shaderc with GlslCompiler's own compiler and options (Vulkan
 * 1.2, auto bind uniforms, auto map locations, debug info, -O0). That shaderc output (the
 * "intermediate" modules) is what the browser's shaderc returns and what the Rust link takes.
 *
 * <ul>
 *   <li>Mode {@code 26.2}: the 26.2 static pipelines plus every post_effect pass (built as
 *       PostChain.createPass builds them). The vanilla rest of GlslCompiler.compile also runs
 *       (IntermediaryShaderModule.createFromSpirv, addToBindGroup, rebind), so the corpus carries
 *       the bound modules and bind group entries the vanilla Vulkan backend would use: the Rust
 *       link (strict) must reproduce them byte for byte.</li>
 *   <li>Mode {@code replay}: the 1.21.11 pipelines dumped by ShaderXlatCorpusDump12111, adapted
 *       into 26.2 RenderPipelines (same names, uniforms, samplers, state; the vertex format realigned
 *       for WebGPU, which is the 1.21.11 LineWidth REQUIRED patch) and compiled the same way after
 *       the one GLSL fix shaderc needs: identifiers named like GL_KHR_vulkan_glsl's sampler types
 *       get a gaius_ prefix. Every other GL difference is the Rust link's lenient mode.</li>
 *   <li>Mode {@code fixtures}: compiles every .vsh/.fsh of a directory the same way (the Rust
 *       front-end's own test shaders).</li>
 * </ul>
 *
 * <p>Writes frontend.json (the pipelines, their declared layout, the intermediate module hashes
 * and, mode 26.2, the vanilla result) and spirv/&lt;sha256&gt;.spv; the crate binary
 * shaderxlat-link turns it into the corpus.json of shaderxlat-corpus and xlat-check.mjs.
 *
 * <p>usage: ShaderXlatCorpusDump262 26.2 CLIENT_JAR OUT | replay META_DIR OUT | fixtures DIR OUT
 */
public final class ShaderXlatCorpusDump262 {
    private ShaderXlatCorpusDump262() {
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

    static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    static Object call(Object target, String name) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /** GlslCompiler's shaderc compiler, options and global defines. */
    static final class Front {
        final GlslCompiler compiler;
        final long shaderCompiler;
        final long shaderOptions;
        final ShaderDefines globalDefines;
        final Method addToBindGroup;

        Front(GlslCompiler compiler) throws Exception {
            this.compiler = compiler;
            this.shaderCompiler = (Long) field(compiler, "shaderCompiler");
            this.shaderOptions = (Long) field(compiler, "shaderOptions");
            this.globalDefines = (ShaderDefines) field(compiler, "globalDefines");
            this.addToBindGroup = GlslCompiler.class.getDeclaredMethod("addToBindGroup", List.class,
                    IntermediaryShaderModule.class, RenderPipeline.class);
            this.addToBindGroup.setAccessible(true);
        }

        /** GlslCompiler.createIntermediary up to the shaderc result: the raw module bytes. */
        byte[] shaderc(String name, String source, ShaderType type) {
            String text = GlslPreprocessor.injectDefines(source, globalDefines);
            ByteBuffer sourceBytes = MemoryUtil.memUTF8(text, false);
            ByteBuffer nameBytes = MemoryUtil.memUTF8(name);
            ByteBuffer entryBytes = MemoryUtil.memUTF8("main");
            long result = Shaderc.shaderc_compile_into_spv(shaderCompiler, sourceBytes,
                    type == ShaderType.FRAGMENT ? 1 : 0, nameBytes, entryBytes, shaderOptions);
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
                    throw new IllegalStateException("Couldn't compile shader: "
                            + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                byte[] out = new byte[bytes.remaining()];
                bytes.duplicate().get(out);
                return out;
            } finally {
                Shaderc.shaderc_result_release(result);
                MemoryUtil.memFree(entryBytes);
                MemoryUtil.memFree(nameBytes);
                MemoryUtil.memFree(sourceBytes);
            }
        }

        static IntermediaryShaderModule intermediary(String name, byte[] spirv) throws Exception {
            ByteBuffer buffer = MemoryUtil.memCalloc(spirv.length);
            buffer.put(0, spirv);
            return IntermediaryShaderModule.createFromSpirv(name, buffer);
        }

        static byte[] bytes(IntermediaryShaderModule module) {
            byte[] out = new byte[module.spirv().remaining()];
            module.spirv().duplicate().get(out);
            return out;
        }

        /** The rest of GlslCompiler.compile (minus vkCreateShaderModule) on the shaderc output. */
        Vanilla link(RenderPipeline pipeline, String vsName, byte[] vs, String fsName, byte[] fs) throws Exception {
            IntermediaryShaderModule vertex = intermediary(vsName, vs);
            IntermediaryShaderModule fragment = intermediary(fsName, fs);
            try {
                List<VulkanBindGroupLayout.Entry> entries = new ArrayList<>();
                try {
                    addToBindGroup.invoke(null, entries, vertex, pipeline);
                    addToBindGroup.invoke(null, entries, fragment, pipeline);
                } catch (InvocationTargetException e) {
                    throw (Exception) e.getCause();
                }
                List<String> outputs = new ArrayList<>();
                for (Object v : vertex.outputs()) {
                    outputs.add((String) call(v, "name"));
                }
                fragment.rebind(outputs, entries);
                vertex.rebind(attributes(pipeline).stream().map(a -> a.name()).toList(), entries);
                return new Vanilla(entries, bytes(vertex), bytes(fragment));
            } finally {
                vertex.close();
                fragment.close();
            }
        }
    }

    /** The vanilla link's bind group entries and bound modules (with their blob hashes once written). */
    static final class Vanilla {
        final List<VulkanBindGroupLayout.Entry> entries;
        final byte[] vertex;
        final byte[] fragment;
        String vertexHash;
        String fragmentHash;

        Vanilla(List<VulkanBindGroupLayout.Entry> entries, byte[] vertex, byte[] fragment) {
            this.entries = entries;
            this.vertex = vertex;
            this.fragment = fragment;
        }
    }

    record Attribute(String name, String format) {
    }

    /** Every element of every vertex format binding, in slot order (what rebind numbers). */
    static List<Attribute> attributes(RenderPipeline pipeline) {
        List<Attribute> out = new ArrayList<>();
        for (VertexFormat format : pipeline.getVertexFormatBindings()) {
            if (format == null) {
                continue;
            }
            for (VertexFormatElement element : format.getElements()) {
                out.add(new Attribute(element.name(), element.format().name()));
            }
        }
        return out;
    }

    static final class Blobs {
        final Path dir;
        final Set<String> written = new HashSet<>();

        Blobs(Path dir) {
            this.dir = dir;
        }

        String put(byte[] spirv) throws Exception {
            String hash = sha256(spirv);
            if (written.add(hash)) {
                Files.write(dir.resolve(hash + ".spv"), spirv);
            }
            return hash;
        }
    }

    static void appendBlend(StringBuilder json, BlendFunction blend) {
        if (blend == null) {
            json.append("null");
            return;
        }
        json.append("{\"color\":{\"sourceFactor\":").append(quote(blend.color().sourceFactor().name()))
                .append(",\"destFactor\":").append(quote(blend.color().destFactor().name()))
                .append(",\"op\":").append(quote(blend.color().op().name()))
                .append("},\"alpha\":{\"sourceFactor\":").append(quote(blend.alpha().sourceFactor().name()))
                .append(",\"destFactor\":").append(quote(blend.alpha().destFactor().name()))
                .append(",\"op\":").append(quote(blend.alpha().op().name())).append("}}");
    }

    static void appendEntries(StringBuilder json, List<VulkanBindGroupLayout.Entry> entries) {
        json.append('[');
        int i = 0;
        for (VulkanBindGroupLayout.Entry e : entries) {
            String type = switch (e.type()) {
                case UNIFORM_BUFFER -> "UNIFORM_BUFFER";
                case TEXEL_BUFFER -> "TEXEL_BUFFER";
                case SAMPLED_IMAGE -> "COMBINED_IMAGE_SAMPLER";
            };
            json.append(i++ == 0 ? "" : ",").append("{\"name\":").append(quote(e.name()))
                    .append(",\"type\":").append(quote(type))
                    .append(",\"gpuFormat\":").append(quote(e.texelBufferFormat() == null ? null : e.texelBufferFormat().name()))
                    .append('}');
        }
        json.append(']');
    }

    /**
     * One frontend.json pipeline: the S4 corpus fields of the pipeline state, the declared layout,
     * the intermediate modules and (mode 26.2) the vanilla result.
     */
    static void appendPipeline(StringBuilder json, RenderPipeline p, String set, String vsId, String fsId,
            String[] intermediate, Vanilla vanilla, String vanillaError, String error, String extra) {
        json.append('{').append("\"location\":").append(quote(p.getLocation().toString()))
                .append(",\"set\":").append(quote(set))
                .append(",\"defines\":").append(quote(String.valueOf(p.getShaderDefines())))
                .append(",\"shaderIds\":{\"VERTEX\":").append(quote(vsId)).append(",\"FRAGMENT\":").append(quote(fsId))
                .append("},\"error\":").append(quote(error));
        if (extra != null) {
            json.append(',').append(extra);
        }
        json.append(",\"layout\":{\"uniforms\":[");
        int i = 0;
        for (BindGroupLayout.UniformDescription u : BindGroupLayout.flattenUniforms(p.getBindGroupLayouts())) {
            json.append(i++ == 0 ? "" : ",").append("{\"name\":").append(quote(u.name()))
                    .append(",\"type\":").append(quote(u.type().name()))
                    .append(",\"gpuFormat\":").append(quote(u.gpuFormat() == null ? null : u.gpuFormat().name()))
                    .append('}');
        }
        json.append("],\"samplers\":[");
        i = 0;
        for (String sampler : BindGroupLayout.flattenSamplers(p.getBindGroupLayouts())) {
            json.append(i++ == 0 ? "" : ",").append(quote(sampler));
        }
        json.append("],\"attributes\":[");
        i = 0;
        for (Attribute a : attributes(p)) {
            json.append(i++ == 0 ? "" : ",").append("{\"name\":").append(quote(a.name()))
                    .append(",\"format\":").append(quote(a.format())).append('}');
        }
        json.append("]}");
        if (intermediate != null) {
            json.append(",\"intermediate\":{\"VERTEX\":").append(quote(intermediate[0]))
                    .append(",\"FRAGMENT\":").append(quote(intermediate[1])).append('}');
        }
        if (vanilla != null) {
            json.append(",\"vanilla\":{\"uniforms\":");
            appendEntries(json, vanilla.entries);
            json.append(",\"spirv\":{\"VERTEX\":").append(quote(vanilla.vertexHash))
                    .append(",\"FRAGMENT\":").append(quote(vanilla.fragmentHash)).append("}}");
        }
        if (vanillaError != null) {
            json.append(",\"vanillaError\":").append(quote(vanillaError));
        }
        json.append(",\"pushConstantsSize\":0,\"vertexBuffers\":[");
        VertexFormat[] bindings = p.getVertexFormatBindings();
        int vb = 0;
        for (int slot = 0; slot < bindings.length; slot++) {
            if (bindings[slot] == null) {
                continue;
            }
            json.append(vb++ == 0 ? "" : ",").append("{\"bufferSlot\":").append(slot)
                    .append(",\"stride\":").append(bindings[slot].getVertexSize())
                    .append(",\"stepRate\":").append(bindings[slot].getStepRate()).append('}');
        }
        json.append("],\"attribBindings\":[");
        int location = 0;
        for (int slot = 0; slot < bindings.length; slot++) {
            if (bindings[slot] == null) {
                continue;
            }
            for (VertexFormatElement el : bindings[slot].getElements()) {
                json.append(location == 0 ? "" : ",").append("{\"bufferSlot\":").append(slot)
                        .append(",\"location\":").append(location)
                        .append(",\"offset\":").append(el.offset())
                        .append(",\"format\":").append(quote(el.format().name()))
                        .append(",\"name\":").append(quote(el.name())).append('}');
                location++;
            }
        }
        DepthStencilState ds = p.getDepthStencilState();
        json.append("],\"depthStencil\":");
        if (ds == null) {
            json.append("null");
        } else {
            json.append("{\"depthTest\":").append(quote(ds.depthTest().name()))
                    .append(",\"writeDepth\":").append(ds.writeDepth())
                    .append(",\"depthBiasScaleFactor\":").append(ds.depthBiasScaleFactor())
                    .append(",\"depthBiasConstant\":").append(ds.depthBiasConstant()).append('}');
        }
        json.append(",\"polygonMode\":").append(quote(p.getPolygonMode().name()))
                .append(",\"cull\":").append(p.isCull()).append(",\"colorTargets\":[");
        int c = 0;
        for (ColorTargetState target : p.getColorTargetStates()) {
            json.append(c++ == 0 ? "" : ",");
            if (target == null) {
                json.append("null");
                continue;
            }
            json.append("{\"blendFunction\":");
            appendBlend(json, target.blendFunction().orElse(null));
            json.append(",\"format\":").append(quote(target.format().name()))
                    .append(",\"writeMask\":").append(target.writeMask()).append('}');
        }
        json.append("],\"topology\":").append(quote(p.getPrimitiveTopology().name()))
                .append(",\"wantsDepthTexture\":").append(p.wantsDepthTexture()).append('}');
    }

    /** One pipeline through shaderc and (mode 26.2) the vanilla link. */
    static final class Result {
        String[] intermediate;
        Vanilla vanilla;
        String vanillaError;
        String error;
    }

    static Result compile(Front front, Blobs blobs, RenderPipeline p, String vsName, String vs, String fsName,
            String fs, boolean withVanilla) throws Exception {
        Result r = new Result();
        byte[] vertex;
        byte[] fragment;
        try {
            vertex = front.shaderc(vsName, vs, ShaderType.VERTEX);
            fragment = front.shaderc(fsName, fs, ShaderType.FRAGMENT);
        } catch (RuntimeException e) {
            r.error = "shaderc: " + e.getMessage();
            return r;
        }
        r.intermediate = new String[] {blobs.put(vertex), blobs.put(fragment)};
        if (withVanilla) {
            try {
                Vanilla v = front.link(p, vsName, vertex, fsName, fragment);
                v.vertexHash = blobs.put(v.vertex);
                v.fragmentHash = blobs.put(v.fragment);
                r.vanilla = v;
            } catch (Exception e) {
                r.vanillaError = e.getMessage();
            }
        }
        return r;
    }

    public static void main(String[] args) throws Throwable {
        net.minecraft.SharedConstants.tryDetectVersion();
        String clientProfile = net.minecraft.SharedConstants.getCurrentVersion().id();
        String mode = args[0];
        Path out = Path.of(args[2]);
        Path spirvDir = out.resolve("spirv");
        if (!mode.equals("fixtures")) {
            Files.createDirectories(spirvDir);
        }
        Blobs blobs = new Blobs(spirvDir);
        long started = System.nanoTime();
        int compiled = 0;
        int failed = 0;
        int vanillaOk = 0;
        StringBuilder json = new StringBuilder();
        try (GlslCompiler compiler = new GlslCompiler()) {
            Front front = new Front(compiler);
            switch (mode.equals(clientProfile) ? 0 : mode.equals("replay") ? 1 : mode.equals("fixtures") ? 2 : -1) {
                case 0 -> {
                    json.append("{\"profile\":").append(quote(clientProfile)).append(",\"mode\":\"strict\",\"frontend\":")
                            .append(quote(clientProfile + " pipelines, vanilla " + clientProfile + " Vulkan GlslCompiler up to shaderc")).append(",\"pipelines\":[\n");
                    int n = 0;
                    for (Map.Entry<RenderPipeline, String> entry : pipelines262(Path.of(args[1])).entrySet()) {
                        RenderPipeline p = entry.getKey();
                        String[] sources = sources262.get(p);
                        Result r = compile(front, blobs, p, p.getVertexShader().toDebugFileName(), sources[0],
                                p.getFragmentShader().toDebugFileName(), sources[1], true);
                        if (r.error == null) {
                            compiled++;
                        } else {
                            failed++;
                            System.out.println("FAIL " + p.getLocation() + ": " + r.error);
                        }
                        if (r.vanilla != null) {
                            vanillaOk++;
                        } else if (r.vanillaError != null) {
                            System.out.println("VANILLA FAIL " + p.getLocation() + ": " + r.vanillaError);
                        }
                        json.append(n++ == 0 ? "" : ",\n");
                        appendPipeline(json, p, entry.getValue(), p.getVertexShader().toString(),
                                p.getFragmentShader().toString(), r.intermediate, r.vanilla, r.vanillaError, r.error, null);
                    }
                }
                case 1 -> {
                    Path metaDir = Path.of(args[1]);
                    JsonObject meta = JsonParser.parseString(Files.readString(metaDir.resolve("meta.json"))).getAsJsonObject();
                    String sourceProfile = meta.get("profile").getAsString();
                    json.append("{\"profile\":").append(quote(sourceProfile)).append(",\"mode\":\"lenient\",\"frontend\":")
                            .append(quote(sourceProfile + " pipelines adapted to " + clientProfile + " RenderPipeline, vanilla " + clientProfile + " Vulkan GlslCompiler up to shaderc"))
                            .append(",\"pipelines\":[\n");
                    int n = 0;
                    for (JsonElement element : meta.getAsJsonArray("pipelines")) {
                        JsonObject m = element.getAsJsonObject();
                        List<String> realigned = new ArrayList<>();
                        RenderPipeline p = adapt(m, realigned);
                        String vs = Files.readString(metaDir.resolve("glsl").resolve(m.get("vertexGlsl").getAsString() + ".glsl"));
                        String fs = Files.readString(metaDir.resolve("glsl").resolve(m.get("fragmentGlsl").getAsString() + ".glsl"));
                        List<String> sourceFixes = new ArrayList<>();
                        String[] renamed = renameReservedWords(vs, fs, sourceFixes);
                        Result r = compile(front, blobs, p, Identifier.parse(m.get("vertexShader").getAsString()).toDebugFileName(),
                                renamed[0], Identifier.parse(m.get("fragmentShader").getAsString()).toDebugFileName(), renamed[1],
                                false);
                        if (r.error == null) {
                            compiled++;
                        } else {
                            failed++;
                            System.out.println("FAIL " + p.getLocation() + ": " + r.error);
                        }
                        String extra = "\"legacy\":{\"depthTest\":" + quote(m.get("depthTest").getAsString())
                                + ",\"mode\":" + quote(m.get("mode").getAsString())
                                + ",\"colorLogic\":" + quote(m.get("colorLogic").getAsString())
                                + ",\"defines\":" + quote(m.get("defines").getAsString())
                                + ",\"realignedAttributes\":" + quote(String.join(" ", realigned))
                                + ",\"sourceFixes\":" + quote(String.join(" ", sourceFixes)) + "}";
                        json.append(n++ == 0 ? "" : ",\n");
                        appendPipeline(json, p, m.get("set").getAsString(), m.get("vertexShader").getAsString(),
                                m.get("fragmentShader").getAsString(), r.intermediate, null, null, r.error, extra);
                    }
                }
                case 2 -> {
                    Path dir = Path.of(args[1]);
                    List<Path> files;
                    try (Stream<Path> list = Files.list(dir)) {
                        files = list.filter(f -> f.toString().endsWith(".vsh") || f.toString().endsWith(".fsh")).sorted().toList();
                    }
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        ShaderType type = name.endsWith(".vsh") ? ShaderType.VERTEX : ShaderType.FRAGMENT;
                        byte[] spirv = front.shaderc(name, Files.readString(file), type);
                        Files.write(out.resolve(name.substring(0, name.length() - 4) + (type == ShaderType.VERTEX ? ".vert" : ".frag")
                                + ".spv"), spirv);
                        compiled++;
                    }
                    System.out.println("fixtures: " + compiled);
                    return;
                }
                default -> throw new IllegalArgumentException("mode " + clientProfile + "|replay|fixtures");
            }
        }
        double totalMs = (System.nanoTime() - started) / 1e6;
        json.append("\n],\"summary\":{\"pipelines\":").append(compiled + failed).append(",\"compiled\":").append(compiled)
                .append(",\"failed\":").append(failed).append(",\"vanillaOk\":").append(vanillaOk)
                .append(",\"uniqueModules\":").append(blobs.written.size())
                .append(",\"totalMs\":").append(String.format(Locale.ROOT, "%.1f", totalMs)).append("}}\n");
        Files.writeString(out.resolve("frontend.json"), json.toString());
        System.out.println("frontend corpus " + mode + ": pipelines=" + (compiled + failed) + " compiled=" + compiled
                + " failed=" + failed + " vanillaOk=" + vanillaOk + " modules=" + blobs.written.size());
    }

    // ---------------------------------------------------------------------------------------
    // 26.2: the static pipelines and the post_effect passes, with their preprocessed sources.

    static final Map<RenderPipeline, String[]> sources262 = new HashMap<>();

    static LinkedHashMap<RenderPipeline, String> pipelines262(Path clientJar) throws Exception {
        LinkedHashMap<RenderPipeline, String> all = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            Map<Identifier, Resource> files = new HashMap<>();
            Map<String, String> posts = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                names.add(e.nextElement().getName());
            }
            names.sort(null);
            for (String name : names) {
                if (name.startsWith("assets/minecraft/shaders/") && !name.endsWith("/")) {
                    byte[] bytes = zip.getInputStream(zip.getEntry(name)).readAllBytes();
                    files.put(Identifier.fromNamespaceAndPath("minecraft", name.substring("assets/minecraft/".length())),
                            new Resource(null, () -> new ByteArrayInputStream(bytes)));
                } else if (name.startsWith("assets/minecraft/post_effect/") && name.endsWith(".json")) {
                    posts.put(name.substring("assets/minecraft/post_effect/".length(), name.length() - 5),
                            new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            Method createPreprocessor = net.minecraft.client.renderer.ShaderManager.class
                    .getDeclaredMethod("createPreprocessor", Map.class, Identifier.class);
            createPreprocessor.setAccessible(true);
            for (RenderPipeline p : RenderPipelines.getStaticPipelines()) {
                all.put(p, "static");
            }
            Field snippetField = RenderPipelines.class.getDeclaredField("POST_PROCESSING_SNIPPET");
            snippetField.setAccessible(true);
            RenderPipeline.Snippet postSnippet = (RenderPipeline.Snippet) snippetField.get(null);
            for (Map.Entry<String, String> post : posts.entrySet()) {
                PostChainConfig config = PostChainConfig.CODEC.parse(JsonOps.INSTANCE,
                        JsonParser.parseString(post.getValue())).getOrThrow();
                for (int i = 0; i < config.passes().size(); i++) {
                    PostChainConfig.Pass pass = config.passes().get(i);
                    // PostChain.createPass (26.2), bytecode 0..165.
                    BindGroupLayout.Builder layout = BindGroupLayout.builder();
                    for (PostChainConfig.Input input : pass.inputs()) {
                        layout.withSampler(input.samplerName() + "Sampler");
                    }
                    layout.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER);
                    for (String uniform : pass.uniforms().keySet()) {
                        layout.withUniform(uniform, UniformType.UNIFORM_BUFFER);
                    }
                    RenderPipeline pipeline = RenderPipeline.builder(postSnippet)
                            .withFragmentShader(pass.fragmentShaderId())
                            .withVertexShader(pass.vertexShaderId())
                            .withLocation(Identifier.fromNamespaceAndPath("minecraft", "post_effect/" + post.getKey() + "/" + i))
                            .withBindGroupLayout(layout.build())
                            .build();
                    all.putIfAbsent(pipeline, "post_effect");
                }
            }
            Map<String, String> processedCache = new HashMap<>();
            for (RenderPipeline p : all.keySet()) {
                String[] sources = new String[2];
                for (int s = 0; s < 2; s++) {
                    Identifier id = s == 0 ? p.getVertexShader() : p.getFragmentShader();
                    Identifier file = id.withPath("shaders/" + id.getPath() + (s == 0 ? ".vsh" : ".fsh"));
                    String processed = processedCache.get(file.toString());
                    if (processed == null) {
                        String raw = new String(files.get(file).open().readAllBytes(), StandardCharsets.UTF_8);
                        GlslPreprocessor pre = (GlslPreprocessor) createPreprocessor.invoke(null, files, file);
                        processed = String.join("", pre.process(raw));
                        processedCache.put(file.toString(), processed);
                    }
                    // VulkanDevice.compileShader: the pipeline's defines.
                    sources[s] = GlslPreprocessor.injectDefines(processed, p.getShaderDefines());
                }
                sources262.put(p, sources);
            }
        }
        return all;
    }

    // ---------------------------------------------------------------------------------------
    // 1.21.11 -> 26.2: what the legacy-12111 seam does in front of the shared front-end.

    /**
     * GL_KHR_vulkan_glsl reserves sampler, samplerShadow and subpassInput as type names; GL code may
     * use them as identifiers (1.21.11 terrain.fsh: {@code sampleNearest(sampler2D sampler, ...)}).
     * Renames every such identifier that is not a constructor call to gaius_&lt;word&gt;.
     */
    static String[] renameReservedWords(String vs, String fs, List<String> fixes) {
        String[] out = {vs, fs};
        for (String word : List.of("sampler", "samplerShadow", "subpassInput")) {
            Pattern re = Pattern.compile("\\b" + word + "\\b(?!\\s*\\()");
            if (re.matcher(out[0]).find() || re.matcher(out[1]).find()) {
                fixes.add(word);
                out[0] = re.matcher(out[0]).replaceAll("gaius_" + word);
                out[1] = re.matcher(out[1]).replaceAll("gaius_" + word);
            }
        }
        return out;
    }

    static GpuFormat vertexFormat(String type, String usage, int count) {
        return switch (type) {
            case "FLOAT" -> switch (count) {
                case 1 -> GpuFormat.R32_FLOAT;
                case 2 -> GpuFormat.RG32_FLOAT;
                case 3 -> GpuFormat.RGB32_FLOAT;
                default -> GpuFormat.RGBA32_FLOAT;
            };
            // GL: COLOR/NORMAL are normalized (glVertexAttribFormat normalized=true).
            case "UBYTE" -> usage.equals("COLOR") ? GpuFormat.RGBA8_UNORM : GpuFormat.RGBA8_UINT;
            // NORMAL is 3 bytes plus one byte of padding in every vanilla format: read it as snorm8x4.
            case "BYTE" -> GpuFormat.RGBA8_SNORM;
            // GL: UV with a non-float type goes through glVertexAttribIFormat (ivec2 UV1/UV2).
            case "SHORT" -> count == 2 ? GpuFormat.RG16_SINT : GpuFormat.RGBA16_SINT;
            case "USHORT" -> count == 2 ? GpuFormat.RG16_UINT : GpuFormat.RGBA16_UINT;
            case "INT" -> count == 1 ? GpuFormat.R32_SINT : GpuFormat.RG32_SINT;
            case "UINT" -> count == 1 ? GpuFormat.R32_UINT : GpuFormat.RG32_UINT;
            default -> throw new IllegalArgumentException(type);
        };
    }

    static RenderPipeline adapt(JsonObject m, List<String> realigned) {
        RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Identifier.parse(m.get("location").getAsString()))
                .withVertexShader(Identifier.parse(m.get("vertexShader").getAsString()))
                .withFragmentShader(Identifier.parse(m.get("fragmentShader").getAsString()));
        BindGroupLayout.Builder layout = BindGroupLayout.builder();
        for (JsonElement u : m.getAsJsonArray("uniforms")) {
            JsonObject o = u.getAsJsonObject();
            if (o.get("type").getAsString().equals("TEXEL_BUFFER")) {
                String tf = o.get("textureFormat").getAsString();
                GpuFormat f = switch (tf) {
                    case "RED8I" -> GpuFormat.R8_SINT;
                    case "RED8" -> GpuFormat.R8_UNORM;
                    case "RGBA8" -> GpuFormat.RGBA8_UNORM;
                    default -> throw new IllegalArgumentException(tf);
                };
                layout.withUniform(o.get("name").getAsString(), UniformType.TEXEL_BUFFER, f);
            } else {
                layout.withUniform(o.get("name").getAsString(), UniformType.UNIFORM_BUFFER);
            }
        }
        for (JsonElement s : m.getAsJsonArray("samplers")) {
            layout.withSampler(s.getAsString());
        }
        b.withBindGroupLayout(layout.build());
        JsonArray elements = m.getAsJsonArray("vertexElements");
        VertexFormat.Builder vf = VertexFormat.builder(0);
        int vertexSize = m.get("vertexSize").getAsInt();
        // WebGPU (and the 26.2 VertexFormat) need each attribute offset aligned to min(4, size); GL 1.21.11 does
        // not (POSITION_COLOR_NORMAL_LINE_WIDTH puts the float LineWidth at offset 19). Realign by padding.
        int n = elements.size();
        GpuFormat[] formats = new GpuFormat[n];
        int[] offsets = new int[n + 1];
        int shift = 0;
        for (int i = 0; i < n; i++) {
            JsonObject o = elements.get(i).getAsJsonObject();
            formats[i] = vertexFormat(o.get("type").getAsString(), o.get("usage").getAsString(), o.get("count").getAsInt());
            int align = Math.min(4, formats[i].blockSize());
            int off = o.get("offset").getAsInt() + shift;
            int aligned = (off + align - 1) / align * align;
            if (aligned != off) {
                realigned.add(o.get("name").getAsString() + "@" + o.get("offset").getAsInt() + "->" + aligned);
                shift += aligned - off;
            }
            offsets[i] = aligned;
        }
        offsets[n] = (vertexSize + shift + 3) / 4 * 4;
        for (int i = 0; i < n; i++) {
            JsonObject o = elements.get(i).getAsJsonObject();
            // addAttribute(name, bytes, format): at the current offset, then offset += bytes.
            vf.addAttribute(o.get("name").getAsString(), offsets[i + 1] - offsets[i], formats[i]);
        }
        if (!elements.isEmpty()) {
            b.withVertexBinding(0, vf.build());
        }
        b.withPrimitiveTopology(PrimitiveTopology.valueOf(m.get("mode").getAsString()));
        JsonElement blend = m.get("blend");
        Optional<BlendFunction> fn = blend.isJsonNull() ? Optional.empty()
                : Optional.of(new BlendFunction(
                        BlendFactor.valueOf(blend.getAsJsonObject().get("sourceColor").getAsString()),
                        BlendFactor.valueOf(blend.getAsJsonObject().get("destColor").getAsString()),
                        BlendFactor.valueOf(blend.getAsJsonObject().get("sourceAlpha").getAsString()),
                        BlendFactor.valueOf(blend.getAsJsonObject().get("destAlpha").getAsString())));
        int mask = (m.get("writeColor").getAsBoolean() ? ColorTargetState.WRITE_COLOR : 0)
                | (m.get("writeAlpha").getAsBoolean() ? ColorTargetState.WRITE_ALPHA : 0);
        b.withColorTargetState(new ColorTargetState(fn, GpuFormat.RGBA8_UNORM, mask));
        String depth = m.get("depthTest").getAsString();
        boolean writeDepth = m.get("writeDepth").getAsBoolean();
        CompareOp op = switch (depth) {
            case "NO_DEPTH_TEST" -> CompareOp.ALWAYS_PASS;
            case "EQUAL_DEPTH_TEST" -> CompareOp.EQUAL;
            case "LEQUAL_DEPTH_TEST" -> CompareOp.LESS_THAN_OR_EQUAL;
            case "LESS_DEPTH_TEST" -> CompareOp.LESS_THAN;
            case "GREATER_DEPTH_TEST" -> CompareOp.GREATER_THAN;
            default -> throw new IllegalArgumentException(depth);
        };
        // GL: a disabled depth test also disables depth writes.
        b.withDepthStencilState(new DepthStencilState(op, depth.equals("NO_DEPTH_TEST") ? false : writeDepth,
                m.get("depthBiasScaleFactor").getAsFloat(), m.get("depthBiasConstant").getAsFloat()));
        b.withPolygonMode(PolygonMode.valueOf(m.get("polygonMode").getAsString()));
        b.withCull(m.get("cull").getAsBoolean());
        return b.build();
    }
}
