package org.teavm.classlib.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.Arrays;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

public class TAtomicReferenceArray<E> implements Serializable {
    private final Object[] values;

    public TAtomicReferenceArray(int length) {
        values = new Object[length];
    }

    public TAtomicReferenceArray(E[] array) {
        values = array.clone();
    }

    public int length() {
        return values.length;
    }

    @SuppressWarnings("unchecked")
    public E get(int index) {
        return (E) values[index];
    }

    public void set(int index, E value) {
        values[index] = value;
    }

    public void lazySet(int index, E value) {
        set(index, value);
    }

    public E getAndSet(int index, E value) {
        E previous = get(index);
        set(index, value);
        return previous;
    }

    public boolean compareAndSet(int index, E expectedValue, E newValue) {
        if (values[index] != expectedValue) {
            return false;
        }
        values[index] = newValue;
        return true;
    }

    public boolean weakCompareAndSet(int index, E expectedValue, E newValue) {
        return compareAndSet(index, expectedValue, newValue);
    }

    public E getAndUpdate(int index, UnaryOperator<E> updateFunction) {
        E previous = get(index);
        set(index, updateFunction.apply(previous));
        return previous;
    }

    public E updateAndGet(int index, UnaryOperator<E> updateFunction) {
        E updated = updateFunction.apply(get(index));
        set(index, updated);
        return updated;
    }

    public E getAndAccumulate(int index, E value, BinaryOperator<E> accumulatorFunction) {
        E previous = get(index);
        set(index, accumulatorFunction.apply(previous, value));
        return previous;
    }

    public E accumulateAndGet(int index, E value, BinaryOperator<E> accumulatorFunction) {
        E updated = accumulatorFunction.apply(get(index), value);
        set(index, updated);
        return updated;
    }

    public E getPlain(int index) {
        return get(index);
    }

    public void setPlain(int index, E value) {
        set(index, value);
    }

    public E getOpaque(int index) {
        return get(index);
    }

    public void setOpaque(int index, E value) {
        set(index, value);
    }

    public E getAcquire(int index) {
        return get(index);
    }

    public void setRelease(int index, E value) {
        set(index, value);
    }

    public E compareAndExchange(int index, E expectedValue, E newValue) {
        E witness = get(index);
        if (witness == expectedValue) {
            set(index, newValue);
        }
        return witness;
    }

    public E compareAndExchangeAcquire(int index, E expectedValue, E newValue) {
        return compareAndExchange(index, expectedValue, newValue);
    }

    public E compareAndExchangeRelease(int index, E expectedValue, E newValue) {
        return compareAndExchange(index, expectedValue, newValue);
    }

    public boolean weakCompareAndSetPlain(int index, E expectedValue, E newValue) {
        return compareAndSet(index, expectedValue, newValue);
    }

    public boolean weakCompareAndSetVolatile(int index, E expectedValue, E newValue) {
        return compareAndSet(index, expectedValue, newValue);
    }

    public boolean weakCompareAndSetAcquire(int index, E expectedValue, E newValue) {
        return compareAndSet(index, expectedValue, newValue);
    }

    public boolean weakCompareAndSetRelease(int index, E expectedValue, E newValue) {
        return compareAndSet(index, expectedValue, newValue);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
