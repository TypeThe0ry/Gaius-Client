package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Exercises the real encoder on the JVM, stopping valid copies at the native glue boundary. */
public final class CoreBufferCopyTest {
    private static int checks;

    public static void main(String[] args) {
        CoreEncoder encoder = new CoreEncoder(null, null);
        CoreBuffer source = buffer(16, 1);
        CoreBuffer target = buffer(16, 2);
        reject(encoder, source, -4, target, 0, 4);
        reject(encoder, source, 0, target, -4, 4);
        reject(encoder, source, 0, target, 0, -4);
        reject(encoder, source, 1, target, 0, 4);
        reject(encoder, source, 0, target, 2, 4);
        reject(encoder, source, 0, target, 0, 3);
        reject(encoder, source, 16, target, 0, 4);
        reject(encoder, source, 0, target, 16, 4);
        reject(encoder, source, 20, target, 0, 0);
        reject(encoder, source, 0, target, 20, 0);
        reject(encoder, source, 4, target, 0, Long.MAX_VALUE - 3);
        reject(encoder, source, Long.MAX_VALUE - 3, target, 0, 8);
        CoreBuffer huge = buffer(Long.MAX_VALUE, 3);
        reject(encoder, huge, 1L << 32, target, 0, 4);
        reject(encoder, source, 0, huge, 1L << 32, 4);
        reject(encoder, huge, 0, huge, 0, 1L << 32);
        // Logical odd-sized allocations must not expose their rounded GPU allocation's padding.
        reject(encoder, buffer(6, 4), 4, target, 0, 4);
        reject(encoder, source, 0, buffer(6, 5), 4, 4);
        seedIdentity(target);
        encoder.copyBuffer(source, 16, target, 16, 0);
        if (!target.isIdentity(false, 0, 4)) {
            throw new AssertionError("Zero-length copies must not invalidate target identity");
        }
        checks++;
        valid(encoder, source, 0, target, 0, 4);
        valid(encoder, source, 4, target, 8, 8);
        valid(encoder, source, 0, target, 0, 16);
        System.out.println("Core buffer copy checks passed: " + checks);
    }

    private static CoreBuffer buffer(long size, int handle) {
        return new CoreBuffer(null, CoreUsage.BUFFER_COPY_SRC | CoreUsage.BUFFER_COPY_DST, size, handle, false);
    }

    private static void seedIdentity(CoreBuffer target) {
        ByteBuffer initial = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) {
            initial.putShort((short) i);
        }
        initial.flip();
        target.scanIdentity(initial);
    }

    private static void reject(CoreEncoder encoder, CoreBuffer source, long sourceOffset,
            CoreBuffer target, long targetOffset, long length) {
        seedIdentity(target);
        try {
            encoder.copyBuffer(source, sourceOffset, target, targetOffset, length);
            throw new AssertionError("Invalid copy accepted: " + sourceOffset + ", " + targetOffset + ", " + length);
        } catch (IllegalArgumentException expected) {
            if (!target.isIdentity(false, 0, 4)) {
                throw new AssertionError("Rejected copy mutated target identity", expected);
            }
            checks++;
        }
    }

    private static void valid(CoreEncoder encoder, CoreBuffer source, long sourceOffset,
            CoreBuffer target, long targetOffset, long length) {
        try {
            encoder.copyBuffer(source, sourceOffset, target, targetOffset, length);
            throw new AssertionError("JVM unexpectedly supplied native WebGPU glue");
        } catch (UnsatisfiedLinkError boundary) {
            if (!boundary.getMessage().contains("WebGpuGlue.copyBufferToBuffer")) {
                throw boundary;
            }
            checks++;
        }
    }
}
