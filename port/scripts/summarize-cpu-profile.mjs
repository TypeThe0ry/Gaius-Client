#!/usr/bin/env node

import {readFile} from "node:fs/promises";
import {resolve} from "node:path";

const profilePath = process.argv[2];
if (!profilePath) {
  throw new Error("Usage: node summarize-cpu-profile.mjs <profile.cpuprofile> [limit]");
}
const limit = Math.max(1, Number(process.argv[3] || "30") || 30);
const absolutePath = resolve(profilePath);
const profile = JSON.parse(await readFile(absolutePath, "utf8"));
if (!Array.isArray(profile.nodes) || !Array.isArray(profile.samples)) {
  throw new Error("CPU profile is missing nodes or samples");
}

const nodeById = new Map(profile.nodes.map((node) => [node.id, node]));
const parentById = new Map();
for (const node of profile.nodes) {
  for (const childId of node.children || []) {
    parentById.set(childId, node.id);
  }
}
const timeDeltas = Array.isArray(profile.timeDeltas) ? profile.timeDeltas : [];
const fallbackDelta = profile.samples.length > 0
  ? Math.max(0, Number(profile.endTime) - Number(profile.startTime)) /
    profile.samples.length
  : 0;
const aggregated = new Map();
let sampledMicros = 0;
let idleMicros = 0;
let profilerOverheadMicros = 0;

function frameKey(frame) {
  return [
    frame.functionName || "(anonymous)",
    frame.url || "",
    Number(frame.lineNumber ?? -1),
    Number(frame.columnNumber ?? -1),
  ].join("\n");
}

function entryFor(frame) {
  const key = frameKey(frame);
  let entry = aggregated.get(key);
  if (!entry) {
    entry = {
      functionName: frame.functionName || "(anonymous)",
      url: frame.url || "",
      line: Number(frame.lineNumber ?? -1) + 1,
      column: Number(frame.columnNumber ?? -1) + 1,
      selfMicros: 0,
      totalMicros: 0,
      selfSamples: 0,
      totalSamples: 0,
    };
    aggregated.set(key, entry);
  }
  return entry;
}

for (let index = 0; index < profile.samples.length; index++) {
  const node = nodeById.get(profile.samples[index]);
  if (!node) continue;
  const delta = Math.max(0, Number(timeDeltas[index] ?? fallbackDelta) || 0);
  sampledMicros += delta;
  const frame = node.callFrame || {};
  const functionName = frame.functionName || "(anonymous)";
  const isIdle = functionName === "(idle)";
  const isProfilerOverhead = String(frame.url || "").startsWith("node:inspector");
  if (isIdle) idleMicros += delta;
  if (isProfilerOverhead) {
    profilerOverheadMicros += delta;
  }
  const entry = entryFor(frame);
  entry.selfMicros += delta;
  entry.selfSamples++;

  // A recursive frame may occur more than once in one sampled stack. Count it
  // once so an aggregate function's inclusive percentage cannot exceed 100%.
  if (!isIdle && !isProfilerOverhead) {
    const stackKeys = new Set();
    let stackNode = node;
    while (stackNode) {
      const stackFrame = stackNode.callFrame || {};
      const key = frameKey(stackFrame);
      if (!stackKeys.has(key)) {
        const stackEntry = entryFor(stackFrame);
        stackEntry.totalMicros += delta;
        stackEntry.totalSamples++;
        stackKeys.add(key);
      }
      stackNode = nodeById.get(parentById.get(stackNode.id));
    }
  }
}

const activeMicros = Math.max(1, sampledMicros - idleMicros - profilerOverheadMicros);
const visibleEntries = [...aggregated.values()]
  .filter((entry) => !["(root)", "(idle)", "(program)", "(garbage collector)"].includes(
    entry.functionName) && !entry.url.startsWith("node:inspector"));

function formatEntry(entry) {
  return {
    function: entry.functionName,
    selfMs: Math.round(entry.selfMicros / 100) / 10,
    selfPercent: Math.round(entry.selfMicros * 1000 / activeMicros) / 10,
    totalMs: Math.round(entry.totalMicros / 100) / 10,
    totalPercent: Math.round(entry.totalMicros * 1000 / activeMicros) / 10,
    selfSamples: entry.selfSamples,
    totalSamples: entry.totalSamples,
    location: entry.url
      ? `${entry.url}:${entry.line}:${entry.column}`
      : `${entry.line}:${entry.column}`,
  };
}

const topSelf = visibleEntries
  .toSorted((left, right) => right.selfMicros - left.selfMicros)
  .slice(0, limit)
  .map(formatEntry);
const topTotal = visibleEntries
  .filter((entry) => entry.url && !entry.url.startsWith("node:") &&
    !entry.url.endsWith("singleplayer-worker-runtime-smoke.mjs"))
  .toSorted((left, right) => right.totalMicros - left.totalMicros)
  .slice(0, limit)
  .map(formatEntry);

console.log(JSON.stringify({
  profile: absolutePath,
  durationMs: Math.round((Number(profile.endTime) - Number(profile.startTime)) / 100) / 10,
  sampledMs: Math.round(sampledMicros / 100) / 10,
  idleMs: Math.round(idleMicros / 100) / 10,
  profilerOverheadMs: Math.round(profilerOverheadMicros / 100) / 10,
  activeMs: Math.round(activeMicros / 100) / 10,
  samples: profile.samples.length,
  topSelf,
  topTotal,
}, null, 2));
