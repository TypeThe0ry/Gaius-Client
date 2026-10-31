package dev.gaius.browser.render;

import dev.gaius.browser.BrowserChunkDrawTelemetry;
import org.lwjgl.opengl.BrowserOpenGL;
import org.teavm.jso.JSBody;

/**
 * Profile-independent half of the batched terrain draw path (26.2 and 26.3).
 *
 * <p>LevelRenderer.prepareChunkRenders is patched to hand the frame's chunk-section records
 * (origin and fade-in visibility, the data vanilla writes into one ChunkSection uniform slice
 * per section) to {@link #beginSections}/{@link #addSection}, and to bind them to the uniform
 * slice array of the ChunkSectionsToRender it builds ({@link #bindSections}). The per-section
 * draw submission (DrawSeparate.render on 26.3, ChunkSectionsToRender.renderGroup on 26.2)
 * then goes through the profile's BrowserTerrainBatchGlue: the first draw of every run that
 * shares a vertex heap and index buffer stays a vanilla draw, which binds the pipeline, the
 * vertex array and all uniforms; the rest of the run is packed here into eight-int records and
 * drawn by {@link BrowserOpenGL#terrainMultiDraw} in one WEBGL_multi_draw call per 256
 * sections. The GL layer draws the batch only when that vanilla lead draw was really the last
 * draw issued (a vanilla call that skipped its draws leaves another draw's state bound).
 * Anything the batch cannot draw falls back to the vanilla call for that run.</p>
 *
 * <p>Switches: URL {@code gaiusTerrainBatch=0} (or {@code globalThis.__gaiusTerrainBatch =
 * false}) turns batching and the shader rewrite off; {@code gaiusTerrainAlign=0} keeps the
 * vanilla vertex heap alignment.</p>
 */
public final class BrowserTerrainBatch {
    /** Ints per draw record: count, first index, base vertex, index bytes, x, y, z, visibility. */
    public static final int RECORD_INTS = 8;
    /** Sequential quad indices (SOLID, CUTOUT): drawn from the shared quad index buffer. */
    public static final int KIND_SEQUENTIAL = 0;
    /** Custom index heap (TRANSLUCENT): drawn from the baked absolute-index copy. */
    public static final int KIND_CUSTOM_INDEX = 1;
    private static final int SECTION_INTS = 4;

    private static int[] sections = new int[SECTION_INTS * 512];
    private static int sectionCount;
    private static Object sectionOwner;
    private static int[] records = new int[RECORD_INTS * 256];
    private static int[] recordSections = new int[256];
    private static int alignmentMode = -1;
    private static long batchRuns;
    private static long batchedDraws;
    private static long fallbackRuns;

    private BrowserTerrainBatch() {
    }

    /** True when the GL layer runs the batch path (it decides at context initialization). */
    public static boolean enabled() {
        return BrowserOpenGL.terrainBatchMode() > 0;
    }

    /**
     * Allocation alignment for the section vertex heaps: four vertices, so every section's
     * base vertex is a multiple of four and its quads line up with the shared quad index
     * buffer (112 bytes for the 28-byte BLOCK format). TlsfAllocator pads non-power-of-two
     * alignments by one alignment unit, so this costs at most 111 bytes per section.
     */
    public static int vertexHeapAlignment(int vertexSize) {
        if (vertexSize <= 0) {
            return vertexSize;
        }
        if (alignmentMode < 0) {
            alignmentMode = alignmentDisabled() ? 0 : 1;
        }
        return alignmentMode == 0 ? vertexSize : vertexSize * 4;
    }

    @JSBody(script = """
            try {
              const value=new URLSearchParams(String(location.search || '')).get('gaiusTerrainAlign');
              return value==='0' || value==='false';
            } catch (ignored) {
              return false;
            }
            """)
    private static native boolean alignmentDisabled();

    /** Starts the section table of one prepareChunkRenders call. */
    public static void beginSections(int expected) {
        int needed = Math.max(0, expected) * SECTION_INTS;
        if (sections.length < needed) {
            sections = new int[Math.max(needed, sections.length * 2)];
        }
        sectionCount = 0;
        sectionOwner = null;
    }

