import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {test} from "node:test";

const sectionIndex = (x, y, z) => (y * 3 + z) * 3 + x;
const localIndex = (x, y, z) => (y << 8) | (z << 4) | x;
const gridIndex = (x, y, z) => ((y + 2) * 20 + z + 2) * 20 + x + 2;

function hash(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function makeStorage(values, bits) {
  const perWord = Math.floor(64 / bits);
  const raw = new BigUint64Array(Math.ceil(values.length / perWord));
  const mask = (1n << BigInt(bits)) - 1n;
  for (let i = 0; i < values.length; i++) {
    raw[Math.floor(i / perWord)] |= (BigInt(values[i]) & mask)
      << BigInt((i % perWord) * bits);
  }
  return raw;
}

function rawGet(raw, bits, index) {
  const perWord = Math.floor(64 / bits);
  const word = Math.floor(index / perWord);
  const shift = (index - word * perWord) * bits;
  return Number((raw[word] >> BigInt(shift)) & ((1n << BigInt(bits)) - 1n));
}

function captureStates(sections, fast, airId, registryCount) {
  const out = new Uint32Array(8000);
  for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) {
    const section = sections[sectionIndex(dx + 1, dy + 1, dz + 1)];
    const x0 = dx < 0 ? 14 : 0, x1 = dx > 0 ? 1 : 15;
    const y0 = dy < 0 ? 14 : 0, y1 = dy > 0 ? 1 : 15;
    const z0 = dz < 0 ? 14 : 0, z1 = dz > 0 ? 1 : 15;
    for (let y = y0; y <= y1; y++) for (let z = z0; z <= z1; z++) {
      for (let x = x0; x <= x1; x++) {
        const rawValue = section ? (fast
          ? rawGet(section.raw, section.bits, localIndex(x, y, z))
          : section.values[localIndex(x, y, z)]) : 0;
        const id = !section ? airId : section.ids[rawValue];
        out[gridIndex(dx * 16 + x, dy * 16 + y, dz * 16 + z)] =
          id >= 0 && id < registryCount ? id : airId;
      }
    }
  }
  return new Uint8Array(out.buffer);
}

function captureRowIncremental(sections, airId, registryCount) {
  const out = new Uint32Array(8000);
  for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) {
    const section = sections[sectionIndex(dx + 1, dy + 1, dz + 1)];
    const x0 = dx < 0 ? 14 : 0, x1 = dx > 0 ? 1 : 15;
    const y0 = dy < 0 ? 14 : 0, y1 = dy > 0 ? 1 : 15;
    const z0 = dz < 0 ? 14 : 0, z1 = dz > 0 ? 1 : 15;
    for (let y = y0; y <= y1; y++) for (let z = z0; z <= z1; z++) {
      const row = (y << 8) | (z << 4);
      const bits = section?.bits || 0;
      const valuesPerWord = bits === 0 ? 1 : Math.floor(64 / bits);
      let wordIndex = bits === 0 ? 0 : Math.floor((row | x0) / valuesPerWord);
      let shift = bits === 0 ? 0 : ((row | x0) - wordIndex * valuesPerWord) * bits;
      let slotsLeft = bits === 0 ? 0 : valuesPerWord - (row | x0) % valuesPerWord;
      let word = bits === 0 ? 0n : section.raw[wordIndex];
      const mask = bits === 0 ? 0n : (1n << BigInt(bits)) - 1n;
      for (let x = x0; x <= x1; x++) {
        const rawValue = bits === 0 ? 0 : Number((word >> BigInt(shift)) & mask);
        const id = section ? section.ids[rawValue] : airId;
        out[gridIndex(dx * 16 + x, dy * 16 + y, dz * 16 + z)] =
          id >= 0 && id < registryCount ? id : airId;
        if (bits !== 0 && x < x1) {
          if (--slotsLeft === 0) {
            word = section.raw[++wordIndex];
            shift = 0;
            slotsLeft = valuesPerWord;
          } else {
            shift += bits;
          }
        }
      }
    }
  }
  return new Uint8Array(out.buffer);
}

