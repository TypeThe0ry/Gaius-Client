import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";

const source = readFileSync(process.env.GPU_GLUE_SOURCE || new URL("gpu-glue.js", import.meta.url), "utf8");

async function fixture({deferRecovery = false, deferDevice = false, deferValidation = false, failRebuild = false} = {}) {
  const devices = [];
  let resolveAdapter;
  let adapterRequests = 0;
  let resolveDevice, resolveValidation, rejectValidation, deviceRequested, validationRequested;
  const devicePending = new Promise(resolve => { deviceRequested = resolve; });
  const validationPending = new Promise(resolve => { validationRequested = resolve; });
  const makeDevice = () => {
    let resolveLost;
    const listeners = new Map();
    const device = {
      features: new Set(), limits: {}, destroyed: false, uploads: [], copies: [],
      lost: new Promise(resolve => { resolveLost = resolve; }),
      queue: {writeBuffer() {}, writeTexture(target, data, layout, extent) {
        device.uploads.push({target, data: Uint8Array.from(data), layout, extent});
      }, submit() {},
        onSubmittedWorkDone: () => Promise.resolve()},
      createBuffer: descriptor => {
        if (failRebuild && devices.length > 1) throw new Error("injected rebuild failure");
        return {descriptor, destroy() {}};
      },
      createTexture: descriptor => ({descriptor, createView: () => ({}), destroy() {}}),
      createCommandEncoder: () => ({copyBufferToTexture(...args) { device.copies.push(args); }, finish: () => ({})}),
      createBindGroupLayout: descriptor => ({descriptor}),
      createPipelineLayout: descriptor => ({descriptor}),
      createShaderModule: descriptor => ({descriptor}),
      createRenderPipeline: descriptor => ({descriptor}),
      pushErrorScope() {}, popErrorScope() {
        if (!deferValidation || devices.length === 1) return Promise.resolve(null);
        validationRequested();
        return new Promise((resolve, reject) => { resolveValidation = resolve; rejectValidation = reject; });
      },
      addEventListener(name, listener) { listeners.set(name, listener); },
      emitError() { listeners.get("uncapturederror")?.({error: {type: "validation", message: "test"}}); },
      destroy() { this.destroyed = true; resolveLost({reason: "destroyed", message: "test"}); },
    };
    devices.push(device);
    return device;
  };
  const adapter = {info: {}, features: new Set(), limits: {}, requestDevice: async () => {
    const device = makeDevice();
    if (!deferDevice || devices.length === 1) return device;
    deviceRequested();
    return new Promise(resolve => { resolveDevice = () => resolve(device); });
  }};
  const canvas = {width: 4, height: 4, getContext: () => ({configure() {}, unconfigure() {}})};
  const context = vm.createContext({console, URL, Uint8Array, Int32Array, Set, Map, WeakMap,
    setTimeout, clearTimeout, performance, location: {href: "https://example.test/"},
    __gaiusCanvasHost: {current: () => canvas},
    navigator: {gpu: {getPreferredCanvasFormat: () => "bgra8unorm", requestAdapter() {
      adapterRequests++;
      if (deferRecovery && adapterRequests > 1) return new Promise(resolve => { resolveAdapter = resolve; });
      return Promise.resolve(adapter);
    }}},
  });
  vm.runInContext(source, context);
  const gpu = context.GaiusWebGpu;
  assert.equal(await gpu.init(canvas), true);
  gpu.ringConfigure(256, 256, 1024);
  assert.equal(gpu.configureSurface(4, 4), true);
  return {context, gpu, devices, releaseAdapter: () => resolveAdapter(adapter),
    devicePending, validationPending, releaseDevice: () => resolveDevice(),
    releaseValidation: error => error === "reject" ? rejectValidation(new Error("late validation rejection")) : resolveValidation(error)};
}

