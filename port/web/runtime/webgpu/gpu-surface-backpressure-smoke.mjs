import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(process.env.GAIUS_WEBGPU_GLUE || new URL("gpu-glue.js", import.meta.url), "utf8");

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((accept, decline) => {
    resolve = accept;
    reject = decline;
  });
  return {promise, resolve, reject};
}

// Most cases below exercise the drain mode (the original behaviour); rawMode keeps the shipped default.
function fixture(globals = {}, rawMode = false) {
  const context = vm.createContext({console, performance, Promise, Uint8Array, Date, Float64Array, ...globals});
  const testSource = source.replace(
    "  root.GaiusWebGpu = G;\n",
    "  G.__testSurfaceSubmit=function(){noteSurfaceSubmission();};\n" +
      "  G.__testReleaseSurfaceGate=function(){releaseSurfaceGate();};\n" +
      "  G.__testInvalidate=function(){invalidateRegionEpochs();};\n" +
      "  root.GaiusWebGpu = G;\n"
  );
  vm.runInContext(testSource, context, {filename: "gpu-glue.js"});
  const gpu = context.GaiusWebGpu;
  if (!rawMode) { gpu.surfaceGateMode = "drain"; gpu.surfaceBackpressureCap = 3; }
  const completions = [];
  let queueSubmits = 0;
  gpu.status = 2;
  gpu.device = {};
  gpu.queue = {
    submit() { queueSubmits++; },
    onSubmittedWorkDone() {
      const completion = deferred();
      completions.push(completion);
      return completion.promise;
    }
  };
  return {gpu, completions, get queueSubmits() { return queueSubmits; }};
}

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
}

{
  const {gpu, completions} = fixture();
  assert.equal(gpu.waitForSurfaceBackpressure(), null, "below the cap must not wait");
  gpu.__testSurfaceSubmit();
  gpu.__testSurfaceSubmit();
  gpu.__testSurfaceSubmit();
  assert.equal(gpu.stats.surfaceSubmitPending, 3, "surface submissions are counted");
  const gate = gpu.waitForSurfaceBackpressure();
  assert.ok(gate instanceof Promise, "the fourth frame must wait behind the cap");
  completions.at(-1).resolve();
  await gate;
  await flush();
  assert.equal(gpu.stats.surfaceSubmitPending, 0, "completed gate retires the queued frames");
  assert.equal(gpu.waitForSurfaceBackpressure(), null, "retired work opens the next frame");
}

{
  const {gpu, completions} = fixture();
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  const gate = gpu.waitForSurfaceBackpressure();
  gpu.__testSurfaceSubmit();
  completions.at(-1).resolve();
  await gate;
  await flush();
  assert.equal(gpu.stats.surfaceSubmitPending, 1, "completion retires only work covered by its queue promise");
  assert.equal(gpu.waitForSurfaceBackpressure(), null, "later submissions remain pending without blocking early");
}

{
  const {gpu, completions} = fixture();
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  const gate = gpu.waitForSurfaceBackpressure();
  completions.at(-1).reject(new Error("device lost"));
  await gate;
  await flush();
  assert.equal(gpu.waitForSurfaceBackpressure(), null, "a rejected completion must release the gate");
}

{
  const {gpu} = fixture();
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  const gate = gpu.waitForSurfaceBackpressure();
  gpu.__testReleaseSurfaceGate();
  await gate;
  assert.equal(gpu.stats.surfaceBackpressureWaits, 1, "teardown release does not create a second wait");
}

{
  const {gpu, completions} = fixture();
  gpu.submit();
  assert.equal(gpu.stats.surfaceSubmitPending, 0, "a non-surface submit is not counted");
  assert.equal(completions.length, 0, "a non-surface submit does not arm a surface completion");
}


