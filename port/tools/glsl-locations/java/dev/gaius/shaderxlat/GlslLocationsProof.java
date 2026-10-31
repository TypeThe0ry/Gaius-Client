package dev.gaius.shaderxlat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import dev.gaius.gpu.webgpu.CoreShaderStage;
import dev.gaius.gpu.webgpu.GlslLocations;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

/**
 * Proves that {@link GlslLocations} (explicit locations in the GLSL text, shaderc without
 * {@code auto_map_locations}, then {@link GlslLocations.Result#restore}) is equivalent to shaderc's
 * {@code auto_map_locations} for the blaze3d front-end, on a whole corpus.
 *
 * <p>Every pipeline's two stages are compiled with the native LWJGL shaderc of the browser
 * toolchain's revision: A exactly as the vanilla 26.2 {@code GlslCompiler} does (auto-mapped), B
 * with GlslLocations on the same text and the same options minus {@code auto_map_locations}. The
 * shaderc outputs are compared byte for byte (B restored) and instruction by instruction (B as
 * shaderc returns it). For 26.2 the vanilla Java link of both paths
 * ({@code IntermediaryShaderModule.rebind}) is compared as well. It writes OUT/auto, OUT/inject
 * (restored) and OUT/inject-raw, frontend dumps in the format of {@code ShaderXlatCorpusDump262},
 * for {@code shaderxlat-link}, and OUT/report.txt.</p>
 *
 * <p>Compiled together with {@code port/native/golden/shaders/java/.../ShaderXlatCorpusDump262.java}
 * (same package: its corpus and shaderc set-up are reused) by {@code run.sh}.</p>
 *
 * <p>usage: GlslLocationsProof strict CLIENT_JAR OUT (the 26.2 pipelines, with the vanilla link) |
 * replay LEGACY_DIR OUT (the 1.21.11 pipelines adapted to 26.2)</p>
 */
public final class GlslLocationsProof {
    private GlslLocationsProof() {
    }

    private static final int OP_SOURCE_CONTINUED = 2;
    private static final int OP_SOURCE = 3;
    private static final int OP_SOURCE_EXTENSION = 4;
    private static final int OP_LINE = 8;
    private static final int OP_DECORATE = 71;
    private static final int OP_MODULE_PROCESSED = 330;
    private static final int DECORATION_LOCATION = 30;

    private record Inst(int op, int[] words) {
    }

    private static int[] words(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int[] w = new int[bytes.length / 4];
        for (int i = 0; i < w.length; i++) {
            w[i] = b.getInt();
        }
        return w;
    }

    private static List<Inst> instructions(byte[] bytes) {
        int[] w = words(bytes);
        List<Inst> out = new ArrayList<>();
        for (int i = 5; i < w.length;) {
            int count = w[i] >>> 16;
            if (count == 0) {
                throw new IllegalStateException("bad SPIR-V");
            }
            out.add(new Inst(w[i] & 0xffff, Arrays.copyOfRange(w, i, i + count)));
            i += count;
        }
        return out;
    }

    /** Instruction-level differences between two modules, counted per kind. */
    private static Map<String, Integer> classify(byte[] a, byte[] b) {
        Map<String, Integer> diff = new TreeMap<>();
        List<Inst> sa = new ArrayList<>();
        List<Inst> sb = new ArrayList<>();
        for (Inst inst : instructions(a)) {
            if (inst.op == OP_SOURCE_EXTENSION || inst.op == OP_MODULE_PROCESSED) {
                diff.merge((inst.op == OP_SOURCE_EXTENSION ? "OpSourceExtension" : "OpModuleProcessed") + ":A", 1,
                        Integer::sum);
            } else {
                sa.add(inst);
            }
        }
        for (Inst inst : instructions(b)) {
            if (inst.op == OP_SOURCE_EXTENSION || inst.op == OP_MODULE_PROCESSED) {
                diff.merge((inst.op == OP_SOURCE_EXTENSION ? "OpSourceExtension" : "OpModuleProcessed") + ":B", 1,
                        Integer::sum);
            } else {
                sb.add(inst);
            }
        }
        if (sa.size() != sb.size()) {
            diff.merge("instructionCount", 1, Integer::sum);
            return diff;
        }
        for (int i = 0; i < sa.size(); i++) {
            Inst x = sa.get(i);
            Inst y = sb.get(i);
            if (Arrays.equals(x.words, y.words)) {
                continue;
            }
            String kind;
            if (x.op != y.op) {
                kind = "opcode";
            } else if (x.op == OP_SOURCE || x.op == OP_SOURCE_CONTINUED) {
                kind = "OpSource text";
            } else if (x.op == OP_LINE) {
                kind = "OpLine";
            } else if (x.op == OP_DECORATE && x.words.length == 4 && y.words.length == 4
                    && x.words[2] == DECORATION_LOCATION && y.words[2] == DECORATION_LOCATION) {
                kind = "Location literal";
            } else {
                kind = "other op " + x.op;
            }
            diff.merge(kind, 1, Integer::sum);
        }
        return diff;
    }

