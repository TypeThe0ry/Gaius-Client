package dev.gaius.browser;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.PrimitiveTag;
import net.minecraft.nbt.Tag;

/**
 * Memoizes the NBT encoding of palette entries of a chunk section container.
 *
 * <p>{@code PalettedContainer.codec} encodes {@code palette} as {@code entryCodec.listOf()}, so every
 * saved section runs the block state (or biome holder) codec once per palette entry, through
 * several layers of {@code DataResult} and record builders: block states, {@code Name} dispatch,
 * {@code Properties} map. A chunk save repeats that for the same few hundred immutable block
 * states in each of its 24+ sections. The encoding of one entry is a pure function of the entry
 * (a registry object: the block state, or the holder of a biome), so the first encoding is kept and
 * later ones return an equal tree built from it.
 *
 * <p>Only the exact call chunk saving makes is memoized: {@link NbtOps#INSTANCE} with its empty
 * prefix, and only a plain success. Any other ops, a non-empty prefix, an error or a partial result
 * goes to the delegate unchanged, as do decoding and every other method of the codec.
 *
 * <p>The saved bytes are those of the delegate codec, including the order of the keys of a compound.
 * A compound is written in the iteration order of its hash map, and that order depends on the order
 * the keys were put in (keys that share a bucket come out in insertion order, or in reverse of it,
 * depending on the map implementation), so {@code CompoundTag.copy()} (which puts the keys in the
 * iteration order of the source) can write a different byte sequence than the codec did. The memo
 * therefore rebuilds a tree with a plan it has checked: each compound is rebuilt by putting its keys
 * in the source's iteration order or in the reverse of it, whichever gives back the source's
 * iteration order on this runtime when the entry is first memoized. If neither does (more keys than
 * the map's first resize, say), the entry is not memoized and every encoding of it runs the delegate.
 * The memo never hands out a tag it also keeps: the first caller gets the delegate's own result and the
 * memo holds a separate tree, and later callers get a fresh tree each time, so a caller that mutates
 * a returned tag cannot change what a later save sees.
 *
 * <p>The memo is per codec instance (one per {@code PalettedContainerFactory} codec) and capped, so a
 * stream of entries that are never shared cannot grow it without bound.
 */
public final class BrowserPaletteEntryCodec<T> implements Codec<T> {
    private static final int MAX_ENTRIES = 8192;
    private static long hits;
    private static long misses;
    private static long unplanned;

    private final Codec<T> delegate;
    private final IdentityHashMap<Object, Memo> memo = new IdentityHashMap<>();

    private BrowserPaletteEntryCodec(Codec<T> delegate) {
        this.delegate = delegate;
    }

    /** Called from the patched head of {@code PalettedContainer.codec}. */
    public static <T> Codec<T> wrap(Codec<T> delegate) {
        if (delegate == null || delegate instanceof BrowserPaletteEntryCodec) {
            return delegate;
        }
        return new BrowserPaletteEntryCodec<>(delegate);
    }

    /** Encodings served from a memo since start (diagnostics and tests). */
    public static long hits() {
        return hits;
    }

    /** Encodings that ran the delegate codec since start (diagnostics and tests). */
    public static long misses() {
        return misses;
    }

    /** Entries whose tree could not be rebuilt with the same key order, so they are never memoized. */
    public static long unplanned() {
        return unplanned;
    }

