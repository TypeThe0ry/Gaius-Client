package dev.gaius.browser;

import org.teavm.interop.PlatformMarker;

/**
 * Compile-time switches of the TeaVM build.
 *
 * <p>Lives in the class library overlay so that the overlay itself (for example
 * {@link BrowserBitStorage}) and every port/src class, which compiles against that overlay,
 * can use it.
 */
public final class BrowserBuildFlags {
    private BrowserBuildFlags() {
    }

    /**
     * True unless the role strips telemetry. TeaVM's PlatformMarkerSupport replaces each call
     * with a constant: true when the build has the {@code gaius-telemetry} platform tag
     * (GaiusTeaVMOptions adds it unless {@code gaius.teavm.telemetry} is false, which
     * {@code GAIUS_TEAVM_STRIP_TELEMETRY=true} sets and build-teavm-release.sh makes the
     * default), false otherwise, so a release drops the guarded code before optimization.
     * A build without the Gaius role options has no tag either. On the JVM (smokes and
     * harnesses) the method body runs and telemetry stays on.
     *
     * <p>Guard only telemetry that nothing in release acceptance, performance tooling or game
     * logic reads; runtime opt-ins such as {@code __gaiusEntityRenderTelemetryEnabled} keep
     * working in a profiling build ({@code GAIUS_TEAVM_STRIP_TELEMETRY=false}).
     */
    @PlatformMarker("gaius-telemetry")
    public static boolean telemetry() {
        return true;
    }
}
