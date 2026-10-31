/*
 *  Copyright Alexey Andreev and TeaVM contributors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Modified for Gaius: Adapted monitor and thread support to WebAssembly GC JSPI.
 */

package org.teavm.classlib.java.lang;

import dev.gaius.browser.JspiHost;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import org.teavm.interop.AsyncCallback;
import org.teavm.interop.Export;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.core.JSString;
import org.teavm.platform.PlatformRunnable;
import org.teavm.runtime.Fiber;

/**
 * Thread and monitor primitives of the WebAssembly GC JSPI mode ({@code gaius.teavm.jspi}).
 *
 * <p>In that mode TeaVM compiles no method as a coroutine (the patched AsyncMethodFinder
 * yields an empty set), so a synchronized block always renders as
 * {@code monitorEnterSync/monitorExitSync} and nothing unwinds the stack on a suspension.
 * The patched TObject and TThread entry points delegate here behind
 * {@link JspiHost#enabled()}; blocking parks the calling activation through the
 * {@link JspiHost#park} suspending import, which keeps the whole wasm stack alive until
 * the host resumes it.
 *
 * <p>Each Java thread runs as one activation of {@link #run}. Green threads still run one
 * at a time: a thread only gives up the agent at a park, a yield or its end, so plain
 * fields need no further synchronization, exactly as in the coroutine mode.
 * {@code Thread.currentThread()} is restored after every resumption.
 *
 * <p>Object.monitor is only read through TObject.gaiusJspiMonitor: the WebAssembly GC
 * backend rewrites that field only inside java.lang.Object.
 *
 * <p>A JS callback into Java (a {@code @JSFunctor}, a JSO export) cannot suspend: outside any
 * activation there is no stack to suspend, and a callback that JS code makes synchronously
 * while an activation runs has a JS frame below it (JspiHost#callbackDepth), where the
 * suspending import would throw V8's SuspendError past every Java finally block. Blocking in
 * a callback throws IllegalStateException instead, and the optional yields
 * ({@link #yieldNow}, HostLoop.yieldToEventLoop) return at once, so callbacks must only post
 * work to a thread.
 *
 * <p>An activation that stops without unwinding (a wasm trap, which a build without strict
 * checks also raises for a null dereference or an array bound) is ended by the host through
 * {@link #abandon}.
 *
 * <p>Class initialization follows the JVM (design D3): every class initializer of a JSPI build
 * starts with a guard that GaiusTeaVMOptions.wasmWrapMethodBody generates, which calls
 * {@link #clinitEnter} and, on every exit of the initializer, {@link #clinitExit}. While an
 * activation runs an initializer (it may suspend in it), another activation that triggers the
 * same class parks until the initializer ends, instead of seeing the class half-initialized
 * as TeaVM's stock code (which erases the initializer pointer before the body runs) lets it.
 */
public final class TJspiThreadSupport {
    /** Handle of the main thread; its activation runs the program's main method. */
    public static final int MAIN = 0;
    private static TThread[] threads = new TThread[16];
    /** Activation generation for each numeric handle; incremented on every allocation. */
    private static int[] activationGenerations = new int[16];
    /** Host tasks ({@link #startTask}) by handle; null for a Thread.start activation. */
    private static Runnable[] tasks = new Runnable[16];
    private static int[] freeHandles = new int[16];
    private static int freeCount;
    private static int nextHandle = 1;
    /**
     * The thread each handle waits for in LockSupport.park, by handle (null: not parked
     * there). An activation of {@link #startTask} shares the TThread of its caller, so
     * {@link #unparkThread} wakes every activation that parks for the thread.
     */
    private static TThread[] parkedThreads = new TThread[16];
    /** Handle of the activation that runs Java code now, -1 while JS code calls into Java. */
    private static int active = -1;

    // Class initializers that run now ({@link #clinitEnter}). These fields have no
    // initializers: the generated guards call clinitEnter and clinitExit directly, also before
    // this class's own static initializer has run (and for that initializer itself), so the
    // state must be valid at its default values and must never be reset by that initializer.
    /** Ids (GaiusTeaVMOptions.wasmWrapMethodBody) of the class initializers that run now. */
    private static int[] clinitIds;
    /**
     * For each entry of {@link #clinitIds}: handle plus one of the activation that runs it
     * (0: host code; -1: an abandoned activation, see {@link #clinitAbandon}).
     */
    private static int[] clinitOwners;
    private static int clinitCount;
    /** Class initializers on the stack of each activation, by handle plus one (0: host code). */
    private static int[] clinitDepths;
    /** Handles of the activations that wait in {@link #clinitEnter} for another activation. */
    private static int[] clinitWaiting;
    private static int[] clinitWaitingGenerations;
    private static int clinitWaitingCount;

