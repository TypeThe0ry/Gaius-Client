package dev.gaius.browser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import org.teavm.jso.JSBody;

/**
 * Turns the integrated server's browser executor into a cooperative FIFO.
 *
 * <p>TeaVM's browser executor is intentionally synchronous. That is a good fit for tiny
 * continuations, but the executor captured by {@code MinecraftServer} also feeds chunk
 * generation, lighting, and ticket propagation. Keeping that executor inline lets a single
 * server tick recursively drain the whole worldgen graph. This adapter only changes that one
 * executor instance: submissions are drained in bounded browser tasks and never recursively.
 */
public final class BrowserCooperativeExecutor implements Executor {
    private static final int MAX_TASKS_PER_PUMP = 8;
    private static final long WORK_BUDGET_NANOS = 2_000_000L;

    private final Executor delegate;
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private boolean pumpScheduled;
    private boolean running;
    private long enqueuedTasks;
    private long completedTasks;
    private long failedTasks;
    private long longestTaskNanos;
    private long overBudgetTasks;

    private BrowserCooperativeExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    /**
     * Wraps the foreground executor, but never the integrated-server Worker executor.
     *
     * <p>The Worker already has TeaVM's continuation scheduler. Adding a second queue around
     * the server executor makes a worldgen task look completed to the outer
     * {@code ConsecutiveExecutor} before its async continuation is resumed, which can leave the
     * spawn {@code CompletableFuture} pending forever. The resource-reload scheduler follows the
     * same Worker bypass rule. Keep the bounded pump for foreground callers only.
     */
    public static Executor defer(Executor delegate) {
        if (isWorkerRuntime()) {
            return delegate;
        }
        return new BrowserCooperativeExecutor(delegate);
    }

    @JSBody(script = "return typeof WorkerGlobalScope !== 'undefined' && globalThis instanceof WorkerGlobalScope;")
    private static native boolean isWorkerRuntime();

    @Override
    public void execute(Runnable command) {
        if (command == null) {
            return;
        }
        queue.addLast(command);
        enqueuedTasks++;
        schedulePump();
    }

    private void schedulePump() {
        if (pumpScheduled) {
            return;
        }
        pumpScheduled = true;
        // Keep the zero-delay browser yield, but enter the callback through TeaVM's native-
        // thread bootstrap. Platform.schedule invokes PlatformRunnable from a raw timer
        // callback; a queued task such as HttpUtil.downloadFile can suspend while fetching a
        // resource pack, and that raw callback has no current TeaVM thread to suspend. The
        // startThread path uses the same deferred browser turn while installing the continuation
        // context required by suspendable CompletableFuture tasks.
        HostLoop.startThread(this::runAfterYield);
    }

    private void runAfterYield() {
        long startedAt = System.nanoTime();
        int completed = 0;
        try {
            while (completed < MAX_TASKS_PER_PUMP
                    && (completed == 0 || System.nanoTime() - startedAt < WORK_BUDGET_NANOS)) {
                Runnable command = queue.pollFirst();
                if (command == null) {
                    return;
                }
                long taskStartedAt = System.nanoTime();
                running = true;
                try {
                    // Preserve the vanilla TracingExecutor/ExecutorService boundary. In the
                    // browser its delegate is synchronous, so this call executes the task on
                    // this bounded pump rather than creating another nested browser turn.
                    if (delegate == null) {
                        command.run();
                    } else {
                        delegate.execute(command);
                    }
                } catch (Throwable ignored) {
                    // CompletableFuture task wrappers normally capture failures themselves.
                    // Keep the browser pump alive if a raw executor task escapes that wrapper.
                    failedTasks++;
                } finally {
                    running = false;
                    long taskNanos = Math.max(0L, System.nanoTime() - taskStartedAt);
                    longestTaskNanos = Math.max(longestTaskNanos, taskNanos);
                    if (taskNanos > WORK_BUDGET_NANOS) {
                        overBudgetTasks++;
                    }
                    completedTasks++;
                }
                completed++;
            }
        } finally {
            pumpScheduled = false;
            if (!queue.isEmpty()) {
                schedulePump();
            }
        }
    }

    /** Exposes bounded executor state for browser smoke tests without coupling to renderer stats. */
    public int pendingTasks() {
        return queue.size() + (running ? 1 : 0);
    }

    public long enqueuedTasks() {
        return enqueuedTasks;
    }

    public long completedTasks() {
        return completedTasks;
    }

    public long failedTasks() {
        return failedTasks;
    }

    public long longestTaskNanos() {
        return longestTaskNanos;
    }

    public long overBudgetTasks() {
        return overBudgetTasks;
    }
}
