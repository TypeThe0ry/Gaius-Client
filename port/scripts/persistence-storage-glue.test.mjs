#!/usr/bin/env node
// Browser-free executable checks for the storage worker's real JavaScript glue.
// The worker is evaluated in node:vm and receives a tiny SyncAccessHandle double;
// no browser, TeaVM build, or generated bundle is involved.
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

const source = await readFile(new URL("../web/singleplayer/server-worker-bootstrap.js", import.meta.url), "utf8");
assert.match(source, /function scanOpfsRegionStore\(\)/);
assert.match(source, /function drainPendingChanges\(\)/);
assert.match(source, /function evictRegionCache\(requiredBytes, excludedPath\)/);
assert.match(source, /NoModificationAllowedError/);
assert.match(source, /function normalize\(path\)/);

class Handle {
  constructor(bytes = 0) { this.bytes = new Uint8Array(bytes); this.flushed = 0; this.failAfter = Infinity; }
  getSize() { return this.bytes.length; }
  read(out, {at}) { if (at >= this.bytes.length) return 0; const n = Math.min(out.length, this.bytes.length - at); out.set(this.bytes.subarray(at, at + n)); return n; }
  write(input, {at}) {
    if (at >= this.failAfter) return 0;
    const n = Math.min(input.length, this.failAfter - at);
    if (n <= 0) return 0;
    if (at + n > this.bytes.length) { const next = new Uint8Array(at + n); next.set(this.bytes); this.bytes = next; }
    this.bytes.set(input.subarray(0, n), at); return n;
  }
  truncate(size) { this.bytes = this.bytes.slice(0, size); }
  flush() { this.flushed++; }
}
class QuotaHandle extends Handle {
  write(input, position) { throw Object.assign(new Error("quota exceeded"), {name: "QuotaExceededError"}); }
}

function makeContext(handle, budget = 128) {
  const context = {
    URL, TextEncoder, TextDecoder, Uint8Array, Uint32Array, ArrayBuffer, DataView, Map, Proxy,
    Object, Math, Number, String, Date, Promise, Error, TypeError, JSON, performance,
    setTimeout, clearTimeout, queueMicrotask, navigator: {},
    __gaiusServerWorldId: "w", __gaiusRegionCacheBudgetBytes: budget,
    __gaiusStorageDatabaseName: "test", __gaiusStorageSchema: 2,
    __gaiusStoragePrefix: "gaius.fs.v2:test:", __gaiusStorageOpfsDirectory: "regions-v2-test",
    postMessage(message) { context.events.push(message); }, events: [],
    indexedDB: undefined,
  };
  context.globalThis = context;
  vm.createContext(context);
  vm.runInContext(source + "\n globalThis.__test = {appendOpfsRegion, scanOpfsRegionStore, appendOpfsPatch, putRegionCache, evictRegionCache, normalize, regionIndex, regionCache, storageStats};", context);
  context.__test.opfsAccessHandle = handle;
  // Lexical binding is deliberately selected through the worker's own globals.
  vm.runInContext("opfsAccessHandle = globalThis.__test.opfsAccessHandle;", context);
  return context;
}

// QuotaExceededError is surfaced to the caller and does not publish a partial
// record. The caller can retry after freeing storage.
{
  const handle = new QuotaHandle(); const c = makeContext(handle);
  assert.throws(() => c.__test.appendOpfsRegion("/gaius/saves/w/region/quota.0.0.mca", new Uint8Array([7]), false), /quota exceeded/);
  assert.equal(handle.getSize(), 0);
}

// A short write must fail before the commit header is written: the resulting tail is
// truncated on the next scan instead of being indexed as a valid region.
{
  const handle = new Handle(); const c = makeContext(handle);
  handle.failAfter = 20;
  assert.throws(() => c.__test.appendOpfsRegion("/gaius/saves/w/region/r.0.0.mca", new Uint8Array([1, 2, 3]), false), /wrote zero bytes/);
  assert.equal(handle.flushed, 0);
  handle.failAfter = Infinity;
  c.__test.scanOpfsRegionStore();
  assert.equal(c.__test.regionIndex.size, 0);
}

// The launcher lease test executes the same lease source in VM-backed fake tabs,
// covering Web Locks/localStorage handoff and live second-tab rejection without Chrome.
{
  const result = spawnSync(process.execPath, [fileURLToPath(new URL("./runtime-lease-smoke.mjs", import.meta.url))], {encoding: "utf8"});
  assert.equal(result.status, 0, result.stderr || result.stdout);
}

// A committed record survives a fresh scan and a patch is rejected atomically when
// its payload does not match declared ranges.
{
  const handle = new Handle(); const c = makeContext(handle);
  const path = "/gaius/saves/w/region/r.0.0.mca";
  c.__test.appendOpfsRegion(path, new Uint8Array([1, 2, 3, 4]), false);
  assert.throws(() => c.__test.appendOpfsPatch(path, 4, [0], [2], new Uint8Array([9])), /payload length mismatch/);
  c.__test.scanOpfsRegionStore();
  assert.equal(c.__test.regionIndex.get(path).length, 4);
}

// LRU evicts the least recently used clean entry while dirty entries remain pinned.
{
  const c = makeContext(new Handle(), 65536);
  assert.equal(c.__test.putRegionCache("a", new Uint8Array(32000), {dirty: false, pinned: false, flushing: false, version: 0}), true);
  assert.equal(c.__test.putRegionCache("b", new Uint8Array(32000), {dirty: true, pinned: false, flushing: false, version: 1}), true);
  c.__test.regionCache.get("a").lastAccess = 1;
  c.__test.regionCache.get("b").lastAccess = 2;
  c.__test.evictRegionCache(32000, "c");
  assert.ok(c.__test.regionCache.has("b"));
  assert.ok(!c.__test.regionCache.has("a"));
}

// Path normalization is the common key used by world rename/delete and flush queues.
{
  const c = makeContext(new Handle());
  assert.equal(c.__test.normalize("/gaius/saves/w/./region/../level.dat"), "/gaius/saves/w/level.dat");
}

console.log("ok persistence storage glue: partial write recovery, patch atomicity, LRU, normalization");
