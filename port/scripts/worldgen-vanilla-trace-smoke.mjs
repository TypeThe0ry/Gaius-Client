#!/usr/bin/env node
// Opt-in vanilla-entry trace of the worldgen kernel facade (port/web/kernels/worldgen-kernel.js):
//
//   node port/scripts/worldgen-vanilla-trace-smoke.mjs
//
// Off by default (recordVanilla counts nothing, telemetry().vanilla stays empty); counts per stage and
// reason with ?gaiusWorldgenVanillaTrace=1, __gaiusWorldgenVanillaTrace, the worldgen kernel config
// switch or the kernel job timeline switch. Needs no wasm module.
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

const kernelsDir = join(dirname(fileURLToPath(import.meta.url)), "..", "web", "kernels");

function load(setup) {
  const context = vm.createContext({console, URLSearchParams, WebAssembly, BigInt, Map, Set, Promise, queueMicrotask,
    SharedArrayBuffer, setInterval, clearInterval, setTimeout, clearTimeout});
  context.globalThis = context;
  context.location = {search: ""};
  context.crossOriginIsolated = false;
  setup(context);
  vm.runInContext(readFileSync(join(kernelsDir, "worldgen-job.js"), "utf8"), context, {filename: "worldgen-job.js"});
  context.__gaiusKernelSemantics = {schemaVersion: 1, profile: "fixture", kernels: {worldgen: {enabled: true}}};
  vm.runInContext(readFileSync(join(kernelsDir, "kernel-mirror.js"), "utf8"), context);
  vm.runInContext(readFileSync(join(kernelsDir, "worldgen-kernel.js"), "utf8"), context, {filename: "worldgen-kernel.js"});
  return context.GaiusWorldgenKernel;
}

const off = load(() => {});
assert.equal(off.vanillaTraceEnabled(), false);
off.recordVanilla("frontier", "kernel");
assert.deepEqual({...off.telemetry().vanilla}, {}, "the trace counted while switched off");
assert.deepEqual({...off.telemetry().hostFallbacks}, {}, "the trace must not touch hostFallbacks");

for (const [name, setup] of [
  ["url", (c) => { c.location = {search: "?gaiusWorldgenVanillaTrace=1"}; }],
  ["global", (c) => { c.__gaiusWorldgenVanillaTrace = true; }],
  ["config", (c) => { c.__gaiusWorldgenKernelConfig = {vanillaTrace: true}; }],
  ["timeline", (c) => { c.__gaiusKernelTimeline = "1"; }],
]) {
  const facade = load(setup);
  assert.equal(facade.vanillaTraceEnabled(), true, name);
  facade.recordVanilla("frontier", "registered");
  facade.recordVanilla("frontier", "registered");
  facade.recordVanilla("frontier", "unpublished-claim");
  facade.recordVanilla("frontier", "fallback:timeout");
  const vanilla = JSON.parse(JSON.stringify(facade.telemetry().vanilla));
  assert.deepEqual(vanilla, {frontier: {registered: 2, "unpublished-claim": 1, "fallback:timeout": 1}}, name);
  assert.deepEqual({...facade.telemetry().hostFallbacks}, {}, `${name}: hostFallbacks changed`);
}
for (const value of ["0", "", 0, false, null]) {
  const facade = load((c) => { c.__gaiusKernelTimeline = value; });
  assert.equal(facade.vanillaTraceEnabled(), false, `timeline ${JSON.stringify(value)}`);
}
console.log("worldgen vanilla trace smoke: ok");
