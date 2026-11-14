// Gaius kernel pool: runs wasm kernels (world-gen noise, section meshing, light) in a pool of
// dedicated Workers. Each Worker owns its own instance of one compiled WebAssembly.Module and
// nothing is shared, so the pool works on static hosting without cross-origin isolation and
// from file://.
//
// This file is a plain script on purpose: it is inlined into the single-file page, so it must
// not use ES module syntax. It installs globalThis.GaiusKernelPool:
//
//   const pool = await GaiusKernelPool.create({moduleBytes, workerSource, size: "auto"});
//   const result = await pool.submit("mesh_section", payload, {priority, key, version});
//
// Jobs run lowest priority first (FIFO within one priority). A key names a unit of work: a
// second submit with the same key and version shares the first promise, a newer version
// supersedes the older job (its promise rejects with code "superseded" and its result, if
// already computing, is dropped), an older version is rejected as "stale". Payloads and
// results move as transferred ArrayBuffers; a payload up to retryCopyLimit bytes keeps a
// copy while in flight so a job can be re-queued once if its Worker dies.
//
// Job timeline (diagnostics, off by default): create({timeline: true | rows}) or
// ?gaiusKernelTimeline=1 (global.__gaiusKernelTimeline) records enqueue/dispatch/worker
// receive/start/finish per job attempt and the busy intervals of every worker in fixed-capacity
// rings; pool.timelineExport({since, max}) reads them (same format as kernel-runtime.js).
// Off, no ring exists and scheduling, messages and results are untouched.
(function (global) {
  "use strict";

  const DEFAULTS = Object.freeze({
    name: "kernels",
    size: "auto",              // "auto" or a worker count
    reserve: 0,                // cores left for the page, the client and the server worker
    minSize: 1,
    maxSize: 8,
    maxInFlightPerWorker: 2,   // one running plus one waiting, so a worker never idles on a round trip
    maxAttempts: 2,            // the first run plus one re-queue after a worker crash
    retryCopyLimit: 1 << 20,   // larger transferred payloads are not kept, so they cannot be re-queued
    jobTimeoutMs: 0,           // > 0: a busy worker silent for this long is treated as hung
    maxRespawnStreak: 3,       // crashes in a row (no successful job between) before the pool halts
    respawnBackoffMs: 100,
  });

  class KernelPoolError extends Error {
    constructor(code, message, extra) {
      super(message);
      this.name = "KernelPoolError";
      this.code = code;
      if (extra) Object.assign(this, extra);
    }
  }

  const now = () => (global.performance && global.performance.now ? global.performance.now() : Date.now());
  const clamp = (value, low, high) => Math.min(high, Math.max(low, value));
  const isArrayBuffer = (value) => Object.prototype.toString.call(value) === "[object ArrayBuffer]";
  const isSharedArrayBuffer = (value) => Object.prototype.toString.call(value) === "[object SharedArrayBuffer]";
  const isBuffer = (value) => isArrayBuffer(value) || isSharedArrayBuffer(value);

  // --- job timeline ring begin (this block is kept identical in kernel-runtime.js and kernel-pool.js) ---
  // Opt-in diagnostics: one row per job attempt (enqueue, dispatch, worker receive/start/finish,
  // outcome) in a fixed-capacity ring of typed arrays, plus one row per busy interval of a worker
  // (the stretch in which it had at least one job in flight). Nothing here exists unless a
  // timeline was requested, and writing a row allocates nothing. Times are wall-clock
  // milliseconds (performance.timeOrigin + performance.now()), so the stamps of different
  // realms line up up to the clock resolution of the engine. Switch: ?gaiusKernelTimeline=1
  // (or a row capacity), localStorage gaius.kernels.settings.v1 {timeline}, the global
  // __gaiusKernelTimeline, or the create option `timeline`.
  const TIMELINE_SCHEMA = "gaius-kernel-timeline/1";
  const TIMELINE_DEFAULT_CAPACITY = 4096;
  const TIMELINE_MIN_CAPACITY = 8;
  const TIMELINE_MAX_CAPACITY = 65536;
  const TIMELINE_EXPORT_MAX_ROWS = 8192;
  const TIMELINE_NAME_LIMIT = 1024;
  const TIMELINE_COLS = Object.freeze(["seq", "id", "w", "k", "kd", "st", "at", "cls", "enq", "dsp", "rcv", "t0", "t1", "exec", "port", "rid"]);

  // true/"1"/"on" -> default capacity, a number -> that many rows, anything else -> 0 (off).
  function timelineCapacity(value) {
    if (value === true || value === "1" || value === "on" || value === "true") return TIMELINE_DEFAULT_CAPACITY;
    const n = typeof value === "string" && value !== "" ? Number(value) : value;
    if (typeof n !== "number" || !isFinite(n) || n < 1) return 0;
    return n === 1 ? TIMELINE_DEFAULT_CAPACITY
      : Math.min(TIMELINE_MAX_CAPACITY, Math.max(TIMELINE_MIN_CAPACITY, Math.floor(n)));
  }

  function timelineOrigin() {
    const p = global.performance;
    return p && typeof p.timeOrigin === "number" ? p.timeOrigin : Date.now() - now();
  }

  class JobTimeline {
    constructor(capacity, busyCapacity) {
      const n = this.capacity = capacity;
      const m = this.busyCapacity = busyCapacity || capacity;
      this.origin = timelineOrigin();
      this.seq = 0;
      this.id = new Float64Array(n);
      this.w = new Int32Array(n);
      this.k = new Int16Array(n);
      this.kd = new Int16Array(n);
      this.st = new Int16Array(n);
      this.at = new Uint8Array(n);
      this.cls = new Int8Array(n);
      this.enq = new Float64Array(n);
      this.dsp = new Float64Array(n);
      this.rcv = new Float64Array(n);
      this.t0 = new Float64Array(n);
      this.t1 = new Float64Array(n);
      this.exec = new Float64Array(n);
      this.port = new Int32Array(n);
      this.rid = new Float64Array(n);
      this.busySeq = 0;
      this.bw = new Int32Array(m);
      this.bb = new Float64Array(m);
      this.be = new Float64Array(m);
      this.names = ["?"];
      this.index = new Map();
    }

    intern(text) {
      const known = this.index.get(text);
      if (known !== undefined) return known;
      if (this.names.length >= TIMELINE_NAME_LIMIT) return 0;
      this.names.push(String(text));
      this.index.set(text, this.names.length - 1);
      return this.names.length - 1;
    }

    // Appends one attempt. Missing stamps are NaN. No allocation (names are interned once).
    record(id, w, kernel, kind, status, attempt, cls, enq, dsp, rcv, t0, t1, exec, port, rid) {
      const i = this.seq % this.capacity;
      this.id[i] = id;
      this.w[i] = w;
      this.k[i] = this.intern(kernel);
      this.kd[i] = this.intern(kind);
      this.st[i] = this.intern(status);
      this.at[i] = attempt > 255 ? 255 : attempt;
      this.cls[i] = cls;
      this.enq[i] = enq;
      this.dsp[i] = dsp;
      this.rcv[i] = rcv;
      this.t0[i] = t0;
      this.t1[i] = t1;
      this.exec[i] = exec;
      this.port[i] = port;
      this.rid[i] = rid;
      this.seq++;
    }

    busy(w, begin, end) {
      const i = this.busySeq % this.busyCapacity;
      this.bw[i] = w;
      this.bb[i] = begin;
      this.be[i] = end;
      this.busySeq++;
    }

    stats() {
      return {
        capacity: this.capacity, total: this.seq, kept: Math.min(this.seq, this.capacity),
        dropped: Math.max(0, this.seq - this.capacity),
        busyTotal: this.busySeq, busyDropped: Math.max(0, this.busySeq - this.busyCapacity),
      };
    }

    // Compact JSON-serializable view. Rows are arrays in TIMELINE_COLS order, oldest first; the
    // k/kd/st columns index `strings`, times are milliseconds after `base` (epoch ms of this
    // realm's time origin; a missing stamp is null). options.since is the `next` of an earlier
    // export (rows lost to the ring in between are reported in `lost`), options.max bounds the
    // rows, options.sinceBusy / `busy.next` do the same for the busy intervals.
    export(options) {
      const o = options || {};
      const cap = this.capacity;
      const limit = Math.min(TIMELINE_EXPORT_MAX_ROWS, Math.max(1,
        Math.floor(Number(o.max)) || Math.min(cap, TIMELINE_DEFAULT_CAPACITY)));
      const oldest = Math.max(0, this.seq - cap);
      const hasSince = typeof o.since === "number" && isFinite(o.since) && o.since >= 0;
      const start = hasSince ? Math.max(Math.floor(o.since), oldest) : Math.max(oldest, this.seq - limit);
      const end = Math.min(this.seq, start + limit);
      const base = this.origin;
      const rel = (x) => (x !== x ? null : Math.round((x - base) * 1000) / 1000);
      const rows = [];
      for (let n = start; n < end; n++) {
        const i = n % cap;
        rows.push([n, this.id[i], this.w[i], this.k[i], this.kd[i], this.st[i], this.at[i], this.cls[i],
          rel(this.enq[i]), rel(this.dsp[i]), rel(this.rcv[i]), rel(this.t0[i]), rel(this.t1[i]),
          this.exec[i] !== this.exec[i] ? null : Math.round(this.exec[i] * 1000) / 1000, this.port[i],
          this.rid[i]]);
      }
      const bcap = this.busyCapacity;
      const boldest = Math.max(0, this.busySeq - bcap);
      const hasBusy = typeof o.sinceBusy === "number" && isFinite(o.sinceBusy) && o.sinceBusy >= 0;
      const bstart = hasBusy ? Math.max(Math.floor(o.sinceBusy), boldest) : Math.max(boldest, this.busySeq - limit);
      const bend = Math.min(this.busySeq, bstart + limit);
      const busyRows = [];
      for (let n = bstart; n < bend; n++) {
        const i = n % bcap;
        busyRows.push([n, this.bw[i], rel(this.bb[i]), rel(this.be[i])]);
      }
      const stats = this.stats();
      return {
        schema: TIMELINE_SCHEMA, enabled: true, base, exportedAt: Math.round((base + now()) * 1000) / 1000,
        capacity: cap, total: stats.total, kept: stats.kept, dropped: stats.dropped,
        lost: hasSince ? Math.max(0, oldest - Math.floor(o.since)) : 0, next: end,
        cols: TIMELINE_COLS, strings: this.names.slice(), rows,
        busy: {cols: ["seq", "w", "b", "e"], rows: busyRows, total: stats.busyTotal, dropped: stats.busyDropped,
          lost: hasBusy ? Math.max(0, boldest - Math.floor(o.sinceBusy)) : 0, next: bend},
      };
    }
  }
  // --- job timeline ring end ---

  function toArrayBuffer(payload) {
    if (payload == null) return new ArrayBuffer(0);
    if (isBuffer(payload)) return payload;
    if (ArrayBuffer.isView(payload)) {
      const {buffer, byteOffset, byteLength} = payload;
      if (isBuffer(buffer) && byteOffset === 0 && byteLength === buffer.byteLength) return buffer;
      return new Uint8Array(buffer, byteOffset, byteLength).slice().buffer;
    }
    throw new TypeError("kernel payload must be a buffer or a view of one");
  }

  // Default worker count: all cores but two (main thread and the integrated server), at most 8.
  function autoSize(hardwareConcurrency, reserve = 0, minSize = 1, maxSize = 8) {
    const cores = Number.isFinite(hardwareConcurrency) && hardwareConcurrency > 0
      ? Math.floor(hardwareConcurrency) : 4;
    return clamp(clamp(cores - 2, 1, 8) - Math.max(0, reserve | 0), Math.max(1, minSize), maxSize);
  }

  function readKinds(module) {
    try {
      const kinds = new Set();
      for (const entry of global.WebAssembly.Module.exports(module)) {
        if (entry.kind === "function" && entry.name.startsWith("run_")) kinds.add(entry.name.slice(4));
      }
      return kinds;
    } catch (_) {
      return null;
    }
  }

  function decodeBase64(text) {
    const binary = global.atob(text);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
  }

  // Binary heap of queue entries with lazy deletion: a cancelled or re-prioritized job only
  // marks its entry dead, and dead entries are skipped on pop or dropped by compact().
  class JobHeap {
    constructor() {
      this.items = [];
      this.dead = 0;
    }

    static less(a, b) {
      return a.priority < b.priority || (a.priority === b.priority && a.seq < b.seq);
    }

    push(entry) {
      const items = this.items;
      items.push(entry);
      let i = items.length - 1;
      while (i > 0) {
        const parent = (i - 1) >> 1;
        if (!JobHeap.less(items[i], items[parent])) break;
        [items[i], items[parent]] = [items[parent], items[i]];
        i = parent;
      }
    }

    pop() {
      const items = this.items;
      const top = items[0];
      const last = items.pop();
      if (items.length > 0) {
        items[0] = last;
        this.siftDown(0);
      }
      return top;
    }

    siftDown(i) {
      const items = this.items;
      for (;;) {
        const left = 2 * i + 1;
        const right = left + 1;
        let best = i;
        if (left < items.length && JobHeap.less(items[left], items[best])) best = left;
        if (right < items.length && JobHeap.less(items[right], items[best])) best = right;
        if (best === i) return;
        [items[i], items[best]] = [items[best], items[i]];
        i = best;
      }
    }

    kill(entry) {
      entry.live = false;
      if (++this.dead > 1024 && this.dead > this.items.length / 2) this.compact();
    }

    compact() {
      this.items = this.items.filter((entry) => entry.live);
      this.dead = 0;
      for (let i = (this.items.length >> 1) - 1; i >= 0; i--) this.siftDown(i);
    }

    takeLive() {
      while (this.items.length > 0) {
        const entry = this.pop();
        if (entry.live) return entry;
        this.dead--;
      }
      return null;
    }
  }

  class KernelPool {
    constructor(options, module, moduleBytes, workerSource) {
      this.options = options;
      this.name = options.name;
      this.module = module;
      this.moduleBytes = moduleBytes;
      this.cloneModule = true;
      this.kinds = readKinds(module);
      this.workerUrl = global.URL.createObjectURL(new global.Blob([workerSource], {type: "text/javascript"}));
      this.heap = new JobHeap();
      this.queued = 0;
      this.byKey = new Map();
      this.slots = [];
      this.nextJobId = 1;
      this.nextSlotId = 1;
      this.seq = 0;
      this.sizeMode = options.size === "auto" ? "auto" : this.clampSize(options.size);
      this.reserve = Math.max(0, options.reserve | 0);
      this.crashStreak = 0;
      this.lastCrash = "";
      this.halted = null;
      this.terminated = false;
      this.pumpScheduled = false;
      this.respawnTimer = null;
      this.stats = {
        submitted: 0, completed: 0, failed: 0, cancelled: 0, superseded: 0, deduped: 0, stale: 0,
        dropped: 0, retried: 0, crashes: 0, spawned: 0, respawns: 0, retired: 0,
        transferMs: 0, maxTransferMs: 0,
      };
      this.kindStats = new Map();
      // Job timeline (see the ring block): null unless requested.
      this.timeline = null;
      let requested = options.timeline;
      if (requested === undefined) {
        requested = global.__gaiusKernelTimeline;
        try {
          const param = new global.URLSearchParams(global.location && global.location.search || "").get("gaiusKernelTimeline");
          if (param !== null) requested = param;
        } catch (_) { /* no URL parameters here */ }
      }
      const timelineRows = timelineCapacity(requested);
      if (timelineRows > 0) {
        this.timeline = new JobTimeline(timelineRows);
        const sources = global.__gaiusKernelTimelineSources || (global.__gaiusKernelTimelineSources = new Set());
        sources.add(this);
      }
      this.reconcile(false);
    }

    clampSize(size) {
      return clamp(Math.floor(Number(size)) || 1, Math.max(1, this.options.minSize), this.options.maxSize);
    }

    get targetSize() {
      if (this.sizeMode !== "auto") return this.sizeMode;
      const nav = global.navigator;
      return autoSize(nav && nav.hardwareConcurrency, this.reserve, this.options.minSize, this.options.maxSize);
    }

    get size() {
      return this.activeSlots().length;
    }

    activeSlots() {
      return this.slots.filter((slot) => !slot.dead && !slot.draining);
    }

    // --- submission -------------------------------------------------------------------------

    submit(kind, payload, opts = {}) {
      if (!global.GaiusKernelMirror || !global.GaiusKernelMirror.allowed(kind, opts.kernel, this.options.profileId)) {
        return Promise.reject(new KernelPoolError("needs-vanilla", "NEEDS_VANILLA: kernel mirror is disabled", {kind}));
      }
      if (this.terminated) return Promise.reject(new KernelPoolError("terminated", `kernel pool ${this.name} is terminated`));
      if (this.halted) return Promise.reject(this.halted);
      if (this.kinds && !this.kinds.has(kind)) {
        return Promise.reject(new KernelPoolError("unknown-kind", `kernel module has no export run_${kind}`, {kind}));
      }
      const priority = Number.isFinite(opts.priority) ? opts.priority : 0;
      const version = Number.isFinite(opts.version) ? opts.version : 0;
      const key = opts.key == null ? null : opts.key;
      if (key !== null) {
        const current = this.byKey.get(key);
        if (current) {
          if (current.kind === kind && current.version === version) {
            this.stats.deduped++;
            if (current.state === "queued" && priority < current.priority) this.enqueue(current, priority);
            return current.promise;
          }
          if (version < current.version) {
            this.stats.stale++;
            return Promise.reject(new KernelPoolError("stale",
              `job ${String(key)} v${version} is older than queued v${current.version}`, {kind, key}));
          }
          this.cancelJob(current, "superseded", `job ${String(key)} superseded by v${version}`);
        }
      }
      const buffer = toArrayBuffer(payload);
      const job = {
        id: this.nextJobId++, seq: this.seq++, kind, key, version, priority,
        payload: buffer, transfer: opts.transfer !== false && !isSharedArrayBuffer(buffer), retry: opts.retry !== false,
        attempts: 0, state: "queued", settled: false, slot: null, entry: null,
        submittedAt: now(), detachSignal: null, promise: null, resolve: null, reject: null,
      };
      job.promise = new Promise((resolve, reject) => {
        job.resolve = resolve;
        job.reject = reject;
      });
      if (key !== null) this.byKey.set(key, job);
      if (opts.signal) this.watchSignal(job, opts.signal);
      this.stats.submitted++;
      this.kindStat(kind).submitted++;
      this.enqueue(job, priority);
      this.schedulePump();
      return job.promise;
    }

    watchSignal(job, signal) {
      const onAbort = () => this.cancelJob(job, "cancelled", "job aborted");
      if (signal.aborted) return void Promise.resolve().then(onAbort);
      signal.addEventListener("abort", onAbort, {once: true});
      job.detachSignal = () => signal.removeEventListener("abort", onAbort);
    }

    cancel(key) {
      const job = this.byKey.get(key);
      return job ? this.cancelJob(job, "cancelled", `job ${String(key)} cancelled`) : false;
    }

    cancelJob(job, code, message) {
      if (job.settled) return false;
      const slot = job.state === "inflight" ? job.slot : null;
      this.stats[code === "superseded" ? "superseded" : "cancelled"]++;
      this.settle(job, new KernelPoolError(code, message, {kind: job.kind, key: job.key}));
      // The job stays counted on its worker until the worker answers; a job still waiting in
      // the worker is skipped there, a running one finishes and its result is dropped.
      if (slot && !slot.dead) {
        try {
          slot.worker.postMessage({type: "cancel", id: job.id});
        } catch (_) { /* the worker is going away; its crash path settles nothing more */ }
      }
      return true;
    }

    enqueue(job, priority) {
      if (job.entry) this.heap.kill(job.entry);
      else this.queued++;
      job.priority = priority;
      job.state = "queued";
      job.entry = {job, priority, seq: job.seq, live: true};
      this.heap.push(job.entry);
    }

    unqueue(job) {
      if (!job.entry) return;
      this.heap.kill(job.entry);
      job.entry = null;
      this.queued--;
    }

    settle(job, error, value) {
      if (job.settled) return;
      // A job answered by a worker gets its row from finish()/crash(); this one never reached a
      // worker (or is between two attempts).
      if (this.timeline !== null && job.state !== "inflight") this.recordAttempt(job, null, error ? error.code : "ok", null, NaN);
      if (job.state === "queued") this.unqueue(job);
      job.settled = true;
      job.state = "done";
      job.payload = null;
      if (job.key !== null && this.byKey.get(job.key) === job) this.byKey.delete(job.key);
      if (job.detachSignal) job.detachSignal();
      if (error) job.reject(error);
      else job.resolve(value);
    }

    fail(job, error) {
      this.stats.failed++;
      this.kindStat(job.kind).failed++;
      this.settle(job, error);
    }

    // --- dispatch ---------------------------------------------------------------------------

    schedulePump() {
      if (this.pumpScheduled || this.terminated) return;
      this.pumpScheduled = true;
      // A microtask, so a burst of synchronous submits is ordered by priority before any runs.
      Promise.resolve().then(() => this.pump());
    }

    pump() {
      this.pumpScheduled = false;
      if (this.terminated) return;
      const limit = Math.max(1, this.options.maxInFlightPerWorker | 0);
      while (this.queued > 0) {
        let slot = null;
        for (const candidate of this.slots) {
          if (!candidate.ready || candidate.draining || candidate.dead || candidate.inflight.size >= limit) continue;
          if (!slot || candidate.inflight.size < slot.inflight.size) slot = candidate;
        }
        if (!slot) return;
        const entry = this.heap.takeLive();
        if (!entry) return;
        entry.job.entry = null;
        this.queued--;
        this.dispatch(slot, entry.job);
      }
    }

    dispatch(slot, job) {
      const buffer = job.payload;
      job.state = "inflight";
      job.slot = slot;
      job.attempts++;
      if (this.timeline !== null) job.dispatchedAt = now();
      if (job.transfer) {
        const keepCopy = job.retry && job.attempts < this.options.maxAttempts
          && buffer.byteLength <= this.options.retryCopyLimit;
        job.payload = keepCopy ? buffer.slice(0) : null;
      }
      if (this.timeline !== null && slot.inflight.size === 0) slot.busySince = now();
      slot.inflight.set(job.id, job);
      const transferStarted = now();
      try {
        const message = {type: "job", id: job.id, kind: job.kind, payload: buffer};
        if (this.timeline !== null) message.tl = 1;
        slot.worker.postMessage(message, job.transfer ? [buffer] : []);
      } catch (error) {
        if (this.timeline !== null) this.recordAttempt(job, slot, "post-failed", null, NaN);
        slot.inflight.delete(job.id);
        if (this.timeline !== null && slot.inflight.size === 0) this.closeBusy(slot);
        this.fail(job, new KernelPoolError("post-failed", String(error && error.message || error), {kind: job.kind}));
        return;
      }
      const transferMs = Math.max(0, now() - transferStarted);
      this.stats.transferMs += transferMs;
      this.stats.maxTransferMs = Math.max(this.stats.maxTransferMs, transferMs);
      if (slot.watchdog === null) this.armWatchdog(slot);
    }

    armWatchdog(slot) {
      if (slot.watchdog !== null) clearTimeout(slot.watchdog);
      slot.watchdog = null;
      const timeout = this.options.jobTimeoutMs;
      if (!(timeout > 0) || slot.dead || slot.inflight.size === 0) return;
      slot.watchdog = setTimeout(() => {
        slot.watchdog = null;
        this.crash(slot, new KernelPoolError("worker-hung", `kernel worker ${slot.id} silent for ${timeout} ms`));
      }, timeout);
    }

    // --- worker lifecycle -------------------------------------------------------------------

    spawn() {
      const slot = {
        id: this.nextSlotId++, worker: null, ready: false, draining: false, dead: false,
        inflight: new Map(), completed: 0, watchdog: null,
      };
      slot.worker = new global.Worker(this.workerUrl, {name: `${this.name}-${slot.id}`});
      slot.worker.onmessage = (event) => this.onMessage(slot, event.data);
      slot.worker.onerror = (event) => {
        if (event && event.preventDefault) event.preventDefault();
        this.crash(slot, new KernelPoolError("worker-crashed",
          `kernel worker ${slot.id} failed: ${event && event.message || "uncaught error"}`));
      };
      slot.worker.onmessageerror = () => {
        this.crash(slot, new KernelPoolError("worker-crashed", `kernel worker ${slot.id} sent an unreadable message`));
      };
      this.slots.push(slot);
      this.stats.spawned++;
      this.postInit(slot);
      return slot;
    }

    postInit(slot) {
      const init = {type: "init", name: this.name, slot: slot.id};
      if (this.cloneModule) {
        try {
          slot.worker.postMessage(Object.assign({module: this.module}, init));
          return;
        } catch (error) {
          // Engines that cannot clone a WebAssembly.Module into a Worker get the bytes instead.
          if (!this.moduleBytes) throw error;
          this.cloneModule = false;
        }
      }
      const bytes = this.moduleBytes.slice(0);
      slot.worker.postMessage(Object.assign({bytes}, init), [bytes]);
    }

    onMessage(slot, message) {
      if (slot.dead || this.terminated || !message) return;
      switch (message.type) {
        case "ready":
          slot.ready = true;
          this.schedulePump();
          return;
        case "init-error":
          this.crash(slot, new KernelPoolError("worker-init-failed", `kernel worker ${slot.id}: ${message.message}`));
          return;
        case "result":
        case "error":
        case "cancelled":
          this.finish(slot, message);
          return;
        default:
          return;
      }
    }

    finish(slot, message) {
      const job = slot.inflight.get(message.id);
      if (!job) return;
      if (this.timeline !== null) {
        this.recordAttempt(job, slot, message.type === "result" ? (job.settled ? "dropped" : "ok")
          : message.type === "error" ? (message.code || "kernel-error") : "cancelled",
          Array.isArray(message.tl) ? message.tl : null, Number.isFinite(message.execMs) ? message.execMs : NaN);
      }
      slot.inflight.delete(message.id);
      if (this.timeline !== null && slot.inflight.size === 0) this.closeBusy(slot);
      this.armWatchdog(slot);
      if (message.type === "result") {
        slot.completed++;
        if (this.crashStreak > 0) {
          this.crashStreak = 0;
          if (this.activeSlots().length < this.targetSize) this.reconcile(true);
        }
      }
      if (job.settled) {
        if (message.type === "result") this.stats.dropped++;
      } else if (message.type === "result") {
        const stat = this.kindStat(job.kind);
        const latency = now() - job.submittedAt;
        const exec = Number(message.execMs) || 0;
        stat.completed++;
        stat.totalMs += latency;
        stat.maxMs = Math.max(stat.maxMs, latency);
        stat.totalExecMs += exec;
        stat.maxExecMs = Math.max(stat.maxExecMs, exec);
        this.stats.completed++;
        this.settle(job, null, message.result);
      } else if (message.type === "error") {
        this.fail(job, new KernelPoolError(message.code || "kernel-error", message.message || "kernel failed",
          {kind: job.kind, status: message.status}));
      } else {
        this.stats.cancelled++;
        this.settle(job, new KernelPoolError("cancelled", "job skipped by its worker", {kind: job.kind, key: job.key}));
      }
      if (slot.draining && slot.inflight.size === 0) this.retire(slot);
      this.schedulePump();
    }

    crash(slot, error) {
      if (slot.dead) return;
      this.dropSlot(slot);
      if (this.terminated) return;
      this.stats.crashes++;
      this.crashStreak++;
      this.lastCrash = error.message;
      if (this.timeline !== null) {
        for (const job of slot.inflight.values()) this.recordAttempt(job, slot, error.code || "worker-crashed", null, NaN);
        this.closeBusy(slot);
      }
      for (const job of slot.inflight.values()) {
        if (job.settled) continue;
        job.slot = null;
        if (job.retry && job.attempts < this.options.maxAttempts && job.payload) {
          this.stats.retried++;
          this.enqueue(job, job.priority);
        } else {
          this.fail(job, new KernelPoolError(error.code, `${error.message} (${job.kind}, attempt ${job.attempts})`,
            {kind: job.kind, key: job.key}));
        }
      }
      slot.inflight.clear();
      if (!slot.draining) this.scheduleRespawn();
      this.schedulePump();
    }

    scheduleRespawn() {
      if (this.crashStreak > this.options.maxRespawnStreak) {
        if (this.activeSlots().length === 0) {
          this.halted = new KernelPoolError("pool-unavailable",
            `kernel pool ${this.name} halted after ${this.crashStreak} worker failures: ${this.lastCrash}`);
          this.failQueued(this.halted);
        }
        return;
      }
      if (this.respawnTimer !== null) return;
      const delay = this.crashStreak <= 1 ? 0
        : Math.min(2000, this.options.respawnBackoffMs * 2 ** (this.crashStreak - 2));
      this.respawnTimer = setTimeout(() => {
        this.respawnTimer = null;
        if (!this.terminated) this.reconcile(true);
      }, delay);
    }

    failQueued(error) {
      for (let entry = this.heap.takeLive(); entry; entry = this.heap.takeLive()) {
        entry.job.entry = null;
        this.queued--;
        this.fail(entry.job, error);
      }
    }

    dropSlot(slot) {
      slot.dead = true;
      slot.ready = false;
      if (slot.watchdog !== null) clearTimeout(slot.watchdog);
      slot.watchdog = null;
      try {
        slot.worker.terminate();
      } catch (_) { /* already gone */ }
      const index = this.slots.indexOf(slot);
      if (index >= 0) this.slots.splice(index, 1);
    }

    retire(slot) {
      this.dropSlot(slot);
      this.stats.retired++;
    }

    // Brings the number of accepting workers to targetSize: revives draining workers first,
    // then spawns; when shrinking, idle workers go first and busy ones drain.
    reconcile(respawn) {
      if (this.terminated) return;
      if (!global.GaiusKernelMirror || !this.kinds
        || ![...this.kinds].some((kind) => global.GaiusKernelMirror.allowed(kind, null, this.options.profileId))) return;
      const target = this.targetSize;
      let active = this.activeSlots().length;
      for (const slot of this.slots) {
        if (active >= target) break;
        if (slot.draining && !slot.dead) {
          slot.draining = false;
          active++;
        }
      }
      try {
        for (; active < target; active++) {
          this.spawn();
          if (respawn) this.stats.respawns++;
        }
      } catch (error) {
        this.lastCrash = String(error && error.message || error);
        if (this.activeSlots().length === 0) {
          this.halted = new KernelPoolError("pool-unavailable", `cannot start kernel workers: ${this.lastCrash}`);
          this.failQueued(this.halted);
        }
      }
      if (active > target) {
        const victims = this.activeSlots().sort((a, b) =>
          (a.ready - b.ready) || (a.inflight.size - b.inflight.size));
        for (const slot of victims.slice(0, active - target)) {
          slot.draining = true;
          if (slot.inflight.size === 0) this.retire(slot);
        }
      }
      this.schedulePump();
    }

    setSize(size) {
      this.sizeMode = size === "auto" ? "auto" : this.clampSize(size);
      this.reconcile(false);
      return this.targetSize;
    }

    grow(count = 1) {
      return this.setSize(this.targetSize + count);
    }

    shrink(count = 1) {
      return this.setSize(this.targetSize - count);
    }

    setReserve(reserve) {
      this.reserve = Math.max(0, reserve | 0);
      this.reconcile(false);
      return this.targetSize;
    }

    // Clears a halt (e.g. after the page recovered memory) and starts workers again.
    restart() {
      if (this.terminated) return;
      this.halted = null;
      this.crashStreak = 0;
      this.reconcile(false);
    }

    terminate() {
      if (this.terminated) return;
      const error = new KernelPoolError("terminated", `kernel pool ${this.name} terminated`);
      for (let entry = this.heap.takeLive(); entry; entry = this.heap.takeLive()) {
        entry.job.entry = null;
        this.queued--;
        this.stats.cancelled++;
        this.settle(entry.job, error);
      }
      for (const slot of this.slots.slice()) {
        for (const job of slot.inflight.values()) {
          if (!job.settled) this.stats.cancelled++;
          this.settle(job, error);
        }
        slot.inflight.clear();
        if (this.timeline !== null) this.closeBusy(slot);
        this.dropSlot(slot);
      }
      this.terminated = true;
      if (this.timeline !== null && global.__gaiusKernelTimelineSources) global.__gaiusKernelTimelineSources.delete(this);
      if (this.respawnTimer !== null) clearTimeout(this.respawnTimer);
      this.respawnTimer = null;
      this.byKey.clear();
      global.URL.revokeObjectURL(this.workerUrl);
    }

    // --- telemetry --------------------------------------------------------------------------

    // Timeline helpers: the row of one attempt (tl = worker [received, started, finished] stamps
    // or null) and the end of a worker's busy interval (it started when its first job went out).
    recordAttempt(job, slot, status, tl, execMs) {
      const t = this.timeline;
      const origin = t.origin;
      t.record(job.id, slot ? slot.id : 0, this.name, job.kind, status, job.attempts, job.priority,
        origin + job.submittedAt, slot && job.dispatchedAt !== undefined ? origin + job.dispatchedAt : NaN,
        tl ? tl[0] : NaN, tl ? tl[1] : NaN, tl ? tl[2] : NaN, execMs, 0, -1);
    }

    closeBusy(slot) {
      const begin = slot.busySince;
      if (begin === undefined || begin !== begin) return;
      slot.busySince = NaN;
      this.timeline.busy(slot.id, this.timeline.origin + begin, this.timeline.origin + now());
    }

    timelineExport(options) {
      const t = this.timeline;
      if (t === null) return {schema: TIMELINE_SCHEMA, enabled: false, host: "pool", name: this.name};
      const out = t.export(options);
      out.host = "pool";
      out.name = this.name;
      out.workers = this.slots.map((slot) => ({id: slot.id, dead: slot.dead, draining: slot.draining,
        completed: slot.completed, inFlight: slot.inflight.size,
        busySince: slot.busySince === undefined || slot.busySince !== slot.busySince ? null : Math.round(slot.busySince * 1000) / 1000}));
      return out;
    }

    kindStat(kind) {
      let stat = this.kindStats.get(kind);
      if (!stat) {
        stat = {submitted: 0, completed: 0, failed: 0, totalMs: 0, maxMs: 0, totalExecMs: 0, maxExecMs: 0};
        this.kindStats.set(kind, stat);
      }
      return stat;
    }

    telemetry() {
      const kinds = {};
      for (const [kind, stat] of this.kindStats) {
        kinds[kind] = {
          submitted: stat.submitted, completed: stat.completed,
          failed: stat.failed,
          meanMs: stat.completed ? stat.totalMs / stat.completed : 0,
          maxMs: stat.maxMs,
          meanExecMs: stat.completed ? stat.totalExecMs / stat.completed : 0,
          maxExecMs: stat.maxExecMs,
        };
      }
      let inFlight = 0;
      for (const slot of this.slots) inFlight += slot.inflight.size;
      const telemetry = Object.assign({
        name: this.name,
        size: this.size,
        targetSize: this.targetSize,
        sizeMode: this.sizeMode,
        reserve: this.reserve,
        queued: this.queued,
        inFlight,
        halted: this.halted ? this.halted.message : null,
        terminated: this.terminated,
        workers: this.slots.map((slot) => ({
          id: slot.id, ready: slot.ready, draining: slot.draining,
          inFlight: slot.inflight.size, completed: slot.completed,
        })),
        kinds,
      }, this.stats);
      if (this.timeline !== null) telemetry.timeline = this.timeline.stats();
      return telemetry;
    }
  }

  async function create(options = {}) {
    const opts = Object.assign({}, DEFAULTS, options);
    let module = opts.module || null;
    let bytes = null;
    if (!module) {
      if (opts.moduleBytes) {
        bytes = toArrayBuffer(opts.moduleBytes).slice(0);
      } else if (opts.moduleBase64) {
        bytes = decodeBase64(opts.moduleBase64);
      } else if (opts.moduleUrl) {
        const response = await global.fetch(opts.moduleUrl);
        if (!response.ok) throw new Error(`kernel module ${opts.moduleUrl}: HTTP ${response.status}`);
        bytes = await response.arrayBuffer();
      } else {
        throw new TypeError("GaiusKernelPool.create needs module, moduleBytes, moduleBase64 or moduleUrl");
      }
      module = await global.WebAssembly.compile(bytes);
    }
    let workerSource = opts.workerSource || api.workerSource;
    if (!workerSource && opts.workerElementId && global.document) {
      const element = global.document.getElementById(opts.workerElementId);
      workerSource = element && element.textContent;
    }
    if (typeof workerSource !== "string" || workerSource.length === 0) {
      throw new TypeError("GaiusKernelPool.create needs the kernel worker source text");
    }
    return new KernelPool(opts, module, bytes, workerSource);
  }

  const api = {
    create,
    autoSize,
    KernelPoolError,
    DEFAULTS,
    workerSource: null,  // a build may set this to the inlined kernel-worker.js text
  };
  global.GaiusKernelPool = api;
})(typeof globalThis !== "undefined" ? globalThis : self);
