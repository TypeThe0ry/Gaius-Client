package org.teavm.classlib.java.util.concurrent.locks;

import java.util.Date;
import java.util.concurrent.TimeUnit;

final class TSingleThreadCondition implements TCondition {
    @Override
    public void await() throws InterruptedException {
        checkInterrupted();
    }

    @Override
    public void awaitUninterruptibly() {
    }

    @Override
    public long awaitNanos(long nanosTimeout) throws InterruptedException {
        checkInterrupted();
        return nanosTimeout;
    }

    @Override
    public boolean await(long time, TimeUnit unit) throws InterruptedException {
        checkInterrupted();
        return false;
    }

    @Override
    public boolean awaitUntil(Date deadline) throws InterruptedException {
        checkInterrupted();
        return false;
    }

    @Override
    public void signal() {
    }

    @Override
    public void signalAll() {
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }
}
