package net.minecraft.client.gui.font.providers;

import dev.gaius.browser.ArchivedStreams;
import dev.gaius.browser.UnihexGlyphTable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.gui.font.CodepointMap;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.lwjgl.system.MemoryUtil;

/**
 * Browser loading of Mojang unihex fonts: the build-time glyph table for a zip of the vanilla
 * pack, else a bulk parser that avoids byte-at-a-time browser I/O.
 *
 * <p>{@code UiPatches.patchUnihexProviderBrowserBulkParser} replaces {@code Definition.loadData}
 * with {@link #load}. Both paths build what vanilla builds: a {@code CodepointMap} with one glyph
 * per code point of the zip, the size overrides of the definition applied in vanilla's order
 * (the first range that holds a code point wins), advance {@code width / 2 + 1}, shadow and bold
 * offset 0.5, and a 16 pixel high bitmap with oversample 2 whose pixels vanilla's
 * {@code unpackBitsToBytes} writes from the rows. The table path is taken only for a stream the
 * vanilla pack opened over its archive ({@link ArchivedStreams#track}: a resource pack's zip is
 * parsed) whose bytes the page verified against the table ({@link UnihexGlyphTable#find}); its
 * glyphs read their rows from the table when they are baked, and their calculated dimensions
 * come from the table, which vanilla's own loader produced at build time.
 */
