package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.gaius.gpu.webgpu.CorePipeline;
import org.jspecify.annotations.Nullable;

/** A compiled blaze3d pipeline over a core pipeline, or an invalid one ({@code core == null}). */
public final class B1Pipeline implements CompiledRenderPipeline {
    private final RenderPipeline info;
    private final @Nullable CorePipeline core;
    private final boolean fan;

    B1Pipeline(RenderPipeline info, @Nullable CorePipeline core) {
        this.info = info;
        this.core = core;
        this.fan = B1Pipelines.isFan(info);
    }

    public RenderPipeline info() {
        return this.info;
    }

    public @Nullable CorePipeline core() {
        return this.core;
    }

    /** A TRIANGLE_FAN pipeline: compiled as a triangle list, its draws go through FanIndices. */
    boolean fan() {
        return this.fan;
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
