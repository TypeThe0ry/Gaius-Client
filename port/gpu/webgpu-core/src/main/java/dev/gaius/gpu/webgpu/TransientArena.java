package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-submit transient memory (vanilla's {@code TransientMemory}, in core types).
 *
 * <p>Blocks allocate a CPU copy only when an upload needs one. Allocations hand out windows of
 * that copy; {@link #flush()} uploads the written range once, then releases the copy before the
 * command buffer is submitted (ahead of the frame's commands), so a frame's uniform, vertex and
 * staging data cost one upload per 512 KiB instead of one per allocation. WebGPU orders queue
 * work before later submissions, so a block can be reused as soon as the submission that read it is queued;
 * {@link #rotate()} therefore recycles every block after each submit. Allocations larger than a
 * block get a dedicated block from a small pool.</p>
 */
public final class TransientArena {
    static final int BLOCK_SIZE = 512 * 1024;
    /** Keep transient GPU/CPU scratch reuse bounded after a burst such as terrain loading. */
    private static final int MAX_FREE_BLOCKS = 16;
    private static final int MAX_FREE_UPLOAD_CPU_BLOCKS = 16;
    private static final int MAX_CPU_BLOCKS = 8;
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
    private final ArrayList<ByteBuffer> temporaryCpuBlocks = new ArrayList<>();
    private final ArrayList<ByteBuffer> freeUploadCpuBlocks = new ArrayList<>();
    private int cpuBlockIndex;
    private int cpuOffset;
    long uploadedBytes;
    int uploadCalls;

    TransientArena(CoreDevice device) {
        this.device = device;
    }

    /** One GPU block and a lazily allocated CPU copy for the range written since the last flush. */
    static final class Block {
        final int size;
        final CoreBuffer buffer;
        final TransientArena arena;
        ByteBuffer cpu;
        int dirtyStart = Integer.MAX_VALUE;
        int dirtyEnd;

        Block(TransientArena arena, CoreDevice device, int size) {
            this.arena = arena;
            this.size = size;
            // JVM retention tests use a null device and exercise CPU ownership only.
            int handle = device == null ? 0
                    : WebGpuGlue.createBuffer(size, CoreUsage.webBufferUsage(BLOCK_USAGE), "gaius transient");
            this.buffer = new CoreBuffer(device, BLOCK_USAGE, size, handle, true);
        }

        ByteBuffer cpu() {
            if (this.cpu == null) {
                this.cpu = this.arena.acquireUploadCpu(this.size);
            }
            return this.cpu;
        }

        void releaseCpu() {
            if (this.cpu != null) {
                this.arena.recycleUploadCpu(this.cpu, this.size);
                this.cpu = null;
            }
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
        this.current = this.freeBlocks.isEmpty() ? new Block(this, this.device, BLOCK_SIZE)
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
        return new Block(this, this.device, size);
    }

    private ByteBuffer acquireUploadCpu(int size) {
        if (size == BLOCK_SIZE && !this.freeUploadCpuBlocks.isEmpty()) {
            return this.freeUploadCpuBlocks.remove(this.freeUploadCpuBlocks.size() - 1);
        }
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }

    private void recycleUploadCpu(ByteBuffer block, int size) {
        if (size == BLOCK_SIZE && this.freeUploadCpuBlocks.size() < MAX_FREE_UPLOAD_CPU_BLOCKS) {
            this.freeUploadCpuBlocks.add(block);
        }
    }

    private static ByteBuffer window(Block block, int offset, int size) {
        ByteBuffer view = block.cpu().duplicate();
        view.limit(offset + size).position(offset);
        return view.slice().order(ByteOrder.nativeOrder());
    }

    /** Copies {@code data} (position to limit) into a block window and returns the window's slice. */
    public CoreSlice stage(ByteBuffer data, long alignment) {
        int length = data.remaining();
        Allocation allocation = this.allocate(align(length, 4L), alignment, align(length, 4L), 1L);
        this.copyBytes(allocation.block.cpu(), allocation.offset, data);
        allocation.block.markDirty(allocation.offset, allocation.offset + allocation.size);
        return new CoreSlice(allocation.block.buffer, allocation.offset, allocation.size);
    }

    private void copyBytes(ByteBuffer target, int offset, ByteBuffer source) {
        if (this.device != null) {
            WebGpuGlue.copyBytes(target, offset, source);
            return;
        }
        ByteBuffer destination = target.duplicate();
        destination.position(offset);
        destination.put(source.duplicate());
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

    /** An isolated per-submit GPU block, used when a compute stage needs distinct bindings. */
    CoreSlice uploadIsolated(ByteBuffer data) {
        int size = align(data.remaining(), 4L);
        Block block = new Block(this, this.device, size);
        this.usedBlocks.add(block);
        this.copyBytes(block.cpu(), 0, data);
        block.markDirty(0, size);
        return new CoreSlice(block.buffer, 0, size);
    }

    CoreSlice allocateIsolatedGpu(long size) {
        int allocated = align(size, 4L);
        Block block = new Block(this, this.device, allocated);
        this.usedBlocks.add(block);
        return new CoreSlice(block.buffer, 0, allocated);
    }

    /** CPU scratch memory valid until the next submit. */
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        int length = (int) size;
        if (length > BLOCK_SIZE) {
            ByteBuffer block = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
            this.temporaryCpuBlocks.add(block);
            return block;
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
            this.copyBytes(allocation.block.cpu(), allocation.offset + offset, part);
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
            if (this.device != null) {
                WebGpuGlue.upload(block.buffer.handle, start, window(block, start, end - start));
            }
            this.uploadedBytes += end - start;
            this.uploadCalls++;
        }
        block.releaseCpu();
        block.dirtyStart = Integer.MAX_VALUE;
        block.dirtyEnd = 0;
    }

    /** After a submit: every block is free again; a few oversized blocks are kept for reuse. */
    void rotate() {
        this.freeBlocks.addAll(this.usedBlocks);
        this.usedBlocks.clear();
        // A large upload burst must not turn into a permanent high-water cache. The queue has
        // already accepted this submit, and oversized blocks use the same post-submit destroy
        // rule below, so dropping older regular blocks is safe and keeps both GPU and CPU copies
        // bounded.
        while (this.freeBlocks.size() > MAX_FREE_BLOCKS) {
            Block block = this.freeBlocks.remove(0);
            if (this.device != null) {
                WebGpuGlue.destroyBuffer(block.buffer.handle);
            }
        }
        this.freeOversizedBlocks.addAll(this.oversizedBlocks);
        this.oversizedBlocks.clear();
        while (this.freeOversizedBlocks.size() > MAX_FREE_OVERSIZED_BLOCKS) {
            Block block = this.freeOversizedBlocks.remove(0);
            if (this.device != null) {
                WebGpuGlue.destroyBuffer(block.buffer.handle);
            }
        }
        while (!this.temporaryCpuBlocks.isEmpty()) {
            this.temporaryCpuBlocks.remove(this.temporaryCpuBlocks.size() - 1);
        }
        this.current = null;
        this.currentOffset = 0;
        while (this.cpuBlocks.size() > MAX_CPU_BLOCKS) {
            this.cpuBlocks.remove(this.cpuBlocks.size() - 1);
        }
        this.cpuBlockIndex = 0;
        this.cpuOffset = 0;
    }
}
