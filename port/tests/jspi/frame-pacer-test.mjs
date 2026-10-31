#!/usr/bin/env node
// Unit test of the frame wait behind HostLoop.awaitAnimationFrame (gaius-jspi.js
// createFramePacer, design D4) on a virtual browser clock: the pacing paths of the JavaScript
// build's per-present yield (hidden 50 ms timer; synchronized present -> next animation frame
// -> MessageChannel task; uncapped present -> rAF in menus, one task in a world; 100 ms
// watchdog; no second wait when the frame's animation frame already ran) and the
// __gaiusFrameTelemetry counters with the sums performance-metrics.mjs checks. Also the host
// calls per frame (at most one requestAnimationFrame, none for an uncapped world frame, no
// timer per frame) and, with the run queue (createScheduler), that a frame never resumes in the
// macrotask that ran the previous frame.
//
//   node port/tests/jspi/frame-pacer-test.mjs
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("../../web/runtime/wasmgc/gaius-jspi.js", import.meta.url), "utf8");

function browser({refreshMillis = 1000 / 60, rafWorks = true} = {}) {
  const events = [];
  let now = 0;
  let order = 0;
  let nextFrame = refreshMillis;
  const rafCallbacks = [];
  const calls = {raf: 0, timers: 0, messages: 0};
  const schedule = (at, callback) => {
    const event = {at, order: order++, callback, cancelled: false};
    events.push(event);
    return event;
  };
  class MessageChannel {
    constructor() {
      this.port1 = {onmessage: null, close() {}};
      this.port2 = {postMessage: (data) => {
        calls.messages++;
        schedule(now + 0.01, () => this.port1.onmessage && this.port1.onmessage({data}));
      }, close() {}};
    }
  }
  const context = {
    console,
    performance: {now: () => now},
    Date: {now: () => now},
    MessageChannel,
    setTimeout: (callback, delay) => {
      calls.timers++;
      return schedule(now + Math.max(0, Number(delay) || 0), callback);
    },
    clearTimeout: (event) => { if (event) event.cancelled = true; },
    requestAnimationFrame: (callback) => {
      calls.raf++;
      rafCallbacks.push(callback);
      return rafCallbacks.length;
    },
    document: {visibilityState: "visible"},
    WebAssembly,
  };
  context.globalThis = context;
  vm.runInNewContext(source, context, {filename: "gaius-jspi.js"});
  // Runs the event loop until `until` (virtual milliseconds) or until `done()` holds.
  const run = (until, done = () => false) => {
    while (!done()) {
      events.sort((a, b) => a.at - b.at || a.order - b.order);
      const next = events.find((event) => !event.cancelled);
      const frameAt = rafWorks && rafCallbacks.length ? nextFrame : Infinity;
      const at = Math.min(next ? next.at : Infinity, frameAt);
      if (at === Infinity || at > until) {
        now = Math.max(now, Math.min(until, at === Infinity ? until : at));
        return;
      }
      now = at;
      if (frameAt === at && (!next || frameAt <= next.at)) {
        nextFrame += refreshMillis;
        for (const callback of rafCallbacks.splice(0)) callback(now);
      } else {
        events.splice(events.indexOf(next), 1);
        next.callback();
      }
    }
  };
  // run() for code with microtasks (the run queue's async drain): each event, then a full
  // microtask checkpoint, as in a browser.
  const runAsync = async (until, done = () => false) => {
    for (;;) {
      await new Promise((resolve) => setImmediate(resolve));
      if (done()) return;
      events.sort((a, b) => a.at - b.at || a.order - b.order);
      const next = events.find((event) => !event.cancelled);
      const frameAt = rafWorks && rafCallbacks.length ? nextFrame : Infinity;
      const at = Math.min(next ? next.at : Infinity, frameAt);
      if (at === Infinity || at > until) {
        now = Math.max(now, Math.min(until, at === Infinity ? until : at));
        return;
      }
      now = at;
      if (frameAt === at && (!next || frameAt <= next.at)) {
        nextFrame += refreshMillis;
        for (const callback of rafCallbacks.splice(0)) callback(now);
      } else {
        events.splice(events.indexOf(next), 1);
        next.callback();
      }
    }
  };
  return {context, run, runAsync, calls, now: () => now, advance: (millis) => { now += millis; },
    frame: () => {
      while (nextFrame <= now) nextFrame += refreshMillis;
    }};
}

