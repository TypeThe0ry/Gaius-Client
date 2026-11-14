import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.teavm.classlib.java.io.TBufferedReader;
import org.teavm.classlib.java.io.TByteArrayInputStream;
import org.teavm.classlib.java.io.TInputStream;
import org.teavm.classlib.java.io.TInputStreamReader;
import org.teavm.classlib.java.io.TReader;
import org.teavm.classlib.java.nio.charset.TCharset;

/**
 * Drives TeaVM's TInputStreamReader (whichever is first on the class path: TeaVM's original or
 * the port/overrides/classlib one) over a corpus and prints one SHA-256 per (input, stream, charset,
 * read pattern) of everything a caller can observe: every return value of read()/read(char[],..)
 * and ready(), the characters, and the exception type when one is thrown. The JSON corpus is read
 * the way Resource.openAsReader does it (BufferedReader over InputStreamReader(UTF-8) over a
 * ByteArrayInputStream) and with direct reader calls; the synthetic corpus covers malformed UTF-8,
 * surrogates, BOMs, truncated tails and multi-byte sequences across the 64/1024/8192 boundaries.
 * The node test runs it on both classes and requires the same output.
 *   java ReaderDietDifferential <zip-or-dir>...    (entries ending in .json/.mcmeta, or files in dirs)
 */
public final class ReaderDietDifferential {
    private static final String[] CHARSETS = {"UTF-8", "ISO-8859-1", "UTF-16", "UTF-16BE", "UTF-16LE", "US-ASCII"};
    private static long checks;

    public static void main(String[] args) throws Exception {
        System.out.println("PROBE inData(10 bytes)=" + inDataLength(new TByteArrayInputStream(new byte[10]))
                + " inData(9000 bytes)=" + inDataLength(new TByteArrayInputStream(new byte[9000]))
                + " inData(chunked)=" + inDataLength(new Chunked(new byte[10], 3)));
        MessageDigest all = MessageDigest.getInstance("SHA-256");
        int corpus = 0;
        for (String arg : args) {
            for (Input input : inputs(arg)) {
                corpus++;
                for (int stream = 0; stream < 2; stream++) {
                    String line = input.name + " s" + stream + " openAsReader " + openAsReader(input.bytes, stream)
                            + " direct " + direct(input.bytes, stream, "UTF-8", input.name.hashCode());
                    all.update(line.getBytes(StandardCharsets.UTF_8));
                    System.out.println(line);
                }
            }
        }
        int synthetic = 0;
        for (Input input : synthetic()) {
            synthetic++;
            for (String charset : CHARSETS) {
                for (int stream = 0; stream < Chunked.KINDS; stream++) {
                    String line = input.name + " " + charset + " s" + stream
                            + " p0 " + direct(input.bytes, stream, charset, 0)
                            + " p1 " + direct(input.bytes, stream, charset, 1)
                            + " p2 " + direct(input.bytes, stream, charset, 7)
                            + " b " + buffered(input.bytes, stream, charset);
                    all.update(line.getBytes(StandardCharsets.UTF_8));
                    System.out.println(line);
                }
            }
        }
        System.out.println("READER_DIET corpus=" + corpus + " synthetic=" + synthetic + " checks=" + checks
                + " sha256=" + HexFormat.of().formatHex(all.digest()));
    }

    private static int inDataLength(TInputStream stream) throws Exception {
        TInputStreamReader reader = new TInputStreamReader(stream, TCharset.forName("UTF-8"));
        Field field = TInputStreamReader.class.getDeclaredField("inData");
        field.setAccessible(true);
        return ((byte[]) field.get(reader)).length;
    }

    /** Resource.openAsReader + a Gson-like consumer: read(char[1024], pos, limit - pos) until -1. */
    private static String openAsReader(byte[] bytes, int stream) throws Exception {
        Trace trace = new Trace();
        try {
            TBufferedReader reader = new TBufferedReader(new TInputStreamReader(stream(bytes, stream),
                    TCharset.forName("UTF-8")));
            char[] buffer = new char[1024];
            int pos = 0;
            while (true) {
                int count = reader.read(buffer, pos, buffer.length - pos);
                trace.value(count);
                if (count < 0) {
                    break;
                }
                trace.chars(buffer, pos, count);
                pos = (pos + count) % 1024;
                if (pos > 1000) {
                    pos = 0;
                }
            }
            trace.value(reader.read());
            reader.close();
        } catch (Exception e) {
            trace.text(e.getClass().getName());
        }
        return trace.digest();
    }