    static {
        JspiHost.install(new JspiHost.Threads() {
            @Override
            public void startTask(Runnable task) {
                TJspiThreadSupport.startTask(task);
            }

            @Override
            public boolean inActivation() {
                return TJspiThreadSupport.inActivation();
            }

            @Override
            public AsyncCallback<Object> newResult() {
                return TJspiThreadSupport.newResult();
            }

            @Override
            public Object awaitResult(AsyncCallback<Object> result) {
                return TJspiThreadSupport.awaitResult(result);
            }

            @Override
            public void awaitFrame(int interval) {
                TJspiThreadSupport.awaitFrame(interval);
            }
        });
    }

    private TJspiThreadSupport() {
    }

    /** Patched {@code Thread.start}. */
    static void start(TThread thread) {
        int handle = allocate(thread);
        thread.gaiusJspiSlot = handle + 1;
        JspiHost.start(handle);
    }

    /**
     * Runs {@code task} later in a new activation that keeps the identity of the current
     * thread (as TeaVM's Fiber.start or the JavaScript build's Platform.startThread do), so
     * vanilla same-thread checks still hold. The task may block. An exception goes to the
     * thread's uncaught exception handler.
     */
    static void startTask(Runnable task) {
        int handle = allocate(TThread.currentThread());
        tasks[handle] = task;
        JspiHost.start(handle);
    }

    /**
     * True while Java code runs inside an activation and not in a synchronous JS callback
     * (JspiHost#callbackDepth), so blocking is possible.
     */
    static boolean inActivation() {
        return current() >= 0;
    }

    /** The handle of the activation that may suspend now, or -1. */
    private static int current() {
        int handle = active;
        if (handle < 0 || JspiHost.callbackDepth() != 0) {
            return -1;
        }
        return handle;
    }

    private static int allocate(TThread thread) {
        int handle;
        if (freeCount > 0) {
            handle = freeHandles[--freeCount];
        } else {
            handle = nextHandle++;
            if (handle >= threads.length) {
                TThread[] grown = new TThread[threads.length * 2];
                System.arraycopy(threads, 0, grown, 0, threads.length);
                threads = grown;
                int[] grownGenerations = new int[grown.length];
                System.arraycopy(activationGenerations, 0, grownGenerations, 0,
                        activationGenerations.length);
                activationGenerations = grownGenerations;
                Runnable[] grownTasks = new Runnable[grown.length];
                System.arraycopy(tasks, 0, grownTasks, 0, tasks.length);
                tasks = grownTasks;
                TThread[] grownParked = new TThread[grown.length];
                System.arraycopy(parkedThreads, 0, grownParked, 0, parkedThreads.length);
                parkedThreads = grownParked;
            }
        }
        activationGenerations[handle]++;
        threads[handle] = thread;
        return handle;
    }

    /**
     * Entry point of an activation, wrapped by the host in {@code WebAssembly.promising}.
     * The host runs the main thread with {@link #MAIN} instead of TeaVM's {@code main} export.
     */
    @Export(name = "gaius.jspi.run")
    public static void run(int handle) {
        if (handle == MAIN) {
            TThread main = TThread.getMainThread();
            main.gaiusJspiSlot = MAIN + 1;
            threads[MAIN] = main;
            activationGenerations[MAIN]++;
            active = MAIN;
            TThread.setCurrentThread(main);
            try {
                Fiber.runMain(mainArgs());
            } finally {
                threads[MAIN] = null;
                main.gaiusJspiSlot = 0;
                active = -1;
            }
            return;
        }
        TThread thread = threads[handle];
        Runnable task = tasks[handle];
        active = handle;
        TThread.setCurrentThread(thread);
        try {
            if (task == null) {
                thread.runThread();
            } else {
                try {
                    task.run();
                } catch (Throwable e) {
                    thread.getUncaughtExceptionHandler().uncaughtException(thread, e);
                }
            }
        } finally {
            threads[handle] = null;
            tasks[handle] = null;
            freeHandles = ensure(freeHandles, freeCount + 1);
            freeHandles[freeCount++] = handle;
            if (task == null) {
                thread.gaiusJspiSlot = 0;
            }
            active = -1;
            JspiHost.release(handle);
        }
    }

