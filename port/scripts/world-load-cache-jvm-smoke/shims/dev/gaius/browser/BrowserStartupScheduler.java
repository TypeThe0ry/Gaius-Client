package dev.gaius.browser;

/** JVM shim: the startup phases of the patched server Main only record their order. */
public final class BrowserStartupScheduler {
    static final StringBuilder PHASES = new StringBuilder();

    private BrowserStartupScheduler() {
    }

    public static void phase(String phase) {
        PHASES.append(phase).append(' ');
    }
}
