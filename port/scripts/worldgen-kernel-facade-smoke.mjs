#!/usr/bin/env node
// Worldgen kernel facade (port/web/kernels/worldgen-kernel.js) against a stand-in for the shared
// kernel runtime client (server-worker-bootstrap.js __gaiusKernelClient).
//
//   node port/scripts/worldgen-kernel-facade-smoke.mjs [wasm-dir]
//
// wasm-dir holds gaius_worldgen_wasm.simd.wasm (or .baseline.wasm) and 26.3.gwir / 26.2.gwir
// (default $CARGO_TARGET_DIR/wasm-worldgen or port/native/target/wasm-worldgen; see worldgen-kernel-pool-smoke.mjs for the build).
// The stand-in runs jobs in-process on one wasm instance with the kernel-worker.js calling
// convention. Checks: the facade is unusable without a runner and usable with the client, jobs
// carry the "worldgen" kernel and the chunk position, terrain and biome results reach the Java
// callbacks as flat arrays of the right sizes, jobs carry the server-side distance hint, a kept
// noise chunk plus its surface diff reproduce the full surface result, ring biomes the neighbours
// store reach the terrain and stored surface jobs (computed ones change nothing, an unknown biome
// is a transient "worldgen-fallback:" refusal), a transient refusal reports "transient:" and keeps
// the generator, a kernel failure disables the generator, and drain() settles a job the runner
// never answers.
import {CANONICAL_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {existsSync, readFileSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const kernelsDir = join(here, "..", "web", "kernels");
const [wasmDir = join(process.env.CARGO_TARGET_DIR || join(here, "..", "native", "target"), "wasm-worldgen")] = process.argv.slice(2);
const wasmFile = ["simd", "baseline"].map((v) => join(wasmDir, `gaius_worldgen_wasm.${v}.wasm`)).find(existsSync);
assert.ok(wasmFile, `no worldgen wasm module in ${wasmDir}`);

const context = vm.createContext({console, URLSearchParams, WebAssembly, BigInt, Map, Set, Promise, queueMicrotask,
  setInterval, clearInterval, setTimeout, clearTimeout});
context.globalThis = context;
context.location = {search: ""};
vm.runInContext(readFileSync(join(kernelsDir, "worldgen-job.js"), "utf8"), context, {filename: "worldgen-job.js"});
context.__gaiusKernelSemantics = {schemaVersion: 1, profile: "fixture", kernels: {worldgen: {enabled: true}}};
vm.runInContext(readFileSync(join(kernelsDir, "kernel-mirror.js"), "utf8"), context);
vm.runInContext(readFileSync(join(kernelsDir, "worldgen-kernel.js"), "utf8"), context, {filename: "worldgen-kernel.js"});
const facade = context.GaiusWorldgenKernel;

// In-process runner with the kernel-worker.js calling convention.
const instance = new WebAssembly.Instance(new WebAssembly.Module(readFileSync(wasmFile)), {});
const decoder = new TextDecoder();
function run(kind, payload) {
  const exports = instance.exports;
  const input = new Uint8Array(payload);
  const ptr = exports.alloc(input.byteLength) >>> 0;
  new Uint8Array(exports.memory.buffer, ptr, input.byteLength).set(input);
  const header = exports["run_" + kind](ptr, input.byteLength) >>> 0;
  const view = new DataView(exports.memory.buffer);
  const status = view.getUint32(header, true);
  const bytes = new Uint8Array(exports.memory.buffer, view.getUint32(header + 4, true), view.getUint32(header + 8, true)).slice();
  exports.release(header);
  exports.dealloc(ptr, input.byteLength);
  if (status !== 0) {
    const error = new Error(decoder.decode(bytes));
    error.code = "kernel-error";
    throw error;
  }
  return bytes.buffer;
}

const submitted = [];
const deferred = [];
let mode = "ok";
context.__gaiusKernelClient = {
  available: (kernel) => kernel === "worldgen",
  submit(kind, payload, opts) {
    submitted.push({kind, opts});
    if (mode === "backpressure" || mode === "request-cap") {
      const error = new Error("kernel memory budget is full");
      error.code = mode;
      return Promise.reject(error);
    }
    if (mode === "hang") return new Promise(() => {});
    if (mode === "trap") {
      const error = new Error("unreachable executed");
      error.code = "kernel-trap";
      return Promise.reject(error);
    }
    if (mode === "deferred") return new Promise((resolve, reject) => deferred.push(() => {
      try { resolve(run(kind, payload)); } catch (error) { reject(error); }
    }));
    try {
      return Promise.resolve(run(kind, payload));
    } catch (error) {
      return Promise.reject(error);
    }
  },
};

function call(submit) {
  return new Promise((resolve) => submit((value) => resolve({ok: true, value}), (message) => resolve({ok: false, message})));
}

let checks = 0;
for (const profile of [RENDERPEARL_PROFILE.id, CANONICAL_PROFILE.id]) {
  const irPath = join(wasmDir, `${profile}.gwir`);
  if (!existsSync(irPath)) continue;
  const key = profile === RENDERPEARL_PROFILE.id ? 1 : 2;
  assert.equal(facade.usable(), true, "usable with the runtime client");
  assert.equal(facade.registerGenerator(key, new Uint8Array(readFileSync(irPath))), true);
  assert.equal(facade.generatorUsable(key), true);

  // Settlements are queued into one microtask batch. Completion callbacks stay
  // in result-arrival order and each callback runs exactly once.
  mode = "deferred";
  deferred.length = 0;
  const settlementOrder = [];
  facade.submitTerrain(key, 7, 0, 1, new Int32Array(9), () => settlementOrder.push("first"), () => settlementOrder.push("first-error"));
  facade.submitTerrain(key, 8, 0, 1, new Int32Array(9), () => settlementOrder.push("second"), () => settlementOrder.push("second-error"));
  // The facade resolves its runner (an async function) before it submits to the client.
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(deferred.length, 2);
  deferred[1](); deferred[0]();
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(settlementOrder, ["second", "first"], "batched settlements preserve FIFO arrival order");
  mode = "ok";

  mode = "ok";
  submitted.length = 0;
  const terrain = await call((ok, err) => facade.submitTerrain(key, 3, -2, 1, new Int32Array(9), ok, err));
  assert.ok(terrain.ok, terrain.message);
  const chunk = terrain.value;
  assert.equal(chunk.chunkX, 3);
  assert.equal(chunk.chunkZ, -2);
  assert.equal(chunk.paletteOffsets.length, chunk.sectionCount + 1);
  assert.equal(chunk.indices.length, chunk.sectionCount * 4096);
  assert.equal(chunk.uniform.length, chunk.sectionCount);
  assert.equal(chunk.worldSurface.length, 256);
  assert.equal(chunk.oceanFloor.length, 256);
  assert.ok(chunk.palettes.length >= chunk.sectionCount);
  assert.equal(submitted[0].kind, "terrain");
  assert.equal(submitted[0].opts.kernel, "worldgen");
  assert.equal(submitted[0].opts.cx, 3);
  assert.equal(submitted[0].opts.cz, -2);

  const biomes = await call((ok, err) => facade.submitBiomes(key, 3, -2, ok, err));
  assert.ok(!biomes.ok && biomes.message.startsWith("transient:worldgen-fallback:multi-noise"), biomes.message);
  assert.equal(facade.generatorUsable(key), true);

  // Server-side priority data (BrowserChunkTaskPriority): the job carries a distance hint.
  context.__gaiusChunkPriorityStats = {playerChunk: "0,0", direction: "1,0"};
  submitted.length = 0;
  const hinted = await call((ok, err) => facade.submitBiomes(key, 4, 0, ok, err));
  assert.ok(!hinted.ok && hinted.message.startsWith("transient:worldgen-fallback:multi-noise"), hinted.message);
  assert.ok(submitted[0].opts.distance < 4, "a chunk ahead of the player ranks closer than its distance");
  delete context.__gaiusChunkPriorityStats;

  // Kept noise chunk + surface diff == the terrain job with surface rules.
  const keep = facade.FLAG_KEEP_NOISE;
  const noise = await call((ok, err) => facade.submitTerrain(key, 0, 0, keep, new Int32Array(9), ok, err));
  assert.ok(noise.ok, noise.message);
  assert.ok(noise.value.token > 0, "the noise chunk is kept");
  const diff = await call((ok, err) => facade.submitSurfaceStored(key, noise.value.token, 0, 0, new Int32Array(9), ok, err));
  assert.ok(diff.ok, diff.message);
  assert.equal(diff.value.positions.length, diff.value.count);
  assert.ok(diff.value.count > 0, "the surface rules change blocks of this chunk");
  const full = await call((ok, err) => facade.submitTerrain(key, 0, 0, 1, new Int32Array(9), ok, err));
  assert.ok(full.ok, full.message);
  const stateAt = (c, s, i) => c.palettes[c.paletteOffsets[s] + (c.uniform[s] ? 0 : c.indices[s * 4096 + i])];
  const patched = new Map();
  for (let n = 0; n < diff.value.count; n++) patched.set(diff.value.positions[n], diff.value.states[n]);
  let mismatches = 0;
  for (let s = 0; s < full.value.sectionCount; s++) {
    for (let i = 0; i < 4096; i++) {
      const id = patched.has((s << 12) | i) ? patched.get((s << 12) | i) : stateAt(noise.value, s, i);
      if (id !== stateAt(full.value, s, i)) mismatches++;
    }
  }
  assert.equal(mismatches, 0, "noise + surface diff reproduces the surface result");
  const again = await call((ok, err) => facade.submitSurfaceStored(key, noise.value.token, 0, 0, new Int32Array(9), ok, err));
  assert.ok(!again.ok && again.message.startsWith("transient:"), "a kept chunk is consumed once");

  // Ring biomes (what the neighbours store around chunk 0,0): neighbours storing the biomes the
  // kernel computes change nothing; a biome outside the generator's table only refuses the chunk.
  const neighbours = new Map();
  for (let nx = -1; nx <= 1; nx++) {
    for (let nz = -1; nz <= 1; nz++) {
      const ids = await call((ok, err) => facade.submitTerrain(key, nx, nz,
        context.GaiusWorldgenJob.FLAG_BIOMES, new Int32Array(9), ok, err));
      assert.ok(ids.ok, ids.message);
      neighbours.set(nx + "," + nz, ids.value.biomes);
    }
  }
  const quartsY = full.value.sectionCount * 4;
  const ring = [];
  const stored = [];
  for (let gx = 0; gx < 6; gx++) {
    for (let gz = 0; gz < 6; gz++) {
      const qx = gx - 1;
      const qz = gz - 1;
      const ids = neighbours.get((qx >> 2) + "," + (qz >> 2));
      const border = gx < 1 || gx > 4 || gz < 1 || gz > 4;
      for (let y = 0; y < quartsY; y++) {
        const id = ids[(y >> 2) * 64 + ((y & 3) * 4 + (qz & 3)) * 4 + (qx & 3)];
        stored.push(id);
        if (border) ring.push(id);
      }
    }
  }
  const ringBiomes = new Int32Array(ring);
  const sameChunk = (a, b) => a.palettes.join() === b.palettes.join() && a.indices.join() === b.indices.join()
    && a.uniform.join() === b.uniform.join();
  const ringed = await call((ok, err) => facade.submitTerrain(key, 0, 0, 1, new Int32Array(9), ok, err, ringBiomes));
  assert.ok(ringed.ok, ringed.message);
  assert.ok(sameChunk(ringed.value, full.value), "a ring of computed biomes keeps the terrain result");
  const storedBiomes = new Int32Array(stored);
  const storedChunk = await call((ok, err) => facade.submitTerrain(key, 0, 0,
    context.GaiusWorldgenJob.FLAG_SURFACE | context.GaiusWorldgenJob.FLAG_BIOMES,
    new Int32Array(9), ok, err, storedBiomes));
  assert.ok(storedChunk.ok, storedChunk.message);
  assert.ok(sameChunk(storedChunk.value, full.value), "stored center and border biomes keep the terrain result");
  assert.equal(storedChunk.value.biomes.join(), neighbours.get("0,0").join(), "stored center biomes are returned");
  const keptAgain = await call((ok, err) => facade.submitTerrain(key, 0, 0, keep, new Int32Array(9), ok, err));
  assert.ok(keptAgain.ok, keptAgain.message);
  const ringedDiff = await call((ok, err) => facade.submitSurfaceStored(key, keptAgain.value.token, 0, 0,
    new Int32Array(9), ok, err, storedBiomes));
  assert.ok(ringedDiff.ok, ringedDiff.message);
  assert.equal(ringedDiff.value.positions.join(), diff.value.positions.join(), "a ring of computed biomes keeps the diff");
  assert.equal(ringedDiff.value.states.join(), diff.value.states.join());
  const foreign = ringBiomes.slice();
  foreign[5] = 0x7fff0000;
  const refusedRing = await call((ok, err) => facade.submitTerrain(key, 0, 0, 1, new Int32Array(9), ok, err, foreign));
  assert.ok(!refusedRing.ok && refusedRing.message.startsWith("transient:")
    && refusedRing.message.indexOf(facade.FALLBACK_PREFIX) >= 0, refusedRing.message);
  assert.equal(facade.generatorUsable(key), true, "an unknown neighbour biome keeps the generator");
  assert.ok(facade.telemetry().ringFallbacks >= 1);

  mode = "backpressure";
  const refused = await call((ok, err) => facade.submitTerrain(key, 4, -2, 1, new Int32Array(9), ok, err));
  assert.equal(refused.ok, false);
  assert.ok(refused.message.startsWith("transient:"), refused.message);
  assert.equal(facade.generatorUsable(key), true, "a transient refusal keeps the generator");

  mode = "request-cap";
  const capped = await call((ok, err) => facade.submitTerrain(key, 4, -2, 1, new Int32Array(9), ok, err));
  assert.equal(capped.ok, false);
  assert.ok(capped.message.startsWith("transient:"), capped.message);
  assert.equal(facade.generatorUsable(key), true, "request-cap keeps the generator");

  mode = "trap";
  const trapped = await call((ok, err) => facade.submitTerrain(key, 5, -2, 1, new Int32Array(9), ok, err));
  assert.equal(trapped.ok, false);
  assert.ok(!trapped.message.startsWith("transient:"), trapped.message);
  assert.equal(facade.generatorUsable(key), false, "a kernel failure disables the generator");
  console.log(`ok   ${profile}: facade over the runtime client (terrain ${chunk.sectionCount} sections, biomes,`
    + ` surface diff ${diff.value.count} blocks, ring biomes, transient and fatal errors)`);
  checks++;
}
assert.ok(checks > 0, `no example IR in ${wasmDir}`);

// drain(): a job its runner never answers settles as transient once the capped deadline passes.
{
  const irPath = [join(wasmDir, "" + RENDERPEARL_PROFILE.id + ".gwir"), join(wasmDir, "" + CANONICAL_PROFILE.id + ".gwir")].find(existsSync);
  assert.equal(facade.registerGenerator(9, new Uint8Array(readFileSync(irPath))), true);
  mode = "hang";
  const hung = call((ok, err) => facade.submitBiomes(9, 0, 0, ok, err));
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(facade.pending(), 1);
  const drained = facade.drain(50);
  assert.equal(facade.usable(), false, "no new jobs while draining");
  const outcome = await hung;
  assert.ok(!outcome.ok && outcome.message.startsWith("transient:"), outcome.message);
  assert.equal(await drained, true);
  assert.equal(facade.pending(), 0);
  facade.resume();
  console.log("ok   drain settles a hung job as transient");
  checks++;
}

delete context.__gaiusKernelClient;
assert.equal(facade.usable(), false, "no runner: Java keeps the vanilla path");
console.log(`all ${checks} worldgen kernel facade checks passed`);
