package dev.gaius.classlib.repro;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Position bookkeeping of direct buffers: TeaVM 0.15's put(ByteBuffer) from an array-backed
 * source added the array offset twice; TeaVMClasslibWasmGcPatcher.
 */
public final class BufTest2 {
    private BufTest2() {
    }

    public static void main(String[] args) {
        ByteBuffer src = ByteBuffer.allocateDirect(16);
        for (int i = 0; i < 16; i++) {
            src.put((byte) i);
        }
        src.flip();
        ByteBuffer dst = ByteBuffer.allocateDirect(64);
        dst.put(src);
        System.out.println("after put(buffer): dst pos=" + dst.position() + " src pos=" + src.position());
        dst.put(new byte[8], 0, 8);
        System.out.println("after put(bytes): dst pos=" + dst.position());
        dst.flip();
        System.out.println("after flip: pos=" + dst.position() + " lim=" + dst.limit());
        dst.order(ByteOrder.BIG_ENDIAN);
        System.out.println("after order: pos=" + dst.position());
        long v = dst.getLong(0);
        System.out.println("after getLong(0): pos=" + dst.position() + " v=" + Long.toHexString(v));
        ByteBuffer heap = ByteBuffer.allocate(64);
        src.rewind();
        heap.put(src);
        System.out.println("heap after put(buffer): pos=" + heap.position() + " src pos=" + src.position());
    }
}
