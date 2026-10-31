import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((accept, decline) => {
    resolve = accept;
    reject = decline;
  });
  return { promise, resolve, reject };
}

const source = readFileSync(process.env.GAIUS_WEBGPU_GLUE || new URL("gpu-glue.js", import.meta.url), "utf8");

function fixture() {
  const context = vm.createContext({ console, performance, Uint8Array, Int8Array });
  // Test-only seam: install a fake pass without exposing production state or APIs.
  const testSource = source.replace("  root.GaiusWebGpu = G;\n", "  G.__testSetIndirectPass=function(p){pass=p;currentPipelineValid=true;};\n  G.__testPipeline=function(label){return put({label:label,pipeline:{},valid:true});};\n  G.__testSetPass=function(p){pass=p;currentPipelineValid=true;};\n  G.__testBindGroup=function(label){return put({label:label});};\n  root.GaiusWebGpu = G;\n");
  vm.runInContext(testSource, context, { filename: "gpu-glue.js" });
  const gpu = context.GaiusWebGpu;
  const completions = [];
  const buffers = [];
  const copies = [];
  gpu.queue = {
    submit() {},
    onSubmittedWorkDone() {
      const completion = deferred();
      completions.push(completion);
      return completion.promise;
    }
  };
  gpu.device = {
    // The spec's default limits; every real GPUDevice exposes them.
    limits: { maxStorageBufferBindingSize: 134217728, maxComputeWorkgroupsPerDimension: 65535,
      maxBufferSize: 268435456 },
    createTexture(descriptor) { return { ...descriptor, format: descriptor.format }; },
    createShaderModule(descriptor) { return { ...descriptor }; },
    createComputePipeline(descriptor) { return { ...descriptor, getBindGroupLayout() { return {}; } }; },
    createBindGroup(descriptor) { return { ...descriptor }; },
    createCommandEncoder() {
      return {
        copyTextureToBuffer(...args) { copies.push(args); },
        copyBufferToBuffer() {},
        beginComputePass() {
          return { setPipeline() {}, setBindGroup() {}, dispatchWorkgroups() {}, end() {} };
        },
        finish() { return {}; }
      };
    },
    createBuffer(descriptor) {
      const mapped = deferred();
      const buffer = {
        bytes: new Uint8Array(descriptor.size),
        mapped,
        unmapped: false,
        destroyed: false,
        mapAsync() { return mapped.promise; },
        getMappedRange() { return this.bytes.buffer; },
        unmap() { this.unmapped = true; },
        destroy() { this.destroyed = true; }
      };
      buffers.push(buffer);
      return buffer;
    }
  };
  return { gpu, context, completions, buffers, copies };
}

{
  const { gpu, context } = fixture();
  const calls = [];
  gpu.__testSetIndirectPass({setPipeline(p) { calls.push("pipeline"); },
    drawIndexed() { calls.push("draw"); }});
  context.__gaiusCloudsEnabled = false;
  context.__gaiusCapture = {optionsObserved: {cloudsOff: true}};
  gpu.setPipeline(gpu.__testPipeline("minecraft:pipeline/clouds"));
  gpu.drawIndexed(6, 1, 0, 0, 0);
  assert.deepEqual(calls, ["pipeline", "draw"],
    "the backend executes the vanilla draw stream regardless of capture metadata or label");
}

{
  const { gpu } = fixture();
  const calls = [];
  gpu.__testSetPass({setBindGroup(...args) { calls.push(args); }});
  const group = gpu.__testBindGroup("uniforms");
  gpu.setBindGroup(0, group, 0, 0, 0, 0, 0);
  gpu.setBindGroup(0, group, 0, 0, 0, 0, 0);
  gpu.setBindGroup(0, group, 1, 16, 0, 0, 0);
  gpu.setBindGroup(0, group, 1, 16, 0, 0, 0);
  gpu.setBindGroup(0, group, 1, 32, 0, 0, 0);
  assert.equal(calls.length, 3, "redundant bind groups cross the WebGPU boundary");
  assert.equal(calls[0].length, 2, "zero-offset bind group uses the short WebGPU form");
  assert.equal(calls[1][4], 1, "dynamic bind group preserves offset count");
}

{
  const { gpu } = fixture();
  const calls = [];
  gpu.__testSetPass({setBindGroup(...args) { calls.push(args); }});
  const group = gpu.__testBindGroup("staged");
  gpu.stageOffset(0, 7);
  gpu.setBindGroupStaged(1, group, 1);
  gpu.setBindGroupStaged(1, group, 1);
  gpu.stageOffset(0, 9);
  gpu.setBindGroupStaged(1, group, 1);
  assert.equal(calls.length, 2, "staged bind groups are cached by offsets");
}

