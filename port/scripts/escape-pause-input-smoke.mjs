#!/usr/bin/env node
// Escape and pause-menu input contract of both browser input bridges, run on the real
// installDomBridge scripts of BrowserGlfw (1.21.11, 26.2) and BrowserSdlDom (26.3) in a
// scripted DOM:
// - Keyboard Lock (API fullscreen) locks Escape with KeyW, so a short Escape reaches the game
//   and pointer lock stays; the game's own Escape handling opens and closes the pause menu.
// - Without Keyboard Lock the browser spends Escape on ending pointer lock. The lost lock
//   reaches the game as exactly one Escape press; a key-down the browser also delivers (before
//   or after the lock change) never makes a second one, so the menu does not open and close.
// - A lock released by the game, or lost with the page, injects nothing; the next canvas
//   click asks for the lock again.
//
//   node port/scripts/escape-pause-input-smoke.mjs

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const glfwSource = await readFile(new URL(
  "../overrides/libraries/lwjgl-glfw/src/main/java/org/lwjgl/glfw/BrowserGlfw.java", import.meta.url), "utf8");
const sdlSource = await readFile(new URL(
  "../overrides/libraries/lwjgl-sdl/src/main/java/org/lwjgl/sdl/BrowserSdlDom.java", import.meta.url), "utf8");

function extractJsBody(source, signature) {
  const markerOffset = source.indexOf(signature);
  assert.ok(markerOffset > 0, `missing ${signature}`);
  const annotationOffset = source.lastIndexOf("@JSBody(", markerOffset);
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(annotationOffset > 0 && scriptEnd > scriptOffset, `JSBody could not be extracted for ${signature}`);
  return source.slice(scriptOffset, scriptEnd);
}

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

/** Normalised view of one bridge: key records as [down, isEscape] pairs. */
function glfwBridge() {
  const env = createEnv();
  env.run(extractJsBody(glfwSource, "private static native void installDomBridge();"));
  const setInputMode = extractJsBody(glfwSource, "private static native void setInputModeJs(int mode, int value);");
  const w = env.sandbox;
  return {
    name: "glfw", env,
    grab(on) {
      vm.runInContext("(function(mode,value){" + setInputMode + "\n})", w)(0x33001, on ? 0x34003 : 0x34001);
    },
    keys() {
      const events = w.__gaiusGlfwEvents.slice(w.__gaiusGlfwEventHead | 0);
      w.__gaiusGlfwEvents.length = 0;
      w.__gaiusGlfwEventHead = 0;
      w.__gaiusGlfwPendingMouseMove = -1;
      return [...events].filter(e => e[0] === 1).map(e => [e[3] !== 0, e[1] === 256]);
    },
    keyRecords() {
      const events = w.__gaiusGlfwEvents.slice(w.__gaiusGlfwEventHead | 0);
      w.__gaiusGlfwEvents.length = 0;
      w.__gaiusGlfwEventHead = 0;
      w.__gaiusGlfwPendingMouseMove = -1;
      return [...events].filter(e => e[0] === 1).map(e => [e[3] !== 0, e[1], e[4]]);
    },
    focus() {
      const events = w.__gaiusGlfwEvents.slice(w.__gaiusGlfwEventHead | 0);
      w.__gaiusGlfwEvents.length = 0;
      w.__gaiusGlfwEventHead = 0;
      w.__gaiusGlfwPendingMouseMove = -1;
      return [...events].filter(e => e[0] === 6).map(e => e[1] !== 0);
    },
  };
}

function sdlBridge() {
  const env = createEnv();
  env.run(extractJsBody(sdlSource, "static native void installDomBridge();"));
  const w = env.sandbox;
  const sdl = w.__gaiusSdl;
  return {
    name: "sdl", env,
    grab(on) { sdl.setRelative(on); },
    keys() {
      const out = [];
      while (sdl.poll()) {
        const r = sdl.current;
        if (r[0] === 768 || r[0] === 769) out.push([r[0] === 768, r[1] === 41]);
      }
      return out;
    },
    keyRecords() {
      const out = [];
      while (sdl.poll()) {
        const r = sdl.current;
        if (r[0] === 768 || r[0] === 769) out.push([r[0] === 768, r[1], r[3]]);
      }
      return out;
    },
    focus() {
      const out = [];
      while (sdl.poll()) {
        const r = sdl.current;
        if (r[0] === 526 || r[0] === 527) out.push(r[0] === 526);
      }
      return out;
    },
  };
}

const ESC_DOWN = [true, true], ESC_UP = [false, true];
const results = [];

