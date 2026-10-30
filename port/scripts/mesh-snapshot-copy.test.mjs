import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";

test("mesh facade owns each snapshot across neighbor, light and biome updates", async () => {
  const jobs = [];
  const context = vm.createContext({URLSearchParams, Uint8Array, Uint16Array, Uint32Array,
    Int32Array, Float32Array, BigInt, Promise,
    GaiusKernelMirror: {enabled: () => true},
    __gaiusKernels: {enabled: () => true, runtime: async () => ({available: () => true})},
    GaiusMeshJob: {enabled: () => true, estimateResultBytes: () => 0,
      session: (_, epoch) => ({epoch, preload: async () => {},
        mesh: (_, input, options) => new Promise(resolve => jobs.push({input, options, resolve}))})},
  });
  vm.runInContext(readFileSync(new URL("../web/kernels/mesh-kernel-job.js", import.meta.url), "utf8"), context);
  const facade = context.__gaiusMeshKernel;
  facade.ready();
  for (let i = 0; i < 8; i++) await Promise.resolve();
  assert(facade.ready());
  facade.setTable(new Uint8Array(1), 1, 1);
  const header = new Int32Array(14);
  header[0] = 1; header[1] = 1; header[3] = 1; header[5] = -3; header[6] = 4; header[7] = 2; header[10] = 1;
  const states = new Uint16Array(8000), light = new Uint8Array(8000), biome = new Uint8Array(216);
  const palette = new Int32Array(5), swamp = new Uint8Array(50), floats = new Float32Array(9);
  const submit = () => facade.submitJob(header, states, light, biome, palette, swamp, floats);
  states[0] = 7; light[0] = 0x11; biome[0] = 0;
  assert(submit());
  // Delivery of a neighbor and its light/biome data mutates the reusable Java bridge arrays.
  states[0] = 19; light[0] = 0xf2; biome[0] = 1;
  palette[0] = 0x123456; swamp[0] = 1; floats[6] = 16;
  header[0] = 2; header[10] = 2;
  assert(submit());
  assert.deepEqual(jobs.map(j => [j.input.states[0], j.input.light[0], j.input.biomeQuarts[0]]),
    [[7, 0x11, 0], [19, 0xf2, 1]]);
  assert.deepEqual(jobs.map(j => [j.input.biomePalette[0], j.input.swampMask[0], j.input.camera[0]]),
    [[0, 0, 0], [0x123456, 1, 16]]);
  states.fill(0); light.fill(0); biome.fill(0); palette.fill(0); swamp.fill(0); floats.fill(0);
  assert.equal(jobs[1].input.states[0], 19);
  assert.equal(jobs[1].input.light[0], 0xf2);
  assert.notEqual(jobs[0].input.states.buffer, jobs[1].input.states.buffer);
  assert.equal(jobs[0].options.key, jobs[1].options.key);
  assert(jobs[1].options.version > jobs[0].options.version);
});

test("light snapshot reads published light before and after a queued layer is published", () => {
  // Execute the production loop with only Java declarations/casts removed. The listener test
  // double models vanilla's distinct visible and queued maps, not the snapshot algorithm.
  const source = readFileSync(new URL("../mc/shared/canonical/src/java/dev/gaius/browser/kernel/mesh/SectionSnapshot.java",
    import.meta.url), "utf8");
  const start = source.indexOf("    private static void captureLight(");
  const end = source.indexOf("    // --- biomes", start);
  assert(start >= 0 && end > start);
  const body = source.slice(start, end)
    .replace(/private static void captureLight\([^)]*\)/, "function captureLight(b, engine, sx, sy, sz)")
    .replace(/LayerLightEventListener /g, "const ")
    .replace(/BlockPos\.MutableBlockPos cursor/, "const cursor")
    .replace(/\bint /g, "let ")
    .replace(/\(byte\) /g, "");
  let published = 1;
  const listener = (sky) => ({
    getDataLayerData() { throw new Error("queued light is not a renderer snapshot"); },
    getLightValue(pos) { return (published + (sky ? 5 : 0) + (pos.x & 3) + (pos.y & 3) + (pos.z & 3)) & 15; },
  });
  const block = listener(false), sky = listener(true);
  const context = vm.createContext({LightLayer: {BLOCK: 0, SKY: 1},
    BlockPos: {MutableBlockPos: class {set(x, y, z) {Object.assign(this, {x, y, z});}}},
    MeshJobBuffers: {regionIndex: (x, y, z) => ((y + 2) * 20 + z + 2) * 20 + x + 2},
  });
  vm.runInContext(body, context);
  const buffer = {light: new Uint8Array(8000)};
  const engine = {getLayerListener: kind => kind === 0 ? block : sky};
  const verify = () => {
    let differences = 0;
    for (let y = -1; y <= 16; y++) for (let z = -1; z <= 16; z++) for (let x = -1; x <= 16; x++) {
      const pos = {x: -48 + x, y: -64 + y, z: 32 + z};
      const expected = (sky.getLightValue(pos) << 4) | block.getLightValue(pos);
      if (buffer.light[((y + 2) * 20 + z + 2) * 20 + x + 2] !== expected) differences++;
    }
    assert.equal(differences, 0, "all 5832 light samples must match vanilla's listener");
  };
  context.captureLight(buffer, engine, -3, -4, 2);
  verify();
  const before = buffer.light.slice();
  published = 9; // Publish the queued neighbor/light update; the next request must observe it.
  context.captureLight(buffer, engine, -3, -4, 2);
  verify();
  assert.notDeepEqual(buffer.light, before);
});
