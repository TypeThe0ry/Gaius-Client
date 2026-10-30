/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Based on TeaVM 0.15's THashMap (Copyright Alexey Andreev, Apache License 2.0), which derives
 *  from Apache Harmony. Modified for Gaius: plain maps follow the JDK's bucket layout, growth
 *  and tree-bin rules so that iteration order matches HotSpot; TLinkedHashMap keeps the original
 *  behaviour (see the class comment).
 *  The JDK-compatible behaviour was implemented clean-room from a behavioural specification
 *  by an implementer who never saw JDK source.
 */

package org.teavm.classlib.java.util;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.teavm.classlib.java.io.TSerializable;
import org.teavm.classlib.java.lang.TCloneNotSupportedException;
import org.teavm.classlib.java.lang.TCloneable;
import org.teavm.classlib.java.lang.TObject;
import org.teavm.interop.Rename;

/**
 * HashMap with the JDK's observable iteration order. Vanilla code iterates plain HashMaps and
 * HashSets during world generation (feature placement, block updates), so the browser has to visit
 * the same keys in the same order as the JVM: the same spread hash, power-of-two tables allocated
 * on first use, the same growth steps, tail insertion by put, head insertion by the compute
 * family, and red-black tree bins (with their linked order) for long collision chains.
 *
 * <p>TLinkedHashMap (TeaVM's class, unchanged) stores its own entries in this class's buckets and
 * keeps its order in a separate chain. For instances of it the map keeps TeaVM's original
 * Harmony-derived behaviour ({@link #legacy}): unspread hashes, eagerly allocated tables, head
 * insertion, Harmony rehashing and the TMap default methods. The package-private members that
 * TLinkedHashMap, THashSet and TLinkedHashSet use keep their names and signatures.
 */
public class THashMap<K, V> extends TAbstractMap<K, V> implements TCloneable, TSerializable {
    private static final int DEFAULT_SIZE = 16;
    private static final float DEFAULT_LOAD_FACTOR = 0.75f;
    private static final int LARGEST_TABLE = 1 << 30;
    /** A list bin that reaches this many entries becomes a tree bin (or the table grows). */
    private static final int TREE_BIN_LENGTH = 8;
    /** A split tree bin part this short becomes a list again. */
    private static final int LIST_BIN_LENGTH = 6;
    /** Below this table length a long bin grows the table instead of becoming a tree. */
    private static final int TREE_TABLE_LENGTH = 64;

    transient int elementCount;

    /** The buckets; null until the first insertion of a JDK-mode map. */
    transient HashEntry<K, V>[] elementData;

    transient int modCount;

    final float loadFactor;

    /**
     * Legacy mode: the resize threshold. JDK mode: the requested initial capacity while the table
     * is absent (0 for the default), the resize limit once it exists.
     */
    int threshold;

    /** True for TLinkedHashMap and its subclasses (TeaVM's original behaviour). */
    private final transient boolean legacy;

    /** Legacy mode: runs TMap's default methods against this map (see {@link DefaultsView}). */
    private transient DefaultsView<K, V> defaults;

    static class HashEntry<K, V> extends TMapEntry<K, V> {
        final int origKeyHash;

        HashEntry<K, V> next;

        HashEntry(K theKey, int hash) {
            super(theKey, null);
            this.origKeyHash = hash;
        }

        HashEntry(K theKey, V theValue) {
            super(theKey, theValue);
            origKeyHash = Objects.hashCode(theKey);
        }

        HashEntry(int hash, K theKey, V theValue, HashEntry<K, V> next) {
            super(theKey, theValue);
            this.origKeyHash = hash;
            this.next = next;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object clone() {
            HashEntry<K, V> entry = (HashEntry<K, V>) super.clone();
            if (next != null) {
                entry.next = (HashEntry<K, V>) next.clone();
            }
            return entry;
        }
    }

    /**
     * An entry of a tree bin. The bin keeps two structures over the same nodes: a red-black tree
     * (parent/left/right, ordered by hash, then by compareTo or a tie-break) used for lookups, and
     * the doubly linked chain (next/prev) that iteration follows.
     */
    static final class TreeEntry<K, V> extends HashEntry<K, V> {
        TreeEntry<K, V> parent;
        TreeEntry<K, V> left;
        TreeEntry<K, V> right;
        TreeEntry<K, V> prev;
        boolean red;

        TreeEntry(int hash, K theKey, V theValue, HashEntry<K, V> next) {
            super(hash, theKey, theValue, next);
        }
    }

    /** A remembered answer of {@link #comparableClassOf}; immutable, so it can be shared. */
    private static final class ComparableProbe {
        final Class<?> type;
        final boolean comparable;

        ComparableProbe(Class<?> type, boolean comparable) {
            this.type = type;
            this.comparable = comparable;
        }
    }

    private static ComparableProbe lastProbe;

    @SuppressWarnings("unchecked")
    HashEntry<K, V>[] newElementArray(int s) {
        return new HashEntry[s];
    }

    public THashMap() {
        legacy = this instanceof TLinkedHashMap;
        loadFactor = DEFAULT_LOAD_FACTOR;
        if (legacy) {
            allocateLegacyTable(DEFAULT_SIZE);
        }
    }

    public THashMap(int capacity) {
        this(capacity, DEFAULT_LOAD_FACTOR);
    }

    public THashMap(int capacity, float loadFactor) {
        legacy = this instanceof TLinkedHashMap;
        if (capacity < 0 || !(loadFactor > 0)) {
            throw new IllegalArgumentException();
        }
        this.loadFactor = loadFactor;
        if (legacy) {
            allocateLegacyTable(capacity);
        } else {
            threshold = powerOfTwoAtLeast(capacity);
        }
    }

    public THashMap(TMap<? extends K, ? extends V> map) {
        legacy = this instanceof TLinkedHashMap;
        loadFactor = DEFAULT_LOAD_FACTOR;
        if (legacy) {
            allocateLegacyTable(legacyCapacity(map.size()));
            putAllImpl(map);
        } else {
            insertAll(map);
        }
    }

    public static <K, V> THashMap<K, V> newHashMap(int numMappings) {
        if (numMappings < 0) {
            throw new IllegalArgumentException();
        }
        return new THashMap<>(capacityFor(numMappings));
    }

    /** The JDK's capacity for n mappings at the default load factor (double arithmetic). */
    static int capacityFor(int mappings) {
        return (int) Math.ceil(mappings / (double) DEFAULT_LOAD_FACTOR);
    }

    /** TeaVM's capacity for n mappings, used by TLinkedHashMap/TLinkedHashSet factories. */
    static int capacity(int expectedSize) {
        return (int) Math.ceil(expectedSize / DEFAULT_LOAD_FACTOR);
    }

