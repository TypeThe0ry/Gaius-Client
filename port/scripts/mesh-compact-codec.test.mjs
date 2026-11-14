import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";

function loadCodec() {
  const context = {
    console,
    URLSearchParams,
    ArrayBuffer,
    DataView,
    Uint8Array,
    Uint16Array,
    Uint32Array,
    Float32Array,
    BigInt,
    BigInt64Array,
    Math,
    Map,
    Set,
    Array,
    Object,
    Number,
    String,
    TypeError,
    RangeError,
    Error,
    Promise,
  };
  vm.createContext(context);
  vm.runInContext(readFileSync(new URL("../web/kernels/mesh-job.js", import.meta.url), "utf8"), context);
  return context.GaiusMeshJob;
}

function compactResult(flags, vertexBytes) {
  const payloadLength = 160 + vertexBytes;
  const buffer = new ArrayBuffer(16 + payloadLength);
  const frame = new DataView(buffer);
  frame.setUint32(0, 0x53524b47, true); // GKRS
  frame.setUint16(4, 1, true);
  frame.setUint16(6, 0, true);
  frame.setUint32(8, 0, true);
  frame.setUint32(12, payloadLength, true);
  const payload = new DataView(buffer, 16);
  payload.setUint32(36, flags, true);
  payload.setUint32(64, 1, true);
  payload.setUint32(72, 160, true);
  return buffer;
}

test("mesh result codec accepts legacy compact v1 and self-describing v2", () => {
  const codec = loadCodec();
  for (const [flags, vertexSize] of [[codec.RESULT_FLAGS.COMPACT, 12],
    [codec.RESULT_FLAGS.COMPACT | codec.RESULT_FLAGS.COMPACT_V2, 20]]) {
    const result = codec.readMeshSection(compactResult(flags, vertexSize * 4));
    assert.equal(result.layers.solid.compactVertexSize, vertexSize);
    assert.equal(result.layers.solid.compact.byteLength, vertexSize * 4);
  }
});

test("compact v2 decoder preserves packed color, light, and quantized coordinates", () => {
  const codec = loadCodec();
  const bytes = new ArrayBuffer(codec.COMPACT_VERTEX);
  const view = new DataView(bytes);
  view.setUint16(0, Math.round((1.25 + 8) * 2048), true);
  view.setUint16(2, Math.round((-2.5 + 8) * 2048), true);
  view.setUint16(4, Math.round((15.75 + 8) * 2048), true);
  view.setUint32(8, 0x7f123456, true);
  view.setUint16(12, 16384, true);
  view.setUint16(14, 49151, true);
  view.setUint16(16, 123, true);
  view.setUint16(18, 231, true);
  const vertex = codec.decodeCompactVertex(view, 0, 20);
  assert.equal(vertex.version, 2);
  assert.equal(vertex.color, 0x7f123456);
  assert.equal(vertex.block, 123);
  assert.equal(vertex.sky, 231);
  assert.ok(Math.abs(vertex.x - 1.25) <= 0.5 / 2048);
  assert.ok(Math.abs(vertex.y + 2.5) <= 0.5 / 2048);
  assert.ok(Math.abs(vertex.z - 15.75) <= 0.5 / 2048);
  assert.ok(Math.abs(vertex.u - 0.25) <= 1 / 65535);
  assert.ok(Math.abs(vertex.v - 0.75) <= 1 / 65535);
});
