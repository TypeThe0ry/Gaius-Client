#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const sourcePath = new URL(
  "../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java",
  import.meta.url,
);
const source = readFileSync(sourcePath, "utf8");

function jsBody(method) {
  const escaped = method.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const declaration = new RegExp(
    `(?:private|public)\\s+static\\s+native\\s+[\\w<>\\[\\]]+\\s+${escaped}\\s*\\(`,
  ).exec(source);
  assert.ok(declaration, `missing native declaration for ${method}`);
  const annotationStart = source.lastIndexOf("@JSBody", declaration.index);
  const marker = 'script = """';
  const bodyStart = source.indexOf(marker, annotationStart);
  const contentStart = bodyStart + marker.length;
  const bodyEnd = source.indexOf('"""', contentStart);
  assert.ok(
    annotationStart >= 0 && bodyStart >= 0 && bodyEnd >= 0 && bodyEnd < declaration.index,
    `missing JS body for ${method}`,
  );
  return source.slice(contentStart, bodyEnd);
}

function compile(method, parameters = []) {
  return Function(...parameters, jsBody(method));
}

function run(method, parameters, args) {
  return compile(method, parameters)(...args);
}

const GL = {
  ARRAY_BUFFER: 0x8892,
  ELEMENT_ARRAY_BUFFER: 0x8893,
  COPY_READ_BUFFER: 0x8f36,
  COPY_WRITE_BUFFER: 0x8f37,
  STATIC_DRAW: 0x88e4,
  UNSIGNED_BYTE: 0x1401,
  UNSIGNED_SHORT: 0x1403,
  UNSIGNED_INT: 0x1405,
  BYTE: 0x1400,
  TEXTURE_2D: 0x0de1,
  TEXTURE_MIN_FILTER: 0x2801,
  TEXTURE_MAG_FILTER: 0x2800,
  TEXTURE_WRAP_S: 0x2802,
  TEXTURE_WRAP_T: 0x2803,
  NEAREST: 0x2600,
  CLAMP_TO_EDGE: 0x812f,
  RED: 0x1903,
  RGBA: 0x1908,
  RGBA8: 0x8058,
  UNPACK_ROW_LENGTH: 0x0cf2,
  UNPACK_SKIP_ROWS: 0x0cf3,
  UNPACK_SKIP_PIXELS: 0x0cf4,
  UNPACK_ALIGNMENT: 0x0cf5,
};

let nextObjectId = 1000;
let readbackCalls = 0;
let contextLost = false;
const physicalBindings = new Map();
const gpuBytes = new Map();
const texUploads = [];

function bytesOf(value) {
  if (typeof value === "number") return new Uint8Array(value);
  if (!value) return new Uint8Array(0);
  return Uint8Array.from(
    new Uint8Array(value.buffer, value.byteOffset || 0, value.byteLength),
  );
}

