#!/usr/bin/env node
// Kernel worker pool runtime (port/web/kernels).
//
//   node port/scripts/kernel-pool-smoke.mjs
//
// Runs kernel-pool.js in a node:vm context whose Worker is simulated: every fake Worker runs
// the real kernel-worker.js in its own vm context, messages cross with a delay through
// structuredClone (so transfer lists really detach buffers), and a fake WebAssembly kernel
// implements the alloc/run_<kind>/reset ABI in JavaScript. Kernels can trap, report an error
// status, crash or hang their worker, which exercises the pool's recovery paths.

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const kernelsDir = new URL("../web/kernels/", import.meta.url);
const poolSource = await readFile(new URL("kernel-pool.js", kernelsDir), "utf8");
const workerSource = await readFile(new URL("kernel-worker.js", kernelsDir), "utf8");

const KERNELS = ["echo", "spin", "fail", "trap", "big", "crash_once", "crash_always", "hang_once"];

// --- fake WebAssembly ------------------------------------------------------------------------

const encodeModule = (desc) => {
  const body = new TextEncoder().encode(JSON.stringify(desc));
  const bytes = new Uint8Array(8 + body.length);
  bytes.set([0, 0x61, 0x73, 0x6d, 1, 0, 0, 0]);
  bytes.set(body, 8);
  return bytes;
};
const decodeModule = (bytes) => JSON.parse(new TextDecoder().decode(new Uint8Array(bytes).subarray(8)));
const isModule = (value) => value && value.fakeKernelModule === 1;

function moduleExports(desc) {
  return [
    {name: "memory", kind: "memory"},
    {name: "alloc", kind: "function"},
    {name: "reset", kind: "function"},
    ...desc.kernels.map((kind) => ({name: `run_${kind}`, kind: "function"})),
  ];
}

function makeInstance(desc, worker, log) {
  if (desc.failInit) throw new Error("simulated instantiate failure");
  log.instances++;
  let buffer = new ArrayBuffer(1 << 16);
  let top = 64;
  const memory = {get buffer() { return buffer; }};
  const alloc = (length) => {
    const ptr = (top + 7) & ~7;
    top = ptr + length;
    // Growing detaches the old buffer, like memory.grow does in a real instance.
    while (top > buffer.byteLength) buffer = buffer.transfer(buffer.byteLength * 2);
    return ptr;
  };
  const input = (ptr, length) => new Uint8Array(buffer, ptr, length).slice();
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
  const firstU32 = (bytes) => (bytes.length >= 4 ? new DataView(bytes.buffer).getUint32(0, true) : -1);
  const echo = (ptr, length) => {
    const bytes = input(ptr, length);
    log.order.push(firstU32(bytes));
    return output(0, bytes);
  };
  const kernels = {
    echo,
    spin(ptr, length) {
      const bytes = input(ptr, length);
      log.order.push("spin");
      const until = Date.now() + firstU32(bytes);
      while (Date.now() < until) { /* busy, like a long kernel */ }
      return output(0, bytes);
    },
    fail: () => output(7, new TextEncoder().encode("bad input")),
    trap: () => { throw new Error("unreachable executed"); },
    big: () => output(0, new Uint8Array(1 << 20).fill(9)),
    crash_once(ptr, length) {
      if (log.flags.has("crashed")) return echo(ptr, length);
      log.flags.add("crashed");
      return worker.crash("simulated abort");
    },
    crash_always: () => worker.crash("simulated abort"),
    hang_once(ptr, length) {
      if (log.flags.has("hung")) return echo(ptr, length);
      log.flags.add("hung");
      return worker.freeze();
    },
  };
  // desc.oomAbove: the exported alloc fails (returns 0) like a failed memory.grow.
  const exportedAlloc = desc.oomAbove === undefined ? alloc : (length) => {
    if (length <= desc.oomAbove) return alloc(length);
    log.oomMemory = memory;
    return 0;
  };
  const exports = {memory, alloc: exportedAlloc, reset: () => { top = 64; }};
  for (const kind of desc.kernels) {
    exports[`run_${kind}`] = (ptr, length) => {
      log.runs[kind] = (log.runs[kind] || 0) + 1;
      return kernels[kind](ptr, length);
    };
  }
  return {exports};
}

