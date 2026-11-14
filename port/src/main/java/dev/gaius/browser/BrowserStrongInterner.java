package dev.gaius.browser;

import com.google.common.collect.Interner;
import java.util.HashMap;
import java.util.Objects;

/**
 * A strong {@link Interner} over a plain {@link HashMap}, for interners that only one browser
 * thread uses (Minecraft262BrowserPatcher.patchModelBakeryInterner).
 *
 * <p>It returns what Guava's {@code Interners.newStrongInterner()} returns for the same sequence of
 * samples: the first sample of each equality class is the canonical instance and is never
 * removed, lookups use the sample's {@code hashCode} and {@code sample.equals(canonical)}, and a
 * null sample throws {@link NullPointerException}. Guava's concurrent segments, locks and entry
 * objects are not needed when no other thread can call {@link #intern} at the same time; the map is
 * not safe for concurrent use.
 */
public final class BrowserStrongInterner<E> implements Interner<E> {
    private final HashMap<E, E> canonical = new HashMap<>();

    private BrowserStrongInterner() {
    }

    public static <E> Interner<E> create() {
        return new BrowserStrongInterner<>();
    }

    @Override
    public E intern(E sample) {
        Objects.requireNonNull(sample);
        E existing = canonical.putIfAbsent(sample, sample);
        return existing == null ? sample : existing;
    }
}
