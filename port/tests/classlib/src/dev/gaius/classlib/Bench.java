package dev.gaius.classlib;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Kernels whose cost depends on the implicit checks (array bounds, null, casts) that TeaVM's
 * strict mode adds to the WebAssembly GC output. Timing lines are informative ({@code #}) and
 * never compared; each kernel also prints a checksum that every runtime must agree on.
 */
final class Bench {
    interface Shape {
        double area();
    }

    record Square(double side) implements Shape {
        @Override
        public double area() {
            return side * side;
        }
    }

    record Circle(double radius) implements Shape {
        @Override
        public double area() {
            return 3.0 * radius * radius;
        }
    }

    static final class Node {
        Node next;
        int value;
    }

    private Bench() {
    }

    static void run() {
        int scale = Integer.parseInt(System.getProperty("classlib.bench.scale", "1"));
        for (int round = 0; round < 3; round++) {
            String tag = round == 0 ? "warmup." : "";
            time(tag + "arrays", () -> arrays(200 * scale));
            time(tag + "objects", () -> objects(400 * scale));
            time(tag + "casts", () -> casts(2_000_000 * scale));
            time(tag + "hashmap", () -> hashmap(200_000 * scale));
            time(tag + "unmodifiableLookup", () -> unmodifiableLookup(2_000, 4_000 * scale));
        }
    }

    interface Kernel {
        long run();
    }

    static void time(String name, Kernel kernel) {
        long start = System.nanoTime();
        long checksum = kernel.run();
        long micros = (System.nanoTime() - start) / 1000;
        if (!name.startsWith("warmup.")) {
            Out.put(name + ".checksum", checksum);
        }
        Out.note("bench " + name + " " + micros + " us");
    }

    /** Bounds-checked loads and stores: a 3x3 box blur over a 256x256 int image. */
    static long arrays(int passes) {
        int size = 256;
        int[] image = new int[size * size];
        int[] out = new int[size * size];
        for (int i = 0; i < image.length; i++) {
            image[i] = (i * 2654435761L) >>> 24 > 0 ? (int) ((i * 2654435761L) >>> 24) : i;
        }
        for (int pass = 0; pass < passes; pass++) {
            for (int y = 1; y < size - 1; y++) {
                for (int x = 1; x < size - 1; x++) {
                    int i = y * size + x;
                    out[i] = (image[i - size - 1] + image[i - size] + image[i - size + 1] + image[i - 1] + image[i]
                            + image[i + 1] + image[i + size - 1] + image[i + size] + image[i + size + 1]) / 9;
                }
            }
            int[] swap = image;
            image = out;
            out = swap;
        }
        long sum = 0;
        for (int v : image) {
            sum += v;
        }
        return sum;
    }

    /** Null-checked field loads: walks of a 10000-node linked list. */
    static long objects(int walks) {
        Node head = null;
        for (int i = 0; i < 10_000; i++) {
            Node node = new Node();
            node.value = i;
            node.next = head;
            head = node;
        }
        long sum = 0;
        for (int walk = 0; walk < walks; walk++) {
            for (Node node = head; node != null; node = node.next) {
                sum += node.value ^ walk;
            }
        }
        return sum;
    }

    /** Checked casts and interface calls. */
    static long casts(int count) {
        Object[] shapes = new Object[64];
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = (i & 1) == 0 ? new Square(i) : new Circle(i);
        }
        double total = 0;
        for (int i = 0; i < count; i++) {
            Object shape = shapes[i & 63];
            if (shape instanceof Square square) {
                total += square.side();
            } else {
                total += ((Shape) shape).area();
            }
        }
        return Double.doubleToLongBits(total);
    }

    static long hashmap(int count) {
        Map<Integer, Integer> map = new HashMap<>();
        long sum = 0;
        for (int i = 0; i < count; i++) {
            map.merge(i & 4095, i, Integer::sum);
        }
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            sum = sum * 31 + entry.getKey() * 7L + entry.getValue();
        }
        return sum;
    }

    /** Model-discovery shaped lookups: string ids in an unmodifiable map, one in three missing. */
    static long unmodifiableLookup(int keys, int lookups) {
        Map<String, Integer> backing = new HashMap<>();
        for (int i = 0; i < keys; i++) {
            backing.put("minecraft:block/model_" + i, i);
        }
        Map<String, Integer> map = Collections.unmodifiableMap(backing);
        String[] probes = new String[lookups];
        for (int i = 0; i < lookups; i++) {
            probes[i] = "minecraft:block/model_" + ((i * 7919) % (keys + keys / 2));
        }
        long sum = 0;
        for (String probe : probes) {
            Integer value = map.get(probe);
            sum = sum * 31 + (value == null ? -1 : value);
        }
        return sum;
    }
}
