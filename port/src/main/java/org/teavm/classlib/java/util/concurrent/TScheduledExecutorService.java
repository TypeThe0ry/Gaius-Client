package org.teavm.classlib.java.util.concurrent;

import org.teavm.classlib.java.lang.TRunnable;

public interface TScheduledExecutorService extends TExecutorService {
    TScheduledFuture<?> schedule(TRunnable command, long delay, TTimeUnit unit);
    <V> TScheduledFuture<V> schedule(TCallable<V> callable, long delay, TTimeUnit unit);
    TScheduledFuture<?> scheduleAtFixedRate(
            TRunnable command, long initialDelay, long period, TTimeUnit unit);
    TScheduledFuture<?> scheduleWithFixedDelay(
            TRunnable command, long initialDelay, long delay, TTimeUnit unit);
}
