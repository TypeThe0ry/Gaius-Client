package dev.gaius.tests.jspi;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/** JS helpers of the JSPI semantic suite: reporting, clock, parameters and callback sources. */
final class Host {
    private Host() {
    }

    @JSFunctor
    interface IntSink extends JSObject {
        void accept(int value);
    }

    @JSBody(script = "return performance.now();")
    static native double now();

    /** A runner parameter (globalThis.__jspiSuiteParams), or -1 when it is not set. */
    @JSBody(params = "name", script = "var p = globalThis.__jspiSuiteParams;"
            + "var v = p ? p[name] : undefined; return v === undefined ? -1 : v;")
    static native int param(String name);

    /** Records one check: globalThis.__jspiSuite.results[key] = value, and a RESULT line. */
    @JSBody(params = {"key", "value"}, script = "var s = globalThis.__jspiSuite"
            + " || (globalThis.__jspiSuite = {results: {}, log: [], done: false});"
            + "s.results[key] = value; console.log('RESULT ' + key + ' ' + value);")
    static native void report(String key, String value);

    @JSBody(params = "line", script = "var s = globalThis.__jspiSuite"
            + " || (globalThis.__jspiSuite = {results: {}, log: [], done: false});"
            + "s.log.push(line); if (s.log.length > 400) s.log.shift();")
    static native void log(String line);

    @JSBody(params = "summary", script = "var s = globalThis.__jspiSuite"
            + " || (globalThis.__jspiSuite = {results: {}, log: [], done: false});"
            + "s.summary = summary; s.done = true; console.log('DONE ' + summary);")
    static native void done(String summary);

    /** A counter of the gaius-jspi.js scheduler stats (globalThis.__gaiusJspiScheduler), or -1. */
    @JSBody(params = "name", script = "var s = globalThis.__gaiusJspiScheduler;"
            + "var v = s && s.stats ? s.stats[name] : undefined;"
            + "return typeof v === 'number' ? v : -1;")
    static native int jspiStat(String name);

    /** globalThis.__gaiusClinitSuspensions (the counter both builds' acceptance reports read). */
    @JSBody(script = "return globalThis.__gaiusClinitSuspensions | 0;")
    static native int clinitSuspensions();

    /** Calls sink(0..count-1), one value per macrotask, from setTimeout callbacks. */
    @JSBody(params = {"sink", "count"}, script = "var i = 0;"
            + "function tick() { var v = i; i = i + 1; sink(v); if (i < count) { setTimeout(tick, 0); } }"
            + "setTimeout(tick, 0);")
    static native void deliverLater(IntSink sink, int count);

    /** Calls sink(value) once from a setTimeout callback and records a thrown error. */
    @JSBody(params = {"sink", "value"}, script = "globalThis.__jspiCallbackError = '';"
            + "setTimeout(function() {"
            + " try { sink(value); globalThis.__jspiCallbackError = 'none'; }"
            + " catch (e) { globalThis.__jspiCallbackError = String(e && e.message || e); } }, 0);")
    static native void callOnce(IntSink sink, int value);

    /** Calls sink(value) synchronously (a JS frame between two wasm frames). */
    @JSBody(params = {"sink", "value"}, script = "try { sink(value); return 'none'; }"
            + " catch (e) { return String(e && e.name) + ': ' + String(e && e.message || e); }")
    static native String callNow(IntSink sink, int value);

    @JSBody(script = "return String(globalThis.__jspiCallbackError || '');")
    static native String callbackError();
}
