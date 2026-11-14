#!/usr/bin/env node
// Opt-in kernel job timeline (kernel-runtime.js, kernel-pool.js, kernel-worker.js and the
// server Worker export in server-worker-bootstrap.js).
//
//   node port/scripts/kernel-job-timeline-smoke.mjs
//
// Same fake-browser approach as kernel-runtime-smoke.mjs / kernel-pool-smoke.mjs: the page
// scripts run in node:vm contexts, every fake Worker runs the real kernel-worker.js, messages
// cross through structuredClone and a fake WebAssembly implements the kernel ABI in JavaScript.
// Checks: per job enqueue <= dispatch <= receive <= start <= finish, balanced worker busy
// intervals, the fixed ring limit with its drop counters and cursors, failure/cancel/crash
// rows, the MessagePort export, the server Worker snapshot, and that a run with the switch off
// posts the same messages and returns the same bytes in the same order as a run with it on
// (minus the timeline stamps) while creating no ring at all.

import assert from "node:assert/strict";
import {readFile, mkdtemp, rm, writeFile} from "node:fs/promises";
import {execFile} from "node:child_process";
import {tmpdir} from "node:os";
import {promisify} from "node:util";
import {webcrypto} from "node:crypto";
import v8 from "node:v8";
import vm from "node:vm";

const kernelsDir = new URL("../web/kernels/", import.meta.url);
const policySource = await readFile(new URL("kernel-policy.js", kernelsDir), "utf8");
const runtimeSource = await readFile(new URL("kernel-runtime.js", kernelsDir), "utf8");
const poolSource = await readFile(new URL("kernel-pool.js", kernelsDir), "utf8");
const workerSource = await readFile(new URL("kernel-worker.js", kernelsDir), "utf8");
const bootstrapSource = await readFile(new URL("../web/singleplayer/server-worker-bootstrap.js", import.meta.url), "utf8");

// --- fake WebAssembly kernels -----------------------------------------------------------------
// echo: returns its payload; spin: busy-waits <first u32> ms, then echoes; trap: throws;
// fail: kernel error status 7.

const KINDS = ["echo", "spin", "trap", "fail"];
const encodeModule = (desc) => {
  const body = new TextEncoder().encode(JSON.stringify(Object.assign({fake: 1}, desc)));
  const bytes = new Uint8Array(8 + body.length);
  bytes.set([0, 0x61, 0x73, 0x6d, 1, 0, 0, 0]);
  bytes.set(body, 8);
  return bytes.buffer;
};
const decodeModule = (bytes) => JSON.parse(new TextDecoder().decode(new Uint8Array(bytes).subarray(8)));
const moduleExports = (desc) => [
  {name: "memory", kind: "memory"}, {name: "alloc", kind: "function"}, {name: "reset", kind: "function"},
  ...desc.kinds.map((kind) => ({name: `run_${kind}`, kind: "function"})),
];

function makeInstance(desc) {
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
  for (const kind of desc.kinds) {
    exports[`run_${kind}`] = (ptr, length) => {
      const bytes = new Uint8Array(buffer, ptr, length).slice();
      const tag = bytes.length >= 4 ? new DataView(bytes.buffer).getUint32(0, true) : 0;
      if (kind === "trap") throw new Error("unreachable executed");
      if (kind === "fail") return output(7, new TextEncoder().encode("bad input"));
      if (kind === "spin") {
        const until = performance.now() + tag;
        while (performance.now() < until) { /* busy, like a long kernel */ }
      }
      return output(0, bytes);
    };
  }
  return {exports};
}

const workerWasm = {
  Module: {exports: moduleExports, imports: () => []},
  compile: async (bytes) => decodeModule(bytes),
  async instantiate(source) {
    return source && source.fake === 1 ? makeInstance(source) : {instance: makeInstance(decodeModule(source))};
  },
};

// A fake Worker that runs the real kernel-worker.js; every message in both directions is
// cloned and recorded in log.toWorker / log.toMain.
function fakeWorkerClass(log, sourceFor) {
  return class FakeWorker {
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
      vm.runInContext(sourceFor(url), this.ctx, {filename: "kernel-worker.js"});
    }

    postMessage(message, transfer = []) {
      if (this.terminated) return;
      const data = structuredClone(message, {transfer});
      log.toWorker.push({worker: this.name, data});
      setTimeout(() => { if (!this.terminated) this.ctx.onmessage({data}); }, 1);
    }

    fromWorker(message, transfer = []) {
      if (this.terminated) return;
      const data = structuredClone(message, {transfer});
      log.toMain.push({worker: this.name, data});
      setTimeout(() => { if (!this.terminated && this.onmessage) this.onmessage({data}); }, 1);
    }

    terminate() {
      this.terminated = true;
    }
  };
}

function runtimeEnv({simd = true} = {}) {
  const log = {toWorker: [], toMain: [], workers: []};
  const pageWasm = {
    Module: {exports: moduleExports, imports: () => []},
    compile: async (bytes) => decodeModule(bytes),
    validate: () => simd,
    Memory: class {},
  };
  const context = vm.createContext({
    console, performance, setTimeout, clearTimeout, setInterval, clearInterval, structuredClone,
    navigator: {hardwareConcurrency: 8, deviceMemory: 8},
    Worker: fakeWorkerClass(log, () => workerSource), WebAssembly: pageWasm, crypto: webcrypto,
    MessageChannel, URLSearchParams,
    URL: {createObjectURL: () => "blob:kernel-job-timeline/1", revokeObjectURL() {}},
    Blob: class { constructor(parts) { this.parts = parts; } },
  });
  vm.runInContext(policySource, context, {filename: "kernel-policy.js"});
  context.GaiusKernelMirror = {enabled: () => true, allowed: () => true};
  vm.runInContext(runtimeSource, context, {filename: "kernel-runtime.js"});
  return {context, log, Runtime: context.GaiusKernelRuntime};
}

