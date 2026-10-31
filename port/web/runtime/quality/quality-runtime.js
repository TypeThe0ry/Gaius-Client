// Gaius quality runtime: the per-frame entry points the patched 26.3 client calls through
// dev.gaius.browser.quality.BrowserQualityFrame / BrowserQualityCaps / BrowserQualityOptions.
// Registers GaiusQuality.runtime. Load it last (see gpu-caps.js for the order).
//
// Frame protocol (26.3 GameRenderer, patched by dev.gaius.tools.quality.QualityPatches263):
//   beginLevel(width, height, fullResolution)  at GameRenderer.renderLevel entry. Returns the
//       world render scale in per-mille (1000 while fullResolution: improved transparency or
//       entity outlines this frame); Java scales every render-pass viewport whose attachments
//       are width x height until endLevel, so the world lands in the bottom-left sub-rectangle.
//   endLevel(color, depth, outline, width, height, rectW, rectH, projection...)  right after
//       LevelRenderer.render: runs the post chain on the world rectangle and, when the world was
//       scaled, upscales it (EASU + RCAS) to the full main target before the hand and the GUI
//       draw. A scaled frame never renders entity outlines (fullResolution); should one carry an
//       outline texture anyway, that target is stretched the same way so its blit lines up.
//   inventoryDecision(newScreen)        whether the world render is skipped this frame while an
//       inventory screen is open (0 render, 1 skip). Skips only once a snapshot exists.
//   worldFrameDone(color, w, h, skipped, throttled)  after the world section of the frame:
//       snapshots the finished world while throttled and restores it on skipped frames (every
//       profile clears the main target before the world section). The inventory pair is also
//       called by 26.2 and 1.21.11 (MinecraftClientPatcher); the other hooks are 26.3 only.
// Texture arguments are BrowserOpenGL texture names, resolved through window.__gaiusGL.
//
// Wiring that lives outside this directory: the launcher loads the six scripts before the
// client starts and calls GaiusQuality.caps.ensureTier() after storage is ready; the SDL/GLFW
// canvas code calls GaiusQuality.runtime.onContext(gl) right after creating window.__gaiusWebGL
// and sizes the canvas with GaiusQuality.runtime.resolvePixelRatio(devicePixelRatio), which is
// the device ratio capped per tier (1 on low and mid, and on every profile but 26.3), not the
// native ratio. Without that wiring the runtime attaches lazily on the first frame and the
// canvas keeps its old cap.
//
// Under WebGPU (GaiusWebGpu.active, ?gpu=webgpu) the GL passes cannot touch the frame: the world
// renders at full scale (beginLevel answers 1000), endLevel runs the WGSL post chain
// (runtime/webgpu/quality-post.js) with the same stages and settings, the inventory snapshot does
// nothing, and oitAllowed() asks the WebGPU device (float32-blendable for the RGBA32F depth
// bounds) instead of the WebGL extensions, with the same tier and ?gaiusOit rules.
// There is no WGSL upscaler.
(function installGaiusQualityRuntime(root) {
  "use strict";
  if (!root) return;
  const Q = root.GaiusQuality || (root.GaiusQuality = {});
  if (Q.runtime) return;

  const MAX_ERRORS = 3;
  const IDLE_FRAMES_BEFORE_RELEASE = 600;
  const SNAPSHOT_IDLE_FRAMES = 120;

  const stats = {
    frames: 0,
    postFrames: 0,
    scaledFrames: 0,
    renderScale: 1,
    rectWidth: 0,
    rectHeight: 0,
    width: 0,
    height: 0,
    lastStages: "",
    lastSubmitMs: 0,
    maxSubmitMs: 0,
    errors: 0,
    lastError: null,
    disabled: false,
    inventoryRenders: 0,
    inventorySkips: 0,
    snapshotSaves: 0,
    snapshotRestores: 0,
    outlineStretches: 0,
    backend: "webgl2"
  };

  const state = {
    gl: null,
    res: null,
    failed: false,
    idleFrames: 0,
    scale: {current: 1, ewma: 0, last: 0, slow: 0, fast: 0, frame: 0, upAt: -1e9, downAt: -1e9,
        upFrames: 180},
    inventory: {
      active: false,
      lastRender: 0,
      snapshotValid: false,
      snapshotWidth: 0,
      snapshotHeight: 0,
      idleFrames: 0
    }
  };

  try {
    root.__gaiusQualityStats = stats;
  } catch (error) {
    // ignore
  }

  function now() {
    return root.performance && root.performance.now ? root.performance.now() : Date.now();
  }

  function settings() {
    return Q.profile ? Q.profile.settings() : null;
  }

  function fail(error) {
    stats.errors++;
    stats.lastError = String(error && error.stack ? error.stack : error);
    if (stats.errors >= MAX_ERRORS && !state.failed) {
      state.failed = true;
      stats.disabled = true;
      try {
        root.console && root.console.warn
            && root.console.warn("Gaius quality runtime disabled after errors:", stats.lastError);
      } catch (ignored) {
        // ignore
      }
    }
  }

  // A lost or restored context invalidates every object of the old resources, including the
  // wrappers around the game's textures (gl-pass.js ExternalFramebuffers never re-checks them).
  function dropResources() {
    if (state.res && state.res.external) state.res.external.forget();
    state.res = null;
    state.inventory.snapshotValid = false;
  }

  // The WebGPU backend renders this session (its glue says so once the core's device exists).
  function webGpuActive() {
    const gpu = root.GaiusWebGpu;
    return !!(gpu && gpu.active === true);
  }

  function onContext(gl) {
    if (!gl) return;
    if (state.gl !== gl) {
      state.gl = gl;
      dropResources();
      // Record viewport, scissor and colour mask from the start, so saveState never has to
      // read them back (gl-pass.js installShadow).
      try {
        if (Q.gl) Q.gl.installShadow(gl);
      } catch (error) {
        fail(error);
      }
      if (gl.canvas && gl.canvas.addEventListener) {
        gl.canvas.addEventListener("webglcontextlost", dropResources, false);
        gl.canvas.addEventListener("webglcontextrestored", dropResources, false);
      }
    }
    try {
      if (Q.caps) Q.caps.attach(gl);
    } catch (error) {
      fail(error);
    }
  }

  function context() {
    if (webGpuActive()) return null;
    const gl = root.__gaiusWebGL || null;
    if (!gl) return null;
    if (gl !== state.gl) onContext(gl);
    if (gl.isContextLost && gl.isContextLost()) return null;
    return gl;
  }

  function resources(gl) {
    if (state.res && state.res.gl === gl) return state.res;
    const G = Q.gl;
    const programs = new Map();
    state.res = {
      gl: gl,
      vao: gl.createVertexArray(),
      samplers: G.createSamplers(gl),
      pool: new G.TargetPool(),
      external: new G.ExternalFramebuffers(),
      stats: stats,
      program: function (name, source) {
        let program = programs.get(name);
        if (!program) {
          program = G.createProgram(gl, name, source);
          programs.set(name, program);
        }
        return program;
      }
    };
    return state.res;
  }

  function textureFor(id) {
    if (!id) return null;
    const glState = root.__gaiusGL;
    if (!glState || !glState.textures) return null;
    return glState.textures.get(id) || null;
  }

  function ready() {
    return !!(Q.gl && Q.profile && !state.failed);
  }

  // Automatic render scale: EWMA of the world frame interval against the target frame time,
  // stepping one quantized scale at a time with hysteresis (down after 45 frames slower than
  // 1.2x the target, up after 180 frames that meet it). Meeting the target counts as fast: with
  // VSync or a frame cap at the target rate the interval never drops below the target, so a
  // stricter bound would keep a stutter-induced step down forever. A step up that is undone
  // within 300 frames doubles the frames the next step up needs (up to 1800), so a load near the
  // threshold does not blur and sharpen the world every few seconds; holding a stepped-up scale
  // for 1800 frames restores the 180-frame wait.
  function autoScaleTick(s) {
    const a = state.scale;
    const t = now();
    if (a.last > 0) {
      const dt = Math.min(t - a.last, 250);
      a.ewma = a.ewma > 0 ? a.ewma * 0.9 + dt * 0.1 : dt;
    }
    a.last = t;
    if (s.renderScaleMode !== "auto") return;
    a.frame++;
    if (a.upFrames > 180 && a.upAt > a.downAt && a.frame - a.upAt >= 1800) a.upFrames = 180;
    const steps = s.renderScaleSteps;
    let index = steps.indexOf(a.current);
    if (index < 0) index = steps.length - 1;
    const target = 1000 / s.targetFps;
    if (a.ewma > target * 1.2) {
      a.slow++;
      a.fast = 0;
    } else if (a.ewma <= target * 1.05) {
      a.fast++;
      a.slow = 0;
    } else {
      a.slow = 0;
      a.fast = 0;
    }
    if (a.slow >= 45 && index > 0) {
      a.current = steps[index - 1];
      a.slow = 0;
      a.ewma = 0;
      if (a.frame - a.upAt < 300) a.upFrames = Math.min(a.upFrames * 2, 1800);
      a.downAt = a.frame;
    } else if (a.fast >= a.upFrames && index < steps.length - 1) {
      a.current = steps[index + 1];
      a.fast = 0;
      a.ewma = 0;
      a.upAt = a.frame;
    }
  }

  function beginLevel(width, height, fullResolution) {
    stats.frames++;
    if (webGpuActive()) {
      stats.backend = "webgpu";
      stats.renderScale = 1;
      return 1000;
    }
    if (!ready() || !Q.upscaler) return 1000;
    const s = settings();
    autoScaleTick(s);
    let scale = s.renderScaleMode === "auto" ? state.scale.current : s.renderScale;
    // OIT and the entity-outline post chain composite full-screen targets with normalized
    // coordinates inside the level render, so they cannot run on a sub-rectangle (Java passes
    // fullResolution for either); tiny windows are not worth scaling.
    if (fullResolution || width < 320 || height < 200 || !(scale > 0) || scale > 1) scale = 1;
    stats.renderScale = scale;
    return Math.round(scale * 1000);
  }

  function idleTick() {
    state.idleFrames++;
    if (state.idleFrames === IDLE_FRAMES_BEFORE_RELEASE && state.res
        && !state.inventory.snapshotValid) {
      const gl = context();
      if (gl) state.res.pool.dispose(gl);
    }
  }

  // Under WebGPU the texture ids are the negated glue handles of the core textures
  // (QualityPatches263's gaius$glTextureId) and the WGSL chain (runtime/webgpu/quality-post.js,
  // loaded with the glue) runs the same stages with the same settings on the glue's encoder. The
  // world is never scaled there, so there is no upscaling.
  function endLevelWebGpu(colorId, depthId, width, height, rectWidth, rectHeight, projection) {
    const post = Q.webgpuPost;
    const gpu = root.GaiusWebGpu;
    if (!post || !gpu || typeof gpu.debugObject !== "function" || !Q.profile || state.failed || colorId >= 0) return;
    const s = settings();
    stats.width = width;
    stats.height = height;
    stats.rectWidth = rectWidth;
    stats.rectHeight = rectHeight;
    if (!post.anyStage(s.post)) return;
    const color = gpu.debugObject(-colorId);
    if (!color) return;
    const started = now();
    const ran = post.run({color: color, depth: depthId < 0 ? gpu.debugObject(-depthId) : null, width: width,
      height: height, rectWidth: rectWidth, rectHeight: rectHeight, projection: projection, zeroToOne: true}, s.post);
    if (ran) {
      stats.postFrames++;
      stats.lastStages = post.stats.lastStages;
    } else if (post.stats.lastError) {
      stats.lastError = post.stats.lastError;
    }
    const elapsed = now() - started;
    stats.lastSubmitMs = Math.round(elapsed * 1000) / 1000;
    if (elapsed > stats.maxSubmitMs) stats.maxSubmitMs = stats.lastSubmitMs;
  }

  function endLevel(colorId, depthId, outlineId, width, height, rectWidth, rectHeight,
      m00, m11, m20, m21, m22, m23, m32, m33) {
    if (webGpuActive()) {
      endLevelWebGpu(colorId, depthId, width, height, rectWidth, rectHeight,
          [m00, m11, m20, m21, m22, m23, m32, m33]);
      return;
    }
    if (!ready()) return;
    const s = settings();
    const scaled = rectWidth < width || rectHeight < height;
    const postOn = !!(Q.postChain && Q.postChain.anyStage(s.post));
    stats.width = width;
    stats.height = height;
    stats.rectWidth = rectWidth;
    stats.rectHeight = rectHeight;
    if (!scaled && !postOn) {
      idleTick();
      return;
    }
    if (scaled && !Q.upscaler) return;
    const gl = context();
    if (!gl) return;
    const color = textureFor(colorId);
    if (!color) return;
    state.idleFrames = 0;
    const frame = {
      color: color,
      depth: textureFor(depthId),
      width: width,
      height: height,
      rectWidth: rectWidth,
      rectHeight: rectHeight,
      projection: [m00, m11, m20, m21, m22, m23, m32, m33]
    };
    const started = now();
    let saved = null;
    try {
      const G = Q.gl;
      const res = resources(gl);
      saved = G.saveState(gl);
      G.prepareState(gl, res.vao);
      const mainFramebuffer = res.external.get(gl, color);
      if (!scaled) {
        Q.postChain.run(res, frame, s.post, mainFramebuffer);
        stats.postFrames++;
      } else {
        let source = color;
        if (postOn) {
          source = Q.postChain.run(res, frame, s.post, null) || color;
          stats.postFrames++;
        }
        // The upscaler always goes through its EASU intermediate (sharpness >= 0), so reading
        // the main colour while RCAS writes it back is never a feedback loop.
        Q.upscaler.run(res, source, rectWidth, rectHeight, mainFramebuffer, width, height,
            Math.max(0, s.sharpness));
        const outline = outlineId ? textureFor(outlineId) : null;
        if (outline) {
          Q.upscaler.stretchInPlace(res, outline, rectWidth, rectHeight, width, height);
          stats.outlineStretches++;
        }
        stats.scaledFrames++;
      }
    } catch (error) {
      fail(error);
    } finally {
      if (saved) {
        try {
          Q.gl.restoreState(gl, saved);
        } catch (error) {
          fail(error);
        }
      }
    }
    const elapsed = now() - started;
    stats.lastSubmitMs = Math.round(elapsed * 1000) / 1000;
    if (elapsed > stats.maxSubmitMs) stats.maxSubmitMs = stats.lastSubmitMs;
  }

  function inventoryDecision(newScreen) {
    const inv = state.inventory;
    inv.active = true;
    inv.idleFrames = 0;
    const t = now();
    if (!ready() || newScreen || !inv.snapshotValid) {
      inv.lastRender = t;
      stats.inventoryRenders++;
      return 0;
    }
    const fps = settings().inventoryWorldFps;
    if (fps > 0 && t - inv.lastRender >= 1000 / fps - 1) {
      inv.lastRender = t;
      stats.inventoryRenders++;
      return 0;
    }
    stats.inventorySkips++;
    return 1;
  }

  function blitFull(gl, readFramebuffer, drawFramebuffer, width, height) {
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, readFramebuffer);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, drawFramebuffer);
    gl.blitFramebuffer(0, 0, width, height, 0, 0, width, height, gl.COLOR_BUFFER_BIT, gl.NEAREST);
  }

  function worldFrameDone(colorId, width, height, skipped, throttled) {
    const inv = state.inventory;
    if (webGpuActive()) {
      inv.active = !!throttled;
      inv.snapshotValid = false;
      return;
    }
    if (!throttled) {
      inv.active = false;
      if (inv.snapshotValid && ++inv.idleFrames > SNAPSHOT_IDLE_FRAMES) {
        inv.snapshotValid = false;
      }
      return;
    }
    if (!ready()) return;
    const gl = context();
    const color = textureFor(colorId);
    if (!gl || !color) {
      inv.snapshotValid = false;
      return;
    }
    let saved = null;
    try {
      const G = Q.gl;
      const res = resources(gl);
      saved = G.saveState(gl);
      G.prepareState(gl, res.vao);
      const main = res.external.get(gl, color);
      if (skipped) {
        if (inv.snapshotValid && inv.snapshotWidth === width && inv.snapshotHeight === height) {
          const snapshot = res.pool.get(gl, "inventory.snapshot", width, height, "rgba8");
          blitFull(gl, snapshot.framebuffer, main, width, height);
          stats.snapshotRestores++;
        } else {
          // Resized while skipping: render the next frame instead of showing a stale size.
          inv.snapshotValid = false;
        }
      } else {
        const snapshot = res.pool.get(gl, "inventory.snapshot", width, height, "rgba8");
        blitFull(gl, main, snapshot.framebuffer, width, height);
        inv.snapshotValid = true;
        inv.snapshotWidth = width;
        inv.snapshotHeight = height;
        stats.snapshotSaves++;
      }
    } catch (error) {
      inv.snapshotValid = false;
      fail(error);
    } finally {
      if (saved) {
        try {
          Q.gl.restoreState(gl, saved);
        } catch (error) {
          fail(error);
        }
      }
    }
  }

  function oitAllowed() {
    if (!Q.profile) return false;
    if (webGpuActive()) {
      // RGBA16F targets blend on every WebGPU device; the RGBA32F depth bounds blended with MAX
      // need float32-blendable. Two colour outputs with per-target blend state are core WebGPU.
      const gpu = root.GaiusWebGpu;
      const s = Q.profile.settings();
      if (s.oitForced === false) return false;
      const capable = typeof gpu.contextAvailable === "function" && gpu.contextAvailable() === true
        && typeof gpu.hasFeature === "function" && gpu.hasFeature("float32-blendable");
      return s.oitForced === true ? capable : capable && !!s.oitTier;
    }
    context();
    return Q.profile.oitAllowed();
  }

  function presetReplay() {
    const s = settings();
    return !!(s && s.presetReplay);
  }

  function resolvePixelRatio(devicePixelRatio) {
    return Q.profile ? Q.profile.resolvePixelRatio(devicePixelRatio) : Math.min(devicePixelRatio || 1, 1);
  }

  Q.runtime = Object.freeze({
    onContext: onContext,
    beginLevel: beginLevel,
    endLevel: endLevel,
    inventoryDecision: inventoryDecision,
    worldFrameDone: worldFrameDone,
    oitAllowed: oitAllowed,
    presetReplay: presetReplay,
    resolvePixelRatio: resolvePixelRatio,
    stats: function () {
      return stats;
    },
    reset: function () {
      state.failed = false;
      stats.disabled = false;
      stats.errors = 0;
      dropResources();
      if (Q.profile) Q.profile.invalidate();
    }
  });
})(typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : this));
