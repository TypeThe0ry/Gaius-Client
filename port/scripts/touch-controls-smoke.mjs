#!/usr/bin/env node
// Touchscreen controls: gesture -> game input mapping and the bridges' direct input API.
//
//   node port/scripts/touch-controls-smoke.mjs
//
// Loads port/web/launcher/touch-controls.js into node:vm with a small fake DOM and checks
// activation rules, joystick/look/button/hotbar mapping in the world, tap/long-press/drag/wheel
// mapping on GUI screens, multitouch independence and the virtual keyboard. The second half runs
// the real installDomBridge scripts of BrowserGlfw (1.21.11, 26.2) and BrowserSdlDom (26.3) and
// checks the window.__gaiusInput records they queue, the virtual pointer lock and the GLFW
// cursor-mode flag on browsers without the Pointer Lock API.

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const touchSource = await readFile(new URL("../web/launcher/touch-controls.js", import.meta.url), "utf8");
const glfwSource = await readFile(
  new URL("../overrides/libraries/lwjgl-glfw/src/main/java/org/lwjgl/glfw/BrowserGlfw.java", import.meta.url), "utf8");
const sdlSource = await readFile(
  new URL("../overrides/libraries/lwjgl-sdl/src/main/java/org/lwjgl/sdl/BrowserSdlDom.java", import.meta.url), "utf8");

// Values from the vm realm have their own prototypes; compare them as plain JSON.
const plain = value => JSON.parse(JSON.stringify(value));

// ---------------------------------------------------------------- fake DOM

class FakeTarget {
  constructor() {
    this.listeners = [];
  }
  addEventListener(type, fn, options) {
    const capture = options === true || !!(options && options.capture);
    this.listeners.push({type, fn, capture});
  }
  removeEventListener(type, fn) {
    this.listeners = this.listeners.filter(entry => entry.type !== type || entry.fn !== fn);
  }
  dispatch(type, init = {}) {
    const event = Object.assign({
      type,
      target: this,
      defaultPrevented: false,
      cancelable: true,
      stopped: false,
      immediateStopped: false,
      preventDefault() { this.defaultPrevented = true; },
      stopPropagation() { this.stopped = true; },
      stopImmediatePropagation() { this.stopped = true; this.immediateStopped = true; }
    }, init);
    let node = this;
    // Bubble from the dispatching node through its ancestors (window listeners are separate).
    const path = [];
    for (; node; node = node.parentNode) path.push(node);
    for (const current of path) {
      for (const entry of current.listeners.slice()) {
        if (entry.type !== type) continue;
        entry.fn.call(current, event);
        if (event.immediateStopped) return event;
      }
      if (event.stopped) return event;
    }
    return event;
  }
}

class FakeStyle {
  setProperty(name, value) {
    this[name] = value;
  }
}

class FakeElement extends FakeTarget {
  constructor(doc, tag) {
    super();
    this.ownerDocument = doc;
    this.tagName = String(tag).toUpperCase();
    this.children = [];
    this.parentNode = null;
    this.style = new FakeStyle();
    this.attributes = {};
    this.dataset = {};
    this.hidden = false;
    this.className = "";
    this.id = "";
    this.value = "";
    this.innerHTML = "";
    this.textContent = "";
    this.rect = {left: 0, top: 0, width: 0, height: 0};
    const classes = new Set();
    this.classList = {
      add: name => classes.add(name),
      remove: name => classes.delete(name),
      contains: name => classes.has(name)
    };
  }
  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
  }
  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }
  contains(node) {
    for (let current = node; current; current = current.parentNode) if (current === this) return true;
    return false;
  }
  getBoundingClientRect() {
    return Object.assign({}, this.rect);
  }
  focus() {
    this.ownerDocument.activeElement = this;
  }
  blur() {
    if (this.ownerDocument.activeElement === this) {
      this.ownerDocument.activeElement = this.ownerDocument.body;
      this.dispatch("blur", {target: this});
    }
  }
  setSelectionRange() {}
}

class FakeDocument extends FakeTarget {
  constructor() {
    super();
    this.documentElement = new FakeElement(this, "html");
    this.body = this.documentElement.appendChild(new FakeElement(this, "body"));
    this.activeElement = this.body;
    this.visibilityState = "visible";
    this.pointerLockElement = null;
    this.fullscreenElement = null;
  }
  createElement(tag) {
    return new FakeElement(this, tag);
  }
  getElementById(id) {
    const visit = node => {
      if (node.id === id) return node;
      for (const child of node.children) {
        const found = visit(child);
        if (found) return found;
      }
      return null;
    };
    return visit(this.documentElement);
  }
}

function createClock() {
  const clock = {now: 1000, timers: [], nextId: 1};
  clock.setTimeout = (fn, ms) => {
    const id = clock.nextId++;
    clock.timers.push({id, at: clock.now + Math.max(0, Number(ms) || 0), fn, repeat: 0});
    return id;
  };
  clock.setInterval = (fn, ms) => {
    const id = clock.nextId++;
    const period = Math.max(1, Number(ms) || 1);
    clock.timers.push({id, at: clock.now + period, fn, repeat: period});
    return id;
  };
  clock.clearTimeout = id => {
    clock.timers = clock.timers.filter(timer => timer.id !== id);
  };
  clock.advance = ms => {
    const end = clock.now + ms;
    for (;;) {
      const due = clock.timers.filter(timer => timer.at <= end).sort((a, b) => a.at - b.at || a.id - b.id)[0];
      if (!due) break;
      clock.now = due.at;
      if (due.repeat) due.at += due.repeat;
      else clock.timers = clock.timers.filter(timer => timer !== due);
      due.fn();
    }
    clock.now = end;
  };
  return clock;
}