const RUNTIME_KERNELS = {
  alpha: {kinds: KINDS, variants: {baseline: {bytes: encodeModule({name: "alpha", kinds: KINDS})}}},
  beta: {kinds: KINDS, variants: {baseline: {bytes: encodeModule({name: "beta", kinds: KINDS})}}},
};

function createRuntime(env, extra = {}) {
  return env.Runtime.create(Object.assign({
    kernels: RUNTIME_KERNELS, workerUrl: "kernel-worker.js", search: "",
    switches: {enabled: true, off: new Set(), simd: "auto", size: 1, budgetBytes: 0},
    sizingIntervalMs: 60_000, instanceEstimateBytes: 1 << 20, mobile: false,
  }, extra));
}

function poolEnv() {
  const log = {toWorker: [], toMain: [], workers: []};
  const blobs = new Map();
  let blobSeq = 0;
  const context = vm.createContext({
    console, performance, setTimeout, clearTimeout, URLSearchParams,
    navigator: {hardwareConcurrency: 8},
    Worker: fakeWorkerClass(log, (url) => blobs.get(url)),
    Blob: class { constructor(parts) { this.text = parts.join(""); } },
    URL: {
      createObjectURL(blob) {
        const url = `blob:kernel-job-timeline-pool/${++blobSeq}`;
        blobs.set(url, blob.text);
        return url;
      },
      revokeObjectURL(url) { blobs.delete(url); },
    },
    WebAssembly: {compile: async (bytes) => decodeModule(bytes), Module: {exports: moduleExports, imports: () => []}},
  });
  vm.runInContext(poolSource, context, {filename: "kernel-pool.js"});
  context.GaiusKernelMirror = {enabled: () => true, allowed: () => true};
  return {context, log, Pool: context.GaiusKernelPool};
}

async function createPool(env, options = {}) {
  return env.Pool.create({moduleBytes: encodeModule({name: "pool", kinds: KINDS}), workerSource, size: 3,
    reserve: 0, ...options});
}

// --- helpers ----------------------------------------------------------------------------------

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const tagged = (tag, size = 16) => {
  const buffer = new ArrayBuffer(size);
  new DataView(buffer).setUint32(0, tag, true);
  return buffer;
};
const tagOf = (buffer) => new DataView(buffer).getUint32(0, true);
const EPS = 0.002;   // exported stamps are rounded to microseconds

// Turns an export into objects keyed by column name with the string columns resolved.
function table(data) {
  assert.equal(data.enabled, true);
  assert.equal(data.schema, "gaius-kernel-timeline/1");
  const rows = data.rows.map((row) => {
    const out = Object.fromEntries(data.cols.map((col, i) => [col, row[i]]));
    out.k = data.strings[out.k];
    out.kd = data.strings[out.kd];
    out.st = data.strings[out.st];
    return out;
  });
  const busy = data.busy.rows.map(([seq, w, b, e]) => ({seq, w, b, e}));
  return {rows, busy};
}

function assertOrdered(rows) {
  for (const row of rows) {
    if (row.st !== "ok") continue;
    for (const [a, b] of [["enq", "dsp"], ["dsp", "rcv"], ["rcv", "t0"], ["t0", "t1"]]) {
      assert.notEqual(row[a], null, `job ${row.id}: ${a} missing`);
      assert.notEqual(row[b], null, `job ${row.id}: ${b} missing`);
      assert.ok(row[a] <= row[b] + EPS, `job ${row.id}: ${a} (${row[a]}) after ${b} (${row[b]})`);
    }
    // execMs measured by the worker is the same interval as t1 - t0.
    assert.ok(Math.abs((row.t1 - row.t0) - row.exec) <= 0.01, `job ${row.id}: exec ${row.exec} vs ${row.t1 - row.t0}`);
  }
}

// Per worker: busy intervals are well-formed and disjoint, every job's execution lies inside one
// of them, and executions on one worker never overlap.
function assertBalanced(rows, busy) {
  const byWorker = new Map();
  for (const interval of busy) {
    assert.ok(interval.e >= interval.b, `busy interval of worker ${interval.w} ends before it starts`);
    if (!byWorker.has(interval.w)) byWorker.set(interval.w, []);
    byWorker.get(interval.w).push(interval);
  }
  for (const [w, list] of byWorker) {
    list.sort((x, y) => x.b - y.b);
    for (let i = 1; i < list.length; i++) {
      assert.ok(list[i].b >= list[i - 1].e - EPS, `worker ${w}: busy intervals overlap`);
    }
  }
  const execByWorker = new Map();
  for (const row of rows) {
    if (row.st !== "ok") continue;
    const list = byWorker.get(row.w) || [];
    assert.ok(list.some((i) => row.t0 >= i.b - EPS && row.t1 <= i.e + EPS),
      `job ${row.id} (worker ${row.w}) is not covered by a busy interval`);
    if (!execByWorker.has(row.w)) execByWorker.set(row.w, []);
    execByWorker.get(row.w).push(row);
  }
  for (const [w, list] of execByWorker) {
    list.sort((x, y) => x.t0 - y.t0);
    for (let i = 1; i < list.length; i++) {
      assert.ok(list[i].t0 >= list[i - 1].t1 - EPS, `worker ${w}: executions overlap`);
    }
  }
}

