#!/usr/bin/env node
// Node smoke for the linear-memory byte uploads of BrowserOpenGL on WebAssembly GC
// (bufferData, bufferSubData, namedBuffer(Sub)Data, texImage2D, texSubImage2D): runs each
// *LinearJs body next to its typed-array body against a mock WebGL and the real buffer helpers
// (shadowing, quad index tracking), and requires the same bytes in every WebGL call and helper,
// without a per-call typed array where none is needed.
//
//   node port/scripts/lwjgl-linear-byte-upload-smoke.mjs
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const gl = readFileSync(root + "/port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java", "utf8").replace(/\r\n/g, "\n");
const jsArraysWasm = readFileSync(root + "/port/overrides/classlib/src/target-wasmgc/java/dev/gaius/browser/JsArrays.java", "utf8");
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
// window.__gaiusGL.<name>=function(...) {...} from the source.
function stateFunction(name) {
  const marker = `window.__gaiusGL.${name}=function`;
  const at = gl.indexOf(marker);
  assert.notEqual(at, -1, name);
  let index = gl.indexOf("{", at);
  let depth = 0;
  for (; index < gl.length; index++) {
    if (gl[index] === "{") depth++;
    else if (gl[index] === "}" && --depth === 0) break;
  }
  return new Function("return " + gl.slice(at + marker.length - "function".length, index + 1))();
}

const memory = new WebAssembly.Memory({initial: 4});
globalThis.__gaiusWasmMemory = memory;
globalThis.window = globalThis;
jsBody(jsArraysWasm, "private static native void installLinearViewsJs(")();
const u8 = () => new Uint8Array(memory.buffer);

const calls = [];
const bytesOf = (src, srcOffset = 0, length = 0) => {
  const view = new Uint8Array(src.buffer, src.byteOffset, src.byteLength);
  return Array.from(view.subarray(srcOffset, length ? srcOffset + length : view.length));
};
const webGl = {
  ELEMENT_ARRAY_BUFFER: 0x8893, ARRAY_BUFFER: 0x8892, COPY_WRITE_BUFFER: 0x8F37,
  COPY_READ_BUFFER: 0x8F36,
  bufferData(target, src, usage, srcOffset, length) {
    calls.push(["bufferData", target, typeof src === "number" ? src : bytesOf(src, srcOffset, length), usage]);
  },
  bufferSubData(target, offset, src, srcOffset, length) {
    calls.push(["bufferSubData", target, offset, bytesOf(src, srcOffset, length)]);
  },
  texImage2D(target, level, internalFormat, width, height, border, format, type, src, srcOffset = 0) {
    calls.push(["texImage2D", target, level, internalFormat, width, height, border, format, type,
      src.constructor.name, bytesOf(src, src.BYTES_PER_ELEMENT * srcOffset, width * height * 4)]);
  },
  texSubImage2D(target, level, x, y, width, height, format, type, src, srcOffset = 0) {
    calls.push(["texSubImage2D", target, level, x, y, width, height, format, type,
      src.constructor.name, bytesOf(src, src.BYTES_PER_ELEMENT * srcOffset, width * height * 4)]);
  },
  bindBuffer(target, buffer) { calls.push(["bindBuffer", target, buffer]); },
  getExtension() { return null; },
  getParameter() { return 1; },
  isContextLost() { return false; },
};
globalThis.__gaiusWebGL = webGl;

const prepareSource = (() => {
  const at = gl.indexOf("state.prepareBufferDataUpload=function");
  const start = gl.indexOf("function", at);
  const end = gl.indexOf("\n            };", start);
  return gl.slice(start, end + "\n            }".length);
})();

