#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const sourcePath = path.resolve(
  scriptDir,
  "../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java",
);
const source = fs.readFileSync(sourcePath, "utf8");

function jsBody(method) {
  const escaped = method.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const declaration = new RegExp(
    `(?:private|public)\\s+static\\s+native\\s+[\\w<>\\[\\]]+\\s+${escaped}\\s*\\(`,
  ).exec(source);
  assert.ok(declaration, `missing native declaration for ${method}`);
  const annotationStart = source.lastIndexOf("@JSBody", declaration.index);
  assert.ok(annotationStart >= 0, `missing @JSBody for ${method}`);
  const marker = 'script = """';
  const bodyStart = source.indexOf(marker, annotationStart);
  assert.ok(bodyStart >= 0 && bodyStart < declaration.index, `missing JS script for ${method}`);
  const contentStart = bodyStart + marker.length;
  const bodyEnd = source.indexOf('"""', contentStart);
  assert.ok(bodyEnd >= 0 && bodyEnd < declaration.index, `unterminated JS script for ${method}`);
  return source.slice(contentStart, bodyEnd);
}

function run(method, parameters, args) {
  const callable = Function(...parameters, jsBody(method));
  return callable(...args);
}

const calls = {
  activeTexture: 0,
  bindBuffer: [],
  bufferData: 0,
  bufferSubData: 0,
  invalidBufferSubData: 0,
  copyBufferSubData: 0,
  invalidCopyBufferSubData: 0,
  clearBufferfv: [],
  drawBuffers: [],
  getParameter: 0,
  pixelStorei: [],
  texImage2D: [],
  texSubImage2D: [],
  texParameteri: [],
};
const physicalBindings = new Map();
const bufferStorage = new Map();
const pixelStore = new Map();

function copyBytes(value) {
  if (typeof value === "number") return new Uint8Array(value);
  if (!value) return new Uint8Array(0);
  return Uint8Array.from(
    new Uint8Array(value.buffer, value.byteOffset || 0, value.byteLength),
  );
}

