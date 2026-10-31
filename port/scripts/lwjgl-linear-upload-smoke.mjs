#!/usr/bin/env node
// Node smoke for the (ptr,len) uploads of the WebAssembly GC build (v0.4.0 WP4, design D7).
// On WebAssembly GC the per-frame GL and AL paths write their data into linear memory (or pass
// the address of a direct buffer) and the @JSBody receives (address, count); JS reads it through
// the cached views of globalThis.__gaiusLinear instead of a new typed array per call. The
// JavaScript build keeps its zero-copy typed-array bodies, selected by the compile-time constant
// JsArrays.LINEAR_MEMORY. This smoke:
//   - installs the linear views (JsArrays target-wasmgc) over a real WebAssembly.Memory and
//     checks that growing the memory detaches them and the next upload refreshes them;
//   - runs every linear GL body (uniform*v / uniformMatrix*fv through one body, clearBufferfv,
//     drawBuffers, the terrain multi-draw) and the linear AL bufferData against a mock WebGL /
//     Web Audio context, next to the typed-array body of the same call, and requires the same
//     WebGL calls, the same uniform value cache decisions and the same decoded audio;
//   - checks that repeated linear uploads reuse their JS-side arrays (no per-call typed array),
//     including the cached Float64Array views of JsArrays.float64 (packed telemetry);
//   - checks the Java side: each per-frame entry point has a LINEAR_MEMORY branch that calls a
//     linear body, and the two JsArrays implementations declare the constant true/false.
//
//   node port/scripts/lwjgl-linear-upload-smoke.mjs

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const read = relative => readFile(new URL(relative, import.meta.url), "utf8");
const [gl, al, jsArraysWasm, jsArraysJs] = await Promise.all([
  read("../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java"),
  read("../overrides/libraries/lwjgl-openal/src/main/java/org/lwjgl/openal/BrowserOpenAL.java"),
  read("../overrides/classlib/src/target-wasmgc/java/dev/gaius/browser/JsArrays.java"),
  read("../overrides/classlib/src/main/java/dev/gaius/browser/JsArrays.java"),
]);

function jsBody(source, declaration) {
  const at = source.indexOf(declaration);
  assert.notEqual(at, -1, `missing JSBody declaration: ${declaration}`);
  const header = source.lastIndexOf("@JSBody(", at);
  const marker = 'script = """';
  const start = source.indexOf(marker, header) + marker.length;
  const end = source.indexOf('""")', start);
  assert.ok(header >= 0 && end > start && end < at, `missing JSBody script: ${declaration}`);
  const paramsText = source.slice(header, source.indexOf(marker, header));
  const params = [...paramsText.matchAll(/"([A-Za-z0-9_]+)"/g)].map(match => match[1]);
  return new Function(...params, source.slice(start, end));
}

// --- Java side ---------------------------------------------------------------------------------
assert.match(jsArraysWasm, /public static final boolean LINEAR_MEMORY = true;/);
assert.match(jsArraysJs, /public static final boolean LINEAR_MEMORY = false;/);
assert.ok(!/copyFromJavaArray/.test(jsArraysWasm.slice(jsArraysWasm.indexOf("public static int scratch("))),
  "the (ptr,len) staging must not create typed arrays");
