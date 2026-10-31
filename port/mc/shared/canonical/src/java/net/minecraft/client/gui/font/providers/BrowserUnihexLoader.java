package net.minecraft.client.gui.font.providers;

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

/** Bulk parser for Mojang unihex fonts that avoids byte-at-a-time browser I/O. */
public final class BrowserUnihexLoader {
    private BrowserUnihexLoader() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static UnihexProvider load(InputStream input, List<?> rawOverrides) throws IOException {
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
        int dimensions = findOverrideDimensions(codepoint, overrides);
        if (dimensions >= 0) {
            left = UnihexProvider.Dimensions.left(dimensions);
            right = UnihexProvider.Dimensions.right(dimensions);
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

    private static int findOverrideDimensions(int codepoint, int[] overrides) {
        for (int offset = 0; offset < overrides.length; offset += 3) {
            if (codepoint >= overrides[offset] && codepoint <= overrides[offset + 1]) {
                return overrides[offset + 2];
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
        private int width() {
            return right - left + 1;
        }

        @Override
        public GlyphInfo info() {
            return new GlyphInfo() {
                @Override
                public float getAdvance() {
                    return width() / 2 + 1;
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

        @Override
        public BakedGlyph bake(Stitcher stitcher) {
            return stitcher.stitch(info(), new GlyphBitmap() {
                @Override
                public float getOversample() {
                    return 2.0F;
                }

                @Override
                public int getPixelWidth() {
                    return width();
                }

                @Override
                public int getPixelHeight() {
                    return 16;
                }

                @Override
                public void upload(int x, int y, GpuTexture texture) {
                    IntBuffer pixels = MemoryUtil.memAllocInt(width() * 16);
                    UnihexProvider.unpackBitsToBytes(pixels, contents, left, right);
                    pixels.rewind();
                    dev.gaius.browser.ops.VersionOps.INSTANCE.uploadGlyph(
                            texture, MemoryUtil.memByteBuffer(pixels), x, y, width(), 16);
                    MemoryUtil.memFree(pixels);
                }

                @Override
                public boolean isColored() {
                    return true;
                }
            });
        }
    }
}
