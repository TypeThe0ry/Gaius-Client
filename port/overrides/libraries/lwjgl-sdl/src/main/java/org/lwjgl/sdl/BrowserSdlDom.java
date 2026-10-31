package org.lwjgl.sdl;

import dev.gaius.browser.HostLoop;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * JavaScript half of the browser SDL3 shim (work package P2, decision D3).
 *
 * <p>The DOM listeners, canvas and WebGL 2 context handling, pointer and keyboard
 * lock, canvas resolution, input warm-up, input statistics and the cooperative
 * frame-yield scheduler are copied from {@code org.lwjgl.glfw.BrowserGlfw} (kept apart so
 * that the GLFW profiles change only where both shims must, such as the canvas host). The JS
 * global names ({@code __gaiusWebGL}, {@code __gaiusInputStats},
 * {@code __gaiusCursorX/Y}, {@code __gaiusWantPointerLock},
 * {@code __gaiusApplyCanvasResolution}, {@code __gaiusResolvePixelRatio},
 * {@code __gaiusDisplay}, {@code __gaiusFrameTelemetry}, ...) are the same; the
 * event queue is new and is called {@code __gaiusSdlEvents}.
 *
 * <p>The game canvas is the one {@code window.__gaiusCanvasHost} names (shared with the GLFW
 * shim and the WebGPU glue, installed by {@code installDomBridge}): every script reads it through
 * {@code current()}, so a canvas that {@code replace()} swapped after an abandoned WebGPU attempt
 * (a canvas keeps its first context kind) is followed and re-hooked.
 *
 * <p>The event engine lives entirely in JavaScript ({@code window.__gaiusSdl}):
 * DOM listeners push neutral records
 * {@code [sdlType, i0, i1, i2, i3, f0, f1, f2, f3, text, timestampMillis]}, and
 * {@code poll()} / {@code flush(min, max)} apply the keyboard, modifier and mouse
 * state when a record leaves the queue. {@link BrowserSdlEvents} copies the current
 * record into an {@code SDL_Event}. Record fields by type:
 * <pre>
 * 768/769 KEY_DOWN/UP   i0 scancode, i1 keycode, i2 mod, i3 repeat, f0 raw key code
 * 770 TEXT_EDITING      i0 start, i1 length, text
 * 771 TEXT_INPUT        text
 * 1024 MOUSE_MOTION     i0 button state, i2 mod, f0 x, f1 y, f2 xrel, f3 yrel
 * 1025/1026 BUTTON      i0 SDL button (1..5), i1 clicks, i2 mod, f0 x, f1 y
 * 1027 MOUSE_WHEEL      i0 direction, i2 mod, f0 x, f1 y, f2 mouse x, f3 mouse y
 * 0x200..0x2FF WINDOW   i0 data1, i1 data2
 * </pre>
 */
final class BrowserSdlDom {
    static final int REC_TYPE = 0;
    static final int REC_I0 = 1;
    static final int REC_I1 = 2;
    static final int REC_I2 = 3;
    static final int REC_I3 = 4;
    static final int REC_F0 = 5;
    static final int REC_F1 = 6;
    static final int REC_F2 = 7;
    static final int REC_F3 = 8;

    private BrowserSdlDom() {
    }

    // ---------------------------------------------------------------- DOM bridge