const gl = {
  ...GL,
  createBuffer() {
    return { id: nextObjectId++ };
  },
  deleteBuffer(object) {
    if (object) gpuBytes.delete(object.id);
  },
  bindBuffer(target, object) {
    physicalBindings.set(target, object || null);
  },
  bufferData(target, data) {
    const object = physicalBindings.get(target);
    assert.ok(object, `bufferData target ${target} is unbound`);
    gpuBytes.set(object.id, bytesOf(data));
  },
  bufferSubData(target, offset, data) {
    const object = physicalBindings.get(target);
    const targetBytes = object ? gpuBytes.get(object.id) : null;
    const sourceBytes = bytesOf(data);
    assert.ok(targetBytes, `bufferSubData target ${target} has no storage`);
    assert.ok(offset >= 0 && offset + sourceBytes.byteLength <= targetBytes.byteLength);
    targetBytes.set(sourceBytes, offset);
  },
  copyBufferSubData(sourceTarget, targetTarget, sourceOffset, targetOffset, size) {
    const sourceObject = physicalBindings.get(sourceTarget);
    const targetObject = physicalBindings.get(targetTarget);
    const sourceBytes = sourceObject ? gpuBytes.get(sourceObject.id) : null;
    const targetBytes = targetObject ? gpuBytes.get(targetObject.id) : null;
    assert.ok(sourceBytes && targetBytes, "copyBufferSubData storage missing");
    assert.ok(sourceOffset >= 0 && sourceOffset + size <= sourceBytes.byteLength);
    assert.ok(targetOffset >= 0 && targetOffset + size <= targetBytes.byteLength);
    targetBytes.set(sourceBytes.slice(sourceOffset, sourceOffset + size), targetOffset);
  },
  getBufferSubData(target, offset, destination) {
    readbackCalls += 1;
    const object = physicalBindings.get(target);
    const bytes = object ? gpuBytes.get(object.id) : null;
    assert.ok(bytes, `getBufferSubData target ${target} has no storage`);
    assert.ok(offset >= 0 && offset + destination.byteLength <= bytes.byteLength);
    destination.set(bytes.subarray(offset, offset + destination.byteLength));
  },
  getExtension() {
    return null;
  },
  isContextLost() {
    return contextLost;
  },
  drawElements() {},
  drawElementsInstanced() {},
  vertexAttribPointer() {},
  vertexAttribIPointer() {},
  pixelStorei() {},
  texParameteri() {},
  texImage2D(_target, _level, _internalFormat, _width, _height, _border, _format, _type, data) {
    texUploads.push(bytesOf(data));
  },
};

globalThis.requestAnimationFrame = () => 1;
globalThis.window = {
  __gaiusWebGL: gl,
  __gaiusGLStats: {},
  __gaiusMaxSingleBufferShadowBytes: 64,
  __gaiusMaxTotalBufferShadowBytes: 256,
  __gaiusBaseVertexDerivedBufferBudgetBytes: 256,
};

compile("initializeJs")();
compile("initializeDrawCompatibilityJs")();
compile("initializeElementBufferStateJs")();
compile("initializePerformanceStateJs")();
compile("initializeGpuHotPathJs")();
compile("initializeShadowDecisionCache")();

const state = window.__gaiusGL;
const stats = window.__gaiusGLStats;
state.hotPathTelemetryEnabled = true;

const objects = new Map();
for (let id = 1; id <= 16; id += 1) {
  const object = { id };
  objects.set(id, object);
  state.buffers.set(id, object);
}

function bind(target, id) {
  run("bindBuffer", ["target", "buffer"], [target, id]);
}

function upload(id, values) {
  bind(gl.COPY_WRITE_BUFFER, id);
  run("bufferDataJs", ["target", "data", "usage"], [
    gl.COPY_WRITE_BUFFER,
    Int8Array.from(values),
    gl.STATIC_DRAW,
  ]);
}

function allocate(id, size) {
  bind(gl.COPY_WRITE_BUFFER, id);
  run("bufferDataSizeJs", ["target", "size", "usage"], [
    gl.COPY_WRITE_BUFFER,
    size,
    gl.STATIC_DRAW,
  ]);
}

function copy(source, target, sourceOffset, targetOffset, size) {
  bind(gl.COPY_READ_BUFFER, source);
  bind(gl.COPY_WRITE_BUFFER, target);
  run(
    "copyBufferSubData",
    ["sourceTarget", "targetTarget", "sourceOffset", "targetOffset", "size"],
    [gl.COPY_READ_BUFFER, gl.COPY_WRITE_BUFFER, sourceOffset, targetOffset, size],
  );
}

