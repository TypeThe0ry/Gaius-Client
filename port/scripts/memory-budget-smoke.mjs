import assert from "node:assert/strict";
import {readFileSync, readdirSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("../web/runtime/quality/quality-profile.js", import.meta.url), "utf8");
const persistence = readFileSync(new URL("../overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java", import.meta.url), "utf8");
function body(java, signature) {
  const end = java.indexOf(signature);
  assert.ok(end > 0, signature);
  const start = java.lastIndexOf('script = """', end) + 'script = """'.length;
  return java.slice(start, java.lastIndexOf('"""', end));
}
function page({device = 8, limit = 4 * 1024 ** 3, selected = "wasm", search = "", saved = {},
  profileId = null} = {}) {
  const caps = {tier: "ultra", tierIndex: 3};
  const root = {URLSearchParams, navigator: {deviceMemory: device},
    performance: {memory: {jsHeapSizeLimit: limit}}, location: {search},
    __gaiusRuntimeSelection: {selected}, __gaiusGpuCaps: caps,
    GaiusQuality: {caps: {current: () => caps, ensureTier: () => caps}},
    localStorage: {getItem: () => JSON.stringify(saved)}};
  if (profileId) root.__gaiusProfileId = profileId;
  root.window = root;
  const context = vm.createContext(root);
  vm.runInContext(source, context);
  return {root, context, settings: root.GaiusQuality.profile.resolve()};
}
for (const device of [0.5, 1, 2, 4]) {
  const {root, settings, context} = page({device});
  assert.equal(settings.tier, "low");
  assert.equal(settings.memoryTier.reason, "device-memory");
  assert.equal(settings.oitTier, false);
  assert.equal(settings.maxDpr, 1);
  assert.equal(settings.post.enabled, false);
  assert.equal(root.GaiusQuality.profile.oitAllowed(), false);
  assert.equal(vm.runInContext(`(function(){${body(persistence, "private static native String runtimeGraphicsTier();")}})()`, context), "low");
}
assert.equal(page({device: undefined, limit: 768 * 1024 ** 2}).settings.tier, "low");
assert.equal(page({device: 8}).settings.tier, "ultra");
assert.equal(page({device: null, limit: 0}).settings.tier, "ultra");
assert.equal(page({device: 4, search: "?gaiusMemoryTier=off"}).settings.tier, "ultra");
assert.equal(page({device: 4, search: "?gaiusMemoryTier=0"}).settings.tier, "ultra");
assert.equal(page({device: 8, search: "?gaiusMemoryTier=low"}).settings.tier, "low");
assert.equal(page({device: 4, selected: "js", search: "?runtime=wasm"}).settings.tier, "ultra");
assert.equal(page({device: 4, selected: "js"}).settings.tier, "ultra");
assert.equal(page({device: 4, saved: {renderScale: 0.75}}).settings.renderScale, 0.75);
const profiles = readdirSync(new URL("../versions/", import.meta.url))
  .filter((name) => name.endsWith(".json"))
  .map((name) => JSON.parse(readFileSync(new URL(`../versions/${name}`, import.meta.url), "utf8")));
const renderpearlProfile = profiles.find((profile) => profile.shaderFront === "renderpearl");
const legacyProfile = profiles.find((profile) => profile.patchSet === "legacy-12111");
assert.ok(renderpearlProfile && legacyProfile, "profile capability smoke inputs");
assert.equal(page({profileId: renderpearlProfile.id}).settings.maxDpr, 2,
  "generated profile capabilities select renderpearl defaults without page seam globals");
const legacyProfilePage = page({profileId: legacyProfile.id});
assert.equal(legacyProfilePage.settings.maxDpr, 1,
  "legacy profile capabilities retain the 1x default without page seam globals");
legacyProfilePage.root.__gaiusRenderSeam = renderpearlProfile.renderSeam;
assert.equal(legacyProfilePage.root.GaiusQuality.profile.resolve().maxDpr, 2,
  "page seam remains an explicit diagnostic override");
const fallback = page({device: 4});
delete fallback.root.__gaiusRuntimeSelection;
fallback.root.__gaiusWasmTimings = {};
assert.equal(fallback.root.GaiusQuality.profile.resolve().tier, "low");
const glSource = readFileSync(new URL("../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java", import.meta.url), "utf8");
const mappedBudget = body(glSource, "private static native int mappedPoolBudgetJs();");
for (const [selected, device, search, expected] of [
  ["js", 4, "", 32], ["wasm", 4, "", 4], ["wasm", 8, "", 8],
  ["wasm", 4, "?gaiusMappedPoolMB=32", 32], ["wasm", 8, "?gaiusMappedPoolMB=0", 0],
  ["wasm", 8, "?gaiusMappedPoolMB=bad", 8]
]) {
  const {root, context} = page({selected, device, search});
  if (selected === "wasm") root.__gaiusWasmMemory = {};
  assert.equal(vm.runInContext(`(function(){${mappedBudget}})()`, context), expected * 1048576);
}
console.log("PASS memory tier, vanilla defaults bridge, persisted overrides, JS/Wasm runtime and staging caps");