// Exports come out of a vm context; plain copies compare with strict deepEqual.
const exp = (source, options) => JSON.parse(JSON.stringify(source.timelineExport(options)));
const sum = (list, pick) => list.reduce((total, item) => total + pick(item), 0);

const checks = [];
const check = (name, fn) => checks.push([name, fn]);

// --- switches ---------------------------------------------------------------------------------

check("the switch is off by default and parses URL, stored setting, global flag and option", async () => {
  const env = runtimeEnv();
  const Runtime = env.Runtime;
  assert.equal(Runtime.readSwitches("", null).timeline, 0);
  assert.equal(Runtime.readSwitches("?gaiusKernelTimeline=0", null).timeline, 0);
  assert.equal(Runtime.readSwitches("?gaiusKernelTimeline=off", null).timeline, 0);
  assert.equal(Runtime.readSwitches("?gaiusKernelTimeline=1", null).timeline, 4096);
  assert.equal(Runtime.readSwitches("?gaiusKernelTimeline=on", null).timeline, 4096);
  assert.equal(Runtime.readSwitches("?gaiusKernelTimeline=256", null).timeline, 256);
  assert.equal(Runtime.readSwitches("?gaiusKernelTimeline=2", null).timeline, 8, "tiny capacities are raised");
  assert.equal(Runtime.readSwitches("?gaiusKernelTimeline=99999999", null).timeline, 65536, "capacity is capped");
  assert.equal(Runtime.readSwitches("", {getItem: () => JSON.stringify({timeline: 300})}).timeline, 300);
  assert.equal(Runtime.readSwitches("?gaiusKernelTimeline=0", {getItem: () => JSON.stringify({timeline: 300})}).timeline, 0,
    "the URL wins over the stored setting");
  env.context.__gaiusKernelTimeline = true;
  assert.equal(Runtime.readSwitches("", null).timeline, 4096);
  env.context.__gaiusKernelTimeline = "512";
  assert.equal(Runtime.readSwitches("", null).timeline, 512);

  const plain = runtimeEnv();
  const off = createRuntime(plain);
  assert.equal(off.timeline, null);
  assert.equal(off.timelineExport().enabled, false);
  assert.equal(off.telemetry().timeline, undefined, "telemetry() keeps its shape while the switch is off");
  assert.equal(plain.context.__gaiusKernelTimelineSources, undefined, "no source registry while the switch is off");
  off.terminate();
  const on = createRuntime(plain, {timeline: true});
  assert.equal(on.timeline.capacity, 4096);
  assert.equal(plain.context.__gaiusKernelTimelineSources.size, 1);
  on.terminate();
  assert.equal(plain.context.__gaiusKernelTimelineSources.size, 0, "terminate unregisters the source");
  const viaSwitches = createRuntime(plain, {switches: Runtime.readSwitches("?gaiusKernelTimeline=64", null)});
  assert.equal(viaSwitches.timeline.capacity, 64);
  viaSwitches.terminate();
});

// --- runtime ----------------------------------------------------------------------------------

async function runMixedWorkload(runtime, count) {
  const jobs = [];
  for (let i = 0; i < count; i++) {
    const kernel = i % 2 ? "alpha" : "beta";
    const kind = i % 5 === 0 ? "spin" : "echo";
    jobs.push(runtime.submit(kind, tagged(kind === "spin" ? 6 : 1000 + i), {kernel, cx: i, cz: -i}));
  }
  await Promise.all(jobs);
}

check("runtime: every job row is ordered enqueue <= dispatch <= receive <= start <= finish", async () => {
  const env = runtimeEnv();
  const runtime = createRuntime(env, {timeline: 4096, switches: {enabled: true, off: new Set(), simd: "auto", size: 3, budgetBytes: 0}});
  await runMixedWorkload(runtime, 40);
  const {rows, busy} = table(exp(runtime));
  assert.equal(rows.length, 40);
  assert.ok(rows.every((row) => row.st === "ok" && row.at === 1));
  assert.deepEqual([...new Set(rows.map((row) => row.k))].sort(), ["alpha", "beta"]);
  assert.deepEqual([...new Set(rows.map((row) => row.kd))].sort(), ["echo", "spin"]);
  assert.ok(new Set(rows.map((row) => row.w)).size >= 2, "the work spread over several workers");
  assertOrdered(rows);
  assert.equal(new Set(rows.map((row) => row.id)).size, 40, "one row per job id");
  assert.equal(runtime.telemetry().timeline.total, 40);
  runtime.terminate();
});

check("runtime: worker busy intervals balance with the host's busyMs", async () => {
  const env = runtimeEnv();
  const runtime = createRuntime(env, {timeline: 4096, switches: {enabled: true, off: new Set(), simd: "auto", size: 3, budgetBytes: 0}});
  await runMixedWorkload(runtime, 40);
  await sleep(5);
  const data = exp(runtime);
  const {rows, busy} = table(data);
  assert.ok(busy.length > 0);
  assertBalanced(rows, busy);
  for (const slot of runtime.slots) {
    const mine = busy.filter((interval) => interval.w === slot.id);
    assert.ok(Math.abs(sum(mine, (i) => i.e - i.b) - slot.busyMs) < 0.05,
      `worker ${slot.id}: intervals ${sum(mine, (i) => i.e - i.b)} vs busyMs ${slot.busyMs}`);
    assert.equal(slot.busySince !== slot.busySince, true, "no interval is left open once idle");
  }
  assert.ok(data.workers.every((worker) => worker.busySince === null && worker.inFlight === 0));
  // While a job runs the open interval is reported on the worker, not as a closed row.
  const long = runtime.submit("spin", tagged(40), {kernel: "alpha"});
  await sleep(15);
  assert.ok(exp(runtime).workers.some((worker) => worker.busySince !== null));
  await long;
  runtime.terminate();
});