function makeState(shadowed) {
  const helperCalls = [];
  const state = {
    boundBuffers: new Map([[0x8892, 7], [0x8893, 8], [0x8A11, 9], [0x8F37, 7]]),
    buffers: new Map([[7, "B7"], [8, "B8"], [9, "B9"], [10, "B10"]]),
    bufferSizes: new Map([[7, 4096], [8, 4096], [9, 4096], [10, 4096]]),
    shadowRequiredBuffers: new Set(shadowed ? [10] : []),
    bufferBytes: new Map(),
    getVaoEmu: () => null,
    ensureLogicalElementBuffer() {},
    shouldShadowBufferTarget: () => shadowed,
    dropBufferShadow(buffer, reason, offset, length) { helperCalls.push(["drop", buffer, reason, offset, length]); return false; },
    bumpBufferVersion(buffer, offset, length) { helperCalls.push(["bump", buffer, offset, length]); },
    shadowBufferData(buffer, data, size) { helperCalls.push(["shadowData", buffer, data ? bytesOf(data).slice(0, size) : null, size]); },
    shadowBufferSubData(buffer, offset, data) { helperCalls.push(["shadowSub", buffer, offset, bytesOf(data)]); },
    noteBufferUpload(source, uploaded, operation) { helperCalls.push(["upload", source, uploaded, operation]); },
    noteBufferWebglType(target, buffer) { helperCalls.push(["type", target, buffer]); },
    noteNamedBufferBindings(a, b) { helperCalls.push(["named", a, b]); },
    boundTextureId: () => 3,
    textureInfo: new Map(),
    invalidateGuiItemAtlasBlitCache() {},
    refreshFramebuffersForTexture() {},
    uniformBufferPadScratch: null,
  };
  state.shadowBufferDataForTarget = stateFunction("shadowBufferDataForTarget");
  state.shadowBufferSubDataForTarget = stateFunction("shadowBufferSubDataForTarget");
  state.recordTextureUpload = stateFunction("recordTextureUpload");
  state.recordTextureError = stateFunction("recordTextureError");
  state.prepareBufferDataUpload = new Function("return " + prepareSource)();
  globalThis.__gaiusGL = state;
  // noteBufferBytes and the quad tracker come from their initializer.
  jsBody(gl, "private static native void initializeTerrainBatchJs(")();
  jsBody(gl, "private static native void initializeLinearUploadsJs(")();
  return {state, helperCalls};
}

const bodies = {
  bufferData: [jsBody(gl, "private static native void bufferDataJs("), jsBody(gl, "private static native void bufferDataLinearJs(")],
  bufferSubData: [jsBody(gl, "private static native void bufferSubDataJs("), jsBody(gl, "private static native void bufferSubDataLinearJs(")],
  namedBufferData: [jsBody(gl, "private static native void namedBufferDataJs("), jsBody(gl, "private static native void namedBufferDataLinearJs(")],
  namedBufferSubData: [jsBody(gl, "private static native void namedBufferSubDataJs("), jsBody(gl, "private static native void namedBufferSubDataLinearJs(")],
  texImage2D: [jsBody(gl, "private static native void texImage2DJs("), jsBody(gl, "private static native void texImage2DLinearJs(")],
  texSubImage2D: [jsBody(gl, "private static native void texSubImage2DJs("), jsBody(gl, "private static native void texSubImage2DLinearJs(")],
};

let cases = 0;
function compare(name, runTyped, runLinear, shadowed) {
  const a = makeState(shadowed);
  calls.length = 0;
  runTyped(a.state);
  const typedCalls = calls.splice(0);
  const typedQuads = JSON.stringify([...a.state.quadPatterns]);
  const typedStats = JSON.stringify(globalThis.__gaiusGLStats || null);
  globalThis.__gaiusGLStats = undefined;
  const b = makeState(shadowed);
  runLinear(b.state);
  const linearCalls = calls.splice(0);
  assert.deepEqual(linearCalls, typedCalls, `${name}: WebGL calls`);
  assert.deepEqual(b.helperCalls, a.helperCalls, `${name}: helper calls`);
  assert.equal(JSON.stringify([...b.state.quadPatterns]), typedQuads, `${name}: quad patterns`);
  const linearStats = JSON.stringify(globalThis.__gaiusGLStats || null);
  globalThis.__gaiusGLStats = undefined;
  assert.equal(linearStats.replace(/"at":\d+/g, ""), typedStats.replace(/"at":\d+/g, ""), `${name}: stats`);
  cases++;
}

