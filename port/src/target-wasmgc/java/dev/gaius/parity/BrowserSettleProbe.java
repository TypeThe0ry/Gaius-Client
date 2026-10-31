package dev.gaius.parity;

import java.util.function.Consumer;

/**
 * The WasmGC Worker's settle probe: the {@link TickParitySerialExecutor} background schedule, the
 * {@link TickParityDirectExecutor} IO gauge, and the asynchronous work of the Worker host that
 * vanilla's executors cannot see.
 *
 * <p>Kernel jobs: the Rust worldgen and light kernels answer through the page's kernel runtime and
 * install their results from new Java threads in the order the kernel workers finish, so a capture
 * with them would not have one generation order. A capture therefore runs with the kernels off (the
 * capture page posts no kernel port and switches both kernels off), every chunk takes vanilla's Java
 * path in the place vanilla runs it, and this probe fails closed when a kernel runtime is present,
 * when the kernel job count moves, and counts kernel jobs in flight as work that keeps a settle open.
 *
 * <p>The worldgen dispatcher's deferred registration (BrowserWorldgenDispatcherScheduler.defer) hops
 * through a new host activation that no executor sees; a Worker that requested a capture keeps
 * vanilla's dispatcher name instead (BrowserTickParityExecutors.consecutiveName), so the dispatcher
 * re-registers inline. A pump hop during the capture means that hook is missing, and fails it.
 */
public final class BrowserSettleProbe implements TickParitySettleProbe {
    /** Timed waits re-read host counters that do not notify a Java monitor. */
    private static final long WAIT_MILLIS = 5L;

    /** The Worker host's asynchronous work outside vanilla's executors. */
    public interface Host {
        /** Any kernel runtime, client, facade or mirror is installed in this Worker. */
        boolean kernelsPresent();

        /** Kernel jobs submitted and not answered yet. */
        int kernelJobsInFlight();

        /** Kernel jobs ever submitted from this Worker (Java and host counters). */
        long kernelJobsSubmitted();

        /** New worldgen dispatcher pump activations ever started (BrowserWorldgenDispatcherScheduler). */
        long dispatcherPumps();

        /**
         * Activations and zero-delay events queued in the Worker's JSPI run queue and not yet run (for
         * example the continuation of a bounded chunk-generation layer). A timer that wakes the
         * settling thread can fire between two slices of that queue, so they are pending work that
         * no executor shows.
         */
        long runQueuePending();
    }

    private final TickParitySerialExecutor background;
    private final TickParityDirectExecutor.Gauge io;
    private final Host host;
    private final Consumer<String> report;
    private boolean started;
    private long kernelBaseline;
    private long pumpBaseline;
    private long ioBaseline;
    private long waits;

    public BrowserSettleProbe(TickParitySerialExecutor background, TickParityDirectExecutor.Gauge io, Host host,
            Consumer<String> report) {
        this.background = background;
        this.io = io;
        this.host = host;
        this.report = report;
    }

    /** The capture starts: the background executor turns FIFO and the host baselines are taken. */
    public void begin() {
        if (started) throw new IllegalStateException("tick parity probe already started");
        if (host.kernelsPresent()) {
            throw new IllegalStateException("kernel runtime present in the Worker; a tick parity capture needs the kernels off");
        }
        kernelBaseline = host.kernelJobsSubmitted();
        pumpBaseline = host.dispatcherPumps();
        ioBaseline = io.tasks();
        background.beginCapture();
        started = true;
        report.accept("{\"phase\":\"started\"," + state() + "}");
    }

    private void check() {
        if (!started) throw new IllegalStateException("tick parity settle before the capture started");
        if (background.broken()) {
            throw new IllegalStateException("background schedule broken; capture refused: " + background.describe());
        }
        if (host.kernelsPresent()) throw new IllegalStateException("kernel runtime appeared during the capture; capture refused");
        long submitted = host.kernelJobsSubmitted();
        if (submitted != kernelBaseline) {
            throw new IllegalStateException("kernel job submitted during the capture (" + (submitted - kernelBaseline)
                    + "); capture refused");
        }
        long pumps = host.dispatcherPumps();
        if (pumps != pumpBaseline) {
            throw new IllegalStateException("worldgen dispatcher hopped through " + (pumps - pumpBaseline)
                    + " host activation(s) during the capture; the parity schedule hook is missing");
        }
    }

    @Override
    public boolean idle() {
        check();
        return background.idle() && io.idle() && host.kernelJobsInFlight() == 0 && host.runQueuePending() == 0;
    }

    @Override
    public boolean ioIdle() {
        check();
        return io.idle();
    }

    @Override
    public Runnable holdBackground() {
        check();
        return background.hold();
    }

    /** Blocks on the executor's monitor: the background thread runs only while this one waits. */
    @Override
    public void awaitIdle(long deadlineNanos) {
        while (!idle()) {
            expire(deadlineNanos);
            waits++;
            if (!background.idle()) background.awaitChange(WAIT_MILLIS);
            else io.awaitChange(WAIT_MILLIS);
        }
    }

    @Override
    public void awaitIoIdle(long deadlineNanos) {
        while (!ioIdle()) {
            expire(deadlineNanos);
            waits++;
            io.awaitChange(WAIT_MILLIS);
        }
    }

    private void expire(long deadlineNanos) {
        if (System.nanoTime() - deadlineNanos > 0) {
            throw new IllegalStateException("tick parity settle timed out; capture refused: " + this);
        }
    }

    /** Reports the schedule's counters and returns new tasks to their submitting threads. */
    @Override
    public void captureEnding(boolean complete) {
        if (!started) return;
        String text = "{\"phase\":\"" + (complete ? "complete" : "failed") + "\"," + state() + "}";
        background.endCapture();
        report.accept(text);
    }

    private String state() {
        return "\"executor\":\"serial-fifo\"," + background.statistics() + ",\"ioTasks\":" + (io.tasks() - ioBaseline)
                + ",\"ioIdle\":" + io.idle() + ",\"waits\":" + waits + ",\"kernels\":{\"present\":" + host.kernelsPresent()
                + ",\"inFlight\":" + host.kernelJobsInFlight() + ",\"submitted\":" + (host.kernelJobsSubmitted() - kernelBaseline)
                + "},\"dispatcherPumps\":" + (host.dispatcherPumps() - pumpBaseline)
                + ",\"runQueuePending\":" + host.runQueuePending();
    }

    @Override
    public String toString() {
        return background.describe() + " ioIdle=" + io.idle() + " kernelJobsInFlight=" + host.kernelJobsInFlight()
                + " runQueuePending=" + host.runQueuePending() + " waits=" + waits;
    }
}
