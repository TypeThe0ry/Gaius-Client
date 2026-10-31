package org.lwjgl.util.shaderc;

/**
 * The compiler behind {@link BrowserShaderc}.  The browser uses
 * {@link BrowserShadercWasm} (shaderc compiled to WebAssembly); the JVM corpus
 * harness installs one backed by the native LWJGL library to prove that the
 * shim path produces the native results.
 */
public interface BrowserShadercToolchain {
    /** Runs one job; never throws for a compilation failure. */
    BrowserShadercJob.Output compile(BrowserShadercJob job);
}
