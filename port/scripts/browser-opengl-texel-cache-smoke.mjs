#!/usr/bin/env node
// BrowserOpenGL texel buffer cache (initializeTexelBufferCacheJs, WebAssembly GC): texBuffer
// without repeated uploads. A randomized differential run: the same writes into ring buffers
// (BrowserOpenGL's shadow, logical version and the WebGL buffer, as its write paths do) and
// the same texBuffer binds go to a context that runs the old texBuffer body and to one with the
// cache; after every bind the bytes of every texture must be identical. The cache must skip
// unchanged sources, upload only the written rows of a changed one, and its verify mode must
// agree.
//
//   node port/scripts/browser-opengl-texel-cache-smoke.mjs [BrowserOpenGL.java]

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
const layerBody = jsBody("initializeBufferLayerJs");
const cacheBody = jsBody("initializeTexelBufferCacheJs");
const texBufferBody = jsBody("texBuffer");
const texBuffer = new Function("target", "internalFormat", "buffer", texBufferBody);

const TEXTURE_2D = 0x0DE1, COPY_WRITE = 0x8F37, R8I = 0x8231, RGBA8 = 0x8058;

function context(withCache, search) {
  const textures = new Map();
  let bound = null;
  const calls = {texImage2D: 0, texSubImage2D: 0, uploadBytes: 0};
  const bytesPer = format => format === 0x1908 ? 4 : 1;
  const gl = {
    canvas: null, TEXTURE_2D, RED: 0x1903, RGBA: 0x1908, RGBA8, UNSIGNED_BYTE: 0x1401, BYTE: 0x1400,
    UNPACK_ALIGNMENT: 0x0CF5, UNPACK_ROW_LENGTH: 0x0CF2, UNPACK_SKIP_ROWS: 0x0CF3, UNPACK_SKIP_PIXELS: 0x0CF4,
    TEXTURE_MIN_FILTER: 0x2801, TEXTURE_MAG_FILTER: 0x2800, TEXTURE_WRAP_S: 0x2802, TEXTURE_WRAP_T: 0x2803,
    NEAREST: 0x2600, CLAMP_TO_EDGE: 0x812F,
    alignment: 4,
    pixelStorei(name, value) { if (name === 0x0CF5) this.alignment = value; },
    texImage2D(target, level, internalFormat, width, height, border, format, type, data) {
      calls.texImage2D++;
      assert.equal(this.alignment, 1, "uploads use alignment 1");
      const bytes = width * height * bytesPer(format);
      const store = {width, height, internalFormat, format, data: new Uint8Array(bytes)};
      if (data) {
        assert.equal(data.byteLength, bytes, "a texImage2D source covers the texture");
        store.data.set(new Uint8Array(data.buffer, data.byteOffset, bytes));
        calls.uploadBytes += bytes;
      }
      textures.set(bound, store);
    },
    texSubImage2D(target, level, x, y, width, height, format, type, data, srcOffset) {
      calls.texSubImage2D++;
      assert.equal(this.alignment, 1, "uploads use alignment 1");
      const store = textures.get(bound);
      assert.ok(store, "texSubImage2D into allocated storage");
      assert.ok(type === 0x1400 ? data instanceof Int8Array : data instanceof Uint8Array, "source view matches the type");
      const row = store.width * bytesPer(format);
      assert.equal(x, 0);
      assert.equal(width, store.width);
      const bytes = height * row;
      const from = (srcOffset || 0);
      assert.ok(from + bytes <= data.length, "texSubImage2D source covers the rows");
      store.data.set(new Uint8Array(data.buffer, data.byteOffset + from, bytes), y * row);
      calls.uploadBytes += bytes;
    },
    bindBuffer() {}, bufferData() {}, bufferSubData() {}, bindBufferRange() {}, bindBufferBase() {},
    copyBufferSubData() {}, getBufferSubData() {}, deleteBuffer() {}, createBuffer() { return {}; },
    vertexAttribPointer() {}, vertexAttribIPointer() {},
  };
  const state = {
    activeTextureUnit: 0, textureBindings: new Map(), textures: new Map(), buffers: new Map(),
    bufferBytes: new Map(), bufferSizes: new Map(), bufferVersions: new Map(), textureInfo: new Map(),
    textureBufferInfo: new Map(), unpackAlignment: 4, unpackRowLength: 0, unpackSkipRows: 0, unpackSkipPixels: 0,
    applyTextureParameter() {}, markBufferShadowRequired() {}, ensureBufferShadow(buffer) { return this.bufferBytes.get(buffer) || null; },
  };
  globalThis.window = {__gaiusWebGL: gl, __gaiusGL: state, __gaiusGLStats: {}};
  globalThis.location = {search: search || ""};
  if (withCache) {
    new Function(layerBody)();
    new Function(cacheBody)();
    assert.ok(state.texelBufferCache, "the cache is installed");
  }
  const ctx = {gl, state, textures, calls, stats: () => state.texelBufferCache && state.texelBufferCache.stats};
  ctx.enter = () => { globalThis.window = {__gaiusWebGL: gl, __gaiusGL: state, __gaiusGLStats: {}}; };
  // A logical buffer: its WebGL object, its shadow and BrowserOpenGL's version.
  ctx.allocate = (id, size, fill) => {
    let object = state.buffers.get(id);
    if (!object) { object = {id}; state.buffers.set(id, object); }
    const bytes = new Uint8Array(size);
    if (fill) bytes.forEach((_, i, a) => { a[i] = (i * 13 + id) & 255; });
    gl.bindBuffer(COPY_WRITE, object);
    if (fill) gl.bufferData(COPY_WRITE, bytes, 0x88E8); else gl.bufferData(COPY_WRITE, size, 0x88E8);
    state.bufferBytes.set(id, bytes);
    state.bufferSizes.set(id, size);
    state.bufferVersions.set(id, (state.bufferVersions.get(id) || 0) + 1);
  };
  ctx.write = (id, offset, data) => {
    const object = state.buffers.get(id);
    gl.bindBuffer(COPY_WRITE, object);
    gl.bufferSubData(COPY_WRITE, offset, data);
    state.bufferBytes.get(id).set(data, offset);
    state.bufferVersions.set(id, (state.bufferVersions.get(id) || 0) + 1);
  };
  ctx.bindTexture = (texture) => {
    if (!state.textures.has(texture)) state.textures.set(texture, {texture});
    state.textureBindings.set(0 * 65536 + (TEXTURE_2D & 65535), texture);
    bound = state.textures.get(texture);
  };
  ctx.texBuffer = (format, buffer) => { ctx.enter(); texBuffer(0x8C2A, format, buffer); };
  ctx.content = texture => {
    const store = textures.get(state.textures.get(texture));
    return store ? {width: store.width, height: store.height, data: store.data} : null;
  };
  return ctx;
}

