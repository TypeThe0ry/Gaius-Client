package org.teavm.classlib.java.util.stream;

import org.teavm.classlib.java.util.TSpliterator;

public final class TPrimitiveStreamSupport {
    private TPrimitiveStreamSupport() {
    }

    public static TIntStream intStream(TSpliterator.OfInt spliterator, boolean parallel) {
        TIntStream.Builder builder = TIntStream.builder();
        spliterator.forEachRemaining((int value) -> builder.accept(value));
        return builder.build();
    }

    public static TLongStream longStream(TSpliterator.OfLong spliterator, boolean parallel) {
        TLongStream.Builder builder = TLongStream.builder();
        spliterator.forEachRemaining((long value) -> builder.accept(value));
        return builder.build();
    }
}
