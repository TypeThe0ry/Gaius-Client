package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Safe resolution of names supplied by ZIP/JAR archives. */
public final class ArchivePaths {
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:");

    private ArchivePaths() {
    }

    /** Returns the absolute, normalized path of {@code entryName} below {@code root}, or throws. */
    public static Path resolveEntry(Path root, String entryName) throws IOException {
        if (entryName == null || entryName.isEmpty()) {
            throw rejected(entryName);
        }
        if (entryName.startsWith("/") || entryName.startsWith("\\")
                || DRIVE_PREFIX.matcher(entryName).find()) {
            throw rejected(entryName);
        }
        for (String segment : entryName.split("[/\\\\]", -1)) {
            if (segment.equals("..")) {
                throw rejected(entryName);
            }
        }
        try {
            Path base = root.toAbsolutePath().normalize();
            Path resolved = base.resolve(entryName).normalize();
            if (!resolved.startsWith(base) || resolved.equals(base)) {
                throw rejected(entryName);
            }
            // Return the checked path itself (absolute): every caller only reads or writes the file.
            return resolved;
        } catch (InvalidPathException error) {
            throw rejected(entryName);
        }
    }

    private static IOException rejected(String entryName) {
        return new IOException("archive entry escapes output root: " + String.valueOf(entryName));
    }
}
