package dev.gaius.browser;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * JDK 13-25 methods that Minecraft 26.3 calls and TeaVM 0.15's class library does not
 * implement: the 26.3 version of the shared port/src/main/java BrowserJdkCompat, which it
 * replaces in the 26.3 source set. {@code JdkCompatPatches263} (the last domain of
 * Minecraft263BrowserPatcher) redirects every call site of the JDK methods in the 26.3 client
 * jar to the JDK-named helpers below; an instance method takes its receiver as the first
 * parameter. Each helper keeps the JDK semantics,
 * including the exceptions and their messages; browser-jdk-compat-smoke.mjs compares them
 * with the real JDK methods on the JVM and runs the same cases compiled by TeaVM. A null
 * receiver throws NullPointerException as the instance call would; the explicit check matters
 * on TeaVM, which otherwise turns a null dereference into a JavaScript TypeError.
 *
 * <p>{@link #readAll}, {@link #firstLine} and {@link #resolve} are the shared helpers that
 * Minecraft262BrowserPatcher calls on 26.2 and 26.3 (TemplateSource, DebugEntrySystemSpecs,
 * Identifier), kept with the shared semantics. The shared {@code slice} keeps the source byte
 * order and checks the range against the capacity; here {@link #slice} follows the JDK
 * (BIG_ENDIAN, range within the limit). Its 26.3 callers, JdkCompatPatches263's three
 * DynamicGpuDataStorageNonMapped sites and Minecraft262BrowserPatcher's
 * StagingBuffer$Cpu.copyTo, all hand the slice to CommandEncoder.writeToBuffer as raw bytes,
 * and the JDK behaviour is what vanilla gets on the JVM. 26.2 compiles the shared file,
 * unchanged.
 */
public final class BrowserJdkCompat {
    /** Chunk size of {@code Reader.readAllCharsAsString} in the JDK. */
    private static final int TRANSFER_BUFFER_SIZE = 8192;

    private BrowserJdkCompat() {
    }

    /**
     * {@code Math.powExact(int, int)} (JDK 25): {@code x} to the power {@code n}. Throws
     * ArithmeticException("negative exponent") for a negative {@code n} and
     * ArithmeticException("integer overflow") when the result does not fit an int. The JDK
     * algorithm: square-and-multiply, where only a square that the result needs is taken, so
     * an overflowing square means an overflowing result.
     */
    public static int powExact(int x, int n) {
        if (n < 0) {
            throw new ArithmeticException("negative exponent");
        }
        if (n == 0) {
            return 1;
        }
        if (x == 0 || x == 1) {
            return x;
        }
        if (x == -1) {
            return (n & 1) == 0 ? 1 : -1;
        }
        int p = 1;
        while (n > 1) {
            if ((n & 1) != 0) {
                p *= x;
            }
            x = multiplyExact(x, x);
            n >>>= 1;
        }
        return multiplyExact(p, x);
    }

    private static int multiplyExact(int x, int y) {
        long product = (long) x * (long) y;
        if ((int) product != product) {
            throw new ArithmeticException("integer overflow");
        }
        return (int) product;
    }

    /**
     * {@code ByteBuffer.slice(int index, int length)} (JDK 13): a buffer sharing
     * {@code length} bytes of {@code buffer}'s content from absolute {@code index}, with
     * position 0, limit and capacity {@code length}, no mark and BIG_ENDIAN byte order (the
     * JDK does not carry the byte order over to a ByteBuffer slice). Direct and read-only
     * like {@code buffer}; the position, limit and mark of both buffers stay independent.
     * Throws IndexOutOfBoundsException unless {@code 0 <= index}, {@code 0 <= length} and
     * {@code index + length <= buffer.limit()}.
     *
     * <p>The slice is a real TeaVM buffer slice (duplicate, window, slice): TeaVM's
     * JavaScript buffer bridge exports a buffer's backing view, so a sub-range upload must be
     * a slice whose backing offset and capacity match the range (see BrowserOpenGL.bytesSlice).
     */
    public static ByteBuffer slice(ByteBuffer buffer, int index, int length) {
        int limit = Objects.requireNonNull(buffer).limit();
        if ((index | length) < 0 || length > limit - index) {
            throw new IndexOutOfBoundsException("Range [" + index + ", " + index + " + " + length
                    + ") out of bounds for length " + limit);
        }
        ByteBuffer window = buffer.duplicate();
        window.clear();
        window.position(index);
        window.limit(index + length);
        return window.slice().order(ByteOrder.BIG_ENDIAN);
    }

    /**
     * {@code Reader.readAllAsString()} (JDK 25): every remaining character up to end of
     * stream, line terminators included; an empty string at end of stream. Does not close the
     * reader; IOExceptions from {@code read} propagate.
     */
    public static String readAllAsString(Reader reader) throws IOException {
        Objects.requireNonNull(reader);
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[TRANSFER_BUFFER_SIZE];
        int read;
        while ((read = reader.read(buffer, 0, buffer.length)) != -1) {
            result.append(buffer, 0, read);
        }
        return result.toString();
    }

    /**
     * {@code Duration.isPositive()} (JDK 18): true when the duration is longer than zero
     * (the JDK computes {@code (seconds | nanos) > 0}).
     */
    public static boolean isPositive(Duration duration) {
        return !Objects.requireNonNull(duration).isNegative() && !duration.isZero();
    }

    // Shared helpers of port/src/main/java/dev/gaius/browser/BrowserJdkCompat.java, called by
    // Minecraft262BrowserPatcher's redirects; same code.

    public static String readAll(Reader reader) throws IOException {
        StringBuilder output = new StringBuilder();
        char[] buffer = new char[8192];
        int count;
        while ((count = reader.read(buffer, 0, buffer.length)) >= 0) {
            if (count > 0) {
                output.append(buffer, 0, count);
            }
        }
        return output.toString();
    }

    public static String firstLine(String value) {
        int newline = value.indexOf('\n');
        int carriageReturn = value.indexOf('\r');
        int end = newline < 0 ? carriageReturn
                : carriageReturn < 0 ? newline : Math.min(newline, carriageReturn);
        return end < 0 ? value : value.substring(0, end);
    }

    public static Path resolve(Path root, String first, String... more) {
        Path resolved = root.resolve(first);
        for (String segment : more) {
            resolved = resolved.resolve(segment);
        }
        return resolved;
    }
}
