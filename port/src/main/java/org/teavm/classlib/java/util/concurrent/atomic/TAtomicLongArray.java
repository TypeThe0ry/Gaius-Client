package org.teavm.classlib.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.Arrays;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

public class TAtomicLongArray implements Serializable {
    private final long[] values;

    public TAtomicLongArray(int length) {
        values = new long[length];
    }

    public TAtomicLongArray(long[] array) {
        values = array.clone();
    }

    public int length() {
        return values.length;
    }

    public long get(int index) {
        return values[index];
    }

    public void set(int index, long value) {
        values[index] = value;
    }

    public void lazySet(int index, long value) {
        set(index, value);
    }

    public long getAndSet(int index, long value) {
        long previous = get(index);
        set(index, value);
        return previous;
    }

    public boolean compareAndSet(int index, long expectedValue, long newValue) {
        if (values[index] != expectedValue) {
            return false;
        }
        values[index] = newValue;
        return true;
    }

    public boolean weakCompareAndSet(int index, long expectedValue, long newValue) {
        return compareAndSet(index, expectedValue, newValue);
    }

    public long getAndIncrement(int index) {
        return getAndAdd(index, 1);
    }

    public long getAndDecrement(int index) {
        return getAndAdd(index, -1);
    }

    public long getAndAdd(int index, long delta) {
        long previous = get(index);
        set(index, previous + delta);
        return previous;
    }

    public long incrementAndGet(int index) {
        return addAndGet(index, 1);
    }

    public long decrementAndGet(int index) {
        return addAndGet(index, -1);
    }

    public long addAndGet(int index, long delta) {
        long updated = get(index) + delta;
        set(index, updated);
        return updated;
    }

    public long getAndUpdate(int index, LongUnaryOperator updateFunction) {
        long previous = get(index);
        set(index, updateFunction.applyAsLong(previous));
        return previous;
    }

    public long updateAndGet(int index, LongUnaryOperator updateFunction) {
        long updated = updateFunction.applyAsLong(get(index));
        set(index, updated);
        return updated;
    }

    public long getAndAccumulate(int index, long value, LongBinaryOperator accumulatorFunction) {
        long previous = get(index);
        set(index, accumulatorFunction.applyAsLong(previous, value));
        return previous;
    }

    public long accumulateAndGet(int index, long value, LongBinaryOperator accumulatorFunction) {
        long updated = accumulatorFunction.applyAsLong(get(index), value);
        set(index, updated);
        return updated;
    }

    public long getPlain(int index) {
        return get(index);
    }

    public void setPlain(int index, long value) {
        set(index, value);
    }

    public long getOpaque(int index) {
        return get(index);
    }

    public void setOpaque(int index, long value) {
        set(index, value);
    }

    public long getAcquire(int index) {
        return get(index);
    }

    public void setRelease(int index, long value) {
        set(index, value);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
