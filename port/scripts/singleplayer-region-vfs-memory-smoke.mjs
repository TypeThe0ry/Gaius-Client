import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, readFile} from "node:fs/promises";
import {existsSync} from "node:fs";
import {fileURLToPath} from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const persistencePath = path.join(root,
  "port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java");
const channelPath = path.join(root,
  "port/src/main/java/org/teavm/classlib/java/nio/channels/TFileChannel.java");
const inputStreamPath = path.join(root,
  "port/overrides/classlib/src/main/java/org/teavm/classlib/java/io/TFileInputStream.java");
const outputStreamPath = path.join(root,
  "port/overrides/classlib/src/main/java/org/teavm/classlib/java/io/TFileOutputStream.java");
const corePatcherPath = path.join(root,
  "port/tools/src/main/java/dev/gaius/tools/TeaVMCoreBrowserPatcher.java");
const classlibPatcherPath = path.join(root,
  "port/tools/src/main/java/dev/gaius/tools/TeaVMClasslibPatcher.java");
const bootstrapPath = path.join(root,
  "port/web/singleplayer/server-worker-bootstrap.js");
const pomGeneratorPath = path.join(root, "port/scripts/generate-pom.sh");
const [persistence, channel, inputStream, outputStream, corePatcher,
  classlibPatcher, bootstrap, pomGenerator, configText] = await Promise.all([
  readFile(persistencePath, "utf8"),
  readFile(channelPath, "utf8"),
  readFile(inputStreamPath, "utf8"),
  readFile(outputStreamPath, "utf8"),
  readFile(corePatcherPath, "utf8"),
  readFile(classlibPatcherPath, "utf8"),
  readFile(bootstrapPath, "utf8"),
  readFile(pomGeneratorPath, "utf8"),
  readFile(path.join(root, "port/config.json"), "utf8"),
]);

assert.match(persistence,
  /startsWith\("\/gaius\/saves\/" \+ activeWorld \+ "\/"\)\s*&& !isOnDemandChunkStoragePath\(normalized\)/,
  "active Worker mount must exclude .mca and .mcc payloads");
assert.match(persistence, /public static boolean restoreOnDemand\(String path\)/,
  "metadata-only lazy placeholder entrypoint is missing");
assert.match(persistence, /writeVirtualFile\(normalized, new byte\[0\]\)/,
  "existence checks do not use payload-free VFS placeholders");
assert.match(persistence, /public static void materializeForOpen\(String path\)/,
  "stream/channel opens do not force payload restoration");
assert.match(persistence, /INTERNAL_VIRTUAL_FILE_PATHS\.add\(normalized\)/,
  "on-demand restoration has no recursion guard");