// Gate telemetry: every wait is timed from its creation to its release.
{
  const {gpu, completions} = fixture();
  assert.equal(gpu.surfaceGateSnapshot().count, 0, "no wait yet");
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  const gate = gpu.waitForSurfaceBackpressure();
  await new Promise(resolve => setTimeout(resolve, 12));
  completions.at(-1).resolve();
  await gate;
  await flush();
  const snapshot = gpu.surfaceGateSnapshot();
  assert.equal(snapshot.count, 1);
  assert.equal(snapshot.mode, "drain");
  assert.equal(snapshot.cap, 3);
  assert.equal(snapshot.rows.length, 1);
  assert.ok(snapshot.rows[0][1] >= 10, "the wait covers the time the gate was held: " + snapshot.rows[0][1]);
  assert.ok(snapshot.maxMs >= 10 && snapshot.totalMs >= snapshot.maxMs);
  assert.equal(gpu.stats.surfaceGateWaitsOver100, 0);
  assert.equal(gpu.surfaceGateReset(), true);
  const cleared = gpu.surfaceGateSnapshot();
  assert.equal(cleared.count, 0);
  assert.equal(cleared.totalMs, 0);
  assert.equal(cleared.rows.length, 0);
}

// The ring keeps the newest 4096 waits oldest first and counts the rest as dropped.
{
  const {gpu, completions} = fixture();
  for (let round = 0; round < 4120; round++) {
    for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
    const gate = gpu.waitForSurfaceBackpressure();
    completions.at(-1).resolve();
    await gate;
    await flush();
  }
  const snapshot = gpu.surfaceGateSnapshot();
  assert.equal(snapshot.count, 4120);
  assert.equal(snapshot.dropped, 24);
  assert.equal(snapshot.rows.length, 4096);
  for (let i = 1; i < snapshot.rows.length; i++) {
    assert.ok(snapshot.rows[i][0] >= snapshot.rows[i - 1][0], "rows are oldest first");
  }
}

// The cap is read when a frame asks (default 3, 1..16, invalid values fall back to 3).
{
  const {gpu} = fixture();
  gpu.surfaceBackpressureCap = 5;
  for (let i = 0; i < 4; i++) gpu.__testSurfaceSubmit();
  assert.equal(gpu.waitForSurfaceBackpressure(), null, "four of five frames in flight do not wait");
  gpu.__testSurfaceSubmit();
  assert.ok(gpu.waitForSurfaceBackpressure() instanceof Promise, "the fifth frame waits");
  gpu.__testReleaseSurfaceGate();
  gpu.surfaceBackpressureCap = 0;
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  assert.ok(gpu.waitForSurfaceBackpressure() instanceof Promise, "an invalid cap keeps the default of 3");
  assert.equal(gpu.surfaceGateSnapshot().cap, 3);
  gpu.surfaceBackpressureCap = 99;
  assert.equal(gpu.surfaceGateSnapshot().cap, 16, "the cap is clamped to 16");
}
{
  const {gpu} = fixture({__gaiusSwitches: Object.freeze({gaiusSurfaceGateCap: 2})});
  gpu.__testSurfaceSubmit();
  assert.equal(gpu.waitForSurfaceBackpressure(), null);
  gpu.__testSurfaceSubmit();
  assert.ok(gpu.waitForSurfaceBackpressure() instanceof Promise, "the launcher switch lowers the cap to 2");
}

