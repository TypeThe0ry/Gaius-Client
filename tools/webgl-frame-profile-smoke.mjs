import assert from 'node:assert/strict';
import vm from 'node:vm';
import {installWebGlFrameProbe, installWasmBoundaryProbe} from './webgl-frame-profile.mjs';

// The diagnostic must preserve the exact context receiver, return values,
// exceptions and own/inherited method descriptors, including after teardown.
const calls = [];
const prototype = {
  drawElements(...args) { assert.equal(this, gl); calls.push(['drawElements', ...args]); return 71; },
  bindBuffer(...args) { assert.equal(this, gl); calls.push(['bindBuffer', ...args]); },
  bufferSubData(...args) { calls.push(['bufferSubData', ...args]); },
  getExtension(name) { assert.equal(name, 'EXT_disjoint_timer_query_webgl2'); return null; },
  getError() { throw new Error('unchanged failure'); },
};
const gl = Object.create(prototype);
const own = function () { return 13; };
Object.defineProperty(gl, 'clear', {configurable: true, writable: true, value: own});
const state = {prepare(value) { assert.equal(this, state); return value + 1; }};
const multiDrawPrototype = {
  multiDrawElementsWEBGL(...args) { assert.equal(this, multiDraw); assert.equal(args[1], data); return 29; },
};
const multiDraw = Object.create(multiDrawPrototype);
state.multiDrawExtension = multiDraw;
const originalPrepare = state.prepare;
let now = 0;
const context = vm.createContext({__gaiusWebGL: gl, __gaiusGL: state,
  __gaiusGlFrameGuard: {frames: 100}, performance: {now: () => ++now}, setTimeout});
assert.equal(vm.runInContext(`(${installWebGlFrameProbe})()`, context), true);
assert.equal(vm.runInContext(`(${installWebGlFrameProbe})()`, context), false);
context.__gaiusWebGlFrameProbe.start();
const buffer = {};
gl.bindBuffer(0x8892, buffer); gl.bindBuffer(0x8892, buffer);
const data = new Uint32Array([1, 2, 3, 4]);
gl.bufferSubData(0x8892, 8, data, 1, 2);
assert.equal(gl.drawElements(4, 6, 0x1403, 0), 71);
assert.equal(multiDraw.multiDrawElementsWEBGL(4, data, 0, 0x1403, data, 0, 4), 29);
assert.equal(gl.clear(), 13);
assert.equal(state.prepare(3), 4);
assert.throws(() => gl.getError(), /unchanged failure/);
context.__gaiusGlFrameGuard.frames += 2;
const stopping = context.__gaiusWebGlFrameProbe.stop();
const concurrentStop = context.__gaiusWebGlFrameProbe.stop();
const report = await stopping;
assert.equal(await concurrentStop, report, 'concurrent stop restores only once');
assert.equal(report.frames, 2);
assert.ok(report.wallMs > 0);
assert.equal(await context.__gaiusWebGlFrameProbe.stop(), report, 'stop restores only once');
assert.throws(() => context.__gaiusWebGlFrameProbe.start(), /already been restored/);
assert.equal(report.calls.bindBuffer.count, 2);
assert.equal(report.calls.bindBuffer.repeated, 1);
assert.equal(report.calls.bufferSubData.bytes, 8);
assert.equal(report.calls.getError.count, 1);
assert.equal(report.calls['WEBGL_multi_draw.multiDrawElementsWEBGL'].count, 1);
assert.equal(report.glue.prepare.count, 1);
assert.equal(report.gpuTimerAvailable, false);
assert.equal(gl.drawElements, prototype.drawElements);
assert.equal(Object.hasOwn(gl, 'drawElements'), false);
assert.equal(gl.clear, own);
assert.equal(Object.getOwnPropertyDescriptor(gl, 'clear').enumerable, false);
assert.equal(state.prepare, originalPrepare);
assert.equal(Object.hasOwn(multiDraw, 'multiDrawElementsWEBGL'), false);
assert.equal(multiDraw.multiDrawElementsWEBGL, multiDrawPrototype.multiDrawElementsWEBGL);
assert.equal(calls.length, 4);

