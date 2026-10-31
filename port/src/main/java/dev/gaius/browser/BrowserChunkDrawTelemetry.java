package dev.gaius.browser;

import java.util.Arrays;
import org.teavm.jso.JSBody;

/**
 * Diagnostic-only bridge from a prepared chunk-section uniform to the first
 * successful GPU draw of its chunk column.
 */
public final class BrowserChunkDrawTelemetry {
    public static final int WINDOW_MILLIS = 300;
    private static final int MAX_UNIFORM_MAPPINGS = 32768;
    private static final int MAX_COLUMNS = 4096;

    private static final long[] columnByUniform = new long[MAX_UNIFORM_MAPPINGS];
    private static final int[] uniformGeneration = new int[MAX_UNIFORM_MAPPINGS];
    private static final long[] seenColumns = new long[MAX_COLUMNS];
    private static final boolean[] seenColumnSlots = new boolean[MAX_COLUMNS];

    private static Object activeWorld;
    private static boolean active;
    private static int generation;
    private static int worldSequence;
    private static int pendingUniformIndex = -1;

    private BrowserChunkDrawTelemetry() {
    }

    /** Starts a new per-frame uniform map and clears all columns on a world switch. */
    public static void beginPrepare(Object world) {
        if (!BrowserBuildFlags.telemetry() || !telemetryEnabled()) {
            active = false;
            activeWorld = null;
            pendingUniformIndex = -1;
            return;
        }
        if (!active || activeWorld != world) {
            active = true;
            activeWorld = world;
            worldSequence++;
            if (worldSequence <= 0) {
                worldSequence = 1;
            }
            generation = 0;
            Arrays.fill(uniformGeneration, 0);
            Arrays.fill(seenColumnSlots, false);
            resetPublished(worldSequence);
        }
        generation++;
        if (generation <= 0) {
            Arrays.fill(uniformGeneration, 0);
            generation = 1;
        }
        pendingUniformIndex = -1;
    }

    /** Records the renderer-side inputs after building one ChunkSectionsToRender snapshot. */
    public static void recordPrepareStats(int visibleSections, int maxIndexCount) {
        if (!active || !telemetryEnabled()) {
            return;
        }
        recordPrepareStatsJs(visibleSections, maxIndexCount);
    }

    @JSBody(params = {"visibleSections", "maxIndexCount"}, script = """
            const state = globalThis.__gaiusChunkDrawTelemetry;
            if (!state) return;
            state.prepareCalls = (state.prepareCalls || 0) + 1;
            state.lastPrepareVisibleSections = Number(visibleSections) || 0;
            state.lastPrepareMaxIndexCount = Number(maxIndexCount) || 0;
            state.maxPrepareVisibleSections = Math.max(
              Number(state.maxPrepareVisibleSections) || 0,
              Number(visibleSections) || 0);
            state.maxPrepareMaxIndexCount = Math.max(
              Number(state.maxPrepareMaxIndexCount) || 0,
              Number(maxIndexCount) || 0);
            """)
    private static native void recordPrepareStatsJs(int visibleSections, int maxIndexCount);

    /** Associates the frame-local ChunkSection uniform index with one x/z column. */
    public static void registerSection(int uniformIndex, int blockX, int blockZ) {
        if (!active) {
            return;
        }
        if (uniformIndex < 0 || uniformIndex >= MAX_UNIFORM_MAPPINGS) {
            recordUniformOverflow(uniformIndex);
            return;
        }
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        columnByUniform[uniformIndex] = packColumn(chunkX, chunkZ);
        uniformGeneration[uniformIndex] = generation;
    }

    /** Clears any callback state left by an earlier failed draw submission. */
    public static void beginDraw() {
        pendingUniformIndex = -1;
    }

    /** Called by the ChunkSection uniform callback immediately before the GPU draw. */
    public static void armUniformIndex(int uniformIndex) {
        pendingUniformIndex = active ? uniformIndex : -1;
    }

    /** True while first-draw telemetry is recording, so batch callers can skip the loop. */
    public static boolean batchTelemetryActive() {
        return active;
    }

    /**
     * Records one section drawn inside a terrain batch (BrowserTerrainBatch): the batch
     * bypasses the per-draw uniform callback and draw hook, so it reports the uniform index
     * and index count of each section it issued here.
     */
    public static void recordBatchedDraw(int uniformIndex, int indexCount) {
        if (!active) {
            return;
        }
        pendingUniformIndex = uniformIndex;
        commitSuccessfulDraw(indexCount);
    }

    /**
     * Commits only after drawFromBuffers returned normally. A thrown draw never
     * reaches this method, and zero-count submissions are explicitly rejected.
     */
    public static void commitSuccessfulDraw(int indexCount) {
        int uniformIndex = pendingUniformIndex;
        pendingUniformIndex = -1;
        if (!active) {
            return;
        }
        if (!telemetryEnabled()) {
            active = false;
            activeWorld = null;
            return;
        }
        if (indexCount <= 0) {
            recordZeroDraw();
            return;
        }
        if (gpuSubmissionUnavailable()) {
            recordBlockedDraw();
            return;
        }
        if (uniformIndex < 0 || uniformIndex >= MAX_UNIFORM_MAPPINGS
                || uniformGeneration[uniformIndex] != generation) {
            recordUnmappedDraw();
            return;
        }
        long packed = columnByUniform[uniformIndex];
        if (!markFirstColumn(packed)) {
            recordDuplicateColumnDraw();
            return;
        }
        recordFirstDraw(
                worldSequence,
                (int) (packed >> 32),
                (int) packed,
                indexCount,
                nowMillis());
    }