function makeSections(seed, emptyCenter = false, bits = 4) {
  let state = seed >>> 0;
  const random = () => {
    state ^= state << 13; state ^= state >>> 17; state ^= state << 5;
    return state >>> 0;
  };
  const sections = Array.from({length: 27}, (_, i) => {
    if ((random() & 15) === 0 || (emptyCenter && i === 13)) return null;
    const ids = Array.from({length: Math.min(1 << bits, 10)}, (_, i) =>
      i === 8 ? 65535 : i === 9 ? -1 : i);
    const values = new Uint16Array(4096);
    for (let n = 0; n < values.length; n++) values[n] = random() % ids.length;
    return {ids, bits, values, raw: makeStorage(values, bits)};
  });
  return sections;
}

test("legacy and fast packed-state capture hashes match for randomized neighbors and boundaries", () => {
  for (let seed = 1; seed <= 160; seed++) {
    const sections = makeSections(seed, seed % 7 === 0);
    const expected = hash(captureStates(sections, false, 0, 8192));
    assert.equal(hash(captureStates(sections, true, 0, 8192)), expected, `fast seed=${seed}`);
    assert.equal(hash(captureRowIncremental(sections, 0, 8192)), expected, `row seed=${seed}`);
  }
});

test("row-incremental packed reads match across palette storage widths", () => {
  for (let bits = 1; bits <= 8; bits++) for (let seed = 1; seed <= 8; seed++) {
    const sections = makeSections(seed * 97 + bits, seed % 5 === 0, bits);
    const expected = hash(captureStates(sections, false, 0, 8192));
    assert.equal(hash(captureRowIncremental(sections, 0, 8192)), expected, `bits=${bits} seed=${seed}`);
  }
});

test("legacy and fast output hashes preserve fluid, slab and block-entity states", () => {
  const states = makeSections(0x263, true);
  states[13] = {ids: [0], bits: 1, values: new Uint16Array(4096), raw: makeStorage(new Uint16Array(4096), 1)};
  // Fluid, slab, and block-entity render states use distinct registry ids; capture must preserve ids.
  states[13].ids = [0, 1001, 1002, 1003];
  states[13].values.fill(0);
  states[13].values[localIndex(0, 0, 0)] = 1;
  states[13].values[localIndex(1, 0, 0)] = 2;
  states[13].values[localIndex(2, 0, 0)] = 3;
  states[13].bits = 2;
  states[13].raw = makeStorage(states[13].values, 2);
  assert.equal(rawGet(states[13].raw, 2, localIndex(1, 0, 0)), 2);
  assert.equal(states[13].ids[rawGet(states[13].raw, 2, localIndex(1, 0, 0))], 1002);
  const fast = captureStates(states, true, 0, 8192);
  const old = captureStates(states, false, 0, 8192);
  assert.equal(hash(fast), hash(old));
  const grid = new Uint32Array(fast.buffer);
  assert.equal(grid[gridIndex(0, 0, 0)], 1001, "fluid fixture registry id survives the snapshot");
  assert.equal(grid[gridIndex(1, 0, 0)], 1002, "slab fixture registry id survives the snapshot");
  assert.equal(grid[gridIndex(2, 0, 0)], 1003, "block-entity host state id survives the snapshot");
});

test("light border nibbles retain exact packed bytes", () => {
  let state = 0x32ab91;
  const random = () => ((state = (Math.imul(state, 1664525) + 1013904223) >>> 0) >>> 24) & 15;
  const layers = Array.from({length: 54}, (_, i) => i % 11 === 0 ? null
    : Uint8Array.from({length: 4096}, () => (random() << 4) | random()));
  const old = new Uint8Array(8000), fast = new Uint8Array(8000);
  for (let y = -1; y <= 16; y++) for (let z = -1; z <= 16; z++) for (let x = -1; x <= 16; x++) {
    const sx = x < 0 ? 0 : x > 15 ? 2 : 1;
    const sy = y < 0 ? 0 : y > 15 ? 2 : 1;
    const sz = z < 0 ? 0 : z > 15 ? 2 : 1;
    const layer = layers[(sy * 3 + sz) * 3 + sx];
    const lx = (x + 16) & 15, ly = (y + 16) & 15, lz = (z + 16) & 15;
    const expected = layer ? layer[localIndex(lx, ly, lz)] : 0;
    const at = gridIndex(x, y, z);
    old[at] = expected;
    fast[at] = layer ? layer[localIndex(lx, ly, lz)] : 0;
  }
  assert.equal(hash(old), hash(fast));
});
