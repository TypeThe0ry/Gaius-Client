package dev.gaius.browser;

import java.util.Arrays;

/** Stateful AES-128/CFB8 used by the Minecraft online-mode packet stream. */
public final class BrowserAesCfb8 {
    public static final int ENCRYPT_MODE = 1;
    public static final int DECRYPT_MODE = 2;

    private static final int[] SBOX = new int[256];
    private static final int[] TE0 = new int[256];
    private static final int[] TE1 = new int[256];
    private static final int[] TE2 = new int[256];
    private static final int[] TE3 = new int[256];

    static {
        for (int value = 0; value < 256; value++) {
            int inverse = value == 0 ? 0 : gfPow(value, 254);
            int substituted = inverse
                    ^ rotateByte(inverse, 1)
                    ^ rotateByte(inverse, 2)
                    ^ rotateByte(inverse, 3)
                    ^ rotateByte(inverse, 4)
                    ^ 0x63;
            int s = substituted & 0xff;
            int s2 = xtime(s);
            int s3 = s2 ^ s;
            SBOX[value] = s;
            TE0[value] = (s2 << 24) | (s << 16) | (s << 8) | s3;
            TE1[value] = (s3 << 24) | (s2 << 16) | (s << 8) | s;
            TE2[value] = (s << 24) | (s3 << 16) | (s2 << 8) | s;
            TE3[value] = (s << 24) | (s << 16) | (s3 << 8) | s2;
        }
    }

    private final boolean encrypting;
    private final byte[] feedback = new byte[16];
    private final int[] roundKeys = new int[44];

    public BrowserAesCfb8(int mode, byte[] key) {
        this(mode, key, key);
    }

    public BrowserAesCfb8(int mode, byte[] key, byte[] iv) {
        if (mode != ENCRYPT_MODE && mode != DECRYPT_MODE) {
            throw new IllegalArgumentException("Unsupported AES/CFB8 mode: " + mode);
        }
        if (key == null || key.length != 16) {
            throw new IllegalArgumentException("Minecraft AES key must be 16 bytes");
        }
        if (iv == null || iv.length != 16) {
            throw new IllegalArgumentException("Minecraft AES IV must be 16 bytes");
        }
        encrypting = mode == ENCRYPT_MODE;
        System.arraycopy(iv, 0, feedback, 0, feedback.length);
        expandKey(key);
    }

    public int getOutputSize(int inputLength) {
        return Math.max(0, inputLength);
    }

    public byte[] update(byte[] input) {
        if (input == null || input.length == 0) {
            return new byte[0];
        }
        byte[] output = new byte[input.length];
        update(input, 0, input.length, output, 0);
        return output;
    }

    public int update(byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset) {
        if (input == null || output == null) {
            throw new NullPointerException("AES/CFB8 buffers must not be null");
        }
        if (inputOffset < 0 || inputLength < 0 || inputOffset + inputLength > input.length
                || outputOffset < 0 || outputOffset + inputLength > output.length) {
            throw new IndexOutOfBoundsException("AES/CFB8 buffer range is invalid");
        }
        for (int index = 0; index < inputLength; index++) {
            int source = input[inputOffset + index] & 0xff;
            int transformed = source ^ encryptFirstFeedbackByte();
            output[outputOffset + index] = (byte) transformed;
            System.arraycopy(feedback, 1, feedback, 0, feedback.length - 1);
            feedback[feedback.length - 1] = (byte) (encrypting ? transformed : source);
        }
        return inputLength;
    }

    public byte[] feedbackForTesting() {
        return Arrays.copyOf(feedback, feedback.length);
    }

    private int encryptFirstFeedbackByte() {
        int s0 = word(feedback, 0) ^ roundKeys[0];
        int s1 = word(feedback, 4) ^ roundKeys[1];
        int s2 = word(feedback, 8) ^ roundKeys[2];
        int s3 = word(feedback, 12) ^ roundKeys[3];
        for (int round = 1; round < 10; round++) {
            int key = round * 4;
            int t0 = TE0[s0 >>> 24]
                    ^ TE1[(s1 >>> 16) & 0xff]
                    ^ TE2[(s2 >>> 8) & 0xff]
                    ^ TE3[s3 & 0xff]
                    ^ roundKeys[key];
            int t1 = TE0[s1 >>> 24]
                    ^ TE1[(s2 >>> 16) & 0xff]
                    ^ TE2[(s3 >>> 8) & 0xff]
                    ^ TE3[s0 & 0xff]
                    ^ roundKeys[key + 1];
            int t2 = TE0[s2 >>> 24]
                    ^ TE1[(s3 >>> 16) & 0xff]
                    ^ TE2[(s0 >>> 8) & 0xff]
                    ^ TE3[s1 & 0xff]
                    ^ roundKeys[key + 2];
            int t3 = TE0[s3 >>> 24]
                    ^ TE1[(s0 >>> 16) & 0xff]
                    ^ TE2[(s1 >>> 8) & 0xff]
                    ^ TE3[s2 & 0xff]
                    ^ roundKeys[key + 3];
            s0 = t0;
            s1 = t1;
            s2 = t2;
            s3 = t3;
        }
        return SBOX[s0 >>> 24] ^ (roundKeys[40] >>> 24);
    }

    private void expandKey(byte[] key) {
        for (int index = 0; index < 4; index++) {
            roundKeys[index] = word(key, index * 4);
        }
        int rcon = 1;
        for (int index = 4; index < roundKeys.length; index++) {
            int value = roundKeys[index - 1];
            if ((index & 3) == 0) {
                value = subWord(Integer.rotateLeft(value, 8)) ^ (rcon << 24);
                rcon = xtime(rcon);
            }
            roundKeys[index] = roundKeys[index - 4] ^ value;
        }
    }

    private static int subWord(int value) {
        return (SBOX[value >>> 24] << 24)
                | (SBOX[(value >>> 16) & 0xff] << 16)
                | (SBOX[(value >>> 8) & 0xff] << 8)
                | SBOX[value & 0xff];
    }

    private static int word(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static int rotateByte(int value, int amount) {
        return ((value << amount) | (value >>> (8 - amount))) & 0xff;
    }

    private static int gfPow(int value, int exponent) {
        int result = 1;
        int factor = value;
        int remaining = exponent;
        while (remaining > 0) {
            if ((remaining & 1) != 0) {
                result = gfMultiply(result, factor);
            }
            factor = gfMultiply(factor, factor);
            remaining >>>= 1;
        }
        return result;
    }

    private static int gfMultiply(int left, int right) {
        int result = 0;
        int a = left;
        int b = right;
        for (int bit = 0; bit < 8; bit++) {
            if ((b & 1) != 0) {
                result ^= a;
            }
            a = xtime(a);
            b >>>= 1;
        }
        return result & 0xff;
    }

    private static int xtime(int value) {
        int shifted = value << 1;
        if ((value & 0x80) != 0) {
            shifted ^= 0x11b;
        }
        return shifted & 0xff;
    }
}
