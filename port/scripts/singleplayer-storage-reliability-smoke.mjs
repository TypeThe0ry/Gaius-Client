#!/usr/bin/env node

import {CANONICAL_PROFILE, LEGACY_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java",
  import.meta.url,
), "utf8");

assert.match(source, /STORAGE_FLUSH_ACK_TIMEOUT_MILLIS = 5000L/);
assert.match(source, /INDEXED_DB_FALLBACK_REHYDRATION_BUDGET_BYTES = 64 \* 1024 \* 1024/);
assert.match(source, /awaitIndexedDbFallbackHydration\(\);\s*BrowserFilePersistence\.mount\(\);/s);
assert.match(source, /serverThreadExited = true;[\s\S]*beginIntegratedServerStorageFlush\(\);/);
assert.match(source, /integratedServerStorageFlushPhase\(\)[\s\S]*STORAGE_FLUSH_ACK_TIMEOUT_MILLIS/);
assert.match(source, /completeIndexedDbFallbackHydration/);
assert.match(source, /__gaiusPersistentFiles = fallbackFiles/);
assert.match(source, /storage-flush-ack/);

const storageProfiles = Object.freeze([
  Object.freeze({
    profileId: CANONICAL_PROFILE.id,
    worldVersion: 4903,
    storageSchema: 2,
    storageDatabaseName: "gaius-fs-v2-" + CANONICAL_PROFILE.id + "",
    storagePrefix: "gaius.fs.v2:" + CANONICAL_PROFILE.id + ":",
    storageOpfsDirectory: "regions-v2-" + CANONICAL_PROFILE.id + "",
  }),
  Object.freeze({
    profileId: LEGACY_PROFILE.id,
    worldVersion: 4671,
    storageSchema: 2,
    storageDatabaseName: "gaius-fs-v2-" + LEGACY_PROFILE.id + "",
    storagePrefix: "gaius.fs.v2:" + LEGACY_PROFILE.id + ":",
    storageOpfsDirectory: "regions-v2-" + LEGACY_PROFILE.id + "",
  }),
]);

function extractJsBody(methodMarker) {
  const methodEnd = source.indexOf(methodMarker);
  assert.ok(methodEnd >= 0, "missing method marker: " + methodMarker);
  const annotationStart = source.lastIndexOf("@JSBody", methodEnd);
  const scriptStart = source.indexOf('"""', annotationStart) + 3;
  const scriptEnd = source.lastIndexOf('""")', methodEnd);
  assert.ok(annotationStart >= 0 && scriptStart > annotationStart && scriptEnd > scriptStart,
    "could not extract JSBody for " + methodMarker);
  return source.slice(scriptStart, scriptEnd);
}

function makeIndexedDb(records) {
  const database = {
    closed: false,
    close() { this.closed = true; },
    transaction() {
      const transaction = {};
      const store = {
        openCursor() {
          const request = {};
          const entries = Array.from(records.values());
          let index = 0;
          const advance = () => queueMicrotask(() => {
            if (index >= entries.length) {
              request.result = null;
            } else {
              const entry = entries[index++];
              request.result = {
                key: entry.path,
                value: entry,
                continue: advance,
              };
            }
            request.onsuccess?.();
          });
          advance();
          return request;
        },
      };
      transaction.objectStore = () => store;
      return transaction;
    },
  };
  return {
    open() {
      const request = {};
      queueMicrotask(() => {
        request.result = database;
        request.onsuccess?.();
      });
      return request;
    },
  };
}

function makeFiles(cached) {
  const target = Object.create(null);
  for (const [path, value] of cached) target[path] = value;
  return new Proxy(target, {
    ownKeys(value) {
      return [
        ...Reflect.ownKeys(value),
        "/gaius/saves/fallback-world/region/evicted.0.0.mca",
      ];
    },
    getOwnPropertyDescriptor(value, property) {
      return Reflect.getOwnPropertyDescriptor(value, property) ||
        (property === "/gaius/saves/fallback-world/region/evicted.0.0.mca"
          ? {configurable: true, enumerable: true}
          : undefined);
    },
  });
}