    /** shaderc_compile_into_spv with the given options (GlslCompiler.createIntermediary's call). */
    private static byte[] shaderc(long compiler, long options, String name, String text, ShaderType type) {
        ByteBuffer sourceBytes = MemoryUtil.memUTF8(text, false);
        ByteBuffer nameBytes = MemoryUtil.memUTF8(name);
        ByteBuffer entryBytes = MemoryUtil.memUTF8("main");
        long result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes, type == ShaderType.FRAGMENT ? 1 : 0,
                nameBytes, entryBytes, options);
        try {
            if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
                throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(result));
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

    /** One frontend dump being written. */
    private static final class Dump {
        final ShaderXlatCorpusDump262.Blobs blobs;
        final StringBuilder json = new StringBuilder();
        int pipelines;
        int vanillaOk;

        Dump(Path dir) throws Exception {
            Files.createDirectories(dir.resolve("spirv"));
            this.blobs = new ShaderXlatCorpusDump262.Blobs(dir.resolve("spirv"));
        }

        void add(RenderPipeline p, Object[] w, byte[][] modules, ShaderXlatCorpusDump262.Vanilla vanilla,
                String vanillaError) throws Exception {
            String[] intermediate = { this.blobs.put(modules[0]), this.blobs.put(modules[1]) };
            if (vanilla != null) {
                vanilla.vertexHash = this.blobs.put(vanilla.vertex);
                vanilla.fragmentHash = this.blobs.put(vanilla.fragment);
                this.vanillaOk++;
            }
            this.json.append(this.pipelines++ == 0 ? "" : ",\n");
            ShaderXlatCorpusDump262.appendPipeline(this.json, p, (String) w[1], (String) w[6], (String) w[7], intermediate,
                    vanilla, vanillaError, null, (String) w[8]);
        }

        void finish(String header, Path dir) throws Exception {
            String json = header + ShaderXlatCorpusDump262.quote("glsl-locations proof") + ",\"pipelines\":[\n" + this.json
                    + "\n],\"summary\":{\"pipelines\":" + this.pipelines + ",\"compiled\":" + this.pipelines
                    + ",\"failed\":0,\"vanillaOk\":" + this.vanillaOk + ",\"uniqueModules\":" + this.blobs.written.size()
                    + "}}\n";
            Files.writeString(dir.resolve("frontend.json"), json, StandardCharsets.UTF_8);
        }
    }

    private static ShaderXlatCorpusDump262.Vanilla link(ShaderXlatCorpusDump262.Front front, RenderPipeline p,
            String[] names, byte[][] modules, String[] error) {
        try {
            return front.link(p, names[0], modules[0], names[1], modules[1]);
        } catch (Exception e) {
            error[0] = e.getMessage();
            return null;
        }
    }

    private static String entries(ShaderXlatCorpusDump262.Vanilla v) {
        StringBuilder json = new StringBuilder();
        ShaderXlatCorpusDump262.appendEntries(json, v.entries);
        return json.toString();
    }

