package org.lwjgl.util.shaderc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.system.MemoryUtil;

/**
 * Browser implementation of the org.lwjgl.util.shaderc.Shaderc entry points
 * (PLAN D5).  LwjglShadercBrowserPatcher replaces the body of every Shaderc
 * method whose name and descriptor also exist here with a call to this class;
 * the other Shaderc methods throw UnsupportedOperationException.
 *
 * <p>Compilers, options and results are Java objects behind opaque handles.
 * An options object records its calls in order; shaderc_compile_into_spv turns
 * them, together with the include results, into a self-contained
 * {@link BrowserShadercJob} for the {@link BrowserShadercToolchain}.
 *
 * <p>Includes are resolved before compilation, on the calling thread: the
 * source is scanned for #include directives and the Java resolver passed to
 * shaderc_compile_options_set_include_callbacks is invoked with the arguments
 * shaderc would pass (requested name, include type, requesting source name,
 * depth), recursively for every successfully resolved include.  The
 * WebAssembly compiler then answers its include requests from that table, so
 * it never calls back into Java.  Each resolver result is read by
 * (pointer, length) - Minecraft's ShadercIncludeResult strings carry no
 * terminator - and handed to the release callback once the compilation ends.
 *
 * <p>Not thread-safe: the browser runs every TeaVM thread on one JavaScript
 * thread and none of these methods can suspend.
 */
public final class BrowserShaderc {
    private static final long HANDLE_TAG = 0x5c00_0000_0000_0000L;
    private static final int MAX_INCLUDE_DEPTH = 256;
    private static final Map<Long, Object> HANDLES = new HashMap<>();
    private static long nextHandle = 1;
    private static BrowserShadercToolchain toolchain;

    private BrowserShaderc() {
    }

    /** Installs the compiler backend (tests); the browser default is WebAssembly. */
    public static void setToolchain(BrowserShadercToolchain value) {
        toolchain = value;
    }

    static BrowserShadercToolchain toolchain() {
        if (toolchain == null) {
            toolchain = new BrowserShadercWasm();
        }
        return toolchain;
    }

    /** Number of live compilers, options and results (leak diagnostics). */
    public static int liveHandles() {
        return HANDLES.size();
    }

    private static final class Compiler {
    }

    private static final class Options {
        final ArrayList<BrowserShadercJob.Op> ops = new ArrayList<>();
        ShadercIncludeResolveI resolver;
        ShadercIncludeResultReleaseI releaser;
        long userData;

        Options copy() {
            Options copy = new Options();
            copy.ops.addAll(ops);
            copy.resolver = resolver;
            copy.releaser = releaser;
            copy.userData = userData;
            return copy;
        }
    }

    private static final class Result {
        final BrowserShadercJob.Output output;
        final ByteBuffer bytes;
        ByteBuffer errorText;

        Result(BrowserShadercJob.Output output) {
            this.output = output;
            ByteBuffer buffer = MemoryUtil.memAlloc(Math.max(1, output.spirv.length));
            buffer.duplicate().put(output.spirv);
            this.bytes = buffer;
        }

        void free() {
            MemoryUtil.memFree(bytes);
            if (errorText != null) {
                MemoryUtil.memFree(errorText);
                errorText = null;
            }
        }
    }

    private static long register(Object value) {
        long handle = HANDLE_TAG | nextHandle++;
        HANDLES.put(handle, value);
        return handle;
    }

    private static Compiler compilerOrNull(long handle) {
        Object value = HANDLES.get(handle);
        return value instanceof Compiler ? (Compiler) value : null;
    }

    private static Options optionsOrNull(long handle) {
        Object value = HANDLES.get(handle);
        return value instanceof Options ? (Options) value : null;
    }

    private static Result resultOrNull(long handle) {
        Object value = HANDLES.get(handle);
        return value instanceof Result ? (Result) value : null;
    }

    private static Options optionsFor(long handle) {
        Options options = optionsOrNull(handle);
        if (options == null) {
            throw new IllegalArgumentException("Unknown shaderc compile options handle 0x" + Long.toHexString(handle));
        }
        return options;
    }

    // ---- compiler ---------------------------------------------------------------------

    public static long shaderc_compiler_initialize() {
        return register(new Compiler());
    }

    public static void shaderc_compiler_release(long compiler) {
        if (compilerOrNull(compiler) != null) {
            HANDLES.remove(compiler);
        }
    }

