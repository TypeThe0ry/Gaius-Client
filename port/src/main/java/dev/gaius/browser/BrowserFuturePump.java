package dev.gaius.browser;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;

/** Keeps Minecraft's queue-backed future waits cooperative in a browser Worker. */
public final class BrowserFuturePump {
    private BrowserFuturePump() {
    }

    /**
     * TeaVM's browser {@code BlockingQueue.poll(timeout)} cannot block a JavaScript Worker.
     * Yield once when the queue is empty so asynchronous future completions and queued work can
     * run before Minecraft checks the completion predicate again.
     */
    public static Object poll(BlockingQueue<?> queue, long timeout, TimeUnit unit) {
        Object task = queue.poll();
        if (task != null) {
            recordPoll(false, queue.size());
            return task;
        }
        recordPoll(true, queue.size());
        TModernRuntimeSupport.yieldToEventLoop(1);
        return queue.poll();
    }

    /**
     * {@code future.join()} for a Worker thread that waits for work it handed to the server
     * thread: vanilla {@code ServerChunkCache.getChunk} called off the server thread runs
     * {@code CompletableFuture.supplyAsync(task, mainThreadProcessor).join()}, which blocks
     * that thread on the JVM until the server thread has run the task. World generation does
     * this, for example when a structure places an entity whose variant spawn condition
     * (StructureCheck) looks up the structures at its position through the ServerLevel.
     * TeaVM's browser CompletableFuture cannot block and throws on an incomplete future, so
     * this thread yields to the event loop until the future is done: the server thread runs
     * the task in one of its turns, as the blocked JVM thread waits for it.
     *
     * <p>A frame that cannot suspend (a class initializer, a method compiled synchronously or
     * a plain JavaScript callback) returns from the yield at once; a macrotask posted before
     * the yield tells the two cases apart, and the wait then ends at {@code join()}, which
     * reports the incomplete future as before instead of spinning.</p>
     */
    public static <T> T joinFromWorker(CompletableFuture<T> future) {
        while (!future.isDone() && !TModernRuntimeSupport.inClassInitializer() && HostLoop.canSuspend()) {
            boolean[] yielded = new boolean[1];
            TModernRuntimeSupport.postRunnableMacrotask(() -> yielded[0] = true);
            TModernRuntimeSupport.yieldToEventLoop(0);
            if (!yielded[0]) {
                break;
            }
        }
        return future.join();
    }

    @JSBody(params = {"empty", "queueDepth"}, script = """
            try {
              const state = globalThis.__gaiusFuturePumpTelemetry ||
                (globalThis.__gaiusFuturePumpTelemetry = {
                  polls: 0,
                  tasks: 0,
                  emptyYields: 0,
                  lastQueueDepth: 0,
                  lastYieldAt: 0,
                  lastReportAt: 0
                });
              state.polls++;
              state.lastQueueDepth = Number(queueDepth) || 0;
              if (empty) {
                state.emptyYields++;
                state.lastYieldAt = Date.now();
                if (typeof WorkerGlobalScope !== 'undefined' &&
                    globalThis instanceof WorkerGlobalScope &&
                    state.lastYieldAt - state.lastReportAt >= 1000) {
                  state.lastReportAt = state.lastYieldAt;
                  postMessage({
                    type: 'server-startup-progress',
                    detail: 'future-pump-waiting polls=' + state.polls +
                      ' tasks=' + state.tasks +
                      ' emptyYields=' + state.emptyYields +
                      ' queue=' + state.lastQueueDepth,
                    at: state.lastYieldAt
                  });
                }
              } else {
                state.tasks++;
              }
            } catch (ignored) {}
            """)
    private static native void recordPoll(boolean empty, int queueDepth);
}
