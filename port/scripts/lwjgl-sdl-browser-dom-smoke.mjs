#!/usr/bin/env node
// Node + mock DOM tests of the browser SDL3 shim's JavaScript half
// (port/overrides/libraries/lwjgl-sdl/.../BrowserSdlDom.java, work package P2).
//
// Every @JSBody script of BrowserSdlDom is extracted from the Java source and run in a
// vm context with a scripted DOM, so the tests exercise exactly the code TeaVM emits.
// Covered: PollEvent re-entrancy, FlushEvents keeping window events and applying key
// state, TEXT_INPUT only while text input is active, mouse-motion coalescing, resize
// emitting WINDOW_RESIZED (518) and WINDOW_PIXEL_SIZE_CHANGED (519), focus-loss key
// release, keycodes/modifiers, wheel direction, fullscreen, clipboard, pointer lock,
// input warm-up and contract C5 (window.__gaiusWebGL exists after SDL_GL_CreateContext).
//
//   node port/scripts/lwjgl-sdl-browser-dom-smoke.mjs

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

// GAIUS_SDL_DOM_SOURCE points the smoke at another copy of BrowserSdlDom.java (used to
// check that a mutated engine fails the tests).
const sourceUrl = process.env.GAIUS_SDL_DOM_SOURCE
  ? process.env.GAIUS_SDL_DOM_SOURCE
  : new URL("../overrides/libraries/lwjgl-sdl/src/main/java/org/lwjgl/sdl/BrowserSdlDom.java", import.meta.url);
const source = await readFile(sourceUrl, "utf8");

/** Returns {name -> {params, script}} for every @JSBody native method of the class. */
function extractJsBodies(text) {
  const bodies = new Map();
  let offset = 0;
  for (;;) {
    const at = text.indexOf("@JSBody(", offset);
    if (at < 0) break;
    let cursor = at + "@JSBody(".length;
    let params = [];
    // params is an array initializer or, for a single parameter, one string literal.
    const paramsMatch = /^params = (?:\{([^}]*)\}|("[^"]*")),\s*/.exec(text.slice(cursor));
    if (paramsMatch) {
      params = (paramsMatch[1] ?? paramsMatch[2]).split(",").map((value) => value.trim().replace(/^"|"$/g, "")).filter(Boolean);
      cursor += paramsMatch[0].length;
    }
    const scriptPrefix = /^script = /.exec(text.slice(cursor));
    assert.ok(scriptPrefix, `@JSBody at ${at} has no script`);
    cursor += scriptPrefix[0].length;
    let script;
    if (text.startsWith('"""', cursor)) {
      const end = text.indexOf('"""', cursor + 3);
      script = text.slice(cursor + 3, end);
      cursor = end + 3;
    } else {
      assert.equal(text[cursor], '"', `@JSBody at ${at} has an unexpected script literal`);
      let end = cursor + 1;
      while (text[end] !== '"' || text[end - 1] === "\\") end++;
      script = text.slice(cursor + 1, end);
      cursor = end + 1;
    }
    assert.ok(!script.includes("\\"), `@JSBody at ${at} uses a Java escape; keep scripts escape-free`);
    const declaration = /\)\s*(?:(?:private|public)\s+)?static native [\w.<>]+ (\w+)\(/.exec(text.slice(cursor, cursor + 400));
    assert.ok(declaration, `@JSBody at ${at} is not followed by a static native method`);
    bodies.set(declaration[1], {params, script});
    offset = cursor;
  }
  return bodies;
}

const bodies = extractJsBodies(source);
// The clipboard bridge (dev.gaius.browser.BrowserClipboard) is installed by SDL_Init before
// the DOM bridge; its install script runs in the same page.
const clipboardSource = await readFile(
  new URL("../overrides/classlib/src/main/java/dev/gaius/browser/BrowserClipboard.java", import.meta.url), "utf8");
const clipboardInstall = extractJsBodies(clipboardSource).get("install");
assert.ok(clipboardInstall, "BrowserClipboard has no @JSBody install");
for (const name of [
  "installDomBridge", "poll", "peek", "recordInt", "recordDouble", "recordText", "recordTimestamp",
  "flush", "keyState", "modState", "buttonState", "cursorX", "cursorY", "focused", "relativeMouseMode",
  "fullscreen", "keycodeForScancode", "setTextInput", "setTextInputArea", "setRelativeMouseMode",
  "warpMouse", "setCursor", "setFullscreen", "prepareCanvas", "showMainWindow", "createContext",
  "resizeCanvas", "setTitle", "canvasCssWidth", "canvasCssHeight", "framebufferWidth",
  "framebufferHeight", "writeClipboard", "readClipboard", "openUrl", "swapBuffersJs", "scheduleFrameYield",
]) {
  assert.ok(bodies.has(name), `BrowserSdlDom has no @JSBody ${name}`);
}

