import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";
import {decodePng, encodeRgbaPng} from "../../../../tools/terrain-visual-metrics.mjs";

const source = readFileSync(new URL("gpu-glue.js", import.meta.url), "utf8");
const context = vm.createContext({
  console, performance, Promise, Set, WeakMap, Map, Uint8Array, Int8Array, Int32Array, Math, Date,
  URL, URLSearchParams,
  btoa(value) { return Buffer.from(value, "binary").toString("base64"); },
});

const pixels = Uint8Array.from([
  1, 2, 3, 255, 4, 5, 6, 255,       // bottom row in the source texture
  9, 10, 11, 255, 12, 13, 14, 255,   // top row in the source texture
]);
const canvasContext = {configure() {}, getCurrentTexture() {
  return {createView() { return {}; }};
}};
const canvas = {width: 2, height: 2, getContext(kind) { return kind === "webgpu" ? canvasContext : null; }};

let pendingCopy = null;
const device = {
  limits: {maxBufferSize: 1 << 20},
  features: new Set(),
  createTexture(descriptor) {
    return {
      ...descriptor,
      format: descriptor.format,
      pixels,
      createView(viewDescriptor) { return {texture: this, viewDescriptor}; },
    };
  },
  createBuffer(descriptor) {
    return {
      bytes: new Uint8Array(descriptor.size),
      mapAsync() { return Promise.resolve(); },
      getMappedRange() { return this.bytes.buffer; },
      unmap() {},
      destroy() {},
    };
  },
  createShaderModule(descriptor) { return descriptor; },
  createRenderPipeline(descriptor) { return {descriptor, getBindGroupLayout() { return {}; }}; },
  createSampler(descriptor) { return descriptor; },
  createBindGroup(descriptor) { return descriptor; },
  createCommandEncoder() {
    return {
      copyTextureToBuffer(source, destination, extent) { pendingCopy = {source, destination, extent}; },
      beginRenderPass() {
        return {setPipeline() {}, setBindGroup() {}, draw() {}, end() {}};
      },
      finish() { return {copy: pendingCopy}; },
    };
  },
};
const queue = {
  submit(commands) {
    for (const command of commands) {
      const copy = command.copy;
      if (!copy) continue;
      const source = copy.source.texture.pixels;
      const rowBytes = copy.extent.width * 4;
      for (let row = 0; row < copy.extent.height; row++) {
        copy.destination.buffer.bytes.set(source.subarray(row * rowBytes, (row + 1) * rowBytes),
          row * copy.destination.bytesPerRow);
      }
    }
  },
};

context.__gaiusCanvasHost = {current: () => canvas};
vm.runInContext(source, context, {filename: "gpu-glue.js"});
const gpu = context.GaiusWebGpu;
gpu.device = device;
gpu.queue = queue;
gpu.status = 2;
gpu.canvasFormat = "rgba8unorm";
assert.equal(gpu.configureSurface(2, 2), true);
assert.equal(gpu.acquireSurface(), true);
const texture = gpu.createTexture(2, 2, 1, 1, "rgba8unorm", 0x06, "surface-source");
const view = gpu.createView(texture, 0, 1, false);
gpu.blitToSurface(view);
const frame = await gpu.captureSurfaceReadback();
assert.equal(frame.source, "webgpu-texture");
const rgba = Buffer.from(frame.rgbaBase64, "base64");
assert.deepEqual([...rgba], [9, 10, 11, 255, 12, 13, 14, 255, 1, 2, 3, 255, 4, 5, 6, 255],
  "GPU surface readback flips the bottom-origin source into PNG order");
const png = encodeRgbaPng(frame.width, frame.height, rgba);
const decoded = decodePng(png);
assert.deepEqual([...decoded.rgba], [...rgba], "PNG encoding preserves GPU readback bytes");
console.log("WebGPU surface readback smoke passed: source texture bytes, row order, PNG output");
