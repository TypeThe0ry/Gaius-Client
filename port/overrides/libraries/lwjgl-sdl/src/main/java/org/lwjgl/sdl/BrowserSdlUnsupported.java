package org.lwjgl.sdl;

import java.util.HashSet;
import java.util.Set;
import org.teavm.jso.JSBody;

/**
 * FEATURE stubs of SDL entry points. {@code dev.gaius.tools.LwjglSdlBrowserPatcher}
 * rewrites every {@code SDL_*} entry point that vanilla references (the profile's
 * {@code port/mc/profiles/<profile>/usage.lock}): one that {@link BrowserSdl} implements is
 * redirected there, any other gets the body {@code throw BrowserSdlUnsupported.entry("<Owner>.<name><descriptor>")}.
 * Only the entries a seam requires ({@code requiredEntries} of its seam.json) must be implemented, so
 * a new vanilla use of an SDL function degrades to this exception instead of failing the build.
 *
 * <p>The first call of each stub is reported once: {@code console.warn} and an entry
 * {@code {library, entry, time}} in {@code window.__gaiusFeatureStubs}.
 */
public final class BrowserSdlUnsupported {
    private static final Set<String> REPORTED = new HashSet<>();

    private BrowserSdlUnsupported() {
    }

    /** Reports {@code entry} on its first call and returns the exception the stub throws. */
    public static UnsupportedOperationException entry(String entry) {
        if (REPORTED.add(entry)) {
            report("lwjgl-sdl", entry);
        }
        return new UnsupportedOperationException("SDL entry point " + entry
                + " is not implemented in the browser (FEATURE stub)");
    }

    @JSBody(params = {"library", "entry"}, script = """
            const w = typeof window !== 'undefined' ? window : self;
            const stubs = Array.isArray(w.__gaiusFeatureStubs) ? w.__gaiusFeatureStubs : (w.__gaiusFeatureStubs = []);
            stubs.push({library: library, entry: entry, time: Date.now()});
            if (typeof console !== 'undefined') console.warn('[Gaius] FEATURE stub: ' + library + ' ' + entry);
            """)
    private static native void report(String library, String entry);
}
