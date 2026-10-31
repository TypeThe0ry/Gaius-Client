(function (global) {
  "use strict";
  var instance = null;
  // The decoder's linear memory grows to fit the largest image it decoded (about 20 MB after
  // the resource load) and never shrinks.  Between resource loads the instance is dropped after
  // IDLE_RELEASE_MS without a decode; the next decode instantiates the compiled module again
  // (a 55 KB module: well under a millisecond, synchronously).
  var module = null;
  var IDLE_RELEASE_MS = 10000;
  var IDLE_RELEASE_MIN_BYTES = 4 * 1024 * 1024;
  var lastDecode = 0;
  var idleTimer = null;
  var initializing = null;
  var sequence = 0;
  var limit = 64 * 1024 * 1024;
  var metrics = { attempts: 0, successes: 0, declines: 0, failures: 0, bytes: 0, totalMs: 0, releases: 0 };
  var kernel = {
    enabled: false,
    ready: false,
    metrics: metrics,
    initialize: initialize,
    decode: decode,
    setEnabled: function (enabled) { kernel.enabled = enabled === true; }
  };

  function isModule(value) {
    return typeof WebAssembly.Module === "function" && value instanceof WebAssembly.Module;
  }

  function initialize(options) {
    options = options || {};
    if (!kernel.enabled) return Promise.resolve(false);
    if (kernel.ready) return Promise.resolve(true);
    if (initializing) return initializing;
    var controller = new AbortController();
    var timeout;
    var timeoutMs = Number.isFinite(options.timeoutMs) && options.timeoutMs > 0 ? options.timeoutMs : 5000;
    var deadline = new Promise(function (resolve, reject) {
      timeout = setTimeout(function () {
        controller.abort();
        reject(new Error("PNG kernel initialization timed out after " + timeoutMs + " ms"));
      }, timeoutMs);
    });
    var loading = Promise.resolve().then(function () {
      if (options.module) return options.module;
      if (options.bytes) return options.bytes;
      return fetch(options.url || "gaius-png.wasm", {signal: controller.signal}).then(function (response) {
        if (!response.ok) throw new Error("PNG kernel HTTP " + response.status);
        return response.arrayBuffer();
      });
    }).then(function (source) {
      if (controller.signal.aborted) throw new Error("PNG kernel initialization aborted");
      return WebAssembly.instantiate(source, {}).then(function (loaded) {
        return loaded.instance ? loaded : {instance: loaded, module: isModule(source) ? source : null};
      });
    }).then(function (loaded) {
      if (controller.signal.aborted) throw new Error("PNG kernel initialization aborted");
      var candidate = loaded.instance || loaded;
      var compiled = loaded.module || null;
      var exports = candidate.exports;
      if (!(exports.memory instanceof WebAssembly.Memory) || exports.gaius_abi_version() !== 1
          || typeof exports.alloc !== "function" || typeof exports.dealloc !== "function"
          || typeof exports.release !== "function" || typeof exports.run_png_decode !== "function") {
        throw new Error("PNG kernel ABI mismatch");
      }
      instance = candidate;
      module = compiled;
      kernel.ready = true;
      return true;
    });
    initializing = Promise.race([loading, deadline]).catch(function (error) {
      kernel.ready = false;
      kernel.lastError = String(error);
      metrics.failures++;
      return false;
    }).finally(function () {
      clearTimeout(timeout);
      initializing = null;
    });
    return initializing;
  }

  function releaseWhenIdle() {
    idleTimer = null;
    if (instance === null || module === null) return;
    if (performance.now() - lastDecode < IDLE_RELEASE_MS) {
      idleTimer = setTimeout(releaseWhenIdle, IDLE_RELEASE_MS);
      return;
    }
    if (instance.exports.memory.buffer.byteLength < IDLE_RELEASE_MIN_BYTES) return;
    instance = null;
    metrics.releases++;
  }

  function decode(encoded, desiredChannels) {
    if (!kernel.enabled || !kernel.ready) return null;
    if (instance === null) {
      try {
        instance = new WebAssembly.Instance(module, {});
      } catch (error) {
        kernel.lastError = String(error);
        kernel.ready = false;
        metrics.failures++;
        return null;
      }
    }
    lastDecode = performance.now();
    if (idleTimer === null && module !== null && typeof setTimeout === "function") {
      idleTimer = setTimeout(releaseWhenIdle, IDLE_RELEASE_MS);
    }
    metrics.attempts++;
    if (!(encoded instanceof Uint8Array) || encoded.length < 33 || encoded.length > limit
        || !Number.isInteger(desiredChannels) || desiredChannels < 0 || desiredChannels > 4) {
      metrics.declines++;
      return null;
    }
    var header = new DataView(encoded.buffer, encoded.byteOffset, encoded.byteLength);
    var width = header.getUint32(16);
    var height = header.getUint32(20);
    if (header.getUint32(0) !== 0x89504e47 || header.getUint32(4) !== 0x0d0a1a0a
        || header.getUint32(8) !== 13 || header.getUint32(12) !== 0x49484452
        || width === 0 || height === 0 || width > 16384 || height > 16384
        || width * height * 4 > limit || encoded[28] !== 0) {
      metrics.declines++;
      return null;
    }
    var started = performance.now();
    var exports = instance.exports;
    var pointer = 0;
    var descriptor = 0;
    var result = null;
    var length = encoded.length + 20;
    try {
      pointer = exports.alloc(length) >>> 0;
      if (!pointer) throw new Error("PNG job allocation failed");
      var memory = new DataView(exports.memory.buffer);
      var jobId = sequence = (sequence + 1) >>> 0;
      memory.setUint32(pointer, 0x424a4b47, true);
      memory.setUint16(pointer + 4, 1, true);
      memory.setUint16(pointer + 6, 0x0701, true);
      memory.setUint32(pointer + 8, jobId, true);
      memory.setUint32(pointer + 12, encoded.length + 4, true);
      memory.setUint32(pointer + 16, desiredChannels, true);
      new Uint8Array(exports.memory.buffer, pointer + 20, encoded.length).set(encoded);
      descriptor = exports.run_png_decode(pointer, length) >>> 0;
      if (!descriptor) throw new Error("PNG result descriptor missing");
      memory = new DataView(exports.memory.buffer);
      var status = memory.getUint32(descriptor, true);
      if (status !== 0) {
        metrics.declines++;
        return null;
      }
      var resultPointer = memory.getUint32(descriptor + 4, true);
      var resultLength = memory.getUint32(descriptor + 8, true);
      if (resultLength < 32 || resultLength > limit + 32
          || memory.getUint32(resultPointer, true) !== 0x53524b47
          || memory.getUint16(resultPointer + 4, true) !== 1
          || memory.getUint16(resultPointer + 6, true) !== 0
          || memory.getUint32(resultPointer + 8, true) !== jobId
          || memory.getUint32(resultPointer + 12, true) !== resultLength - 16) {
        throw new Error("PNG result framing mismatch");
      }
      var payload = resultPointer + 16;
      var sourceChannels = memory.getUint32(payload + 8, true);
      var outputChannels = memory.getUint32(payload + 12, true);
      if (memory.getUint32(payload, true) !== width || memory.getUint32(payload + 4, true) !== height
          || sourceChannels < 1 || sourceChannels > 4
          || outputChannels !== (desiredChannels === 0 ? sourceChannels : desiredChannels)
          || width * height * outputChannels !== resultLength - 32) {
        throw new Error("PNG result pixel layout mismatch");
      }
      result = new Uint8Array(exports.memory.buffer, payload, resultLength - 16).slice();
    } catch (error) {
      metrics.failures++;
      kernel.lastError = String(error);
      kernel.ready = false;
      return null;
    } finally {
      try {
        if (descriptor) exports.release(descriptor);
      } catch (error) {
        kernel.lastError = String(error);
        kernel.ready = false;
        metrics.failures++;
      }
      try {
        if (pointer) exports.dealloc(pointer, length);
      } catch (error) {
        kernel.lastError = String(error);
        kernel.ready = false;
        metrics.failures++;
      }
      metrics.totalMs += performance.now() - started;
    }
    if (!kernel.ready) return null;
    metrics.successes++;
    metrics.bytes += encoded.length;
    return result;
  }

  global.GaiusPngKernel = kernel;
})(globalThis);