// Rolling mode: each surface submission asks for its own completion and the gate opens as soon
// as fewer than cap frames are pending, without waiting for the queue to run empty.
{
  const {gpu, completions} = fixture();
  gpu.surfaceGateMode = "rolling";
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  assert.equal(completions.length, 3, "one completion per surface submission");
  const gate = gpu.waitForSurfaceBackpressure();
  assert.ok(gate instanceof Promise);
  assert.equal(completions.length, 3, "the gate asks for nothing more");
  assert.equal(gpu.waitForSurfaceBackpressure(), gate, "waiting frames share the gate");
  let opened = false;
  gate.then(() => { opened = true; });
  completions[0].resolve();
  await flush();
  await flush();
  assert.equal(opened, true, "the oldest completion opens the gate");
  assert.equal(gpu.stats.surfaceSubmitPending, 2, "only the retired frame leaves the window");
  assert.equal(gpu.waitForSurfaceBackpressure(), null, "two frames in flight do not wait");
  gpu.__testSurfaceSubmit();
  assert.ok(gpu.waitForSurfaceBackpressure() instanceof Promise, "three in flight wait again");
  completions[1].resolve();
  completions[2].resolve();
  await flush();
  await flush();
  assert.equal(gpu.stats.surfaceSubmitPending, 1);
  assert.equal(gpu.surfaceGateSnapshot().mode, "rolling");
  assert.equal(gpu.surfaceGateSnapshot().count, 2);
  assert.equal(gpu.stats.surfaceBackpressureWaits, 2);
}
{
  // A later completion never un-retires an earlier one, and out-of-order settlement is safe.
  const {gpu, completions} = fixture();
  gpu.surfaceGateMode = "rolling";
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  const gate = gpu.waitForSurfaceBackpressure();
  completions[2].resolve();
  await gate;
  await flush();
  assert.equal(gpu.stats.surfaceSubmitPending, 0, "the newest completion retires everything before it");
  completions[0].resolve();
  completions[1].resolve();
  await flush();
  assert.equal(gpu.stats.surfaceSubmitPending, 0);
}
{
  // A rejected completion (device loss) retires the frame; a released gate ignores later settlement.
  const {gpu, completions} = fixture();
  gpu.surfaceGateMode = "rolling";
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  const gate = gpu.waitForSurfaceBackpressure();
  completions[0].reject(new Error("device lost"));
  await gate;
  await flush();
  assert.equal(gpu.waitForSurfaceBackpressure(), null, "a rejected completion opens the gate");
}
{
  // Teardown and recovery release the gate; completions of the old device generation are ignored.
  const {gpu, completions} = fixture();
  gpu.surfaceGateMode = "rolling";
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  const gate = gpu.waitForSurfaceBackpressure();
  gpu.__testInvalidate();
  gpu.__testReleaseSurfaceGate();
  await gate;
  assert.equal(gpu.stats.surfaceSubmitPending, 0, "the release retires every pending frame");
  gpu.__testSurfaceSubmit();
  completions[0].resolve();
  completions[1].resolve();
  completions[2].resolve();
  await flush();
  assert.equal(gpu.stats.surfaceSubmitPending, 1, "stale completions do not retire a newer frame");
}
{
  // Switching the mode on with frames already pending covers them with one request at the gate.
  const {gpu, completions} = fixture();
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  assert.equal(completions.length, 0, "drain mode asks for nothing at submit time");
  gpu.surfaceGateMode = "rolling";
  const gate = gpu.waitForSurfaceBackpressure();
  assert.equal(completions.length, 1, "one request for the frames submitted before the switch");
  completions[0].resolve();
  await gate;
  await flush();
  assert.equal(gpu.stats.surfaceSubmitPending, 0);
}
{
  // Rolling is the shipped default; the launcher switch and the global select drain instead.
  for (const globals of [{}, {__gaiusSwitches: Object.freeze({})}]) {
    const {gpu, completions} = fixture(globals, true);
    gpu.__testSurfaceSubmit();
    assert.equal(completions.length, 1, "rolling is the default");
    assert.equal(gpu.surfaceGateSnapshot().mode, "rolling");
  }
  for (const globals of [{__gaiusSwitches: Object.freeze({gaiusSurfaceGate: "drain"})}, {__gaiusSurfaceGate: "drain"}]) {
    const {gpu, completions} = fixture(globals, true);
    gpu.__testSurfaceSubmit();
    assert.equal(completions.length, 0, "the drain switch opts out");
    assert.equal(gpu.surfaceGateSnapshot().mode, "drain");
  }
  // The launcher switch and the global both select rolling; "drain" overrides them.
  for (const globals of [{__gaiusSwitches: Object.freeze({gaiusSurfaceGate: "rolling"})}, {__gaiusSurfaceGate: "rolling"}]) {
    const {gpu, completions} = fixture(globals, true);
    gpu.__testSurfaceSubmit();
    assert.equal(completions.length, 1);
    assert.equal(gpu.surfaceGateSnapshot().mode, "rolling");
  }
  const {gpu, completions} = fixture({__gaiusSurfaceGate: "rolling"}, true);
  gpu.surfaceGateMode = "drain";
  gpu.__testSurfaceSubmit();
  assert.equal(completions.length, 0);
}
{
  // A queue without onSubmittedWorkDone never leaves the gate closed in either mode.
  for (const mode of ["drain", "rolling"]) {
    const {gpu} = fixture();
    gpu.surfaceGateMode = mode;
    gpu.queue = {submit() {}};
    for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
    const gate = gpu.waitForSurfaceBackpressure();
    await gate;
    await flush();
    assert.equal(gpu.waitForSurfaceBackpressure(), null, mode + ": no completion API opens the gate");
  }
}