{
  const { gpu } = fixture();
  const calls = [];
  gpu.__testSetPass({
    setVertexBuffer(...args) { calls.push(["vertex", args]); },
    setIndexBuffer(...args) { calls.push(["index", args]); },
  });
  const vertex = gpu.createBuffer(64, 0x20, "vertex");
  const index = gpu.createBuffer(64, 0x10, "index");
  gpu.setVertexBuffer(0, vertex, 0, 64);
  gpu.setVertexBuffer(0, vertex, 0, 64);
  gpu.setVertexBuffer(0, vertex, 16, 48);
  gpu.setIndexBuffer(index, false, 0);
  gpu.setIndexBuffer(index, false, 0);
  gpu.setIndexBuffer(index, true, 0);
  assert.deepEqual(calls.map(([kind]) => kind), ["vertex", "vertex", "index", "index"],
    "unchanged vertex/index state is cached while changes are forwarded");
}

for (const method of ["drawIndexedWithOffsets", "drawWithOffsets"]) {
  const { gpu } = fixture();
  const binds = [], draws = [];
  gpu.__testSetPass({
    setBindGroup(group, object, offsets, start, count) {
      binds.push([group, object.label, Array.from(offsets).slice(start, start + count)]);
    },
    drawIndexed(...args) { draws.push(args); },
    draw(...args) { draws.push(args); },
  });
  const a = gpu.__testBindGroup("a"), b = gpu.__testBindGroup("b");
  const draw = (handle, count, offsets) => gpu[method](handle, count, ...offsets, 6, 1, 0, 0, 0);
  const offsets = [16, 32, 48, 64];
  gpu.setBindGroup(0, a, 4, ...offsets);
  binds.length = 0;
  draw(a, 4, offsets);
  draw(a, 4, offsets);
  assert.equal(binds.length, 0, `${method} shares the ordinary setter's shadow`);
  for (let i = 0; i < 4; i++) {
    offsets[i] += 16;
    draw(a, 4, offsets);
    draw(a, 4, offsets);
    assert.equal(binds.length, i + 1, `${method} keys offset ${i}`);
  }
  draw(a, 3, offsets);
  draw(b, 3, offsets);
  draw(b, 3, offsets);
  assert.equal(binds.length, 6, `${method} keys count and handle`);
  gpu.setBindGroup(0, b, 3, ...offsets);
  assert.equal(binds.length, 6, `${method} updates the ordinary setter's shadow`);
  draw(b, 0, offsets);
  draw(b, 0, [0, 0, 0, 0]);
  assert.equal(binds.length, 7, `${method} ignores offsets when count is zero`);
  assert.equal(draws.length, 15, `${method} never skips a draw when a bind is cached`);
  assert.deepEqual(binds[0], [0, "a", [32, 32, 48, 64]], `${method} forwards offsets`);
}

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
}

{
  const { gpu } = fixture();
  const calls = [];
  const fakePass = { drawIndexedIndirect(buffer, offset) { calls.push({buffer, offset}); } };
  const commandBuffer = gpu.createBuffer(128, 0x100, "indirect-test");
  gpu.__testSetIndirectPass(fakePass);
  gpu.drawIndexedIndirect(commandBuffer, 12, 3);
  assert.deepEqual(calls.map(call => call.offset), [12, 32, 52]);
  assert.equal(calls.every(call => call.buffer === gpu.debugObject(commandBuffer)), true);
  assert.equal(gpu.stats.indirectDraws, 3);
  assert.equal(gpu.stats.indirectBatches, 1);
  assert.equal(gpu.stats.draws, 3);
}

{
  const { gpu } = fixture();
  const binds = [], calls = [];
  const fakePass = {
    setBindGroup(...args) { binds.push(args); },
    drawIndexedIndirect(buffer, offset) { calls.push({buffer, offset}); }
  };
  const group = gpu.__testBindGroup("terrain");
  const commandBuffer = gpu.createBuffer(128, 0x100, "fused-indirect-test");
  gpu.__testSetIndirectPass(fakePass);
  gpu.drawIndexedIndirectWithOffsets(group, 2, 16, 32, 0, 0, commandBuffer, 4, 2);
  assert.equal(binds.length, 1, "fused indirect path applies dynamic offsets once");
  assert.deepEqual(Array.from(binds[0][2]).slice(0, 2), [16, 32]);
  assert.deepEqual(calls.map(call => call.offset), [4, 24]);
  assert.equal(gpu.stats.indirectBatches, 1);
  assert.equal(gpu.stats.indirectDraws, 2);
}

