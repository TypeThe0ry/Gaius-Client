package dev.gaius.mc.seams.blaze3ddirect0;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.LogicOp;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.gaius.gpu.webgpu.CoreBlendOp;
import dev.gaius.gpu.webgpu.CoreDevice;
import dev.gaius.gpu.webgpu.CoreFormat;
import dev.gaius.gpu.webgpu.CorePipeline;
import dev.gaius.gpu.webgpu.CorePipelineDesc;
import dev.gaius.gpu.webgpu.CoreShaderSource;
import dev.gaius.gpu.webgpu.CoreTopology;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * 1.21.11 {@link RenderPipeline} to {@link CorePipelineDesc}: the GL state vocabulary of 1.21.11
 * (depth test function, source/destination factors, colour and alpha write flags, a single
 * vertex format) translated to the core's, and the GLSL through the core's front-end with the
 * lenient link and GL's depth range.
 *
 * <p>Refused here, with the reason in the device's debug messages (D11 disables the pipeline):
 * {@code LogicOp.OR_REVERSE} (WebGPU has no logic ops; no vanilla pipeline uses it), a vertex
 * format whose offsets or stride WebGPU cannot address (the LineWidth REQUIRED patch aligns
 * vanilla's {@code POSITION_COLOR_NORMAL_LINE_WIDTH}).</p>
 *
 * <p>A TRIANGLE_FAN pipeline is compiled as a triangle list; {@link D0RenderPass} draws its fans
 * through the core's {@code FanIndices} (the core's degrade table).</p>
 */
final class D0Pipelines {
    /** {@code GlslCompiler.globalDefines} of the shared front-end. */
    static final ShaderDefines GLOBAL_DEFINES = ShaderDefines.builder()
            .define("gl_VertexID", "gl_VertexIndex")
            .define("gl_InstanceID", "gl_InstanceIndex")
            .build();
    /** GL_KHR_vulkan_glsl's type names that 1.21.11 GLSL uses as identifiers (terrain.fsh). */
    private static final List<String> RESERVED = List.of("sampler", "samplerShadow", "subpassInput");

    private D0Pipelines() {
    }

