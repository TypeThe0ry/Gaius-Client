#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {join, resolve} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import vm from "node:vm";

const packageRoot = process.env.GAIUS_FAKE_INDEXEDDB_ROOT
  ? resolve(process.env.GAIUS_FAKE_INDEXEDDB_ROOT)
  : resolve(fileURLToPath(new URL("vendor/fake-indexeddb-6.2.7", import.meta.url)));
const {IDBFactory} = await import(pathToFileURL(join(packageRoot, "build/esm/index.js")));
const pageSource = await readFile(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");
const workerSource = await readFile(new URL("../web/singleplayer/server-worker-bootstrap.js", import.meta.url), "utf8");

function extractFunction(source, marker) {
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `missing function ${marker}`);
  const open = source.indexOf("{", start);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === "{") depth++;
    else if (source[i] === "}" && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error(`unbalanced function ${marker}`);
}

function transactionDone(transaction) {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = resolve;
    transaction.onerror = () => reject(transaction.error || new Error("IndexedDB transaction failed"));
    transaction.onabort = () => reject(transaction.error || new Error("IndexedDB transaction aborted"));
  });
}

function instrument(database, abortAfterPut = false) {
  const state = {putCount: 0, terminated: false};
  const wrapped = {
    transaction(...args) {
      const transaction = database.transaction(...args);
      const nativeObjectStore = transaction.objectStore.bind(transaction);
      transaction.objectStore = (name) => {
        const store = nativeObjectStore(name);
        const nativePut = store.put.bind(store);
        store.put = (...putArgs) => {
          state.putCount++;
          const request = nativePut(...putArgs);
          if (abortAfterPut) {
            request.addEventListener("success", () => {
              // A terminated worker cannot finish the active transaction. Abort it at the
              // first task boundary after the target record request has succeeded.
              state.terminated = true;
              transaction.abort();
            }, {once: true});
          }
          return request;
        };
        return store;
      };
      return transaction;
    },
  };
  return {database: wrapped, state};
}

function openDatabase(factory, name) {
  return new Promise((resolve, reject) => {
    const request = factory.open(name, 1);
    request.onupgradeneeded = () => request.result.createObjectStore("files", {keyPath: "path"});
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
  });
}

async function seed(database, path, bytes) {
  const transaction = database.transaction("files", "readwrite");
  transaction.objectStore("files").put({path, value: bytes});
  await transactionDone(transaction);
}

async function readBytes(database, path) {
  const transaction = database.transaction("files", "readonly");
  const completed = transactionDone(transaction);
  const request = transaction.objectStore("files").get(path);
  const record = await new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  await completed;
  return record?.value == null ? null : new Uint8Array(record.value);
}

function pageContext(database, files, state) {
  const normalize = extractFunction(pageSource, "function normalize(path)");
  const copy = extractFunction(pageSource, "function copyPersistentBytes(value)")
    .replace("function copyPersistentBytes(value)", "function copyPersistentBytesImpl(value)");
  const put = extractFunction(pageSource, "async function putIndexedDbFile(path, value)");
  const putBytesStart = pageSource.indexOf("window.__gaiusFsPutBytes = function(path, value) {");
  assert.notEqual(putBytesStart, -1);
  const putBytes = extractFunction(pageSource.slice(putBytesStart), "window.__gaiusFsPutBytes = function(path, value)");
  const context = {
    ArrayBuffer, Date, Error, Map, Number, Object, Promise, String, Uint8Array,
    db: database, storeName: "files", files, window: {}, pending: Promise.resolve(),
    persistentWriteGeneration: 0, storageWriteFailure: null, copyCount: 0,
    transactionDone,
    report() {},
  };
  vm.createContext(context);
  vm.runInContext(`${normalize}\n${copy}\nfunction copyPersistentBytes(value) { copyCount++; return copyPersistentBytesImpl(value); }\n${put}\n${putBytes}\nglobalThis.getPending = () => pending;`, context);
  context.putCount = state.putCount;
  return context;
}

function workerContext(database) {
  const source = [
    extractFunction(workerSource, "async function writeBatch(changes)"),
  ].join("\n");
  const context = {
    Date, Error, Map, Number, Object, Promise, String, Uint8Array,
    database, storeName: "files", root: {}, activeFlushTransaction: undefined,
    regionCache: new Map(), regionIndex: new Map(), fileValues: {},
    pairCommitSequenceForPath() { return 0; },
    trimRegionCache() {},
    transactionDone,
  };
  context.root = context;
  vm.createContext(context);
  vm.runInContext(`${source}\nglobalThis.runBatch = writeBatch;`, context);
  return context;
}

