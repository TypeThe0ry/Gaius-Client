package org.lwjgl.opengl;

import org.teavm.jso.JSBody;

/**
 * WebGL2 implementations of the OpenGL entry points that LWJGL 3.4.3 clients (Minecraft 26.3)
 * call and that {@link BrowserOpenGL} does not cover: {@code GL30C.glEnablei},
 * {@code GL30C.glDisablei} and {@code GL11C.glReadBuffer}.
 *
 * <p>This class lives in the lwjgl-opengl 3.4.3 version directory, so the 3.4.1 overlay (26.2)
 * never contains it and {@code BrowserOpenGL} stays byte-identical. LwjglOpenGLBrowserPatcher
 * delegates the three natives here only when the overlay jar contains this class.
 *
 * <p>26.3 enables blending per colour attachment ({@code GlStateManager._enableBlend(int)} calls
 * {@code glEnablei(GL_BLEND, index)}), so without these delegates the natives fall back to
 * no-ops and blending is never enabled. WebGL2 exposes per-draw-buffer enables through
 * {@code OES_draw_buffers_indexed}; without the extension, index 0 falls back to the global
 * {@code enable}/{@code disable} (the only attachment of every single-target pass) and other
 * indices are counted in {@code __gaiusGLStats.indexedCapabilityUnsupported}.
 */
public final class BrowserOpenGLIndexed {
    private BrowserOpenGLIndexed() {
    }

    /** {@code glEnablei(target, index)}. */
    public static void enablei(int target, int index) {
        if (!indexedCapabilityJs(target, index, true)) {
            BrowserOpenGL.enable(target);
        }
    }

    /** {@code glDisablei(target, index)}. */
    public static void disablei(int target, int index) {
        if (!indexedCapabilityJs(target, index, false)) {
            BrowserOpenGL.disable(target);
        }
    }

    /**
     * Applies an indexed enable/disable through OES_draw_buffers_indexed. Returns {@code false}
     * when the caller must fall back to the global capability (index 0 without the extension).
     *
     * <p>Every indexed change makes BrowserOpenGL's cached global state for the capability
     * unknown, so the next global enable/disable always reaches WebGL. Index 0 also updates the
     * tracked enabled state that BrowserOpenGL's diagnostics read.
     */
    @JSBody(params = {"target", "index", "enable"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const capability=target|0,drawBuffer=index|0;
            let extension=state ? state.drawBuffersIndexedExtension : undefined;
            if (extension===undefined) {
              extension=gl.getExtension('OES_draw_buffers_indexed') || null;
              if (state) state.drawBuffersIndexedExtension=extension;
            }
            if (extension && typeof extension.enableiOES==='function') {
              if (enable) {
                extension.enableiOES(capability,drawBuffer);
              } else {
                extension.disableiOES(capability,drawBuffer);
              }
              if (state) {
                state.knownCaps.delete(capability);
                if (drawBuffer===0) {
                  const bit=typeof state.capabilityBit==='function'
                    ? state.capabilityBit(capability) : 0;
                  if (enable) {
                    state.enabledCaps.add(capability);
                    if (bit) state.enabledCapBits|=bit;
                  } else {
                    state.enabledCaps.delete(capability);
                    if (bit) state.enabledCapBits&=~bit;
                  }
                }
              }
              return true;
            }
            if (drawBuffer===0) return false;
            const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
            stats.indexedCapabilityUnsupported=(stats.indexedCapabilityUnsupported||0)+1;
            return true;
            """)
    private static native boolean indexedCapabilityJs(int target, int index, boolean enable);

    /**
     * {@code glReadBuffer(mode)}. On the default framebuffer WebGL2 accepts only {@code BACK} and
     * {@code NONE}, so the desktop front/back-left names map to {@code BACK} there; every other
     * value is passed through unchanged.
     */
    @JSBody(params = {"mode"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            let value=mode|0;
            const readFramebuffer=state ? (state.framebufferBindings.read|0) : -1;
            if (readFramebuffer===0
                && (value===0x0400 || value===0x0402 || value===0x0404 || value===0x0408)) {
              value=gl.BACK;
            }
            gl.readBuffer(value);
            """)
    public static native void readBuffer(int mode);
}
