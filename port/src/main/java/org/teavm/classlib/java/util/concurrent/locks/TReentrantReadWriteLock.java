package org.teavm.classlib.java.util.concurrent.locks;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class TReentrantReadWriteLock implements TReadWriteLock, Serializable {
    private final boolean fair;
    private final ReadLock readLock;
    private final WriteLock writeLock;
    private int readHoldCount;
    private int writeHoldCount;

    public TReentrantReadWriteLock() {
        this(false);
    }

    public TReentrantReadWriteLock(boolean fair) {
        this.fair = fair;
        readLock = new ReadLock(this);
        writeLock = new WriteLock(this);
    }

    @Override
    public ReadLock readLock() {
        return readLock;
    }

    @Override
    public WriteLock writeLock() {
        return writeLock;
    }

    public boolean isFair() {
        return fair;
    }

    protected Thread getOwner() {
        return writeHoldCount > 0 ? Thread.currentThread() : null;
    }

    public int getReadLockCount() {
        return readHoldCount;
    }

    public boolean isWriteLocked() {
        return writeHoldCount > 0;
    }

    public boolean isWriteLockedByCurrentThread() {
        return writeHoldCount > 0;
    }

    public int getWriteHoldCount() {
        return writeHoldCount;
    }

    public int getReadHoldCount() {
        return readHoldCount;
    }

    protected Collection<Thread> getQueuedWriterThreads() {
        return Collections.emptyList();
    }

    protected Collection<Thread> getQueuedReaderThreads() {
        return Collections.emptyList();
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
        return super.toString() + "[Write locks = " + writeHoldCount
                + ", Read locks = " + readHoldCount + "]";
    }

    public static class ReadLock implements TLock, Serializable {
        private final TReentrantReadWriteLock sync;

        protected ReadLock(TReentrantReadWriteLock lock) {
            sync = lock;
        }

        @Override
        public void lock() {
            sync.readHoldCount++;
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
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            lockInterruptibly();
            return true;
        }

        @Override
        public void unlock() {
            if (sync.readHoldCount == 0) {
                throw new IllegalMonitorStateException();
            }
            sync.readHoldCount--;
        }

        @Override
        public TCondition newCondition() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return super.toString() + "[Read locks = " + sync.readHoldCount + "]";
        }
    }

    public static class WriteLock implements TLock, Serializable {
        private final TReentrantReadWriteLock sync;

        protected WriteLock(TReentrantReadWriteLock lock) {
            sync = lock;
        }

        @Override
        public void lock() {
            sync.writeHoldCount++;
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
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            lockInterruptibly();
            return true;
        }

        @Override
        public void unlock() {
            if (sync.writeHoldCount == 0) {
                throw new IllegalMonitorStateException();
            }
            sync.writeHoldCount--;
        }

        @Override
        public TCondition newCondition() {
            return new TSingleThreadCondition();
        }

        public boolean isHeldByCurrentThread() {
            return sync.writeHoldCount > 0;
        }

        public int getHoldCount() {
            return sync.writeHoldCount;
        }

        @Override
        public String toString() {
            return super.toString() + (sync.writeHoldCount > 0
                    ? "[Locked by thread " + Thread.currentThread().getName() + "]"
                    : "[Unlocked]");
        }
    }
}
