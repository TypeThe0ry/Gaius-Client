package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Opt-in counters for tracing the client block-breaking render path. */
public final class BrowserBlockBreakingTelemetry {
    private BrowserBlockBreakingTelemetry() {
    }

    public static void recordDestroyProgress(int stage) {
        if (!BrowserBuildFlags.telemetry()) return;
        recordDestroyProgressJs(stage);
    }

    public static void recordExtraction() {
        if (!BrowserBuildFlags.telemetry()) return;
        recordExtractionJs();
    }

    public static void recordEmitted() {
        if (!BrowserBuildFlags.telemetry()) return;
        recordEmittedJs();
    }

    public static void recordSubmitPass() {
        if (!BrowserBuildFlags.telemetry()) return;
        recordSubmitPassJs();
    }

    public static void recordActualSubmit() {
        if (!BrowserBuildFlags.telemetry()) return;
        recordActualSubmitJs();
    }

    @JSBody(params = "stage", script = """
            try {
              if (globalThis.__gaiusBlockBreakingTelemetryEnabled !== true) return;
              const state = globalThis.__gaiusBlockBreakingTelemetry ||
                (globalThis.__gaiusBlockBreakingTelemetry = {
                  destroyProgressCalls: 0, extractionCalls: 0, emittedStates: 0,
                  submitPasses: 0, actualSubmitCalls: 0, lastStage: -1, updatedAt: 0
                });
              state.destroyProgressCalls++;
              state.lastStage = Number(stage) | 0;
              state.updatedAt = (typeof performance !== 'undefined' && performance.now)
                ? performance.now() : Date.now();
            } catch (ignored) {}
            """)
    private static native void recordDestroyProgressJs(int stage);

    @JSBody(script = """
            try {
              if (globalThis.__gaiusBlockBreakingTelemetryEnabled !== true) return;
              const state = globalThis.__gaiusBlockBreakingTelemetry ||
                (globalThis.__gaiusBlockBreakingTelemetry = {
                  destroyProgressCalls: 0, extractionCalls: 0, emittedStates: 0,
                  submitPasses: 0, actualSubmitCalls: 0, lastStage: -1, updatedAt: 0
                });
              state.extractionCalls++;
              state.updatedAt = (typeof performance !== 'undefined' && performance.now)
                ? performance.now() : Date.now();
            } catch (ignored) {}
            """)
    private static native void recordExtractionJs();

    @JSBody(script = """
            try {
              if (globalThis.__gaiusBlockBreakingTelemetryEnabled !== true) return;
              const state = globalThis.__gaiusBlockBreakingTelemetry ||
                (globalThis.__gaiusBlockBreakingTelemetry = {
                  destroyProgressCalls: 0, extractionCalls: 0, emittedStates: 0,
                  submitPasses: 0, actualSubmitCalls: 0, lastStage: -1, updatedAt: 0
                });
              state.emittedStates++;
              state.updatedAt = (typeof performance !== 'undefined' && performance.now)
                ? performance.now() : Date.now();
            } catch (ignored) {}
            """)
    private static native void recordEmittedJs();

    @JSBody(script = """
            try {
              if (globalThis.__gaiusBlockBreakingTelemetryEnabled !== true) return;
              const state = globalThis.__gaiusBlockBreakingTelemetry ||
                (globalThis.__gaiusBlockBreakingTelemetry = {
                  destroyProgressCalls: 0, extractionCalls: 0, emittedStates: 0,
                  submitPasses: 0, actualSubmitCalls: 0, lastStage: -1, updatedAt: 0
                });
              state.submitPasses++;
              state.updatedAt = (typeof performance !== 'undefined' && performance.now)
                ? performance.now() : Date.now();
            } catch (ignored) {}
            """)
    private static native void recordSubmitPassJs();

    @JSBody(script = """
            try {
              if (globalThis.__gaiusBlockBreakingTelemetryEnabled !== true) return;
              const state = globalThis.__gaiusBlockBreakingTelemetry ||
                (globalThis.__gaiusBlockBreakingTelemetry = {
                  destroyProgressCalls: 0, extractionCalls: 0, emittedStates: 0,
                  submitPasses: 0, actualSubmitCalls: 0, lastStage: -1, updatedAt: 0
                });
              state.actualSubmitCalls++;
              state.updatedAt = (typeof performance !== 'undefined' && performance.now)
                ? performance.now() : Date.now();
            } catch (ignored) {}
            """)
    private static native void recordActualSubmitJs();
}