function workerWebAssembly(worker, log) {
  return {
    Module: {exports: moduleExports, imports: () => []},
    compile: async (bytes) => decodeModule(bytes),
    async instantiate(source) {
      if (isModule(source)) return makeInstance(source, worker, log);
      const module = decodeModule(source);
      return {module, instance: makeInstance(module, worker, log)};
    },
  };
}

// --- fake browser ----------------------------------------------------------------------------

function createEnv({hardwareConcurrency = 8, delay = 1, rejectModuleClone = false} = {}) {
  const blobs = new Map();
  const log = {posts: [], workers: [], revoked: [], runs: {}, order: [], flags: new Set(), instances: 0};

  class FakeWorker {
    constructor(url, options) {
      const source = blobs.get(url);
      assert.ok(source, `worker started from unknown url ${url}`);
      this.id = log.workers.length;
      this.name = options && options.name;
      this.terminated = false;
      this.dead = false;
      this.frozen = false;
      this.onmessage = null;
      this.onerror = null;
      log.workers.push(this);
      const alive = () => !this.terminated && !this.dead && !this.frozen;
      this.ctx = vm.createContext({
        console, performance, TextDecoder,
        setTimeout: (fn, ms) => setTimeout(() => { if (alive()) fn(); }, ms),
        WebAssembly: workerWebAssembly(this, log),
        postMessage: (message, transfer) => this.fromWorker(message, transfer),
      });
      vm.runInContext("globalThis.self = globalThis;", this.ctx);
      vm.runInContext(source, this.ctx, {filename: "kernel-worker.js"});
    }

    postMessage(message, transfer = []) {
      if (this.terminated) return;
      if (rejectModuleClone && message.type === "init" && message.module) {
        throw new Error("DataCloneError: WebAssembly.Module could not be cloned");
      }
      const data = structuredClone(message, {transfer});
      log.posts.push({dir: "toWorker", worker: this.id, type: message.type, transfer: [...transfer], data});
      setTimeout(() => {
        if (!this.terminated && !this.dead && !this.frozen) this.ctx.onmessage({data});
      }, delay);
    }

    fromWorker(message, transfer = []) {
      if (this.terminated || this.dead || this.frozen) return;
      const data = structuredClone(message, {transfer});
      log.posts.push({dir: "toMain", worker: this.id, type: message.type, transfer: [...transfer], data});
      setTimeout(() => {
        if (!this.terminated && this.onmessage) this.onmessage({data});
      }, delay);
    }

    crash(message) {
      this.dead = true;
      setTimeout(() => {
        if (!this.terminated && this.onerror) this.onerror({message, preventDefault() {}});
      }, delay);
      throw new Error("worker died");
    }

    freeze() {
      this.frozen = true;
      throw new Error("worker hung");
    }

    terminate() {
      this.terminated = true;
    }
  }

  let blobSeq = 0;
  const context = vm.createContext({
    console, performance, setTimeout, clearTimeout,
    navigator: {hardwareConcurrency},
    Worker: FakeWorker,
    Blob: class {
      constructor(parts) { this.text = parts.join(""); }
    },
    URL: {
      createObjectURL(blob) {
        const url = `blob:kernel-pool-smoke/${++blobSeq}`;
        blobs.set(url, blob.text);
        return url;
      },
      revokeObjectURL(url) {
        blobs.delete(url);
        log.revoked.push(url);
      },
    },
    WebAssembly: {
      compile: async (bytes) => decodeModule(bytes),
      Module: {exports: moduleExports, imports: () => []},
    },
  });
  vm.runInContext(poolSource, context, {filename: "kernel-pool.js"});
  context.GaiusKernelMirror = {enabled: () => true, allowed: () => true};
  const api = context.GaiusKernelPool;
  const pools = [];
  const create = async (options = {}, desc = {}) => {
    const module = {fakeKernelModule: 1, kernels: KERNELS, ...desc};
    const pool = await api.create({moduleBytes: encodeModule(module), workerSource, ...options});
    pools.push(pool);
    return pool;
  };
  return {api, log, create, pools};
}

// --- helpers ---------------------------------------------------------------------------------

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const ticks = async (count = 8) => { for (let i = 0; i < count; i++) await Promise.resolve(); };
const u32 = (value) => new Uint32Array([value]).buffer;
const readU32 = (buffer) => new DataView(buffer).getUint32(0, true);

async function waitFor(predicate, what, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await sleep(1);
  }
}

const allReady = (pool) => () => {
  const workers = pool.telemetry().workers;
  return workers.length > 0 && workers.every((worker) => worker.ready);
};

