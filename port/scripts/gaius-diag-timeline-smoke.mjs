#!/usr/bin/env node
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../web/boot/gaius-boot.js', import.meta.url), 'utf8');
const start = source.indexOf('  function installLightweightDiagnosticTimeline() {');
const end = source.indexOf('\n  installLightweightDiagnosticTimeline();', start);
assert.ok(start >= 0 && end > start);
const installer = source.slice(start, end);
function make(search) {
  const rafs = new Map(), timers = new Map(); let id = 0, now = 0, observers = 0;
  class Observer { constructor(cb) { this.cb = cb; observers++; } observe() {} disconnect() {} }
  const context = vm.createContext({URLSearchParams, params: new URLSearchParams(search), perf: {now: () => now},
    PerformanceObserver: Observer, performance: {now: () => now},
    requestAnimationFrame: cb => { const n = ++id; rafs.set(n, cb); return n; },
    cancelAnimationFrame: n => rafs.delete(n),
    setInterval: cb => { const n = ++id; timers.set(n, cb); return n; }, clearInterval: n => timers.delete(n),
    __gaiusChunkPipelineTelemetry: {compileBacklog: 2}, __gaiusWorkerMessageTelemetry: {rttMs: 4},
  });
  context.global = context;
  vm.runInContext(`${installer};installLightweightDiagnosticTimeline()`, context);
  return {context, rafs, timers, get observers() { return observers; }, frame(t) {
    now = t; const [n, cb] = [...rafs.entries()][0]; rafs.delete(n); cb(t);
  }};
}
const off = make('');
assert.equal(off.context.__gaiusDiagTimeline, undefined);
assert.equal(off.rafs.size + off.timers.size + off.observers, 0);
const on = make('?gaiusDiag=1');
assert.equal(on.context.__gaiusDiagTimeline.enabled, true);
on.frame(0); on.frame(20); on.frame(80); on.frame(190);
for (let i = 0; i < 600; i++) on.context.__gaiusDiagTimeline.push('fixture', {i});
const summary = on.context.__gaiusDiagTimeline.summary();
assert.equal(summary.frames, 3);
assert.equal(summary.over16, 3);
assert.equal(summary.over50, 2);
assert.equal(summary.over100, 1);
assert.equal(summary.maxFrameMs, 110);
assert.equal(summary.events.length, 512);
assert.equal(summary.events.at(-1).detail.i, 599);
on.context.__gaiusDiagTimeline.stop();
assert.equal(on.timers.size, 0);
console.log('gaius diagnostic timeline smoke PASS');
