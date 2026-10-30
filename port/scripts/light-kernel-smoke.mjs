#!/usr/bin/env node
// Light kernel smoke: runs light_column jobs encoded by port/web/kernels/light-job.js through
// the built wasm modules and checks a few hand-computed levels, then checks that the SIMD and
// baseline builds return the same bytes, that a job without its table gets "table missing" from
// a fresh instance and runs once the table is spliced in, and that a full-height job with four
// neighbours (ring slices only) stays under 100 KB.
//
//   node port/scripts/light-kernel-smoke.mjs <simd.wasm> [baseline.wasm]
//
// Build the modules first with port/native/build-light-wasm.sh.
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const native = join(here, "..", "native", "target", "light");
const [simdPath = join(native, "gaius_light_wasm.simd.wasm"), baselinePath = join(native, "gaius_light_wasm.baseline.wasm")] =
  process.argv.slice(2);

const context = vm.createContext({globalThis: undefined, BigInt, DataView, ArrayBuffer, Uint8Array, Uint16Array,
  Uint32Array, Int32Array, Math, Object, Array, Error, RangeError, TypeError, WebAssembly});
context.globalThis = context;
vm.runInContext(readFileSync(join(here, "..", "web", "kernels", "light-job.js"), "utf8"), context);
const codec = context.GaiusLightJob;

// States: 0 air, 1 stone (dampening 15), 2 torch (emission 14), 3 bottom slab (shape class 1).
// Faces: 0 empty, 1 full, 2 lower half.
function lightTable() {
  const runs = [[1, 0x00], [1, 0x0f], [1, 0xe0], [1, 0x100]];
  const faces = [[0, 0, 0, 0, 0, 0], [1, 0, 2, 2, 2, 2]];
  const faceCount = 3;
  const words = [0x42544c47, 1, 4, runs.length, faces.length, faceCount];
  const bytes = [];
  const u32 = (v) => bytes.push(v & 255, (v >>> 8) & 255, (v >>> 16) & 255, (v >>> 24) & 255);
  words.forEach(u32);
  runs.forEach(([length, props]) => { u32(length); u32(props); });
  faces.forEach((f) => bytes.push(...f));
  const matrix = new Uint8Array(Math.ceil((faceCount * faceCount) / 8));
  for (let a = 0; a < faceCount; a++) {
    for (let b = 0; b < faceCount; b++) {
      if (a === 1 || b === 1) matrix[(a * faceCount + b) >> 3] |= 1 << ((a * faceCount + b) & 7);
    }
  }
  bytes.push(...matrix);
  return new Uint8Array(bytes);
}

// PalettedContainer.write bytes: 4-bit linear palette, value per cell from pick(index).
function networkSection(palette, pick) {
  const out = [4, palette.length, ...palette];
  const words = new DataView(new ArrayBuffer(256 * 8));
  for (let word = 0; word < 256; word++) {
    let value = 0n;
    for (let k = 0; k < 16; k++) value |= BigInt(pick(word * 16 + k)) << BigInt(4 * k);
    words.setBigUint64(word * 8, value, false);
  }
  return new Uint8Array([...out, ...new Uint8Array(words.buffer)]);
}

function instantiate(path) {
  const module = new WebAssembly.Module(readFileSync(path));
  return instance(module, path);
}

function instance(module, path) {
  const {exports} = new WebAssembly.Instance(module, {});
  for (const name of ["memory", "alloc", "dealloc", "release", "gaius_abi_version", "run_light_column"]) {
    assert.ok(name in exports, `${path} lacks export ${name}`);
  }
  return (job) => {
    const input = new Uint8Array(job);
    const ptr = exports.alloc(input.byteLength) >>> 0;
    new Uint8Array(exports.memory.buffer, ptr, input.byteLength).set(input);
    const desc = exports.run_light_column(ptr, input.byteLength) >>> 0;
    const view = new DataView(exports.memory.buffer);
    const status = view.getUint32(desc, true);
    const data = new Uint8Array(exports.memory.buffer, view.getUint32(desc + 4, true), view.getUint32(desc + 8, true)).slice();
    exports.release(desc);
    exports.dealloc(ptr, input.byteLength);
    assert.equal(status, 0, new TextDecoder().decode(data));
    return data.buffer;
  };
}