function telemetryOf(b) {
  b.context.__gaiusFrameTelemetry = {enabled: true};
  return b.context.__gaiusFrameTelemetry;
}

function checkSums(t, label) {
  assert.equal(t.yieldRequestCount, (t.uncappedYieldCount || 0) + (t.vsyncYieldCount || 0), label + ": requests = uncapped + vsync");
  assert.equal(t.yieldRequestCount, (t.visibleYieldCount || 0) + (t.hiddenYieldCount || 0), label + ": requests = visible + hidden");
  assert.equal(t.yieldCompletionCount, (t.messageChannelYieldCount || 0) + (t.schedulerYieldCount || 0)
    + (t.timerYieldCount || 0), label + ": completions = message + scheduler + timer");
  assert.equal(t.pendingYieldCount, 0, label + ": nothing pending");
}

// Waits `frames` times in a row, as the Minecraft.run loop does, and returns the resume times.
function loop(b, pacer, interval, frames, work = 0.5) {
  const resumes = [];
  let waiting = false;
  const step = () => {
    waiting = true;
    pacer.wait(interval, () => {
      waiting = false;
      resumes.push(b.now());
    });
  };
  for (let i = 0; i < frames; i++) {
    step();
    b.run(b.now() + 1000, () => !waiting);
    assert.ok(!waiting, `frame ${i} never resumed`);
    b.run(b.now() + work); // the frame's own work
  }
  return resumes;
}

const intervals = (times) => times.slice(1).map((t, i) => t - times[i]);
const mean = (values) => values.reduce((a, b) => a + b, 0) / values.length;