    /** Direct reader calls: seed 0 single chars, 1 bulk 8192, other seeds random mixes with ready(). */
    private static String direct(byte[] bytes, int stream, String charset, int seed) throws Exception {
        Trace trace = new Trace();
        Random random = new Random(seed);
        try {
            TReader reader = new TInputStreamReader(stream(bytes, stream), charset);
            char[] buffer = new char[9000];
            for (int step = 0; step < 100000; step++) {
                int count;
                if (seed == 0) {
                    count = reader.read();
                    trace.value(count);
                } else {
                    int kind = seed == 1 ? 3 : random.nextInt(5);
                    if (kind == 0) {
                        trace.value(reader.ready() ? 1 : 0);
                        continue;
                    }
                    if (kind == 1) {
                        count = reader.read();
                        trace.value(count);
                    } else {
                        int len = kind == 3 ? 8192 : random.nextInt(kind == 2 ? 3 : 2100);
                        int off = random.nextInt(buffer.length - len + 1);
                        count = reader.read(buffer, off, len);
                        trace.value(count);
                        if (count > 0) {
                            trace.chars(buffer, off, count);
                        }
                        if (len == 0 && count == 0) {
                            continue;
                        }
                    }
                }
                trace.value(reader.ready() ? 1 : 0);
                if (count < 0) {
                    trace.value(reader.read());
                    trace.value(reader.read(buffer, 0, 10));
                    break;
                }
            }
            reader.close();
        } catch (Exception e) {
            trace.text(e.getClass().getName());
        }
        return trace.digest();
    }

    private static String buffered(byte[] bytes, int stream, String charset) throws Exception {
        Trace trace = new Trace();
        try {
            TBufferedReader reader = new TBufferedReader(new TInputStreamReader(stream(bytes, stream), charset), 37);
            String line;
            while ((line = reader.readLine()) != null) {
                trace.text(line);
            }
            trace.value(reader.read());
        } catch (Exception e) {
            trace.text(e.getClass().getName());
        }
        return trace.digest();
    }

    private static TInputStream stream(byte[] bytes, int kind) {
        return switch (kind) {
            case 0 -> new TByteArrayInputStream(bytes);
            case 1 -> {
                // offset/length constructor over a larger array, then skip and read part of it
                byte[] padded = new byte[bytes.length + 7];
                System.arraycopy(bytes, 0, padded, 3, bytes.length);
                TByteArrayInputStream in = new TByteArrayInputStream(padded, 1, bytes.length + 2);
                in.read();
                in.skip(1);
                yield in;
            }
            case 7 -> new Subclass(bytes);
            default -> new Chunked(bytes, kind);
        };
    }

    /** Streams that keep TeaVM's 8192-byte buffer: a subclass, short reads, available() 0 or throwing. */
    static final class Chunked extends TInputStream {
        static final int KINDS = 8;
        private final byte[] bytes;
        private final int kind;
        private int pos;

        Chunked(byte[] bytes, int kind) {
            this.bytes = bytes;
            this.kind = kind;
        }

        @Override
        public int read() {
            return pos < bytes.length ? bytes[pos++] & 255 : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            int max = switch (kind) {
                case 2 -> 1;
                case 3 -> 7;
                case 4 -> 100;
                default -> len;
            };
            int count = Math.min(Math.min(len, max), bytes.length - pos);
            if (count <= 0) {
                return len == 0 ? 0 : -1;
            }
            System.arraycopy(bytes, pos, b, off, count);
            pos += count;
            return count;
        }

        @Override
        public int available() throws IOException {
            return switch (kind) {
                case 5 -> 0;
                case 6 -> throw new IOException("available");
                default -> bytes.length - pos;
            };
        }
    }

    static final class Subclass extends TByteArrayInputStream {
        Subclass(byte[] bytes) {
            super(bytes);
        }
    }

