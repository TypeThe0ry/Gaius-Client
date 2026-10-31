/*
 * WebGPU glue of the Gaius WebGPU core (port/gpu/webgpu-core, Java package dev.gaius.gpu.webgpu).
 *
 * The core is called by thin per-generation render seams (port/mc/seams/<generation>, for example
 * renderpearl-1 for 26.3, which implements vanilla's backend SPI) and calls the functions of
 * globalThis.GaiusWebGpu through @JSBody. Only the core calls this glue. Every GPU object lives in
 * a handle table here; Java only holds integer handles, so the same Java code runs on the TeaVM JS
 * target and on the WasmGC target (plain imports, no object marshalling on the hot path).
 *
 * Conventions shared with the Java side and with the SPIR-V -> WGSL translator:
 *   - Binding contract ("Split" layout). For the pipeline's flat uniform list
 *     (renderpearl BackendRenderPipeline.CreateInfo.uniforms), uniform i is
 *       UNIFORM_BUFFER          -> @group(0) @binding(i), dynamic offset
 *       COMBINED_IMAGE_SAMPLER  -> @group(1) @binding(2i) texture, @group(1) @binding(2i + 1) sampler
 *       TEXEL_BUFFER            -> @group(1) @binding(2i) read-only storage buffer
 *     The Java core sends each pipeline's bind group layout entries explicitly (from the
 *     translator's reflection, or the rule above for WGSL it was given), so the glue builds
 *     whatever layout the shaders declare; uniform buffers always live in group 0.
 *   - Orientation. Translated vertex shaders negate the clip-space y, so textures keep the
 *     OpenGL/Vulkan row order vanilla expects (row 0 is the bottom of the image); the winding is
 *     then clockwise like vanilla's Vulkan backend (frontFace "cw"), and the surface blit samples
 *     the source so that row 0 ends up at the bottom of the canvas.
 *   - Upload order. queue.writeBuffer takes effect before the
 *     whole next submission, vanilla's writeToBuffer between the commands around it. Ordered
 *     writes therefore go through the staging ring and an encoded copyBufferToBuffer; only the
 *     per-submit uploads of transient memory, which every command of the submit may read, may
 *     fall back to queue.writeBuffer.
 *
 * Device acquisition is asynchronous in WebGPU, vanilla's device creation is not: the page calls
 * GaiusWebGpu.init(canvas) before the Java main starts (gaius-boot.js, ?gpu=webgpu, bounded by
 * 5 s; abandon() turns a late answer into a failure) and the seam's loadLibrary() only reads the
 * outcome (state() === 2), so vanilla falls back to the next backend (WebGL 2) otherwise.
 *
 * Canvas rules. The adapter, the device and the canvas format are checked first;
 * the canvas's "webgpu" context is only taken by the first surface configure (rule 1), and the
 * canvas is marked __gaiusContextKind = "webgpu". A canvas keeps its first context kind, so the GL
 * shims give a later WebGL 2 context a fresh element (window.__gaiusCanvasHost.replace, rule 2).
 * init(canvas, {inject: "after-configure"}) is the fallback test: it configures the canvas and
 * then fails, so the GL backend must attach anyway; taintCanvas() takes a "webgpu" context on the
 * canvas for the GLFW profiles' version of that test. {inject: "lose-device"} destroys the device
 * 3 s after the first frame: a device lost while the game renders shows the reload prompt.
 *
 * Which backend renders: "active" is true once the core created its device and the WebGPU
 * surface is the game's (window.__gaiusGpuBackend === "webgpu"; the GL shims set "webgl2").
 * contextAvailable() and caps() are the backend-neutral questions non-GL code asks under WebGPU
 * instead of looking for window.__gaiusWebGL.
 *
 * Pipelines. createPipeline() parses and records the descriptor and returns
 * a handle at once; the GPU pipeline (and its shader modules) is created on first use, which is
 * the title screen's set first, and the rest in idle time under a budget through
 * createRenderPipelineAsync (pipelineMode "eager" restores the synchronous creation for A/B runs).
 * startup / pipelineTelemetry() report what was created when and how long it took.
 */
