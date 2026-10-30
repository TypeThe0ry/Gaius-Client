package dev.gaius.browser;

import org.teavm.jso.JSBody;

/**
 * System clipboard bridge shared by the browser GLFW and SDL3 shims
 * ({@code window.__gaiusClipboardBridge}).
 *
 * <p>The game reads the clipboard synchronously ({@code glfwGetClipboardString},
 * {@code SDL_GetClipboardText}) while the browser only hands it out asynchronously, so the
 * bridge keeps the last known text in {@code window.__gaiusClipboard} and refreshes it on
 * every paste chord before the game sees the key:
 *
 * <ul>
 *   <li>Ctrl/Cmd+V and Shift+Insert keep their browser default, so the {@code paste} event
 *       carries the system text. The key records of the shims go through {@code deliver(fn)},
 *       which holds them until that event (or the fallback below) has updated the text.</li>
 *   <li>When no {@code paste} event follows the chord (a browser that fires it only on
 *       editable elements), {@code navigator.clipboard.readText()} is asked instead, with a
 *       timeout so a pending permission prompt cannot hold the keyboard for long.</li>
 *   <li>Writes use {@code navigator.clipboard.writeText}, then a {@code copy} event driven by
 *       {@code document.execCommand('copy')} (non-secure origins, a refused promise). WebKit
 *       only writes during the key gesture, so there a copy chord (Ctrl/Cmd+C or X, F3+C or
 *       F3+I) starts a {@code ClipboardItem} write whose text the game supplies when it
 *       copies.</li>
 *   <li>Apple platforms: the game runs with the non-macOS shortcut quirks (os.name is not
 *       Mac OS X), so Cmd+A/C/V/X are reported to it as Ctrl chords ({@code appleEditChord}).
 *       macOS does not deliver the keyup of a key pressed with Cmd held, so the shims send
 *       those chords as a press immediately followed by a release.</li>
 * </ul>
 */
public final class BrowserClipboard {
    private BrowserClipboard() {
    }