function createEnvironment(options = {}) {
  const clock = createClock();
  const doc = new FakeDocument();
  const windowTarget = new FakeTarget();
  const storageData = new Map(Object.entries(options.storage || {}));
  const localStorage = options.brokenStorage ? {
    getItem() { throw new Error("blocked"); },
    setItem() { throw new Error("blocked"); },
    removeItem() { throw new Error("blocked"); }
  } : {
    getItem: key => (storageData.has(key) ? storageData.get(key) : null),
    setItem: (key, value) => storageData.set(key, String(value)),
    removeItem: key => storageData.delete(key)
  };
  const canvas = doc.createElement("canvas");
  canvas.id = "mc-canvas";
  canvas.rect = Object.assign({left: 0, top: 0, width: 844, height: 390}, options.canvasRect || {});
  canvas.width = 1688;
  canvas.height = 780;
  doc.body.appendChild(canvas);
  const sandbox = {
    console,
    URLSearchParams,
    document: doc,
    navigator: {maxTouchPoints: options.maxTouchPoints || 0},
    location: {search: options.search || ""},
    localStorage,
    innerWidth: options.innerWidth || 844,
    innerHeight: options.innerHeight || 390,
    devicePixelRatio: options.devicePixelRatio || 2,
    performance: {now: () => clock.now},
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    setInterval: clock.setInterval,
    clearInterval: clock.clearTimeout,
    requestAnimationFrame: fn => clock.setTimeout(() => fn(clock.now), 16),
    matchMedia: query => ({
      matches: query === "(pointer: coarse)" ? !!options.coarse : false,
      addEventListener() {},
      removeEventListener() {}
    }),
    getComputedStyle: () => ({
      paddingTop: "0px", paddingRight: "0px", paddingBottom: "0px", paddingLeft: "0px"
    }),
    addEventListener: windowTarget.addEventListener.bind(windowTarget),
    removeEventListener: windowTarget.removeEventListener.bind(windowTarget),
    screen: {orientation: null}
  };
  sandbox.window = sandbox;
  sandbox.__gaiusDisplay = {
    cssWidth: canvas.rect.width * (options.cssScale || 1),
    cssHeight: canvas.rect.height * (options.cssScale || 1),
    framebufferWidth: 1688,
    framebufferHeight: 780,
    pixelRatio: 2
  };
  vm.createContext(sandbox);
  const env = {sandbox, doc, clock, canvas, windowTarget, storageData};
  // Window events reach the window listeners only (the fake DOM keeps them separate).
  env.windowEvent = (type, init = {}) => windowTarget.dispatch(type, Object.assign({target: windowTarget}, init));
  return env;
}

function recorderInput(textInput = false) {
  const calls = [];
  const input = {
    backend: "test",
    calls,
    pointer: (x, y) => calls.push(["pointer", round(x), round(y)]),
    look: (dx, dy) => calls.push(["look", round(dx), round(dy)]),
    button: (button, down) => calls.push(["button", button, !!down]),
    key: (code, down) => calls.push(["key", code, !!down]),
    text: text => calls.push(["text", text]),
    wheel: (dx, dy) => calls.push(["wheel", dx, dy]),
    releaseAll: () => calls.push(["releaseAll"]),
    state: () => ({backend: "test", wantPointerLock: true, textInput, textInputArea: null})
  };
  return input;
}

function round(value) {
  return Math.round(Number(value) * 100) / 100;
}

function loadTouch(env) {
  vm.runInContext(touchSource, env.sandbox, {filename: "touch-controls.js"});
  return env.sandbox.__gaiusTouch;
}

function overlay(env) {
  return env.doc.getElementById("gaius-touch");
}

let nextTouchId = 1;
function touchStart(env, x, y, id = nextTouchId++) {
  const root = overlay(env);
  const event = root.dispatch("touchstart", {target: root, changedTouches: [{identifier: id, clientX: x, clientY: y}]});
  assert.ok(event.defaultPrevented, "touchstart on the overlay must cancel compatibility mouse events");
  return id;
}
function touchMove(env, id, x, y) {
  const root = overlay(env);
  return root.dispatch("touchmove", {target: root, changedTouches: [{identifier: id, clientX: x, clientY: y}]});
}
function touchEnd(env, id, x, y) {
  const root = overlay(env);
  return root.dispatch("touchend", {target: root, changedTouches: [{identifier: id, clientX: x, clientY: y}]});
}
function center(rect) {
  return [rect.x + rect.w / 2, rect.y + rect.h / 2];
}
function takeCalls(input) {
  return plain(input.calls.splice(0));
}

function worldEnv(options = {}) {
  const env = createEnvironment(Object.assign({coarse: true, maxTouchPoints: 5}, options));
  env.input = recorderInput(false);
  env.sandbox.__gaiusInput = env.input;
  env.sandbox.__gaiusMinecraftState = {level: "net.minecraft.client.multiplayer.ClientLevel", screen: null};
  env.doc.documentElement.dataset.gaiusShellView = "canvas";
  env.touch = loadTouch(env);
  env.touch.sync();
  return env;
}

function guiEnv(state, options = {}) {
  const env = worldEnv(options);
  env.sandbox.__gaiusMinecraftState = Object.assign({
    level: null,
    screen: "net.minecraft.client.gui.screens.TitleScreen",
    screenSize: {width: 563, height: 260},
    screenWidgets: []
  }, state || {});
  env.touch.sync();
  takeCalls(env.input);
  return env;
}

// ---------------------------------------------------------------- activation

{
  const env = createEnvironment({coarse: false, maxTouchPoints: 0});
  const touch = loadTouch(env);
  assert.equal(touch.enabled, false, "a desktop with a mouse must not enable the touch controls");
  assert.equal(overlay(env), null, "a desktop page must not get the overlay");
  assert.equal(env.sandbox.__gaiusTouchActive, undefined, "a desktop page must not set the virtual pointer lock");
  env.windowEvent("touchstart", {});
  assert.equal(touch.enabled, true, "the first real touch enables the controls");
  assert.equal(env.sandbox.__gaiusTouchActive, true);
  env.windowEvent("pointerdown", {pointerType: "mouse"});
  assert.equal(touch.enabled, false, "a real mouse on a hybrid device hands back to the desktop path");
  assert.equal(env.sandbox.__gaiusTouchActive, false);
  assert.ok(overlay(env).hidden, "the overlay hides when the mouse takes over");
}
{
  const env = createEnvironment({coarse: true, maxTouchPoints: 5});
  assert.equal(loadTouch(env).enabled, true, "touch-primary devices enable the controls at load");
  assert.ok(env.doc.documentElement.classList.contains("gaius-touch"));
}
{
  const env = createEnvironment({coarse: true, maxTouchPoints: 0});
  assert.equal(loadTouch(env).enabled, false, "a coarse pointer without touch points waits for a touch");
}
{
  const env = createEnvironment({coarse: true, maxTouchPoints: 5, search: "?touch=0"});
  const touch = loadTouch(env);
  assert.equal(touch.enabled, false, "?touch=0 disables the controls");
  assert.equal(env.storageData.get("gaius.touchControls"), "0", "?touch=0 is remembered");
  env.windowEvent("touchstart", {});
  assert.equal(touch.enabled, false, "a disabled override ignores touches");
}
{
  const env = createEnvironment({coarse: true, maxTouchPoints: 5, storage: {"gaius.touchControls": "0"}});
  assert.equal(loadTouch(env).enabled, false, "the remembered override applies without a URL parameter");
  const reset = createEnvironment({coarse: true, maxTouchPoints: 5, search: "?touch=auto",
    storage: {"gaius.touchControls": "0"}});
  assert.equal(loadTouch(reset).enabled, true, "?touch=auto restores detection");
  assert.equal(reset.storageData.has("gaius.touchControls"), false);
}
{
  const env = createEnvironment({coarse: false, maxTouchPoints: 0, search: "?touch=1"});
  const touch = loadTouch(env);
  assert.equal(touch.enabled, true, "?touch=1 forces the controls on a desktop");
  env.windowEvent("pointerdown", {pointerType: "mouse"});
  assert.equal(touch.enabled, true, "a forced override survives mouse input");
}
{
  const env = createEnvironment({coarse: true, maxTouchPoints: 5, brokenStorage: true, search: "?touch=1"});
  assert.equal(loadTouch(env).enabled, true, "blocked storage must not break activation");
}

