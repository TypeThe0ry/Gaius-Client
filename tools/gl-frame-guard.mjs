// Per-frame browser GPU guards for the CDP acceptance harnesses: synchronous WebGL calls per
// presented frame (which must be 0) and the unique presented frame rate on either backend.
//
// GL_FRAME_GUARD_SOURCE is evaluated in the page before its own scripts
// (Page.addScriptToEvaluateOnNewDocument). It wraps the WebGL2 prototype:
//
//   - Synchronous calls: every call that makes the renderer wait for the GPU process to execute
//     the command buffer (CommandBufferHelper::Finish). That is getParameter for the values the
//     browser does not cache (SCISSOR_BOX, COLOR_WRITEMASK, blend/depth/stencil state, clear
//     values, pack settings, ...; the object bindings, the active texture unit, VIEWPORT once
//     set, the unpack settings and the MAX_* limits answer from the browser's cache, as timing
//     each query behind a 200k-command backlog in headless Chrome shows), isFramebuffer and the
//     other is* queries, getBufferSubData, readPixels, getError, checkFramebufferStatus and
//     finish. Each is counted by name (getParameter by pname).
//   - Presented frames: WebGL counts a transition from off-screen work to a default-framebuffer
//     draw/blit (clears do not count). WebGPU counts a successful GaiusWebGpu.present. A
//     requestAnimationFrame probe compares the count between two callbacks.
//
// GL_FRAME_GUARD_WINDOW_EXPRESSION(ms) measures for ms milliseconds and resolves to plain JSON:
// rAF fps, unique presented fps, game frames per second, P95/P99 of the rAF interval and the
// synchronous calls (total, per game frame, by name). glFrameGuardFailures(window) returns the
// failures of the "synchronous GL calls per frame = 0" assertion.

export const GL_FRAME_GUARD_SOURCE = String.raw`(() => {
  "use strict";
  if (globalThis.__gaiusGlFrameGuard) return;
  let captureStackCall = "";
  try {
    captureStackCall = new URLSearchParams(location.search).get("gaiusGlTrace") || "";
  } catch (_) {}
  const G = globalThis.__gaiusGlFrameGuard = {frames: 0, defaultWrites: 0, syncCalls: 0, sync: {}, stacks: [], captureStackCall};
  let webGpu;
  Object.defineProperty(globalThis, "GaiusWebGpu", {
    configurable: true, enumerable: true,
    get() { return webGpu; },
    set(value) {
      if (value && typeof value.present === "function" && !value.__gaiusFrameGuardWrapped) {
        const present = value.present;
        value.present = function () {
          const before = value.stats && value.stats.presents;
          const result = present.apply(this, arguments);
          if (value.stats && value.stats.presents > before) G.frames++;
          return result;
        };
        value.__gaiusFrameGuardWrapped = true;
      }
      webGpu = value;
    }
  });
  const contexts = [];
  if (typeof WebGL2RenderingContext !== "undefined") contexts.push(WebGL2RenderingContext.prototype);
  if (typeof WebGLRenderingContext !== "undefined") contexts.push(WebGLRenderingContext.prototype);
  const names = {};
  if (typeof WebGL2RenderingContext !== "undefined") {
    for (const key in WebGL2RenderingContext) {
      const value = WebGL2RenderingContext[key];
      if (typeof value === "number" && !(value in names)) names[value] = key;
    }
  }
  const viewports = new WeakSet();
  const watchedContexts = new WeakSet();
  const CACHED = new Set(["CURRENT_PROGRAM", "VERTEX_ARRAY_BINDING", "FRAMEBUFFER_BINDING",
    "DRAW_FRAMEBUFFER_BINDING", "READ_FRAMEBUFFER_BINDING", "RENDERBUFFER_BINDING", "ACTIVE_TEXTURE",
    "TEXTURE_BINDING_2D", "TEXTURE_BINDING_2D_ARRAY", "TEXTURE_BINDING_3D", "TEXTURE_BINDING_CUBE_MAP",
    "SAMPLER_BINDING", "ARRAY_BUFFER_BINDING", "ELEMENT_ARRAY_BUFFER_BINDING", "COPY_READ_BUFFER_BINDING",
    "COPY_WRITE_BUFFER_BINDING", "PIXEL_PACK_BUFFER_BINDING", "PIXEL_UNPACK_BUFFER_BINDING",
    "UNIFORM_BUFFER_BINDING", "TRANSFORM_FEEDBACK_BUFFER_BINDING", "TRANSFORM_FEEDBACK_BINDING",
    "UNPACK_ALIGNMENT", "UNPACK_SKIP_ROWS", "UNPACK_SKIP_PIXELS", "UNPACK_FLIP_Y_WEBGL",
    "UNPACK_PREMULTIPLY_ALPHA_WEBGL", "UNPACK_COLORSPACE_CONVERSION_WEBGL", "UNIFORM_BUFFER_OFFSET_ALIGNMENT",
    "VENDOR", "RENDERER", "READ_BUFFER", "STENCIL_BITS"]);
  const cached = (gl, pname) => {
    const name = names[pname];
    if (!name) return false;
    if (name === "VIEWPORT") return viewports.has(gl);
    return CACHED.has(name) || name.startsWith("MAX_") || name.startsWith("DRAW_BUFFER");
  };
  const note = key => {
    G.syncCalls++;
    G.sync[key] = (G.sync[key] || 0) + 1;
    if (G.captureStackCall === key || G.captureStackCall === "all") {
      G.captureStackCall = "";
      const limit = Error.stackTraceLimit;
      Error.stackTraceLimit = 24;
      G.stacks.push({call: key, stack: String(new Error().stack).split("\n").slice(2, 24).join("\n")});
      Error.stackTraceLimit = limit;
    }
  };
  const draws = new WeakMap();
  const drawState = gl => {
    let s = draws.get(gl);
    if (!watchedContexts.has(gl)) {
      watchedContexts.add(gl);
      if (gl.canvas && gl.canvas.addEventListener) {
        const reset = () => { viewports.delete(gl); draws.delete(gl); };
        gl.canvas.addEventListener("webglcontextlost", reset, false);
        gl.canvas.addEventListener("webglcontextrestored", reset, false);
      }
    }
    if (!s) { s = {defaultBound: true, offscreen: true}; draws.set(gl, s); }
    return s;
  };
  const onWrite = gl => {
    const s = drawState(gl);
    if (s.defaultBound) {
      G.defaultWrites++;
      if (s.offscreen) { s.offscreen = false; G.frames++; }
    } else {
      s.offscreen = true;
    }
  };
  for (const proto of contexts) {
    const wrap = (name, make) => {
      const original = proto[name];
      if (typeof original === "function") proto[name] = make(original);
    };
    wrap("getParameter", original => function (pname) {
      drawState(this);
      if (!cached(this, pname)) note("getParameter:" + (names[pname] || ("0x" + Number(pname).toString(16))));
      return original.call(this, pname);
    });
    wrap("viewport", original => function (x, y, width, height) {
      drawState(this);
      if (width >= 0 && height >= 0) viewports.add(this);
      return original.call(this, x, y, width, height);
    });
    for (const name of ["isFramebuffer", "isBuffer", "isTexture", "isProgram", "isShader", "isRenderbuffer",
      "isVertexArray", "isSampler", "isSync", "isQuery", "isTransformFeedback", "getBufferSubData",
      "readPixels", "getError", "checkFramebufferStatus", "finish"]) {
      wrap(name, original => function () { note(name); return original.apply(this, arguments); });
    }
    wrap("bindFramebuffer", original => function (target, framebuffer) {
      if (target === 0x8D40 || target === 0x8CA9) drawState(this).defaultBound = !framebuffer;
      return original.call(this, target, framebuffer);
    });
    for (const name of ["drawArrays", "drawElements", "drawArraysInstanced", "drawElementsInstanced",
      "drawRangeElements", "blitFramebuffer"]) {
      wrap(name, original => function () { onWrite(this); return original.apply(this, arguments); });
    }
  }
})();`;

