package org.lwjgl.system;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public final class MemoryUtil {
    private static final Map<Long, ByteBuffer> BUFFERS = new HashMap<>();
    private static long nextAddress = 1;

    private MemoryUtil() {
    }

    public static long nmemAlloc(long size) {
        long address = nextAddress++;
        BUFFERS.put(address, ByteBuffer.allocate(Math.toIntExact(size)));
        return address;
    }

    public static long nmemCalloc(long count, long size) {
        return nmemAlloc(Math.multiplyExact(count, size));
    }

    public static ByteBuffer memByteBuffer(long address, int size) {
        ByteBuffer buffer = BUFFERS.get(address).duplicate();
        buffer.limit(size);
        return buffer;
    }

    public static void nmemFree(long address) {
        BUFFERS.remove(address);
    }
}
