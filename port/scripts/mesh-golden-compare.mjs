import assert from "node:assert/strict";

const layers = ["solid", "cutout", "translucent"];
export function compareSection(result, references, job, table, vertexStride = 28) {
  assert(result.length >= 160, "truncated mesh result");
  assert.equal(result.readUInt32LE(4), job.readUInt32LE(4), "request sequence");
  assert.equal(result.readUInt32LE(8), job.readUInt32LE(8), "section version");
  assert.deepEqual(result.subarray(12, 24), job.subarray(16, 28), "section coordinates");
  assert.equal(result.readUInt32LE(24), table.readUInt32LE(8), "table epoch");
  const status = result.readUInt32LE(0);
  if (status === 2) {
    const state = result.readUInt32LE(52);
    assert(state < table.readUInt32LE(12), "decline state outside table");
    assert(vertexStride !== 28 || table.readUInt32LE(64 + state * 56) & 8192, "decline state is not marked unsupported");
    let present = false;
    for (let vertical = 0; vertical < 16; vertical++) for (let depth = 0; depth < 16; depth++) for (let horizontal = 0; horizontal < 16; horizontal++) {
      if (job.readUInt16LE(96 + 2 * ((vertical + 2) * 400 + (depth + 2) * 20 + horizontal + 2)) === state) present = true;
    }
    assert(present, "decline state not in section interior");
    assert.equal(result.length, 160, "decline must not publish partial geometry");
    assert(!result.subarray(64).some((value) => value !== 0), "decline layer descriptors must be empty");
    return {declinedState: state, bytesCompared: 0, bytesDifferent: 0, differences: []};
  }
  assert.equal(status, 0, "missing table or unknown mesh status");
  let bytesCompared = 0;
  let bytesDifferent = 0;
  const differences = [];
  function compare(actual, expected, label) {
    const count = Math.max(actual.length, expected.length);
    let unequal = 0;
    let first = null;
    for (let index = 0; index < count; index++) if (actual[index] !== expected[index]) {
      unequal++;
      if (first === null) first = index;
    }
    bytesCompared += count;
    bytesDifferent += unequal;
    if (unequal) differences.push({layer: label, actualBytes: actual.length, expectedBytes: expected.length, bytesDifferent: unequal, first});
  }
  for (let index = 0; index < layers.length; index++) {
    const base = 64 + index * 32;
    const quads = result.readUInt32LE(base);
    const offset = result.readUInt32LE(base + 4);
    assert(!quads || offset >= 160 && offset + quads * 112 <= result.length, "vertex bounds");
    compare(result.subarray(offset, offset + quads * 112), references[layers[index]].vertices, layers[index]);
    if (index !== 2) {
      assert.equal(references[layers[index]].indices.length, 0, "opaque layers must use sequential draw indices");
      continue;
    }
    const sorted = references.translucent.indices;
    if (!quads) { compare(Buffer.alloc(0), sorted, "translucent-indices"); continue; }
    const width = quads * 4 > 65536 ? 4 : 2;
    const permutation = result.readUInt32LE(base + 16);
    assert(permutation >= 160 && permutation + quads * 4 <= result.length, "translucent permutation bounds");
    const indices = Buffer.alloc(quads * 6 * width);
    const seen = new Set();
    for (let quad = 0; quad < quads; quad++) {
      const source = result.readUInt32LE(permutation + quad * 4);
      assert(source < quads, "translucent permutation range");
      assert(!seen.has(source), "duplicate translucent permutation entry");
      seen.add(source);
      const corners = [0, 1, 2, 2, 3, 0];
      for (let corner = 0; corner < 6; corner++) indices[width === 2 ? "writeUInt16LE" : "writeUInt32LE"](source * 4 + corners[corner], (quad * 6 + corner) * width);
    }
    compare(indices, sorted, "translucent-indices");
  }
  return {bytesCompared, bytesDifferent, differences};
}
