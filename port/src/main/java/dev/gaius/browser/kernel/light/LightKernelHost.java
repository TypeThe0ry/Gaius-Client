package dev.gaius.browser.kernel.light;

import org.teavm.jso.typedarrays.Int8Array;
import dev.gaius.browser.JsArrays;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * The JS side of the light kernel as the integrated server Worker sees it: whether
 * {@code light_column} jobs may be submitted, and the submit call itself. Profile independent
 * (no Minecraft types), so it is compiled for every profile.
 *
 * <p>A job goes to the first of:
 * <ol>
 *   <li>{@code globalThis.GaiusLightKernelHost}, installed by {@code GaiusLightJob.installHost}
 *       (port/web/kernels/light-job.js) with the page's switch;</li>
 *   <li>{@code globalThis.__gaiusKernelClient}, the kernel runtime client the server Worker
 *       bootstrap builds from the page's {@code gaius-kernel-port}, when it serves the
 *       {@code light} kernel ({@code available('light')}).</li>
 * </ol>
 *
 * <p>Switches that keep every chunk on the vanilla light engine: {@code lightKernel=0} (also
 * {@code off}/{@code false}/{@code no}, and the lower-case {@code lightkernel}) in the Worker
 * URL, {@code globalThis.GAIUS_LIGHT_KERNEL === false} or
 * {@code globalThis.__gaiusLightKernelConfig.enabled === false} (both set by the bootstrap from the
 * page's switch), a host whose {@code enabled} is false, and the runtime's own
 * {@code gaiusKernels=0} / {@code gaiusKernelsOff=light}.
 *
 * <p>Every submitted job settles exactly once: with its result, with the runtime's error, or with
 * {@code timeout} after {@link #timeoutMillis()} so a lost job never stalls a chunk.
 *
 * <p>Jobs leave the light table out and name only its epoch. A kernel instance that does not hold
 * that epoch (a fresh, restarted or trimmed worker) answers "table missing"; the job is then sent
 * once more with the table spliced in, and only that answer reaches the callbacks.
 */
public final class LightKernelHost {
    /** Default bound on light jobs in the pool at once ({@code __gaiusLightKernelConfig.maxInFlight}). */
    private static final int DEFAULT_MAX_IN_FLIGHT = 4;
    /** Default job timeout ({@code __gaiusLightKernelConfig.timeoutMs}). */
    private static final int DEFAULT_TIMEOUT_MS = 15000;

    private LightKernelHost() {
    }

    /** Receives the raw result buffer (ABI framing included) on the JS stack. */
    @JSFunctor
    public interface ResultCallback extends JSObject {
        void accept(JSObject result);
    }

    /** Receives {@code code:message}; see {@link #isTransient(String)}. */
    @JSFunctor
    public interface FailureCallback extends JSObject {
        void accept(String failure);
    }

    /** Whether jobs may be submitted now. Cheap enough to call per chunk. */
    public static boolean available() {
        return dev.gaius.browser.kernel.KernelMirror.enabled("light") && availableNative();
    }

    public static int maxInFlight() {
        int value = configInt("maxInFlight", DEFAULT_MAX_IN_FLIGHT);
        return value < 1 ? 1 : Math.min(value, 32);
    }

    public static int timeoutMillis() {
        int value = configInt("timeoutMs", DEFAULT_TIMEOUT_MS);
        return value < 1000 ? 1000 : value;
    }

    /**
     * Submits {@code bytes[0..length)} (a framed {@code light_column} job without its table). The
     * bytes are copied into a fresh transferable buffer before this returns, so the caller may
     * reuse the array; {@code table} (the job's light table) must stay unchanged while the job
     * runs. Returns false when no host takes jobs; otherwise exactly one callback follows.
     */
    public static boolean submit(byte[] bytes, int length, byte[] table, int chunkX, int chunkZ, int jobId,
            ResultCallback done, FailureCallback fail) {
        return available() && submitNative(bytes, length, table, chunkX, chunkZ, jobId, timeoutMillis(), done, fail);
    }

    /** Length of a result buffer delivered to {@link ResultCallback}. */
    public static int resultLength(JSObject result) {
        return resultLengthNative(result);
    }

    /** Copies a result buffer into {@code out} (of {@link #resultLength} bytes). */
    public static void copyResult(JSObject result, byte[] out) {
        copyResultNative(result, out);
    }

    /**
     * Whether a failure only refused that one job (superseded or stale, back pressure, runtime
     * briefly unavailable or closed, or a timeout, which a long queue of visible mesh work can
     * cause): the chunk is lit the vanilla way, but it does not count towards disabling the
     * kernel. Kernel errors, traps and crashes do count; a kernel that really hangs is stopped by
     * the runtime's own job watchdog, whose failures count.
     */
    public static boolean isTransient(String failure) {
        if (failure == null) {
            return false;
        }
        return failure.startsWith("superseded:") || failure.startsWith("stale:") || failure.startsWith("backpressure:")
                || failure.startsWith("request-cap:")
                || failure.startsWith("runtime-disabled:") || failure.startsWith("runtime-unavailable:")
                || failure.startsWith("terminated:") || failure.startsWith("aborted:")
                || failure.startsWith("cancelled:") || failure.startsWith("no-host:")
                || failure.startsWith("timeout:");
    }

    public static void log(String message) {
        logNative(message);
    }

    // --- JS bindings (Rhino-compatible scripts) ------------------------------------------------

    private static final String SCOPE = "var scope = typeof globalThis !== 'undefined' ? globalThis : self;";

    /** Shared switch logic: false when any off switch is set. */
    private static final String SWITCHED_ON = "function switchedOn(scope) {"
            + " if (scope.GAIUS_LIGHT_KERNEL === false) { return false; }"
            + " var config = scope.__gaiusLightKernelConfig;"
            + " if (config && config.enabled === false) { return false; }"
            + " try {"
            + "  var search = String(scope.location && scope.location.search || '');"
            + "  var match = /[?&]light[kK]ernel=([^&#]*)/.exec(search);"
            + "  if (match && /^(0|off|false|no)$/i.test(decodeURIComponent(match[1]))) { return false; }"
            + " } catch (ignored) {}"
            + " return true;"
            + "}";

    /** Picks the job target: {kind: 'host'|'client', target} or null. */
    private static final String PICK = "function pick(scope) {"
            + " if (!switchedOn(scope)) { return null; }"
            + " var host = scope.GaiusLightKernelHost;"
            + " if (host && typeof host.submit === 'function') {"
            + "  return host.enabled === false ? null : {kind: 'host', target: host};"
            + " }"
            + " var client = scope.__gaiusKernelClient;"
            + " if (client && typeof client.submit === 'function'"
            + "   && (typeof client.available !== 'function' || client.available('light'))) {"
            + "  return {kind: 'client', target: client};"
            + " }"
            + " return null;"
            + "}";

    @JSBody(script = SCOPE + SWITCHED_ON + PICK + "return pick(scope) !== null;")
    private static native boolean availableNative();

    @JSBody(params = {"name", "fallback"}, script = SCOPE
            + "var config = scope.__gaiusLightKernelConfig;"
            + "var value = config ? Number(config[name]) : NaN;"
            + "return isFinite(value) && value > 0 ? (value | 0) : fallback;")
    private static native int configInt(String name, int fallback);

    /**
     * Job framing the splice relies on (port/web/kernels/light-job.js withTable): 16-byte ABI
     * header, 48-byte payload header with {@code table_len} at payload offset 28, the table right
     * after the payload header and padded to 8 bytes. A result with payload byte 0 = 2 (result
     * version) and byte 1 = 1 means "table missing".
     */
    @JSBody(params = {"bytes", "length", "table", "chunkX", "chunkZ", "jobId", "timeoutMs", "done", "fail"},
            script = SCOPE + SWITCHED_ON + PICK
            + "var chosen = pick(scope);"
            + "if (chosen === null) { return false; }"
            + "var lean = new Uint8Array(length);"
            + "lean.set(new Uint8Array(bytes.buffer, bytes.byteOffset, length));"
            + "var settled = false;"
            + "var resent = false;"
            + "var timer = null;"
            + "function finish() {"
            + " if (settled) { return false; }"
            + " settled = true;"
            + " if (timer !== null) { clearTimeout(timer); }"
            + " return true;"
            + "}"
            + "function bad(code, error) {"
            + " if (!finish()) { return; }"
            + " var text = error && error.message ? error.message : String(error);"
            + " var errorCode = error && error.code ? String(error.code) : code;"
            + " fail(errorCode + ':' + text);"
            + "}"
            + "function tableMissing(result) {"
            + " if (!result || (result.byteLength | 0) < 18) { return false; }"
            + " var view = result instanceof ArrayBuffer ? new DataView(result)"
            + "  : new DataView(result.buffer, result.byteOffset, result.byteLength);"
            + " return view.getUint32(0, true) === 0x53524b47 && view.getUint16(6, true) === 0"
            + "  && view.getUint8(16) === 2 && view.getUint8(17) === 1;"
            + "}"
            + "function withTable() {"
            + " var count = table.length;"
            + " var inserted = (count + 7) & ~7;"
            + " var out = new Uint8Array(length + inserted);"
            + " out.set(lean.subarray(0, 64), 0);"
            + " out.set(new Uint8Array(table.buffer, table.byteOffset, count), 64);"
            + " out.set(lean.subarray(64), 64 + inserted);"
            + " var view = new DataView(out.buffer);"
            + " view.setUint32(12, view.getUint32(12, true) + inserted, true);"
            + " view.setUint32(44, count, true);"
            + " return out.buffer;"
            + "}"
            + "function send(buffer) {"
            + " if (chosen.kind === 'host') {"
            + "  chosen.target.submit(buffer, 0, ok, function (message) { bad('kernel-error', message); });"
            + " } else {"
            + "  chosen.target.submit('light_column', buffer, {kernel: 'light', key: 'light:' + chunkX + ',' + chunkZ,"
            + "   version: jobId >>> 0, cx: chunkX, cz: chunkZ, visible: true})"
            + "   .then(ok, function (error) { bad('kernel-error', error); });"
            + " }"
            + "}"
            + "function ok(result) {"
            + " if (settled) { return; }"
            + " if (tableMissing(result)) {"
            + "  if (resent || !table || !(table.length > 0)) {"
            + "   bad('kernel-error', 'light_column kernel lacks the light table');"
            + "   return;"
            + "  }"
            + "  resent = true;"
            + "  try { send(withTable()); } catch (error) { bad('no-host', error); }"
            + "  return;"
            + " }"
            + " if (finish()) { done(result); }"
            + "}"
            // A job that timed out is cancelled in the runtime, so it does not hold a kernel worker.
            + "timer = setTimeout(function () {"
            + " if (chosen.kind === 'client' && typeof chosen.target.cancel === 'function') {"
            + "  try { chosen.target.cancel('light:' + chunkX + ',' + chunkZ, 'light'); } catch (error) { }"
            + " }"
            + " bad('timeout', 'light_column job timed out');"
            + "}, timeoutMs);"
            + "try {"
            + " send(lean.slice(0).buffer);"
            + "} catch (error) {"
            + " bad('no-host', error);"
            + "}"
            + "return true;")
    private static native boolean submitNativeJs(Int8Array bytes, int length, Int8Array table, int chunkX, int chunkZ, int jobId, int timeoutMs, ResultCallback done, FailureCallback fail);

    private static boolean submitNative(byte[] bytes, int length, byte[] table, int chunkX, int chunkZ, int jobId, int timeoutMs, ResultCallback done, FailureCallback fail) {
        if (JsArrays.LINEAR_MEMORY) {
            // WebAssembly GC: a typed-array view is a copy here, so the job's bytes cross through
            // the linear-memory scratch area (only the length used, not the whole job buffer;
            // the script copies them before it returns), and the light table, which only the
            // resend of a "table missing" job reads, is copied once per table instead of per job.
            if (table != linearTableSource) {
                linearTableView = JsArrays.int8(table);
                linearTableSource = table;
            }
            int address = JsArrays.stageInt8(bytes, 0, length);
            return submitNativeJs(linearBytes(address, length), length, linearTableView, chunkX, chunkZ, jobId,
                    timeoutMs, done, fail);
        }
        return submitNativeJs(JsArrays.int8(bytes), length, JsArrays.int8(table), chunkX, chunkZ, jobId, timeoutMs, done, fail);
    }

    /** The light table {@link #linearTableView} copies (WebAssembly GC). */
    private static byte[] linearTableSource;
    private static Int8Array linearTableView;

    /** A view of {@code length} bytes of linear memory at {@code address} (WebAssembly GC). */
    @JSBody(params = {"address", "length"}, script = "var linear = globalThis.__gaiusLinear;"
            + "if (!linear.u8.length) { linear.refresh(); }"
            + "return new Int8Array(linear.buffer, address >>> 0, length);")
    private static native Int8Array linearBytes(int address, int length);

    @JSBody(params = "result", script = "return result ? (result.byteLength | 0) : 0;")
    private static native int resultLengthNative(JSObject result);

    @JSBody(params = {"result", "out"}, script = "var view = result instanceof ArrayBuffer"
            + " ? new Int8Array(result) : new Int8Array(result.buffer, result.byteOffset, result.byteLength);"
            + "out.set(view.subarray(0, out.length));")
    private static native void copyResultNativeJs(JSObject result, Int8Array out);

    private static void copyResultNative(JSObject result, byte[] out) {
        Int8Array outView = JsArrays.int8(out);
        copyResultNativeJs(result, outView);
        JsArrays.readBack(outView, out);
    }

    @JSBody(params = "message", script = "if (typeof console !== 'undefined') { console.log(message); }")
    private static native void logNative(String message);
}
