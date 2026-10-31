package dev.gaius.gpu.webgpu;

import java.util.Arrays;
import java.util.HashMap;

/**
 * The open render pass. One instance per command encoder is reused for every pass.
 *
 * <p>Uniforms are addressed by their index in the pipeline description (renderpearl's order;
 * blaze3d seams find the index with {@link CorePipeline#slotOf(String)}); the pipeline's
 * {@link BindingLayout} maps them to group and binding. Uniform buffers live in bind group 0 with
 * dynamic offsets: a draw that only moves a uniform slice inside the same buffer (vanilla's
 * per-section {@code ChunkSection} slices) costs a {@code setBindGroup} with new offsets, fused into
 * the draw call, and no new object. Textures, samplers and texel buffers live in group 1 (or in
 * group 0, when the translator's layout puts them there). Bind groups are cached by their
 * resources.</p>
 *
 * <p>The pass state is recorded so the pass can be split: a write mapping committed while the
 * pass is open ends it, encodes the copy and continues in a new pass that loads the attachments
 * ({@link #suspend()}, {@link #resume()}).</p>
 */
public final class CorePass {
    private static final int MAX_INLINE_OFFSETS = 4;
    private static final int MAX_CACHED_BIND_GROUPS = 4096;
    private static final int MAX_VERTEX_BUFFERS = 8;

    private final CoreEncoder encoder;
    /** Hash buckets retain the exact resource signature so a 64-bit fingerprint cannot alias. */
    private final HashMap<Long, java.util.ArrayList<BindGroupCacheEntry>> bindGroupCache = new HashMap<>();
    /** Entries in all buckets of {@link #bindGroupCache}, kept so that a miss does not walk the cache. */
    private int bindGroupCacheEntries;

    private static final class BindGroupCacheEntry {
        final int pipeline;
        final int group;
        final int[] signature;
        final int handle;

        BindGroupCacheEntry(int pipeline, int group, int[] signature, int handle) {
            this.pipeline = pipeline;
            this.group = group;
            this.signature = signature;
            this.handle = handle;
        }
    }

    private String depthFormat = "";
    private int areaX;
    private int areaY;
    private int areaWidth;
    private int areaHeight;
    private boolean scissorSet;
    private int scissorX;
    private int scissorY;
    private int scissorWidth;
    private int scissorHeight;

    private CorePipeline pipeline;
    /** The pipeline vanilla set; {@link #pipeline} is it or its variant for the bound depth textures. */
    private CorePipeline basePipeline;
    /** Filtered texture uniforms (bit i: uniform i) that have a depth texture bound. */
    private long depthTextures;
    private int pipelineHandle;
    private String layoutKey = "";
    private boolean skipDraws;

    // Bound resource per uniform index: a = buffer or view handle, b = sampler handle.
    private boolean[] set = new boolean[16];
    private int[] resourceA = new int[16];
    private int[] resourceB = new int[16];
    private int[] resourceOffset = new int[16];
    private int[] resourceLength = new int[16];
    /** Largest legal dynamic offset for the slice's logical and padded binding lengths. */
    private int[] resourceMaxOffset = new int[16];
    private CoreSampler[] resourceSampler = new CoreSampler[16];
    /** The uniform at this index binds a renameable buffer: offsets move through setBuffer. */
    private boolean[] renamedSlot = new boolean[16];

    private boolean group0Dirty;
    private boolean group1Dirty;
    private int boundGroup0;
    private int boundGroup1;
    private final int[] offsets = new int[16];
    private final int[] boundOffsets = new int[16];
    private int boundOffsetCount = -1;

    private final int[] vertexHandles = new int[MAX_VERTEX_BUFFERS];
    private final int[] vertexOffsets = new int[MAX_VERTEX_BUFFERS];
    private final int[] vertexSizes = new int[MAX_VERTEX_BUFFERS];
    private CoreBuffer indexBuffer;
    private int indexHandle;
    private boolean indexWide;
    private int indexOffset;
    /** A fan draw bound the fan index buffer: vanilla's index buffer must be bound again. */
    private boolean indexOverridden;