const gl = {
  ARRAY_BUFFER: 0x8892,
  ELEMENT_ARRAY_BUFFER: 0x8893,
  COPY_READ_BUFFER: 0x8f36,
  COPY_WRITE_BUFFER: 0x8f37,
  TEXTURE_2D: 0x0de1,
  TEXTURE0: 0x84c0,
  TEXTURE_MIN_FILTER: 0x2801,
  TEXTURE_MAG_FILTER: 0x2800,
  TEXTURE_WRAP_S: 0x2802,
  TEXTURE_WRAP_T: 0x2803,
  TEXTURE_BASE_LEVEL: 0x813c,
  TEXTURE_MAX_LEVEL: 0x813d,
  NEAREST: 0x2600,
  CLAMP_TO_EDGE: 0x812f,
  RED: 0x1903,
  RGBA: 0x1908,
  RGBA8: 0x8058,
  UNSIGNED_BYTE: 0x1401,
  BYTE: 0x1400,
  COLOR: 0x1800,
  UNPACK_ROW_LENGTH: 0x0cf2,
  UNPACK_SKIP_ROWS: 0x0cf3,
  UNPACK_SKIP_PIXELS: 0x0cf4,
  UNPACK_ALIGNMENT: 0x0cf5,
  bindBuffer(target, object) {
    calls.bindBuffer.push({ target, object });
    physicalBindings.set(target, object || null);
  },
  bufferData(target, data) {
    calls.bufferData += 1;
    const object = physicalBindings.get(target);
    assert.ok(object, `bufferData target ${target} has no physical buffer`);
    bufferStorage.set(object.id, copyBytes(data));
  },
  bufferSubData(target, offset, data) {
    calls.bufferSubData += 1;
    const object = physicalBindings.get(target);
    assert.ok(object, `bufferSubData target ${target} has no physical buffer`);
    const sourceBytes = copyBytes(data);
    const targetBytes = bufferStorage.get(object.id);
    if (
      !targetBytes ||
      !Number.isInteger(offset) ||
      offset < 0 ||
      offset + sourceBytes.byteLength > targetBytes.byteLength
    ) {
      calls.invalidBufferSubData += 1;
      return;
    }
    targetBytes.set(sourceBytes, offset);
    bufferStorage.set(object.id, targetBytes);
  },
  copyBufferSubData(sourceTarget, targetTarget, sourceOffset, targetOffset, size) {
    calls.copyBufferSubData += 1;
    const sourceObject = physicalBindings.get(sourceTarget);
    const targetObject = physicalBindings.get(targetTarget);
    const sourceBytes = bufferStorage.get(sourceObject.id);
    const targetBytes = bufferStorage.get(targetObject.id);
    if (
      !sourceBytes ||
      !targetBytes ||
      !Number.isInteger(sourceOffset) ||
      !Number.isInteger(targetOffset) ||
      !Number.isInteger(size) ||
      sourceOffset < 0 ||
      targetOffset < 0 ||
      size < 0 ||
      sourceOffset + size > sourceBytes.byteLength ||
      targetOffset + size > targetBytes.byteLength ||
      (sourceObject === targetObject && size > 0 &&
        sourceOffset < targetOffset + size && targetOffset < sourceOffset + size)
    ) {
      calls.invalidCopyBufferSubData += 1;
      return;
    }
    targetBytes.set(sourceBytes.slice(sourceOffset, sourceOffset + size), targetOffset);
    bufferStorage.set(targetObject.id, targetBytes);
  },
  clearBufferfv(buffer, drawBuffer, values) {
    calls.clearBufferfv.push({ buffer, drawBuffer, values: Float32Array.from(values) });
  },
  drawBuffers(values) {
    calls.drawBuffers.push(Int32Array.from(values));
  },
  activeTexture() {
    calls.activeTexture += 1;
  },
  getParameter() {
    calls.getParameter += 1;
    throw new Error("synchronous getParameter is forbidden in the texBuffer hot path");
  },
  pixelStorei(parameter, value) {
    calls.pixelStorei.push({ parameter, value });
    pixelStore.set(parameter, value);
  },
  texParameteri(target, parameter, value) {
    calls.texParameteri.push({ target, parameter, value });
  },
  texImage2D(target, level, internalFormat, width, height, border, format, type, pixels) {
    calls.texImage2D.push({
      target,
      level,
      internalFormat,
      width,
      height,
      border,
      format,
      type,
      pixels,
      bytes: copyBytes(pixels),
    });
  },
  texSubImage2D(target, level, x, y, width, height, format, type, pixels) {
    calls.texSubImage2D.push({
      target,
      level,
      x,
      y,
      width,
      height,
      format,
      type,
      pixels,
      bytes: copyBytes(pixels),
    });
  },
};