    // ---- options ------------------------------------------------------------------------

    public static long shaderc_compile_options_initialize() {
        return register(new Options());
    }

    public static long shaderc_compile_options_clone(long options) {
        Options source = optionsOrNull(options);
        return register(source == null ? new Options() : source.copy());
    }

    public static void shaderc_compile_options_release(long options) {
        if (optionsOrNull(options) != null) {
            HANDLES.remove(options);
        }
    }

    public static void shaderc_compile_options_add_macro_definition(
            long options, CharSequence name, CharSequence value) {
        optionsFor(options).ops.add(new BrowserShadercJob.Op(
                utf8(name), value == null ? null : utf8(value)));
    }

    public static void shaderc_compile_options_add_macro_definition(
            long options, ByteBuffer name, ByteBuffer value) {
        optionsFor(options).ops.add(new BrowserShadercJob.Op(
                remaining(name), value == null ? null : remaining(value)));
    }

    public static void nshaderc_compile_options_add_macro_definition(
            long options, long name, long nameLength, long value, long valueLength) {
        optionsFor(options).ops.add(new BrowserShadercJob.Op(
                bytes(name, nameLength), value == 0L ? null : bytes(value, valueLength)));
    }

    public static void shaderc_compile_options_set_generate_debug_info(long options) {
        optionsFor(options).ops.add(new BrowserShadercJob.Op(BrowserShadercJob.OP_GENERATE_DEBUG_INFO, 0, 0));
    }

    public static void shaderc_compile_options_set_optimization_level(long options, int level) {
        optionsFor(options).ops.add(new BrowserShadercJob.Op(BrowserShadercJob.OP_OPTIMIZATION_LEVEL, level, 0));
    }

    public static void shaderc_compile_options_set_target_env(long options, int target, int version) {
        optionsFor(options).ops.add(new BrowserShadercJob.Op(BrowserShadercJob.OP_TARGET_ENV, target, version));
    }

    public static void shaderc_compile_options_set_auto_bind_uniforms(long options, boolean autoBind) {
        optionsFor(options).ops.add(new BrowserShadercJob.Op(
                BrowserShadercJob.OP_AUTO_BIND_UNIFORMS, autoBind ? 1 : 0, 0));
    }

    public static void shaderc_compile_options_set_preserve_bindings(long options, boolean preserve) {
        optionsFor(options).ops.add(new BrowserShadercJob.Op(
                BrowserShadercJob.OP_PRESERVE_BINDINGS, preserve ? 1 : 0, 0));
    }

    public static void shaderc_compile_options_set_auto_map_locations(long options, boolean autoMap) {
        optionsFor(options).ops.add(new BrowserShadercJob.Op(
                BrowserShadercJob.OP_AUTO_MAP_LOCATIONS, autoMap ? 1 : 0, 0));
    }

    public static void shaderc_compile_options_set_include_callbacks(
            long options,
            ShadercIncludeResolveI resolver,
            ShadercIncludeResultReleaseI resultReleaser,
            long userData) {
        Options target = optionsFor(options);
        target.resolver = resolver;
        target.releaser = resultReleaser;
        target.userData = userData;
    }

    // ---- compilation ---------------------------------------------------------------------

    public static long shaderc_compile_into_spv(
            long compiler, ByteBuffer sourceText, int shaderKind, ByteBuffer inputFileName,
            ByteBuffer entryPointName, long additionalOptions) {
        return compile(compiler, remaining(sourceText), shaderKind, terminated(inputFileName),
                terminated(entryPointName), additionalOptions);
    }

    public static long shaderc_compile_into_spv(
            long compiler, CharSequence sourceText, int shaderKind, CharSequence inputFileName,
            CharSequence entryPointName, long additionalOptions) {
        return compile(compiler, utf8(sourceText), shaderKind, utf8(inputFileName),
                utf8(entryPointName), additionalOptions);
    }

    public static long nshaderc_compile_into_spv(
            long compiler, long sourceText, long sourceTextSize, int shaderKind, long inputFileName,
            long entryPointName, long additionalOptions) {
        return compile(compiler, bytes(sourceText, sourceTextSize), shaderKind, terminated(inputFileName),
                terminated(entryPointName), additionalOptions);
    }