{
  // Synchronized to the display: one frame per refresh, resumed after the rAF by a task.
  const b = browser();
  const t = telemetryOf(b);
  const pacer = b.context.GaiusJspi.createFramePacer();
  const resumes = loop(b, pacer, 1, 60);
  assert.ok(Math.abs(mean(intervals(resumes)) - 1000 / 60) < 0.5, "vsync frames follow the refresh rate");
  assert.equal(t.vsyncYieldCount, 60);
  assert.equal(t.presentToRafCount, 60);
  assert.equal(t.messageChannelYieldCount, 60);
  assert.equal(t.swapInterval, 1);
  checkSums(t, "vsync");
}
{
  // Uncapped in a menu (no level): paced by the animation frame too (the vanilla menu limit).
  const b = browser();
  const t = telemetryOf(b);
  const pacer = b.context.GaiusJspi.createFramePacer();
  const resumes = loop(b, pacer, 0, 60);
  assert.ok(Math.abs(mean(intervals(resumes)) - 1000 / 60) < 0.5, "menu frames follow the refresh rate");
  assert.equal(t.uncappedYieldCount, 60);
  assert.equal(t.uncappedRafWaitCount, 60);
  checkSums(t, "uncapped menu");
}
{
  // Uncapped in a world: one macrotask per frame, not held to the refresh rate.
  const b = browser();
  const t = telemetryOf(b);
  b.context.__gaiusMinecraftState = {level: "overworld"};
  const pacer = b.context.GaiusJspi.createFramePacer();
  const resumes = loop(b, pacer, 0, 100, 2);
  assert.ok(mean(intervals(resumes)) < 3, "world frames are not held to the refresh rate");
  assert.equal(t.uncappedRafWaitCount || 0, 0);
  assert.equal(t.messageChannelYieldCount, 100);
  checkSums(t, "uncapped world");
}
{
  // A WebGPU surface gate is allowed to outlive the generic 100 ms watchdog. It must resume only
  // after queue completion, otherwise another uncapped frame would recreate the FIFO backlog.
  const b = browser();
  const t = telemetryOf(b);
  b.context.__gaiusMinecraftState = {level: "overworld"};
  let release;
  b.context.GaiusWebGpu = {
    waitForSurfaceBackpressure() {
      return new Promise((resolve) => { release = resolve; });
    }
  };
  const pacer = b.context.GaiusJspi.createFramePacer();
  let resumed = false;
  pacer.wait(0, () => { resumed = true; });
  b.run(100);
  assert.equal(resumed, false, "surface backpressure is not released by the generic watchdog");
  assert.equal(t.watchdogYieldCount || 0, 0, "GPU gate does not count as a watchdog yield");
  release();
  await b.runAsync(500, () => resumed);
  assert.equal(resumed, true, "surface gate resumes after queue completion");
}
{
  // Hidden page: a 50 ms timer, whatever the interval.
  const b = browser();
  const t = telemetryOf(b);
  b.context.document.visibilityState = "hidden";
  const pacer = b.context.GaiusJspi.createFramePacer();
  const resumes = loop(b, pacer, 1, 5, 0);
  assert.ok(intervals(resumes).every((d) => Math.abs(d - 50) < 0.5), "hidden frames wait 50 ms");
  assert.equal(t.hiddenYieldCount, 5);
  assert.equal(t.timerYieldCount, 5);
  checkSums(t, "hidden");
}
{
  // A suspended rAF (background tab) cannot stall the thread: the watchdog resumes it at 100 ms.
  const b = browser({rafWorks: false});
  const t = telemetryOf(b);
  const pacer = b.context.GaiusJspi.createFramePacer();
  const resumes = loop(b, pacer, 1, 3, 0);
  assert.ok(intervals(resumes).every((d) => Math.abs(d - 100) < 0.5), "the watchdog resumes after 100 ms");
  assert.equal(t.watchdogYieldCount, 3);
  checkSums(t, "watchdog");
}
{
  // A frame whose own work (vanilla's frame limiter sleeping to the 60 fps menu deadline) ends
  // just after the next animation frame does not wait for one more: one frame per refresh.
  for (const interval of [1, 0]) {
    const b = browser();
    const t = telemetryOf(b);
    const pacer = b.context.GaiusJspi.createFramePacer();
    const resumes = loop(b, pacer, interval, 60, 1000 / 60 + 0.3);
    const gaps = intervals(resumes);
    assert.ok(Math.abs(mean(gaps) - (1000 / 60 + 0.31)) < 0.5,
      `interval ${interval}: late frames run at the refresh rate (mean ${mean(gaps).toFixed(2)} ms)`);
    assert.ok(gaps.every((gap) => gap < 25), `interval ${interval}: a late frame waited for one more refresh`);
    assert.ok(t.rafAlreadyRanCount >= 58, `interval ${interval}: rafAlreadyRanCount ${t.rafAlreadyRanCount}`);
    checkSums(t, `late frames ${interval}`);
  }
  // Frames that finish early still wait for the animation frame: never two per refresh.
  const b = browser();
  const pacer = b.context.GaiusJspi.createFramePacer();
  const resumes = loop(b, pacer, 1, 30, 4);
  assert.ok(intervals(resumes).every((gap) => gap > 16), "an early frame did not wait for the refresh");
}
{
  // Without telemetry nothing is recorded, and the pacing is the same.
  const b = browser();
  const pacer = b.context.GaiusJspi.createFramePacer();
  const resumes = loop(b, pacer, 1, 10);
  assert.ok(Math.abs(mean(intervals(resumes)) - 1000 / 60) < 0.5);
  assert.equal(b.context.__gaiusFrameTelemetry, undefined);
}
{
  // Host calls per frame: an uncapped world frame makes no requestAnimationFrame call and one
  // task; a paced frame one requestAnimationFrame (the watch and the wait share it); the
  // watchdog is one timer for many frames, not one per frame.
  for (const [label, interval, world, rafPerFrame] of [["world", 0, true, 0], ["vsync", 1, false, 1],
    ["menu", 0, false, 1]]) {
    const b = browser();
    if (world) b.context.__gaiusMinecraftState = {level: "overworld"};
    const pacer = b.context.GaiusJspi.createFramePacer();
    loop(b, pacer, interval, 5, 2);
    const before = {...b.calls};
    const frames = 120;
    loop(b, pacer, interval, frames, 2);
    const raf = (b.calls.raf - before.raf) / frames;
    const timers = b.calls.timers - before.timers;
    assert.ok(Math.abs(raf - rafPerFrame) < 0.02, `${label}: ${raf} requestAnimationFrame calls per frame`);
    assert.equal(b.calls.messages - before.messages, frames, `${label}: one task per frame`);
    const elapsed = b.now();
    assert.ok(timers <= Math.ceil(elapsed / 100) + 1, `${label}: ${timers} timers for ${frames} frames`);
  }
}

