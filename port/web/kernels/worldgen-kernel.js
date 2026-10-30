// Gaius worldgen kernel facade for the integrated server worker. The Java side
// (dev.gaius.browser.kernel.worldgen.BrowserWorldgenKernel) calls these functions through @JSBody; they
// pick the kernel runner, own the generator handles, the job deadlines and the off-switch, and
// turn kernel results into the flat typed arrays the Java installers read.
//
// Plain script, loaded into the server worker after worldgen-job.js (server-worker-bootstrap.js
// imports both when the page hands it the kernel scripts; kernel-policy.js + kernel-runtime.js
// or kernel-pool.js as well when this worker hosts its own kernel workers). It installs
// globalThis.GaiusWorldgenKernel.
//
// Hosts (globalThis.__gaiusWorldgenKernelConfig.host or ?worldgenKernelHost=auto|shared|worker):
//   shared  the page's kernel runtime through globalThis.__gaiusKernelClient
//           (server-worker-bootstrap.js). One worker pool, one memory budget and one priority
//           model for mesh, light and worldgen; jobs carry the chunk position so the runtime's
//           motion prediction runs near generation first.
//   worker  kernel workers owned by this server worker (nested Workers): a GaiusKernelRuntime
//           restricted to the worldgen kernel when the config carries a kernel manifest entry,
//           otherwise a GaiusKernelPool from raw module bytes. Results then never cross the page's
//           main thread. Configured by __gaiusWorldgenKernelConfig:
//             {
//               kernels: {worldgen: {kinds, variants: {simd: {url|bytes}, baseline: {url|bytes}}}},
//               workerUrl | workerSource,             // kernel-worker.js
//               simd, baseline,                        // GaiusKernelPool form: bytes or URLs
//               size: "auto" | number, reserve, budgetMB,
//             }
//   auto    (default) the shared runtime when the page connected one, else the worker host when
//           configured. A page that connected a runtime without the worldgen kernel (switched
//           off there) keeps worldgen on the Java path.
// Without a runner, usable() is false and every chunk stays on the Java path.
//
// Scheduling: every job carries the chunk position and a distance hint derived from the server's
// view of the player (BrowserChunkTaskPriority publishes it as __gaiusChunkPriorityStats), so
// chunks in the player's movement direction run first. The worker host also feeds that position
// to its own runtime's motion predictor.
//
// Dispatched jobs are never dropped: each one settles exactly once, with its result, with an
// error, or with "transient:timeout" when its deadline passes (the Java side then computes the
// chunk itself). drain(ms) stops new jobs and caps every deadline; the server worker calls it
// before the integrated server's final save, which waits for in-flight generation.
//
// 26.2 surface: a terrain job flagged FLAG_KEEP_NOISE keeps its decoded noise chunk (bounded by
// a byte budget) under a token; submitSurfaceStored(token) later runs the kernel surface rules on
// it and reports only the blocks the rules changed.
//
// Ring biomes: submitTerrain / submitSurface / submitSurfaceStored take an optional last argument,
// the global biome ids the neighbouring chunks store around the chunk (Int32Array; empty or
// missing: the kernel computes them). A ring the kernel cannot use ("worldgen-fallback:") is a
// transient refusal: that chunk takes the Java path.
//
// Off-switch: `worldgenKernel=0` (or `off`/`false`/`no`) in the page or worker URL,
// `__gaiusWorldgenKernelConfig.enabled === false`, or the shared runtime's own switches
// (?gaiusKernels=0, ?gaiusKernelsOff=worldgen). A job refused for a transient reason (memory
// budget, cancelled, runtime briefly unavailable, deadline) reports "transient:<message>" and only
// that chunk takes the Java path; any other failure disables its generator, and three of them the
// facade.
(function (global) {
  "use strict";

  const KERNEL = "worldgen";
  const MAX_FAILURES = 3;
  const TERRAIN_RESULT_BYTES = 256 * 1024;
  const BIOMES_RESULT_BYTES = 8 * 1024;
  const FLAG_KEEP_NOISE = 1 << 8;
  const FALLBACK_PREFIX = "worldgen-fallback:";
  const KERNEL_FLAGS_MASK = 0xff;
  const DEFAULT_DEADLINE_MS = 60000;
  const WATCHDOG_INTERVAL_MS = 1000;
  const VIEWER_INTERVAL_MS = 250;
  const MB = 1024 * 1024;
  const TRANSIENT_CODES = new Set([
    "backpressure", "stale", "cancelled", "superseded", "terminated", "runtime-disabled",
    "runtime-unavailable", "kernel-disabled", "pool-unavailable", "unavailable", "timeout",
    "draining", "noise-evicted",
    "needs-vanilla", "request-cap",
  ]);

  const now = () => (global.performance && global.performance.now ? global.performance.now() : Date.now());

  const state = {
    status: "idle",       // idle | starting | ready | failed | disabled  (worker host)
    host: null,           // "runtime" | "pool" once the worker host started
    pool: null,
    runtime: null,
    starting: null,
    variant: null,
    failures: 0,
    reason: "",
    draining: false,
    generators: new Map(),  // key -> {handle, failed}
    pending: new Map(),     // ticket -> job record (until it settles)
    drainWaiters: [],
    settlementQueue: [],
    settlementScheduled: false,
    nextTicket: 1,
    watchdog: null,
    viewerTimer: null,
    store: new Map(),       // token -> {chunk, bytes}  (insertion order = eviction order)
    storeBytes: 0,
    nextToken: 1,
    jobs: 0,
    completed: 0,
    fallbacks: 0,
    transient: 0,
    timeouts: 0,
    hostFallbacks: Object.create(null),
    late: 0,
    viaRuntime: 0,
    viaHost: 0,
    stored: 0,
    evicted: 0,
    surfaces: 0,
    surfaceChanges: 0,
    ringJobs: 0,
    ringFallbacks: 0,
    kindStats: Object.create(null),
  };

  function config() {
    const c = global.__gaiusWorldgenKernelConfig;
    return c && typeof c === "object" ? c : null;
  }

  function urlParam(name) {
    try {
      const search = String(global.location && global.location.search || "");
      if (!search || typeof URLSearchParams !== "function") return null;
      return new URLSearchParams(search).get(name);
    } catch (_) {
      return null;
    }
  }

  function urlDisabled() {
    const value = urlParam("worldgenKernel");
    return value !== null && /^(0|off|false|no)$/i.test(value);
  }

  function switchedOff() {
    const c = config();
    return !global.GaiusKernelMirror || !global.GaiusKernelMirror.enabled(KERNEL)
      || urlDisabled() || (c !== null && c.enabled === false);
  }

  function hostMode() {
    const c = config();
    const value = String(urlParam("worldgenKernelHost") || (c && c.host) || "auto").toLowerCase();
    return value === "shared" || value === "worker" ? value : "auto";
  }

  // The shared kernel runtime client, when it currently serves the worldgen kernel.
  function runtimeClient() {
    const client = global.__gaiusKernelClient;
    if (!client || typeof client.submit !== "function") return null;
    try {
      return typeof client.available !== "function" || client.available(KERNEL) ? client : null;
    } catch (_) {
      return null;
    }
  }

  function hostRuntimeConfigured(c) {
    return !!(c && c.kernels && c.kernels[KERNEL] && (c.workerUrl || c.workerSource)
      && global.GaiusKernelRuntime && global.GaiusKernelPolicy);
  }

  function hostPoolConfigured(c) {
    return !!(c && c.workerSource && global.GaiusKernelPool && (c.simd || c.baseline));
  }

  function hostConfigured() {
    const c = config();
    return hostRuntimeConfigured(c) || hostPoolConfigured(c);
  }

  function hostUsable() {
    if (!hostConfigured() || state.status === "failed" || state.status === "disabled") return false;
    return !(state.runtime && !state.runtime.available(KERNEL));
  }

  // Whether Java should try the kernel for new chunks at all.
  function usable() {
    if (switchedOff() || state.draining || state.failures >= MAX_FAILURES || !global.GaiusWorldgenJob) return false;
    const mode = hostMode();
    if (mode !== "worker" && runtimeClient()) return true;
    if (mode === "shared") return false;
    // auto: a page that connected a runtime decided about worldgen itself.
    if (mode === "auto" && global.__gaiusKernelClient) return false;
    return hostUsable();
  }

  async function bytesOf(source) {
    if (source == null) return null;
    if (typeof source === "string") {
      const response = await global.fetch(source);
      if (!response.ok) throw new Error("worldgen kernel fetch " + source + ": " + response.status);
      return await response.arrayBuffer();
    }
    return source;
  }

  function stopHost() {
    if (state.pool) {
      try {
        state.pool.terminate();
      } catch (_) {
        // ignore
      }
      state.pool = null;
    }
    if (state.runtime) {
      try {
        state.runtime.terminate();
      } catch (_) {
        // ignore
      }
      state.runtime = null;
    }
    if (state.viewerTimer !== null && typeof global.clearInterval === "function") {
      global.clearInterval(state.viewerTimer);
    }
    state.viewerTimer = null;
  }

  function fail(reason) {
    state.failures++;
    state.reason = String(reason);
    if (state.failures >= MAX_FAILURES) {
      state.status = "failed";
      stopHost();
    }
  }

  // Server-side view of the player: BrowserChunkTaskPriority keeps the player chunk and the
  // last chunk-to-chunk movement direction in __gaiusChunkPriorityStats.
  function serverViewer() {
    const stats = global.__gaiusChunkPriorityStats;
    if (!stats || typeof stats.playerChunk !== "string") return null;
    const chunk = stats.playerChunk.split(",");
    const direction = typeof stats.direction === "string" ? stats.direction.split(",") : ["0", "0"];
    const cx = Number(chunk[0]);
    const cz = Number(chunk[1]);
    if (!isFinite(cx) || !isFinite(cz)) return null;
    return {cx, cz, dx: Number(direction[0]) || 0, dz: Number(direction[1]) || 0};
  }

  function viewDistance() {
    const distance = Number(global.__gaiusServerViewDistance);
    return isFinite(distance) && distance > 0 ? distance : 8;
  }

  // Feeds the worker host's runtime with the server's player position (block centre of the
  // player chunk; the predictor derives the velocity from successive samples).
  function startViewerFeed(runtime) {
    if (state.viewerTimer !== null || typeof global.setInterval !== "function") return;
    state.viewerTimer = global.setInterval(() => {
      const viewer = serverViewer();
      if (!viewer || !state.runtime) return;
      try {
        runtime.setViewer({
          x: viewer.cx * 16 + 8, y: 64, z: viewer.cz * 16 + 8, t: now(), viewDistance: viewDistance(),
        });
      } catch (_) {
        // the predictor is a hint only
      }
    }, VIEWER_INTERVAL_MS);
  }

  function hostSize(c) {
    const requested = c && c.size !== undefined ? String(c.size) : urlParam("worldgenKernelWorkers");
    if (requested !== null && /^\d+$/.test(requested)) {
      const n = Number(requested);
      if (n >= 1 && n <= 8) return n;
    }
    if (c.size && c.size !== "auto") return c.size;
    const cores = global.navigator && global.navigator.hardwareConcurrency;
    const memory = global.navigator && global.navigator.deviceMemory;
    // The page, the client, the server worker and the page runtime's workers share the cores.
    let size = Math.max(1, Math.min(3, (cores || 4) - 4));
    if (memory && memory <= 4) size = 1;
    return size;
  }

  // Starts the worker host once; resolves to true when it accepts jobs.
  function startHost() {
    if (state.status === "ready") return Promise.resolve(true);
    if (state.status === "failed" || state.status === "disabled") return Promise.resolve(false);
    if (state.starting) return state.starting;
    const c = config();
    if (!hostRuntimeConfigured(c) && !hostPoolConfigured(c)) return Promise.resolve(false);
    state.status = "starting";
    state.starting = (async () => {
      try {
        if (hostRuntimeConfigured(c)) {
          const kernels = {};
          kernels[KERNEL] = c.kernels[KERNEL];
          const budgetMB = Number(c.budgetMB);
          const runtime = await global.GaiusKernelRuntime.create({
            name: "worldgen-host",
            kernels,
            workerUrl: c.workerUrl,
            workerSource: c.workerSource,
            size: hostSize(c),
            reserve: c.reserve || 0,
            budgetBytes: isFinite(budgetMB) && budgetMB > 0 ? budgetMB * MB : 0,
          });
          if (!runtime.available(KERNEL)) throw new Error("worldgen kernel is not available in the worker host");
          state.runtime = runtime;
          state.host = "runtime";
          startViewerFeed(runtime);
        } else {
          const [simd, baseline] = await Promise.all([bytesOf(c.simd), bytesOf(c.baseline)]);
          const chosen = global.GaiusWorldgenJob.pickModule({simd, baseline});
          if (!chosen.bytes) throw new Error("no worldgen kernel module for this engine");
          state.variant = chosen.name;
          state.pool = await global.GaiusKernelPool.create({
            moduleBytes: chosen.bytes,
            workerSource: c.workerSource,
            name: KERNEL,
            size: hostSize(c),
            reserve: c.reserve || 0,
            maxInFlightPerWorker: 2,
            jobTimeoutMs: 30000,
          });
          state.host = "pool";
        }
        state.status = "ready";
        return true;
      } catch (error) {
        state.status = "failed";
        state.reason = error && error.message ? error.message : String(error);
        stopHost();
        return false;
      } finally {
        state.starting = null;
      }
    })();
    return state.starting;
  }

  // Warms the worker host when it will serve jobs (the shared runtime loads lazily itself).
  function start() {
    if (switchedOff()) return Promise.resolve(false);
    const mode = hostMode();
    if (mode !== "worker" && runtimeClient()) return Promise.resolve(true);
    if (mode === "shared" || (mode === "auto" && global.__gaiusKernelClient)) return Promise.resolve(false);
    return startHost();
  }

  // The runner for the next job: {via, submit(kind, payload, opts)} or null.
  async function runner() {
    const mode = hostMode();
    const client = mode !== "worker" ? runtimeClient() : null;
    if (client) {
      return {
        via: "runtime",
        submit: (kind, payload, opts) => client.submit(kind, payload, Object.assign({kernel: KERNEL}, opts)),
      };
    }
    if (mode === "shared" || (mode === "auto" && global.__gaiusKernelClient)) return null;
    if (!(await startHost())) return null;
    if (state.runtime) {
      const runtime = state.runtime;
      return {
        via: "host",
        submit: (kind, payload, opts) => runtime.submit(kind, payload, Object.assign({kernel: KERNEL}, opts)),
      };
    }
    if (state.pool) {
      const pool = state.pool;
      return {via: "host", submit: (kind, payload, opts) => pool.submit(kind, payload, opts)};
    }
    return null;
  }

  function generatorUsable(key) {
    const g = state.generators.get(key >>> 0);
    return !!g && !g.failed;
  }

  // Registers (or replaces) the IR of a generator slot.
  function registerGenerator(key, irBytes) {
    if (!global.GaiusWorldgenJob) return false;
    const handle = global.GaiusWorldgenJob.generator(key, irBytes);
    state.generators.set(key >>> 0, {handle, failed: false});
    return true;
  }

  function messageOf(error) {
    return error && error.message ? error.message : String(error);
  }

  // A kernel refusal of one chunk's input (a neighbour biome outside the generator's table).
  function isFallback(error) {
    return !!error && typeof error === "object" && typeof error.message === "string"
      && error.message.indexOf(FALLBACK_PREFIX) >= 0;
  }

  function isTransient(error) {
    return !!error && typeof error === "object" && (TRANSIENT_CODES.has(error.code) || isFallback(error));
  }

  function transientError(code, message) {
    const error = new Error(message);
    error.code = code;
    return error;
  }

  function recordHostFallback(stage, reason) {
    stage = String(stage || "unknown");
    reason = String(reason || "unknown");
    const byStage = state.hostFallbacks[stage] || (state.hostFallbacks[stage] = Object.create(null));
    byStage[reason] = (byStage[reason] || 0) + 1;
  }

  function generatorFailed(key, error) {
    const g = state.generators.get(key >>> 0);
    if (g) g.failed = true;
    state.fallbacks++;
    fail(messageOf(error));
  }

  // ---- deadlines ----

  function deadlineMs() {
    const c = config();
    const value = c && c.jobDeadlineMs != null ? Number(c.jobDeadlineMs) : NaN;
    return isFinite(value) && value > 0 ? value : DEFAULT_DEADLINE_MS;
  }

  function ensureWatchdog() {
    if (state.watchdog !== null || typeof global.setInterval !== "function") return;
    state.watchdog = global.setInterval(checkDeadlines, WATCHDOG_INTERVAL_MS);
  }

  function stopWatchdogWhenIdle() {
    if (state.pending.size === 0 && state.watchdog !== null && typeof global.clearInterval === "function") {
      global.clearInterval(state.watchdog);
      state.watchdog = null;
    }
  }

  function checkDeadlines() {
    const t = now();
    const expired = [];
    state.pending.forEach((job) => {
      if (job.deadline <= t) expired.push(job);
    });
    for (let i = 0; i < expired.length; i++) {
      state.timeouts++;
      settle(expired[i], false, transientError("timeout", "worldgen kernel job passed its deadline"));
    }
    stopWatchdogWhenIdle();
  }

  // Settles a job exactly once; a late result after a deadline is dropped.
  function settle(job, ok, value) {
    if (job.settled) {
      if (ok) state.late++;
      return;
    }
    job.settled = true;
    state.pending.delete(job.ticket);
    stopWatchdogWhenIdle();
    const invoke = () => {
      if (ok) {
        state.completed++;
        const completedStat = state.kindStats[job.kind];
        if (completedStat) completedStat.completed++;
        job.onResult(value);
      } else if (isTransient(value)) {
        state.transient++;
        const transientStat = state.kindStats[job.kind];
        if (transientStat) {
          transientStat.transient++;
          if (value && value.code === "timeout") transientStat.timeout++;
        }
        if (isFallback(value)) {
          state.ringFallbacks++;
          if (transientStat) transientStat.fallback++;
        }
        job.onError("transient:" + messageOf(value));
      } else {
        generatorFailed(job.key, value);
        job.onError(messageOf(value));
      }
    };
    state.settlementQueue.push(invoke);
    if (!state.settlementScheduled) {
      state.settlementScheduled = true;
      const flush = () => {
        state.settlementScheduled = false;
        const queue = state.settlementQueue.splice(0);
        for (let i = 0; i < queue.length; i++) queue[i]();
        if (state.pending.size === 0 && state.settlementQueue.length === 0) {
          const waiters = state.drainWaiters.splice(0);
          for (let i = 0; i < waiters.length; i++) waiters[i](true);
        }
        if (state.settlementQueue.length > 0 && !state.settlementScheduled) {
          state.settlementScheduled = true;
          queueMicrotask(flush);
        }
      };
      queueMicrotask(flush);
    }
  }

  // Stops new jobs and caps the deadline of every pending one at `ms` from now. Resolves true
  // once nothing is pending (each job then settled with its result or a transient error, so the
  // Java side has completed or recomputed every chunk future).
  function drain(ms) {
    state.draining = true;
    const limit = now() + (isFinite(ms) && ms >= 0 ? ms : 10000);
    state.pending.forEach((job) => {
      if (job.deadline > limit) job.deadline = limit;
    });
    if (state.pending.size === 0) return Promise.resolve(true);
    ensureWatchdog();
    return new Promise((resolve) => state.drainWaiters.push(resolve));
  }

  // Accepts jobs again after drain (a new world in the same worker).
  function resume() {
    state.draining = false;
  }

  // ---- scheduling hints ----

  // Forward-biased chunk distance from the server's player chunk: chunks along the movement
  // direction rank as if up to three chunks closer.
  function chunkDistance(chunkX, chunkZ) {
    const viewer = serverViewer();
    if (!viewer) return null;
    const ox = chunkX - viewer.cx;
    const oz = chunkZ - viewer.cz;
    const distance = Math.sqrt(ox * ox + oz * oz);
    const forward = (ox * viewer.dx + oz * viewer.dz) / (Math.sqrt(viewer.dx * viewer.dx + viewer.dz * viewer.dz) || 1);
    return Math.max(0, distance - Math.max(-3, Math.min(3, forward * 0.5)));
  }

  function jobOptions(via, chunkX, chunkZ, resultBytes) {
    const distance = chunkDistance(chunkX, chunkZ);
    const opts = {cx: chunkX, cz: chunkZ, resultBytes};
    if (distance !== null) {
      opts.distance = distance;
      opts.near = distance <= Math.max(4, viewDistance() / 2);
    }
    if (via === "host" && state.pool) {
      // GaiusKernelPool: lowest priority first.
      opts.priority = distance === null ? 0 : Math.round(distance * 16);
    } else {
      opts.priority = 0;
    }
    return opts;
  }

  // ---- job submission ----

  function declineMirror(onError) {
    if (global.GaiusKernelMirror && global.GaiusKernelMirror.enabled(KERNEL)) return false;
    state.transient++;
    onError("transient:needs-vanilla:NEEDS_VANILLA: kernel mirror is disabled");
    return true;
  }

  function submit(kind, key, chunkX, chunkZ, resultBytes, run, onResult, onError) {
    if (declineMirror(onError)) return;
    const g = state.generators.get(key >>> 0);
    if (!g || g.failed) {
      onError("generator unavailable");
      return;
    }
    if (state.draining) {
      state.transient++;
      onError("transient:worldgen kernel is draining");
      return;
    }
    const job = {
      ticket: state.nextTicket++, kind, key, settled: false, deadline: now() + deadlineMs(), onResult, onError,
    };
    state.pending.set(job.ticket, job);
    state.jobs++;
    const stat = state.kindStats[kind] || (state.kindStats[kind] = {submitted: 0, completed: 0, fallback: 0, transient: 0, timeout: 0});
    stat.submitted++;
    ensureWatchdog();
    runner().then((r) => {
      if (job.settled) return null;
      if (!r) throw transientError("unavailable", state.reason || "worldgen kernel unavailable");
      if (r.via === "runtime") state.viaRuntime++;
      else state.viaHost++;
      return run(r, g.handle, jobOptions(r.via, chunkX, chunkZ, resultBytes));
    }).then((result) => {
      if (result !== null) settle(job, true, result);
    }, (error) => settle(job, false, error));
  }

  // Java-facing view of a chunk result: flat arrays only (no nested objects), freshly allocated
  // so the Java side may read them later on its own thread.
  function flatten(chunk) {
    const sections = chunk.sections;
    const paletteOffsets = new Int32Array(sections.length + 1);
    let paletteTotal = 0;
    for (let i = 0; i < sections.length; i++) {
      paletteOffsets[i] = paletteTotal;
      paletteTotal += sections[i].palette.length;
    }
    paletteOffsets[sections.length] = paletteTotal;
    const palettes = new Int32Array(paletteTotal);
    const indices = new Uint16Array(sections.length * 4096);
    const uniform = new Int8Array(sections.length);
    for (let i = 0; i < sections.length; i++) {
      const s = sections[i];
      palettes.set(s.palette, paletteOffsets[i]);
      if (s.bits === 0) uniform[i] = 1;
      else indices.set(s.indices, i * 4096);
    }
    return {
      chunkX: chunk.chunkX,
      chunkZ: chunk.chunkZ,
      minY: chunk.minY,
      sectionCount: chunk.sectionCount,
      paletteOffsets,
      palettes,
      indices,
      uniform,
      worldSurface: new Int32Array(chunk.worldSurface),
      oceanFloor: new Int32Array(chunk.oceanFloor),
      postProcessing: new Int32Array(chunk.postProcessing),
      biomes: chunk.biomes ? new Int32Array(chunk.biomes) : null,
      token: 0,
    };
  }

  // beard: Int32Array [flags, affected x6, rigidCount, rigid x8 ..., junctionCount, junction x3 ...]
  function beardOf(packed) {
    if (!packed || packed.length < 9) return null;
    let p = 0;
    const flags = packed[p++];
    const affected = Array.prototype.slice.call(packed, p, p + 6);
    p += 6;
    const rigidCount = packed[p++];
    const rigids = packed.subarray(p, p + rigidCount * 8);
    p += rigidCount * 8;
    const junctionCount = packed[p++];
    const junctions = packed.subarray(p, p + junctionCount * 3);
    return {affected: flags & 1 ? affected : null, rigids, junctions};
  }

  // ---- kept noise chunks (26.2 surface) ----

  function storeBudget() {
    const c = config();
    const configured = c && c.noiseStoreMB != null ? Number(c.noiseStoreMB) : NaN;
    if (isFinite(configured) && configured >= 0) return configured * MB;
    const memory = global.navigator && global.navigator.deviceMemory;
    return memory && memory <= 4 ? 16 * MB : 32 * MB;
  }

  function storeChunk(chunk) {
    const bytes = chunk.bytes ? chunk.bytes.buffer.byteLength : 0;
    const budget = storeBudget();
    if (bytes === 0 || bytes > budget) return 0;
    while (state.storeBytes + bytes > budget && state.store.size > 0) {
      const oldest = state.store.keys().next().value;
      dropStored(oldest);
      state.evicted++;
    }
    let token = state.nextToken;
    state.nextToken = state.nextToken >= 0x3fffffff ? 1 : state.nextToken + 1;
    if (state.store.has(token)) dropStored(token);
    state.store.set(token, {chunk, bytes});
    state.storeBytes += bytes;
    state.stored++;
    return token;
  }

  function dropStored(token) {
    const entry = state.store.get(token);
    if (!entry) return false;
    state.store.delete(token);
    state.storeBytes -= entry.bytes;
    return true;
  }

  function sectionId(section, i) {
    return section.bits === 0 ? section.palette[0] : section.palette[section.indices[i]];
  }

  // Blocks that differ between the kept noise chunk and the surface result:
  // positions (section << 12 | y << 8 | z << 4 | x) and their new block state ids.
  function surfaceDiff(before, after) {
    if (before.sectionCount !== after.sectionCount) throw new Error("surface result section count mismatch");
    let capacity = 4096;
    let positions = new Int32Array(capacity);
    let states = new Int32Array(capacity);
    let count = 0;
    for (let s = 0; s < after.sectionCount; s++) {
      const a = before.sections[s];
      const b = after.sections[s];
      if (a.bits === 0 && b.bits === 0 && a.palette[0] === b.palette[0]) continue;
      for (let i = 0; i < 4096; i++) {
        const id = sectionId(b, i);
        if (id === sectionId(a, i)) continue;
        if (count === capacity) {
          capacity *= 2;
          const grownPositions = new Int32Array(capacity);
          grownPositions.set(positions);
          positions = grownPositions;
          const grownStates = new Int32Array(capacity);
          grownStates.set(states);
          states = grownStates;
        }
        positions[count] = (s << 12) | i;
        states[count] = id;
        count++;
      }
    }
    return {
      chunkX: after.chunkX,
      chunkZ: after.chunkZ,
      count,
      positions: positions.slice(0, count),
      states: states.slice(0, count),
    };
  }

  // ---- Java entry points ----

  function ringOf(ring) {
    if (!ring || !(ring.length > 0)) return null;
    state.ringJobs++;
    return ring;
  }

  function submitTerrain(key, chunkX, chunkZ, flags, beard, onResult, onError, ringBiomes) {
    const J = global.GaiusWorldgenJob;
    const keep = (flags & FLAG_KEEP_NOISE) !== 0;
    const kernelFlags = flags & KERNEL_FLAGS_MASK;
    const ring = ringOf(ringBiomes);
    submit("TERRAIN", key, chunkX, chunkZ, TERRAIN_RESULT_BYTES, (r, handle, opts) => J.terrain(r, handle, chunkX, chunkZ, {
      surface: (kernelFlags & J.FLAG_SURFACE) !== 0,
      biomes: (kernelFlags & J.FLAG_BIOMES) !== 0,
      beard: beardOf(beard),
      ringBiomes: ring,
    }, opts).then((chunk) => {
      const flat = flatten(chunk);
      if (keep && (kernelFlags & J.FLAG_SURFACE) === 0) flat.token = storeChunk(chunk);
      return flat;
    }), onResult, onError);
  }

  function submitBiomes(key, chunkX, chunkZ, onResult, onError) {
    const J = global.GaiusWorldgenJob;
    submit("BIOMES", key, chunkX, chunkZ, BIOMES_RESULT_BYTES, (r, handle, opts) => J.biomes(r, handle, chunkX, chunkZ, opts)
      .then((b) => new Int32Array(b.ids)), onResult, onError);
  }

  // Surface rules on raw chunk bytes (the chunk part of an earlier terrain result).
  function submitSurface(key, chunkBytes, chunkX, chunkZ, beard, onResult, onError, ringBiomes) {
    const J = global.GaiusWorldgenJob;
    const ring = ringOf(ringBiomes);
    submit("SURFACE", key, chunkX, chunkZ, TERRAIN_RESULT_BYTES, (r, handle, opts) => J.surface(r, handle,
      {bytes: chunkBytes, chunkX, chunkZ}, {beard: beardOf(beard), ringBiomes: ring}, opts).then(flatten),
      onResult, onError);
  }

  // Surface rules on a kept noise chunk; reports the changed blocks only. The entry is consumed.
  function submitSurfaceStored(key, token, chunkX, chunkZ, beard, onResult, onError, ringBiomes) {
    if (declineMirror(onError)) return;
    const entry = state.store.get(token);
    if (!entry || entry.chunk.chunkX !== chunkX || entry.chunk.chunkZ !== chunkZ) {
      if (entry) dropStored(token);
      state.transient++;
      onError("transient:noise chunk " + token + " is no longer kept");
      return;
    }
    dropStored(token);
    const J = global.GaiusWorldgenJob;
    const before = entry.chunk;
    const ring = ringOf(ringBiomes);
    submit("SURFACE", key, chunkX, chunkZ, TERRAIN_RESULT_BYTES, (r, handle, opts) => J.surface(r, handle,
      {bytes: before.bytes, chunkX, chunkZ}, {beard: beardOf(beard), ringBiomes: ring}, opts).then((after) => {
      const diff = surfaceDiff(before, after);
      state.surfaces++;
      state.surfaceChanges += diff.count;
      return diff;
    }), onResult, onError);
  }

  function telemetry() {
    return {
      status: state.status,
      host: state.host,
      mode: hostMode(),
      usable: usable(),
      runtime: !!runtimeClient(),
      variant: state.variant,
      reason: state.reason,
      failures: state.failures,
      draining: state.draining,
      pending: state.pending.size,
      jobs: state.jobs,
      completed: state.completed,
      fallbacks: state.fallbacks,
      transient: state.transient,
      timeouts: state.timeouts,
      hostFallbacks: Object.fromEntries(Object.entries(state.hostFallbacks).map(([stage, reasons]) =>
        [stage, Object.fromEntries(Object.entries(reasons))])),
      late: state.late,
      viaRuntime: state.viaRuntime,
      viaHost: state.viaHost,
      generators: state.generators.size,
      kept: state.store.size,
      keptBytes: state.storeBytes,
      stored: state.stored,
      evicted: state.evicted,
      surfaces: state.surfaces,
      surfaceChanges: state.surfaceChanges,
      ringJobs: state.ringJobs,
      ringFallbacks: state.ringFallbacks,
      kinds: Object.fromEntries(Object.entries(state.kindStats).map(([kind, value]) => [kind, {...value}])),
      pool: state.pool ? state.pool.telemetry() : null,
      hostRuntime: state.runtime ? state.runtime.telemetry() : null,
    };
  }

  global.GaiusWorldgenKernel = Object.freeze({
    FLAG_KEEP_NOISE,
    FALLBACK_PREFIX,
    start,
    usable,
    status: () => state.status,
    switchedOff,
    hostMode,
    registerGenerator,
    generatorUsable,
    submitTerrain,
    submitBiomes,
    submitSurface,
    submitSurfaceStored,
    dropStored,
    pending: () => state.pending.size,
    drain,
    resume,
    telemetry,
    recordHostFallback,
    snapshot: () => ({kernel: telemetry(), worldgen: global.__gaiusWorldgenStats || null,
      server: global.__gaiusServerTickTelemetry || null}),
  });
})(typeof globalThis !== "undefined" ? globalThis : self);
