#!/usr/bin/env node
// Round trip of port/web/launcher/world-archive.js: a browser world (IndexedDB records plus an
// OPFS region container) is exported to a Java Edition save .zip and imported back as a new world;
// archives from other zip writers, archives without a top folder, path traversal and archives with
// two worlds are checked too. Runs the page script in node:vm with in-memory IndexedDB and OPFS.
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {readFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import vm from "node:vm";
import {gzipSync} from "node:zlib";

const source = await readFile(new URL("../web/launcher/world-archive.js", import.meta.url), "utf8");

// --- in-memory IndexedDB (records keyed by path) -----------------------------------------------
function createIndexedDb(records) {
  function transaction() {
    const tx = {oncomplete: null, onerror: null, onabort: null, pending: 0, error: null};
    const settle = () => setTimeout(() => { if (tx.pending === 0 && tx.oncomplete) tx.oncomplete(); }, 0);
    tx.objectStore = () => ({
      put(record) { records.set(String(record.path), structuredClone(record)); settle(); },
      delete(path) { records.delete(String(path)); settle(); },
      get(path) {
        const request = {result: undefined, onsuccess: null};
        tx.pending++;
        setTimeout(() => {
          const record = records.get(String(path));
          request.result = record ? structuredClone(record) : undefined;
          tx.pending--;
          if (request.onsuccess) request.onsuccess();
          settle();
        }, 0);
        return request;
      },
      openKeyCursor(range) {
        const keys = [...records.keys()].sort().filter(key => !range || (key >= range.lower && key <= range.upper));
        const request = {result: null, onsuccess: null, onerror: null};
        let index = 0;
        tx.pending++;
        const step = () => setTimeout(() => {
          if (index >= keys.length) {
            request.result = null;
            tx.pending--;
            request.onsuccess();
            settle();
            return;
          }
          request.result = {primaryKey: keys[index++], continue: step};
          request.onsuccess();
        }, 0);
        step();
        return request;
      }
    });
    settle();
    return tx;
  }
  return {
    open() {
      const request = {result: null, onsuccess: null, onerror: null, onupgradeneeded: null};
      setTimeout(() => {
        request.result = {
          objectStoreNames: {contains: () => true},
          transaction,
          close() {}
        };
        request.onsuccess();
      }, 0);
      return request;
    }
  };
}

// --- in-memory OPFS ----------------------------------------------------------------------------
function createOpfs(files) {
  const directory = (path) => ({
    async getDirectoryHandle(name) { return directory(path + name + "/"); },
    async getFileHandle(name) {
      const key = path + name;
      if (!files.has(key)) { const error = new Error("missing"); error.name = "NotFoundError"; throw error; }
      return {async getFile() { return new File([files.get(key)], name); }};
    }
  });
  return {storage: {getDirectory: async () => directory("")}};
}

function createWindow({records, opfsFiles, downloads}) {
  const window = {
    __gaiusStorageDatabaseName: "gaius-fs-v2-26.3",
    __gaiusStorageOpfsDirectory: "regions-v2-26.3",
    __gaiusStorageSchema: 2,
    __gaiusPersistentFiles: Object.create(null),
    __gaiusFsFlush: async () => {},
    indexedDB: createIndexedDb(records),
    IDBKeyRange: {bound: (lower, upper) => ({lower, upper})},
    navigator: createOpfs(opfsFiles),
    document: {
      body: {appendChild() {}},
      createElement() {
        return {style: {}, remove() {}, click() { downloads.push({href: this.href, download: this.download}); }};
      }
    },
    URL: {createObjectURL: blob => { downloads.blob = blob; return "blob:test"; }, revokeObjectURL() {}},
    setTimeout, Blob, File, Response, TextEncoder, TextDecoder, DataView, Uint8Array,
    Uint32Array, ArrayBuffer, CompressionStream, DecompressionStream, Promise, Map, Set,
    Array, Object, String, Number, Math, Error, structuredClone, atob, Date, BigInt
  };
  window.window = window;
  window.globalThis = window;
  vm.createContext(window);
  vm.runInContext(source, window, {filename: "world-archive.js"});
  return window;
}

const encoder = new TextEncoder();
const bytes = (text) => encoder.encode(text);
const random = (length, seed) => {
  const out = new Uint8Array(length);
  let state = seed >>> 0;
  for (let index = 0; index < length; index++) {
    state = (Math.imul(state, 1103515245) + 12345) >>> 0;
    out[index] = state >>> 24;
  }
  return out;
};

// --- OPFS container records (server-worker-bootstrap.js appendOpfsRegion/appendOpfsPatch) -----
function crc32(chunks) {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) { let v = i; for (let b = 0; b < 8; b++) v = (v >>> 1) ^ ((v & 1) ? 0xedb88320 : 0); table[i] = v >>> 0; }
  let crc = 0xffffffff;
  for (const chunk of chunks) for (const byte of chunk) crc = (crc >>> 8) ^ table[(crc ^ byte) & 0xff];
  return (crc ^ 0xffffffff) >>> 0;
}
function v2Record(path, logicalSize, ranges, payload, generation) {
  const pathBytes = bytes(path);
  const metadata = new Uint8Array(ranges.length * 8);
  const mv = new DataView(metadata.buffer);
  ranges.forEach(([offset, length], index) => { mv.setUint32(index * 8, offset, true); mv.setUint32(index * 8 + 4, length, true); });
  const scalar = new Uint8Array(8);
  new DataView(scalar.buffer).setUint32(0, logicalSize, true);
  new DataView(scalar.buffer).setUint32(4, generation, true);
  const length = 48 + pathBytes.length + metadata.length + payload.length;
  const record = new Uint8Array(length);
  const view = new DataView(record.buffer);
  view.setUint32(0, 0x47525332, true); view.setUint32(4, 2, true); view.setUint32(8, 3, true);
  view.setUint32(12, pathBytes.length, true); view.setUint32(16, logicalSize, true);
  view.setUint32(20, ranges.length, true); view.setUint32(24, metadata.length, true);
  view.setUint32(28, payload.length, true); view.setUint32(32, length, true);
  view.setUint32(36, generation, true);
  view.setUint32(40, crc32([pathBytes, scalar, metadata, payload]), true);
  view.setUint32(44, 0x434f4d54, true);
  record.set(pathBytes, 48); record.set(metadata, 48 + pathBytes.length);
  record.set(payload, 48 + pathBytes.length + metadata.length);
  return record;
}
function v1Record(path, value, deleted) {
  const pathBytes = bytes(path);
  const length = 24 + pathBytes.length + value.length;
  const record = new Uint8Array(length);
  const view = new DataView(record.buffer);
  view.setUint32(0, 0x47525331, true); view.setUint32(4, 1, true); view.setUint32(8, deleted ? 2 : 1, true);
  view.setUint32(12, pathBytes.length, true); view.setUint32(16, value.length, true); view.setUint32(20, length, true);
  record.set(pathBytes, 24); record.set(value, 24 + pathBytes.length);
  return record;
}
const concat = (parts) => {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) { out.set(part, offset); offset += part.length; }
  return out;
};