const objects = new Map([
  [1, { id: 1 }],
  [2, { id: 2 }],
  [3, { id: 3 }],
]);
const textureObject = { id: 10 };
const state = {
  // The hot-path counters are recorded only when the page enables the telemetry.
  hotPathTelemetryEnabled: true,
  activeTextureUnit: 0,
  boundBuffers: new Map(),
  bufferBytes: new Map(),
  bufferSizes: new Map(),
  bufferWebglTypes: new Map(),
  bufferVersionBumps: 0,
  bufferShadowDrops: 0,
  buffers: objects,
  shadowRequiredBuffers: new Set([1, 2, 3]),
  shadowCompleteRequired: new Set(),
  bufferZeroFresh: new Set(),
  textureBindings: new Map(),
  textureBufferInfo: new Map(),
  textureParameters: new Map(),
  textures: new Map([[10, textureObject]]),
  framebufferBindings: { draw: 0, read: 0 },
  shouldShadowBufferTarget() {
    return true;
  },
  markBufferShadowRequired(buffer) {
    this.shadowRequiredBuffers.add(buffer | 0);
  },
  noteZeroAllocation() {},
  shadowedRange() {
    return null;
  },
  ensureBufferShadow(buffer) {
    const current = this.bufferBytes.get(buffer | 0);
    if (current) return current;
    const physical = bufferStorage.get(buffer | 0);
    if (!physical) return null;
    const copy = copyBytes(physical);
    this.bufferBytes.set(buffer | 0, copy);
    return copy;
  },
  shadowBufferDataForTarget(_target, buffer, data, size) {
    this.bufferBytes.set(buffer, data ? copyBytes(data) : new Uint8Array(size));
  },
  shadowBufferSubDataForTarget(_target, buffer, offset, data) {
    let bytes = this.bufferBytes.get(buffer) || new Uint8Array(0);
    if (bytes.byteLength < offset + data.byteLength) {
      const grown = new Uint8Array(offset + data.byteLength);
      grown.set(bytes);
      bytes = grown;
    }
    bytes.set(copyBytes(data), offset);
    this.bufferBytes.set(buffer, bytes);
  },
  shadowBufferData(buffer, data, size) {
    this.bufferBytes.set(buffer, data ? copyBytes(data) : new Uint8Array(size));
  },
  shadowBufferSubData(buffer, offset, data) {
    this.shadowBufferSubDataForTarget(0, buffer, offset, data);
  },
  bumpBufferVersion() {
    this.bufferVersionBumps += 1;
  },
  dropBufferShadow(buffer) {
    this.bufferShadowDrops += 1;
    this.bufferBytes.delete(buffer);
    return true;
  },
  recordTextureUpload() {},
  recordTextureError(_kind, _target, _level, _width, _height, _format, _type, _pixels, error) {
    throw error;
  },
};

globalThis.window = {
  __gaiusGL: state,
  __gaiusGLStats: {},
  __gaiusWebGL: gl,
};

const typeHelperStart = source.indexOf("window.__gaiusGL.noteBufferWebglType=function");
const typeHelperEnd = source.indexOf("window.__gaiusGL.crossKindBufferCopy=function", typeHelperStart);
assert.ok(typeHelperStart >= 0 && typeHelperEnd > typeHelperStart);
Function(source.slice(typeHelperStart, typeHelperEnd))();

run("drawBuffersJs", ["buffers"], [new Int32Array([0x8ce0, 0x8ce1])]);
assert.deepEqual([...calls.drawBuffers.at(-1)], [0x8ce0, 0x8ce1]);

run(
  "clearBufferfvJs",
  ["buffer", "drawBuffer", "values"],
  [gl.COLOR, 0, new Float32Array([0.25, 0.5, 0.75, 1])],
);
assert.equal(calls.clearBufferfv.at(-1).buffer, gl.COLOR);
assert.deepEqual([...calls.clearBufferfv.at(-1).values], [0.25, 0.5, 0.75, 1]);

run("initializeGpuHotPathJs", [], []);
assert.equal(state.unpackAlignment, 4);
assert.equal(state.unpackRowLength, 0);

function bindLogical(target, id) {
  state.boundBuffers.set(target, id);
  physicalBindings.set(target, id ? objects.get(id) : null);
}

bindLogical(0x8a11, 1);
run("bufferDataJs", ["target", "data", "usage"], [0x8a11, new Int8Array([1, 2, 3]), 0x88e8]);
const paddingScratch = state.uniformBufferPadScratch;
assert.equal(bufferStorage.get(1).byteLength, 256);
assert.deepEqual([...bufferStorage.get(1).slice(0, 5)], [1, 2, 3, 0, 0]);
assert.deepEqual([...state.bufferBytes.get(1).slice(0, 5)], [1, 2, 3, 0, 0]);

