package dev.gaius.browser.kernel.light;

import java.util.ArrayDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Orders only public light futures; PRE_UPDATE installs remain independent. */
final class CompletionFrontier {
    private static final long TIMEOUT_MS = 15_000;
    private static final ScheduledExecutorService WATCHDOG = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "gaius-light-frontier-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lock = new Object();
    private final ArrayDeque<Ticket> pending = new ArrayDeque<>();
    private boolean draining;

    Ticket register(Runnable onTimeout) {
        Ticket ticket = new Ticket(this);
        synchronized (lock) {
            pending.addLast(ticket);
            ticket.watchdog = WATCHDOG.schedule(onTimeout, TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        return ticket;
    }

    private void drain() {
        synchronized (lock) {
            if (draining) return;
            draining = true;
        }
        while (true) {
            Runnable action;
            synchronized (lock) {
                Ticket ticket = pending.peekFirst();
                if (ticket == null || !ticket.ready) {
                    draining = false;
                    return;
                }
                pending.removeFirst();
                action = ticket.action;
            }
            action.run();
        }
    }

    static final class Ticket {
        private final CompletionFrontier owner;
        private boolean ready;
        private Runnable action;
        private ScheduledFuture<?> watchdog;

        Ticket(CompletionFrontier owner) { this.owner = owner; }

        void complete(Runnable action) {
            synchronized (owner.lock) {
                if (ready) return;
                if (watchdog != null) watchdog.cancel(false);
                this.action = action;
                ready = true;
            }
            owner.drain();
        }
    }
}
