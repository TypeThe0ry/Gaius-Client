import assert from "node:assert/strict";

export function effectiveLight(layers, section, cell, sky) {
  let layer = layers[section];
  let index = cell;
  if (!layer && sky) {
    layer = layers.slice(section + 1).find((value) => value !== null);
    if (!layer) return 15;
    index &= 255;
  }
  return layer ? (layer[index >> 1] >> ((index & 1) * 4)) & 15 : 0;
}

export function compareLightColumn(expected, actual, {sectionCount, minSection, sky}) {
  assert.equal(actual.tableMissing, false, "light table was not loaded");
  assert.equal(actual.lightSections, sectionCount + 2, "light result height differs");
  const mismatches = {block: 0, sky: 0};
  const samples = [];
  for (const kind of sky ? ["block", "sky"] : ["block"]) {
    for (let section = 0; section < sectionCount + 2; section++) {
      for (let cell = 0; cell < 4096; cell++) {
        const wanted = effectiveLight(expected[kind], section, cell, kind === "sky");
        const observed = effectiveLight(actual[kind], section, cell, kind === "sky");
        if (wanted !== observed) {
          mismatches[kind]++;
          if (samples.length < 12) samples.push({kind, x: cell & 15, z: (cell >> 4) & 15,
            y: (minSection + section - 1) * 16 + (cell >> 8), expected: wanted, actual: observed});
        }
      }
    }
  }
  return {mismatches, samples};
}
