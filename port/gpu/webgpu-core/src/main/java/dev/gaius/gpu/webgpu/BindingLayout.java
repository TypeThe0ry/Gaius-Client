package dev.gaius.gpu.webgpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where each uniform of a pipeline is bound: group, binding and, for textures, the binding of the
 * split-off sampler, plus the bind group layout entries the glue builds.
 *
 * <p>Without reflection (WGSL given directly) the layout is the binding contract of the
 * "Split" layout: uniform buffer {@code i} at group 0 binding {@code i} with a dynamic offset, texture
 * {@code i} at group 1 binding {@code 2i} with its sampler at {@code 2i + 1}, texel buffer
 * {@code i} at group 1 binding {@code 2i}. With the translator's reflection the layout follows
 * what the shaders declare (group, binding, texture dimension and sample type, which samplers are
 * used), so the backend matches the WGSL whatever binding layout the translator emits, as long as
 * every uniform buffer is in group 0 (the dynamic offset path).</p>
 */
final class BindingLayout {
    static final byte NONE = -1;
    static final byte UNIFORM_BUFFER = 0;
    static final byte TEXTURE = 1;
    static final byte TEXEL_BUFFER = 2;

    private static final String[] DIMENSIONS = { "1d", "2d", "2d-array", "3d", "cube", "cube-array" };

    /** Per uniform: its kind, or {@link #NONE} when no stage declares it (nothing is bound). */
    final byte[] kinds;
    final int[] groups;
    final int[] bindings;
    /** Per texture uniform: the sampler binding, or -1 when the layout has no sampler for it. */
    final int[] samplerBindings;
    /** Uniform indices of the uniform buffers in binding order: the dynamic offset order. */
    final int[] dynamic;
    /** Uniform indices bound in group 0 and group 1, in binding order. */
    final int[] group0;
    final int[] group1;
    /** The layout entries in the glue's format (a JSON array). */
    final String json;
    /** The entries of {@link #json}, one JSON array each. */
    private final List<String> entries;
    /** Per uniform: the index in {@link #entries} of its texture entry and of its sampler entry, or -1. */
    private final int[] textureEntries;
    private final int[] samplerEntries;
    /**
     * Per texture uniform: the shaders filter it ("float" texture with a "filtering" sampler), so a
     * depth texture bound there needs the {@link #unfilterable(long)} variant of the layout.
     */
    final boolean[] filtered;
    /** Per texture uniform: its sampler entry is "non-filtering" only because of a depth texture. */
    final boolean[] depthSampled;

    private BindingLayout(byte[] kinds, int[] groups, int[] bindings, int[] samplerBindings, List<String> entries,
            int[] textureEntries, int[] samplerEntries, boolean[] depthSampled) {
        this.kinds = kinds;
        this.groups = groups;
        this.bindings = bindings;
        this.samplerBindings = samplerBindings;
        this.entries = entries;
        this.textureEntries = textureEntries;
        this.samplerEntries = samplerEntries;
        this.json = "[" + String.join(",", entries) + "]";
        this.dynamic = select(kinds, groups, bindings, 0, true);
        this.group0 = select(kinds, groups, bindings, 0, false);
        this.group1 = select(kinds, groups, bindings, 1, false);
        this.filtered = new boolean[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            this.filtered[i] = kinds[i] == TEXTURE && samplerEntries[i] >= 0
                    && entries.get(textureEntries[i]).contains(",\"float\",")
                    && entries.get(samplerEntries[i]).endsWith(",\"filtering\"]");
        }
        this.depthSampled = depthSampled;
    }

    /**
     * This layout with the textures of the uniforms in {@code mask} (bit i: uniform i) declared
     * "unfilterable-float" and their samplers "non-filtering": WebGPU samples a depth texture only
     * like that (or as "depth"), while GL and Vulkan let a shader sample one through a plain
     * {@code sampler2D} (vanilla's {@code blit_depth}). The pass binds a non-filtering copy of the
     * sampler there.
     */
    BindingLayout unfilterable(long mask) {
        List<String> copy = new ArrayList<>(this.entries);
        boolean[] sampledDepth = new boolean[this.kinds.length];
        for (int i = 0; i < this.kinds.length && i < 64; i++) {
            if ((mask & (1L << i)) != 0 && this.filtered[i]) {
                copy.set(this.textureEntries[i], copy.get(this.textureEntries[i]).replace(",\"float\",",
                        ",\"unfilterable-float\","));
                copy.set(this.samplerEntries[i], copy.get(this.samplerEntries[i]).replace(",\"filtering\"]",
                        ",\"non-filtering\"]"));
                sampledDepth[i] = true;
            }
        }
        return new BindingLayout(this.kinds, this.groups, this.bindings, this.samplerBindings, copy,
                this.textureEntries, this.samplerEntries, sampledDepth);
    }

