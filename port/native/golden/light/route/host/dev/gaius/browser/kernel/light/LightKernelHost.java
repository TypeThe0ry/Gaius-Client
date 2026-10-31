package dev.gaius.browser.kernel.light;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * JVM stand-in for the production {@code LightKernelHost} (same package, name and public API)
 * used only by the light route harness ({@code port/native/golden/light/route}): it lets the
 * unchanged {@code BrowserLightKernel} run on a JVM against the patched vanilla
 * {@code ThreadedLevelLightEngine}.
 *
 * <p>Jobs go to a node process ({@code kernel-bridge.mjs}) that runs the shipped
 * {@code light.<variant>.wasm} module of a dist exactly as the kernel worker does. The protocol
 * of the production host is kept: a job is sent without its light table, and only a "table
 * missing" answer is resent once with the table spliced in after the payload header (the
 * {@code withTable} script of the production host). Results and failures reach the callbacks on
 * a host thread, never inside {@link #submit}.
 *
 * <p>TeaVM runs one Java thread at a time, and {@code BrowserLightKernel} relies on that for its
 * job bookkeeping (slots, waiting queue, in-flight columns), which its result threads share with
 * the light engine's task queue. A JVM runs them in parallel, so the harness serializes them
 * with {@link #TURN}: the engine executor of the harness takes it around every task, and a
 * callback is delivered holding it until the {@code gaius-light-kernel} thread that the callback
 * starts has ended.
 *
 * <p>System properties: {@code gaius.lightRoute.kernel} ({@code on} or {@code off}),
 * {@code gaius.lightRoute.bridge} (the bridge command, arguments separated by {@code |}),
 * {@code gaius.lightRoute.maxInFlight}, {@code gaius.lightRoute.fail} ({@code none},
 * {@code every:<n>:<code>} fails every n-th job, {@code all:<code>} every job, before it reaches
 * the kernel) and {@code gaius.lightRoute.stats} (a file the counters are written to).
 */
public final class LightKernelHost {
    /** One Java thread at a time between the light engine and the kernel's result threads. */
    public static final Semaphore TURN = new Semaphore(1);
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "light-route-host");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicInteger SUBMITTED = new AtomicInteger();
    private static final AtomicInteger TABLE_RESENDS = new AtomicInteger();
    private static final AtomicInteger INJECTED = new AtomicInteger();
    private static final AtomicInteger RESULTS = new AtomicInteger();
    private static final List<String> LOG = new ArrayList<>();
    private static Process bridge;
    private static OutputStream toBridge;
    private static DataInputStream fromBridge;

    private LightKernelHost() {
    }

    @JSFunctor
    public interface ResultCallback extends JSObject {
        void accept(JSObject result);
    }

    @JSFunctor
    public interface FailureCallback extends JSObject {
        void accept(String failure);
    }

    /** A result buffer as the callbacks receive it. */
    private record Buffer(byte[] bytes) implements JSObject {
    }

    public static boolean available() {
        return "on".equals(System.getProperty("gaius.lightRoute.kernel", "off"));
    }

    public static int maxInFlight() {
        int value = Integer.getInteger("gaius.lightRoute.maxInFlight", 4);
        return value < 1 ? 1 : Math.min(value, 32);
    }

    public static int timeoutMillis() {
        return 15000;
    }

    public static boolean submit(byte[] bytes, int length, byte[] table, int chunkX, int chunkZ, int jobId,
            ResultCallback done, FailureCallback fail) {
        if (!available()) {
            return false;
        }
        byte[] lean = Arrays.copyOf(bytes, length);
        int ordinal = SUBMITTED.incrementAndGet();
        String injected = injectedFailure(ordinal);
        WORKER.execute(() -> {
            if (Boolean.getBoolean("gaius.lightRoute.outOfOrder")) {
                try {
                    Thread.sleep(ordinal == 1 ? 150 : 1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (injected != null) {
                INJECTED.incrementAndGet();
                deliver(() -> fail.accept(injected + ":injected by the light route harness (job " + ordinal + ")"));
                return;
            }
            byte[] result;
            try {
                result = run(lean);
                if (tableMissing(result)) {
                    if (table == null || table.length == 0) {
                        deliver(() -> fail.accept("kernel-error:light_column kernel lacks the light table"));
                        return;
                    }
                    TABLE_RESENDS.incrementAndGet();
                    result = run(withTable(lean, table));
                    if (tableMissing(result)) {
                        deliver(() -> fail.accept("kernel-error:light_column kernel lacks the light table"));
                        return;
                    }
                }
            } catch (IOException | RuntimeException error) {
                deliver(() -> fail.accept("kernel-error:" + error));
                return;
            }
            RESULTS.incrementAndGet();
            Buffer buffer = new Buffer(result);
            deliver(() -> done.accept(buffer));
        });
        return true;
    }

    public static int resultLength(JSObject result) {
        return result instanceof Buffer buffer ? buffer.bytes.length : 0;
    }

    public static void copyResult(JSObject result, byte[] out) {
        System.arraycopy(((Buffer) result).bytes, 0, out, 0, out.length);
    }

    /** The production classification. */
    public static boolean isTransient(String failure) {
        if (failure == null) {
            return false;
        }
        return failure.startsWith("superseded:") || failure.startsWith("stale:") || failure.startsWith("backpressure:")
                || failure.startsWith("request-cap:")
                || failure.startsWith("runtime-disabled:") || failure.startsWith("runtime-unavailable:")
                || failure.startsWith("terminated:") || failure.startsWith("aborted:")
                || failure.startsWith("cancelled:") || failure.startsWith("no-host:")
                || failure.startsWith("timeout:");
    }

    public static void log(String message) {
        synchronized (LOG) {
            LOG.add(message);
        }
        System.out.println(message);
    }

    /** Writes the host counters as JSON to {@code gaius.lightRoute.stats} and stops the bridge. */
    public static synchronized void finish() throws IOException {
        String path = System.getProperty("gaius.lightRoute.stats");
        if (path != null) {
            StringBuilder messages = new StringBuilder();
            synchronized (LOG) {
                for (String message : LOG) {
                    messages.append(messages.length() == 0 ? "" : ",").append('"')
                            .append(message.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                }
            }
            Files.writeString(Path.of(path), "{\"submitted\":" + SUBMITTED.get() + ",\"results\":" + RESULTS.get()
                    + ",\"tableResends\":" + TABLE_RESENDS.get() + ",\"injectedFailures\":" + INJECTED.get()
                    + ",\"log\":[" + messages + "]}\n");
        }
        if (bridge != null) {
            toBridge.close();
            try {
                bridge.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            bridge = null;
        }
    }

    /**
     * Runs a callback holding {@link #TURN} and keeps it until the result threads the callback
     * started ({@code BrowserLightKernel} hands each result to a new {@code gaius-light-kernel}
     * thread) have ended.
     */
    private static void deliver(Runnable callback) {
        TURN.acquireUninterruptibly();
        try {
            Set<Thread> before = kernelThreads();
            callback.run();
            for (Thread thread : kernelThreads()) {
                if (!before.contains(thread)) {
                    thread.join();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            TURN.release();
        }
    }

    private static Set<Thread> kernelThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("gaius-light-kernel")).collect(Collectors.toSet());
    }

    private static String injectedFailure(int ordinal) {
        String spec = System.getProperty("gaius.lightRoute.fail", "none");
        if (spec.equals("none")) {
            return null;
        }
        String[] parts = spec.split(":");
        if (parts[0].equals("all") && parts.length == 2) {
            return parts[1];
        }
        if (parts[0].equals("every") && parts.length == 3) {
            return ordinal % Integer.parseInt(parts[1]) == 0 ? parts[2] : null;
        }
        throw new IllegalArgumentException("gaius.lightRoute.fail: " + spec);
    }

    /** One framed job through the bridge: u32 length + bytes out, u32 status + u32 length + bytes in. */
    private static synchronized byte[] run(byte[] job) throws IOException {
        if (bridge == null) {
            String command = System.getProperty("gaius.lightRoute.bridge");
            if (command == null) {
                throw new IOException("gaius.lightRoute.bridge is not set");
            }
            bridge = new ProcessBuilder(command.split("\\|")).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            toBridge = bridge.getOutputStream();
            fromBridge = new DataInputStream(bridge.getInputStream());
        }
        byte[] header = new byte[4];
        putInt(header, 0, job.length);
        toBridge.write(header);
        toBridge.write(job);
        toBridge.flush();
        int status = Integer.reverseBytes(fromBridge.readInt());
        int length = Integer.reverseBytes(fromBridge.readInt());
        byte[] result = new byte[length];
        fromBridge.readFully(result);
        if (status != 0) {
            throw new IOException("light kernel status " + status + ": " + new String(result));
        }
        return result;
    }

    /** The "table missing" answer: result version 2 with payload byte 1 set. */
    private static boolean tableMissing(byte[] result) {
        return result.length >= 18 && getInt(result, 0) == 0x53524b47 && (result[6] | result[7]) == 0
                && result[16] == 2 && result[17] == 1;
    }

    /** The production splice: the table right after the 64-byte headers, padded to 8 bytes. */
    private static byte[] withTable(byte[] lean, byte[] table) {
        int count = table.length;
        int inserted = (count + 7) & ~7;
        byte[] out = new byte[lean.length + inserted];
        System.arraycopy(lean, 0, out, 0, 64);
        System.arraycopy(table, 0, out, 64, count);
        System.arraycopy(lean, 64, out, 64 + inserted, lean.length - 64);
        putInt(out, 12, getInt(out, 12) + inserted);
        putInt(out, 44, count);
        return out;
    }

    private static int getInt(byte[] bytes, int at) {
        return (bytes[at] & 0xff) | (bytes[at + 1] & 0xff) << 8 | (bytes[at + 2] & 0xff) << 16
                | (bytes[at + 3] & 0xff) << 24;
    }

    private static void putInt(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
        bytes[at + 2] = (byte) (value >>> 16);
        bytes[at + 3] = (byte) (value >>> 24);
    }
}
