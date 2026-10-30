package net.minecraft.world.level.biome;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Exact, flat copy of a vanilla multi-noise {@code Climate.RTree} for the WasmGC server.
 *
 * <p>Vanilla answers every biome lookup with {@code RTree.search}: a recursive walk over
 * {@code SubTree}/{@code Leaf} objects that calls the {@code DistanceMetric} interface (always
 * {@code RTree.Node::distance} for {@code ParameterList.findValue}) on each child, chases seven
 * {@code Climate.Parameter} objects per distance and recomputes the distance of the current best
 * leaf on entry to and return from every subtree. In TeaVM WasmGC each of those is an interface
 * dispatch or a GC struct load, and the search was the single largest piece of vanilla biome
 * generation in the server profile.
 *
 * <p>This index keeps the vanilla tree, visit order and comparisons, and only changes how the
 * numbers are fetched: node bounds live in one {@code long[]} (min/max per dimension, seven
 * dimensions per node, breadth-first so the children of a node are adjacent, in vanilla order),
 * and the running best distance is carried down instead of being recomputed. Every pruning test is
 * vanilla's {@code best <= distance} (ties keep the earlier leaf), the distance is vanilla's
 * {@code Parameter.distance} squared and summed in 64-bit two's complement, and the search starts
 * from the same {@code lastResult} candidate, which the patched {@code RTree.search} reads and
 * writes as vanilla does. The returned leaf, and with it the per-thread history that later lookups
 * depend on, is therefore identical to vanilla for every input
 * ({@code port/scripts/climate-rtree-parity.mjs} checks this on the JVM).
 *
 * <p>The patched {@code ParameterList.findValueIndex(TargetPoint)} passes {@link #NODE_DISTANCE};
 * any other metric, a tree whose shape this class does not recognise, and an unpatched
 * {@code SubTree} take the vanilla recursive search.
 */
public final class GaiusClimateIndex {
    /** The metric vanilla's {@code ParameterList.findValueIndex(TargetPoint)} creates per call. */
    public static final Climate.DistanceMetric<Object> NODE_DISTANCE = Climate.RTree.Node::distance;

    private static final int DIMENSIONS = 7;
    private static final int STRIDE = DIMENSIONS * 2;
    private static final int LEAF = -1;
    /** Trees indexed at once: overworld and nether of the current and one previous world. */
    private static final int MAX_CACHED_TREES = 4;
    private static final Object UNSUPPORTED = new Object();

    /** Pairs of (root node, index or {@link #UNSUPPORTED}), most recently built first. */
    private static volatile Object[] cache = new Object[0];

    private final long[] bounds;
    private final int[] childStart;
    private final int[] childEnd;
    /** The leaf node of each index, null for subtrees. */
    private final Climate.RTree.Node<?>[] leaves;

    private GaiusClimateIndex(
            long[] bounds, int[] childStart, int[] childEnd, Climate.RTree.Node<?>[] leaves) {
        this.bounds = bounds;
        this.childStart = childStart;
        this.childEnd = childEnd;
        this.leaves = leaves;
    }

    /**
     * The nearest leaf for {@code values} with the {@code candidate} vanilla's {@code RTree.search}
     * read from {@code lastResult}, or null when this class does not handle the call (another
     * metric, or a tree it cannot index); the patched {@code RTree.search} then runs vanilla's
     * {@code root.search(values, candidate, metric)} itself. The leaf type is private to
     * {@code RTree}, so leaves travel as {@code Node}/{@code Object} here and the patched caller
     * casts them back.
     */
    public static Object find(
            Climate.RTree.Node<?> root, long[] values, Object candidate, Climate.DistanceMetric<?> metric) {
        if (metric != NODE_DISTANCE) {
            return null;
        }
        GaiusClimateIndex index = indexFor(root);
        return index == null ? null : index.find(values, (Climate.RTree.Node<?>) candidate);
    }

    private Climate.RTree.Node<?> find(long[] values, Climate.RTree.Node<?> candidate) {
        if (childStart[0] == LEAF) {
            // Leaf.search returns the leaf itself whatever the candidate.
            return leaves[0];
        }
        Cursor cursor = new Cursor(
                candidate == null ? Long.MAX_VALUE : candidate.distance(values));
        visit(0, values, cursor);
        return cursor.best == LEAF ? candidate : leaves[cursor.best];
    }

    /**
     * {@code SubTree.search} on the flat arrays. Entering a child subtree with the current best
     * leaf and its distance is what vanilla's recursive call does (it recomputes that same
     * distance), and the child's improved best is exactly what vanilla's caller then accepts.
     */
    private void visit(int node, long[] values, Cursor cursor) {
        int end = childEnd[node];
        for (int child = childStart[node]; child < end; child++) {
            long distance = distance(child, values);
            if (cursor.min <= distance) {
                continue;
            }
            if (childStart[child] == LEAF) {
                cursor.min = distance;
                cursor.best = child;
            } else {
                visit(child, values, cursor);
            }
        }
    }

    /** {@code RTree.Node.distance}: sum over the seven dimensions of the squared Parameter.distance. */
    private long distance(int node, long[] values) {
        long[] b = bounds;
        int o = node * STRIDE;
        return square(values[0], b[o], b[o + 1])
                + square(values[1], b[o + 2], b[o + 3])
                + square(values[2], b[o + 4], b[o + 5])
                + square(values[3], b[o + 6], b[o + 7])
                + square(values[4], b[o + 8], b[o + 9])
                + square(values[5], b[o + 10], b[o + 11])
                + square(values[6], b[o + 12], b[o + 13]);
    }

    /** {@code Mth.square(Parameter.distance(value))}. */
    private static long square(long value, long min, long max) {
        long above = value - max;
        long below = min - value;
        long distance = above > 0L ? above : Math.max(below, 0L);
        return distance * distance;
    }

    private static GaiusClimateIndex indexFor(Climate.RTree.Node<?> root) {
        Object[] entries = cache;
        for (int i = 0; i < entries.length; i += 2) {
            if (entries[i] == root) {
                Object index = entries[i + 1];
                return index == UNSUPPORTED ? null : (GaiusClimateIndex) index;
            }
        }
        GaiusClimateIndex index = build(root);
        // Racing builders may drop each other's entry; that only costs a rebuild later.
        int kept = Math.min(entries.length, (MAX_CACHED_TREES - 1) * 2);
        Object[] updated = new Object[kept + 2];
        updated[0] = root;
        updated[1] = index == null ? UNSUPPORTED : index;
        System.arraycopy(entries, 0, updated, 2, kept);
        cache = updated;
        return index;
    }

    /** Breadth-first copy of the tree, or null when it is not the vanilla shape this class reads. */
    static GaiusClimateIndex build(Climate.RTree.Node<?> root) {
        ArrayList<Climate.RTree.Node<?>> nodes = new ArrayList<>();
        nodes.add(root);
        int[] childStart = new int[16];
        int[] childEnd = new int[16];
        for (int i = 0; i < nodes.size(); i++) {
            Climate.RTree.Node<?> node = nodes.get(i);
            if (childStart.length <= i) {
                childStart = Arrays.copyOf(childStart, childStart.length * 2);
                childEnd = Arrays.copyOf(childEnd, childEnd.length * 2);
            }
            if (node instanceof GaiusClimateSubTree subTree) {
                Climate.RTree.Node<?>[] children = subTree.gaius$children();
                if (children == null) {
                    return null;
                }
                childStart[i] = nodes.size();
                for (Climate.RTree.Node<?> child : children) {
                    if (child == null) {
                        return null;
                    }
                    nodes.add(child);
                }
                childEnd[i] = nodes.size();
            } else {
                // Node has two subclasses, SubTree and Leaf (the patcher checks the jar).
                childStart[i] = LEAF;
                childEnd[i] = LEAF;
            }
        }
        int count = nodes.size();
        long[] bounds = new long[count * STRIDE];
        Climate.RTree.Node<?>[] leaves = new Climate.RTree.Node<?>[count];
        for (int i = 0; i < count; i++) {
            Climate.RTree.Node<?> node = nodes.get(i);
            Climate.Parameter[] space = node.parameterSpace;
            if (space == null || space.length < DIMENSIONS) {
                return null;
            }
            for (int dimension = 0; dimension < DIMENSIONS; dimension++) {
                Climate.Parameter parameter = space[dimension];
                if (parameter == null) {
                    return null;
                }
                bounds[i * STRIDE + dimension * 2] = parameter.min();
                bounds[i * STRIDE + dimension * 2 + 1] = parameter.max();
            }
            if (childStart[i] == LEAF) {
                leaves[i] = node;
            }
        }
        return new GaiusClimateIndex(
                bounds,
                Arrays.copyOf(childStart, count),
                Arrays.copyOf(childEnd, count),
                leaves);
    }

    private static final class Cursor {
        long min;
        int best = LEAF;

        Cursor(long min) {
            this.min = min;
        }
    }
}