// The run queue path: the frame resume is a turn of the run queue (no channel of the pacer's
// own), counted as a MessageChannel resume, and never runs in the drain that ran the previous
// frame, even when that drain has time left; work queued during a frame (the render pump) runs
// in between.
async function schedulerLoop({world, interval, frames, work, pumpEveryFrame}) {
  const b = browser();
  const t = telemetryOf(b);
  if (world) b.context.__gaiusMinecraftState = {level: "overworld"};
  const scheduler = b.context.GaiusJspi.createScheduler();
  const pacer = b.context.GaiusJspi.createFramePacer(scheduler);
  const log = [];
  let done = false;
  // The main thread's activation: runs the frame, then suspends in the frame wait.
  const frame = () => {
    log.push({kind: "frame", slice: scheduler.stats.slices, at: b.now()});
    b.advance(work);
    if (pumpEveryFrame) {
      scheduler.post(() => {
        log.push({kind: "pump", slice: scheduler.stats.slices, at: b.now()});
        b.advance(0.5);
        return false;
      });
    }
    scheduler.turn.running = false;
    if (log.filter((entry) => entry.kind === "frame").length >= frames) {
      done = true;
      return;
    }
    pacer.wait(interval, () => {
      scheduler.freshSlice();
      Promise.resolve().then(frame);
    });
  };
  scheduler.post(() => {
    Promise.resolve().then(frame);
    return true;
  });
  await b.runAsync(60_000, () => done);
  assert.ok(done, "the frame loop did not finish");
  return {b, t, log, scheduler};
}
for (const work of [0.5, 3]) {
  const {b, t, log, scheduler} = await schedulerLoop({world: true, interval: 0, frames: 60, work,
    pumpEveryFrame: true});
  const frameLog = log.filter((entry) => entry.kind === "frame");
  for (let index = 1; index < frameLog.length; index++) {
    assert.ok(frameLog[index].slice > frameLog[index - 1].slice,
      `work ${work}: frame ${index} resumed in the drain that ran frame ${index - 1}`);
  }
  for (let index = 1; index < frameLog.length; index++) {
    assert.ok(log.some((entry) => entry.kind === "pump" && entry.at >= frameLog[index - 1].at
      && entry.at <= frameLog[index].at), `work ${work}: the pump did not run between frames ${index - 1} and ${index}`);
  }
  for (const frame of frameLog.slice(1, -1)) {
    assert.ok(!log.slice(log.indexOf(frame) + 1)
      .some((entry) => entry.kind === "pump" && entry.slice === frame.slice),
      `work ${work}: queued pump work delayed the commit of frame in drain ${frame.slice}`);
  }
  assert.equal(t.messageChannelYieldCount, 59, `work ${work}: frame resumes counted as MessageChannel resumes`);
  assert.equal(t.schedulerYieldCount || 0, 0);
  assert.equal(t.timerYieldCount || 0, 0);
  assert.equal(b.calls.raf, 0, `work ${work}: an uncapped world frame requested an animation frame`);
  checkSums(t, `run queue, work ${work}`);
  // One MessageChannel for the host: the pacer opened none of its own.
  assert.ok(b.calls.messages <= scheduler.stats.slices + 1,
    `work ${work}: ${b.calls.messages} messages for ${scheduler.stats.slices} drains`);
}
{
  // Paced by the animation frame through the run queue: one frame per refresh.
  const {t, log} = await schedulerLoop({world: false, interval: 1, frames: 30, work: 2, pumpEveryFrame: false});
  const times = log.filter((entry) => entry.kind === "frame").map((entry) => entry.at);
  assert.ok(Math.abs(mean(intervals(times)) - 1000 / 60) < 0.5, "run-queue vsync frames follow the refresh rate");
  assert.equal(t.presentToRafCount, 29);
  checkSums(t, "run queue, vsync");
}