const worldId = "My World";
const prefix = "/gaius/saves/" + worldId + "/";
const region0 = random(8192, 1);
const patch = random(100, 2);
const region0Expected = region0.slice(); region0Expected.set(patch, 4096);
const region1 = random(12288, 3);
const deletedRegion = random(4096, 4);
const regionStale = random(8192, 5);
const levelDat = gzipSync(Buffer.from("level data")).subarray();
const icon = random(300, 6);
const player = random(500, 7);

const records = new Map();
const put = (path, value) => records.set(path, {path, value, updatedAt: 1});
put(prefix + "level.dat", levelDat);
put(prefix + "level.dat_old", levelDat);
put(prefix + "icon.png", Buffer.from(icon).toString("base64"));
put(prefix + "playerdata/0123.dat", player);
put(prefix + "data/minecraft/world_gen_settings.dat", bytes("wgs"));
put(prefix + "region/r.0.0.mca", regionStale);             // migrated earlier; OPFS wins
put(prefix + "entities/r.2.2.mca", {encoding: "gzip", bytes: gzipSync(Buffer.from(region1)).subarray()});
put(prefix + "session.lock", new Uint8Array(0));
put(prefix + ".gaius-backups/1/r.0.0.mca.bak", random(10, 8));
put("/gaius/saves/Other/level.dat", levelDat);
put("/gaius/options.txt", "dmVyc2lvbjox");