    private int drawsSkipped;
    private int fanDraws;
    private int fanDrawsSkipped;

    CorePass(CoreEncoder encoder) {
        this.encoder = encoder;
    }

    void begin(String depthFormat, int x, int y, int width, int height, boolean full) {
        this.depthFormat = depthFormat;
        this.areaX = x;
        this.areaY = y;
        this.areaWidth = width;
        this.areaHeight = height;
        this.pipeline = null;
        this.basePipeline = null;
        this.depthTextures = 0L;
        this.pipelineHandle = 0;
        this.layoutKey = "";
        Arrays.fill(this.set, false);
        this.group0Dirty = true;
        this.group1Dirty = true;
        this.boundGroup0 = 0;
        this.boundGroup1 = 0;
        this.boundOffsetCount = -1;
        this.skipDraws = true;
        Arrays.fill(this.vertexHandles, 0);
        this.indexBuffer = null;
        this.indexHandle = 0;
        this.indexOverridden = false;
        this.scissorSet = !full;
        if (!full) {
            this.scissor(x, y, width, height);
        }
    }

    /** Draws skipped so far because the pipeline failed or a uniform was missing. */
    public int drawsSkipped() {
        return this.drawsSkipped;
    }

    /** Triangle fan draws converted to triangle lists so far. */
    public int fanDraws() {
        return this.fanDraws;
    }

    /** Indexed triangle fan draws skipped so far: their index buffer is not a sequential one. */
    public int fanDrawsSkipped() {
        return this.fanDrawsSkipped;
    }

    /** The encoder that owns this pass. */
    public CoreEncoder encoder() {
        return this.encoder;
    }

    public void setPipeline(CorePipeline pipeline) {
        if (this.basePipeline == pipeline) {
            // The same pipeline again: bound resources stay, so does the depth-texture variant.
            pipeline = this.pipeline;
        } else {
            this.basePipeline = pipeline;
            this.depthTextures = 0L;
            int count = pipeline.uniformCount();
            if (this.set.length < count) {
                this.set = new boolean[count];
                this.resourceA = new int[count];
                this.resourceB = new int[count];
                this.resourceOffset = new int[count];
                this.resourceLength = new int[count];
                this.resourceMaxOffset = new int[count];
                this.resourceSampler = new CoreSampler[count];
                this.renamedSlot = new boolean[count];
            } else {
                Arrays.fill(this.set, false);
            }
            if (!pipeline.layoutKey.equals(this.layoutKey)) {
                this.layoutKey = pipeline.layoutKey;
                this.boundGroup0 = 0;
                this.boundGroup1 = 0;
                this.boundOffsetCount = -1;
            }
            this.group0Dirty = true;
            this.group1Dirty = true;
        }
        this.pipeline = pipeline;
        int handle = pipeline.variant(this.depthFormat);
        this.skipDraws = handle == 0;
        if (handle != 0 && handle != this.pipelineHandle) {
            WebGpuGlue.setPipeline(handle);
        }
        this.pipelineHandle = handle;
    }

