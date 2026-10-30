#!/usr/bin/env node
// Unified kernel runtime (port/web/kernels/kernel-runtime.js) over the real kernel worker.
//
//   node port/scripts/kernel-runtime-smoke.mjs
//
// kernel-policy.js and kernel-runtime.js run in a node:vm "page"; every fake Worker runs the
// real kernel-worker.js in its own vm context, messages cross through structuredClone with
// their transfer lists, and a fake WebAssembly implements the kernel ABI in JavaScript. The
// checks cover priority dispatch across kernels, movement-aware ordering, lazy multi-kernel
// loading, SIMD/baseline selection, budget backpressure, kernel disabling (the vanilla
// fallback signal), primers for respawned workers, trimming under memory pressure and the
// MessagePort client the integrated server Worker uses.

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {webcrypto} from "node:crypto";
import vm from "node:vm";

const kernelsDir = new URL("../web/kernels/", import.meta.url);
const policySource = await readFile(new URL("kernel-policy.js", kernelsDir), "utf8");
const runtimeSource = await readFile(new URL("kernel-runtime.js", kernelsDir), "utf8");
const workerSource = await readFile(new URL("kernel-worker.js", kernelsDir), "utf8");

const encodeModule = (desc) => {
  const body = new TextEncoder().encode(JSON.stringify(Object.assign({fake: 1}, desc)));
  const bytes = new Uint8Array(8 + body.length);
  bytes.set([0, 0x61, 0x73, 0x6d, 1, 0, 0, 0]);
  bytes.set(body, 8);
  return bytes.buffer;
};
const decodeModule = (bytes) => JSON.parse(new TextDecoder().decode(new Uint8Array(bytes).subarray(8)));

function moduleExports(desc) {
  return [
    {name: "memory", kind: "memory"},
    {name: "alloc", kind: "function"},
    {name: "reset", kind: "function"},
    ...desc.kinds.map((kind) => ({name: `run_${kind}`, kind: "function"})),
  ];
}

function makeInstance(desc, log) {
  log.instances.push(desc.name);
  let buffer = new ArrayBuffer(1 << 16);
  let top = 64;
  const memory = {get buffer() { return buffer; }};
  const alloc = (length) => {
    const ptr = (top + 7) & ~7;
    top = ptr + length;
    while (top > buffer.byteLength) buffer = buffer.transfer(buffer.byteLength * 2);
    return ptr;
  };
  const output = (status, bytes) => {
    const data = alloc(bytes.length);
    new Uint8Array(buffer, data, bytes.length).set(bytes);
    const header = alloc(12);
    const view = new DataView(buffer);
    view.setUint32(header, status, true);
    view.setUint32(header + 4, data, true);
    view.setUint32(header + 8, bytes.length, true);
    return header;
  };
  const exports = {memory, alloc, reset: () => { top = 64; }};
  if (desc.trim) exports[`${desc.name}_trim`] = () => { log.trims.push(desc.name); };
  for (const kind of desc.kinds) {
    exports[`run_${kind}`] = (ptr, length) => {
      const bytes = new Uint8Array(buffer, ptr, length).slice();
      const tag = bytes.length >= 4 ? new DataView(bytes.buffer).getUint32(0, true) : -1;
      log.order.push(`${desc.name}:${kind}:${tag}`);
      if (kind === "trap") throw new Error("unreachable executed");
      if (kind === "spin") {
        const until = Date.now() + 30;
        while (Date.now() < until) { /* a long kernel */ }
      }
      return output(0, bytes);
    };
  }
  return {exports};
}

