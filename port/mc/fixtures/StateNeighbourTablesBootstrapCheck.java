import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * One side of the JVM gate of MinecraftClientPatcher.patchStateDefinitionNeighbourTables; the
 * runner (port/scripts/state-neighbour-tables-jvm.test.mjs) starts it in fresh JVMs on the vanilla
 * jar and with the patched StateDefinition and GaiusStateNeighbourTables first on the class path,
 * all with {@code -XX:hashCode=3} (identity hashes from one global sequence, as TeaVM assigns
 * them), and compares the files byte for byte:
 *
 * <ul>
 *   <li>{@code states.txt}: per block and per fluid (registry order) the properties in definition
 *       order with their values, then every state in {@code getPossibleStates()} order: its id in
 *       the state id mapper, toString, the classes of its key and value arrays, whether the key
 *       array is the definition's shared one, the values, and its neighbour table: the table's
 *       class and length, and per property the row's class, the row's identity (numbered by first
 *       appearance in the block) and the row as state indices;</li>
 *   <li>{@code identity.txt}: the identity hash right after bootstrap (a fresh object's, i.e. how
 *       many identity hashes the bootstrap requested), then the identity hash of every block,
 *       fluid, state, property and property value and of the StateCollection wildcard;</li>
 *   <li>{@code registries.txt}: every built-in registry (raw id, key, class per entry).</li>
 * </ul>
 *   java StateNeighbourTablesBootstrapCheck <name> <out dir> [primed|cold]
 */
public final class StateNeighbourTablesBootstrapCheck {
    static final class PrimeDone {
        static final boolean MARK = Boolean.parseBoolean("true");
    }

    static final class BootstrapDone {
        static final boolean MARK = Boolean.parseBoolean("true");
    }

    /** Multi-property definitions, and those whose values GaiusStateNeighbourTables takes. */
    static int multi;
    static int eligible;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        boolean primed = args.length < 3 || args[2].equals("primed");
        SharedConstants.tryDetectVersion();
        if (primed) {
            prime();
        }
        if (!PrimeDone.MARK) {
            throw new AssertionError();
        }
        Bootstrap.bootStrap();
        int afterBootstrap = System.identityHashCode(new Object());
        if (!BootstrapDone.MARK) {
            throw new AssertionError();
        }
        Field neighbors = StateHolder.class.getDeclaredField("neighbors");
        Field keys = StateHolder.class.getDeclaredField("propertyKeys");
        Field values = StateHolder.class.getDeclaredField("propertyValues");
        for (Field field : new Field[] {neighbors, keys, values}) {
            field.setAccessible(true);
        }

