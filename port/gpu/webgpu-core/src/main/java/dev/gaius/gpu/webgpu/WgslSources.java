package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Where pipelines get their WGSL.
 *
 * <ul>
 * <li>Programs registered under a pipeline name (harnesses, pre-translated corpora) take
 * precedence over the pipeline's own shader source; they follow the default binding contract
 * (the "Split" layout of {@link CoreUniformType}).</li>
 * <li>SPIR-V goes through the {@link WgslTranslator}: {@code compilePipeline} hands the modules to
 * the {@link Prefetcher} (a kernel worker translates them ahead of time), {@code finishCompile}
 * looks the translation up in this class's cache and translates synchronously on a miss. The
 * bind group layout then follows the translator's reflection.</li>
 * </ul>
 *
 * <p>Whatever produces the WGSL must follow the orientation contract of the glue
 * ({@code port/web/runtime/webgpu/gpu-glue.js}).</p>
 */
public final class WgslSources {
    private static final Map<String, Program> PROGRAMS = new HashMap<>();
    private static final Map<String, WgslTranslator.Translation> TRANSLATIONS = new HashMap<>();
    private static final int MAX_CACHED_TRANSLATIONS = 1024;
    private static WgslTranslator translator = GlueTranslator.INSTANCE;
    /**
     * By default both stages go to the glue's translator Worker (GaiusWebGpu.prefetchSpirv), with
     * the options finishCompile uses (CorePipeline.compile: flipVertexY for both stages), so
     * finishCompile finds them in the glue's translation cache.
     */
    private static Prefetcher prefetcher = (pipelineName, shaders, texelBuffers) -> {
        WebGpuGlue.prefetchSpirv(shaders.vertex(), texelBuffers, 1);
        WebGpuGlue.prefetchSpirv(shaders.fragment(), texelBuffers, 1);
    };
    private static int translations;
    private static int cacheHits;
    private static long translateNanos;

    private WgslSources() {
    }

    /** A vertex and a fragment entry point; both may live in the same module. */
    public record Program(String vertexCode, String vertexEntry, String fragmentCode, String fragmentEntry) {
        public static Program single(String code, String vertexEntry, String fragmentEntry) {
            return new Program(code, vertexEntry, code, fragmentEntry);
        }
    }

    /**
     * Receives every SPIR-V pipeline at {@code compilePipeline}, before {@code finishCompile} needs
     * it, to translate it off the main thread and {@link WgslSources#offer offer} the result.
     */
    public interface Prefetcher {
        void prefetch(String pipelineName, CoreShaderSource.Spirv shaders, String texelBuffers);
    }

    public static synchronized void register(String pipelineName, Program program) {
        PROGRAMS.put(pipelineName, program);
    }

    /** The registered program for a pipeline, or {@code null}. */
    static synchronized Program lookup(String pipelineName) {
        return PROGRAMS.get(pipelineName);
    }

    /** Replaces the synchronous translator ({@code null} restores the glue's kernel). */
    public static synchronized void setTranslator(WgslTranslator replacement) {
        translator = replacement != null ? replacement : GlueTranslator.INSTANCE;
    }

    public static synchronized void setPrefetcher(Prefetcher replacement) {
        prefetcher = replacement;
    }

    /** Called by {@code compilePipeline} (CoreDevice.compile) for SPIR-V pipelines. */
    static void prefetch(String pipelineName, CoreShaderSource.Spirv shaders, String texelBuffers) {
        Prefetcher current;
        synchronized (WgslSources.class) {
            current = prefetcher;
        }
        if (current != null) {
            current.prefetch(pipelineName, shaders, texelBuffers);
        }
        // TODO(M3 G3): without a registered prefetcher nothing is translated ahead of time. The
        // kernel job is SHADER_XLAT (gaius-kernel-abi kind 0x0501) through
        // port/web/kernels/shaderxlat-job.js on a kernel worker; its result goes to offer().
    }

    /** Stores a translation produced elsewhere (a prefetch), keyed like {@link #translate}. */
    public static synchronized void offer(CoreShaderStage stage, ByteBuffer spirv, String texelBuffers,
            boolean flipVertexY, WgslTranslator.Translation translation) {
        remember(key(stage, spirv, texelBuffers, flipVertexY), translation);
    }

    /**
     * The WGSL of one stage: from the cache, else from the translator, synchronously. Throws
     * {@link IllegalArgumentException} when the module cannot be translated.
     */
    static WgslTranslator.Translation translate(String pipelineName, CoreShaderStage stage, ByteBuffer spirv,
            String texelBuffers, boolean flipVertexY) {
        return translate(pipelineName, stage, spirv, texelBuffers, flipVertexY, false);
    }

    /** {@link #translate(String, CoreShaderStage, ByteBuffer, String, boolean)} with GL's depth range remapped. */
    static WgslTranslator.Translation translate(String pipelineName, CoreShaderStage stage, ByteBuffer spirv,
            String texelBuffers, boolean flipVertexY, boolean glDepthRemap) {
        String key = key(stage, spirv, texelBuffers, flipVertexY) + (glDepthRemap ? "|gl-depth" : "");
        WgslTranslator current;
        synchronized (WgslSources.class) {
            WgslTranslator.Translation cached = TRANSLATIONS.get(key);
            if (cached != null) {
                cacheHits++;
                return cached;
            }
            current = translator;
        }
        long start = System.nanoTime();
        WgslTranslator.Translation translation = glDepthRemap
                ? current.translate(pipelineName, stage, spirv, texelBuffers, flipVertexY, true)
                : current.translate(pipelineName, stage, spirv, texelBuffers, flipVertexY);
        synchronized (WgslSources.class) {
            translateNanos += System.nanoTime() - start;
            translations++;
            remember(key, translation);
        }
        return translation;
    }

    private static void remember(String key, WgslTranslator.Translation translation) {
        if (TRANSLATIONS.size() >= MAX_CACHED_TRANSLATIONS) {
            TRANSLATIONS.clear();
        }
        TRANSLATIONS.put(key, translation);
    }

    /** Stage, options, length and a 64-bit FNV-1a hash of the module's bytes. */
    private static String key(CoreShaderStage stage, ByteBuffer spirv, String texelBuffers, boolean flipVertexY) {
        long hash = 0xcbf29ce484222325L;
        int end = spirv.limit();
        for (int i = spirv.position(); i < end; i++) {
            hash = (hash ^ (spirv.get(i) & 0xff)) * 0x100000001b3L;
        }
        return stage.name() + (flipVertexY ? "|y|" : "|n|") + texelBuffers + "|" + spirv.remaining() + "|"
                + Long.toHexString(hash);
    }

    /** Synchronous translations so far. */
    public static synchronized int translations() {
        return translations;
    }

    /** {@code finishCompile} lookups served by the cache (prefetched or repeated modules). */
    public static synchronized int cacheHits() {
        return cacheHits;
    }

    /** Time spent in synchronous translations, in nanoseconds. */
    public static synchronized long translateNanos() {
        return translateNanos;
    }
}
