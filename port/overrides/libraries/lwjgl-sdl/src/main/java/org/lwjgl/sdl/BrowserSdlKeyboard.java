package org.lwjgl.sdl;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Keyboard side of the browser SDL3 shim: the persistent state array returned by
 * {@code SDL_GetKeyboardState}, keycode lookup and key names.
 *
 * <p>The DOM {@code KeyboardEvent.code} &rarr; SDL scancode table and the keycode
 * rules (unshifted, Latin letters, {@code scancode | SDLK_SCANCODE_MASK} for keys
 * without a character) live in the JavaScript engine of {@link BrowserSdlDom};
 * {@link #usKeycode} is the same US table for callers that run before the DOM bridge
 * exists.
 */
final class BrowserSdlKeyboard {
    /** SDL_SCANCODE_COUNT. */
    static final int SCANCODE_COUNT = 512;
    static final int SDLK_SCANCODE_MASK = 1 << 30;

    private static ByteBuffer state;

    private BrowserSdlKeyboard() {
    }

    /** SDL_GetKeyboardState: one persistent array indexed by scancode, limit 512. */
    static ByteBuffer state() {
        if (state == null) {
            state = MemoryUtil.memCalloc(SCANCODE_COUNT);
        }
        return state;
    }

    static void setKeyState(int scancode, boolean down) {
        if (scancode > 0 && scancode < SCANCODE_COUNT) {
            state().put(scancode, (byte) (down ? 1 : 0));
        }
    }

    /** Copies the engine's keyboard state after a flush applied key records. */
    static void resync(BrowserSdlEvents.Source source) {
        ByteBuffer keys = state();
        for (int scancode = 0; scancode < SCANCODE_COUNT; scancode++) {
            keys.put(scancode, (byte) (source.keyState(scancode) != 0 ? 1 : 0));
        }
    }

    /** Unshifted US keycode of a scancode (the JavaScript table without the layout map). */
    static int usKeycode(int scancode) {
        if (scancode <= 0 || scancode >= SCANCODE_COUNT) {
            return 0;
        }
        if (scancode >= 4 && scancode <= 29) {
            return 'a' + (scancode - 4);
        }
        if (scancode >= 30 && scancode <= 38) {
            return '1' + (scancode - 30);
        }
        return switch (scancode) {
            case 39 -> '0';
            case 40 -> 13;
            case 41 -> 27;
            case 42 -> 8;
            case 43 -> 9;
            case 44 -> ' ';
            case 45 -> '-';
            case 46 -> '=';
            case 47 -> '[';
            case 48 -> ']';
            case 49 -> '\\';
            case 50 -> '#';
            case 51 -> ';';
            case 52 -> '\'';
            case 53 -> '`';
            case 54 -> ',';
            case 55 -> '.';
            case 56 -> '/';
            case 76 -> 127;
            default -> scancode | SDLK_SCANCODE_MASK;
        };
    }

    /**
     * SDL_GetKeyName: the upper-case character for keycodes that type one, the SDL
     * name for the whitespace and control keycodes, "" otherwise (Minecraft then
     * falls back to its {@code key.keyboard.*} translation).
     */
    static String keyName(int keycode) {
        switch (keycode) {
            case 0:
                return "";
            case 8:
                return "Backspace";
            case 9:
                return "Tab";
            case 13:
                return "Return";
            case 27:
                return "Escape";
            case 32:
                return "Space";
            case 127:
                return "Delete";
            default:
                break;
        }
        if ((keycode & SDLK_SCANCODE_MASK) != 0 || keycode < 33 || !Character.isValidCodePoint(keycode)) {
            return "";
        }
        return new String(Character.toChars(keycode)).toUpperCase(java.util.Locale.ROOT);
    }
}
