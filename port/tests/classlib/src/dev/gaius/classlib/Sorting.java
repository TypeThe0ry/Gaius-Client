package dev.gaius.classlib;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

/**
 * Object sorts are stable by specification (Arrays.sort(Object[]), Collections.sort, List.sort,
 * Stream.sorted), so equal elements keep their order on every runtime and the result is unique.
 * Primitive sorts order -0.0 before 0.0 and NaN last. PriorityQueue order among equal elements
 * is not specified but follows the JDK's binary heap, which vanilla relies on for tick
 * scheduling ties (it is printed here and registered if it differs).
 */
final class Sorting {
    record Item(int key, int index) {
    }

    private Sorting() {
    }

    static void run() {
        Rand random = new Rand(7);
        for (int n : new int[] {5, 31, 32, 33, 100, 1000, 20000}) {
            List<Item> items = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                items.add(new Item(random.nextInt(Math.max(2, n / 8)), i));
            }
            Comparator<Item> byKey = Comparator.comparingInt(Item::key);
            Out.check("arraysSort.n" + n, () -> {
                Item[] array = items.toArray(new Item[0]);
                Arrays.sort(array, byKey);
                return indexes(Arrays.asList(array));
            });
            Out.check("collectionsSort.n" + n, () -> {
                List<Item> list = new ArrayList<>(items);
                Collections.sort(list, byKey.reversed());
                return indexes(list);
            });
            Out.check("listSort.n" + n, () -> {
                List<Item> list = new ArrayList<>(items);
                list.sort(Comparator.comparingInt((Item item) -> item.key() % 3).thenComparing(Item::key, Comparator.reverseOrder()));
                return indexes(list);
            });
            Out.check("streamSorted.n" + n, () -> indexes(items.stream().sorted(byKey).collect(Collectors.toList())));
            Out.check("rangeSort.n" + n, () -> {
                Item[] array = items.toArray(new Item[0]);
                Arrays.sort(array, n / 4, n - n / 4, byKey);
                return indexes(Arrays.asList(array));
            });
            Out.check("comparableSort.n" + n, () -> {
                String[] strings = new String[n];
                for (int i = 0; i < n; i++) {
                    strings[i] = Integer.toString(items.get(i).key(), 7);
                }
                Arrays.sort(strings);
                Out.Digest digest = new Out.Digest();
                for (String s : strings) {
                    digest.add(s);
                }
                return digest;
            });
            Out.check("priorityQueue.n" + n, () -> {
                PriorityQueue<Item> queue = new PriorityQueue<>(byKey);
                queue.addAll(items);
                List<Item> polled = new ArrayList<>(n);
                while (!queue.isEmpty()) {
                    polled.add(queue.poll());
                }
                return indexes(polled);
            });
            Out.check("priorityQueue.iteration.n" + n, () -> {
                PriorityQueue<Item> queue = new PriorityQueue<>(byKey);
                for (Item item : items) {
                    queue.add(item);
                }
                return indexes(new ArrayList<>(queue));
            });
        }
        double[] doubles = {3, -0.0, Double.NaN, 0.0, -1, Double.NEGATIVE_INFINITY, 0.0, -0.0, 2.5,
            Double.POSITIVE_INFINITY, Double.longBitsToDouble(0x7ff8000000000001L), 1e-300};
        Out.check("doubleSort", () -> {
            double[] copy = doubles.clone();
            Arrays.sort(copy);
            StringBuilder out = new StringBuilder();
            for (double d : copy) {
                out.append(Out.hex(d)).append(',');
            }
            return out;
        });
        Out.check("floatSort", () -> {
            float[] copy = new float[doubles.length];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = (float) doubles[i];
            }
            Arrays.sort(copy);
            StringBuilder out = new StringBuilder();
            for (float f : copy) {
                out.append(Out.hex(f)).append(',');
            }
            return out;
        });
        Out.check("intSort", () -> {
            int[] ints = new int[2000];
            Rand r = new Rand(3);
            for (int i = 0; i < ints.length; i++) {
                ints[i] = r.nextInt();
            }
            Arrays.sort(ints);
            Out.Digest digest = new Out.Digest();
            for (int v : ints) {
                digest.add(v);
            }
            return digest;
        });
        Out.check("binarySearch", () -> Arrays.binarySearch(new int[] {1, 3, 3, 3, 5}, 3) + ","
                + Arrays.binarySearch(new int[] {1, 3, 5}, 4) + "," + Collections.binarySearch(List.of(1, 3, 3, 3, 5), 3));
        Out.check("nullsFirst", () -> {
            List<String> list = new ArrayList<>(Arrays.asList("b", null, "a", null, "c"));
            list.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
            return list;
        });
        Out.check("caseInsensitive", () -> {
            List<String> list = new ArrayList<>(List.of("b", "B", "a", "A", "é", "E", "e"));
            list.sort(String.CASE_INSENSITIVE_ORDER);
            return list;
        });
    }

    static String indexes(List<Item> items) {
        if (items.size() <= 40) {
            StringBuilder out = new StringBuilder();
            for (Item item : items) {
                out.append(item.index()).append(',');
            }
            return out.toString();
        }
        Out.Digest digest = new Out.Digest();
        for (Item item : items) {
            digest.add(item.index());
        }
        return digest.toString();
    }
}