    @JSBody(script = """
            const w = window;
            // The game canvas registry: the same script as BrowserGlfw.installCanvasHost,
            // installed by whichever shim runs first.
            if (!(w.__gaiusCanvasHost && w.__gaiusCanvasHost.version >= 1)) {
              const host = {
                version: 1,
                id: 'mc-canvas',
                replacements: 0,
                lastReason: '',
                hooks: []
              };
              host.current = function () {
                return document.getElementById(host.id);
              };
              host.ensure = function () {
                let canvas = host.current();
                if (!canvas) {
                  canvas = document.createElement('canvas');
                  canvas.id = host.id;
                  canvas.tabIndex = 0;
                  document.body.appendChild(canvas);
                }
                return canvas;
              };
              host.replace = function (reason) {
                const previous = host.current();
                const canvas = document.createElement('canvas');
                if (previous) {
                  const attributes = previous.attributes;
                  for (let index = 0; index < attributes.length; index++) {
                    canvas.setAttribute(attributes[index].name, attributes[index].value);
                  }
                  canvas.width = previous.width;
                  canvas.height = previous.height;
                  const focused = document.activeElement === previous;
                  previous.replaceWith(canvas);
                  if (focused && canvas.focus) canvas.focus();
                } else {
                  canvas.id = host.id;
                  canvas.tabIndex = 0;
                  document.body.appendChild(canvas);
                }
                host.replacements++;
                host.lastReason = String(reason || '');
                w.__gaiusCanvasRect = null;
                for (let index = 0; index < host.hooks.length; index++) {
                  try {
                    host.hooks[index](canvas, previous, host.lastReason);
                  } catch (ignored) {
                    // A failing hook must not keep the canvas from being replaced.
                  }
                }
                try {
                  w.dispatchEvent(new CustomEvent('gaius:canvas-replaced',
                    {detail: {canvas: canvas, previous: previous, reason: host.lastReason}}));
                } catch (ignored) {
                  // Listeners are optional.
                }
                return canvas;
              };
              host.onReplace = function (hook) {
                if (typeof hook === 'function') host.hooks.push(hook);
              };
              w.__gaiusCanvasHost = host;
            }
            if (w.__gaiusSdl && w.__gaiusSdl.installed) return;
            const clock = () => (typeof performance !== 'undefined' && performance.now)
              ? performance.now() : Date.now();
            const events = Array.isArray(w.__gaiusSdlEvents) ? w.__gaiusSdlEvents : [];
            events.length = 0;
            w.__gaiusSdlEvents = events;
            w.__gaiusSdlEventHead = 0;
            w.__gaiusSdlKeys = Object.create(null);
            const stats = w.__gaiusInputStats || (w.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            stats.backend = 'sdl';
            const sdl = {
              installed: true,
              events: events,
              head: 0,
              current: null,
              keys: new Uint8Array(512),
              buttons: 0,
              mods: 0,
              cursorX: 0,
              cursorY: 0,
              focused: true,
              domKeys: Object.create(null),
              domButtons: 0,
              domMods: 0,
              lastX: NaN,
              lastY: NaN,
              textInput: false,
              textInputArea: null,
              relative: false,
              fullscreen: false,
              layoutMap: null,
              coalescedMotion: 0,
              flushed: 0,
              lastFlushRemoved: 0
            };
            w.__gaiusSdl = sdl;
            w.__gaiusWantPointerLock = false;
            w.__gaiusPointerLockErrors = [];
            w.__gaiusMaxDpr = Number.isFinite(Number(w.__gaiusMaxDpr))
              ? Number(w.__gaiusMaxDpr)
              : 1.0;

            // ---- records and the queue engine
            const record = (type, i0, i1, i2, i3, f0, f1, f2, f3, text) => [
              type | 0, i0 | 0, i1 | 0, i2 | 0, i3 | 0,
              +f0 || 0, +f1 || 0, +f2 || 0, +f3 || 0,
              text == null ? null : String(text), clock()
            ];
            const push = r => {
              events.push(r);
              return r;
            };
            const pushWindow = (type, data1, data2) => push(record(type, data1, data2, 0, 0, 0, 0, 0, 0, null));
            const compact = () => {
              const head = sdl.head;
              if (head >= events.length) {
                events.length = 0;
                sdl.head = 0;
              } else if (head > 128 && head * 2 > events.length) {
                events.splice(0, head);
                sdl.head = 0;
              }
              w.__gaiusSdlEventHead = sdl.head;
            };
            const apply = r => {
              switch (r[0]) {
                case 768: if (r[1] > 0 && r[1] < 512) sdl.keys[r[1]] = 1; sdl.mods = r[3]; break;
                case 769: if (r[1] > 0 && r[1] < 512) sdl.keys[r[1]] = 0; sdl.mods = r[3]; break;
                case 1024: sdl.buttons = r[1]; sdl.mods = r[3]; sdl.cursorX = r[5]; sdl.cursorY = r[6]; break;
                case 1025:
                  sdl.buttons |= 1 << ((r[1] - 1) & 31);
                  sdl.mods = r[3]; sdl.cursorX = r[5]; sdl.cursorY = r[6];
                  break;
                case 1026:
                  sdl.buttons &= ~(1 << ((r[1] - 1) & 31));
                  sdl.mods = r[3]; sdl.cursorX = r[5]; sdl.cursorY = r[6];
                  break;
                case 1027: sdl.mods = r[3]; break;
                case 526: sdl.focused = true; break;
                case 527: sdl.focused = false; break;
                default: break;
              }
            };
            const warmup = () => {
              if (w.__gaiusInputWarmupDone) return;
              const minecraftState = w.__gaiusMinecraftState || null;
              if (!minecraftState || !minecraftState.screen || minecraftState.level) return;
              w.__gaiusInputWarmupDone = true;
              w.__gaiusCursorX = 1;
              w.__gaiusCursorY = 1;
              push(record(1024, sdl.domButtons, 0, sdl.domMods, 0, 1, 1, 0, 0, null));
              push(record(1025, 1, 1, sdl.domMods, 0, 1, 1, 0, 0, null));
              push(record(1026, 1, 1, sdl.domMods, 0, 1, 1, 0, 0, null));
            };
            // Returns the SDL type of the record that left the queue, 0 when empty.
            // Re-entrant: a handler may flush or poll again before the outer poll
            // returns; each call only moves the shared head forward.
            sdl.poll = () => {
              if (sdl.head >= events.length) warmup();
              if (sdl.head >= events.length) {
                compact();
                sdl.current = null;
                return 0;
              }
              const r = events[sdl.head];
              events[sdl.head] = null;
              sdl.head++;
              apply(r);
              sdl.current = r;
              const key = String(r[0]);
              stats.totalEvents = (stats.totalEvents || 0) + 1;
              stats.events[key] = (stats.events[key] || 0) + 1;
              stats.lastEvent = {
                type: r[0],
                a: r[1],
                b: r[2],
                c: r[3],
                x: r[5],
                y: r[6],
                callbackInstalled: true,
                at: Date.now()
              };
              compact();
              return r[0];
            };
            sdl.peek = () => sdl.head < events.length;
            // SDL_FlushEvents: drop queued records whose type is in [min, max]
            // after applying their state; keep every other record in order.
            // Returns the number of keyboard records whose state was applied.
            sdl.flush = (min, max) => {
              let keyChanges = 0;
              let removed = 0;
              let write = sdl.head;
              for (let read = sdl.head; read < events.length; read++) {
                const r = events[read];
                if (r[0] >= min && r[0] <= max) {
                  apply(r);
                  removed++;
                  if (r[0] === 768 || r[0] === 769) keyChanges++;
                } else {
                  events[write++] = r;
                }
              }
              events.length = write;
              sdl.lastFlushRemoved = removed;
              sdl.flushed += removed;
              stats.flushedEvents = (stats.flushedEvents || 0) + removed;
              compact();
              return keyChanges;
            };
            // Mouse motion records merge while they are the newest queued record:
            // the position and state follow the latest DOM event, xrel/yrel add up.
            const pushMotion = (x, y, xrel, yrel) => {
              sdl.lastX = x;
              sdl.lastY = y;
              const n = events.length;
              if (n > sdl.head) {
                const last = events[n - 1];
                if (last && last[0] === 1024) {
                  last[1] = sdl.domButtons;
                  last[3] = sdl.domMods;
                  last[5] = +x || 0;
                  last[6] = +y || 0;
                  last[7] += +xrel || 0;
                  last[8] += +yrel || 0;
                  last[10] = clock();
                  sdl.coalescedMotion++;
                  return last;
                }
              }
              return push(record(1024, sdl.domButtons, 0, sdl.domMods, 0, x, y, xrel, yrel, null));
            };
            sdl.record = record;
            sdl.push = push;
            sdl.pushMotion = pushMotion;
            sdl.pushWindow = pushWindow;

            // ---- keyboard tables (SDL3 scancodes; see migration notes 4.4)
            const CODE_TO_SCANCODE = Object.create(null);
            for (let i = 0; i < 26; i++) CODE_TO_SCANCODE['Key' + String.fromCharCode(65 + i)] = 4 + i;
            for (let i = 1; i <= 9; i++) CODE_TO_SCANCODE['Digit' + i] = 29 + i;
            CODE_TO_SCANCODE.Digit0 = 39;
            for (let i = 1; i <= 12; i++) CODE_TO_SCANCODE['F' + i] = 57 + i;
            for (let i = 13; i <= 24; i++) CODE_TO_SCANCODE['F' + i] = 91 + i;
            for (let i = 1; i <= 9; i++) CODE_TO_SCANCODE['Numpad' + i] = 88 + i;
            CODE_TO_SCANCODE.Numpad0 = 98;
            const namedCodes = {
              Enter: 40, Escape: 41, Backspace: 42, Tab: 43, Space: 44, Minus: 45, Equal: 46,
              BracketLeft: 47, BracketRight: 48, Backslash: 49, IntlHash: 50, Semicolon: 51,
              Quote: 52, Backquote: 53, Comma: 54, Period: 55, Slash: 56, CapsLock: 57,
              PrintScreen: 70, ScrollLock: 71, Pause: 72, Insert: 73, Home: 74, PageUp: 75,
              Delete: 76, End: 77, PageDown: 78, ArrowRight: 79, ArrowLeft: 80, ArrowDown: 81,
              ArrowUp: 82, NumLock: 83, NumpadDivide: 84, NumpadMultiply: 85, NumpadSubtract: 86,
              NumpadAdd: 87, NumpadEnter: 88, NumpadDecimal: 99, IntlBackslash: 100,
              ContextMenu: 101, Power: 102, NumpadEqual: 103, Help: 117, Select: 119, Undo: 122,
              Cut: 123, Copy: 124, Paste: 125, Find: 126, AudioVolumeMute: 127,
              AudioVolumeUp: 128, AudioVolumeDown: 129, NumpadComma: 133, IntlRo: 135,
              KanaMode: 136, IntlYen: 137, Convert: 138, NonConvert: 139, Lang1: 144, Lang2: 145,
              ControlLeft: 224, ShiftLeft: 225, AltLeft: 226, MetaLeft: 227, OSLeft: 227,
              ControlRight: 228, ShiftRight: 229, AltRight: 230, MetaRight: 231, OSRight: 231,
              Sleep: 258, WakeUp: 259, MediaTrackNext: 267, MediaTrackPrevious: 268,
              MediaStop: 269, MediaPlayPause: 271, BrowserSearch: 280, BrowserHome: 281,
              BrowserBack: 282, BrowserForward: 283, BrowserStop: 284, BrowserRefresh: 285,
              BrowserFavorites: 286
            };
            Object.keys(namedCodes).forEach(code => { CODE_TO_SCANCODE[code] = namedCodes[code]; });
            const SCANCODE_TO_CODE = Object.create(null);
            Object.keys(CODE_TO_SCANCODE).forEach(code => {
              const scancode = CODE_TO_SCANCODE[code];
              if (SCANCODE_TO_CODE[scancode] === undefined) SCANCODE_TO_CODE[scancode] = code;
            });
            // Unshifted US keycodes; everything else is scancode | SDLK_SCANCODE_MASK.
            const US_KEYCODES = new Int32Array(512);
            for (let i = 0; i < 26; i++) US_KEYCODES[4 + i] = 97 + i;
            for (let i = 0; i < 9; i++) US_KEYCODES[30 + i] = 49 + i;
            US_KEYCODES[39] = 48;
            const fixedKeycodes = [
              40, 13, 41, 27, 42, 8, 43, 9, 44, 32, 45, 45, 46, 61, 47, 91, 48, 93, 49, 92,
              50, 35, 51, 59, 52, 39, 53, 96, 54, 44, 55, 46, 56, 47, 76, 127
            ];
            for (let i = 0; i < fixedKeycodes.length; i += 2) US_KEYCODES[fixedKeycodes[i]] = fixedKeycodes[i + 1];
            const scancodeOf = code => {
              const scancode = CODE_TO_SCANCODE[code];
              return scancode === undefined ? 0 : scancode;
            };
            // SDL3 keycodes are unshifted; letters follow the layout but stay Latin
            // (SDL_HINT_KEYCODE_OPTIONS "latin_letters"), digits stay digits
            // ("french_numbers") and punctuation follows the layout when it is ASCII.
            const keycodeFor = (scancode, code, key) => {
              if (scancode <= 0 || scancode >= 512) return 0;
              const fixed = US_KEYCODES[scancode];
              const letter = scancode >= 4 && scancode <= 29;
              const punctuation = scancode >= 45 && scancode <= 56;
              if (letter || punctuation) {
                let ch = null;
                const layoutMap = sdl.layoutMap;
                const layoutCode = code || SCANCODE_TO_CODE[scancode];
                if (layoutMap && layoutCode && typeof layoutMap.get === 'function') {
                  const mapped = layoutMap.get(layoutCode);
                  if (typeof mapped === 'string' && mapped.length === 1) ch = mapped;
                }
                if (ch === null && letter && typeof key === 'string' && key.length === 1) ch = key;
                if (ch !== null) {
                  const cp = ch.toLowerCase().charCodeAt(0);
                  if (letter ? (cp >= 97 && cp <= 122) : (cp > 32 && cp < 127 && !(cp >= 65 && cp <= 90))) {
                    return cp;
                  }
                }
                return fixed;
              }
              if (fixed) return fixed;
              return scancode | 0x40000000;
            };
            sdl.keycodeForScancode = scancode => keycodeFor(scancode | 0, SCANCODE_TO_CODE[scancode | 0], null);
            sdl.scancodeOf = scancodeOf;
            try {
              const keyboard = typeof navigator !== 'undefined' ? navigator.keyboard : null;
              if (keyboard && typeof keyboard.getLayoutMap === 'function') {
                Promise.resolve(keyboard.getLayoutMap()).then(map => { sdl.layoutMap = map; }, () => {});
              }
            } catch (ignored) {
              sdl.layoutMap = null;
            }
            // SDL_Keymod bits: LSHIFT 1, RSHIFT 2, LCTRL 64, RCTRL 128, LALT 256,
            // RALT 512, LGUI 1024, RGUI 2048, NUM 4096, CAPS 8192, MODE 16384, SCROLL 32768.
            const MOD_BITS = {224: 64, 225: 1, 226: 256, 227: 1024, 228: 128, 229: 2, 230: 512, 231: 2048};
            const LOCK_BITS = 4096 | 8192 | 32768;
            const reconcile = (mods, pressed, mask, fallback) => pressed
              ? ((mods & mask) ? mods : (mods | fallback))
              : (mods & ~mask);
            const updateMods = (e, scancode, down) => {
              let mods = sdl.domMods & 0x0FC3;
              const bit = MOD_BITS[scancode];
              if (bit) mods = down ? (mods | bit) : (mods & ~bit);
              if (e) {
                if (typeof e.shiftKey === 'boolean') mods = reconcile(mods, e.shiftKey, 3, 1);
                if (typeof e.ctrlKey === 'boolean') mods = reconcile(mods, e.ctrlKey, 192, 64);
                if (typeof e.altKey === 'boolean') mods = reconcile(mods, e.altKey, 768, 256);
                if (typeof e.metaKey === 'boolean') mods = reconcile(mods, e.metaKey, 3072, 1024);
              }
              if (e && typeof e.getModifierState === 'function') {
                if (e.getModifierState('NumLock')) mods |= 4096;
                if (e.getModifierState('CapsLock')) mods |= 8192;
                if (e.getModifierState('AltGraph')) mods |= 16384;
                if (e.getModifierState('ScrollLock')) mods |= 32768;
              } else {
                mods |= sdl.domMods & LOCK_BITS;
              }
              sdl.domMods = mods & 0xFFFF;
              return sdl.domMods;
            };
            // Printable filter of BrowserGlfw: TEXT_INPUT follows the KEY_DOWN only
            // while SDL text input is active (SDL3 semantics).
            const printable = e => {
              const altGraph = typeof e.getModifierState === 'function' && e.getModifierState('AltGraph');
              return typeof e.key === 'string'
                && (e.key.length === 1 || (e.key.length === 2 && e.key.codePointAt(0) > 65535))
                && !e.metaKey && (!e.ctrlKey || altGraph);
            };
            // Key records go through the clipboard bridge (dev.gaius.browser.BrowserClipboard):
            // a paste chord keeps its default and its records wait until the system text is
            // known; Cmd+A/C/V/X on Apple platforms reach the game as Ctrl chords (LCTRL),
            // pressed and released at once since macOS drops keyups while Cmd is held.
            const clip = () => w.__gaiusClipboardBridge || null;

            // ---- canvas, pointer lock, keyboard lock (copied from BrowserGlfw)
            const canvas = () => w.__gaiusCanvasHost.current();
            const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
            const rememberPointerLockError = error => {
              const message = String(error && (error.message || error.name) || error);
              w.__gaiusPointerLockLastError = message;
              w.__gaiusPointerLockErrors.push({message: message, at: Date.now()});
              if (w.__gaiusPointerLockErrors.length > 20) w.__gaiusPointerLockErrors.shift();
            };
            // Touch controls drive relative motion through w.__gaiusInput.look() (a virtual
            // pointer lock), so a real lock is never requested while they are active.
            const requestPointerLockIfWanted = () => {
              const c = canvas();
              if (!w.__gaiusWantPointerLock || w.__gaiusTouchActive || !c || !c.requestPointerLock ||
                  document.pointerLockElement === c) {
                return;
              }
              try {
                const lockResult = c.requestPointerLock();
                if (lockResult && lockResult.catch) lockResult.catch(rememberPointerLockError);
              } catch (error) {
                rememberPointerLockError(error);
              }
            };
            const rememberKeyboardLockError = error => {
              w.__gaiusKeyboardLockPending = false;
              w.__gaiusKeyboardLockHeld = false;
              w.__gaiusKeyboardLockLastError = String(error && (error.message || error.name) || error);
            };
            const requestKeyboardLockIfWanted = () => {
              const keyboard = typeof navigator !== 'undefined' ? navigator.keyboard : null;
              if (!document.fullscreenElement || !keyboard || !keyboard.lock || w.__gaiusKeyboardLockHeld || w.__gaiusKeyboardLockPending) {
                return;
              }
              w.__gaiusKeyboardLockPending = true;
              try {
                // Browser accelerators are captured only in API fullscreen.
                // Locking KeyW covers Ctrl+W while leaving Ctrl+R and Ctrl+L alone.
                // Locking Escape hands a short press to the game (its pause menu) instead of
                // ending fullscreen and pointer lock; holding Escape still leaves fullscreen.
                const result = keyboard.lock(['KeyW', 'Escape']);
                if (result && result.then) {
                  result.then(() => {
                    w.__gaiusKeyboardLockPending = false;
                    w.__gaiusKeyboardLockHeld = !!document.fullscreenElement;
                  }, rememberKeyboardLockError);
                } else {
                  w.__gaiusKeyboardLockPending = false;
                  w.__gaiusKeyboardLockHeld = true;
                }
              } catch (error) {
                rememberKeyboardLockError(error);
              }
            };
            const requestGameFullscreen = () => {
              const root = document.documentElement;
              const keyboard = typeof navigator !== 'undefined' ? navigator.keyboard : null;
              if (!w.__gaiusWantPointerLock || document.fullscreenElement ||
                  !keyboard || !keyboard.lock || !root || !root.requestFullscreen) {
                requestKeyboardLockIfWanted();
                requestPointerLockIfWanted();
                return;
              }
              if (w.__gaiusFullscreenPending) return;
              w.__gaiusFullscreenPending = true;
              const ready = () => {
                w.__gaiusFullscreenPending = false;
                requestKeyboardLockIfWanted();
                requestPointerLockIfWanted();
              };
              const failed = error => {
                w.__gaiusFullscreenPending = false;
                rememberKeyboardLockError(error);
                requestPointerLockIfWanted();
              };
              try { Promise.resolve(root.requestFullscreen()).then(ready, failed); }
              catch (error) { failed(error); }
            };
            addEventListener('fullscreenchange', () => {
              w.__gaiusKeyboardLockHeld = false;
              const keyboard = typeof navigator !== 'undefined' ? navigator.keyboard : null;
              if (document.fullscreenElement) requestKeyboardLockIfWanted();
              else if (keyboard && keyboard.unlock) keyboard.unlock();
              // Leaving browser fullscreen ends the SDL fullscreen state that
              // SDL_SetWindowFullscreen(true) requested.
              if (!document.fullscreenElement && sdl.fullscreen) {
                sdl.fullscreen = false;
                pushWindow(536, 0, 0);
              }
            });
            // Ctrl+W is a valid sprint + forward chord in Minecraft; Chrome reserves
            // it for closing the tab. The normal handler still forwards both key events.
            addEventListener('keydown', e => {
              if (e.ctrlKey && !e.altKey && !e.metaKey && e.code === 'KeyW') {
                e.preventDefault();
              }
            }, {capture: true, passive: false});
            const urlNumber = name => {
              try {
                const value = new URLSearchParams(location.search).get(name);
                const number = Number(value);
                return Number.isFinite(number) ? number : NaN;
              } catch (ignored) {
                return NaN;
              }
            };
            w.__gaiusResolvePixelRatio = () => {
              const minecraftState = w.__gaiusMinecraftState || null;
              const inWorld = !!(minecraftState && minecraftState.level);
              const minDpr = clamp(
                inWorld
                  ? (Number(w.__gaiusWorldMinDpr) || Number(w.__gaiusMinDpr) || 1.0)
                  : (Number(w.__gaiusMenuMinDpr) || Number(w.__gaiusMinDpr) || 1.0),
                1.0,
                3.0);
              const forced = urlNumber('dpr');
              if (forced > 0) return clamp(forced, 0.2, 3.0);
              const forcedPixelRatio = urlNumber('pixelRatio');
              if (forcedPixelRatio > 0) return clamp(forcedPixelRatio, 0.2, 3.0);
              const urlMax = urlNumber('maxDpr');
              const raw = Number(w.devicePixelRatio) || 1.0;
              let maxDpr = urlMax > 0 ? urlMax : (Number(w.__gaiusMaxDpr) || 1.0);
              // The quality runtime caps the ratio per GPU tier. The launcher's frame-rate
              // governor still applies once it has stepped __gaiusMaxDpr below its default.
              const quality = w.GaiusQuality ? w.GaiusQuality.runtime : null;
              if (!(urlMax > 0) && quality && typeof quality.resolvePixelRatio === 'function') {
                let tierMax = NaN;
                try {
                  tierMax = Number(quality.resolvePixelRatio(raw));
                } catch (ignored) {
                  tierMax = NaN;
                }
                if (tierMax > 0) {
                  const governed = Number(w.__gaiusMaxDpr) < (Number(w.__gaiusDefaultMaxDpr) || 0);
                  maxDpr = governed ? Math.min(tierMax, Number(w.__gaiusMaxDpr)) : tierMax;
                }
              }
              return clamp(Math.min(raw, maxDpr), minDpr, 3.0);
            };
            // emitEvent marks a change the game did not ask for (browser resize,
            // launcher DPR policy): 26.3 never calls SDL_GetWindowSize and learns the
            // logical size only from WINDOW_RESIZED (518), the pixel size from
            // WINDOW_PIXEL_SIZE_CHANGED (519).
            w.__gaiusApplyCanvasResolution = (width, height, emitEvent) => {
              const c = canvas();
              if (!c) return;
              const cssWidth = Math.max(1, Math.round(Number(width) || w.innerWidth || 1));
              const cssHeight = Math.max(1, Math.round(Number(height) || w.innerHeight || 1));
              const pixelRatio = w.__gaiusResolvePixelRatio();
              const framebufferWidth = Math.max(1, Math.round(cssWidth * pixelRatio));
              const framebufferHeight = Math.max(1, Math.round(cssHeight * pixelRatio));
              const previous = w.__gaiusDisplay;
              c.style.width = cssWidth + 'px';
              c.style.height = cssHeight + 'px';
              const framebufferChanged = c.width !== framebufferWidth || c.height !== framebufferHeight;
              const cssChanged = !previous || previous.cssWidth !== cssWidth || previous.cssHeight !== cssHeight;
              c.width = framebufferWidth;
              c.height = framebufferHeight;
              w.__gaiusDisplay = {
                cssWidth: cssWidth,
                cssHeight: cssHeight,
                framebufferWidth: framebufferWidth,
                framebufferHeight: framebufferHeight,
                pixelRatio: pixelRatio,
                rawDevicePixelRatio: Number(w.devicePixelRatio) || 1.0,
                maxDpr: Number(w.__gaiusMaxDpr) || 1.0
              };
              w.__gaiusCanvasRect = null;
              if (emitEvent) {
                if (cssChanged) pushWindow(518, cssWidth, cssHeight);
                if (cssChanged || framebufferChanged) pushWindow(519, framebufferWidth, framebufferHeight);
              }
            };
            const watchPixelRatio = () => {
              if (typeof w.matchMedia !== 'function') return;
              try {
                const query = w.matchMedia('(resolution: ' + (Number(w.devicePixelRatio) || 1) + 'dppx)');
                if (!query || typeof query.addEventListener !== 'function') return;
                const changed = () => {
                  try { query.removeEventListener('change', changed); } catch (ignored) {}
                  const display = w.__gaiusDisplay;
                  if (display) w.__gaiusApplyCanvasResolution(display.cssWidth, display.cssHeight, true);
                  watchPixelRatio();
                };
                query.addEventListener('change', changed);
              } catch (ignored) {
                // matchMedia resolution queries are optional.
              }
            };
            watchPixelRatio();
            const canvasRect = c => {
              const now = clock();
              const cached = w.__gaiusCanvasRect;
              if (cached && now - (w.__gaiusCanvasRectAt || 0) < 250) return cached;
              const rect = c ? c.getBoundingClientRect() : {left: 0, top: 0, width: 0, height: 0};
              w.__gaiusCanvasRect = {left: rect.left, top: rect.top, width: rect.width, height: rect.height};
              w.__gaiusCanvasRectAt = now;
              return w.__gaiusCanvasRect;
            };
            const updateCursorFromMouseEvent = e => {
              const c = canvas();
              const r = canvasRect(c);
              const locked = !!c && document.pointerLockElement === c;
              w.__gaiusCursorX = locked ? (w.__gaiusCursorX || 0) + (+e.movementX || 0) : e.clientX - r.left;
              w.__gaiusCursorY = locked ? (w.__gaiusCursorY || 0) + (+e.movementY || 0) : e.clientY - r.top;
              return [w.__gaiusCursorX, w.__gaiusCursorY];
            };
            sdl.hookCanvas = c => {
              if (!c || c.__gaiusSdlHooked || typeof c.addEventListener !== 'function') return;
              c.__gaiusSdlHooked = true;
              c.addEventListener('mouseenter', () => pushWindow(524, 0, 0));
              c.addEventListener('mouseleave', () => pushWindow(525, 0, 0));
            };
            sdl.hookCanvas(canvas());
            w.__gaiusCanvasHost.onReplace(c => sdl.hookCanvas(c));
            sdl.requestPointerLockIfWanted = requestPointerLockIfWanted;

            // ---- Escape and lost pointer lock
            // Without Keyboard Lock (windowed, or a browser without it) the browser spends a
            // short Escape on ending pointer lock and the page never sees that key-down. The
            // game then still has the mouse grabbed and no pause menu, so the lost lock is
            // handed to it as that one Escape press. A browser that also delivers the key-down
            // is covered both ways: a recent Escape key-down skips the injected press, and a
            // late one right after the injected press is dropped, so the menu opens only once.
            const escape = {lastDownAt: -Infinity, suppressUntil: -Infinity, lockHeld: false};
            const pageFocused = () => document.visibilityState !== 'hidden'
              && (typeof document.hasFocus !== 'function' || document.hasFocus());
            const injectEscape = () => {
              const keycode = keycodeFor(41, 'Escape', 'Escape');
              const mods = sdl.domMods;
              // Through the clipboard bridge, so it keeps its place behind a pending paste chord.
              const press = () => {
                push(record(768, 41, keycode, mods, 0, 27, 0, 0, 0, null));
                push(record(769, 41, keycode, mods, 0, 27, 0, 0, 0, null));
              };
              const bridge = clip();
              if (bridge) bridge.deliver(press); else press();
            };
            const pointerLockLost = lostAt => {
              const c = canvas();
              if (!w.__gaiusWantPointerLock || w.__gaiusTouchActive || (c && document.pointerLockElement === c)) return;
              // Focus moved to another tab, window or application: the focus-loss path below
              // reports it and the game's own pauseOnLostFocus decides.
              if (!pageFocused()) return;
              if (escape.lastDownAt >= lostAt - 500) return;
              escape.suppressUntil = clock() + 500;
              w.__gaiusEscapePauseInjected = (w.__gaiusEscapePauseInjected | 0) + 1;
              injectEscape();
            };
            if (typeof document.addEventListener === 'function') {
              document.addEventListener('pointerlockchange', () => {
                const c = canvas();
                const locked = !!c && document.pointerLockElement === c;
                const lost = escape.lockHeld && !locked;
                escape.lockHeld = locked;
                if (!lost || !w.__gaiusWantPointerLock) return;
                const lostAt = clock();
                // Let a blur or visibility change that ended the lock arrive first.
                setTimeout(() => pointerLockLost(lostAt), 100);
              });
            }

            // ---- DOM input listeners
            addEventListener('keydown', e => {
              if (e.code === 'Escape') {
                if (!e.repeat && clock() < escape.suppressUntil) {
                  escape.suppressUntil = -Infinity;
                  e.preventDefault();
                  return;
                }
                escape.lastDownAt = clock();
              }
              const bridge = clip();
              const keepDefault = bridge ? bridge.keyDown(e) : false;
              const edit = !!bridge && bridge.appleEditChord(e);
              const scancode = scancodeOf(e.code);
              const mods = updateMods(e, scancode, true);
              const keycode = keycodeFor(scancode, e.code, e.key);
              if (scancode && !edit) {
                sdl.domKeys[scancode] = keycode;
                w.__gaiusSdlKeys[scancode] = true;
              }
              const keyMods = edit ? (mods | 64) : mods;
              const repeat = e.repeat ? 1 : 0;
              const legacy = e.keyCode | 0;
              const text = sdl.textInput && printable(e) ? e.key : null;
              const deliver = () => {
                push(record(768, scancode, keycode, keyMods, repeat, legacy, 0, 0, 0, null));
                if (text !== null) push(record(771, 0, 0, 0, 0, 0, 0, 0, 0, text));
                if (edit) push(record(769, scancode, keycode, keyMods, 0, legacy, 0, 0, 0, null));
              };
              if (bridge) bridge.deliver(deliver); else deliver();
              // A paste chord keeps its default: the paste event carries the system text.
              if (document.activeElement === canvas() && !keepDefault) e.preventDefault();
            });
            addEventListener('keyup', e => {
              const bridge = clip();
              if (bridge) bridge.keyUp(e);
              const scancode = scancodeOf(e.code);
              const mods = updateMods(e, scancode, false);
              if (scancode && sdl.domKeys[scancode] === undefined) return;
              const keycode = scancode ? sdl.domKeys[scancode] : 0;
              if (scancode) {
                delete sdl.domKeys[scancode];
                w.__gaiusSdlKeys[scancode] = false;
              }
              const r = record(769, scancode, keycode, mods, 0, e.keyCode | 0, 0, 0, 0, null);
              if (bridge) bridge.deliver(() => push(r)); else push(r);
            });
            // Direct input for the launcher's touch controls (touch-controls.js), the same API as
            // BrowserGlfw: CSS pixels relative to #mc-canvas, GLFW button numbers (0 left, 1 right,
            // 2 middle) and KeyboardEvent.code names. Records go through the same domKeys,
            // domButtons and domMods state as the DOM listeners, so blur still releases them.
            // Nothing here focuses the canvas or asks for fullscreen or pointer lock; look()
            // supplies xrel/yrel directly, so relative mouse mode works without pointer lock.
            const injected = {keys: Object.create(null), buttons: 0};
            const injectKey = (code, down) => {
              const scancode = scancodeOf(code);
              if (!scancode || !!injected.keys[scancode] === !!down) return false;
              if (down) {
                const downMods = updateMods(null, scancode, true);
                const downKeycode = keycodeFor(scancode, code, null);
                injected.keys[scancode] = true;
                sdl.domKeys[scancode] = downKeycode;
                w.__gaiusSdlKeys[scancode] = true;
                push(record(768, scancode, downKeycode, downMods, 0, 0, 0, 0, 0, null));
                return true;
              }
              delete injected.keys[scancode];
              // Like keyup: nothing to release when blur already reset the keyboard.
              if (sdl.domKeys[scancode] === undefined) return false;
              const upMods = updateMods(null, scancode, false);
              const upKeycode = sdl.domKeys[scancode];
              delete sdl.domKeys[scancode];
              w.__gaiusSdlKeys[scancode] = false;
              push(record(769, scancode, upKeycode, upMods, 0, 0, 0, 0, 0, null));
              return true;
            };
            const injectButton = (button, down) => {
              const glfwButton = button | 0;
              if (glfwButton < 0 || glfwButton > 4) return false;
              const sdlButton = glfwButton === 1 ? 3 : (glfwButton === 2 ? 2 : (glfwButton === 0 ? 1 : glfwButton + 1));
              const bit = 1 << (sdlButton - 1);
              if (!!(injected.buttons & bit) === !!down) return false;
              injected.buttons = down ? (injected.buttons | bit) : (injected.buttons & ~bit);
              sdl.domButtons = down ? (sdl.domButtons | bit) : (sdl.domButtons & ~bit);
              push(record(down ? 1025 : 1026, sdlButton, 1, sdl.domMods, 0,
                w.__gaiusCursorX || 0, w.__gaiusCursorY || 0, 0, 0, null));
              return true;
            };
            w.__gaiusInput = {
              backend: 'sdl',
              pointer: (x, y) => {
                const nx = +x || 0;
                const ny = +y || 0;
                const dx = nx - (w.__gaiusCursorX || 0);
                const dy = ny - (w.__gaiusCursorY || 0);
                w.__gaiusCursorX = nx;
                w.__gaiusCursorY = ny;
                pushMotion(nx, ny, dx, dy);
              },
              look: (dx, dy) => {
                const rx = +dx || 0;
                const ry = +dy || 0;
                w.__gaiusCursorX = (w.__gaiusCursorX || 0) + rx;
                w.__gaiusCursorY = (w.__gaiusCursorY || 0) + ry;
                pushMotion(w.__gaiusCursorX, w.__gaiusCursorY, rx, ry);
              },
              button: injectButton,
              key: injectKey,
              // SDL3 semantics: TEXT_INPUT only while the game has text input started.
              text: value => {
                const raw = value == null ? '' : String(value);
                let text = '';
                for (let i = 0; i < raw.length; i++) {
                  const unit = raw.charCodeAt(i);
                  if (unit >= 32 && unit !== 127) text += raw.charAt(i);
                }
                if (!sdl.textInput || !text) return false;
                push(record(771, 0, 0, 0, 0, 0, 0, 0, 0, text));
                return true;
              },
              // Wheel steps, positive away from the user (SDL3 wheel y).
              wheel: (dx, dy) => {
                push(record(1027, 0, 0, sdl.domMods, 0, +dx || 0, +dy || 0,
                  w.__gaiusCursorX || 0, w.__gaiusCursorY || 0, null));
              },
              releaseAll: () => {
                Object.keys(injected.keys).forEach(scancode => {
                  const code = SCANCODE_TO_CODE[scancode | 0];
                  if (code) injectKey(code, false);
                  else delete injected.keys[scancode];
                });
                for (let button = 0; button <= 4; button++) injectButton(button, false);
              },
              state: () => {
                return {
                  backend: 'sdl',
                  wantPointerLock: !!w.__gaiusWantPointerLock,
                  textInput: !!sdl.textInput,
                  textInputArea: sdl.textInputArea
                };
              }
            };
            addEventListener('mousedown', e => {
              const c = canvas();
              if (c && c.focus) c.focus();
              const p = updateCursorFromMouseEvent(e);
              const mods = updateMods(e, 0, false);
              if (p[0] !== sdl.lastX || p[1] !== sdl.lastY) pushMotion(p[0], p[1], 0, 0);
              const button = (e.button | 0) + 1;
              sdl.domButtons |= 1 << ((button - 1) & 31);
              push(record(1025, button, Math.max(1, Math.min(255, e.detail | 0)), mods, 0, p[0], p[1], 0, 0, null));
              if (c && e.target === c) requestGameFullscreen();
            });
            addEventListener('mouseup', e => {
              const p = updateCursorFromMouseEvent(e);
              const mods = updateMods(e, 0, false);
              if (p[0] !== sdl.lastX || p[1] !== sdl.lastY) pushMotion(p[0], p[1], 0, 0);
              const button = (e.button | 0) + 1;
              sdl.domButtons &= ~(1 << ((button - 1) & 31));
              push(record(1026, button, Math.max(1, Math.min(255, e.detail | 0)), mods, 0, p[0], p[1], 0, 0, null));
            });
            addEventListener('mousemove', e => {
              const p = updateCursorFromMouseEvent(e);
              updateMods(e, 0, false);
              pushMotion(p[0], p[1], +e.movementX || 0, +e.movementY || 0);
            });
            // SDL3 wheel: y > 0 away from the user, x > 0 to the right.
            addEventListener('wheel', e => {
              const scale = e.deltaMode === 1 ? 100 / 3 : (e.deltaMode === 2 ? 100 : 1);
              const x = (+e.deltaX || 0) * scale / 100;
              const y = -(+e.deltaY || 0) * scale / 100;
              const mods = updateMods(e, 0, false);
              push(record(1027, 0, 0, mods, 0, x, y, w.__gaiusCursorX || 0, w.__gaiusCursorY || 0, null));
              if (document.activeElement === canvas()) e.preventDefault();
            }, {passive: false});
            // The window focus the game sees (Window.isFocused, and through it vanilla's
            // pauseOnLostFocus) follows the page: a window blur, and also a hidden page
            // (another tab, a minimized window) that did not blur first. A page made visible
            // again reports the gain only once it has focus, otherwise the next focus does.
            let pageFocusLost = false;
            addEventListener('focus', () => {
              pageFocusLost = false;
              pushWindow(526, 0, 0);
            });
            // SDL resets the keyboard on focus loss; also release held mouse buttons
            // so that nothing stays pressed while the page is in the background.
            const loseFocus = () => {
              pageFocusLost = true;
              Object.keys(sdl.domKeys).forEach(key => {
                const scancode = key | 0;
                push(record(769, scancode, sdl.domKeys[key], 0, 0, 0, 0, 0, 0, null));
                w.__gaiusSdlKeys[scancode] = false;
              });
              sdl.domKeys = Object.create(null);
              for (let button = 1; button <= 5; button++) {
                if (sdl.domButtons & (1 << (button - 1))) {
                  push(record(1026, button, 1, 0, 0, sdl.lastX || 0, sdl.lastY || 0, 0, 0, null));
                }
              }
              sdl.domButtons = 0;
              sdl.domMods &= LOCK_BITS;
              pushWindow(527, 0, 0);
            };
            addEventListener('blur', loseFocus);
            if (typeof document.addEventListener === 'function') {
              document.addEventListener('visibilitychange', () => {
                if (document.visibilityState === 'hidden') {
                  if (!pageFocusLost) loseFocus();
                } else if (pageFocusLost && pageFocused()) {
                  pageFocusLost = false;
                  pushWindow(526, 0, 0);
                }
              });
            }
            addEventListener('resize', () => {
              w.__gaiusApplyCanvasResolution(w.innerWidth, w.innerHeight, true);
            });
            addEventListener('beforeunload', () => {
              const keyboard = typeof navigator !== 'undefined' ? navigator.keyboard : null;
              if (keyboard && keyboard.unlock) {
                try { keyboard.unlock(); } catch (ignored) {}
              }
              w.__gaiusKeyboardLockHeld = false;
              w.__gaiusKeyboardLockPending = false;
            });

            // ---- game-requested window state
            sdl.setTextInput = on => { sdl.textInput = !!on; };
            sdl.setTextInputArea = (x, y, width, height, cursor) => {
              sdl.textInputArea = {x: x | 0, y: y | 0, w: width | 0, h: height | 0, cursor: cursor | 0};
            };
            sdl.setRelative = on => {
              sdl.relative = !!on;
              if (on) {
                w.__gaiusWantPointerLock = true;
                // grabMouse normally runs inside the poll that handled the click, so
                // the page still has transient activation; the canvas mousedown path
                // stays as the fallback.
                requestPointerLockIfWanted();
              } else {
                w.__gaiusWantPointerLock = false;
                if (document.exitPointerLock) {
                  try {
                    const exitResult = document.exitPointerLock();
                    if (exitResult && exitResult.catch) exitResult.catch(() => {});
                  } catch (ignored) {
                    // Pointer-lock exit is best effort.
                  }
                }
              }
            };
            sdl.warp = (x, y) => {
              w.__gaiusCursorX = +x || 0;
              w.__gaiusCursorY = +y || 0;
              sdl.cursorX = w.__gaiusCursorX;
              sdl.cursorY = w.__gaiusCursorY;
              sdl.lastX = sdl.cursorX;
              sdl.lastY = sdl.cursorY;
            };
            const CURSORS = [
              'default', 'text', 'wait', 'crosshair', 'progress', 'nwse-resize', 'nesw-resize',
              'ew-resize', 'ns-resize', 'move', 'not-allowed', 'pointer', 'nw-resize', 'n-resize',
              'ne-resize', 'e-resize', 'se-resize', 's-resize', 'sw-resize', 'w-resize'
            ];
            sdl.setCursor = id => {
              const c = canvas();
              if (!c || !c.style) return;
              c.style.cursor = CURSORS[id | 0] || 'default';
            };
            sdl.setFullscreen = on => {
              on = !!on;
              if (on === sdl.fullscreen) return true;
              const root = document.documentElement;
              if (on) {
                if (!document.fullscreenElement) {
                  if (!root || !root.requestFullscreen) return false;
                  try {
                    Promise.resolve(root.requestFullscreen()).then(() => {}, () => {
                      if (sdl.fullscreen) {
                        sdl.fullscreen = false;
                        pushWindow(536, 0, 0);
                      }
                    });
                  } catch (error) {
                    return false;
                  }
                }
                sdl.fullscreen = true;
                pushWindow(535, 0, 0);
                return true;
              }
              sdl.fullscreen = false;
              pushWindow(536, 0, 0);
              if (document.fullscreenElement && document.exitFullscreen) {
                try {
                  const exitResult = document.exitFullscreen();
                  if (exitResult && exitResult.catch) exitResult.catch(() => {});
                } catch (ignored) {
                  // Leaving fullscreen is best effort.
                }
              }
              return true;
            };
            """)
    static native void installDomBridge();

