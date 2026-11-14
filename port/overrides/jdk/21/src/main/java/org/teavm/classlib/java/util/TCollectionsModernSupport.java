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

    // Map.of / Map.ofEntries / Map.copyOf (NEtriesMap.toEntryArray, TeaVMClasslibPatcher): TeaVM
    // puts the n entries into an n-slot array, each at the first free slot from |hash| % n
    // (wrapping once), and throws IllegalArgumentException when a slot it passes holds an equal
    // key. The slot order is the map's iteration order, so it must not change. At 100% load the
    // probe runs grow long, and every step calls getKey() twice and equals() once.
    //
    // placeKnownEntries finds the same first free slot with a next-free table when every entry is
    // one of the class library's own entry classes (plain getKey/getValue) and every key a string
    // or a boxed primitive (equals and hashCode pure and consistent), and the input has no null,
    // no hash whose |hash| is negative and no equal keys. Then TeaVM's body would place the entries
    // exactly there and never throw: an equal key earlier in the table has the same start slot,
    // and every slot from there to it is still occupied, so the probe run reaches it. For any
    // other input it returns null and probeEntries, a transcript of TeaVM's body, runs instead,
    // with the same calls, slots and exceptions.

    public static TMap.Entry<?, ?>[] mapEntryArray(TMap.Entry<?, ?>[] entries) {
        TMap.Entry<?, ?>[] placed = placeKnownEntries(entries);
        return placed != null ? placed : probeEntries(entries);
    }

    /** TeaVM 0.15's NEtriesMap.toEntryArray. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static TMap.Entry<?, ?>[] probeEntries(TMap.Entry<?, ?>[] entries) {
        TMap.Entry<?, ?>[] result = new TMap.Entry<?, ?>[entries.length];
        java.util.Arrays.fill(result, null);
        for (TMap.Entry<?, ?> entry : entries) {
            java.util.Objects.requireNonNull(entry.getKey());
            java.util.Objects.requireNonNull(entry.getValue());
            int suggestedIndex = Math.abs(entry.getKey().hashCode()) % entries.length;
            int index = suggestedIndex;
            boolean found = false;
            while (index < entries.length) {
                TMap.Entry<?, ?> existingEntry = result[index];
                if (existingEntry == null) {
                    found = true;
                    break;
                }
                if (existingEntry.getKey().equals(entry.getKey())) {
                    throw new IllegalArgumentException();
                }
                ++index;
            }
            if (!found) {
                index = 0;
                while (index < suggestedIndex) {
                    TMap.Entry<?, ?> existingEntry = result[index];
                    if (existingEntry == null) {
                        break;
                    }
                    if (existingEntry.getKey().equals(entry.getKey())) {
                        throw new IllegalArgumentException();
                    }
                    ++index;
                }
            }
            result[index] = new TTemplateCollections.ImmutableEntry(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static final int KNOWN_ENTRIES_MIN = 8;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TMap.Entry<?, ?>[] placeKnownEntries(TMap.Entry<?, ?>[] entries) {
        int count = entries.length;
        if (count < KNOWN_ENTRIES_MIN) {
            return null;
        }
        Object[] keys = new Object[count];
        Object[] values = new Object[count];
        int[] hashes = new int[count];
        for (int i = 0; i < count; ++i) {
            TMap.Entry<?, ?> entry = entries[i];
            if (entry == null || !knownEntry(entry)) {
                return null;
            }
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null || !knownKey(key)) {
                return null;
            }
            int hash = key.hashCode();
            if (Math.abs(hash) < 0) {
                return null;
            }
            keys[i] = key;
            values[i] = value;
            hashes[i] = hash;
        }
        int mask = indexMask(count);
        int[] table = new int[mask + 1];
        for (int i = 0; i < count; ++i) {
            int slot = spread(hashes[i]) & mask;
            int position;
            while ((position = table[slot]) != 0) {
                if (hashes[position - 1] == hashes[i] && keys[position - 1].equals(keys[i])) {
                    return null;
                }
                slot = (slot + 1) & mask;
            }
            table[slot] = i + 1;
        }
        // next[s] == s: slot s is free; otherwise a later slot (cyclically), with only occupied
        // slots in between.
        int[] next = new int[count];
        for (int i = 0; i < count; ++i) {
            next[i] = i;
        }
        TMap.Entry<?, ?>[] result = new TMap.Entry<?, ?>[count];
        for (int i = 0; i < count; ++i) {
            int start = Math.abs(hashes[i]) % count;
            int slot = start;
            while (next[slot] != slot) {
                slot = next[slot];
            }
            for (int cursor = start; cursor != slot;) {
                int following = next[cursor];
                next[cursor] = slot;
                cursor = following;
            }
            result[slot] = new TTemplateCollections.ImmutableEntry(keys[i], values[i]);
            next[slot] = slot + 1 == count ? 0 : slot + 1;
        }
        return result;
    }

    private static boolean knownEntry(TMap.Entry<?, ?> entry) {
        Class<?> type = entry.getClass();
        return type == TTemplateCollections.ImmutableEntry.class
                || type == THashMap.HashEntry.class
                || type == THashMap.TreeEntry.class
                || type == TLinkedHashMap.LinkedHashMapEntry.class
                || type == TMapEntry.class
                || type == TAbstractMap.SimpleEntry.class
                || type == TAbstractMap.SimpleImmutableEntry.class;
    }

    private static boolean knownKey(Object key) {
        Class<?> type = key.getClass();
        return type == String.class || type == Integer.class || type == Long.class || type == Short.class
                || type == Byte.class || type == Character.class || type == Boolean.class;
    }

    /**
     * ImmutableArrayList.hashCode (TeaVMClasslibPatcher.patchTemplateListHashCode): the fold of
     * TAbstractList.hashCode over the list's array, element by element in order.
     */
    public static int arrayListHashCode(Object[] elements) {
        int hashCode = 1;
        for (Object element : elements) {
            hashCode = 31 * hashCode + java.util.Objects.hashCode(element);
        }
        return hashCode;
    }
}
