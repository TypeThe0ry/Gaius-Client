package org.teavm.platform;

import java.util.ArrayDeque;
import java.util.Queue;

/** Test-only browser-turn queue for Platform.startThread. */
public final class Platform {
    private static final Queue<PlatformRunnable> THREADS = new ArrayDeque<>();
    private static int startedThreads;

    private Platform() {
    }

    public static void startThread(PlatformRunnable command) {
        THREADS.add(command);
        startedThreads++;
    }

    /** HostLoop.schedule compiles against this; the dispatcher never uses a raw timer. */
    public static int schedule(PlatformRunnable command, int delayMillis) {
        throw new AssertionError("the worldgen dispatcher must not use Platform.schedule");
    }

    public static void killSchedule(int id) {
        throw new AssertionError("the worldgen dispatcher must not use Platform.killSchedule");
    }

    public static int pendingThreads() {
        return THREADS.size();
    }

    public static int startedThreads() {
        return startedThreads;
    }

    public static void reset() {
        THREADS.clear();
        startedThreads = 0;
    }

    public static void runNextThread() {
        PlatformRunnable command = THREADS.poll();
        if (command == null) {
            throw new AssertionError("no deferred Platform thread was scheduled");
        }
        command.run();
    }
}