const containerName = (() => {
  let hash = 0x811c9dc5;
  for (const byte of bytes(worldId)) { hash ^= byte; hash = Math.imul(hash, 0x01000193); }
  return worldId.replace(/[^A-Za-z0-9._-]/g, "_") + "-" + (hash >>> 0).toString(16).padStart(8, "0") + ".regions";
})();
const opfsFiles = new Map();
opfsFiles.set("gaius/regions-v2-26.3/" + containerName, concat([
  v1Record("/gaius/storage/region-pair-seq/0,0", bytes("1"), false),
  v2Record(prefix + "region/r.0.0.mca", region0.length, [[0, region0.length]], region0, 1),
  v2Record(prefix + "region/r.0.0.mca", region0.length, [[4096, patch.length]], patch, 2),
  v2Record(prefix + "region/r.1.0.mca", region1.length, [[0, region1.length]], region1, 1),
  v1Record(prefix + "region/r.9.9.mca", deletedRegion, false),
  v1Record(prefix + "region/r.9.9.mca", new Uint8Array(0), true),
  new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9]),               // torn tail
]));

const downloads = [];
const window = createWindow({records, opfsFiles, downloads});
const archive = window.__gaiusWorldArchive;
const waitFor = async (predicate) => {
  for (let i = 0; i < 2000 && !predicate(); i++) await new Promise(resolve => setTimeout(resolve, 2));
  assert.ok(predicate(), "timed out: " + JSON.stringify(archive.status));
};

// Export.
assert.equal(archive.startExport(worldId, "My World"), true);
await waitFor(() => archive.status.state !== "running");
assert.equal(archive.status.state, "done", archive.status.error);
assert.equal(downloads.length, 1);
assert.equal(downloads[0].download, "My World.zip");
const exported = new Uint8Array(await downloads.blob.arrayBuffer());
const exportedFile = new File([exported], "My World.zip");
const internals = archive.internals;
const entries = await internals.readZipDirectory(exportedFile);
const names = Array.from(entries, entry => entry.name).sort();
assert.deepEqual(names, [
  "My World/data/minecraft/world_gen_settings.dat",
  "My World/entities/r.2.2.mca",
  "My World/icon.png",
  "My World/level.dat",
  "My World/level.dat_old",
  "My World/playerdata/0123.dat",
  "My World/region/r.0.0.mca",
  "My World/region/r.1.0.mca",
]);
const entryBytes = async (name) => internals.readZipEntry(exportedFile, entries.find(entry => entry.name === name));
assert.deepEqual(await entryBytes("My World/region/r.0.0.mca"), region0Expected);
assert.deepEqual(await entryBytes("My World/region/r.1.0.mca"), region1);
assert.deepEqual(await entryBytes("My World/entities/r.2.2.mca"), region1);
assert.deepEqual(await entryBytes("My World/icon.png"), icon);

