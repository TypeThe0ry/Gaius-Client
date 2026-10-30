// Gaius mesh job codec: builds framed load_model_table and mesh_section jobs for the wasm mesh
// kernel (port/native/crates/gaius-mesher-wasm) and reads its results. The layouts mirror
// port/native/crates/gaius-kernel-abi (job/result headers), gaius-mesher/src/job.rs (section
// job), gaius-mesher/src/table.rs (model table) and gaius-mesher/src/output.rs (result); all
// integers are little-endian and offsets inside a payload are payload-relative.
//
// This file is a plain script so it can be inlined next to kernel-pool.js / kernel-runtime.js.
// It installs globalThis.GaiusMeshJob:
//
//   const session = GaiusMeshJob.session(tableBytes, epoch);   // once per resource reload
//   const result = await session.mesh(submit, {
//     requestSeq, sectionVersion, section: [sx, sy, sz],
//     ao: true, cutoutLeaves: false, vanilla: true, compact: false, sortTranslucent: true,
//     cardinal: "default" | "nether" | [down, up, north, south, west, east],
//     camera: [cx, cy, cz],                  // camera minus the section origin, in blocks
//     biomeZoomSeed: 123n,                   // BigInt, decimal string or safe integer
//     states: Uint16Array(8000) | Uint32Array(8000),   // 20^3 region, see regionIndex
//     light: Uint8Array(8000),               // (sky << 4) | block
//     biomeQuarts: Uint8Array(216),          // palette indices, quart origin section * 4 - 1
//     biomePalette: Int32Array(n * 5) | [{grass, grassModifier, foliage, dryFoliage, water}],
//     swampMask: Uint8Array(32),             // optional; bit z * 16 + x
//     biomeBlend: 0..2,                      // optional Options.biomeBlendRadius; with it the
//                                            // swampMask is Uint8Array(50), bit (z + 2) * 20 + (x + 2)
//                                            // for the columns x, z in -2..17
//   }, {priority, key, version, ...});
//   // submit(kind, payload, opts) -> Promise<ArrayBuffer>, e.g. (k, p, o) => runtime.submit(k, p, o)
//   // result.status: "meshed" | "needs-vanilla"; result.layers.solid.vertices is a Uint8Array of
//   // vanilla BLOCK vertices (28 bytes each), byte-identical to SectionCompiler's buffer.
//
// The model table lives in each worker's wasm memory. A worker that does not hold the job's
// epoch (fresh, restarted or reloaded) answers "table missing"; the session then resends the
// same job with the table attached, so any worker recovers without a broadcast.
//
// Switches: ?gaiusMesher=0 (or =vanilla) keeps the vanilla Java mesher; the same setting
// persists under localStorage "gaius.mesher.enabled" ("0" disables). After three kernel failures
// in a row GaiusMeshJob.enabled() turns false for the rest of the session, so callers fall back
// to the vanilla path automatically.
(function (global) {
  "use strict";

  const ABI_VERSION = 1;
  const JOB_MAGIC = 0x424a4b47;     // "GKJB" read as little-endian u32
  const RESULT_MAGIC = 0x53524b47;  // "GKRS"
  const HEADER_LEN = 16;
  const KIND_LOAD_MODEL_TABLE = 0x0201;
  const KIND_MESH_SECTION = 0x0202;
  const TABLE_MAGIC = 0x42544d47;   // "GMTB"

  const REGION = 20;
  const MARGIN = 2;
  const VOLUME = REGION * REGION * REGION;
  const QUART_VOLUME = 216;
  const JOB_HEADER_LEN = 96;
  const PALETTE_ENTRY_LEN = 20;
  const RESULT_HEADER_LEN = 160;
  const VANILLA_VERTEX = 28;
  const COMPACT_VERTEX = 12;
  const SWAMP_BYTES = 32;
  const SWAMP_BLEND_BYTES = 50;
  const MAX_BIOME_BLEND = 2;

  const FLAGS = Object.freeze({
    AMBIENT_OCCLUSION: 1 << 0,
    CUTOUT_LEAVES: 1 << 1,
    EMIT_VANILLA: 1 << 2,
    EMIT_COMPACT: 1 << 3,
    SORT_TRANSLUCENT: 1 << 4,
    WIDE_IDS: 1 << 5,
    GREEDY: 1 << 6,
    INLINE_TABLE: 1 << 7,
    EMIT_CENTROIDS: 1 << 8,
    BIOME_BLEND: 1 << 9,
  });
  const RESULT_FLAGS = Object.freeze({VANILLA: 1, COMPACT: 2, SORTED: 4, CENTROIDS: 8, EMPTY: 16});
  const STATUS = Object.freeze({MESHED: 0, TABLE_MISSING: 1, NEEDS_VANILLA: 2});
  const STATUS_NAMES = Object.freeze(["meshed", "table-missing", "needs-vanilla"]);
  const LAYERS = Object.freeze(["solid", "cutout", "translucent"]);
  const CARDINAL = Object.freeze({
    default: Object.freeze([0.5, 1.0, 0.8, 0.8, 0.6, 0.6]),
    nether: Object.freeze([0.9, 0.9, 0.8, 0.8, 0.6, 0.6]),
  });
  const GRASS_MODIFIER = Object.freeze({none: 0, dark_forest: 1, swamp: 2});
  const LITTLE_ENDIAN = new Uint8Array(new Uint16Array([1]).buffer)[0] === 1;

  const pad = (offset, align) => Math.ceil(offset / align) * align;

  // Index of a section-relative position (each coordinate in -2..17) in the 20^3 region.
  function regionIndex(x, y, z) {
    return ((y + MARGIN) * REGION + (z + MARGIN)) * REGION + (x + MARGIN);
  }

  function toBytes(data, what) {
    if (data instanceof ArrayBuffer) return new Uint8Array(data);
    if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    throw new TypeError(`${what} must be an ArrayBuffer or a typed array`);
  }

  function frame(kind, payloadLength, jobId) {
    const buffer = new ArrayBuffer(HEADER_LEN + payloadLength);
    const view = new DataView(buffer);
    view.setUint32(0, JOB_MAGIC, true);
    view.setUint16(4, ABI_VERSION, true);
    view.setUint16(6, kind, true);
    view.setUint32(8, jobId >>> 0, true);
    view.setUint32(12, payloadLength, true);
    return buffer;
  }

  // The model table's epoch, read from its header (throws on a foreign buffer).
  function tableEpoch(tableBytes) {
    const bytes = toBytes(tableBytes, "model table");
    if (bytes.byteLength < 64) throw new RangeError("model table is shorter than its header");
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (view.getUint32(0, true) !== TABLE_MAGIC) throw new Error("not a Gaius model table");
    return view.getUint32(8, true);
  }

  // Framed load_model_table job (copies the table, so the caller keeps its bytes).
  function loadModelTable(tableBytes, jobId) {
    const bytes = toBytes(tableBytes, "model table");
    const buffer = frame(KIND_LOAD_MODEL_TABLE, bytes.byteLength, jobId);
    new Uint8Array(buffer, HEADER_LEN).set(bytes);
    return buffer;
  }

  function writeIds(buffer, at, states, wide) {
    if (states.length !== VOLUME) throw new RangeError(`states must hold ${VOLUME} ids`);
    if (LITTLE_ENDIAN) {
      if (wide) new Uint32Array(buffer, at, VOLUME).set(states);
      else new Uint16Array(buffer, at, VOLUME).set(states);
      return;
    }
    const view = new DataView(buffer, at);
    for (let i = 0; i < VOLUME; i++) {
      if (wide) view.setUint32(4 * i, states[i], true);
      else view.setUint16(2 * i, states[i], true);
    }
  }

  function paletteLength(palette) {
    if (palette instanceof Int32Array) {
      if (palette.length === 0 || palette.length % 5 !== 0) throw new RangeError("biomePalette must hold whole 5-int entries");
      return palette.length / 5;
    }
    if (!Array.isArray(palette) || palette.length === 0) throw new RangeError("biomePalette is empty");
    return palette.length;
  }

  function writePalette(view, at, palette) {
    if (palette instanceof Int32Array) {
      for (let i = 0; i < palette.length; i++) view.setInt32(at + 4 * i, palette[i], true);
      return;
    }
    palette.forEach((entry, i) => {
      const base = at + PALETTE_ENTRY_LEN * i;
      const modifier = typeof entry.grassModifier === "string"
        ? GRASS_MODIFIER[entry.grassModifier] : (entry.grassModifier | 0);
      view.setInt32(base, entry.grass | 0, true);
      view.setInt32(base + 4, modifier | 0, true);
      view.setInt32(base + 8, entry.foliage | 0, true);
      view.setInt32(base + 12, entry.dryFoliage | 0, true);
      view.setInt32(base + 16, entry.water | 0, true);
    });
  }

  // Options.biomeBlendRadius of the job, or -1 for the legacy layout (radius 0, 16 x 16 swamp mask).
  function biomeBlend(input) {
    const blend = input.biomeBlend;
    if (blend === undefined || blend === null) return -1;
    if (!Number.isInteger(blend) || blend < 0 || blend > MAX_BIOME_BLEND) {
      throw new RangeError(`biomeBlend must be an integer in 0..${MAX_BIOME_BLEND}`);
    }
    return blend;
  }

  function jobFlags(input, wide, withTable) {
    let flags = 0;
    if (input.ao !== false) flags |= FLAGS.AMBIENT_OCCLUSION;
    if (input.cutoutLeaves) flags |= FLAGS.CUTOUT_LEAVES;
    if (input.vanilla !== false) flags |= FLAGS.EMIT_VANILLA;
    if (input.compact) flags |= FLAGS.EMIT_COMPACT;
    if (input.sortTranslucent !== false) flags |= FLAGS.SORT_TRANSLUCENT;
    if (input.centroids) flags |= FLAGS.EMIT_CENTROIDS;
    if (wide) flags |= FLAGS.WIDE_IDS;
    if (withTable) flags |= FLAGS.INLINE_TABLE;
    if (biomeBlend(input) >= 0) flags |= FLAGS.BIOME_BLEND;
    return flags;
  }

  // Encodes a framed mesh_section job into one ArrayBuffer ready to transfer. `table` (optional)
  // attaches the model table for a worker that does not hold `tableEpoch`.
  function meshSection(input, jobId, table) {
    const wide = input.states instanceof Uint32Array;
    if (!wide && !(input.states instanceof Uint16Array)) throw new TypeError("states must be a Uint16Array or Uint32Array");
    const light = toBytes(input.light, "light");
    const quarts = toBytes(input.biomeQuarts, "biomeQuarts");
    if (light.byteLength !== VOLUME) throw new RangeError(`light must hold ${VOLUME} bytes`);
    if (quarts.byteLength !== QUART_VOLUME) throw new RangeError(`biomeQuarts must hold ${QUART_VOLUME} bytes`);
    const paletteCount = paletteLength(input.biomePalette);
    if (paletteCount > 256) throw new RangeError("biomePalette holds more than 256 biomes");
    const tableBytes = table ? toBytes(table, "model table") : null;
    const blend = biomeBlend(input);
    const swampBytes = blend >= 0 ? SWAMP_BLEND_BYTES : SWAMP_BYTES;
    const swamp = input.swampMask ? toBytes(input.swampMask, "swampMask") : null;
    if (swamp && swamp.byteLength !== swampBytes) throw new RangeError(`swampMask must hold ${swampBytes} bytes`);

    const statesAt = JOB_HEADER_LEN;
    const lightAt = statesAt + VOLUME * (wide ? 4 : 2);
    const quartsAt = lightAt + VOLUME;
    const paletteAt = pad(quartsAt + QUART_VOLUME, 8);
    const swampAt = paletteAt + PALETTE_ENTRY_LEN * paletteCount;
    const tableAt = pad(swampAt + swampBytes, 8);
    const payloadLength = tableBytes ? tableAt + tableBytes.byteLength : swampAt + swampBytes;

    const buffer = frame(KIND_MESH_SECTION, payloadLength, jobId);
    const view = new DataView(buffer, HEADER_LEN);
    const section = input.section || [0, 0, 0];
    const cardinal = typeof input.cardinal === "string" ? CARDINAL[input.cardinal] : (input.cardinal || CARDINAL.default);
    if (!cardinal || cardinal.length !== 6) throw new RangeError("cardinal must be 'default', 'nether' or six floats");
    const camera = input.camera || [8, 8, 8];
    view.setUint32(0, input.tableEpoch >>> 0, true);
    view.setUint32(4, (input.requestSeq || 0) >>> 0, true);
    view.setUint32(8, (input.sectionVersion || 0) >>> 0, true);
    view.setUint32(12, jobFlags(input, wide, !!tableBytes), true);
    view.setInt32(16, section[0] | 0, true);
    view.setInt32(20, section[1] | 0, true);
    view.setInt32(24, section[2] | 0, true);
    view.setUint32(28, paletteCount, true);
    for (let i = 0; i < 6; i++) view.setFloat32(32 + 4 * i, cardinal[i], true);
    for (let i = 0; i < 3; i++) view.setFloat32(56 + 4 * i, camera[i], true);
    view.setUint32(68, tableBytes ? tableBytes.byteLength : 0, true);
    view.setBigInt64(72, BigInt.asIntN(64, BigInt(input.biomeZoomSeed || 0)), true);
    view.setUint32(80, blend >= 0 ? blend : 0, true);

    writeIds(buffer, HEADER_LEN + statesAt, input.states, wide);
    new Uint8Array(buffer, HEADER_LEN + lightAt, VOLUME).set(light);
    new Uint8Array(buffer, HEADER_LEN + quartsAt, QUART_VOLUME).set(quarts);
    writePalette(view, paletteAt, input.biomePalette);
    if (swamp) new Uint8Array(buffer, HEADER_LEN + swampAt, swampBytes).set(swamp);
    if (tableBytes) new Uint8Array(buffer, HEADER_LEN + tableAt, tableBytes.byteLength).set(tableBytes);
    return buffer;
  }

  // Validates a result header and returns {view, length} of the payload.
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
    return {view: new DataView(buffer, HEADER_LEN, length), length};
  }

  function readModelTableLoaded(buffer, jobId) {
    const {view, length} = readResult(buffer, jobId);
    if (length < 16) throw new Error("load_model_table result is too short");
    return {
      epoch: view.getUint32(0, true),
      stateCount: view.getUint32(4, true),
      quadCount: view.getUint32(8, true),
      tableBytes: view.getUint32(12, true),
    };
  }

  // Views (no copies) into a mesh_section result.
  function readMeshSection(buffer, jobId) {
    const {view, length} = readResult(buffer, jobId);
    if (length < RESULT_HEADER_LEN) throw new Error("mesh_section result is shorter than its header");
    const status = view.getUint32(0, true);
    const flags = view.getUint32(36, true);
    const at = (offset, bytes) => {
      if (offset === 0) return -1;
      if (offset + bytes > length) throw new Error("mesh_section result section is out of bounds");
      return HEADER_LEN + offset;
    };
    const layers = {};
    LAYERS.forEach((name, k) => {
      const base = 64 + 32 * k;
      const quadCount = view.getUint32(base, true);
      const vanillaAt = at(view.getUint32(base + 4, true), quadCount * 4 * VANILLA_VERTEX);
      const compactAt = at(view.getUint32(base + 8, true), quadCount * 4 * COMPACT_VERTEX);
      const tintAt = at(view.getUint32(base + 12, true), quadCount * 4);
      const orderAt = at(view.getUint32(base + 16, true), quadCount * 4);
      const centroidAt = at(view.getUint32(base + 20, true), quadCount * 12);
      layers[name] = {
        quadCount,
        vertexCount: quadCount * 4,
        vertices: vanillaAt < 0 ? null : new Uint8Array(buffer, vanillaAt, quadCount * 4 * VANILLA_VERTEX),
        compact: compactAt < 0 ? null : new Uint8Array(buffer, compactAt, quadCount * 4 * COMPACT_VERTEX),
        quadTints: tintAt < 0 ? null : new Uint32Array(buffer, tintAt, quadCount),
        order: orderAt < 0 ? null : new Uint32Array(buffer, orderAt, quadCount),
        centroids: centroidAt < 0 ? null : new Float32Array(buffer, centroidAt, quadCount * 3),
      };
    });
    return {
      buffer,
      status: STATUS_NAMES[status] || `status-${status}`,
      statusCode: status,
      requestSeq: view.getUint32(4, true),
      sectionVersion: view.getUint32(8, true),
      section: [view.getInt32(12, true), view.getInt32(16, true), view.getInt32(20, true)],
      tableEpoch: view.getUint32(24, true),
      // VisibilitySet bits a + 6 * b (Direction ordinals): BitSet.valueOf(new long[] {hi << 32 | lo}).
      visibilityLo: view.getUint32(28, true),
      visibilityHi: view.getUint32(32, true),
      flags,
      empty: (flags & RESULT_FLAGS.EMPTY) !== 0,
      camera: [view.getFloat32(40, true), view.getFloat32(44, true), view.getFloat32(48, true)],
      // needs-vanilla: the first unsupported state id; meshed: the count of non-air blocks.
      detail: view.getUint32(52, true),
      layers,
    };
  }

  // MeshData.writeIndices for a sorted quad order: 4q, 4q+1, 4q+2, 4q+2, 4q+3, 4q per quad.
  function sortedIndices(order, vertexCount) {
    const out = vertexCount > 65536 ? new Uint32Array(order.length * 6) : new Uint16Array(order.length * 6);
    for (let i = 0; i < order.length; i++) {
      const v = order[i] * 4;
      const o = i * 6;
      out[o] = v;
      out[o + 1] = v + 1;
      out[o + 2] = v + 2;
      out[o + 3] = v + 2;
      out[o + 4] = v + 3;
      out[o + 5] = v;
    }
    return out;
  }

  // Re-sorts translucent quads for a new camera (ResortTransparencyTask) from the centroids,
  // like VertexSorting.byDistance: float distances, stable, farthest first.
  function sortOrder(centroids, camera) {
    const f = Math.fround;
    const count = centroids.length / 3;
    const distance = new Float32Array(count);
    const cx = f(camera[0]);
    const cy = f(camera[1]);
    const cz = f(camera[2]);
    for (let i = 0; i < count; i++) {
      const dx = f(cx - centroids[3 * i]);
      const dy = f(cy - centroids[3 * i + 1]);
      const dz = f(cz - centroids[3 * i + 2]);
      distance[i] = f(f(dx * dx) + f(f(dy * dy) + f(dz * dz)));
    }
    const order = Array.from({length: count}, (_, i) => i);
    order.sort((a, b) => (distance[b] - distance[a]) || (a - b));
    return Uint32Array.from(order);
  }

  // Rough upper bound of the result size, for memory budgets: about 1.6 visible quads per
  // non-air block on surfaces, capped by the section.
  function estimateResultBytes(nonAirBlocks, compact) {
    const quads = Math.min(4096 * 6, Math.max(16, Math.ceil((nonAirBlocks || 2048) * 1.6)));
    return HEADER_LEN + RESULT_HEADER_LEN + quads * 4 * (VANILLA_VERTEX + (compact ? COMPACT_VERTEX : 0)) + quads * 16;
  }

  // --- runtime switch and automatic fallback -----------------------------------------------

  let failureStreak = 0;
  let disabledReason = null;

  function settingDisabled() {
    try {
      const search = global.location && global.location.search ? global.location.search : "";
      const value = new URLSearchParams(search).get("gaiusMesher");
      if (value === "0" || value === "false" || value === "vanilla") return "url";
    } catch (_) { /* no location in this realm */ }
    try {
      if (global.localStorage && global.localStorage.getItem("gaius.mesher.enabled") === "0") return "setting";
    } catch (_) { /* storage blocked */ }
    return null;
  }

  function enabled() {
    if (!global.GaiusKernelMirror || !global.GaiusKernelMirror.enabled("mesher")) return false;
    if (disabledReason) return false;
    const reason = settingDisabled();
    if (reason) {
      disabledReason = reason;
      return false;
    }
    return true;
  }

  function noteSuccess() {
    failureStreak = 0;
  }

  function noteFailure(error) {
    failureStreak++;
    if (failureStreak >= 3 && !disabledReason) {
      disabledReason = `kernel failures: ${error && error.message ? error.message : String(error)}`;
    }
  }

  function status() {
    return {enabled: enabled(), disabledReason, failureStreak};
  }

  // One resource epoch: keeps the table bytes and resends a job with them when a worker answers
  // "table missing". submit(kind, payload, opts) returns a Promise of the result ArrayBuffer.
  class MeshTableSession {
    constructor(tableBytes, epoch) {
      const bytes = toBytes(tableBytes, "model table");
      this.table = bytes;
      this.epoch = epoch === undefined ? tableEpoch(bytes) : epoch >>> 0;
      if (tableEpoch(bytes) !== this.epoch) throw new Error("model table epoch does not match");
      this.nextJobId = 1;
      this.tableResends = 0;
    }

    // Preloads the table into whichever worker takes the job (optional warm-up).
    preload(submit, opts) {
      if (!global.GaiusKernelMirror || !global.GaiusKernelMirror.enabled("mesher")) {
        return Promise.reject(Object.assign(new Error("NEEDS_VANILLA: mesh mirror is disabled"), {code: "needs-vanilla"}));
      }
      const jobId = this.nextJobId++ >>> 0;
      return submit("load_model_table", loadModelTable(this.table, jobId), opts || {})
        .then((buffer) => readModelTableLoaded(buffer, jobId));
    }

    async mesh(submit, input, opts) {
      if (!global.GaiusKernelMirror || !global.GaiusKernelMirror.enabled("mesher")) {
        throw Object.assign(new Error("NEEDS_VANILLA: mesh mirror is disabled"), {code: "needs-vanilla"});
      }
      if (!enabled()) throw Object.assign(new Error("mesh kernel is disabled"), {code: "kernel-disabled"});
      const job = Object.assign({}, input, {tableEpoch: this.epoch});
      try {
        let jobId = this.nextJobId++ >>> 0;
        let result = readMeshSection(await submit("mesh_section", meshSection(job, jobId, null), opts || {}), jobId);
        if (result.statusCode === STATUS.TABLE_MISSING) {
          this.tableResends++;
          jobId = this.nextJobId++ >>> 0;
          result = readMeshSection(await submit("mesh_section", meshSection(job, jobId, this.table), opts || {}), jobId);
          if (result.statusCode === STATUS.TABLE_MISSING) throw new Error("mesh kernel did not take the inline table");
        }
        noteSuccess();
        return result;
      } catch (error) {
        // Superseded or cancelled jobs are scheduling outcomes, not kernel failures.
        const code = error && error.code;
        if (code !== "superseded" && code !== "stale" && code !== "cancelled" && code !== "backpressure"
            && code !== "request-cap") noteFailure(error);
        throw error;
      }
    }
  }

  global.GaiusMeshJob = Object.freeze({
    ABI_VERSION, KIND_LOAD_MODEL_TABLE, KIND_MESH_SECTION, FLAGS, RESULT_FLAGS, STATUS, LAYERS, CARDINAL,
    GRASS_MODIFIER, REGION, MARGIN, VOLUME, QUART_VOLUME, VANILLA_VERTEX, COMPACT_VERTEX,
    SWAMP_BYTES, SWAMP_BLEND_BYTES, MAX_BIOME_BLEND,
    regionIndex, tableEpoch, loadModelTable, readModelTableLoaded, meshSection, readResult, readMeshSection,
    sortedIndices, sortOrder, estimateResultBytes, enabled, noteSuccess, noteFailure, status,
    session: (tableBytes, epoch) => new MeshTableSession(tableBytes, epoch),
    MeshTableSession,
  });
})(typeof globalThis !== "undefined" ? globalThis : self);
