package org.teavm.classlib.java.util.concurrent;

public class TCountDownLatch {
    private long count;

    public TCountDownLatch(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count < 0");
        }
        this.count = count;
    }

    public void await() throws InterruptedException {
        if (count != 0) {
            throw new IllegalStateException("Blocking await is unavailable on the browser thread");
        }
    }

    public boolean await(long timeout, TTimeUnit unit) throws InterruptedException {
        return count == 0;
    }

    public void countDown() {
        if (count > 0) {
            count--;
        }
    }

    public long getCount() {
        return count;
    }

    @Override
    public String toString() {
        return super.toString() + "[Count = " + count + "]";
    }
}
