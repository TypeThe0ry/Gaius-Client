#!/usr/bin/env node
// Automatic video preset bookkeeping (GaiusQuality.autoPreset in
// web/runtime/quality/quality-profile.js) and its Java bridge in BrowserFilePersistence:
// the first start records the vanilla FAST seed, tier changes leave it unchanged, and once the
// player changed a video option the profile is never adjusted again.
//
//   node port/scripts/quality-auto-preset-smoke.mjs

import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("../web/runtime/quality/quality-profile.js", import.meta.url), "utf8");
const persistence = readFileSync(new URL(
  "../overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java", import.meta.url), "utf8");

function jsBody(java, signature) {
  const end = java.indexOf(signature);
  assert.ok(end > 0, signature);
  const start = java.lastIndexOf('script = """', end) + 'script = """'.length;
  return java.slice(start, java.lastIndexOf('"""', end));
}

// BrowserFilePersistence writes one vanilla FAST graphics block for every tier.
function fastBlock(java) {
  const at = java.indexOf('private static final String VANILLA_FAST_GRAPHICS_DEFAULTS = String.join("\\n",');
  assert.ok(at > 0, "VANILLA_FAST_GRAPHICS_DEFAULTS");
  const end = java.indexOf(') + "\\n";', at);
  return [...java.slice(at, end).matchAll(/^\s*"((?:[^"\\]|\\.)*)"[,)]?\s*$/gm)]
    .map((match) => match[1].replace(/\\"/g, '"')).join("\n") + "\n";
}
const COMMON = "version:4790\nautoJump:false\nmaxFps:120\nenableVsync:true\n";
const FAST = fastBlock(persistence);
const seeds = {
  low: COMMON + FAST,
  mid: COMMON + FAST,
  high: COMMON + FAST,
};
for (const seed of Object.values(seeds)) {
  assert.match(seed, /^graphicsPreset:"fast"$/m);
  assert.match(seed, /^renderDistance:8$/m);
  assert.match(seed, /^simulationDistance:6$/m);
  assert.match(seed, /^improvedTransparency:false$/m);
}

function page() {
  const storage = new Map();
  const logs = [];
  const root = {
    URLSearchParams, location: {search: ""}, navigator: {},
    console: {info: (line) => logs.push(line)},
    localStorage: {
      getItem: (key) => (storage.has(key) ? storage.get(key) : null),
      setItem: (key, value) => storage.set(key, String(value)),
      removeItem: (key) => storage.delete(key),
    },
  };
  root.window = root;
  const context = vm.createContext(root);
  vm.runInContext(source, context);
  const bridge = jsBody(persistence, "private static native String syncAutomaticPreset(");
  const sync = (profile, current, seed, tier, seeded) => {
    context.__args = {profile, current, seed, tier, seeded};
    return vm.runInContext(`(function(profile,current,seed,tier,seeded){${bridge}})`
      + "(__args.profile,__args.current,__args.seed,__args.tier,__args.seeded)", context);
  };
  return {root, storage, logs, sync};
}

// The game rewrites options.txt with every option, in its own order.
function gameSave(options, extra = "") {
  const lines = options.trim().split("\n").filter((line) => !line.startsWith("version:"));
  return "version:4790\n" + extra + lines.reverse().join("\n") + "\nlang:en_us\n";
}
function setOption(options, key, value) {
  return options.replace(new RegExp(`^${key}:.*$`, "m"), `${key}:${value}`);
}

// 1. First start: Java seeds the mid preset and the bridge records it.
const p = page();
assert.equal(p.sync("26.3|a", "", seeds.mid, "mid", true), "");
assert.equal(p.root.__gaiusAutoPreset.action, "seeded");
assert.match(p.logs.at(-1), /^Gaius quality: first start, applied vanilla FAST video settings, render distance 8$/);
const markers = JSON.parse(p.storage.get("gaius.quality.autoPreset.v1"));
assert.equal(markers["26.3|a"].tier, "mid");
assert.equal(markers["26.3|a"].values.renderDistance, "8");
assert.equal(markers["26.3|a"].values.maxFps, undefined, "frame settings are not part of the preset");

// 2. Same tier, the game saved the options: nothing changes.
let saved = gameSave(seeds.mid);
assert.equal(p.sync("26.3|a", saved, seeds.mid, "mid", false), "");
assert.equal(p.root.__gaiusAutoPreset.action, "auto-kept");

// 3. A new classification (high) on an untouched profile keeps the vanilla FAST values.
const upgraded = p.sync("26.3|a", saved, seeds.high, "high", false);
assert.equal(upgraded, "");
assert.equal(p.root.__gaiusAutoPreset.action, "auto-kept");
assert.match(p.logs.at(-1), /video settings follow vanilla FAST values/);

// 4. The player changes the render distance: detected once, then never adjusted again.
saved = setOption(saved, "renderDistance", "16");
assert.equal(p.sync("26.3|a", saved, seeds.low, "low", false), "");
assert.equal(p.root.__gaiusAutoPreset.action, "manual-detected");
assert.deepEqual([...p.root.__gaiusAutoPreset.changed], ["renderDistance"]);
assert.match(p.logs.at(-1), /the player changed video settings \(renderDistance\); automatic presets are off/);
assert.equal(p.sync("26.3|a", gameSave(seeds.high), seeds.low, "low", false), "",
  "manual profile: not adjusted even when its options equal a preset again");
assert.equal(p.root.__gaiusAutoPreset.action, "manual");

// 5. Changing only the frame settings keeps the automatic preset.
p.sync("26.3|b", "", seeds.mid, "mid", true);
const fps = setOption(setOption(gameSave(seeds.mid), "maxFps", "260"), "enableVsync", "false");
const lowered = p.sync("26.3|b", fps, seeds.low, "low", false);
assert.equal(lowered, "");
assert.match(fps, /^maxFps:260$/m, "the player's frame settings stay");

// 6. A key the game of this version does not write is not compared and not re-added.
p.sync("1.21.11|c", "", seeds.mid, "mid", true);
const legacy = gameSave(seeds.mid).replace(/^graphicsPreset:.*\n/m, "").replace(/^cutoutLeaves:.*\n/m, "");
assert.equal(p.sync("1.21.11|c", legacy, seeds.mid, "mid", false), "");
assert.equal(p.root.__gaiusAutoPreset.action, "auto-kept");
const legacyLow = p.sync("1.21.11|c", legacy, seeds.low, "low", false);
assert.equal(legacyLow, "");

// 7. Profiles are independent; a profile without a record (saved before this feature) is left alone.
assert.equal(p.sync("26.2|d", gameSave(seeds.mid), seeds.high, "high", false), "");
assert.equal(p.root.__gaiusAutoPreset.action, "untracked");

// 8. Without the quality layer the bridge changes nothing; blocked storage records nothing.
{
  const bare = vm.createContext({});
  const bridge = jsBody(persistence, "private static native String syncAutomaticPreset(");
  assert.equal(vm.runInContext(`(function(profile,current,seed,tier,seeded){${bridge}})("p","x","y","mid",false)`, bare), "");
  const blocked = page();
  blocked.root.localStorage.setItem = () => { throw new Error("blocked"); };
  assert.equal(blocked.sync("26.3|e", "", seeds.mid, "mid", true), "");
  assert.equal(blocked.sync("26.3|e", gameSave(seeds.mid), seeds.high, "high", false), "");
  assert.equal(blocked.root.__gaiusAutoPreset.action, "untracked");
}

// 9. CRLF options keep their line endings.
{
  const q = page();
  q.sync("26.3|f", "", seeds.mid, "mid", true);
  const crlf = gameSave(seeds.mid).replace(/\n/g, "\r\n");
  const result = q.sync("26.3|f", crlf, seeds.low, "low", false);
  assert.equal(result, "");
}

// 10. The F3 CPU line (BrowserQualityOptions.debugCpuInfo) names the tier and its source.
{
  const options = readFileSync(new URL(
    "../src/main/java/dev/gaius/browser/quality/BrowserQualityOptions.java", import.meta.url), "utf8");
  const body = jsBody(options, "private static native String queryDebugQuality();");
  const line = (globals) => vm.runInContext(`(function(){${body}})()`, vm.createContext(globals));
  const caps = (value) => ({GaiusQuality: {caps: {current: () => value}}});
  assert.equal(line({}), "");
  assert.equal(line(caps({tier: "high"})), "Gaius quality high (auto)");
  assert.equal(line(caps({tier: "low", overridden: true})), "Gaius quality low (override)");
  assert.equal(line({...caps({tier: "mid"}), __gaiusAutoPreset: {automatic: false}}),
    "Gaius quality mid (player settings)");
  assert.match(options, /return debugQuality\.isEmpty\(\) \? base : base \+ ", " \+ debugQuality;/);
}

console.log("PASS automatic video preset: vanilla FAST seed, tier stability, player choice wins, version keys, bridge, F3 line");
