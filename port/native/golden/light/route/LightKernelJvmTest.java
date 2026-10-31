package dev.gaius.browser.kernel.light;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** JVM-only seam test for the production release and completion paths. */
public final class LightKernelJvmTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        InFlightReleaseGate gate = new InFlightReleaseGate();
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> racers = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            Thread thread = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (gate.tryRelease()) releases.incrementAndGet();
            });
            racers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : racers) thread.join();
        check(releases.get() == 1, "timeout and late callback released the in-flight slot more than once");
        check(!gate.tryRelease(), "late callback released an already timed-out job");

        CompletionFrontier frontier = new CompletionFrontier();
        List<String> applied = new ArrayList<>();
        List<String> completed = new ArrayList<>();
        CompletionFrontier.Ticket a = frontier.register(() -> completed.add("timeout-a"));
        CompletionFrontier.Ticket b = frontier.register(() -> completed.add("timeout-b"));
        CompletionFrontier.Ticket c = frontier.register(() -> completed.add("timeout-c"));
        b.complete(() -> { applied.add("B"); completed.add("B"); });
        c.complete(() -> { applied.add("C"); completed.add("C"); });
        check(applied.isEmpty(), "completion frontier installed a public future before its prefix");
        a.complete(() -> { applied.add("A"); completed.add("A"); });
        check(applied.equals(List.of("A", "B", "C")), "completion prefix did not drain in acceptance order");
        check(completed.equals(List.of("A", "B", "C")), "late callback/timeout changed completion order");
        System.out.println("LIGHT_KERNEL_JVM_TEST PASS releaseOnce=1 applied=" + applied);
    }
}
