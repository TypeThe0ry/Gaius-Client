"use strict";

const {readFileSync, writeFileSync} = require("node:fs");
const configuration = JSON.parse(readFileSync(process.env.GAIUS_STORAGE_PROBE_CONFIG, "utf8"));
const profile = configuration.profile;
const files = Object.create(null);
for (const [name, bytes] of Object.entries(configuration.files || {})) {
  files[`/gaius/saves/storage-parity/region/${name}`] = Uint8Array.from(Buffer.from(bytes, "base64"));
}
Object.assign(globalThis, {
  __gaiusProfileId: profile.id,
  __gaiusWorldVersion: profile.worldVersion,
  __gaiusStorageSchema: profile.storage.schema,
  __gaiusStorageDatabaseName: profile.storage.databaseName,
  __gaiusStoragePrefix: profile.storage.prefix,
  __gaiusStorageOpfsDirectory: profile.storage.opfsDirectory,
  __gaiusServerWorldId: "storage-parity",
  __gaiusPersistentFiles: files,
  __gaiusFsBackend: "storage-parity-fixture",
});
let snapshots = 0;
let patches = 0;
globalThis.__gaiusFsPut = (path, value) => {
  files[path] = value;
  return true;
};
globalThis.__gaiusFsPutBytes = (path, value) => {
  files[path] = Uint8Array.from(new Uint8Array(value.buffer, value.byteOffset, value.byteLength));
  snapshots++;
  return true;
};
globalThis.__gaiusFsCanPatchBytes = () => true;
globalThis.__gaiusFsPatchBytes = (path, logicalSize, offsets, lengths, payload) => {
  if (!Number.isSafeInteger(logicalSize) || logicalSize < 0 || offsets.length !== lengths.length) {
    throw new Error("invalid range persistence framing");
  }
  const previous = files[path];
  const source = typeof previous === "string" ? Buffer.from(previous, "base64") : previous;
  const bytes = new Uint8Array(logicalSize);
  if (source) bytes.set(source.subarray(0, logicalSize));
  let consumed = 0;
  for (let index = 0; index < offsets.length; index++) {
    const offset = offsets[index];
    const length = lengths[index];
    if (offset < 0 || length < 0 || offset + length > logicalSize || consumed + length > payload.length) {
      throw new Error("range persistence exceeds its declared length");
    }
    bytes.set(payload.subarray(consumed, consumed + length), offset);
    consumed += length;
  }
  if (consumed !== payload.length) throw new Error("unconsumed range persistence payload");
  files[path] = bytes;
  patches++;
  return true;
};
globalThis.__gaiusFsDelete = path => { delete files[path]; };
process.on("exit", () => {
  const regions = Object.fromEntries(Object.entries(files).filter(([path]) => path.endsWith(".mca"))
    .map(([path, bytes]) => [path.split("/").at(-1),
      typeof bytes === "string" ? bytes : Buffer.from(bytes).toString("base64")]));
  writeFileSync(configuration.receipt, JSON.stringify({snapshots, patches, regions}, null, 2) + "\n");
});
