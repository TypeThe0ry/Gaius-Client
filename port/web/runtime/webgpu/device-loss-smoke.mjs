import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("gpu-glue.js", import.meta.url), "utf8");
let rejectAdapter = false;
let configureCalls = 0;
let prompt = null;
const devices = [];
const context = {
  console: {warn() {}, info() {}, error(...args) { console.error(...args); }},
  navigator: {gpu: {
    async requestAdapter() {
      if (rejectAdapter) return null;
      return {info: {}, features: new Set(), limits: {}, async requestDevice() { return makeDevice(); }};
    },
    getPreferredCanvasFormat() { return "bgra8unorm"; }
  }},
  document: {
    body: {appendChild(element) { prompt = element; }},
    createElement() {
      return {id: "", style: {}, textContent: "", setAttribute() {}, appendChild() {},
        addEventListener() {}, focus() {}};
    },
    getElementById(id) { return prompt && prompt.id === id ? prompt : null; },
    exitPointerLock() {}
  },
  location: {href: "https://example.test/", reload() {}},
  URL, setTimeout, clearTimeout, Promise, Set, WeakMap, Map, Uint8Array, Int32Array, Math, Date,
  performance: {now: () => Date.now()}
};

function makeDevice() {
  let resolveLost;
  const listeners = new Map();
  const device = {
    features: new Set(), limits: {}, buffers: [], textures: [],
    lost: new Promise(resolve => { resolveLost = resolve; }),
    queue: {
      submit() {}, onSubmittedWorkDone() { return Promise.resolve(); },
      writeBuffer(buffer, offset, data, byteOffset = 0, byteLength = data.byteLength) {
        assert.ok(buffer, `queue buffer missing at offset ${offset}`);
        buffer.bytes.set(new Uint8Array(data, byteOffset, byteLength), offset);
      },
      writeTexture(target, data, layout, extent) {
        target.texture.writes.push({target, data: new Uint8Array(data).slice(), layout, extent});
      }
    },
    createBuffer(descriptor) {
      const buffer = {descriptor, bytes: new Uint8Array(descriptor.size), destroy() { this.destroyed = true; }};
      device.buffers.push(buffer);
      return buffer;
    },
    createTexture(descriptor) {
      const texture = {descriptor, writes: [], destroy() { this.destroyed = true; },
        createView(viewDescriptor) { return {texture, viewDescriptor}; }};
      device.textures.push(texture);
      return texture;
    },
    createSampler(descriptor) { return {descriptor}; },
    createCommandEncoder() {
      return {copyBufferToBuffer() {}, finish() { return {}; }};
    },
    createBindGroupLayout(descriptor) { return {descriptor}; },
    createPipelineLayout(descriptor) { return {descriptor}; },
    createShaderModule(descriptor) { return {descriptor}; },
    createRenderPipeline(descriptor) { return {descriptor}; },
    createBindGroup(descriptor) { return {descriptor}; },
    pushErrorScope() {}, popErrorScope() { return Promise.resolve(null); },
    addEventListener(type, listener) {
      const list = listeners.get(type) || [];
      list.push(listener);
      listeners.set(type, list);
    },
    emitError(error) {
      for (const listener of listeners.get("uncapturederror") || []) listener({error});
    },
    destroy() { resolveLost({reason: "destroyed", message: "fault injection"}); }
  };
  devices.push(device);
  return device;
}

const canvas = {width: 320, height: 200, getContext(kind) {
  assert.equal(kind, "webgpu");
  return {configure() { configureCalls++; }, getCurrentTexture() { return {}; }};
}};
context.__gaiusCanvasHost = {current: () => canvas};
vm.createContext(context);
vm.runInContext(source, context, {filename: "gpu-glue.js"});
const gpu = context.GaiusWebGpu;

