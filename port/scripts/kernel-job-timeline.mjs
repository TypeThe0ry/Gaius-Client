#!/usr/bin/env node
// Summarizes a kernel job timeline export (kernel-runtime.js / kernel-pool.js timelineExport,
// or the "kernel-timeline" snapshot the server Worker posts).
//
//   node port/scripts/kernel-job-timeline.mjs export.json [--json] [--from <ms>] [--to <ms>]
//   cat export.json | node port/scripts/kernel-job-timeline.mjs -
//
// The input is one export, a server Worker snapshot ({client, page, hosted: [...]}), or an array
// of either; only parts that carry job rows (a runtime or pool ring) are analyzed, the server
// client ring is summarized as submit -> answer latency. --from/--to cut the window (ms after
// the part's own base, i.e. the same numbers the rows carry).
//
// Per part it prints
//   (a) jobs per kernel/kind with queue wait (dispatch - enqueue) and execution time
//       (finish - start) P50/P95, plus the worker-side lag (start - dispatch: message transit and
//       the worker's own queue);
//   (b) per worker: execution utilization (union of its job executions over the window) and
//       in-flight utilization (the host's busy intervals: at least one job posted to it);
//   (c) the share of the window in which no worker was executing, and the part of that idle
//       time during which jobs were waiting in the host queue (the signature of a dispatch or
//       server-side serialization gap rather than of a lack of work).
// The window runs from the first enqueue to the last finish of the part.

import {readFileSync} from "node:fs";

const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const option = (name) => {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? Number(argv[i + 1]) : null;
};
const input = argv.find((arg, i) => !arg.startsWith("--") && (i === 0 || !["--from", "--to"].includes(argv[i - 1])));
if (!input) {
  console.error("usage: kernel-job-timeline.mjs <export.json | -> [--json] [--from ms] [--to ms]");
  process.exit(2);
}

const text = input === "-" ? readFileSync(0, "utf8") : readFileSync(input, "utf8");
let document = JSON.parse(text);

// --- collect the parts to analyze ----------------------------------------------------------------

function collectParts(value, label, out) {
  if (Array.isArray(value)) {
    value.forEach((entry, i) => collectParts(entry, `${label}[${i}]`, out));
    return out;
  }
  if (!value || typeof value !== "object") return out;
  if (value.schema === "gaius-kernel-timeline/1") {
    if (value.enabled) out.push({label: `${label}${value.host ? ` (${value.host}${value.name ? " " + value.name : ""})` : ""}`, data: value});
    return out;
  }
  if (value.type === "kernel-timeline" || "client" in value || "page" in value || "hosted" in value) {
    if (value.client) collectParts(value.client, `${label}client`, out);
    if (value.page) collectParts(value.page, `${label}page`, out);
    (value.hosted || []).forEach((entry, i) => collectParts(entry, `${label}host${i}`, out));
    return out;
  }
  return out;
}

const parts = collectParts(document, "", []);
if (parts.length === 0) {
  console.error("no enabled kernel timeline export found in the input");
  process.exit(1);
}

// --- numeric helpers -----------------------------------------------------------------------------

function percentile(sorted, p) {
  if (sorted.length === 0) return null;
  if (sorted.length === 1) return sorted[0];
  const position = (sorted.length - 1) * p;
  const low = Math.floor(position);
  const high = Math.ceil(position);
  return sorted[low] + (sorted[high] - sorted[low]) * (position - low);
}

function summarize(values) {
  const sorted = values.filter((v) => Number.isFinite(v)).sort((a, b) => a - b);
  return {n: sorted.length, p50: percentile(sorted, 0.5), p95: percentile(sorted, 0.95),
    max: sorted.length ? sorted[sorted.length - 1] : null};
}

