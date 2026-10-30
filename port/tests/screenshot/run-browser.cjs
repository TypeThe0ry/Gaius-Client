"use strict";
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const zlib = require("node:zlib");
const [target, directory, profileFile, mode] = process.argv.slice(2);
const large = mode === "large" || mode === "restore-large";
const screenshotPath = large ? "/gaius/screenshots/large.png" : "/gaius/screenshots/regression.png";
const savedName = large ? "browser-large.png" : "browser-persisted.png";
const profile = JSON.parse(fs.readFileSync(profileFile, "utf8"));
globalThis.__gaiusProfileId = profile.id;
globalThis.__gaiusWorldVersion = profile.worldVersion;
globalThis.__gaiusStorageSchema = profile.storage.schema;
globalThis.__gaiusStorageDatabaseName = profile.storage.databaseName;
globalThis.__gaiusStoragePrefix = profile.storage.prefix;
globalThis.__gaiusStorageOpfsDirectory = profile.storage.opfsDirectory;
globalThis.__gaiusPersistentFiles = Object.create(null);
if (mode === "restore" || mode === "restore-large") {
  globalThis.__gaiusPersistentFiles[screenshotPath] = fs.readFileSync(
    path.join(directory, savedName)).toString("base64");
}
globalThis.__gaiusFsPut = (name, value) => {
  if (mode === "quota" && name === "/gaius/screenshots/rejected.png") return false;
  globalThis.__gaiusPersistentFiles[name] = value;
  return true;
};
function verify() {
  if (mode === "quota") {
    assert.equal(globalThis.__gaiusPersistentFiles["/gaius/screenshots/rejected.png"], undefined);
    console.log(`BROWSER_MIRROR_QUOTA PASS target=${target}`);
    return;
  }
  const width = large ? 1000 : 2;
  const height = large ? 790 : 1;
  const bytes = Buffer.from(globalThis.__gaiusPersistentFiles[screenshotPath], "base64");
  assert.equal(bytes.readBigUInt64BE(), 0x89504e470d0a1a0an);
  assert.equal(bytes.readInt32BE(16), width);
  assert.equal(bytes.readInt32BE(20), height);
  const compressed = [];
  for (let offset = 8; offset < bytes.length;) {
    const count = bytes.readUInt32BE(offset);
    if (bytes.toString("ascii", offset + 4, offset + 8) === "IDAT") {
      compressed.push(bytes.subarray(offset + 8, offset + 8 + count));
    }
    offset += count + 12;
  }
  let expected = Buffer.from([0, 255, 0, 0, 255, 0, 255, 0, 128]);
  if (large) {
    expected = Buffer.alloc((width * 4 + 1) * height);
    let state = 123456789;
    for (let row = 0; row < height; row++) {
      for (let column = 0; column < width * 4; column++) {
        state = (Math.imul(state, 1664525) + 1013904223) | 0;
        expected[row * (width * 4 + 1) + column + 1] = state >>> 24;
      }
    }
  }
  assert.deepEqual(zlib.inflateSync(Buffer.concat(compressed)), expected);
  fs.writeFileSync(path.join(directory, savedName), bytes);
  console.log(`BROWSER_MIRROR_REGRESSION PASS target=${target} bytes=${bytes.length}`);
}
(async () => {
  const args = mode ? [mode] : [];
  if (target === "js") require(path.join(directory, "classes.js")).main(args);
  else {
    require(path.join(directory, "gaius-wasm-gc-runtime.js"));
    console.log("BROWSER_SAVE_MODULE_LOADING");
    const teavm = await globalThis.TeaVM.wasmGC.load(path.join(directory, "classes.wasm"), {nodejs: true});
    console.log("BROWSER_SAVE_MODULE_LOADED");
    teavm.exports.main(args);
  }
  verify();
  setTimeout(() => process.exit(0), 20);
})().catch(error => { console.error(error); setTimeout(() => process.exit(1), 20); });
