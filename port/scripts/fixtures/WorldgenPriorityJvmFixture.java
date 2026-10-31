import static dev.gaius.browser.BrowserWorldgenDispatcherScheduler.STOP_BUDGET;
import static dev.gaius.browser.BrowserWorldgenDispatcherScheduler.STOP_GENERATION;
import static dev.gaius.browser.BrowserWorldgenDispatcherScheduler.STOP_IDLE;
import static dev.gaius.browser.BrowserWorldgenDispatcherScheduler.STOP_NEXT_GENERATION;

import dev.gaius.browser.WorldgenDispatcherFixtureRuntime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.util.thread.StrictQueue;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.platform.Platform;

public final class WorldgenPriorityJvmFixture {
    private static final String WORLDGEN = "worldgen-dispatcher";
    private static final int TURN_RUNNABLE_CAP = 1024;
    private static final long BOOKKEEPING_NANOS = 100_000L;
    private static final int RUNNABLES_PER_BUDGET = 20;

    private static final class Gate implements Executor {
        private final Queue<Runnable> pending = new ArrayDeque<>();
        private boolean draining;

        @Override
        public void execute(Runnable command) {
            if (draining) {
                command.run();
            } else {
                pending.add(command);
            }
        }

        void drainOne() {
            Runnable command = pending.poll();
            if (command == null) {
                throw new AssertionError("executor command was not registered");
            }
            draining = true;
            try {
                command.run();
            } finally {
                draining = false;
            }
        }

