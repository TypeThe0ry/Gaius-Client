package org.teavm.classlib.java.util.concurrent;

import java.io.Serializable;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;

public class TConcurrentLinkedQueue<E> extends AbstractQueue<E> implements Serializable {
    private final ArrayDeque<E> values = new ArrayDeque<>();

    public TConcurrentLinkedQueue() {
    }

    public TConcurrentLinkedQueue(Collection<? extends E> source) {
        addAll(source);
    }

    @Override
    public boolean offer(E value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return values.offer(value);
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
}
