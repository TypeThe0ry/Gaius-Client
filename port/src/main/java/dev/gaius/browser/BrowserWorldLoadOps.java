package dev.gaius.browser;

/**
 * Version-facing half of {@link BrowserWorldLoadPrewarm}. This default does nothing: a profile
 * whose vanilla world loader has the cached shape (see {@code BrowserWorldLoadCache}) replaces
 * this file with its generation's source set.
 */
final class BrowserWorldLoadOps {
    private BrowserWorldLoadOps() {
    }

    /** Runs the seed-independent part of the world load ahead of time; returns a short outcome.
     * {@code forceMiss} makes the record unusable (a diagnostic for the fallback path). */
    static String prewarm(boolean forceMiss) {
        return "unsupported";
    }

    /** Drops whatever a failed prewarm left behind. */
    static void discard() {
    }
}