class MockElement {
  constructor(tag) {
    this.tagName = tag;
    this.id = "";
    this.width = 300;
    this.height = 150;
    this.style = {};
    this.listeners = new Map();
    this.pointerLockRequests = 0;
    this.focusCount = 0;
    this.contextRequests = [];
  }
  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }
  dispatch(type, event = {}) {
    for (const listener of this.listeners.get(type) || []) listener(event);
  }
  focus() {
    this.focusCount++;
    this.ownerDocument.activeElement = this;
  }
  getBoundingClientRect() {
    const width = Number.parseFloat(this.style.width) || this.width;
    const height = Number.parseFloat(this.style.height) || this.height;
    return {left: 0, top: 0, width, height};
  }
  getContext(kind, attributes) {
    this.contextRequests.push({kind, attributes});
    return kind === "webgl2" && this.webgl2 !== false ? {kind, attributes} : null;
  }
  requestPointerLock() {
    this.pointerLockRequests++;
    this.ownerDocument.pointerLockElement = this;
    return Promise.resolve();
  }
}

function createBrowser({canvas = true, webgl2 = true, dpr = 1, width = 1280, height = 720} = {}) {
  let now = 1000;
  const windowListeners = new Map();
  const documentListeners = new Map();
  const clipboardWrites = [];
  const opened = [];
  const errors = [];
  const document = {
    elements: new Map(),
    activeElement: null,
    pointerLockElement: null,
    fullscreenElement: null,
    visibilityState: "visible",
    title: "",
    exitPointerLockCount: 0,
    body: {
      appendChild(element) {
        if (element.id) document.elements.set(element.id, element);
      },
    },
    documentElement: {
      requestFullscreen() {
        document.fullscreenElement = document.documentElement;
        return Promise.resolve();
      },
    },
    getElementById(id) {
      return this.elements.get(id) || null;
    },
    createElement(tag) {
      const element = new MockElement(tag);
      element.ownerDocument = document;
      element.webgl2 = webgl2;
      return element;
    },
    addEventListener(type, listener) {
      if (!documentListeners.has(type)) documentListeners.set(type, []);
      documentListeners.get(type).push(listener);
    },
    exitPointerLock() {
      this.exitPointerLockCount++;
      this.pointerLockElement = null;
    },
    exitFullscreen() {
      this.fullscreenElement = null;
      return Promise.resolve();
    },
  };
  if (canvas) {
    const element = document.createElement("canvas");
    element.id = "mc-canvas";
    document.elements.set("mc-canvas", element);
  }
  const context = {
    Array, Date, Error, Map, Math, Number, Object, Promise, String, Uint8Array, Uint32Array,
    Int32Array, Float32Array, Boolean, JSON, URLSearchParams, globalThis: undefined,
    console: {error: (message) => errors.push(String(message)), log() {}, warn() {}},
    document,
    location: {search: ""},
    screen: {width: 2560, height: 1440},
    devicePixelRatio: dpr,
    innerWidth: width,
    innerHeight: height,
    performance: {now: () => now},
    navigator: {
      clipboard: {
        writeText(value) {
          clipboardWrites.push(value);
          return Promise.resolve();
        },
      },
    },
    setTimeout: () => 0,
    clearTimeout: () => {},
    addEventListener(type, listener) {
      if (!windowListeners.has(type)) windowListeners.set(type, []);
      windowListeners.get(type).push(listener);
    },
    open(url, target, features) {
      opened.push({url, target, features});
      return null;
    },
  };
  context.window = context;
  context.globalThis = context;
  vm.createContext(context);
  const functions = {};
  for (const [name, {params, script}] of bodies) {
    functions[name] = vm.runInContext(`(function(${params.join(",")}) {${script}})`, context);
  }
  functions.installClipboard = vm.runInContext(`(function() {${clipboardInstall.script}})`, context);
  const fire = (type, fields = {}) => {
    const event = {
      type,
      defaultPrevented: false,
      preventDefault() {
        this.defaultPrevented = true;
      },
      getModifierState: () => false,
      shiftKey: false,
      ctrlKey: false,
      altKey: false,
      metaKey: false,
      ...fields,
    };
    for (const listener of windowListeners.get(type) || []) listener(event);
    return event;
  };
  const fireDocument = (type, fields = {}) => {
    const event = {type, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, ...fields};
    for (const listener of documentListeners.get(type) || []) listener(event);
    return event;
  };
  return {
    context, document, js: functions, fire, fireDocument, clipboardWrites, opened, errors,
    advance(ms) { now += ms; },
    canvas: () => document.getElementById("mc-canvas"),
    sdl: () => context.__gaiusSdl,
  };
}

