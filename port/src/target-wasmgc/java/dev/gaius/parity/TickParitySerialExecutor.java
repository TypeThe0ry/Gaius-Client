package dev.gaius.parity;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Vanilla's background executor ({@code Util.makeExecutor}) in a WasmGC Worker that records a tick
 * parity capture; installed only by the TELEMETRY patch MinecraftClientPatcher.patchTickPacketParity
 * of a packet-parity debug build, and only when the Worker global {@code __gaiusTickParityDebug} is
 * true. It is the browser counterpart of the JVM harness's TickParityExecutors.
 *
 * <p>Until {@link #beginCapture} (vanilla's {@code loadLevel} has returned) every task runs at once on
 * the submitting thread, exactly as the Worker's own executor (TeaVM's TScheduledThreadPoolExecutor)
 * does, so server start-up, whose code joins futures that TeaVM cannot block on, is unchanged. During
 * the capture the executor is the schedule of TickParityExecutors: one background thread drains one
 * FIFO queue; a task that thread submits to itself runs at once on it, depth first; the pending count
 * covers queued and running tasks, a hold included; a hold parks the thread inside a task of its own
 * until released. All Java threads of the Worker take turns on one JavaScript thread (JSPI), so every
 * wait here suspends through a monitor and never spins.
 *
 * <p>After {@link #endCapture} new tasks run on the submitting thread again; tasks still queued keep
 * draining on the background thread, so vanilla's shutdown after the capture is not blocked by it.
 */
public final class TickParitySerialExecutor extends AbstractExecutorService {
    /** Deepest nesting of self-submitted tasks before the capture is refused instead of risking the Wasm stack. */
    static final int MAX_INLINE_DEPTH = 256;
    private static final long MAX_WAIT_MILLIS = 1000L;

    private final Object lock = new Object();
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final String threadName;
    private final long holdLimitNanos;
    private volatile boolean capturing;
    private volatile boolean shutdown;
    private volatile boolean broken;
    private volatile Thread thread;
    private String brokenReason;
    // Guarded by lock: queued plus running FIFO tasks.
    private int pending;
    // Background-thread state: tasks on its stack (0 while it waits for work).
    private int depth;
    private int maxDepth;
    private long queuedTasks;
    private long inlineTasks;
    private long callerTasks;
    private long completedTasks;
    private long failedTasks;
    private long holds;

    public TickParitySerialExecutor(String threadName, long holdLimitNanos) {
        this.threadName = threadName;
        this.holdLimitNanos = holdLimitNanos;
    }

    /** Starts the FIFO schedule; call on the server thread while no background work is in flight. */
    public void beginCapture() {
        synchronized (lock) {
            if (shutdown) throw new IllegalStateException("background executor shut down before the capture");
            if (capturing) throw new IllegalStateException("tick parity capture already started");
            if (thread == null) {
                Thread created = new Thread(this::loop, threadName);
                created.setDaemon(true);
                thread = created;
                created.start();
            }
            capturing = true;
        }
    }

    /** New tasks run on their submitting thread again; queued ones still drain on the background thread. */
    public void endCapture() {
        capturing = false;
    }

    public boolean capturing() {
        return capturing;
    }

    @Override
    public void execute(Runnable task) {
        if (task == null) throw new NullPointerException();
        if (!capturing) {
            // The Worker executor's contract (TScheduledThreadPoolExecutor.execute).
            if (shutdown) throw new IllegalStateException("executor is shut down");
            callerTasks++;
            task.run();
            return;
        }
        if (onBackgroundThread()) {
            runNested(task);
            return;
        }
        synchronized (lock) {
            if (shutdown) throw new RejectedExecutionException("background executor shut down");
            pending++;
            queuedTasks++;
            queue.addLast(task);
            lock.notifyAll();
        }
    }

    /**
     * The background thread running one of its tasks. A host activation that only shares the
     * thread's identity (HostLoop.startThread) while the thread waits for work does not count.
     */
    boolean onBackgroundThread() {
        return depth > 0 && Thread.currentThread() == thread;
    }

    private void runNested(Runnable task) {
        if (depth > MAX_INLINE_DEPTH) {
            fail("self-submitted background tasks nested deeper than " + MAX_INLINE_DEPTH);
            throw new IllegalStateException("tick parity background nesting limit exceeded; capture refused");
        }
        depth++;
        if (depth - 1 > maxDepth) maxDepth = depth - 1;
        inlineTasks++;
        try {
            runReporting(task);
        } finally {
            depth--;
        }
    }

    private void runReporting(Runnable task) {
        try {
            task.run();
        } catch (Throwable error) {
            // A pool worker reports a failing task and carries on; so does this thread.
            failedTasks++;
            error.printStackTrace();
        }
    }

    private void loop() {
        while (true) {
            Runnable task;
            synchronized (lock) {
                while (queue.isEmpty()) {
                    if (shutdown) {
                        lock.notifyAll();
                        return;
                    }
                    try {
                        lock.wait();
                    } catch (InterruptedException ignored) {
                        // Keep serving: the interrupt has no owner here.
                    }
                }
                task = queue.removeFirst();
            }
            depth = 1;
            try {
                runReporting(task);
            } finally {
                depth = 0;
                synchronized (lock) {
                    pending--;
                    completedTasks++;
                    lock.notifyAll();
                }
            }
        }
    }

    /** No FIFO task is queued or running, a hold included. */
    public boolean idle() {
        synchronized (lock) {
            return pending == 0;
        }
    }

    public int pending() {
        synchronized (lock) {
            return pending;
        }
    }

    /** Suspends until a FIFO task completes or is queued, or for at most {@code maxMillis}. */
    public void awaitChange(long maxMillis) {
        synchronized (lock) {
            waitOn(lock, maxMillis);
        }
    }

    /**
     * Parks the background thread inside a task of its own, queued behind the work already queued
     * (so it is entered only once that work has run); returns the release. Call from the server
     * thread during the capture.
     */
    public Runnable hold() {
        if (!capturing) throw new IllegalStateException("background hold outside the capture");
        if (onBackgroundThread()) throw new IllegalStateException("background hold requested by the background thread");
        Hold hold = new Hold();
        execute(hold);
        long deadline = System.nanoTime() + holdLimitNanos;
        synchronized (lock) {
            holds++;
            while (!hold.entered) {
                long left = deadline - System.nanoTime();
                if (left <= 0 || broken) {
                    hold.released = true;
                    lock.notifyAll();
                    fail("background hold not entered within the limit");
                    throw new IllegalStateException("background hold not entered; capture refused: " + describe());
                }
                waitOn(lock, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
            }
        }
        return hold::release;
    }

    /** A hold that outlived its limit, a refused nesting or an unentered hold: the capture is void. */
    public boolean broken() {
        return broken;
    }

    public String brokenReason() {
        synchronized (lock) {
            return brokenReason;
        }
    }

    private void fail(String reason) {
        synchronized (lock) {
            if (brokenReason == null) brokenReason = reason;
            broken = true;
            lock.notifyAll();
        }
    }

    public String describe() {
        synchronized (lock) {
            return "background capturing=" + capturing + " pending=" + pending + " queued=" + queue.size()
                    + " depth=" + depth + " broken=" + broken + (brokenReason == null ? "" : " (" + brokenReason + ")")
                    + " thread=" + threadName;
        }
    }

    /** Counters for the capture report (a JSON object body without braces). */
    public String statistics() {
        synchronized (lock) {
            return "\"thread\":\"" + threadName + "\",\"queuedTasks\":" + queuedTasks + ",\"inlineTasks\":" + inlineTasks
                    + ",\"callerTasks\":" + callerTasks + ",\"completedTasks\":" + completedTasks
                    + ",\"failedTasks\":" + failedTasks + ",\"holds\":" + holds + ",\"maxInlineDepth\":" + maxDepth
                    + ",\"pending\":" + pending + ",\"broken\":" + broken;
        }
    }

    private static void waitOn(Object monitor, long millis) {
        try {
            monitor.wait(Math.max(1L, Math.min(millis, MAX_WAIT_MILLIS)));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tick parity wait interrupted", error);
        }
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            shutdown = true;
            lock.notifyAll();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown();
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        synchronized (lock) {
            return shutdown && queue.isEmpty() && pending == 0;
        }
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (lock) {
            while (!(shutdown && queue.isEmpty() && pending == 0)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                lock.wait(Math.max(1L, Math.min(TimeUnit.NANOSECONDS.toMillis(left), MAX_WAIT_MILLIS)));
            }
            return true;
        }
    }

    private final class Hold implements Runnable {
        boolean entered;
        boolean released;

        @Override
        public void run() {
            synchronized (lock) {
                entered = true;
                lock.notifyAll();
                long deadline = System.nanoTime() + holdLimitNanos;
                while (!released) {
                    // TeaVM cannot park a Java join on an incomplete future. If vanilla
                    // submits follow-up work while the settle hold is parked (notably
                    // chunk save futures during shutdown), release the hold so the FIFO
                    // can drain that work in deterministic order instead of throwing.
                    if (!queue.isEmpty()) {
                        released = true;
                        lock.notifyAll();
                        break;
                    }
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        // The server thread waited on held work: release, and void the capture.
                        fail("background hold outlived its limit (the server thread waited on held work)");
                        return;
                    }
                    waitOn(lock, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
                }
            }
        }

        void release() {
            synchronized (lock) {
                released = true;
                lock.notifyAll();
            }
        }
    }
}