function createHydrationContext(records, cached, storageProfile) {
  const events = [];
  const files = makeFiles(cached);
  const context = {
    ArrayBuffer,
    Date,
    Error,
    Map,
    Math,
    Number,
    Object,
    Promise,
    Proxy,
    Reflect,
    String,
    Uint8Array,
    IDBKeyRange: {bound() { return {}; }},
    indexedDB: makeIndexedDb(records),
    __gaiusFsBackend: "indexeddb-worker-lru",
    __gaiusPersistentFiles: files,
    __gaiusServerWorldId: "fallback-world",
    __gaiusProfileId: storageProfile.profileId,
    __gaiusWorldVersion: storageProfile.worldVersion,
    __gaiusStorageSchema: storageProfile.storageSchema,
    __gaiusStorageDatabaseName: storageProfile.storageDatabaseName,
    __gaiusStoragePrefix: storageProfile.storagePrefix,
    __gaiusStorageOpfsDirectory: storageProfile.storageOpfsDirectory,
    __gaiusFsPutBytes(path, value) {
      return value instanceof Uint8Array && String(path).endsWith(".mca");
    },
    __gaiusFsDelete() {
      return true;
    },
    postMessage(message) { events.push(message); },
    queueMicrotask,
    setTimeout,
  };
  context.globalThis = context;
  context.completeIndexedDbFallbackHydration = (success, detail) => {
    events.push({type: "hydration-result", success, detail});
  };
  return {context, events};
}

const hydrationScript = extractJsBody(
  "private static native boolean beginIndexedDbFallbackHydration(",
);
assert.doesNotMatch(hydrationScript, /\blet\s+[A-Za-z_$]/,
  "TeaVM's JSBody parser does not accept let declarations");