test("texture upload accepts a padded row without trailing padding", async () => {
  const {context, gpu} = await fixture();
  try {
    const buffer = gpu.createBuffer(260, 0x0c, "two rows");
    gpu.writeBuffer(buffer, 0, new Uint8Array(260));
    const texture = gpu.createTexture(1, 2, 1, 1, "rgba8unorm", 0x06, "target");
    context.testBuffer = buffer;
    context.testTexture = texture;
    // A 256-byte row pitch needs 256 + 4 source bytes for two RGBA texels.
    vm.runInContext("GaiusWebGpu.copyBufferToTexture(testBuffer, 0, 256, 2, testTexture, 0, 0, 0, 0, 1, 2)",
      context, {timeout: 200});
  } finally { gpu.terminate("test complete"); }
});

test("termination cannot be undone by a pending adapter request", async () => {
  const {gpu, devices, releaseAdapter} = await fixture({deferRecovery: true});
  try {
    gpu.loseDevice();
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(gpu.recovering, true);
    gpu.terminate("document unloaded");
    releaseAdapter();
    await gpu.whenRecovered();
    assert.equal(gpu.device, null, "late recovery must not publish a new device after termination");
    assert.notEqual(gpu.state(), 2, "terminated glue must stay unavailable");
    assert.ok(devices.slice(1).every(device => device.destroyed), "late replacement devices must be destroyed");
  } finally { for (const device of devices) device.destroy(); }
});

test("error recovery releases the replaced live device", async () => {
  const {gpu, devices} = await fixture();
  try {
    devices[0].emitError();
    assert.equal(await gpu.whenRecovered(), true);
    assert.equal(devices[0].destroyed, true, "a replaced device must not remain live");
  } finally { gpu.terminate("test complete"); for (const device of devices) device.destroy(); }
});

for (const [format, texelBytes, width, height, offset] of [
  ["rgba8unorm", 4, 1, 2, 0], ["r8unorm", 1, 3, 3, 4],
  ["rgba16float", 8, 2, 2, 65528], ["rgba8unorm", 4, 1, 1, 0],
]) {
  test(`padded upload replays exact texels: ${format} ${width}x${height} at ${offset}`, async () => {
    const {gpu, devices} = await fixture();
    try {
      const rowBytes = width * texelBytes, pitch = 256;
      const coveredEnd = offset + (height - 1) * pitch + rowBytes;
      const size = Math.ceil(coveredEnd / 4) * 4;
      const bytes = Uint8Array.from({length: size}, (_, i) => (i * 13 + 7) & 255);
      const buffer = gpu.createBuffer(size, 0x0c, "exact upload extent");
      gpu.writeBuffer(buffer, 0, bytes);
      const texture = gpu.createTexture(width, height, 1, 1, format, 0x06, "exact texture");
      gpu.copyBufferToTexture(buffer, offset, pitch, height, texture, 0, 0, 0, 0, width, height);
      assert.equal(devices[0].copies.length, 1, "the native upload must still be encoded");
      gpu.loseDevice();
      await Promise.resolve();
      assert.equal(await gpu.whenRecovered(), true, gpu.failure);
      assert.equal(devices[1].uploads.length, 1);
      const upload = devices[1].uploads[0];
      assert.deepEqual(upload.data, bytes.slice(offset, coveredEnd), "replay retains the exact covered byte range");
      for (let row = 0; row < height; row++) {
        assert.deepEqual(upload.data.slice(row * upload.layout.bytesPerRow, row * upload.layout.bytesPerRow + rowBytes),
          bytes.slice(offset + row * pitch, offset + row * pitch + rowBytes));
      }
    } finally { gpu.terminate("test complete"); }
  });
}

test("out-of-bounds shadow reads fail closed without looping", async () => {
  const {context, gpu} = await fixture();
  try {
    context.testBuffer = gpu.createBuffer(260, 0x0c, "short buffer");
    gpu.writeBuffer(context.testBuffer, 0, new Uint8Array(260));
    context.testTexture = gpu.createTexture(2, 2, 1, 1, "rgba8unorm", 0x06, "unreplayable texture");
    vm.runInContext("GaiusWebGpu.copyBufferToTexture(testBuffer, 0, 256, 2, testTexture, 0, 0, 0, 0, 2, 2)",
      context, {timeout: 1000});
    gpu.loseDevice();
    await Promise.resolve();
    assert.equal(await gpu.whenRecovered(), false, "an invalid upload cannot become a partial recovery image");
    assert.match(gpu.failure, /no recovery uploads/);
  } finally { gpu.terminate("test complete"); }
});

