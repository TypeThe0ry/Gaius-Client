import assert from "node:assert/strict";
import {readFileSync, readdirSync} from "node:fs";
import {join} from "node:path";
import {performance} from "node:perf_hooks";

const [beforePath, afterPath, jobsDirectory, roundsArgument = "21", repeatsArgument = "6"] = process.argv.slice(2);
assert(beforePath && afterPath && jobsDirectory, "usage: worldgen-perf-paired.mjs <before-wasm> <after-wasm> <jobs-dir> [rounds] [repeats]");
const rounds = Number(roundsArgument);
const repeats = Number(repeatsArgument);
assert(Number.isInteger(rounds) && rounds > 0 && Number.isInteger(repeats) && repeats > 0);
const kernels = [beforePath, afterPath].map(path => {
  const module = new WebAssembly.Module(readFileSync(path));
  assert.equal(WebAssembly.Module.imports(module).length, 0);
  return new WebAssembly.Instance(module).exports;
});

function invoke(kernel, job, name, copy) {
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

const load = readFileSync(join(jobsDirectory, "load.job"));
for (const kernel of kernels) invoke(kernel, load, "run_load_generator", false);
console.log("phase\tstage\tround\tjobs\tmilliseconds\tms_per_chunk");
for (const stage of ["biomes", "terrain", "surface", "combined"]) {
  const name = stage === "combined" ? "run_terrain" : "run_" + stage;
  const jobs = readdirSync(jobsDirectory).filter(file => file.startsWith(stage + "-") && file.endsWith(".job"))
    .sort().map(file => ({bytes: readFileSync(join(jobsDirectory, file)), file,
      expected: readFileSync(join(jobsDirectory, file.replace(/\.job$/, ".expected")))}));
  assert(jobs.length > 0);
  for (const kernel of kernels) for (const job of jobs) assert.deepEqual(invoke(kernel, job.bytes, name, true), job.expected, job.file);
  for (let round = 0; round <= rounds; round++) {
    const elapsed = [0, 0];
    for (let repeat = 0; repeat < repeats; repeat++) {
      for (let index = 0; index < jobs.length; index++) {
        const order = (round + repeat + index) % 2 === 0 ? [0, 1] : [1, 0];
        for (const phase of order) {
          const started = performance.now();
          invoke(kernels[phase], jobs[index].bytes, name, false);
          elapsed[phase] += performance.now() - started;
        }
      }
    }
    if (round > 0) {
      const count = jobs.length * repeats;
      for (const phase of [0, 1]) console.log([phase === 0 ? "before" : "after", stage, round, count,
        elapsed[phase].toFixed(6), (elapsed[phase] / count).toFixed(6)].join("\t"));
    }
  }
  for (const kernel of kernels) for (const job of jobs) assert.deepEqual(invoke(kernel, job.bytes, name, true), job.expected, job.file);
}
console.error("exact paired native/wasm parity; memories=" + kernels.map(kernel => kernel.memory.buffer.byteLength).join(","));
