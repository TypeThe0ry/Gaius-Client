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

test("mesh facade reports results left behind by a bounded poll", async () => {
  const pending = [];
  const context = vm.createContext({URLSearchParams, Uint8Array, Uint16Array, Uint32Array,
    Int32Array, Float32Array, BigInt, Promise,
    GaiusKernelMirror: {enabled: () => true},
    __gaiusKernels: {enabled: () => true, runtime: async () => ({available: () => true})},
    GaiusMeshJob: {enabled: () => true, estimateResultBytes: () => 0,
      session: (_, epoch) => ({epoch, preload: async () => {},
        mesh: (_, input) => new Promise(resolve => pending.push({input, resolve}))})},
  });
  vm.runInContext(readFileSync(new URL("../web/kernels/mesh-kernel-job.js", import.meta.url), "utf8"), context);
  const facade = context.__gaiusMeshKernel;
  facade.ready();
  for (let i = 0; i < 8; i++) await Promise.resolve();
  facade.setTable(new Uint8Array(1), 1, 1);
  const submit = (ticket, x) => {
    const header = new Int32Array(14);
    header[0] = ticket; header[1] = 1; header[3] = 1;
    header[5] = x; header[6] = 4; header[7] = 2; header[10] = ticket;
    return facade.submitJob(header, new Uint16Array(8000), new Uint8Array(8000),
      new Uint8Array(216), new Int32Array(5), new Uint8Array(50), new Float32Array(9));
  };
  assert(submit(1, -3));
  assert(submit(2, -2));
  for (const job of pending) {
    job.resolve({tableEpoch: 1, requestSeq: job.input.requestSeq, section: job.input.section,
      statusCode: 0, layers: {solid: {quadCount: 0}, cutout: {quadCount: 0},
        translucent: {quadCount: 0}}, visibilityLo: 0, visibilityHi: 0, detail: 0});
  }
  for (let i = 0; i < 4; i++) await Promise.resolve();
  assert.equal(facade.hasResults(), true);
  assert.equal(facade.poll().ticket, 1);
  assert.equal(facade.hasResults(), true, "one bounded drain must leave later records visible");
  assert.equal(facade.poll().ticket, 2);
  assert.equal(facade.hasResults(), false);
});

test("light snapshot reads published light before and after a queued layer is published", () => {
  // Execute the production loop with only Java declarations/casts removed. The listener test
  // double models vanilla's distinct visible and queued maps, not the snapshot algorithm.
  const source = readFileSync(new URL("../mc/shared/canonical/src/java/dev/gaius/browser/kernel/mesh/SectionSnapshot.java",
    import.meta.url), "utf8");
  const start = source.indexOf("    private static void captureVanillaLight(");
  const end = source.indexOf("    // --- biomes", start);
  assert(start >= 0 && end > start);
  const body = source.slice(start, end)
    .replace(/private static void captureVanillaLight\([^)]*\)/, "function captureVanillaLight(b, engine, sx, sy, sz)")
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
  context.captureVanillaLight(buffer, engine, -3, -4, 2);
  verify();
  const before = buffer.light.slice();
  published = 9; // Publish the queued neighbor/light update; the next request must observe it.
  context.captureVanillaLight(buffer, engine, -3, -4, 2);
  verify();
  assert.notDeepEqual(buffer.light, before);
});