    /**
     * Ends the activation of {@code handle} after it stopped without running the finally block
     * of {@link #run}: a wasm trap (a null dereference or an array bound in a build without
     * strict checks, a stack overflow, an illegal cast) or a JS exception. The host
     * (gaius-jspi.js) calls it from the rejection of the activation, outside any activation.
     * The slot is released as in run's finally; a thread started with Thread.start ends as if
     * its run method had thrown: isAlive() turns false, join() returns and the uncaught
     * exception handler sees an Error. Monitors the activation held stay held, since nothing
     * unwound its synchronized blocks.
     */
    @Export(name = "gaius.jspi.abandon")
    public static void abandon(int handle) {
        if (active == handle) {
            active = -1;
        }
        if (handle == MAIN) {
            // Its failure reaches the boot through startMain; threads that wait for a class it
            // was initializing go on with the class as it is.
            clinitAbandon(handle);
            threads[MAIN] = null;
            TThread.getMainThread().gaiusJspiSlot = 0;
            return;
        }
        if (handle < MAIN || handle >= threads.length || threads[handle] == null) {
            // A slot that run's finally already released (and may have handed out again).
            return;
        }
        clinitAbandon(handle);
        TThread thread = threads[handle];
        Runnable task = tasks[handle];
        threads[handle] = null;
        tasks[handle] = null;
        parkedThreads[handle] = null;
        freeHandles = ensure(freeHandles, freeCount + 1);
        freeHandles[freeCount++] = handle;
        JspiHost.release(handle);
        TThread.setCurrentThread(TThread.getMainThread());
        if (task == null) {
            // What TThread.runThread's finally does for a thread whose run method threw.
            thread.gaiusJspiSlot = 0;
            thread.alive = false;
            TThread.activeCount--;
            try {
                synchronized (thread.finishedLock) {
                    thread.finishedLock.notifyAll();
                }
            } catch (IllegalStateException contended) {
                // A suspended thread holds the lock: its joiners wake when their timeout ends.
            }
        }
        try {
            String name = thread.getName() != null ? thread.getName() : "#" + thread.getId();
            thread.getUncaughtExceptionHandler().uncaughtException(thread, new Error("Gaius JSPI: "
                    + (task == null ? "thread " : "a host task of thread ") + name
                    + " stopped without unwinding (a wasm trap); see the browser console"));
        } catch (Throwable ignored) {
            // The handler must not keep the host from releasing the activation.
        }
    }

    /** The arguments the host passed to startMain (GaiusJspi), as TeaVM's main export would. */
    private static String[] mainArgs() {
        JSArrayReader<JSString> host = JspiHost.mainArgs();
        String[] args = new String[host.getLength()];
        for (int i = 0; i < args.length; i++) {
            args[i] = host.get(i).stringValue();
        }
        return args;
    }

    private static int[] ensure(int[] array, int size) {
        if (size <= array.length) {
            return array;
        }
        int[] grown = new int[Math.max(size, array.length * 2)];
        System.arraycopy(array, 0, grown, 0, array.length);
        return grown;
    }

    private static int activeHandle(String what) {
        int handle = current();
        if (handle < 0) {
            IllegalStateException failure = new IllegalStateException("Gaius JSPI: " + what + " outside a Java thread"
                    + " activation (a JS callback into Java cannot suspend; post the work to a"
                    + " thread instead)");
            failure.printStackTrace();
            throw failure;
        }
        return handle;
    }

    private static int activationGeneration(int handle) {
        return handle >= 0 && handle < activationGenerations.length ? activationGenerations[handle] : 0;
    }

    /**
     * Parks the current activation until {@link JspiHost#unpark} or the timeout (0 or less:
     * none) and restores {@code Thread.currentThread()}. May return spuriously. Only called
     * with the handle of {@link #current}: a JS exception from the import (SuspendError under
     * a JS frame) would skip the finally block.
     */
    private static boolean park(int handle, double timeoutMillis) {
        countClinitSuspend(handle);
        TThread thread = TThread.currentThread();
        active = -1;
        int result;
        try {
            result = JspiHost.park(handle, timeoutMillis);
        } finally {
            active = handle;
            TThread.setCurrentThread(thread);
        }
        return result != 0;
    }

