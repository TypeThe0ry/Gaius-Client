package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutByte;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.system.MemoryUtil.memPutShort;
import static org.lwjgl.system.MemoryUtil.memSet;
import static org.lwjgl.system.MemoryUtil.memUTF8;

import java.nio.ByteBuffer;

/**
 * SDL_PollEvent / SDL_FlushEvents on top of the JavaScript event engine of
 * {@link BrowserSdlDom}.
 *
 * <p>Every call of {@link #poll} takes one record off the shared queue (the engine
 * applies its keyboard, modifier and mouse state at that moment) and writes it into
 * the caller's {@code SDL_Event} with the offsets of the LWJGL struct classes, so
 * the layout follows {@code Pointer.POINTER_SIZE}. Minecraft 26.3 re-enters the pump
 * from input handlers (disconnect, doWorldLoad and {@code Minecraft.<init>} call
 * {@code SDLEventHandler.pumpEvents}); a nested poll only moves the shared queue
 * head, so no record is delivered twice and none is lost.
 *
 * <p>Text payloads stay valid until the next {@code SDL_PollEvent} call, as SDL
 * specifies. The keyboard state array of {@code SDL_GetKeyboardState} mirrors the
 * engine: it changes when a key record is dequeued or flushed.
 */
final class BrowserSdlEvents {
    // Compile-time constants of SDLEvents (inlined by javac; no class initialization).
    static final int SDL_EVENT_KEY_DOWN = SDLEvents.SDL_EVENT_KEY_DOWN;
    static final int SDL_EVENT_KEY_UP = SDLEvents.SDL_EVENT_KEY_UP;
    static final int SDL_EVENT_TEXT_EDITING = SDLEvents.SDL_EVENT_TEXT_EDITING;
    static final int SDL_EVENT_TEXT_INPUT = SDLEvents.SDL_EVENT_TEXT_INPUT;
    static final int SDL_EVENT_MOUSE_MOTION = SDLEvents.SDL_EVENT_MOUSE_MOTION;
    static final int SDL_EVENT_MOUSE_BUTTON_DOWN = SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN;
    static final int SDL_EVENT_MOUSE_BUTTON_UP = SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP;
    static final int SDL_EVENT_MOUSE_WHEEL = SDLEvents.SDL_EVENT_MOUSE_WHEEL;
    static final int SDL_EVENT_WINDOW_FIRST = SDLEvents.SDL_EVENT_WINDOW_FIRST;
    static final int SDL_EVENT_WINDOW_LAST = SDLEvents.SDL_EVENT_WINDOW_LAST;

    /** The event records, normally the JavaScript engine; tests install a fake. */
    interface Source {
        /** Dequeues the next record and returns its SDL type, or 0 when the queue is empty. */
        int poll();

        /** Whether a record is queued, without dequeuing it. */
        boolean peek();

        int recordInt(int index);

        double recordDouble(int index);

        String recordText();

        double recordTimestampMillis();

        /** Drops records with a type in [min, max]; returns the number of key records applied. */
        int flush(int min, int max);

        /** Dequeue-time keyboard state of a scancode (0 or 1). */
        int keyState(int scancode);

        /**
         * Called before the first poll of each drain cycle (SDLEventHandler.pollEvents polls until
         * the queue is empty once per Minecraft.run iteration): the game-loop boundary.
         */
        default void loopBoundary() {
        }
    }

    private static final class DomSource implements Source {
        @Override
        public int poll() {
            return BrowserSdlDom.poll();
        }

        @Override
        public boolean peek() {
            return BrowserSdlDom.peek();
        }

        @Override
        public int recordInt(int index) {
            return BrowserSdlDom.recordInt(index);
        }

        @Override
        public double recordDouble(int index) {
            return BrowserSdlDom.recordDouble(index);
        }

        @Override
        public String recordText() {
            return BrowserSdlDom.recordText();
        }

        @Override
        public double recordTimestampMillis() {
            return BrowserSdlDom.recordTimestamp();
        }