let seed = 20261007;
const random = () => { seed = (Math.imul(seed, 1103515245) + 12345) >>> 0; return seed / 4294967296; };
const int = n => Math.floor(random() * n);

function run(search) {
  const old = context(false), cached = context(true, search);
  const both = fn => { old.enter(); fn(old); cached.enter(); fn(cached); };
  // Three ring buffers of cloud faces (R8I, 392 KB at Fancy) and one RGBA8 buffer.
  const sizes = {1: 392 * 1024, 2: 392 * 1024, 3: 392 * 1024, 4: 4096 * 4 * 3 + 100};
  for (const id of [1, 2, 3, 4]) both(c => c.allocate(id, sizes[id], true));
  for (let round = 0; round < 1500; round++) {
    const op = int(100);
    if (op < 30) {
      const id = 1 + int(4);
      const length = 1 + int(random() < 0.8 ? 4000 : 200000);
      const offset = int(Math.max(1, sizes[id] - length));
      const data = new Uint8Array(Math.min(length, sizes[id] - offset)).map(() => int(256));
      both(c => c.write(id, offset, data));
    } else if (op < 33) {
      const id = 1 + int(3);
      sizes[id] = 1000 + int(400 * 1024);
      const fill = random() < 0.5;
      both(c => c.allocate(id, sizes[id], fill));
    } else {
      const texture = 100 + int(3);
      const id = texture === 102 ? 4 : 1 + int(3);
      const format = id === 4 ? RGBA8 : R8I;
      both(c => { c.bindTexture(texture); c.texBuffer(format, id); });
      const a = old.content(texture), b = cached.content(texture);
      assert.ok(a && b, "both textures exist");
      assert.equal(b.width, a.width);
      assert.equal(b.height, a.height);
      assert.ok(Buffer.from(b.data).equals(Buffer.from(a.data)), `texture ${texture} differs at round ${round}`);
    }
  }
  return {old, cached};
}

const {old, cached} = run("?gaiusGlVerify=1");
const stats = cached.stats();
assert.ok(stats.binds > 900, "binds went through the cache");
assert.ok(stats.skips > 100, "unchanged sources are not uploaded again");
assert.ok(stats.partialUploads > 50, "changed sources upload only the written rows");
assert.equal(stats.verifyMismatches, 0, `verify: ${stats.lastVerifyMismatch}`);
assert.ok(stats.verifyChecks >= stats.binds, "verify compared every bind");
assert.ok(cached.calls.uploadBytes < old.calls.uploadBytes,
  `uploads shrink (${cached.calls.uploadBytes} vs ${old.calls.uploadBytes} bytes)`);
// Steady state: a source nothing writes is uploaded once.
{
  const c = context(true, "");
  c.enter();
  c.allocate(7, 392 * 1024, true);
  c.bindTexture(200);
  for (let i = 0; i < 10; i++) c.texBuffer(R8I, 7);
  assert.equal(c.calls.texImage2D, 1, "storage allocated once");
  assert.equal(c.stats().skips, 9, "nine binds skipped");
  // A write of 256 bytes uploads the rows holding them (4096 texels per row): one row.
  c.write(7, 5000, new Uint8Array(256).fill(9));
  const before = c.calls.uploadBytes;
  c.texBuffer(R8I, 7);
  assert.equal(c.calls.uploadBytes - before, 4096, "one row re-uploaded");
  assert.equal(c.content(200).data[5100], 9);
}
// ?gaiusTexelCache=0 keeps the old path.
{
  const c = context(true, "?gaiusTexelCache=0");
  assert.equal(c.state.texelBufferCache.enabled, false);
}
console.log(`browser-opengl-texel-cache-smoke: ok (${JSON.stringify({binds: stats.binds, skips: stats.skips,
  partial: stats.partialUploads, full: stats.fullUploads, uploadBytes: cached.calls.uploadBytes, oldUploadBytes: old.calls.uploadBytes})})`);
