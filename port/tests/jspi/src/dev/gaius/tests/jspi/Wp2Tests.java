package dev.gaius.tests.jspi;

import static dev.gaius.tests.jspi.Checks.check;
import static dev.gaius.tests.jspi.Checks.sleepQuietly;

import dev.gaius.browser.HostLoop;

/**
 * M1 WP2 checks beyond S5: Thread.join (the JVM's loop on isAlive), interrupt paths, the class
 * initialization semantics in more shapes (recursion, an initializer that throws, a JS callback
 * that cannot wait, the telemetry counters) and the frame boundary of design D4
 * (HostLoop.awaitAnimationFrame).
 */
final class Wp2Tests {
    static final String GROUP = "wp2";
    static volatile boolean throwInInit;
    static volatile int initRuns;
    static volatile int callbackSeen;

    private Wp2Tests() {
    }

    static void run() {
        Checks.test(GROUP, "joinUnstarted", Wp2Tests::joinUnstarted);
        Checks.test(GROUP, "joinTimed", Wp2Tests::joinTimed);
        Checks.test(GROUP, "joinFinished", Wp2Tests::joinFinished);
        Checks.test(GROUP, "interruptJoin", Wp2Tests::interruptJoin);
        Checks.test(GROUP, "interruptSleepZero", Wp2Tests::interruptSleepZero);
        Checks.test(GROUP, "clinitRecursive", Wp2Tests::clinitRecursive);
        Checks.test(GROUP, "clinitThrows", Wp2Tests::clinitThrows);
        Checks.test(GROUP, "clinitCallbackCannotWait", Wp2Tests::clinitCallbackCannotWait);
        Checks.test(GROUP, "clinitSyncCallbackSameThread", Wp2Tests::clinitSyncCallbackSameThread);
        Checks.test(GROUP, "clinitTelemetry", Wp2Tests::clinitTelemetry);
        Checks.test(GROUP, "awaitAnimationFrame", Wp2Tests::awaitAnimationFrame);
    }

    /** JVM: a thread that was never started is not alive, so join returns at once. */
    static void joinUnstarted() throws Exception {
        Thread never = new Thread(() -> { }, "never");
        double start = Host.now();
        never.join(300);
        check(Host.now() - start < 100, "join(300) of an unstarted thread waited "
                + Checks.fmt(Host.now() - start) + " ms");
        start = Host.now();
        never.join();
        check(Host.now() - start < 100, "join() of an unstarted thread waited");
    }

    static void joinTimed() throws Exception {
        Thread slow = new Thread(() -> sleepQuietly(120), "slow");
        slow.start();
        double start = Host.now();
        slow.join(30);
        double elapsed = Host.now() - start;
        check(slow.isAlive(), "the thread ended within join(30)");
        check(elapsed >= 29 && elapsed < 110, "join(30) returned after " + Checks.fmt(elapsed) + " ms");
        slow.join();
        check(!slow.isAlive(), "join() returned while the thread is alive");
    }

    static void joinFinished() throws Exception {
        Thread quick = new Thread(() -> { }, "quick");
        quick.start();
        quick.join();
        double start = Host.now();
        quick.join();
        quick.join(1000);
        check(Host.now() - start < 50, "join of a finished thread waited");
    }

    /** An interrupt ends a join with InterruptedException and clears the flag. */
    static void interruptJoin() throws Exception {
        Thread target = new Thread(() -> sleepQuietly(200), "target");
        String[] result = {"none"};
        Thread joiner = new Thread(() -> {
            try {
                target.join();
                result[0] = "joined";
            } catch (InterruptedException e) {
                result[0] = "interrupted flag=" + Thread.currentThread().isInterrupted()
                        + " targetAlive=" + target.isAlive();
            }
        }, "joiner");
        target.start();
        joiner.start();
        Thread.sleep(10);
        joiner.interrupt();
        joiner.join(1000);
        check("interrupted flag=false targetAlive=true".equals(result[0]), "interrupted join: " + result[0]);
        target.join();
    }

    /** A pending interrupt makes Thread.sleep(0) throw, as on the JVM. */
    static void interruptSleepZero() {
        Thread.currentThread().interrupt();
        boolean thrown = false;
        try {
            Thread.sleep(0);
        } catch (InterruptedException e) {
            thrown = true;
        }
        check(thrown, "sleep(0) ignored a pending interrupt");
        check(!Thread.interrupted(), "the flag stayed set");
    }

    static final class CycleA {
        static final int A = CycleB.B + 1;

        private CycleA() {
        }
    }

    static final class CycleB {
        static final int B = CycleA.A + 1;

        private CycleB() {
        }
    }

    /**
     * A recursive request from the initializing thread returns at once (JVM 12.4.2 step 3):
     * CycleA's initializer reaches CycleB's, which reads CycleA.A while it is still 0.
     */
    static void clinitRecursive() {
        int a = CycleA.A;
        int b = CycleB.B;
        check(a == 2 && b == 1, "CycleA.A=" + a + " CycleB.B=" + b + " (JVM: 2 and 1)");
    }

    static final class Throwing {
        static int value;

        static {
            initRuns++;
            value = 1;
            sleepQuietly(20);
            if (throwInInit) {
                throw new IllegalStateException("initializer failed");
            }
            value = 2;
        }

        private Throwing() {
        }
    }

