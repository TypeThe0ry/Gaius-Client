package dev.gaius.browser;

import org.teavm.interop.PlatformMarker;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.platform.Platform;

/**
 * Host event-loop entry points shared by the JavaScript and the WebAssembly GC builds.
 *
 * <p>Browser platform code schedules work through this class instead of TeaVM's
 * {@code org.teavm.platform.Platform}, which the WebAssembly GC backend does not support. This
 * is the JavaScript implementation: every method delegates to {@code Platform} unchanged.
 * {@code port/overrides/classlib/src/target-wasmgc/java} holds the WebAssembly GC
 * implementation with the same API (TeaVM's event queue and JSPI activations).
 */
public final class HostLoop {
    private HostLoop() {
    }

    /**
     * Runs {@code task} later on a new cooperative thread, which may suspend
     * ({@code Platform.startThread}).
     */
    public static void startThread(Runnable task) {
        Platform.startThread(task::run);
    }

    /**
     * Runs {@code task} from a host timer callback after {@code delayMillis}; the task has no
     * cooperative thread and must not suspend ({@code Platform.schedule}). Returns an id for
     * {@link #killSchedule}.
     */
    public static int schedule(Runnable task, int delayMillis) {
        return Platform.schedule(task::run, delayMillis);
    }

    public static void killSchedule(int id) {
        Platform.killSchedule(id);
    }

    /**
     * False when the caller runs from a plain host callback that has no cooperative thread to
     * suspend. The JavaScript runtime reports that case itself by throwing ("Suspension point
     * reached from non-threading context"), so this build always answers true.
     */
    public static boolean canSuspend() {
        return true;
    }

    /**
     * True in the WebAssembly GC build. A platform marker: TeaVM folds each call to a constant
     * before dependency analysis, so code behind it is not even compiled for the other target.
     */
    @PlatformMarker("webassembly-gc")
    public static boolean isWasmGc() {
        return false;
    }

    /**
     * True in a WebAssembly GC build in JSPI mode (gaius.teavm.jspi), where a Java thread
     * suspends its whole stack and TeaVM's {@code @Async} methods are not available. A platform
     * marker like {@link #isWasmGc}; always false in this build.
     */
    @PlatformMarker("gaius-jspi")
    public static boolean jspi() {
        return false;
    }

    /** Starts host work that calls {@code resume} once (from any host callback) when done. */
    @FunctionalInterface
    public interface HostCall {
        void start(Runnable resume);
    }

    /**
     * JSPI only (see {@link #jspi}): suspends the current Java thread until the host work
     * started by {@code call} resumes it. This build suspends through TeaVM's {@code @Async}
     * methods instead.
     */
    public static void awaitHost(HostCall call) {
        throw new UnsupportedOperationException("HostLoop.awaitHost needs the JSPI build");
    }

    /**
     * JSPI only (see {@link #jspi}): lets the browser run, then resumes after
     * {@code delayMillis}; TModernRuntimeSupport.yieldToEventLoop uses TeaVM's coroutines in
     * this build.
     */
    public static void yieldToEventLoop(int delayMillis) {
        throw new UnsupportedOperationException("HostLoop.yieldToEventLoop needs the JSPI build");
    }

    /** Managed-block compatibility hooks; platform-specific WasmGC implementation yields. */
    public static long managedBlockYield(boolean predicate, long lastYieldNanos) {
        return lastYieldNanos;
    }

    public static long managedBlockIdle(long lastYieldNanos) {
        return System.nanoTime();
    }

    /**
     * Runs {@code task} from a host macrotask as soon as the browser is ready; like
     * {@link #schedule} the task has no cooperative thread. Platform.postpone uses
     * setTimeout(0), which is still subject to nested timer clamping; a MessagePort turn gives
     * other Worker messages a chance to run without imposing that delay on every cooperative
     * work slice.
     */
    public static void postMacrotask(Runnable task) {
        MacrotaskCallback callback = task::run;
        postMacrotask(callback);
    }

    /**
     * True while the current frame must not suspend: the patched TeaVM counts class
     * initializer frames in globalThis.__gaiusClinitDepth (gaius.teavm.syncClinits) and frames
     * of methods compiled synchronously in globalThis.__gaiusNoSuspendDepth
     * (gaius.teavm.asyncBarrier, gaius.teavm.syncMonitors). Builds without those options never
     * set the counters.
     */
    @JSBody(script = """
            return (globalThis.__gaiusClinitDepth | 0) > 0
              || (globalThis.__gaiusNoSuspendDepth | 0) > 0;
            """)
    public static native boolean inNoSuspendFrame();

    @JSFunctor
    private interface MacrotaskCallback extends JSObject {
        void run();
    }

    @JSBody(params = "callback", script = """
            let state = globalThis.__gaiusMacrotaskScheduler;
            if (!state) {
              state = {channel: null, pending: new Map(), sequence: 0, failed: false};
              globalThis.__gaiusMacrotaskScheduler = state;
              try {
                state.channel = new MessageChannel();
                state.channel.port1.onmessage = function(event) {
                  const resume = state.pending.get(event.data);
                  if (!resume) return;
                  state.pending.delete(event.data);
                  resume();
                };
              } catch (_) {
                state.failed = true;
              }
            }
            if (state.failed) {
              setTimeout(callback, 0);
              return;
            }
            const id = ++state.sequence;
            state.pending.set(id, callback);
            try {
              state.channel.port2.postMessage(id);
            } catch (_) {
              state.failed = true;
              try { state.channel.port1.close(); } catch (ignored) {}
              try { state.channel.port2.close(); } catch (ignored) {}
              const callbacks = Array.from(state.pending.values());
              state.pending.clear();
              for (let index = 0; index < callbacks.length; index++) {
                setTimeout(callbacks[index], 0);
              }
            }
            """)
    private static native void postMacrotask(MacrotaskCallback callback);

    // Declared after every method of the JavaScript build, so that TeaVM's per-class names of
    // the existing lambdas and JSBody functions, and with them the JavaScript output, stay as
    // they were.

    /**
     * WebAssembly GC only: records a present for the frame boundary of
     * {@link #awaitAnimationFrame}. This build yields after each present instead
     * (BrowserGlfw/BrowserSdlDom yieldAfterPresent), so nothing calls it here.
     */
    public static void framePresented(boolean hidden, int interval) {
    }

    /**
     * WebAssembly GC only (design D4): the frame boundary that the patched Minecraft.run calls
     * at the head of its loop (MinecraftClientPatcher.patchMinecraftRunFrameBoundary, a
     * wasm-only patch). This build yields after each present instead.
     */
    public static void awaitAnimationFrame() {
        throw new UnsupportedOperationException("HostLoop.awaitAnimationFrame needs the WebAssembly GC build");
    }
}