    // ---------------------------------------------------------------- event engine

    @JSBody(script = "const s=window.__gaiusSdl; return s ? (s.poll()|0) : 0;")
    static native int poll();

    @JSBody(script = "const s=window.__gaiusSdl; return !!(s && s.peek());")
    static native boolean peek();

    @JSBody(params = {"index"}, script = "const s=window.__gaiusSdl; const r=s&&s.current; return r ? (r[index]|0) : 0;")
    static native int recordInt(int index);

    @JSBody(params = {"index"}, script = "const s=window.__gaiusSdl; const r=s&&s.current; return r ? +r[index] : 0;")
    static native double recordDouble(int index);

    @JSBody(script = "const s=window.__gaiusSdl; const r=s&&s.current; return r && r[9] != null ? String(r[9]) : null;")
    static native String recordText();

    @JSBody(script = "const s=window.__gaiusSdl; const r=s&&s.current; return r ? +r[10] : 0;")
    static native double recordTimestamp();

    @JSBody(params = {"min", "max"}, script = "const s=window.__gaiusSdl; return s ? (s.flush(min, max)|0) : 0;")
    static native int flush(int min, int max);

    @JSBody(params = {"scancode"}, script = "const s=window.__gaiusSdl; return s ? (s.keys[scancode & 511]|0) : 0;")
    static native int keyState(int scancode);

