#!/usr/bin/env node
// Contract smoke for the opt-in shader-pack prototype.  It uses a recording WebGPU device so the
// test can distinguish the default-off path from the three-pass path without a TeaVM build.
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("./shader-pack.js", import.meta.url), "utf8");
const compatSource = readFileSync(new URL("./shader-pack-compat.js", import.meta.url), "utf8");

function harness(query) {
  const counts = {textures: 0, buffers: 0, modules: 0, pipelines: 0, passes: 0, draws: 0, compiles: 0, translates: 0};
  const device = {
    queue: {writeBuffer() {}},
    createBuffer(desc) { counts.buffers++; return {size: desc.size, destroy() {}}; },
    createSampler() { return {}; },
    createTexture(desc) {
      counts.textures++;
      return {format: desc.format, createView() { return {}; }, destroy() {}};
    },
    createShaderModule() { counts.modules++; return {}; },
    createRenderPipeline() {
      counts.pipelines++;
      return {getBindGroupLayout(index) { return {index}; }};
    },
    createBindGroup() { return {}; },
  };
  const encoder = {
    beginRenderPass() {
      counts.passes++;
      return {setPipeline() {}, setBindGroup() {}, setScissorRect() {}, draw() {counts.draws++;}, end() {}};
    }
  };
  const color = {format: "rgba8unorm", createView() { return {}; }};
  const shaderc = {
    shadercBegin() { return {}; },
    shadercOption() {},
    shadercCompile() { counts.compiles++; return 0; },
    shadercOutputLength() { return 4; },
    shadercOutputCopy(_id, out) { out.fill(7); },
    shadercErrorMessage() { return ""; },
    shadercEnd() {},
  };
  const gpu = {
    active: true, device, translator: {translate(_bytes, _texels, options) {
      counts.translates++;
      const stage = counts.translates % 2 === 0 ? "fragment" : "vertex";
      return {stage, bindings: stage === "fragment" ? [
        {group: 0, binding: 1, type: "uniform", size: 16},
        {group: 1, binding: 0, type: "texture"},
        {group: 1, binding: 1, type: "sampler"}
      ] : [], wgsl: "@vertex fn main() -> @builtin(position) vec4f { return vec4f(0.0); }"};
    }},
    externalEncoder() { return encoder; },
    debugTextureFormat(texture) { return texture.format; },
  };
  const context = vm.createContext({
    GaiusQuality: {}, GaiusWebGpu: gpu, __gaiusShaderToolchain: shaderc,
    location: {search: query}, URLSearchParams, TextEncoder, Uint8Array, ArrayBuffer,
    Float32Array, Map, Object, Number, String, Math, Date, Error, console,
    performance: {now: () => 1},
  });
  context.globalThis = context;
  vm.runInContext(compatSource, context, {filename: "shader-pack-compat.js"});
  vm.runInContext(source, context, {filename: "shader-pack.js"});
  return {pack: context.GaiusQuality.shaderPack, counts, color, gpu};
}

const off = harness("");
assert.equal(off.pack.enabled(), false);
assert.equal(off.pack.run({color: off.color, width: 64, height: 64}), false);
assert.deepEqual(off.counts, {textures: 0, buffers: 0, modules: 0, pipelines: 0, passes: 0, draws: 0, compiles: 0, translates: 0},
  "disabled shader packs must not allocate or compile");

const on = harness("?gpu=webgpu&gaiusShaders=1");
assert.equal(on.pack.enabled(), true);
assert.equal(on.pack.run({color: on.color, width: 64, height: 64}), true);
assert.equal(on.counts.compiles, 6, "terrain/composite/final vertex and fragment stages compile");
assert.equal(on.counts.translates, 6, "every compiled stage is translated to WGSL");
assert.equal(on.counts.passes, 3, "the compatibility chain records three fullscreen passes");
assert.equal(on.counts.draws, 3);
assert.equal(on.pack.stats.initialized, true);
assert.deepEqual(Array.from(on.pack.stats.lastStages), ["gbuffers_terrain", "composite", "final"]);
on.pack.dispose();
assert.equal(on.pack.stats.initialized, false);
console.log("shader-pack-smoke PASS (default-off and three-pass WebGPU path)");
