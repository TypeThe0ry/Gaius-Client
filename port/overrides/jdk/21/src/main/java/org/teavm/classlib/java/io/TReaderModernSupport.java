package org.teavm.classlib.java.io;

import java.io.IOException;

public final class TReaderModernSupport {
    private TReaderModernSupport() {
    }

    public static long transferTo(TBufferedReader reader, TWriter writer) throws IOException {
        char[] buffer = new char[8192];
        long total = 0;
        int count;
        while ((count = reader.read(buffer, 0, buffer.length)) >= 0) {
            writer.write(buffer, 0, count);
            total += count;
        }
        return total;
    }
}
