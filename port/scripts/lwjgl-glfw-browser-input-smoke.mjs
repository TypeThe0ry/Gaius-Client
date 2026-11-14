#!/usr/bin/env node
// Execute the complete production GLFW JSBody installer, listeners, cursor-mode change,
// and native poll accessors. No duplicated queue algorithm or browser is used here.
// GAIUS_GLFW_DOM_SOURCE may select a fixed parent/mutant for regression checks.
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";
import test from "node:test";
const source = await readFile(process.env.GAIUS_GLFW_DOM_SOURCE || new URL(
  "../overrides/libraries/lwjgl-glfw/src/main/java/org/lwjgl/glfw/BrowserGlfw.java", import.meta.url), "utf8");
class Target {
  constructor() { this.listeners = new Map(); }
  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }
  removeEventListener() {}
  dispatch(type, init = {}) {
    const event = {
      type, defaultPrevented: false, repeat: false, shiftKey: false, ctrlKey: false, altKey: false,
      metaKey: false, getModifierState: () => false, preventDefault() { this.defaultPrevented = true; },
      ...init,
    };
    for (const listener of this.listeners.get(type) || []) listener(event);
    return event;
  }
}

function createEnv() {
  let now = 1000;
  let timers = [];
  const doc = new Target();
  const win = new Target();
  const canvas = new Target();
  Object.assign(canvas, {
    id: "mc-canvas", tagName: "CANVAS", width: 1280, height: 720, style: {}, lockRequests: 0,
    focus() { doc.activeElement = canvas; },
    getBoundingClientRect: () => ({left: 0, top: 0, width: 1280, height: 720}),
    requestPointerLock() {
      canvas.lockRequests++;
      if (env.refuseLock) return Promise.reject(new Error("The user has exited the lock"));
      doc.pointerLockElement = canvas;
      doc.dispatch("pointerlockchange");
      return Promise.resolve();
    },
    getAttribute: () => null,
  });
  Object.assign(doc, {
    activeElement: null, pointerLockElement: null, fullscreenElement: null, visibilityState: "visible",
    focused: true, title: "",
    hasFocus() { return doc.focused; },
    getElementById: id => (id === "mc-canvas" ? canvas : null),
    createElement: tag => Object.assign(new Target(), {tagName: tag, style: {}}),
    body: {appendChild() {}},
    exitPointerLock() {
      doc.pointerLockElement = null;
      doc.dispatch("pointerlockchange");
    },
    exitFullscreen() { doc.fullscreenElement = null; return Promise.resolve(); },
    documentElement: {
      requestFullscreen() {
        doc.fullscreenElement = doc.documentElement;
        return Promise.resolve();
      },
    },
  });
  const keyboardLocks = [];
  const sandbox = {
    console, URLSearchParams, Promise, Object, Array, Map, Math, Number, String, Boolean, JSON, Date,
    Uint8Array, Uint32Array, Int32Array, Float32Array, Float64Array, Error, Infinity,
    document: doc,
    navigator: {keyboard: {lock(keys) { keyboardLocks.push(keys); return Promise.resolve(); }, unlock() {}}},
    location: {search: ""},
    innerWidth: 1280, innerHeight: 720, devicePixelRatio: 1, screen: {width: 1920, height: 1080},
    performance: {now: () => now},
    setTimeout(fn, delay) { timers.push({at: now + (Number(delay) || 0), fn}); return timers.length; },
    clearTimeout() {},
    addEventListener: win.addEventListener.bind(win),
    removeEventListener() {},
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  const env = {
    sandbox, doc, win, canvas, keyboardLocks, refuseLock: false,
    run(script) { return vm.runInContext("(function(){" + script + "\n})", sandbox)(); },
    advance(ms) {
      now += ms;
      const due = timers.filter(timer => timer.at <= now);
      timers = timers.filter(timer => timer.at > now);
      for (const timer of due) timer.fn();
    },
    async settle() { for (let i = 0; i < 4; i++) await Promise.resolve(); },
  };
  return env;
}

function body(name, params = []) {
  const marker = new RegExp(`(?:private|public) static native [^;]+ ${name}\\(`).exec(source);
  assert.ok(marker, `missing native method ${name}`);
  const at = source.lastIndexOf('@JSBody(', marker.index);
  const start = source.indexOf('script = ', at) + 'script = '.length;
  let script;
  if (source.startsWith('"""', start)) script = source.slice(start + 3, source.indexOf('"""', start + 3));
  else {
    let end = start + 1;
    while (source[end] !== '"' || source[end - 1] === '\\') end++;
    script = JSON.parse(source.slice(start, end + 1));
  }
  return {params, script};
}
function bridge() {
  const env = createEnv(), w = env.sandbox;
  const call = (name, args = [], params = []) => {
    const b = body(name, params);
    return vm.runInContext(`(function(${params.join(',')}){${b.script}\n})`, w)(...args);
  };
  call('installDomBridge');
  const drain = (limit = Infinity) => {
    const rows = [];
    while (call('eventCount') > 0 && rows.length < limit) {
      const row = [];
      for (let i = 0; i < 7; i++) row.push(call(i < 5 ? 'eventInt' : 'eventDouble', [i], ['index']));
      call('removeEvent');
      rows.push(row);
    }
    return rows;
  };
  const send = (type, init = {}) => env.win.dispatch(type, {
    target: env.canvas, button: 0, clientX: w.__gaiusCursorX || 0, clientY: w.__gaiusCursorY || 0,
    movementX: 0, movementY: 0, ...init,
  });
  return {env, w, call, drain, send,
    move(x, y) { send('mousemove', {clientX: x, clientY: y}); },
    grab() { call('setInputModeJs', [0x33001, 0x34003], ['mode', 'value']); },
    lock() { env.doc.pointerLockElement = env.canvas; env.doc.dispatch('pointerlockchange'); },
  };
}
const motion = (x, y) => [4, 0, 0, 0, 0, x, y];
const button = (pressed, x, y) => [3, 0, pressed ? 1 : 0, 0, 0, x, y];

test('motion, press, motion, release retain exact position and button order', () => {
  const b = bridge();
  b.move(10, 20); b.send('mousedown'); b.move(30, 40); b.send('mouseup');
  assert.deepEqual(b.drain(), [motion(10, 20), button(true, 10, 20), motion(30, 40), button(false, 30, 40)]);
});
test('only adjacent unpolled motion coalesces to the latest absolute position', () => {
  const b = bridge(); b.move(10, 20); b.move(30, 40);
  assert.deepEqual(b.drain(), [motion(30, 40)]);
});
test('key press and release are barriers to motion coalescing', () => {
  const b = bridge();
  b.move(10, 20); b.send('keydown', {code: 'KeyW', key: 'w', keyCode: 87});
  b.move(30, 40); b.send('keyup', {code: 'KeyW', key: 'w', keyCode: 87});
  assert.deepEqual(b.drain(), [motion(10, 20), [1, 87, 87, 1, 0, 0, 0], [2, 119, 0, 0, 0, 0, 0], motion(30, 40), [1, 87, 87, 0, 0, 0, 0]]);
});
test('wheel and focus records also retain their original place between motions', () => {
  const b = bridge(); b.move(10, 20); b.send('wheel', {deltaX: 100, deltaY: -200}); b.move(30, 40);
  assert.deepEqual(b.drain(), [motion(10, 20), [5, 0, 0, 0, 0, -1, 2], motion(30, 40)]);
  b.move(50, 60); b.send('blur'); b.move(70, 80);
  assert.deepEqual(b.drain(), [motion(50, 60), [6, 0, 0, 0, 0, 0, 0], motion(70, 80)]);
});
test('actual pointer-lock acquisition is a barrier even without a queued record', () => {
  const b = bridge(); b.move(10, 20); b.lock();
  b.send('mousemove', {movementX: 5, movementY: -2});
  assert.deepEqual(b.drain(), [motion(10, 20), motion(15, 18)]);
});
test('cursor-mode transition is a barrier before the actual pointer lock arrives', () => {
  const b = bridge(); b.move(10, 20); b.grab(); b.move(30, 40);
  assert.deepEqual(b.drain(), [motion(10, 20), motion(30, 40)]);
});
test('stationary unlocked buttons do not manufacture cursor callbacks', () => {
  const b = bridge(); b.send('mousedown'); b.send('mouseup');
  assert.deepEqual(b.drain(), [button(true, 0, 0), button(false, 0, 0)]);
});
test('initial grab and stationary locked buttons leave the first real move queued', () => {
  const b = bridge(); b.w.__gaiusCursorX = 400; b.w.__gaiusCursorY = 300;
  b.grab(); b.lock(); b.send('mousedown'); b.send('mouseup');
  assert.deepEqual(b.drain(), [button(true, 400, 300), button(false, 400, 300)]);
  b.send('mousemove', {movementX: 5, movementY: -3});
  assert.deepEqual(b.drain(), [motion(405, 297)]);
  b.send('mousemove', {movementX: 7, movementY: 2});
  assert.deepEqual(b.drain(), [motion(412, 299)]);
});
test('real changed button coordinates still deliver motion before the button', () => {
  const b = bridge(); b.send('mousedown', {clientX: 40, clientY: 50});
  b.send('mouseup', {clientX: 42, clientY: 55});
  assert.deepEqual(b.drain(), [motion(40, 50), button(true, 40, 50), motion(42, 55), button(false, 42, 55)]);
});
test('nonzero relative movement on a locked button is retained', () => {
  const b = bridge(); b.lock(); b.send('mousedown', {movementX: 4, movementY: -2});
  b.send('mouseup', {movementX: 3, movementY: 5});
  assert.deepEqual(b.drain(), [motion(4, -2), button(true, 4, -2), motion(7, 3), button(false, 7, 3)]);
});
test('two polls are empty after draining; later motion cannot replace a consumed record', () => {
  const b = bridge(); b.move(1, 2);
  assert.deepEqual(b.drain(), [motion(1, 2)]); assert.deepEqual(b.drain(), []);
  assert.equal(b.w.__gaiusGlfwPendingMouseMove, -1);
  b.move(3, 4); assert.deepEqual(b.drain(), [motion(3, 4)]); assert.deepEqual(b.drain(), []);
});
test('polling a prefix leaves button barriers and pending tail intact', () => {
  const b = bridge(); b.move(1, 2); b.send('mousedown'); b.move(3, 4);
  assert.deepEqual(b.drain(1), [motion(1, 2)]);
  b.move(5, 6); b.send('mouseup');
  assert.deepEqual(b.drain(), [button(true, 1, 2), motion(5, 6), button(false, 5, 6)]);
});
test('queue compaction preserves all buttons and rebases only the pending tail', () => {
  const b = bridge(), expected = [];
  for (let i = 0; i < 140; i++) { b.send('mousedown'); b.send('mouseup'); expected.push(button(true, 0, 0), button(false, 0, 0)); }
  b.move(1, 2);
  const first = b.drain(150);
  assert.ok(b.w.__gaiusGlfwEventHead < 150, 'production removeEvent compacted its consumed prefix');
  b.move(3, 4);
  assert.deepEqual([...first, ...b.drain()], [...expected, motion(3, 4)]);
  assert.deepEqual(b.drain(), []);
});
test('touch injection shares key and button barriers without changing its coordinates', () => {
  const b = bridge();
  b.w.__gaiusInput.pointer(10, 20); b.w.__gaiusInput.button(0, true);
  b.w.__gaiusInput.pointer(30, 40); b.w.__gaiusInput.button(0, false);
  assert.deepEqual(b.drain(), [motion(10, 20), button(true, 10, 20), motion(30, 40), button(false, 30, 40)]);
});
test('bridge installation is idempotent and a new bridge starts with an empty queue', () => {
  const b = bridge(); b.move(1, 2); b.call('installDomBridge');
  assert.deepEqual(b.drain(), [motion(1, 2)]); assert.deepEqual(bridge().drain(), []);
});
test('zero-delta mousemove is still a real cursor event, unlike a stationary button', () => {
  const b = bridge(); b.w.__gaiusCursorX = 400; b.w.__gaiusCursorY = 300;
  b.grab(); b.lock(); b.send('mousemove');
  assert.deepEqual(b.drain(), [motion(400, 300)]);
});
test('pointer-lock release also separates adjacent motion from different cursor states', () => {
  const b = bridge(); b.lock(); b.send('mousemove', {movementX: 5, movementY: 7});
  b.env.doc.pointerLockElement = null; b.env.doc.dispatch('pointerlockchange'); b.move(30, 40);
  assert.deepEqual(b.drain(), [motion(5, 7), motion(30, 40)]);
});
test('first real locked move before the first button poll stays after the press', () => {
  const b = bridge(); b.w.__gaiusCursorX = 400; b.w.__gaiusCursorY = 300;
  b.grab(); b.lock(); b.send('mousedown');
  b.send('mousemove', {movementX: 5, movementY: -3}); b.send('mouseup');
  assert.deepEqual(b.drain(), [button(true, 400, 300), motion(405, 297), button(false, 405, 297)]);
  assert.deepEqual(b.drain(), []);
});