    @JSBody(script = "const s=window.__gaiusSdl; return s ? (s.mods & 0xFFFF) : 0;")
    static native int modState();

    @JSBody(script = "const s=window.__gaiusSdl; return s ? (s.buttons|0) : 0;")
    static native int buttonState();

    @JSBody(script = "const s=window.__gaiusSdl; return s ? +s.cursorX : 0;")
    static native double cursorX();

    @JSBody(script = "const s=window.__gaiusSdl; return s ? +s.cursorY : 0;")
    static native double cursorY();

    @JSBody(script = "const s=window.__gaiusSdl; return !s || s.focused !== false;")
    static native boolean focused();

    @JSBody(script = "const s=window.__gaiusSdl; return !!(s && s.relative);")
    static native boolean relativeMouseMode();

    @JSBody(script = "const s=window.__gaiusSdl; return !!(s && s.fullscreen);")
    static native boolean fullscreen();

    @JSBody(params = {"scancode"}, script = "const s=window.__gaiusSdl; return s ? (s.keycodeForScancode(scancode)|0) : 0;")
    static native int keycodeForScancode(int scancode);

    @JSBody(params = {"on"}, script = "const s=window.__gaiusSdl; if (s) s.setTextInput(on);")
    static native void setTextInput(boolean on);

    @JSBody(params = {"x", "y", "width", "height", "cursor"},
            script = "const s=window.__gaiusSdl; if (s) s.setTextInputArea(x, y, width, height, cursor);")
    static native void setTextInputArea(int x, int y, int width, int height, int cursor);

