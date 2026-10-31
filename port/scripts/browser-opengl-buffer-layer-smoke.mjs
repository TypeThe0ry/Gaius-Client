#!/usr/bin/env node
// BrowserOpenGL buffer layer (initializeBufferLayerJs, WebAssembly GC): large uniform buffers in
// 256 KiB segments. A randomized differential run against a model WebGL2 buffer state: the same
// sequence of buffer calls goes to a plain context and to one with the layer installed, and
// after every call the bytes each bound uniform range would show a draw, and every buffer read
// back, must be identical. The layer's verify mode (CPU copy of each segmented buffer) must
// report no mismatch, segments must actually be used, and a range across two segments, a whole
// binding, a copy into a segmented buffer or a vertex use must put the buffer back on its single
// storage.
//
//   node port/scripts/browser-opengl-buffer-layer-smoke.mjs [BrowserOpenGL.java]

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";

const dir = path.dirname(fileURLToPath(import.meta.url));
const source = fs.readFileSync(path.resolve(process.argv[2] || path.join(dir,
  "../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java")), "utf8");
function jsBody(method) {
  const d = new RegExp(`(?:private|public)\\s+static\\s+native\\s+[\\w<>\\[\\]]+\\s+${method}\\s*\\(`).exec(source);
  assert.ok(d, `missing ${method}`);
  const a = source.lastIndexOf("@JSBody", d.index);
  const m = 'script = """';
  const s = source.indexOf(m, a) + m.length;
  const e = source.indexOf('"""', s);
  assert.ok(e > s, `missing body ${method}`);
  return source.slice(s, e);
}
const body = jsBody("initializeBufferLayerJs");
assert.ok(source.includes("initializeBufferLayerJs();"), "initialize() must install the buffer layer");
const installIndex = source.indexOf("initializeBufferLayerJs();");
const guardIndex = source.lastIndexOf("if (JsArrays.LINEAR_MEMORY) {", installIndex);
assert.ok(guardIndex > 0 && installIndex - guardIndex < 400, "the buffer layer is WebAssembly GC only");

const UNIFORM = 0x8A11, ARRAY = 0x8892, ELEMENT = 0x8893, COPY_READ = 0x8F36, COPY_WRITE = 0x8F37, UNPACK = 0x88EC;
const SEGMENT = 262144;

// FNV-1a over a byte range, with its length (what a draw would read, compared cheaply).
function digest(bytes) {
  let hash = 2166136261;
  for (let i = 0; i < bytes.length; i++) hash = Math.imul(hash ^ bytes[i], 16777619);
  return bytes.length + ":" + (hash >>> 0).toString(16);
}

