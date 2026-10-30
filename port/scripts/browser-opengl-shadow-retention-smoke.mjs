#!/usr/bin/env node
// BrowserOpenGL shadow retention (initializeShadowRetentionJs, WebAssembly GC): fewer GPU
// readbacks for the CPU buffer shadows, on a model WebGL2 buffer state with the real
// BrowserOpenGL bodies (initializeJs, bindBuffer, bufferDataSizeJs, bufferSubDataJs,
// copyBufferSubData). A new staging buffer (other data, copied into an element buffer)
// reads back once instead of twice; a required buffer allocated without data starts with a
// zero shadow (its partial writes need no readback); over budget, unrequired shadows are
// evicted first; ?gaiusShadowKeep=0 restores the old counts; every copy stays byte-exact.
//
//   node port/scripts/browser-opengl-shadow-retention-smoke.mjs [BrowserOpenGL.java]

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
  return source.slice(s, e);
}
const C = {ARRAY_BUFFER: 0x8892, ELEMENT_ARRAY_BUFFER: 0x8893, COPY_READ_BUFFER: 0x8f36, COPY_WRITE_BUFFER: 0x8f37};

function setup(search, wasm = false, lowMemory = false) {
  const bytes = new Map(), bindings = new Map(), kinds = new Map();
  const counts = {readbacks: 0, readbackBytes: 0};
  const gl = new Proxy({...C, getError: () => 0, isContextLost: () => false}, {get(t, k) {
    if (k in t) return t[k];
    if (k === "bindBuffer") return (target, object) => {
      if (object && !kinds.has(object)) kinds.set(object, target === C.ELEMENT_ARRAY_BUFFER ? 1 : 2);
      bindings.set(target, object || null);
    };
    if (k === "bufferData") return (target, data) => {
      const o = bindings.get(target); bytes.set(o, typeof data === "number" ? new Uint8Array(data) : new Uint8Array(data));
    };
    if (k === "bufferSubData") return (target, offset, data) => { bytes.get(bindings.get(target)).set(new Uint8Array(data.buffer, data.byteOffset, data.byteLength), offset); };
    if (k === "getBufferSubData") return (target, offset, out) => {
      counts.readbacks++; counts.readbackBytes += out.byteLength;
      out.set(bytes.get(bindings.get(target)).subarray(offset, offset + out.byteLength));
    };
    if (k === "copyBufferSubData") return (r, w, ro, wo, n) => {
      const a = bindings.get(r), b = bindings.get(w);
      assert.equal(kinds.get(a), kinds.get(b), "native copies stay within one buffer kind");
      bytes.get(b).set(bytes.get(a).slice(ro, ro + n), wo);
    };
    if (k === "createBuffer") return () => ({});
    return () => {};
  }});
  globalThis.window = {__gaiusWebGL: gl, __gaiusGLStats: {}, __gaiusMaxSingleBufferShadowBytes: 16 * 1024 * 1024,
    __gaiusMaxTotalBufferShadowBytes: 64 * 1024 * 1024};
  globalThis.performance = {now: () => 1};
  globalThis.location = {search};
  if (wasm) window.__gaiusWasmMemory = {};
  window.__gaiusMemoryTier = {lowMemory};
  new Function(jsBody("initializeJs"))();
  const state = window.__gaiusGL;
  state.hasUsableBaseVertexExtension = () => true;
  const vao = {elementArrayBuffer: 0, elementArrayBufferObject: null};
  state.getVaoEmu = () => vao;
  state.bindPhysicalElementBuffer = (v, o) => gl.bindBuffer(C.ELEMENT_ARRAY_BUFFER, o || null);
  state.replaceVaoBufferRef = () => {}; state.ensureLogicalElementBuffer = () => {}; state.noteBufferUpload = () => {};
  new Function(jsBody("initializeShadowRetentionJs"))();
  const run = (name, params, args) => new Function(...params, jsBody(name))(...args);
  const ctx = {
    gl, state, counts, bytes,
    create(id) { state.buffers.set(id, {id}); },
    bind(target, id) { run("bindBuffer", ["target", "buffer"], [target, id]); },
    allocate(target, id, size) { ctx.bind(target, id); run("bufferDataSizeJs", ["target", "size", "usage"], [target, size, 0x88E8]); },
    write(target, offset, data) { run("bufferSubDataJs", ["target", "offset", "data"], [target, offset, data]); },
    copy(from, to, offset, targetOffset, size) {
      ctx.bind(C.COPY_READ_BUFFER, from); ctx.bind(C.COPY_WRITE_BUFFER, to);
      run("copyBufferSubData", ["sourceTarget", "targetTarget", "sourceOffset", "targetOffset", "size"],
        [C.COPY_READ_BUFFER, C.COPY_WRITE_BUFFER, offset, targetOffset, size]);
    },
    physical(id) { return bytes.get(state.buffers.get(id)); },
  };
  return ctx;
}

