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

    private static long[] columnByUniform;
    private static int[] uniformGeneration;
    private static long[] seenColumns;
    private static boolean[] seenColumnSlots;

    private static Object activeWorld;
    private static boolean active;
    private static boolean drawTelemetryActive;
    private static int generation;
    private static int worldSequence;
    private static int pendingUniformIndex = -1;
    private static final boolean DRAW_ENABLED = BrowserBuildFlags.telemetry() && telemetryEnabledAtStartup();

    private BrowserChunkDrawTelemetry() {
    }

    /** Starts a new per-frame uniform map and clears all columns on a world switch. */
    public static void beginPrepare(Object world) {
        boolean drawEnabled = DRAW_ENABLED;
        boolean causalEnabled = BrowserChunkPipelineTelemetry.enabled();
        if (!drawEnabled && !causalEnabled) {
            active = false;
            activeWorld = null;
            pendingUniformIndex = -1;
            return;
        }
        if (!active || activeWorld != world || drawTelemetryActive != drawEnabled) {
            active = true;
            activeWorld = world;
            if (drawEnabled) {
                ensureDrawStorage();
                worldSequence++;
                if (worldSequence <= 0) {
                    worldSequence = 1;
                }
                Arrays.fill(seenColumnSlots, false);
                resetPublished(worldSequence);
            }
            generation = 0;
            if (drawEnabled) {
                Arrays.fill(uniformGeneration, 0);
            }
            drawTelemetryActive = drawEnabled;
        }
        generation++;
        if (generation <= 0) {
            if (drawEnabled) {
                Arrays.fill(uniformGeneration, 0);
            }
            generation = 1;
        }
        pendingUniformIndex = -1;
    }

    /** Records the renderer-side inputs after building one ChunkSectionsToRender snapshot. */
    public static void recordPrepareStats(int visibleSections, int maxIndexCount) {
        if (!active || !drawTelemetryActive) {
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
    /** Compatibility entry point used by the existing profile patchers. */
    public static void registerSection(int uniformIndex, int blockX, int blockZ) {
        registerSection(uniformIndex, blockX, 0, blockZ);
    }

    public static void registerSection(int uniformIndex, int blockX, int blockY, int blockZ) {
        if (!active) {
            return;
        }
        if (uniformIndex < 0 || uniformIndex >= MAX_UNIFORM_MAPPINGS) {
            if (drawTelemetryActive) {
                recordUniformOverflow(uniformIndex);
            }
            return;
        }
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (drawTelemetryActive) {
            columnByUniform[uniformIndex] = packColumn(chunkX, chunkZ);
            uniformGeneration[uniformIndex] = generation;
        }
        BrowserChunkPipelineTelemetry.registerDrawSection(uniformIndex, chunkX, blockY >> 4, chunkZ);
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
        boolean drawEnabled = drawTelemetryActive;
        boolean causalEnabled = BrowserChunkPipelineTelemetry.enabled();
        if (!drawEnabled && !causalEnabled) {
            active = false;
            activeWorld = null;
            return;
        }
        if (indexCount <= 0) {
            recordZeroDraw();
            return;
        }
        if (!drawEnabled) {
            BrowserChunkPipelineTelemetry.firstDraw(uniformIndex, indexCount);
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
        if (causalEnabled) {
            BrowserChunkPipelineTelemetry.firstDraw(uniformIndex, indexCount);
        }
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

    private static void ensureDrawStorage() {
        if (columnByUniform == null) {
            columnByUniform = new long[MAX_UNIFORM_MAPPINGS];
            uniformGeneration = new int[MAX_UNIFORM_MAPPINGS];
            seenColumns = new long[MAX_COLUMNS];
            seenColumnSlots = new boolean[MAX_COLUMNS];
        }
    }

    @JSBody(script = """
            let enabled=globalThis.__gaiusChunkDrawTelemetryEnabled;
            if(enabled!==true&&enabled!==false){
              const params=new URLSearchParams(globalThis.location?globalThis.location.search:'');
              enabled=/^(1|true|on)$/i.test(params.get('gaiusChunkDrawTelemetry')||'');
              if(enabled)globalThis.__gaiusChunkDrawTelemetryEnabled=true;
            }
            return enabled===true;
            """)
    private static native boolean telemetryEnabledAtStartup();

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
