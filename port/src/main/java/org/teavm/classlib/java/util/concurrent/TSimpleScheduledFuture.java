package org.teavm.classlib.java.util.concurrent;

import dev.gaius.browser.HostLoop;

final class TSimpleScheduledFuture<V> extends TCompletableFuture<V>
        implements TScheduledFuture<V> {
    private long deadlineMillis;
    private int scheduleId = -1;

    void setDeadlineMillis(long deadlineMillis) {
        this.deadlineMillis = deadlineMillis;
    }

    void setScheduleId(int scheduleId) {
        this.scheduleId = scheduleId;
    }

    void clearScheduleId() {
        scheduleId = -1;
    }

    long remainingMillis() {
        return Math.max(0L, deadlineMillis - System.currentTimeMillis());
    }

    @Override
    public long getDelay(TTimeUnit unit) {
        return unit.convert(remainingMillis(), TTimeUnit.MILLISECONDS);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!super.cancel(mayInterruptIfRunning)) {
            return false;
        }
        if (scheduleId >= 0) {
            HostLoop.killSchedule(scheduleId);
            scheduleId = -1;
        }
        return true;
    }

    @Override
    public int compareTo(TDelayed other) {
        return Long.compare(getDelay(TTimeUnit.NANOSECONDS),
                other.getDelay(TTimeUnit.NANOSECONDS));
    }
}
