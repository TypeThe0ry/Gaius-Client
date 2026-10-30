package dev.gaius.parity;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

/**
 * JVM unit test of the WasmGC settle probe (src/target-wasmgc/java/dev/gaius/parity) without a TeaVM
 * build: the production probe, executors and TickParitySettle compiled against small Minecraft stubs.
 * Run by settle-probe-jvm.mjs. The last case runs one seeded synthetic workload (main-thread tasks,
 * background tasks, self-submissions, joins, IO, a consecutive dispatcher) through TickParitySettle
 * with the JVM harness's TickParityExecutors and with the browser schedule and requires identical
 * execution histories.
 */
public final class TickParityProbeTest {
    private static final long LIMIT = TimeUnit.SECONDS.toNanos(20);
    private static final List<String> failures = new ArrayList<>();
    private static final List<String> passed = new ArrayList<>();
    private static int checks;

    private TickParityProbeTest() {
    }

    interface Case {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        test("inline-before-capture", TickParityProbeTest::inlineBeforeCapture);
        test("fifo-order-and-pending", TickParityProbeTest::fifoOrderAndPending);
        test("self-submission-runs-inline", TickParityProbeTest::selfSubmissionInline);
        test("hold-limit-voids-capture", TickParityProbeTest::holdLimit);
        test("hold-releases-when-follow-up-work-is-queued", TickParityProbeTest::holdDrainsQueuedWork);
        test("nesting-limit-fails-closed", TickParityProbeTest::nestingLimit);
        test("dispatcher-worker-trampoline-equals-recursion", TickParityProbeTest::workerTrampolineEqualsRecursion);
        test("io-completes-before-hold-release", TickParityProbeTest::ioBeforeRelease);
        test("kernel-jobs-keep-settle-open-and-fail-closed", TickParityProbeTest::kernelGauge);
        test("dispatcher-pump-hop-fails-closed", TickParityProbeTest::dispatcherPump);
        test("queued-run-queue-activations-keep-settle-open", TickParityProbeTest::runQueueBacklog);
        test("capture-end-report-and-caller-mode", TickParityProbeTest::captureEnd);
        test("settle-history-equals-jvm-schedule", TickParityProbeTest::equivalence);
        System.out.println("PROBE_TEST {\"checks\":" + checks + ",\"passed\":" + passed.size() + ",\"failed\":" + failures.size() + "}");
        for (String failure : failures) System.out.println("PROBE_TEST_FAILURE " + failure);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static void test(String name, Case body) {
        int before = failures.size();
        try {
            body.run();
        } catch (Throwable error) {
            failures.add(name + ": " + error);
            error.printStackTrace(System.out);
        }
        if (failures.size() == before) passed.add(name);
        System.out.println((failures.size() == before ? "PASS " : "FAIL ") + name);
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }

    private static TickParitySerialExecutor serial(long holdLimitNanos) {
        return new TickParitySerialExecutor("Worker-Main-1", holdLimitNanos);
    }

    private static void awaitIdle(TickParitySerialExecutor serial) {
        long deadline = System.nanoTime() + LIMIT;
        while (!serial.idle()) {
            if (System.nanoTime() > deadline) throw new AssertionError("background never idle: " + serial.describe());
            serial.awaitChange(5);
        }
    }

    private static String role() {
        return Thread.currentThread().getName().startsWith("Worker-") ? "bg" : "main";
    }

    private static void inlineBeforeCapture() {
        TickParitySerialExecutor serial = serial(LIMIT);
        AtomicReference<Thread> ran = new AtomicReference<>();
        serial.execute(() -> ran.set(Thread.currentThread()));
        check(ran.get() == Thread.currentThread(), "before the capture a task runs on the submitting thread");
        check(serial.idle() && serial.pending() == 0, "nothing queued before the capture");
        boolean propagated = false;
        try {
            serial.execute(() -> { throw new IllegalArgumentException("task failure"); });
        } catch (IllegalArgumentException expected) {
            propagated = true;
        }
        check(propagated, "a failing task reaches the submitter, as with the Worker executor");
        serial.shutdown();
        boolean refused = false;
        try {
            serial.execute(() -> { });
        } catch (IllegalStateException expected) {
            refused = true;
        }
        check(refused, "shut down: the Worker executor's IllegalStateException");
    }