run("bufferDataJs", ["target", "data", "usage"], [0x8a11, new Int8Array([4, 5]), 0x88e8]);
assert.strictEqual(state.uniformBufferPadScratch, paddingScratch);
assert.deepEqual([...bufferStorage.get(1).slice(0, 5)], [4, 5, 0, 0, 0]);
assert.equal(window.__gaiusGLStats.uniformBufferPadScratchAllocations, 1);
assert.equal(window.__gaiusGLStats.uniformBufferPadScratchReuses, 1);
assert.equal(window.__gaiusGLStats.bufferDataCalls, 2);
assert.equal(window.__gaiusGLStats.bufferUploadSourceBytes, 5);
assert.equal(window.__gaiusGLStats.bufferUploadBytes, 512);

bindLogical(gl.COPY_WRITE_BUFFER, 2);
const bindCountBeforeSameNamedUpload = calls.bindBuffer.length;
run("namedBufferDataJs", ["buffer", "data", "usage"], [2, new Int8Array([9, 8]), 0x88e8]);
assert.equal(calls.bindBuffer.length, bindCountBeforeSameNamedUpload);
assert.equal(state.boundBuffers.get(gl.COPY_WRITE_BUFFER), 2);
assert.deepEqual([...bufferStorage.get(2).slice(0, 4)], [9, 8, 0, 0]);

const bindCountBeforeDifferentNamedUpload = calls.bindBuffer.length;
run("namedBufferDataJs", ["buffer", "data", "usage"], [3, new Int8Array([7, 6, 5]), 0x88e8]);
assert.equal(calls.bindBuffer.length - bindCountBeforeDifferentNamedUpload, 2);
assert.strictEqual(physicalBindings.get(gl.COPY_WRITE_BUFFER), objects.get(2));
assert.equal(state.boundBuffers.get(gl.COPY_WRITE_BUFFER), 2);
assert.deepEqual([...bufferStorage.get(3).slice(0, 5)], [7, 6, 5, 0, 0]);

const bindCountBeforeSameSubData = calls.bindBuffer.length;
run("namedBufferSubDataJs", ["buffer", "offset", "data"], [2, 4, new Int8Array([3, 4])]);
assert.equal(calls.bindBuffer.length, bindCountBeforeSameSubData);
assert.deepEqual([...bufferStorage.get(2).slice(0, 7)], [9, 8, 0, 0, 3, 4, 0]);
assert.equal(window.__gaiusGLStats.namedBufferBindSkips, 4);
assert.equal(window.__gaiusGLStats.namedBufferPhysicalBinds, 2);

function snapshotBufferState(buffer) {
  const shadow = state.bufferBytes.get(buffer);
  return {
    size: state.bufferSizes.get(buffer),
    shadow,
    shadowBytes: shadow ? copyBytes(shadow) : null,
    physical: copyBytes(bufferStorage.get(buffer)),
    bumps: state.bufferVersionBumps,
    drops: state.bufferShadowDrops,
  };
}

function assertBufferStateUnchanged(buffer, before) {
  assert.equal(state.bufferSizes.get(buffer), before.size);
  assert.strictEqual(state.bufferBytes.get(buffer), before.shadow);
  assert.deepEqual(
    [...(state.bufferBytes.get(buffer) || [])],
    [...(before.shadowBytes || [])],
  );
  assert.deepEqual([...bufferStorage.get(buffer)], [...before.physical]);
  assert.equal(state.bufferVersionBumps, before.bumps);
  assert.equal(state.bufferShadowDrops, before.drops);
}

const logicalInvalidSubData = snapshotBufferState(1);
run("bufferSubDataJs", ["target", "offset", "data"], [0x8a11, 255, new Int8Array([41, 42])]);
assert.equal(calls.invalidBufferSubData, 1);
assertBufferStateUnchanged(1, logicalInvalidSubData);