test("termination destroys a device arriving from a pending requestDevice", async () => {
  const {gpu, devices, devicePending, releaseDevice} = await fixture({deferDevice: true});
  try {
    gpu.loseDevice();
    await devicePending;
    gpu.terminate("document unloaded");
    releaseDevice();
    assert.equal(await gpu.whenRecovered(), false);
    assert.equal(gpu.device, null);
    assert.equal(gpu.recovering, false);
    assert.equal(devices[1].destroyed, true);
    assert.equal(gpu.stat("deviceRecoveryFailures"), 0, "teardown is not a recovery failure");
  } finally { gpu.terminate("test complete"); for (const device of devices) device.destroy(); }
});

for (const rejectValidation of [false, true, "reject"]) {
  test(`termination during pipeline rebuild ignores late validation (error=${rejectValidation})`, async () => {
    const {gpu, devices, validationPending, releaseValidation} = await fixture({deferValidation: true});
    try {
      gpu.pipelineMode = "eager";
      const vs = gpu.createShaderModule("@vertex fn main() -> @builtin(position) vec4f { return vec4f(0.0); }", "vs");
      const fs = gpu.createShaderModule("@fragment fn main() -> @location(0) vec4f { return vec4f(1.0); }", "fs");
      gpu.createPipeline(JSON.stringify({label: "pipeline", vs, fs, vsEntry: "main", fsEntry: "main",
        buffers: [], targets: [{format: "rgba8unorm", mask: 15}], topology: "triangle-list", cull: false,
        depth: null, layout: []}), "");
      gpu.loseDevice();
      await validationPending;
      gpu.terminate("document unloaded");
      releaseValidation(rejectValidation === "reject" ? "reject" : rejectValidation ? {message: "late validation error"} : null);
      assert.equal(await gpu.whenRecovered(), false);
      assert.equal(gpu.device, null);
      assert.notEqual(gpu.state(), 2);
      assert.equal(devices[1].destroyed, true);
      assert.equal(gpu.stat("deviceRecoveries"), 0);
      assert.equal(gpu.stat("deviceRecoveryFailures"), 0);
    } finally { gpu.terminate("test complete"); for (const device of devices) device.destroy(); }
  });
}

test("failed resource rebuild destroys the unpublished replacement", async () => {
  const {gpu, devices} = await fixture({failRebuild: true});
  try {
    gpu.createBuffer(4, 0x0c, "rebuild failure");
    gpu.loseDevice();
    await Promise.resolve();
    assert.equal(await gpu.whenRecovered(), false);
    assert.equal(devices[1].destroyed, true);
    assert.equal(gpu.device, null);
    assert.equal(gpu.stat("deviceRecoveryFailures"), 1);
  } finally { gpu.terminate("test complete"); for (const device of devices) device.destroy(); }
});

test("repeated validation recovery releases old devices and ignores their callbacks", async () => {
  const {gpu, devices} = await fixture();
  try {
    for (let i = 0; i < 3; i++) {
      devices[i].emitError();
      assert.equal(await gpu.whenRecovered(), true, gpu.failure);
      assert.equal(devices[i].destroyed, true);
      assert.equal(devices[i + 1].destroyed, false);
      const attempts = gpu.stat("deviceRecoveryAttempts");
      devices[i].emitError();
      devices[i].destroy();
      await Promise.resolve();
      assert.equal(gpu.device, devices[i + 1]);
      assert.equal(gpu.stat("deviceRecoveryAttempts"), attempts);
      assert.equal(gpu.recovering, false);
    }
    assert.equal(gpu.stat("deviceRecoveries"), 3);
  } finally { gpu.terminate("test complete"); }
});

test("failed validation recovery releases both devices", async () => {
  const {gpu, devices} = await fixture({failRebuild: true});
  try {
    gpu.createBuffer(4, 0x0c, "rebuild failure");
    devices[0].emitError();
    assert.equal(await gpu.whenRecovered(), false);
    assert.equal(devices.length, 2);
    assert.ok(devices.every(device => device.destroyed));
    assert.equal(gpu.device, null);
  } finally { gpu.terminate("test complete"); }
});