    private static void fifoOrderAndPending() throws Exception {
        TickParitySerialExecutor serial = serial(LIMIT);
        serial.beginCapture();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Runnable release = serial.hold();
        check(serial.pending() == 1 && !serial.idle(), "a hold counts as running background work");
        serial.execute(() -> log.add("a " + Thread.currentThread().getName()));
        serial.execute(() -> log.add("b " + Thread.currentThread().getName()));
        Thread.sleep(50);
        check(log.isEmpty(), "queued tasks wait behind the hold");
        check(serial.pending() == 3, "pending covers the hold and the queued tasks");
        release.run();
        awaitIdle(serial);
        check(log.equals(List.of("a Worker-Main-1", "b Worker-Main-1")), "FIFO on the single background thread: " + log);
        check(serial.statistics().contains("\"queuedTasks\":3"), "statistics count queued tasks: " + serial.statistics());
        serial.shutdown();
    }

    private static void selfSubmissionInline() {
        TickParitySerialExecutor serial = serial(LIMIT);
        serial.beginCapture();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        serial.execute(() -> {
            log.add("outer");
            serial.execute(() -> log.add("inner"));
            log.add("outer-after");
            int value = CompletableFuture.supplyAsync(() -> {
                log.add("joined");
                return 7;
            }, serial).join();
            log.add("value " + value);
        });
        awaitIdle(serial);
        check(log.equals(List.of("outer", "inner", "outer-after", "joined", "value 7")),
                "self-submitted tasks run at once, depth first, and a join on one cannot block: " + log);
        check(serial.statistics().contains("\"inlineTasks\":2") && serial.statistics().contains("\"maxInlineDepth\":1"),
                "inline statistics: " + serial.statistics());
        serial.shutdown();
    }

    private static void holdLimit() throws Exception {
        TickParitySerialExecutor serial = serial(TimeUnit.MILLISECONDS.toNanos(200));
        BrowserSettleProbe probe = new BrowserSettleProbe(serial, new TickParityDirectExecutor.Gauge(), new FakeHost(), report -> { });
        probe.begin();
        Runnable release = probe.holdBackground();
        Thread.sleep(400);
        awaitIdle(serial);
        check(serial.broken(), "a hold that outlives its limit voids the capture");
        check(refuses(probe::idle, "broken"), "the probe refuses every later settle");
        release.run();
        serial.shutdown();
    }

    private static void holdDrainsQueuedWork() throws Exception {
        TickParitySerialExecutor serial = serial(LIMIT);
        serial.beginCapture();
        Runnable release = serial.hold();
        AtomicBoolean ran = new AtomicBoolean();
        serial.execute(() -> ran.set(true));
        release.run();
        awaitIdle(serial);
        check(ran.get(), "queued work drains after the hold is released");
        serial.shutdown();
    }

    private static void nestingLimit() {
        TickParitySerialExecutor serial = serial(LIMIT);
        serial.beginCapture();
        AtomicInteger level = new AtomicInteger();
        Runnable[] deep = new Runnable[1];
        deep[0] = () -> {
            if (level.incrementAndGet() < 400) serial.execute(deep[0]);
        };
        PrintStream errors = System.err;
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        try {
            serial.execute(deep[0]);
            awaitIdle(serial);
        } finally {
            System.setErr(errors);
        }
        check(serial.broken(), "nesting beyond the limit marks the schedule broken");
        check(level.get() <= TickParitySerialExecutor.MAX_INLINE_DEPTH + 2, "nesting stops at the limit: " + level.get());
        serial.shutdown();
    }

    /** How a consecutive executor re-registers at the tail of its {@code run()}. */
    enum Registration {
        /** Vanilla AbstractConsecutiveExecutor: {@code registerForExecution}, which recurses on an inline executor. */
        VANILLA,
        /**
         * MinecraftServerWorkerPatcher's patched {@code run()} for every executor but the worldgen
         * dispatcher (and for that one too in a parity Worker, which keeps vanilla's name): a
         * re-registration that re-enters {@code run()} only sets a flag; the outer {@code run()} loops.
         */
        WORKER_TRAMPOLINE,
        /** The production worldgen dispatcher: the next turn is submitted from a new activation. */
        PUMP
    }

