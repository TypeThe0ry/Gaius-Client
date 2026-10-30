package org.lwjgl.system;

import java.nio.Buffer;
import java.nio.ByteBuffer;

/** JVM heap stand-in for the parts of LWJGL's MemoryUtil that the STBImage override calls. */
public final class MemoryUtil {
    private MemoryUtil() {
    }

    public static ByteBuffer memAlloc(int size) {
        return ByteBuffer.allocate(size);
    }

    public static void memFree(Buffer buffer) {
    }

    public static void nmemFree(long address) {
        throw new UnsupportedOperationException();
    }

    public static ByteBuffer memByteBuffer(long address, int size) {
        throw new UnsupportedOperationException();
    }

    public static void memPutInt(long address, int value) {
        throw new UnsupportedOperationException();
    }

    public static long memAddress(ByteBuffer buffer) {
        return 0L;
    }
}
