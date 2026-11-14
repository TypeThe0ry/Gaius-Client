import {writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {captureMainThreadWindow} from './main-thread-profile.mjs';

// Install with CDP before page scripts in a diagnostic-only session. Imported
// functions are fixed at instantiation, so their inactive branch still has a
// cost: do not use this preamble for clean throughput or release comparisons.
export function installWasmBoundaryProbe() {
  'use strict';
  if (globalThis.__gaiusWasmBoundaryProbe) return false;
  const probe = globalThis.__gaiusWasmBoundaryProbe = {active: false, rows: [], skipped: []};
  const instantiate = WebAssembly.instantiate;
  WebAssembly.instantiate = function (module, imports, ...options) {
    if (imports?.teavmJso) {
      for (const [name, original] of Object.entries(imports.teavmJso)) {
        if (typeof original !== 'function') { probe.skipped.push(name); continue; }
        const source = Function.prototype.toString.call(original);
        const row = {name, count: 0, cpuMs: 0, source: source.slice(0, 2048)};
        probe.rows.push(row);
        imports.teavmJso[name] = function (...args) {
          if (!probe.active) return original.apply(this, args);
          row.count++;
          const start = performance.now();
          try { return original.apply(this, args); }
          finally { row.cpuMs += performance.now() - start; }
        };
      }
    }
    return instantiate.call(this, module, imports, ...options);
  };
  return true;
}

// Diagnostic only: install after a clean throughput window. Timers and wrappers
// deliberately stay out of release throughput measurements.
export function installWebGlFrameProbe() {
  'use strict';
  const gl = globalThis.__gaiusWebGL;
  if (!gl || globalThis.__gaiusWebGlFrameProbe) return false;
  const restores = [], calls = {}, glue = {}, queries = [], last = new Map();
  const probe = globalThis.__gaiusWebGlFrameProbe = {calls, glue, active: false};
  const ids = new WeakMap(); let nextId = 1, depth = 0;
  const id = value => {
    if (value && typeof value === 'object') {
      if (!ids.has(value)) ids.set(value, nextId++);
      return '#' + ids.get(value);
    }
    return String(value);
  };
  const ext = gl.getExtension('EXT_disjoint_timer_query_webgl2');
  const timer = ext ? Object.fromEntries(['createQuery', 'beginQuery', 'endQuery',
    'getQueryParameter', 'deleteQuery', 'getParameter'].map(n => [n, gl[n].bind(gl)])) : null;
  const draws = new Set(['drawArrays', 'drawElements', 'drawArraysInstanced',
    'drawElementsInstanced', 'drawRangeElements', 'blitFramebuffer',
    'WEBGL_multi_draw.multiDrawArraysWEBGL', 'WEBGL_multi_draw.multiDrawElementsWEBGL',
    'WEBGL_multi_draw.multiDrawArraysInstancedWEBGL', 'WEBGL_multi_draw.multiDrawElementsInstancedWEBGL']);
  const stateCalls = new Set(['useProgram', 'bindVertexArray', 'activeTexture',
    'bindBuffer', 'bindBufferRange', 'bindBufferBase', 'bindTexture', 'bindSampler',
    'bindFramebuffer', 'enable', 'disable', 'viewport', 'scissor', 'depthMask',
    'colorMask', 'depthFunc', 'blendFuncSeparate', 'blendEquationSeparate']);
  const names = new Set();
  for (let p = gl; p && p !== Object.prototype; p = Object.getPrototypeOf(p)) {
    for (const name of Object.getOwnPropertyNames(p)) {
      const d = Object.getOwnPropertyDescriptor(p, name);
      if (typeof d?.value === 'function' && name !== 'constructor') names.add(name);
    }
  }
  let frame = globalThis.__gaiusGlFrameGuard?.frames || 0, drawIndex = 0, frameQuery = null;
  let frameStart = frame, startedAt = null, stoppedResult = null;
  probe.start = () => {
    if (stoppedResult) throw new Error('Frame probe has already been restored');
    frame = frameStart = globalThis.__gaiusGlFrameGuard?.frames || 0;
    startedAt = performance.now();
    probe.active = true;
  };
  const entries = [...names].map(name => ({object: gl, method: name, name}));
  const multiDraw = globalThis.__gaiusGL?.multiDrawExtension;
  if (multiDraw) for (const method of ['multiDrawArraysWEBGL', 'multiDrawElementsWEBGL',
    'multiDrawArraysInstancedWEBGL', 'multiDrawElementsInstancedWEBGL']) {
    if (typeof multiDraw[method] === 'function') entries.push({object: multiDraw, method, name: 'WEBGL_multi_draw.' + method});
  }
  for (const {object, method, name} of entries) {
    const original = object[method];
    if (typeof original !== 'function') continue;
    const own = Object.getOwnPropertyDescriptor(object, method);
    const row = calls[name] = {count: 0, cpuMs: 0, outerCpuMs: 0, repeated: 0, bytes: 0};
    object[method] = function (...args) {
      if (!probe.active) return original.apply(this, args);
      row.count++;
      if (stateCalls.has(name)) {
        // This is an opportunity histogram, not a correctness cache: intervening
        // indexed binds, VAO changes or texture-unit changes can invalidate it.
        const key = name + ':' + (name.startsWith('bind') || name === 'enable' || name === 'disable' ? id(args[0]) : '');
        const signature = args.map(id).join(',');
        if (last.get(key) === signature) row.repeated++;
        last.set(key, signature);
      }
      if (name === 'bufferSubData' || name === 'bufferData') {
        const data = args[name === 'bufferData' ? 1 : 2];
        const offset = args[3] || 0;
        const length = args[4];
        row.bytes += typeof data === 'number' ? data : data?.byteLength == null ? 0
          : length ? length * (data.BYTES_PER_ELEMENT || 1)
          : data.byteLength - offset * (data.BYTES_PER_ELEMENT || 1);
      }
      let query;
      if (timer && draws.has(name)) {
        const now = globalThis.__gaiusGlFrameGuard?.frames || 0;
        if (now !== frame) { frame = now; drawIndex = 0; }
        const relative = frame - frameStart;
        // Alternate whole render spans and individual draws. A render-span
        // timer includes GPU idle gaps between its GL commands; it is elapsed
        // time on the GPU timeline, not a measurement of device busy time.
        if (!frameQuery && relative % 16 === 1 && drawIndex === 0 && queries.length < 256) {
          const object = timer.createQuery();
          if (object) {
            timer.beginQuery(ext.TIME_ELAPSED_EXT, object);
            frameQuery = {query: object, frame, cpuStart: performance.now()};
          }
        } else if (!frameQuery && relative % 16 === 9 && drawIndex === Math.floor(relative / 16) % 256 && queries.length < 256) {
          query = timer.createQuery();
          if (query) timer.beginQuery(ext.TIME_ELAPSED_EXT, query);
        }
        drawIndex++;
      }
      const outer = depth++ === 0, start = performance.now();
      try { return original.apply(this, args); }
      finally {
        const elapsed = performance.now() - start;
        depth--; row.cpuMs += elapsed;
        if (outer) row.outerCpuMs += elapsed;
        if (query) { timer.endQuery(ext.TIME_ELAPSED_EXT); queries.push({kind: 'draw', name, query}); }
        if (frameQuery && (globalThis.__gaiusGlFrameGuard?.frames || 0) > frameQuery.frame) {
          timer.endQuery(ext.TIME_ELAPSED_EXT);
          queries.push({kind: 'frame-span', name: 'first-draw-to-present', query: frameQuery.query,
            cpuSpanMs: performance.now() - frameQuery.cpuStart});
          frameQuery = null;
        }
      }
    };
    restores.push(() => own ? Object.defineProperty(object, method, own) : delete object[method]);
  }
  const state = globalThis.__gaiusGL;
  if (state) for (const name of Object.keys(state)) {
    if (typeof state[name] !== 'function') continue;
    const original = state[name], row = glue[name] = {count: 0, cpuMs: 0};
    state[name] = function (...args) {
      if (!probe.active) return original.apply(this, args);
      const start = performance.now(); row.count++;
      try { return original.apply(this, args); }
      finally { row.cpuMs += performance.now() - start; }
    };
    restores.push(() => { state[name] = original; });
  }
  probe.stop = async (deferGpu = false) => {
    if (stoppedResult) {
      if (!deferGpu) await probe.collectGpu();
      return stoppedResult;
    }
    probe.active = false;
    const stoppedAt = performance.now();
    const frames = (globalThis.__gaiusGlFrameGuard?.frames || 0) - frameStart;
    for (const restore of restores.reverse()) restore();
    if (frameQuery) {
      timer.endQuery(ext.TIME_ELAPSED_EXT);
      queries.push({kind: 'partial-frame', name: 'discarded-at-stop', query: frameQuery.query});
      frameQuery = null;
    }
    const gpuDrawSamples = [], gpuFrameSamples = [];
    probe.collectGpu = () => probe.gpuCollection || (probe.gpuCollection = (async () => {
      if (!timer) return {gpuDrawSamples, gpuFrameSamples};
      const deadline = performance.now() + 3000;
      while (queries.some(q => !timer.getQueryParameter(q.query, gl.QUERY_RESULT_AVAILABLE)) && performance.now() < deadline)
        await new Promise(r => setTimeout(r, 20));
      const disjoint = timer.getParameter(ext.GPU_DISJOINT_EXT);
      for (const {name, kind, cpuSpanMs, query} of queries) {
        const sample = {name, disjoint, cpuSpanMs, ns: !disjoint && timer.getQueryParameter(query, gl.QUERY_RESULT_AVAILABLE)
          ? timer.getQueryParameter(query, gl.QUERY_RESULT) : null};
        if (kind === 'draw') gpuDrawSamples.push(sample);
        else if (kind === 'frame-span') gpuFrameSamples.push(sample);
        timer.deleteQuery(query);
      }
      probe.gpuCollected = true;
      return {gpuDrawSamples, gpuFrameSamples};
    })());
    stoppedResult = {frames, wallMs: startedAt === null ? 0 : stoppedAt - startedAt,
      calls: Object.fromEntries(Object.entries(calls).filter(([, r]) => r.count)),
      glue: Object.fromEntries(Object.entries(glue).filter(([, r]) => r.count)),
      gpuTimerAvailable: !!ext, gpuDrawSamples, gpuFrameSamples,
      scope: 'Public context entries include compatibility layers. cpuMs fields are inclusive synchronous-call elapsed times and can include scheduler pauses; they are not thread CPU time. GPU frame spans include idle gaps between commands; they are not device busy time.'};
    if (!deferGpu) await probe.collectGpu();
    return stoppedResult;
  };
  return true;
}

export async function profileWorldFrames({cdp, evaluate, output, sleep}) {
  await evaluate(`(${installWebGlFrameProbe.toString()})()`);
  let counters, boundaries, worldBefore, worldAfter, profile, failure;
  const world = `(()=>({loaded:globalThis.__gaiusMinecraftState?.loadedChunkCount,
    screen:globalThis.__gaiusMinecraftState?.screen,overlay:globalThis.__gaiusMinecraftState?.overlay,
    player:globalThis.__gaiusMinecraftState?.player,
    distance:globalThis.__gaiusMinecraftState?.clientDistance,
    pipeline:globalThis.__gaiusChunkPipelineTelemetry,audit:globalThis.__gaiusSectionAudit,
    terrainBatchMode:globalThis.__gaiusGL?.terrainBatchMode,
    terrainPrograms:globalThis.__gaiusGL?.terrainPrograms?.size,
    multiDraw:!!globalThis.__gaiusGL?.multiDrawExtension}))()`;
  const stopWindow = async () => {
    if (counters) return;
    const stopped = await evaluate(`(async()=>{const p=globalThis.__gaiusWasmBoundaryProbe;
      if(p)p.active=false;const counters=await globalThis.__gaiusWebGlFrameProbe.stop(true);
      return {counters,boundaries:p?{skipped:p.skipped,rows:p.rows.filter(r=>r.count)}:null};})()`);
    counters = stopped.counters; boundaries = stopped.boundaries;
    worldAfter = await evaluate(world);
  };
  try { profile = await captureMainThreadWindow({cdp, evaluate, output,
    mode: 'webgl-frame', durationMs: 10000, sleep,
    beforeWindow: async () => {
      worldBefore = await evaluate(world);
      await evaluate(`(()=>{globalThis.__gaiusWebGlFrameProbe.start();
        if(globalThis.__gaiusWasmBoundaryProbe)globalThis.__gaiusWasmBoundaryProbe.active=true;return true;})()`);
    },
    afterWindow: stopWindow});
  } catch (error) {
    failure = error;
  } finally {
    try {
      await stopWindow();
      Object.assign(counters, await evaluate('globalThis.__gaiusWebGlFrameProbe.collectGpu()'));
    } catch (error) { failure ||= error; }
    if (counters) {
      counters.worldBefore = worldBefore; counters.worldAfter = worldAfter;
      if (failure) counters.incomplete = String(failure.stack || failure);
      await writeFile(join(output, 'webgl-frame-calls.json'), JSON.stringify(counters, null, 2));
    }
    if (boundaries) await writeFile(join(output, 'wasm-boundary-calls.json'), JSON.stringify(boundaries, null, 2));
  }
  if (failure) throw failure;
  return {profile: {frames: profile.frames, wallMs: profile.wallMs,
    sampledMsPerFrame: profile.sampledMsPerFrame, taskMsPerFrame: profile.taskMsPerFrame},
    counters, boundaries};
}
