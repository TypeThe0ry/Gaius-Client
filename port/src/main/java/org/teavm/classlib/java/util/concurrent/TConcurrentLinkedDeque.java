package org.teavm.classlib.java.util.concurrent;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Collection;

/**
 * Gaius' {@code ConcurrentLinkedDeque} of the TeaVM builds: TeaVM's ArrayDeque. Its iterators are not weakly
 * consistent as the JDK's are (see {@link TConcurrentLinkedQueue}): do not change the deque during a scan except
 * through {@code Iterator.remove()}.
 */
public class TConcurrentLinkedDeque<E> extends ArrayDeque<E> implements Serializable {
    public TConcurrentLinkedDeque() {
    }

    public TConcurrentLinkedDeque(Collection<? extends E> source) {
        super(source);
    }
}
