package dev.gaius.tests.jspi;

import static dev.gaius.tests.jspi.Checks.check;
import static dev.gaius.tests.jspi.Checks.checkCurrent;
import static dev.gaius.tests.jspi.Checks.fail;
import static dev.gaius.tests.jspi.Checks.fmt;
import static dev.gaius.tests.jspi.Checks.sleepQuietly;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The thread semantics tests of M0 spike S5 (design D3): the 16 tests and the currentThread
 * check of S5's Main (17), plus clinitJvm, the JVM's class initialization semantics (WP2(4)),
 * which S5 only reported. The tests are those of S5 with the informational ones turned into
 * checks of what the JSPI implementation now guarantees.
 */
final class SemanticTests {
    static final String GROUP = "semantic";

    private SemanticTests() {
    }

    static void run() {
        Checks.test(GROUP, "startOrder", SemanticTests::startOrder);
        Checks.test(GROUP, "visibility", SemanticTests::visibility);
        Checks.test(GROUP, "producerConsumer", SemanticTests::producerConsumer);
        Checks.test(GROUP, "waitTimeout", SemanticTests::waitTimeout);
        Checks.test(GROUP, "waitNanosRange", SemanticTests::waitNanosRange);
        Checks.test(GROUP, "sleepOrder", SemanticTests::sleepOrder);
        Checks.test(GROUP, "interruptSleep", SemanticTests::interruptSleep);
        Checks.test(GROUP, "interruptWait", SemanticTests::interruptWait);
        Checks.test(GROUP, "interruptBeforeSleep", SemanticTests::interruptBeforeSleep);
        Checks.test(GROUP, "contention2", () -> contention(2, 300));
        Checks.test(GROUP, "callbackDelivery", SemanticTests::callbackDelivery);
        Checks.test(GROUP, "callbackBlocking", SemanticTests::callbackBlocking);
        Checks.test(GROUP, "exceptions", SemanticTests::exceptions);
        Checks.test(GROUP, "deepStack", SemanticTests::deepStack);
        Checks.test(GROUP, "clinitSuspend", SemanticTests::clinitSuspend);
        Checks.test(GROUP, "contention3", () -> contention(3, 300));
        Checks.test(GROUP, "reentrantBlocking", SemanticTests::reentrantBlocking);
        Checks.test(GROUP, "currentThread", () -> check(Checks.currentThreadChecks > 100
                && Checks.currentThreadFailures == 0, Checks.currentThreadChecks + " checks, "
                + Checks.currentThreadFailures + " wrong"));
        Checks.test(GROUP, "clinitJvm", SemanticTests::clinitJvm);
    }

    static void startOrder() throws Exception {
        List<String> log = new ArrayList<>();
        Thread a = new Thread(() -> log.add("A"), "A");
        Thread b = new Thread(() -> log.add("B"), "B");
        a.start();
        b.start();
        log.add("main");
        a.join();
        b.join();
        check(log.size() == 3 && log.indexOf("A") < log.indexOf("B"), "start order " + log);
        check(!a.isAlive() && !b.isAlive(), "threads alive after join");
    }

    static final class Shared {
        int value;
        boolean ready;
    }

    static void visibility() throws Exception {
        Shared shared = new Shared();
        Thread main = Thread.currentThread();
        Thread writer = new Thread(() -> {
            synchronized (shared) {
                shared.value = 42;
                shared.ready = true;
                shared.notifyAll();
            }
        }, "writer");
        writer.start();
        synchronized (shared) {
            while (!shared.ready) {
                shared.wait();
                checkCurrent(main, "visibility wait");
            }
        }
        check(shared.value == 42, "value " + shared.value);
        writer.join();
    }

