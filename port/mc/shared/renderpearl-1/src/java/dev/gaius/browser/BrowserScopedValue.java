package dev.gaius.browser;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Stand-in for {@code java.lang.ScopedValue} (JDK 25), which TeaVM 0.15's class library
 * lacks. {@code JdkCompatPatches263} renames {@code java/lang/ScopedValue},
 * {@code ScopedValue$Carrier} and {@code ScopedValue$CallableOp} to this class and its
 * nested types in every class of the 26.3 client jar that uses them (in 26.3 only
 * {@code SolidDebugger}, reached from {@code Blocks.<clinit>} in the client and the Worker).
 *
 * <p>Same public API and semantics as the JDK class: a binding made by
 * {@link Carrier#run}/{@link Carrier#call} is visible to the calling thread only, for the
 * duration of the operation; the innermost binding of a value wins, and within one carrier
 * the last {@code where}; the previous bindings come back when the operation returns or
 * throws. {@link #get()} of an unbound value throws NoSuchElementException("ScopedValue not
 * bound"), {@link Carrier#get} of a missing key NoSuchElementException("No mapping
 * present"); null keys, operations, {@code orElse} defaults and exception suppliers throw
 * NullPointerException, null values are allowed. Not modelled: inheritance into
 * StructuredTaskScope forks (TeaVM has none) and the JDK's per-thread lookup cache.
 * browser-jdk-compat-smoke.mjs runs one scenario against both classes.
 *
 * <p>Only the renderpearl-1 source set has this class (port/mc/shared/renderpearl-1).
 *
 * @param <T> the type of the value
 */
public final class BrowserScopedValue<T> {
    private static final Object UNBOUND = new Object();
    /** The calling thread's bindings, innermost first; no entry when there are none. */
    private static final ThreadLocal<Snapshot> BINDINGS = new ThreadLocal<>();

    private BrowserScopedValue() {
    }

    public static <T> BrowserScopedValue<T> newInstance() {
        return new BrowserScopedValue<>();
    }

    public static <T> Carrier where(BrowserScopedValue<T> key, T value) {
        return new Carrier(Objects.requireNonNull(key), value, null);
    }

    public T get() {
        Object value = find();
        if (value == UNBOUND) {
            throw new NoSuchElementException("ScopedValue not bound");
        }
        return cast(value);
    }

    public boolean isBound() {
        return find() != UNBOUND;
    }

    public T orElse(T other) {
        Objects.requireNonNull(other);
        Object value = find();
        return value == UNBOUND ? other : cast(value);
    }

    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        Objects.requireNonNull(exceptionSupplier);
        Object value = find();
        if (value == UNBOUND) {
            throw exceptionSupplier.get();
        }
        return cast(value);
    }

    private Object find() {
        for (Snapshot snapshot = BINDINGS.get(); snapshot != null; snapshot = snapshot.previous) {
            for (Carrier carrier = snapshot.bindings; carrier != null; carrier = carrier.previous) {
                if (carrier.key == this) {
                    return carrier.value;
                }
            }
        }
        return UNBOUND;
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static void restore(Snapshot previous) {
        if (previous == null) {
            BINDINGS.remove();
        } else {
            BINDINGS.set(previous);
        }
    }

    /** An immutable set of bindings, made by {@link #where}; the last binding is first. */
    public static final class Carrier {
        private final BrowserScopedValue<?> key;
        private final Object value;
        private final Carrier previous;

        Carrier(BrowserScopedValue<?> key, Object value, Carrier previous) {
            this.key = key;
            this.value = value;
            this.previous = previous;
        }

        public <T> Carrier where(BrowserScopedValue<T> key, T value) {
            return new Carrier(Objects.requireNonNull(key), value, this);
        }

        public <T> T get(BrowserScopedValue<T> key) {
            Objects.requireNonNull(key);
            for (Carrier carrier = this; carrier != null; carrier = carrier.previous) {
                if (carrier.key == key) {
                    return cast(carrier.value);
                }
            }
            throw new NoSuchElementException("No mapping present");
        }

        public <R, X extends Throwable> R call(CallableOp<? extends R, X> op) throws X {
            Objects.requireNonNull(op);
            Snapshot previous = BINDINGS.get();
            BINDINGS.set(new Snapshot(this, previous));
            try {
                return op.call();
            } finally {
                restore(previous);
            }
        }

        public void run(Runnable op) {
            Objects.requireNonNull(op);
            Snapshot previous = BINDINGS.get();
            BINDINGS.set(new Snapshot(this, previous));
            try {
                op.run();
            } finally {
                restore(previous);
            }
        }
    }

    /** {@code ScopedValue.CallableOp}: an operation that returns a value and may throw. */
    @FunctionalInterface
    public interface CallableOp<T, X extends Throwable> {
        T call() throws X;
    }

    private static final class Snapshot {
        final Carrier bindings;
        final Snapshot previous;

        Snapshot(Carrier bindings, Snapshot previous) {
            this.bindings = bindings;
            this.previous = previous;
        }
    }
}
