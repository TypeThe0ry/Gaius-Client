import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import {runReadbackPackCases} from './gpu-readback-pack-cases.mjs';

const context = vm.createContext({console, performance, Uint8Array, Uint32Array, Int8Array});
vm.runInContext(readFileSync(process.env.GAIUS_WEBGPU_GLUE || new URL('gpu-glue.js', import.meta.url), 'utf8'), context);
const gpu = context.GaiusWebGpu;
const buffers = [];
let computePasses = 0;
let depthCopies = 0;
function aligned(value) { assert.equal(value % 4, 0, `copyBuffer alignment ${value}`); }
const device = {
  // Deliberately small: odd 257-byte rows must split into ordered row bands.
  limits: {maxComputeWorkgroupsPerDimension: 65535, maxStorageBufferBindingSize: 1024,
    maxBufferSize: 1024},
  createBuffer(descriptor) {
    const buffer = {bytes: new Uint8Array(descriptor.size), usage: descriptor.usage,
      getMappedRange() { return this.bytes.buffer; }, unmap() {},
      mapAsync() { return Promise.resolve(); }, destroy() { this.destroyed = true; }};
    buffers.push(buffer);
    return buffer;
  },
  createTexture(descriptor) { return {...descriptor, destroy() {}}; },
  createShaderModule(descriptor) {
    assert.match(descriptor.code, /@compute/);
    return descriptor;
  },
  createComputePipeline() { return {getBindGroupLayout() { return {}; }}; },
  createBindGroup(descriptor) {
    for (const entry of descriptor.entries.slice(0, 2)) {
      assert.ok(entry.resource.buffer.bytes.length <= device.limits.maxStorageBufferBindingSize, 'storage binding size');
    }
    return descriptor;
  },
  createCommandEncoder() {
    const operations = [];
    return {
      copyTextureToBuffer(source, target, extent) {
        assert.equal(target.offset % 4, 0, 'texture copy offset alignment');
        assert.equal(target.bytesPerRow % 256, 0, 'texture copy row alignment');
        if (source.texture.format === 'depth32float') {
          depthCopies++;
          assert.equal(source.origin.x, 0);
          assert.equal(source.origin.y, 0);
          assert.equal(extent.width, source.texture.size.width, 'depth texture copy covers full mip width');
          assert.equal(extent.height, source.texture.size.height, 'depth texture copy covers full mip height');
        }
        operations.push(() => {
          const texel = {r8unorm: 1, rg8unorm: 2, rgba8unorm: 4, depth32float: 4}[source.texture.format];
          for (let row = 0; row < extent.height; row++) {
            const from = ((source.origin.z * source.texture.size.height + source.origin.y + row)
              * source.texture.size.width + source.origin.x) * texel;
            target.buffer.bytes.set(source.texture.bytes.subarray(from, from + extent.width * texel),
              target.offset + row * target.bytesPerRow);
          }
        });
      },
      copyBufferToBuffer(source, from, target, to, length) {
        aligned(from); aligned(to); aligned(length);
        assert.ok(from + length <= source.bytes.length && to + length <= target.bytes.length, 'copy range');
        assert.ok(source.usage & 4, 'source COPY_SRC');
        assert.ok(target.usage & 8, 'target COPY_DST');
        operations.push(() => target.bytes.set(source.bytes.slice(from, from + length), to));
      },
      beginComputePass() {
        computePasses++;
        let group;
        return {setPipeline() {}, setBindGroup(_, value) { group = value; },
          dispatchWorkgroups() {
            const entries = group.entries.map(entry => entry.resource.buffer);
            operations.push(() => {
              // Independent byte-wise reference for the native shader's word packing.
              const [rowBytes, padded, head, length] = new Uint32Array(entries[2].bytes.buffer);
              for (let i = 0; i < length; i++) {
                entries[1].bytes[head + i] = entries[0].bytes[Math.floor(i / rowBytes) * padded + i % rowBytes];
              }
            });
          }, end() {}};
      },
      finish() { return operations; }
    };
  }
};
const queue = {
  writeBuffer(buffer, offset, data, from, size) { buffer.bytes.set(new Uint8Array(data, from, size), offset); },
  writeTexture(target, data) { target.texture.bytes = data.slice(); },
  submit(commands) { for (const list of commands) for (const operation of list) operation(); },
  onSubmittedWorkDone() { return Promise.resolve(); }
};
gpu.device = device;
gpu.queue = queue;
const result = await runReadbackPackCases(gpu, device, queue);
// Force two compute/storage bands while preserving the single full-mip depth texture copy.
const depth = gpu.createTexture(4, 4, 1, 1, 'depth32float', 1, 'depth pack bands');
const depthPixels = Uint8Array.from({length: 64}, (_, i) => (i * 31 + 13) & 255);
gpu.debugObject(depth).bytes = depthPixels;
const depthTarget = gpu.createBuffer(68, 0x0c, 'depth pack destination');
gpu.debugObject(depthTarget).bytes.fill(73);
gpu.copyTextureToBufferRows(depth, 0, 0, 0, 0, 4, 4, 4, depthTarget, 1);
gpu.submit();
assert.equal(depthCopies, 1, 'storage banding must not split depth/stencil texture copies');
assert.deepEqual(gpu.debugObject(depthTarget).bytes.slice(1, 65), depthPixels);
assert.equal(gpu.debugObject(depthTarget).bytes[0], 73);
assert.ok(gpu.debugObject(depthTarget).bytes.slice(65).every(value => value === 73));
gpu.destroyBuffer(depthTarget);
gpu.destroyTexture(depth);
assert.ok(computePasses > 0, 'unaligned packing exercises compute');
assert.ok(buffers.every(buffer => buffer.destroyed), 'all temporary/destination/readback buffers destroyed after submit');
console.log(`WebGPU compact readback smoke passed: ${result.cases} byte-exact cases, ordered overwrite, untouched head/tail, aligned copies, full-mip depth bands`);