{
  const { gpu, completions } = fixture();
  const first = gpu.createFence();
  const sameSubmit = gpu.createFence();
  assert.equal(gpu.fenceStatus(first), 0);
  assert.equal(completions.length, 0);
  gpu.submit();
  assert.equal(completions.length, 1);
  assert.equal(gpu.fenceStatus(first), 0);
  const later = gpu.createFence();
  completions[0].resolve();
  await flush();
  assert.equal(gpu.fenceStatus(first), 1);
  assert.equal(gpu.fenceStatus(sameSubmit), 1);
  assert.equal(gpu.fenceStatus(later), 0);
  gpu.submit();
  assert.equal(completions.length, 2);
  completions[1].resolve();
  await flush();
  assert.equal(gpu.fenceStatus(later), 1);
  gpu.closeFence(first);
  gpu.closeFence(first);
  assert.equal(gpu.fenceStatus(first), -1);
}

{
  const { gpu, completions } = fixture();
  const rejected = gpu.createFence();
  gpu.submit();
  completions[0].reject(new Error("completion rejected"));
  await flush();
  assert.equal(gpu.fenceStatus(rejected), -1);
  const lost = gpu.createFence();
  gpu.submit();
  gpu.lostReason = "device lost";
  completions[1].resolve();
  await flush();
  assert.equal(gpu.fenceStatus(lost), -1);
}

{
  const { gpu, buffers, copies } = fixture();
  const texture = gpu.createTexture(3, 2, 1, 1, "rgba8unorm", 1, "packing");
  const readback = gpu.readbackTexture(texture, 0, 0, 0, 0, 3, 2, 4);
  assert.equal(gpu.readbackPoll(), 0);
  const staging = buffers[0];
  const expected = Uint8Array.from({ length: 24 }, (_, index) => index + 1);
  staging.bytes.fill(255);
  staging.bytes.set(expected.subarray(0, 12), 0);
  staging.bytes.set(expected.subarray(12), 256);
  assert.equal(copies[0][1].bytesPerRow, 256);
  gpu.submit();
  assert.equal(gpu.readbackPoll(), 0);
  staging.mapped.resolve();
  await flush();
  assert.equal(gpu.readbackPoll(), readback);
  const target = new Int8Array(new ArrayBuffer(40), 8, 24);
  assert.equal(gpu.readbackFetch(readback, target), 24);
  assert.deepEqual(new Uint8Array(target.buffer, 8, 24), expected);
  assert.deepEqual(new Uint8Array(target.buffer, 0, 8), new Uint8Array(8));
  assert.equal(staging.unmapped, true);
  assert.equal(staging.destroyed, true);
  assert.equal(gpu.readbackFetch(readback, target), -1);
  assert.equal(gpu.readbackPoll(), 0);
  const failed = gpu.readbackTexture(texture, 0, 0, 0, 0, 3, 2, 4);
  gpu.submit();
  buffers[1].mapped.reject(new Error("mapping rejected"));
  await flush();
  assert.equal(gpu.readbackPoll(), failed);
  const unchanged = new Int8Array(24).fill(37);
  assert.equal(gpu.readbackFetch(failed, unchanged), -1);
  assert.ok(unchanged.every(value => value === 37));
  assert.equal(gpu.stats.readbackFailures, 1);
  assert.equal(buffers[1].destroyed, true);
}

{
  const { gpu, copies } = fixture();
  const cube = gpu.createTexture(4, 4, 6, 2, "rgba8unorm", 1, "cube-readback");
  const target = gpu.createBuffer(128, 0x0c, "cube-target");
  gpu.readbackTexture(cube, 1, 5, 0, 0, 2, 2, 4);
  gpu.copyTextureToBufferRows(cube, 1, 4, 0, 0, 2, 2, 4, target, 0);
  assert.deepEqual(copies.map(copy => copy[0].origin.z), [5, 4],
    "both CPU and GPU readbacks must preserve the requested cube face");
  assert.ok(copies.every(copy => copy[0].mipLevel === 1));
}

{
  const { gpu, copies } = fixture();
  const depth = gpu.createTexture(2, 2, 1, 1, "depth32float", 2, "depth-readback");
  gpu.readbackTexture(depth, 0, 0, 0, 0, 2, 2, 4);
  assert.equal(copies[0][0].aspect, "depth-only",
    "depth texture readback must select the depth aspect even when GPUTexture has no format property");
}

