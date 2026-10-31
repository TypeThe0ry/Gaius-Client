#!/usr/bin/env node
// Kernel scheduling policies (port/web/kernels/kernel-policy.js).
//
//   node port/scripts/kernel-scheduler-policy-smoke.mjs
//
// Drives the pure policies with synthetic clocks: priority classes and scores, movement
// prediction, initial plans for phones / Chromebooks / desktops, the adaptive pool-size state
// machine and the memory budget's backpressure.

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../web/kernels/kernel-policy.js", import.meta.url), "utf8");
const context = vm.createContext({});
vm.runInContext(source, context, {filename: "kernel-policy.js"});
const P = context.GaiusKernelPolicy;
const MB = 1024 * 1024;

const checks = [];
function check(name, fn) {
  checks.push([name, fn]);
}

check("priority classes order visible mesh, visible light, near gen, far gen, background", () => {
  const predictor = new P.MotionPredictor();
  predictor.update({x: 0, z: 0, t: 0});
  const job = (fields) => P.classify(fields, predictor);
  assert.equal(job({kernel: "mesh"}), P.PRIORITY.VISIBLE_MESH);
  assert.equal(job({kernel: "mesh", visible: false}), P.PRIORITY.FAR_GEN);
  assert.equal(job({kernel: "light"}), P.PRIORITY.VISIBLE_LIGHT);
  assert.equal(job({kernel: "worldgen", cx: 1, cz: 0}), P.PRIORITY.NEAR_GEN);
  assert.equal(job({kernel: "worldgen", cx: 40, cz: 40}), P.PRIORITY.FAR_GEN);
  assert.equal(job({kernel: "worldgen", background: true}), P.PRIORITY.BACKGROUND);
  assert.equal(job({kernel: "mesh", priorityClass: 4}), P.PRIORITY.BACKGROUND);
  assert.equal(job({kernel: "mesher"}), P.PRIORITY.VISIBLE_MESH, "crate-derived names keep their role");
  // Below the starvation limit a class never interleaves with another, whatever the distance.
  const now = 1e6;
  const farMesh = P.score({cls: 0, distance: 1e9, submittedAt: now}, predictor, now);
  const nearLight = P.score({cls: 1, distance: 0, submittedAt: now - P.STARVATION_MS}, predictor, now);
  assert.ok(farMesh < nearLight, "every P0 job sorts before every P1 job that has not starved");
});

check("starved P1 and P2 jobs compete one class up; far and background work never does", () => {
  const predictor = new P.MotionPredictor();
  predictor.update({x: 0, z: 0, t: 0});
  const now = 1e6;
  const starved = now - P.STARVATION_MS - 1000;
  // A server light job behind a flood of fresh visible mesh jobs.
  const freshMesh = P.score({cls: 0, distance: 8, submittedAt: now}, predictor, now);
  const starvedLight = P.score({cls: 1, distance: 2, submittedAt: starved}, predictor, now);
  assert.ok(starvedLight < freshMesh, "a light job queued past the limit overtakes fresh P0 work");
  assert.ok(starvedLight < P.CLASS_SPAN, "it competes inside P0");
  const starvedNearGen = P.score({cls: 2, distance: 0, submittedAt: starved}, predictor, now);
  assert.ok(starvedNearGen >= P.CLASS_SPAN && starvedNearGen < 2 * P.CLASS_SPAN, "P2 rises to P1, not further");
  const starvedFar = P.score({cls: 3, distance: 0, submittedAt: 0}, predictor, now);
  const starvedBackground = P.score({cls: 4, distance: 0, submittedAt: 0}, predictor, now);
  assert.ok(starvedFar >= 3 * P.CLASS_SPAN, "far generation stays behind its worker cap");
  assert.ok(starvedBackground >= 4 * P.CLASS_SPAN);
  // Bounded wait: once past the limit, a P1 job sorts ahead of P0 work at its own distance or
  // farther, so under a P0 flood it waits STARVATION_MS plus about one rescore interval.
  const sameDistanceMesh = P.score({cls: 0, distance: 2, submittedAt: now}, predictor, now);
  assert.ok(starvedLight < sameDistanceMesh);
});

