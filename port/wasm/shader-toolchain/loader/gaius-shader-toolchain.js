/*
 * Gaius browser shader toolchain (PLAN D5, backend T1; contract C7).
 *
 * Minecraft 26.3 compiles every render pipeline GLSL -> SPIR-V with shaderc and
 * reflects/decompiles the SPIR-V with SPIRV-Cross (spvc).  In the browser the
 * patched LWJGL entry points land in org.lwjgl.util.shaderc.BrowserShaderc and
 * org.lwjgl.util.spvc.BrowserSpvc, whose default backends
 * (BrowserShadercWasm, BrowserSpvcWasm) call the synchronous API below.  It
 * drives the WebAssembly builds of the exact shaderc / SPIRV-Cross revisions of
 * the LWJGL 3.4.3 natives (port/wasm/shader-toolchain).
 *
 * Page contract: this script sets window.__gaiusShaderToolchainReady, a
 * Promise that resolves to the API once both modules are instantiated; it is
 * also installed as window.__gaiusShaderToolchain.  The launcher awaits the
 * promise before it calls the TeaVM main(args).  Asset URLs default to
 * siblings of this script (same ?v= token); a portable page sets
 * window.__gaiusShaderToolchainUrls = {shadercJs, shadercWasm, spvcJs, spvcWasm,
 * version} (and/or window.__gaiusPortableAssetsReady) before this script runs.
 * data-profile on the script element names the IndexedDB result cache.
 *
 * Failure model: shaderc jobs are self-contained (the Java side keeps options
 * and pre-resolved includes), and every spvc call carries the module
 * generation of its context.  Any exception out of a WebAssembly call (trap,
 * abort, out of memory) retires that instance: the failing shaderc job reports
 * an internal error, stale spvc handles are rejected with an error code, and
 * the next call runs on a spare instance that was started in advance (a new
 * spare is then instantiated in the background).
 *
 * Node (tests): require() this file for {createToolchain}.
 */