    @JSBody(params = {"on"}, script = "const s=window.__gaiusSdl; if (s) s.setRelative(on);")
    static native void setRelativeMouseMode(boolean on);

    @JSBody(params = {"x", "y"}, script = "const s=window.__gaiusSdl; if (s) s.warp(x, y);")
    static native void warpMouse(double x, double y);

    @JSBody(params = {"systemCursor"}, script = "const s=window.__gaiusSdl; if (s) s.setCursor(systemCursor);")
    static native void setCursor(int systemCursor);

    @JSBody(params = {"on"}, script = "const s=window.__gaiusSdl; return s ? !!s.setFullscreen(on) : false;")
    static native boolean setFullscreen(boolean on);

    // ---------------------------------------------------------------- canvas and context

    /** Makes sure the game canvas exists without changing its size (installDomBridge runs first). */
    @JSBody(script = """
            const host = window.__gaiusCanvasHost;
            if (!host) return;
            const canvasElement = host.ensure();
            const s = window.__gaiusSdl;
            if (s && s.hookCanvas) s.hookCanvas(canvasElement);
            """)
    static native void prepareCanvas();

    /** The first visible SDL window adopts the canvas (BrowserGlfw.createCanvas parity). */
    @JSBody(params = {"width", "height", "title"}, script = """
            const host = window.__gaiusCanvasHost;
            const canvasElement = host ? host.current() : null;
            if (!canvasElement) return;
            if (window.__gaiusApplyCanvasResolution) {
              window.__gaiusApplyCanvasResolution(width, height, false);
            } else {
              const quality = window.GaiusQuality ? window.GaiusQuality.runtime : null;
              const pixelRatio = quality && typeof quality.resolvePixelRatio === 'function'
                ? (Number(quality.resolvePixelRatio(devicePixelRatio || 1)) || 1)
                : Math.min(devicePixelRatio || 1, 1);
              canvasElement.style.width = width + 'px'; canvasElement.style.height = height + 'px';
              canvasElement.width = Math.max(1, Math.round(width * pixelRatio));
              canvasElement.height = Math.max(1, Math.round(height * pixelRatio));
            }
            document.title = title;
            if (canvasElement.focus) canvasElement.focus();
            """)
    static native void showMainWindow(int width, int height, String title);

