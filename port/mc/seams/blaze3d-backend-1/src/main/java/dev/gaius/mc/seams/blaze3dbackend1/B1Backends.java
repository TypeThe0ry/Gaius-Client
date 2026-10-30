package dev.gaius.mc.seams.blaze3dbackend1;

import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.systems.GpuBackend;
import org.teavm.jso.JSBody;

/**
 * The backend list for {@code PreferredGraphicsApi.getBackendsToTry()}, the seam's registration
 * point (the generic patch {@code Minecraft262BrowserPatcher.patchPreferredGraphicsApi} replaces
 * that method's body with a call of this one on WebAssembly GC): WebGPU first when the page asks
 * for it ({@code ?gpu=webgpu}, or {@code GaiusWebGpu.requested}), then the WebGL 2 backend, which
 * vanilla's loop falls back to on a {@code BackendCreationException}.
 */
public final class B1Backends {
    private B1Backends() {
    }

    public static GpuBackend[] backendsToTry() {
        if (webGpuRequested()) {
            return new GpuBackend[] { new B1Backend(), new GlBackend() };
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
