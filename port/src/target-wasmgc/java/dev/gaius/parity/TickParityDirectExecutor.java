package dev.gaius.parity;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Vanilla's IO executors ({@code Util.makeIoExecutor}) in a WasmGC tick parity capture: every task
 * runs on the calling thread, which is both what the Worker's own executor does and the JVM
 * harness's choice (vanilla's zero-thread {@code MoreExecutors.newDirectExecutorService}). A task is
 * therefore complete when its {@code execute} returns. The shared {@link Gauge} counts the tasks
 * still on some thread's stack, which is only ever non-zero while such a thread is suspended inside
 * one: the settle probe's {@code ioIdle}.
 */
public final class TickParityDirectExecutor extends AbstractExecutorService {
    private final Gauge gauge;
    private volatile boolean shutdown;

    public TickParityDirectExecutor(Gauge gauge) {
        this.gauge = gauge;
    }

    @Override
    public void execute(Runnable task) {
        if (task == null) throw new NullPointerException();
        // The Worker executor's contract (TScheduledThreadPoolExecutor.execute).
        if (shutdown) throw new IllegalStateException("executor is shut down");
        gauge.enter();
        try {
            task.run();
        } finally {
            gauge.exit();
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown && gauge.idle();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }

    /** Running IO tasks of all parity IO executors. */
    public static final class Gauge {
        private int running;
        private long tasks;

        synchronized void enter() {
            running++;
            tasks++;
        }

        synchronized void exit() {
            running--;
            notifyAll();
        }

        public synchronized boolean idle() {
            return running == 0;
        }

        public synchronized long tasks() {
            return tasks;
        }

        /** Suspends until an IO task completes, or for at most {@code maxMillis}. */
        public synchronized void awaitChange(long maxMillis) {
            try {
                wait(Math.max(1L, maxMillis));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("tick parity wait interrupted", error);
            }
        }
    }
}
