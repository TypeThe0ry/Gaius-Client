package org.teavm.classlib.java.util.concurrent;

import java.util.Collection;
import java.util.List;
import org.teavm.classlib.java.lang.TRunnable;

public interface TExecutorService extends TExecutor {
    void shutdown();
    List<TRunnable> shutdownNow();
    boolean isShutdown();
    boolean isTerminated();
    boolean awaitTermination(long timeout, TTimeUnit unit) throws InterruptedException;
    <T> TFuture<T> submit(TCallable<T> task);
    <T> TFuture<T> submit(TRunnable task, T result);
    TFuture<?> submit(TRunnable task);
    <T> List<TFuture<T>> invokeAll(Collection<? extends TCallable<T>> tasks)
            throws InterruptedException;
    <T> List<TFuture<T>> invokeAll(
            Collection<? extends TCallable<T>> tasks, long timeout, TTimeUnit unit)
            throws InterruptedException;
    <T> T invokeAny(Collection<? extends TCallable<T>> tasks)
            throws InterruptedException, TExecutionException;
    <T> T invokeAny(
            Collection<? extends TCallable<T>> tasks, long timeout, TTimeUnit unit)
            throws InterruptedException, TExecutionException, TTimeoutException;
}
