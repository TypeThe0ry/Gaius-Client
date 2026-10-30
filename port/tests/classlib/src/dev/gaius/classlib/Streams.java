package dev.gaius.classlib;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * java.util.stream pipelines whose result size the class library has to know in advance.
 * TeaVM's toArray allocates the size a stream estimates and writes past it when the estimate is
 * too small: a WebAssembly GC trap (array element access out of bounds), an array that silently
 * grows in the JavaScript build. sorted() drains its source when it is created, and a source
 * whose estimate counts the remaining elements (fastutil and Guava list spliterators) then
 * reports 0; vanilla's LevelStorageSource.loadLevelSummaries sorts the level summaries this way
 * (filter, sorted, toList). Spliterators that are not SIZED may estimate any number. distinct()
 * must remember the elements it saw across the separate pulls of iterator(), skip() and limit().
 * A stream's spliterator() must return false from tryAdvance() once it has no element left: TeaVM
 * returned true one more time, and an iterator over it (Guava's Streams.mapWithIndex, which
 * vanilla's NBT list streams use) repeated the last element, so every chunk loaded its last entity
 * twice.
 */
final class Streams {
    private Streams() {
    }

    static void run() {
        sortedAfterDrainedSource();
        unsizedEstimates();
        distinctAcrossPulls();
        spliteratorEnd();
    }

    private static void sortedAfterDrainedSource() {
        for (int size : new int[] {0, 1, 5, 40}) {
            String[] words = words(size);
            List<Supplier<Stream<String>>> sources = new ArrayList<>();
            List<String> names = new ArrayList<>();
            names.add("remaining");
            sources.add(() -> StreamSupport.stream(new ArraySpliterator<>(words, Spliterator.SIZED | Spliterator.ORDERED, -1), false));
            names.add("supplierRemaining");
            sources.add(() -> StreamSupport.stream(
                    () -> new ArraySpliterator<>(words, Spliterator.SIZED | Spliterator.ORDERED, -1),
                    Spliterator.SIZED | Spliterator.ORDERED, false));
            names.add("arrayList");
            sources.add(() -> new ArrayList<>(Arrays.asList(words)).stream());
            names.add("linkedList");
            sources.add(() -> new LinkedList<>(Arrays.asList(words)).stream());
            names.add("arrayDeque");
            sources.add(() -> new ArrayDeque<>(nonNull(words)).stream());
            names.add("asList");
            sources.add(() -> Arrays.asList(words).stream());
            names.add("hashSet");
            sources.add(() -> new HashSet<>(Arrays.asList(words)).stream());
            names.add("of");
            sources.add(() -> Stream.of(words));
            names.add("arrayRange");
            sources.add(() -> Arrays.stream(words, 0, words.length));
            names.add("concat");
            sources.add(() -> Stream.concat(Stream.of(words), StreamSupport.stream(
                    new ArraySpliterator<>(words, Spliterator.SIZED | Spliterator.ORDERED, -1), false)));
            for (int s = 0; s < sources.size(); s++) {
                Supplier<Stream<String>> source = sources.get(s);
                String key = names.get(s) + ".n" + size;
                // LevelStorageSource.loadLevelSummaries: filter(Objects::nonNull).sorted().toList()
                Out.check("sorted.toList." + key, () -> source.get().filter(Objects::nonNull).sorted().toList());
                Out.check("sorted.toArray." + key, () -> Arrays.toString(
                        source.get().filter(Objects::nonNull).sorted(Comparator.reverseOrder()).toArray(String[]::new)));
                Out.check("sorted.toArrayObject." + key, () -> Arrays.toString(
                        source.get().filter(Objects::nonNull).sorted().toArray()));
                Out.check("sorted.map." + key, () -> Arrays.toString(
                        source.get().filter(Objects::nonNull).sorted().map(String::length).toArray(Integer[]::new)));
                Out.check("sorted.skip." + key, () -> source.get().filter(Objects::nonNull).sorted().skip(2).toList());
                Out.check("sorted.limit." + key, () -> source.get().filter(Objects::nonNull).sorted().limit(3).toList());
                Out.check("sorted.sorted." + key, () -> source.get().filter(Objects::nonNull).sorted()
                        .sorted(Comparator.comparingInt(String::length)).toList());
                Out.check("sorted.collect." + key, () -> source.get().filter(Objects::nonNull).sorted()
                        .collect(Collectors.toList()));
                Out.check("sorted.count." + key, () -> source.get().filter(Objects::nonNull).sorted().count());
                Out.check("sorted.toSet." + key, () -> new TreeSet<>(source.get().filter(Objects::nonNull).sorted()
                        .collect(Collectors.toSet())));
            }
        }
        Out.check("sorted.mapToInt", () -> Arrays.toString(StreamSupport.stream(
                new ArraySpliterator<>(words(12), Spliterator.SIZED, -1), false).filter(Objects::nonNull).sorted()
                .mapToInt(String::length).toArray()));
        Out.check("sorted.flatMap", () -> StreamSupport.stream(
                new ArraySpliterator<>(words(6), Spliterator.SIZED, -1), false).filter(Objects::nonNull).sorted()
                .flatMap(word -> Stream.of(word, word.toUpperCase())).toList());
    }

    private static void unsizedEstimates() {
        String[] words = nonNull(words(9)).toArray(new String[0]);
        long[] estimates = {0, 1, 5, Long.MAX_VALUE, (1L << 32) + 2, Long.MAX_VALUE - 1, -1};
        for (int e = 0; e < estimates.length; e++) {
            long estimate = estimates[e];
            Function<Integer, Stream<String>> source = characteristics -> StreamSupport.stream(
                    new ArraySpliterator<>(words, characteristics, estimate), false);
            String key = ".e" + e;
            Out.check("unsized.toArray" + key, () -> Arrays.toString(source.apply(Spliterator.ORDERED).toArray()));
            Out.check("unsized.toList" + key, () -> source.apply(Spliterator.ORDERED).toList());
            Out.check("unsized.map" + key, () -> Arrays.toString(source.apply(Spliterator.ORDERED)
                    .map(String::length).toArray(Integer[]::new)));
            Out.check("unsized.limit" + key, () -> source.apply(Spliterator.ORDERED).limit(7).toList());
            Out.check("unsized.skip" + key, () -> source.apply(Spliterator.ORDERED).skip(2).toList());
            Out.check("unsized.count" + key, () -> source.apply(Spliterator.ORDERED).count());
            Out.check("unsized.supplier" + key, () -> Arrays.toString(StreamSupport.stream(
                    () -> new ArraySpliterator<>(words, Spliterator.ORDERED, estimate), Spliterator.ORDERED, false)
                    .toArray(String[]::new)));
        }
        Out.check("sized.count", () -> StreamSupport.stream(
                new ArraySpliterator<>(words, Spliterator.SIZED, -1), false).count());
        Out.check("sized.toArray", () -> Arrays.toString(StreamSupport.stream(
                new ArraySpliterator<>(words, Spliterator.SIZED, -1), false).toArray(String[]::new)));
    }

    private static void distinctAcrossPulls() {
        Integer[] values = {3, 1, 3, 2, 1, 2, 3, 4, 1};
        Out.check("distinct.toList", () -> Stream.of(values).distinct().toList());
        Out.check("distinct.skip", () -> Stream.of(values).distinct().skip(1).toList());
        Out.check("distinct.limit", () -> Stream.of(values).distinct().limit(3).toList());
        Out.check("distinct.iterator", () -> {
            List<Integer> out = new ArrayList<>();
            for (Iterator<Integer> it = Stream.of(values).distinct().iterator(); it.hasNext();) {
                out.add(it.next());
            }
            return out;
        });
        Out.check("distinct.spliterator", () -> {
            List<Integer> out = new ArrayList<>();
            Spliterator<Integer> spliterator = Stream.of(values).distinct().spliterator();
            while (spliterator.tryAdvance(out::add)) {
                // one element per pull
            }
            return out;
        });
        Out.check("distinct.concat", () -> Stream.concat(Stream.of(values).distinct(), Stream.of(9)).skip(1).toList());
        Out.check("distinct.skipFindFirst", () -> {
            Stream<Integer> stream = Stream.of(values).distinct().skip(3);
            return stream.findFirst().orElse(-1);
        });
        int[] ints = {3, 1, 3, 2, 1, 2, 3, 4, 1};
        Out.check("distinct.int.skip", () -> Arrays.toString(IntStream.of(ints).distinct().skip(1).toArray()));
        Out.check("distinct.int.iterator", () -> {
            List<Integer> out = new ArrayList<>();
            for (PrimitiveIterator.OfInt it = IntStream.of(ints).distinct().iterator(); it.hasNext();) {
                out.add(it.nextInt());
            }
            return out;
        });
        Out.check("distinct.long.skip", () -> Arrays.toString(LongStream.of(3, 1, 3, 2, 1, 2, 3, 4, 1)
                .distinct().skip(1).toArray()));
        Out.check("distinct.long.limit", () -> Arrays.toString(LongStream.of(3, 1, 3, 2, 1, 2, 3, 4, 1)
                .distinct().limit(3).toArray()));
        Out.check("distinct.double.skip", () -> Arrays.toString(DoubleStream.of(3, 1, 3, 2, 1, 2, 3, 4, 1)
                .distinct().skip(1).toArray()));
        Out.check("distinct.double.iterator", () -> {
            List<Double> out = new ArrayList<>();
            for (PrimitiveIterator.OfDouble it = DoubleStream.of(3, 1, 3, 2, 1, 2, 3, 4, 1).distinct().iterator();
                    it.hasNext();) {
                out.add(it.nextDouble());
            }
            return out;
        });
    }

    private static void spliteratorEnd() {
        List<String> names = new ArrayList<>();
        List<Function<List<String>, Stream<String>>> shapes = new ArrayList<>();
        names.add("list");
        shapes.add(List::stream);
        names.add("of");
        shapes.add(list -> Stream.of(list.toArray(new String[0])));
        names.add("filter");
        shapes.add(list -> list.stream().filter(Objects::nonNull));
        names.add("map");
        shapes.add(list -> list.stream().map(String::toUpperCase));
        names.add("flatMap");
        shapes.add(list -> list.stream().flatMap(word -> Stream.of(word, word + "'")));
        names.add("sorted");
        shapes.add(list -> list.stream().sorted());
        names.add("supplier");
        shapes.add(list -> StreamSupport.stream(list::spliterator, Spliterator.SIZED, false));
        for (int size : new int[] {0, 1, 2, 5}) {
            List<String> list = new ArrayList<>(nonNull(words(size + 1)));
            while (list.size() > size) {
                list.remove(list.size() - 1);
            }
            for (int s = 0; s < shapes.size(); s++) {
                Function<List<String>, Stream<String>> shape = shapes.get(s);
                String key = names.get(s) + ".n" + size;
                Out.check("spliteratorEnd.tryAdvance." + key, () -> {
                    Spliterator<String> spliterator = shape.apply(list).spliterator();
                    int[] accepted = {0};
                    int advanced = 0;
                    while (advanced < 20 && spliterator.tryAdvance(word -> accepted[0]++)) {
                        advanced++;
                    }
                    boolean again = spliterator.tryAdvance(word -> accepted[0]++);
                    return "advanced=" + advanced + " accepted=" + accepted[0] + " again=" + again;
                });
                Out.check("spliteratorEnd.iterator." + key, () -> {
                    List<String> out = new ArrayList<>();
                    Iterator<String> iterator = Spliterators.iterator(shape.apply(list).spliterator());
                    while (out.size() < 20 && iterator.hasNext()) {
                        out.add(iterator.next());
                    }
                    return out + " hasNext=" + iterator.hasNext();
                });
                Out.check("spliteratorEnd.restream." + key,
                        () -> StreamSupport.stream(shape.apply(list).spliterator(), false).toList());
            }
        }
        Out.check("spliteratorEnd.int.tryAdvance", () -> {
            Spliterator.OfInt spliterator = IntStream.of(4, 5, 6).filter(i -> i != 0).spliterator();
            List<Integer> out = new ArrayList<>();
            int advanced = 0;
            while (advanced < 20 && spliterator.tryAdvance((java.util.function.IntConsumer) out::add)) {
                advanced++;
            }
            return "advanced=" + advanced + " " + out;
        });
        Out.check("spliteratorEnd.int.iterator", () -> {
            List<Integer> out = new ArrayList<>();
            PrimitiveIterator.OfInt iterator = Spliterators.iterator(IntStream.of(4, 5, 6).map(i -> i * 2).spliterator());
            while (out.size() < 20 && iterator.hasNext()) {
                out.add(iterator.nextInt());
            }
            return out;
        });
        Out.check("spliteratorEnd.long.tryAdvance", () -> {
            Spliterator.OfLong spliterator = LongStream.of(4, 5).filter(i -> i != 0).spliterator();
            List<Long> out = new ArrayList<>();
            int advanced = 0;
            while (advanced < 20 && spliterator.tryAdvance((java.util.function.LongConsumer) out::add)) {
                advanced++;
            }
            return "advanced=" + advanced + " " + out;
        });
        Out.check("spliteratorEnd.double.tryAdvance", () -> {
            Spliterator.OfDouble spliterator = DoubleStream.of(4, 5).filter(i -> i != 0).spliterator();
            List<Double> out = new ArrayList<>();
            int advanced = 0;
            while (advanced < 20 && spliterator.tryAdvance((java.util.function.DoubleConsumer) out::add)) {
                advanced++;
            }
            return "advanced=" + advanced + " " + out;
        });
        // TagValueInput.ListWrapper.stream() + EntityType.loadEntitiesRecursive, per chunk list:
        // Guava Streams.mapWithIndex (its non-SUBSIZED branch, written out without Guava), filter,
        // mapMulti, forEach. Each chunk must load each entity once.
        List<String> loaded = new ArrayList<>();
        int[] batches = {2, 2, 1, 1, 1, 1, 1, 1, 1};
        for (int b = 0; b < batches.length; b++) {
            List<String> tags = new ArrayList<>();
            for (int i = 0; i < batches[b]; i++) {
                tags.add("cart" + b + "." + i);
            }
            mapWithIndex(tags.stream(), (tag, index) -> tag + "#" + index)
                    .filter(Objects::nonNull)
                    .<String>mapMulti((input, output) -> output.accept(input))
                    .forEach(loaded::add);
        }
        Out.check("spliteratorEnd.entityBatches.count", loaded::size);
        Out.check("spliteratorEnd.entityBatches", () -> loaded);
        Out.check("spliteratorEnd.entityBatches.toList", () -> mapWithIndex(List.of("a", "b", "c").stream(),
                (tag, index) -> tag + "#" + index).filter(Objects::nonNull)
                .<String>mapMulti((input, output) -> output.accept(input)).toList());
    }

    private interface FunctionWithIndex<T, R> {
        R apply(T value, long index);
    }

    /** Guava 33 Streams.mapWithIndex for a stream whose spliterator is not SUBSIZED. */
    private static <T, R> Stream<R> mapWithIndex(Stream<T> stream, FunctionWithIndex<? super T, ? extends R> function) {
        Spliterator<T> from = stream.spliterator();
        Iterator<T> fromIterator = Spliterators.iterator(from);
        int characteristics = from.characteristics() & (Spliterator.ORDERED | Spliterator.SIZED);
        long estimate = from.estimateSize();
        return StreamSupport.stream(new Spliterator<R>() {
            private long index;

            @Override
            public boolean tryAdvance(Consumer<? super R> action) {
                if (fromIterator.hasNext()) {
                    action.accept(function.apply(fromIterator.next(), index++));
                    return true;
                }
                return false;
            }

            @Override
            public Spliterator<R> trySplit() {
                return null;
            }

            @Override
            public long estimateSize() {
                return estimate;
            }

            @Override
            public int characteristics() {
                return characteristics;
            }
        }, false);
    }

    /** size words with a null every seventh element. */
    private static String[] words(int size) {
        Rand random = new Rand(41 + size);
        String[] words = new String[size];
        for (int i = 0; i < size; i++) {
            words[i] = i % 7 == 3 ? null : "w" + Integer.toString(random.nextInt(1 << 20), 36);
        }
        return words;
    }

    private static Collection<String> nonNull(String[] words) {
        List<String> out = new ArrayList<>();
        for (String word : words) {
            if (word != null) {
                out.add(word);
            }
        }
        return out;
    }

    /**
     * A list spliterator: estimate < 0 reports the remaining elements as fastutil and Guava do,
     * otherwise the given estimate.
     */
    private static final class ArraySpliterator<T> implements Spliterator<T> {
        private final T[] items;
        private final int characteristics;
        private final long estimate;
        private int index;

        ArraySpliterator(T[] items, int characteristics, long estimate) {
            this.items = items;
            this.characteristics = characteristics;
            this.estimate = estimate;
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            if (index >= items.length) {
                return false;
            }
            action.accept(items[index++]);
            return true;
        }

        @Override
        public Spliterator<T> trySplit() {
            return null;
        }

        @Override
        public long estimateSize() {
            return estimate >= 0 ? estimate : items.length - index;
        }

        @Override
        public int characteristics() {
            return characteristics;
        }
    }
}
