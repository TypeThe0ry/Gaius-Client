package org.teavm.classlib.java.util.concurrent;

import org.teavm.classlib.java.lang.TRunnable;

public final class TExecutors {
    private TExecutors() {
    }

    public static TExecutorService newFixedThreadPool(int threadCount) {
        return new TScheduledThreadPoolExecutor(threadCount);
    }

    public static TExecutorService newFixedThreadPool(
            int threadCount, TThreadFactory threadFactory) {
        return new TScheduledThreadPoolExecutor(threadCount, threadFactory);
    }

    public static TExecutorService newSingleThreadExecutor() {
        return new TScheduledThreadPoolExecutor(1);
    }

    public static TExecutorService newSingleThreadExecutor(TThreadFactory threadFactory) {
        return new TScheduledThreadPoolExecutor(1, threadFactory);
    }

    public static TExecutorService newCachedThreadPool() {
        return new TScheduledThreadPoolExecutor(1);
    }

    public static TExecutorService newCachedThreadPool(TThreadFactory threadFactory) {
        return new TScheduledThreadPoolExecutor(1, threadFactory);
    }

    public static TExecutorService newWorkStealingPool() {
        return TForkJoinPool.commonPool();
    }

    public static TExecutorService newWorkStealingPool(int parallelism) {
        return new TForkJoinPool(parallelism);
    }

    public static TScheduledExecutorService newSingleThreadScheduledExecutor() {
        return new TScheduledThreadPoolExecutor(1);
    }

    public static TScheduledExecutorService newSingleThreadScheduledExecutor(
            TThreadFactory threadFactory) {
        return new TScheduledThreadPoolExecutor(1, threadFactory);
    }

    public static TScheduledExecutorService newScheduledThreadPool(int corePoolSize) {
        return new TScheduledThreadPoolExecutor(corePoolSize);
    }

    public static TScheduledExecutorService newScheduledThreadPool(
            int corePoolSize, TThreadFactory threadFactory) {
        return new TScheduledThreadPoolExecutor(corePoolSize, threadFactory);
    }

    public static TExecutorService unconfigurableExecutorService(TExecutorService executor) {
        return executor;
    }

    public static TScheduledExecutorService unconfigurableScheduledExecutorService(
            TScheduledExecutorService executor) {
        return executor;
    }

    public static TThreadFactory defaultThreadFactory() {
        return Thread::new;
    }

    public static <T> TCallable<T> callable(TRunnable task, T result) {
        return () -> {
            task.run();
            return result;
        };
    }

    public static TCallable<Object> callable(TRunnable task) {
        return callable(task, null);
    }
}
