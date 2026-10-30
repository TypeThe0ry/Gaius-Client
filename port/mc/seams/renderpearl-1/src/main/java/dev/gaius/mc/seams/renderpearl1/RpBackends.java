package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import org.teavm.jso.JSBody;

/**
 * The backend list for {@code PreferredGraphicsApi.getBackendsToTry()} (the seam's registration
 * point on both targets): WebGPU first when the page asks for it ({@code ?gpu=webgpu}) and the glue
 * has a device, then the WebGL 2 backend, which vanilla's loop falls back to on a
 * {@code BackendCreationException}.
 */
public final class RpBackends {
    private RpBackends() {
    }

    public static GpuBackend[] backendsToTry() {
        if (webGpuRequested()) {
            return new GpuBackend[] { new RpBackend(), new GlBackend() };
        }
        return new GpuBackend[] { new GlBackend() };
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
}
