package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * The blaze3d GLSL front-end of the 26.2 and 1.21.11 render seams: what the vanilla 26.2 Vulkan
 * {@code GlslCompiler} does between the preprocessed GLSL and {@code vkCreateShaderModule}, in the
 * browser. Keyed by dialect (the link's {@code lenient} rules), not by version.
 *
 * <ol>
 * <li>Each stage is compiled by shaderc with {@code GlslCompiler}'s options: Vulkan 1.2, auto
 * bind uniforms, debug information, {@code -O0}. The browser's {@code gaius-shaderc.wasm} has no
 * {@code auto_map_locations}, so {@link GlslLocations} writes the locations auto-mapping assigns
 * into the source and restores the module afterwards: the SPIR-V is byte for byte what shaderc
 * with the option returns.</li>
 * <li>The two modules go through {@code gaius-shaderxlat}'s {@code frontend::link} (kind 0x0502;
 * reflection, {@code addToBindGroup}, {@code rebind}) with the pipeline's declared layout. Its
 * bind group entries become the pipeline's uniforms; entry {@code i} is binding {@code i} and the
 * vanilla uniform index for the translator.</li>
 * </ol>
 *
 * <p>By default shaderc is the page's shader toolchain ({@code window.__gaiusShaderToolchain},
 * {@code port/wasm/shader-toolchain/loader}) and the link the {@code link} function of the
 * translator the page installs on the glue ({@code GaiusWebGpu.translator}, a
 * {@code GaiusShaderXlatJob} instance, {@code port/native/golden/shaders/shaderxlat-job.js}). Both
 * run synchronously ({@code precompilePipeline} never suspends, D11) and can be replaced, e.g. by
 * JVM tests.</p>
 */
public final class GlslFrontEnd {
    /** {@code shaderc_target_env_vulkan} and {@code shaderc_env_version_vulkan_1_2}. */
    static final int TARGET_ENV_VULKAN = 0;
    static final int ENV_VERSION_VULKAN_1_2 = 4202496;
    private static final int MAX_CACHED_STAGES = 512;

    private static final Map<String, byte[]> STAGES = new HashMap<>();
    private static StageCompiler compiler = GlslFrontEnd::compileInToolchain;
    private static Linker linker = GlslFrontEnd::linkInTranslator;
    private static int compiles;
    private static int cacheHits;
    private static int links;
    private static long compileNanos;
    private static long linkNanos;

    private GlslFrontEnd() {
    }

    /** Compiles one stage to SPIR-V with the options given above, without auto-mapped locations. */
    public interface StageCompiler {
        /** @throws IllegalArgumentException with shaderc's message when the stage does not compile */
        byte[] compile(String fileName, String source, CoreShaderStage stage);
    }

    /** {@code frontend::link} of {@code gaius-shaderxlat}. */
    public interface Linker {
        /** @throws IllegalArgumentException with the link's message when the stages do not link */
        Linked link(byte[] vertex, byte[] fragment, Layout layout, boolean lenient);
    }

    /**
     * What the pipeline declares: uniform buffers and texel buffers (in declaration order),
     * samplers, and its vertex attributes in location order with their formats.
     */
    public record Layout(List<CorePipelineDesc.Uniform> uniforms, List<String> samplers, List<String> attributeNames,
            List<CoreFormat> attributeFormats) {
    }

    /** A bind group entry of the link; {@code declared} is false for a lenient addition. */
    public record Entry(String name, CoreUniformType type, CoreFormat texelFormat, boolean declared) {
    }

    /** The linked modules, the bind group entries and the lenient fixes ({@code kind:name}). */
    public record Linked(byte[] vertex, byte[] fragment, List<Entry> entries, List<String> fixes) {
        /** The entries as pipeline uniforms, in binding order. */
        public List<CorePipelineDesc.Uniform> uniforms() {
            List<CorePipelineDesc.Uniform> uniforms = new ArrayList<>(this.entries.size());
            for (Entry entry : this.entries) {
                uniforms.add(new CorePipelineDesc.Uniform(entry.name(), entry.type(), entry.texelFormat()));
            }
            return uniforms;
        }
    }

    /** Replaces the stage compiler ({@code null} restores the page's toolchain). */
    public static synchronized void setCompiler(StageCompiler replacement) {
        compiler = replacement != null ? replacement : GlslFrontEnd::compileInToolchain;
        STAGES.clear();
    }

    /** Replaces the linker ({@code null} restores the translator's link). */
    public static synchronized void setLinker(Linker replacement) {
        linker = replacement != null ? replacement : GlslFrontEnd::linkInTranslator;
    }

