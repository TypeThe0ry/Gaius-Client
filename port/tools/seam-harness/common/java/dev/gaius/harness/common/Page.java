package dev.gaius.harness.common;

import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/** The harness page's {@code globalThis.__harness} object (web/index.html). */
public final class Page {
    private Page() {
    }

    @JSBody(script = "return String(globalThis.__harness.mode);")
    public static native String mode();

    /** The preprocessed vanilla shader text for {@code "<id>|VERTEX|FRAGMENT"}, or null. */
    @JSBody(params = "key", script = """
            var text = globalThis.__harness.shaders[key];
            return typeof text === "string" ? text : null;
            """)
    public static native String shader(String key);

    @JSBody(params = "status", script = "globalThis.__harness.status = status;")
    public static native void status(String status);

    @JSBody(params = { "key", "value" }, script = "globalThis.__harness.info[key] = value;")
    public static native void info(String key, String value);

    @JSBody(params = { "key", "value" }, script = "globalThis.__harness.info[key] = value;")
    public static native void info(String key, double value);

    @JSBody(script = "return performance.now();")
    public static native double now();

    /** Keeps a module the front-end produced, for the page to hash ({@code __harness.modules}). */
    public static void module(String key, byte[] bytes) {
        Int8Array array = Int8Array.create(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            array.set(i, bytes[i]);
        }
        module(key, array);
    }

    @JSBody(params = { "key", "bytes" }, script = "globalThis.__harness.modules[key] = bytes;")
    private static native void module(String key, Int8Array bytes);
}
