#!/usr/bin/env node

import assert from "node:assert/strict";
import {summarizeVisualOutput} from "./performance-metrics.mjs";

const rules = {
  requiredSamples: 6,
  requiredPhases: ["pre-measurement", "post-measurement"],
  minimumSamplesPerPhase: 3,
  expectedWidth: 1280,
  expectedHeight: 720,
  minimumNonBlackRatio: 0.05,
  minimumLuminanceStdDev: 6,
  minimumColorBuckets: 24,
  maximumDominantColorRatio: 0.97,
  minimumCentralNonBlackRatio: 0.05,
  minimumCentralLuminanceStdDev: 6,
  minimumCentralColorBuckets: 16,
  maximumCentralDominantColorRatio: 0.97,
  minimumActiveTileCount: 6,
  minimumTraversalFingerprintRms: 2,
};

function frame(index, overrides = {}) {
  return {
    available: true,
    phase: index <= 3 ? "pre-measurement" : "post-measurement",
    index: (index - 1) % 3 + 1,
    width: 1280,
    height: 720,
    sampleWidth: 80,
    sampleHeight: 45,
    pixelCount: 3600,
    canvasClip: {x: 0, y: 0, width: 1280, height: 720, scale: 1},
    nonBlackRatio: 0.62,
    luminanceStdDev: 38,
    colorBuckets: 240,
    dominantColorRatio: 0.08,
    centralPixelCount: 2200,
    centralNonBlackRatio: 0.68,
    centralLuminanceStdDev: 42,
    centralColorBuckets: 180,
    centralDominantColorRatio: 0.09,
    tileCount: 12,
    activeTileCount: 12,
    terrainFingerprint: Array.from({length: 144}, (_, offset) =>
      (offset % 16) * 4 + index * 3),
    ...overrides,
  };
}

const valid = summarizeVisualOutput(
  Array.from({length: 6}, (_, index) => frame(index + 1)),
  rules,
);
assert.equal(valid.verdict, "pass");

const blank = summarizeVisualOutput([
  ...Array.from({length: 5}, (_, index) => frame(index + 1)),
  frame(6, {
    nonBlackRatio: 0,
    luminanceStdDev: 0,
    colorBuckets: 1,
    dominantColorRatio: 1,
  }),
], rules);
assert.equal(blank.verdict, "fail");
assert.equal(blank.failures.length, 1);

const missing = summarizeVisualOutput([
  ...Array.from({length: 5}, (_, index) => frame(index + 1)),
  {available: false, phase: "post-measurement", index: 3, error: "capture failed"},
], rules);
assert.equal(missing.verdict, "inconclusive");
assert.equal(missing.unavailableSampleCount, 1);

const missingClip = summarizeVisualOutput(
  Array.from({length: 6}, (_, index) => frame(index + 1, {canvasClip: null})),
  rules,
);
assert.equal(missingClip.verdict, "inconclusive");
assert.equal(missingClip.missingCanvasClip.length, 6);

const debugOnly = summarizeVisualOutput([
  ...Array.from({length: 5}, (_, index) => frame(index + 1)),
  frame(6, {
    nonBlackRatio: 0.012,
    luminanceStdDev: 11,
    colorBuckets: 20,
    dominantColorRatio: 0.982,
    centralNonBlackRatio: 0.003,
    centralLuminanceStdDev: 3,
    centralColorBuckets: 5,
    centralDominantColorRatio: 0.994,
    activeTileCount: 1,
  }),
], rules);
assert.equal(debugOnly.verdict, "fail", "debug text cannot impersonate rendered terrain");

const spatiallyEmpty = summarizeVisualOutput([
  ...Array.from({length: 5}, (_, index) => frame(index + 1)),
  frame(6, {activeTileCount: 2}),
], rules);
assert.equal(spatiallyEmpty.verdict, "fail", "a crosshair-sized region cannot pass terrain evidence");

const staleTraversal = summarizeVisualOutput(
  Array.from({length: 6}, (_, index) => frame(index + 1, {
    terrainFingerprint: Array.from({length: 144}, (_, offset) => offset % 16),
  })),
  {...rules, requireSceneChange: true},
);
assert.equal(staleTraversal.verdict, "fail", "traversal cannot present one stale compositor frame");

const movingTraversal = summarizeVisualOutput(
  Array.from({length: 6}, (_, index) => frame(index + 1)),
  {...rules, requireSceneChange: true},
);
assert.equal(movingTraversal.verdict, "pass");

const invalidDimensions = summarizeVisualOutput([
  ...Array.from({length: 5}, (_, index) => frame(index + 1)),
  frame(6, {pixelCount: 0}),
], rules);
assert.equal(invalidDimensions.verdict, "fail");
assert.ok(invalidDimensions.failures[0].reasons.includes("invalid screenshot dimensions"));

console.log(JSON.stringify({
  passed: true,
  valid: valid.verdict,
  blank: blank.verdict,
  missing: missing.verdict,
  missingClip: missingClip.verdict,
  debugOnly: debugOnly.verdict,
  spatiallyEmpty: spatiallyEmpty.verdict,
  staleTraversal: staleTraversal.verdict,
  movingTraversal: movingTraversal.verdict,
  invalidDimensions: invalidDimensions.verdict,
}, null, 2));
