package org.teavm.classlib.java.util.concurrent;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Collection;

public class TConcurrentLinkedDeque<E> extends ArrayDeque<E> implements Serializable {
    public TConcurrentLinkedDeque() {
    }

    public TConcurrentLinkedDeque(Collection<? extends E> source) {
        super(source);
    }
}