    /**
     * Binds {@code [offset, offset + length)} of a buffer to uniform {@code index}: a uniform
     * buffer (bound with a dynamic offset) or a texel buffer, as the pipeline declares it. Offsets
     * and lengths are ints on the draw path: WebGPU binds and copies with 32-bit offsets, and on
     * the TeaVM JavaScript target every {@code long} operation costs far more than an int one.
     */
    public void setBuffer(int index, CoreBuffer buffer, int offset, int length) {
        CorePipeline current = this.pipeline;
        if (current == null || index >= current.uniformCount()) {
            return;
        }
        byte kind = current.layout.kinds[index];
        if (kind == BindingLayout.UNIFORM_BUFFER) {
            this.renamedSlot[index] = this.encoder.renameable(buffer);
            if (this.renamedSlot[index]) {
                CoreSlice renamed = this.encoder.uniformRead(buffer, offset, length);
                if (renamed != null) {
                    buffer = renamed.buffer();
                    offset = (int) renamed.offset();
                }
            }
            this.validateUniformOffset(index, offset, Integer.MAX_VALUE);
            int size = bindingSize(buffer, offset, length, this.encoder.device().caps().maxUniformBufferBindingSize());
            boolean sameBinding = this.set[index] && this.resourceA[index] == buffer.handle
                    && this.resourceLength[index] == size;
            this.set[index] = true;
            this.resourceA[index] = buffer.handle;
            this.resourceLength[index] = size;
            this.resourceMaxOffset[index] = (int) Math.min(Integer.MAX_VALUE,
                    Math.min(buffer.size() - length, uniformCapacity(buffer) - size));
            this.resourceOffset[index] = offset;
            if (!sameBinding) {
                // Same binding: only the dynamic offset moves, prepareDraw picks it up.
                this.markDirty(current, index);
            }
        } else if (kind == BindingLayout.TEXEL_BUFFER) {
            int size = (length + 3) & ~3;
            if (this.set[index] && this.resourceA[index] == buffer.handle && this.resourceOffset[index] == offset
                    && this.resourceLength[index] == size) {
                return;
            }
            this.set[index] = true;
            this.resourceA[index] = buffer.handle;
            this.resourceOffset[index] = offset;
            this.resourceLength[index] = size;
            this.markDirty(current, index);
        } else if (kind == BindingLayout.TEXTURE) {
            this.clearUniform(index);
        }
    }

    /**
     * Moves the slice of the uniform buffer bound to {@code index} to {@code offset} (same buffer,
     * same length): the per-draw case of vanilla's section and dynamic-transform slices. Returns
     * {@code false}, changing nothing, when no uniform buffer is bound there (a new pipeline or
     * pass cleared it); the caller then binds with {@link #setBuffer}.
     */
    public boolean moveBuffer(int index, int offset) {
        CorePipeline current = this.pipeline;
        if (current == null || index >= current.uniformCount() || !this.set[index]
                || current.layout.kinds[index] != BindingLayout.UNIFORM_BUFFER || this.renamedSlot[index]) {
            return false;
        }
        this.validateUniformOffset(index, offset, this.resourceMaxOffset[index]);
        this.resourceOffset[index] = offset;
        return true;
    }

    private void validateUniformOffset(int index, int offset, int maxOffset) {
        int alignment = this.encoder.device().caps().minUniformOffsetAlignment();
        if (offset < 0 || offset > maxOffset || offset % alignment != 0) {
            throw new IllegalArgumentException("Uniform " + index + " offset " + offset
                    + " must be aligned to " + alignment + " and within [0, " + maxOffset + "]");
        }
    }

    /**
     * Binds a texture view and its sampler to uniform {@code index}. A depth texture bound where
     * the shaders filter switches to the pipeline's depth-texture variant
     * ({@link CorePipeline#withDepthTextures}), with a non-filtering copy of the sampler.
     */
    public void setTexture(int index, CoreTextureView view, CoreSampler sampler) {
        CorePipeline current = this.pipeline;
        if (current == null || index >= current.uniformCount()) {
            return;
        }
        if (current.layout.kinds[index] != BindingLayout.TEXTURE) {
            this.clearUniform(index);
            return;
        }
        if (index < 64 && this.basePipeline.layout.filtered[index]) {
            long bit = 1L << index;
            long mask = view.texture().format().hasDepthAspect() ? this.depthTextures | bit : this.depthTextures & ~bit;
            if (mask != this.depthTextures) {
                this.useDepthVariant(mask);
                current = this.pipeline;
            }
        }
        if (this.set[index] && this.resourceA[index] == view.handle && this.resourceB[index] == sampler.handle) {
            return;
        }
        this.set[index] = true;
        this.resourceA[index] = view.handle;
        this.resourceB[index] = sampler.handle;
        this.resourceSampler[index] = sampler;
        this.markDirty(current, index);
    }

