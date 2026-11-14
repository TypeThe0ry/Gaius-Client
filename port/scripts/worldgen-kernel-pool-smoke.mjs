#!/usr/bin/env node
// Worldgen kernel through the real kernel pool (port/web/kernels).
//
//   node port/scripts/worldgen-kernel-pool-smoke.mjs [wasm-dir] [seed.gwir ...] [--replay=directory ...]
// Replay directories contain the bounded .job/.result pairs optionally captured by
// worldgen-status-perf/kernel-host.mjs with GAIUS_WORLDGEN_REPLAY_DIR.
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
import {createHash} from "node:crypto";
import {readFileSync, readdirSync} from "node:fs";
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

function createEnv(isolated = false) {
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
    crossOriginIsolated: isolated,
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

// Optional seeded IRs exercise the real train module with the same chunk requests serially
// and over multiple Workers, including the isolated shared-frame transport. Compare payloads:
// the result header contains the request ID, which intentionally changes with submission order.
for (const irPath of process.argv.slice(3).filter(path => !path.startsWith("--replay="))) {
  test(`seeded serial/parallel transport parity: ${irPath}`, async () => {
    const positions = [[0, 0], [-3, 9], [8, -8], [1, 0], [-2, 3], [5, -7]];
    const ir = readFileSync(irPath);
    const expected = new Map();
    for (const isolated of [false, true]) {
      for (const size of [1, 3]) {
        const env = createEnv(isolated);
        const pool = await env.create(variants.simd, {size, name: "worldgen-parity"});
        try {
          const gen = env.codec.generator(11, ir);
          const order = size === 1 ? positions : [...positions].reverse();
          const generate = async ([cx, cz]) => {
            const chunk = await env.codec.terrain(pool, gen, cx, cz, {surface: true, biomes: true});
            const payload = bytesOf(chunk.bytes).subarray(24);
            const key = `${cx},${cz}`;
            if (!isolated && size === 1) expected.set(key, Buffer.from(payload));
            else assert.ok(expected.get(key)?.equals(payload), `${key}: size=${size} isolated=${isolated}`);
            return {key, sha256: createHash("sha256").update(payload).digest("hex")};
          };
          const hashes = [];
          if (size === 1) for (const position of order) hashes.push(await generate(position));
          else hashes.push(...await Promise.all(order.map(generate)));
          console.log(JSON.stringify({irPath, isolated, size, hashes, telemetry: pool.telemetry()}));
        } finally {
          await env.shutdown(pool);
        }
      }
    }
    return `${positions.length} chunks, serial and reversed parallel, SAB and Transferable`;
  });
}

// Replays recorded production seam jobs, including their sampler arrays and biome rings.
// Inline the recorded generator so any Worker can accept the identical terrain body.
for (const argument of process.argv.slice(3).filter(path => path.startsWith("--replay="))) {
  const directory = argument.slice("--replay=".length);
  test("captured production job parity: " + directory, async () => {
    const names = readdirSync(directory).filter(name => name.endsWith(".job")).sort();
    const loads = new Map();
    const jobs = [];
    for (const name of names) {
      const frame = readFileSync(join(directory, name));
      assert.equal(frame.readUInt32LE(0), 0x424a4b47);
      assert.equal(frame.readUInt32LE(12), frame.length - 16);
      const kind = frame.readUInt16LE(6), key = frame.readUInt32LE(16);
      const irLength = frame.readUInt32LE(32);
      if (kind === 0x0401) {
        assert.ok(irLength > 0);
        loads.set(key, Buffer.from(frame.subarray(40, 40 + irLength)));
      } else if (kind === 0x0403) {
        const ir = loads.get(key);
        assert.ok(ir, "terrain requires a captured generator");
        const bodyOffset = 16 + Math.ceil((24 + irLength) / 8) * 8;
        const referenceLength = Math.ceil((24 + ir.length) / 8) * 8;
        const body = frame.subarray(bodyOffset);
        const inline = Buffer.alloc(16 + referenceLength + body.length);
        frame.copy(inline, 0, 0, 40);
        inline.writeUInt32LE(inline.length - 16, 12);
        inline.writeUInt32LE(frame.readUInt32LE(20) | 1, 20);
        inline.writeUInt32LE(ir.length, 32);
        ir.copy(inline, 40);
        body.copy(inline, 16 + referenceLength);
        assert.ok(inline.subarray(16 + referenceLength).equals(body));
        jobs.push({name, frame: inline, expected: readFileSync(join(directory, name.replace(/.job$/, ".result")))});
      }
    }
    assert.ok(jobs.length > 0, "capture must execute terrain jobs");
    for (const isolated of [false, true]) {
      for (const size of [1, 3]) {
        const env = createEnv(isolated);
        const pool = await env.create(variants.simd, {size, name: "worldgen-replay"});
        try {
          const order = size === 1 ? jobs : [...jobs].reverse();
          const run = async job => {
            const buffer = isolated ? new SharedArrayBuffer(job.frame.length) : new ArrayBuffer(job.frame.length);
            new Uint8Array(buffer).set(job.frame);
            const result = await pool.submit("terrain", buffer, {kernel: "worldgen", retry: false});
            assert.ok(Buffer.from(result).equals(job.expected), job.name + ": serial/parallel result differs");
          };
          if (size === 1) for (const job of order) await run(job);
          else await Promise.all(order.map(run));
          console.log(JSON.stringify({directory, isolated, size, jobs: jobs.length, telemetry: pool.telemetry()}));
        } finally {
          await env.shutdown(pool);
        }
      }
    }
    return jobs.length + " production terrain bodies: exact recorded results on 1/3 Workers, SAB/Transferable";
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
