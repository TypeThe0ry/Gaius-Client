package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.gaius.gpu.webgpu.CorePipelineDesc;
import dev.gaius.gpu.webgpu.CoreShaderSource;
import dev.gaius.gpu.webgpu.CoreTopology;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

/**
 * {@link RenderPipeline} to {@link CorePipelineDesc}: the GLSL path of 26.2. The shader text is
 * what vanilla's Vulkan backend hands to shaderc ({@code VulkanDevice.compileShader}: the
 * pipeline's defines injected, then {@code GlslCompiler}'s global defines), the vertex
 * attributes take locations in element order over all bindings ({@code VulkanRenderPipeline}).
 * A TRIANGLE_FAN pipeline is compiled as a triangle list; {@link B1RenderPass} draws its fans
 * through the core's {@code FanIndices} (the core's degrade table).
 */
final class B1Pipelines {
    /** {@code GlslCompiler.globalDefines}: Vulkan GLSL names of the GL vertex and instance ids. */
    static final ShaderDefines GLOBAL_DEFINES = ShaderDefines.builder()
            .define("gl_VertexID", "gl_VertexIndex")
            .define("gl_InstanceID", "gl_InstanceIndex")
            .build();

    private B1Pipelines() {
    }

    static CorePipelineDesc describe(RenderPipeline pipeline, ShaderSource source) {
        List<CorePipelineDesc.VertexBuffer> buffers = new ArrayList<>();
        List<CorePipelineDesc.Attribute> attributes = new ArrayList<>();
        List<String> attributeNames = new ArrayList<>();
        VertexFormat[] bindings = pipeline.getVertexFormatBindings();
        int location = 0;
        for (int slot = 0; slot < bindings.length; slot++) {
            VertexFormat format = bindings[slot];
            if (format == null) {
                continue;
            }
            buffers.add(new CorePipelineDesc.VertexBuffer(slot, format.getVertexSize(), format.getStepRate()));
            for (VertexFormatElement element : format.getElements()) {
                attributes.add(new CorePipelineDesc.Attribute(slot, location++, element.offset(),
                        B1Enums.FORMAT.toCore(element.format())));
                attributeNames.add(element.name());
            }
        }
        List<CorePipelineDesc.Uniform> uniforms = new ArrayList<>();
        for (BindGroupLayout.UniformDescription uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
            uniforms.add(new CorePipelineDesc.Uniform(uniform.name(), B1Enums.UNIFORM_TYPE.toCore(uniform.type()),
                    uniform.gpuFormat() != null ? B1Enums.FORMAT.toCore(uniform.gpuFormat()) : null));
        }
        List<String> samplers = BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts());
        DepthStencilState depth = pipeline.getDepthStencilState();
        CorePipelineDesc.DepthState depthState = depth == null ? null
                : new CorePipelineDesc.DepthState(B1Enums.COMPARE.toCore(depth.depthTest()), depth.writeDepth(),
                        depth.depthBiasConstant(), depth.depthBiasScaleFactor());
        List<CorePipelineDesc.ColorTarget> targets = new ArrayList<>();
        for (ColorTargetState target : pipeline.getColorTargetStates()) {
            if (target == null) {
                targets.add(null);
                continue;
            }
            CorePipelineDesc.BlendComponent color = null;
            CorePipelineDesc.BlendComponent alpha = null;
            if (target.blendFunction().isPresent()) {
                BlendFunction blend = target.blendFunction().get();
                color = blend(blend.color());
                alpha = blend(blend.alpha());
            }
            targets.add(new CorePipelineDesc.ColorTarget(B1Enums.FORMAT.toCore(target.format()),
                    B1Enums.writeMask(target.writeMask()), color, alpha));
        }
        return new CorePipelineDesc(pipeline.getLocation().toString(),
                shaders(pipeline, source, samplers, attributeNames), buffers, attributes, uniforms, depthState,
                B1Enums.POLYGON_MODE.toCore(pipeline.getPolygonMode()), pipeline.isCull(), targets,
                topology(pipeline.getPrimitiveTopology()), 0);
    }

    /** Whether the pipeline draws triangle fans (compiled as a triangle list, drawn through FanIndices). */
    static boolean isFan(RenderPipeline pipeline) {
        return pipeline.getPrimitiveTopology() == PrimitiveTopology.TRIANGLE_FAN;
    }

    private static CoreTopology topology(PrimitiveTopology topology) {
        return topology == PrimitiveTopology.TRIANGLE_FAN ? CoreTopology.TRIANGLES : B1Enums.TOPOLOGY.toCore(topology);
    }

    /** Both stages' GLSL as shaderc receives it, or no source (the pipeline is then unavailable). */
    private static CoreShaderSource shaders(RenderPipeline pipeline, ShaderSource source, List<String> samplers,
            List<String> attributeNames) {
        String vertex = stage(pipeline, source, pipeline.getVertexShader(), ShaderType.VERTEX);
        String fragment = stage(pipeline, source, pipeline.getFragmentShader(), ShaderType.FRAGMENT);
        if (vertex == null || fragment == null) {
            return new CoreShaderSource.Registered();
        }
        return new CoreShaderSource.Glsl(pipeline.getVertexShader().toDebugFileName(), vertex,
                pipeline.getFragmentShader().toDebugFileName(), fragment, samplers, attributeNames, false, false);
    }

    private static String stage(RenderPipeline pipeline, ShaderSource source, Identifier id, ShaderType type) {
        String text = source != null ? source.get(id, type) : null;
        if (text == null) {
            return null;
        }
        return GlslPreprocessor.injectDefines(GlslPreprocessor.injectDefines(text, pipeline.getShaderDefines()),
                GLOBAL_DEFINES);
    }

    private static CorePipelineDesc.BlendComponent blend(BlendEquation equation) {
        return new CorePipelineDesc.BlendComponent(B1Enums.BLEND_FACTOR.toCore(equation.sourceFactor()),
                B1Enums.BLEND_FACTOR.toCore(equation.destFactor()), B1Enums.BLEND_OP.toCore(equation.op()));
    }
}
