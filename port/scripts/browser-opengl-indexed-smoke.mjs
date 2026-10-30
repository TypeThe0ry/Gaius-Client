#!/usr/bin/env node
// Node smoke for the LWJGL 3.4.3 OpenGL entry points that Minecraft 26.3 added
// (GL30C.glEnablei/glDisablei, GL11C.glReadBuffer), implemented by
// port/overrides/libraries/lwjgl-opengl/src/versions/3.4.3/.../BrowserOpenGLIndexed.java.
//
// Runs the @JSBody scripts against a mock WebGL2 context:
//   - indexed enable/disable goes through OES_draw_buffers_indexed for every index, keeps
//     BrowserOpenGL's capability cache coherent (the next global enable/disable must reach
//     WebGL) and mirrors index 0 into the tracked state;
//   - without the extension, index 0 asks the Java side to fall back to the global
//     enable/disable and other indices are counted as unsupported;
//   - readBuffer maps the desktop front/back names to BACK on the default framebuffer only;
//   - no script repeats a top-level lexical name (TeaVM 0.15 rejects that in one @JSBody).
// It also checks that the Java wrappers fall back exactly when the script returns false.
//
//   node port/scripts/browser-opengl-indexed-smoke.mjs

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const sourcePath = fileURLToPath(new URL(
  "../overrides/libraries/lwjgl-opengl/src/versions/3.4.3/java/org/lwjgl/opengl/"
    + "BrowserOpenGLIndexed.java", import.meta.url));
const source = await readFile(sourcePath, "utf8");

function jsBody(declaration) {
  const at = source.indexOf(declaration);
  assert.notEqual(at, -1, `missing declaration: ${declaration}`);
  const header = source.lastIndexOf("@JSBody(", at);
  const params = /params\s*=\s*\{([^}]*)\}/.exec(source.slice(header, at));
  const start = source.indexOf('script = """', header) + 'script = """'.length;
  const end = source.indexOf('""")', start);
  assert.ok(header >= 0 && start > header && end > start && end < at,
    `malformed @JSBody for ${declaration}`);
  const names = params ? params[1].split(",").map((name) => name.trim().replaceAll('"', ""))
    .filter(Boolean) : [];
  const script = source.slice(start, end);
  const topLevel = [...script.replace(/\{[^{}]*\}/g, "").matchAll(
    /\b(?:const|let)\s+([A-Za-z_$][\w$]*(?:\s*=[^,;]*)?(?:\s*,\s*[A-Za-z_$][\w$]*(?:\s*=[^,;]*)?)*)/g)]
    .flatMap((match) => match[1].split(",").map((part) => part.trim().split(/\s|=/)[0]));
  assert.deepEqual(topLevel.filter((name, index) => topLevel.indexOf(name) !== index), [],
    `${declaration} repeats a top-level lexical name`);
  return new Function(...names, script);
}

const indexedCapability = jsBody(
  "private static native boolean indexedCapabilityJs(int target, int index, boolean enable);");
const readBuffer = jsBody("public static native void readBuffer(int mode);");

// The Java wrappers must fall back to the global capability exactly when the script says so.
for (const [wrapper, fallback] of [["enablei", "enable"], ["disablei", "disable"]]) {
  const body = new RegExp(`public static void ${wrapper}\\(int target, int index\\) \\{\\s*`
    + `if \\(!indexedCapabilityJs\\(target, index, (true|false)\\)\\) \\{\\s*`
    + `BrowserOpenGL\\.${fallback}\\(target\\);\\s*\\}\\s*\\}`).exec(source);
  assert.ok(body, `${wrapper} does not fall back to BrowserOpenGL.${fallback}`);
  assert.equal(body[1], wrapper === "enablei" ? "true" : "false",
    `${wrapper} passes the wrong enable flag`);
}

