package dev.gaius.browser;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM stand-in for the browser deep-worldgen pulse, used by worldgen-seed-parity.mjs when the
 * patched worldgen classes run on the JVM. It never yields; it counts the pulses and samples
 * the calling method of one pulse in 1024, so the run shows which patched loops pulse.
 */
public final class BrowserWorldgenDeepCheckpoint {
    private static final AtomicLong PULSES = new AtomicLong();
    private static final Map<String, AtomicLong> SAMPLED_CALLERS = new ConcurrentHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance();

    private BrowserWorldgenDeepCheckpoint() {
    }

    public static void checkpoint() {
        pulse();
    }

    public static void pulse() {
        if ((PULSES.incrementAndGet() & 1023) != 0) {
            return;
        }
        String caller = WALKER.walk(frames -> frames
                .filter(frame -> !frame.getClassName().equals(
                        BrowserWorldgenDeepCheckpoint.class.getName()))
                .findFirst()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                .orElse("?"));
        SAMPLED_CALLERS.computeIfAbsent(caller, key -> new AtomicLong()).incrementAndGet();
    }

    /** JSON object read by WorldgenSeedParity through reflection. */
    public static String parityReport() {
        StringBuilder json = new StringBuilder("{\"total\": ").append(PULSES.get())
                .append(", \"sampleEvery\": 1024, \"sampledCallers\": {");
        boolean first = true;
        for (Map.Entry<String, AtomicLong> entry : new TreeMap<>(SAMPLED_CALLERS).entrySet()) {
            json.append(first ? "" : ", ").append('"').append(entry.getKey()).append("\": ")
                    .append(entry.getValue().get());
            first = false;
        }
        return json.append("}}").toString();
    }
}