function createEnv({cores = 4, deviceMemory = 8, simd = true} = {}) {
  const log = {order: [], instances: [], workers: [], posts: [], trims: []};
  const workerWasm = {
    Module: {exports: moduleExports, imports: () => []},
    compile: async (bytes) => decodeModule(bytes),
    async instantiate(source) {
      return source && source.fake === 1 ? makeInstance(source, log) : {instance: makeInstance(decodeModule(source), log)};
    },
  };

  class FakeWorker {
    constructor(url, options) {
      this.name = options && options.name;
      this.terminated = false;
      this.onmessage = null;
      this.onerror = null;
      log.workers.push(this);
      this.ctx = vm.createContext({
        console, performance, TextDecoder,
        setTimeout: (fn, ms) => setTimeout(() => { if (!this.terminated) fn(); }, ms),
        WebAssembly: workerWasm,
        postMessage: (message, transfer) => this.fromWorker(message, transfer),
      });
      vm.runInContext("globalThis.self = globalThis;", this.ctx);
      vm.runInContext(workerSource, this.ctx, {filename: "kernel-worker.js"});
    }

    postMessage(message, transfer = []) {
      if (this.terminated) return;
      const data = structuredClone(message, {transfer});
      log.posts.push({dir: "toWorker", type: message.type, kernel: message.kernel});
      setTimeout(() => { if (!this.terminated) this.ctx.onmessage({data}); }, 1);
    }

    fromWorker(message, transfer = []) {
      if (this.terminated) return;
      const data = structuredClone(message, {transfer});
      setTimeout(() => { if (!this.terminated && this.onmessage) this.onmessage({data}); }, 1);
    }

    terminate() {
      this.terminated = true;
    }
  }

  const pageWasm = {
    Module: {exports: moduleExports, imports: () => []},
    compile: async (bytes) => {
      const desc = decodeModule(bytes);
      if (desc.failCompile) throw new Error("CompileError: simd opcode not supported");
      return desc;
    },
    validate: () => simd,
    Memory: class {},
  };
  const context = vm.createContext({
    console, performance, setTimeout, clearTimeout, setInterval, clearInterval, structuredClone,
    navigator: {hardwareConcurrency: cores, deviceMemory},
    Worker: FakeWorker, WebAssembly: pageWasm, crypto: webcrypto, MessageChannel, URLSearchParams,
    URL: {createObjectURL: () => "blob:kernel-runtime-smoke/1", revokeObjectURL() {}},
    Blob: class { constructor(parts) { this.parts = parts; } },
  });
  vm.runInContext(policySource, context, {filename: "kernel-policy.js"});
  context.GaiusKernelMirror = {enabled: () => true, allowed: () => true};
  vm.runInContext(runtimeSource, context, {filename: "kernel-runtime.js"});
  return {context, log, Runtime: context.GaiusKernelRuntime};
}

const tagged = (tag, size = 16) => {
  const buffer = new ArrayBuffer(size);
  new DataView(buffer).setUint32(0, tag, true);
  return buffer;
};
const tagOf = (buffer) => new DataView(buffer).getUint32(0, true);
const KERNELS = {
  mesh: {kinds: ["mesh_section", "spin"], variants: {simd: {bytes: encodeModule({name: "mesh", kinds: ["mesh_section", "spin"]})}}},
  worldgen: {kinds: ["gen_chunk"], variants: {
    simd: {bytes: encodeModule({name: "worldgen-simd", kinds: ["gen_chunk"], failCompile: true})},
    baseline: {bytes: encodeModule({name: "worldgen-baseline", kinds: ["gen_chunk"]})},
  }},
  light: {kinds: ["light_section", "trap"], variants: {baseline: {bytes: encodeModule({name: "light", kinds: ["light_section", "trap"]})}}},
};

function create(env, extra = {}) {
  return env.Runtime.create(Object.assign({
    kernels: KERNELS, workerUrl: "kernel-worker.js", search: "",
    switches: {enabled: true, off: new Set(), simd: "auto", size: 1, budgetBytes: 0},
    sizingIntervalMs: 60_000, instanceEstimateBytes: 1 << 20, mobile: false,
  }, extra));
}

const checks = [];
const check = (name, fn) => checks.push([name, fn]);

check("feature switches: URL parameters and stored settings", async () => {
  const {Runtime} = createEnv();
  const storage = {getItem: () => JSON.stringify({off: ["light"], simd: "off", workers: 3})};
  const switches = Runtime.readSwitches("?gaiusKernelsOff=mesh&gaiusKernelBudgetMB=64", storage);
  assert.deepEqual([...switches.off].sort(), ["light", "mesh"]);
  assert.equal(switches.simd, "off");
  assert.equal(switches.size, 3);
  assert.equal(switches.budgetBytes, 64 * 1024 * 1024);
  assert.equal(Runtime.readSwitches("?gaiusKernels=0", null).enabled, false);
  // The real SIMD probe module is valid wasm with SIMD (node supports it).
  assert.equal(WebAssembly.validate(new Uint8Array(Runtime.SIMD_PROBE)), true);
});

