import assert from "node:assert/strict";
import {access, readFile} from "node:fs/promises";

// The integrated server start path is the only owner of Bootstrap, WorldLoader and datapack
// loading. The earlier world-prewarm handoff compared a settings fingerprint (level.dat NBT plus a
// SHA-256 over every datapack resource) on every cold start even though no prewarmed WorldStem
// could exist; this smoke keeps that cost and the dormant handoff from coming back unnoticed.
const root = new URL("..", import.meta.url);
const read = (path) => readFile(new URL(path, root), "utf8");
const serverMain = await read("src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java");
const patches = await read(
  "tools/src/main/java/dev/gaius/tools/patches/serverworker/ServerWorkerPatches.java");
const worker = await read("web/singleplayer/server-worker-bootstrap.js");

await assert.rejects(
  access(new URL("src/main/java/dev/gaius/browser/BrowserWorldSettingsFingerprint.java", root)),
  "the settings fingerprint has no consumer and must not be compiled into the server");

for (const name of [
  "BrowserWorldSettingsFingerprint", "loadWorldForMain", "takeOrAwaitPrewarmedWorldStem",
  "prewarmBrowserWorld", "prewarmedWorldStem", "recomputeStartSettingsFingerprint",
  "WorldLoader.load(", "registryHash(", "datapackHash(",
]) {
  assert.ok(!serverMain.includes(name),
    `server main still carries the dormant world prewarm (${name})`);
}
for (const name of ["loadWorldForMain", "takeOrAwaitPrewarmedWorldStem"]) {
  assert.ok(!patches.includes(name),
    `server Main patch still reroutes the vanilla WorldLoader future (${name})`);
}
// The cooperative-executor and datapack checkpoints stay; the vanilla calls they bracket do not
// change.
assert.match(patches, /serverStartupPhase\("world-loader-cooperative-executor"\)/,
  "WorldLoader checkpoint disappeared");
assert.match(patches, /new LdcInsnNode\("datapacks-loaded"\)/,
  "datapack checkpoint disappeared");
const datapackPatch = patches.slice(patches.indexOf("Server datapack completion shape changed"),
  patches.indexOf("datapackCheckpointPatched = true"));
assert.ok(datapackPatch.length > 0, "datapack checkpoint patch block not found");
assert.doesNotMatch(datapackPatch, /instructions\.(set|remove)\(/,
  "datapack checkpoint must not replace Main's CompletableFuture.get()");
assert.ok(!worker.includes("prewarmBrowserWorld"),
  "Worker prewarm must not start a world load before the start handshake");

console.log("settings fingerprint smoke passed (start path stays vanilla, no fingerprint hashing)");
