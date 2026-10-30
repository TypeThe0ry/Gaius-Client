package org.teavm.classlib.java.util.concurrent.locks;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class TReentrantLock implements TLock, Serializable {
    private final boolean fair;
    private int holdCount;

    public TReentrantLock() {
        this(false);
    }

    public TReentrantLock(boolean fair) {
        this.fair = fair;
    }

    @Override
    public void lock() {
        holdCount++;
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        lock();
    }

    @Override
    public boolean tryLock() {
        lock();
        return true;
    }

    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        lockInterruptibly();
        return true;
    }

    @Override
    public void unlock() {
        if (holdCount == 0) {
            throw new IllegalMonitorStateException();
        }
        holdCount--;
    }

    @Override
    public TCondition newCondition() {
        return new TSingleThreadCondition();
    }

    public int getHoldCount() {
        return holdCount;
    }

    public boolean isHeldByCurrentThread() {
        return holdCount > 0;
    }

    public boolean isLocked() {
        return holdCount > 0;
    }

    public boolean isFair() {
        return fair;
    }

    protected Thread getOwner() {
        return holdCount > 0 ? Thread.currentThread() : null;
    }

    public boolean hasQueuedThreads() {
        return false;
    }

    public boolean hasQueuedThread(Thread thread) {
        if (thread == null) {
            throw new NullPointerException();
        }
        return false;
    }

    public int getQueueLength() {
        return 0;
    }

    protected Collection<Thread> getQueuedThreads() {
        return Collections.emptyList();
    }

    public boolean hasWaiters(TCondition condition) {
        requireCondition(condition);
        return false;
    }

    public int getWaitQueueLength(TCondition condition) {
        requireCondition(condition);
        return 0;
    }

    protected Collection<Thread> getWaitingThreads(TCondition condition) {
        requireCondition(condition);
        return Collections.emptyList();
    }

    private static void requireCondition(TCondition condition) {
        if (!(condition instanceof TSingleThreadCondition)) {
            throw new IllegalArgumentException("condition was not created by this lock");
        }
    }

    @Override
    public String toString() {
        return super.toString() + (isLocked() ? "[Locked]" : "[Unlocked]");
    }
}
