package dev.gaius.browser;

import org.teavm.jso.JSBody;

/**
 * The monotonic clock of the browser realm, for the deadline checks of the integrated server.
 *
 * <p>{@code performance} is not a data property of a Worker's global object but an accessor of
 * its prototype chain, and every reference to it runs a C++ getter inside the browser. A script
 * that tests {@code typeof performance}, loads {@code performance.now} and calls it makes three
 * of those calls for one reading, and TeaVM's own {@code System.nanoTime()} makes a fourth
 * through {@code global("performance")}. The server Worker reads the clock tens of thousands of
 * times per second while it generates terrain (a measured 4.6-5.0% of the thread, most of it
 * those getters), so the object is looked up once and kept: a reading is one call of
 * {@code now()} on the kept object.
 *
 * <p>The values are the ones the previous code produced: {@code performance.now()} in
 * milliseconds, or {@code Date.now()} in a realm without it, and {@link #nanoTime()} is
 * {@code (long) (performance.now() * 1e6)}, which is what TeaVM's {@code System.nanoTime()}
 * returns. {@code TeaVMClasslibPatcher} routes that method here in the WebAssembly GC build.
 *
 * <p>The object is kept in {@code globalThis.__gaiusClockSource} so that every module of a
 * realm shares it; a test that replaces {@code globalThis.performance} after the first reading
 * has to delete that property too (replacing {@code performance.now} itself needs nothing).
 */
public final class HostClock {
    private HostClock() {
    }

    /** {@code performance.now()}, or {@code Date.now()} where the realm has no such clock. */
    @JSBody(script = """
            let source = globalThis.__gaiusClockSource;
            if (source === undefined) {
              const candidate = globalThis.performance;
              source = candidate && typeof candidate.now === 'function'
                ? candidate
                : {now: function () { return Date.now(); }};
              globalThis.__gaiusClockSource = source;
            }
            return source.now();
            """)
    public static native double nowMillis();

    /** The value of TeaVM's {@code System.nanoTime()}: {@link #nowMillis()} in nanoseconds. */
    public static long nanoTime() {
        return (long) (nowMillis() * 1_000_000.0);
    }
}
