#!/usr/bin/env node

import {CANONICAL_PROFILE, LEGACY_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import {patcherSource} from "./patcher-sources.mjs";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const HOLDERS_PER_TURN = 16;

function pendingDependencyLayerModel(
  turns,
  dependencyOf = new Map(),
  {drainAfterEachTurn = false} = {},
) {
  const submitted = [];
  const submittedSet = new Set();
  const completed = [];
  const completedSet = new Set();
  const pending = [];
  // Keep the planned turn shape separate from the turns actually submitted.
  // The old one-holder await stops after turn 0, but its intended schedule is
  // still [1, ...] and must be visible to the regression assertion.
  const batches = turns.map(turn => [...turn]);
  const submittedBatches = [];
  const drains = [];

  function drainPending(stage) {
    let progress = true;
    const drained = [];
    while (progress) {
      progress = false;
      const remaining = [];
      for (const item of pending) {
        const dependency = item.dependsOn;
        if (dependency == null || completedSet.has(dependency)) {
          completed.push(item.holder);
          completedSet.add(item.holder);
          drained.push(item.holder);
          progress = true;
        } else {
          remaining.push(item);
        }
      }
      pending.splice(0, pending.length, ...remaining);
    }
    drains.push({stage, drained, pending: pending.map(item => item.holder)});
  }

  for (const [turnIndex, turn] of turns.entries()) {
    const submittedBatch = [];
    for (const holder of turn) {
      assert.equal(submittedSet.has(holder), false,
        `layer model submitted holder ${holder} more than once`);
      submittedSet.add(holder);
      submitted.push(holder);
      submittedBatch.push(holder);
      pending.push({holder, dependsOn: dependencyOf.get(holder) ?? null});
    }
    submittedBatches.push(submittedBatch);
    if (drainAfterEachTurn) {
      drainPending(`turn-${turnIndex}`);
      if (pending.length) {
        return {
          blocked: true,
          blockedAtTurn: turnIndex,
          waitedAt: submitted.length,
          submitted,
          completed,
          pending: pending.map(item => ({...item})),
          batches,
          submittedBatches,
          drains,
        };
      }
    }
  }

  // The fixed barrier drains only after every holder in the layer was submitted.
  drainPending("final-layer-barrier");
  return {
    blocked: pending.length !== 0,
    blockedAtTurn: null,
    waitedAt: submitted.length,
    submitted,
    completed,
    pending: pending.map(item => ({...item})),
    batches,
    submittedBatches,
    drains,
  };
}

function boundedLayerTurns(holderCount, batchLimit = HOLDERS_PER_TURN) {
  const turns = [];
  for (let holder = 0; holder < holderCount; holder += batchLimit) {
    turns.push(Array.from(
      {length: Math.min(batchLimit, holderCount - holder)},
      (_, offset) => holder + offset,
    ));
  }
  return turns;
}

function oneHolderTurns(holderCount) {
  return boundedLayerTurns(holderCount, 1);
}

function boundedLayerModel(holderCount, {waitBetweenBatches = false} = {}) {
  const run = pendingDependencyLayerModel(
    boundedLayerTurns(holderCount),
    new Map([[0, holderCount - 1]]),
    {drainAfterEachTurn: waitBetweenBatches},
  );
  return {
    blocked: run.blocked,
    waitedAt: run.waitedAt,
    scheduled: run.submitted,
    batches: run.batches,
    submittedBatches: run.submittedBatches,
    completed: run.completed,
    pending: run.pending,
    drains: run.drains,
  };
}

function sourceSection(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  assert.notEqual(start, -1, `missing source marker: ${startMarker}`);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.notEqual(end, -1, `missing source marker: ${endMarker}`);
  return source.slice(start, end);
}

function assertOrdered(section, markers, message) {
  let previous = -1;
  for (const marker of markers) {
    const index = section.indexOf(marker, previous + 1);
    assert.ok(index > previous, `${message}: missing/out-of-order ${marker}`);
    previous = index;
  }
}

const fourHolders = boundedLayerModel(4);
assert.deepEqual(fourHolders.batches.map(batch => batch.length), [4]);
assert.equal(fourHolders.waitedAt, 4,
  "a 2x2 layer must not reach its wait before all holders are submitted");

const seventeenHolders = boundedLayerModel(17);
assert.deepEqual(seventeenHolders.batches.map(batch => batch.length), [16, 1],
  "a 17-holder layer must span exactly two bounded browser turns");
assert.deepEqual(seventeenHolders.submittedBatches.map(batch => batch.length), [16, 1],
  "the fixed barrier must submit both bounded turns before draining");
assert.deepEqual(seventeenHolders.scheduled,
  Array.from({length: 17}, (_, holder) => holder),
  "the fixed barrier must submit all 17 holders before waiting");
assert.equal(seventeenHolders.waitedAt, 17,
  "the layer barrier must follow the final 17-holder batch");

const twentyFiveHolders = boundedLayerModel(25);
assert.deepEqual(twentyFiveHolders.batches.map(batch => batch.length), [16, 9]);
assert.ok(twentyFiveHolders.batches.every(
  batch => batch.length > 0 && batch.length <= HOLDERS_PER_TURN,
), "every browser turn must process between 1 and 16 holders");
assert.equal(new Set(twentyFiveHolders.scheduled).size, 25,
  "the bounded cursor must submit each holder exactly once");

const unsafeOldOrder = boundedLayerModel(17, {waitBetweenBatches: true});
assert.equal(unsafeOldOrder.blocked, true,
  "the synthetic same-layer dependency must expose the old per-batch await hazard");
assert.equal(unsafeOldOrder.waitedAt, 16,
  "the unsafe model must wait before the dependency holder is submitted");
assert.deepEqual(unsafeOldOrder.pending, [{holder: 0, dependsOn: 16}],
  "the unsafe model must retain the blocked holder in its pending list");
assert.equal(unsafeOldOrder.drains.length, 1,
  "the unsafe model must stop at its first per-batch drain");
assert.deepEqual(seventeenHolders.pending, [],
  "the fixed model must drain all pending holders after the final batch");
assert.deepEqual(seventeenHolders.completed.sort((a, b) => a - b),
  Array.from({length: 17}, (_, holder) => holder),
  "the fixed model must complete every holder after the final batch");
assert.equal(seventeenHolders.drains.length, 1,
  "the fixed model must not drain any holder batch before the final barrier");
assert.equal(seventeenHolders.drains.at(-1)?.stage, "final-layer-barrier",
  "the fixed model must drain at the final layer barrier");

const oldOneHolderOrder = pendingDependencyLayerModel(
  oneHolderTurns(17),
  new Map([[0, 16]]),
  {drainAfterEachTurn: true},
);
assert.equal(oldOneHolderOrder.blocked, true,
  "the old one-holder await order must block on a same-layer dependency");
assert.equal(oldOneHolderOrder.blockedAtTurn, 0,
  "the old order must await holder 0 before submitting its dependency holder");
assert.deepEqual(oldOneHolderOrder.batches.map(batch => batch.length),
  Array.from({length: 17}, () => 1),
  "the old regression model must use one holder per browser turn");
assert.deepEqual(oldOneHolderOrder.pending, [{holder: 0, dependsOn: 16}],
  "the old blocked order must retain holder 0 pending on holder 16");
assert.deepEqual(oldOneHolderOrder.submittedBatches.map(batch => batch.length), [1],
  "the old await model must stop submitting after the blocked first turn");
assert.deepEqual(oldOneHolderOrder.submitted, [0],
  "the old await model must not submit the dependency holder after blocking");

const patchers = [
  {
    profile: CANONICAL_PROFILE.id,
    patcher: "Minecraft262BrowserPatcher",
    scheduler: true,
  },
  {
    profile: LEGACY_PROFILE.id,
    patcher: "Minecraft12111BrowserPatcher",
    scheduler: false,
  },
];

for (const patcher of patchers) {
  // The step with its domain classes (port/tools/patchkit/README.md, "Domains").
  const source = patcherSource(patcher.patcher);
  assert.match(source, /BROWSER_HOLDERS_PER_TURN = 16/,
    `${patcher.profile} batch limit changed`);

  const gate = sourceSection(
    source,
    "private static void patchRunUntilWaitYieldGate",
    "private static void replaceChunkGenerationScheduleNextLayer",
  );
  assertOrdered(gate, [
    "BROWSER_LAYER_YIELD",
    "Opcodes.ARETURN",
    "BROWSER_LAYER_ACTIVE",
    '"scheduleNextLayer"',
    "BROWSER_LAYER_YIELD",
    "Opcodes.ARETURN",
    "gate.add(continueVanilla)",
    "method.instructions.insert(entryLabel, gate)",
  ], `${patcher.profile} active-before-wait gate`);
  if (patcher.scheduler) {
    assertOrdered(gate, [
      "activeResume",
      "freshReturn",
      "BROWSER_LAYER_YIELD",
      "Opcodes.ARETURN",
      "method.instructions.insertBefore(activeResume, freshReturn)",
    ], `${patcher.profile} fresh path returns before pulse`);
  }
  assert.doesNotMatch(gate, /Opcodes\.GOTO,\s*gateStart/,
    `${patcher.profile} gate added a second runUntilWait backedge`);

  const scheduleLayer = sourceSection(
    source,
    "private static void replaceChunkGenerationScheduleLayer",
    "private static void writeChunkGenerationYieldHelper",
  );
  assertOrdered(scheduleLayer, [
      "code.add(start)",
      "Opcodes.ISTORE, 7",
      "code.add(resume)",
      '"scheduleChunkInLayer"',
      "BROWSER_HOLDERS_PER_TURN",
      "Opcodes.IF_ICMPLT, resume",
      "code.add(scheduleYield)",
      'Opcodes.NEW, "java/util/concurrent/CompletableFuture"',
      '"org/teavm/platform/Platform"',
  ], `${patcher.profile} bounded holder batch`);
  if (patcher.scheduler) {
    assert.match(scheduleLayer, /"startThread"/,
      `${patcher.profile} continuation must use TeaVM native-thread bootstrap`);
  }
  assert.match(scheduleLayer,
      /Opcodes\.PUTFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"[\s\S]*Opcodes\.GOTO, scheduleYield/,
      `${patcher.profile} final holder does not retain the final continuation barrier`);
  assert.match(scheduleLayer,
      /code\.add\(cancel\)[\s\S]*BROWSER_LAYER_ACTIVE[\s\S]*BROWSER_LAYER_YIELD[\s\S]*normalReturn/,
      `${patcher.profile} cancellation does not clear cursor/yield state`);
  assert.match(scheduleLayer,
      /code\.add\(handler\)[\s\S]*BROWSER_LAYER_ACTIVE[\s\S]*BROWSER_LAYER_YIELD[\s\S]*ATHROW/,
      `${patcher.profile} exception cleanup does not clear cursor/yield state`);

  if (patcher.scheduler) {
    assertOrdered(scheduleLayer, [
      "code.add(successful)",
      "WORLDGEN_SCHEDULER",
      '"pulse"',
      "Opcodes.ILOAD, 7",
    ], "" + CANONICAL_PROFILE.id + " per-holder scheduler pulse");
  } else {
    assert.doesNotMatch(source, /BrowserWorldgenScheduler|"pulse"|"checkpoint"/,
      "" + LEGACY_PROFILE.id + " holder cursor must remain scheduler-call free");
  }
}

console.log("Chunk layer barrier smoke passed", JSON.stringify({
  profiles: patchers.map(({profile}) => profile),
  holderBatchLimit: HOLDERS_PER_TURN,
  twoByTwoTurns: fourHolders.batches.map(batch => batch.length),
  seventeenHolderTurns: seventeenHolders.batches.map(batch => batch.length),
  syntheticOldOrderBlocked: unsafeOldOrder.blocked,
  waitAfterCompleteLayer: true,
}));
