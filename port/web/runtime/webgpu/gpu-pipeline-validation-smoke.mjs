import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("gpu-glue.js", import.meta.url), "utf8");
let nextValidation = Promise.resolve(null);
const selectedPipelines = [];
const listeners = {};
let draws = 0;
const context = {
  console: {warn() {}, info() {}, error() {}},
  navigator: {gpu: {
    async requestAdapter() {
      return {info: {}, features: new Set(), limits: {}, async requestDevice() { return device; }};
    },
    getPreferredCanvasFormat() { return "rgba8unorm"; }
  }},
  document: {body: {appendChild() {}}, createElement() { return {style: {}, appendChild() {}, setAttribute() {}, addEventListener() {}}; },
    getElementById() { return null; }, exitPointerLock() {}},
  location: {href: "https://example.test/", reload() {}},
  URL, setTimeout, clearTimeout, Promise, Set, WeakMap, Map, Uint8Array, Int32Array, Math, Date,
  performance: {now: () => Date.now()}
};
const device = {
  features: new Set(), limits: {}, lost: new Promise(() => {}),
  queue: {submit() {}, onSubmittedWorkDone() { return Promise.resolve(); }, writeBuffer() {}, writeTexture() {}},
  createBuffer(descriptor) { return {descriptor, destroy() {}, bytes: new Uint8Array(descriptor.size)}; },
  createTexture(descriptor) { return {descriptor, destroy() {}, createView() { return {}; }}; },
  createSampler(descriptor) { return {descriptor}; },
  createCommandEncoder() {
    return {beginRenderPass() { return {
      setPipeline(pipeline) { selectedPipelines.push(pipeline); },
      setScissorRect() {}, setBindGroup() {}, draw() { draws++; }, end() {}
    }; }, finish() { return {}; }};
  },
  createBindGroupLayout(descriptor) { return {descriptor}; },
  createPipelineLayout(descriptor) { return {descriptor}; },
  createShaderModule(descriptor) {
    return {descriptor, getCompilationInfo() {
      return Promise.resolve({messages: descriptor.label === "invalid-fragment"
        ? [{type: "error", lineNum: 1, linePos: 1, message: "non-uniform textureSample"}]
        : []});
    }};
  },
  createRenderPipeline(descriptor) { return {descriptor}; },
  createBindGroup(descriptor) { return {descriptor}; },
  pushErrorScope() {},
  popErrorScope() { return nextValidation; },
  addEventListener(name, listener) { listeners[name] = listener; }, destroy() {}
};
const canvas = {width: 64, height: 64, getContext() { return {configure() {}, getCurrentTexture() { return {}; }}; }};
context.__gaiusCanvasHost = {current: () => canvas};
vm.createContext(context);
vm.runInContext(source, context, {filename: "gpu-glue.js"});
const gpu = context.GaiusWebGpu;
assert.equal(await gpu.init(canvas), true);
listeners.uncapturederror({error: {type: "validation", message: "shader pipeline rejected"}});
assert.equal(gpu.state(), 2, "a validation error leaves the device usable");

const texture = gpu.createTexture(2, 2, 1, 1, "rgba8unorm", 0x10, "validation-target");
const view = gpu.createView(texture, 0, 1, false);
gpu.passColor(0, view, false, 0, 0, 0, 1);
gpu.passBegin(1);
const vs = gpu.createShaderModule("vertex", "validation-vs");
const fs = gpu.createShaderModule("fragment", "validation-fs");
const createPipeline = (label, shaderVs = vs, shaderFs = fs) => gpu.createPipeline(JSON.stringify({label,
  vs: shaderVs, fs: shaderFs, vsEntry: "main", fsEntry: "main",
  buffers: [], targets: [{format: "rgba8unorm", mask: 15}], topology: "triangle-list", cull: false,
  depth: null, layout: []}), "");
const validatePipeline = handle => new Promise(resolve => gpu.awaitPipelineValidation(handle,
  (valid, error) => resolve({valid, error})));

const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return {promise, resolve};
};

const invalidValidation = deferred();
nextValidation = invalidValidation.promise;
const invalidPipeline = createPipeline("invalid-resource-pack-pipeline");
gpu.setPipeline(invalidPipeline);
gpu.draw(3, 1, 0, 0);
assert.equal(selectedPipelines.length, 0, "a pipeline is not bound before its error scope resolves");
assert.equal(draws, 0, "draws using a pending pipeline are skipped");
invalidValidation.resolve({message: "textureSample requires uniform control flow"});
const invalidResult = await validatePipeline(invalidPipeline);
assert.equal(invalidResult.valid, false, "reload validation waits for a rejected pipeline");
assert.match(invalidResult.error, /textureSample requires uniform control flow/);
assert.ok(gpu.pipelineTelemetry().failureDetails.some(x => x.label === "invalid-resource-pack-pipeline"
  && /textureSample requires uniform control flow/.test(x.message)), "pipeline telemetry preserves rejection details");
gpu.setPipeline(invalidPipeline);
gpu.draw(3, 1, 0, 0);
assert.equal(selectedPipelines.length, 0, "a rejected pipeline is never submitted");
assert.equal(draws, 0);

nextValidation = Promise.resolve(null);
const invalidFragment = gpu.createShaderModule("invalid fragment", "invalid-fragment");
const invalidShaderPipeline = createPipeline("invalid-shader-module-pipeline", vs, invalidFragment);
const invalidShaderResult = await validatePipeline(invalidShaderPipeline);
assert.equal(invalidShaderResult.valid, false, "reload validation observes WGSL compilation errors");
assert.match(invalidShaderResult.error, /non-uniform textureSample/);
gpu.setPipeline(invalidShaderPipeline);
gpu.setPipeline(invalidShaderPipeline);
gpu.draw(3, 1, 0, 0);
assert.equal(selectedPipelines.length, 0, "a pipeline with a shader diagnostic error is never submitted");
assert.equal(draws, 0);

nextValidation = Promise.resolve(null);
const validPipeline = createPipeline("valid-pipeline");
gpu.setPipeline(validPipeline);
assert.equal(selectedPipelines.length, 0, "a valid pipeline waits for validation");
const validResult = await validatePipeline(validPipeline);
assert.equal(validResult.valid, true, "reload validation waits for a successful pipeline");
gpu.setPipeline(validPipeline);
gpu.draw(3, 1, 0, 0);
assert.equal(selectedPipelines.length, 1, "a validated pipeline is bound");
assert.equal(draws, 1, "a validated pipeline renders normally");
gpu.passEnd(true);
console.log("WebGPU pipeline validation smoke passed: reload validation awaits pipeline and WGSL results");