    // ---------------------------------------------------------------------------------------
    // Common API

    @Override
    public int size() {
        return elementCount;
    }

    @Override
    public boolean isEmpty() {
        return elementCount == 0;
    }

    @Override
    public void clear() {
        if (elementCount > 0) {
            elementCount = 0;
            Arrays.fill(elementData, null);
            modCount++;
        }
    }

    @Override
    public boolean containsKey(Object key) {
        return entryByKey(key) != null;
    }

    @Override
    public V get(Object key) {
        HashEntry<K, V> entry = entryByKey(key);
        return entry != null ? entry.value : null;
    }

    @Override
    public boolean containsValue(Object value) {
        HashEntry<K, V>[] table = elementData;
        if (table == null || elementCount == 0) {
            return false;
        }
        for (HashEntry<K, V> bucket : table) {
            for (HashEntry<K, V> e = bucket; e != null; e = e.next) {
                Object v = e.value;
                if (v == value || (value != null && value.equals(v))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public V put(K key, V value) {
        if (legacy) {
            return legacyPut(key, value);
        }
        return putValue(spread(key), key, value, false);
    }

    @Override
    public void putAll(TMap<? extends K, ? extends V> map) {
        if (legacy) {
            if (!map.isEmpty()) {
                putAllImpl(map);
            }
        } else {
            insertAll(map);
        }
    }

    void putAllImpl(TMap<? extends K, ? extends V> map) {
        if (!legacy) {
            insertAll(map);
            return;
        }
        int capacity = elementCount + map.size();
        if (capacity > threshold) {
            rehash(capacity);
        }
        for (TIterator<? extends TMap.Entry<? extends K, ? extends V>> it = map.entrySet().iterator();
                it.hasNext();) {
            TMap.Entry<? extends K, ? extends V> entry = it.next();
            legacyPut(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public V remove(Object key) {
        HashEntry<K, V> entry = removeByKey(key);
        return entry != null ? entry.value : null;
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        // TLinkedHashMap overrides getOrDefault; both modes look the key up here.
        HashEntry<K, V> entry = entryByKey(key);
        return entry != null ? entry.value : defaultValue;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        if (legacy) {
            return tmapDefaults().putIfAbsent(key, value);
        }
        return putValue(spread(key), key, value, true);
    }

    @Override
    public boolean remove(Object key, Object value) {
        if (legacy) {
            return tmapDefaults().remove(key, value);
        }
        return removeNode(spread(key), key, value, true, true) != null;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        if (legacy) {
            return tmapDefaults().replace(key, oldValue, newValue);
        }
        HashEntry<K, V> entry = findEntry(key);
        if (entry != null && sameValue(entry.value, oldValue)) {
            entry.value = newValue;
            return true;
        }
        return false;
    }

    @Override
    public V replace(K key, V value) {
        if (legacy) {
            return tmapDefaults().replace(key, value);
        }
        HashEntry<K, V> entry = findEntry(key);
        if (entry != null) {
            V old = entry.value;
            entry.value = value;
            return old;
        }
        return null;
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (legacy) {
            return tmapDefaults().computeIfAbsent(key, mappingFunction);
        }
        Objects.requireNonNull(mappingFunction);
        int hash = spread(key);
        BinProbe<K, V> probe = probeForUpdate(hash, key);
        HashEntry<K, V> existing = probe.found;
        if (existing != null && existing.value != null) {
            return existing.value;
        }
        int expected = modCount;
        V value = mappingFunction.apply(key);
        if (expected != modCount) {
            throw new ConcurrentModificationException();
        }
        if (value == null) {
            return null;
        }
        if (existing != null) {
            existing.value = value;
            return value;
        }
        insertProbed(probe, hash, key, value);
        modCount = expected + 1;
        elementCount++;
        return value;
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (legacy) {
            return tmapDefaults().computeIfPresent(key, remappingFunction);
        }
        Objects.requireNonNull(remappingFunction);
        int hash = spread(key);
        HashEntry<K, V> entry = findEntry(key);
        if (entry == null || entry.value == null) {
            return null;
        }
        int expected = modCount;
        V value = remappingFunction.apply(key, entry.value);
        if (expected != modCount) {
            throw new ConcurrentModificationException();
        }
        if (value == null) {
            removeNode(hash, key, null, false, true);
        } else {
            entry.value = value;
        }
        return value;
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (legacy) {
            return tmapDefaults().compute(key, remappingFunction);
        }
        Objects.requireNonNull(remappingFunction);
        int hash = spread(key);
        BinProbe<K, V> probe = probeForUpdate(hash, key);
        HashEntry<K, V> existing = probe.found;
        V old = existing != null ? existing.value : null;
        int expected = modCount;
        V value = remappingFunction.apply(key, old);
        if (expected != modCount) {
            throw new ConcurrentModificationException();
        }
        if (existing != null) {
            if (value != null) {
                existing.value = value;
            } else {
                removeNode(hash, key, null, false, true);
            }
        } else if (value != null) {
            insertProbed(probe, hash, key, value);
            modCount = expected + 1;
            elementCount++;
        }
        return value;
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (legacy) {
            return tmapDefaults().merge(key, value, remappingFunction);
        }
        if (value == null || remappingFunction == null) {
            throw new NullPointerException();
        }
        int hash = spread(key);
        BinProbe<K, V> probe = probeForUpdate(hash, key);
        HashEntry<K, V> existing = probe.found;
        if (existing != null) {
            V result;
            if (existing.value != null) {
                int expected = modCount;
                result = remappingFunction.apply(existing.value, value);
                if (expected != modCount) {
                    throw new ConcurrentModificationException();
                }
            } else {
                result = value;
            }
            if (result != null) {
                existing.value = result;
            } else {
                removeNode(hash, key, null, false, true);
            }
            return result;
        }
        insertProbed(probe, hash, key, value);
        modCount++;
        elementCount++;
        return value;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        HashEntry<K, V>[] table = elementData;
        if (elementCount > 0 && table != null) {
            int expected = modCount;
            for (HashEntry<K, V> bucket : table) {
                for (HashEntry<K, V> e = bucket; e != null; e = e.next) {
                    action.accept(e.key, e.value);
                }
                if (expected != modCount) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        HashEntry<K, V>[] table = elementData;
        if (elementCount > 0 && table != null) {
            int expected = modCount;
            for (HashEntry<K, V> bucket : table) {
                for (HashEntry<K, V> e = bucket; e != null; e = e.next) {
                    e.value = function.apply(e.key, e.value);
                }
                if (expected != modCount) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }

    @Override
    public TSet<K> keySet() {
        if (cachedKeySet == null) {
            // Anonymous class #1: replaces TeaVM's THashMap$1 in the merged class library.
            cachedKeySet = new TAbstractSet<K>() {
                @Override
                public boolean contains(Object object) {
                    return containsKey(object);
                }

                @Override
                public int size() {
                    return elementCount;
                }

                @Override
                public void clear() {
                    THashMap.this.clear();
                }

                @Override
                public boolean remove(Object key) {
                    return removeByKey(key) != null;
                }

                @Override
                public TIterator<K> iterator() {
                    return new KeyCursor<>(THashMap.this);
                }

                @Override
                public void forEach(Consumer<? super K> action) {
                    THashMap.this.forEach((k, v) -> action.accept(k));
                }
            };
        }
        return cachedKeySet;
    }

    @Override
    public TCollection<V> values() {
        if (cachedValues == null) {
            // Anonymous class #2: replaces TeaVM's THashMap$2 in the merged class library.
            cachedValues = new TAbstractCollection<V>() {
                @Override
                public boolean contains(Object object) {
                    return containsValue(object);
                }

                @Override
                public int size() {
                    return elementCount;
                }

                @Override
                public void clear() {
                    THashMap.this.clear();
                }

                @Override
                public TIterator<V> iterator() {
                    return new ValueCursor<>(THashMap.this);
                }

                @Override
                public void forEach(Consumer<? super V> action) {
                    THashMap.this.forEach((k, v) -> action.accept(v));
                }
            };
        }
        return cachedValues;
    }

    @Override
    public TSet<TMap.Entry<K, V>> entrySet() {
        return new EntryView<>(this);
    }

    @Rename("clone")
    @SuppressWarnings("unchecked")
    public TObject clone0() {
        try {
            THashMap<K, V> copy = (THashMap<K, V>) super.clone();
            copy.becomeCloneOf(this);
            return copy;
        } catch (TCloneNotSupportedException e) {
            return null;
        }
    }

    /**
     * Fills a field-by-field copy of {@code source} (same class and load factor) the way clone()
     * does. Legacy: TeaVM's clone (a table of the same length, then putAll). JDK mode: an absent
     * table, then a bulk insertion, so the copy's capacity is sized for the source's size.
     */
    void becomeCloneOf(THashMap<K, V> source) {
        cachedKeySet = null;
        cachedValues = null;
        defaults = null;
        elementCount = 0;
        if (legacy) {
            elementData = newElementArray(source.elementData.length);
            putAll(source);
        } else {
            elementData = null;
            threshold = 0;
            modCount = 0;
            insertAll(source);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Package-private API used by TLinkedHashMap, TLinkedHashMapEntrySet and THashSet

    final HashEntry<K, V> entryByKey(Object key) {
        if (!legacy) {
            return findEntry(key);
        }
        if (key == null) {
            return findNullKeyEntry();
        }
        int hash = key.hashCode();
        return findNonNullKeyEntry(key, hash & (elementData.length - 1), hash);
    }

    final HashEntry<K, V> findNonNullKeyEntry(Object key, int index, int keyHash) {
        HashEntry<K, V> m = elementData[index];
        while (m != null && (m.origKeyHash != keyHash || !areEqualKeys(key, m.key))) {
            m = m.next;
        }
        return m;
    }

    final HashEntry<K, V> findNullKeyEntry() {
        HashEntry<K, V> m = elementData[0];
        while (m != null && m.key != null) {
            m = m.next;
        }
        return m;
    }

    void rehash(int capacity) {
        if (!legacy) {
            while (capacity > threshold && (elementData == null || elementData.length < LARGEST_TABLE)) {
                grow();
            }
            return;
        }
        int length = legacyCapacity(capacity == 0 ? 1 : capacity << 1);
        HashEntry<K, V>[] newData = newElementArray(length);
        for (int i = 0; i < elementData.length; i++) {
            HashEntry<K, V> entry = elementData[i];
            elementData[i] = null;
            while (entry != null) {
                int index = entry.origKeyHash & (length - 1);
                HashEntry<K, V> next = entry.next;
                entry.next = newData[index];
                newData[index] = entry;
                entry = next;
            }
        }
        elementData = newData;
        threshold = (int) (elementData.length * loadFactor);
    }

    void rehash() {
        if (legacy) {
            rehash(elementData.length);
        } else {
            grow();
        }
    }

    final void removeEntry(HashEntry<K, V> entry) {
        if (!legacy) {
            removeNode(entry.origKeyHash, entry.key, null, false, true);
            return;
        }
        int index = entry.origKeyHash & (elementData.length - 1);
        HashEntry<K, V> m = elementData[index];
        if (m == entry) {
            elementData[index] = entry.next;
        } else {
            while (m.next != entry) {
                m = m.next;
            }
            m.next = entry.next;
        }
        modCount++;
        elementCount--;
    }

    final HashEntry<K, V> removeByKey(Object key) {
        if (!legacy) {
            return removeNode(spread(key), key, null, false, true);
        }
        int index = 0;
        HashEntry<K, V> entry;
        HashEntry<K, V> last = null;
        if (key != null) {
            int hash = key.hashCode();
            index = hash & (elementData.length - 1);
            entry = elementData[index];
            while (entry != null && !(entry.origKeyHash == hash && areEqualKeys(key, entry.key))) {
                last = entry;
                entry = entry.next;
            }
        } else {
            entry = elementData[0];
            while (entry != null && entry.key != null) {
                last = entry;
                entry = entry.next;
            }
        }
        if (entry == null) {
            return null;
        }
        if (last == null) {
            elementData[index] = entry.next;
        } else {
            last.next = entry.next;
        }
        modCount++;
        elementCount--;
        return entry;
    }

    static boolean areEqualKeys(Object key1, Object key2) {
        return key1 == key2 || key1.equals(key2);
    }

    // ---------------------------------------------------------------------------------------
    // Legacy (TeaVM/Harmony) mode

    private TMap<K, V> tmapDefaults() {
        if (defaults == null) {
            defaults = new DefaultsView<>(this);
        }
        return defaults;
    }

    /**
     * A TMap that forwards every method a map class implements to the legacy map, but inherits
     * TMap's default putIfAbsent/compute/merge/replace/remove(key, value). Calling one of those on
     * the view runs TeaVM's default implementation with this map's own get/put/remove (and
     * TLinkedHashMap's overrides of them), as on TeaVM's original THashMap, which did not
     * override them.
     */
    static final class DefaultsView<K, V> implements TMap<K, V> {
        private final THashMap<K, V> map;

        DefaultsView(THashMap<K, V> map) {
            this.map = map;
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return map.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return map.containsValue(value);
        }

        @Override
        public V get(Object key) {
            return map.get(key);
        }

        @Override
        public V getOrDefault(Object key, V defaultValue) {
            return map.getOrDefault(key, defaultValue);
        }

        @Override
        public V put(K key, V value) {
            return map.put(key, value);
        }

        @Override
        public V remove(Object key) {
            return map.remove(key);
        }

        @Override
        public void putAll(TMap<? extends K, ? extends V> m) {
            map.putAll(m);
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public TSet<K> keySet() {
            return map.keySet();
        }

        @Override
        public TCollection<V> values() {
            return map.values();
        }

        @Override
        public TSet<TMap.Entry<K, V>> entrySet() {
            return map.entrySet();
        }

        @Override
        public void forEach(BiConsumer<? super K, ? super V> action) {
            map.forEach(action);
        }

        @Override
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            map.replaceAll(function);
        }
    }

    private void allocateLegacyTable(int capacity) {
        elementCount = 0;
        elementData = newElementArray(legacyCapacity(capacity));
        threshold = (int) (elementData.length * loadFactor);
    }

    /** Harmony's table length: the power of two at least x, 16 for 0, at most 2^30. */
    private static int legacyCapacity(int x) {
        if (x >= LARGEST_TABLE) {
            return LARGEST_TABLE;
        }
        if (x == 0) {
            return DEFAULT_SIZE;
        }
        x = x - 1;
        x |= x >> 1;
        x |= x >> 2;
        x |= x >> 4;
        x |= x >> 8;
        x |= x >> 16;
        return x + 1;
    }

    private V legacyPut(K key, V value) {
        HashEntry<K, V> entry;
        if (key == null) {
            entry = findNullKeyEntry();
            if (entry == null) {
                modCount++;
                entry = legacyLink(null, 0, 0);
                if (++elementCount > threshold) {
                    rehash();
                }
            }
        } else {
            int hash = key.hashCode();
            int index = hash & (elementData.length - 1);
            entry = findNonNullKeyEntry(key, index, hash);
            if (entry == null) {
                modCount++;
                entry = legacyLink(key, index, hash);
                if (++elementCount > threshold) {
                    rehash();
                }
            }
        }
        V result = entry.value;
        entry.value = value;
        return result;
    }

    private HashEntry<K, V> legacyLink(K key, int index, int hash) {
        HashEntry<K, V> entry = new HashEntry<>(key, hash);
        entry.next = elementData[index];
        elementData[index] = entry;
        return entry;
    }

    // ---------------------------------------------------------------------------------------
    // JDK mode: hashing, sizing and growth

    static int spread(Object key) {
        if (key == null) {
            return 0;
        }
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static boolean sameKey(HashEntry<?, ?> entry, int hash, Object key) {
        if (entry.origKeyHash != hash) {
            return false;
        }
        Object k = entry.key;
        return k == key || (key != null && key.equals(k));
    }

    private static boolean sameValue(Object stored, Object probe) {
        return stored == probe || (probe != null && probe.equals(stored));
    }

    /** P(c): the smallest power of two at least c (1 for 0), at most 2^30. */
    private static int powerOfTwoAtLeast(int c) {
        if (c >= LARGEST_TABLE) {
            return LARGEST_TABLE;
        }
        int n = 1;
        while (n < c) {
            n <<= 1;
        }
        return n;
    }

    private int limitFor(int length) {
        float limit = length * loadFactor;
        return length < LARGEST_TABLE && limit < (float) LARGEST_TABLE ? (int) limit : Integer.MAX_VALUE;
    }

    /** One growth step (allocation of the first table included); returns the current table. */
    final HashEntry<K, V>[] grow() {
        HashEntry<K, V>[] old = elementData;
        int oldLength = old == null ? 0 : old.length;
        int newLength;
        int newLimit;
        if (oldLength > 0) {
            if (oldLength >= LARGEST_TABLE) {
                threshold = Integer.MAX_VALUE;
                return old;
            }
            newLength = oldLength << 1;
            newLimit = newLength < LARGEST_TABLE && oldLength >= DEFAULT_SIZE ? threshold << 1 : limitFor(newLength);
        } else if (threshold > 0) {
            newLength = threshold;
            newLimit = limitFor(newLength);
        } else {
            newLength = DEFAULT_SIZE;
            newLimit = (int) (DEFAULT_SIZE * DEFAULT_LOAD_FACTOR);
        }
        threshold = newLimit;
        HashEntry<K, V>[] table = newElementArray(newLength);
        elementData = table;
        if (old != null) {
            for (int i = 0; i < oldLength; i++) {
                HashEntry<K, V> e = old[i];
                if (e == null) {
                    continue;
                }
                old[i] = null;
                if (e.next == null) {
                    table[e.origKeyHash & (newLength - 1)] = e;
                } else if (e instanceof TreeEntry) {
                    splitTreeBin(table, (TreeEntry<K, V>) e, i, oldLength);
                } else {
                    splitListBin(table, e, i, oldLength);
                }
            }
        }
        return table;
    }

    private static <K, V> void splitListBin(HashEntry<K, V>[] table, HashEntry<K, V> e, int index, int bit) {
        HashEntry<K, V> lowHead = null;
        HashEntry<K, V> lowTail = null;
        HashEntry<K, V> highHead = null;
        HashEntry<K, V> highTail = null;
        while (e != null) {
            HashEntry<K, V> next = e.next;
            e.next = null;
            if ((e.origKeyHash & bit) == 0) {
                if (lowTail == null) {
                    lowHead = e;
                } else {
                    lowTail.next = e;
                }
                lowTail = e;
            } else {
                if (highTail == null) {
                    highHead = e;
                } else {
                    highTail.next = e;
                }
                highTail = e;
            }
            e = next;
        }
        table[index] = lowHead;
        table[index + bit] = highHead;
    }

    private void insertAll(TMap<? extends K, ? extends V> map) {
        int count = map.size();
        if (count <= 0) {
            return;
        }
        if (elementData == null) {
            double wanted = Math.ceil(count / (double) loadFactor);
            int capacity = wanted < LARGEST_TABLE ? (int) wanted : LARGEST_TABLE;
            if (capacity > threshold) {
                threshold = powerOfTwoAtLeast(capacity);
            }
        } else {
            while (count > threshold && elementData.length < LARGEST_TABLE) {
                grow();
            }
        }
        for (TIterator<? extends TMap.Entry<? extends K, ? extends V>> it = map.entrySet().iterator();
                it.hasNext();) {
            TMap.Entry<? extends K, ? extends V> entry = it.next();
            K key = entry.getKey();
            putValue(spread(key), key, entry.getValue(), false);
        }
    }

    // ---------------------------------------------------------------------------------------
    // JDK mode: lookup, insertion, removal

    final HashEntry<K, V> findEntry(Object key) {
        HashEntry<K, V>[] table = elementData;
        if (table == null) {
            return null;
        }
        int hash = spread(key);
        HashEntry<K, V> first = table[hash & (table.length - 1)];
        if (first == null) {
            return null;
        }
        if (sameKey(first, hash, key)) {
            return first;
        }
        HashEntry<K, V> e = first.next;
        if (e == null) {
            return null;
        }
        if (first instanceof TreeEntry) {
            return search(rootOf((TreeEntry<K, V>) first), hash, key, null, false);
        }
        do {
            if (sameKey(e, hash, key)) {
                return e;
            }
            e = e.next;
        } while (e != null);
        return null;
    }

    private V putValue(int hash, K key, V value, boolean onlyIfAbsent) {
        HashEntry<K, V>[] table = elementData;
        if (table == null || table.length == 0) {
            table = grow();
        }
        int index = hash & (table.length - 1);
        HashEntry<K, V> first = table[index];
        HashEntry<K, V> existing = null;
        if (first == null) {
            table[index] = new HashEntry<>(hash, key, value, null);
        } else if (sameKey(first, hash, key)) {
            existing = first;
        } else if (first instanceof TreeEntry) {
            existing = treeInsert(table, (TreeEntry<K, V>) first, hash, key, value);
        } else {
            HashEntry<K, V> p = first;
            int length = 1;
            while (true) {
                HashEntry<K, V> e = p.next;
                if (e == null) {
                    p.next = new HashEntry<>(hash, key, value, null);
                    if (length >= TREE_BIN_LENGTH) {
                        treeifyOrGrow(table, index);
                    }
                    break;
                }
                if (sameKey(e, hash, key)) {
                    existing = e;
                    break;
                }
                length++;
                p = e;
            }
        }
        if (existing != null) {
            V old = existing.value;
            if (!onlyIfAbsent || old == null) {
                existing.value = value;
            }
            return old;
        }
        modCount++;
        if (++elementCount > threshold) {
            grow();
        }
        return null;
    }

    /** What the compute family found in the key's bin before calling the function. */
    private static final class BinProbe<K, V> {
        HashEntry<K, V>[] table;
        int index;
        HashEntry<K, V> found;
        /** Entries of a list bin examined before the match or the end. */
        int examined;
    }

    private BinProbe<K, V> probeForUpdate(int hash, Object key) {
        HashEntry<K, V>[] table = elementData;
        if (elementCount > threshold || table == null || table.length == 0) {
            table = grow();
        }
        BinProbe<K, V> probe = new BinProbe<>();
        probe.table = table;
        probe.index = hash & (table.length - 1);
        HashEntry<K, V> first = table[probe.index];
        if (first instanceof TreeEntry) {
            probe.found = findInTree((TreeEntry<K, V>) first, hash, key);
        } else {
            for (HashEntry<K, V> e = first; e != null; e = e.next) {
                if (sameKey(e, hash, key)) {
                    probe.found = e;
                    break;
                }
                probe.examined++;
            }
        }
        return probe;
    }

    /** Inserts a new mapping found absent by {@link #probeForUpdate} (the size is not updated). */
    private void insertProbed(BinProbe<K, V> probe, int hash, K key, V value) {
        HashEntry<K, V>[] table = probe.table;
        HashEntry<K, V> first = table[probe.index];
        if (first instanceof TreeEntry) {
            treeInsert(table, (TreeEntry<K, V>) first, hash, key, value);
        } else {
            table[probe.index] = new HashEntry<>(hash, key, value, first);
            if (probe.examined >= TREE_BIN_LENGTH - 1) {
                treeifyOrGrow(table, probe.index);
            }
        }
    }

    final HashEntry<K, V> removeNode(int hash, Object key, Object value, boolean matchValue, boolean movable) {
        HashEntry<K, V>[] table = elementData;
        if (table == null) {
            return null;
        }
        int index = hash & (table.length - 1);
        HashEntry<K, V> first = table[index];
        if (first == null) {
            return null;
        }
        HashEntry<K, V> node = null;
        HashEntry<K, V> before = null;
        if (sameKey(first, hash, key)) {
            node = first;
        } else if (first instanceof TreeEntry) {
            node = findInTree((TreeEntry<K, V>) first, hash, key);
        } else {
            for (HashEntry<K, V> p = first, e = first.next; e != null; p = e, e = e.next) {
                if (sameKey(e, hash, key)) {
                    node = e;
                    before = p;
                    break;
                }
            }
        }
        if (node == null || (matchValue && !sameValue(node.value, value))) {
            return null;
        }
        if (node instanceof TreeEntry) {
            treeDelete(table, (TreeEntry<K, V>) node, movable);
        } else if (before == null) {
            table[index] = node.next;
        } else {
            before.next = node.next;
        }
        modCount++;
        elementCount--;
        return node;
    }

    // ---------------------------------------------------------------------------------------
    // JDK mode: tree bins

    private void treeifyOrGrow(HashEntry<K, V>[] table, int index) {
        if (table.length < TREE_TABLE_LENGTH) {
            grow();
            return;
        }
        HashEntry<K, V> e = table[index];
        if (e == null || e instanceof TreeEntry) {
            return;
        }
        TreeEntry<K, V> head = null;
        TreeEntry<K, V> tail = null;
        for (; e != null; e = e.next) {
            TreeEntry<K, V> node = new TreeEntry<>(e.origKeyHash, e.key, e.value, null);
            node.prev = tail;
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
        }
        table[index] = head;
        buildTree(table, head);
    }

    /** Builds the red-black tree of a chain of tree entries (in chain order), then puts its root first. */
    private static <K, V> void buildTree(HashEntry<K, V>[] table, TreeEntry<K, V> head) {
        TreeEntry<K, V> root = null;
        for (TreeEntry<K, V> x = head; x != null; x = (TreeEntry<K, V>) x.next) {
            x.left = null;
            x.right = null;
            if (root == null) {
                x.parent = null;
                x.red = false;
                root = x;
                continue;
            }
            Object key = x.key;
            int hash = x.origKeyHash;
            Class<?> comparable = null;
            boolean classified = false;
            TreeEntry<K, V> p = root;
            while (true) {
                int dir;
                int ph = p.origKeyHash;
                if (ph > hash) {
                    dir = -1;
                } else if (ph < hash) {
                    dir = 1;
                } else {
                    if (!classified) {
                        comparable = comparableClassOf(key);
                        classified = true;
                    }
                    dir = comparable != null ? compareSameClass(comparable, key, p.key) : 0;
                    if (dir == 0) {
                        dir = tieBreak(key, p.key);
                    }
                }
                TreeEntry<K, V> child = dir <= 0 ? p.left : p.right;
                if (child == null) {
                    x.parent = p;
                    if (dir <= 0) {
                        p.left = x;
                    } else {
                        p.right = x;
                    }
                    root = insertFixup(root, x);
                    break;
                }
                p = child;
            }
        }
        promoteTreeRootInBin(table, root);
    }

    /**
     * Inserts a mapping into a tree bin, or returns the entry that already holds the key. A new
     * entry follows its tree parent in the bin's chain.
     */
    private TreeEntry<K, V> treeInsert(HashEntry<K, V>[] table, TreeEntry<K, V> first, int hash, K key, V value) {
        TreeEntry<K, V> root = rootOf(first);
        Class<?> comparable = null;
        boolean classified = false;
        boolean searched = false;
        TreeEntry<K, V> p = root;
        while (true) {
            int dir;
            int ph = p.origKeyHash;
            if (ph > hash) {
                dir = -1;
            } else if (ph < hash) {
                dir = 1;
            } else {
                Object pk = p.key;
                if (pk == key || (key != null && key.equals(pk))) {
                    return p;
                }
                if (!classified) {
                    comparable = comparableClassOf(key);
                    classified = true;
                }
                dir = comparable != null ? compareSameClass(comparable, key, pk) : 0;
                if (dir == 0) {
                    if (!searched) {
                        searched = true;
                        TreeEntry<K, V> q = null;
                        if (p.left != null) {
                            q = search(p.left, hash, key, comparable, true);
                        }
                        if (q == null && p.right != null) {
                            q = search(p.right, hash, key, comparable, true);
                        }
                        if (q != null) {
                            return q;
                        }
                    }
                    dir = tieBreak(key, pk);
                }
            }
            TreeEntry<K, V> child = dir <= 0 ? p.left : p.right;
            if (child == null) {
                TreeEntry<K, V> after = (TreeEntry<K, V>) p.next;
                TreeEntry<K, V> x = new TreeEntry<>(hash, key, value, after);
                if (dir <= 0) {
                    p.left = x;
                } else {
                    p.right = x;
                }
                p.next = x;
                x.parent = p;
                x.prev = p;
                if (after != null) {
                    after.prev = x;
                }
                promoteTreeRootInBin(table, insertFixup(root, x));
                return null;
            }
            p = child;
        }
    }

    private static <K, V> TreeEntry<K, V> findInTree(TreeEntry<K, V> first, int hash, Object key) {
        if (sameKey(first, hash, key)) {
            return first;
        }
        return search(rootOf(first), hash, key, null, false);
    }

    /** Finds the entry of a key in the subtree of p. */
    private static <K, V> TreeEntry<K, V> search(TreeEntry<K, V> p, int hash, Object key, Class<?> comparable,
            boolean classified) {
        while (p != null) {
            TreeEntry<K, V> pl = p.left;
            TreeEntry<K, V> pr = p.right;
            int ph = p.origKeyHash;
            if (ph > hash) {
                p = pl;
                continue;
            }
            if (ph < hash) {
                p = pr;
                continue;
            }
            Object pk = p.key;
            if (pk == key || (key != null && key.equals(pk))) {
                return p;
            }
            if (pl == null) {
                p = pr;
            } else if (pr == null) {
                p = pl;
            } else {
                if (!classified) {
                    comparable = comparableClassOf(key);
                    classified = true;
                }
                int dir = comparable != null ? compareSameClass(comparable, key, pk) : 0;
                if (dir != 0) {
                    p = dir < 0 ? pl : pr;
                } else {
                    TreeEntry<K, V> q = search(pr, hash, key, comparable, true);
                    if (q != null) {
                        return q;
                    }
                    p = pl;
                }
            }
        }
        return null;
    }

    /**
     * Removes a tree entry. With {@code movable} (map removal) a tree that became too small turns
     * back into a list and the root moves to the front of the chain; iterator removal leaves both.
     */
    private void treeDelete(HashEntry<K, V>[] table, TreeEntry<K, V> z, boolean movable) {
        int index = z.origKeyHash & (table.length - 1);
        TreeEntry<K, V> first = (TreeEntry<K, V>) table[index];
        TreeEntry<K, V> root = first;
        TreeEntry<K, V> successor = (TreeEntry<K, V>) z.next;
        TreeEntry<K, V> predecessor = z.prev;
        if (predecessor == null) {
            first = successor;
            table[index] = successor;
        } else {
            predecessor.next = successor;
        }
        if (successor != null) {
            successor.prev = predecessor;
        }
        if (first == null) {
            return;
        }
        root = rootOf(root);
        if (movable) {
            TreeEntry<K, V> rootLeft = root.left;
            if (root.right == null || rootLeft == null || rootLeft.left == null) {
                table[index] = toPlainList(first);
                return;
            }
        }

        TreeEntry<K, V> left = z.left;
        TreeEntry<K, V> right = z.right;
        TreeEntry<K, V> replacement;
        if (left != null && right != null) {
            // Swap z with its in-order successor s (positions and colours), so that z has at most
            // one child.
            TreeEntry<K, V> s = right;
            while (s.left != null) {
                s = s.left;
            }
            boolean colour = s.red;
            s.red = z.red;
            z.red = colour;
            TreeEntry<K, V> sRight = s.right;
            TreeEntry<K, V> zParent = z.parent;
            if (s == right) {
                z.parent = s;
                s.right = z;
            } else {
                TreeEntry<K, V> sParent = s.parent;
                z.parent = sParent;
                if (s == sParent.left) {
                    sParent.left = z;
                } else {
                    sParent.right = z;
                }
                s.right = right;
                right.parent = s;
            }
            z.left = null;
            z.right = sRight;
            if (sRight != null) {
                sRight.parent = z;
            }
            s.left = left;
            left.parent = s;
            s.parent = zParent;
            if (zParent == null) {
                root = s;
            } else if (zParent.left == z) {
                zParent.left = s;
            } else {
                zParent.right = s;
            }
            replacement = sRight != null ? sRight : z;
        } else if (left != null) {
            replacement = left;
        } else if (right != null) {
            replacement = right;
        } else {
            replacement = z;
        }

        if (replacement != z) {
            TreeEntry<K, V> zParent = z.parent;
            replacement.parent = zParent;
            if (zParent == null) {
                root = replacement;
                replacement.red = false;
            } else if (zParent.left == z) {
                zParent.left = replacement;
            } else {
                zParent.right = replacement;
            }
            z.left = null;
            z.right = null;
            z.parent = null;
        }

        // z stands in for the empty leaf while the fix-up runs when it has no child.
        TreeEntry<K, V> newRoot = z.red ? root : deleteFixup(root, replacement);

        if (replacement == z) {
            TreeEntry<K, V> zParent = z.parent;
            z.parent = null;
            if (zParent != null) {
                if (zParent.left == z) {
                    zParent.left = null;
                } else if (zParent.right == z) {
                    zParent.right = null;
                }
            }
        }
        if (movable) {
            promoteTreeRootInBin(table, newRoot);
        }
    }

    /** TREE SPLIT of a grow step: a part keeps the tree only if the other part is empty. */
    private static <K, V> void splitTreeBin(HashEntry<K, V>[] table, TreeEntry<K, V> first, int index, int bit) {
        TreeEntry<K, V> lowHead = null;
        TreeEntry<K, V> lowTail = null;
        TreeEntry<K, V> highHead = null;
        TreeEntry<K, V> highTail = null;
        int lowCount = 0;
        int highCount = 0;
        TreeEntry<K, V> e = first;
        while (e != null) {
            TreeEntry<K, V> next = (TreeEntry<K, V>) e.next;
            e.next = null;
            if ((e.origKeyHash & bit) == 0) {
                e.prev = lowTail;
                if (lowTail == null) {
                    lowHead = e;
                } else {
                    lowTail.next = e;
                }
                lowTail = e;
                lowCount++;
            } else {
                e.prev = highTail;
                if (highTail == null) {
                    highHead = e;
                } else {
                    highTail.next = e;
                }
                highTail = e;
                highCount++;
            }
            e = next;
        }
        if (lowHead != null) {
            if (lowCount <= LIST_BIN_LENGTH) {
                table[index] = toPlainList(lowHead);
            } else {
                table[index] = lowHead;
                if (highHead != null) {
                    buildTree(table, lowHead);
                }
            }
        }
        if (highHead != null) {
            if (highCount <= LIST_BIN_LENGTH) {
                table[index + bit] = toPlainList(highHead);
            } else {
                table[index + bit] = highHead;
                if (lowHead != null) {
                    buildTree(table, highHead);
                }
            }
        }
    }

    private static <K, V> HashEntry<K, V> toPlainList(HashEntry<K, V> first) {
        HashEntry<K, V> head = null;
        HashEntry<K, V> tail = null;
        for (HashEntry<K, V> e = first; e != null; e = e.next) {
            HashEntry<K, V> plain = new HashEntry<>(e.origKeyHash, e.key, e.value, null);
            if (tail == null) {
                head = plain;
            } else {
                tail.next = plain;
            }
            tail = plain;
        }
        return head;
    }

    private static <K, V> TreeEntry<K, V> rootOf(TreeEntry<K, V> node) {
        while (node.parent != null) {
            node = node.parent;
        }
        return node;
    }

    /** Makes the tree root the first entry of its bin's chain; the other entries keep their order. */
    private static <K, V> void promoteTreeRootInBin(HashEntry<K, V>[] table, TreeEntry<K, V> root) {
        int index = root.origKeyHash & (table.length - 1);
        TreeEntry<K, V> first = (TreeEntry<K, V>) table[index];
        if (root == first) {
            return;
        }
        TreeEntry<K, V> after = (TreeEntry<K, V>) root.next;
        TreeEntry<K, V> before = root.prev;
        if (after != null) {
            after.prev = before;
        }
        if (before != null) {
            before.next = after;
        }
        if (first != null) {
            first.prev = root;
        }
        root.next = first;
        root.prev = null;
        table[index] = root;
    }

    /**
     * The class of x if x is a String or its class itself declares Comparable (the order of keys of
     * one such class in a tree bin is their compareTo order), otherwise null. Interfaces that a
     * class inherits from its superclasses do not count.
     */
    static Class<?> comparableClassOf(Object x) {
        if (!(x instanceof Comparable)) {
            return null;
        }
        Class<?> type = x.getClass();
        if (type == String.class || type == Integer.class || type == Long.class || type == Short.class
                || type == Byte.class || type == Character.class || type == Boolean.class || type == Float.class
                || type == Double.class || type == java.util.UUID.class) {
            return type;
        }
        ComparableProbe probe = lastProbe;
        if (probe == null || probe.type != type) {
            probe = new ComparableProbe(type, declaresComparable(type));
            lastProbe = probe;
        }
        return probe.comparable ? type : null;
    }

    private static boolean declaresComparable(Class<?> type) {
        Class<?>[] interfaces;
        try {
            interfaces = type.getInterfaces();
        } catch (RuntimeException e) {
            return false;
        }
        if (interfaces != null) {
            for (Class<?> declared : interfaces) {
                if (declared == Comparable.class) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int compareSameClass(Class<?> comparable, Object key, Object other) {
        if (other == null || other.getClass() != comparable) {
            return 0;
        }
        return ((Comparable) key).compareTo(other);
    }

    /** Orders keys that neither hash nor compareTo separate: by class name, then identity hash. */
    private static int tieBreak(Object a, Object b) {
        int d = 0;
        if (a != null && b != null) {
            d = a.getClass().getName().compareTo(b.getClass().getName());
        }
        if (d == 0) {
            d = identityHash(a) <= identityHash(b) ? -1 : 1;
        }
        return d;
    }

    private static int identityHash(Object o) {
        return o == null ? 0 : System.identityHashCode(o);
    }

    private static boolean isRed(TreeEntry<?, ?> node) {
        return node != null && node.red;
    }

    // Red-black tree rotations and fix-ups (Cormen et al., Introduction to Algorithms, chapter 13).

    private static <K, V> TreeEntry<K, V> rotateLeft(TreeEntry<K, V> root, TreeEntry<K, V> x) {
        TreeEntry<K, V> y = x.right;
        x.right = y.left;
        if (y.left != null) {
            y.left.parent = x;
        }
        TreeEntry<K, V> xp = x.parent;
        y.parent = xp;
        if (xp == null) {
            root = y;
        } else if (x == xp.left) {
            xp.left = y;
        } else {
            xp.right = y;
        }
        y.left = x;
        x.parent = y;
        return root;
    }

    private static <K, V> TreeEntry<K, V> rotateRight(TreeEntry<K, V> root, TreeEntry<K, V> x) {
        TreeEntry<K, V> y = x.left;
        x.left = y.right;
        if (y.right != null) {
            y.right.parent = x;
        }
        TreeEntry<K, V> xp = x.parent;
        y.parent = xp;
        if (xp == null) {
            root = y;
        } else if (x == xp.right) {
            xp.right = y;
        } else {
            xp.left = y;
        }
        y.right = x;
        x.parent = y;
        return root;
    }

    private static <K, V> TreeEntry<K, V> insertFixup(TreeEntry<K, V> root, TreeEntry<K, V> z) {
        z.red = true;
        while (z.parent != null && z.parent.red) {
            TreeEntry<K, V> zp = z.parent;
            TreeEntry<K, V> zpp = zp.parent;
            if (zpp == null) {
                zp.red = false;
                break;
            }
            if (zp == zpp.left) {
                TreeEntry<K, V> uncle = zpp.right;
                if (isRed(uncle)) {
                    zp.red = false;
                    uncle.red = false;
                    zpp.red = true;
                    z = zpp;
                } else {
                    if (z == zp.right) {
                        z = zp;
                        root = rotateLeft(root, z);
                        zp = z.parent;
                        zpp = zp.parent;
                    }
                    zp.red = false;
                    zpp.red = true;
                    root = rotateRight(root, zpp);
                }
            } else {
                TreeEntry<K, V> uncle = zpp.left;
                if (isRed(uncle)) {
                    zp.red = false;
                    uncle.red = false;
                    zpp.red = true;
                    z = zpp;
                } else {
                    if (z == zp.left) {
                        z = zp;
                        root = rotateRight(root, z);
                        zp = z.parent;
                        zpp = zp.parent;
                    }
                    zp.red = false;
                    zpp.red = true;
                    root = rotateLeft(root, zpp);
                }
            }
        }
        root.red = false;
        return root;
    }

    private static <K, V> TreeEntry<K, V> deleteFixup(TreeEntry<K, V> root, TreeEntry<K, V> x) {
        while (x != root && !x.red) {
            TreeEntry<K, V> xp = x.parent;
            if (x == xp.left) {
                TreeEntry<K, V> w = xp.right;
                if (w.red) {
                    w.red = false;
                    xp.red = true;
                    root = rotateLeft(root, xp);
                    w = xp.right;
                }
                if (!isRed(w.left) && !isRed(w.right)) {
                    w.red = true;
                    x = xp;
                } else {
                    if (!isRed(w.right)) {
                        w.left.red = false;
                        w.red = true;
                        root = rotateRight(root, w);
                        w = xp.right;
                    }
                    w.red = xp.red;
                    xp.red = false;
                    w.right.red = false;
                    root = rotateLeft(root, xp);
                    x = root;
                }
            } else {
                TreeEntry<K, V> w = xp.left;
                if (w.red) {
                    w.red = false;
                    xp.red = true;
                    root = rotateRight(root, xp);
                    w = xp.left;
                }
                if (!isRed(w.left) && !isRed(w.right)) {
                    w.red = true;
                    x = xp;
                } else {
                    if (!isRed(w.left)) {
                        w.right.red = false;
                        w.red = true;
                        root = rotateLeft(root, w);
                        w = xp.left;
                    }
                    w.red = xp.red;
                    xp.red = false;
                    w.left.red = false;
                    root = rotateRight(root, xp);
                    x = root;
                }
            }
        }
        x.red = false;
        return root;
    }

    // ---------------------------------------------------------------------------------------
    // Iteration

    /**
     * Walks the buckets in index order and each bucket's chain. The position after an entry is
     * fixed when the entry is returned; removing the entry does not move it.
     */
    abstract static class BucketCursor<K, V> {
        final THashMap<K, V> map;
        private final HashEntry<K, V>[] table;
        private int slot;
        private HashEntry<K, V> upcoming;
        private HashEntry<K, V> current;
        private int expectedModCount;

        BucketCursor(THashMap<K, V> map) {
            this.map = map;
            this.table = map.elementData;
            this.expectedModCount = map.modCount;
            if (table != null && map.elementCount > 0) {
                upcoming = nextBucket();
            }
        }

        private HashEntry<K, V> nextBucket() {
            while (slot < table.length) {
                HashEntry<K, V> e = table[slot++];
                if (e != null) {
                    return e;
                }
            }
            return null;
        }

        public boolean hasNext() {
            return upcoming != null;
        }

        final HashEntry<K, V> nextEntry() {
            if (map.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            HashEntry<K, V> e = upcoming;
            if (e == null) {
                throw new NoSuchElementException();
            }
            current = e;
            upcoming = e.next != null ? e.next : nextBucket();
            return e;
        }

        public void remove() {
            HashEntry<K, V> e = current;
            if (e == null) {
                throw new IllegalStateException();
            }
            if (map.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            current = null;
            if (map.legacy) {
                map.removeEntry(e);
            } else {
                map.removeNode(e.origKeyHash, e.key, null, false, false);
            }
            expectedModCount = map.modCount;
        }
    }

    static final class KeyCursor<K, V> extends BucketCursor<K, V> implements TIterator<K> {
        KeyCursor(THashMap<K, V> map) {
            super(map);
        }

        @Override
        public K next() {
            return nextEntry().key;
        }
    }

    static final class ValueCursor<K, V> extends BucketCursor<K, V> implements TIterator<V> {
        ValueCursor(THashMap<K, V> map) {
            super(map);
        }

        @Override
        public V next() {
            return nextEntry().value;
        }
    }

    static final class EntryCursor<K, V> extends BucketCursor<K, V> implements TIterator<TMap.Entry<K, V>> {
        EntryCursor(THashMap<K, V> map) {
            super(map);
        }

        @Override
        public TMap.Entry<K, V> next() {
            return nextEntry();
        }
    }

    static final class EntryView<K, V> extends TAbstractSet<TMap.Entry<K, V>> {
        private final THashMap<K, V> map;

        EntryView(THashMap<K, V> map) {
            this.map = map;
        }

        @Override
        public int size() {
            return map.elementCount;
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public boolean remove(Object object) {
            if (!(object instanceof TMap.Entry)) {
                return false;
            }
            TMap.Entry<?, ?> entry = (TMap.Entry<?, ?>) object;
            Object key = entry.getKey();
            if (map.legacy) {
                if (contains(object)) {
                    map.remove(key);
                    return true;
                }
                return false;
            }
            return map.removeNode(spread(key), key, entry.getValue(), true, true) != null;
        }

        @Override
        public boolean contains(Object object) {
            if (!(object instanceof TMap.Entry)) {
                return false;
            }
            TMap.Entry<?, ?> entry = (TMap.Entry<?, ?>) object;
            HashEntry<K, V> found = map.entryByKey(entry.getKey());
            return found != null && sameValue(found.value, entry.getValue());
        }

        @Override
        public TIterator<TMap.Entry<K, V>> iterator() {
            return new EntryCursor<>(map);
        }

        @Override
        public void forEach(Consumer<? super TMap.Entry<K, V>> action) {
            Objects.requireNonNull(action);
            HashEntry<K, V>[] table = map.elementData;
            if (map.elementCount > 0 && table != null) {
                int expected = map.modCount;
                for (HashEntry<K, V> bucket : table) {
                    for (HashEntry<K, V> e = bucket; e != null; e = e.next) {
                        action.accept(e);
                    }
                    if (expected != map.modCount) {
                        throw new ConcurrentModificationException();
                    }
                }
            }
        }
    }
}
