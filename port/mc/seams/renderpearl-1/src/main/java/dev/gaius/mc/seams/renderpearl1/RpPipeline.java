package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.gaius.gpu.webgpu.CorePipeline;

/** A compiled renderpearl pipeline over a core pipeline. */
public final class RpPipeline implements BackendRenderPipeline {
    private final CorePipeline core;

    RpPipeline(CorePipeline core) {
        this.core = core;
    }

    public CorePipeline core() {
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
