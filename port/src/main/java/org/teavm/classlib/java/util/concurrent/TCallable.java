package org.teavm.classlib.java.util.concurrent;

@FunctionalInterface
public interface TCallable<V> {
    V call() throws Exception;
}