    /**
     * Contract C5: creates {@code window.__gaiusWebGL} once (same attributes as
     * BrowserGlfw.createCanvas) and reports whether it exists. When the canvas cannot give a
     * {@code webgl2} context (it holds the context of an abandoned WebGPU attempt), a fresh
     * element replaces it and is asked once more.
     */
    @JSBody(script = """
            const host = window.__gaiusCanvasHost;
            let canvasElement = host ? host.ensure() : null;
            const existing = window.__gaiusWebGL;
            if (existing && (!canvasElement || !existing.canvas || existing.canvas === canvasElement)) return true;
            if (!canvasElement || !canvasElement.getContext) return false;
            let preserveDrawingBuffer = false;
            try {
              preserveDrawingBuffer = new URLSearchParams(location.search).get('preserveDrawingBuffer') === '1';
            } catch (ignored) {
              preserveDrawingBuffer = false;
            }
            const attributes = {
              alpha: false,
              antialias: false,
              depth: true,
              stencil: true,
              powerPreference: 'high-performance',
              preserveDrawingBuffer: preserveDrawingBuffer
            };
            window.__gaiusWebGL = canvasElement.getContext('webgl2', attributes);
            if (!window.__gaiusWebGL && host) {
              try {
                canvasElement = host.replace('sdl-webgl2-context');
                window.__gaiusWebGL = canvasElement.getContext('webgl2', attributes);
              } catch (ignored) {
                window.__gaiusWebGL = null;
              }
            }
            if (window.__gaiusWebGL) {
              canvasElement.__gaiusContextKind = 'webgl2';
              window.__gaiusGpuBackend = 'webgl2';
            }
            // Lets the quality runtime enable its extensions on the game context before
            // the first frame instead of attaching lazily.
            if (window.__gaiusWebGL && window.GaiusQuality && window.GaiusQuality.runtime) {
              try {
                window.GaiusQuality.runtime.onContext(window.__gaiusWebGL);
              } catch (ignored) {
                // The runtime reports its own failures; the context stays usable.
              }
            }
            return !!window.__gaiusWebGL;
            """)
    static native boolean createContext();

    @JSBody(params = {"width", "height"}, script = """
            const host = window.__gaiusCanvasHost;
            const canvasElement = host ? host.current() : null;
            if (!canvasElement) return;
            if (window.__gaiusApplyCanvasResolution) {
              window.__gaiusApplyCanvasResolution(width, height, false);
            } else {
              const quality = window.GaiusQuality ? window.GaiusQuality.runtime : null;
              const pixelRatio = quality && typeof quality.resolvePixelRatio === 'function'
                ? (Number(quality.resolvePixelRatio(devicePixelRatio || 1)) || 1)
                : Math.min(devicePixelRatio || 1, 1);
              canvasElement.style.width = width + 'px'; canvasElement.style.height = height + 'px';
              canvasElement.width = Math.max(1, Math.round(width * pixelRatio));
              canvasElement.height = Math.max(1, Math.round(height * pixelRatio));
            }
            """)
    static native void resizeCanvas(int width, int height);

    @JSBody(params = {"title"}, script = "document.title = title;")
    static native void setTitle(String title);

    @JSBody(script = """
            const display = window.__gaiusDisplay;
            if (display && display.cssWidth > 0) return display.cssWidth | 0;
            const host = window.__gaiusCanvasHost;
            const canvasElement = host ? host.current() : null;
            return canvasElement ? Math.round(canvasElement.getBoundingClientRect().width) : (innerWidth | 0);
            """)
    static native int canvasCssWidth();

    @JSBody(script = """
            const display = window.__gaiusDisplay;
            if (display && display.cssHeight > 0) return display.cssHeight | 0;
            const host = window.__gaiusCanvasHost;
            const canvasElement = host ? host.current() : null;
            return canvasElement ? Math.round(canvasElement.getBoundingClientRect().height) : (innerHeight | 0);
            """)
    static native int canvasCssHeight();

    @JSBody(script = "const host = window.__gaiusCanvasHost; const canvasElement = host ? host.current() : null; return canvasElement ? canvasElement.width : innerWidth;")
    static native int framebufferWidth();

    @JSBody(script = "const host = window.__gaiusCanvasHost; const canvasElement = host ? host.current() : null; return canvasElement ? canvasElement.height : innerHeight;")
    static native int framebufferHeight();

    @JSBody(script = "return screen.width | 0;")
    static native int screenWidth();

    @JSBody(script = "return screen.height | 0;")
    static native int screenHeight();

    @JSBody(script = "return window.__gaiusResolvePixelRatio ? window.__gaiusResolvePixelRatio() : (devicePixelRatio || 1);")
    static native double devicePixelRatio();

    // ---------------------------------------------------------------- misc

    @JSBody(script = "return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();")
    static native double nowMillis();