// Disable during world-load window: title -> entry -> first terrain, without GPU progress.
for (const mode of ["rolling", "drain"]) {
  for (const moment of ["world entry", "title", "first terrain"]) {
    const {gpu, completions} = fixture();
    gpu.surfaceGateMode = mode;
    if (moment === "title") gpu.surfaceBackpressureEnabled = false;
    for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
    const gate = gpu.waitForSurfaceBackpressure();
    let resumed = !gate;
    if (gate) gate.then(() => { resumed = true; });
    gpu.surfaceBackpressureEnabled = false;
    await flush();
    assert.equal(resumed, true, mode + ": disable during world-load window at " + moment);
    assert.equal(gpu.stats.surfaceSubmitPending, 3, "config wake must not invent retirement");
    gpu.surfaceBackpressureEnabled = true;
    const next = gpu.waitForSurfaceBackpressure();
    assert.ok(next instanceof Promise, "re-enable preserves bounded run-ahead");
    gpu.surfaceBackpressureCap = 4;
    await flush();
    let capResumed = false;
    next.then(() => { capResumed = true; });
    await flush();
    assert.equal(capResumed, true, "cap change wakes an existing load waiter");
    await next;
    gpu.__testSurfaceSubmit();
    const newer = gpu.waitForSurfaceBackpressure();
    let newerOpened = false;
    newer.then(() => { newerOpened = true; });
    completions[0].resolve();
    await flush(); await flush();
    assert.equal(gpu.stats.surfaceSubmitPending, mode === "drain" ? 1 : 3);
    // Both modes may open once genuine retirement brings pending below the new cap.
    assert.equal(newerOpened, true);
    gpu.__testReleaseSurfaceGate();
  }
}
{
  const {gpu} = fixture({}, true);
  assert.equal(gpu.surfaceBackpressureCap, "auto", "production uses adaptive mode during load");
  gpu.surfaceBackpressureCap = 1;
  gpu.__testSurfaceSubmit();
  const gate = gpu.waitForSurfaceBackpressure();
  let resumed = false;
  gate.then(() => { resumed = true; });
  gpu.surfaceBackpressureCap = "auto";
  await flush();
  assert.equal(resumed, true, "auto takes effect on an already parked load frame");
  assert.equal(gpu.stats.surfaceSubmitPending, 1);
}

{
  const {gpu, completions} = fixture();
  for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit();
  const old = gpu.waitForSurfaceBackpressure();
  gpu.surfaceBackpressureEnabled = false;
  await old;
  gpu.__testSurfaceSubmit();
  gpu.__testSurfaceSubmit();
  gpu.surfaceBackpressureEnabled = true;
  gpu.surfaceBackpressureCap = 1;
  const current = gpu.waitForSurfaceBackpressure();
  let opened = false;
  current.then(() => { opened = true; });
  completions[0].resolve();
  await flush(); await flush();
  assert.equal(gpu.stats.surfaceSubmitPending, 2, "old drain completion retires only its snapshot");
  assert.equal(opened, false, "old drain must not release a newer blocked gate");
  completions[1].resolve();
  await current;
}

// A newly observed slower display expands the time budget and wakes a parked gate.
{
  let time = 0, raf;
  const {gpu} = fixture({performance: {now: () => time},
    requestAnimationFrame: callback => { raf = callback; }}, true);
  gpu.__testSurfaceSubmit();
  time = 10; raf(time);
  time = 34;
  const gate = gpu.waitForSurfaceBackpressure();
  assert.ok(gate instanceof Promise);
  let opened = false; gate.then(() => { opened = true; });
  time = 10 + 1000 / 30; raf(time);
  await flush();
  assert.equal(opened, true, "refresh budget updates wake existing gate");
  assert.equal(gpu.stats.inFlight, 1, "wake does not fabricate retirement");
}

