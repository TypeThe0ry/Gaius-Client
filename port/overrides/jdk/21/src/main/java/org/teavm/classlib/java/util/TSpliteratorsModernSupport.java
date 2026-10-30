package org.teavm.classlib.java.util;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * Spliterators.iterator as the JDK defines it: an element is ready only when tryAdvance() passed one
 * to the action, whatever tryAdvance() returns (a spliterator that returns true without an element,
 * as TeaVM 0.15's stream spliterator did at the end, must not repeat the previous element).
 */
public final class TSpliteratorsModernSupport {
    private TSpliteratorsModernSupport() {
    }

    public static <T> Iterator<T> iterator(TSpliterator<T> spliterator) {
        return new Iterator<>() {
            private T next;
            private boolean ready;
            private boolean finished;

            @Override
            public boolean hasNext() {
                if (!ready && !finished) {
                    finished = !spliterator.tryAdvance(value -> {
                        next = value;
                        ready = true;
                    });
                }
                return ready;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ready = false;
                T value = next;
                next = null;
                return value;
            }
        };
    }

    public static PrimitiveIterator.OfInt iterator(TSpliterator.OfInt spliterator) {
        return new PrimitiveIterator.OfInt() {
            private int next;
            private boolean ready;
            private boolean finished;

            @Override
            public boolean hasNext() {
                if (!ready && !finished) {
                    finished = !spliterator.tryAdvance((IntConsumer) value -> {
                        next = value;
                        ready = true;
                    });
                }
                return ready;
            }

            @Override
            public int nextInt() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ready = false;
                return next;
            }
        };
    }

    public static TSpliterator.OfDouble spliterator(
            double[] array, int fromIndex, int toIndex, int characteristics) {
        return new TSpliterator.OfDouble() {
            private int index = fromIndex;

            @Override
            public boolean tryAdvance(DoubleConsumer action) {
                if (index >= toIndex) {
                    return false;
                }
                action.accept(array[index++]);
                return true;
            }

            @Override
            public TSpliterator.OfDouble trySplit() {
                return null;
            }

            @Override
            public long estimateSize() {
                return toIndex - index;
            }

            @Override
            public int characteristics() {
                return characteristics | TSpliterator.SIZED | TSpliterator.SUBSIZED;
            }
        };
    }
}
