package org.lwjgl.stb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.lwjgl.system.MemoryUtil;

final class PngPixels {
    private PngPixels() {
    }

    static ByteBuffer materialize(byte[] payload, int desiredChannels, boolean flip,
            IntBuffer x, IntBuffer y, IntBuffer channels) {
        if (payload == null || payload.length < 16 || desiredChannels < 0 || desiredChannels > 4) {
            return null;
        }
        ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int width = header.getInt();
        int height = header.getInt();
        int sourceChannels = header.getInt();
        int outputChannels = header.getInt();
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384
                || sourceChannels < 1 || sourceChannels > 4
                || outputChannels != (desiredChannels == 0 ? sourceChannels : desiredChannels)) {
            return null;
        }
        long size = (long) width * height * outputChannels;
        if ((long) width * height * 4 > 64 * 1024 * 1024 || size != payload.length - 16) {
            return null;
        }
        ByteBuffer output = MemoryUtil.memAlloc((int) size);
        try {
            if (flip) {
                int stride = width * outputChannels;
                for (int row = height - 1; row >= 0; row--) {
                    output.put(payload, 16 + row * stride, stride);
                }
            } else {
                output.put(payload, 16, (int) size);
            }
            output.flip();
            if (x != null && x.remaining() > 0) {
                x.put(x.position(), width);
            }
            if (y != null && y.remaining() > 0) {
                y.put(y.position(), height);
            }
            if (channels != null && channels.remaining() > 0) {
                channels.put(channels.position(), sourceChannels);
            }
            return output;
        } catch (Throwable throwable) {
            MemoryUtil.memFree(output);
            throw throwable;
        }
    }
}