check("runtime: the ring keeps its capacity, counts drops and serves cursors", async () => {
  const env = runtimeEnv();
  const runtime = createRuntime(env, {timeline: 8});
  const first = await Promise.all(Array.from({length: 5}, (_, i) => runtime.submit("echo", tagged(i), {kernel: "alpha"})));
  assert.equal(first.length, 5);
  let data = exp(runtime);
  assert.deepEqual([data.capacity, data.total, data.kept, data.dropped, data.next], [8, 5, 5, 0, 5]);
  const cursor = data.next;
  const busyCursor = data.busy.next;
  await Promise.all(Array.from({length: 25}, (_, i) => runtime.submit("echo", tagged(100 + i), {kernel: "beta"})));
  data = exp(runtime);
  assert.deepEqual([data.capacity, data.total, data.kept, data.dropped, data.next], [8, 30, 8, 22, 30]);
  assert.equal(data.rows.length, 8, "the ring never holds more than its capacity");
  assert.deepEqual(data.rows.map((row) => row[0]), [22, 23, 24, 25, 26, 27, 28, 29], "newest rows, oldest first");
  assert.equal(data.busy.rows.length <= 8, true);
  assert.equal(data.busy.dropped, Math.max(0, data.busy.total - 8));
  assert.equal(runtime.telemetry().timeline.dropped, 22);
  // Polling from an old cursor reports what the ring lost in between.
  const polled = exp(runtime, {since: cursor, sinceBusy: busyCursor});
  assert.equal(polled.lost, 22 - cursor + 0, "rows overwritten between two polls are counted");
  assert.equal(polled.rows[0][0], 22);
  const tail = exp(runtime, {since: 27});
  assert.deepEqual(tail.rows.map((row) => row[0]), [27, 28, 29]);
  assert.equal(tail.lost, 0);
  assert.equal(exp(runtime, {max: 3}).rows.length, 3);
  assert.equal(exp(runtime, {since: 30}).rows.length, 0);
  runtime.terminate();
});

check("runtime: failures, cancels and crashes get their own rows", async () => {
  const env = runtimeEnv();
  const runtime = createRuntime(env, {timeline: 64});
  await runtime.submit("echo", tagged(1), {kernel: "alpha"});
  const trap = await runtime.submit("trap", tagged(2), {kernel: "alpha"}).then(() => null, (error) => error);
  assert.equal(trap.code, "kernel-trap");
  const failed = await runtime.submit("fail", tagged(3), {kernel: "alpha"}).then(() => null, (error) => error);
  assert.equal(failed.code, "kernel-error");
  // Queue one long job in front, then cancel a keyed job that never left the queue.
  // A worker slot takes two jobs at a time, so the fourth job stays in the host's queue.
  const long = [1, 2, 3].map((n) => runtime.submit("spin", tagged(15), {kernel: "alpha", key: "long" + n, version: 1}));
  const queued = runtime.submit("echo", tagged(4), {kernel: "alpha", key: "k", version: 1}).then(() => null, (error) => error);
  await sleep(2);
  assert.equal(runtime.cancel("k", "alpha"), true);
  assert.equal((await queued).code, "cancelled");
  await Promise.all(long);
  // A crash while a job runs: the attempt is recorded as crashed, the retry as ok (attempt 2).
  const survivor = runtime.submit("spin", tagged(25), {kernel: "alpha"});
  await sleep(10);
  runtime.crash(runtime.slots[0], {code: "worker-crashed", message: "test crash"});
  assert.equal(tagOf(await survivor), 25);
  await sleep(5);
  const {rows, busy} = table(exp(runtime));
  const byStatus = (status) => rows.filter((row) => row.st === status);
  assert.equal(byStatus("kernel-trap").length, 1);
  assert.equal(byStatus("kernel-error").length, 1);
  assert.ok(byStatus("kernel-trap")[0].t0 !== null && byStatus("kernel-trap")[0].t1 !== null, "a trap still carries its stamps");
  const cancelled = byStatus("cancelled");
  assert.equal(cancelled.length, 1);
  assert.equal(cancelled[0].dsp, null, "a job cancelled in the queue was never dispatched");
  assert.equal(cancelled[0].w, 0);
  assert.equal(byStatus("worker-crashed").length, 1);
  assert.equal(byStatus("worker-crashed")[0].at, 1);
  const retried = rows.filter((row) => row.id === byStatus("worker-crashed")[0].id);
  assert.deepEqual(retried.map((row) => [row.st, row.at]), [["worker-crashed", 1], ["ok", 2]]);
  assert.ok(busy.every((interval) => interval.e >= interval.b));
  runtime.terminate();
});

check("runtime: ports record the client's request id and answer timeline requests", async () => {
  const env = runtimeEnv();
  const runtime = createRuntime(env, {timeline: 64});
  const channel = new MessageChannel();
  runtime.attachPort(channel.port1);
  const client = env.Runtime.connect(channel.port2);
  await client.ready;
  await client.submit("echo", tagged(11), {kernel: "alpha", cx: 1, cz: 2});
  await client.submit("echo", tagged(12), {kernel: "beta", cx: 3, cz: 4});
  await runtime.submit("echo", tagged(13), {kernel: "alpha"});
  const data = await client.timeline();
  const {rows} = table(data);
  assert.equal(rows.length, 3);
  assert.deepEqual(rows.map((row) => [row.port, row.rid]), [[1, 1], [1, 2], [0, -1]]);
  assertOrdered(rows);
  assert.equal((await client.timeline({max: 1})).rows.length, 1);
  // A runtime without a timeline answers the same request with enabled: false.
  const plain = createRuntime(env);
  const channel2 = new MessageChannel();
  plain.attachPort(channel2.port1);
  const client2 = env.Runtime.connect(channel2.port2);
  await client2.ready;
  assert.equal((await client2.timeline()).enabled, false);
  runtime.terminate();
  plain.terminate();
  channel.port2.close();
  channel2.port2.close();
});

