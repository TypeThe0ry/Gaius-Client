package dev.gaius.browser;

import io.netty.buffer.ByteBuf;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;

/** Bulk decoder for Minecraft's big-endian packed-long network fields. */
public final class BrowserLongArrayCodec {
    private static final int LONGS_PER_BATCH = 512;

    private BrowserLongArrayCodec() {
    }

    /**
     * Matches {@link ByteBuf#readLong()} repeated for every destination element, while avoiding
     * one Java-to-JavaScript long conversion per value in chunk palette and heightmap packets.
     */
    public static long[] readFixedSizeLongArray(ByteBuf buffer, long[] output) {
        byte[] bytes = new byte[Math.min(output.length, LONGS_PER_BATCH) * Long.BYTES];
        for (int offset = 0; offset < output.length; ) {
            int count = Math.min((bytes.length / Long.BYTES), output.length - offset);
            int byteCount = count * Long.BYTES;
            buffer.readBytes(bytes, 0, byteCount);
            if (!decodeBigEndian(bytes, output, offset, count)) {
                decodeBigEndianFallback(bytes, output, offset, count);
            }
            offset += count;
        }
        return output;
    }

    private static void decodeBigEndianFallback(byte[] bytes, long[] output, int offset, int count) {
        for (int index = 0; index < count; index++) {
            int byteOffset = index * Long.BYTES;
            long value = 0L;
            for (int byteIndex = 0; byteIndex < Long.BYTES; byteIndex++) {
                value = (value << Byte.SIZE) | (bytes[byteOffset + byteIndex] & 0xffL);
            }
            output[offset + index] = value;
        }
    }

    @JSBody(params = {"bytes", "output", "offset", "count"}, script = """
            try {
              const source = bytes && bytes.data ? bytes.data : bytes;
              const target = output && output.data ? output.data : output;
              const start = offset | 0;
              const length = count | 0;
              if (!source || !target || length < 0 || source.length < length * 8
                  || start < 0 || target.length < start + length) {
                return false;
              }
              const view = new DataView(source.buffer, source.byteOffset, length * 8);
              for (let index = 0; index < length; index++) {
                target[start + index] = view.getBigInt64(index * 8, false);
              }
              const counters = globalThis.__gaiusMinecraftCounters
                  || (globalThis.__gaiusMinecraftCounters = {});
              counters.longArrayDecodeBatches = (counters.longArrayDecodeBatches || 0) + 1;
              counters.longArrayDecodeLongs = (counters.longArrayDecodeLongs || 0) + length;
              return true;
            } catch (ignored) {
              return false;
            }
            """)
    private static native boolean decodeBigEndian(
            @JSByRef byte[] bytes, @JSByRef long[] output, int offset, int count);
}
