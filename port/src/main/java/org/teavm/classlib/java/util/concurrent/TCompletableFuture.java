package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import dev.gaius.browser.HostLoop;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;

public class TCompletableFuture<T> implements TFuture<T>, TCompletionStage<T> {
    private static final TExecutor DIRECT_EXECUTOR = command -> command.run();

    private final List<Runnable> listeners = new ArrayList<>();
    private final List<Thread> waiters = new ArrayList<>();
    private boolean done;
    private boolean cancelled;
    private T value;
    private Throwable failure;

    public TCompletableFuture() {
    }

    public static <U> TCompletableFuture<U> completedFuture(U value) {
        TCompletableFuture<U> future = new TCompletableFuture<>();
        future.complete(value);
        return future;
    }

    public static <U> TCompletableFuture<U> failedFuture(Throwable failure) {
        TCompletableFuture<U> future = new TCompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    public static <U> TCompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        return supplyAsync(supplier, DIRECT_EXECUTOR);
    }

    public static <U> TCompletableFuture<U> supplyAsync(Supplier<U> supplier, TExecutor executor) {
        TCompletableFuture<U> future = new TCompletableFuture<>();
        executor.execute(() -> future.capture(() -> supplier.get()));
        return future;
    }

    public static TCompletableFuture<Void> runAsync(Runnable action) {
        return runAsync(action, DIRECT_EXECUTOR);
    }

    public static TCompletableFuture<Void> runAsync(Runnable action, TExecutor executor) {
        return supplyAsync(() -> {
            action.run();
            return null;
        }, executor);
    }

    public static TCompletableFuture<Void> allOf(TCompletableFuture<?>... futures) {
        TCompletableFuture<Void> result = new TCompletableFuture<>();
        if (futures.length == 0) {
            result.complete(null);
            return result;
        }
        int[] remaining = {futures.length};
        for (TCompletableFuture<?> future : futures) {
            future.onComplete(() -> {
                if (result.done) {
                    return;
                }
                if (future.failure != null) {
                    result.completeExceptionally(future.failure);
                } else if (--remaining[0] == 0) {
                    result.complete(null);
                }
            });
        }
        return result;
    }

    public static TCompletableFuture<Object> anyOf(TCompletableFuture<?>... futures) {
        TCompletableFuture<Object> result = new TCompletableFuture<>();
        for (TCompletableFuture<?> future : futures) {
            future.onComplete(() -> {
                if (result.done) {
                    return;
                }
                if (future.failure != null) {
                    result.completeExceptionally(future.failure);
                } else {
                    result.complete(future.value);
                }
            });
        }
        return result;
    }

    public boolean complete(T value) {
        if (done) {
            return false;
        }
        this.value = value;
        finish();
        return true;
    }

    public boolean completeExceptionally(Throwable failure) {
        if (done) {
            return false;
        }
        this.failure = requireFailure(failure);
        finish();
        return true;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (done) {
            return false;
        }
        cancelled = true;
        failure = new TCancellationException();
        finish();
        return true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    public boolean isCompletedExceptionally() {
        return failure != null;
    }

    @Override
    public T get() throws TExecutionException {
        ensureDone();
        if (failure != null) {
            throw new TExecutionException(failure);
        }
        return value;
    }

    @Override
    public T get(long timeout, TTimeUnit unit) throws TExecutionException, TTimeoutException {
        if (!done && HostLoop.isWasmGc()) {
            long timeoutMillis = unit.toMillis(timeout);
            long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
            awaitDone(deadline);
        }
        if (!done) {
            throw new TTimeoutException("future is not complete");
        }
        return get();
    }

    public T join() {
        ensureDone();
        if (failure != null) {
            throw new RuntimeException(failure);
        }
        return value;
    }

    public T getNow(T fallback) {
        return done && failure == null ? value : fallback;
    }

    public int getNumberOfDependents() {
        return listeners.size();
    }

    @Override
    public <U> TCompletableFuture<U> thenApply(Function<? super T, ? extends U> function) {
        return thenApplyAsync(function, DIRECT_EXECUTOR);
    }

    @Override
    public <U> TCompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> function) {
        return thenApplyAsync(function, DIRECT_EXECUTOR);
    }

