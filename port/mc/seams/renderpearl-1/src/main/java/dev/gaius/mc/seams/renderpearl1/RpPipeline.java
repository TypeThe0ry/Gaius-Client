package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.gaius.gpu.webgpu.CorePipeline;

/** A compiled renderpearl pipeline over a core pipeline. */
public final class RpPipeline implements BackendRenderPipeline {
    private final CorePipeline core;
    private boolean closed;

    RpPipeline(CorePipeline core) {
        this.core = core;
    }

    public CorePipeline core() {
        return this.core;
    }

    @Override
    public boolean isClosed() {
        return this.closed || this.core.isClosed();
    }

    /** One release per wrapper: a fallback shares its core pipeline with the wrapper of an earlier build. */
    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.core.close();
        }
    }
}
