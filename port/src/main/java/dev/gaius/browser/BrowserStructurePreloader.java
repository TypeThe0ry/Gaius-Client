package dev.gaius.browser;

import dev.gaius.browser.ops.VersionOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;

/** Optional, explicitly selected structure-template warmup for the browser server. */
public final class BrowserStructurePreloader {
    private static final int MAX_IDS = 64;
    private static final double DEFAULT_BUDGET_MILLIS = 5000.0;
    private static final double MAX_BUDGET_MILLIS = 30000.0;

    private BrowserStructurePreloader() {
    }

    /**
     * Warms only the IDs supplied by the page. With no page configuration this is a no-op.
     * The manager's own Identifier cache remains the sole parsed-template cache.
     */
    public static void preload(MinecraftServer server) {
        String[] ids = configuredIds();
        if (server == null || ids == null || ids.length == 0) {
            return;
        }
        if (!hasNativeContinuation()) {
            recordStart(ids.length, 0, 0, 0, 0, 0, "skipped-no-native-continuation");
            return;
        }
        double budget = configuredBudgetMillis(DEFAULT_BUDGET_MILLIS);
        double startedAt = nowMillis();
        double deadline = startedAt + Math.min(MAX_BUDGET_MILLIS, budget);
        int limit = Math.min(MAX_IDS, ids.length);
        int completed = 0;
        int missing = 0;
        int failed = 0;
        double maxOne = 0.0;
        String stopReason = "completed";
        StructureTemplateManager manager = VersionOps.INSTANCE.structureTemplateManager(server);
        recordStart(ids.length, limit, startedAt, 0, 0, 0, "running");
        if (nowMillis() >= deadline) {
            recordEnd(0, 0, 0, 0.0, Math.max(0.0, nowMillis() - startedAt), "budget");
            return;
        }
        for (int index = 0; index < limit; index++) {
            String text = ids[index];
            Identifier id = Identifier.tryParse(text);
            if (id == null) {
                failed++;
                stopReason = "invalid-id:" + String.valueOf(text);
                break;
            }
            double itemStarted = nowMillis();
            try {
                if (manager.get(id).isPresent()) {
                    completed++;
                } else {
                    missing++;
                }
            } catch (RuntimeException failure) {
                failed++;
                stopReason = "failed:" + id + ":" + failure.getClass().getName();
                maxOne = Math.max(maxOne, Math.max(0.0, nowMillis() - itemStarted));
                break;
            }
            maxOne = Math.max(maxOne, Math.max(0.0, nowMillis() - itemStarted));
            recordProgress(completed, missing, failed, maxOne, "completed:" + id);
            if (index + 1 < limit && nowMillis() >= deadline) {
                stopReason = "budget";
                break;
            }
            // This call is reached only under the direct native continuation guard above.
            TModernRuntimeSupport.yieldToEventLoop(0);
            if (nowMillis() >= deadline && index + 1 < limit) {
                stopReason = "budget";
                break;
            }
        }
        if (completed + missing == limit && failed == 0 && ids.length > limit) {
            stopReason = "max-ids";
        }
        recordEnd(completed, missing, failed, maxOne,
                Math.max(0.0, nowMillis() - startedAt), stopReason);
    }

    @JSBody(script = """
            const value = globalThis.__gaiusStructurePreloadIds;
            return Array.isArray(value) ? value : null;
            """)
    private static native String[] configuredIds();

    @JSBody(params = "fallback", script = """
            const value = Number(globalThis.__gaiusStructurePreloadBudgetMillis);
            return Number.isFinite(value) && value > 0
              ? Math.min(30000, value) : fallback;
            """)
    private static native double configuredBudgetMillis(double fallback);

    @JSBody(script = "return typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now();")
    private static native double nowMillis();

    @JSBody(script = """
            return typeof $rt_nativeThread === 'function' && $rt_nativeThread() != null;
            """)
    private static native boolean hasNativeContinuation();

    @JSBody(params = {"requested", "limit", "startedAt", "completed", "missing", "failed", "reason"}, script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            stats.structurePreload = {requested: requested, limit: limit, startedAt: startedAt,
              completed: completed, missing: missing, failed: failed,
              remaining: Math.max(0, requested - completed - missing - failed),
              maxOneMillis: 0, elapsedMillis: 0, stopReason: String(reason)};
            """)
    private static native void recordStart(int requested, int limit, double startedAt,
            int completed, int missing, int failed, String reason);

    @JSBody(params = {"completed", "missing", "failed", "maxOne", "reason"}, script = """
            const stats = globalThis.__gaiusWorldgenStats && globalThis.__gaiusWorldgenStats.structurePreload;
            if (!stats) return;
            stats.completed = completed; stats.missing = missing; stats.failed = failed;
            stats.remaining = Math.max(0, Number(stats.requested) - completed - missing - failed);
            stats.maxOneMillis = maxOne; stats.last = String(reason);
            """)
    private static native void recordProgress(int completed, int missing, int failed,
            double maxOne, String reason);

    @JSBody(params = {"completed", "missing", "failed", "maxOne", "elapsed", "reason"}, script = """
            const stats = globalThis.__gaiusWorldgenStats && globalThis.__gaiusWorldgenStats.structurePreload;
            if (!stats) return;
            stats.completed = completed; stats.missing = missing; stats.failed = failed;
            stats.maxOneMillis = maxOne; stats.elapsedMillis = elapsed;
            stats.remaining = Math.max(0, Number(stats.requested) - completed - missing - failed);
            stats.stopReason = String(reason);
            """)
    private static native void recordEnd(int completed, int missing, int failed,
            double maxOne, double elapsed, String reason);
}