run("bufferSubDataJs", ["target", "offset", "data"], [0x8a11, 254, new Int8Array([43, 44])]);
assert.equal(calls.invalidBufferSubData, 1);
assert.equal(state.bufferSizes.get(1), logicalInvalidSubData.size);
assert.deepEqual([...bufferStorage.get(1).slice(254, 256)], [43, 44]);
assert.deepEqual([...state.bufferBytes.get(1).slice(254, 256)], [43, 44]);

const logicalZeroLengthSubData = snapshotBufferState(1);
run("bufferSubDataJs", ["target", "offset", "data"], [0x8a11, 256, new Int8Array(0)]);
assert.equal(calls.invalidBufferSubData, 1);
assertBufferStateUnchanged(1, logicalZeroLengthSubData);

const namedInvalidSubData = snapshotBufferState(2);
run("namedBufferSubDataJs", ["buffer", "offset", "data"], [2, 255, new Int8Array([51, 52])]);
assert.equal(calls.invalidBufferSubData, 2);
assertBufferStateUnchanged(2, namedInvalidSubData);

run("namedBufferSubDataJs", ["buffer", "offset", "data"], [2, 254, new Int8Array([53, 54])]);
assert.equal(calls.invalidBufferSubData, 2);
assert.equal(state.bufferSizes.get(2), namedInvalidSubData.size);
assert.deepEqual([...bufferStorage.get(2).slice(254, 256)], [53, 54]);
assert.deepEqual([...state.bufferBytes.get(2).slice(254, 256)], [53, 54]);

const namedZeroLengthSubData = snapshotBufferState(2);
run("namedBufferSubDataJs", ["buffer", "offset", "data"], [2, 256, new Int8Array(0)]);
assert.equal(calls.invalidBufferSubData, 2);
assertBufferStateUnchanged(2, namedZeroLengthSubData);

bindLogical(gl.COPY_READ_BUFFER, 1);
bindLogical(gl.COPY_WRITE_BUFFER, 2);
bufferStorage.set(1, new Uint8Array([10, 11, 12, 13]));
const bindCountBeforeCopy = calls.bindBuffer.length;
run(
  "copyNamedBufferSubData",
  ["sourceBuffer", "targetBuffer", "sourceOffset", "targetOffset", "size"],
  [1, 2, 1, 8, 2],
);
assert.equal(calls.bindBuffer.length, bindCountBeforeCopy);
assert.deepEqual([...bufferStorage.get(2).slice(8, 10)], [11, 12]);
assert.equal(state.boundBuffers.get(gl.COPY_READ_BUFFER), 1);
assert.equal(state.boundBuffers.get(gl.COPY_WRITE_BUFFER), 2);

bufferStorage.set(3, new Uint8Array([20, 21, 22, 23]));
const bindCountBeforeDifferentCopy = calls.bindBuffer.length;
run(
  "copyNamedBufferSubData",
  ["sourceBuffer", "targetBuffer", "sourceOffset", "targetOffset", "size"],
  [3, 1, 1, 0, 2],
);
assert.equal(calls.bindBuffer.length - bindCountBeforeDifferentCopy, 4);
assert.deepEqual([...bufferStorage.get(1).slice(0, 2)], [21, 22]);
assert.strictEqual(physicalBindings.get(gl.COPY_READ_BUFFER), objects.get(1));
assert.strictEqual(physicalBindings.get(gl.COPY_WRITE_BUFFER), objects.get(2));
assert.equal(state.boundBuffers.get(gl.COPY_READ_BUFFER), 1);
assert.equal(state.boundBuffers.get(gl.COPY_WRITE_BUFFER), 2);
assert.equal(window.__gaiusGLStats.namedBufferBindSkips, 14);
assert.equal(window.__gaiusGLStats.namedBufferPhysicalBinds, 6);

const namedInvalidCopy = snapshotBufferState(2);
run(
  "copyNamedBufferSubData",
  ["sourceBuffer", "targetBuffer", "sourceOffset", "targetOffset", "size"],
  [1, 2, 0, 255, 2],
);
assert.equal(calls.invalidCopyBufferSubData, 1);
assertBufferStateUnchanged(2, namedInvalidCopy);