// Model of the WebGL2 buffer state a draw sees.
function modelGl(name) {
  let next = 1;
  const generic = new Map();
  const uniform = [];
  const calls = {bufferSubData: 0, copyBufferSubData: 0, getBufferSubData: 0, createBuffer: 0};
  const bytesOf = (data, srcOffset, length, argc) => {
    if (data instanceof ArrayBuffer) return new Uint8Array(data);
    const size = data.BYTES_PER_ELEMENT || 1;
    const start = argc > 3 ? srcOffset : 0;
    const count = argc > 4 && length > 0 ? length : data.length - start;
    return new Uint8Array(data.buffer, data.byteOffset + start * size, count * size);
  };
  const gl = {
    name, calls, generic, uniform, canvas: null,
    createBuffer() { calls.createBuffer++; return {id: next++, data: new Uint8Array(0), deleted: false}; },
    bindBuffer(target, buffer) { generic.set(target, buffer || null); },
    bufferData(target, source, usage, srcOffset, length) {
      const buffer = generic.get(target);
      assert.ok(buffer, `${name}: bufferData without a buffer`);
      buffer.data = typeof source === "number" ? new Uint8Array(source)
        : new Uint8Array(bytesOf(source, srcOffset, length, arguments.length));
    },
    bufferSubData(target, offset, data, srcOffset, length) {
      calls.bufferSubData++;
      const buffer = generic.get(target);
      assert.ok(buffer, `${name}: bufferSubData without a buffer`);
      const bytes = bytesOf(data, srcOffset, length, arguments.length);
      assert.ok(offset >= 0 && offset + bytes.length <= buffer.data.length, `${name}: bufferSubData out of range`);
      buffer.data.set(bytes, offset);
    },
    copyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size) {
      calls.copyBufferSubData++;
      const from = generic.get(readTarget), to = generic.get(writeTarget);
      assert.ok(from && to, `${name}: copy without buffers`);
      assert.ok(readOffset + size <= from.data.length && writeOffset + size <= to.data.length, `${name}: copy out of range`);
      to.data.set(from.data.slice(readOffset, readOffset + size), writeOffset);
    },
    getBufferSubData(target, offset, out) {
      calls.getBufferSubData++;
      const buffer = generic.get(target);
      assert.ok(buffer, `${name}: read without a buffer`);
      out.set(buffer.data.subarray(offset, offset + out.length));
    },
    bindBufferRange(target, index, buffer, offset, size) {
      generic.set(target, buffer || null);
      if (target === UNIFORM) uniform[index] = buffer ? {buffer, offset, size} : null;
    },
    bindBufferBase(target, index, buffer) {
      generic.set(target, buffer || null);
      if (target === UNIFORM) uniform[index] = buffer ? {buffer, offset: 0, size: -1} : null;
    },
    deleteBuffer(buffer) {
      if (!buffer) return;
      buffer.deleted = true;
      for (const [target, bound] of generic) if (bound === buffer) generic.set(target, null);
      for (let i = 0; i < uniform.length; i++) if (uniform[i] && uniform[i].buffer === buffer) uniform[i] = null;
    },
    vertexAttribPointer() {},
    vertexAttribIPointer() {},
    drawArrays() {},
    fenceSync() { return {ready: false}; },
    clientWaitSync(fence, flags, timeout) {
      assert.equal(flags, 0);
      assert.equal(timeout, 0);
      return fence.ready ? 0x911A : 0x911B;
    },
    deleteSync(fence) { fence.deleted = true; },
  };
  // What a draw reads from each uniform binding.
  gl.draw = () => uniform.map(binding => {
    if (!binding) return null;
    const {buffer, offset, size} = binding;
    assert.ok(!buffer.deleted, `${name}: a draw reads a deleted buffer`);
    const end = size < 0 ? buffer.data.length : offset + size;
    return digest(buffer.data.subarray(offset, Math.min(end, buffer.data.length)));
  });
  return gl;
}

function install(gl, search) {
  globalThis.window = {__gaiusWebGL: gl, __gaiusGL: {}, __gaiusGLStats: {}};
  globalThis.location = {search};
  new Function(body)();
  return globalThis.window.__gaiusGL.bufferLayer;
}

let seed = 20261007;
const random = () => {
  seed = (Math.imul(seed, 1103515245) + 12345) >>> 0;
  return seed / 4294967296;
};
const pick = list => list[Math.floor(random() * list.length)];
const int = n => Math.floor(random() * n);