    /**
     * An initializer that throws ends the class initialization too: the thread that waited for
     * it goes on (the JVM would throw NoClassDefFoundError there; TeaVM keeps the class as the
     * initializer left it), and the initializer does not run a second time.
     */
    static void clinitThrows() throws Exception {
        throwInInit = true;
        String[] first = {"none"};
        Thread a = new Thread(() -> {
            try {
                first[0] = "value " + Throwing.value;
            } catch (Throwable e) {
                first[0] = "threw " + e.getClass().getName();
            }
        }, "throwing");
        a.start();
        Thread.sleep(5);
        String second;
        try {
            second = "value " + Throwing.value;
        } catch (Throwable e) {
            second = "threw " + e.getClass().getName();
        }
        a.join(2000);
        String third = "value " + Throwing.value;
        Host.report("info.clinitThrows", "initializer thread: " + first[0] + "; waiter: " + second
                + "; later: " + third);
        check(!a.isAlive(), "the initializing thread did not end");
        check(first[0].startsWith("threw "), "the initializing thread did not see the exception: " + first[0]);
        check(initRuns == 1, "the initializer ran " + initRuns + " times");
        check("value 1".equals(third), "later read " + third);
    }

    static final class CallbackSlow {
        static int value;

        static {
            value = 1;
            // awaitHost suspends this initializer activation before the timer callback can run.
            // The callback observes the half-initialized value and then posts the resume, so the
            // barrier is tied to CallbackSlow itself rather than to an unrelated clinit counter.
            HostLoop.awaitHost(resume -> Host.callOnce(ignored -> {
                callbackSeen = CallbackSlow.value;
                HostLoop.postMacrotask(resume);
            }, 0));
            value = 2;
        }

        private CallbackSlow() {
        }
    }

    /**
     * A JS callback outside any activation cannot wait for a class that a suspended thread
     * initializes: it reads the class as it is (counted as clinitUnwaitable) and nothing hangs.
     */
    static void clinitCallbackCannotWait() throws Exception {
        int before = Host.jspiStat("clinitUnwaitable");
        callbackSeen = -1;
        Thread a = new Thread(() -> { int ignored = CallbackSlow.value; }, "callbackSlow");
        a.start();
        a.join();
        check(CallbackSlow.value == 2, "the initializing thread saw " + CallbackSlow.value);
        check(callbackSeen == 1, "the callback saw " + callbackSeen + " (it cannot wait: 1)");
        check("none".equals(Host.callbackError()), "the callback threw " + Host.callbackError());
        check(Host.jspiStat("clinitUnwaitable") == before + 1, "clinitUnwaitable "
                + before + " -> " + Host.jspiStat("clinitUnwaitable"));
    }

    static final class SelfCallback {
        static int value;
        static String fromCallback;

        static {
            value = 1;
            fromCallback = readSelfFromCallback();
            value = 2;
        }

        private SelfCallback() {
        }
    }

    /**
     * Called by SelfCallback's initializer: a synchronous JS callback reads SelfCallback.value
     * from this class, so the read requests SelfCallback's initialization (a read from
     * SelfCallback's own code would not).
     */
    static String readSelfFromCallback() {
        String[] seen = {"unset"};
        String js = Host.callNow(v -> seen[0] = "value " + SelfCallback.value, 0);
        return js + "/" + seen[0];
    }

    /**
     * A synchronous callback from the initializing thread itself is a recursive request: it
     * reads the class as it is, without waiting (a JVM thread would do the same).
     */
    static void clinitSyncCallbackSameThread() {
        int value = SelfCallback.value;
        check(value == 2, "value " + value);
        check("none/value 1".equals(SelfCallback.fromCallback), "callback " + SelfCallback.fromCallback);
    }

    static final class Suspending {
        static int value;

        static {
            sleepQuietly(2);
            value = 3;
        }

        private Suspending() {
        }
    }

    /** A suspension inside a class initializer is counted (scheduler stats and the global counter). */
    static void clinitTelemetry() {
        int stat = Host.jspiStat("clinitSuspend");
        int global = Host.clinitSuspensions();
        int value = Suspending.value;
        check(value == 3, "value " + value);
        check(Host.jspiStat("clinitSuspend") > stat, "clinitSuspend " + stat + " -> "
                + Host.jspiStat("clinitSuspend"));
        check(Host.clinitSuspensions() > global, "__gaiusClinitSuspensions " + global + " -> "
                + Host.clinitSuspensions());
    }

    /**
     * The frame boundary of design D4 suspends the main thread until the browser is ready for
     * the next frame, for a synchronized and an uncapped present alike, and other threads run
     * in between.
     */
    static void awaitAnimationFrame() throws Exception {
        int frames = Host.jspiStat("frames");
        int[] ticks = {0};
        boolean[] stop = {false};
        Thread ticker = new Thread(() -> {
            while (!stop[0]) {
                ticks[0]++;
                sleepQuietly(1);
            }
        }, "ticker");
        ticker.start();
        double start = Host.now();
        for (int interval = 0; interval <= 1; interval++) {
            HostLoop.framePresented(false, interval);
            for (int i = 0; i < 5; i++) {
                HostLoop.awaitAnimationFrame();
            }
        }
        double elapsed = Host.now() - start;
        stop[0] = true;
        ticker.join();
        HostLoop.framePresented(false, 1);
        check(Host.jspiStat("frames") == frames + 10, "frames " + frames + " -> " + Host.jspiStat("frames"));
        check(elapsed < 2000, "10 frame waits took " + Checks.fmt(elapsed) + " ms");
        // Node runs MessagePort messages back to back and starves the ticker's timers there.
        check(ticks[0] > 0 || Host.param("node") == 1, "no other thread ran during the frame waits");
    }
}
