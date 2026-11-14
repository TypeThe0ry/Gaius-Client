import dev.gaius.browser.kernel.KernelStatusFrontier;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * JVM check of the status frontier's timeout lifecycle (no Minecraft types).
 *
 * <p>A ticket's timeout bounds the wait for a kernel result. Once the claim is decided (a published kernel
 * action or a fallback) the timeout can only find the claim taken, so it must be cancelled: an uncancelled
 * timer keeps the ticket, and through its selected action the decoded kernel result and the generation
 * context, reachable until it would have fired (15 s in the worldgen bridge). The check also keeps the
 * claim contract: exactly one action per ticket, completion in registration order, a timeout that wins the
 * claim still selects the vanilla supplier.
 */
public final class KernelStatusFrontierWatchdogCheck {
    private KernelStatusFrontierWatchdogCheck() {
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

    private static ScheduledFuture<?> watchdogOf(KernelStatusFrontier.Ticket<?> ticket) throws Exception {
        Field field = KernelStatusFrontier.Ticket.class.getDeclaredField("watchdog");
        field.setAccessible(true);
        return (ScheduledFuture<?>) field.get(ticket);
    }

    /** Timeouts in the frontier's shared timer queue that are still able to fire. */
    private static int liveTimeouts() throws Exception {
        Field field = KernelStatusFrontier.class.getDeclaredField("WATCHDOG");
        field.setAccessible(true);
        ScheduledThreadPoolExecutor timer = (ScheduledThreadPoolExecutor) field.get(null);
        int live = 0;
        for (Runnable task : timer.getQueue()) {
            if (!((Future<?>) task).isCancelled()) {
                live++;
            }
        }
        return live;
    }

    public static void main(String[] args) throws Exception {
        decidedTicketsLeaveNoLiveTimeouts();
        prefetchCancelsTimeout();
        fallbackCancelsTimeout();
        claimBeforeConsumeSchedulesNothing();
        zeroTimeoutSchedulesNothing();
        timeoutStillSelectsVanilla();
        timeoutRacesPrefetchWithoutChangingOrder();
        System.out.println("KernelStatusFrontierWatchdogCheck PASS");
    }

    /** The memory-relevant outcome: tickets decided long before their 60 s timeout leave no timer behind. */
    private static void decidedTicketsLeaveNoLiveTimeouts() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        KernelStatusFrontier<String> frontier = new KernelStatusFrontier<>();
        int before = liveTimeouts();
        List<KernelStatusFrontier.Ticket<String>> tickets = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            KernelStatusFrontier.Ticket<String> ticket = frontier.register(value("vanilla", log), 60_000, reason -> {});
            ticket.consumeAtTask();
            tickets.add(ticket);
        }
        require(liveTimeouts() - before == 500, "expected 500 pending timeouts, got " + (liveTimeouts() - before));
        for (int i = 0; i < tickets.size(); i++) {
            if (i % 2 == 0) {
                tickets.get(i).prefetch(value("kernel", log));
            } else {
                tickets.get(i).fallback("kernel-error");
            }
        }
        int live = liveTimeouts() - before;
        require(live == 0, live + " of 500 decided tickets still have a live 60 s timeout");
    }

    private static void prefetchCancelsTimeout() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        List<String> reasons = Collections.synchronizedList(new ArrayList<>());
        KernelStatusFrontier<String> frontier = new KernelStatusFrontier<>();
        KernelStatusFrontier.Ticket<String> ticket =
                frontier.register(value("vanilla", log), 60_000, reasons::add);
        ticket.consumeAtTask();
        ScheduledFuture<?> timer = watchdogOf(ticket);
        require(timer != null && !timer.isCancelled(), "a consumed, unclaimed ticket has no live timeout");
        require(ticket.prefetch(value("kernel", log)), "claim refused");
        require(timer.isCancelled(), "the timeout of a published kernel claim stays pending");
        require(watchdogOf(ticket) == null, "the ticket still references its timeout");
        require(log.equals(List.of("kernel")) && "kernel".equals(ticket.future().get()),
                "the kernel action did not run: " + log);
        require(reasons.isEmpty(), "a fallback consumer was told: " + reasons);
    }

    private static void fallbackCancelsTimeout() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        List<String> reasons = Collections.synchronizedList(new ArrayList<>());
        KernelStatusFrontier<String> frontier = new KernelStatusFrontier<>();
        KernelStatusFrontier.Ticket<String> ticket =
                frontier.register(value("vanilla", log), 60_000, reasons::add);
        ticket.consumeAtTask();
        ScheduledFuture<?> timer = watchdogOf(ticket);
        require(timer != null && !timer.isCancelled(), "a consumed, unclaimed ticket has no live timeout");
        require(ticket.fallback("kernel-error"), "fallback refused");
        require(timer.isCancelled(), "the timeout of a fallen-back ticket stays pending");
        require(log.equals(List.of("vanilla")) && reasons.equals(List.of("kernel-error")),
                "fallback did not select vanilla: " + log + " " + reasons);
        require(!ticket.prefetch(value("late-kernel", log)) && !ticket.fallback("again"),
                "a decided claim was taken a second time");
    }

    /** A claim won before the status task consumed the ticket leaves the timeout nothing to do. */
    private static void claimBeforeConsumeSchedulesNothing() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        KernelStatusFrontier<String> frontier = new KernelStatusFrontier<>();
        KernelStatusFrontier.Ticket<String> ticket = frontier.register(value("vanilla", log), 60_000, reason -> {});
        require(ticket.prefetch(value("kernel", log)), "claim refused");
        ticket.consumeAtTask();
        require(watchdogOf(ticket) == null, "a timeout was scheduled for a decided claim");
        require(ticket.future().isDone() && "kernel".equals(ticket.future().get()), "ticket did not complete: " + log);
    }

    private static void zeroTimeoutSchedulesNothing() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        KernelStatusFrontier<String> frontier = new KernelStatusFrontier<>();
        KernelStatusFrontier.Ticket<String> ticket = frontier.register(value("vanilla", log), 0, reason -> {});
        ticket.consumeAtTask();
        require(watchdogOf(ticket) == null, "a timeout was scheduled for timeoutMs=0");
        require(ticket.prefetch(value("kernel", log)) && "kernel".equals(ticket.future().get()), "ticket broke");
    }

    /** An unclaimed ticket still falls back to vanilla when its timeout passes, exactly once. */
    private static void timeoutStillSelectsVanilla() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        List<String> reasons = Collections.synchronizedList(new ArrayList<>());
        KernelStatusFrontier<String> frontier = new KernelStatusFrontier<>();
        KernelStatusFrontier.Ticket<String> ticket = frontier.register(value("vanilla", log), 30, reasons::add);
        ticket.consumeAtTask();
        require("vanilla".equals(ticket.future().get(10, TimeUnit.SECONDS)), "the timeout did not select vanilla");
        require(reasons.equals(List.of("timeout")), "the timeout reason was not delivered once: " + reasons);
        require(!ticket.prefetch(value("late-kernel", log)), "a kernel result won after the timeout");
        require(log.equals(List.of("vanilla")), "unexpected actions: " + log);
    }

    /**
     * Many tickets whose timeout and kernel result race on different threads: each takes exactly one of the
     * two actions, none stays pending, and they complete in registration order.
     */
    private static void timeoutRacesPrefetchWithoutChangingOrder() throws Exception {
        int count = 2000;
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger kernelActions = new AtomicInteger();
        AtomicInteger vanillaActions = new AtomicInteger();
        AtomicInteger timeouts = new AtomicInteger();
        KernelStatusFrontier<Integer> frontier = new KernelStatusFrontier<>();
        List<KernelStatusFrontier.Ticket<Integer>> tickets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            tickets.add(frontier.register(() -> {
                vanillaActions.incrementAndGet();
                order.add(index);
                return CompletableFuture.completedFuture(index);
            }, 40 + 12 * (i % 8), reason -> timeouts.incrementAndGet()));
        }
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(8);
        try {
            List<ScheduledFuture<?>> done = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                KernelStatusFrontier.Ticket<Integer> ticket = tickets.get(i);
                // Completion order is consumption order (the vanilla status task order): consume in order
                // here; the kernel results (scheduled around the timeouts' deadlines) and the timeouts race.
                ticket.consumeAtTask();
                if (index % 3 != 0) {
                    done.add(pool.schedule(() -> {
                        ticket.prefetch(() -> {
                            kernelActions.incrementAndGet();
                            order.add(index);
                            return CompletableFuture.completedFuture(index);
                        });
                    }, 6L * (index % 24), TimeUnit.MILLISECONDS));
                }
            }
            for (ScheduledFuture<?> future : done) {
                future.get(60, TimeUnit.SECONDS);
            }
            for (int i = 0; i < count; i++) {
                require(Integer.valueOf(i).equals(tickets.get(i).future().get(60, TimeUnit.SECONDS)),
                        "ticket " + i + " completed with the wrong value");
            }
        } finally {
            pool.shutdownNow();
        }
        require(kernelActions.get() + vanillaActions.get() == count,
                "actions " + kernelActions + " + " + vanillaActions + " != " + count);
        require(timeouts.get() <= vanillaActions.get(), "more timeouts than vanilla actions");
        require(kernelActions.get() > 0 && timeouts.get() > 0,
                "the race did not exercise both outcomes: " + kernelActions + " kernel, " + timeouts + " timeouts");
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            expected.add(i);
        }
        require(order.equals(expected), "completion left registration order");
        // A timeout that won its claim is still finishing when the ticket's future completes; every other
        // ticket has cancelled its timeout. Give those a moment, then nothing may be left live.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int live;
        do {
            live = 0;
            for (KernelStatusFrontier.Ticket<Integer> ticket : tickets) {
                ScheduledFuture<?> timer = watchdogOf(ticket);
                if (timer != null && !timer.isDone() && !timer.isCancelled()) {
                    live++;
                }
            }
        } while (live > 0 && System.nanoTime() < deadline);
        require(live == 0, live + " decided tickets still hold a live timeout");
        System.out.println("race: " + kernelActions + " kernel, " + vanillaActions + " vanilla (" + timeouts
                + " timeouts) of " + count);
    }
}