    private static long compile(
            long compiler, byte[] source, int kind, byte[] inputFileName, byte[] entryPointName, long options) {
        if (compilerOrNull(compiler) == null) {
            return register(new Result(BrowserShadercJob.Output.internalError(
                    "Gaius shader toolchain: unknown shaderc compiler handle")));
        }
        Options settings = options == 0L ? new Options() : optionsOrNull(options);
        if (settings == null) {
            return register(new Result(BrowserShadercJob.Output.internalError(
                    "Gaius shader toolchain: unknown shaderc compile options handle")));
        }
        BrowserShadercJob job = new BrowserShadercJob(
                new ArrayList<>(settings.ops), source, kind, inputFileName, entryPointName);
        List<Long> resolved = new ArrayList<>();
        BrowserShadercJob.Output output;
        try {
            if (settings.resolver != null) {
                resolveIncludes(job, settings, source, inputFileName, 1, new HashSet<>(), resolved);
            }
            output = toolchain().compile(job);
        } finally {
            if (settings.releaser != null) {
                for (long result : resolved) {
                    settings.releaser.invoke(settings.userData, result);
                }
            }
        }
        return register(new Result(output));
    }

    /**
     * Invokes the resolver for every #include directive of text (requested by
     * a source named requesting, at the given depth) and recurses into each
     * successfully resolved include.  Directives in inactive preprocessor
     * branches are resolved too; the Minecraft resolver is a pure lookup, and
     * shaderc only asks for the ones it reaches.
     */
    private static void resolveIncludes(
            BrowserShadercJob job, Options settings, byte[] text, byte[] requesting, int depth,
            Set<String> seen, List<Long> resolved) {
        if (depth > MAX_INCLUDE_DEPTH) {
            return;
        }
        int length = text.length;
        int line = 0;
        while (line < length) {
            int end = line;
            while (end < length && text[end] != '\n') {
                end++;
            }
            int[] directive = includeDirective(text, line, end);
            if (directive != null) {
                int type = directive[0];
                byte[] requested = slice(text, directive[1], directive[2]);
                String key = type + "\u0000" + latin1(requested) + "\u0000" + latin1(requesting);
                if (seen.add(key)) {
                    BrowserShadercJob.Include include = resolveOne(settings, type, requested, requesting, depth, resolved);
                    job.includes.add(include);
                    if (include.sourceName.length > 0) {
                        resolveIncludes(job, settings, include.content, include.sourceName, depth + 1, seen, resolved);
                    }
                }
            }
            line = end + 1;
        }
    }

    /** {type, nameStart, nameEnd} of an #include directive on text[start, end), or null. */
    static int[] includeDirective(byte[] text, int start, int end) {
        int i = skipBlanks(text, start, end);
        if (i >= end || text[i] != '#') {
            return null;
        }
        i = skipBlanks(text, i + 1, end);
        byte[] keyword = {'i', 'n', 'c', 'l', 'u', 'd', 'e'};
        if (end - i < keyword.length) {
            return null;
        }
        for (int k = 0; k < keyword.length; k++) {
            if (text[i + k] != keyword[k]) {
                return null;
            }
        }
        i = skipBlanks(text, i + keyword.length, end);
        if (i >= end || (text[i] != '<' && text[i] != '"')) {
            return null;
        }
        byte close = text[i] == '<' ? (byte) '>' : (byte) '"';
        int nameStart = i + 1;
        int nameEnd = nameStart;
        while (nameEnd < end && text[nameEnd] != close) {
            nameEnd++;
        }
        if (nameEnd >= end || nameEnd == nameStart) {
            return null;
        }
        return new int[] {close == '>' ? 1 : 0, nameStart, nameEnd};
    }

    private static int skipBlanks(byte[] text, int index, int end) {
        while (index < end && (text[index] == ' ' || text[index] == '\t' || text[index] == '\r')) {
            index++;
        }
        return index;
    }

