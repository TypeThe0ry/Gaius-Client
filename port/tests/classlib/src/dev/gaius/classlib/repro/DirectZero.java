package dev.gaius.classlib.repro;

import java.nio.ByteBuffer;

/**
 * {@code ByteBuffer.allocateDirect} returns zeroed memory (TeaVMClasslibWasmGcPatcher
 * zeroAllocateDirect). TeaVM 0.15's WebAssembly GC branch took the block from
 * {@code Heap.alloc} as it was: once the finalization registry has freed earlier direct buffers,
 * a new one held their bytes or the allocator's own free-list fields (a new RegionFile then
 * wrote that garbage as the location table of a new region file). The case fills buffers of a
 * few sizes with a pattern, drops them, makes garbage and yields so the registry can free them,
 * and counts the non-zero bytes of the buffers allocated afterwards.
 */
public final class DirectZero {
    private static final int[] SIZES = {16, 24, 8192, 4096 * 3};
    private static final int ROUNDS = 40;
    private static final int PER_ROUND = 24;
    private static Object sink;

    private DirectZero() {
    }

    public static void main(String[] args) {
        long dirtyBytes = 0;
        int dirtyBuffers = 0;
        int checked = 0;
        for (int round = 0; round < ROUNDS; round++) {
            for (int index = 0; index < PER_ROUND; index++) {
                int size = SIZES[(round + index) % SIZES.length];
                ByteBuffer buffer = ByteBuffer.allocateDirect(size);
                int dirty = 0;
                for (int at = 0; at < size; at++) {
                    if (buffer.get(at) != 0) {
                        dirty++;
                    }
                }
                checked++;
                if (dirty != 0) {
                    dirtyBuffers++;
                    dirtyBytes += dirty;
                }
                for (int at = 0; at < size; at++) {
                    buffer.put(at, (byte) 0x5a);
                }
            }
            // Garbage so that the dropped buffers become collectable, then a turn of the event
            // loop for the finalization registry (a JSPI suspension in the WebAssembly GC build).
            for (int junk = 0; junk < 200; junk++) {
                sink = new byte[4096];
            }
            System.gc();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("allocateDirect buffers checked = " + checked);
        System.out.println("allocateDirect buffers with non-zero bytes = " + dirtyBuffers);
        System.out.println("allocateDirect non-zero bytes = " + dirtyBytes);
    }
}