check("kernel bytes are SHA-256 bound before worker loading", async () => {
  const env = createEnv();
  const bytes = KERNELS.mesh.variants.simd.bytes;
  const digest = Array.from(new Uint8Array(await webcrypto.subtle.digest("SHA-256", bytes)),
    (byte) => byte.toString(16).padStart(2, "0")).join("");
  const good = create(env, {kernels: {mesh: {kinds: ["mesh_section"], variants: {simd: {bytes, sha256: digest}}}}});
  await good.warm();
  assert.equal(good.telemetry().kernels.mesh.disabled, null);
  good.terminate();
  const bad = create(env, {kernels: {mesh: {kinds: ["mesh_section"], variants: {simd: {bytes, sha256: "0".repeat(64)}}}}});
  await bad.warm();
  assert.match(bad.telemetry().kernels.mesh.disabled, /SHA-256 mismatch/);
  assert.equal(bad.available("mesh"), false);
  bad.terminate();
});

check("one queue across kernels: visible mesh, then near generation, far, background", async () => {
  const env = createEnv();
  const runtime = create(env);
  runtime.setViewer({x: 8, z: 8, t: 0});
  await runtime.warm();
  // Occupy the single worker so the next submits queue together.
  const blocker = runtime.submit("spin", tagged(1), {kernel: "mesh"});
  await new Promise((resolve) => setTimeout(resolve, 5));
  const results = Promise.all([
    runtime.submit("gen_chunk", tagged(40), {background: true}),
    runtime.submit("gen_chunk", tagged(30), {cx: 30, cz: 30}),
    runtime.submit("light_section", tagged(20), {visible: true}),
    runtime.submit("gen_chunk", tagged(25), {cx: 1, cz: 0}),
    runtime.submit("mesh_section", tagged(10), {visible: true}),
  ]);
  await blocker;
  const values = await results;
  assert.deepEqual(values.map(tagOf), [40, 30, 20, 25, 10], "results resolve to their own jobs");
  const order = env.log.order.filter((entry) => !entry.endsWith(":1")).map((entry) => Number(entry.split(":")[2]));
  assert.deepEqual(order, [10, 20, 25, 30, 40]);
  const telemetry = runtime.telemetry();
  assert.equal(telemetry.kernels.worldgen.variant, "baseline", "the SIMD build failed to compile: baseline");
  assert.equal(telemetry.kernels.mesh.variant, "simd");
  assert.deepEqual([...telemetry.workers[0].loaded].sort(), ["light", "mesh", "worldgen"]);
  assert.equal(telemetry.classes[0].completed, 2, "the blocker and the visible mesh job");
  runtime.terminate();
});

check("movement prediction reorders queued generation toward where the player goes", async () => {
  const env = createEnv();
  const runtime = create(env);
  await runtime.warm();
  const blocker = runtime.submit("spin", tagged(1), {kernel: "mesh"});
  const blocker2 = runtime.submit("spin", tagged(2), {kernel: "mesh"});
  await new Promise((resolve) => setTimeout(resolve, 5));
  const jobs = [];
  for (const cx of [-6, -3, 3, 6]) jobs.push(runtime.submit("gen_chunk", tagged(100 + cx), {cx, cz: 0, near: true, priorityClass: 2}));
  // The player flies west (-X) at about 12 blocks per second.
  for (let i = 0; i <= 8; i++) runtime.setViewer({x: 8 - i * 3, z: 8, t: i * 250});
  runtime.rescore();
  await Promise.all([blocker, blocker2]);
  await Promise.all(jobs);
  const order = env.log.order.filter((entry) => entry.startsWith("worldgen")).map((entry) => Number(entry.split(":")[2]) - 100);
  assert.deepEqual(order.slice(0, 2), [-3, -6], `chunks ahead (west) first, got ${order}`);
  runtime.terminate();
});

