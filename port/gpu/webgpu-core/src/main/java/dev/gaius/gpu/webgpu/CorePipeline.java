package dev.gaius.gpu.webgpu;

import java.util.List;

/**
 * A compiled pipeline. WebGPU pipelines are bound to the depth format of the pass they draw
 * in, so each pipeline keeps one variant per depth format ("" is no depth attachment), created
 * on first use; the variant vanilla most likely needs is created eagerly.
 */
public final class CorePipeline {
    private static final int MAX_VARIANTS = 4;

    /** Compilation in two steps: the description is captured first, the pipeline built on demand. */
    public interface Pending {
        /**
         * Builds the pipeline synchronously (never suspends, D11), or returns {@code null} when it
         * cannot be built on WebGPU; the reason goes to the device's debug messages.
         */
        CorePipeline finish();
    }

    private final CoreDevice device;
    private final String name;
    private final String json;
    private final String[] uniformNames;
    final BindingLayout layout;
    /** Identifies the bind group layouts; pipelines with equal keys share bind groups. */
    final String layoutKey;
    private final String[] variantFormats = new String[MAX_VARIANTS];
    private final int[] variantHandles = new int[MAX_VARIANTS];
    private int variantCount;
    private final int[] modules;
    /** TRIANGLE_FAN: {@link CorePass} draws through a fan index buffer (see {@link CoreTopology}). */
    final boolean fan;
    /** Whether {@link #modules} belong to this pipeline (a depth-texture variant shares them). */
    private final boolean ownsModules;
    /** Depth-texture variants by uniform mask ({@link #withDepthTextures}). */
    private java.util.HashMap<Long, CorePipeline> depthVariants;
    private boolean closed;

    private CorePipeline(CoreDevice device, String name, String json, String[] uniformNames, BindingLayout layout,
            int[] modules, boolean fan, boolean ownsModules) {
        this.device = device;
        this.name = name;
        this.json = json;
        this.uniformNames = uniformNames;
        this.layout = layout;
        this.layoutKey = device.layoutKey(layout.json);
        this.modules = modules;
        this.fan = fan;
        this.ownsModules = ownsModules;
    }

    /**
     * This pipeline for draws that bind depth textures to the filtered texture uniforms in
     * {@code mask} (bit i: uniform i): the same shaders and state with those textures declared
     * "unfilterable-float" and their samplers "non-filtering" ({@link BindingLayout#unfilterable}).
     * Variants are made on first use and kept.
     */
    CorePipeline withDepthTextures(long mask) {
        if (mask == 0L) {
            return this;
        }
        if (this.depthVariants == null) {
            this.depthVariants = new java.util.HashMap<>();
        }
        CorePipeline variant = this.depthVariants.get(mask);
        if (variant == null) {
            BindingLayout layout = this.layout.unfilterable(mask);
            String variantJson = this.json.replace("\"layout\":" + this.layout.json, "\"layout\":" + layout.json);
            variant = new CorePipeline(this.device, this.name, variantJson, this.uniformNames, layout, this.modules,
                    this.fan, false);
            this.depthVariants.put(mask, variant);
            WebGpuGlue.count("depthTextureVariants", 1);
        }
        return variant;
    }