// Import linking must preserve promise identity and leave suspension marker
// objects untouched. Existing instantiated modules are never rewritten.
const marker = {}, returned = Promise.resolve(17), namespace = {
  draw(value) { assert.equal(this, namespace); return value + 2; },
  fail() { throw new Error('import failure'); },
  suspend: marker,
};
const boundaryContext = vm.createContext({performance: {now: () => ++now},
  WebAssembly: {instantiate(module, imports, options) {
    assert.equal(module, 'module'); assert.equal(imports.teavmJso, namespace);
    assert.equal(options, 'options'); return returned;
  }}});
assert.equal(vm.runInContext(`(${installWasmBoundaryProbe})()`, boundaryContext), true);
assert.equal(boundaryContext.WebAssembly.instantiate('module', {teavmJso: namespace}, 'options'), returned);
assert.equal(namespace.suspend, marker);
assert.equal(namespace.draw(2), 4);
boundaryContext.__gaiusWasmBoundaryProbe.active = true;
assert.equal(namespace.draw(3), 5);
assert.throws(() => namespace.fail(), /import failure/);
assert.equal(boundaryContext.__gaiusWasmBoundaryProbe.rows[0].count, 1);
assert.equal(boundaryContext.__gaiusWasmBoundaryProbe.rows[1].count, 1);
assert.equal(boundaryContext.__gaiusWasmBoundaryProbe.skipped[0], 'suspend');

{
  let active = null, deleted = 0;
  const guard = {frames: 0};
  const ext = {TIME_ELAPSED_EXT: 0x88BF, GPU_DISJOINT_EXT: 0x8FBB};
  const gpuGl = {
    QUERY_RESULT_AVAILABLE: 0x8867, QUERY_RESULT: 0x8866,
    getExtension() { return ext; },
    createQuery() { return {}; },
    beginQuery(target, query) { assert.equal(target, ext.TIME_ELAPSED_EXT); assert.equal(active, null); active = query; },
    endQuery(target) { assert.equal(target, ext.TIME_ELAPSED_EXT); assert.ok(active); active = null; },
    getQueryParameter(query, parameter) { return parameter === this.QUERY_RESULT_AVAILABLE ? true : 6400; },
    deleteQuery() { deleted++; },
    getParameter(parameter) { assert.equal(parameter, ext.GPU_DISJOINT_EXT); return false; },
    drawElements(present) { if (present) guard.frames++; return 17; },
  };
  const gpuContext = vm.createContext({__gaiusWebGL: gpuGl,
    __gaiusGlFrameGuard: guard, performance: {now: () => ++now}, setTimeout});
  vm.runInContext(`(${installWebGlFrameProbe})()`, gpuContext);
  gpuContext.__gaiusWebGlFrameProbe.start();
  gpuGl.drawElements(true);
  assert.equal(gpuGl.drawElements(false), 17);
  assert.ok(active, 'render span query stays open between draws');
  gpuGl.drawElements(true);
  assert.equal(active, null, 'presentation closes the render span query');
  while (guard.frames < 10) gpuGl.drawElements(true);
  const stopped = await gpuContext.__gaiusWebGlFrameProbe.stop(true);
  assert.equal(stopped.frames, 10);
  assert.equal(deleted, 0, 'query results are collected outside the trace window');
  const collecting = gpuContext.__gaiusWebGlFrameProbe.collectGpu();
  const concurrentCollect = gpuContext.__gaiusWebGlFrameProbe.collectGpu();
  assert.equal(collecting, concurrentCollect, 'concurrent collection shares the query readback');
  const gpu = await collecting;
  assert.equal(gpu.gpuFrameSamples.length, 1);
  assert.equal(gpu.gpuDrawSamples.length, 1);
  assert.equal(gpu.gpuFrameSamples[0].ns, 6400);
  assert.equal(gpu.gpuDrawSamples[0].ns, 6400);
  assert.equal(deleted, 2);
  await gpuContext.__gaiusWebGlFrameProbe.collectGpu();
  assert.equal(deleted, 2, 'query collection is idempotent');
}
console.log('webgl-frame-profile-smoke PASS');