// Hybrid devices: compatibility mouse events of the activating tap and the hand-back click.
{
  const env = createEnvironment({coarse: false, maxTouchPoints: 10});
  const touch = loadTouch(env);
  env.input = recorderInput(false);
  env.sandbox.__gaiusInput = env.input;
  env.sandbox.__gaiusMinecraftState = {level: "level", screen: null};
  env.doc.documentElement.dataset.gaiusShellView = "canvas";
  env.sandbox.__gaiusWantPointerLock = true;
  let locks = 0;
  env.canvas.requestPointerLock = () => { locks++; };
  const seen = [];
  // Registered after the touch layer, like the bridges' window listeners.
  ["mousedown", "mouseup", "mousemove"].forEach(type => {
    env.sandbox.addEventListener(type, event => seen.push(type + ":" + (event.target === env.canvas ? "canvas" : "other")));
  });
  env.windowEvent("touchstart", {target: env.canvas});
  env.windowEvent("touchend", {target: env.canvas});
  assert.equal(touch.enabled, true);
  env.clock.advance(30);
  for (const type of ["mousemove", "mousedown", "mouseup"]) {
    const event = env.windowEvent(type, {target: overlay(env), sourceCapabilities: {firesTouchEvents: true}});
    assert.ok(event.defaultPrevented, `${type} synthesised from the activating tap is cancelled`);
  }
  env.windowEvent("mousedown", {target: env.canvas});
  assert.deepEqual(seen, [], "compatibility mouse events of a tap never reach the bridges");
  env.clock.advance(1000);
  env.windowEvent("mousedown", {target: env.doc.body});
  assert.deepEqual(seen, ["mousedown:other"], "mouse events elsewhere (launcher UI) are untouched");
  seen.length = 0;

  // A real mouse click on the visible overlay hands back to the mouse: it takes the pointer lock
  // and does not reach the game as an attack.
  touch.sync();
  env.windowEvent("pointerdown", {pointerType: "mouse", target: overlay(env)});
  assert.equal(touch.enabled, false);
  assert.equal(locks, 1, "the hand-back click takes the pointer lock within its own gesture");
  env.windowEvent("mousedown", {target: overlay(env), sourceCapabilities: {firesTouchEvents: false}});
  env.windowEvent("mouseup", {target: env.canvas, sourceCapabilities: {firesTouchEvents: false}});
  assert.deepEqual(seen, [], "the hand-back click is kept from the game");
  env.windowEvent("pointerdown", {pointerType: "mouse", target: env.canvas});
  env.windowEvent("mousedown", {target: env.canvas, sourceCapabilities: {firesTouchEvents: false}});
  env.windowEvent("mouseup", {target: env.canvas, sourceCapabilities: {firesTouchEvents: false}});
  env.windowEvent("mousemove", {target: env.canvas, sourceCapabilities: {firesTouchEvents: false}});
  assert.deepEqual(seen, ["mousedown:canvas", "mouseup:canvas", "mousemove:canvas"],
    "later mouse input is the desktop path again");
}

// ---------------------------------------------------------------- pure geometry

{
  const core = worldEnv().touch.core;
  const keys = (dx, dy, latched) => {
    const result = core.stickKeys(dx, dy, 50, latched);
    return ["forward", "back", "left", "right", "sprint"].filter(name => result[name]).join("+") || "none";
  };
  assert.equal(keys(0, -50), "forward+sprint", "pushing the stick to the rim sprints");
  assert.equal(keys(0, -30), "forward");
  assert.equal(keys(30, -30), "forward+right", "diagonals hold two keys");
  assert.equal(keys(35, -35), "forward+right+sprint", "a diagonal at the rim sprints too");
  assert.equal(keys(-35, 35), "back+left");
  assert.equal(keys(-50, 0), "left");
  assert.equal(keys(0, 50), "back", "the rim does not sprint backwards");
  assert.equal(keys(48, -10), "right", "a shallow angle stays on one axis");
  assert.equal(keys(5, 5), "none", "the dead zone holds nothing");
  assert.equal(keys(0, -25, true), "forward+sprint", "a latched double tap sprints at any magnitude");

  assert.equal(core.autoGuiScale(1688, 780, 0), 3);
  assert.equal(core.autoGuiScale(854, 480, 0), 2);
  assert.equal(core.autoGuiScale(1688, 780, 2), 2, "an explicit GUI scale caps the auto scale");
  assert.equal(core.autoGuiScale(640, 360, 4), 1);

  const metrics = {rect: {left: 0, top: 0, width: 844, height: 390}, cssWidth: 844, cssHeight: 390,
    framebufferWidth: 1688, framebufferHeight: 780};
  const memory = {scale: 0, autoScale: 0};
  assert.deepEqual(plain(core.guiSize(metrics, null, memory)), {width: 563, height: 260, scale: 3},
    "the world GUI size follows the auto scale on the framebuffer (CSS size times DPR)");
  core.guiSize(metrics, {screenSize: {width: 844, height: 390}}, memory);
  assert.deepEqual(plain(core.guiSize(metrics, null, memory)), {width: 844, height: 390, scale: 2},
    "a GUI scale seen on a screen sticks in the world");

  // DPR 2: 844x390 CSS, 1688x780 framebuffer, GUI scale 3, 563x260 GUI pixels.
  const gui = {width: 563, height: 260, scale: 3};
  const toClient = (gx, gy) => [gx * 844 / 563, gy * 390 / 260];
  const slotCenter = slot => toClient(Math.floor(563 / 2) - 91 + 1 + slot * 20 + 10, 249);
  for (let slot = 0; slot < 9; slot++) {
    const point = slotCenter(slot);
    assert.equal(core.hotbarSlotAt(metrics, gui, point[0], point[1]), slot, `hotbar slot ${slot + 1}`);
  }
  assert.equal(core.hotbarSlotAt(metrics, gui, toClient(180, 249)[0], 373), -1, "left of the hotbar");
  assert.equal(core.hotbarSlotAt(metrics, gui, slotCenter(4)[0], 330), -1, "above the hotbar touch band");
  assert.equal(core.hotbarSlotAt(metrics, gui, slotCenter(4)[0], 352), 4,
    "a short hotbar still gets a touch band of at least 40 CSS pixels");

  // A canvas shown at half its CSS size (CSS transform or zoom) maps back to game pixels.
  const scaled = {rect: {left: 10, top: 20, width: 422, height: 195}, cssWidth: 844, cssHeight: 390,
    framebufferWidth: 1688, framebufferHeight: 780};
  assert.deepEqual(plain(core.toCanvas(scaled, 10 + 211, 20 + 97.5)), {x: 422, y: 195});
  assert.deepEqual(plain(core.toGui(scaled, gui, 10 + 211, 20 + 97.5)), {x: 281.5, y: 130});
}

