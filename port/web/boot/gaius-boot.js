// Gaius boot: the one script the launcher (port/web/launcher/index.template.html) includes
// before its own boot code. It wires the v0.4 runtime pieces into every distribution form
// without the launcher having to know which one it runs in:
//
//   site      multi-file GitHub Pages layout (port/scripts/build-pages-site.py); the page
//             carries window.__gaiusSite = {id, profile, assets: {logical name: hashed url},
//             kernels, modules, serviceWorker, inflate}; gaius-site-inflate-v1: the
//             WebAssembly GC modules may ship only as <name>.wasm.<hash>.gz (assets
//             "<name>.wasm.gz"), and inflate names the <name>.<hash>.wasm URL the Service
//             Worker serves them under (port/web/sw/gaius-sw.js)
//   portable  the offline single file (port/scripts/build-portable-html.py); modules are
//             inlined before this script and window.__gaiusPortableKernels carries the wasm
//   dev       a plain dist directory over http(s); files are loaded by their logical names
//   file      a plain dist directory opened from disk (file://): nothing can be fetched there,
//             so binary files come from gzip+base64 script payloads next to them
//             (window.__gaiusFilePayloads, written with the WebAssembly GC page)
//
// What it does:
//   - client runtime: checks the WebAssembly GC, try_table and JSPI capabilities before any
//     payload is fetched. A supported page starts the WasmGC client; an unsupported page exposes
//     a localized browser-capability state and stops before the launcher downloads game assets.
//   - file payloads (file form): the vanilla pack, the shader toolchain modules, the hotpath and
//     the WebAssembly GC client are decoded from their script payloads into the globals the
//     launcher already reads for the portable page (__gaiusVanillaAssetsCompressedPromise,
//     __gaiusShaderToolchainUrls, __gaiusHotpathWasmUrl, __gaiusPortableAssetsReady).
//   - integrated server runtime (WP10): ?serverRuntime=wasm hands the server Worker
//     singleplayer-server.wasm and its runtime scripts in the start message (site, dist over
//     http, and a dist opened from disk, whose Worker gets these files from the page:
//     filePayload, BrowserSingleplayerClient).
//   - asset URLs: __gaiusBoot.assetUrl(name) maps a logical Wasm/support file name to its
//     content-hashed URL; applyAssetUrls() points the launcher's client, wasm hotpath and
//     singleplayer globals at them.
//   - Service Worker (site over https only): registers gaius-sw.js (cross-origin isolation
//     headers + immutable asset cache) and prunes old generations once the title screen is up.
//     ?gaiusSw=0 unregisters it; ?gaiusCoi=0|credentialless|require-corp picks the COEP mode.
//   - runtime modules: kernel-policy.js, kernel-runtime.js and the kernel job codecs, then the
//     quality layer (runtime/quality/*, owned by the graphics workstream) in its load order.
//     beforeMain() waits for them (bounded), up to 300 ms for the WebGPU adapter signal
//     (GaiusQuality.caps.adapterReady), for GaiusQuality.caps.prepareTier (the off-main-thread
//     tier benchmark, off with ?gaiusTierWorker=0, or the tier study) and runs
//     GaiusQuality.caps.ensureTier(). The
//     quality layer reads window.__gaiusProfileId and window.__gaiusRenderSeam, which the
//     launcher sets in <head>.
//   - kernel runtime: window.__gaiusKernels creates GaiusKernelRuntime lazily, feeds it the
//     player position from window.__gaiusMinecraftState for motion prediction, and gives the
//     integrated server Worker a MessagePort into it ({type: "gaius-kernel-port"} right after
//     the singleplayer "start" message) together with the worldgen kernel scripts and the
//     page's worldgen and light switches (?worldgenKernel=0, ?worldgenKernelHost=,
//     ?lightKernel=0 or gaius.lightKernel=off), which that Worker cannot read itself.
//   - lazy singleplayer: the server Worker payload is only loaded when a world starts; while
//     the title screen idles it is prefetched (?gaiusPrefetch=0 turns that off).
//   - sounds on demand (site): the vanilla pack is split into a core pack and a sounds pack;
//     the sounds pack streams in parallel and is merged into window.__gaiusVanillaAssets.
//   - graphics backend (M1 G2): ?gpu=webgpu|webgl2 (default webgl2; WebGPU where available on an
//     automatic site and on the portable page); for webgpu the WebGPU glue
//     and the shader translator load at parse time and beforeMain() waits (at most 5 s) for the
//     device and the translator, else the game renders with WebGL 2. See "graphics backend".
//   - boot timeline: performance marks "gaius:<phase>" for every launcher boot timing.
//   - heap guard: every isolate of the renderer shares one 4 GiB V8 cage, so the page collects
//     the previous document's heap when it starts and keeps its own garbage under a budget
//     (installHeapGuard; the integrated server Worker runs the same code).
//   - memory pacing (cross-origin-isolated pages): measureUserAgentSpecificMemory() requests at
//     menus, the pause screen, loading screens and hidden pages make the browser collect the
//     per-frame WebGPU and message garbage that V8 otherwise leaves for minutes. See "memory pacing".
//
// Plain script, no modules: it is inlined into the portable page. It must never contain a
// closing script tag sequence.
(function (global) {
  "use strict";
  if (!global || global.__gaiusBoot) return;

  const BOOT_VERSION = 1;

  // BEGIN gaius-heap-guard
  //
  // Chrome gives every isolate of a renderer process (the page, the integrated server Worker
  // and the kernel Workers) one shared 4 GiB V8 pointer-compression cage, and V8 sizes each heap
  // as if it had the cage to itself. Two things follow.
  //   - A reload leaves the previous document's heap in the process until V8 gets around to
  //     collecting it; ten quick reloads can hold two documents' worth of memory at a time.
  //   - A collection in one isolate cannot reclaim another's garbage. With render and simulation
  //     distance 32 the page and the server keep ~2 GB alive between them, their garbage peaks
  //     add ~1.5 GB on top, and the isolate that asks for memory first (the server, when an old
  //     world is upgraded and entered) dies with "V8 javascript OOM (CALL_AND_RETRY_LAST)"
  //     while the cage is still full of the page's dead objects.
  //
  // There is no API to request a collection. A typed array that cannot be allocated, however,
  // makes V8 run a full collection before it gives up, so an impossible allocation is a
  // synchronous, side-effect-free collection request. It stops the thread for the length of a
  // full collection (a few hundred ms to a second on a large world), so installHeapGuard() asks
  //   - once when the page starts, while only the previous document's garbage is on the heap
  //     (collectOnStart: true), or, for the page (collectOnStart: "idle"), once the game has
  //     finished booting and stayed on a quiet screen for startDelayMs, and only when this tab
  //     held an earlier document (startNeeded()) and the heap is not known to be small. A
  //     reload's start collection has to sweep the whole previous document, 0.4-0.5 s on a
  //     loaded Linux box and ~1 s on Windows with a 1.5 GB renderer, and it ran in front of the
  //     title, so a warm reload started later than a cold start; V8 collects the old heap on its
  //     own while the new page boots, and the guard only sweeps up what is left;
  //   - when the heap has grown by more than budgetMB since its last low point, at a moment
  //     canCollect() allows (no gameplay frames are being measured); and
  //   - when the heap is above emergencyMB and has grown by more than emergencyGrowthMB since its
  //     last low point, at most every emergencyGapMs (30 s), wherever that is: a stall beats an
  //     OOM, and only worlds far larger than the defaults ever get there.
  // performance.memory is the only per-isolate size signal there is, and only the page has it:
  // a Worker cannot read its own heap size, so the integrated server is not guarded.
  function installHeapGuard(root, options) {
    const state = root.__gaiusHeapGuard = {collections: 0, lastReason: null, startSkipped: null, log: []};
    const perf = root.performance;
    const MB = 1048576;
    const settings = options || {};
    const budget = (Number(settings.budgetMB) || 384) * MB;
    const minGapMs = Number(settings.minGapMs) || 4000;
    const emergency = (Number(settings.emergencyMB) || Infinity) * MB;
    const emergencyGrowth = (Number(settings.emergencyGrowthMB) || 256) * MB;
    const emergencyGapMs = Number(settings.emergencyGapMs) || 30000;
    const tickMs = 1000;
    function collect(reason) {
      const started = perf && typeof perf.now === "function" ? perf.now() : 0;
      try {
        // 16 GiB is above what the sandboxed ArrayBuffer allocator hands out, so the call throws
        // after V8's collect-and-retry; if a platform grants it anyway the buffer is unreferenced.
        new root.ArrayBuffer(Math.pow(2, 34));
      } catch (_) { /* expected */ }
      state.collections++;
      state.lastReason = reason;
      if (typeof settings.afterCollect === "function") {
        try { settings.afterCollect(reason); } catch (_) { /* advisory */ }
      }
      if (state.log.length < 32) {
        state.log.push([Date.now(), reason, perf && typeof perf.now === "function" ? Math.round(perf.now() - started) : -1]);
      }
    }
    state.collect = collect;
    // Chrome reports performance.memory as a coarse size, cached for a long time, unless it runs
    // with --enable-precise-memory-info (or the page is cross-origin isolated): the coarse sizes are
    // multiples of 100 kB (10000000, 18200000, 26000000, ...) and stay put for minutes while the
    // heap grows, so they tell nothing about the heap and only an exact size can say "small".
    function heapKnownSmall(minBytes) {
      if (!minBytes || !perf || !perf.memory) return false;
      const used = perf.memory.usedJSHeapSize;
      return used > 0 && used % 100000 !== 0 && used < minBytes;
    }
    let startTimer = 0;
    if (settings.collectOnStart === "idle" && typeof root.setTimeout === "function") {
      const startDelayMs = settings.startDelayMs === undefined ? 4000 : Number(settings.startDelayMs);
      const startGiveUpMs = Number(settings.startGiveUpMs) || 120000;
      const startMinHeap = (Number(settings.startMinHeapMB) || 0) * MB;
      const begun = root.Date.now();
      let readyAt = 0;
      let polls = 0;
      const pollStart = function () {
        startTimer = 0;
        let again = true;
        try {
          const now = root.Date.now();
          if (typeof settings.startNeeded === "function" && !settings.startNeeded()) {
            again = false;
            state.startSkipped = "no-previous-document";
          } else if (state.collections > 0 || now - begun > startGiveUpMs || ++polls > startGiveUpMs / 500) {
            again = false;
            if (!state.collections) state.startSkipped = "not-quiet";
          } else if (typeof settings.startReady === "function" && settings.startReady()) {
            if (!readyAt) readyAt = now;
            if (now - readyAt >= startDelayMs && (typeof settings.canCollect !== "function" || settings.canCollect())) {
              again = false;
              if (heapKnownSmall(startMinHeap)) state.startSkipped = "small-heap";
              else collect("start-idle");
            }
          }
        } catch (_) { again = false; }
        if (again) startTimer = root.setTimeout(pollStart, 500);
        // a Node host (the smokes) must not be kept alive by the poll; a browser timer id is a number
        if (startTimer && typeof startTimer.unref === "function") startTimer.unref();
      };
      startTimer = root.setTimeout(pollStart, 500);
      if (startTimer && typeof startTimer.unref === "function") startTimer.unref();
    } else if (settings.collectOnStart) {
      collect("start");
    }
    state.stop = function () {
      if (startTimer) root.clearTimeout(startTimer);
      startTimer = -1;
    };
    if (!perf || !perf.memory || typeof root.setTimeout !== "function") return state;
    let low = Infinity;
    let lastCollection = -Infinity;
    let timer = 0;
    function tick() {
      timer = 0;
      try {
        const used = perf.memory.usedJSHeapSize;
        if (used < low) low = used;
        const now = Date.now();
        const growth = used - low;
        let reason = null;
        if (used > emergency && growth > emergencyGrowth && now - lastCollection >= emergencyGapMs) {
          reason = "emergency";
        } else if (growth > budget && now - lastCollection >= minGapMs
            && typeof settings.canCollect === "function" && settings.canCollect()) {
          reason = "budget";
        }
        if (reason) {
          lastCollection = now;
          collect(reason);
          low = Infinity;
        }
      } catch (_) { /* the signal is advisory */ }
      timer = root.setTimeout(tick, tickMs);
    }
    state.stop = function () {
      if (startTimer) root.clearTimeout(startTimer);
      startTimer = -1;
      if (timer) root.clearTimeout(timer);
      timer = -1;
    };
    timer = root.setTimeout(tick, tickMs);
    return state;
  }
  // How many earlier documents of this tab may still have a heap waiting for a collection. A
  // reload or history step means one (the document that was just replaced); each page of ours
  // leaves the count in sessionStorage and resets it to 0 once it ran any collection, so a chain
  // of reloads that never reaches a quiet screen counts up. A fresh Chrome process has nothing
  // to collect. Without sessionStorage there is no way to tell: assume one.
  const HEAP_DEBT_KEY = "gaius.heapDebt";
  function heapDebtAtStart(root) {
    let debt = 0;
    try {
      const entry = root.performance && root.performance.getEntriesByType && root.performance.getEntriesByType("navigation")[0];
      if (entry && (entry.type === "reload" || entry.type === "back_forward")) debt = 1;
    } catch (_) { /* advisory */ }
    try {
      const storage = root.sessionStorage;
      const raw = storage.getItem(HEAP_DEBT_KEY);
      if (raw !== null) debt = Math.max(debt, (parseInt(raw, 10) || 0) + 1);
      storage.setItem(HEAP_DEBT_KEY, String(debt));
    } catch (_) {
      debt = Math.max(debt, 1);
    }
    return debt;
  }
  // When the previous documents' heaps are swept. The start path never is, unless three heaps are
  // waiting: a forced full collection there cost a reload 0.4-1 s before the title and made main
  // twice as slow (it also drops the compiled code the boot just produced). One earlier heap is
  // swept 4 s after the game booted, two shortly after it booted (the reloads before were
  // interrupted early), three or more (reloads that never reached a booted game) first thing.
  function heapStartPlan(debt) {
    return {collectOnStart: debt >= 3 ? true : "idle", startDelayMs: debt >= 2 ? 500 : 4000};
  }
  function clearHeapDebt(root) {
    try { root.sessionStorage.setItem(HEAP_DEBT_KEY, "0"); } catch (_) { /* advisory */ }
  }
  // END gaius-heap-guard

  const doc = global.document;
  const loc = global.location || {protocol: "", search: "", href: ""};
  const nav = global.navigator || {};
  const perf = global.performance;
  // A moment without measured gameplay frames (menus, loading and progress screens, the pause
  // screen, a hidden page): the memory pacing and the heap guard may stall the page there.
  const QUIET_SCREENS = /(?:LevelLoadingScreen|ReceivingLevelScreen|ProgressScreen|GenericMessageScreen|ConnectScreen|GenericWaitingScreen|PauseScreen|DisconnectedScreen)$/;
  function quietReason() {
    if (doc && doc.visibilityState === "hidden") return "hidden";
    const game = global.__gaiusMinecraftState;
    if (!game) return null;
    if (!game.level) return "menu";
    if (game.pause === true) return "pause";
    const screen = game.screen ? String(game.screen) : "";
    return screen && QUIET_SCREENS.test(screen) ? "loading" : null;
  }
  // The game finished booting and shows a screen: the first moment the guard may stall the page
  // without delaying the title.
  function gameBooted() {
    const game = global.__gaiusMinecraftState;
    return !!game && game.running === true && game.noRender !== true && !game.overlay
      && global.__gaiusInitialBootPending === false;
  }
  const heapDebt = heapDebtAtStart(global);
  installHeapGuard(global, {
    ...heapStartPlan(heapDebt), startNeeded: () => heapDebt > 0, startReady: gameBooted, startMinHeapMB: 200,
    afterCollect: () => clearHeapDebt(global), canCollect: () => quietReason() !== null, emergencyMB: 1100});
  const params = (() => {
    try {
      return new URLSearchParams(loc.search || "");
    } catch (_) {
      return {get: () => null};
    }
  })();

  // RD32 diagnostics are entirely opt-in. No frame callback, observer, timer, or
  // sampling machinery is created on the normal path.
  if ((params.get("rd32trace") === "1" || params.get("rd32snapcheck") === "1")
      && global.__gaiusRd32TraceSupported === true) {
    const priorEveryFrame = global.__gaiusChunkPipelineTelemetryEveryFrame;
    global.__gaiusChunkCausalTelemetryEnabled = true;
    global.__gaiusChunkPipelineTelemetryEveryFrame = true;
    const now = () => global.performance && performance.now ? performance.now() : Date.now();
    let captureFastNanos = 0, captureLegacyNanos = 0, captureFastCount = 0, captureLegacyCount = 0;
    const startedAt = Date.now();
    const trace = global.__gaiusRd32Trace = {
      schema: "gaius.rd32-trace.v1", version: global.__gaiusProfileId || global.__gaiusSite?.profile || "unknown", enabled: true,
      startedAt: new Date(startedAt).toISOString(), samples: [], frames: [],
      sampling: {
        clock: "performance.now; one-second buckets",
        frames: "requestAnimationFrame delta; >33ms only; cause is temporal correlation",
        gc: "PerformanceObserver gc when supported; longtask is not GC-specific",
        allocations: "allocation sampling and call sites require a DevTools Protocol profiler; page APIs expose neither",
        liveSet: "total uses measureUserAgentSpecificMemory when available; category byte totals are not exposed by complete managers",
        generationDecode: "server owns generation and packet decode completes before client handler entry",
        workerQueue: "mesh worker queue lives in its worker global and does not publish queue depth to page"
      },
      gcPauses: [], droppedSamples: 0, droppedFrames: 0, droppedGcPauses: 0,
      snapshotCheck: {captures: 0, mismatches: 0, samples: [], sampleLimit: 20,
        recordSnapshotCheck(sx, sy, sz, firstDifference, legacyLength, fastLength) {
          this.captures++;
          if (firstDifference < 0) return;
          this.mismatches++;
          if (this.samples.length < this.sampleLimit) this.samples.push({section: {x: sx, y: sy, z: sz},
            firstDifference, legacyLength, fastLength});
        }},
      recordSnapshotTiming(fast, nanos) {
        if (fast) { captureFastNanos += nanos; captureFastCount++; }
        else { captureLegacyNanos += nanos; captureLegacyCount++; }
      },
      stop() { this.enabled = false; cancelAnimationFrame(raf); clearInterval(timer); observers.forEach(o => o.disconnect());
        if (priorEveryFrame === undefined) delete global.__gaiusChunkPipelineTelemetryEveryFrame;
        else global.__gaiusChunkPipelineTelemetryEveryFrame = priorEveryFrame; },
      snapshot() { return JSON.parse(JSON.stringify(this)); }
    };
    let lastFrame = 0, bucketAt = now(), raf = 0, observers = [];
    let lastUploadTotal = 0, observed = new WeakMap();
    let lastFrameUpload = 0, lastFrameCapture = 0, lastFrameInstall = 0;
    const appendFrame = t => {
      if (!trace.enabled) return;
      if (lastFrame) {
        const duration = t - lastFrame;
        if (duration > 33) {
          const causal = global.__gaiusChunkPipelineTelemetry?.causal;
          const uploadTotal = Number(global.__gaiusChunkPipelineTelemetry?.totalUploadPassMillis) || 0;
          const captureCount = Number(causal?.snapshotCompleteCount) || 0;
          const installCount = Number(causal?.installCompleteCount) || 0;
          const uploaded = uploadTotal > lastFrameUpload;
          const captured = captureCount > lastFrameCapture;
          const installed = installCount > lastFrameInstall;
          lastFrameUpload = uploadTotal; lastFrameCapture = captureCount; lastFrameInstall = installCount;
          if (trace.frames.length === 10000) { trace.frames.shift(); trace.droppedFrames++; }
          trace.frames.push({at: Date.now(), durationMs: duration,
            cause: trace.gcPauses.some(g => g.end >= performance.timeOrigin + t - duration && g.start <= performance.timeOrigin + t)
              ? "gc" : uploaded ? "upload" : captured ? "capture" : "other",
            installCompleted: installed,
            attribution: "counter overlap is approximate; rAF delta includes scheduling and missed frames"});
        }
      }
      lastFrame = t; raf = requestAnimationFrame(appendFrame);
    };
    if (global.PerformanceObserver) {
      try {
        for (const type of ["gc", "longtask"]) if (PerformanceObserver.supportedEntryTypes?.includes(type)) {
          const observer = new PerformanceObserver(list => list.getEntries().forEach(e => {
            if (trace.gcPauses.length === 1000) { trace.gcPauses.shift(); trace.droppedGcPauses++; }
            trace.gcPauses.push({start: e.startTime + performance.timeOrigin, end: e.startTime + e.duration + performance.timeOrigin, durationMs: e.duration, source: type});
          }));
          observer.observe({type, buffered: false}); observers.push(observer);
        }
      } catch (_) { observers.forEach(o => o.disconnect()); observers = []; }
    }
    const timer = setInterval(() => {
      const at = now(), seconds = Math.max(0.001, (at - bucketAt) / 1000); bucketAt = at;
      const causal = global.__gaiusChunkPipelineTelemetry?.causal;
      const sections = causal?.sections || [];
      const stages = {captureMs: 0, captureFastMs: captureFastNanos / 1e6,
        captureFastCount, captureLegacyMs: captureLegacyNanos / 1e6, captureLegacyCount,
        bridgeSubmitMs: 0, submitToResultMs: 0,
        installQueueToCompleteMs: 0, installExecutionMs: 0,
        uploadPassMs: 0, uploadProxyCount: 0};
      captureFastNanos = 0; captureLegacyNanos = 0; captureFastCount = 0; captureLegacyCount = 0;
      for (const s of sections) {
        let mask = observed.get(s) || 0;
        if (!(mask & 1) && s.snapshotStartAt != null && s.snapshotEndAt != null) { stages.captureMs += Math.max(0, s.snapshotEndAt - s.snapshotStartAt); mask |= 1; }
        if (!(mask & 2) && s.workerSubmitAt != null && s.workerResultAt != null) { stages.submitToResultMs += Math.max(0, s.workerResultAt - s.workerSubmitAt); mask |= 2; }
        if (!(mask & 4) && s.installQueueAt != null && s.installCompleteAt != null) { stages.installQueueToCompleteMs += Math.max(0, s.installCompleteAt - s.installQueueAt); mask |= 4; }
        if (!(mask & 8) && s.installDurationMs != null) { stages.installExecutionMs += Math.max(0, s.installDurationMs); mask |= 8; }
        if (!(mask & 16) && s.gpuUploadProxyAt != null) { stages.uploadProxyCount++; mask |= 16; }
        if (!(mask & 32) && s.bridgeSubmitMs != null) { stages.bridgeSubmitMs += Math.max(0, s.bridgeSubmitMs); mask |= 32; }
        if (mask) observed.set(s, mask);
      }
      const pipe = global.__gaiusChunkPipelineTelemetry || {};
      const uploadTotal = Number(pipe.totalUploadPassMillis) || 0;
      stages.uploadPassMs = Math.max(0, uploadTotal - lastUploadTotal); lastUploadTotal = uploadTotal;
      const networkStats = global.__gaiusNetworkStats || {};
      const sample = {at: new Date().toISOString(), windowMs: seconds * 1000,
        stages, queues: {compile: pipe.compileBacklog ?? null, upload: pipe.uploadBacklog ?? null,
          installReady: pipe.meshInstallReady ?? null, installPending: pipe.meshInstallPending ?? null,
          installReadyBytes: pipe.meshInstallReadyBytes ?? null,
          clientDecodedPackets: Number.isFinite(Number(networkStats.decodedPacketQueue))
            ? Math.max(0, Number(networkStats.decodedPacketQueue)) : null,
          kernel: null, generation: null, decode: null},
        generationMs: null, decodeMs: null,
        allocations: {bytesPerSecond: null, topSites: null},
        liveSetBytes: {total: null, chunkData: null, cpuMeshCopies: null, gpuBuffers: null, staging: null},
        unavailable: {
          generationMs: "remote server has no telemetry protocol field",
          decodeMs: "client packet handler starts after packet decode",
          queuesKernel: "worker-global queue depth is not published to page",
          queuesGenerationDecode: "remote server queue counters are not sent to client",
          allocations: "requires DevTools Protocol allocation sampling outside page runtime",
          liveSetChunkData: "chunk storage manager exposes no byte counter",
          liveSetCpuMeshCopies: "mesh snapshot arrays have no retained-live byte counter",
          liveSetGpuBuffers: "terrain allocation manager does not aggregate live GPU bytes",
          liveSetStaging: "staging pool has no complete cross-version live-byte counter"
        }};
      if (trace.samples.length === 3600) { trace.samples.shift(); trace.droppedSamples++; }
      trace.samples.push(sample);
      if (performance.measureUserAgentSpecificMemory) performance.measureUserAgentSpecificMemory().then(m => { sample.liveSetBytes.total = m.bytes; }).catch(() => {});
    }, 1000);
    global.__gaiusRd32TraceGet = () => trace.snapshot();
    raf = requestAnimationFrame(appendFrame);
  }

  // Opt-in bounded timeline for release investigations.  Normal boots do not allocate an
  // observer, timer, or sample buffer.  The portable artifact can therefore be profiled with
  // ?gaiusDiag=1 without changing its default frame path.
  function installLightweightDiagnosticTimeline() {
    if (params.get("gaiusDiag") !== "1" || global.__gaiusDiagTimeline) return;
    const limit = 512, events = [], started = perf && typeof perf.now === "function" ? perf.now() : 0;
    const push = (kind, detail) => {
      const at = (perf && typeof perf.now === "function" ? perf.now() : 0) - started;
      events.push({at: Math.round(at * 100) / 100, kind, detail: detail || null});
      if (events.length > limit) events.shift();
    };
    const state = {enabled: true, events, longTasks: 0, gc: 0, frames: 0, over16: 0, over33: 0,
      over50: 0, over100: 0, maxFrameMs: 0, lastFrameMs: null,
      started, push};
    let raf = 0, interval = 0, last = null, observer = null, gcObserver = null;
    const sample = now => {
      if (!state.enabled) return;
      if (last !== null) {
        const delta = now - last; state.frames++; state.lastFrameMs = delta;
        state.maxFrameMs = Math.max(state.maxFrameMs, delta);
        if (delta > 16.7) state.over16++; if (delta > 33.4) state.over33++;
        if (delta > 50) state.over50++; if (delta > 100) state.over100++;
        if (delta > 33.4) push("frame", {deltaMs: Math.round(delta * 100) / 100,
          pipeline: global.__gaiusChunkPipelineTelemetry || null,
          worker: global.__gaiusWorkerMessageTelemetry || null,
          game: global.__gaiusFrameTelemetry || null});
      }
      last = now; raf = global.requestAnimationFrame(sample);
    };
    try {
      if (global.PerformanceObserver) {
        if ((global.PerformanceObserver.supportedEntryTypes || []).includes("longtask")) {
          observer = new global.PerformanceObserver(list => { for (const e of list.getEntries()) {
            state.longTasks++; push("longtask", {durationMs: e.duration, name: e.name});
          }}); observer.observe({type: "longtask", buffered: false});
        }
        if ((global.PerformanceObserver.supportedEntryTypes || []).includes("gc")) {
          gcObserver = new global.PerformanceObserver(list => { for (const e of list.getEntries()) {
            state.gc++; push("gc", {durationMs: e.duration});
          }}); gcObserver.observe({type: "gc", buffered: false});
        }
      }
    } catch (_) { /* diagnostics must never affect boot */ }
    interval = global.setInterval(() => {
      const pipe = global.__gaiusChunkPipelineTelemetry || {};
      const messages = global.__gaiusWorkerMessageTelemetry || {};
      const game = global.__gaiusFrameTelemetry || {};
      const world = global.__gaiusMinecraftState || {};
      push("timeline", {loadedChunks: Number(world.loadedChunkCount) || 0,
        player: world.player ? {x: world.player.x, y: world.player.y, z: world.player.z} : null,
        compileBacklog: Number(pipe.compileBacklog) || 0, uploadBacklog: Number(pipe.uploadBacklog) || 0,
        pipeline: pipe, workerMessages: messages, gameFrame: game,
        jspi: global.__gaiusJspiTelemetry || null});
    }, 250);
    state.stop = () => { state.enabled = false; if (raf) global.cancelAnimationFrame(raf);
      if (interval) global.clearInterval(interval);
      try { observer && observer.disconnect(); gcObserver && gcObserver.disconnect(); } catch (_) {} };
    state.summary = () => ({enabled: state.enabled, frames: state.frames, longTasks: state.longTasks,
      gc: state.gc, over16: state.over16, over33: state.over33, over50: state.over50,
      over100: state.over100, maxFrameMs: state.maxFrameMs,
      events: events.slice(), lastFrameMs: state.lastFrameMs});
    global.__gaiusDiagTimeline = state;
    if (typeof global.requestAnimationFrame === "function") raf = global.requestAnimationFrame(sample);
  }
  installLightweightDiagnosticTimeline();
  // Keep all low-memory decisions on one bridge. The quality layer may publish its automatic
  // tier after this script starts, so read the global dynamically and retain the URL override.
  global.__gaiusIsLowMemoryTier = function () {
    return String(params.get("gaiusMemoryTier") || "").toLowerCase() === "low"
      || !!(global.__gaiusMemoryTier && global.__gaiusMemoryTier.lowMemory);
  };
  const nowMs = () => (perf && perf.now ? perf.now() : Date.now());
  const site = global.__gaiusSite && typeof global.__gaiusSite === "object" ? global.__gaiusSite : null;
  // The quality layer caps the canvas ratio by profile. The launcher sets the id in <head>; a
  // site page from an older launcher still names it in its descriptor.
  if (site && typeof site.profile === "string" && site.profile && !global.__gaiusProfileId) {
    global.__gaiusProfileId = site.profile;
  }
  // The portable page sets __gaiusBootPortable before this script (its own bootstrap, which sets
  // __gaiusPortableBuild, runs later in the body).
  // The portable body bootstrap runs after this head script. Its runtime descriptor and
  // payload table are already present, so use them to identify file:// portable pages before
  // __gaiusPortableBuild is published; otherwise dual pages are mistaken for plain file dists.
  const isPortable = () => global.__gaiusPortableBuild === true || global.__gaiusBootPortable === true
    || (isFile && global.__gaiusPortableRuntimes && global.__gaiusFilePayloads);
  const isFile = loc.protocol === "file:";
  const events = [];

  function record(event, detail) {
    events.push({event, detail: detail === undefined ? "" : String(detail), at: Date.now()});
    if (events.length > 200) events.splice(0, events.length - 200);
  }

  function storageGet(key) {
    try {
      return global.localStorage ? global.localStorage.getItem(key) : null;
    } catch (_) {
      return null;
    }
  }

  function storageSet(key, value) {
    try {
      if (global.localStorage) global.localStorage.setItem(key, value);
    } catch (_) {
      // storage may be blocked; the setting simply does not persist
    }
  }

  function mark(name) {
    try {
      if (perf && typeof perf.mark === "function") perf.mark("gaius:" + name);
    } catch (_) {
      // marks are diagnostics only
    }
  }

  mark("boot-script");

  // --- asset map ------------------------------------------------------------------------------

  const KERNEL_RUNTIME_SCRIPTS = ["kernels/kernel-mirror.js", "kernels/kernel-policy.js", "kernels/kernel-runtime.js"];
  const QUALITY_SCRIPTS = [
    "runtime/quality/gpu-caps.js",
    "runtime/quality/quality-profile.js",
    "runtime/quality/gl-pass.js",
    "runtime/quality/upscaler.js",
    "runtime/quality/post-chain.js",
    "runtime/quality/quality-runtime.js",
  ];
  const SITE_GZIP_KEY = "gaius.site.gzip.v1";

  function assetUrl(name) {
    if (site && site.assets && Object.prototype.hasOwnProperty.call(site.assets, name)) return site.assets[name];
    return null;
  }

  // Derived client data is only reusable when it came from the same immutable asset set. Pages
  // encode the SHA-256 prefix in every asset URL; portable/dev pages fall back to their explicit
  // build identity. Keep this value separate from the URL so Java/Worker code can use one key.
  function assetHash(name) {
    const url = assetUrl(name);
    const match = typeof url === "string" ? url.match(/(?:^|[._-])([0-9a-f]{16,64})(?:[._-]|$)/i) : null;
    return match ? match[1].toLowerCase() : "";
  }

  if (!global.__gaiusResourceContentHash) {
    const hash = assetHash("vanilla-assets.pack.gz") || assetHash("vanilla-assets.pack")
      || (site && typeof site.id === "string" ? site.id : "");
    if (hash) global.__gaiusResourceContentHash = hash;
  }

  function hasAsset(name) {
    return assetUrl(name) !== null;
  }

  // A gzip-only WebAssembly GC module of the site (build-pages-site.py): the URL the Service
  // Worker inflates it under, or null. Under the worker the page asks for that URL and gets
  // the stored module as application/wasm (compileStreaming keeps V8's code cache with the
  // stored entry); without one the page inflates assets["<name>.gz"] itself.
  function inflateUrl(name) {
    const inflate = site && site.inflate && typeof site.inflate === "object" ? site.inflate : null;
    return inflate && typeof inflate[name] === "string" && hasAsset(name + ".gz")
      ? new URL(inflate[name], loc.href).href : null;
  }

  function hasModule(name) {
    return hasAsset(name) || inflateUrl(name) !== null;
  }

  function serviceWorkerControlled() {
    return !!(nav.serviceWorker && nav.serviceWorker.controller);
  }

  // Whether the host compresses large text responses on the wire. Measured on a visit without
  // the Service Worker (its responses report decoded sizes); remembered per browser.
  function siteUsesGzipFallback() {
    if (!site) return false;
    const forced = params.get("gaiusSiteGzip");
    if (forced === "1") return true;
    if (forced === "0") return false;
    return storageGet(SITE_GZIP_KEY) === "1";
  }

  const useGzip = siteUsesGzipFallback();
  let assetsReadyPromise = Promise.resolve();

  async function gunzipToBlobUrl(url, type) {
    const response = await global.fetch(url, {cache: "force-cache"});
    if (!response.ok || !response.body) throw new Error("HTTP " + response.status + " while loading " + url);
    const sniffed = await sniffGzipStream(response.body);
    const stream = sniffed.gzip === false ? sniffed.stream
      : sniffed.stream.pipeThrough(new global.DecompressionStream("gzip"));
    const blob = await new global.Response(stream).blob();
    const source = type === "text/javascript"
      ? "\n//# sourceURL=" + new URL(url, loc.href).href.replace(/[\r\n\u2028\u2029]/g, "") + "\n" : "";
    return global.URL.createObjectURL(new global.Blob([blob, source], {type}));
  }

  // --- file payloads -------------------------------------------------------------------------

  // A dist opened from disk cannot fetch its siblings, but it can run them as scripts: each
  // binary file the page needs has a <file>.js next to it that stores the file's bytes
  // (gzip-compressed unless the file already is) as base64 in
  // globalThis.__gaiusFilePayloadData[<file>]. window.__gaiusFilePayloads (written into the page
  // with the WebAssembly GC dist) lists them: {<file>: {script, bytes, gzip}}.
  const FILE_PAYLOAD_STORE = "__gaiusFilePayloadData";
  const filePayloadLoads = new Map();

  // The client module is consumed once. Drop its compressed source after the Blob/Response
  // owns the stream so a portable/file page does not retain both compressed and executable bytes.
  function releaseClientPayload(name) {
    if (name !== "classes.wasm") return;
    filePayloadLoads.delete(name);
    try {
      if (global.__gaiusPortablePayloadBytes) delete global.__gaiusPortablePayloadBytes[name];
    } catch (_) {
      // A host may expose the payload table as a read-only object.
    }
  }

  function filePayloads() {
    const payloads = global.__gaiusFilePayloads;
    // Wasm-only portable pages retain the staged site's __gaiusSite metadata,
    // but their sibling assets are embedded in the document.  Treat the
    // portable form as a file-payload page even when site metadata is present.
    // A single file embeds its payloads on any origin (file://, https://gaius.fun/...): only a
    // plain dist opened from disk needs the file: check, a site form fetches its siblings.
    return (isPortable() || (isFile && !site)) && payloads && typeof payloads === "object" &&
      payloads;
  }

  function hasFilePayload(name) {
    const payloads = filePayloads();
    return !!(payloads && payloads[name] && typeof payloads[name].script === "string");
  }

  function decodeBase64(text) {
    if (typeof Uint8Array.fromBase64 === "function") return Uint8Array.fromBase64(text);
    const binary = global.atob(text);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  function gunzipStream(bytes) {
    const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const blob = new global.Blob([view]);
    // Some hosts transparently decode .gz responses but retain the compressed URL.
    // Decompress only when the payload still has the gzip magic bytes.
    if (view.byteLength < 2 || view[0] !== 0x1f || view[1] !== 0x8b) return blob.stream();
    return blob.stream().pipeThrough(new global.DecompressionStream("gzip"));
  }

  function isGzipBytes(bytes) {
    const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    return view.byteLength >= 2 && view[0] === 0x1f && view[1] === 0x8b;
  }

  // The payload bytes as stored (for a gzip payload: still compressed).
  function filePayloadBytes(name) {
    if (filePayloadLoads.has(name)) return filePayloadLoads.get(name);
    const entry = hasFilePayload(name) ? filePayloads()[name] : null;
    const portableBytes = global.__gaiusPortablePayloadBytes && global.__gaiusPortablePayloadBytes[name];
    if (portableBytes) {
      const promise = Promise.resolve(portableBytes).then((value) => value instanceof Uint8Array ? value : new Uint8Array(value));
      filePayloadLoads.set(name, promise);
      return promise;
    }
    const inline = global[FILE_PAYLOAD_STORE] && global[FILE_PAYLOAD_STORE][name];
    const promise = inline ? Promise.resolve(decodeBase64(inline)) : (!entry || !doc || !doc.createElement
      ? Promise.reject(new Error("This page has no file payload for " + name))
      : new Promise((resolve, reject) => {
        const element = doc.createElement("script");
        element.src = entry.script;
        element.async = true;
        element.setAttribute("data-gaius-file-payload", name);
        element.onload = () => {
          const store = global[FILE_PAYLOAD_STORE] || {};
          const text = store[name];
          // The text can be tens of megabytes: keep no reference once it is decoded.
          delete store[name];
          if (element.parentNode) element.parentNode.removeChild(element);
          if (typeof text !== "string") {
            reject(new Error(entry.script + " did not provide " + name));
            return;
          }
          try {
            resolve(decodeBase64(text));
          } catch (error) {
            reject(error);
          }
        };
        element.onerror = () => reject(new Error("Could not load " + entry.script));
        (doc.head || doc.documentElement).appendChild(element);
      }));
    promise.catch((error) => record("file-payload-failed", name + ": " + (error && error.message)));
    filePayloadLoads.set(name, promise);
    return promise;
  }

  // The file itself (inflated when its payload is gzip), as a Blob URL of the given type.
  function filePayloadUrl(name, type) {
    return filePayloadBytes(name).then((bytes) => {
      const entry = (filePayloads() && filePayloads()[name]) || {gzip: false};
      const body = entry.gzip ? new global.Response(gunzipStream(bytes)).blob() : new global.Blob([bytes]);
      return Promise.resolve(body).then((blob) => {
        const url = global.URL.createObjectURL(new global.Blob([blob], {type}));
        (global.__gaiusPortablePayloadUrls || (global.__gaiusPortablePayloadUrls = {}))[name] = url;
        return url;
      });
    });
  }

  // The file itself as bytes the caller owns (it may transfer them): the integrated server Worker
  // of a page opened from disk cannot read its siblings, so BrowserSingleplayerClient passes it
  // the bootstrap, the server and its runtime scripts read this way.
  function filePayload(name) {
    return filePayloadBytes(name).then((bytes) => {
      if (!filePayloads()[name].gzip) return bytes.slice();
      return new global.Response(gunzipStream(bytes)).arrayBuffer().then((buffer) => new Uint8Array(buffer));
    });
  }

  // Optional build-time bootstrap snapshot. The snapshot is an immutable transport envelope for
  // vanilla data entries; Java still owns registry construction until a profile-specific hook
  // proves that the decoded values can be adopted without changing behaviour.
  const INIT_SNAPSHOT = "gaius-init-snapshot.bin";
  const INIT_SNAPSHOT_MAGIC = "GAIUSIS1";
  let initSnapshotState = {status: "absent", entries: 0, profile: null, error: null};

  function parseInitSnapshot(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    for (let index = 0; index < INIT_SNAPSHOT_MAGIC.length; index++) {
      if (bytes[index] !== INIT_SNAPSHOT_MAGIC.charCodeAt(index)) throw new Error("boot snapshot magic mismatch");
    }
    if (bytes.length < 20) throw new Error("boot snapshot header is truncated");
    const version = view.getUint16(8, true);
    if (version !== 1) throw new Error("unsupported boot snapshot version: " + version);
    const profileLength = view.getUint16(10, true);
    const entryCount = view.getUint32(12, true);
    const payloadLength = view.getUint32(16, true);
    let cursor = 20;
    if (cursor + profileLength > bytes.length) throw new Error("boot snapshot profile is truncated");
    const profile = new global.TextDecoder().decode(bytes.subarray(cursor, cursor + profileLength));
    cursor += profileLength;
    const records = [];
    for (let index = 0; index < entryCount; index++) {
      if (cursor + 42 > bytes.length) throw new Error("boot snapshot record is truncated");
      const nameLength = view.getUint16(cursor, true);
      const offset = view.getUint32(cursor + 2, true);
      const length = view.getUint32(cursor + 6, true);
      const digest = bytes.slice(cursor + 10, cursor + 42);
      cursor += 42;
      if (cursor + nameLength > bytes.length) throw new Error("boot snapshot name is truncated");
      const name = new global.TextDecoder().decode(bytes.subarray(cursor, cursor + nameLength));
      cursor += nameLength;
      records.push({name, offset, length, digest});
    }
    if (cursor + payloadLength !== bytes.length) throw new Error("boot snapshot payload length mismatch");
    if (payloadLength > 0) {
      for (const record of records) {
        if (record.offset + record.length > payloadLength) throw new Error("boot snapshot range is invalid: " + record.name);
      }
    }
    return {version, profile, records, payload: bytes.subarray(cursor)};
  }

  function initSnapshotSource() {
    if (params.get("gaiusInitSnapshot") === "0") return null;
    if (hasFilePayload(INIT_SNAPSHOT)) return filePayload(INIT_SNAPSHOT);
    const mapped = assetUrl(INIT_SNAPSHOT);
    if (!mapped || typeof global.fetch !== "function") return null;
    const url = new URL(mapped, loc.href);
    return global.fetch(url, {cache: "force-cache"}).then(response => {
      if (!response.ok) throw new Error("HTTP " + response.status + " while loading boot snapshot");
      return response.arrayBuffer();
    }).then(buffer => new Uint8Array(buffer));
  }

  const initSnapshotPromise = (() => {
    const source = initSnapshotSource();
    if (!source) return Promise.resolve(initSnapshotState);
    initSnapshotState = {status: "loading", entries: 0, profile: null, error: null};
    return Promise.resolve(source).then(parseInitSnapshot).then(snapshot => {
      if (typeof global.__gaiusProfileId === "string"
          && snapshot.profile !== global.__gaiusProfileId) {
        throw new Error("boot snapshot profile mismatch: " + snapshot.profile);
      }
      initSnapshotState = {status: "decoded", entries: snapshot.records.length,
        profile: snapshot.profile, error: null, snapshot};
      global.__gaiusInitSnapshotState = initSnapshotState;
      mark("init-snapshot");
      return initSnapshotState;
    }).catch(error => {
      initSnapshotState = {status: "invalid", entries: 0, profile: null,
        error: String(error && error.message || error)};
      global.__gaiusInitSnapshotState = initSnapshotState;
      record("init-snapshot-invalid", initSnapshotState.error);
      return initSnapshotState;
    });
  })();
  global.__gaiusInitSnapshotReady = initSnapshotPromise;
  global.__gaiusInitSnapshotState = initSnapshotState;
  global.__gaiusValidateInitSnapshot = async (root) => {
    const state = await initSnapshotPromise;
    const snapshot = state && state.snapshot;
    if (!snapshot || state.status === "invalid") return state;
    const subtle = global.crypto && global.crypto.subtle;
    if (!subtle || typeof subtle.digest !== "function") {
      state.status = "unverified";
      state.error = "WebCrypto SHA-256 unavailable";
      global.__gaiusInitSnapshotState = state;
      return state;
    }
    for (const entry of snapshot.records) {
      const range = root && root.index && root.index[entry.name];
      if (!Array.isArray(range) || range[1] !== entry.length) {
        state.status = "mismatch";
        state.error = "missing or differently sized entry: " + entry.name;
        global.__gaiusInitSnapshotState = state;
        return state;
      }
      const start = root.dataOffset + range[0];
      const content = root.bytes.subarray(start, start + range[1]);
      const digest = new Uint8Array(await subtle.digest("SHA-256", content));
      if (digest.length !== entry.digest.length
          || digest.some((value, index) => value !== entry.digest[index])) {
        state.status = "mismatch";
        state.error = "entry hash mismatch: " + entry.name;
        global.__gaiusInitSnapshotState = state;
        return state;
      }
    }
    state.status = "verified";
    state.error = null;
    state.verifiedEntries = snapshot.records.length;
    global.__gaiusInitSnapshotState = state;
    record("init-snapshot-verified", String(snapshot.records.length));
    return state;
  };

  // Optional build-time atlas prebake (gaius-atlas-prebake.bin.gz, port/tools/atlasprebake): the mip
  // chains and atlas layouts the vanilla classes produced for the vanilla pack. BrowserAtlasPrebake
  // replays an entry only for inputs identical to the recorded ones; this loader only checks the
  // envelope (magic, version, lengths, profile, body SHA-256) and publishes the inflated bytes as
  // __gaiusAtlasPrebake {status: "verified", bytes}. ?gaiusAtlasPrebake=0 skips it, =verify makes
  // Java run vanilla and compare.
  const ATLAS_PREBAKE = "gaius-atlas-prebake.bin.gz";
  const ATLAS_PREBAKE_MAGIC = "GAIUSAP1";
  const atlasPrebakeParam = params.get("gaiusAtlasPrebake");
  const atlasPrebakeMode = atlasPrebakeParam === "0" || atlasPrebakeParam === "off" ? 0
    : atlasPrebakeParam === "verify" ? 2 : 1;
  global.__gaiusAtlasPrebake = {status: "absent", mode: atlasPrebakeMode, profile: null, bytes: null, error: null};

  function atlasPrebakeSource() {
    if (atlasPrebakeMode === 0) return null;
    if (hasFilePayload(ATLAS_PREBAKE)
        || (global.__gaiusPortablePayloadBytes && global.__gaiusPortablePayloadBytes[ATLAS_PREBAKE])) {
      return filePayloadBytes(ATLAS_PREBAKE);
    }
    const mapped = assetUrl(ATLAS_PREBAKE);
    if (!mapped || typeof global.fetch !== "function") return null;
    return global.fetch(new URL(mapped, loc.href), {cache: "force-cache"}).then(response => {
      if (!response.ok) throw new Error("HTTP " + response.status + " while loading the atlas prebake");
      return response.arrayBuffer();
    }).then(buffer => new Uint8Array(buffer));
  }

  async function parseAtlasPrebake(compressed) {
    const bytes = new Uint8Array(await new global.Response(gunzipStream(compressed)).arrayBuffer());
    if (bytes.length < 56) throw new Error("atlas prebake is truncated");
    for (let index = 0; index < ATLAS_PREBAKE_MAGIC.length; index++) {
      if (bytes[index] !== ATLAS_PREBAKE_MAGIC.charCodeAt(index)) throw new Error("atlas prebake magic mismatch");
    }
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (view.getUint32(8, true) !== 1) throw new Error("unsupported atlas prebake version");
    const headerLength = view.getUint32(12, true);
    const bodyLength = view.getUint32(16, true);
    if (headerLength < 56 || headerLength + bodyLength !== bytes.length) throw new Error("atlas prebake length mismatch");
    const profileLength = view.getUint16(52, true);
    if (54 + profileLength > headerLength) throw new Error("atlas prebake profile is truncated");
    const profile = new global.TextDecoder().decode(bytes.subarray(54, 54 + profileLength));
    if (typeof global.__gaiusProfileId === "string" && profile !== global.__gaiusProfileId) {
      throw new Error("atlas prebake profile mismatch: " + profile);
    }
    const subtle = global.crypto && global.crypto.subtle;
    if (!subtle || typeof subtle.digest !== "function") {
      return {status: "unverified", profile, bytes: null, error: "WebCrypto SHA-256 unavailable"};
    }
    const digest = new Uint8Array(await subtle.digest("SHA-256", bytes.subarray(headerLength)));
    for (let index = 0; index < 32; index++) {
      if (digest[index] !== bytes[20 + index]) return {status: "mismatch", profile, bytes: null, error: "body sha256"};
    }
    return {status: "verified", profile, bytes, error: null};
  }

  const atlasPrebakePromise = (() => {
    const source = atlasPrebakeSource();
    if (!source) return Promise.resolve(global.__gaiusAtlasPrebake);
    global.__gaiusAtlasPrebake.status = "loading";
    return Promise.resolve(source).then(parseAtlasPrebake).then(result => {
      Object.assign(global.__gaiusAtlasPrebake, result);
      if (result.status === "verified") mark("atlas-prebake");
      else record("atlas-prebake-" + result.status, result.error);
      return global.__gaiusAtlasPrebake;
    }).catch(error => {
      Object.assign(global.__gaiusAtlasPrebake, {status: "invalid", bytes: null,
        error: String(error && error.message || error)});
      record("atlas-prebake-invalid", global.__gaiusAtlasPrebake.error);
      return global.__gaiusAtlasPrebake;
    });
  })();
  global.__gaiusAtlasPrebakeReady = atlasPrebakePromise;

  // Optional build-time unihex glyph table (gaius-unihex.bin.gz: gzip of a GAIUSUH1 table written by
  // dev.gaius.tools.UnihexTableBuilder from vanilla's own unihex loader). Java
  // (dev.gaius.browser.UnihexGlyphTable) takes a font zip's glyphs from it instead of inflating and
  // parsing the zip only when this page hashed the zip in the archive it serves and found the bytes
  // the table was built from. ?gaiusUnihexLazy=0 keeps the parser. The table is never released:
  // glyph rows are read from it when a glyph is baked.
  const UNIHEX_TABLE = "gaius-unihex.bin.gz";
  const UNIHEX_TABLE_MAGIC = "GAIUSUH1";

  function parseUnihexTable(bytes) {
    if (bytes.length < 20) throw new Error("unihex table is truncated");
    for (let index = 0; index < UNIHEX_TABLE_MAGIC.length; index++) {
      if (bytes[index] !== UNIHEX_TABLE_MAGIC.charCodeAt(index)) throw new Error("unihex table magic mismatch");
    }
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const version = view.getUint32(8, true);
    if (version !== 1) throw new Error("unsupported unihex table version: " + version);
    const count = view.getUint32(12, true);
    const headerLength = view.getUint32(16, true);
    if (headerLength > bytes.length) throw new Error("unihex table header is truncated");
    const zips = [];
    let cursor = 20;
    for (let index = 0; index < count; index++) {
      if (cursor + 2 > headerLength) throw new Error("unihex table entry is truncated");
      const pathLength = view.getUint16(cursor, true);
      if (cursor + 2 + pathLength > headerLength) throw new Error("unihex table path is truncated");
      const path = new global.TextDecoder().decode(bytes.subarray(cursor + 2, cursor + 2 + pathLength));
      cursor = (cursor + 2 + pathLength + 3) & ~3;
      if (cursor + 56 > headerLength) throw new Error("unihex table entry is truncated");
      zips.push({path, length: view.getUint32(cursor, true), digest: bytes.slice(cursor + 4, cursor + 36),
        verified: false});
      cursor += 56;
    }
    return zips;
  }

  // The archive the vanilla pack reads a resource from (AssetArchive: the core pack, then sounds).
  function archiveEntry(path) {
    const packs = [global.__gaiusVanillaAssets, global.__gaiusVanillaSounds];
    for (const root of packs) {
      if (!root || !root.bytes || !root.index || !Object.prototype.hasOwnProperty.call(root.index, path)) continue;
      const range = root.index[path];
      return Array.isArray(range) && range.length === 2 ? {root, offset: range[0], length: range[1]} : null;
    }
    return null;
  }

  async function verifyUnihexTable(state) {
    const subtle = global.crypto && global.crypto.subtle;
    if (!subtle || typeof subtle.digest !== "function") throw new Error("WebCrypto SHA-256 unavailable");
    let verified = 0;
    for (const zip of state.zips) {
      const entry = archiveEntry(zip.path);
      if (!entry || entry.length !== zip.length) continue;
      const start = entry.root.dataOffset + entry.offset;
      const content = entry.root.bytes.subarray(start, start + entry.length);
      const digest = new Uint8Array(await subtle.digest("SHA-256", content));
      if (digest.length !== zip.digest.length || digest.some((value, index) => value !== zip.digest[index])) continue;
      // Java re-checks that the archive still maps the path to this range of this pack.
      zip.root = entry.root;
      zip.offset = entry.offset;
      zip.verified = true;
      verified++;
    }
    return verified;
  }

  function unihexTableSource() {
    if (params.get("gaiusUnihexLazy") === "0") return null;
    if (hasFilePayload(UNIHEX_TABLE)) return filePayload(UNIHEX_TABLE);
    // Only a site (build-pages-site.py) or the file payloads name the table; a plain dist parses.
    const mapped = assetUrl(UNIHEX_TABLE);
    if (!mapped || typeof global.fetch !== "function") return null;
    return global.fetch(new URL(mapped, loc.href), {cache: "force-cache"}).then(response => {
      if (!response.ok) throw new Error("HTTP " + response.status + " while loading the unihex table");
      return response.arrayBuffer().then(buffer => new Uint8Array(buffer));
    });
  }

  const unihexTablePromise = (() => {
    const disabled = params.get("gaiusUnihexLazy") === "0";
    let source = null;
    try {
      source = unihexTableSource();
    } catch (error) {
      source = Promise.reject(error);
    }
    if (!source) {
      global.__gaiusUnihexTable = {status: disabled ? "disabled" : "absent"};
      return Promise.resolve(global.__gaiusUnihexTable);
    }
    const started = nowMs();
    global.__gaiusUnihexTable = {status: "loading"};
    return Promise.resolve(source).then(compressed => compressed
      // gunzipStream passes bytes a host already decoded through unchanged.
      ? new global.Response(gunzipStream(compressed)).arrayBuffer().then(buffer => new Uint8Array(buffer))
      : null).then(bytes => {
      if (!bytes) return {status: "absent"};
      const zips = parseUnihexTable(bytes);
      return vanillaAssetsDecoded().then(root => {
        if (!root) return {status: "no-vanilla-pack"};
        const state = {status: "verifying", bytes, zips};
        return verifyUnihexTable(state).then(verified => {
          state.status = "verified";
          state.verifiedZips = verified;
          state.ms = Math.round(nowMs() - started);
          mark("unihex-table");
          return state;
        });
      });
    }).catch(error => {
      const state = {status: "invalid", error: String(error && error.message || error)};
      record("unihex-table-invalid", state.error);
      return state;
    }).then(state => {
      global.__gaiusUnihexTable = state;
      return state;
    });
  })();
  global.__gaiusUnihexTableReady = unihexTablePromise;

  // Started at parse time, before the launcher reads these globals: its vanilla pack loader takes
  // the compressed pack as from the portable page, the hotpath and the shader toolchain loader
  // wait for __gaiusPortableAssetsReady and then read their (Blob) URLs.
  function wireFilePayloads() {
    if (!filePayloads() || typeof global.DecompressionStream !== "function") return;
    const waits = [];
    if (hasFilePayload("vanilla-assets.pack.gz") && !global.__gaiusVanillaAssetsCompressedPromise) {
      global.__gaiusVanillaAssetsCompressedPromise = filePayloadBytes("vanilla-assets.pack.gz");
    }
    if (hasFilePayload("gaius-shaderc.js") && hasFilePayload("gaius-spvc.js")
        && hasFilePayload("gaius-shaderc.wasm") && hasFilePayload("gaius-spvc.wasm")
        && !global.__gaiusShaderToolchainUrls) {
      const urls = {};
      const modules = [
        filePayloadUrl("gaius-shaderc.js", "text/javascript").then((url) => { urls.shadercJs = url; }),
        filePayloadUrl("gaius-spvc.js", "text/javascript").then((url) => { urls.spvcJs = url; }),
        filePayloadUrl("gaius-shaderc.wasm", "application/wasm").then((url) => { urls.shadercWasm = url; }),
        filePayloadUrl("gaius-spvc.wasm", "application/wasm").then((url) => { urls.spvcWasm = url; }),
      ];
      // Published once complete: the toolchain loader reads it after __gaiusPortableAssetsReady.
      waits.push(Promise.all(modules).then(() => { global.__gaiusShaderToolchainUrls = urls; }));
    }
    // Baked shader results (gaius-shader-prebake.bin, written by bake-shader-cache.mjs): the toolchain
    // loader primes its result cache with them; a page without the payload compiles as before.
    if (hasFilePayload("gaius-shader-prebake.bin") && !global.__gaiusShaderPrebake) {
      global.__gaiusShaderPrebake = filePayloadBytes("gaius-shader-prebake.bin");
    }
    if (hasFilePayload("gaius-hotpath.wasm") && !global.__gaiusHotpathWasmUrl) {
      waits.push(filePayloadUrl("gaius-hotpath.wasm", "application/wasm").then((url) => { global.__gaiusHotpathWasmUrl = url; }));
    }
    if (waits.length && !global.__gaiusPortableAssetsReady) {
      global.__gaiusPortableAssetsReady = Promise.all(waits).then(() => {
        mark("file-payloads");
      });
    }
  }

  // --- client runtime ---------------------------------------------------------------------------

  const WASM_CLIENT_FILES = ["classes.wasm", "gaius-wasm-boot.js", "gaius-wasm-gc-runtime.js", "gaius-jspi.js"];
  const WASM_SERVER_FILES = ["singleplayer-server.wasm", "gaius-wasm-gc-runtime.js", "gaius-jspi.js", "gaius-wasm-worker.js"];
  // WebAssembly.validate probes for what the TeaVM WebAssembly GC output needs: a struct type
  // created with struct.new (GC) and a try_table with catch_all (exception handling with exnref).
  const WASM_GC_PROBE = [0, 97, 115, 109, 1, 0, 0, 0, 1, 10, 2, 95, 1, 127, 0, 96, 0, 1, 99, 0,
    3, 2, 1, 1, 10, 9, 1, 7, 0, 65, 0, 251, 0, 0, 11];
  const WASM_TRY_TABLE_PROBE = [0, 97, 115, 109, 1, 0, 0, 0, 1, 4, 1, 96, 0, 0, 3, 2, 1, 0,
    10, 10, 1, 8, 0, 31, 64, 1, 2, 0, 11, 11];

  function validates(wasm, probe) {
    try {
      return wasm.validate(new Uint8Array(probe)) === true;
    } catch (_) {
      return false;
    }
  }

  function detectWasmFeatures() {
    const wasm = global.WebAssembly;
    const features = {
      webAssembly: !!wasm && typeof wasm.validate === "function",
      gc: false,
      tryTable: false,
      jspi: false,
      decompressionStream: typeof global.DecompressionStream === "function",
    };
    if (!features.webAssembly) return features;
    features.gc = validates(wasm, WASM_GC_PROBE);
    features.tryTable = validates(wasm, WASM_TRY_TABLE_PROBE);
    features.jspi = typeof wasm.Suspending === "function" && typeof wasm.promising === "function";
    return features;
  }

  function distributionForm() {
    if (isPortable()) return "portable";
    if (site) return "site";
    return isFile ? "file" : "dev";
  }

  // The window.__gaiusRuntimes descriptor of a dist: {wasm: {client, server?}}.
  function distRuntimes() {
    const runtimes = global.__gaiusRuntimes;
    return runtimes && typeof runtimes === "object" && runtimes.wasm && typeof runtimes.wasm === "object"
      ? runtimes.wasm : null;
  }

  function wasmClientBuilt(form) {
    if (form === "site") return WASM_CLIENT_FILES.every(hasModule);
    if (form === "portable") return !!(global.__gaiusPortableRuntimes && global.__gaiusPortableRuntimes.wasm);
    if (!distRuntimes()) return false;
    // From disk the module only arrives through its script payload.
    return form !== "file" || hasFilePayload("classes.wasm");
  }

  function missingWasmFeatures(features, inflatesOnPage) {
    if (!features.webAssembly) return ["webassembly"];
    const missing = [];
    if (!features.gc) missing.push("gc");
    if (!features.tryTable) missing.push("try-table");
    if (!features.jspi) missing.push("jspi");
    if (inflatesOnPage && !features.decompressionStream) missing.push("decompression-stream");
    return missing;
  }

  function jsClientBuilt(form) {
    if (form === "site") {
      const compressed = typeof global.DecompressionStream === "function";
      return (hasAsset("classes.js") || (compressed && hasAsset("classes.js.gz")))
        && (hasAsset("singleplayer-server.js") || (compressed && hasAsset("singleplayer-server.js.gz")));
    }
    if (form === "portable") return typeof global.__gaiusPortableLoadJsClient === "function"
      || !!(global.__gaiusPortableRuntimes && global.__gaiusPortableRuntimes.js);
    return !!(global.__gaiusRuntimes && global.__gaiusRuntimes.js);
  }

  function selectRuntime() {
    const fromUrl = String(params.get("runtime") || "").toLowerCase();
    const form = distributionForm();
    const features = detectWasmFeatures();
    const built = wasmClientBuilt(form);
    const jsBuilt = jsClientBuilt(form);
    const selection = {requested: "wasm", source: fromUrl === "wasm" ? "url" : jsBuilt ? "auto" : "wasm-only",
      selected: "wasm", fallback: false, reason: jsBuilt ? "auto-wasm" : "wasm-only", form, wasmBuilt: built,
      jsBuilt, features, at: nowMs()};
    const missing = built ? [] : ["wasm-build"];
    missing.push(...missingWasmFeatures(features, form === "file" || form === "portable"
      || (form === "site" && inflateUrl("classes.wasm") !== null && !serviceWorkerControlled())));
    if (fromUrl === "js" && jsBuilt) {
      selection.selected = "js";
      selection.reason = "url-js";
    } else if (missing.length) {
      selection.selected = jsBuilt ? "js" : "unsupported";
      selection.fallback = jsBuilt;
      selection.reason = "missing:" + missing.join(",");
    } else if (fromUrl && fromUrl !== "wasm" && fromUrl !== "auto") record("runtime-invalid", fromUrl);
    return selection;
  }

  // iPhone and iPad browsers all run on WebKit, so the "use Chrome" advice does not apply
  // there; iPadOS reports a desktop Mac user agent and only its touch points tell it apart.
  function appleMobileWebKit() {
    const nav = global.navigator || {};
    const ua = String(nav.userAgent || "");
    if (/\b(?:iPhone|iPad|iPod)\b/.test(ua)) return true;
    return /\bMacintosh\b/.test(ua) && (Number(nav.maxTouchPoints) || 0) > 1;
  }

  function unsupportedMessageKey(selection) {
    const browserGap = selection.reason !== "missing:wasm-build";
    return browserGap && appleMobileWebKit() ? "unsupportedIosMessage" : "unsupportedBrowserMessage";
  }

  const clientRuntime = selectRuntime();
  global.__gaiusRuntimeSelection = clientRuntime;
  record("runtime", clientRuntime.selected + " (" + clientRuntime.reason + ")");
  if (clientRuntime.selected === "unsupported") {
    record("runtime-unsupported", clientRuntime.reason);
    global.__gaiusUnsupportedBrowser = {reason: clientRuntime.reason, form: clientRuntime.form,
      features: clientRuntime.features, messageKey: unsupportedMessageKey(clientRuntime)};
    if (doc && doc.documentElement) {
      doc.documentElement.setAttribute("data-gaius-unsupported", "wasm-only");
    }
    global.__gaiusBoot = {version: BOOT_VERSION, runtime: clientRuntime, events,
      unsupportedBrowser: global.__gaiusUnsupportedBrowser, handlesWasmFailure: false};
    return;
  }
  mark("runtime-" + clientRuntime.selected);

  function siblingUrl(name) {
    return new URL(assetUrl(name) || name, loc.href).href;
  }

  // A Response of the module type: the loader compiles it while it streams.
  function wasmResponse(body) {
    return new global.Response(body, {headers: {"Content-Type": "application/wasm"}});
  }

  // Bytes of a payload that streams into its consumer, shown by the boot screen
  // (port/web/launcher/boot-art.js reads window.__gaiusLoadBytes[key] = {loaded, total}). The
  // count rides on reads that happen anyway; nothing is buffered or copied for it.
  function loadMeter(key, total) {
    const meters = global.__gaiusLoadBytes || (global.__gaiusLoadBytes = {});
    const entry = meters[key] = {loaded: 0, total: total > 0 ? total : 0};
    return (count) => { entry.loaded += count; };
  }

  // Inspect only the first two bytes, then replay the consumed chunks through a fresh stream. This
  // handles proxies that strip Content-Encoding after transparently decoding a .gz response,
  // without buffering the module before compileStreaming. onBytes (optional) hears the size of
  // every chunk read from the body.
  function sniffGzipStream(body, onBytes) {
    if (!body || typeof body.getReader !== "function" || typeof global.ReadableStream !== "function") {
      return Promise.resolve({stream: body, gzip: null});
    }
    const reader = body.getReader();
    const chunks = [];
    const magic = [];
    function finish(done) {
      let queue = chunks.slice();
      let reading = false;
      const stream = new global.ReadableStream({
        pull(controller) {
          if (reading) return;
          while (queue.length && controller.desiredSize > 0) controller.enqueue(queue.shift());
          if (queue.length) return;
          if (done) { controller.close(); return; }
          reading = true;
          return reader.read().then((part) => {
            reading = false;
            if (part.done) { done = true; controller.close(); }
            else {
              if (onBytes) onBytes(part.value.byteLength);
              controller.enqueue(part.value);
            }
          }, (error) => { reading = false; controller.error(error); });
        },
        cancel(reason) { return reader.cancel(reason); },
      });
      return {stream, gzip: magic.length >= 2 && magic[0] === 0x1f && magic[1] === 0x8b};
    }
    function read() {
      return reader.read().then((part) => {
        if (part.done) return finish(true);
        const chunk = part.value instanceof Uint8Array ? part.value : new Uint8Array(part.value);
        if (onBytes) onBytes(chunk.byteLength);
        chunks.push(chunk);
        for (let i = 0; i < chunk.length && magic.length < 2; i++) magic.push(chunk[i]);
        return magic.length >= 2 ? finish(false) : read();
      });
    }
    return read();
  }

  function requestWasm(url, gzip) {
    const preload = global.__gaiusWasmHttpPreload;
    let request;
    if (!gzip && preload && preload.url === url && preload.response && typeof preload.response.then === "function") {
      global.__gaiusWasmHttpPreload = null;
      // The shell uses fetch(), not a link preload: retain its FetchManager metadata
      // handler and consume the very same Response rather than doing a second fetch.
      request = preload.response.then(response => response || global.fetch(url, {cache: "force-cache"}));
    } else {
      request = global.fetch(url, site ? {cache: "force-cache"} : {});
    }
    return request;
  }

  function fetchWasm(url, gzip) {
    return requestWasm(url, gzip).then((response) => {
      if (!response.ok || !response.body) {
        throw new Error("HTTP " + response.status + " while loading the WebAssembly GC client");
      }
      // Keep the compressed response streaming all the way into compileStreaming. The old
      // arrayBuffer path serialized download, inflate and compilation and also doubled peak RAM.
      const length = response.headers && response.headers.get ? Number(response.headers.get("Content-Length")) : 0;
      const type = response.headers && response.headers.get ? String(response.headers.get("Content-Type") || "").toLowerCase() : "";
      const encoding = response.headers && response.headers.get ? String(response.headers.get("Content-Encoding") || "").toLowerCase() : "";
      // Fetch already decodes HTTP Content-Encoding. Keep a correctly typed network Response
      // intact so streamed compilation can use the response's URL and native code cache.
      if (gzip && type.indexOf("application/wasm") === 0 && /\b(?:gzip|br|deflate)\b/.test(encoding)) return response;
      if (gzip) return sniffGzipStream(response.body, loadMeter(response.url || url, length)).then((sniffed) => wasmResponse(
        sniffed.gzip === false
          ? sniffed.stream
          : sniffed.stream.pipeThrough(new global.DecompressionStream("gzip"))));
      // The fetched Response itself keeps V8's code cache for streamed modules; a host that does
      // not serve .wasm as application/wasm gets the body rewrapped (compileStreaming needs it).
      return type.indexOf("application/wasm") === 0 ? response : wasmResponse(response.body);
    });
  }

  function fetchBrotliWasm(url) {
    // Fetch decodes Content-Encoding before compilation. Keep the real response
    // and the shell's early request so native code caching still uses one URL.
    const fallback = () => site && hasAsset("classes.wasm")
      ? fetchWasm(siblingUrl("classes.wasm") + "?gaiusWasmHttp=1", false)
      : fetchWasm(siblingUrl("classes.wasm.gz"), true);
    global.__gaiusWasmSourceFallback = null;
    return requestWasm(url, false).then((response) => {
      const headers = response.headers;
      const encoding = headers && headers.get ? String(headers.get("Content-Encoding") || "") : "";
      if (!response.ok || !response.body || !/\bbr\b/i.test(encoding)) {
        try {
          const cancelled = response.body && response.body.cancel();
          if (cancelled && typeof cancelled.catch === "function") cancelled.catch(() => {});
        } catch (_) {}
        record("wasm-brotli-fallback", encoding || "missing content-encoding");
        return null;
      }
      record("wasm-brotli", url);
      let retried = false;
      global.__gaiusWasmSourceFallback = (error) => {
        global.__gaiusWasmSourceFallback = null;
        if (retried) throw error;
        retried = true;
        record("wasm-brotli-fallback", error && error.message);
        return fallback();
      };
      const type = headers && headers.get ? String(headers.get("Content-Type") || "").toLowerCase() : "";
      return type.indexOf("application/wasm") === 0 ? response : wasmResponse(response.body);
    }, (error) => {
      record("wasm-brotli-fallback", error && error.message);
      return null;
    }).then((response) => response || fallback());
  }

  // The portable page decodes its embedded module itself (build-portable-html.py); every other
  // form loads the loader scripts by URL and starts the module now, while storage initialises.
  function wireWasmClient() {
    if (clientRuntime.form === "portable") return;
    global.__gaiusClassesUrl = siblingUrl("gaius-wasm-boot.js");
    global.__gaiusWasmRuntimeUrl = siblingUrl("gaius-wasm-gc-runtime.js");
    global.__gaiusJspiUrl = siblingUrl("gaius-jspi.js");
    // Start support scripts while the large client module is downloading. The Wasm shim awaits
    // this promise, so runtime setup no longer sits serially after sourceReady.
    global.__gaiusWasmSupportReady = Promise.all([
      loadClassicScript(global.__gaiusWasmRuntimeUrl),
      loadClassicScript(global.__gaiusJspiUrl),
    ]).then(() => true, (error) => {
      record("wasm-support-preload-failed", error && error.message);
      return false;
    });
    let source;
    if (clientRuntime.form === "file") {
      // Inflated while it compiles; no fetch from disk.
      source = filePayloadBytes("classes.wasm").then((bytes) => {
        const result = filePayloads()["classes.wasm"].gzip ? wasmResponse(gunzipStream(bytes)) : bytes;
        releaseClientPayload("classes.wasm");
        return result;
      });
    } else if (site && hasAsset("classes.wasm.br")) {
      source = fetchBrotliWasm(siblingUrl("classes.wasm.br") + "?gaiusWasmHttp=1");
    } else if (site && hasAsset("classes.wasm")) {
      // A real immutable URL retains the HTTP response identity needed by V8's code cache.
      // Synthetic SW/page inflation responses cannot supply that identity.
      source = fetchWasm(siblingUrl("classes.wasm") + "?gaiusWasmHttp=1", false);
    } else if (inflateUrl("classes.wasm")) {
      const inflateOnPage = () => fetchWasm(siblingUrl("classes.wasm.gz"), true);
      if (serviceWorkerControlled()) {
        record("wasm-inflate", "service-worker");
        source = fetchWasm(inflateUrl("classes.wasm"), false).catch((error) => {
          record("wasm-inflate-fallback", error && error.message);
          return inflateOnPage();
        });
      } else {
        record("wasm-inflate", "page");
        source = inflateOnPage();
      }
    } else if (useGzip && hasAsset("classes.wasm.gz") && typeof global.DecompressionStream === "function") {
      record("wasm-gzip-fallback", assetUrl("classes.wasm.gz"));
      source = fetchWasm(siblingUrl("classes.wasm.gz"), true);
    } else {
      source = fetchWasm(siblingUrl("classes.wasm"), false);
    }
    source.catch(() => {});
    global.__gaiusWasmSource = source;
    // Load the tiny Wasm boot shim while the document is still parsing. It starts the
    // TeaVM compile from the already-streaming Response; the launcher reuses this load
    // instead of inserting the same script again after storage is ready.
    if (doc && doc.createElement && !global.__gaiusWasmBootReady) {
      const bootLoad = loadClassicScript(global.__gaiusClassesUrl).then(() => true, async (error) => {
        record("wasm-boot-load-failed", error && error.message);
        if (!clientRuntime.jsBuilt) return false;
        await startJsClientInstead(error);
        return true;
      });
      global.__gaiusWasmBootReady = bootLoad;
    }
  }

  if (clientRuntime.selected !== "unsupported" && (clientRuntime.form === "file" || clientRuntime.form === "portable")) wireFilePayloads();

  // Early (parse-time) URLs: the wasm hotpath loader and the client script read these.
  if (clientRuntime.selected !== "unsupported" && site) {
    if (hasAsset("gaius-hotpath.wasm") && !global.__gaiusHotpathWasmUrl) {
      global.__gaiusHotpathWasmUrl = new URL(assetUrl("gaius-hotpath.wasm"), loc.href).href;
    }
    if (clientRuntime.selected === "js" && hasAsset("classes.js")) {
      global.__gaiusClassesUrl = siblingUrl("classes.js");
    }
  }
  if (clientRuntime.selected === "js" && clientRuntime.form !== "portable") {
    if (site && !hasAsset("classes.js") && hasAsset("classes.js.gz")) {
      global.__gaiusJsClientSourceReady = jsClientUrl().then((url) => { global.__gaiusClassesUrl = url; });
      assetsReadyPromise = Promise.all([assetsReadyPromise, global.__gaiusJsClientSourceReady]);
    } else global.__gaiusClassesUrl = siblingUrl("classes.js");
  }
  if (clientRuntime.selected === "wasm") wireWasmClient();
  if (clientRuntime.form === "portable" && clientRuntime.selected === "js"
      && typeof global.__gaiusPortableLoadJsClient === "function") {
    global.__gaiusJsClientSourceReady = global.__gaiusPortableLoadJsClient().then((url) => {
      global.__gaiusClassesUrl = url;
    });
    assetsReadyPromise = Promise.all([assetsReadyPromise, global.__gaiusJsClientSourceReady]);
  }

  // Called by the launcher once its own singleplayer URL block ran (that block is regenerated by
  // postprocess-index-html.py, so the site URLs are applied after it instead of inside it).
  function applyAssetUrls() {
    if (!site || isPortable()) return false;
    if (hasAsset("singleplayer-server-worker.js")) {
      global.__gaiusSingleplayerWorkerUrl = new URL(assetUrl("singleplayer-server-worker.js"), loc.href).href;
    }
    if (hasAsset("singleplayer-server.js")) {
      global.__gaiusSingleplayerServerUrl = siblingUrl("singleplayer-server.js");
    }
    if (hasAsset("singleplayer-server.js.gz")) {
      global.__gaiusSingleplayerServerGzipUrl = siblingUrl("singleplayer-server.js.gz");
    }
    if (hasAsset("singleplayer-server.wasm")) {
      global.__gaiusSingleplayerServerWasmUrl = new URL(assetUrl("singleplayer-server.wasm"), loc.href).href;
    }
    return true;
  }

  // --- script modules -------------------------------------------------------------------------

  const inline = global.__gaiusBootInline && typeof global.__gaiusBootInline === "object" ? global.__gaiusBootInline : {};
  const scriptLoads = new Map();

  function loadScript(name) {
    if (scriptLoads.has(name)) return scriptLoads.get(name);
    let promise;
    if (inline[name] === true) {
      promise = Promise.resolve(true);
    } else if (!doc || !doc.createElement) {
      promise = Promise.resolve(false);
    } else if (isPortable() || (isFile && !site && name.indexOf("runtime/quality/") !== 0
        && name.indexOf("runtime/webgpu/") !== 0)) {
      // A portable page inlines what it has. A dist opened from disk has no kernels (no Worker
      // from a file: URL, no kernels.json fetch), so their modules are not loaded there; the
      // quality layer and the WebGPU glue are plain script and load by tag like the Wasm boot shim.
      promise = Promise.resolve(false);
    } else {
      const url = assetUrl(name) || name;
      promise = new Promise((resolve) => {
        const element = doc.createElement("script");
        element.src = url;
        // Dynamically inserted scripts with async=false run in insertion order.
        element.async = false;
        element.setAttribute("data-gaius-boot-module", name);
        element.onload = () => resolve(true);
        element.onerror = () => {
          record("module-missing", name);
          resolve(false);
        };
        (doc.head || doc.documentElement).appendChild(element);
      });
    }
    scriptLoads.set(name, promise);
    return promise;
  }

  function kernelJobScripts() {
    if (site && Array.isArray(site.modules)) return site.modules.slice();
    if (Array.isArray(global.__gaiusKernelJobScripts)) return global.__gaiusKernelJobScripts.slice();
    return [];
  }

  // Started at parse time: these download while the launcher fetches the Wasm boot shim.
  const kernelScriptsReady = Promise.all(KERNEL_RUNTIME_SCRIPTS.concat(kernelJobScripts()).map(loadScript));
  const qualityReady = Promise.all(QUALITY_SCRIPTS.map(loadScript));

  function withTimeout(promise, ms) {
    return Promise.race([promise, new Promise((resolve) => global.setTimeout(resolve, ms))]);
  }

  // --- graphics backend -----------------------------------------------------
  //
  // ?gpu=webgpu|webgl2, else WebGPU where the browser offers it on a site with the automatic
  // runtime policy and on the portable page, else the page default window.__gaiusGpuDefault, else
  // webgl2. A page that does not try WebGPU says why in __gaiusGpuSelection.reason and on the
  // console ("Gaius graphics: ..."). For webgpu the page loads the WebGPU glue and the shader translator
  // (runtime/webgpu/: gpu-glue.js, shaderxlat-job.js, gaius-shaderxlat.wasm) and beforeMain()
  // waits, at most WEBGPU_INIT_TIMEOUT_MS, for the adapter, the device, the canvas format and the
  // translator. Java then picks the backend: the profile's render seam (Minecraft262BrowserPatcher.
  // patchPreferredGraphicsApi) tries WebGPU first and vanilla falls back to WebGL 2 when the
  // device is not ready. window.__gaiusGpuSelection / __gaiusBoot.gpu say what the page offered,
  // window.__gaiusGpuBackend ("webgpu" or "webgl2") what the game renders with.
  //   ?gpuInject=after-configure  fallback test: WebGPU fails after configuring the canvas, so
  //                               WebGL 2 must attach to a fresh canvas
  //   ?gpuInject=taint-canvas     the same for profiles without a WebGPU backend: the canvas holds
  //                               a "webgpu" context before the GL window is made
  //   ?gpuInject=lose-device      device-loss test: the glue destroys the device the game renders
  //                               with 3 s after the first frame, so the reload prompt must appear
  //   ?gpuPipelines=eager|lazy    pipeline creation of the glue (A/B runs; default lazy)
  //   ?gpuAllowFallback=1         accept a fallback (software) adapter, e.g. SwiftShader in CI
  //
  // A profile whose WebGPU backend compiles GLSL itself (the blaze3d front-end of 26.2 and
  // 1.21.11: shaderc in the browser, then the translator's link) also needs the shader toolchain
  // (gaius-shader-toolchain.js and its four modules next to index.html). Such a page names the
  // loader in window.__gaiusWebGpuShaderToolchain = {loader: "gaius-shader-toolchain.js?v=..."}
  // (make-wasm-page.py); it loads for webgpu only, at parse time, and beforeMain() waits for it
  // within the same budget. A page that loads the toolchain for every backend (renderpearl,
  // postprocess-index-html.py) already has window.__gaiusShaderToolchainReady.

  const GPUS = ["webgpu", "webgl2"];
  const GPU_INJECTIONS = ["after-configure", "taint-canvas", "lose-device"];
  const WEBGPU_SCRIPTS = ["runtime/webgpu/gpu-glue.js", "runtime/webgpu/shaderxlat-job.js",
    "runtime/webgpu/quality-post.js", "runtime/webgpu/shader-pack-store.js",
    "runtime/webgpu/shader-pack-compat.js", "runtime/webgpu/shader-pack.js"];
  const WEBGPU_TRANSLATOR = "runtime/webgpu/gaius-shaderxlat.wasm";
  const WEBGPU_INIT_TIMEOUT_MS = 15000;
  // How long a warm WGSL translation cache may delay graphics readiness (counted from the translator install).
  const TRANSLATION_CACHE_HEAD_START_MS = 200;

  // A WebGPU page module this page can load: a site asset, or a script the portable page inlined.
  function webGpuModuleAvailable(name) {
    return inline[name] === true || hasModule(name);
  }

  // Why this page cannot offer WebGPU on its own, as short reasons (empty when it can). Shown in
  // the selection's reason and on the console so a report from another machine names the cause.
  function webGpuUnavailableReasons() {
    const reasons = [];
    if (clientRuntime.selected !== "wasm") {
      reasons.push("client runtime is " + clientRuntime.selected + " (" + clientRuntime.reason + ")");
    }
    const gpu = global.navigator && global.navigator.gpu;
    if (!gpu) {
      reasons.push("navigator.gpu is not available" + (global.isSecureContext === false ? " (not a secure context)" : ""));
    } else if (typeof gpu.requestAdapter !== "function") {
      reasons.push("navigator.gpu.requestAdapter is not a function");
    }
    const scripts = WEBGPU_SCRIPTS.filter((name) => !webGpuModuleAvailable(name));
    if (scripts.length) reasons.push("missing " + scripts.join(", "));
    // A portable page announces its embedded translator from its body bootstrap, after this
    // script; prepareGpu() reports it when it never arrives.
    if (!isPortable() && !hasModule(WEBGPU_TRANSLATOR)) reasons.push("missing " + WEBGPU_TRANSLATOR);
    if (isPortable() && webGpuShaderToolchainConfig() && !global.__gaiusShaderToolchainReady) {
      reasons.push("this page form carries no shader toolchain");
    }
    return reasons;
  }

  function selectGpu() {
    const fromUrl = String(params.get("gpu") || "").toLowerCase();
    const fromPage = typeof global.__gaiusGpuDefault === "string" ? global.__gaiusGpuDefault.toLowerCase() : "";
    // A site with the automatic runtime policy and the portable page (the same WebAssembly GC
    // client, its WebGPU glue and translator inlined) prefer WebGPU where the browser offers it.
    // A Wasm-only portable page keeps the staged site's descriptor (policy "wasm-only"), so the
    // portable form is decided before the site policy; a Wasm-only site is automatic as well.
    const portableAuto = isPortable();
    const automatic = (site && (site.runtimePolicy === "auto" || site.runtimePolicy === "wasm-only")) || portableAuto;
    const unavailable = webGpuUnavailableReasons();
    let requested = automatic && unavailable.length === 0 ? "webgpu" : "webgl2";
    let source = automatic ? (portableAuto ? "portable" : "site") : "default";
    if (GPUS.indexOf(fromUrl) >= 0) {
      requested = fromUrl;
      source = "url";
    } else if (!automatic && GPUS.indexOf(fromPage) >= 0) {
      requested = fromPage;
      source = "page";
    }
    if (fromUrl && source !== "url") record("gpu-invalid", fromUrl);
    const inject = String(params.get("gpuInject") || "").toLowerCase();
    const pipelines = String(params.get("gpuPipelines") || "").toLowerCase();
    let reason = source + "-" + requested;
    if (requested === "webgl2" && automatic && source !== "url" && unavailable.length) {
      reason = "webgpu unavailable: " + unavailable.join("; ");
    } else if (requested === "webgl2" && source === "default") {
      reason = "default-webgl2 (this page form selects WebGPU only with ?gpu=webgpu)";
    }
    return {
      requested, source, selected: requested === "webgpu" ? "pending" : "webgl2", fallback: false,
      reason: reason.slice(0, 300), unavailable, inject: GPU_INJECTIONS.indexOf(inject) >= 0 ? inject : "",
      pipelines: pipelines === "eager" || pipelines === "lazy" ? pipelines : "",
      allowFallbackAdapter: params.get("gpuAllowFallback") === "1",
      initMs: null, at: nowMs(),
      backend: () => (typeof global.__gaiusGpuBackend === "string" ? global.__gaiusGpuBackend : null),
    };
  }

  const gpuSelection = selectGpu();
  global.__gaiusGpuSelection = gpuSelection;
  record("gpu", gpuSelection.requested + " (" + gpuSelection.source + ")"
    + (gpuSelection.inject ? " inject=" + gpuSelection.inject : ""));

  // One console line per decision, prefixed "Gaius graphics:", so a player can copy why the game
  // did or did not render with WebGPU (also in __gaiusBoot.gpu.reason and the boot events).
  function logGpu(message, warn) {
    const console = global.console;
    if (!console) return;
    const write = warn && typeof console.warn === "function" ? console.warn
      : typeof console.info === "function" ? console.info : console.log;
    if (typeof write === "function") write.call(console, "Gaius graphics: " + message);
  }

  if (gpuSelection.requested === "webgl2") {
    if (gpuSelection.unavailable.length) record("gpu-unavailable", gpuSelection.unavailable.join("; "));
    logGpu("WebGL 2 selected, WebGPU not used: " + gpuSelection.reason, gpuSelection.source !== "url");
  } else {
    logGpu("trying WebGPU (" + gpuSelection.source + ")");
  }
  const webGpuWanted = gpuSelection.requested === "webgpu" || gpuSelection.inject !== "";
  // Started at parse time, like the quality layer: these download while classes load.
  const webGpuReady = webGpuWanted ? Promise.all(WEBGPU_SCRIPTS.map(loadScript)) : Promise.resolve([]);

  // The blaze3d front-end's shader toolchain (see above): resolves to "loaded" once
  // window.__gaiusShaderToolchainReady resolved, "page" when the page loads it itself, "none" when
  // this page needs none; rejects when it cannot be loaded.
  function webGpuShaderToolchainConfig() {
    const config = global.__gaiusWebGpuShaderToolchain;
    return config && typeof config === "object" && typeof config.loader === "string" && config.loader ? config : null;
  }

  function loadWebGpuShaderToolchain() {
    if (global.__gaiusShaderToolchainReady) {
      return Promise.resolve(global.__gaiusShaderToolchainReady).then(() => "page");
    }
    const config = webGpuShaderToolchainConfig();
    if (!config) return Promise.resolve("none");
    if (isPortable() || !doc || !doc.createElement) {
      return Promise.reject(new Error("this page form carries no shader toolchain"));
    }
    let url = config.loader;
    if (site) {
      const loader = assetUrl("gaius-shader-toolchain.js");
      const modules = {shadercJs: "gaius-shaderc.js", shadercWasm: "gaius-shaderc.wasm",
        spvcJs: "gaius-spvc.js", spvcWasm: "gaius-spvc.wasm"};
      const urls = {};
      for (const key of Object.keys(modules)) urls[key] = assetUrl(modules[key]);
      if (!loader || Object.keys(urls).some((key) => !urls[key])) {
        return Promise.reject(new Error("the site carries no shader toolchain"));
      }
      // Content-hashed names: the loader cannot find its modules as siblings.
      if (!global.__gaiusShaderToolchainUrls) {
        urls.version = String(config.version || loader);
        global.__gaiusShaderToolchainUrls = urls;
      }
      url = loader;
    }
    return new Promise((resolve, reject) => {
      const element = doc.createElement("script");
      element.src = url;
      element.async = false;
      // The loader names its IndexedDB result cache after the profile.
      const profile = typeof global.__gaiusProfileId === "string" ? global.__gaiusProfileId : "";
      if (/^[A-Za-z0-9._+-]+$/.test(profile)) element.setAttribute("data-profile", profile);
      element.setAttribute("data-gaius-boot-module", "gaius-shader-toolchain.js");
      element.onload = () => {
        if (global.__gaiusShaderToolchainReady) resolve(true);
        else reject(new Error(url + " did not start the shader toolchain"));
      };
      element.onerror = () => reject(new Error("could not load " + url));
      (doc.head || doc.documentElement).appendChild(element);
    }).then(() => global.__gaiusShaderToolchainReady).then(() => "loaded");
  }

  // WasmGC clients use the shader compiler for both WebGPU and WebGL2. Frozen
  // JavaScript clients declare the same dependency when their page carries it.
  // page-level declaration is present only when this profile carries the
  // compiler; keep the capability gate ahead of it so unsupported browsers do
  // not start the heavyweight download. Portable pages publish their inline
  // loader's promise before boot instead of a separate runtime declaration.
  const shaderToolchainConfig = webGpuShaderToolchainConfig();
  const shaderToolchainRequired = (clientRuntime.selected === "wasm"
      || (clientRuntime.selected === "js" && shaderToolchainConfig?.requiredForJs === true))
    && (shaderToolchainConfig !== null
      || (isPortable() && !!global.__gaiusShaderToolchainReady));
  // Started at parse time next to the glue (a 4 MB download and two compiles).
  const webGpuShaderToolchain = (shaderToolchainRequired
    || (gpuSelection.requested === "webgpu" && gpuSelection.inject !== "after-configure"))
    ? loadWebGpuShaderToolchain() : null;
  if (webGpuShaderToolchain) webGpuShaderToolchain.catch(() => {});

  function webGpuInitOptions() {
    return {
      inject: gpuSelection.inject === "after-configure" || gpuSelection.inject === "lose-device"
        ? gpuSelection.inject : undefined,
      pipelineMode: gpuSelection.pipelines || undefined,
      allowFallback: gpuSelection.allowFallbackAdapter,
    };
  }

  // The adapter and the device are requested as soon as the glue is there, while the client
  // loads (init takes no canvas context; the canvas is found when the surface is configured).
  // The after-configure test needs the canvas at init, so it starts in beforeMain().
  if (gpuSelection.requested === "webgpu" && gpuSelection.inject !== "after-configure") {
    webGpuReady.then(() => {
      const gpu = global.GaiusWebGpu;
      if (gpu && typeof gpu.init === "function") {
        gpu.requested = true;
        requestWebGpu(gpu);
      }
    });
  }

  function requestWebGpu(gpu) {
    const failed = (error) => {
      const reason = "adapter/device: " + String(error && error.message || error);
      if (typeof gpu.abandon === "function") gpu.abandon(reason);
      return false;
    };
    try {
      return Promise.resolve(gpu.init(
        doc && doc.getElementById ? doc.getElementById("mc-canvas") : null, webGpuInitOptions())).catch(failed);
    } catch (error) {
      return Promise.resolve(failed(error));
    }
  }

  function webGpuFallback(reason) {
    gpuSelection.selected = "webgl2";
    gpuSelection.fallback = true;
    gpuSelection.reason = String(reason || "unavailable").slice(0, 300);
    record("gpu-fallback", gpuSelection.reason);
    const adapter = global.GaiusWebGpu && global.GaiusWebGpu.adapterInfo;
    const adapterText = adapter ? " [adapter " + [adapter.vendor, adapter.architecture, adapter.device,
      adapter.description].filter(Boolean).join(" / ") + (adapter.isFallbackAdapter ? ", fallback" : "") + "]" : "";
    logGpu("WebGPU failed, falling back to WebGL 2: " + gpuSelection.reason + adapterText, true);
  }

  // The translator module's bytes or Response for this distribution form, or null.
  function translatorSource() {
    if (isPortable()) {
      const embedded = global.__gaiusPortableWebGpu;
      if (embedded && embedded.translator && typeof embedded.translator.load === "function") {
        return Promise.resolve(embedded.translator.load());
      }
      // A Wasm-only single file announces nothing: its translator is a file payload like the
      // other embedded modules.
      if (!hasFilePayload(WEBGPU_TRANSLATOR)) return null;
    }
    if (isPortable() || (isFile && !site)) {
      if (!hasFilePayload(WEBGPU_TRANSLATOR)) return null;
      return filePayloadBytes(WEBGPU_TRANSLATOR).then((bytes) =>
        filePayloads()[WEBGPU_TRANSLATOR].gzip ? wasmResponse(gunzipStream(bytes)) : bytes);
    }
    if (site && !hasAsset(WEBGPU_TRANSLATOR) && !hasAsset(WEBGPU_TRANSLATOR + ".gz")) return null;
    if (site && useGzip && hasAsset(WEBGPU_TRANSLATOR + ".gz") && typeof global.DecompressionStream === "function") {
      return fetchWasm(siblingUrl(WEBGPU_TRANSLATOR + ".gz"), true);
    }
    return fetchWasm(siblingUrl(WEBGPU_TRANSLATOR), false);
  }

  function compileTranslator() {
    const source = translatorSource();
    if (!source || !global.WebAssembly) return Promise.resolve(null);
    return source.then((body) => {
      const wasm = global.WebAssembly;
      if (body instanceof global.Response) {
        return typeof wasm.compileStreaming === "function"
          ? wasm.compileStreaming(body) : body.arrayBuffer().then((bytes) => wasm.compile(bytes));
      }
      return wasm.compile(body);
    }).catch((error) => {
      record("gpu-translator-failed", error && error.message);
      return null;
    });
  }

  // A portable page announces its embedded translator (window.__gaiusPortableWebGpu) from the
  // portable bootstrap. Until then the module is not missing, only not yet announced.
  function portableTranslatorPending() {
    const embedded = global.__gaiusPortableWebGpu;
    return isPortable() && !(embedded && embedded.translator && typeof embedded.translator.load === "function");
  }

  // Start translator compilation at parse time. The promise is consumed by prepareGpu()
  // below, so the beforeMain barrier only waits for work that is already in flight. A portable
  // page that has not announced its translator yet compiles it in prepareGpu() instead of
  // caching an early miss as "translator unavailable".
  const gpuTranslatorStarted = gpuSelection.requested === "webgpu"
    && gpuSelection.inject !== "after-configure" && !portableTranslatorPending()
    ? compileTranslator() : null;
  if (gpuTranslatorStarted) gpuTranslatorStarted.catch(() => {});

  // Where the prefetch Worker gets the job codec: its URL, or the inline text of a portable page.
  function translatorJobSource() {
    const name = WEBGPU_SCRIPTS[1];
    if (isPortable()) {
      const element = doc && doc.querySelector ? doc.querySelector('script[data-gaius-boot-module="' + name + '"]') : null;
      return element && element.textContent ? {jobSource: element.textContent} : null;
    }
    if (isFile && !site) return null;
    return {jobUrl: siblingUrl(name)};
  }

  // Bounded by WEBGPU_INIT_TIMEOUT_MS; never throws. Leaves GaiusWebGpu ready (state 2) or failed.
  async function prepareGpuOnce() {
    if (!webGpuWanted) return;
    const started = nowMs();
    mark("webgpu-start");
    const deadline = started + WEBGPU_INIT_TIMEOUT_MS;
    const left = () => Math.max(0, deadline - nowMs());
    // Started now unless a portable page has not announced its translator yet; that one is
    // compiled once the device is ready (below), after the portable bootstrap had its turn.
    let translator = gpuSelection.requested === "webgpu"
      ? (gpuTranslatorStarted || (portableTranslatorPending() ? null : compileTranslator()))
      : Promise.resolve(null);
    await withTimeout(webGpuReady, left());
    const gpu = global.GaiusWebGpu;
    if (!gpu || typeof gpu.init !== "function") {
      if (gpuSelection.requested === "webgpu") webGpuFallback("glue-missing");
      return;
    }
    if (gpuSelection.inject === "taint-canvas") {
      record("gpu-inject", "taint-canvas " + (gpu.taintCanvas() ? "done" : "failed"));
    }
    if (gpuSelection.requested !== "webgpu") return;
    gpu.requested = true;
    const timedOut = {};
    const ready = await Promise.race([
      // The same promise when the early request above already started it.
      requestWebGpu(gpu),
      new Promise((resolve) => global.setTimeout(() => resolve(timedOut), left())),
    ]);
    let failure = "";
    let translationCacheHeadStart = null;
    if (ready === timedOut) {
      failure = "init timed out after " + WEBGPU_INIT_TIMEOUT_MS + " ms";
    } else if (ready !== true) {
      failure = gpu.failure || "init failed";
    } else {
      if (!translator) translator = compileTranslator();
      const module = await Promise.race([translator, new Promise((resolve) => global.setTimeout(() => resolve(timedOut), left()))]);
      if (module === timedOut) {
        failure = "translator timed out after " + WEBGPU_INIT_TIMEOUT_MS + " ms";
      } else if (!module) {
        failure = "translator unavailable (" + WEBGPU_TRANSLATOR + ")";
      } else {
        // The URL is content-hashed on a staged site. Keep it in the cache identity so a
        // rebuilt translator can never reuse WGSL produced by an older module.
        const translatorAsset = site && site.assets && site.assets[WEBGPU_TRANSLATOR];
        const embeddedTranslator = global.__gaiusPortableWebGpu &&
          global.__gaiusPortableWebGpu.translator;
        const payloadTranslator = filePayloads() && filePayloads()[WEBGPU_TRANSLATOR];
        global.__gaiusGpuTranslationCacheVersion = String(translatorAsset ||
          (embeddedTranslator && embeddedTranslator.sha256) ||
          (payloadTranslator && payloadTranslator.sha256) ||
          (global.__gaiusWebGpuShaderToolchainUrls && global.__gaiusWebGpuShaderToolchainUrls.version) ||
          WEBGPU_TRANSLATOR);
        if (!gpu.installTranslator(module, translatorJobSource())) {
          failure = "translator: " + (gpu.translateError || "install failed");
        } else if (gpu.translationCacheReady) {
          // IndexedDB is an optimisation. A warm cache gets a short head start, counted from
          // now and spent while the shader toolchain loads below, without making storage
          // stalls part of the graphics readiness deadline.
          translationCacheHeadStart = Promise.race([gpu.translationCacheReady,
            new Promise((resolve) => global.setTimeout(resolve, TRANSLATION_CACHE_HEAD_START_MS))]);
        }
      }
    }
    if (!failure) {
      const toolchain = (webGpuShaderToolchain || loadWebGpuShaderToolchain()).catch(
        (error) => ({failure: "shader toolchain: " + (error && error.message ? error.message : error)}));
      // A required compiler also gates WebGL2 and main. Waiting for it cannot
      // make an otherwise ready device fall back merely because loading is slow.
      const state = await (shaderToolchainRequired ? toolchain : Promise.race([
        toolchain, new Promise((resolve) => global.setTimeout(() => resolve(timedOut), left())),
      ]));
      if (state === timedOut) {
        failure = "shader toolchain timed out after " + WEBGPU_INIT_TIMEOUT_MS + " ms";
      } else if (state && state.failure) {
        failure = state.failure;
      } else if (state === "loaded") {
        record("webgpu-shader-toolchain", "loaded");
      }
    }
    // Normally already settled (the toolchain takes longer than the head start); only what is
    // left of the head start is waited for.
    if (!failure && translationCacheHeadStart) await translationCacheHeadStart;
    gpuSelection.initMs = Math.round(nowMs() - started);
    if (failure) {
      // The seam reads the glue's state: a refused device sends vanilla on to WebGL 2.
      if (typeof gpu.abandon === "function") gpu.abandon(failure);
      webGpuFallback(failure);
    } else {
      gpuSelection.selected = "webgpu";
      record("webgpu-ready", gpuSelection.initMs + " ms");
      const adapter = gpu.adapterInfo;
      logGpu("WebGPU ready in " + gpuSelection.initMs + " ms" + (adapter ? " [adapter " + [adapter.vendor,
        adapter.architecture, adapter.device, adapter.description].filter(Boolean).join(" / ") + "]" : ""));
    }
    mark("webgpu-" + gpuSelection.selected);
  }

  let gpuPreparePromise = null;
  function prepareGpu() {
    if (!gpuPreparePromise) gpuPreparePromise = prepareGpuOnce();
    return gpuPreparePromise;
  }

  // The normal path is safe to begin during parsing. The after-configure injection deliberately
  // remains a beforeMain-only test path because it needs the game canvas.
  if (webGpuWanted && gpuSelection.inject !== "after-configure") prepareGpu();

  function publishGpuTimings() {
    const timings = global.__gaiusBootTimings;
    if (!timings || typeof timings !== "object") return;
    timings.gpu = gpuSelection.selected;
    if (gpuSelection.initMs !== null) timings.gpuInitMs = gpuSelection.initMs;
    if (gpuSelection.fallback) timings.gpuFallback = gpuSelection.reason;
    else delete timings.gpuFallback;
  }

  function loadClassicScript(url) {
    return new Promise((resolve, reject) => {
      const element = doc.createElement("script");
      element.src = url;
      element.onload = () => resolve();
      element.onerror = () => reject(new Error("Could not load " + url));
      (doc.body || doc.head || doc.documentElement).appendChild(element);
    });
  }

  // --- sounds on demand -----------------------------------------------------------------------

  const VANILLA_MAGIC = "GAIUSVP1";

  function parsePack(bytes) {
    for (let i = 0; i < VANILLA_MAGIC.length; i++) {
      if (bytes[i] !== VANILLA_MAGIC.charCodeAt(i)) throw new Error("sound pack has no GAIUSVP1 header");
    }
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const indexLength = view.getUint32(8, true);
    const dataOffset = 12 + indexLength;
    if (dataOffset > bytes.length) throw new Error("sound pack index is truncated");
    const index = JSON.parse(new global.TextDecoder().decode(bytes.subarray(12, dataOffset)));
    return {index, dataOffset};
  }

  // Appends the sound pack's payload to the decoded core pack and its ranges to the index. The
  // swap of bytes and index happens in one synchronous step, so Java never sees a half merge.
  function mergeSoundPack(root, soundBytes) {
    const pack = parsePack(soundBytes);
    const payload = soundBytes.subarray(pack.dataOffset);
    const merged = new Uint8Array(root.bytes.length + payload.length);
    merged.set(root.bytes, 0);
    merged.set(payload, root.bytes.length);
    const shift = root.bytes.length - root.dataOffset;
    const additions = [];
    for (const name in pack.index) {
      if (!Object.prototype.hasOwnProperty.call(pack.index, name)) continue;
      const range = pack.index[name];
      if (!Array.isArray(range) || range.length !== 2 || range[0] < 0 || range[1] < 0
          || range[0] + range[1] > payload.length) {
        throw new Error("sound pack range is invalid: " + name);
      }
      additions.push([name, [range[0] + shift, range[1]]]);
    }
    root.bytes = merged;
    for (let i = 0; i < additions.length; i++) root.index[additions[i][0]] = additions[i][1];
    root.resourceCount = (root.resourceCount || 0) + additions.length;
    return additions.length;
  }

  // Low-memory sessions keep sounds in a separate indexed pack. AssetArchive checks this
  // sidecar after the core pack, so Java retains synchronous reads without a second merged copy.
  function publishSoundPack(soundBytes) {
    const pack = parsePack(soundBytes);
    let resourceCount = 0;
    for (const name in pack.index) {
      if (Object.prototype.hasOwnProperty.call(pack.index, name)) resourceCount++;
    }
    global.__gaiusVanillaSounds = {
      bytes: soundBytes,
      index: pack.index,
      dataOffset: pack.dataOffset,
      resourceCount
    };
    return resourceCount;
  }

  function splitSoundPackRequested() {
    if (!clientRuntime || clientRuntime.selected !== "wasm") return false;
    // A sidecar is valid only when SoundManager can reload after it arrives. The caller may have
    // fallen back to eager mode because no bridge exists; keep that path merged even on a low-tier
    // device so the first SoundEngine never observes an incomplete pack.
    if (typeof global.__gaiusSoundReloadHook !== "function") return false;
    return String(params.get("gaiusSounds") || "").toLowerCase() === "split"
      || global.__gaiusIsLowMemoryTier();
  }

  // The launcher defines __gaiusVanillaAssetsReady after this script ran; wait for it to exist,
  // then for the core pack to be decoded.
  function vanillaAssetsDecoded() {
    return new Promise((resolve) => {
      const poll = () => {
        const ready = global.__gaiusVanillaAssetsReady;
        if (ready && typeof ready.then === "function") {
          ready.then((value) => {
            // The launcher has parsed the pack at this point. Drop the compressed payload
            // promise so its temporary response bytes can be collected while the indexed
            // decoded pack remains available to Java.
            try { global.__gaiusVanillaAssetsCompressedPromise = null; } catch (_) {}
            resolve(value);
          }, () => resolve(null));
        }
        else global.setTimeout(poll, 50);
      };
      poll();
    });
  }

  let soundsState = {mode: "none", merged: false, layout: "none", error: null, resources: 0};
  let soundsGate = Promise.resolve();
  let soundsReady = Promise.resolve();
  let soundLoadStarted = false;
  let soundReloadCalled = false;
  let soundReloadHook = null;
  let soundPollTimer = null;
  let soundPollDeadlineTimer = null;
  let soundPollStopped = false;
  const SOUND_TITLE_POLL_INTERVAL_MS = 100;
  const SOUND_TITLE_POLL_DEADLINE_MS = 15000;

  function stopSoundTitlePoll(reason) {
    if (soundPollTimer !== null) {
      try { global.clearTimeout(soundPollTimer); } catch (_) {}
      soundPollTimer = null;
    }
    if (soundPollDeadlineTimer !== null) {
      try { global.clearTimeout(soundPollDeadlineTimer); } catch (_) {}
      soundPollDeadlineTimer = null;
    }
    if (!soundPollStopped) {
      soundPollStopped = true;
      if (reason) record("sound-title-poll-stopped", reason);
    }
  }

  function fallBackToEagerSounds(reason) {
    if (soundsState.mode !== "deferred" && soundsState.mode !== "split") return false;
    stopSoundTitlePoll(reason || "eager-fallback");
    if (soundLoadStarted) return false;
    soundsState.mode = "eager";
    soundsState.layout = "pending";
    record("sound-mode-fallback", reason || "eager-fallback");
    startSoundLoad();
    return true;
  }

  function invokeSoundReloadHook(hook) {
    if (soundReloadCalled || typeof hook !== "function"
        || (soundsState.mode !== "deferred" && soundsState.mode !== "split")) return;
    soundReloadCalled = true;
    try {
      hook();
      record("sound-reload-hook", "called");
    } catch (error) {
      record("sound-reload-hook-failed", error && error.message);
    }
  }

  // SoundEngine installs this hook while main() constructs the client. Install an accessor before
  // main so the default mode can be decided when that runtime assignment actually happens.
  function installSoundReloadHookBridge() {
    const key = "__gaiusSoundReloadHook";
    let descriptor = null;
    try { descriptor = Object.getOwnPropertyDescriptor(global, key); } catch (_) {}
    if (descriptor && descriptor.configurable === false) {
      try { soundReloadHook = global[key]; } catch (_) {}
      return;
    }
    try { soundReloadHook = global[key]; } catch (_) {}
    try {
      Object.defineProperty(global, key, {
        configurable: true,
        enumerable: descriptor ? descriptor.enumerable : true,
        get: () => soundReloadHook,
        set: (value) => {
          soundReloadHook = value;
          if (soundsState.merged) invokeSoundReloadHook(value);
        },
      });
    } catch (_) {
      // A host may expose a non-configurable bridge. The merge path still checks its value.
    }
  }

  function startSoundLoad() {
    if (soundLoadStarted || soundsState.mode === "none") return soundsReady;
    stopSoundTitlePoll("sound-load-started");
    soundLoadStarted = true;
    // A portable page embeds the pack as a file payload (no sibling URL to fetch).
    const soundBytes = isPortable() && hasFilePayload("vanilla-sounds.pack")
      ? filePayload("vanilla-sounds.pack").then((bytes) => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength))
      : global.fetch(assetUrl("vanilla-sounds.pack"), {cache: "force-cache"}).then((response) => {
        if (!response.ok) throw new Error("HTTP " + response.status + " while loading the sound pack");
        return response.arrayBuffer();
      });
    const merge = Promise.all([soundBytes, vanillaAssetsDecoded()])
      .then((parts) => {
        mark("asset-pack-decode");
        const root = global.__gaiusVanillaAssets || parts[1];
        if (!root || !root.bytes || !root.index) throw new Error("the core asset pack is not decoded");
        const soundBytes = new Uint8Array(parts[0]);
        const useSoundSidecar = clientRuntime.selected === "wasm"
          && (splitSoundPackRequested() || soundsState.mode === "eager");
        if (useSoundSidecar) {
          soundsState.resources = publishSoundPack(soundBytes);
          soundsState.layout = "split";
          record("sound-layout", "split");
        } else {
          soundsState.resources = mergeSoundPack(root, soundBytes);
          soundsState.layout = "merged";
          record("sound-layout", "merged");
        }
        soundsState.merged = true;
        mark("sounds-merged");
        if (soundsState.mode !== "eager") invokeSoundReloadHook(soundReloadHook || global.__gaiusSoundReloadHook);
      })
      .catch((error) => {
        soundsState.error = String(error && error.message || error);
        record("sound-pack-failed", soundsState.error);
      });
    soundsReady = merge;
    if (soundsState.mode === "eager") soundsGate = merge;
    return merge;
  }

  function scheduleSoundLoadAfterTitle() {
    stopSoundTitlePoll();
    soundPollStopped = false;
    const configuredDeadline = Number(global.__gaiusSoundPollDeadlineMs);
    const deadline = Number.isFinite(configuredDeadline) && configuredDeadline > 0
      ? configuredDeadline : SOUND_TITLE_POLL_DEADLINE_MS;
    const poll = () => {
      soundPollTimer = null;
      if (soundLoadStarted || soundPollStopped) return;
      const state = global.__gaiusMinecraftState;
      const screen = state && state.screen ? String(state.screen) : "";
      const title = (screen === "TitleScreen" || screen.slice(-12) === ".TitleScreen")
        && state.running === true && !state.overlay;
      if (title) {
        startSoundLoad();
        return;
      }
      soundPollTimer = global.setTimeout(poll, SOUND_TITLE_POLL_INTERVAL_MS);
    };
    soundPollDeadlineTimer = global.setTimeout(() => {
      soundPollDeadlineTimer = null;
      if (!soundLoadStarted && !soundPollStopped) fallBackToEagerSounds("title-timeout");
    }, deadline);
    soundPollTimer = global.setTimeout(poll, 0);
  }

  // The single-file build carries no window.__gaiusSite (its assets are file payloads), so the
  // portable form must not require site metadata.
  if (isPortable() ? hasFilePayload("vanilla-sounds.pack") : (site && hasAsset("vanilla-sounds.pack"))) {
    const requested = String(params.get("gaiusSounds") || "").toLowerCase();
    const requestedMode = requested === "eager" || requested === "deferred" || requested === "split"
      ? requested : "eager";
    // A deferred/split pack is safe only when the client has provided a real SoundManager
    // reload callback before boot. Without it the first SoundEngine sees an incomplete pack and
    // remains silent; eager merged loading is the correctness-preserving fallback.
    const reloadHookAvailable = typeof global.__gaiusSoundReloadHook === "function";
    const mode = (requestedMode === "deferred" || requestedMode === "split") && reloadHookAvailable
      ? requestedMode : "eager";
    if (mode === "eager" && requestedMode !== "eager" && !reloadHookAvailable) {
      record("sound-mode-fallback", "eager-no-reload-hook");
    }
    soundsState = {mode, merged: false, layout: "pending", error: null, resources: 0};
    installSoundReloadHookBridge();
    if (mode === "eager") startSoundLoad();
    else scheduleSoundLoadAfterTitle();
  }

  // --- kernel runtime -------------------------------------------------------------------------

  let kernelsTerminated = false;
  let runtimePromise = null;
  let runtimeInstance = null;
  let viewerTimer = null;
  // Viewer state is a scheduling hint for kernel admission, not a gameplay input. Keep the
  // newest tick and send it in a bounded batch so movement polling does not compete with the
  // render loop or create a MessagePort event for every short client tick.
  const VIEWER_FEED_INTERVAL_MS = 250;

  function kernelSwitchOff() {
    const value = params.get("gaiusKernels");
    if (value === "0" || value === "off" || value === "false") return true;
    try {
      const stored = JSON.parse(storageGet("gaius.kernels.settings.v1") || "null");
      return !!(stored && stored.enabled === false) && value !== "1";
    } catch (_) {
      return false;
    }
  }

  async function kernelManifest() {
    if (isPortable() && global.__gaiusPortableKernels) {
      return {kernels: global.__gaiusPortableKernels, worker: portableWorkerSource()};
    }
    if (site) {
      if (!site.kernels || !hasAsset("kernels/kernel-worker.js")) return null;
      const kernels = {};
      for (const name of Object.keys(site.kernels)) {
        const entry = site.kernels[name];
        const variants = {};
        for (const variant of Object.keys(entry.variants || {})) {
          variants[variant] = {url: new URL(entry.variants[variant], loc.href).href,
            sha256: entry.sha256 && entry.sha256[variant]};
        }
        kernels[name] = {kinds: entry.kinds || [], memory: entry.memory, variants};
      }
      return {kernels, worker: {workerUrl: new URL(assetUrl("kernels/kernel-worker.js"), loc.href).href}};
    }
    if (isFile) return null;
    try {
      const response = await global.fetch("kernels/kernels.json", {cache: "no-cache"});
      if (!response.ok) return null;
      const manifest = await response.json();
      // A dist directory lists the kernel job codecs next to the modules.
      if (Array.isArray(manifest.scripts)) await Promise.all(manifest.scripts.map(loadScript));
      const kernels = {};
      for (const name of Object.keys(manifest.kernels || {})) {
        const entry = manifest.kernels[name];
        const variants = {};
        for (const variant of Object.keys(entry.variants || {})) {
          variants[variant] = {url: new URL("kernels/" + entry.variants[variant].file, loc.href).href,
            sha256: entry.variants[variant].sha256};
        }
        kernels[name] = {kinds: entry.kinds || [], memory: entry.memory, variants};
      }
      return {kernels, worker: {workerUrl: new URL("kernels/kernel-worker.js", loc.href).href}};
    } catch (_) {
      return null;
    }
  }

  function portableWorkerSource() {
    const element = doc && doc.getElementById ? doc.getElementById("gaius-kernel-worker-source") : null;
    const source = element ? element.textContent : "";
    return source ? {workerSource: source} : {};
  }

  function runtime() {
    if (kernelsTerminated) return Promise.resolve(null);
    if (runtimePromise) return runtimePromise;
    runtimePromise = (async () => {
      if (kernelSwitchOff()) {
        record("kernels-disabled", "switch");
        return null;
      }
      await withTimeout(kernelScriptsReady, 15000);
      if (kernelsTerminated) return null;
      if (!global.GaiusKernelRuntime || !global.GaiusKernelPolicy) {
        record("kernels-unavailable", "runtime scripts missing");
        return null;
      }
      const manifest = await kernelManifest();
      if (kernelsTerminated) return null;
      if (!manifest || !manifest.kernels || Object.keys(manifest.kernels).length === 0) {
        record("kernels-unavailable", "no kernel manifest");
        return null;
      }
      if (!manifest.worker.workerUrl && !manifest.worker.workerSource) {
        record("kernels-unavailable", "no kernel worker script");
        return null;
      }
      const created = global.GaiusKernelRuntime.create(Object.assign({kernels: manifest.kernels}, manifest.worker));
      // Factory callbacks can tear down this document before the runtime is published.
      if (kernelsTerminated) {
        if (created && typeof created.terminate === "function") created.terminate();
        return null;
      }
      runtimeInstance = created;
      // Keep the page-owned runtime reachable by lifecycle teardown.  The successor must not
      // inherit a renderer full of workers/wasm instances while the old document is unloading.
      global.__gaiusKernelRuntime = created;
      startViewerFeed();
      mark("kernel-runtime");
      return created.available() ? created : null;
    })().catch((error) => {
      record("kernel-runtime-failed", error && error.message);
      return null;
    });
    return runtimePromise;
  }

  // Motion prediction input: the client publishes the player in __gaiusMinecraftState.
  function startViewerFeed() {
    if (viewerTimer !== null) return;
    let lastAt = 0;
    viewerTimer = global.setInterval(() => {
      const runtime = runtimeInstance;
      if (!runtime || runtime.terminated) return;
      const state = global.__gaiusMinecraftState;
      const player = state && state.player;
      if (!player || !state.level || state.at === lastAt) return;
      lastAt = state.at;
      const distance = state.clientDistance && state.clientDistance.effectiveRenderDistance;
      runtime.setViewer({
        x: player.x, y: player.y, z: player.z, yaw: player.yaw, t: state.at,
        viewDistance: typeof distance === "number" ? distance : undefined,
      });
    }, VIEWER_FEED_INTERVAL_MS);
  }

  // --- integrated server Worker ---------------------------------------------------------------

  // The server Worker sees neither the page URL nor its storage, so the page passes the kernel
  // switches and the scripts that Worker imports before its kernel client exists along with the
  // port (server-worker-bootstrap.js loadKernelHostScripts).
  const SERVER_KERNEL_SCRIPTS = ["kernels/worldgen-job.js", "kernels/worldgen-kernel.js"];
  const OFF_VALUE = /^(0|off|false|no)$/i;
  const ON_VALUE = /^(1|on|true|yes)$/i;

  // "worker": the server Worker runs its own worldgen kernel workers; "shared": worldgen only
  // goes through this page's runtime; empty: the facade decides (auto).
  function worldgenHostMode() {
    const value = String(params.get("worldgenKernelHost") || "").toLowerCase();
    return value === "worker" || value === "shared" ? value : "";
  }

  // [{name, url}] for a site or dist over http(s), [{name, source}] from the inert copies a
  // portable page carries (build-portable-html.py), nothing for a dist opened from disk.
  function serverWorkerKernelScripts() {
    const names = (worldgenHostMode() === "worker" ? KERNEL_RUNTIME_SCRIPTS : ["kernels/kernel-mirror.js"]).concat(SERVER_KERNEL_SCRIPTS);
    if (isPortable()) {
      const out = [];
      for (const name of names) {
        const element = doc && doc.querySelector
          ? doc.querySelector('script[type="text/plain"][data-gaius-worker-script="' + name + '"]')
          : null;
        if (element && element.textContent) out.push({name, source: element.textContent});
      }
      return out;
    }
    if (isFile && !site) return [];
    const out = [];
    for (const name of names) {
      // A site that does not list a script never had it: the Worker keeps the vanilla path.
      if (site && !hasAsset(name)) continue;
      out.push({name, url: new URL(assetUrl(name) || name, loc.href).href});
    }
    return out;
  }

  function serverWorldgenConfig() {
    const config = {};
    const value = params.get("worldgenKernel");
    // port/versions/<profile>.json "worldgenKernelDefault": false keeps a release site on vanilla
    // worldgen while its kernel on/off A/B differs beyond run-to-run noise (the launcher injects
    // it as __gaiusWorldgenKernelDefault); explicit ?worldgenKernel=1 stays available for
    // validation runs.
    if (global.__gaiusWorldgenKernelDefault === false) {
      config.enabled = value !== null ? ON_VALUE.test(value) : false;
    } else if (value !== null && OFF_VALUE.test(value)) {
      config.enabled = false;
    }
    const host = worldgenHostMode();
    if (host) config.host = host;
    // ?worldgenConcurrentYield=legacy|window: the integrated server's worldgen yield policy for
    // threads that run while another one is suspended in a scheduler yield
    // (BrowserWorldgenScheduler reads __gaiusWorldgenKernelConfig.concurrentYield).
    const yieldMode = String(params.get("worldgenConcurrentYield") || "").toLowerCase();
    if (yieldMode === "legacy" || yieldMode === "window") config.concurrentYield = yieldMode;
    return config;
  }

  // ?lightKernel=0 (also lightkernel, off/false/no) or the stored gaius.lightKernel=off setting.
  function serverLightConfig() {
    const value = params.get("lightKernel") !== null ? params.get("lightKernel") : params.get("lightkernel");
    const off = (value !== null && OFF_VALUE.test(value)) || storageGet("gaius.lightKernel") === "off";
    return {enabled: !off};
  }

  // ?worldgenKernelHost=worker: the server Worker starts its own kernel runtime, so it needs the
  // worldgen modules and the kernel worker script itself. Portable modules travel as bytes
  // (copied, the page runtime keeps its own), URLs stay URLs.
  async function serverWorldgenHost() {
    try {
      const manifest = await kernelManifest();
      const entry = manifest && manifest.kernels ? manifest.kernels.worldgen : null;
      if (!entry || !manifest.worker) return null;
      const variants = {};
      for (const variant of Object.keys(entry.variants || {})) {
        const source = entry.variants[variant];
        if (source && source.url) variants[variant] = {url: source.url, sha256: source.sha256};
        else if (source && typeof source.load === "function") variants[variant] = {bytes: await source.load(), sha256: source.sha256};
      }
      if (Object.keys(variants).length === 0) return null;
      const host = {kernels: {worldgen: {kinds: entry.kinds || [], memory: entry.memory, variants}}};
      const workers = params.get("worldgenKernelWorkers");
      if (workers !== null && /^\d+$/.test(workers) && Number(workers) >= 1 && Number(workers) <= 8) {
        host.size = Number(workers);
      }
      if (manifest.worker.workerUrl) host.workerUrl = manifest.worker.workerUrl;
      else if (manifest.worker.workerSource) host.workerSource = manifest.worker.workerSource;
      else return null;
      return host;
    } catch (error) {
      record("worldgen-host-config-failed", error && error.message);
      return null;
    }
  }

  // The inert copy of a WebAssembly GC runtime script a portable page carries
  // (build-portable-html.py data-gaius-wasm-script), or null.
  function portableWasmScript(name) {
    const element = doc && doc.querySelector
      ? doc.querySelector('script[type="text/plain"][data-gaius-wasm-script="' + name + '"]')
      : null;
    return element && element.textContent ? element.textContent : null;
  }

  function portableWasmServerBuilt() {
    const runtimes = global.__gaiusPortableRuntimes;
    return !!(runtimes && runtimes.wasm && typeof runtimes.wasm.server === "string")
      && WASM_SERVER_FILES.slice(1).every((name) => portableWasmScript(name) !== null);
  }

  // The server module of a site: a gzip-only one under the Service Worker by its inflated URL,
  // otherwise its .gz, which gaius-wasm-worker.js inflates in the Worker.
  function siteServerWasmUrl() {
    const inflate = inflateUrl("singleplayer-server.wasm");
    if (inflate === null) return siblingUrl("singleplayer-server.wasm");
    return serviceWorkerControlled() ? inflate : siblingUrl("singleplayer-server.wasm.gz");
  }

  // ?serverRuntime=wasm (WP10): the start message names the integrated server module and the
  // runtime scripts the Worker imports (server-worker-bootstrap.js, gaius-wasm-worker.js). Site,
  // dist over http, and a dist opened from disk with file payloads for the module and the
  // scripts: that Worker finds them by name among the files the page hands it
  // (BrowserSingleplayerClient, filePayload). A portable page that embeds the module gets the
  // scripts as sources here; its own bootstrap adds the module bytes (serverWasmGzipData).
  // Release sites and portable pages run the Wasm server with the Wasm client.
  function siteRunsWasmServer() {
    return site.runtimePolicy === "auto" || site.runtimePolicy === "wasm-only";
  }

  function serverRuntimeConfig() {
    if (clientRuntime.selected !== "wasm") return null;
    const requested = String(params.get("serverRuntime")
      || (clientRuntime.form === "portable" && clientRuntime.selected === "wasm" ? "wasm" : "")
      || (site && siteRunsWasmServer() && clientRuntime.selected === "wasm" ? "wasm" : "")).toLowerCase();
    if (requested !== "wasm") return null;
    const form = clientRuntime.form;
    const dist = distRuntimes();
    const missing = [];
    if (form === "site" ? !WASM_SERVER_FILES.every(hasModule)
      : form === "portable" ? !portableWasmServerBuilt()
      : form === "file" ? !dist || typeof dist.server !== "string"
        || ![dist.server].concat(WASM_SERVER_FILES.slice(1)).every(hasFilePayload)
      : form !== "dev" || !dist || typeof dist.server !== "string") missing.push("wasm-server-build");
    missing.push(...missingWasmFeatures(clientRuntime.features, form === "file" || form === "portable"
      || (form === "site" && siteRunsWasmServer() && inflateUrl("singleplayer-server.wasm") !== null
        && !serviceWorkerControlled())));
    if (missing.length) {
      record("server-runtime-unavailable", "missing:" + missing.join(","));
      return null;
    }
    const scripts = WASM_SERVER_FILES.slice(1);
    if (form === "portable") {
      return {
        serverRuntime: "wasm",
        wasmRuntimeScripts: scripts.map((name) => ({name, source: portableWasmScript(name)})),
      };
    }
    return {
      serverRuntime: "wasm",
      serverWasmUrl: form === "site" ? siteServerWasmUrl() : siblingUrl(dist.server),
      wasmRuntimeScripts: scripts.map((name) => ({name, url: siblingUrl(name)})),
    };
  }
  let serverWasm = serverRuntimeConfig();
  let serverWasmModule = null;
  let serverWasmModulePromise = null;
  if (serverWasm) record("server-runtime", "wasm");

  // Compile the integrated-server module in the page while the title screen is idle. The
  // resulting WebAssembly.Module is structured-cloneable, so a new server Worker can reuse the
  // browser's compiled code instead of compiling the same bytes again. A failed or unsupported
  // warm-up leaves the original URL/bytes hand-off untouched.
  function prewarmServerWasm() {
    if (!serverWasm || serverWasmModulePromise || !global.WebAssembly ||
        typeof global.WebAssembly.compile !== "function") return Promise.resolve(null);
    if (params.get("gaiusPrefetch") === "0" || connectionIsConstrained()) {
      return Promise.resolve(null);
    }
    const form = clientRuntime.form;
    let source;
    if ((form === "file" || form === "portable") && typeof filePayloadBytes === "function") {
      const runtimes = global.__gaiusPortableRuntimes;
      const name = form === "portable"
        ? String(runtimes && runtimes.wasm && runtimes.wasm.server || "singleplayer-server.wasm")
        : "singleplayer-server.wasm";
      if (hasFilePayload(name) || (global.__gaiusPortablePayloadBytes &&
          global.__gaiusPortablePayloadBytes[name])) {
        source = filePayloadBytes(name).then((bytes) => {
          const entry = filePayloads() && filePayloads()[name];
          // Portable decodeAsset(..., {gunzip:false}) intentionally hands the raw gzip
          // payload to the Worker.  There is no file-payload metadata entry in that form,
          // so use the wire signature as the page-side prewarm decision as well.
          return (entry && entry.gzip) || isGzipBytes(bytes)
            ? wasmResponse(gunzipStream(bytes))
            : bytes;
        });
      }
    }
    if (!source && serverWasm.serverWasmUrl) {
      const gzip = /\.gz(?:[?#]|$)/i.test(String(serverWasm.serverWasmUrl));
      source = fetchWasm(serverWasm.serverWasmUrl, gzip);
    }
    if (!source) return Promise.resolve(null);
    serverWasmModulePromise = Promise.resolve(source).then((body) => {
      const wasm = global.WebAssembly;
      if (body instanceof global.Response) {
        return typeof wasm.compileStreaming === "function"
          ? wasm.compileStreaming(body, {builtins: ["js-string"]})
          : body.arrayBuffer().then((bytes) => wasm.compile(bytes, {builtins: ["js-string"]}));
      }
      return wasm.compile(body, {builtins: ["js-string"]});
    }).then((module) => {
      serverWasmModule = module;
      record("server-wasm-prewarmed", "compiled");
      return module;
    }).catch((error) => {
      record("server-wasm-prewarm-failed", error && error.message);
      // A parse-time fetch can race Service Worker control. Permit the title idle pass to retry
      // once the controlled, cache-backed URL is available.
      serverWasmModulePromise = null;
      return null;
    });
    return serverWasmModulePromise;
  }
  // The integrated server Worker gets a port into this runtime right after its start message.
  let serverKernelPort = null;

  function installServerWorkerHook() {
    const WorkerClass = global.Worker;
    if (typeof WorkerClass !== "function" || !WorkerClass.prototype || WorkerClass.prototype.__gaiusKernelHook) return;
    if (typeof global.MessageChannel !== "function") return;
    const nativePostMessage = WorkerClass.prototype.postMessage;
    WorkerClass.prototype.postMessage = function (message, transfer) {
      let result;
      // The parked server Worker reports what its data pack prewarm did and whether the world
      // load used it (BrowserWorldLoadPrewarm, BrowserWorldLoadCache); keep it with the boot events.
      if (message && (message.type === "prewarm" || message.type === "start") &&
          !this.__gaiusWorldLoadHook && typeof this.addEventListener === "function") {
        this.__gaiusWorldLoadHook = true;
        this.addEventListener("message", (event) => {
          const data = event && event.data;
          if (!data || typeof data.type !== "string" || data.type.slice(0, 11) !== "world-load-") return;
          record("server-" + data.type, String(data.detail || "") + " at=" + Date.now());
        });
      }
      if (message && message.type === "start" && !this.__gaiusStorageErrorHook &&
          typeof this.addEventListener === "function") {
        this.__gaiusStorageErrorHook = true;
        this.addEventListener("message", (event) => {
          const data = event && event.data;
          if (!data || (data.type !== "storage-write-error" && data.type !== "storage-flush-failed")) return;
          const detail = String(data.detail || "browser storage write failed");
          try {
            if (typeof global.__gaiusMinecraftEvents?.push === "function") {
              global.__gaiusMinecraftEvents.push({event: "storage:error", detail, at: Date.now()});
            }
          } catch (_) {}
          const status = global.document && global.document.getElementById
            ? global.document.getElementById("status") : null;
          if (status) {
            status.hidden = false;
            status.dataset.state = "error";
            status.textContent = "保存失败：" + detail;
          }
        });
      }
      if (message && (message.type === "start" || message.type === "prewarm") && !message.serverRuntime) {
        const args = Array.prototype.slice.call(arguments);
        const warm = serverWasmModule
          ? {serverWasmModule}
          : {};
        const server = serverWasm || {serverRuntime: "js",
          serverScriptUrl: global.__gaiusSingleplayerServerUrl || null,
          serverScriptGzipUrl: global.__gaiusSingleplayerServerGzipUrl || null};
        args[0] = Object.assign({}, message, server, warm);
        // ?gaiusWorldgenSlice=<2..50> overrides the cooperative worldgen slice (ms) the client
        // asks for (8); an A/B knob, nothing changes without it.
        const sliceParam = params.get("gaiusWorldgenSlice");
        const slice = sliceParam === null || sliceParam === "" ? NaN : Number(sliceParam);
        if (slice >= 2 && slice <= 50) args[0].worldgenSliceMillis = slice;
        // ?gaiusServerWake=1: the server Worker wakes its parked server thread when light or worldgen work
        // for its poll is queued (BrowserIntegratedServerMain.wakeServerForPoll); an A/B knob for the same page
        // bytes, absent (or =0) is the vanilla wait until the next tick.
        const wakeParam = params.get("gaiusServerWake");
        if (wakeParam !== null && ON_VALUE.test(wakeParam)) args[0].gaiusServerWake = true;
        result = nativePostMessage.apply(this, args);
      } else {
        result = nativePostMessage.apply(this, arguments);
      }
      try {
        if (message && message.type === "start" && message.port && !this.__gaiusKernelPortSent && !kernelSwitchOff()) {
          this.__gaiusKernelPortSent = true;
          const channel = new global.MessageChannel();
          const portMessage = {
            type: "gaius-kernel-port",
            port: channel.port2,
            kernelScripts: serverWorkerKernelScripts(),
            worldgenKernel: serverWorldgenConfig(),
            lightKernel: serverLightConfig(),
            kernelSemantics: global.__gaiusKernelSemantics || null,
          };
          if (worldgenHostMode() === "worker") {
            // The host configuration needs the resolved manifest; bounded, and without it the
            // Worker's worldgen simply stays on vanilla Java.
            const worker = this;
            withTimeout(serverWorldgenHost(), 15000).then((host) => {
              if (host) Object.assign(portMessage.worldgenKernel, host);
              try {
                nativePostMessage.call(worker, portMessage, [channel.port2]);
              } catch (error) {
                record("kernel-port-failed", error && error.message);
              }
            });
          } else {
            nativePostMessage.call(this, portMessage, [channel.port2]);
          }
          runtime().then((created) => {
            if (created) {
              // One integrated server at a time: a new start replaces the previous Worker, whose
              // queued jobs would only hold kernel workers (detachPort cancels them).
              if (serverKernelPort && typeof created.detachPort === "function") created.detachPort(serverKernelPort);
              serverKernelPort = channel.port1;
              created.attachPort(channel.port1);
            } else {
              channel.port1.postMessage({type: "status", status: {enabled: false, kernels: {}}});
              channel.port1.close();
            }
          });
        }
      } catch (error) {
        record("kernel-port-failed", error && error.message);
      }
      return result;
    };
    WorkerClass.prototype.__gaiusKernelHook = true;
  }
  installServerWorkerHook();

  const kernels = {
    version: BOOT_VERSION,
    runtime,
    enabled() {
      return !kernelSwitchOff();
    },
    available(kernel) {
      return !!(runtimeInstance && runtimeInstance.available(kernel));
    },
    submit(kind, payload, opts) {
      return runtime().then((created) => {
        if (!created) throw new Error("kernel runtime is not available");
        return created.submit(kind, payload, opts);
      });
    },
    warm(names) {
      return runtime().then((created) => {
        if (!created || typeof created.warm !== "function") return [];
        return created.warm(names || ["mesher", "light"]);
      }).catch((error) => {
        record("kernel-warm-failed", error && error.message || String(error));
        return [];
      });
    },
    setViewer(viewer) {
      return runtimeInstance ? runtimeInstance.setViewer(viewer) : false;
    },
    reportPressure(level) {
      if (runtimeInstance) runtimeInstance.reportPressure(level);
    },
    telemetry() {
      return runtimeInstance ? runtimeInstance.telemetry() : null;
    },
  };
  global.__gaiusKernels = kernels;
  global.__gaiusTerminateKernels = function (reason) {
    if (kernelsTerminated) return false;
    kernelsTerminated = true;
    runtimePromise = null;
    if (viewerTimer !== null) {
      try { global.clearInterval(viewerTimer); } catch (_) { /* unloading */ }
      viewerTimer = null;
    }
    const runtime = runtimeInstance || global.__gaiusKernelRuntime;
    runtimeInstance = null;
    global.__gaiusKernelRuntime = null;
    try {
      if (runtime && typeof runtime.terminate === "function") runtime.terminate();
    } catch (error) {
      record("kernel-teardown-error", (reason || "pagehide") + ":" + (error && error.message || error));
    }
    try {
      if (global.__gaiusKernelClient && typeof global.__gaiusKernelClient.close === "function") {
        global.__gaiusKernelClient.close();
      }
    } catch (_) { /* unloading */ }
    global.__gaiusKernelClient = null;
    record("kernel-teardown", reason || "pagehide");
    return true;
  };

  // --- Service Worker -------------------------------------------------------------------------

  const swState = {registered: false, controlled: false, coep: null, error: null, pruned: null};

  function coepMode() {
    const requested = params.get("gaiusCoi");
    if (requested === "0" || requested === "off") return "off";
    if (requested === "credentialless" || requested === "require-corp") return requested;
    const stored = storageGet("gaius.sw.coep");
    return stored === "require-corp" || stored === "off" ? stored : "credentialless";
  }

  function serviceWorkerAllowed() {
    return !!site && !isPortable() && !isFile && !!nav.serviceWorker && global.isSecureContext === true;
  }

  function registerServiceWorker() {
    if (!serviceWorkerAllowed()) return;
    const container = nav.serviceWorker;
    swState.controlled = !!container.controller;
    if (params.get("gaiusSw") === "0") {
      // Only this site's own worker: sibling profiles (and other sites on a shared origin such
      // as <user>.github.io) register theirs on the same origin.
      container.getRegistration("./").then((registration) => {
        if (registration && registration.scope === new URL("./", loc.href).href) registration.unregister();
      }).catch(() => {});
      record("service-worker", "disabled by ?gaiusSw=0");
      return;
    }
    const mode = coepMode();
    swState.coep = mode;
    const generation = typeof site.id === "string" ? site.id : "";
    const script = (site.serviceWorker || "gaius-sw.js") + "?coep=" + encodeURIComponent(mode)
      + "&generation=" + encodeURIComponent(generation);
    container.register(script, {scope: "./", updateViaCache: "none"}).then(() => {
      swState.registered = true;
    }, (error) => {
      swState.error = String(error && error.message || error);
      record("service-worker-failed", swState.error);
    });
  }

  function pruneServiceWorkerCache() {
    if (!serviceWorkerAllowed() || !nav.serviceWorker.controller || !site.assets) return;
    const keep = [];
    for (const name of Object.keys(site.assets)) keep.push(new URL(site.assets[name], loc.href).href);
    nav.serviceWorker.controller.postMessage({type: "gaius-sw-prune", generation: site.id || "", keep});
    swState.pruned = Date.now();
  }

  if (doc && doc.readyState === "complete") registerServiceWorker();
  else if (global.addEventListener) global.addEventListener("load", registerServiceWorker, {once: true});

  // --- title-screen idle work -----------------------------------------------------------------

  let idleWorkDone = false;
  // Consecutive idle server Worker load failures (constructor throw, Worker onerror for a
  // 404/CSP/offline script, or a failed bootstrap delivery) during the current stay on the
  // world-selection screens. The 250ms warm-up poll stops recreating the Worker after
  // IDLE_WORKER_MAX_FAILURES; leaving those screens resets the count.
  const IDLE_WORKER_MAX_FAILURES = 2;
  let idleWorkerFailures = 0;
  const noteIdleWorkerFailure = (detail) => {
    idleWorkerFailures++;
    record("singleplayer-worker-idle-failed", detail);
    if (idleWorkerFailures === IDLE_WORKER_MAX_FAILURES) {
      record("singleplayer-worker-idle-suspended", idleWorkerFailures + " consecutive failures");
    }
  };
  let titleSince = 0;

  function connectionIsConstrained() {
    const connection = nav.connection;
    if (!connection) return false;
    return connection.saveData === true || /(^|-)2g$/.test(String(connection.effectiveType || ""));
  }

  function prefetch(url) {
    if (!url) return Promise.resolve(false);
    const options = {cache: "force-cache"};
    try {
      options.priority = "low";
    } catch (_) {
      // older engines ignore unknown init members anyway
    }
    return global.fetch(url, options).then((response) => response.ok ? response.arrayBuffer().then(() => true) : false)
      .catch(() => false);
  }

  async function createIdleSingleplayerWorker() {
    if (isFile && !isPortable() || typeof global.Worker !== "function" ||
        params.get("gaiusPrefetch") === "0" || connectionIsConstrained() ||
        global.__gaiusMultiplayerOnly === true) return false;
    const existing = global.__gaiusIdleSingleplayerWorker;
    if (existing && existing.__gaiusIdle === true && !existing.__gaiusTerminal) {
      return true;
    }
    const configured = global.__gaiusSingleplayerWorkerUrl;
    let workerUrl = configured;
    let portableFilesPromise = null;
    if (!workerUrl && isPortable()) {
      const boot = global.__gaiusBoot;
      const runtimes = global.__gaiusPortableRuntimes;
      const wasm = serverWasm && serverWasm.serverRuntime === "wasm";
      const names = ["singleplayer-server-worker.js"];
      if (!wasm) return false;
      names.push(String(runtimes?.wasm?.server || "singleplayer-server.wasm"),
        ...WASM_SERVER_FILES.slice(1));
      if (!boot || typeof boot.filePayload !== "function" ||
          names.some((name) => !boot.hasFilePayload(name))) return false;
      portableFilesPromise = Promise.all(names.map((name) => boot.filePayload(name))).then((values) => {
        const source = new TextDecoder().decode(values[0]);
        const files = {};
        for (let index = 1; index < names.length; index++) {
          const bytes = values[index];
          files[names[index]] = bytes.byteOffset === 0 && bytes.byteLength === bytes.buffer.byteLength
            ? bytes.buffer : bytes.slice().buffer;
        }
        return {source, files};
      });
      workerUrl = global.URL.createObjectURL(new global.Blob([
        "(()=>{let q=[];self.onmessage=e=>{if(e.data?.type!=='gaius-worker-bootstrap'){q.push(e);return;}self.onmessage=null;self.__gaiusWorkerFiles=e.data.files||{};const u=URL.createObjectURL(new Blob([String(e.data.source||'')],{type:'text/javascript'}));try{importScripts(u)}finally{URL.revokeObjectURL(u)}for(const x of q)self.dispatchEvent(new MessageEvent('message',{data:x.data,ports:x.ports?Array.from(x.ports):[]}));q=null;};})();"
      ], {type: "text/javascript"}));
    }
    if (!workerUrl || String(workerUrl).indexOf("blob:") === 0 && !portableFilesPromise) return false;
    let worker;
    try {
      worker = new global.Worker(String(workerUrl), {name: "Gaius Integrated Server idle"});
    } catch (error) {
      noteIdleWorkerFailure(String(error && error.message || error));
      return false;
    }
    worker.__gaiusIdle = true;
    worker.__gaiusTerminal = false;
    // BrowserSingleplayerClient sets __gaiusAdopted when its start path takes this Worker over.
    // From then on the start path owns the Worker's lifetime; this branch may only finish the
    // one bootstrap delivery it already started and must never terminate it.
    worker.__gaiusAdopted = false;
    worker.onerror = function(event) {
      if (global.__gaiusIdleSingleplayerWorker !== worker || worker.__gaiusAdopted === true) return;
      global.__gaiusIdleSingleplayerWorker = null;
      worker.__gaiusTerminal = true;
      if (worker.__gaiusIdleTimeout) global.clearTimeout(worker.__gaiusIdleTimeout);
      try { worker.terminate(); } catch (_) {}
      noteIdleWorkerFailure(String(event && event.message || "worker error"));
    };
    global.__gaiusIdleSingleplayerWorker = worker;
    const ownsIdleWorker = () => global.__gaiusIdleSingleplayerWorker === worker &&
      worker.__gaiusAdopted !== true && !worker.__gaiusTerminal;
    if (portableFilesPromise) {
      // The blob loader waits for exactly one gaius-worker-bootstrap message. Publish the
      // delivery so an adopting start path reuses it instead of decoding and transferring the
      // tens of MB of server payload a second time.
      let deliveryError = null;
      worker.__gaiusBootstrapDelivery = portableFilesPromise.then((bundle) => {
        // Ownership can change while the payload decodes: re-check after the await.
        if (worker.__gaiusTerminal) return false;
        if (worker.__gaiusAdopted !== true && !ownsIdleWorker()) return false;
        worker.postMessage({type: "gaius-worker-bootstrap", source: bundle.source, files: bundle.files},
          Object.values(bundle.files));
        return true;
      }).catch((error) => {
        deliveryError = error;
        return false;
      });
      const delivered = await worker.__gaiusBootstrapDelivery;
      if (worker.__gaiusAdopted === true) {
        // The start path owns this Worker now; it handles a failed delivery itself.
        if (!delivered) {
          record("singleplayer-worker-idle-failed",
            String(deliveryError && deliveryError.message || deliveryError || "bootstrap not delivered"));
        }
        return delivered;
      }
      if (!delivered || !ownsIdleWorker()) {
        // Neither adopted nor still the idle Worker: an orphan this branch created.
        if (global.__gaiusIdleSingleplayerWorker === worker) global.__gaiusIdleSingleplayerWorker = null;
        if (!worker.__gaiusTerminal) {
          worker.__gaiusTerminal = true;
          try { worker.terminate(); } catch (_) {}
        }
        if (deliveryError) {
          noteIdleWorkerFailure(String(deliveryError && deliveryError.message || deliveryError));
        }
        return false;
      }
    }
    const armIdleTimeout = () => {
      worker.__gaiusIdleTimeout = global.setTimeout(() => {
        if (!ownsIdleWorker()) return;
        // The world-selection screens are one click from a launch: keep the warmed runtime.
        if (onSingleplayerFlowScreen()) {
          armIdleTimeout();
          return;
        }
        global.__gaiusIdleSingleplayerWorker = null;
        worker.__gaiusTerminal = true;
        try { worker.terminate(); } catch (_) {}
        record("singleplayer-worker-idle-timeout", "title idle timeout");
      }, 30000);
    };
    armIdleTimeout();
    // Compile and instantiate the server runtime now instead of after Create World. The Worker's
    // prewarm runs no Java code (server-worker-bootstrap.js prewarmServerRuntime); the start
    // message that adopts this Worker later still owns storage, Bootstrap and world loading.
    // installServerWorkerHook adds the Wasm module location and, once the page-side compile
    // (prewarmServerWasm) finished, the compiled module itself, so wait for that compile instead
    // of compiling the same bytes twice. An adopting start path posts its own prewarm.
    record("singleplayer-worker-idle-created", String(workerUrl));
    if (serverWasm) {
      try {
        await withTimeout(prewarmServerWasm(), 20000);
      } catch (_) {
        // The Worker compiles from the URL instead.
      }
    }
    if (!ownsIdleWorker()) return true;
    worker.__gaiusPrewarmPosted = true;
    try {
      worker.postMessage({
        type: "prewarm",
        sessionId: "idle-" + Date.now().toString(36) + "-" +
          Math.floor(Math.random() * 0x100000000).toString(36),
        profileId: global.__gaiusProfileId || null,
        parkMain: requestParkedMain(worker),
        // The parked Main may also decode the default data pack ahead of the click.
        prewarmWorld: srvInitMode(),
      });
      record("singleplayer-worker-idle-prewarm", worker.__gaiusParkRequested ? "parked-main" : "runtime");
    } catch (error) {
      // The start path's own prewarm/start remains the complete fallback.
      record("singleplayer-worker-idle-prewarm", "failed: " + String(error && error.message || error));
    }
    return true;
  }

  // The idle Worker also runs vanilla Main up to Bootstrap and parks there
  // (server-worker-bootstrap.js dispatchParkedMain): about a second of the start that no longer
  // waits for Create World. On the world-selection screens always; on the title screen only when
  // the page has room (onTitleScreenWithRoom), because there the user has not shown singleplayer
  // intent yet and an unused parked Main is dropped by the 30 s idle timeout. Not for low-memory
  // pages, and ?gaiusParkMain=0 keeps the compile-only prewarm.
  // The data pack prewarm of the parked Main: on by default, ?gaiusSrvInit=0 turns it off and
  // =miss runs it but makes the world load take the fallback path (a diagnostic).
  function srvInitMode() {
    const value = params.get("gaiusSrvInit");
    return value === "miss" ? "miss" : value !== "0";
  }

  function requestParkedMain(worker) {
    if (worker.__gaiusParkRequested) return true;
    // The parked Main hook is part of the Wasm server path.
    if (!serverWasm || serverWasm.serverRuntime !== "wasm") return false;
    if (!(onSingleplayerFlowScreen() || onTitleScreenWithRoom()) || params.get("gaiusParkMain") === "0") return false;
    const tier = global.__gaiusMemoryTier;
    if (tier && tier.lowMemory) return false;
    worker.__gaiusParkRequested = true;
    return true;
  }

  // The title screen on a page with memory and cores to spare (the bar the boot-time server
  // prewarm uses): the idle Worker may run Main to Bootstrap there too. The singleplayer click is
  // the most likely next input, and a parked Main that nobody uses is dropped by the idle timeout.
  function onTitleScreenWithRoom() {
    const state = global.__gaiusMinecraftState;
    if (!state || state.level) return false;
    const screen = state.screen ? String(state.screen) : "";
    return (screen === "TitleScreen" || screen.slice(-12) === ".TitleScreen") &&
      Number(nav.deviceMemory) >= 8 && Number(nav.hardwareConcurrency) >= 4 &&
      params.get("gaiusTitlePark") !== "0";
  }

  // The world-selection flow (Singleplayer -> Select World / Create World). Reaching it means a
  // launch is a click away, so the warm-up must not wait for the title screen to sit idle.
  function onSingleplayerFlowScreen() {
    const state = global.__gaiusMinecraftState;
    if (!state || state.level) return false;
    const screen = state.screen ? String(state.screen) : "";
    return /(?:^|\.)(?:SelectWorldScreen|CreateWorldScreen)$/.test(screen) ||
      screen.indexOf(".worldselection.") >= 0;
  }

  global.__gaiusCancelIdleSingleplayerWorker = function(reason) {
    const worker = global.__gaiusIdleSingleplayerWorker;
    global.__gaiusIdleSingleplayerWorker = null;
    if (!worker || worker.__gaiusAdopted === true) return false;
    worker.__gaiusTerminal = true;
    if (worker.__gaiusIdleTimeout) global.clearTimeout(worker.__gaiusIdleTimeout);
    try { worker.terminate(); } catch (_) {}
    record("singleplayer-worker-idle-cancelled", String(reason || "cancelled"));
    return true;
  };

  function runIdleWork() {
    if (idleWorkDone) return;
    idleWorkDone = true;
    publishTimeline();
    pruneServiceWorkerCache();
    // Portable/file pages cannot prefetch sibling URLs, but their embedded payload may become
    // available only after the body bootstrap has decoded it. Give the module cache one more
    // chance before the distribution-form early return.
    prewarmServerWasm();
    const warmKernels = !connectionIsConstrained() && params.get("gaiusPrefetch") !== "0"
      && global.__gaiusKernels && typeof global.__gaiusKernels.warm === "function"
      ? global.__gaiusKernels.warm(["mesher", "light"])
      : Promise.resolve([]);
    createIdleSingleplayerWorker();
    // Nothing to warm from disk: a file:// page cannot fetch its siblings (design D13).
    if (isPortable() || isFile || params.get("gaiusPrefetch") === "0" || connectionIsConstrained()) {
      warmKernels.then(() => record("kernel-warm-done", "title-idle"));
      return;
    }
    // The singleplayer payload is only needed when a world opens; warm the caches now.
    const targets = [global.__gaiusSingleplayerWorkerUrl];
    if (serverWasm && serverWasm.serverWasmUrl) targets.push(serverWasm.serverWasmUrl);
    if (serverWasm && Array.isArray(serverWasm.wasmRuntimeScripts)) {
      for (const entry of serverWasm.wasmRuntimeScripts) {
        if (entry && entry.url) targets.push(entry.url);
      }
    }
    const jobs = targets.filter((target) => target && String(target).indexOf("blob:") !== 0)
      .map((target) => prefetch(target));
    Promise.all([warmKernels, Promise.all(jobs)]).then(([warm, prefetched]) => {
      record("kernel-warm-done", Array.isArray(warm) ? warm.length : "title-idle");
      record("prefetch-done", prefetched.length);
    });
  }

  // How long the title screen has to show before the idle work (server module compile, kernel
  // warm-up, idle server Worker with its parked Main) starts. The work only starts once the title
  // screen is up, so it never delays the title; starting it at the next poll instead of after 2.5 s
  // (and an idle callback that waits up to 5 s, here 0.5 s: the title screen and the pipeline
  // warm-up leave the main thread no idle period, so the callback runs at its timeout) is what lets a user who clicks Singleplayer within a few
  // seconds find a finished Bootstrap instead of waiting for it behind Create World, and moves
  // the main-thread part of the work (payload decode) out of the world-entry window.
  // ?gaiusTitleIdleMs=2500 restores the former grace period.
  function titleIdleGraceMs() {
    const requested = Number(params.get("gaiusTitleIdleMs"));
    return params.get("gaiusTitleIdleMs") !== null && Number.isFinite(requested) && requested >= 0
      ? Math.min(requested, 60000) : 0;
  }

  function watchTitleScreen() {
    const timer = global.setInterval(() => {
      const state = global.__gaiusMinecraftState;
      const screen = state && state.screen ? String(state.screen) : "";
      const onTitle = (screen === "TitleScreen" || screen.slice(-12) === ".TitleScreen")
        && state.running === true && !state.overlay;
      if (state && state.level && !idleWorkDone) {
        // A world opened before the title idled: do the bookkeeping, skip the prefetch.
        idleWorkDone = true;
        publishTimeline();
        pruneServiceWorkerCache();
      }
      if (!onTitle) {
        titleSince = 0;
        if (idleWorkDone) global.clearInterval(timer);
        return;
      }
      if (!titleSince) {
        titleSince = Date.now();
        mark("title-screen");
        return;
      }
      if (Date.now() - titleSince < titleIdleGraceMs() || idleWorkDone) return;
      global.clearInterval(timer);
      if (typeof global.requestIdleCallback === "function") global.requestIdleCallback(runIdleWork, {timeout: 500});
      else global.setTimeout(runIdleWork, 0);
    }, 250);
  }
  // A memory-rich site can overlap runtime-only server instantiation with the client boot.
  // The existing prewarm handshake runs no Java before world selection; its Worker is adopted
  // by the normal start path. Keep small devices, constrained links and file payloads deferred.
  if (site && serverWasm && Number(nav.deviceMemory) >= 8 && Number(nav.hardwareConcurrency) >= 4
      && params.get("gaiusBootServer") !== "0") {
    applyAssetUrls();
    Promise.resolve().then(() => createIdleSingleplayerWorker()).then((started) => {
      if (started) record("singleplayer-worker-boot-prewarm", "runtime");
    }).catch((error) => record("singleplayer-worker-boot-prewarm-failed", String(error && error.message || error)));
  }
  watchTitleScreen();

  // A user who leaves the title within its idle grace period (or before requestIdleCallback
  // fires behind the render loop) used to reach Create World with nothing warmed: the server
  // Worker, its Wasm compile and the kernel pool all started after the click. Start the same
  // idle work as soon as the world-selection flow opens, and replace an idle Worker that timed
  // out or was adopted by a previous launch.
  (() => {
    const launchWorkerAlive = () => {
      const workers = global.__gaiusSingleplayerWorkers;
      let alive = false;
      if (workers && typeof workers.forEach === "function") {
        workers.forEach((worker) => { if (worker && !worker.__gaiusTerminal) alive = true; });
      }
      return alive;
    };
    global.setInterval(() => {
      if (!onSingleplayerFlowScreen()) {
        // Leaving the world-selection screens ends the stay; the next visit may retry.
        idleWorkerFailures = 0;
        return;
      }
      if (!idleWorkDone) {
        runIdleWork();
        return;
      }
      // A launching, running or stopping world owns the server Worker slot; its own start path
      // (or the next visit to these screens) handles warming.
      if (launchWorkerAlive()) return;
      const existing = global.__gaiusIdleSingleplayerWorker;
      if (!existing || existing.__gaiusTerminal) {
        // A Worker script that fails to load (404, CSP, offline) fails again on every poll.
        if (idleWorkerFailures >= IDLE_WORKER_MAX_FAILURES) return;
        createIdleSingleplayerWorker();
      } else if (existing.__gaiusPrewarmPosted && !existing.__gaiusParkRequested &&
          existing.__gaiusAdopted !== true && requestParkedMain(existing)) {
        try {
          existing.postMessage({type: "park-main", prewarmWorld: srvInitMode()});
          record("singleplayer-worker-idle-prewarm", "parked-main");
        } catch (_) {
          // The start path dispatches Main itself.
        }
      }
    }, 250);
  })();

  // Cheap startup observability. These marks are intentionally one-shot and do not
  // alter scheduling; they let Pages startup traces expose the critical path.
  (() => {
    const started = {title: false, present: false, server: false, kernel: false, shader: false};
    const timer = global.setInterval(() => {
      const state = global.__gaiusMinecraftState;
      if (!started.title && state && String(state.screen || "").endsWith("TitleScreen")) {
        started.title = true; mark("title-screen-frame");
      }
      const gpu = global.GaiusWebGpu;
      if (!started.present && gpu && gpu.stats && gpu.stats.presents > 0) {
        started.present = true; mark("first-title-frame-presented");
      }
      if (!started.shader && gpu && gpu.stats && (gpu.stats.pipelinesRecorded || gpu.stats.pipelinesOnDemand)) {
        started.shader = true; mark("shader-pipeline-ready");
      }
      if (!started.server && global.__gaiusSingleplayerServerState
          && global.__gaiusSingleplayerServerState.ready) {
        started.server = true; mark("server-worker-ready");
      }
      if (!started.kernel && global.__gaiusKernelRuntime && global.__gaiusKernelRuntime.ready) {
        started.kernel = true; mark("kernel-worker-ready");
      }
      if (started.title && started.present && started.shader && started.server && started.kernel) {
        global.clearInterval(timer);
      }
    }, 50);
  })();

  // --- boot timeline --------------------------------------------------------------------------

  const timeline = [];

  function publishTimeline() {
    const timings = global.__gaiusBootTimings;
    if (!timings || !perf || typeof perf.mark !== "function") return timeline;
    for (const key of Object.keys(timings)) {
      const value = timings[key];
      if (typeof value !== "number" || !isFinite(value)) continue;
      if (timeline.some((entry) => entry.name === key)) continue;
      timeline.push({name: key, at: value});
      try {
        perf.mark("gaius:" + key, {startTime: value});
      } catch (_) {
        // engines without mark options still get the table below
      }
    }
    timeline.sort((a, b) => a.at - b.at);
    return timeline;
  }

  // --- launcher hooks -------------------------------------------------------------------------

  // The JavaScript client of this distribution as a script URL, for the late fallback.
  async function jsClientUrl() {
    if (clientRuntime.form === "portable") {
      // The portable page decodes its embedded classes.js only on demand in wasm mode.
      const load = global.__gaiusPortableLoadJsClient;
      return typeof load === "function" ? load() : null;
    }
    if (site) {
      if ((useGzip || !hasAsset("classes.js")) && hasAsset("classes.js.gz") && typeof global.DecompressionStream === "function") {
        return gunzipToBlobUrl(assetUrl("classes.js.gz"), "text/javascript");
      }
      return hasAsset("classes.js") ? siblingUrl("classes.js") : null;
    }
    return new URL("classes.js", loc.href).href;
  }

  // The module did not load or instantiate although the probes passed (a feature the probes do
  // not cover, a truncated download, out of memory): start the JavaScript client instead.
  async function startJsClientInstead(error) {
    if (clientRuntime.mainStarted || !clientRuntime.jsBuilt) throw error;
    const message = String(error && error.message || error);
    fallBackToEagerSounds("runtime-fallback");
    // The legacy JS client only reads the merged core archive. If Wasm failed after the sound
    // sidecar was selected, restore that view before loading classes.js so sounds are preserved.
    startSoundLoad();
    await soundsReady;
    if (global.__gaiusVanillaSounds && global.__gaiusVanillaAssets) {
      try {
        mergeSoundPack(global.__gaiusVanillaAssets, global.__gaiusVanillaSounds.bytes);
        global.__gaiusVanillaSounds = null;
        soundsState.layout = "merged-fallback";
        record("sounds-merged-for-js-fallback", "legacy archive");
      } catch (soundError) {
        record("sound-fallback-merge-failed", soundError && soundError.message);
        throw soundError;
      }
    }
    let url = null;
    try {
      url = await jsClientUrl();
    } catch (lookupError) {
      record("runtime-fallback-failed", lookupError && lookupError.message);
    }
    if (!url || !doc || !doc.createElement) throw error;
    clientRuntime.selected = "js";
    clientRuntime.fallback = true;
    clientRuntime.late = true;
    clientRuntime.reason = "wasm-load-failed:" + message.slice(0, 200);
    {
      serverWasm = null;
      if (global.GaiusWebGpu && typeof global.GaiusWebGpu.abandon === "function") {
        global.GaiusWebGpu.abandon("client-runtime-fallback");
      }
      gpuSelection.requested = "webgl2";
      webGpuFallback("client-runtime-fallback");
      publishGpuTimings();
    }
    record("runtime-fallback", clientRuntime.reason);
    if (global.console && typeof global.console.warn === "function") {
      global.console.warn("Gaius: the WebAssembly GC client did not start (" + message
        + "); starting the JavaScript client");
    }
    mark("runtime-js");
    global.__gaiusClassesUrl = url;
    await loadClassicScript(url);
  }

  // Wasm: the instantiated module (gaius-wasm-boot.js __gaiusClientReady), so main(args) starts
  // at once. Returns the time the client became ready (performance.now()).
  async function clientReady() {
    const timings = global.__gaiusBootTimings;
    if (clientRuntime.selected !== "wasm") return timings && timings.classesLoaded;
    try {
      await global.__gaiusClientReady;
      const wasm = global.__gaiusWasmTimings;
      return wasm && typeof wasm.instantiated === "number" ? wasm.instantiated : nowMs();
    } catch (error) {
      await startJsClientInstead(error);
      return nowMs();
    }
  }

  function commitClientRuntime() {
    if (clientRuntime.selected !== "wasm" || clientRuntime.mainStarted) return false;
    clientRuntime.mainStarted = true;
    const release = global.__gaiusPortableReleaseJsFallback;
    if (typeof release === "function") {
      try { record("portable-frozen-js-release", release() ? "released" : "retained"); }
      catch (error) { record("portable-frozen-js-release-failed", error && error.message); }
    }
    return true;
  }

  // Shared boot telemetry of both clients in the launcher's __gaiusBootTimings.
  function publishRuntimeTimings(readyAt) {
    const timings = global.__gaiusBootTimings;
    if (!timings || typeof timings !== "object") return;
    timings.runtime = clientRuntime.selected;
    timings.runtimeSelected = clientRuntime.at;
    if (clientRuntime.fallback) timings.runtimeFallback = clientRuntime.reason;
    else delete timings.runtimeFallback;
    if (typeof readyAt === "number" && isFinite(readyAt)) timings.clientReady = readyAt;
  }

  // Awaited by the launcher right before main(args): the quality layer must be loaded (bounded,
  // a missing file never blocks the boot) and its tier chosen; eager sounds must be merged; the
  // client must be ready to start synchronously.
  async function beforeMain() {
    if (clientRuntime.selected === "unsupported") return;
    if (global.__gaiusJsClientSourceReady) await global.__gaiusJsClientSourceReady;
    mark("before-main");
    publishRuntimeTimings();
    await withTimeout(qualityReady, 5000);
    const quality = global.GaiusQuality;
    if (quality && quality.caps && typeof quality.caps.ensureTier === "function") {
      // The WebGPU adapter signal of the automatic tier was requested at load time; a boot that
      // classifies the machine waits at most 300 ms more for it (normally it has arrived).
      if (typeof quality.caps.adapterReady === "function") {
        try {
          await withTimeout(Promise.resolve(quality.caps.adapterReady()).catch(() => null), 300);
        } catch (error) {
          record("quality-adapter-failed", error && error.message);
        }
      }
      // The tier Worker (on unless ?gaiusTierWorker=0) measures off the main thread from load time on;
      // prepareTier waits (bounded) for its result, or runs the paired study (?gaiusTierStudy=1).
      if (typeof quality.caps.prepareTier === "function") {
        try {
          await withTimeout(Promise.resolve(quality.caps.prepareTier()).catch(() => null), 30000);
        } catch (error) {
          record("quality-prepare-failed", error && error.message);
        }
      }
      try {
        quality.caps.ensureTier();
      } catch (error) {
        record("quality-tier-failed", error && error.message);
      }
      try {
        const probe = typeof quality.caps.probeInfo === "function" ? quality.caps.probeInfo() : null;
        if (probe && probe.path) record("quality-tier-probe", probe.path + (probe.worker && probe.worker.reason ? " (" + probe.worker.reason + ")" : ""));
      } catch (error) {
        // Diagnostics only.
      }
    }
    await prepareGpu();
    if (gpuSelection.selected === "webgpu" && global.__gaiusShaderPacks &&
        typeof global.__gaiusShaderPacks.setAvailable === "function") {
      try { global.__gaiusShaderPacks.setAvailable(true); } catch (error) { record("shader-pack-ui-failed", error && error.message); }
    }
    if (shaderToolchainRequired) {
      await webGpuShaderToolchain;
      record("webgpu-shader-toolchain", "loaded");
    }
    publishGpuTimings();
    await initSnapshotPromise;
    await soundsGate;
    publishRuntimeTimings(await clientReady());
    mark("client-ready");
    mark("main");
  }

  function assetsReady() {
    return assetsReadyPromise;
  }

  global.__gaiusBoot = {
    version: BOOT_VERSION,
    site,
    startedAt: nowMs(),
    // The client runtime selection (window.__gaiusRuntimeSelection): requested, source,
    // selected, fallback, reason, form, wasmBuilt, features.
    runtime: clientRuntime,
    // The graphics backend selection (window.__gaiusGpuSelection): requested, source, selected
    // (webgpu, webgl2, or pending until beforeMain), fallback, reason, inject, initMs, backend().
    gpu: gpuSelection,
    get serverRuntime() { return serverWasm ? "wasm" : "js"; },
    handlesWasmFailure: true,
    commitClientRuntime,
    assetUrl,
    filePayload,
    hasFilePayload,
    applyAssetUrls,
    assetsReady,
    initSnapshot: () => initSnapshotState,
    atlasPrebake: () => global.__gaiusAtlasPrebake,
    beforeMain,
    mark,
    publishTimeline,
    timeline,
    events,
    kernels,
    serviceWorker: swState,
    sounds: () => soundsState,
    stopSoundPolling: stopSoundTitlePoll,
    modules: {kernels: kernelScriptsReady, quality: qualityReady, webgpu: webGpuReady},
    mergeSoundPack,
  };

  // A streamed client/server Wasm fetch can outlive the document during a fast reload.  Drop
  // the shell preload and cancel its body so the old renderer does not retain a Response, stream
  // reader, and compiled-module continuation while the successor starts.
  let bootPreloadsTerminated = false;
  global.__gaiusTerminateBootPreloads = function (reason) {
    if (bootPreloadsTerminated) return false;
    bootPreloadsTerminated = true;
    try {
      if (typeof global.__gaiusCancelIdleSingleplayerWorker === "function") {
        global.__gaiusCancelIdleSingleplayerWorker(reason || "pagehide");
      }
    } catch (_) {}
    try {
      const preload = global.__gaiusWasmHttpPreload;
      global.__gaiusWasmHttpPreload = null;
      const pending = preload && preload.response;
      if (pending && typeof pending.then === "function") {
        pending.then((response) => {
          try {
            if (response && response.body && typeof response.body.cancel === "function") {
              return response.body.cancel(reason || "pagehide");
            }
          } catch (_) {}
          return undefined;
        }, () => undefined);
      }
    } catch (_) {}
    try {
      if (typeof global.__gaiusAbortServerWasmPrewarm === "function") {
        global.__gaiusAbortServerWasmPrewarm(reason || "pagehide");
      }
    } catch (_) {}
    return true;
  };

  // Explicitly release graphics resources before a reload. The browser normally tears down a
  // document eventually, but an active WebGPU device, WebGL context, wasm callbacks, and JS
  // resource tables can otherwise remain resident while the successor page starts.
  global.__gaiusTerminateGraphics = function (reason) {
    if (global.__gaiusGraphicsTerminated) return false;
    global.__gaiusGraphicsTerminated = true;
    // Losing the context here is intentional: the document is already leaving. Prevent the
    // normal context-loss recovery path from racing that navigation with another page reload.
    global.__gaiusDisableGpuContextAutoReload = true;
    try {
      const post = global.GaiusQuality && global.GaiusQuality.webgpuPost;
      if (post && typeof post.dispose === "function") post.dispose();
      const shaderPack = global.GaiusQuality && global.GaiusQuality.shaderPack;
      if (shaderPack && typeof shaderPack.dispose === "function") shaderPack.dispose();
    } catch (error) {
      record("gpu-teardown-error", "quality:" + (error && error.message || error));
    }
    try {
      if (global.GaiusWebGpu && typeof global.GaiusWebGpu.terminate === "function") {
        global.GaiusWebGpu.terminate(reason || "pagehide");
      }
    } catch (error) {
      record("gpu-teardown-error", String(error && (error.message || error)));
    }
    try {
      // Reading the deferred GLFW accessor on pagehide would create a brand-new WebGL context.
      // Use the attached context only, and leave a pending accessor untouched.
      const glfw = global.__gaiusGlfwContext;
      const descriptor = Object.getOwnPropertyDescriptor(global, "__gaiusWebGL");
      const gl = (glfw && glfw.gl) || (descriptor && Object.prototype.hasOwnProperty.call(descriptor, "value")
        ? descriptor.value : null);
      const lose = gl && gl.getExtension && gl.getExtension("WEBGL_lose_context");
      if (lose && typeof lose.loseContext === "function") lose.loseContext();
    } catch (error) {
      record("webgl-teardown-error", String(error && (error.message || error)));
    }
    try {
      const state = global.__gaiusGL;
      if (state && typeof state === "object") {
        for (const value of Object.values(state)) {
          if (value && typeof value.clear === "function") value.clear();
        }
      }
      global.__gaiusWebGL = null;
      global.__gaiusGL = null;
      global.__gaiusGlfwContext = null;
      global.__gaiusGpuBackend = null;
    } catch (error) {
      record("graphics-state-teardown-error", String(error && (error.message || error)));
    }
    record("graphics-teardown", reason || "pagehide");
    return true;
  };

  // --- memory pacing ----------------------------------------------------------------------------

  // Every frame allocates WebGPU wrappers (a GPURenderPassEncoder and its descriptors for each of
  // 20-25 passes) and chunk and kernel messages allocate ArrayBuffers. They die young, but while
  // the game renders the page never idles, the WebAssembly GC heap barely grows and V8 starts no
  // full collection for minutes; each wrapper keeps its GPU-process object until then (a
  // 3-minute session grew the GPU process from 0.8 to 2.3 GB, the renderer by 1.5 GB).
  // measureUserAgentSpecificMemory() makes the browser collect the page and its Workers at its
  // next convenient point (Chrome waits up to ~20 s). A full collection of this heap costs
  // frames, though (5-10 frames over 20 ms each), so the pacing asks only at moments where no
  // gameplay frame is measured: the title and other menus, the pause screen, loading and
  // progress screens, a hidden page; at most every MIN_QUIET_GAP_MS. In a world without such a
  // moment it asks only once the frames since the last answer imply a large backlog
  // (WORLD_BACKLOG_PASSES render passes, about 1.5 GB of wrappers and their GPU objects) and at
  // most every MIN_WORLD_GAP_MS, which is where V8 would collect on its own anyway.
  // The adaptive policy is for WebGPU wrapper retention, not WebGL2. Measuring a WebGL2
  // loading/menu screen forces a full collection despite there being no WebGPU pass backlog.
  // Cross-origin-isolated pages only (the site). ?gaiusGcPacing=0 turns it off;
  // ?gaiusGcPacing=<ms> asks again <ms> after every answer instead (diagnostics).
  // __gaiusMemoryTelemetry.uaMemory keeps the latest answer and the request reasons.
  (function startMemoryPacing() {
    const setting = params.get("gaiusGcPacing");
    if (setting === "0" || setting === "off") return;
    if (!global.crossOriginIsolated || !perf || typeof perf.measureUserAgentSpecificMemory !== "function") return;
    const fixed = Number(setting);
    const fixedPauseMs = Number.isFinite(fixed) && fixed >= 1000 ? fixed : 0;
    const CHECK_MS = 1000;
    const MIN_QUIET_GAP_MS = 30000;
    const MIN_WORLD_GAP_MS = 180000;
    const WORLD_BACKLOG_PASSES = 1500000;
    const telemetry = global.__gaiusMemoryTelemetry || (global.__gaiusMemoryTelemetry = {});
    const state = telemetry.uaMemory = {mode: fixedPauseMs ? "fixed" : "adaptive", requests: 0, bytes: null, peakBytes: 0,
      waitMs: null, at: null, error: null, reasons: {}};
    let stopped = false;
    let pending = false;
    let lastAnswer = -Infinity;
    let passesAtAnswer = 0;
    const passes = () => {
      const stats = global.GaiusWebGpu && global.GaiusWebGpu.stats;
      return stats && Number.isFinite(stats.passes) ? stats.passes : 0;
    };
    function measure(reason) {
      if (stopped || pending) return;
      pending = true;
      const started = nowMs();
      state.requests++;
      state.reasons[reason] = (state.reasons[reason] || 0) + 1;
      let result;
      try {
        result = perf.measureUserAgentSpecificMemory();
      } catch (error) {
        state.error = String(error && (error.message || error));
        record("memory-pacing-stopped", state.error);
        stopped = true;
        return;
      }
      Promise.resolve(result).then((value) => {
        pending = false;
        state.bytes = value && Number.isFinite(value.bytes) ? value.bytes : null;
        if (state.bytes !== null && state.bytes > state.peakBytes) state.peakBytes = state.bytes;
        state.waitMs = Math.round(nowMs() - started);
        state.at = Date.now();
        lastAnswer = nowMs();
        passesAtAnswer = passes();
        if (fixedPauseMs && !stopped) global.setTimeout(() => measure("fixed"), fixedPauseMs);
      }, (error) => {
        pending = false;
        stopped = true;
        state.error = String(error && (error.message || error));
        record("memory-pacing-stopped", state.error);
      });
    }
    function check() {
      if (stopped) return;
      state.backend = global.__gaiusGpuBackend || null;
      if (!pending && state.backend === "webgpu") {
        const since = nowMs() - lastAnswer;
        const quiet = quietReason();
        if (quiet !== null && since >= MIN_QUIET_GAP_MS) measure(quiet);
        else if (quiet === null && since >= MIN_WORLD_GAP_MS && passes() - passesAtAnswer >= WORLD_BACKLOG_PASSES) measure("backlog");
      }
      global.setTimeout(check, CHECK_MS);
    }
    if (fixedPauseMs) global.setTimeout(() => measure("fixed"), fixedPauseMs);
    else global.setTimeout(check, CHECK_MS);
    if (global.addEventListener) global.addEventListener("pagehide", () => { stopped = true; }, {once: true});
  })();

  if (global.addEventListener) {
    global.addEventListener("pagehide", function () {
      try { stopSoundTitlePoll("pagehide"); } catch (_) {}
      try { if (global.__gaiusHeapGuard && global.__gaiusHeapGuard.stop) global.__gaiusHeapGuard.stop(); } catch (_) {}
      try { global.__gaiusTerminateBootPreloads("pagehide"); } catch (_) {}
      try { global.__gaiusTerminateGraphics("pagehide"); } catch (_) {}
      try { global.__gaiusTerminateKernels("pagehide"); } catch (_) {}
      try { if (typeof global.__gaiusTerminateWasm === "function") global.__gaiusTerminateWasm("pagehide"); } catch (_) {}
    }, {once: true});
  }
})(typeof window !== "undefined" ? window : globalThis);
