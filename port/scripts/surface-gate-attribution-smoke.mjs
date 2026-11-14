import assert from "node:assert/strict";
import {attribute} from "./surface-gate-attribution.mjs";

// 6 frames: 10, 5, 120 (gate holds 110 of it), 8, 300 (no gate), 40 (gate holds 10 of it).
const intervals = [10, 5, 120, 8, 300, 40];
const firstAt = 1000;
// presentation times: 1000, 1010, 1015, 1135, 1143, 1443, 1483
const report = {releasePerformance: {walk: {
  intervals, firstAt,
  surfaceGate: {mode: "drain", cap: 3, count: 2, dropped: 0, totalMs: 120, maxMs: 110,
    rows: [[1020, 110], [1453, 10]]},
}}};
const result = attribute(report);
assert.equal(result.framesOver100, 2);
assert.equal(result.framesOver100Covered, 1, "only the 120 ms frame sits behind the gate");
assert.equal(result.classes[">=100"].heldMs, 110);
assert.equal(result.classes[">=100"].frames, 2);
assert.equal(result.classes["20-50"].heldMs, 10);
assert.equal(result.classes["<20"].frames, 3);
assert.equal(result.longest[0].ms, 300);
assert.equal(result.longest[1].heldMs, 110);
assert.equal(result.mode, "drain");

// A wait that spans two frames is split between them.
const split = attribute({walk: {intervals: [50, 50], firstAt: 0,
  surfaceGate: {rows: [[25, 50]], count: 1, dropped: 0, totalMs: 50, maxMs: 50}}});
assert.equal(split.classes["20-50"].frames, 0);
assert.equal(split.classes["50-100"].heldMs, 50);

assert.throws(() => attribute({walk: {intervals: [1], firstAt: 0}}), /surfaceGate/);
assert.throws(() => attribute({walk: {intervals: [], firstAt: 0, surfaceGate: {rows: []}}}), /intervals/);
assert.throws(() => attribute({walk: {intervals: [1], surfaceGate: {rows: []}}}), /firstAt/);
console.log("surface gate attribution smoke passed: coverage, split waits, missing inputs");
