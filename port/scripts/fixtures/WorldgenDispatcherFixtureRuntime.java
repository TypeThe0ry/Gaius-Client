package dev.gaius.browser;

/** Test-only clock and telemetry sink swapped into BrowserWorldgenDispatcherScheduler. */
public final class WorldgenDispatcherFixtureRuntime {
    public static long nanoTime;
    public static final int[] stopsByReason = new int[6];
    public static int turns;
    public static int runnables;
    public static int lastTurnRunnables;
    public static int threadHops;
    public static int messageHops;

    private WorldgenDispatcherFixtureRuntime() {
    }

    public static void reset() {
        nanoTime = 0L;
        java.util.Arrays.fill(stopsByReason, 0);
        turns = 0;
        runnables = 0;
        lastTurnRunnables = 0;
        threadHops = 0;
        messageHops = 0;
    }

    static void turn(int count, int reason, double elapsedMillis) {
        turns++;
        runnables += count;
        lastTurnRunnables = count;
        stopsByReason[reason]++;
    }

    static void hop(int kind) {
        if (kind == 1) {
            messageHops++;
        } else {
            threadHops++;
        }
    }
}