    /** A consecutive executor: one step per run, tail re-registration, at most one registration at a time. */
    static final class Dispatcher implements Runnable {
        private final ArrayDeque<Runnable> steps = new ArrayDeque<>();
        private final Executor executor;
        private final Registration registration;
        private boolean running;
        private boolean registering;
        private boolean requested;

        Dispatcher(Executor executor, Registration registration) {
            this.executor = executor;
            this.registration = registration;
        }

        void schedule(Runnable step) {
            synchronized (this) {
                steps.addLast(step);
            }
            registerForExecution();
        }

        /** Vanilla: {@code canBeScheduled() && setRunning()}, then {@code executor.execute(this)}. */
        private void registerForExecution() {
            synchronized (this) {
                if (running || steps.isEmpty()) return;
                running = true;
            }
            if (registration == Registration.PUMP && Thread.currentThread().getName().startsWith("Worker-")) {
                Thread pump = new Thread(() -> executor.execute(this), "pump");
                pump.start();
                try {
                    pump.join();
                } catch (InterruptedException error) {
                    throw new IllegalStateException(error);
                }
                return;
            }
            executor.execute(this);
        }

        private void pollTask() {
            Runnable step;
            synchronized (this) {
                step = steps.pollFirst();
            }
            if (step != null) step.run();
        }

        private synchronized void setSleeping() {
            running = false;
        }

        @Override
        public void run() {
            if (registration != Registration.WORKER_TRAMPOLINE) {
                try {
                    pollTask();
                } finally {
                    setSleeping();
                    registerForExecution();
                }
                return;
            }
            if (registering) {
                requested = true;
                return;
            }
            do {
                pollTask();
                setSleeping();
                requested = false;
                registering = true;
                try {
                    registerForExecution();
                } finally {
                    registering = false;
                }
            } while (requested);
        }
    }

    private static List<String> dispatcherHistory(Registration registration, TickParitySerialExecutor serial) {
        serial.beginCapture();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Dispatcher dispatcher = new Dispatcher(serial, registration);
        Runnable release = serial.hold();
        for (int step = 0; step < 6; step++) {
            int id = step;
            dispatcher.schedule(() -> log.add("step " + id + " " + role()));
        }
        serial.execute(() -> log.add("other " + role()));
        release.run();
        awaitIdle(serial);
        return log;
    }

    private static void workerTrampolineEqualsRecursion() {
        TickParitySerialExecutor recursive = serial(LIMIT);
        List<String> vanilla = dispatcherHistory(Registration.VANILLA, recursive);
        TickParitySerialExecutor trampolined = serial(LIMIT);
        List<String> parity = dispatcherHistory(Registration.WORKER_TRAMPOLINE, trampolined);
        check(vanilla.equals(parity), "trampolined re-registration keeps the recursive order: " + vanilla + " vs " + parity);
        check(parity.get(parity.size() - 1).startsWith("other"), "the dispatcher drains before later queued work: " + parity);
        check(recursive.statistics().contains("\"maxInlineDepth\":5"), "recursion nests per step: " + recursive.statistics());
        check(trampolined.statistics().contains("\"maxInlineDepth\":1"), "the trampoline does not nest: " + trampolined.statistics());
        // The production worldgen deferral hops through a new activation: its next turn queues behind other work.
        TickParitySerialExecutor pumped = serial(LIMIT);
        List<String> production = dispatcherHistory(Registration.PUMP, pumped);
        check(!production.equals(vanilla), "a pump hop reorders the dispatcher against queued work: " + production);
        for (TickParitySerialExecutor executor : List.of(recursive, trampolined, pumped)) executor.shutdown();
    }

    /** Records hold releases so the test can order them against IO completion. */
    static final class RecordingProbe implements TickParitySettleProbe {
        final TickParitySettleProbe delegate;
        final List<String> log;

        RecordingProbe(TickParitySettleProbe delegate, List<String> log) {
            this.delegate = delegate;
            this.log = log;
        }

        @Override
        public boolean idle() {
            return delegate.idle();
        }

        @Override
        public boolean ioIdle() {
            return delegate.ioIdle();
        }

        @Override
        public Runnable holdBackground() {
            Runnable release = delegate.holdBackground();
            return () -> {
                log.add("release");
                release.run();
            };
        }

        @Override
        public void awaitIdle(long deadlineNanos) {
            delegate.awaitIdle(deadlineNanos);
        }

        @Override
        public void awaitIoIdle(long deadlineNanos) {
            delegate.awaitIoIdle(deadlineNanos);
        }
    }

