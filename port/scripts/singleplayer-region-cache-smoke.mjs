import {CANONICAL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {deflateSync, inflateSync} from "node:zlib";
import vm from "node:vm";

// This harness does not select a profile. Keep it on the safe, current
// default storage namespace rather than reopening the removed v1 database.
const DEFAULT_STORAGE_CONFIG = Object.freeze({
  profileId: CANONICAL_PROFILE.id,
  worldVersion: 4903,
  storageSchema: 2,
  storageDatabaseName: "gaius-fs-v2-" + CANONICAL_PROFILE.id + "",
  storagePrefix: "gaius.fs.v2:" + CANONICAL_PROFILE.id + ":",
  storageOpfsDirectory: "regions-v2-" + CANONICAL_PROFILE.id + "",
});

const bootstrap = await readFile(
  process.env.GAIUS_BOOTSTRAP ||
    new URL("../web/singleplayer/server-worker-bootstrap.js", import.meta.url),
  "utf8",
);
const frozenBootstrap = process.env.GAIUS_FROZEN_BOOTSTRAP
  ? await readFile(process.env.GAIUS_FROZEN_BOOTSTRAP, "utf8")
  : null;

class MemoryFile {
  constructor() {
    this.bytes = new Uint8Array(0);
    this.size = 0;
    this.flushes = 0;
  }

  ensureCapacity(required) {
    if (required <= this.bytes.byteLength) return;
    let capacity = Math.max(1024, this.bytes.byteLength || 0);
    while (capacity < required) capacity *= 2;
    const replacement = new Uint8Array(capacity);
    replacement.set(this.bytes.subarray(0, this.size));
    this.bytes = replacement;
  }
}

class FakeSyncAccessHandle {
  constructor(file) {
    this.file = file;
    this.closed = false;
  }

  getSize() {
    return this.file.size;
  }

  read(output, options) {
    const at = Number(options?.at) || 0;
    const available = Math.max(0, Math.min(output.byteLength, this.file.size - at));
    for (let index = 0; index < available; index++) {
      output[index] = this.file.bytes[at + index];
    }
    return available;
  }

  write(input, options) {
    assert.equal(this.closed, false, "write used a closed OPFS handle");
    const at = Number(options?.at) || 0;
    this.file.ensureCapacity(at + input.byteLength);
    for (let index = 0; index < input.byteLength; index++) {
      this.file.bytes[at + index] = input[index];
    }
    this.file.size = Math.max(this.file.size, at + input.byteLength);
    return input.byteLength;
  }

  truncate(size) {
    this.file.size = Math.max(0, Number(size) || 0);
  }

  flush() {
    this.file.flushes++;
  }

  close() {
    this.closed = true;
  }
}

function createOpfs(files) {
  const directory = {
    async getDirectoryHandle() {
      return directory;
    },
    async getFileHandle(name) {
      if (!files.has(name)) files.set(name, new MemoryFile());
      const file = files.get(name);
      return {
        async createSyncAccessHandle() {
          if (files.hangingAttempts > 0) {
            files.hangingAttempts--;
            return new Promise(() => {});
          }
          if (files.exclusiveHandles && file.activeHandle) {
            files.lockConflicts = (files.lockConflicts || 0) + 1;
            const error = new Error("Previous session still owns the OPFS handle");
            error.name = "NoModificationAllowedError";
            throw error;
          }
          // files.lockedAttempts simulates a container still held by another worker.
          if (files.lockedAttempts > 0) {
            files.lockedAttempts--;
            const error = new Error("Access Handles cannot be created if there is another open Access Handle");
            error.name = "NoModificationAllowedError";
            throw error;
          }
          const handle = new FakeSyncAccessHandle(file);
          if (files.exclusiveHandles) {
            file.activeHandle = handle;
            const close = handle.close.bind(handle);
            handle.close = () => {
              close();
              files.closeRequested = true;
              // close() returns synchronously, but model the browser's delayed
              // release of the exclusive lock independently of Worker events.
              setTimeout(() => {
                if (file.activeHandle === handle) file.activeHandle = undefined;
                files.releaseTime = performance.now();
              }, files.releaseDelayMillis || 0);
            };
          }
          return handle;
        },
      };
    },
  };
  return directory;
}

function createIndexedDb(records) {
  const database = {
    objectStoreNames: {contains: () => true},
    close() {},
    transaction() {
      const transaction = {};
      const store = {
        openKeyCursor() {
          return cursorRequest(true);
        },
        openCursor() {
          return cursorRequest(false);
        },
        get(path) {
          const request = {};
          queueMicrotask(() => {
            request.result = records.get(path);
            request.onsuccess?.();
          });
          return request;
        },
        put(entry) {
          records.set(entry.path, entry);
        },
        delete(path) {
          records.delete(path);
        },
      };
      function cursorRequest(keyOnly) {
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
              primaryKey: entry.path,
              value: keyOnly ? undefined : entry,
              continue: advance,
            };
          }
          request.onsuccess?.();
        });
        advance();
        return request;
      }
      transaction.objectStore = () => store;
      setTimeout(() => transaction.oncomplete?.(), 0);
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

// Web Locks shared by every runtime of one "browser profile". A context's locks disappear when
// the context dies (kill), as the browser drops them for a closed tab or terminated Worker.
function createLockManager() {
  const held = new Map();
  const drop = (name, holder) => {
    const entry = held.get(name);
    if (!entry) return;
    entry.holders = entry.holders.filter((candidate) => candidate !== holder);
    if (entry.holders.length === 0) held.delete(name);
  };
  return {
    held,
    forContext(contextId) {
      return {
        async request(name, options, callback) {
          const exclusive = options?.mode !== "shared";
          const current = held.get(name);
          if (current && (exclusive || current.mode === "exclusive")) return callback(null);
          const entry = current || {mode: exclusive ? "exclusive" : "shared", holders: []};
          const holder = {contextId};
          entry.holders.push(holder);
          held.set(name, entry);
          try {
            return await callback({name, mode: entry.mode});
          } finally {
            drop(name, holder);
          }
        },
        async query() {
          return {
            held: Array.from(held.entries()).flatMap(([name, entry]) =>
              entry.holders.map((holder) => ({name, mode: entry.mode, clientId: holder.contextId}))),
            pending: [],
          };
        },
      };
    },
    kill(contextId) {
      for (const [name, entry] of Array.from(held.entries())) {
        for (const holder of entry.holders.filter((candidate) => candidate.contextId === contextId)) {
          drop(name, holder);
        }
      }
    },
    names() {
      return Array.from(held.keys());
    },
  };
}

function createRuntime({opfsFiles, idbRecords, budgetBytes = 64 * 1024, configureBudget = true,
  opfsLockWaitMillis, opfsLockProbeMillis, recoveryCrashStep, bootstrapSource = bootstrap,
  webLocks, extraGlobals = {}}) {
  const events = [];
  const context = {
    Array,
    ArrayBuffer,
    Blob,
    DataView,
    Date,
    Error,
    Map,
    Math,
    Number,
    Object,
    Promise,
    Proxy,
    Reflect,
    Response,
    String,
    TextDecoder,
    TextEncoder,
    Uint8Array,
    URL,
    atob,
    clearTimeout,
    console,
    indexedDB: createIndexedDb(idbRecords),
    location: new URL("https://client.example/singleplayer-server-worker.js"),
    performance,
    postMessage(message) {
      events.push(message);
    },
    queueMicrotask,
    setTimeout,
    ...(configureBudget ? {__gaiusRegionCacheBudgetBytes: budgetBytes} : {}),
    ...(opfsLockWaitMillis ? {__gaiusOpfsLockWaitMillis: opfsLockWaitMillis} : {}),
    ...(opfsLockProbeMillis ? {__gaiusOpfsLockProbeMillis: opfsLockProbeMillis} : {}),
    ...(recoveryCrashStep ? {__gaiusRecoveryCrashStep: recoveryCrashStep} : {}),
    __gaiusStartIntegratedServerPump() {},
    ...extraGlobals,
  };
  if (opfsFiles) {
    context.navigator = {
      storage: {
        async getDirectory() {
          return createOpfs(opfsFiles);
        },
      },
    };
  }
  if (webLocks) {
    context.navigator = {...(context.navigator || {}), locks: webLocks};
  }
  context.globalThis = context;
  context.close = () => events.push({type: "harness-close"});
  context.importScripts = () => {};
  context.main = () => events.push({type: "harness-main"});
  let runtimeStopped = false;
  context.stopIntegratedServer = () => { runtimeStopped = true; };
  context.isIntegratedServerStopped = () => runtimeStopped;
  vm.runInNewContext(bootstrapSource, context, {filename: "server-worker-bootstrap.js"});
  return {context, events};
}

function startRuntime(runtime, worldId = "region-cache-world", storageConfig = DEFAULT_STORAGE_CONFIG) {
  const port = {
    close() {},
    postMessage() {},
  };
  return runtime.context.onmessage({
    data: {
      type: "start",
      sessionId: "7123456789abcdef0123456789abcdef",
      launchGeneration: "1",
      worldId,
      profileId: storageConfig.profileId,
      worldVersion: storageConfig.worldVersion,
      storageSchema: storageConfig.storageSchema,
      storageDatabaseName: storageConfig.storageDatabaseName,
      storagePrefix: storageConfig.storagePrefix,
      storageOpfsDirectory: storageConfig.storageOpfsDirectory,
      renderDistance: 6,
      simulationDistance: 4,
      serverScriptUrl: "https://client.example/singleplayer-server.js",
      port,
    },
    ports: [port],
  });
}