const nibble = (layer, x, y, z) => {
  const i = (y << 8) | (z << 4) | x;
  return (layer[i >> 1] >> ((i & 1) << 2)) & 15;
};

function scene(random) {
  // Two world sections: stone floor at y 0..2 with a torch on it, a slab roof at y 20 over x/z 4..9.
  const storing = codec.SECTION_FLAGS.SKY_STORING | codec.SECTION_FLAGS.BLOCK_STORING;
  const ground = networkSection([0, 1, 2], (i) => {
    const y = i >> 8;
    const z = (i >> 4) & 15;
    const x = i & 15;
    if (y < 3) return 1;
    if (y === 3 && x === 8 && z === 8) return 2;
    return random && random() < 0.08 ? 1 : 0;
  });
  const flat = new Uint16Array(4096);
  for (let z = 4; z < 10; z++) for (let x = 4; x < 10; x++) flat[(4 << 8) | (z << 4) | x] = 3;
  // East neighbour: the same stone floor, and a stone roof at y 24 over all of it.
  const eastLow = new Uint16Array(4096);
  const eastHigh = new Uint16Array(4096);
  for (let i = 0; i < 256; i++) {
    for (let y = 0; y < 3; y++) eastLow[(y << 8) | i] = 1;
    eastHigh[(8 << 8) | i] = 1;
  }
  const neighbour = {flags: new Uint8Array(4).fill(storing), sections: [{flat: eastLow}, {flat: eastHigh}]};
  return {
    ops: codec.OPS.INITIAL, sky: true, block: true, emitOutgoing: true,
    chunkX: 3, chunkZ: -2, minSection: 0, sectionCount: 2, skyBottomSection: -1,
    table: lightTable(), tableEpoch: 1234n,
    column: {flags: new Uint8Array(4).fill(storing), sections: [ground, {flat}]},
    neighbours: {east: neighbour},
  };
}

const runs = [simdPath, baselinePath].map((path) => [path, instantiate(path)]);
for (const [path, run] of runs) {
  const result = codec.readLightColumn(run(codec.lightColumn(scene(null), 9)), 9);
  assert.equal(result.tableMissing, false);
  assert.equal(result.lightSections, 4);
  const block1 = result.block[1];
  const sky1 = result.sky[1];
  const sky2 = result.sky[2];
  assert.equal(nibble(block1, 8, 3, 8), 14, path);
  assert.equal(nibble(block1, 8, 4, 8), 13, path);
  assert.equal(nibble(block1, 0, 3, 8), 6, path);
  assert.equal(nibble(sky1, 0, 3, 0), 15, path);
  assert.equal(nibble(sky1, 0, 2, 0), 0, path);
  // Under the slab roof (world y 20 = section 1 local 4) light only comes in sideways.
  assert.equal(nibble(sky2, 6, 3, 6), 12, path);
  // Sky light leaks sideways under the east neighbour's roof: one record per lit ring cell.
  let skyIntoEast = 0;
  for (let i = 0; i < result.outgoing.count; i++) {
    assert.equal(result.outgoing.side[i], 3);
    if (result.outgoing.kind[i] === codec.OUTGOING.SKY_INCREASE) {
      assert.equal(result.outgoing.level[i], 14);
      skyIntoEast++;
    }
  }
  // Ring cells at y 3..23 (the roof sits at 24) for all 16 z.
  assert.equal(skyIntoEast, 21 * 16, path);
  console.log(`light-kernel-smoke: ${path.split(/[\\/]/).pop()} ok (${result.stats.increasePops} increase pops, ${result.outgoing.count} outgoing)`);
}

// SIMD and baseline builds agree byte for byte on a noisier column, first light and checks.
let seed = 0x2545f491;
const random = () => ((seed = (seed * 1103515245 + 12345) >>> 0) / 4294967296);
const noisy = scene(random);
const first = runs.map(([, run]) => new Uint8Array(run(codec.lightColumn(noisy, 1))));
assert.deepEqual(first[0], first[1]);
const checks = Object.assign({}, noisy, {ops: 0, checks: [8, 3, 8, 1, 5, 1], skyLightOn: true, blockLightOn: true});
const second = runs.map(([, run]) => new Uint8Array(run(codec.lightColumn(checks, 2))));
assert.deepEqual(second[0], second[1]);
console.log("light-kernel-smoke: simd and baseline builds agree");

