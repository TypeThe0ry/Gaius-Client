import java.util.Random;
import org.teavm.classlib.java.util.TAbstractMap;
import org.teavm.classlib.java.util.TIterator;
import org.teavm.classlib.java.util.TLinkedHashMap;
import org.teavm.classlib.java.util.TLinkedHashSet;
import org.teavm.classlib.java.util.TMap;

/**
 * Trace of random TLinkedHashMap/TLinkedHashSet operations: every result, the iteration order
 * after every operation and any exception. classlib-hashmap-order.test.mjs runs it once on TeaVM's
 * original THashMap/THashSet and once on the override; both traces must be identical (the override
 * keeps TeaVM's behaviour for linked maps, including TMap's default compute/merge methods).
 * Prints LINKED_TRACE length=... hash=....
 */
public final class LinkedHashLegacyTrace {
    private static final StringBuilder TRACE = new StringBuilder();
    private static long hash = 1125899906842597L;
    private static long length;

    public static void main(String[] args) {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        Random random = new Random(11);
        for (int t = 0; t < rounds; t++) {
            maps(random, t);
            sets(random, t);
        }
        flush();
        System.out.println("LINKED_TRACE length=" + length + " hash=" + Long.toHexString(hash));
    }

    static void emit(Object value) {
        TRACE.append(value).append('\n');
        if (TRACE.length() > 1 << 16) {
            flush();
        }
    }

    static void flush() {
        for (int i = 0; i < TRACE.length(); i++) {
            hash = 31 * hash + TRACE.charAt(i);
        }
        length += TRACE.length();
        TRACE.setLength(0);
    }

    static Object key(Random random, int universe) {
        int i = random.nextInt(universe);
        return switch (i % 3) {
            case 0 -> i;
            case 1 -> "k" + i;
            default -> i == 5 ? null : (Object) (long) i;
        };
    }

    static String order(TMap<Object, Integer> map) {
        StringBuilder out = new StringBuilder("[");
        for (TIterator<TMap.Entry<Object, Integer>> it = map.entrySet().iterator(); it.hasNext();) {
            TMap.Entry<Object, Integer> e = it.next();
            out.append(e.getKey()).append('=').append(e.getValue()).append(',');
        }
        return out.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    static void maps(Random random, int t) {
        boolean access = random.nextBoolean();
        int limit = random.nextBoolean() ? 1_000_000 : 5 + random.nextInt(40);
        int universe = 5 + random.nextInt(120);
        int capacity = random.nextInt(4) == 0 ? random.nextInt(70) : 16;
        float lf = random.nextBoolean() ? 0.75f : 0.5f + random.nextInt(4);
        TLinkedHashMap<Object, Integer> map = new TLinkedHashMap<>(capacity, lf, access) {
            @Override
            protected boolean removeEldestEntry(TMap.Entry<Object, Integer> eldest) {
                return size() > limit;
            }
        };
        emit("map " + t + " access=" + access + " limit=" + limit + " capacity=" + capacity + " lf=" + lf);
        for (int op = 0; op < 300; op++) {
            Object k = key(random, universe);
            int v = random.nextInt(50);
            int choice = random.nextInt(22);
            Object result;
            try {
                result = switch (choice) {
                    case 0, 1, 2 -> map.put(k, v);
                    case 3 -> map.put(k, null);
                    case 4 -> map.get(k);
                    case 5 -> map.getOrDefault(k, -1);
                    case 6 -> map.remove(k);
                    case 7 -> map.remove(k, v % 5);
                    case 8 -> map.putIfAbsent(k, v);
                    case 9 -> map.replace(k, v);
                    case 10 -> map.replace(k, v % 5, v);
                    case 11 -> map.computeIfAbsent(k, x -> v % 4 == 0 ? null : v);
                    case 12 -> map.computeIfPresent(k, (x, o) -> v % 3 == 0 ? null : v);
                    case 13 -> map.compute(k, (x, o) -> v % 4 == 0 ? null : v);
                    case 14 -> map.merge(k, v, (a, b) -> b % 6 == 0 ? null : a + b);
                    case 15 -> map.containsKey(k) + "/" + map.containsValue(v);
                    case 16 -> {
                        TLinkedHashMap<Object, Integer> source = new TLinkedHashMap<>();
                        for (int i = 0, n = random.nextInt(10); i < n; i++) {
                            source.put(key(random, universe), i);
                        }
                        map.putAll(source);
                        yield "putAll";
                    }
                    case 17 -> {
                        int mod = 2 + random.nextInt(4);
                        int index = 0;
                        for (TIterator<Object> it = map.keySet().iterator(); it.hasNext();) {
                            it.next();
                            if (index++ % mod == 0) {
                                it.remove();
                            }
                        }
                        yield "iteratorRemove";
                    }
                    case 18 -> map.entrySet().remove(new TAbstractMap.SimpleEntry<>(k, (Integer) (v % 5)));
                    case 19 -> map.keySet().remove(k);
                    case 20 -> {
                        if (random.nextInt(5) == 0) {
                            map.clear();
                        }
                        yield map.size() + "/" + map.isEmpty();
                    }
                    default -> order(new TLinkedHashMap<>(map));
                };
            } catch (RuntimeException e) {
                result = e.getClass().getName();
            }
            emit(choice + " " + k + " -> " + result + " " + order(map) + " " + map.size());
        }
    }

    static void sets(Random random, int t) {
        TLinkedHashSet<Object> set = random.nextBoolean() ? new TLinkedHashSet<>() : new TLinkedHashSet<>(random.nextInt(40));
        emit("set " + t);
        for (int op = 0; op < 200; op++) {
            Object k = key(random, 60);
            int choice = random.nextInt(6);
            Object result;
            try {
                result = switch (choice) {
                    case 0, 1, 2 -> set.add(k);
                    case 3 -> set.remove(k);
                    case 4 -> set.contains(k);
                    default -> {
                        TLinkedHashSet<Object> copy = new TLinkedHashSet<>(set);
                        copy.add(k);
                        StringBuilder out = new StringBuilder();
                        for (TIterator<Object> it = copy.iterator(); it.hasNext();) {
                            out.append(it.next()).append(',');
                        }
                        yield out;
                    }
                };
            } catch (RuntimeException e) {
                result = e.getClass().getName();
            }
            StringBuilder out = new StringBuilder();
            for (TIterator<Object> it = set.iterator(); it.hasNext();) {
                out.append(it.next()).append(',');
            }
            emit(choice + " " + k + " -> " + result + " " + out + " " + set.size());
        }
    }
}
