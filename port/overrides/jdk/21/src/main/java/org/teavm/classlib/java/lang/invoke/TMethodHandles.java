package org.teavm.classlib.java.lang.invoke;

/**
 * Browser stub for {@code java.lang.invoke.MethodHandles}. The TeaVM 0.15
 * classlib shell only provides the constructor; the Gaius build additionally
 * needs {@code lookup()} and {@code publicLookup()} because Netty and Guava
 * reference them. Lookups are inert in the browser. The nested Lookup class
 * compiles to TMethodHandles$Lookup, matching TeaVM's classlib mapping.
 */
public class TMethodHandles {
    public TMethodHandles() {
    }

    public static Lookup lookup() {
        return new Lookup();
    }

    public static Lookup publicLookup() {
        return new Lookup();
    }

    public static final class Lookup {
        public Lookup() {
        }

        public TVarHandle findVarHandle(Class<?> declaringClass, String name, Class<?> valueType) {
            return null;
        }
    }
}
