package org.teavm.classlib.java.util.concurrent;

import java.io.Serializable;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;

/**
 * Gaius' {@code ConcurrentLinkedQueue} of the TeaVM builds: an {@link ArrayDeque}, one cooperative thread at a
 * time. Its iterator is NOT weakly consistent as the JDK's is: removing an element other than through the
 * iterator ({@code remove(Object)}, {@code clear()}, {@code poll()} of an element that the scan did not pass) makes
 * it skip, read {@code null} behind the tail (an uncatchable trap in a build without strict null checks) or throw
 * ConcurrentModificationException at the next {@code next()} after an add or poll. Scan a snapshot
 * ({@code toArray()}) or use index loops when the loop body can change the queue
 * (WorldgenKernel263.PrefetchQueue, port/scripts/climate-handoff/jvm/.../PrefetchQueueTest).
 */
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
