package dev.gaius.parity;

/**
 * The capture host's view of vanilla's background (worldgen, light, entity deserialization) and
 * IO executors as it schedules them, used by {@link TickPacketRecorder#settle}. The JVM host's
 * probe runs over TickParityExecutors (port/scripts/packet-parity); the WasmGC Worker's is
 * BrowserSettleProbe (src/target-wasmgc). Without one no capture starts.
 *
 * <p>The default wait methods poll with the back-off of {@link TickParitySettle}, which suits a
 * host whose executor threads run in parallel with the server thread. A host whose threads take
 * turns on one Worker thread overrides them to block until the executor reports a change.
 */
public interface TickParitySettleProbe {
    /** No queued, handed-off or running task on the background or IO executors (or other async work). */
    boolean idle();

    /** As {@link #idle()}, for the IO executors only. */
    boolean ioIdle();

    /** Occupies every background worker; returns the release once they are held. */
    Runnable holdBackground();

    /** Returns once {@link #idle()} holds; throws after {@code deadlineNanos} (System.nanoTime). */
    default void awaitIdle(long deadlineNanos) {
        for (int spins = 0; !idle(); spins++) TickParitySettle.backOff(this, spins, deadlineNanos);
    }

    /** Returns once {@link #ioIdle()} holds; throws after {@code deadlineNanos} (System.nanoTime). */
    default void awaitIoIdle(long deadlineNanos) {
        for (int spins = 0; !ioIdle(); spins++) TickParitySettle.backOff(this, spins, deadlineNanos);
    }

    /**
     * The recording ends: before the {@code complete} footer ({@code complete} true) or the
     * {@code failure} row (false). The background workers are released right after.
     */
    default void captureEnding(boolean complete) {
    }
}
