package dev.gaius.browser.quality;

import org.teavm.jso.JSBody;

/**
 * Capability gates of the graphics quality tiers, answered by the page's quality runtime
 * ({@code port/web/runtime/quality}).
 *
 * <p>{@code QualityTierPatches.patchImprovedTransparencyByTier} wraps 26.3's
 * {@code GameRenderer.useImprovedTransparency()} in {@link #filterImprovedTransparency}: the
 * player's option still decides, but order-independent transparency only runs when the context
 * renders and blends float colour targets ({@code EXT_color_buffer_float},
 * {@code EXT_float_blend}, complete RGBA16F/RGBA32F framebuffers, two draw buffers with
 * per-buffer blend enables and write masks through {@code OES_draw_buffers_indexed}) and the GPU
 * tier is high or ultra ({@code ?gaiusOit=1} lifts the tier condition, {@code ?gaiusOit=0}
 * forces the previous always-off behaviour). Without the runtime the answer is "off", which is
 * the behaviour of earlier releases.
 */
public final class BrowserQualityCaps {
    /** Re-asks the runtime after this many calls (the renderer asks several times per frame). */
    private static final int REFRESH_CALLS = 600;
    private static int improvedTransparency = -1;
    private static int callsSinceRefresh;

    private BrowserQualityCaps() {
    }

    public static boolean filterImprovedTransparency(boolean requested) {
        return requested && improvedTransparencyAllowed();
    }

    public static boolean improvedTransparencyAllowed() {
        if (improvedTransparency < 0 || ++callsSinceRefresh >= REFRESH_CALLS) {
            callsSinceRefresh = 0;
            int answer = queryImprovedTransparency();
            // -1: no context or runtime yet. Do not cache, so the first frame after the context
            // exists gets the real answer instead of a startup "no".
            improvedTransparency = answer < 0 ? -1 : answer;
            return answer > 0;
        }
        return improvedTransparency > 0;
    }

    /**
     * -1 while there is no context to ask about yet. Gate by backend: under
     * WebGPU ({@code GaiusWebGpu.active}) the core's {@code contextAvailable()} replaces the WebGL
     * 2 check and the runtime answers from the WebGPU device's caps.
     */
    @JSBody(script = """
            try {
              var quality = globalThis.GaiusQuality;
              if (!quality || !quality.runtime || !quality.profile) return -1;
              var gpu = globalThis.GaiusWebGpu;
              if (gpu && gpu.active === true && typeof gpu.contextAvailable === 'function') {
                if (gpu.contextAvailable() !== true) return -1;
              } else if (!globalThis.__gaiusWebGL) {
                return -1;
              }
              return quality.runtime.oitAllowed() ? 1 : 0;
            } catch (e) {
              return 0;
            }
            """)
    private static native int queryImprovedTransparency();
}
