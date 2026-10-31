import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const boot = await readFile(new URL("../web/boot/gaius-boot.js", import.meta.url), "utf8");
const archive = await readFile(new URL(
  "../overrides/client/src/main/java/dev/gaius/browser/AssetArchive.java", import.meta.url), "utf8");
assert.match(boot, /__gaiusVanillaSounds/);
assert.match(boot, /__gaiusIsLowMemoryTier/);
assert.match(boot, /clientRuntime\.selected !== "wasm"/);
assert.match(boot, /sounds-merged-for-js-fallback/);
assert.match(boot, /gaiusMemoryTier.*low.*publishSoundPack/s);
assert.match(archive, /globalThis\.__gaiusVanillaAssets, globalThis\.__gaiusVanillaSounds/);
// Indexed loops: the JavaScript backend does not parse for-of in a @JSBody script.
assert.match(archive, /for \(var i = 0; i < packs\.length; i\+\+\)/);
console.log("gaius boot sound sidecar smoke passed");
