package dev.gaius.browser;

import org.teavm.interop.Import;

/** Exact realm clock through a numeric import, without the generic JSBody bridge. */
public final class HostClock {
    private HostClock() {
    }

    /** A fresh host reading on every call, including within a single activation. */
    @Import(module = "gaiusClock", name = "nowMillis")
    public static native double nowMillis();

    /** Keep TeaVM's double multiplication and Java long conversion in wasm. */
    public static long nanoTime() {
        return (long) (nowMillis() * 1_000_000.0);
    }
}