check("runtime: with the switch off nothing is stamped and a run matches the same run with it on", async () => {
  const run = async (timeline) => {
    const env = runtimeEnv();
    const runtime = createRuntime(env, timeline ? {timeline: 4096} : {});
    const results = [];
    const jobs = [];
    // One worker and one synchronous burst: dispatch order is fully determined by the queue.
    const specs = [
      ["echo", "alpha", {visible: true, cx: 0, cz: 0}], ["echo", "beta", {cx: 5, cz: 5}],
      ["fail", "alpha", {cx: 1, cz: 1}], ["echo", "alpha", {near: true, cx: 2, cz: 0}],
      ["trap", "beta", {cx: 9, cz: 9, retry: false}], ["echo", "beta", {background: true}],
      ["echo", "alpha", {key: "dup", version: 1, cx: 3, cz: 3}], ["echo", "alpha", {key: "dup", version: 2, cx: 3, cz: 3}],
    ];
    specs.forEach(([kind, kernel, opts], i) => {
      jobs.push(runtime.submit(kind, tagged(500 + i, 16 + i), Object.assign({kernel}, opts)).then(
        (buffer) => results.push(["ok", i, Buffer.from(buffer).toString("hex")]),
        (error) => results.push([error.code, i, ""])));
    });
    await Promise.all(jobs);
    await sleep(5);
    const normal = (entry) => {
      const data = entry.data;
      const copy = JSON.parse(JSON.stringify(data, (key, value) => (value instanceof ArrayBuffer
        ? Buffer.from(value).toString("hex") : value)));
      // rcv is written into the received copy by the worker (the log shares that object).
      delete copy.tl;
      delete copy.rcv;
      delete copy.execMs;
      return {worker: entry.worker, data: copy};
    };
    const out = {
      toWorker: env.log.toWorker.map(normal), toMain: env.log.toMain.map(normal), results,
      hadTl: [...env.log.toWorker, ...env.log.toMain].some((entry) => "tl" in entry.data),
      keys: [...env.log.toWorker, ...env.log.toMain].map((entry) => Object.keys(entry.data).sort().join(",")),
      ring: runtime.timeline !== null,
    };
    runtime.terminate();
    return out;
  };
  const off = await run(false);
  const on = await run(true);
  assert.equal(off.hadTl, false, "no tl field in any message while the switch is off");
  assert.equal(off.ring, false);
  assert.equal(on.hadTl, true);
  assert.equal(on.ring, true);
  // The message sequence, payload bytes, result bytes and result order are identical.
  assert.deepEqual(on.toWorker, off.toWorker);
  assert.deepEqual(on.toMain, off.toMain);
  assert.deepEqual(on.results, off.results);
  assert.ok(off.results.some(([code]) => code === "ok") && off.results.some(([code]) => code === "kernel-trap"));
  // The only key the timeline adds to a message is tl.
  const strip = (keys) => keys.map((k) => k.split(",").filter((name) => name !== "tl" && name !== "rcv").join(","));
  assert.deepEqual(strip(on.keys), strip(off.keys));
});

check("the ring writes rows without allocating", async () => {
  v8.setFlagsFromString("--expose-gc");
  const gc = vm.runInNewContext("gc");
  const env = runtimeEnv();
  const runtime = createRuntime(env, {timeline: 4096});
  await runtime.submit("echo", tagged(1), {kernel: "alpha"});   // interns every name once
  const ring = runtime.timeline;
  const records = 400_000;
  gc();
  const before = process.memoryUsage().heapUsed;
  for (let i = 0; i < records; i++) {
    ring.record(i, 1, "alpha", "echo", "ok", 1, 2, 1.5, 2.5, 3.5, 4.5, 5.5, 1, 0, -1);
    ring.busy(1, 1.5, 2.5);
  }
  gc();
  const grown = process.memoryUsage().heapUsed - before;
  assert.ok(grown < 256 * 1024, `ring writes grew the heap by ${grown} bytes over ${records} rows`);
  assert.equal(ring.seq, records + 1);
  runtime.terminate();
});

// --- pool -------------------------------------------------------------------------------------

async function runPoolWorkload(pool, count) {
  const jobs = [];
  for (let i = 0; i < count; i++) {
    const kind = i % 5 === 0 ? "spin" : "echo";
    jobs.push(pool.submit(kind, tagged(kind === "spin" ? 6 : 2000 + i), {priority: i % 3}));
  }
  await Promise.all(jobs);
}

check("pool: ordered rows, balanced busy intervals, ring limit and cursors", async () => {
  const env = poolEnv();
  const pool = await createPool(env, {timeline: 4096});
  await runPoolWorkload(pool, 36);
  await sleep(5);
  const data = exp(pool);
  const {rows, busy} = table(data);
  assert.equal(data.host, "pool");
  assert.equal(rows.length, 36);
  assert.ok(rows.every((row) => row.st === "ok" && row.k === pool.name));
  assertOrdered(rows);
  assertBalanced(rows, busy);
  assert.ok(new Set(rows.map((row) => row.w)).size >= 2);
  assert.ok(data.workers.every((worker) => worker.busySince === null && worker.inFlight === 0));
  assert.equal(pool.telemetry().timeline.total, 36);
  pool.terminate();

  const small = await createPool(env, {timeline: 8, size: 1});
  await runPoolWorkload(small, 20);
  const limited = exp(small);
  assert.deepEqual([limited.capacity, limited.total, limited.kept, limited.dropped, limited.next], [8, 20, 8, 12, 20]);
  assert.deepEqual(limited.rows.map((row) => row[0]), [12, 13, 14, 15, 16, 17, 18, 19]);
  assert.equal(exp(small, {since: 18}).rows.length, 2);
  assert.equal(exp(small, {since: 0}).lost, 12);
  small.terminate();
});

