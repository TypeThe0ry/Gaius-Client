package org.teavm.classlib.java.util.concurrent;

import java.io.Serializable;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

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