    /**
     * Compiles and links the two stages of a pipeline. Throws {@link IllegalArgumentException}
     * naming the failing step; the pipeline is then unavailable (D11).
     */
    public static Linked link(CoreShaderSource.Glsl glsl, List<CorePipelineDesc.Uniform> declared,
            List<CoreFormat> attributeFormats) {
        byte[] vertex = stage(glsl.vertexName(), glsl.vertexText(), CoreShaderStage.VERTEX);
        byte[] fragment = stage(glsl.fragmentName(), glsl.fragmentText(), CoreShaderStage.FRAGMENT);
        Layout layout = new Layout(declared, glsl.samplers(), glsl.attributeNames(), attributeFormats);
        Linker current;
        synchronized (GlslFrontEnd.class) {
            current = linker;
        }
        long start = System.nanoTime();
        Linked linked = current.link(vertex, fragment, layout, glsl.lenient());
        synchronized (GlslFrontEnd.class) {
            links++;
            linkNanos += System.nanoTime() - start;
        }
        return linked;
    }

    /** One stage's shaderc output, as shaderc with {@code auto_map_locations} gives it (cached). */
    public static byte[] stage(String fileName, String source, CoreShaderStage stage) {
        String key = stage.name() + '|' + fileName + '|' + source;
        StageCompiler current;
        synchronized (GlslFrontEnd.class) {
            byte[] cached = STAGES.get(key);
            if (cached != null) {
                cacheHits++;
                return cached;
            }
            current = compiler;
        }
        long start = System.nanoTime();
        GlslLocations.Result located = GlslLocations.inject(source, stage);
        byte[] spirv = located.restore(current.compile(fileName, located.source(), stage));
        synchronized (GlslFrontEnd.class) {
            compiles++;
            compileNanos += System.nanoTime() - start;
            if (STAGES.size() >= MAX_CACHED_STAGES) {
                STAGES.clear();
            }
            STAGES.put(key, spirv);
        }
        return spirv;
    }

    /** Stages compiled so far, stage cache hits, links, and the time spent in both (ns). */
    public static synchronized long[] stats() {
        return new long[] { compiles, cacheHits, links, compileNanos, linkNanos };
    }

    // ---- the page's shader toolchain ---------------------------------------------------------

    private static byte[] compileInToolchain(String fileName, String source, CoreShaderStage stage) {
        if (!toolchainAvailable()) {
            throw new IllegalArgumentException("shaderc: the page's shader toolchain is not loaded"
                    + " (window.__gaiusShaderToolchain)");
        }
        int length = toolchainCompile(source, stage == CoreShaderStage.FRAGMENT ? 1 : 0, fileName,
                TARGET_ENV_VULKAN, ENV_VERSION_VULKAN_1_2);
        if (length < 0) {
            throw new IllegalArgumentException("shaderc: " + lastError());
        }
        return takeBytes(length, 0);
    }

    @JSBody(script = "var t = globalThis.__gaiusShaderToolchain; return !!(t && t.shadercBegin);")
    private static native boolean toolchainAvailable();

    /**
     * Compiles with GlslCompiler's options (minus auto_map_locations); keeps the module for
     * {@link #takeBytes} and returns its length, or -1 with the message in {@link #lastError}.
     */
    @JSBody(params = { "source", "kind", "fileName", "env", "version" }, script = """
            var holder = globalThis.__gaiusGlslFrontEnd || (globalThis.__gaiusGlslFrontEnd = {});
            var t = globalThis.__gaiusShaderToolchain;
            var encoder = new TextEncoder();
            holder.bytes = [null, null];
            var id = t.shadercBegin();
            try {
              t.shadercOption(id, 1, env, version);
              t.shadercOption(id, 2, 1, 0);
              t.shadercOption(id, 4, 0, 0);
              t.shadercOption(id, 5, 0, 0);
              var status = t.shadercCompile(id, encoder.encode(source), kind, encoder.encode(fileName),
                  encoder.encode("main"));
              if (status !== 0) {
                holder.error = String(t.shadercErrorMessage(id) || ("status " + status));
                return -1;
              }
              var out = new Int8Array(t.shadercOutputLength(id));
              t.shadercOutputCopy(id, out);
              holder.bytes[0] = out;
              return out.length;
            } catch (error) {
              holder.error = String(error && error.message ? error.message : error);
              return -1;
            } finally {
              t.shadercEnd(id);
            }
            """)
    private static native int toolchainCompile(String source, int kind, String fileName, int env, int version);

    // ---- the translator's link ---------------------------------------------------------------

    private static Linked linkInTranslator(byte[] vertex, byte[] fragment, Layout layout, boolean lenient) {
        // Both buffers exist before either view: on WebAssembly GC a direct buffer lives in the
        // module's memory, and an allocation that grows it detaches every view made before.
        ByteBuffer vertexBuffer = direct(vertex);
        ByteBuffer fragmentBuffer = direct(fragment);
        String layoutJson = layoutJson(layout);
        int count = translatorLink(WebGpuGlue.view(vertexBuffer), WebGpuGlue.view(fragmentBuffer), layoutJson,
                lenient);
        if (count < 0) {
            throw new IllegalArgumentException("link: " + lastError());
        }
        byte[] linkedVertex = takeBytes(linkedLength(0), 0);
        byte[] linkedFragment = takeBytes(linkedLength(1), 1);
        List<Entry> entries = new ArrayList<>(count);
        String text = linkedEntries();
        if (!text.isEmpty()) {
            for (String line : text.split("\n")) {
                String[] parts = line.split("\\|", -1);
                CoreUniformType type = switch (parts[0]) {
                    case "UNIFORM_BUFFER" -> CoreUniformType.UNIFORM_BUFFER;
                    case "TEXEL_BUFFER" -> CoreUniformType.TEXEL_BUFFER;
                    default -> CoreUniformType.COMBINED_IMAGE_SAMPLER;
                };
                CoreFormat format = parts[3].isEmpty() ? null : CoreFormat.valueOf(parts[3]);
                entries.add(new Entry(parts[2], type, format, parts[1].equals("1")));
            }
        }
        String fixText = linkedFixes();
        List<String> fixes = fixText.isEmpty() ? Collections.emptyList() : List.of(fixText.split("\n"));
        return new Linked(linkedVertex, linkedFragment, entries, fixes);
    }

