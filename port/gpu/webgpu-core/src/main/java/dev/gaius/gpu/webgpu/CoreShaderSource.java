package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Where a pipeline's shaders come from. Each render SPI generation hands the backend a different
 * form; the core resolves all of them to WGSL (a program registered in {@link WgslSources} under
 * the pipeline name always wins):
 * <ul>
 * <li>{@link Wgsl}: WGSL given directly (harnesses, the core's own passes);</li>
 * <li>{@link Spirv}: the vanilla frontend's SPIR-V (renderpearl, 26.3 and later), translated by
 * the {@code gaius-shaderxlat} kernel;</li>
 * <li>{@link Glsl}: GLSL text with name-based bindings (blaze3d, 26.2 and 1.21.11), for the shared
 * GLSL front end ({@link GlslFrontEnd}: shaderc and the {@code gaius-shaderxlat} link, then the
 * same translator);</li>
 * <li>{@link Registered}: no source of its own, only the registry.</li>
 * </ul>
 */
public sealed interface CoreShaderSource {
    /** Only a program registered under the pipeline name. */
    record Registered() implements CoreShaderSource {
    }

    /** WGSL vertex and fragment entry points. */
    record Wgsl(WgslSources.Program program) implements CoreShaderSource {
    }

    /**
     * SPIR-V modules of both stages. The buffers stay valid until the pipeline's
     * {@code finish()} returns (renderpearl closes the modules right after {@code finishCompile}).
     */
    record Spirv(ByteBuffer vertex, String vertexEntry, ByteBuffer fragment, String fragmentEntry)
            implements CoreShaderSource {
    }

    /**
     * GLSL of both stages as shaderc receives it (the pipeline's and the front-end's defines
     * injected), with the shaderc file names (vanilla {@code Identifier.toDebugFileName()}), the
     * pipeline's declared samplers and the names of its vertex attributes in location order (the
     * declared uniform buffers and texel buffers are the description's uniforms). After
     * {@link GlslFrontEnd} the pipeline's uniforms are the link's bind group entries, which
     * blaze3d binds by name ({@link CorePipeline#slotOf(String)}).
     *
     * @param lenient the link's GL rules for 1.21.11 shaders ({@code frontend::link} Lenient)
     * @param glDepthRange the vertex shader writes GL's -1..1 depth and needs the z remap
     *        (1.21.11, whose projection matrices do not follow {@code isZZeroToOne})
     */
    record Glsl(String vertexName, String vertexText, String fragmentName, String fragmentText, List<String> samplers,
            List<String> attributeNames, boolean lenient, boolean glDepthRange) implements CoreShaderSource {
    }
}