async function waitForEvent(events, type, timeoutMillis = 1000) {
  const deadline = Date.now() + timeoutMillis;
  while (!events.some((event) => event?.type === type)) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${type}`);
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
}

async function runFrozenCompatibilityCases() {
  assert.ok(frozenBootstrap, "GAIUS_FROZEN_BOOTSTRAP must name the frozen Worker source");
  for (const writer of ["current", "frozen"]) {
    const files = new Map();
    const records = new Map();
    const world = "frozen-compat-" + writer;
    const a = `/gaius/saves/${world}/region/r.0.0.mca`;
    const b = `/gaius/saves/${world}/region/r.1.0.mca`;
    const writerSource = writer === "frozen" ? frozenBootstrap : bootstrap;
    const owner = createRuntime({opfsFiles: files, idbRecords: records, bootstrapSource: writerSource});
    await startRuntime(owner, world);
    const valueA1 = makeMca([{timestamp: 1, value: 0x31}]);
    const valueA2 = makeMca([{timestamp: 2, value: 0x32}]);
    const valueB = makeMca([{timestamp: 1, value: 0x33}]);
    assert.equal(owner.context.__gaiusFsPutBytes(a, valueA1), true);
    assert.equal(owner.context.__gaiusFsPutBytes(b, valueB), true);
    assert.equal(owner.context.__gaiusFsPutBytes(a, valueA2), true);
    await owner.context.__gaiusFsFlush();
    owner.context.onmessage({data: {type: "stop"}, ports: []});
    await waitForEvent(owner.events, "stopped");
    const readerSource = writer === "frozen" ? bootstrap : frozenBootstrap;
    const reader = createRuntime({opfsFiles: files, idbRecords: records, bootstrapSource: readerSource});
    await startRuntime(reader, world);
    await waitForEvent(reader.events, "runtime-ready", 5000);
    assert.deepEqual(parseMca(reader.context.__gaiusPersistentFiles[a]), parseMca(valueA2),
      `${writer}: A=1, B=1, A=2 journal was truncated or misread`);
    assert.deepEqual(parseMca(reader.context.__gaiusPersistentFiles[b]), parseMca(valueB),
      `${writer}: cross-path journal data was lost`);
    reader.context.onmessage({data: {type: "stop"}, ports: []});
    await waitForEvent(reader.events, "stopped");
    console.log(`ok frozen-compat-${writer}: cross-path log readable in both directions`);
  }
}

if (process.env.GAIUS_COMPAT_ONLY === "1") {
  await runFrozenCompatibilityCases();
  process.exit(0);
}

const opfsFiles = new Map();
const idbRecords = new Map();
const first = createRuntime({opfsFiles, idbRecords});
await startRuntime(first);
assert.equal(first.context.__gaiusFsBackend, "opfs-sync-worker");
assert.ok(
  first.events.findIndex((event) => event?.type === "harness-main") <
    first.events.findIndex((event) => event?.type === "runtime-ready"),
  "runtime-ready was emitted before main dispatch",
);

const fixtureCount = 512;
const fixtureBytes = 16 * 1024;
const externalFixtureCount = 256;
const externalFixtureBytes = 96 * 1024;
for (let index = 0; index < fixtureCount; index++) {
  const bytes = new Uint8Array(fixtureBytes);
  bytes[0] = index & 0xff;
  bytes[bytes.length - 1] = (index >>> 8) & 0xff;
  const path = `/gaius/saves/region-cache-world/region/r.${index}.0.mca`;
  assert.equal(first.context.__gaiusFsPutBytes(path, bytes), true);
}
for (let index = 0; index < externalFixtureCount; index++) {
  const bytes = new Uint8Array(externalFixtureBytes);
  bytes[0] = (index + 31) & 0xff;
  bytes[bytes.length - 1] = (index >>> 8) & 0xff;
  const path = `/gaius/saves/region-cache-world/region/c.${index}.0.mcc`;
  assert.equal(first.context.__gaiusFsPutBytes(path, bytes), true);
}
await first.context.__gaiusFsFlush();
assert.equal(
  Object.keys(first.context.__gaiusPersistentFiles)
    .filter((path) => path.endsWith(".mca") || path.endsWith(".mcc")).length,
  fixtureCount + externalFixtureCount,
  "OPFS chunk-storage index lost fixture entries",
);

for (let index = 0; index < fixtureCount; index++) {
  const path = `/gaius/saves/region-cache-world/region/r.${index}.0.mca`;
  const bytes = first.context.__gaiusPersistentFiles[path];
  assert.equal(bytes.byteLength, fixtureBytes);
  assert.equal(bytes[0], index & 0xff);
  assert.equal(bytes[bytes.length - 1], (index >>> 8) & 0xff);
}
for (let index = 0; index < externalFixtureCount; index++) {
  const path = `/gaius/saves/region-cache-world/region/c.${index}.0.mcc`;
  const bytes = first.context.__gaiusPersistentFiles[path];
  assert.equal(bytes.byteLength, externalFixtureBytes);
  assert.equal(bytes[0], (index + 31) & 0xff);
  assert.equal(bytes[bytes.length - 1], (index >>> 8) & 0xff);
}
const firstStats = first.context.__gaiusFsStorageSnapshot();
assert.ok(firstStats.cacheBytes <= firstStats.cacheBudgetBytes,
  "region cache exceeded its byte budget");
assert.ok(firstStats.cacheEntries <= 4, "region cache retained too many fixture entries");
assert.ok(firstStats.evictions >= fixtureCount - firstStats.cacheEntries,
  "large fixture did not exercise LRU eviction");
assert.equal(firstStats.regionEntries, fixtureCount + externalFixtureCount);

const temporaryPath =
  "/gaius/saves/region-cache-world/region/tmpabcdefghij.tmp";
const movedExternalPath =
  "/gaius/saves/region-cache-world/region/c.9000.0.mcc";
const movedBytes = new Uint8Array(128 * 1024);
movedBytes[0] = 0x4d;
movedBytes[movedBytes.length - 1] = 0x43;
assert.equal(first.context.__gaiusFsPutBytes(temporaryPath, movedBytes), true,
  "RegionFile temporary write was rejected");
await first.context.__gaiusFsFlush();
const temporaryBytes = first.context.__gaiusPersistentFiles[temporaryPath];
assert.equal(temporaryBytes.byteLength, movedBytes.byteLength,
  "closed RegionFile temporary write was not persisted as binary");
assert.equal(first.context.__gaiusFsPutBytes(movedExternalPath, temporaryBytes), true,
  "temporary payload could not be persisted under its final .mcc path");
assert.equal(first.context.__gaiusFsDelete(temporaryPath), true,
  "temporary path could not be removed after the durable .mcc write");
await first.context.__gaiusFsFlush();
assert.equal(temporaryPath in first.context.__gaiusPersistentFiles, false,
  "temporary path survived a completed .mcc move");

first.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(first.events, "stopped");
const container = Array.from(opfsFiles.values())[0];
assert.ok(container.flushes >= fixtureCount + externalFixtureCount + 1,
  "OPFS writes reported success before their sync handle was flushed");
container.ensureCapacity(container.size + 13);
container.bytes.fill(0xa5, container.size, container.size + 13);
container.size += 13;

const second = createRuntime({opfsFiles, idbRecords});
await startRuntime(second);
const restoredStats = second.context.__gaiusFsStorageSnapshot();
assert.equal(restoredStats.regionEntries, fixtureCount + externalFixtureCount + 1,
  "restart did not rebuild the complete OPFS region index");
assert.equal(restoredStats.cacheEntries, 0,
  "restart eagerly hydrated persisted OPFS regions");
assert.equal(restoredStats.cacheBytes, 0,
  "restart retained region payload bytes before first access");
assert.equal(restoredStats.cacheMisses, 0,
  "enumerating persisted region paths read their payloads");
assert.equal(restoredStats.recoveredTailBytes, 13,
  "restart did not discard an incomplete append-log tail");
for (let index = 0; index < externalFixtureCount; index++) {
  const path = `/gaius/saves/region-cache-world/region/c.${index}.0.mcc`;
  assert.equal(path in second.context.__gaiusPersistentFiles, true);
  assert.equal(Object.prototype.hasOwnProperty.call(
    second.context.__gaiusPersistentFiles, path), true);
}
const afterExistenceScan = second.context.__gaiusFsStorageSnapshot();
assert.equal(afterExistenceScan.cacheMisses, 0,
  "existence-only .mcc scans read OPFS payload bytes");
assert.equal(afterExistenceScan.cacheBytes, 0,
  "existence-only .mcc scans retained payload bytes");
for (const index of [0, 511, 1023]) {
  if (index >= fixtureCount) continue;
  const path = `/gaius/saves/region-cache-world/region/r.${index}.0.mca`;
  const bytes = second.context.__gaiusPersistentFiles[path];
  assert.equal(bytes[0], index & 0xff);
  assert.equal(bytes[bytes.length - 1], (index >>> 8) & 0xff);
}
for (const index of [0, 127, 255]) {
  const path = `/gaius/saves/region-cache-world/region/c.${index}.0.mcc`;
  const bytes = second.context.__gaiusPersistentFiles[path];
  assert.equal(bytes.byteLength, externalFixtureBytes);
  assert.equal(bytes[0], (index + 31) & 0xff);
}
const restoredMovedBytes = second.context.__gaiusPersistentFiles[movedExternalPath];
assert.equal(restoredMovedBytes.byteLength, movedBytes.byteLength,
  "restart lost the committed external chunk");
assert.equal(restoredMovedBytes[0], 0x4d);
assert.equal(restoredMovedBytes[restoredMovedBytes.length - 1], 0x43);
assert.equal(temporaryPath in second.context.__gaiusPersistentFiles, false,
  "restart resurrected the moved temporary file");
const afterOversizedReads = second.context.__gaiusFsStorageSnapshot();
assert.ok(afterOversizedReads.cacheBytes <= afterOversizedReads.cacheBudgetBytes,
  "oversized .mcc reads exceeded the materialization cache budget");
second.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(second.events, "stopped");

const fallbackRecords = new Map();
for (let index = 0; index < 4; index++) {
  const extension = index % 2 === 0 ? "mca" : "mcc";
  const prefix = extension === "mca" ? "r" : "c";
  const path = `/gaius/saves/fallback-world/region/${prefix}.${index}.0.${extension}`;
  fallbackRecords.set(path, {path, value: new Uint8Array(96 * 1024)});
}
const fallback = createRuntime({opfsFiles: null, idbRecords: fallbackRecords});
await startRuntime(fallback, "fallback-world");
await waitForEvent(fallback.events, "runtime-ready");
assert.equal(fallbackRecords.size, 4,
  "IndexedDB fallback changed durable regions while opening");
for (const path of fallbackRecords.keys()) {
  await new Promise((resolve, reject) => fallback.context.__gaiusFsRequestRegion(
    path, (success, detail) => success ? resolve() : reject(new Error(detail))));
  assert.ok(fallback.context.__gaiusPersistentFiles[path],
    `fallback did not publish on-demand region ${path}`);
}
fallback.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(fallback.events, "stopped");

// Without OPFS (every file:// portable page), the bounded IndexedDB LRU is the world's only
// synchronous region source. Save a world above the Java bridge's former 64 MiB duplicate-copy
// limit, close it, then reopen the persisted records and verify the original bytes.
const largeFallbackRecords = new Map();
const largeFallbackRegionBytes = 1024 * 1024;
const largeFallbackRegions = 400;
const largeFallbackWorld = "large-fallback-world";
for (let index = 0; index < largeFallbackRegions; index++) {
  const path = `/gaius/saves/${largeFallbackWorld}/region/r.${index}.0.mca`;
  const value = new Uint8Array(largeFallbackRegionBytes);
  value[0] = index & 0xff;
  value[value.length - 1] = (index >>> 8) & 0xff;
  largeFallbackRecords.set(path, {path, value});
}
assert.equal(largeFallbackRecords.size, largeFallbackRegions,
  "large fallback world did not persist each region record");
assert.equal(Array.from(largeFallbackRecords.values()).reduce(
  (total, entry) => total + entry.value.byteLength, 0),
largeFallbackRegions * largeFallbackRegionBytes,
"large fallback region records do not exceed the old 64 MiB gate");
const reopenedLargeFallback = createRuntime({
  opfsFiles: null,
  idbRecords: largeFallbackRecords,
  configureBudget: false,
});
await startRuntime(reopenedLargeFallback, largeFallbackWorld);
await waitForEvent(reopenedLargeFallback.events, "runtime-ready");
assert.equal(reopenedLargeFallback.events.some((event) => event?.type === "bootstrap-crash"), false,
  "an 80 MiB world did not reopen on the IndexedDB fallback");
const reopenedLargeFallbackStats = reopenedLargeFallback.context.__gaiusFsStorageSnapshot();
assert.ok(reopenedLargeFallbackStats.cacheBytes <= reopenedLargeFallbackStats.cacheBudgetBytes,
  "reopened fallback cache exceeded its bounded LRU budget");
for (let index = 0; index < largeFallbackRegions; index++) {
  const path = `/gaius/saves/${largeFallbackWorld}/region/r.${index}.0.mca`;
  await new Promise((resolve, reject) => reopenedLargeFallback.context.__gaiusFsRequestRegion(
    path, (success, detail) => success ? resolve() : reject(new Error(detail))));
  const bytes = reopenedLargeFallback.context.__gaiusPersistentFiles[path];
  assert.equal(bytes?.byteLength, largeFallbackRegionBytes, `fallback region ${index} is not readable`);
  assert.equal(bytes[0], index & 0xff);
  assert.equal(bytes[bytes.length - 1], (index >>> 8) & 0xff);
  reopenedLargeFallback.context.__gaiusFsReleaseRegion(path);
}
const largeFallbackStats = reopenedLargeFallback.context.__gaiusFsStorageSnapshot();
assert.equal(reopenedLargeFallback.context.__gaiusIndexedDbHydratedRegions.size, 0,
  "reopening retained bridge payloads after region reads completed");
reopenedLargeFallback.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(reopenedLargeFallback.events, "stopped");

// A world whose OPFS container is still held by another worker (a previous session that is
// still saving, or another tab) must wait for the handle and then fail closed: an IndexedDB
// session cannot see the OPFS regions, would regenerate those chunks, and the next OPFS session
// would migrate the regenerated regions over the saved ones.
const lockedFiles = new Map();
const lockedRecords = new Map();
const lockedPath = "/gaius/saves/locked-world/region/r.0.0.mca";
const lockedOwner = createRuntime({opfsFiles: lockedFiles, idbRecords: lockedRecords});
await startRuntime(lockedOwner, "locked-world");
assert.equal(lockedOwner.context.__gaiusFsBackend, "opfs-sync-worker");
const lockedOriginal = makeMca([{timestamp: 1, value: 0x11}]);
assert.equal(lockedOwner.context.__gaiusFsPutBytes(lockedPath, lockedOriginal), true);
await lockedOwner.context.__gaiusFsFlush();
lockedOwner.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(lockedOwner.events, "stopped");
assert.ok(
  lockedOwner.events.findIndex((event) => event?.type === "storage-closed") <
    lockedOwner.events.findIndex((event) => event?.type === "stopped"),
  "storage-closed handoff barrier was not published before stopped",
);

// Once a world has used OPFS, a temporary OPFS outage must refuse the world rather than create
// a writable IndexedDB fork that can later overwrite or be discarded beside the saved regions.
const unavailable = createRuntime({opfsFiles: null, idbRecords: lockedRecords});
await startRuntime(unavailable, "locked-world");
await waitForEvent(unavailable.events, "bootstrap-crash");
assert.match(
  String(unavailable.events.find((event) => event?.type === "bootstrap-crash")?.detail || ""),
  /OPFS is temporarily unavailable/,
  "an OPFS world opened on the fallback during a temporary outage",
);
assert.equal(unavailable.events.some((event) => event?.type === "runtime-ready"), false);
assert.equal(lockedRecords.size, 1, "the refused fallback session changed durable records");

lockedFiles.lockedAttempts = 3;
const handoff = createRuntime({opfsFiles: lockedFiles, idbRecords: lockedRecords, opfsLockWaitMillis: 2000});
await startRuntime(handoff, "locked-world");
await waitForEvent(handoff.events, "runtime-ready", 3000);
assert.equal(handoff.context.__gaiusFsBackend, "opfs-sync-worker",
  "a briefly locked OPFS container did not open once the previous handle was released");
assert.deepEqual(parseMca(handoff.context.__gaiusPersistentFiles[lockedPath]), parseMca(lockedOriginal));
handoff.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(handoff.events, "stopped");

// Save and Quit may return while the browser still retains the old handle.
// Tie lock contention to that actual handle's delayed release, rather than a
// predetermined count of failed acquisition attempts.
const reentryFiles = new Map();
reentryFiles.exclusiveHandles = true;
reentryFiles.releaseDelayMillis = 450;
const reentryRecords = new Map();
const reentryOwner = createRuntime({opfsFiles: reentryFiles, idbRecords: reentryRecords});
await startRuntime(reentryOwner, "locked-world");
assert.equal(reentryOwner.context.__gaiusFsPutBytes(lockedPath, lockedOriginal), true);
await reentryOwner.context.__gaiusFsFlush();
reentryOwner.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(reentryOwner.events, "storage-closed");
const closePublishedAt = performance.now();
assert.equal(reentryFiles.closeRequested, true, "storage-closed preceded handle.close()");
assert.ok(Array.from(reentryFiles.values()).some((file) => file.activeHandle),
  "the fixture released its old handle before successor startup");
assert.equal(reentryOwner.events.some((event) => event?.type === "stopped"), false,
  "Worker published stopped without allowing the handle release to settle");
const reentry = createRuntime({opfsFiles: reentryFiles, idbRecords: reentryRecords,
  opfsLockWaitMillis: 2000});
const reentryStartup = startRuntime(reentry, "locked-world");
await new Promise((resolve) => setTimeout(resolve, 60));
assert.ok(reentryFiles.lockConflicts > 0, "successor never encountered the old exclusive handle");
assert.equal(reentry.events.some((event) => event?.type === "runtime-ready"), false,
  "successor started while the old exclusive handle remained held");
assert.equal(reentry.events.some((event) => event?.type === "bootstrap-crash"), false,
  "successor refused fallback instead of waiting for the old handle");
await waitForEvent(reentryOwner.events, "stopped");
assert.ok(performance.now() - closePublishedAt >= 230,
  "Worker did not retain the storage-close settle window before stopped");
await reentryStartup;
await waitForEvent(reentry.events, "runtime-ready", 3000);
assert.ok(reentryFiles.releaseTime, "successor opened before the old handle was released");
assert.equal(reentry.context.__gaiusFsBackend, "opfs-sync-worker",
  "re-entry used the refused IndexedDB fallback instead of reopening OPFS");
assert.equal(reentry.events.some((event) => event?.type === "bootstrap-crash"), false);
assert.deepEqual(parseMca(reentry.context.__gaiusPersistentFiles[lockedPath]), parseMca(lockedOriginal),
  "re-entry did not preserve the old session's durable region data");
reentryFiles.releaseDelayMillis = 0;
reentry.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(reentry.events, "stopped");

lockedFiles.lockedAttempts = Infinity;
const contended = createRuntime({opfsFiles: lockedFiles, idbRecords: lockedRecords, opfsLockWaitMillis: 300});
await startRuntime(contended, "locked-world");
await waitForEvent(contended.events, "bootstrap-crash", 3000);
assert.match(
  String(contended.events.find((event) => event?.type === "bootstrap-crash")?.detail || ""),
  /OPFS container locked/,
  "a locked OPFS container did not report why the world could not open",
);
assert.equal(contended.events.some((event) => event?.type === "storage-opfs-fallback"), false,
  "a locked OPFS container fell back to IndexedDB without its saved regions");
assert.equal(contended.events.some((event) => event?.type === "runtime-ready"), false,
  "a locked OPFS container started the world");
assert.equal(lockedRecords.size, 1, "a refused session wrote IndexedDB records");

// A browser can leave createSyncAccessHandle() pending while the old document's renderer is
// tearing down. The startup gate must turn that into the same bounded lock failure instead of
// parking the Worker until the 180 second client readiness watchdog expires.
const hungFiles = new Map();
hungFiles.hangingAttempts = Infinity;
const hungProbe = createRuntime({
  opfsFiles: hungFiles,
  idbRecords: new Map(),
  opfsLockWaitMillis: 80,
  opfsLockProbeMillis: 20,
});
await startRuntime(hungProbe, "hung-opfs-world");
await waitForEvent(hungProbe.events, "bootstrap-crash", 1000);
assert.match(
  String(hungProbe.events.find((event) => event?.type === "bootstrap-crash")?.detail || ""),
  /OPFS container locked/,
  "a hung OPFS access-handle probe did not fail closed",
);
assert.equal(hungProbe.events.some((event) => event?.type === "runtime-ready"), false,
  "a hung OPFS access-handle probe started the world");
lockedFiles.lockedAttempts = 0;
const reopened = createRuntime({opfsFiles: lockedFiles, idbRecords: lockedRecords});
await startRuntime(reopened, "locked-world");
assert.deepEqual(parseMca(reopened.context.__gaiusPersistentFiles[lockedPath]), parseMca(lockedOriginal),
  "the saved region did not survive a refused contended open");
reopened.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(reopened.events, "stopped");

// Legacy OPFS worlds had no marker. Simulate an outage, write a newer fallback copy, then
// restore OPFS: startup must retain the OPFS copy and open with the fallback bytes.
const legacyFiles = new Map();
const legacyRecords = new Map();
const legacyPath = "/gaius/saves/legacy-world/region/r.0.0.mca";
const legacyOwner = createRuntime({opfsFiles: legacyFiles, idbRecords: legacyRecords});
await startRuntime(legacyOwner, "legacy-world");
assert.equal(legacyOwner.context.__gaiusFsPutBytes(legacyPath,
  makeMca([{timestamp: 1, value: 0x21}])), true);
await legacyOwner.context.__gaiusFsFlush();
legacyOwner.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(legacyOwner.events, "stopped");
for (const key of Array.from(legacyRecords.keys())) {
  if (key.includes("/gaius/storage/opfs-worlds/")) legacyRecords.delete(key);
}
const legacyFallback = createRuntime({opfsFiles: null, idbRecords: legacyRecords});
await startRuntime(legacyFallback, "legacy-world");
assert.equal(legacyFallback.context.__gaiusFsBackend, "indexeddb-worker-lru");
const legacyNew = makeMca([{timestamp: 2, value: 0x22}]);
assert.equal(legacyFallback.context.__gaiusFsPutBytes(legacyPath, legacyNew), true);
await legacyFallback.context.__gaiusFsFlush();
legacyFallback.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(legacyFallback.events, "stopped");
const legacyRecovered = createRuntime({opfsFiles: legacyFiles, idbRecords: legacyRecords});
await startRuntime(legacyRecovered, "legacy-world");
await waitForEvent(legacyRecovered.events, "runtime-ready");
assert.deepEqual(parseMca(legacyRecovered.context.__gaiusPersistentFiles[legacyPath]), parseMca(legacyNew),
  "legacy OPFS recovery did not select the newer fallback region");
assert.ok(Array.from(legacyRecords.keys()).some((path) => path.includes("/.gaius-backups/")),
  "legacy OPFS recovery did not retain a backup");
legacyRecovered.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(legacyRecovered.events, "stopped");

function makeMca(chunks) {
  const header = new Uint8Array(8192);
  const view = new DataView(header.buffer);
  const records = [];
  let sectors = 2;
  for (let index = 0; index < chunks.length; index++) {
    const compressed = deflateSync(Buffer.from([0x0a, 0x00, chunks[index].value & 0xff, 0x00]));
    const record = new Uint8Array(5 + compressed.length);
    new DataView(record.buffer).setUint32(0, 1 + compressed.length, false);
    record[4] = 2;
    record.set(compressed, 5);
    const count = Math.ceil(record.byteLength / 4096);
    view.setUint32(index * 4, (sectors << 8) | count, false);
    view.setUint32(4096 + index * 4, chunks[index].timestamp, false);
    records.push({sector: sectors, bytes: record});
    sectors += count;
  }
  const result = new Uint8Array(sectors * 4096);
  result.set(header);
  for (const record of records) result.set(record.bytes, record.sector * 4096);
  return result;
}

function parseMca(bytes) {
  assert.equal(bytes.byteLength % 4096, 0, "MCA length is sector aligned");
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const chunks = [];
  for (let index = 0; index < 1024; index++) {
    const location = view.getUint32(index * 4, false);
    if (location === 0) continue;
    const sector = location >>> 8;
    const count = location & 0xff;
    assert.ok(sector >= 2 && sector + count <= bytes.byteLength / 4096);
    const offset = sector * 4096;
    const length = view.getUint32(offset, false);
    const compression = view.getUint8(offset + 4);
    assert.ok(length >= 1 && length <= count * 4096 - 4);
    if ((compression & 0x80) !== 0) {
      assert.equal(length, 1);
      chunks.push(Buffer.alloc(0));
    } else {
      assert.equal(compression, 2);
      chunks.push(inflateSync(Buffer.from(bytes.subarray(offset + 5, offset + 4 + length))));
    }
  }
  return chunks;
}
const mergeFiles = new Map();
const mergeRecords = new Map();
const mergePath = "/gaius/saves/merge-world/region/r.0.0.mca";
const mergeOwner = createRuntime({opfsFiles: mergeFiles, idbRecords: new Map()});
await startRuntime(mergeOwner, "merge-world");
assert.equal(mergeOwner.context.__gaiusFsPutBytes(mergePath, makeMca([
  {timestamp: 10, value: 0xa1}, {timestamp: 1, value: 0xa2},
])), true);
await mergeOwner.context.__gaiusFsFlush();
mergeOwner.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(mergeOwner.events, "stopped");
const mergeIdb = makeMca([
  {timestamp: 1, value: 0xb1}, {timestamp: 20, value: 0xb2},
]);
mergeRecords.set(mergePath, {path: mergePath, value: mergeIdb, generation: 2});
const mergeRuntime = createRuntime({opfsFiles: mergeFiles, idbRecords: mergeRecords});
await startRuntime(mergeRuntime, "merge-world");
await waitForEvent(mergeRuntime.events, "runtime-ready");
const mergedRegion = mergeRuntime.context.__gaiusPersistentFiles[mergePath];
assert.deepEqual(parseMca(mergedRegion), parseMca(mergeIdb),
  "recovery did not take the complete IndexedDB region");
mergeRuntime.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(mergeRuntime.events, "stopped");

// Conflicting durable copies are ordered by the shared persisted commit generation. Every
// losing copy must be retained under the bounded recovery backup namespace before dedupe.
async function assertGenerationConflict(label, {opfsWrites, idbGeneration, expected}) {
  const files = new Map();
  const records = new Map();
  const world = "generation-conflict-" + label;
  const path = `/gaius/saves/${world}/region/r.0.0.mca`;
  const opfsValues = Array.from({length: opfsWrites}, (_, index) =>
    makeMca([{timestamp: index + 1, value: 0x31 + index}]));
  const idbValue = makeMca([{timestamp: 20, value: 0x71}]);
  const owner = createRuntime({opfsFiles: files, idbRecords: records});
  await startRuntime(owner, world);
  for (const value of opfsValues) assert.equal(owner.context.__gaiusFsPutBytes(path, value), true);
  await owner.context.__gaiusFsFlush();
  owner.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(owner.events, "stopped");
  const container = [...files.values()].find((file) => file.size > 0);
  assert.ok(container, `${label}: OPFS region journal was not written`);
  let opfsGeneration = opfsWrites;
  if (label === "missing-opfs-generation") {
    // Rewrite the committed record as the supported legacy V1 representation (generation 0).
    const pathBytes = new TextEncoder().encode(path);
    const headerBytes = 24;
    const value = opfsValues.at(-1);
    const legacy = new Uint8Array(headerBytes + pathBytes.byteLength + value.byteLength);
    const view = new DataView(legacy.buffer);
    view.setUint32(0, 0x47525331, true);
    view.setUint32(4, 1, true);
    view.setUint32(8, 1, true);
    view.setUint32(12, pathBytes.byteLength, true);
    view.setUint32(16, value.byteLength, true);
    view.setUint32(20, legacy.byteLength, true);
    legacy.set(pathBytes, headerBytes);
    legacy.set(value, headerBytes + pathBytes.byteLength);
    container.bytes = legacy;
    container.size = legacy.byteLength;
    opfsGeneration = 0;
  }
  records.set(path, {
    path, value: idbValue,
    ...(idbGeneration == null ? {} : {generation: idbGeneration}),
    committedAt: 1,
  });
  const recovered = createRuntime({opfsFiles: files, idbRecords: records});
  await startRuntime(recovered, world);
  await waitForEvent(recovered.events, "runtime-ready");
  const actual = recovered.context.__gaiusPersistentFiles[path];
  assert.deepEqual(parseMca(actual), parseMca(expected === "opfs" ? opfsValues.at(-1) : idbValue),
    `${label}: chose the wrong generation winner (OPFS generation ${opfsGeneration})`);
  const backup = [...records.entries()].find(([backupPath, entry]) =>
    backupPath.includes("/.gaius-backups/") && entry.originalPath === path);
  assert.ok(backup, `${label}: losing copy was not retained before IndexedDB dedupe`);
  assert.equal(records.has(path), false, `${label}: original IndexedDB record was not deduped`);
  recovered.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(recovered.events, "stopped");
  console.log(`ok ${label}: ${expected} wins and losing copy is backed up`);
}

await assertGenerationConflict("opfs-newer", {opfsWrites: 2, idbGeneration: 1, expected: "opfs"});
await assertGenerationConflict("idb-newer", {opfsWrites: 1, idbGeneration: 2, expected: "indexeddb"});
await assertGenerationConflict("equal-generation", {opfsWrites: 1, idbGeneration: 1, expected: "indexeddb"});
await assertGenerationConflict("missing-idb-generation", {opfsWrites: 1, idbGeneration: null, expected: "opfs"});
await assertGenerationConflict("missing-opfs-generation", {opfsWrites: 1, idbGeneration: 5, expected: "indexeddb"});

// A region pair is one recovery unit. Deliberately make per-file generation order disagree
// and verify both directions still select all files from one source.
async function assertPairGenerationConflict(label, opfsCounts, idbGenerations, expected) {
  const files = new Map();
  const records = new Map();
  const world = "pair-generation-" + label;
  const mca = `/gaius/saves/${world}/region/r.0.0.mca`;
  const mcc = `/gaius/saves/${world}/region/c.0.0.mcc`;
  const opfsMca = makeMca([{timestamp: opfsCounts.mca, value: 0x41 + opfsCounts.mca}]);
  const opfsMcc = makeMcc(0x51 + opfsCounts.mcc);
  const owner = createRuntime({opfsFiles: files, idbRecords: records});
  await startRuntime(owner, world);
  for (let index = 0; index < opfsCounts.mca; index++) {
    assert.equal(owner.context.__gaiusFsPutBytes(mca,
      makeMca([{timestamp: index + 1, value: 0x41 + index}])), true);
  }
  for (let index = 0; index < opfsCounts.mcc; index++) {
    assert.equal(owner.context.__gaiusFsPutBytes(mcc, makeMcc(0x51 + index)), true);
  }
  await owner.context.__gaiusFsFlush();
  owner.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(owner.events, "stopped");
  const idbMca = makeMca([{timestamp: 30, value: 0x61}]);
  const idbMcc = makeMcc(0x71);
  records.set(mca, {path: mca, value: idbMca, generation: idbGenerations.mca});
  records.set(mcc, {path: mcc, value: idbMcc, generation: idbGenerations.mcc});
  const recovered = createRuntime({opfsFiles: files, idbRecords: records});
  await startRuntime(recovered, world);
  await waitForEvent(recovered.events, "runtime-ready");
  const expectedMca = opfsCounts.mca > idbGenerations.mca
    ? makeMca([{timestamp: opfsCounts.mca, value: 0x40 + opfsCounts.mca}]) : idbMca;
  const expectedMcc = expected === "opfs"
    ? (opfsCounts.mcc > idbGenerations.mcc ? makeMcc(0x50 + opfsCounts.mcc) : idbMcc)
    : (idbGenerations.mcc > opfsCounts.mcc ? idbMcc : makeMcc(0x50 + opfsCounts.mcc));
  assert.deepEqual(parseMca(recovered.context.__gaiusPersistentFiles[mca]), parseMca(expectedMca),
    `${label}: MCA came from the wrong pair source`);
  assert.deepEqual(Array.from(recovered.context.__gaiusPersistentFiles[mcc]), Array.from(expectedMcc),
    `${label}: MCC came from the wrong pair source`);
  const backups = [...records.values()].filter((entry) => entry.originalPath === mca || entry.originalPath === mcc);
  assert.equal(backups.length, 2, `${label}: both losing pair members were not backed up`);
  recovered.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(recovered.events, "stopped");
  console.log(`ok ${label}: entire region pair recovered from ${expected}`);
}

await assertPairGenerationConflict("opfs-pair-wins", {mca: 5, mcc: 2},
  {mca: 4, mcc: 3}, "opfs");
await assertPairGenerationConflict("idb-pair-wins", {mca: 4, mcc: 1},
  {mca: 3, mcc: 5}, "indexeddb");

// Pair-level ordering must dominate deliberately opposite per-path counters.
async function assertPairCommitSequenceCase(label, idbPairSequence, expected) {
  const files = new Map();
  const records = new Map();
  const world = "pair-sequence-" + label;
  const mca = `/gaius/saves/${world}/region/r.0.0.mca`;
  const mcc = `/gaius/saves/${world}/region/c.0.0.mcc`;
  const owner = createRuntime({opfsFiles: files, idbRecords: records});
  await startRuntime(owner, world);
  for (let index = 0; index < 10; index++) {
    assert.equal(owner.context.__gaiusFsPutBytes(mca,
      makeMca([{timestamp: index + 1, value: 0x41 + index}])), true);
  }
  assert.equal(owner.context.__gaiusFsPutBytes(mcc, makeMcc(0x52)), true);
  await owner.context.__gaiusFsFlush();
  owner.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(owner.events, "stopped");
  const idbMca = makeMca([{timestamp: 99, value: 0x71}]);
  const idbMcc = makeMcc(0x72);
  records.set(mca, {path: mca, value: idbMca, generation: 2,
    ...(idbPairSequence == null ? {} : {pairCommitSequence: idbPairSequence})});
  records.set(mcc, {path: mcc, value: idbMcc, generation: 100,
    ...(label === "missing-one-side" ? {} :
      (idbPairSequence == null ? {} : {pairCommitSequence: idbPairSequence}))});
  const recovered = createRuntime({opfsFiles: files, idbRecords: records});
  await startRuntime(recovered, world);
  await waitForEvent(recovered.events, "runtime-ready");
  const actualMca = recovered.context.__gaiusPersistentFiles[mca];
  const actualMcc = recovered.context.__gaiusPersistentFiles[mcc];
  const opfsMca = makeMca([{timestamp: 10, value: 0x4a}]);
  if (expected === "indexeddb") {
    assert.deepEqual(parseMca(actualMca), parseMca(idbMca), `${label}: pair sequence ignored for MCA`);
    assert.deepEqual(Array.from(actualMcc), Array.from(idbMcc), `${label}: pair sequence ignored for MCC`);
  } else {
    assert.deepEqual(parseMca(actualMca), parseMca(opfsMca), `${label}: path fallback chose wrong MCA`);
  }
  recovered.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(recovered.events, "stopped");
  console.log(`ok ${label}: pair sequence recovery`);
}
await assertPairCommitSequenceCase("opposite-path-generations", 2, "indexeddb");
await assertPairCommitSequenceCase("equal-sequence-different-bytes", 1, "opfs");
await assertPairCommitSequenceCase("old-new-mixed", null, "opfs");
await assertPairCommitSequenceCase("missing-one-side", 2, "opfs");

// Exhaustion is a rejected write, never a wrapped generation or a damaged log.
{
  const world = "generation-ceiling";
  const path = `/gaius/saves/${world}/region/r.0.0.mca`;
  const original = makeMca([{timestamp: 1, value: 0x78}]);
  const records = new Map([[path, {path, value: original, generation: 0xffffffff}]]);
  const runtime = createRuntime({opfsFiles: null, idbRecords: records});
  await startRuntime(runtime, world);
  await waitForEvent(runtime.events, "runtime-ready");
  assert.equal(runtime.context.__gaiusFsPutBytes(path,
    makeMca([{timestamp: 2, value: 0x79}])), false,
  "generation exhaustion wrapped and accepted a write");
  assert.match(String(runtime.events.find((event) => event?.type === "storage-write-error")?.detail || ""),
    /generation exhausted/);
  await runtime.context.__gaiusFsFlush();
  assert.deepEqual(parseMca(runtime.context.__gaiusPersistentFiles[path]), parseMca(original));
  runtime.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(runtime.events, "stopped");
  const reopened = createRuntime({opfsFiles: null, idbRecords: records});
  await startRuntime(reopened, world);
  await waitForEvent(reopened.events, "runtime-ready");
  assert.deepEqual(parseMca(reopened.context.__gaiusPersistentFiles[path]), parseMca(original),
    "generation exhaustion changed the durable region");
  reopened.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(reopened.events, "stopped");

  const opfsWorld = "opfs-generation-ceiling";
  const opfsPath = `/gaius/saves/${opfsWorld}/region/r.0.0.mca`;
  const opfsFiles = new Map();
  const opfsRecords = new Map();
  const opfsOwner = createRuntime({opfsFiles, idbRecords: opfsRecords});
  await startRuntime(opfsOwner, opfsWorld);
  assert.equal(opfsOwner.context.__gaiusFsPutBytes(opfsPath, original), true);
  await opfsOwner.context.__gaiusFsFlush();
  opfsOwner.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(opfsOwner.events, "stopped");
  const container = [...opfsFiles.values()].find((file) => file.size > 0);
  const durableSize = container.size;
  const testBootstrap = bootstrap +
    "\nglobalThis.__gaiusSetRegionGenerationForTest = (path, value) => {" +
    "const entry = regionIndex.get(path); regionIndex.set(path, {...entry, generation: value, opfsSequence: value});};";
  const opfsRuntime = createRuntime({opfsFiles, idbRecords: opfsRecords, bootstrapSource: testBootstrap});
  await startRuntime(opfsRuntime, opfsWorld);
  await waitForEvent(opfsRuntime.events, "runtime-ready");
  opfsRuntime.context.__gaiusSetRegionGenerationForTest(opfsPath, 0xffffffff);
  assert.equal(opfsRuntime.context.__gaiusFsPutBytes(opfsPath,
    makeMca([{timestamp: 2, value: 0x79}])), false,
  "OPFS generation exhaustion wrapped and accepted a write");
  assert.equal(container.size, durableSize, "OPFS generation exhaustion appended a corrupt record");
  opfsRuntime.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(opfsRuntime.events, "stopped");
  const opfsReopened = createRuntime({opfsFiles, idbRecords: opfsRecords});
  await startRuntime(opfsReopened, opfsWorld);
  await waitForEvent(opfsReopened.events, "runtime-ready");
  assert.deepEqual(parseMca(opfsReopened.context.__gaiusPersistentFiles[opfsPath]), parseMca(original),
    "OPFS generation exhaustion damaged the committed log");
  opfsReopened.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(opfsReopened.events, "stopped");
  console.log("ok generation-ceiling: write is refused without wrapping or changing durable bytes");
}

if (frozenBootstrap) await runFrozenCompatibilityCases();

// A byte-identical partner belongs to the IndexedDB source group when its sibling differs.
// Exercise both directions so recovery never rejects a valid .mca/.mcc pair as mixed-source.
async function assertIdenticalPartnerRecovery(label, differingMca) {
  const files = new Map();
  const records = new Map();
  const world = "identical-partner-" + label;
  const mca = `/gaius/saves/${world}/region/r.0.0.mca`;
  const mcc = `/gaius/saves/${world}/region/c.0.0.mcc`;
  const owner = createRuntime({opfsFiles: files, idbRecords: records});
  await startRuntime(owner, world);
  const opfsMca = makeMca([{timestamp: 1, value: 0x71}]);
  const opfsMcc = makeMcc(0x72);
  await owner.context.__gaiusFsPutBytes(mca, opfsMca);
  await owner.context.__gaiusFsPutBytes(mcc, opfsMcc);
  await owner.context.__gaiusFsFlush();
  owner.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(owner.events, "stopped");
  records.set(mca, {path: mca, value: differingMca ? makeMca([{timestamp: 2, value: 0x73}]) : opfsMca});
  records.set(mcc, {path: mcc, value: differingMca ? opfsMcc : makeMcc(0x74)});
  const recovered = createRuntime({opfsFiles: files, idbRecords: records});
  await startRuntime(recovered, world);
  await waitForEvent(recovered.events, "runtime-ready");
  assert.equal(recovered.events.some((event) => event?.type === "bootstrap-crash"), false,
    `${label}: identical partner was rejected as mixed-source`);
  assert.equal(records.has(mca), false, `${label}: MCA source was not deduped`);
  assert.equal(records.has(mcc), false, `${label}: MCC source was not deduped`);
  recovered.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(recovered.events, "stopped");
}
await assertIdenticalPartnerRecovery("mca-identical", false);
await assertIdenticalPartnerRecovery("mcc-identical", true);

// A differing IndexedDB region next to an OPFS region is recovered automatically. The replaced
// OPFS source is retained under the world's visible .gaius-backups folder before dedupe.
const staleRecords = new Map();
const keptPath = "/gaius/saves/locked-world/region/r.0.0.mca";
const onlyIdbPath = "/gaius/saves/locked-world/region/r.1.0.mca";
const staleKept = makeMca([{timestamp: 30, value: 0xd1}]);
staleRecords.set(keptPath, {path: keptPath, value: staleKept, generation: 100});
staleRecords.set(onlyIdbPath, {path: onlyIdbPath, value: makeMca([{timestamp: 30, value: 0xd2}])});
const migrating = createRuntime({opfsFiles: lockedFiles, idbRecords: staleRecords});
await startRuntime(migrating, "locked-world");
await waitForEvent(migrating.events, "runtime-ready");
assert.equal(migrating.events.some((event) => event?.type === "bootstrap-crash"), false,
  "a differing duplicate region still blocked world startup");
assert.equal(staleRecords.has(keptPath), false,
  "the recovered IndexedDB region was not deduped after backup");
assert.equal(staleRecords.has(onlyIdbPath), false,
  "an unrelated IndexedDB region was not migrated");
assert.deepEqual(parseMca(migrating.context.__gaiusPersistentFiles[keptPath]),
  parseMca(staleKept),
  "recovery selected an invalid or partial region copy");
const backupPaths = Array.from(staleRecords.keys()).filter((path) =>
  path.includes("/.gaius-backups/"));
assert.ok(backupPaths.length >= 1, "recovery did not retain the replaced OPFS copy as backup");
assert.equal(backupPaths.some((path) => path.endsWith(".mca")), false,
  "a backup retained a region suffix and could be re-ingested");

function makeExternalMca(timestamp) {
  const result = new Uint8Array(3 * 4096);
  const view = new DataView(result.buffer);
  view.setUint32(0, (2 << 8) | 1, false);
  view.setUint32(4096, timestamp, false);
  view.setUint32(8192, 1, false);
  result[8196] = 0x82;
  return result;
}

function makeMcc(value) {
  return new Uint8Array(deflateSync(Buffer.from([0x0a, value & 0xff, 0x00, 0x00])));
}

// Repeated crashes at every journal boundary reuse the intent's generation, preserve the
// original OPFS bytes, and stay bounded even with two generations from earlier recoveries.
for (const step of ["intent", "backup", "replace", "verify", "dedupe"]) {
  const crashFiles = new Map();
  const crashRecords = new Map();
  const crashWorld = "journal-crash-" + step;
  const crashMca = `/gaius/saves/${crashWorld}/region/r.0.0.mca`;
  const crashMcc = `/gaius/saves/${crashWorld}/region/c.0.0.mcc`;
  const owner = createRuntime({opfsFiles: crashFiles, idbRecords: crashRecords});
  await startRuntime(owner, crashWorld);
  const originalMca = makeMca([{timestamp: 1, value: 0x31}]);
  const originalMcc = makeMcc(0x31);
  await owner.context.__gaiusFsPutBytes(crashMca, originalMca);
  await owner.context.__gaiusFsPutBytes(crashMcc, originalMcc);
  await owner.context.__gaiusFsFlush();
  owner.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(owner.events, "stopped");
  const idbMca = makeExternalMca(2);
  const idbMcc = makeMcc(0x41);
  crashRecords.set(crashMca, {path: crashMca, value: idbMca, generation: 100});
  crashRecords.set(crashMcc, {path: crashMcc, value: idbMcc, generation: 100});
  for (const old of ["000-old", "001-old"]) {
    const path = `/gaius/saves/${crashWorld}/.gaius-backups/${old}/previous.bak`;
    crashRecords.set(path, {path, value: originalMca.slice(), recoveryBackup: true});
  }
  let backupDir;
  function assertBackups() {
    const backups = [...crashRecords.values()].filter((entry) => entry.path.includes("/.gaius-backups/"));
    const generations = new Set(backups.map((entry) => entry.path.split("/.gaius-backups/")[1].split("/")[0]));
    assert.ok(generations.size <= 2, `${step}: pending recovery exceeded two backup generations`);
    for (const entry of backups.filter((entry) => entry.originalPath)) {
      assert.ok(entry.path.startsWith(backupDir + "/"), `${step}: backup escaped intent generation`);
      assert.deepEqual(Array.from(entry.value),
        Array.from(entry.originalPath === crashMca ? originalMca : originalMcc),
        `${step}: resumed recovery overwrote original OPFS backup`);
    }
  }
  for (let attempt = 0; attempt < 5; attempt++) {
    const crashing = createRuntime({opfsFiles: crashFiles, idbRecords: crashRecords,
      recoveryCrashStep: step});
    await startRuntime(crashing, crashWorld);
    await waitForEvent(crashing.events, "bootstrap-crash");
    assert.match(String(crashing.events.find((event) => event?.type === "bootstrap-crash")?.detail),
      new RegExp("Injected recovery crash at " + step));
    assert.equal(crashRecords.has(crashMca), true, `${step}: MCA source deleted before recovery completed`);
    assert.equal(crashRecords.has(crashMcc), true, `${step}: MCC source deleted before recovery completed`);
    const journal = [...crashRecords.values()].find((entry) => entry.path.startsWith("/gaius/storage/recovery-journal/")).value;
    backupDir ??= journal.backupDir;
    assert.equal(journal.backupDir, backupDir, `${step}: restart created another backup generation`);
    if (step === "intent" && attempt === 0) {
      delete journal.backupGeneration; // Journals written before this field still reuse backupDir.
    }
    assertBackups();
  }
  const resumed = createRuntime({opfsFiles: crashFiles, idbRecords: crashRecords});
  await startRuntime(resumed, crashWorld);
  await waitForEvent(resumed.events, "runtime-ready");
  assert.deepEqual(parseMca(resumed.context.__gaiusPersistentFiles[crashMca]), parseMca(idbMca),
    `${step}: MCA did not resume from the IDB whole copy`);
  assert.deepEqual(Array.from(resumed.context.__gaiusPersistentFiles[crashMcc]), Array.from(idbMcc),
    `${step}: paired MCC did not resume from the IDB source`);
  assert.deepEqual(Array.from(inflateSync(Buffer.from(resumed.context.__gaiusPersistentFiles[crashMcc]))),
    Array.from(inflateSync(Buffer.from(idbMcc))), `${step}: MCC is not a readable compressed stream`);
  assert.equal(crashRecords.has(crashMca), false, `${step}: MCA source was not deduped`);
  assert.equal(crashRecords.has(crashMcc), false, `${step}: MCC source was not deduped`);
  assertBackups();
  assert.equal([...crashRecords.values()].filter((entry) => entry.originalPath).length, 2,
    `${step}: whole paired OPFS backup was not retained`);
  resumed.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(resumed.events, "stopped");
  console.log(`ok ${step}: five consecutive crashes reuse backups and recover the paired region`);
}

// Delete through the page's real persistent-FS bootstrap, then recreate the same id
// through the Worker's IndexedDB fallback. Metadata is scoped to this world only.
{
  const world = "delete-recreate-α";
  const files = new Map();
  const records = new Map();
  const owner = createRuntime({opfsFiles: files, idbRecords: records});
  await startRuntime(owner, world);
  await waitForEvent(owner.events, "runtime-ready");
  owner.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(owner.events, "stopped");
  const marker = [...records.keys()].find((path) => path.startsWith("/gaius/storage/opfs-worlds/"));
  assert.ok(marker, "OPFS Worker did not create its world marker");
  const container = marker.slice("/gaius/storage/opfs-worlds/".length);
  const journal = "/gaius/storage/recovery-journal/" + container;
  const backup = `/gaius/saves/${world}/.gaius-backups/old/region.bak`;
  for (const path of [journal, backup, `/gaius/saves/${world}/level.dat`]) {
    records.set(path, {path, value: "deleted-world"});
  }
  const siblingPaths = [marker + "-sibling", journal + "-sibling",
    `/gaius/saves/${world}-sibling/.gaius-backups/old/region.bak`];
  for (const path of siblingPaths) records.set(path, {path, value: "sibling"});
  const template = await readFile(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");
  const begin = template.indexOf("    (function installPersistentFsBootstrap() {");
  const end = template.indexOf("\n\n    function loadScript", begin);
  assert.ok(begin >= 0 && end > begin);
  const pageBootstrap = template.slice(begin, end)
    .replaceAll("gaius.fs.template:", DEFAULT_STORAGE_CONFIG.storagePrefix)
    .replaceAll("gaius-fs-template", DEFAULT_STORAGE_CONFIG.storageDatabaseName)
    .replaceAll("regions-template", DEFAULT_STORAGE_CONFIG.storageOpfsDirectory);
  const localRecords = new Map();
  const directory = {
    async getDirectoryHandle() { return directory; },
    async removeEntry(name) {
      assert.equal(name, container);
      assert.equal(records.has(marker), true, "marker disappeared before OPFS removal succeeded");
      assert.equal(files.delete(name), true, "deleted the wrong OPFS container");
    },
  };
  const page = {
    console, Date, Math, JSON, Promise, TextEncoder, Uint8Array, ArrayBuffer,
    setTimeout, clearTimeout, setInterval, clearInterval,
    performance,
    indexedDB: createIndexedDb(records),
    localStorage: {
      get length() { return localRecords.size; },
      key(index) { return [...localRecords.keys()][index] ?? null; },
      getItem(key) { return localRecords.get(key) ?? null; },
      setItem(key, value) { localRecords.set(key, value); },
      removeItem(key) { localRecords.delete(key); },
    },
    navigator: {storage: {async getDirectory() { return directory; }}},
    document: {getElementById() { return null; }}, addEventListener() {},
    __gaiusSingleplayerWorkers: new Map([[world, {
      __gaiusWorldId: world, __gaiusTerminal: true, __gaiusStorageClosed: true,
    }]]),
  };
  page.window = page;
  vm.runInNewContext(pageBootstrap, page);
  await page.__gaiusFsReady;
  for (const path of [marker, journal, backup, ...siblingPaths]) {
    localRecords.set(DEFAULT_STORAGE_CONFIG.storagePrefix + path, "local-copy");
  }
  assert.equal(page.__gaiusDeleteWorld(world), true);
  await page.__gaiusFsFlush();
  for (const path of [marker, journal, backup]) {
    assert.equal(records.has(path), false, `world deletion left ${path}`);
    assert.equal(Object.hasOwn(page.__gaiusPersistentFiles, path), false, `mirror retained ${path}`);
    assert.equal(localRecords.has(DEFAULT_STORAGE_CONFIG.storagePrefix + path), false);
  }
  for (const path of siblingPaths) {
    assert.equal(records.get(path).value, "sibling", "world deletion removed sibling metadata");
    assert.equal(localRecords.has(DEFAULT_STORAGE_CONFIG.storagePrefix + path), true);
  }
  await page.__gaiusFsClose();
  const fallback = createRuntime({opfsFiles: null, idbRecords: records});
  await startRuntime(fallback, world);
  await waitForEvent(fallback.events, "runtime-ready");
  assert.equal(fallback.context.__gaiusFsBackend, "indexeddb-worker-lru");
  assert.equal(fallback.events.some((event) => event?.type === "bootstrap-crash"), false);
  const path = `/gaius/saves/${world}/region/r.0.0.mca`;
  const bytes = makeMca([{timestamp: 3, value: 0x55}]);
  assert.equal(fallback.context.__gaiusFsPutBytes(path, bytes), true);
  await fallback.context.__gaiusFsFlush();
  assert.deepEqual(Array.from(records.get(path).value), Array.from(bytes));
  fallback.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(fallback.events, "stopped");
  const reopened = createRuntime({opfsFiles: null, idbRecords: records});
  await startRuntime(reopened, world);
  await waitForEvent(reopened.events, "runtime-ready");
  assert.deepEqual(Array.from(reopened.context.__gaiusPersistentFiles[path]), Array.from(bytes));
  reopened.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(reopened.events, "stopped");
  console.log("ok delete then recreate the same world id on IndexedDB fallback and reload its region");
}

// Three completed recoveries leave only two backup generations. Backup files are internal and
// never appear in the region index or the server's persistent-file view after restart.
const boundedFiles = new Map();
const boundedRecords = new Map();
const boundedWorld = "bounded-backups";
const boundedMca = `/gaius/saves/${boundedWorld}/region/r.0.0.mca`;
const boundedOwner = createRuntime({opfsFiles: boundedFiles, idbRecords: boundedRecords});
await startRuntime(boundedOwner, boundedWorld);
await boundedOwner.context.__gaiusFsPutBytes(boundedMca, makeMca([{timestamp: 1, value: 0x51}]));
await boundedOwner.context.__gaiusFsFlush();
boundedOwner.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(boundedOwner.events, "stopped");
for (let generation = 0; generation < 3; generation++) {
  const value = makeMca([{timestamp: generation + 2, value: 0x61 + generation}]);
  boundedRecords.set(boundedMca, {path: boundedMca, value});
  const runtime = createRuntime({opfsFiles: boundedFiles, idbRecords: boundedRecords});
  await startRuntime(runtime, boundedWorld);
  await waitForEvent(runtime.events, "runtime-ready");
  runtime.context.onmessage({data: {type: "stop"}, ports: []});
  await waitForEvent(runtime.events, "stopped");
}
const boundedBackupPaths = Array.from(boundedRecords.keys()).filter((path) =>
  path.includes("/.gaius-backups/"));
const boundedGenerations = new Set(boundedBackupPaths.map((path) =>
  path.split("/.gaius-backups/")[1].split("/")[0]));
assert.ok(boundedGenerations.size <= 2, "backup generations exceeded the configured bound");
assert.equal(boundedBackupPaths.some((path) => path.endsWith(".mca") || path.endsWith(".mcc")), false,
  "bounded backup retained a region suffix");

// World ownership across sessions (Web Locks). A page opened from disk never gets OPFS, so on
// IndexedDB the owner lock is the only cross-tab guard: a session that ended without closing the
// world (tab closed, renderer killed) must not block the next one, the next one must open the
// last completely flushed data, and a live session must still keep a second one out.
const ownerLocks = createLockManager();
const ownerRecords = new Map();
const ownerPath = "/gaius/saves/owner-world/region/r.0.0.mca";
const ownerFlushed = makeMca([{timestamp: 1, value: 0x21}]);
const ownerUnflushed = makeMca([{timestamp: 2, value: 0x22}]);
// The dead tab's Worker stops with it: once killed, its timers can no longer reach storage.
let ownerAAlive = true;
const ownerAView = {
  get: (path) => ownerRecords.get(path),
  set: (path, value) => { if (ownerAAlive) ownerRecords.set(path, value); },
  delete: (path) => { if (ownerAAlive) ownerRecords.delete(path); },
  values: () => ownerRecords.values(),
};
const ownerA = createRuntime({opfsFiles: null, idbRecords: ownerAView,
  webLocks: ownerLocks.forContext("tab-a")});
await startRuntime(ownerA, "owner-world");
await waitForEvent(ownerA.events, "runtime-ready", 3000);
assert.equal(ownerA.context.__gaiusFsBackend, "indexeddb-worker-lru");
assert.equal(ownerLocks.names().filter((name) => name.startsWith("gaius-world-owner:")).length, 1,
  "the open world did not hold its owner lock");
assert.equal(ownerA.context.__gaiusFsPutBytes(ownerPath, ownerFlushed), true);
await ownerA.context.__gaiusFsFlush();
// A write the dead session never flushed: the successor must see the last complete write.
assert.equal(ownerA.context.__gaiusFsPutBytes(ownerPath, ownerUnflushed), true);
// The tab is closed without Save and Quit: no stop reaches the Worker, the browser drops its locks.
ownerAAlive = false;
ownerLocks.kill("tab-a");
const ownerB = createRuntime({opfsFiles: null, idbRecords: ownerRecords,
  webLocks: ownerLocks.forContext("tab-b"),
  extraGlobals: {__gaiusWorldOwnerBusyWaitMillis: 100, __gaiusWorldOwnerClosingWaitMillis: 200}});
const ownerBStarted = performance.now();
await startRuntime(ownerB, "owner-world");
await waitForEvent(ownerB.events, "runtime-ready", 3000);
assert.ok(performance.now() - ownerBStarted < 1000, "a dead session's world was not taken over at once");
assert.equal(ownerB.events.some((event) => event?.type === "bootstrap-crash"), false);
assert.equal(ownerB.events.some((event) => event?.type === "storage-opfs-waiting"), false,
  "taking over a dead session's world reported a wait for it");
assert.deepEqual(parseMca(ownerB.context.__gaiusPersistentFiles[ownerPath]), parseMca(ownerFlushed),
  "the successor did not open the last completely flushed region");

// A live second tab is refused with an actionable reason and without touching the saved data.
const ownerRecordsBefore = JSON.stringify(Array.from(ownerRecords.keys()).sort());
const ownerC = createRuntime({opfsFiles: null, idbRecords: ownerRecords,
  webLocks: ownerLocks.forContext("tab-c"),
  extraGlobals: {__gaiusWorldOwnerBusyWaitMillis: 100, __gaiusWorldOwnerClosingWaitMillis: 200}});
await startRuntime(ownerC, "owner-world");
await waitForEvent(ownerC.events, "bootstrap-crash", 3000);
assert.match(String(ownerC.events.find((event) => event?.type === "bootstrap-crash")?.detail || ""),
  /open in another Gaius tab/, "a live owner's world was not reported as open elsewhere");
assert.equal(ownerC.events.some((event) => event?.type === "runtime-ready"), false,
  "a second live session opened a world that is in use");
assert.equal(JSON.stringify(Array.from(ownerRecords.keys()).sort()), ownerRecordsBefore,
  "the refused session changed durable records");
assert.equal(ownerB.events.some((event) => event?.type === "stopped"), false);

// A live owner that is saving (Save and Quit, reload) is waited for, then handed over.
ownerB.context.onmessage({data: {type: "stop"}, ports: []});
const ownerD = createRuntime({opfsFiles: null, idbRecords: ownerRecords,
  webLocks: ownerLocks.forContext("tab-d"),
  extraGlobals: {__gaiusWorldOwnerBusyWaitMillis: 1, __gaiusWorldOwnerClosingWaitMillis: 5000}});
await startRuntime(ownerD, "owner-world");
await waitForEvent(ownerB.events, "stopped", 3000);
await waitForEvent(ownerD.events, "runtime-ready", 3000);
assert.equal(ownerD.events.some((event) => event?.type === "bootstrap-crash"), false,
  "a successor gave up on a live owner that was saving");
assert.deepEqual(parseMca(ownerD.context.__gaiusPersistentFiles[ownerPath]), parseMca(ownerFlushed));
ownerD.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(ownerD.events, "stopped", 3000);
assert.deepEqual(ownerLocks.names(), [], "a stopped session kept its world owner locks");

// OPFS: the dead owner's exclusive handle outlives its locks until the browser cleans it up.
// Holding the owner lock proves no live session owns the container, so the successor waits for
// that release beyond the short live-handoff window instead of refusing the world.
const deadOpfsFiles = new Map();
deadOpfsFiles.exclusiveHandles = true;
const deadOpfsRecords = new Map();
const deadOpfsPath = "/gaius/saves/stale-world/region/r.0.0.mca";
const deadOpfsSaved = makeMca([{timestamp: 3, value: 0x31}]);
const deadOpfsOwner = createRuntime({opfsFiles: deadOpfsFiles, idbRecords: deadOpfsRecords,
  webLocks: ownerLocks.forContext("tab-e")});
await startRuntime(deadOpfsOwner, "stale-world");
await waitForEvent(deadOpfsOwner.events, "runtime-ready", 3000);
assert.equal(deadOpfsOwner.context.__gaiusFsBackend, "opfs-sync-worker");
assert.equal(deadOpfsOwner.context.__gaiusFsPutBytes(deadOpfsPath, deadOpfsSaved), true);
await deadOpfsOwner.context.__gaiusFsFlush();
ownerLocks.kill("tab-e");
const deadOpfsFile = Array.from(deadOpfsFiles.values()).find((file) => file.activeHandle);
assert.ok(deadOpfsFile, "the dead owner's handle was not held");
setTimeout(() => { deadOpfsFile.activeHandle = undefined; }, 400);
const deadOpfsSuccessor = createRuntime({opfsFiles: deadOpfsFiles, idbRecords: deadOpfsRecords,
  webLocks: ownerLocks.forContext("tab-f"), opfsLockWaitMillis: 50,
  extraGlobals: {__gaiusOpfsStaleHandleWaitMillis: 3000}});
await startRuntime(deadOpfsSuccessor, "stale-world");
await waitForEvent(deadOpfsSuccessor.events, "runtime-ready", 4000);
assert.equal(deadOpfsSuccessor.events.some((event) => event?.type === "bootstrap-crash"), false,
  "a dead session's lingering OPFS handle refused the world");
assert.match(String(deadOpfsSuccessor.events.find((event) => event?.type === "storage-opfs-waiting")?.detail || ""),
  /ended without closing this world/);
assert.equal(deadOpfsSuccessor.context.__gaiusFsBackend, "opfs-sync-worker");
assert.deepEqual(parseMca(deadOpfsSuccessor.context.__gaiusPersistentFiles[deadOpfsPath]), parseMca(deadOpfsSaved),
  "the successor lost the dead session's flushed OPFS region");
deadOpfsSuccessor.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(deadOpfsSuccessor.events, "stopped", 3000);

console.log(JSON.stringify({
  ok: true,
  largeFallbackBytes: largeFallbackRegions * largeFallbackRegionBytes,
  largeFallbackBudgetBytes: largeFallbackStats.cacheBudgetBytes,
  fixtureRegions: fixtureCount,
  fixtureExternalChunks: externalFixtureCount,
  fixtureBytes: fixtureCount * fixtureBytes +
    externalFixtureCount * externalFixtureBytes,
  cacheBudgetBytes: firstStats.cacheBudgetBytes,
  cachePeakBytes: firstStats.cachePeakBytes,
  evictions: firstStats.evictions,
  restartRecoveredEntries: restoredStats.regionEntries,
  restartHydratedEntries: restoredStats.cacheEntries,
  existenceScanHydratedBytes: afterExistenceScan.cacheBytes,
  movedExternalBytes: restoredMovedBytes.byteLength,
  recoveredTailBytes: restoredStats.recoveredTailBytes,
  fallbackPreservedEntries: fallbackRecords.size,
  reentryOldHandleConflicts: reentryFiles.lockConflicts,
  reentryBackend: reentry.context.__gaiusFsBackend,
  worldOwnerTakeover: ownerB.context.__gaiusFsBackend,
  staleOpfsTakeover: deadOpfsSuccessor.context.__gaiusFsBackend,
}));
