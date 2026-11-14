package dev.gaius.browser;

import org.teavm.jso.JSBody;

/**
 * The part of the server's world load that does not depend on the world, run while an idle
 * Worker's Main waits in {@link BrowserIntegratedServerMain#resumeAfterBootstrap}. The work itself
 * (decoding the default data pack's registries) is {@code BrowserWorldLoadOps}; this class only
 * decides whether to run it, bounds its failure and records the outcome for the page. Nothing
 * here can change a result: a failed or skipped prewarm leaves the vanilla load untouched.
 */
final class BrowserWorldLoadPrewarm {
    private BrowserWorldLoadPrewarm() {
    }

    /** Runs the prewarm when the page asked for it; never throws. */
    static void runWhileParked() {
        if (!requested()) {
            publish("skipped", 0);
            return;
        }
        double started = now();
        publish("running", 0);
        String outcome;
        try {
            outcome = BrowserWorldLoadOps.prewarm(forceMiss());
        } catch (Throwable failure) {
            try {
                BrowserWorldLoadOps.discard();
            } catch (Throwable ignored) {
                // The vanilla load below does not depend on the prewarm.
            }
            outcome = "failed: " + failure;
        }
        int elapsed = (int) Math.round(now() - started);
        publish(outcome, elapsed);
        BrowserIntegratedServerMain.reportRuntimeEvent("world-load-prewarm", outcome + " " + elapsed + "ms");
    }

    // ?gaiusSrvInit=1 and =miss: the page's word for the request ("miss" records a pack list no
    // world has, so the real load takes the fallback path with a record on offer; a diagnostic).
    @JSBody(script = "return globalThis.__gaiusServerPrewarmWorld === true"
            + " || globalThis.__gaiusServerPrewarmWorld === 'miss';")
    private static native boolean requested();

    @JSBody(script = "return globalThis.__gaiusServerPrewarmWorld === 'miss';")
    private static native boolean forceMiss();

    @JSBody(params = {"state", "elapsedMs"}, script =
            "globalThis.__gaiusServerWorldPrewarmState = state;"
            + "globalThis.__gaiusServerWorldPrewarmMs = elapsedMs;")
    private static native void publish(String state, int elapsedMs);

    @JSBody(script = "return performance.now();")
    private static native double now();
}