for (const [wasm, lowMemory, search, expected] of [
  [false, true, "", 64], [true, true, "", 16], [true, false, "", 32],
  [true, true, "?gaiusShadowBudgetMB=off", 64], [true, true, "?gaiusShadowBudgetMB=0", 0],
  [true, true, "?gaiusShadowBudgetMB=bad", 16], [true, false, "?gaiusShadowBudgetMB=999", 64]
]) {
  const context = setup(search, wasm, lowMemory);
  assert.equal(context.state.maxTotalBufferShadowBytes(), expected * 1048576);
}

// A staging cycle as StagedVertexBuffer runs it: a partial write of [0, used) into the staging
// buffer, then its index range copied into the element buffer.
function stagingFrames(search, frames, wasm = false, lowMemory = false) {
  const c = setup(search, wasm, lowMemory);
  c.create(1); c.create(2);
  c.allocate(C.ELEMENT_ARRAY_BUFFER, 2, 65536);
  c.allocate(C.COPY_WRITE_BUFFER, 1, 262144);
  const perFrame = [];
  for (let frame = 0; frame < frames; frame++) {
    const before = c.counts.readbacks;
    const data = new Uint8Array(4096).map((_, i) => (i * 3 + frame) & 255);
    c.bind(C.COPY_WRITE_BUFFER, 1);
    c.write(C.COPY_WRITE_BUFFER, 0, data);
    c.copy(1, 2, 2048, 128 * frame, 1024);
    assert.deepEqual(c.physical(2).slice(128 * frame, 128 * frame + 1024), data.slice(2048, 3072), `frame ${frame} indices exact`);
    perFrame.push(c.counts.readbacks - before);
  }
  return {c, perFrame};
}

const kept = stagingFrames("", 6);
assert.deepEqual(kept.perFrame, [0, 0, 0, 0, 0, 0], "staging retains CPU bytes before its first copy");
assert.equal(kept.c.state.shadowRetention.readbacks.calls, 0);
const old = stagingFrames("?gaiusShadowKeep=0", 6);
assert.deepEqual(old.perFrame, [1, 1, 0, 0, 0, 0], "the old path read back twice");
const minimal = stagingFrames("?gaiusShadowBudgetMB=0", 6, true, true);
assert.equal(minimal.c.state.maxTotalBufferShadowBytes(), 0);
assert.ok(minimal.c.counts.readbacks > 0, "a zero shadow budget falls back to byte-exact readback");

// A required buffer reallocated without data starts with a zero shadow.
{
  const c = setup("");
  c.create(5); c.create(6);
  c.allocate(C.ELEMENT_ARRAY_BUFFER, 6, 4096);
  c.allocate(C.COPY_WRITE_BUFFER, 5, 8192);
  c.bind(C.COPY_WRITE_BUFFER, 5); c.write(C.COPY_WRITE_BUFFER, 0, new Uint8Array(8192).fill(7));
  c.copy(5, 6, 0, 0, 64);
  const before = c.counts.readbacks;
  c.allocate(C.COPY_WRITE_BUFFER, 5, 16384);
  assert.ok(!c.state.bufferBytes.has(5) && c.state.bufferZeroFresh.has(5), "a staging buffer keeps no whole-buffer shadow, only the knowledge that the storage is zero");
  c.bind(C.COPY_WRITE_BUFFER, 5); c.write(C.COPY_WRITE_BUFFER, 100, new Uint8Array(50).fill(9));
  assert.equal(c.state.bufferPartial.get(5).bytes.byteLength, 50, "the extent holds what was written");
  c.copy(5, 6, 90, 0, 80);
  assert.equal(c.counts.readbacks, before, "no readback after reallocation");
  const expected = new Uint8Array(80); expected.fill(9, 10, 60);
  assert.deepEqual(c.physical(6).slice(0, 80), expected, "zeros and the written bytes reach the element buffer");
}