    static @Nullable CorePipeline compile(CoreDevice core, RenderPipeline pipeline, ShaderSource source) {
        String name = pipeline.getLocation().toString();
        if (pipeline.getColorLogic() != LogicOp.NONE) {
            core.debugMessage("pipeline " + name + ": colour logic op " + pipeline.getColorLogic()
                    + " has no WebGPU equivalent");
            return null;
        }
        VertexFormat format = pipeline.getVertexFormat();
        List<CorePipelineDesc.VertexBuffer> buffers = new ArrayList<>();
        List<CorePipelineDesc.Attribute> attributes = new ArrayList<>();
        List<String> attributeNames = new ArrayList<>();
        List<VertexFormatElement> elements = format.getElements();
        if (!elements.isEmpty()) {
            if ((format.getVertexSize() & 3) != 0) {
                core.debugMessage("pipeline " + name + ": vertex stride " + format.getVertexSize()
                        + " is not a multiple of 4 (the LineWidth alignment patch is missing)");
                return null;
            }
            buffers.add(new CorePipelineDesc.VertexBuffer(0, format.getVertexSize(), 0));
            List<String> names = format.getElementAttributeNames();
            for (int i = 0; i < elements.size(); i++) {
                VertexFormatElement element = elements.get(i);
                int offset = format.getOffset(element);
                CoreFormat vertexFormat = D0Enums.vertexFormat(element);
                if (offset % Math.min(4, vertexFormat.blockSize()) != 0) {
                    core.debugMessage("pipeline " + name + ": vertex element " + names.get(i) + " at offset " + offset
                            + " is not aligned for WebGPU");
                    return null;
                }
                attributes.add(new CorePipelineDesc.Attribute(0, i, offset, vertexFormat));
                attributeNames.add(names.get(i));
            }
        }
        List<CorePipelineDesc.Uniform> uniforms = new ArrayList<>();
        for (RenderPipeline.UniformDescription uniform : pipeline.getUniforms()) {
            uniforms.add(new CorePipelineDesc.Uniform(uniform.name(), D0Enums.UNIFORM_TYPE.toCore(uniform.type()),
                    uniform.textureFormat() != null ? D0Enums.TEXTURE_FORMAT.toCore(uniform.textureFormat()) : null));
        }
        DepthTestFunction depthTest = pipeline.getDepthTestFunction();
        // GL: a disabled depth test also disables depth writes.
        CorePipelineDesc.DepthState depth = depthTest == DepthTestFunction.NO_DEPTH_TEST ? null
                : new CorePipelineDesc.DepthState(D0Enums.DEPTH_TEST.toCore(depthTest), pipeline.isWriteDepth(),
                        pipeline.getDepthBiasConstant(), pipeline.getDepthBiasScaleFactor());
        CorePipelineDesc.BlendComponent color = null;
        CorePipelineDesc.BlendComponent alpha = null;
        if (pipeline.getBlendFunction().isPresent()) {
            BlendFunction blend = pipeline.getBlendFunction().get();
            color = new CorePipelineDesc.BlendComponent(D0Enums.SOURCE_FACTOR.toCore(blend.sourceColor()),
                    D0Enums.DEST_FACTOR.toCore(blend.destColor()), CoreBlendOp.ADD);
            alpha = new CorePipelineDesc.BlendComponent(D0Enums.SOURCE_FACTOR.toCore(blend.sourceAlpha()),
                    D0Enums.DEST_FACTOR.toCore(blend.destAlpha()), CoreBlendOp.ADD);
        }
        int mask = (pipeline.isWriteColor() ? 1 | 2 | 4 : 0) | (pipeline.isWriteAlpha() ? 8 : 0);
        // 1.21.11 renders into RGBA8 targets only.
        CorePipelineDesc.ColorTarget target = new CorePipelineDesc.ColorTarget(CoreFormat.RGBA8_UNORM, mask, color, alpha);
        CorePipelineDesc desc = new CorePipelineDesc(name, shaders(pipeline, source, attributeNames), buffers, attributes,
                uniforms, depth, D0Enums.POLYGON_MODE.toCore(pipeline.getPolygonMode()), pipeline.isCull(),
                List.of(target), topology(pipeline.getVertexFormatMode()), 0);
        return core.compile(desc).finish();
    }

    private static CoreTopology topology(VertexFormat.Mode mode) {
        return mode == VertexFormat.Mode.TRIANGLE_FAN ? CoreTopology.TRIANGLES : D0Enums.MODE.toCore(mode);
    }

    private static CoreShaderSource shaders(RenderPipeline pipeline, ShaderSource source, List<String> attributeNames) {
        String vertex = source.get(pipeline.getVertexShader(), ShaderType.VERTEX);
        String fragment = source.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
        if (vertex == null || fragment == null) {
            return new CoreShaderSource.Registered();
        }
        // The one fix shaderc needs before compiling (the rest is the lenient link's).
        for (String word : RESERVED) {
            Pattern identifier = Pattern.compile("\\b" + word + "\\b(?!\\s*\\()");
            if (identifier.matcher(vertex).find() || identifier.matcher(fragment).find()) {
                vertex = identifier.matcher(vertex).replaceAll("gaius_" + word);
                fragment = identifier.matcher(fragment).replaceAll("gaius_" + word);
            }
        }
        return new CoreShaderSource.Glsl(fileName(pipeline.getVertexShader()), defines(pipeline, vertex),
                fileName(pipeline.getFragmentShader()), defines(pipeline, fragment), pipeline.getSamplers(),
                attributeNames, true, true);
    }

    private static String defines(RenderPipeline pipeline, String text) {
        return GlslPreprocessor.injectDefines(GlslPreprocessor.injectDefines(text, pipeline.getShaderDefines()),
                GLOBAL_DEFINES);
    }

    private static String fileName(Identifier id) {
        return id.toDebugFileName();
    }
}
