package org.teavm.classlib.java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;

public interface TLock {
    void lock();

    void lockInterruptibly() throws InterruptedException;

    boolean tryLock();

    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;

    void unlock();

    TCondition newCondition();
}