const KEY_DOWN = 768, KEY_UP = 769, TEXT_INPUT = 771, MOTION = 1024, BUTTON_DOWN = 1025,
  BUTTON_UP = 1026, WHEEL = 1027, RESIZED = 518, PIXEL_SIZE_CHANGED = 519, FOCUS_GAINED = 526,
  FOCUS_LOST = 527, ENTER_FULLSCREEN = 535, LEAVE_FULLSCREEN = 536;

/** Mirrors BrowserSdlEvents.poll: dequeue, then read the record fields through the glue. */
function pollRecord(browser) {
  const type = browser.js.poll();
  if (type === 0) return null;
  return {
    type,
    i: [1, 2, 3, 4].map((index) => browser.js.recordInt(index)),
    f: [5, 6, 7, 8].map((index) => browser.js.recordDouble(index)),
    text: browser.js.recordText(),
    ts: browser.js.recordTimestamp(),
  };
}

function drain(browser) {
  const records = [];
  for (let record = pollRecord(browser); record; record = pollRecord(browser)) records.push(record);
  return records;
}

function types(records) {
  return records.map((record) => record.type);
}

function keyEvent(code, key, fields = {}) {
  return {code, key, keyCode: 0, repeat: false, ...fields};
}

function started(options) {
  const browser = createBrowser(options);
  browser.js.installClipboard();
  browser.js.installDomBridge();
  browser.js.prepareCanvas();
  browser.js.showMainWindow(854, 480, "Minecraft 26.3");
  return browser;
}

const results = [];
function test(name, body) {
  body();
  results.push(name);
}

test("contract C5: the WebGL 2 context exists after SDL_GL_CreateContext, on a hidden-window path too", () => {
  const browser = createBrowser({canvas: false});
  browser.js.installDomBridge();
  browser.js.prepareCanvas();
  assert.ok(browser.canvas(), "prepareCanvas must create #mc-canvas");
  const sizeBefore = [browser.canvas().width, browser.canvas().height];
  assert.equal(browser.js.createContext(), true);
  assert.ok(browser.context.__gaiusWebGL, "window.__gaiusWebGL is missing after createContext");
  assert.deepEqual([browser.canvas().width, browser.canvas().height], sizeBefore,
    "a hidden window's context must not resize the canvas");
  const request = browser.canvas().contextRequests[0];
  assert.equal(request.kind, "webgl2");
  assert.deepEqual(
    {alpha: request.attributes.alpha, antialias: request.attributes.antialias, depth: request.attributes.depth,
      stencil: request.attributes.stencil, powerPreference: request.attributes.powerPreference,
      preserveDrawingBuffer: request.attributes.preserveDrawingBuffer},
    {alpha: false, antialias: false, depth: true, stencil: true, powerPreference: "high-performance",
      preserveDrawingBuffer: false});
  assert.equal(browser.js.createContext(), true, "a second context request reuses the context");
  assert.equal(browser.canvas().contextRequests.length, 1);
  browser.js.showMainWindow(854, 480, "Minecraft 26.3");
  assert.equal(browser.canvas().style.width, "854px");
  assert.equal(browser.document.title, "Minecraft 26.3");
  assert.equal(browser.js.framebufferWidth(), 854);
  assert.equal(browser.js.canvasCssWidth(), 854);

  const noGl = createBrowser({webgl2: false});
  noGl.js.installDomBridge();
  assert.equal(noGl.js.createContext(), false, "createContext must report a missing WebGL 2");
  assert.equal(noGl.context.__gaiusWebGL, null);
});

test("TEXT_INPUT follows KEY_DOWN only while SDL text input is active", () => {
  const browser = started();
  browser.fire("keydown", keyEvent("KeyA", "a"));
  let records = drain(browser);
  assert.deepEqual(types(records), [KEY_DOWN], "text input is off by default (SDL3)");
  assert.deepEqual(records[0].i, [4, 97, 0, 0], "scancode 4, keycode 'a', no modifiers, no repeat");

  browser.js.setTextInput(true);
  browser.fire("keydown", keyEvent("KeyB", "b"));
  browser.fire("keydown", keyEvent("ShiftLeft", "Shift", {shiftKey: true}));
  browser.fire("keydown", keyEvent("KeyA", "A", {shiftKey: true}));
  browser.fire("keydown", keyEvent("KeyC", "c", {ctrlKey: true}));
  browser.fire("keydown", keyEvent("Enter", "Enter"));
  browser.fire("keydown", keyEvent("KeyE", "e", {repeat: true}));
  records = drain(browser);
  assert.deepEqual(types(records),
    [KEY_DOWN, TEXT_INPUT, KEY_DOWN, KEY_DOWN, TEXT_INPUT, KEY_DOWN, KEY_DOWN, KEY_DOWN, TEXT_INPUT]);
  assert.equal(records[1].text, "b");
  assert.deepEqual(records[3].i.slice(0, 2), [4, 97], "shifted letters keep the unshifted keycode");
  assert.equal(records[4].text, "A");
  assert.deepEqual(records[5].i.slice(0, 3), [6, 99, 64], "Ctrl+C: no text, LCTRL modifier");
  assert.deepEqual(records[6].i.slice(0, 2), [40, 13], "Enter types no text");
  assert.equal(records[7].i[3], 1, "repeat flag");
  assert.equal(records[8].text, "e", "repeats type text too");

  browser.js.setTextInput(false);
  browser.fire("keydown", keyEvent("KeyD", "d"));
  assert.deepEqual(types(drain(browser)), [KEY_DOWN], "SDL_StopTextInput stops TEXT_INPUT");
});

