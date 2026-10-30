// Gaius mesh kernel facade: the page-side bridge between the Java section compiler
// (dev.gaius.browser.kernel.mesh.MeshKernelBridge, through small @JSBody calls) and the "mesher"
// kernel of the kernel runtime (kernel-runtime.js, created by gaius-boot.js as
// window.__gaiusKernels). The job and result codecs are GaiusMeshJob (mesh-job.js); this file
// only owns the per-resource-epoch table session, the in-flight jobs and a queue of finished
// results that Java polls. It is named *-job.js so stage-web-runtime.py and the portable page
// load it with the other kernel scripts; it resolves GaiusMeshJob lazily, so load order between
// the two does not matter.
//
// It installs globalThis.__gaiusMeshKernel:
//
//   ready()                      true when the runtime exists, the mesher kernel is available and
//                                no switch turned the kernel off (the Java side then compiles
//                                sections through the kernel; otherwise it keeps the vanilla path)
//   setTable(bytes, length, epoch)   new model table (Int8Array view of a Java byte[]): opens a
//                                GaiusMeshJob session for that epoch and warms one worker
//   hasTable(epoch)
//   submitJob(header, ids, light, quarts, palette, swamp, floats)
//                                copies one section snapshot (typed views of Java arrays) and
//                                submits it; header is an Int32Array, see HEADER below
//   cancelAll()                  cancels every section job still out (the level was reset or
//                                left); their records come back with code "cancelled"
//   poll()                       next finished record or null: {ticket, ok, code, message,
//                                status (0 meshed, 2 needs vanilla, -1 failed), quads[3],
//                                visLo, visHi, detail, result}
//   setWake(fn)                  fn() runs once when a record lands in an empty queue
//   status()                     counters for telemetry and debugging
//
// Switches: ?meshKernel=0|off|vanilla (or localStorage "gaius.meshKernel" = "off") keeps the
// vanilla Java compiler for this page; GaiusMeshJob's own ?gaiusMesher=0 and its automatic
// disable after repeated kernel failures are honoured too, as is ?gaiusKernels=0.
(function (global) {
  "use strict";

  const KERNEL = "mesher";
  const VOLUME = 8000;
  const QUART_VOLUME = 216;
  // Header ints written by MeshKernelBridge.
  const HEADER = Object.freeze({
    TICKET: 0, EPOCH: 1, WIDE: 2, PALETTE_COUNT: 3, FLAGS: 4, SX: 5, SY: 6, SZ: 7,
    SEED_HI: 8, SEED_LO: 9, REQUEST_SEQ: 10, NON_AIR: 11, DISTANCE: 12, BLEND: 13, LENGTH: 14,
  });
  const FLAG_AO = 1;
  const FLAG_CUTOUT_LEAVES = 2;
  const FLAG_NEARBY = 4;
  const NEARBY_PRIORITY_CHUNKS = 8;
  // Swamp column mask of a job with a biome blend radius: 20 x 20 columns (x, z in -2..17).
  const SWAMP_BLEND_BYTES = 50;
  const MAX_BIOME_BLEND = 2;
  // Configurable for slow-machine validation; the bounded default gives a cold model-table
  // compile enough time while ensuring a lost reply cannot hold resource reload forever.
  const configuredTimeout = Number(global.__gaiusMeshWaitTimeoutMs);
  const MESH_WAIT_TIMEOUT_MS = Number.isFinite(configuredTimeout)
    ? Math.max(5000, Math.min(120000, configuredTimeout)) : 30000;

  const recycleEnabled = (() => {
    try {
      const value = new URLSearchParams(global.location ? global.location.search : "").get("gaiusKernelRecycle");
      return value !== "0" && value !== "off";
    } catch (_) {
      return true;
    }
  })();
  let runtime = null;
  let runtimeState = "none"; // none | pending | ready | failed
  let switchReason;          // undefined until read once
  let disabledReason = null;
  let session = null;
  let sessionEpoch = -1;
  let wake = null;
  let wakeArmed = true;
  let done = [];
  let head = 0;
  let inFlight = 0;
  // Runtime key of every section job still out -> its ticket, for cancelAll.
  const outstanding = new Map();
  const stats = {
    submitted: 0, meshed: 0, needsVanilla: 0, failed: 0, superseded: 0, cancelledAll: 0, tables: 0,
    tableBytes: 0, tableEpoch: -1, preloadErrors: 0, lastError: null,
    tableCacheHits: 0, tableCacheStores: 0, tableCacheErrors: 0, tableCacheValidated: 0, recycled: 0,
  };

  // The model table is a pure function of the shipped asset set and the profile's table schema.
  // Keep one bounded record in IndexedDB so a page reload can hand Java the exact bytes before
  // ModelTableExporter starts. The cache is an optimisation only: every malformed or unavailable
  // record falls through to the existing vanilla exporter.
  const TABLE_CACHE_DB = "gaius-derived-v1-" + String(global.__gaiusProfileId || "default");
  const TABLE_CACHE_STORE = "mesh-model-table";
  const TABLE_CACHE_SCHEMA = "mesh-table-v1";
  const TABLE_HEADER_LEN = 64;
  const TABLE_SECTION_LENGTHS = [56, 12, 8, 20, 4, 16, 48, 32, 8, 32, 56, 4];
  const TABLE_MAX_BYTES = 64 * 1024 * 1024;
  const tableCache = {db: null, ready: false, opening: null, key: null, entry: null, entryScope: null,
    pending: null, consumed: false, current: null};

  function tableCacheKey() {
    const profile = String(global.__gaiusProfileId || "default");
    const content = String(global.__gaiusResourceContentHash || global.__gaiusAssetContentHash || "dev");
    return profile + "|" + content + "|" + TABLE_CACHE_SCHEMA;
  }

  function validTableRecord(value, key) {
    if (!value || value.key !== key || value.schema !== TABLE_CACHE_SCHEMA || typeof value.scope !== "string"
        || !(value.bytes instanceof ArrayBuffer) || value.bytes.byteLength < TABLE_HEADER_LEN
        || value.bytes.byteLength > TABLE_MAX_BYTES) return false;
    try {
      const view = new DataView(value.bytes);
      if (view.getUint32(0, true) !== 0x42544d47 || view.getUint16(4, true) !== 1
          || view.getUint32(60, true) !== 0) return false;
      let expected = TABLE_HEADER_LEN;
      const stateCount = view.getUint32(12, true);
      const airCount = view.getUint32(56, true);
      if (stateCount === 0 || stateCount > (1 << 20) || airCount > 8) return false;
      for (let i = 0; i < TABLE_SECTION_LENGTHS.length; i++) {
        const count = view.getUint32(12 + i * 4, true);
        const section = count * TABLE_SECTION_LENGTHS[i];
        if (!Number.isSafeInteger(section)) return false;
        expected += (section + 7) & ~7;
        if (expected > value.bytes.byteLength) return false;
      }
      return expected === value.bytes.byteLength;
    } catch (_) {
      return false;
    }
  }

  function prepareTableCache() {
    tableCache.key = tableCacheKey();
    if (typeof global.indexedDB === "undefined") {
      tableCache.ready = true;
      return Promise.resolve(0);
    }
    tableCache.opening = new Promise((resolve) => {
      let request;
      try {
        request = global.indexedDB.open(TABLE_CACHE_DB, 1);
      } catch (_) {
        stats.tableCacheErrors++;
        tableCache.ready = true;
        resolve(0);
        return;
      }
      request.onupgradeneeded = () => {
        try {
          const db = request.result;
          if (!db.objectStoreNames.contains(TABLE_CACHE_STORE)) {
            db.createObjectStore(TABLE_CACHE_STORE, {keyPath: "key"});
          }
        } catch (_) {
          stats.tableCacheErrors++;
        }
      };
      request.onerror = () => {
        stats.tableCacheErrors++;
        tableCache.ready = true;
        resolve(0);
      };
      request.onblocked = () => {
        stats.tableCacheErrors++;
        tableCache.ready = true;
        resolve(0);
      };
      request.onsuccess = () => {
        const db = request.result;
        let transaction;
        try {
          transaction = db.transaction(TABLE_CACHE_STORE, "readonly");
          const get = transaction.objectStore(TABLE_CACHE_STORE).get(tableCache.key);
          get.onerror = () => {
            stats.tableCacheErrors++;
            tableCache.ready = true;
            tableCache.db = db;
            resolve(0);
          };
          get.onsuccess = () => {
            if (validTableRecord(get.result, tableCache.key)) {
              tableCache.entry = new Uint8Array(get.result.bytes.slice(0));
              tableCache.entryScope = get.result.scope;
            } else if (get.result) {
              // Do not let a corrupt record poison future boots.
              try {
                const cleanup = db.transaction(TABLE_CACHE_STORE, "readwrite");
                cleanup.objectStore(TABLE_CACHE_STORE).delete(tableCache.key);
              } catch (_) { stats.tableCacheErrors++; }
            }
            tableCache.db = db;
            tableCache.ready = true;
            if (tableCache.pending) {
              const pending = tableCache.pending;
              tableCache.pending = null;
              persistTable(pending);
            }
            resolve(tableCache.entry ? tableCache.entry.byteLength : 0);
          };
        } catch (_) {
          stats.tableCacheErrors++;
          tableCache.ready = true;
          tableCache.db = db;
          if (tableCache.pending) {
            const pending = tableCache.pending;
            tableCache.pending = null;
            persistTable(pending);
          }
          resolve(0);
        }
      };
    });
    return tableCache.opening;
  }

  // scope: the resource pack stack Java exported the table under (only stacks whose contents the
  // asset hash identifies; Java never stores a table made with a user or server resource pack).
  function persistTable(record) {
    if (!tableCache.db || !tableCache.ready || !tableCache.key) return;
    const bytes = record.bytes;
    try {
      const transaction = tableCache.db.transaction(TABLE_CACHE_STORE, "readwrite");
      transaction.objectStore(TABLE_CACHE_STORE).put({key: tableCache.key, schema: TABLE_CACHE_SCHEMA,
        scope: record.scope, bytes: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)});
      transaction.oncomplete = () => { stats.tableCacheStores++; };
      transaction.onerror = () => { stats.tableCacheErrors++; };
    } catch (_) {
      stats.tableCacheErrors++;
    }
  }

  // Start the read while the client is loading its classes. Java consumes this once, then the
  // normal setTable path takes ownership of the byte copy.
  prepareTableCache();

  function message(error) {
    return error && error.message ? String(error.message) : String(error);
  }

  function bounded(promise, label) {
    let timer = 0;
    return Promise.race([
      Promise.resolve(promise),
      new Promise((_, reject) => {
        timer = global.setTimeout(() => {
          const error = new Error("mesh " + label + " timed out after " + MESH_WAIT_TIMEOUT_MS + "ms");
          error.code = "mesh-timeout";
          reject(error);
        }, MESH_WAIT_TIMEOUT_MS);
      }),
    ]).finally(() => global.clearTimeout(timer));
  }

  function readSwitch() {
    if (switchReason !== undefined) return switchReason;
    switchReason = null;
    try {
      const search = global.location && global.location.search ? global.location.search : "";
      const value = new URLSearchParams(search).get("meshKernel");
      if (value === "0" || value === "false" || value === "off" || value === "vanilla") switchReason = "url";
    } catch (_) { /* no location in this realm */ }
    try {
      if (!switchReason && global.localStorage && global.localStorage.getItem("gaius.meshKernel") === "off") {
        switchReason = "setting";
      }
    } catch (_) { /* storage blocked */ }
    return switchReason;
  }

  function ensureRuntime() {
    if (runtimeState !== "none") return;
    const kernels = global.__gaiusKernels;
    // The boot script may not have installed the runtime yet; try again on the next call.
    if (!kernels || typeof kernels.runtime !== "function") return;
    if (typeof kernels.enabled === "function" && !kernels.enabled()) {
      runtimeState = "failed";
      disabledReason = "kernels-off";
      return;
    }
    runtimeState = "pending";
    Promise.resolve().then(() => kernels.runtime()).then((created) => {
      runtime = created || null;
      runtimeState = created ? "ready" : "failed";
      if (!created) disabledReason = "runtime-unavailable";
      else preload();
    }, (error) => {
      runtimeState = "failed";
      disabledReason = "runtime-failed: " + message(error);
    });
  }

  function ready() {
    if (!global.GaiusKernelMirror || !global.GaiusKernelMirror.enabled(KERNEL)) return false;
    if (disabledReason || readSwitch()) return false;
    const codec = global.GaiusMeshJob;
    if (!codec || !codec.enabled()) return false;
    ensureRuntime();
    return runtimeState === "ready" && runtime.available(KERNEL);
  }

  function submitToRuntime(kind, payload, opts) {
    return runtime.submit(kind, payload, Object.assign({kernel: KERNEL}, opts || {}));
  }

  function preload() {
    if (!session || runtimeState !== "ready" || !runtime.available(KERNEL)) return;
    const current = session;
    bounded(current.preload(submitToRuntime, {key: "mesher-table", version: current.epoch, visible: true}), "model-table")
      .catch((error) => {
        stats.preloadErrors++;
        stats.lastError = "preload: " + message(error);
        if (error && error.code === "mesh-timeout") {
          disable("model-table-timeout");
          cancelAll();
          try { console.warn("[Gaius mesh] model table timed out; using vanilla compiler", error); } catch (_) {}
        }
      });
  }

  function setTable(bytes, length, epoch) {
    if (!global.GaiusKernelMirror || !global.GaiusKernelMirror.enabled(KERNEL)) return false;
    const codec = global.GaiusMeshJob;
    if (!codec) return false;
    const count = length >>> 0;
    const copy = new Uint8Array(count);
    copy.set(new Uint8Array(bytes.buffer, bytes.byteOffset, count));
    const next = codec.session(copy, epoch >>> 0);
    // Jobs of another table would come back with its UVs and model choices: drop them, the Java
    // side then compiles those sections with the vanilla compiler.
    if (session !== null && sessionEpoch !== (epoch >>> 0)) cancelAll();
    session = next;
    sessionEpoch = epoch >>> 0;
    stats.tables++;
    stats.tableBytes = count;
    stats.tableEpoch = sessionEpoch;
    // Java decides whether this table may be persisted (storeTable): only a fresh export made
    // with built-in resource packs is.
    tableCache.current = copy;
    preload();
    return true;
  }

  function storeTable(scope) {
    if (typeof scope !== "string" || !scope || tableCache.current === null) return;
    const record = {bytes: tableCache.current, scope};
    // Persisted after the cache read has settled. A cached table keeps the epoch encoded by
    // Java, so the bytes handed to the worker stay byte-identical to the exporter's table.
    if (tableCache.ready) persistTable(record);
    else tableCache.pending = record;
  }

  function takeCachedTable(scope) {
    if (!tableCache.ready || tableCache.consumed || !tableCache.entry) return null;
    if (typeof scope !== "string" || scope !== tableCache.entryScope) return null;
    tableCache.consumed = true;
    stats.tableCacheHits++;
    const copy = new Int8Array(tableCache.entry.byteLength);
    copy.set(new Int8Array(tableCache.entry.buffer, tableCache.entry.byteOffset, tableCache.entry.byteLength));
    tableCache.entry = null;
    stats.tableCacheValidated++;
    return copy;
  }

  function tableCacheReady() {
    return tableCache.ready;
  }

  function hasTable(epoch) {
    return session !== null && sessionEpoch === (epoch >>> 0);
  }

  function copyOf(view, Type, count) {
    const out = new Type(count);
    out.set(new Type(view.buffer, view.byteOffset, count));
    return out;
  }

  function finish(record, key) {
    inFlight--;
    if (key !== undefined && outstanding.get(key) === record.ticket) outstanding.delete(key);
    if (record.ok) {
      const result = record.result;
      record.status = result.statusCode;
      record.quads = [result.layers.solid.quadCount, result.layers.cutout.quadCount, result.layers.translucent.quadCount];
      record.visLo = result.visibilityLo | 0;
      record.visHi = result.visibilityHi | 0;
      record.detail = result.detail | 0;
      if (record.status === 0) stats.meshed++;
      else stats.needsVanilla++;
    } else {
      record.status = -1;
      record.quads = [0, 0, 0];
      record.visLo = 0;
      record.visHi = 0;
      record.detail = 0;
      if (record.code === "superseded" || record.code === "stale" || record.code === "cancelled") {
        stats.superseded++;
      } else {
        stats.failed++;
        stats.lastError = record.code + ": " + record.message;
      }
    }
    done.push(record);
    if (wake && wakeArmed) {
      wakeArmed = false;
      try {
        wake();
      } catch (_) {
        wakeArmed = true;
      }
    }
  }

  // header: Int32Array (HEADER); ids: Int16Array or Int32Array (20^3 global state ids, the
  // region of GaiusMeshJob.regionIndex); light, quarts, swamp: Int8Array; palette: Int32Array of
  // 5 ints per biome; floats: Float32Array cardinal[6] then camera[3]. Every array is a view of a
  // reused Java array, so this copies before anything asynchronous happens.
  function submitJob(header, ids, light, quarts, palette, swamp, floats) {
    if (!session || runtimeState !== "ready" || !runtime.available(KERNEL)) return false;
    const codec = global.GaiusMeshJob;
    if (!codec || !codec.enabled()) return false;
    if ((header[HEADER.EPOCH] >>> 0) !== sessionEpoch) return false;
    const ticket = header[HEADER.TICKET] | 0;
    const wide = header[HEADER.WIDE] !== 0;
    const flags = header[HEADER.FLAGS] | 0;
    const sx = header[HEADER.SX] | 0;
    const sy = header[HEADER.SY] | 0;
    const sz = header[HEADER.SZ] | 0;
    const requestSeq = header[HEADER.REQUEST_SEQ] >>> 0;
    const paletteCount = header[HEADER.PALETTE_COUNT] | 0;
    if (paletteCount < 1 || paletteCount > 256) return false;
    const blend = header[HEADER.BLEND] | 0;
    if (blend < 0 || blend > MAX_BIOME_BLEND) return false;
    const seed = BigInt.asIntN(64, (BigInt(header[HEADER.SEED_HI] | 0) << BigInt(32))
      | BigInt(header[HEADER.SEED_LO] >>> 0));
    const input = {
      requestSeq,
      sectionVersion: requestSeq,
      section: [sx, sy, sz],
      ao: (flags & FLAG_AO) !== 0,
      cutoutLeaves: (flags & FLAG_CUTOUT_LEAVES) !== 0,
      vanilla: true,
      compact: false,
      sortTranslucent: true,
      centroids: false,
      cardinal: [floats[0], floats[1], floats[2], floats[3], floats[4], floats[5]],
      camera: [floats[6], floats[7], floats[8]],
      biomeZoomSeed: seed,
      states: wide ? copyOf(ids, Uint32Array, VOLUME) : copyOf(ids, Uint16Array, VOLUME),
      light: copyOf(light, Uint8Array, VOLUME),
      biomeQuarts: copyOf(quarts, Uint8Array, QUART_VOLUME),
      biomePalette: copyOf(palette, Int32Array, paletteCount * 5),
      biomeBlend: blend,
      swampMask: copyOf(swamp, Uint8Array, SWAMP_BLEND_BYTES),
    };
    const key = "s" + sx + "," + sy + "," + sz;
    const epochAtSubmit = sessionEpoch;
    const opts = {
      key,
      // The runtime dedupes a key's queued job of the same version and rejects older versions as
      // stale. Java's request sequence restarts at 1 after a level reset, so the version is the
      // ticket, which only grows for the whole page.
      version: ticket,
      visible: true,
      cx: sx,
      cz: sz,
      distance: header[HEADER.DISTANCE] | 0,
      resultBytes: codec.estimateResultBytes(header[HEADER.NON_AIR] | 0, false),
    };
    // A recompile next to the camera is almost always the player's own edit: run it ahead of
    // the sections that merely stream in (kernel-policy counts priority in chunks of distance).
    if ((flags & FLAG_NEARBY) !== 0) opts.priority = -NEARBY_PRIORITY_CHUNKS;
    stats.submitted++;
    inFlight++;
    let pending;
    try {
      pending = bounded(session.mesh(submitToRuntime, input, opts), "section");
    } catch (error) {
      inFlight--;
      stats.failed++;
      stats.lastError = "submit: " + message(error);
      return false;
    }
    outstanding.set(key, ticket);
    pending.then(
      (result) => {
        // Last line of defence against a result that answers another request (a different
        // table, section or request): it never installs, the section goes to vanilla.
        if (sessionEpoch !== epochAtSubmit || (result.tableEpoch >>> 0) !== epochAtSubmit
            || (result.requestSeq >>> 0) !== requestSeq || result.section[0] !== sx
            || result.section[1] !== sy || result.section[2] !== sz) {
          finish({ticket, ok: false, code: "stale", message: "result does not match the request", result: null}, key);
          return;
        }
        finish({ticket, ok: true, code: null, message: null, result}, key);
      },
      (error) => {
        if (error && error.code === "mesh-timeout") {
          disable("section-timeout");
          cancelAll();
          try { console.warn("[Gaius mesh] section timed out; using vanilla compiler", error); } catch (_) {}
        }
        finish({
        ticket, ok: false, code: (error && error.code) || "error", message: message(error), result: null,
        }, key);
      });
    return true;
  }

  // Cancels every section job still out. Their promises reject with "cancelled", so the Java side
  // still gets one record per ticket and settles its in-flight bookkeeping.
  function cancelAll() {
    if (outstanding.size === 0) return 0;
    const keys = Array.from(outstanding.keys());
    outstanding.clear();
    if (!runtime || typeof runtime.cancel !== "function") return 0;
    let cancelled = 0;
    keys.forEach((key) => {
      try {
        if (runtime.cancel(key, KERNEL) !== false) cancelled++;
      } catch (_) { /* the runtime is going away; the Java answer timeout covers the job */ }
    });
    stats.cancelledAll += cancelled;
    return cancelled;
  }

  function poll() {
    if (head >= done.length) {
      if (done.length > 0) {
        done = [];
        head = 0;
      }
      wakeArmed = true;
      return null;
    }
    const record = done[head];
    done[head++] = undefined;
    return record;
  }

  // Java copied everything it needs out of the record (MeshKernelHooks.compile): its buffer goes
  // back to a kernel worker for reuse. Views into it must not be used afterwards. ?gaiusKernelRecycle=0
  // keeps every buffer for the collector.
  function release(record) {
    if (!record || record.released) return false;
    record.released = true;
    const result = record.result;
    if (!result || !result.buffer || !runtime || typeof runtime.recycle !== "function" || !recycleEnabled) return false;
    record.result = null;
    stats.recycled++;
    return runtime.recycle(result.buffer);
  }

  function setWake(fn) {
    wake = typeof fn === "function" ? fn : null;
    wakeArmed = true;
  }

  function disable(reason) {
    if (!disabledReason) disabledReason = String(reason || "disabled");
    stats.lastError = "disabled: " + disabledReason;
    try {
      global.__gaiusMeshKernelTelemetry = Object.assign(global.__gaiusMeshKernelTelemetry || {}, {
        meshKernelDisabled: true,
        meshKernelDisableReason: disabledReason,
        meshKernelTimeoutMs: MESH_WAIT_TIMEOUT_MS,
      });
    } catch (_) {}
  }

  function status() {
    const codec = global.GaiusMeshJob;
    return Object.assign({
      ready: ready(),
      runtime: runtimeState,
      disabledReason: disabledReason || readSwitch(),
      codec: codec ? codec.status() : null,
      inFlight,
      queued: done.length - head,
      tableCache: {
        key: tableCache.key,
        ready: tableCache.ready,
        loaded: tableCache.entry ? tableCache.entry.byteLength : 0,
        consumed: tableCache.consumed,
        hits: stats.tableCacheHits,
        stores: stats.tableCacheStores,
        validated: stats.tableCacheValidated,
        errors: stats.tableCacheErrors,
      },
    }, stats);
  }

  global.__gaiusMeshKernel = Object.freeze({
    version: 1, HEADER, ready, setTable, storeTable, takeCachedTable, tableCacheReady, hasTable, submitJob,
    cancelAll, poll, release,
    setWake, disable, status,
  });
})(typeof globalThis !== "undefined" ? globalThis : self);
