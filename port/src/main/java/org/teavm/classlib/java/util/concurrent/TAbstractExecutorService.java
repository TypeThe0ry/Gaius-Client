package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.teavm.classlib.java.lang.TRunnable;

public abstract class TAbstractExecutorService implements TExecutorService {
    @Override
    public <T> TFuture<T> submit(TCallable<T> task) {
        TCompletableFuture<T> future = new TCompletableFuture<>();
        execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    @Override
    public <T> TFuture<T> submit(TRunnable task, T result) {
        return submit(() -> {
            task.run();
            return result;
        });
    }

    @Override
    public TFuture<?> submit(TRunnable task) {
        return submit(task, null);
    }

    @Override
    public <T> List<TFuture<T>> invokeAll(Collection<? extends TCallable<T>> tasks) {
        List<TFuture<T>> futures = new ArrayList<>();
        for (TCallable<T> task : tasks) {
            futures.add(submit(task));
        }
        return futures;
    }

    @Override
    public <T> List<TFuture<T>> invokeAll(
            Collection<? extends TCallable<T>> tasks, long timeout, TTimeUnit unit) {
        return invokeAll(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends TCallable<T>> tasks)
            throws TExecutionException {
        Throwable lastFailure = null;
        for (TCallable<T> task : tasks) {
            try {
                return task.call();
            } catch (Throwable failure) {
                lastFailure = failure;
            }
        }
        throw new TExecutionException(lastFailure);
    }

    @Override
    public <T> T invokeAny(
            Collection<? extends TCallable<T>> tasks, long timeout, TTimeUnit unit)
            throws TExecutionException, TTimeoutException {
        if (tasks.isEmpty()) {
            throw new TTimeoutException("no tasks");
        }
        return invokeAny(tasks);
    }
}
