#!/usr/bin/env node
import assert from "node:assert/strict";
import {compareSection} from "./mesh-golden-compare.mjs";

let checks = 0;
function input() {
  const job = Buffer.alloc(16096);
  job.writeUInt32LE(17, 4); job.writeUInt32LE(3, 8);
  job.writeInt32LE(-2, 16); job.writeInt32LE(4, 20); job.writeInt32LE(9, 24);
  const table = Buffer.alloc(64 + 2 * 56);
  table.writeUInt32LE(7, 8); table.writeUInt32LE(2, 12);
  const result = Buffer.alloc(160);
  result.writeUInt32LE(17, 4); result.writeUInt32LE(3, 8);
  job.copy(result, 12, 16, 28); result.writeUInt32LE(7, 24);
  const references = Object.fromEntries(["solid", "cutout", "translucent"].map((layer) =>
    [layer, {vertices: Buffer.alloc(0), indices: Buffer.alloc(0)}]));
  return {job, table, result, references};
}
function compare(data, stride = 28) {
  checks++;
  return compareSection(data.result, data.references, data.job, data.table, stride);
}
assert.equal(compare(input()).bytesDifferent, 0);
for (const offset of [4, 8, 12, 24]) {
  const data = input(); data.result[offset] ^= 1;
  assert.throws(() => compare(data));
}
const truncated = input(); truncated.result = truncated.result.subarray(0, 159);
assert.throws(() => compare(truncated), /truncated/);
const unknown = input(); unknown.result.writeUInt32LE(1);
assert.throws(() => compare(unknown), /status/);
const decline = input(); decline.result.writeUInt32LE(2); decline.result.writeUInt32LE(1, 52);
decline.table.writeUInt32LE(8192, 64 + 56);
assert.throws(() => compare(decline), /not in section/);
decline.job.writeUInt16LE(1, 96 + 2 * (2 * 400 + 2 * 20 + 2));
assert.equal(compare(decline).declinedState, 1);
decline.table.writeUInt32LE(0, 64 + 56);
assert.throws(() => compare(decline), /unsupported/);
assert.equal(compare(decline, 32).declinedState, 1);
decline.result[64] = 1;
assert.throws(() => compare(decline, 32), /descriptors/);
decline.result[64] = 0; decline.result = Buffer.concat([decline.result, Buffer.alloc(1)]);
assert.throws(() => compare(decline, 32), /partial/);

function translucent(quads) {
  const data = input();
  const vertices = Buffer.alloc(quads * 112, 0x5a);
  const order = Buffer.alloc(quads * 4);
  const width = quads * 4 > 65536 ? 4 : 2;
  const indices = Buffer.alloc(quads * 6 * width);
  for (let quad = 0; quad < quads; quad++) {
    const source = quads - quad - 1; order.writeUInt32LE(source, quad * 4);
    for (const [corner, value] of [0, 1, 2, 2, 3, 0].entries()) {
      indices[width === 2 ? "writeUInt16LE" : "writeUInt32LE"](source * 4 + value, (quad * 6 + corner) * width);
    }
  }
  data.result.writeUInt32LE(quads, 128); data.result.writeUInt32LE(160, 132);
  data.result.writeUInt32LE(160 + vertices.length, 144);
  data.result = Buffer.concat([data.result, vertices, order]);
  data.references.translucent = {vertices, indices};
  return data;
}
for (const quads of [1, 2, 16384, 16385]) assert.equal(compare(translucent(quads)).bytesDifferent, 0);
const corrupt = translucent(2); corrupt.result[170] ^= 1;
assert.equal(compare(corrupt).bytesDifferent, 1);
corrupt.result[170] ^= 1; corrupt.references.translucent.indices[0] ^= 1;
assert.equal(compare(corrupt).bytesDifferent, 1);
const duplicate = translucent(2); duplicate.result.writeUInt32LE(1, 160 + 224 + 4);
assert.throws(() => compare(duplicate), /duplicate/);
const range = translucent(2); range.result.writeUInt32LE(2, 160 + 224);
assert.throws(() => compare(range), /range/);
const bounds = translucent(2); bounds.result.writeUInt32LE(bounds.result.length, 144);
assert.throws(() => compare(bounds), /bounds/);
const badVertices = translucent(1); badVertices.result.writeUInt32LE(159, 132);
assert.throws(() => compare(badVertices), /bounds/);
const opaque = input(); opaque.references.solid.indices = Buffer.from([0]);
assert.throws(() => compare(opaque), /sequential/);
console.log(`MESH_GOLDEN_COMPARE_TEST PASS checks=${checks}`);