test("fast light snapshot reads visible block and sky nibbles with vanilla fallback for missing sky data", () => {
  const source = readFileSync(new URL("../mc/shared/canonical/src/java/dev/gaius/browser/kernel/mesh/SectionSnapshot.java",
    import.meta.url), "utf8");
  const start = source.indexOf("    private static void captureLight(");
  const end = source.indexOf("    private static void captureVanillaLight(", start);
  assert(start >= 0 && end > start);
  const body = source.slice(start, end)
    .replace(/private static void captureLight\([^)]*\)/, "function captureLight(b, engine, sx, sy, sz, fast)")
    .replace(/LightEngine<\?, \?> /g, "const ")
    .replace(/LayerLightEventListener /g, "const ")
    .replace(/DataLayer /g, "const ")
    .replace(/byte\[\] /g, "const ")
    .replace(/BlockPos\.MutableBlockPos cursor/, "const cursor")
    .replace(/\bboolean /g, "let ")
    .replace(/\bint /g, "let ")
    .replace(/\(byte\) /g, "");
  const key = (x, y, z) => `${x},${y},${z}`;
  const dataLayer = (fn, homogeneous = false) => {
    const data = new Uint8Array(2048);
    const levels = new Uint8Array(4096);
    for (let y = 0; y < 16; y++) for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
      const i = (y << 8) | (z << 4) | x, level = fn(x, y, z) & 15;
      levels[i] = level;
      data[i >> 1] |= level << ((i & 1) << 2);
    }
    return {levels, data, isDefinitelyHomogenous: () => homogeneous,
      get: (x, y, z) => levels[(y << 8) | (z << 4) | x], getData: () => data};
  };
  let published = 1;
  const visible = new Map(), visibleSky = new Map(), queued = new Map();
  const fillLayers = () => {
    visible.clear(); visibleSky.clear();
    for (let sy = -5; sy <= -3; sy++) for (let sz = 1; sz <= 3; sz++) for (let sx = -4; sx <= -2; sx++) {
      const homogeneous = sx === -3 && sy === -4 && sz === 2;
      const layer = dataLayer(homogeneous ? (() => published) :
        ((x, y, z) => (published + sx + sy + sz + x + 2 * y + 3 * z) & 15), homogeneous);
      const section = key(sx, sy, sz);
      visible.set(section, layer);
      if (section !== key(-4, -5, 1)) {
        visibleSky.set(section, dataLayer(homogeneous ? (() => published + 5) :
          ((x, y, z) => (published + sx + 2 * sy + 3 * sz + x + 2 * y + 3 * z + 5) & 15), homogeneous));
      }
    }
  };
  fillLayers();
  queued.set(key(-3, -4, 2), dataLayer(() => 14, true));
  const position = (x, y, z) => ({x, y, z});
  const block = {getLightValue: pos => {
    const layer = visible.get(key(Math.floor(pos.x / 16), Math.floor(pos.y / 16), Math.floor(pos.z / 16)));
    return layer ? layer.get(((pos.x % 16) + 16) % 16, ((pos.y % 16) + 16) % 16,
      ((pos.z % 16) + 16) % 16) : 0;
  }};
  let skyQueries = 0;
  const sky = {getLightValue: pos => {
    skyQueries++;
    const layer = visibleSky.get(key(Math.floor(pos.x / 16), Math.floor(pos.y / 16), Math.floor(pos.z / 16)));
    if (layer) return layer.get(((pos.x % 16) + 16) % 16, ((pos.y % 16) + 16) % 16,
      ((pos.z % 16) + 16) % 16);
    return (published + pos.x + 2 * pos.y + 3 * pos.z) & 15;
  }};
  const engine = {blockEngine: {}, skyEngine: {}, getLayerListener: kind => kind === 0 ? block : sky};
  const context = vm.createContext({LightLayer: {BLOCK: 0, SKY: 1},
    BlockPos: {MutableBlockPos: class {set(x, y, z) {Object.assign(this, {x, y, z});}}},
    SectionPos: {asLong: (x, y, z) => key(x, y, z)},
    GaiusLightColumnAccess: {engine: (e, kind) => kind === 0 ? e.blockEngine : e.skyEngine,
      visibleLayer: (lightEngine, section) => lightEngine === engine.blockEngine
        ? visible.get(section) : visibleSky.get(section)},
    MeshJobBuffers: {regionIndex: (x, y, z) => ((y + 2) * 20 + z + 2) * 20 + x + 2}});
  vm.runInContext(body, context);
  const buffer = {light: new Uint8Array(8000)};
  const expected = () => {
    const out = new Uint8Array(8000);
    for (let y = -1; y <= 16; y++) for (let z = -1; z <= 16; z++) for (let x = -1; x <= 16; x++) {
      const pos = position(-48 + x, -64 + y, 32 + z);
      out[((y + 2) * 20 + z + 2) * 20 + x + 2] = (sky.getLightValue(pos) << 4) | block.getLightValue(pos);
    }
    return out;
  };
  context.captureLight(buffer, engine, -3, -4, 2, true);
  const initialSkyFallbacks = skyQueries;
  assert.deepEqual(buffer.light, expected(), "direct nibbles equal visible listener values across all section edges");
  assert.equal(initialSkyFallbacks, 1, "only the one missing sky section uses the listener fallback");
  const before = buffer.light.slice();
  published = 7;
  fillLayers();
  skyQueries = 0;
  context.captureLight(buffer, engine, -3, -4, 2, true);
  const updatedSkyFallbacks = skyQueries;
  assert.deepEqual(buffer.light, expected(), "published block and vanilla sky updates are visible on recapture");
  assert.equal(updatedSkyFallbacks, 1, "sky listener remains limited to missing visible data");
  assert.notDeepEqual(buffer.light, before);
});

test("opt-in zero-copy facade frames the reusable bridge views before they are reused", async () => {
  const codecContext = vm.createContext({ArrayBuffer, BigInt, DataView, Int32Array, Math, Number,
    Uint8Array, Uint16Array, Uint32Array, Float32Array, Promise, String, TypeError, RangeError});
  vm.runInContext(readFileSync(new URL("../web/kernels/mesh-job.js", import.meta.url), "utf8"), codecContext);
  const frameMesh = codecContext.GaiusMeshJob.meshSection;
  const jobs = [];
  const context = vm.createContext({URLSearchParams, Uint8Array, Uint16Array, Uint32Array,
    Int32Array, Float32Array, BigInt, Promise, location: {search: "?gaiusMeshZeroCopy=1"},
    GaiusKernelMirror: {enabled: () => true},
    __gaiusKernels: {enabled: () => true, runtime: async () => ({available: () => true})},
    GaiusMeshJob: {enabled: () => true, estimateResultBytes: () => 0,
      session: (_, epoch) => ({epoch, preload: async () => {},
        mesh: (_, input) => {
          const framed = frameMesh(input, 1, null);
          jobs.push({input, framed});
          return new Promise(() => {});
        }})},
  });
  vm.runInContext(readFileSync(new URL("../web/kernels/mesh-kernel-job.js", import.meta.url), "utf8"), context);
  const facade = context.__gaiusMeshKernel;
  facade.ready();
  for (let i = 0; i < 8; i++) await Promise.resolve();
  facade.setTable(new Uint8Array(1), 1, 1);
  const header = new Int32Array(14);
  header[0] = 1; header[1] = 1; header[3] = 1; header[10] = 1;
  const states = new Uint16Array(8000), light = new Uint8Array(8000), biome = new Uint8Array(216);
  const palette = new Int32Array(5), swamp = new Uint8Array(50), floats = new Float32Array(9);
  states[0] = 23;
  assert(facade.submitJob(header, states, light, biome, palette, swamp, floats));
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].input.states.buffer, states.buffer, "zero-copy mode forwards the bridge buffer");
  assert.equal(jobs[0].input.states.byteOffset, states.byteOffset);
  states[0] = 99;
  assert.equal(new Uint16Array(jobs[0].framed, 16 + 96, 8000)[0], 23,
    "synchronous framing owns the transferred bytes before the bridge reuses its array");
});
