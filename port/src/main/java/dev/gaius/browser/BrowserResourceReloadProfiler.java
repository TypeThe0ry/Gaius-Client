package dev.gaius.browser;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.teavm.jso.JSBody;

/** Records resource-reload listener timing without changing the vanilla reload graph. */
public final class BrowserResourceReloadProfiler {
    private BrowserResourceReloadProfiler() {
    }

    public static PreparableReloadListener wrap(PreparableReloadListener delegate) {
        return new TimedListener(delegate);
    }

    /** The vanilla reload barrier tracks listeners by identity, so it must see the delegate. */
    public static PreparableReloadListener unwrap(PreparableReloadListener listener) {
        return listener instanceof TimedListener timed ? timed.delegate : listener;
    }

    /** Attaches a stable name to a known reload continuation for browser timing diagnostics. */
    public static Executor label(Executor delegate, String taskKind) {
        if (!detailedProfilingEnabled()) {
            return delegate;
        }
        return command -> delegate.execute(new LabeledTask(taskKind, command));
    }

    /** Adds the atlas identity to every task submitted while that atlas is loading. */
    public static Executor labelAtlas(Executor delegate, Object atlasEntry) {
        if (!detailedProfilingEnabled()) {
            return delegate;
        }
        return label(delegate, "AtlasManager.load " + String.valueOf(atlasEntry));
    }

    /** Marks a synchronous subsection inside a reload continuation for browser diagnostics. */
    public static void sectionStarted(String name) {
        recordSectionStart(name, now());
    }

    /** Completes a synchronous subsection started with {@link #sectionStarted(String)}. */
    public static void sectionCompleted(String name) {
        recordSectionEnd(name, now());
    }

    private static final class TimedListener implements PreparableReloadListener {
        private final PreparableReloadListener delegate;
        private final String name;

        private TimedListener(PreparableReloadListener delegate) {
            this.delegate = delegate;
            name = delegate.getName();
        }

        @Override
        public CompletableFuture<Void> reload(
                SharedState sharedState,
                Executor preparationExecutor,
                PreparationBarrier barrier,
                Executor reloadExecutor) {
            double startedAt = now();
            recordStart(name, startedAt);
            return delegate.reload(
                    sharedState,
                    new TimedExecutor(
                            name,
                            startedAt,
                            "preparation",
                            BrowserResourceReloadScheduler.defer(preparationExecutor)),
                    barrier,
                    new TimedExecutor(name, startedAt, "apply", reloadExecutor))
                    .whenComplete((unused, error) -> recordEnd(name, startedAt, error != null));
        }

        @Override
        public void prepareSharedState(SharedState sharedState) {
            delegate.prepareSharedState(sharedState);
        }

        @Override
        public String getName() {
            return name;
        }
    }

    private static final class TimedExecutor implements Executor {
        private final String listener;
        private final double reloadStartedAt;
        private final String phase;
        private final Executor delegate;
        private final boolean profileTasks;

        private TimedExecutor(String listener, double reloadStartedAt, String phase, Executor delegate) {
            this.listener = listener;
            this.reloadStartedAt = reloadStartedAt;
            this.phase = phase;
            this.delegate = delegate;
            profileTasks = detailedProfilingEnabled();
        }

        @Override
        public void execute(Runnable command) {
            if (!profileTasks) {
                delegate.execute(command);
                return;
            }
            String taskKind = command instanceof LabeledTask labeled
                    ? labeled.taskKind
                    : command.getClass().getName();
            delegate.execute(() -> {
                double taskStartedAt = now();
                try {
                    command.run();
                } finally {
                    recordTask(listener, reloadStartedAt, phase, taskKind, taskStartedAt, now());
                }
            });
        }
    }

    private static final class LabeledTask implements Runnable {
        private final String taskKind;
        private final Runnable delegate;

        private LabeledTask(String taskKind, Runnable delegate) {
            this.taskKind = taskKind;
            this.delegate = delegate;
        }

        @Override
        public void run() {
            delegate.run();
        }
    }

    @JSBody(script = """
            return typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now();
            """)
    private static native double now();

    @JSBody(script = """
            if (globalThis.__gaiusProfileResourceReloadTasks === true) return true;
            try {
              return typeof location !== 'undefined' &&
                new URLSearchParams(location.search).get('diag') === 'reload';
            } catch (ignored) {
              return false;
            }
            """)
    private static native boolean detailedProfilingEnabled();

    @JSBody(params = {"name", "startedAt"}, script = """
            const list = globalThis.__gaiusResourceReloadTimings ||
              (globalThis.__gaiusResourceReloadTimings = []);
            list.push({name: String(name), startedAt: +startedAt || 0, endedAt: 0, failed: false});
            if (list.length > 256) list.splice(0, list.length - 256);
            """)
    private static native void recordStart(String name, double startedAt);

