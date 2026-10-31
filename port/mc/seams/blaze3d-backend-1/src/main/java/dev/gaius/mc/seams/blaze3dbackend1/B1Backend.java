package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.gaius.gpu.webgpu.CoreDevice;
import java.util.Locale;
import org.lwjgl.glfw.GLFW;
import org.teavm.jso.JSBody;

/**
 * The WebGPU blaze3d backend: the third backend next to vanilla 26.2's OpenGL and Vulkan ones,
 * behind vanilla's own frontend ({@link GpuDevice}), built like vanilla's {@code GlBackend}.
 *
 * <p>Vanilla's backend loop creates the window with {@link #setWindowHints()} first and calls
 * {@link #createDevice} afterwards; without a ready WebGPU device in the page's glue it throws
 * {@link BackendCreationException}, so the loop closes the window and moves on to WebGL 2.</p>
 */
public final class B1Backend implements GpuBackend {
    @Override
    public String getName() {
        return "WebGPU";
    }

    /** Like vanilla's Vulkan backend: a window without a client API (no GL context). */
    @Override
    public void setWindowHints() {
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
    }

    @Override
    public void handleWindowCreationErrors(GLFWErrorCapture.Error error) throws BackendCreationException {
        if (error != null) {
            throw new BackendCreationException(String.format(Locale.ROOT, "GLFW_ERROR: 0x%X", error.error()),
                    BackendCreationException.Reason.GLFW_ERROR);
        }
        throw new BackendCreationException("Failed to create window for WebGPU", BackendCreationException.Reason.GLFW_ERROR);
    }

    @Override
    public GpuDevice createDevice(long window, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions,
            Runnable criticalShaderLoader) throws BackendCreationException {
        requireDevice();
        return new GpuDevice(new B1Device(CoreDevice.create(debugOptions.useLabels()), defaultShaderSource, true),
                criticalShaderLoader);
    }

    /**
     * The backend device without a window and without the GLFW frame yield in
     * {@code present()} (harnesses and tests).
     */
    public static B1Device createBackendDevice(ShaderSource defaultShaderSource, boolean debug)
            throws BackendCreationException {
        requireDevice();
        return new B1Device(CoreDevice.create(debug), defaultShaderSource, false);
    }

    /**
     * A ready core device, and the page's shader toolchain: the core's GLSL front-end compiles
     * every pipeline with it, so without it no pipeline would be valid and vanilla's required
     * shaders would fail after the window and device exist. Throwing here lets vanilla's loop
     * fall back to WebGL 2 instead.
     */
    private static void requireDevice() throws BackendCreationException {
        if (!CoreDevice.isAvailable()) {
            throw new BackendCreationException("WebGPU is not available: " + CoreDevice.failure(),
                    BackendCreationException.Reason.OTHER);
        }
        if (!shaderToolchainLoaded()) {
            throw new BackendCreationException("WebGPU is not available: the page's shader toolchain is not loaded"
                    + " (window.__gaiusShaderToolchain)", BackendCreationException.Reason.OTHER);
        }
    }

    @JSBody(script = "var t = globalThis.__gaiusShaderToolchain; return !!(t && t.shadercBegin);")
    private static native boolean shaderToolchainLoaded();
}
