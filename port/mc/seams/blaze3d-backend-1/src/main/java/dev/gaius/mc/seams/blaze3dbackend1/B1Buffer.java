package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.gaius.gpu.webgpu.CoreBuffer;

/** A blaze3d buffer over a core buffer (vanilla's abstract {@link GpuBuffer} keeps usage and size). */
public final class B1Buffer extends GpuBuffer {
    /** The usage vanilla sees on transient memory blocks. */
    private static final int TRANSIENT_USAGE = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC
            | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM
            | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_INDIRECT_PARAMETERS;

    private final CoreBuffer core;

    B1Buffer(@GpuBuffer.Usage int usage, long size, CoreBuffer core) {
        super(usage, size);
        this.core = core;
        core.setFacade(this);
    }

    /** The blaze3d face of a core buffer; transient blocks get theirs on first use. */
    static B1Buffer of(CoreBuffer core) {
        Object facade = core.facade();
        if (facade instanceof B1Buffer buffer) {
            return buffer;
        }
        return new B1Buffer(TRANSIENT_USAGE, core.size(), core);
    }

    /** The core buffer behind a vanilla buffer of this seam. */
    static CoreBuffer core(GpuBuffer buffer) {
        return ((B1Buffer) buffer).core;
    }

    public CoreBuffer core() {
        return this.core;
    }

    @Override
    public boolean isClosed() {
        return this.core.isClosed();
    }

    @Override
    public void close() {
        this.core.close();
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        if (read) {
            // Read mappings follow copyTextureToBuffer(..., callback): the callback path copies the
            // result into a CPU block first (M3).
            throw new UnsupportedOperationException("WebGPU read mapping is not implemented yet");
        }
        CoreBuffer.MappedRange range = this.core.mapWrite(offset, length);
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), range.data(), range::commit);
    }
}
