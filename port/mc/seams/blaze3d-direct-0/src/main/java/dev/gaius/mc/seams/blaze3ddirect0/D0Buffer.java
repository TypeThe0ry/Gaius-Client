package dev.gaius.mc.seams.blaze3ddirect0;

import com.mojang.blaze3d.buffers.GpuBuffer;
import dev.gaius.gpu.webgpu.CoreBuffer;

/**
 * A 1.21.11 buffer over a core buffer. 1.21.11 maps buffers through the command encoder
 * ({@link D0Encoder#mapBuffer}), so the buffer itself only closes.
 */
public final class D0Buffer extends GpuBuffer {
    private final CoreBuffer core;

    D0Buffer(@GpuBuffer.Usage int usage, long size, CoreBuffer core) {
        super(usage, size);
        this.core = core;
        core.setFacade(this);
    }

    /** The core buffer behind a vanilla buffer of this seam. */
    static CoreBuffer core(GpuBuffer buffer) {
        return ((D0Buffer) buffer).core;
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
}