check("memory budget backpressure refuses far work and keeps visible work queued", async () => {
  const env = createEnv();
  const runtime = create(env, {switches: {enabled: true, off: new Set(), simd: "auto", size: 1, budgetBytes: 8 * 1024 * 1024}});
  await runtime.warm();
  const accepted = [];
  let refused = null;
  for (let i = 0; i < 16 && !refused; i++) {
    try {
      accepted.push(runtime.submit("gen_chunk", tagged(i, 1 << 20), {cx: 50 + i, cz: 50, priorityClass: 3}).catch(() => null));
    } catch (error) {
      refused = error;
    }
  }
  const rejection = await runtime.submit("gen_chunk", tagged(99, 1 << 20), {cx: 90, cz: 90, priorityClass: 3}).then(() => null, (error) => error);
  assert.equal(rejection && rejection.code, "backpressure");
  const visible = runtime.submit("mesh_section", tagged(7, 1 << 20), {visible: true});
  assert.equal(tagOf(await visible), 7, "visible work is never refused");
  await Promise.all(accepted);
  assert.ok(runtime.telemetry().backpressure >= 1);
  runtime.terminate();
});

check("queued jobs charge their payload only; the result estimate is charged while they run", async () => {
  const env = createEnv();
  const runtime = create(env, {switches: {enabled: true, off: new Set(), simd: "auto", size: 1, budgetBytes: 16 * 1024 * 1024}});
  await runtime.warm();
  const blocker = runtime.submit("spin", tagged(1), {kernel: "mesh"});
  await new Promise((resolve) => setTimeout(resolve, 5));
  // 200 visible mesh jobs with a 1 MB result estimate each: 200 MB of estimates, 3.2 KB queued.
  const meshes = [];
  for (let i = 0; i < 200; i++) meshes.push(runtime.submit("mesh_section", tagged(1000 + i), {visible: true, resultBytes: 1 << 20}));
  assert.equal(runtime.budget.queuedBytes, 200 * 16, "only payloads are queued");
  assert.ok(runtime.budget.residentPressure() < 1, "the queue is not memory the workers hold");
  const nearGen = runtime.submit("gen_chunk", tagged(2), {cx: 1, cz: 0, priorityClass: 2});
  assert.equal(tagOf(await nearGen), 2, "near generation still queues behind a long P0 queue");
  await blocker;
  assert.equal((await Promise.all(meshes)).length, 200);
  assert.equal(runtime.budget.queuedBytes, 0);
  assert.equal(runtime.budget.inflightBytes, 0);
  runtime.terminate();
});

check("load generations isolate stale worker lifecycle replies", async () => {
  const env = createEnv();
  const runtime = create(env);
  assert.equal(tagOf(await runtime.submit("mesh_section", tagged(3), {kernel: "mesh"})), 3);
  const slot = runtime.slots[0];
  const previous = slot.loaded.get("mesh");
  const oldGeneration = previous.generation;
  const newGeneration = oldGeneration + 1;
  slot.generations.set("mesh", newGeneration);
  // An old unload reply can arrive after a newer load has been registered.
  slot.loaded.delete("mesh");
  runtime.budget.setInstance(slot.id + ":mesh", 0);
  slot.loaded.set("mesh", {state: "loading", generation: newGeneration, memoryBytes: 0, lastUsed: Date.now(), memory: null});
  runtime.budget.setInstance(slot.id + ":mesh", 1 << 20);
  runtime.onMessage(slot, {type: "unloaded", kernel: "mesh", generation: oldGeneration});
  assert.equal(slot.loaded.get("mesh") && slot.loaded.get("mesh").state, "loading", "the newer load stays");
  assert.equal(runtime.budget.instances.get(slot.id + ":mesh"), 1 << 20);
  runtime.onMessage(slot, {type: "loaded", kernel: "mesh", generation: oldGeneration, memoryBytes: 3 << 20});
  assert.equal(slot.loaded.get("mesh").state, "loading", "a stale loaded reply cannot ready the newer load");
  assert.equal(runtime.budget.instances.get(slot.id + ":mesh"), 1 << 20, "stale load memory is ignored");
  runtime.onMessage(slot, {type: "loaded", kernel: "mesh", generation: newGeneration, memoryBytes: 3 << 20});
  assert.equal(runtime.budget.instances.get(slot.id + ":mesh"), 3 << 20, "the instance is accounted when it loads");
  // A ready instance is still dropped by its unloaded reply (disableKernel relies on it).
  runtime.onMessage(slot, {type: "unloaded", kernel: "mesh", generation: newGeneration});
  assert.equal(slot.loaded.has("mesh"), false);
  runtime.terminate();
});