check("pool: failures and a mid-job crash are recorded", async () => {
  const env = poolEnv();
  const pool = await createPool(env, {timeline: 64, size: 1});
  const trap = await pool.submit("trap", tagged(1)).then(() => null, (error) => error);
  assert.equal(trap.code, "kernel-trap");
  const survivor = pool.submit("spin", tagged(25));
  await sleep(10);
  pool.crash(pool.slots[0], {code: "worker-crashed", message: "test crash"});
  assert.equal(tagOf(await survivor), 25);
  await sleep(5);
  const {rows, busy} = table(exp(pool));
  assert.equal(rows.filter((row) => row.st === "kernel-trap").length, 1);
  const crashed = rows.find((row) => row.st === "worker-crashed");
  assert.ok(crashed);
  assert.deepEqual(rows.filter((row) => row.id === crashed.id).map((row) => [row.st, row.at]),
    [["worker-crashed", 1], ["ok", 2]]);
  assert.ok(busy.every((interval) => interval.e >= interval.b));
  pool.terminate();
});

check("pool: with the switch off nothing is stamped and a run matches the same run with it on", async () => {
  const run = async (timeline) => {
    const env = poolEnv();
    const pool = await createPool(env, Object.assign({size: 1}, timeline ? {timeline: true} : {}));
    const results = [];
    const specs = [["echo", 0], ["spin", 1], ["fail", 0], ["echo", 2], ["trap", 1], ["echo", 0]];
    await Promise.all(specs.map(([kind, priority], i) => pool.submit(kind, tagged(i === 1 ? 4 : 700 + i, 16 + i), {priority}).then(
      (buffer) => results.push(["ok", i, Buffer.from(buffer).toString("hex")]),
      (error) => results.push([error.code, i, ""]))));
    await sleep(5);
    const normal = (entry) => {
      const copy = JSON.parse(JSON.stringify(entry.data, (key, value) => (value instanceof ArrayBuffer
        ? Buffer.from(value).toString("hex") : value)));
      delete copy.tl;
      delete copy.rcv;
      delete copy.execMs;
      return {worker: entry.worker, data: copy};
    };
    const out = {
      toWorker: env.log.toWorker.map(normal), toMain: env.log.toMain.map(normal), results,
      hadTl: [...env.log.toWorker, ...env.log.toMain].some((entry) => "tl" in entry.data),
      ring: pool.timeline !== null, telemetryKeys: Object.keys(pool.telemetry()).sort().join(","),
      sources: env.context.__gaiusKernelTimelineSources,
    };
    pool.terminate();
    return out;
  };
  const off = await run(false);
  const on = await run(true);
  assert.equal(off.hadTl, false);
  assert.equal(off.ring, false);
  assert.equal(off.sources, undefined);
  assert.equal(on.hadTl, true);
  assert.deepEqual(on.toWorker, off.toWorker);
  assert.deepEqual(on.toMain, off.toMain);
  assert.deepEqual(on.results, off.results);
  assert.equal(on.telemetryKeys.replace(",timeline", ""), off.telemetryKeys);
});

check("the ring block is the same text in kernel-runtime.js and kernel-pool.js", async () => {
  const block = (source) => {
    const begin = source.indexOf("// --- job timeline ring begin");
    const end = source.indexOf("// --- job timeline ring end ---");
    assert.ok(begin >= 0 && end > begin);
    return source.slice(begin, end);
  };
  assert.equal(block(poolSource), block(runtimeSource));
});

// --- server Worker export ---------------------------------------------------------------------

