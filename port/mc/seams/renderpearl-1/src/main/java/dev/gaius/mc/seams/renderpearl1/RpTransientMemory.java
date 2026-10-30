package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import dev.gaius.gpu.webgpu.CoreSlice;
import dev.gaius.gpu.webgpu.CoreWindow;
import dev.gaius.gpu.webgpu.TransientArena;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla's per-submit {@link TransientMemory} over the core's {@link TransientArena}: every
 * written block is uploaded once per submit, ahead of the submit's commands, through the staging
 * ring. Mapped windows need no close action (their contents are uploaded at the submit).
 */
final class RpTransientMemory implements TransientMemory {
    private static final Runnable NO_OP = () -> {
    };

    private final TransientArena arena;

    RpTransientMemory(TransientArena arena) {
        this.arena = arena;
    }

    private static GpuBufferSlice slice(CoreSlice slice) {
        return new GpuBufferSlice(RpBuffer.of(slice.buffer()), slice.offset(), slice.length());
    }

    private static GpuBufferSlice.MappedView view(CoreWindow window) {
        return new GpuBufferSlice.MappedView(slice(window.slice()), window.data(), NO_OP);
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        return this.arena.allocateCpu(size, alignment, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, @GpuBuffer.Usage int usage,
            long minimumAllocation, long elementSize) {
        return view(this.arena.stagingWindow(size, alignment, minimumAllocation, elementSize));
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation,
            long elementSize) {
        return slice(this.arena.allocateGpu(size, alignment, minimumAllocation, elementSize));
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, @GpuBuffer.Usage int usage,
            long minimumAllocation, long elementSize) {
        return view(this.arena.stagingWindow(size, alignment, minimumAllocation, elementSize));
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage,
            long minimumAllocation, long elementSize) {
        return slice(this.arena.upload(data, alignment, minimumAllocation, elementSize));
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage,
            long minimumAllocation, long elementSize) {
        return slice(this.arena.upload(data, alignment, minimumAllocation, elementSize));
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        return this.multiUploadGpu(data, alignment, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        List<CoreSlice> slices = this.arena.uploadEach(data, alignment);
        ArrayList<GpuBufferSlice> out = new ArrayList<>(slices.size());
        for (int i = 0; i < slices.size(); i++) {
            out.add(slice(slices.get(i)));
        }
        return out;
    }
}
