package org.lwjgl.sdl;

import dev.gaius.browser.BrowserClipboard;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

/**
 * Browser implementation behind the LWJGL 3.4.3 SDL3 bindings used by Minecraft
 * 26.3 (work package P2, decision D3).
 *
 * <p>{@code dev.gaius.tools.LwjglSdlBrowserPatcher} replaces the body of every
 * {@code SDL_*} entry point the client reaches (84) with an {@code invokestatic} of
 * the method of the same name and descriptor here; {@code SDL.<clinit>} no longer
 * loads a native library. The method bodies are redirected rather than the client's
 * call sites because {@code SDLTimer.SDL_GetTicksNS} is also Minecraft's
 * {@code NanoTimeSource}, referenced through a method handle.
 *
 * <p>This class only marshals arguments; the event engine is
 * {@link BrowserSdlEvents}/{@link BrowserSdlDom}, windows and the GL context are
 * {@link BrowserSdlVideo}, keyboard tables are {@link BrowserSdlKeyboard}.
 */
public final class BrowserSdl {
    private static final String NO_ERROR = "";

    private static final Map<String, String> HINTS = new HashMap<>();
    private static String error = NO_ERROR;
    private static boolean initialized;

    private BrowserSdl() {
    }

    static void setError(String message) {
        error = message == null ? NO_ERROR : message;
    }

    private static String string(CharSequence value) {
        return value == null ? null : value.toString();
    }

    private static void put(IntBuffer buffer, int value) {
        if (buffer != null) {
            buffer.put(buffer.position(), value);
        }
    }

    private static void put(FloatBuffer buffer, float value) {
        if (buffer != null) {
            buffer.put(buffer.position(), value);
        }
    }

    // ------------------------------------------------------------------ SDLInit

    public static boolean SDL_Init(int flags) {
        if (!initialized) {
            BrowserClipboard.install();
            BrowserSdlDom.installDomBridge();
            initialized = true;
        }
        return true;
    }

    public static void SDL_Quit() {
    }

    public static boolean SDL_SetAppMetadataProperty(CharSequence name, CharSequence value) {
        return true;
    }

    // ------------------------------------------------------------------ SDLHints / SDLError / SDLLog

    public static boolean SDL_SetHint(CharSequence name, CharSequence value) {
        if (name == null) {
            return false;
        }
        HINTS.put(name.toString(), string(value));
        return true;
    }

    /** Never null: Minecraft concatenates it into log and exception messages. */
    public static String SDL_GetError() {
        return error == null ? NO_ERROR : error;
    }

    public static void SDL_SetLogOutputFunction(SDL_LogOutputFunctionI callback, long userdata) {
    }

    public static void SDL_SetLogPriorities(int priority) {
    }

    // ------------------------------------------------------------------ SDLTimer / SDLPlatform / SDLMisc

    /** Monotonic nanoseconds; Minecraft's NanoTimeSource and Blaze3D.getTime. */
    public static long SDL_GetTicksNS() {
        return (long) (BrowserSdlDom.nowMillis() * 1_000_000.0);
    }

    public static String SDL_GetPlatform() {
        return "Browser";
    }

