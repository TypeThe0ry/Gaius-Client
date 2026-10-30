package org.teavm.classlib.java.util;

public final class THexFormat {
    private static final THexFormat DEFAULT = new THexFormat("", "", "", false);
    private final String delimiter;
    private final String prefix;
    private final String suffix;
    private final boolean upperCase;

    private THexFormat(String delimiter, String prefix, String suffix, boolean upperCase) {
        this.delimiter = delimiter;
        this.prefix = prefix;
        this.suffix = suffix;
        this.upperCase = upperCase;
    }

    public static THexFormat of() {
        return DEFAULT;
    }

    public static THexFormat ofDelimiter(String delimiter) {
        return DEFAULT.withDelimiter(delimiter);
    }

    public THexFormat withDelimiter(String value) {
        return new THexFormat(require(value), prefix, suffix, upperCase);
    }

    public THexFormat withPrefix(String value) {
        return new THexFormat(delimiter, require(value), suffix, upperCase);
    }

    public THexFormat withSuffix(String value) {
        return new THexFormat(delimiter, prefix, require(value), upperCase);
    }

    public THexFormat withUpperCase() {
        return new THexFormat(delimiter, prefix, suffix, true);
    }

    public THexFormat withLowerCase() {
        return new THexFormat(delimiter, prefix, suffix, false);
    }

    public String delimiter() {
        return delimiter;
    }

    public String prefix() {
        return prefix;
    }

    public String suffix() {
        return suffix;
    }

    public boolean isUpperCase() {
        return upperCase;
    }

    public String formatHex(byte[] bytes) {
        return formatHex(bytes, 0, bytes.length);
    }

    public String formatHex(byte[] bytes, int fromIndex, int toIndex) {
        checkRange(bytes.length, fromIndex, toIndex);
        StringBuilder output = new StringBuilder();
        for (int index = fromIndex; index < toIndex; index++) {
            if (index > fromIndex) {
                output.append(delimiter);
            }
            output.append(prefix).append(toHighHexDigit(bytes[index]))
                    .append(toLowHexDigit(bytes[index])).append(suffix);
        }
        return output.toString();
    }

    public byte[] parseHex(CharSequence text) {
        return parseHex(text, 0, text.length());
    }

    public byte[] parseHex(CharSequence text, int fromIndex, int toIndex) {
        checkRange(text.length(), fromIndex, toIndex);
        String value = text.subSequence(fromIndex, toIndex).toString();
        if (value.isEmpty()) {
            return new byte[0];
        }
        if (delimiter.isEmpty() && prefix.isEmpty() && suffix.isEmpty()) {
            if ((value.length() & 1) != 0) {
                throw new IllegalArgumentException("odd number of hexadecimal digits");
            }
            byte[] output = new byte[value.length() / 2];
            for (int index = 0; index < output.length; index++) {
                output[index] = (byte) fromHexDigits(value, index * 2, index * 2 + 2);
            }
            return output;
        }
        String[] parts = delimiter.isEmpty() ? new String[] {value} : value.split(delimiter);
        byte[] output = new byte[parts.length];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (!part.startsWith(prefix) || !part.endsWith(suffix)) {
                throw new IllegalArgumentException("invalid hexadecimal literal");
            }
            String digits = part.substring(prefix.length(), part.length() - suffix.length());
            if (digits.length() != 2) {
                throw new IllegalArgumentException("expected two hexadecimal digits");
            }
            output[index] = (byte) fromHexDigits(digits);
        }
        return output;
    }

    public byte[] parseHex(char[] text, int fromIndex, int toIndex) {
        return parseHex(new String(text), fromIndex, toIndex);
    }

    public char toLowHexDigit(int value) {
        return digit(value);
    }

    public char toHighHexDigit(int value) {
        return digit(value >>> 4);
    }

    public String toHexDigits(byte value) {
        return "" + toHighHexDigit(value) + toLowHexDigit(value);
    }

    public String toHexDigits(char value) {
        return toHexDigits(value, 4);
    }

    public String toHexDigits(short value) {
        return toHexDigits(value, 4);
    }

    public String toHexDigits(int value) {
        return toHexDigits(value, 8);
    }

    public String toHexDigits(long value) {
        return toHexDigits(value, 16);
    }

    public String toHexDigits(long value, int digits) {
        if (digits < 0 || digits > 16) {
            throw new IllegalArgumentException("digits out of range");
        }
        char[] output = new char[digits];
        for (int index = digits - 1; index >= 0; index--) {
            output[index] = digit((int) value);
            value >>>= 4;
        }
        return new String(output);
    }

    public static boolean isHexDigit(int value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }

    public static int fromHexDigit(int value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        throw new NumberFormatException("not a hexadecimal digit");
    }

    public static int fromHexDigits(CharSequence text) {
        return fromHexDigits(text, 0, text.length());
    }

    public static int fromHexDigits(CharSequence text, int fromIndex, int toIndex) {
        return (int) fromHexDigitsToLong(text, fromIndex, toIndex);
    }

    public static long fromHexDigitsToLong(CharSequence text) {
        return fromHexDigitsToLong(text, 0, text.length());
    }

    public static long fromHexDigitsToLong(CharSequence text, int fromIndex, int toIndex) {
        checkRange(text.length(), fromIndex, toIndex);
        if (toIndex - fromIndex > 16) {
            throw new IllegalArgumentException("value exceeds 64 bits");
        }
        long output = 0;
        for (int index = fromIndex; index < toIndex; index++) {
            output = output << 4 | fromHexDigit(text.charAt(index));
        }
        return output;
    }

    private char digit(int value) {
        int nibble = value & 15;
        return (char) (nibble < 10 ? '0' + nibble
                : (upperCase ? 'A' : 'a') + nibble - 10);
    }

    private static String require(String value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return value;
    }

    private static void checkRange(int length, int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex < fromIndex || toIndex > length) {
            throw new IndexOutOfBoundsException();
        }
    }
}