// The archive is a standard zip: Python's zipfile reads it and agrees on every CRC.
const scratch = mkdtempSync(join(tmpdir(), "gaius-world-archive-"));
let python = null;
for (const candidate of ["python3", "python"]) {
  try { execFileSync(candidate, ["--version"], {stdio: "ignore"}); python = candidate; break; } catch (_) {}
}
try {
  if (python) {
    writeFileSync(join(scratch, "export.zip"), exported);
    const tested = execFileSync(python, ["-I", "-c",
      "import sys,zipfile;z=zipfile.ZipFile(sys.argv[1]);print(z.testzip());print(len(z.namelist()))",
      join(scratch, "export.zip")], {encoding: "utf8"}).trim().split(/\r?\n/);
    assert.deepEqual(tested, ["None", "8"]);
  }

  // Import it back: the name is taken, so it becomes "My World (1)".
  assert.equal(await archive.importFile(exportedFile), true);
  assert.equal(archive.status.state, "done", archive.status.error);
  assert.equal(archive.status.worldId, "My World (1)");
  const imported = "/gaius/saves/My World (1)/";
  for (const name of names) {
    const relative = name.slice("My World/".length);
    const record = records.get(imported + relative);
    assert.ok(record, "missing " + relative);
    assert.deepEqual(new Uint8Array(record.value), await entryBytes(name), relative);
  }
  assert.ok(!records.has(imported + "session.lock"));
  assert.deepEqual(new Uint8Array(window.__gaiusPersistentFiles[imported + "level.dat"]), new Uint8Array(levelDat));
  assert.ok(window.__gaiusPersistentFiles[imported + "icon.png"]);
  assert.ok(!window.__gaiusPersistentFiles[imported + "region/r.0.0.mca"]);

  // A zip from another writer (Python, deflate, directory entries, no top folder).
  if (python) {
    const script = [
      "import sys,zipfile",
      "z=zipfile.ZipFile(sys.argv[1],'w',zipfile.ZIP_DEFLATED)",
      "z.writestr('region/','')",
      "z.writestr('level.dat',b'L'*1000)",
      "z.writestr('region/r.0.0.mca',bytes(range(256))*64)",
      "z.writestr('session.lock',b'')",
      "z.close()",
      "t=zipfile.ZipFile(sys.argv[2],'w')",
      "t.writestr('w/level.dat',b'x')",
      "t.writestr('../evil.txt',b'x')",
      "t.close()",
      "d=zipfile.ZipFile(sys.argv[3],'w')",
      "d.writestr('a/level.dat',b'x')",
      "d.writestr('b/level.dat',b'x')",
      "d.close()",
    ].join("\n");
    execFileSync(python, ["-I", "-c", script, join(scratch, "flat.zip"), join(scratch, "evil.zip"),
      join(scratch, "two.zip")]);
    const flat = new File([readFileSync(join(scratch, "flat.zip"))], "Survival Base.zip");
    assert.equal(await archive.importFile(flat), true);
    assert.equal(archive.status.state, "done", archive.status.error);
    assert.equal(archive.status.worldId, "Survival Base");
    assert.equal(records.get("/gaius/saves/Survival Base/region/r.0.0.mca").value.length, 256 * 64);
    assert.ok(!records.has("/gaius/saves/Survival Base/session.lock"));

    const before = records.size;
    await archive.importFile(new File([readFileSync(join(scratch, "evil.zip"))], "evil.zip"));
    assert.equal(archive.status.state, "error");
    assert.match(archive.status.error, /unsafe path/);
    await archive.importFile(new File([readFileSync(join(scratch, "two.zip"))], "two.zip"));
    assert.equal(archive.status.state, "error");
    assert.match(archive.status.error, /more than one world/);
    assert.equal(records.size, before, "a refused archive wrote records");
  }

  // Not a zip at all.
  await archive.importFile(new File([bytes("hello")], "notes.zip"));
  assert.equal(archive.status.state, "error");
  assert.match(archive.status.error, /not a zip/);

  // Path rules.
  assert.equal(internals.isTraversal("a/../../b"), true);
  assert.equal(internals.isTraversal("/etc/passwd"), true);
  assert.equal(internals.isTraversal("C:\\Windows\\x"), true);
  assert.equal(internals.isTraversal("world/region/r.0.0.mca"), false);
  assert.equal(internals.sanitizeWorldId("a/b:c*?.zip"), "a_b_c__");
  assert.equal(internals.uniqueWorldId("World", new Set(["world", "World (1)"])), "World (2)");
} finally {
  rmSync(scratch, {recursive: true, force: true});
}
console.log("world-archive-smoke: ok" + (python ? "" : " (python zip interop skipped)"));
