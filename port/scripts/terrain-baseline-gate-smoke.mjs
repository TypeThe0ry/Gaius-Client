import assert from "node:assert/strict";
import {terrainBaselinePass} from "./terrain-baseline-gate.mjs";

const baseline = {
  before: {sky: 0.0063, other: 0.9892},
  after: {sky: 0.0082, other: 0.9892},
  diff: {meanDiff: 29.81, changedFraction: 0.2068},
  beforeAudit: {visible: 225, uncompiled: 0}, afterAudit: {visible: 264, uncompiled: 0},
  beforeLoaded: 421, afterLoaded: 329, beforeExpectedLoaded: 289, afterExpectedLoaded: 289,
};
assert.equal(terrainBaselinePass(baseline), true);
assert.equal(terrainBaselinePass({...baseline, after: {sky: 1, other: 0}}), false);
assert.equal(terrainBaselinePass({...baseline, before: {sky: 1, other: 0}}), false);
assert.equal(terrainBaselinePass({...baseline, afterAudit: {visible: 0, uncompiled: 0}}), false);
assert.equal(terrainBaselinePass({...baseline, beforeAudit: {visible: 30, uncompiled: 2}}), false);
assert.equal(terrainBaselinePass({...baseline, afterLoaded: 0}), false);
assert.equal(terrainBaselinePass({...baseline, diff: {meanDiff: 4, changedFraction: 0.01}}), true);
assert.equal(terrainBaselinePass({...baseline, diff: {meanDiff: 36, changedFraction: 0.01}}), false);
console.log("terrain baseline gate smoke: PASS");
