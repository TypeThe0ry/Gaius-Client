import {performance} from "node:perf_hooks";

const iterations = Number(process.argv[2] || 2000);
const rng = (() => {
  let s = 0x263c0de;
  return () => ((s = (Math.imul(s, 1664525) + 1013904223) >>> 0) >>> 24) & 15;
})();
const sections = Array.from({length: 27}, () => {
  const ids = Int32Array.from({length: 16}, (_, i) => i === 15 ? 0 : i + 100);
  const words = new Uint32Array(512);
  for (let i = 0; i < 4096; i++) words[i >>> 3] |= rng() << ((i & 7) << 2);
  const states = Array.from(ids, id => ({id}));
  const unpacked = new Uint8Array(4096);
  for (let i = 0; i < unpacked.length; i++) unpacked[i] = (words[i >>> 3] >>> ((i & 7) << 2)) & 15;
  return {ids, states, words, unpacked};
});
const idsJob = new Uint16Array(8000);
const oldIdsJob = new Uint16Array(8000);

function storageGet(section, index) {
  return (section.words[index >>> 3] >>> ((index & 7) << 2)) & 15;
}

function paletteValue(section, index) {
  return section.states[index];
}

function registryId(state) {
  return state.id;
}

function legacyCapture() {
  let nonAir = 0;
  for (let y = -2; y <= 17; y++) for (let z = -2; z <= 17; z++) for (let x = -2; x <= 17; x++) {
    const dx = x < 0 ? -1 : x > 15 ? 1 : 0;
    const dy = y < 0 ? -1 : y > 15 ? 1 : 0;
    const dz = z < 0 ? -1 : z > 15 ? 1 : 0;
    const section = sections[((dy + 1) * 3 + (dz + 1)) * 3 + (dx + 1)];
    const index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    const value = dx === 0 && dy === 0 && dz === 0
      ? section.unpacked[index] : storageGet(section, index);
    const id = registryId(paletteValue(section, value));
    oldIdsJob[((y + 2) * 20 + z + 2) * 20 + x + 2] = id;
    if (dx === 0 && dy === 0 && dz === 0 && id !== 0) nonAir++;
  }
  return nonAir;
}

function fastCapture() {
  let nonAir = 0;
  for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) {
    const section = sections[((dy + 1) * 3 + (dz + 1)) * 3 + (dx + 1)];
    const x0 = dx < 0 ? 14 : 0, x1 = dx > 0 ? 1 : 15;
    const y0 = dy < 0 ? 14 : 0, y1 = dy > 0 ? 1 : 15;
    const z0 = dz < 0 ? 14 : 0, z1 = dz > 0 ? 1 : 15;
    for (let y = y0; y <= y1; y++) for (let z = z0; z <= z1; z++) {
      const row = (y << 8) | (z << 4);
      for (let x = x0; x <= x1; x++) {
        const value = storageGet(section, row | x);
        const id = section.ids[value];
        const out = ((dy * 16 + y + 2) * 20 + (dz * 16 + z + 2)) * 20 + dx * 16 + x + 2;
        idsJob[out] = id;
        if (dx === 0 && dy === 0 && dz === 0 && id !== 0) nonAir++;
      }
    }
  }
  return nonAir;
}

function bench(fn) {
  for (let i = 0; i < 100; i++) fn();
  const start = performance.now();
  let checksum = 0;
  for (let i = 0; i < iterations; i++) checksum += fn();
  return {ms: performance.now() - start, checksum};
}

const old = bench(legacyCapture);
const fast = bench(fastCapture);
const oldMsPerCapture = old.ms / iterations;
const fastMsPerCapture = fast.ms / iterations;
const parity = idsJob.every((value, i) => value === oldIdsJob[i]);
console.log(JSON.stringify({
  workload: "synthetic 27-section neighborhood; 4-bit palettes; 20^3 state snapshot",
  runtime: process.version,
  iterations,
  legacy: {ms: old.ms, usPerCapture: oldMsPerCapture * 1000, perCellPaletteAndRegistryLookups: 8000},
  fast: {ms: fast.ms, usPerCapture: fastMsPerCapture * 1000, paletteAndRegistryLookups: 27 * 16},
  captureTimeReductionPercent: (1 - fastMsPerCapture / oldMsPerCapture) * 100,
  outputBytesPerCapture: 8000 * 2,
  outputHashEqual: parity,
  checksumEqual: old.checksum === fast.checksum,
}, null, 2));