async function rejectsWith(promise, code) {
  const error = await promise.then(
    (value) => { throw new Error(`expected rejection ${code}, resolved with ${value}`); },
    (rejection) => rejection);
  assert.equal(error.code, code, `expected ${code}, got ${error.code}: ${error.message}`);
  return error;
}

const tests = [];
const test = (name, fn) => tests.push({name, fn});

// --- tests -----------------------------------------------------------------------------------

test("adaptive size: clamp(cores - 2, 1, 8) minus reserve, at least 1", async () => {
  const {api, create} = createEnv({hardwareConcurrency: 16});
  const cases = [
    [undefined, 0, 2], [1, 0, 1], [2, 0, 1], [3, 0, 1], [4, 0, 2], [8, 0, 6], [10, 0, 8],
    [64, 0, 8], [12, 2, 6], [4, 5, 1], [16, 7, 1], [16, 3, 5],
  ];
  for (const [cores, reserve, expected] of cases) {
    assert.equal(api.autoSize(cores, reserve), expected, `autoSize(${cores}, ${reserve})`);
  }
  const pool = await create({size: "auto", reserve: 3});
  assert.equal(pool.telemetry().targetSize, 5);
  assert.equal(pool.size, 5);
});

test("grow, shrink, explicit sizes and reserve keep within bounds", async () => {
  const {log, create} = createEnv({hardwareConcurrency: 16});
  const pool = await create({size: "auto"});
  assert.equal(pool.size, 8);
  assert.equal(log.workers.length, 8);
  await waitFor(allReady(pool), "8 ready workers");
  assert.equal(pool.shrink(3), 5);
  assert.equal(pool.size, 5);
  assert.equal(log.workers.filter((worker) => worker.terminated).length, 3, "idle workers retired at once");
  assert.equal(pool.grow(2), 7);
  assert.equal(log.workers.length, 10);
  assert.equal(pool.setSize(100), 8, "explicit size clamps to maxSize");
  assert.equal(pool.setSize(0), 1, "explicit size clamps to 1");
  assert.equal(pool.setSize("auto"), 8);
  assert.equal(pool.setReserve(4), 4);
  assert.equal(pool.size, 4);
  assert.equal(pool.telemetry().retired, log.workers.filter((worker) => worker.terminated).length);

  // Shrinking under load drains busy workers instead of killing their jobs.
  pool.setSize(2);
  await waitFor(allReady(pool), "2 ready workers");
  const jobs = [pool.submit("spin", u32(15)), pool.submit("spin", u32(15))];
  await waitFor(() => pool.telemetry().inFlight === 2, "two jobs in flight");
  pool.setSize(1);
  assert.equal(pool.telemetry().workers.filter((worker) => worker.draining).length, 1);
  await Promise.all(jobs);
  await waitFor(() => pool.telemetry().workers.length === 1, "drained worker retired");
});

test("transfer lists move payloads and results without copies", async () => {
  const {log, create} = createEnv();
  const pool = await create({size: 1});
  await waitFor(allReady(pool), "ready worker");

  const payload = u32(42);
  const moved = pool.submit("echo", payload);
  await ticks();
  assert.equal(payload.byteLength, 0, "transferred payload is detached on the main side");
  const jobPost = log.posts.find((post) => post.type === "job");
  assert.deepEqual(jobPost.transfer, [payload]);
  const result = await moved;
  assert.equal(readU32(result), 42);
  const resultPost = log.posts.find((post) => post.type === "result");
  assert.equal(resultPost.transfer.length, 1);
  assert.equal(resultPost.transfer[0].byteLength, 0, "result buffer was moved out of the worker");
  assert.equal(resultPost.data.result, result, "the promise resolves with the transferred buffer itself");

  const kept = u32(43);
  assert.equal(readU32(await pool.submit("echo", kept, {transfer: false})), 43);
  assert.equal(kept.byteLength, 4, "transfer: false leaves the payload with the caller");
  assert.deepEqual(log.posts.filter((post) => post.type === "job")[1].transfer, []);

  const whole = new Uint8Array(16).fill(5);
  const part = new Uint8Array(whole.buffer, 4, 8);
  const partResult = new Uint8Array(await pool.submit("echo", part));
  assert.deepEqual([...partResult], [5, 5, 5, 5, 5, 5, 5, 5]);
  assert.equal(whole.byteLength, 16, "a partial view is copied, its buffer stays usable");

  const big = await pool.submit("big", new ArrayBuffer(0));
  assert.equal(big.byteLength, 1 << 20, "results survive a memory grow inside the kernel");
});