    /**
     * Builds the pipeline, or returns {@code null} (vanilla then treats the pipeline as
     * unavailable) when no WGSL can be found or made for it or WebGPU cannot express it.
     */
    static CorePipeline compile(CoreDevice device, CorePipelineDesc desc) {
        String topology = desc.topology().webName();
        if (desc.topology() == CoreTopology.POINTS) {
            // WebGPU points are one pixel wide; the debug point pipeline's gl_PointSize is ignored.
            device.pointPipelineCreated(desc.name());
        }
        WgslSources.Program program = WgslSources.lookup(desc.name());
        BindingLayout layout = null;
        List<CorePipelineDesc.Uniform> uniforms = desc.uniforms();
        if (program == null && desc.shaders() instanceof CoreShaderSource.Wgsl wgsl) {
            program = wgsl.program();
        }
        if (program == null && desc.shaders() instanceof CoreShaderSource.Spirv spirv) {
            String texelBuffers = texelBuffers(desc.uniforms());
            try {
                WgslTranslator.Translation vertex = WgslSources.translate(desc.name(), CoreShaderStage.VERTEX,
                        spirv.vertex(), texelBuffers, true);
                WgslTranslator.Translation fragment = WgslSources.translate(desc.name(), CoreShaderStage.FRAGMENT,
                        spirv.fragment(), texelBuffers, true);
                layout = BindingLayout.reflected(desc.uniforms(), vertex.bindings(), fragment.bindings());
                program = new WgslSources.Program(vertex.wgsl(), spirv.vertexEntry(), fragment.wgsl(),
                        spirv.fragmentEntry());
            } catch (IllegalArgumentException e) {
                device.debugMessage("pipeline " + desc.name() + ": " + e.getMessage());
                return null;
            }
        }
        if (program == null && desc.shaders() instanceof CoreShaderSource.Glsl glsl) {
            // blaze3d: shaderc and the link first; the link's entries are the pipeline's uniforms.
            try {
                List<CoreFormat> attributeFormats = new java.util.ArrayList<>(desc.attributes().size());
                for (CorePipelineDesc.Attribute attribute : desc.attributes()) {
                    attributeFormats.add(attribute.format());
                }
                GlslFrontEnd.Linked linked = GlslFrontEnd.link(glsl, desc.uniforms(), attributeFormats);
                uniforms = linked.uniforms();
                String texelBuffers = texelBuffers(uniforms);
                // GLSL for GL's -1..1 depth range (1.21.11) gets the z remap at the vertex output.
                WgslTranslator.Translation vertex = WgslSources.translate(desc.name(), CoreShaderStage.VERTEX,
                        direct(linked.vertex()), texelBuffers, true, glsl.glDepthRange());
                WgslTranslator.Translation fragment = WgslSources.translate(desc.name(), CoreShaderStage.FRAGMENT,
                        direct(linked.fragment()), texelBuffers, true, glsl.glDepthRange());
                layout = BindingLayout.reflected(uniforms, vertex.bindings(), fragment.bindings());
                program = new WgslSources.Program(vertex.wgsl(), "main", fragment.wgsl(), "main");
            } catch (IllegalArgumentException e) {
                device.debugMessage("pipeline " + desc.name() + ": " + e.getMessage());
                return null;
            }
        }
        if (program == null) {
            device.debugMessage("no WGSL for pipeline " + desc.name());
            return null;
        }
        if (layout == null) {
            layout = BindingLayout.split(uniforms);
        }
        String[] names = new String[uniforms.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = uniforms.get(i).name();
        }
        int vertexModule = WebGpuGlue.createShaderModule(program.vertexCode(), desc.name());
        int fragmentModule = program.fragmentCode().equals(program.vertexCode())
                ? vertexModule
                : WebGpuGlue.createShaderModule(program.fragmentCode(), desc.name());
        String json;
        try {
            json = describe(desc, topology, layout, vertexModule, program.vertexEntry(), fragmentModule,
                    program.fragmentEntry());
        } catch (IllegalArgumentException e) {
            device.debugMessage("pipeline " + desc.name() + ": " + e.getMessage());
            WebGpuGlue.releaseHandle(vertexModule);
            if (fragmentModule != vertexModule) {
                WebGpuGlue.releaseHandle(fragmentModule);
            }
            return null;
        }
        CorePipeline pipeline = new CorePipeline(device, desc.name(), json, names, layout,
                vertexModule == fragmentModule ? new int[] { vertexModule } : new int[] { vertexModule, fragmentModule },
                desc.topology().isFan(), true);
        // Vanilla draws pipelines with depth state into passes with a D32 depth attachment.
        pipeline.variant(desc.depth() != null ? "depth32float" : "");
        return pipeline;
    }

