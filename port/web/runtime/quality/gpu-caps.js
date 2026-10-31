// Gaius GPU capability layer: probes the WebGL2 features the graphics quality tiers depend on,
// runs a short fill-rate microbenchmark and classifies the device as low, mid, high or ultra.
//
// Load order (plain scripts, no module loader): gpu-caps.js, quality-profile.js, gl-pass.js,
// upscaler.js, post-chain.js, quality-runtime.js. Every file registers itself on
// window.GaiusQuality; this one adds GaiusQuality.caps and publishes window.__gaiusGpuCaps.
//
// Two entry points:
//   GaiusQuality.caps.ensureTier()  synchronous; returns the classification. The first call on a
//       device creates a throw-away WebGL2 canvas, probes it, benchmarks it (about 150 ms at
//       most) and releases it again. The result is remembered in localStorage, so later boots
//       only read it back. The launcher calls this after storage is ready and before the client
//       starts, because BrowserFilePersistence picks the first-launch option defaults from it.
//   GaiusQuality.caps.attach(gl)    called with the game's own context once it exists. Enables
//       the float colour-buffer, float-blend, indexed draw-buffer, float-linear, anisotropic,
//       parallel-compile, multi-draw and timer-query extensions on that context (WebGL
//       extensions are per context and must be re-enabled after a context loss), re-checks float
//       framebuffer completeness and republishes the capabilities.
//
// Overrides: ?gaiusTier=low|mid|high|ultra (also stored as localStorage gaius.quality.tierOverride
// by GaiusQuality.profile.setTier), ?gaiusTier=auto clears the stored override, and
// ?gaiusGpuRebench=1 discards the remembered benchmark.
//
// Automatic tier (decide(), also GaiusQuality.caps.decide for the decision-table smoke): the
// benchmark tier (or the renderer's guess) clamped by the renderer class, then capped by the
// weakest signal of the machine:
//   software renderer or WebGPU fallback adapter      low
//   ChromeOS (Chromebook)                             low (mid with 8+ threads, 8+ GiB and a
//                                                     benchmark of the high tier)
//   navigator.deviceMemory <= 2 / <= 4 GiB            low / mid
//   navigator.hardwareConcurrency <= 2 / <= 4         low / mid
//   MAX_TEXTURE_SIZE < 4096, no WebGL 2 context       low
//   no RGBA16F colour target                          mid
//   WebGPU API without an adapter (GPU blocklisted)   mid
//   WebGPU adapter limits below the desktop baseline  mid
//   no WebGPU API at all (WebGL 2 only browser)       high
// The WebGPU adapter is requested at load time (not on the boot's critical path; beforeMain
// waits at most a few hundred milliseconds for it) and only on boots that classify, i.e. when
// no remembered classification exists. Every decision is printed once on the console with the
// prefix "Gaius quality:" and kept in __gaiusGpuCaps.reasons.
(function installGaiusGpuCaps(root) {
  "use strict";
  if (!root) return;
  const Q = root.GaiusQuality || (root.GaiusQuality = {});
  if (Q.caps) return;

  const STORAGE_KEY = "gaius.quality.gpuTier.v1";
  const OVERRIDE_KEY = "gaius.quality.tierOverride";
  // 2: the ChromeOS, WebGPU adapter and fallback-context signals joined the decision; earlier
  // records are classified again once.
  const SCHEMA = 2;
  const TIERS = Object.freeze(["low", "mid", "high", "ultra"]);
  const BENCH_SIZE = 512;
  const BENCH_BUDGET_MS = 150;
  const BENCH_MIN_SAMPLE_MS = 6;
  // Provisional fill-rate thresholds in megapixels per second for the benchmark shader below
  // (about 24 dependent sin/cos/fract iterations per pixel). They are deliberately low: the
  // renderer string decides the ceiling, the benchmark mostly catches weak or throttled GPUs.
  // Recalibrate from the telemetry field __gaiusGpuCaps.score once real devices report it.
  const SCORE_MID = 350;
  const SCORE_HIGH = 2200;
  const SCORE_ULTRA = 7000;

  const EXTENSIONS = Object.freeze({
    colorBufferFloat: "EXT_color_buffer_float",
    colorBufferHalfFloat: "EXT_color_buffer_half_float",
    floatBlend: "EXT_float_blend",
    drawBuffersIndexed: "OES_draw_buffers_indexed",
    textureFloatLinear: "OES_texture_float_linear",
    anisotropic: "EXT_texture_filter_anisotropic",
    parallelShaderCompile: "KHR_parallel_shader_compile",
    multiDraw: "WEBGL_multi_draw",
    timerQuery: "EXT_disjoint_timer_query_webgl2",
    debugRendererInfo: "WEBGL_debug_renderer_info"
  });

  const state = {
    caps: null,
    attachedContext: null,
    attachedListeners: false,
    // Fields of window.__gaiusGpuCaps this layer wrote (see publish).
    ownedKeys: null,
    // WebGPU adapter signal: {status: "pending"|"ok"|"none"|"unsupported"|"error", ...}.
    webgpu: null,
    webgpuPromise: null,
    // Last "Gaius quality:" console line, so republishing the same decision stays quiet.
    logged: ""
  };

  function tierIndex(name) {
    const index = TIERS.indexOf(String(name || "").toLowerCase());
    return index < 0 ? -1 : index;
  }

  function clampTier(index, floor, cap) {
    let value = index;
    if (floor >= 0 && value < floor) value = floor;
    if (cap >= 0 && value > cap) value = cap;
    return Math.max(0, Math.min(TIERS.length - 1, value));
  }

  function urlParam(name) {
    try {
      return new URLSearchParams(root.location ? root.location.search : "").get(name);
    } catch (error) {
      return null;
    }
  }

  function storageGet(key) {
    try {
      return root.localStorage ? root.localStorage.getItem(key) : null;
    } catch (error) {
      return null;
    }
  }

  function storageSet(key, value) {
    try {
      if (!root.localStorage) return false;
      if (value === null || value === undefined) root.localStorage.removeItem(key);
      else root.localStorage.setItem(key, value);
      return true;
    } catch (error) {
      return false;
    }
  }

  function readStoredRecord() {
    const raw = storageGet(STORAGE_KEY);
    if (!raw) return null;
    try {
      const record = JSON.parse(raw);
      if (!record || record.schema !== SCHEMA || tierIndex(record.tier) < 0) return null;
      return record;
    } catch (error) {
      return null;
    }
  }

  function writeStoredRecord(caps) {
    storageSet(STORAGE_KEY, JSON.stringify({
      schema: SCHEMA,
      tier: caps.tier,
      kind: caps.kind,
      score: caps.score,
      renderer: caps.renderer,
      vendor: caps.vendor,
      rebench: !!caps.rebench,
      lowBench: !!caps.lowBench,
      extensions: caps.extensions || null,
      floatTargets: caps.floatTargets || null,
      maxTextureSize: caps.maxTextureSize || 0,
      cros: !!caps.cros,
      webgpu: caps.webgpu || null,
      reasons: Array.isArray(caps.reasons) ? caps.reasons.slice(0, 16) : [],
      at: Date.now()
    }));
  }

  function overrideTier() {
    const param = urlParam("gaiusTier");
    if (param !== null) {
      const value = String(param).toLowerCase();
      if (value === "auto" || value === "") {
        storageSet(OVERRIDE_KEY, null);
        return null;
      }
      if (tierIndex(value) >= 0) return value;
    }
    const stored = storageGet(OVERRIDE_KEY);
    return tierIndex(stored) >= 0 ? String(stored).toLowerCase() : null;
  }

  function navigatorInfo() {
    const nav = root.navigator || {};
    const ua = String(nav.userAgent || "");
    let mobile = false;
    try {
      mobile = !!(nav.userAgentData && nav.userAgentData.mobile);
    } catch (error) {
      mobile = false;
    }
    if (!mobile) mobile = /Android|iPhone|iPod|Mobile|Silk|Kindle/i.test(ua);
    // iPadOS reports a desktop Safari user agent; a Mac with touch points is an iPad.
    const ipad = /iPad/.test(ua) || (/Macintosh/.test(ua) && Number(nav.maxTouchPoints || 0) > 1);
    // Chromebooks: "CrOS" in the user agent, or the Chrome OS platform of client hints.
    let platform = "";
    try {
      platform = String((nav.userAgentData && nav.userAgentData.platform) || nav.platform || "");
    } catch (error) {
      platform = "";
    }
    const cros = /\bCrOS\b/.test(ua) || /^chrome ?os$/i.test(platform);
    return {
      mobile: mobile || ipad,
      tablet: ipad || (/Android/i.test(ua) && !/Mobile/i.test(ua)),
      cros: cros,
      platform: platform,
      deviceMemory: Number(nav.deviceMemory || 0),
      hardwareConcurrency: Number(nav.hardwareConcurrency || 0),
      userAgent: ua
    };
  }

  function rendererInfo(gl) {
    let vendor = "";
    let renderer = "";
    let unmasked = false;
    try {
      const debug = gl.getExtension(EXTENSIONS.debugRendererInfo);
      if (debug) {
        vendor = String(gl.getParameter(debug.UNMASKED_VENDOR_WEBGL) || "");
        renderer = String(gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) || "");
        unmasked = renderer.length > 0;
      }
    } catch (error) {
      unmasked = false;
    }
    if (!renderer) {
      try {
        vendor = vendor || String(gl.getParameter(gl.VENDOR) || "");
        renderer = String(gl.getParameter(gl.RENDERER) || "");
      } catch (error) {
        renderer = "";
      }
    }
    return {vendor: vendor, renderer: renderer, unmasked: unmasked};
  }

  // Coarse class of the GPU from its renderer string. ANGLE wraps the real name, e.g.
  // "ANGLE (NVIDIA, NVIDIA GeForce RTX 4060 (0x00002882) Direct3D11 vs_5_0 ps_5_0, D3D11)".
  // floor/cap are tier indexes (-1 = none).
  function classifyRenderer(renderer, vendor, nav) {
    const text = (String(renderer) + " " + String(vendor)).toLowerCase();
    if (/swiftshader|llvmpipe|softpipe|software|basic render|microsoft basic/.test(text)) {
      return {kind: "software", floor: 0, cap: 0, guess: 0};
    }
    if (/mali|adreno|powervr|videocore|immortalis|xclipse|tegra/.test(text) || nav.mobile) {
      // Snapdragon laptops (Adreno on Windows) are not phones; let them reach mid.
      const windowsArm = /adreno/.test(text) && /windows/i.test(nav.userAgent);
      return {kind: "mobile", floor: 0, cap: 1, guess: windowsArm ? 1 : 0};
    }
    if (/apple m\d+ (pro|max|ultra)/.test(text)) {
      return {kind: "integrated", floor: 1, cap: 1, guess: 1};
    }
    // Safari reports a masked "Apple GPU" on every Mac (iPhone/iPad are caught as mobile above).
    // Chrome on Intel Macs names the real GPU after the vendor, e.g. "ANGLE (Apple, ANGLE Metal
    // Renderer: AMD Radeon Pro 5500M, ...)"; those discrete parts fall through to the next case.
    if ((/apple m\d+/.test(text) || /apple/.test(text))
        && !/radeon pro|radeon rx|geforce|nvidia|quadro|firepro/.test(text)) {
      return {kind: "integrated", floor: 0, cap: 1, guess: 1};
    }
    if (/geforce|rtx|gtx|quadro|nvidia|radeon rx|radeon pro|radeon \(tm\) rx|firepro|intel\(r\) arc|intel arc|\barc a\d/.test(text)) {
      // Laptop "MX" GeForce parts are entry level; keep them out of ultra.
      const entry = /geforce mx|gt \d{3}\b|gt\d{3}\b/.test(text);
      return {kind: "discrete", floor: 1, cap: entry ? 2 : 3, guess: entry ? 1 : 2};
    }
    if (/intel|uhd graphics|iris|hd graphics|radeon\(tm\) graphics|radeon graphics|vega \d|radeon vega/.test(text)) {
      return {kind: "integrated", floor: 0, cap: 1, guess: 1};
    }
    return {kind: "unknown", floor: 0, cap: 2, guess: 1};
  }

  function tierFromScore(score) {
    if (!(score > 0)) return -1;
    if (score >= SCORE_ULTRA) return 3;
    if (score >= SCORE_HIGH) return 2;
    if (score >= SCORE_MID) return 1;
    return 0;
  }

  function enableExtensions(gl) {
    const result = {};
    const names = Object.keys(EXTENSIONS);
    for (let i = 0; i < names.length; i++) {
      const key = names[i];
      let enabled = false;
      try {
        enabled = !!gl.getExtension(EXTENSIONS[key]);
      } catch (error) {
        enabled = false;
      }
      result[key] = enabled;
    }
    let supported = [];
    try {
      supported = gl.getSupportedExtensions() || [];
    } catch (error) {
      supported = [];
    }
    result.supported = supported.slice(0);
    return result;
  }

  function limits(gl, extensions) {
    const read = function (name, fallback) {
      try {
        const value = gl.getParameter(name);
        return typeof value === "number" ? value : fallback;
      } catch (error) {
        return fallback;
      }
    };
    let maxAnisotropy = 1;
    if (extensions.anisotropic) {
      try {
        const ext = gl.getExtension(EXTENSIONS.anisotropic);
        maxAnisotropy = Number(gl.getParameter(ext.MAX_TEXTURE_MAX_ANISOTROPY_EXT)) || 1;
      } catch (error) {
        maxAnisotropy = 1;
      }
    }
    return {
      maxTextureSize: read(gl.MAX_TEXTURE_SIZE, 2048),
      maxRenderbufferSize: read(gl.MAX_RENDERBUFFER_SIZE, 2048),
      maxDrawBuffers: read(gl.MAX_DRAW_BUFFERS, 4),
      maxColorAttachments: read(gl.MAX_COLOR_ATTACHMENTS, 4),
      maxSamples: read(gl.MAX_SAMPLES, 4),
      maxUniformBlockSize: read(gl.MAX_UNIFORM_BLOCK_SIZE, 16384),
      maxTextureImageUnits: read(gl.MAX_TEXTURE_IMAGE_UNITS, 16),
      maxAnisotropy: maxAnisotropy
    };
  }

  // Saves and restores exactly the bindings the completeness probe touches, so probing the
  // game's own context cannot desynchronise BrowserOpenGL's binding caches.
  // drainErrors is only set for the private probe context: on the game's context a pending error
  // belongs to BrowserOpenGL and must stay visible to it.
  function probeFloatTargets(gl, extensions, drainErrors) {
    const result = {rgba16f: false, rgba32f: false, r11fg11fb10f: false};
    if (!extensions.colorBufferFloat && !extensions.colorBufferHalfFloat) return result;
    let savedTexture = null;
    let savedDraw = null;
    let savedRead = null;
    let savedUnpack = null;
    try {
      savedTexture = gl.getParameter(gl.TEXTURE_BINDING_2D);
      savedDraw = gl.getParameter(gl.DRAW_FRAMEBUFFER_BINDING);
      savedRead = gl.getParameter(gl.READ_FRAMEBUFFER_BINDING);
      savedUnpack = gl.getParameter(gl.PIXEL_UNPACK_BUFFER_BINDING);
    } catch (error) {
      return result;
    }
    const formats = [
      ["rgba16f", gl.RGBA16F],
      ["rgba32f", gl.RGBA32F],
      ["r11fg11fb10f", gl.R11F_G11F_B10F]
    ];
    const framebuffer = gl.createFramebuffer();
    try {
      gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, null);
      gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
      for (let i = 0; i < formats.length; i++) {
        const texture = gl.createTexture();
        try {
          gl.bindTexture(gl.TEXTURE_2D, texture);
          gl.texStorage2D(gl.TEXTURE_2D, 1, formats[i][1], 4, 4);
          gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
          result[formats[i][0]] =
              gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE;
          gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, null, 0);
        } finally {
          gl.deleteTexture(texture);
        }
      }
    } catch (error) {
      // Leave the remaining formats false.
    } finally {
      gl.deleteFramebuffer(framebuffer);
      gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, savedDraw);
      gl.bindFramebuffer(gl.READ_FRAMEBUFFER, savedRead);
      gl.bindTexture(gl.TEXTURE_2D, savedTexture);
      gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, savedUnpack);
      if (drainErrors) {
        try {
          let guard = 16;
          while (gl.getError() !== gl.NO_ERROR && guard-- > 0) { /* drain */ }
        } catch (error) {
          // ignore
        }
      }
    }
    return result;
  }

  const BENCH_VS = "#version 300 es\n"
      + "void main() {\n"
      + "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
      + "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
      + "}\n";
  const BENCH_FS = "#version 300 es\n"
      + "precision highp float;\n"
      + "uniform float uSeed;\n"
      + "out vec4 outColor;\n"
      + "void main() {\n"
      + "  vec2 p = gl_FragCoord.xy * 0.0137 + uSeed;\n"
      + "  vec3 c = vec3(0.0);\n"
      + "  for (int i = 0; i < 24; i++) {\n"
      + "    p = vec2(p.x * 1.031 + sin(p.y), p.y * 0.977 + cos(p.x));\n"
      + "    c += vec3(fract(p.x), fract(p.y), fract(p.x + p.y)) * 0.041;\n"
      + "  }\n"
      + "  outColor = vec4(c, 1.0);\n"
      + "}\n";

  function compile(gl, type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const log = gl.getShaderInfoLog(shader);
      gl.deleteShader(shader);
      throw new Error("benchmark shader: " + log);
    }
    return shader;
  }

  // Fill-rate microbenchmark on a private context: draws full-screen triangles with a fixed
  // ALU-heavy fragment shader into a 512x512 RGBA8 target and synchronises with a one-pixel
  // readPixels. Doubles the draw count until a sample takes at least BENCH_MIN_SAMPLE_MS and
  // keeps the best megapixel-per-second figure within BENCH_BUDGET_MS.
  function runBenchmark(gl) {
    const now = function () {
      return root.performance && root.performance.now ? root.performance.now() : Date.now();
    };
    const started = now();
    let program = null;
    let vs = null;
    let fs = null;
    let texture = null;
    let framebuffer = null;
    let vao = null;
    try {
      vs = compile(gl, gl.VERTEX_SHADER, BENCH_VS);
      fs = compile(gl, gl.FRAGMENT_SHADER, BENCH_FS);
      program = gl.createProgram();
      gl.attachShader(program, vs);
      gl.attachShader(program, fs);
      gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
        throw new Error("benchmark link: " + gl.getProgramInfoLog(program));
      }
      texture = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, BENCH_SIZE, BENCH_SIZE);
      framebuffer = gl.createFramebuffer();
      gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
        throw new Error("benchmark framebuffer incomplete");
      }
      vao = gl.createVertexArray();
      gl.bindVertexArray(vao);
      gl.useProgram(program);
      gl.viewport(0, 0, BENCH_SIZE, BENCH_SIZE);
      gl.disable(gl.BLEND);
      gl.disable(gl.DEPTH_TEST);
      gl.disable(gl.SCISSOR_TEST);
      const seed = gl.getUniformLocation(program, "uSeed");
      const pixel = new Uint8Array(4);
      // Warm-up: compile on first use, flush the pipeline.
      gl.uniform1f(seed, 0.5);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
      let draws = 2;
      let best = 0;
      let samples = 0;
      while (now() - started < BENCH_BUDGET_MS) {
        const t0 = now();
        for (let i = 0; i < draws; i++) {
          gl.uniform1f(seed, (i + 1) * 0.37 + samples);
          gl.drawArrays(gl.TRIANGLES, 0, 3);
        }
        gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
        const elapsed = now() - t0;
        samples++;
        if (elapsed >= BENCH_MIN_SAMPLE_MS) {
          const mpix = (draws * BENCH_SIZE * BENCH_SIZE) / (elapsed * 1000);
          if (mpix > best) best = mpix;
        }
        if (elapsed < BENCH_MIN_SAMPLE_MS * 2 && draws < 4096) draws *= 2;
        if (gl.isContextLost && gl.isContextLost()) break;
      }
      return {score: Math.round(best), ms: Math.round(now() - started), samples: samples};
    } catch (error) {
      return {score: 0, ms: Math.round(now() - started), samples: 0, error: String(error)};
    } finally {
      try {
        gl.bindVertexArray(null);
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        gl.bindTexture(gl.TEXTURE_2D, null);
        gl.useProgram(null);
        if (vao) gl.deleteVertexArray(vao);
        if (framebuffer) gl.deleteFramebuffer(framebuffer);
        if (texture) gl.deleteTexture(texture);
        if (program) gl.deleteProgram(program);
        if (vs) gl.deleteShader(vs);
        if (fs) gl.deleteShader(fs);
      } catch (error) {
        // The context is about to be released anyway.
      }
    }
  }

  function buildCaps(gl, nav, extensions, float, renderer, source) {
    const lim = limits(gl, extensions);
    return {
      schema: SCHEMA,
      source: source,
      tier: "mid",
      tierIndex: 1,
      kind: "unknown",
      score: 0,
      benchmarkMs: 0,
      renderer: renderer.renderer,
      vendor: renderer.vendor,
      rendererUnmasked: renderer.unmasked,
      mobile: nav.mobile,
      tablet: nav.tablet,
      cros: nav.cros,
      deviceMemory: nav.deviceMemory,
      hardwareConcurrency: nav.hardwareConcurrency,
      webgpu: webGpuSignal(),
      extensions: {
        colorBufferFloat: !!extensions.colorBufferFloat,
        colorBufferHalfFloat: !!extensions.colorBufferHalfFloat,
        floatBlend: !!extensions.floatBlend,
        drawBuffersIndexed: !!extensions.drawBuffersIndexed,
        textureFloatLinear: !!extensions.textureFloatLinear,
        anisotropic: !!extensions.anisotropic,
        parallelShaderCompile: !!extensions.parallelShaderCompile,
        multiDraw: !!extensions.multiDraw,
        timerQuery: !!extensions.timerQuery
      },
      supportedExtensions: extensions.supported || [],
      floatTargets: float,
      maxTextureSize: lim.maxTextureSize,
      maxRenderbufferSize: lim.maxRenderbufferSize,
      maxDrawBuffers: lim.maxDrawBuffers,
      maxColorAttachments: lim.maxColorAttachments,
      maxSamples: lim.maxSamples,
      maxUniformBlockSize: lim.maxUniformBlockSize,
      maxTextureImageUnits: lim.maxTextureImageUnits,
      maxAnisotropy: lim.maxAnisotropy,
      rebench: false
    };
  }

  // Class of the GPU from the WebGPU adapter info, for renderers WebGL masks or does not name.
  // Vendor and architecture are lower-case identifiers ("nvidia", "intel" + "gen-12lp", ...).
  function classifyAdapter(webgpu) {
    if (!webgpu || webgpu.status !== "ok") return null;
    const vendor = String(webgpu.vendor || "").toLowerCase();
    const arch = String(webgpu.architecture || "").toLowerCase();
    const text = vendor + " " + arch + " " + String(webgpu.description || "").toLowerCase();
    if (/nvidia/.test(vendor)) return {kind: "discrete", floor: 1, cap: 3, guess: 2};
    if (/intel/.test(vendor)) {
      return /xe-hpg|alchemist|battlemage|\barc\b/.test(text)
        ? {kind: "discrete", floor: 1, cap: 2, guess: 2}
        : {kind: "integrated", floor: 0, cap: 1, guess: 1};
    }
    if (/qualcomm|arm|imagination|samsung|broadcom|mediatek/.test(vendor)) {
      return {kind: "mobile", floor: 0, cap: 1, guess: 0};
    }
    if (/apple/.test(vendor)) return {kind: "integrated", floor: 0, cap: 2, guess: 1};
    // AMD names its APUs and discrete parts with the same architectures: no class.
    return null;
  }

  // The automatic tier from the machine's signals, without side effects (the decision table of
  // the header; GaiusQuality.caps.decide). Signals: renderer, vendor, mobile, userAgent, cros,
  // deviceMemory, hardwareConcurrency, maxTextureSize, rgba16f (false only when probed and
  // missing), score (benchmark MP/s, 0 = none), webgpu ({status, vendor, architecture,
  // description, isFallbackAdapter, maxTextureDimension2D}), noContext (a document without a
  // WebGL 2 context). Returns {tierIndex, tier, kind, benchTierIndex, guessTierIndex, reasons}.
  function decide(signals) {
    const s = signals || {};
    const reasons = [];
    const nav = {mobile: !!s.mobile, userAgent: String(s.userAgent || "")};
    let cls = classifyRenderer(s.renderer || "", s.vendor || "", nav);
    const webgpu = s.webgpu || null;
    if (webgpu && webgpu.status === "ok" && webgpu.isFallbackAdapter) {
      cls = {kind: "software", floor: 0, cap: 0, guess: 0};
      reasons.push("WebGPU offers only a fallback (software) adapter");
    } else if (cls.kind === "unknown") {
      const adapterClass = classifyAdapter(webgpu);
      if (adapterClass) {
        cls = adapterClass;
        reasons.push("GPU class from the WebGPU adapter (" + webgpu.vendor
            + (webgpu.architecture ? " " + webgpu.architecture : "") + ")");
      }
    }
    const name = String(s.renderer || "").replace(/\s+/g, " ").trim();
    reasons.push(cls.kind + " GPU" + (name ? " " + name.slice(0, 96) : ""));
    const benchTierIndex = tierFromScore(Number(s.score) || 0);
    let index = benchTierIndex;
    if (index >= 0) {
      reasons.push("benchmark " + Math.round(Number(s.score)) + " MP/s (" + TIERS[index] + ")");
    } else {
      index = cls.guess;
      reasons.push("no benchmark, renderer suggests " + TIERS[index]);
    }
    const clamped = clampTier(index, cls.floor, cls.cap);
    if (clamped !== index) {
      reasons.push((clamped < index ? "at most " : "at least ") + TIERS[clamped] + " for "
          + (/^[aeiou]/.test(cls.kind) ? "an " : "a ") + cls.kind + " GPU");
    }
    index = clamped;
    const caps = [];
    const capAt = function (tier, reason) {
      caps.push({tier: tier, reason: reason});
    };
    const memory = Number(s.deviceMemory) || 0;
    const threads = Number(s.hardwareConcurrency) || 0;
    if (s.cros) {
      const strong = threads >= 8 && memory >= 8 && benchTierIndex >= 2;
      capAt(strong ? 1 : 0, strong ? "ChromeOS (Chromebook) with 8+ threads, 8+ GiB and a fast GPU"
          : "ChromeOS (Chromebook)");
    }
    if (memory > 0 && memory <= 2) capAt(0, "device memory " + memory + " GiB");
    else if (memory > 0 && memory <= 4) capAt(1, "device memory " + memory + " GiB");
    if (threads > 0 && threads <= 2) capAt(0, threads + " CPU threads");
    else if (threads > 0 && threads <= 4) capAt(1, threads + " CPU threads");
    if (s.noContext) capAt(0, "no WebGL 2 context");
    else if (Number(s.maxTextureSize) > 0 && Number(s.maxTextureSize) < 4096) {
      capAt(0, "max texture size " + s.maxTextureSize);
    }
    if (s.rgba16f === false) capAt(1, "no RGBA16F colour targets");
    if (webgpu) {
      if (webgpu.status === "none") capAt(1, "WebGPU without an adapter (GPU blocklisted or unsupported)");
      else if (webgpu.status === "unsupported") capAt(2, "no WebGPU in this browser (WebGL 2 only)");
      else if (webgpu.status === "ok" && Number(webgpu.maxTextureDimension2D) > 0
          && Number(webgpu.maxTextureDimension2D) < 8192) {
        capAt(1, "WebGPU limits below the desktop baseline");
      }
    }
    for (let i = 0; i < caps.length; i++) {
      if (caps[i].tier < index) {
        index = caps[i].tier;
        reasons.push("capped at " + TIERS[index] + ": " + caps[i].reason);
      }
    }
    return {
      tierIndex: index,
      tier: TIERS[index],
      kind: cls.kind,
      benchTierIndex: benchTierIndex,
      guessTierIndex: cls.guess,
      reasons: reasons
    };
  }

  // Final tier: benchmark tier (or the renderer guess when the benchmark failed), clamped by the
  // renderer class, then capped by the weakest machine signal (decide()).
  function decideTier(caps, score) {
    const decision = decide({
      renderer: caps.renderer,
      vendor: caps.vendor,
      mobile: caps.mobile,
      userAgent: (root.navigator && root.navigator.userAgent) || "",
      cros: caps.cros,
      deviceMemory: caps.deviceMemory,
      hardwareConcurrency: caps.hardwareConcurrency,
      maxTextureSize: caps.maxTextureSize,
      rgba16f: caps.floatTargets ? !!caps.floatTargets.rgba16f : null,
      score: score,
      webgpu: caps.webgpu || null,
      noContext: !!caps.noContext
    });
    caps.kind = decision.kind;
    caps.benchTierIndex = decision.benchTierIndex;
    caps.guessTierIndex = decision.guessTierIndex;
    caps.tierIndex = decision.tierIndex;
    caps.tier = decision.tier;
    caps.reasons = decision.reasons;
    return caps;
  }

  function probeTemporaryContext(nav) {
    const doc = root.document;
    if (!doc || typeof doc.createElement !== "function") return null;
    let canvas = null;
    let gl = null;
    try {
      canvas = doc.createElement("canvas");
      canvas.width = BENCH_SIZE;
      canvas.height = BENCH_SIZE;
      gl = canvas.getContext("webgl2", {
        alpha: false,
        antialias: false,
        depth: false,
        stencil: false,
        powerPreference: "high-performance",
        failIfMajorPerformanceCaveat: false
      });
    } catch (error) {
      gl = null;
    }
    if (!gl) return null;
    try {
      const extensions = enableExtensions(gl);
      const float = probeFloatTargets(gl, extensions, true);
      const renderer = rendererInfo(gl);
      const caps = buildCaps(gl, nav, extensions, float, renderer, "probe");
      const bench = runBenchmark(gl);
      caps.score = bench.score;
      caps.benchmarkMs = bench.ms;
      if (bench.error) caps.benchmarkError = bench.error;
      return decideTier(caps, bench.score);
    } finally {
      // Do not force WEBGL_lose_context here.  Firefox treats an explicit loss on a
      // short-lived probe as a GPU/context reset and can deliver that loss to the
      // real game context created immediately afterwards, leaving startup blocked
      // while resources wait for a context that will never recover.  The probe
      // canvas is detached already; dropping its backing store is sufficient for
      // the browser to reclaim it without perturbing the game's context.
      canvas.width = 1;
      canvas.height = 1;
      if (typeof canvas.remove === "function") canvas.remove();
    }
  }

  function fromStoredRecord(record, nav) {
    const index = tierIndex(record.tier);
    return {
      schema: SCHEMA,
      source: "storage",
      tier: TIERS[index],
      tierIndex: index,
      kind: String(record.kind || "unknown"),
      score: Number(record.score) || 0,
      benchmarkMs: 0,
      renderer: String(record.renderer || ""),
      vendor: String(record.vendor || ""),
      rendererUnmasked: !!record.renderer,
      mobile: nav.mobile,
      tablet: nav.tablet,
      cros: nav.cros,
      deviceMemory: nav.deviceMemory,
      hardwareConcurrency: nav.hardwareConcurrency,
      webgpu: record.webgpu && typeof record.webgpu === "object" ? record.webgpu : null,
      reasons: Array.isArray(record.reasons) ? record.reasons.map(String) : [],
      // Remembered from the probe; attach() replaces them with the game context's own.
      extensions: record.extensions && typeof record.extensions === "object" ? record.extensions : null,
      supportedExtensions: [],
      floatTargets: record.floatTargets && typeof record.floatTargets === "object"
          ? record.floatTargets : null,
      maxTextureSize: Number(record.maxTextureSize) || 0,
      rebench: !!record.rebench
    };
  }

  // The WebGPU adapter signal for decide(): the glue's adapter when it already has one, else the
  // adapter this layer requested at load time. null while that request is still pending.
  function webGpuSignal() {
    try {
      const glue = root.GaiusWebGpu;
      const info = glue && glue.adapterInfo;
      if (info && typeof info === "object") {
        return {
          status: "ok",
          vendor: String(info.vendor || ""),
          architecture: String(info.architecture || ""),
          device: String(info.device || ""),
          description: String(info.description || ""),
          isFallbackAdapter: !!info.isFallbackAdapter,
          maxTextureDimension2D: state.webgpu && state.webgpu.maxTextureDimension2D || 0
        };
      }
    } catch (error) {
      // Fall through to the own probe.
    }
    const own = state.webgpu;
    return own && own.status !== "pending" ? own : null;
  }

  // Requests a WebGPU adapter once, off the critical path, for its info and limits only (no
  // device). Started at load time when this boot will classify the machine.
  function startWebGpuProbe() {
    if (state.webgpuPromise) return state.webgpuPromise;
    const nav = root.navigator;
    const gpu = nav && nav.gpu;
    if (!root.document) {
      state.webgpuPromise = Promise.resolve(null);
      return state.webgpuPromise;
    }
    if (!gpu || typeof gpu.requestAdapter !== "function") {
      state.webgpu = {status: "unsupported"};
      state.webgpuPromise = Promise.resolve(state.webgpu);
      return state.webgpuPromise;
    }
    state.webgpu = {status: "pending"};
    let request;
    try {
      request = Promise.resolve(gpu.requestAdapter({powerPreference: "high-performance"}));
    } catch (error) {
      request = Promise.reject(error);
    }
    state.webgpuPromise = request.then(function (adapter) {
      if (!adapter) {
        state.webgpu = {status: "none"};
        return state.webgpu;
      }
      const info = adapter.info || {};
      const adapterLimits = adapter.limits || {};
      state.webgpu = {
        status: "ok",
        vendor: String(info.vendor || ""),
        architecture: String(info.architecture || ""),
        device: String(info.device || ""),
        description: String(info.description || ""),
        isFallbackAdapter: !!(info.isFallbackAdapter || adapter.isFallbackAdapter),
        maxTextureDimension2D: Number(adapterLimits.maxTextureDimension2D) || 0,
        maxBufferSize: Number(adapterLimits.maxBufferSize) || 0
      };
      return state.webgpu;
    }, function (error) {
      state.webgpu = {status: "error", error: String(error && error.message || error).slice(0, 160)};
      return state.webgpu;
    });
    return state.webgpuPromise;
  }

  // Resolves once the WebGPU adapter signal is known (at once when this boot reads a remembered
  // classification). gaius-boot.js waits for it, bounded, before ensureTier().
  function adapterReady() {
    if (state.caps) return Promise.resolve(state.webgpu);
    const record = readStoredRecord();
    if (record && !record.rebench && urlParam("gaiusGpuRebench") !== "1") {
      return Promise.resolve(null);
    }
    return startWebGpuProbe();
  }

  // One console line per decision: "Gaius quality: tier high (auto; ...reasons)".
  function logDecision(caps) {
    try {
      const reasons = Array.isArray(caps.reasons) ? caps.reasons : [];
      let line = "Gaius quality: tier " + caps.tier + " (";
      if (caps.overridden) {
        line += "player override; detected " + (caps.detectedTier || "?");
      } else {
        line += "auto" + (caps.source === "storage" ? ", remembered" : "");
      }
      if (reasons.length) line += "; " + reasons.join("; ");
      line += ")";
      caps.summary = line.slice("Gaius quality: ".length);
      if (line !== state.logged) {
        state.logged = line;
        const console = root.console;
        if (console && typeof console.info === "function") console.info(line);
        else if (console && typeof console.log === "function") console.log(line);
      }
    } catch (error) {
      // Diagnostics only.
    }
    return caps;
  }

  function applyOverride(caps) {
    const detected = caps.overridden && caps.detectedTier ? caps.detectedTier : caps.tier;
    const forced = overrideTier();
    caps.detectedTier = detected;
    if (forced) {
      caps.tier = forced;
      caps.tierIndex = tierIndex(forced);
      caps.overridden = true;
    } else {
      caps.tier = detected;
      caps.tierIndex = tierIndex(detected);
      caps.overridden = false;
    }
    return caps;
  }

  // window.__gaiusGpuCaps has more than one writer: BrowserOpenGL adds anisotropicFiltering,
  // maxAnisotropy, multiDraw and terrainBatchMode to it at context init. A new capability record
  // is therefore merged into the published object instead of replacing it; only the fields this
  // layer wrote earlier and no longer reports are removed.
  function publish(caps) {
    let published = caps;
    try {
      const existing = root.__gaiusGpuCaps;
      if (existing && typeof existing === "object" && existing !== caps) {
        const owned = state.ownedKeys || [];
        for (let i = 0; i < owned.length; i++) {
          if (!Object.prototype.hasOwnProperty.call(caps, owned[i])) delete existing[owned[i]];
        }
        state.ownedKeys = Object.keys(caps);
        published = Object.assign(existing, caps);
      } else if (existing !== caps) {
        state.ownedKeys = Object.keys(caps);
        root.__gaiusGpuCaps = caps;
      }
    } catch (error) {
      // ignore
    }
    state.caps = published;
    if (Q.profile && typeof Q.profile.invalidate === "function") Q.profile.invalidate();
    return published;
  }

  function ensureTier() {
    if (state.caps) return state.caps;
    const nav = navigatorInfo();
    if (urlParam("gaiusGpuRebench") === "1") storageSet(STORAGE_KEY, null);
    const record = readStoredRecord();
    let caps = null;
    if (record && !record.rebench) {
      caps = fromStoredRecord(record, nav);
    } else {
      caps = probeTemporaryContext(nav);
      if (caps) {
        // A score below what the renderer suggests usually means a busy machine (a compile, a
        // game, another tab) rather than a weak GPU: keep the renderer's tier this boot and
        // benchmark again next boot; only a second low measurement lowers the tier.
        const low = caps.benchTierIndex >= 0 && caps.benchTierIndex < caps.guessTierIndex;
        if (low && !(record && record.lowBench)) {
          const score = caps.score;
          decideTier(caps, 0);
          caps.reasons.push("benchmark " + score + " MP/s below the renderer's tier, measured again next boot");
          caps.score = score;
          caps.lowBench = true;
          caps.rebench = true;
        }
        writeStoredRecord(caps);
      }
    }
    if (!caps) {
      // No document (Worker) or no WebGL2: classify from the user agent and the machine alone.
      const doc = root.document;
      caps = {
        schema: SCHEMA,
        source: "fallback",
        tier: "mid",
        tierIndex: 1,
        kind: "unknown",
        score: 0,
        renderer: "",
        vendor: "",
        mobile: nav.mobile,
        tablet: nav.tablet,
        cros: nav.cros,
        deviceMemory: nav.deviceMemory,
        hardwareConcurrency: nav.hardwareConcurrency,
        webgpu: webGpuSignal(),
        // A page that could not create a WebGL 2 context: the GPU is blocklisted or missing.
        noContext: !!(doc && typeof doc.createElement === "function"),
        extensions: null,
        floatTargets: null,
        rebench: false
      };
      decideTier(caps, 0);
    }
    return publish(logDecision(applyOverride(caps)));
  }

  function onContextLost() {
    if (state.caps) state.caps.contextLost = true;
  }

  function onContextRestored() {
    const gl = state.attachedContext;
    if (gl) attach(gl);
  }

  // Binds the capability layer to the game's context. Safe to call repeatedly (it re-enables the
  // extensions, which a restored context has forgotten).
  function attach(gl) {
    if (!gl) return ensureTier();
    const base = ensureTier();
    const nav = navigatorInfo();
    const extensions = enableExtensions(gl);
    const float = probeFloatTargets(gl, extensions, false);
    const renderer = rendererInfo(gl);
    const caps = buildCaps(gl, nav, extensions, float, renderer, "attach");
    caps.score = base.score || 0;
    caps.benchmarkMs = base.benchmarkMs || 0;
    caps.kind = base.kind;
    caps.overridden = !!base.overridden;
    caps.detectedTier = base.detectedTier || base.tier;
    caps.contextLost = false;
    const sameGpu = !base.renderer || !renderer.renderer || base.renderer === renderer.renderer;
    caps.source = base.source === "storage" ? "storage" : caps.source;
    caps.reasons = Array.isArray(base.reasons) ? base.reasons.slice(0) : [];
    if (sameGpu) {
      caps.tier = base.tier;
      caps.tierIndex = base.tierIndex;
    } else {
      // The game runs on a different adapter than the probe saw (or than the stored record
      // describes): classify from the real renderer now and benchmark again next boot.
      decideTier(caps, 0);
      caps.rebench = true;
      writeStoredRecord(caps);
      caps.overridden = false;
      caps.detectedTier = null;
      applyOverride(caps);
    }
    // A probe that found no float colour targets cannot be trusted over the real context.
    if (!float.rgba16f && caps.tierIndex > 1 && !caps.overridden) {
      caps.tierIndex = 1;
      caps.tier = TIERS[1];
      caps.reasons.push("capped at mid: the game's context has no RGBA16F colour targets");
    }
    state.attachedContext = gl;
    if (!state.attachedListeners && gl.canvas && gl.canvas.addEventListener) {
      state.attachedListeners = true;
      gl.canvas.addEventListener("webglcontextlost", onContextLost, false);
      gl.canvas.addEventListener("webglcontextrestored", onContextRestored, false);
    }
    return publish(logDecision(caps));
  }

  function setOverride(name) {
    const index = tierIndex(name);
    storageSet(OVERRIDE_KEY, index >= 0 ? TIERS[index] : null);
    if (state.caps) publish(logDecision(applyOverride(state.caps)));
    return state.caps;
  }

  Q.caps = Object.freeze({
    TIERS: TIERS,
    STORAGE_KEY: STORAGE_KEY,
    ensureTier: ensureTier,
    attach: attach,
    current: function () {
      return state.caps;
    },
    tierIndex: tierIndex,
    setOverride: setOverride,
    forgetBenchmark: function () {
      storageSet(STORAGE_KEY, null);
    },
    classifyRenderer: function (renderer, vendor) {
      return classifyRenderer(renderer, vendor, navigatorInfo());
    },
    decide: decide,
    adapterReady: adapterReady
  });

  // The adapter request runs while the client downloads; only boots that classify need it.
  try {
    adapterReady().catch(function () {});
  } catch (error) {
    // The decision falls back to the WebGL signals.
  }
})(typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : this));
