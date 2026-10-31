package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Default-off bounded trace of integrated-server chunk generation layers. */
public final class BrowserChunkGenerationOrderTelemetry {
    private BrowserChunkGenerationOrderTelemetry() {
    }

    public static void begin(Object task, int x, int z, Object status) {
        if (!BrowserBuildFlags.telemetry()) return;
        net.minecraft.world.level.chunk.status.ChunkStatus value =
                (net.minecraft.world.level.chunk.status.ChunkStatus) status;
        record(task, x, z, value.getIndex(), value.getName(), "begin");
    }

    public static void complete(Object task, int x, int z, Object status) {
        if (!BrowserBuildFlags.telemetry()) return;
        net.minecraft.world.level.chunk.status.ChunkStatus value =
                (net.minecraft.world.level.chunk.status.ChunkStatus) status;
        record(task, x, z, value.getIndex(), value.getName(), "complete");
    }

    @JSBody(params = {"task", "x", "z", "ordinal", "name", "phase"}, script = """
            try {
              if (globalThis.__gaiusChunkOrderTelemetryEnabled !== true) return;
              const root = globalThis.__gaiusWorldgenStats ||
                (globalThis.__gaiusWorldgenStats = {});
              const ring = root.chunkGenerationOrder || (root.chunkGenerationOrder = []);
              const seq = (Number(root.chunkGenerationOrderSequence) || 0) + 1;
              root.chunkGenerationOrderSequence = seq;
              ring.push({seq: seq, x: Number(x) || 0, z: Number(z) || 0,
                statusOrdinal: Number(ordinal) || 0, status: String(name || 'unknown'), phase: String(phase || 'unknown')});
              if (ring.length > 4096) ring.shift();
              root.chunkGenerationOrderDropped = Math.max(0,
                seq - ring.length);
            } catch (_) {}
            """)
    public static native void record(Object task, int x, int z, int ordinal, String name, String phase);
}