// ---------------------------------------------------------------- world controls

{
  const env = worldEnv();
  const layout = env.touch.layout();
  assert.equal(env.touch.mode, "world");
  takeCalls(env.input);
  const stick = layout.stick;

  // Joystick: rim = forward + sprint, half way = forward only, release = nothing.
  let id = touchStart(env, stick.cx, stick.cy);
  assert.deepEqual(takeCalls(env.input), [], "a touch in the stick centre holds nothing");
  touchMove(env, id, stick.cx, stick.cy - stick.r);
  assert.deepEqual(takeCalls(env.input), [["key", "KeyW", true], ["key", "ControlLeft", true]]);
  touchMove(env, id, stick.cx, stick.cy - stick.r * 0.5);
  env.clock.advance(100);
  assert.deepEqual(takeCalls(env.input), [["key", "ControlLeft", false]], "sprint lets go below the rim");
  touchMove(env, id, stick.cx + stick.r * 0.6, stick.cy - stick.r * 0.6);
  assert.deepEqual(takeCalls(env.input), [["key", "KeyD", true]], "a diagonal adds the side key");
  touchEnd(env, id, stick.cx, stick.cy);
  env.clock.advance(100);
  assert.deepEqual(takeCalls(env.input), [["key", "KeyW", false], ["key", "KeyD", false]]);

  // The rotate hint of a portrait phone sits below the top button row, not on top of it.
  {
    const portrait = worldEnv({innerWidth: 390, innerHeight: 844});
    const pause = portrait.touch.layout().world.pause;
    const hint = overlay(portrait).children.find(child => child.className === "gt-rotate");
    assert.ok(hint && !hint.hidden, "a portrait phone shows the rotate hint");
    assert.ok(parseFloat(hint.style.top) >= pause.y + pause.h, "the rotate hint starts below the top button row");
  }

  // Double tap forward latches sprint at any stick magnitude.
  env.clock.advance(1000);
  id = touchStart(env, stick.cx, stick.cy);
  touchMove(env, id, stick.cx, stick.cy - stick.r * 0.6);
  env.clock.advance(100);
  touchEnd(env, id, stick.cx, stick.cy - stick.r * 0.6);
  env.clock.advance(120);
  takeCalls(env.input);
  id = touchStart(env, stick.cx, stick.cy - stick.r * 0.6);
  assert.deepEqual(takeCalls(env.input), [["key", "KeyW", true], ["key", "ControlLeft", true]],
    "forward twice in quick succession sprints");
  touchEnd(env, id, stick.cx, stick.cy);
  env.clock.advance(1000);
  takeCalls(env.input);

  // Look: relative deltas scaled by the sensitivity, no buttons for a drag.
  const lookX = 640;
  const lookY = 170;
  id = touchStart(env, lookX, lookY);
  touchMove(env, id, lookX + 20, lookY - 10);
  touchMove(env, id, lookX + 25, lookY - 10);
  touchEnd(env, id, lookX + 25, lookY - 10);
  env.clock.advance(400);
  assert.deepEqual(takeCalls(env.input), [["look", 40, -20], ["look", 10, 0]],
    "dragging looks around at sensitivity 2 without clicking");

  // Tap = use (right click held for at least one tick), hold still = attack.
  id = touchStart(env, lookX, lookY);
  env.clock.advance(60);
  touchEnd(env, id, lookX, lookY);
  assert.deepEqual(takeCalls(env.input), [["button", 1, true]]);
  env.clock.advance(70);
  assert.deepEqual(takeCalls(env.input), [], "the use click is held for at least 80 ms");
  env.clock.advance(10);
  assert.deepEqual(takeCalls(env.input), [["button", 1, false]]);
  id = touchStart(env, lookX, lookY);
  env.clock.advance(350);
  assert.deepEqual(takeCalls(env.input), [["button", 0, true]], "holding still attacks");
  touchMove(env, id, lookX + 30, lookY);
  touchEnd(env, id, lookX + 30, lookY);
  env.clock.advance(100);
  assert.deepEqual(takeCalls(env.input), [["look", 60, 0], ["button", 0, false]],
    "aiming while breaking keeps the attack held until release");

  // Multitouch: stick, look and jump at the same time, each tracked by identifier.
  const stickId = touchStart(env, stick.cx, stick.cy);
  touchMove(env, stickId, stick.cx, stick.cy - stick.r * 0.6);
  const lookId = touchStart(env, lookX, lookY);
  const jump = center(layout.world.jump);
  const jumpId = touchStart(env, jump[0], jump[1]);
  touchMove(env, lookId, lookX - 20, lookY);
  touchMove(env, stickId, stick.cx - stick.r * 0.6, stick.cy - stick.r * 0.6);
  env.clock.advance(100);
  touchEnd(env, lookId, lookX - 20, lookY);
  touchEnd(env, jumpId, jump[0], jump[1]);
  env.clock.advance(100);
  assert.deepEqual(takeCalls(env.input), [
    ["key", "KeyW", true],
    ["key", "Space", true],
    ["look", -40, 0],
    ["key", "KeyA", true],
    ["key", "Space", false]
  ], "joystick, look and jump work together");
  touchEnd(env, stickId, stick.cx, stick.cy);
  env.clock.advance(100);
  assert.deepEqual(takeCalls(env.input), [["key", "KeyW", false], ["key", "KeyA", false]]);

  // Buttons.
  const press = (name, holdMs) => {
    const point = center(layout.world[name]);
    const touchId = touchStart(env, point[0], point[1]);
    env.clock.advance(holdMs);
    touchEnd(env, touchId, point[0], point[1]);
    env.clock.advance(200);
    return takeCalls(env.input);
  };
  assert.deepEqual(press("attack", 500), [["button", 0, true], ["button", 0, false]]);
  assert.deepEqual(press("use", 500), [["button", 1, true], ["button", 1, false]], "holding use keeps eating");
  assert.deepEqual(press("jump", 20), [["key", "Space", true], ["key", "Space", false]]);
  assert.deepEqual(press("inventory", 20), [["key", "KeyE", true], ["key", "KeyE", false]]);
  assert.deepEqual(press("pause", 20), [["key", "Escape", true], ["key", "Escape", false]]);
  assert.deepEqual(press("perspective", 20), [["key", "F5", true], ["key", "F5", false]]);
  assert.deepEqual(press("drop", 20), [["key", "KeyQ", true], ["key", "KeyQ", false]]);
  {
    // Sprinting: a single drop lets go of Ctrl and the stick cannot press it again until Q is up
    // and the game has had time for a tick.
    const runId = touchStart(env, stick.cx, stick.cy);
    touchMove(env, runId, stick.cx, stick.cy - stick.r);
    env.clock.advance(100);
    assert.deepEqual(takeCalls(env.input), [["key", "KeyW", true], ["key", "ControlLeft", true]]);
    const drop = center(layout.world.drop);
    const dropId = touchStart(env, drop[0], drop[1]);
    env.clock.advance(20);
    touchEnd(env, dropId, drop[0], drop[1]);
    touchMove(env, runId, stick.cx + 1, stick.cy - stick.r);
    env.clock.advance(16);
    touchMove(env, runId, stick.cx, stick.cy - stick.r);
    env.clock.advance(100);
    touchMove(env, runId, stick.cx + 1, stick.cy - stick.r);
    assert.deepEqual(takeCalls(env.input), [["key", "ControlLeft", false], ["key", "KeyQ", true], ["key", "KeyQ", false]],
      "a single drop while sprinting is a plain Q; stick moves do not press Ctrl again meanwhile");
    env.clock.advance(100);
    assert.deepEqual(takeCalls(env.input), [["key", "ControlLeft", true]], "sprint comes back after the drop");
    touchEnd(env, runId, stick.cx, stick.cy);
    env.clock.advance(1000);
    takeCalls(env.input);
  }
  assert.deepEqual(press("drop", 600), [
    ["key", "ControlLeft", true], ["key", "KeyQ", true], ["key", "KeyQ", false], ["key", "ControlLeft", false]
  ], "holding drop throws the whole stack");
  assert.deepEqual(press("sneak", 20), [["key", "ShiftLeft", true]], "a sneak tap latches");
  assert.equal(overlay(env).children.find(node => node.attributes["data-control"] === "sneak")
    .attributes["data-on"], "1");
  assert.deepEqual(press("sneak", 20), [["key", "ShiftLeft", false]], "a second tap unlatches");
  assert.deepEqual(press("sneak", 500), [["key", "ShiftLeft", true], ["key", "ShiftLeft", false]],
    "a long sneak press is momentary (fly down)");
  assert.deepEqual(press("sneak", 20), [["key", "ShiftLeft", true]]);
  env.windowEvent("blur", {});
  assert.deepEqual(takeCalls(env.input), [["key", "ShiftLeft", false], ["releaseAll"]]);
  assert.equal(overlay(env).children.find(node => node.attributes["data-control"] === "sneak")
    .attributes["data-on"], "0", "going to the background clears the sneak latch with the key");
  assert.deepEqual(press("sneak", 20), [["key", "ShiftLeft", true]], "the next sneak tap sneaks again");
  assert.deepEqual(press("sneak", 20), [["key", "ShiftLeft", false]]);

  // Hotbar: a tap selects the slot through its number key.
  const hotbar = env.touch.hotbar();
  assert.ok(hotbar && hotbar.w > 0, "the hotbar rectangle is known in the world");
  const slotWidth = hotbar.w / 182 * 20;
  const slotX = hotbar.x + 1 * hotbar.w / 182 + slotWidth * 3.5;
  id = touchStart(env, slotX, hotbar.y + hotbar.h / 2);
  touchMove(env, id, slotX + slotWidth, hotbar.y + hotbar.h / 2);
  touchEnd(env, id, slotX + slotWidth, hotbar.y + hotbar.h / 2);
  env.clock.advance(200);
  assert.deepEqual(takeCalls(env.input), [
    ["key", "Digit4", true], ["key", "Digit5", true], ["key", "Digit4", false], ["key", "Digit5", false]
  ], "tapping and sliding along the hotbar selects slots 4 and 5");

  // Chat: T and the virtual keyboard from the same touchend.
  const chat = center(layout.world.chat);
  id = touchStart(env, chat[0], chat[1]);
  touchEnd(env, id, chat[0], chat[1]);
  env.clock.advance(200);
  assert.deepEqual(takeCalls(env.input), [["key", "KeyT", true], ["key", "KeyT", false]]);
  const text = env.doc.getElementById("gaius-touch-text");
  assert.equal(env.doc.activeElement, text, "chat focuses the hidden input to open the keyboard");

  // The chat screen opens; typed text, corrections and Enter reach the game.
  env.sandbox.__gaiusMinecraftState = {level: "level", screen: "net.minecraft.client.gui.screens.ChatScreen",
    screenSize: {width: 563, height: 260}, screenWidgets: []};
  env.touch.sync();
  assert.equal(env.touch.mode, "gui");
  takeCalls(env.input);
  const sentinel = "\u200b\u200b";
  text.value = sentinel + "hi";
  text.dispatch("input", {target: text});
  text.value = sentinel + "h";
  text.dispatch("input", {target: text});
  text.value = sentinel + "h\u00e9\u{1F600}";
  text.dispatch("input", {target: text});
  const enter = text.dispatch("keydown", {target: text, key: "Enter", code: "Enter", keyCode: 13});
  assert.ok(enter.stopped, "keys typed into the hidden input do not reach the bridges' window listeners");
  const typed = text.dispatch("keydown", {target: text, key: "a", code: "KeyA", keyCode: 65});
  assert.ok(typed.stopped && !typed.defaultPrevented, "printable keys stay native and arrive as text");
  assert.deepEqual(takeCalls(env.input), [
    ["text", "hi"],
    ["key", "Backspace", true], ["key", "Backspace", false],
    ["text", "\u00e9\u{1F600}"],
    ["key", "Enter", true], ["key", "Enter", false]
  ]);
  env.clock.advance(1000);
  assert.equal(env.doc.activeElement, text, "the keyboard stays open on the chat screen");
  env.sandbox.__gaiusMinecraftState = {level: "level", screen: null};
  env.clock.advance(200);
  assert.notEqual(env.doc.activeElement, text, "the keyboard closes with the chat screen");

  // A screen opening while the stick is held releases the movement keys.
  takeCalls(env.input);
  id = touchStart(env, stick.cx, stick.cy);
  touchMove(env, id, stick.cx, stick.cy - stick.r * 0.6);
  assert.deepEqual(takeCalls(env.input), [["key", "KeyW", true]]);
  env.sandbox.__gaiusMinecraftState = {level: "level", screen: "net.minecraft.client.gui.screens.inventory.InventoryScreen",
    screenSize: {width: 563, height: 260}, screenWidgets: []};
  env.clock.advance(150);
  assert.deepEqual(takeCalls(env.input), [["key", "KeyW", false]], "opening a screen releases the joystick");
  touchMove(env, id, stick.cx, stick.cy - stick.r);
  touchEnd(env, id, stick.cx, stick.cy);
  assert.deepEqual(takeCalls(env.input), [], "the cancelled touch is ignored until it lifts");
}