    @Override
    public <U> DataResult<Pair<T, U>> decode(DynamicOps<U> ops, U input) {
        return delegate.decode(ops, input);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <U> DataResult<U> encode(T input, DynamicOps<U> ops, U prefix) {
        if (input == null || ops != (Object) NbtOps.INSTANCE || prefix != (Object) NbtOps.INSTANCE.empty()) {
            return delegate.encode(input, ops, prefix);
        }
        Memo known;
        synchronized (memo) {
            known = memo.get(input);
        }
        if (known != null && known.plan != null) {
            hits++;
            return (DataResult<U>) (DataResult<?>) DataResult.success(known.plan.build(), known.lifecycle);
        }
        misses++;
        DataResult<U> result = delegate.encode(input, ops, prefix);
        if (known != null) {
            return result; // planned before and found unusable
        }
        Optional<U> value = result.result();
        if (value.isPresent() && result.error().isEmpty() && value.get() instanceof Tag tag) {
            Plan plan = Plan.of(tag);
            if (plan == null) {
                unplanned++;
            }
            synchronized (memo) {
                if (memo.size() < MAX_ENTRIES) {
                    memo.put(input, new Memo(plan, result.lifecycle()));
                }
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    private static final class Memo {
        final Plan plan;
        final Lifecycle lifecycle;

        Memo(Plan plan, Lifecycle lifecycle) {
            this.plan = plan;
            this.lifecycle = lifecycle;
        }
    }

    /** A private copy of a tag tree and how to rebuild a tree with the same key orders from it. */
    private abstract static class Plan {
        abstract Tag build();

        /** Null when the tree has a tag this class does not know how to rebuild or order. */
        static Plan of(Tag tag) {
            if (tag instanceof PrimitiveTag || tag instanceof EndTag) {
                return new Leaf(tag, false);
            }
            if (tag instanceof ByteArrayTag || tag instanceof IntArrayTag || tag instanceof LongArrayTag) {
                return new Leaf(tag.copy(), true);
            }
            if (tag instanceof ListTag list) {
                List<Plan> items = new ArrayList<>(list.size());
                for (int index = 0; index < list.size(); index++) {
                    Plan item = of(list.get(index));
                    if (item == null) {
                        return null;
                    }
                    items.add(item);
                }
                return new Items(items);
            }
            if (tag instanceof CompoundTag compound) {
                List<String> keys = new ArrayList<>(compound.keySet());
                List<Plan> values = new ArrayList<>(keys.size());
                for (String key : keys) {
                    Plan value = of(compound.get(key));
                    if (value == null) {
                        return null;
                    }
                    values.add(value);
                }
                Boolean reverse = insertionOrder(keys);
                return reverse == null ? null : new Fields(keys, values, reverse);
            }
            return null;
        }

        /** Whether putting {@code keys} in reverse reproduces their iteration order; null when neither way does. */
        private static Boolean insertionOrder(List<String> keys) {
            if (iterationOrder(keys, false).equals(keys)) {
                return Boolean.FALSE;
            }
            if (iterationOrder(keys, true).equals(keys)) {
                return Boolean.TRUE;
            }
            return null;
        }

        private static List<String> iterationOrder(List<String> keys, boolean reverse) {
            CompoundTag probe = new CompoundTag();
            for (int step = 0; step < keys.size(); step++) {
                probe.put(keys.get(reverse ? keys.size() - 1 - step : step), ByteTag.ZERO);
            }
            return new ArrayList<>(probe.keySet());
        }
    }

    private static final class Leaf extends Plan {
        private final Tag tag;
        private final boolean mutable;

        Leaf(Tag tag, boolean mutable) {
            this.tag = tag;
            this.mutable = mutable;
        }

        @Override
        Tag build() {
            return mutable ? tag.copy() : tag;
        }
    }

    private static final class Items extends Plan {
        private final List<Plan> items;

        Items(List<Plan> items) {
            this.items = items;
        }

        @Override
        Tag build() {
            ListTag list = new ListTag();
            for (Plan item : items) {
                list.add(item.build());
            }
            return list;
        }
    }

    private static final class Fields extends Plan {
        private final List<String> keys;
        private final List<Plan> values;
        private final boolean reverse;

        Fields(List<String> keys, List<Plan> values, boolean reverse) {
            this.keys = keys;
            this.values = values;
            this.reverse = reverse;
        }

        @Override
        Tag build() {
            CompoundTag compound = new CompoundTag();
            for (int step = 0; step < keys.size(); step++) {
                int index = reverse ? keys.size() - 1 - step : step;
                compound.put(keys.get(index), values.get(index).build());
            }
            return compound;
        }
    }
}
