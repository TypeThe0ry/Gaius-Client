import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.teavm.classlib.java.util.TArrayDeque;
import org.teavm.classlib.java.util.TIterator;

/**
 * Check of TeaVMClasslibPatcher.patchArrayDequeIteratorRemove: the iterator of TeaVM's ArrayDeque
 * (the class behind java.util.ArrayDeque, and behind Gaius' ConcurrentLinkedQueue, ConcurrentLinkedDeque
 * and LinkedBlockingQueue wrappers in the WebAssembly build) resumed one slot too far after remove() of
 * an element of the back half: the next element was skipped and the scan read null behind the tail.
 *
 * Run by classlib-arraydeque-iterator-remove.test.mjs, once over TeaVM's class library as it is and once
 * over the patched class:
 *
 *   java ArrayDequeIteratorRemoveCheck patch <class library jar> <output dir>
 *   java ArrayDequeIteratorRemoveCheck check                      (prints the defects; exit 0)
 *
 * A defect is a scan whose elements are not exactly the elements of the deque before the removal, in
 * order, or whose remaining elements are wrong. TArrayDeque is loaded on HotSpot like any class (it uses
 * no platform intrinsics); a thrown TConcurrentModificationException is a VerifyError there and counts as
 * a defect as well.
 */
public final class ArrayDequeIteratorRemoveCheck {
    private ArrayDequeIteratorRemoveCheck() {
    }

    /** Elements 0..n-1 in a deque whose head sits at {@code shift}: the removal then crosses the array end too. */
    private static TArrayDeque<Object> deque(int n, int shift) {
        TArrayDeque<Object> deque = new TArrayDeque<>();
        for (int i = 0; i < shift; i++) {
            deque.addLast("x");
        }
        for (int i = 0; i < shift; i++) {
            deque.pollFirst();
        }
        for (int i = 0; i < n; i++) {
            deque.addLast(i);
        }
        return deque;
    }

    private static List<Object> contents(TArrayDeque<Object> deque) {
        List<Object> all = new ArrayList<>();
        for (TIterator<Object> it = deque.iterator(); it.hasNext();) {
            all.add(it.next());
        }
        return all;
    }

