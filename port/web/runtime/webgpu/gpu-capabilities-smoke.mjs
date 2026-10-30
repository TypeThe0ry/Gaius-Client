import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("gpu-glue.js", import.meta.url), "utf8");

function fixture(features = [], omitIndirect = false) {
  const context = vm.createContext({ console, performance });
  vm.runInContext(source, context, { filename: "gpu-glue.js" });
  const gpu = context.GaiusWebGpu;
  gpu.status = 2;
  gpu.device = { features: new Set(features), limits: {} };
  gpu.queue = {};
  gpu.lostReason = "";
  if (omitIndirect) {
    gpu.drawIndexedIndirect = undefined;
    gpu.drawIndexedIndirectWithOffsets = undefined;
  }
  return gpu;
}

// The vanilla rules (javap of the clean 26.3 jar): FrontendRenderPass.drawIndexedIndirect throws
// without DeviceFeatures.drawIndirect and, for drawCount > 1, without multiDrawIndirect; 26.3
// terrain goes indirect once maxDrawIndirectDrawCount > 0 (RpDevice: 256 iff multi-draw-indirect).
function assertVanillaAccepted(gpu, label) {
  const draw = gpu.hasCapability("draw-indirect");
  const multi = gpu.hasCapability("multi-draw-indirect");
  assert.ok(!multi || draw, `${label}: multi-draw-indirect is never advertised without draw-indirect`);
  const terrainBatch = multi && draw ? 256 : 0;
  if (terrainBatch > 0) {
    assert.ok(draw && multi && gpu.hasCapability("non-zero-first-instance"),
      `${label}: indirect terrain batches pass FrontendRenderPass validation`);
  }
  return { draw, multi, terrainBatch };
}

{
  const gpu = fixture();
  const caps = assertVanillaAccepted(gpu, "no indirect-first-instance");
  assert.equal(caps.draw, true,
    "core WebGPU executes drawIndexedIndirect without the optional feature");
  assert.equal(caps.multi, false,
    "an adapter without indirect-first-instance must select direct terrain");
  assert.equal(caps.terrainBatch, 0);
  assert.equal(gpu.hasCapability("non-zero-first-instance"), true,
    "direct draw firstInstance is available without the indirect adapter feature");
}

{
  const gpu = fixture(["indirect-first-instance"]);
  const caps = assertVanillaAccepted(gpu, "indirect-first-instance");
  assert.equal(caps.draw, true,
    "drawIndirect must accompany multiDrawIndirect (vanilla checks drawIndirect first)");
  assert.equal(caps.multi, true,
    "the indexed indirect replay path is available when the adapter feature is present");
  assert.equal(caps.terrainBatch, 256);
  assert.equal(gpu.hasCapability("shader-draw-parameters"), true);
  assert.equal(gpu.hasCapability("non-zero-first-instance"), true);
}

for (const features of [[], ["indirect-first-instance"]]) {
  const gpu = fixture(features, true);
  const caps = assertVanillaAccepted(gpu, `missing indirect imports (${features.join(",") || "no feature"})`);
  assert.equal(caps.draw, false, "a missing indirect glue entry must disable drawIndirect");
  assert.equal(caps.multi, false, "a missing indirect glue entry must disable the terrain batch path");
  const table = gpu.importTable();
  assert.equal(typeof table.drawIndexedIndirect, "function",
    "missing indirect imports remain linkable for WasmGC");
  assert.equal(typeof table.drawIndexedIndirectWithOffsets, "function");
}

{
  // Only one of the two replay entries: CorePass may use either, so neither capability holds.
  const gpu = fixture(["indirect-first-instance"]);
  gpu.drawIndexedIndirectWithOffsets = undefined;
  const caps = assertVanillaAccepted(gpu, "partial indirect imports");
  assert.equal(caps.draw, false);
  assert.equal(caps.multi, false);
}

{
  // A lost device reports nothing, so no indirect combination can be advertised.
  const gpu = fixture(["indirect-first-instance"]);
  gpu.lostReason = "destroyed";
  const caps = assertVanillaAccepted(gpu, "lost device");
  assert.equal(caps.draw, false);
  assert.equal(caps.multi, false);
}

function linkStubs(file, globalName) {
  const text = readFileSync(file, "utf8");
  const start = text.indexOf("  function installGpuImports(imports) {");
  assert.ok(start >= 0, `${file} declares installGpuImports`);
  const end = text.indexOf("\n  }\n", start);
  assert.ok(end > start, `${file} installGpuImports ends`);
  const fn = text.slice(start, end + 4);
  const sandbox = vm.createContext({});
  vm.runInContext(`var ${globalName} = this;\n${fn}\nthis.installGpuImports = installGpuImports;`, sandbox,
    { filename: file.toString() });
  return sandbox;
}

for (const [file, globalName] of [
  [new URL("../wasmgc/gaius-wasm-boot.js", import.meta.url), "g"],
  [new URL("../wasmgc/gaius-wasm-worker.js", import.meta.url), "root"]
]) {
  const sandbox = linkStubs(file, globalName);
  const linked = {};
  sandbox.installGpuImports(linked);
  sandbox.GaiusWebGpu = {
    importTable() {
      return { draw() {} };
    }
  };
  assert.doesNotThrow(() => linked.gaiusGpu.drawIndexedIndirect(1, 0, 1),
    `${file} must fail closed when an older glue has no indirect import`);
}

console.log("WebGPU capability smoke passed: with/without indirect-first-instance, missing imports, fallback imports");
