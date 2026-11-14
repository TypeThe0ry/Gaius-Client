package dev.gaius.browser;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * The build-time unihex glyph table (GAIUSUH1; the pages carry it gzip-compressed as
 * {@code gaius-unihex.bin.gz}) that
 * {@code BrowserUnihexLoader} reads instead of inflating and parsing a unifont zip of the vanilla
 * pack. No Minecraft types.
 *
 * <p>{@code dev.gaius.tools.UnihexTableBuilder} writes it at build time by running vanilla's own
 * {@code UnihexProvider$Definition.loadData} (without size overrides) on every font zip of the
 * validated vanilla pack, so the table is vanilla's result, not a mirror of it: per zip the
 * supported code points in ascending order, the calculated {@code left}/{@code right} of each glyph
 * ({@code LineData.calculateWidth}), its bit width and its 16 rows. The page (gaius-boot.js) loads
 * the table and hashes every listed zip of the archive it actually serves (WebCrypto SHA-256 of the
 * archive entry); only a zip whose archive bytes equal the bytes the table was built from is
 * offered here. Anything else (no table, {@code ?gaiusUnihexLazy=0}, a hash or length mismatch, a
 * stream that is not the vanilla pack's archive copy) returns null and the caller keeps its parser.
 *
 * <p>Layout (little endian): {@code "GAIUSUH1"}, u32 version (1), u32 zip count, u32 header
 * length; per zip: u16 path length, UTF-8 path, padding to 4, u32 zip length, 32 bytes zip
 * SHA-256, u32 glyph count, u32 code point offset, u32 meta offset, u32 rows offset, u32 rows
 * length. Code points: i32 per glyph. Meta: 3 bytes per glyph (signed left, signed right, row
 * bytes = bit width / 8). Rows: per glyph 16 rows of row-bytes big-endian bytes, the row's
 * hexadecimal digits of the .hex line.
 */
public final class UnihexGlyphTable {
    public static final int VERSION = 1;
    private static final byte[] MAGIC = "GAIUSUH1".getBytes(StandardCharsets.US_ASCII);
    private static final int ROWS = 16;

    /** Where the table bytes come from: the page (default) or a JVM gate. */
    public interface Source {
        /** {@code verified}, or why the table cannot be used ({@code absent}, {@code disabled}, ...). */
        String status();

        int length();

        /** Copies {@code target.length} bytes from {@code offset}; false when out of range. */
        boolean copy(int offset, byte[] target);

        /** Whether the page verified the archive bytes of zip {@code index} against the table. */
        boolean zipVerified(int index);

        /** Publishes the counters for the runbook; nothing to do outside a page. */
        void publish(int hits, int fallbacks, String lastReason);
    }

    private static Source source;
    private static boolean headerRead;
    private static Map<String, Zip> zips;
    private static String headerFailure;
    private static int hits;
    private static int fallbacks;

    private UnihexGlyphTable() {
    }

    /** The JVM gates install their own source; the page always uses the default one. */
    public static synchronized void useSourceForJvmGate(Source replacement) {
        source = replacement;
        headerRead = false;
        zips = null;
        headerFailure = null;
        hits = 0;
        fallbacks = 0;
    }

    private static Source source() {
        if (source == null) {
            source = new PageSource();
        }
        return source;
    }

    /**
     * The verified table entry of the archived resource {@code path} that the caller holds
     * {@code length} bytes of, or null with the reason recorded (the caller then parses the zip as
     * before).
     */
    public static synchronized Zip find(String path, int length) {
        Source current = source();
        String status = current.status();
        if (!"verified".equals(status)) {
            return miss("table-" + status);
        }
        if (!headerRead) {
            readHeader(current);
        }
        if (zips == null) {
            return miss("header-" + headerFailure);
        }
        Zip zip = zips.get(path);
        if (zip == null) {
            return miss("not-in-table");
        }
        if (!current.zipVerified(zip.index)) {
            return miss("zip-unverified");
        }
        if (length != zip.zipLength) {
            return miss("zip-length");
        }
        if (!zip.loadMeta(current)) {
            return miss("meta-unreadable");
        }
        return zip;
    }

    /** Called by the loader when it took the table path. */
    public static synchronized void recordHit() {
        hits++;
        source().publish(hits, fallbacks, "");
    }

    /** Called by the loader when it keeps the parser for a reason found before {@link #find}. */
    public static synchronized void recordFallback(String reason) {
        miss(reason);
    }

    private static Zip miss(String reason) {
        fallbacks++;
        source().publish(hits, fallbacks, reason);
        return null;
    }

    private static void readHeader(Source current) {
        headerRead = true;
        try {
            int length = current.length();
            byte[] fixed = new byte[16];
            if (length < fixed.length || !current.copy(0, fixed)) {
                headerFailure = "truncated";
                return;
            }
            for (int index = 0; index < MAGIC.length; index++) {
                if (fixed[index] != MAGIC[index]) {
                    headerFailure = "magic";
                    return;
                }
            }
            if (u32(fixed, 8) != VERSION) {
                headerFailure = "version";
                return;
            }
            int count = u32(fixed, 12);
            byte[] lengthField = new byte[4];
            if (!current.copy(16, lengthField)) {
                headerFailure = "truncated";
                return;
            }
            int headerLength = u32(lengthField, 0);
            if (count < 0 || headerLength < 20 || headerLength > length) {
                headerFailure = "header-length";
                return;
            }
            byte[] header = new byte[headerLength];
            if (!current.copy(0, header)) {
                headerFailure = "truncated";
                return;
            }
            Map<String, Zip> parsed = new HashMap<>();
            int cursor = 20;
            for (int index = 0; index < count; index++) {
                if (cursor + 2 > headerLength) {
                    headerFailure = "entry";
                    return;
                }
                int pathLength = (header[cursor] & 0xFF) | (header[cursor + 1] & 0xFF) << 8;
                cursor += 2;
                if (cursor + pathLength > headerLength) {
                    headerFailure = "entry";
                    return;
                }
                String path = new String(header, cursor, pathLength, StandardCharsets.UTF_8);
                cursor = align4(cursor + pathLength);
                if (cursor + 4 + 32 + 20 > headerLength) {
                    headerFailure = "entry";
                    return;
                }
                int zipLength = u32(header, cursor);
                cursor += 4 + 32;
                int glyphs = u32(header, cursor);
                int codepoints = u32(header, cursor + 4);
                int meta = u32(header, cursor + 8);
                int rows = u32(header, cursor + 12);
                int rowsLength = u32(header, cursor + 16);
                cursor += 20;
                if (glyphs < 0 || zipLength < 0
                        || !range(codepoints, glyphs * 4L, length)
                        || !range(meta, glyphs * 3L, length)
                        || !range(rows, rowsLength, length)
                        || parsed.containsKey(path)) {
                    headerFailure = "entry";
                    return;
                }
                parsed.put(path, new Zip(index, path, zipLength, glyphs, codepoints, meta, rows,
                        rowsLength));
            }
            zips = parsed;
        } catch (RuntimeException failure) {
            headerFailure = "exception";
        }
    }

    private static boolean range(long offset, long size, int length) {
        return offset >= 0 && size >= 0 && offset + size <= length;
    }

    private static int align4(int value) {
        return (value + 3) & ~3;
    }

    private static int u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | (bytes[offset + 1] & 0xFF) << 8
                | (bytes[offset + 2] & 0xFF) << 16
                | (bytes[offset + 3] & 0xFF) << 24;
    }

    /** One font zip of the table. Rows are copied on the first bake of one of its glyphs. */
    public static final class Zip {
        final int index;
        public final String path;
        final int zipLength;
        private final int glyphCount;
        private final int codepointOffset;
        private final int metaOffset;
        private final int rowsOffset;
        private final int rowsLength;
        private int[] codepoints;
        private byte[] meta;
        private byte[] rows;
        private int[] rowStart;

        private Zip(int index, String path, int zipLength, int glyphCount, int codepointOffset,
                int metaOffset, int rowsOffset, int rowsLength) {
            this.index = index;
            this.path = path;
            this.zipLength = zipLength;
            this.glyphCount = glyphCount;
            this.codepointOffset = codepointOffset;
            this.metaOffset = metaOffset;
            this.rowsOffset = rowsOffset;
            this.rowsLength = rowsLength;
        }

        private boolean loadMeta(Source current) {
            if (codepoints != null) {
                return true;
            }
            byte[] packed = new byte[glyphCount * 4];
            byte[] glyphMeta = new byte[glyphCount * 3];
            if (!current.copy(codepointOffset, packed) || !current.copy(metaOffset, glyphMeta)) {
                return false;
            }
            int[] values = new int[glyphCount];
            int previous = -1;
            long rowBytes = 0;
            for (int glyph = 0; glyph < glyphCount; glyph++) {
                int value = u32(packed, glyph * 4);
                int width = glyphMeta[glyph * 3 + 2];
                if (value <= previous || width < 1 || width > 4) {
                    return false;
                }
                values[glyph] = value;
                previous = value;
                rowBytes += width * ROWS;
            }
            if (rowBytes != rowsLength) {
                return false;
            }
            meta = glyphMeta;
            codepoints = values;
            return true;
        }

        public int glyphCount() {
            return glyphCount;
        }

        /** Code point of glyph {@code glyph}; ascending in {@code glyph}. */
        public int codepoint(int glyph) {
            return codepoints[glyph];
        }

        /** {@code Dimensions.left} of the calculated width of glyph {@code glyph}. */
        public int left(int glyph) {
            return meta[glyph * 3];
        }

        /** {@code Dimensions.right} of the calculated width of glyph {@code glyph}. */
        public int right(int glyph) {
            return meta[glyph * 3 + 1];
        }

        /** {@code LineData.bitWidth()} of glyph {@code glyph}. */
        public int bitWidth(int glyph) {
            return meta[glyph * 3 + 2] * 8;
        }

        /** {@code LineData.line(row)} of glyph {@code glyph}: the row left-aligned in 32 bits. */
        public int line(int glyph, int row) {
            if (rows == null) {
                loadRows();
            }
            int bytes = meta[glyph * 3 + 2];
            int offset = rowStart[glyph] + row * bytes;
            int value = 0;
            for (int index = 0; index < bytes; index++) {
                value = value << 8 | rows[offset + index] & 0xFF;
            }
            return bytes == 4 ? value : value << (32 - bytes * 8);
        }

        private void loadRows() {
            synchronized (UnihexGlyphTable.class) {
                if (rows != null) {
                    return;
                }
                byte[] copied = new byte[rowsLength];
                if (!source().copy(rowsOffset, copied)) {
                    // find() checked the range against the same immutable table bytes.
                    throw new IllegalStateException("unihex table rows are no longer readable: " + path);
                }
                int[] starts = new int[glyphCount];
                int offset = 0;
                for (int glyph = 0; glyph < glyphCount; glyph++) {
                    starts[glyph] = offset;
                    offset += meta[glyph * 3 + 2] * ROWS;
                }
                rowStart = starts;
                rows = copied;
            }
        }
    }

    /** The page's table: {@code globalThis.__gaiusUnihexTable} (gaius-boot.js). */
    private static final class PageSource implements Source {
        @Override
        public String status() {
            return tableStatus();
        }

        @Override
        public int length() {
            return tableLength();
        }

        @Override
        public boolean copy(int offset, byte[] target) {
            Int8Array view = JsArrays.int8Output(target);
            if (!copyTable(offset, view)) {
                return false;
            }
            JsArrays.readBack(view, target);
            return true;
        }

        @Override
        public boolean zipVerified(int index) {
            return tableZipVerified(index);
        }

        @Override
        public void publish(int hits, int fallbacks, String lastReason) {
            publishCounters(hits, fallbacks, lastReason);
        }
    }

    @JSBody(script = """
            const table = globalThis.__gaiusUnihexTable;
            return table && typeof table.status === 'string' ? table.status : 'absent';
            """)
    private static native String tableStatus();

    @JSBody(script = """
            const table = globalThis.__gaiusUnihexTable;
            return table && table.bytes ? table.bytes.length | 0 : 0;
            """)
    private static native int tableLength();

    @JSBody(params = {"offset", "output"}, script = """
            const table = globalThis.__gaiusUnihexTable;
            const target = output && output.data ? output.data : output;
            if (!table || !table.bytes || !target || offset < 0
                || offset + target.length > table.bytes.length) return false;
            target.set(table.bytes.subarray(offset, offset + target.length));
            return true;
            """)
    private static native boolean copyTable(int offset, Int8Array output);

    /**
     * The page hashed the zip's archive bytes; it still counts only while the archive the pack
     * reads from (AssetArchive: the core pack, then sounds) maps the path to that range of the
     * same pack.
     */
    @JSBody(params = "index", script = """
            const table = globalThis.__gaiusUnihexTable;
            const zip = table && table.status === 'verified' && Array.isArray(table.zips) ? table.zips[index] : null;
            if (!zip || zip.verified !== true) return false;
            const packs = [globalThis.__gaiusVanillaAssets, globalThis.__gaiusVanillaSounds];
            for (var i = 0; i < packs.length; i++) {
                const root = packs[i];
                if (!root || !root.bytes || !root.index
                    || !Object.prototype.hasOwnProperty.call(root.index, zip.path)) continue;
                const range = root.index[zip.path];
                return root === zip.root && Array.isArray(range)
                    && range[0] === zip.offset && range[1] === zip.length;
            }
            return false;
            """)
    private static native boolean tableZipVerified(int index);

    @JSBody(params = {"hits", "fallbacks", "reason"}, script = """
            try {
              const state = globalThis.__gaiusUnihexLazy || (globalThis.__gaiusUnihexLazy = {reasons: []});
              state.hits = hits;
              state.fallbacks = fallbacks;
              state.table = globalThis.__gaiusUnihexTable ? globalThis.__gaiusUnihexTable.status : 'absent';
              if (reason && state.reasons.length < 16) state.reasons.push(String(reason));
            } catch (ignored) {}
            """)
    private static native void publishCounters(int hits, int fallbacks, String reason);
}
