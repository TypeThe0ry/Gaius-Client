#!/usr/bin/env node
// System clipboard bridge: copy and paste between the game's text fields and the OS.
//
//   node port/scripts/clipboard-bridge-smoke.mjs
//
// Runs the real @JSBody scripts of dev.gaius.browser.BrowserClipboard (install) together with
// the installDomBridge and writeClipboard scripts of BrowserGlfw (1.21.11, 26.2) and
// BrowserSdlDom (26.3) in node:vm with a fake DOM, and checks for both shims:
//  - Ctrl+V / Cmd+V / Shift+Insert keep their default and the key records wait for the paste
//    event, so glfwGetClipboardString / SDL_GetClipboardText return the OS text when the game
//    handles the key;
//  - without a paste event (WebKit on a non-editable canvas) navigator.clipboard.readText()
//    supplies the text, and a refused or pending read releases the keys after a timeout;
//  - copies use navigator.clipboard.writeText, then execCommand('copy') with a copy event
//    (no async API, a refused promise), and on WebKit a ClipboardItem armed by the copy chord
//    (Cmd+C, F3+C) inside the key gesture;
//  - on Apple platforms Cmd+A/C/V/X reach the game as Ctrl chords, pressed and released at
//    once, while Windows/Linux Ctrl chords are unchanged.

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const read = path => readFile(new URL(path, import.meta.url), "utf8");
const clipboardSource = await read("../overrides/classlib/src/main/java/dev/gaius/browser/BrowserClipboard.java");
const glfwSource = await read("../overrides/libraries/lwjgl-glfw/src/main/java/org/lwjgl/glfw/BrowserGlfw.java");
const sdlSource = await read("../overrides/libraries/lwjgl-sdl/src/main/java/org/lwjgl/sdl/BrowserSdlDom.java");
const sdlJava = await read("../overrides/libraries/lwjgl-sdl/src/main/java/org/lwjgl/sdl/BrowserSdl.java");

const plain = value => JSON.parse(JSON.stringify(value));

function extractJsBody(source, signature) {
  const markerOffset = source.indexOf(signature);
  assert.ok(markerOffset > 0, `missing ${signature}`);
  const annotationOffset = source.lastIndexOf("@JSBody(", markerOffset);
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(annotationOffset > 0 && scriptEnd > scriptOffset, `JSBody could not be extracted for ${signature}`);
  const script = source.slice(scriptOffset, scriptEnd);
  assert.ok(!script.includes("\\"), `${signature}: keep the script free of Java escapes`);
  return script;
}

const installClipboard = extractJsBody(clipboardSource, "public static native void install();");
const glfwInstall = extractJsBody(glfwSource, "private static native void installDomBridge();");
const glfwWrite = extractJsBody(glfwSource, "private static native void writeClipboard(String value);");
const sdlInstall = extractJsBody(sdlSource, "static native void installDomBridge();");
const sdlWrite = extractJsBody(sdlSource, "static native void writeClipboard(String value);");

// The Java halves install the bridge before the DOM listeners and read the shared text.
assert.match(glfwSource, /BrowserClipboard\.install\(\);\s+installDomBridge\(\);/, "BrowserGlfw.init installs the bridge first");
assert.match(sdlJava, /BrowserClipboard\.install\(\);\s+BrowserSdlDom\.installDomBridge\(\);/, "SDL_Init installs the bridge first");
assert.match(glfwSource, /script = "return window\.__gaiusClipboard\|\|'';"\)\s+private static native String readClipboard\(\);/);
assert.match(sdlSource, /return window\.__gaiusClipboard == null \? '' : String\(window\.__gaiusClipboard\);"\)\s+static native String readClipboard\(\);/);

// ---------------------------------------------------------------- fake DOM

class Target {
  constructor() {
    this.listeners = [];
  }
  addEventListener(type, fn, options) {
    this.listeners.push({type, fn, capture: options === true || !!(options && options.capture)});
  }
  removeEventListener(type, fn) {
    this.listeners = this.listeners.filter(entry => entry.type !== type || entry.fn !== fn);
  }
  dispatch(type, init = {}) {
    const event = Object.assign({
      type, defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; },
      getModifierState: () => false,
      shiftKey: false, ctrlKey: false, altKey: false, metaKey: false, repeat: false, keyCode: 0
    }, init);
    for (const entry of this.listeners.slice()) if (entry.type === type) entry.fn.call(this, event);
    return event;
  }
}