    /**
     * Lets the other runnable threads run, and the browser once the host's run-queue slice is
     * over (gaius-jspi.js); returns at once in a JS callback, where there is nothing to yield.
     */
    public static void yieldNow() {
        int handle = current();
        if (handle < 0) {
            return;
        }
        TThread thread = TThread.currentThread();
        active = -1;
        int suspended;
        try {
            suspended = JspiHost.yieldNow(handle);
        } finally {
            active = handle;
            TThread.setCurrentThread(thread);
        }
        if (suspended != 0) {
            countClinitSuspend(handle);
        }
    }

    /**
     * HostLoop.awaitAnimationFrame (design D4): suspends the current thread until the browser
     * is ready for the next frame; {@code interval} is the swap interval of the last present.
     * Returns at once in a JS callback, which has no thread to suspend.
     */
    static void awaitFrame(int interval) {
        int handle = current();
        if (handle < 0) {
            return;
        }
        countClinitSuspend(handle);
        TThread thread = TThread.currentThread();
        active = -1;
        try {
            JspiHost.frame(handle, interval);
        } finally {
            active = handle;
            TThread.setCurrentThread(thread);
        }
    }

    /**
     * Patched {@code Thread.join(long, int)} (join(long) and join() call it): the JVM's loop,
     * which waits on the thread's finishedLock while the thread is alive and re-checks after
     * every wake-up, so a spurious or foreign notify and a timed wait that ends early do not
     * end the join. TeaVM's stock join waits once, outside any loop, and treats a thread that
     * was never started as alive (TThread sets alive in its constructor); here a thread is alive
     * from start() until runThread ends, as on the JVM. A join on the current thread returns at
     * once, as the stock join does.
     */
    static void join(TThread thread, long millis, int nanos) throws InterruptedException {
        if (millis < 0 || nanos < 0 || nanos > 999999) {
            throw new TIllegalArgumentException();
        }
        if (thread == TThread.currentThread()) {
            return;
        }
        if (nanos > 0 && millis < Long.MAX_VALUE) {
            millis++;
        }
        Object lock = thread.finishedLock;
        synchronized (lock) {
            if (millis == 0) {
                while (startedAndAlive(thread)) {
                    lock.wait(0);
                }
            } else {
                double deadline = (double) System.currentTimeMillis() + millis;
                while (startedAndAlive(thread)) {
                    double left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    lock.wait(left >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(left));
                }
            }
        }
    }

    /** Started (it has an activation slot: TThread.gaiusJspiSlot) and runThread has not ended. */
    private static boolean startedAndAlive(TThread thread) {
        return thread.alive && thread.gaiusJspiSlot > 0;
    }

    /**
     * Start of every class initializer of a JSPI build (the guard that
     * GaiusTeaVMOptions.wasmWrapMethodBody generates; {@code id} numbers the class). Returns true
     * when the caller must run the initializer body: no activation runs it yet, so the current
     * one is now its initializing thread. Returns false when the body must be skipped: the
     * current activation already runs it (a recursive request, as on the JVM), or another
     * activation ran it while this one waited here, as the JVM's class initialization lock
     * makes it wait. A JS callback cannot wait: it goes on with the class as it is, as TeaVM's
     * stock code does for every caller, and the case is counted
     * ({@link JspiHost#CLINIT_UNWAITABLE}).
     *
     * <p>The guard calls this without initializing this class (it may run before this class's
     * own static initializer, and for it), so the path to the first return touches nothing but
     * the clinit fields, which are valid at their default values.
     *
     * <p>Exported only so that the dependency analysis keeps it (and its body, which TeaVM
     * would otherwise drop once it inlined the only call site) for the generated guards. TeaVM
     * starts an exported method with the initialization of its class; this class's own
     * initializer is therefore not guarded (GaiusTeaVMOptions.wasmWrapMethodBody), it runs
     * before any thread exists, and the guard of every other class reaches it as already
     * initialized.
     */
    @Export(name = "gaius.jspi.clinitEnter")
    public static boolean clinitEnter(int id) {
        int owner = active + 1;
        int[] ids = clinitIds;
        int count = clinitCount;
        for (int i = count - 1; i >= 0; i--) {
            if (ids[i] == id) {
                return clinitBusy(id, clinitOwners[i], owner);
            }
        }
        if (ids == null || count == ids.length) {
            int size = ids == null ? 16 : ids.length * 2;
            int[] grownIds = new int[size];
            int[] grownOwners = new int[size];
            for (int i = 0; i < count; i++) {
                grownIds[i] = ids[i];
                grownOwners[i] = clinitOwners[i];
            }
            clinitIds = grownIds;
            clinitOwners = grownOwners;
            ids = grownIds;
        }
        ids[count] = id;
        clinitOwners[count] = owner;
        clinitCount = count + 1;
        int[] depths = clinitDepths;
        if (depths == null || owner >= depths.length) {
            // No library calls on this path (Math.max would request Math's initializer).
            int size = depths == null ? 16 : depths.length * 2;
            if (size <= owner) {
                size = owner + 1;
            }
            int[] grown = new int[size];
            if (depths != null) {
                for (int i = 0; i < depths.length; i++) {
                    grown[i] = depths[i];
                }
            }
            clinitDepths = grown;
            depths = grown;
        }
        depths[owner]++;
        return true;
    }

