package dev.gaius.classlib.repro;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * TeaVM 0.15 WebAssembly GC code generation bugs seen in the 26.3 client, fixed by
 * TeaVMCoreBrowserPatcher: a catch of a class that dependency analysis never reaches (missing
 * from the class path, or never instantiated) produced an invalid br_on_cast
 * (wasmCatchBranch), and a value method whose every path returns or throws inside a block
 * failed validation at the block end (wasmSealFunctionBody). A regression makes the whole
 * module invalid, so every WebAssembly section of the suite fails, not only this one.
 *
 * <p>The runner deletes the class file of {@code Missing} before TeaVM compiles the suite, as
 * UnsatisfiedLinkError is missing in the client build.
 */
public final class WasmBugs {
    static int calls;

    private WasmBugs() {
    }

    // BrowserCrypto.report: catch of a class nothing instantiates.
    static void report(String event) {
        try {
            calls += event.length();
        } catch (UnsatisfiedLinkError ignored) {
            calls--;
        }
    }

    static InputStream open(String name) {
        return new ByteArrayInputStream(name.getBytes());
    }

    // BitmapProvider$Definition.load: a value method whose every path returns or throws
    // inside try-with-resources.
    static String load(String name) throws IOException {
        try (InputStream in = open(name)) {
            int first = in.read();
            if (first < 0) {
                throw new IOException("empty");
            }
            StringBuilder out = new StringBuilder();
            try {
                out.append((char) first);
                return out.toString();
            } catch (Throwable t) {
                out.setLength(0);
                throw t;
            }
        }
    }

    static class NeverThrown extends RuntimeException {
    }

    /** Deleted from the class files before TeaVM runs (as UnsatisfiedLinkError is missing). */
    static class Missing extends RuntimeException {
    }

    static void report3(String event) {
        try {
            calls += event.length();
        } catch (Missing ignored) {
            calls--;
        }
    }

    static Object lastError;

    static void report5(String event) {
        try {
            calls += event.length();
        } catch (Missing e) {
            throw new IllegalStateException("wrapped " + event, e);
        }
    }

    static void report4(String event) {
        Object holder = event;
        try {
            calls += event.length();
        } catch (Missing e) {
            holder = e;
        }
        lastError = holder;
    }

    static void report2(String event) {
        try {
            calls += event.length();
        } catch (NeverThrown ignored) {
            calls--;
        }
    }

    static void release(Object image) {
        calls += image == null ? 0 : 1;
    }

    // The exact shape of BitmapProvider$Definition.load (Gaius-patched): an outer
    // catch (Throwable) { release; throw } around try-with-resources with a return inside.
    static Object load2(String name) throws IOException {
        Object image = null;
        try {
            try (InputStream in = open(name)) {
                image = new StringBuilder().append(in.read());
                int[] widths = new int[name.length()];
                for (int i = 0; i < widths.length; i++) {
                    if (widths[i] != 0) {
                        System.out.println("dup " + i);
                    }
                }
                return new Object[] {image, widths};
            }
        } catch (Throwable t) {
            release(image);
            throw t;
        }
    }

    static final Object LOCK = new Object();
    static Object cached;

    static Object syncReturn(Object lock, int k) {
        synchronized (lock) {
            if (k > 3) {
                return "big";
            }
            return "small" + k;
        }
    }

    static Object syncLoop(Object lock, int[] work) {
        synchronized (lock) {
            for (int i = 0; i < work.length; i++) {
                if (work[i] < 0) {
                    return "negative " + i;
                }
            }
            return "done";
        }
    }

    static Object doubleChecked() {
        if (cached == null) {
            synchronized (LOCK) {
                if (cached == null) {
                    throw new IllegalStateException("missing");
                }
            }
        }
        return cached;
    }

    public static void main(String[] args) {
        try {
            System.out.println(syncReturn(LOCK, args.length));
            System.out.println(syncReturn(args, 7));
            System.out.println(syncLoop(LOCK, new int[] {1, -2}));
            System.out.println(syncLoop(args, new int[0]));
            cached = args;
            System.out.println(doubleChecked() != null);
            System.out.println(doubleChecked() == args);
            report2("y");
            report3("z");
            report4("w");
            report5("v");
            System.out.println(load2("q") != null);
            report("x");
            System.out.println(load("abc") + calls);
            System.out.println(load(args.length > 0 ? args[0] : "d"));
            System.out.println("lastError=" + lastError);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