(function (root) {
  "use strict";

  if (root.GaiusWebGpu && root.GaiusWebGpu.glueVersion) {
    return;
  }

  var G = {};
  G.glueVersion = 3;
  var terminated = false;
  var generation = 0;
  // Surface submissions are retired by the browser's canvas FIFO, not by the JavaScript task
  // that records them. Keep only a small number of those submissions ahead of the displayed frame;
  // the WasmGC frame pacer waits on this gate before starting the next uncapped world frame.
  var surfaceSubmitted = 0;
  var surfaceCompleted = 0;
  var surfaceGate = null;
  var surfaceBackpressureCap = 3;

  function now() {
    return root.performance && root.performance.now ? root.performance.now() : Date.now();
  }

  // Startup telemetry (G3): init phases and pipeline creation, in performance.now() ms.
  G.startup = {
    initStart: 0,
    adapterAt: 0,
    deviceAt: 0,
    readyAt: 0,
    translatorAt: 0,
    deviceInUseAt: 0,
    surfaceAt: 0,
    firstPresentAt: 0,
    pipelinesAtFirstPresent: 0,
    pipelinesCreatedAtFirstPresent: 0,
    allPipelinesCreatedAt: 0
  };

  // ---- handle table -----------------------------------------------------------------------

  // Handles are never reused: the Java side caches bind groups by the handles of the resources
  // they reference, so a recycled handle would alias a destroyed resource.
  var objects = [null];
  var released = 0;
  var terminated = false;
  var idleHandle = null;

  function put(object) {
    objects.push(object);
    return objects.length - 1;
  }

  function get(handle) {
    return objects[handle];
  }

  function release(handle) {
    if (handle > 0 && handle < objects.length && objects[handle] !== null) {
      var object = objects[handle];
      if (object.state === "pending" && object.desc) {
        // A recorded pipeline closed before it was ever created.
        object.released = true;
        pipelineSettled(object, "released");
      }
      objects[handle] = null;
      released++;
    }
  }

  G.liveHandles = function () {
    return objects.length - 1 - released;
  };

  /** Diagnostics: the object behind a handle (GPU objects, pipeline records, shader records). */
  G.debugObject = function (handle) {
    return handle > 0 && handle < objects.length ? objects[handle] : null;
  };

  // ---- state and diagnostics --------------------------------------------------------------

  // 0 = not started, 1 = pending, 2 = ready, -1 = failed.
  G.status = 0;
  G.failure = "";
  G.lostReason = "";
  G.messages = [];
  G.stats = {
    passes: 0,
    passSplits: 0,
    // Passes that continued the previous one (same views, load operations, nothing for the frame
    // encoder in between; see "merged passes").
    passesMerged: 0,
    clearFolds: 0,
    // Folded passes that a later command opened after all.
    clearFoldsOpened: 0,
    // Frames presented to the canvas (a surface texture acquired, blitted and presented).
    presents: 0,
    draws: 0,
    indirectDraws: 0,
    indirectBatches: 0,
    submits: 0,
    submitMsTotal: 0,
    submitMsMax: 0,
    acquireMsTotal: 0,
    acquireMsMax: 0,
    presentMsTotal: 0,
    presentMsMax: 0,
    pipelines: 0,
    bindGroups: 0,
    writeBufferCalls: 0,
    writeBufferBytes: 0,
    writeTextureCalls: 0,
    errors: 0,
    // Staging ring (see "staging ring" below). Counters are cumulative; the core reads them.
    ringUploads: 0,
    ringUploadBytes: 0,
    ringOrdered: 0,
    ringOrderedBytes: 0,
    ringOrderedRefused: 0,
    ringFallbacks: 0,
    ringFallbackBytes: 0,
    largeWrites: 0,
    largeWriteBytes: 0,
    ringBlocks: 0,
    ringBytes: 0,
    ringPending: 0,
    ringMapP95: 0,
    lastSubmitUploadBytes: 0,
    translations: 0,
    translateErrors: 0,
    translateMs: 0,
    translationCacheHits: 0,
    prefetches: 0,
    prefetchErrors: 0,
    // Pipeline creation (see "shaders and pipelines"): descriptors recorded, GPU pipelines created
    // on first use (synchronously, with their JS time) and in idle time (asynchronously, with the
    // time from issue to resolution), failures, and draws skipped on an invalid pipeline.
    shaderModules: 0,
    pipelinesRecorded: 0,
    pipelinesOnDemand: 0,
    pipelineOnDemandMs: 0,
    pipelinesIdle: 0,
    pipelineIdleMs: 0,
    // Pipelines queued ahead for idle creation (what a world draws first), and pipelines created
    // on first use after the first frame: each such creation stalls a frame.
    pipelinesPriority: 0,
    pipelinesOnDemandAfterFirstFrame: 0,
    pipelineOnDemandAfterFirstFrameMs: 0,
    pipelineFailures: 0,
    skippedDraws: 0,
    // copyBufferToTexture calls, and the rows copied one by one for layouts tighter than 256 bytes.
    textureCopies: 0,
    textureCopyRows: 0,
    // The core's downgrade table (count()): triangle fans drawn as indexed triangle lists, indexed
    // fans skipped (not through a sequential index buffer), POINTS pipelines (one pixel wide),
    // region clears, timestamp writes vanilla asked for (not recorded on WebGPU).
    fanDraws: 0,
    fanDrawsSkipped: 0,
    pointPipelines: 0,
    partialClears: 0,
    timestampWrites: 0,
    // Texture readbacks (screenshots, texture dumps): count, bytes, map round trips, failures.
    readbacks: 0,
    readbackBytes: 0,
    readbackMs: 0,
    readbackFailures: 0,
    // Device losses while the game rendered with WebGPU (each shows the reload prompt).
    deviceLost: 0,
    // Surface frames (vanilla's acquire; the frame id of the surface diagnostics) and the frames
    // whose window from that acquire to the submit that carries the blit spanned a suspension of
    // the game thread or an animation frame (where a canvas texture taken at the acquire expired).
    surfaceFrames: 0,
    surfaceSpanSuspend: 0,
    surfaceSpanRender: 0,
    surfaceSubmitPending: 0,
    surfaceSubmitMaxPending: 0,
    surfaceBackpressureWaits: 0
  };

  function note(message) {
    var text = String(message);
    if (G.messages.length < 128) {
      G.messages.push(text);
    }
    if (root.console && root.console.warn) {
      root.console.warn("[gaius-webgpu] " + text);
    }
  }

  G.note = note;

  G.state = function () {
    return G.status;
  };

  G.takeMessage = function () {
    return G.messages.length > 0 ? G.messages.shift() : null;
  };

  /** A numeric counter of G.stats (0 when unknown), read by the core's upload telemetry. */
  G.stat = function (key) {
    var value = G.stats[key];
    return typeof value === "number" ? value : 0;
  };

  /**
   * Adds delta to the counter G.stats[key] (created at 0): the core's telemetry of what the
   * downgrade table converted or dropped (fanDraws, fanDrawsSkipped, pointPipelines, ...).
   */
  G.count = function (key, delta) {
    G.stats[key] = (typeof G.stats[key] === "number" ? G.stats[key] : 0) + delta;
  };

  function updateSurfacePending() {
    var pending = Math.max(0, surfaceSubmitted - surfaceCompleted);
    G.stats.surfaceSubmitPending = pending;
    if (pending > G.stats.surfaceSubmitMaxPending) {
      G.stats.surfaceSubmitMaxPending = pending;
    }
  }

  function releaseSurfaceGate(completedThrough) {
    var gate = surfaceGate;
    surfaceCompleted = completedThrough === undefined
      ? surfaceSubmitted
      : Math.max(surfaceCompleted, completedThrough);
    surfaceGate = null;
    // A device loss or document teardown must wake the JSPI frame even though the old queue
    // promise may never settle. The next frame observes deviceUsable() and fails closed.
    if (gate !== null) {
      try { gate.resolve(); } catch (_) {}
    }
    updateSurfacePending();
  }

  function noteSurfaceSubmission() {
    ++surfaceSubmitted;
    updateSurfacePending();
  }

  /** Returns a promise only when the next uncapped world frame must wait for a surface submission. */
  G.waitForSurfaceBackpressure = function () {
    if (G.surfaceBackpressureEnabled === false || !deviceUsable()) {
      return null;
    }
    updateSurfacePending();
    if (G.stats.surfaceSubmitPending < surfaceBackpressureCap) {
      return null;
    }
    if (surfaceGate !== null) {
      return surfaceGate.promise;
    }
    var resolve;
    var promise = new Promise(function (accept) { resolve = accept; });
    surfaceGate = { promise: promise, resolve: resolve, submittedAt: surfaceSubmitted };
    G.stats.surfaceBackpressureWaits++;
    var queue = G.queue;
    var waitGeneration = generation;
    if (!queue || typeof queue.onSubmittedWorkDone !== "function") {
      releaseSurfaceGate();
      return promise;
    }
    var completion;
    try {
      completion = queue.onSubmittedWorkDone();
    } catch (_) {
      releaseSurfaceGate();
      return promise;
    }
    Promise.resolve(completion).then(function () {
      if (waitGeneration === generation && !terminated) {
        releaseSurfaceGate(surfaceGate ? surfaceGate.submittedAt : surfaceCompleted);
      }
    }, function () {
      if (waitGeneration === generation && !terminated) {
        releaseSurfaceGate(surfaceGate ? surfaceGate.submittedAt : surfaceCompleted);
      }
    });
    return promise;
  };

  // The production flag defaults to enabled. Harnesses that measure raw uncapped throughput can
  // set GaiusWebGpu.surfaceBackpressureEnabled = false without changing vanilla options.
  G.surfaceBackpressureEnabled = true;
  G.surfaceBackpressureCap = surfaceBackpressureCap;

  // ---- device -----------------------------------------------------------------------------

  var OPTIONAL_FEATURES = [
    "float32-blendable",
    "float32-filterable",
    "indirect-first-instance",
    "timestamp-query",
    "depth32float-stencil8",
    "rg11b10ufloat-renderable",
    "texture-compression-bc"
  ];

  // Limits raised to the adapter's value when it offers more than the default.
  var RAISED_LIMITS = [
    "maxBufferSize",
    "maxStorageBufferBindingSize",
    "maxUniformBufferBindingSize",
    "maxDynamicUniformBuffersPerPipelineLayout",
    "maxStorageBuffersInVertexStage",
    "maxColorAttachmentBytesPerSample",
    "maxTextureDimension2D",
    "maxSampledTexturesPerShaderStage",
    "maxSamplersPerShaderStage",
    "maxBindingsPerBindGroup"
  ];

  function fail(error) {
    G.status = -1;
    G.failure = String(error && error.message ? error.message : error);
    note("WebGPU unavailable: " + G.failure);
    return false;
  }

  // Canvas formats the surface blit can render to (getPreferredCanvasFormat returns one of them).
  var CANVAS_FORMATS = ["bgra8unorm", "rgba8unorm"];

  /**
   * Requests the adapter and device and checks the canvas format; the canvas's context is taken
   * later, by the first surface configure. Resolves to true when the device is ready, false
   * (never rejects) when WebGPU cannot be used. options: { powerPreference, allowFallback,
   * inject: "after-configure" (fallback test: configure the canvas, then fail) }.
   */
  G.init = function (canvas, options) {
    if (terminated) return Promise.resolve(false);
    var initGeneration = generation;
    if (G.initPromise) {
      return G.initPromise;
    }
    options = options || {};
    G.startup.initStart = now();
    G.canvas = canvas || null;
    G.inject = options.inject || "";
    if (options.pipelineMode === "eager" || options.pipelineMode === "lazy") {
      G.pipelineMode = options.pipelineMode;
    }
    var gpu = root.navigator && root.navigator.gpu;
    if (!gpu) {
      G.initPromise = Promise.resolve(fail("navigator.gpu is not available"));
      return G.initPromise;
    }
    G.status = 1;
    G.initPromise = gpu.requestAdapter({ powerPreference: options.powerPreference || "high-performance" })
      .then(function (adapter) {
        if (terminated || initGeneration !== generation) return false;
        G.startup.adapterAt = now();
        if (!adapter) {
          throw new Error("requestAdapter returned null");
        }
        var info = adapter.info || {};
        G.adapterInfo = {
          vendor: info.vendor || "",
          architecture: info.architecture || "",
          device: info.device || "",
          description: info.description || "",
          isFallbackAdapter: !!(info.isFallbackAdapter || adapter.isFallbackAdapter)
        };
        if (G.adapterInfo.isFallbackAdapter && !options.allowFallback) {
          throw new Error("only a fallback (software) adapter is available");
        }
        var features = [];
        for (var i = 0; i < OPTIONAL_FEATURES.length; i++) {
          if (adapter.features.has(OPTIONAL_FEATURES[i])) {
            features.push(OPTIONAL_FEATURES[i]);
          }
        }
        var limits = {};
        for (var j = 0; j < RAISED_LIMITS.length; j++) {
          var name = RAISED_LIMITS[j];
          if (adapter.limits[name] !== undefined) {
            limits[name] = adapter.limits[name];
          }
        }
        G.adapter = adapter;
        return adapter.requestDevice({ label: "gaius", requiredFeatures: features, requiredLimits: limits });
      })
      .then(function (device) {
        if (terminated || initGeneration !== generation) {
          try { if (device && device.destroy) device.destroy(); } catch (_) {}
          return false;
        }
        G.startup.deviceAt = now();
        if (G.status !== 1) {
          // abandon() ran first (the boot's timeout): this device is not going to be used.
          device.destroy();
          return false;
        }
        G.device = device;
        G.queue = device.queue;
        device.lost.then(function (lost) {
          if (terminated) return;
          releaseSurfaceGate();
          G.lostReason = (lost && lost.reason ? lost.reason : "unknown") + ": " + (lost && lost.message ? lost.message : "");
          // A lost GPUDevice cannot be used or recreated by the Java core. Mark the glue
          // unavailable as soon as loss is observed so CoreDevice.isAvailable() does not keep
          // handing out a permanently invalid device to re-entry callers.
          G.status = -1;
          G.failure = "WebGPU device lost: " + G.lostReason;
          // Do not leave a lost GPUDevice reachable through the public entry points.  Some
          // browsers accept configure/getCurrentTexture/submit on a lost device and only report
          // the loss asynchronously; clearing both references makes every lower-level entry
          // point fail closed as well as making deviceUsable() false.
          G.device = null;
          G.queue = null;
          note("device lost " + G.lostReason);
          if (deviceInUse) {
            // The game renders with this device: it cannot recover in this session (vanilla has
            // no device re-creation), so the page offers a reload.
            G.stats.deviceLost++;
            showLostPrompt(G.lostReason);
          }
        });
        device.addEventListener("uncapturederror", function (event) {
          G.stats.errors++;
          note(event.error && event.error.message ? event.error.message : event.error);
        });
        G.canvasFormat = gpu.getPreferredCanvasFormat();
        if (CANVAS_FORMATS.indexOf(G.canvasFormat) < 0) {
          throw new Error("unsupported canvas format " + G.canvasFormat);
        }
        // Rule 1: the canvas context is taken when the backend first configures the surface; a
        // canvas that has a "webgpu" context can no longer give the WebGL 2 fallback its context.
        if (options.inject === "after-configure") {
          G.configureSurface(Math.max(1, (gameCanvas() || {}).width | 0), Math.max(1, (gameCanvas() || {}).height | 0));
          throw new Error("injected failure after configure (gpuInject=after-configure)");
        }
        G.status = 2;
        G.startup.readyAt = now();
        return true;
      })
      .catch(fail);
    return G.initPromise;
  };

  /**
   * Gives up on WebGPU before the game uses it (the boot's timeout, a missing translator): a
   * device that is ready or may still arrive is destroyed and the seam reports WebGPU as
   * unavailable, so the game starts on WebGL 2. Does nothing once the core uses the device.
   */
  G.abandon = function (reason) {
    if (terminated || G.status === -1 || deviceInUse) {
      return;
    }
    if (G.device) {
      releaseSurfaceGate();
      G.device.destroy();
      G.device = null;
      G.queue = null;
    }
    fail(reason || "abandoned");
  };

  // Explicit document teardown. WebGPU objects are normally collected with the document, but
  // pending mapAsync/onSubmittedWorkDone/pipeline promises can retain the old device and Wasm
  // callbacks across a fast reload. Clear every JS-side owner before destroying the device.
  G.terminate = function (reason) {
    if (terminated) return false;
    terminated = true;
    releaseSurfaceGate();
    generation++;
    if (idleHandle !== null) {
      try {
        if (idleHandleIsRaf && typeof root.clearTimeout === "function") root.clearTimeout(idleHandle);
        else if (typeof root.cancelIdleCallback === "function") root.cancelIdleCallback(idleHandle);
      } catch (_) {}
      idleHandle = null;
    }
    idleHandleIsRaf = false;
    idleScheduled = false;
    pipelineQueue.length = 0;
    pipelinesPending = 0;
    pipelinePriorityCount = 0;
    try {
      if (translatorWorker && translatorWorker.terminate) translatorWorker.terminate();
      if (translatorWorker) {
        translatorWorker.onmessage = null;
        translatorWorker.onerror = null;
      }
    } catch (_) {}
    translatorWorker = null;
    prefetchPending.clear();
    if (translatorWorkerUrl) {
      try { root.URL.revokeObjectURL(translatorWorkerUrl); } catch (_) {}
      translatorWorkerUrl = null;
    }
    try { if (root.GaiusQuality && root.GaiusQuality.webgpuPost && root.GaiusQuality.webgpuPost.dispose) root.GaiusQuality.webgpuPost.dispose(); } catch (_) {}
    for (var i = 1; i < objects.length; i++) {
      var object = objects[i];
      if (!object) continue;
      try {
        if (object.destroy) object.destroy();
      } catch (_) {}
      objects[i] = null;
    }
    objects.length = 1;
    released = 0;
    try { if (pass) pass.end(); } catch (_) {}
    pass = null; foldedPass = null; encoder = null; uploadEncoder = null; lastPass = null;
    colorAttachments = []; depthAttachment = null; bindEntries = [];
    surfaceParts = []; surfaceAcquired = false; blit = null;
    if (surfaceRafPending && typeof root.cancelAnimationFrame === "function" && surfaceRafId !== null) {
      try { root.cancelAnimationFrame(surfaceRafId); } catch (_) {}
    }
    surfaceRafPending = false; surfaceRafId = null;
    if (G.context && typeof G.context.unconfigure === "function") {
      try { G.context.unconfigure(); } catch (_) {}
    }
    readbacksEncoded.forEach(function (readback) {
      try { if (readback && readback.staging) readback.staging.destroy(); } catch (_) {}
    });
    readbacksEncoded.length = 0;
    for (var readbackId in readbacksPending) {
      var readback = readbacksPending[readbackId];
      try { if (readback && readback.staging) { readback.staging.unmap(); readback.staging.destroy(); } } catch (_) {}
    }
    readbacksPending = {};
    readbacksDone.length = 0;
    fencesEncoded.length = 0;
    fences.forEach(function (fence) { fence.status = -1; });
    fences.clear();
    clearBuffers.forEach(function (buffer) { try { buffer.destroy(); } catch (_) {} });
    clearBuffers.length = 0;
    Object.keys(clearGroups).forEach(function (key) {
      clearGroups[key].entries.forEach(function (entry) { try { entry.buffer.destroy(); } catch (_) {} });
    });
    clearGroups = {};
    textureRowTemps.forEach(function (buffer) { try { buffer.destroy(); } catch (_) {} });
    textureRowTemps.length = 0;
    Object.keys(ring.free).forEach(function (size) {
      ring.free[size].forEach(function (block) { try { block.buffer.destroy(); } catch (_) {} });
    });
    ring.used.forEach(function (block) { try { block.buffer.unmap(); block.buffer.destroy(); } catch (_) {} });
    if (ring.current && ring.used.indexOf(ring.current) < 0) {
      try { ring.current.buffer.unmap(); ring.current.buffer.destroy(); } catch (_) {}
    }
    ring.free = {}; ring.used = []; ring.current = null; ring.pendingBytes = 0; ring.submitBytes = 0;
    layoutCache = {}; clearPipelines = {}; texturePackPipeline = null; endPending = false;
    textureFormats = new WeakMap(); textureExtents = new WeakMap();
    translations.clear(); translation = null; G.translator = null; G.translatorModule = null; G.translatorSource = null;
    if (translationCache.flushTimer !== null) {
      root.clearTimeout(translationCache.flushTimer);
      translationCache.flushTimer = null;
    }
    translationCache.pending = [];
    translationCache.enabled = false;
    if (translationCache.db && translationCache.db.close) translationCache.db.close();
    translationCache.db = null;
    translationCache.dbName = null;
    G.initPromise = null;
    var oldDevice = G.device;
    G.device = null; G.queue = null; G.adapter = null; G.canvas = null; G.context = null;
    deviceInUse = false;
    G.status = -1;
    G.lostReason = String(reason || "document unloaded");
    G.failure = G.lostReason;
    try { if (oldDevice && oldDevice.destroy) oldDevice.destroy(); } catch (_) {}
    if (root.__gaiusGpuBackend === "webgpu") root.__gaiusGpuBackend = "";
    return true;
  };

  /**
   * The reload prompt after a device loss while the game renders with WebGPU: a fixed overlay
   * with "Reload" and "Reload with WebGL 2" (the same page with ?gpu=webgl2). The game keeps
   * running behind it; WebGPU calls on a lost device do nothing.
   */
  function showLostPrompt(reason) {
    var doc = root.document;
    if (!doc || !doc.body || doc.getElementById("gaius-gpu-lost")) {
      return;
    }
    var box = doc.createElement("div");
    box.id = "gaius-gpu-lost";
    box.setAttribute("role", "alertdialog");
    // Styled like the game's own menus (dim backdrop, dark panel, square bevelled buttons) without
    // any game texture or font: the game cannot draw this itself once its device is gone.
    var font = "14px/1.45 ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace";
    box.style.cssText = "position:fixed;inset:0;z-index:2147483000;display:flex;align-items:center;"
      + "justify-content:center;background:rgba(16,16,16,0.75);color:#fff;font:" + font
      + ";text-align:center;text-shadow:2px 2px 0 #3f3f3f";
    var panel = doc.createElement("div");
    panel.style.cssText = "box-sizing:border-box;width:min(34em,calc(100vw - 32px));padding:16px 18px;"
      + "background:rgba(16,16,16,0.92);border:2px solid #000;"
      + "box-shadow:inset 2px 2px 0 rgba(255,255,255,0.2),inset -2px -2px 0 rgba(0,0,0,0.5)";
    var title = doc.createElement("div");
    title.style.cssText = "margin-bottom:0.8em;color:#fff";
    title.textContent = "The graphics device was lost";
    var text = doc.createElement("div");
    text.style.cssText = "margin-bottom:1.2em;color:#e0e0e0";
    text.textContent = "WebGPU stopped working (" + reason + "). Reload the page to continue. "
      + "If this keeps happening, reload with WebGL 2.";
    var buttons = doc.createElement("div");
    buttons.style.cssText = "display:flex;flex-wrap:wrap;gap:8px;justify-content:center";
    function button(label, action) {
      var element = doc.createElement("button");
      element.type = "button";
      element.textContent = label;
      element.style.cssText = "box-sizing:border-box;min-width:150px;height:30px;padding:0 10px;font:" + font
        + ";color:#fff;text-shadow:2px 2px 0 #3f3f3f;background:#6f6f6f;border:2px solid #000;border-radius:0;"
        + "box-shadow:inset 2px 2px 0 rgba(255,255,255,0.35),inset -2px -2px 0 rgba(0,0,0,0.4);cursor:pointer";
      function hover(on) {
        element.style.borderColor = on ? "#fff" : "#000";
      }
      element.addEventListener("mouseenter", function () { hover(true); });
      element.addEventListener("mouseleave", function () { hover(false); });
      element.addEventListener("focus", function () { hover(true); });
      element.addEventListener("blur", function () { hover(false); });
      element.addEventListener("click", action);
      buttons.appendChild(element);
      return element;
    }
    button("Reload with WebGL 2", function () {
      var url = new root.URL(root.location.href);
      url.searchParams.set("gpu", "webgl2");
      root.location.href = url.toString();
    });
    button("Reload", function () {
      root.location.reload();
    }).focus();
    panel.appendChild(title);
    panel.appendChild(text);
    panel.appendChild(buttons);
    box.appendChild(panel);
    doc.body.appendChild(box);
    if (root.document.exitPointerLock) {
      root.document.exitPointerLock();
    }
  }

  /** Test hook (?gpuInject=lose-device): loses the device the game renders with. */
  G.loseDevice = function () {
    if (deviceUsable()) {
      G.device.destroy();
    }
  };

  /** Fallback test of the GLFW profiles: the game canvas holds a "webgpu" context before GL starts. */
  G.taintCanvas = function () {
    var canvas = gameCanvas();
    var context = canvas && canvas.getContext ? canvas.getContext("webgpu") : null;
    if (context) {
      canvas.__gaiusContextKind = "webgpu";
    }
    return !!context;
  };

  // ---- which backend renders --------------------------------------------------------------

  var deviceInUse = false;

  /**
   * One gate for work that can reach the active GPU device.  A device can be pending during the
   * init fallback probe (status 1), but once it is lost every entry point must fail closed.  The
   * queue check matters because a few WebGPU implementations expose the device after replacing
   * its queue during teardown.
   */
  function deviceUsable() {
    return G.status !== -1 && !!G.device && !!G.queue && !G.lostReason;
  }

  G.deviceUsable = deviceUsable;

  Object.defineProperty(G, "active", {
    enumerable: true,
    get: function () {
      return deviceInUse && G.status === 2 && root.__gaiusGpuBackend === "webgpu";
    }
  });

  /** The core created its device (RpBackend.createDevice): WebGPU renders this session. */
  function markDeviceInUse() {
    if (!deviceInUse) {
      deviceInUse = true;
      G.startup.deviceInUseAt = now();
      root.__gaiusGpuBackend = "webgpu";
      schedulePipelineIdleWork();
    }
  }

  /** Whether the device can take work (the WebGPU half of the core's contextAvailable()). */
  G.contextAvailable = function () {
    return G.status === 2 && deviceUsable();
  };

  /** The device's capabilities in one object, for non-GL code that used to probe WebGL. */
  G.caps = function () {
    var features = [];
    if (G.device) {
      G.device.features.forEach(function (name) {
        features.push(name);
      });
    }
    var limits = {};
    for (var i = 0; G.device && i < RAISED_LIMITS.length; i++) {
      limits[RAISED_LIMITS[i]] = G.device.limits[RAISED_LIMITS[i]];
    }
    return {
      backend: "webgpu",
      available: G.contextAvailable(),
      adapter: G.adapterInfo || null,
      features: features,
      limits: limits,
      canvasFormat: G.canvasFormat || ""
    };
  };

  G.hasFeature = function (name) {
    return !!(G.device && G.device.features.has(name));
  };

  // DeviceFeatures is a contract between the vanilla render path and this glue.  An adapter
  // feature alone is not enough: WasmGC also needs every import used by the implementation.  Keep
  // this predicate here so the Java core and the JSBody path make the same decision.
  function gpuImportAvailable(name) {
    if (typeof G[name] !== "function") return false;
    if (typeof G.importTable !== "function") return true;
    var table = G.importTable();
    return !!table && table[name] !== missingGpuImport && typeof table[name] === "function";
  }

  function gpuImportsAvailable(names) {
    for (var i = 0; i < names.length; i++) {
      if (!gpuImportAvailable(names[i])) return false;
    }
    return true;
  }

  G.hasCapability = function (name) {
    if (!deviceUsable()) return false;
    switch (name) {
      case "wireframe-fill-mode":
      case "multi-draw-direct-interleaved":
      case "multi-draw-direct-separate":
      case "persistent-mapping":
        return false;
      case "shader-draw-parameters":
        return gpuImportsAvailable(["draw", "drawIndexed", "drawWithOffsets", "drawIndexedWithOffsets"]);
      case "draw-indirect":
        // Vanilla's DeviceFeatures.drawIndirect gates RenderPass.drawIndexedIndirect (and the
        // non-indexed drawIndirect, which neither 26.2 nor 26.3 issues).  Core WebGPU always
        // executes drawIndexedIndirect; only a non-zero firstInstance inside a record needs the
        // optional adapter feature, which multi-draw-indirect below requires.  The two glue
        // entries replay the records (one WebGPU draw call per record).
        return gpuImportsAvailable(["drawIndexedIndirect", "drawIndexedIndirectWithOffsets"]);
      case "multi-draw-indirect":
        // Vanilla rejects every indirect draw without drawIndirect and drawCount > 1 without
        // multiDrawIndirect, so this is never reported without draw-indirect.  The 26.3 terrain
        // records carry a non-zero firstInstance, so the adapter feature is required as well.
        return G.hasCapability("draw-indirect") && G.hasFeature("indirect-first-instance");
      case "non-zero-first-instance":
        return gpuImportsAvailable(["draw", "drawIndexed", "drawWithOffsets", "drawIndexedWithOffsets"]);
      case "gaius-uniform-rename":
        // The core's renamed uniform writes (CoreEncoder); ?gaiusUniformRename=0 keeps every
        // uniform write in command order.
        try {
          var rename = new URLSearchParams(root.location ? root.location.search : "").get("gaiusUniformRename");
          return rename !== "0" && rename !== "off";
        } catch (_) {
          return true;
        }
      default:
        return false;
    }
  };

  G.limit = function (name) {
    var value = G.device ? G.device.limits[name] : undefined;
    return typeof value === "number" ? value : -1;
  };

  G.adapterString = function (key) {
    return G.adapterInfo && G.adapterInfo[key] !== undefined ? String(G.adapterInfo[key]) : "";
  };

  // ---- buffers ----------------------------------------------------------------------------

  G.createBuffer = function (size, usage, label) {
    return put(G.device.createBuffer({ size: size, usage: usage, label: label || undefined }));
  };

  G.destroyBuffer = function (handle) {
    var buffer = get(handle);
    if (buffer) {
      buffer.destroy();
    }
    release(handle);
  };

  /** data: an Int8Array (a zero-copy view of a Java direct buffer); its byteLength is a multiple of 4. */
  function writeBufferImpl(handle, offset, data) {
    G.queue.writeBuffer(get(handle), offset, data.buffer, data.byteOffset, data.byteLength);
    G.stats.writeBufferCalls++;
    G.stats.writeBufferBytes += data.byteLength;
  }
  G.writeBuffer = function (handle, offset, data) {
    if (!deviceUsable()) return;
    writeBufferImpl(handle, offset, data);
  };
  var writeBufferRaw = writeBufferImpl;

  // WasmGC imports pass a linear-memory address and length instead of an externref view.
  // Refreshing the cached view is needed after memory.grow; the common path stays one branch
  // and one subarray allocation-free view.
  function linearBytes(address, length) {
    var linear = root.__gaiusLinear;
    if (linear && linear.i8) {
      linear.refresh();
      return linear.i8.subarray(address | 0, (address + length) | 0);
    }
    // JsArrays normally installs this cache during WasmGC startup. Keep the import ABI
    // functional during the short window before that helper runs, and after a loader variant
    // that does not include the classlib override.
    var memory = root.__gaiusWasmMemory;
    return memory && memory.buffer
      ? new Int8Array(memory.buffer).subarray(address | 0, (address + length) | 0)
      : null;
  }

  G.writeBufferLinear = function (handle, offset, address, size) {
    if (!deviceUsable()) return;
    var data = linearBytes(address, size);
    if (!data) return;
    writeBufferRaw(handle, offset, data);
  };

  // ---- staging ring -----------------------------------------------------------------------
  //
  // MAP_WRITE | COPY_SRC staging buffers that are mapped while the frame is recorded. A write
  // copies its bytes into the mapped range (one TypedArray copy, no IPC payload) and encodes a
  // copyBufferToBuffer from the staging buffer:
  //   - upload(): per-submit uploads of transient memory, on an upload command buffer that is
  //     submitted ahead of the frame's commands;
  //   - writeOrdered(): writes that must take effect between the commands around them
  //     (writeToBuffer, write mappings), on the frame's encoder in command order.
  // Small writes share 512 KiB blocks (vanilla's own transient BLOCK_SIZE); a write larger than a
  // block gets a dedicated power-of-two block of at most 4 MiB. Every used block is unmapped
  // before the submit and mapped again (mapAsync) right after it; a block whose mapping has not
  // come back is not reused. mapAsync costs grow with the mapped size, so there are no large
  // fixed blocks. The ring never holds more than 32 MiB: when it is full (or the write is larger
  // than 4 MiB) upload() falls back to queue.writeBuffer, which is ordered correctly for
  // per-submit uploads, and writeOrdered() refuses so the core stages the write in transient
  // memory instead. Nothing ever waits for a mapping (D11: no suspension in the backend).
  //
  // uploadMode "write" (A/B runs) bypasses the ring: upload() uses queue.writeBuffer and
  // writeOrdered() always refuses.

  // After a burst (world load, a resource reload) the ring shrinks again. Its demand is the staging
  // it has in flight after a submit (blocks whose mapping has not come back, this submit's
  // included), which grows with the frame rate and the mapAsync latency; the ring keeps twice the
  // largest demand of the last 120 submits (at least two blocks) and destroys pooled blocks above
  // that.

  G.uploadMode = "map";
  var uploadEncoder = null;
  var RECENT_SUBMITS = 120;
  var ring = {
    blockSize: 512 * 1024,
    dedicatedMax: 4 * 1024 * 1024,
    cap: 32 * 1024 * 1024,
    free: {},
    current: null,
    used: [],
    latencies: [],
    latencySorted: new Array(64),
    latencyCount: 0,
    latencyNext: 0,
    submitBytes: 0,
    pendingBytes: 0,
    recentDemand: [],
    recentNext: 0,
    budget: 0
  };

  /** Staging bytes the ring keeps: twice the recent peak demand, at least two blocks. */
  function updateBudget() {
    var peak = 0;
    for (var i = 0; i < ring.recentDemand.length; i++) {
      peak = Math.max(peak, ring.recentDemand[i]);
    }
    ring.budget = Math.max(2 * peak, 2 * ring.blockSize);
  }

  /**
   * The core passes its configuration (StagingRing) when it creates the device, which is also the
   * glue's signal that WebGPU renders this session.
   */
  G.ringConfigure = function (blockSize, dedicatedMax, cap) {
    ring.blockSize = blockSize;
    ring.dedicatedMax = dedicatedMax;
    ring.cap = cap;
    markDeviceInUse();
  };

  function ringBlock(size) {
    var pool = ring.free[size];
    if (pool && pool.length > 0) {
      return pool.pop();
    }
    if (G.stats.ringBytes + size > ring.cap) {
      return null;
    }
    var buffer = G.device.createBuffer({
      label: "gaius staging",
      size: size,
      usage: 6, // MAP_WRITE | COPY_SRC
      mappedAtCreation: true
    });
    G.stats.ringBlocks++;
    G.stats.ringBytes += size;
    return { buffer: buffer, mapped: new Uint8Array(buffer.getMappedRange()), size: size, offset: 0 };
  }

  /**
   * Copies data (an Int8Array) into a reserved range of size bytes (a multiple of 4, padding
   * zeroed). Returns { block, offset } or null when the ring cannot take the write.
   */
  function ringWrite(data, size) {
    if (G.uploadMode !== "map" || size > ring.dedicatedMax) {
      return null;
    }
    var block;
    var offset = 0;
    if (size > ring.blockSize) {
      var bytes = ring.blockSize * 2;
      while (bytes < size) {
        bytes *= 2;
      }
      block = ringBlock(bytes);
      if (block === null) {
        return null;
      }
      block.offset = block.size;
      ring.used.push(block);
    } else {
      block = ring.current;
      // getMappedRange offsets are multiples of 8; each write maps only its own range (S8).
      offset = block !== null ? (block.offset + 7) & ~7 : 0;
      if (block === null || offset + size > block.size) {
        block = ringBlock(ring.blockSize);
        if (block === null) {
          return null;
        }
        ring.current = block;
        ring.used.push(block);
        offset = 0;
      }
      block.offset = offset + size;
    }
    // A block remains mapped between submits. Reusing its full-range view avoids one typed-array
    // wrapper per transient upload while preserving the exact byte range and zero padding.
    var target = block.mapped;
    target.set(data, offset);
    if (data.byteLength < size) {
      target.fill(0, offset + data.byteLength, offset + size);
    }
    ring.submitBytes += size;
    return { block: block, offset: offset };
  }

  /** Uploads data (byteLength a multiple of 4) ahead of the commands of the next submit. */
  function uploadImpl(handle, offset, data) {
    var size = data.byteLength;
    var staged = ringWrite(data, size);
    if (staged === null) {
      if (G.uploadMode === "map") {
        if (size > ring.dedicatedMax) {
          G.stats.largeWrites++;
          G.stats.largeWriteBytes += size;
        } else {
          G.stats.ringFallbacks++;
          G.stats.ringFallbackBytes += size;
        }
      }
      writeBufferRaw(handle, offset, data);
      ring.submitBytes += size;
      return;
    }
    if (uploadEncoder === null) {
      uploadEncoder = G.device.createCommandEncoder({ label: "gaius uploads" });
    }
    uploadEncoder.copyBufferToBuffer(staged.block.buffer, staged.offset, get(handle), offset, size);
    G.stats.ringUploads++;
    G.stats.ringUploadBytes += size;
  }
  G.upload = function (handle, offset, data) {
    if (!deviceUsable()) return;
    uploadImpl(handle, offset, data);
  };
  var uploadRaw = uploadImpl;

  G.uploadLinear = function (handle, offset, address, size) {
    if (!deviceUsable()) return;
    var data = linearBytes(address, size);
    if (!data) return;
    uploadRaw(handle, offset, data);
  };

  /**
   * Writes data into [offset, offset + size) of a buffer in command order: through the ring and a
   * copyBufferToBuffer on the frame's encoder (no render pass may be open). size is data's length
   * rounded up to a multiple of 4. Returns 1, or 0 when the ring refuses (the core then stages
   * the bytes in transient memory and encodes the copy from there).
   */
  function writeOrderedImpl(handle, offset, data, size) {
    var staged = ringWrite(data, size);
    if (staged === null) {
      G.stats.ringOrderedRefused++;
      return 0;
    }
    currentEncoder().copyBufferToBuffer(staged.block.buffer, staged.offset, get(handle), offset, size);
    G.stats.ringOrdered++;
    G.stats.ringOrderedBytes += size;
    return 1;
  }
  G.writeOrdered = function (handle, offset, data, size) {
    if (!deviceUsable()) return 0;
    return writeOrderedImpl(handle, offset, data, size);
  };
  var writeOrderedRaw = writeOrderedImpl;

  G.writeOrderedLinear = function (handle, offset, address, dataLength, size) {
    if (!deviceUsable()) return 0;
    var data = linearBytes(address, dataLength);
    if (!data) return 0;
    return writeOrderedRaw(handle, offset, data, size);
  };

  function ringBeforeSubmit() {
    for (var i = 0; i < ring.used.length; i++) {
      ring.used[i].buffer.unmap();
    }
  }

  function recordMapLatency(ms) {
    ring.latencies[ring.latencyNext] = ms;
    ring.latencyCount = Math.min(64, ring.latencyCount + 1);
    ring.latencyNext = (ring.latencyNext + 1) % 64;
    if (ring.latencyNext % 16 === 0) {
      for (var i = 0; i < ring.latencyCount; i++) ring.latencySorted[i] = ring.latencies[i];
      ring.latencySorted.length = ring.latencyCount;
      ring.latencySorted.sort(function (a, b) { return a - b; });
      G.stats.ringMapP95 = ring.latencySorted[Math.min(ring.latencyCount - 1, Math.floor(ring.latencyCount * 0.95))];
      ring.latencySorted.length = 64;
    }
  }

  function ringAfterSubmit() {
    for (var i = 0; i < ring.used.length; i++) {
      recycle(ring.used[i]);
    }
    ring.recentDemand[ring.recentNext] = ring.pendingBytes;
    ring.recentNext = (ring.recentNext + 1) % RECENT_SUBMITS;
    ring.used.length = 0;
    updateBudget();
    trimFreeBlocks();
    ring.current = null;
    G.stats.lastSubmitUploadBytes = ring.submitBytes;
    ring.submitBytes = 0;
  }

  /** Destroys pooled blocks, the largest first, while the ring is above its budget. */
  function trimFreeBlocks() {
    var budget = ring.budget;
    if (G.stats.ringBytes <= budget) {
      return;
    }
    var sizes = Object.keys(ring.free).map(Number).sort(function (a, b) { return b - a; });
    for (var i = 0; i < sizes.length && G.stats.ringBytes > budget; i++) {
      var pool = ring.free[sizes[i]];
      while (pool.length > 0 && G.stats.ringBytes > budget) {
        var block = pool.pop();
        G.stats.ringBlocks--;
        G.stats.ringBytes -= block.size;
        block.buffer.destroy();
      }
    }
  }

  function recycle(block) {
    var blockGeneration = generation;
    var start = root.performance ? root.performance.now() : 0;
    G.stats.ringPending++;
    ring.pendingBytes += block.size;
    block.offset = 0;
    block.buffer.mapAsync(2).then(function () { // GPUMapMode.WRITE
      if (terminated || blockGeneration !== generation) {
        try { block.buffer.destroy(); } catch (_) {}
        return;
      }
      G.stats.ringPending--;
      ring.pendingBytes -= block.size;
      if (root.performance) {
        recordMapLatency(root.performance.now() - start);
      }
      if (G.stats.ringBytes > ring.budget) {
        G.stats.ringBlocks--;
        G.stats.ringBytes -= block.size;
        block.buffer.destroy();
        return;
      }
      block.mapped = new Uint8Array(block.buffer.getMappedRange());
      var pool = ring.free[block.size] || (ring.free[block.size] = []);
      pool.push(block);
    }, function () {
      if (terminated || blockGeneration !== generation) {
        try { block.buffer.destroy(); } catch (_) {}
        return;
      }
      G.stats.ringPending--;
      ring.pendingBytes -= block.size;
      G.stats.ringBlocks--;
      G.stats.ringBytes -= block.size;
      block.buffer.destroy();
    });
  }

  // ---- textures and samplers --------------------------------------------------------------

  // GPUTexture does not expose its creation descriptor. Keep the format beside the native
  // object so depth/stencil readbacks can select the required aspect explicitly.
  var textureFormats = new WeakMap();
  var textureExtents = new WeakMap();
  var viewExtents = new WeakMap();

  G.createTexture = function (width, height, layers, mips, format, usage, label) {
    var texture = G.device.createTexture({
      label: label || undefined,
      size: { width: width, height: height, depthOrArrayLayers: layers },
      mipLevelCount: mips,
      format: format,
      usage: usage,
      dimension: "2d"
    });
    textureFormats.set(texture, format);
    textureExtents.set(texture, { width: width, height: height });
    return put(texture);
  };

  G.destroyTexture = function (handle) {
    var texture = get(handle);
    if (texture) {
      texture.destroy();
    }
    release(handle);
  };

  G.createView = function (textureHandle, baseMip, mipCount, cube) {
    var texture = get(textureHandle);
    var view = texture.createView({
      baseMipLevel: baseMip,
      mipLevelCount: mipCount,
      dimension: cube ? "cube" : "2d",
      baseArrayLayer: 0,
      arrayLayerCount: cube ? 6 : 1
    });
    var extent = textureExtents.get(texture);
    if (extent) viewExtents.set(view, { width: Math.max(1, extent.width >> baseMip), height: Math.max(1, extent.height >> baseMip) });
    return put(view);
  };

  G.releaseHandle = release;

  G.writeTexture = function (textureHandle, mip, layer, x, y, width, height, bytesPerRow, data) {
    lastClearDescriptor = null;
    G.queue.writeTexture(
      { texture: get(textureHandle), mipLevel: mip, origin: { x: x, y: y, z: layer } },
      data,
      { offset: 0, bytesPerRow: bytesPerRow, rowsPerImage: height },
      { width: width, height: height, depthOrArrayLayers: 1 }
    );
    G.stats.writeTextureCalls++;
  };

  G.createSampler = function (addressU, addressV, minFilter, magFilter, mipmapFilter, anisotropy, maxLod) {
    var descriptor = {
      addressModeU: addressU,
      addressModeV: addressV,
      minFilter: minFilter,
      magFilter: magFilter,
      mipmapFilter: mipmapFilter
    };
    if (maxLod >= 0) {
      descriptor.lodMaxClamp = maxLod;
    }
    // WebGPU only allows anisotropy when every filter is linear.
    if (anisotropy > 1 && minFilter === "linear" && magFilter === "linear" && mipmapFilter === "linear") {
      descriptor.maxAnisotropy = anisotropy;
    }
    return put(G.device.createSampler(descriptor));
  };

  // ---- shaders and pipelines --------------------------------------------------------------
  //
  // Pipeline creation is the largest part of a cold start on WebGPU (5-6 s for 26.3's pipelines
  // on D3D12, M1 shader notes), and a pipeline created ahead of a submit delays that submit in the
  // GPU process. So createPipeline() only records the descriptor (its layouts are made at once:
  // bind groups need them) and the GPU pipeline is made when a pass first sets it, which creates
  // the title screen's pipelines first; once the first frame is presented, the rest are created
  // in idle time with createRenderPipelineAsync, at most PIPELINE_INFLIGHT at a time and within
  // the idle deadline (PIPELINE_SLICE_MS without requestIdleCallback). Shader modules are created
  // with the first pipeline that uses them. A pipeline the device rejects is marked invalid and
  // its draws are skipped (counted in stats.skippedDraws) instead of invalidating the frame.
  //
  // G.pipelineMode "eager" (?gpuPipelines=eager, A/B runs) creates every pipeline synchronously
  // in createPipeline(), as before.

  G.pipelineMode = "lazy";
  var PIPELINE_INFLIGHT = 4;
  var PIPELINE_SLICE_MS = 4;
  var pipelineQueue = [];
  // Idle creation order: what the first frames in a world draw (terrain, sky, clouds, entities,
  // particles, the world's post passes) before the rest (debug, rarely used pipelines), so entering
  // a world finds them created. A pipeline is still created on first use whatever its place.
  var PIPELINE_PRIORITY = /terrain|solid|cutout|translucent|tripwire|water|sky|sunrise|stars|celestial|cloud|entity|item|armor|particle|lightmap|weather|block|banner|text|outline|blit|lines|crumbling|leash|beacon|end_portal|oit_/;
  var pipelinePriorityCount = 0;
  var pipelinesPending = 0;
  var pipelinesInflight = 0;
  var idleScheduled = false;
  var idleHandle = null;
  var idleHandleIsRaf = false;
  var currentPipelineValid = true;

  G.createShaderModule = function (code, label) {
    return put({ shaderModule: true, code: code, label: label || "", module: null });
  };

  function shaderModule(record) {
    if (record.module === null) {
      var label = record.label;
      var module = G.device.createShaderModule({ code: record.code, label: label || undefined });
      G.stats.shaderModules++;
      if (module.getCompilationInfo) {
        module.getCompilationInfo().then(function (info) {
          for (var i = 0; i < info.messages.length; i++) {
            var message = info.messages[i];
            if (message.type === "error") {
              note("WGSL " + label + ":" + message.lineNum + ":" + message.linePos + " " + message.message);
            }
          }
        });
      }
      record.module = module;
    }
    return record.module;
  }

  var layoutCache = {};

  /**
   * The bind group layouts of groups 0 and 1 and their pipeline layout. layout is the list of
   * entries the core derived for the pipeline: [group, binding, "ubo"] (dynamic offset),
   * [group, binding, "storage"] (read-only storage), [group, binding, "texture", sampleType,
   * viewDimension, multisampled] and [group, binding, "sampler", type]. Equal lists share one
   * layout object, so the core may share bind groups between pipelines with equal layouts.
   */
  function bindGroupLayouts(layout) {
    var key = JSON.stringify(layout);
    var cached = layoutCache[key];
    if (cached) {
      return cached;
    }
    var stages = 3; // GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT
    var groups = [[], []];
    for (var i = 0; i < layout.length; i++) {
      var e = layout[i];
      var entry = { binding: e[1], visibility: stages };
      if (e[2] === "ubo") {
        entry.buffer = { type: "uniform", hasDynamicOffset: true };
      } else if (e[2] === "storage") {
        entry.buffer = { type: "read-only-storage" };
      } else if (e[2] === "texture") {
        entry.texture = { sampleType: e[3], viewDimension: e[4], multisampled: !!e[5] };
      } else {
        entry.sampler = { type: e[3] };
      }
      groups[e[0]].push(entry);
    }
    var layouts = [
      G.device.createBindGroupLayout({ entries: groups[0] }),
      G.device.createBindGroupLayout({ entries: groups[1] })
    ];
    cached = {
      groups: layouts,
      pipelineLayout: G.device.createPipelineLayout({ bindGroupLayouts: layouts })
    };
    layoutCache[key] = cached;
    return cached;
  }

  function blendComponent(component) {
    return { srcFactor: component[0], dstFactor: component[1], operation: component[2] };
  }

  /** The GPURenderPipelineDescriptor of a recorded pipeline (creates its shader modules). */
  function pipelineDescriptor(entry) {
    var d = entry.desc;
    var buffers = [];
    for (var i = 0; i < d.buffers.length; i++) {
      var b = d.buffers[i];
      if (!b) {
        buffers.push(null);
        continue;
      }
      var attributes = [];
      for (var j = 0; j < b.attributes.length; j++) {
        var a = b.attributes[j];
        attributes.push({ shaderLocation: a[0], offset: a[1], format: a[2] });
      }
      buffers.push({ arrayStride: b.stride, stepMode: b.instance ? "instance" : "vertex", attributes: attributes });
    }
    var targets = [];
    for (var k = 0; k < d.targets.length; k++) {
      var t = d.targets[k];
      if (!t) {
        targets.push(null);
        continue;
      }
      var target = { format: t.format, writeMask: t.mask };
      if (t.blend) {
        target.blend = { color: blendComponent(t.blend[0]), alpha: blendComponent(t.blend[1]) };
      }
      targets.push(target);
    }
    var descriptor = {
      label: d.label,
      layout: entry.layouts.pipelineLayout,
      vertex: { module: shaderModule(entry.vs), entryPoint: d.vsEntry, buffers: buffers },
      fragment: { module: shaderModule(entry.fs), entryPoint: d.fsEntry, targets: targets },
      primitive: { topology: d.topology, cullMode: d.cull ? "back" : "none", frontFace: "cw" }
    };
    if (entry.depthFormat) {
      var depth = d.depth;
      descriptor.depthStencil = {
        format: entry.depthFormat,
        depthWriteEnabled: depth ? depth.write : false,
        depthCompare: depth ? depth.compare : "always",
        depthBias: depth ? depth.bias : 0,
        depthBiasSlopeScale: depth ? depth.slope : 0
      };
    }
    return descriptor;
  }

  function pipelineSettled(entry, state) {
    entry.state = state;
    entry.desc = null;
    entry.vs = null;
    entry.fs = null;
    pipelinesPending--;
    if (pipelinesPending === 0 && G.startup.firstPresentAt > 0 && G.startup.allPipelinesCreatedAt === 0) {
      G.startup.allPipelinesCreatedAt = now();
    }
  }

  function pipelineRejected(entry, message) {
    entry.valid = false;
    G.stats.pipelineFailures++;
    G.stats.errors++;
    note("pipeline " + entry.label + " (" + (entry.depthFormat || "no depth") + "): " + message);
  }

  /** Creates a recorded pipeline now (its first use, or eager mode); null when it fails. */
  function createPipelineNow(entry) {
    var start = now();
    var callbackGeneration = generation;
    var pipeline = null;
    G.device.pushErrorScope("validation");
    try {
      pipeline = G.device.createRenderPipeline(pipelineDescriptor(entry));
    } catch (error) {
      pipelineRejected(entry, String(error && error.message ? error.message : error));
    }
    G.device.popErrorScope().then(function (error) {
      if (terminated || callbackGeneration !== generation) return;
      if (error && entry.valid) {
        pipelineRejected(entry, error.message);
      }
    });
    entry.pipeline = pipeline;
    G.stats.pipelinesOnDemand++;
    G.stats.pipelineOnDemandMs += now() - start;
    if (G.startup.firstPresentAt > 0) {
      G.stats.pipelinesOnDemandAfterFirstFrame++;
      G.stats.pipelineOnDemandAfterFirstFrameMs += now() - start;
    }
    pipelineSettled(entry, pipeline !== null ? "ready" : "failed");
    return pipeline;
  }

  function createPipelineIdle(entry) {
    var start = now();
    var callbackGeneration = generation;
    var descriptor;
    try {
      descriptor = pipelineDescriptor(entry);
    } catch (error) {
      pipelineRejected(entry, String(error && error.message ? error.message : error));
      pipelineSettled(entry, "failed");
      return;
    }
    entry.state = "creating";
    pipelinesInflight++;
    G.device.createRenderPipelineAsync(descriptor).then(function (pipeline) {
      if (terminated || callbackGeneration !== generation) return;
      pipelinesInflight--;
      if (entry.state === "creating") {
        entry.pipeline = pipeline;
        G.stats.pipelinesIdle++;
        G.stats.pipelineIdleMs += now() - start;
        pipelineSettled(entry, "ready");
      }
      schedulePipelineIdleWork();
    }, function (error) {
      if (terminated || callbackGeneration !== generation) return;
      pipelinesInflight--;
      if (entry.state === "creating") {
        pipelineRejected(entry, String(error && error.message ? error.message : error));
        pipelineSettled(entry, "failed");
      }
      schedulePipelineIdleWork();
    });
  }

  function runPipelineIdleWork(deadline) {
    idleHandle = null;
    idleHandleIsRaf = false;
    idleScheduled = false;
    if (terminated || !G.device || G.lostReason) {
      return;
    }
    var sliceEnd = now() + PIPELINE_SLICE_MS;
    while (pipelineQueue.length > 0 && pipelinesInflight < PIPELINE_INFLIGHT) {
      var left = deadline && typeof deadline.timeRemaining === "function"
        ? deadline.timeRemaining() : sliceEnd - now();
      if (left <= 1) {
        break;
      }
      var entry = pipelineQueue.shift();
      if (pipelinePriorityCount > 0) {
        pipelinePriorityCount--;
      }
      if (entry.state === "pending" && !entry.released) {
        createPipelineIdle(entry);
      }
    }
    schedulePipelineIdleWork();
  }

  /** Idle creation starts once WebGPU renders and the first frame is presented. */
  function schedulePipelineIdleWork() {
    if (idleScheduled || pipelineQueue.length === 0 || pipelinesInflight >= PIPELINE_INFLIGHT
        || !deviceInUse || G.startup.firstPresentAt === 0) {
      return;
    }
    idleScheduled = true;
    if (typeof root.requestIdleCallback === "function") {
      idleHandleIsRaf = false;
      idleHandle = root.requestIdleCallback(runPipelineIdleWork, { timeout: 500 });
    } else {
      idleHandleIsRaf = true;
      idleHandle = root.setTimeout(runPipelineIdleWork, 16);
    }
  }

  /**
   * Records one depth variant of a pipeline. json is the JSON description built by the core's
   * CorePipeline; depthFormat is the render pass's depth format or "" for none. Returns a handle
   * (in eager mode 0 when the descriptor is rejected synchronously).
   */
  G.createPipeline = function (json, depthFormat) {
    var d = JSON.parse(json);
    var entry = {
      pipeline: null,
      groups: null,
      layouts: null,
      label: d.label,
      valid: true,
      state: "pending",
      released: false,
      depthFormat: depthFormat || "",
      desc: d,
      vs: get(d.vs),
      fs: get(d.fs)
    };
    entry.layouts = bindGroupLayouts(d.layout);
    entry.groups = entry.layouts.groups;
    G.stats.pipelines++;
    G.stats.pipelinesRecorded++;
    pipelinesPending++;
    if (G.pipelineMode === "eager") {
      if (createPipelineNow(entry) === null) {
        return 0;
      }
      return put(entry);
    }
    if (PIPELINE_PRIORITY.test(String(entry.label)) && !/debug|wireframe/.test(String(entry.label))) {
      // Ahead of the queued pipelines without priority, behind the earlier priority ones.
      pipelineQueue.splice(pipelinePriorityCount, 0, entry);
      pipelinePriorityCount++;
      G.stats.pipelinesPriority++;
    } else {
      pipelineQueue.push(entry);
    }
    schedulePipelineIdleWork();
    return put(entry);
  };

  /** Pipeline creation so far (startup telemetry, G3). */
  G.pipelineTelemetry = function () {
    return {
      mode: G.pipelineMode,
      recorded: G.stats.pipelinesRecorded,
      pending: pipelinesPending,
      inflight: pipelinesInflight,
      onDemand: G.stats.pipelinesOnDemand,
      onDemandMs: Math.round(G.stats.pipelineOnDemandMs * 1000) / 1000,
      idle: G.stats.pipelinesIdle,
      idleMs: Math.round(G.stats.pipelineIdleMs * 1000) / 1000,
      priority: G.stats.pipelinesPriority,
      onDemandAfterFirstFrame: G.stats.pipelinesOnDemandAfterFirstFrame,
      onDemandAfterFirstFrameMs: Math.round(G.stats.pipelineOnDemandAfterFirstFrameMs * 1000) / 1000,
      queued: pipelineQueue.length,
      failures: G.stats.pipelineFailures,
      shaderModules: G.stats.shaderModules,
      translations: G.stats.translations,
      translateMs: Math.round(G.stats.translateMs * 1000) / 1000,
      translationCacheHits: G.stats.translationCacheHits,
      translationCache: {
        database: translationCache.dbName,
        version: translationCache.version,
        loaded: translationCache.loaded,
        hits: translationCache.hits,
        stored: translationCache.stored,
        pending: translationCache.pending.length,
        errors: translationCache.errors
      },
      startup: G.startup
    };
  };

  // ---- bind groups (built entry by entry, then created) -----------------------------------

  var bindEntries = [];

  G.bindBuffer = function (binding, bufferHandle, offset, size) {
    bindEntries.push({ binding: binding, resource: { buffer: get(bufferHandle), offset: offset, size: size } });
  };

  /** samplerBinding < 0: the layout has no sampler for this texture (the shaders never sample it). */
  G.bindTexture = function (binding, viewHandle, samplerBinding, samplerHandle) {
    bindEntries.push({ binding: binding, resource: get(viewHandle) });
    if (samplerBinding >= 0) {
      bindEntries.push({ binding: samplerBinding, resource: get(samplerHandle) });
    }
  };

  G.createBindGroup = function (pipelineHandle, group) {
    var entries = bindEntries;
    bindEntries = [];
    G.stats.bindGroups++;
    return put(G.device.createBindGroup({ layout: get(pipelineHandle).groups[group], entries: entries }));
  };

  // ---- command encoding -------------------------------------------------------------------

  var encoder = null;
  /**
   * Increments each time G.submit hands the frame's command buffers (the encoder returned by
   * externalEncoder and every surface part finished before it) to the queue. Page-side passes
   * use it to tell when command buffers they recorded into have been submitted.
   */
  G.submitCount = 0;
  // queue.submit consumes this list synchronously; keep the backing array across frames.
  var submitBuffers = [];
  var pass = null;
  var colorAttachments = [];
  var depthAttachment = null;
  var offsets = new Uint32Array(16);
  // Render-pass state cache. TeaVM calls these entry points for every vanilla draw; WebGPU
  // ignores redundant state, but crossing the Wasm/JS boundary does not. Keep the values in
  // the glue so unchanged state is rejected before reaching the WebGPU object.
  var boundPipelineHandle = 0;
  var boundVertexBuffers = [];
  var boundVertexOffsets = [];
  var boundVertexSizes = [];
  var boundIndexBufferHandle = 0;
  var boundIndexWide = false;
  var boundIndexOffset = 0;
  var boundGroups = [];
  var boundGroupCounts = [];
  var boundGroupOffsets = new Int32Array(32);
  var boundScissor = new Int32Array(4);
  var boundScissorValid = false;

  function resetPassState() {
    boundPipelineHandle = 0;
    boundVertexBuffers.length = 0;
    boundVertexOffsets.length = 0;
    boundVertexSizes.length = 0;
    boundIndexBufferHandle = 0;
    boundIndexWide = false;
    boundIndexOffset = 0;
    boundGroups.length = 0;
    boundGroupCounts.length = 0;
    boundGroupOffsets.fill(0);
    boundScissorValid = false;
  }

  // ---- merged passes ------------------------------------------------------------------------
  //
  // The game ends a pass and begins the next one on the same attachments several times a frame
  // (one pass per vanilla stage). passEnd holds a pass that drew open; when the next passBegin
  // names the same views with load operations only, the pass continues (the scissor goes back to
  // the whole attachment and every cached binding is applied again, as for a new pass), which
  // saves a render pass encoder and its descriptors per stage. Anything that needs the frame
  // encoder, the surface or the page's own passes ends the held pass first (endHeldPass).
  // ?gaiusPassMerge=0 turns it off.
  var endPending = false;
  var passMerge = (function () {
    try {
      var value = new URLSearchParams(root.location ? root.location.search : "").get("gaiusPassMerge");
      return value !== "0" && value !== "off";
    } catch (_) {
      return true;
    }
  })();

  function endHeldPass() {
    if (!endPending) return;
    endPending = false;
    if (pass !== null) {
      pass.end();
      pass = null;
      lastClearDescriptor = !passHadDraw && clearDescriptor(lastPass) ? lastPass : null;
    }
    lastPass = null;
  }

  function continuesPass(descriptor) {
    var previous = lastPass;
    if (!previous || pass === null) return false;
    var a = previous.colorAttachments, b = descriptor.colorAttachments;
    if (a.length !== b.length) return false;
    for (var i = 0; i < b.length; i++) {
      var x = a[i], y = b[i];
      if (!x || !y) {
        if (x || y) return false;
        continue;
      }
      if (x.view !== y.view || y.loadOp !== "load") return false;
    }
    var dx = previous.depthStencilAttachment, dy = descriptor.depthStencilAttachment;
    if (!dx || !dy) return !dx && !dy;
    if (dx.view !== dy.view || dy.depthLoadOp !== "load") return false;
    if (!!dx.stencilLoadOp !== !!dy.stencilLoadOp) return false;
    return !dy.stencilLoadOp || dy.stencilLoadOp === "load";
  }

  function attachmentExtent(descriptor) {
    var first = descriptor.colorAttachments.length > 0 ? descriptor.colorAttachments[0] : null;
    var view = first ? first.view : (descriptor.depthStencilAttachment ? descriptor.depthStencilAttachment.view : null);
    return view ? viewExtents.get(view) || null : null;
  }

  function currentEncoder() {
    endHeldPass();
    if (encoder === null) {
      encoder = G.device.createCommandEncoder();
    }
    return encoder;
  }

  /**
   * The frame's command encoder for page-side passes recorded in command order between the
   * game's own (the quality layer's WGSL post chain, runtime/webgpu/quality-post.js), or null
   * while a render pass of the game is open or no device renders.
   */
  G.externalEncoder = function () {
    endHeldPass();
    if (pass !== null || foldedPass !== null || !deviceUsable()) return null;
    lastClearDescriptor = null;
    return currentEncoder();
  };

  G.passColor = function (index, viewHandle, clear, r, g, b, a) {
    colorAttachments[index] = viewHandle === 0 ? null : {
      view: get(viewHandle),
      loadOp: clear ? "clear" : "load",
      storeOp: "store",
      clearValue: { r: r, g: g, b: b, a: a }
    };
  };

  G.passDepth = function (viewHandle, clear, depth, hasStencil) {
    depthAttachment = {
      view: get(viewHandle),
      depthLoadOp: clear ? "clear" : "load",
      depthStoreOp: "store",
      depthClearValue: depth
    };
    if (hasStencil) {
      depthAttachment.stencilLoadOp = "load";
      depthAttachment.stencilStoreOp = "store";
    }
  };

  var lastPass = null;
  var lastClearDescriptor = null;
  var passHadDraw = false;
  // A pass that repeats the previous clear-only pass is not begun. It stays logically open: the
  // first command that needs a GPURenderPassEncoder begins it after all (openFoldedPass), and
  // passEnd/passSuspend close it without touching the encoder.
  var foldedPass = null;

  function openFoldedPass() {
    var descriptor = foldedPass;
    foldedPass = null;
    pass = currentEncoder().beginRenderPass(descriptor);
    lastPass = descriptor;
    passHadDraw = false;
    currentPipelineValid = true;
    resetPassState();
    G.stats.clearFoldsOpened++;
    G.stats.passes++;
  }

  function clearDescriptor(descriptor) {
    for (var i = 0; i < descriptor.colorAttachments.length; i++) {
      var color = descriptor.colorAttachments[i];
      if (color && color.loadOp !== "clear") return false;
    }
    var depth = descriptor.depthStencilAttachment;
    return !depth || depth.depthLoadOp === "clear";
  }

  function sameClearDescriptor(a, b) {
    if (!a || !b || a.colorAttachments.length !== b.colorAttachments.length) return false;
    for (var i = 0; i < a.colorAttachments.length; i++) {
      var x = a.colorAttachments[i];
      var y = b.colorAttachments[i];
      if (!x || !y) {
        if (x || y) return false;
        continue;
      }
      var xc = x.clearValue;
      var yc = y.clearValue;
      // The earlier pass only left the cleared values behind if it stored (and resolved) them.
      if (x.view !== y.view || x.loadOp !== "clear" || y.loadOp !== "clear"
          || x.storeOp !== "store" || y.storeOp !== "store"
          || (x.resolveTarget || null) !== (y.resolveTarget || null)
          || xc.r !== yc.r || xc.g !== yc.g || xc.b !== yc.b || xc.a !== yc.a) return false;
    }
    var ad = a.depthStencilAttachment;
    var bd = b.depthStencilAttachment;
    if (!ad || !bd) return ad === bd;
    return ad.view === bd.view && ad.depthLoadOp === "clear" && bd.depthLoadOp === "clear"
      && ad.depthStoreOp === "store" && bd.depthStoreOp === "store"
      && ad.depthClearValue === bd.depthClearValue
      && ad.stencilLoadOp === bd.stencilLoadOp && ad.stencilStoreOp === bd.stencilStoreOp
      && (!ad.stencilLoadOp || ad.stencilLoadOp === "load" || ad.stencilClearValue === bd.stencilClearValue);
  }

  G.passBegin = function (colorCount) {
    colorAttachments.length = colorCount;
    var descriptor = { colorAttachments: colorAttachments };
    if (depthAttachment !== null) {
      descriptor.depthStencilAttachment = depthAttachment;
    }
    if (endPending) {
      var extent = continuesPass(descriptor) ? attachmentExtent(descriptor) : null;
      if (extent !== null) {
        endPending = false;
        colorAttachments = [];
        depthAttachment = null;
        if (boundScissorValid) pass.setScissorRect(0, 0, extent.width, extent.height);
        resetPassState();
        currentPipelineValid = true;
        G.stats.passesMerged++;
        return;
      }
      endHeldPass();
    }
    if (lastClearDescriptor !== null && clearDescriptor(descriptor)
        && sameClearDescriptor(lastClearDescriptor, descriptor)) {
      colorAttachments = [];
      depthAttachment = null;
      lastPass = null;
      foldedPass = descriptor;
      G.stats.clearFolds++;
      return;
    }
    pass = currentEncoder().beginRenderPass(descriptor);
    lastPass = descriptor;
    passHadDraw = false;
    colorAttachments = [];
    depthAttachment = null;
    currentPipelineValid = true;
    resetPassState();
    G.stats.passes++;
  };

  G.passEnd = function (now) {
    if (passMerge && pass !== null && now !== true && passHadDraw) {
      endPending = true;
      foldedPass = null;
      return;
    }
    endPending = false;
    if (pass !== null) {
      pass.end();
      pass = null;
      lastClearDescriptor = !passHadDraw && clearDescriptor(lastPass) ? lastPass : null;
    }
    // A folded pass that never opened leaves the attachments as the previous clear did, so
    // lastClearDescriptor still describes them.
    foldedPass = null;
    lastPass = null;
  };

  /**
   * Ends the open pass so that a copy can be encoded between its commands (a write mapping
   * committed while the pass is open); passResume() continues it in a new pass that loads the
   * attachments. The core re-applies the pass state afterwards.
   */
  G.passSuspend = function () {
    if (foldedPass !== null) {
      // Nothing was encoded: the attachments already hold the cleared values, so passResume can
      // continue with load operations.
      lastPass = foldedPass;
      foldedPass = null;
      lastClearDescriptor = null;
      G.stats.passSplits++;
      return;
    }
    if (pass !== null) {
      pass.end();
      pass = null;
      lastClearDescriptor = null;
      G.stats.passSplits++;
    }
  };

  G.passResume = function () {
    var d = lastPass;
    var colors = [];
    for (var i = 0; i < d.colorAttachments.length; i++) {
      var a = d.colorAttachments[i];
      colors.push(a ? { view: a.view, loadOp: "load", storeOp: "store" } : null);
    }
    var descriptor = { colorAttachments: colors };
    var z = d.depthStencilAttachment;
    if (z) {
      var depth = { view: z.view, depthLoadOp: "load", depthStoreOp: "store" };
      if (z.stencilLoadOp) {
        depth.stencilLoadOp = "load";
        depth.stencilStoreOp = "store";
      }
      descriptor.depthStencilAttachment = depth;
    }
    pass = currentEncoder().beginRenderPass(descriptor);
    lastPass = descriptor;
    lastClearDescriptor = null;
    passHadDraw = false;
    resetPassState();
    G.stats.passes++;
  };

  G.setPipeline = function (handle) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    var entry = get(handle);
    var pipeline = entry.pipeline;
    if (pipeline === null && entry.state !== "failed") {
      pipeline = createPipelineNow(entry);
    }
    currentPipelineValid = pipeline !== null && entry.valid;
    if (currentPipelineValid && boundPipelineHandle !== (handle | 0)) {
      pass.setPipeline(pipeline);
      boundPipelineHandle = handle | 0;
    }
  };

  G.setVertexBuffer = function (slot, bufferHandle, offset, size) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    var same = boundVertexBuffers[slot] === (bufferHandle | 0)
        && boundVertexOffsets[slot] === (offset | 0) && boundVertexSizes[slot] === (size | 0);
    if (!same) pass.setVertexBuffer(slot, get(bufferHandle), offset, size);
    boundVertexBuffers[slot] = bufferHandle | 0;
    boundVertexOffsets[slot] = offset | 0;
    boundVertexSizes[slot] = size | 0;
  };

  G.setIndexBuffer = function (bufferHandle, wide, offset) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    var same = boundIndexBufferHandle === (bufferHandle | 0)
        && boundIndexWide === !!wide && boundIndexOffset === (offset | 0);
    if (!same) pass.setIndexBuffer(get(bufferHandle), wide ? "uint32" : "uint16", offset);
    boundIndexBufferHandle = bufferHandle | 0;
    boundIndexWide = !!wide;
    boundIndexOffset = offset | 0;
  };

  /** Group 0 carries every uniform buffer with a dynamic offset; up to four offsets inline. */
  G.setBindGroup = function (group, bindGroupHandle, count, o0, o1, o2, o3) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    var base = group * 16;
    var same = boundGroups[group] === (bindGroupHandle | 0) && boundGroupCounts[group] === (count | 0);
    if (same) {
      var previous = boundGroupOffsets;
      same = count === 0 || (previous[base] === (o0 | 0) && previous[base + 1] === (o1 | 0)
          && previous[base + 2] === (o2 | 0) && previous[base + 3] === (o3 | 0));
    }
    if (!same) {
      if (count === 0) {
        pass.setBindGroup(group, get(bindGroupHandle));
      } else {
        offsets[0] = o0;
        offsets[1] = o1;
        offsets[2] = o2;
        offsets[3] = o3;
        pass.setBindGroup(group, get(bindGroupHandle), offsets, 0, count);
      }
    }
    boundGroups[group] = bindGroupHandle | 0;
    boundGroupCounts[group] = count | 0;
    boundGroupOffsets[base] = o0 | 0;
    boundGroupOffsets[base + 1] = o1 | 0;
    boundGroupOffsets[base + 2] = o2 | 0;
    boundGroupOffsets[base + 3] = o3 | 0;
  };

  /** For pipelines with more than four uniform buffers: stage offsets, then setBindGroupStaged. */
  G.stageOffset = function (index, value) {
    if (!deviceUsable()) return;
    offsets[index] = value;
  };

  G.setBindGroupStaged = function (group, bindGroupHandle, count) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    var base = group * 16;
    var same = boundGroups[group] === (bindGroupHandle | 0) && boundGroupCounts[group] === (count | 0);
    for (var i = 0; same && i < count; i++) same = boundGroupOffsets[base + i] === (offsets[i] | 0);
    if (!same) pass.setBindGroup(group, get(bindGroupHandle), offsets, 0, count);
    boundGroups[group] = bindGroupHandle | 0;
    boundGroupCounts[group] = count | 0;
    for (var j = 0; j < count; j++) boundGroupOffsets[base + j] = offsets[j] | 0;
  };

  G.setScissor = function (x, y, width, height) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    pass.setScissorRect(x, y, width, height);
    boundScissor[0] = x | 0;
    boundScissor[1] = y | 0;
    boundScissor[2] = width | 0;
    boundScissor[3] = height | 0;
    boundScissorValid = true;
  };

  G.draw = function (vertexCount, instanceCount, firstVertex, firstInstance) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    if (!currentPipelineValid) {
      G.stats.skippedDraws++;
      return;
    }
    pass.draw(vertexCount, instanceCount, firstVertex, firstInstance);
    passHadDraw = true;
    G.stats.draws++;
  };

  G.drawIndexed = function (indexCount, instanceCount, firstIndex, baseVertex, firstInstance) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    if (!currentPipelineValid) {
      G.stats.skippedDraws++;
      return;
    }
    pass.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
    passHadDraw = true;
    G.stats.draws++;
  };

  /** setBindGroup(0, ...) with new dynamic offsets, fused with the draw that needs them. */
  G.drawIndexedWithOffsets = function (bindGroupHandle, count, o0, o1, o2, o3, indexCount, instanceCount, firstIndex,
      baseVertex, firstInstance) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    if (!currentPipelineValid) {
      G.stats.skippedDraws++;
      return;
    }
    offsets[0] = o0;
    offsets[1] = o1;
    offsets[2] = o2;
    offsets[3] = o3;
    var same = boundGroups[0] === (bindGroupHandle | 0) && boundGroupCounts[0] === (count | 0);
    for (var i = 0; same && i < count; i++) same = boundGroupOffsets[i] === (offsets[i] | 0);
    if (!same) pass.setBindGroup(0, get(bindGroupHandle), offsets, 0, count);
    boundGroups[0] = bindGroupHandle | 0;
    boundGroupCounts[0] = count | 0;
    boundGroupOffsets[0] = o0 | 0;
    boundGroupOffsets[1] = o1 | 0;
    boundGroupOffsets[2] = o2 | 0;
    boundGroupOffsets[3] = o3 | 0;
    pass.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
    passHadDraw = true;
    G.stats.draws++;
  };

  // WebGPU exposes one indexed indirect record per draw. Replay the records in their original
  // order inside the glue so Java crosses the boundary once for a whole terrain run.
  G.drawIndexedIndirect = function (bufferHandle, offset, drawCount) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    if (!currentPipelineValid) {
      G.stats.skippedDraws += drawCount;
      return;
    }
    var buffer = get(bufferHandle);
    for (var i = 0; i < drawCount; i++) {
      pass.drawIndexedIndirect(buffer, offset + i * 20);
    }
    passHadDraw = true;
    G.stats.draws += drawCount;
    G.stats.indirectDraws += drawCount;
    G.stats.indirectBatches++;
  };

  // Fused terrain fast path: apply the pending dynamic offsets and replay one complete
  // indirect run without a second Java -> JS entry. The command records remain untouched.
  G.drawIndexedIndirectWithOffsets = function (bindGroupHandle, count, o0, o1, o2, o3,
      bufferHandle, offset, drawCount) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    if (!currentPipelineValid) {
      G.stats.skippedDraws += drawCount;
      return;
    }
    offsets[0] = o0;
    offsets[1] = o1;
    offsets[2] = o2;
    offsets[3] = o3;
    var same = boundGroups[0] === (bindGroupHandle | 0) && boundGroupCounts[0] === (count | 0);
    for (var i = 0; same && i < count; i++) same = boundGroupOffsets[i] === (offsets[i] | 0);
    if (!same) pass.setBindGroup(0, get(bindGroupHandle), offsets, 0, count);
    boundGroups[0] = bindGroupHandle | 0;
    boundGroupCounts[0] = count | 0;
    boundGroupOffsets[0] = o0 | 0;
    boundGroupOffsets[1] = o1 | 0;
    boundGroupOffsets[2] = o2 | 0;
    boundGroupOffsets[3] = o3 | 0;
    var buffer = get(bufferHandle);
    for (var j = 0; j < drawCount; j++) pass.drawIndexedIndirect(buffer, offset + j * 20);
    passHadDraw = true;
    G.stats.draws += drawCount;
    G.stats.indirectDraws += drawCount;
    G.stats.indirectBatches++;
  };

  G.drawWithOffsets = function (bindGroupHandle, count, o0, o1, o2, o3, vertexCount, instanceCount, firstVertex,
      firstInstance) {
    if (!deviceUsable()) return;
    if (pass === null && foldedPass !== null) openFoldedPass();
    if (!currentPipelineValid) {
      G.stats.skippedDraws++;
      return;
    }
    offsets[0] = o0;
    offsets[1] = o1;
    offsets[2] = o2;
    offsets[3] = o3;
    var same = boundGroups[0] === (bindGroupHandle | 0) && boundGroupCounts[0] === (count | 0);
    for (var i = 0; same && i < count; i++) same = boundGroupOffsets[i] === (offsets[i] | 0);
    if (!same) pass.setBindGroup(0, get(bindGroupHandle), offsets, 0, count);
    boundGroups[0] = bindGroupHandle | 0;
    boundGroupCounts[0] = count | 0;
    boundGroupOffsets[0] = o0 | 0;
    boundGroupOffsets[1] = o1 | 0;
    boundGroupOffsets[2] = o2 | 0;
    boundGroupOffsets[3] = o3 | 0;
    pass.draw(vertexCount, instanceCount, firstVertex, firstInstance);
    passHadDraw = true;
    G.stats.draws++;
  };

  G.copyBufferToBuffer = function (source, sourceOffset, target, targetOffset, size) {
    lastClearDescriptor = null;
    currentEncoder().copyBufferToBuffer(get(source), sourceOffset, get(target), targetOffset, size);
  };

  G.copyTextureToTexture = function (source, target, mip, destX, destY, sourceX, sourceY, width, height) {
    lastClearDescriptor = null;
    currentEncoder().copyTextureToTexture(
      { texture: get(source), mipLevel: mip, origin: { x: sourceX, y: sourceY } },
      { texture: get(target), mipLevel: mip, origin: { x: destX, y: destY } },
      { width: width, height: height, depthOrArrayLayers: 1 }
    );
  };

  /**
   * Copies width x height texels from a buffer into layer (array layer or cube face) of a texture
   * mip level at (x, y), in command order (no render pass may be open). The texels start at byte
   * offset and rows are bytesPerRow apart (rowsPerImage rows per image). WebGPU wants bytesPerRow
   * in multiples of 256 for a copy of more than one row, so a tighter layout (vanilla's glyphs and
   * animated sprites) is copied one row at a time; offset and bytesPerRow must be multiples of the
   * texel size. Vanilla's copyBufferToTexture(slice, srcX, srcY, rowLength, imageHeight, texture,
   * destX, destY, width, height, mip, layer) maps to offset = slice offset + (srcX + srcY *
   * rowLength) * texel size, bytesPerRow = rowLength * texel size, rowsPerImage = imageHeight.
   */
  G.copyBufferToTexture = function (bufferHandle, offset, bytesPerRow, rowsPerImage, textureHandle, mip, layer,
      x, y, width, height) {
    lastClearDescriptor = null;
    var buffer = get(bufferHandle);
    var destination = { texture: get(textureHandle), mipLevel: mip, origin: { x: x, y: y, z: layer } };
    var commands = currentEncoder();
    if (height <= 1 || (bytesPerRow & 255) === 0) {
      var layout = { buffer: buffer, offset: offset };
      if (height > 1) {
        layout.bytesPerRow = bytesPerRow;
        layout.rowsPerImage = Math.max(rowsPerImage, height);
      }
      commands.copyBufferToTexture(layout, destination, { width: width, height: Math.max(height, 1), depthOrArrayLayers: 1 });
      G.stats.textureCopies++;
      return;
    }
    for (var row = 0; row < height; row++) {
      commands.copyBufferToTexture(
        { buffer: buffer, offset: offset + row * bytesPerRow },
        { texture: destination.texture, mipLevel: mip, origin: { x: x, y: y + row, z: layer } },
        { width: width, height: 1, depthOrArrayLayers: 1 }
      );
    }
    G.stats.textureCopies++;
    G.stats.textureCopyRows += height;
  };

  G.submit = function () {
    if (!deviceUsable()) {
      return false;
    }
    var submitStart = now();
    endHeldPass();
    lastClearDescriptor = null;
    if (pass !== null || foldedPass !== null) {
      note("submit with an open render pass");
      G.passEnd(true);
    }
    // The encoder is retired below; a clear cannot be folded across submissions.
    lastClearDescriptor = null;
    var buffers = submitBuffers;
    buffers.length = 0;
    // Only a submission that carries a canvas surface blit participates in the
    // surface backpressure window. Upload-only and transient-uniform submits do not.
    var surfaceEncoded = false;
    if (uploadEncoder !== null) {
      buffers.push(uploadEncoder.finish());
      uploadEncoder = null;
    }
    if (surfaceParts.length > 0) {
      surfaceEncoded = appendSurfaceParts(buffers);
    }
    if (encoder !== null) {
      buffers.push(encoder.finish());
      encoder = null;
    }
    // Every command buffer recorded so far is in this submission (or dropped with it).
    G.submitCount++;
    ringBeforeSubmit();
    // A tightly packed texture readback uses one temporary GPU buffer per row.  Retire those
    // buffers even when WebGPU rejects the submission synchronously (for example after device
    // loss); otherwise every failed submit leaves the row temporaries reachable forever.
    try {
      if (buffers.length > 0) {
        G.queue.submit(buffers);
        if (surfaceEncoded) {
          noteSurfaceSubmission();
        }
      }
      ringAfterSubmit();
      readbacksAfterSubmit();
      fencesAfterSubmit();
      clearsAfterSubmit();
    } finally {
      textureRowTempsAfterSubmit();
      buffers.length = 0;
    }
    if (surfaceWindow.open && surfaceWindow.blitted) {
      closeSurfaceWindow("submit");
    }
    G.stats.submits++;
    var submitMs = now() - submitStart;
    G.stats.submitMsTotal += submitMs;
    if (submitMs > G.stats.submitMsMax) G.stats.submitMsMax = submitMs;
  };

  // ---- texture readback (copyTextureToBuffer + map read; screenshots, texture dumps) ----------
  //
  // readbackTexture() encodes a copy of a texture region into a MAP_READ staging buffer on the
  // frame's encoder (rows padded to the 256 bytes WebGPU wants); after the submit that carries it
  // the staging buffer is mapped, and once the mapping resolves the rows are packed tightly
  // (vanilla's layout: width * texel bytes per row) and the readback is complete. The core polls
  // completed readbacks (readbackPoll) and copies each into its destination
  // (readbackFetch): nothing ever waits for the GPU (D11).

  var readbacksEncoded = [];
  var readbacksPending = {};
  var readbacksDone = [];
  var nextReadback = 1;
  var fencesEncoded = [];
  var fences = new Map();
  var nextFence = 1;

  G.createFence = function () {
    var id = nextFence++;
    var fence = { status: 0 };
    fences.set(id, fence);
    fencesEncoded.push(fence);
    return id;
  };

  G.fenceStatus = function (id) {
    var fence = fences.get(id);
    return fence ? fence.status : -1;
  };

  G.closeFence = function (id) {
    fences.delete(id);
  };

  function fencesAfterSubmit() {
    if (fencesEncoded.length === 0) {
      return;
    }
    var submitted = fencesEncoded;
    fencesEncoded = [];
    var fenceGeneration = generation;
    G.queue.onSubmittedWorkDone().then(function () {
      if (terminated || fenceGeneration !== generation) return;
      submitted.forEach(function (fence) { fence.status = G.lostReason ? -1 : 1; });
    }, function (error) {
      if (terminated || fenceGeneration !== generation) return;
      submitted.forEach(function (fence) { fence.status = -1; });
      note("fence completion failed: " + (error && error.message ? error.message : error));
    });
  }

  /** The copy source of a texture: depth formats copy their depth aspect. */
  function copySource(texture, mip, x, y, z) {
    var source = { texture: texture, mipLevel: mip, origin: { x: x, y: y, z: z } };
    var format = textureFormats.get(texture) || texture.format || "";
    if (String(format).indexOf("depth") === 0) {
      source.aspect = "depth-only";
    } else if (format === "stencil8") {
      source.aspect = "stencil-only";
    }
    return source;
  }

  function validateReadbackAspect(texture, mip, x, y, width, height, texelBytes) {
    var format = textureFormats.get(texture) || texture.format || "";
    if (format === "depth24plus" || format === "depth24plus-stencil8") {
      throw new Error("WebGPU depth24plus depth readback is not supported");
    }
    var aspectBytes = format === "depth16unorm" ? 2
      : format === "depth32float" || format === "depth32float-stencil8" ? 4
      : format === "stencil8" ? 1 : texelBytes;
    if (texelBytes !== aspectBytes) {
      throw new Error("Texture readback aspect requires " + aspectBytes + " bytes per texel for " + format);
    }
    if (String(format).indexOf("depth") === 0 || format === "stencil8") {
      var extent = textureExtents.get(texture) || texture;
      var mipWidth = Math.max(1, Math.floor(extent.width / Math.pow(2, mip)));
      var mipHeight = Math.max(1, Math.floor(extent.height / Math.pow(2, mip)));
      if (x !== 0 || y !== 0 || width !== mipWidth || height !== mipHeight) {
        throw new Error("Depth/stencil readback requires the complete mip subresource");
      }
    }
  }

  /** Returns the readback's id; no render pass may be open. */
  G.readbackTexture = function (textureHandle, mip, layer, x, y, width, height, texelBytes) {
    validateReadbackAspect(get(textureHandle), mip, x, y, width, height, texelBytes);
    var rowBytes = width * texelBytes;
    var bytesPerRow = (rowBytes + 255) & ~255;
    var staging = G.device.createBuffer({ label: "gaius readback", size: Math.max(4, bytesPerRow * height), usage: 9 }); // MAP_READ | COPY_DST
    currentEncoder().copyTextureToBuffer(
      copySource(get(textureHandle), mip, x, y, layer),
      { buffer: staging, offset: 0, bytesPerRow: bytesPerRow, rowsPerImage: height },
      { width: width, height: height, depthOrArrayLayers: 1 }
    );
    var id = nextReadback++;
    readbacksEncoded.push({ id: id, staging: staging, rowBytes: rowBytes, bytesPerRow: bytesPerRow, height: height,
      data: null, failed: false });
    G.stats.readbacks++;
    G.stats.readbackBytes += rowBytes * height;
    return id;
  };

  function readbacksAfterSubmit() {
    if (readbacksEncoded.length === 0) {
      return;
    }
    var submitted = readbacksEncoded;
    readbacksEncoded = [];
    submitted.forEach(function (r) {
      var start = now();
      var readbackGeneration = generation;
      readbacksPending[r.id] = r;
      r.staging.mapAsync(1).then(function () { // GPUMapMode.READ
        if (terminated || readbackGeneration !== generation) {
          try { r.staging.unmap(); } catch (_) {}
          try { r.staging.destroy(); } catch (_) {}
          return;
        }
        var mapped = new Uint8Array(r.staging.getMappedRange());
        var tight = new Uint8Array(r.rowBytes * r.height);
        for (var row = 0; row < r.height; row++) {
          tight.set(mapped.subarray(row * r.bytesPerRow, row * r.bytesPerRow + r.rowBytes), row * r.rowBytes);
        }
        r.staging.unmap();
        r.staging.destroy();
        r.data = tight;
        G.stats.readbackMs += now() - start;
        readbacksDone.push(r.id);
      }, function (error) {
        if (terminated || readbackGeneration !== generation) {
          try { r.staging.destroy(); } catch (_) {}
          return;
        }
        r.staging.destroy();
        r.failed = true;
        G.stats.readbackFailures++;
        note("readback " + r.id + " failed: " + (error && error.message ? error.message : error));
        readbacksDone.push(r.id);
      });
    });
  }

  var texturePackPipeline = null;

  function compactTexturePipeline() {
    if (texturePackPipeline) return texturePackPipeline;
    var module = G.device.createShaderModule({ label: "gaius texture row pack", code: [
      "struct Words { data: array<u32> };",
      "struct Pack { rowBytes: u32, padded: u32, head: u32, length: u32, words: u32 };",
      "@group(0) @binding(0) var<storage, read> srcWords: Words;",
      "@group(0) @binding(1) var<storage, read_write> dstWords: Words;",
      "@group(0) @binding(2) var<uniform> pack: Pack;",
      "@compute @workgroup_size(64) fn main(@builtin(global_invocation_id) gid: vec3u,",
      "    @builtin(num_workgroups) groups: vec3u) {",
      "  let index = gid.x + gid.y * groups.x * 64u;",
      "  if (index >= pack.words) { return; }",
      "  var word = dstWords.data[index];",
      "  for (var b = 0u; b < 4u; b++) {",
      "    let position = index * 4u + b;",
      "    if (position >= pack.head && position - pack.head < pack.length) {",
      "      let relative = position - pack.head;",
      "      let byteOffset = (relative / pack.rowBytes) * pack.padded + relative % pack.rowBytes;",
      "      let byte = (srcWords.data[byteOffset / 4u] >> ((byteOffset % 4u) * 8u)) & 255u;",
      "      let shift = b * 8u;",
      "      word = (word & ~(255u << shift)) | (byte << shift);",
      "    }",
      "  }",
      "  dstWords.data[index] = word;",
      "}"
    ].join("\n") });
    texturePackPipeline = G.device.createComputePipeline({ label: "gaius texture row pack",
      layout: "auto", compute: { module: module, entryPoint: "main" } });
    return texturePackPipeline;
  }

  /** A texture region packed into a GPU buffer, preserving bytes outside its tight byte range. */
  G.copyTextureToBufferRows = function (textureHandle, mip, layer, x, y, width, height, texelBytes, bufferHandle, offset) {
    var texture = get(textureHandle);
    validateReadbackAspect(texture, mip, x, y, width, height, texelBytes);
    var buffer = get(bufferHandle);
    var rowBytes = width * texelBytes;
    var padded = Math.ceil(rowBytes / 256) * 256;
    var commands = currentEncoder();
    if ((offset % 256) === 0 && (height === 1 || rowBytes === padded)) {
      commands.copyTextureToBuffer(copySource(texture, mip, x, y, layer),
        { buffer: buffer, offset: offset, bytesPerRow: padded, rowsPerImage: height },
        { width: width, height: height, depthOrArrayLayers: 1 });
      return;
    }
    var alignedRows = (offset % 4) === 0 && (rowBytes % 4) === 0;
    var format = textureFormats.get(texture) || texture.format || "";
    var depthStencil = String(format).indexOf("depth") === 0 || format === "stencil8";
    var maxBufferSize = Number(G.device.limits.maxBufferSize);
    if (!Number.isFinite(maxBufferSize) || maxBufferSize <= 0) maxBufferSize = Infinity;
    var textureRows = Math.min(height, Math.floor(maxBufferSize / padded));
    if (depthStencil && textureRows < height) {
      throw new Error("Depth/stencil texture row readback exceeds device maxBufferSize");
    }
    var bandRows = depthStencil ? height : textureRows;
    if (!alignedRows) {
      var maxStorage = Number(G.device.limits.maxStorageBufferBindingSize);
      if (!Number.isFinite(maxStorage) || maxStorage <= 0) maxStorage = Infinity;
      bandRows = Math.min(bandRows, Math.floor((maxStorage - 4) / padded));
    }
    if (bandRows < 1) {
      throw new Error("Texture row readback row exceeds device maxBufferSize/maxStorageBufferBindingSize");
    }
    var wholeTextureTemporary = null;
    if (depthStencil) {
      wholeTextureTemporary = G.device.createBuffer({ label: "gaius padded texture rows", size: padded * height,
        usage: 0x0c }); // COPY_SRC|COPY_DST; depth/stencil texture copies stay whole.
      textureRowTemps.push(wholeTextureTemporary);
      commands.copyTextureToBuffer(copySource(texture, mip, x, y, layer),
        { buffer: wholeTextureTemporary, offset: 0, bytesPerRow: padded, rowsPerImage: height },
        { width: width, height: height, depthOrArrayLayers: 1 });
    }
    for (var firstRow = 0; firstRow < height; firstRow += bandRows) {
      var rows = Math.min(bandRows, height - firstRow);
      var temporary = wholeTextureTemporary;
      if (!temporary) {
        temporary = G.device.createBuffer({ label: "gaius padded texture rows", size: padded * rows,
          usage: alignedRows ? 0x0c : 0x8c }); // COPY_SRC|COPY_DST[, STORAGE]
        textureRowTemps.push(temporary);
        commands.copyTextureToBuffer(copySource(texture, mip, x, y + firstRow, layer),
          { buffer: temporary, offset: 0, bytesPerRow: padded, rowsPerImage: rows },
          { width: width, height: rows, depthOrArrayLayers: 1 });
      }
      if (alignedRows) {
        for (var row = 0; row < rows; row++) {
          commands.copyBufferToBuffer(temporary, row * padded, buffer,
            offset + (firstRow + row) * rowBytes, rowBytes);
        }
        continue;
      }
      var storage = temporary;
      if (depthStencil) {
        storage = G.device.createBuffer({ label: "gaius texture pack band", size: padded * rows, usage: 0x88 });
        textureRowTemps.push(storage);
        commands.copyBufferToBuffer(temporary, padded * firstRow, storage, 0, padded * rows);
      }
      var offsetInBand = offset + firstRow * rowBytes;
      var start = Math.floor(offsetInBand / 4) * 4;
      var head = offsetInBand - start;
      var length = rowBytes * rows;
      var span = Math.ceil((head + length) / 4) * 4;
      var packed = G.device.createBuffer({ label: "gaius compact texture rows", size: span, usage: 0x8c });
      var params = G.device.createBuffer({ label: "gaius texture pack parameters", size: 32,
        usage: 0x40, mappedAtCreation: true });
      new Uint32Array(params.getMappedRange()).set([rowBytes, padded, head, length, span / 4]);
      params.unmap();
      textureRowTemps.push(packed, params);
      // Preserve the neighboring bytes of the first and last words before the compute overwrites.
      commands.copyBufferToBuffer(buffer, start, packed, 0, span);
      var pipeline = compactTexturePipeline();
      var group = G.device.createBindGroup({ layout: pipeline.getBindGroupLayout(0), entries: [
        { binding: 0, resource: { buffer: storage } }, { binding: 1, resource: { buffer: packed } },
        { binding: 2, resource: { buffer: params } }
      ] });
      var groups = Math.ceil(span / 256);
      var maxGroups = Number(G.device.limits.maxComputeWorkgroupsPerDimension);
      if (!Number.isFinite(maxGroups) || maxGroups < 1) {
        throw new Error("Texture row pack requires maxComputeWorkgroupsPerDimension");
      }
      var groupsX = Math.min(groups, maxGroups);
      var groupsY = Math.ceil(groups / groupsX);
      if (groupsY > maxGroups) {
        throw new Error("Texture row pack exceeds maxComputeWorkgroupsPerDimension in 2D dispatch");
      }
      var compute = commands.beginComputePass({ label: "gaius texture row pack" });
      compute.setPipeline(pipeline);
      compute.setBindGroup(0, group);
      compute.dispatchWorkgroups(groupsX, groupsY);
      compute.end();
      commands.copyBufferToBuffer(packed, 0, buffer, start, span);
    }
  };

  /** The id of a completed readback (0 when none): fetch it with readbackFetch. */
  G.readbackPoll = function () {
    return readbacksDone.length > 0 ? readbacksDone.shift() : 0;
  };

  /**
   * Copies a completed readback's tightly packed bytes into target (an Int8Array, at most its
   * length) and forgets the readback. Returns the bytes copied, or -1 when the readback failed.
   */
  G.readbackFetch = function (id, target) {
    var r = readbacksPending[id];
    delete readbacksPending[id];
    if (!r || r.failed || r.data === null) {
      return -1;
    }
    var count = Math.min(target.byteLength, r.data.byteLength);
    new Uint8Array(target.buffer, target.byteOffset, count).set(r.data.subarray(0, count));
    return count;
  };

  // ---- partial clears ----------------------------------------------------------------------
  //
  // clearRegion() clears a rectangle of a colour attachment (and of a depth attachment) without
  // touching the texels around it: a pass that loads both attachments, the rectangle as scissor
  // and a full-screen triangle whose fragment writes the colour and frag_depth (compare "always").
  // The values travel in a small uniform buffer made per clear (clears of different values in
  // one submit must not share it), destroyed after the submit.

  var clearPipelines = {};
  var clearBuffers = [];
  // Padded rows, packed words and parameters live until the submit carrying their commands.
  var textureRowTemps = [];

  function clearPipeline(colorFormat, depthFormat) {
    var key = colorFormat + "|" + depthFormat;
    var cached = clearPipelines[key];
    if (cached) {
      return cached;
    }
    var module = G.device.createShaderModule({
      label: "gaius clear",
      code: [
        "struct Clear { color: vec4f, depth: vec4f };",
        "@group(0) @binding(0) var<uniform> clear: Clear;",
        "@vertex fn vs(@builtin(vertex_index) index: u32) -> @builtin(position) vec4f {",
        "  let p = vec2f(f32((index << 1u) & 2u), f32(index & 2u));",
        "  return vec4f(p * 4.0 - 1.0, 0.0, 1.0);",
        "}",
        "struct Both { @location(0) color: vec4f, @builtin(frag_depth) depth: f32 };",
        "@fragment fn fsBoth() -> Both {",
        "  var out: Both;",
        "  out.color = clear.color;",
        "  out.depth = clear.depth.x;",
        "  return out;",
        "}",
        "@fragment fn fsColor() -> @location(0) vec4f {",
        "  return clear.color;",
        "}"
      ].join("\n")
    });
    var descriptor = {
      label: "gaius clear " + key,
      layout: "auto",
      vertex: { module: module, entryPoint: "vs" },
      fragment: { module: module, entryPoint: depthFormat ? "fsBoth" : "fsColor", targets: [{ format: colorFormat }] },
      primitive: { topology: "triangle-list" }
    };
    if (depthFormat) {
      descriptor.depthStencil = { format: depthFormat, depthWriteEnabled: true, depthCompare: "always" };
    }
    cached = G.device.createRenderPipeline(descriptor);
    clearPipelines[key] = cached;
    return cached;
  }

  G.clearRegion = function (colorViewHandle, colorFormat, depthViewHandle, depthFormat, hasStencil, r, g, b, a, depth,
      x, y, width, height) {
    lastClearDescriptor = null;
    var pipeline = clearPipeline(colorFormat, depthViewHandle ? depthFormat : "");
    var group = clearGroup(colorFormat + "|" + (depthViewHandle ? depthFormat : ""), pipeline, r, g, b, a, depth);
    var descriptor = { colorAttachments: [{ view: get(colorViewHandle), loadOp: "load", storeOp: "store" }] };
    if (depthViewHandle) {
      descriptor.depthStencilAttachment = { view: get(depthViewHandle), depthLoadOp: "load", depthStoreOp: "store" };
      if (hasStencil) {
        descriptor.depthStencilAttachment.stencilLoadOp = "load";
        descriptor.depthStencilAttachment.stencilStoreOp = "store";
      }
    }
    var clearPass = currentEncoder().beginRenderPass(descriptor);
    clearPass.setPipeline(pipeline);
    clearPass.setBindGroup(0, group);
    clearPass.setScissorRect(x, y, width, height);
    clearPass.draw(3, 1, 0, 0);
    clearPass.end();
    G.stats.passes++;
  };

  // Partial clears repeat the same values frame after frame, so each value set keeps its uniform
  // buffer and bind group per clear pipeline. A new buffer, bind group and getBindGroupLayout()
  // wrapper per clear (1-2 a frame) stayed reachable until a full collection, and each kept its
  // GPU-process object. The least recently used set beyond CLEAR_GROUP_LIMIT retires after the
  // next submit.
  var clearGroups = {};
  var CLEAR_GROUP_LIMIT = 32;

  function clearGroup(pipelineKey, pipeline, r, g, b, a, depth) {
    var state = clearGroups[pipelineKey];
    if (!state) {
      state = clearGroups[pipelineKey] = { layout: pipeline.getBindGroupLayout(0), entries: new Map() };
    }
    var key = r + "," + g + "," + b + "," + a + "," + depth;
    var entry = state.entries.get(key);
    if (entry) {
      state.entries.delete(key);
      state.entries.set(key, entry);
      return entry.group;
    }
    if (state.entries.size >= CLEAR_GROUP_LIMIT) {
      var oldest = state.entries.keys().next().value;
      clearBuffers.push(state.entries.get(oldest).buffer);
      state.entries.delete(oldest);
    }
    var values = G.device.createBuffer({ label: "gaius clear values", size: 32, usage: 0x40, mappedAtCreation: true }); // UNIFORM
    new Float32Array(values.getMappedRange()).set([r, g, b, a, depth, 0, 0, 0]);
    values.unmap();
    entry = {
      buffer: values,
      group: G.device.createBindGroup({ layout: state.layout, entries: [{ binding: 0, resource: { buffer: values } }] })
    };
    state.entries.set(key, entry);
    return entry.group;
  }

  function clearsAfterSubmit() {
    for (var i = 0; i < clearBuffers.length; i++) {
      clearBuffers[i].destroy();
    }
    clearBuffers.length = 0;
  }

  function textureRowTempsAfterSubmit() {
    for (var i = 0; i < textureRowTemps.length; i++) {
      textureRowTemps[i].destroy();
    }
    textureRowTemps.length = 0;
  }

  // ---- shader translation -----------------------------------------------------------------
  //
  // finishCompile translates SPIR-V synchronously on a cache miss. The page installs the
  // translator before the game starts (installTranslator(module), gaius-boot.js): the
  // gaius-shaderxlat module (runtime/webgpu/gaius-shaderxlat.wasm) on a synchronous instance of
  // its job codec (runtime/webgpu/shaderxlat-job.js, GaiusShaderXlatJob.instantiateSync), an
  // object with translate(spirv, texelBuffers, options) returning { stage, bindings, wgsl }. The
  // result is kept here and read word by word by the core.
  //
  // Prefetch: prefetchSpirv() (the core's WgslSources.Prefetcher at compilePipeline) copies the
  // module and translates it on a translator Worker running the same job codec and module, off
  // the main thread; the result lands in the translation cache that translateSpirv() reads
  // first, keyed by the bytes and the options, so finishCompile only translates what the worker
  // has not finished yet.

  G.translator = null;
  G.translateError = "";
  var translation = null;
  var translations = new Map();
  var MAX_CACHED_TRANSLATIONS = 1024;
  var translationCache = {
    db: null, dbName: null, version: "dev", enabled: false, pending: [], flushTimer: null,
    loaded: 0, hits: 0, stored: 0, errors: 0
  };
  var TRANSLATION_CACHE_STORE = "wgsl";
  var MAX_PERSISTENT_TRANSLATIONS = 1024;
  var DIMS = { "1d": 0, "2d": 1, "2d-array": 2, "3d": 3, "cube": 4, "cube-array": 5 };
  var SAMPLE_KINDS = { "float": 0, "sint": 1, "uint": 2, "depth": 3 };
  var BINDING_KINDS = { "uniform": 0, "storage": 1, "texture": 2, "sampler": 3 };

  G.hasTranslator = function () {
    return !!(root.GaiusKernelMirror && root.GaiusKernelMirror.enabled("shaderxlat")
      && G.translator && typeof G.translator.translate === "function");
  };

  /**
   * Installs the translator from the compiled gaius-shaderxlat module (the job codec must be
   * loaded). source (optional): { jobUrl } or { jobSource } for the prefetch Worker. Returns
   * false when the module cannot be instantiated.
   */
  G.installTranslator = function (module, source) {
    if (terminated) return false;
    if (!root.GaiusKernelMirror || !root.GaiusKernelMirror.enabled("shaderxlat")) {
      G.translateError = "NEEDS_VANILLA: shader mirror is disabled";
      return false;
    }
    var job = root.GaiusShaderXlatJob;
    if (!module || !job || typeof job.instantiateSync !== "function") {
      G.translateError = !module ? "no gaius-shaderxlat module" : "the shaderxlat job codec is not loaded";
      return false;
    }
    try {
      G.translator = job.instantiateSync(module);
    } catch (error) {
      G.translateError = String(error && error.message ? error.message : error);
      return false;
    }
    // A page can reinstall the translator after a backend retry. Do not let a prior
    // translator's in-memory entries cross that boundary; IndexedDB records are checked
    // against the new version below.
    translations.clear();
    translation = null;
    translationCache.enabled = false;
    translationCache.pending = [];
    G.translatorModule = module;
    G.translatorSource = source || null;
    var profile = String(root.__gaiusProfileId || "");
    var cacheVersion = String(root.__gaiusGpuTranslationCacheVersion || "dev");
    if (/^[A-Za-z0-9._+-]+$/.test(profile)) {
      G.translationCacheReady = G.attachPersistentTranslationCache(
        "gaius-wgsl-cache-v1-" + profile, cacheVersion);
    } else {
      G.translationCacheReady = Promise.resolve(0);
    }
    G.startup.translatorAt = now();
    return true;
  };

  // Lower-case hex digits as ASCII codes, and a decoder that turns those codes into a string in
  // one call: every SPIR-V byte of every pipeline goes into its translation key, and building
  // that string a byte at a time cost over 100 ms of start-up.
  var HEX_CODES = [48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 97, 98, 99, 100, 101, 102];
  var hexDecoder = null;
  try {
    hexDecoder = typeof TextDecoder === "function" ? new TextDecoder("latin1") : null;
  } catch (_) {
    hexDecoder = null;
  }

  /** The bytes as two lower-case hex digits each, as byte.toString(16).padStart(2, "0") gives. */
  function hexContent(spirv) {
    var length = spirv.length;
    var codes = new Uint8Array(length * 2);
    for (var i = 0, j = 0; i < length; i++) {
      var byte = spirv[i] & 0xff;
      codes[j++] = HEX_CODES[byte >>> 4];
      codes[j++] = HEX_CODES[byte & 15];
    }
    if (hexDecoder) return hexDecoder.decode(codes);
    var parts = [];
    for (var start = 0; start < codes.length; start += 8192) {
      parts.push(String.fromCharCode.apply(null, codes.subarray(start, Math.min(codes.length, start + 8192))));
    }
    return parts.join("");
  }

  function translationKey(spirv, texelBuffers, flags) {
    var hash = 0x811c9dc5;
    var high = 0xcbf29ce4;
    for (var i = 0; i < spirv.length; i++) {
      var byte = spirv[i] & 0xff;
      hash = Math.imul(hash ^ byte, 0x01000193) >>> 0;
      high = Math.imul(high ^ hash, 0x01000193) >>> 0;
    }
    var content = hexContent(spirv);
    // Keep the compact FNV pair for diagnostics, but include every input byte so an IndexedDB
    // key cannot collide and return WGSL for a different module.
    return flags + "|" + texelBuffers + "|" + spirv.length + "|" + hash.toString(16) + high.toString(16)
      + "|" + content;
  }

  function texelPairs(texelBuffers) {
    var pairs = [];
    if (texelBuffers) {
      var parts = texelBuffers.split(",");
      for (var i = 0; i < parts.length; i++) {
        var pair = parts[i].split(":");
        pairs.push([Number(pair[0]), pair[1]]);
      }
    }
    return pairs;
  }

  function remember(key, result) {
    if (translations.size >= MAX_CACHED_TRANSLATIONS) {
      translations.clear();
    }
    translations.set(key, result);
    if (!translationCache.enabled) return;
    var words = new Int32Array(result.words.length);
    words.set(result.words);
    if (translationCache.pending.length >= MAX_PERSISTENT_TRANSLATIONS) {
      translationCache.pending.shift();
    }
    translationCache.pending.push({key: key, version: translationCache.version,
      stage: result.stage, count: result.count, words: words.buffer,
      wgsl: String(result.wgsl || ""), used: Date.now()});
    if (translationCache.flushTimer === null) {
      translationCache.flushTimer = root.setTimeout(flushTranslationCache, 250);
    }
  }

  function flushTranslationCache() {
    translationCache.flushTimer = null;
    if (!translationCache.db || translationCache.pending.length === 0) return Promise.resolve(0);
    var batch = translationCache.pending;
    translationCache.pending = [];
    return new Promise(function (resolve) {
      try {
        var transaction = translationCache.db.transaction(TRANSLATION_CACHE_STORE, "readwrite");
        var store = transaction.objectStore(TRANSLATION_CACHE_STORE);
        for (var i = 0; i < batch.length; i++) store.put(batch[i]);
        transaction.oncomplete = function () {
          translationCache.stored += batch.length;
          resolve(batch.length);
        };
        transaction.onerror = function () {
          translationCache.errors++;
          resolve(0);
        };
      } catch (_) {
        translationCache.errors++;
        resolve(0);
      }
    });
  }

  /**
   * Loads WGSL translations from an origin-scoped cache. The key includes the complete SPIR-V
   * input, texture-buffer declarations and translator flags; version identifies the translator
   * module. A hit returns the packed binding words and WGSL bytes from the previous computation.
   */
  G.attachPersistentTranslationCache = function (name, version) {
    translationCache.version = String(version || "dev");
    translationCache.enabled = typeof root.indexedDB !== "undefined" && !!name;
    if (!translationCache.enabled) return Promise.resolve(0);
    return new Promise(function (resolve) {
      var settled = false;
      var finish = function (count) {
        if (settled) return;
        settled = true;
        resolve(count);
      };
      var request;
      try {
        request = root.indexedDB.open(String(name), 1);
      } catch (_) {
        translationCache.errors++;
        finish(0);
        return;
      }
      request.onupgradeneeded = function () {
        var db = request.result;
        if (!db.objectStoreNames.contains(TRANSLATION_CACHE_STORE)) {
          db.createObjectStore(TRANSLATION_CACHE_STORE, {keyPath: "key"});
        }
      };
      request.onerror = function () {
        translationCache.errors++;
        finish(0);
      };
      request.onblocked = function () {
        translationCache.errors++;
        finish(0);
      };
      request.onsuccess = function () {
        var db = request.result;
        var transaction;
        try {
          transaction = db.transaction(TRANSLATION_CACHE_STORE, "readwrite");
        } catch (_) {
          translationCache.errors++;
          finish(0);
          return;
        }
        var store = transaction.objectStore(TRANSLATION_CACHE_STORE);
        var loaded = 0;
        var cursorRequest = store.openCursor();
        cursorRequest.onerror = function () {
          translationCache.errors++;
          finish(0);
        };
        cursorRequest.onsuccess = function () {
          var cursor = cursorRequest.result;
          if (!cursor) return;
          var value = cursor.value;
          var valid = value && value.version === translationCache.version
            && Number.isInteger(value.stage) && Number.isInteger(value.count)
            && value.count >= 0 && value.count <= 4096 && value.words
            && typeof value.wgsl === "string";
          if (valid && loaded < MAX_PERSISTENT_TRANSLATIONS) {
            try {
              var words = new Int32Array(value.words);
              if (words.length !== value.count * 8) valid = false;
              if (valid && !translations.has(value.key)) {
                translations.set(value.key, {stage: value.stage, count: value.count,
                  words: words, wgsl: value.wgsl, persistent: true});
                loaded++;
              }
            } catch (_) {
              valid = false;
            }
          }
          if (!valid || loaded >= MAX_PERSISTENT_TRANSLATIONS && !translations.has(value.key)) cursor.delete();
          cursor.continue();
        };
        transaction.oncomplete = function () {
          translationCache.db = db;
          translationCache.dbName = String(name);
          translationCache.loaded += loaded;
          finish(loaded);
          // Translations produced while IndexedDB was opening are still useful. Persist
          // them after the database handle becomes available instead of losing the first
          // boot's warm-up work.
          if (translationCache.pending.length) flushTranslationCache();
        };
        transaction.onerror = function () {
          translationCache.errors++;
          finish(0);
        };
      };
    });
  };

  G.flushTranslationCache = function () {
    if (translationCache.flushTimer !== null) {
      root.clearTimeout(translationCache.flushTimer);
      translationCache.flushTimer = null;
    }
    return flushTranslationCache();
  };

  G.clearTranslationCache = function () {
    translations.clear();
    translation = null;
  };

  function packTranslation(result) {
    var words = new Int32Array(result.bindings.length * 8);
    for (var b = 0; b < result.bindings.length; b++) {
      var r = result.bindings[b];
      var at = b * 8;
      var kind = BINDING_KINDS[r.type];
      words[at] = r.group;
      words[at + 1] = r.binding;
      words[at + 2] = r.uniform;
      words[at + 3] = kind;
      if (kind === 0) {
        words[at + 4] = r.size || 0;
      } else if (kind === 2) {
        words[at + 4] = DIMS[r.dim];
        words[at + 5] = SAMPLE_KINDS[r.sampleKind];
        words[at + 6] = (r.multisampled ? 1 : 0) | (r.sampled ? 2 : 0);
      } else if (kind === 3) {
        words[at + 4] = r.comparison ? 1 : 0;
      }
      words[at + 7] = r.used ? 1 : 0;
    }
    return { stage: result.stage === "fragment" ? 1 : 0, words: words, wgsl: result.wgsl, count: result.bindings.length };
  }

  /**
   * Translates one SPIR-V module (an Int8Array). texelBuffers: "index:FORMAT,..." (renderpearl
   * GpuFormat names); flags bit 0: flip the vertex y, bit 2: map GL's -1..1 clip depth to 0..1
   * (glDepthRemap, GLSL written for GL's depth range). Returns the number of binding records, or
   * -1 with G.translateError set.
   */
  G.translateSpirv = function (spirv, texelBuffers, flags) {
    translation = null;
    if (!root.GaiusKernelMirror || !root.GaiusKernelMirror.enabled("shaderxlat")) {
      G.translateError = "NEEDS_VANILLA: shader mirror is disabled";
      return -1;
    }
    var key = translationKey(spirv, texelBuffers || "", flags);
    var cached = translations.get(key);
    if (cached) {
      G.stats.translationCacheHits++;
      if (cached.persistent) translationCache.hits++;
      translation = cached;
      return cached.count;
    }
    var start = now();
    try {
      var result = G.translator.translate(spirv, texelPairs(texelBuffers),
        { flipVertexY: (flags & 1) !== 0, glDepthRemap: (flags & 4) !== 0 });
      translation = packTranslation(result);
      remember(key, translation);
      G.stats.translations++;
      return translation.count;
    } catch (error) {
      G.translateError = String(error && error.message ? error.message : error);
      G.stats.translateErrors++;
      return -1;
    } finally {
      G.stats.translateMs += now() - start;
    }
  };

  G.translatedWgsl = function () {
    return translation ? translation.wgsl : "";
  };

  G.translatedStage = function () {
    return translation ? translation.stage : -1;
  };

  G.translatedWord = function (index) {
    return translation ? translation.words[index] : 0;
  };

  // The prefetch Worker: the job codec and the module the page installed, one job at a time.
  var TRANSLATOR_WORKER = [
    "var xlat = null;",
    "self.onmessage = function (event) {",
    "  var m = event.data;",
    "  if (m.type === 'init') {",
    "    try {",
    "      if (m.jobUrl) { importScripts(m.jobUrl); } else { (new Function(m.jobSource))(); }",
    "      xlat = self.GaiusShaderXlatJob.instantiateSync(m.module);",
    "    } catch (error) { self.postMessage({ type: 'failed', message: String(error && error.message || error) }); }",
    "    return;",
    "  }",
    "  try {",
    "    var result = xlat.translate(m.spirv, m.pairs, { flipVertexY: m.flip });",
    "    self.postMessage({ type: 'done', key: m.key, result: result });",
    "  } catch (error) {",
    "    self.postMessage({ type: 'error', key: m.key, message: String(error && error.message || error) });",
    "  }",
    "};"
  ].join("\n");

  var translatorWorker = null;
  var translatorWorkerUrl = null;
  var prefetchPending = new Map();

  function prefetchWorker() {
    if (terminated) return null;
    if (translatorWorker !== null) {
      return translatorWorker || null;
    }
    translatorWorker = false;
    var source = G.translatorSource;
    if (!G.translatorModule || !source || (!source.jobUrl && !source.jobSource)
        || typeof root.Worker !== "function" || typeof root.Blob !== "function" || !root.URL) {
      return null;
    }
    try {
      var url = root.URL.createObjectURL(new root.Blob([TRANSLATOR_WORKER], { type: "text/javascript" }));
      translatorWorkerUrl = url;
      var worker = new root.Worker(url);
      var workerGeneration = generation;
      worker.onmessage = function (event) {
        if (terminated || workerGeneration !== generation) return;
        var m = event.data;
        if (m.type === "failed") {
          note("translator worker: " + m.message);
          worker.terminate();
          worker.onmessage = null;
          worker.onerror = null;
          translatorWorker = false;
          try { root.URL.revokeObjectURL(url); } catch (_) {}
          if (translatorWorkerUrl === url) translatorWorkerUrl = null;
          prefetchPending.clear();
          return;
        }
        prefetchPending.delete(m.key);
        if (m.type === "done") {
          remember(m.key, packTranslation(m.result));
        } else {
          G.stats.prefetchErrors++;
        }
      };
      worker.onerror = function () {
        if (terminated || workerGeneration !== generation) return;
        worker.onmessage = null;
        worker.onerror = null;
        translatorWorker = false;
        try { root.URL.revokeObjectURL(url); } catch (_) {}
        if (translatorWorkerUrl === url) translatorWorkerUrl = null;
        prefetchPending.clear();
      };
      worker.postMessage({ type: "init", module: G.translatorModule, jobUrl: source.jobUrl || null,
        jobSource: source.jobSource || null });
      translatorWorker = worker;
    } catch (error) {
      note("translator worker unavailable: " + (error && error.message ? error.message : error));
      translatorWorker = false;
      if (translatorWorkerUrl) {
        try { root.URL.revokeObjectURL(translatorWorkerUrl); } catch (_) {}
        translatorWorkerUrl = null;
      }
    }
    return translatorWorker || null;
  }

  /** Prefetches the Worker has not answered yet. */
  G.prefetchesPending = function () {
    return prefetchPending.size;
  };

  /**
   * Translates a module ahead of its finishCompile on the translator Worker (spirv: an Int8Array,
   * copied here). Returns false when there is no Worker (finishCompile then translates as usual).
   */
  G.prefetchSpirv = function (spirv, texelBuffers, flags) {
    if (terminated) return false;
    if (!root.GaiusKernelMirror || !root.GaiusKernelMirror.enabled("shaderxlat")) return false;
    var worker = prefetchWorker();
    if (worker === null) {
      return false;
    }
    var key = translationKey(spirv, texelBuffers || "", flags);
    if (translations.has(key) || prefetchPending.has(key)) {
      return true;
    }
    var copy = new Uint8Array(spirv.length);
    copy.set(new Uint8Array(spirv.buffer, spirv.byteOffset, spirv.length));
    prefetchPending.set(key, true);
    G.stats.prefetches++;
    worker.postMessage({ type: "translate", key: key, spirv: copy, pairs: texelPairs(texelBuffers),
      flip: (flags & 1) !== 0 }, [copy.buffer]);
    return true;
  };

  // ---- surface ----------------------------------------------------------------------------

  // The canvas texture lives only until the browser next updates the rendering; a frame of the
  // game can span several tasks (JSPI suspensions inside the frame let the event loop run), so the
  // texture is never held across a game call. Vanilla's acquire only marks the frame
  // (surfaceAcquired); its blit finishes the commands recorded so far and queues the blit
  // (surfaceParts, in command order); the submit takes the canvas texture, encodes the blit and
  // submits it in one synchronous call, where no task boundary can fall.
  var blit = null;
  var surfaceAcquired = false;
  var surfaceParts = [];

  // Surface window diagnostics: from vanilla's acquire to the submit that carries the blit (or
  // the present), whether the game thread suspended (a microtask ran) or an animation frame ran.
  // A JSPI yield inside the frame (BrowserWorldgenDeepCheckpoint.pulse in the client light
  // updates of ClientLevel.update) suspends it; ?gaiusDebug logs the frames that span an
  // animation frame.
  var surfaceWindow = { frame: 0, open: false, blitted: false, suspended: false, rafs: 0, acquiredAt: 0 };
  var surfaceRafs = 0;
  var surfaceRafPending = false;
  var surfaceRafId = null;

  function surfaceDebug() {
    if (root.__gaiusDebug === true) {
      return true;
    }
    try {
      return !!(root.location && root.location.search && /[?&](gaiusDebug|diag)(=|&|$)/.test(root.location.search));
    } catch (ignored) {
      return false;
    }
  }

  function openSurfaceWindow() {
    var frame = ++G.stats.surfaceFrames;
    var w = surfaceWindow;
    w.frame = frame;
    w.open = true;
    w.blitted = false;
    w.suspended = false;
    w.rafs = surfaceRafs;
    w.acquiredAt = now();
    if (typeof root.queueMicrotask === "function") {
      root.queueMicrotask(function () {
        if (w.open && w.frame === frame) w.suspended = true;
      });
    }
    if (!surfaceRafPending && typeof root.requestAnimationFrame === "function") {
      surfaceRafPending = true;
      surfaceRafId = root.requestAnimationFrame(function () {
        surfaceRafId = null;
        surfaceRafPending = false;
        if (terminated) return;
        surfaceRafs++;
      });
    }
  }

  function closeSurfaceWindow(where) {
    var w = surfaceWindow;
    if (!w.open) {
      return;
    }
    w.open = false;
    var rendered = surfaceRafs !== w.rafs;
    if (w.suspended) G.stats.surfaceSpanSuspend++;
    if (rendered) G.stats.surfaceSpanRender++;
    if (rendered && surfaceDebug() && root.console && root.console.info) {
      root.console.info("[gaius-webgpu-surface] " + JSON.stringify({ frame: w.frame, where: where,
        ms: now() - w.acquiredAt, suspended: w.suspended }));
    }
  }

  /** The game canvas: the shims' canvas host when there is one, else the canvas init() got (or #mc-canvas). */
  function gameCanvas() {
    var host = root.__gaiusCanvasHost;
    var current = host && typeof host.current === "function" ? host.current() : null;
    if (!current && !G.canvas && root.document && root.document.getElementById) {
      current = root.document.getElementById("mc-canvas");
    }
    return current || G.canvas || null;
  }

  /**
   * Takes the canvas's "webgpu" context (rule 1: only once the device is ready). A canvas that
   * already holds another context kind, which is not the game's WebGL 2 context, is replaced by
   * a fresh element first (rule 2).
   */
  function bindCanvas() {
    if (!deviceUsable()) {
      return false;
    }
    // The canvas host can replace the element when a game is re-entered.  Keep the
    // context only while it still belongs to the current canvas; otherwise bind the
    // replacement before configure/acquire so frames do not continue on a detached
    // canvas from the previous session.
    var currentCanvas = gameCanvas();
    if (G.context && G.canvas === currentCanvas) {
      return true;
    }
    var canvas = gameCanvas();
    var context = canvas && canvas.getContext ? canvas.getContext("webgpu") : null;
    var host = root.__gaiusCanvasHost;
    var gl = root.__gaiusWebGL;
    if (!context && canvas && host && typeof host.replace === "function" && !(gl && gl.canvas === canvas)) {
      canvas = host.replace("webgpu-context");
      context = canvas.getContext("webgpu");
    }
    if (!context) {
      note("the canvas gives no webgpu context");
      return false;
    }
    canvas.__gaiusContextKind = "webgpu";
    G.canvas = canvas;
    G.context = context;
    if (G.startup.surfaceAt === 0) {
      G.startup.surfaceAt = now();
    }
    return true;
  }

  G.configureSurface = function (width, height) {
    if (!deviceUsable() || !bindCanvas()) {
      return false;
    }
    if (G.canvas.width !== width) {
      G.canvas.width = width;
    }
    if (G.canvas.height !== height) {
      G.canvas.height = height;
    }
    G.context.configure({ device: G.device, format: G.canvasFormat, alphaMode: "opaque" });
    return true;
  };

  G.acquireSurface = function () {
    if (!deviceUsable() || !bindCanvas()) {
      return false;
    }
    surfaceAcquired = true;
    openSurfaceWindow();
    return true;
  };

  /** The canvas texture, taken at the submit that draws to it (acquireMs* time this call). */
  function takeSurfaceTexture() {
    var start = now();
    var texture = bindCanvas() ? G.context.getCurrentTexture() : null;
    var elapsed = now() - start;
    G.stats.acquireMsTotal += elapsed;
    if (elapsed > G.stats.acquireMsMax) G.stats.acquireMsMax = elapsed;
    return texture;
  }

  /** Adds the frame's command buffers up to each blit and the blits themselves, in order. */
  function appendSurfaceParts(buffers) {
    var texture = null;
    var encoded = false;
    for (var i = 0; i < surfaceParts.length; i++) {
      var part = surfaceParts[i];
      if (part.buffer) {
        buffers.push(part.buffer);
        continue;
      }
      if (texture === null) {
        texture = takeSurfaceTexture();
        if (texture === null) {
          continue;
        }
      }
      buffers.push(encodeSurfaceBlit(texture, part.view));
      encoded = true;
    }
    surfaceParts.length = 0;
    return encoded;
  }

  function blitPipeline() {
    if (blit !== null) {
      return blit;
    }
    var module = G.device.createShaderModule({
      label: "gaius surface blit",
      code: [
        "@group(0) @binding(0) var source: texture_2d<f32>;",
        "@group(0) @binding(1) var sourceSampler: sampler;",
        "struct Varyings { @builtin(position) position: vec4f, @location(0) uv: vec2f };",
        "@vertex fn vs(@builtin(vertex_index) index: u32) -> Varyings {",
        "  let p = vec2f(f32((index << 1u) & 2u), f32(index & 2u));",
        "  var out: Varyings;",
        "  out.position = vec4f(p * 2.0 - 1.0, 0.0, 1.0);",
        // Row 0 of the source is the bottom of the image (OpenGL/Vulkan order): uv.y = 0 at the
        // bottom edge of the canvas, which mirrors the flipped vkCmdBlitImage of vanilla Vulkan.
        "  out.uv = p;",
        "  return out;",
        "}",
        "@fragment fn fs(in: Varyings) -> @location(0) vec4f {",
        "  return vec4f(textureSample(source, sourceSampler, in.uv).rgb, 1.0);",
        "}"
      ].join("\n")
    });
    var pipeline = G.device.createRenderPipeline({
      label: "gaius surface blit",
      layout: "auto",
      vertex: { module: module, entryPoint: "vs" },
      fragment: { module: module, entryPoint: "fs", targets: [{ format: G.canvasFormat }] },
      primitive: { topology: "triangle-list" }
    });
    blit = {
      pipeline: pipeline,
      sampler: G.device.createSampler({ minFilter: "linear", magFilter: "linear" }),
      groups: new WeakMap()
    };
    return blit;
  }

  G.blitToSurface = function (viewHandle) {
    if (!deviceUsable() || !surfaceAcquired) {
      return;
    }
    endHeldPass();
    if (pass !== null || foldedPass !== null) {
      note("surface blit with an open render pass");
      G.passEnd(true);
    }
    // Finishing the game's encoder separates this command stream from the next pass.
    lastClearDescriptor = null;
    if (encoder !== null) {
      surfaceParts.push({ buffer: encoder.finish(), view: null });
      encoder = null;
    }
    surfaceParts.push({ buffer: null, view: get(viewHandle) });
    surfaceWindow.blitted = true;
  };

  function encodeSurfaceBlit(texture, view) {
    var state = blitPipeline();
    var group = state.groups.get(view);
    if (!group) {
      group = G.device.createBindGroup({
        layout: state.pipeline.getBindGroupLayout(0),
        entries: [{ binding: 0, resource: view }, { binding: 1, resource: state.sampler }]
      });
      state.groups.set(view, group);
    }
    var commands = G.device.createCommandEncoder({ label: "gaius surface blit" });
    var blitPass = commands.beginRenderPass({
      colorAttachments: [{ view: texture.createView(), loadOp: "clear", storeOp: "store", clearValue: { r: 0, g: 0, b: 0, a: 1 } }]
    });
    blitPass.setPipeline(state.pipeline);
    blitPass.setBindGroup(0, group);
    blitPass.draw(3, 1, 0, 0);
    blitPass.end();
    return commands.finish();
  }

  G.present = function () {
    if (!deviceUsable()) {
      return false;
    }
    var presentStart = now();
    // The canvas presents when the current task ends; nothing to do here.
    if (surfaceParts.length > 0) {
      // A blit vanilla did not submit: submit it here, still in this frame's task.
      G.submit();
    }
    if (surfaceAcquired) {
      G.stats.presents++;
    }
    surfaceAcquired = false;
    closeSurfaceWindow("present");
    if (G.inject === "lose-device" && G.startup.firstPresentAt > 0 && now() - G.startup.firstPresentAt > 3000) {
      G.inject = "";
      note("injected device loss (gpuInject=lose-device)");
      G.loseDevice();
    }
    if (G.startup.firstPresentAt === 0) {
      G.startup.firstPresentAt = now();
      G.startup.pipelinesAtFirstPresent = G.stats.pipelinesRecorded;
      G.startup.pipelinesCreatedAtFirstPresent = G.stats.pipelinesOnDemand + G.stats.pipelinesIdle;
      if (pipelinesPending === 0) {
        G.startup.allPipelinesCreatedAt = G.startup.firstPresentAt;
      }
      schedulePipelineIdleWork();
    }
    var presentMs = now() - presentStart;
    G.stats.presentMsTotal += presentMs;
    if (presentMs > G.stats.presentMsMax) G.stats.presentMsMax = presentMs;
  };

  // ---- diagnostics: texel readback (asynchronous, for harnesses and golden checks) --------

  G.readTexels = function (textureHandle, x, y, width, height) {
    var texture = get(textureHandle);
    var bytesPerRow = Math.ceil(width * 4 / 256) * 256;
    var buffer = G.device.createBuffer({ size: bytesPerRow * height, usage: 9 }); // MAP_READ | COPY_DST
    var commands = G.device.createCommandEncoder();
    commands.copyTextureToBuffer(
      { texture: texture, origin: { x: x, y: y } },
      { buffer: buffer, bytesPerRow: bytesPerRow, rowsPerImage: height },
      { width: width, height: height, depthOrArrayLayers: 1 }
    );
    G.queue.submit([commands.finish()]);
    var readGeneration = generation;
    return buffer.mapAsync(1).then(function () {
      if (terminated || readGeneration !== generation) {
        buffer.destroy();
        throw new Error("WebGPU device unavailable");
      }
      var mapped = new Uint8Array(buffer.getMappedRange());
      var out = new Uint8Array(width * height * 4);
      for (var row = 0; row < height; row++) {
        out.set(mapped.subarray(row * bytesPerRow, row * bytesPerRow + width * 4), row * width * 4);
      }
      buffer.unmap();
      buffer.destroy();
      return out;
    }, function (error) {
      // mapAsync rejects on a lost device or an invalidated submission. Always release the
      // staging allocation; diagnostics are allowed to reject, but must not leak one buffer per
      // failed screenshot while the caller is deciding whether to reload.
      buffer.destroy();
      throw error;
    });
  };

  /** Diagnostics: the bytes [offset, offset + size) of a buffer (asynchronous, size % 4 == 0). */
  G.readBuffer = function (bufferHandle, offset, size) {
    var buffer = G.device.createBuffer({ size: size, usage: 9 }); // MAP_READ | COPY_DST
    var commands = G.device.createCommandEncoder();
    commands.copyBufferToBuffer(get(bufferHandle), offset, buffer, 0, size);
    G.queue.submit([commands.finish()]);
    var readGeneration = generation;
    return buffer.mapAsync(1).then(function () {
      if (terminated || readGeneration !== generation) {
        buffer.destroy();
        throw new Error("WebGPU device unavailable");
      }
      var out = new Uint8Array(buffer.getMappedRange()).slice();
      buffer.unmap();
      buffer.destroy();
      return out;
    }, function (error) {
      buffer.destroy();
      throw error;
    });
  };

  // The WasmGC render path imports these functions directly. The table is built after the loss
  // guards below are installed, so each import reaches the same entry a JSBody call would: the
  // guarded wrapper, or for the hot calls (which are not wrapped) the function's own one-branch
  // device check. A lost device then unwinds through the reload prompt instead of throwing.
  var GPU_IMPORTS = [
    "copyBufferToBuffer", "bindBuffer", "bindTexture", "createBindGroup",
    "passColor", "passDepth", "passBegin", "passEnd", "passSuspend", "passResume",
    "setPipeline", "setVertexBuffer", "setIndexBuffer", "setBindGroup", "stageOffset", "setBindGroupStaged",
    "setScissor", "draw", "drawIndexed", "drawIndexedWithOffsets", "drawIndexedIndirect",
    "drawIndexedIndirectWithOffsets", "drawWithOffsets",
    "submit", "createFence", "fenceStatus", "closeFence", "blitToSurface", "present"
  ];
  // Keep WasmGC instantiation and the WebGL2 fallback linkable when an older glue is loaded.  The
  // capability predicate above still reports false, so no Java path can mistake this for support.
  function missingGpuImport() {}
  var gpuImportTable = null;

  function buildImportTable() {
    var table = {
      writeBuffer: G.writeBufferLinear,
      upload: G.uploadLinear,
      writeOrdered: G.writeOrderedLinear,
      configureSurface: function (width, height) { return G.configureSurface(width, height) ? 1 : 0; },
      acquireSurface: function () { return G.acquireSurface() ? 1 : 0; }
    };
    for (var i = 0; i < GPU_IMPORTS.length; i++) {
      var name = GPU_IMPORTS[i];
      table[name] = typeof G[name] === "function" ? G[name] : missingGpuImport;
    }
    return table;
  }

  G.importTable = function () {
    if (gpuImportTable === null) gpuImportTable = buildImportTable();
    return gpuImportTable;
  };

  G.installImports = function (imports) {
    if (imports) imports.gaiusGpu = G.importTable();
  };

  // Keep the loss policy for the JSBody calls and imports in one place. The hot draw and state
  // entries are omitted because their unwrapped functions already perform the same cheap device
  // check.
  // The fallback values match each import's normal return kind, so a caller can unwind without a
  // second WebGPU call while the reload prompt remains visible.
  function guardGpuEntry(name, fallback) {
    var entry = G[name];
    G[name] = function () {
      if (!deviceUsable()) {
        return typeof fallback === "function" ? fallback() : fallback;
      }
      return entry.apply(G, arguments);
    };
  }

  [
    ["createBuffer", 0], ["destroyBuffer", undefined],
    ["createTexture", 0], ["destroyTexture", undefined],
    ["createView", 0], ["writeTexture", undefined], ["createSampler", 0], ["createShaderModule", 0],
    ["createPipeline", 0], ["bindBuffer", undefined], ["bindTexture", undefined], ["createBindGroup", 0],
    ["passColor", undefined], ["passDepth", undefined], ["passBegin", undefined], ["passEnd", undefined],
    ["passSuspend", undefined], ["passResume", undefined],
    ["copyBufferToBuffer", undefined],
    ["copyTextureToTexture", undefined], ["copyBufferToTexture", undefined], ["submit", false],
    ["createFence", 0], ["readbackTexture", 0], ["copyTextureToBufferRows", undefined],
    ["clearRegion", undefined], ["configureSurface", false], ["acquireSurface", false],
    ["blitToSurface", undefined], ["present", false], ["readTexels", function () { return Promise.reject(new Error("WebGPU device unavailable")); }],
    ["readBuffer", function () { return Promise.reject(new Error("WebGPU device unavailable")); }]
  ].forEach(function (entry) { guardGpuEntry(entry[0], entry[1]); });

  root.GaiusWebGpu = G;
  if (root.__gaiusGraphicsTerminated) G.terminate("pagehide");
})(typeof globalThis !== "undefined" ? globalThis : this);
