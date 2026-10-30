#!/usr/bin/env node

import assert from "node:assert/strict";
import {
  aggregateChromeProcessRss,
  parsePsRssOutput,
  summarizeChromeProcessRssTrend,
} from "./performance-metrics.mjs";

const MIB = 1024 * 1024;
const REQUIRED_DURATION_MS = 30 * 60 * 1000;
const rules = {
  requiredDurationMs: REQUIRED_DURATION_MS,
  sampleIntervalMs: 15_000,
  minimumAvailableRatio: 0.9,
  minimumDurationCoverageRatio: 0.98,
  maximumSampleGapRatio: 1.5,
  trendWindowCount: 4,
  processTypes: ["browser", "renderer", "gpu", "utility"],
  requiredProcessTypes: ["browser", "renderer", "gpu"],
  totalThresholds: {
    growthPercentMax: 25,
    growthBytesMax: 512 * MIB,
    peakBytesMax: 12 * 1024 * MIB,
  },
  typeThresholds: {
    browser: {growthPercentMax: 25, growthBytesMax: 256 * MIB, peakBytesMax: 2 * 1024 * MIB},
    renderer: {growthPercentMax: 25, growthBytesMax: 384 * MIB, peakBytesMax: 8 * 1024 * MIB},
    gpu: {growthPercentMax: 25, growthBytesMax: 256 * MIB, peakBytesMax: 2 * 1024 * MIB},
    utility: {growthPercentMax: 50, growthBytesMax: 128 * MIB, peakBytesMax: 2 * 1024 * MIB},
  },
};

const parsed = parsePsRssOutput(" 101 2048\n202 4096\ninvalid\n");
assert.deepEqual(parsed, {"101": 2 * MIB, "202": 4 * MIB});

const aggregated = aggregateChromeProcessRss([
  {id: 101, type: "browser", cpuTime: 1.5},
  {id: 202, type: "renderer", cpuTime: 2.5},
  {id: 303, type: "GPU", cpuTime: 0.5},
  {id: 404, type: "network.mojom.NetworkService", cpuTime: 0.25},
], {
  "101": 200 * MIB,
  "202": 600 * MIB,
  "303": 220 * MIB,
  "404": 100 * MIB,
});
assert.equal(aggregated.available, true);
assert.equal(aggregated.totalRssBytes, 1120 * MIB);
assert.equal(aggregated.byType.gpu.rssBytes, 220 * MIB);
assert.equal(aggregated.byType.utility.rssBytes, 100 * MIB);
assert.equal(aggregated.processes.at(-1).rawType, "network.mojom.NetworkService");

const missing = aggregateChromeProcessRss([
  {id: 101, type: "browser"},
  {id: 202, type: "renderer"},
], {"101": 200 * MIB});
assert.equal(missing.available, false);
assert.equal(missing.totalRssBytes, null);
assert.deepEqual(missing.missingPids, [202]);

function sample(atMillis, values) {
  const byType = Object.fromEntries(Object.entries(values).map(([type, rssBytes]) => [type, {
    available: true,
    rssBytes,
    processCount: type === "renderer" ? 2 : 1,
    missingProcessCount: 0,
  }]));
  return {
    available: true,
    atMillis,
    totalRssBytes: Object.values(values).reduce((sum, value) => sum + value, 0),
    byType,
    processes: [],
  };
}

const SAMPLE_COUNT = REQUIRED_DURATION_MS / rules.sampleIntervalMs + 1;
const stableSamples = Array.from({length: SAMPLE_COUNT}, (_, index) => sample(
  index * rules.sampleIntervalMs,
  {
    browser: (200 + [0, 2, 1, 3][index % 4]) * MIB,
    renderer: (600 + [0, 5, 2, 4][index % 4]) * MIB,
    gpu: (220 + [0, 3, 1, 2][index % 4]) * MIB,
    utility: (100 + [0, 1, 0, 2][index % 4]) * MIB,
  },
));
const stable = summarizeChromeProcessRssTrend({
  samples: stableSamples,
  durationMs: REQUIRED_DURATION_MS,
  ...rules,
});
assert.equal(stable.verdict, "pass");
assert.equal(stable.availability.unavailableSampleCount, 0);
assert.equal(stable.total.failed, false);

const gappedSamples = stableSamples.map((entry, index) => index === 20
  ? {...entry, atMillis: entry.atMillis + 30_000}
  : entry);
const gapped = summarizeChromeProcessRssTrend({
  samples: gappedSamples,
  durationMs: REQUIRED_DURATION_MS,
  ...rules,
});
assert.equal(gapped.verdict, "inconclusive",
  "a long RSS sampling blind spot must not pass on sample count alone");