    /** Uniform indices in {@code group} by binding; {@code buffersOnly} keeps uniform buffers only. */
    private static int[] select(byte[] kinds, int[] groups, int[] bindings, int group, boolean buffersOnly) {
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] != NONE && groups[i] == group && (!buffersOnly || kinds[i] == UNIFORM_BUFFER)) {
                selected.add(i);
            }
        }
        selected.sort((a, b) -> Integer.compare(bindings[a], bindings[b]));
        int[] out = new int[selected.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = selected.get(i);
        }
        return out;
    }

    static byte kindOf(CoreUniformType type) {
        return switch (type) {
            case UNIFORM_BUFFER -> UNIFORM_BUFFER;
            case COMBINED_IMAGE_SAMPLER -> TEXTURE;
            case TEXEL_BUFFER -> TEXEL_BUFFER;
        };
    }

    /** The "Split" layout for {@code uniforms}. */
    static BindingLayout split(List<CorePipelineDesc.Uniform> uniforms) {
        int count = uniforms.size();
        byte[] kinds = new byte[count];
        int[] groups = new int[count];
        int[] bindings = new int[count];
        int[] samplers = new int[count];
        List<String> json = new ArrayList<>();
        int[] textureEntries = new int[count];
        int[] samplerEntries = new int[count];
        Arrays.fill(textureEntries, -1);
        Arrays.fill(samplerEntries, -1);
        for (int i = 0; i < count; i++) {
            byte kind = kindOf(uniforms.get(i).type());
            kinds[i] = kind;
            samplers[i] = -1;
            if (kind == UNIFORM_BUFFER) {
                groups[i] = 0;
                bindings[i] = i;
                entry(json, 0, i, "\"ubo\"");
            } else if (kind == TEXEL_BUFFER) {
                groups[i] = 1;
                bindings[i] = 2 * i;
                entry(json, 1, 2 * i, "\"storage\"");
            } else {
                groups[i] = 1;
                bindings[i] = 2 * i;
                samplers[i] = 2 * i + 1;
                textureEntries[i] = entry(json, 1, 2 * i, "\"texture\",\"float\",\"2d\",false");
                samplerEntries[i] = entry(json, 1, 2 * i + 1, "\"sampler\",\"filtering\"");
            }
        }
        return new BindingLayout(kinds, groups, bindings, samplers, json, textureEntries, samplerEntries,
                new boolean[count]);
    }

    /**
     * The layout the translated stages declare. Throws {@link IllegalArgumentException} when the
     * reflection contradicts the pipeline's uniforms or the core's constraints.
     */
    static BindingLayout reflected(List<CorePipelineDesc.Uniform> uniforms, List<WgslTranslator.Binding> vertex,
            List<WgslTranslator.Binding> fragment) {
        int count = uniforms.size();
        WgslTranslator.Binding[] primary = new WgslTranslator.Binding[count];
        WgslTranslator.Binding[] sampler = new WgslTranslator.Binding[count];
        boolean[] primaryUsed = new boolean[count];
        boolean[] samplerUsed = new boolean[count];
        boolean[] sampled = new boolean[count];
        List<WgslTranslator.Binding> all = new ArrayList<>(vertex);
        all.addAll(fragment);
        for (WgslTranslator.Binding binding : all) {
            int u = binding.uniform();
            if (u < 0 || u >= count) {
                throw new IllegalArgumentException("binding " + binding.group() + "/" + binding.binding()
                        + " names uniform " + u + " of " + count);
            }
            if (binding.group() < 0 || binding.group() > 1) {
                throw new IllegalArgumentException("uniform " + uniforms.get(u).name() + " is in bind group "
                        + binding.group() + "; the core binds groups 0 and 1");
            }
            boolean isSampler = binding.kind() == WgslTranslator.Binding.KIND_SAMPLER;
            WgslTranslator.Binding[] slot = isSampler ? sampler : primary;
            WgslTranslator.Binding previous = slot[u];
            if (previous != null && (previous.group() != binding.group() || previous.binding() != binding.binding()
                    || previous.kind() != binding.kind())) {
                throw new IllegalArgumentException("the stages bind uniform " + uniforms.get(u).name()
                        + " differently");
            }
            slot[u] = binding;
            if (isSampler) {
                samplerUsed[u] |= binding.used();
            } else {
                primaryUsed[u] |= binding.used();
                if (binding.kind() == WgslTranslator.Binding.KIND_TEXTURE && (binding.c() & 2) != 0) {
                    sampled[u] = true;
                }
            }
        }

        byte[] kinds = new byte[count];
        int[] groups = new int[count];
        int[] bindings = new int[count];
        int[] samplers = new int[count];
        Arrays.fill(samplers, -1);
        List<String> json = new ArrayList<>();
        int[] textureEntries = new int[count];
        int[] samplerEntries = new int[count];
        Arrays.fill(textureEntries, -1);
        Arrays.fill(samplerEntries, -1);
        for (int i = 0; i < count; i++) {
            CorePipelineDesc.Uniform uniform = uniforms.get(i);
            byte kind = kindOf(uniform.type());
            WgslTranslator.Binding binding = primary[i];
            if (binding == null) {
                // Neither stage declares it: nothing to bind.
                kinds[i] = NONE;
                continue;
            }
            int expected = kind == UNIFORM_BUFFER ? WgslTranslator.Binding.KIND_UNIFORM
                    : kind == TEXEL_BUFFER ? WgslTranslator.Binding.KIND_STORAGE : WgslTranslator.Binding.KIND_TEXTURE;
            if (binding.kind() != expected) {
                throw new IllegalArgumentException("uniform " + uniform.name() + " (" + uniform.type()
                        + ") is reflected as binding kind " + binding.kind());
            }
            if (kind == UNIFORM_BUFFER && binding.group() != 0) {
                throw new IllegalArgumentException("uniform buffer " + uniform.name()
                        + " must be in group 0 (dynamic offsets)");
            }
            kinds[i] = kind;
            groups[i] = binding.group();
            bindings[i] = binding.binding();
            if (kind == UNIFORM_BUFFER) {
                entry(json, binding.group(), binding.binding(), "\"ubo\"");
            } else if (kind == TEXEL_BUFFER) {
                entry(json, binding.group(), binding.binding(), "\"storage\"");
            } else {
                String sampleType = switch (binding.b()) {
                    case 1 -> "sint";
                    case 2 -> "uint";
                    case 3 -> "depth";
                    default -> sampled[i] ? "float" : "unfilterable-float";
                };
                int dimension = binding.a();
                String viewDimension = dimension >= 0 && dimension < DIMENSIONS.length ? DIMENSIONS[dimension] : "2d";
                textureEntries[i] = entry(json, binding.group(), binding.binding(), "\"texture\",\"" + sampleType
                        + "\",\"" + viewDimension + "\"," + ((binding.c() & 1) != 0));
                WgslTranslator.Binding split = sampler[i];
                // A sampler no stage uses stays out of the layout: its filtering mode could not
                // match an unfilterable texture.
                if (split != null && samplerUsed[i]) {
                    if (split.group() != binding.group()) {
                        throw new IllegalArgumentException("the sampler of " + uniform.name()
                                + " is not in its texture's bind group");
                    }
                    samplers[i] = split.binding();
                    String type = split.a() != 0 ? "comparison" : "float".equals(sampleType) ? "filtering"
                            : "non-filtering";
                    samplerEntries[i] = entry(json, split.group(), split.binding(), "\"sampler\",\"" + type + "\"");
                }
            }
        }
        checkUnique(kinds, groups, bindings, samplers, uniforms);
        return new BindingLayout(kinds, groups, bindings, samplers, json, textureEntries, samplerEntries,
                new boolean[count]);
    }

    private static void checkUnique(byte[] kinds, int[] groups, int[] bindings, int[] samplers,
            List<CorePipelineDesc.Uniform> uniforms) {
        List<Long> seen = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == NONE) {
                continue;
            }
            long[] slots = samplers[i] >= 0 ? new long[] { slot(groups[i], bindings[i]), slot(groups[i], samplers[i]) }
                    : new long[] { slot(groups[i], bindings[i]) };
            for (long slot : slots) {
                if (seen.contains(slot)) {
                    throw new IllegalArgumentException("uniform " + uniforms.get(i).name() + " reuses binding "
                            + (slot >>> 32) + "/" + (slot & 0xffffffffL));
                }
                seen.add(slot);
            }
        }
    }

    private static long slot(int group, int binding) {
        return ((long) group << 32) | (binding & 0xffffffffL);
    }

    /** Appends an entry; returns its index. */
    private static int entry(List<String> json, int group, int binding, String rest) {
        json.add("[" + group + "," + binding + "," + rest + "]");
        return json.size() - 1;
    }
}