        @Override
        public int flush(int min, int max) {
            return BrowserSdlDom.flush(min, max);
        }

        @Override
        public int keyState(int scancode) {
            return BrowserSdlDom.keyState(scancode);
        }

        @Override
        public void loopBoundary() {
            BrowserSdlDom.loopBoundary();
        }
    }

    private static Source source = new DomSource();
    private static ByteBuffer text;
    private static long polledEvents;
    /** True when the next poll starts a drain cycle (the previous one answered "empty"). */
    private static boolean drainCycleStart = true;

    private BrowserSdlEvents() {
    }

    /** Test hook: replaces the JavaScript engine. */
    static void useSource(Source replacement) {
        source = replacement == null ? new DomSource() : replacement;
        releaseText();
        drainCycleStart = true;
    }

    static long polledEvents() {
        return polledEvents;
    }

    static boolean poll(SDL_Event event) {
        if (event == null) {
            return source.peek();
        }
        if (drainCycleStart) {
            drainCycleStart = false;
            source.loopBoundary();
        }
        releaseText();
        int type = source.poll();
        if (type == 0) {
            drainCycleStart = true;
            return false;
        }
        write(event.address(), type, source, BrowserSdlVideo.eventWindowId());
        polledEvents++;
        return true;
    }

    static void flush(int minType, int maxType) {
        if (source.flush(minType, maxType) > 0) {
            BrowserSdlKeyboard.resync(source);
        }
    }

    static long windowFromEvent(SDL_Event event) {
        if (event == null) {
            return 0L;
        }
        return BrowserSdlVideo.windowFromId(memGetInt(event.address() + SDL_WindowEvent.WINDOWID));
    }

    private static void releaseText() {
        if (text != null) {
            ByteBuffer previous = text;
            text = null;
            memFree(previous);
        }
    }