// A buffer allocated without data (ARRAY_BUFFER) that becomes a staging buffer later (COPY_WRITE
// bind marks it required) still holds zeros: its first partial write and the copy out of it
// need no readback; once written outside the shadow's knowledge it is read back as before.
{
  const c = setup("");
  c.create(20); c.create(21);
  c.allocate(C.ELEMENT_ARRAY_BUFFER, 21, 4096);
  c.allocate(C.ARRAY_BUFFER, 20, 65536);
  assert.ok(!c.state.bufferBytes.has(20), "an unrequired allocation keeps no shadow");
  const data = new Uint8Array(512).map((_, i) => (i * 7 + 1) & 255);
  c.bind(C.COPY_WRITE_BUFFER, 20);
  c.write(C.COPY_WRITE_BUFFER, 0, data);
  c.copy(20, 21, 128, 0, 256);
  assert.equal(c.counts.readbacks, 0, "no readback for a fresh zero-allocated staging buffer");
  assert.equal(c.state.shadowRetention.readbacks.calls, 0);
  assert.equal(window.__gaiusGLStats.bufferShadowExtentUpdates, 1);
  assert.deepEqual(c.physical(21).slice(0, 256), data.slice(128, 384), "indices exact");
  assert.deepEqual(c.state.bufferPartial.get(20).bytes, data, "the extent equals the bytes written");
  assert.ok(c.state.shadowedRange(20, 512, 65536).every(v => v === 0), "the rest stays zero");
  // A consumer of the whole shadow gets it from zeros and the extent, again without a readback.
  const whole = c.state.ensureBufferShadow(20, "test", false);
  assert.equal(c.counts.readbacks, 0);
  assert.equal(window.__gaiusGLStats.bufferShadowZeroFreshShadows, 1);
  assert.deepEqual(whole.slice(0, 512), data);
}
// A write before the buffer is required makes its contents unknown: the shadow is read back.
{
  const c = setup("");
  c.create(22);
  c.allocate(C.ARRAY_BUFFER, 22, 65536);
  c.bind(C.ARRAY_BUFFER, 22);
  c.write(C.ARRAY_BUFFER, 100, new Uint8Array(64).fill(5));
  c.bind(C.COPY_WRITE_BUFFER, 22);
  c.write(C.COPY_WRITE_BUFFER, 0, new Uint8Array(32).fill(6));
  assert.equal(c.counts.readbacks, 0, "a staging write keeps its extent without a readback");
  assert.ok(!c.state.bufferPartial.get(22).zeroBase, "bytes written while unshadowed are not known to the CPU");
  const shadow = c.state.ensureBufferShadow(22, "test", false);
  assert.equal(c.counts.readbacks, 1, "contents written while unshadowed come from the GPU when a consumer needs them whole");
  assert.equal(shadow[0], 6); assert.equal(shadow[100], 5); assert.equal(shadow[163], 5); assert.equal(shadow[164], 0);
}
// Reallocation, a pixel-pack bind and deletion forget the zero state.
{
  const c = setup("");
  c.create(23);
  c.allocate(C.ARRAY_BUFFER, 23, 4096);
  assert.ok(c.state.bufferZeroFresh.has(23));
  c.bind(0x88EB, 23);
  assert.ok(!c.state.bufferZeroFresh.has(23), "a pixel-pack bind may write the storage");
  c.allocate(C.ARRAY_BUFFER, 23, 4096);
  assert.ok(c.state.bufferZeroFresh.has(23), "reallocation without data is zeros again");
  c.state.bumpBufferVersion(23, 0, 16);
  assert.ok(!c.state.bufferZeroFresh.has(23), "any write ends it");
}