// COPY targets are staging aliases, not permanent CPU-shadow requirements.
bind(gl.COPY_READ_BUFFER, 1);
bind(gl.COPY_WRITE_BUFFER, 2);
assert.equal(state.shadowRequiredBuffers.has(1), false);
assert.equal(state.shadowRequiredBuffers.has(2), false);
assert.equal(state.shouldShadowBufferTarget(gl.COPY_READ_BUFFER, 1), false);
assert.equal(state.shouldShadowBufferTarget(gl.COPY_WRITE_BUFFER, 2), false);
assert.equal(state.shouldShadowBufferTarget(0x8c2a, 1), true, "TEXTURE_BUFFER requirement lost");
// Element uploads keep no CPU shadow by default (a base-vertex fallback reads back lazily
// and marks the buffer required); gaiusElementShadow=always restores the upload shadow.
assert.equal(
  state.shouldShadowBufferTarget(gl.ELEMENT_ARRAY_BUFFER, 1),
  false,
  "default ELEMENT upload requested a CPU shadow",
);
state.elementShadowAlways = true;
assert.equal(
  state.shouldShadowBufferTarget(gl.ELEMENT_ARRAY_BUFFER, 1),
  true,
  "ELEMENT/base-vertex fallback requirement lost under gaiusElementShadow=always",
);
state.elementShadowAlways = false;
state.shadowRequiredBuffers.add(1);
assert.equal(
  state.shouldShadowBufferTarget(gl.ELEMENT_ARRAY_BUFFER, 1),
  true,
  "ELEMENT buffer marked shadow-required lost its shadow",
);
state.shadowRequiredBuffers.delete(1);
state.misalignedBufferRefs = new Map([[2, 1]]);
assert.equal(
  state.shouldShadowBufferTarget(gl.ARRAY_BUFFER, 2),
  true,
  "misaligned ARRAY requirement lost",
);
state.misalignedBufferRefs.clear();

// Storage allocation, data upload, and subData have separate fixed scalars.
allocate(1, 16);
assert.equal(stats.bufferStorageAllocCalls, 1);
assert.equal(stats.bufferStorageAllocBytes, 16);
assert.equal(stats.bufferDataUploadCalls || 0, 0);
assert.equal(stats.bufferUploadPaddingBytes || 0, 0, "size-only storage counted as padding");
assert.equal(state.bufferBytes.has(1), false, "COPY storage allocated a CPU shadow");

upload(2, [1, 2, 3, 4, 5, 6, 7, 8]);
assert.equal(stats.bufferDataUploadCalls, 1);
assert.equal(stats.bufferDataUploadSourceBytes, 8);
assert.equal(stats.bufferDataUploadBytes, 8);
assert.equal(state.bufferBytes.has(2), false, "COPY data upload allocated a CPU shadow");
bind(gl.COPY_WRITE_BUFFER, 2);
run("bufferSubDataJs", ["target", "offset", "data"], [
  gl.COPY_WRITE_BUFFER,
  2,
  new Int8Array([31, 32]),
]);
assert.equal(stats.bufferSubDataUploadCalls, 1);
assert.equal(stats.bufferSubDataUploadBytes, 2);
assert.equal(state.bufferBytes.has(2), false, "unrequired COPY subData rebuilt a shadow");

// An exact full-range replacement is authoritative. It copies the source and never
// reads the old GPU allocation back merely to overwrite every byte.
upload(13, [13, 13, 13, 13, 13, 13, 13, 13]);
state.markBufferShadowRequired(13, "full-replacement-smoke");
const fullReplacement = new Int8Array([21, 22, 23, 24, 25, 26, 27, 28]);
const fullReplacementReadbacksBefore = readbackCalls;
const lazyCallsBeforeFull = stats.bufferShadowLazyReadbackCalls || 0;
bind(gl.COPY_WRITE_BUFFER, 13);
run("bufferSubDataJs", ["target", "offset", "data"], [
  gl.COPY_WRITE_BUFFER,
  0,
  fullReplacement,
]);
assert.equal(readbackCalls, fullReplacementReadbacksBefore);
assert.equal(stats.bufferShadowLazyReadbackCalls || 0, lazyCallsBeforeFull);
assert.deepEqual([...state.bufferBytes.get(13)], [21, 22, 23, 24, 25, 26, 27, 28]);
assert.deepEqual([...gpuBytes.get(13)], [...state.bufferBytes.get(13)]);
fullReplacement[0] = 99;
assert.equal(state.bufferBytes.get(13)[0], 21, "full replacement shadow aliases caller bytes");
assert.equal(stats.bufferShadowSubDataFullReplacements, 1);
assert.equal(stats.bufferShadowSubDataFullReplacementBytes, 8);