test("jobs run by priority, FIFO within a priority", async () => {
  const {log, create} = createEnv();
  const pool = await create({size: 1, maxInFlightPerWorker: 1});
  const jobs = [[50, 5], [10, 1], [30, 3], [11, 1], [0, 0], [20, 2], [12, 1]];
  const done = jobs.map(([id, priority]) => pool.submit("echo", u32(id), {priority}));
  await Promise.all(done);
  assert.deepEqual(log.order, [0, 10, 11, 12, 20, 30, 50]);

  // Jobs arriving while the worker is busy are ordered too.
  log.order.length = 0;
  const blocker = pool.submit("spin", u32(10), {priority: 9});
  await waitFor(() => log.runs.spin === 1, "blocker running");
  const late = [[7, 4], [3, 2], [8, 4], [1, -1]].map(([id, priority]) => pool.submit("echo", u32(id), {priority}));
  await Promise.all([blocker, ...late]);
  assert.deepEqual(log.order, ["spin", 1, 3, 7, 8]);
});

test("same key and version de-duplicates; newer versions supersede; older ones are stale", async () => {
  const {log, create} = createEnv();
  const pool = await create({size: 1, maxInFlightPerWorker: 1});
  const blocker = pool.submit("spin", u32(5), {priority: -10});
  const a1 = pool.submit("echo", u32(1), {key: "a", version: 1});
  const a2 = pool.submit("echo", u32(1), {key: "a", version: 1});
  assert.equal(a1, a2, "duplicate submit shares the first promise");
  const b1 = pool.submit("echo", u32(2), {key: "b", version: 1, priority: 1});
  const b2 = pool.submit("echo", u32(3), {key: "b", version: 2, priority: 1});
  const c3 = pool.submit("echo", u32(4), {key: "c", version: 3});
  const c2 = pool.submit("echo", u32(99), {key: "c", version: 2});
  const d = pool.submit("echo", u32(5), {key: "d", priority: 9});
  const e = pool.submit("echo", u32(6), {priority: 5});
  assert.equal(pool.submit("echo", u32(5), {key: "d", priority: 0}), d, "re-submit raises the priority");
  await rejectsWith(b1, "superseded");
  await rejectsWith(c2, "stale");
  const results = await Promise.all([blocker, a1, b2, c3, d, e]);
  assert.deepEqual(results.slice(1).map(readU32), [1, 3, 4, 5, 6]);
  assert.deepEqual(log.order, ["spin", 1, 4, 5, 3, 6]);
  assert.equal(log.runs.echo, 5, "superseded and duplicate jobs never ran");

  // Superseding a job that is already computing drops its result.
  const x1 = pool.submit("spin", u32(15), {key: "x", version: 1});
  await waitFor(() => log.runs.spin === 2, "x v1 running");
  const x2 = pool.submit("echo", u32(77), {key: "x", version: 2});
  await rejectsWith(x1, "superseded");
  assert.equal(readU32(await x2), 77);
  const stats = pool.telemetry();
  assert.equal(stats.dropped, 1, "the stale in-flight result was dropped");
  assert.equal(stats.superseded, 2);
  assert.equal(stats.deduped, 2);
  assert.equal(stats.stale, 1);
});

