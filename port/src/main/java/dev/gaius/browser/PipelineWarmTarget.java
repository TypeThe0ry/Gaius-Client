package dev.gaius.browser;

/**
 * Implemented by the client's pipeline cache (the patched {@code PipelineCache}) so that
 * {@link BrowserPipelineWarmup} can compile a deferred render pipeline without referring to
 * version-specific vanilla types. {@link #gaiusWarm} is the cache's own {@code get}: it returns the
 * cached pipeline or compiles it exactly as the first draw would.
 */
public interface PipelineWarmTarget {
    /** Returns the compiled pipeline of {@code pipeline} (a {@code RenderPipeline}), or null when it fails to compile. */
    Object gaiusWarm(Object pipeline);
}
