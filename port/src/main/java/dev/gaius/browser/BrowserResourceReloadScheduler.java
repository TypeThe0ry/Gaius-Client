package dev.gaius.browser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import org.teavm.jso.JSBody;

/**
 * Keeps large resource-pack reloads cooperative with the browser event loop.
 *
 * <p>Minecraft submits many small model and atlas preparation tasks through an executor that
 * becomes synchronous in the browser. Submitting a bounded batch per browser task preserves the
 * original executor's completion behavior while letting input, painting, and network timers run.
 */
public final class BrowserResourceReloadScheduler {
    // Large server packs can otherwise exceed proxy/backend configuration timeouts.
    // Checked between commands; a single command can exceed this slice budget.
    // Resource-pack reloads are a finite foreground burst.  Eleven milliseconds
    // with 384 submissions stretched the 62 MB multiplayer pack across ~54 s;
    // use a 25 ms cooperative slice so the browser still gets timers/input while
    // the atlas/model graph drains in a small number of turns.
    private static final long FRAME_WORK_BUDGET_NANOS = 25_000_000L;
    private static final int MAX_SUBMISSIONS_PER_BATCH = 1024;
    private static final Deque<Runnable> QUEUE = new ArrayDeque<>();
    private static boolean pumpScheduled;

    private BrowserResourceReloadScheduler() {
    }

    public static Executor defer(Executor delegate) {
        // The dedicated server runs in a Worker: there is no canvas to keep
        // paintable, and delaying its datapack preparation delays listener
        // registration and the first local login. Preserve vanilla execution
        // there; frame-budget only the foreground client reload.
        if (isWorkerRuntime()) {
            return delegate;
        }
        return command -> enqueue(() -> delegate.execute(command));
    }

    private static void enqueue(Runnable command) {
        if (command == null) {
            return;
        }
        QUEUE.addLast(command);
        schedulePump();
    }

    private static void schedulePump() {
        if (pumpScheduled) {
            return;
        }
        pumpScheduled = true;
        // Waiting for the next frame callback here capped thousands of tiny model
        // preparation batches at the display refresh rate and dominated startup. A
        // zero-delay browser task still yields to input, networking, and rendering,
        // while allowing the next batch to run as soon as the browser is ready.
        //
        // Enter through a TeaVM thread activation rather than a raw EventQueue callback.
        // Resource preparation commands can complete a CompletableFuture or wait on a
        // browser-backed resource; a raw callback has no JSPI activation and cannot suspend,
        // which leaves the reload barrier pending on browsers that take the asynchronous path.
        HostLoop.startThread(BrowserResourceReloadScheduler::runAfterYield);
    }

    @JSBody(script = "return typeof WorkerGlobalScope !== 'undefined' && globalThis instanceof WorkerGlobalScope;")
    private static native boolean isWorkerRuntime();

    private static void runAfterYield() {
        long startedAt = System.nanoTime();
        int submitted = 0;
        // A preparation command may park this JSPI activation while a later reload task is
        // enqueued. Do not leave the global scheduled bit set across command.run(): enqueue()
        // must be able to start a successor pump, otherwise the parked command can wait on work
        // that this activation is the only one allowed to drain.
        pumpScheduled = false;
        try {
            while (submitted < MAX_SUBMISSIONS_PER_BATCH) {
                Runnable command = QUEUE.pollFirst();
                if (command == null) {
                    return;
                }
                command.run();
                submitted++;
                if (System.nanoTime() - startedAt >= FRAME_WORK_BUDGET_NANOS) {
                    return;
                }
            }
        } finally {
            pumpScheduled = false;
            if (!QUEUE.isEmpty()) {
                schedulePump();
            }
        }
    }
}
