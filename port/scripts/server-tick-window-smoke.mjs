import assert from 'node:assert/strict';
import {browserServerTickWindow, collectInitialServerTickSamples} from './server-tick-window.mjs';

function sample(at, count = at / 50) {
  return {at, worker: {updatedAt: at, sessionId: 'world-a', measurementId: 'measure-a',
    serverTick: {sampledAtMillis: at + 12345, intervalCount: count,
      totalTickIntervalMillis: count * 50, completedTickCount: count,
      totalTickDurationMillis: count * 5, completedWaitPhaseCount: count,
      totalWaitPhaseDurationMillis: count * 45,
      intervalOver100MillisCount: 0, intervalOver500MillisCount: 0,
      tickWorkOver500MillisCount: 0, waitPhaseOver500MillisCount: 0}}};
}
const steady = [sample(1000), sample(1500), sample(2000)];
assert.equal(browserServerTickWindow(steady).observedTps, 20);
const missingInitial = [{at: 950, worker: {}}, ...steady];
assert.equal(browserServerTickWindow(missingInitial).observedTps, 20);
const resetHeartbeat = {at: 950, worker: {serverTick: {sampledAtMillis: 13295}}};
assert.equal(browserServerTickWindow([resetHeartbeat, ...steady]).observedTps, 20);
const lostCounters = sample(1500);
lostCounters.worker.serverTick = {sampledAtMillis: 13845};
assert.equal(browserServerTickWindow([steady[0], lostCounters, steady[2]]).reason, 'missing-counter');
const staleTail = structuredClone(steady);
staleTail.push({...sample(5000), worker: structuredClone(steady.at(-1).worker)});
assert.equal(browserServerTickWindow(staleTail).reason, 'stale-worker-snapshot');
const changedWorld = structuredClone(steady);
changedWorld[1].worker.sessionId = 'world-b';
assert.equal(browserServerTickWindow(changedWorld).reason, 'worker-window-changed');
const reset = [sample(1000), sample(1500, 1), sample(2000)];
assert.equal(browserServerTickWindow(reset).reason, 'counter-reset');
const missing = [sample(1000), {at: 1500, worker: {}}, sample(2000)];
assert.equal(browserServerTickWindow(missing).reason, 'missing-tick-snapshot-inside-window');
const lateStart = [{at: 0, worker: {}}, sample(1000), sample(2000)];
assert.equal(browserServerTickWindow(lateStart).reason, 'incomplete-browser-window');
const stall = [sample(1000, 20), sample(2000, 20)];
assert.equal(browserServerTickWindow(stall).observedTps, 0);

let clock = 0;
const reads = [
  {at: 0, worker: {}},
  {at: 50, worker: {serverTick: {sampledAtMillis: 12395}}},
  sample(100, 2),
];
const collected = await collectInitialServerTickSamples(
  [{at: -1, worker: {}}],
  {read: async () => reads.shift(), sleep: async (ms) => {clock += ms;},
    now: () => clock, expectedSessionId: 'world-a', expectedMeasurementId: 'measure-a'},
);
assert.equal(collected.available, true);
assert.equal(collected.reason, 'initial-tick-observed');
assert.equal(collected.samples.length, 4, 'initial empty samples must be retained');
assert.equal(collected.waitedMillis, 100);

clock = 0;
const timeout = await collectInitialServerTickSamples([], {
  read: async () => ({at: clock, worker: {}}), sleep: async (ms) => {clock += ms;},
  now: () => clock, expectedSessionId: 'world-a', expectedMeasurementId: 'measure-a',
  intervalMillis: 50, maxWaitMillis: 500,
});
assert.equal(timeout.available, false);
assert.equal(timeout.reason, 'initial-tick-timeout');
assert.equal(timeout.waitedMillis, 500);
assert.equal(timeout.attempts, 11);

clock = 0;
const crossedEpoch = await collectInitialServerTickSamples([], {
  read: async () => ({at: 0, worker: {sessionId: 'world-b', measurementId: 'measure-a',
    serverTick: sample(0).worker.serverTick}}), sleep: async () => {}, now: () => clock,
  expectedSessionId: 'world-a', expectedMeasurementId: 'measure-a',
});
assert.equal(crossedEpoch.available, false);
assert.equal(crossedEpoch.reason, 'worker-window-changed');
console.log('Browser server TPS windows reject stale, missing, reset and partial evidence; initial sampling preserves epoch and timeout semantics.');