        List<String> states = new ArrayList<>();
        List<String> identity = new ArrayList<>();
        identity.add("afterBootstrap " + afterBootstrap);
        int definitions = 0;
        int stateCount = 0;
        int rows = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            String name = "block " + BuiltInRegistries.BLOCK.getKey(block);
            identity.add(name + " " + System.identityHashCode(block));
            int[] counts = dump(name, block.getStateDefinition(), state -> Block.BLOCK_STATE_REGISTRY.getId((BlockState) state), neighbors, keys,
                    values, states, identity);
            definitions++;
            stateCount += counts[0];
            rows += counts[1];
        }
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            String name = "fluid " + BuiltInRegistries.FLUID.getKey(fluid);
            identity.add(name + " " + System.identityHashCode(fluid));
            int[] counts = dump(name, fluid.getStateDefinition(), state -> Fluid.FLUID_STATE_REGISTRY.getId((FluidState) state), neighbors, keys,
                    values, states, identity);
            definitions++;
            stateCount += counts[0];
            rows += counts[1];
        }
        Class<?> wildcard = Class.forName("net.minecraft.world.level.block.state.StateDefinition$StateCollection$Wildcard");
        Field instance = wildcard.getField("INSTANCE");
        instance.setAccessible(true);
        identity.add("wildcard " + System.identityHashCode(instance.get(null)));
        write(out.resolve("states.txt"), states);
        write(out.resolve("identity.txt"), identity);

        List<String> registries = new ArrayList<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            dumpRegistry(registry, registries);
        }
        write(out.resolve("registries.txt"), registries);
        System.out.println("STATE_NEIGHBOUR_GATE run=" + args[0] + " definitions=" + definitions + " states="
                + stateCount + " rows=" + rows + " multiProperty=" + multi + " helperEligible=" + eligible + " afterBootstrap=" + afterBootstrap + " registryLines="
                + registries.size());
        System.exit(0);
    }

    /** A property without values: the patched prologue hands it to the vanilla body. */
    static final class EmptyProperty extends Property<Integer> {
        EmptyProperty(String name) {
            super(name, Integer.class);
        }

        @Override
        public List<Integer> getPossibleValues() {
            return List.of();
        }

        @Override
        public String getName(Integer value) {
            return value.toString();
        }

        @Override
        public java.util.Optional<Integer> getValue(String name) {
            return java.util.Optional.empty();
        }

        @Override
        public int getInternalIndex(Integer value) {
            return -1;
        }
    }

    /** A two-valued property for the priming definition. */
    static final class FlagProperty extends Property<Boolean> {
        FlagProperty(String name) {
            super(name, Boolean.class);
        }

        @Override
        public List<Boolean> getPossibleValues() {
            return List.of(true, false);
        }

        @Override
        public String getName(Boolean value) {
            return value.toString();
        }

        @Override
        public java.util.Optional<Boolean> getValue(String name) {
            return java.util.Optional.empty();
        }

        @Override
        public int getInternalIndex(Boolean value) {
            return value ? 0 : 1;
        }
    }

    static final class PrimingState extends StateHolder<Object, PrimingState> {
        PrimingState(Object owner, Property<?>[] keys, Comparable<?>[] values) {
            super(owner, keys, values);
        }
    }

    enum Mark {
        INSTANCE
    }

    /**
     * Runs, in both JVMs before the bootstrap and on throwaway objects, every library path of
     * vanilla's createMultiPropertyStates and of GaiusStateNeighbourTables once: the vanilla body
     * on two properties without values (no state, so no value and no wildcard is hashed; the
     * patched prologue hands an empty value list to the vanilla body), the helper on a
     * throwaway two-flag definition, and the JDK/Guava calls of vanilla's state loop and
     * neighbour visit on throwaway lists. The first use of such a path makes the JVM itself ask
     * for identity hashes (class loading, linking the body's invokedynamic, reflection); TeaVM
     * compiles all of this ahead of time. Without priming, the vanilla bootstrap makes these
     * requests at its first multi-property definition and the patched one at other points, which
     * shifts every later JVM identity hash and hides what the gate compares: the identity hash
     * requests of the game code.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void prime() throws Exception {
        java.lang.reflect.Method multi = StateDefinition.class.getDeclaredMethod("createMultiPropertyStates",
                Object.class, StateDefinition.Factory.class, Map.class);
        multi.setAccessible(true);
        Map<String, Property<?>> empty = new java.util.TreeMap<>();
        empty.put("a", new EmptyProperty("a"));
        empty.put("b", new EmptyProperty("b"));
        StateDefinition.Factory none = (owner, keys, values) -> {
            throw new AssertionError("no state expected");
        };
        List<?> states = (List<?>) multi.invoke(null, new Object(), none, empty);
        if (!states.isEmpty()) {
            throw new AssertionError("states from empty properties");
        }

        com.google.common.collect.ImmutableSortedMap<String, Property<?>> flags =
                com.google.common.collect.ImmutableSortedMap.of("c", new FlagProperty("c"), "d", new FlagProperty("d"));
        StateDefinition.Factory<Object, PrimingState> factory = PrimingState::new;
        Object tables = net.minecraft.world.level.block.state.GaiusStateNeighbourTables.create(new Object(), factory, flags);
        if (((net.minecraft.world.level.block.state.GaiusStateNeighbourTables) tables).finish(new Object()).size() != 4) {
            throw new AssertionError("priming states");
        }

        // vanilla's state loop and neighbour visit, on throwaway lists
        Property<?>[] keys = flags.values().toArray(new Property<?>[0]);
        List<List<Comparable<?>>> lists = new ArrayList<>(keys.length);
        for (Property<?> key : keys) {
            lists.add((List) key.getPossibleValues());
        }
        Map<List<Comparable<?>>, Object> byValues = new java.util.HashMap<>();
        com.google.common.collect.ImmutableList.Builder<Object> builder =
                com.google.common.collect.ImmutableList.builderWithExpectedSize(4);
        for (List<Comparable<?>> combination : com.google.common.collect.Lists.cartesianProduct(lists)) {
            List<Comparable<?>> copy = List.copyOf(combination);
            Object[] array = copy.toArray(new Comparable<?>[0]);
            byValues.put(copy, array);
            builder.add((Object) array);
        }
        Map<List<Comparable<?>>, Object[]> cache = new java.util.HashMap<>();
        byValues.forEach((values, state) -> {
            List<Object> pivot = new ArrayList<>(values);
            for (int p = 0; p < pivot.size(); p++) {
                Object previous = pivot.set(p, Mark.INSTANCE);
                if (cache.get(pivot) == null) {
                    pivot.set(p, previous);
                    java.util.Objects.requireNonNull(byValues.get(pivot));
                    pivot.set(p, Mark.INSTANCE);
                    cache.put((List) List.copyOf(pivot), new Object[0]);
                }
                pivot.set(p, previous);
            }
        });
        if (builder.build().size() != 4) {
            throw new AssertionError("priming loop");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int[] dump(String name, StateDefinition definition, java.util.function.ToIntFunction<Object> ids,
            Field neighbors, Field keys, Field values, List<String> states, List<String> identity) throws Exception {
        List<StateHolder> possible = definition.getPossibleStates();
        StringBuilder header = new StringBuilder(name).append(" states=").append(possible.size())
                .append(" list=").append(possible.getClass().getName());
        for (Object object : definition.getProperties()) {
            Property property = (Property) object;
            header.append(' ').append(property.getName()).append('=').append(property.getPossibleValues());
            identity.add("  property " + property.getName() + " " + System.identityHashCode(property));
            for (Object value : property.getPossibleValues()) {
                identity.add("    value " + value + " " + System.identityHashCode(value));
            }
        }
        states.add(header.toString());
        if (definition.getProperties().size() > 1) {
            multi++;
            boolean takes = true;
            for (Object object : definition.getProperties()) {
                List<?> valueList = ((Property) object).getPossibleValues();
                for (int k = 0; k < valueList.size(); k++) {
                    Object value = valueList.get(k);
                    takes &= value instanceof Boolean || value instanceof Integer || value instanceof Enum<?>;
                    takes &= valueList.indexOf(value) == k;
                }
            }
            if (takes) {
                eligible++;
            }
        }
        Map<Object, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < possible.size(); i++) {
            if (index.put(possible.get(i), i) != null) {
                throw new IllegalStateException(name + ": state listed twice");
            }
        }
        Map<Object, Integer> rowIds = new IdentityHashMap<>();
        Object sharedKeys = possible.isEmpty() ? null : keys.get(possible.get(0));
        for (int i = 0; i < possible.size(); i++) {
            StateHolder state = possible.get(i);
            identity.add("  state " + i + " " + System.identityHashCode(state));
            Object[] stateKeys = (Object[]) keys.get(state);
            Object[] stateValues = (Object[]) values.get(state);
            StringBuilder line = new StringBuilder("  #").append(i).append(" id=").append(ids.applyAsInt(state))
                    .append(' ').append(state).append(" keys=").append(stateKeys.getClass().getName())
                    .append(stateKeys == sharedKeys ? " shared" : " own").append(" values=")
                    .append(stateValues.getClass().getName()).append(java.util.Arrays.toString(stateValues));
            Object[][] table = (Object[][]) neighbors.get(state);
            if (table == null) {
                line.append(" table=null");
            } else {
                line.append(" table=").append(table.getClass().getName()).append('[').append(table.length).append(']');
                for (int p = 0; p < table.length; p++) {
                    Object[] row = table[p];
                    if (row == null) {
                        line.append(" | null");
                        continue;
                    }
                    Integer rowId = rowIds.get(row);
                    if (rowId == null) {
                        rowId = rowIds.size();
                        rowIds.put(row, rowId);
                    }
                    line.append(" | r").append(rowId).append(' ').append(row.getClass().getName()).append(' ');
                    for (int k = 0; k < row.length; k++) {
                        Integer target = index.get(row[k]);
                        line.append(k == 0 ? "" : ",").append(target == null ? "?" + row[k] : target);
                    }
                }
            }
            states.add(line.toString());
        }
        return new int[] {possible.size(), rowIds.size()};
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void dumpRegistry(Registry registry, List<String> lines) {
        lines.add("registry " + registry.key() + " size=" + registry.size());
        for (Object value : registry) {
            lines.add("  " + registry.getId(value) + " " + registry.getKey(value) + " "
                    + value.getClass().getName().replaceAll("\\$\\$Lambda.*", "\\$\\$Lambda"));
        }
    }

    private static void write(Path file, List<String> lines) throws Exception {
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
