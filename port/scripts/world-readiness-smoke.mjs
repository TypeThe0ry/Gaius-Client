#!/usr/bin/env node

import assert from "node:assert/strict";
import {summarizeWorldReadiness} from "./performance-metrics.mjs";

const rules = {
  requiredConsecutiveTerrainFrames: 16,
  minimumTerrainWindowMs: 250,
  maximumTerrainFrameGapMs: 100,
  minimumBlockHitFrames: 3,
  minimumVisualSamples: 3,
};

function readyFrames(count, {
  startAt = 0,
  startFrame = 1,
  visualAt = [1, 5, 9],
  hit = true,
} = {}) {
  return Array.from({length: count}, (_, index) => ({
    at: startAt + index * 20,
    stateAt: startAt + index * 20,
    baseReady: true,
    visibleFrameCount: startFrame + index,
    frameAgeMillis: 4,
    hitIsSolidBlock: hit,
    visualPass: visualAt.includes(index) ? true : undefined,
  }));
}

const valid = summarizeWorldReadiness(readyFrames(16), rules);
assert.equal(valid.verdict, "pass");
assert.ok(valid.streakMillis >= 250);
assert.ok(valid.blockHitFrames >= 3);
assert.equal(valid.validVisualSamples, 3);

const interrupted = [
  ...readyFrames(8),
  {
    at: 160,
    stateAt: 160,
    baseReady: false,
    visibleFrameCount: 9,
    frameAgeMillis: 4,
  },
  ...readyFrames(12, {startAt: 180, startFrame: 10}),
];
assert.equal(summarizeWorldReadiness(interrupted, rules).verdict, "pending");

const stalled = readyFrames(20);
stalled[12] = {...stalled[12], frameAgeMillis: 101};
assert.equal(summarizeWorldReadiness(stalled, rules).verdict, "pending");

const blackFrame = readyFrames(20);
blackFrame[10] = {...blackFrame[10], visualPass: false};
assert.equal(summarizeWorldReadiness(blackFrame, rules).verdict, "pending");

const frozenCounter = readyFrames(30).map((frame) => ({
  ...frame,
  visibleFrameCount: 1,
}));
assert.equal(summarizeWorldReadiness(frozenCounter, rules).verdict, "pending");

const jumpedCounter = readyFrames(3).map((frame, index) => ({
  ...frame,
  at: index * 250,
  stateAt: index * 250,
  visibleFrameCount: index === 0 ? 1 : index * 100,
}));
assert.equal(summarizeWorldReadiness(jumpedCounter, rules).verdict, "pending",
  "one observation must not stand in for hundreds of unobserved frames");

console.log(JSON.stringify({
  passed: true,
  valid: valid.verdict,
  interrupted: summarizeWorldReadiness(interrupted, rules).verdict,
  stalled: summarizeWorldReadiness(stalled, rules).verdict,
  blackFrame: summarizeWorldReadiness(blackFrame, rules).verdict,
  frozenCounter: summarizeWorldReadiness(frozenCounter, rules).verdict,
  jumpedCounter: summarizeWorldReadiness(jumpedCounter, rules).verdict,
}, null, 2));
