package org.teavm.classlib.java.util.concurrent.locks;

import java.util.IdentityHashMap;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;

public final class TLockSupport {
    private static final IdentityHashMap<Thread, Boolean> permits = new IdentityHashMap<>();
    private static final IdentityHashMap<Thread, Boolean> parkedThreads = new IdentityHashMap<>();

    private TLockSupport() {
    }

    public static void unpark(Thread thread) {
        if (thread == null) {
            return;
        }
        permits.put(thread, Boolean.TRUE);
        if (parkedThreads.containsKey(thread)) {
            thread.interrupt();
        }
    }

    public static void park(Object blocker) {
        // A static initializer or a method compiled synchronously by the role options must
        // not suspend (TModernRuntimeSupport.inClassInitializer); park may return spuriously,
        // so it returns at once there.
        if (TModernRuntimeSupport.inClassInitializer()) {
            return;
        }
        if (!takePermit(Thread.currentThread())) {
            Thread.yield();
        }
    }

    public static void parkNanos(Object blocker, long nanos) {
        sleepNanos(nanos);
    }

    public static void parkUntil(Object blocker, long deadline) {
        if (TModernRuntimeSupport.inClassInitializer()) {
            return;
        }
        long millis = deadline - System.currentTimeMillis();
        if (millis > 0) {
            sleepMillis(millis);
        } else {
            Thread.yield();
        }
    }

    public static Object getBlocker(Thread thread) {
        if (thread == null) {
            throw new NullPointerException();
        }
        return null;
    }

    public static void park() {
        if (TModernRuntimeSupport.inClassInitializer()) {
            return;
        }
        if (!takePermit(Thread.currentThread())) {
            Thread.yield();
        }
    }

    public static void parkNanos(long nanos) {
        sleepNanos(nanos);
    }

    public static void parkUntil(long deadline) {
        if (TModernRuntimeSupport.inClassInitializer()) {
            return;
        }
        long millis = deadline - System.currentTimeMillis();
        if (millis > 0) {
            sleepMillis(millis);
        } else {
            Thread.yield();
        }
    }

    private static void sleepNanos(long nanos) {
        if (TModernRuntimeSupport.inClassInitializer()) {
            return;
        }
        if (nanos <= 0) {
            // Minecraft's managed-block loop can pass an expired tick deadline.
            // TeaVM Thread.yield() may return synchronously for 100 ms; spinning
            // here prevents the Worker from resuming the chunk future we await.
            TModernRuntimeSupport.yieldToEventLoop(0);
            return;
        }
        long millis = Math.max(1, (nanos + 999_999L) / 1_000_000L);
        sleepMillis(millis);
    }

    private static void sleepMillis(long millis) {
        if (TModernRuntimeSupport.inClassInitializer()) {
            return;
        }
        Thread thread = Thread.currentThread();
        if (takePermit(thread)) {
            return;
        }
        parkedThreads.put(thread, Boolean.TRUE);
        try {
            Thread.sleep(Math.max(1, Math.min(millis, Integer.MAX_VALUE)));
        } catch (InterruptedException e) {
            if (!takePermit(thread)) {
                thread.interrupt();
            }
        } catch (RuntimeException e) {
            // A JavaScript callback without a TeaVM thread cannot suspend, and TeaVM refuses
            // before it schedules anything. Park may return spuriously, so it returns at once:
            // a frame rendered inside the network inbound pump (the 26.x login packet:
            // setScreenAndShow -> renderFrame -> FramerateLimiter.limitDisplayFPS) otherwise
            // fails the packet and disconnects the client.
            if (!isNonThreadingSuspension(e)) {
                throw e;
            }
        } finally {
            parkedThreads.remove(thread);
        }
    }

    private static boolean isNonThreadingSuspension(RuntimeException error) {
        String message = error.getMessage();
        return message != null && message.contains("Suspension point reached from non-threading context");
    }

    private static boolean takePermit(Thread thread) {
        return permits.remove(thread) != null;
    }
}
