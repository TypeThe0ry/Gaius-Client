package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Opt-in, bounded observations for vanilla chunk-generation creature packs. */
public final class BrowserGenerationSpawnTelemetry {
    private BrowserGenerationSpawnTelemetry() {
    }

    // A stripped build returns token 0, which the other entries ignore.
    public static int begin(Object world, int chunkX, int chunkZ) {
        if (!BrowserBuildFlags.telemetry()) return 0;
        return recordBegin(world, chunkX, chunkZ);
    }

    public static void entityAdded(int token) {
        if (!BrowserBuildFlags.telemetry()) return;
        recordEntityAdded(token);
    }

    public static void complete(int token) {
        if (!BrowserBuildFlags.telemetry()) return;
        recordComplete(token);
    }

    public static void failed(int token) {
        if (!BrowserBuildFlags.telemetry()) return;
        recordFailed(token);
    }

    @JSBody(params = {"world", "chunkX", "chunkZ"}, script = """
            try {
              if (globalThis.__gaiusSlowProbeTelemetryEnabled !== true) return 0;
              const stats = globalThis.__gaiusWorldgenStats ||
                (globalThis.__gaiusWorldgenStats = {});
              let worlds = stats.__generationSpawnWorlds;
              if (!worlds) {
                worlds = new WeakMap();
                Object.defineProperty(stats, '__generationSpawnWorlds',
                  {value: worlds, enumerable: false, configurable: true});
              }
              let worldId = worlds.get(world);
              if (!worldId) {
                worldId = (Number(stats.generationSpawnNextWorldId) || 0) + 1;
                stats.generationSpawnNextWorldId = worldId;
                worlds.set(world, worldId);
              }
              let active = stats.__generationSpawnActive;
              if (!active) {
                active = new Array(32);
                Object.defineProperty(stats, '__generationSpawnActive',
                  {value: active, enumerable: false, configurable: true});
              }
              let slot = -1;
              for (let i = 0; i < active.length; i++) {
                if (!active[i]) { slot = i; break; }
              }
              if (slot < 0) {
                stats.generationSpawnDroppedCount = (Number(stats.generationSpawnDroppedCount) || 0) + 1;
                return 0;
              }
              const token = ((Number(stats.generationSpawnNextToken) || 0) % 2147483647) + 1;
              stats.generationSpawnNextToken = token;
              active[slot] = {token: token, worldId: worldId, chunkX: Number(chunkX) || 0,
                chunkZ: Number(chunkZ) || 0, entityCount: 0,
                startedAt: typeof performance !== 'undefined' && performance.now
                  ? performance.now() : Date.now()};
              return token;
            } catch (_) {}
            return 0;
            """)
    private static native int recordBegin(Object world, int chunkX, int chunkZ);

    @JSBody(params = "token", script = """
            try {
              if (!token) return;
              const stats = globalThis.__gaiusWorldgenStats;
              const active = stats && stats.__generationSpawnActive;
              const entry = active && active.find(value => value && value.token === Number(token));
              if (entry && entry.token === Number(token)) entry.entityCount++;
            } catch (_) {}
            """)
    private static native void recordEntityAdded(int token);

    private static void recordComplete(int token) {
        finishGenerationSpawn(token, "completed");
    }

    private static void recordFailed(int token) {
        finishGenerationSpawn(token, "failed");
    }

    @JSBody(params = {"token", "status"}, script = """
            try {
              if (!token) return;
            const stats = globalThis.__gaiusWorldgenStats;
            const active = stats && stats.__generationSpawnActive;
            const slot = active ? active.findIndex(value => value && value.token === Number(token)) : -1;
            const entry = active && active[slot];
            if (!entry || entry.token !== Number(token)) return;
            const now = typeof performance !== 'undefined' && performance.now
              ? performance.now() : Date.now();
            entry.endedAt = now;
            entry.durationMillis = Math.max(0, now - entry.startedAt);
            entry.status = String(status || 'unknown');
            active[slot] = null;
            const ring = stats.generationSpawnSamples ||
              (stats.generationSpawnSamples = []);
            ring.push(entry);
            if (ring.length > 64) ring.shift();
            stats.generationSpawnSampleCount =
              (Number(stats.generationSpawnSampleCount) || 0) + 1;
            stats.generationSpawnCompletedCount =
              (Number(stats.generationSpawnCompletedCount) || 0) +
              (entry.status === 'completed' ? 1 : 0);
            stats.generationSpawnFailedCount =
              (Number(stats.generationSpawnFailedCount) || 0) +
              (entry.status === 'failed' ? 1 : 0);
            } catch (_) {}
            """)
    private static native void finishGenerationSpawn(int token, String status);
}