assert.equal(await gpu.init(canvas), true, "initial device should initialise");
gpu.ringConfigure(256, 256, 1024);
assert.equal(gpu.configureSurface(320, 200), true);
gpu.pipelineMode = "eager";
const buffer = gpu.createBuffer(16, 0x88, "recoverable-buffer");
assert.ok(buffer > 0 && gpu.debugObject(buffer), `buffer handle ${buffer} should resolve`);
gpu.writeBuffer(buffer, 0, Uint8Array.from([11, 22, 33, 44]));
const texture = gpu.createTexture(2, 2, 1, 1, "rgba8unorm", 0x06, "recoverable-texture");
gpu.writeTexture(texture, 0, 0, 0, 0, 2, 2, 8, Uint8Array.from({length: 16}, (_, i) => i + 1));
const view = gpu.createView(texture, 0, 1, false);
const sampler = gpu.createSampler("clamp-to-edge", "clamp-to-edge", "nearest", "nearest", "nearest", 1, 0);
const vs = gpu.createShaderModule("@vertex fn main() -> @builtin(position) vec4f { return vec4f(0.0); }", "smoke-vs");
const fs = gpu.createShaderModule("@fragment fn main() -> @location(0) vec4f { return vec4f(1.0); }", "smoke-fs");
const pipeline = gpu.createPipeline(JSON.stringify({label: "smoke-pipeline", vs, fs, vsEntry: "main", fsEntry: "main",
  buffers: [], targets: [{format: "rgba8unorm", mask: 15}], topology: "triangle-list", cull: false,
  depth: null, layout: []}), "");
gpu.bindTexture(0, view, 1, sampler);
const group = gpu.createBindGroup(pipeline, 0);
assert.ok(group > 0);

gpu.loseDevice();
await Promise.resolve();
await Promise.resolve();
assert.equal(await gpu.whenRecovered(), true, `the fault-injected device must recover in the same page: ${gpu.failure}`);
assert.equal(gpu.state(), 2, "recovered device is ready");
assert.equal(gpu.deviceUsable(), true, "GPU calls resume only after resource reconstruction");
assert.equal(devices.length, 2, "a new device was requested");
assert.deepEqual(Array.from(devices[1].buffers[0].bytes.slice(0, 4)), [11, 22, 33, 44],
  "buffer bytes are restored under the existing Java handle");
assert.equal(devices[1].textures[0].writes.length, 1, "CPU texture uploads are replayed");
assert.equal(gpu.debugObject(buffer), devices[1].buffers[0], "buffer handle identity is stable");
assert.equal(gpu.debugObject(view).texture, devices[1].textures[0], "view is rebound to the restored texture");
assert.ok(gpu.debugObject(pipeline).pipeline, "render pipeline is rebuilt before recovery completes");
assert.ok(gpu.debugObject(group).descriptor.entries.length, "bind group is rebuilt from stable handles");
assert.equal(configureCalls, 2, "the canvas is reconfigured to the recovered device");
assert.equal(prompt, null, "successful recovery does not display a reload prompt");
assert.equal(gpu.stat("deviceLost"), 1);
assert.equal(gpu.stat("deviceRecoveries"), 1);

devices[1].emitError({type: "internal", message: "injected device error"});
await Promise.resolve();
await Promise.resolve();
assert.equal(await gpu.whenRecovered(), true, "an uncaptured error must recover in-session");
assert.equal(devices.length, 3, "an uncaptured error requests a replacement device");
assert.equal(gpu.stat("uncapturedErrors"), 1);
assert.equal(gpu.stat("deviceRecoveries"), 2);

rejectAdapter = true;
devices[2].emitError({type: "internal", message: "adapter reset"});
await Promise.resolve();
await Promise.resolve();
assert.equal(await gpu.whenRecovered(), false, "a reset with no replacement adapter fails closed");
assert.equal(gpu.deviceUsable(), false);
assert.match(gpu.failure, /requestAdapter returned null/);
assert.equal(gpu.stat("deviceRecoveryFailures"), 1);
assert.ok(prompt && prompt.id === "gaius-gpu-lost", "failed recovery reports the existing fallback actions");
assert.equal(gpu.terminate("smoke done"), true);
assert.equal(gpu.liveHandles(), 0, "termination releases all GPU handle records");
console.log("WebGPU device-loss recovery smoke passed: resource replay, stable handles, null-adapter failure");