const address = 4096 + 64;
for (const length of [0, 12, 100, 300, 2048]) {
  for (const shadowed of [false, true]) {
    // A quad index pattern (0,1,2,2,3,0, 4,5,6,6,7,4, ...) as 16-bit indices.
    const data = new Uint8Array(length);
    const quad = [0, 1, 2, 2, 3, 0];
    for (let i = 0; i < length >> 1; i++) {
      const value = quad[i % 6] + Math.floor(i / 6) * 4;
      data[2 * i] = value & 255;
      data[2 * i + 1] = value >> 8;
    }
    u8().fill(0xEE);
    u8().set(data, address);
    for (const target of [0x8892, 0x8893, 0x8A11]) {
      compare(`bufferData ${target} ${length} ${shadowed}`,
        () => bodies.bufferData[0](target, new Int8Array(data.buffer.slice(0)), 0x88E4),
        () => bodies.bufferData[1](target, address, length, 0x88E4), shadowed);
      for (const offset of [0, 512]) {
        compare(`bufferSubData ${target} ${offset} ${length} ${shadowed}`,
          () => bodies.bufferSubData[0](target, offset, new Int8Array(data.buffer.slice(0))),
          () => bodies.bufferSubData[1](target, offset, address, length), shadowed);
      }
    }
    for (const buffer of [7, 10]) {
      compare(`namedBufferData ${buffer} ${length} ${shadowed}`,
        () => bodies.namedBufferData[0](buffer, new Int8Array(data.buffer.slice(0)), 0x88E4),
        () => bodies.namedBufferData[1](buffer, address, length, 0x88E4), shadowed);
      for (const offset of [0, 512]) {
        compare(`namedBufferSubData ${buffer} ${offset} ${length} ${shadowed}`,
          () => bodies.namedBufferSubData[0](buffer, offset, new Int8Array(data.buffer.slice(0))),
          () => bodies.namedBufferSubData[1](buffer, offset, address, length), shadowed);
      }
    }
  }
}
// Sequences in one state: a store that creates a quad entry, then ranged writes that extend,
// keep or truncate it (the quad tracker reads the bytes of a ranged write only then).
{
  const quadBytes = (count, start = 0, break_ = -1) => {
    const out = new Uint8Array(count * 2);
    const quad = [0, 1, 2, 2, 3, 0];
    for (let i = 0; i < count; i++) {
      const index = start + i;
      const value = i === break_ ? 999 : quad[index % 6] + Math.floor(index / 6) * 4;
      out[2 * i] = value & 255;
      out[2 * i + 1] = value >> 8;
    }
    return out;
  };
  const steps = [
    [0, quadBytes(60)],
    [120, quadBytes(30, 60)],
    [240, quadBytes(30, 120, 7)],
    [48, quadBytes(12, 24)],
  ];
  for (const named of [false, true]) {
    for (const shadowed of [false, true]) {
      compare(`sequence named=${named} ${shadowed}`, (state) => {
        const first = new Int8Array(steps[0][1].buffer.slice(0));
        if (named) bodies.namedBufferData[0](7, first, 0x88E4); else bodies.bufferData[0](0x8893, first, 0x88E4);
        for (const [offset, bytes] of steps.slice(1)) {
          const view = new Int8Array(bytes.buffer.slice(0));
          if (named) bodies.namedBufferSubData[0](7, offset, view); else bodies.bufferSubData[0](0x8893, offset, view);
        }
      }, (state) => {
        let at = address;
        u8().set(steps[0][1], at);
        if (named) bodies.namedBufferData[1](7, at, steps[0][1].length, 0x88E4);
        else bodies.bufferData[1](0x8893, at, steps[0][1].length, 0x88E4);
        for (const [offset, bytes] of steps.slice(1)) {
          at += 1024;
          u8().set(bytes, at);
          if (named) bodies.namedBufferSubData[1](7, offset, at, bytes.length);
          else bodies.bufferSubData[1](0x8893, offset, at, bytes.length);
        }
      }, shadowed);
    }
  }
  const probe = makeState(false);
  bodies.bufferData[1](0x8893, address, 120, 0x88E4);
  assert.ok(probe.state.quadPatterns.size > 0, "the sequence exercises the quad tracker");
}
// Textures: 4x4 RGBA of UNSIGNED_BYTE, and the BYTE (signed) RED path.
const pixels = Uint8Array.from({length: 64}, (_, i) => (i * 37) & 255);
u8().set(pixels, address);
for (const [format, type] of [[0x1908, 0x1401], [0x1903, 0x1400]]) {
  compare(`texImage2D ${type}`,
    () => bodies.texImage2D[0](0x0DE1, 0, 0x8058, 4, 4, 0, format, type, new Int8Array(pixels.buffer.slice(0))),
    () => bodies.texImage2D[1](0x0DE1, 0, 0x8058, 4, 4, 0, format, type, address, 64), false);
  compare(`texSubImage2D ${type}`,
    () => bodies.texSubImage2D[0](0x0DE1, 0, 1, 2, 4, 4, format, type, new Int8Array(pixels.buffer.slice(0))),
    () => bodies.texSubImage2D[1](0x0DE1, 0, 1, 2, 4, 4, format, type, address, 64), false);
}
// The plain path reuses its byte count record.
const {state} = makeState(false);
const record = state.linearRangeInfo(1);
bodies.bufferSubData[1](0x8892, 512, address, 100);
assert.equal(state.linearRangeInfo(1), record, "the byte count record is reused");
console.log(`gl byte upload smoke passed: ${cases} cases`);