// ---------------------------------------------------------------- GUI screens

{
  // A corner button of the screen (Edit Profile) must not end up under the GUI button row: the row
  // keeps to its left, and a full-width widget (tab bar) does not push the row anywhere.
  const plain = guiEnv();
  const free = plain.touch.layout().gui.back;
  const corner = {type: "net.minecraft.client.gui.components.Button", text: "Edit Profile", x: 483, y: 2, width: 80, height: 14,
    active: true, visible: true, focused: false};
  const env = guiEnv({screenWidgets: [corner, {type: "net.minecraft.client.gui.components.tabs.TabNavigationBar", text: "",
    x: 0, y: 0, width: 563, height: 16, active: true, visible: true, focused: false}]});
  const rects = env.touch.layout().gui;
  const cornerLeft = corner.x * 844 / 563;
  Object.keys(rects).forEach(id => {
    assert.ok(rects[id].x + rects[id].w <= cornerLeft, `${id} stays left of the corner widget`);
  });
  assert.ok(rects.back.x < free.x, "the row moved left");
  const hit = center(rects.back);
  const tapId = touchStart(env, hit[0], hit[1]);
  touchEnd(env, tapId, hit[0], hit[1]);
  env.clock.advance(200);
  assert.deepEqual(takeCalls(env.input), [["key", "Escape", true], ["key", "Escape", false]],
    "the moved back button still presses Escape");
}