const factory = new IDBFactory();
const worldPath = "/gaius/saves/review/level.dat";
const oldBytes = Uint8Array.from([0, 1, 2, 127, 128, 254, 255]);
const newBytes = Uint8Array.from([255, 254, 128, 127, 2, 1, 0]);
const failures = [];
function check(condition, message) {
  if (!condition) failures.push(message);
}
function checkBytes(actual, expected, message) {
  check(actual !== null && [...actual].every((byte, index) => byte === expected[index]) &&
    actual.length === expected.length, message);
}

{
  const db = await openDatabase(factory, "page-atomicity");
  await seed(db, worldPath, oldBytes);
  const files = {[worldPath]: oldBytes.slice()};
  const tracked = instrument(db);
  const page = pageContext(tracked.database, files, tracked.state);
  assert.equal(page.window.__gaiusFsPutBytes(worldPath, newBytes), true);
  checkBytes(files[worldPath], oldBytes, "page mirror changed before commit");
  await page.getPending();
  check(tracked.state.putCount === 1, "page write staged more than one IndexedDB record");
  check(page.copyCount === 1, "page staged an extra JavaScript byte copy");
  checkBytes(files[worldPath], newBytes, "page mirror did not publish committed bytes");
  db.close();
  const restarted = await openDatabase(factory, "page-atomicity");
  checkBytes(await readBytes(restarted, worldPath), newBytes, "page restart bytes differ");
  restarted.close();
}

{
  const db = await openDatabase(factory, "page-abort");
  await seed(db, worldPath, oldBytes);
  const tracked = instrument(db, true);
  const files = {[worldPath]: oldBytes.slice()};
  const page = pageContext(tracked.database, files, tracked.state);
  page.window.__gaiusFsPutBytes(worldPath, newBytes);
  let aborted = false;
  try { await page.getPending(); } catch (error) { aborted = /abort|Abort/i.test(error.message); }
  check(aborted, "page abort was not surfaced");
  check(tracked.state.putCount === 1, "page abort issued more than one target write");
  check(tracked.state.terminated, "page abort boundary was not exercised");
  checkBytes(files[worldPath], oldBytes, "aborted page mirror changed");
  db.close();
  const restarted = await openDatabase(factory, "page-abort");
  checkBytes(await readBytes(restarted, worldPath), oldBytes, "aborted page lost prior bytes");
  restarted.close();
}

{
  const regionPath = "/gaius/saves/review/region/r.0.0.mca";
  const db = await openDatabase(factory, "page-region-snapshot");
  const tracked = instrument(db);
  const page = pageContext(tracked.database, {}, tracked.state);
  page.window.__gaiusFsPutBytes(regionPath, newBytes);
  await page.getPending();
  check(tracked.state.putCount === 1, "page region full snapshot used an extra temp record");
  check(page.copyCount === 1, "region full snapshot staged an extra JavaScript byte copy");
  db.close();
}

{
  const db = await openDatabase(factory, "worker-termination");
  await seed(db, worldPath, oldBytes);
  const tracked = instrument(db, true);
  const worker = workerContext(tracked.database);
  worker.fileValues[worldPath] = oldBytes.slice();
  let aborted = false;
  try { await worker.runBatch(new Map([[worldPath, {
    value: newBytes, version: 1, region: false,
  }]])); } catch (error) { aborted = /abort|Abort/i.test(error.message); }
  check(aborted, "worker termination abort was not surfaced");
  check(tracked.state.putCount === 1, "worker termination crossed more than one record write");
  check(tracked.state.terminated, "worker termination boundary was not exercised");
  checkBytes(worker.fileValues[worldPath], oldBytes, "terminated worker published uncommitted bytes");
  db.close();
  const restarted = await openDatabase(factory, "worker-termination");
  checkBytes(await readBytes(restarted, worldPath), oldBytes, "worker termination lost prior bytes");
  restarted.close();
}

assert.deepEqual(failures, [], failures.join("\n"));
console.log("IndexedDB transaction atomicity PASS pageCommit=byte-exact pageAbort=old-bytes workerTerminate=old-bytes regionWrites=1");
