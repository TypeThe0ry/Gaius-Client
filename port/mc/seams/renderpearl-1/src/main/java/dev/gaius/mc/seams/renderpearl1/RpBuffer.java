package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import dev.gaius.gpu.webgpu.CoreBuffer;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * A renderpearl buffer over a core buffer. It extends vanilla's {@link BaseGpuBuffer} because the
 * 26.3 frontend casts buffers to it ({@code FrontendRenderPass} {@code checkcast BaseGpuBuffer}
 * before {@code checkCanBeUsed}); everything else is delegated.
 */
public final class RpBuffer extends BaseGpuBuffer {
    /** The usage vanilla sees on transient memory blocks. */
    private static final int TRANSIENT_USAGE = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC
            | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM
            | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_INDIRECT_PARAMETERS;

    private final CoreBuffer core;

    RpBuffer(@GpuBuffer.Usage int usage, long size, CoreBuffer core) {
        super(usage, size);
        this.core = core;
        core.setFacade(this);
    }

    /** The renderpearl face of a core buffer; transient blocks get theirs on first use. */
    static RpBuffer of(CoreBuffer core) {
        Object facade = core.facade();
        if (facade instanceof RpBuffer buffer) {
            return buffer;
        }
        return new RpBuffer(TRANSIENT_USAGE, core.size(), core);
    }

    /** The core buffer behind a vanilla buffer of this seam. */
    static CoreBuffer core(GpuBuffer buffer) {
        return ((RpBuffer) buffer).core;
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

    /**
     * Read mappings see what texture readbacks copied into the buffer (the core's CPU copy,
     * complete once the readback's callback ran: vanilla maps in that callback). Write mappings
     * are committed into the buffer in command order when the view closes.
     *
     * <p>Vanilla reads and fills mappings through LWJGL MemoryUtil (memCopy, memAddress), which
     * only sees memory of its own allocator: a plain direct buffer is copied away by the browser
     * allocator and the copy is used instead (glyph uploads arrived empty). The view therefore
     * hands out such a block and moves its bytes when the mapping opens (read) and closes
     * (write).</p>
     */
    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        CoreBuffer.MappedRange range = write ? this.core.mapWrite(offset, length) : null;
        ByteBuffer staging = MemoryUtil.memAlloc((int) length);
        if (read) {
            ByteBuffer source = this.core.readData().duplicate();
            source.limit((int) (offset + length)).position((int) offset);
            staging.put(source).clear();
        }
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), staging, () -> {
            try {
                if (range != null) {
                    ByteBuffer target = range.data().duplicate();
                    target.clear();
                    ByteBuffer source = staging.duplicate();
                    source.clear();
                    target.put(source);
                    range.commit();
                }
            } finally {
                MemoryUtil.memFree(staging);
            }
        });
    }
}
