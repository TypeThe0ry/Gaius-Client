package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-submit transient memory (vanilla's {@code TransientMemory}, in core types).
 *
 * <p>Every block is a GPU buffer with a CPU copy. Allocations hand out windows of the CPU copy;
 * {@link #flush()} uploads the written range of each block once, right before the command buffer
 * is submitted (ahead of the frame's commands), so a frame's uniform, vertex and staging data cost
 * one upload per 512 KiB instead of one per allocation. WebGPU orders queue work before later
 * submissions, so a block can be reused as soon as the submission that read it is queued;
 * {@link #rotate()} therefore recycles every block after each submit. Allocations larger than a
 * block get a dedicated block from a small pool.</p>
 */
public final class TransientArena {
    static final int BLOCK_SIZE = 512 * 1024;
    private static final int MAX_FREE_OVERSIZED_BLOCKS = 4;
    private static final int BLOCK_USAGE = CoreUsage.BUFFER_COPY_SRC | CoreUsage.BUFFER_COPY_DST
            | CoreUsage.BUFFER_VERTEX | CoreUsage.BUFFER_INDEX | CoreUsage.BUFFER_UNIFORM | CoreUsage.BUFFER_TEXEL
            | CoreUsage.BUFFER_INDIRECT;

    private final CoreDevice device;
    private final ArrayList<Block> freeBlocks = new ArrayList<>();
    private final ArrayList<Block> usedBlocks = new ArrayList<>();
    private final ArrayList<Block> oversizedBlocks = new ArrayList<>();
    private final ArrayList<Block> freeOversizedBlocks = new ArrayList<>();
    private Block current;
    private int currentOffset;
    private final ArrayList<ByteBuffer> cpuBlocks = new ArrayList<>();
    private int cpuBlockIndex;
    private int cpuOffset;
    long uploadedBytes;
    int uploadCalls;

    TransientArena(CoreDevice device) {
        this.device = device;
    }

    /** One GPU block, its CPU copy and the range written since the last flush. */
    static final class Block {
        final int size;
        final CoreBuffer buffer;
        final ByteBuffer cpu;
        int dirtyStart = Integer.MAX_VALUE;
        int dirtyEnd;

        Block(CoreDevice device, int size) {
            this.size = size;
            int handle = WebGpuGlue.createBuffer(size, CoreUsage.webBufferUsage(BLOCK_USAGE), "gaius transient");
            this.buffer = new CoreBuffer(device, BLOCK_USAGE, size, handle, true);
            this.cpu = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        }

        void markDirty(int start, int end) {
            if (start < this.dirtyStart) {
                this.dirtyStart = start;
            }
            if (end > this.dirtyEnd) {
                this.dirtyEnd = end;
            }
        }
    }

    /** A window [offset, offset + size) of a block. */
    private record Allocation(Block block, int offset, int size) {
    }

    static int align(long value, long alignment) {
        long a = Math.max(4L, alignment);
        return (int) ((value + a - 1L) / a * a);
    }

    private Allocation allocate(long size, long alignment, long minimumAllocation, long elementSize) {
        if (size > BLOCK_SIZE) {
            Block block = this.oversizedBlock(align(size, 4L));
            this.oversizedBlocks.add(block);
            return new Allocation(block, 0, (int) size);
        }
        if (this.current != null) {
            int offset = align(this.currentOffset, alignment);
            if (offset + size <= BLOCK_SIZE) {
                this.currentOffset = offset + (int) size;
                return new Allocation(this.current, offset, (int) size);
            }
            if (minimumAllocation < size && offset + minimumAllocation <= BLOCK_SIZE) {
                long element = Math.max(1L, elementSize);
                int partial = (int) ((BLOCK_SIZE - offset) / element * element);
                if (partial >= minimumAllocation) {
                    this.currentOffset = offset + partial;
                    return new Allocation(this.current, offset, partial);
                }
            }
        }
        this.current = this.freeBlocks.isEmpty() ? new Block(this.device, BLOCK_SIZE)
                : this.freeBlocks.remove(this.freeBlocks.size() - 1);
        this.usedBlocks.add(this.current);
        this.currentOffset = (int) size;
        return new Allocation(this.current, 0, (int) size);
    }

    /** A pooled oversized block of at least {@code size} bytes and at most twice that. */
    private Block oversizedBlock(int size) {
        for (int i = 0; i < this.freeOversizedBlocks.size(); i++) {
            Block block = this.freeOversizedBlocks.get(i);
            if (block.size >= size && block.size / 2 <= size) {
                this.freeOversizedBlocks.remove(i);
                return block;
            }
        }
        return new Block(this.device, size);
    }

    private static ByteBuffer window(Block block, int offset, int size) {
        ByteBuffer view = block.cpu.duplicate();
        view.limit(offset + size).position(offset);
        return view.slice().order(ByteOrder.nativeOrder());
    }

    /** Copies {@code data} (position to limit) into a block window and returns the window's slice. */
    public CoreSlice stage(ByteBuffer data, long alignment) {
        int length = data.remaining();
        Allocation allocation = this.allocate(align(length, 4L), alignment, align(length, 4L), 1L);
        WebGpuGlue.copyBytes(allocation.block.cpu, allocation.offset, data);
        allocation.block.markDirty(allocation.offset, allocation.offset + allocation.size);
        return new CoreSlice(allocation.block.buffer, allocation.offset, allocation.size);
    }

    /**
     * A writable block window whose contents reach the GPU at the next submit; with
     * {@code minimumAllocation < size} the window may be shorter (a whole number of elements).
     */
    public CoreWindow stagingWindow(long size, long alignment, long minimumAllocation, long elementSize) {
        Allocation allocation = this.allocate(size, alignment, minimumAllocation, elementSize);
        allocation.block.markDirty(allocation.offset, align(allocation.offset + allocation.size, 4L));
        return new CoreWindow(new CoreSlice(allocation.block.buffer, allocation.offset, allocation.size),
                window(allocation.block, allocation.offset, allocation.size));
    }

    /** GPU-only transient memory: a slice nothing uploads into. */
    public CoreSlice allocateGpu(long size, long alignment, long minimumAllocation, long elementSize) {
        Allocation allocation = this.allocate(size, alignment, minimumAllocation, elementSize);
        return new CoreSlice(allocation.block.buffer, allocation.offset, allocation.size);
    }

    /** CPU scratch memory valid until the next submit. */
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        int length = (int) size;
        if (length > BLOCK_SIZE) {
            return ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
        }
        int offset = align(this.cpuOffset, alignment);
        if (this.cpuBlockIndex >= this.cpuBlocks.size() || offset + length > BLOCK_SIZE) {
            if (this.cpuBlockIndex < this.cpuBlocks.size()) {
                this.cpuBlockIndex++;
            }
            if (this.cpuBlockIndex >= this.cpuBlocks.size()) {
                this.cpuBlocks.add(ByteBuffer.allocateDirect(BLOCK_SIZE).order(ByteOrder.nativeOrder()));
            }
            offset = 0;
        }
        this.cpuOffset = offset + length;
        ByteBuffer view = this.cpuBlocks.get(this.cpuBlockIndex).duplicate();
        view.limit(offset + length).position(offset);
        return view.slice().order(ByteOrder.nativeOrder());
    }

    /** Copies every buffer (position to limit), each aligned, into one allocation. */
    public CoreSlice upload(List<ByteBuffer> data, long alignment, long minimumAllocation, long elementSize) {
        long total = 0L;
        for (int i = 0; i < data.size(); i++) {
            total = align(total + data.get(i).remaining(), alignment);
        }
        Allocation allocation = this.allocate(total, alignment, minimumAllocation, elementSize);
        int offset = 0;
        for (int i = 0; i < data.size() && offset < allocation.size; i++) {
            ByteBuffer source = data.get(i);
            int length = Math.min(source.remaining(), allocation.size - offset);
            ByteBuffer part = source;
            if (length < source.remaining()) {
                part = source.duplicate();
                part.limit(part.position() + length);
            }
            WebGpuGlue.copyBytes(allocation.block.cpu, allocation.offset + offset, part);
            offset = align(offset + length, alignment);
        }
        allocation.block.markDirty(allocation.offset, allocation.offset + allocation.size);
        return new CoreSlice(allocation.block.buffer, allocation.offset, allocation.size);
    }

    /** One slice per buffer. */
    public List<CoreSlice> uploadEach(List<ByteBuffer> data, long alignment) {
        ArrayList<CoreSlice> slices = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            slices.add(this.stage(data.get(i), alignment));
        }
        return slices;
    }

    /** Uploads the written range of every block used since the last submit. */
    void flush() {
        for (int i = 0; i < this.usedBlocks.size(); i++) {
            this.flushBlock(this.usedBlocks.get(i));
        }
        for (int i = 0; i < this.oversizedBlocks.size(); i++) {
            this.flushBlock(this.oversizedBlocks.get(i));
        }
    }

    private void flushBlock(Block block) {
        if (block.dirtyEnd > block.dirtyStart) {
            int start = block.dirtyStart & ~3;
            int end = Math.min(block.size, (block.dirtyEnd + 3) & ~3);
            WebGpuGlue.upload(block.buffer.handle, start, window(block, start, end - start));
            this.uploadedBytes += end - start;
            this.uploadCalls++;
        }
        block.dirtyStart = Integer.MAX_VALUE;
        block.dirtyEnd = 0;
    }

    /** After a submit: every block is free again; a few oversized blocks are kept for reuse. */
    void rotate() {
        this.freeBlocks.addAll(this.usedBlocks);
        this.usedBlocks.clear();
        this.freeOversizedBlocks.addAll(this.oversizedBlocks);
        this.oversizedBlocks.clear();
        while (this.freeOversizedBlocks.size() > MAX_FREE_OVERSIZED_BLOCKS) {
            WebGpuGlue.destroyBuffer(this.freeOversizedBlocks.remove(0).buffer.handle);
        }
        this.current = null;
        this.currentOffset = 0;
        this.cpuBlockIndex = 0;
        this.cpuOffset = 0;
    }
}