    /** Installs {@code window.__gaiusClipboardBridge} once; later calls do nothing. */
    @JSBody(script = """
            const w = window;
            if (w.__gaiusClipboardBridge) return;
            const nav = typeof navigator !== 'undefined' ? navigator : null;
            const doc = typeof document !== 'undefined' ? document : null;
            const ua = String((nav && nav.userAgent) || '');
            const platform = String((nav && ((nav.userAgentData && nav.userAgentData.platform) || nav.platform)) || '');
            const apple = /mac|iphone|ipad|ipod/i.test(platform);
            // Safari and every iOS browser: clipboard writes only inside the user gesture.
            const webkitGesture = /applewebkit/i.test(ua) && (!/chrome|chromium|edg|opr|firefox/i.test(ua) || /iphone|ipad|ipod/i.test(ua));
            const later = (fn, ms) => {
              if (typeof setTimeout === 'function') return setTimeout(fn, ms);
              fn();
              return 0;
            };
            const cancel = id => {
              if (id && typeof clearTimeout === 'function') clearTimeout(id);
            };
            const canvas = () => {
              const host = w.__gaiusCanvasHost;
              if (host && typeof host.current === 'function') return host.current();
              return doc && doc.getElementById ? doc.getElementById('mc-canvas') : null;
            };
            const clipboard = () => (nav && nav.clipboard) || null;
            const b = {
              apple: apple,
              webkitGesture: webkitGesture,
              pending: null,
              held: [],
              armed: null,
              f3: false,
              stats: {writes: 0, asyncWrites: 0, gestureWrites: 0, legacyWrites: 0, pasteEvents: 0, readTextFallbacks: 0, heldRecords: 0}
            };
            if (w.__gaiusClipboard == null) w.__gaiusClipboard = '';
            const setText = text => {
              w.__gaiusClipboard = String(text);
            };
            // execCommand('copy') with a one-shot copy listener: works without a selection and
            // on origins without navigator.clipboard, as long as the user gesture is recent.
            const legacyWrite = value => {
              if (!doc || typeof doc.execCommand !== 'function' || typeof doc.addEventListener !== 'function') return false;
              let written = false;
              const onCopy = e => {
                try {
                  if (e.clipboardData && e.clipboardData.setData) {
                    e.clipboardData.setData('text/plain', value);
                    written = true;
                    e.preventDefault();
                  }
                } catch (ignored) {
                  // Leave the clipboard unchanged.
                }
              };
              doc.addEventListener('copy', onCopy, true);
              try {
                doc.execCommand('copy');
              } catch (ignored) {
                written = false;
              } finally {
                doc.removeEventListener('copy', onCopy, true);
              }
              if (written) b.stats.legacyWrites++;
              return written;
            };
            const asyncWrite = value => {
              const c = clipboard();
              if (c && typeof c.writeText === 'function') {
                try {
                  const result = c.writeText(value);
                  b.stats.asyncWrites++;
                  if (result && typeof result.then === 'function') {
                    result.then(null, () => legacyWrite(value));
                  }
                  return;
                } catch (ignored) {
                  // Fall through to the copy event.
                }
              }
              legacyWrite(value);
            };
            // WebKit: start the write while the key gesture is current and resolve it with the
            // text the game copies (KeyboardHandler.setClipboard) a frame later.
            b.arm = () => {
              const c = clipboard();
              if (!webkitGesture || !c || typeof c.write !== 'function' || typeof ClipboardItem !== 'function' || typeof Blob !== 'function') return false;
              if (b.armed) b.armed.reject();
              const entry = {value: null};
              const text = new Promise((resolve, reject) => {
                entry.resolve = value => {
                  entry.value = value;
                  resolve(new Blob([value], {type: 'text/plain'}));
                };
                entry.reject = () => reject(new Error('nothing copied'));
              });
              entry.timer = later(() => {
                if (b.armed === entry) {
                  b.armed = null;
                  entry.reject();
                }
              }, 2000);
              b.armed = entry;
              try {
                const result = c.write([new ClipboardItem({'text/plain': text})]);
                if (result && typeof result.then === 'function') {
                  result.then(null, () => {
                    if (entry.value != null) asyncWrite(entry.value);
                  });
                }
              } catch (ignored) {
                if (b.armed === entry) b.armed = null;
                cancel(entry.timer);
                return false;
              }
              return true;
            };
            b.write = value => {
              const text = value == null ? '' : String(value);
              setText(text);
              b.stats.writes++;
              const entry = b.armed;
              if (entry) {
                b.armed = null;
                cancel(entry.timer);
                b.stats.gestureWrites++;
                entry.resolve(text);
                return;
              }
              asyncWrite(text);
            };
            b.read = () => (w.__gaiusClipboard == null ? '' : String(w.__gaiusClipboard));
            const release = p => {
              if (!p || b.pending !== p) return;
              b.pending = null;
              cancel(p.timer);
              const held = b.held;
              b.held = [];
              for (let i = 0; i < held.length; i++) {
                try {
                  held[i]();
                } catch (error) {
                  if (typeof console !== 'undefined' && console.error) console.error(error);
                }
              }
            };
            // Runs a key record push now, or after the pending paste chord has its text.
            b.deliver = fn => {
              if (b.pending) {
                b.held.push(fn);
                b.stats.heldRecords++;
              } else {
                fn();
              }
            };
            const readTextFallback = p => {
              if (b.pending !== p) return;
              const c = clipboard();
              if (!c || typeof c.readText !== 'function') {
                release(p);
                return;
              }
              b.stats.readTextFallbacks++;
              // A permission prompt may stay open; the key goes through with the old text then.
              p.timer = later(() => release(p), 3000);
              try {
                c.readText().then(text => {
                  if (typeof text === 'string') setText(text);
                  release(p);
                }, () => release(p));
              } catch (ignored) {
                release(p);
              }
            };
            const pasteChord = e => (((e.ctrlKey || e.metaKey) && !e.altKey && e.code === 'KeyV')
              || (e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey && e.code === 'Insert'));
            const copyChord = e => (((e.ctrlKey || e.metaKey) && !e.altKey && (e.code === 'KeyC' || e.code === 'KeyX'))
              || (b.f3 && (e.code === 'KeyC' || e.code === 'KeyI')));
            b.pasteChord = pasteChord;
            // Cmd+A/C/V/X on Apple platforms, reported to the game as Ctrl chords.
            b.appleEditChord = e => apple && !!e.metaKey && !e.ctrlKey && !e.altKey
              && (e.code === 'KeyA' || e.code === 'KeyC' || e.code === 'KeyV' || e.code === 'KeyX');
            // Called first by the keydown listeners. True when the default must stay (paste).
            b.keyDown = e => {
              if (e.code === 'F3') b.f3 = true;
              if (copyChord(e)) b.arm();
              if (!pasteChord(e)) return false;
              if (!b.pending) {
                const p = {timer: 0};
                b.pending = p;
                // The paste event is dispatched in this task, right after the keydown.
                p.timer = later(() => readTextFallback(p), 0);
              }
              return true;
            };
            b.keyUp = e => {
              if (e.code === 'F3') b.f3 = false;
            };
            if (doc && typeof doc.addEventListener === 'function') {
              doc.addEventListener('paste', e => {
                b.stats.pasteEvents++;
                try {
                  const text = e.clipboardData && e.clipboardData.getData('text/plain');
                  if (typeof text === 'string') setText(text);
                } catch (ignored) {
                  // Keep the previous text.
                }
                if (doc.activeElement && doc.activeElement === canvas()) e.preventDefault();
                release(b.pending);
              });
            }
            if (typeof addEventListener === 'function') {
              addEventListener('blur', () => {
                b.f3 = false;
              });
            }
            w.__gaiusClipboardBridge = b;
            """)
    public static native void install();
}
