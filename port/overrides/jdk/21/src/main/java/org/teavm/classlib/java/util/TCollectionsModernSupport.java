package org.teavm.classlib.java.util;

import java.util.SequencedSet;

public final class TCollectionsModernSupport {
    private TCollectionsModernSupport() {
    }

    public static <E> TSortedSet<E> unmodifiableSortedSet(TSortedSet<E> set) {
        return set;
    }

    public static <K, V> TSortedMap<K, V> unmodifiableSortedMap(TSortedMap<K, V> map) {
        return map;
    }

    public static <E> TSpliterator<E> emptySpliterator() {
        return TSpliterators.spliterator(new Object[0], 0);
    }

    public static <E> SequencedSet<E> unmodifiableSequencedSet(SequencedSet<E> set) {
        return set;
    }

    // Hash index for TeaVM's Set.of/Set.copyOf (TTemplateCollections.NElementSet) and Map.of
    // (NEtriesMap). Both keep their elements in a compacted array and answered every miss
    // with an equals() call per element; Minecraft's tag checks (Holder.Reference.is ->
    // Set.contains) are mostly misses. The patched lookups build this open-addressing table
    // of 1-based element positions on first use (TeaVMClasslibPatcher).

    private static int indexMask(int size) {
        int capacity = 4;
        while (capacity < size * 2) {
            capacity <<= 1;
        }
        return capacity - 1;
    }

    private static int spread(int hash) {
        return (hash ^ (hash >>> 16)) * 0x9E3779B1;
    }

    public static int[] setIndex(Object[] data) {
        int mask = indexMask(data.length);
        int[] table = new int[mask + 1];
        for (int i = 0; i < data.length; ++i) {
            int slot = spread(data[i].hashCode()) & mask;
            while (table[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            table[slot] = i + 1;
        }
        return table;
    }

    public static boolean setContains(Object[] data, int[] table, Object value) {
        if (value == null) {
            return false;
        }
        int mask = table.length - 1;
        int slot = spread(value.hashCode()) & mask;
        int position;
        while ((position = table[slot]) != 0) {
            Object element = data[position - 1];
            if (element == value || element.equals(value)) {
                return true;
            }
            slot = (slot + 1) & mask;
        }
        return identityIndexOf(data, value) >= 0;
    }

    // A key whose hashCode changed after the index was built is no longer found by its hash
    // (the JDK's Set.of/Map.of behave the same); TeaVM's linear scan still found it when
    // queried with the same instance, so misses keep that identity match.
    private static int identityIndexOf(Object[] data, Object value) {
        for (int i = 0; i < data.length; ++i) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    public static int[] mapIndex(TMap.Entry<?, ?>[] data) {
        int mask = indexMask(data.length);
        int[] table = new int[mask + 1];
        for (int i = 0; i < data.length; ++i) {
            int slot = spread(data[i].getKey().hashCode()) & mask;
            while (table[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            table[slot] = i + 1;
        }
        return table;
    }

    private static int mapFind(TMap.Entry<?, ?>[] data, int[] table, Object key) {
        if (key == null) {
            return -1;
        }
        int mask = table.length - 1;
        int slot = spread(key.hashCode()) & mask;
        int position;
        while ((position = table[slot]) != 0) {
            Object candidate = data[position - 1].getKey();
            if (candidate == key || candidate.equals(key)) {
                return position - 1;
            }
            slot = (slot + 1) & mask;
        }
        for (int i = 0; i < data.length; ++i) {
            if (data[i].getKey() == key) {
                return i;
            }
        }
        return -1;
    }

    public static boolean mapContainsKey(TMap.Entry<?, ?>[] data, int[] table, Object key) {
        return mapFind(data, table, key) >= 0;
    }

    public static Object mapGet(TMap.Entry<?, ?>[] data, int[] table, Object key) {
        int index = mapFind(data, table, key);
        return index < 0 ? null : data[index].getValue();
    }
}