    /**
     * End of every class initializer that {@link #clinitEnter} let run, on its normal and its
     * exceptional exit (the guard erases TeaVM's initializer pointer right before, so later
     * requests skip the initializer): the class is initialized, the activations that wait for
     * it resume. Exported for the same reason as clinitEnter.
     */
    @Export(name = "gaius.jspi.clinitExit")
    public static void clinitExit(int id) {
        int[] ids = clinitIds;
        int count = clinitCount;
        for (int i = count - 1; i >= 0; i--) {
            if (ids[i] != id) {
                continue;
            }
            int owner = clinitOwners[i];
            for (int j = i + 1; j < count; j++) {
                ids[j - 1] = ids[j];
                clinitOwners[j - 1] = clinitOwners[j];
            }
            clinitCount = count - 1;
            int[] depths = clinitDepths;
            if (owner >= 0 && depths != null && owner < depths.length && depths[owner] > 0) {
                depths[owner]--;
            }
            if (clinitWaitingCount > 0) {
                for (int k = 0; k < clinitWaitingCount; k++) {
                    JspiHost.unpark(clinitWaiting[k], clinitWaitingGenerations[k]);
                }
            }
            return;
        }
    }

    /**
     * The class initializer {@code id} runs already ({@code initOwner}): returns false after
     * waiting for it when another activation runs it. Host code (owner 0) cannot suspend, so a
     * host entry that is still there was cut short by a trap, as are the entries of an
     * abandoned activation (-1): nobody will end them, and the caller goes on.
     */
    private static boolean clinitBusy(int id, int initOwner, int owner) {
        if (initOwner == owner || initOwner <= 0) {
            return false;
        }
        int handle = current();
        if (handle < 0) {
            JspiHost.clinitEvent(JspiHost.CLINIT_UNWAITABLE);
            return false;
        }
        JspiHost.clinitEvent(JspiHost.CLINIT_WAIT);
        int generation = activationGeneration(handle);
        int[] waiting = clinitWaiting;
        if (waiting == null || clinitWaitingCount == waiting.length) {
            int[] grown = new int[waiting == null ? 8 : waiting.length * 2];
            int[] grownGenerations = new int[grown.length];
            for (int i = 0; i < clinitWaitingCount; i++) {
                grown[i] = waiting[i];
                grownGenerations[i] = clinitWaitingGenerations[i];
            }
            clinitWaiting = grown;
            clinitWaitingGenerations = grownGenerations;
        }
        clinitWaiting[clinitWaitingCount] = handle;
        clinitWaitingGenerations[clinitWaitingCount++] = generation;
        try {
            while (clinitRunning(id)) {
                park(handle, 0);
            }
        } finally {
            for (int i = 0; i < clinitWaitingCount; i++) {
                if (clinitWaiting[i] == handle && clinitWaitingGenerations[i] == generation) {
                    int last = --clinitWaitingCount;
                    clinitWaiting[i] = clinitWaiting[last];
                    clinitWaitingGenerations[i] = clinitWaitingGenerations[last];
                    break;
                }
            }
        }
        return false;
    }

    /** True while an activation that can still end it runs the class initializer {@code id}. */
    private static boolean clinitRunning(int id) {
        for (int i = clinitCount - 1; i >= 0; i--) {
            if (clinitIds[i] == id) {
                return clinitOwners[i] > 0;
            }
        }
        return false;
    }