test("PollEvent is re-entrant: a handler that flushes and polls again sees every record once", () => {
  const browser = started();
  browser.fire("mousemove", {clientX: 10, clientY: 10, movementX: 1, movementY: 1});
  browser.fire("mousedown", {clientX: 10, clientY: 10, button: 0, detail: 1});
  browser.fire("keydown", keyEvent("KeyW", "w"));
  browser.fire("mousemove", {clientX: 20, clientY: 15, movementX: 10, movementY: 5});
  browser.context.innerWidth = 1600;
  browser.context.innerHeight = 900;
  browser.fire("resize");
  browser.fire("mouseup", {clientX: 20, clientY: 15, button: 0, detail: 1});
  const delivered = [];
  // Outer SDLEventHandler.pollEvents loop; the button handler behaves like
  // Minecraft.disconnect -> RenderSystem.pumpEvents: flushInputEvents (SDL_PumpEvents +
  // SDL_FlushEvents(768, 4871)) followed by a nested pollEvents loop.
  for (let record = pollRecord(browser); record; record = pollRecord(browser)) {
    delivered.push(["outer", record.type]);
    if (record.type === BUTTON_DOWN) {
      browser.js.flush(768, 4871);
      for (let inner = pollRecord(browser); inner; inner = pollRecord(browser)) {
        delivered.push(["inner", inner.type]);
      }
    }
  }
  assert.deepEqual(delivered, [
    ["outer", MOTION],
    ["outer", BUTTON_DOWN],
    ["inner", RESIZED],
    ["inner", PIXEL_SIZE_CHANGED],
  ]);
  assert.equal(browser.js.keyState(26), 1, "the flushed KEY_DOWN of W still presses the key");
  assert.equal(browser.js.buttonState(), 0, "the flushed BUTTON_UP still releases the button");
  assert.equal(browser.js.cursorX(), 20, "the flushed motion still moves the cursor");
  assert.equal(browser.js.poll(), 0);
  assert.equal(browser.sdl().head, 0, "the drained queue is compacted");
  assert.equal(browser.context.__gaiusSdlEventHead, 0);

  // Nested polls in the middle of a longer queue: the outer loop continues with what the
  // inner loop left, in order, without duplicates.
  for (let index = 0; index < 6; index++) browser.fire("keydown", keyEvent("Digit" + (index + 1), String(index + 1)));
  const order = [];
  let nested = false;
  for (let record = pollRecord(browser); record; record = pollRecord(browser)) {
    order.push(record.i[0]);
    if (!nested && record.i[0] === 31) {
      nested = true;
      const inner = pollRecord(browser);
      order.push(inner.i[0]);
    }
  }
  assert.deepEqual(order, [30, 31, 32, 33, 34, 35]);
});

test("FlushEvents drops only [min, max], keeps window events and keeps key state consistent", () => {
  const browser = started();
  browser.fire("focus");
  browser.fire("keydown", keyEvent("KeyA", "a"));
  browser.fire("keydown", keyEvent("KeyS", "s"));
  browser.fire("keyup", keyEvent("KeyS", "s"));
  browser.js.setTextInput(true);
  browser.fire("keydown", keyEvent("KeyD", "d"));
  browser.fire("wheel", {deltaX: 0, deltaY: 100, deltaMode: 0});
  browser.context.innerWidth = 800;
  browser.fire("resize");
  // A type above the range (0x2000, SDL_EVENT_RENDER_TARGETS_RESET) survives too.
  browser.sdl().push(browser.sdl().record(0x2000, 0, 0, 0, 0, 0, 0, 0, 0, null));
  const keyChanges = browser.js.flush(768, 4871);
  assert.equal(keyChanges, 4, "four key records applied their state");
  assert.equal(browser.sdl().lastFlushRemoved, 6, "4 key records, 1 text input, 1 wheel");
  assert.deepEqual(types(drain(browser)), [FOCUS_GAINED, RESIZED, PIXEL_SIZE_CHANGED, 0x2000],
    "events outside [768, 4871] survive the input flush in order");
  assert.equal(browser.js.keyState(4), 1, "A is still held");
  assert.equal(browser.js.keyState(22), 0, "S was pressed and released");
  assert.equal(browser.js.keyState(7), 1, "D is still held");
  browser.fire("keyup", keyEvent("KeyA", "a"));
  browser.fire("keyup", keyEvent("KeyD", "d"));
  assert.deepEqual(types(drain(browser)), [KEY_UP, KEY_UP]);
  assert.equal(browser.js.keyState(4), 0);
  assert.equal(browser.js.keyState(7), 0);
});

