package dev.gaius.browser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.teavm.jso.JSBody;

/**
 * Moves the render pipelines that the title screen does not draw out of the resource reload.
 *
 * <p>Vanilla compiles every registered render pipeline (about 190) inside the shader manager's
 * reload, which in the browser is ~2.8 s of main-thread time before the first menu frame. The
 * pipelines are the same in every launch, and a pipeline that is not in the cache is compiled by
 * {@code PipelineCache.get} on its first draw, so only the order of the compilations changes here:
 * <ol>
 *   <li>{@link #eager} keeps the {@link #TITLE} pipelines in the reload (they are compiled by the
 *       unchanged vanilla code) and returns the rest of the registered list to this class;</li>
 *   <li>{@link #attach} receives the cache the reload installs;</li>
 *   <li>{@link #onFrame} (every rendered frame, from {@code BrowserRenderScheduler.beginFrame})
 *       waits for the loading overlay to leave (a screen or a world is showing) and then compiles the deferred pipelines through
 *       the cache's own {@code get}, one frame budget at a time, world-critical ones first.</li>
 * </ol>
 * The compiled objects, the shader sources, the compile path and the set of pipelines are the
 * vanilla ones; a pipeline drawn before the warm-up reaches it is compiled on demand by the
 * unchanged {@code get}. The reload keeps its vanilla behaviour (every pipeline compiled before
 * it completes, so a broken shader pack fails the reload as before) when any shader file of the
 * reload comes from a pack other than {@code vanilla}, when fewer than {@link #MIN_EAGER} of the
 * title pipelines are registered (a renamed registry), or when {@code gaiusPipelineWarmup=0} is
 * in the page URL (or {@code globalThis.__gaiusPipelineWarmupConfig.gaiusPipelineWarmup} is 0).
 *
 * <p>Other URL parameters, for measurements: {@code gaiusPipelineEager=a,b} replaces the title
 * set by locations ({@code minecraft:pipeline/} may be omitted; {@code none} defers everything,
 * {@code all} defers nothing); {@code gaiusPipelineBudgetMs} is the work time per frame (default
 * {@value #DEFAULT_BUDGET_MS}); {@code gaiusPipelineHold=1} never starts the warm-up, so every deferred
 * pipeline is compiled on demand (tests).  State and counters are published as
 * {@code globalThis.__gaiusPipelineWarmup}.
 */
public final class BrowserPipelineWarmup {
    /** Locations compiled inside the reload: what the loading overlay hand-over and the first menu frames draw. */
    static final String[] TITLE = {
        "minecraft:pipeline/panorama",
        "minecraft:pipeline/gui_textured",
        "minecraft:pipeline/gui",
        "minecraft:pipeline/gui_text",
        "minecraft:pipeline/mojang_logo",
        "minecraft:pipeline/animate_sprite_blit",
        "minecraft:pipeline/animate_sprite_interpolate",
    };

    /**
     * Warm-up order: the menus that follow the title screen, then what the first world frames draw.
     * Every other deferred pipeline follows in registry order, the order-independent transparency
     * variants (used only with improved transparency) last.
     */
    static final String[] PRIORITY = {
        "minecraft:pipeline/gui_opaque_textured_background",
        "minecraft:pipeline/gui_text_highlight",
        "minecraft:pipeline/gui_textured_premultiplied_alpha",
        "minecraft:pipeline/gui_text_grayscale",
        "minecraft:pipeline/text",
        "minecraft:pipeline/solid_terrain",
        "minecraft:pipeline/cutout_terrain",
        "minecraft:pipeline/translucent_terrain",
        "minecraft:pipeline/lightmap",
        "minecraft:pipeline/sky",
        "minecraft:pipeline/celestial",
        "minecraft:pipeline/stars",
        "minecraft:pipeline/sunrise_sunset",
        "minecraft:pipeline/flat_clouds",
        "minecraft:pipeline/clouds",
        "minecraft:pipeline/vignette",
        "minecraft:pipeline/crosshair",
        "minecraft:pipeline/entity_cutout",
        "minecraft:pipeline/entity_translucent",
        "minecraft:pipeline/entity_solid",
        "minecraft:pipeline/entity_cutout_cull",
        "minecraft:pipeline/entity_shadow",
        "minecraft:pipeline/item_cutout",
        "minecraft:pipeline/item_translucent",
        "minecraft:pipeline/opaque_particle",
        "minecraft:pipeline/translucent_particle",
        "minecraft:pipeline/weather",
        "minecraft:pipeline/lines",
        "minecraft:pipeline/outline_cull",
    };

