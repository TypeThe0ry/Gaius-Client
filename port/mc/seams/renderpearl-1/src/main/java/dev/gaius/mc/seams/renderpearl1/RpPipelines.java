package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.gaius.gpu.webgpu.CorePipelineDesc;
import dev.gaius.gpu.webgpu.CoreShaderSource;
import java.util.ArrayList;
import java.util.List;

/** {@link BackendRenderPipeline.CreateInfo} to {@link CorePipelineDesc}: the SPIR-V path of 26.3. */
final class RpPipelines {
    private RpPipelines() {
    }

    static CorePipelineDesc describe(BackendRenderPipeline.CreateInfo info) {
        List<CorePipelineDesc.VertexBuffer> buffers = new ArrayList<>(info.vertexBuffers().size());
        for (BackendRenderPipeline.CreateInfo.VertexBuffer buffer : info.vertexBuffers()) {
            buffers.add(new CorePipelineDesc.VertexBuffer(buffer.bufferSlot(), buffer.stride(), buffer.stepRate()));
        }
        List<CorePipelineDesc.Attribute> attributes = new ArrayList<>(info.attribBindings().size());
        for (BackendRenderPipeline.CreateInfo.AttribBinding attribute : info.attribBindings()) {
            attributes.add(new CorePipelineDesc.Attribute(attribute.bufferSlot(), attribute.location(), attribute.offset(),
                    RpEnums.FORMAT.toCore(attribute.format())));
        }
        List<CorePipelineDesc.Uniform> uniforms = new ArrayList<>(info.uniforms().size());
        for (BindGroupLayout.UniformDescription uniform : info.uniforms()) {
            uniforms.add(new CorePipelineDesc.Uniform(uniform.name(), RpEnums.UNIFORM_TYPE.toCore(uniform.type()),
                    uniform.gpuFormat() != null ? RpEnums.FORMAT.toCore(uniform.gpuFormat()) : null));
        }
        DepthStencilState depth = info.depthStencilState();
        CorePipelineDesc.DepthState depthState = depth == null ? null
                : new CorePipelineDesc.DepthState(RpEnums.COMPARE.toCore(depth.depthTest()), depth.writeDepth(),
                        depth.depthBiasConstant(), depth.depthBiasScaleFactor());
        List<CorePipelineDesc.ColorTarget> targets = new ArrayList<>(info.colorTargetStates().size());
        for (ColorTargetState target : info.colorTargetStates()) {
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
            targets.add(new CorePipelineDesc.ColorTarget(RpEnums.FORMAT.toCore(target.format()),
                    RpEnums.writeMask(target.writeMask()), color, alpha));
        }
        return new CorePipelineDesc(info.name(), shaders(info.shaders()), buffers, attributes, uniforms, depthState,
                RpEnums.POLYGON_MODE.toCore(info.polygonMode()), info.cull(), targets,
                RpEnums.TOPOLOGY.toCore(info.primitiveTopology()), info.pushConstantsSize());
    }

    /** The vanilla frontend's SPIR-V of both stages, or only the WGSL registry without shaders. */
    private static CoreShaderSource shaders(List<BackendRenderPipeline.CreateInfo.Shader> shaders) {
        BackendRenderPipeline.CreateInfo.Shader vertex = null;
        BackendRenderPipeline.CreateInfo.Shader fragment = null;
        for (BackendRenderPipeline.CreateInfo.Shader shader : shaders) {
            if (shader.module().type() == ShaderType.VERTEX) {
                vertex = shader;
            } else if (shader.module().type() == ShaderType.FRAGMENT) {
                fragment = shader;
            }
        }
        if (vertex == null || fragment == null) {
            return new CoreShaderSource.Registered();
        }
        return new CoreShaderSource.Spirv(vertex.module().spv(), vertex.entryPoint(), fragment.module().spv(),
                fragment.entryPoint());
    }

    private static CorePipelineDesc.BlendComponent blend(BlendEquation equation) {
        return new CorePipelineDesc.BlendComponent(RpEnums.BLEND_FACTOR.toCore(equation.sourceFactor()),
                RpEnums.BLEND_FACTOR.toCore(equation.destFactor()), RpEnums.BLEND_OP.toCore(equation.op()));
    }
}
