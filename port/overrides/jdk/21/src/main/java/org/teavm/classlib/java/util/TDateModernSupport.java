package org.teavm.classlib.java.util;

import java.time.Instant;

public final class TDateModernSupport {
    private TDateModernSupport() {
    }

    public static TDate from(Instant instant) {
        return new TDate(instant.toEpochMilli());
    }

    public static Instant toInstant(TDate date) {
        return Instant.ofEpochMilli(date.getTime());
    }
}
