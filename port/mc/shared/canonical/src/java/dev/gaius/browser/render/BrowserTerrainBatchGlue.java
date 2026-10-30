package dev.gaius.browser.render;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import dev.gaius.browser.BrowserChunkDrawTelemetry;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.DynamicUniforms;
import org.lwjgl.opengl.BrowserOpenGL;

/**
 * Vanilla-facing side of the batched terrain draw path; see
 * {@link BrowserTerrainBatch}. Called from code patched by TerrainBatchPatches:
 * <ul>
 *   <li>LevelRenderer.prepareChunkRenders: {@link #chunkUniform} replaces the per-draw
 *       ChunkSection uniform lambda, {@link #captureSections} and {@link #bindSections}
 *       record the section table around DynamicUniforms.writeChunkSections;</li>
 *   <li>ChunkSectionsToRender.renderGroup: {@link #drawMultipleIndexed} replaces
 *       RenderPass.drawMultipleIndexed (the backend groups every translucent section, of every
 *       heap, into one list, so runs split at heap changes).</li>
 * </ul>
 */
public final class BrowserTerrainBatchGlue {
    private static ChunkUniform[] uniforms = new ChunkUniform[1024];

    private BrowserTerrainBatchGlue() {
    }

    /** The ChunkSection uniform callback of one draw, shared per section index. */
    public static BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> chunkUniform(int index) {
        if (index < 0) {
            return new ChunkUniform(index);
        }
        if (index >= uniforms.length) {
            ChunkUniform[] grown = new ChunkUniform[Math.max(index + 1, uniforms.length * 2)];
            System.arraycopy(uniforms, 0, grown, 0, uniforms.length);
            uniforms = grown;
        }
        ChunkUniform uniform = uniforms[index];
        if (uniform == null) {
            uniform = new ChunkUniform(index);
            uniforms[index] = uniform;
        }
        return uniform;
    }

    /** Copies the frame's section records before vanilla writes them into uniform slices. */
    public static void captureSections(List<DynamicUniforms.ChunkSectionInfo> sections) {
        if (sections == null || !BrowserTerrainBatch.enabled()) {
            BrowserTerrainBatch.beginSections(0);
            return;
        }
        int count = sections.size();
        BrowserTerrainBatch.beginSections(count);
        for (int index = 0; index < count; index++) {
            DynamicUniforms.ChunkSectionInfo section = sections.get(index);
            BrowserTerrainBatch.addSection(
                    section.x(), section.y(), section.z(), section.visibility());
        }
    }

    /** Ties the captured records to the slice array; returns it unchanged. */
    public static GpuBufferSlice[] bindSections(GpuBufferSlice[] slices) {
        BrowserTerrainBatch.bindSections(slices);
        return slices;
    }

    /**
     * Replacement for {@code pass.drawMultipleIndexed(draws, indexBuffer, indexType,
     * uniformNames, sectionSlices)} in ChunkSectionsToRender.renderGroup.
     */
    public static void drawMultipleIndexed(
            RenderPass pass,
            Collection<RenderPass.Draw<GpuBufferSlice[]>> draws,
            GpuBuffer indexBuffer,
            IndexType indexType,
            Collection<String> uniformNames,
            GpuBufferSlice[] sectionSlices) {
        int size = draws.size();
        if (size < 2 || !(draws instanceof List) || !BrowserTerrainBatch.enabled()
                || !BrowserTerrainBatch.hasSections(sectionSlices)) {
            pass.drawMultipleIndexed(draws, indexBuffer, indexType, uniformNames, sectionSlices);
            return;
        }
        @SuppressWarnings("unchecked")
        List<RenderPass.Draw<GpuBufferSlice[]>> list =
                (List<RenderPass.Draw<GpuBufferSlice[]>>) draws;
        // Draws that are not batched go to vanilla in contiguous spans, one call each, so a
        // list of mostly single-draw runs (interleaved heaps) keeps vanilla's per-call setup
        // cost instead of paying it once per section. Spans keep the original draw order.
        int spanStart = 0;
        int start = 0;
        while (start < size) {
            RenderPass.Draw<GpuBufferSlice[]> first = list.get(start);
            boolean custom = first.indexBuffer() != null;
            GpuBuffer runIndexBuffer = custom ? first.indexBuffer() : indexBuffer;
            IndexType runIndexType = first.indexType() != null ? first.indexType() : indexType;
            int end = start + 1;
            while (end < size) {
                RenderPass.Draw<GpuBufferSlice[]> next = list.get(end);
                if (next.vertexBuffer() != first.vertexBuffer()
                        || next.slot() != first.slot()
                        || (next.indexBuffer() != null) != custom
                        || (custom ? next.indexBuffer() : indexBuffer) != runIndexBuffer
                        || (next.indexType() != null ? next.indexType() : indexType)
                                != runIndexType) {
                    break;
                }
                end++;
            }
            if (end - start > 1) {
                // The run's first draw stays vanilla and ends the pending span: as the last
                // draw of that call it leaves pipeline, vertex array, index buffer and every
                // uniform bound for the batch. The GL layer checks that it really was the last
                // draw issued (26.2 returns without drawing when the pipeline cannot be set
                // up). A rejected batch just extends the span.
                int leadSerial = BrowserOpenGL.drawSerial();
                pass.drawMultipleIndexed(
                        list.subList(spanStart, start + 1), indexBuffer, indexType,
                        uniformNames, sectionSlices);
                spanStart = submitRun(list, start + 1, end, runIndexType, custom, leadSerial, first)
                        ? end
                        : start + 1;
            }
            start = end;
        }
        if (spanStart < size) {
            pass.drawMultipleIndexed(
                    list.subList(spanStart, size), indexBuffer, indexType, uniformNames,
                    sectionSlices);
        }
    }

    private static boolean submitRun(
            List<RenderPass.Draw<GpuBufferSlice[]>> list, int from, int to,
            IndexType indexType, boolean custom, int leadSerial,
            RenderPass.Draw<GpuBufferSlice[]> lead) {
        if (indexType == null) {
            BrowserTerrainBatch.noteFallback();
            return false;
        }
        int count = to - from;
        int[] records = BrowserTerrainBatch.records(count);
        for (int slot = 0; slot < count; slot++) {
            RenderPass.Draw<GpuBufferSlice[]> draw = list.get(from + slot);
            if (!(draw.uniformUploaderConsumer() instanceof ChunkUniform uniform)
                    || !BrowserTerrainBatch.putRecord(records, slot, draw.indexCount(),
                            draw.firstIndex(), draw.baseVertex(), indexType.bytes,
                            uniform.index)) {
                BrowserTerrainBatch.noteFallback();
                return false;
            }
        }
        return BrowserTerrainBatch.submit(
                custom ? BrowserTerrainBatch.KIND_CUSTOM_INDEX : BrowserTerrainBatch.KIND_SEQUENTIAL,
                records, count, leadSerial, lead.indexCount(), lead.baseVertex());
    }

    /** Uploads one section's ChunkSection slice, as the vanilla lambda did. */
    static final class ChunkUniform
            implements BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> {
        final int index;

        ChunkUniform(int index) {
            this.index = index;
        }

        @Override
        public void accept(GpuBufferSlice[] slices, RenderPass.UniformUploader uploader) {
            BrowserChunkDrawTelemetry.armUniformIndex(index);
            dev.gaius.browser.kernel.mesh.MeshVersionOps.INSTANCE.uploadUniform(uploader, slices[index]);
        }
    }
}