{
  const env = guiEnv();
  assert.equal(env.touch.mode, "gui");
  // Tap = left click at the touch point, in CSS pixels (the bridges scale by DPR themselves).
  let id = touchStart(env, 422, 195);
  env.clock.advance(50);
  touchEnd(env, id, 422, 195);
  assert.deepEqual(takeCalls(env.input), [
    ["pointer", 422, 195], ["pointer", 422, 195], ["button", 0, true], ["button", 0, false]
  ], "a tap clicks where it lands");

  // A slow tap on a screen without slots stays a left click (vanilla buttons ignore the right one).
  id = touchStart(env, 300, 120);
  env.clock.advance(450);
  touchEnd(env, id, 300, 120);
  assert.deepEqual(takeCalls(env.input), [
    ["pointer", 300, 120], ["pointer", 300, 120], ["button", 0, true], ["button", 0, false]
  ], "a long press on the title screen still presses the button");

  // Long press = right click (held until release) on container screens.
  const titleScreen = env.sandbox.__gaiusMinecraftState.screen;
  env.sandbox.__gaiusMinecraftState.screen = "net.minecraft.client.gui.screens.inventory.InventoryScreen";
  id = touchStart(env, 300, 120);
  env.clock.advance(450);
  touchEnd(env, id, 300, 120);
  assert.deepEqual(takeCalls(env.input), [
    ["pointer", 300, 120], ["pointer", 300, 120], ["button", 1, true], ["pointer", 300, 120], ["button", 1, false]
  ], "a long press in a container screen right clicks");
  env.sandbox.__gaiusMinecraftState.screen = "net.minecraft.client.gui.screens.inventory.SignEditScreen";
  id = touchStart(env, 300, 120);
  env.clock.advance(450);
  touchEnd(env, id, 300, 120);
  assert.equal(takeCalls(env.input).filter(call => call[0] === "button" && call[1] === 1).length, 0,
    "screens in the inventory package without slots get no right click");
  env.sandbox.__gaiusMinecraftState.screen = titleScreen;

  // Drag = left button held while the pointer follows.
  id = touchStart(env, 100, 100);
  touchMove(env, id, 130, 100);
  touchMove(env, id, 160, 110);
  touchEnd(env, id, 160, 110);
  assert.deepEqual(takeCalls(env.input), [
    ["pointer", 100, 100], ["pointer", 100, 100], ["button", 0, true], ["pointer", 130, 100],
    ["pointer", 160, 110], ["pointer", 160, 110], ["button", 0, false]
  ], "a drag holds the left button (sliders, inventory dragging)");

  // Two fingers moving vertically scroll with the wheel and never click.
  const first = touchStart(env, 300, 100);
  const second = touchStart(env, 400, 100);
  takeCalls(env.input);
  touchMove(env, first, 300, 130);
  touchMove(env, second, 400, 130);
  touchMove(env, first, 300, 160);
  touchMove(env, second, 400, 160);
  touchEnd(env, first, 300, 160);
  touchEnd(env, second, 400, 160);
  env.clock.advance(500);
  const scrolled = takeCalls(env.input);
  assert.equal(scrolled.filter(call => call[0] === "button").length, 0, "a two-finger swipe never clicks");
  const wheel = scrolled.filter(call => call[0] === "wheel").reduce((sum, call) => sum + call[2], 0);
  assert.equal(wheel, 2, "a 60 px two-finger swipe down scrolls two wheel steps up");
  const reverse = [touchStart(env, 300, 200), touchStart(env, 400, 200)];
  touchMove(env, reverse[0], 300, 140);
  touchMove(env, reverse[1], 400, 140);
  touchEnd(env, reverse[0], 300, 140);
  touchEnd(env, reverse[1], 400, 140);
  assert.equal(takeCalls(env.input).filter(call => call[0] === "wheel").reduce((sum, call) => sum + call[2], 0), -2);

  // Tapping a text field opens the virtual keyboard from the touchend.
  env.sandbox.__gaiusMinecraftState.screenWidgets = [
    {type: "net.minecraft.client.gui.components.EditBox", text: "Seed", x: 180, y: 60, width: 200, height: 20,
      active: true, visible: true, focused: false}
  ];
  const field = [(180 + 100) * 844 / 563, (60 + 10) * 390 / 260];
  id = touchStart(env, field[0], field[1]);
  touchEnd(env, id, field[0], field[1]);
  const text = env.doc.getElementById("gaius-touch-text");
  assert.equal(env.doc.activeElement, text, "tapping an EditBox opens the keyboard");
  env.sandbox.__gaiusMinecraftState.screenWidgets[0].focused = true;
  env.clock.advance(1000);
  assert.equal(env.doc.activeElement, text, "the keyboard stays while the field is focused");
  env.sandbox.__gaiusMinecraftState.screenWidgets[0].focused = false;
  env.clock.advance(200);
  assert.notEqual(env.doc.activeElement, text, "the keyboard closes when the field loses focus");

  // A vanilla anonymous EditBox subclass (the seed field) is only known through the edit flag.
  env.sandbox.__gaiusMinecraftState.screenWidgets = [
    {type: "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab$1", text: "Seed", x: 180, y: 60,
      width: 200, height: 20, active: true, visible: true, focused: false, edit: true}
  ];
  id = touchStart(env, field[0], field[1]);
  touchEnd(env, id, field[0], field[1]);
  assert.equal(env.doc.activeElement, text, "tapping an anonymous EditBox subclass opens the keyboard");
  env.sandbox.__gaiusMinecraftState.screenWidgets[0].focused = false;
  env.clock.advance(1000);
  env.sandbox.__gaiusMinecraftState.screenWidgets = [
    {type: "net.minecraft.client.gui.components.EditBox", text: "Seed", x: 180, y: 60, width: 200, height: 20,
      active: true, visible: true, focused: false}
  ];

  // Android hides the keyboard (back button) without blurring the input: tapping the field or the
  // keyboard button must bring it back instead of doing nothing or closing it.
  const viewport = {height: 390, offsetTop: 0, addEventListener() {}};
  env.sandbox.visualViewport = viewport;
  let blurs = 0;
  text.addEventListener("blur", () => blurs++);
  env.sandbox.__gaiusMinecraftState.screenWidgets[0].focused = true;
  id = touchStart(env, field[0], field[1]);
  touchEnd(env, id, field[0], field[1]);
  assert.equal(env.doc.activeElement, text);
  viewport.height = 190;
  env.touch.sync();
  id = touchStart(env, field[0], field[1]);
  touchEnd(env, id, field[0], field[1]);
  assert.equal(blurs, 0, "a visible keyboard is left alone");
  viewport.height = 390;
  env.touch.sync();
  assert.equal(env.doc.activeElement, text, "the hidden keyboard keeps the input focused");
  id = touchStart(env, field[0], field[1]);
  touchEnd(env, id, field[0], field[1]);
  assert.equal(blurs, 1, "a hidden keyboard is reopened with a fresh focus");
  assert.equal(env.doc.activeElement, text);
  viewport.height = 190;
  env.touch.sync();
  viewport.height = 390;
  env.touch.sync();
  const keyboardButton = center(env.touch.layout().gui.keyboard);
  id = touchStart(env, keyboardButton[0], keyboardButton[1]);
  touchEnd(env, id, keyboardButton[0], keyboardButton[1]);
  assert.equal(blurs, 2, "the keyboard button reopens a hidden keyboard rather than closing it");
  assert.equal(env.doc.activeElement, text);
  id = touchStart(env, keyboardButton[0], keyboardButton[1]);
  touchEnd(env, id, keyboardButton[0], keyboardButton[1]);
  assert.notEqual(env.doc.activeElement, text, "the keyboard button closes a keyboard not seen hidden");
  env.sandbox.__gaiusMinecraftState.screenWidgets[0].focused = false;
  delete env.sandbox.visualViewport;

  // GUI buttons: back = Escape, settings popover toggles.
  const layout = env.touch.layout();
  takeCalls(env.input);
  const back = center(layout.gui.back);
  id = touchStart(env, back[0], back[1]);
  touchEnd(env, id, back[0], back[1]);
  env.clock.advance(200);
  assert.deepEqual(takeCalls(env.input), [["key", "Escape", true], ["key", "Escape", false]]);
  const gear = center(layout.gui.settings);
  id = touchStart(env, gear[0], gear[1]);
  touchEnd(env, id, gear[0], gear[1]);
  const panel = overlay(env).children.find(node => node.className === "gt-panel");
  assert.equal(panel.hidden, false, "the gear opens the settings popover");
  const panelTouch = overlay(env).dispatch("touchstart", {target: panel,
    changedTouches: [{identifier: 999, clientX: 422, clientY: 195}]});
  assert.equal(panelTouch.defaultPrevented, false, "touches on the popover keep their native behaviour");
  assert.deepEqual(takeCalls(env.input), [], "touches on the popover never reach the game");
}