upload(16, [61, 62, 63, 64, 65, 66, 67, 68]);
state.markBufferShadowRequired(16, "named-full-replacement-smoke");
const namedFullReadbacksBefore = readbackCalls;
run("namedBufferSubDataJs", ["buffer", "offset", "data"], [
  16,
  0,
  new Int8Array([71, 72, 73, 74, 75, 76, 77, 78]),
]);
assert.equal(readbackCalls, namedFullReadbacksBefore);
assert.deepEqual([...state.bufferBytes.get(16)], [71, 72, 73, 74, 75, 76, 77, 78]);
assert.deepEqual([...gpuBytes.get(16)], [...state.bufferBytes.get(16)]);
assert.equal(stats.bufferShadowSubDataFullReplacements, 2);
assert.equal(stats.bufferShadowSubDataFullReplacementBytes, 16);

// First required partial subData performs one bounded exact readback after the GPU write.
upload(3, [10, 11, 12, 13, 14, 15, 16, 17]);
state.markBufferShadowRequired(3, "explicit-smoke");
const partialReadbacksBefore = readbackCalls;
const partialLazyCallsBefore = stats.bufferShadowLazyReadbackCalls || 0;
const partialLazyBytesBefore = stats.bufferShadowLazyReadbackBytes || 0;
const partialLazyMsBefore = stats.bufferShadowLazyReadbackMs || 0;
bind(gl.COPY_WRITE_BUFFER, 3);
run("bufferSubDataJs", ["target", "offset", "data"], [
  gl.COPY_WRITE_BUFFER,
  3,
  new Int8Array([90, 91]),
]);
assert.equal(readbackCalls - partialReadbacksBefore, 1);
assert.deepEqual([...state.bufferBytes.get(3)], [10, 11, 12, 90, 91, 15, 16, 17]);
assert.deepEqual([...gpuBytes.get(3)], [...state.bufferBytes.get(3)]);
assert.equal(stats.bufferShadowSubDataLazyRebuilds, 1);
assert.equal((stats.bufferShadowLazyReadbackCalls || 0) - partialLazyCallsBefore, 1);
assert.equal((stats.bufferShadowLazyReadbackBytes || 0) - partialLazyBytesBefore, 8);
assert.ok((stats.bufferShadowLazyReadbackMs || 0) >= partialLazyMsBefore);
assert.equal(stats.bufferShadowSubDataPartialUpdates, 1);
assert.equal(stats.bufferShadowSubDataPartialUpdateBytes, 2);

// Unknown sizes, out-of-bounds updates, and context loss are never mistaken for
// authoritative full replacements and never manufacture a zero-filled shadow.
upload(14, [31, 32, 33, 34, 35, 36, 37, 38]);
state.markBufferShadowRequired(14, "unknown-size-smoke");
state.bufferBytes.set(14, new Uint8Array(8).fill(77));
state.bufferSizes.delete(14);
const invalidReadbacksBefore = readbackCalls;
const fullHitsBeforeInvalid = stats.bufferShadowSubDataFullReplacements;
assert.equal(
  state.shadowBufferSubData(14, 0, new Int8Array(8).fill(1), "unknown-size"),
  false,
);
assert.equal(readbackCalls, invalidReadbacksBefore);
assert.equal(state.bufferBytes.has(14), false, "unknown size retained a fake exact shadow");

upload(15, [41, 42, 43, 44, 45, 46, 47, 48]);
state.markBufferShadowRequired(15, "out-of-bounds-smoke");
state.bufferBytes.set(15, new Uint8Array(8).fill(66));
assert.equal(
  state.shadowBufferSubData(15, 7, new Int8Array([2, 3]), "out-of-bounds"),
  false,
);
assert.equal(readbackCalls, invalidReadbacksBefore);
assert.equal(state.bufferBytes.has(15), false, "out-of-bounds update retained a fake shadow");
state.bufferBytes.set(15, new Uint8Array(8).fill(65));
assert.equal(
  state.shadowBufferSubData(15, Number.MAX_VALUE, new Int8Array(8), "numeric-overflow"),
  false,
);
assert.equal(readbackCalls, invalidReadbacksBefore);
assert.equal(state.bufferBytes.has(15), false, "numeric overflow retained a fake shadow");
assert.ok(Number.isFinite(stats.bufferShadowSkippedLargeBytes));

