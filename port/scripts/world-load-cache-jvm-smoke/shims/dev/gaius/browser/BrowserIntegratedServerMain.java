package dev.gaius.browser;

/** JVM stand-in for the one member of the browser server entry point that BrowserWorldLoadCache calls. */
final class BrowserIntegratedServerMain {
    private BrowserIntegratedServerMain() {
    }

    static void reportRuntimeEvent(String event, String detail) {
        System.out.println("EVENT " + event + " " + detail);
    }
}