assert.match(persistence, /finally \{\s*INTERNAL_VIRTUAL_FILE_PATHS\.remove\(normalized\)/,
  "on-demand restoration does not release its recursion guard");
assert.match(persistence, /OPEN_MATERIALIZED_CHUNK_FILES/,
  "materialized chunk-file reference accounting is missing");
assert.match(persistence, /endsWith\("\.mca"\) \|\| normalized\.endsWith\("\.mcc"\)/,
  ".mcc files are not classified as lazy chunk storage");
assert.match(persistence, /fileName\.startsWith\("tmp"\) && fileName\.endsWith\("\.tmp"\)/,
  "RegionFile temporary payloads are not persisted as binary");
assert.match(persistence, /file\.delete\(\)/,
  "last-close VFS reclamation is missing");
assert.match(persistence,
  /boolean stored = syncFile\(target\);[\s\S]*?delete\(source\);[\s\S]*?reclaimMaterializedChunkFileIfUnreferenced/,
  "temp moves do not persist the target before deleting/reclaiming the source");
assert.match(channel, /materializeForOpen\(absolutePath\)/,
  "file channels can open metadata placeholders");
assert.match(channel, /retainMaterializedChunkFile\(absolutePath\)/,
  "chunk file channels do not pin materialized files");
assert.match(channel,
  /catch \(IOException \| RuntimeException \| Error failure\)[\s\S]*?accessor\.close\(\)/,
  "file-channel construction can leak an accessor on failure");
assert.match(inputStream, /materializeForOpen\(absolutePath\)/,
  "direct input streams do not force .mcc hydration");
assert.match(inputStream,
  /finally \{[\s\S]*?releaseMaterializedChunkFile\(path, true\)/,
  "input stream close does not release materialized .mcc payloads");
assert.match(inputStream,
  /catch \(RuntimeException \| Error failure\)[\s\S]*?opened\.close\(\)/,
  "input stream construction can leak an accessor");
assert.match(inputStream, /return read > 0 \? buffer\[0\] & 0xFF : -1;/,
  "single-byte input does not preserve unsigned byte and EOF semantics");
assert.match(inputStream,
  /if \(n <= 0\) \{\s*return 0;[\s\S]*?Math\.min\(n, Math\.max\(0, accessor\.size\(\) - position\)\)/,
  "input skip is not bounded to the remaining file size");
assert.match(outputStream, /materializeForOpen\(absolutePath\)/,
  "direct output streams do not force .mcc hydration");
assert.match(outputStream,
  /releaseMaterializedChunkFile\([\s\S]*?failure == null && !dirty/,
  "output stream close can reclaim an unpersisted payload");
assert.match(classlibPatcher,
  /newInputStream[\s\S]*?insertMaterializeForOpen[\s\S]*?TFileInputStream/,
  "Files.newInputStream is not patched for lazy .mcc payloads");
assert.match(classlibPatcher,
  /newOutputStream[\s\S]*?insertMaterializeForOpen[\s\S]*?TFileOutputStream/,
  "Files.newOutputStream is not patched for lazy .mcc payloads");
assert.match(bootstrap,
  /path\.endsWith\("\.mca"\) \|\| path\.endsWith\("\.mcc"\)/,
  "Worker OPFS indexing excludes .mcc files");
assert.match(bootstrap,
  /appendOpfsRegion\(path, bytes, false\);\s*\/\/[^\n]*\n\s*flushOpfsSync\(\)/,
  "OPFS writes can report success before becoming durable");
assert.match(corePatcher,
  /"dev\/gaius\/browser\/BrowserFilePersistence",\s*"restoreOnDemand"/,
  "TeaVM getFile hook is missing");
assert.match(pomGenerator,
  /<artifactId>teavm-core<\/artifactId>[\s\S]*?<scope>system<\/scope>[\s\S]*?<systemPath>\$maven_teavm_core_patch<\/systemPath>/,
  "generated applications do not consume the patched TeaVM runtime");

const regionFixtureCount = 1024;
const externalFixtureCount = 4096;
const regionFixtureBytes = 32 * 1024;
const externalFixtureBytes = 96 * 1024;
let backingBytesRead = 0;
const backing = Object.create(null);
for (let index = 0; index < regionFixtureCount; index++) {
  backing[`/gaius/saves/world/region/r.${index}.0.mca`] = {byteLength: regionFixtureBytes};
}
for (let index = 0; index < externalFixtureCount; index++) {
  backing[`/gaius/saves/world/region/c.${index}.0.mcc`] = {byteLength: externalFixtureBytes};
}
const persistentFiles = new Proxy(backing, {
  get(target, property, receiver) {
    const value = Reflect.get(target, property, receiver);
    if (typeof property === "string" && value && Number.isFinite(value.byteLength)) {
      backingBytesRead += value.byteLength;
      return new Uint8Array(value.byteLength);
    }
    return value;
  },
});

const materialized = new Map();
let materializedBytes = 0;
let peakMaterializedBytes = 0;
const startupPaths = Object.keys(persistentFiles);
for (const storedPath of startupPaths) {
  if (!storedPath.endsWith(".mca") && !storedPath.endsWith(".mcc")) {
    materialized.set(storedPath, persistentFiles[storedPath]);
  }
}
assert.equal(materialized.size, 0, "mount materialized chunk-storage payloads");
assert.equal(backingBytesRead, 0, "mount read .mca or .mcc payload bytes");

const placeholders = new Set();
for (let index = 0; index < externalFixtureCount; index++) {
  const externalPath = `/gaius/saves/world/region/c.${index}.0.mcc`;
  assert.equal(externalPath in persistentFiles, true);
  placeholders.add(externalPath);
}
assert.equal(backingBytesRead, 0, "many .mcc existence checks hydrated payload bytes");
assert.equal(materializedBytes, 0, ".mcc existence checks left payload bytes materialized");
assert.equal(placeholders.size, externalFixtureCount,
  "metadata placeholders did not cover every durable .mcc file");

for (const chunkPath of startupPaths) {
  const bytes = persistentFiles[chunkPath];
  placeholders.delete(chunkPath);
  materialized.set(chunkPath, bytes);
  materializedBytes += bytes.byteLength;
  peakMaterializedBytes = Math.max(peakMaterializedBytes, materializedBytes);
  materializedBytes -= materialized.get(chunkPath).byteLength;
  materialized.delete(chunkPath);
}
assert.equal(materializedBytes, 0, "closed chunk payloads stayed materialized");
assert.equal(materialized.size, 0, "closed chunk files stayed in the VFS");
assert.equal(Object.keys(backing).length, regionFixtureCount + externalFixtureCount,
  "VFS reclamation deleted durable OPFS records");
assert.equal(peakMaterializedBytes, externalFixtureBytes,
  "sequential chunk access retained more than one oversized payload");

const singleByteRead = (bytes, position) => {
  if (position >= bytes.length) return {value: -1, position};
  return {value: bytes[position] & 0xff, position: position + 1};
};
const byteFixture = Uint8Array.of(0x00, 0x7f, 0x80, 0xff);
let bytePosition = 0;
for (const expected of [0x00, 0x7f, 0x80, 0xff]) {
  const result = singleByteRead(byteFixture, bytePosition);
  assert.equal(result.value, expected);
  bytePosition = result.position;
}
assert.equal(singleByteRead(byteFixture, bytePosition).value, -1,
  "single-byte EOF returned stale data");

const boundedSkip = (size, position, n) => {
  if (n <= 0) return {skipped: 0, position};
  const skipped = Math.min(n, Math.max(0, size - position));
  return {skipped, position: position + skipped};
};
assert.deepEqual(boundedSkip(4, 1, 0), {skipped: 0, position: 1});
assert.deepEqual(boundedSkip(4, 1, -9), {skipped: 0, position: 1});
assert.deepEqual(boundedSkip(4, 1, Number.MAX_SAFE_INTEGER), {skipped: 3, position: 4});
assert.deepEqual(boundedSkip(4, 4, 10), {skipped: 0, position: 4});

const parsedConfig = JSON.parse(configText);
const buildRootProfileId = process.env.GAIUS_BUILD_ROOT
  ? path.basename(nativePath(process.env.GAIUS_BUILD_ROOT).replaceAll("\\", "/")) : "";
const overlayProfileId = process.env.GAIUS_OVERLAY_DIRECTORY
  ? path.basename(nativePath(process.env.GAIUS_OVERLAY_DIRECTORY).replaceAll("\\", "/")) : "";
const isolatedProfileId = [buildRootProfileId, overlayProfileId]
  .find((value) => /^\d+(?:\.\d+)+$/.test(value)) || "";
const configuredProfilePath = nativePath(
  process.env.GAIUS_VERSION_PROFILE_PATH
    || (isolatedProfileId ? `versions/${isolatedProfileId}.json` : String(parsedConfig.versionProfile || "")),
);
const version = path.basename(configuredProfilePath.replaceAll("\\", "/"))
  .replace(/\.json$/, "");
const {teaVMVersion} = parsedConfig;
const overlayRoot = nativePath(process.env.GAIUS_OVERLAY_DIRECTORY || path.join(
  root, "port/work/overlays", process.env.GAIUS_BUILD_ROOT || process.env.GAIUS_VERSION_PROFILE_PATH ? version : "",
));
const overlayJar = path.join(overlayRoot, `teavm-core-${teaVMVersion}-gaius.jar`);
function javaTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath)) {
    const candidate = path.join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}
let bytecodeVerified = false;
try {
  await access(overlayJar);
  const bytecode = execFileSync(javaTool("javap"), [
    "-classpath", overlayJar, "-p", "-c",
    "org.teavm.runtime.fs.memory.InMemoryVirtualFileSystem",
  ], {encoding: "utf8"});
  assert.match(bytecode, /BrowserFilePersistence\.restoreOnDemand/,
    "built TeaVM VFS does not invoke on-demand restoration");
  const classlibJar = path.join(overlayRoot,
    `teavm-classlib-${teaVMVersion}-gaius.jar`);
  const providerBytecode = execFileSync(javaTool("javap"), [
    "-classpath", classlibJar, "-p", "-c",
    "org.teavm.classlib.java.nio.file.impl.TDefaultFileSystemProvider",
  ], {encoding: "utf8"});
  assert.match(providerBytecode, /BrowserFilePersistence\.materializeForOpen/,
    "built TeaVM stream provider does not force .mcc payload restoration");
  assert.match(providerBytecode,
    /TFileInputStream\."<init>":\(Ljava\/lang\/String;Lorg\/teavm\/runtime\/fs\/VirtualFileAccessor;\)V/,
    "built Files.newInputStream does not pass the .mcc path to the input stream");
  bytecodeVerified = true;
} catch (error) {
  if (error?.code !== "ENOENT") throw error;
}

console.log(JSON.stringify({
  ok: true,
  fixtureRegions: regionFixtureCount,
  fixtureExternalChunks: externalFixtureCount,
  durableBytes: regionFixtureCount * regionFixtureBytes +
    externalFixtureCount * externalFixtureBytes,
  startupHydratedBytes: 0,
  existenceScanHydratedBytes: 0,
  peakMaterializedBytes,
  finalMaterializedBytes: materializedBytes,
  bytecodeVerified,
}));
