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
 *  Based on TeaVM 0.15's THashSet (Copyright Alexey Andreev, Apache License 2.0), which derives
 *  from Apache Harmony. Modified for Gaius: the copy constructor, the sized factory and clone
 *  size the backing map like the JDK's HashSet.
 */

package org.teavm.classlib.java.util;

import org.teavm.classlib.java.io.TSerializable;
import org.teavm.classlib.java.lang.TCloneNotSupportedException;
import org.teavm.classlib.java.lang.TCloneable;
import org.teavm.classlib.java.lang.TObject;
import org.teavm.interop.Rename;

/**
 * HashSet over a {@link THashMap}, so a set built by the same operations iterates in the JDK's
 * order. TLinkedHashSet passes a TLinkedHashMap through the package constructor.
 */
public class THashSet<E> extends TAbstractSet<E> implements TCloneable, TSerializable {
    transient THashMap<E, THashSet<E>> backingMap;

    public THashSet() {
        this(new THashMap<>());
    }

    public THashSet(int capacity) {
        this(new THashMap<>(capacity));
    }

    public THashSet(int capacity, float loadFactor) {
        this(new THashMap<>(capacity, loadFactor));
    }

    /** A set of the collection's elements, added one by one in its iteration order. */
    public THashSet(TCollection<? extends E> collection) {
        this(new THashMap<>(copyCapacity(collection.size())));
        for (TIterator<? extends E> iter = collection.iterator(); iter.hasNext();) {
            add(iter.next());
        }
    }

    THashSet(THashMap<E, THashSet<E>> backingMap) {
        this.backingMap = backingMap;
    }

    private static int copyCapacity(int size) {
        return (int) Math.ceil(Math.max(size, 12) / 0.75);
    }

    public static <T> THashSet<T> newHashSet(int numElements) {
        if (numElements < 0) {
            throw new IllegalArgumentException();
        }
        return new THashSet<>(THashMap.capacityFor(numElements));
    }

    @Override
    public boolean add(E object) {
        return backingMap.put(object, this) == null;
    }

    @Override
    public void clear() {
        backingMap.clear();
    }

    @Rename("clone")
    @SuppressWarnings("unchecked")
    public TObject clone0() {
        try {
            THashSet<E> clone = (THashSet<E>) super.clone();
            clone.backingMap = (THashMap<E, THashSet<E>>) backingMap.clone();
            return clone;
        } catch (TCloneNotSupportedException e) {
            return null;
        }
    }

    @Override
    public boolean contains(Object object) {
        return backingMap.containsKey(object);
    }

    @Override
    public boolean isEmpty() {
        return backingMap.isEmpty();
    }

    @Override
    public TIterator<E> iterator() {
        return backingMap.keySet().iterator();
    }

    @Override
    public boolean remove(Object object) {
        return backingMap.remove(object) != null;
    }

    @Override
    public int size() {
        return backingMap.size();
    }
}