    private static void ioBeforeRelease() throws Exception {
        TickParitySerialExecutor serial = serial(LIMIT);
        TickParityDirectExecutor.Gauge gauge = new TickParityDirectExecutor.Gauge();
        TickParityDirectExecutor io = new TickParityDirectExecutor(gauge);
        BrowserSettleProbe browser = new BrowserSettleProbe(serial, gauge, new FakeHost(), report -> { });
        browser.begin();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        RecordingProbe probe = new RecordingProbe(browser, log);
        ServerChunkCache cache = new ServerChunkCache();
        MinecraftServer server = new MinecraftServer(List.of(new ServerLevel(cache)));
        Runnable hold = TickParitySettle.run(server, probe, null);
        log.clear();
        cache.post(() -> {
            // A main-thread task whose IO is still running on another thread when the task returns.
            CountDownLatch inside = new CountDownLatch(1);
            Thread writer = new Thread(() -> io.execute(() -> {
                inside.countDown();
                log.add("io-start");
                sleep(150);
                log.add("io-done");
            }), "io-caller");
            writer.start();
            await(inside);
            log.add("main-task-returns");
        });
        hold = TickParitySettle.run(server, probe, hold);
        int returned = log.indexOf("main-task-returns");
        int released = log.subList(returned + 1, log.size()).indexOf("release") + returned + 1;
        int done = log.indexOf("io-done");
        check(returned >= 0 && released > returned && done > returned && done < released,
                "IO started by a main-thread task completes before the hold is released: " + log);
        check(gauge.idle() && gauge.tasks() == 1, "the IO gauge saw one task and is idle");
        browser.captureEnding(true);
        hold.run();
        serial.shutdown();
    }

    static final class FakeHost implements BrowserSettleProbe.Host {
        volatile boolean present;
        volatile int inFlight;
        volatile long submitted;
        volatile long pumps;
        volatile long runQueue;

        @Override
        public boolean kernelsPresent() {
            return present;
        }

        @Override
        public int kernelJobsInFlight() {
            return inFlight;
        }

        @Override
        public long kernelJobsSubmitted() {
            return submitted;
        }

        @Override
        public long dispatcherPumps() {
            return pumps;
        }

        @Override
        public long runQueuePending() {
            return runQueue;
        }
    }

    private static boolean refuses(Runnable action, String message) {
        try {
            action.run();
            return false;
        } catch (IllegalStateException expected) {
            return message == null || String.valueOf(expected.getMessage()).contains(message);
        }
    }

    private static void kernelGauge() {
        FakeHost host = new FakeHost();
        host.present = true;
        BrowserSettleProbe refused = new BrowserSettleProbe(serial(LIMIT), new TickParityDirectExecutor.Gauge(), host, report -> { });
        check(refuses(refused::begin, "kernel runtime present"), "a kernel runtime in the Worker refuses the capture");
        host.present = false;
        host.submitted = 3;
        TickParitySerialExecutor serial = serial(LIMIT);
        BrowserSettleProbe probe = new BrowserSettleProbe(serial, new TickParityDirectExecutor.Gauge(), host, report -> { });
        probe.begin();
        check(probe.idle(), "idle with no kernel job in flight");
        host.inFlight = 1;
        check(!probe.idle(), "a kernel job in flight keeps the settle open");
        long started = System.nanoTime();
        check(refuses(() -> probe.awaitIdle(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(60)), "timed out"),
                "a kernel job that never answers times the settle out");
        check(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(55), "the wait suspended instead of returning early");
        Thread answer = new Thread(() -> {
            sleep(40);
            host.inFlight = 0;
        });
        answer.start();
        probe.awaitIdle(System.nanoTime() + LIMIT);
        check(probe.idle(), "the settle continues once the job answered");
        host.submitted = 4;
        check(refuses(probe::idle, "kernel job submitted"), "a kernel submission during the capture fails closed");
        host.submitted = 3;
        host.present = true;
        check(refuses(probe::ioIdle, "kernel runtime appeared"), "a kernel runtime appearing later fails closed");
        serial.shutdown();
    }