    private static boolean markFirstColumn(long packed) {
        int slot = mixColumn(packed) & (MAX_COLUMNS - 1);
        for (int probes = 0; probes < MAX_COLUMNS; probes++) {
            if (!seenColumnSlots[slot]) {
                seenColumnSlots[slot] = true;
                seenColumns[slot] = packed;
                return true;
            }
            if (seenColumns[slot] == packed) {
                return false;
            }
            slot = (slot + 1) & (MAX_COLUMNS - 1);
        }
        recordColumnOverflow();
        return false;
    }

    private static long packColumn(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static int mixColumn(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;
        return (int) value;
    }

    @JSBody(script = "return globalThis.__gaiusChunkDrawTelemetryEnabled === true;")
    private static native boolean telemetryEnabled();

    @JSBody(script = """
            return typeof performance !== 'undefined'
              ? performance.timeOrigin + performance.now() : Date.now();
            """)
    private static native double nowMillis();

    @JSBody(params = "worldSequence", script = """
            const state = globalThis.__gaiusChunkDrawTelemetry = {
              schemaVersion: 'gaius.chunk-first-draw.v1',
              diagnosticOnly: true,
              enabled: true,
              worldSequence: Number(worldSequence) || 0,
              windowMillis: 300,
              eventCapacity: 2048,
              firstDrawColumns: 0,
               duplicateColumnDraws: 0,
               zeroIndexDraws: 0,
               blockedDraws: 0,
               unmappedDraws: 0,
              uniformMappingOverflows: 0,
              columnCapacityOverflows: 0,
              droppedEvents: 0,
              events: [],
              lastWindow: null
            };
            """)
    private static native void resetPublished(int worldSequence);

    @JSBody(params = {"worldSequence", "chunkX", "chunkZ", "indexCount", "atMillis"}, script = """
            const state = globalThis.__gaiusChunkDrawTelemetry;
            if (!state || globalThis.__gaiusChunkDrawTelemetryEnabled !== true) return;
            const event = {
              sequence: state.firstDrawColumns + 1,
              worldSequence: Number(worldSequence) || 0,
              chunkX: Number(chunkX) || 0,
              chunkZ: Number(chunkZ) || 0,
              indexCount: Math.max(0, Number(indexCount) || 0),
              atMillis: Number(atMillis) || Date.now()
            };
            state.firstDrawColumns++;
            if (state.events.length >= state.eventCapacity) {
              state.events.shift();
              state.droppedEvents++;
            }
            state.events.push(event);
            const start = event.atMillis - state.windowMillis;
            let columns = 0;
            for (let index = state.events.length - 1; index >= 0; index--) {
              if (state.events[index].atMillis < start) break;
              columns++;
            }
            state.lastWindow = {
              startAtMillis: start,
              endAtMillis: event.atMillis,
              windowMillis: state.windowMillis,
              firstDrawColumns: columns
            };
            """)
    private static native void recordFirstDraw(
            int worldSequence, int chunkX, int chunkZ, int indexCount, double atMillis);

    @JSBody(script = """
            const state = globalThis.__gaiusChunkDrawTelemetry;
            if (state) state.zeroIndexDraws++;
            """)
    private static native void recordZeroDraw();

    /**
     * Gate by backend: under WebGPU ({@code GaiusWebGpu.active}) the core's
     * {@code contextAvailable()}, otherwise BrowserOpenGL's submission state; without the WebGPU
     * branch every draw would count as blocked there.
     */
    @JSBody(script = """
            const gpu = globalThis.GaiusWebGpu;
            if (gpu && gpu.active === true && typeof gpu.contextAvailable === 'function') {
              try { return gpu.contextAvailable() !== true; } catch (error) { return true; }
            }
            const state = globalThis.__gaiusGL;
            return !state || state.gpuSubmissionBlocked === true
              || state.gpuContextLost === true;
            """)
    private static native boolean gpuSubmissionUnavailable();

    @JSBody(script = """
            const state = globalThis.__gaiusChunkDrawTelemetry;
            if (state) state.blockedDraws++;
            """)
    private static native void recordBlockedDraw();

    @JSBody(script = """
            const state = globalThis.__gaiusChunkDrawTelemetry;
            if (state) state.unmappedDraws++;
            """)
    private static native void recordUnmappedDraw();

    @JSBody(script = """
            const state = globalThis.__gaiusChunkDrawTelemetry;
            if (state) state.duplicateColumnDraws++;
            """)
    private static native void recordDuplicateColumnDraw();

    @JSBody(params = "uniformIndex", script = """
            const state = globalThis.__gaiusChunkDrawTelemetry;
            if (state) {
              state.uniformMappingOverflows++;
              state.lastUniformMappingOverflow = Number(uniformIndex);
            }
            """)
    private static native void recordUniformOverflow(int uniformIndex);

    @JSBody(script = """
            const state = globalThis.__gaiusChunkDrawTelemetry;
            if (state) state.columnCapacityOverflows++;
            """)
    private static native void recordColumnOverflow();
}
