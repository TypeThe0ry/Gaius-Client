package dev.gaius.gpu.webgpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the constants of a render SPI enum (a Minecraft type) to a core enum by {@code name()},
 * through an ordinal table built once; an alias table covers SPIs whose names differ from the
 * core's (the core names follow renderpearl). Constants without a core counterpart map to
 * {@code null} and are listed by {@link #missing()}, which a seam's completeness test asserts to
 * be empty for every profile that selects the seam.
 *
 * @param <V> the SPI enum
 * @param <C> the core enum
 */
public final class EnumBridge<V extends Enum<V>, C extends Enum<C>> {
    private final String name;
    private final Object[] table;
    private final List<String> missing;
    private final List<String> unused;

    private EnumBridge(String name, Object[] table, List<String> missing, List<String> unused) {
        this.name = name;
        this.table = table;
        this.missing = missing;
        this.unused = unused;
    }

    /** A bridge by constant name; {@code aliases} maps SPI names to core names. */
    public static <V extends Enum<V>, C extends Enum<C>> EnumBridge<V, C> byName(String name, V[] spiValues,
            C[] coreValues, Map<String, String> aliases) {
        Map<String, C> core = new HashMap<>();
        for (C value : coreValues) {
            core.put(value.name(), value);
        }
        Object[] table = new Object[spiValues.length];
        List<String> missing = new ArrayList<>();
        Map<String, Boolean> used = new HashMap<>();
        for (V value : spiValues) {
            String coreName = aliases.getOrDefault(value.name(), value.name());
            C mapped = core.get(coreName);
            if (mapped == null) {
                missing.add(value.name());
            } else {
                table[value.ordinal()] = mapped;
                used.put(coreName, Boolean.TRUE);
            }
        }
        List<String> unused = new ArrayList<>();
        for (C value : coreValues) {
            if (!used.containsKey(value.name())) {
                unused.add(value.name());
            }
        }
        return new EnumBridge<>(name, table, Collections.unmodifiableList(missing), Collections.unmodifiableList(unused));
    }

    public static <V extends Enum<V>, C extends Enum<C>> EnumBridge<V, C> byName(String name, V[] spiValues,
            C[] coreValues) {
        return byName(name, spiValues, coreValues, Collections.emptyMap());
    }

    /** The core constant for {@code value}; throws when the SPI constant has no counterpart. */
    @SuppressWarnings("unchecked")
    public C toCore(V value) {
        Object mapped = this.table[value.ordinal()];
        if (mapped == null) {
            throw new IllegalArgumentException(this.name + ": no core constant for " + value.name());
        }
        return (C) mapped;
    }

    public String name() {
        return this.name;
    }

    /** SPI constants without a core constant (must be empty). */
    public List<String> missing() {
        return this.missing;
    }

    /** Core constants no SPI constant maps to (allowed: the core serves several SPIs). */
    public List<String> unused() {
        return this.unused;
    }
}