    /**
     * The activation of {@code handle} stopped without unwinding ({@link #abandon}): the class
     * initializers it was running never end. Their entries stay, marked as abandoned, so later
     * requests skip the initializers (as after TeaVM's stock erase) instead of running them a
     * second time, and the activations that wait for them go on.
     */
    private static void clinitAbandon(int handle) {
        int owner = handle + 1;
        boolean changed = false;
        for (int i = 0; i < clinitCount; i++) {
            if (clinitOwners[i] == owner) {
                clinitOwners[i] = -1;
                changed = true;
            }
        }
        if (clinitDepths != null && owner < clinitDepths.length) {
            clinitDepths[owner] = 0;
        }
        if (changed) {
            for (int k = 0; k < clinitWaitingCount; k++) {
                JspiHost.unpark(clinitWaiting[k], clinitWaitingGenerations[k]);
            }
        }
    }

    /** Counts a suspension of {@code handle} with a class initializer on its stack (telemetry). */
    private static void countClinitSuspend(int handle) {
        int[] depths = clinitDepths;
        if (depths != null && handle + 1 < depths.length && depths[handle + 1] > 0) {
            JspiHost.clinitEvent(JspiHost.CLINIT_SUSPEND);
        }
    }

    /** Patched {@code Thread.sleep(long)}. */
    static void sleep(long millis) throws TInterruptedException {
        int handle = activeHandle("Thread.sleep");
        TThread thread = TThread.currentThread();
        if (TThread.interrupted()) {
            throw new TInterruptedException();
        }
        if (millis <= 0) {
            yieldNow();
            return;
        }
        Wakeup wakeup = new Wakeup(handle, activationGeneration(handle));
        thread.interruptHandler = wakeup;
        double deadline = (double) System.currentTimeMillis() + millis;
        try {
            while (!wakeup.interrupted) {
                double left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                park(handle, left);
            }
        } finally {
            if (thread.interruptHandler == wakeup) {
                thread.interruptHandler = null;
            }
        }
        if (wakeup.interrupted) {
            TThread.interrupted();
            throw new TInterruptedException();
        }
    }

    /**
     * Patched {@code monitorEnterSync} and {@code monitorEnter}. The uncontended cases stay
     * small enough to inline; a contended entry parks in {@link #monitorEnterContended}.
     */
    static void monitorEnter(TObject o, int count) {
        TThread current = TThread.currentThread();
        TObject.Monitor monitor = TObject.gaiusJspiMonitor(o);
        if (monitor == null) {
            TObject.createMonitor(o);
            monitor = TObject.gaiusJspiMonitor(o);
            monitor.owner = current;
            monitor.count = count;
        } else if (monitor.owner == current) {
            monitor.count += count;
        } else if (monitor.owner == null) {
            monitor.owner = current;
            monitor.count += count;
        } else {
            monitorEnterContended(o, monitor, current, count);
        }
    }

    private static void monitorEnterContended(TObject o, TObject.Monitor monitor, TThread current,
            int count) {
        int handle = current();
        if (handle < 0) {
            // Same message as TeaVM's synchronous monitor entry.
            throw new IllegalStateException("Can't enter monitor from another thread synchronously");
        }
        if (monitor.enteringThreads == null) {
            monitor.enteringThreads = new ArrayDeque<>();
        }
        monitor.enteringThreads.add(new Entering(current, handle, activationGeneration(handle)));
        // monitorExit hands the monitor over (owner set) before it unparks this thread.
        while (TObject.gaiusJspiMonitor(o).owner != current) {
            park(handle, 0);
        }
        TObject.gaiusJspiMonitor(o).count += count;
    }

    /** Patched {@code monitorExitSync} and {@code monitorExit}: FIFO hand-over. */
    static void monitorExit(TObject o, int count) {
        TObject.Monitor monitor = TObject.gaiusJspiMonitor(o);
        if (monitor == null || monitor.owner != TThread.currentThread()) {
            throw new TIllegalMonitorStateException();
        }
        monitor.count -= count;
        if (monitor.count > 0) {
            return;
        }
        monitor.count = 0;
        monitor.owner = null;
        Queue<PlatformRunnable> entering = monitor.enteringThreads;
        if (entering == null || entering.isEmpty()) {
            o.isEmptyMonitor();
        } else {
            handOver(monitor, entering);
        }
    }