check("within a class nearer chunks first and aging lifts long-waiting jobs", () => {
  const predictor = new P.MotionPredictor();
  predictor.update({x: 8, z: 8, t: 0});
  const near = P.score({cls: 2, cx: 1, cz: 0, submittedAt: 0}, predictor, 0);
  const far = P.score({cls: 2, cx: 6, cz: 0, submittedAt: 0}, predictor, 0);
  assert.ok(near < far);
  const agedFar = P.score({cls: 2, cx: 6, cz: 0, submittedAt: 0}, predictor, 10_000);
  const freshNear = P.score({cls: 2, cx: 1, cz: 0, submittedAt: 10_000}, predictor, 10_000);
  assert.ok(agedFar < freshNear, "a far job queued for 10 s overtakes a fresh near one");
  const agedWithin = P.score({cls: 2, cx: 6, cz: 0, submittedAt: 0}, predictor, P.STARVATION_MS);
  assert.ok(agedWithin >= 2 * P.CLASS_SPAN, "aging below the starvation limit never leaves the class");
  assert.ok(agedFar >= P.CLASS_SPAN, "a starved job rises one class at most");
});

check("movement prediction favours chunks ahead of the player", () => {
  const predictor = new P.MotionPredictor({horizonSec: 2});
  // Flying east (+X) at 10 blocks/s, derived from positions.
  for (let i = 0; i <= 10; i++) predictor.update({x: 100 + i * 2.5, z: 0, t: i * 250});
  assert.ok(predictor.speed > 8 && predictor.speed < 12, `speed ${predictor.speed}`);
  assert.ok(predictor.dirX > 0.95, "heading east");
  const playerChunkX = Math.floor(predictor.x / 16);
  const ahead = predictor.chunkDistance(playerChunkX + 4, 0);
  const behind = predictor.chunkDistance(playerChunkX - 4, 0);
  const side = predictor.chunkDistance(playerChunkX, 4);
  assert.ok(ahead < side && side < behind, `ahead ${ahead} side ${side} behind ${behind}`);
  assert.ok(predictor.isNear(playerChunkX + predictor.nearChunks(), 0), "the lead edge counts as near");
  assert.ok(!predictor.isNear(playerChunkX - predictor.nearChunks() - 2, 0), "far behind is not near");
  // Standing still: only the look direction hints, weakly.
  const still = new P.MotionPredictor();
  still.update({x: 0, z: 0, yaw: 0, t: 0});
  still.update({x: 0, z: 0, yaw: 0, t: 1000});
  assert.ok(still.heading > 0 && still.heading < 0.3);
  assert.ok(still.chunkDistance(0, 3) < still.chunkDistance(0, -4), "yaw 0 looks south (+Z)");
  // A teleport is not motion.
  const teleported = new P.MotionPredictor();
  teleported.update({x: 0, z: 0, t: 0});
  teleported.update({x: 5000, z: 0, t: 250});
  assert.equal(teleported.speed, 0);
});

check("explicit velocities and the rescoring epoch", () => {
  const predictor = new P.MotionPredictor();
  predictor.update({x: 0, z: 0, vx: 0, vz: 30, t: 0});
  assert.ok(predictor.dirZ > 0.99 && predictor.heading === 1);
  const epoch = predictor.epoch;
  predictor.update({x: 0, z: 0.5, vx: 0, vz: 30, t: 20});
  assert.equal(predictor.epoch, epoch, "same predicted chunk and heading: no rescore");
  predictor.update({x: 0, z: 0, vx: -30, vz: 0, t: 40});
  predictor.update({x: 0, z: 0, vx: -30, vz: 0, t: 60});
  predictor.update({x: 0, z: 0, vx: -30, vz: 0, t: 80});
  assert.ok(predictor.epoch > epoch, "turning around asks for a rescore");
});

check("initial plans: phone, 4 GB Chromebook, desktop, unknown", () => {
  const phone = P.initialPlan({hardwareConcurrency: 8, deviceMemory: 4, mobile: true});
  assert.equal(phone.initial, 2);
  assert.ok(phone.ceiling <= 2);
  assert.equal(phone.budgetBytes, 128 * MB);
  const chromebook = P.initialPlan({hardwareConcurrency: 4, deviceMemory: 4});
  assert.equal(chromebook.initial, 2);
  assert.equal(chromebook.ceiling, 2);
  assert.equal(chromebook.budgetBytes, 160 * MB);
  assert.equal(chromebook.lowMemory, true);
  const small = P.initialPlan({hardwareConcurrency: 2, deviceMemory: 2});
  assert.equal(small.initial, 1);
  assert.equal(small.ceiling, 1);
  assert.equal(small.budgetBytes, 96 * MB);
  const desktop = P.initialPlan({hardwareConcurrency: 16, deviceMemory: 8});
  assert.equal(desktop.initial, 6);
  assert.equal(desktop.ceiling, 8);
  assert.equal(desktop.budgetBytes, 512 * MB);
  const unknown = P.initialPlan({});
  assert.equal(unknown.initial, 2);
  assert.equal(unknown.budgetBytes, 256 * MB);
  const capped = P.initialPlan({hardwareConcurrency: 32, deviceMemory: 8, maxSize: 3});
  assert.equal(capped.ceiling, 3);
  assert.ok(capped.initial <= 3);
  assert.equal(P.isMobileAgent({userAgent: "Mozilla/5.0 (Linux; Android 14) Mobile"}), true);
  assert.equal(P.isMobileAgent({userAgentData: {mobile: false}, userAgent: "Android"}), false);
});

