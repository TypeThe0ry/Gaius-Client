package dev.gaius.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Writes the unihex glyph table (GAIUSUH1; {@code gaius-unihex.bin.gz} in a dist) of a vanilla
 * asset pack.
 *
 * <pre>
 * UnihexTableBuilder --client-jar &lt;vanilla client-named.jar&gt; --libraries &lt;dir&gt;
 *     (--pack &lt;vanilla-assets.pack.gz&gt; | --entry &lt;assets/ns/font/x.zip&gt;=&lt;file&gt;...)
 *     --out &lt;gaius-unihex.bin[.gz]&gt;
 * </pre>
 *
 * <p>Vanilla is the oracle: every {@code assets/<ns>/font/**.zip} of the pack goes through the
 * unpatched {@code UnihexProvider$Definition.loadData} of the vanilla client jar (no size
 * overrides; reflection, so this tool has no Minecraft types), and the table records for each
 * supported code point, in ascending order, the {@code left}/{@code right} of the glyph vanilla
 * built ({@code LineData.calculateWidth}), its {@code LineData.bitWidth()} and its 16
 * {@code LineData.line(row)} values (stored at the bit width; every row is checked to round-trip).
 * A zip vanilla cannot load is left out: the browser then reads it and fails as vanilla does.
 * The layout is described in {@code dev.gaius.browser.UnihexGlyphTable}; an output name ending
 * in {@code .gz} is written gzip-compressed (what the pages carry). The output depends only on
 * the inputs. Prints a JSON summary (sizes and SHA-256 of the uncompressed table).
 */
public final class UnihexTableBuilder {
    private static final byte[] MAGIC = "GAIUSUH1".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final Pattern FONT_ZIP = Pattern.compile("assets/[^/]+/font/.+\\.zip");
    private static final String PROVIDERS = "net.minecraft.client.gui.font.providers.";

    private UnihexTableBuilder() {
    }

    public static void main(String[] args) throws Exception {
        Path clientJar = null;
        Path libraries = null;
        Path pack = null;
        Path out = null;
        Map<String, Path> entries = new TreeMap<>();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            switch (arg) {
                case "--client-jar" -> clientJar = Path.of(args[++index]);
                case "--libraries" -> libraries = Path.of(args[++index]);
                case "--pack" -> pack = Path.of(args[++index]);
                case "--out" -> out = Path.of(args[++index]);
                case "--entry" -> {
                    String value = args[++index];
                    int split = value.indexOf('=');
                    if (split <= 0) {
                        throw new IllegalArgumentException("--entry needs <resource>=<file>: " + value);
                    }
                    entries.put(value.substring(0, split), Path.of(value.substring(split + 1)));
                }
                default -> throw new IllegalArgumentException("unknown argument " + arg);
            }
        }
        if (clientJar == null || libraries == null || out == null || (pack == null) == entries.isEmpty()) {
            System.err.println("usage: UnihexTableBuilder --client-jar <jar> --libraries <dir> "
                    + "(--pack <vanilla-assets.pack.gz> | --entry <resource>=<file>...) --out <file>");
            System.exit(2);
        }
        Map<String, byte[]> zips = new TreeMap<>();
        if (pack != null) {
            zips.putAll(fontZips(pack));
        }
        for (Map.Entry<String, Path> entry : entries.entrySet()) {
            zips.put(entry.getKey(), Files.readAllBytes(entry.getValue()));
        }
        try (URLClassLoader loader = vanillaLoader(clientJar, libraries)) {
            Oracle oracle = new Oracle(loader);
            List<Table> tables = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (Map.Entry<String, byte[]> zip : zips.entrySet()) {
                Table table = oracle.load(zip.getKey(), zip.getValue());
                if (table == null) {
                    skipped.add(zip.getKey());
                } else {
                    tables.add(table);
                }
            }
            byte[] data = encode(tables);
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = out.resolveSibling(out.getFileName() + ".part");
            if (out.getFileName().toString().endsWith(".gz")) {
                // GZIPOutputStream writes a fixed header (no name, mtime 0): deterministic bytes.
                try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(
                        Files.newOutputStream(temporary)) {
                    {
                        def.setLevel(java.util.zip.Deflater.BEST_COMPRESSION);
                    }
                }) {
                    gzip.write(data);
                }
            } else {
                Files.write(temporary, data);
            }
            Files.move(temporary, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            StringBuilder json = new StringBuilder("{\"bytes\":").append(data.length)
                    .append(",\"sha256\":\"").append(hex(sha256(data))).append("\",\"zips\":[");
            for (int index = 0; index < tables.size(); index++) {
                Table table = tables.get(index);
                json.append(index == 0 ? "" : ",").append("{\"path\":\"").append(table.path)
                        .append("\",\"glyphs\":").append(table.codepoints.length)
                        .append(",\"zipBytes\":").append(table.zip.length).append('}');
            }
            json.append("],\"skipped\":").append(skipped.size()).append('}');
            System.out.println(json);
        }
    }

    /** The class loader of the vanilla client: the jar, then every library jar in path order. */
    static URLClassLoader vanillaLoader(Path clientJar, Path libraries) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(clientJar.toUri().toURL());
        try (Stream<Path> walk = Files.walk(libraries)) {
            for (Path jar : walk.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
                urls.add(jar.toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    /** The font zips of a GAIUSVP1 pack (gzip), by resource path. */
    static Map<String, byte[]> fontZips(Path pack) throws IOException {
        byte[] raw;
        try (InputStream input = new GZIPInputStream(Files.newInputStream(pack))) {
            raw = input.readAllBytes();
        }
        if (raw.length < 12 || !Arrays.equals(Arrays.copyOf(raw, 8), "GAIUSVP1".getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException(pack + " is not a GAIUSVP1 vanilla asset pack");
        }
        int indexLength = ByteBuffer.wrap(raw, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int dataOffset = 12 + indexLength;
        if (indexLength <= 0 || dataOffset > raw.length) {
            throw new IOException(pack + " has a truncated index");
        }
        Map<String, long[]> index = new IndexParser(
                new String(raw, 12, indexLength, StandardCharsets.UTF_8)).parse();
        Map<String, byte[]> zips = new TreeMap<>();
        for (Map.Entry<String, long[]> entry : index.entrySet()) {
            if (!FONT_ZIP.matcher(entry.getKey()).matches()) {
                continue;
            }
            long offset = entry.getValue()[0];
            long length = entry.getValue()[1];
            if (offset < 0 || length < 0 || dataOffset + offset + length > raw.length) {
                throw new IOException("pack range outside payload: " + entry.getKey());
            }
            zips.put(entry.getKey(), Arrays.copyOfRange(raw, (int) (dataOffset + offset),
                    (int) (dataOffset + offset + length)));
        }
        return zips;
    }

    /** One zip as vanilla loaded it. */
    record Table(String path, byte[] zip, int[] codepoints, byte[] meta, byte[] rows) {
    }

    /** Vanilla's unihex loader, through reflection. */
    static final class Oracle {
        private final Constructor<?> definition;
        private final Method loadData;
        private final Method supported;
        private final Method getGlyph;
        private final Method line;
        private final Method bitWidth;

        Oracle(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> definitionClass = Class.forName(PROVIDERS + "UnihexProvider$Definition", true, loader);
            Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, loader);
            definition = definitionClass.getDeclaredConstructor(identifier, List.class);
            definition.setAccessible(true);
            loadData = definitionClass.getDeclaredMethod("loadData", InputStream.class);
            loadData.setAccessible(true);
            Class<?> provider = Class.forName(PROVIDERS + "UnihexProvider", false, loader);
            supported = provider.getMethod("getSupportedGlyphs");
            getGlyph = provider.getMethod("getGlyph", int.class);
            Class<?> lineData = Class.forName(PROVIDERS + "UnihexProvider$LineData", false, loader);
            line = lineData.getMethod("line", int.class);
            bitWidth = lineData.getMethod("bitWidth");
        }

        Table load(String path, byte[] zip) throws ReflectiveOperationException {
            Object provider;
            try {
                provider = loadData.invoke(definition.newInstance(null, List.of()), new ByteArrayInputStream(zip));
            } catch (InvocationTargetException failure) {
                System.err.println("UnihexTableBuilder: vanilla does not load " + path + ": " + failure.getCause());
                return null;
            }
            Collection<?> keys = (Collection<?>) supported.invoke(provider);
            int[] codepoints = keys.stream().mapToInt(value -> (Integer) value).sorted().toArray();
            byte[] meta = new byte[codepoints.length * 3];
            ByteArrayOutputStream rows = new ByteArrayOutputStream();
            Method contents = null;
            Method left = null;
            Method right = null;
            for (int glyph = 0; glyph < codepoints.length; glyph++) {
                Object value = getGlyph.invoke(provider, codepoints[glyph]);
                if (contents == null) {
                    contents = accessible(value.getClass(), "contents");
                    left = accessible(value.getClass(), "left");
                    right = accessible(value.getClass(), "right");
                }
                Object lines = contents.invoke(value);
                int width = (Integer) bitWidth.invoke(lines);
                int leftValue = (Integer) left.invoke(value);
                int rightValue = (Integer) right.invoke(value);
                if (width != 8 && width != 16 && width != 24 && width != 32) {
                    throw new IllegalStateException(path + ": bit width " + width);
                }
                if (leftValue != (byte) leftValue || rightValue != (byte) rightValue) {
                    throw new IllegalStateException(path + ": dimensions outside a byte");
                }
                meta[glyph * 3] = (byte) leftValue;
                meta[glyph * 3 + 1] = (byte) rightValue;
                meta[glyph * 3 + 2] = (byte) (width / 8);
                int shift = 32 - width;
                for (int row = 0; row < 16; row++) {
                    int bits = (Integer) line.invoke(lines, row);
                    int stored = shift == 0 ? bits : bits >>> shift;
                    int decoded = shift == 0 ? stored : stored << shift;
                    if (decoded != bits) {
                        throw new IllegalStateException(path + ": row " + row + " of U+"
                                + Integer.toHexString(codepoints[glyph]) + " does not round-trip");
                    }
                    for (int index = width / 8 - 1; index >= 0; index--) {
                        rows.write(stored >>> (index * 8));
                    }
                }
            }
            return new Table(path, zip, codepoints, meta, rows.toByteArray());
        }

        private static Method accessible(Class<?> type, String name) throws NoSuchMethodException {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        }
    }

    static byte[] encode(List<Table> tables) {
        List<byte[]> paths = new ArrayList<>();
        int headerLength = 20;
        for (Table table : tables) {
            byte[] path = table.path.getBytes(StandardCharsets.UTF_8);
            if (path.length > 0xFFFF) {
                throw new IllegalArgumentException("path too long: " + table.path);
            }
            paths.add(path);
            headerLength = align4(headerLength + 2 + path.length) + 4 + 32 + 20;
        }
        int cursor = align4(headerLength);
        Map<Table, int[]> offsets = new LinkedHashMap<>();
        for (Table table : tables) {
            int codepoints = cursor;
            cursor += table.codepoints.length * 4;
            int meta = cursor;
            cursor = align4(cursor + table.meta.length);
            int rows = cursor;
            cursor = align4(cursor + table.rows.length);
            offsets.put(table, new int[] {codepoints, meta, rows});
        }
        ByteBuffer out = ByteBuffer.allocate(cursor).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC).putInt(VERSION).putInt(tables.size()).putInt(headerLength);
        for (int index = 0; index < tables.size(); index++) {
            Table table = tables.get(index);
            byte[] path = paths.get(index);
            out.putShort((short) path.length).put(path);
            out.position(align4(out.position()));
            int[] at = offsets.get(table);
            out.putInt(table.zip.length).put(sha256(table.zip)).putInt(table.codepoints.length)
                    .putInt(at[0]).putInt(at[1]).putInt(at[2]).putInt(table.rows.length);
        }
        for (Table table : tables) {
            int[] at = offsets.get(table);
            out.position(at[0]);
            for (int codepoint : table.codepoints) {
                out.putInt(codepoint);
            }
            out.position(at[1]);
            out.put(table.meta);
            out.position(at[2]);
            out.put(table.rows);
        }
        return out.array();
    }

    private static int align4(int value) {
        return (value + 3) & ~3;
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(Character.forDigit((value >> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        }
        return text.toString();
    }

    /** The pack index: one JSON object of {@code "resource": [offset, length]}. */
    static final class IndexParser {
        private final String text;
        private int at;

        IndexParser(String text) {
            this.text = text;
        }

        Map<String, long[]> parse() throws IOException {
            Map<String, long[]> result = new TreeMap<>();
            expect('{');
            if (peek() == '}') {
                at++;
                return result;
            }
            while (true) {
                String name = string();
                expect(':');
                expect('[');
                long offset = number();
                expect(',');
                long length = number();
                expect(']');
                result.put(name, new long[] {offset, length});
                char next = next();
                if (next == '}') {
                    return result;
                }
                if (next != ',') {
                    throw new IOException("pack index: unexpected '" + next + "' at " + at);
                }
            }
        }

        private char peek() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            return at < text.length() ? text.charAt(at) : '\0';
        }

        private char next() throws IOException {
            char value = peek();
            if (at >= text.length()) {
                throw new IOException("pack index is truncated");
            }
            at++;
            return value;
        }

        private void expect(char value) throws IOException {
            char found = next();
            if (found != value) {
                throw new IOException("pack index: expected '" + value + "' at " + at + ", found '" + found + "'");
            }
        }

        private long number() throws IOException {
            peek();
            int start = at;
            while (at < text.length() && Character.isDigit(text.charAt(at))) {
                at++;
            }
            if (start == at) {
                throw new IOException("pack index: expected a number at " + at);
            }
            return Long.parseLong(text.substring(start, at));
        }

        private String string() throws IOException {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (true) {
                if (at >= text.length()) {
                    throw new IOException("pack index: unterminated string");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return value.toString();
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> value.append(escape);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        value.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new IOException("pack index: bad escape \\" + escape);
                }
            }
        }
    }
}
