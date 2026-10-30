#!/usr/bin/env node
// BrowserOpenGL divisor cache (initializeDivisorCacheJs, WebAssembly GC): vertexAttribDivisor
// calls that would not change the bound vertex array are skipped. A randomized differential run
// against a model WebGL2 context keeps per-array divisors identical to the uncached context
// (binds, divisor changes, deletes and a context loss), and repeated calls are skipped.
//
//   node port/scripts/browser-opengl-divisor-cache-smoke.mjs [BrowserOpenGL.java]

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
  return source.slice(s, source.indexOf('"""', s));
}
const body = jsBody("initializeDivisorCacheJs");

function model() {
  const listeners = {};
  const defaultArray = {name: "default", divisors: new Map()};
  let bound = defaultArray;
  const gl = {
    calls: 0,
    canvas: {addEventListener(type, fn) { (listeners[type] ??= []).push(fn); }},
    createVertexArray() { return {divisors: new Map()}; },
    bindVertexArray(array) { bound = array || defaultArray; },
    vertexAttribDivisor(index, divisor) { this.calls++; bound.divisors.set(index, divisor >>> 0); },
    deleteVertexArray(array) { if (array) { array.deleted = true; if (bound === array) bound = defaultArray; } },
    // A lost and restored context starts every array from its defaults.
    lose() { defaultArray.divisors.clear(); bound = defaultArray; (listeners.webglcontextlost || []).forEach(fn => fn()); },
    divisorsOf(array) { return [...(array || defaultArray).divisors.entries()].sort((a, b) => a[0] - b[0]); },
  };
  return gl;
}
function install(gl, search) {
  globalThis.window = {__gaiusWebGL: gl, __gaiusGL: {}};
  globalThis.location = {search};
  new Function(body)();
  return window.__gaiusGL.divisorCache;
}

let seed = 77;
const random = () => { seed = (Math.imul(seed, 1103515245) + 12345) >>> 0; return seed / 4294967296; };
const int = n => Math.floor(random() * n);
const plain = model(), cached = model();
const cache = install(cached, "");
assert.ok(cache, "the cache is installed");
const arrays = [[null, null]];
for (let i = 0; i < 4; i++) arrays.push([plain.createVertexArray(), cached.createVertexArray()]);
for (let round = 0; round < 5000; round++) {
  const op = int(100);
  if (op < 25) {
    const [a, b] = arrays[int(arrays.length)];
    plain.bindVertexArray(a); cached.bindVertexArray(b);
  } else if (op < 90) {
    const index = int(8), divisor = random() < 0.7 ? 0 : 1 + int(2);
    plain.vertexAttribDivisor(index, divisor); cached.vertexAttribDivisor(index, divisor);
  } else if (op < 95 && arrays.length > 1) {
    const at = 1 + int(arrays.length - 1);
    const [a, b] = arrays[at];
    plain.deleteVertexArray(a); cached.deleteVertexArray(b);
    arrays[at] = [plain.createVertexArray(), cached.createVertexArray()];
  } else if (op === 99) {
    plain.lose(); cached.lose();
    for (let i = 1; i < arrays.length; i++) arrays[i] = [plain.createVertexArray(), cached.createVertexArray()];
  }
  for (const [a, b] of arrays) {
    assert.deepEqual(cached.divisorsOf(b), plain.divisorsOf(a), `divisors differ at round ${round}`);
  }
}
assert.ok(cache.skipped > 500, "repeated divisors are skipped");
assert.ok(cached.calls < plain.calls, `fewer calls (${cached.calls} vs ${plain.calls})`);
// Steady state: a draw re-applying the same eight divisors to the same array every frame.
{
  const gl = model();
  install(gl, "");
  const array = gl.createVertexArray();
  for (let frame = 0; frame < 100; frame++) {
    gl.bindVertexArray(array);
    for (let index = 0; index < 8; index++) gl.vertexAttribDivisor(index, index < 3 ? 0 : 1);
    gl.bindVertexArray(null);
  }
  assert.equal(gl.calls, 8, "only the first frame's divisors reach the context");
}
assert.equal(install(model(), "?gaiusDivisorCache=0"), undefined, "?gaiusDivisorCache=0 keeps every call");
console.log(`browser-opengl-divisor-cache-smoke: ok (${cached.calls} of ${plain.calls} calls, ${cache.skipped} skipped)`);