function run(rounds, search) {
  const plain = modelGl("plain");
  const layered = modelGl("layered");
  const layer = install(layered, search);
  assert.equal(layer.segmentsEnabled, !search.includes("gaiusUboRing=0"), "segments are on unless ?gaiusUboRing=0");
  const enabledAtStart = layer.segmentsEnabled;
  const sizes = [64 * 1024, 1024 * 1024, 2 * 1024 * 1024 + 4096, 3 * SEGMENT];
  const buffers = [];
  const make = () => {
    const pair = {plain: plain.createBuffer(), layered: layered.createBuffer(), size: 0};
    buffers.push(pair);
    return pair;
  };
  const both = (fn) => { fn(plain, "plain"); fn(layered, "layered"); };
  const allocate = (pair, size, withData) => {
    pair.size = size;
    const data = withData ? new Uint8Array(size).map((_, i) => (i * 7 + size) & 255) : null;
    both((gl, which) => {
      gl.bindBuffer(COPY_WRITE, pair[which]);
      if (data) gl.bufferData(COPY_WRITE, data, 0x88E8); else gl.bufferData(COPY_WRITE, size, 0x88E8);
    });
  };
  for (let i = 0; i < 8; i++) allocate(make(), sizes[i % sizes.length], i % 2 === 0);
  // Buffers 6 and 7 also serve as vertex and pixel sources (whole for good); 0-5 stay uniform buffers.
  const sources = () => buffers.slice(6, 8).filter(b => b.size > 0);
  const compareDraws = (step) => assert.deepEqual(layered.draw(), plain.draw(), `draw state differs after ${step}`);
  for (let round = 0; round < rounds; round++) {
    const op = int(100);
    const pair = pick(buffers.filter(b => b.size > 0));
    let step = "";
    if (op < 40) {
      // A write through COPY_WRITE (named path) or UNIFORM_BUFFER, in one of the source shapes.
      const length = 1 + int(Math.min(pair.size - 4, random() < 0.85 ? 600 : 300000));
      const offset = int(pair.size - length + 1);
      const shape = int(4);
      const raw = new Uint8Array(length + 64).map(() => int(256));
      const target = random() < 0.5 ? COPY_WRITE : UNIFORM;
      step = `write ${offset}+${length} shape ${shape}`;
      both((gl, which) => {
        gl.bindBuffer(target, pair[which]);
        if (shape === 0) gl.bufferSubData(target, offset, raw.subarray(0, length));
        else if (shape === 1) gl.bufferSubData(target, offset, raw, 16, length);
        else if (shape === 2) gl.bufferSubData(target, offset, new Int8Array(raw.buffer, 0, raw.length), 8, length);
        else {
          const floats = Math.max(1, length >> 2);
          const fl = new Float32Array(raw.buffer, 0, Math.floor(raw.length / 4));
          gl.bufferSubData(target, Math.min(offset, pair.size - floats * 4), fl, 0, floats);
        }
      });
    } else if (op < 80) {
      // A uniform range, mostly inside a segment, sometimes across a boundary.
      const slot = int(6);
      const size = Math.min(pair.size, 256 * (1 + int(random() < 0.9 ? 16 : 256)));
      let offset = 256 * int(Math.max(1, (pair.size - size) / 256));
      if (random() < 0.005) offset = Math.max(0, Math.min(pair.size - size, SEGMENT - 256 * (1 + int(4))));
      step = `bindBufferRange ${slot} ${offset}+${size}`;
      both((gl, which) => gl.bindBufferRange(UNIFORM, slot, pair[which], offset, size));
    } else if (op < 81) {
      const slot = int(6);
      step = `bindBufferBase ${slot}`;
      both((gl, which) => gl.bindBufferBase(UNIFORM, slot, pair[which]));
    } else if (op < 85) {
      const other = pick(buffers.filter(b => b.size > 0));
      const length = 1 + int(Math.min(pair.size, other.size, 70000));
      const from = int(other.size - length + 1), to = int(pair.size - length + 1);
      if (other === pair && from < to + length && to < from + length) continue;
      step = `copy ${from}->${to}+${length}`;
      both((gl, which) => {
        gl.bindBuffer(COPY_READ, other[which]);
        gl.bindBuffer(COPY_WRITE, pair[which]);
        gl.copyBufferSubData(COPY_READ, COPY_WRITE, from, to, length);
      });
    } else if (op < 91) {
      const length = 1 + int(Math.min(pair.size, 300000));
      const offset = int(pair.size - length + 1);
      step = `read ${offset}+${length}`;
      const a = new Uint8Array(length), b = new Uint8Array(length);
      plain.bindBuffer(COPY_READ, pair.plain);
      plain.getBufferSubData(COPY_READ, offset, a);
      layered.bindBuffer(COPY_READ, pair.layered);
      layered.getBufferSubData(COPY_READ, offset, b);
      assert.ok(Buffer.from(b).equals(Buffer.from(a)), `read back differs after ${step}`);
    } else if (op < 93) {
      step = "reallocate";
      allocate(pair, pick(sizes), random() < 0.5);
    } else if (op < 94) {
      step = "delete";
      const index = buffers.indexOf(pair);
      both((gl, which) => gl.deleteBuffer(pair[which]));
      pair.size = 0;
      const fresh = {plain: plain.createBuffer(), layered: layered.createBuffer(), size: 0};
      buffers[index] = fresh;
      buffers.push(pair);
      allocate(fresh, pick(sizes), true);
    } else if (op < 95) {
      const source = pick(sources());
      if (!source) continue;
      step = "vertex source";
      both((gl, which) => { gl.bindBuffer(ARRAY, source[which]); gl.vertexAttribPointer(0, 4, 0x1406, false, 16, 0); });
    } else if (op < 96) {
      const source = pick(sources());
      if (!source) continue;
      step = "unpack binding";
      both((gl, which) => { gl.bindBuffer(UNPACK, source[which]); gl.bindBuffer(UNPACK, null); });
    } else if (op < 97) {
      // Off (every buffer back to its single storage) and on again, as an A/B run does.
      step = "toggle";
      if (!enabledAtStart) continue;
      layer.setSegmentsEnabled(false);
      compareDraws(`${round}: segments off`);
      layer.setSegmentsEnabled(true);
    } else {
      step = "rebind COPY_WRITE";
      both((gl, which) => gl.bindBuffer(COPY_WRITE, pair[which]));
    }
    compareDraws(`${round}: ${step}`);
    // The generic bindings the game sees are its own buffers, never a segment.
    for (const target of [UNIFORM, COPY_READ, COPY_WRITE, ARRAY]) {
      const p = plain.generic.get(target) || null, l = layered.generic.get(target) || null;
      const pi = p ? buffers.findIndex(b => b.plain === p) : -1;
      const li = l ? buffers.findIndex(b => b.layered === l) : -1;
      assert.equal(li, pi, `generic binding 0x${target.toString(16)} differs after ${round}: ${step}`);
    }
  }
  // Everything still reads back identically.
  for (const pair of buffers) {
    if (!pair.size) continue;
    const a = new Uint8Array(pair.size), b = new Uint8Array(pair.size);
    plain.bindBuffer(COPY_READ, pair.plain); plain.getBufferSubData(COPY_READ, 0, a);
    layered.bindBuffer(COPY_READ, pair.layered); layered.getBufferSubData(COPY_READ, 0, b);
    assert.ok(Buffer.from(b).equals(Buffer.from(a)), "final contents differ");
  }
  return {layer, stats: layer.stats, layered};
}

