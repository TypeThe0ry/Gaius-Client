package dev.gaius.tools;

/**
 * The TeaVM backend an overlay build is made for: {@code js} (the default, the existing
 * JavaScript build) or {@code wasmgc} (TeaVM WEBASSEMBLY_GC). build-overlays.sh exports
 * {@code GAIUS_TEAVM_TARGET}; a patcher may also be run with {@code -Dgaius.teavm.target}.
 * Patches that only exist to speed up the JavaScript output (JSBody kernels over Java arrays,
 * double-based long arithmetic) are skipped for wasmgc, where the vanilla bytecode is both exact
 * and fast.
 */
public final class BuildTarget {
    public static final String PROPERTY = "gaius.teavm.target";
    public static final String ENV = "GAIUS_TEAVM_TARGET";
    public static final String JS = "js";
    public static final String WASM_GC = "wasmgc";

    private BuildTarget() {
    }

    public static String name() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            value = System.getenv(ENV);
        }
        if (value == null || value.isBlank()) {
            return JS;
        }
        value = value.trim();
        if (!value.equals(JS) && !value.equals(WASM_GC)) {
            throw new IllegalStateException("Unknown TeaVM target '" + value
                    + "' (expected " + JS + " or " + WASM_GC + ")");
        }
        return value;
    }

    public static boolean wasmGc() {
        return WASM_GC.equals(name());
    }
}