public final class BrowserUnihexLoader {
    private BrowserUnihexLoader() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static UnihexProvider load(InputStream input, List<?> rawOverrides) throws IOException {
        UnihexProvider table = loadFromTable(input, rawOverrides);
        if (table != null) {
            return table;
        }
        CodepointMap glyphs = new CodepointMap(
                size -> new UnbakedGlyph[size],
                size -> new UnbakedGlyph[size][]);
        int[] overrides = prepareOverrides(rawOverrides);
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith(".hex")) {
                    parseHex(zip.readAllBytes(), overrides, glyphs);
                }
            }
        }
        return new UnihexProvider(glyphs);
    }

    /**
     * The provider of the zip {@code input} reads from the build-time glyph table, or null when
     * the zip must be read: the stream is not the vanilla pack's archive copy of a resource (a
     * resource pack replaces the zip), or the table holds no verified copy of exactly these bytes.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static UnihexProvider loadFromTable(InputStream input, List<?> rawOverrides) {
        String resource = ArchivedStreams.trackedResource(input);
        if (resource == null) {
            UnihexGlyphTable.recordFallback("not-archived");
            return null;
        }
        UnihexGlyphTable.Zip zip = UnihexGlyphTable.find(resource, ArchivedStreams.trackedLength(input));
        if (zip == null) {
            return null;
        }
        int[] overrides = prepareOverrides(rawOverrides);
        CodepointMap glyphs = new CodepointMap(
                size -> new UnbakedGlyph[size],
                size -> new UnbakedGlyph[size][]);
        int count = zip.glyphCount();
        for (int glyph = 0; glyph < count; glyph++) {
            int codepoint = zip.codepoint(glyph);
            int override = findOverrideDimensions(codepoint, overrides);
            int left = override >= 0 ? UnihexProvider.Dimensions.left(overrides[override + 2]) : zip.left(glyph);
            int right = override >= 0 ? UnihexProvider.Dimensions.right(overrides[override + 2]) : zip.right(glyph);
            glyphs.put(codepoint, new TableGlyph(zip, glyph, left, right));
        }
        UnihexGlyphTable.recordHit();
        return new UnihexProvider(glyphs);
    }

    /**
     * {@code from, to, Dimensions.pack()} of every size override, in definition order. The packed
     * form keeps {@code left} and {@code right} only as signed bytes: vanilla's glyph takes the
     * raw ints, so an override outside -128..127 (none in the vanilla fonts) differs from vanilla on
     * both paths, as it did before the table.
     */
    private static int[] prepareOverrides(List<?> rawOverrides) {
        int[] overrides = new int[rawOverrides.size() * 3];
        for (int index = 0; index < rawOverrides.size(); index++) {
            Object value = rawOverrides.get(index);
            int offset = index * 3;
            overrides[offset] = UnihexProvider.Definition.browserOverrideFrom(value);
            overrides[offset + 1] = UnihexProvider.Definition.browserOverrideTo(value);
            overrides[offset + 2] = UnihexProvider.Definition.browserOverrideDimensions(value);
        }
        return overrides;
    }

    static void parseHex(
            byte[] bytes,
            int[] overrides,
            CodepointMap<Object> glyphs) throws IOException {
        int offset = 0;
        int lineNumber = 0;
        while (offset < bytes.length) {
            lineNumber++;
            int lineEnd = offset;
            while (lineEnd < bytes.length && bytes[lineEnd] != '\n' && bytes[lineEnd] != '\r') {
                lineEnd++;
            }
            if (lineEnd > offset) {
                parseLine(bytes, offset, lineEnd, lineNumber, overrides, glyphs);
            }
            offset = lineEnd;
            while (offset < bytes.length && (bytes[offset] == '\n' || bytes[offset] == '\r')) {
                offset++;
            }
        }
    }

    private static void parseLine(
            byte[] bytes,
            int start,
            int end,
            int lineNumber,
            int[] overrides,
            CodepointMap<Object> glyphs) throws IOException {
        int colon = start;
        while (colon < end && bytes[colon] != ':') {
            colon++;
        }
        int codepointDigits = colon - start;
        if (colon == end || codepointDigits < 4 || codepointDigits > 6) {
            throw invalidLine(lineNumber, "invalid codepoint");
        }
        int codepoint = parseHexInt(bytes, start, colon, lineNumber);
        int dataStart = colon + 1;
        int dataDigits = end - dataStart;
        if (dataDigits != 32 && dataDigits != 64 && dataDigits != 96 && dataDigits != 128) {
            throw invalidLine(lineNumber, "invalid glyph width");
        }

        int bitWidth = dataDigits / 4;
        UnihexProvider.LineData lines = parseRows(bytes, dataStart, bitWidth, lineNumber);
        int left;
        int right;
        int override = findOverrideDimensions(codepoint, overrides);
        if (override >= 0) {
            left = UnihexProvider.Dimensions.left(overrides[override + 2]);
            right = UnihexProvider.Dimensions.right(overrides[override + 2]);
        } else {
            int calculatedDimensions = lines.calculateWidth();
            left = UnihexProvider.Dimensions.left(calculatedDimensions);
            right = UnihexProvider.Dimensions.right(calculatedDimensions);
        }
        glyphs.put(codepoint, new BrowserGlyph(lines, left, right));
    }

    private static UnihexProvider.LineData parseRows(
            byte[] bytes, int start, int bitWidth, int lineNumber) throws IOException {
        int digitsPerRow = bitWidth / 4;
        if (bitWidth == 8) {
            byte[] rows = new byte[16];
            for (int row = 0; row < rows.length; row++) {
                rows[row] = (byte) parseHexInt(
                        bytes, start + row * digitsPerRow, start + (row + 1) * digitsPerRow, lineNumber);
            }
            return new ByteLines(rows);
        }
        if (bitWidth == 16) {
            short[] rows = new short[16];
            for (int row = 0; row < rows.length; row++) {
                rows[row] = (short) parseHexInt(
                        bytes, start + row * digitsPerRow, start + (row + 1) * digitsPerRow, lineNumber);
            }
            return new ShortLines(rows);
        }
        int[] rows = new int[16];
        int shift = 32 - bitWidth;
        for (int row = 0; row < rows.length; row++) {
            rows[row] = parseHexInt(
                    bytes, start + row * digitsPerRow, start + (row + 1) * digitsPerRow, lineNumber)
                    << shift;
        }
        return new IntLines(rows, bitWidth);
    }

    /** Offset in {@code overrides} of the first range that holds {@code codepoint}, or -1. */
    private static int findOverrideDimensions(int codepoint, int[] overrides) {
        for (int offset = 0; offset < overrides.length; offset += 3) {
            if (codepoint >= overrides[offset] && codepoint <= overrides[offset + 1]) {
                return offset;
            }
        }
        return -1;
    }

    private static int parseHexInt(byte[] bytes, int start, int end, int lineNumber)
            throws IOException {
        int value = 0;
        for (int index = start; index < end; index++) {
            int digit = decodeHex(bytes[index]);
            if (digit < 0) {
                throw invalidLine(lineNumber, "invalid hexadecimal digit");
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    private static int decodeHex(byte value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        return -1;
    }

    private static IOException invalidLine(int lineNumber, String detail) {
        return new IOException("Invalid unihex line " + lineNumber + ": " + detail);
    }

    private record ByteLines(byte[] rows) implements UnihexProvider.LineData {
        @Override
        public int line(int row) {
            return rows[row] << 24;
        }

        @Override
        public int bitWidth() {
            return 8;
        }
    }

    private record ShortLines(short[] rows) implements UnihexProvider.LineData {
        @Override
        public int line(int row) {
            return rows[row] << 16;
        }

        @Override
        public int bitWidth() {
            return 16;
        }
    }

    private record IntLines(int[] rows, int bitWidth) implements UnihexProvider.LineData {
        @Override
        public int line(int row) {
            return rows[row];
        }
    }

    private record BrowserGlyph(UnihexProvider.LineData contents, int left, int right)
            implements UnbakedGlyph {
        @Override
        public GlyphInfo info() {
            return glyphInfo(left, right);
        }

        @Override
        public BakedGlyph bake(Stitcher stitcher) {
            return bakeGlyph(stitcher, contents, left, right);
        }
    }

    /**
     * A glyph of the table: its rows are read from the table when it is baked (it is its own
     * {@code LineData}), its dimensions were resolved when the provider was built.
     */
    private static final class TableGlyph implements UnbakedGlyph, UnihexProvider.LineData {
        private final UnihexGlyphTable.Zip zip;
        private final int glyph;
        private final int left;
        private final int right;

        private TableGlyph(UnihexGlyphTable.Zip zip, int glyph, int left, int right) {
            this.zip = zip;
            this.glyph = glyph;
            this.left = left;
            this.right = right;
        }

        @Override
        public int line(int row) {
            return zip.line(glyph, row);
        }

        @Override
        public int bitWidth() {
            return zip.bitWidth(glyph);
        }

        @Override
        public GlyphInfo info() {
            return glyphInfo(left, right);
        }

        @Override
        public BakedGlyph bake(Stitcher stitcher) {
            return bakeGlyph(stitcher, this, left, right);
        }
    }

    /** Vanilla's {@code UnihexProvider.Glyph.info()}: a new info per call, as there. */
    private static GlyphInfo glyphInfo(int left, int right) {
        int width = right - left + 1;
        return new GlyphInfo() {
            @Override
            public float getAdvance() {
                return width / 2 + 1;
            }

            @Override
            public float getShadowOffset() {
                return 0.5F;
            }

            @Override
            public float getBoldOffset() {
                return 0.5F;
            }
        };
    }

    /** Vanilla's {@code UnihexProvider.Glyph.bake}: the info and a 16 pixel high bitmap. */
    private static BakedGlyph bakeGlyph(
            UnbakedGlyph.Stitcher stitcher, UnihexProvider.LineData contents, int left, int right) {
        int width = right - left + 1;
        return stitcher.stitch(glyphInfo(left, right), new GlyphBitmap() {
            @Override
            public float getOversample() {
                return 2.0F;
            }

            @Override
            public int getPixelWidth() {
                return width;
            }

            @Override
            public int getPixelHeight() {
                return 16;
            }

            @Override
            public void upload(int x, int y, GpuTexture texture) {
                IntBuffer pixels = MemoryUtil.memAllocInt(width * 16);
                UnihexProvider.unpackBitsToBytes(pixels, contents, left, right);
                pixels.rewind();
                dev.gaius.browser.ops.VersionOps.INSTANCE.uploadGlyph(
                        texture, MemoryUtil.memByteBuffer(pixels), x, y, width, 16);
                MemoryUtil.memFree(pixels);
            }

            @Override
            public boolean isColored() {
                return true;
            }
        });
    }
}
