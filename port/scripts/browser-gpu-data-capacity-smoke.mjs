#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFileSync} from "node:fs";

const source = readFileSync(new URL("../src/main/java/dev/gaius/browser/quality/BrowserGpuDataCapacity.java",
  import.meta.url), "utf8");
const patcher = readFileSync(new URL("../tools/src/main/java/dev/gaius/tools/patches/render/RenderPatches.java",
  import.meta.url), "utf8");
const script = source.match(/@JSBody\(script = """([\s\S]*?)"""\)/)[1];
const choose = new Function("globalThis", "URLSearchParams", script);
for (const [query, expected] of [["", -1], ["vanilla", -2], ["VANILLA", -2], ["1", 1],
  ["32767", 32767], ["65536", 65536], ["0", -1], ["65537", -1], ["1.5", -1],
  ["NaN", -1], ["Infinity", -1], ["4294967298", -1], ["-4294967294", -1]]) {
  assert.equal(choose({location: {search: `?gaiusUboCap=${query}`}}, URLSearchParams), expected, query);
}
assert.equal(choose({}, URLSearchParams), -1);
for (const [label, owner, method] of [
  ["Chunk Sections Instanced", "DynamicGpuDataStorageNonMapped", "writeChunkSectionsInstanced"],
  ["Chunk Sections Command Buffer", "DynamicGpuDataStorageMapped", "writeChunkSectionCommands"],
]) {
  assert.ok(patcher.includes(`"${label}", "net/minecraft/client/renderer/${owner}"`),
    `${label} storage owner is not guarded`);
  assert.ok(patcher.includes(`MethodNode ${method} = find(node, "${method}"`),
    `${label} method shape is not located`);
  assert.ok(patcher.includes(`"${label}", "net/minecraft/client/renderer/${owner}",\n                2, 32767)`),
    `${label} is not raised from capacity 2 to the browser slab`);
}
console.log("browser-gpu-data-capacity-smoke: ok (14 query cases, 2 lazy-storage patch contracts)");