run(
  "copyNamedBufferSubData",
  ["sourceBuffer", "targetBuffer", "sourceOffset", "targetOffset", "size"],
  [1, 2, 0, 254, 2],
);
assert.equal(calls.invalidCopyBufferSubData, 1);
assert.equal(state.bufferSizes.get(2), namedInvalidCopy.size);
assert.deepEqual([...bufferStorage.get(2).slice(254, 256)], [21, 22]);
assert.deepEqual([...state.bufferBytes.get(2).slice(254, 256)], [6, 5]);

const genericInvalidCopy = snapshotBufferState(2);
run(
  "copyBufferSubData",
  ["sourceTarget", "targetTarget", "sourceOffset", "targetOffset", "size"],
  [gl.COPY_READ_BUFFER, gl.COPY_WRITE_BUFFER, 255, 0, 2],
);
assert.equal(calls.invalidCopyBufferSubData, 2);
assertBufferStateUnchanged(2, genericInvalidCopy);

run(
  "copyBufferSubData",
  ["sourceTarget", "targetTarget", "sourceOffset", "targetOffset", "size"],
  [gl.COPY_READ_BUFFER, gl.COPY_WRITE_BUFFER, 0, 254, 2],
);
assert.equal(calls.invalidCopyBufferSubData, 2);
assert.equal(state.bufferSizes.get(2), genericInvalidCopy.size);
assert.deepEqual([...bufferStorage.get(2).slice(254, 256)], [21, 22]);
assert.deepEqual([...state.bufferBytes.get(2).slice(254, 256)], [6, 5]);

const namedOverlapCopy = snapshotBufferState(1);
run(
  "copyNamedBufferSubData",
  ["sourceBuffer", "targetBuffer", "sourceOffset", "targetOffset", "size"],
  [1, 1, 0, 1, 2],
);
assert.equal(calls.invalidCopyBufferSubData, 3);
assertBufferStateUnchanged(1, namedOverlapCopy);

const namedZeroLengthCopy = snapshotBufferState(1);
run(
  "copyNamedBufferSubData",
  ["sourceBuffer", "targetBuffer", "sourceOffset", "targetOffset", "size"],
  [1, 1, 1, 1, 0],
);
assert.equal(calls.invalidCopyBufferSubData, 3);
assertBufferStateUnchanged(1, namedZeroLengthCopy);

const previousGenericWriteObject = physicalBindings.get(gl.COPY_WRITE_BUFFER);
const previousGenericWriteBuffer = state.boundBuffers.get(gl.COPY_WRITE_BUFFER);
physicalBindings.set(gl.COPY_WRITE_BUFFER, objects.get(1));
state.boundBuffers.set(gl.COPY_WRITE_BUFFER, 1);
const genericOverlapCopy = snapshotBufferState(1);
run(
  "copyBufferSubData",
  ["sourceTarget", "targetTarget", "sourceOffset", "targetOffset", "size"],
  [gl.COPY_READ_BUFFER, gl.COPY_WRITE_BUFFER, 0, 1, 2],
);
assert.equal(calls.invalidCopyBufferSubData, 4);
assertBufferStateUnchanged(1, genericOverlapCopy);

run(
  "copyBufferSubData",
  ["sourceTarget", "targetTarget", "sourceOffset", "targetOffset", "size"],
  [gl.COPY_READ_BUFFER, gl.COPY_WRITE_BUFFER, 0, 2, 2],
);
assert.equal(calls.invalidCopyBufferSubData, 4);
assert.equal(state.bufferSizes.get(1), genericOverlapCopy.size);
assert.deepEqual([...bufferStorage.get(1).slice(2, 4)], [21, 22]);

