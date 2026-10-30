package dev.gaius.parity;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The serial drain behind {@link TickPacketRecorder#settle}. It only calls vanilla's own chunk
 * main-thread executor ({@code ServerChunkCache.pollTask}, which also applies pending ticket
 * updates and schedules light work) and never changes what a task does. The difference to
 * vanilla's {@code managedBlock} pumping is timing only: the background workers never run while the
 * server thread runs vanilla code.
 * <ol>
 * <li>Outside a settle the workers are held, so work the server thread submits in a tick or a
 * task queues up instead of racing it.
 * <li>Inside a settle the workers are released and drained first; a main-thread task then runs only
 * with the workers held again and the IO it started complete, so the queues the workers drain next
 * hold the same tasks in every run instead of whatever had arrived when a worker happened to look.
 * </ol>
 */
final class TickParitySettle {
    private static final long LIMIT_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final long CONFIRM_MILLIS = 2;

    private TickParitySettle() {
    }

    /** Drains all work; {@code held} is the current hold or null. Returns the hold taken afterwards. */
    static Runnable run(MinecraftServer server, TickParitySettleProbe probe, Runnable held) {
        long deadline = System.nanoTime() + LIMIT_NANOS;
        Runnable hold = held;
        try {
            while (true) {
                if (hold != null) {
                    hold.run();
                    hold = null;
                }
                probe.awaitIdle(deadline);
                hold = probe.holdBackground();
                boolean ran = pollOne(server);
                probe.awaitIoIdle(deadline);
                if (ran) continue;
                hold.run();
                hold = null;
                // A task handed to an executor thread but not yet started can be invisible to a probe
                // for a moment; idle must hold again after a pause before the drain is accepted.
                pause();
                if (probe.idle() && !pending(server)) return probe.holdBackground();
            }
        } catch (RuntimeException | Error failure) {
            if (hold != null) hold.run();
            throw failure;
        }
    }

    private static boolean pollOne(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().pollTask()) return true;
        }
        return false;
    }

    private static boolean pending(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getPendingTasksCount() > 0) return true;
        }
        return false;
    }

    /**
     * Short waits yield without parking: a park costs about a millisecond here and a settle can run
     * thousands of tasks. (Thread.onSpinWait is not in TeaVM's class library.)
     */
    static void backOff(Object probe, int spins, long deadline) {
        if (System.nanoTime() > deadline) throw new IllegalStateException("tick parity settle timed out; capture refused: " + probe);
        if (spins < 4000) Thread.yield();
        else LockSupport.parkNanos(100_000L);
    }

    private static void pause() {
        try {
            Thread.sleep(CONFIRM_MILLIS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tick parity settle interrupted", error);
        }
    }
}
