package dev.gaius.tests.jspi;

import static dev.gaius.tests.jspi.Checks.check;

import dev.gaius.browser.HostLoop;
import dev.gaius.browser.JspiHost;
import java.util.concurrent.locks.LockSupport;

/**
 * Runtime probes of the JSPI host (the JspiFix probe of the M1 T0 review fixes and the host
 * task checks of S5's TaskTest): host results, startTask identity, yield slices, parkNanos(0),
 * trap recovery, long timeouts, killSchedule and synchronous JS callbacks.
 */
final class ProbeTests {
    static final String GROUP = "probes";
    static volatile boolean flag;
    static volatile int taskState;
    static volatile int counter;

    private ProbeTests() {
    }

    static String nothing() {
        return taskState > 100 ? "x" : null;
    }

    static void run() {
        boolean node = Host.param("node") == 1;
        Checks.test(GROUP, "hostResultBeforeAwait", () -> HostLoop.awaitHost(resume -> resume.run()));
        Checks.test(GROUP, "hostResultLater", () -> HostLoop.awaitHost(resume -> HostLoop.postMacrotask(resume)));
        Checks.test(GROUP, "stalePermitIgnored", ProbeTests::stalePermit);
        Checks.test(GROUP, "hostTaskIdentity", ProbeTests::hostTask);
        Checks.test(GROUP, "startTaskUnpark", ProbeTests::startTaskUnpark);
        Checks.test(GROUP, "lockSupport", ProbeTests::lockSupport);
        if (node) {
            // Node's MessagePort runs messages posted from a message handler back to back and
            // starves timers (a plain-JS repro needs no Java), so a yield loop never sees one.
            Checks.skip(GROUP, "yieldLetsTimersRun", "node starves timers behind MessagePort messages");
            Checks.skip(GROUP, "parkNanos0LetsTimersRun", "node starves timers behind MessagePort messages");
        } else {
            Checks.test(GROUP, "yieldLetsTimersRun", ProbeTests::yieldSlice);
            Checks.test(GROUP, "parkNanos0LetsTimersRun", ProbeTests::parkNanosZero);
        }
        Checks.test(GROUP, "trapEndsThread", ProbeTests::trap);
        Checks.test(GROUP, "longTimedWait", ProbeTests::longWait);
        Checks.test(GROUP, "killSchedule", ProbeTests::killSchedule);
        Checks.test(GROUP, "syncCallbackCannotBlock", ProbeTests::syncCallback);
        Checks.test(GROUP, "syncCallbackContendedMonitor", ProbeTests::syncCallbackMonitor);
    }

    static void stalePermit() {
        LockSupport.unpark(Thread.currentThread());
        double start = Host.now();
        HostLoop.awaitHost(resume -> HostLoop.schedule(resume, 30));
        check(Host.now() - start >= 25, "a stale LockSupport permit ended a host wait early");
    }

