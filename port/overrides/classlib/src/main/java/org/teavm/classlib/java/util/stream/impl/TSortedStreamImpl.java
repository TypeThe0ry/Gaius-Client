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
 *  Modified for Gaius: Reported the remaining size of the sorted array.
 */

package org.teavm.classlib.java.util.stream.impl;

import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Predicate;

/**
 * TeaVM 0.15's sorted stream with an exact size. The stream drains its source into an array
 * when it is created, so the source's estimate afterwards describes what is left of the source
 * (nothing, for a spliterator that counts the remaining elements, as fastutil's and Guava's list
 * spliterators do). TeaVM delegated estimateSize() to that drained source; toArray() of this
 * stream, or of a stream mapped from it, then allocated too small an array and wrote past its end
 * (a WebAssembly GC trap; a silently grown or truncated array in the JavaScript build). Vanilla's
 * LevelStorageSource.loadLevelSummaries sorts the level summaries this way.
 */
public class TSortedStreamImpl<T> extends TSimpleStreamImpl<T> {
    private TSimpleStreamImpl<T> sourceStream;
    private T[] array;
    private int index;

    @SuppressWarnings("unchecked")
    public TSortedStreamImpl(TSimpleStreamImpl<T> sourceStream, Comparator<? super T> comparator) {
        this.sourceStream = sourceStream;
        array = (T[]) sourceStream.toArray(Object[]::new);
        Arrays.sort(array, comparator);
    }

    @Override
    public boolean next(Predicate<? super T> consumer) {
        while (index < array.length) {
            if (!consumer.test(array[index++])) {
                break;
            }
        }
        return index < array.length;
    }

    @Override
    protected int estimateSize() {
        return array.length - index;
    }

    @Override
    public void close() {
        sourceStream.close();
    }
}