check("port clients: keys are scoped per port, shared results are copied, detach cancels", async () => {
  const env = createEnv();
  const runtime = create(env);
  await runtime.warm();
  const oldChannel = new MessageChannel();
  const newChannel = new MessageChannel();
  runtime.attachPort(oldChannel.port1);
  runtime.attachPort(newChannel.port1);
  const oldClient = env.Runtime.connect(oldChannel.port2);
  const newClient = env.Runtime.connect(newChannel.port2);
  await Promise.all([oldClient.ready, newClient.ready]);
  const blocker = runtime.submit("spin", tagged(1), {kernel: "mesh"});
  await new Promise((resolve) => setTimeout(resolve, 5));
  // A restarted server Worker reuses keys and versions: the two ports never share a job.
  const fromOld = oldClient.submit("light_section", tagged(31), {kernel: "light", key: "light:0,0", version: 1});
  const fromNew = newClient.submit("light_section", tagged(32), {kernel: "light", key: "light:0,0", version: 1});
  // One port asking twice for the same job gets the result twice (the first answer is a copy).
  const twiceA = newClient.submit("light_section", tagged(33), {kernel: "light", key: "light:1,0", version: 1});
  const twiceB = newClient.submit("light_section", tagged(33), {kernel: "light", key: "light:1,0", version: 1});
  await new Promise((resolve) => setTimeout(resolve, 5));
  // The old Worker is replaced: its queued job is cancelled, not left holding a worker.
  assert.equal(runtime.detachPort(oldChannel.port1), true);
  const oldOutcome = await fromOld.then(() => "result", (error) => error.code);
  assert.equal(oldOutcome, "cancelled");
  assert.equal(tagOf(await fromNew), 32);
  assert.equal(tagOf(await twiceA), 33);
  assert.equal(tagOf(await twiceB), 33);
  await blocker;
  assert.equal(runtime.detachPort(oldChannel.port1), false, "detaching twice is a no-op");
  runtime.terminate();
  oldChannel.port2.close();
  newChannel.port2.close();
});

check("port clients cap pending kernel requests", async () => {
  const env = createEnv();
  const runtime = create(env, {maxPendingRequests: 2});
  const channel = new MessageChannel();
  runtime.attachPort(channel.port1);
  const client = env.Runtime.connect(channel.port2);
  await client.ready;
  const blocker = runtime.submit("spin", tagged(1), {kernel: "mesh"});
  await new Promise((resolve) => setTimeout(resolve, 5));
  const first = client.submit("light_section", tagged(11), {kernel: "light"});
  const second = client.submit("light_section", tagged(12), {kernel: "light"});
  const third = client.submit("light_section", tagged(13), {kernel: "light"});
  const outcome = await Promise.race([
    third.then(() => "accepted", (error) => error.code),
    new Promise((resolve) => setTimeout(() => resolve("timeout"), 100)),
  ]);
  assert.equal(outcome, "request-cap", "the third port request must be rejected at the configured cap");
  await blocker;
  await Promise.all([first, second]);
  runtime.terminate();
  channel.port2.close();
});

