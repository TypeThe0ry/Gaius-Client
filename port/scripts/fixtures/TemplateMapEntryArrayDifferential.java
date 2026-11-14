import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Differential test of NEtriesMap.toEntryArray (Map.of / Map.ofEntries / Map.copyOf of the TeaVM
 * class library): the class as the build has it before the change (TeaVM's probing body, with the
 * lookup index patch) against the class with TeaVMClasslibPatcher.patchTemplateMapEntryArray
 * (TCollectionsModernSupport.mapEntryArray). Each runs in its own class loader over the TeaVM
 * class library jar plus the compiled Gaius class library overrides.
 *
 *   java TemplateMapEntryArrayDifferential <before class path> <after class path> [<lang json>]
 *
 * Every case builds the same entries in both loaders and compares the outcome: the exception
 * (class and message) or every slot of the data array (key, key class, value, entry class), the
 * entrySet() iteration order, get() of every key and of absent keys, and the trace of every
 * hashCode/equals/getKey/getValue call on instrumented keys and entries. Cases: random sizes 0..400
 * and a few thousand; string keys with forced hash collisions, boxed keys (Integer, Long,
 * Character, Boolean, Short, Byte) over small ranges, a key whose |hash| is negative, instrumented
 * keys and entries, nulls, equal keys, and entries of the class library's own entry classes
 * (ImmutableEntry, TMapEntry, SimpleEntry, SimpleImmutableEntry, THashMap and TLinkedHashMap
 * entries through Map.copyOf); plus Map.copyOf of the vanilla en_us translations if a lang file
 * is given.
 */
public final class TemplateMapEntryArrayDifferential {
    private static final String UTIL = "org.teavm.classlib.java.util.";

    /** One side: a class loader and reflective handles. */
    static final class Side {
        final ClassLoader loader;
        final Class<?> entry;
        final Constructor<?> immutableEntry;
        final Constructor<?> mapEntry;
        final Constructor<?> simpleEntry;
        final Constructor<?> simpleImmutableEntry;
        final Constructor<?> fromArray;
        final Field data;
        final Method getKey;
        final Method getValue;
        final Method copyOf;
        final Class<?> map;
        final Method entrySet;
        final Method iterator;
        final Method hasNext;
        final Method next;
        final Method get;
        final Method put;
        final Constructor<?> hashMap;
        final Constructor<?> linkedHashMap;
        final Method toArray;
        final Method listOf;
        final Method listCopyOf;
        final Constructor<?> arrayList;
        final Method add;
        final Method hashCode;

        Side(String classPath) throws Exception {
            List<URL> urls = new ArrayList<>();
            for (String part : classPath.split(java.io.File.pathSeparator)) {
                urls.add(Path.of(part).toUri().toURL());
            }
            loader = new URLClassLoader(urls.toArray(new URL[0]), null);
            entry = load("TMap$Entry");
            immutableEntry = constructor("TTemplateCollections$ImmutableEntry", Object.class, Object.class);
            mapEntry = constructor("TMapEntry", Object.class, Object.class);
            simpleEntry = constructor("TAbstractMap$SimpleEntry", Object.class, Object.class);
            simpleImmutableEntry = constructor("TAbstractMap$SimpleImmutableEntry", Object.class, Object.class);
            Class<?> entries = Array.newInstance(entry, 0).getClass();
            Class<?> template = load("TTemplateCollections$NEtriesMap");
            fromArray = template.getDeclaredConstructor(entries);
            fromArray.setAccessible(true);
            data = template.getDeclaredField("data");
            data.setAccessible(true);
            getKey = entry.getMethod("getKey");
            getValue = entry.getMethod("getValue");
            map = load("TMap");
            copyOf = map.getMethod("copyOf", map);
            entrySet = map.getMethod("entrySet");
            Class<?> collection = load("TCollection");
            iterator = Class.forName("org.teavm.classlib.java.lang.TIterable", true, loader).getMethod("iterator");
            Class<?> iteratorType = load("TIterator");
            hasNext = iteratorType.getMethod("hasNext");
            next = iteratorType.getMethod("next");
            get = map.getMethod("get", Object.class);
            put = map.getMethod("put", Object.class, Object.class);
            hashMap = load("THashMap").getConstructor();
            linkedHashMap = load("TLinkedHashMap").getConstructor();
            toArray = collection.getMethod("toArray", Object[].class);
            Class<?> list = load("TList");
            listOf = list.getMethod("of", Object[].class);
            listCopyOf = list.getMethod("copyOf", collection);
            arrayList = load("TArrayList").getConstructor();
            add = collection.getMethod("add", Object.class);
            hashCode = Object.class.getMethod("hashCode");
        }

        Class<?> load(String name) throws ClassNotFoundException {
            return Class.forName(UTIL + name, true, loader);
        }