// A staging pool: each buffer keeps the extent it is written, not a shadow of its whole size, so
// 40 buffers of 256 KiB stay far below the budget and nothing evicts (the shadows the pool used
// to fill the budget with pushed out the terrain index heap and the texel buffers, which were
// then read back from the GPU).
{
  const c = setup("?gaiusShadowBudgetMB=8", true, true);
  c.state.hasUsableBaseVertexExtension = () => false;
  c.state.elementShadowAlways = true; // what initializeTerrainBatchJs sets unless ?gaiusElementShadow=lazy
  c.create(500); c.allocate(C.ELEMENT_ARRAY_BUFFER, 500, 4194304);
  const heap = new Uint8Array(4096).fill(3);
  c.write(C.ELEMENT_ARRAY_BUFFER, 0, heap);
  assert.ok(c.state.bufferBytes.get(500) && c.state.bufferBytes.get(500).byteLength === 4194304,
    "an element buffer is shadowed from its zero allocation (no base-vertex extension)");
  for (let i = 0; i < 40; i++) {
    const id = 600 + i;
    c.create(id);
    c.allocate(C.ARRAY_BUFFER, id, 262144);
    c.bind(C.COPY_WRITE_BUFFER, id);
    c.write(C.COPY_WRITE_BUFFER, 0, new Uint8Array(3000).fill(i + 1));
    c.copy(id, 500, 100, 8 * i, 256);
  }
  assert.equal(c.counts.readbacks, 0, "no readback for the staging pool");
  assert.equal(window.__gaiusGLStats.bufferShadowEvictions || 0, 0, "the staging pool evicts nothing");
  assert.ok(c.state.bufferShadowTotalBytes < 4194304 + 40 * 4096, "staging shadows are extents");
  assert.ok(c.state.bufferBytes.has(500), "the element heap keeps its shadow");
  assert.equal(c.state.bufferBytes.get(500)[8 * 39], 40, "the shadow of the element heap follows the copies");
  // The element heap's shadow holds exactly what the GPU holds.
  assert.deepEqual(c.state.bufferBytes.get(500).slice(0, 4096), c.physical(500).slice(0, 4096));
}
// A write that does not touch the extent grows a zero-based extent over the gap; a copy beyond
// the written bytes then reads zeros, and a whole-shadow consumer completes it without a readback.
{
  const c = setup("");
  c.create(30); c.create(31);
  c.allocate(C.ELEMENT_ARRAY_BUFFER, 31, 4096);
  c.allocate(C.ARRAY_BUFFER, 30, 8192);
  c.bind(C.COPY_WRITE_BUFFER, 30);
  c.write(C.COPY_WRITE_BUFFER, 100, new Uint8Array(20).fill(4));
  c.write(C.COPY_WRITE_BUFFER, 1000, new Uint8Array(20).fill(5));
  const extent = c.state.bufferPartial.get(30);
  assert.equal(extent.lo, 100); assert.equal(extent.bytes.byteLength, 920);
  c.copy(30, 31, 90, 0, 1000);
  const expected = new Uint8Array(1000); expected.fill(4, 10, 30); expected.fill(5, 910, 930);
  assert.deepEqual(c.physical(31).slice(0, 1000), expected, "zeros around the written bytes");
  assert.equal(c.counts.readbacks, 0);
  const whole = c.state.ensureBufferShadow(30, "test", false);
  assert.equal(whole.byteLength, 8192);
  assert.equal(c.counts.readbacks, 0, "a zero-based extent completes without a readback");
  assert.deepEqual(whole.slice(0, 1100), c.physical(30).slice(0, 1100));
  assert.ok(!c.state.bufferPartial.has(30));
}
// Without a zero base (written before it was required) a disjoint write replaces the extent and a
// range outside it comes from the GPU.
{
  const c = setup("");
  c.create(32); c.create(33);
  c.allocate(C.ELEMENT_ARRAY_BUFFER, 33, 4096);
  c.allocate(C.ARRAY_BUFFER, 32, 8192);
  c.bind(C.ARRAY_BUFFER, 32); c.write(C.ARRAY_BUFFER, 0, new Uint8Array(8).fill(1));
  c.bind(C.COPY_WRITE_BUFFER, 32);
  c.write(C.COPY_WRITE_BUFFER, 200, new Uint8Array(16).fill(2));
  c.write(C.COPY_WRITE_BUFFER, 1000, new Uint8Array(16).fill(3));
  assert.equal(c.state.bufferPartial.get(32).lo, 1000, "the newest disjoint write is the extent");
  assert.equal(c.counts.readbacks, 0);
  c.copy(32, 33, 200, 0, 16);
  assert.equal(c.counts.readbacks, 1, "bytes outside the extent are read back");
  assert.deepEqual(c.physical(33).slice(0, 16), new Uint8Array(16).fill(2));
}
// A buffer that needs the whole shadow (texel buffer, misaligned attributes) is not given an extent.
{
  const c = setup("");
  c.create(34);
  c.allocate(C.ARRAY_BUFFER, 34, 8192);
  c.state.markBufferShadowRequired(34, "texture-buffer");
  c.bind(C.ARRAY_BUFFER, 34);
  c.write(C.ARRAY_BUFFER, 64, new Uint8Array(16).fill(8));
  assert.ok(!c.state.bufferPartial.has(34));
  assert.equal(c.state.bufferBytes.get(34).byteLength, 8192);
  assert.equal(c.counts.readbacks, 0, "a zero-allocated buffer is completed from zeros");
}
// Deleting a buffer frees the bytes of its extent; ?gaiusShadowKeep=0 keeps whole shadows.
{
  const c = setup("");
  c.create(35); c.allocate(C.ARRAY_BUFFER, 35, 65536); c.bind(C.COPY_WRITE_BUFFER, 35);
  c.write(C.COPY_WRITE_BUFFER, 0, new Uint8Array(100).fill(1));
  assert.equal(c.state.bufferShadowTotalBytes, 100);
  c.state.deleteBufferShadow(35);
  assert.equal(c.state.bufferShadowTotalBytes, 0); assert.ok(!c.state.bufferPartial.has(35));
  const old = setup("?gaiusShadowKeep=0");
  old.create(36); old.allocate(C.ARRAY_BUFFER, 36, 65536); old.bind(C.COPY_WRITE_BUFFER, 36);
  old.write(C.COPY_WRITE_BUFFER, 0, new Uint8Array(100).fill(1));
  assert.equal(old.state.bufferPartial.size, 0, "no extents with retention off");
  assert.equal(old.state.bufferZeroFresh.size, 0, "no zero-state tracking with retention off");
}

// Over budget, a shadow nothing requires is evicted before a required one.
{
  const c = setup("");
  window.__gaiusMaxTotalBufferShadowBytes = 3 * 65536;
  c.create(10); c.create(11); c.create(12);
  c.state.markBufferShadowRequired(10, "test");
  for (const id of [10, 11]) {
    c.allocate(id === 11 ? C.ARRAY_BUFFER : C.COPY_WRITE_BUFFER, id, 65536);
    c.state.shadowBufferData(id, new Uint8Array(65536).fill(id), 65536);
  }
  c.state.touchBufferShadow(11, 65536);
  c.allocate(C.COPY_WRITE_BUFFER, 12, 65536);
  c.state.shadowBufferData(12, new Uint8Array(2 * 65536).fill(12), 2 * 65536);
  assert.ok(c.state.bufferBytes.has(10), "the required shadow stays");
  assert.ok(!c.state.bufferBytes.has(11), "the unrequired shadow goes first");
}
console.log(`browser-opengl-shadow-retention-smoke: ok (staging readbacks ${JSON.stringify(kept.perFrame)} vs old ${JSON.stringify(old.perFrame)})`);