upload(16, [51, 52, 53, 54, 55, 56, 57, 58]);
state.markBufferShadowRequired(16, "context-loss-smoke");
state.bufferBytes.set(16, new Uint8Array(8).fill(55));
contextLost = true;
assert.equal(
  state.shadowBufferSubData(16, 0, new Int8Array(8).fill(4), "context-loss"),
  false,
);
assert.equal(state.ensureBufferShadow(16, "context-loss-consumer", false), null);
contextLost = false;
assert.equal(readbackCalls, invalidReadbacksBefore);
assert.equal(state.bufferBytes.has(16), false, "context loss retained a fake exact shadow");
assert.equal(stats.bufferShadowSubDataFullReplacements, fullHitsBeforeInvalid);
assert.equal(stats.bufferShadowSubDataContextLossRejects, 1);

// Missing COPY source provenance invalidates and defers; no per-copy synchronous readback.
upload(4, [40, 41, 42, 43, 44, 45, 46, 47]);
state.markBufferShadowRequired(3, "explicit-smoke");
const ensured3 = state.ensureBufferShadow(3, "pre-copy-smoke", false);
assert.ok(ensured3);
state.bufferBytes.delete(4);
const missingSourceReadbacksBefore = readbackCalls;
copy(4, 3, 1, 2, 3);
assert.equal(readbackCalls, missingSourceReadbacksBefore);
assert.equal(state.bufferBytes.has(3), false, "stale target shadow survived missing-source copy");
assert.equal(stats.bufferShadowDeferredCopyInvalidations, 1);
const rebuilt3 = state.ensureBufferShadow(3, "post-copy-consumer", false);
assert.deepEqual([...rebuilt3], [10, 11, 41, 42, 43, 15, 16, 17]);
assert.deepEqual([...rebuilt3], [...gpuBytes.get(3)]);

// Named partial/copy APIs obey the same exact rebuild and deferred-copy contract.
upload(10, [70, 71, 72, 73, 74, 75, 76, 77]);
state.markBufferShadowRequired(10, "named-explicit-smoke");
const namedPartialReadbacksBefore = readbackCalls;
run("namedBufferSubDataJs", ["buffer", "offset", "data"], [
  10,
  1,
  new Int8Array([81, 82]),
]);
assert.equal(readbackCalls - namedPartialReadbacksBefore, 1);
assert.deepEqual([...state.bufferBytes.get(10)], [70, 81, 82, 73, 74, 75, 76, 77]);
const namedCopyReadbacksBefore = readbackCalls;
run(
  "copyNamedBufferSubData",
  ["sourceBuffer", "targetBuffer", "sourceOffset", "targetOffset", "size"],
  [4, 10, 0, 4, 2],
);
assert.equal(readbackCalls, namedCopyReadbacksBefore);
assert.equal(state.bufferBytes.has(10), false);
const rebuilt10 = state.ensureBufferShadow(10, "named-post-copy-consumer", false);
assert.deepEqual([...rebuilt10], [70, 81, 82, 73, 40, 41, 76, 77]);

