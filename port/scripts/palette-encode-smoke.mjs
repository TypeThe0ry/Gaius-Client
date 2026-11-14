// Chunk save palette encoding (patchPalettedContainerBrowserEncode): the registration, the patch
// shape and the helpers, and, with GAIUS_JAVA_HOME set, the JVM byte comparison against vanilla
// (native/golden/storage/palette-encode-check.sh).
//
//   node port/scripts/palette-encode-smoke.mjs
//   GAIUS_JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 node port/scripts/palette-encode-smoke.mjs 26.3
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";
import {patcherSource} from "./patcher-sources.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const read = (path) => readFileSync(root + path, "utf8");

const client = patcherSource("MinecraftClientPatcher");
assert.match(client,
  /PatchRegistry\.run\("MinecraftClientPatcher\.patchPalettedContainerBrowserEncode", "PERF js wasmgc"/,
  "the patch is registered as a PERF patch on both targets");
assert.match(read("tools/patchkit/classification.tsv"),
  /^MinecraftClientPatcher\.patchPalettedContainerBrowserEncode\tPERF\t/m, "the patch kind is reviewed");

const start = client.indexOf("public static void patchPalettedContainerBrowserEncode");
const patch = client.slice(start, client.indexOf("    public static void ", start + 10));
for (const needle of ["BrowserPaletteEntryCodec", "BrowserPaletteRepack", "reencodeContents", "PalettedContainer"]) {
  assert.ok(patch.includes(needle), `the patch mentions ${needle}`);
}
assert.ok(!/writeComputeFrames/.test(patch), "the patch keeps vanilla's frames");

const codec = read("mc/shared/canonical/src/java/dev/gaius/browser/BrowserPaletteEntryCodec.java");
assert.match(codec, /ops != \(Object\) NbtOps\.INSTANCE/, "only NbtOps encodings are memoized");
assert.match(codec, /result\.error\(\)\.isEmpty\(\)/, "only plain successes are memoized");
assert.match(codec, /insertionOrder/, "the key order of rebuilt compounds is checked");
const repack = read("mc/shared/canonical/src/java/dev/gaius/browser/BrowserPaletteRepack.java");
assert.match(repack, /PaletteResize\.noResizeExpected\(\)/, "the repack keeps vanilla's resize handler");
for (const source of [codec, repack]) {
  assert.ok(!/JSBody|JSObject|java\.util\.concurrent/.test(source), "the helpers are plain Java");
}

const java = process.env.GAIUS_JAVA_HOME;
if (java) {
  const profiles = process.argv.slice(2);
  const result = spawnSync("bash", [root + "native/golden/storage/palette-encode-check.sh", ...profiles],
    {stdio: "inherit", env: {...process.env, GOLDEN_JAVA_HOME: java}});
  assert.equal(result.status, 0, "palette-encode-check.sh passes");
} else {
  console.log("palette-encode-smoke: GAIUS_JAVA_HOME is not set, the JVM comparison was not run");
}
console.log("palette-encode-smoke: ok");
