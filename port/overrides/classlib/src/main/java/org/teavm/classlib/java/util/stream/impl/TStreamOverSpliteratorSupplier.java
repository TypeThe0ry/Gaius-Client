/*
 *  Copyright Alexey Andreev and TeaVM contributors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Modified for Gaius: Used only exact, int-bounded supplied spliterator sizes.
 */

package org.teavm.classlib.java.util.stream.impl;

import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * TeaVM 0.15's stream over a supplied spliterator, with the size estimate of
 * TStreamOverSpliterator: exact only for a SIZED spliterator whose size fits in an int, -1
 * otherwise, so toArray() never fills an array that is too small.
 */
public class TStreamOverSpliteratorSupplier<T> extends TSimpleStreamImpl<T> {
    private Supplier<Spliterator<T>> supplier;
    private int characteristics;
    private Spliterator<T> spliterator;

    public TStreamOverSpliteratorSupplier(Supplier<Spliterator<T>> supplier, int characteristics) {
        this.supplier = supplier;
        this.characteristics = characteristics;
    }

    private void init() {
        if (spliterator == null) {
            spliterator = supplier.get();
            supplier = null;
        }
    }

    @Override
    public boolean next(Predicate<? super T> consumer) {
        AdapterAction<T> action = new AdapterAction<>(consumer);
        init();
        while (spliterator.tryAdvance(action)) {
            if (!action.wantsMore) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected int estimateSize() {
        init();
        if (!spliterator.hasCharacteristics(Spliterator.SIZED)) {
            return -1;
        }
        long size = spliterator.estimateSize();
        return size >= 0 && size <= Integer.MAX_VALUE ? (int) size : -1;
    }

    @Override
    public long count() {
        init();
        return spliterator.hasCharacteristics(Spliterator.SIZED) ? spliterator.estimateSize() : super.count();
    }

    static class AdapterAction<T> implements Consumer<T> {
        private Predicate<? super T> consumer;
        boolean wantsMore;

        AdapterAction(Predicate<? super T> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void accept(T t) {
            wantsMore = consumer.test(t);
        }
    }
}
