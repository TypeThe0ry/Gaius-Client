package org.teavm.classlib.java.lang;

/** Test-only stub: the real TeaVM runtime resumes the caller in a later macrotask. */
public final class TModernRuntimeSupport {
    public static int calls;
    /** Runs at the yield boundary, standing in for the other macrotasks of the event loop. */
    public static Runnable onYield;

    private TModernRuntimeSupport() {}

    public static void yieldToEventLoop(int delayMillis) {
        calls++;
        if (onYield != null) {
            onYield.run();
        }
    }
}