test("mouse motion coalesces while it is the newest record and sums xrel/yrel", () => {
  const browser = started();
  browser.fire("mousemove", {clientX: 5, clientY: 5, movementX: 1, movementY: 2});
  browser.fire("mousemove", {clientX: 6, clientY: 8, movementX: 2, movementY: 3});
  browser.fire("mousemove", {clientX: 9, clientY: 9, movementX: 3, movementY: -1});
  let records = drain(browser);
  assert.deepEqual(types(records), [MOTION]);
  assert.deepEqual(records[0].f, [9, 9, 6, 4], "latest position, summed relative motion");
  assert.equal(browser.sdl().coalescedMotion, 2);

  browser.fire("mousemove", {clientX: 10, clientY: 10, movementX: 1, movementY: 1});
  browser.fire("mousedown", {clientX: 10, clientY: 10, button: 2, detail: 1});
  browser.fire("mousemove", {clientX: 12, clientY: 10, movementX: 2, movementY: 0});
  records = drain(browser);
  assert.deepEqual(types(records), [MOTION, BUTTON_DOWN, MOTION], "no merge across a button event");
  assert.deepEqual(records[1].i.slice(0, 2), [3, 1], "DOM button 2 is SDL_BUTTON_RIGHT (3), one click");
  assert.equal(records[2].i[0], 4, "motion state carries the right-button mask bit");
  assert.deepEqual(records[2].f, [12, 10, 2, 0]);

  // A motion that is being processed is never modified by later DOM events.
  browser.fire("mousemove", {clientX: 13, clientY: 10, movementX: 1, movementY: 0});
  const first = pollRecord(browser);
  browser.fire("mousemove", {clientX: 14, clientY: 10, movementX: 1, movementY: 0});
  assert.deepEqual(first.f, [13, 10, 1, 0]);
  assert.deepEqual(drain(browser)[0].f, [14, 10, 1, 0]);

  // A click without a preceding mousemove still moves the cursor first.
  browser.fire("mousedown", {clientX: 40, clientY: 30, button: 0, detail: 2});
  records = drain(browser);
  assert.deepEqual(types(records), [MOTION, BUTTON_DOWN]);
  assert.deepEqual(records[0].f.slice(0, 2), [40, 30]);
  assert.equal(records[1].i[1], 2, "double click count");
});

test("resize emits WINDOW_RESIZED (518) and WINDOW_PIXEL_SIZE_CHANGED (519)", () => {
  const browser = started();
  assert.deepEqual(drain(browser), [], "the game's own window size is not an event");
  browser.context.innerWidth = 1024;
  browser.context.innerHeight = 600;
  browser.fire("resize");
  let records = drain(browser);
  assert.deepEqual(types(records), [RESIZED, PIXEL_SIZE_CHANGED]);
  assert.deepEqual(records[0].i.slice(0, 2), [1024, 600], "logical (CSS) size");
  assert.deepEqual(records[1].i.slice(0, 2), [1024, 600], "pixel size at DPR 1");

  browser.fire("resize");
  assert.deepEqual(drain(browser), [], "an unchanged size emits nothing");

  // Launcher DPR policy: same CSS size, larger framebuffer -> only 519.
  browser.context.devicePixelRatio = 2;
  browser.context.__gaiusMaxDpr = 2;
  browser.context.__gaiusApplyCanvasResolution(1024, 600, true);
  records = drain(browser);
  assert.deepEqual(types(records), [PIXEL_SIZE_CHANGED]);
  assert.deepEqual(records[0].i.slice(0, 2), [2048, 1200]);
  assert.equal(browser.js.framebufferWidth(), 2048);
  assert.equal(browser.js.canvasCssWidth(), 1024);

  // SDL_SetWindowSize from the game resizes exactly and emits nothing.
  browser.js.resizeCanvas(900, 500);
  assert.deepEqual(drain(browser), []);
  assert.equal(browser.canvas().style.width, "900px");
  assert.equal(browser.js.framebufferWidth(), 1800);
});