{
  const { gpu, copies, buffers } = fixture();
  for (const [format, texelBytes, aspect] of [["depth32float-stencil8", 4, "depth-only"],
      ["depth16unorm", 2, "depth-only"], ["stencil8", 1, "stencil-only"]]) {
    const texture = gpu.createTexture(3, 2, 1, 1, format, 1, "aspect-readback");
    gpu.readbackTexture(texture, 0, 0, 0, 0, 3, 2, texelBytes);
    assert.equal(copies.at(-1)[0].aspect, aspect);
    const count = buffers.length;
    assert.throws(() => gpu.readbackTexture(texture, 0, 0, 0, 0, 3, 2, texelBytes * 2), /aspect requires/);
    assert.equal(buffers.length, count, "invalid aspect size must fail before allocating staging");
    assert.throws(() => gpu.readbackTexture(texture, 0, 0, 1, 0, 2, 2, texelBytes), /complete mip/);
    assert.throws(() => gpu.readbackTexture(texture, 0, 0, 0, 0, 3, 1, texelBytes), /complete mip/);
    assert.throws(() => gpu.copyTextureToBufferRows(texture, 0, 0, 0, 0, 2, 2, texelBytes, 0, 0), /complete mip/);
    assert.equal(buffers.length, count, "partial depth/stencil readback must fail before allocating staging");
  }
  const depth24 = gpu.createTexture(2, 2, 1, 1, "depth24plus-stencil8", 1, "opaque-depth");
  assert.throws(() => gpu.readbackTexture(depth24, 0, 0, 0, 0, 2, 2, 4), /depth24plus/);
}

{
  const { gpu, buffers } = fixture();
  const texture = gpu.createTexture(2, 2, 1, 1, "rgba8unorm", 1, "readtexels-failure");
  const rejectedTexels = gpu.readTexels(texture, 0, 0, 2, 2);
  buffers[0].mapped.reject(new Error("device lost during texel readback"));
  await rejectedTexels.then(() => assert.fail("readTexels should reject"), error => {
    assert.match(error.message, /device lost/);
  });
  assert.equal(buffers[0].destroyed, true,
    "readTexels must destroy its staging buffer when mapAsync rejects");

  const source = gpu.createBuffer(16, 0x4, "readbuffer-source");
  const rejectedBuffer = gpu.readBuffer(source, 0, 16);
  buffers[2].mapped.reject(new Error("device lost during buffer readback"));
  await rejectedBuffer.then(() => assert.fail("readBuffer should reject"), error => {
    assert.match(error.message, /device lost/);
  });
  assert.equal(buffers[2].destroyed, true,
    "readBuffer must destroy its staging buffer when mapAsync rejects");
}

{
  const { gpu } = fixture();
  gpu.device.limits.maxComputeWorkgroupsPerDimension = 1;
  const texture = gpu.createTexture(1025, 1, 1, 1, "r8unorm", 1, "dispatch-limit");
  const destination = gpu.createBuffer(2048, 0x08, "dispatch-limit-destination");
  assert.throws(() => gpu.copyTextureToBufferRows(texture, 0, 0, 0, 0, 1025, 1, 1,
    destination, 1), /2D dispatch/,
    "compact texture packing must reject a second dispatch dimension above the device limit");
}

// Both compact readback paths (row copies for 4-byte aligned rows, the compute pack otherwise)
// must free every temporary they allocated when queue.submit() throws synchronously.
for (const [label, format, texelBytes, offset] of [["aligned", "rgba8unorm", 4, 0], ["packed", "r8unorm", 1, 1]]) {
  const { gpu, buffers } = fixture();
  const texture = gpu.createTexture(3, 2, 1, 1, format, 1, `compact-submit-failure-${label}`);
  const destination = gpu.createBuffer(32, 0x08, `compact-submit-destination-${label}`);
  const before = buffers.length;
  gpu.copyTextureToBufferRows(texture, 0, 0, 0, 3, 2, texelBytes, destination, offset);
  const temporaries = buffers.slice(before);
  assert.ok(temporaries.length > 0, `${label} compact readback allocates staging`);
  gpu.queue.submit = () => { throw new Error("synchronous submit rejection"); };
  assert.throws(() => gpu.submit(), /synchronous submit rejection/);
  temporaries.forEach((buffer, index) => assert.equal(buffer.destroyed, true,
    `${label} compact readback temporary ${index} must be destroyed when submit throws`));
}

console.log("WebGPU completion smoke passed: fences, rejection, device loss, packed readback, failed mapping, synchronous submit cleanup");
