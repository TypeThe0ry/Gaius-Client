import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const counts = {targets: [], formats: [], attachments: [], passes: 0};
const device = {
  queue: {writeBuffer() {}},
  createBuffer(d) { return {size: d.size, destroy() {}}; }, createSampler() { return {}; },
  createTexture(d) { return {format: d.format, createView() { return {}; }, destroy() {}}; },
  createShaderModule() { return {}; },
  createRenderPipeline(d) { counts.targets.push(d.fragment.targets.length); counts.formats.push(d.fragment.targets.map(x => x.format)); return {getBindGroupLayout(i) { return {i}; }}; },
  createBindGroup() { return {}; }
};
const encoder = {beginRenderPass(d) { counts.passes++; counts.attachments.push(d.colorAttachments.length); return {
  setPipeline() {}, setBindGroup() {}, setScissorRect() {}, draw() {}, end() {}
}; }};
const files = {"shaders.properties": "shaderPack=Mock\ncolortex2Format=RGBA16F", "shaders/gbuffers_terrain.vsh": "#version 450\nvoid main(){}",
  "shaders/gbuffers_terrain.fsh": "#version 450\n/* RENDERTARGETS: 0,2,7 */\nvoid main(){}",
  "shaders/deferred.vsh": "#version 450\nvoid main(){}", "shaders/deferred.fsh": "#version 450\nvoid main(){}",
  "shaders/composite.vsh": "#version 450\nvoid main(){}", "shaders/composite.fsh": "#version 450\nvoid main(){}",
  "shaders/final.vsh": "#version 450\nvoid main(){}", "shaders/final.fsh": "#version 450\nvoid main(){}"};
let translated = 0;
const context = vm.createContext({GaiusQuality: {}, GaiusWebGpu: {active: true, device, externalEncoder() { return encoder; },
  debugTextureFormat(t) { return t.format; }, translator: {translate(_b, _t, _o) {
    translated++;
    return {stage: translated % 2 === 1 ? "vertex" : "fragment", bindings: [], wgsl: "x"};
  }}}, __gaiusShaderToolchain: {shadercBegin() { return {}; }, shadercOption() {}, shadercCompile() { return 0; },
  shadercOutputLength() { return 4; }, shadercOutputCopy(_i, a) { a.fill(1); }, shadercErrorMessage() { return ""; }, shadercEnd() {}},
  __gaiusShaderPacks: {selected: "mock", getSelectedFiles() { return Promise.resolve(files); }},
  location: {search: ""}, URLSearchParams, TextEncoder, Uint8Array, ArrayBuffer, Float32Array,
  Map, Object, Number, String, Math, Date, Error, console, performance: {now() { return 1; }}});
context.globalThis = context;
vm.runInContext(readFileSync(new URL("./shader-pack-compat.js", import.meta.url), "utf8"), context);
vm.runInContext(readFileSync(new URL("./shader-pack.js", import.meta.url), "utf8"), context);
const pack = context.GaiusQuality.shaderPack;
const color = {format: "rgba8unorm", createView() { return {}; }};
assert.equal(pack.run({color, width: 64, height: 64}), false, "first frame starts async pack extraction");
await Promise.resolve();
assert.equal(pack.run({color, width: 64, height: 64}), true);
assert.deepEqual(counts.targets, [3, 1, 1, 1]);
assert.deepEqual(counts.attachments, [3, 1, 1, 1]);
assert.equal(Array.from(counts.formats[0]).join(","), "rgba8unorm,rgba16float,rgba8unorm");
assert.equal(counts.passes, 4, "terrain, deferred, composite, final pipeline sequence");
pack.dispose();
console.log("shader-pack-mrt-smoke PASS (mock WebGPU MRT and deferred chain)");
