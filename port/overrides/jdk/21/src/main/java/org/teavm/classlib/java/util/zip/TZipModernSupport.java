package org.teavm.classlib.java.util.zip;

import java.nio.ByteBuffer;

public final class TZipModernSupport {
    private TZipModernSupport() {
    }

    public static void setInput(TInflater inflater, ByteBuffer buffer) {
        if (buffer.hasArray()) {
            int position = buffer.position();
            int remaining = buffer.remaining();
            inflater.setInput(
                    buffer.array(),
                    buffer.arrayOffset() + position,
                    remaining);
            buffer.position(position + remaining);
            return;
        }

        byte[] input = new byte[buffer.remaining()];
        buffer.get(input);
        inflater.setInput(input);
    }

    public static int inflate(TInflater inflater, ByteBuffer buffer) throws TDataFormatException {
        if (buffer.hasArray() && !buffer.isReadOnly()) {
            int position = buffer.position();
            int count = inflater.inflate(
                    buffer.array(),
                    buffer.arrayOffset() + position,
                    buffer.remaining());
            buffer.position(position + count);
            return count;
        }

        byte[] output = new byte[buffer.remaining()];
        int count = inflater.inflate(output);
        buffer.put(output, 0, count);
        return count;
    }
}
