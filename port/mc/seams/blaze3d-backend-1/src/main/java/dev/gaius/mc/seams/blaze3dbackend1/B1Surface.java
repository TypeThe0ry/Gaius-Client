package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.gaius.gpu.webgpu.CoreSurface;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The game canvas as a blaze3d surface. Both present modes are reported so vanilla's vsync option
 * keeps working; the canvas presents when the browser task ends, which the GLFW shim's
 * {@code glfwSwapBuffers} (the frame yield, as on the WebGL 2 path) brings about.
 *
 * <p>The frame yield paces a frame by the swap interval the last present carried. Vanilla 26.2
 * no longer calls {@code glfwSwapInterval}; it hands the vsync option to the surface as the
 * present mode of {@link GpuSurface.Configuration}. {@link #configure} turns that mode into the
 * swap interval of the GLFW shim (FIFO is 1, any other mode 0, as vanilla's GlSurface does), so vsync
 * paces the WebGPU path exactly as it does the WebGL 2 one.</p>
 */
final class B1Surface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO,
            GpuSurface.PresentMode.IMMEDIATE);
    private final CoreSurface core;
    private final IntConsumer swapInterval;

    /** swapInterval: the GLFW shim's glfwSwapInterval (the game); null in harnesses. */
    B1Surface(CoreSurface core, IntConsumer swapInterval) {
        this.core = core;
        this.swapInterval = swapInterval;
    }

    @Override
    public void configure(GpuSurface.Configuration config) throws SurfaceException {
        if (!this.core.configure(config.width(), config.height())) {
            throw new SurfaceException("The WebGPU canvas context is not available");
        }
        if (this.swapInterval != null) {
            this.swapInterval.accept(config.presentMode() == GpuSurface.PresentMode.FIFO ? 1 : 0);
        }
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        if (this.core.isIconified()) {
            throw new SurfaceException("Cannot acquire minimized window");
        }
        if (!this.core.acquire()) {
            throw new SurfaceException("The WebGPU canvas context is not available");
        }
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
        this.core.blit(((B1TextureView) textureView).core());
    }

    @Override
    public void present() {
        this.core.present();
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return PRESENT_MODES;
    }

    @Override
    public void close() {
    }
}