// Sorted, merged copy of [start, end] pairs clipped to [lo, hi].
function union(intervals, lo, hi) {
  const clipped = [];
  for (const [b, e] of intervals) {
    const start = Math.max(b, lo);
    const end = Math.min(e, hi);
    if (end > start) clipped.push([start, end]);
  }
  clipped.sort((x, y) => x[0] - y[0]);
  const merged = [];
  for (const [b, e] of clipped) {
    const last = merged[merged.length - 1];
    if (last && b <= last[1]) last[1] = Math.max(last[1], e);
    else merged.push([b, e]);
  }
  return merged;
}

const length = (merged) => merged.reduce((total, [b, e]) => total + (e - b), 0);

// merged (a) minus merged (b) -> merged intervals of a not covered by b
function subtract(a, b) {
  const out = [];
  let j = 0;
  for (const [start, end] of a) {
    let cursor = start;
    while (j < b.length && b[j][1] <= cursor) j++;
    let k = j;
    while (k < b.length && b[k][0] < end) {
      if (b[k][0] > cursor) out.push([cursor, b[k][0]]);
      cursor = Math.max(cursor, b[k][1]);
      k++;
    }
    if (cursor < end) out.push([cursor, end]);
  }
  return out;
}

function intersect(a, b) {
  const out = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    const start = Math.max(a[i][0], b[j][0]);
    const end = Math.min(a[i][1], b[j][1]);
    if (end > start) out.push([start, end]);
    if (a[i][1] < b[j][1]) i++;
    else j++;
  }
  return out;
}

// --- analysis of one part -----------------------------------------------------------------------