class Element extends Target {
  constructor(doc, tag) {
    super();
    this.ownerDocument = doc;
    this.tagName = tag.toUpperCase();
    this.style = {setProperty(name, value) { this[name] = value; }};
    this.children = [];
    this.attributes = {};
    this.dataset = {};
    this.classList = {add() {}, remove() {}, contains: () => false};
    this.id = "";
    this.width = 854;
    this.height = 480;
  }
  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return this.attributes[name] ?? null; }
  getBoundingClientRect() { return {left: 0, top: 0, width: 854, height: 480}; }
  focus() { this.ownerDocument.activeElement = this; }
  getContext() { return null; }
}

class Doc extends Target {
  constructor() {
    super();
    this.documentElement = new Element(this, "html");
    this.body = this.documentElement.appendChild(new Element(this, "body"));
    this.activeElement = this.body;
    this.visibilityState = "visible";
    this.pointerLockElement = null;
    this.fullscreenElement = null;
    this.copies = [];
    this.execCommandWorks = true;
  }
  createElement(tag) { return new Element(this, tag); }
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
  execCommand(command) {
    if (command !== "copy" || !this.execCommandWorks) return false;
    const data = {};
    this.dispatch("copy", {clipboardData: {setData: (kind, value) => { data[kind] = value; }}});
    this.copies.push(data["text/plain"]);
    return true;
  }
}

const SAFARI = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15";
const CHROME_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
const CHROME_WIN = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

/**
 * options.clipboard: "full" (writeText/readText/write), "none" (no navigator.clipboard, e.g. a
 * non-secure origin), "refusing" (every promise rejects); options.osText is what readText sees.
 */
