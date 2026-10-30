package org.lwjgl.util.shaderc;

import java.util.ArrayList;
import java.util.List;

/**
 * One self-contained shaderc compilation: every option in call order, the
 * include results the Java resolver produced before compilation, and the
 * source.  A {@link BrowserShadercToolchain} turns it into an {@link Output}
 * without any state shared between jobs, so a backend can replace its
 * WebAssembly instance between two jobs.
 */
public final class BrowserShadercJob {
    /** Op code of an {@link Op} that is a macro definition. */
    public static final int OP_MACRO = 0;
    /** shaderc_compile_options_set_target_env(options, a, b). */
    public static final int OP_TARGET_ENV = 1;
    /** shaderc_compile_options_set_auto_bind_uniforms(options, a != 0). */
    public static final int OP_AUTO_BIND_UNIFORMS = 2;
    /** shaderc_compile_options_set_preserve_bindings(options, a != 0). */
    public static final int OP_PRESERVE_BINDINGS = 3;
    /** shaderc_compile_options_set_generate_debug_info(options). */
    public static final int OP_GENERATE_DEBUG_INFO = 4;
    /** shaderc_compile_options_set_optimization_level(options, a). */
    public static final int OP_OPTIMIZATION_LEVEL = 5;
    /**
     * shaderc_compile_options_set_auto_map_locations(options, a != 0). Only toolchain modules that
     * export it accept this op ({@code __gaiusShaderToolchain.shadercSupportsOption(6)}); an older
     * module fails the job with an error that names the missing export.
     */
    public static final int OP_AUTO_MAP_LOCATIONS = 6;

    /** shaderc_compilation_status_success. */
    public static final int STATUS_SUCCESS = 0;
    /** shaderc_compilation_status_internal_error. */
    public static final int STATUS_INTERNAL_ERROR = 3;
    /** shaderc_compilation_status_null_result_object. */
    public static final int STATUS_NULL_RESULT_OBJECT = 5;

    /** One options call, or (code {@link #OP_MACRO}) one macro definition. */
    public static final class Op {
        public final int code;
        public final int a;
        public final int b;
        /** Macro name, UTF-8 without a terminator ({@link #OP_MACRO} only). */
        public final byte[] name;
        /** Macro value, UTF-8 without a terminator, or null for none ({@link #OP_MACRO} only). */
        public final byte[] value;

        Op(int code, int a, int b) {
            this.code = code;
            this.a = a;
            this.b = b;
            this.name = null;
            this.value = null;
        }

        Op(byte[] name, byte[] value) {
            this.code = OP_MACRO;
            this.a = 0;
            this.b = 0;
            this.name = name;
            this.value = value;
        }
    }

    /**
     * The result of one include request: shaderc asks for (type, requested,
     * requesting) and receives sourceName and content.  An error result has an
     * empty sourceName and the message as content.
     */
    public static final class Include {
        /** shaderc_include_type_relative (0) for "name", standard (1) for &lt;name&gt;. */
        public final int type;
        public final byte[] requested;
        public final byte[] requesting;
        public final byte[] sourceName;
        public final byte[] content;

        Include(int type, byte[] requested, byte[] requesting, byte[] sourceName, byte[] content) {
            this.type = type;
            this.requested = requested;
            this.requesting = requesting;
            this.sourceName = sourceName;
            this.content = content;
        }
    }

    /** What shaderc_compile_into_spv produced. */
    public static final class Output {
        public final int status;
        /** The SPIR-V (empty unless status is success). */
        public final byte[] spirv;
        /** shaderc_result_get_error_message, never null. */
        public final String errorMessage;
        public final long warnings;
        public final long errors;

        public Output(int status, byte[] spirv, String errorMessage, long warnings, long errors) {
            this.status = status;
            this.spirv = spirv == null ? new byte[0] : spirv;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
            this.warnings = warnings;
            this.errors = errors;
        }

        public static Output internalError(String message) {
            return new Output(STATUS_INTERNAL_ERROR, new byte[0], message, 0, 1);
        }
    }

    public final List<Op> ops;
    public final List<Include> includes = new ArrayList<>();
    /** The source text, without a terminator. */
    public final byte[] source;
    /** shaderc_shader_kind. */
    public final int kind;
    /** input_file_name without its terminator. */
    public final byte[] inputFileName;
    /** entry_point_name without its terminator. */
    public final byte[] entryPointName;

    BrowserShadercJob(List<Op> ops, byte[] source, int kind, byte[] inputFileName, byte[] entryPointName) {
        this.ops = ops;
        this.source = source;
        this.kind = kind;
        this.inputFileName = inputFileName;
        this.entryPointName = entryPointName;
    }
}
