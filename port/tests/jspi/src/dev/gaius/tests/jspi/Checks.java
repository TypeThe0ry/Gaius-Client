package dev.gaius.tests.jspi;

import java.util.ArrayList;
import java.util.List;

/** Test bookkeeping of the suite: one PASS/FAIL result per named check, per group. */
final class Checks {
    interface Body {
        void run() throws Exception;
    }

    static final List<String> FAILURES = new ArrayList<>();
    static int currentThreadChecks;
    static int currentThreadFailures;
    private static final List<String> GROUP_ORDER = new ArrayList<>();
    private static final List<int[]> GROUP_COUNTS = new ArrayList<>();

    private Checks() {
    }

    /** Runs one named check of {@code group}: it passes when it records no failure. */
    static void test(String group, String name, Body body) {
        Host.log("begin " + group + "." + name);
        int before = FAILURES.size();
        try {
            body.run();
        } catch (Throwable e) {
            fail(name + ": threw " + e);
        }
        boolean passed = FAILURES.size() == before;
        count(group, passed);
        Host.report(group + "." + name, passed ? "PASS"
                : "FAIL " + FAILURES.subList(before, FAILURES.size()));
    }

    /** A check that the runner skips in this environment (not counted). */
    static void skip(String group, String name, String reason) {
        Host.report(group + "." + name, "SKIP " + reason);
    }

    static void count(String group, boolean passed) {
        int index = GROUP_ORDER.indexOf(group);
        if (index < 0) {
            GROUP_ORDER.add(group);
            GROUP_COUNTS.add(new int[2]);
            index = GROUP_ORDER.size() - 1;
        }
        int[] counts = GROUP_COUNTS.get(index);
        counts[1]++;
        if (passed) {
            counts[0]++;
        }
    }

    /** "semantic 18/18, probes 27/27, ..." */
    static String summary() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < GROUP_ORDER.size(); i++) {
            int[] counts = GROUP_COUNTS.get(i);
            if (i > 0) {
                text.append(", ");
            }
            text.append(GROUP_ORDER.get(i)).append(' ').append(counts[0]).append('/').append(counts[1]);
        }
        return text.toString();
    }

    static void fail(String message) {
        FAILURES.add(message);
        Host.log("FAIL " + message);
    }

    static void check(boolean ok, String message) {
        if (!ok) {
            fail(message);
        }
    }

    static void checkCurrent(Thread expected, String where) {
        currentThreadChecks++;
        if (Thread.currentThread() != expected) {
            currentThreadFailures++;
            fail("currentThread wrong at " + where + ": " + Thread.currentThread().getName()
                    + " instead of " + expected.getName());
        }
    }

    static void sleepQuietly(long ms) {
        Thread self = Thread.currentThread();
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            fail("unexpected interrupt");
        }
        checkCurrent(self, "sleep(" + ms + ")");
    }

    static String fmt(double v) {
        long scaled = Math.round(v * 1000);
        return (scaled / 1000) + "." + String.valueOf(1000 + scaled % 1000).substring(1);
    }
}