    static final String PIPELINE_PREFIX = "minecraft:pipeline/";
    static final String VANILLA_PACK = "vanilla";
    /** Fewer title pipelines than this in the registry means the registry is not the one this list was made for. */
    static final int MIN_EAGER = 3;
    static final int DEFAULT_BUDGET_MS = 6;
    /** Frames to wait after the overlay left (the first menu frame must be on screen before compiling resumes). */
    static final int START_DELAY_FRAMES = 6;
    /** Start anyway after this many frames without an overlay report (telemetry off). */
    static final int MENU_WAIT_LIMIT_FRAMES = 1500;
    /** A frame longer than this is left alone (world join) unless {@link #MAX_SKIPPED_FRAMES} frames passed. */
    static final long SLOW_FRAME_NANOS = 60_000_000L;
    static final int MAX_SKIPPED_FRAMES = 8;
    static final int MAX_NOTED_NAMES = 64;

    /** Everything that touches the page; replaced in the JVM tests. */
    interface Host {
        String param(String name);

        boolean menuReady();

        long nanos();

        void publish(boolean active, String reason, int eager, int deferred, int warmed, int failed,
                int misses, boolean started, boolean done, double warmMs, double maxMs, int queued);

        void note(String list, String value);

        void log(String message);
    }

    private static Host host = new PageHost();

    private static boolean foreignShader;
    private static List<Object> pendingRequired = Collections.emptyList();
    private static List<Object> pendingOptional = Collections.emptyList();
    private static int eagerCount;
    private static int deferredCount;
    private static String reason = "idle";
    private static boolean enabledForReload;

    private static PipelineWarmTarget target;
    private static ArrayDeque<Object> queue = new ArrayDeque<>();
    private static int generation;
    private static int phase;
    private static int framesWaited;
    private static int delayFrames;
    private static int skippedFrames;
    private static long lastFrameNanos;
    private static long budgetNanos;
    private static boolean hold;
    /** True while the warm-up itself calls the cache: its compilations are not on-demand misses. */
    private static boolean warming;
    private static int warmed;
    private static int failed;
    private static int misses;
    private static boolean started;
    private static boolean done;
    private static double warmMs;
    private static double maxMs;

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_WAIT_MENU = 1;
    private static final int PHASE_DELAY = 2;
    private static final int PHASE_RUN = 3;

    private BrowserPipelineWarmup() {
    }

    // ---- hooks called from the patched ShaderManager / PipelineCache ----------------------------

    /** Start of {@code ShaderManager.loadConfigs}: forgets the shader sources of the previous reload. */
    public static void beginConfigLoad() {
        foreignShader = false;
    }

    /** Start of each shader/include loader of {@code ShaderManager}: the pack that provides the file. */
    public static void noteShaderPack(String packId) {
        if (!VANILLA_PACK.equals(packId)) {
            foreignShader = true;
        }
    }