    /** S5 TaskTest: a host task blocks and keeps the starter's thread identity. */
    static void hostTask() throws Exception {
        Thread main = Thread.currentThread();
        Object lock = new Object();
        int[] state = new int[2];
        check(JspiHost.inActivation(), "main is not in an activation");
        JspiHost.startTask(() -> {
            boolean same = Thread.currentThread() == main;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                state[1] = -1;
            }
            synchronized (lock) {
                state[0] = same && Thread.currentThread() == main ? 1 : 2;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            while (state[0] == 0) {
                lock.wait();
            }
        }
        check(state[0] == 1 && state[1] == 0, "host task state " + state[0] + "/" + state[1]);
    }

    /** LockSupport.unpark(thread) reaches a startTask activation that shares the thread. */
    static void startTaskUnpark() throws Exception {
        Thread[] seen = new Thread[1];
        taskState = 0;
        HostLoop.startThread(() -> {
            seen[0] = Thread.currentThread();
            taskState = 1;
            LockSupport.park();
            taskState = 2;
        });
        Thread.sleep(10);
        check(seen[0] == Thread.currentThread(), "the task does not run as its starter");
        LockSupport.unpark(seen[0]);
        Thread.sleep(50);
        check(taskState == 2, "task state after unpark " + taskState);
    }

    /** S5 TaskTest: park permits, parkNanos timeout, unpark and interrupt wake-ups. */
    static void lockSupport() throws Exception {
        Thread main = Thread.currentThread();
        LockSupport.unpark(main);
        double start = Host.now();
        LockSupport.park();
        double permitMs = Host.now() - start;
        start = Host.now();
        LockSupport.parkNanos(20_000_000L);
        double timedMs = Host.now() - start;
        boolean[] woke = new boolean[1];
        Thread parker = new Thread(() -> {
            while (!woke[0]) {
                LockSupport.park(main);
            }
        }, "parker");
        parker.start();
        Thread.sleep(5);
        woke[0] = true;
        LockSupport.unpark(parker);
        parker.join(1000);
        boolean[] flagAfter = new boolean[1];
        Thread interrupted = new Thread(() -> {
            LockSupport.park();
            flagAfter[0] = Thread.currentThread().isInterrupted();
        }, "int");
        interrupted.start();
        Thread.sleep(5);
        interrupted.interrupt();
        interrupted.join(1000);
        check(permitMs < 5, "park with a permit took " + Checks.fmt(permitMs) + " ms");
        check(timedMs >= 19 && timedMs < 200, "parkNanos(20 ms) took " + Checks.fmt(timedMs) + " ms");
        check(!parker.isAlive(), "unpark did not wake the parker");
        check(!interrupted.isAlive(), "interrupt did not wake park");
        check(flagAfter[0], "park cleared the interrupt status");
    }

    /** Thread.yield() with an empty run queue still lets a timer run (the slice ends). */
    static void yieldSlice() throws Exception {
        flag = false;
        HostLoop.schedule(() -> flag = true, 1);
        double start = Host.now();
        while (!flag && Host.now() - start < 300) {
            Thread.yield();
        }
        check(flag, "the timer never ran during the yield loop");
        check(Host.now() - start < 100, "the timer ran after " + Checks.fmt(Host.now() - start) + " ms");
        Thread.sleep(5);
    }

    /** LockSupport.parkNanos(0), an expired parkUntil and sleep(0) yield too. */
    static void parkNanosZero() throws Exception {
        flag = false;
        HostLoop.schedule(() -> flag = true, 1);
        double start = Host.now();
        while (!flag && Host.now() - start < 300) {
            LockSupport.parkNanos(0);
        }
        check(flag, "parkNanos(0) loop");
        flag = false;
        HostLoop.schedule(() -> flag = true, 1);
        start = Host.now();
        while (!flag && Host.now() - start < 300) {
            LockSupport.parkUntil(System.currentTimeMillis() - 5);
        }
        check(flag, "expired parkUntil loop");
        flag = false;
        HostLoop.schedule(() -> flag = true, 1);
        start = Host.now();
        while (!flag && Host.now() - start < 300) {
            Thread.sleep(0);
        }
        check(flag, "sleep(0) loop");
        Thread.sleep(5);
    }

    /**
     * A trap in a thread ends the thread (gaius.jspi.abandon): join returns, isAlive is false,
     * and a later host callback cannot suspend.
     */
    static void trap() throws Exception {
        Thread crasher = new Thread(() -> {
            String text = nothing();
            Host.report("info.unreachable", String.valueOf(text.length()));
        }, "crasher");
        boolean[] probe = {true};
        HostLoop.schedule(() -> probe[0] = HostLoop.canSuspend(), 30);
        crasher.start();
        crasher.join(150);
        check(!crasher.isAlive(), "the trapped thread is alive");
        Thread.sleep(40);
        check(!probe[0], "a host callback after the trap can suspend");
        Thread crasher2 = new Thread(() -> {
            String text = nothing();
            Host.report("info.unreachable2", String.valueOf(text.length()));
        }, "crasher2");
        crasher2.start();
        double start = Host.now();
        crasher2.join();
        check(Host.now() - start < 1000, "untimed join of a trapped thread hangs");
    }

    /** A timed wait longer than setTimeout's 32-bit delay sleeps instead of polling. */
    static void longWait() throws Exception {
        Object lock = new Object();
        Thread notifier = new Thread(() -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                // ignored
            }
            synchronized (lock) {
                lock.notifyAll();
            }
        }, "notifier");
        notifier.start();
        int parksBefore = Host.jspiStat("parks");
        synchronized (lock) {
            lock.wait(Long.MAX_VALUE / 4);
        }
        int parks = Host.jspiStat("parks") - parksBefore;
        notifier.join();
        // The main thread parks once or twice, the notifier once in its sleep.
        check(parksBefore >= 0 && parks <= 5, parks + " parks during the wait");
    }

    /** HostLoop.killSchedule cancels delayed and zero-delay events. */
    static void killSchedule() throws Exception {
        counter = 0;
        int delayed = HostLoop.schedule(() -> counter += 1, 5);
        int queued = HostLoop.schedule(() -> counter += 10, 0);
        HostLoop.schedule(() -> counter += 100, 5);
        HostLoop.killSchedule(delayed);
        HostLoop.killSchedule(queued);
        Thread.sleep(30);
        check(counter == 100, "counter " + counter);
    }

    /**
     * A synchronous JS -> Java callback inside the activation cannot suspend, and the thread
     * keeps working afterwards.
     */
    static void syncCallback() throws Exception {
        check(HostLoop.canSuspend(), "main cannot suspend before");
        boolean[] inCallback = {true};
        String js = Host.callNow(value -> {
            inCallback[0] = HostLoop.canSuspend();
            Thread.yield();
            LockSupport.parkNanos(0);
            HostLoop.awaitAnimationFrame();
            try {
                Thread.sleep(value);
            } catch (InterruptedException e) {
                // ignored
            }
        }, 2);
        check(!inCallback[0], "the callback can suspend");
        // Thread.sleep throws IllegalStateException; JavaScript sees its message.
        check(js.contains("outside a Java thread activation"), "js saw " + js);
        check(HostLoop.canSuspend(), "main cannot suspend after");
        Thread.sleep(1);
    }

    static void syncCallbackMonitor() throws Exception {
        Object monitor = new Object();
        Thread holder = new Thread(() -> {
            synchronized (monitor) {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    // ignored
                }
            }
        }, "holder");
        holder.start();
        Thread.sleep(5);
        String contended = Host.callNow(value -> {
            synchronized (monitor) {
                counter = value;
            }
        }, 7);
        check(contended.contains("Can't enter monitor from another thread synchronously"), "js saw " + contended);
        holder.join();
        synchronized (monitor) {
            counter = 0;
        }
    }
}