    /**
     * A continuation that sits in the Worker's run queue (for example the next batch of a bounded
     * chunk-generation layer) is pending work that no executor shows: a timer that wakes the settling
     * thread can fire before the queue's next slice, and a settle that returned then would end one
     * generation step early. The probe is open until the queue is empty.
     */
    private static void runQueueBacklog() {
        FakeHost host = new FakeHost();
        TickParitySerialExecutor serial = serial(LIMIT);
        BrowserSettleProbe probe = new BrowserSettleProbe(serial, new TickParityDirectExecutor.Gauge(), host, report -> { });
        probe.begin();
        check(probe.idle(), "idle with an empty run queue");
        host.runQueue = 2;
        check(!probe.idle(), "queued activations keep the settle open");
        check(refuses(() -> probe.awaitIdle(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(60)), "runQueuePending=2"),
                "a run queue that never drains times the settle out and says so");
        Thread drain = new Thread(() -> {
            sleep(40);
            host.runQueue = 0;
        });
        drain.start();
        probe.awaitIdle(System.nanoTime() + LIMIT);
        check(probe.idle(), "the settle continues once the run queue drained");
        serial.shutdown();
    }

    private static void dispatcherPump() {
        FakeHost host = new FakeHost();
        host.pumps = 12;
        TickParitySerialExecutor serial = serial(LIMIT);
        BrowserSettleProbe probe = new BrowserSettleProbe(serial, new TickParityDirectExecutor.Gauge(), host, report -> { });
        probe.begin();
        check(probe.idle(), "pumps before the capture are allowed");
        host.pumps = 13;
        check(refuses(probe::idle, "hook is missing"), "a dispatcher pump hop during the capture fails closed");
        check(refuses(probe::holdBackground, "hook is missing"), "and refuses a hold");
        serial.shutdown();
    }

    private static void captureEnd() {
        List<String> reports = new ArrayList<>();
        TickParitySerialExecutor serial = serial(LIMIT);
        BrowserSettleProbe probe = new BrowserSettleProbe(serial, new TickParityDirectExecutor.Gauge(), new FakeHost(), reports::add);
        probe.begin();
        Runnable hold = probe.holdBackground();
        probe.captureEnding(true);
        check(reports.size() == 2 && reports.get(0).startsWith("{\"phase\":\"started\"")
                && reports.get(1).startsWith("{\"phase\":\"complete\"") && reports.get(1).contains("\"pending\":1"),
                "started and complete reports, the final hold still pending: " + reports);
        AtomicReference<Thread> ran = new AtomicReference<>();
        serial.execute(() -> ran.set(Thread.currentThread()));
        check(ran.get() == Thread.currentThread(), "after the capture new tasks run on the submitting thread again");
        hold.run();
        awaitIdle(serial);
        serial.shutdown();
    }

    /** One seeded synthetic workload; identical schedules give identical histories. */
    static final class Workload {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final Random random = new Random(420040L);
        final Executor background;
        final Executor io;
        final ServerChunkCache cache;
        final Dispatcher dispatcher;
        int next;
        int budget = 900;

        Workload(Executor background, Executor io, ServerChunkCache cache, Registration registration) {
            this.background = background;
            this.io = io;
            this.cache = cache;
            this.dispatcher = new Dispatcher(background, registration);
        }

        void log(String entry) {
            log.add(entry + " @" + role());
        }

        boolean spend() {
            return budget-- > 0;
        }

        Runnable main(int id) {
            return () -> {
                log("main " + id);
                int fanout = 1 + random.nextInt(4);
                for (int index = 0; index < fanout; index++) if (spend()) background.execute(background(++next, 0));
                if (random.nextInt(3) == 0 && spend()) dispatcher.schedule(step(++next));
            };
        }

        Runnable background(int id, int depth) {
            return () -> {
                log("bg " + id + " d" + depth);
                switch (random.nextInt(6)) {
                    case 0 -> {
                        if (depth < 4 && spend()) {
                            int child = ++next;
                            background.execute(background(child, depth + 1));
                            log("bg " + id + " after-child " + child);
                        }
                    }
                    case 1 -> {
                        if (spend()) cache.post(main(++next));
                    }
                    case 2 -> {
                        int task = ++next;
                        io.execute(() -> log("io " + task));
                    }
                    case 3 -> {
                        if (spend()) dispatcher.schedule(step(++next));
                    }
                    case 4 -> {
                        int task = ++next;
                        int value = CompletableFuture.supplyAsync(() -> {
                            log("join " + task);
                            return task;
                        }, background).join();
                        log("bg " + id + " joined " + value);
                    }
                    default -> {
                    }
                }
            };
        }

