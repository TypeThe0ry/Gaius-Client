/*
 * Copyright (c) LWJGL
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Derived from the LWJGL GLCapabilities API.
 * Modified for Gaius: expose only browser-supported capabilities.
 */

package org.lwjgl.opengl;

/**
 * WebGL2 capability set used by the original Minecraft renderer.
 *
 * <p>Desktop-only extension flags are deliberately disabled so Blaze3D uses
 * the OpenGL 3.x fallback paths that map directly to WebGL2. Flags that have a
 * real WebGL2 extension behind them are probed on the live context when the
 * capability set is created.</p>
 */
public final class GLCapabilities {
    public final boolean OpenGL30 = true;
    public final boolean OpenGL31 = true;
    public final boolean OpenGL32 = true;
    public final boolean OpenGL33 = true;

    public final boolean GL_ARB_buffer_storage = false;
    public final boolean GL_ARB_base_instance = false;
    public final boolean GL_ARB_clip_control = false;
    public final boolean GL_ARB_debug_output = false;
    public final boolean GL_ARB_direct_state_access = false;
    public final boolean GL_ARB_draw_indirect = false;
    public final boolean GL_ARB_multi_draw_indirect = false;
    public final boolean GL_ARB_shader_draw_parameters = false;
    public final boolean GL_ARB_vertex_attrib_binding = false;
    public final boolean GL_EXT_debug_label = false;
    /**
     * EXT_texture_filter_anisotropic, probed on the live context. The renderer
     * then reads MAX_TEXTURE_MAX_ANISOTROPY through glGetFloat and sets it per
     * sampler through glSamplerParameterf; both map straight to the WebGL
     * extension once it is enabled (BrowserOpenGL.initialize enables it).
     */
    public final boolean GL_EXT_texture_filter_anisotropic;
    public final boolean GL_KHR_debug = false;

    public GLCapabilities() {
        GL_EXT_texture_filter_anisotropic = BrowserOpenGL.anisotropicFilteringAvailable();
    }
}