        int pendingCount() {
            return pending.size();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void reset() {
        Platform.reset();
        WorldgenDispatcherFixtureRuntime.reset();
        TModernRuntimeSupport.calls = 0;
        TModernRuntimeSupport.onYield = null;
    }

    private static StrictQueue.RunnableWithPriority task(int priority, Runnable body) {
        return new StrictQueue.RunnableWithPriority(priority, body);
    }

    private static int stops(int reason) {
        return WorldgenDispatcherFixtureRuntime.stopsByReason[reason];
    }

    public static void main(String[] args) throws Exception {
        bookkeepingFloodIsCountBounded();
        bookkeepingFloodIsWallClockBounded();
        generationRunsAloneAtTurnStart();
        suspendedGenerationIsNeverReentered();
        pumpContinuesThroughMessageTurns();
        pumpRecoversAfterFailingTurn();
        dispatchersKeepIndependentPumps();
        closedAndVanillaExecutorsAreUnchanged();
        inlineBacklogDrainsWithoutRecursion();
        System.out.println("WORLDGEN_PRIORITY_JVM_OK count=4096 cap=" + TURN_RUNNABLE_CAP
                + " budgetRunnables=" + RUNNABLES_PER_BUDGET
                + " generationTurns=isolated messageHops=pumped");
    }

    /** A frozen clock must not turn one Worker turn into an unbounded drain. */
    private static void bookkeepingFloodIsCountBounded() {
        reset();
        Gate gate = new Gate();
        PriorityConsecutiveExecutor dispatcher = new PriorityConsecutiveExecutor(4, gate, WORLDGEN);
        AtomicInteger count = new AtomicInteger();
        final int taskCount = 4_096;
        for (int i = 0; i < taskCount; i++) {
            int expected = i;
            dispatcher.schedule(dispatcher.wrapRunnable(() -> {
                int actual = count.getAndIncrement();
                check(actual == expected, "FIFO mismatch: expected " + expected + " got " + actual);
            }));
        }
        gate.drainOne();
        check(count.get() == TURN_RUNNABLE_CAP && dispatcher.hasWork() && gate.pendingCount() == 0
                        && Platform.pendingThreads() == 1,
                "first bookkeeping turn was not bounded by the runnable cap: " + count.get());
        while (count.get() < taskCount) {
            int before = count.get();
            Platform.runNextThread();
            check(count.get() == before && gate.pendingCount() == 1,
                    "deferred callback bypassed the executor boundary");
            gate.drainOne();
            check(count.get() - before == Math.min(TURN_RUNNABLE_CAP, taskCount - before),
                    "bookkeeping turn did not drain up to its cap");
        }
        check(!dispatcher.hasWork() && gate.pendingCount() == 0 && Platform.pendingThreads() == 0,
                "bookkeeping backlog did not drain cleanly");
        check(Platform.startedThreads() == taskCount / TURN_RUNNABLE_CAP - 1,
                "bookkeeping backlog used more than one deferred hop per bounded turn");
        check(stops(STOP_BUDGET) == 3 && stops(STOP_IDLE) == 1
                        && WorldgenDispatcherFixtureRuntime.runnables == taskCount,
                "bookkeeping turns reported the wrong stop reasons");
    }

    /** Each bookkeeping runnable costs 0.1 ms here, so the 2 ms budget admits 20 per turn. */
    private static void bookkeepingFloodIsWallClockBounded() {
        reset();
        Gate gate = new Gate();
        PriorityConsecutiveExecutor dispatcher = new PriorityConsecutiveExecutor(4, gate, WORLDGEN);
        AtomicInteger count = new AtomicInteger();
        for (int i = 0; i < 5 * RUNNABLES_PER_BUDGET; i++) {
            dispatcher.schedule(task(i % 3, () -> {
                count.incrementAndGet();
                WorldgenDispatcherFixtureRuntime.nanoTime += BOOKKEEPING_NANOS;
            }));
        }
        List<Integer> turns = new ArrayList<>();
        gate.drainOne();
        turns.add(count.get());
        while (Platform.pendingThreads() > 0) {
            int before = count.get();
            Platform.runNextThread();
            gate.drainOne();
            turns.add(count.get() - before);
        }
        check(turns.equals(List.of(20, 20, 20, 20, 20)),
                "bookkeeping turns ignored the wall-clock budget: " + turns);
        check(stops(STOP_BUDGET) == 4 && stops(STOP_IDLE) == 1 && !dispatcher.hasWork(),
                "wall-clock bounded turns reported the wrong stop reasons");
    }

    /** Priority 3 (ChunkTaskDispatcher.pollTask) may suspend: never after other work, never followed. */
    private static void generationRunsAloneAtTurnStart() {
        reset();
        Gate gate = new Gate();
        PriorityConsecutiveExecutor dispatcher = new PriorityConsecutiveExecutor(4, gate, WORLDGEN);
        List<String> order = new ArrayList<>();
        dispatcher.schedule(task(0, () -> order.add("a")));
        dispatcher.schedule(task(3, () -> {
            order.add("G1");
            // Work queued by a running generation poll waits for a later turn.
            dispatcher.schedule(task(0, () -> order.add("e")));
            check(!order.contains("e"), "generation poll re-entered its own dispatcher");
        }));
        dispatcher.schedule(task(2, () -> order.add("b")));
        dispatcher.schedule(task(0, () -> order.add("c")));
        dispatcher.schedule(task(3, () -> order.add("G2")));
        dispatcher.schedule(task(1, () -> order.add("d")));
        List<List<String>> turns = new ArrayList<>();
        gate.drainOne();
        turns.add(List.copyOf(order));
        while (Platform.pendingThreads() > 0) {
            int before = order.size();
            Platform.runNextThread();
            gate.drainOne();
            turns.add(List.copyOf(order.subList(before, order.size())));
        }
        check(turns.equals(List.of(
                        List.of("a", "c", "d", "b"),
                        List.of("G1"),
                        List.of("e"),
                        List.of("G2"))),
                "generation polls did not start fresh turns: " + turns);
        check(stops(STOP_NEXT_GENERATION) == 2 && stops(STOP_GENERATION) == 2
                        && !dispatcher.hasWork() && Platform.pendingThreads() == 0,
                "generation boundaries reported the wrong stop reasons");
    }

    /**
     * Models a runUntilWait continuation parked across browser turns with a blocked Java thread:
     * scheduling more work meanwhile must neither enter the dispatcher nor queue a second turn.
     */
    private static void suspendedGenerationIsNeverReentered() throws Exception {
        reset();
        Gate gate = new Gate();
        PriorityConsecutiveExecutor dispatcher = new PriorityConsecutiveExecutor(4, gate, WORLDGEN);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger bookkeeping = new AtomicInteger();
        List<Integer> order = new ArrayList<>();
        dispatcher.schedule(task(3, () -> {
            entered.countDown();
            try {
                check(release.await(10, TimeUnit.SECONDS), "suspended generation was never released");
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
        }));
        Throwable[] failure = new Throwable[1];
        Thread suspended = new Thread(() -> {
            try {
                gate.drainOne();
            } catch (Throwable throwable) {
                failure[0] = throwable;
            }
        }, "suspended-generation");
        suspended.start();
        check(entered.await(10, TimeUnit.SECONDS), "generation poll did not start");
        for (int i = 0; i < 50; i++) {
            int expected = i;
            dispatcher.schedule(task(0, () -> {
                order.add(expected);
                bookkeeping.incrementAndGet();
            }));
        }
        check(bookkeeping.get() == 0 && gate.pendingCount() == 0 && Platform.pendingThreads() == 0,
                "dispatcher was re-entered while a generation continuation was suspended");
        release.countDown();
        suspended.join(10_000L);
        check(!suspended.isAlive() && failure[0] == null, "suspended generation did not finish");
        check(bookkeeping.get() == 0 && Platform.pendingThreads() == 1,
                "generation turn ran bookkeeping after resuming instead of deferring");
        Platform.runNextThread();
        gate.drainOne();
        check(bookkeeping.get() == 50 && !dispatcher.hasWork(),
                "bookkeeping queued behind a suspended generation did not drain in one turn");
        for (int i = 0; i < order.size(); i++) {
            check(order.get(i) == i, "bookkeeping queued during suspension lost FIFO order");
        }
    }

    /** Only the first hop of a chain pays Platform.startThread; later turns yield via MessageChannel. */
    private static void pumpContinuesThroughMessageTurns() {
        reset();
        PriorityConsecutiveExecutor dispatcher =
                new PriorityConsecutiveExecutor(4, Runnable::run, WORLDGEN);
        AtomicInteger count = new AtomicInteger();
        dispatcher.schedule(task(3, () -> {
            for (int i = 0; i < 3 * RUNNABLES_PER_BUDGET; i++) {
                dispatcher.schedule(task(0, () -> {
                    count.incrementAndGet();
                    WorldgenDispatcherFixtureRuntime.nanoTime += BOOKKEEPING_NANOS;
                }));
            }
        }));
        check(count.get() == 0 && Platform.pendingThreads() == 1
                        && WorldgenDispatcherFixtureRuntime.threadHops == 1,
                "inline generation turn did not defer its bookkeeping to one pump");
        List<Integer> boundaries = new ArrayList<>();
        AtomicInteger lateArrivals = new AtomicInteger();
        TModernRuntimeSupport.onYield = () -> {
            boundaries.add(count.get());
            if (boundaries.size() == 1) {
                // Other macrotasks may schedule while the pump waits; that only enqueues.
                dispatcher.schedule(task(1, lateArrivals::incrementAndGet));
                check(count.get() == boundaries.get(0) && lateArrivals.get() == 0,
                        "dispatcher ran inside a MessageChannel hop");
            }
        };
        Platform.runNextThread();
        check(count.get() == 3 * RUNNABLES_PER_BUDGET && lateArrivals.get() == 1
                        && !dispatcher.hasWork(),
                "pump did not drain the chain across message turns");
        check(boundaries.equals(List.of(20, 40, 60)),
                "each message turn did not run exactly one budgeted dispatcher turn: " + boundaries);
        check(Platform.startedThreads() == 1 && Platform.pendingThreads() == 0
                        && WorldgenDispatcherFixtureRuntime.threadHops == 1
                        && WorldgenDispatcherFixtureRuntime.messageHops == 3
                        && TModernRuntimeSupport.calls == 3,
                "continuation hops fell back to clamped Platform.startThread timers");
    }

    /** A failing pumped turn keeps its already re-registered continuation alive. */
    private static void pumpRecoversAfterFailingTurn() {
        reset();
        PriorityConsecutiveExecutor dispatcher =
                new PriorityConsecutiveExecutor(4, Runnable::run, WORLDGEN);
        List<String> order = new ArrayList<>();
        dispatcher.schedule(task(3, () -> {
            dispatcher.schedule(task(0, () -> order.add("ok1")));
            dispatcher.schedule(task(0, () -> { throw new ExpectedFailure(); }));
            dispatcher.schedule(task(0, () -> order.add("ok2")));
        }));
        check(Platform.pendingThreads() == 1, "generation turn did not defer its follow-up");
        try {
            Platform.runNextThread();
            throw new AssertionError("expected task failure");
        } catch (ExpectedFailure expected) {
            // TeaVM reports the escaped exception for this thread only.
        }
        check(order.equals(List.of("ok1")) && dispatcher.hasWork() && Platform.pendingThreads() == 1,
                "failing turn lost the dispatcher continuation");
        Platform.runNextThread();
        check(order.equals(List.of("ok1", "ok2")) && !dispatcher.hasWork()
                        && Platform.pendingThreads() == 0,
                "dispatcher did not recover after a failing pumped turn");

        Gate errorGate = new Gate();
        PriorityConsecutiveExecutor errorDispatcher =
                new PriorityConsecutiveExecutor(4, errorGate, WORLDGEN);
        AtomicInteger recoveredCount = new AtomicInteger();
        errorDispatcher.schedule(errorDispatcher.wrapRunnable(() -> { throw new ExpectedFailure(); }));
        errorDispatcher.schedule(errorDispatcher.wrapRunnable(recoveredCount::incrementAndGet));
        try {
            errorGate.drainOne();
            throw new AssertionError("expected task failure");
        } catch (ExpectedFailure expected) {
            // The patched catch path must leave the executor schedulable.
        }
        check(errorDispatcher.hasWork() && errorGate.pendingCount() == 0
                        && Platform.pendingThreads() == 1,
                "exception recovery did not retain its deferred turn");
        Platform.runNextThread();
        errorGate.drainOne();
        check(recoveredCount.get() == 1, "exception path did not recover");
    }

    /** Another dispatcher deferring during a pumped turn gets its own chain, not a shared queue. */
    private static void dispatchersKeepIndependentPumps() {
        reset();
        PriorityConsecutiveExecutor first =
                new PriorityConsecutiveExecutor(4, Runnable::run, WORLDGEN);
        PriorityConsecutiveExecutor second =
                new PriorityConsecutiveExecutor(4, Runnable::run, WORLDGEN);
        AtomicInteger secondCount = new AtomicInteger();
        first.schedule(task(3, () -> first.schedule(task(0, () ->
                second.schedule(task(3, () -> second.schedule(task(0,
                        secondCount::incrementAndGet))))))));
        check(Platform.pendingThreads() == 1, "first dispatcher did not start its pump");
        Platform.runNextThread();
        check(secondCount.get() == 0 && Platform.pendingThreads() == 1
                        && WorldgenDispatcherFixtureRuntime.threadHops == 2,
                "second dispatcher did not receive an independent pump");
        Platform.runNextThread();
        check(secondCount.get() == 1 && !first.hasWork() && !second.hasWork()
                        && Platform.pendingThreads() == 0,
                "independent dispatcher pump did not drain");
    }

    private static void closedAndVanillaExecutorsAreUnchanged() {
        reset();
        Gate closedGate = new Gate();
        PriorityConsecutiveExecutor closed = new PriorityConsecutiveExecutor(4, closedGate, WORLDGEN);
        closed.close();
        closed.schedule(closed.wrapRunnable(() -> { throw new AssertionError("closed task ran"); }));
        check(!closed.hasWork() && closedGate.pendingCount() == 0,
                "closed dispatcher reports work");

        AtomicInteger vanillaCount = new AtomicInteger();
        PriorityConsecutiveExecutor vanilla =
                new PriorityConsecutiveExecutor(4, Runnable::run, "dispatcher");
        vanilla.schedule(vanilla.wrapRunnable(vanillaCount::incrementAndGet));
        vanilla.schedule(task(3, vanillaCount::incrementAndGet));
        check(vanillaCount.get() == 2 && !vanilla.hasWork(), "vanilla dispatcher behavior changed");

        AtomicInteger nullNameCount = new AtomicInteger();
        PriorityConsecutiveExecutor nullName =
                new PriorityConsecutiveExecutor(4, Runnable::run, null);
        nullName.schedule(nullName.wrapRunnable(nullNameCount::incrementAndGet));
        check(nullNameCount.get() == 1 && !nullName.hasWork(),
                "null-name dispatcher behavior changed");
        check(Platform.startedThreads() == 0 && WorldgenDispatcherFixtureRuntime.turns == 0,
                "non-worldgen executors entered the browser dispatcher scheduler");
    }

    /**
     * A synchronous executor used to re-enter run() from registerForExecution() once per queued
     * runnable, so a large backlog overflowed the stack. The trampoline drains it iteratively, in
     * order, including runnables queued mid-drain, and a failing runnable still propagates after
     * the rest of the backlog ran.
     */
    private static void inlineBacklogDrainsWithoutRecursion() throws Exception {
        reset();
        int backlog = 200_000;
        List<Integer> order = new ArrayList<>();
        PriorityConsecutiveExecutor light = new PriorityConsecutiveExecutor(4, Runnable::run, "light");
        Throwable[] failure = new Throwable[1];
        // A small stack makes the old one-frame-chain-per-runnable drain fail deterministically.
        Thread thread = new Thread(null, () -> {
            try {
                light.schedule(task(0, () -> {
                    for (int i = 0; i < backlog; i++) {
                        int id = i;
                        light.schedule(task(1, () -> {
                            order.add(id);
                            if (id == backlog / 2) {
                                light.schedule(task(2, () -> order.add(-1)));
                            }
                        }));
                    }
                }));
            } catch (Throwable throwable) {
                failure[0] = throwable;
            }
        }, "inline-backlog", 512 * 1024);
        thread.start();
        thread.join();
        check(failure[0] == null, "inline backlog drain failed: " + failure[0]);
        check(order.size() == backlog + 1 && !light.hasWork(), "inline backlog incomplete: " + order.size());
        for (int i = 0; i < backlog; i++) {
            check(order.get(i) == i, "inline backlog order changed at " + i);
        }
        check(order.get(backlog) == -1, "runnable queued mid-drain did not run last");

        // Vanilla contract: the rest of the backlog still runs, then the last failure escapes.
        List<String> log = new ArrayList<>();
        PriorityConsecutiveExecutor failing = new PriorityConsecutiveExecutor(4, Runnable::run, "light");
        try {
            failing.schedule(task(0, () -> {
                for (int i = 0; i < 5; i++) {
                    int id = i;
                    failing.schedule(task(1, () -> {
                        log.add("t" + id);
                        if (id == 1 || id == 3) {
                            throw new ExpectedFailure(id);
                        }
                    }));
                }
            }));
        } catch (ExpectedFailure expected) {
            log.add("caught" + expected.id);
        }
        failing.schedule(task(1, () -> log.add("after")));
        check(log.equals(List.of("t0", "t1", "t2", "t3", "t4", "caught3", "after"))
                && !failing.hasWork(),
                "failing inline runnable changed the backlog contract: " + log);

        // Failing runnables must not grow the stack either.
        int failingBacklog = 100_000;
        int[] ran = new int[1];
        Throwable[] escaped = new Throwable[1];
        PriorityConsecutiveExecutor failingLight =
                new PriorityConsecutiveExecutor(4, Runnable::run, "light");
        Thread failingThread = new Thread(null, () -> {
            try {
                failingLight.schedule(task(0, () -> {
                    for (int i = 0; i < failingBacklog; i++) {
                        int id = i;
                        failingLight.schedule(task(1, () -> {
                            ran[0]++;
                            throw new ExpectedFailure(id);
                        }));
                    }
                }));
            } catch (Throwable throwable) {
                escaped[0] = throwable;
            }
        }, "failing-backlog", 512 * 1024);
        failingThread.start();
        failingThread.join();
        check(escaped[0] instanceof ExpectedFailure last && last.id == failingBacklog - 1
                && ran[0] == failingBacklog && !failingLight.hasWork(),
                "failing inline backlog: ran=" + ran[0] + " escaped=" + escaped[0]);
        check(Platform.startedThreads() == 0 && WorldgenDispatcherFixtureRuntime.turns == 0,
                "trampolined executors entered the browser dispatcher scheduler");
    }

    private static final class ExpectedFailure extends RuntimeException {
        final int id;

        ExpectedFailure() {
            this(-1);
        }

        ExpectedFailure(int id) {
            this.id = id;
        }
    }
}
