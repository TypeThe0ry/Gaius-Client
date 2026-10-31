package dev.gaius.shadercorpus;

import dev.gaius.shadercorpus.nat.NativeShaderc;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.BrowserShadercJob;
import org.lwjgl.util.shaderc.BrowserShadercToolchain;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;

/**
 * A {@link BrowserShadercToolchain} on the native LWJGL shaderc (through the
 * relocated NativeShaderc).  It replays each job exactly like the WebAssembly
 * loader does - options and macros in order, includes answered from the job's
 * table - and records the job and its output as one line of tape-shaderc.jsonl,
 * which the Node smoke replays against the WebAssembly build.
 */
final class NativeShadercToolchain implements BrowserShadercToolchain {
    private final Corpus corpus;
    private final long compiler;
    private final ShadercIncludeResolve resolver;
    private final ShadercIncludeResultRelease releaser;
    private BrowserShadercJob current;
    private int misses;
    int jobs;
    int includeMisses;
    double nativeMs;

    NativeShadercToolchain(Corpus corpus) {
        this.corpus = corpus;
        this.compiler = NativeShaderc.shaderc_compiler_initialize();
        this.resolver = ShadercIncludeResolve.create(this::resolve);
        this.releaser = ShadercIncludeResultRelease.create((userData, result) -> {
            ShadercIncludeResult include = ShadercIncludeResult.create(result);
            MemoryUtil.nmemFree(MemoryUtil.memGetAddress(result + ShadercIncludeResult.SOURCE_NAME));
            MemoryUtil.nmemFree(MemoryUtil.memGetAddress(result + ShadercIncludeResult.CONTENT));
            include.free();
        });
    }

    private static String key(int type, byte[] requested, byte[] requesting) {
        return type + "\u0000" + latin1(requested) + "\u0000" + latin1(requesting);
    }

    private static String latin1(byte[] bytes) {
        char[] chars = new char[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            chars[i] = (char) (bytes[i] & 0xff);
        }
        return new String(chars);
    }

