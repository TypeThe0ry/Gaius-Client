// Gaius kernel runtime: one scheduler for every wasm kernel (mesh, light, worldgen, noise) over
// one pool of generic kernel Workers (kernel-worker.js). It replaces per-kernel pools with a
// single priority model, a single memory budget and a single adaptive worker count, so a
// 4 GB Chromebook and a 16-core desktop run the same code with different plans.
//
//   P0 visible mesh > P1 visible light > P2 near generation ahead of the player's motion >
//   P3 far generation / off-screen work > P4 saves and background work
//
// Policies live in kernel-policy.js (load it first). This file is a plain script, inlined into
// the single-file page next to it. It installs globalThis.GaiusKernelRuntime:
//
//   const runtime = await GaiusKernelRuntime.create({
//     kernels: {mesher: {kinds: ["mesh_section"], variants: {simd: {url}, baseline: {url}}}, ...},
//     workerUrl | workerSource,             // kernel-worker.js (a URL keeps the V8 code cache)
//   });
//   runtime.submit("mesh_section", payload, {kernel: "mesher", key, version, visible: true,
//                                           cx, cz, distance, resultBytes, signal, transfer});
//   runtime.setViewer({x, y, z, yaw, vx?, vz?, viewDistance?});    // movement prediction
//   runtime.available("mesher")           // false: use the vanilla Java path
//   runtime.setPrimer("mesher", {kind, payload})   // job run on every new instance (see below)
//   runtime.reportPressure(0..1)          // heap pressure seen by the page (Java BrowserMemory)
//   runtime.attachPort(port)              // serve a remote client (the server Worker)
//   GaiusKernelRuntime.connect(port)      // that remote client, in the other realm
//
// Kernel names are the crate names without "gaius-" and "-wasm": mesher, light, worldgen, noise.
//
// Primers: a kernel that keeps state between jobs (the mesher's model table) may name a job
// that every new instance runs before its first real job, so a worker that was just spawned,
// respawned after a crash or reloaded after an idle unload does not answer its first job with
// "table missing". A submitted job whose kind is in options.primeKinds (load_model_table by
// default) becomes its kernel's primer automatically; setPrimer sets or clears one directly. The
// retained primer payload counts against the memory budget, and so does the state it creates in
// each instance (estimated by its size until the worker reports its memory).
//
// Memory pressure: when the heap pressure (performance.memory, or the level the page reports
// through reportPressure) reaches options.trimPressure, every worker is asked, at most once per
// options.trimIntervalMs, to call the trim exports of its instances (mesh_trim, light_trim and
// any other export named trim or *_trim), which drop the cached tables and scratch buffers. A
// trimmed mesher answers its next job with "table missing" and recovers through its session.
//
// Kernels are compiled once on this thread (simd128 build when WebAssembly.validate accepts a
// SIMD probe, the baseline build otherwise or when the SIMD module does not compile) and the
// compiled WebAssembly.Module is posted to each worker that needs it (bytes when an engine
// cannot clone modules). Payloads and results move as transferred ArrayBuffers. When the page
// is cross-origin isolated, kernels whose module imports env.memory get a shared
// WebAssembly.Memory per instance (runtime.sharedMemory reports it); the job codecs can build
// a zero-copy path on that later, today every kernel keeps message passing.
//
// Every failure degrades to the vanilla path: a kernel whose module does not compile, traps
// repeatedly or cannot be loaded is disabled (its jobs reject with code "kernel-disabled");
// a pool that cannot keep workers alive halts ("runtime-unavailable"); jobs refused by the
// memory budget reject with "backpressure". URL switches: ?gaiusKernels=0 (all off),
// ?gaiusKernelsOff=mesher,light, ?gaiusKernelSimd=0|1, ?gaiusKernelWorkers=<n>,
// ?gaiusKernelBudgetMB=<n>; the same settings persist under localStorage
// gaius.kernels.settings.v1 ({enabled, off, simd, workers, budgetMB}).
(function (global) {
  "use strict";

  const DEFAULTS = Object.freeze({
    name: "kernels",
    size: "auto",
    reserve: 0,
    maxSize: 8,
    budgetBytes: 0,
    retryCopyLimit: 1 << 20,
    maxAttempts: 2,
    jobTimeoutMs: 30000,
    maxRespawnStreak: 3,
    respawnBackoffMs: 100,
    maxKernelFailures: 3,
    sizingIntervalMs: 1000,
    rescoreIntervalMs: 1000,
    rescoreMinGapMs: 100,
    idleUnloadMs: 60000,
    instanceEstimateBytes: 16 * 1024 * 1024,
    sharedMemory: "auto",
    simd: "auto",
    primeKinds: Object.freeze(["load_model_table"]),
    trimPressure: 0.85,
    trimIntervalMs: 15000,
    maxPendingRequests: 256,
    // A remote client may attach several response listeners to one deduplicated job, but
    // that listener list must remain bounded even when a caller retries the same request.
    maxPendingWaiters: 256,
  });

  // (module (func (result v128) i32.const 0 i8x16.splat i8x16.popcnt))
  const SIMD_PROBE = [0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11];
  const SETTINGS_KEY = "gaius.kernels.settings.v1";

  class KernelRuntimeError extends Error {
    constructor(code, message, extra) {
      super(message);
      this.name = "KernelRuntimeError";
      this.code = code;
      if (extra) Object.assign(this, extra);
    }
  }

  const now = () => (global.performance && global.performance.now ? global.performance.now() : Date.now());
  const isArrayBuffer = (value) => Object.prototype.toString.call(value) === "[object ArrayBuffer]";
  const finite = (value) => typeof value === "number" && isFinite(value);

  function policy() {
    const P = global.GaiusKernelPolicy;
    if (!P) throw new Error("GaiusKernelRuntime needs kernel-policy.js loaded first");
    return P;
  }

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

  function decodeBase64(text) {
    const binary = global.atob(text);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
  }

  function wasmBytes(value) {
    const bytes = new Uint8Array(toArrayBuffer(value));
    if (bytes.byteLength < 4 || bytes[0] !== 0 || bytes[1] !== 0x61 || bytes[2] !== 0x73 || bytes[3] !== 0x6d) {
      throw new TypeError("kernel bytes do not have the WebAssembly magic header");
    }
    return bytes.buffer;
  }

  async function verifyKernelBytes(bytes, expected, label) {
    const buffer = wasmBytes(bytes);
    if (expected === undefined || expected === null || expected === "") return buffer;
    const digest = global.crypto && global.crypto.subtle;
    if (!digest || typeof digest.digest !== "function") throw new Error(`${label}: SHA-256 verification is unavailable`);
    const actual = Array.from(new Uint8Array(await digest.digest("SHA-256", buffer)),
      (byte) => byte.toString(16).padStart(2, "0")).join("");
    if (actual !== String(expected).toLowerCase()) {
      throw new Error(`${label}: SHA-256 mismatch (expected ${expected}, got ${actual})`);
    }
    return buffer;
  }

  // --- feature detection and switches ---------------------------------------------------------

  function detectFeatures() {
    const W = global.WebAssembly;
    const features = {
      wasm: !!(W && typeof W.compile === "function"),
      simd: false,
      workers: typeof global.Worker === "function",
      crossOriginIsolated: global.crossOriginIsolated === true,
      sharedArrayBuffer: typeof global.SharedArrayBuffer === "function",
      sharedMemory: false,
      compileStreaming: !!(W && typeof W.compileStreaming === "function"),
    };
    if (features.wasm && typeof W.validate === "function") {
      try {
        features.simd = W.validate(new Uint8Array(SIMD_PROBE));
      } catch (_) {
        features.simd = false;
      }
    }
    features.sharedMemory = features.crossOriginIsolated && features.sharedArrayBuffer && features.wasm;
    return features;
  }

  function readSwitches(search, storage) {
    const switches = {enabled: true, off: new Set(), simd: "auto", size: null, budgetBytes: 0};
    let stored = null;
    try {
      const raw = storage && storage.getItem(SETTINGS_KEY);
      stored = raw ? JSON.parse(raw) : null;
    } catch (_) {
      stored = null;
    }
    if (stored && typeof stored === "object") {
      if (stored.enabled === false) switches.enabled = false;
      if (Array.isArray(stored.off)) stored.off.forEach((name) => switches.off.add(String(name)));
      if (stored.simd === "off" || stored.simd === "force") switches.simd = stored.simd;
      if (finite(stored.workers) && stored.workers >= 1) switches.size = Math.floor(stored.workers);
      if (finite(stored.budgetMB) && stored.budgetMB > 0) switches.budgetBytes = stored.budgetMB * 1024 * 1024;
    }
    let params = null;
    try {
      params = new URLSearchParams(search || "");
    } catch (_) {
      params = null;
    }
    if (params) {
      const all = params.get("gaiusKernels");
      if (all === "0" || all === "off" || all === "false") switches.enabled = false;
      else if (all === "1" || all === "on" || all === "true") switches.enabled = true;
      const off = params.get("gaiusKernelsOff");
      if (off) off.split(",").forEach((name) => { if (name.trim()) switches.off.add(name.trim()); });
      const simd = params.get("gaiusKernelSimd");
      if (simd === "0" || simd === "off") switches.simd = "off";
      else if (simd === "1" || simd === "force") switches.simd = "force";
      const workers = Number(params.get("gaiusKernelWorkers"));
      if (finite(workers) && workers >= 1) switches.size = Math.floor(workers);
      const budget = Number(params.get("gaiusKernelBudgetMB"));
      if (finite(budget) && budget > 0) switches.budgetBytes = budget * 1024 * 1024;
    }
    // The memory tier is resolved before the kernel runtime is created.  Apply the low tier
    // before any worker/wasm instance is allocated; shrinking after startup cannot reclaim a
    // WebAssembly memory that has already grown.
    const tier = global.__gaiusMemoryTier;
    if (tier && tier.lowMemory && switches.size === null) switches.size = 1;
    if (tier && tier.lowMemory && switches.budgetBytes === 0) switches.budgetBytes = 96 * 1024 * 1024;
    return switches;
  }

  // --- queue ------------------------------------------------------------------------------------

  // Binary heap ordered by (score, seq) with lazy deletion, like kernel-pool.js.
  class JobHeap {
    constructor() {
      this.items = [];
      this.dead = 0;
    }

    static less(a, b) {
      return a.score < b.score || (a.score === b.score && a.seq < b.seq);
    }

    push(entry) {
      const items = this.items;
      items.push(entry);
      let i = items.length - 1;
      while (i > 0) {
        const parent = (i - 1) >> 1;
        if (!JobHeap.less(items[i], items[parent])) break;
        const swap = items[i];
        items[i] = items[parent];
        items[parent] = swap;
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
        const swap = items[i];
        items[i] = items[best];
        items[best] = swap;
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

    peekLive() {
      while (this.items.length > 0) {
        const top = this.items[0];
        if (top.live) return top;
        this.pop();
        this.dead--;
      }
      return null;
    }

    liveJobs() {
      const jobs = [];
      for (const entry of this.items) if (entry.live) jobs.push(entry.job);
      return jobs;
    }

    clear() {
      this.items = [];
      this.dead = 0;
    }
  }

  // --- runtime ----------------------------------------------------------------------------------

  class KernelRuntime {
    constructor(options, features, switches) {
      const P = policy();
      this.P = P;
      this.options = options;
      this.name = options.name;
      this.features = features;
      this.switches = switches;
      this.enabled = switches.enabled && features.wasm && features.workers;
      this.sharedMemory = features.sharedMemory && options.sharedMemory !== "off";
      const nav = global.navigator || {};
      this.plan = P.initialPlan({
        hardwareConcurrency: nav.hardwareConcurrency,
        deviceMemory: nav.deviceMemory,
        mobile: options.mobile === true || (options.mobile !== false && P.isMobileAgent(nav)),
        reserve: options.reserve,
        maxSize: options.maxSize,
        budgetBytes: switches.budgetBytes || options.budgetBytes,
      });
      if (switches.size !== null) {
        this.plan.initial = Math.min(switches.size, options.maxSize);
        this.plan.ceiling = this.plan.initial;
      } else if (options.size !== "auto" && finite(Number(options.size))) {
        this.plan.initial = Math.max(1, Math.min(options.maxSize, Math.floor(Number(options.size))));
        this.plan.ceiling = this.plan.initial;
      }
      this.sizer = new P.PoolSizer(this.plan, options.sizer);
      this.budget = new P.MemoryBudget(this.plan.budgetBytes);
      this.predictor = new P.MotionPredictor(options.motion);
      this.lastRescoreEpoch = this.predictor.epoch;
      this.lastRescoreAt = 0;
      this.rescoreScheduled = false;

      this.kernels = new Map();
      this.kindToKernel = new Map();
      for (const name of Object.keys(options.kernels || {})) this.defineKernel(name, options.kernels[name]);

      this.workerUrl = null;
      this.ownsWorkerUrl = false;
      if (options.workerUrl) {
        this.workerUrl = String(options.workerUrl);
      } else if (typeof options.workerSource === "string" && options.workerSource.length > 0) {
        this.workerUrl = global.URL.createObjectURL(new global.Blob([options.workerSource], {type: "text/javascript"}));
        this.ownsWorkerUrl = true;
      }
      this.heap = new JobHeap();
      this.queued = 0;
      this.byKey = new Map();
      this.slots = [];
      this.ports = new Set();
      this.portDetach = new Map();   // port -> detach(): cancels that client's keyed jobs
      this.nextPortId = 0;
      // promise -> {waiters}: a deduplicated job's result goes to several port listeners; all but
      // the last get a copy, since a transferred ArrayBuffer cannot be posted again.
      this.portResultWaiters = new WeakMap();
      this.nextJobId = 1;
      this.nextSlotId = 1;
      this.seq = 0;
      this.crashStreak = 0;
      this.lastCrash = "";
      this.halted = null;
      this.terminated = false;
      this.pumpScheduled = false;
      this.respawnTimer = null;
      this.retiredBusyMs = 0;
      this.externalPressure = 0;
      this.externalPressureAt = 0;
      this.lastTrimAt = -Infinity;
      this.primeKinds = new Set(Array.isArray(options.primeKinds) ? options.primeKinds.map(String) : []);
      this.stats = {
        submitted: 0, completed: 0, failed: 0, cancelled: 0, superseded: 0, deduped: 0, stale: 0,
        dropped: 0, retried: 0, crashes: 0, spawned: 0, respawns: 0, retired: 0, backpressure: 0,
        loads: 0, unloads: 0, rescores: 0, primed: 0, primeErrors: 0, trims: 0,
      };
      this.classStats = [];
      for (let i = 0; i < P.CLASS_COUNT; i++) this.classStats.push({completed: 0, totalMs: 0, maxMs: 0});
      this.timers = [];
      if (this.enabled && this.workerUrl) {
        this.reconcile(false);
        this.timers.push(global.setInterval(() => this.tick(), options.sizingIntervalMs));
      } else if (this.enabled) {
        this.enabled = false;
        this.halted = new KernelRuntimeError("runtime-unavailable", "kernel runtime has no worker script");
      }
    }

    defineKernel(name, spec) {
      const kernel = {
        name, spec: spec || {}, variants: (spec && spec.variants) || {}, kinds: new Set(),
        module: null, compiling: null, variant: null, bytes: null, importsMemory: false,
        disabled: this.switches.off.has(name) ? "disabled-by-switch" : null,
        trapStreak: 0, loadFailures: 0, parked: [], stats: {completed: 0, failed: 0, totalExecMs: 0, maxExecMs: 0},
        compileMs: 0, compileError: null, primer: null,
      };
      const kinds = spec && Array.isArray(spec.kinds) ? spec.kinds : [];
      for (const kind of kinds) {
        kernel.kinds.add(String(kind));
        this.kindToKernel.set(String(kind), name);
      }
      this.kernels.set(name, kernel);
      return kernel;
    }

    available(name) {
      if (!this.enabled || this.terminated || this.halted) return false;
      if (name === undefined) return [...this.kernels.keys()].some((kernelName) => this.available(kernelName));
      const kernel = this.kernels.get(name);
      return !!kernel && !kernel.disabled && !!global.GaiusKernelMirror
        && global.GaiusKernelMirror.enabled(name, this.options.profileId);
    }

    // --- compilation ------------------------------------------------------------------------

    variantOrder(kernel) {
      const order = [];
      const simd = this.switches.simd !== "auto" ? this.switches.simd : this.options.simd;
      if (kernel.variants.simd && simd !== "off" && (this.features.simd || simd === "force")) order.push("simd");
      if (kernel.variants.baseline && simd !== "force") order.push("baseline");
      return order;
    }

    async loadVariant(source) {
      const W = global.WebAssembly;
      if (source.module) {
        if (source.sha256) throw new Error("kernel module bytes are unavailable for SHA-256 verification");
        return {module: source.module, bytes: null};
      }
      if (source.bytes) {
        const bytes = await verifyKernelBytes(source.bytes, source.sha256, "kernel variant");
        return {module: await W.compile(bytes), bytes};
      }
      if (typeof source.base64 === "string") {
        const bytes = await verifyKernelBytes(decodeBase64(source.base64), source.sha256, "kernel variant");
        return {module: await W.compile(bytes), bytes};
      }
      if (typeof source.load === "function") {
        const bytes = await verifyKernelBytes(await source.load(), source.sha256, "kernel variant");
        return {module: await W.compile(bytes), bytes};
      }
      if (source.url) {
        const response = await global.fetch(source.url, {cache: "force-cache"});
        if (!response.ok) throw new Error(`kernel module ${source.url}: HTTP ${response.status}`);
        const type = (response.headers && response.headers.get("Content-Type")) || "";
        // Streaming compilation from a URL is what lets the engine cache the compiled code.
        if (this.features.compileStreaming && type.indexOf("application/wasm") === 0) {
          if (!source.sha256) return {module: await W.compileStreaming(response), bytes: null};
        }
        const bytes = await response.arrayBuffer();
        return {module: await W.compile(await verifyKernelBytes(bytes, source.sha256, `kernel ${source.url}`)), bytes};
      }
      throw new Error("kernel variant has no module, bytes, base64, load or url");
    }

    compile(kernel) {
      if (kernel.module || kernel.compiling) return kernel.compiling || Promise.resolve(kernel.module);
      const started = now();
      kernel.compiling = (async () => {
        const order = this.variantOrder(kernel);
        if (order.length === 0) throw new Error(`kernel ${kernel.name} has no usable build`);
        let lastError = null;
        for (const variant of order) {
          try {
            const loaded = await this.loadVariant(kernel.variants[variant]);
            kernel.module = loaded.module;
            kernel.bytes = loaded.bytes;
            kernel.variant = variant;
            const W = global.WebAssembly;
            for (const entry of W.Module.exports(kernel.module)) {
              if (entry.kind === "function" && entry.name.indexOf("run_") === 0) {
                kernel.kinds.add(entry.name.slice(4));
                if (!this.kindToKernel.has(entry.name.slice(4))) this.kindToKernel.set(entry.name.slice(4), kernel.name);
              }
            }
            kernel.importsMemory = W.Module.imports(kernel.module).some((entry) => entry.kind === "memory");
            return kernel.module;
          } catch (error) {
            lastError = error;
          }
        }
        throw lastError;
      })().then((module) => {
        kernel.compiling = null;
        kernel.compileMs = now() - started;
        const parked = kernel.parked;
        kernel.parked = [];
        for (const job of parked) if (!job.settled) this.enqueue(job);
        this.schedulePump();
        return module;
      }, (error) => {
        kernel.compiling = null;
        kernel.compileError = String(error && error.message || error);
        this.disableKernel(kernel, "compile-failed: " + kernel.compileError);
        throw error;
      });
      kernel.compiling.catch(() => {});
      return kernel.compiling;
    }

    // Compiles the named kernels now (e.g. while the title screen idles) instead of on first use.
    warm(names) {
      const list = names || Array.from(this.kernels.keys());
      return Promise.all(list.map((name) => {
        const kernel = this.kernels.get(name);
        if (!kernel || !this.available(name)) return null;
        return this.compile(kernel).catch(() => null);
      }));
    }

    // --- submission -------------------------------------------------------------------------

    submit(kind, payload, opts) {
      const o = opts || {};
      if (this.terminated) return Promise.reject(new KernelRuntimeError("terminated", `kernel runtime ${this.name} is terminated`));
      const name = o.kernel || this.kindToKernel.get(kind);
      if (!global.GaiusKernelMirror || !global.GaiusKernelMirror.allowed(kind, name, this.options.profileId)) {
        return Promise.reject(new KernelRuntimeError("needs-vanilla", "NEEDS_VANILLA: kernel mirror is disabled", {kind, kernel: name}));
      }
      if (!this.enabled) return Promise.reject(new KernelRuntimeError("runtime-disabled", "kernel runtime is disabled"));
      if (this.halted) return Promise.reject(this.halted);
      const kernel = name ? this.kernels.get(name) : null;
      if (!kernel) return Promise.reject(new KernelRuntimeError("unknown-kind", `no kernel provides ${kind}`, {kind}));
      if (kernel.disabled) {
        return Promise.reject(new KernelRuntimeError("kernel-disabled", `kernel ${kernel.name} is disabled: ${kernel.disabled}`, {kind, kernel: kernel.name}));
      }
      if (kernel.module && !kernel.kinds.has(kind)) {
        return Promise.reject(new KernelRuntimeError("unknown-kind", `kernel ${kernel.name} has no export run_${kind}`, {kind, kernel: kernel.name}));
      }
      const version = finite(o.version) ? o.version : 0;
      const key = o.key == null ? null : kernel.name + "\u0000" + String(o.key);
      if (key !== null) {
        const current = this.byKey.get(key);
        if (current) {
          if (current.kind === kind && current.version === version) {
            this.stats.deduped++;
            return current.promise;
          }
          if (version < current.version) {
            this.stats.stale++;
            return Promise.reject(new KernelRuntimeError("stale", `job ${String(o.key)} v${version} is older than queued v${current.version}`, {kind, key: o.key}));
          }
          this.cancelJob(current, "superseded", `job ${String(o.key)} superseded by v${version}`);
        }
      }
      let buffer;
      try {
        buffer = toArrayBuffer(payload);
      } catch (error) {
        return Promise.reject(error);
      }
      if (this.primeKinds.has(kind)) this.setPrimer(kernel.name, {kind, payload: buffer, version});
      const P = this.P;
      const job = {
        id: this.nextJobId++, seq: this.seq++, kernel: kernel.name, kind, key, userKey: o.key, version,
        payload: buffer, bytes: 0, queueBytes: 0, transfer: o.transfer !== false, retry: o.retry !== false,
        attempts: 0, state: "new", settled: false, slot: null, entry: null,
        visible: o.visible, background: o.background === true, near: o.near,
        priorityClass: o.priorityClass, priority: o.priority, distance: o.distance,
        cx: o.cx, cz: o.cz, cls: 0, score: 0,
        submittedAt: now(), detachSignal: null, promise: null, resolve: null, reject: null,
      };
      job.bytes = buffer.byteLength + (finite(o.resultBytes) ? o.resultBytes : buffer.byteLength);
      // A queued job holds its payload; the result estimate is only charged while it runs, so a
      // long P0 queue does not fill the budget with results nobody has allocated yet.
      job.queueBytes = buffer.byteLength;
      job.cls = P.classify(job, this.predictor);
      if (!this.budget.canQueue(job.bytes, job.cls)) {
        this.stats.backpressure++;
        return Promise.reject(new KernelRuntimeError("backpressure",
          `kernel memory budget is full (${Math.round(this.budget.used() / 1048576)} of ${Math.round(this.budget.limit / 1048576)} MB)`,
          {kind, kernel: kernel.name, priorityClass: job.cls}));
      }
      job.promise = new Promise((resolve, reject) => {
        job.resolve = resolve;
        job.reject = reject;
      });
      this.budget.queue(job.queueBytes);
      if (key !== null) this.byKey.set(key, job);
      if (o.signal) this.watchSignal(job, o.signal);
      this.stats.submitted++;
      if (kernel.module) {
        this.enqueue(job);
        this.schedulePump();
      } else {
        job.state = "parked";
        kernel.parked.push(job);
        this.compile(kernel).catch(() => {});
      }
      return job.promise;
    }

    watchSignal(job, signal) {
      const onAbort = () => this.cancelJob(job, "cancelled", "job aborted");
      if (signal.aborted) return void Promise.resolve().then(onAbort);
      signal.addEventListener("abort", onAbort, {once: true});
      job.detachSignal = () => signal.removeEventListener("abort", onAbort);
    }

    // Gives a result buffer back to a worker for its next result (kernel-worker.js), round robin
    // over the live workers. Returns false when nothing could take it.
    recycle(buffer) {
      const slots = this.activeSlots();
      if (!slots.length || Object.prototype.toString.call(buffer) !== "[object ArrayBuffer]") return false;
      const slot = slots[(this.recycleNext = ((this.recycleNext | 0) + 1) % slots.length)];
      try {
        slot.worker.postMessage({type: "recycle", buffer}, [buffer]);
        this.stats.recycled = (this.stats.recycled | 0) + 1;
        return true;
      } catch (_) {
        return false;
      }
    }

    cancel(key, kernelName) {
      const names = kernelName ? [kernelName] : Array.from(this.kernels.keys());
      let cancelled = false;
      for (const name of names) {
        const job = this.byKey.get(name + "\u0000" + String(key));
        if (job) cancelled = this.cancelJob(job, "cancelled", `job ${String(key)} cancelled`) || cancelled;
      }
      return cancelled;
    }

    cancelJob(job, code, message) {
      if (job.settled) return false;
      const slot = job.state === "inflight" ? job.slot : null;
      this.stats[code === "superseded" ? "superseded" : "cancelled"]++;
      this.settle(job, new KernelRuntimeError(code, message, {kind: job.kind, kernel: job.kernel, key: job.userKey}));
      if (slot && !slot.dead) {
        try {
          slot.worker.postMessage({type: "cancel", id: job.id});
        } catch (_) { /* the worker is going away */ }
      }
      return true;
    }

    enqueue(job) {
      if (job.entry) this.heap.kill(job.entry);
      else this.queued++;
      job.state = "queued";
      job.score = this.P.score(job, this.predictor, now());
      job.entry = {job, score: job.score, seq: job.seq, live: true};
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
      if (job.state === "parked") {
        const kernel = this.kernels.get(job.kernel);
        const index = kernel ? kernel.parked.indexOf(job) : -1;
        if (index >= 0) kernel.parked.splice(index, 1);
      }
      // In-flight bytes are released when the worker answers (finish/crash), queued ones now.
      if (job.state !== "inflight") this.budget.unqueue(job.queueBytes);
      job.settled = true;
      job.state = job.state === "inflight" ? "inflight-settled" : "done";
      job.payload = null;
      if (job.key !== null && this.byKey.get(job.key) === job) this.byKey.delete(job.key);
      if (job.detachSignal) job.detachSignal();
      if (error) job.reject(error);
      else job.resolve(value);
    }

    fail(job, error) {
      this.stats.failed++;
      const kernel = this.kernels.get(job.kernel);
      if (kernel) kernel.stats.failed++;
      this.settle(job, error);
    }

    // --- primers -----------------------------------------------------------------------------

    // primer: {kind, payload (ArrayBuffer or view, kept as a copy), version?} or null to clear.
    // An older version never replaces a newer primer.
    setPrimer(name, primer) {
      const kernel = this.kernels.get(name);
      if (!kernel) return false;
      if (!primer) {
        kernel.primer = null;
        this.budget.setInstance("primer:" + name, 0);
        return true;
      }
      const version = finite(primer.version) ? primer.version : 0;
      if (kernel.primer && version < kernel.primer.version) return false;
      let bytes;
      try {
        bytes = toArrayBuffer(primer.payload).slice(0);
      } catch (_) {
        return false;
      }
      kernel.primer = {kind: String(primer.kind), payload: bytes, version};
      this.budget.setInstance("primer:" + name, bytes.byteLength);
      return true;
    }

    // Posts the kernel's primer to a worker right behind its load message, so it runs before
    // any job of that instance. Its result only updates the memory figures.
    primeSlot(slot, kernel) {
      const primer = kernel.primer;
      if (!primer || slot.dead) return false;
      if (!global.GaiusKernelMirror || !global.GaiusKernelMirror.allowed(primer.kind, kernel.name, this.options.profileId)) return false;
      const id = this.nextJobId++;
      const payload = primer.payload.slice(0);
      try {
        slot.worker.postMessage({type: "job", id, kernel: kernel.name, kind: primer.kind, payload}, [payload]);
      } catch (_) {
        this.stats.primeErrors++;
        return false;
      }
      slot.primers.set(id, kernel.name);
      // The instance holds the primed state before the worker reports its memory.
      const key = slot.id + ":" + kernel.name;
      const known = this.budget.instances.get(key) || 0;
      this.budget.setInstance(key, Math.max(known, this.options.instanceEstimateBytes + payload.byteLength));
      return true;
    }

    finishPrimer(slot, message) {
      const name = slot.primers.get(message.id);
      slot.primers.delete(message.id);
      if (message.type === "result") {
        this.stats.primed++;
        if (finite(message.memoryBytes)) {
          const loaded = slot.loaded.get(name);
          if (loaded) loaded.memoryBytes = message.memoryBytes;
          this.budget.setInstance(slot.id + ":" + name, message.memoryBytes);
        }
      } else if (message.type === "error") {
        // The instance answers its first job with "table missing" instead; nothing to undo.
        this.stats.primeErrors++;
      }
    }

    // --- memory pressure --------------------------------------------------------------------

    // Asks every worker to trim its instances when the heap is under pressure (rate limited).
    maybeTrim(t) {
      if (this.terminated || !this.enabled) return false;
      if (this.heapPressure() < this.options.trimPressure) return false;
      if (t - this.lastTrimAt < this.options.trimIntervalMs) return false;
      this.lastTrimAt = t;
      let posted = 0;
      for (const slot of this.slots) {
        if (slot.dead || slot.loaded.size === 0) continue;
        try {
          slot.worker.postMessage({type: "trim"});
          posted++;
        } catch (_) { /* gone */ }
      }
      if (posted > 0) this.stats.trims++;
      return posted > 0;
    }

    // --- motion and rescoring ---------------------------------------------------------------

    setViewer(viewer) {
      if (!viewer || !this.predictor.update(viewer)) return false;
      if (this.predictor.epoch !== this.lastRescoreEpoch) this.scheduleRescore();
      return true;
    }

    scheduleRescore() {
      if (this.rescoreScheduled || this.terminated) return;
      this.rescoreScheduled = true;
      const wait = Math.max(0, this.options.rescoreMinGapMs - (now() - this.lastRescoreAt));
      global.setTimeout(() => {
        this.rescoreScheduled = false;
        this.rescore();
      }, wait);
    }

    // Recomputes class and score of every queued job (motion changed, or jobs aged) and
    // rebuilds the heap in O(n).
    rescore() {
      if (this.terminated || this.queued === 0) {
        this.lastRescoreEpoch = this.predictor.epoch;
        return;
      }
      const t = now();
      const jobs = this.heap.liveJobs();
      this.heap.clear();
      for (const job of jobs) {
        // Only generation work changes class with the player's position.
        if (job.priorityClass === undefined && this.P.kernelRole(job.kernel) === "gen" && !job.background) {
          job.cls = this.P.classify(job, this.predictor);
        }
        job.score = this.P.score(job, this.predictor, t);
        job.entry = {job, score: job.score, seq: job.seq, live: true};
        this.heap.items.push(job.entry);
      }
      for (let i = (this.heap.items.length >> 1) - 1; i >= 0; i--) this.heap.siftDown(i);
      this.lastRescoreEpoch = this.predictor.epoch;
      this.lastRescoreAt = t;
      this.stats.rescores++;
      this.schedulePump();
    }

    // --- dispatch ---------------------------------------------------------------------------

    schedulePump() {
      if (this.pumpScheduled || this.terminated) return;
      this.pumpScheduled = true;
      // A microtask, so a burst of synchronous submits is ordered by priority before any runs.
      Promise.resolve().then(() => this.pump());
    }

    runningCounts() {
      let background = 0;
      let lowest = 0;
      for (const slot of this.slots) {
        for (const job of slot.inflight.values()) {
          if (job.cls >= this.P.PRIORITY.FAR_GEN) background++;
          if (job.cls === this.P.PRIORITY.BACKGROUND) lowest++;
        }
      }
      return {background, lowest};
    }

    pump() {
      this.pumpScheduled = false;
      if (this.terminated || !this.enabled) return;
      const P = this.P;
      const active = this.activeSlots().length;
      // Far and background work never takes every worker: one stays free for what the player sees.
      const backgroundCap = active <= 1 ? 1 : active - 1;
      let counts = this.runningCounts();
      while (this.queued > 0) {
        const entry = this.heap.peekLive();
        if (!entry) return;
        const job = entry.job;
        if (job.cls >= P.PRIORITY.FAR_GEN && counts.background >= backgroundCap) return;
        if (job.cls === P.PRIORITY.BACKGROUND && counts.lowest >= 1) return;
        if (!this.budget.canDispatch(job.bytes, job.cls)) return;
        const slot = this.pickSlot(job);
        if (!slot) return;
        this.heap.pop();
        entry.live = false;
        job.entry = null;
        this.queued--;
        this.dispatch(slot, job);
        if (job.cls >= P.PRIORITY.FAR_GEN) counts.background++;
        if (job.cls === P.PRIORITY.BACKGROUND) counts.lowest++;
      }
    }

    pickSlot(job) {
      const P = this.P;
      const limit = job.cls <= P.PRIORITY.NEAR_GEN ? 2 : 1;
      let anyLoaded = false;
      for (const slot of this.slots) {
        if (!slot.dead && slot.loaded.has(job.kernel)) anyLoaded = true;
      }
      let best = null;
      let bestRank = Infinity;
      for (const slot of this.slots) {
        if (!slot.ready || slot.dead || slot.draining) continue;
        const n = slot.inflight.size;
        if (n >= limit) continue;
        // Never park a job behind far or background work already queued in that worker.
        if (n > 0) {
          let blocked = false;
          for (const other of slot.inflight.values()) if (other.cls > P.PRIORITY.NEAR_GEN) blocked = true;
          if (blocked) continue;
        }
        const loaded = slot.loaded.get(job.kernel);
        let rank = n * 10;
        if (!loaded) {
          // A new instance must fit the budget, unless no worker hosts this kernel yet or
          // nothing is running (the queue must always drain).
          if (anyLoaded && !this.canLoadInstance() && this.budget.inflightCount > 0) continue;
          rank += 3;
        } else if (loaded.state !== "ready") {
          rank += 1;
        }
        if (rank < bestRank) {
          best = slot;
          bestRank = rank;
        }
      }
      return best;
    }

    canLoadInstance() {
      const b = this.budget;
      return b.instanceBytes + b.inflightBytes + this.options.instanceEstimateBytes <= b.limit;
    }

    // skipPrimer: the job that triggers the load is itself a primer job.
    loadKernel(slot, kernel, skipPrimer) {
      const generation = (slot.generations.get(kernel.name) || 0) + 1;
      slot.generations.set(kernel.name, generation);
      const message = {type: "load", kernel: kernel.name, generation};
      const transfer = [];
      if (kernel.importsMemory) {
        const memorySpec = kernel.spec.memory || {};
        try {
          message.memory = new global.WebAssembly.Memory({
            initial: memorySpec.initial || 17,
            maximum: memorySpec.maximum || 16384,
            shared: this.sharedMemory,
          });
        } catch (_) {
          message.memory = null;
        }
      }
      if (kernel.cloneModule !== false) {
        try {
          slot.worker.postMessage(Object.assign({module: kernel.module}, message), transfer);
          slot.loaded.set(kernel.name, {state: "loading", generation, memoryBytes: 0, lastUsed: now(), memory: message.memory || null});
          this.budget.setInstance(slot.id + ":" + kernel.name, this.options.instanceEstimateBytes);
          this.stats.loads++;
          if (!skipPrimer) this.primeSlot(slot, kernel);
          return true;
        } catch (error) {
          // Engines that cannot clone a WebAssembly.Module into a Worker get the bytes instead.
          kernel.cloneModule = false;
          if (!kernel.bytes && !kernel.variants[kernel.variant]) throw error;
        }
      }
      return false;
    }

    async loadKernelBytes(slot, kernel, skipPrimer, generation) {
      if (!kernel.bytes) {
        const source = kernel.variants[kernel.variant];
        if (source.url) {
          const response = await global.fetch(source.url, {cache: "force-cache"});
          if (!response.ok) throw new Error(`kernel module ${source.url}: HTTP ${response.status}`);
          kernel.bytes = await verifyKernelBytes(await response.arrayBuffer(), source.sha256, `kernel ${source.url}`);
        } else if (source.bytes) {
          kernel.bytes = await verifyKernelBytes(source.bytes, source.sha256, `kernel ${kernel.name}`);
        } else if (typeof source.base64 === "string") {
          kernel.bytes = await verifyKernelBytes(decodeBase64(source.base64), source.sha256, `kernel ${kernel.name}`);
        } else if (typeof source.load === "function") {
          kernel.bytes = await verifyKernelBytes(await source.load(), source.sha256, `kernel ${kernel.name}`);
        }
      }
      if (slot.dead) return;
      generation = Number(generation) || (slot.generations.get(kernel.name) || 0) + 1;
      const loading = slot.loaded.get(kernel.name);
      if (!loading || loading.generation !== generation) return;
      slot.generations.set(kernel.name, generation);
      const bytes = kernel.bytes.slice(0);
      slot.worker.postMessage({type: "load", kernel: kernel.name, generation, bytes}, [bytes]);
      this.stats.loads++;
      if (!skipPrimer) this.primeSlot(slot, kernel);
    }

    dispatch(slot, job) {
      const kernel = this.kernels.get(job.kernel);
      if (!slot.loaded.has(job.kernel)) {
        const skipPrimer = !!kernel.primer && kernel.primer.kind === job.kind;
        if (!this.loadKernel(slot, kernel, skipPrimer)) {
          // The bytes path may have to fetch first; jobs for this slot wait for the load
          // message so the worker never sees a job before its kernel.
          const generation = (slot.generations.get(kernel.name) || 0) + 1;
          slot.generations.set(kernel.name, generation);
          const loading = {state: "loading", generation, memoryBytes: 0, lastUsed: now(), memory: null, pending: null};
          slot.loaded.set(kernel.name, loading);
          this.budget.setInstance(slot.id + ":" + kernel.name, this.options.instanceEstimateBytes);
          loading.pending = this.loadKernelBytes(slot, kernel, skipPrimer, generation).then(() => {
            loading.pending = null;
          }, (error) => {
            this.crash(slot, new KernelRuntimeError("worker-load-failed", String(error && error.message || error)));
            throw error;
          });
        }
      }
      const loaded = slot.loaded.get(job.kernel);
      if (loaded) loaded.lastUsed = now();
      const buffer = job.payload;
      job.state = "inflight";
      job.slot = slot;
      job.attempts++;
      job.dispatchedAt = now();
      if (job.transfer) {
        const keepCopy = job.retry && job.attempts < this.options.maxAttempts
          && buffer.byteLength <= this.options.retryCopyLimit;
        job.payload = keepCopy ? buffer.slice(0) : null;
      }
      this.budget.unqueue(job.queueBytes);
      this.budget.begin(job.bytes);
      if (slot.inflight.size === 0) slot.busySince = now();
      slot.inflight.set(job.id, job);
      const post = () => {
        if (slot.dead) return;
        if (job.settled) {
          // Cancelled while its kernel was still loading: never reaches the worker.
          this.releaseInflight(slot, job);
          this.schedulePump();
          return;
        }
        try {
          slot.worker.postMessage({type: "job", id: job.id, kernel: job.kernel, kind: job.kind, payload: buffer},
            job.transfer ? [buffer] : []);
        } catch (error) {
          this.releaseInflight(slot, job);
          this.fail(job, new KernelRuntimeError("post-failed", String(error && error.message || error), {kind: job.kind, kernel: job.kernel}));
          return;
        }
        if (slot.watchdog === null) this.armWatchdog(slot);
      };
      if (loaded && loaded.pending) loaded.pending.then(post, () => {});
      else post();
    }

    releaseInflight(slot, job) {
      if (!slot.inflight.delete(job.id)) return;
      this.budget.end(job.bytes);
      if (slot.inflight.size === 0 && finite(slot.busySince)) {
        slot.busyMs += now() - slot.busySince;
        slot.busySince = NaN;
      }
    }

    armWatchdog(slot) {
      if (slot.watchdog !== null) global.clearTimeout(slot.watchdog);
      slot.watchdog = null;
      const timeout = this.options.jobTimeoutMs;
      if (!(timeout > 0) || slot.dead || slot.inflight.size === 0) return;
      slot.watchdog = global.setTimeout(() => {
        slot.watchdog = null;
        this.crash(slot, new KernelRuntimeError("worker-hung", `kernel worker ${slot.id} silent for ${timeout} ms`));
      }, timeout);
    }

    // --- worker lifecycle -------------------------------------------------------------------

    activeSlots() {
      return this.slots.filter((slot) => !slot.dead && !slot.draining);
    }

    spawn() {
      const slot = {
        id: this.nextSlotId++, worker: null, ready: false, draining: false, dead: false,
        loaded: new Map(), generations: new Map(), inflight: new Map(), primers: new Map(), completed: 0, watchdog: null,
        busySince: NaN, busyMs: 0,
      };
      slot.worker = new global.Worker(this.workerUrl, {name: `${this.name}-${slot.id}`});
      slot.worker.onmessage = (event) => this.onMessage(slot, event.data);
      slot.worker.onerror = (event) => {
        if (event && event.preventDefault) event.preventDefault();
        this.crash(slot, new KernelRuntimeError("worker-crashed",
          `kernel worker ${slot.id} failed: ${event && event.message || "uncaught error"}`));
      };
      slot.worker.onmessageerror = () => {
        this.crash(slot, new KernelRuntimeError("worker-crashed", `kernel worker ${slot.id} sent an unreadable message`));
      };
      // Messages posted before the worker script runs wait in its queue, so it accepts
      // load and job messages right away.
      slot.ready = true;
      this.slots.push(slot);
      this.stats.spawned++;
      return slot;
    }

    onMessage(slot, message) {
      if (slot.dead || this.terminated || !message) return;
      switch (message.type) {
        case "loaded": {
          const kernel = this.kernels.get(message.kernel);
          const loaded = slot.loaded.get(message.kernel);
          if (loaded && Number(message.generation) !== loaded.generation) return;
          if (loaded) {
            loaded.state = "ready";
            loaded.memoryBytes = message.memoryBytes >>> 0;
            this.budget.setInstance(slot.id + ":" + message.kernel, message.memoryBytes >>> 0);
          }
          if (kernel) kernel.loadFailures = 0;
          this.schedulePump();
          return;
        }
        case "load-error": {
          const loading = slot.loaded.get(message.kernel);
          if (loading && Number(message.generation) !== loading.generation) return;
          slot.loaded.delete(message.kernel);
          this.budget.setInstance(slot.id + ":" + message.kernel, 0);
          const kernel = this.kernels.get(message.kernel);
          if (kernel && ++kernel.loadFailures >= this.options.maxKernelFailures) {
            this.disableKernel(kernel, "load-failed: " + message.message);
          }
          return;
        }
        case "unloaded": {
          const current = slot.loaded.get(message.kernel);
          const kernel = this.kernels.get(message.kernel);
          if (current && Number(message.generation) !== current.generation) return;
          // An idle unload already dropped its entry; an entry still loading here is a newer
          // load sent after that unload (the worker answers them in order), so it stays. A
          // disabled kernel drops everything.
          if (current && (current.state !== "loading" || (kernel && kernel.disabled))) {
            slot.loaded.delete(message.kernel);
            this.budget.setInstance(slot.id + ":" + message.kernel, 0);
          }
          this.schedulePump();
          return;
        }
        case "trimmed": {
          const loaded = slot.loaded.get(message.kernel);
          if (loaded && finite(message.memoryBytes)) {
            loaded.memoryBytes = message.memoryBytes;
            this.budget.setInstance(slot.id + ":" + message.kernel, message.memoryBytes);
          }
          return;
        }
        case "init-error":
          this.crash(slot, new KernelRuntimeError("worker-init-failed", `kernel worker ${slot.id}: ${message.message}`));
          return;
        case "result":
        case "error":
        case "cancelled":
          if (slot.primers.has(message.id)) this.finishPrimer(slot, message);
          else this.finish(slot, message);
          return;
        default:
          return;
      }
    }

    finish(slot, message) {
      const job = slot.inflight.get(message.id);
      if (!job) return;
      this.releaseInflight(slot, job);
      this.armWatchdog(slot);
      const kernel = this.kernels.get(job.kernel);
      if (message.type === "result") {
        slot.completed++;
        if (finite(message.memoryBytes)) {
          const loaded = slot.loaded.get(job.kernel);
          if (loaded) loaded.memoryBytes = message.memoryBytes;
          this.budget.setInstance(slot.id + ":" + job.kernel, message.memoryBytes);
        }
        if (kernel) kernel.trapStreak = 0;
        if (this.crashStreak > 0) {
          this.crashStreak = 0;
          if (this.activeSlots().length < this.sizer.size) this.reconcile(true);
        }
      }
      if (job.settled) {
        if (message.type === "result") this.stats.dropped++;
      } else if (message.type === "result") {
        const latency = now() - job.submittedAt;
        const exec = Number(message.execMs) || 0;
        const classStat = this.classStats[job.cls];
        classStat.completed++;
        classStat.totalMs += latency;
        if (latency > classStat.maxMs) classStat.maxMs = latency;
        if (kernel) {
          kernel.stats.completed++;
          kernel.stats.totalExecMs += exec;
          if (exec > kernel.stats.maxExecMs) kernel.stats.maxExecMs = exec;
        }
        this.stats.completed++;
        this.settle(job, null, message.result);
      } else if (message.type === "error") {
        const code = message.code || "kernel-error";
        if ((code === "kernel-not-loaded" || code === "kernel-unloaded" || code === "kernel-load-failed")
            && job.attempts < this.options.maxAttempts && job.payload && !(kernel && kernel.disabled)) {
          // The instance went away under the job (eviction race or a failed instantiation):
          // run it again elsewhere.
          this.stats.retried++;
          job.slot = null;
          job.state = "new";
          this.budget.queue(job.queueBytes);
          this.enqueue(job);
        } else {
          if (code === "kernel-trap" && kernel && ++kernel.trapStreak >= this.options.maxKernelFailures) {
            this.disableKernel(kernel, "trapped " + kernel.trapStreak + " times: " + message.message);
          }
          this.fail(job, new KernelRuntimeError(code, message.message || "kernel failed",
            {kind: job.kind, kernel: job.kernel, status: message.status}));
        }
      } else {
        this.stats.cancelled++;
        this.settle(job, new KernelRuntimeError("cancelled", "job skipped by its worker", {kind: job.kind, kernel: job.kernel, key: job.userKey}));
      }
      if (slot.draining && slot.inflight.size === 0) this.retire(slot);
      this.schedulePump();
    }

    crash(slot, error) {
      if (slot.dead) return;
      const inflight = Array.from(slot.inflight.values());
      for (const job of inflight) this.releaseInflight(slot, job);
      this.dropSlot(slot);
      if (this.terminated) return;
      this.stats.crashes++;
      this.crashStreak++;
      this.lastCrash = error.message;
      for (const job of inflight) {
        if (job.settled) continue;
        job.slot = null;
        if (job.retry && job.attempts < this.options.maxAttempts && job.payload) {
          this.stats.retried++;
          job.state = "new";
          this.budget.queue(job.queueBytes);
          this.enqueue(job);
        } else {
          this.fail(job, new KernelRuntimeError(error.code, `${error.message} (${job.kind}, attempt ${job.attempts})`,
            {kind: job.kind, kernel: job.kernel, key: job.userKey}));
        }
      }
      if (!slot.draining) this.scheduleRespawn();
      this.schedulePump();
    }

    scheduleRespawn() {
      if (this.crashStreak > this.options.maxRespawnStreak) {
        if (this.activeSlots().length === 0) this.halt(`halted after ${this.crashStreak} worker failures: ${this.lastCrash}`);
        return;
      }
      if (this.respawnTimer !== null) return;
      const delay = this.crashStreak <= 1 ? 0
        : Math.min(2000, this.options.respawnBackoffMs * Math.pow(2, this.crashStreak - 2));
      this.respawnTimer = global.setTimeout(() => {
        this.respawnTimer = null;
        if (!this.terminated) this.reconcile(true);
      }, delay);
    }

    halt(reason) {
      this.halted = new KernelRuntimeError("runtime-unavailable", `kernel runtime ${this.name} ${reason}`);
      this.failAll(this.halted);
      this.broadcastStatus();
    }

    failAll(error) {
      for (const job of this.heap.liveJobs()) this.fail(job, error);
      this.heap.clear();
      this.queued = 0;
      for (const kernel of this.kernels.values()) {
        const parked = kernel.parked;
        kernel.parked = [];
        for (const job of parked) this.fail(job, error);
      }
    }

    disableKernel(kernel, reason) {
      if (kernel.disabled) return;
      kernel.disabled = reason;
      const error = new KernelRuntimeError("kernel-disabled", `kernel ${kernel.name} is disabled: ${reason}`, {kernel: kernel.name});
      for (const job of this.heap.liveJobs()) if (job.kernel === kernel.name) this.fail(job, error);
      const parked = kernel.parked;
      kernel.parked = [];
      for (const job of parked) this.fail(job, error);
      for (const slot of this.slots) {
        const loaded = slot.loaded.get(kernel.name);
        if (!loaded) continue;
        slot.loaded.delete(kernel.name);
        this.budget.setInstance(slot.id + ":" + kernel.name, 0);
        try {
          slot.worker.postMessage({type: "unload", kernel: kernel.name, generation: loaded.generation});
        } catch (_) { /* gone */ }
      }
      this.broadcastStatus();
    }

    dropSlot(slot) {
      slot.dead = true;
      slot.ready = false;
      if (slot.watchdog !== null) global.clearTimeout(slot.watchdog);
      slot.watchdog = null;
      this.retiredBusyMs += slot.busyMs + (finite(slot.busySince) ? now() - slot.busySince : 0);
      slot.busySince = NaN;
      slot.busyMs = 0;
      this.budget.dropInstancesWithPrefix(slot.id + ":");
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

    // Brings the number of accepting workers to the sizer's size: revives draining workers
    // first, then spawns; when shrinking, idle workers go first and busy ones drain.
    reconcile(respawn) {
      if (this.terminated || !this.enabled) return;
      if (!this.available()) return;
      const target = this.sizer.size;
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
        if (this.activeSlots().length === 0) this.halt(`cannot start kernel workers: ${this.lastCrash}`);
      }
      if (active > target) {
        const victims = this.activeSlots().sort((a, b) => a.inflight.size - b.inflight.size);
        for (const slot of victims.slice(0, active - target)) {
          slot.draining = true;
          if (slot.inflight.size === 0) this.retire(slot);
        }
      }
      this.schedulePump();
    }

    setSize(size) {
      const value = Math.max(1, Math.min(this.options.maxSize, Math.floor(Number(size)) || 1));
      this.sizer.size = value;
      this.sizer.max = Math.max(this.sizer.max, value);
      this.reconcile(false);
      return value;
    }

    // Heap pressure reported by the page (e.g. the Java side seeing allocation failures); it
    // counts for 10 s. A level at the trim threshold trims the workers right away.
    reportPressure(level) {
      this.externalPressure = Math.max(0, Math.min(1, Number(level) || 0));
      this.externalPressureAt = now();
      if (this.externalPressure >= this.options.trimPressure) this.maybeTrim(this.externalPressureAt);
    }

    heapPressure() {
      let level = 0;
      const memory = global.performance && global.performance.memory;
      if (memory && memory.jsHeapSizeLimit > 0) level = memory.usedJSHeapSize / memory.jsHeapSizeLimit;
      if (this.externalPressure && now() - this.externalPressureAt < 10000) level = Math.max(level, this.externalPressure);
      return level;
    }

    totalBusyMs() {
      let total = this.retiredBusyMs;
      const t = now();
      for (const slot of this.slots) total += slot.busyMs + (finite(slot.busySince) ? t - slot.busySince : 0);
      return total;
    }

    tick() {
      if (this.terminated || !this.enabled) return;
      const t = now();
      let queuedHigh = 0;
      if (this.queued > 0) {
        for (const job of this.heap.liveJobs()) if (job.cls <= this.P.PRIORITY.NEAR_GEN) queuedHigh++;
      }
      const before = this.sizer.size;
      const decision = this.sizer.sample({
        now: t, completed: this.stats.completed, busyMs: this.totalBusyMs(), queuedHigh, queued: this.queued,
        heapPressure: this.heapPressure(), budgetPressure: this.budget.residentPressure(),
      });
      this.lastSizing = decision;
      if (this.sizer.size !== before) this.reconcile(false);
      this.unloadIdleInstances(t);
      this.maybeTrim(t);
      // Aging and motion both move scores; refresh them at most once per interval.
      if (this.queued > 0 && t - this.lastRescoreAt >= this.options.rescoreIntervalMs) this.rescore();
    }

    unloadIdleInstances(t) {
      const tight = this.budget.residentPressure() > 0.75;
      const idleLimit = tight ? 5000 : this.options.idleUnloadMs;
      for (const slot of this.slots) {
        if (slot.dead) continue;
        for (const [name, loaded] of slot.loaded) {
          if (loaded.state !== "ready" || t - loaded.lastUsed < idleLimit) continue;
          let busy = false;
          for (const job of slot.inflight.values()) if (job.kernel === name) busy = true;
          if (busy) continue;
          // Keep one instance of every kernel unless memory is tight.
          if (!tight) {
            let others = 0;
            for (const other of this.slots) if (other !== slot && !other.dead && other.loaded.has(name)) others++;
            if (others === 0) continue;
          }
          slot.loaded.delete(name);
          this.budget.setInstance(slot.id + ":" + name, 0);
          this.stats.unloads++;
          try {
            slot.worker.postMessage({type: "unload", kernel: name, generation: loaded.generation});
          } catch (_) { /* gone */ }
        }
      }
    }

    terminate() {
      if (this.terminated) return;
      const error = new KernelRuntimeError("terminated", `kernel runtime ${this.name} terminated`);
      for (const job of this.heap.liveJobs()) {
        this.stats.cancelled++;
        this.settle(job, error);
      }
      this.heap.clear();
      this.queued = 0;
      for (const kernel of this.kernels.values()) {
        for (const job of kernel.parked.slice()) this.settle(job, error);
        kernel.parked = [];
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
      for (const timer of this.timers) global.clearInterval(timer);
      this.timers = [];
      if (this.respawnTimer !== null) global.clearTimeout(this.respawnTimer);
      this.respawnTimer = null;
      this.byKey.clear();
      for (const port of this.ports) {
        try {
          port.postMessage({type: "status", status: {enabled: false, kernels: {}}});
          port.close();
        } catch (_) { /* closed */ }
      }
      this.ports.clear();
      this.portDetach.clear();
      if (this.ownsWorkerUrl) global.URL.revokeObjectURL(this.workerUrl);
    }

    // --- remote clients ---------------------------------------------------------------------

    status() {
      const kernels = {};
      for (const [name, kernel] of this.kernels) {
        kernels[name] = {available: this.available(name), variant: kernel.variant, kinds: Array.from(kernel.kinds), disabled: kernel.disabled};
      }
      return {enabled: this.available(), sharedMemory: this.sharedMemory, kernels};
    }

    broadcastStatus() {
      const status = this.status();
      for (const port of this.ports) {
        try {
          port.postMessage({type: "status", status});
        } catch (_) { /* closed */ }
      }
    }

    // Serves GaiusKernelRuntime.connect(port) clients (the integrated server Worker): their jobs
    // join the same queue and budget; results go back over the port as transferables. Job keys
    // are scoped to the port, so a new server Worker (whose job ids and versions restart) never
    // shares or supersedes a job of an old one.
    attachPort(port) {
      if (this.portDetach.has(port)) return;
      const prefix = "p" + (++this.nextPortId) + ":";
      const keys = new Map();   // scoped key -> submits not yet answered
      const maxPending = Math.max(1, Math.floor(Number(this.options.maxPendingRequests) || DEFAULTS.maxPendingRequests));
      const maxWaiters = Math.max(1, Math.floor(Number(this.options.maxPendingWaiters) || maxPending));
      let pendingRequests = 0;
      // Count one slot per unique runtime promise. Duplicate port messages share that slot;
      // their individual callbacks are bounded by maxWaiters below.
      const portWaiters = new Map();
      const release = (key) => {
        if (key === null) return;
        const left = (keys.get(key) || 1) - 1;
        if (left > 0) keys.set(key, left);
        else keys.delete(key);
      };
      this.ports.add(port);
      const post = (message, transfer) => {
        try {
          port.postMessage(message, transfer || []);
          return true;
        } catch (_) {
          return false;   // the client went away
        }
      };
      port.onmessage = (event) => {
        const message = event.data;
        if (!message || this.terminated || !this.portDetach.has(port)) return;
        if (message.type === "submit") {
          const opts = Object.assign({}, message.opts || {}, {kernel: message.kernel});
          const key = opts.key == null ? null : prefix + String(opts.key);
          const version = finite(opts.version) ? opts.version : 0;
          // A repeated request for the same key/kind/version shares the runtime promise.
          // Keep that dedupe path available even while the port is at its backpressure cap.
          const current = key === null ? null : this.byKey.get(String(message.kernel || "") + "\u0000" + key);
          const duplicate = !!(current && current.kind === message.kind && current.version === version);
          const currentPromise = duplicate ? current.promise : null;
          const currentShare = currentPromise ? (portWaiters.get(currentPromise) || null) : null;
          if (duplicate && currentShare && currentShare.waiters >= maxWaiters) {
            post({type: "error", id: message.id, code: "request-cap",
              message: `kernel waiter limit reached (${maxWaiters})`});
            return;
          }
          if (pendingRequests >= maxPending && (!duplicate || !currentShare)) {
            post({type: "error", id: message.id, code: "request-cap",
              message: `kernel request limit reached (${maxPending})`});
            return;
          }
          if (key !== null) {
            opts.key = key;
            keys.set(key, (keys.get(key) || 0) + 1);
          }
          const promise = this.submit(message.kind, message.payload, opts);
          let portShare = portWaiters.get(promise);
          if (!portShare) {
            portShare = {waiters: 0};
            portWaiters.set(promise, portShare);
            pendingRequests++;
          }
          portShare.waiters++;
          const share = this.portResultWaiters.get(promise) || {waiters: 0};
          share.waiters++;
          this.portResultWaiters.set(promise, share);
          promise.then((result) => {
            if (--portShare.waiters === 0) {
              pendingRequests--;
              portWaiters.delete(promise);
            }
            const last = --share.waiters === 0;
            release(key);
            const value = isArrayBuffer(result) && !last ? result.slice(0) : result;
            if (!post({type: "result", id: message.id, result: value}, isArrayBuffer(value) ? [value] : [])) {
              // Answer anyway, so the client fails over now instead of at its own timeout.
              post({type: "error", id: message.id, code: "cancelled", message: "kernel result could not be posted"});
            }
          }, (error) => {
            if (--portShare.waiters === 0) {
              pendingRequests--;
              portWaiters.delete(promise);
            }
            share.waiters--;
            release(key);
            post({type: "error", id: message.id, code: error && error.code || "kernel-error",
              message: String(error && error.message || error)});
          });
        } else if (message.type === "cancel") {
          this.cancel(prefix + String(message.key), message.kernel);
        } else if (message.type === "viewer") {
          this.setViewer(message.viewer);
        } else if (message.type === "pressure") {
          this.reportPressure(message.level);
        } else if (message.type === "status") {
          post({type: "status", status: this.status()});
        } else if (message.type === "close") {
          this.detachPort(port);
        }
      };
      this.portDetach.set(port, () => {
        this.ports.delete(port);
        // The client is gone: its keyed jobs would only occupy workers.
        for (const key of Array.from(keys.keys())) this.cancel(key);
        keys.clear();
      });
      if (typeof port.start === "function") port.start();
      post({type: "status", status: this.status()});
    }

    // Stops serving a port (its client closed, or a new server Worker replaced it) and cancels
    // that client's queued and running keyed jobs.
    detachPort(port) {
      const detach = this.portDetach.get(port);
      if (!detach) return false;
      this.portDetach.delete(port);
      detach();
      return true;
    }

    // A pool-shaped view of one kernel, for code written against GaiusKernelPool.
    pool(kernelName) {
      const runtime = this;
      return {
        submit(kind, payload, opts) {
          return runtime.submit(kind, payload, Object.assign({}, opts || {}, {kernel: kernelName}));
        },
        setPrimer(primer) {
          return runtime.setPrimer(kernelName, primer);
        },
        cancel(key) {
          return runtime.cancel(key, kernelName);
        },
        available() {
          return runtime.available(kernelName);
        },
        telemetry() {
          return runtime.telemetry();
        },
      };
    }

    // --- telemetry --------------------------------------------------------------------------

    telemetry() {
      const kernels = {};
      for (const [name, kernel] of this.kernels) {
        kernels[name] = {
          variant: kernel.variant, disabled: kernel.disabled, compileMs: kernel.compileMs,
          compileError: kernel.compileError, parked: kernel.parked.length,
          primer: kernel.primer ? {kind: kernel.primer.kind, bytes: kernel.primer.payload.byteLength} : null,
          completed: kernel.stats.completed, failed: kernel.stats.failed,
          meanExecMs: kernel.stats.completed ? kernel.stats.totalExecMs / kernel.stats.completed : 0,
          maxExecMs: kernel.stats.maxExecMs,
        };
      }
      let inFlight = 0;
      for (const slot of this.slots) inFlight += slot.inflight.size;
      return Object.assign({
        name: this.name,
        enabled: this.enabled,
        features: this.features,
        sharedMemory: this.sharedMemory,
        plan: this.plan,
        sizer: this.sizer.snapshot(),
        lastSizing: this.lastSizing || null,
        budget: this.budget.snapshot(),
        motion: this.predictor.snapshot(),
        size: this.activeSlots().length,
        queued: this.queued,
        inFlight,
        halted: this.halted ? this.halted.message : null,
        terminated: this.terminated,
        heapPressure: this.heapPressure(),
        ports: this.ports.size,
        workers: this.slots.map((slot) => ({
          id: slot.id, draining: slot.draining, inFlight: slot.inflight.size, completed: slot.completed,
          loaded: Array.from(slot.loaded.keys()),
        })),
        classes: this.classStats.map((stat) => ({
          completed: stat.completed, meanMs: stat.completed ? stat.totalMs / stat.completed : 0, maxMs: stat.maxMs,
        })),
        kernels,
      }, this.stats);
    }
  }

  // --- remote client --------------------------------------------------------------------------

  // The client side of attachPort, for a realm without its own runtime (the server Worker).
  function connect(port) {
    let nextId = 1;
    const pending = new Map();
    let status = {enabled: false, kernels: {}};
    const listeners = [];
    let markReady = null;
    const ready = new Promise((resolve) => { markReady = resolve; });
    port.onmessage = (event) => {
      const message = event.data;
      if (!message) return;
      if (message.type === "status") {
        status = message.status || status;
        if (markReady) {
          markReady(status);
          markReady = null;
        }
        for (const listener of listeners.slice()) {
          try {
            listener(status);
          } catch (_) { /* a listener must not break the port */ }
        }
        return;
      }
      const entry = pending.get(message.id);
      if (!entry) return;
      pending.delete(message.id);
      if (message.type === "result") entry.resolve(message.result);
      else entry.reject(new KernelRuntimeError(message.code || "kernel-error", message.message || "kernel failed"));
    };
    if (typeof port.start === "function") port.start();
    return {
      // Resolves with the first status the runtime sends (right after attachPort).
      ready,
      submit(kind, payload, opts) {
        const o = opts || {};
        const kernel = o.kernel || null;
        if (!global.GaiusKernelMirror || !global.GaiusKernelMirror.allowed(kind, kernel)) {
          return Promise.reject(new KernelRuntimeError("needs-vanilla", "NEEDS_VANILLA: kernel mirror is disabled", {kind, kernel}));
        }
        if (!status.enabled || (kernel && !(status.kernels[kernel] && status.kernels[kernel].available))) {
          return Promise.reject(new KernelRuntimeError("runtime-disabled", "kernel runtime is not available"));
        }
        let buffer;
        try {
          buffer = toArrayBuffer(payload);
        } catch (error) {
          return Promise.reject(error);
        }
        const id = nextId++;
        const plain = {};
        for (const name of ["key", "version", "visible", "background", "near", "priorityClass", "priority",
          "distance", "cx", "cz", "resultBytes", "retry"]) {
          if (o[name] !== undefined) plain[name] = o[name];
        }
        return new Promise((resolve, reject) => {
          pending.set(id, {resolve, reject});
          try {
            port.postMessage({type: "submit", id, kernel, kind, payload: buffer, opts: plain},
              o.transfer === false ? [] : [buffer]);
          } catch (error) {
            pending.delete(id);
            reject(error);
          }
        });
      },
      cancel(key, kernel) {
        port.postMessage({type: "cancel", key, kernel: kernel || null});
      },
      setViewer(viewer) {
        port.postMessage({type: "viewer", viewer});
      },
      reportPressure(level) {
        port.postMessage({type: "pressure", level});
      },
      available(kernel) {
        if (!status.enabled) return false;
        const names = kernel === undefined ? Object.keys(status.kernels) : [kernel];
        return names.some((name) => !!(status.kernels[name] && status.kernels[name].available)
          && !!global.GaiusKernelMirror && global.GaiusKernelMirror.enabled(name));
      },
      status() {
        return status;
      },
      onStatus(listener) {
        listeners.push(listener);
      },
      close() {
        try {
          port.postMessage({type: "close"});
          port.close();
        } catch (_) { /* closed */ }
        for (const entry of pending.values()) entry.reject(new KernelRuntimeError("terminated", "kernel client closed"));
        pending.clear();
        status = {enabled: false, kernels: {}};
      },
    };
  }

  function create(options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const features = opts.features || detectFeatures();
    let storage = null;
    try {
      storage = global.localStorage || null;
    } catch (_) {
      storage = null;
    }
    const search = opts.search !== undefined ? opts.search : (global.location ? global.location.search : "");
    const switches = opts.switches || readSwitches(search, storage);
    return new KernelRuntime(opts, features, switches);
  }

  global.GaiusKernelRuntime = {
    create, connect, detectFeatures, readSwitches, KernelRuntimeError, DEFAULTS, SIMD_PROBE, SETTINGS_KEY,
  };
})(typeof globalThis !== "undefined" ? globalThis : self);
