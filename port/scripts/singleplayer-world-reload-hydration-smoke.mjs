#!/usr/bin/env node

import assert from "node:assert/strict";
import {VERSION_PROFILES} from "./verification-profiles.mjs";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const client = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java",
  import.meta.url,
), "utf8");
const postprocess = await readFile(new URL(
  "./postprocess-index-html.py",
  import.meta.url,
), "utf8");
const persistence = await readFile(new URL(
  "../overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java",
  import.meta.url,
), "utf8");

assert.match(postprocess, /data\/minecraft\/world_gen_settings\.dat/,
  "postprocess source does not retain world-gen metadata");
assert.match(client, /data\/minecraft\/world_gen_settings\.dat/,
  "singleplayer restart refresh does not retain world-gen metadata");
assert.match(persistence, /data\/minecraft\/world_gen_settings\.dat/,
  "BrowserFilePersistence mount does not restore world-gen metadata");

function extractFunction(source, marker, endMarker) {
  const start = source.indexOf(marker);
  assert.ok(start >= 0, `missing function marker: ${marker}`);
  const end = source.indexOf(endMarker, start);
  assert.ok(end > start, `missing function end marker: ${marker}`);
  return source.slice(start, end + endMarker.length);
}

// Extract the launcher filter from the postprocessor's source template rather
// than from generated output. This keeps the smoke useful before a TeaVM build and
// makes the source of truth explicit for a fresh browser page.
const filterStartMarker = '"      function isClientBootstrapPath(path) {\\n"';
const filterEndMarker = '"      function openDatabase() {\\n"';
const filterStart = postprocess.indexOf(filterStartMarker);
assert.ok(filterStart >= 0, "postprocess source has no bootstrap filter template");
const filterEnd = postprocess.indexOf(filterEndMarker, filterStart);
assert.ok(filterEnd > filterStart, "postprocess source has no bootstrap filter boundary");
const filterTemplate = postprocess.slice(filterStart, filterEnd);
const clientFilterSource = [...filterTemplate.matchAll(/"((?:\\.|[^"\\])*)"/g)]
  .map((match) => JSON.parse(`"${match[1]}"`))
  .join("");
assert.match(clientFilterSource, /world_gen_settings\.dat/,
  "postprocess template lost the world-gen bootstrap allowlist entry");

const restartFilterSource = extractFunction(
  client,
  "const isWorldMetadataPath = function(path) {",
  "\n                };",
);

const context = {
  globalThis: null,
  normalize(path) {
    const value = String(path || "/").replace(/\\/g, "/");
    return value.startsWith("/") ? value : "/" + value;
  },
};
context.globalThis = context;
vm.runInNewContext(
  `globalThis.clientFilter = ${clientFilterSource};\n` +
    "globalThis.restartFilter = (function() {\n" +
    "  const worldPrefix = '/gaius/saves/';\n" +
    `  return ${restartFilterSource.replace("const isWorldMetadataPath = ", "")};\n` +
    "})();",
  context,
  {filename: "singleplayer-world-reload-hydration-smoke"},
);

function hydrate(records, predicate) {
  const files = Object.create(null);
  for (const [path, value] of records) {
    if (predicate(path)) files[path] = value.slice();
  }
  return files;
}

const profileIds = VERSION_PROFILES.map(profile => profile.id);
for (const profileId of profileIds) {
  const worldRoot = `/gaius/saves/${profileId}/`;
  const worldGenPath = `${worldRoot}data/minecraft/world_gen_settings.dat`;
  const levelPath = `${worldRoot}level.dat`;
  const iconPath = `${worldRoot}icon.png`;
  const regionPath = `${worldRoot}region/r.0.0.mca`;

  assert.equal(context.clientFilter(worldGenPath), true,
    `${profileId}: fresh-page filter must retain world-gen metadata`);
  assert.equal(context.clientFilter(levelPath), true,
    `${profileId}: fresh-page filter must retain level metadata`);
  assert.equal(context.clientFilter(iconPath), true,
    `${profileId}: fresh-page filter must retain icon metadata`);
  assert.equal(context.clientFilter(regionPath), false,
    `${profileId}: fresh-page filter must not hydrate a large region`);
  assert.equal(context.restartFilter(worldGenPath), true,
    `${profileId}: restart filter must retain world-gen metadata`);
  assert.equal(context.restartFilter(regionPath), false,
    `${profileId}: restart filter must not hydrate a large region`);

  const worldGenBytes = new Uint8Array([0x0a, 0x0b, 0x0c, 0x0d]);
  const records = new Map([
    [levelPath, new Uint8Array([1, 2, 3])],
    [iconPath, new Uint8Array([4, 5])],
    [worldGenPath, worldGenBytes],
    // Model a genuinely large region record without ever selecting it for
    // the title-client mirror. Its bytes stay in IndexedDB/OPFS on demand.
    [regionPath, new Uint8Array(8 * 1024 * 1024)],
  ]);

  // A fresh page starts with an empty mirror. The world-gen record must be
  // readable immediately, while the large region stays in IndexedDB/OPFS.
  const freshFiles = hydrate(records, context.clientFilter);
  assert.deepEqual([...freshFiles[worldGenPath]], [...worldGenBytes]);
  assert.equal(regionPath in freshFiles, false);

  // A page restart refreshes the same small metadata set and discards stale
  // title-client entries without pulling the region payload into memory.
  const restartedFiles = {
    [worldGenPath]: new Uint8Array([0xff]),
  };
  for (const path of Object.keys(restartedFiles)) {
    if (context.restartFilter(path)) delete restartedFiles[path];
  }
  for (const [path, value] of records) {
    if (context.restartFilter(path)) restartedFiles[path] = value.slice();
  }
  assert.deepEqual([...restartedFiles[worldGenPath]], [...worldGenBytes]);
  assert.equal(regionPath in restartedFiles, false);
}

console.log(JSON.stringify({
  ok: true,
  profiles: profileIds,
  freshPageWorldGenReadable: true,
  restartWorldGenReadable: true,
  largeRegionNotHydrated: true,
}));