        Constructor<?> constructor(String name, Class<?>... types) throws Exception {
            Constructor<?> result = load(name).getDeclaredConstructor(types);
            result.setAccessible(true);
            return result;
        }
    }

    /** A key that records its hashCode/equals calls. */
    static final class TracedKey {
        final int hash;
        final int identity;
        final List<String> trace;

        TracedKey(int hash, int identity, List<String> trace) {
            this.hash = hash;
            this.identity = identity;
            this.trace = trace;
        }

        @Override
        public int hashCode() {
            trace.add("hash " + identity);
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            boolean result = other instanceof TracedKey key && key.identity == identity;
            trace.add("equals " + identity + " " + (other instanceof TracedKey key ? key.identity : String.valueOf(other))
                    + " " + result);
            return result;
        }

        @Override
        public String toString() {
            return "T" + identity;
        }
    }

    /** Entry kinds. */
    enum Kind { IMMUTABLE, MAP_ENTRY, SIMPLE, SIMPLE_IMMUTABLE, TRACED, MIXED }

    static final class Case {
        final List<Object> keys = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        Kind kind;
        int viaMap; // 0 = entries array, 1 = copyOf(THashMap), 2 = copyOf(TLinkedHashMap)
        long seed;
    }

    private static long cases;
    private static long thrown;
    private static long known;

    public static void main(String[] args) throws Exception {
        Side before = new Side(args[0]);
        Side after = new Side(args[1]);
        Random random = new Random(20261113L);
        for (int round = 0; round < 60_000; round++) {
            Case c = randomCase(random, round);
            compare(before, after, c, "round " + round);
        }
        for (int round = 0; round < 40_000; round++) {
            compareList(before, after, random, round);
        }
        if (args.length > 2) {
            String json = Files.readString(Path.of(args[2]), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
            Case c = new Case();
            while (matcher.find()) {
                c.keys.add(matcher.group(1));
                c.values.add(matcher.group(2));
            }
            c.kind = Kind.IMMUTABLE;
            c.viaMap = 1;
            long[] times = new long[2];
            compare(before, after, c, "lang " + c.keys.size(), times);
            System.out.println("LANG entries=" + c.keys.size() + " beforeMs=" + times[0] / 1_000_000.0
                    + " afterMs=" + times[1] / 1_000_000.0 + " (JVM, first call)");
        }
        System.out.println("TEMPLATE_MAP_ENTRY_ARRAY_DIFFERENTIAL_OK cases=" + cases + " listCases=" + listCases
                + " thrown=" + thrown
                + " casesOfKnownKindsWithoutAnomaly=" + known);
    }

    private static Case randomCase(Random random, int round) {
        Case c = new Case();
        c.seed = random.nextLong();
        int size = round % 997 == 0 ? 2000 + random.nextInt(4000) : random.nextInt(round % 5 == 0 ? 400 : 40);
        int keyStyle = random.nextInt(9);
        c.kind = Kind.values()[random.nextInt(Kind.values().length)];
        // Traced keys only through entry arrays: a hash map orders colliding keys that are not
        // Comparable by identity hash, which differs between the two loaders.
        c.viaMap = c.kind != Kind.TRACED && random.nextInt(4) == 0 ? 1 + random.nextInt(2) : 0;
        boolean anomalies = random.nextInt(6) == 0;
        List<Object> pool = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Object key = switch (keyStyle) {
                case 0 -> "k" + random.nextInt(size * 3 + 1);
                case 1 -> collidingString(random, size);
                case 2 -> random.nextInt(size * 2 + 1) - size;
                case 3 -> (long) random.nextInt(size * 2 + 1) * (random.nextBoolean() ? 1 : 1L << 32);
                case 4 -> (char) ('a' + random.nextInt(Math.max(1, Math.min(size, 60000))));
                case 5 -> (short) random.nextInt(Math.max(1, size));
                case 6 -> random.nextBoolean() ? (Object) (byte) random.nextInt(Math.max(1, Math.min(size, 256)))
                        : (Object) Boolean.valueOf(random.nextBoolean());
                case 7 -> i % 2 == 0 ? (Object) ("s" + random.nextInt(size + 1)) : (Object) random.nextInt(size + 1);
                default -> "t" + i;
            };
            pool.add(key);
        }
        // Distinct keys unless the case asks for duplicates (a copyOf of a map has distinct keys anyway).
        List<Object> distinct = new ArrayList<>(new java.util.LinkedHashSet<>(pool));
        if (anomalies && c.viaMap == 0 && !distinct.isEmpty()) {
            switch (random.nextInt(4)) {
                case 0 -> distinct.add(random.nextInt(distinct.size() + 1), distinct.get(random.nextInt(distinct.size())));
                case 1 -> distinct.add(random.nextInt(distinct.size() + 1), "polygenelubricants");
                case 2 -> distinct.add(random.nextInt(distinct.size() + 1), null);
                default -> distinct.add(random.nextInt(distinct.size() + 1), new Object() {
                    @Override
                    public int hashCode() {
                        return 7;
                    }
                });
            }
        }
        c.keys.addAll(distinct);
        for (int i = 0; i < c.keys.size(); i++) {
            c.values.add(anomalies && c.viaMap == 0 && random.nextInt(50) == 0 ? null : "v" + i);
        }
        return c;
    }