    static void producerConsumer() throws Exception {
        final int count = 2000;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Thread producer = new Thread(() -> {
            Thread self = Thread.currentThread();
            try {
                for (int i = 0; i < count; i++) {
                    synchronized (queue) {
                        while (queue.size() >= 4) {
                            queue.wait();
                            checkCurrent(self, "producer wait");
                        }
                        queue.add(i);
                        queue.notifyAll();
                    }
                }
            } catch (InterruptedException e) {
                fail("producer interrupted");
            }
        }, "producer");
        producer.start();
        long sum = 0;
        int expectedNext = 0;
        Thread main = Thread.currentThread();
        for (int i = 0; i < count; i++) {
            int v;
            synchronized (queue) {
                while (queue.isEmpty()) {
                    queue.wait();
                    checkCurrent(main, "consumer wait");
                }
                v = queue.remove();
                queue.notifyAll();
            }
            if (v != expectedNext) {
                fail("order " + v + " != " + expectedNext);
                break;
            }
            expectedNext++;
            sum += v;
        }
        producer.join();
        check(sum == (long) count * (count - 1) / 2, "sum " + sum);
    }

    static void waitTimeout() throws Exception {
        Object o = new Object();
        double start = Host.now();
        synchronized (o) {
            o.wait(30);
        }
        double elapsed = Host.now() - start;
        check(elapsed >= 29, "wait(30) returned after " + fmt(elapsed));
        check(elapsed < 1000, "wait(30) took " + fmt(elapsed));
    }

    static void waitNanosRange() throws Exception {
        Object monitor = new Object();
        boolean rejected = false;
        synchronized (monitor) {
            try {
                monitor.wait(0, 1_000_000);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
        }
        check(rejected, "Object.wait accepted nanos outside 0..999999");
    }

    static void sleepOrder() throws Exception {
        List<String> log = new ArrayList<>();
        double[] slept = new double[2];
        Thread a = new Thread(() -> {
            double s = Host.now();
            sleepQuietly(30);
            slept[0] = Host.now() - s;
            log.add("A");
        }, "A");
        Thread b = new Thread(() -> {
            double s = Host.now();
            sleepQuietly(10);
            slept[1] = Host.now() - s;
            log.add("B");
        }, "B");
        a.start();
        b.start();
        a.join();
        b.join();
        check(log.equals(List.of("B", "A")), "sleep order " + log);
        check(slept[0] >= 29 && slept[1] >= 9, "short sleep " + fmt(slept[0]) + "/" + fmt(slept[1]));
    }

    static void interruptSleep() throws Exception {
        String[] result = new String[1];
        double[] elapsed = new double[1];
        Thread a = new Thread(() -> {
            double s = Host.now();
            try {
                Thread.sleep(10_000);
                result[0] = "not interrupted";
            } catch (InterruptedException e) {
                result[0] = "interrupted flag=" + Thread.currentThread().isInterrupted();
            }
            elapsed[0] = Host.now() - s;
            checkCurrent(Thread.currentThread(), "interrupted sleep");
        }, "sleeper");
        a.start();
        Thread.sleep(5);
        a.interrupt();
        a.join();
        check("interrupted flag=false".equals(result[0]), "interrupt sleep: " + result[0]);
        check(elapsed[0] < 1000, "interrupt took " + fmt(elapsed[0]));
    }

    static void interruptWait() throws Exception {
        Object o = new Object();
        String[] result = new String[1];
        Thread a = new Thread(() -> {
            synchronized (o) {
                try {
                    o.wait();
                    result[0] = "not interrupted";
                } catch (InterruptedException e) {
                    result[0] = "interrupted holdsLock=" + Thread.holdsLock(o)
                            + " flag=" + Thread.currentThread().isInterrupted();
                }
            }
        }, "waiter");
        a.start();
        Thread.sleep(5);
        a.interrupt();
        a.join();
        check("interrupted holdsLock=true flag=false".equals(result[0]), "interrupt wait: " + result[0]);
        // The interrupted waiter must have left the wait set: notify must not be lost on it.
        Shared s = new Shared();
        Thread b = new Thread(() -> {
            synchronized (o) {
                s.ready = true;
                try {
                    o.wait();
                    s.value = 1;
                } catch (InterruptedException e) {
                    s.value = -1;
                }
            }
        }, "waiter2");
        b.start();
        while (!s.ready) {
            Thread.sleep(1);
        }
        synchronized (o) {
            o.notify();
        }
        b.join(2000);
        check(s.value == 1, "notify after an interrupted waiter: " + s.value);
    }

    static void interruptBeforeSleep() {
        Thread.currentThread().interrupt();
        double start = Host.now();
        boolean thrown = false;
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            thrown = true;
        }
        check(thrown, "pending interrupt did not throw");
        check(Host.now() - start < 40, "pending interrupt slept");
        check(!Thread.interrupted(), "flag still set");
    }

