#!/usr/bin/env node
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../web/boot/gaius-boot.js', import.meta.url), 'utf8');
const profile263 = JSON.parse(readFileSync(new URL('../versions/26.3.json', import.meta.url), 'utf8'));
const profile262 = JSON.parse(readFileSync(new URL('../versions/26.2.json', import.meta.url), 'utf8'));
const profile12111 = JSON.parse(readFileSync(new URL('../versions/1.21.11.json', import.meta.url), 'utf8'));
assert.equal(profile263.rd32TraceSupported, true);
assert.notEqual(profile262.rd32TraceSupported, true);
assert.notEqual(profile12111.rd32TraceSupported, true);
const start = source.indexOf('  // RD32 diagnostics are entirely opt-in.');
const end = source.indexOf('\n  // Opt-in bounded timeline', start);
assert.ok(start >= 0 && end > start);
const block = source.slice(start, end);
function make(search, supported = false) {
  let nextId = 0, clock = 0;
  const intervals = new Map(), rafs = new Map();
  class Observer { static supportedEntryTypes = ['gc']; constructor(cb) { this.cb = cb; } observe() {} disconnect() {} }
  const context = vm.createContext({URLSearchParams, Date, Map, WeakSet, Number, Math, JSON,
    params: new URLSearchParams(search), loc: {search}, __gaiusRd32TraceSupported: supported, __gaiusProfileId: 'test', PerformanceObserver: Observer,
    performance: {now: () => clock, timeOrigin: 1000},
    requestAnimationFrame: cb => { const id = ++nextId; rafs.set(id, cb); return id; },
    cancelAnimationFrame: id => rafs.delete(id),
    setInterval: cb => { const id = ++nextId; intervals.set(id, cb); return id; },
    clearInterval: id => intervals.delete(id)});
  context.global = context;
  vm.runInContext(block, context);
  return {context, intervals, rafs, tick(ms) { clock += ms; [...intervals.values()][0]?.(); }};
}

const off = make('');
assert.equal(off.context.__gaiusRd32Trace, undefined);
assert.equal(off.intervals.size + off.rafs.size, 0, 'disabled mode installs no timers or frame hooks');
const unsupported = make('?rd32trace=1');
assert.equal(unsupported.context.__gaiusRd32Trace, undefined, 'non-26.3 profile cannot enable the trace');
assert.equal(make('?rd32snapcheck=1').context.__gaiusRd32Trace, undefined,
  'non-26.3 profile cannot enable snapshot checking');
assert.ok(make('?rd32snapcheck=1', true).context.__gaiusRd32Trace,
  'snapshot checking creates its diagnostic trace without requiring rd32trace');
const on = make('?rd32trace=1', true);
assert.equal(on.context.__gaiusChunkCausalTelemetryEnabled, true);
assert.equal(on.context.__gaiusChunkPipelineTelemetryEveryFrame, true);
assert.equal(on.context.__gaiusRd32Trace.version, 'test');
assert.deepEqual(JSON.parse(JSON.stringify(on.context.__gaiusRd32Trace.snapshotCheck)),
  {captures: 0, mismatches: 0, samples: [], sampleLimit: 20});
on.context.__gaiusRd32Trace.snapshotCheck.recordSnapshotCheck(-2, 4, 9, -1, 16000, 16000);
on.context.__gaiusRd32Trace.snapshotCheck.recordSnapshotCheck(-2, 4, 9, 31, 16000, 16004);
on.context.__gaiusRd32Trace.recordSnapshotTiming(true, 240000);
on.context.__gaiusRd32Trace.recordSnapshotTiming(false, 500000);
assert.equal(on.context.__gaiusRd32Trace.snapshotCheck.captures, 2);
assert.equal(on.context.__gaiusRd32Trace.snapshotCheck.mismatches, 1);
assert.deepEqual(JSON.parse(JSON.stringify(on.context.__gaiusRd32Trace.snapshotCheck.samples[0])),
  {section: {x: -2, y: 4, z: 9}, firstDifference: 31, legacyLength: 16000, fastLength: 16004});
on.context.__gaiusChunkPipelineTelemetry = {compileBacklog: 2, uploadBacklog: 1, totalUploadPassMillis: 5,
  causal: {sections: [{snapshotStartAt: 1, snapshotEndAt: 3, workerSubmitAt: 4, workerResultAt: 8,
  installQueueAt: 9, installCompleteAt: 11, installDurationMs: 0.75, bridgeSubmitMs: 0.5, gpuUploadProxyAt: 12}]}};
on.tick(1000);
const sample = on.context.__gaiusRd32Trace.samples[0];
for (const key of ['stages', 'queues', 'generationMs', 'decodeMs', 'allocations', 'liveSetBytes']) assert.ok(key in sample, `missing ${key}`);
assert.equal(sample.stages.captureMs, 2);
assert.equal(sample.stages.captureFastMs, 0.24);
assert.equal(sample.stages.captureLegacyMs, 0.5);
assert.equal(sample.stages.captureFastCount, 1);
assert.equal(sample.stages.captureLegacyCount, 1);
assert.equal(sample.stages.submitToResultMs, 4);
assert.equal(sample.stages.installQueueToCompleteMs, 2);
assert.equal(sample.stages.installExecutionMs, 0.75);
assert.equal(sample.stages.bridgeSubmitMs, 0.5);
assert.equal(sample.queues.kernel, null);
assert.equal(sample.queues.installReady, null, 'missing install queue remains explicit when no publisher exists');
assert.equal(sample.queues.clientDecodedPackets, null, 'missing network stats must not be reported as an observed zero');
assert.match(sample.unavailable.generationMs, /remote server/);
const json = on.context.__gaiusRd32TraceGet();
assert.equal(json.schema, 'gaius.rd32-trace.v1');
assert.doesNotThrow(() => JSON.stringify(json));
on.context.__gaiusRd32Trace.stop();
assert.equal(on.intervals.size, 0);
assert.equal(on.context.__gaiusChunkPipelineTelemetryEveryFrame, undefined);
console.log('RD32 trace opt-in smoke PASS');
