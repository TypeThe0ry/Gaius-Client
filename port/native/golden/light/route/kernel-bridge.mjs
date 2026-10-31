#!/usr/bin/env node
// Light route harness: runs framed light_column jobs through one shipped light kernel module
// (kernels/light.<variant>.wasm of a dist, checked against its kernels.json) for the JVM
// LightKernelHost stand-in. One instance serves every job of a run, as a kernel worker does, so
// the light table is spliced in only when that instance asks for it.
//
//   node kernel-bridge.mjs --dist <dist> --variant simd|baseline
//
// stdin: u32 length + job bytes, repeated; stdout: u32 status + u32 length + result bytes.
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {readFileSync} from "node:fs";
import {join, resolve} from "node:path";

const args = process.argv.slice(2);
const option = (name) => args.includes(name) ? args[args.indexOf(name) + 1] : null;
const dist = resolve(option("--dist") ?? "");
const variant = option("--variant");
assert(option("--dist") && ["simd", "baseline"].includes(variant), "usage: kernel-bridge.mjs --dist <dist> --variant simd|baseline");
const kernels = join(dist, "kernels");
const metadata = JSON.parse(readFileSync(join(kernels, "kernels.json"))).kernels.light.variants[variant];
const moduleBytes = readFileSync(join(kernels, `light.${variant}.wasm`));
assert.equal(metadata.file || metadata.path, `light.${variant}.wasm`);
assert.equal(metadata.bytes, moduleBytes.length, "wasm manifest size");
assert.equal(metadata.sha256, createHash("sha256").update(moduleBytes).digest("hex"), "wasm manifest digest");
const {exports} = new WebAssembly.Instance(new WebAssembly.Module(moduleBytes), {});
assert.equal(exports.gaius_abi_version(), 1);

function run(input) {
  const pointer = exports.alloc(input.length) >>> 0;
  new Uint8Array(exports.memory.buffer, pointer, input.length).set(input);
  const descriptor = exports.run_light_column(pointer, input.length) >>> 0;
  const view = new DataView(exports.memory.buffer);
  const status = view.getUint32(descriptor, true);
  const output = Buffer.from(new Uint8Array(exports.memory.buffer, view.getUint32(descriptor + 4, true),
    view.getUint32(descriptor + 8, true)));
  exports.release(descriptor);
  exports.dealloc(pointer, input.length);
  return {status, output};
}

let pending = Buffer.alloc(0);
process.stdin.on("data", (chunk) => {
  pending = Buffer.concat([pending, chunk]);
  while (pending.length >= 4 && pending.length >= 4 + pending.readUInt32LE(0)) {
    const length = pending.readUInt32LE(0);
    const job = new Uint8Array(pending.subarray(4, 4 + length));
    pending = pending.subarray(4 + length);
    const {status, output} = run(job);
    const header = Buffer.alloc(8);
    header.writeUInt32LE(status, 0);
    header.writeUInt32LE(output.length, 4);
    process.stdout.write(Buffer.concat([header, output]));
  }
});
