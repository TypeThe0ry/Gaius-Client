#!/usr/bin/env node
// Matches the long game frames of a release walk to the waits of the WebGPU surface gate.
//
//   node surface-gate-attribution.mjs report.json [--json]
//
// Input: a report of minecraft-263-singleplayer-cdp.mjs whose releasePerformance.walk carries
// intervals (game frame to game frame, ms), firstAt (performance.now() of the first presentation)
// and surfaceGate (GaiusWebGpu.surfaceGateSnapshot(): rows [createdAt, heldFor] in the same clock).
// For every frame interval it measures how much of it the frame pacer spent held by the gate.
import {readFileSync} from "node:fs";

const CLASSES = [["<20", 0, 20], ["20-50", 20, 50], ["50-100", 50, 100], [">=100", 100, Infinity]];

function overlap(aStart, aEnd, bStart, bEnd) {
  return Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
}

export function attribute(report, options = {}) {
  const walk = report?.releasePerformance?.walk ?? report?.walk ?? report;
  const intervals = walk?.intervals;
  const gate = walk?.surfaceGate;
  const firstAt = Number(walk?.firstAt);
  if (!Array.isArray(intervals) || intervals.length === 0) throw new Error("walk.intervals is missing");
  if (!gate || !Array.isArray(gate.rows)) throw new Error("walk.surfaceGate is missing (build without gate telemetry?)");
  if (!Number.isFinite(firstAt)) throw new Error("walk.firstAt is missing");
  const coverThreshold = options.coverThreshold ?? 0.8;
  const rows = gate.rows.map(([created, held]) => [Number(created), Number(created) + Number(held)])
    .sort((x, y) => x[0] - y[0]);
  const frames = [];
  let start = firstAt;
  for (let i = 0; i < intervals.length; i++) {
    const end = start + intervals[i];
    // rows are sorted and mostly disjoint: walk from the first row that can reach this frame
    let held = 0, longest = 0, longestRow = null;
    let lo = 0, hi = rows.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (rows[mid][1] <= start) lo = mid + 1; else hi = mid;
    }
    for (let j = lo; j < rows.length && rows[j][0] < end; j++) {
      const part = overlap(start, end, rows[j][0], rows[j][1]);
      held += part;
      if (part > longest) { longest = part; longestRow = rows[j]; }
    }
    frames.push({index: i, startAt: start, ms: intervals[i], heldMs: held, longestWaitMs: longest, longestRow});
    start = end;
  }
  const firstRow = rows.length ? rows[0][0] : Infinity;
  const classes = {};
  for (const [name, lo, hi] of CLASSES) {
    const members = frames.filter(f => f.ms >= lo && f.ms < hi);
    const total = members.reduce((sum, f) => sum + f.ms, 0);
    const held = members.reduce((sum, f) => sum + f.heldMs, 0);
    classes[name] = {
      frames: members.length,
      totalMs: Math.round(total),
      heldMs: Math.round(held),
      heldShare: total > 0 ? Number((held / total).toFixed(3)) : null,
      coveredFrames: members.filter(f => f.ms > 0 && f.heldMs / f.ms >= coverThreshold).length,
    };
  }
  const covered = frames.filter(f => f.ms >= 100 && f.heldMs / f.ms >= coverThreshold).length;
  const long = frames.filter(f => f.ms >= 100);
  return {
    mode: gate.mode ?? null,
    cap: gate.cap ?? null,
    gateWaits: gate.count ?? rows.length,
    gateWaitsDropped: gate.dropped ?? 0,
    gateTotalMs: gate.totalMs ?? null,
    gateMaxMs: gate.maxMs ?? null,
    firstGateAtOrAfterFirstFrame: firstRow >= firstAt - 1,
    coverThreshold,
    framesOver100: long.length,
    framesOver100Covered: covered,
    framesOver100CoveredShare: long.length ? Number((covered / long.length).toFixed(3)) : null,
    classes,
    longest: frames.slice().sort((x, y) => y.ms - x.ms).slice(0, 10).map(f => ({
      index: f.index, ms: Number(f.ms.toFixed(1)), heldMs: Number(f.heldMs.toFixed(1)),
      longestWaitMs: Number(f.longestWaitMs.toFixed(1)),
    })),
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const path = process.argv[2];
  if (!path) {
    console.error("usage: surface-gate-attribution.mjs report.json [--json]");
    process.exit(2);
  }
  const result = attribute(JSON.parse(readFileSync(path, "utf8")));
  if (process.argv.includes("--json")) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    console.log(`gate mode=${result.mode} cap=${result.cap} waits=${result.gateWaits} (dropped ${result.gateWaitsDropped}) ` +
      `total=${Math.round(result.gateTotalMs)}ms max=${Math.round(result.gateMaxMs)}ms`);
    console.log(`frames >=100 ms: ${result.framesOver100}, of which the gate covered >=${result.coverThreshold * 100}%: ` +
      `${result.framesOver100Covered} (${result.framesOver100CoveredShare})`);
    for (const [name, c] of Object.entries(result.classes)) {
      console.log(`  ${name.padEnd(7)} frames=${String(c.frames).padStart(5)} time=${String(c.totalMs).padStart(6)}ms ` +
        `held=${String(c.heldMs).padStart(6)}ms (${c.heldShare}) covered-frames=${c.coveredFrames}`);
    }
    console.log("longest frames:", result.longest.map(f => `#${f.index} ${f.ms}ms (gate ${f.heldMs}ms)`).join(", "));
  }
}