test("fractional DPR and odd viewport sizes stay stable between real display changes", () => {
  const browser = started({dpr: 1.25, width: 2064, height: 864});
  browser.context.__gaiusMaxDpr = 1.25;
  const guiScale = (width, height, setting = 0) => {
    let scale = 1;
    while (scale !== setting && scale < width && scale < height &&
        Math.floor(width / (scale + 1)) >= 320 && Math.floor(height / (scale + 1)) >= 240) scale++;
    return scale;
  };
  browser.context.__gaiusApplyCanvasResolution(2064, 864, true);
  drain(browser);
  assert.deepEqual(
    [browser.js.canvasCssWidth(), browser.js.canvasCssHeight(), browser.js.framebufferWidth(), browser.js.framebufferHeight()],
    [2064, 864, 2580, 1080]);
  const initialScale = guiScale(browser.js.framebufferWidth(), browser.js.framebufferHeight());
  for (let index = 0; index < 120; index++) browser.context.__gaiusApplyCanvasResolution(2064, 864, true);
  assert.deepEqual(drain(browser), [], "stable viewport/DPR does not enqueue resize events");
  assert.equal(guiScale(browser.js.framebufferWidth(), browser.js.framebufferHeight()), initialScale);

  browser.context.innerWidth = 1280;
  browser.context.innerHeight = 721;
  browser.fire("resize");
  assert.deepEqual(types(drain(browser)), [RESIZED, PIXEL_SIZE_CHANGED]);
  assert.deepEqual(
    [browser.js.canvasCssWidth(), browser.js.canvasCssHeight(), browser.js.framebufferWidth(), browser.js.framebufferHeight()],
    [1280, 721, 1600, 901]);
  const resizedScale = guiScale(browser.js.framebufferWidth(), browser.js.framebufferHeight());
  browser.context.devicePixelRatio = 2;
  browser.context.__gaiusMaxDpr = 2;
  browser.context.__gaiusApplyCanvasResolution(1280, 721, true);
  assert.deepEqual(types(drain(browser)), [PIXEL_SIZE_CHANGED]);
  assert.deepEqual([browser.js.framebufferWidth(), browser.js.framebufferHeight()], [2560, 1442]);
  assert.notEqual(guiScale(browser.js.framebufferWidth(), browser.js.framebufferHeight()), resizedScale,
    "a real DPR change updates vanilla auto GUI scale input once");
});

test("focus loss releases held keys and buttons once", () => {
  const browser = started();
  browser.fire("keydown", keyEvent("KeyW", "w"));
  browser.fire("keydown", keyEvent("ShiftLeft", "Shift", {shiftKey: true}));
  browser.fire("mousedown", {clientX: 3, clientY: 4, button: 0, detail: 1});
  drain(browser);
  assert.equal(browser.js.keyState(26), 1);
  assert.equal(browser.js.buttonState(), 1);
  browser.fire("blur");
  const records = drain(browser);
  assert.deepEqual(types(records), [KEY_UP, KEY_UP, BUTTON_UP, FOCUS_LOST]);
  assert.equal(browser.js.keyState(26), 0);
  assert.equal(browser.js.keyState(225), 0);
  assert.equal(browser.js.buttonState(), 0);
  assert.equal(browser.js.focused(), false);
  browser.fire("keyup", keyEvent("KeyW", "w"));
  assert.deepEqual(drain(browser), [], "the physical key-up after the release is dropped");
  browser.fire("focus");
  assert.deepEqual(types(drain(browser)), [FOCUS_GAINED]);
  assert.equal(browser.js.focused(), true);
});

test("modifier state tracks left/right bits and is applied at dequeue", () => {
  const browser = started();
  browser.fire("keydown", keyEvent("ShiftLeft", "Shift", {shiftKey: true}));
  browser.fire("keydown", keyEvent("ControlRight", "Control", {shiftKey: true, ctrlKey: true}));
  assert.equal(browser.js.modState(), 0, "nothing dequeued yet");
  let records = drain(browser);
  assert.equal(records[0].i[2], 1, "LSHIFT");
  assert.equal(records[1].i[2], 1 | 128, "LSHIFT | RCTRL");
  assert.equal(browser.js.modState(), 129);
  browser.fire("keyup", keyEvent("ShiftLeft", "Shift", {ctrlKey: true}));
  records = drain(browser);
  assert.equal(records[0].i[2], 128);
  browser.fire("keydown", keyEvent("KeyA", "A", {getModifierState: (name) => name === "CapsLock"}));
  records = drain(browser);
  assert.equal(records[0].i[2] & 8192, 8192, "caps lock bit");
  assert.equal(records[0].i[1], 97, "caps lock does not shift the keycode");
});

