package dev.gaius.browser;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Small adapters for recent JDK methods that TeaVM 0.15 does not expose yet. */
public final class BrowserJdkCompat {
    private BrowserJdkCompat() {
    }

    public static ByteBuffer slice(ByteBuffer source, int index, int length) {
        if (index < 0 || length < 0 || index > source.capacity() - length) {
            throw new IndexOutOfBoundsException();
        }
        ByteBuffer view = source.duplicate();
        view.position(index);
        view.limit(index + length);
        return view.slice().order(source.order());
    }

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
