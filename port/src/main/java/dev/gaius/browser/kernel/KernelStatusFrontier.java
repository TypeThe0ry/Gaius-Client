package dev.gaius.browser.kernel;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
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
    public Ticket<T> register(Supplier<CompletableFuture<T>> vanilla, long timeoutMs, Runnable onTimeout) {
        return register(vanilla, timeoutMs, reason -> { if ("timeout".equals(reason) && onTimeout != null) onTimeout.run(); });
    }
    public Ticket<T> register(Supplier<CompletableFuture<T>> vanilla, long timeoutMs, Consumer<String> onFallback) {
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
        try { while (!pending.isEmpty()) { Ticket<T> ticket = pending.peekFirst(); if (!ticket.claimed.get()) break; pending.removeFirst(); ticket.completeClaimed(); } }
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
        private Ticket(KernelStatusFrontier<T> owner, Supplier<CompletableFuture<T>> vanilla, long timeoutMs, Consumer<String> onFallback) {
            this.owner = owner; this.vanilla = vanilla; this.timeoutMs = timeoutMs; this.onFallback = onFallback;
            this.result = action.thenCompose(Supplier::get);
        }
        private void startWatchdog() { if (timeoutMs > 0) WATCHDOG.schedule(() -> fallback("timeout"), timeoutMs, TimeUnit.MILLISECONDS); }
        /** Called by the vanilla status task at its deterministic execution point. */
        public void consumeAtTask() { owner.consume(this); }
        public CompletableFuture<T> future() { return result; }
        /** Publishes a prefetched install action without advancing status completion. */
        public boolean prefetch(Supplier<CompletableFuture<T>> install) {
            if (!claimed.compareAndSet(false, true)) return false;
            synchronized (owner.lock) { selected = install; owner.drainLocked(); } return true;
        }
        /** Compatibility name for JVM golden seams; identical prefetch-only semantics. */
        public boolean claimKernel(Supplier<CompletableFuture<T>> install) { return prefetch(install); }
        public boolean fallback(String reason) {
            if (!claimed.compareAndSet(false, true)) return false;
            if (onFallback != null) onFallback.accept(reason);
            synchronized (owner.lock) { selected = vanilla; owner.drainLocked(); } return true;
        }
        private void completeClaimed() { action.complete(selected == null ? vanilla : selected); }
        public boolean claimed() { return claimed.get(); }
    }
}