// Table protocol: a job that names its table epoch without the bytes gets "table missing" from
// an instance that never saw the table, the same job with the table spliced in runs, and the
// instance then serves table-less jobs of that epoch.
{
  const module = new WebAssembly.Module(readFileSync(simdPath));
  const run = instance(module, simdPath);
  const full = scene(null);
  const lean = Object.assign({}, full, {table: null});
  const leanJob = codec.lightColumn(lean, 21);
  const missing = run(leanJob.slice(0));
  assert.ok(codec.isTableMissing(missing));
  const decoded = codec.readLightColumn(missing, 21);
  assert.equal(decoded.tableMissing, true);
  assert.equal(decoded.lightSections, 4);
  const spliced = codec.withTable(leanJob, lightTable());
  assert.deepEqual(new Uint8Array(spliced), new Uint8Array(codec.lightColumn(full, 21)));
  const lit = run(spliced);
  assert.ok(!codec.isTableMissing(lit));
  const again = run(codec.lightColumn(lean, 21));
  assert.deepEqual(new Uint8Array(again), new Uint8Array(lit));
  console.log("light-kernel-smoke: table-missing round trip ok");
}

// Job size: 24 sections, every neighbour present with noisy sections and stored layers in every
// light section. Neighbours travel as ring slices (palette + one byte per touching cell, 128
// bytes per layer), the table stays out of the job.
{
  let state = 0x9e3779b9;
  const next = () => ((state = (state * 1664525 + 1013904223) >>> 0) / 4294967296);
  const sectionCount = 24;
  const storing = codec.SECTION_FLAGS.SKY_STORING | codec.SECTION_FLAGS.BLOCK_STORING;
  const noisyFlat = () => {
    const flat = new Uint16Array(4096);
    for (let i = 0; i < 4096; i++) flat[i] = next() < 0.6 ? 1 : (next() < 0.5 ? 0 : 3);
    return flat;
  };
  const noisyLayer = () => {
    const layer = new Uint8Array(2048);
    for (let i = 0; i < 2048; i++) layer[i] = (next() * 256) | 0;
    return layer;
  };
  const slot = (withLayers) => ({
    flags: new Uint8Array(sectionCount + 2).fill(storing),
    sections: Array.from({length: sectionCount}, () => ({flat: noisyFlat()})),
    sky: withLayers ? Array.from({length: sectionCount + 2}, noisyLayer) : null,
    block: withLayers ? Array.from({length: sectionCount + 2}, noisyLayer) : null,
  });
  const job = {
    ops: codec.OPS.INITIAL, sky: true, block: true, emitOutgoing: true, omitUnchanged: true,
    chunkX: 0, chunkZ: 0, minSection: -4, sectionCount, skyBottomSection: -5,
    table: null, tableEpoch: 1234n,
    column: {flags: new Uint8Array(sectionCount + 2).fill(storing),
      sections: Array.from({length: sectionCount}, () => ({single: 0}))},
    neighbours: {north: slot(true), south: slot(true), west: slot(true), east: slot(true)},
  };
  const bytes = codec.lightColumn(job, 5).byteLength;
  // Before job version 2 the four neighbours alone carried 4 x 26 x 2 x 2048 bytes of layers.
  assert.ok(bytes < 100 * 1024, `light_column job with four neighbours is ${bytes} bytes`);
  const module = new WebAssembly.Module(readFileSync(simdPath));
  const run = instance(module, simdPath);
  const result = codec.readLightColumn(run(codec.withTable(codec.lightColumn(job, 5), lightTable())), 5);
  assert.equal(result.tableMissing, false);
  assert.equal(result.lightSections, sectionCount + 2);
  console.log(`light-kernel-smoke: full-height job with four neighbours is ${bytes} bytes`);
}
