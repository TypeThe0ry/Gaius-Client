package dev.gaius.browser;

/** JVM-compatible source form; the browser patcher replaces the body with TeaVM Class.initialize. */
public final class BrowserClassInitialization {
    private BrowserClassInitialization() {
    }

    public static void initialize(Class<?> type) {
        try {
            Class.forName(type.getName());
        } catch (ClassNotFoundException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
