package dev.gaius.browser;

/**
 * Eagerly initializes a class while remaining runnable on a regular JVM.
 *
 * <p>The browser class library supplies {@code Class.initialize()} as a
 * TeaVM intrinsic. The browser patcher rewrites this method to that intrinsic;
 * the source form deliberately uses the standard JVM operation so JVM smokes
 * can load the patched game jars.</p>
 */
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
