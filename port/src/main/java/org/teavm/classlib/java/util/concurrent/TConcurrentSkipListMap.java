package org.teavm.classlib.java.util.concurrent;

import java.io.Serializable;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Gaius' {@code ConcurrentSkipListMap} of the TeaVM builds: a TreeMap. It is not a {@code ConcurrentMap} or
 * {@code ConcurrentNavigableMap} and its iterators fail fast with ConcurrentModificationException after any
 * structural change, where the JDK's are weakly consistent.
 */
public class TConcurrentSkipListMap<K, V> extends TreeMap<K, V> implements Serializable {
    public TConcurrentSkipListMap() {
    }

    public TConcurrentSkipListMap(Comparator<? super K> comparator) {
        super(comparator);
    }

    public TConcurrentSkipListMap(Map<? extends K, ? extends V> source) {
        super(source);
    }
}
