import dev.gaius.browser.kernel.KernelStatusFrontier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * JVM check of the status frontier's claim/publish contract with real threads (no Minecraft types).
 *
 * <p>A ticket's claim is decided by a compare-and-set outside the frontier lock and its action is
 * published inside it. While one thread drains (runs the earlier tickets' actions inline, holding the
 * lock), another thread may win the claim of a later ticket and then wait for the lock. The drain must
 * not complete that ticket before its action is published, or it would silently take the vanilla
 * supplier although the kernel result is ready.
 */
public final class KernelStatusFrontierRaceCheck {
    private KernelStatusFrontierRaceCheck() {
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Supplier<CompletableFuture<String>> value(String name, List<String> log) {
        return () -> {
            log.add(name);
            return CompletableFuture.completedFuture(name);
        };
    }

    public static void main(String[] args) throws Exception {
        claimWonDuringDrain();
        publishedClaimsKeepOrder();
        throwingFallbackObserverStillSelectsVanilla();
        System.out.println("KernelStatusFrontierRaceCheck PASS");
    }

    /** Thread T1 drains ticket A inline; T2 wins B's claim meanwhile and must still run B's kernel action. */
    private static void claimWonDuringDrain() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        KernelStatusFrontier<String> frontier = new KernelStatusFrontier<>();
        KernelStatusFrontier.Ticket<String> a = frontier.register(value("vanilla-A", log), 0, reason -> {});
        KernelStatusFrontier.Ticket<String> b = frontier.register(value("vanilla-B", log), 0, reason -> {});
        a.consumeAtTask();
        b.consumeAtTask();

        CountDownLatch inDrain = new CountDownLatch(1);
        AtomicBoolean sawClaim = new AtomicBoolean();
        Thread first = new Thread(() -> a.prefetch(() -> {
            inDrain.countDown();
            // Hold the frontier lock (we are inside drainLocked) until the other thread has won B's claim.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!b.claimed() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            sawClaim.set(b.claimed());
            log.add("kernel-A");
            return CompletableFuture.completedFuture("kernel-A");
        }), "drain");
        Thread second = new Thread(() -> {
            try {
                inDrain.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            b.prefetch(value("kernel-B", log));
        }, "claimant");
        first.start();
        second.start();
        first.join(15_000);
        second.join(15_000);
        require(!first.isAlive() && !second.isAlive(), "a frontier thread hung");
        require(sawClaim.get(), "the claimant never won B's claim while A drained");
        require(a.future().isDone() && b.future().isDone(), "tickets left pending: " + log);
        require(log.equals(List.of("kernel-A", "kernel-B")),
                "B did not run its published kernel action, got " + log);
        require("kernel-B".equals(b.future().get()), "B completed with the wrong value");
    }

    /** Claims that arrive out of order still complete in registration order, and all take their kernel action. */
    private static void publishedClaimsKeepOrder() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        KernelStatusFrontier<String> frontier = new KernelStatusFrontier<>();
        List<KernelStatusFrontier.Ticket<String>> tickets = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            KernelStatusFrontier.Ticket<String> ticket = frontier.register(value("vanilla-" + i, log), 0, reason -> {});
            ticket.consumeAtTask();
            tickets.add(ticket);
        }
        int[] order = {3, 1, 5, 0, 4, 2};
        for (int index : order) {
            require(tickets.get(index).prefetch(value("kernel-" + index, log)), "claim refused " + index);
        }
        List<String> expected = List.of("kernel-0", "kernel-1", "kernel-2", "kernel-3", "kernel-4", "kernel-5");
        require(log.equals(expected), "completion order changed: " + log);
    }

    /** A fallback consumer that throws must not leave the claimed ticket without a selected action. */
    private static void throwingFallbackObserverStillSelectsVanilla() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        KernelStatusFrontier<String> frontier = new KernelStatusFrontier<>();
        KernelStatusFrontier.Ticket<String> ticket = frontier.register(value("vanilla", log), 0, reason -> {
            throw new IllegalStateException("consumer failure");
        });
        ticket.consumeAtTask();
        try {
            ticket.fallback("kernel-error");
            throw new AssertionError("the consumer failure was swallowed");
        } catch (IllegalStateException expected) {
            // The failure still propagates to the caller.
        }
        require(ticket.future().isDone() && log.equals(List.of("vanilla")),
                "a failing fallback consumer left the ticket without vanilla: " + log);
    }
}
