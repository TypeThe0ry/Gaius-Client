package dev.gaius.browser;

import java.util.concurrent.atomic.AtomicInteger;

/** JVM stand-in for the browser generation-spawn telemetry (no JavaScript sink). */
public final class BrowserGenerationSpawnTelemetry {
    private static final AtomicInteger TOKENS = new AtomicInteger();

    private BrowserGenerationSpawnTelemetry() {
    }

    public static int begin(Object world, int chunkX, int chunkZ) {
        return TOKENS.incrementAndGet();
    }

    public static void entityAdded(int token) {
    }

    public static void complete(int token) {
    }

    public static void failed(int token) {
    }
}
