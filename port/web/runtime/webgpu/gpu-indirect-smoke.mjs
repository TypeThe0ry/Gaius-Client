import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("gpu-glue.js", import.meta.url), "utf8");

function fixture(search = "", pipelineFactory = null) {
  const query = new URLSearchParams(search);
  const context = vm.createContext({
    console,
    performance,
    Uint8Array,
    Uint32Array,
    Float32Array,
    URLSearchParams,
    location: {search},
    __gaiusSwitches: {
      gaiusGpuIndirect: query.get("gaiusGpuIndirect") === "1",
      gaiusGpuIndirectHiZ: query.get("gaiusGpuIndirectHiZ") === "1",
      gaiusGpuIndirectParity: query.get("gaiusGpuIndirectParity") === "1",
      gaiusGpuIndirectReadback: query.get("gaiusGpuIndirectReadback") === "1"
    }
  });
  vm.runInContext(source, context, {filename: "gpu-glue.js"});
  const gpu = context.GaiusWebGpu;
  const computeCalls = [];
  let nextBuffer = 1;
  gpu.device = {
    limits: {maxComputeWorkgroupsPerDimension: 65535, maxStorageBufferBindingSize: 1 << 27,
      maxBufferSize: 1 << 26},
    features: new Set(),
    createShaderModule(descriptor) { return descriptor; },
    createComputePipelineAsync(descriptor) {
      return pipelineFactory ? pipelineFactory(descriptor)
        : Promise.resolve({...descriptor, getBindGroupLayout() { return {}; }});
    },
    createBindGroup(descriptor) { return descriptor; },
    createCommandEncoder() {
      return {
        beginComputePass(descriptor) {
          const call = {descriptor, dispatch: null, ended: false};
          computeCalls.push(call);
          return {
            setPipeline(pipeline) { call.pipeline = pipeline; },
            setBindGroup(index, group) { call.bindGroup = [index, group]; },
            dispatchWorkgroups(groups) { call.dispatch = groups; },
            end() { call.ended = true; }
          };
        },
        finish() { return {}; }
      };
    },
    createBuffer(descriptor) { return {size: descriptor.size, id: nextBuffer++}; },
    createTexture(descriptor) {
      return {...descriptor, createView() { return {texture: this}; }};
    },
    createSampler(descriptor) { return descriptor; }
  };
  gpu.queue = {writeBuffer() {}, submit() {}};
  gpu.status = 2;
  return {context, gpu, computeCalls};
}

{
  const {gpu} = fixture();
  assert.equal(gpu.gpuIndirectState().requested, false, "GPU indirect is default-off");
  assert.equal(typeof gpu.importTable().gpuIndirectEncodeFrustum, "function",
    "the WebAssembly import table exposes the frustum entry point");
  assert.equal(gpu.gpuIndirectEncodeFrustum(1, 2, 3, 4, 64), false,
    "default-off path falls back before touching a command encoder");
}

