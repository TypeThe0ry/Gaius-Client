package net.minecraft.world.level.block.state;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The states and neighbour tables of a multi-property {@link StateDefinition}, built by mixed-radix
 * index arithmetic instead of vanilla's {@code HashMap<List<Comparable>, S>} and the
 * {@code StateCollection} pivot cache. {@code MinecraftClientPatcher.patchStateDefinitionNeighbourTables}
 * makes {@code StateDefinition.createMultiPropertyStates} call {@link #create} and, when it returns a
 * table, {@link #finish} with the {@code StateCollection.Wildcard} constant; when {@link #create}
 * returns null nothing has happened yet and the vanilla body runs. Lives in the vanilla package
 * because {@code StateHolder.initializeNeighbors} is package-private.
 *
 * <p>Vanilla (StateDefinition.createMultiPropertyStates) takes the properties in name order, the
 * cartesian product of their possible-value lists (Guava: the first list varies slowest), creates
 * one state per combination in that order through the factory with a fresh {@code Comparable[]} of
 * the combination, puts {@code List.copyOf(combination) -> state} into a HashMap and collects the
 * states into an {@code ImmutableList}. It then visits the map and gives each state a
 * {@code StateHolder[keys.length][]} table whose row {@code p} is a {@code StateHolder[]} of the
 * states that differ from it only in property {@code p}, in possible-value order; rows are cached
 * by the combination with a wildcard at {@code p}, so every state of such a line shares one row
 * array. The map and the cache are dropped afterwards.
 *
 * <p>With pairwise distinct possible values (checked here), combination {@code i} has digit
 * {@code (i / stride[p]) % size[p]} for property {@code p}, {@code stride[last] = 1}; the state
 * with value index {@code k} for {@code p} and the other values of state {@code i} is
 * {@code i + (k - digit) * stride[p]}. This class creates the same states in the same order with
 * the same arguments, returns the same list, and gives every state a table of the same runtime
 * classes and contents, sharing one row array per (property, line) as vanilla does. The visiting
 * order of the map only decided which state of a line allocated the shared row first, which has no
 * effect.
 *
 * <p>Identity hashes: TeaVM gives an object its identity hash at its first {@code hashCode()} from
 * a global sequence, so the order of first identity-hash requests is observable later (every
 * HashMap keyed by enum constants). Vanilla's put hashes each combination list, which calls
 * {@code hashCode()} on every value of the combination in property order right after the state
 * is created; {@link #create} makes exactly these calls at the same points. The map visit and the
 * pivot cache hash lists of values (all hashed before) and the wildcard; the wildcard's first hash
 * is the only new identity request there, so {@link #finish} hashes it once before filling the
 * tables (the patch only loads the constant there, after the states exist, where vanilla first
 * touches it). Values are therefore limited to Boolean, Integer and enum constants, whose
 * {@code hashCode()} has no other effect; any other value class, a null or repeated value, an
 * empty value list or a product larger than an int make {@link #create} return null so that
 * vanilla runs (and fails or not exactly as before).
 */
public final class GaiusStateNeighbourTables<O, S extends StateHolder<O, S>> {
    private final Property<?>[] keys;
    private final int[] sizes;
    private final int[] strides;
    private final StateHolder<?, ?>[] states;
    private final ImmutableList<S> list;

    private GaiusStateNeighbourTables(Property<?>[] keys, int[] sizes, int[] strides,
            StateHolder<?, ?>[] states, ImmutableList<S> list) {
        this.keys = keys;
        this.sizes = sizes;
        this.strides = strides;
        this.states = states;
        this.list = list;
    }

    /**
     * Creates the states of a multi-property definition in vanilla order, or returns null (before
     * creating anything) when the values are outside what this class reproduces.
     */
    public static <O, S extends StateHolder<O, S>> GaiusStateNeighbourTables<O, S> create(
            O owner, StateDefinition.Factory<O, S> factory, Map<String, Property<?>> properties) {
        Property<?>[] keys = properties.values().toArray(new Property<?>[0]);
        int count = keys.length;
        if (count == 0) {
            return null;
        }
        Object[][] values = new Object[count][];
        int[] sizes = new int[count];
        for (int p = 0; p < count; p++) {
            List<?> possible = keys[p].getPossibleValues();
            Object[] row = possible.toArray();
            if (row.length == 0) {
                return null;
            }
            for (int k = 0; k < row.length; k++) {
                Object value = row[k];
                if (!(value instanceof Boolean || value instanceof Integer || value instanceof Enum<?>)) {
                    return null;
                }
                for (int other = 0; other < k; other++) {
                    if (row[other].equals(value)) {
                        return null;
                    }
                }
            }
            values[p] = row;
            sizes[p] = row.length;
        }
        int[] strides = new int[count];
        int total = 1;
        for (int p = count - 1; p >= 0; p--) {
            strides[p] = total;
            long next = (long) total * sizes[p];
            if (next > Integer.MAX_VALUE) {
                return null;
            }
            total = (int) next;
        }

        StateHolder<?, ?>[] states = new StateHolder<?, ?>[total];
        ImmutableList.Builder<S> builder = ImmutableList.builderWithExpectedSize(total);
        int[] digits = new int[count];
        for (int index = 0; index < total; index++) {
            Comparable<?>[] combination = new Comparable<?>[count];
            for (int p = 0; p < count; p++) {
                combination[p] = (Comparable<?>) values[p][digits[p]];
            }
            S state = factory.create(owner, keys, combination);
            // Vanilla's HashMap.put(List.copyOf(combination), state): the list hash calls
            // hashCode() on each value in order.
            for (int p = 0; p < count; p++) {
                values[p][digits[p]].hashCode();
            }
            states[index] = state;
            builder.add(state);
            for (int p = count - 1; p >= 0; p--) {
                if (++digits[p] < sizes[p]) {
                    break;
                }
                digits[p] = 0;
            }
        }
        return new GaiusStateNeighbourTables<>(keys, sizes, strides, states, builder.build());
    }

    /**
     * Gives every state its neighbour table and returns the states. {@code wildcard} is the
     * {@code StateCollection.Wildcard} constant; vanilla's first pivot lookup hashes it.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ImmutableList<S> finish(Object wildcard) {
        int total = states.length;
        int count = keys.length;
        wildcard.hashCode();
        StateHolder<?, ?>[][][] tables = new StateHolder<?, ?>[total][][];
        for (int index = 0; index < total; index++) {
            tables[index] = new StateHolder<?, ?>[count][];
        }
        for (int p = 0; p < count; p++) {
            int size = sizes[p];
            int stride = strides[p];
            int block = stride * size;
            for (int high = 0; high < total; high += block) {
                for (int low = 0; low < stride; low++) {
                    int base = high + low;
                    StateHolder<?, ?>[] row = new StateHolder<?, ?>[size];
                    for (int k = 0; k < size; k++) {
                        row[k] = states[base + k * stride];
                    }
                    for (int k = 0; k < size; k++) {
                        tables[base + k * stride][p] = row;
                    }
                }
            }
        }
        for (int index = 0; index < total; index++) {
            ((StateHolder) states[index]).initializeNeighbors(tables[index]);
        }
        return list;
    }
}
