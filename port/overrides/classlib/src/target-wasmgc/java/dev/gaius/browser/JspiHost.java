package dev.gaius.browser;

import org.teavm.interop.AsyncCallback;
import org.teavm.interop.Import;
import org.teavm.interop.PlatformMarker;
import org.teavm.jso.JSBody;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.core.JSString;

/**
 * Host imports of the WebAssembly GC JSPI mode ({@code gaius.teavm.jspi}).
 *
 * <p>Every Java thread is one {@code WebAssembly.promising} activation of the
 * {@code gaius.jspi.run} export. {@link #park}, {@link #yieldNow} and {@link #frame} are
 * installed as {@code new WebAssembly.Suspending(fn)}, so a call suspends the whole wasm
 * stack of the calling activation (no coroutine transformation is involved) and the host
 * resumes it from its run queue. The other imports are plain calls. Handles are small
 * integers that the class library allocates per thread; 0 is the main thread.
 *
 * <p>Only numbers cross this boundary. A suspending import must be called from wasm code
 * whose stack reaches a promising entry without a JS frame in between: from a JS callback
 * into Java ({@code @JSFunctor}, JSO exports) V8 throws SuspendError, a JS exception that the
 * Java finally blocks on its way do not see. The host counts those callbacks
 * ({@link #callbackDepth}), so the class library refuses to block there (TJspiThreadSupport),
 * also when the callback runs synchronously inside an activation.
 */
public final class JspiHost {
    // No field with an initializer: JspiHost has no class initializer, so calls from the class
    // initialization guards (TJspiThreadSupport.clinitEnter/clinitExit) request none.
    private static Threads threads;

    private JspiHost() {
    }

    /**
     * Thread services of the class library side (java.lang.JspiThreadSupport), for code
     * outside the class library packages. Such code must not reference
     * {@code org.teavm.classlib.*} classes: TeaVM would load them a second time under their
     * own names.
     */
    public interface Threads {
        void startTask(Runnable task);

        boolean inActivation();

        AsyncCallback<Object> newResult();

        Object awaitResult(AsyncCallback<Object> result);

        void awaitFrame(int interval);
    }

    /** Called once by the class library when its JSPI support initializes. */
    public static void install(Threads services) {
        threads = services;
    }

    /**
     * Runs {@code task} later in a new activation with the identity of the current thread
     * (the JSPI counterpart of TeaVM's Fiber.start); the task may block.
     */
    public static void startTask(Runnable task) {
        services().startTask(task);
    }

    /**
     * False in a JS callback into Java, where blocking is impossible: outside any activation,
     * and in a callback that JS code makes synchronously while an activation runs.
     */
    public static boolean inActivation() {
        return services().inActivation();
    }

    /**
     * A callback for host work that completes later; {@link #awaitResult} parks the current
     * thread until it completes. It may be completed from a JS callback.
     */
    public static AsyncCallback<Object> newResult() {
        return services().newResult();
    }

    /** Parks the current thread until {@code result} completes; returns its value. */
    public static Object awaitResult(AsyncCallback<Object> result) {
        return services().awaitResult(result);
    }

    /**
     * Suspends the current thread until the browser is ready for the next frame (the frame
     * boundary of HostLoop.awaitAnimationFrame): {@code interval} is the swap interval of the
     * last present, 0 for uncapped. Returns at once in a JS callback.
     */
    public static void awaitFrame(int interval) {
        services().awaitFrame(interval);
    }

    private static Threads services() {
        Threads services = threads;
        if (services == null) {
            initializeSupport();
            services = threads;
            if (services == null) {
                throw new IllegalStateException("Gaius JSPI support is not installed");
            }
        }
        return services;
    }

