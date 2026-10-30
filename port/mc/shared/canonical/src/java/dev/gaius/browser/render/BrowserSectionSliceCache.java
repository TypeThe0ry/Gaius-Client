package dev.gaius.browser.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * Memoizes {@code SectionRenderDispatcher.getRenderSectionSlice} per compiled section mesh and
 * layer for the per-frame terrain draw extraction of LevelRenderer.
 *
 * <p>The vanilla lookup is a pure function of the dispatcher's final per-layer uber buffers, the
 * {@code allocationMap} of those {@code UberGpuBuffer}s (keyed by mesh identity: SectionMesh
 * does not override equals/hashCode) and the final offset and heap of each allocation. Section
 * slice patches (SectionSliceCachePatches) make every {@code allocationMap} put, remove and clear
 * call {@link #invalidate()}, so a memoized slice is returned only while no allocation of any uber
 * buffer changed since it was computed, for the same dispatcher. The returned record is the one the
 * vanilla call produced; callers only read its components. Everything runs on the render thread
 * under the dispatcher's copy lock, as in vanilla.
 */
public final class BrowserSectionSliceCache {
    private static final int LAYERS = ChunkSectionLayer.values().length;
    /** Replaced on every allocation change; compared by identity, so it cannot wrap around. */
    private static Object generation = new Object();

    static {
        if (LAYERS > 31) {
            throw new IllegalStateException("too many chunk section layers for the slice cache: " + LAYERS);
        }
    }

    private BrowserSectionSliceCache() {
    }

    /** Called by the patched UberGpuBuffer after each allocationMap put, remove and clear. */
    public static void invalidate() {
        generation = new Object();
    }

    /**
     * The slice {@code dispatcher.getRenderSectionSlice(mesh, layer)} returns now, from
     * {@code entry} (the mesh's own memo) when it is still current.
     */
    public static SectionRenderDispatcher.RenderSectionBufferSlice slice(
            SectionRenderDispatcher dispatcher, SectionMesh mesh, ChunkSectionLayer layer, Entry entry) {
        Object current = generation;
        if (entry.generation != current || entry.dispatcher != dispatcher) {
            entry.generation = current;
            entry.dispatcher = dispatcher;
            entry.computed = 0;
        }
        int index = layer.ordinal();
        int bit = 1 << index;
        if ((entry.computed & bit) != 0) {
            return entry.slices[index];
        }
        SectionRenderDispatcher.RenderSectionBufferSlice slice = dispatcher.getRenderSectionSlice(mesh, layer);
        entry.slices[index] = slice;
        entry.computed |= bit;
        return slice;
    }

    /** The per-mesh memo, held by the patched CompiledSectionMesh. */
    public static final class Entry {
        private Object generation;
        private SectionRenderDispatcher dispatcher;
        private int computed;
        private final SectionRenderDispatcher.RenderSectionBufferSlice[] slices =
                new SectionRenderDispatcher.RenderSectionBufferSlice[LAYERS];

        public Entry() {
        }
    }
}