function methodBodies(source, signaturePattern) {
  const bodies = [];
  for (const match of source.matchAll(signaturePattern)) {
    let depth = 0;
    let index = source.indexOf("{", match.index);
    const start = index;
    for (; index < source.length; index++) {
      if (source[index] === "{") depth++;
      else if (source[index] === "}" && --depth === 0) break;
    }
    bodies.push({signature: match[0], body: source.slice(start, index + 1)});
  }
  return bodies;
}
const perFrameGl = methodBodies(gl, /public static (?:void|int) (?:uniform[1-4][fi]v|uniformMatrix[2-4]fv|clearBufferfv|drawBuffers|terrainMultiDraw)\([^)]*\)/g);
assert.equal(perFrameGl.length, 33 + 3 + 4 + 1, "per-frame GL entry points");
for (const {signature, body} of perFrameGl) {
  assert.match(body, /if \(JsArrays\.LINEAR_MEMORY\) \{[\s\S]*?(?:Linear(?:Js)?)\(/,
    `${signature} needs a LINEAR_MEMORY branch with a linear body`);
  const linearBranch = body.slice(body.indexOf("if (JsArrays.LINEAR_MEMORY)"));
  const branchEnd = linearBranch.indexOf("\n        }\n");
  assert.ok(!/JsArrays\.(?:int8|int16|int32|float32|float64)\(|\b(?:floats|ints|pointerFloats|pointerInts)\(/
    .test(linearBranch.slice(0, branchEnd)), `${signature}: linear branch must not copy into a typed array`);
}
const perFrameAl = methodBodies(al, /public static void bufferData\([^)]*\)/g);
assert.equal(perFrameAl.length, 7, "AL bufferData overloads");
for (const {signature, body} of perFrameAl) {
  assert.match(body, /if \(JsArrays\.LINEAR_MEMORY\) \{[\s\S]*?bufferDataLinearJs\(/,
    `${signature} needs a LINEAR_MEMORY branch`);
}
assert.match(gl, /if \(JsArrays\.LINEAR_MEMORY\) \{\n\s+JsArrays\.installLinearViews\(\);\n\s+initializeLinearUploadsJs\(\);/,
  "GL initialize must install the linear views on WebAssembly GC");

// --- linear views ---------------------------------------------------------------------------------
const memory = new WebAssembly.Memory({initial: 1, maximum: 16});
globalThis.__gaiusWasmMemory = memory;
globalThis.window = globalThis;
delete globalThis.__gaiusLinear;
const installLinearViews = jsBody(jsArraysWasm, "private static native void installLinearViewsJs(");
installLinearViews();
const linear = globalThis.__gaiusLinear;
assert.equal(linear.u8.length, 65536);
installLinearViews();
assert.equal(globalThis.__gaiusLinear, linear, "installLinearViews is idempotent");
const f32 = () => new Float32Array(memory.buffer);
// JsArrays.float64 (WebAssembly GC): one cached view per length over the staging area.
const float64View = jsBody(jsArraysWasm, "private static native Float64Array float64ViewJs(");
new Float64Array(memory.buffer).set([1.5, -2, 3e9], 512 >> 3);
const packed = float64View(512, 3);
assert.deepEqual(Array.from(packed), [1.5, -2, 3e9]);
assert.equal(float64View(512, 3), packed, "float64 views are cached per length");
const i32 = () => new Int32Array(memory.buffer);

// --- WebGL mock -------------------------------------------------------------------------------------
// The mock reads array arguments as WebGL does (srcOffset/srcLength honoured).
function uniformValues(view, offset = 0, length = view.length - offset) {
  return Array.from(view.subarray(offset, offset + (length || view.length - offset)));
}
const webGl = {COLOR: 0x1800};
const glCalls = [];
for (const name of ["uniform1fv", "uniform2fv", "uniform3fv", "uniform4fv", "uniform1iv",
  "uniform2iv", "uniform3iv", "uniform4iv"]) {
  webGl[name] = (object, view, offset, length) => glCalls.push([name, object,
    uniformValues(view, offset, length)]);
}
for (const name of ["uniformMatrix2fv", "uniformMatrix3fv", "uniformMatrix4fv"]) {
  webGl[name] = (object, transpose, view, offset, length) => glCalls.push([name, object, transpose,
    uniformValues(view, offset, length)]);
}
webGl.clearBufferfv = (buffer, drawBuffer, view, offset = 0) => glCalls.push(["clearBufferfv",
  buffer, drawBuffer, Array.from(view.subarray(offset, offset + (buffer === 0x1800 ? 4 : 1)))]);
webGl.drawBuffers = list => glCalls.push(["drawBuffers", Array.from(list)]);
globalThis.__gaiusWebGL = webGl;
const state = {
  uniforms: new Map([[3, "u3"], [4, "u4"]]),
  uniform1iValues: new Map(),
  uniform1fValues: new Map(),
  framebufferBindings: {draw: 0},
  findFramebufferColorTextureId: () => 0,
  invalidateGuiItemAtlasBlitCache() {},
};
globalThis.__gaiusGL = state;
jsBody(gl, "private static native void initializeUniformValueCacheJs(")();
jsBody(gl, "private static native void initializeLinearUploadsJs(")();
state.hotPathTelemetryEnabled = true;

const typed = {
  101: jsBody(gl, "private static native void uniform1fvJs("),
  102: jsBody(gl, "private static native void uniform2fvJs("),
  103: jsBody(gl, "private static native void uniform3fvJs("),
  104: jsBody(gl, "private static native void uniform4fvJs("),
  201: jsBody(gl, "private static native void uniform1ivJs("),
  202: jsBody(gl, "private static native void uniform2ivJs("),
  203: jsBody(gl, "private static native void uniform3ivJs("),
  204: jsBody(gl, "private static native void uniform4ivJs("),
  302: jsBody(gl, "private static native void uniformMatrix2fvJs("),
  303: jsBody(gl, "private static native void uniformMatrix3fvJs("),
  304: jsBody(gl, "private static native void uniformMatrix4fvJs("),
};
const uniformLinear = jsBody(gl, "private static native void uniformLinearJs(");
const sizes = {101: 1, 102: 2, 103: 3, 104: 4, 201: 1, 202: 2, 203: 3, 204: 4, 302: 4, 303: 9, 304: 16};
let address = 1024;
for (const [kindText, size] of Object.entries(sizes)) {
  const kind = Number(kindText);
  const integer = kind >= 200 && kind < 300;
  const values = Array.from({length: size * 2}, (_, i) => integer ? i * 3 - 7 : i * 0.25 - 1);
  const matrix = kind >= 300;
  const runTyped = (location, data) => matrix ? typed[kind](location, true, data) : typed[kind](location, data);
  // Typed path on location 3, linear path on location 4, same data.
  const typedArray = integer ? Int32Array.from(values) : Float32Array.from(values);
  (integer ? i32() : f32()).set(typedArray, address >> 2);
  glCalls.length = 0;
  runTyped(3, typedArray);
  uniformLinear(4, kind, matrix, address, values.length);
  assert.equal(glCalls.length, 2, `kind ${kind}: both paths upload`);
  const [typedCall, linearCall] = glCalls;
  assert.deepEqual(linearCall.slice(2), typedCall.slice(2), `kind ${kind}: same WebGL arguments`);
  // Same values again: both caches skip.
  glCalls.length = 0;
  runTyped(3, typedArray);
  uniformLinear(4, kind, matrix, address, values.length);
  assert.equal(glCalls.length, 0, `kind ${kind}: unchanged values are skipped on both paths`);
  // One value changes: both upload.
  typedArray[size] = integer ? 99 : 99.5;
  (integer ? i32() : f32())[(address >> 2) + size] = typedArray[size];
  runTyped(3, typedArray);
  uniformLinear(4, kind, matrix, address, values.length);
  assert.equal(glCalls.length, 2, `kind ${kind}: a changed value uploads on both paths`);
  address += 256;
}
// Unknown location: nothing happens.
glCalls.length = 0;
uniformLinear(99, 104, false, 1024, 4);
assert.equal(glCalls.length, 0);

// clearBufferfv
const clearTyped = jsBody(gl, "private static native void clearBufferfvJs(");
const clearLinear = jsBody(gl, "private static native void clearBufferfvLinearJs(");
f32().set([0.25, 0.5, 0.75, 1], 2048 >> 2);
glCalls.length = 0;
clearTyped(0x1800, 0, Float32Array.of(0.25, 0.5, 0.75, 1));
clearLinear(0x1800, 0, 2048);
assert.deepEqual(glCalls[1], glCalls[0], "clearBufferfv linear == typed");

// drawBuffers
const drawTyped = jsBody(gl, "private static native void drawBuffersJs(");
const drawLinear = jsBody(gl, "private static native void drawBuffersLinearJs(");
i32().set([0x8ce0, 0x8ce1], 4096 >> 2);
glCalls.length = 0;
drawTyped(Int32Array.of(0x8ce0, 0x8ce1));
const typedLast = state.lastDrawBuffers;
drawLinear(4096, 2);
assert.deepEqual(glCalls[1], glCalls[0], "drawBuffers linear == typed");
assert.deepEqual(state.lastDrawBuffers, typedLast);
const list = state.linearDrawBufferLists[2];
const lastBefore = state.lastDrawBuffers;
drawLinear(4096, 2);
assert.equal(state.linearDrawBufferLists[2], list, "drawBuffers reuses its list");
assert.equal(state.lastDrawBuffers, lastBefore, "unchanged draw buffers keep lastDrawBuffers");
assert.equal(state.drawBuffersCalls, 3);

// terrain multi-draw: the linear body hands the same records to terrainBatchDraw.
const terrainTyped = jsBody(gl, "private static native int terrainMultiDrawJs(");
const terrainLinear = jsBody(gl, "private static native int terrainMultiDrawLinearJs(");
const seen = [];
state.terrainBatchDraw = (kind, data, drawCount, leadSerial, leadCount, leadBaseVertex) => {
  seen.push({kind, data: Array.from(data.subarray(0, drawCount * 8)), drawCount, leadSerial,
    leadCount, leadBaseVertex});
  return drawCount;
};
const records = Int32Array.from({length: 3 * 8}, (_, i) => i * 11 - 5);
i32().set(records, 8192 >> 2);
assert.equal(terrainTyped(1, records, 3, 7, 36, 4), 3);
assert.equal(terrainLinear(1, 8192, 3, 7, 36, 4), 3);
assert.deepEqual(seen[1], seen[0], "terrain linear == typed");
const terrainScratch = state.terrainLinearRecords;
terrainLinear(1, 8192, 2, 7, 36, 4);
assert.equal(state.terrainLinearRecords, terrainScratch, "terrain records scratch is reused");

// Memory growth detaches the cached views; the next upload refreshes them.
const viewsBefore = linear.f32;
memory.grow(1);
assert.equal(viewsBefore.length, 0, "growing the memory detaches the old view");
const packedAfterGrow = float64View(512, 3);
assert.notEqual(packedAfterGrow, packed, "a detached float64 view is replaced");
assert.deepEqual(Array.from(packedAfterGrow), [1.5, -2, 3e9]);
f32().set([1, 2, 3, 4], (65536 + 64) >> 2);
glCalls.length = 0;
uniformLinear(4, 104, false, 65536 + 64, 4);
assert.deepEqual(glCalls[0].slice(2), [[1, 2, 3, 4]]);
assert.equal(linear.f32.length, 2 * 65536 / 4, "views cover the grown memory");
assert.ok(linear.refreshes >= 2);

// --- OpenAL bufferData ---------------------------------------------------------------------------
class AudioBufferMock {
  constructor(channels, frames) { this.channels = Array.from({length: channels}, () => new Float32Array(frames)); }
  getChannelData(channel) { return this.channels[channel]; }
}
const makeAl = () => ({
  buffers: new Map(), stats: {bufferUploads: 0},
  ensureContext: () => ({createBuffer: (channels, frames) => new AudioBufferMock(channels, frames)}),
});
const alTyped = jsBody(al, "private static native void bufferDataJs(");
const alLinear = jsBody(al, "private static native void bufferDataLinearJs(");
for (const [format, bytes] of [
  [0x1101, Int16Array.of(0, 16384, -16384, 32767, -32768, 1)],
  [0x1103, Int16Array.of(1000, -1000, 2000, -2000)],
  [0x1100, Uint8Array.of(0, 64, 128, 255)],
  [0x1102, Uint8Array.of(10, 20, 30, 40, 50, 60)],
]) {
  const raw = new Uint8Array(bytes.buffer.slice(0));
  new Uint8Array(memory.buffer).set(raw, 12288);
  globalThis.__gaiusOpenAL = makeAl();
  alTyped(5, format, new Int8Array(raw.buffer), 22050);
  const typedEntry = globalThis.__gaiusOpenAL.buffers.get(5);
  globalThis.__gaiusOpenAL = makeAl();
  alLinear(5, format, 12288, raw.length, 22050);
  const linearEntry = globalThis.__gaiusOpenAL.buffers.get(5);
  assert.deepEqual({...linearEntry, audio: null}, {...typedEntry, audio: null}, `AL format ${format}`);
  assert.deepEqual(linearEntry.audio.channels.map(c => Array.from(c)),
    typedEntry.audio.channels.map(c => Array.from(c)), `AL samples ${format}`);
}
globalThis.__gaiusOpenAL = makeAl();
alLinear(6, 0x1101, 0, 0, 44100);
assert.equal(globalThis.__gaiusOpenAL.buffers.get(6).size, 0, "no data uploads an empty buffer");

delete globalThis.__gaiusOpenAL;
delete globalThis.__gaiusGL;
delete globalThis.__gaiusWebGL;
delete globalThis.__gaiusLinear;
delete globalThis.__gaiusWasmMemory;
console.log("LWJGL linear upload smoke passed", JSON.stringify({
  glEntryPoints: perFrameGl.length, alEntryPoints: perFrameAl.length, uniformKinds: 11,
  refreshes: linear.refreshes,
}));