function environment(backend, options = {}) {
  const timers = [];
  let now = 1000;
  let nextTimer = 1;
  const doc = new Doc();
  const win = new Target();
  const canvas = doc.createElement("canvas");
  canvas.id = "mc-canvas";
  doc.body.appendChild(canvas);
  const os = {text: options.osText ?? "", writes: [], reads: 0, items: [], pendingRead: false};
  const mode = options.clipboard || "full";
  const clipboard = mode === "none" ? undefined : {
    writeText(value) {
      if (mode === "refusing") return Promise.reject(new Error("NotAllowedError"));
      os.writes.push(value);
      os.text = value;
      return Promise.resolve();
    },
    readText() {
      os.reads++;
      if (mode === "refusing") return Promise.reject(new Error("NotAllowedError"));
      if (os.pendingRead) return new Promise(() => {});
      return Promise.resolve(os.text);
    },
    write(items) {
      const item = items[0];
      os.items.push(item);
      return item.types["text/plain"].then(blob => blob.text()).then(text => {
        os.text = text;
        os.writes.push(text);
      });
    }
  };
  class ClipboardItem {
    constructor(types) { this.types = types; }
  }
  const sandbox = {
    console, URLSearchParams, Promise, Blob,
    document: doc,
    navigator: {userAgent: options.userAgent || CHROME_WIN, platform: options.platform || "Win32", maxTouchPoints: 0, clipboard},
    ClipboardItem,
    location: {search: ""},
    localStorage: {getItem: () => null, setItem() {}, removeItem() {}},
    innerWidth: 854, innerHeight: 480, devicePixelRatio: 1,
    screen: {width: 1920, height: 1080, orientation: null},
    performance: {now: () => now},
    setTimeout: (fn, ms) => {
      const id = nextTimer++;
      timers.push({id, at: now + Math.max(0, Number(ms) || 0), fn});
      return id;
    },
    clearTimeout: id => {
      const index = timers.findIndex(timer => timer.id === id);
      if (index >= 0) timers.splice(index, 1);
    },
    setInterval: () => 0,
    clearInterval() {},
    requestAnimationFrame: () => 0,
    matchMedia: () => ({matches: false, addEventListener() {}, removeEventListener() {}}),
    getComputedStyle: () => ({paddingTop: "0px", paddingRight: "0px", paddingBottom: "0px", paddingLeft: "0px"}),
    addEventListener: win.addEventListener.bind(win),
    removeEventListener: win.removeEventListener.bind(win)
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.__gaiusDisplay = {cssWidth: 854, cssHeight: 480, framebufferWidth: 854, framebufferHeight: 480, pixelRatio: 1};
  vm.createContext(sandbox);
  const run = (script, params = {}) => {
    const names = Object.keys(params);
    return vm.runInContext(`(function(${names.join(",")}){${script}\n})`, sandbox)(...names.map(name => params[name]));
  };
  run(installClipboard);
  run(installClipboard); // installs once
  run(backend === "glfw" ? glfwInstall : sdlInstall);
  canvas.focus();
  const w = sandbox;
  const env = {
    w, doc, os, canvas,
    bridge: w.__gaiusClipboardBridge,
    write: value => run(backend === "glfw" ? glfwWrite : sdlWrite, {value}),
    clipboardText: () => String(w.__gaiusClipboard),
    key: (type, code, key, fields = {}) => win.dispatch(type, Object.assign({code, key, target: canvas}, fields)),
    paste: text => doc.dispatch("paste", {target: canvas, clipboardData: {getData: kind => (kind === "text/plain" ? text : "")}}),
    async settle(ms = 0) {
      for (let i = 0; i < 10; i++) await new Promise(resolve => setImmediate(resolve));
      const end = now + ms;
      for (;;) {
        timers.sort((a, b) => a.at - b.at || a.id - b.id);
        const due = timers[0];
        if (!due || due.at > end) break;
        timers.shift();
        now = due.at;
        due.fn();
        for (let i = 0; i < 10; i++) await new Promise(resolve => setImmediate(resolve));
      }
      now = end;
    }
  };
  if (backend === "glfw") {
    // [1, key, legacy, action, mods, 0, 0]; the clipboard text is sampled as the game handles each record.
    env.drain = () => {
      const list = plain(w.__gaiusGlfwEvents ? w.__gaiusGlfwEvents.slice(w.__gaiusGlfwEventHead | 0) : []);
      if (w.__gaiusGlfwEvents) w.__gaiusGlfwEvents.length = 0;
      w.__gaiusGlfwEventHead = 0;
      return list.filter(r => r[0] === 1 || r[0] === 2).map(r => ({kind: r[0] === 1 ? ["up", "down", "repeat"][r[3]] : "text", key: r[1], mods: r[4]}));
    };
    env.keyIsDown = code => !!w.__gaiusGlfwKeys[{KeyV: 86, KeyA: 65, KeyC: 67}[code]];
    env.CTRL = 2;
    env.V = 86;
    env.A = 65;
  } else {
    env.drain = () => {
      const sdl = w.__gaiusSdl;
      const list = [];
      while (sdl.poll()) {
        const r = sdl.current;
        if (r[0] === 768 || r[0] === 769 || r[0] === 771) {
          list.push({kind: r[0] === 768 ? (r[4] ? "repeat" : "down") : (r[0] === 769 ? "up" : "text"), key: r[2], mods: r[3]});
        }
      }
      return plain(list);
    };
    env.keyIsDown = code => !!w.__gaiusSdlKeys[{KeyV: 25, KeyA: 4, KeyC: 6}[code]];
    env.CTRL = 64;
    env.V = 118;
    env.A = 97;
  }
  return env;
}

const results = [];
async function test(name, body) {
  for (const backend of ["glfw", "sdl"]) {
    await body(backend);
    results.push(`${backend}: ${name}`);
  }
}

await test("Ctrl+V waits for the paste event, then the game reads the OS text", async backend => {
  const env = environment(backend);
  env.write("old in-game text");
  await env.settle();
  assert.equal(env.clipboardText(), "old in-game text");
  env.drain();
  const ctrl = env.key("keydown", "ControlLeft", "Control", {ctrlKey: true});
  assert.equal(ctrl.defaultPrevented, true);
  const down = env.key("keydown", "KeyV", "v", {ctrlKey: true});
  assert.equal(down.defaultPrevented, false, "Ctrl+V keeps its default so the browser fires paste");
  const held = env.drain();
  assert.equal(held.length, 1, "only the Ctrl press is queued while the paste is pending");
  const paste = env.paste("text from the OS");
  assert.equal(paste.defaultPrevented, true, "the paste event is consumed on the focused canvas");
  assert.equal(env.clipboardText(), "text from the OS");
  const released = env.drain();
  assert.deepEqual(released.map(r => [r.kind, r.key, (r.mods & env.CTRL) !== 0]), [["down", env.V, true]]);
  env.key("keyup", "KeyV", "v", {ctrlKey: true});
  assert.deepEqual(env.drain().map(r => r.kind), ["up"]);
  await env.settle(5000);
  assert.equal(env.os.reads, 0, "no readText when the paste event arrived");
  assert.equal(env.bridge.stats.pasteEvents, 1);
});

await test("Shift+Insert and keyups that arrive while the paste is pending keep their order", async backend => {
  const env = environment(backend);
  env.drain();
  env.key("keydown", "Insert", "Insert", {shiftKey: true});
  env.key("keyup", "Insert", "Insert", {shiftKey: true});
  assert.deepEqual(env.drain().filter(r => r.key !== 0), [], "both records wait for the paste text");
  env.paste("pasted by Shift+Insert");
  assert.deepEqual(env.drain().map(r => r.kind), ["down", "up"]);
  assert.equal(env.clipboardText(), "pasted by Shift+Insert");
});

await test("no paste event: readText supplies the text before the key is delivered", async backend => {
  const env = environment(backend, {osText: "from readText"});
  env.drain();
  env.key("keydown", "KeyV", "v", {ctrlKey: true});
  assert.deepEqual(env.drain(), []);
  await env.settle(0);
  assert.equal(env.os.reads, 1);
  assert.equal(env.clipboardText(), "from readText");
  assert.deepEqual(env.drain().map(r => r.kind), ["down"]);
});

await test("a refused or pending readText releases the keys with the old text", async backend => {
  const refused = environment(backend, {clipboard: "refusing"});
  refused.write("kept");
  refused.drain();
  refused.key("keydown", "KeyV", "v", {ctrlKey: true});
  await refused.settle(0);
  assert.equal(refused.clipboardText(), "kept");
  assert.deepEqual(refused.drain().map(r => r.kind), ["down"]);

  const pending = environment(backend, {osText: "never"});
  pending.os.pendingRead = true;
  pending.drain();
  pending.key("keydown", "KeyV", "v", {ctrlKey: true});
  await pending.settle(100);
  assert.deepEqual(pending.drain(), [], "keys wait while the permission prompt is open");
  await pending.settle(3000);
  assert.deepEqual(pending.drain().map(r => r.kind), ["down"], "the timeout releases them");
  pending.key("keydown", "KeyS", "s");
  assert.deepEqual(pending.drain().filter(r => r.kind !== "text").map(r => r.kind), ["down"], "later keys are not held");
});

await test("copies reach the OS through writeText", async backend => {
  const env = environment(backend);
  env.key("keydown", "KeyC", "c", {ctrlKey: true});
  env.write("selected text");
  await env.settle();
  assert.deepEqual(env.os.writes, ["selected text"]);
  assert.equal(env.os.items.length, 0, "Chromium does not need the ClipboardItem path");
  assert.equal(env.clipboardText(), "selected text", "the game reads its own copy back at once");
});

await test("without navigator.clipboard or with a refused writeText the copy event is used", async backend => {
  const none = environment(backend, {clipboard: "none"});
  none.write("insecure origin copy");
  assert.deepEqual(none.doc.copies, ["insecure origin copy"]);
  assert.equal(none.clipboardText(), "insecure origin copy");

  const refused = environment(backend, {clipboard: "refusing"});
  refused.write("refused copy");
  await refused.settle();
  assert.deepEqual(refused.doc.copies, ["refused copy"]);
  assert.equal(refused.bridge.stats.legacyWrites, 1);
});

await test("WebKit: the copy chord arms a ClipboardItem inside the key gesture", async backend => {
  const env = environment(backend, {userAgent: SAFARI, platform: "MacIntel"});
  assert.equal(env.bridge.webkitGesture, true);
  env.key("keydown", "KeyC", "c", {metaKey: true});
  assert.equal(env.os.items.length, 1, "the write starts during the keydown");
  env.write("copied in Safari");
  await env.settle();
  assert.equal(env.os.text, "copied in Safari");
  assert.deepEqual(env.os.writes, ["copied in Safari"], "no second writeText");
  // F3+C (copy location) arms the same way.
  env.key("keydown", "F3", "F3");
  env.key("keydown", "KeyC", "c");
  assert.equal(env.os.items.length, 2);
  env.write("/execute in minecraft:overworld run tp @s 1 2 3");
  await env.settle();
  assert.equal(env.os.text, "/execute in minecraft:overworld run tp @s 1 2 3");
  env.key("keyup", "KeyC", "c");
  env.key("keyup", "F3", "F3");
  // An armed write that the game never completes expires and leaves the clipboard alone.
  env.key("keydown", "KeyC", "c", {metaKey: true});
  await env.settle(2500);
  assert.equal(env.bridge.armed, null);
  assert.equal(env.os.text, "/execute in minecraft:overworld run tp @s 1 2 3");
  env.write("after expiry");
  await env.settle();
  assert.equal(env.os.text, "after expiry");

  const chrome = environment(backend, {userAgent: CHROME_MAC, platform: "MacIntel"});
  assert.equal(chrome.bridge.webkitGesture, false);
});

await test("macOS: Cmd+V/A/C/X reach the game as Ctrl chords, pressed and released", async backend => {
  const env = environment(backend, {userAgent: CHROME_MAC, platform: "MacIntel"});
  assert.equal(env.bridge.apple, true);
  env.drain();
  env.key("keydown", "MetaLeft", "Meta", {metaKey: true});
  const cmdV = env.key("keydown", "KeyV", "v", {metaKey: true});
  assert.equal(cmdV.defaultPrevented, false, "Cmd+V keeps its default");
  env.paste("mac text");
  const records = env.drain().filter(r => r.key === env.V);
  assert.deepEqual(records.map(r => [r.kind, (r.mods & env.CTRL) !== 0]), [["down", true], ["up", true]]);
  assert.equal(env.keyIsDown("KeyV"), false, "no stuck key: macOS drops the keyup while Cmd is held");
  assert.equal(env.clipboardText(), "mac text");
  const cmdA = env.key("keydown", "KeyA", "a", {metaKey: true});
  assert.equal(cmdA.defaultPrevented, true);
  assert.deepEqual(env.drain().map(r => [r.kind, r.key, (r.mods & env.CTRL) !== 0]),
    [["down", env.A, true], ["up", env.A, true]], "select all");
  assert.equal(env.keyIsDown("KeyA"), false, "A (strafe left) is not left pressed");
  env.key("keyup", "KeyA", "a", {metaKey: true});
  env.key("keyup", "MetaLeft", "Meta");
  env.drain();
  // Cmd with another key is unchanged.
  env.key("keydown", "KeyQ", "q", {metaKey: true});
  assert.deepEqual(env.drain().map(r => [r.kind, (r.mods & env.CTRL) !== 0]), [["down", false]]);
});

await test("Windows/Linux: Ctrl chords are unchanged and Meta is never remapped", async backend => {
  const env = environment(backend);
  assert.equal(env.bridge.apple, false);
  env.drain();
  env.key("keydown", "KeyA", "a", {ctrlKey: true});
  assert.deepEqual(env.drain().map(r => [r.kind, (r.mods & env.CTRL) !== 0]), [["down", true]]);
  assert.equal(env.keyIsDown("KeyA"), true);
  env.key("keyup", "KeyA", "a", {ctrlKey: true});
  assert.deepEqual(env.drain().map(r => r.kind), ["up"]);
  env.key("keydown", "KeyC", "c", {metaKey: true});
  assert.deepEqual(env.drain().map(r => [r.kind, (r.mods & env.CTRL) !== 0]), [["down", false]]);
});

await test("Escape is never swallowed: delivered at once, or in order behind a pending paste", async backend => {
  const env = environment(backend);
  const ESC = backend === "glfw" ? 256 : 27;
  env.drain();
  env.key("keydown", "Escape", "Escape");
  env.key("keyup", "Escape", "Escape");
  assert.deepEqual(env.drain().map(r => [r.kind, r.key]), [["down", ESC], ["up", ESC]]);
  env.key("keydown", "KeyV", "v", {ctrlKey: true});
  env.key("keydown", "Escape", "Escape");
  assert.deepEqual(env.drain(), [], "Escape waits behind the pending paste chord");
  env.paste("x");
  assert.deepEqual(env.drain().map(r => [r.kind, r.key]), [["down", env.V], ["down", ESC]]);
});

for (const name of results) console.log(`  ok - ${name}`);
console.log(`clipboard bridge smoke passed (${results.length} checks)`);
