package dev.gaius.mc.seams.blaze3ddirect0;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.gaius.gpu.webgpu.CoreDevice;
import org.teavm.jso.JSBody;

/**
 * The device factory of 1.21.11, the seam's registration point. 1.21.11 has no backend loop:
 * {@code RenderSystem.initRenderer(long, int, boolean, ShaderSource, boolean)} constructs the one
 * {@code GlDevice} itself ({@code new GlDevice; dup; lload_0; iload_2; iload_3; aload 4;
 * iload 5; invokespecial <init>(JIZLShaderSource;Z)V}, bytecode 0-11). The REQUIRED registration
 * patch ({@code Minecraft12111BrowserPatcher.patchInitRendererDeviceFactory}) replaces those
 * instructions by {@code lload_0; iload_2; iload_3; aload 4; iload 5; invokestatic
 * D0Devices.create(JIZLShaderSource;Z)GpuDevice}, which leaves the same value for the following
 * {@code putstatic DEVICE} (see DESIGN.md).
 *
 * <p>WebGPU when the page asks for it ({@code ?gpu=webgpu}), the glue has a device and the page's
 * shader toolchain is loaded (the core's GLSL front-end compiles every pipeline with it), else
 * vanilla's {@code GlDevice} with the original arguments, so the GL path is unchanged.</p>
 *
 * <p>The canvas: 1.21.11's {@code Window} creates the window with an OpenGL 3.3 core hint before
 * {@code initRenderer}, so the GLFW shim holds a GL context for it, attached lazily (at
 * {@code glfwMakeContextCurrent}, which only {@code GlDevice} calls, or at the first read of
 * {@code window.__gaiusWebGL}), and a canvas keeps its first context kind. Once the WebGPU
 * device exists, {@link #releaseGlCanvas()} keeps that context off the canvas WebGPU takes.
 * A WebGPU failure before that point (no device, a failed device creation, the {@code after-configure} fallback
 * test, which fails the glue's init) leaves the GL canvas alone and creates {@code GlDevice}.</p>
 */
public final class D0Devices {
    private D0Devices() {
    }

    /** {@code GlDevice(long window, int debugVerbosity, boolean synchronousLogs, ShaderSource, boolean labels)}. */
    public static GpuDevice create(long window, int debugVerbosity, boolean synchronousLogs, ShaderSource shaderSource,
            boolean debugLabels) {
        if (webGpuRequested()) {
            String unavailable = !CoreDevice.isAvailable() ? CoreDevice.failure()
                    : !shaderToolchainLoaded() ? "the page's shader toolchain is not loaded" : null;
            if (unavailable == null) {
                try {
                    D0Device device = new D0Device(CoreDevice.create(debugLabels), shaderSource, window);
                    releaseGlCanvas();
                    return device;
                } catch (RuntimeException e) {
                    unavailable = String.valueOf(e);
                }
            }
            // Falls through to GL; the reason stays in the log.
            System.err.println("WebGPU is not available (" + unavailable + "), using OpenGL");
        }
        return new GlDevice(window, debugVerbosity, synchronousLogs, shaderSource, debugLabels);
    }

    /**
     * The padding before {@code LineWidth} in {@code POSITION_COLOR_NORMAL_LINE_WIDTH} (the REQUIRED
     * {@code patchLineWidthVertexFormatAlignment} calls it from {@code DefaultVertexFormat.<clinit>}):
     * 1 when the page asks for WebGPU, which addresses vertex attributes and strides in whole
     * 4-byte words (LineWidth moves from 19 to 20, the stride from 23 to 24), else 0, which leaves
     * vanilla's layout. GL reads both layouts from the format, so a WebGPU request that falls back
     * to GL still draws the same lines.
     */
    public static int lineWidthPadding() {
        return webGpuRequested() ? 1 : 0;
    }

    @JSBody(script = """
            try {
              var g = globalThis.GaiusWebGpu;
              if (g && g.requested === true) return true;
              var search = globalThis.location ? String(globalThis.location.search) : "";
              return /[?&]gpu=webgpu(&|$)/.test(search);
            } catch (e) {
              return false;
            }
            """)
    private static native boolean webGpuRequested();

    @JSBody(script = "var t = globalThis.__gaiusShaderToolchain; return !!(t && t.shadercBegin);")
    private static native boolean shaderToolchainLoaded();

    /**
     * Keeps the GLFW shim's GL context off the WebGPU canvas. 1.21.11 creates an OpenGL window,
     * whose context the shim attaches at {@code glfwMakeContextCurrent} (only {@code GlDevice}
     * calls it) or at the first read of {@code window.__gaiusWebGL}. Not attached yet: the lazy
     * attach is disarmed and {@code window.__gaiusWebGL} becomes {@code null} (no GL this session),
     * so a later reader cannot take the canvas WebGPU renders to. Attached to the current canvas:
     * the canvas host replaces the element (rule 2) and the GL context stays on the detached one.
     * The state is {@code BrowserGlfw.deferContext}'s ({@code window.__gaiusGlfwContext}).
     */
    @JSBody(script = """
            var w = window;
            var state = w.__gaiusGlfwContext;
            if (!state) return;
            if (state.pending) {
              state.pending = false;
              Object.defineProperty(w, '__gaiusWebGL',
                {configurable: true, enumerable: true, writable: true, value: null});
              return;
            }
            var host = w.__gaiusCanvasHost;
            var canvas = host ? host.current() : null;
            if (state.gl && canvas && state.gl.canvas === canvas) host.replace('webgpu-device');
            """)
    private static native void releaseGlCanvas();
}