    private static void handOver(TObject.Monitor monitor, Queue<PlatformRunnable> entering) {
        // An activation can abandon while it is queued for a monitor. Drop entries whose
        // numeric slot was released or reused; handing ownership to one would deadlock the
        // monitor behind a thread that can no longer resume.
        while (!entering.isEmpty()) {
            Entering next = (Entering) entering.remove();
            if (next.handle >= MAIN && next.handle < threads.length
                    && threads[next.handle] == next.thread
                    && activationGenerations[next.handle] == next.generation) {
                monitor.owner = next.thread;
                JspiHost.unpark(next.handle, next.generation);
                return;
            }
        }
        monitor.owner = null;
    }

    /** Patched {@code Object.wait(long, int)}. */
    static void waitOn(TObject o, long timeout, int nanos) throws TInterruptedException {
        if (!TObject.holdsLock(o)) {
            throw new TIllegalMonitorStateException();
        }
        if (timeout < 0 || nanos < 0 || nanos > 999999) {
            throw new TIllegalArgumentException();
        }
        int handle = activeHandle("Object.wait");
        TThread current = TThread.currentThread();
        if (TThread.interrupted()) {
            throw new TInterruptedException();
        }
        TObject.Monitor monitor = TObject.gaiusJspiMonitor(o);
        Waiter waiter = new Waiter(handle);
        if (monitor.notifyListeners == null) {
            monitor.notifyListeners = new ArrayDeque<>();
        }
        monitor.notifyListeners.add(waiter);
        current.interruptHandler = waiter;
        int count = monitor.count;
        monitorExit(o, count);
        double deadline = timeout > 0 || nanos > 0
                ? (double) System.currentTimeMillis() + timeout + (nanos > 0 ? 1 : 0) : -1;
        while (!waiter.notified && !waiter.interrupted) {
            double left = 0;
            if (deadline >= 0) {
                left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
            }
            park(handle, left);
        }
        waiter.done = true;
        if (!waiter.notified) {
            // Timed out or interrupted: leave the wait set (notify already removed it).
            removeWaiter(o, waiter);
        }
        if (current.interruptHandler == waiter) {
            current.interruptHandler = null;
        }
        monitorEnter(o, count);
        if (waiter.interrupted) {
            TThread.interrupted();
            throw new TInterruptedException();
        }
    }

    private static void removeWaiter(TObject o, Waiter waiter) {
        TObject.Monitor monitor = TObject.gaiusJspiMonitor(o);
        if (monitor == null || monitor.notifyListeners == null) {
            return;
        }
        for (Iterator<TObject.NotifyListener> it = monitor.notifyListeners.iterator(); it.hasNext();) {
            if (it.next() == waiter) {
                it.remove();
                break;
            }
        }
        if (monitor.notifyListeners.isEmpty()) {
            monitor.notifyListeners = null;
        }
    }

    /** Patched {@code Object.notify()} and {@code Object.notifyAll()}. */
    static void notify(TObject o, boolean all) {
        if (!TObject.holdsLock(o)) {
            throw new TIllegalMonitorStateException();
        }
        Queue<TObject.NotifyListener> listeners = TObject.gaiusJspiMonitor(o).notifyListeners;
        if (listeners == null) {
            return;
        }
        while (!listeners.isEmpty()) {
            TObject.NotifyListener listener = listeners.remove();
            if (!listener.expired()) {
                listener.run();
                if (!all) {
                    break;
                }
            }
        }
        if (listeners.isEmpty()) {
            TObject.gaiusJspiMonitor(o).notifyListeners = null;
        }
    }

    /**
     * A callback for host work that completes later (a TeaVM {@code @Async} implementation, a
     * JS event): {@link #awaitResult} parks the current thread until it completes. The
     * callback may be completed from anywhere, including a JS callback outside any
     * activation, and before awaitResult runs.
     */
    public static AsyncCallback<Object> newResult() {
        int handle = activeHandle("waiting for the host");
        return new AsyncResult(handle, activationGeneration(handle));
    }

