package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetByte;
import static org.lwjgl.system.MemoryUtil.memGetFloat;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memGetLong;
import static org.lwjgl.system.MemoryUtil.memGetShort;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * JVM unit test of the browser SDL3 shim (work package P2), run by
 * {@code port/scripts/lwjgl-sdl-browser-patcher-smoke.mjs} against the patched
 * lwjgl-sdl 3.4.3 jar (vanilla jar + overlay + LwjglSdlBrowserPatcher output) with the
 * real LWJGL core on a 64-bit JVM.
 *
 * <ul>
 *   <li>Every SDL_Event field written by {@link BrowserSdlEvents#write} lands on the
 *       offset of the matching lwjgl-sdl 3.4.3 {@code SDL_*Event} constant, with the
 *       constant's width, and reads back through LWJGL's own struct accessors. The
 *       offsets are also compared with the literal 64-bit layout of the migration notes
 *       (input-window 1.8), so a layout change of a future LWJGL is noticed.</li>
 *   <li>SDL_PollEvent / SDL_FlushEvents / SDL_GetKeyboardState through the patched
 *       {@code SDLEvents}/{@code SDLKeyboard} classes: the keyboard state mirror, text
 *       payload lifetime, re-entrant polling and the flush resync.</li>
 *   <li>The game-loop boundary (BrowserSdlDom.loopBoundary) is reached once per drain cycle
 *       of SDL_PollEvent, not per event and not for SDL_PollEvent(NULL).</li>
 *   <li>Pure-Java entry points through the redirected bodies: SDL.getLibrary() without a
 *       native library, key names, GL attributes, GetProcAddress, displays.</li>
 *   <li>The present yield's rule for frames presented from a JavaScript callback: only
 *       TeaVM's refusal to suspend outside a TeaVM thread is skipped.</li>
 * </ul>
 * Exits with status 1 on the first failed check.
 */
public final class BrowserSdlJvmTest {
    private static int checks;

    private BrowserSdlJvmTest() {
    }

    public static void main(String[] args) {
        layoutConstants();
        keyboardEventFields();
        textEventFields();
        mouseEventFields();
        windowEventFields();
        pollFlushAndKeyboardState();
        loopBoundaryPerDrainCycle();
        redirectedEntryPoints();
        nonThreadingPresentYield();
        System.out.println("BrowserSdlJvmTest passed: " + checks + " checks");
    }

    // ------------------------------------------------------------------ helpers

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(long expected, long actual, String message) {
        check(expected == actual, message + ": expected " + expected + " but was " + actual);
    }

    private static void equal(float expected, float actual, String message) {
        check(Float.compare(expected, actual) == 0, message + ": expected " + expected + " but was " + actual);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual),
                message + ": expected " + expected + " but was " + actual);
    }

    /** One scripted record: [type, i0..i3, f0..f3, text, timestamp]. */
    private record Rec(int type, int[] ints, double[] doubles, String text, double timestamp) {
        static Rec of(int type, int i0, int i1, int i2, int i3, double f0, double f1, double f2, double f3,
                String text) {
            return new Rec(type, new int[] {i0, i1, i2, i3}, new double[] {f0, f1, f2, f3}, text, 1234.5);
        }
    }

    /**
     * A Java model of the JavaScript engine's contract (queue head, state applied at
     * dequeue and flush) that the Node smoke checks against the real script.
     */
    private static final class FakeSource implements BrowserSdlEvents.Source {
        final Deque<Rec> queue = new ArrayDeque<>();
        final byte[] keys = new byte[512];
        Rec current;
        int loopBoundaries;

        void apply(Rec rec) {
            if (rec.type == BrowserSdlEvents.SDL_EVENT_KEY_DOWN || rec.type == BrowserSdlEvents.SDL_EVENT_KEY_UP) {
                int scancode = rec.ints[0];
                if (scancode > 0 && scancode < 512) {
                    keys[scancode] = (byte) (rec.type == BrowserSdlEvents.SDL_EVENT_KEY_DOWN ? 1 : 0);
                }
            }
        }

        @Override
        public int poll() {
            current = queue.pollFirst();
            if (current == null) {
                return 0;
            }
            apply(current);
            return current.type;
        }

        @Override
        public boolean peek() {
            return !queue.isEmpty();
        }

        @Override
        public int recordInt(int index) {
            return current.ints[index - BrowserSdlDom.REC_I0];
        }

        @Override
        public double recordDouble(int index) {
            return current.doubles[index - BrowserSdlDom.REC_F0];
        }

        @Override
        public String recordText() {
            return current.text;
        }

        @Override
        public double recordTimestampMillis() {
            return current.timestamp;
        }

        @Override
        public int flush(int min, int max) {
            int keyChanges = 0;
            Deque<Rec> kept = new ArrayDeque<>();
            for (Rec rec : queue) {
                if (rec.type >= min && rec.type <= max) {
                    apply(rec);
                    if (rec.type == BrowserSdlEvents.SDL_EVENT_KEY_DOWN || rec.type == BrowserSdlEvents.SDL_EVENT_KEY_UP) {
                        keyChanges++;
                    }
                } else {
                    kept.add(rec);
                }
            }
            queue.clear();
            queue.addAll(kept);
            return keyChanges;
        }

        @Override
        public int keyState(int scancode) {
            return keys[scancode & 511];
        }

        @Override
        public void loopBoundary() {
            loopBoundaries++;
        }
    }

    private static SDL_Event written(Rec rec, int windowId) {
        FakeSource source = new FakeSource();
        source.queue.add(rec);
        check(source.poll() == rec.type, "fake source dequeues");
        SDL_Event event = SDL_Event.calloc();
        BrowserSdlEvents.write(event.address(), rec.type, source, windowId);
        return event;
    }

    // ------------------------------------------------------------------ layout

    private static void layoutConstants() {
        equal(128, SDL_Event.SIZEOF, "SDL_Event.SIZEOF");
        equal(0, SDL_CommonEvent.TYPE, "SDL_CommonEvent.TYPE");
        equal(8, SDL_CommonEvent.TIMESTAMP, "SDL_CommonEvent.TIMESTAMP");
        equal(16, SDL_KeyboardEvent.WINDOWID, "SDL_KeyboardEvent.WINDOWID");
        equal(24, SDL_KeyboardEvent.SCANCODE, "SDL_KeyboardEvent.SCANCODE");
        equal(28, SDL_KeyboardEvent.KEY, "SDL_KeyboardEvent.KEY");
        equal(32, SDL_KeyboardEvent.MOD, "SDL_KeyboardEvent.MOD");
        equal(34, SDL_KeyboardEvent.RAW, "SDL_KeyboardEvent.RAW");
        equal(36, SDL_KeyboardEvent.DOWN, "SDL_KeyboardEvent.DOWN");
        equal(37, SDL_KeyboardEvent.REPEAT, "SDL_KeyboardEvent.REPEAT");
        equal(16, SDL_MouseMotionEvent.WINDOWID, "SDL_MouseMotionEvent.WINDOWID");
        equal(24, SDL_MouseMotionEvent.STATE, "SDL_MouseMotionEvent.STATE");
        equal(28, SDL_MouseMotionEvent.X, "SDL_MouseMotionEvent.X");
        equal(32, SDL_MouseMotionEvent.Y, "SDL_MouseMotionEvent.Y");
        equal(36, SDL_MouseMotionEvent.XREL, "SDL_MouseMotionEvent.XREL");
        equal(40, SDL_MouseMotionEvent.YREL, "SDL_MouseMotionEvent.YREL");
        equal(24, SDL_MouseButtonEvent.BUTTON, "SDL_MouseButtonEvent.BUTTON");
        equal(25, SDL_MouseButtonEvent.DOWN, "SDL_MouseButtonEvent.DOWN");
        equal(26, SDL_MouseButtonEvent.CLICKS, "SDL_MouseButtonEvent.CLICKS");
        equal(28, SDL_MouseButtonEvent.X, "SDL_MouseButtonEvent.X");
        equal(32, SDL_MouseButtonEvent.Y, "SDL_MouseButtonEvent.Y");
        equal(24, SDL_MouseWheelEvent.X, "SDL_MouseWheelEvent.X");
        equal(28, SDL_MouseWheelEvent.Y, "SDL_MouseWheelEvent.Y");
        equal(32, SDL_MouseWheelEvent.DIRECTION, "SDL_MouseWheelEvent.DIRECTION");
        equal(36, SDL_MouseWheelEvent.MOUSE_X, "SDL_MouseWheelEvent.MOUSE_X");
        equal(40, SDL_MouseWheelEvent.MOUSE_Y, "SDL_MouseWheelEvent.MOUSE_Y");
        equal(44, SDL_MouseWheelEvent.INTEGER_X, "SDL_MouseWheelEvent.INTEGER_X");
        equal(48, SDL_MouseWheelEvent.INTEGER_Y, "SDL_MouseWheelEvent.INTEGER_Y");
        equal(16, SDL_TextInputEvent.WINDOWID, "SDL_TextInputEvent.WINDOWID");
        equal(24, SDL_TextInputEvent.TEXT, "SDL_TextInputEvent.TEXT");
        equal(24, SDL_TextEditingEvent.TEXT, "SDL_TextEditingEvent.TEXT");
        equal(32, SDL_TextEditingEvent.START, "SDL_TextEditingEvent.START");
        equal(36, SDL_TextEditingEvent.LENGTH, "SDL_TextEditingEvent.LENGTH");
        equal(16, SDL_WindowEvent.WINDOWID, "SDL_WindowEvent.WINDOWID");
        equal(20, SDL_WindowEvent.DATA1, "SDL_WindowEvent.DATA1");
        equal(24, SDL_WindowEvent.DATA2, "SDL_WindowEvent.DATA2");
        equal(40, SDL_DisplayMode.SIZEOF, "SDL_DisplayMode.SIZEOF");
        equal(0, SDL_DisplayMode.DISPLAYID, "SDL_DisplayMode.DISPLAYID");
    }

    // ------------------------------------------------------------------ writes

    private static void keyboardEventFields() {
        SDL_Event event = written(
                Rec.of(BrowserSdlEvents.SDL_EVENT_KEY_DOWN, 44, 32, 1 | 128 | 8192, 1, 32, 0, 0, 0, null), 7);
        long address = event.address();
        equal(BrowserSdlEvents.SDL_EVENT_KEY_DOWN, event.type(), "type()");
        equal(1_234_500_000L, event.common().timestamp(), "timestamp() in nanoseconds");
        equal(1_234_500_000L, memGetLong(address + 8), "timestamp at offset 8");
        SDL_KeyboardEvent key = event.key();
        equal(7, key.windowID(), "key.windowID()");
        equal(44, key.scancode(), "key.scancode()");
        equal(32, key.key(), "key.key()");
        equal((short) (1 | 128 | 8192), key.mod(), "key.mod()");
        equal((short) 32, key.raw(), "key.raw()");
        check(key.down(), "key.down()");
        check(key.repeat(), "key.repeat()");
        equal(7, memGetInt(address + 16), "keyboard WINDOWID at 16");
        equal(44, memGetInt(address + 24), "SCANCODE at 24");
        equal(32, memGetInt(address + 28), "KEY at 28");
        equal((short) (1 | 128 | 8192), memGetShort(address + 32), "MOD (16 bit) at 32");
        equal(1, memGetByte(address + 36), "DOWN (8 bit) at 36");
        equal(1, memGetByte(address + 37), "REPEAT (8 bit) at 37");
        equal(0, memGetByte(address + 38), "no stray write after REPEAT");
        event.free();

        SDL_Event up = written(Rec.of(BrowserSdlEvents.SDL_EVENT_KEY_UP, 4, 97, 0, 0, 65, 0, 0, 0, null), 7);
        check(!up.key().down(), "KEY_UP is not down");
        check(!up.key().repeat(), "KEY_UP is not a repeat");
        equal(BrowserSdlEvents.SDL_EVENT_KEY_UP, up.type(), "KEY_UP type");
        up.free();
    }

    private static void textEventFields() {
        SDL_Event event = written(Rec.of(BrowserSdlEvents.SDL_EVENT_TEXT_INPUT, 0, 0, 0, 0, 0, 0, 0, 0,
                "é😀"), 3);
        equal(3, event.text().windowID(), "text.windowID()");
        equal("é😀", event.text().textString(), "text.textString() (UTF-8 round trip)");
        long pointer = memGetAddress(event.address() + 24);
        check(pointer != 0L, "TEXT pointer at 24");
        equal("é😀", MemoryUtil.memUTF8(pointer), "UTF-8 at the TEXT pointer");
        event.free();

        SDL_Event edit = written(Rec.of(BrowserSdlEvents.SDL_EVENT_TEXT_EDITING, 2, 3, 0, 0, 0, 0, 0, 0,
                "kana"), 3);
        equal("kana", edit.edit().textString(), "edit.textString()");
        equal(2, edit.edit().start(), "edit.start()");
        equal(3, edit.edit().length(), "edit.length()");
        edit.free();
    }

    private static void mouseEventFields() {
        SDL_Event motion = written(Rec.of(BrowserSdlEvents.SDL_EVENT_MOUSE_MOTION, 5, 0, 0, 0,
                12.5, 30.25, -3.0, 7.5, null), 9);
        SDL_MouseMotionEvent m = motion.motion();
        equal(9, m.windowID(), "motion.windowID()");
        equal(5, m.state(), "motion.state()");
        equal(12.5f, m.x(), "motion.x()");
        equal(30.25f, m.y(), "motion.y()");
        equal(-3.0f, m.xrel(), "motion.xrel()");
        equal(7.5f, m.yrel(), "motion.yrel()");
        equal(12.5f, memGetFloat(motion.address() + 28), "motion X at 28");
        equal(7.5f, memGetFloat(motion.address() + 40), "motion YREL at 40");
        motion.free();

        SDL_Event button = written(Rec.of(BrowserSdlEvents.SDL_EVENT_MOUSE_BUTTON_DOWN, 3, 2, 0, 0,
                100.0, 200.0, 0, 0, null), 9);
        SDL_MouseButtonEvent b = button.button();
        equal((byte) 3, b.button(), "button.button()");
        check(b.down(), "button.down()");
        equal((byte) 2, b.clicks(), "button.clicks()");
        equal(100.0f, b.x(), "button.x()");
        equal(200.0f, b.y(), "button.y()");
        equal(3, memGetByte(button.address() + 24), "BUTTON (8 bit) at 24");
        equal(0, memGetByte(button.address() + 27), "padding stays zero");
        button.free();

        SDL_Event wheel = written(Rec.of(BrowserSdlEvents.SDL_EVENT_MOUSE_WHEEL, 0, 0, 0, 0,
                0.5, -2.0, 40.0, 50.0, null), 9);
        SDL_MouseWheelEvent w = wheel.wheel();
        equal(0.5f, w.x(), "wheel.x()");
        equal(-2.0f, w.y(), "wheel.y()");
        equal(0, w.direction(), "wheel.direction() (SDL_MOUSEWHEEL_NORMAL)");
        equal(40.0f, w.mouse_x(), "wheel.mouse_x()");
        equal(50.0f, w.mouse_y(), "wheel.mouse_y()");
        equal(0, w.integer_x(), "wheel.integer_x()");
        equal(-2, w.integer_y(), "wheel.integer_y()");
        wheel.free();
    }

    private static void windowEventFields() {
        SDL_Event resized = written(Rec.of(518, 1280, 720, 0, 0, 0, 0, 0, 0, null), 2);
        equal(2, resized.window().windowID(), "window.windowID()");
        equal(1280, resized.window().data1(), "window.data1()");
        equal(720, resized.window().data2(), "window.data2()");
        equal(1280, memGetInt(resized.address() + 20), "DATA1 at 20");
        resized.free();
        SDL_Event quit = written(Rec.of(256, 9, 9, 0, 0, 0, 0, 0, 0, null), 2);
        equal(256, quit.type(), "QUIT type");
        equal(0, memGetInt(quit.address() + 16), "non-window events carry no window fields");
        quit.free();
    }

    // ------------------------------------------------------------------ poll, flush, keyboard state

    private static void pollFlushAndKeyboardState() {
        FakeSource source = new FakeSource();
        BrowserSdlEvents.useSource(source);
        ByteBuffer keys = SDLKeyboard.SDL_GetKeyboardState();
        check(keys == BrowserSdlKeyboard.state(), "SDL_GetKeyboardState is the persistent shim buffer");
        equal(512, keys.limit(), "keyboard state covers SDL_SCANCODE_COUNT");
        check(keys == SDLKeyboard.SDL_GetKeyboardState(), "the same buffer on every call");

        source.queue.add(Rec.of(BrowserSdlEvents.SDL_EVENT_KEY_DOWN, 26, 119, 0, 0, 87, 0, 0, 0, null));
        source.queue.add(Rec.of(BrowserSdlEvents.SDL_EVENT_TEXT_INPUT, 0, 0, 0, 0, 0, 0, 0, 0, "w"));
        source.queue.add(Rec.of(BrowserSdlEvents.SDL_EVENT_MOUSE_BUTTON_DOWN, 1, 1, 0, 0, 5, 5, 0, 0, null));
        source.queue.add(Rec.of(BrowserSdlEvents.SDL_EVENT_KEY_DOWN, 4, 97, 0, 0, 65, 0, 0, 0, null));
        source.queue.add(Rec.of(BrowserSdlEvents.SDL_EVENT_KEY_UP, 26, 119, 0, 0, 87, 0, 0, 0, null));
        source.queue.add(Rec.of(518, 800, 600, 0, 0, 0, 0, 0, 0, null));
        source.queue.add(Rec.of(BrowserSdlEvents.SDL_EVENT_KEY_DOWN, 22, 115, 0, 0, 83, 0, 0, 0, null));

        List<Integer> outer = new ArrayList<>();
        List<Integer> inner = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Event event = SDL_Event.malloc(stack);
            check(SDLEvents.SDL_PollEvent(null), "SDL_PollEvent(NULL) reports queued events");
            equal(7, source.queue.size(), "SDL_PollEvent(NULL) does not dequeue");
            while (SDLEvents.SDL_PollEvent(event)) {
                outer.add(event.type());
                if (event.type() == BrowserSdlEvents.SDL_EVENT_KEY_DOWN && event.key().scancode() == 26) {
                    equal(1, keys.get(26), "KEY_DOWN is visible in the keyboard state when dequeued");
                }
                if (event.type() == BrowserSdlEvents.SDL_EVENT_TEXT_INPUT) {
                    long pointer = memGetAddress(event.address() + SDL_TextInputEvent.TEXT);
                    equal("w", event.text().textString(), "text before the next poll");
                    check(pointer != 0L, "text pointer");
                }
                if (event.type() == BrowserSdlEvents.SDL_EVENT_MOUSE_BUTTON_DOWN) {
                    // Minecraft.disconnect from a click handler: flushInputEvents + nested pollEvents.
                    SDLEvents.SDL_PumpEvents();
                    SDLEvents.SDL_FlushEvents(768, 4871);
                    SDL_Event nested = SDL_Event.malloc(stack);
                    while (SDLEvents.SDL_PollEvent(nested)) {
                        inner.add(nested.type());
                    }
                }
            }
        }
        equal(List.of(768, 771, 1025), outer, "outer loop events");
        equal(List.of(518), inner, "nested loop keeps the window event");
        equal(0, keys.get(26), "W pressed then released inside the flushed range");
        equal(1, keys.get(4), "A pressed inside the flushed range stays pressed");
        equal(1, keys.get(22), "S pressed inside the flushed range stays pressed");
        check(!SDLEvents.SDL_PollEvent(SDL_Event.calloc()), "queue drained");

        BrowserSdlEvents.useSource(null);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Event event = SDL_Event.calloc(stack);
            MemoryUtil.memPutInt(event.address() + SDL_WindowEvent.WINDOWID, 1);
            equal(0L, SDLEvents.SDL_GetWindowFromEvent(event), "an unknown window id maps to 0");
            equal(0L, SDLEvents.SDL_GetWindowFromEvent(null), "a null event maps to 0");
        }
    }

    // ------------------------------------------------------------------ pure-Java entry points

    private static void redirectedEntryPoints() {
        check(SDL.getLibrary() == null, "SDL.<clinit> no longer loads a native library");
        equal("A", SDLKeyboard.SDL_GetKeyName(97), "key name of 'a'");
        equal("1", SDLKeyboard.SDL_GetKeyName(49), "key name of '1'");
        equal("Space", SDLKeyboard.SDL_GetKeyName(32), "space has an SDL name, not ' '");
        equal("Return", SDLKeyboard.SDL_GetKeyName(13), "return");
        equal("", SDLKeyboard.SDL_GetKeyName(0x40000059), "keypad keys use Minecraft's translations");
        equal("", SDLKeyboard.SDL_GetKeyName(0), "SDLK_UNKNOWN");
        equal(97, BrowserSdlKeyboard.usKeycode(4), "US keycode of scancode 4");
        equal(0x40000000 | 58, BrowserSdlKeyboard.usKeycode(58), "F1 keycode");
        equal(97, SDLKeyboard.SDL_GetKeyFromScancode(4, (short) 0, false),
                "GetKeyFromScancode falls back to the US table before SDL_Init");
        equal("", SDLError.SDL_GetError(), "SDL_GetError is never null");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var value = stack.mallocInt(1);
            check(SDLVideo.SDL_GL_GetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, value), "GetAttribute");
            equal(3, value.get(0), "GL major version");
            check(SDLVideo.SDL_GL_GetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, value), "GetAttribute");
            equal(3, value.get(0), "GL minor version");
            check(SDLVideo.SDL_GL_GetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, value), "GetAttribute");
            equal(SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE, value.get(0), "core profile");
            check(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_FLAGS, 2), "SetAttribute");
            check(SDLVideo.SDL_GL_GetAttribute(SDLVideo.SDL_GL_CONTEXT_FLAGS, value), "GetAttribute");
            equal(2, value.get(0), "stored context flags");
            check(SDLVideo.SDL_GL_GetAttribute(SDLVideo.SDL_GL_STENCIL_SIZE, value), "GetAttribute");
            equal(8, value.get(0), "stencil bits");
        }
        check(SDLVideo.SDL_GL_SetSwapInterval(1), "SetSwapInterval");
        equal(1, BrowserSdlVideo.swapInterval(), "swap interval stored for SDL_GL_SwapWindow");
        check(SDLVideo.SDL_GL_LoadLibrary((CharSequence) null), "GL_LoadLibrary");
        check(SDLVideo.SDL_GL_MakeCurrent(1L, 1L), "GL_MakeCurrent");

        long hash = 0xcbf29ce484222325L;
        for (byte b : "glGetError".getBytes(StandardCharsets.US_ASCII)) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        equal(hash, SDLVideo.SDL_GL_GetProcAddress("glGetError"),
                "GetProcAddress matches BrowserSharedLibrary.getFunctionAddress");

        var displays = SDLVideo.SDL_GetDisplays();
        equal(1, displays.remaining(), "one display");
        equal(1, displays.get(0), "display id 1");
        SDLStdinc.SDL_free(displays);
        equal(1, SDLVideo.SDL_GetDisplays().get(0), "SDL_free leaves shim memory alone");
        equal(1, SDLVideo.SDL_GetPrimaryDisplay(), "primary display");
        equal(1, SDLVideo.SDL_GetDisplayForWindow(1L), "display for window");
        check(SDLVideo.SDL_GetWindowFullscreenMode(1L) == null, "never exclusive fullscreen");
        check(SDLPixels.SDL_GetPixelFormatDetails(SDLPixels.SDL_PIXELFORMAT_XRGB8888) == null,
                "null pixel format details (VideoMode uses 8/8/8)");
        check(SDLSurface.SDL_CreateSurfaceFrom(16, 16, SDLPixels.SDL_PIXELFORMAT_ABGR8888,
                MemoryUtil.memAlloc(16 * 16 * 4), 64) == null, "no icon surfaces");
        check(!SDLVulkan.SDL_Vulkan_LoadLibrary((CharSequence) null), "no Vulkan");
        equal(0L, SDLVulkan.SDL_Vulkan_GetVkGetInstanceProcAddr(), "no vkGetInstanceProcAddr");
        check(SDLVulkan.SDL_Vulkan_GetInstanceExtensions() == null, "no Vulkan instance extensions");
        check(SDLHints.SDL_SetHint("SDL_TEST", "1"), "SDL_SetHint");
        check(SDLInit.SDL_SetAppMetadataProperty("SDL.app.metadata.name", "Minecraft"), "metadata");
        check(SDLVideo.SDL_SyncWindow(1L), "SDL_SyncWindow");

        SDLStdinc.SDL_free((PointerBuffer) null);
    }

    // ------------------------------------------------------------------ loop boundary

    private static void loopBoundaryPerDrainCycle() {
        FakeSource source = new FakeSource();
        BrowserSdlEvents.useSource(source);
        source.queue.add(Rec.of(BrowserSdlEvents.SDL_EVENT_KEY_DOWN, 4, 97, 0, 0, 65, 0, 0, 0, null));
        source.queue.add(Rec.of(BrowserSdlEvents.SDL_EVENT_KEY_UP, 4, 97, 0, 0, 65, 0, 0, 0, null));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Event event = SDL_Event.malloc(stack);
            int polled = 0;
            while (SDLEvents.SDL_PollEvent(event)) {
                polled++;
            }
            equal(2, polled, "both events of the first cycle");
            equal(1, source.loopBoundaries, "one boundary for a cycle of two events and the empty answer");
            check(!SDLEvents.SDL_PollEvent(null), "SDL_PollEvent(NULL) on an empty queue");
            equal(1, source.loopBoundaries, "SDL_PollEvent(NULL) is no boundary");
            check(!SDLEvents.SDL_PollEvent(event), "an empty cycle");
            equal(2, source.loopBoundaries, "an empty cycle is one boundary");
            source.queue.add(Rec.of(BrowserSdlEvents.SDL_EVENT_TEXT_INPUT, 0, 0, 0, 0, 0, 0, 0, 0, "a"));
            check(SDLEvents.SDL_PollEvent(event), "the next cycle's event");
            check(!SDLEvents.SDL_PollEvent(event), "the next cycle drained");
            equal(3, source.loopBoundaries, "one boundary per cycle");
        }
        BrowserSdlEvents.useSource(null);
    }

    // ------------------------------------------------------------------ present yield

    private static void nonThreadingPresentYield() {
        // The message TeaVM's runtime throws, wrapped the way a JavaScript Error reaches Java.
        check(BrowserSdlDom.isNonThreadingSuspension(new RuntimeException(
                "(JavaScript) Error: Suspension point reached from non-threading context (perhaps, from"
                        + " native JS method). See https://teavm.org/docs/runtime/coroutines.html"
                        + " ('Interaction with JavaScript' section)")),
                "a frame presented from a JavaScript callback skips its yield");
        check(!BrowserSdlDom.isNonThreadingSuspension(new RuntimeException("Invalid recorded state")),
                "other coroutine failures still propagate");
        check(!BrowserSdlDom.isNonThreadingSuspension(new IllegalStateException()),
                "a failure without a message still propagates");
    }
}