// COPY A -> B -> C stays shadow-free until C becomes an ELEMENT/base-vertex CPU consumer.
upload(5, [0, 0, 2, 0, 4, 0, 6, 0]);
allocate(6, 8);
allocate(7, 8);
const chainReadbacksBefore = readbackCalls;
copy(5, 6, 0, 0, 8);
copy(6, 7, 0, 0, 8);
assert.equal(readbackCalls, chainReadbacksBefore, "COPY chain performed synchronous readback");
assert.equal(state.bufferBytes.has(5), false);
assert.equal(state.bufferBytes.has(6), false);
assert.equal(state.bufferBytes.has(7), false);
const vao = state.getVaoEmu();
vao.elementArrayBuffer = 7;
vao.elementArrayBufferObject = objects.get(7);
// The base-vertex path never reads the GPU back itself: without a CPU shadow it declines (the
// draw then uses attribute-offset emulation) and the buffer stays shadow-free.
assert.equal(state.cacheShiftedIndexBuffer(vao, gl.UNSIGNED_SHORT, 0, 4, 1), null,
  "base-vertex indices were built without a CPU shadow");
assert.equal(readbackCalls, chainReadbacksBefore, "base-vertex path performed synchronous readback");
assert.equal(state.bufferBytes.has(7), false);
// A real CPU consumer rebuilds the exact bytes lazily; the index cache can then be built.
assert.ok(state.ensureBufferShadow(7, "base-vertex-index", false), "COPY A->B->ELEMENT shadow rebuild failed");
state.markBufferShadowRequired(7, "base-vertex-index");
const shifted = state.cacheShiftedIndexBuffer(vao, gl.UNSIGNED_SHORT, 0, 4, 1);
assert.ok(shifted, "COPY A->B->ELEMENT could not build base-vertex indices");
assert.equal(readbackCalls - chainReadbacksBefore, 1);
assert.deepEqual([...state.bufferBytes.get(7)], [0, 0, 2, 0, 4, 0, 6, 0]);
assert.deepEqual(
  [...new Uint16Array(gpuBytes.get(shifted.buffer.id).buffer)],
  [1, 3, 5, 7],
);
assert.equal(stats.bufferShadowLazyRebuildLastReason, "base-vertex-index");

// COPY does keep an already-exact CPU view coherent without making the target permanently required.
upload(11, [1, 1, 1, 1]);
upload(12, [2, 3, 4, 5]);
state.bufferBytes.set(11, bytesOf(gpuBytes.get(11)));
state.bufferBytes.set(12, bytesOf(gpuBytes.get(12)));
assert.equal(state.shadowRequiredBuffers.has(11), false);
assert.equal(state.shouldShadowBufferTarget(gl.COPY_WRITE_BUFFER, 11), true);
const coherentCopyReadbacksBefore = readbackCalls;
copy(12, 11, 1, 1, 2);
assert.equal(readbackCalls, coherentCopyReadbacksBefore);
assert.deepEqual([...state.bufferBytes.get(11)], [1, 3, 4, 1]);
assert.deepEqual([...state.bufferBytes.get(11)], [...gpuBytes.get(11)]);

// Oversized required partial updates fail closed: GPU contents change, no zero-filled valid shadow.
upload(8, Array.from({ length: 80 }, (_, index) => index));
state.markBufferShadowRequired(8, "oversized-smoke");
const lazyFailuresBefore = stats.bufferShadowLazyRebuildFailures || 0;
bind(gl.COPY_WRITE_BUFFER, 8);
run("bufferSubDataJs", ["target", "offset", "data"], [
  gl.COPY_WRITE_BUFFER,
  40,
  new Int8Array([101, 102]),
]);
assert.equal(state.bufferBytes.has(8), false, "oversized readback manufactured a shadow");
assert.equal((stats.bufferShadowLazyRebuildFailures || 0) - lazyFailuresBefore, 1);
assert.deepEqual([...gpuBytes.get(8).slice(38, 44)], [38, 39, 101, 102, 42, 43]);

// TEXTURE_BUFFER is another real CPU consumer: exact lazy bytes or no upload at all.
state.textures.set(20, { id: 20 });
state.textureBindings.set(gl.TEXTURE_2D & 65535, 20);
upload(9, [9, 8, 7, 6]);
const textureReadbacksBefore = readbackCalls;
run("texBuffer", ["target", "internalFormat", "buffer"], [0x8c2a, gl.RGBA8, 9]);
assert.equal(readbackCalls - textureReadbacksBefore, 1);
assert.deepEqual([...texUploads.at(-1).slice(0, 4)], [9, 8, 7, 6]);
const texUploadsBeforeFailure = texUploads.length;
run("texBuffer", ["target", "internalFormat", "buffer"], [0x8c2a, gl.RGBA8, 8]);
assert.equal(texUploads.length, texUploadsBeforeFailure, "TEXTURE_BUFFER uploaded zero-filled fallback");
assert.equal(stats.texBufferMissingExactShadow, 1);

