package org.teavm.classlib.java.util.concurrent;

import java.time.Duration;

public final class TTimeUnitModernSupport {
    private TTimeUnitModernSupport() {
    }

    public static long convert(TTimeUnit unit, Duration duration) {
        return unit.convert(duration.toNanos(), TTimeUnit.NANOSECONDS);
    }
}
