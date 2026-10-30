package org.teavm.classlib.java.util;

import java.io.Serializable;

public final class TUUID implements Serializable, Comparable<TUUID> {
    private final long mostSignificantBits;
    private final long leastSignificantBits;

    public TUUID(long mostSignificantBits, long leastSignificantBits) {
        this.mostSignificantBits = mostSignificantBits;
        this.leastSignificantBits = leastSignificantBits;
    }

    public static TUUID randomUUID() {
        byte[] bytes = new byte[16];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (Math.random() * 256);
        }
        bytes[6] = (byte) (bytes[6] & 15 | 64);
        bytes[8] = (byte) (bytes[8] & 63 | 128);
        return fromBytes(bytes);
    }

    public static TUUID nameUUIDFromBytes(byte[] name) {
        byte[] bytes = md5(name);
        bytes[6] = (byte) (bytes[6] & 15 | 48);
        bytes[8] = (byte) (bytes[8] & 63 | 128);
        return fromBytes(bytes);
    }

    public static TUUID fromString(String value) {
        if (value == null) {
            throw new NullPointerException();
        }
        String compact = value.replace("-", "");
        if (compact.length() != 32) {
            throw new IllegalArgumentException("Invalid UUID string: " + value);
        }
        return new TUUID(parseHex(compact, 0, 16), parseHex(compact, 16, 32));
    }

    public long getMostSignificantBits() {
        return mostSignificantBits;
    }

    public long getLeastSignificantBits() {
        return leastSignificantBits;
    }

    public int version() {
        return (int) (mostSignificantBits >>> 12) & 15;
    }

    public int variant() {
        return (leastSignificantBits & 0x8000000000000000L) == 0 ? 0
                : (leastSignificantBits & 0x4000000000000000L) == 0 ? 2 : 6;
    }

    @Override
    public String toString() {
        return hex(mostSignificantBits >>> 32, 8) + "-"
                + hex(mostSignificantBits >>> 16, 4) + "-"
                + hex(mostSignificantBits, 4) + "-"
                + hex(leastSignificantBits >>> 48, 4) + "-"
                + hex(leastSignificantBits, 12);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof TUUID
                && mostSignificantBits == ((TUUID) object).mostSignificantBits
                && leastSignificantBits == ((TUUID) object).leastSignificantBits;
    }

    @Override
    public int hashCode() {
        long hash = mostSignificantBits ^ leastSignificantBits;
        return (int) (hash >>> 32) ^ (int) hash;
    }

    @Override
    public int compareTo(TUUID other) {
        int most = Long.compare(mostSignificantBits, other.mostSignificantBits);
        return most != 0 ? most : Long.compare(leastSignificantBits, other.leastSignificantBits);
    }

    private static TUUID fromBytes(byte[] bytes) {
        long most = 0;
        long least = 0;
        for (int index = 0; index < 8; index++) {
            most = most << 8 | bytes[index] & 255L;
            least = least << 8 | bytes[index + 8] & 255L;
        }
        return new TUUID(most, least);
    }

    private static long parseHex(String value, int fromIndex, int toIndex) {
        long output = 0;
        for (int index = fromIndex; index < toIndex; index++) {
            int digit = Character.digit(value.charAt(index), 16);
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid UUID string: " + value);
            }
            output = output << 4 | digit;
        }
        return output;
    }

    private static String hex(long value, int digits) {
        char[] output = new char[digits];
        for (int index = digits - 1; index >= 0; index--) {
            int digit = (int) value & 15;
            output[index] = (char) (digit < 10 ? '0' + digit : 'a' + digit - 10);
            value >>>= 4;
        }
        return new String(output);
    }

    private static byte[] md5(byte[] input) {
        long bitLength = (long) input.length * 8;
        int length = ((input.length + 8) / 64 + 1) * 64;
        byte[] data = new byte[length];
        System.arraycopy(input, 0, data, 0, input.length);
        data[input.length] = (byte) 128;
        for (int index = 0; index < 8; index++) {
            data[length - 8 + index] = (byte) (bitLength >>> 8 * index);
        }

        int a0 = 0x67452301;
        int b0 = 0xefcdab89;
        int c0 = 0x98badcfe;
        int d0 = 0x10325476;
        int[] shifts = {
                7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,
                5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
                4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,
                6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21
        };

        for (int offset = 0; offset < data.length; offset += 64) {
            int[] words = new int[16];
            for (int index = 0; index < 16; index++) {
                int p = offset + index * 4;
                words[index] = data[p] & 255 | (data[p + 1] & 255) << 8
                        | (data[p + 2] & 255) << 16 | (data[p + 3] & 255) << 24;
            }
            int a = a0, b = b0, c = c0, d = d0;
            for (int index = 0; index < 64; index++) {
                int f;
                int word;
                if (index < 16) {
                    f = b & c | ~b & d;
                    word = index;
                } else if (index < 32) {
                    f = d & b | ~d & c;
                    word = 5 * index + 1 & 15;
                } else if (index < 48) {
                    f = b ^ c ^ d;
                    word = 3 * index + 5 & 15;
                } else {
                    f = c ^ (b | ~d);
                    word = 7 * index & 15;
                }
                int next = d;
                d = c;
                c = b;
                int constant = (int) (Math.abs(Math.sin(index + 1)) * 4294967296L);
                b += Integer.rotateLeft(a + f + constant + words[word], shifts[index]);
                a = next;
            }
            a0 += a;
            b0 += b;
            c0 += c;
            d0 += d;
        }

        byte[] output = new byte[16];
        write(output, 0, a0);
        write(output, 4, b0);
        write(output, 8, c0);
        write(output, 12, d0);
        return output;
    }

    private static void write(byte[] output, int offset, int value) {
        for (int index = 0; index < 4; index++) {
            output[offset + index] = (byte) (value >>> 8 * index);
        }
    }
}
