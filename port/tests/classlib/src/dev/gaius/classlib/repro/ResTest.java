package dev.gaius.classlib.repro;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Chunked class-path resource reads. TeaVM 0.15's WasmGCResources.readBytes wrote
 * {@code bytes[i]} instead of {@code bytes[off + i]}, so every read with a non-zero offset
 * overwrote the start of the buffer (ICU: "Not an ICU data file"); patchWasmGcResourceRead.
 */
public final class ResTest {
    public static final String RESOURCE = "dev/gaius/classlib/repro/data.bin";

    private ResTest() {
    }

    public static void main(String[] args) {
        byte[] buffer = new byte[300];
        int total = 0;
        try (InputStream in = ResTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            while (total < buffer.length) {
                int n = in.read(buffer, total, Math.min(100, buffer.length - total));
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int bad = -1;
        for (int i = 0; i < total; i++) {
            if ((buffer[i] & 0xFF) != i % 251) {
                bad = i;
                break;
            }
        }
        System.out.println("read=" + total + " firstBad=" + bad);
    }
}