    @JSBody(params = {"name", "startedAt", "failed"}, script = """
            const list = globalThis.__gaiusResourceReloadTimings ||
              (globalThis.__gaiusResourceReloadTimings = []);
            const end = typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now();
            for (let index = list.length - 1; index >= 0; index--) {
              const entry = list[index];
              if (entry.name === String(name) && entry.startedAt === (+startedAt || 0) && !entry.endedAt) {
                entry.endedAt = end;
                entry.durationMs = Math.max(0, end - entry.startedAt);
                entry.failed = !!failed;
                if (diagnosticsEnabled()) {
                  console.info('[Gaius reload] listener=' + entry.name +
                    ' durationMs=' + entry.durationMs.toFixed(1) +
                    ' failed=' + entry.failed);
                }
                break;
              }
            }

            function diagnosticsEnabled() {
              try {
                return typeof location !== 'undefined' &&
                  new URLSearchParams(location.search).get('diag') === 'reload';
              } catch (ignored) {
                return false;
              }
            }
            """)
    private static native void recordEnd(String name, double startedAt, boolean failed);

    @JSBody(params = {"name", "reloadStartedAt", "phase", "taskKind", "startedAt", "endedAt"}, script = """
            const list = globalThis.__gaiusResourceReloadTimings || [];
            for (let index = list.length - 1; index >= 0; index--) {
              const entry = list[index];
              if (entry.name !== String(name) || entry.startedAt !== (+reloadStartedAt || 0)) continue;
              const phases = entry.phases || (entry.phases = {});
              const bucket = phases[String(phase)] || (phases[String(phase)] = {tasks: 0, totalMs: 0, maxMs: 0});
              const elapsed = Math.max(0, (+endedAt || 0) - (+startedAt || 0));
              let kind = String(taskKind || "unknown");
              if (kind.length > 160) kind = kind.slice(0, 160);
              const kinds = bucket.taskKinds || (bucket.taskKinds = Object.create(null));
              if (kinds[kind] === undefined && (bucket.taskKindCount || 0) >= 48) kind = "<other>";
              let kindBucket = kinds[kind];
              if (kindBucket === undefined) {
                kindBucket = kinds[kind] = {tasks: 0, totalMs: 0, maxMs: 0};
                bucket.taskKindCount = (bucket.taskKindCount || 0) + 1;
              }
              bucket.tasks++;
              bucket.totalMs += elapsed;
              kindBucket.tasks++;
              kindBucket.totalMs += elapsed;
              kindBucket.maxMs = Math.max(kindBucket.maxMs, elapsed);
              if (elapsed >= bucket.maxMs) {
                bucket.maxMs = elapsed;
                bucket.slowestTaskKind = kind;
              }
              if (elapsed >= 50 && diagnosticsEnabled()) {
                console.warn('[Gaius reload] task listener=' + entry.name +
                  ' phase=' + String(phase) + ' kind=' + kind +
                  ' durationMs=' + elapsed.toFixed(1));
              }
              break;
            }

            function diagnosticsEnabled() {
              try {
                return typeof location !== 'undefined' &&
                  new URLSearchParams(location.search).get('diag') === 'reload';
              } catch (ignored) {
                return false;
              }
            }
            """)
    private static native void recordTask(
            String name,
            double reloadStartedAt,
            String phase,
            String taskKind,
            double startedAt,
            double endedAt);

    @JSBody(params = {"name", "startedAt"}, script = """
            const active = globalThis.__gaiusResourceReloadSectionActive ||
              (globalThis.__gaiusResourceReloadSectionActive = Object.create(null));
            active[String(name)] = +startedAt || 0;
            """)
    private static native void recordSectionStart(String name, double startedAt);

    @JSBody(params = {"name", "endedAt"}, script = """
            const key = String(name);
            const active = globalThis.__gaiusResourceReloadSectionActive || Object.create(null);
            const startedAt = active[key];
            delete active[key];
            if (!Number.isFinite(startedAt)) return;
            const list = globalThis.__gaiusResourceReloadSections ||
              (globalThis.__gaiusResourceReloadSections = []);
            const elapsed = Math.max(0, (+endedAt || 0) - startedAt);
            list.push({name: key, startedAt: startedAt, endedAt: +endedAt || 0, durationMs: elapsed});
            if (list.length > 256) list.splice(0, list.length - 256);
            try {
              if (elapsed >= 50 && typeof location !== 'undefined' &&
                  new URLSearchParams(location.search).get('diag') === 'reload') {
                console.warn('[Gaius reload] section=' + key +
                  ' durationMs=' + elapsed.toFixed(1));
              }
            } catch (ignored) {}
            """)
    private static native void recordSectionEnd(String name, double endedAt);
}
