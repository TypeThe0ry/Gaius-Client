import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.IntFunction;
import org.teavm.classlib.java.util.HashCloneProbe;
import org.teavm.classlib.java.util.TAbstractMap;
import org.teavm.classlib.java.util.THashMap;
import org.teavm.classlib.java.util.THashSet;
import org.teavm.classlib.java.util.TIterator;
import org.teavm.classlib.java.util.TLinkedHashMap;
import org.teavm.classlib.java.util.TMap;

/**
 * JVM differential test of the Gaius THashMap/THashSet override (port/overrides/classlib) against
 * java.util.HashMap/HashSet: random operation sequences on value-hash keys must leave both with the
 * same contents and the same iteration order, including colliding keys that turn bins into trees.
 * TLinkedHashMap (TeaVM's, on top of the override) must still match java.util.LinkedHashMap.
 * Prints HASHMAP_ORDER_OK or the first difference and exits 1.
 */
public final class HashMapOrderDifferential {
    /** Vanilla Vec3i/BlockPos: hash (y + z * 31) * 31 + x, not Comparable to itself. */
    static final class Pos {
        final int x;
        final int y;
        final int z;

        Pos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pos p && p.x == x && p.y == y && p.z == z;
        }

        @Override
        public int hashCode() {
            return (y + z * 31) * 31 + x;
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z;
        }
    }

    /** Few distinct hashes and directly Comparable: tree bins ordered by compareTo. */
    static final class Ranked implements Comparable<Ranked> {
        final int id;

        Ranked(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Ranked r && r.id == id;
        }

        @Override
        public int hashCode() {
            return (id % 3) * 64;
        }

        @Override
        public int compareTo(Ranked o) {
            return Integer.compare(id, o.id);
        }

        @Override
        public String toString() {
            return "R" + id;
        }
    }

    /** Not Comparable, with a chosen hash: keys of one hash are ordered by identity in tree bins. */
    static final class Slot {
        final int id;
        final int hash;

        Slot(int id, int hash) {
            this.id = id;
            this.hash = hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Slot s && s.id == id;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            return "S" + id + "#" + hash;
        }
    }

    private static int checks;

    public static void main(String[] args) {
        long seeds = args.length > 0 ? Long.parseLong(args[0]) : 300;
        List<String> kinds = List.of("int", "string", "collide", "pos", "posdense", "ranked", "mixed");
        for (String kind : kinds) {
            for (long seed = 1; seed <= seeds; seed++) {
                randomOps(kind, seed);
            }
        }
        vegetationSets();
        linked();
        List<String> extendedKinds = List.of("int", "collide", "posdense", "ranked", "mixed", "slots", "slotsint", "slotsfew");
        for (String kind : extendedKinds) {
            for (long seed = 1; seed <= Math.max(1, seeds / 2); seed++) {
                extendedOps(kind, seed);
            }
        }
        bigTrees();
        treeSplits();
        extremeCapacities();
        argumentChecks();
        System.out.println("HASHMAP_ORDER_OK checks=" + checks);
    }

    static IntFunction<Object> keys(String kind, Random random) {
        Object[] pool = new Object[1];
        return switch (kind) {
            case "int" -> i -> i * 37 - 500;
            case "string" -> i -> "minecraft:block/" + i;
            case "collide" -> HashMapOrderDifferential::colliding;
            case "pos" -> i -> new Pos(i % 9 - 4, i / 81, (i / 9) % 9 - 4);
            case "posdense" -> i -> new Pos(i % 62, (i / 62) % 2 - 1, -(i / 124));
            case "ranked" -> Ranked::new;
            default -> i -> switch (i % 4) {
                case 0 -> i;
                case 1 -> "k" + i;
                case 2 -> new Pos(i % 7, 3, i / 7);
                default -> i == 3 ? null : new Ranked(i);
            };
        };
    }

    static String colliding(int i) {
        StringBuilder out = new StringBuilder();
        for (int bit = 9; bit >= 0; bit--) {
            out.append((i >>> bit & 1) == 0 ? "Aa" : "BB");
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    static void randomOps(String kind, long seed) {
        Random random = new Random(seed * 1_000_003L + kind.hashCode());
        IntFunction<Object> keyOf = keys(kind, random);
        int universe = 1 + random.nextInt(random.nextBoolean() ? 40 : 600);
        Object[] keyObjects = new Object[universe];
        for (int i = 0; i < universe; i++) {
            keyObjects[i] = keyOf.apply(i);
        }
        HashMap<Object, Integer> jdk;
        THashMap<Object, Integer> tea;
        switch (random.nextInt(6)) {
            case 0 -> { jdk = new HashMap<>(); tea = new THashMap<>(); }
            case 1 -> { int c = random.nextInt(5); jdk = new HashMap<>(c); tea = new THashMap<>(c); }
            case 2 -> { int c = random.nextInt(300); jdk = new HashMap<>(c); tea = new THashMap<>(c); }
            case 3 -> { jdk = new HashMap<>(8, 0.5f); tea = new THashMap<>(8, 0.5f); }
            case 4 -> { int n = random.nextInt(50); jdk = HashMap.newHashMap(n); tea = THashMap.newHashMap(n); }
            default -> {
                LinkedHashMap<Object, Integer> source = new LinkedHashMap<>();
                TLinkedHashMap<Object, Integer> teaSource = new TLinkedHashMap<>();
                int n = random.nextInt(universe + 1);
                for (int i = 0; i < n; i++) {
                    Object k = keyObjects[random.nextInt(universe)];
                    source.put(k, i);
                    teaSource.put(k, i);
                }
                jdk = new HashMap<>(source);
                tea = new THashMap<>(teaSource);
            }
        }
        String label = kind + "#" + seed;
        compare(label + " init", jdk, tea);
        int ops = 1 + random.nextInt(universe * 3);
        for (int op = 0; op < ops; op++) {
            Object k = keyObjects[random.nextInt(universe)];
            int v = random.nextInt(1000);
            String what;
            switch (random.nextInt(14)) {
                case 0, 1, 2, 3 -> { what = "put"; check(label, what, jdk.put(k, v), tea.put(k, v)); }
                case 4 -> { what = "putIfAbsent"; check(label, what, jdk.putIfAbsent(k, v), tea.putIfAbsent(k, v)); }
                case 5 -> { what = "remove"; check(label, what, jdk.remove(k), tea.remove(k)); }
                case 6 -> { what = "computeIfAbsent"; check(label, what, jdk.computeIfAbsent(k, x -> v % 5 == 0 ? null : v),
                        tea.computeIfAbsent(k, x -> v % 5 == 0 ? null : v)); }
                case 7 -> { what = "compute"; check(label, what, jdk.compute(k, (x, o) -> v % 4 == 0 ? null : v),
                        tea.compute(k, (x, o) -> v % 4 == 0 ? null : v)); }
                case 8 -> { what = "merge"; check(label, what, jdk.merge(k, v, (a, b) -> b % 6 == 0 ? null : a + b),
                        tea.merge(k, v, (a, b) -> b % 6 == 0 ? null : a + b)); }
                case 9 -> { what = "computeIfPresent"; check(label, what, jdk.computeIfPresent(k, (x, o) -> v % 3 == 0 ? null : o + 1),
                        tea.computeIfPresent(k, (x, o) -> v % 3 == 0 ? null : o + 1)); }
                case 10 -> {
                    what = "iteratorRemove";
                    int mod = 2 + random.nextInt(5);
                    Iterator<Object> a = jdk.keySet().iterator();
                    TIterator<Object> b = tea.keySet().iterator();
                    int index = 0;
                    while (a.hasNext()) {
                        if (!b.hasNext()) {
                            fail(label, what + " iterator length");
                        }
                        Object ka = a.next();
                        Object kb = b.next();
                        if (!Objects.equals(ka, kb)) {
                            fail(label, what + " order " + ka + " vs " + kb);
                        }
                        if (index++ % mod == 0) {
                            a.remove();
                            b.remove();
                        }
                    }
                }
                case 11 -> {
                    what = "putAll";
                    LinkedHashMap<Object, Integer> source = new LinkedHashMap<>();
                    TLinkedHashMap<Object, Integer> teaSource = new TLinkedHashMap<>();
                    int n = random.nextInt(universe + 1);
                    for (int i = 0; i < n; i++) {
                        Object key = keyObjects[random.nextInt(universe)];
                        source.put(key, i);
                        teaSource.put(key, i);
                    }
                    jdk.putAll(source);
                    tea.putAll(teaSource);
                }
                case 12 -> {
                    what = random.nextInt(4) == 0 ? "clear" : "keySetRemove";
                    if (what.equals("clear")) {
                        jdk.clear();
                        tea.clear();
                    } else {
                        check(label, what, jdk.keySet().remove(k), tea.keySet().remove(k));
                    }
                }
                default -> {
                    what = "copy";
                    jdk = new HashMap<>(jdk);
                    tea = new THashMap<>(tea);
                }
            }
            compare(label + " op" + op + " " + what, jdk, tea);
        }
        // HashSet copy constructor and add order
        HashSet<Object> jdkSet = new HashSet<>(jdk.keySet());
        THashSet<Object> teaSet = new THashSet<>(tea.keySet());
        for (int i = 0; i < universe / 2; i++) {
            Object k = keyObjects[random.nextInt(universe)];
            jdkSet.add(k);
            teaSet.add(k);
        }
        compareSet(label + " set", jdkSet, teaSet);
    }

    static void vegetationSets() {
        // The shape VegetationPatchFeature.placeGroundPatch builds: a disc of floor positions at mixed heights.
        Random random = new Random(42);
        for (int t = 0; t < 2000; t++) {
            HashSet<Object> jdk = new HashSet<>();
            THashSet<Object> tea = new THashSet<>();
            int cx = random.nextInt(60000) - 30000;
            int cz = random.nextInt(60000) - 30000;
            int cy = random.nextInt(120) - 60;
            int rx = 1 + random.nextInt(7);
            int rz = 1 + random.nextInt(7);
            for (int dx = -rx; dx <= rx; dx++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    if (random.nextInt(10) == 0) {
                        continue;
                    }
                    Pos p = new Pos(cx + dx, cy + random.nextInt(3) - 1, cz + dz);
                    jdk.add(p);
                    tea.add(p);
                }
            }
            compareSet("vegetation#" + t, jdk, tea);
        }
    }

    static void linked() {
        Random random = new Random(7);
        for (int t = 0; t < 300; t++) {
            boolean access = random.nextBoolean();
            int limit = 5 + random.nextInt(40);
            LinkedHashMap<Object, Integer> jdk = new LinkedHashMap<>(16, 0.75f, access) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, Integer> eldest) {
                    return size() > limit;
                }
            };
            TLinkedHashMap<Object, Integer> tea = new TLinkedHashMap<>(16, 0.75f, access) {
                @Override
                protected boolean removeEldestEntry(TMap.Entry<Object, Integer> eldest) {
                    return size() > limit;
                }
            };
            for (int op = 0; op < 400; op++) {
                Object k = random.nextInt(3) == 0 ? (Object) ("s" + random.nextInt(80)) : (Object) random.nextInt(80);
                switch (random.nextInt(4)) {
                    case 0, 1 -> check("linked#" + t, "put", jdk.put(k, op), tea.put(k, op));
                    case 2 -> check("linked#" + t, "get", jdk.get(k), tea.get(k));
                    default -> check("linked#" + t, "remove", jdk.remove(k), tea.remove(k));
                }
                List<Object> a = new ArrayList<>(jdk.keySet());
                List<Object> b = new ArrayList<>();
                for (TIterator<Object> it = tea.keySet().iterator(); it.hasNext();) {
                    b.add(it.next());
                }
                if (!a.equals(b)) {
                    fail("linked#" + t, "order " + a + " vs " + b);
                }
                checks++;
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Cases added on top of the first differential run: clone, entrySet().remove, remove(k, v),
    // replace, putIfAbsent over a stored null, other load factors, capacities near 2^30, large
    // tree bins with many deletions (map and iterator removal), and tree bins split by growth.

    static IntFunction<Object> extendedKeys(String kind) {
        return switch (kind) {
            // All in bin 5 while the table has at most 64 buckets, 40 distinct hashes.
            case "slots" -> i -> new Slot(i, 5 + 64 * (i % 40));
            // Same bin pattern with distinct, comparable keys.
            case "slotsint" -> i -> i * 64 + 5;
            // Three hashes only: long identity-ordered tree bins.
            case "slotsfew" -> i -> new Slot(i, 5 + 64 * (i % 3));
            default -> keys(kind, null);
        };
    }

    static final float[] LOAD_FACTORS = {0.25f, 0.5f, 0.6f, 0.75f, 1f, 1.5f, 3f};

    @SuppressWarnings("unchecked")
    static void extendedOps(String kind, long seed) {
        Random random = new Random(seed * 7_919L + kind.hashCode() * 31L + 17);
        IntFunction<Object> keyOf = extendedKeys(kind);
        int universe = 1 + random.nextInt(random.nextBoolean() ? 60 : 700);
        Object[] keyObjects = new Object[universe];
        for (int i = 0; i < universe; i++) {
            keyObjects[i] = keyOf.apply(i);
        }
        float lf = LOAD_FACTORS[random.nextInt(LOAD_FACTORS.length)];
        HashMap<Object, Integer> jdk;
        THashMap<Object, Integer> tea;
        switch (random.nextInt(4)) {
            case 0 -> { int c = random.nextInt(300); jdk = new HashMap<>(c, lf); tea = new THashMap<>(c, lf); }
            case 1 -> { jdk = new HashMap<>(0, lf); tea = new THashMap<>(0, lf); }
            case 2 -> { jdk = new HashMap<>(); tea = new THashMap<>(); }
            default -> { int c = 1 << random.nextInt(9); jdk = new HashMap<>(c, lf); tea = new THashMap<>(c, lf); }
        }
        String label = "ext-" + kind + "#" + seed + " lf=" + lf;
        compare(label + " init", jdk, tea);
        int ops = 1 + random.nextInt(universe * 4);
        for (int op = 0; op < ops; op++) {
            Object k = keyObjects[random.nextInt(universe)];
            int v = random.nextInt(1000);
            String what;
            switch (random.nextInt(23)) {
                case 0, 1, 2 -> { what = "put"; check(label, what, jdk.put(k, v), tea.put(k, v)); }
                case 3 -> { what = "putNull"; check(label, what, jdk.put(k, null), tea.put(k, null)); }
                case 4 -> { what = "putIfAbsent"; check(label, what, jdk.putIfAbsent(k, v), tea.putIfAbsent(k, v)); }
                case 5 -> { what = "remove"; check(label, what, jdk.remove(k), tea.remove(k)); }
                case 6 -> {
                    what = "removeKeyValue";
                    Integer value = random.nextBoolean() ? jdk.get(k) : (Integer) v;
                    check(label, what, jdk.remove(k, value), tea.remove(k, value));
                }
                case 7 -> { what = "replace"; check(label, what, jdk.replace(k, v), tea.replace(k, v)); }
                case 8 -> {
                    what = "replaceIfEqual";
                    Integer old = random.nextBoolean() ? jdk.get(k) : (Integer) (v % 7);
                    check(label, what, jdk.replace(k, old, v), tea.replace(k, old, v));
                }
                case 9 -> {
                    what = "entrySetRemove";
                    Integer value = random.nextBoolean() ? jdk.get(k) : (Integer) v;
                    check(label, what, jdk.entrySet().remove(new AbstractMap.SimpleEntry<>(k, value)),
                            tea.entrySet().remove(new TAbstractMap.SimpleEntry<>(k, value)));
                }
                case 10 -> { what = "computeIfAbsent"; check(label, what, jdk.computeIfAbsent(k, x -> v % 5 == 0 ? null : v),
                        tea.computeIfAbsent(k, x -> v % 5 == 0 ? null : v)); }
                case 11 -> { what = "compute"; check(label, what, jdk.compute(k, (x, o) -> v % 4 == 0 ? null : v),
                        tea.compute(k, (x, o) -> v % 4 == 0 ? null : v)); }
                case 12 -> { what = "merge"; check(label, what, jdk.merge(k, v, (a, b) -> b % 6 == 0 ? null : a + b),
                        tea.merge(k, v, (a, b) -> b % 6 == 0 ? null : a + b)); }
                case 13 -> { what = "computeIfPresent"; check(label, what,
                        jdk.computeIfPresent(k, (x, o) -> v % 3 == 0 ? null : o + 1),
                        tea.computeIfPresent(k, (x, o) -> v % 3 == 0 ? null : o + 1)); }
                case 14 -> {
                    what = "entryIteratorRemove";
                    int mod = 1 + random.nextInt(4);
                    Iterator<Map.Entry<Object, Integer>> a = jdk.entrySet().iterator();
                    TIterator<TMap.Entry<Object, Integer>> b = tea.entrySet().iterator();
                    int index = 0;
                    while (a.hasNext()) {
                        if (!b.hasNext()) {
                            fail(label, what + " iterator length");
                        }
                        Map.Entry<Object, Integer> ea = a.next();
                        TMap.Entry<Object, Integer> eb = b.next();
                        if (!Objects.equals(ea.getKey(), eb.getKey()) || !Objects.equals(ea.getValue(), eb.getValue())) {
                            fail(label, what + " order " + ea + " vs " + eb.getKey() + "=" + eb.getValue());
                        }
                        if (index++ % mod == 0) {
                            a.remove();
                            b.remove();
                        }
                    }
                    if (b.hasNext()) {
                        fail(label, what + " iterator length");
                    }
                }
                case 15 -> {
                    what = "valueIteratorRemove";
                    int mod = 2 + random.nextInt(3);
                    Iterator<Integer> a = jdk.values().iterator();
                    TIterator<Integer> b = tea.values().iterator();
                    int index = 0;
                    while (a.hasNext()) {
                        check(label, what, a.next(), b.next());
                        if (index++ % mod == 1) {
                            a.remove();
                            b.remove();
                        }
                    }
                }
                case 16 -> {
                    what = "clone";
                    jdk = (HashMap<Object, Integer>) jdk.clone();
                    tea = HashCloneProbe.cloneMap(tea);
                }
                case 17 -> {
                    what = "putAllPlain";
                    HashMap<Object, Integer> source = new HashMap<>();
                    THashMap<Object, Integer> teaSource = new THashMap<>();
                    int n = random.nextInt(universe + 1);
                    for (int i = 0; i < n; i++) {
                        Object key = keyObjects[random.nextInt(universe)];
                        source.put(key, i);
                        teaSource.put(key, i);
                    }
                    jdk.putAll(source);
                    tea.putAll(teaSource);
                }
                case 18 -> {
                    what = "drain";
                    List<Object> present = new ArrayList<>(jdk.keySet());
                    Collections.shuffle(present, random);
                    int keep = random.nextInt(present.size() + 1);
                    for (int i = keep; i < present.size(); i++) {
                        check(label, what, jdk.remove(present.get(i)), tea.remove(present.get(i)));
                    }
                }
                case 19 -> {
                    what = "query";
                    check(label, what, jdk.getOrDefault(k, -1), tea.getOrDefault(k, -1));
                    check(label, what, jdk.containsValue(v), tea.containsValue(v));
                    check(label, what, jdk.containsValue(null), tea.containsValue(null));
                }
                case 20 -> {
                    what = random.nextInt(3) == 0 ? "clear" : "keySetRemove";
                    if (what.equals("clear")) {
                        jdk.clear();
                        tea.clear();
                    } else {
                        check(label, what, jdk.keySet().remove(k), tea.keySet().remove(k));
                    }
                }
                default -> {
                    what = "copy";
                    jdk = new HashMap<>(jdk);
                    tea = new THashMap<>(tea);
                }
            }
            compare(label + " op" + op + " " + what, jdk, tea);
        }
        extendedSets(label, random, keyObjects, jdk, tea);
    }

    @SuppressWarnings("unchecked")
    static void extendedSets(String label, Random random, Object[] keyObjects, HashMap<Object, Integer> jdkMap,
            THashMap<Object, Integer> teaMap) {
        int universe = keyObjects.length;
        HashSet<Object> jdk;
        THashSet<Object> tea;
        switch (random.nextInt(4)) {
            case 0 -> {
                int c = random.nextInt(200);
                float lf = LOAD_FACTORS[random.nextInt(LOAD_FACTORS.length)];
                jdk = new HashSet<>(c, lf);
                tea = new THashSet<>(c, lf);
            }
            case 1 -> { int n = random.nextInt(100); jdk = HashSet.newHashSet(n); tea = THashSet.newHashSet(n); }
            case 2 -> { int c = random.nextInt(100); jdk = new HashSet<>(c); tea = new THashSet<>(c); }
            default -> { jdk = new HashSet<>(jdkMap.keySet()); tea = new THashSet<>(teaMap.keySet()); }
        }
        for (int round = 0; round < 4; round++) {
            for (int i = 0, n = random.nextInt(universe + 1); i < n; i++) {
                Object k = keyObjects[random.nextInt(universe)];
                if (random.nextInt(4) == 0) {
                    check(label, "set remove", jdk.remove(k), tea.remove(k));
                } else {
                    check(label, "set add", jdk.add(k), tea.add(k));
                }
            }
            compareSet(label + " set round" + round, jdk, tea);
            if (random.nextBoolean()) {
                jdk = (HashSet<Object>) jdk.clone();
                tea = HashCloneProbe.cloneSet(tea);
                compareSet(label + " set clone" + round, jdk, tea);
            }
        }
    }

    /** Tree bins of up to 1024 keys, emptied by map removal and by iterator removal. */
    static void bigTrees() {
        Random random = new Random(99);
        for (int t = 0; t < 24; t++) {
            int n = 100 + random.nextInt(925);
            Object[] keys = new Object[n];
            for (int i = 0; i < n; i++) {
                keys[i] = switch (t % 3) {
                    case 0 -> colliding(i);
                    case 1 -> new Slot(i, 77);
                    default -> new Slot(i, 77 + 64 * (i % 2));
                };
            }
            HashMap<Object, Integer> jdk = t % 4 == 0 ? new HashMap<>(64) : new HashMap<>();
            THashMap<Object, Integer> tea = t % 4 == 0 ? new THashMap<>(64) : new THashMap<>();
            String label = "bigtree#" + t;
            List<Object> order = new ArrayList<>(List.of(keys));
            Collections.shuffle(order, random);
            for (Object k : order) {
                check(label, "put", jdk.put(k, 1), tea.put(k, 1));
                if (random.nextInt(20) == 0) {
                    compare(label + " fill", jdk, tea);
                }
            }
            compare(label + " filled", jdk, tea);
            while (!jdk.isEmpty()) {
                switch (random.nextInt(5)) {
                    case 0, 1 -> {
                        List<Object> present = new ArrayList<>(jdk.keySet());
                        for (int i = 0, m = 1 + present.size() / 8; i < m; i++) {
                            Object k = present.get(random.nextInt(present.size()));
                            check(label, "remove", jdk.remove(k), tea.remove(k));
                            compare(label + " remove", jdk, tea);
                        }
                    }
                    case 2 -> {
                        int mod = 2 + random.nextInt(6);
                        Iterator<Object> a = jdk.keySet().iterator();
                        TIterator<Object> b = tea.keySet().iterator();
                        int index = 0;
                        while (a.hasNext()) {
                            check(label, "iterator", a.next(), b.next());
                            if (index++ % mod == 0) {
                                a.remove();
                                b.remove();
                            }
                        }
                        compare(label + " iteratorRemove", jdk, tea);
                    }
                    case 3 -> {
                        for (int i = 0, m = random.nextInt(30); i < m; i++) {
                            Object k = keys[random.nextInt(n)];
                            int v = random.nextInt(9);
                            switch (random.nextInt(4)) {
                                case 0 -> check(label, "put", jdk.put(k, v), tea.put(k, v));
                                case 1 -> check(label, "computeIfAbsent", jdk.computeIfAbsent(k, x -> v),
                                        tea.computeIfAbsent(k, x -> v));
                                case 2 -> check(label, "merge", jdk.merge(k, v, (a, b) -> a + b == 4 ? null : a + b),
                                        tea.merge(k, v, (a, b) -> a + b == 4 ? null : a + b));
                                default -> check(label, "removeKeyValue", jdk.remove(k, v), tea.remove(k, v));
                            }
                            compare(label + " mixed", jdk, tea);
                        }
                    }
                    default -> {
                        Object k = keys[random.nextInt(n)];
                        Integer value = jdk.get(k);
                        check(label, "entrySetRemove", jdk.entrySet().remove(new AbstractMap.SimpleEntry<>(k, value)),
                                tea.entrySet().remove(new TAbstractMap.SimpleEntry<>(k, value)));
                        compare(label + " entrySetRemove", jdk, tea);
                    }
                }
            }
        }
    }

    /** Tree bins in a 64-bucket table that growth splits (both halves, one half, short halves). */
    static void treeSplits() {
        Random random = new Random(1234);
        for (int t = 0; t < 300; t++) {
            float lf = random.nextBoolean() ? 0.75f : 4f;
            HashMap<Object, Integer> jdk = new HashMap<>(64, lf);
            THashMap<Object, Integer> tea = new THashMap<>(64, lf);
            String label = "treesplit#" + t;
            int highBits = 1 + random.nextInt(8);
            boolean comparable = random.nextBoolean();
            int binKeys = 9 + random.nextInt(40);
            List<Object> colliders = new ArrayList<>();
            for (int j = 0; j < binKeys; j++) {
                int hash = 5 + 64 * random.nextInt(highBits);
                colliders.add(comparable ? (Object) (5 + 64 * j * (highBits == 1 ? 2 : 1)) : new Slot(j, hash));
            }
            int nextFiller = 0;
            int steps = 400 + random.nextInt(800);
            for (int s = 0; s < steps; s++) {
                int choice = random.nextInt(10);
                if (choice < 3) {
                    Object k = colliders.get(random.nextInt(binKeys));
                    check(label, "put", jdk.put(k, s), tea.put(k, s));
                } else if (choice < 4) {
                    Object k = colliders.get(random.nextInt(binKeys));
                    check(label, "remove", jdk.remove(k), tea.remove(k));
                } else {
                    Slot filler = new Slot(100_000 + nextFiller++, random.nextInt());
                    check(label, "filler", jdk.put(filler, s), tea.put(filler, s));
                }
                compare(label + " step" + s, jdk, tea);
            }
        }
    }

    /** Capacities near 2^30 (construction only: a table that large does not fit) and large tables. */
    @SuppressWarnings("unchecked")
    static void extremeCapacities() {
        int[] capacities = {(1 << 29) + 1, (1 << 30) - 1, 1 << 30, (1 << 30) + 1, Integer.MAX_VALUE};
        for (int capacity : capacities) {
            for (float lf : new float[] {0.5f, 0.75f, 2f}) {
                String label = "huge " + capacity + " lf=" + lf;
                HashMap<Object, Integer> jdk = new HashMap<>(capacity, lf);
                THashMap<Object, Integer> tea = new THashMap<>(capacity, lf);
                compare(label, jdk, tea);
                check(label, "get", jdk.get(1), tea.get(1));
                check(label, "remove", jdk.remove(1), tea.remove(1));
                check(label, "computeIfPresent", jdk.computeIfPresent(1, (k, v) -> v), tea.computeIfPresent(1, (k, v) -> v));
                jdk.putAll(new HashMap<>());
                tea.putAll(new THashMap<>());
                jdk.clear();
                tea.clear();
                compare(label + " putAll/clear", jdk, tea);
                compare(label + " clone", (HashMap<Object, Integer>) jdk.clone(), HashCloneProbe.cloneMap(tea));
                compareSet(label + " set", new HashSet<>(capacity, lf), new THashSet<>(capacity, lf));
            }
        }
        compare("newHashMap max", HashMap.newHashMap(Integer.MAX_VALUE), THashMap.newHashMap(Integer.MAX_VALUE));
        compareSet("newHashSet max", HashSet.newHashSet(Integer.MAX_VALUE), THashSet.newHashSet(Integer.MAX_VALUE));
        Random random = new Random(5);
        for (int capacity : new int[] {1 << 20, (1 << 21) + 1, 1 << 22}) {
            for (float lf : new float[] {0.75f, 8f}) {
                HashMap<Object, Integer> jdk = new HashMap<>(capacity, lf);
                THashMap<Object, Integer> tea = new THashMap<>(capacity, lf);
                for (int i = 0; i < 3000; i++) {
                    Pos p = new Pos(random.nextInt(2000) - 1000, random.nextInt(300), random.nextInt(2000) - 1000);
                    check("large", "put", jdk.put(p, i), tea.put(p, i));
                }
                compare("large " + capacity + " lf=" + lf, jdk, tea);
                compare("large clone", (HashMap<Object, Integer>) jdk.clone(), HashCloneProbe.cloneMap(tea));
            }
        }
    }

    static void argumentChecks() {
        sameThrow("capacity -1", () -> new HashMap<>(-1), () -> new THashMap<>(-1));
        sameThrow("capacity -1, 0.75", () -> new HashMap<>(-1, 0.75f), () -> new THashMap<>(-1, 0.75f));
        sameThrow("load factor 0", () -> new HashMap<>(16, 0f), () -> new THashMap<>(16, 0f));
        sameThrow("load factor -1", () -> new HashMap<>(16, -1f), () -> new THashMap<>(16, -1f));
        sameThrow("load factor NaN", () -> new HashMap<>(16, Float.NaN), () -> new THashMap<>(16, Float.NaN));
        sameThrow("newHashMap -1", () -> HashMap.newHashMap(-1), () -> THashMap.newHashMap(-1));
        sameThrow("set capacity -1", () -> new HashSet<>(-1), () -> new THashSet<>(-1));
        sameThrow("set load factor NaN", () -> new HashSet<>(4, Float.NaN), () -> new THashSet<>(4, Float.NaN));
        sameThrow("newHashSet -1", () -> HashSet.newHashSet(-1), () -> THashSet.newHashSet(-1));
        sameThrow("merge null value", () -> new HashMap<Object, Integer>().merge(1, null, (a, b) -> a),
                () -> new THashMap<Object, Integer>().merge(1, null, (a, b) -> a));
    }

    static void sameThrow(String what, Runnable jdk, Runnable tea) {
        String a = thrown(jdk);
        String b = thrown(tea);
        if (!a.equals(b)) {
            fail("arguments", what + ": " + a + " vs " + b);
        }
        checks++;
    }

    static String thrown(Runnable action) {
        try {
            action.run();
            return "none";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    static void check(String label, String what, Object a, Object b) {
        if (!Objects.equals(a, b)) {
            fail(label, what + " returned " + a + " vs " + b);
        }
    }

    static void compare(String label, HashMap<Object, Integer> jdk, THashMap<Object, Integer> tea) {
        if (jdk.size() != tea.size()) {
            fail(label, "size " + jdk.size() + " vs " + tea.size());
        }
        List<Object> a = new ArrayList<>();
        List<Object> av = new ArrayList<>();
        jdk.forEach((k, v) -> {
            a.add(k);
            av.add(v);
        });
        List<Object> b = new ArrayList<>();
        List<Object> bv = new ArrayList<>();
        for (TIterator<? extends TMap.Entry<Object, Integer>> it = tea.entrySet().iterator(); it.hasNext();) {
            TMap.Entry<Object, Integer> e = it.next();
            b.add(e.getKey());
            bv.add(e.getValue());
        }
        if (!a.equals(b)) {
            fail(label, "order\n  jdk " + a + "\n  tea " + b);
        }
        if (!av.equals(bv)) {
            fail(label, "values " + av + " vs " + bv);
        }
        for (Object k : a) {
            if (!Objects.equals(jdk.get(k), tea.get(k)) || !tea.containsKey(k)) {
                fail(label, "lookup " + k);
            }
        }
        checks++;
    }

    static void compareSet(String label, HashSet<Object> jdk, THashSet<Object> tea) {
        List<Object> a = new ArrayList<>(jdk);
        List<Object> b = new ArrayList<>();
        for (TIterator<Object> it = tea.iterator(); it.hasNext();) {
            b.add(it.next());
        }
        if (!a.equals(b)) {
            fail(label, "set order\n  jdk " + a + "\n  tea " + b);
        }
        checks++;
    }

    static void fail(String label, String message) {
        System.out.println("HASHMAP_ORDER_FAIL " + label + ": " + message);
        System.exit(1);
    }
}
