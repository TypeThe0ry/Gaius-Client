package dev.gaius.browser;

import org.teavm.interop.AsyncCallback;
import org.teavm.interop.Import;
import org.teavm.interop.PlatformMarker;
import org.teavm.runtime.EventQueue;

/**
 * WebAssembly GC implementation of the host event-loop entry points (the JavaScript one in
 * {@code src/main/java} delegates to TeaVM's {@code Platform}, which this backend does not
 * support).
 *
 * <p>The WebAssembly GC build always runs in JSPI mode (gaius.teavm.jspi, design D3; the
 * coroutine mode was removed in M1): every Java thread is a {@code WebAssembly.promising}
 * activation (JspiHost), new threads start as activations and a thread waits for host work by
 * parking its activation ({@link #awaitHost}).
 *
 * <p>TeaVM's WebAssembly GC runtime keeps no queue of its own: {@link EventQueue#offer} hands
 * the event to the {@code teavmAsync.offer} import of the runtime JS, which gaius-jspi.js
 * routes into the same run queue as the thread resumptions (port/web/runtime/wasmgc).
 *
 * <p>The frame loop (design D4): the patched {@code Minecraft.run} calls
 * {@link #awaitAnimationFrame} at the head of its while loop, so the browser gets its turn
 * (compositing, input, timers) between two frames and nowhere inside one. A present only
 * records its swap interval ({@link #framePresented}); see port/web/runtime/host-abi.md.
 */
public final class HostLoop {
    /** Swap interval of the last present: 0 uncapped, otherwise synchronized to the display. */
    private static int presentInterval = 1;

    private HostLoop() {
    }

    /**
     * Runs {@code task} later in a new activation with the identity of the current thread
     * (JspiHost.startTask); the task may block. Like {@code Platform.startThread} of the
     * JavaScript build it does not switch {@code Thread.currentThread()}.
     */
    public static void startThread(Runnable task) {
        JspiHost.startTask(task);
    }

    /**
     * Runs {@code task} from a host callback after {@code delayMillis}; the task has no
     * activation and must not block. Returns an id for {@link #killSchedule}.
     */
    public static int schedule(Runnable task, int delayMillis) {
        return EventQueue.offer(task::run, System.currentTimeMillis() + Math.max(0, delayMillis));
    }

    /**
     * Cancels a {@link #schedule} that has not run yet. TeaVM 0.15's EventQueue.kill only
     * searches the event heap of the other backends; under WebAssembly GC the events live in
     * the runtime JS (a setTimeout id, or a run-queue id of gaius-jspi.js), so the id goes to
     * the runtime's teavmAsync.kill import directly.
     */
    public static void killSchedule(int id) {
        killHost(id);
    }

    @Import(module = "teavmAsync", name = "kill")
    private static native int killHost(int id);

    /**
     * False in a JS callback into Java (outside any activation, or a synchronous JS-to-Java
     * call inside one), where nothing can suspend.
     */
    public static boolean canSuspend() {
        return JspiHost.inActivation();
    }

    /** True in the WebAssembly GC build (a platform marker, folded by TeaVM). */
    @PlatformMarker("webassembly-gc")
    public static boolean isWasmGc() {
        return true;
    }

    /** True in JSPI mode (a platform marker, folded by TeaVM; see JspiHost#enabled). */
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
     * Suspends the current Java thread (its whole wasm stack) until the host work started by
     * {@code call} resumes it. {@code resume} may run from a JS callback.
     */
    public static void awaitHost(HostCall call) {
        AsyncCallback<Object> result = JspiHost.newResult();
        call.start(() -> result.complete(null));
        JspiHost.awaitResult(result);
    }

    /**
     * Suspends the current Java thread for at least {@code delayMillis}, or for one pass of
     * the run queue when it is 0 or less (the queue yields to the browser every few
     * milliseconds). Returns at once from a JS callback, which has no thread to suspend; the
     * yield is optional there (TModernRuntimeSupport.yieldToEventLoop).
     */
    public static void yieldToEventLoop(int delayMillis) {
        if (!JspiHost.inActivation()) {
            return;
        }
        awaitHost(resume -> {
            if (delayMillis > 0) {
                schedule(resume, delayMillis);
            } else {
                postMacrotask(resume);
            }
        });
    }

    /** Yields only after a busy managed-block loop has consumed a two millisecond slice. */
    public static long managedBlockYield(boolean predicate, long lastYieldNanos) {
        if (!predicate) {
            long now = System.nanoTime();
            if (now - lastYieldNanos >= 2_000_000L) {
                yieldToEventLoop(0);
                return now;
            }
        }
        return lastYieldNanos;
    }

    /** A false pollTask result means the queue is empty; let worker replies and timers run. */
    public static long managedBlockIdle(long lastYieldNanos) {
        yieldToEventLoop(0);
        return System.nanoTime();
    }

    /**
     * Records a present (SDL_GL_SwapWindow, glfwSwapBuffers): its swap interval decides how
     * the next {@link #awaitAnimationFrame} waits. {@code hidden} is not kept: the frame wait
     * reads the page visibility itself when it starts.
     */
    public static void framePresented(boolean hidden, int interval) {
        presentInterval = interval;
    }

    /**
     * The frame boundary at the head of the {@code Minecraft.run} loop (design D4, the
     * WebAssembly GC replacement of the per-present yield): suspends the main thread until the
     * browser is ready for the next frame. With the last present synchronized to the display
     * the wait ends after the next animation frame; uncapped presents wait for one macrotask
     * in a world and for the next animation frame in menus; a hidden page waits 50 ms
     * (gaius-jspi.js, awaitFrame: the pacing of the JavaScript build's per-present yield,
     * BrowserGlfw/BrowserSdlDom scheduleFrameYield). Returns at once from a JS callback.
     */
    public static void awaitAnimationFrame() {
        JspiHost.awaitFrame(presentInterval);
    }

    /**
     * Runs {@code task} from a host macrotask as soon as the browser is ready; the task has no
     * activation. Zero-delay events go through the run queue of gaius-jspi.js.
     */
    public static void postMacrotask(Runnable task) {
        EventQueue.offer(task::run);
    }

    /**
     * The WebAssembly GC roles compile without gaius.teavm.syncClinits, asyncBarrier and
     * syncMonitors, so no frame is marked as one that must not suspend.
     */
    public static boolean inNoSuspendFrame() {
        return false;
    }
}
