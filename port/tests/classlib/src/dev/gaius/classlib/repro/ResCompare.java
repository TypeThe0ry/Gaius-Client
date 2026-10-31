package dev.gaius.classlib.repro;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Class-path resource reads (patchWasmGcResourceRead and the ResourceInputStream.read bounds
 * check of TeaVMCoreBrowserPatcher) against the JVM. A null buffer is the trap section
 * {@code trap.resource-read-null}.
 */
public final class ResCompare {
    private ResCompare() {
    }

    static InputStream open() {
        return ResCompare.class.getClassLoader().getResourceAsStream(ResTest.RESOURCE);
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void run() throws IOException {
        // Chunked reads at increasing offsets (the patched case).
        byte[] buffer = new byte[310];
        int total = 0;
        try (InputStream in = open()) {
            while (total < buffer.length) {
                int n = in.read(buffer, total, Math.min(97, buffer.length - total));
                if (n < 0) {
                    break;
                }
                total += n;
            }
            System.out.println("chunked total=" + total);
            int bad = -1;
            for (int i = 0; i < total; i++) {
                if ((buffer[i] & 0xFF) != i % 251) {
                    bad = i;
                    break;
                }
            }
            System.out.println("chunked firstBad=" + bad + " tail=" + buffer[300] + "," + buffer[309]);
            System.out.println("eof read(b,5,0)=" + in.read(buffer, 5, 0));
            System.out.println("eof read(b,5,4)=" + in.read(buffer, 5, 4));
        }
        try (InputStream in = open()) {
            byte[] small = new byte[8];
            System.out.println("read(b,0,0)=" + in.read(small, 0, 0));
            System.out.println("read(b,6,2)=" + in.read(small, 6, 2) + " -> " + small[6] + "," + small[7]
                    + " untouched=" + small[0]);
            String r;
            try {
                r = String.valueOf(in.read(small, 6, 4));
            } catch (Throwable e) {
                r = "throws " + e.getClass().getSimpleName();
            }
            System.out.println("read(b,6,4) overflow=" + r);
            System.out.println("single read=" + in.read());
            for (int[] range : new int[][] {{-1, 1}, {0, -1}, {8, 1}, {9, 0}, {8, 0}, {Integer.MAX_VALUE, 2}}) {
                try {
                    r = String.valueOf(in.read(small, range[0], range[1]));
                } catch (Throwable e) {
                    r = "throws " + e.getClass().getSimpleName();
                }
                System.out.println("read(b," + range[0] + "," + range[1] + ")=" + r);
            }
            System.out.println("after range checks single read=" + in.read());
            System.out.println("available after 3 reads=" + in.available());
            System.out.println("skip(10)=" + in.skip(10) + " then read=" + in.read());
            byte[] rest = in.readAllBytes();
            System.out.println("readAllBytes length=" + rest.length + " first=" + (rest.length > 0 ? rest[0] : -1));
        }
        System.out.println("missing resource null=" + (ResCompare.class.getClassLoader()
                .getResourceAsStream("dev/gaius/classlib/repro/absent.bin") == null));
    }
}
