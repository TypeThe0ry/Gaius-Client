package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Opt-in evidence for the 26.2 inventory avatar render path. */
public final class BrowserGuiEntityTelemetry {
    private BrowserGuiEntityTelemetry() {
    }

    public static void renderer(Object renderer) {
        if (enabled()) record("rendererCalls", "rendererClass", className(renderer));
    }

    public static void state(Object state) {
        if (enabled()) record("stateCalls", "stateClass", className(state));
    }

    public static void skin(Object skin) {
        if (enabled()) record("skinCalls", "skinClass", className(skin));
    }

    public static void avatarState(Object state) {
        if (enabled()) record("textureStateCalls", "textureStateClass", className(state));
    }

    public static void texture(Object identifier) {
        if (enabled()) record("textureCalls", "texturePath", textureName(identifier));
    }

    public static void submit() {
        if (enabled()) submitJs();
    }

    static String className(Object value) {
        return value == null ? "<null>" : value.getClass().getName();
    }

    static String textureName(Object value) {
        return value == null ? "<null>" : String.valueOf(value);
    }

    private static void record(String countKey, String valueKey, String value) {
        recordJs(countKey, valueKey, value);
    }

    /** Always false in a build that strips telemetry, without asking the page. */
    private static boolean enabled() {
        return BrowserBuildFlags.telemetry() && enabledJs();
    }

    @JSBody(script = "return globalThis.__gaiusEntityRenderTelemetryEnabled === true;")
    private static native boolean enabledJs();

    @JSBody(params = {"countKey", "valueKey", "value"}, script = """
            try {
              if (globalThis.__gaiusEntityRenderTelemetryEnabled !== true) return;
              const state = globalThis.__gaiusEntityRenderTelemetry ||
                (globalThis.__gaiusEntityRenderTelemetry = {
                  rendererCalls: 0, stateCalls: 0, skinCalls: 0,
                  textureStateCalls: 0, textureCalls: 0, updatedAt: 0
                });
              state[countKey] = (Number(state[countKey]) || 0) + 1;
              if (value != null) {
                state[valueKey] = String(value);
              }
              state.updatedAt = (typeof performance !== 'undefined' && performance.now)
                ? performance.now() : Date.now();
            } catch (ignored) {}
            """)
    private static native void recordJs(String countKey, String valueKey, String value);

    @JSBody(script = """
            try {
              if (globalThis.__gaiusEntityRenderTelemetryEnabled !== true) return;
              const state = globalThis.__gaiusEntityRenderTelemetry ||
                (globalThis.__gaiusEntityRenderTelemetry = {
                  rendererCalls: 0, stateCalls: 0, skinCalls: 0,
                  textureStateCalls: 0, textureCalls: 0, submitCalls: 0, updatedAt: 0
                });
              state.submitCalls = (Number(state.submitCalls) || 0) + 1;
              state.updatedAt = (typeof performance !== 'undefined' && performance.now)
                ? performance.now() : Date.now();
            } catch (ignored) {}
            """)
    private static native void submitJs();
}