// Static contracts guard against reintroducing unconditional COPY marking/readbacks.
const shadowDecisionBody = jsBody("initializeShadowDecisionCache");
assert.match(
  shadowDecisionBody,
  /COPY_READ_BUFFER\|\|t===g\.COPY_WRITE_BUFFER\)return this\.bufferBytes\.has\(id\)/,
);
assert.doesNotMatch(
  shadowDecisionBody,
  /COPY_READ_BUFFER\|\|t===g\.COPY_WRITE_BUFFER\)return true/,
);
const bindBody = jsBody("bindBuffer");
assert.doesNotMatch(bindBody, /target===gl\.COPY_(?:READ|WRITE)_BUFFER[\s\S]*markBufferShadowRequired/);
assert.doesNotMatch(jsBody("copyBufferSubData"), /ensureBufferShadow|readBufferShadow/);
assert.doesNotMatch(jsBody("copyNamedBufferSubData"), /ensureBufferShadow|readBufferShadow/);
assert.doesNotMatch(source, /const next=new Uint8Array\(allocation\)/);
assert.match(source, /bufferStorageAllocCalls/);
assert.match(source, /bufferShadowLazyRebuilds/);
assert.match(source, /copy-deferred-readback/);
const shadowSubDataBody = jsBody("initializeJs").slice(
  jsBody("initializeJs").indexOf("window.__gaiusGL.shadowBufferSubData=function"),
);
assert.ok(
  shadowSubDataBody.indexOf("start===0 && source.byteLength===known") <
    shadowSubDataBody.indexOf("this.ensureBufferShadow("),
  "exact replacement guard must precede lazy readback",
);
assert.match(shadowSubDataBody, /const replacement=new Uint8Array\(source\)/);
assert.match(source, /bufferShadowSubDataFullReplacements/);
assert.match(source, /bufferShadowSubDataFullReplacementBytes/);
assert.match(source, /bufferShadowLazyReadbackCalls/);
assert.match(source, /bufferShadowLazyReadbackBytes/);
assert.match(source, /bufferShadowLazyReadbackMs/);
assert.match(source, /bufferShadowSubDataPartialUpdates/);
assert.doesNotMatch(shadowSubDataBody, /const replacement=new Uint8Array\(known\)/);

console.log("Browser buffer shadow policy smoke passed");
console.log(JSON.stringify({
  storageAllocCalls: stats.bufferStorageAllocCalls,
  dataUploadCalls: stats.bufferDataUploadCalls,
  subDataUploadCalls: stats.bufferSubDataUploadCalls,
  lazyRebuildAttempts: stats.bufferShadowLazyRebuildAttempts,
  lazyRebuilds: stats.bufferShadowLazyRebuilds,
  lazyRebuildFailures: stats.bufferShadowLazyRebuildFailures,
  lazyReadbackCalls: stats.bufferShadowLazyReadbackCalls,
  lazyReadbackBytes: stats.bufferShadowLazyReadbackBytes,
  lazyReadbackMs: stats.bufferShadowLazyReadbackMs,
  fullReplacements: stats.bufferShadowSubDataFullReplacements,
  fullReplacementBytes: stats.bufferShadowSubDataFullReplacementBytes,
  partialUpdates: stats.bufferShadowSubDataPartialUpdates,
  partialUpdateBytes: stats.bufferShadowSubDataPartialUpdateBytes,
  contextLossRejects: stats.bufferShadowSubDataContextLossRejects,
  deferredCopyInvalidations: stats.bufferShadowDeferredCopyInvalidations,
  readbackCalls,
  textureUploads: texUploads.length,
}, null, 2));