test("cancel removes queued jobs, skips jobs waiting in a worker and drops running ones", async () => {
  const {log, create} = createEnv();
  const pool = await create({size: 1, maxInFlightPerWorker: 2});
  await waitFor(allReady(pool), "ready worker");

  // Both jobs reach the worker; the second is cancelled before the worker starts it.
  const running = pool.submit("spin", u32(10), {key: "A"});
  const waiting = pool.submit("echo", u32(1), {key: "B"});
  await ticks();
  assert.equal(pool.telemetry().inFlight, 2);
  assert.equal(pool.cancel("B"), true);
  await rejectsWith(waiting, "cancelled");
  await running;
  await waitFor(() => log.posts.some((post) => post.type === "cancelled"), "worker skip notice");
  assert.equal(log.runs.echo, undefined, "the cancelled job never ran");
  await waitFor(() => pool.telemetry().inFlight === 0, "worker slots released");

  // A queued job never leaves the pool.
  const blocker = pool.submit("spin", u32(5), {key: "S"});
  const third = pool.submit("echo", u32(2), {key: "Q1"});
  const queued = pool.submit("echo", u32(3), {key: "Q2"});
  await ticks();
  assert.equal(pool.telemetry().queued, 1);
  assert.equal(pool.cancel("Q2"), true);
  assert.equal(pool.cancel("nope"), false);
  await rejectsWith(queued, "cancelled");
  assert.equal(pool.telemetry().queued, 0);

  // A running job finishes, but its result is dropped.
  await waitFor(() => log.runs.spin === 2, "S running");
  assert.equal(pool.cancel("S"), true);
  await rejectsWith(blocker, "cancelled");
  assert.equal(readU32(await third), 2);
  await waitFor(() => pool.telemetry().dropped === 1, "dropped result");
  assert.ok(!log.order.includes(3), "the queued cancelled job never ran");

  const controller = new AbortController();
  const aborted = pool.submit("spin", u32(1), {signal: controller.signal});
  controller.abort();
  await rejectsWith(aborted, "cancelled");
  assert.equal(pool.telemetry().cancelled, 4);
});

test("kernel errors, traps and unknown kinds reject without killing the worker", async () => {
  const {log, create} = createEnv();
  const pool = await create({size: 1});
  const failed = await rejectsWith(pool.submit("fail", u32(0)), "kernel-error");
  assert.equal(failed.status, 7);
  assert.equal(failed.message, "bad input");
  const instancesBefore = log.instances;
  await rejectsWith(pool.submit("trap", u32(0)), "kernel-trap");
  assert.equal(readU32(await pool.submit("echo", u32(5))), 5, "the worker re-instantiated after the trap");
  assert.equal(log.instances, instancesBefore + 1);
  await rejectsWith(pool.submit("missing", u32(0)), "unknown-kind");
  const stats = pool.telemetry();
  assert.equal(stats.failed, 2);
  assert.equal(stats.crashes, 0);
  assert.equal(stats.kinds.trap.failed, 1);
  assert.equal(log.workers.length, 1);
});

test("a failed payload allocation traps instead of writing to address 0", async () => {
  const {log, create} = createEnv();
  const pool = await create({size: 1}, {oomAbove: 1024});
  await waitFor(allReady(pool), "ready worker");
  const instancesBefore = log.instances;
  const payload = new Uint8Array(4096).fill(0xab);
  const error = await rejectsWith(pool.submit("echo", payload), "kernel-trap");
  assert.match(error.message, /out of memory/);
  assert.equal(log.runs.echo, undefined, "the kernel never ran on the unallocated payload");
  assert.ok(new Uint8Array(log.oomMemory.buffer, 0, 4096).every((byte) => byte === 0),
    "the payload was written to address 0");
  assert.equal(readU32(await pool.submit("echo", u32(6))), 6);
  assert.equal(log.instances, instancesBefore + 1, "the worker re-instantiated after the failed allocation");
  assert.equal(log.workers.length, 1);
});

test("a crashed worker is replaced and its in-flight jobs are re-queued once", async () => {
  const {log, create} = createEnv();
  const pool = await create({size: 1, maxInFlightPerWorker: 2, respawnBackoffMs: 1});
  await waitFor(allReady(pool), "ready worker");
  const payload = u32(7);
  const crashing = pool.submit("crash_once", payload);
  const neighbour = pool.submit("echo", u32(8));
  await ticks();
  assert.equal(payload.byteLength, 0, "payload was transferred; the pool kept a retry copy");
  assert.deepEqual([readU32(await crashing), readU32(await neighbour)], [7, 8]);
  let stats = pool.telemetry();
  assert.equal(stats.crashes, 1);
  assert.equal(stats.retried, 2);
  assert.equal(stats.respawns, 1);
  assert.equal(log.workers.length, 2);
  assert.equal(log.workers[0].terminated, true);

  const always = await rejectsWith(pool.submit("crash_always", u32(1)), "worker-crashed");
  assert.match(always.message, /attempt 2/);
  assert.equal(readU32(await pool.submit("echo", u32(9))), 9, "the pool recovers after giving up on a job");
  stats = pool.telemetry();
  assert.equal(stats.crashes, 3);
  assert.equal(stats.kinds.crash_always.failed, 1);

  const {create: createOther} = createEnv();
  const strict = await createOther({size: 1});
  await rejectsWith(strict.submit("crash_once", u32(1), {retry: false}), "worker-crashed");
  const large = await createOther({size: 1, retryCopyLimit: 2});
  await rejectsWith(large.submit("crash_always", u32(1)), "worker-crashed");
  assert.equal(large.telemetry().retried, 0, "payloads over retryCopyLimit are not kept for a retry");
});

