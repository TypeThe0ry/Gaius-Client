#!/usr/bin/env node
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("gpu-glue.js", import.meta.url), "utf8");
// Mock IndexedDB: a map of databases by name, each a map of records. Counts every request kind so
// the smoke can assert how the cache is read (one read-only getAll, never a cursor).
const databases = new Map();
const stats = {opens: [], getAll: 0, cursors: 0, readwrite: 0, readonly: 0, deletes: [], getAllCounts: []};
let timers = [];
const realSetTimeout = setTimeout;
// Short timers run for real (the slicing of a large read); the long ones (background deletes, the
// flush timer) wait for runIdle().
const idleSetTimeout = (fn, delay) => {
  if (!(delay >= 200)) return realSetTimeout(fn, delay);
  timers.push(fn);
  return timers.length;
};
async function runIdle() {
  // runs the background work the glue scheduled with requestIdleCallback/setTimeout
  for (let round = 0; round < 6 && timers.length; round++) {
    const batch = timers; timers = [];
    for (const fn of batch) fn();
    await new Promise((done) => setImmediate(done));
  }
}

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

function database(name, records) {
  const db = {
    name,
    objectStoreNames: {contains: () => true},
    createObjectStore() {},
    close() {},
    transaction(storeName, mode) {
      assert.equal(storeName, "wgsl");
      stats[mode === "readwrite" ? "readwrite" : "readonly"]++;
      const transaction = {
        oncomplete: null,
        onerror: null,
        objectStore() {
          return {
            put(value) {
              records.set(value.key, clone(value));
              queueMicrotask(() => transaction.oncomplete?.());
            },
            delete(key) {
              records.delete(key);
              queueMicrotask(() => transaction.oncomplete?.());
            },
            getAll(range, count) {
              assert.equal(mode, "readonly", "the cache is read in a read-only transaction");
              stats.getAll++;
              stats.getAllCounts.push(count);
              const request = {onsuccess: null, onerror: null};
              queueMicrotask(() => {
                request.result = [...records.values()].slice(0, count ?? Infinity).map(clone);
                request.onsuccess?.();
                queueMicrotask(() => transaction.oncomplete?.());
              });
              return request;
            },
            openCursor() {
              stats.cursors++;
              throw new Error("the cache must not be read with a cursor");
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
  open(name) {
    stats.opens.push(name);
    const created = !databases.has(name);
    if (created) databases.set(name, new Map());
    const request = {result: database(name, databases.get(name)), onupgradeneeded: null, onsuccess: null, onerror: null,
      onblocked: null};
    queueMicrotask(() => {
      if (created) request.onupgradeneeded?.();
      request.onsuccess?.();
    });
    return request;
  },
  databases() { return Promise.resolve([...databases.keys()].map((name) => ({name, version: 1}))); },
  deleteDatabase(name) {
    stats.deletes.push(name);
    databases.delete(name);
    const request = {onsuccess: null, onerror: null, onblocked: null};
    queueMicrotask(() => request.onsuccess?.());
    return request;
  },
};

function fixture(shouldTranslate, globals = {}) {
  let calls = 0;
  const context = vm.createContext({
    performance, ...globals,
    console, indexedDB, Uint8Array, Int8Array, Int32Array, ArrayBuffer, String,
    Map, Set, WeakMap, Promise, Number, Date, Math, setTimeout: idleSetTimeout, clearTimeout() {}, queueMicrotask,
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

const V1 = "gaius-wgsl-cache-v1-fixture-profile";
const V2 = "gaius-wgsl-cache-v2-fixture-profile";
const records = () => databases.get(V2);
const input = new Uint8Array([0x03, 0x02, 0x23, 0x07, 0x01]);

// ---- first boot: translate, persist (also what was translated while the database was opening)
const first = fixture(true);
assert.equal(first.gpu.installTranslator({}), true);
assert.equal(first.gpu.translateSpirv(input, "", 1), 1);
assert.equal(await first.gpu.translationCacheReady, 0);
await new Promise((done) => setImmediate(done));
assert.equal(records().size, 1, "a translation produced while the database opened is persisted with the first flush");
const expected = {
  stage: first.gpu.translatedStage(),
  wgsl: first.gpu.translatedWgsl(),
  word: first.gpu.translatedWord(0),
};
await first.gpu.flushTranslationCache();
assert.equal(first.calls(), 1, "fresh translation invokes the translator once");
assert.deepEqual([...records().keys()], [first.gpu.translationKey(input, "", 1)]);
assert.ok([...records().keys()][0].length < 120, "the persisted key is compact");

// ---- warm boot: one read-only getAll (bounded), no cursor, same bytes
stats.getAll = 0; stats.getAllCounts = []; stats.cursors = 0; stats.readwrite = 0; stats.readonly = 0;
const second = fixture(false);
assert.equal(second.gpu.installTranslator({}), true);
assert.equal(await second.gpu.translationCacheReady, 1);
assert.equal(stats.getAll, 1, "the cache is read with one getAll");
assert.deepEqual(stats.getAllCounts, [1024 + 256], "the read is bounded");
assert.equal(stats.cursors, 0);
assert.equal(stats.readwrite, 0, "no read-write transaction on the boot path");
assert.equal(second.gpu.translateSpirv(input, "", 1), 1);
assert.deepEqual({stage: second.gpu.translatedStage(), wgsl: second.gpu.translatedWgsl(),
  word: second.gpu.translatedWord(0)}, expected, "persistent hit changed translation bytes");
assert.equal(second.calls(), 0, "persistent hit avoids a fresh translator call");
const telemetry = second.gpu.pipelineTelemetry().translationCache;
assert.equal(telemetry.hits, 1);
assert.equal(telemetry.database, V2);
assert.equal(telemetry.rows, 1);
assert.equal(telemetry.stale, 0);

// ---- SHA-256 (the key function) against the standard vectors and node:crypto
const sha = (bytes) => createHash("sha256").update(bytes).digest("hex");
const glue = fixture(true).gpu;
const text = (value) => new Uint8Array(Buffer.from(value, "latin1"));
const vectors = [
  ["", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"],
  ["abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"],
  ["abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
    "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"],
  ["a".repeat(1000000), "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"],
];
for (const [message, digest] of vectors) {
  assert.equal(glue.sha256Hex(text(message)), digest, `sha256 vector (${message.length} bytes)`);
  assert.equal(glue.sha256Hex(new Int8Array(text(message).buffer)), digest, "Int8Array input (SPIR-V arrives signed)");
}
let seed = 0x2545f491;
const random = () => (seed = Math.imul(seed ^ (seed >>> 15), 0x2c1b3c6d) + 0x6d2b79f5 >>> 0) & 0xff;
for (let length = 0; length <= 300; length++) {
  const bytes = new Int8Array(length).map(() => random() - 128);
  assert.equal(glue.sha256Hex(bytes), sha(Buffer.from(bytes.buffer)), `sha256 of ${length} random bytes`);
}
for (const length of [4095, 4096, 65535, 70000, 262145]) {
  const bytes = new Int8Array(length).map(() => random() - 128);
  assert.equal(glue.sha256Hex(bytes), sha(Buffer.from(bytes.buffer)), `sha256 of ${length} random bytes`);
}

// ---- key layout: flags | texel buffers | length | digest; every input matters
const big = new Int8Array(70000).map(() => random() - 128);
const key = glue.translationKey(big, "2:r32f,5:rgba8unorm", 5);
assert.equal(key, "5|2:r32f,5:rgba8unorm|70000|" + sha(Buffer.from(big.buffer)));
assert.ok(key.length < 120, "a 70 KB module gives a compact key");
const tweaked = big.slice(); tweaked[69999] ^= 1;
const keys = new Set([key, glue.translationKey(tweaked, "2:r32f,5:rgba8unorm", 5), glue.translationKey(big, "2:r32f", 5),
  glue.translationKey(big, "2:r32f,5:rgba8unorm", 1), glue.translationKey(big.subarray(0, 69999), "2:r32f,5:rgba8unorm", 5)]);
assert.equal(keys.size, 5, "a changed byte, texel declaration, flag or length gives another key");
// Same length and same first/last bytes, differing in the middle only: the old compact FNV pair is not a guard, the digest is.
assert.notEqual(glue.translationKey(new Int8Array([1, 2, 3, 4, 5, 6, 7, 8]), "", 0),
  glue.translationKey(new Int8Array([1, 2, 3, 9, 5, 6, 7, 8]), "", 0));

// ---- migration: the legacy database (full-hex keys) is not read and goes away in the background
databases.clear(); stats.deletes = []; stats.opens = []; stats.getAll = 0; stats.cursors = 0; timers = [];
const legacyHex = "0302230701".repeat(6000);
databases.set(V1, new Map([["1||5|811c9dc5cbf29ce4|" + legacyHex, {
  key: "1||5|811c9dc5cbf29ce4|" + legacyHex, version: "translator-test-v1", stage: 0, count: 1,
  words: new Int32Array(8).buffer, wgsl: "// legacy", used: 1}]]));
const migrated = fixture(true);
assert.equal(migrated.gpu.installTranslator({}), true);
assert.equal(await migrated.gpu.translationCacheReady, 0, "legacy records are not loaded");
assert.deepEqual(stats.opens, [V2], "only the v2 database is opened");
assert.equal(migrated.gpu.translateSpirv(input, "", 1), 1);
assert.equal(migrated.calls(), 1, "the legacy record did not answer");
assert.ok(databases.has(V1), "the legacy database is not deleted on the boot path");
await runIdle();
assert.deepEqual(stats.deletes, [V1], "the legacy database is deleted in the background");
assert.ok(!databases.has(V1) && databases.has(V2));
// Nothing left to retire: later boots do not issue deletes.
stats.deletes = [];
const later = fixture(true);
later.gpu.installTranslator({});
await later.gpu.translationCacheReady;
await runIdle();
assert.deepEqual(stats.deletes, [], "no delete once the legacy database is gone");

// ---- stale and surplus rows: ignored by the read, deleted afterwards in the background
function row(index, extra = {}) {
  const value = {key: "k" + String(index).padStart(5, "0"), version: "translator-test-v1", stage: 0, count: 1,
    words: new Int32Array(8).buffer, wgsl: "// " + index, used: index};
  return Object.assign(value, extra);
}
databases.clear(); stats.getAll = 0; stats.getAllCounts = []; timers = [];
const rows = new Map();
for (let i = 0; i < 1100; i++) rows.set(row(i).key, row(i));
rows.set("old-version", row(2000, {key: "old-version", version: "an older translator"}));
rows.set("bad-words", row(2001, {key: "bad-words", words: new Int32Array(3).buffer}));
rows.set("bad-count", row(2002, {key: "bad-count", count: 5000}));
rows.set("bad-wgsl", row(2003, {key: "bad-wgsl", wgsl: null}));
databases.set(V2, rows);
const surplus = fixture(false);
assert.equal(surplus.gpu.installTranslator({}), true);
assert.equal(await surplus.gpu.translationCacheReady, 1024, "at most 1024 rows are loaded");
assert.equal(stats.getAll, 1);
const t2 = surplus.gpu.pipelineTelemetry().translationCache;
assert.equal(t2.loaded, 1024);
assert.equal(t2.rows, 1104);
assert.ok(rows.size > 1100, "stale rows are still there when the boot finishes");
await runIdle();
assert.equal(rows.size, 1024, "invalid, outdated and surplus rows are deleted in the background");
assert.ok(!rows.has("old-version") && !rows.has("bad-words") && !rows.has("bad-count") && !rows.has("bad-wgsl"));

// ---- a large read is turned into entries in slices (a clock that advances 5 ms per read forces a yield every 16 rows)
databases.clear(); timers = [];
const many = new Map();
for (let i = 0; i < 200; i++) many.set(row(i).key, row(i));
databases.set(V2, many);
let tick = 0;
const slowClock = {now: () => (tick += 5)};
const sliced = fixture(false, {performance: slowClock});
assert.equal(sliced.gpu.installTranslator({}), true);
assert.equal(await sliced.gpu.translationCacheReady, 200, "all rows arrive although the work was sliced");

// ---- keys written by the previous generation of this smoke (per-byte hex) must not be produced any more
databases.clear(); timers = [];
for (const globals of [{}, {TextDecoder}]) {
  for (const [spirv, texels, flags] of [[input, "", 1], [new Uint8Array(256).map((_, index) => index), "", 3],
    [big, "2:r32f,5:rgba8unorm", 5], [new Int8Array(0), "", 0]]) {
    databases.clear();
    const keyed = fixture(true, globals);
    assert.equal(keyed.gpu.installTranslator({}), true);
    await keyed.gpu.translationCacheReady;
    assert.equal(keyed.gpu.translateSpirv(spirv, texels, flags), 1);
    await keyed.gpu.flushTranslationCache();
    const bytes = Buffer.from(spirv.buffer, spirv.byteOffset, spirv.byteLength);
    assert.deepEqual([...databases.get(V2).keys()], [flags + "|" + texels + "|" + spirv.length + "|" + sha(bytes)],
      `persisted key (${spirv.length} bytes)`);
  }
}
console.log("gpu-translation-cache-smoke: SHA-256 keys, one read-only getAll, v1 retirement, stale-row cleanup, sliced read and byte-identical hits PASS");
