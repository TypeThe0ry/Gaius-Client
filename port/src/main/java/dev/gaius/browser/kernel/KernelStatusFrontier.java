package dev.gaius.browser.kernel;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Status bridge: registration prefetches; consumeAtTask controls completion order. */
public final class KernelStatusFrontier<T> {
    private static final ScheduledExecutorService WATCHDOG = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "gaius-kernel-frontier-watchdog"); t.setDaemon(true); return t;
    });
    private final Object lock = new Object();
    private final ArrayDeque<Ticket<T>> pending = new ArrayDeque<>();
    private boolean draining;
    /**
     * Opt-in diagnostics: receives {@code registered}, {@code kernel} and the reason of every ticket
     * that takes its vanilla supplier. Null (the default) means no counting and no extra calls.
     */
    private static Consumer<String> observer;

    /** Installs (or, with null, removes) the diagnostics observer; never changes ticket behavior. */
    public static void setObserver(Consumer<String> value) {
        observer = value;
    }

    private static void observe(String reason) {
        Consumer<String> sink = observer;
        if (sink == null) return;
        try {
            sink.accept(reason);
        } catch (Throwable ignored) {
            // Diagnostics must never alter the status path.
        }
    }
    public Ticket<T> register(Supplier<CompletableFuture<T>> vanilla, long timeoutMs, Runnable onTimeout) {
        return register(vanilla, timeoutMs, reason -> { if ("timeout".equals(reason) && onTimeout != null) onTimeout.run(); });
    }
    public Ticket<T> register(Supplier<CompletableFuture<T>> vanilla, long timeoutMs, Consumer<String> onFallback) {
        observe("registered");
        return new Ticket<>(this, vanilla, timeoutMs, onFallback);
    }
    private void consume(Ticket<T> ticket) {
        synchronized (lock) {
            if (ticket.consumed.compareAndSet(false, true)) { pending.addLast(ticket); ticket.startWatchdog(); }
            drainLocked();
        }
    }
    private void drainLocked() {
        if (draining) return;
        draining = true;
        try {
            while (!pending.isEmpty()) {
                Ticket<T> ticket = pending.peekFirst();
                if (!ticket.claimed.get()) break;
                // The claim is won by a compare-and-set outside the lock; the winner publishes its action
                // (selected) inside it. A drain running in between must wait for that publication instead of
                // completing the ticket with the vanilla supplier: the winner drains again right after.
                if (ticket.selected == null) { ticket.noteUnpublished(); break; }
                pending.removeFirst();
                ticket.completeClaimed();
            }
        }
        finally { draining = false; }
    }
    public static final class Ticket<T> {
        private final KernelStatusFrontier<T> owner;
        private final Supplier<CompletableFuture<T>> vanilla;
        private final long timeoutMs;
        private final Consumer<String> onFallback;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CompletableFuture<Supplier<CompletableFuture<T>>> action = new CompletableFuture<>();
        private final CompletableFuture<T> result;
        private Supplier<CompletableFuture<T>> selected;
        private boolean deferredNoted;
        /**
         * The pending timeout, while it can still act. Once the claim is decided the timeout can only
         * find it taken, so it is cancelled: left alone it keeps this ticket (and through its action the
         * decoded kernel result and the generation context) reachable until the timeout would have fired.
         */
        private volatile ScheduledFuture<?> watchdog;
        private Ticket(KernelStatusFrontier<T> owner, Supplier<CompletableFuture<T>> vanilla, long timeoutMs, Consumer<String> onFallback) {
            this.owner = owner; this.vanilla = vanilla; this.timeoutMs = timeoutMs; this.onFallback = onFallback;
            this.result = action.thenCompose(Supplier::get);
        }
        private void startWatchdog() {
            // A claim that is already decided leaves the timeout nothing to do (fallback returns false).
            if (timeoutMs <= 0 || claimed.get()) return;
            watchdog = WATCHDOG.schedule(() -> fallback("timeout", false), timeoutMs, TimeUnit.MILLISECONDS);
            // A claim won between the check and the store looked at an empty field: cancel for it.
            if (claimed.get()) cancelWatchdog();
        }
        /** Never throws: the claim that calls this has still to publish its action. */
        private void cancelWatchdog() {
            try {
                ScheduledFuture<?> pending = watchdog;
                if (pending != null) {
                    watchdog = null;
                    pending.cancel(false);
                }
            } catch (Throwable ignored) {
                // The timeout is only a bound; a failed cancel leaves it to find the claim taken.
            }
        }
        /** Called by the vanilla status task at its deterministic execution point. */
        public void consumeAtTask() { owner.consume(this); }
        public CompletableFuture<T> future() { return result; }
        /** Publishes a prefetched install action without advancing status completion. */
        public boolean prefetch(Supplier<CompletableFuture<T>> install) {
            if (!claimed.compareAndSet(false, true)) return false;
            cancelWatchdog();
            synchronized (owner.lock) { selected = install != null ? install : vanilla; owner.drainLocked(); } return true;
        }
        /** Compatibility name for JVM golden seams; identical prefetch-only semantics. */
        public boolean claimKernel(Supplier<CompletableFuture<T>> install) { return prefetch(install); }
        public boolean fallback(String reason) { return fallback(reason, true); }
        /** {@code cancelTimeout} is false for the timeout itself, which is the task that is running. */
        private boolean fallback(String reason, boolean cancelTimeout) {
            if (!claimed.compareAndSet(false, true)) return false;
            if (cancelTimeout) cancelWatchdog();
            if (observer != null) observe("fallback:" + reason);
            // The claim is won: whatever the consumer does, the ticket must publish the vanilla supplier.
            try { if (onFallback != null) onFallback.accept(reason); }
            finally { synchronized (owner.lock) { selected = vanilla; owner.drainLocked(); } }
            return true;
        }
        /** Counted once per ticket: a drain met a won claim whose action is not published yet (it used to take vanilla). */
        private void noteUnpublished() {
            if (!deferredNoted) { deferredNoted = true; observe("unpublished-claim-deferred"); }
        }
        private void completeClaimed() {
            if (selected != vanilla) observe("kernel");
            action.complete(selected);
        }
        public boolean claimed() { return claimed.get(); }
    }
}