    /**
     * Filters the registered pipelines of one reload.
     *
     * @param all the list the reload is about to compile ({@code RenderPipelines.requiredPipelines()}
     *            or {@code optionalPipelines()})
     * @return the list the reload compiles; the remainder is warmed up after the title screen
     */
    public static List<Object> eager(List<?> all, boolean required) {
        @SuppressWarnings("unchecked")
        List<Object> registered = (List<Object>) all;
        if (required) {
            pendingRequired = Collections.emptyList();
            pendingOptional = Collections.emptyList();
            eagerCount = 0;
            deferredCount = 0;
            enabledForReload = decide(registered);
        }
        if (!enabledForReload) {
            return registered;
        }
        String[] keep = keepSet();
        List<Object> now = new ArrayList<>();
        List<Object> later = new ArrayList<>();
        for (Object pipeline : registered) {
            if (contains(keep, String.valueOf(pipeline))) {
                now.add(pipeline);
            } else {
                later.add(pipeline);
            }
        }
        eagerCount += now.size();
        deferredCount += later.size();
        if (required) {
            pendingRequired = later;
        } else {
            pendingOptional = later;
        }
        return now;
    }

    /** {@code ShaderManager.apply}, just before the new cache becomes current. */
    public static void attach(Object cache) {
        generation++;
        queue = new ArrayDeque<>();
        target = null;
        phase = PHASE_IDLE;
        warmed = 0;
        failed = 0;
        misses = 0;
        warmMs = 0;
        maxMs = 0;
        started = false;
        done = false;
        boolean armed = enabledForReload && deferredCount > 0 && cache instanceof PipelineWarmTarget;
        if (armed) {
            target = (PipelineWarmTarget) cache;
            for (Object pipeline : order(pendingRequired, pendingOptional)) {
                queue.addLast(pipeline);
            }
            framesWaited = 0;
            delayFrames = 0;
            skippedFrames = 0;
            lastFrameNanos = 0;
            budgetNanos = budgetMillis() * 1_000_000L;
            hold = "1".equals(host.param("gaiusPipelineHold"));
            phase = PHASE_WAIT_MENU;
            reason = "deferred";
        } else {
            reason = enabledForReload ? "no-target" : reason;
        }
        pendingRequired = Collections.emptyList();
        pendingOptional = Collections.emptyList();
        enabledForReload = false;
        publish();
    }

    /** {@code PipelineCache.get} found no entry: the pipeline is compiled now, on the draw that asked for it (the warm-up's own calls are not counted). */
    public static void onMiss(Object pipeline) {
        if (warming) {
            return;
        }
        misses++;
        if (misses <= MAX_NOTED_NAMES) {
            noteSafely("misses", String.valueOf(pipeline));
        }
        publish();
    }

    /** Start of every rendered frame. */
    public static void onFrame() {
        if (phase == PHASE_IDLE) {
            return;
        }
        long now = host.nanos();
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        switch (phase) {
            case PHASE_WAIT_MENU -> {
                if (hold) {
                    return;
                }
                framesWaited++;
                if (host.menuReady() || framesWaited > MENU_WAIT_LIMIT_FRAMES) {
                    phase = PHASE_DELAY;
                    delayFrames = START_DELAY_FRAMES;
                }
            }
            case PHASE_DELAY -> {
                if (--delayFrames <= 0) {
                    phase = PHASE_RUN;
                    started = true;
                    publish();
                }
            }
            case PHASE_RUN -> runBatch(now, previous);
            default -> {
            }
        }
    }

    // ---- warm-up --------------------------------------------------------------------------------

    private static void runBatch(long now, long previousFrameNanos) {
        if (previousFrameNanos != 0 && now - previousFrameNanos > SLOW_FRAME_NANOS
                && skippedFrames < MAX_SKIPPED_FRAMES) {
            skippedFrames++;
            return;
        }
        skippedFrames = 0;
        int ticket = generation;
        long started = now;
        while (!queue.isEmpty() && ticket == generation) {
            Object pipeline = queue.pollFirst();
            long before = host.nanos();
            boolean ok;
            warming = true;
            try {
                ok = target.gaiusWarm(pipeline) != null;
            } catch (Throwable error) {
                ok = false;
                host.log("pipeline warm-up failed for " + pipeline + ": " + error);
            } finally {
                warming = false;
            }
            double ms = (host.nanos() - before) / 1e6;
            warmMs += ms;
            maxMs = Math.max(maxMs, ms);
            if (ok) {
                warmed++;
            } else {
                failed++;
                noteSafely("failedNames", String.valueOf(pipeline));
            }
            if (ticket != generation || host.nanos() - started >= budgetNanos) {
                break;
            }
        }
        if (ticket == generation && queue.isEmpty()) {
            done = true;
            phase = PHASE_IDLE;
            target = null;
            reason = "done";
        }
        publish();
    }

