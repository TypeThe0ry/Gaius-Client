package dev.gaius.browser;

import org.teavm.jso.JSBody;

/**
 * Float arithmetic whose exact form depends on the TeaVM backend. This is the JavaScript
 * implementation; {@code port/overrides/classlib/src/target-wasmgc/java} holds the WebAssembly GC
 * one with the same API.
 *
 * <p>TeaVM's JavaScript backend keeps a float in a JS number and emits no rounding for a
 * double-to-float cast, so the rounding to float32 that Java's {@code (float)} performs must be
 * written as {@code Math.fround}.
 */
public final class TargetMath {
    private TargetMath() {
    }

    /** {@code Math.fma(float, float, float)}: one double multiply and add, rounded to float. */
    @JSBody(
            params = {"left", "right", "addend"},
            script = "return Math.fround(left * right + addend);")
    public static native float fma(float left, float right, float addend);
}
