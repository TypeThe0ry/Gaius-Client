package dev.gaius.golden;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Writes fixture cases to one {@code <kind>.jsonl} file per kind and keeps per-kind counts. */
final class FixtureSink implements AutoCloseable {
    /** Hard cap per fixture file so the corpus stays reviewable and cheap to load. */
    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private static final class KindFile {
        final BufferedWriter writer;
        int lines;
        long outputs;
        long bytes;

        KindFile(BufferedWriter writer) {
            this.writer = writer;
        }
    }

    private final Path directory;
    private final Map<String, KindFile> files = new TreeMap<>();

    FixtureSink(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
    }

    void write(Case c) {
        try {
            KindFile file = files.get(c.kind);
            if (file == null) {
                file = new KindFile(Files.newBufferedWriter(
                        directory.resolve(c.kind + ".jsonl"), StandardCharsets.UTF_8));
                files.put(c.kind, file);
            }
            String line = c.toJson() + "\n";
            file.writer.write(line);
            file.lines++;
            file.outputs += c.outputs.size();
            file.bytes += line.length();
            if (file.bytes > MAX_FILE_BYTES) {
                throw new IllegalStateException(c.kind + ".jsonl exceeds " + MAX_FILE_BYTES + " bytes");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Prints one row per kind: lines, total outputs and file size. */
    void printCounts(String profile) {
        System.out.printf("%-24s %6s %9s %10s%n", "kind (" + profile + ")", "lines", "outputs", "bytes");
        for (Map.Entry<String, KindFile> entry : files.entrySet()) {
            KindFile f = entry.getValue();
            System.out.printf("%-24s %6d %9d %10d%n", entry.getKey(), f.lines, f.outputs, f.bytes);
        }
    }

    @Override
    public void close() throws IOException {
        for (KindFile f : files.values()) {
            f.writer.close();
        }
    }
}
