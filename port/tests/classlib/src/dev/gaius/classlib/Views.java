package dev.gaius.classlib;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Read-only queries of the Collections.unmodifiable* views (TeaVMClasslibPatcher
 * .patchUnmodifiableViewLookups forwards them to the wrapped collection, as the JDK does):
 * hits, misses, null keys and values, keys whose hash codes collide, live views over a backing
 * collection that changes after wrapping, the mutators that must still throw, and the
 * Collectors.toUnmodifiableMap/Set results that vanilla's model loading reads.
 */
final class Views {
    private Views() {
    }

    /** Equal keys with one hash code, so a hash lookup must still call equals. */
    private record Collide(String name) {
        @Override
        public int hashCode() {
            return 7;
        }
    }

    static void run() {
        Map<Object, Object> backing = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            backing.put("minecraft:block/stone_" + i, i);
        }
        backing.put(null, "nullKey");
        backing.put("nullValue", null);
        backing.put(new Collide("a"), "ca");
        backing.put(new Collide("b"), "cb");
        Map<Object, Object> map = Collections.unmodifiableMap(backing);
        Out.check("map.get.hit", () -> map.get("minecraft:block/stone_17") + " " + map.get("minecraft:block/stone_0"));
        Out.check("map.get.miss", () -> map.get("minecraft:block/stone_40") + " " + map.get(17));
        Out.check("map.get.distinctKey", () -> map.get(new String("minecraft:block/stone_3".toCharArray())));
        Out.check("map.get.null", () -> map.get(null) + " " + map.get("nullValue"));
        Out.check("map.get.collide", () -> map.get(new Collide("a")) + " " + map.get(new Collide("b"))
                + " " + map.get(new Collide("c")));
        Out.check("map.containsKey", () -> map.containsKey("minecraft:block/stone_5") + " " + map.containsKey(null)
                + " " + map.containsKey("nullValue") + " " + map.containsKey("absent") + " "
                + map.containsKey(new Collide("b")) + " " + map.containsKey(new Collide("z")));
        Out.check("map.containsValue", () -> map.containsValue(39) + " " + map.containsValue(null) + " "
                + map.containsValue("cb") + " " + map.containsValue(40));
        Out.check("map.size", () -> map.size() + " " + map.isEmpty() + " "
                + Collections.unmodifiableMap(new HashMap<>()).isEmpty());
        Out.check("map.getOrDefault", () -> map.getOrDefault("nullValue", "d") + " " + map.getOrDefault("absent", "d")
                + " " + map.getOrDefault("minecraft:block/stone_9", "d"));
        Out.check("map.put", () -> map.put("x", 1));
        Out.check("map.remove", () -> map.remove("minecraft:block/stone_1"));
        Out.check("map.clear", () -> {
            map.clear();
            return map.size();
        });
        Out.check("map.keySet.remove", () -> map.keySet().remove("minecraft:block/stone_2"));
        Out.check("map.equals", () -> map.equals(backing) + " " + backing.equals(map) + " "
                + (map.hashCode() == backing.hashCode()));
        Out.check("map.live", () -> {
            backing.put("late", "added");
            backing.remove("minecraft:block/stone_0");
            String text = map.get("late") + " " + map.containsKey("minecraft:block/stone_0") + " " + map.size()
                    + " " + map.get("minecraft:block/stone_0");
            backing.remove("late");
            backing.put("minecraft:block/stone_0", 0);
            return text;
        });
        Map<String, Integer> sorted = Collections.unmodifiableMap(new TreeMap<>(Map.of("b", 2, "a", 1, "c", 3)));
        Out.check("map.tree", () -> sorted.get("a") + " " + sorted.get("d") + " " + sorted.containsKey("c") + " "
                + sorted);
        Map<String, Integer> linked = new LinkedHashMap<>();
        linked.put("z", 26);
        linked.put("a", 1);
        Out.check("map.linkedOrder", () -> Collections.unmodifiableMap(linked).toString() + " "
                + Collections.unmodifiableMap(linked).get("z"));

        Map<String, Integer> collected = IntStream.range(0, 300).boxed()
                .collect(Collectors.toUnmodifiableMap(i -> "minecraft:item/model_" + i, i -> i * 3));
        Out.check("collectors.map", () -> collected.get("minecraft:item/model_299") + " "
                + collected.get("minecraft:item/model_300") + " " + collected.containsKey("minecraft:item/model_0")
                + " " + collected.size());
        Out.check("collectors.map.put", () -> collected.put("x", 1));
        Out.check("collectors.map.duplicate", () -> IntStream.of(1, 2, 1).boxed()
                .collect(Collectors.toUnmodifiableMap(i -> i, i -> i)));

        Set<Object> backingSet = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            backingSet.add("minecraft:stone_" + i);
        }
        backingSet.add(null);
        backingSet.add(new Collide("a"));
        Set<Object> set = Collections.unmodifiableSet(backingSet);
        Out.check("set.contains", () -> set.contains("minecraft:stone_12") + " " + set.contains("minecraft:stone_40")
                + " " + set.contains(null) + " " + set.contains(new Collide("a")) + " "
                + set.contains(new Collide("b")) + " " + set.contains(12));
        Out.check("set.containsAll", () -> set.containsAll(List.of("minecraft:stone_1", "minecraft:stone_2")) + " "
                + set.containsAll(List.of("minecraft:stone_1", "absent")) + " " + set.containsAll(List.of()));
        Out.check("set.size", () -> set.size() + " " + set.isEmpty() + " "
                + Collections.unmodifiableSet(new HashSet<>()).isEmpty());
        Out.check("set.add", () -> set.add("x"));
        Out.check("set.remove", () -> set.remove("minecraft:stone_1"));
        Out.check("set.live", () -> {
            backingSet.add("late");
            String text = set.contains("late") + " " + set.size();
            backingSet.remove("late");
            return text + " " + set.contains("late");
        });
        Set<String> collectedSet = IntStream.range(0, 100).mapToObj(i -> "tag_" + i)
                .collect(Collectors.toUnmodifiableSet());
        Out.check("collectors.set", () -> collectedSet.contains("tag_99") + " " + collectedSet.contains("tag_100")
                + " " + collectedSet.size());

        List<Object> backingList = new ArrayList<>(List.of("a", "b", "c"));
        backingList.add(null);
        Collection<Object> collection = Collections.unmodifiableCollection(backingList);
        Out.check("collection.contains", () -> collection.contains("b") + " " + collection.contains("d") + " "
                + collection.contains(null));
        Out.check("collection.containsAll", () -> collection.containsAll(List.of("a", "c")) + " "
                + collection.containsAll(List.of("a", "z")));
        Out.check("collection.size", () -> collection.size() + " " + collection.isEmpty() + " "
                + Collections.unmodifiableCollection(new ArrayList<>()).isEmpty());
        Out.check("collection.add", () -> collection.add("x"));
        Out.check("collection.live", () -> {
            backingList.add("late");
            String text = collection.contains("late") + " " + collection.size();
            backingList.remove("late");
            return text + " " + Objects.toString(collection);
        });
    }
}
