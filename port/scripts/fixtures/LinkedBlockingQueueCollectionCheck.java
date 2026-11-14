import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import org.teavm.classlib.java.util.concurrent.TLinkedBlockingQueue;

/**
 * Differential check of Gaius' LinkedBlockingQueue wrapper (the WebAssembly build's class behind
 * java.util.concurrent.LinkedBlockingQueue, port/src/main/java/org/teavm/classlib/java/util/concurrent/
 * TLinkedBlockingQueue.java) against the JDK's, for the constructor that takes a collection: the JDK
 * queue is unbounded; the wrapper bounded itself to the size of the collection (1 for an empty one), so every
 * later offer returned false and add/put threw "queue is full". The wrapper is a plain Java class over
 * java.util.ArrayDeque, so it runs on HotSpot as written.
 *
 *   java LinkedBlockingQueueCollectionCheck        (exit 1 on any difference)
 */
public final class LinkedBlockingQueueCollectionCheck {
    private LinkedBlockingQueueCollectionCheck() {
    }

    private static String trace(java.util.concurrent.BlockingQueue<Integer> queue, Collection<Integer> source) {
        StringBuilder out = new StringBuilder();
        out.append("size=").append(queue.size()).append(" remaining=").append(queue.remainingCapacity() == Integer.MAX_VALUE - queue.size());
        for (int i = 0; i < 5; i++) {
            out.append(" offer=").append(queue.offer(100 + i));
        }
        try {
            queue.add(200);
            queue.put(201);
            out.append(" addPut=ok");
        } catch (Exception failure) {
            out.append(" addPut=").append(failure.getClass().getSimpleName());
        }
        out.append(" size=").append(queue.size());
        List<Integer> drained = new ArrayList<>();
        queue.drainTo(drained, 3);
        out.append(" drained=").append(drained).append(" peek=").append(queue.peek());
        try {
            new TLinkedBlockingQueue<Integer>(Arrays.asList(1, null, 3));
            out.append(" nullElement=accepted");
        } catch (NullPointerException expected) {
            out.append(" nullElement=NullPointerException");
        }
        return out.toString();
    }

    public static void main(String[] args) {
        int failures = 0;
        List<List<Integer>> sources = List.of(List.of(), List.of(7), List.of(1, 2, 3, 4, 5), new ArrayList<>(Arrays.asList(9, 8)));
        for (List<Integer> source : sources) {
            String expected = trace(new LinkedBlockingQueue<>(source), source);
            String actual = trace(new TLinkedBlockingQueue<>(source), source);
            boolean same = expected.equals(actual);
            System.out.println((same ? "same " : "DIFF ") + source + "\n  jdk     " + expected + (same ? "" : "\n  wrapper " + actual));
            if (!same) {
                failures++;
            }
        }
        try {
            new TLinkedBlockingQueue<Integer>((Collection<Integer>) null);
            System.out.println("DIFF null collection accepted");
            failures++;
        } catch (NullPointerException expected) {
            System.out.println("same null collection: NullPointerException");
        }
        System.out.println(failures == 0 ? "LINKED_BLOCKING_QUEUE_COLLECTION_OK" : "LINKED_BLOCKING_QUEUE_COLLECTION_FAIL " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