    /**
     * Parks the current thread until {@code result} (from {@link #newResult}) completes, then
     * returns its value or throws its error. This is how a JSPI build runs TeaVM's
     * {@code @Async} methods (TeaVMClasslibJspiPatcher) without coroutines.
     */
    public static Object awaitResult(AsyncCallback<Object> result) {
        AsyncResult pending = (AsyncResult) result;
        if (!pending.done && activeHandle("waiting for the host") != pending.handle) {
            throw new IllegalStateException("Gaius JSPI: a host result awaited by another thread");
        }
        while (!pending.done) {
            pending.waiting = true;
            try {
                park(pending.handle, 0);
            } finally {
                pending.waiting = false;
            }
        }
        if (pending.error != null) {
            TJspiThreadSupport.<RuntimeException>throwUnchecked(pending.error);
        }
        return pending.value;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable error) throws T {
        throw (T) error;
    }

    /** {@code LockSupport.park}: returns on unpark, interrupt or spuriously. */
    public static void parkCurrent(double timeoutMillis) {
        int handle = activeHandle("LockSupport.park");
        TThread thread = TThread.currentThread();
        if (thread.isInterrupted()) {
            return;
        }
        Wakeup wakeup = new Wakeup(handle, activationGeneration(handle));
        thread.interruptHandler = wakeup;
        parkedThreads[handle] = thread;
        try {
            park(handle, timeoutMillis);
        } finally {
            parkedThreads[handle] = null;
            if (thread.interruptHandler == wakeup) {
                thread.interruptHandler = null;
            }
        }
    }

    /**
     * {@code LockSupport.unpark} (the argument is a java.lang.Thread, that is a TThread). Wakes
     * every activation that parks for the thread, including {@link #startTask} activations
     * that share its identity (park may return spuriously, so waking more than one is safe).
     * When none parks, the permit goes to the thread's own activation: TThread.gaiusJspiSlot is
     * its handle plus one (0: not started or finished, and a permit for such a thread is
     * dropped, as on the JVM).
     */
    public static void unparkThread(Object thread) {
        TThread target = (TThread) thread;
        if (target == null) {
            return;
        }
        boolean woken = false;
        TThread[] parked = parkedThreads;
        for (int handle = 0; handle < parked.length; handle++) {
            if (parked[handle] == target) {
                JspiHost.unpark(handle, activationGeneration(handle));
                woken = true;
            }
        }
        if (!woken && target.gaiusJspiSlot > 0) {
            int handle = target.gaiusJspiSlot - 1;
            JspiHost.unpark(handle, activationGeneration(handle));
        }
    }

    static final class AsyncResult implements AsyncCallback<Object> {
        final int handle;
        final int generation;
        boolean done;
        /** True while awaitResult parks, so a completion before that leaves no permit. */
        boolean waiting;
        Object value;
        Throwable error;

        AsyncResult(int handle, int generation) {
            this.handle = handle;
            this.generation = generation;
        }

        @Override
        public void complete(Object result) {
            if (done) {
                return;
            }
            done = true;
            value = result;
            if (waiting) {
                JspiHost.unpark(handle, generation);
            }
        }

        @Override
        public void error(Throwable e) {
            if (done) {
                return;
            }
            done = true;
            error = e;
            if (waiting) {
                JspiHost.unpark(handle, generation);
            }
        }
    }

    static final class Entering implements PlatformRunnable {
        final TThread thread;
        final int handle;
        final int generation;

        Entering(TThread thread, int handle, int generation) {
            this.thread = thread;
            this.handle = handle;
            this.generation = generation;
        }

        @Override
        public void run() {
            JspiHost.unpark(handle, generation);
        }
    }

    static final class Wakeup implements TThreadInterruptHandler {
        final int handle;
        final int generation;
        boolean interrupted;

        Wakeup(int handle, int generation) {
            this.handle = handle;
            this.generation = generation;
        }

        @Override
        public void interrupted() {
            interrupted = true;
            JspiHost.unpark(handle, generation);
        }
    }

    static final class Waiter implements TObject.NotifyListener, TThreadInterruptHandler {
        final int handle;
        final int generation;
        boolean done;
        boolean notified;
        boolean interrupted;

        Waiter(int handle) {
            this.handle = handle;
            this.generation = activationGeneration(handle);
        }

        @Override
        public boolean expired() {
            boolean result = done;
            done = true;
            return result;
        }

        /** Notified: the caller already marked this waiter done through expired(). */
        @Override
        public void run() {
            notified = true;
            JspiHost.unpark(handle, generation);
        }

        @Override
        public void interrupted() {
            if (!done) {
                done = true;
                interrupted = true;
                JspiHost.unpark(handle, generation);
            }
        }
    }
}