check("port request cap preserves same-key dedupe", async () => {
  const env = createEnv();
  const runtime = create(env, {maxPendingRequests: 1});
  const channel = new MessageChannel();
  runtime.attachPort(channel.port1);
  const client = env.Runtime.connect(channel.port2);
  await client.ready;
  const blocker = runtime.submit("spin", tagged(1), {kernel: "mesh"});
  await new Promise((resolve) => setTimeout(resolve, 5));
  const first = client.submit("light_section", tagged(21), {kernel: "light", key: "same", version: 1});
  const duplicate = client.submit("light_section", tagged(21), {kernel: "light", key: "same", version: 1});
  const different = await client.submit("light_section", tagged(22), {kernel: "light", key: "other", version: 1})
    .then(() => "accepted", (error) => error.code);
  assert.equal(different, "request-cap", "a distinct request remains bounded at the cap");
  assert.equal(tagOf(await first), 21);
  assert.equal(tagOf(await duplicate), 21);
  await blocker;
  runtime.terminate();
  channel.port2.close();
});

check("port dedupe waiters stay bounded", async () => {
  const env = createEnv();
  const runtime = create(env, {maxPendingRequests: 1, maxPendingWaiters: 2});
  const channel = new MessageChannel();
  runtime.attachPort(channel.port1);
  const client = env.Runtime.connect(channel.port2);
  await client.ready;
  const blocker = runtime.submit("spin", tagged(1), {kernel: "mesh"});
  await new Promise((resolve) => setTimeout(resolve, 5));
  const first = client.submit("light_section", tagged(31), {kernel: "light", key: "same", version: 1});
  const duplicate = client.submit("light_section", tagged(31), {kernel: "light", key: "same", version: 1});
  const overflow = await client.submit("light_section", tagged(31), {kernel: "light", key: "same", version: 1})
    .then(() => "accepted", (error) => error.code);
  assert.equal(overflow, "request-cap", "duplicate waiters must be bounded");
  assert.equal(tagOf(await first), 31);
  assert.equal(tagOf(await duplicate), 31);
  await blocker;
  runtime.terminate();
  channel.port2.close();
});

check("a kernel that keeps trapping is disabled; its callers fall back", async () => {
  const env = createEnv();
  const runtime = create(env, {maxKernelFailures: 3});
  for (let i = 0; i < 3; i++) {
    const error = await runtime.submit("trap", tagged(i)).then(() => null, (failure) => failure);
    assert.equal(error.code, "kernel-trap");
  }
  assert.equal(runtime.available("light"), false);
  assert.equal(runtime.available("mesh"), true);
  const disabled = await runtime.submit("light_section", tagged(5)).then(() => null, (error) => error);
  assert.equal(disabled.code, "kernel-disabled");
  assert.equal(tagOf(await runtime.submit("mesh_section", tagged(6))), 6, "other kernels keep running");
  runtime.terminate();
});

check("switched-off kernels, no SIMD and dedupe/supersede by key", async () => {
  const env = createEnv({simd: false});
  const runtime = create(env, {switches: {enabled: true, off: new Set(["light"]), simd: "auto", size: 1, budgetBytes: 0}});
  assert.equal(runtime.available("light"), false);
  const error = await runtime.submit("light_section", tagged(1)).then(() => null, (failure) => failure);
  assert.equal(error.code, "kernel-disabled");
  assert.equal(runtime.features.simd, false);
  const meshKernel = Object.assign({}, KERNELS.mesh, {variants: {
    simd: KERNELS.mesh.variants.simd.bytes && {bytes: encodeModule({name: "mesh-simd", kinds: ["mesh_section", "spin"]})},
    baseline: {bytes: encodeModule({name: "mesh-baseline", kinds: ["mesh_section", "spin"]})},
  }});
  const noSimd = create(env, {kernels: {mesh: meshKernel}});
  await noSimd.warm();
  assert.equal(noSimd.telemetry().kernels.mesh.variant, "baseline");
  const first = noSimd.submit("mesh_section", tagged(1), {key: "section:0:0:0", version: 1});
  const same = noSimd.submit("mesh_section", tagged(1), {key: "section:0:0:0", version: 1});
  assert.equal(first, same, "same key and version share one job");
  const newer = noSimd.submit("mesh_section", tagged(2), {key: "section:0:0:0", version: 2});
  const superseded = await first.then(() => null, (failure) => failure);
  assert.equal(superseded.code, "superseded");
  assert.equal(tagOf(await newer), 2);
  runtime.terminate();
  noSimd.terminate();
});

