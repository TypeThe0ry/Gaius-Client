package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.memPutInt;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Windows, the WebGL 2 context and the single display of the browser SDL3 shim.
 *
 * <p>There is one {@code #mc-canvas} and one WebGL 2 context. Window handles are
 * small integers that double as SDL window ids. The first window without
 * {@code SDL_WINDOW_HIDDEN} is the main window: it adopts the canvas (size and
 * title). Hidden windows (the renderpearl GL backend's utility and test windows)
 * never touch the canvas, and {@code SDL_DestroyWindow} never removes it.
 *
 * <p>Contract C5 (P2 &rarr; P3/P4): {@code window.__gaiusWebGL} exists when
 * {@code SDL_GL_CreateContext} returns a non-zero context, and already after the
 * first {@code SDL_CreateWindow} with {@code SDL_WINDOW_OPENGL}, hidden or not.
 * {@code SDL_GL_SwapWindow} is the per-frame cooperative yield.
 */
final class BrowserSdlVideo {
    static final long SDL_WINDOW_FULLSCREEN = SDLVideo.SDL_WINDOW_FULLSCREEN;
    static final long SDL_WINDOW_OPENGL = SDLVideo.SDL_WINDOW_OPENGL;
    static final long SDL_WINDOW_HIDDEN = SDLVideo.SDL_WINDOW_HIDDEN;
    static final long SDL_WINDOW_RESIZABLE = SDLVideo.SDL_WINDOW_RESIZABLE;
    static final long SDL_WINDOW_INPUT_FOCUS = SDLVideo.SDL_WINDOW_INPUT_FOCUS;
    static final long SDL_WINDOW_MOUSE_FOCUS = SDLVideo.SDL_WINDOW_MOUSE_FOCUS;
    static final long SDL_WINDOW_HIGH_PIXEL_DENSITY = SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY;
    static final long SDL_WINDOW_MOUSE_RELATIVE_MODE = SDLVideo.SDL_WINDOW_MOUSE_RELATIVE_MODE;
    static final int PIXELFORMAT_XRGB8888 = SDLPixels.SDL_PIXELFORMAT_XRGB8888;
    static final int DISPLAY_ID = 1;
    static final long GL_CONTEXT = 1L;

    private static final Map<Long, Long> WINDOW_FLAGS = new HashMap<>();
    private static final int[] GL_ATTRIBUTES = new int[32];
    private static long nextWindow = 1L;
    private static long mainWindow;
    private static boolean contextReady;
    private static int swapInterval;
    private static SDL_DisplayMode displayMode;
    private static IntBuffer displays;
    private static PointerBuffer fullscreenModes;

    static {
        GL_ATTRIBUTES[SDLVideo.SDL_GL_RED_SIZE] = 8;
        GL_ATTRIBUTES[SDLVideo.SDL_GL_GREEN_SIZE] = 8;
        GL_ATTRIBUTES[SDLVideo.SDL_GL_BLUE_SIZE] = 8;
        GL_ATTRIBUTES[SDLVideo.SDL_GL_ALPHA_SIZE] = 0;
        GL_ATTRIBUTES[SDLVideo.SDL_GL_DOUBLEBUFFER] = 1;
        GL_ATTRIBUTES[SDLVideo.SDL_GL_DEPTH_SIZE] = 24;
        GL_ATTRIBUTES[SDLVideo.SDL_GL_STENCIL_SIZE] = 8;
    }

    private BrowserSdlVideo() {
    }

    // ------------------------------------------------------------------ windows

    static long createWindow(String title, int width, int height, long flags) {
        long handle = nextWindow++;
        WINDOW_FLAGS.put(handle, flags);
        BrowserSdlDom.prepareCanvas();
        boolean hidden = (flags & SDL_WINDOW_HIDDEN) != 0;
        if (!hidden && mainWindow == 0L) {
            mainWindow = handle;
            BrowserSdlDom.showMainWindow(Math.max(1, width), Math.max(1, height), title == null ? "Minecraft" : title);
        }
        if ((flags & SDL_WINDOW_OPENGL) != 0L && !ensureContext()) {
            destroyWindow(handle);
            return 0L;
        }
        return handle;
    }

    static void destroyWindow(long window) {
        WINDOW_FLAGS.remove(window);
        if (window == mainWindow) {
            mainWindow = 0L;
        }
    }

    static boolean exists(long window) {
        return WINDOW_FLAGS.containsKey(window);
    }

    static boolean isMainWindow(long window) {
        return window != 0L && window == mainWindow;
    }

    static int eventWindowId() {
        return (int) mainWindow;
    }

    static long windowFromId(int id) {
        long handle = id & 0xFFFFFFFFL;
        return handle != 0L && WINDOW_FLAGS.containsKey(handle) ? handle : 0L;
    }

    /** Flags stay consistent with the events: Minecraft reads FULLSCREEN back after every mode change. */
    static long windowFlags(long window) {
        Long stored = WINDOW_FLAGS.get(window);
        if (stored == null) {
            return 0L;
        }
        if (window != mainWindow) {
            return stored;
        }
        long flags = SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY;
        if (BrowserSdlDom.focused()) {
            flags |= SDL_WINDOW_INPUT_FOCUS | SDL_WINDOW_MOUSE_FOCUS;
        }
        if (BrowserSdlDom.relativeMouseMode()) {
            flags |= SDL_WINDOW_MOUSE_RELATIVE_MODE;
        }
        if (BrowserSdlDom.fullscreen()) {
            flags |= SDL_WINDOW_FULLSCREEN;
        }
        return flags;
    }

    static boolean setWindowSize(long window, int width, int height) {
        if (!exists(window)) {
            return false;
        }
        if (window == mainWindow) {
            BrowserSdlDom.resizeCanvas(Math.max(1, width), Math.max(1, height));
        }
        return true;
    }

    static boolean setWindowTitle(long window, String title) {
        if (!exists(window)) {
            return false;
        }
        if (window == mainWindow) {
            BrowserSdlDom.setTitle(title == null ? "" : title);
        }
        return true;
    }

    static boolean setFullscreen(long window, boolean fullscreen) {
        if (!exists(window)) {
            return false;
        }
        if (window != mainWindow) {
            return true;
        }
        if (!BrowserSdlDom.setFullscreen(fullscreen)) {
            BrowserSdl.setError("Browser fullscreen is not available");
            return false;
        }
        return true;
    }

    static int pixelWidth() {
        return Math.max(1, BrowserSdlDom.framebufferWidth());
    }

    static int pixelHeight() {
        return Math.max(1, BrowserSdlDom.framebufferHeight());
    }

    static float pixelDensity() {
        int css = BrowserSdlDom.canvasCssWidth();
        if (css <= 0) {
            return 1.0f;
        }
        float density = (float) BrowserSdlDom.framebufferWidth() / (float) css;
        return density > 0.0f ? density : 1.0f;
    }

    // ------------------------------------------------------------------ GL context

    /** Contract C5: the WebGL 2 context exists once this returns true. */
    static boolean ensureContext() {
        if (contextReady) {
            return true;
        }
        BrowserSdlDom.prepareCanvas();
        if (!BrowserSdlDom.createContext()) {
            BrowserSdl.setError("WebGL 2 is required");
            return false;
        }
        contextReady = true;
        return true;
    }

    static long createContext(long window) {
        if (!exists(window)) {
            BrowserSdl.setError("Invalid window");
            return 0L;
        }
        return ensureContext() ? GL_CONTEXT : 0L;
    }

    static boolean setGlAttribute(int attribute, int value) {
        if (attribute < 0 || attribute >= GL_ATTRIBUTES.length) {
            return false;
        }
        GL_ATTRIBUTES[attribute] = value;
        return true;
    }

    /**
     * The WebGL 2 context is reported as OpenGL 3.3 core (the renderpearl GL backend
     * requires at least 3.3), without alpha, with 24-bit depth and 8-bit stencil.
     */
    static int glAttribute(int attribute) {
        return switch (attribute) {
            case SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION -> 3;
            case SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION -> 3;
            case SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK -> SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE;
            default -> attribute >= 0 && attribute < GL_ATTRIBUTES.length ? GL_ATTRIBUTES[attribute] : 0;
        };
    }

    static void setSwapInterval(int interval) {
        swapInterval = interval;
    }

    static int swapInterval() {
        return swapInterval;
    }

    // ------------------------------------------------------------------ display

    static IntBuffer displays() {
        if (displays == null) {
            displays = MemoryUtil.memAllocInt(1);
            displays.put(0, DISPLAY_ID);
        }
        displays.clear().limit(1);
        return displays;
    }

    /** One persistent SDL_DisplayMode for the screen, refreshed on every lookup. */
    static SDL_DisplayMode displayMode() {
        if (displayMode == null) {
            displayMode = SDL_DisplayMode.calloc();
        }
        memPutInt(displayMode.address() + SDL_DisplayMode.DISPLAYID, DISPLAY_ID);
        displayMode.format(PIXELFORMAT_XRGB8888);
        displayMode.w(Math.max(1, BrowserSdlDom.screenWidth()));
        displayMode.h(Math.max(1, BrowserSdlDom.screenHeight()));
        displayMode.pixel_density((float) Math.max(0.1, BrowserSdlDom.devicePixelRatio()));
        displayMode.refresh_rate(60.0f);
        displayMode.refresh_rate_numerator(60);
        displayMode.refresh_rate_denominator(1);
        return displayMode;
    }

    static PointerBuffer fullscreenModes() {
        SDL_DisplayMode mode = displayMode();
        if (fullscreenModes == null) {
            fullscreenModes = MemoryUtil.memAllocPointer(1);
        }
        fullscreenModes.clear().limit(1);
        fullscreenModes.put(0, mode.address());
        return fullscreenModes;
    }
}