assert.doesNotMatch(hydrationScript, /for\s*\(\s*const\s+\w+\s+of\s+/,
  "TeaVM's JSBody parser does not accept for-of loops");
assert.doesNotMatch(hydrationScript,
  /(?:^|[,\{])\s*(?:get|has|ownKeys|getOwnPropertyDescriptor)\s*\([^:]*\)\s*\{/m,
  "TeaVM's JSBody parser does not accept object method shorthand");
assert.doesNotMatch(hydrationScript, /\{\s*path\s*,\s*value\s*\}/,
  "TeaVM's JSBody parser does not accept property shorthand");
assert.doesNotMatch(hydrationScript, /,\s*detail\s*\}/,
  "TeaVM's JSBody parser does not accept property shorthand");

const cachedPath = "/gaius/saves/fallback-world/region/cached.0.0.mca";
const evictedPath = "/gaius/saves/fallback-world/region/evicted.0.0.mca";
const records = new Map([
  [cachedPath, {path: cachedPath, value: new Uint8Array([1, 2, 3])}],
  [evictedPath, {path: evictedPath, value: new Uint8Array([4, 5, 6, 7])}],
]);
for (const storageProfile of storageProfiles) {
  const runtime = createHydrationContext(records, new Map([
    [cachedPath, new Uint8Array([1, 2, 3])],
  ]), storageProfile);
  vm.runInNewContext(
    "globalThis.beginHydration = function(maxBytes, maxEntries, onDemandReads) {" +
      hydrationScript + "\n};",
    runtime.context,
    {filename: `BrowserIntegratedServerMain.java:hydration:${storageProfile.profileId}`},
  );
  assert.equal(runtime.context.beginHydration(1024 * 1024, 16, true), true);
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(runtime.events.find((event) => event.type === "hydration-result")?.success, true);
  assert.equal(runtime.context.__gaiusPersistentFiles[evictedPath], undefined,
    "JSPI startup must leave evicted regions for on-demand reads");
  delete runtime.context.__gaiusPersistentFiles[cachedPath];
  assert.equal(runtime.context.__gaiusPersistentFiles[cachedPath], undefined,
    "a cached region must not be copied into the hydration mirror");
  const changedPath = "/gaius/saves/fallback-world/region/changed.0.0.mca";
  const changed = new Uint8Array([8, 9]);
  assert.equal(runtime.context.__gaiusFsPutBytes(changedPath, changed), true);
  assert.deepEqual([...runtime.context.__gaiusPersistentFiles[changedPath]], [8, 9]);
  assert.equal(runtime.context.__gaiusFsDelete(changedPath), true);
  assert.equal(runtime.context.__gaiusPersistentFiles[changedPath], undefined);

  const synchronousRuntime = createHydrationContext(records, new Map([
    [cachedPath, new Uint8Array([1, 2, 3])],
  ]), storageProfile);
  vm.runInNewContext(
    "globalThis.beginHydration = function(maxBytes, maxEntries, onDemandReads) {" +
      hydrationScript + "\n};",
    synchronousRuntime.context,
    {filename: `BrowserIntegratedServerMain.java:hydration-sync:${storageProfile.profileId}`},
  );
  assert.equal(synchronousRuntime.context.beginHydration(1024 * 1024, 16, false), true);
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(synchronousRuntime.events.find((event) => event.type === "hydration-result")?.success, true);
  assert.deepEqual([...synchronousRuntime.context.__gaiusPersistentFiles[evictedPath]], [4, 5, 6, 7],
    "synchronous JavaScript startup must restore evicted region bytes before vanilla Main");

  const overBudget = new Map([
    [evictedPath, {path: evictedPath, value: new Uint8Array(32)}],
  ]);
  const rejected = createHydrationContext(overBudget, new Map(), storageProfile);
  vm.runInNewContext(
    "globalThis.beginHydration = function(maxBytes, maxEntries, onDemandReads) {" +
      hydrationScript + "\n};",
    rejected.context,
    {filename: `BrowserIntegratedServerMain.java:hydration-budget:${storageProfile.profileId}`},
  );
  assert.equal(rejected.context.beginHydration(8, 16, false), true);
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(rejected.events.find((event) => event.type === "hydration-result")?.success, false,
    "synchronous JavaScript startup must reject an evicted world above its bridge budget");
}

// A file-origin world can legitimately exceed the old 64 MiB bridge bound while every region is
// already present in the Worker's bounded synchronous cache. Rehydration must skip those cached
// values and leave durable records readable instead of copying the whole world a second time.
const cachedOnlyRecords = new Map();
const cachedOnly = new Map();
const cachedOnlyRegionBytes = 1024 * 1024;
const cachedOnlyRegions = 65;
for (let index = 0; index < cachedOnlyRegions; index++) {
  const path = `/gaius/saves/fallback-world/region/r.${index}.0.mca`;
  const value = new Uint8Array(cachedOnlyRegionBytes);
  value[0] = index & 0xff;
  value[value.length - 1] = (index >>> 8) & 0xff;
  cachedOnlyRecords.set(path, {path, value});
  cachedOnly.set(path, value);
}
const cachedOnlyRuntime = createHydrationContext(
  cachedOnlyRecords,
  cachedOnly,
  storageProfiles[0],
);
vm.runInNewContext(
  "globalThis.beginHydration = function(maxBytes, maxEntries, onDemandReads) {" +
    hydrationScript + "\n};",
  cachedOnlyRuntime.context,
  {filename: "BrowserIntegratedServerMain.java:hydration-cached-over-budget"},
);
assert.equal(cachedOnlyRuntime.context.beginHydration(64 * 1024 * 1024, 4096), true);
await new Promise((resolve) => setTimeout(resolve, 20));
const cachedOnlyResult = cachedOnlyRuntime.events.find((event) => event.type === "hydration-result");
assert.equal(cachedOnlyResult?.success, true,
  "a cached world over 64 MiB must not fail bridge rehydration");
assert.equal(cachedOnlyResult?.detail, "0 regions",
  "cached regions must not be copied into the bridge mirror");
for (let index = 0; index < cachedOnlyRegions; index++) {
  const path = `/gaius/saves/fallback-world/region/r.${index}.0.mca`;
  const value = cachedOnlyRuntime.context.__gaiusPersistentFiles[path];
  assert.equal(value?.byteLength, cachedOnlyRegionBytes, `cached region ${index} is unreadable`);
  assert.equal(value[0], index & 0xff);
  assert.equal(value[value.length - 1], (index >>> 8) & 0xff);
}

// JSPI keeps evicted records in IndexedDB for per-region reads. The synchronous JS runtime
// restores evicted records through its bounded startup bridge before vanilla Main opens files.
const mixedCachedRecords = new Map();
const mixedCached = new Map();
const mixedCachedRegions = 65;
for (let index = 0; index < mixedCachedRegions; index++) {
  const path = `/gaius/saves/fallback-world/region/m.${index}.0.mca`;
  const value = new Uint8Array(cachedOnlyRegionBytes);
  value[0] = (index + 17) & 0xff;
  value[value.length - 1] = (index >>> 8) & 0xff;
  mixedCachedRecords.set(path, {path, value});
  if (index < mixedCachedRegions - 1) mixedCached.set(path, value);
}
const mixedCachedRuntime = createHydrationContext(
  mixedCachedRecords,
  mixedCached,
  storageProfiles[0],
);
vm.runInNewContext(
  "globalThis.beginHydration = function(maxBytes, maxEntries, onDemandReads) {" +
    hydrationScript + "\n};",
  mixedCachedRuntime.context,
  {filename: "BrowserIntegratedServerMain.java:hydration-cached-evicted"},
);
assert.equal(mixedCachedRuntime.context.beginHydration(64 * 1024 * 1024, 4096), true);
await new Promise((resolve) => setTimeout(resolve, 20));
const mixedCachedResult = mixedCachedRuntime.events.find((event) => event.type === "hydration-result");
assert.equal(mixedCachedResult?.success, true,
  "an evicted region must still be restored when cached regions exceed 64 MiB");
assert.equal(mixedCachedResult?.detail, "1 regions",
  "synchronous JavaScript startup must restore the one evicted region");
const mixedEvictedPath = `/gaius/saves/fallback-world/region/m.${mixedCachedRegions - 1}.0.mca`;
const mixedEvictedValue = mixedCachedRuntime.context.__gaiusPersistentFiles[mixedEvictedPath];
assert.equal(mixedEvictedValue?.byteLength, cachedOnlyRegionBytes,
  "synchronous JavaScript startup did not materialize its evicted region");

const flushScript = extractJsBody(
  "private static native void beginIntegratedServerStorageFlush();",
);
let resolveFlush;
const flushEvents = [];
const flushContext = {
  Date,
  Promise,
  String,
  Number,
  globalThis: null,
  __gaiusServerWorldId: "flush-world",
  __gaiusFsFlush: () => new Promise((resolve) => { resolveFlush = resolve; }),
  postMessage(message) { flushEvents.push(message); },
};
flushContext.globalThis = flushContext;
vm.runInNewContext(
  "globalThis.beginFlush = function() {" + flushScript + "\n};",
  flushContext,
  {filename: "BrowserIntegratedServerMain.java:flush"},
);
flushContext.beginFlush();
assert.equal(flushContext.__gaiusIntegratedServerStorageFlush.phase, "pending");
resolveFlush();
await new Promise((resolve) => setTimeout(resolve, 0));
assert.equal(flushContext.__gaiusIntegratedServerStorageFlush.phase, "ack");
assert.equal(flushEvents.at(-1)?.type, "storage-flush-ack");

const expireScript = extractJsBody(
  "private static native void expireIntegratedServerStorageFlush();",
);
const timeoutEvents = [];
const timeoutContext = {
  globalThis: null,
  __gaiusIntegratedServerStorageFlush: {phase: "pending", startedAt: Date.now()},
  postMessage(message) { timeoutEvents.push(message); },
};
timeoutContext.globalThis = timeoutContext;
vm.runInNewContext(
  "globalThis.expireFlush = function() {" + expireScript + "\n};",
  timeoutContext,
  {filename: "BrowserIntegratedServerMain.java:flush-timeout"},
);
timeoutContext.expireFlush();
assert.equal(timeoutContext.__gaiusIntegratedServerStorageFlush.phase, "timeout");
assert.equal(timeoutEvents.at(-1)?.type, "storage-flush-timeout");

console.log(JSON.stringify({
  ok: true,
  boundedFallbackRehydration: true,
  evictedRegionReadable: true,
  overBudgetRejected: true,
  boundedFlushAck: true,
  boundedFlushTimeout: true,
}));
