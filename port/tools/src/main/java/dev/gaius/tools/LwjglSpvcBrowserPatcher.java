package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Redirects org.lwjgl.util.spvc.Spvc to the browser shim
 * org.lwjgl.util.spvc.BrowserSpvc (port/overrides/libraries/lwjgl-spvc, PLAN
 * D5), which reflects and decompiles SPIR-V through the WebAssembly build of
 * SPIRV-Cross and post-processes GLSL output into WebGL2 ESSL.  The input jar
 * must already contain the compiled overlay.
 */
public final class LwjglSpvcBrowserPatcher {
    static final String SPVC = "org/lwjgl/util/spvc/Spvc";
    static final String BROWSER_SPVC = "org/lwjgl/util/spvc/BrowserSpvc";

    /** The Spvc methods Minecraft 26.3 calls (renderpearl SPIRVModule, SpvUtil, GlPipelineRecompiler). */
    static final Set<String> MINECRAFT_METHODS = Set.of(
            "spvc_context_create(Lorg/lwjgl/PointerBuffer;)I",
            "spvc_context_destroy(J)V",
            "spvc_context_parse_spirv(JLjava/nio/IntBuffer;JLorg/lwjgl/PointerBuffer;)I",
            "spvc_context_create_compiler(JIJILorg/lwjgl/PointerBuffer;)I",
            "spvc_compiler_create_compiler_options(JLorg/lwjgl/PointerBuffer;)I",
            "spvc_compiler_options_set_bool(JIZ)I",
            "spvc_compiler_options_set_uint(JII)I",
            "spvc_compiler_install_compiler_options(JJ)I",
            "spvc_compiler_create_shader_resources(JLorg/lwjgl/PointerBuffer;)I",
            "spvc_resources_get_resource_list_for_type(JILorg/lwjgl/PointerBuffer;Lorg/lwjgl/PointerBuffer;)I",
            "spvc_compiler_get_decoration(JII)I",
            "spvc_compiler_get_binary_offset_for_decoration(JIILjava/nio/IntBuffer;)Z",
            "spvc_compiler_set_name(JILjava/lang/CharSequence;)V",
            "spvc_compiler_get_name(JI)Ljava/lang/String;",
            "spvc_compiler_set_entry_point(JLjava/lang/CharSequence;I)I",
            "spvc_compiler_compile(JLorg/lwjgl/PointerBuffer;)I",
            "spvc_compiler_get_declared_struct_size(JJLorg/lwjgl/PointerBuffer;)I",
            "spvc_compiler_get_type_handle(JI)J",
            "spvc_type_get_basetype(J)I",
            "spvc_type_get_image_dimension(J)I",
            "spvc_type_get_vector_size(J)I",
            "spvc_type_get_num_array_dimensions(J)I",
            "spvc_type_get_array_dimension(JI)I");

    private LwjglSpvcBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LwjglSpvcBrowserPatcher INPUT_JAR OUTPUT_ROOT");
        }
        System.out.println(LwjglBrowserShimRedirect.redirect(
                args[0], Path.of(args[1]), SPVC, BROWSER_SPVC, "SPVC", MINECRAFT_METHODS));
    }
}