    private static long listCases;

    /** List.of / List.copyOf: hashCode value, the trace of element hashCode calls, and equals. */
    private static void compareList(Side before, Side after, Random random, int round) throws Exception {
        int size = random.nextInt(round % 10 == 0 ? 200 : 12);
        long seed = random.nextLong();
        String expected = listRun(before, size, seed);
        String actual = listRun(after, size, seed);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("list round " + round + " differs:\nbefore " + expected + "\nafter  " + actual);
        }
        listCases++;
    }

    private static String listRun(Side side, int size, long seed) throws Exception {
        Random random = new Random(seed);
        List<String> trace = new ArrayList<>();
        Object[] elements = new Object[size];
        for (int i = 0; i < size; i++) {
            elements[i] = switch (random.nextInt(5)) {
                case 0 -> "e" + random.nextInt(100);
                case 1 -> random.nextInt();
                case 2 -> new TracedKey(random.nextInt(), i, trace);
                case 3 -> random.nextBoolean();
                default -> (double) random.nextInt(1000) / 7;
            };
        }
        Object source = side.arrayList.newInstance();
        for (Object element : elements) {
            side.add.invoke(source, element);
        }
        StringBuilder out = new StringBuilder();
        Object copy = side.listCopyOf.invoke(null, source);
        Object of = side.listOf.invoke(null, (Object) elements);
        for (Object list : new Object[] {copy, of, source}) {
            try {
                out.append(side.hashCode.invoke(list)).append(' ').append(list.getClass().getName()).append(' ');
            } catch (InvocationTargetException e) {
                out.append("THROWS ").append(e.getCause()).append(' ');
            }
            out.append(trace).append('\n');
            trace.clear();
        }
        out.append(copy.equals(of)).append(' ').append(of.equals(copy)).append(' ').append(copy.equals(source))
                .append(' ').append(trace).append('\n');
        return out.toString();
    }

    private static String collidingString(Random random, int size) {
        // "Aa" and "BB" share a hash: concatenations of them collide in groups.
        StringBuilder builder = new StringBuilder();
        int blocks = 1 + random.nextInt(Math.max(1, Math.min(6, 2 + size / 50)));
        for (int i = 0; i < blocks; i++) {
            builder.append(random.nextBoolean() ? "Aa" : "BB");
        }
        return builder.toString();
    }

    private static void compare(Side before, Side after, Case c, String what) throws Exception {
        compare(before, after, c, what, new long[2]);
    }

    private static void compare(Side before, Side after, Case c, String what, long[] times) throws Exception {
        String expected = run(before, c, times, 0);
        String actual = run(after, c, times, 1);
        if (!expected.equals(actual)) {
            int at = 0;
            while (at < Math.min(expected.length(), actual.length()) && expected.charAt(at) == actual.charAt(at)) {
                at++;
            }
            throw new IllegalStateException(what + " (" + c.kind + ", via " + c.viaMap + ", " + c.keys.size()
                    + " keys) differs at " + at + ":\nbefore " + excerpt(expected, at) + "\nafter  " + excerpt(actual, at));
        }
        cases++;
        if (expected.startsWith("THROWS")) {
            thrown++;
        }
        if (c.kind != Kind.TRACED && c.kind != Kind.MIXED && c.keys.size() >= 8 && !expected.startsWith("THROWS")) {
            known++;
        }
    }

    private static String excerpt(String text, int at) {
        return text.substring(Math.max(0, at - 120), Math.min(text.length(), at + 120)).replace('\n', '|');
    }

    private static String run(Side side, Case c, long[] times, int slot) throws Exception {
        List<String> trace = new ArrayList<>();
        Random random = new Random(c.seed);
        List<Object> keys = new ArrayList<>();
        for (int i = 0; i < c.keys.size(); i++) {
            Object key = c.keys.get(i);
            keys.add(c.kind == Kind.TRACED && key != null && random.nextInt(3) != 0
                    ? new TracedKey(key.hashCode(), i, trace) : key);
        }
        StringBuilder out = new StringBuilder();
        Object result;
        long start;
        try {
            if (c.viaMap != 0) {
                Object source = (c.viaMap == 1 ? side.hashMap : side.linkedHashMap).newInstance();
                for (int i = 0; i < keys.size(); i++) {
                    side.put.invoke(source, keys.get(i), c.values.get(i));
                }
                start = System.nanoTime();
                result = side.copyOf.invoke(null, source);
            } else {
                Object entries = Array.newInstance(side.entry, keys.size());
                for (int i = 0; i < keys.size(); i++) {
                    Array.set(entries, i, entry(side, c.kind, random, keys.get(i), c.values.get(i), trace, i));
                }
                start = System.nanoTime();
                result = side.fromArray.newInstance(entries);
            }
            times[slot] += System.nanoTime() - start;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            out.append("THROWS ").append(cause.getClass().getName()).append(' ').append(cause.getMessage()).append('\n');
            out.append("TRACE ").append(trace).append('\n');
            return out.toString();
        }
        out.append("TRACE ").append(trace).append('\n');
        trace.clear();
        Object[] data = (Object[]) side.data.get(result);
        out.append("SLOTS ").append(data.length).append('\n');
        for (Object e : data) {
            Object key = side.getKey.invoke(e);
            out.append(key).append(" (").append(key == null ? "-" : key.getClass().getName()).append(") = ")
                    .append(side.getValue.invoke(e)).append(" [").append(e.getClass().getName()).append("]\n");
        }
        Object iterator = side.iterator.invoke(side.entrySet.invoke(result));
        out.append("ITERATION");
        while ((Boolean) side.hasNext.invoke(iterator)) {
            out.append(' ').append(side.getKey.invoke(side.next.invoke(iterator)));
        }
        out.append('\n');
        for (Object key : keys) {
            out.append(side.get.invoke(result, key)).append(',');
        }
        out.append(side.get.invoke(result, "absent")).append(side.get.invoke(result, -1)).append('\n');
        out.append("TRACE ").append(trace).append('\n');
        return out.toString();
    }

    private static Object entry(Side side, Kind kind, Random random, Object key, Object value, List<String> trace,
            int index) throws Exception {
        Kind chosen = kind == Kind.MIXED ? Kind.values()[random.nextInt(Kind.values().length - 1)] : kind;
        return switch (chosen) {
            case IMMUTABLE -> side.immutableEntry.newInstance(key, value);
            case MAP_ENTRY -> side.mapEntry.newInstance(key, value);
            case SIMPLE -> side.simpleEntry.newInstance(key, value);
            case SIMPLE_IMMUTABLE -> side.simpleImmutableEntry.newInstance(key, value);
            default -> Proxy.newProxyInstance(side.loader, new Class<?>[] {side.entry}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getKey" -> {
                        trace.add("getKey " + index);
                        return key;
                    }
                    case "getValue" -> {
                        trace.add("getValue " + index);
                        return value;
                    }
                    case "hashCode" -> {
                        return index;
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "toString" -> {
                        return "P" + index;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
            });
        };
    }

    /**
     * Writes the two NEtriesMap classes with the real patcher code:
     * {@code <before root>} after patchTemplateCollectionLookups (the class the build has today),
     * {@code <after root>} after patchTemplateMapEntryArray on top of it; a second
     * patchTemplateMapEntryArray must refuse the already replaced body.
     *   java TemplateMapEntryArrayDifferential$Patch <teavm-classlib jar> <before root> <after root>
     */
    public static final class Patch {
        public static void main(String[] args) throws Exception {
            Class<?> patcher = Class.forName("dev.gaius.tools.TeaVMClasslibPatcher");
            Method lookups = patcher.getDeclaredMethod("patchTemplateCollectionLookups", String.class, Path.class);
            Method entryArray = patcher.getDeclaredMethod("patchTemplateMapEntryArray", Path.class);
            Method listHash = patcher.getDeclaredMethod("patchTemplateListHashCode", String.class, Path.class);
            lookups.setAccessible(true);
            entryArray.setAccessible(true);
            listHash.setAccessible(true);
            Path before = Path.of(args[1]);
            Path after = Path.of(args[2]);
            lookups.invoke(null, args[0], before);
            lookups.invoke(null, args[0], after);
            entryArray.invoke(null, after);
            listHash.invoke(null, args[0], after);
            for (Method again : new Method[] {entryArray, listHash}) {
                try {
                    if (again == entryArray) {
                        entryArray.invoke(null, after);
                    } else {
                        listHash.invoke(null, args[0], after);
                    }
                    throw new IllegalStateException(again.getName() + " accepted its own output");
                } catch (InvocationTargetException expected) {
                    if (!(expected.getCause() instanceof IllegalStateException)) {
                        throw expected;
                    }
                }
            }
            System.out.println("TEMPLATE_MAP_PATCH_OK refusesReplacedBody=true");
        }
    }
}