(function (root) {
  "use strict";

  var API_VERSION = 1;
  // shaderc_compilation_status_internal_error
  var SHADERC_STATUS_INTERNAL_ERROR = 3;
  // spvc_result returned for a call on a retired module or a stale handle
  var SPVC_ERROR_OUT_OF_MEMORY = -3;
  // Option codes of BrowserShaderc jobs (see BrowserShadercJob).
  var OPTION_TARGET_ENV = 1;
  var OPTION_AUTO_BIND_UNIFORMS = 2;
  var OPTION_PRESERVE_BINDINGS = 3;
  var OPTION_GENERATE_DEBUG_INFO = 4;
  var OPTION_OPTIMIZATION_LEVEL = 5;
  // The blaze3d front-end of 26.2 and 1.21.11 (GlslCompiler sets it; renderpearl does not).
  var OPTION_AUTO_MAP_LOCATIONS = 6;
  // The export each option code calls.
  var OPTION_EXPORTS = {
    1: "_shaderc_compile_options_set_target_env",
    2: "_shaderc_compile_options_set_auto_bind_uniforms",
    3: "_shaderc_compile_options_set_preserve_bindings",
    4: "_shaderc_compile_options_set_generate_debug_info",
    5: "_shaderc_compile_options_set_optimization_level",
    6: "_shaderc_compile_options_set_auto_map_locations"
  };

  function describe(error) {
    if (error && typeof error === "object") {
      var text = String(error.message || error);
      if (error.name && text.indexOf(error.name) !== 0) text = error.name + ": " + text;
      return text;
    }
    return String(error);
  }

  function bytesOf(view) {
    if (view === null || view === undefined) return null;
    if (view instanceof Uint8Array) return view;
    if (ArrayBuffer.isView(view)) return new Uint8Array(view.buffer, view.byteOffset, view.byteLength);
    if (view instanceof ArrayBuffer) return new Uint8Array(view);
    throw new TypeError("gaius shader toolchain: expected a typed array");
  }

  function copyOf(view) {
    var bytes = bytesOf(view);
    return bytes === null ? null : new Uint8Array(bytes);
  }

  function asciiBytes(text) {
    var bytes = new Uint8Array(text.length);
    for (var i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i) & 0x7f;
    return bytes;
  }

  // The bytes of a key made by byteKey/includeKey (every char is < 256).
  function asciiBytesExact(text) {
    var bytes = new Uint8Array(text.length);
    for (var i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
    return bytes;
  }

  // Latin-1 view of raw bytes: an exact, reversible key for byte strings.
  function byteKey(bytes) {
    var text = "";
    for (var i = 0; i < bytes.length; i += 4096) {
      text += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(bytes.length, i + 4096)));
    }
    return text;
  }

  function cStringBytes(M, pointer) {
    if (!pointer) return new Uint8Array(0);
    var heap = M.HEAPU8;
    var end = pointer;
    while (heap[end] !== 0) end++;
    return heap.subarray(pointer, end);
  }

  function includeKey(type, requested, requesting) {
    return (type | 0) + "\u0000" + byteKey(requested) + "\u0000" + byteKey(requesting);
  }

  function allocBytes(M, bytes, nulTerminated) {
    var length = bytes ? bytes.length : 0;
    var pointer = M._malloc(Math.max(1, length + (nulTerminated ? 1 : 0)));
    if (!pointer) throw new Error("WebAssembly heap exhausted (" + length + " bytes)");
    if (length) M.HEAPU8.set(bytes, pointer);
    if (nulTerminated) M.HEAPU8[pointer + length] = 0;
    return pointer;
  }

  // SHA-256 (FIPS 180-4), synchronous: shaderc cache keys are computed inside
  // the synchronous compile call, where crypto.subtle (async) cannot be used.
  var SHA256_K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ]);

  function Sha256() {
    this.h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
      0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
    this.block = new Uint8Array(64);
    this.used = 0;
    this.length = 0;
    this.w = new Uint32Array(64);
  }

  Sha256.prototype.compress = function (bytes, offset) {
    var w = this.w;
    var h = this.h;
    var i;
    for (i = 0; i < 16; i++) {
      var j = offset + i * 4;
      w[i] = (bytes[j] << 24) | (bytes[j + 1] << 16) | (bytes[j + 2] << 8) | bytes[j + 3];
    }
    for (i = 16; i < 64; i++) {
      var x = w[i - 15];
      var y = w[i - 2];
      var s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
      var s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], k = h[7];
    for (i = 0; i < 64; i++) {
      var t1 = (k + (((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7)))
        + ((e & f) ^ (~e & g)) + SHA256_K[i] + w[i]) | 0;
      var t2 = ((((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10)))
        + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      k = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += k;
  };

  Sha256.prototype.update = function (bytes) {
    var index = 0;
    this.length += bytes.length;
    if (this.used > 0) {
      var take = Math.min(64 - this.used, bytes.length);
      this.block.set(bytes.subarray(0, take), this.used);
      this.used += take;
      index = take;
      if (this.used < 64) return this;
      this.compress(this.block, 0);
      this.used = 0;
    }
    for (; index + 64 <= bytes.length; index += 64) this.compress(bytes, index);
    if (index < bytes.length) {
      this.block.set(bytes.subarray(index), 0);
      this.used = bytes.length - index;
    }
    return this;
  };

  Sha256.prototype.updateInt = function (value) {
    return this.update(new Uint8Array([value >>> 24, (value >>> 16) & 255, (value >>> 8) & 255, value & 255]));
  };

  // A length-prefixed field, so different splits of the same bytes never collide.
  Sha256.prototype.updateField = function (bytes) {
    if (bytes === null) return this.updateInt(0xffffffff);
    return this.updateInt(bytes.length).update(bytes);
  };

  Sha256.prototype.hex = function () {
    var bits = this.length * 8;
    var tail = new Uint8Array(this.used < 56 ? 64 : 128);
    tail.set(this.block.subarray(0, this.used));
    tail[this.used] = 0x80;
    var view = new DataView(tail.buffer);
    view.setUint32(tail.length - 8, Math.floor(bits / 0x100000000));
    view.setUint32(tail.length - 4, bits >>> 0);
    this.compress(tail, 0);
    if (tail.length === 128) this.compress(tail, 64);
    var text = "";
    for (var i = 0; i < 8; i++) text += ("00000000" + this.h[i].toString(16)).slice(-8);
    return text;
  };

  function sha256Hex(bytes) {
    return new Sha256().update(bytes).hex();
  }

  // ---------------------------------------------------------------------------------------
  // One emscripten module.  A retired instance cannot be replaced synchronously
  // (the emscripten factory only finishes start-up asynchronously, and browsers
  // limit synchronous compilation on the main thread), so every slot keeps a
  // started spare instance of the same compiled WebAssembly.Module: a failure
  // promotes the spare within the failing call's successor, and a new spare is
  // instantiated in the background.
  function ModuleSlot(name, factory, wasmModule) {
    this.name = name;
    this.factory = factory;
    this.wasmModule = wasmModule;
    this.setup = null;
    this.M = null;
    this.state = null;
    this.spare = null;
    this.sparePromise = null;
    this.generation = 0;
    this.instantiations = 0;
    this.failures = 0;
    this.spareFailures = 0;
    this.recycles = 0;
    this.lastError = null;
  }

  ModuleSlot.prototype.create = function () {
    var slot = this;
    // The emscripten factory promise only settles through receiveInstance and has
    // no rejection path, so a failed instantiation (for example an out-of-memory
    // WebAssembly.Memory) would leave it pending forever: race it against a
    // promise that rejects with the instantiation error.
    var rejectInstantiation = null;
    var instantiationFailed = new Promise(function (resolve, reject) {
      rejectInstantiation = reject;
    });
    instantiationFailed.catch(function () {});
    var moduleArg = {
      instantiateWasm: function (imports, receiveInstance) {
        WebAssembly.instantiate(slot.wasmModule, imports).then(function (instance) {
          receiveInstance(instance, slot.wasmModule);
        }, function (error) {
          slot.lastError = slot.name + " instantiation: " + describe(error);
          rejectInstantiation(new Error(slot.lastError));
        });
        return {};
      },
      print: function () {},
      printErr: function (text) {
        if (typeof console !== "undefined") console.warn("[gaius " + slot.name + "] " + text);
      }
    };
    var started;
    try {
      started = Promise.resolve(slot.factory(moduleArg));
    } catch (error) {
      started = Promise.reject(error);
    }
    return Promise.race([started, instantiationFailed]).then(function (M) {
      M = M || moduleArg;
      if (typeof M._malloc !== "function" || !M.HEAPU8) {
        throw new Error(slot.name + " module has no heap after start-up");
      }
      slot.instantiations++;
      return M;
    });
  };

  // Starts a spare instance unless one is ready or on its way.
  ModuleSlot.prototype.prepareSpare = function () {
    var slot = this;
    if (slot.spare !== null || slot.sparePromise !== null) return slot.sparePromise || Promise.resolve();
    slot.sparePromise = slot.create().then(function (M) {
      slot.spare = M;
      slot.sparePromise = null;
    }, function (error) {
      slot.sparePromise = null;
      slot.spareFailures++;
      slot.lastError = slot.name + " spare instance: " + describe(error);
    });
    return slot.sparePromise;
  };

  // The live instance; promotes the spare when the previous one was retired.
  // Returns null while no instance is ready (a replacement is still starting).
  ModuleSlot.prototype.live = function () {
    if (this.M !== null) return this.M;
    if (this.spare === null) {
      this.prepareSpare();
      return null;
    }
    var M = this.spare;
    this.spare = null;
    var state = this.setup ? this.setup(M) : null;
    this.M = M;
    this.state = state;
    this.generation++;
    this.prepareSpare();
    return M;
  };

  ModuleSlot.prototype.retire = function (error) {
    this.M = null;
    this.state = null;
    this.failures++;
    this.lastError = this.name + " generation " + this.generation + ": " + describe(error);
    if (typeof console !== "undefined") {
      console.warn("[gaius shader toolchain] retired the " + this.name
        + " WebAssembly instance after a failure; the next call uses a fresh one", error);
    }
    this.prepareSpare();
    return this.lastError;
  };

  // Drops the live instance on purpose (no open handles): the next call promotes the spare,
  // a fresh instance whose memory has not grown, and a new spare starts in the background.
  ModuleSlot.prototype.recycle = function () {
    this.M = null;
    this.state = null;
    this.recycles++;
    this.prepareSpare();
  };

  ModuleSlot.prototype.stats = function () {
    return {
      live: this.M !== null,
      spareReady: this.spare !== null,
      generation: this.generation,
      instantiations: this.instantiations,
      failures: this.failures,
      spareFailures: this.spareFailures,
      recycles: this.recycles,
      memoryBytes: this.M !== null && this.M.HEAPU8 ? this.M.HEAPU8.length : 0,
      lastError: this.lastError
    };
  };

  // ---------------------------------------------------------------------------------------
  function createApi(shadercSlot, spvcSlot, cacheVersion) {
    var api = {version: API_VERSION};
    // Content-addressed shaderc results (PLAN D5): the key hashes every input of
    // a job plus the toolchain version, so a hit is exactly the bytes shaderc
    // would produce.  Only successful compilations are kept.
    var cache = {
      enabled: true, version: String(cacheVersion || "dev"), entries: new Map(), hits: 0, misses: 0,
      db: null, dbName: null, pending: [], flushTimer: null, loaded: 0, stored: 0, evicted: 0, lastError: null
    };
    var jobs = new Map();
    var nextJob = 1;
    var outs = [0, 0];
    var counters = {shadercCompiles: 0, shadercMs: 0, spvcCompiles: 0, spvcMs: 0};
    var now = (typeof performance !== "undefined" && performance.now)
      ? function () { return performance.now(); } : function () { return Date.now(); };

    // spvc's emscripten heap only grows: translating the start-up pipelines left the live
    // instance at 103 MB while a fresh one has 17 MB.  Once no spvc context is open and the
    // toolchain has been idle for SPVC_RECYCLE_IDLE_MS, a grown instance is recycled (the
    // spare takes over on the next call), so the heap of a pipeline burst does not stay
    // committed for the rest of the session.
    var SPVC_RECYCLE_IDLE_MS = 15000;
    var SPVC_RECYCLE_MIN_BYTES = 48 * 1024 * 1024;
    var spvcOpenContexts = 0;
    var spvcLastUse = 0;
    var spvcRecycleTimer = null;

    function armSpvcRecycle() {
      if (spvcRecycleTimer !== null || typeof setTimeout !== "function") return;
      spvcRecycleTimer = setTimeout(recycleIdleSpvc, SPVC_RECYCLE_IDLE_MS);
      if (spvcRecycleTimer && typeof spvcRecycleTimer.unref === "function") spvcRecycleTimer.unref();
    }

    function recycleIdleSpvc() {
      spvcRecycleTimer = null;
      var M = spvcSlot.M;
      if (M === null) return;
      // The spare must be ready: the next call has to find an instance without waiting.
      if (spvcOpenContexts > 0 || spvcSlot.spare === null || now() - spvcLastUse < SPVC_RECYCLE_IDLE_MS) {
        armSpvcRecycle();
        return;
      }
      if (!M.HEAPU8 || M.HEAPU8.length < SPVC_RECYCLE_MIN_BYTES) return;
      spvcSlot.recycle();
    }

    // ---- shaderc --------------------------------------------------------------------
    function job(id) {
      var value = jobs.get(id | 0);
      if (!value) throw new Error("gaius shader toolchain: unknown shaderc job " + id);
      return value;
    }

    shadercSlot.setup = function (M) {
      var state = {compiler: 0, job: null, resolver: 0, releaser: 0};
      state.resolver = M.addFunction(function (userData, requested, type, requesting, depth) {
        return resolveInclude(M, state, requested, type, requesting);
      }, "iiiiii");
      state.releaser = M.addFunction(function (userData, result) {
        releaseInclude(M, result);
      }, "vii");
      state.compiler = M._shaderc_compiler_initialize();
      if (!state.compiler) throw new Error("shaderc_compiler_initialize failed");
      return state;
    };

    function resolveInclude(M, state, requested, type, requesting) {
      var current = state.job;
      var requestedBytes = cStringBytes(M, requested);
      var key = includeKey(type, requestedBytes, cStringBytes(M, requesting));
      var entry = current ? current.includes.get(key) : undefined;
      var sourceName;
      var content;
      if (entry) {
        sourceName = entry.sourceName;
        content = entry.content;
        current.includeLookups++;
      } else {
        // An error result: empty source_name, the message as content.
        sourceName = new Uint8Array(0);
        content = asciiBytes("Gaius shader toolchain: the include \"" + byteKey(requestedBytes)
          + "\" was not resolved before compilation");
        if (current) current.includeMisses++;
      }
      var result = M._malloc(20);
      if (!result) throw new Error("WebAssembly heap exhausted (include result)");
      var namePointer = allocBytes(M, sourceName, true);
      var contentPointer = allocBytes(M, content, true);
      var words = M.HEAPU32;
      words[result >> 2] = namePointer;
      words[(result >> 2) + 1] = sourceName.length;
      words[(result >> 2) + 2] = contentPointer;
      words[(result >> 2) + 3] = content.length;
      words[(result >> 2) + 4] = 0;
      return result;
    }

    function releaseInclude(M, result) {
      if (!result) return;
      var words = M.HEAPU32;
      M._free(words[result >> 2]);
      M._free(words[(result >> 2) + 2]);
      M._free(result);
    }

    function applyOption(M, options, op) {
      switch (op.code) {
        case OPTION_TARGET_ENV:
          M._shaderc_compile_options_set_target_env(options, op.a, op.b);
          break;
        case OPTION_AUTO_BIND_UNIFORMS:
          M._shaderc_compile_options_set_auto_bind_uniforms(options, op.a ? 1 : 0);
          break;
        case OPTION_PRESERVE_BINDINGS:
          M._shaderc_compile_options_set_preserve_bindings(options, op.a ? 1 : 0);
          break;
        case OPTION_GENERATE_DEBUG_INFO:
          M._shaderc_compile_options_set_generate_debug_info(options);
          break;
        case OPTION_OPTIMIZATION_LEVEL:
          M._shaderc_compile_options_set_optimization_level(options, op.a);
          break;
        case OPTION_AUTO_MAP_LOCATIONS:
          M._shaderc_compile_options_set_auto_map_locations(options, op.a ? 1 : 0);
          break;
        default:
          throw new Error("gaius shader toolchain: unknown shaderc option " + op.code);
      }
    }

    // A job option the loaded module cannot apply: modules built before an export was added (the
    // auto map locations export came with the blaze3d front-end). The job fails cleanly instead of
    // retiring a healthy instance.
    function unsupportedOption(M, ops) {
      for (var i = 0; i < ops.length; i++) {
        var name = OPTION_EXPORTS[ops[i].code];
        if (ops[i].code !== 0 && name && typeof M[name] !== "function") {
          var error = new Error("this gaius-shaderc.wasm does not export " + name.slice(1)
            + " (rebuild it with port/scripts/build-wasm-shader-toolchain.sh)");
          error.gaiusUnsupported = true;
          return error;
        }
      }
      return null;
    }

    // Whether the live shaderc module can apply option `code` (false while no instance is ready).
    api.shadercSupportsOption = function (code) {
      var M = shadercSlot.live();
      var name = OPTION_EXPORTS[code | 0];
      return M !== null && !!name && typeof M[name] === "function";
    };

    api.shadercBegin = function () {
      var id = nextJob++;
      if (nextJob > 0x3fffffff) nextJob = 1;
      jobs.set(id, {
        ops: [], includes: new Map(), includeLookups: 0, includeMisses: 0,
        status: -1, bytes: null, error: "", warnings: 0, errors: 0
      });
      return id;
    };

    api.shadercOption = function (id, code, a, b) {
      job(id).ops.push({code: code | 0, a: a | 0, b: b | 0});
    };

    api.shadercMacro = function (id, name, value) {
      job(id).ops.push({code: 0, name: copyOf(name), value: copyOf(value)});
    };

    api.shadercInclude = function (id, type, requested, requesting, sourceName, content) {
      job(id).includes.set(includeKey(type, bytesOf(requested), bytesOf(requesting)), {
        sourceName: copyOf(sourceName), content: copyOf(content)
      });
    };

    function jobKey(current, source, kind, fileName, entryPoint) {
      var hash = new Sha256().updateField(asciiBytes("gaius-shaderc-job-v1"))
        .updateField(asciiBytes(cache.version)).updateInt(kind | 0)
        .updateField(bytesOf(fileName)).updateField(bytesOf(entryPoint)).updateInt(current.ops.length);
      for (var i = 0; i < current.ops.length; i++) {
        var op = current.ops[i];
        hash.updateInt(op.code);
        if (op.code === 0) hash.updateField(op.name).updateField(op.value);
        else hash.updateInt(op.a).updateInt(op.b);
      }
      var keys = Array.from(current.includes.keys()).sort();
      hash.updateInt(keys.length);
      for (i = 0; i < keys.length; i++) {
        var entry = current.includes.get(keys[i]);
        hash.updateField(asciiBytesExact(keys[i])).updateField(entry.sourceName).updateField(entry.content);
      }
      return hash.updateField(bytesOf(source)).hex();
    }

    api.shadercCompile = function (id, source, kind, fileName, entryPoint) {
      var current = job(id);
      var started = now();
      counters.shadercCompiles++;
      var key = null;
      if (cache.enabled) {
        key = jobKey(current, source, kind, fileName, entryPoint);
        var hit = cache.entries.get(key);
        if (hit) {
          cache.hits++;
          hit.used = Date.now();
          current.status = 0;
          current.bytes = hit.spirv;
          current.error = hit.error;
          current.warnings = hit.warnings;
          current.errors = hit.errors;
          counters.shadercMs += now() - started;
          return 0;
        }
        cache.misses++;
      }
      var M = null;
      try {
        M = shadercSlot.live();
        if (M === null) {
          throw new Error("no shaderc instance is ready yet (a replacement is starting)");
        }
        var state = shadercSlot.state;
        var unsupported = unsupportedOption(M, current.ops);
        if (unsupported) throw unsupported;
        var options = M._shaderc_compile_options_initialize();
        if (!options) throw new Error("shaderc_compile_options_initialize failed");
        for (var i = 0; i < current.ops.length; i++) {
          var op = current.ops[i];
          if (op.code === 0) {
            var namePointer = allocBytes(M, op.name, false);
            var valuePointer = op.value === null ? 0 : allocBytes(M, op.value, false);
            M._shaderc_compile_options_add_macro_definition(options, namePointer, op.name.length,
              valuePointer, op.value === null ? 0 : op.value.length);
            M._free(namePointer);
            if (valuePointer) M._free(valuePointer);
          } else {
            applyOption(M, options, op);
          }
        }
        M._shaderc_compile_options_set_include_callbacks(options, state.resolver, state.releaser, 0);
        var sourceBytes = bytesOf(source);
        var sourcePointer = allocBytes(M, sourceBytes, false);
        var filePointer = allocBytes(M, bytesOf(fileName), true);
        var entryPointer = allocBytes(M, bytesOf(entryPoint), true);
        state.job = current;
        var result;
        try {
          result = M._shaderc_compile_into_spv(state.compiler, sourcePointer, sourceBytes.length,
            kind | 0, filePointer, entryPointer, options);
        } finally {
          state.job = null;
        }
        M._free(sourcePointer);
        M._free(filePointer);
        M._free(entryPointer);
        M._shaderc_compile_options_release(options);
        if (!result) throw new Error("shaderc_compile_into_spv returned no result");
        current.status = M._shaderc_result_get_compilation_status(result) | 0;
        var length = M._shaderc_result_get_length(result) >>> 0;
        var bytesPointer = M._shaderc_result_get_bytes(result);
        current.bytes = length && bytesPointer
          ? new Uint8Array(M.HEAPU8.subarray(bytesPointer, bytesPointer + length)) : new Uint8Array(0);
        current.error = M.UTF8ToString(M._shaderc_result_get_error_message(result));
        current.warnings = M._shaderc_result_get_num_warnings(result) >>> 0;
        current.errors = M._shaderc_result_get_num_errors(result) >>> 0;
        M._shaderc_result_release(result);
        if (key !== null && current.status === 0 && current.includeMisses === 0) {
          remember(key, {spirv: current.bytes, error: current.error, warnings: current.warnings,
            errors: current.errors, used: Date.now()});
        }
      } catch (error) {
        current.status = SHADERC_STATUS_INTERNAL_ERROR;
        current.bytes = new Uint8Array(0);
        current.error = "Gaius shader toolchain: shaderc failed ("
          + (M === null || error.gaiusUnsupported ? describe(error) : shadercSlot.retire(error)) + ")";
        current.warnings = 0;
        current.errors = 1;
      }
      counters.shadercMs += now() - started;
      return current.status;
    };

    api.shadercOutputLength = function (id) {
      var bytes = job(id).bytes;
      return bytes ? bytes.length : 0;
    };

    api.shadercOutputCopy = function (id, out) {
      var bytes = job(id).bytes;
      var target = bytesOf(out);
      if (!bytes || target.length !== bytes.length) {
        throw new Error("gaius shader toolchain: output buffer size mismatch");
      }
      target.set(bytes);
    };

    api.shadercErrorMessage = function (id) { return job(id).error; };
    api.shadercWarnings = function (id) { return job(id).warnings; };
    api.shadercErrors = function (id) { return job(id).errors; };
    api.shadercIncludeMisses = function (id) { return job(id).includeMisses; };
    api.shadercEnd = function (id) { jobs.delete(id | 0); };

    // ---- spvc -------------------------------------------------------------------------
    spvcSlot.setup = function (M) {
      var scratch = M._malloc(16);
      if (!scratch) throw new Error("WebAssembly heap exhausted (spvc scratch)");
      return {scratch: scratch, list: 0, listCount: 0, source: null};
    };

    // Runs fn(M, state) when gen is the live generation; any exception retires
    // the instance.  failValue is returned for stale or failing calls.
    function spvc(gen, failValue, fn) {
      if (spvcSlot.M === null || (gen | 0) !== spvcSlot.generation) return failValue;
      spvcLastUse = now();
      armSpvcRecycle();
      try {
        return fn(spvcSlot.M, spvcSlot.state);
      } catch (error) {
        spvcSlot.retire(error);
        spvcOpenContexts = 0;
        return failValue;
      }
    }

    function out(M, state, index) {
      return M.HEAPU32[(state.scratch >> 2) + index];
    }

    // The generation new spvc contexts belong to, or -1 while no instance is ready.
    api.spvcGeneration = function () {
      try {
        return spvcSlot.live() === null ? -1 : spvcSlot.generation;
      } catch (error) {
        spvcSlot.retire(error);
        return -1;
      }
    };

    api.spvcOut = function (index) { return outs[index | 0] | 0; };

    api.spvcContextCreate = function (gen) {
      return spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M, state) {
        var result = M._spvc_context_create(state.scratch);
        outs[0] = out(M, state, 0);
        if (result === 0) spvcOpenContexts++;
        return result;
      });
    };

    api.spvcContextDestroy = function (gen, context) {
      spvc(gen, 0, function (M) {
        M._spvc_context_destroy(context);
        if (spvcOpenContexts > 0) spvcOpenContexts--;
        return 0;
      });
    };

    api.spvcContextLastError = function (gen, context) {
      return spvc(gen, null, function (M) {
        return M.UTF8ToString(M._spvc_context_get_last_error_string(context));
      });
    };

    api.spvcParseSpirv = function (gen, context, words, wordCount) {
      return spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M, state) {
        var bytes = bytesOf(words);
        if (bytes.length < wordCount * 4) throw new Error("SPIR-V buffer is shorter than its word count");
        var pointer = allocBytes(M, bytes.subarray(0, wordCount * 4), false);
        var result = M._spvc_context_parse_spirv(context, pointer, wordCount, state.scratch);
        outs[0] = out(M, state, 0);
        M._free(pointer);
        return result;
      });
    };

    api.spvcCreateCompiler = function (gen, context, backend, ir, captureMode) {
      return spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M, state) {
        var result = M._spvc_context_create_compiler(context, backend, ir, captureMode, state.scratch);
        outs[0] = out(M, state, 0);
        return result;
      });
    };

    api.spvcCreateCompilerOptions = function (gen, compiler) {
      return spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M, state) {
        var result = M._spvc_compiler_create_compiler_options(compiler, state.scratch);
        outs[0] = out(M, state, 0);
        return result;
      });
    };

    api.spvcOptionsSetBool = function (gen, options, option, value) {
      return spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M) {
        return M._spvc_compiler_options_set_bool(options, option, value ? 1 : 0);
      });
    };

    api.spvcOptionsSetUint = function (gen, options, option, value) {
      return spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M) {
        return M._spvc_compiler_options_set_uint(options, option, value >>> 0);
      });
    };

    api.spvcInstallCompilerOptions = function (gen, compiler, options) {
      return spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M) {
        return M._spvc_compiler_install_compiler_options(compiler, options);
      });
    };

    api.spvcCreateShaderResources = function (gen, compiler) {
      return spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M, state) {
        var result = M._spvc_compiler_create_shader_resources(compiler, state.scratch);
        outs[0] = out(M, state, 0);
        return result;
      });
    };

    // Lists one resource type; the entries are read with spvcResourceFill /
    // spvcResourceName until the next spvc call that lists resources.
    api.spvcResourceList = function (gen, resources, type) {
      return spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M, state) {
        var result = M._spvc_resources_get_resource_list_for_type(resources, type,
          state.scratch, state.scratch + 4);
        state.list = out(M, state, 0);
        state.listCount = result === 0 ? out(M, state, 1) : 0;
        outs[0] = state.listCount;
        return result;
      });
    };

    // Writes id, base_type_id and type_id of every listed resource.
    api.spvcResourceFill = function (gen, target) {
      spvc(gen, 0, function (M, state) {
        var ints = new Int32Array(target.buffer, target.byteOffset, target.length);
        var words = M.HEAPU32;
        for (var i = 0; i < state.listCount; i++) {
          var entry = (state.list >> 2) + i * 4;
          ints[i * 3] = words[entry];
          ints[i * 3 + 1] = words[entry + 1];
          ints[i * 3 + 2] = words[entry + 2];
        }
        return 0;
      });
    };

    api.spvcResourceName = function (gen, index) {
      return spvc(gen, null, function (M, state) {
        if (index < 0 || index >= state.listCount) throw new Error("resource index out of range");
        return M.UTF8ToString(M.HEAPU32[(state.list >> 2) + index * 4 + 3]);
      });
    };

    api.spvcGetDecoration = function (gen, compiler, id, decoration) {
      return spvc(gen, 0, function (M) {
        return M._spvc_compiler_get_decoration(compiler, id, decoration) | 0;
      });
    };

    api.spvcGetBinaryOffsetForDecoration = function (gen, compiler, id, decoration) {
      return spvc(gen, 0, function (M, state) {
        var found = M._spvc_compiler_get_binary_offset_for_decoration(compiler, id, decoration,
          state.scratch) ? 1 : 0;
        outs[0] = found ? out(M, state, 0) : 0;
        return found;
      });
    };

    api.spvcSetName = function (gen, compiler, id, name) {
      spvc(gen, 0, function (M) {
        var pointer = allocBytes(M, bytesOf(name), true);
        M._spvc_compiler_set_name(compiler, id, pointer);
        M._free(pointer);
        return 0;
      });
    };

    api.spvcGetName = function (gen, compiler, id) {
      return spvc(gen, null, function (M) {
        var pointer = M._spvc_compiler_get_name(compiler, id);
        return pointer ? M.UTF8ToString(pointer) : null;
      });
    };

    api.spvcSetEntryPoint = function (gen, compiler, name, model) {
      return spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M) {
        var pointer = allocBytes(M, bytesOf(name), true);
        var result = M._spvc_compiler_set_entry_point(compiler, pointer, model);
        M._free(pointer);
        return result;
      });
    };

    // Compiles; the source text is read with spvcCompiledSource().
    api.spvcCompile = function (gen, compiler) {
      var started = now();
      counters.spvcCompiles++;
      var result = spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M, state) {
        state.source = null;
        var code = M._spvc_compiler_compile(compiler, state.scratch);
        if (code === 0) state.source = M.UTF8ToString(out(M, state, 0));
        return code;
      });
      counters.spvcMs += now() - started;
      return result;
    };

    api.spvcCompiledSource = function (gen) {
      return spvc(gen, null, function (M, state) { return state.source; });
    };

    api.spvcGetDeclaredStructSize = function (gen, compiler, type) {
      return spvc(gen, SPVC_ERROR_OUT_OF_MEMORY, function (M, state) {
        var result = M._spvc_compiler_get_declared_struct_size(compiler, type, state.scratch);
        outs[0] = out(M, state, 0);
        return result;
      });
    };

    api.spvcGetTypeHandle = function (gen, compiler, typeId) {
      return spvc(gen, 0, function (M) {
        return M._spvc_compiler_get_type_handle(compiler, typeId) | 0;
      });
    };

    api.spvcTypeGetBasetype = function (gen, type) {
      return spvc(gen, 0, function (M) { return M._spvc_type_get_basetype(type) | 0; });
    };
    api.spvcTypeGetImageDimension = function (gen, type) {
      return spvc(gen, 0, function (M) { return M._spvc_type_get_image_dimension(type) | 0; });
    };
    api.spvcTypeGetVectorSize = function (gen, type) {
      return spvc(gen, 0, function (M) { return M._spvc_type_get_vector_size(type) | 0; });
    };
    api.spvcTypeGetNumArrayDimensions = function (gen, type) {
      return spvc(gen, 0, function (M) { return M._spvc_type_get_num_array_dimensions(type) | 0; });
    };
    api.spvcTypeGetArrayDimension = function (gen, type, dimension) {
      return spvc(gen, 0, function (M) { return M._spvc_type_get_array_dimension(type, dimension) | 0; });
    };

    // ---- shaderc result cache ----------------------------------------------------------
    var CACHE_MAX_ENTRIES = 4096;
    var CACHE_STORE = "spirv";

    function remember(key, entry) {
      cache.entries.set(key, entry);
      if (cache.entries.size > CACHE_MAX_ENTRIES) {
        cache.entries.delete(cache.entries.keys().next().value);
        cache.evicted++;
      }
      if (cache.db) {
        cache.pending.push({key: key, version: cache.version, spirv: entry.spirv, error: entry.error,
          warnings: entry.warnings, errors: entry.errors, used: entry.used});
        if (cache.flushTimer === null) cache.flushTimer = setTimeout(flushCache, 500);
      }
    }

    function flushCache() {
      cache.flushTimer = null;
      var batch = cache.pending;
      cache.pending = [];
      if (!cache.db || batch.length === 0) return Promise.resolve(0);
      return new Promise(function (resolve) {
        try {
          var transaction = cache.db.transaction(CACHE_STORE, "readwrite");
          var store = transaction.objectStore(CACHE_STORE);
          for (var i = 0; i < batch.length; i++) store.put(batch[i]);
          transaction.oncomplete = function () {
            cache.stored += batch.length;
            resolve(batch.length);
          };
          transaction.onerror = function () {
            cache.lastError = String(transaction.error);
            resolve(0);
          };
        } catch (error) {
          cache.lastError = describe(error);
          resolve(0);
        }
      });
    }

    api.setCacheEnabled = function (enabled) {
      cache.enabled = !!enabled;
    };

    api.clearCache = function () {
      cache.entries.clear();
      cache.hits = 0;
      cache.misses = 0;
    };

    // Loads this version's entries from the IndexedDB database `name` and keeps
    // new results there; entries of other toolchain versions are deleted.
    // Resolves to the number of loaded entries (0 when IndexedDB is unavailable).
    api.attachPersistentCache = function (name) {
      if (typeof indexedDB === "undefined" || !name) return Promise.resolve(0);
      return new Promise(function (resolve) {
        var settled = false;
        var finish = function (count, error) {
          if (settled) return;
          settled = true;
          if (error) cache.lastError = describe(error);
          resolve(count);
        };
        var request;
        try {
          request = indexedDB.open(name, 1);
        } catch (error) {
          finish(0, error);
          return;
        }
        request.onupgradeneeded = function () {
          var db = request.result;
          if (!db.objectStoreNames.contains(CACHE_STORE)) db.createObjectStore(CACHE_STORE, {keyPath: "key"});
        };
        request.onerror = function () { finish(0, request.error); };
        request.onblocked = function () { finish(0, "blocked"); };
        request.onsuccess = function () {
          var db = request.result;
          var transaction = db.transaction(CACHE_STORE, "readwrite");
          var store = transaction.objectStore(CACHE_STORE);
          var loaded = 0;
          var cursorRequest = store.openCursor();
          cursorRequest.onsuccess = function () {
            var cursor = cursorRequest.result;
            if (!cursor) return;
            var value = cursor.value;
            if (value && value.version === cache.version && value.spirv) {
              if (!cache.entries.has(value.key)) {
                cache.entries.set(value.key, {spirv: new Uint8Array(value.spirv), error: value.error || "",
                  warnings: value.warnings | 0, errors: value.errors | 0, used: value.used || 0});
                loaded++;
              }
            } else {
              cursor.delete();
            }
            cursor.continue();
          };
          transaction.oncomplete = function () {
            cache.db = db;
            cache.dbName = name;
            cache.loaded += loaded;
            finish(loaded, null);
          };
          transaction.onerror = function () { finish(0, transaction.error); };
        };
      });
    };

    // Writes pending cache entries now; resolves to their number (tests; the
    // page flushes 500 ms after the last new result).
    api.flushCache = function () {
      if (cache.flushTimer !== null) {
        clearTimeout(cache.flushTimer);
        cache.flushTimer = null;
      }
      return flushCache();
    };

    // ---- diagnostics -------------------------------------------------------------------
    api.stats = function () {
      return {
        version: API_VERSION,
        shaderc: shadercSlot.stats(),
        spvc: spvcSlot.stats(),
        openShadercJobs: jobs.size,
        openSpvcContexts: spvcOpenContexts,
        shadercCompiles: counters.shadercCompiles,
        shadercMs: Math.round(counters.shadercMs * 10) / 10,
        spvcCompiles: counters.spvcCompiles,
        spvcMs: Math.round(counters.spvcMs * 10) / 10,
        cache: {
          enabled: cache.enabled, version: cache.version, entries: cache.entries.size, hits: cache.hits,
          misses: cache.misses, database: cache.dbName, loaded: cache.loaded, stored: cache.stored,
          evicted: cache.evicted, lastError: cache.lastError
        }
      };
    };

    // Test hook: runs the idle check now, as if SPVC_RECYCLE_IDLE_MS had passed.
    api.debugRecycleIdle = function (minBytes) {
      var M = spvcSlot.M;
      if (M === null || spvcOpenContexts > 0 || spvcSlot.spare === null) return false;
      if (M.HEAPU8.length < (minBytes === undefined ? SPVC_RECYCLE_MIN_BYTES : minBytes)) return false;
      spvcSlot.recycle();
      return true;
    };

    // Resolves when both slots have a spare instance again (tests).
    api.settled = function () {
      return Promise.all([shadercSlot.prepareSpare(), spvcSlot.prepareSpare()]);
    };

    // SHA-256 of bytes as hex (diagnostics and tests; the cache keys use it too).
    api.sha256Hex = sha256Hex;

    api.lastFailure = function () {
      return spvcSlot.lastError || shadercSlot.lastError || null;
    };

    // Test hook: makes the named module trap inside a WebAssembly call and
    // retires it exactly like a real failure.  Returns the retirement text.
    api.debugTrap = function (which) {
      var slot = which === "spvc" ? spvcSlot : shadercSlot;
      try {
        var M = slot.live();
        if (M === null) return null;
        // A load far past the end of linear memory traps (memory access out of bounds).
        if (slot === spvcSlot) M._spvc_type_get_basetype(0xfffffff0);
        else M._shaderc_result_get_compilation_status(0xfffffff0);
      } catch (error) {
        return slot.retire(error);
      }
      return null;
    };

    return api;
  }

  // Instantiates both modules and resolves to the API.  options: {shadercFactory,
  // spvcFactory} are the emscripten factories (GaiusShadercModule and
  // GaiusSpvcModule); {shadercModule, spvcModule} their compiled WebAssembly.Module;
  // cacheVersion names the toolchain build in shaderc cache keys.
  function createToolchain(options) {
    var shadercSlot = new ModuleSlot("shaderc", options.shadercFactory, options.shadercModule);
    var spvcSlot = new ModuleSlot("spvc", options.spvcFactory, options.spvcModule);
    var api = createApi(shadercSlot, spvcSlot, options.cacheVersion);
    return Promise.all([shadercSlot.prepareSpare(), spvcSlot.prepareSpare()]).then(function () {
      if (shadercSlot.live() === null || spvcSlot.live() === null) {
        throw new Error(shadercSlot.lastError || spvcSlot.lastError || "the shader toolchain did not start");
      }
      return api;
    });
  }

  // ---- browser bootstrap ----------------------------------------------------------------
  var loaderScript = typeof document !== "undefined" ? document.currentScript : null;

  function loaderScriptUrl() {
    return loaderScript && loaderScript.src ? loaderScript.src
      : (typeof location !== "undefined" ? location.href : "");
  }

  // The launcher tags the loader with its profile (data-profile), which names
  // the profile's own IndexedDB cache database.
  function loaderProfile() {
    var profile = loaderScript && loaderScript.getAttribute ? loaderScript.getAttribute("data-profile") : null;
    return profile && /^[A-Za-z0-9._+-]+$/.test(profile) ? profile : null;
  }

  function siblingUrl(base, name) {
    var url = new URL(name, base);
    try {
      var token = new URL(base).searchParams.get("v");
      if (token) url.searchParams.set("v", token);
    } catch (ignored) {
      // A base without a query keeps the plain sibling URL.
    }
    return url.href;
  }

  function loadClassicScript(url, globalName) {
    return new Promise(function (resolve, reject) {
      if (typeof root[globalName] === "function") {
        resolve(root[globalName]);
        return;
      }
      var element = document.createElement("script");
      element.src = url;
      element.async = true;
      element.onload = function () {
        if (typeof root[globalName] === "function") resolve(root[globalName]);
        else reject(new Error(url + " did not define " + globalName));
      };
      element.onerror = function () { reject(new Error("could not load " + url)); };
      document.head.appendChild(element);
    });
  }

  function compileWasm(url) {
    return fetch(url, {cache: "force-cache"}).then(function (response) {
      if (!response.ok) throw new Error("could not load " + url + " (HTTP " + response.status + ")");
      if (typeof WebAssembly.compileStreaming === "function"
          && (response.headers.get("Content-Type") || "").indexOf("application/wasm") === 0) {
        return WebAssembly.compileStreaming(response);
      }
      return response.arrayBuffer().then(function (bytes) { return WebAssembly.compile(bytes); });
    });
  }

  function bootstrapBrowser(base, profile) {
    var started = performance.now();
    // The toolchain version in cache keys: the ?v= content token of the loader URL,
    // or window.__gaiusShaderToolchainUrls.version for a page that inlines the loader.
    var token = "dev";
    try {
      token = new URL(base).searchParams.get("v") || "dev";
    } catch (ignored) {
      // Keep the development token.
    }
    if (root.__gaiusShaderToolchainUrls && root.__gaiusShaderToolchainUrls.version) {
      token = String(root.__gaiusShaderToolchainUrls.version);
    }
    return Promise.resolve(root.__gaiusPortableAssetsReady).then(function () {
      var urls = root.__gaiusShaderToolchainUrls || {
        shadercJs: siblingUrl(base, "gaius-shaderc.js"),
        shadercWasm: siblingUrl(base, "gaius-shaderc.wasm"),
        spvcJs: siblingUrl(base, "gaius-spvc.js"),
        spvcWasm: siblingUrl(base, "gaius-spvc.wasm")
      };
      return Promise.all([
        loadClassicScript(urls.shadercJs, "GaiusShadercModule"),
        loadClassicScript(urls.spvcJs, "GaiusSpvcModule"),
        compileWasm(urls.shadercWasm),
        compileWasm(urls.spvcWasm)
      ]);
    }).then(function (parts) {
      return createToolchain({
        shadercFactory: parts[0], spvcFactory: parts[1], shadercModule: parts[2], spvcModule: parts[3],
        cacheVersion: token
      });
    }).then(function (api) {
      if (!profile) return api;
      // The persistent cache is an optimisation: never hold the boot for more than 3 s on it.
      return Promise.race([
        api.attachPersistentCache("gaius-shader-cache-v1-" + profile),
        new Promise(function (resolve) { setTimeout(resolve, 3000); })
      ]).then(function () { return api; });
    }).then(function (api) {
      api.loadMs = Math.round((performance.now() - started) * 10) / 10;
      root.__gaiusShaderToolchain = api;
      return api;
    }).catch(function (error) {
      root.__gaiusShaderToolchainError = describe(error);
      throw new Error("The Gaius shader compiler could not be loaded: " + describe(error));
    });
  }

  var exported = {createToolchain: createToolchain, apiVersion: API_VERSION, sha256Hex: sha256Hex};
  if (typeof module === "object" && module && module.exports) {
    module.exports = exported;
  }
  if (typeof window === "object" && window && window.document && !root.__gaiusShaderToolchainReady) {
    root.__gaiusShaderToolchainReady = bootstrapBrowser(loaderScriptUrl(), loaderProfile());
    // The launcher awaits the promise; keep an early failure from being reported twice.
    root.__gaiusShaderToolchainReady.catch(function () {});
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