    /** Draws continue with the base pipeline's variant for {@code mask}; bound resources stay. */
    private void useDepthVariant(long mask) {
        this.depthTextures = mask;
        CorePipeline variant = this.basePipeline.withDepthTextures(mask);
        this.pipeline = variant;
        if (!variant.layoutKey.equals(this.layoutKey)) {
            this.layoutKey = variant.layoutKey;
            this.boundGroup0 = 0;
            this.boundGroup1 = 0;
            this.boundOffsetCount = -1;
        }
        this.group0Dirty = true;
        this.group1Dirty = true;
        int handle = variant.variant(this.depthFormat);
        this.skipDraws = handle == 0;
        if (handle != 0 && handle != this.pipelineHandle) {
            WebGpuGlue.setPipeline(handle);
        }
        this.pipelineHandle = handle;
    }

    /** Unbinds uniform {@code index}; draws are skipped until it is bound again. */
    public void clearUniform(int index) {
        CorePipeline current = this.pipeline;
        if (current == null || index >= current.uniformCount() || !this.set[index]) {
            return;
        }
        this.set[index] = false;
        this.markDirty(current, index);
    }

    private void markDirty(CorePipeline current, int index) {
        if (current.layout.kinds[index] == BindingLayout.NONE) {
            return;
        }
        if (current.layout.groups[index] == 0) {
            this.group0Dirty = true;
        } else {
            this.group1Dirty = true;
        }
    }

    /** Confines draws to a rectangle, clipped to the render area. */
    public void enableScissor(int x, int y, int width, int height) {
        // WebGPU rejects scissor rectangles outside the attachment.
        int x0 = Math.max(x, this.areaX);
        int y0 = Math.max(y, this.areaY);
        int x1 = Math.min(x + width, this.areaX + this.areaWidth);
        int y1 = Math.min(y + height, this.areaY + this.areaHeight);
        this.scissorSet = true;
        this.scissor(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    public void disableScissor() {
        this.scissorSet = true;
        this.scissor(this.areaX, this.areaY, this.areaWidth, this.areaHeight);
    }

    private void scissor(int x, int y, int width, int height) {
        this.scissorX = x;
        this.scissorY = y;
        this.scissorWidth = width;
        this.scissorHeight = height;
        WebGpuGlue.setScissor(x, y, width, height);
    }

    public void setVertexBuffer(int slot, CoreBuffer buffer, int offset, int length) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Vertex buffer slot " + slot + " is out of range");
        }
        this.vertexHandles[slot] = buffer.handle;
        this.vertexOffsets[slot] = offset;
        this.vertexSizes[slot] = length;
        WebGpuGlue.setVertexBuffer(slot, buffer.handle, offset, length);
    }

    public void setIndexBuffer(CoreBuffer buffer, CoreIndexType type, int offset) {
        boolean wide = type == CoreIndexType.INT;
        this.indexBuffer = buffer;
        this.indexHandle = buffer.handle;
        this.indexWide = wide;
        this.indexOffset = offset;
        this.indexOverridden = false;
        WebGpuGlue.setIndexBuffer(buffer.handle, this.indexWide, offset);
    }

    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        if (this.pipeline != null && this.pipeline.fan) {
            this.drawIndexedFan(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
            return;
        }
        if (this.indexOverridden && this.indexHandle != 0) {
            this.indexOverridden = false;
            WebGpuGlue.setIndexBuffer(this.indexHandle, this.indexWide, this.indexOffset);
        }
        int pending = this.prepareDraw();
        if (pending == 0) {
            WebGpuGlue.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
        } else if (pending > 0) {
            int[] o = this.offsets;
            WebGpuGlue.drawIndexedWithOffsets(this.boundGroup0, pending, o[0], o[1], o[2], o[3], indexCount, instanceCount,
                    firstIndex, baseVertex, firstInstance);
        }
    }

