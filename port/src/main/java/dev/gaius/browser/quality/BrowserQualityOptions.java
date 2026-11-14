package dev.gaius.browser.quality;

import java.util.Arrays;
import org.teavm.jso.JSBody;

/**
 * Option-level hooks of the graphics quality tiers.
 *
 * <ul>
 *   <li>{@link #filterQualityOptions}: 26.3 Video Settings lists the Improved Transparency
 *       option only when {@link BrowserQualityCaps#improvedTransparencyAllowed()} says it can
 *       run, so the menu never offers a switch that does nothing.</li>
 *   <li>{@link #debugCpuInfo}: the CPU line of the F3 system specs (26.3
 *       {@code DebugEntrySystemSpecs.getCpuInfo}, {@code GLX._getCpuInfo} before it) names the
 *       graphics tier and whether the video options follow it, e.g.
 *       {@code Browser runtime, Gaius quality high (auto)}.</li>
 * </ul>
 */
public final class BrowserQualityOptions {
    /** Re-asks the runtime after this many calls (the debug screen asks every frame). */
    private static final int DEBUG_REFRESH_CALLS = 600;
    private static String debugQuality;
    private static int debugCalls;

    private BrowserQualityOptions() {
    }

    /** {@code base} plus the quality tier and its source, for the F3 CPU line. */
    public static String debugCpuInfo(String base) {
        if (debugQuality == null || ++debugCalls >= DEBUG_REFRESH_CALLS) {
            debugCalls = 0;
            String quality;
            try {
                quality = queryDebugQuality();
            } catch (Throwable exception) {
                quality = "";
            }
            debugQuality = quality == null ? "" : quality;
        }
        return debugQuality.isEmpty() ? base : base + ", " + debugQuality;
    }

    /**
     * Returns {@code options} without {@code improvedTransparency} unless the runtime allows
     * order-independent transparency. The result keeps the runtime array type of
     * {@code options} ({@code OptionInstance[]}), which the patched caller casts back to.
     */
    public static Object[] filterQualityOptions(Object[] options, Object improvedTransparency) {
        if (options == null || improvedTransparency == null
                || BrowserQualityCaps.improvedTransparencyAllowed()) {
            return options;
        }
        int index = -1;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == improvedTransparency) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return options;
        }
        Object[] result = Arrays.copyOf(options, options.length - 1);
        System.arraycopy(options, index + 1, result, index, options.length - index - 1);
        return result;
    }

    /** "Gaius quality high (auto)", "... (override)", "... (player settings)"; empty if unknown. */
    @JSBody(script = """
            try {
              var quality = globalThis.GaiusQuality;
              var caps = quality && quality.caps && typeof quality.caps.current === 'function'
                  ? quality.caps.current() : null;
              if (!caps || !caps.tier) return '';
              var preset = globalThis.__gaiusAutoPreset;
              var source = caps.overridden ? 'override' : 'auto';
              if (preset && preset.automatic === false) source = 'player settings';
              return 'Gaius quality ' + String(caps.tier) + ' (' + source + ')';
            } catch (e) {
              return '';
            }
            """)
    private static native String queryDebugQuality();

}