const verified = run(4000, "?gaiusGlVerify=1");
assert.ok(verified.stats.redirectedWrites > 150, "writes went to segments");
assert.ok(verified.stats.redirectedBinds > 200, "ranges were bound to segments");
assert.equal(verified.stats.verifyChecks, verified.stats.redirectedBinds, "verify mode compared every bound range");
assert.equal(verified.stats.verifyMismatches, 0, `verify mismatch: ${verified.stats.lastVerifyMismatch}`);
assert.ok(verified.stats.demotions > 0, "whole uses put buffers back on their single storage");
const fast = run(4000, "");
assert.equal(fast.stats.verifyChecks, 0, "no verify reads without ?gaiusGlVerify=1");
const off = run(400, "?gaiusUboRing=0");
assert.equal(off.stats.redirectedWrites + off.stats.redirectedBinds, 0, "?gaiusUboRing=0 keeps every buffer whole");

// A write into a segmented buffer never touches the big buffer (the point of the layer).
{
  const gl = modelGl("direct");
  const layer = install(gl, "");
  const big = gl.createBuffer();
  gl.bindBuffer(COPY_WRITE, big);
  gl.bufferData(COPY_WRITE, 8 * 1024 * 1024, 0x88E8);
  gl.bindBufferRange(UNIFORM, 0, big, 0, 256);
  const before = Array.from(big.data.subarray(0, 512));
  gl.bindBuffer(COPY_WRITE, big);
  for (let block = 0; block < 64; block++) {
    gl.bufferSubData(COPY_WRITE, block * 256, new Uint8Array(256).fill(block + 1));
    gl.bindBufferRange(UNIFORM, 0, big, block * 256, 256);
    assert.notEqual(gl.uniform[0].buffer, big, "the range is bound to a segment");
    assert.equal(gl.uniform[0].offset, block * 256, "at the same offset inside its segment");
    assert.equal(gl.draw()[0], digest(new Uint8Array(256).fill(block + 1)));
  }
  assert.deepEqual(Array.from(big.data.subarray(0, 512)), before, "the big buffer was not written");
  assert.equal(gl.generic.get(UNIFORM), big, "the generic binding stays the buffer itself");
  assert.equal(layer.stats.segmentsCreated, 1);
  // Reading it back copies the newer segment bytes into it first.
  const out = new Uint8Array(512);
  gl.bindBuffer(COPY_READ, big);
  gl.getBufferSubData(COPY_READ, 0, out);
  assert.equal(out[0], 1);
  assert.equal(out[300], 2);
}

