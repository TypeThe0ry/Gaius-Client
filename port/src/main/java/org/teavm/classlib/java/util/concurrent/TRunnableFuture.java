package org.teavm.classlib.java.util.concurrent;

public interface TRunnableFuture<V> extends Runnable, TFuture<V> {
    @Override
    void run();
}
