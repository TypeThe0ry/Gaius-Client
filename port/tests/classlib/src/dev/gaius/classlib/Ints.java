package dev.gaius.classlib;

/** Integer and Long: string conversion, parsing, bit operations and unsigned arithmetic. */
final class Ints {
    private Ints() {
    }

    static void run() {
        int[] ints = {0, 1, -1, 42, -42, 255, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x12345678, 0x80000001};
        for (int v : ints) {
            Out.check("int.toString(" + v + ")", () -> Integer.toString(v));
            Out.check("int.hex(" + v + ")", () -> Integer.toHexString(v) + " " + Integer.toOctalString(v) + " "
                    + Integer.toBinaryString(v));
            Out.check("int.radix36(" + v + ")", () -> Integer.toString(v, 36) + " " + Integer.toString(v, 7));
            Out.check("int.unsigned(" + v + ")", () -> Integer.toUnsignedString(v) + " " + Integer.toUnsignedLong(v)
                    + " " + Integer.divideUnsigned(v, 7) + " " + Integer.remainderUnsigned(v, 7));
            Out.check("int.bits(" + v + ")", () -> Integer.bitCount(v) + " " + Integer.numberOfLeadingZeros(v) + " "
                    + Integer.numberOfTrailingZeros(v) + " " + Integer.highestOneBit(v) + " " + Integer.lowestOneBit(v)
                    + " " + Integer.reverse(v) + " " + Integer.reverseBytes(v) + " " + Integer.rotateLeft(v, 7)
                    + " " + Integer.signum(v));
            Out.check("int.compare(" + v + ")", () -> Integer.compare(v, 0) + " " + Integer.compareUnsigned(v, 0));
        }
        long[] longs = {0, 1, -1, 42, Long.MAX_VALUE, Long.MIN_VALUE, 0x123456789ABCDEFL, 0x8000000000000001L,
            4294967296L, -4294967296L};
        for (long v : longs) {
            Out.check("long.toString(" + v + ")", () -> Long.toString(v));
            Out.check("long.hex(" + v + ")", () -> Long.toHexString(v) + " " + Long.toOctalString(v) + " "
                    + Long.toBinaryString(v));
            Out.check("long.radix36(" + v + ")", () -> Long.toString(v, 36));
            Out.check("long.unsigned(" + v + ")", () -> Long.toUnsignedString(v) + " " + Long.divideUnsigned(v, 7)
                    + " " + Long.remainderUnsigned(v, 7));
            Out.check("long.bits(" + v + ")", () -> Long.bitCount(v) + " " + Long.numberOfLeadingZeros(v) + " "
                    + Long.numberOfTrailingZeros(v) + " " + Long.highestOneBit(v) + " " + Long.reverse(v) + " "
                    + Long.reverseBytes(v) + " " + Long.rotateRight(v, 13) + " " + Long.signum(v));
        }
        String[] parse = {"0", "-0", "+5", "-2147483648", "2147483648", "2147483647", "  1", "1 ", "", "-", "+",
            "0x10", "007", "1_000", "١٢", "99999999999", "-9223372036854775808", "9223372036854775808",
            "ffffffff", "7fffffff", "zz"};
        for (int i = 0; i < parse.length; i++) {
            String s = parse[i];
            Out.check("parseInt." + i, () -> Integer.parseInt(s));
            Out.check("parseLong." + i, () -> Long.parseLong(s));
            Out.check("parseInt16." + i, () -> Integer.parseInt(s, 16));
            Out.check("parseUnsignedInt16." + i, () -> Integer.parseUnsignedInt(s, 16));
            Out.check("parseInt36." + i, () -> Integer.parseInt(s, 36));
            Out.check("decode." + i, () -> Integer.decode(s));
            Out.check("valueOfLong." + i, () -> Long.valueOf(s));
        }
        Out.check("integerCache", () -> (Integer.valueOf(127) == Integer.valueOf(127)) + " "
                + Integer.valueOf(1000).equals(Integer.valueOf(1000)));
        Out.check("char.toString", () -> Character.toString(0x1F600).length() + " " + Character.toString('x'));
        Out.check("short.parse", () -> Short.parseShort("-32768") + " " + Byte.parseByte("7f", 16));
        Out.check("short.overflow", () -> Short.parseShort("32768"));
    }
}
