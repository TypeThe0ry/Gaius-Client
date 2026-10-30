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
 *  Modified for Gaius: Used only exact, int-bounded spliterator size estimates.
 */

package org.teavm.classlib.java.util.stream.impl;

import java.util.function.Consumer;
import java.util.function.Predicate;
import org.teavm.classlib.java.util.TSpliterator;

/**
 * TeaVM 0.15's stream over a spliterator, with a size estimate that toArray() may trust. A
 * spliterator's estimate is exact only when it reports SIZED; any other spliterator may return an
 * estimate below its real size, and an estimate above Integer.MAX_VALUE does not survive the
 * conversion to int. TeaVM used every estimate as the size of the array that toArray() fills and
 * wrote past its end when the estimate was too small. Unknown sizes are -1 now (toArray() then
 * collects into a list first).
 */
public class TStreamOverSpliterator<T> extends TSimpleStreamImpl<T> {
    private static final int SIZED = 0x00000040;

    private TSpliterator<T> spliterator;

    public TStreamOverSpliterator(TSpliterator<T> spliterator) {
        this.spliterator = spliterator;
    }

    @Override
    public boolean next(Predicate<? super T> consumer) {
        AdapterAction<T> action = new AdapterAction<>(consumer);
        while (spliterator.tryAdvance(action)) {
            if (!action.wantsMore) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected int estimateSize() {
        if (!spliterator.hasCharacteristics(SIZED)) {
            return -1;
        }
        long size = spliterator.estimateSize();
        return size >= 0 && size <= Integer.MAX_VALUE ? (int) size : -1;
    }

    @Override
    public long count() {
        return spliterator.hasCharacteristics(SIZED) ? spliterator.estimateSize() : super.count();
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