function run(sizer, samples) {
  const decisions = [];
  for (const sample of samples) decisions.push(sizer.sample(sample));
  return decisions;
}

check("sizing: trial growth kept on a throughput gain", () => {
  const sizer = new P.PoolSizer({initial: 2, ceiling: 4, min: 1}, {trialWindowMs: 2000});
  let completed = 0;
  let busy = 0;
  let t = 0;
  const step = (rate, workers) => {
    t += 1000;
    completed += rate;
    busy += 1000 * workers;
    return {now: t, completed, busyMs: busy, queuedHigh: 50, queued: 80, heapPressure: 0.3, budgetPressure: 0.2};
  };
  run(sizer, [{now: 0, completed: 0, busyMs: 0, queuedHigh: 50, queued: 80}]);
  const grow = run(sizer, [step(10, 2), step(10, 2)]);
  assert.equal(grow[1].action, "grow");
  assert.equal(sizer.size, 3);
  assert.equal(sizer.state, "trial");
  const trial = run(sizer, [step(15, 3), step(15, 3)]);
  assert.equal(trial[0].reason, "trial-running");
  assert.equal(trial.at(-1).action, "keep");
  assert.equal(sizer.size, 3);
  assert.equal(sizer.state, "steady");
});

check("sizing: rollback and a remembered ceiling when the extra worker brings nothing", () => {
  const sizer = new P.PoolSizer({initial: 2, ceiling: 6, min: 1}, {trialWindowMs: 2000, cooldownMs: 3000, ceilingTtlMs: 60000});
  let completed = 0;
  let busy = 0;
  let t = 0;
  const step = (rate, workers) => {
    t += 1000;
    completed += rate;
    busy += 1000 * workers;
    return {now: t, completed, busyMs: busy, queuedHigh: 50, queued: 80, heapPressure: 0.3, budgetPressure: 0.2};
  };
  run(sizer, [{now: 0, completed: 0, busyMs: 0, queuedHigh: 50, queued: 80}, step(10, 2), step(10, 2)]);
  assert.equal(sizer.size, 3);
  const decisions = run(sizer, [step(10, 3), step(10, 3)]);
  assert.equal(decisions.at(-1).action, "rollback");
  assert.equal(sizer.size, 2);
  assert.equal(sizer.ceiling, 2);
  assert.equal(sizer.state, "cooldown");
  // During the cooldown and while the ceiling holds, no new trial starts.
  const later = run(sizer, [step(10, 2), step(10, 2), step(10, 2), step(10, 2), step(10, 2), step(10, 2)]);
  assert.ok(later.every((decision) => decision.action !== "grow"));
  assert.equal(sizer.size, 2);
});

check("sizing: heap pressure shrinks, idle time shrinks back to the initial size", () => {
  const sizer = new P.PoolSizer({initial: 2, ceiling: 4, min: 1}, {cooldownMs: 1000, idleShrinkMs: 5000});
  sizer.size = 4;
  run(sizer, [{now: 0, completed: 0, busyMs: 0, queuedHigh: 0, queued: 0}]);
  const pressured = run(sizer, [{now: 1000, completed: 5, busyMs: 3000, queuedHigh: 3, queued: 3, heapPressure: 0.92}]);
  assert.equal(pressured[0].action, "shrink");
  assert.equal(pressured[0].reason, "heap-pressure");
  assert.equal(sizer.size, 3);
  const budget = run(sizer, [{now: 2000, completed: 6, busyMs: 4000, queuedHigh: 3, queued: 3, heapPressure: 0.2, budgetPressure: 1.05}]);
  assert.equal(budget[0].reason, "budget-pressure");
  assert.equal(sizer.size, 2);
  let t = 2000;
  const idle = [];
  for (let i = 0; i < 12; i++) {
    t += 1000;
    idle.push(sizer.sample({now: t, completed: 6, busyMs: 4000, queuedHigh: 0, queued: 0, heapPressure: 0.2, budgetPressure: 0.1}));
  }
  assert.equal(sizer.size, 2, "idle shrink stops at the initial size");
  const atMinimum = new P.PoolSizer({initial: 1, ceiling: 1, min: 1});
  run(atMinimum, [{now: 0, completed: 0, busyMs: 0, queuedHigh: 0, queued: 0}]);
  assert.equal(atMinimum.sample({now: 1000, completed: 0, busyMs: 0, queuedHigh: 0, queued: 0, heapPressure: 0.99}).reason, "pressure-at-minimum");
  assert.equal(atMinimum.size, 1);
});

