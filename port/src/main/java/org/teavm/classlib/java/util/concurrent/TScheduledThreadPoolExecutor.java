package org.teavm.classlib.java.util.concurrent;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import org.teavm.classlib.java.lang.TRunnable;
import dev.gaius.browser.HostLoop;

public class TScheduledThreadPoolExecutor extends TAbstractExecutorService
        implements TScheduledExecutorService {
    private boolean shutdown;
    private final BlockingQueue<TRunnable> queue = new TLinkedBlockingQueue<>();

    public TScheduledThreadPoolExecutor(int corePoolSize) {
    }

    public TScheduledThreadPoolExecutor(int corePoolSize, TThreadFactory threadFactory) {
    }

    public BlockingQueue<TRunnable> getQueue() {
        return queue;
    }

    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
    }

    @Override
    public void execute(TRunnable command) {
        if (shutdown) {
            throw new IllegalStateException("executor is shut down");
        }
        command.run();
    }

    @Override
    public TScheduledFuture<?> schedule(
            TRunnable command, long delay, TTimeUnit unit) {
        return schedule(() -> {
            command.run();
            return null;
        }, delay, unit);
    }

    @Override
    public <V> TScheduledFuture<V> schedule(
            TCallable<V> callable, long delay, TTimeUnit unit) {
        if (shutdown) {
            throw new IllegalStateException("executor is shut down");
        }
        TSimpleScheduledFuture<V> future = new TSimpleScheduledFuture<>();
        long delayMillis = unit.toMillis(Math.max(0L, delay));
        if (delay > 0L && delayMillis == 0L) {
            delayMillis = 1L;
        }
        long now = System.currentTimeMillis();
        long deadline = delayMillis > Long.MAX_VALUE - now
                ? Long.MAX_VALUE
                : now + delayMillis;
        future.setDeadlineMillis(deadline);
        scheduleChunk(future, callable, delayMillis);
        return future;
    }

    private <V> void scheduleChunk(
            TSimpleScheduledFuture<V> future,
            TCallable<V> callable,
            long remainingMillis) {
        if (remainingMillis <= 0L) {
            runScheduled(future, callable);
            return;
        }
        int chunkMillis = (int) Math.min(remainingMillis, Integer.MAX_VALUE);
        int scheduleId = HostLoop.schedule(() -> {
            future.clearScheduleId();
            if (future.isCancelled()) {
                return;
            }
            long remaining = future.remainingMillis();
            if (remaining > 0L) {
                scheduleChunk(future, callable, remaining);
            } else {
                runScheduled(future, callable);
            }
        }, chunkMillis);
        future.setScheduleId(scheduleId);
    }

    private <V> void runScheduled(
            TSimpleScheduledFuture<V> future,
            TCallable<V> callable) {
        if (future.isCancelled()) {
            return;
        }
        try {
            future.complete(callable.call());
        } catch (Throwable failure) {
            future.completeExceptionally(failure);
        }
    }

    @Override
    public TScheduledFuture<?> scheduleAtFixedRate(
            TRunnable command, long initialDelay, long period, TTimeUnit unit) {
        return schedule(command, initialDelay, unit);
    }

    @Override
    public TScheduledFuture<?> scheduleWithFixedDelay(
            TRunnable command, long initialDelay, long delay, TTimeUnit unit) {
        return schedule(command, initialDelay, unit);
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<TRunnable> shutdownNow() {
        shutdown = true;
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TTimeUnit unit) {
        return shutdown;
    }
}
