package org.teavm.classlib.java.util;

/**
 * Test hook of classlib-hashmap-order.test.mjs: clone() of THashMap/THashSet without TeaVM's
 * native object copy (unavailable on the JVM). A fresh map with the source's load factor goes
 * through the same {@code becomeCloneOf} step as the field-by-field copy that clone0 makes;
 * every field that step does not reset (load factor, mode) is equal in both.
 */
public final class HashCloneProbe {
    private HashCloneProbe() {
    }

    public static <K, V> THashMap<K, V> cloneMap(THashMap<K, V> source) {
        THashMap<K, V> copy = new THashMap<>(0, source.loadFactor);
        copy.becomeCloneOf(source);
        return copy;
    }

    /** THashSet.clone0: a copy whose backing map is the backing map's clone. */
    public static <E> THashSet<E> cloneSet(THashSet<E> source) {
        return new THashSet<>(cloneMap(source.backingMap));
    }
}