    public static void main(String[] args) throws Throwable {
        String mode = args[0];
        Path out = Path.of(args[2]);
        Dump auto = new Dump(out.resolve("auto"));
        Dump inject = new Dump(out.resolve("inject"));
        Dump injectRaw = new Dump(out.resolve("inject-raw"));
        Map<String, Integer> rawDiff = new TreeMap<>();
        int pipelines = 0;
        int stages = 0;
        int extensions = 0;
        int identical = 0;
        int identicalExceptDebug = 0;
        int vanillaCompared = 0;
        int vanillaIdentical = 0;
        int failures = 0;
        int controlRuns = 0;
        int controlFailures = 0;
        String controlMessage = "";
        StringBuilder failuresText = new StringBuilder();
        boolean withVanilla = mode.equals("strict");
        String header = withVanilla ? "{\"profile\":\"26.2\",\"mode\":\"strict\",\"frontend\":"
                : "{\"profile\":\"1.21.11\",\"mode\":\"lenient\",\"frontend\":";
        try (GlslCompiler compiler = new GlslCompiler()) {
            ShaderXlatCorpusDump262.Front front = new ShaderXlatCorpusDump262.Front(compiler);
            long noAutoMap = Shaderc.shaderc_compile_options_clone(front.shaderOptions);
            Shaderc.shaderc_compile_options_set_auto_map_locations(noAutoMap, false);
            // {pipeline, set, vertex name, vertex text, fragment name, fragment text, vertex id, fragment id, extra}
            List<Object[]> work = new ArrayList<>();
            if (withVanilla) {
                for (Map.Entry<RenderPipeline, String> e : ShaderXlatCorpusDump262.pipelines262(Path.of(args[1])).entrySet()) {
                    RenderPipeline p = e.getKey();
                    String[] s = ShaderXlatCorpusDump262.sources262.get(p);
                    work.add(new Object[] { p, e.getValue(), p.getVertexShader().toDebugFileName(), s[0],
                            p.getFragmentShader().toDebugFileName(), s[1], p.getVertexShader().toString(),
                            p.getFragmentShader().toString(), null });
                }
            } else {
                Path legacy = Path.of(args[1]);
                JsonObject meta = JsonParser.parseString(Files.readString(legacy.resolve("meta.json"))).getAsJsonObject();
                for (JsonElement element : meta.getAsJsonArray("pipelines")) {
                    JsonObject m = element.getAsJsonObject();
                    List<String> realigned = new ArrayList<>();
                    RenderPipeline p = ShaderXlatCorpusDump262.adapt(m, realigned);
                    String vs = Files.readString(legacy.resolve("glsl").resolve(m.get("vertexGlsl").getAsString() + ".glsl"));
                    String fs = Files.readString(legacy.resolve("glsl").resolve(m.get("fragmentGlsl").getAsString() + ".glsl"));
                    List<String> fixes = new ArrayList<>();
                    String[] renamed = ShaderXlatCorpusDump262.renameReservedWords(vs, fs, fixes);
                    String extra = "\"legacy\":{\"depthTest\":" + ShaderXlatCorpusDump262.quote(m.get("depthTest").getAsString())
                            + ",\"mode\":" + ShaderXlatCorpusDump262.quote(m.get("mode").getAsString())
                            + ",\"colorLogic\":" + ShaderXlatCorpusDump262.quote(m.get("colorLogic").getAsString())
                            + ",\"defines\":" + ShaderXlatCorpusDump262.quote(m.get("defines").getAsString())
                            + ",\"realignedAttributes\":" + ShaderXlatCorpusDump262.quote(String.join(" ", realigned))
                            + ",\"sourceFixes\":" + ShaderXlatCorpusDump262.quote(String.join(" ", fixes)) + "}";
                    work.add(new Object[] { p, m.get("set").getAsString(),
                            Identifier.parse(m.get("vertexShader").getAsString()).toDebugFileName(), renamed[0],
                            Identifier.parse(m.get("fragmentShader").getAsString()).toDebugFileName(), renamed[1],
                            m.get("vertexShader").getAsString(), m.get("fragmentShader").getAsString(), extra });
                }
            }
            ShaderType[] types = { ShaderType.VERTEX, ShaderType.FRAGMENT };
            for (Object[] w : work) {
                pipelines++;
                RenderPipeline p = (RenderPipeline) w[0];
                String location = p.getLocation().toString();
                String[] names = { (String) w[2], (String) w[4] };
                String[] texts = { (String) w[3], (String) w[5] };
                byte[][] a = new byte[2][];
                byte[][] b = new byte[2][];
                byte[][] raw = new byte[2][];
                String error = null;
                for (int s = 0; s < 2 && error == null; s++) {
                    String text = GlslPreprocessor.injectDefines(texts[s], front.globalDefines);
                    GlslLocations.Result located = GlslLocations.inject(text,
                            s == 0 ? CoreShaderStage.VERTEX : CoreShaderStage.FRAGMENT);
                    stages++;
                    extensions += located.extension() ? 1 : 0;
                    try {
                        a[s] = shaderc(front.shaderCompiler, front.shaderOptions, names[s], text, types[s]);
                        raw[s] = shaderc(front.shaderCompiler, noAutoMap, names[s], located.source(), types[s]);
                    } catch (RuntimeException e) {
                        error = e.getMessage();
                        break;
                    }
                    b[s] = located.restore(raw[s]);
                    if (controlRuns < 8) {
                        // Control: without explicit locations and without auto-mapping shaderc refuses.
                        controlRuns++;
                        try {
                            shaderc(front.shaderCompiler, noAutoMap, names[s], text, types[s]);
                        } catch (RuntimeException e) {
                            controlFailures++;
                            controlMessage = e.getMessage().trim().split("\n")[0];
                        }
                    }
                    if (Arrays.equals(a[s], b[s])) {
                        identical++;
                    } else {
                        failuresText.append("DIFFERS ").append(location).append(' ').append(types[s]).append('\n');
                        failures++;
                    }
                    Map<String, Integer> d = classify(a[s], raw[s]);
                    d.keySet().removeIf(k -> k.startsWith("OpSourceExtension") || k.startsWith("OpModuleProcessed")
                            || k.equals("OpSource text"));
                    if (d.isEmpty()) {
                        identicalExceptDebug++;
                    }
                    for (Map.Entry<String, Integer> e : classify(a[s], raw[s]).entrySet()) {
                        rawDiff.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
                if (error != null) {
                    failures++;
                    failuresText.append("FAIL ").append(location).append(": ").append(error).append('\n');
                    continue;
                }
                String[] errorA = new String[1];
                String[] errorB = new String[1];
                String[] errorRaw = new String[1];
                ShaderXlatCorpusDump262.Vanilla va = withVanilla ? link(front, p, names, a, errorA) : null;
                ShaderXlatCorpusDump262.Vanilla vb = withVanilla ? link(front, p, names, b, errorB) : null;
                ShaderXlatCorpusDump262.Vanilla vr = withVanilla ? link(front, p, names, raw, errorRaw) : null;
                if (withVanilla) {
                    vanillaCompared++;
                    if (va != null && vb != null && Arrays.equals(va.vertex, vb.vertex)
                            && Arrays.equals(va.fragment, vb.fragment) && entries(va).equals(entries(vb))) {
                        vanillaIdentical++;
                    } else if (!(va == null && vb == null && String.valueOf(errorA[0]).equals(String.valueOf(errorB[0])))) {
                        failures++;
                        failuresText.append("VANILLA LINK DIFFERS ").append(location).append('\n');
                    }
                }
                auto.add(p, w, a, va, errorA[0]);
                inject.add(p, w, b, vb, errorB[0]);
                injectRaw.add(p, w, raw, vr, errorRaw[0]);
            }
            Shaderc.shaderc_compile_options_release(noAutoMap);
        }
        auto.finish(header, out.resolve("auto"));
        inject.finish(header, out.resolve("inject"));
        injectRaw.finish(header, out.resolve("inject-raw"));
        String report = "glsl-locations " + (withVanilla ? "strict corpus" : "lenient replay corpus") + ": pipelines=" + pipelines + " stages="
                + stages + " (extension directive in " + extensions + ") failures=" + failures + "\n"
                + "shaderc, injected locations + restore vs auto_map_locations: byte-identical " + identical + "/" + stages
                + "\n"
                + "shaderc, injected locations without restore: identical except OpSource text, OpSourceExtension,"
                + " OpModuleProcessed " + identicalExceptDebug + "/" + stages + "; differences " + rawDiff + "\n"
                + (withVanilla ? "vanilla Java link (IntermediaryShaderModule.rebind) of both: byte-identical "
                        + vanillaIdentical + "/" + vanillaCompared + "\n" : "")
                + "control, no locations and no auto-map: " + controlFailures + "/" + controlRuns + " refused ("
                + controlMessage + ")\n" + failuresText;
        Files.writeString(out.resolve("report.txt"), report);
        System.out.print(report);
        if (failures > 0) {
            System.exit(1);
        }
    }
}
