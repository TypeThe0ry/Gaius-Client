package dev.gaius.browser;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;

/**
 * Bounds and resumes integrated-server worldgen dispatcher turns in the browser Worker.
 *
 * <p>The patched {@code AbstractConsecutiveExecutor.run()} asks {@link #continueTurn} after
 * every runnable. {@code ChunkTaskDispatcher} queues level changes, releases and submissions
 * at StrictQueue priorities 0-2; those only update dispatcher bookkeeping and cannot suspend,
 * so they drain inline under a small wall-clock budget. Priority 3 is the generation poll whose
 * {@code ChunkGenerationTask.runUntilWait} may suspend: it only ever runs as the first runnable
 * of a turn, and the turn ends after it. The dispatcher stays RUNNING from the end of one turn
 * until the next one starts, so a suspended generation continuation is never re-entered.
 */
public final class BrowserWorldgenDispatcherScheduler {
    /** ChunkTaskDispatcher.pollTask priority; the only dispatcher runnable that can suspend. */
    public static final int GENERATION_PRIORITY = 3;
    public static final int CONTINUE_TURN = 0;
    public static final int STOP_IDLE = 1;
    public static final int STOP_GENERATION = 2;
    public static final int STOP_NEXT_GENERATION = 3;
    public static final int STOP_BUDGET = 4;
    public static final int STOP_UNCLASSIFIED = 5;
    // Keep bookkeeping bounded before the next generation turn. The vanilla generation boundary
    // remains a turn boundary even when a poll completes synchronously.
    private static final long INLINE_BUDGET_NANOS = 2_000_000L;
    // Coarse or frozen performance clocks must not turn the budget into an unbounded drain.
    private static final int MAX_RUNNABLES_PER_TURN = 1024;
    private static final int HOP_THREAD = 0;
    private static final int HOP_MESSAGE = 1;
    private static final Map<Runnable, Pump> PUMPS = new IdentityHashMap<>();

    private BrowserWorldgenDispatcherScheduler() {
    }

    public static long beginTurn() {
        return nowNanos();
    }

    /**
     * Returns {@link #CONTINUE_TURN} when the next queued runnable may run in this turn,
     * otherwise the reason the turn ends. {@code ranPriority} is the priority of the runnable
     * that just ran and {@code nextPriority} the head of the queue; both are -1 when unknown.
     */
    public static int continueTurn(long startedAtNanos, int executed, int ranPriority,
            int nextPriority) {
        if (ranPriority < 0) {
            return STOP_UNCLASSIFIED;
        }
        if (ranPriority >= GENERATION_PRIORITY) {
            return STOP_GENERATION;
        }
        if (nextPriority < 0) {
            return STOP_IDLE;
        }
        if (nextPriority >= GENERATION_PRIORITY) {
            return STOP_NEXT_GENERATION;
        }
        if (executed >= MAX_RUNNABLES_PER_TURN
                || nowNanos() - startedAtNanos >= INLINE_BUDGET_NANOS) {
            return STOP_BUDGET;
        }
        return CONTINUE_TURN;
    }

    public static void endTurn(long startedAtNanos, int executed, int reason) {
        double elapsedMillis = Math.max(0L, nowNanos() - startedAtNanos) / 1_000_000.0;
        recordTurnTelemetry(executed, reason == CONTINUE_TURN ? STOP_IDLE : reason, elapsedMillis);
    }

    /**
     * Runs {@code dispatcher} again in a fresh Worker turn. A dispatcher re-registering from the
     * tail of the turn its pump is executing continues through a MessageChannel macrotask;
     * only the first hop of a chain pays Platform.startThread's clamped setTimeout.
     */
    public static void defer(Executor executor, Runnable dispatcher) {
        Pump pump = PUMPS.get(dispatcher);
        if (pump != null) {
            pump.resumeRequested = true;
            return;
        }
        pump = new Pump(executor, dispatcher);
        PUMPS.put(dispatcher, pump);
        recordHopTelemetry(HOP_THREAD);
        HostLoop.startThread(pump);
    }

    private static long nowNanos() {
        return System.nanoTime();
    }

    /**
     * One TeaVM thread per active dispatcher chain. Suspension inside {@code execute} only
     * parks this dispatcher's chain; other dispatchers start their own pumps.
     */
    private static final class Pump implements Runnable {
        private final Executor executor;
        private final Runnable dispatcher;
        private boolean resumeRequested;

        private Pump(Executor executor, Runnable dispatcher) {
            this.executor = executor;
            this.dispatcher = dispatcher;
        }

        @Override
        public void run() {
            boolean completed = false;
            try {
                while (true) {
                    resumeRequested = false;
                    executor.execute(dispatcher);
                    if (!resumeRequested) {
                        break;
                    }
                    // AsyncCallback resumption re-enters this TeaVM thread, so the next turn
                    // keeps its continuation context without a raw JavaScript callback.
                    recordHopTelemetry(HOP_MESSAGE);
                    TModernRuntimeSupport.yieldToEventLoop(0);
                }
                completed = true;
            } finally {
                PUMPS.remove(dispatcher);
                if (!completed && resumeRequested) {
                    // The failing turn already re-registered the dispatcher. Keep that
                    // continuation alive while the exception reaches TeaVM's thread reporter.
                    defer(executor, dispatcher);
                }
            }
        }
    }

    @JSBody(params = {"runnables", "reason", "elapsedMillis"}, script = """
            try {
              const root = globalThis.__gaiusWorldgenStats ||
                (globalThis.__gaiusWorldgenStats = {});
              // Nested so the flat scalar heartbeat budget keeps its existing slice fields.
              const stats = root.dispatcher || (root.dispatcher = {});
              const count = Math.max(0, Number(runnables) || 0);
              const elapsed = Math.max(0, Number(elapsedMillis) || 0);
              stats.turns = (Number(stats.turns) || 0) + 1;
              stats.runnables = (Number(stats.runnables) || 0) + count;
              stats.inlineRunnables = (Number(stats.inlineRunnables) || 0) +
                Math.max(0, count - 1);
              stats.lastTurnRunnables = count;
              stats.maxTurnRunnables = Math.max(Number(stats.maxTurnRunnables) || 0, count);
              stats.runnablesPerTurn = Math.round(stats.runnables / stats.turns * 100) / 100;
              const reasons = ['continue', 'idle', 'generation', 'nextGeneration', 'budget',
                'unclassified'];
              const key = (reasons[reason | 0] || 'unclassified') + 'Stops';
              stats[key] = (Number(stats[key]) || 0) + 1;
              if ((reason | 0) !== 2 && (reason | 0) !== 5) {
                // Turns without a generation runnable never suspend; this is the inline budget.
                stats.maxBookkeepingTurnMillis = Math.max(
                  Number(stats.maxBookkeepingTurnMillis) || 0,
                  Math.round(elapsed * 1000) / 1000
                );
              }
            } catch (ignored) {
              // Diagnostic telemetry is fail-open.
            }
            """)
    private static native void recordTurnTelemetry(int runnables, int reason, double elapsedMillis);

    @JSBody(params = "kind", script = """
            try {
              const root = globalThis.__gaiusWorldgenStats ||
                (globalThis.__gaiusWorldgenStats = {});
              const stats = root.dispatcher || (root.dispatcher = {});
              stats.deferredHops = (Number(stats.deferredHops) || 0) + 1;
              const key = (kind | 0) === 1 ? 'messageHops' : 'threadHops';
              stats[key] = (Number(stats[key]) || 0) + 1;
            } catch (ignored) {
              // Diagnostic telemetry is fail-open.
            }
            """)
    private static native void recordHopTelemetry(int kind);
}
