package dev.gaius.mc.seams.blaze3ddirect0;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.gaius.gpu.webgpu.CorePipeline;
import org.jspecify.annotations.Nullable;

/** A compiled 1.21.11 pipeline over a core pipeline, or an invalid one ({@code core == null}). */
public final class D0Pipeline implements CompiledRenderPipeline {
    private final RenderPipeline info;
    private final @Nullable CorePipeline core;
    private final boolean fan;

    D0Pipeline(RenderPipeline info, @Nullable CorePipeline core) {
        this.info = info;
        this.core = core;
        this.fan = info.getVertexFormatMode() == VertexFormat.Mode.TRIANGLE_FAN;
    }

    /** A TRIANGLE_FAN pipeline: compiled as a triangle list, its draws go through FanIndices. */
    boolean fan() {
        return this.fan;
    }

    public RenderPipeline info() {
        return this.info;
    }

    public @Nullable CorePipeline core() {
        return this.core;
    }

    @Override
    public boolean isValid() {
        return this.core != null && !this.core.isClosed();
    }

    void close() {
        if (this.core != null) {
            this.core.close();
        }
    }
}