    public static void addSection(int x, int y, int z, float visibility) {
        int at = sectionCount * SECTION_INTS;
        if (at + SECTION_INTS > sections.length) {
            int[] grown = new int[sections.length * 2];
            System.arraycopy(sections, 0, grown, 0, sections.length);
            sections = grown;
        }
        sections[at] = x;
        sections[at + 1] = y;
        sections[at + 2] = z;
        sections[at + 3] = Float.floatToRawIntBits(visibility);
        sectionCount++;
    }

    /** Ties the captured table to the uniform slice array vanilla wrote it into. */
    public static void bindSections(Object owner) {
        sectionOwner = owner;
    }

    /** True when the captured table belongs to {@code owner} (the newest prepared frame). */
    public static boolean hasSections(Object owner) {
        return owner != null && owner == sectionOwner;
    }

    public static int sectionCount() {
        return sectionCount;
    }

    /** Scratch record array for {@code draws} draws, valid until the next call. */
    public static int[] records(int draws) {
        int needed = Math.max(1, draws) * RECORD_INTS;
        if (records.length < needed) {
            records = new int[Math.max(needed, records.length * 2)];
        }
        if (recordSections.length < draws) {
            recordSections = new int[Math.max(draws, recordSections.length * 2)];
        }
        return records;
    }

    /** Writes one draw record; false when the section index is not in the current table. */
    public static boolean putRecord(
            int[] target, int slot, int indexCount, int firstIndex, int baseVertex,
            int indexBytes, int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount || indexCount <= 0) {
            return false;
        }
        int at = slot * RECORD_INTS;
        int section = sectionIndex * SECTION_INTS;
        target[at] = indexCount;
        target[at + 1] = firstIndex;
        target[at + 2] = baseVertex;
        target[at + 3] = indexBytes;
        target[at + 4] = sections[section];
        target[at + 5] = sections[section + 1];
        target[at + 6] = sections[section + 2];
        target[at + 7] = sections[section + 3];
        recordSections[slot] = sectionIndex;
        return true;
    }

    /**
     * Draws the packed run. {@code leadSerial} is {@link BrowserOpenGL#drawSerial()} read
     * before the vanilla call that ended with the run's lead draw, whose index count and base
     * vertex follow. Returns true when every draw was issued; false means nothing was drawn
     * and the caller must submit the run through the vanilla path.
     */
    public static boolean submit(int kind, int[] packed, int draws, int leadSerial,
            int leadIndexCount, int leadBaseVertex) {
        if (draws <= 0) {
            return true;
        }
        int drawn = BrowserOpenGL.terrainMultiDraw(
                kind, packed, draws, leadSerial, leadIndexCount, leadBaseVertex);
        if (drawn != draws) {
            fallbackRuns++;
            return false;
        }
        batchRuns++;
        batchedDraws += draws;
        if (BrowserChunkDrawTelemetry.batchTelemetryActive()) {
            for (int slot = 0; slot < draws; slot++) {
                BrowserChunkDrawTelemetry.recordBatchedDraw(
                        recordSections[slot], packed[slot * RECORD_INTS]);
            }
        }
        if ((batchRuns & 255L) == 1L) {
            publish((double) batchRuns, (double) batchedDraws, (double) fallbackRuns);
        }
        return true;
    }

    /** Counts a run that went to the vanilla path before reaching the GL layer. */
    public static void noteFallback() {
        fallbackRuns++;
    }

    @JSBody(params = {"runs", "draws", "fallbacks"}, script = """
            const stats=globalThis.__gaiusGLStats || (globalThis.__gaiusGLStats={});
            stats.terrainBatchRuns=runs;
            stats.terrainBatchRunDraws=draws;
            stats.terrainBatchFallbackRuns=fallbacks;
            """)
    private static native void publish(double runs, double draws, double fallbacks);
}
