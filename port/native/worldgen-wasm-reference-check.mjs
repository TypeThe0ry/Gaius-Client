#!/usr/bin/env node
// Compare existing JVM references with a wasm module through the production job codec.
import assert from "node:assert/strict";
import {readFileSync, readdirSync} from "node:fs";
import {join} from "node:path";
import vm from "node:vm";

const [modulePath, references] = process.argv.slice(2);
assert(modulePath && references, "usage: worldgen-wasm-reference-check.mjs <wasm> <reference-dir>");
const context = vm.createContext({WebAssembly, DataView, Uint8Array, Uint16Array, Uint32Array,
  Int32Array, ArrayBuffer, BigInt, TextEncoder, TextDecoder, Math});
vm.runInContext(readFileSync(new URL("../web/kernels/worldgen-job.js", import.meta.url), "utf8"), context);
const codec = context.GaiusWorldgenJob;
const {exports: wasm} = new WebAssembly.Instance(new WebAssembly.Module(readFileSync(modulePath)), {});
assert.equal(wasm.gaius_abi_version(), codec.ABI_VERSION);
let sequence = 0;
function execute(name, frame) {
  const input = new Uint8Array(frame);
  const pointer = wasm.alloc(input.length) >>> 0;
  assert(pointer, "input allocation");
  let descriptor;
  try {
    new Uint8Array(wasm.memory.buffer, pointer, input.length).set(input);
    descriptor = wasm[name](pointer, input.length) >>> 0;
    assert(descriptor, "result descriptor");
    const view = new DataView(wasm.memory.buffer);
    const status = view.getUint32(descriptor, true);
    const start = view.getUint32(descriptor + 4, true);
    const length = view.getUint32(descriptor + 8, true);
    const output = new Uint8Array(wasm.memory.buffer, start, length).slice();
    assert.equal(status, 0, new TextDecoder().decode(output));
    codec.readResult(output.buffer, sequence);
    return output.buffer;
  } finally {
    if (descriptor) wasm.release(descriptor);
    wasm.dealloc(pointer, input.length);
  }
}
const generator = codec.generator(1, readFileSync(join(references, "overworld.gwir")));
const info = codec.readLoadInfo(execute("run_load_generator", codec.encodeLoad(generator, ++sequence)), sequence);
const height = info.sectionCount * 16;
const volume = 256 * height;
const biomeCount = info.sectionCount * 64;
const baseWords = 2 + volume * 3 + biomeCount + 512;
const files = readdirSync(references).filter(name => name.endsWith(".ref")).sort();
assert(files.length > 0, "no vanilla references");
let blocks = 0, biomes = 0, heights = 0;
for (const name of files) {
  const bytes = readFileSync(join(references, name));
  assert.equal(bytes.length % 4, 0, "truncated reference");
  const words = Array.from({length: bytes.length / 4}, (_, index) => bytes.readInt32LE(index * 4));
  assert(words.length >= baseWords, "reference shape");
  const [x, z] = words;
  const ringBiomes = words.length > baseWords ? words.slice(baseWords) : null;
  for (const surface of [false, true]) {
    const result = execute("run_terrain", codec.encodeTerrain(generator, x, z, ++sequence,
      {surface, biomes: true, ringBiomes}, false));
    const chunk = codec.readChunk(result, sequence);
    assert.equal(chunk.chunkX, x); assert.equal(chunk.chunkZ, z);
    assert.equal(chunk.minY, info.minY); assert.equal(chunk.sectionCount, info.sectionCount);
    const start = 2 + volume + biomeCount + (surface ? volume : 0);
    for (let cz = 0; cz < 16; cz++) for (let cx = 0; cx < 16; cx++) for (let y = 0; y < height; y++) {
      const actual = codec.sectionState(chunk.sections[y >> 4], cx, y & 15, cz);
      assert.equal(actual, words[start + y + (cx + cz * 16) * height],
        `${name} surface=${surface} block ${cx},${y + info.minY},${cz}`);
      blocks++;
    }
    assert.equal(chunk.biomes.length, biomeCount);
    chunk.biomes.forEach((id, index) => assert.equal(id, words[2 + volume + index], `${name} biome ${index}`));
    biomes += biomeCount;
    if (surface) {
      [...chunk.worldSurface, ...chunk.oceanFloor].forEach((value, index) =>
        assert.equal(value, words[baseWords - 512 + index], `${name} height ${index}`));
      heights += 512;
    }
  }
}
console.log(JSON.stringify({verdict: "PASS", references, modulePath, chunks: files.length, jobs: sequence,
  blocks, biomes, heights, mismatches: 0}));
