package org.teavm.classlib.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.Arrays;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

public class TAtomicIntegerArray implements Serializable {
    private final int[] values;

    public TAtomicIntegerArray(int length) {
        values = new int[length];
    }

    public TAtomicIntegerArray(int[] array) {
        values = array.clone();
    }

    public int length() {
        return values.length;
    }

    public int get(int index) {
        return values[index];
    }

    public void set(int index, int value) {
        values[index] = value;
    }

    public void lazySet(int index, int value) {
        set(index, value);
    }

    public int getAndSet(int index, int value) {
        int previous = get(index);
        set(index, value);
        return previous;
    }

    public boolean compareAndSet(int index, int expectedValue, int newValue) {
        if (values[index] != expectedValue) {
            return false;
        }
        values[index] = newValue;
        return true;
    }

    public boolean weakCompareAndSet(int index, int expectedValue, int newValue) {
        return compareAndSet(index, expectedValue, newValue);
    }

    public int getAndIncrement(int index) {
        return getAndAdd(index, 1);
    }

    public int getAndDecrement(int index) {
        return getAndAdd(index, -1);
    }

    public int getAndAdd(int index, int delta) {
        int previous = get(index);
        set(index, previous + delta);
        return previous;
    }

    public int incrementAndGet(int index) {
        return addAndGet(index, 1);
    }

    public int decrementAndGet(int index) {
        return addAndGet(index, -1);
    }

    public int addAndGet(int index, int delta) {
        int updated = get(index) + delta;
        set(index, updated);
        return updated;
    }

    public int getAndUpdate(int index, IntUnaryOperator updateFunction) {
        int previous = get(index);
        set(index, updateFunction.applyAsInt(previous));
        return previous;
    }

    public int updateAndGet(int index, IntUnaryOperator updateFunction) {
        int updated = updateFunction.applyAsInt(get(index));
        set(index, updated);
        return updated;
    }

    public int getAndAccumulate(int index, int value, IntBinaryOperator accumulatorFunction) {
        int previous = get(index);
        set(index, accumulatorFunction.applyAsInt(previous, value));
        return previous;
    }

    public int accumulateAndGet(int index, int value, IntBinaryOperator accumulatorFunction) {
        int updated = accumulatorFunction.applyAsInt(get(index), value);
        set(index, updated);
        return updated;
    }

    public int getPlain(int index) {
        return get(index);
    }

    public void setPlain(int index, int value) {
        set(index, value);
    }

    public int getOpaque(int index) {
        return get(index);
    }

    public void setOpaque(int index, int value) {
        set(index, value);
    }

    public int getAcquire(int index) {
        return get(index);
    }

    public void setRelease(int index, int value) {
        set(index, value);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