{
  const gl = modelGl("ring");
  const events = {};
  gl.canvas = {addEventListener: (name, handler) => { events[name] = handler; }};
  const layer = install(gl, "?gaiusGlVerify=1");
  const big = gl.createBuffer();
  gl.bindBuffer(COPY_WRITE, big);
  gl.bufferData(COPY_WRITE, 2 * 1024 * 1024, 0x88E8);
  gl.bindBufferRange(UNIFORM, 0, big, 0, 256);
  gl.bufferSubData(COPY_WRITE, 0, new Uint8Array(256).fill(1));
  const first = gl.uniform[0].buffer;
  gl.drawArrays(4, 0, 3);
  gl.bufferSubData(COPY_WRITE, 256, new Uint8Array(256).fill(2));
  assert.equal(gl.uniform[0].buffer, first, "nonoverlapping writes need no rotation");
  gl.bufferSubData(COPY_WRITE, 0, new Uint8Array(256).fill(3));
  assert.notEqual(gl.uniform[0].buffer, first, "a pending reader keeps its original segment");
  assert.equal(first.data[0], 1, "an unsignaled segment is not overwritten");
  gl.drawArrays(4, 0, 3);
  layer.retired[0].fence.ready = true;
  gl.bufferSubData(COPY_WRITE, 0, new Uint8Array(256).fill(4));
  assert.equal(gl.uniform[0].buffer, first, "a signaled segment is recycled");
  assert.equal(layer.stats.ringReuses, 1);
  assert.equal(gl.uniform[0].buffer.data[256], 2, "rotation preserves untouched bytes");
  for (let frame = 0; frame < 100; frame++) {
    gl.drawArrays(4, 0, 3);
    gl.bufferSubData(COPY_WRITE, 0, new Uint8Array(256).fill(frame));
    gl.bindBufferRange(UNIFORM, 0, big, 0, 256);
    assert.equal(gl.draw()[0], digest(new Uint8Array(256).fill(frame)));
  }
  assert.ok(layer.retired.length <= 32, "in-flight ring metadata stays bounded");
  assert.ok(layer.stats.retiredSegmentBytes <= 32 * SEGMENT);
  assert.ok(layer.stats.ringPressureDeletes > 0);
  assert.equal(layer.stats.verifyMismatches, 0);
  layer.setSegmentsEnabled(false);
  assert.equal(layer.retired.length, 0);
  assert.equal(layer.stats.retiredSegmentBytes, 0);
  assert.equal(big.data[256], 2);
  layer.setSegmentsEnabled(true);
  gl.bindBufferRange(UNIFORM, 0, big, 0, 256);
  gl.drawArrays(4, 0, 3);
  gl.bufferSubData(COPY_WRITE, 0, new Uint8Array(256).fill(7));
  assert.ok(layer.retired.length > 0);
  events.webglcontextlost();
  assert.equal(layer.retired.length, 0);
  assert.equal(layer.uniform.length, 0);
  assert.equal(layer.infos.size, 0);
  assert.equal(layer.stats.retiredSegmentBytes, 0);
  assert.equal(layer.stats.liveSegmentBytes, 0);
}

console.log(`browser-opengl-buffer-layer-smoke: ok (verified ${JSON.stringify({
  writes: verified.stats.redirectedWrites, binds: verified.stats.redirectedBinds,
  checks: verified.stats.verifyChecks, demotions: verified.stats.demotions, fills: verified.stats.segmentFills,
  flushes: verified.stats.segmentFlushes})})`);
