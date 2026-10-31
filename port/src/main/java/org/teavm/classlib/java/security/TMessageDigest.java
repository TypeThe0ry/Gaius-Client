package org.teavm.classlib.java.security;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Synchronous pure-Java message digests for browser builds.
 */
public class TMessageDigest implements Cloneable {
    private final String algorithm;
    private byte[] input = new byte[64];
    private int size;

    protected TMessageDigest(String algorithm) {
        this.algorithm = algorithm;
    }

    public static TMessageDigest getInstance(String algorithm) throws TNoSuchAlgorithmException {
        String normalized = normalize(algorithm);
        if (!normalized.equals("MD5")
                && !normalized.equals("SHA-1")
                && !normalized.equals("SHA-256")) {
            throw new TNoSuchAlgorithmException(algorithm);
        }
        return new TMessageDigest(normalized);
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public int getDigestLength() {
        return algorithm.equals("MD5") ? 16 : algorithm.equals("SHA-1") ? 20 : 32;
    }

    public void update(byte value) {
        ensureCapacity(size + 1);
        input[size++] = value;
    }

    public void update(byte[] values) {
        update(values, 0, values.length);
    }

    public void update(byte[] values, int offset, int length) {
        if (offset < 0 || length < 0 || offset > values.length - length) {
            throw new IndexOutOfBoundsException();
        }
        ensureCapacity(size + length);
        System.arraycopy(values, offset, input, size, length);
        size += length;
    }

    public void update(ByteBuffer buffer) {
        int remaining = buffer.remaining();
        ensureCapacity(size + remaining);
        buffer.get(input, size, remaining);
        size += remaining;
    }

    public byte[] digest() {
        byte[] result = switch (algorithm) {
            case "MD5" -> md5(input, size);
            case "SHA-1" -> sha1(input, size);
            default -> sha256(input, size);
        };
        reset();
        return result;
    }

    public byte[] digest(byte[] values) {
        update(values);
        return digest();
    }

    public int digest(byte[] output, int offset, int length) throws TDigestException {
        int digestLength = getDigestLength();
        if (length < digestLength) {
            throw new TDigestException("Output buffer is too small");
        }
        if (offset < 0 || offset > output.length - digestLength) {
            throw new TDigestException("Invalid output offset");
        }
        byte[] value = digest();
        System.arraycopy(value, 0, output, offset, value.length);
        return value.length;
    }

    public void reset() {
        size = 0;
    }

    @Override
    public TMessageDigest clone() {
        TMessageDigest copy = new TMessageDigest(algorithm);
        copy.input = Arrays.copyOf(input, input.length);
        copy.size = size;
        return copy;
    }

    public static boolean isEqual(byte[] left, byte[] right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.length == 0 || right.length == 0) {
            return left.length == right.length;
        }
        int difference = left.length ^ right.length;
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            difference |= left[index % left.length] ^ right[index % right.length];
        }
        return difference == 0;
    }

    private void ensureCapacity(int capacity) {
        if (capacity > input.length) {
            input = Arrays.copyOf(input, Math.max(capacity, input.length * 2));
        }
    }

    private static String normalize(String algorithm) {
        String value = algorithm.toUpperCase(Locale.ROOT).replace("_", "-");
        if (value.equals("SHA") || value.equals("SHA1")) {
            return "SHA-1";
        }
        if (value.equals("SHA256")) {
            return "SHA-256";
        }
        return value;
    }

    private static byte[] padded(byte[] input, int length, boolean littleEndianLength) {
        int paddedLength = ((length + 9 + 63) / 64) * 64;
        byte[] data = Arrays.copyOf(input, paddedLength);
        data[length] = (byte) 0x80;
        long bits = (long) length * 8;
        for (int index = 0; index < 8; index++) {
            int target = littleEndianLength ? paddedLength - 8 + index : paddedLength - 1 - index;
            data[target] = (byte) (bits >>> (index * 8));
        }
        return data;
    }

    private static byte[] md5(byte[] input, int length) {
        int[] shifts = {
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
        };
        int[] constants = new int[64];
        for (int index = 0; index < constants.length; index++) {
            constants[index] = (int) (long) (Math.abs(Math.sin(index + 1)) * 0x1_0000_0000L);
        }
        int a0 = 0x67452301;
        int b0 = 0xefcdab89;
        int c0 = 0x98badcfe;
        int d0 = 0x10325476;
        byte[] data = padded(input, length, true);
        int[] words = new int[16];
        for (int offset = 0; offset < data.length; offset += 64) {
            for (int index = 0; index < 16; index++) {
                words[index] = littleEndianInt(data, offset + index * 4);
            }
            int a = a0;
            int b = b0;
            int c = c0;
            int d = d0;
            for (int index = 0; index < 64; index++) {
                int f;
                int word;
                if (index < 16) {
                    f = (b & c) | (~b & d);
                    word = index;
                } else if (index < 32) {
                    f = (d & b) | (~d & c);
                    word = (5 * index + 1) & 15;
                } else if (index < 48) {
                    f = b ^ c ^ d;
                    word = (3 * index + 5) & 15;
                } else {
                    f = c ^ (b | ~d);
                    word = (7 * index) & 15;
                }
                int next = d;
                d = c;
                c = b;
                b += Integer.rotateLeft(a + f + constants[index] + words[word], shifts[index]);
                a = next;
            }
            a0 += a;
            b0 += b;
            c0 += c;
            d0 += d;
        }
        byte[] result = new byte[16];
        writeLittleEndian(result, 0, a0);
        writeLittleEndian(result, 4, b0);
        writeLittleEndian(result, 8, c0);
        writeLittleEndian(result, 12, d0);
        return result;
    }

    private static byte[] sha1(byte[] input, int length) {
        int h0 = 0x67452301;
        int h1 = 0xefcdab89;
        int h2 = 0x98badcfe;
        int h3 = 0x10325476;
        int h4 = 0xc3d2e1f0;
        byte[] data = padded(input, length, false);
        int[] words = new int[80];
        for (int offset = 0; offset < data.length; offset += 64) {
            for (int index = 0; index < 16; index++) {
                words[index] = bigEndianInt(data, offset + index * 4);
            }
            for (int index = 16; index < 80; index++) {
                words[index] = Integer.rotateLeft(
                        words[index - 3] ^ words[index - 8] ^ words[index - 14] ^ words[index - 16], 1);
            }
            int a = h0;
            int b = h1;
            int c = h2;
            int d = h3;
            int e = h4;
            for (int index = 0; index < 80; index++) {
                int f;
                int k;
                if (index < 20) {
                    f = (b & c) | (~b & d);
                    k = 0x5a827999;
                } else if (index < 40) {
                    f = b ^ c ^ d;
                    k = 0x6ed9eba1;
                } else if (index < 60) {
                    f = (b & c) | (b & d) | (c & d);
                    k = 0x8f1bbcdc;
                } else {
                    f = b ^ c ^ d;
                    k = 0xca62c1d6;
                }
                int next = Integer.rotateLeft(a, 5) + f + e + k + words[index];
                e = d;
                d = c;
                c = Integer.rotateLeft(b, 30);
                b = a;
                a = next;
            }
            h0 += a;
            h1 += b;
            h2 += c;
            h3 += d;
            h4 += e;
        }
        byte[] result = new byte[20];
        writeBigEndian(result, 0, h0);
        writeBigEndian(result, 4, h1);
        writeBigEndian(result, 8, h2);
        writeBigEndian(result, 12, h3);
        writeBigEndian(result, 16, h4);
        return result;
    }

    private static byte[] sha256(byte[] input, int length) {
        int[] constants = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
            0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
            0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
            0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
            0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
        };
        int[] hash = {
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        };
        byte[] data = padded(input, length, false);
        int[] words = new int[64];
        for (int offset = 0; offset < data.length; offset += 64) {
            for (int index = 0; index < 16; index++) {
                words[index] = bigEndianInt(data, offset + index * 4);
            }
            for (int index = 16; index < 64; index++) {
                int x = words[index - 15];
                int y = words[index - 2];
                int s0 = Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3);
                int s1 = Integer.rotateRight(y, 17) ^ Integer.rotateRight(y, 19) ^ (y >>> 10);
                words[index] = words[index - 16] + s0 + words[index - 7] + s1;
            }
            int a = hash[0];
            int b = hash[1];
            int c = hash[2];
            int d = hash[3];
            int e = hash[4];
            int f = hash[5];
            int g = hash[6];
            int h = hash[7];
            for (int index = 0; index < 64; index++) {
                int sum1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11)
                        ^ Integer.rotateRight(e, 25);
                int choose = (e & f) ^ (~e & g);
                int temp1 = h + sum1 + choose + constants[index] + words[index];
                int sum0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13)
                        ^ Integer.rotateRight(a, 22);
                int majority = (a & b) ^ (a & c) ^ (b & c);
                int temp2 = sum0 + majority;
                h = g;
                g = f;
                f = e;
                e = d + temp1;
                d = c;
                c = b;
                b = a;
                a = temp1 + temp2;
            }
            hash[0] += a;
            hash[1] += b;
            hash[2] += c;
            hash[3] += d;
            hash[4] += e;
            hash[5] += f;
            hash[6] += g;
            hash[7] += h;
        }
        byte[] result = new byte[32];
        for (int index = 0; index < hash.length; index++) {
            writeBigEndian(result, index * 4, hash[index]);
        }
        return result;
    }

    private static int littleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | (data[offset + 1] & 0xff) << 8
                | (data[offset + 2] & 0xff) << 16
                | data[offset + 3] << 24;
    }

    private static int bigEndianInt(byte[] data, int offset) {
        return data[offset] << 24
                | (data[offset + 1] & 0xff) << 16
                | (data[offset + 2] & 0xff) << 8
                | (data[offset + 3] & 0xff);
    }

    private static void writeLittleEndian(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
        output[offset + 2] = (byte) (value >>> 16);
        output[offset + 3] = (byte) (value >>> 24);
    }

    private static void writeBigEndian(byte[] output, int offset, int value) {
        output[offset] = (byte) (value >>> 24);
        output[offset + 1] = (byte) (value >>> 16);
        output[offset + 2] = (byte) (value >>> 8);
        output[offset + 3] = (byte) value;
    }
}