const zeroLengthCopy = snapshotBufferState(1);
run(
  "copyBufferSubData",
  ["sourceTarget", "targetTarget", "sourceOffset", "targetOffset", "size"],
  [gl.COPY_READ_BUFFER, gl.COPY_WRITE_BUFFER, 1, 1, 0],
);
assert.equal(calls.invalidCopyBufferSubData, 4);
assertBufferStateUnchanged(1, zeroLengthCopy);
physicalBindings.set(gl.COPY_WRITE_BUFFER, previousGenericWriteObject);
state.boundBuffers.set(gl.COPY_WRITE_BUFFER, previousGenericWriteBuffer);

const missingSourceShadow = state.bufferBytes.get(1);
const missingSourceTarget = snapshotBufferState(2);
state.bufferBytes.delete(1);
run(
  "copyNamedBufferSubData",
  ["sourceBuffer", "targetBuffer", "sourceOffset", "targetOffset", "size"],
  [1, 2, 0, 0, 2],
);
assert.equal(calls.invalidCopyBufferSubData, 4);
assert.equal(state.bufferSizes.get(2), missingSourceTarget.size);
assert.equal(state.bufferShadowDrops, missingSourceTarget.drops + 1);
assert.equal(state.bufferBytes.has(2), false);
state.bufferBytes.set(1, missingSourceShadow);

const signedPixels = new Int8Array([-1, 2, 3]);
run(
  "texImage2DJs",
  ["target", "level", "internalFormat", "width", "height", "border", "format", "type", "pixels"],
  [gl.TEXTURE_2D, 0, 0x8231, 3, 1, 0, 0x8d94, gl.BYTE, signedPixels],
);
assert.strictEqual(calls.texImage2D.at(-1).pixels, signedPixels);
assert.deepEqual([...calls.texImage2D.at(-1).bytes], [255, 2, 3]);
assert.equal(window.__gaiusGLStats.textureUploadSignedViewSkips, 1);

const signedSubPixels = new Int8Array([-2, 4]);
run(
  "texSubImage2DJs",
  ["target", "level", "x", "y", "width", "height", "format", "type", "pixels"],
  [gl.TEXTURE_2D, 0, 2, 3, 2, 1, 0x8d94, gl.BYTE, signedSubPixels],
);
assert.strictEqual(calls.texSubImage2D.at(-1).pixels, signedSubPixels);
assert.deepEqual([...calls.texSubImage2D.at(-1).bytes], [254, 4]);
assert.equal(window.__gaiusGLStats.textureUploadSignedViewSkips, 2);

const unsignedSubPixels = new Int8Array([0, -1, 127]);
run(
  "texSubImage2DJs",
  ["target", "level", "x", "y", "width", "height", "format", "type", "pixels"],
  [gl.TEXTURE_2D, 0, 0, 0, 3, 1, gl.RED, gl.UNSIGNED_BYTE, unsignedSubPixels],
);
assert.ok(calls.texSubImage2D.at(-1).pixels instanceof Uint8Array);
assert.notStrictEqual(calls.texSubImage2D.at(-1).pixels, unsignedSubPixels);
assert.deepEqual([...calls.texSubImage2D.at(-1).bytes], [0, 255, 127]);