// Settings persist and are clamped.
{
  const env = createEnvironment({coarse: true, maxTouchPoints: 5,
    storage: {"gaius.touchSettings": JSON.stringify({opacity: 5, size: 0.8, sensitivity: "x"})}});
  const touch = loadTouch(env);
  assert.deepEqual(plain(touch.settings), {opacity: 1, size: 0.8, sensitivity: 2});
}

// ---------------------------------------------------------------- bridges' direct input API

function extractJsBody(source, signature) {
  const markerOffset = source.indexOf(signature);
  assert.ok(markerOffset > 0, `missing ${signature}`);
  const annotationOffset = source.lastIndexOf("@JSBody(", markerOffset);
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(annotationOffset > 0 && scriptEnd > scriptOffset, `JSBody could not be extracted for ${signature}`);
  return source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}

function bridgeEnv() {
  const env = createEnvironment({coarse: true, maxTouchPoints: 5});
  env.lockRequests = 0;
  env.canvas.requestPointerLock = () => {
    env.lockRequests++;
  };
  return env;
}

function runJsBody(env, script, params = {}) {
  const names = Object.keys(params);
  const fn = vm.runInContext("(function(" + names.join(",") + "){" + script + "\n})", env.sandbox);
  return fn(...names.map(name => params[name]));
}

