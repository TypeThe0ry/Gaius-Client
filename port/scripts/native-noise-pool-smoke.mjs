#!/usr/bin/env node
// Real noise kernel through the real kernel pool (port/web/kernels).
//
//   node port/scripts/native-noise-pool-smoke.mjs [module.wasm] [fixtures-dir]
//
// kernel-pool.js runs in a node:vm context whose Worker is backed by node:worker_threads: each
// worker is a real thread running kernel-worker.js with its own instance of the built
// gaius_noise_wasm module. Jobs are encoded with noise-job.js from the golden fixtures and every
// result must match them bit for bit. Build the module first with port/native/build-wasm.sh.
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {join} from "node:path";
import vm from "node:vm";
import {Worker as Thread} from "node:worker_threads";
import {compareBits, defaultFixtures, defaultWasm, fixtureJobs, kernelsDir, loadCodec} from "../native/js/fixture-jobs.mjs";

const [modulePath = defaultWasm, fixturesDir = defaultFixtures] = process.argv.slice(2);
const poolSource = readFileSync(join(kernelsDir, "kernel-pool.js"), "utf8");
const workerSource = readFileSync(join(kernelsDir, "kernel-worker.js"), "utf8");
const moduleBytes = readFileSync(modulePath);
const codec = loadCodec();
const jobs = fixtureJobs(fixturesDir);

// Gives a worker thread the dedicated-worker globals kernel-worker.js expects.
const BOOTSTRAP = `
const {parentPort, workerData} = require("node:worker_threads");
const vm = require("node:vm");
globalThis.self = globalThis;
globalThis.postMessage = (message, transfer) => parentPort.postMessage(message, transfer);
parentPort.on("message", (data) => { if (typeof self.onmessage === "function") self.onmessage({data}); });
vm.runInThisContext(workerData.source, {filename: "kernel-worker.js"});
`;

function createEnv({rejectModuleClone = false} = {}) {
  const blobs = new Map();
  const log = {threads: [], inits: []};
  class ThreadWorker {
    constructor(url, options) {
      const source = blobs.get(url);
      assert.ok(source, `worker started from unknown url ${url}`);
      this.onmessage = null;
      this.onerror = null;
      this.thread = new Thread(BOOTSTRAP, {eval: true, workerData: {source}, name: options && options.name});
      this.exited = new Promise((resolve) => this.thread.once("exit", resolve));
      this.thread.on("message", (data) => this.onmessage && this.onmessage({data}));
      this.thread.on("error", (error) => this.onerror && this.onerror({message: error.message, preventDefault() {}}));
      log.threads.push(this);
    }
    postMessage(message, transfer = []) {
      if (message.type === "init") {
        if (rejectModuleClone && message.module) throw new Error("DataCloneError: WebAssembly.Module could not be cloned");
        log.inits.push(message.module ? "module" : "bytes");
      }
      this.thread.postMessage(message, transfer);
    }
    terminate() {
      this.thread.terminate();
    }
  }
  let seq = 0;
  const context = vm.createContext({
    console, performance, setTimeout, clearTimeout, WebAssembly, TextEncoder, TextDecoder,
    navigator: {hardwareConcurrency: 6},
    Worker: ThreadWorker,
    Blob: class { constructor(parts) { this.text = parts.join(""); } },
    URL: {
      createObjectURL(blob) {
        const url = `blob:native-noise-pool-smoke/${++seq}`;
        blobs.set(url, blob.text);
        return url;
      },
      revokeObjectURL: (url) => blobs.delete(url),
    },
  });
  vm.runInContext(poolSource, context, {filename: "kernel-pool.js"});
  context.__gaiusKernelSemantics = {schemaVersion: 1, profile: "fixture", kernels: {noise: {enabled: true}}};
  vm.runInContext(readFileSync(join(kernelsDir, "kernel-mirror.js"), "utf8"), context);
  const create = (options) => context.GaiusKernelPool.create({moduleBytes, workerSource, ...options});
  const shutdown = async (pool) => {
    pool.terminate();
    await Promise.all(log.threads.map((worker) => worker.exited));
  };
  return {create, shutdown, log};
}

// Submits fixture jobs, awaits every result and checks it against the fixture bits.
async function runFixtureJobs(pool, selected) {
  const payloads = [];
  const results = selected.map(({job}, index) => {
    const payload = codec.noisePoints(job, index);
    payloads.push(payload);
    return pool.submit("noise_points", payload, {priority: index % 5, key: `job-${index}`});
  });
  const buffers = await Promise.all(results);
  buffers.forEach((buffer, index) => {
    const problem = compareBits(codec.readNoisePoints(buffer, index), selected[index].expected);
    assert.equal(problem, null, `${selected[index].label}: ${problem}`);
  });
  assert.ok(payloads.every((payload) => payload.byteLength === 0), "payloads move to the workers by transfer");
  return buffers.reduce((points, buffer) => points + (buffer.byteLength - 16) / 8, 0);
}

const tests = [];
const test = (name, fn) => tests.push({name, fn});

test("every fixture job, bit-exact, spread over three worker threads", async () => {
  const env = createEnv();
  const pool = await env.create({size: 3});
  const points = await runFixtureJobs(pool, jobs);
  const telemetry = pool.telemetry();
  assert.equal(telemetry.completed, jobs.length);
  assert.equal(telemetry.failed, 0);
  assert.deepEqual(env.log.inits, ["module", "module", "module"], "workers receive the compiled module");
  const busy = telemetry.workers.filter((worker) => worker.completed > 0).length;
  assert.ok(busy >= 2, `jobs ran on ${busy} worker(s)`);
  await env.shutdown(pool);
  return `${jobs.length} jobs, ${points} points, ${busy} workers busy`;
});

test("module bytes when the compiled module cannot be cloned", async () => {
  const env = createEnv({rejectModuleClone: true});
  const pool = await env.create({size: 2});
  const selected = jobs.filter((_, index) => index % 23 === 0);
  await runFixtureJobs(pool, selected);
  assert.ok(env.log.inits.includes("bytes"), `inits ${env.log.inits}`);
  await env.shutdown(pool);
  return `${selected.length} jobs via ${env.log.inits.join(",")}`;
});

test("a damaged frame is a kernel error and the worker keeps serving", async () => {
  const env = createEnv();
  const pool = await env.create({size: 1});
  const damaged = new Uint8Array(codec.noisePoints(jobs[0].job, 0));
  damaged[4] = 9;  // abi version
  const error = await pool.submit("noise_points", damaged.buffer).then(
    () => assert.fail("damaged frame resolved"), (rejection) => rejection);
  assert.equal(error.code, "kernel-error", error.message);
  assert.equal(error.status, 2, "BadVersion");
  await runFixtureJobs(pool, jobs.slice(0, 3));
  assert.equal(env.log.threads.length, 1, "the worker was not replaced");
  await env.shutdown(pool);
  return `rejected with status ${error.status}: ${error.message}`;
});

const guard = setTimeout(() => {
  console.error("native noise pool smoke timed out");
  process.exit(1);
}, 120000);
let failed = 0;
for (const {name, fn} of tests) {
  try {
    const detail = await fn();
    console.log(`ok   ${name}${detail ? ` (${detail})` : ""}`);
  } catch (error) {
    failed++;
    console.log(`FAIL ${name}\n     ${error && error.stack ? error.stack : error}`);
  }
}
clearTimeout(guard);
if (failed) {
  console.error(`${failed} of ${tests.length} native noise pool checks failed`);
  process.exit(1);
}
console.log(`all ${tests.length} native noise pool checks passed`);
