package dev.gaius.classlib;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Iteration order of hash collections whose keys have value-based hash codes. Vanilla iterates
 * such maps and sets (registries, tag maps, block-state tables, recipe and advancement maps,
 * chunk tickets), so the order is observable game behaviour: the JDK's HashMap (power-of-two
 * table, hash spreading, tail insertion, order-preserving resize, tree bins) defines it.
 * Identity-hash keys (enums, plain objects) and Map.of/Set.of (salted per JVM run) have no
 * stable order even on the JVM and are left out.
 */
final class HashOrder {
    private static final String[] NAMES = {"stone", "granite", "polished_granite", "diorite", "andesite",
        "grass_block", "dirt", "coarse_dirt", "podzol", "cobblestone", "oak_planks", "spruce_planks",
        "bedrock", "water", "lava", "sand", "red_sand", "gravel", "gold_ore", "iron_ore", "coal_ore",
        "oak_log", "oak_leaves", "sponge", "glass", "lapis_ore", "dispenser", "sandstone", "note_block"};
    private static final int[] SIZES = {3, 12, 13, 40, 97, 400, 3000};

    record Pos(int x, int y, int z) {
    }

    /** Vanilla Vec3i/BlockPos hash: (y + z * 31) * 31 + x. */
    static final class BlockPosLike {
        final int x;
        final int y;
        final int z;

        BlockPosLike(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BlockPosLike p && p.x == x && p.y == y && p.z == z;
        }

        @Override
        public int hashCode() {
            return (y + z * 31) * 31 + x;
        }

        @Override
        public String toString() {
            return "BP{" + x + "," + y + "," + z + "}";
        }
    }

    private HashOrder() {
    }

    static void run() {
        int[] randomInts = new int[8000];
        Rand random = new Rand(1);
        for (int i = 0; i < randomInts.length; i++) {
            randomInts[i] = random.nextInt();
        }
        Map<String, IntFunction<Object>> kinds = new LinkedHashMap<>();
        kinds.put("int.seq", i -> i);
        kinds.put("int.stride37", i -> i * 37);
        kinds.put("int.high", i -> i << 16);
        kinds.put("int.neg", i -> -i * 7919);
        kinds.put("int.random", i -> randomInts[i]);
        kinds.put("long.mix", i -> (long) i * 0x100000001L + (i ^ 5));
        kinds.put("string.k", i -> "k" + i);
        kinds.put("string.path", i -> "minecraft:block/" + NAMES[i % NAMES.length] + (i / NAMES.length));
        kinds.put("string.collide", HashOrder::colliding);
        kinds.put("double", i -> i * 0.5 - 7);
        kinds.put("float", i -> i * 0.25f);
        kinds.put("char", i -> (char) ('!' + i));
        kinds.put("list", i -> List.of(i % 7, i / 7));
        kinds.put("record", i -> new Pos(i % 5, i / 25, (i / 5) % 5));
        kinds.put("blockpos", i -> new BlockPosLike(i % 16 - 8, i / 256, (i / 16) % 16 - 8));
        kinds.put("uuid", i -> new UUID(i * 0x9E3779B97F4A7C15L, ~(long) i));
        kinds.put("entry", i -> new AbstractMap.SimpleImmutableEntry<>("e" + (i % 50), i / 50));
        for (Map.Entry<String, IntFunction<Object>> kind : kinds.entrySet()) {
            IntFunction<Object> k = kind.getValue();
            for (int n : SIZES) {
                if (n > 0xFFFF && kind.getKey().equals("char")) {
                    continue;
                }
                String base = kind.getKey() + ".n" + n + ".";
                List<Object> keys = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    keys.add(k.apply(i));
                }
                Out.check(base + "put", () -> {
                    Map<Object, Integer> m = new HashMap<>();
                    for (int i = 0; i < n; i++) {
                        m.put(keys.get(i), i);
                    }
                    return order(m.keySet()) + (n <= 12 ? " " + m : "");
                });
                Out.check(base + "set", () -> {
                    HashSet<Object> s = new HashSet<>();
                    for (Object key : keys) {
                        s.add(key);
                    }
                    return order(s);
                });
                Out.check(base + "removeReinsert", () -> {
                    Map<Object, Integer> m = new HashMap<>();
                    for (int i = 0; i < n; i++) {
                        m.put(keys.get(i), i);
                    }
                    for (int i = 0; i < n; i += 3) {
                        m.remove(keys.get(i));
                    }
                    for (int i = n; i < n + n / 2; i++) {
                        m.put(k.apply(i), i);
                    }
                    for (Iterator<Object> it = m.keySet().iterator(); it.hasNext();) {
                        Object key = it.next();
                        if (m.get(key) % 5 == 1) {
                            it.remove();
                        }
                    }
                    return order(m.keySet());
                });
                Out.check(base + "copy", () -> {
                    Map<Object, Integer> linked = new LinkedHashMap<>();
                    for (int i = 0; i < n; i++) {
                        linked.put(keys.get(i), i);
                    }
                    return order(new HashMap<>(linked).keySet()) + " | " + order(new HashSet<>(keys));
                });
                Out.check(base + "capacity4", () -> {
                    Map<Object, Integer> m = new HashMap<>(4);
                    for (int i = 0; i < n; i++) {
                        m.put(keys.get(i), i);
                    }
                    return order(m.keySet());
                });
                Out.check(base + "putAll", () -> {
                    Map<Object, Integer> source = new LinkedHashMap<>();
                    for (int i = n - 1; i >= 0; i--) {
                        source.put(keys.get(i), i);
                    }
                    Map<Object, Integer> m = new HashMap<>();
                    m.put(keys.get(0), -1);
                    m.putAll(source);
                    return order(m.keySet());
                });
                Out.check(base + "collectors", () -> order(keys.stream().collect(Collectors.toSet())) + " | "
                        + order(IntStream.range(0, n).boxed()
                                .collect(Collectors.toMap(keys::get, i -> i, (a, b) -> a)).keySet()));
                Out.check(base + "concurrent", () -> {
                    Map<Object, Integer> m = new ConcurrentHashMap<>();
                    for (int i = 0; i < n; i++) {
                        m.put(keys.get(i), i);
                    }
                    return order(m.keySet());
                });
            }
        }
    }

    /** Strings over {"Aa", "BB"} of one length all share a hash code (a single bucket). */
    static String colliding(int i) {
        StringBuilder out = new StringBuilder(24);
        for (int bit = 11; bit >= 0; bit--) {
            out.append((i >>> bit & 1) == 0 ? "Aa" : "BB");
        }
        return out.toString();
    }

    static String order(Collection<?> keys) {
        if (keys.size() <= 40) {
            StringBuilder out = new StringBuilder();
            for (Object key : keys) {
                out.append(out.length() == 0 ? "" : ",").append(key);
            }
            return out.toString();
        }
        Out.Digest digest = new Out.Digest();
        StringBuilder head = new StringBuilder();
        int index = 0;
        for (Object key : keys) {
            digest.add(String.valueOf(key));
            if (index++ < 12) {
                head.append(head.length() == 0 ? "" : ",").append(key);
            }
        }
        return digest + " " + head + ",...";
    }
}
