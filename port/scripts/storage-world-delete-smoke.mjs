#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

// A failed write is deliberately kept rejected until the next cleanup operation. The VM would
// otherwise report that expected intermediate rejection before the test can exercise recovery.
process.on("unhandledRejection", error => {
  if (!/synthetic write rejection/.test(String(error && error.message || error))) throw error;
});
process.on("uncaughtException", error => {
  if (!/synthetic write rejection/.test(String(error && error.message || error))) throw error;
});

const template = await readFile(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");
const bootstrapStart = template.indexOf("    (function installPersistentFsBootstrap() {");
const bootstrapEnd = template.indexOf("\n\n    function loadScript", bootstrapStart);
assert.ok(bootstrapStart >= 0 && bootstrapEnd > bootstrapStart, "persistent FS bootstrap not found");
const bootstrap = template.slice(bootstrapStart, bootstrapEnd);

class FakeStorage {
  constructor(entries = {}) { this.map = new Map(Object.entries(entries)); }
  get length() { return this.map.size; }
  key(index) { return [...this.map.keys()][index] ?? null; }
  getItem(key) { return this.map.has(key) ? this.map.get(key) : null; }
  setItem(key, value) { this.map.set(String(key), String(value)); }
  removeItem(key) { this.map.delete(String(key)); }
}

class FakeTransaction {
  constructor(database, mode) {
    this.database = database; this.mode = mode; this.error = null;
    this.oncomplete = null; this.onerror = null; this.onabort = null;
    this.failed = false; this.finished = false;
  }
  fail(error) {
    if (this.finished) return;
    this.failed = true; this.error = error;
    setTimeout(() => {
      if (this.finished) return;
      this.finished = true;
      if (this.onerror) this.onerror();
      if (this.onabort) this.onabort();
    }, 10);
  }
  complete() {
    if (this.finished || this.failed) return;
    setTimeout(() => {
      if (this.finished || this.failed) return;
      this.finished = true;
      if (this.oncomplete) this.oncomplete();
    }, 10);
  }
  objectStore() {
    const tx = this;
    return {
      put(record) {
        if (tx.database.failNextWrite && tx.mode === "readwrite") {
          tx.database.failNextWrite = false;
          tx.fail(new Error("synthetic write rejection"));
          return;
        }
        tx.database.records.set(String(record.path), {...record});
        tx.complete();
      },
      delete(path) {
        if (tx.database.failMetadataDelete && String(path).startsWith("/gaius/storage/opfs-worlds/")) {
          tx.fail(new Error("synthetic metadata cleanup rejection"));
          return;
        }
        tx.database.records.delete(String(path));
        if (!tx.cursorActive) tx.complete();
      },
      get(path) {
        const request = {result: tx.database.records.get(String(path)) || undefined, onsuccess: null, onerror: null};
        setTimeout(() => request.onsuccess && request.onsuccess(), 0);
        return request;
      },
      openKeyCursor() {
        tx.cursorActive = true;
        const request = {result: null, onsuccess: null, onerror: null};
        const keys = [...tx.database.records.keys()];
        let index = 0;
        const step = () => {
          if (index >= keys.length) {
            request.result = null;
            if (request.onsuccess) request.onsuccess();
            tx.cursorActive = false;
            tx.complete();
            return;
          }
          const key = keys[index++];
          request.result = {primaryKey: key, continue: () => setTimeout(step, 0)};
          if (request.onsuccess) request.onsuccess();
        };
        setTimeout(step, 0);
        return request;
      }
    };
  }
}

class FakeDatabase {
  constructor() {
    this.records = new Map(); this.failNextWrite = false;
    this.objectStoreNames = {contains: () => true};
  }
  createObjectStore() { return {}; }
  transaction(_name, mode) { return new FakeTransaction(this, mode); }
  close() {}
}

function fakeIndexedDb(database, failFirst = false) {
  let opens = 0;
  return {
    open() {
      const request = {result: database, error: null, onupgradeneeded: null, onsuccess: null, onerror: null, onblocked: null};
      setTimeout(() => {
        opens++;
        if (failFirst && opens === 1) {
          request.error = new Error("synthetic IndexedDB unavailable");
          if (request.onerror) request.onerror();
          return;
        }
        if (request.onupgradeneeded) request.onupgradeneeded();
        if (request.onsuccess) request.onsuccess();
      }, 0);
      return request;
    }
  };
}

function fakeOpfs(state) {
  const regions = {
    async removeEntry(name) {
      state.attempts = (state.attempts || 0) + 1;
      if (state.open || state.retryFailures > 0) {
        if (state.retryFailures > 0) state.retryFailures--;
        const error = new Error("SyncAccessHandle is still open");
        error.name = "InvalidStateError";
        throw error;
      }
      state.removed.push(name);
    }
  };
  const gaius = {async getDirectoryHandle() { return regions; }};
  const root = {async getDirectoryHandle() { return gaius; }};
  return {async getDirectory() { return root; }, async persist() { return false; }};
}

async function boot({storage, database = null, idbFailFirst = false, opfs = {open: false, removed: []}}) {
  const events = [];
  const context = {
    console: {warn() {}, info() {}, error() {}}, Date, Math, JSON, Promise, TextEncoder,
    Uint8Array, ArrayBuffer, setTimeout, clearTimeout, setInterval, clearInterval,
    performance: {now: () => Date.now()},
    navigator: {storage: fakeOpfs(opfs)}, localStorage: storage,
    document: {getElementById() { return null; }},
    addEventListener() {},
    __gaiusMinecraftEvents: events, __gaiusMinecraftCounters: {}
  };
  context.window = context;
  if (database) context.indexedDB = fakeIndexedDb(database, idbFailFirst);
  vm.createContext(context);
  vm.runInContext(bootstrap, context);
  await context.__gaiusFsReady;
  return {context, events, opfs, database};
}

const worldKey = "gaius.fs.template:/gaius/saves/alpha/level.dat";
const otherKey = "gaius.fs.template:/gaius/saves/beta/level.dat";

{
  const storage = new FakeStorage({[worldKey]: "alpha"});
  const database = new FakeDatabase();
  const opfs = {open: false, removed: []};
  const {context, events} = await boot({storage, database, opfs});
  context.location = {protocol: "file:"};
  context.__gaiusPersistentFiles["/gaius/saves/alpha/level.dat"] = "alpha";
  context.__gaiusSingleplayerWorkers = new Map([["alpha", {
    __gaiusWorldId: "alpha", __gaiusTerminal: true, __gaiusStorageClosed: true
  }]]);
  assert.equal(context.__gaiusDeleteWorld("alpha"), true);
  await context.__gaiusFsFlush();
  assert.equal(opfs.attempts || 0, 0, "file:// must not probe unavailable OPFS");
  assert.equal(events.some(event => event.event === "storage:storage-world-deleted"), true,
    "file:// must use IndexedDB cleanup when OPFS is unavailable");
  console.log("ok file origin skips the unsupported OPFS probe");
}

{
  const database = new FakeDatabase();
  const path = "/gaius/saves/alpha/level.dat";
  database.records.set(path, {path, value: "stale-idb"});
  const storage = new FakeStorage({[worldKey]: "alpha"});
  const {context, events} = await boot({storage, database, idbFailFirst: true});
  assert.equal(context.__gaiusFsBackend, "localStorage", "startup must use fallback after the first IDB open fails");
  assert.equal(context.__gaiusDeleteWorld("alpha"), true);
  await context.__gaiusFsFlush();
  assert.equal(database.records.has(path), false, "delete must reopen IDB and remove stale world records");
  assert.equal(storage.getItem(worldKey), null);
  assert.ok(events.some(event => event.event === "storage:storage-world-deleted"));
  console.log("ok fallback delete reopens IndexedDB and removes stale records");
}

{
  const storage = new FakeStorage({[worldKey]: "alpha", [otherKey]: "beta"});
  const {context, events} = await boot({storage});
  assert.equal(context.__gaiusFsBackend, "localStorage");
  assert.equal(context.__gaiusDeleteWorld("alpha"), true);
  await assert.rejects(context.__gaiusFsFlush(), /IndexedDB is not available/);
  assert.equal(storage.getItem(worldKey), "alpha", "fallback deletion must fail closed when IDB cannot open");
  assert.ok(events.some(event => event.event === "storage:storage-world-delete-failed"));
  console.log("ok localStorage fallback refuses deletion when IndexedDB cannot open");
}

{
  const database = new FakeDatabase();
  const {context} = await boot({storage: new FakeStorage(), database});
  context.__gaiusFsPut("/gaius/saves/alpha/level.dat", "alpha");
  await context.__gaiusFsFlush();
  context.__gaiusFsPut("/gaius/saves/alpha/region/r.0.0.mca", "region");
  database.failNextWrite = true;
  let writeFailed = false;
  try {
    await context.__gaiusFsFlush();
  } catch (error) {
    writeFailed = true;
    assert.match(String(error && error.message || error), /synthetic write rejection/);
  }
  assert.equal(writeFailed, true, "the injected write must reject");
  assert.equal(context.__gaiusDeleteWorld("alpha"), true, "delete remains synchronous for vanilla");
  try {
    await context.__gaiusFsFlush();
  } catch (error) {
    assert.match(String(error && error.message || error), /synthetic write rejection/);
  }
  assert.equal(database.records.has("/gaius/saves/alpha/level.dat"), false);
  assert.equal(database.records.has("/gaius/saves/alpha/region/r.0.0.mca"), false,
    "a rejected prior write must not poison world cleanup");
  console.log("ok rejected IndexedDB write does not block world cleanup");
}

{
  const storage = new FakeStorage({[worldKey]: "alpha"});
  const {context, events} = await boot({storage});
  context.__gaiusSingleplayerWorkers = new Map([["alpha", {
    __gaiusWorldId: "alpha", __gaiusTerminal: true, __gaiusStorageClosed: false
  }]]);
  assert.equal(context.__gaiusDeleteWorld("alpha"), false, "terminal without close proof must refuse deletion");
  assert.equal(storage.getItem(worldKey), "alpha");
  assert.ok(events.some(event => event.event === "storage:storage-world-delete-refused"));
  console.log("ok terminal Worker without close proof refuses deletion");
}

{
  const storage = new FakeStorage({[worldKey]: "alpha"});
  const opfs = {open: false, retryFailures: 1, removed: []};
  const database = new FakeDatabase();
  database.records.set("/gaius/saves/alpha/level.dat", {
    path: "/gaius/saves/alpha/level.dat", value: "alpha"
  });
  const {context, events} = await boot({storage, opfs, database});
  const listeners = new Set();
  const worker = {
    __gaiusWorldId: "alpha", __gaiusTerminal: false, __gaiusStopped: false,
    __gaiusStopRequested: true,
    __gaiusStorageClosed: false,
    addEventListener(type, listener) { if (type === "message") listeners.add(listener); },
    removeEventListener(type, listener) { if (type === "message") listeners.delete(listener); }
  };
  context.__gaiusSingleplayerWorkers = new Map([["alpha", worker]]);
  assert.equal(context.__gaiusDeleteWorld("alpha"), true,
    "a Worker already stopping must not throw through vanilla delete");
  setTimeout(() => {
    worker.__gaiusStorageClosed = true;
    for (const listener of listeners) listener({data: {type: "storage-closed"}});
  }, 5);
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(database.records.has("/gaius/saves/alpha/level.dat"), true,
    "durable deletion must wait for the Worker stop barrier");
  setTimeout(() => {
    worker.__gaiusStopped = true;
    worker.__gaiusTerminal = true;
    worker.__gaiusStorageRefresh = new Promise(resolve => setTimeout(() => {
      context.__gaiusPersistentFiles["/gaius/saves/alpha/level.dat"] = "rehydrated";
      resolve();
    }, 5));
    for (const listener of listeners) listener({data: {type: "stopped"}});
  }, 5);
  await context.__gaiusFsFlush();
  assert.equal(storage.getItem(worldKey), null);
  assert.equal(database.records.has("/gaius/saves/alpha/level.dat"), false);
  assert.equal(context.__gaiusPersistentFiles["/gaius/saves/alpha/level.dat"], undefined,
    "storage refresh after stop must not resurrect the deleted world in memory");
  assert.equal(opfs.removed.length, 1);
  assert.equal(opfs.attempts, 2, "transient OPFS handle contention must be retried");
  assert.ok(events.some(event => event.event === "storage:storage-world-deleted"));
  console.log("ok delete waits for close and stop, then clears refreshed world metadata");
}

{
  const storage = new FakeStorage({[worldKey]: "alpha"});
  const {context, events} = await boot({storage});
  context.__gaiusSingleplayerWorkers = new Map([["alpha", {
    __gaiusWorldId: "alpha", __gaiusTerminal: false, __gaiusStopped: false,
    __gaiusStopRequested: false, __gaiusStorageClosed: false
  }]]);
  assert.equal(context.__gaiusDeleteWorld("alpha"), false,
    "an active Worker without a stop request must refuse deletion");
  assert.equal(storage.getItem(worldKey), "alpha");
  assert.ok(events.some(event => event.event === "storage:storage-world-delete-refused"));
  console.log("ok active Worker without a stop request refuses deletion");
}

{
  const storage = new FakeStorage({[worldKey]: "alpha"});
  const opfs = {open: false, removed: []};
  const {context, events} = await boot({storage, opfs, database: new FakeDatabase()});
  context.__gaiusPersistentFiles["/gaius/saves/alpha/level.dat"] = "alpha";
  const worker = {
    __gaiusWorldId: "alpha", __gaiusTerminal: true, __gaiusStorageClosed: true,
    __gaiusStorageRefresh: new Promise(resolve => setTimeout(() => {
      context.__gaiusPersistentFiles["/gaius/saves/alpha/level.dat"] = "rehydrated";
      resolve();
    }, 5))
  };
  context.__gaiusSingleplayerWorkers = new Map([["alpha", worker]]);
  assert.equal(context.__gaiusDeleteWorld("alpha"), true);
  await context.__gaiusFsFlush();
  assert.equal(storage.getItem(worldKey), null);
  assert.equal(context.__gaiusPersistentFiles["/gaius/saves/alpha/level.dat"], undefined);
  assert.equal(opfs.removed.length, 1);
  assert.ok(events.some(event => event.event === "storage:storage-world-deleted"));
  console.log("ok confirmed close proof permits deletion and removes OPFS data");
}

{
  const storage = new FakeStorage({[worldKey]: "alpha"});
  const opfs = {open: true, removed: []};
  const database = new FakeDatabase();
  const marker = "/gaius/storage/opfs-worlds/alpha-5d8b6dab.regions";
  const journal = "/gaius/storage/recovery-journal/alpha-5d8b6dab.regions";
  for (const path of [marker, journal, "/gaius/saves/alpha/level.dat"])
    database.records.set(path, {path, value: "keep-on-failure"});
  const {context, events} = await boot({storage, opfs, database});
  storage.setItem(worldKey, "alpha-after-boot");
  const fallbackBefore = storage.getItem(worldKey);
  for (const path of [marker, journal]) context.__gaiusPersistentFiles[path] = "keep-on-failure";
  context.__gaiusPersistentFiles["/gaius/saves/alpha/level.dat"] = "alpha";
  context.__gaiusSingleplayerWorkers = new Map([["alpha", {
    __gaiusWorldId: "alpha", __gaiusTerminal: true, __gaiusStorageClosed: true
  }]]);
  assert.equal(context.__gaiusDeleteWorld("alpha"), true);
  await assert.rejects(context.__gaiusFsFlush(), /close it first/);
  assert.ok(events.some(event => event.event === "storage:storage-world-delete-failed"),
    "OPFS removal failure must be reported");
  assert.equal(context.__gaiusPersistentFiles["/gaius/saves/alpha/level.dat"], "alpha",
    "failed delete must restore the in-memory world mirror");
  for (const path of [marker, journal]) {
    assert.equal(database.records.get(path).value, "keep-on-failure");
    assert.equal(context.__gaiusPersistentFiles[path], "keep-on-failure");
  }
  assert.equal(database.records.get("/gaius/saves/alpha/level.dat").value, "keep-on-failure",
    "an OPFS lock failure must happen before deleting IndexedDB world data");
  assert.equal(storage.getItem(worldKey), fallbackBefore,
    "an OPFS lock failure must happen before deleting fallback world data");
  console.log("ok OPFS removal failure is surfaced after synchronous delete acceptance");
}

{
  const database = new FakeDatabase();
  database.failMetadataDelete = true;
  const {context, events} = await boot({storage: new FakeStorage(), database});
  const path = "/gaius/saves/alpha/level.dat";
  context.__gaiusPersistentFiles[path] = "restore-on-failure";
  assert.equal(context.__gaiusDeleteWorld("alpha"), true);
  await assert.rejects(context.__gaiusFsFlush(), /synthetic metadata cleanup rejection/);
  assert.equal(context.__gaiusPersistentFiles[path], "restore-on-failure");
  assert.ok(events.some(event => event.event === "storage:storage-world-delete-failed"));
  assert.equal(events.some(event => event.event === "storage:storage-world-deleted"), false);
  // The failed delete must leave the global queue usable. Clear the injected failure,
  // enqueue a fresh write, and require a successful flush afterwards.
  database.failMetadataDelete = false;
  const followupPath = "/gaius/saves/beta/level.dat";
  assert.equal(context.__gaiusFsPut(followupPath, "follow-up"), true);
  await context.__gaiusFsFlush();
  assert.equal(database.records.get(followupPath).value, "follow-up",
    "a failed delete must not poison subsequent writes");
  console.log("ok metadata cleanup failure restores the mirror and queue for follow-up writes");
}

console.log("STORAGE_WORLD_DELETE_SMOKE_OK");