// Resolves after ms milliseconds of requestAnimationFrame callbacks to the window's figures
// (see the header). The guard must have been installed before the page's scripts.
export function GL_FRAME_GUARD_WINDOW_EXPRESSION(ms) {
  return `new Promise(resolve => {
    const G = globalThis.__gaiusGlFrameGuard;
    if (!G) { resolve({installed: false}); return; }
    const t = [], f = [];
    let s0, by0, d0;
    const loop = stamp => {
      if (!t.length) { s0 = G.syncCalls; by0 = Object.assign({}, G.sync); d0 = G.defaultWrites; }
      t.push(stamp); f.push(G.frames);
      if (stamp - t[0] < ${Number(ms) || 3000}) { requestAnimationFrame(loop); return; }
      const intervals = [];
      for (let i = 1; i < t.length; i++) intervals.push(t[i] - t[i - 1]);
      const sorted = intervals.slice().sort((a, b) => a - b);
      const q = p => sorted.length ? +sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))].toFixed(2) : null;
      const span = t[t.length - 1] - t[0];
      let unique = 0;
      for (let i = 1; i < f.length; i++) if (f[i] > f[i - 1]) unique++;
      const frames = f[f.length - 1] - f[0];
      const byName = {};
      for (const key of Object.keys(G.sync)) {
        const delta = G.sync[key] - (by0[key] || 0);
        if (delta) byName[key] = delta;
      }
      const sync = G.syncCalls - s0;
      resolve({installed: true, ms: +span.toFixed(1), rafIntervals: intervals.length,
        rafFps: span > 0 ? +(intervals.length * 1000 / span).toFixed(1) : 0,
        uniqueFps: span > 0 ? +(unique * 1000 / span).toFixed(1) : 0,
        gameFps: span > 0 ? +(frames * 1000 / span).toFixed(1) : 0,
        gameFrames: frames, defaultWrites: G.defaultWrites - d0,
        p50: q(0.5), p95: q(0.95), p99: q(0.99),
        syncCalls: sync, syncPerFrame: frames > 0 ? +(sync / frames).toFixed(3) : (sync ? Infinity : 0),
        syncByName: byName});
    };
    requestAnimationFrame(loop);
  })`;
}

// Failures of the "synchronous GL calls per frame = 0" assertion for one measured window
// (GL_FRAME_GUARD_WINDOW_EXPRESSION result); empty when it holds. A window without game
// frames fails too: it measured nothing.
export function glFrameGuardFailures(window, label = "") {
  const prefix = label ? `${label}: ` : "";
  if (!window || !window.installed) return [`${prefix}the GL frame guard was not installed`];
  const failures = [];
  if (!(window.gameFrames > 0)) failures.push(`${prefix}no game frame was presented in ${window.ms} ms`);
  if (window.syncCalls > 0) {
    const names = Object.entries(window.syncByName || {}).sort((a, b) => b[1] - a[1])
      .map(([name, count]) => `${name} x${count}`).join(", ");
    failures.push(`${prefix}${window.syncCalls} synchronous GL calls in ${window.gameFrames} frames `
      + `(${window.syncPerFrame}/frame): ${names}`);
  }
  return failures;
}