{
  const {gpu, computeCalls} = fixture("?gaiusGpuIndirect=1");
  const input = gpu.createBuffer(20 * 128, 0x18c, "input-records");
  const bounds = gpu.createBuffer(32 * 128, 0x80, "bounds");
  const planes = gpu.createBuffer(96, 0x80, "planes");
  const output = gpu.createBuffer(20 * 128, 0x18c, "output-records");
  assert.equal(gpu.gpuIndirectEncodeFrustum(input, bounds, planes, output, 65), false,
    "frustum waits for asynchronous pipeline validation");
  assert.equal(gpu.gpuIndirectState().pipelinePending, true,
    "state reports the asynchronous compute pipeline while validation is pending");
  await Promise.resolve();
  assert.equal(gpu.gpuIndirectEncodeFrustum(input, bounds, planes, output, 65), true,
    "frustum dispatch is encoded after asynchronous pipeline validation");
  assert.equal(computeCalls.length, 1);
  assert.equal(computeCalls[0].dispatch, 2, "frustum dispatch uses 64-record workgroups");
  assert.equal(computeCalls[0].ended, true);
  assert.equal(computeCalls[0].bindGroup[1].entries[1].resource.size, 65 * 32,
    "exact bounds binding length guards tail invocations");
  assert.equal(gpu.gpuIndirectState().pipelinePending, false,
    "state refreshes after asynchronous pipeline validation");
  assert.match(gpu.gpuIndirectShaderSources().frustum, /record >= arrayLength\(&inputRecords\) \/ 5u/,
    "frustum shader guards the tail invocation");
  assert.equal(gpu.gpuIndirectState().enabled, true);
  const draws = [];
  gpu.drawIndexedIndirect = (handle, offset, count) => draws.push([handle, offset, count]);
  assert.equal(gpu.gpuIndirectDrawIndexed(output, input, 0, 65), true,
    "generated commands are selected only after their dispatch is recorded");
  assert.equal(gpu.gpuIndirectDrawIndexed(output, input, 20, 65), false,
    "records beyond the range written by the dispatch fall back to the complete source stream");
  gpu.submitCount++;
  assert.equal(gpu.gpuIndirectDrawIndexed(output, input, 0, 65), false,
    "a generated stream from an earlier submit is stale and falls back");
  assert.deepEqual(draws, [[output, 0, 65], [input, 20, 65], [input, 0, 65]]);
}

{
  const {gpu} = fixture("?gaiusGpuIndirect=1");
  const generated = gpu.createBuffer(20, 0x180, "aliased-generated");
  const calls = [];
  gpu.drawIndexedIndirect = (handle, offset, count) => calls.push([handle, offset, count]);
  assert.equal(gpu.gpuIndirectDrawIndexed(generated, generated, 0, 1), false,
    "generated and fallback aliases must fail closed");
  assert.deepEqual(calls, [], "aliased fallback is not a valid draw source");
}

{
  const {gpu} = fixture("?gaiusGpuIndirect=1", () => Promise.reject(new Error("validation failed")));
  const input = gpu.createBuffer(20, 0x180, "source-record");
  const bounds = gpu.createBuffer(32, 0x80, "bounds");
  const planes = gpu.createBuffer(96, 0x80, "planes");
  const output = gpu.createBuffer(20, 0x180, "output-record");
  assert.equal(gpu.gpuIndirectEncodeFrustum(input, bounds, planes, output, 1), false,
    "unvalidated compute pipeline keeps the original path");
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(gpu.gpuIndirectState().frustumCapability, false,
    "pipeline validation rejection disables the compute capability");
  assert.equal(gpu.gpuIndirectEncodeFrustum(input, bounds, planes, output, 1), false,
    "a rejected compute pipeline never publishes generated commands");
}

{
  const {gpu, computeCalls} = fixture("?gaiusGpuIndirect=1&gaiusGpuIndirectHiZ=1");
  assert.equal(gpu.gpuIndirectEncodeHiZ(1, 2, 3, 4, 5, 6, 64), false,
    "unproven Hi-Z culling fails closed");
  assert.equal(computeCalls.length, 0);
  assert.match(gpu.gpuIndirectShaderSources().hiz, /previous frame|depthPyramid|texture_2d/,
    "Hi-Z shader uses a depth pyramid and remains inspectable in the smoke harness");
  assert.equal(gpu.gpuIndirectState().hizCapability, true);
}

{
  const {gpu} = fixture("?gaiusGpuIndirect=1");
  gpu.gpuIndirectEncodeFrustum = undefined;
  assert.equal(gpu.hasCapability("gaius-gpu-indirect-frustum"), false,
    "the feature is unavailable when the Wasm import is missing");
}

{
  const {gpu} = fixture();
  const bounds = new Float32Array([
    0, 0, 0, 0, 1, 1, 1, 0,
    20, 0, 0, 0, 1, 1, 1, 0,
    20, 0, 0, 0, 1, 1, 1, 1
  ]);
  const planes = new Float32Array([
    1, 0, 0, 10, -1, 0, 0, 10, 0, 1, 0, 10,
    0, -1, 0, 10, 0, 0, 1, 10, 0, 0, -1, 10
  ]);
  assert.deepEqual(Array.from(gpu.gpuIndirectConservativeCandidates(bounds, planes, 3)), [0, 2],
    "frustum reference keeps an uncertain record and rejects only a definite outside record");
  const invalidPlanes = new Float32Array(planes);
  invalidPlanes[0] = Number.NaN;
  assert.deepEqual(Array.from(gpu.gpuIndirectConservativeCandidates(bounds, invalidPlanes, 3)), [0, 1, 2],
    "an invalid frustum keeps every candidate");
}