check("memory budget: class shares, backpressure and the progress guarantee", () => {
  const budget = new P.MemoryBudget(100 * MB);
  budget.setInstance("1:mesh", 30 * MB);
  budget.setInstance("2:worldgen", 30 * MB);
  assert.equal(budget.instanceBytes, 60 * MB);
  // Background work (50 % share) is refused at submit; visible work always queues.
  assert.equal(budget.canQueue(1 * MB, P.PRIORITY.BACKGROUND), false);
  assert.equal(budget.canQueue(5 * MB, P.PRIORITY.FAR_GEN), true);
  assert.equal(budget.canQueue(500 * MB, P.PRIORITY.VISIBLE_MESH), true);
  assert.equal(budget.canQueue(500 * MB, P.PRIORITY.VISIBLE_LIGHT), true);
  assert.equal(budget.rejected, 1);
  budget.queue(8 * MB);
  assert.equal(budget.canQueue(5 * MB, P.PRIORITY.FAR_GEN), false, "queued bytes count against the share");
  // Dispatch: a far job that does not fit waits while others run; P0 still fits.
  budget.unqueue(8 * MB);
  budget.begin(20 * MB);
  assert.equal(budget.canDispatch(10 * MB, P.PRIORITY.FAR_GEN), false);
  assert.equal(budget.canDispatch(10 * MB, P.PRIORITY.VISIBLE_MESH), true);
  budget.end(20 * MB);
  assert.equal(budget.canDispatch(90 * MB, P.PRIORITY.VISIBLE_MESH), true, "nothing in flight: one job may always run");
  assert.equal(budget.canDispatch(90 * MB, P.PRIORITY.BACKGROUND), true, "the queue drains even when instances fill the budget");
  budget.begin(1 * MB);
  assert.equal(budget.canDispatch(90 * MB, P.PRIORITY.VISIBLE_MESH), false);
  budget.end(1 * MB);
  budget.dropInstancesWithPrefix("1:");
  assert.equal(budget.instanceBytes, 30 * MB);
  assert.ok(Math.abs(budget.pressure() - 0.3) < 1e-9);
  // Queued payloads count against admission but not against what the workers hold.
  budget.queue(50 * MB);
  assert.ok(Math.abs(budget.pressure() - 0.8) < 1e-9);
  assert.ok(Math.abs(budget.residentPressure() - 0.3) < 1e-9, "resident memory ignores the queue");
  budget.unqueue(50 * MB);
  const snapshot = budget.snapshot();
  assert.equal(snapshot.limit, 100 * MB);
  assert.equal(snapshot.held, 2);
});

check("a long P0 queue does not shrink the pool or refuse near generation (4 GB desktop)", () => {
  const plan = P.initialPlan({hardwareConcurrency: 4, deviceMemory: 4});
  const budget = new P.MemoryBudget(plan.budgetBytes);
  budget.setInstance("1:mesher", 8 * MB);
  // Entering a world at render distance 12: hundreds of visible mesh jobs queue. Only their
  // payloads (about 24 KB each) are charged while queued; results are charged at dispatch.
  for (let i = 0; i < 600; i++) {
    assert.equal(budget.canQueue(24 * 1024 + 600 * 1024, P.PRIORITY.VISIBLE_MESH), true);
    budget.queue(24 * 1024);
  }
  budget.begin(2 * 640 * 1024);
  assert.ok(budget.pressure() < 1, "the queued payloads alone stay inside the budget");
  assert.equal(budget.canQueue(2 * MB, P.PRIORITY.NEAR_GEN), true, "server worldgen still queues");
  const sizer = new P.PoolSizer(plan);
  const before = sizer.size;
  let t = 0;
  for (let i = 0; i < 6; i++) {
    t += 1000;
    sizer.sample({now: t, completed: i * 20, busyMs: t * before, queuedHigh: 600, queued: 600,
      heapPressure: 0.3, budgetPressure: budget.residentPressure()});
  }
  assert.ok(sizer.size >= before, `the pool keeps its workers (${before} -> ${sizer.size})`);
});

let failed = 0;
for (const [name, fn] of checks) {
  try {
    fn();
    console.log(`ok   ${name}`);
  } catch (error) {
    failed++;
    console.log(`FAIL ${name}`);
    console.log(error && error.stack || error);
  }
}
if (failed) {
  console.log(`${failed} of ${checks.length} kernel policy checks failed`);
  process.exit(1);
}
console.log(`all ${checks.length} kernel policy checks passed`);