{
  const b = browser();
  const scheduler = b.context.GaiusJspi.createScheduler();
  const order = [];
  scheduler.post(() => {
    order.push("long-start");
    let hops = 0;
    const wait = () => {
      if (++hops < 100) return Promise.resolve().then(wait);
      scheduler.turn.running = false;
    };
    Promise.resolve().then(wait);
    return true;
  });
  scheduler.post(() => { order.push("next"); return false; });
  await b.runAsync(1000, () => order.includes("next"));
  assert.deepEqual(order, ["long-start", "next"], "long JSPI activation stays ahead of FIFO work");
  assert.ok(scheduler.stats.hops >= 100, "scheduler waited for explicit suspension");
}

// A long JSPI activation is counted always but logged once per session, or every time with a
// debug switch (frame telemetry enabled, __gaiusDebug, or the gaiusDebug / diag query).
async function slowActivations(setup, count) {
  const b = browser();
  const warnings = [];
  b.context.console = {warn: (...args) => warnings.push(args), error: console.error, log() {}, info() {}};
  setup(b.context);
  const scheduler = b.context.GaiusJspi.createScheduler();
  let finished = 0;
  for (let index = 0; index < count; index++) {
    scheduler.post(() => {
      let hops = 0;
      const wait = () => {
        if (++hops < 1100) return Promise.resolve().then(wait);
        scheduler.turn.running = false;
        finished++;
      };
      Promise.resolve().then(wait);
      return true;
    });
  }
  await b.runAsync(1000, () => finished === count);
  assert.equal(finished, count, "slow activations finished");
  return {warnings, scheduler, context: b.context};
}

{
  const quiet = await slowActivations(() => {}, 3);
  assert.equal(quiet.warnings.length, 1, "slow activation is logged once per session without a switch");
  assert.match(String(quiet.warnings[0][0]), /taking unusually long to suspend/);
  assert.equal(quiet.scheduler.stats.slowActivations, 3, "every slow activation is counted");

  const telemetry = {enabled: true};
  const loud = await slowActivations((context) => { context.__gaiusFrameTelemetry = telemetry; }, 3);
  assert.equal(loud.warnings.length, 3, "telemetry switch logs every slow activation");
  assert.equal(telemetry.jspiSlowActivationCount, 3, "telemetry carries the slow activation count");

  const debug = await slowActivations((context) => { context.__gaiusDebug = true; }, 2);
  assert.equal(debug.warnings.length, 2, "__gaiusDebug logs every slow activation");

  const query = await slowActivations((context) => {
    context.URLSearchParams = URLSearchParams;
    context.location = {search: "?gaiusDebug=1"};
  }, 2);
  assert.equal(query.warnings.length, 2, "the gaiusDebug query logs every slow activation");
}

console.log("frame-pacer-test: OK (vsync, uncapped menu, uncapped world, hidden, watchdog, late frames, no telemetry,"
  + " host calls per frame, run-queue turns)");
