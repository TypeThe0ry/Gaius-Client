package dev.gaius.gpu.webgpu;

import java.util.function.BooleanSupplier;

/**
 * The game canvas as a surface. The canvas presents when the browser task ends, so
 * {@link #present()} only has to end the task: the seam passes a frame yield (in the game the
 * browser SDL or GLFW shim's swap call, which on the WebGL 2 path is the per-frame cooperative
 * yield and frame telemetry and issues no GL call), so frame pacing is shared by both paths.
 * Harnesses pass none.
 */
public final class CoreSurface {
    private final CoreDevice device;
    private final BooleanSupplier iconified;
    private final Runnable frameYield;

    CoreSurface(CoreDevice device, BooleanSupplier iconified, Runnable frameYield) {
        this.device = device;
        this.iconified = iconified;
        this.frameYield = frameYield;
    }

    /** Sizes the canvas and configures its context; {@code false} when the context is unavailable. */
    public boolean configure(int width, int height) {
        return this.device.isUsable() && WebGpuGlue.configureSurface(width, height);
    }

    public boolean isIconified() {
        return this.iconified.getAsBoolean();
    }

    /**
     * Starts the frame on the canvas; {@code false} when the context is unavailable. The glue takes
     * the canvas texture only at the submit that carries the blit: the texture expires when the
     * browser updates the rendering, which a JSPI suspension inside the frame lets happen.
     */
    public boolean acquire() {
        return this.device.isUsable() && WebGpuGlue.acquireSurface();
    }

    /** Queues the blit of {@code view} to the canvas texture for the next submit, flipped once (GL row order). */
    public void blit(CoreTextureView view) {
        if (this.device.isUsable()) {
            WebGpuGlue.blitToSurface(view.handle);
        }
    }

    public void present() {
        // A lost device skips the GPU call but still yields, so a frame loop that keeps running after
        // the loss does not spin without letting the browser run.
        if (this.device.isUsable()) {
            WebGpuGlue.present();
        }
        if (this.frameYield != null) {
            this.frameYield.run();
        }
    }
}
