package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The default translator: the {@code gaius-shaderxlat} kernel on a synchronous instance that the
 * page installs as {@code GaiusWebGpu.translator} (see the glue's "shader translation hook").
 */
final class GlueTranslator implements WgslTranslator {
    static final GlueTranslator INSTANCE = new GlueTranslator();

    private GlueTranslator() {
    }

    @Override
    public Translation translate(String pipelineName, CoreShaderStage stage, ByteBuffer spirv, String texelBuffers,
            boolean flipVertexY) {
        return this.translate(pipelineName, stage, spirv, texelBuffers, flipVertexY, false);
    }

    /** The glue's job flags: bit 0 flips the vertex y, bit 2 remaps GL's depth range. */
    @Override
    public Translation translate(String pipelineName, CoreShaderStage stage, ByteBuffer spirv, String texelBuffers,
            boolean flipVertexY, boolean glDepthRemap) {
        if (!WebGpuGlue.hasTranslator()) {
            throw new IllegalArgumentException("no SPIR-V translator is installed (GaiusWebGpu.translator)");
        }
        ByteBuffer source = spirv;
        if (!source.isDirect()) {
            // The glue reads direct buffers only (a view of their backing memory).
            source = ByteBuffer.allocateDirect(spirv.remaining()).order(ByteOrder.nativeOrder());
            source.put(spirv.duplicate()).flip();
        }
        int count = WebGpuGlue.translateSpirv(source, texelBuffers, (flipVertexY ? 1 : 0) | (glDepthRemap ? 4 : 0));
        if (count < 0) {
            throw new IllegalArgumentException(WebGpuGlue.translateError());
        }
        int translatedStage = WebGpuGlue.translatedStage();
        if (translatedStage != (stage == CoreShaderStage.FRAGMENT ? 1 : 0)) {
            throw new IllegalArgumentException("the " + stage + " module translated as another stage");
        }
        List<Binding> bindings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int at = i * 8;
            bindings.add(new Binding(WebGpuGlue.translatedWord(at), WebGpuGlue.translatedWord(at + 1),
                    WebGpuGlue.translatedWord(at + 2), WebGpuGlue.translatedWord(at + 3),
                    WebGpuGlue.translatedWord(at + 4), WebGpuGlue.translatedWord(at + 5),
                    WebGpuGlue.translatedWord(at + 6), WebGpuGlue.translatedWord(at + 7) != 0));
        }
        return new Translation(WebGpuGlue.translatedWgsl(), bindings);
    }
}