test("keycodes: unshifted US table, scancode mask for non-characters, layout-aware letters", () => {
  const browser = started();
  assert.equal(browser.js.keycodeForScancode(4), 97);
  assert.equal(browser.js.keycodeForScancode(39), 48);
  assert.equal(browser.js.keycodeForScancode(44), 32);
  assert.equal(browser.js.keycodeForScancode(42), 8);
  assert.equal(browser.js.keycodeForScancode(76), 127);
  assert.equal(browser.js.keycodeForScancode(58), 0x40000000 | 58, "F1");
  assert.equal(browser.js.keycodeForScancode(89), 0x40000000 | 89, "keypad 1");
  assert.equal(browser.js.keycodeForScancode(0), 0);
  browser.sdl().layoutMap = new Map([["KeyQ", "a"], ["KeyA", "q"], ["Minus", ")"], ["KeyW", "ц"]]);
  assert.equal(browser.js.keycodeForScancode(20), 97, "AZERTY KeyQ types 'a'");
  assert.equal(browser.js.keycodeForScancode(45), 41, "layout punctuation");
  assert.equal(browser.js.keycodeForScancode(26), 119, "non-Latin letters fall back to Latin");
  browser.fire("keydown", keyEvent("F5", "F5"));
  browser.fire("keydown", keyEvent("Unidentified", "Unidentified"));
  const records = drain(browser);
  assert.deepEqual(records[0].i.slice(0, 2), [62, 0x40000000 | 62]);
  assert.deepEqual(records[1].i.slice(0, 2), [0, 0], "unknown keys are SDL_SCANCODE_UNKNOWN");
});

test("sprint keys: KeyR is SDL scancode 21 and ControlLeft is 224 (LCTRL)", () => {
  const browser = started();
  browser.fire("keydown", keyEvent("KeyR", "r"));
  browser.fire("keyup", keyEvent("KeyR", "r"));
  browser.fire("keydown", keyEvent("ControlLeft", "Control", {ctrlKey: true}));
  const records = drain(browser);
  assert.deepEqual(types(records), [KEY_DOWN, KEY_UP, KEY_DOWN]);
  assert.deepEqual(records[0].i.slice(0, 2), [21, 114], "KeyR -> SDL_SCANCODE_R, keycode 'r'");
  assert.deepEqual(records[1].i.slice(0, 2), [21, 114]);
  assert.deepEqual(records[2].i.slice(0, 2), [224, 0x40000000 | 224], "ControlLeft -> SDL_SCANCODE_LCTRL");
});

test("Ctrl+W forwards both held keys and releases in order", () => {
  const browser = started();
  const controlDown = browser.fire("keydown", keyEvent("ControlLeft", "Control", {ctrlKey: true}));
  const forwardDown = browser.fire("keydown", keyEvent("KeyW", "w", {ctrlKey: true}));
  const forwardUp = browser.fire("keyup", keyEvent("KeyW", "w", {ctrlKey: true}));
  const controlUp = browser.fire("keyup", keyEvent("ControlLeft", "Control"));
  const records = drain(browser);
  assert.equal(forwardDown.defaultPrevented, true, "Ctrl+W must not trigger the browser shortcut");
  assert.deepEqual(types(records), [KEY_DOWN, KEY_DOWN, KEY_UP, KEY_UP]);
  assert.deepEqual(records.map(record => record.i[0]), [224, 26, 26, 224]);
  assert.equal(records[1].i[2] & 64, 64, "W down sees LCTRL held");
  assert.equal(records[2].i[2] & 64, 64, "W up still sees LCTRL held");
  assert.equal(records[3].i[2] & 64, 0, "LCTRL release clears the modifier");
  assert.equal(controlDown.defaultPrevented, true);
  assert.equal(forwardUp.defaultPrevented, false);
  assert.equal(controlUp.defaultPrevented, false);
});

test("wheel uses SDL3 directions and normalizes line/page delta modes", () => {
  const browser = started();
  browser.fire("wheel", {deltaX: 0, deltaY: 100, deltaMode: 0});
  browser.fire("wheel", {deltaX: 50, deltaY: 0, deltaMode: 0});
  browser.fire("wheel", {deltaX: 0, deltaY: -3, deltaMode: 1});
  browser.fire("wheel", {deltaX: 0, deltaY: 1, deltaMode: 2});
  const records = drain(browser);
  assert.deepEqual(types(records), [WHEEL, WHEEL, WHEEL, WHEEL]);
  assert.equal(records[0].f[1], -1, "scrolling down is y < 0");
  assert.equal(records[1].f[0], 0.5, "scrolling right is x > 0 (SDL3)");
  assert.ok(Math.abs(records[2].f[1] - 1) < 1e-9, "three lines up = one notch");
  assert.equal(records[3].f[1], -1, "one page down = one notch");
});

test("input warm-up queues one harmless title-screen click when the queue is empty", () => {
  const browser = started();
  assert.equal(browser.js.poll(), 0, "no warm-up before the title screen");
  browser.context.__gaiusMinecraftState = {screen: "TitleScreen", level: null};
  const records = drain(browser);
  assert.deepEqual(types(records), [MOTION, BUTTON_DOWN, BUTTON_UP]);
  assert.deepEqual(records[0].f.slice(0, 2), [1, 1]);
  assert.equal(records[1].i[0], 1, "left button");
  assert.equal(browser.context.__gaiusInputWarmupDone, true);
  assert.deepEqual(drain(browser), [], "warm-up runs once");
});

