package dev.gaius.browser;

/**
 * WebAssembly GC implementation of the backend-dependent float arithmetic (the JavaScript one in
 * {@code src/main/java} rounds with {@code Math.fround}): a float is a wasm f32 here and the
 * double-to-float cast is an {@code f32.demote_f64}, which rounds as Java does. TeaVM does not
 * allow JSBody natives in class library classes under WebAssembly GC, so the class library
 * reaches the JavaScript version only through this class.
 */
public final class TargetMath {
    private TargetMath() {
    }

    /** {@code Math.fma(float, float, float)}: one double multiply and add, rounded to float. */
    public static float fma(float left, float right, float addend) {
        return (float) ((double) left * (double) right + (double) addend);
    }
}
