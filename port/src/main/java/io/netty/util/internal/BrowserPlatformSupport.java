package io.netty.util.internal;

/** Browser-safe replacements for Netty's Unsafe-backed byte-array stores. */
public final class BrowserPlatformSupport {
    private BrowserPlatformSupport() {
    }

    public static void putInt(byte[] bytes, int index, int value) {
        checkRange(bytes, index, Integer.BYTES);
        bytes[index] = (byte) value;
        bytes[index + 1] = (byte) (value >>> 8);
        bytes[index + 2] = (byte) (value >>> 16);
        bytes[index + 3] = (byte) (value >>> 24);
    }

    public static void putLong(byte[] bytes, int index, long value) {
        checkRange(bytes, index, Long.BYTES);
        for (int offset = 0; offset < Long.BYTES; offset++) {
            bytes[index + offset] = (byte) (value >>> (offset * Byte.SIZE));
        }
    }

    private static void checkRange(byte[] bytes, int index, int length) {
        if (bytes == null || index < 0 || index > bytes.length - length) {
            throw new IndexOutOfBoundsException("byte array write outside bounds");
        }
    }
}
