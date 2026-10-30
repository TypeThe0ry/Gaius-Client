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

  function toArrayBuffer(payload) {
    if (payload == null) return new ArrayBuffer(0);
    if (isArrayBuffer(payload)) return payload;
    if (ArrayBuffer.isView(payload)) {
      const {buffer, byteOffset, byteLength} = payload;
      if (isArrayBuffer(buffer) && byteOffset === 0 && byteLength === buffer.byteLength) return buffer;
      return new Uint8Array(buffer, byteOffset, byteLength).slice().buffer;
    }
    throw new TypeError("kernel payload must be an ArrayBuffer or a view of one");
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
      const job = {
        id: this.nextJobId++, seq: this.seq++, kind, key, version, priority,
        payload: toArrayBuffer(payload), transfer: opts.transfer !== false, retry: opts.retry !== false,
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
      if (job.transfer) {
        const keepCopy = job.retry && job.attempts < this.options.maxAttempts
          && buffer.byteLength <= this.options.retryCopyLimit;
        job.payload = keepCopy ? buffer.slice(0) : null;
      }
      slot.inflight.set(job.id, job);
      const transferStarted = now();
      try {
        slot.worker.postMessage({type: "job", id: job.id, kind: job.kind, payload: buffer},
          job.transfer ? [buffer] : []);
      } catch (error) {
        slot.inflight.delete(job.id);
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
      slot.inflight.delete(message.id);
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
        this.dropSlot(slot);
      }
      this.terminated = true;
      if (this.respawnTimer !== null) clearTimeout(this.respawnTimer);
      this.respawnTimer = null;
      this.byKey.clear();
      global.URL.revokeObjectURL(this.workerUrl);
    }

    // --- telemetry --------------------------------------------------------------------------

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
      return Object.assign({
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
