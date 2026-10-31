package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.gaius.gpu.webgpu.CoreDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

/**
 * The WebGPU renderpearl backend: the third backend next to vanilla's OpenGL and Vulkan ones,
 * behind vanilla's own frontend ({@link FrontendGpuDevice}).
 *
 * <p>{@link #loadLibrary()} fails with a {@link BackendCreationException} unless the page's
 * glue has a ready device, so vanilla's backend loop moves on to the next backend (WebGL 2).</p>
 */
public final class RpBackend implements GpuBackend {
    @Override
    public String getName() {
        return "WebGPU";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        requireDevice();
    }

    @Override
    public void unloadLibrary() {
    }

    /** The browser SDL shim adopts the canvas for the first visible window; no GL context is made. */
    @Override
    public long createWindow(@Nullable String title, int width, int height, long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, flags);
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
        requireDevice();
        return new FrontendGpuDevice(new RpDevice(CoreDevice.create(debugOptions.useLabels()), true));
    }

    /**
     * The backend device without vanilla's frontend and without the SDL frame yield in
     * {@code present()} (harnesses and tests).
     */
    public static RpDevice createBackendDevice(boolean debug) throws BackendCreationException {
        requireDevice();
        return new RpDevice(CoreDevice.create(debug), false);
    }

    private static void requireDevice() throws BackendCreationException {
        if (!CoreDevice.isAvailable()) {
            throw new BackendCreationException("WebGPU is not available: " + CoreDevice.failure(),
                    BackendCreationException.Reason.OTHER);
        }
    }
}
