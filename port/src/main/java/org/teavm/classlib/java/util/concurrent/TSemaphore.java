package org.teavm.classlib.java.util.concurrent;

public class TSemaphore {
    private int permits;

    public TSemaphore(int permits) {
        this(permits, false);
    }

    public TSemaphore(int permits, boolean fair) {
        if (permits < 0) {
            throw new IllegalArgumentException("permits < 0");
        }
        this.permits = permits;
    }

    public void acquire() throws InterruptedException {
        acquire(1);
    }

    public void acquire(int count) throws InterruptedException {
        if (!tryAcquire(count)) {
            throw new IllegalStateException("Blocking semaphore acquire on browser thread");
        }
    }

    public void acquireUninterruptibly() {
        if (!tryAcquire()) {
            throw new IllegalStateException("Blocking semaphore acquire on browser thread");
        }
    }

    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    public boolean tryAcquire(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("permits < 0");
        }
        if (permits < count) {
            return false;
        }
        permits -= count;
        return true;
    }

    public boolean tryAcquire(long timeout, TTimeUnit unit) throws InterruptedException {
        return tryAcquire();
    }

    public void release() {
        release(1);
    }

    public void release(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("permits < 0");
        }
        permits += count;
    }

    public int availablePermits() {
        return permits;
    }
}