for (const make of [glfwBridge, sdlBridge]) {
  const test = async (name, body) => {
    await body(make());
    results.push(`${make().name}: ${name}`);
  };

  await test("Keyboard Lock in fullscreen locks Escape with KeyW", async (bridge) => {
    const {env} = bridge;
    bridge.grab(true);
    env.win.dispatch("mousedown", {target: env.canvas, button: 0, clientX: 5, clientY: 5});
    await env.settle();
    assert.ok(env.doc.fullscreenElement, "a canvas click in game enters API fullscreen");
    assert.deepEqual([...env.keyboardLocks.at(-1)], ["KeyW", "Escape"]);
    assert.equal(env.doc.pointerLockElement, env.canvas);
    // A locked Escape reaches the page; pointer lock stays and the game gets one press.
    bridge.keys();
    env.win.dispatch("keydown", {code: "Escape", key: "Escape", keyCode: 27});
    env.win.dispatch("keyup", {code: "Escape", key: "Escape", keyCode: 27});
    assert.deepEqual(bridge.keys(), [ESC_DOWN, ESC_UP]);
    // The game opens its pause menu: it releases the mouse itself, nothing is injected.
    bridge.grab(false);
    env.advance(200);
    assert.deepEqual(bridge.keys(), []);
    assert.equal(env.sandbox.__gaiusEscapePauseInjected | 0, 0);
  });

  await test("Ctrl+W keeps Control held through the forward key", async (bridge) => {
    const {env} = bridge;
    env.win.dispatch("keydown", {code: "ControlLeft", key: "Control", keyCode: 17, ctrlKey: true});
    env.win.dispatch("keydown", {code: "KeyW", key: "w", keyCode: 87, ctrlKey: true});
    env.win.dispatch("keyup", {code: "KeyW", key: "w", keyCode: 87, ctrlKey: true});
    env.win.dispatch("keyup", {code: "ControlLeft", key: "Control", keyCode: 17});
    const records = bridge.keyRecords();
    const control = bridge.name === "sdl" ? 224 : 341;
    const forward = bridge.name === "sdl" ? 26 : 87;
    const controlBit = bridge.name === "sdl" ? 64 : 2;
    assert.deepEqual(records.map(record => record[0]), [true, true, false, false]);
    assert.deepEqual(records.map(record => record[1]), [control, forward, forward, control]);
    assert.equal(records[1][2] & controlBit, controlBit, "W down sees Control held");
    assert.equal(records[2][2] & controlBit, controlBit, "W up sees Control held");
    assert.equal(records[3][2] & controlBit, 0, "Control release clears the modifier");
  });

  await test("without Keyboard Lock the lost lock becomes exactly one Escape press", async (bridge) => {
    const {env} = bridge;
    env.sandbox.navigator.keyboard = undefined;
    bridge.grab(true);
    env.win.dispatch("mousedown", {target: env.canvas, button: 0, clientX: 5, clientY: 5});
    await env.settle();
    assert.equal(env.doc.fullscreenElement, null, "no fullscreen without Keyboard Lock");
    assert.equal(env.doc.pointerLockElement, env.canvas);
    bridge.keys();
    // The browser consumes Escape to end the lock; the page only sees pointerlockchange.
    env.doc.pointerLockElement = null;
    env.doc.dispatch("pointerlockchange");
    env.advance(50);
    assert.deepEqual(bridge.keys(), [], "the lost lock waits for a focus change that ended it");
    env.advance(100);
    assert.deepEqual(bridge.keys(), [ESC_DOWN, ESC_UP], "one injected Escape opens the pause menu");
    assert.equal(env.sandbox.__gaiusEscapePauseInjected, 1);
    // A late key-down/key-up of that Escape (some browsers deliver it) is dropped.
    env.advance(20);
    env.win.dispatch("keydown", {code: "Escape", key: "Escape", keyCode: 27});
    env.win.dispatch("keyup", {code: "Escape", key: "Escape", keyCode: 27});
    assert.deepEqual(bridge.keys(), [], "the menu is not closed again by the same Escape");
    // The pause menu releases the mouse; the next Escape is the player's and closes it.
    bridge.grab(false);
    env.advance(600);
    env.win.dispatch("keydown", {code: "Escape", key: "Escape", keyCode: 27});
    env.win.dispatch("keyup", {code: "Escape", key: "Escape", keyCode: 27});
    assert.deepEqual(bridge.keys(), [ESC_DOWN, ESC_UP]);
  });

  await test("an Escape key-down delivered before the lock change is not doubled", async (bridge) => {
    const {env} = bridge;
    env.sandbox.navigator.keyboard = undefined;
    bridge.grab(true);
    env.win.dispatch("mousedown", {target: env.canvas, button: 0, clientX: 5, clientY: 5});
    await env.settle();
    bridge.keys();
    env.win.dispatch("keydown", {code: "Escape", key: "Escape", keyCode: 27});
    env.doc.pointerLockElement = null;
    env.doc.dispatch("pointerlockchange");
    env.win.dispatch("keyup", {code: "Escape", key: "Escape", keyCode: 27});
    env.advance(200);
    assert.deepEqual(bridge.keys(), [ESC_DOWN, ESC_UP], "only the delivered Escape reaches the game");
    assert.equal(env.sandbox.__gaiusEscapePauseInjected | 0, 0);
  });

  await test("a lock lost with the page focus injects nothing", async (bridge) => {
    const {env} = bridge;
    env.sandbox.navigator.keyboard = undefined;
    bridge.grab(true);
    env.win.dispatch("mousedown", {target: env.canvas, button: 0, clientX: 5, clientY: 5});
    await env.settle();
    bridge.keys();
    env.doc.pointerLockElement = null;
    env.doc.dispatch("pointerlockchange");
    env.doc.focused = false;
    env.win.dispatch("blur");
    env.advance(200);
    assert.deepEqual(bridge.keys().filter(k => k[1]), [], "focus loss is left to pauseOnLostFocus");
  });

  await test("a refused re-lock does not get stuck: the next canvas click asks again", async (bridge) => {
    const {env} = bridge;
    env.sandbox.navigator.keyboard = undefined;
    bridge.grab(true);
    env.win.dispatch("mousedown", {target: env.canvas, button: 0, clientX: 5, clientY: 5});
    await env.settle();
    env.doc.pointerLockElement = null;
    env.doc.dispatch("pointerlockchange");
    env.advance(200);
    bridge.grab(false);
    // Resume from the pause menu right away: the browser refuses the lock this time.
    env.refuseLock = true;
    bridge.grab(true);
    env.win.dispatch("mousedown", {target: env.canvas, button: 0, clientX: 5, clientY: 5});
    await env.settle();
    assert.equal(env.doc.pointerLockElement, null);
    env.refuseLock = false;
    const before = env.canvas.lockRequests;
    env.win.dispatch("mousedown", {target: env.canvas, button: 0, clientX: 5, clientY: 5});
    await env.settle();
    assert.equal(env.canvas.lockRequests, before + 1, "the next click requests pointer lock again");
    assert.equal(env.doc.pointerLockElement, env.canvas);
    bridge.keys();
    env.advance(200);
    assert.deepEqual(bridge.keys(), [], "a refused lock is not a lost lock");
  });

  await test("a hidden page reports focus loss once and regains it when focused again", async (bridge) => {
    const {env} = bridge;
    bridge.focus();
    // Hidden without a blur first (some tab switches): the game sees the focus loss.
    env.doc.visibilityState = "hidden";
    env.doc.dispatch("visibilitychange");
    assert.deepEqual(bridge.focus(), [false]);
    // The blur that follows does not matter to the game's focus state.
    env.doc.focused = false;
    env.win.dispatch("blur");
    assert.deepEqual(bridge.focus().filter(Boolean), [], "no focus gain while hidden");
    // Visible again but not focused yet (another window on top): still unfocused.
    env.doc.visibilityState = "visible";
    env.doc.dispatch("visibilitychange");
    assert.deepEqual(bridge.focus(), []);
    env.doc.focused = true;
    env.win.dispatch("focus");
    assert.deepEqual(bridge.focus(), [true]);
    // Blur first, then hidden: one loss; visible with focus: one gain.
    env.doc.focused = false;
    env.win.dispatch("blur");
    env.doc.visibilityState = "hidden";
    env.doc.dispatch("visibilitychange");
    assert.deepEqual(bridge.focus(), [false]);
    env.doc.focused = true;
    env.doc.visibilityState = "visible";
    env.doc.dispatch("visibilitychange");
    assert.deepEqual(bridge.focus(), [true]);
  });

  await test("a lock taken by another window is a focus loss, not an Escape", async (bridge) => {
    const {env} = bridge;
    env.sandbox.navigator.keyboard = undefined;
    bridge.grab(true);
    env.win.dispatch("mousedown", {target: env.canvas, button: 0, clientX: 5, clientY: 5});
    await env.settle();
    bridge.focus();
    env.doc.pointerLockElement = null;
    env.doc.dispatch("pointerlockchange");
    env.doc.focused = false;
    env.win.dispatch("blur");
    env.advance(200);
    assert.deepEqual(bridge.focus(), [false], "the game gets the focus loss for pauseOnLostFocus");
    assert.equal(env.sandbox.__gaiusEscapePauseInjected | 0, 0);
  });
}

// New browser profiles keep vanilla's pauseOnLostFocus default (true); the untouched v0.3 seed
// still names it so that such files are still recognised and replaced.
{
  const persistence = await readFile(new URL(
    "../overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java", import.meta.url), "utf8");
  const END = ') + "' + "\\" + 'n";';
  const section = (start) => {
    const at = persistence.indexOf(start);
    assert.ok(at > 0, `missing ${start}`);
    return persistence.slice(at, persistence.indexOf(END, at));
  };
  const common = section("BROWSER_OPTION_COMMON_DEFAULTS = String.join(");
  assert.ok(common.includes("hideSplashTexts:true"), "the common seed was found");
  assert.ok(!/"pauseOnLostFocus:/.test(common), "the new-profile seed leaves pauseOnLostFocus at the vanilla default");
  const previous = section("PREVIOUS_BROWSER_OPTION_DEFAULTS = String.join(");
  assert.ok(previous.includes("\"pauseOnLostFocus:false\""), "the v0.3 seed used for migration is unchanged");
  results.push("options seed: pauseOnLostFocus keeps the vanilla default");
}

console.log(`escape pause input smoke passed: ${results.length} tests`);
for (const name of results) console.log(`  ok - ${name}`);