    public static boolean SDL_OpenURL(CharSequence url) {
        if (url == null || !BrowserSdlDom.openUrl(url.toString())) {
            setError("Only http and https links can be opened in the browser");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ SDLMessageBox

    public static boolean SDL_ShowSimpleMessageBox(int flags, CharSequence title, CharSequence message, long window) {
        BrowserSdlDom.consoleError(string(title) + ": " + string(message));
        return true;
    }

    /**
     * Logs the message and answers with button id 1 ("continue"): a blocking
     * {@code confirm()} on the game thread is not an option. GameNarrator therefore
     * no longer throws NarratorInitException (26.2 answered "no").
     */
    public static boolean SDL_ShowMessageBox(SDL_MessageBoxData data, IntBuffer buttonid) {
        String title = data == null ? "" : data.titleString();
        String message = data == null ? "" : data.messageString();
        BrowserSdlDom.consoleError(title + ": " + message);
        put(buttonid, 1);
        return true;
    }

    // ------------------------------------------------------------------ SDLEvents

    public static void SDL_PumpEvents() {
    }

    public static boolean SDL_PollEvent(SDL_Event event) {
        return BrowserSdlEvents.poll(event);
    }

    public static void SDL_FlushEvents(int minType, int maxType) {
        BrowserSdlEvents.flush(minType, maxType);
    }

    public static long SDL_GetWindowFromEvent(SDL_Event event) {
        return BrowserSdlEvents.windowFromEvent(event);
    }

    // ------------------------------------------------------------------ SDLKeyboard

    public static ByteBuffer SDL_GetKeyboardState() {
        return BrowserSdlKeyboard.state();
    }

    public static short SDL_GetModState() {
        return (short) BrowserSdlDom.modState();
    }

    public static int SDL_GetKeyFromScancode(int scancode, short modstate, boolean keyEvent) {
        int keycode = initialized ? BrowserSdlDom.keycodeForScancode(scancode) : 0;
        return keycode != 0 ? keycode : BrowserSdlKeyboard.usKeycode(scancode);
    }

    public static String SDL_GetKeyName(int key) {
        return BrowserSdlKeyboard.keyName(key);
    }

    public static boolean SDL_StartTextInput(long window) {
        BrowserSdlDom.setTextInput(true);
        return true;
    }

    public static boolean SDL_StopTextInput(long window) {
        BrowserSdlDom.setTextInput(false);
        return true;
    }

    public static boolean SDL_ClearComposition(long window) {
        return true;
    }

    public static boolean SDL_SetTextInputArea(long window, SDL_Rect.Buffer rect, int cursor) {
        if (rect != null && rect.remaining() > 0) {
            SDL_Rect area = rect.get(rect.position());
            BrowserSdlDom.setTextInputArea(area.x(), area.y(), area.w(), area.h(), cursor);
        }
        return true;
    }

    // ------------------------------------------------------------------ SDLMouse

    public static long SDL_CreateSystemCursor(int id) {
        return id + 1L;
    }

    public static long SDL_GetDefaultCursor() {
        return 1L;
    }

    /** The canvas cursor follows the SDL system cursor (0 = SDL_SYSTEM_CURSOR_DEFAULT). */
    public static boolean SDL_SetCursor(long cursor) {
        BrowserSdlDom.setCursor(cursor <= 0L ? 0 : (int) (cursor - 1L));
        return true;
    }

    public static int SDL_GetMouseState(FloatBuffer x, FloatBuffer y) {
        put(x, (float) BrowserSdlDom.cursorX());
        put(y, (float) BrowserSdlDom.cursorY());
        return BrowserSdlDom.buttonState();
    }

    /** The window sits at 0,0 of the only display, so global and window coordinates agree. */
    public static int SDL_GetGlobalMouseState(FloatBuffer x, FloatBuffer y) {
        return SDL_GetMouseState(x, y);
    }

    public static void SDL_WarpMouseInWindow(long window, float x, float y) {
        BrowserSdlDom.warpMouse(x, y);
    }

    public static boolean SDL_SetWindowRelativeMouseMode(long window, boolean enabled) {
        BrowserSdlDom.setRelativeMouseMode(enabled);
        return true;
    }

    // ------------------------------------------------------------------ SDLClipboard

    public static boolean SDL_SetClipboardText(CharSequence text) {
        BrowserSdlDom.writeClipboard(text == null ? "" : text.toString());
        return true;
    }

    /** Never null; the clipboard bridge refreshes it before a paste chord reaches the game. */
    public static String SDL_GetClipboardText() {
        String text = BrowserSdlDom.readClipboard();
        return text == null ? "" : text;
    }

    // ------------------------------------------------------------------ SDLVideo: windows

    public static long SDL_CreateWindow(CharSequence title, int w, int h, long flags) {
        return BrowserSdlVideo.createWindow(string(title), w, h, flags);
    }

    public static void SDL_DestroyWindow(long window) {
        BrowserSdlVideo.destroyWindow(window);
    }

    public static long SDL_GetWindowFlags(long window) {
        return BrowserSdlVideo.windowFlags(window);
    }

    public static boolean SDL_GetWindowSizeInPixels(long window, IntBuffer w, IntBuffer h) {
        put(w, BrowserSdlVideo.pixelWidth());
        put(h, BrowserSdlVideo.pixelHeight());
        return true;
    }

    public static float SDL_GetWindowPixelDensity(long window) {
        return BrowserSdlVideo.pixelDensity();
    }

    public static boolean SDL_GetWindowPosition(long window, IntBuffer x, IntBuffer y) {
        put(x, 0);
        put(y, 0);
        return true;
    }

    public static boolean SDL_SetWindowPosition(long window, int x, int y) {
        return true;
    }

    public static boolean SDL_SetWindowSize(long window, int w, int h) {
        return BrowserSdlVideo.setWindowSize(window, w, h);
    }

    public static boolean SDL_SetWindowMinimumSize(long window, int minW, int minH) {
        return true;
    }

    public static boolean SDL_SetWindowMaximumSize(long window, int maxW, int maxH) {
        return true;
    }

    public static boolean SDL_SetWindowBordered(long window, boolean bordered) {
        return true;
    }

    public static boolean SDL_RestoreWindow(long window) {
        return true;
    }

    public static boolean SDL_SyncWindow(long window) {
        return true;
    }

    public static boolean SDL_SetWindowMouseGrab(long window, boolean grabbed) {
        return true;
    }

    public static boolean SDL_SetWindowTitle(long window, CharSequence title) {
        return BrowserSdlVideo.setWindowTitle(window, string(title));
    }

    public static boolean SDL_SetWindowIcon(long window, SDL_Surface icon) {
        return true;
    }

    public static boolean SDL_SetWindowFullscreen(long window, boolean fullscreen) {
        return BrowserSdlVideo.setFullscreen(window, fullscreen);
    }

    public static boolean SDL_SetWindowFullscreenMode(long window, SDL_DisplayMode mode) {
        return true;
    }

    /** Never exclusive fullscreen. */
    public static SDL_DisplayMode SDL_GetWindowFullscreenMode(long window) {
        return null;
    }

    // ------------------------------------------------------------------ SDLVideo: displays

    public static IntBuffer SDL_GetDisplays() {
        return BrowserSdlVideo.displays();
    }

    public static int SDL_GetPrimaryDisplay() {
        return BrowserSdlVideo.DISPLAY_ID;
    }

    public static int SDL_GetDisplayForWindow(long window) {
        return BrowserSdlVideo.DISPLAY_ID;
    }

    public static String SDL_GetDisplayName(int displayID) {
        return displayID == BrowserSdlVideo.DISPLAY_ID ? "Browser Display" : null;
    }

    public static boolean SDL_GetDisplayBounds(int displayID, SDL_Rect rect) {
        if (rect == null || displayID != BrowserSdlVideo.DISPLAY_ID) {
            return false;
        }
        SDL_DisplayMode mode = BrowserSdlVideo.displayMode();
        rect.set(0, 0, mode.w(), mode.h());
        return true;
    }

    public static SDL_DisplayMode SDL_GetDesktopDisplayMode(int displayID) {
        return displayID == BrowserSdlVideo.DISPLAY_ID ? BrowserSdlVideo.displayMode() : null;
    }

    public static SDL_DisplayMode SDL_GetCurrentDisplayMode(int displayID) {
        return SDL_GetDesktopDisplayMode(displayID);
    }

    public static PointerBuffer SDL_GetFullscreenDisplayModes(int displayID) {
        return displayID == BrowserSdlVideo.DISPLAY_ID ? BrowserSdlVideo.fullscreenModes() : null;
    }

    public static boolean SDL_GetClosestFullscreenDisplayMode(
            int displayID, int w, int h, float refreshRate, boolean includeHighDensityModes,
            SDL_DisplayMode closest) {
        if (closest == null || displayID != BrowserSdlVideo.DISPLAY_ID) {
            return false;
        }
        closest.set(BrowserSdlVideo.displayMode());
        return true;
    }

    public static String SDL_GetCurrentVideoDriver() {
        return "browser";
    }

    /** VideoMode treats a null format description as 8/8/8 bits. */
    public static SDL_PixelFormatDetails SDL_GetPixelFormatDetails(int format) {
        return null;
    }

    /** Memory returned by the shim is persistent; Minecraft frees display lists with SDL_free. */
    public static void SDL_free(IntBuffer mem) {
    }

    public static void SDL_free(PointerBuffer mem) {
    }

    // ------------------------------------------------------------------ SDLSurface (window icons)

    /** No surfaces: Window.setIcon then closes the decoded icon images and returns. */
    public static SDL_Surface SDL_CreateSurfaceFrom(int width, int height, int format, ByteBuffer pixels, int pitch) {
        setError("Window icons are not supported in the browser");
        return null;
    }

    public static boolean SDL_AddSurfaceAlternateImage(SDL_Surface surface, SDL_Surface image) {
        return false;
    }

    public static void SDL_DestroySurface(SDL_Surface surface) {
    }

    // ------------------------------------------------------------------ SDLVideo: OpenGL

    public static boolean SDL_GL_LoadLibrary(CharSequence path) {
        return true;
    }

    public static void SDL_GL_UnloadLibrary() {
    }

    /** Same FNV-1a hash as BrowserSharedLibrary.getFunctionAddress; never 0. */
    public static long SDL_GL_GetProcAddress(CharSequence proc) {
        if (proc == null) {
            return 0L;
        }
        long hash = 0xcbf29ce484222325L;
        String name = proc.toString();
        for (int index = 0; index < name.length(); index++) {
            char ch = name.charAt(index);
            if (ch == 0) {
                break;
            }
            hash ^= ch & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash == 0L ? 1L : hash;
    }

    public static boolean SDL_GL_SetAttribute(int attr, int value) {
        return BrowserSdlVideo.setGlAttribute(attr, value);
    }

    public static boolean SDL_GL_GetAttribute(int attr, IntBuffer value) {
        put(value, BrowserSdlVideo.glAttribute(attr));
        return true;
    }

    public static long SDL_GL_CreateContext(long window) {
        return BrowserSdlVideo.createContext(window);
    }

    public static boolean SDL_GL_MakeCurrent(long window, long context) {
        return true;
    }

    public static boolean SDL_GL_DestroyContext(long context) {
        return true;
    }

    public static boolean SDL_GL_SetSwapInterval(int interval) {
        BrowserSdlVideo.setSwapInterval(interval);
        return true;
    }

    /** Frame boundary: present bookkeeping plus the cooperative frame yield. */
    public static boolean SDL_GL_SwapWindow(long window) {
        BrowserSdlDom.swapWindow(BrowserSdlVideo.swapInterval());
        return true;
    }

    // ------------------------------------------------------------------ SDLVulkan (the GL backend is forced)

    public static boolean SDL_Vulkan_LoadLibrary(CharSequence path) {
        setError("Vulkan is not available in the browser");
        return false;
    }

    public static void SDL_Vulkan_UnloadLibrary() {
    }

    public static long SDL_Vulkan_GetVkGetInstanceProcAddr() {
        return 0L;
    }

    public static PointerBuffer SDL_Vulkan_GetInstanceExtensions() {
        return null;
    }

    public static boolean SDL_Vulkan_CreateSurface(
            long window, VkInstance instance, VkAllocationCallbacks allocator, LongBuffer surface) {
        return false;
    }

    public static boolean SDL_Vulkan_GetPresentationSupport(
            VkInstance instance, VkPhysicalDevice physicalDevice, int queueFamilyIndex) {
        return false;
    }
}
