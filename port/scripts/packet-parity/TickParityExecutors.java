package dev.gaius.parity;

import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * The JVM capture host's schedule for vanilla's executors, installed only into the harness copy of
 * {@code Util.makeExecutor}/{@code makeIoExecutor} by {@code TickPacketParityPatches.main} (never
 * by the browser registration). Vanilla tasks are unchanged; only the legal order they run in is fixed.
 *
 * <p>Vanilla's background executor is a ForkJoinPool of at least one worker (max.bg.threads only
 * caps it). Its workers pick between non-empty submission queues from a per-process random origin,
 * and the IO pool runs every storage's IOWorker on its own threads, so even with one worker the
 * order in which generation steps reach shared neighbour chunks differs between processes. Cross-chunk
 * feature placement depends on that order (palette order, and even whether a mineshaft chest cart
 * finds air). Here the background executor is one thread draining one FIFO queue, the shape the
 * WasmGC Worker's executor also has, and IO runs on the calling thread, which is vanilla's own
 * zero-thread executor choice ({@code MoreExecutors.newDirectExecutorService}).
 *
 * <p>Vanilla relies on the pool compensating a worker that blocks on work it submitted itself:
 * {@code IOWorker.isOldChunkAround}, called from generation steps, joins a
 * {@code supplyAsync(..., Util.backgroundExecutor())}. A ForkJoinPool starts a spare worker, which
 * runs concurrently once the blocked one resumes. Here a task the background thread submits to
 * itself runs at once on that thread instead (depth first), so nothing blocks and nothing overlaps.
 */
public final class TickParityExecutors {
    private static final long HOLD_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static Serial background;

    private TickParityExecutors() {
    }

    public static synchronized ExecutorService background(String name) {
        if (background != null) throw new IllegalStateException("second vanilla background executor");
        background = new Serial("Worker-" + name + "-1");
        return background;
    }

    public static ExecutorService io(String name, boolean daemon) {
        return MoreExecutors.newDirectExecutorService();
    }

    static synchronized boolean installed() {
        return background != null;
    }

    /** No background task is queued or running, the hold included. */
    static boolean idle() {
        return background.pending.get() == 0;
    }

    /** Parks the background thread inside a task of its own; the returned release lets it continue. */
    static Runnable hold() {
        AtomicBoolean entered = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        background.execute(() -> {
            entered.set(true);
            long start = System.nanoTime();
            for (int spins = 0; !released.get(); spins++) {
                if (System.nanoTime() - start > HOLD_LIMIT_NANOS) {
                    background.broken = true;
                    return;
                }
                if (spins < 4000) Thread.onSpinWait();
                else LockSupport.parkNanos(20_000L);
            }
        });
        long deadline = System.nanoTime() + HOLD_LIMIT_NANOS;
        for (int spins = 0; !entered.get(); spins++) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("background hold not entered; capture refused");
            if (spins < 4000) Thread.onSpinWait();
            else LockSupport.parkNanos(20_000L);
        }
        return () -> released.set(true);
    }

    /** A hold that outlived its limit means the server thread waited on held work; the capture is void. */
    static boolean broken() {
        return background.broken;
    }

    static String describe() {
        StringBuilder text = new StringBuilder("background pending=").append(background.pending.get())
                .append(" queued=").append(background.queue.size()).append(" broken=").append(background.broken)
                .append(" thread=").append(background.thread.getState());
        for (StackTraceElement frame : background.thread.getStackTrace()) text.append("\n\tat ").append(frame);
        return text.toString();
    }

    private static final class Serial extends AbstractExecutorService {
        private static final Runnable STOP = () -> { };
        private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        private final AtomicInteger pending = new AtomicInteger();
        private final Thread thread;
        private volatile boolean shutdown;
        private volatile boolean broken;

        Serial(String name) {
            // Self-submitted tasks nest (a consecutive executor re-registers after every task), so the
            // thread reserves a deep stack.
            thread = new Thread(null, this::loop, name, 256L << 20);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void execute(Runnable task) {
            if (shutdown) throw new RejectedExecutionException("background executor shut down");
            if (Thread.currentThread() == thread) {
                runReporting(task);
                return;
            }
            pending.incrementAndGet();
            queue.add(task);
        }

        private void runReporting(Runnable task) {
            try {
                task.run();
            } catch (Throwable error) {
                // A ForkJoinPool worker would die and be replaced; the task's failure is reported the same way.
                Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
                if (handler != null) handler.uncaughtException(thread, error);
                else error.printStackTrace();
            }
        }

        private void loop() {
            while (true) {
                Runnable task;
                try {
                    task = queue.take();
                } catch (InterruptedException error) {
                    continue;
                }
                if (task == STOP) return;
                try {
                    runReporting(task);
                } finally {
                    pending.decrementAndGet();
                }
            }
        }

        @Override
        public void shutdown() {
            if (!shutdown) {
                shutdown = true;
                queue.add(STOP);
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && !thread.isAlive();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            thread.join(Math.max(1, unit.toMillis(timeout)));
            return !thread.isAlive();
        }
    }
}
