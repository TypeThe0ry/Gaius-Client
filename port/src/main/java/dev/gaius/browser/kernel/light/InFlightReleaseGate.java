package dev.gaius.browser.kernel.light;

import java.util.concurrent.atomic.AtomicBoolean;

/** Per-job one-shot release shared by worker callbacks and the watchdog. */
final class InFlightReleaseGate {
    private final AtomicBoolean released = new AtomicBoolean();

    boolean tryRelease() {
        return released.compareAndSet(false, true);
    }
}