    // ---- decisions ------------------------------------------------------------------------------

    private static boolean decide(List<Object> registered) {
        if ("0".equals(host.param("gaiusPipelineWarmup"))) {
            reason = "disabled-by-parameter";
            return false;
        }
        if (foreignShader) {
            reason = "foreign-shader-pack";
            return false;
        }
        String[] keep = keepSet();
        if (keep == null) {
            reason = "eager-all";
            return false;
        }
        int found = 0;
        for (Object pipeline : registered) {
            if (contains(keep, String.valueOf(pipeline))) {
                found++;
            }
        }
        if (found < Math.min(MIN_EAGER, keep.length)) {
            reason = "registry-mismatch";
            return false;
        }
        reason = "deferred";
        return true;
    }

    /** The locations compiled inside the reload; null when everything is. */
    private static String[] keepSet() {
        String override = host.param("gaiusPipelineEager");
        if (override == null || override.isEmpty()) {
            return TITLE;
        }
        if ("all".equals(override)) {
            return null;
        }
        if ("none".equals(override)) {
            return new String[0];
        }
        String[] parts = override.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            parts[i] = part.indexOf(':') < 0 ? PIPELINE_PREFIX + part : part;
        }
        return parts;
    }

    private static int budgetMillis() {
        String value = host.param("gaiusPipelineBudgetMs");
        if (value != null) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed >= 1 && parsed <= 1000) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Keep the default.
            }
        }
        return DEFAULT_BUDGET_MS;
    }

    private static boolean contains(String[] set, String name) {
        for (String value : set) {
            if (value.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** {@link #PRIORITY} first, then registry order with the transparency variants last. */
    static List<Object> order(List<Object> required, List<Object> optional) {
        Map<String, Object> remaining = new LinkedHashMap<>();
        for (Object pipeline : required) {
            remaining.put(String.valueOf(pipeline), pipeline);
        }
        for (Object pipeline : optional) {
            remaining.put(String.valueOf(pipeline), pipeline);
        }
        List<Object> ordered = new ArrayList<>(remaining.size());
        for (String name : PRIORITY) {
            Object pipeline = remaining.remove(name);
            if (pipeline != null) {
                ordered.add(pipeline);
            }
        }
        List<Object> transparency = new ArrayList<>();
        for (Map.Entry<String, Object> entry : remaining.entrySet()) {
            if (entry.getKey().startsWith(PIPELINE_PREFIX + "oit_")) {
                transparency.add(entry.getValue());
            } else {
                ordered.add(entry.getValue());
            }
        }
        ordered.addAll(transparency);
        return ordered;
    }

    /** Diagnostics must never take a frame down: a failing note is dropped. */
    private static void noteSafely(String list, String value) {
        try {
            host.note(list, value);
        } catch (Throwable ignored) {
            // The counters stay correct without the name.
        }
    }

    private static void publish() {
        host.publish(phase != PHASE_IDLE, reason, eagerCount, deferredCount, warmed, failed, misses,
                started, done, warmMs, maxMs, queue.size());
    }

    // ---- test seams -----------------------------------------------------------------------------

    static void setHostForTest(Host replacement) {
        host = replacement == null ? new PageHost() : replacement;
    }

    static void resetForTest() {
        foreignShader = false;
        pendingRequired = Collections.emptyList();
        pendingOptional = Collections.emptyList();
        eagerCount = 0;
        deferredCount = 0;
        reason = "idle";
        enabledForReload = false;
        target = null;
        queue = new ArrayDeque<>();
        generation++;
        phase = PHASE_IDLE;
        warming = false;
    }

    static int queuedForTest() {
        return queue.size();
    }

    static int warmedForTest() {
        return warmed;
    }

    static String reasonForTest() {
        return reason;
    }

    // ---- page access ----------------------------------------------------------------------------

    private static final class PageHost implements Host {
        @Override
        public String param(String name) {
            return pageParam(name);
        }

        @Override
        public boolean menuReady() {
            return pageMenuReady();
        }

        @Override
        public long nanos() {
            return System.nanoTime();
        }

        @Override
        public void publish(boolean active, String reason, int eager, int deferred, int warmed, int failed,
                int misses, boolean started, boolean done, double warmMs, double maxMs, int queued) {
            pagePublish(active, reason, eager, deferred, warmed, failed, misses, started, done, warmMs, maxMs,
                    queued);
        }

        @Override
        public void note(String list, String value) {
            pageNote(list, value);
        }

        @Override
        public void log(String message) {
            pageLog(message);
        }
    }

    @JSBody(params = "name", script = """
            try {
              var q = (globalThis.location && globalThis.location.search) || '';
              var m = new RegExp('[?&]' + name + '=([^&]*)').exec(q);
              if (m) return decodeURIComponent(m[1]);
              var o = globalThis.__gaiusPipelineWarmupConfig;
              if (o && o[name] != null) return String(o[name]);
            } catch (e) {}
            return null;
            """)
    private static native String pageParam(String name);

    @JSBody(script = """
            var s = globalThis.__gaiusMinecraftState;
            return !!(s && s.running !== false && !s.overlay && (s.screen || s.level));
            """)
    private static native boolean pageMenuReady();

    @JSBody(params = {"active", "reason", "eager", "deferred", "warmed", "failed", "misses", "started", "done",
            "warmMs", "maxMs", "queued"}, script = """
            var w = globalThis.__gaiusPipelineWarmup;
            if (!w || typeof w !== 'object') {
              w = globalThis.__gaiusPipelineWarmup = {misses: [], failedNames: [], log: []};
            }
            w.active = !!active; w.reason = reason; w.eager = eager; w.deferred = deferred;
            w.warmed = warmed; w.failed = failed; w.missCount = misses; w.queued = queued;
            w.warmMs = Math.round(warmMs * 100) / 100; w.maxMs = Math.round(maxMs * 100) / 100;
            // Page clock (performance.now) at the first call that reports the transition.
            var now = performance.now();
            if (started && !w.startedAt) w.startedAt = Math.round(now * 10) / 10;
            if (done && !w.doneAt) w.doneAt = Math.round(now * 10) / 10;
            if (!started) w.startedAt = 0;
            if (!done) w.doneAt = 0;
            """)
    private static native void pagePublish(boolean active, String reason, int eager, int deferred, int warmed,
            int failed, int misses, boolean started, boolean done, double warmMs, double maxMs, int queued);

    @JSBody(params = {"list", "value"}, script = """
            var w = globalThis.__gaiusPipelineWarmup;
            if (!w || typeof w !== 'object') {
              w = globalThis.__gaiusPipelineWarmup = {misses: [], failedNames: [], log: []};
            }
            // The counters (failed, missCount, ...) are numbers; only the name lists are arrays.
            var a = w[list];
            if (!Array.isArray(a)) a = w[list] = [];
            a.push([Math.round(performance.now() * 10) / 10, value]);
            """)
    private static native void pageNote(String list, String value);

    @JSBody(params = "message", script = """
            try { console.warn('[gaius] ' + message); } catch (e) {}
            """)
    private static native void pageLog(String message);
}