    /** Writes the current record of {@code records} into the SDL_Event at {@code address}. */
    static void write(long address, int type, Source records, int windowId) {
        memSet(address, 0, SDL_Event.SIZEOF);
        memPutInt(address + SDL_CommonEvent.TYPE, type);
        memPutLong(address + SDL_CommonEvent.TIMESTAMP, nanos(records.recordTimestampMillis()));
        switch (type) {
            case SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP -> {
                int scancode = records.recordInt(BrowserSdlDom.REC_I0);
                memPutInt(address + SDL_KeyboardEvent.WINDOWID, windowId);
                memPutInt(address + SDL_KeyboardEvent.SCANCODE, scancode);
                memPutInt(address + SDL_KeyboardEvent.KEY, records.recordInt(BrowserSdlDom.REC_I1));
                memPutShort(address + SDL_KeyboardEvent.MOD, (short) records.recordInt(BrowserSdlDom.REC_I2));
                memPutShort(address + SDL_KeyboardEvent.RAW, (short) records.recordDouble(BrowserSdlDom.REC_F0));
                memPutByte(address + SDL_KeyboardEvent.DOWN, (byte) (type == SDL_EVENT_KEY_DOWN ? 1 : 0));
                memPutByte(address + SDL_KeyboardEvent.REPEAT,
                        (byte) (records.recordInt(BrowserSdlDom.REC_I3) != 0 ? 1 : 0));
                BrowserSdlKeyboard.setKeyState(scancode, type == SDL_EVENT_KEY_DOWN);
            }
            case SDL_EVENT_TEXT_INPUT -> {
                memPutInt(address + SDL_TextInputEvent.WINDOWID, windowId);
                memPutAddress(address + SDL_TextInputEvent.TEXT, textAddress(records.recordText()));
            }
            case SDL_EVENT_TEXT_EDITING -> {
                memPutInt(address + SDL_TextEditingEvent.WINDOWID, windowId);
                memPutAddress(address + SDL_TextEditingEvent.TEXT, textAddress(records.recordText()));
                memPutInt(address + SDL_TextEditingEvent.START, records.recordInt(BrowserSdlDom.REC_I0));
                memPutInt(address + SDL_TextEditingEvent.LENGTH, records.recordInt(BrowserSdlDom.REC_I1));
            }
            case SDL_EVENT_MOUSE_MOTION -> {
                memPutInt(address + SDL_MouseMotionEvent.WINDOWID, windowId);
                memPutInt(address + SDL_MouseMotionEvent.STATE, records.recordInt(BrowserSdlDom.REC_I0));
                memPutFloat(address + SDL_MouseMotionEvent.X, (float) records.recordDouble(BrowserSdlDom.REC_F0));
                memPutFloat(address + SDL_MouseMotionEvent.Y, (float) records.recordDouble(BrowserSdlDom.REC_F1));
                memPutFloat(address + SDL_MouseMotionEvent.XREL, (float) records.recordDouble(BrowserSdlDom.REC_F2));
                memPutFloat(address + SDL_MouseMotionEvent.YREL, (float) records.recordDouble(BrowserSdlDom.REC_F3));
            }
            case SDL_EVENT_MOUSE_BUTTON_DOWN, SDL_EVENT_MOUSE_BUTTON_UP -> {
                memPutInt(address + SDL_MouseButtonEvent.WINDOWID, windowId);
                memPutByte(address + SDL_MouseButtonEvent.BUTTON, (byte) records.recordInt(BrowserSdlDom.REC_I0));
                memPutByte(address + SDL_MouseButtonEvent.DOWN,
                        (byte) (type == SDL_EVENT_MOUSE_BUTTON_DOWN ? 1 : 0));
                memPutByte(address + SDL_MouseButtonEvent.CLICKS, (byte) records.recordInt(BrowserSdlDom.REC_I1));
                memPutFloat(address + SDL_MouseButtonEvent.X, (float) records.recordDouble(BrowserSdlDom.REC_F0));
                memPutFloat(address + SDL_MouseButtonEvent.Y, (float) records.recordDouble(BrowserSdlDom.REC_F1));
            }
            case SDL_EVENT_MOUSE_WHEEL -> {
                float x = (float) records.recordDouble(BrowserSdlDom.REC_F0);
                float y = (float) records.recordDouble(BrowserSdlDom.REC_F1);
                memPutInt(address + SDL_MouseWheelEvent.WINDOWID, windowId);
                memPutFloat(address + SDL_MouseWheelEvent.X, x);
                memPutFloat(address + SDL_MouseWheelEvent.Y, y);
                memPutInt(address + SDL_MouseWheelEvent.DIRECTION, records.recordInt(BrowserSdlDom.REC_I0));
                memPutFloat(address + SDL_MouseWheelEvent.MOUSE_X, (float) records.recordDouble(BrowserSdlDom.REC_F2));
                memPutFloat(address + SDL_MouseWheelEvent.MOUSE_Y, (float) records.recordDouble(BrowserSdlDom.REC_F3));
                memPutInt(address + SDL_MouseWheelEvent.INTEGER_X, (int) x);
                memPutInt(address + SDL_MouseWheelEvent.INTEGER_Y, (int) y);
            }
            default -> {
                if (type >= SDL_EVENT_WINDOW_FIRST && type <= SDL_EVENT_WINDOW_LAST) {
                    memPutInt(address + SDL_WindowEvent.WINDOWID, windowId);
                    memPutInt(address + SDL_WindowEvent.DATA1, records.recordInt(BrowserSdlDom.REC_I0));
                    memPutInt(address + SDL_WindowEvent.DATA2, records.recordInt(BrowserSdlDom.REC_I1));
                }
            }
        }
    }

    private static long textAddress(String value) {
        releaseText();
        text = memUTF8(value == null ? "" : value, true);
        return memAddress(text);
    }

    private static long nanos(double millis) {
        if (!(millis > 0.0)) {
            return 0L;
        }
        return (long) (millis * 1_000_000.0);
    }
}
