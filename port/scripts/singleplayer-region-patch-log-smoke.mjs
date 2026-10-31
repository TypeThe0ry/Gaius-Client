import {CANONICAL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
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

const bootstrapUrl = new URL("../web/singleplayer/server-worker-bootstrap.js", import.meta.url);
const channelUrl = new URL(
  "../src/main/java/org/teavm/classlib/java/nio/channels/TFileChannel.java",
  import.meta.url,
);
const persistenceUrl = new URL(
  "../overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java",
  import.meta.url,
);
const jsArraysUrl = new URL(
  "../overrides/classlib/src/main/java/dev/gaius/browser/JsArrays.java",
  import.meta.url,
);
const [bootstrap, channel, persistence, jsArrays] = await Promise.all([
  readFile(bootstrapUrl, "utf8"),
  readFile(channelUrl, "utf8"),
  readFile(persistenceUrl, "utf8"),
  readFile(jsArraysUrl, "utf8"),
]);

assert.match(channel, /MAX_DIRTY_RANGES = 64/,
  "FileChannel dirty ranges are no longer bounded");
assert.match(channel, /createAccessor\(read, write, write\)/,
  "writable FileChannel opens can truncate an existing region");
assert.match(channel, /BrowserFilePersistence\.supportsRangePersistence\(path\)/,
  "region persistence no longer selects the dirty-range path");
const rangeMethod = channel.slice(
  channel.indexOf("private void persistDirtyRanges()"),
  channel.indexOf("private void persistFullSnapshot()"),
);
assert.ok(rangeMethod.length > 0, "dirty-range persistence method is missing");
assert.doesNotMatch(rangeMethod, /new byte\[size\]/,
  "dirty-range persistence copied the complete region");
assert.match(rangeMethod, /new byte\[\(int\) payloadSize\]/,
  "dirty-range persistence does not size its copy from changed bytes");
assert.match(persistence, /public static boolean persistRanges\(/,
  "BrowserFilePersistence range bridge is missing");
assert.match(persistence, /__gaiusFsCanPatchBytes/,
  "range persistence cannot fall back when OPFS patches are unavailable");
// The JSBody takes typed arrays; JsArrays.int32 is the Java array itself (zero copy) in the
// JavaScript build and a copy in the WebAssembly GC build.
assert.match(persistence, /private static native boolean patchBytesJs\(\s*String path,\s*int logicalSize,\s*Int32Array offsets,\s*Int32Array lengths,\s*Int8Array payload\);/,
  "range offsets are not passed to JavaScript as typed arrays");
assert.match(persistence, /JsArrays\.int32\(offsets\)/,
  "range offsets are not passed to JavaScript through JsArrays");
assert.match(jsArrays, /public static Int32Array int32\(int\[\] array\) \{\s*return array == null \? null : Int32Array\.fromJavaArray\(array\);\s*\}/,
  "range offsets are not passed to JavaScript by reference in the JavaScript build");
assert.match(bootstrap, /!indexed \|\| indexed\.backend === "opfs"/,
  "range writes can bypass the full v1 migration of a non-OPFS region");
for (const contract of [
  "opfsPatchRecordVersion = 2",
  "maximumOpfsPatchRanges = 64",
  "maximumOpfsPatchChainRecords = 64",
  "opfsPatchChecksum",
  "opfsPatchCommitMagic",
  "checkpointOpfsRegion",
  "materializeOpfsRegion",
]) {
  assert.ok(bootstrap.includes(contract), `missing OPFS patch contract: ${contract}`);
}

class MemoryFile {
  constructor() {
    this.bytes = new Uint8Array(1024);
    this.size = 0;
    this.flushes = 0;
  }

  ensureCapacity(required) {
    if (required <= this.bytes.byteLength) return;
    let capacity = this.bytes.byteLength;
    while (capacity < required) capacity *= 2;
    const replacement = new Uint8Array(capacity);
    replacement.set(this.bytes.subarray(0, this.size));
    this.bytes = replacement;
  }
}

class FakeSyncAccessHandle {
  constructor(file) {
    this.file = file;
  }

  getSize() {
    return this.file.size;
  }

  read(output, options) {
    const at = Number(options?.at) || 0;
    const count = Math.max(0, Math.min(output.byteLength, this.file.size - at));
    output.set(this.file.bytes.subarray(at, at + count));
    return count;
  }

  write(input, options) {
    const at = Number(options?.at) || 0;
    this.file.ensureCapacity(at + input.byteLength);
    this.file.bytes.set(input, at);
    this.file.size = Math.max(this.file.size, at + input.byteLength);
    return input.byteLength;
  }

  truncate(size) {
    this.file.size = Math.max(0, Number(size) || 0);
  }

  flush() {
    this.file.flushes++;
  }

  close() {}
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
          return new FakeSyncAccessHandle(file);
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
          return cursorRequest();
        },
        openCursor() {
          return cursorRequest();
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
      function cursorRequest() {
        const request = {};
        const entries = Array.from(records.values());
        let index = 0;
        const advance = () => queueMicrotask(() => {
          const entry = entries[index++];
          request.result = entry
            ? {key: entry.path, value: entry, continue: advance}
            : null;
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

function createRuntime(opfsFiles, idbRecords) {
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
    Uint32Array,
    URL,
    atob,
    clearTimeout,
    console,
    indexedDB: createIndexedDb(idbRecords),
    location: new URL("https://client.example/singleplayer-server-worker.js"),
    navigator: {storage: {getDirectory: async () => createOpfs(opfsFiles)}},
    performance,
    postMessage(message) {
      events.push(message);
    },
    queueMicrotask,
    setTimeout,
    __gaiusStartIntegratedServerPump() {},
  };
  context.globalThis = context;
  context.close = () => {};
  context.importScripts = () => {};
  context.main = () => {};
  let runtimeStopped = false;
  context.stopIntegratedServer = () => { runtimeStopped = true; };
  context.isIntegratedServerStopped = () => runtimeStopped;
  vm.runInNewContext(bootstrap, context, {filename: "server-worker-bootstrap.js"});
  return {context, events};
}

async function startRuntime(runtime, worldId) {
  const port = {close() {}, postMessage() {}};
  await runtime.context.onmessage({
    data: {
      type: "start",
      sessionId: "8123456789abcdef0123456789abcdef",
      launchGeneration: "1",
      worldId,
      profileId: DEFAULT_STORAGE_CONFIG.profileId,
      worldVersion: DEFAULT_STORAGE_CONFIG.worldVersion,
      storageSchema: DEFAULT_STORAGE_CONFIG.storageSchema,
      storageDatabaseName: DEFAULT_STORAGE_CONFIG.storageDatabaseName,
      storagePrefix: DEFAULT_STORAGE_CONFIG.storagePrefix,
      storageOpfsDirectory: DEFAULT_STORAGE_CONFIG.storageOpfsDirectory,
      renderDistance: 6,
      simulationDistance: 4,
      serverScriptUrl: "https://client.example/singleplayer-server.js",
      port,
    },
    ports: [port],
  });
}

function applyRanges(target, logicalSize, offsets, lengths, payload) {
  const resized = new Uint8Array(logicalSize);
  resized.set(target.subarray(0, Math.min(target.byteLength, logicalSize)));
  let cursor = 0;
  for (let index = 0; index < offsets.length; index++) {
    resized.set(payload.subarray(cursor, cursor + lengths[index]), offsets[index]);
    cursor += lengths[index];
  }
  return resized;
}

function records(file) {
  const result = [];
  let offset = 0;
  while (offset + 8 <= file.size) {
    const view = new DataView(file.bytes.buffer, file.bytes.byteOffset + offset);
    const magic = view.getUint32(0, true);
    const version = view.getUint32(4, true);
    let length;
    if (magic === 0x47525331 && version === 1 && offset + 24 <= file.size) {
      length = view.getUint32(20, true);
      result.push({offset, version, length, payloadOffset: offset + 24 + view.getUint32(12, true)});
    } else if (magic === 0x47525332 && version === 2 && offset + 48 <= file.size) {
      length = view.getUint32(32, true);
      result.push({
        offset,
        version,
        length,
        payloadOffset: offset + 48 + view.getUint32(12, true) + view.getUint32(24, true),
        payloadLength: view.getUint32(28, true),
      });
    } else {
      break;
    }
    if (!length || offset + length > file.size) break;
    offset += length;
  }
  return result;
}

const worldId = "patch-log-world";
const path = `/gaius/saves/${worldId}/region/r.0.0.mca`;
const opfsFiles = new Map();
const idbRecords = new Map();
const first = createRuntime(opfsFiles, idbRecords);
await startRuntime(first, worldId);
assert.equal(first.context.__gaiusFsBackend, "opfs-sync-worker");
assert.equal(first.context.__gaiusFsCanPatchBytes(path), true);

let expected = new Uint8Array(1024 * 1024);
for (let index = 0; index < expected.length; index += 4096) {
  expected[index] = (index / 4096) & 0xff;
}
assert.equal(first.context.__gaiusFsPutBytes(path, expected), true,
  "legacy v1 baseline write failed");
const container = Array.from(opfsFiles.values())[0];
const baselineSize = container.size;

const offsets = Int32Array.of(0, 4096, expected.length - 4);
const lengths = Int32Array.of(4, 3, 4);
const payload = Uint8Array.of(9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 255);
const flushesBeforeMultiRange = container.flushes;
assert.equal(first.context.__gaiusFsPatchBytes(
  path, expected.length, offsets, lengths, payload,
), true, "multi-range v2 patch failed");
assert.equal(container.flushes, flushesBeforeMultiRange + 1,
  "one multi-range transaction performed more than one flush");
expected = applyRanges(expected, expected.length, offsets, lengths, payload);
assert.deepEqual(first.context.__gaiusPersistentFiles[path], expected,
  "multi-range reconstruction changed bytes");

assert.equal(first.context.__gaiusFsPatchBytes(
  path, 8192, new Int32Array(0), new Int32Array(0), new Uint8Array(0),
), true, "truncate-only patch failed");
expected = expected.slice(0, 8192);
assert.deepEqual(first.context.__gaiusPersistentFiles[path], expected,
  "truncate-only reconstruction failed");

const growthOffset = 12000;
const growthPayload = Uint8Array.of(0xaa, 0xbb, 0xcc, 0xdd);
assert.equal(first.context.__gaiusFsPatchBytes(
  path, 16384, Int32Array.of(growthOffset), Int32Array.of(4), growthPayload,
), true, "growth patch failed");
expected = applyRanges(expected, 16384, [growthOffset], [4], growthPayload);
assert.deepEqual(first.context.__gaiusPersistentFiles[path], expected,
  "truncate followed by growth resurrected discarded bytes");
assert.ok(expected.subarray(8192, growthOffset).every((value) => value === 0),
  "growth gap was not zero-filled");

const statsAfterGrowth = first.context.__gaiusFsStorageSnapshot();
assert.equal(statsAfterGrowth.opfsPatchWrites, 3);
assert.equal(statsAfterGrowth.opfsPatchRanges, 4);
assert.equal(statsAfterGrowth.opfsPatchPayloadBytes, payload.length + growthPayload.length);

const committedRecords = records(container);
const corrupted = committedRecords.at(-1);
assert.equal(corrupted.version, 2);
assert.ok(corrupted.payloadLength > 0);
container.bytes[corrupted.payloadOffset] ^= 0x5a;
const sizeBeforeRecovery = container.size;

const recovered = createRuntime(opfsFiles, idbRecords);
await startRuntime(recovered, worldId);
const expectedAfterRecovery = expected.slice(0, 8192);
assert.deepEqual(recovered.context.__gaiusPersistentFiles[path], expectedAfterRecovery,
  "CRC failure did not roll back to the preceding committed generation");
assert.equal(container.size, corrupted.offset,
  "corrupted v2 transaction tail was not truncated");
assert.equal(
  recovered.context.__gaiusFsStorageSnapshot().recoveredTailBytes,
  sizeBeforeRecovery - corrupted.offset,
);

expected = expectedAfterRecovery;
for (let index = 0; index < 70; index++) {
  const value = Uint8Array.of((index + 17) & 0xff);
  const at = 128 + index;
  assert.equal(recovered.context.__gaiusFsPatchBytes(
    path, expected.length, Int32Array.of(at), Int32Array.of(1), value,
  ), true, `bounded patch ${index} failed`);
  expected[at] = value[0];
}
assert.ok(recovered.context.__gaiusFsStorageSnapshot().opfsPatchCheckpoints >= 1,
  "patch chain did not checkpoint at its bounded record limit");
assert.deepEqual(recovered.context.__gaiusPersistentFiles[path], expected,
  "checkpointed patch chain reconstructed incorrectly");
const checkpointRestart = createRuntime(opfsFiles, idbRecords);
await startRuntime(checkpointRestart, worldId);
assert.deepEqual(checkpointRestart.context.__gaiusPersistentFiles[path], expected,
  "checkpointed v1/v2 chain did not survive a Worker restart");

const largePath = `/gaius/saves/${worldId}/region/r.1.0.mca`;
const large = new Uint8Array(1024 * 1024);
assert.equal(recovered.context.__gaiusFsPutBytes(largePath, large), true);
const largeBaselineSize = container.size;
const patchBytesBefore = recovered.context.__gaiusFsStorageSnapshot().opfsPatchPayloadBytes;
for (let index = 0; index < 10; index++) {
  assert.equal(recovered.context.__gaiusFsPatchBytes(
    largePath,
    large.length,
    Int32Array.of(8192 + index * 16),
    Int32Array.of(4),
    Uint8Array.of(index, index + 1, index + 2, index + 3),
  ), true);
}
const finalStats = recovered.context.__gaiusFsStorageSnapshot();
assert.equal(finalStats.opfsPatchPayloadBytes - patchBytesBefore, 40,
  "small updates wrote more than their dirty payload");
assert.ok(container.size - largeBaselineSize < 10 * 256,
  "small updates appended complete region snapshots");

console.log(JSON.stringify({
  ok: true,
  protocol: "v2-generation-journal",
  baselineBytes: baselineSize,
  patchWrites: finalStats.opfsPatchWrites,
  patchPayloadBytes: finalStats.opfsPatchPayloadBytes,
  checkpoints: finalStats.opfsPatchCheckpoints,
  recoveredCorruptTailBytes: sizeBeforeRecovery - corrupted.offset,
  finalContainerBytes: container.size,
}));