    private static BrowserShadercJob.Include resolveOne(
            Options settings, int type, byte[] requested, byte[] requesting, int depth, List<Long> resolved) {
        ByteBuffer requestedText = terminatedBuffer(requested);
        ByteBuffer requestingText = terminatedBuffer(requesting);
        long result;
        try {
            result = settings.resolver.invoke(settings.userData, MemoryUtil.memAddress(requestedText), type,
                    MemoryUtil.memAddress(requestingText), depth);
        } finally {
            MemoryUtil.memFree(requestedText);
            MemoryUtil.memFree(requestingText);
        }
        if (result == 0L) {
            return new BrowserShadercJob.Include(type, requested, requesting, new byte[0],
                    "Gaius shader toolchain: the include resolver returned no result".getBytes(StandardCharsets.UTF_8));
        }
        resolved.add(result);
        byte[] sourceName = bytes(MemoryUtil.memGetAddress(result + ShadercIncludeResult.SOURCE_NAME),
                ShadercIncludeResult.nsource_name_length(result));
        byte[] content = bytes(MemoryUtil.memGetAddress(result + ShadercIncludeResult.CONTENT),
                ShadercIncludeResult.ncontent_length(result));
        return new BrowserShadercJob.Include(type, requested, requesting, sourceName, content);
    }

    // ---- results ----------------------------------------------------------------------------

    public static void shaderc_result_release(long result) {
        Result value = resultOrNull(result);
        if (value != null) {
            HANDLES.remove(result);
            value.free();
        }
    }

    public static long shaderc_result_get_length(long result) {
        Result value = resultOrNull(result);
        return value == null ? 0L : value.output.spirv.length;
    }

    public static long shaderc_result_get_num_warnings(long result) {
        Result value = resultOrNull(result);
        return value == null ? 0L : value.output.warnings;
    }

    public static long shaderc_result_get_num_errors(long result) {
        Result value = resultOrNull(result);
        return value == null ? 0L : value.output.errors;
    }

    public static int shaderc_result_get_compilation_status(long result) {
        Result value = resultOrNull(result);
        return value == null ? BrowserShadercJob.STATUS_NULL_RESULT_OBJECT : value.output.status;
    }

    public static long nshaderc_result_get_bytes(long result) {
        Result value = resultOrNull(result);
        return value == null ? 0L : MemoryUtil.memAddress(value.bytes);
    }

    public static ByteBuffer shaderc_result_get_bytes(long result) {
        Result value = resultOrNull(result);
        return value == null ? null : MemoryUtil.memByteBuffer(MemoryUtil.memAddress(value.bytes),
                value.output.spirv.length);
    }

    public static long nshaderc_result_get_error_message(long result) {
        Result value = resultOrNull(result);
        if (value == null) {
            return 0L;
        }
        if (value.errorText == null) {
            value.errorText = MemoryUtil.memUTF8(value.output.errorMessage, true);
        }
        return MemoryUtil.memAddress(value.errorText);
    }

    public static String shaderc_result_get_error_message(long result) {
        Result value = resultOrNull(result);
        return value == null ? null : value.output.errorMessage;
    }

    // ---- bytes -------------------------------------------------------------------------------

    private static byte[] utf8(CharSequence text) {
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] remaining(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    /** The bytes of a NUL-terminated buffer before the first NUL (or all remaining bytes). */
    private static byte[] terminated(ByteBuffer buffer) {
        int start = buffer.position();
        int end = start;
        while (end < buffer.limit() && buffer.get(end) != 0) {
            end++;
        }
        byte[] bytes = new byte[end - start];
        ByteBuffer view = buffer.duplicate();
        view.position(start);
        view.get(bytes);
        return bytes;
    }

    private static byte[] terminated(long address) {
        if (address == 0L) {
            return new byte[0];
        }
        int length = 0;
        while (MemoryUtil.memGetByte(address + length) != 0) {
            length++;
        }
        return bytes(address, length);
    }

    private static byte[] bytes(long address, long length) {
        if (length <= 0L || address == 0L) {
            return new byte[0];
        }
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("shaderc string is too long: " + length);
        }
        byte[] bytes = new byte[(int) length];
        MemoryUtil.memByteBuffer(address, (int) length).get(bytes);
        return bytes;
    }

    private static ByteBuffer terminatedBuffer(byte[] bytes) {
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length + 1);
        buffer.duplicate().put(bytes).put((byte) 0);
        return buffer;
    }

    private static byte[] slice(byte[] bytes, int start, int end) {
        byte[] slice = new byte[end - start];
        System.arraycopy(bytes, start, slice, 0, slice.length);
        return slice;
    }

    /** An exact, reversible string key for raw bytes. */
    private static String latin1(byte[] bytes) {
        char[] chars = new char[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            chars[i] = (char) (bytes[i] & 0xff);
        }
        return new String(chars);
    }
}
