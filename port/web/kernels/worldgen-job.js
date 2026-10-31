// Gaius worldgen job codec: frames load_generator / biomes / terrain / surface jobs for the
// wasm worldgen kernel and reads their results. The layout mirrors
// port/native/crates/gaius-kernel-abi (job/result headers) and
// port/native/crates/gaius-worldgen-wasm/src/job.rs (payloads); all integers are little-endian.
//
// This file is a plain script so it can be inlined next to kernel-pool.js. It installs
// globalThis.GaiusWorldgenJob:
//
//   const module = await GaiusWorldgenJob.compileModule({simd: simdBytes, baseline: baseBytes});
//   const pool = await GaiusKernelPool.create({module, workerSource, name: "worldgen"});
//   const gen = GaiusWorldgenJob.generator(0, irBytes);          // key 0 = this dimension
//   const chunk = await GaiusWorldgenJob.terrain(pool, gen, cx, cz, {surface: true, beard});
//   chunk.sections[i].palette / .indices, chunk.worldSurface, chunk.oceanFloor, ...
//
// Every worker owns its own wasm instance, so a generator is loaded lazily per worker. The first
// `inlineJobs` jobs of a generator (default 8: enough to reach every worker of a pool) carry the
// IR; later jobs are sent without it and resent with it when a worker answers
// "generator-missing" (a respawned worker, for example).
// beard is {affected: [minX, minY, minZ, maxX, maxY, maxZ] | null,
//           rigids: Int32Array of [minX, minY, minZ, maxX, maxY, maxZ, adjustment, groundDelta]*,
//           junctions: Int32Array of [sourceX, sourceGroundY, sourceZ]*}
// (adjustment: 0 none, 1 bury, 2 beard_thin, 3 beard_box, 4 encapsulate).
// ringBiomes (terrain and surface options) are the global biome ids the neighbouring chunks store
// around the chunk, 20 quart columns x the level height in quarts (job.rs documents the order); the
// surface rules read them instead of computing those quarts. A ring naming a biome the generator
// does not know fails the job with a "worldgen-fallback:" message.
(function (global) {
  "use strict";

  const ABI_VERSION = 1;
  const JOB_MAGIC = 0x424a4b47;     // "GKJB" read as little-endian u32
  const RESULT_MAGIC = 0x53524b47;  // "GKRS"
  const HEADER_LEN = 16;
  const KINDS = Object.freeze({
    load_generator: 0x0401,
    biomes: 0x0402,
    terrain: 0x0403,
    surface: 0x0404,
  });
  const FLAG_SURFACE = 1;
  const FLAG_BIOMES = 2;
  const FLAG_RING_BIOMES = 4;
  const MISSING_PREFIX = "generator-missing:";
  const FNV_OFFSET = 0xcbf29ce484222325n;
  const FNV_PRIME = 0x100000001b3n;
  const MASK64 = (1n << 64n) - 1n;

  // (module (func (result v128) i32.const 0 i8x16.splat i8x16.popcnt)): validates only with SIMD.
  const SIMD_PROBE = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0,
    10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11]);

  const pad = (offset, align) => Math.ceil(offset / align) * align;

  function simdSupported() {
    try {
      return global.WebAssembly.validate(SIMD_PROBE);
    } catch (_) {
      return false;
    }
  }

  // Picks the module variant this engine can run: {simd, baseline} are bytes or ArrayBuffers.
  function pickModule(variants) {
    return simdSupported() && variants.simd ? {name: "simd", bytes: variants.simd}
      : {name: "baseline", bytes: variants.baseline};
  }

  async function compileModule(variants) {
    const chosen = pickModule(variants);
    const module = await global.WebAssembly.compile(chosen.bytes);
    module.gaiusVariant = chosen.name;
    return module;
  }

  // FNV-1a 64 of the IR bytes, as an unsigned BigInt (the generator reference hash).
  function fnv1a64(bytes) {
    let h = FNV_OFFSET;
    for (let i = 0; i < bytes.length; i++) {
      h ^= BigInt(bytes[i]);
      h = (h * FNV_PRIME) & MASK64;
    }
    return h;
  }

  function toBytes(value) {
    if (value instanceof Uint8Array) return value;
    if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    return new Uint8Array(value);
  }

  // A generator handle: key (one slot per dimension on the page), the IR, its hash and how many
  // more jobs carry the IR inline.
  function generator(key, ir, options) {
    const bytes = toBytes(ir);
    const inlineJobs = options && Number.isFinite(options.inlineJobs) ? options.inlineJobs : 8;
    return Object.seal({key: key >>> 0, ir: bytes, hash: fnv1a64(bytes), inlineLeft: inlineJobs});
  }

  // Builds a framed job: header, generator reference, then `bodyLength` bytes written by `writeBody`.
  function frame(kind, jobId, gen, inline, bodyLength, writeBody) {
    const irLength = inline ? gen.ir.length : 0;
    const bodyAt = pad(24 + irLength, 8);
    const payloadLength = bodyAt + bodyLength;
    const buffer = new ArrayBuffer(HEADER_LEN + payloadLength);
    const view = new DataView(buffer);
    view.setUint32(0, JOB_MAGIC, true);
    view.setUint16(4, ABI_VERSION, true);
    view.setUint16(6, kind, true);
    view.setUint32(8, jobId >>> 0, true);
    view.setUint32(12, payloadLength, true);
    const payload = new DataView(buffer, HEADER_LEN);
    payload.setUint32(0, gen.key, true);
    payload.setUint32(4, inline ? 1 : 0, true);
    payload.setBigUint64(8, gen.hash, true);
    payload.setUint32(16, irLength, true);
    payload.setUint32(20, 0, true);
    if (inline) new Uint8Array(buffer, HEADER_LEN + 24, irLength).set(gen.ir);
    writeBody(payload, bodyAt);
    return buffer;
  }

  function beardLength(beard) {
    const rigids = beard && beard.rigids ? beard.rigids.length : 0;
    const junctions = beard && beard.junctions ? beard.junctions.length : 0;
    if (rigids % 8 !== 0) throw new RangeError("beard.rigids must hold whole [8 x int32] records");
    if (junctions % 3 !== 0) throw new RangeError("beard.junctions must hold whole [3 x int32] records");
    return 4 + 24 + 4 + 4 * rigids + 4 + 4 * junctions;
  }

  function writeBeard(view, at, beard) {
    const affected = beard && beard.affected;
    view.setUint32(at, affected ? 1 : 0, true);
    for (let i = 0; i < 6; i++) view.setInt32(at + 4 + 4 * i, affected ? affected[i] : 0, true);
    let p = at + 28;
    const rigids = beard && beard.rigids ? beard.rigids : [];
    view.setUint32(p, rigids.length / 8, true);
    p += 4;
    for (let i = 0; i < rigids.length; i++, p += 4) view.setInt32(p, rigids[i], true);
    const junctions = beard && beard.junctions ? beard.junctions : [];
    view.setUint32(p, junctions.length / 3, true);
    p += 4;
    for (let i = 0; i < junctions.length; i++, p += 4) view.setInt32(p, junctions[i], true);
    return p;
  }

  function ringOf(options) {
    const ring = options && options.ringBiomes;
    return ring && ring.length > 0 ? ring : null;
  }

  function ringLength(ring) {
    return ring ? 4 + 4 * ring.length : 0;
  }

  function writeRing(view, at, ring) {
    view.setUint32(at, ring.length, true);
    for (let i = 0; i < ring.length; i++) view.setUint32(at + 4 + 4 * i, ring[i] >>> 0, true);
    return at + 4 + 4 * ring.length;
  }

  function encodeLoad(gen, jobId, options) {
    const budgetKb = options && options.arenaBudgetKb ? options.arenaBudgetKb >>> 0 : 0;
    return frame(KINDS.load_generator, jobId, gen, true, 4, (view, at) => view.setUint32(at, budgetKb, true));
  }

  function encodeBiomes(gen, chunkX, chunkZ, jobId, inline) {
    return frame(KINDS.biomes, jobId, gen, inline, 8, (view, at) => {
      view.setInt32(at, chunkX, true);
      view.setInt32(at + 4, chunkZ, true);
    });
  }

  function encodeTerrain(gen, chunkX, chunkZ, jobId, options, inline) {
    const o = options || {};
    const ring = ringOf(o);
    const flags = (o.surface ? FLAG_SURFACE : 0) | (o.biomes ? FLAG_BIOMES : 0) | (ring ? FLAG_RING_BIOMES : 0);
    return frame(KINDS.terrain, jobId, gen, inline, 12 + beardLength(o.beard) + ringLength(ring), (view, at) => {
      view.setInt32(at, chunkX, true);
      view.setInt32(at + 4, chunkZ, true);
      view.setUint32(at + 8, flags, true);
      const end = writeBeard(view, at + 12, o.beard);
      if (ring) writeRing(view, end, ring);
    });
  }

  // chunkBytes: the chunk part of a terrain result (readChunk(...).bytes).
  function encodeSurface(gen, chunk, jobId, options, inline) {
    const o = options || {};
    const ring = ringOf(o);
    const bytes = toBytes(chunk.bytes || chunk);
    const head = 12 + beardLength(o.beard) + ringLength(ring);
    const chunkAt = pad(head, 8);
    return frame(KINDS.surface, jobId, gen, inline, chunkAt + bytes.length, (view, at) => {
      view.setInt32(at, chunk.chunkX | 0, true);
      view.setInt32(at + 4, chunk.chunkZ | 0, true);
      view.setUint32(at + 8, ring ? FLAG_RING_BIOMES : 0, true);
      const end = writeBeard(view, at + 12, o.beard);
      if (ring) writeRing(view, end, ring);
      new Uint8Array(view.buffer, view.byteOffset + at + chunkAt, bytes.length).set(bytes);
    });
  }

  // Validates a result header and returns {buffer, offset, length} of the payload.
  function readResult(buffer, jobId) {
    if (buffer.byteLength < HEADER_LEN) throw new Error("kernel result is shorter than its header");
    const view = new DataView(buffer);
    if (view.getUint32(0, true) !== RESULT_MAGIC) throw new Error("kernel result has a bad magic");
    if (view.getUint16(4, true) !== ABI_VERSION) throw new Error(`kernel result abi ${view.getUint16(4, true)}`);
    const status = view.getUint16(6, true);
    if (status !== 0) throw new Error(`kernel result status ${status}`);
    if (jobId !== undefined && view.getUint32(8, true) !== jobId >>> 0) {
      throw new Error(`kernel result for job ${view.getUint32(8, true)}, expected ${jobId >>> 0}`);
    }
    const length = view.getUint32(12, true);
    if (HEADER_LEN + length > buffer.byteLength) throw new Error("kernel result is truncated");
    return {buffer, offset: HEADER_LEN, length};
  }

  function readLoadInfo(buffer, jobId) {
    const r = readResult(buffer, jobId);
    const v = new DataView(buffer, r.offset, r.length);
    return {
      nodes: v.getUint32(0, true),
      noises: v.getUint32(4, true),
      states: v.getUint32(8, true),
      biomes: v.getUint32(12, true),
      pooledBytes: v.getUint32(16, true),
      sectionCount: v.getUint32(20, true),
      minY: v.getInt32(24, true),
      simd: v.getUint32(28, true) === 1,
    };
  }

  function readBiomes(buffer, jobId) {
    const r = readResult(buffer, jobId);
    const v = new DataView(buffer, r.offset, r.length);
    const sectionCount = v.getUint32(0, true);
    if (r.length !== 8 + sectionCount * 256) throw new Error("biomes result size mismatch");
    return {sectionCount, minY: v.getInt32(4, true), ids: new Uint32Array(buffer, r.offset + 8, sectionCount * 64)};
  }

  // Decodes a chunk result. Arrays are views into `buffer` (no copies); `bytes` is the raw chunk
  // part for a later surface job.
  function readChunk(buffer, jobId) {
    const r = readResult(buffer, jobId);
    const base = r.offset;
    const v = new DataView(buffer, base, r.length);
    const chunk = {
      chunkX: v.getInt32(0, true),
      chunkZ: v.getInt32(4, true),
      minY: v.getInt32(8, true),
      sectionCount: v.getUint32(12, true),
      flags: v.getUint32(16, true),
      sections: [],
      worldSurface: null,
      oceanFloor: null,
      postProcessing: null,
      biomes: null,
      bytes: null,
    };
    let p = 24;
    for (let s = 0; s < chunk.sectionCount; s++) {
      const paletteLength = v.getUint16(p, true);
      const bits = v.getUint8(p + 2);
      const hasNonAir = v.getUint8(p + 3) !== 0;
      p += 4;
      const palette = new Uint32Array(buffer, base + p, paletteLength);
      p += 4 * paletteLength;
      let indices = null;
      if (bits === 8) {
        indices = new Uint8Array(buffer, base + p, 4096);
        p += 4096;
      } else if (bits === 16) {
        indices = new Uint16Array(buffer, base + p, 4096);
        p += 8192;
      } else if (bits !== 0) {
        throw new Error(`bad section index bits ${bits}`);
      }
      p = pad(p, 4);
      chunk.sections.push({palette, bits, hasNonAir, indices});
    }
    chunk.worldSurface = new Int32Array(buffer, base + p, 256);
    chunk.oceanFloor = new Int32Array(buffer, base + p + 1024, 256);
    p += 2048;
    const postCount = v.getUint32(p, true);
    chunk.postProcessing = new Uint32Array(buffer, base + p + 4, postCount);
    p += 4 + 4 * postCount;
    const chunkEnd = p;
    if (chunk.flags & 1) {
      chunk.biomes = new Uint32Array(buffer, base + p, chunk.sectionCount * 64);
      p += chunk.sectionCount * 256;
    }
    if (p !== r.length) throw new Error("chunk result size mismatch");
    chunk.bytes = new Uint8Array(buffer, base, chunkEnd);
    return chunk;
  }

  // Block state id at section-relative (x, y, z) of a decoded section.
  function sectionState(section, x, y, z) {
    if (section.bits === 0) return section.palette[0];
    return section.palette[section.indices[(y << 8) | (z << 4) | x]];
  }

  function isGeneratorMissing(error) {
    return !!error && typeof error.message === "string" && error.message.indexOf(MISSING_PREFIX) >= 0;
  }

  let nextJobId = 0;
  function takeJobId() {
    nextJobId = (nextJobId + 1) >>> 0;
    if (nextJobId === 0) nextJobId = 1;
    return nextJobId;
  }

  // Submits a job (with the IR while the generator is new to the pool) and resends it with the IR
  // when the worker that ran it has not loaded the generator yet.
  async function submit(pool, gen, kindName, encode, opts) {
    if (!global.GaiusKernelMirror || !global.GaiusKernelMirror.enabled("worldgen")) {
      throw Object.assign(new Error("NEEDS_VANILLA: worldgen mirror is disabled"), {code: "needs-vanilla"});
    }
    const jobId = takeJobId();
    const inline = gen.inlineLeft > 0;
    if (inline) gen.inlineLeft--;
    try {
      return {jobId, result: await pool.submit(kindName, encode(jobId, inline), opts)};
    } catch (error) {
      if (!isGeneratorMissing(error)) throw error;
      const retryId = takeJobId();
      return {jobId: retryId, result: await pool.submit(kindName, encode(retryId, true), opts)};
    }
  }

  async function biomes(pool, gen, chunkX, chunkZ, opts) {
    const {jobId, result} = await submit(pool, gen, "biomes",
      (id, inline) => encodeBiomes(gen, chunkX, chunkZ, id, inline), opts);
    return readBiomes(result, jobId);
  }

  async function terrain(pool, gen, chunkX, chunkZ, options, opts) {
    const {jobId, result} = await submit(pool, gen, "terrain",
      (id, inline) => encodeTerrain(gen, chunkX, chunkZ, id, options, inline), opts);
    return readChunk(result, jobId);
  }

  async function surface(pool, gen, chunk, options, opts) {
    const {jobId, result} = await submit(pool, gen, "surface",
      (id, inline) => encodeSurface(gen, chunk, id, options, inline), opts);
    return readChunk(result, jobId);
  }

  async function load(pool, gen, options, opts) {
    if (!global.GaiusKernelMirror || !global.GaiusKernelMirror.enabled("worldgen")) {
      throw Object.assign(new Error("NEEDS_VANILLA: worldgen mirror is disabled"), {code: "needs-vanilla"});
    }
    const jobId = takeJobId();
    return readLoadInfo(await pool.submit("load_generator", encodeLoad(gen, jobId, options), opts), jobId);
  }

  global.GaiusWorldgenJob = Object.freeze({
    ABI_VERSION, KINDS, FLAG_SURFACE, FLAG_BIOMES, FLAG_RING_BIOMES,
    simdSupported, pickModule, compileModule, fnv1a64, generator,
    encodeLoad, encodeBiomes, encodeTerrain, encodeSurface,
    readResult, readLoadInfo, readBiomes, readChunk, sectionState, isGeneratorMissing,
    load, biomes, terrain, surface,
  });
})(typeof globalThis !== "undefined" ? globalThis : self);
