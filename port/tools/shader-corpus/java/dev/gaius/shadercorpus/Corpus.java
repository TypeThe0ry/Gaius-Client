package dev.gaius.shadercorpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Content-addressed blobs plus JSON-lines streams of one harness run. */
final class Corpus {
    private final Path root;
    private final Map<String, Writer> streams = new HashMap<>();

    Corpus(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    Path root() {
        return root;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stores data as blob/sub/sha256+ext once and returns the hash. */
    String blob(String sub, String ext, byte[] data) {
        String hash = sha256(data);
        Path path = root.resolve(sub).resolve(hash + ext);
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.write(path, data);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hash;
    }

    void line(String stream, String json) {
        try {
            Writer writer = streams.get(stream);
            if (writer == null) {
                writer = Files.newBufferedWriter(root.resolve(stream + ".jsonl"), StandardCharsets.UTF_8);
                streams.put(stream, writer);
            }
            writer.write(json);
            writer.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void write(String name, String content) throws IOException {
        Files.writeString(root.resolve(name), content, StandardCharsets.UTF_8);
    }

    void close() throws IOException {
        for (Writer writer : streams.values()) {
            writer.close();
        }
        streams.clear();
    }

    static String q(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    static String q(byte[] utf8) {
        return utf8 == null ? "null" : q(new String(utf8, StandardCharsets.UTF_8));
    }
}
