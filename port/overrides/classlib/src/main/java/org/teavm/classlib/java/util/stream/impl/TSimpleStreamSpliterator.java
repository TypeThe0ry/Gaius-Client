/*
 *  Copyright Alexey Andreev (TeaVM).
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
 *  Modified for Gaius: Corrected tryAdvance to report only delivered elements.
 */

package org.teavm.classlib.java.util.stream.impl;

import java.util.Spliterator;
import java.util.function.Consumer;

/**
 * TeaVM 0.15's spliterator of a stream (Stream.spliterator()), with tryAdvance() returning whether
 * it passed an element to the action. TeaVM returned true also from the call that found the stream
 * exhausted, without calling the action. An iterator over the spliterator (Spliterators.iterator,
 * TSpliteratorsModernSupport) then returned the previous element a second time: Guava's
 * Streams.mapWithIndex takes that path for every non-SUBSIZED stream, and vanilla's
 * TagValueInput.ListWrapper.stream() uses it for every list read from NBT, so the last entity of
 * each list (EntityType.loadEntitiesRecursive: proto-chunk promotion and entity chunk loads) was
 * loaded twice.
 */
public class TSimpleStreamSpliterator<T> implements Spliterator<T> {
    private TSimpleStreamImpl<T> stream;
    private boolean foundItems;
    private boolean done;

    public TSimpleStreamSpliterator(TSimpleStreamImpl<T> stream) {
        this.stream = stream;
    }

    @Override
    public void forEachRemaining(Consumer<? super T> action) {
        while (stream.next(x -> {
            action.accept(x);
            return true;
        })) {
            // next() returns false once the stream is exhausted
        }
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        if (done) {
            return false;
        }
        foundItems = false;
        while (!foundItems && !done) {
            done = !stream.next(x -> {
                action.accept(x);
                foundItems = true;
                return false;
            });
        }
        return foundItems;
    }

    @Override
    public Spliterator<T> trySplit() {
        return null;
    }

    @Override
    public long estimateSize() {
        return stream.estimateSize();
    }

    @Override
    public int characteristics() {
        return 0;
    }
}