function analyzeRuntimePart(data) {
  const col = Object.fromEntries(data.cols.map((name, i) => [name, i]));
  const name = (index) => data.strings[index] ?? "?";
  const rows = data.rows.map((row) => ({
    seq: row[col.seq], id: row[col.id], w: row[col.w], kernel: name(row[col.k]), kind: name(row[col.kd]),
    status: name(row[col.st]), attempt: row[col.at], enq: row[col.enq], dsp: row[col.dsp], rcv: row[col.rcv],
    t0: row[col.t0], t1: row[col.t1], exec: row[col.exec],
  }));
  const busyRows = data.busy.rows.map(([seq, w, b, e]) => ({seq, w, b, e}));
  const from = option("--from");
  const to = option("--to");
  const finite = (v) => typeof v === "number" && Number.isFinite(v);
  const inCut = (t) => (from === null || t >= from) && (to === null || t <= to);
  const used = rows.filter((row) => (!finite(row.enq) || inCut(row.enq)));

  // window: first enqueue .. last finish (or busy end)
  let lo = Infinity;
  let hi = -Infinity;
  for (const row of used) {
    if (finite(row.enq)) lo = Math.min(lo, row.enq);
    for (const t of [row.t1, row.dsp]) if (finite(t)) hi = Math.max(hi, t);
  }
  if (from !== null) lo = Math.max(lo, from);
  if (to !== null) hi = Math.min(hi, to);
  const windowMs = hi > lo ? hi - lo : 0;

  // (a) per kernel/kind
  const groups = new Map();
  const statuses = {};
  for (const row of used) {
    statuses[row.status] = (statuses[row.status] || 0) + 1;
    const key = `${row.kernel}/${row.kind}`;
    if (!groups.has(key)) groups.set(key, {rows: 0, ok: 0, queue: [], exec: [], lag: []});
    const group = groups.get(key);
    group.rows++;
    if (row.status !== "ok") continue;
    group.ok++;
    if (finite(row.enq) && finite(row.dsp)) group.queue.push(Math.max(0, row.dsp - row.enq));
    if (finite(row.t0) && finite(row.t1)) group.exec.push(Math.max(0, row.t1 - row.t0));
    if (finite(row.dsp) && finite(row.t0)) group.lag.push(Math.max(0, row.t0 - row.dsp));
  }
  const byKind = [...groups.entries()].sort(([a], [b]) => (a < b ? -1 : 1)).map(([key, group]) => ({
    key, attempts: group.rows, ok: group.ok,
    queueMs: summarize(group.queue), execMs: summarize(group.exec), workerLagMs: summarize(group.lag),
  }));

  // (b) per worker
  const execByWorker = new Map();
  const busyByWorker = new Map();
  for (const row of used) {
    if (row.w > 0 && finite(row.t0) && finite(row.t1)) {
      if (!execByWorker.has(row.w)) execByWorker.set(row.w, []);
      execByWorker.get(row.w).push([row.t0, row.t1]);
    }
  }
  for (const interval of busyRows) {
    if (!busyByWorker.has(interval.w)) busyByWorker.set(interval.w, []);
    busyByWorker.get(interval.w).push([interval.b, interval.e]);
  }
  for (const worker of data.workers || []) {
    if (!execByWorker.has(worker.id)) execByWorker.set(worker.id, []);
  }
  const workerIds = [...new Set([...execByWorker.keys(), ...busyByWorker.keys()])].sort((a, b) => a - b);
  const workers = workerIds.map((id) => {
    const exec = union(execByWorker.get(id) || [], lo, hi);
    const inflight = union(busyByWorker.get(id) || [], lo, hi);
    return {
      id, jobs: (execByWorker.get(id) || []).length,
      execMs: length(exec), inflightMs: length(inflight),
      execUtilization: windowMs ? length(exec) / windowMs : null,
      inflightUtilization: windowMs ? length(inflight) / windowMs : null,
    };
  });

  // (c) all workers idle; and idle with jobs waiting in the queue
  const allExec = union([...execByWorker.values()].flat(), lo, hi);
  const idle = subtract([[lo, hi]], allExec).filter(([b, e]) => e > b);
  const waiting = union(used.filter((row) => finite(row.enq) && finite(row.dsp) && row.dsp > row.enq)
    .map((row) => [row.enq, row.dsp]), lo, hi);
  const idleWithQueue = intersect(idle, waiting);
  const allInflight = union([...busyByWorker.values()].flat(), lo, hi);
  const noneInflight = subtract([[lo, hi]], allInflight);
  // longest single stretch with every worker idle
  const longestIdle = idle.reduce((best, [b, e]) => Math.max(best, e - b), 0);

  return {
    window: {startMs: windowMs ? lo : null, endMs: windowMs ? hi : null, lengthMs: windowMs},
    ring: {total: data.total, kept: data.kept, dropped: data.dropped, capacity: data.capacity},
    attempts: used.length, statuses, byKind, workers,
    allIdle: {
      ms: length(idle), fraction: windowMs ? length(idle) / windowMs : null, longestMs: longestIdle,
      withQueuedJobsMs: length(idleWithQueue), withQueuedJobsFraction: windowMs ? length(idleWithQueue) / windowMs : null,
      noJobInFlightMs: length(noneInflight), noJobInFlightFraction: windowMs ? length(noneInflight) / windowMs : null,
    },
  };
}

function analyzeClientPart(data) {
  const finite = (v) => typeof v === "number" && Number.isFinite(v);
  const col = Object.fromEntries(data.cols.map((name, i) => [name, i]));
  const groups = new Map();
  for (const row of data.rows) {
    const key = `${row[col.kernel]}/${row[col.kind]}`;
    if (!groups.has(key)) groups.set(key, {n: 0, answered: 0, latency: []});
    const group = groups.get(key);
    group.n++;
    if (finite(row[col.done]) && finite(row[col.submit])) {
      group.answered++;
      group.latency.push(row[col.done] - row[col.submit]);
    }
  }
  return {
    ring: {total: data.total, kept: data.kept, dropped: data.dropped, capacity: data.capacity},
    byKind: [...groups.entries()].sort(([a], [b]) => (a < b ? -1 : 1)).map(([key, group]) => ({
      key, submitted: group.n, answered: group.answered, roundTripMs: summarize(group.latency),
    })),
  };
}

const results = parts.map(({label, data}) => ({
  label, host: data.host,
  ...(data.cols.includes("exec") ? {jobs: analyzeRuntimePart(data)} : {client: analyzeClientPart(data)}),
}));