const BLEND = 0x0BE2;
const calls = [];
function mockContext({withExtension}) {
  const extension = {
    enableiOES(capability, index) { calls.push(["enableiOES", capability, index]); },
    disableiOES(capability, index) { calls.push(["disableiOES", capability, index]); },
  };
  return {
    BACK: 0x0405,
    getExtension(name) {
      calls.push(["getExtension", name]);
      return withExtension && name === "OES_draw_buffers_indexed" ? extension : null;
    },
    readBuffer(mode) { calls.push(["readBuffer", mode]); },
  };
}
function mockState() {
  return {
    knownCaps: new Set([BLEND]),
    enabledCaps: new Set(),
    enabledCapBits: 0,
    capabilityBit: (capability) => (capability === BLEND ? 4 : 0),
    framebufferBindings: {draw: 0, read: 0},
  };
}

const previousWindow = globalThis.window;
try {
  // With OES_draw_buffers_indexed: every index is indexed, the cache is invalidated.
  calls.length = 0;
  globalThis.window = {__gaiusWebGL: mockContext({withExtension: true}), __gaiusGL: mockState()};
  let state = globalThis.window.__gaiusGL;
  assert.equal(indexedCapability(BLEND, 0, true), true);
  assert.deepEqual(calls.filter((call) => call[0] !== "getExtension"),
    [["enableiOES", BLEND, 0]]);
  assert.ok(!state.knownCaps.has(BLEND), "indexed enable left the global cache authoritative");
  assert.ok(state.enabledCaps.has(BLEND) && (state.enabledCapBits & 4) === 4,
    "index 0 enable is not mirrored into the tracked state");
  state.knownCaps.add(BLEND);
  assert.equal(indexedCapability(BLEND, 2, false), true);
  assert.deepEqual(calls.at(-1), ["disableiOES", BLEND, 2]);
  assert.ok(!state.knownCaps.has(BLEND), "indexed disable left the global cache authoritative");
  assert.ok(state.enabledCaps.has(BLEND), "index 2 changed the tracked index 0 state");
  assert.equal(indexedCapability(BLEND, 0, false), true);
  assert.ok(!state.enabledCaps.has(BLEND) && (state.enabledCapBits & 4) === 0,
    "index 0 disable is not mirrored into the tracked state");
  assert.equal(calls.filter((call) => call[0] === "getExtension").length, 1,
    "the extension lookup is not cached");

  // Without the extension: index 0 falls back, other indices are counted.
  calls.length = 0;
  globalThis.window = {__gaiusWebGL: mockContext({withExtension: false}), __gaiusGL: mockState()};
  state = globalThis.window.__gaiusGL;
  assert.equal(indexedCapability(BLEND, 0, true), false,
    "index 0 without the extension must fall back to the global enable");
  assert.equal(indexedCapability(BLEND, 1, true), true);
  assert.equal(globalThis.window.__gaiusGLStats.indexedCapabilityUnsupported, 1);
  assert.ok(state.knownCaps.has(BLEND), "a fallback must leave the global cache to enable()");
  assert.equal(calls.filter((call) => call[0] === "getExtension").length, 1,
    "a missing extension is not cached");

  // No Gaius GL state yet (first calls): still usable.
  globalThis.window = {__gaiusWebGL: mockContext({withExtension: true})};
  assert.equal(indexedCapability(BLEND, 0, true), true);

  // readBuffer.
  calls.length = 0;
  globalThis.window = {__gaiusWebGL: mockContext({withExtension: true}), __gaiusGL: mockState()};
  for (const desktop of [0x0400, 0x0402, 0x0404, 0x0408]) readBuffer(desktop);
  readBuffer(0);
  readBuffer(0x8CE0);
  globalThis.window.__gaiusGL.framebufferBindings.read = 7;
  readBuffer(0x0404);
  readBuffer(0x8CE0);
  assert.deepEqual(calls.map((call) => call[1]),
    [0x0405, 0x0405, 0x0405, 0x0405, 0, 0x8CE0, 0x0404, 0x8CE0],
    "readBuffer did not map desktop names on the default framebuffer only");
} finally {
  globalThis.window = previousWindow;
}

console.log("BrowserOpenGLIndexed smoke passed");