run(
  "texSubImage2DOffsetJs",
  ["target", "level", "x", "y", "width", "height", "format", "type", "offset"],
  [gl.TEXTURE_2D, 0, 4, 5, 2, 2, gl.RED, gl.BYTE, 4],
);
assert.equal(calls.texSubImage2D.at(-1).pixels, 4);
assert.equal(calls.texSubImage2D.at(-1).format, 0x8d94);
assert.equal(calls.texSubImage2D.at(-1).type, gl.BYTE);
assert.match(
  source,
  /boundBufferForTargetJs\(PIXEL_UNPACK_BUFFER\)\s*!=\s*0[\s\S]*?texSubImage2DOffsetJs\([\s\S]*?return;[\s\S]*?pointerBytes\(/,
  "long texture uploads must preserve PBO offsets instead of dereferencing them",
);

for (const [parameter, value] of [
  [gl.UNPACK_ALIGNMENT, 8],
  [gl.UNPACK_ROW_LENGTH, 7],
  [gl.UNPACK_SKIP_ROWS, 2],
  [gl.UNPACK_SKIP_PIXELS, 1],
]) {
  run("pixelStoreiJs", ["parameter", "value"], [parameter, value]);
}
state.bufferBytes.set(3, new Uint8Array([1, 2, 3, 4, 5]));
state.bufferSizes.set(3, 5);
state.textureBindings.set(gl.TEXTURE_2D & 65535, 10);
const pixelStoreCallStart = calls.pixelStorei.length;
run("texBuffer", ["target", "internalFormat", "buffer"], [0x8c2a, gl.RGBA8, 3]);
const texBufferUpload = calls.texImage2D.at(-1);
assert.equal(texBufferUpload.width, 2);
assert.equal(texBufferUpload.height, 1);
assert.deepEqual([...texBufferUpload.bytes], [1, 2, 3, 4, 5, 0, 0, 0]);
assert.equal(calls.getParameter, 0);
assert.equal(calls.activeTexture, 0);
assert.equal(calls.pixelStorei.length - pixelStoreCallStart, 8);
assert.equal(pixelStore.get(gl.UNPACK_ALIGNMENT), 8);
assert.equal(pixelStore.get(gl.UNPACK_ROW_LENGTH), 7);
assert.equal(pixelStore.get(gl.UNPACK_SKIP_ROWS), 2);
assert.equal(pixelStore.get(gl.UNPACK_SKIP_PIXELS), 1);
assert.equal(calls.texParameteri.length, 6);
assert.equal(state.textureBufferInfo.get(10).byteLength, 5);

run("texBuffer", ["target", "internalFormat", "buffer"], [0x8c2a, gl.RGBA8, 3]);
assert.equal(calls.texParameteri.length, 6);
assert.equal(window.__gaiusGLStats.texBufferTextureParameterCalls, 6);
assert.equal(window.__gaiusGLStats.texBufferTextureParameterSkips, 6);
assert.equal(window.__gaiusGLStats.texBufferStateReadbacksAvoided, 10);
assert.equal(window.__gaiusGLStats.texBufferUnsignedViewSkips, 2);

const texBufferBody = jsBody("texBuffer");
assert.doesNotMatch(texBufferBody, /gl\.getParameter\s*\(/);
assert.doesNotMatch(texBufferBody, /gl\.activeTexture\s*\(/);
assert.doesNotMatch(jsBody("bufferDataJs"), /new\s+Int8Array\s*\(\s*256\s*\)/);

console.log("Browser OpenGL hot-path smoke passed");
console.log(JSON.stringify({
  bufferDataCalls: window.__gaiusGLStats.bufferDataCalls,
  bufferSubDataCalls: window.__gaiusGLStats.bufferSubDataCalls,
  invalidBufferSubDataCalls: calls.invalidBufferSubData,
  copyBufferSubDataCalls: calls.copyBufferSubData,
  invalidCopyBufferSubDataCalls: calls.invalidCopyBufferSubData,
  bufferUploadBytes: window.__gaiusGLStats.bufferUploadBytes,
  namedBufferBindSkips: window.__gaiusGLStats.namedBufferBindSkips,
  namedBufferPhysicalBinds: window.__gaiusGLStats.namedBufferPhysicalBinds,
  textureUploadSignedViewSkips: window.__gaiusGLStats.textureUploadSignedViewSkips,
  texBufferStateReadbacksAvoided: window.__gaiusGLStats.texBufferStateReadbacksAvoided,
  texBufferTextureParameterSkips: window.__gaiusGLStats.texBufferTextureParameterSkips,
}, null, 2));
