#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const worldgen = await readFile(new URL("../web/kernels/worldgen-job.js", import.meta.url), "utf8");
const noise = await readFile(new URL("../web/kernels/noise-job.js", import.meta.url), "utf8");
const context = vm.createContext({
  TextEncoder, Uint8Array, Float64Array, ArrayBuffer, SharedArrayBuffer, DataView, BigInt,
  Math, Number, String, Object, JSON, crossOriginIsolated: false,
});
context.globalThis = context;
vm.runInContext(worldgen, context, {filename: "worldgen-job.js"});
vm.runInContext(noise, context, {filename: "noise-job.js"});

const gen = context.GaiusWorldgenJob.generator(7, new Uint8Array([1, 2, 3]), {inlineJobs: 1});
const encode = () => context.GaiusWorldgenJob.encodeBiomes(gen, 0, 0, 1, true);
assert.equal(Object.prototype.toString.call(encode()), "[object ArrayBuffer]");

context.crossOriginIsolated = true;
const shared = encode();
assert.equal(Object.prototype.toString.call(shared), "[object SharedArrayBuffer]");
const noiseJob = context.GaiusNoiseJob.noisePoints({
  semantics: {code: 1}, seed: "42", fork: "minecraft:temperature",
  noise: {type: "normal", firstOctave: 0, amplitudes: [1]},
  xyz: new Float64Array([0, 0, 0]),
}, 2);
assert.equal(Object.prototype.toString.call(noiseJob), "[object SharedArrayBuffer]");
console.log("WORLDGEN_JOB_SAB_OK");