    record Input(String name, byte[] bytes) {
    }

    private static List<Input> inputs(String path) throws IOException {
        List<Input> result = new ArrayList<>();
        if (path.startsWith("pack=")) {
            // pack=<vanilla-assets.pack.gz>: the browser's validated vanilla pack (GAIUSVP1,
            // build-vanilla-assets-pack.py); every .json/.mcmeta resource in it
            result.addAll(packEntries(new File(path.substring("pack=".length())), ".json", ".mcmeta"));
            if (result.isEmpty()) {
                throw new IllegalStateException("no .json/.mcmeta resources in " + path);
            }
            return result;
        }
        if (path.startsWith("index=")) {
            // index=<assets/indexes/N.json>: the asset-index objects whose names end in .json/.mcmeta
            File index = new File(path.substring("index=".length()));
            File objects = new File(index.getParentFile().getParentFile(), "objects");
            String json = Files.readString(index.toPath());
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"([^\"]+\\.(?:json|mcmeta))\"\\s*:\\s*\\{\\s*\"hash\"\\s*:\\s*\"([0-9a-f]{40})\"")
                    .matcher(json);
            java.util.TreeMap<String, String> names = new java.util.TreeMap<>();
            while (m.find()) {
                names.put(m.group(1), m.group(2));
            }
            for (var entry : names.entrySet()) {
                File object = new File(objects, entry.getValue().substring(0, 2) + "/" + entry.getValue());
                result.add(new Input(entry.getKey(), Files.readAllBytes(object.toPath())));
            }
            if (result.isEmpty()) {
                throw new IllegalStateException("no .json/.mcmeta objects in " + index);
            }
            return result;
        }
        File file = new File(path);
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            java.util.Arrays.sort(files);
            for (File child : files) {
                if (child.isFile()) {
                    result.add(new Input(child.getName(), Files.readAllBytes(child.toPath())));
                }
            }
            return result;
        }
        try (ZipFile zip = new ZipFile(file)) {
            List<ZipEntry> entries = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory() && (entry.getName().endsWith(".json") || entry.getName().endsWith(".mcmeta"))) {
                    entries.add(entry);
                }
            }
            entries.sort((a, b) -> a.getName().compareTo(b.getName()));
            for (ZipEntry entry : entries) {
                try (var in = zip.getInputStream(entry)) {
                    result.add(new Input(entry.getName(), in.readAllBytes()));
                }
            }
        }
        return result;
    }

    static List<Input> packEntries(File pack, String... suffixes) throws IOException {
        byte[] data;
        try (var in = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(pack))) {
            data = in.readAllBytes();
        }
        if (!new String(data, 0, 8, StandardCharsets.ISO_8859_1).equals("GAIUSVP1")) {
            throw new IllegalStateException(pack + " is not a GAIUSVP1 pack");
        }
        int indexLength = (data[8] & 255) | (data[9] & 255) << 8 | (data[10] & 255) << 16 | (data[11] & 255) << 24;
        String index = new String(data, 12, indexLength, StandardCharsets.UTF_8);
        int base = 12 + indexLength;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\":\\[(\\d+),(\\d+)\\]")
                .matcher(index);
        java.util.TreeMap<String, int[]> entries = new java.util.TreeMap<>();
        while (m.find()) {
            entries.put(m.group(1), new int[] {Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))});
        }
        List<Input> result = new ArrayList<>();
        for (var entry : entries.entrySet()) {
            boolean wanted = suffixes.length == 0;
            for (String suffix : suffixes) {
                wanted |= entry.getKey().endsWith(suffix);
            }
            if (wanted) {
                int[] at = entry.getValue();
                result.add(new Input(entry.getKey(), java.util.Arrays.copyOfRange(data, base + at[0], base + at[0] + at[1])));
            }
        }
        return result;
    }

    private static List<Input> synthetic() {
        List<Input> result = new ArrayList<>();
        int[] sizes = {0, 1, 2, 3, 4, 5, 62, 63, 64, 65, 66, 127, 128, 1021, 1022, 1023, 1024, 1025, 1026,
                2047, 2048, 2049, 4096, 8189, 8190, 8191, 8192, 8193, 8194, 12000, 16384, 20000};
        byte[][] pieces = {
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                "é".getBytes(StandardCharsets.UTF_8),
                "€".getBytes(StandardCharsets.UTF_8),
                "😀".getBytes(StandardCharsets.UTF_8),
                {(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
                {(byte) 0x80},
                {(byte) 0xbf},
                {(byte) 0xc0, (byte) 0xaf},
                {(byte) 0xc2},
                {(byte) 0xe2, (byte) 0x82},
                {(byte) 0xf0, (byte) 0x9f, (byte) 0x98},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xed, (byte) 0xbf, (byte) 0xbf},
                {(byte) 0xed, (byte) 0xa0, (byte) 0xbd, (byte) 0xed, (byte) 0xb8, (byte) 0x80},
                {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
                {(byte) 0xf5, (byte) 0x80},
                {(byte) 0xf8, (byte) 0x88, (byte) 0x80, (byte) 0x80, (byte) 0x80},
                {(byte) 0xfe, (byte) 0xff},
                {(byte) 0xff, (byte) 0xfe},
                {(byte) 0xe0, (byte) 0x80, (byte) 0x80},
                {0},
                {'\r', '\n'},
                {'\n'},
                {'\r'},
        };
        Random random = new Random(20261113);
        for (int size : sizes) {
            for (int variant = 0; variant < 8; variant++) {
                byte[] bytes = new byte[size];
                int i = 0;
                if (variant == 1 && size >= 3) {
                    bytes[0] = (byte) 0xef;
                    bytes[1] = (byte) 0xbb;
                    bytes[2] = (byte) 0xbf;
                    i = 3;
                }
                while (i < size) {
                    byte[] piece;
                    switch (variant) {
                        case 0, 1 -> piece = pieces[random.nextInt(4)];
                        case 2 -> piece = new byte[] {(byte) random.nextInt(256)};
                        case 3, 4 -> piece = pieces[random.nextInt(pieces.length)];
                        case 5 -> piece = pieces[3 + random.nextInt(2) * 10];
                        default -> piece = random.nextInt(9) == 0 ? pieces[5 + random.nextInt(pieces.length - 5)]
                                : pieces[random.nextInt(4)];
                    }
                    for (int k = 0; k < piece.length && i < size; k++) {
                        bytes[i++] = piece[k];
                    }
                }
                if (variant == 4 && size > 0) {
                    // a multi-byte sequence cut by the end of the input
                    bytes[size - 1] = (byte) 0xf0;
                }
                if (variant == 7 && size >= 4) {
                    // a four-byte sequence straddling every boundary near the end of the input
                    int at = Math.max(0, size - 2);
                    bytes[at - 2] = (byte) 0xf0;
                    bytes[at - 1] = (byte) 0x9f;
                    bytes[at] = (byte) 0x98;
                    bytes[at + 1] = (byte) 0x80;
                }
                result.add(new Input("syn-" + size + "-" + variant, bytes));
            }
            for (int at : new int[] {61, 62, 63, 64, 1022, 1023, 8190, 8191}) {
                if (at + 4 <= size) {
                    byte[] bytes = new byte[size];
                    java.util.Arrays.fill(bytes, (byte) 'x');
                    bytes[at] = (byte) 0xf0;
                    bytes[at + 1] = (byte) 0x9f;
                    bytes[at + 2] = (byte) 0x98;
                    bytes[at + 3] = (byte) 0x80;
                    result.add(new Input("straddle-" + size + "-" + at, bytes));
                }
            }
        }
        return result;
    }

    static final class Trace {
        private final MessageDigest digest;

        Trace() throws Exception {
            digest = MessageDigest.getInstance("SHA-256");
        }

        void value(int value) {
            checks++;
            digest.update(new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value, ';'});
        }

        void chars(char[] chars, int off, int count) {
            for (int i = 0; i < count; i++) {
                char c = chars[off + i];
                digest.update((byte) (c >> 8));
                digest.update((byte) c);
            }
            digest.update((byte) '|');
        }

        void text(String text) {
            chars(text.toCharArray(), 0, text.length());
        }

        String digest() {
            return HexFormat.of().formatHex(digest.digest(), 0, 12);
        }
    }
}
