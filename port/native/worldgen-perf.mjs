import assert from "node:assert/strict";
import {readFileSync, readdirSync} from "node:fs";
import {join} from "node:path";
import {performance} from "node:perf_hooks";

const [wasmPath, jobsDirectory, roundsArgument = "5", repeatsArgument = "2"] = process.argv.slice(2);
assert(wasmPath && jobsDirectory, "usage: worldgen-perf.mjs <wasm> <jobs-dir> [rounds] [repeats]");
const rounds = Number(roundsArgument);
const repeats = Number(repeatsArgument);
assert(Number.isInteger(rounds) && rounds > 0 && Number.isInteger(repeats) && repeats > 0);
const module = new WebAssembly.Module(readFileSync(wasmPath));
assert.equal(WebAssembly.Module.imports(module).length, 0);
const kernel = new WebAssembly.Instance(module).exports;

function invoke(job, name, copy) {
  const pointer = kernel.alloc(job.length);
  new Uint8Array(kernel.memory.buffer, pointer, job.length).set(job);
  let descriptor;
  try {
    descriptor = kernel[name](pointer, job.length);
    const view = new DataView(kernel.memory.buffer);
    const status = view.getUint32(descriptor, true);
    const resultPointer = view.getUint32(descriptor + 4, true);
    const length = view.getUint32(descriptor + 8, true);
    const result = new Uint8Array(kernel.memory.buffer, resultPointer, length);
    assert.equal(status, 0, status ? new TextDecoder().decode(result) : name);
    return copy ? Buffer.from(result) : length;
  } finally {
    if (descriptor) kernel.release(descriptor);
    kernel.dealloc(pointer, job.length);
  }
}

invoke(readFileSync(join(jobsDirectory, "load.job")), "run_load_generator", false);
console.log("path\tstage\tround\tjobs\tmilliseconds\tms_per_chunk");
for (const stage of ["biomes", "terrain", "surface", "combined"]) {
  const name = stage === "combined" ? "run_terrain" : "run_" + stage;
  const jobs = readdirSync(jobsDirectory).filter(file => file.startsWith(stage + "-") && file.endsWith(".job"))
    .sort().map(file => ({bytes: readFileSync(join(jobsDirectory, file)), file}));
  assert(jobs.length > 0);
  for (const job of jobs) {
    assert.deepEqual(invoke(job.bytes, name, true), readFileSync(join(jobsDirectory, job.file.replace(/\.job$/, ".expected"))), job.file);
  }
  for (let round = 0; round <= rounds; round++) {
    const started = performance.now();
    for (let repeat = 0; repeat < repeats; repeat++) for (const job of jobs) invoke(job.bytes, name, false);
    const elapsed = performance.now() - started;
    if (round > 0) {
      const count = jobs.length * repeats;
      console.log(["framed", stage, round, count, elapsed.toFixed(6), (elapsed / count).toFixed(6)].join("\t"));
    }
  }
  for (const job of jobs) {
    assert.deepEqual(invoke(job.bytes, name, true), readFileSync(join(jobsDirectory, job.file.replace(/\.job$/, ".expected"))), job.file);
  }
}
console.error("exact native/wasm parity: " + wasmPath + "; memory=" + kernel.memory.buffer.byteLength);