    @Override
    public <U> TCompletableFuture<U> thenApplyAsync(
            Function<? super T, ? extends U> function, TExecutor executor) {
        TCompletableFuture<U> next = new TCompletableFuture<>();
        onComplete(() -> executor.execute(() -> relay(next, () -> function.apply(value))));
        return next;
    }

    @Override
    public TCompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        return thenApply(value -> {
            action.accept(value);
            return null;
        });
    }

    @Override
    public TCompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        return thenAcceptAsync(action, DIRECT_EXECUTOR);
    }

    @Override
    public TCompletableFuture<Void> thenAcceptAsync(
            Consumer<? super T> action, TExecutor executor) {
        return thenApplyAsync(value -> {
            action.accept(value);
            return null;
        }, executor);
    }

    @Override
    public TCompletableFuture<Void> thenRun(Runnable action) {
        return thenRunAsync(action, DIRECT_EXECUTOR);
    }

    @Override
    public TCompletableFuture<Void> thenRunAsync(Runnable action) {
        return thenRunAsync(action, DIRECT_EXECUTOR);
    }

    @Override
    public TCompletableFuture<Void> thenRunAsync(Runnable action, TExecutor executor) {
        return thenApplyAsync(value -> {
            action.run();
            return null;
        }, executor);
    }

    @Override
    public <U, V> TCompletableFuture<V> thenCombine(
            TCompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> function) {
        return thenCombineAsync(other, function, DIRECT_EXECUTOR);
    }

    @Override
    public <U, V> TCompletableFuture<V> thenCombineAsync(
            TCompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> function) {
        return thenCombineAsync(other, function, DIRECT_EXECUTOR);
    }

    @Override
    public <U, V> TCompletableFuture<V> thenCombineAsync(
            TCompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> function,
            TExecutor executor) {
        return thenComposeAsync(left -> other.thenApply(right -> function.apply(left, right)), executor);
    }

    @Override
    public <U> TCompletableFuture<Void> thenAcceptBoth(
            TCompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        return thenAcceptBothAsync(other, action, DIRECT_EXECUTOR);
    }

    @Override
    public <U> TCompletableFuture<Void> thenAcceptBothAsync(
            TCompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        return thenAcceptBothAsync(other, action, DIRECT_EXECUTOR);
    }

    @Override
    public <U> TCompletableFuture<Void> thenAcceptBothAsync(
            TCompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action,
            TExecutor executor) {
        return thenCombineAsync(other, (left, right) -> {
            action.accept(left, right);
            return null;
        }, executor);
    }

    @Override
    public TCompletableFuture<Void> runAfterBoth(TCompletionStage<?> other, Runnable action) {
        return runAfterBothAsync(other, action, DIRECT_EXECUTOR);
    }

    @Override
    public TCompletableFuture<Void> runAfterBothAsync(TCompletionStage<?> other, Runnable action) {
        return runAfterBothAsync(other, action, DIRECT_EXECUTOR);
    }

    @Override
    public TCompletableFuture<Void> runAfterBothAsync(
            TCompletionStage<?> other, Runnable action, TExecutor executor) {
        return thenCombineAsync(other, (left, right) -> {
            action.run();
            return null;
        }, executor);
    }

    @Override
    public <U> TCompletableFuture<U> applyToEither(
            TCompletionStage<? extends T> other, Function<? super T, U> function) {
        return applyToEitherAsync(other, function, DIRECT_EXECUTOR);
    }

    @Override
    public <U> TCompletableFuture<U> applyToEitherAsync(
            TCompletionStage<? extends T> other, Function<? super T, U> function) {
        return applyToEitherAsync(other, function, DIRECT_EXECUTOR);
    }

    @Override
    public <U> TCompletableFuture<U> applyToEitherAsync(
            TCompletionStage<? extends T> other,
            Function<? super T, U> function,
            TExecutor executor) {
        TCompletableFuture<U> next = new TCompletableFuture<>();
        completeEither(next, this, function, executor);
        completeEither(next, other.toCompletableFuture(), function, executor);
        return next;
    }

    @Override
    public TCompletableFuture<Void> acceptEither(
            TCompletionStage<? extends T> other, Consumer<? super T> action) {
        return acceptEitherAsync(other, action, DIRECT_EXECUTOR);
    }

    @Override
    public TCompletableFuture<Void> acceptEitherAsync(
            TCompletionStage<? extends T> other, Consumer<? super T> action) {
        return acceptEitherAsync(other, action, DIRECT_EXECUTOR);
    }

    @Override
    public TCompletableFuture<Void> acceptEitherAsync(
            TCompletionStage<? extends T> other,
            Consumer<? super T> action,
            TExecutor executor) {
        return applyToEitherAsync(other, value -> {
            action.accept(value);
            return null;
        }, executor);
    }

    @Override
    public TCompletableFuture<Void> runAfterEither(TCompletionStage<?> other, Runnable action) {
        return runAfterEitherAsync(other, action, DIRECT_EXECUTOR);
    }

    @Override
    public TCompletableFuture<Void> runAfterEitherAsync(TCompletionStage<?> other, Runnable action) {
        return runAfterEitherAsync(other, action, DIRECT_EXECUTOR);
    }

    @Override
    public TCompletableFuture<Void> runAfterEitherAsync(
            TCompletionStage<?> other, Runnable action, TExecutor executor) {
        TCompletableFuture<Void> next = new TCompletableFuture<>();
        Runnable completion = () -> executor.execute(() -> next.capture(() -> {
            action.run();
            return null;
        }));
        onComplete(completion);
        other.toCompletableFuture().onComplete(completion);
        return next;
    }

    @Override
    public <U> TCompletableFuture<U> thenCompose(
            Function<? super T, ? extends TCompletionStage<U>> function) {
        return thenComposeAsync(function, DIRECT_EXECUTOR);
    }

    @Override
    public <U> TCompletableFuture<U> thenComposeAsync(
            Function<? super T, ? extends TCompletionStage<U>> function) {
        return thenComposeAsync(function, DIRECT_EXECUTOR);
    }

    @Override
    public <U> TCompletableFuture<U> thenComposeAsync(
            Function<? super T, ? extends TCompletionStage<U>> function,
            TExecutor executor) {
        TCompletableFuture<U> next = new TCompletableFuture<>();
        onComplete(() -> executor.execute(() -> {
            if (failure != null) {
                next.completeExceptionally(failure);
                return;
            }
            try {
                TCompletableFuture<U> composed = function.apply(value).toCompletableFuture();
                composed.onComplete(() -> composed.relayOutcome(next));
            } catch (Throwable throwable) {
                next.completeExceptionally(throwable);
            }
        }));
        return next;
    }

    @Override
    public <U> TCompletableFuture<U> handle(
            BiFunction<? super T, Throwable, ? extends U> function) {
        return handleAsync(function, DIRECT_EXECUTOR);
    }

    @Override
    public <U> TCompletableFuture<U> handleAsync(
            BiFunction<? super T, Throwable, ? extends U> function) {
        return handleAsync(function, DIRECT_EXECUTOR);
    }

    @Override
    public <U> TCompletableFuture<U> handleAsync(
            BiFunction<? super T, Throwable, ? extends U> function,
            TExecutor executor) {
        TCompletableFuture<U> next = new TCompletableFuture<>();
        onComplete(() -> executor.execute(() -> next.capture(() -> function.apply(value, failure))));
        return next;
    }

    @Override
    public TCompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return whenCompleteAsync(action, DIRECT_EXECUTOR);
    }

    @Override
    public TCompletableFuture<T> whenCompleteAsync(
            BiConsumer<? super T, ? super Throwable> action) {
        return whenCompleteAsync(action, DIRECT_EXECUTOR);
    }

    @Override
    public TCompletableFuture<T> whenCompleteAsync(
            BiConsumer<? super T, ? super Throwable> action, TExecutor executor) {
        TCompletableFuture<T> next = new TCompletableFuture<>();
        onComplete(() -> executor.execute(() -> {
            try {
                action.accept(value, failure);
                relayOutcome(next);
            } catch (Throwable throwable) {
                next.completeExceptionally(throwable);
            }
        }));
        return next;
    }

    @Override
    public TCompletableFuture<T> exceptionally(Function<Throwable, ? extends T> function) {
        TCompletableFuture<T> next = new TCompletableFuture<>();
        onComplete(() -> {
            if (failure == null) {
                next.complete(value);
            } else {
                next.capture(() -> function.apply(failure));
            }
        });
        return next;
    }

    public TCompletableFuture<T> exceptionallyAsync(
            Function<Throwable, ? extends T> function) {
        return exceptionallyAsync(function, DIRECT_EXECUTOR);
    }

    public TCompletableFuture<T> exceptionallyAsync(
            Function<Throwable, ? extends T> function, TExecutor executor) {
        TCompletableFuture<T> next = new TCompletableFuture<>();
        onComplete(() -> executor.execute(() -> {
            if (failure == null) {
                next.complete(value);
            } else {
                next.capture(() -> function.apply(failure));
            }
        }));
        return next;
    }

    public TCompletableFuture<T> exceptionallyComposeAsync(
            Function<Throwable, ? extends TCompletionStage<T>> function, TExecutor executor) {
        TCompletableFuture<T> next = new TCompletableFuture<>();
        onComplete(() -> executor.execute(() -> {
            if (failure == null) {
                next.complete(value);
                return;
            }
            try {
                TCompletableFuture<T> composed = function.apply(failure).toCompletableFuture();
                composed.onComplete(() -> composed.relayOutcome(next));
            } catch (Throwable throwable) {
                next.completeExceptionally(throwable);
            }
        }));
        return next;
    }

    @Override
    public TCompletableFuture<T> toCompletableFuture() {
        return this;
    }

    public TCompletableFuture<T> copy() {
        TCompletableFuture<T> copy = new TCompletableFuture<>();
        onComplete(() -> relayOutcome(copy));
        return copy;
    }

    private void finish() {
        List<Thread> pendingWaiters;
        synchronized (this) {
            done = true;
            pendingWaiters = new ArrayList<>(waiters);
            waiters.clear();
        }
        for (Thread waiter : pendingWaiters) {
            LockSupport.unpark(waiter);
        }
        List<Runnable> pending = new ArrayList<>(listeners);
        listeners.clear();
        for (Runnable listener : pending) {
            listener.run();
        }
    }

    private void onComplete(Runnable listener) {
        if (done) {
            listener.run();
        } else {
            listeners.add(listener);
        }
    }

    private void ensureDone() {
        if (!done) {
            if (!HostLoop.isWasmGc()) {
                throw new IllegalStateException("blocking on an incomplete future is unavailable");
            }
            synchronized (this) {
                if (!done) {
                    waiters.add(Thread.currentThread());
                }
            }
            while (!done) {
                LockSupport.park(this);
            }
        }
    }

    private void awaitDone(long deadline) {
        synchronized (this) {
            if (!done) {
                waiters.add(Thread.currentThread());
            }
        }
        while (!done) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                synchronized (this) {
                    waiters.remove(Thread.currentThread());
                }
                return;
            }
            LockSupport.parkNanos(this, remaining * 1_000_000L);
        }
    }

    private void capture(Supplier<? extends T> supplier) {
        try {
            complete(supplier.get());
        } catch (Throwable throwable) {
            completeExceptionally(throwable);
        }
    }

    private <U> void relay(TCompletableFuture<U> next, Supplier<? extends U> supplier) {
        if (failure != null) {
            next.completeExceptionally(failure);
        } else {
            next.capture(supplier);
        }
    }

    private <U> void relayOutcome(TCompletableFuture<U> next) {
        if (failure != null) {
            next.completeExceptionally(failure);
        } else {
            @SuppressWarnings("unchecked")
            U castValue = (U) value;
            next.complete(castValue);
        }
    }

    private static <T, U> void completeEither(
            TCompletableFuture<U> target,
            TCompletableFuture<? extends T> source,
            Function<? super T, U> function,
            TExecutor executor) {
        source.onComplete(() -> {
            if (target.done) {
                return;
            }
            executor.execute(() -> source.relay(target, () -> function.apply(source.value)));
        });
    }

    private static Throwable requireFailure(Throwable failure) {
        if (failure == null) {
            throw new NullPointerException();
        }
        return failure;
    }
}
