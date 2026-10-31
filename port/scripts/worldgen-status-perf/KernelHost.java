package dev.gaius.wgperf;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

public final class KernelHost {
    public static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "worldgen-rust-host");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<Integer, LongAdder> JOBS = new ConcurrentHashMap<>();
    private static final LongAdder NANOS = new LongAdder();
    private static final LongAdder ERRORS = new LongAdder();
    private static final Map<Integer, LongAdder> DECLINES = new ConcurrentHashMap<>();
    private static final LongAdder BYTES = new LongAdder();
    private static final Arena LIBRARY = Arena.ofShared();
    private static final Map<Integer, MethodHandle> RUN = new TreeMap<>();
    private static MethodHandle allocate;
    private static MethodHandle deallocate;
    private static MethodHandle release;
    private static Process process;
    private static OutputStream input;
    private static DataInputStream output;

    private static MethodHandle function(SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
        return Linker.nativeLinker().downcallHandle(symbols.find(name).orElseThrow(), descriptor);
    }

    private static void initialize() throws Exception {
        if (allocate != null || process != null) return;
        String backend = System.getProperty("gaius.perf.kernels", "off");
        String artifact = System.getProperty("gaius.perf.artifact");
        if (backend.equals("native")) {
            SymbolLookup symbols = SymbolLookup.libraryLookup(Path.of(artifact), LIBRARY);
            allocate = function(symbols, "alloc", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            deallocate = function(symbols, "dealloc", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            release = function(symbols, "release", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            String[] names = {"run_load_generator", "run_biomes", "run_terrain", "run_surface", "run_ore"};
            for (int index = 0; index < names.length; index++) {
                if (symbols.find(names[index]).isPresent()) RUN.put(0x0401 + index, function(symbols, names[index],
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)));
            }
        } else {
            process = new ProcessBuilder("node", System.getProperty("gaius.perf.bridge"), artifact)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            Process bridge = process;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                bridge.destroy();
                try {
                    if (!bridge.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) bridge.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    bridge.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }, "worldgen-wasm-shutdown"));
            input = process.getOutputStream();
            output = new DataInputStream(process.getInputStream());
        }
    }

    public static synchronized byte[] run(int kind, byte[] job) {
        StatusProfiler.Frame frame = StatusProfiler.enter(switch (kind) {
            case 0x0402 -> "biomes";
            case 0x0403 -> "noise";
            case 0x0404 -> "surface";
            case 0x0405 -> "features_ore";
            default -> "kernel_setup";
        }, null);
        long start = System.nanoTime();
        try {
            initialize();
            int status;
            byte[] bytes;
            if (allocate != null) {
                MemorySegment allocation = (MemorySegment) allocate.invokeExact(job.length);
                if (allocation.address() == 0) throw new IllegalStateException("Rust allocation failed");
                MemorySegment descriptor = MemorySegment.NULL;
                try {
                    allocation.reinterpret(job.length).copyFrom(MemorySegment.ofArray(job));
                    MethodHandle function = RUN.get(kind);
                    if (function == null) throw new IllegalStateException("Unsupported kind " + kind);
                    descriptor = (MemorySegment) function.invokeExact(allocation, job.length);
                    if (descriptor.address() == 0) throw new IllegalStateException("Null Rust descriptor");
                    ByteBuffer header = descriptor.reinterpret(16).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
                    status = header.getInt(0);
                    int length = header.getInt(8);
                    if (length < 0 || length > 16 * 1024 * 1024) throw new IllegalStateException("Invalid result length");
                    bytes = descriptor.reinterpret(16L + length).asSlice(16, length).toArray(ValueLayout.JAVA_BYTE);
                } finally {
                    if (descriptor.address() != 0) release.invokeExact(descriptor);
                    deallocate.invokeExact(allocation, job.length);
                }
            } else {
                input.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(job.length).array());
                input.write(job);
                input.flush();
                byte[] header = output.readNBytes(8);
                if (header.length != 8) throw new IllegalStateException("Wasm host closed");
                ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                status = buffer.getInt();
                int length = buffer.getInt();
                if (length < 0 || length > 16 * 1024 * 1024) throw new IllegalStateException("Invalid result length");
                bytes = output.readNBytes(length);
                if (bytes.length != length) throw new IllegalStateException("Truncated wasm result");
            }
            JOBS.computeIfAbsent(kind, ignored -> new LongAdder()).increment();
            BYTES.add(bytes.length);
            if (status != 0) {
                String message = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (message.startsWith("worldgen-fallback:")) {
                    DECLINES.computeIfAbsent(kind, ignored -> new LongAdder()).increment();
                    throw new IllegalStateException("transient:" + message);
                }
                ERRORS.increment();
                throw new IllegalStateException("Rust status " + status + ": "
                        + message);
            }
            return bytes;
        } catch (Throwable error) {
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Kernel host failure", error);
        } finally {
            NANOS.add(System.nanoTime() - start);
            StatusProfiler.exit(frame);
        }
    }

    public static synchronized void resetMeasurement() {
        JOBS.clear();
        NANOS.reset();
        ERRORS.reset();
        DECLINES.clear();
        BYTES.reset();
    }

    public static Map<String, Object> report() {
        Map<String, Long> jobs = new TreeMap<>();
        JOBS.forEach((kind, count) -> jobs.put(Integer.toHexString(kind), count.sum()));
        Map<String, Long> declines = new TreeMap<>();
        DECLINES.forEach((kind, count) -> declines.put(Integer.toHexString(kind), count.sum()));
        return Map.of("jobs", jobs, "declines", declines, "hostWallNanos", NANOS.sum(), "errors", ERRORS.sum(), "resultBytes", BYTES.sum());
    }
}