test("relative mouse mode requests pointer lock immediately and releases it", () => {
  const browser = started();
  browser.js.setRelativeMouseMode(true);
  assert.equal(browser.context.__gaiusWantPointerLock, true);
  assert.equal(browser.canvas().pointerLockRequests, 1);
  assert.equal(browser.js.relativeMouseMode(), true);
  // Locked: the cursor is virtual and follows movementX/Y; xrel/yrel carry the motion.
  browser.js.warpMouse(100, 50);
  browser.fire("mousemove", {clientX: 0, clientY: 0, movementX: 7, movementY: -3});
  const records = drain(browser);
  assert.deepEqual(records[0].f, [107, 47, 7, -3]);
  assert.equal(browser.context.__gaiusCursorX, 107);
  browser.js.setRelativeMouseMode(false);
  assert.equal(browser.context.__gaiusWantPointerLock, false);
  assert.equal(browser.document.exitPointerLockCount, 1);
});

test("SDL fullscreen is a virtual flag kept consistent with ENTER/LEAVE_FULLSCREEN", () => {
  const browser = started();
  assert.equal(browser.js.setFullscreen(true), true);
  assert.equal(browser.js.fullscreen(), true);
  assert.deepEqual(types(drain(browser)), [ENTER_FULLSCREEN]);
  browser.document.fullscreenElement = null;
  browser.fire("fullscreenchange");
  assert.equal(browser.js.fullscreen(), false, "leaving browser fullscreen clears the flag");
  assert.deepEqual(types(drain(browser)), [LEAVE_FULLSCREEN]);
  assert.equal(browser.js.setFullscreen(false), true, "already windowed");
  assert.deepEqual(drain(browser), []);
  browser.document.documentElement.requestFullscreen = undefined;
  assert.equal(browser.js.setFullscreen(true), false, "no Fullscreen API: SDL_SetWindowFullscreen fails");
  assert.equal(browser.js.fullscreen(), false);
});

test("clipboard round-trips inside the page and captures paste events", () => {
  const browser = started();
  assert.equal(browser.js.readClipboard(), "");
  browser.js.writeClipboard("copied text");
  assert.equal(browser.js.readClipboard(), "copied text");
  assert.deepEqual(browser.clipboardWrites, ["copied text"]);
  browser.canvas().focus();
  const paste = browser.fireDocument("paste", {clipboardData: {getData: (kind) => kind === "text/plain" ? "from os" : ""}});
  assert.equal(browser.js.readClipboard(), "from os");
  assert.equal(paste.defaultPrevented, true);
  const ctrlV = browser.fire("keydown", keyEvent("KeyV", "v", {ctrlKey: true}));
  assert.equal(ctrlV.defaultPrevented, false, "Ctrl+V keeps its default so the paste event fires");
  const ctrlS = browser.fire("keydown", keyEvent("KeyS", "s", {ctrlKey: true}));
  assert.equal(ctrlS.defaultPrevented, true, "other keys on the focused canvas are consumed");
});

test("cursor, title, links and statistics", () => {
  const browser = started();
  browser.js.setCursor(1);
  assert.equal(browser.canvas().style.cursor, "text");
  browser.js.setCursor(11);
  assert.equal(browser.canvas().style.cursor, "pointer");
  browser.js.setTitle("Minecraft* 26.3");
  assert.equal(browser.document.title, "Minecraft* 26.3");
  assert.equal(browser.js.openUrl("https://minecraft.net/"), true);
  assert.equal(browser.js.openUrl("file:///C:/screenshots"), false);
  assert.deepEqual(browser.opened.map((entry) => entry.url), ["https://minecraft.net/"]);
  browser.fire("keydown", keyEvent("KeyA", "a"));
  drain(browser);
  const stats = browser.context.__gaiusInputStats;
  assert.equal(stats.backend, "sdl");
  assert.equal(stats.events[String(KEY_DOWN)], 1);
  assert.equal(stats.lastEvent.type, KEY_DOWN);
  browser.js.setTextInputArea(10, 20, 30, 40, -1);
  assert.deepEqual({...browser.sdl().textInputArea}, {x: 10, y: 20, w: 30, h: 40, cursor: -1});
});

test("the bridge installs once and exposes the queue under its new name", () => {
  const browser = started();
  const sdl = browser.sdl();
  browser.js.installDomBridge();
  assert.equal(browser.sdl(), sdl, "a second SDL_Init keeps the engine");
  browser.fire("keydown", keyEvent("KeyA", "a"));
  assert.equal(browser.context.__gaiusSdlEvents.length - browser.context.__gaiusSdlEventHead, 1);
  assert.equal(browser.context.__gaiusSdlKeys[4], true, "DOM-time key state for tools");
  assert.equal(browser.js.peek(), true);
  drain(browser);
  assert.equal(browser.js.peek(), false);
});

console.log(`lwjgl-sdl browser DOM smoke passed: ${results.length} tests`);
for (const name of results) console.log(`  ok - ${name}`);
