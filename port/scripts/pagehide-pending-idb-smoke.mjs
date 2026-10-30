#!/usr/bin/env node
import assert from "node:assert/strict";
import {readdirSync, readFileSync} from "node:fs";
import {mkdtemp, rm, writeFile} from "node:fs/promises";
import {join} from "node:path";
import {execFile} from "node:child_process";
import {promisify} from "node:util";
import vm from "node:vm";

const template = readFileSync(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");
const bootstrapStart = template.indexOf("    (function installPersistentFsBootstrap() {");
const bootstrapEnd = template.indexOf("\n\n    function loadScript", bootstrapStart);
assert.ok(bootstrapStart >= 0 && bootstrapEnd > bootstrapStart);
const bootstrap = template.slice(bootstrapStart, bootstrapEnd);
const postprocess = readFileSync(new URL("./postprocess-index-html.py", import.meta.url), "utf8");
assert.match(postprocess, /lease_source = launcher_template\s*\[lease_start:lease_end\]/,
  "postprocess must synchronize the launcher lease from the tracked template");
const execFileAsync = promisify(execFile);
const generatedDir = await mkdtemp(join(process.env.TMPDIR || process.env.TMP || ".", "lease-index-"));
const generatedIndex = join(generatedDir, "index.html");
const generatedClasses = join(generatedDir, "classes.js");
await writeFile(generatedIndex, template, "utf8");
await writeFile(generatedClasses, "// generated launcher fixture\n", "utf8");
// Any shipped profile will do: the lease section does not depend on the profile.
const versionsDir = new URL("../versions/", import.meta.url);
const profileId = JSON.parse(readFileSync(new URL(
  readdirSync(versionsDir).filter((name) => name.endsWith(".json")).sort()[0], versionsDir), "utf8")).id;
await execFileAsync(process.env.PYTHON || "python", [
  "port/scripts/postprocess-index-html.py", generatedIndex, generatedClasses, profileId,
], {cwd: join(new URL("../..", import.meta.url).pathname.replace(/^\/+/, "").replaceAll("%20", " "))});
const generatedTemplate = readFileSync(generatedIndex, "utf8");

// IDB open, requests, and transaction commits run as later tasks. In particular, put() must
// not commit synchronously: the real launcher queues transaction creation in pending.then().
class OrderedDatabase {
  records = new Map();
  closed = false;
  closes = 0;
  active = 0;
  objectStoreNames = {contains: () => true};
  close() { this.closed = true; this.closes++; }
  transaction() {
    assert.equal(this.closed, false, "transaction attempted after IDB connection close");
    this.active++;
    const database = this;
    const operations = [];
    const transaction = {
      objectStore() {
        return {
          put(record) { operations.push(() => database.records.set(record.path, record)); },
          delete(path) { operations.push(() => database.records.delete(path)); },
          openKeyCursor() {
            // Bootstrap starts with an empty database; records are seeded after readiness.
            const request = {result: null};
            operations.push(() => request.onsuccess?.());
            return request;
          }
        };
      }
    };
    setTimeout(() => {
      for (const operation of operations) operation();
      database.active--;
      transaction.oncomplete?.();
    }, 5);
    return transaction;
  }
}

async function page(lifecycle) {
  const database = new OrderedDatabase();
  const listeners = new Map();
  const context = {
    console, Date, Math, Promise, TextEncoder, Uint8Array, ArrayBuffer,
    setTimeout, clearTimeout, setInterval, clearInterval,
    performance: {now: () => Date.now()},
    navigator: {}, localStorage: {length: 0},
    document: {addEventListener() {}, getElementById() { return null; }},
    indexedDB: {open() {
      const request = {result: database};
      setTimeout(() => request.onsuccess(), 0);
      return request;
    }},
    addEventListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(listener);
    }
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(bootstrap, context);
  await context.__gaiusFsReady;
  assert.equal(context.__gaiusFsBackend, "indexeddb");
  const start = lifecycle.indexOf("    const runtimeLeaseHandoffMs = 30000;");
  const end = lifecycle.indexOf("\n    async function acquireGaiusRuntimeLease", start);
  assert.ok(start >= 0 && end > start);
  vm.runInContext(lifecycle.slice(start, end) +
    "\nthis.installLifecycle = releaseGaiusRuntimeLeaseOnPagehide;", context);
  return {context, database, hide() {
    for (const listener of listeners.get("pagehide") || []) listener({persisted: false});
  }};
}

for (const [label, lifecycle] of [["template", template], ["postprocess generated index", generatedTemplate]]) {
  const {context, database, hide} = await page(lifecycle);
  const root = "/gaius/saves/queued/";
  database.records.set(root + "obsolete", {path: root + "obsolete", value: "old"});
  let releases = 0;
  let cancels = 0;
  context.installLifecycle(() => releases++, () => cancels++);
  assert.equal(context.__gaiusFsPut(root + "level.dat", "metadata"), true);
  assert.equal(context.__gaiusFsPutBytes(root + "icon.png", new Uint8Array([7, 8])), true);
  assert.equal(context.__gaiusFsDelete(root + "obsolete"), true);
  assert.equal(context.__gaiusFsPutScreenshot("/gaius/screenshots/queued.png", new Uint8Array([9])), true);
  // No microtask checkpoint occurs between enqueuing writes and pagehide.
  const flushed = context.__gaiusFsFlush();
  hide();
  hide();
  assert.equal(releases, 1, `${label}: synchronous lease release must remain idempotent`);
  assert.equal(cancels, 1);
  await assert.doesNotReject(flushed, `${label}: queued writes must survive pagehide`);
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(database.closes, 1, `${label}: pagehide must close IDB after queued writes drain`);
  assert.equal(database.active, 0, "flush must wait for asynchronous transaction commit");
  assert.equal(database.records.get(root + "level.dat").value, "metadata");
  assert.deepEqual([...database.records.get(root + "icon.png").value], [7, 8]);
  assert.equal(database.records.has(root + "obsolete"), false);
  assert.deepEqual([...database.records.get("/gaius/screenshots/queued.png").value], [9]);
  const upgrade = await page(lifecycle);
  upgrade.database.onversionchange();
  assert.equal(upgrade.database.closes, 1, "upgrades must still close the old connection");
  console.log(`ok ${label}: queued metadata, binary, delete and screenshot commit after pagehide; explicit close and upgrade close retained`);
}
await rm(generatedDir, {recursive: true, force: true});
console.log("PAGEHIDE_PENDING_IDB_SMOKE_OK");