    /** Any patched monitor entry initializes java.lang.JspiThreadSupport, which calls install. */
    private static void initializeSupport() {
        Object lock = new Object();
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * True when the build has the {@code gaius-jspi} platform tag. TeaVM's
     * PlatformMarkerSupport replaces each call with a constant, so the stock coroutine
     * primitives disappear from a JSPI build and the JSPI ones from every other build.
     */
    @PlatformMarker("gaius-jspi")
    public static boolean enabled() {
        return false;
    }

    /**
     * Suspends the activation of {@code handle} until {@link #unpark} or the timeout
     * (milliseconds; 0 or less waits without a timeout). Returns 1 when unparked (or when a
     * permit from an earlier unpark was pending, without suspending) and 0 on timeout.
     * Spurious returns are allowed; callers re-check their condition.
     */
    @Import(module = "gaiusJspi", name = "park")
    public static native int park(int handle, double timeoutMillis);

    /** Makes {@code handle} runnable: resumes it from the run queue, or leaves a permit. */
    @Import(module = "gaiusJspi", name = "unpark")
    public static native void unpark(int handle);

    /** Makes a specific activation generation runnable; stale queued wakes are ignored. */
    @Import(module = "gaiusJspi", name = "unpark")
    public static native void unpark(int handle, int generation);

    /**
     * Lets the other runnable activations run first. Returns at once, without suspending,
     * when the run queue is empty and the current slice of the host's run queue is not over;
     * after a slice the thread suspends even with an empty queue, so the browser gets a turn.
     * Returns 1 when the thread suspended, 0 when it did not.
     */
    @Import(module = "gaiusJspi", name = "yield")
    public static native int yieldNow(int handle);

    /**
     * Suspends the activation of {@code handle} until the browser is ready for the next frame,
     * a suspending import like {@link #park}. gaius-jspi.js (awaitFrame) decides from
     * {@code interval} and the page: after the next animation frame when the last present was
     * synchronized to the display or the game shows a menu, after one macrotask for uncapped
     * presents in a world, after 50 ms while the page is hidden.
     */
    @Import(module = "gaiusJspi", name = "awaitFrame")
    public static native int frame(int handle, int interval);

    /** {@link #clinitEvent}: an activation suspended with a class initializer on its stack. */
    public static final int CLINIT_SUSPEND = 0;
    /** {@link #clinitEvent}: an activation waited for a class that another one initializes. */
    public static final int CLINIT_WAIT = 1;
    /**
     * {@link #clinitEvent}: a JS callback into Java reached a class that an activation
     * initializes; it cannot wait and sees the class half-initialized.
     */
    public static final int CLINIT_UNWAITABLE = 2;

    /**
     * Counts a class-initialization event in the run-queue telemetry: the gaius-jspi.js
     * scheduler stats clinitSuspend, clinitWait and clinitUnwaitable, and
     * globalThis.__gaiusClinitSuspensions for CLINIT_SUSPEND, the counter of the JavaScript
     * build (patched thread.js) that the acceptance reports read.
     */
    @Import(module = "gaiusJspi", name = "clinitEvent")
    public static native void clinitEvent(int kind);

    /** Queues a new activation that runs the thread of {@code handle}. */
    @Import(module = "gaiusJspi", name = "start")
    public static native void start(int handle);

    /**
     * Arguments of the main method, set by the host before it starts the main activation
     * (TeaVM's own main export is a JS wrapper that cannot run inside an activation).
     */
    @JSBody(script = "var a = globalThis.__gaiusJspiMainArgs; return a ? a : [];")
    public static native JSArrayReader<JSString> mainArgs();

    /** The thread of {@code handle} finished; the host drops its state. */
    @Import(module = "gaiusJspi", name = "release")
    public static native void release(int handle);

    /**
     * Number of synchronous JS-to-Java calls (JSO methods, {@code @JSFunctor} callbacks,
     * exported classes) on the stack now; more than 0 means a JS frame lies between the
     * running Java code and its activation entry, so it must not suspend.
     */
    @Import(module = "gaiusJspi", name = "callbackDepth")
    public static native int callbackDepth();

    /** Name of the synchronous JS callback currently calling into Java, when known. */
    @Import(module = "gaiusJspi", name = "callbackName")
    public static native String callbackName();
}