    /** Scans with {@code removeWhen.test(index)} removing through the iterator; the elements seen must be 0..n-1. */
    private static boolean scanIsCorrect(TArrayDeque<Object> deque, int n, java.util.function.IntPredicate removeWhen,
            boolean descending) {
        List<Object> seen = new ArrayList<>();
        List<Object> expectedRest = new ArrayList<>();
        try {
            TIterator<Object> it = descending ? deque.descendingIterator() : deque.iterator();
            for (int index = 0; it.hasNext(); index++) {
                Object element = it.next();
                seen.add(element);
                if (removeWhen.test(index)) {
                    it.remove();
                } else {
                    expectedRest.add(element);
                }
            }
        } catch (Throwable thrown) {
            return false;
        }
        List<Object> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(descending ? n - 1 - i : i);
        }
        List<Object> rest = contents(deque);
        java.util.Comparator<Object> byValue = java.util.Comparator.nullsFirst((a, b) -> ((Integer) a).compareTo((Integer) b));
        rest.sort(byValue);
        expectedRest.sort(byValue);
        return seen.equals(order) && rest.equals(expectedRest);
    }

    private static int[] check() {
        int single = 0;
        int singleWrong = 0;
        int many = 0;
        int manyWrong = 0;
        for (int n = 1; n <= 14; n++) {
            for (int shift = 0; shift < 9; shift++) {
                for (int at = 0; at < n; at++) {
                    final int target = at;
                    single++;
                    if (!scanIsCorrect(deque(n, shift), n, i -> i == target, false)) {
                        singleWrong++;
                    }
                }
            }
        }
        Random random = new Random(20261114L);
        for (int run = 0; run < 20000; run++) {
            int n = 1 + random.nextInt(24);
            int shift = random.nextInt(30);
            boolean[] drop = new boolean[n];
            for (int i = 0; i < n; i++) {
                drop[i] = random.nextInt(3) == 0;
            }
            boolean descending = random.nextBoolean();
            many++;
            if (!scanIsCorrect(deque(n, shift), n, i -> drop[i], descending)) {
                manyWrong++;
            }
        }
        // Collection.removeIf / removeAll / retainAll go through the same iterator
        int collectionOps = 0;
        int collectionOpsWrong = 0;
        for (int run = 0; run < 5000; run++) {
            int n = 1 + random.nextInt(24);
            TArrayDeque<Object> deque = deque(n, random.nextInt(30));
            int modulus = 2 + random.nextInt(3);
            collectionOps++;
            try {
                deque.removeIf(value -> (Integer) value % modulus == 0);
                List<Object> expected = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    if (i % modulus != 0) {
                        expected.add(i);
                    }
                }
                if (!contents(deque).equals(expected)) {
                    collectionOpsWrong++;
                }
            } catch (Throwable thrown) {
                collectionOpsWrong++;
            }
        }
        // the loop shape of vanilla's ParticleGroup.tickParticles / CrossFrameResourcePool.endFrame: read a field
        // of every element that comes back and remove the dead ones
        int loops = 0;
        int loopsWrong = 0;
        for (int run = 0; run < 5000; run++) {
            int n = 1 + random.nextInt(24);
            TArrayDeque<Object> deque = deque(n, random.nextInt(30));
            int dead = 1 + random.nextInt(n);
            loops++;
            int ticked = 0;
            boolean nullSeen = false;
            for (TIterator<Object> it = deque.iterator(); it.hasNext();) {
                Object element = it.next();
                if (element == null) {
                    nullSeen = true;
                    break;
                }
                ticked++;
                if ((Integer) element >= n - dead) {
                    it.remove();
                }
            }
            if (nullSeen || ticked != n || deque.size() != n - dead) {
                loopsWrong++;
            }
        }
        System.out.println("single removals: " + singleWrong + " of " + single + " scans wrong");
        System.out.println("random removal patterns, both directions: " + manyWrong + " of " + many + " scans wrong");
        System.out.println("removeIf: " + collectionOpsWrong + " of " + collectionOps + " wrong");
        System.out.println("expiry loop (null element or skipped element): " + loopsWrong + " of " + loops + " wrong");
        return new int[] {singleWrong, manyWrong, collectionOpsWrong, loopsWrong};
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("patch")) {
            Class<?> patcher = Class.forName("dev.gaius.tools.TeaVMClasslibPatcher");
            Method patch = patcher.getDeclaredMethod("patchArrayDequeIteratorRemove", String.class, Path.class);
            patch.setAccessible(true);
            Path output = Path.of(args[2]);
            patch.invoke(null, args[1], output);
            // the class that was written is no longer TeaVM's body: a second application refuses it
            String entry = "org/teavm/classlib/java/util/TArrayDeque$1.class";
            Path patchedJar = output.resolveSibling("patched-array-deque.jar");
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                    java.nio.file.Files.newOutputStream(patchedJar))) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry));
                zip.write(java.nio.file.Files.readAllBytes(output.resolve(entry)));
                zip.closeEntry();
            }
            try {
                patch.invoke(null, patchedJar.toString(), output.resolveSibling("again"));
                throw new IllegalStateException("patchArrayDequeIteratorRemove accepted its own output");
            } catch (InvocationTargetException expected) {
                if (!(expected.getCause() instanceof IllegalStateException)) {
                    throw expected;
                }
            }
            System.out.println("ARRAY_DEQUE_PATCH_OK refusesPatchedBody=true");
            return;
        }
        int[] defects = check();
        int total = 0;
        for (int defect : defects) {
            total += defect;
        }
        System.out.println("ARRAY_DEQUE_DEFECTS " + total);
    }
}