assert.ok(gapped.availability.maximumSampleGapMs
  > gapped.availability.maximumAllowedSampleGapMs);

const sparse = summarizeChromeProcessRssTrend({
  samples: stableSamples.filter((_, index) => index % 40 === 0),
  durationMs: REQUIRED_DURATION_MS,
  ...rules,
});
assert.equal(sparse.verdict, "inconclusive");
assert.ok(sparse.availability.availableSampleCount < sparse.availability.minimumExpectedSampleCount);

const unavailableSamples = Array.from({length: SAMPLE_COUNT}, (_, index) => ({
  available: false,
  atMillis: index * rules.sampleIntervalMs,
  error: "SystemInfo.getProcessInfo unavailable",
  totalRssBytes: null,
  byType: {},
}));
const unavailable = summarizeChromeProcessRssTrend({
  samples: unavailableSamples,
  durationMs: REQUIRED_DURATION_MS,
  ...rules,
});
assert.equal(unavailable.verdict, "inconclusive");
assert.equal(unavailable.availability.availableSampleCount, 0);
assert.equal(unavailable.total.firstRssBytes, null);
assert.deepEqual(unavailable.availability.errors, ["SystemInfo.getProcessInfo unavailable"]);

const growingSamples = Array.from({length: SAMPLE_COUNT}, (_, index) => {
  const progress = index / (SAMPLE_COUNT - 1);
  return sample(index * rules.sampleIntervalMs, {
    browser: (200 + 100 * progress) * MIB,
    renderer: (600 + 800 * progress) * MIB,
    gpu: (220 + 400 * progress) * MIB,
    utility: (100 + 180 * progress) * MIB,
  });
});
const growing = summarizeChromeProcessRssTrend({
  samples: growingSamples,
  durationMs: REQUIRED_DURATION_MS,
  ...rules,
});
assert.equal(growing.verdict, "fail");
assert.equal(growing.total.sustainedGrowth, true);
assert.equal(growing.total.failed, true);
assert.ok(growing.failedMetrics.includes("total"));
assert.ok(growing.failedMetrics.includes("renderer"));
assert.ok(growing.failedMetrics.includes("gpu"));

const highByteLowPercentSamples = Array.from({length: SAMPLE_COUNT}, (_, index) => {
  const progress = index / (SAMPLE_COUNT - 1);
  return sample(index * rules.sampleIntervalMs, {
    browser: (1000 + 100 * progress) * MIB,
    renderer: (7000 + 1200 * progress) * MIB,
    gpu: (500 + 100 * progress) * MIB,
    utility: (500 + 100 * progress) * MIB,
  });
});
const highByteLowPercent = summarizeChromeProcessRssTrend({
  samples: highByteLowPercentSamples,
  durationMs: REQUIRED_DURATION_MS,
  ...rules,
});
assert.equal(highByteLowPercent.verdict, "fail",
  "large sustained byte growth must fail even below the percentage threshold");
assert.equal(highByteLowPercent.total.growthPercent < 25, true);
assert.equal(highByteLowPercent.total.growthFailed, true);

const overPeakSamples = stableSamples.map((entry) => sample(entry.atMillis, {
  browser: 1500 * MIB,
  renderer: 9500 * MIB,
  gpu: 800 * MIB,
  utility: 800 * MIB,
}));
const overPeak = summarizeChromeProcessRssTrend({
  samples: overPeakSamples,
  durationMs: REQUIRED_DURATION_MS,
  ...rules,
});
assert.equal(overPeak.verdict, "fail", "absolute RSS peak limits must be release-gated");
assert.equal(overPeak.byType.renderer.peakFailed, true);

const shortCapture = summarizeChromeProcessRssTrend({
  samples: growingSamples.map((entry, index) => ({
    ...entry,
    atMillis: index * 5 * 60 * 1000 / (SAMPLE_COUNT - 1),
  })),
  durationMs: 5 * 60 * 1000,
  ...rules,
});
assert.equal(shortCapture.verdict, "not-evaluated");

console.log(JSON.stringify({
  passed: true,
  stable: stable.verdict,
  sparse: sparse.verdict,
  gapped: gapped.verdict,
  unavailable: unavailable.verdict,
  sustainedGrowth: growing.verdict,
  highByteLowPercent: highByteLowPercent.verdict,
  overPeak: overPeak.verdict,
  shortCapture: shortCapture.verdict,
  growth: {
    totalMiB: growing.total.growthMiB,
    totalPercent: growing.total.growthPercent,
    failedMetrics: growing.failedMetrics,
  },
}, null, 2));