check("server Worker: client ring, page ring over the port and the bounded snapshot", async () => {
  const env = runtimeEnv();
  const runtime = createRuntime(env, {timeline: 256});
  const channel = new MessageChannel();
  runtime.attachPort(channel.port1);
  const posted = [];
  const root = vm.createContext({
    console, performance, setTimeout, clearTimeout, ArrayBuffer, SharedArrayBuffer, Uint8Array, Float64Array,
    Array, Number, Math, Object, Set, Map, Promise, String, JSON,
    postMessage: (message) => posted.push(JSON.parse(JSON.stringify(message))),
  });
  root.root = root;
  root.GaiusKernelMirror = {allowed: () => true, enabled: () => true};
  vm.runInContext(bootstrapSource.slice(bootstrapSource.indexOf("// Kernel job timeline export (diagnostics")), root,
    {filename: "server-worker-bootstrap.js"});

  // The page runtime has its timeline on, so the client follows through the port status.
  const client = root.createKernelClient(channel.port2);
  root.__gaiusKernelClient = client;
  assert.equal(root.__gaiusKernelTimeline, undefined, "nothing is switched on before the page says so");
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(root.__gaiusKernelTimeline, 256);
  assert.equal(client.timelineStats(), null, "the ring is created by the first submit, not by the switch");
  await client.submit("echo", tagged(1), {kernel: "alpha"});
  for (let i = 0; i < 5; i++) await client.submit("echo", tagged(10 + i), {kernel: i % 2 ? "alpha" : "beta"});
  const failure = await client.submit("trap", tagged(99), {kernel: "alpha"}).then(() => null, (error) => error);
  assert.equal(failure.code, "kernel-trap");
  assert.deepEqual(JSON.parse(JSON.stringify(root.kernelTimelineSummary().client)), {capacity: 256, total: 7, kept: 7, dropped: 0});
  posted.length = 0;
  await root.postKernelTimelineSnapshot({seq: 2, max: 1_000_000});
  const snapshot = posted[0];
  assert.equal(snapshot.seq, 2);
  assert.equal(snapshot.enabled, true);
  assert.equal(snapshot.client.host, "server-client");
  assert.deepEqual(snapshot.client.cols, ["seq", "rid", "kernel", "kind", "submit", "done", "status"]);
  assert.equal(snapshot.client.rows.length, 7);
  assert.deepEqual(snapshot.client.rows.map((row) => row[6]), ["ok", "ok", "ok", "ok", "ok", "ok", "kernel-trap"]);
  for (const row of snapshot.client.rows) assert.ok(row[5] >= row[4], "answer after submit");
  // The page's ring lines up with the client's by request id, in the same wall-clock base.
  const page = table(snapshot.page);
  const sentRids = new Set(snapshot.client.rows.map((row) => row[1]));
  assert.equal(page.rows.length, 7);
  const pageRows = page.rows.filter((row) => row.port === 1 && sentRids.has(row.rid));
  assert.equal(pageRows.length, sentRids.size, "every request in the client ring has its row in the page ring");
  for (const row of pageRows) {
    const mine = snapshot.client.rows.find((entry) => entry[1] === row.rid);
    const clientSubmit = snapshot.client.base + mine[4];
    const pageEnqueue = snapshot.page.base + row.enq;
    assert.ok(clientSubmit <= pageEnqueue + EPS, "the client submits before the runtime enqueues");
  }
  assert.ok(Buffer.byteLength(JSON.stringify(snapshot)) < 200_000, "snapshot stays small");
  // Row and part limits.
  const bounded = root.kernelTimelineHostedSources();
  assert.equal(bounded.length, 0);
  posted.length = 0;
  await root.postKernelTimelineSnapshot({seq: 3, max: 2, page: false});
  assert.equal(posted[0].client.rows.length, 2);
  assert.equal(posted[0].page, null, "page: false skips the port round trip");
  // Cursors: one number for every part, or an object per part.
  posted.length = 0;
  await root.postKernelTimelineSnapshot({seq: 4, since: {client: 4, page: 5}});
  assert.deepEqual(posted[0].client.rows.map((row) => row[0]), [4, 5, 6]);
  assert.deepEqual(posted[0].page.rows.map((row) => row[0]), [5, 6]);
  // Rings of runtimes the worker hosts itself are listed too, at most four.
  root.__gaiusKernelTimelineSources = new Set();
  const hosts = [];
  for (let i = 0; i < 6; i++) {
    const hosted = createRuntime(env, {timeline: 16, name: "host" + i});
    hosts.push(hosted);
    root.__gaiusKernelTimelineSources.add(hosted);
  }
  await hosts[0].submit("echo", tagged(1), {kernel: "alpha"});
  posted.length = 0;
  await root.postKernelTimelineSnapshot({seq: 5, page: false});
  assert.equal(posted[0].hosted.length, 4);
  assert.equal(posted[0].hosted[0].name, "host0");
  assert.equal(posted[0].hosted[0].rows.length, 1);
  assert.equal(root.kernelTimelineSummary().hosted.length, 4);
  // A page that does not answer is a timeout, not a hang.
  const silent = new MessageChannel();
  root.__gaiusKernelClient = root.createKernelClient(silent.port2);
  posted.length = 0;
  await root.postKernelTimelineSnapshot({seq: 6, timeoutMs: 60});
  assert.equal(posted[0].page, null);
  silent.port2.close();
  silent.port1.close();
  hosts.forEach((hosted) => hosted.terminate());
  runtime.terminate();
  channel.port2.close();
});

check("server Worker: with both switches off no ring, flag or summary exists", async () => {
  const env = runtimeEnv();
  const runtime = createRuntime(env);
  const channel = new MessageChannel();
  runtime.attachPort(channel.port1);
  const posted = [];
  const root = vm.createContext({
    console, performance, setTimeout, clearTimeout, ArrayBuffer, SharedArrayBuffer, Uint8Array, Float64Array,
    Array, Number, Math, Object, Set, Map, Promise, String, JSON,
    postMessage: (message) => posted.push(JSON.parse(JSON.stringify(message))),
  });
  root.root = root;
  root.GaiusKernelMirror = {allowed: () => true, enabled: () => true};
  vm.runInContext(bootstrapSource.slice(bootstrapSource.indexOf("// Kernel job timeline export (diagnostics")), root,
    {filename: "server-worker-bootstrap.js"});
  const client = root.createKernelClient(channel.port2);
  root.__gaiusKernelClient = client;
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(runtime.status().timeline, undefined, "the port status keeps its shape");
  await client.submit("echo", tagged(1), {kernel: "alpha"});
  assert.equal(root.__gaiusKernelTimeline, undefined);
  assert.equal(client.timelineStats(), null);
  assert.equal(root.kernelTimelineSummary(), null);
  await root.postKernelTimelineSnapshot({seq: 1});
  assert.equal(posted[0].enabled, false);
  assert.equal(posted[0].client.enabled, false);
  assert.equal(posted[0].page.enabled, false);
  runtime.terminate();
  channel.port2.close();
});

