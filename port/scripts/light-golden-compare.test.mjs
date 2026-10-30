import assert from "node:assert/strict";
import {compareLightColumn, effectiveLight} from "./light-golden-compare.mjs";

const upper = new Uint8Array(2048);
upper[17] = 0x9c;
assert.equal(effectiveLight([null, upper], 0, 34, true), 12);
assert.equal(effectiveLight([null, upper], 0, 35 + 8 * 256, true), 9);
assert.equal(effectiveLight([null, upper], 0, 34, false), 0);
assert.equal(effectiveLight([null, null], 0, 34, true), 15);
assert.equal(effectiveLight([new Uint8Array(2048), upper], 0, 34, true), 0);
const empty = [null, null, null];
const expected = {block: empty, sky: empty};
const actual = {tableMissing: false, lightSections: 3, block: [...empty], sky: [...empty]};
const spec = {sectionCount: 1, minSection: -4, sky: true};
assert.deepEqual(compareLightColumn(expected, actual, spec).mismatches, {block: 0, sky: 0});
actual.block[0] = new Uint8Array(2048);
actual.block[0][0] = 1;
actual.sky[2] = new Uint8Array(2048).fill(0xff);
actual.sky[2][2047] = 0x7f;
const changed = compareLightColumn(expected, actual, spec);
assert.deepEqual(changed.mismatches, {block: 1, sky: 1});
assert.deepEqual(changed.samples, [{kind: "block", x: 0, z: 0, y: -80, expected: 0, actual: 1},
  {kind: "sky", x: 15, z: 15, y: -33, expected: 15, actual: 7}]);
assert.deepEqual(compareLightColumn(expected, actual, {...spec, sky: false}).mismatches, {block: 1, sky: 0});
assert.throws(() => compareLightColumn(expected, {...actual, tableMissing: true}, spec), /table was not loaded/);
assert.throws(() => compareLightColumn(expected, {...actual, lightSections: 2}, spec), /height differs/);
console.log("light-golden-compare: null sky extrusion, nibbles, both height padding sections and mismatch accounting PASS");