test("a hung worker is detected by the watchdog and replaced", async () => {
  const {log, create} = createEnv();
  // Generous: one round trip through coarse (~16 ms on Windows) timers takes several ticks.
  const pool = await create({size: 1, jobTimeoutMs: 250});
  assert.equal(readU32(await pool.submit("hang_once", u32(4))), 4);
  const stats = pool.telemetry();
  assert.equal(stats.crashes, 1);
  assert.equal(stats.retried, 1);
  assert.equal(log.workers.length, 2);
});

test("workers that cannot start halt the pool instead of looping", async () => {
  const {create} = createEnv();
  const pool = await create({size: 2, maxRespawnStreak: 2, respawnBackoffMs: 1}, {failInit: true});
  await rejectsWith(pool.submit("echo", u32(1)), "pool-unavailable");
  assert.ok(pool.telemetry().halted);
  await rejectsWith(pool.submit("echo", u32(1)), "pool-unavailable");
});

test("engines that cannot clone a Module get the module bytes", async () => {
  const {log, create} = createEnv({rejectModuleClone: true});
  const pool = await create({size: 2});
  assert.equal(readU32(await pool.submit("echo", u32(11))), 11);
  const inits = log.posts.filter((post) => post.type === "init");
  assert.equal(inits.length, 2);
  assert.ok(inits.every((post) => post.data.bytes && post.transfer.length === 1));
});

test("telemetry reports queue, in-flight, totals and per-kind latency", async () => {
  const {create} = createEnv();
  const pool = await create({size: 2, name: "noise"});
  const idle = pool.telemetry();
  assert.equal(idle.name, "noise");
  assert.equal(idle.queued, 0);
  await Promise.all(Array.from({length: 6}, (_, i) => pool.submit("echo", u32(i))));
  await pool.submit("spin", u32(3));
  const stats = pool.telemetry();
  assert.equal(stats.submitted, 7);
  assert.equal(stats.completed, 7);
  assert.equal(stats.queued, 0);
  assert.equal(stats.inFlight, 0);
  assert.equal(stats.kinds.echo.completed, 6);
  assert.ok(stats.kinds.echo.maxMs >= stats.kinds.echo.meanMs && stats.kinds.echo.meanMs > 0);
  assert.ok(stats.kinds.spin.meanExecMs >= 2, "worker-side execution time is reported");
  assert.equal(stats.workers.reduce((sum, worker) => sum + worker.completed, 0), 7);
});

test("terminate rejects every pending job and releases workers and the blob url", async () => {
  const {log, create} = createEnv();
  const pool = await create({size: 1, maxInFlightPerWorker: 1});
  const running = pool.submit("spin", u32(5));
  const queued = pool.submit("echo", u32(1));
  await waitFor(() => log.runs.spin === 1, "spin running");
  pool.terminate();
  await rejectsWith(running, "terminated");
  await rejectsWith(queued, "terminated");
  await rejectsWith(pool.submit("echo", u32(2)), "terminated");
  assert.ok(log.workers.every((worker) => worker.terminated));
  assert.equal(log.revoked.length, 1);
  assert.equal(pool.telemetry().terminated, true);
});

// --- runner ----------------------------------------------------------------------------------

const unhandled = [];
process.on("unhandledRejection", (reason) => unhandled.push(reason));

let failures = 0;
for (const {name, fn} of tests) {
  try {
    await Promise.race([fn(), sleep(10000).then(() => { throw new Error("test timed out"); })]);
    console.log(`ok   ${name}`);
  } catch (error) {
    failures++;
    console.log(`FAIL ${name}\n     ${error && error.stack || error}`);
  }
}
await sleep(20);
if (unhandled.length) {
  failures++;
  console.log(`FAIL unhandled rejections: ${unhandled.map((reason) => reason && reason.message).join("; ")}`);
}
console.log(failures ? `${failures} kernel pool check(s) failed` : `all ${tests.length} kernel pool checks passed`);
process.exit(failures ? 1 : 0);
