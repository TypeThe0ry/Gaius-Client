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

package org.teavm.classlib.java.util.stream.intimpl;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/** TeaVM 0.15's distinct int stream with one set for the whole stream (see TDistinctStreamImpl). */
public class TDistinctIntStreamImpl extends TWrappingIntStreamImpl {
    private final Set<Integer> seen = new HashSet<>();

    public TDistinctIntStreamImpl(TSimpleIntStreamImpl innerStream) {
        super(innerStream);
    }

    @Override
    protected IntPredicate wrap(IntPredicate consumer) {
        return e -> !seen.add(e) || consumer.test(e);
    }
}