if (flag("--json")) {
  console.log(JSON.stringify(results, null, 2));
  process.exit(0);
}

// --- text report ---------------------------------------------------------------------------------

const f1 = (v) => (v === null || v === undefined ? "-" : v.toFixed(v >= 100 ? 0 : v >= 10 ? 1 : 2));
const pct = (v) => (v === null || v === undefined ? "-" : `${(v * 100).toFixed(1)}%`);
const pad = (text, width) => String(text).padEnd(width);
const num = (text, width) => String(text).padStart(width);

for (const result of results) {
  console.log(`== ${result.label.trim() || "export"}`);
  if (result.client) {
    const part = result.client;
    console.log(`   server client ring: ${part.ring.total} requests (${part.ring.kept} kept, ${part.ring.dropped} dropped)`);
    console.log(`   ${pad("kernel/kind", 28)}${num("sent", 7)}${num("answered", 10)}${num("RTT p50", 10)}${num("RTT p95", 10)}${num("max", 10)}  (ms)`);
    for (const group of part.byKind) {
      console.log(`   ${pad(group.key, 28)}${num(group.submitted, 7)}${num(group.answered, 10)}${num(f1(group.roundTripMs.p50), 10)}${num(f1(group.roundTripMs.p95), 10)}${num(f1(group.roundTripMs.max), 10)}`);
    }
    continue;
  }
  const part = result.jobs;
  console.log(`   ring: ${part.ring.total} attempts (${part.ring.kept} kept, ${part.ring.dropped} dropped, capacity ${part.ring.capacity}); window ${f1(part.window.lengthMs)} ms`);
  console.log(`   statuses: ${Object.entries(part.statuses).map(([name, n]) => `${name}=${n}`).join(" ") || "-"}`);
  console.log("");
  console.log("   (a) per kernel/kind, ms");
  console.log(`   ${pad("kernel/kind", 28)}${num("jobs", 6)}${num("q p50", 9)}${num("q p95", 9)}${num("exec p50", 10)}${num("exec p95", 10)}${num("lag p50", 9)}${num("lag p95", 9)}`);
  for (const group of part.byKind) {
    console.log(`   ${pad(group.key, 28)}${num(group.ok + "/" + group.attempts, 6)}${num(f1(group.queueMs.p50), 9)}${num(f1(group.queueMs.p95), 9)}`
      + `${num(f1(group.execMs.p50), 10)}${num(f1(group.execMs.p95), 10)}${num(f1(group.workerLagMs.p50), 9)}${num(f1(group.workerLagMs.p95), 9)}`);
  }
  console.log("   (q = queue wait enqueue->dispatch, exec = start->finish in the worker, lag = dispatch->start)");
  console.log("");
  console.log("   (b) per worker utilization over the window");
  console.log(`   ${pad("worker", 10)}${num("jobs", 6)}${num("exec ms", 10)}${num("exec util", 11)}${num("in-flight ms", 14)}${num("in-flight util", 16)}`);
  for (const worker of part.workers) {
    console.log(`   ${pad("#" + worker.id, 10)}${num(worker.jobs, 6)}${num(f1(worker.execMs), 10)}${num(pct(worker.execUtilization), 11)}${num(f1(worker.inflightMs), 14)}${num(pct(worker.inflightUtilization), 16)}`);
  }
  console.log("");
  const idle = part.allIdle;
  console.log("   (c) all workers idle at the same time");
  console.log(`   nothing executing: ${f1(idle.ms)} ms = ${pct(idle.fraction)} of the window (longest stretch ${f1(idle.longestMs)} ms)`);
  console.log(`   ... with jobs waiting in the host queue: ${f1(idle.withQueuedJobsMs)} ms = ${pct(idle.withQueuedJobsFraction)}`);
  console.log(`   no job posted to any worker: ${f1(idle.noJobInFlightMs)} ms = ${pct(idle.noJobInFlightFraction)}`);
  console.log("");
}
