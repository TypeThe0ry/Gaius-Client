package dev.gaius.harness.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A generated 5x7 bitmap font in a 128x64 RGBA atlas of 8x8 cells (ASCII 32..127, 16 per row). */
public final class Glyphs {
    public static final int ADVANCE = 6;
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!.-:?/";
    private static final String[] ROWS = {
        // A      B      C      D      E      F      G      H      I      J      K      L      M
        " ### ", "#### ", " ### ", "#### ", "#####", "#####", " ### ", "#   #", " ### ", "  ###", "#   #", "#    ", "#   #",
        "#   #", "#   #", "#   #", "#   #", "#    ", "#    ", "#    ", "#   #", "  #  ", "   # ", "#  # ", "#    ", "## ##",
        "#   #", "#   #", "#    ", "#   #", "#    ", "#    ", "#    ", "#   #", "  #  ", "   # ", "# #  ", "#    ", "# # #",
        "#####", "#### ", "#    ", "#   #", "#### ", "#### ", "# ###", "#####", "  #  ", "   # ", "##   ", "#    ", "#   #",
        "#   #", "#   #", "#    ", "#   #", "#    ", "#    ", "#   #", "#   #", "  #  ", "#  # ", "# #  ", "#    ", "#   #",
        "#   #", "#   #", "#   #", "#   #", "#    ", "#    ", "#   #", "#   #", "  #  ", "#  # ", "#  # ", "#    ", "#   #",
        "#   #", "#### ", " ### ", "#### ", "#####", "#    ", " ### ", "#   #", " ### ", " ##  ", "#   #", "#####", "#   #",
        // N      O      P      Q      R      S      T      U      V      W      X      Y      Z
        "#   #", " ### ", "#### ", " ### ", "#### ", " ####", "#####", "#   #", "#   #", "#   #", "#   #", "#   #", "#####",
        "##  #", "#   #", "#   #", "#   #", "#   #", "#    ", "  #  ", "#   #", "#   #", "#   #", " # # ", " # # ", "    #",
        "# # #", "#   #", "#   #", "#   #", "#   #", "#    ", "  #  ", "#   #", "#   #", "#   #", "  #  ", "  #  ", "   # ",
        "#  ##", "#   #", "#### ", "#   #", "#### ", " ### ", "  #  ", "#   #", "#   #", "# # #", "  #  ", "  #  ", "  #  ",
        "#   #", "#   #", "#    ", "# # #", "# #  ", "    #", "  #  ", "#   #", " # # ", "# # #", " # # ", "  #  ", " #   ",
        "#   #", "#   #", "#    ", "#  # ", "#  # ", "    #", "  #  ", "#   #", " # # ", "## ##", "#   #", "  #  ", "#    ",
        "#   #", " ### ", "#    ", " ## #", "#   #", "#### ", "  #  ", " ### ", "  #  ", "#   #", "#   #", "  #  ", "#####",
        // 0      1      2      3      4      5      6      7      8      9      !      .      -      :      ?      /
        " ### ", "  #  ", " ### ", " ### ", "   # ", "#####", " ### ", "#####", " ### ", " ### ", "  #  ", "     ", "     ", "     ", " ### ", "    #",
        "#   #", " ##  ", "#   #", "#   #", "  ## ", "#    ", "#    ", "    #", "#   #", "#   #", "  #  ", "     ", "     ", "  #  ", "#   #", "   # ",
        "#  ##", "  #  ", "    #", "    #", " # # ", "#### ", "#    ", "   # ", "#   #", "#   #", "  #  ", "     ", "     ", "     ", "    #", "   # ",
        "# # #", "  #  ", "  ## ", "  ## ", "#  # ", "    #", "#### ", "  #  ", " ### ", " ####", "  #  ", "     ", "#####", "     ", "   # ", "  #  ",
        "##  #", "  #  ", " #   ", "    #", "#####", "    #", "#   #", "  #  ", "#   #", "    #", "  #  ", "     ", "     ", "     ", "  #  ", " #   ",
        "#   #", "  #  ", "#    ", "#   #", "   # ", "#   #", "#   #", "  #  ", "#   #", "    #", "     ", "  #  ", "     ", "  #  ", "     ", " #   ",
        " ### ", " ### ", "#####", " ### ", "   # ", " ### ", " ### ", "  #  ", " ### ", " ### ", "  #  ", "  #  ", "     ", "     ", "  #  ", "#    "
    };

    private Glyphs() {
    }

    /** Whether pixel (x, y) of the 5x7 glyph of {@code c} is set. */
    public static boolean pixel(char c, int x, int y) {
        int index = CHARS.indexOf(Character.toUpperCase(c));
        if (index < 0 || x < 0 || x >= 5 || y < 0 || y >= 7) {
            return false;
        }
        int block;
        int column;
        if (index < 13) {
            block = 0;
            column = index;
        } else if (index < 26) {
            block = 1;
            column = index - 13;
        } else {
            block = 2;
            column = index - 26;
        }
        int perRow = block == 2 ? 16 : 13;
        int base = block == 0 ? 0 : block == 1 ? 7 * 13 : 14 * 13;
        return ROWS[base + y * perRow + column].charAt(x) == '#';
    }

    /** A pixel of {@code word} set in glyph cells 6 pixels apart (the logo). */
    public static boolean letterPixel(String word, int x, int y) {
        if (x < 0 || y < 0) {
            return false;
        }
        int letter = x / ADVANCE;
        return letter < word.length() && pixel(word.charAt(letter), x % ADVANCE, y);
    }

    /** The atlas cell of a character, or -1 for none (space). */
    public static int cell(char c) {
        if (c <= ' ' || c > 127) {
            return -1;
        }
        return Character.toUpperCase(c) - 32;
    }

    public static ByteBuffer atlas() {
        int width = 128;
        int height = 64;
        ByteBuffer data = buffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int code = 32 + (y / 8) * 16 + x / 8;
                boolean on = pixel((char) code, x % 8, y % 8);
                byte value = on ? (byte) 255 : 0;
                data.put((byte) 255).put((byte) 255).put((byte) 255).put(value);
            }
        }
        data.flip();
        return data;
    }

    /** A direct native-order buffer (the GL shim and the WebGPU glue read direct buffers). */
    public static ByteBuffer buffer(int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }
}
