package dev.gaius.browser.kernel;

import org.teavm.jso.JSBody;

public final class KernelMirror {
    private KernelMirror() {
    }

    @JSBody(params = "kernel", script = "var scope = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var mirror = scope.GaiusKernelMirror;"
            + "return !!mirror && mirror.enabled(kernel) === true;")
    public static native boolean enabled(String kernel);
}
