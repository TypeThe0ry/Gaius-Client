#!/usr/bin/env node
// The launcher's persistent file bootstrap (installPersistentFsBootstrap in
// port/web/launcher/index.template.html) in node:vm with an in-memory IndexedDB. Screenshots:
//   - startup lists their names without reading one, and none reaches __gaiusPersistentFiles;
//   - startup keeps only the newest maximumScreenshots (by name, which is date and time);
//   - __gaiusFsPutScreenshot stores bytes with their size and no memory copy, refuses files over
//     maximumScreenshotBytes, and keeps the total within maximumScreenshotTotalBytes (records an
//     older page wrote as base64 are measured once for that);
//   - __gaiusScreenshots.read returns one on demand.
// The budgets are shortened for the test. Exit status 0 when every check passes.

import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const template = readFileSync(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");
const start = template.indexOf("    (function installPersistentFsBootstrap() {");
const end = template.indexOf("\n    })();\n", start);
assert.ok(start >= 0 && end > start, "persistent file bootstrap not found");
let bootstrap = template.slice(start, end + "\n    })();\n".length);
for (const [from, to] of [
  ["const maximumScreenshots = 32;", "const maximumScreenshots = 4;"],
  ["const maximumScreenshotBytes = 32 * 1024 * 1024;", "const maximumScreenshotBytes = 1000;"],
  ["const maximumScreenshotTotalBytes = 256 * 1024 * 1024;", "const maximumScreenshotTotalBytes = 2500;"],
]) {
  assert.ok(bootstrap.includes(from), `budget constant changed: ${from}`);
  bootstrap = bootstrap.replace(from, to);
}

// A small IndexedDB: one database, one object store keyed by "path", requests answered on
// later macrotasks and transactions completing once their requests are done.
function createIndexedDb(records) {
  const reads = [];
  const later = callback => setTimeout(callback, 0);
  function transaction() {
    let open = 0;
    let completed = false;
    const tx = {
      oncomplete: null, onerror: null, onabort: null,
      abort() {},
      objectStore() { return store; },
    };
    const settle = () => later(() => {
      if (open === 0 && !completed) {
        completed = true;
        if (tx.oncomplete) tx.oncomplete();
      }
    });
    const request = (run) => {
      open++;
      const req = {result: undefined, onsuccess: null, onerror: null};
      later(() => {
        req.result = run(req);
        if (req.onsuccess) req.onsuccess();
        open--;
        settle();
      });
      return req;
    };
    const store = {
      get(key) {
        return request(() => {
          reads.push(key);
          const record = records.get(key);
          return record === undefined ? undefined : structuredClone(record);
        });
      },
      put(record) { return request(() => { records.set(record.path, structuredClone(record)); }); },
      delete(key) { return request(() => { records.delete(key); }); },
      openKeyCursor() {
        const keys = [...records.keys()].sort();
        let index = 0;
        open++;
        const req = {result: null, onsuccess: null, onerror: null};
        const step = () => later(() => {
          if (index < keys.length) {
            const key = keys[index++];
            req.result = {primaryKey: key, continue: step};
          } else {
            req.result = null;
            open--;
            settle();
          }
          if (req.onsuccess) req.onsuccess();
        });
        step();
        return req;
      },
    };
    settle();
    return tx;
  }
  const database = {
    objectStoreNames: {contains: () => true},
    createObjectStore() {},
    transaction,
    close() {},
  };
  return {
    reads,
    open() {
      const request = {result: database, onsuccess: null, onerror: null, onupgradeneeded: null, onblocked: null};
      later(() => request.onsuccess && request.onsuccess());
      return request;
    },
  };
}

async function boot(records) {
  const indexedDB = createIndexedDb(records);
  const window = {indexedDB, localStorage: {length: 0, key: () => null, getItem: () => null}};
  const context = vm.createContext({
    window, indexedDB, setTimeout, clearTimeout, Promise, Uint8Array, ArrayBuffer, Object, Array, Map,
    String, Number, Math, Date, JSON, Error, atob, console, performance,
    structuredClone,
    navigator: {}, addEventListener() {},
  });
  window.window = window;
  vm.runInContext(bootstrap, context, {filename: "persistent-fs-bootstrap.js"});
  await window.__gaiusFsReady;
  return {window, reads: indexedDB.reads};
}

const shot = index => `/gaius/screenshots/2026-10-${String(index).padStart(2, "0")}_12.00.00.png`;
const png = (length, fill = 7) => new Uint8Array(length).fill(fill);
const base64Record = (path, length) => ({path, value: Buffer.from(png(length)).toString("base64"), updatedAt: 1});
const byteRecord = (path, length) => ({path, value: png(length), size: length, updatedAt: 1});
const screenshotKeys = records => [...records.keys()].filter(key => key.startsWith("/gaius/screenshots/")).sort();
const checks = [];

{
  // Startup: names only, nothing in memory, and only the newest four kept.
  const records = new Map([
    ["/gaius/options.txt", {path: "/gaius/options.txt", value: "b3B0aW9ucw==", updatedAt: 1}],
    ["/gaius/saves/World/level.dat", {path: "/gaius/saves/World/level.dat", value: "AAAA", updatedAt: 1}],
  ]);
  for (let index = 1; index <= 6; index++) records.set(shot(index), base64Record(shot(index), 800));
  const {window, reads} = await boot(records);
  const files = Object.keys(window.__gaiusPersistentFiles).sort();
  assert.deepEqual(files, ["/gaius/options.txt", "/gaius/saves/World/level.dat"], "no screenshot in memory");
  assert.deepEqual(reads.filter(key => key.startsWith("/gaius/screenshots/")), [], "startup read no screenshot");
  assert.deepEqual(screenshotKeys(records), [shot(3), shot(4), shot(5), shot(6)], "the newest four are kept");
  assert.deepEqual([...window.__gaiusScreenshots.list()], [shot(6), shot(5), shot(4), shot(3)]);
  const bytes = await window.__gaiusScreenshots.read(shot(5));
  assert.equal(bytes.length, 800);
  assert.equal(await window.__gaiusScreenshots.read(shot(1)), null);
  checks.push("startup lists names, keeps the newest four, reads none");

  // A new screenshot: bytes with their size, no memory copy, count and byte budgets.
  assert.equal(window.__gaiusFsPutScreenshot(shot(7), png(400)), true);
  assert.equal(window.__gaiusFsPutScreenshot("/gaius/options.txt", png(4)), false, "only screenshots");
  assert.equal(window.__gaiusFsPutScreenshot(shot(8), png(1001)), false, "over the per-file limit");
  await window.__gaiusFsFlush();
  assert.equal(window.__gaiusPersistentFiles[shot(7)], undefined, "no memory copy");
  const stored = records.get(shot(7));
  assert.ok(stored.value instanceof Uint8Array && stored.value.length === 400 && stored.size === 400);
  // Four kept, then 3 * 800 (legacy base64) + 400 > 2500: the oldest go until the total fits.
  assert.deepEqual(screenshotKeys(records), [shot(5), shot(6), shot(7)]);
  assert.deepEqual([...window.__gaiusScreenshots.list()], [shot(7), shot(6), shot(5)]);
  checks.push("a write stores bytes and keeps the count and byte budgets");

  const before = reads.length;
  assert.equal(window.__gaiusFsPutScreenshot(shot(9), png(300)), true);
  await window.__gaiusFsFlush();
  assert.equal(reads.length, before, "sizes are measured once");
  assert.deepEqual(screenshotKeys(records), [shot(5), shot(6), shot(7), shot(9)]);
  checks.push("legacy sizes are measured once");
}

{
  // Records this page wrote carry their size; each is measured once, at the first write.
  const records = new Map();
  for (let index = 1; index <= 3; index++) records.set(shot(index), byteRecord(shot(index), 900));
  const {window, reads} = await boot(records);
  assert.equal(reads.length, 0);
  assert.equal(window.__gaiusFsPutScreenshot(shot(4), png(900)), true);
  await window.__gaiusFsFlush();
  assert.deepEqual(screenshotKeys(records), [shot(3), shot(4)]);
  assert.deepEqual([...reads].sort(), [shot(1), shot(2), shot(3)]);
  checks.push("byte budget evicts the oldest screenshots");
}

for (const check of checks) console.log(`ok ${check}`);
console.log(`SCREENSHOT_STORAGE_SMOKE_OK ${checks.length} checks`);