    /** Replays indexed indirect records in their original order. */
    public void drawIndexedIndirect(CoreBuffer commands, int offset, int drawCount) {
        if (drawCount <= 0) {
            return;
        }
        // Vanilla validates DeviceFeatures.drawIndirect (any indirect draw) and multiDrawIndirect
        // (drawCount > 1) before this call; the 26.3 terrain gate itself is
        // DeviceLimits.maxDrawIndirectDrawCount > 0.  Keep the same split at the core boundary so an
        // old glue or a missing Wasm import never reaches either indirect entry point.
        String capability = drawCount > 1 ? "multi-draw-indirect" : "draw-indirect";
        if (!this.encoder.device().supportsCapability(capability)) {
            this.encoder.device().debugMessage("indexed indirect draw skipped: " + capability + " unavailable");
            return;
        }
        if (this.indexOverridden && this.indexHandle != 0) {
            this.indexOverridden = false;
            WebGpuGlue.setIndexBuffer(this.indexHandle, this.indexWide, this.indexOffset);
        }
        int pending = this.prepareDraw();
        if (pending < 0) {
            return;
        }
        if (pending != 0) {
            int[] o = this.offsets;
            WebGpuGlue.drawIndexedIndirectWithOffsets(this.boundGroup0, pending, o[0], o[1], o[2], o[3],
                    commands.handle, offset, drawCount);
            return;
        }
        WebGpuGlue.drawIndexedIndirect(commands.handle, offset, drawCount);
    }

    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (this.pipeline != null && this.pipeline.fan) {
            this.drawFan(vertexCount, instanceCount, firstVertex, firstInstance);
            return;
        }
        int pending = this.prepareDraw();
        if (pending == 0) {
            WebGpuGlue.draw(vertexCount, instanceCount, firstVertex, firstInstance);
        } else if (pending > 0) {
            int[] o = this.offsets;
            WebGpuGlue.drawWithOffsets(this.boundGroup0, pending, o[0], o[1], o[2], o[3], vertexCount, instanceCount,
                    firstVertex, firstInstance);
        }
    }

    // ---- triangle fans (CoreTopology downgrade table) -------------------------------------------

    /**
     * A fan of {@code vertexCount} vertices from {@code firstVertex}: an indexed triangle list
     * through the encoder's fan index buffer, with {@code firstVertex} as the base vertex. Fewer
     * than three vertices draw nothing, as in OpenGL.
     */
    private void drawFan(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (vertexCount < 3 || instanceCount <= 0) {
            return;
        }
        CoreBuffer fan = this.encoder.fanIndices(vertexCount);
        int pending = this.prepareDraw();
        if (pending < 0) {
            return;
        }
        WebGpuGlue.setIndexBuffer(fan.handle, true, 0);
        this.indexOverridden = true;
        int indexCount = (vertexCount - 2) * 3;
        if (pending == 0) {
            WebGpuGlue.drawIndexed(indexCount, instanceCount, 0, firstVertex, firstInstance);
        } else {
            int[] o = this.offsets;
            WebGpuGlue.drawIndexedWithOffsets(this.boundGroup0, pending, o[0], o[1], o[2], o[3], indexCount,
                    instanceCount, 0, firstVertex, firstInstance);
        }
        this.fanDraws++;
        WebGpuGlue.count("fanDraws", 1);
    }

    /**
     * An indexed fan. Vanilla draws fans through its shared sequential index buffer, whose index
     * i is i: element {@code firstIndex} onwards then names vertices {@code baseVertex + first}
     * onwards, which is the non-indexed fan from there. Other index buffers are not converted (no
     * vanilla pipeline uses one); such draws are skipped and counted.
     */
    private void drawIndexedFan(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        CoreBuffer buffer = this.indexBuffer;
        int elementSize = this.indexWide ? 4 : 2;
        int first = this.indexOffset / elementSize + firstIndex;
        if (buffer == null || this.indexOffset % elementSize != 0
                || !buffer.isIdentity(this.indexWide, first, indexCount)) {
            this.fanDrawsSkipped++;
            this.drawsSkipped++;
            WebGpuGlue.count("fanDrawsSkipped", 1);
            if (this.fanDrawsSkipped == 1) {
                this.encoder.device().debugMessage("indexed TRIANGLE_FAN draw of pipeline " + this.pipeline.name()
                        + " through a non-sequential index buffer skipped");
            }
            return;
        }
        this.drawFan(indexCount, instanceCount, baseVertex + first, firstInstance);
    }

    // ---- pass splits ---------------------------------------------------------------------------

    /** Ends the GPU pass so that encoder commands can be recorded; {@link #resume()} continues it. */
    void suspend() {
        WebGpuGlue.passSuspend();
    }

    /** Continues the pass in a new GPU pass that loads the attachments and re-applies the state. */
    void resume() {
        WebGpuGlue.passResume();
        if (this.pipelineHandle != 0) {
            WebGpuGlue.setPipeline(this.pipelineHandle);
        }
        for (int slot = 0; slot < MAX_VERTEX_BUFFERS; slot++) {
            if (this.vertexHandles[slot] != 0) {
                WebGpuGlue.setVertexBuffer(slot, this.vertexHandles[slot], this.vertexOffsets[slot],
                        this.vertexSizes[slot]);
            }
        }
        if (this.indexHandle != 0) {
            WebGpuGlue.setIndexBuffer(this.indexHandle, this.indexWide, this.indexOffset);
        }
        this.indexOverridden = false;
        if (this.scissorSet) {
            WebGpuGlue.setScissor(this.scissorX, this.scissorY, this.scissorWidth, this.scissorHeight);
        }
        // Bind groups come from the cache at the next draw.
        this.boundGroup0 = 0;
        this.boundGroup1 = 0;
        this.boundOffsetCount = -1;
        this.group0Dirty = true;
        this.group1Dirty = true;
    }

    // ---- bind groups ---------------------------------------------------------------------------

    /**
     * Binds what changed since the last draw. Returns -1 when the draw must be skipped, 0 when
     * everything is bound, or the number of group 0 dynamic offsets (at most four, in
     * {@link #offsets}) that the draw call itself applies: the common terrain case, a draw that
     * only moves uniform slices, then costs a single glue call.
     */
    private int prepareDraw() {
        CorePipeline current = this.pipeline;
        if (current == null || this.skipDraws) {
            this.drawsSkipped++;
            return -1;
        }
        int[] dynamic = current.layout.dynamic;
        int count = dynamic.length;
        if (this.group0Dirty) {
            int group = this.bindGroup(current, 0);
            if (group == 0) {
                this.drawsSkipped++;
                return -1;
            }
            if (group != this.boundGroup0) {
                this.boundGroup0 = group;
                if (count == 0) {
                    WebGpuGlue.setBindGroup(0, group, 0, 0, 0, 0, 0);
                } else {
                    this.boundOffsetCount = -1;
                }
            }
            this.group0Dirty = false;
        }
        int pending = 0;
        if (count > 0) {
            boolean changed = this.boundOffsetCount != count;
            for (int i = 0; i < count; i++) {
                int offset = this.resourceOffset[dynamic[i]];
                this.offsets[i] = offset;
                if (offset != this.boundOffsets[i]) {
                    changed = true;
                }
            }
            if (changed) {
                if (count <= MAX_INLINE_OFFSETS) {
                    pending = count;
                } else {
                    for (int i = 0; i < count; i++) {
                        WebGpuGlue.stageOffset(i, this.offsets[i]);
                    }
                    WebGpuGlue.setBindGroupStaged(0, this.boundGroup0, count);
                }
                System.arraycopy(this.offsets, 0, this.boundOffsets, 0, count);
                this.boundOffsetCount = count;
            }
        }
        if (this.group1Dirty) {
            int group = this.bindGroup(current, 1);
            if (group == 0) {
                this.drawsSkipped++;
                // Offsets recorded above were not applied: the next draw must apply them.
                this.boundOffsetCount = -1;
                return -1;
            }
            if (group != this.boundGroup1) {
                WebGpuGlue.setBindGroup(1, group, 0, 0, 0, 0, 0);
                this.boundGroup1 = group;
            }
            this.group1Dirty = false;
            if (this.group0Dirty) {
                // Creating group 1 flushed the bind group cache, group 0 included: bind it again.
                return this.prepareDraw();
            }
        }
        return pending;
    }

    /** The bind group of {@code group} for the bound resources, or 0 when one is missing. */
    private int bindGroup(CorePipeline current, int group) {
        BindingLayout layout = current.layout;
        int[] entries = group == 0 ? layout.group0 : layout.group1;
        long hash = 0xcbf29ce484222325L ^ this.pipelineHandle ^ group;
        for (int index : entries) {
            if (!this.set[index]) {
                return 0;
            }
            byte kind = layout.kinds[index];
            hash ^= kind;
            hash *= 0x100000001b3L;
            hash ^= layout.bindings[index];
            hash *= 0x100000001b3L;
            if (kind == BindingLayout.UNIFORM_BUFFER) {
                hash ^= this.resourceA[index];
                hash *= 0x100000001b3L;
                hash ^= this.resourceLength[index];
            } else if (kind == BindingLayout.TEXEL_BUFFER) {
                hash ^= this.resourceA[index];
                hash *= 0x100000001b3L;
                hash ^= this.resourceOffset[index];
                hash *= 0x100000001b3L;
                hash ^= this.resourceLength[index];
            } else {
                hash ^= this.resourceA[index];
                hash *= 0x100000001b3L;
                hash ^= this.resourceB[index];
            }
            hash *= 0x100000001b3L;
        }
        Long cacheKey = hash;
        java.util.ArrayList<BindGroupCacheEntry> bucket = this.bindGroupCache.get(cacheKey);
        if (bucket != null) {
            for (BindGroupCacheEntry cached : bucket) {
                if (cached.pipeline == this.pipelineHandle && cached.group == group
                        && this.matches(cached.signature, layout, entries)) {
                    return cached.handle;
                }
            }
        }
        this.rememberCapacity();
        bucket = this.bindGroupCache.get(cacheKey);
        for (int index : entries) {
            byte kind = layout.kinds[index];
            if (kind == BindingLayout.UNIFORM_BUFFER) {
                WebGpuGlue.bindBuffer(layout.bindings[index], this.resourceA[index], 0, this.resourceLength[index]);
            } else if (kind == BindingLayout.TEXEL_BUFFER) {
                WebGpuGlue.bindBuffer(layout.bindings[index], this.resourceA[index], this.resourceOffset[index],
                        this.resourceLength[index]);
            } else {
                WebGpuGlue.bindTexture(layout.bindings[index], this.resourceA[index], layout.samplerBindings[index],
                        layout.depthSampled[index] ? this.resourceSampler[index].nonFilteringHandle()
                                : this.resourceB[index]);
            }
        }
        int[] signature = this.signature(layout, entries);
        int handle = WebGpuGlue.createBindGroup(this.pipelineHandle, group);
        if (bucket == null) {
            bucket = new java.util.ArrayList<>(1);
            this.bindGroupCache.put(cacheKey, bucket);
        }
        bucket.add(new BindGroupCacheEntry(this.pipelineHandle, group, signature, handle));
        this.bindGroupCacheEntries++;
        return handle;
    }

    private int[] signature(BindingLayout layout, int[] entries) {
        // A texel entry carries kind, binding, handle, offset and length; other entries use
        // the same fixed-width stride with the final slot holding the sampler or range value.
        int[] signature = new int[entries.length * 5];
        int at = 0;
        for (int index : entries) {
            byte kind = layout.kinds[index];
            signature[at++] = kind;
            signature[at++] = layout.bindings[index];
            signature[at++] = this.resourceA[index];
            if (kind == BindingLayout.UNIFORM_BUFFER) {
                signature[at++] = this.resourceLength[index];
            } else if (kind == BindingLayout.TEXEL_BUFFER) {
                signature[at++] = this.resourceOffset[index];
                signature[at++] = this.resourceLength[index];
            } else {
                signature[at++] = this.resourceB[index];
            }
        }
        return signature;
    }

    private boolean matches(int[] signature, BindingLayout layout, int[] entries) {
        int at = 0;
        for (int index : entries) {
            byte kind = layout.kinds[index];
            if (signature[at++] != kind || signature[at++] != layout.bindings[index]
                    || signature[at++] != this.resourceA[index]) {
                return false;
            }
            if (kind == BindingLayout.UNIFORM_BUFFER) {
                if (signature[at++] != this.resourceLength[index]) return false;
            } else if (kind == BindingLayout.TEXEL_BUFFER) {
                if (signature[at++] != this.resourceOffset[index]
                        || signature[at++] != this.resourceLength[index]) return false;
            } else if (signature[at++] != this.resourceB[index]) {
                return false;
            }
        }
        return true;
    }

    private void clearBindGroupCache() {
        for (java.util.ArrayList<BindGroupCacheEntry> bucket : this.bindGroupCache.values()) {
            for (BindGroupCacheEntry stale : bucket) {
                WebGpuGlue.releaseHandle(stale.handle);
            }
        }
        this.bindGroupCache.clear();
        this.bindGroupCacheEntries = 0;
    }

    private void rememberCapacity() {
        if (this.bindGroupCacheEntries >= MAX_CACHED_BIND_GROUPS) {
            // Resources churn (resized targets, re-uploaded atlases): start over rather than track
            // liveness per entry. Released handles drop the glue's last reference.
            this.clearBindGroupCache();
            this.boundGroup0 = 0;
            this.boundGroup1 = 0;
            this.boundOffsetCount = -1;
            this.group0Dirty = true;
            this.group1Dirty = true;
        }
    }

    /**
     * The size of a uniform buffer binding: a valid slice rounded up to whole 16-byte rows,
     * within the buffer's physical allocation and the device's uniform binding limit. A std140 block's
     * size is a multiple of 16 and WebGPU refuses a binding smaller than the block (a validation error that
     * invalidates the whole command buffer), while vanilla sizes uniform buffers by their last
     * member (a {@code vec2} and a {@code float}: 12 bytes, a 16-byte block); GL and Vulkan bind
     * such a range. {@link CoreDevice#createBuffer} pads uniform buffers to 16 bytes for this.
     */
    static int bindingSize(CoreBuffer buffer, int offset, int length, int maxBindingSize) {
        long capacity = uniformCapacity(buffer);
        long rounded = (length + 15L) & ~15L;
        if (offset < 0 || length <= 0 || rounded > maxBindingSize || (long) offset + length > buffer.size()
                || (long) offset + rounded > capacity) {
            throw new IllegalArgumentException("Uniform buffer " + buffer.handle + " slice [" + offset + ", "
                    + ((long) offset + length) + ") with padded size " + rounded
                    + " exceeds logical size " + buffer.size() + ", allocation " + capacity
                    + " or device binding limit " + maxBindingSize);
        }
        return (int) rounded;
    }

    private static long uniformCapacity(CoreBuffer buffer) {
        return buffer.isTransient() ? buffer.size() : CoreDevice.allocatedSize(buffer.usage(), buffer.size());
    }
}