check("the server Worker client: MessagePort submit, viewer and status", async () => {
  const env = createEnv();
  const runtime = create(env);
  const channel = new MessageChannel();
  runtime.attachPort(channel.port1);
  const client = env.Runtime.connect(channel.port2);
  const status = await client.ready;
  assert.equal(status.enabled, true);
  assert.equal(client.available("worldgen"), true);
  client.setViewer({x: 100, z: -40, vx: 0, vz: -20, t: 0});
  const result = await client.submit("gen_chunk", tagged(77), {kernel: "worldgen", cx: 6, cz: -4});
  assert.equal(tagOf(result), 77);
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(runtime.telemetry().motion.known, true, "the server's viewer reached the predictor");
  runtime.terminate();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(client.available(), false, "a terminated runtime tells its clients");
  channel.port2.close();
});

check("a load_model_table job primes every new mesher instance, once per load", async () => {
  const env = createEnv();
  const mesher = {kinds: ["mesh_section", "load_model_table"], variants: {simd: {bytes: encodeModule(
    {name: "mesher", kinds: ["mesh_section", "load_model_table"], trim: true})}}};
  const runtime = create(env, {kernels: {mesher}});
  assert.equal(tagOf(await runtime.submit("load_model_table", tagged(500, 64), {kernel: "mesher", version: 3})), 500);
  assert.equal(runtime.telemetry().kernels.mesher.primer.bytes, 64);
  assert.equal(runtime.budget.instances.get("primer:mesher"), 64, "the retained primer counts in the budget");
  assert.equal(tagOf(await runtime.submit("mesh_section", tagged(501), {kernel: "mesher"})), 501);
  const before = env.log.order.filter((entry) => entry === "mesher:load_model_table:500").length;
  assert.equal(before, 1, "the job that set the primer is not primed twice");
  // The worker dies; its replacement runs the primer before its first mesh job.
  runtime.crash(runtime.slots[0], {code: "worker-crashed", message: "test crash"});
  await new Promise((resolve) => setTimeout(resolve, 5));
  const mark = env.log.order.length;
  assert.equal(tagOf(await runtime.submit("mesh_section", tagged(600), {kernel: "mesher"})), 600);
  assert.deepEqual(env.log.order.slice(mark), ["mesher:load_model_table:500", "mesher:mesh_section:600"]);
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(runtime.telemetry().primed, 1);
  // An older version never replaces the primer; clearing it stops priming.
  assert.equal(runtime.setPrimer("mesher", {kind: "load_model_table", payload: tagged(400, 8), version: 2}), false);
  assert.equal(runtime.setPrimer("mesher", null), true);
  assert.equal(runtime.budget.instances.has("primer:mesher"), false);
  runtime.terminate();
});

check("memory pressure trims loaded instances, at most once per interval", async () => {
  const env = createEnv();
  const mesher = {kinds: ["mesh_section"], variants: {simd: {bytes: encodeModule(
    {name: "mesher", kinds: ["mesh_section"], trim: true})}}};
  const runtime = create(env, {kernels: {mesher}, trimIntervalMs: 60_000});
  assert.equal(tagOf(await runtime.submit("mesh_section", tagged(1), {kernel: "mesher"})), 1);
  runtime.reportPressure(0.5);
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.deepEqual(env.log.trims, [], "no trim below the threshold");
  runtime.reportPressure(0.95);
  runtime.reportPressure(0.97);
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.deepEqual(env.log.trims, ["mesher"], "one trim per interval");
  assert.equal(runtime.telemetry().trims, 1);
  assert.equal(tagOf(await runtime.submit("mesh_section", tagged(2), {kernel: "mesher"})), 2, "the instance keeps working");
  runtime.terminate();
});

let failed = 0;
for (const [name, fn] of checks) {
  try {
    await fn();
    console.log(`ok   ${name}`);
  } catch (error) {
    failed++;
    console.log(`FAIL ${name}`);
    console.log(error && error.stack || error);
  }
}
if (failed) {
  console.log(`${failed} of ${checks.length} kernel runtime checks failed`);
  process.exit(1);
}
console.log(`all ${checks.length} kernel runtime checks passed`);