{
  const {gpu} = fixture();
  const ok = gpu.gpuIndirectParity({
    cpuVisible: [1, 3], gpuCandidates: [0, 1, 2, 3],
    cpuFinalRecords: new Uint8Array([1, 2]), gpuFinalRecords: new Uint8Array([1, 2]),
    cpuDrawCount: 2, gpuDrawCount: 2,
    cpuQuads: 4, gpuQuads: 4, cpuIndices: 12, gpuIndices: 12,
    cpuTranslucentOrder: [3, 1], gpuTranslucentOrder: [3, 1]
  });
  assert.equal(ok.ok, true);
  const mismatch = gpu.gpuIndirectParity({
    cpuVisible: [1], gpuCandidates: [1],
    cpuFinalRecords: new Uint8Array([1]), gpuFinalRecords: new Uint8Array([2]),
    cpuDrawCount: 1, gpuDrawCount: 2,
    cpuQuads: 1, gpuQuads: 2, cpuIndices: 3, gpuIndices: 6,
    cpuTranslucentOrder: [1], gpuTranslucentOrder: [1]
  });
  assert.equal(mismatch.fallback, true, "parity mismatch selects vanilla record fallback");
  assert.throws(() => gpu.gpuIndirectParity({
    cpuVisible: [1], gpuCandidates: [], assert: true
  }), /GPU indirect parity mismatch/);
}

{
  const {gpu} = fixture("?gaiusGpuIndirect=1");
  const input = gpu.createBuffer(20 * 1, 0x100, "missing-storage");
  const bounds = gpu.createBuffer(32, 0x80, "bounds");
  const planes = gpu.createBuffer(96, 0x80, "planes");
  const output = gpu.createBuffer(20, 0x180, "output");
  assert.equal(gpu.gpuIndirectEncodeFrustum(input, bounds, planes, output, 1), false,
    "storage usage is required before compute encoding");
}

{
  const {gpu} = fixture("?gaiusGpuIndirect=1");
  const input = gpu.createBuffer(20, 0x180, "input");
  const bounds = gpu.createBuffer(32, 0x80, "bounds");
  const planes = gpu.createBuffer(96, 0x80, "planes");
  assert.equal(gpu.gpuIndirectEncodeFrustum(input, bounds, planes, 0, 1), false,
    "an invalid generated handle cannot publish a compute result");
}

{
  const {gpu} = fixture();
  const calls = [];
  const generated = gpu.createBuffer(40, 0x180, "generated-records");
  const fallback = gpu.createBuffer(52, 0x100, "fallback-records");
  gpu.drawIndexedIndirect = (handle, offset, count) => calls.push([handle, offset, count]);
  assert.equal(gpu.gpuIndirectDrawIndexed(generated, fallback, 12, 2), false,
    "draw helper uses the original record stream while the switch is off");
  assert.deepEqual(calls, [[fallback, 12, 2]]);
}

{
  const {gpu} = fixture("?gaiusGpuIndirect=1&gaiusGpuIndirectParity=1");
  const generated = gpu.createBuffer(20, 0x180, "generated-records");
  const fallback = gpu.createBuffer(20, 0x100, "fallback-records");
  const calls = [];
  gpu.drawIndexedIndirect = (handle, offset, count) => calls.push([handle, offset, count]);
  assert.equal(gpu.gpuIndirectDrawIndexed(generated, fallback, 0, 1), false,
    "parity mode with no paired evidence keeps the vanilla record stream");
  assert.deepEqual(calls, [[fallback, 0, 1]]);
}

console.log("GPU indirect smoke passed: fail-closed switches, bounded frustum dispatch, stale-output fallback, parity checks");
