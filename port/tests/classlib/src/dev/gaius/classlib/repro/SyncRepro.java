package dev.gaius.classlib.repro;

/** A value method with a double-checked lock whose inner branch throws (S5 finding 1). */
public final class SyncRepro {
    private static final Object LOCK = new Object();
    static Object value;

    private SyncRepro() {
    }

    static Object get() {
        if (value == null) {
            synchronized (LOCK) {
                if (value == null) {
                    throw new IllegalStateException("missing");
                }
            }
        }
        return value;
    }

    public static void main(String[] args) {
        value = args;
        System.out.println(get() != null);
        System.out.println(get() == args);
    }
}
