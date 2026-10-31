#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("gpu-glue.js", import.meta.url), "utf8");
const records = new Map();
let schemaReady = false;

function clone(value) {
  return {
    key: value.key,
    version: value.version,
    stage: value.stage,
    count: value.count,
    words: value.words.slice(0),
    wgsl: value.wgsl,
    used: value.used,
  };
}

function database() {
  const db = {
    objectStoreNames: {contains: () => schemaReady},
    createObjectStore() { schemaReady = true; },
    close() {},
    transaction() {
      const transaction = {
        oncomplete: null,
        onerror: null,
        objectStore() {
          return {
            put(value) {
              records.set(value.key, clone(value));
              queueMicrotask(() => transaction.oncomplete?.());
            },
            openCursor() {
              const request = {onsuccess: null, onerror: null};
              const values = [...records.values()];
              let index = 0;
              const emit = () => {
                const value = values[index];
                const cursor = value ? {
                  value: clone(value),
                  delete() { records.delete(value.key); },
                  continue() { index++; queueMicrotask(emit); },
                } : null;
                request.result = cursor;
                request.onsuccess?.();
                if (!cursor) transaction.oncomplete?.();
              };
              queueMicrotask(emit);
              return request;
            },
          };
        },
      };
      return transaction;
    },
  };
  return db;
}

const indexedDB = {
  open() {
    const request = {result: database(), onupgradeneeded: null, onsuccess: null, onerror: null,
      onblocked: null};
    queueMicrotask(() => {
      if (!schemaReady) request.onupgradeneeded?.();
      request.onsuccess?.();
    });
    return request;
  },
};

function fixture(shouldTranslate, globals = {}) {
  let calls = 0;
  const context = vm.createContext({
    ...globals,
    console, performance, indexedDB, Uint8Array, Int8Array, Int32Array, ArrayBuffer, String,
    Map, Set, WeakMap, Promise, Number, Date, Math, setTimeout, clearTimeout,
    GaiusKernelMirror: {enabled: name => name === "shaderxlat"},
    GaiusShaderXlatJob: {instantiateSync() {
      return {translate() {
        calls++;
        if (!shouldTranslate) throw new Error("fresh translation was not allowed");
        return {stage: "vertex", wgsl: "@vertex fn main() -> @builtin(position) vec4f { return vec4f(0.0); }",
          bindings: [{type: "uniform", group: 0, binding: 0, uniform: 0, size: 16, used: true}]};
      }};
    }},
    __gaiusProfileId: "fixture-profile",
    __gaiusGpuTranslationCacheVersion: "translator-test-v1",
  });
  vm.runInContext(source, context, {filename: "gpu-glue.js"});
  return {context, gpu: context.GaiusWebGpu, calls: () => calls};
}

const first = fixture(true);
assert.equal(first.gpu.installTranslator({}), true);
const input = new Uint8Array([0x03, 0x02, 0x23, 0x07, 0x01]);
assert.equal(first.gpu.translateSpirv(input, "", 1), 1);
assert.equal(await first.gpu.translationCacheReady, 0);
const expected = {
  stage: first.gpu.translatedStage(),
  wgsl: first.gpu.translatedWgsl(),
  word: first.gpu.translatedWord(0),
};
await first.gpu.flushTranslationCache();
assert.equal(records.size, 1, "first translation was persisted");
assert.equal(first.calls(), 1, "fresh translation invokes the translator once");

const second = fixture(false);
assert.equal(second.gpu.installTranslator({}), true);
assert.equal(await second.gpu.translationCacheReady, 1);
assert.equal(second.gpu.translateSpirv(input, "", 1), 1);
assert.deepEqual({stage: second.gpu.translatedStage(), wgsl: second.gpu.translatedWgsl(),
  word: second.gpu.translatedWord(0)}, expected, "persistent hit changed translation bytes");
assert.equal(second.calls(), 0, "persistent hit avoids a fresh translator call");
assert.equal(second.gpu.pipelineTelemetry().translationCache.hits, 1);

// The persisted key must stay the one older builds wrote (the per-byte padStart hex string), with
// TextDecoder (browsers) and without it, so existing IndexedDB entries keep hitting.
function referenceKey(spirv, texelBuffers, flags) {
  let hash = 0x811c9dc5;
  let high = 0xcbf29ce4;
  let content = "";
  for (let i = 0; i < spirv.length; i++) {
    const byte = spirv[i] & 0xff;
    hash = Math.imul(hash ^ byte, 0x01000193) >>> 0;
    high = Math.imul(high ^ hash, 0x01000193) >>> 0;
    content += byte.toString(16).padStart(2, "0");
  }
  return flags + "|" + texelBuffers + "|" + spirv.length + "|" + hash.toString(16) + high.toString(16)
    + "|" + content;
}
let seed = 0x2545f491;
const random = () => (seed = Math.imul(seed ^ (seed >>> 15), 0x2c1b3c6d) + 0x6d2b79f5 >>> 0) & 0xff;
const all = new Uint8Array(256).map((_, index) => index);
const large = new Int8Array(70000).map(() => random() - 128);
const cases = [[input, "", 1], [all, "", 3], [large, "2:r32f,5:rgba8unorm", 5], [new Int8Array(all.buffer), "", 0]];
for (const globals of [{}, {TextDecoder}]) {
  for (const [spirv, texels, flags] of cases) {
    records.clear();
    const keyed = fixture(true, globals);
    assert.equal(keyed.gpu.installTranslator({}), true);
    await keyed.gpu.translationCacheReady;
    assert.equal(keyed.gpu.translateSpirv(spirv, texels, flags), 1);
    await keyed.gpu.flushTranslationCache();
    assert.deepEqual([...records.keys()], [referenceKey(spirv, texels, flags)],
      `translation key changed (${globals.TextDecoder ? "TextDecoder" : "fallback"}, ${spirv.length} bytes)`);
  }
}
console.log("gpu-translation-cache-smoke: cross-context WGSL words/WGSL hit is byte-identical and keys are unchanged PASS");
