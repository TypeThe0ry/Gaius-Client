#!/usr/bin/env node
// Worldgen kernel through the real kernel pool (port/web/kernels).
//
//   node port/scripts/worldgen-kernel-pool-smoke.mjs [wasm-dir]
//
// wasm-dir holds gaius_worldgen_wasm.{simd,baseline}.wasm and the example IRs 26.2.gwir and
// 26.3.gwir (default $CARGO_TARGET_DIR/wasm-worldgen or port/native/target/wasm-worldgen). Build them first:
//   port/native/build-worldgen-wasm.sh
//   (cd port/native && cargo run -p gaius-worldgen-wasm --example example_ir -- target/wasm-worldgen)
//
// kernel-pool.js runs in a node:vm context whose Worker is backed by node:worker_threads; jobs are
// encoded and decoded with worldgen-job.js. Checks: generators load lazily per worker
// (generator-missing, then a resend with the IR), terrain and biome results decode, the separate
// surface job equals the surface run inside the terrain job, and the SIMD and baseline modules
// produce byte-identical chunks.
import {CANONICAL_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";
import {Worker as Thread} from "node:worker_threads";

const here = dirname(fileURLToPath(import.meta.url));
const kernelsDir = join(here, "..", "web", "kernels");
const [wasmDir = join(process.env.CARGO_TARGET_DIR || join(here, "..", "native", "target"), "wasm-worldgen")] = process.argv.slice(2);
const poolSource = readFileSync(join(kernelsDir, "kernel-pool.js"), "utf8");
const workerSource = readFileSync(join(kernelsDir, "kernel-worker.js"), "utf8");
const codecSource = readFileSync(join(kernelsDir, "worldgen-job.js"), "utf8");
const variants = {
  simd: readFileSync(join(wasmDir, "gaius_worldgen_wasm.simd.wasm")),
  baseline: readFileSync(join(wasmDir, "gaius_worldgen_wasm.baseline.wasm")),
};
const irs = {[CANONICAL_PROFILE.id]: readFileSync(join(wasmDir, "" + CANONICAL_PROFILE.id + ".gwir")), [RENDERPEARL_PROFILE.id]: readFileSync(join(wasmDir, "" + RENDERPEARL_PROFILE.id + ".gwir"))};

const BOOTSTRAP = `
const {parentPort, workerData} = require("node:worker_threads");
const vm = require("node:vm");
globalThis.self = globalThis;
globalThis.postMessage = (message, transfer) => parentPort.postMessage(message, transfer);
parentPort.on("message", (data) => { if (typeof self.onmessage === "function") self.onmessage({data}); });
vm.runInThisContext(workerData.source, {filename: "kernel-worker.js"});
`;

function createEnv() {
  const blobs = new Map();
  const threads = [];
  class ThreadWorker {
    constructor(url) {
      const source = blobs.get(url);
      assert.ok(source, `worker started from unknown url ${url}`);
      this.onmessage = null;
      this.onerror = null;
      this.thread = new Thread(BOOTSTRAP, {eval: true, workerData: {source}});
      this.exited = new Promise((resolve) => this.thread.once("exit", resolve));
      this.thread.on("message", (data) => this.onmessage && this.onmessage({data}));
      this.thread.on("error", (error) => this.onerror && this.onerror({message: error.message, preventDefault() {}}));
      threads.push(this);
    }
    postMessage(message, transfer = []) {
      this.thread.postMessage(message, transfer);
    }
    terminate() {
      this.thread.terminate();
    }
  }
  let seq = 0;
  const context = vm.createContext({
    console, performance, setTimeout, clearTimeout, WebAssembly, TextEncoder, TextDecoder, BigInt, DataView,
    navigator: {hardwareConcurrency: 6},
    Worker: ThreadWorker,
    Blob: class { constructor(parts) { this.text = parts.join(""); } },
    URL: {
      createObjectURL(blob) {
        const url = `blob:worldgen-kernel-pool-smoke/${++seq}`;
        blobs.set(url, blob.text);
        return url;
      },
      revokeObjectURL: (url) => blobs.delete(url),
    },
  });
  vm.runInContext(poolSource, context, {filename: "kernel-pool.js"});
  context.__gaiusKernelSemantics = {schemaVersion: 1, profile: "fixture", kernels: {worldgen: {enabled: true}}};
  vm.runInContext(readFileSync(join(kernelsDir, "kernel-mirror.js"), "utf8"), context);
  vm.runInContext(codecSource, context, {filename: "worldgen-job.js"});
  const codec = context.GaiusWorldgenJob;
  const create = (moduleBytes, options) => context.GaiusKernelPool.create({moduleBytes, workerSource, ...options});
  const shutdown = async (pool) => {
    pool.terminate();
    await Promise.all(threads.map((worker) => worker.exited));
  };
  return {codec, create, shutdown};
}

const bytesOf = (view) => Buffer.from(view.buffer, view.byteOffset, view.byteLength);

function checkChunk(codec, chunk, cx, cz) {
  assert.equal(chunk.chunkX, cx);
  assert.equal(chunk.chunkZ, cz);
  assert.equal(chunk.sectionCount, 24);
  assert.equal(chunk.minY, -64);
  const bottom = codec.sectionState(chunk.sections[0], 0, 4, 0);
  const top = codec.sectionState(chunk.sections[23], 0, 15, 0);
  assert.notEqual(bottom, 0, "deep layers are solid");
  assert.equal(top, 0, "the sky is air");
  for (let i = 0; i < 256; i++) {
    assert.ok(chunk.worldSurface[i] > -64 && chunk.worldSurface[i] <= 320, `height ${chunk.worldSurface[i]}`);
    assert.ok(chunk.oceanFloor[i] <= chunk.worldSurface[i]);
  }
}

const tests = [];
const test = (name, fn) => tests.push({name, fn});

for (const profile of Object.keys(irs)) {
  test(`${profile}: terrain, biomes and surface over three workers`, async () => {
    const env = createEnv();
    const {codec} = env;
    const chosen = codec.pickModule(variants);
    const pool = await env.create(chosen.bytes, {size: 3, name: "worldgen"});
    const gen = codec.generator(7, irs[profile]);
    const chunks = [];
    const positions = [[0, 0], [1, 0], [-2, 3], [5, -7], [16, 16], [-30, 2]];
    const results = await Promise.all(positions.map(([cx, cz]) =>
      codec.terrain(pool, gen, cx, cz, {surface: true, biomes: true})));
    results.forEach((chunk, i) => {
      checkChunk(codec, chunk, positions[i][0], positions[i][1]);
      assert.equal(chunk.biomes.length, 24 * 64);
      chunks.push(chunk);
    });
    const plain = await codec.terrain(pool, gen, 1, 0, {});
    const surfaced = await codec.surface(pool, gen, plain, {});
    assert.ok(bytesOf(surfaced.bytes).subarray(24).equals(bytesOf(chunks[1].bytes).subarray(24)),
      "the surface job equals the surface inside the terrain job");
    await assert.rejects(codec.biomes(pool, gen, 0, 0), /worldgen-fallback:multi-noise/);
    const retry = await codec.terrain(pool, gen, 0, 0, {surface: true, biomes: true});
    assert.deepEqual(Array.from(retry.biomes), Array.from(chunks[0].biomes));
    const telemetry = pool.telemetry();
    await env.shutdown(pool);
    return `${chosen.name} module, ${telemetry.completed} jobs, ${telemetry.failed} lazy-load retries`;
  });

  test(`${profile}: simd and baseline modules agree byte for byte`, async () => {
    const outputs = [];
    for (const name of ["simd", "baseline"]) {
      const env = createEnv();
      const pool = await env.create(variants[name], {size: 1});
      const gen = env.codec.generator(1, irs[profile]);
      const chunk = await env.codec.terrain(pool, gen, 3, -4, {surface: true, biomes: true});
      outputs.push(Buffer.from(bytesOf(chunk.bytes)));
      await env.shutdown(pool);
    }
    assert.ok(outputs[0].equals(outputs[1]), "simd and baseline chunks differ");
    return `${outputs[0].length} chunk bytes`;
  });
}

const guard = setTimeout(() => {
  console.error("worldgen kernel pool smoke timed out");
  process.exit(1);
}, 180000);
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
  console.error(`${failed} of ${tests.length} worldgen kernel pool checks failed`);
  process.exit(1);
}
console.log(`all ${tests.length} worldgen kernel pool checks passed`);
