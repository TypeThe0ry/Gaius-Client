import assert from "node:assert/strict";
import {mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import {execFileSync} from "node:child_process";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

assert.ok(process.env.OUT, "OUT must name a scratch directory");
const out = resolve(process.env.OUT, "memory-tooling-smoke");
mkdirSync(out, {recursive: true});
const metadata = {node_fields: ["type", "name", "id", "self_size", "edge_count"],
  node_types: [["object", "native"], "string", "number", "number", "number"]};
const snapshot = {snapshot: {meta: metadata}, nodes: [0, 0, 1, 12, 0, 1, 1, 2, 34, 0,
  0, 0, 3, 56, 0, 1, 2, 4, 78, 0], edges: [],
  strings: ['owner "escaped" \\ name', "system / JSArrayBufferData", "long".repeat(20000)]};
const input = join(out, "fixture.heapsnapshot"), summaryPath = join(out, "summary.json");
writeFileSync(input, JSON.stringify(snapshot));
execFileSync(process.execPath, ["--max-old-space-size=384",
  fileURLToPath(new URL("memory-heap-summary.mjs", import.meta.url)), input, summaryPath]);
const summary = JSON.parse(readFileSync(summaryPath, "utf8"));
assert.equal(summary.nodeCount, 4);
assert.equal(summary.selfBytes, 180);
assert.equal(summary.byType.native, 112);
assert.equal(summary.largestNames.find(entry => entry.count === 2).name, snapshot.strings[0]);
assert.equal(summary.largestNames[0].name.length, 180);

let resize, refreshes = 0;
const memory = new WebAssembly.Memory({initial: 1, maximum: 64});
const root = {performance: {now: () => 1}, console, URLSearchParams,
  WebAssembly: {Suspending: function() {}, promising: callback => callback},
  __gaiusWasmSource: new Uint8Array(1), __gaiusLinear: {refresh: () => refreshes++},
  TeaVM: {wasmGC: {load: async (source, options) => {
    assert.equal(source.length, 1);
    resize = options.memory.onResize;
    options.installImports({env: {memory}});
    return {instance: {exports: {}}};
  }}},
  GaiusJspi: {createScheduler: () => ({}), installJspi: () => ({startMain: () => Promise.resolve()}),
    routeEventQueue: () => {}}};
root.window = root;
vm.runInNewContext(readFileSync(new URL("../web/runtime/wasmgc/gaius-wasm-boot.js", import.meta.url), "utf8"), root);
await root.__gaiusClientReady;
for (let index = 0; index < 40; index++) {memory.grow(1); resize();}
const telemetry = root.__gaiusMemoryTelemetry.linearMemory;
assert.equal(telemetry.initialBytes, 65536);
assert.equal(telemetry.currentBytes, memory.buffer.byteLength);
assert.equal(telemetry.peakBytes, memory.buffer.byteLength);
assert.equal(telemetry.growthCount, 40);
assert.equal(telemetry.growths.length, 32);
assert.equal(refreshes, 40);

const injectionPath = join(out, "inject.js");
execFileSync(process.execPath, [fileURLToPath(new URL("memory-budget-inject.mjs", import.meta.url)), injectionPath],
  {env: {...process.env, MEMORY_BASELINE: "0", MEMORY_DEVICE_GIB: "4"}});
const callbacks = [], caps = {tier: "ultra"};
const page = {URLSearchParams, navigator: {}, performance: {now: () => 1}, location: {search: "?runtime=wasm"},
  __gaiusRuntimeSelection: {selected: "wasm"}, __gaiusGpuCaps: caps,
  GaiusQuality: {caps: {current: () => caps, ensureTier: () => caps}},
  __gaiusBoot: {beforeMain: async () => {page.originalBootRan = true;}},
  setInterval: callback => {callbacks.push(callback); return callbacks.length;}, clearInterval: () => {},
  addEventListener: () => {}};
page.window = page;
vm.runInNewContext(readFileSync(injectionPath, "utf8"), page);
page.__gaiusBeforeMain();
assert.equal(page.__gaiusMemoryExperiment.qualitySourceInjection, false);
await page.__gaiusBoot.beforeMain();
assert.equal(page.originalBootRan, true);
assert.equal(page.__gaiusMemoryTier.reason, "device-memory");
assert.equal(page.GaiusQuality.caps.ensureTier().tier, "low");
assert.equal(page.__gaiusMemoryExperiment.qualitySourceInjection, true);
page.__gaiusWasmMemory = memory;
page.__gaiusGL = {maxTotalBufferShadowBytes: () => 1024 ** 3};
callbacks.forEach(callback => callback());
assert.equal(page.__gaiusGL.maxTotalBufferShadowBytes(), 16 * 1048576);
assert.equal(page.__gaiusMemoryExperiment.shadowBudgetApplied, true);
execFileSync(process.execPath, [fileURLToPath(new URL("memory-budget-cdp.mjs", import.meta.url))],
  {env: {...process.env, OUT: out, MEMORY_PREPARE_ONLY: "1", MEMORY_INJECT: injectionPath}});
execFileSync(process.execPath, ["--check", join(out, "memory-run.mjs")]);
console.log("PASS bounded snapshot parser, loader telemetry, injection ordering, shadow budget and CDP harness syntax");
