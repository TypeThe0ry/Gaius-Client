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

package org.teavm.classlib.java.util.stream.longimpl;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongPredicate;

/** TeaVM 0.15's distinct long stream with one set for the whole stream (see TDistinctStreamImpl). */
public class TDistinctLongStreamImpl extends TWrappingLongStreamImpl {
    private final Set<Long> seen = new HashSet<>();

    public TDistinctLongStreamImpl(TSimpleLongStreamImpl innerStream) {
        super(innerStream);
    }

    @Override
    protected LongPredicate wrap(LongPredicate consumer) {
        return e -> !seen.add(e) || consumer.test(e);
    }
}
