package dev.gaius.classlib.repro;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Direct ByteBuffer round trip as TextureUtil.readResource, PngInfo and STBImage do it.
 * TeaVM 0.15's TByteBufferNative.getLong(int) advanced the position (an absolute read), so STB
 * decoded from offset 16 ("not a PNG image"); TeaVMClasslibWasmGcPatcher.
 */
public final class BufTest {
    private BufTest() {
    }

    static int read32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    static long read64(byte[] bytes, int offset) {
        return ((long) read32(bytes, offset) << 32) | (read32(bytes, offset + 4) & 0xFFFFFFFFL);
    }

    public static void main(String[] args) {
        byte[] png = new byte[64];
        long header = 0x89504E470D0A1A0AL;
        for (int i = 0; i < 8; i++) {
            png[i] = (byte) (header >>> (56 - 8 * i));
        }
        for (int i = 8; i < png.length; i++) {
            png[i] = (byte) i;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        buffer.put(png, 0, 16);
        ByteBuffer grown = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        buffer.flip();
        grown.put(buffer);
        grown.put(png, 16, png.length - 16);
        grown.flip();
        ByteOrder order = grown.order();
        grown.order(ByteOrder.BIG_ENDIAN);
        System.out.println("getLong(0)=" + Long.toHexString(grown.getLong(0)) + " ok=" + (grown.getLong(0) == header));
        grown.order(order);
        System.out.println("grown pos=" + grown.position() + " lim=" + grown.limit() + " cap=" + grown.capacity());
        ByteBuffer copy = grown.duplicate();
        System.out.println("copy pos=" + copy.position() + " lim=" + copy.limit() + " cap=" + copy.capacity()
                + " direct=" + copy.isDirect());
        ByteBuffer plain = ByteBuffer.allocateDirect(8);
        plain.put((byte) 1).put((byte) 2);
        plain.flip();
        ByteBuffer plainCopy = plain.duplicate();
        System.out.println("plain pos=" + plain.position() + " lim=" + plain.limit() + " dup pos=" + plainCopy.position()
                + " lim=" + plainCopy.limit() + " get0=" + plainCopy.get());
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        System.out.println("remaining=" + bytes.length + " b0=" + (bytes[0] & 0xFF) + " b9=" + bytes[9]
                + " read64=" + Long.toHexString(read64(bytes, 0)) + " ok=" + (read64(bytes, 0) == header));
        int diff = -1;
        for (int i = 0; i < png.length; i++) {
            if (bytes[i] != png[i]) {
                diff = i;
                break;
            }
        }
        System.out.println("firstDiff=" + diff);
    }
}
