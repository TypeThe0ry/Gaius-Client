package org.teavm.classlib.java.util.concurrent;

public class TForkJoinPool extends TScheduledThreadPoolExecutor {
    @FunctionalInterface
    public interface ForkJoinWorkerThreadFactory {
        TForkJoinWorkerThread newThread(TForkJoinPool pool);
    }

    private static final TForkJoinPool COMMON = new TForkJoinPool(1);
    private final int parallelism;

    public TForkJoinPool() {
        this(1);
    }

    public TForkJoinPool(int parallelism) {
        super(Math.max(1, parallelism));
        this.parallelism = Math.max(1, parallelism);
    }

    public TForkJoinPool(
            int parallelism,
            ForkJoinWorkerThreadFactory factory,
            Thread.UncaughtExceptionHandler handler,
            boolean asyncMode) {
        this(parallelism);
    }

    public static TForkJoinPool commonPool() {
        return COMMON;
    }

    public static int getCommonPoolParallelism() {
        return 1;
    }

    public int getParallelism() {
        return parallelism;
    }

    public int getPoolSize() {
        return 1;
    }

    public int getActiveThreadCount() {
        return 1;
    }

    public boolean isQuiescent() {
        return true;
    }

    public boolean awaitQuiescence(long timeout, TTimeUnit unit) {
        return true;
    }
}
