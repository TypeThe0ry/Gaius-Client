import {test} from "node:test";
import assert from "node:assert/strict";
import {clientCacheWrites, cacheObservation} from "./wasm-cache-evidence.mjs";
const client = "http://localhost/classes.0123456789abcdef.wasm?gaiusWasmHttp=1";
const event = (name, ts, args, tid = 1) => ({name, ts, args, pid: 1, tid});
test("cache observation separates shader writes and interleaved background threads", () => {
  const events = [event("v8.wasm.compiledModule", 1000, {url: client}),
    event("v8.wasm.compiledModule", 1001, {url: "http://localhost/shader.wasm"}, 2),
    event("v8.wasm.cachedModule", 2000, {producedCacheSize: 99}, 2),
    event("v8.wasm.cachedModule", 3000, {producedCacheSize: 6000000})];
  assert.deepEqual(clientCacheWrites(events), [{url: client, ts: 3000, producedCacheSize: 6000000}]);
  assert.equal(cacheObservation(events, 4000, 15).ready, false);
  assert.equal(cacheObservation(events, 18000, 15).ready, true);
});
test("serialization is required; compile, cache hit and orphan cache events cannot acknowledge a write", () => {
  const events = [event("v8.wasm.cachedModule", 1000, {}),
    event("v8.wasm.moduleCacheHit", 2000, {url: client}),
    event("v8.wasm.compiledModule", 3000, {url: client})];
  assert.deepEqual(clientCacheWrites(events), []);
  assert.equal(cacheObservation(events, 100000000, 15).ready, false);
});
test("later tiered serialization resets the writeback observation window", () => {
  const events = [event("v8.wasm.compiledModule", 1000, {url: client}),
    event("v8.wasm.cachedModule", 2000, {producedCacheSize: 10}),
    event("v8.wasm.compiledModule", 20000, {url: client}),
    event("v8.wasm.cachedModule", 21000, {producedCacheSize: 20})];
  assert.equal(cacheObservation(events, 22000, 15).ready, false);
  assert.equal(cacheObservation(events, 36000, 15).ready, true);
});

test("trace buffer delivery order cannot shorten the last serialization quiet window", () => {
  const early = [event("v8.wasm.compiledModule", 1000, {url: client}),
    event("v8.wasm.cachedModule", 2000, {producedCacheSize: 10})];
  const late = [event("v8.wasm.compiledModule", 20000, {url: client}, 2),
    event("v8.wasm.cachedModule", 21000, {producedCacheSize: 20}, 2)];
  assert.equal(cacheObservation([...late, ...early], 22000, 15).ready, false);
});
