package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Redirects org.lwjgl.util.shaderc.Shaderc to the browser shim
 * org.lwjgl.util.shaderc.BrowserShaderc (port/overrides/libraries/lwjgl-shaderc,
 * PLAN D5), which compiles through the WebAssembly build of shaderc.  The input
 * jar must already contain the compiled overlay.
 */
public final class LwjglShadercBrowserPatcher {
    static final String SHADERC = "org/lwjgl/util/shaderc/Shaderc";
    static final String BROWSER_SHADERC = "org/lwjgl/util/shaderc/BrowserShaderc";

    /** The Shaderc methods Minecraft 26.3 calls (renderpearl GlslCompiler). */
    static final Set<String> MINECRAFT_METHODS = Set.of(
            "shaderc_compiler_initialize()J",
            // Only referenced as a method handle (GlslCompiler.close(): compilers.forEach(Shaderc::...)).
            "shaderc_compiler_release(J)V",
            "shaderc_compile_options_initialize()J",
            "shaderc_compile_options_release(J)V",
            "shaderc_compile_options_add_macro_definition(JLjava/lang/CharSequence;Ljava/lang/CharSequence;)V",
            "shaderc_compile_options_set_generate_debug_info(J)V",
            "shaderc_compile_options_set_optimization_level(JI)V",
            "shaderc_compile_options_set_include_callbacks(JLorg/lwjgl/util/shaderc/ShadercIncludeResolveI;"
                    + "Lorg/lwjgl/util/shaderc/ShadercIncludeResultReleaseI;J)V",
            "shaderc_compile_options_set_target_env(JII)V",
            "shaderc_compile_options_set_auto_bind_uniforms(JZ)V",
            "shaderc_compile_options_set_preserve_bindings(JZ)V",
            "shaderc_compile_into_spv(JLjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;J)J",
            "shaderc_result_release(J)V",
            "shaderc_result_get_compilation_status(J)I",
            "shaderc_result_get_bytes(J)Ljava/nio/ByteBuffer;",
            "shaderc_result_get_error_message(J)Ljava/lang/String;");

    private LwjglShadercBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LwjglShadercBrowserPatcher INPUT_JAR OUTPUT_ROOT");
        }
        System.out.println(LwjglBrowserShimRedirect.redirect(
                args[0], Path.of(args[1]), SHADERC, BROWSER_SHADERC, "SHADERC", MINECRAFT_METHODS));
    }
}