    private static java.nio.ByteBuffer direct(byte[] bytes) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(bytes.length)
                .order(java.nio.ByteOrder.nativeOrder());
        buffer.put(bytes).flip();
        return buffer;
    }

    /** The texel buffers of a pipeline for the translator: {@code "index:FORMAT,..."}. */
    private static String texelBuffers(List<CorePipelineDesc.Uniform> uniforms) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < uniforms.size(); i++) {
            CorePipelineDesc.Uniform uniform = uniforms.get(i);
            if (uniform.type() == CoreUniformType.TEXEL_BUFFER && uniform.texelFormat() != null) {
                if (out.length() > 0) {
                    out.append(',');
                }
                out.append(i).append(':').append(uniform.texelFormat().name());
            }
        }
        return out.toString();
    }

    public String name() {
        return this.name;
    }

    /** Number of uniforms (the valid indices of the pass's uniform setters). */
    public int uniformCount() {
        return this.uniformNames.length;
    }

    /**
     * The uniform index of the uniform called {@code name}, or -1: blaze3d binds uniforms and
     * samplers by name, renderpearl by index.
     */
    public int slotOf(String name) {
        for (int i = 0; i < this.uniformNames.length; i++) {
            if (this.uniformNames[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The pipeline handle for a pass with the given depth format, or 0 when it failed. */
    int variant(String depthFormat) {
        for (int i = 0; i < this.variantCount; i++) {
            if (this.variantFormats[i].equals(depthFormat)) {
                return this.variantHandles[i];
            }
        }
        long start = System.nanoTime();
        int handle = WebGpuGlue.createPipeline(this.json, depthFormat);
        this.device.pipelineCreated(System.nanoTime() - start);
        if (this.variantCount < MAX_VARIANTS) {
            this.variantFormats[this.variantCount] = depthFormat;
            this.variantHandles[this.variantCount] = handle;
            this.variantCount++;
        }
        return handle;
    }

    public boolean isClosed() {
        return this.closed;
    }

    public void close() {
        if (!this.closed) {
            this.closed = true;
            for (int i = 0; i < this.variantCount; i++) {
                WebGpuGlue.releaseHandle(this.variantHandles[i]);
            }
            if (this.depthVariants != null) {
                for (CorePipeline variant : this.depthVariants.values()) {
                    variant.close();
                }
                this.depthVariants = null;
            }
            if (this.ownsModules) {
                for (int module : this.modules) {
                    WebGpuGlue.releaseHandle(module);
                }
            }
            this.variantCount = 0;
        }
    }

    private static String describe(CorePipelineDesc desc, String topology, BindingLayout layout, int vertexModule,
            String vertexEntry, int fragmentModule, String fragmentEntry) {
        StringBuilder out = new StringBuilder(512);
        out.append("{\"label\":");
        quote(out, desc.name());
        out.append(",\"vs\":").append(vertexModule).append(",\"vsEntry\":");
        quote(out, vertexEntry);
        out.append(",\"fs\":").append(fragmentModule).append(",\"fsEntry\":");
        quote(out, fragmentEntry);
        out.append(",\"topology\":\"").append(topology).append('"');
        out.append(",\"cull\":").append(desc.cull());
        out.append(",\"layout\":").append(layout.json);

        int slots = 0;
        for (CorePipelineDesc.VertexBuffer buffer : desc.vertexBuffers()) {
            slots = Math.max(slots, buffer.slot() + 1);
        }
        out.append(",\"buffers\":[");
        for (int slot = 0; slot < slots; slot++) {
            if (slot > 0) {
                out.append(',');
            }
            CorePipelineDesc.VertexBuffer buffer = null;
            for (CorePipelineDesc.VertexBuffer candidate : desc.vertexBuffers()) {
                if (candidate.slot() == slot) {
                    buffer = candidate;
                }
            }
            if (buffer == null) {
                out.append("null");
                continue;
            }
            out.append("{\"stride\":").append(buffer.stride()).append(",\"instance\":").append(buffer.stepRate() > 0)
                    .append(",\"attributes\":[");
            boolean first = true;
            for (CorePipelineDesc.Attribute attribute : desc.attributes()) {
                if (attribute.slot() != slot) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                String format = attribute.format().webVertexFormat();
                if (format == null) {
                    throw new IllegalArgumentException("no WebGPU vertex format for " + attribute.format());
                }
                out.append('[').append(attribute.location()).append(',').append(attribute.offset()).append(",\"")
                        .append(format).append("\"]");
            }
            out.append("]}");
        }
        out.append(']');

        out.append(",\"targets\":[");
        List<CorePipelineDesc.ColorTarget> targets = desc.colorTargets();
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            CorePipelineDesc.ColorTarget target = targets.get(i);
            if (target == null) {
                out.append("null");
                continue;
            }
            String format = target.format().webTextureFormat();
            if (format == null) {
                throw new IllegalArgumentException("no WebGPU texture format for " + target.format());
            }
            out.append("{\"format\":\"").append(format).append("\",\"mask\":").append(target.writeMask());
            if (target.color() != null) {
                out.append(",\"blend\":[");
                blendComponent(out, target.color());
                out.append(',');
                blendComponent(out, target.alpha());
                out.append(']');
            }
            out.append('}');
        }
        out.append(']');

        CorePipelineDesc.DepthState depth = desc.depth();
        if (depth != null) {
            // Line and point topologies must not carry a depth bias in WebGPU.
            boolean biasAllowed = !desc.topology().isLineOrPoint();
            out.append(",\"depth\":{\"compare\":\"").append(depth.compare().webName()).append("\",\"write\":")
                    .append(depth.write()).append(",\"bias\":")
                    .append(biasAllowed ? Math.round(depth.biasConstant()) : 0).append(",\"slope\":")
                    .append(biasAllowed ? depth.biasSlope() : 0.0F).append('}');
        }
        out.append('}');
        return out.toString();
    }

    private static void blendComponent(StringBuilder out, CorePipelineDesc.BlendComponent component) {
        out.append("[\"").append(component.source().webName()).append("\",\"")
                .append(component.destination().webName()).append("\",\"")
                .append(component.op().webName()).append("\"]");
    }

    static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }
}
