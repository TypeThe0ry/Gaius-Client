import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("mesh-kernel-job.js", import.meta.url), "utf8");
const records = new Map();
let schemaReady = false;

function makeDatabase() {
  return {
    objectStoreNames: {contains: () => schemaReady},
    createObjectStore() { schemaReady = true; },
    transaction() {
      const tx = {oncomplete: null, onerror: null};
      tx.objectStore = () => ({
        get(key) {
          const request = {onsuccess: null, onerror: null, result: undefined};
          queueMicrotask(() => {
            request.result = records.get(key);
            request.onsuccess?.();
            tx.oncomplete?.();
          });
          return request;
        },
        put(value) {
          records.set(value.key, {key: value.key, schema: value.schema, scope: value.scope,
            bytes: value.bytes.slice(0)});
          queueMicrotask(() => tx.oncomplete?.());
        },
        delete(key) {
          records.delete(key);
          queueMicrotask(() => tx.oncomplete?.());
        },
      });
      return tx;
    },
  };
}

const indexedDB = {
  open() {
    const request = {result: makeDatabase(), onupgradeneeded: null, onsuccess: null,
      onerror: null, onblocked: null};
    queueMicrotask(() => {
      if (!schemaReady) request.onupgradeneeded?.();
      request.onsuccess?.();
    });
    return request;
  },
};

function fixture(hash) {
  const context = vm.createContext({
    console, Uint8Array, Int8Array, Uint16Array, Uint32Array, Int32Array, Float32Array,
    ArrayBuffer, DataView, BigInt, Map, Set, Promise, queueMicrotask, indexedDB,
    setTimeout, clearTimeout, URLSearchParams,
    __gaiusProfileId: "fixture-profile", __gaiusResourceContentHash: hash,
    GaiusKernelMirror: {enabled: () => true},
    GaiusMeshJob: {enabled: () => true, estimateResultBytes: () => 0,
      session: (_, epoch) => ({epoch, preload: async () => {}, mesh: async () => ({})})},
    __gaiusKernels: {enabled: () => true, runtime: async () => ({available: () => true})},
  });
  vm.runInContext(source, context, {filename: "mesh-kernel-job.js"});
  return context.__gaiusMeshKernel;
}

function table() {
  const bytes = new Uint8Array(120);
  const view = new DataView(bytes.buffer);
  view.setUint32(0, 0x42544d47, true);
  view.setUint16(4, 1, true);
  view.setUint16(6, 2, true);
  view.setUint32(8, 7, true);
  view.setUint32(12, 1, true);
  return bytes;
}

const VANILLA = "packs:vanilla,";

const first = fixture("asset-a");
for (let i = 0; i < 5; i++) await Promise.resolve();
assert.equal(first.tableCacheReady(), true);
const fresh = table();
assert.equal(first.setTable(fresh, fresh.length, 7), true);
await new Promise(resolve => setTimeout(resolve, 0));
assert.equal(records.size, 0, "setTable alone must not persist: Java decides which tables may be cached");
first.storeTable(VANILLA);
await new Promise(resolve => setTimeout(resolve, 0));
assert.equal(records.size, 1, "fresh model table was not persisted");

const second = fixture("asset-a");
for (let i = 0; i < 5; i++) await Promise.resolve();
assert.equal(second.tableCacheReady(), true);
assert.equal(second.takeCachedTable("packs:vanilla,programmer_art,"), null,
  "a table cached under another resource pack stack was reused");
assert.deepEqual([...second.takeCachedTable(VANILLA)], [...fresh], "cached table bytes changed");
assert.equal(second.takeCachedTable(VANILLA), null, "cache entry was not consumed exactly once");

const unscoped = fixture("asset-a");
for (let i = 0; i < 5; i++) await Promise.resolve();
assert.equal(unscoped.takeCachedTable(null), null, "a stack with a user or server pack read the cache");
assert.equal(unscoped.takeCachedTable(undefined), null);

const changed = fixture("asset-b");
for (let i = 0; i < 5; i++) await Promise.resolve();
assert.equal(changed.takeCachedTable(VANILLA), null, "resource hash did not isolate model tables");

records.set("fixture-profile|asset-c|mesh-table-v1", {key: "fixture-profile|asset-c|mesh-table-v1",
  schema: "mesh-table-v1", scope: VANILLA, bytes: table().buffer.slice(0, 80)});
const corrupted = fixture("asset-c");
for (let i = 0; i < 5; i++) await Promise.resolve();
assert.equal(corrupted.takeCachedTable(VANILLA), null, "truncated model table was accepted");

records.set("fixture-profile|asset-d|mesh-table-v1", {key: "fixture-profile|asset-d|mesh-table-v1",
  schema: "mesh-table-v1", bytes: table().buffer.slice(0)});
const legacy = fixture("asset-d");
for (let i = 0; i < 5; i++) await Promise.resolve();
assert.equal(legacy.takeCachedTable(VANILLA), null, "a record without a pack scope was accepted");
console.log("mesh-table-cache-smoke: content- and pack-keyed model table survives reload byte-for-byte PASS");