    @JSBody(params = {"value"}, script = """
            const bridge = window.__gaiusClipboardBridge;
            if (bridge) {
              bridge.write(value);
              return;
            }
            window.__gaiusClipboard = value;
            try {
              if (navigator.clipboard && navigator.clipboard.writeText) {
                const result = navigator.clipboard.writeText(value);
                if (result && result.catch) result.catch(() => {});
              }
            } catch (ignored) {
              // The in-page clipboard above still round-trips inside the game.
            }
            """)
    static native void writeClipboard(String value);

    @JSBody(script = "return window.__gaiusClipboard == null ? '' : String(window.__gaiusClipboard);")
    static native String readClipboard();

    @JSBody(params = {"url"}, script = """
            const lower = String(url).toLowerCase();
            if (lower.indexOf('https:') !== 0 && lower.indexOf('http:') !== 0) return false;
            try {
              // With noopener the return value is always null, so a blocked popup
              // cannot be told apart from an opened one.
              window.open(url, '_blank', 'noopener,noreferrer');
              return true;
            } catch (ignored) {
              return false;
            }
            """)
    static native boolean openUrl(String url);

    @JSBody(params = {"message"}, script = "if (typeof console !== 'undefined') console.error(message);")
    static native void consoleError(String message);

    // ---------------------------------------------------------------- frame pacing
    // Copied from BrowserGlfw.swapBuffersJs / yieldAfterPresent / scheduleFrameYield.

    @JSBody(script = """
            const hidden=document.visibilityState!=='visible';
            const fps=window.__gaiusFps || (window.__gaiusFps={});
            const telemetry=window.__gaiusFrameTelemetry;
            let now;
            if (telemetry && telemetry.enabled) {
              now=(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
              if (!Number.isFinite(telemetry.startedAt)) telemetry.startedAt=now;
              const previous=telemetry.lastFrameAt;
              if (Number.isFinite(previous) && previous > 0) {
                const frameElapsed=Math.max(0, now-previous);
                const bucket=Math.min(4000, Math.floor(frameElapsed*4));
                let histogram=telemetry.histogram;
                if (!histogram || histogram.length !== 4001) {
                  histogram=new Uint32Array(4001);
                  telemetry.histogram=histogram;
                }
                histogram[bucket]=histogram[bucket]+1;
                telemetry.frameCount=(telemetry.frameCount||0)+1;
                telemetry.totalFrameMillis=(telemetry.totalFrameMillis||0)+frameElapsed;
                telemetry.longestFrameMillis=Math.max(telemetry.longestFrameMillis||0, frameElapsed);
                if (frameElapsed >= 500) {
                  telemetry.freezeCount=(telemetry.freezeCount||0)+1;
                }
                let samples=telemetry.frameTimes;
                if (!(samples instanceof Float32Array)) {
                  const requested=Number(telemetry.sampleCapacity);
                  const capacity=Number.isFinite(requested)
                    ? Math.max(1024, Math.min(65536, Math.floor(requested)))
                    : 65536;
                  samples=new Float32Array(capacity);
                  telemetry.frameTimes=samples;
                  telemetry.sampleCapacity=capacity;
                  telemetry.sampleWriteIndex=0;
                  telemetry.sampleCount=0;
                }
                const writeIndex=(Number(telemetry.sampleWriteIndex)||0)%samples.length;
                samples[writeIndex]=frameElapsed;
                telemetry.sampleWriteIndex=(writeIndex+1)%samples.length;
                telemetry.sampleCount=Math.min(
                  samples.length,
                  (Number(telemetry.sampleCount)||0)+1
                );
              }
              if (hidden) telemetry.hiddenFrameCount=(telemetry.hiddenFrameCount||0)+1;
              else telemetry.visibleFrameCount=(telemetry.visibleFrameCount||0)+1;
              telemetry.lastFrameAt=now;
            }
            fps.gameFrames=(fps.gameFrames||0)+1;
            fps.gameSampleCounter=((fps.gameSampleCounter||0)+1)&15;
            if (fps.gameSampleCounter !== 0 && fps.gameLastSampleAt) {
              if (hidden) {
                window.__gaiusBackgroundFrameThrottles=(window.__gaiusBackgroundFrameThrottles||0)+1;
              }
              return hidden;
            }
            if (now === undefined) {
              now=(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
            }
            if (!fps.gameLastSampleAt) fps.gameLastSampleAt=now;
            const elapsed=now-fps.gameLastSampleAt;
            if (elapsed >= 1000) {
              fps.gameFps=Math.round((fps.gameFrames*1000/elapsed)*10)/10;
              fps.gameFrames=0;
              fps.gameLastSampleAt=now;
            }
            if (hidden) {
              window.__gaiusBackgroundFrameThrottles=(window.__gaiusBackgroundFrameThrottles||0)+1;
            }
            return hidden;
            """)
    private static native boolean swapBuffersJs();

    /**
     * The per-frame yield after a present. The WebAssembly GC build (HostLoop.jspi(),
     * constant-folded) does not yield here: the present only records its swap interval, and the
     * patched Minecraft.run waits for the next frame at the head of its loop
     * (HostLoop.awaitAnimationFrame, design D4) with the pacing of scheduleFrameYield
     * (gaius-jspi.js), so the browser gets its turn between two frames and never inside one.
     *
     * <p>A frame presented from a JavaScript callback cannot suspend: the login packet runs
     * ClientPacketListener.handleLogin -> startWaitingForNewLevel -> Minecraft.setScreenAndShow
     * -> renderFrame inside the network inbound pump's MessageChannel callback whenever that
     * callback, not the frame-boundary pump of Minecraft.runTick, decodes it. TeaVM refuses that
     * suspension ("Suspension point reached from non-threading context") before it schedules
     * anything, so the frame skips its yield and the next regular frame yields as usual (the same
     * rule as BrowserGlfw.swapBuffers on the GLFW profiles). Letting the refusal propagate is
     * fatal here: it leaves the renderpearl surface acquired, every later Minecraft.renderFrame
     * returns before presenting, so the JavaScript build's main loop never yields again and the
     * page freezes; the packet pipeline also reports it and the client disconnects. Any other
     * failure still propagates.</p>
     */
    private static void presentYield(boolean hidden, int interval) {
        if (HostLoop.jspi()) {
            HostLoop.framePresented(hidden, interval);
            return;
        }
        try {
            yieldAfterPresent(hidden, interval);
        } catch (RuntimeException e) {
            if (!isNonThreadingSuspension(e)) {
                throw e;
            }
            recordSkippedPresentYield();
        }
    }

    @Async
    private static native void yieldAfterPresent(boolean hidden, int interval);

    private static void yieldAfterPresent(boolean hidden, int interval, AsyncCallback<Void> callback) {
        scheduleFrameYield(hidden, interval, () -> callback.complete(null));
    }