{
  const env = bridgeEnv();
  runJsBody(env, extractJsBody(glfwSource, "private static native void installDomBridge();"));
  const setInputMode = extractJsBody(glfwSource, "private static native void setInputModeJs(int mode, int value);");
  const w = env.sandbox;
  const input = w.__gaiusInput;
  assert.equal(input.backend, "glfw");
  const events = () => plain(w.__gaiusGlfwEvents.slice(w.__gaiusGlfwEventHead | 0));
  const drain = () => {
    const list = events();
    w.__gaiusGlfwEvents.length = 0;
    w.__gaiusGlfwEventHead = 0;
    w.__gaiusGlfwPendingMouseMove = -1;
    return list;
  };
  input.pointer(10, 20);
  input.look(5, -3);
  assert.deepEqual(drain(), [[4, 0, 0, 0, 0, 15, 17]], "pointer and look coalesce into one GLFW cursor move");
  input.key("ShiftLeft", true);
  input.button(0, true);
  input.button(0, true);
  input.key("KeyW", true);
  input.text("h\u00e9\u{1F600}\n");
  input.wheel(0, 2);
  assert.equal(w.__gaiusGlfwKeys[87], true, "glfwGetKey sees injected keys");
  assert.equal(w.__gaiusGlfwButtons[0], true, "glfwGetMouseButton sees injected buttons");
  input.releaseAll();
  assert.deepEqual(drain(), [
    [1, 340, 16, 1, 1, 0, 0],
    [3, 0, 1, 1, 0, 15, 17],
    [1, 87, 87, 1, 1, 0, 0],
    [2, 104, 0, 0, 0, 0, 0],
    [2, 233, 0, 0, 0, 0, 0],
    [2, 128512, 0, 0, 0, 0, 0],
    [5, 0, 0, 0, 0, 0, 2],
    [1, 340, 16, 0, 0, 0, 0],
    [1, 87, 87, 0, 0, 0, 0],
    [3, 0, 0, 0, 0, 15, 17]
  ], "GLFW records for keys, mods, buttons, text and wheel");
  assert.equal(input.key("NotAKey", true), false);
  env.windowEvent("beforeunload", {});
  assert.deepEqual(drain(), [], "page teardown releases browser keyboard state without queueing a game close event");
  assert.equal(w.__gaiusKeyboardLockHeld, false, "page teardown clears the keyboard-lock state");

  // Cursor disabled without the Pointer Lock API: the flag must follow the cursor mode.
  runJsBody(env, setInputMode, {mode: 0x33001, value: 0x34003});
  assert.equal(w.__gaiusWantPointerLock, true);
  assert.equal(input.state().wantPointerLock, true);
  runJsBody(env, setInputMode, {mode: 0x33001, value: 0x34001});
  assert.equal(w.__gaiusWantPointerLock, false, "GLFW clears the lock flag without document.exitPointerLock");

  // Virtual pointer lock: no real lock while the touch controls are active.
  runJsBody(env, setInputMode, {mode: 0x33001, value: 0x34003});
  w.__gaiusTouchActive = true;
  env.windowEvent("mousedown", {target: env.canvas, button: 0, clientX: 5, clientY: 5});
  assert.equal(env.lockRequests, 0, "touch mode never requests pointer lock");
  w.__gaiusTouchActive = false;
  env.windowEvent("mousedown", {target: env.canvas, button: 0, clientX: 5, clientY: 5});
  assert.equal(env.lockRequests, 1, "desktop clicks still request pointer lock");

  // End to end: a GUI tap from the touch layer becomes GLFW move + press + release.
  drain();
  w.__gaiusMinecraftState = {level: null, screen: "net.minecraft.client.gui.screens.TitleScreen",
    screenSize: {width: 563, height: 260}, screenWidgets: []};
  env.doc.documentElement.dataset.gaiusShellView = "canvas";
  const touch = loadTouch(env);
  touch.sync();
  const id = touchStart(env, 200, 100);
  touchEnd(env, id, 200, 100);
  assert.deepEqual(drain(), [[4, 0, 0, 0, 0, 200, 100], [3, 0, 1, 0, 0, 200, 100], [3, 0, 0, 0, 0, 200, 100]],
    "a tap reaches GLFW as one cursor move and a left click");
}

{
  const env = bridgeEnv();
  runJsBody(env, extractJsBody(sdlSource, "static native void installDomBridge();"));
  const w = env.sandbox;
  const sdl = w.__gaiusSdl;
  const input = w.__gaiusInput;
  assert.equal(input.backend, "sdl");
  const drain = () => {
    const list = [];
    while (sdl.poll()) {
      const r = sdl.current;
      list.push([r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9]]);
    }
    return plain(list.filter(r => r[0] < 0x200 || r[0] > 0x2ff));
  };
  drain();
  input.pointer(10, 20);
  input.look(5, -3);
  assert.deepEqual(drain(), [[1024, 0, 0, 0, 0, 15, 17, 15, 17, null]],
    "SDL motion carries xrel/yrel, so relative mouse mode works without pointer lock");
  input.button(1, true);
  input.key("ShiftLeft", true);
  input.key("KeyW", true);
  assert.equal(sdl.keys[26], 0, "keyboard state changes when the record is polled");
  assert.equal(input.text("hi"), false, "no TEXT_INPUT before SDL_StartTextInput");
  input.wheel(0, 2);
  const pressed = drain();
  assert.deepEqual(pressed, [
    [1025, 3, 1, 0, 0, 15, 17, 0, 0, null],
    [768, 225, 1073742049, 1, 0, 0, 0, 0, 0, null],
    [768, 26, 119, 1, 0, 0, 0, 0, 0, null],
    [1027, 0, 0, 1, 0, 0, 2, 15, 17, null]
  ], "SDL records for the right button, keys with mods and the wheel");
  assert.equal(sdl.keys[26], 1);
  assert.equal(sdl.buttons, 4);
  sdl.setTextInput(true);
  assert.equal(input.state().textInput, true);
  input.text("h\u00e9\n");
  input.releaseAll();
  assert.deepEqual(drain(), [
    [771, 0, 0, 0, 0, 0, 0, 0, 0, "h\u00e9"],
    [769, 26, 119, 1, 0, 0, 0, 0, 0, null],
    [769, 225, 1073742049, 0, 0, 0, 0, 0, 0, null],
    [1026, 3, 1, 0, 0, 15, 17, 0, 0, null]
  ], "TEXT_INPUT while text input is on; releaseAll lets go of keys and buttons");
  assert.equal(sdl.keys[26], 0);

  // Blur resets the keyboard; a later injected key-up is not duplicated.
  input.key("KeyA", true);
  env.windowEvent("blur", {});
  assert.equal(input.key("KeyA", false), false, "a key released by blur is not released twice");

  // Virtual pointer lock: relative mode asks for no real lock while touch is active.
  w.__gaiusTouchActive = true;
  sdl.setRelative(true);
  assert.equal(w.__gaiusWantPointerLock, true);
  assert.equal(env.lockRequests, 0, "touch mode never requests pointer lock");
  sdl.setRelative(false);
  w.__gaiusTouchActive = false;
  sdl.setRelative(true);
  assert.equal(env.lockRequests, 1, "desktop relative mode still requests pointer lock");
  drain();
  env.windowEvent("beforeunload", {});
  assert.deepEqual(drain(), [], "page teardown releases browser keyboard state without queueing an SDL close event");
  assert.equal(w.__gaiusKeyboardLockHeld, false, "page teardown clears the keyboard-lock state");
}

console.log("touch controls smoke passed");