    /** The link's layout argument: {uniforms: [{name, texelFormat}], samplers, attributes: [{name, format}]}. */
    static String layoutJson(Layout layout) {
        StringBuilder json = new StringBuilder(256).append("{\"uniforms\":[");
        for (int i = 0; i < layout.uniforms().size(); i++) {
            CorePipelineDesc.Uniform uniform = layout.uniforms().get(i);
            json.append(i == 0 ? "" : ",").append("{\"name\":");
            CorePipeline.quote(json, uniform.name());
            if (uniform.type() == CoreUniformType.TEXEL_BUFFER && uniform.texelFormat() != null) {
                json.append(",\"texelFormat\":\"").append(uniform.texelFormat().name()).append('"');
            }
            json.append('}');
        }
        json.append("],\"samplers\":[");
        for (int i = 0; i < layout.samplers().size(); i++) {
            json.append(i == 0 ? "" : ",");
            CorePipeline.quote(json, layout.samplers().get(i));
        }
        json.append("],\"attributes\":[");
        for (int i = 0; i < layout.attributeNames().size(); i++) {
            json.append(i == 0 ? "" : ",").append("{\"name\":");
            CorePipeline.quote(json, layout.attributeNames().get(i));
            CoreFormat format = i < layout.attributeFormats().size() ? layout.attributeFormats().get(i) : null;
            json.append(",\"format\":\"").append(format != null ? format.name() : "").append("\"}");
        }
        return json.append("]}").toString();
    }

    @JSBody(params = { "vertex", "fragment", "layout", "lenient" }, script = """
            var holder = globalThis.__gaiusGlslFrontEnd || (globalThis.__gaiusGlslFrontEnd = {});
            holder.bytes = [null, null];
            holder.entries = "";
            holder.fixes = "";
            var g = globalThis.GaiusWebGpu;
            var x = g && g.translator;
            if (!x || typeof x.link !== "function") {
              holder.error = "no GLSL front-end link is installed (GaiusWebGpu.translator.link)";
              return -1;
            }
            try {
              var r = x.link(vertex, fragment, JSON.parse(layout), { lenient: lenient });
              holder.bytes = [new Int8Array(r.vertex.buffer, r.vertex.byteOffset, r.vertex.byteLength),
                  new Int8Array(r.fragment.buffer, r.fragment.byteOffset, r.fragment.byteLength)];
              holder.entries = r.entries.map(function (e) {
                return e.type + "|" + (e.declared ? 1 : 0) + "|" + e.name + "|" + (e.gpuFormat || "");
              }).join("\\n");
              holder.fixes = r.fixes.map(function (f) {
                return f.kind + ":" + f.name + (f.base ? ":" + f.base : "");
              }).join("\\n");
              return r.entries.length;
            } catch (error) {
              holder.error = String(error && error.message ? error.message : error);
              return -1;
            }
            """)
    private static native int translatorLink(Int8Array vertex, Int8Array fragment, String layout, boolean lenient);

    @JSBody(params = "index", script = """
            var bytes = globalThis.__gaiusGlslFrontEnd.bytes[index];
            return bytes ? bytes.length : 0;
            """)
    private static native int linkedLength(int index);

    @JSBody(script = "return String(globalThis.__gaiusGlslFrontEnd.entries || \"\");")
    private static native String linkedEntries();

    @JSBody(script = "return String(globalThis.__gaiusGlslFrontEnd.fixes || \"\");")
    private static native String linkedFixes();

    @JSBody(script = """
            var holder = globalThis.__gaiusGlslFrontEnd;
            return holder && holder.error ? String(holder.error) : "unknown error";
            """)
    private static native String lastError();

    @JSBody(params = { "index", "out" }, script = "out.set(globalThis.__gaiusGlslFrontEnd.bytes[index]);")
    private static native void copyBytes(int index, Int8Array out);

    /** The kept module {@code index} (length {@code length}) as a Java array. */
    private static byte[] takeBytes(int length, int index) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(length, 1)).order(ByteOrder.nativeOrder());
        if (length > 0) {
            ByteBuffer window = buffer.duplicate();
            window.limit(length);
            copyBytes(index, WebGpuGlue.view(window));
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes, 0, length);
        return bytes;
    }

    private static ByteBuffer direct(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        buffer.put(bytes).flip();
        return buffer;
    }
}
