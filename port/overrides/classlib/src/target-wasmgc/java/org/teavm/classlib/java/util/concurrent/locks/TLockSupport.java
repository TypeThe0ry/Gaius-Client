package org.teavm.classlib.java.util.concurrent.locks;

import org.teavm.classlib.java.lang.TJspiThreadSupport;

/**
 * LockSupport of the WebAssembly GC JSPI mode: park suspends the calling thread's activation
 * (TJspiThreadSupport), unpark leaves the permit with the host, which resumes the thread
 * from its run queue. As on the JVM, park may return spuriously and returns at once for an
 * interrupted thread, whose interrupt flag stays set.
 */
public final class TLockSupport {
    private TLockSupport() {
    }

    public static void unpark(Thread thread) {
        TJspiThreadSupport.unparkThread(thread);
    }

    public static void park(Object blocker) {
        TJspiThreadSupport.parkCurrent(0);
    }

    public static void parkNanos(Object blocker, long nanos) {
        parkNanos(nanos);
    }

    public static void parkUntil(Object blocker, long deadline) {
        parkUntil(deadline);
    }

    public static Object getBlocker(Thread thread) {
        if (thread == null) {
            throw new NullPointerException();
        }
        return null;
    }

    public static void park() {
        TJspiThreadSupport.parkCurrent(0);
    }

    public static void parkNanos(long nanos) {
        if (nanos > 0) {
            // The host timer has millisecond resolution; a timeout below 1 ms still waits
            // for one timer turn instead of spinning.
            TJspiThreadSupport.parkCurrent(Math.max(nanos / 1_000_000.0, 0.001));
        } else {
            expired();
        }
    }

    public static void parkUntil(long deadline) {
        long millis = deadline - System.currentTimeMillis();
        if (millis > 0) {
            TJspiThreadSupport.parkCurrent(millis);
        } else {
            expired();
        }
    }

    /**
     * An expired timeout returns at once on the JVM. Callers poll in a loop with it (a
     * managedBlock or MinecraftServer.waitForTasks deadline that already passed), waiting for
     * work that another thread or a host event delivers, so it yields like Thread.yield: to
     * the other runnable threads, and to the browser once the run-queue slice is over (the
     * JavaScript build yields to the event loop here).
     */
    private static void expired() {
        TJspiThreadSupport.yieldNow();
    }
}