// Emergency count ceiling remains independent of measured interval and wakes on disable.
{
  const {gpu} = fixture({performance: {now: () => 0}}, true);
  for (let i = 0; i < 95; i++) { gpu.__testSurfaceSubmit(); assert.equal(gpu.waitForSurfaceBackpressure(), null); }
  gpu.__testSurfaceSubmit();
  const parked = gpu.waitForSurfaceBackpressure();
  assert.ok(parked instanceof Promise);
  assert.equal(gpu.stats.inFlightMax, 96);
  gpu.surfaceBackpressureEnabled = false;
  await parked;
}

// Every public A/B counter is readable both before work and while parked.
const documented = ["submitCount", "stalls", "waitedMs", "adaptiveCap", "surfaceLatencyEwma",
  "surfaceBackpressureWaits", "surfaceSubmitPending", "surfaceSubmitMaxPending",
  "acquireMsTotal", "acquireMsMax", "surfaceGateWaitMsTotal", "surfaceGateWaitMsMax",
  "surfaceGateWaitsOver50", "surfaceGateWaitsOver100", "surfaceGateWaitsOver250",
  "oldestAgeMs", "oldestAgeMsMax", "inFlight", "inFlightMax", "refreshPeriodMs", "droppedRaf"];
{
  const {gpu} = fixture();
  for (const running of [false, true]) {
    if (running) { for (let i = 0; i < 3; i++) gpu.__testSurfaceSubmit(); gpu.waitForSurfaceBackpressure(); }
    assert.equal(typeof gpu.stats, "object");
    for (const name of documented) assert.ok(Number.isFinite(gpu.stats[name]), name);
    assert.equal(typeof gpu.submitCount, "number");
    assert.doesNotThrow(() => JSON.stringify(gpu.surfaceGateSnapshot()));
  }
}

// Realistic clocks: completions notify asynchronously, in submission order, without polling.
for (const hz of [30, 60, 144]) {
  for (const [cost, latency] of [[1, 17], [4, 17], [1, 60], [4, 60]]) {
    let time = 0, raf = null, nextRaf = 1000 / hz;
    const {gpu, completions} = fixture({performance: {now: () => time},
      requestAnimationFrame: callback => { assert.equal(raf, null); raf = callback; }}, true);
    const due = [];
    let frames = 0, maxAdmissionAge = 0;
    for (; time < 2000; time += 0.25) {
      while (due.length && due[0].at <= time) { due.shift().completion.resolve(); }
      await flush();
      if (time >= nextRaf) { const callback = raf; raf = null; callback?.(nextRaf); nextRaf += 1000 / hz; }
      if (time % cost !== 0) continue;
      if (gpu.waitForSurfaceBackpressure()) continue;
      maxAdmissionAge = Math.max(maxAdmissionAge, gpu.surfaceGateSnapshot().oldestAgeMs);
      gpu.__testSurfaceSubmit(); frames++;
      due.push({at: time + latency, completion: completions.at(-1)});
    }
    const stats = gpu.stats;
    assert.equal(completions.length, frames, "at most one notification per surface frame");
    assert.ok(stats.inFlightMax <= 96);
    assert.ok(maxAdmissionAge <= Math.max(1000 / 30, 2000 / hz) + cost,
      `${hz}Hz admission bounded: ${maxAdmissionAge}`);
    assert.ok(stats.oldestAgeMsMax <= latency + 0.25, "honest age includes parked time until actual retirement");
    if (hz <= 60 && latency === 17) {
      assert.equal(stats.stalls, 0, "keeping-up GPU does not stall");
      assert.equal(stats.waitedMs, 0);
      assert.equal(stats.droppedRaf, 0);
    }
    assert.ok(Math.abs(stats.refreshPeriodMs - 1000 / hz) < 0.01);
    assert.ok(frames > 10);
  }
}

console.log("WebGPU surface backpressure smoke passed: bounded gate, rejection, teardown release, non-surface submit, gate telemetry, cap override, rolling mode");
