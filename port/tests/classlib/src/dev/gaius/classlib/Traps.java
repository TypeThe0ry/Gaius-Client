package dev.gaius.classlib;

import dev.gaius.classlib.repro.ResTest;
import java.io.IOException;
import java.io.InputStream;

/**
 * Implicit runtime checks of the JVM: null dereference, array bounds, casts, array stores,
 * negative array sizes, integer division and stack depth. On the JVM each case throws a Java
 * exception that the case catches. TeaVM's WebAssembly GC backend without strict mode emits no
 * null, bounds or cast checks: a failing case traps (RuntimeError) and ends the process, which
 * the runner records as {@code trap}; with strict mode it throws the Java exception. Each case is
 * its own section ({@code trap.<name>}) so a trap ends only that case.
 */
final class Traps {
    static final String[] NAMES = {"npe-field", "npe-call", "npe-array-length", "npe-array-load",
        "npe-array-store", "npe-monitor", "npe-throw", "npe-string-length", "npe-unbox", "aioobe-load",
        "aioobe-store", "aioobe-negative", "aioobe-char", "aioobe-long", "cce", "cce-array", "arraystore",
        "negative-array-size", "idiv-zero", "irem-zero", "ldiv-zero", "lrem-zero", "idiv-overflow",
        "irem-overflow", "ldiv-overflow", "lrem-overflow", "stack-overflow", "resource-read-null",
        "npe-caught-as-runtime", "aioobe-in-library", "string-index", "format-missing-argument",
        "floordiv-overflow"};

    static volatile Object sink;
    static int depth;

    static final class Holder {
        int value = 7;
        Object next;
        int[] numbers = new int[3];

        int read() {
            return value;
        }
    }

    private Traps() {
    }

    static Holder holder(boolean present) {
        return present ? new Holder() : null;
    }

    static int index(int value) {
        return Integer.parseInt(Integer.toString(value));
    }

    static Object box(Object value) {
        sink = value;
        return sink;
    }

    static int recurse(int n) {
        depth = n;
        return recurse(n + 1) + 1;
    }

    static void run(String name) {
        boolean absent = name.startsWith("npe-") || name.equals("resource-read-null");
        Holder h = holder(!absent);
        int[] array = new int[4];
        String result;
        try {
            result = switch (name) {
                case "npe-field" -> "value " + h.value;
                case "npe-call" -> "value " + h.read();
                case "npe-array-length" -> "length " + h.numbers.length;
                case "npe-array-load" -> {
                    int[] numbers = absent ? null : array;
                    yield "load " + numbers[0];
                }
                case "npe-array-store" -> {
                    int[] numbers = absent ? null : array;
                    numbers[1] = 5;
                    yield "stored";
                }
                case "npe-monitor" -> {
                    Object lock = absent ? null : new Object();
                    synchronized (lock) {
                        yield "locked";
                    }
                }
                case "npe-throw" -> {
                    RuntimeException e = absent ? null : new RuntimeException();
                    throw e;
                }
                case "npe-string-length" -> {
                    String s = (String) box(absent ? null : "x");
                    yield "length " + s.length();
                }
                case "npe-unbox" -> {
                    Integer boxed = (Integer) box(absent ? null : 1);
                    int value = boxed;
                    yield "unboxed " + value;
                }
                case "aioobe-load" -> "load " + array[index(4)];
                case "aioobe-store" -> {
                    array[index(4)] = 1;
                    yield "stored";
                }
                case "aioobe-negative" -> "load " + array[index(-1)];
                case "aioobe-char" -> {
                    char[] chars = new char[2];
                    yield "load " + (int) chars[index(2)];
                }
                case "aioobe-long" -> {
                    long[] longs = new long[2];
                    longs[index(1 << 20)] = 1;
                    yield "stored";
                }
                case "cce" -> {
                    String s = (String) box(Integer.valueOf(1));
                    yield "cast " + s;
                }
                case "cce-array" -> {
                    String[] strings = (String[]) box(new Object[1]);
                    yield "cast " + strings.length;
                }
                case "arraystore" -> {
                    Object[] objects = (Object[]) box(new String[1]);
                    objects[0] = Integer.valueOf(1);
                    yield "stored " + objects[0];
                }
                case "negative-array-size" -> "length " + new int[index(-1)].length;
                case "idiv-zero" -> "quotient " + (7 / index(0));
                case "irem-zero" -> "remainder " + (7 % index(0));
                case "ldiv-zero" -> "quotient " + (7L / index(0));
                case "lrem-zero" -> "remainder " + (7L % index(0));
                case "idiv-overflow" -> "quotient " + (Integer.MIN_VALUE / index(-1));
                case "irem-overflow" -> "remainder " + (Integer.MIN_VALUE % index(-1));
                case "ldiv-overflow" -> "quotient " + (Long.MIN_VALUE / (long) index(-1));
                case "lrem-overflow" -> "remainder " + (Long.MIN_VALUE % (long) index(-1));
                case "stack-overflow" -> "depth " + recurse(0);
                case "resource-read-null" -> {
                    try (InputStream in = Traps.class.getClassLoader().getResourceAsStream(ResTest.RESOURCE)) {
                        byte[] buffer = absent ? null : new byte[4];
                        yield "read " + in.read(buffer, 0, 1);
                    } catch (IOException e) {
                        yield "throws IOException";
                    }
                }
                case "npe-caught-as-runtime" -> {
                    try {
                        yield "value " + h.value;
                    } catch (RuntimeException e) {
                        yield "caught as RuntimeException: " + e.getClass().getSimpleName();
                    }
                }
                case "aioobe-in-library" -> {
                    // A library method whose own check throws: no trap on any runtime.
                    yield "char " + "abc".charAt(index(5));
                }
                case "string-index" -> "substring " + "abc".substring(index(2), index(1));
                // TeaVM's Formatter reads the argument array without a bounds check (the JVM
                // throws MissingFormatArgumentException).
                case "format-missing-argument" -> String.format("%s %s", "one");
                case "floordiv-overflow" -> "quotient " + Math.floorDiv(Integer.MIN_VALUE, index(-1));
                default -> "unknown case " + name;
            };
        } catch (StackOverflowError e) {
            result = "throws StackOverflowError";
        } catch (Throwable e) {
            result = "throws " + e.getClass().getSimpleName();
        }
        Out.put("result", result);
    }
}