check("server Worker: the heartbeat and message wiring exist and stay opt-in", async () => {
  for (const needle of [
    'message.type === "kernel-timeline-snapshot"',
    "const kernelTimeline = kernelTimelineSummary();",
    "if (kernelTimeline !== null) pong.kernelTimeline = kernelTimeline;",
    'diagnosticUrl.searchParams.get("gaiusKernelTimeline")',
    "message.gaiusKernelTimeline",
  ]) assert.ok(bootstrapSource.includes(needle), `bootstrap lost: ${needle}`);
  // The URL parameter only sets the flag when present; nothing is created at load.
  assert.ok(!/__gaiusKernelTimeline\s*=\s*(true|false|0|"0")\s*;/.test(bootstrapSource), "the flag has no default value");
});

// --- analysis script --------------------------------------------------------------------------

async function analyze(document, extra = []) {
  const dir = await mkdtemp(`${tmpdir()}/kernel-job-timeline-`);
  try {
    const file = `${dir}/export.json`;
    await writeFile(file, JSON.stringify(document));
    const script = new URL("kernel-job-timeline.mjs", import.meta.url).pathname;
    const {stdout} = await promisify(execFile)(process.execPath, [script, file, "--json", ...extra]);
    return JSON.parse(stdout);
  } finally {
    await rm(dir, {recursive: true, force: true});
  }
}

check("kernel-job-timeline.mjs: percentiles, utilization and the all-idle share on a known timeline", async () => {
  const strings = ["?", "alpha", "echo", "ok"];
  const row = (seq, id, w, enq, dsp, rcv, t0, t1) => [seq, id, w, 1, 2, 3, 1, 2, enq, dsp, rcv, t0, t1, t1 - t0, 0, -1];
  const document = {
    schema: "gaius-kernel-timeline/1", enabled: true, host: "runtime", name: "synthetic", base: 1000,
    capacity: 64, total: 3, kept: 3, dropped: 0, lost: 0, next: 3,
    cols: ["seq", "id", "w", "k", "kd", "st", "at", "cls", "enq", "dsp", "rcv", "t0", "t1", "exec", "port", "rid"],
    strings,
    rows: [row(0, 1, 1, 0, 5, 6, 10, 30), row(1, 2, 2, 0, 5, 6, 8, 18), row(2, 3, 2, 5, 40, 41, 45, 55)],
    busy: {cols: ["seq", "w", "b", "e"], total: 3, dropped: 0, lost: 0, next: 3,
      rows: [[0, 1, 5, 31], [1, 2, 5, 19], [2, 2, 40, 56]]},
    workers: [{id: 1}, {id: 2}],
  };
  const [result] = await analyze(document);
  const jobs = result.jobs;
  assert.equal(jobs.window.lengthMs, 55);
  const kind = jobs.byKind.find((entry) => entry.key === "alpha/echo");
  assert.deepEqual([kind.ok, kind.attempts], [3, 3]);
  assert.equal(kind.queueMs.p50, 5);
  assert.ok(Math.abs(kind.queueMs.p95 - 32) < 1e-9);
  assert.equal(kind.execMs.p50, 10);
  assert.ok(Math.abs(kind.execMs.p95 - 19) < 1e-9);
  const [w1, w2] = jobs.workers;
  assert.deepEqual([w1.id, w1.execMs, w2.id, w2.execMs], [1, 20, 2, 20]);
  assert.ok(Math.abs(w1.execUtilization - 20 / 55) < 1e-9);
  assert.equal(w1.inflightMs, 26);
  assert.equal(w2.inflightMs, 14 + 15);
  // executing union = [8,30] and [45,55]; idle = [0,8] and [30,45]; jobs waited during [0,40].
  assert.equal(jobs.allIdle.ms, 23);
  assert.ok(Math.abs(jobs.allIdle.fraction - 23 / 55) < 1e-9);
  assert.equal(jobs.allIdle.longestMs, 15);
  assert.equal(jobs.allIdle.withQueuedJobsMs, 18);
  const cut = (await analyze(document, ["--from", "5"]))[0].jobs;
  assert.equal(cut.window.startMs, 5);
});

check("kernel-job-timeline.mjs reads a real runtime export and a server Worker snapshot", async () => {
  const env = runtimeEnv();
  const runtime = createRuntime(env, {timeline: 256, switches: {enabled: true, off: new Set(), simd: "auto", size: 2, budgetBytes: 0}});
  await runMixedWorkload(runtime, 24);
  await sleep(5);
  const results = await analyze(exp(runtime));
  assert.equal(results.length, 1);
  const jobs = results[0].jobs;
  assert.equal(jobs.attempts, 24);
  assert.equal(jobs.byKind.reduce((total, entry) => total + entry.ok, 0), 24);
  assert.ok(jobs.allIdle.fraction >= 0 && jobs.allIdle.fraction <= 1);
  for (const worker of jobs.workers) {
    assert.ok(worker.execUtilization >= 0 && worker.execUtilization <= 1 + 1e-9);
    assert.ok(worker.execMs <= worker.inflightMs + 0.01, "execution is inside the in-flight interval");
  }
  const snapshot = {type: "kernel-timeline", seq: 1, enabled: true, client: {schema: "gaius-kernel-timeline/1", enabled: false},
    page: exp(runtime), hosted: []};
  const fromSnapshot = await analyze(snapshot);
  assert.equal(fromSnapshot.length, 1);
  assert.equal(fromSnapshot[0].jobs.attempts, 24);
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
  console.log(`${failed} of ${checks.length} kernel job timeline checks failed`);
  process.exit(1);
}
console.log(`all ${checks.length} kernel job timeline checks passed`);