    static final class Counter {
        int n;
    }

    /** {@code threads} contenders (main included) incrementing under one monitor. */
    static void contention(int threads, int rounds) throws Exception {
        Counter c = new Counter();
        Checks.Body body = () -> {
            Thread self = Thread.currentThread();
            for (int i = 0; i < rounds; i++) {
                synchronized (c) {
                    int v = c.n;
                    if (i % 50 == 0) {
                        Thread.sleep(1);
                    } else if (i % 10 == 0) {
                        Thread.yield();
                    }
                    c.n = v + 1;
                }
                checkCurrent(self, "contention");
                if (i % 7 == 0) {
                    Thread.sleep(0);
                }
            }
        };
        List<Thread> started = new ArrayList<>();
        for (int t = 1; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    body.run();
                } catch (Exception e) {
                    fail("contender " + e);
                }
            }, "contender" + t);
            thread.start();
            started.add(thread);
        }
        body.run();
        for (Thread thread : started) {
            thread.join(10_000);
            check(!thread.isAlive(), thread.getName() + " still runs (deadlock)");
        }
        check(c.n == threads * rounds, "counter " + c.n + " != " + threads * rounds);
    }

    static void callbackDelivery() throws Exception {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        final int count = 100;
        int[] received = new int[1];
        boolean[] ordered = {true};
        Thread consumer = new Thread(() -> {
            Thread self = Thread.currentThread();
            try {
                for (int i = 0; i < count; i++) {
                    int v;
                    synchronized (queue) {
                        while (queue.isEmpty()) {
                            queue.wait();
                            checkCurrent(self, "callback consumer");
                        }
                        v = queue.remove();
                    }
                    if (v != i) {
                        ordered[0] = false;
                    }
                    received[0]++;
                }
            } catch (InterruptedException e) {
                fail("consumer interrupted");
            }
        }, "consumer");
        consumer.start();
        Host.deliverLater(value -> {
            synchronized (queue) {
                queue.add(value);
                queue.notifyAll();
            }
        }, count);
        consumer.join(5000);
        check(received[0] == count && ordered[0], "received " + received[0] + " ordered " + ordered[0]);
    }

    /** Blocking in a {@code @JSFunctor} callback: a clear IllegalStateException, no trap. */
    static void callbackBlocking() throws Exception {
        Host.callOnce(value -> {
            try {
                Thread.sleep(value);
            } catch (InterruptedException e) {
                // ignored
            }
        }, 5);
        Thread.sleep(50);
        String error = Host.callbackError();
        check(error.contains("outside a Java thread activation"), "callback error: " + error);
    }

    /**
     * A JS frame between the activation entry and a blocking call (a JSBody that calls back
     * into Java synchronously): the blocking call refuses instead of suspending through the JS
     * frame, and threads keep working afterwards.
     */
    static void reentrantBlocking() throws Exception {
        String[] caught = {"none"};
        Thread a = new Thread(() -> {
            String js = Host.callNow(value -> {
                try {
                    Thread.sleep(value);
                } catch (InterruptedException e) {
                    // ignored
                }
            }, 2);
            caught[0] = js;
        }, "reentrant");
        a.start();
        a.join(2000);
        check(!a.isAlive(), "the reentrant thread did not finish");
        // The blocking call throws IllegalStateException; JavaScript sees its message.
        check(caught[0].contains("outside a Java thread activation"), "js saw " + caught[0]);
        Thread b = new Thread(() -> sleepQuietly(1), "after");
        b.start();
        b.join();
        check(!b.isAlive(), "thread after the reentrant case did not finish");
    }

    static void exceptions() throws Exception {
        List<String> log = new ArrayList<>();
        Thread a = new Thread(() -> {
            try {
                try {
                    Thread.sleep(1);
                    throw new IllegalStateException("x");
                } finally {
                    log.add("finally");
                }
            } catch (IllegalStateException e) {
                log.add("caught " + e.getMessage());
            } catch (InterruptedException e) {
                log.add("interrupted");
            }
        }, "thrower");
        a.start();
        a.join();
        String[] uncaught = new String[1];
        Thread b = new Thread(() -> {
            sleepQuietly(1);
            throw new IllegalArgumentException("boom");
        }, "uncaught");
        b.setUncaughtExceptionHandler((t, e) -> uncaught[0] = t.getName() + ":" + e.getMessage());
        b.start();
        b.join();
        check(log.equals(List.of("finally", "caught x")), "exception log " + log);
        check("uncaught:boom".equals(uncaught[0]), "uncaught " + uncaught[0]);
    }

    static void deepStack() throws Exception {
        long[] result = new long[1];
        Thread a = new Thread(() -> result[0] = recurse(2000), "deep");
        a.start();
        a.join();
        check(result[0] == 2000L * 2001 / 2, "deep result " + result[0]);
    }

    static long recurse(int depth) {
        if (depth == 0) {
            sleepQuietly(1);
            return 0;
        }
        return depth + recurse(depth - 1);
    }

    static final class SlowInit {
        static int value;

        static {
            value = 1;
            sleepQuietly(20);
            value = 2;
        }

        private SlowInit() {
        }
    }

    /** S5: a thread suspends inside a class initializer; its stack survives (it reads 2). */
    static void clinitSuspend() throws Exception {
        int[] seen = new int[2];
        Thread a = new Thread(() -> seen[0] = SlowInit.value, "init");
        a.start();
        Thread.sleep(5);
        seen[1] = SlowInit.value;
        a.join();
        Host.report("info.clinitSuspend", "initializer thread saw " + seen[0]
                + ", concurrent reader saw " + seen[1]);
        check(seen[0] == 2, "the initializing thread saw " + seen[0]);
    }

    static final class SlowInit2 {
        static int value;
        static final List<String> NAMES;

        static {
            value = 1;
            sleepQuietly(20);
            value = 2;
            NAMES = new ArrayList<>();
        }

        private SlowInit2() {
        }
    }

    /**
     * WP2(4): a thread that requests a class while another thread is suspended in its
     * initializer waits until the initializer ends, as the JVM's class initialization lock
     * makes it (S5 read 1 here: the class half-initialized).
     */
    static void clinitJvm() throws Exception {
        int[] seen = new int[2];
        boolean[] namesNull = new boolean[1];
        int waitsBefore = Host.jspiStat("clinitWait");
        Thread a = new Thread(() -> seen[0] = SlowInit2.value, "init2");
        a.start();
        Thread.sleep(5);
        seen[1] = SlowInit2.value;
        namesNull[0] = SlowInit2.NAMES == null;
        a.join();
        check(seen[0] == 2, "the initializing thread saw " + seen[0]);
        check(seen[1] == 2, "the concurrent reader saw " + seen[1] + " (JVM: 2)");
        check(!namesNull[0], "the concurrent reader saw NAMES == null");
        check(Host.jspiStat("clinitWait") > waitsBefore, "no clinitWait counted");
    }
}