    @JSBody(params = {"hidden", "interval", "resume"}, script = """
            const root=typeof window!=='undefined' ? window : globalThis;
            const telemetry=root.__gaiusFrameTelemetry;
            const telemetryEnabled=!!(telemetry && telemetry.enabled);
            const synchronizedToDisplay=Number(interval)!==0;
            const clock=() => (typeof performance!=='undefined' && performance.now)
              ? performance.now()
              : Date.now();
            const requestedAt=telemetryEnabled ? clock() : 0;
            if (telemetryEnabled) {
              telemetry.yieldRequestCount=(telemetry.yieldRequestCount||0)+1;
              telemetry.pendingYieldCount=Math.max(
                0,Number(telemetry.pendingYieldCount)||0)+1;
              telemetry.maxPendingYieldCount=Math.max(
                Number(telemetry.maxPendingYieldCount)||0,
                telemetry.pendingYieldCount);
              telemetry.duplicateYieldCallbackCount=
                Number(telemetry.duplicateYieldCallbackCount)||0;
              telemetry.swapInterval=Number(interval)||0;
              if (synchronizedToDisplay) {
                telemetry.vsyncYieldCount=(telemetry.vsyncYieldCount||0)+1;
              } else {
                telemetry.uncappedYieldCount=(telemetry.uncappedYieldCount||0)+1;
              }
              if (hidden) telemetry.hiddenYieldCount=(telemetry.hiddenYieldCount||0)+1;
              else telemetry.visibleYieldCount=(telemetry.visibleYieldCount||0)+1;
            }
            let resumed=false;
            let watchdog=-1;
            let activeMessageScheduler=null;
            let activeMessageTaskId=0;
            const retireMessageChannel=scheduler => {
              const channel=scheduler && scheduler.channel;
              if (!channel) return;
              try { channel.port1.onmessage=null; } catch (ignored) {}
              try { if (channel.port1.close) channel.port1.close(); } catch (ignored) {}
              try { if (channel.port2.close) channel.port2.close(); } catch (ignored) {}
              scheduler.channel=null;
              if (telemetryEnabled) {
                telemetry.messageChannelRebuildCount=
                  (Number(telemetry.messageChannelRebuildCount)||0)+1;
              }
            };
            const detachMessageTask=failed => {
              const scheduler=activeMessageScheduler;
              const taskId=activeMessageTaskId;
              activeMessageScheduler=null;
              activeMessageTaskId=0;
              if (!scheduler || !taskId || !(scheduler.tasks instanceof Map)) return;
              const removed=scheduler.tasks.delete(taskId);
              if (removed && telemetryEnabled) {
                telemetry.cancelledMessageTaskCount=
                  (Number(telemetry.cancelledMessageTaskCount)||0)+1;
              }
              if (failed) retireMessageChannel(scheduler);
            };
            const finish=source => {
              if (resumed) {
                if (telemetryEnabled) {
                  telemetry.duplicateYieldCallbackCount=
                    (Number(telemetry.duplicateYieldCallbackCount)||0)+1;
                }
                return;
              }
              resumed=true;
              if (watchdog >= 0) clearTimeout(watchdog);
              if (source==='message') {
                activeMessageScheduler=null;
                activeMessageTaskId=0;
              } else {
                detachMessageTask(source==='watchdog');
              }
              if (telemetryEnabled) {
                telemetry.pendingYieldCount=Math.max(
                  0,(Number(telemetry.pendingYieldCount)||0)-1);
                const delay=Math.max(0, clock()-requestedAt);
                telemetry.yieldCompletionCount=(telemetry.yieldCompletionCount||0)+1;
                telemetry.lastYieldResumeDelayMillis=delay;
                telemetry.totalYieldResumeDelayMillis=(telemetry.totalYieldResumeDelayMillis||0)+delay;
                telemetry.longestYieldResumeDelayMillis=Math.max(
                  telemetry.longestYieldResumeDelayMillis||0,
                  delay
                );
                if (source==='message') {
                  telemetry.messageChannelYieldCount=(telemetry.messageChannelYieldCount||0)+1;
                } else if (source==='scheduler') {
                  telemetry.schedulerYieldCount=(telemetry.schedulerYieldCount||0)+1;
                } else {
                  telemetry.timerYieldCount=(telemetry.timerYieldCount||0)+1;
                  if (source==='watchdog') {
                    telemetry.watchdogYieldCount=(telemetry.watchdogYieldCount||0)+1;
                  }
                }
              }
              resume();
            };
            const postTask=() => {
              let scheduler=root.__gaiusFrameYieldScheduler;
              if (!scheduler || !(scheduler.tasks instanceof Map)) {
                if (scheduler && scheduler.channel) retireMessageChannel(scheduler);
                scheduler={tasks:new Map(),channel:null,nextTaskId:1};
                root.__gaiusFrameYieldScheduler=scheduler;
              }
              if (!scheduler.channel) {
                if (typeof MessageChannel==='function') {
                  try {
                    const channel=new MessageChannel();
                    channel.port1.onmessage=event => {
                      const taskId=Number(event && event.data)||0;
                      const task=scheduler.tasks.get(taskId);
                      if (!task) return;
                      scheduler.tasks.delete(taskId);
                      task();
                    };
                    scheduler.channel=channel;
                  } catch (ignored) {
                    if (telemetryEnabled) {
                      telemetry.messageChannelCreateFailureCount=
                        (Number(telemetry.messageChannelCreateFailureCount)||0)+1;
                    }
                  }
                }
              }
              if (scheduler.channel) {
                let taskId=(Number(scheduler.nextTaskId)||1)>>>0;
                if (taskId===0) taskId=1;
                scheduler.nextTaskId=(taskId+1)>>>0;
                activeMessageScheduler=scheduler;
                activeMessageTaskId=taskId;
                scheduler.tasks.set(taskId,() => finish('message'));
                try {
                  scheduler.channel.port2.postMessage(taskId);
                } catch (ignored) {
                  detachMessageTask(false);
                  retireMessageChannel(scheduler);
                  if (telemetryEnabled) {
                    telemetry.messageChannelPostFailureCount=
                      (Number(telemetry.messageChannelPostFailureCount)||0)+1;
                  }
                  setTimeout(() => finish('timer'), 0);
                }
              } else {
                setTimeout(() => finish('timer'), 0);
              }
            };
            if (hidden) {
              setTimeout(() => finish('timer'), 50);
            } else if (synchronizedToDisplay && typeof requestAnimationFrame==='function') {
              watchdog=setTimeout(() => finish('watchdog'), 100);
              requestAnimationFrame(() => {
                if (resumed) return;
                if (telemetryEnabled) {
                  const delay=Math.max(0, clock()-requestedAt);
                  telemetry.presentToRafCount=(telemetry.presentToRafCount||0)+1;
                  telemetry.lastPresentToRafMillis=delay;
                  telemetry.totalPresentToRafMillis=(telemetry.totalPresentToRafMillis||0)+delay;
                  telemetry.longestPresentToRafMillis=Math.max(
                    telemetry.longestPresentToRafMillis||0,
                    delay
                  );
                }
                postTask();
              });
            } else {
              watchdog=setTimeout(() => finish('watchdog'), 100);
              // Keep every visible uncapped present on the same MessageChannel task path.
              // Mixing scheduler.yield() into every fourth present makes Chromium defer that
              // continuation behind compositor arbitration, producing a stable 3:1 cadence
              // and periodic multi-refresh frame bubbles on high-refresh displays.
              // Menus (no level): the title screen draws a frame in about 1.5 ms, so uncapped
              // presents ran at hundreds of FPS back to back and starved the compositor (the
              // page showed 5-14 FPS while the game presented 650). In menus each present waits
              // for the next rAF, the vanilla menu limit. In a world presents stay uncapped:
              // BrowserRenderScheduler compiles and uploads terrain sections on a per-frame
              // budget, so holding world frames would slow chunk rendering.
              const state=root.__gaiusMinecraftState;
              if (state && state.level || typeof requestAnimationFrame!=='function') {
                postTask();
              } else {
                let pacer=root.__gaiusUncappedPresentPacer;
                if (!pacer || !Array.isArray(pacer.waiters)) {
                  pacer={rafPending:false,waiters:[]};
                  root.__gaiusUncappedPresentPacer=pacer;
                }
                if (telemetryEnabled) {
                  telemetry.uncappedRafWaitCount=(Number(telemetry.uncappedRafWaitCount)||0)+1;
                }
                pacer.waiters.push(() => {
                  if (!resumed) postTask();
                });
                if (!pacer.rafPending) {
                  pacer.rafPending=true;
                  requestAnimationFrame(() => {
                    pacer.rafPending=false;
                    const waiters=pacer.waiters;
                    pacer.waiters=[];
                    for (let index=0; index<waiters.length; index++) waiters[index]();
                  });
                }
              }
            }
            """)
    private static native void scheduleFrameYield(boolean hidden, int interval, FrameYieldCallback resume);

    /**
     * SDL_GL_SwapWindow body: present bookkeeping, then the per-frame cooperative
     * yield (frame source of performance-contract.json on 26.3).
     */
    static void swapWindow(int swapInterval) {
        presentCount++;
        boolean hidden = swapBuffersJs();
        presentYield(hidden, swapInterval);
    }

    // Declared after the existing methods so that TeaVM's per-class names of the existing
    // lambdas and JSBody functions stay as they were in the JavaScript output.

    /** True for TeaVM's refusal to suspend outside a TeaVM thread (see {@link #presentYield}). */
    static boolean isNonThreadingSuspension(Throwable error) {
        String message = error.getMessage();
        return message != null && message.contains("Suspension point reached from non-threading context");
    }

    /** Counts presents whose yield was skipped because they ran from a JavaScript callback. */
    @JSBody(script = """
            globalThis.__gaiusSkippedPresentYields=(Number(globalThis.__gaiusSkippedPresentYields)||0)+1;
            """)
    private static native void recordSkippedPresentYield();

    /** Presents of every thread so far; compared at each game-loop boundary. */
    private static int presentCount;
    private static int presentCountAtLoopBoundary;

    /**
     * Game-loop boundary of the JavaScript build: the first SDL_PollEvent of each
     * SDLEventHandler.pollEvents cycle, i.e. once per Minecraft.run iteration.
     *
     * <p>The loop's only yields to the browser are in Minecraft.renderFrame (the present yield and
     * FramerateLimiter), and vanilla renders nothing while its GpuSurface is acquired. Another
     * TeaVM thread can hold that acquisition across its own present yield: the singleplayer
     * ready poll (a HostLoop.startThread thread) runs ConnectScreen.startConnecting ->
     * Minecraft.disconnect -> setScreenAndShow -> renderFrame. When the main loop resumes first
     * (from FramerateLimiter's park), every renderFrame returns at once, the loop never yields,
     * the other thread is never resumed to finish its present, and the page freezes. So when no
     * frame was presented since the previous boundary, the loop yields one event-loop turn here.
     * A loop that presents (every normal frame) never reaches the yield. The WebAssembly GC build
     * waits for the next frame at the head of Minecraft.run instead (HostLoop.awaitAnimationFrame);
     * class initializers, synchronously compiled methods and JavaScript callbacks skip the yield.</p>
     */
    static void loopBoundary() {
        if (HostLoop.jspi()) {
            return;
        }
        if (presentCount != presentCountAtLoopBoundary) {
            presentCountAtLoopBoundary = presentCount;
            return;
        }
        if (HostLoop.inNoSuspendFrame()) {
            return;
        }
        try {
            yieldLoopTurn();
        } catch (RuntimeException e) {
            if (!isNonThreadingSuspension(e)) {
                throw e;
            }
            return;
        }
        recordLoopBoundaryYield();
    }

    @Async
    private static native void yieldLoopTurn();

    private static void yieldLoopTurn(AsyncCallback<Void> callback) {
        scheduleLoopTurn(() -> callback.complete(null));
    }

    /** One timer turn (nested timers clamp to 4 ms, so a loop that keeps skipping stays cheap). */
    @JSBody(params = "resume", script = "setTimeout(resume, 0);")
    private static native void scheduleLoopTurn(FrameYieldCallback resume);

    /** Counts game-loop iterations that presented nothing and yielded at the boundary instead. */
    @JSBody(script = """
            globalThis.__gaiusLoopBoundaryYields=(Number(globalThis.__gaiusLoopBoundaryYields)||0)+1;
            """)
    private static native void recordLoopBoundaryYield();

    @JSFunctor
    interface FrameYieldCallback extends JSObject {
        void run();
    }
}