        Runnable step(int id) {
            return () -> {
                log("step " + id);
                if (random.nextInt(3) == 0 && spend()) background.execute(background(++next, 1));
            };
        }
    }

    private static Runnable drive(Workload workload, MinecraftServer server, ServerChunkCache cache, TickParitySettleProbe probe) {
        Runnable hold = null;
        for (int round = 0; round < 6; round++) {
            for (int index = 0; index < 4; index++) cache.post(workload.main(++workload.next));
            hold = TickParitySettle.run(server, probe, hold);
            workload.log("preload-settle " + round);
        }
        for (int tick = 1; tick <= 12; tick++) {
            // The tick body runs with the background thread held: its submissions queue.
            workload.log("tick " + tick);
            for (int index = 0; index < 3; index++) {
                if (workload.spend()) workload.background.execute(workload.background(++workload.next, 0));
            }
            if (workload.spend()) workload.dispatcher.schedule(workload.step(++workload.next));
            cache.post(workload.main(++workload.next));
            hold = TickParitySettle.run(server, probe, hold);
            workload.log("tick-end " + tick);
        }
        return hold;
    }

    static final class JvmProbe implements TickParitySettleProbe {
        @Override
        public boolean idle() {
            if (TickParityExecutors.broken()) throw new IllegalStateException("background hold exceeded its limit");
            return TickParityExecutors.idle();
        }

        @Override
        public boolean ioIdle() {
            return true;
        }

        @Override
        public Runnable holdBackground() {
            return TickParityExecutors.hold();
        }
    }

    private static List<String> browserHistory(List<String> reports) {
        TickParitySerialExecutor serial = serial(TimeUnit.SECONDS.toNanos(60));
        TickParityDirectExecutor.Gauge gauge = new TickParityDirectExecutor.Gauge();
        ServerChunkCache cache = new ServerChunkCache();
        MinecraftServer server = new MinecraftServer(List.of(new ServerLevel(cache)));
        BrowserSettleProbe probe = new BrowserSettleProbe(serial, gauge, new FakeHost(), reports::add);
        probe.begin();
        Workload workload = new Workload(serial, new TickParityDirectExecutor(gauge), cache, Registration.WORKER_TRAMPOLINE);
        Runnable hold = drive(workload, server, cache, probe);
        probe.captureEnding(true);
        hold.run();
        awaitIdle(serial);
        serial.shutdown();
        return new ArrayList<>(workload.log);
    }

    private static void equivalence() {
        ServerChunkCache cache = new ServerChunkCache();
        MinecraftServer server = new MinecraftServer(List.of(new ServerLevel(cache)));
        Workload jvm = new Workload(TickParityExecutors.background("Main"), TickParityExecutors.io("IO-Worker-", false), cache,
                Registration.VANILLA);
        Runnable hold = drive(jvm, server, cache, new JvmProbe());
        hold.run();
        List<String> jvmHistory = new ArrayList<>(jvm.log);
        List<String> reports = new ArrayList<>();
        List<String> first = browserHistory(reports);
        List<String> second = browserHistory(new ArrayList<>());
        int firstDifference = -1;
        for (int index = 0; index < Math.min(first.size(), jvmHistory.size()); index++) {
            if (!first.get(index).equals(jvmHistory.get(index))) {
                firstDifference = index;
                break;
            }
        }
        check(first.equals(jvmHistory), "browser schedule history equals the JVM schedule's (" + first.size() + " vs "
                + jvmHistory.size() + " entries, first difference at " + firstDifference + ")");
        check(first.equals(second), "two browser-schedule runs agree");
        long workerEntries = first.stream().filter(entry -> entry.endsWith("@bg")).count();
        check(workerEntries > 100 && first.size() > 300, "the workload exercised the background thread: " + workerEntries
                + " of " + first.size());
        for (String report : reports) System.out.println("PROBE_REPORT " + report);
        System.out.println("PROBE_HISTORY {\"entries\":" + first.size() + ",\"background\":" + workerEntries
                + ",\"settles\":18,\"sha\":\"" + Integer.toHexString(first.hashCode()) + "\"}");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
        } catch (InterruptedException error) {
            throw new IllegalStateException(error);
        }
    }
}