    private static byte[] cString(long address) {
        ByteBuffer buffer = MemoryUtil.memByteBufferNT1(address);
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private long resolve(long userData, long requested, int type, long requesting, long depth) {
        byte[] requestedBytes = cString(requested);
        String wanted = key(type, requestedBytes, cString(requesting));
        byte[] name = new byte[0];
        byte[] content = null;
        for (BrowserShadercJob.Include include : current.includes) {
            if (key(include.type, include.requested, include.requesting).equals(wanted)) {
                name = include.sourceName;
                content = include.content;
                break;
            }
        }
        if (content == null) {
            misses++;
            content = ("Gaius shader toolchain: the include \"" + latin1(requestedBytes)
                    + "\" was not resolved before compilation").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        }
        ShadercIncludeResult result = ShadercIncludeResult.calloc();
        ByteBuffer nameBuffer = MemoryUtil.memAlloc(name.length + 1);
        nameBuffer.put(name).put((byte) 0).flip();
        ByteBuffer contentBuffer = MemoryUtil.memAlloc(content.length + 1);
        contentBuffer.put(content).put((byte) 0).flip();
        MemoryUtil.memPutAddress(result.address() + ShadercIncludeResult.SOURCE_NAME, MemoryUtil.memAddress(nameBuffer));
        ShadercIncludeResult.nsource_name_length(result.address(), name.length);
        MemoryUtil.memPutAddress(result.address() + ShadercIncludeResult.CONTENT, MemoryUtil.memAddress(contentBuffer));
        ShadercIncludeResult.ncontent_length(result.address(), content.length);
        return result.address();
    }

    @Override
    public BrowserShadercJob.Output compile(BrowserShadercJob job) {
        long started = System.nanoTime();
        long options = NativeShaderc.shaderc_compile_options_initialize();
        List<ByteBuffer> buffers = new ArrayList<>();
        StringBuilder ops = new StringBuilder();
        for (BrowserShadercJob.Op op : job.ops) {
            if (ops.length() > 0) {
                ops.append(',');
            }
            switch (op.code) {
                case BrowserShadercJob.OP_MACRO -> {
                    ByteBuffer name = copy(op.name, buffers);
                    ByteBuffer value = op.value == null ? null : copy(op.value, buffers);
                    NativeShaderc.nshaderc_compile_options_add_macro_definition(options, MemoryUtil.memAddress(name),
                            op.name.length, value == null ? 0L : MemoryUtil.memAddress(value),
                            op.value == null ? 0 : op.value.length);
                    ops.append("{\"macro\":").append(Corpus.q(op.name)).append(",\"value\":")
                            .append(Corpus.q(op.value)).append('}');
                    continue;
                }
                case BrowserShadercJob.OP_TARGET_ENV ->
                        NativeShaderc.shaderc_compile_options_set_target_env(options, op.a, op.b);
                case BrowserShadercJob.OP_AUTO_BIND_UNIFORMS ->
                        NativeShaderc.shaderc_compile_options_set_auto_bind_uniforms(options, op.a != 0);
                case BrowserShadercJob.OP_PRESERVE_BINDINGS ->
                        NativeShaderc.shaderc_compile_options_set_preserve_bindings(options, op.a != 0);
                case BrowserShadercJob.OP_GENERATE_DEBUG_INFO ->
                        NativeShaderc.shaderc_compile_options_set_generate_debug_info(options);
                case BrowserShadercJob.OP_OPTIMIZATION_LEVEL ->
                        NativeShaderc.shaderc_compile_options_set_optimization_level(options, op.a);
                default -> throw new IllegalStateException("unknown shaderc op " + op.code);
            }
            ops.append("{\"code\":").append(op.code).append(",\"a\":").append(op.a).append(",\"b\":").append(op.b)
                    .append('}');
        }
        NativeShaderc.shaderc_compile_options_set_include_callbacks(options, resolver, releaser, 0L);
        ByteBuffer source = MemoryUtil.memAlloc(Math.max(1, job.source.length));
        source.put(job.source).flip();
        buffers.add(source);
        ByteBuffer fileName = terminated(job.inputFileName, buffers);
        ByteBuffer entryPoint = terminated(job.entryPointName, buffers);
        current = job;
        misses = 0;
        long result;
        try {
            result = NativeShaderc.shaderc_compile_into_spv(compiler, source, job.kind, fileName, entryPoint, options);
        } finally {
            current = null;
        }
        int status = NativeShaderc.shaderc_result_get_compilation_status(result);
        byte[] spirv = new byte[(int) NativeShaderc.shaderc_result_get_length(result)];
        if (spirv.length > 0) {
            NativeShaderc.shaderc_result_get_bytes(result).get(spirv);
        }
        String error = NativeShaderc.shaderc_result_get_error_message(result);
        long warnings = NativeShaderc.shaderc_result_get_num_warnings(result);
        long errors = NativeShaderc.shaderc_result_get_num_errors(result);
        NativeShaderc.shaderc_result_release(result);
        NativeShaderc.shaderc_compile_options_release(options);
        for (ByteBuffer buffer : buffers) {
            MemoryUtil.memFree(buffer);
        }
        nativeMs += (System.nanoTime() - started) / 1e6;
        jobs++;
        includeMisses += misses;

        StringBuilder includes = new StringBuilder();
        for (BrowserShadercJob.Include include : job.includes) {
            if (includes.length() > 0) {
                includes.append(',');
            }
            includes.append("{\"type\":").append(include.type)
                    .append(",\"requested\":").append(Corpus.q(include.requested))
                    .append(",\"requesting\":").append(Corpus.q(include.requesting))
                    .append(",\"sourceName\":").append(Corpus.q(include.sourceName))
                    .append(",\"content\":\"").append(corpus.blob("tape-include", ".glsl", include.content))
                    .append("\"}");
        }
        corpus.line("tape-shaderc", "{\"ops\":[" + ops + "],\"includes\":[" + includes + "]"
                + ",\"source\":\"" + corpus.blob("tape-source", ".glsl", job.source) + "\""
                + ",\"kind\":" + job.kind
                + ",\"inputFileName\":" + Corpus.q(job.inputFileName)
                + ",\"entryPointName\":" + Corpus.q(job.entryPointName)
                + ",\"status\":" + status
                + ",\"spirv\":\"" + corpus.blob("tape-spirv", ".spv", spirv) + "\""
                + ",\"error\":" + Corpus.q(error == null ? "" : error)
                + ",\"warnings\":" + warnings + ",\"errors\":" + errors
                + ",\"includeMisses\":" + misses + "}");
        return new BrowserShadercJob.Output(status, spirv, error, warnings, errors);
    }

    private static ByteBuffer copy(byte[] bytes, List<ByteBuffer> buffers) {
        ByteBuffer buffer = MemoryUtil.memAlloc(Math.max(1, bytes.length));
        buffer.put(bytes).flip();
        buffers.add(buffer);
        return buffer;
    }

    private static ByteBuffer terminated(byte[] bytes, List<ByteBuffer> buffers) {
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length + 1);
        buffer.put(bytes).put((byte) 0).flip();
        buffers.add(buffer);
        return buffer;
    }

    void close() {
        NativeShaderc.shaderc_compiler_release(compiler);
        resolver.free();
        releaser.free();
    }
}
