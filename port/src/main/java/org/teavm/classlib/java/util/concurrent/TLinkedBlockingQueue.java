package org.teavm.classlib.java.util.concurrent;

import java.io.Serializable;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class TLinkedBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, Serializable {
    private final ArrayDeque<E> values = new ArrayDeque<>();
    private final int capacity;

    public TLinkedBlockingQueue() {
        this(Integer.MAX_VALUE);
    }

    public TLinkedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException();
        }
        this.capacity = capacity;
    }

    public TLinkedBlockingQueue(Collection<? extends E> source) {
        this(Math.max(1, source.size()));
        addAll(source);
    }

    @Override
    public boolean offer(E value) {
        requireValue(value);
        return values.size() < capacity && values.offer(value);
    }

    @Override
    public void put(E value) {
        if (!offer(value)) {
            throw new IllegalStateException("queue is full");
        }
    }

    @Override
    public boolean offer(E value, long timeout, TimeUnit unit) {
        return offer(value);
    }

    @Override
    public E take() throws InterruptedException {
        E value = poll();
        if (value == null) {
            throw new InterruptedException("blocking queue is empty in browser runtime");
        }
        return value;
    }

    @Override
    public E poll(long timeout, TimeUnit unit) {
        return poll();
    }

    @Override
    public E poll() {
        return values.poll();
    }

    @Override
    public E peek() {
        return values.peek();
    }

    @Override
    public int remainingCapacity() {
        return capacity - values.size();
    }

    @Override
    public int drainTo(Collection<? super E> target) {
        return drainTo(target, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super E> target, int maxElements) {
        if (target == this) {
            throw new IllegalArgumentException();
        }
        int count = 0;
        while (count < maxElements) {
            E value = poll();
            if (value == null) {
                break;
            }
            target.add(value);
            count++;
        }
        return count;
    }

    @Override
    public Iterator<E> iterator() {
        return values.iterator();
    }

    @Override
    public int size() {
        return values.size();
    }

    @Override
    public boolean remove(Object value) {
        return values.remove(value);
    }

    @Override
    public boolean contains(Object value) {
        return values.contains(value);
    }

    @Override
    public void clear() {
        values.clear();
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }
}
