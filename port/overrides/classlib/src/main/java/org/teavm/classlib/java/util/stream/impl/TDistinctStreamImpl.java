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
 *  Modified for Gaius: Retained seen values across the lifetime of the stream.
 */

package org.teavm.classlib.java.util.stream.impl;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * TeaVM 0.15's distinct stream, remembering the elements it saw for the whole stream. TeaVM
 * created the set in wrap(), that is once per next() call, and iterator(), spliterator(),
 * skip() and concat() pull through several next() calls: every pull after the first let
 * duplicates through again.
 */
public class TDistinctStreamImpl<T> extends TWrappingStreamImpl<T, T> {
    private final Set<T> seen = new HashSet<>();

    public TDistinctStreamImpl(TSimpleStreamImpl<T> innerStream) {
        super(innerStream);
    }

    @Override
    protected Predicate<T> wrap(Predicate<? super T> consumer) {
        return e -> !seen.add(e) || consumer.test(e);
    }
}
