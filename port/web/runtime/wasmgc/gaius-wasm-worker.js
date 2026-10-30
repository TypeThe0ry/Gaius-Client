// Gaius WebAssembly GC integrated server host for the singleplayer Worker (v0.4.0 WP10).
//
// server-worker-bootstrap.js imports this file after gaius-wasm-gc-runtime.js (the patched
// TeaVM loader) and gaius-jspi.js when its start message selects serverRuntime "wasm". It
// exposes self.GaiusWasmWorker:
//
//   compile(source)   starts compiling singleplayer-server.wasm while the Worker opens its
//                     storage. source: a URL (streamed with compileStreaming), a Response,
//                     bytes (an ArrayBuffer or a typed array) or a WebAssembly.Module. A URL
//                     of a .gz file is inflated while it compiles; a gzip-only site's
//                     <name>.<hash>.wasm that the host does not have (no Service Worker
//                     served it) is taken from its .gz sibling <name>.wasm.<hash>.gz.
//   instantiate()     links the module with this Worker's JSPI scheduler (gaius-jspi.js: run
//                     queue, gaiusJspi imports, TeaVM EventQueue routing) and publishes the
//                     exported Java entry points on the Worker global (see below).
//   startMain(args)   runs BrowserIntegratedServerMain.main as the activation of the main
//                     thread; returns the promise that settles when main returns or throws.
//   info()            phase, timings, exported names, linear memory size, scheduler counters
//                     (the bootstrap's ping answer carries it).
//
// Export calling convention (BrowserIntegratedServerMain, decided in WP10): the server keeps
// @JSExport. TeaVM 0.15 WebAssembly GC exports every @JSExport static method of the main class
// as a WebAssembly.Global that holds a JS function, with the JS-visible signature of the
// JavaScript build (strings, booleans and numbers convert the same way). instantiate()
// publishes those functions on the Worker global under their Java names, so the bootstrap's
// typeof setIntegratedServerDistances === "function" checks and calls hold for both runtimes.
// They are synchronous entry points and must not block: a JSPI suspension cannot cross the JS
// frame of the caller. main is not published; startMain() replaces it (TeaVM's main export is
// a JS wrapper that cannot run as a promising activation). The JavaScript build's generated
// __gaiusStartIntegratedServerPump (postprocess-teavm-js.py) is provided here as an event on
// the run queue that calls pumpIntegratedServerNetworkInput.
//
// Bytes between the page and the server travel on the singleplayer MessagePort as
// ArrayBuffers, as with the JavaScript build; the Java side reads them through JsArrays.
(function (root) {
  "use strict";

  var state = {
    phase: "idle",
    timings: {},
    error: null,
    exports: [],
    mainSettled: false
  };
  var compiled = null;
  var teavm = null;
  var jspi = null;
  var scheduler = null;
  var terminated = false;

  function installGpuImports(imports) {
    if (root.GaiusWebGpu && typeof root.GaiusWebGpu.installImports === "function") {
      root.GaiusWebGpu.installImports(imports);
      return;
    }
    // The client module carries the GPU core even when WebGL2 is selected, so the imports must
    // link before the WebGPU glue is known. Bind forwarders, not no-ops: if the glue loads after
    // instantiation (the WebGPU path), the calls must still reach it instead of rendering nothing.
    // On the WebGL2 path the backend never calls these and the defaults are never returned.
    var gpu = {};
    var table = null;
    function forward(name, fallback) {
      gpu[name] = function () {
        if (table === null) {
          var glue = root.GaiusWebGpu;
          if (!glue || typeof glue.importTable !== "function") return fallback;
          table = glue.importTable();
        }
        var target = table[name];
        return typeof target === "function" ? target.apply(null, arguments) : fallback;
      };
    }
    [
      "writeBuffer", "upload", "copyBufferToBuffer", "bindBuffer", "bindTexture",
      "passColor", "passDepth", "passBegin", "passEnd", "passSuspend", "passResume",
      "setPipeline", "setVertexBuffer", "setIndexBuffer", "setBindGroup", "stageOffset",
      "setBindGroupStaged", "setScissor", "draw", "drawIndexed", "drawIndexedWithOffsets",
      "drawIndexedIndirect", "drawIndexedIndirectWithOffsets", "drawWithOffsets", "submit",
      "closeFence", "blitToSurface", "present"
    ].forEach(function (name) { forward(name, undefined); });
    ["writeOrdered", "createBindGroup", "createFence", "fenceStatus", "configureSurface", "acquireSurface"]
      .forEach(function (name) { forward(name, 0); });
    imports.gaiusGpu = gpu;
  }

  function now() {
    return typeof performance !== "undefined" && performance.now ? performance.now() : Date.now();
  }

  function phase(name) {
    state.phase = name;
    state.timings[name] = now();
  }

  function describe(error) {
    return String(error && (error.stack || error.message) || error);
  }

  function post(message) {
    try {
      root.postMessage(message);
    } catch (ignored) {
      // A closing Worker cannot report anymore.
    }
  }

  // The js-string builtins option must match the one TeaVM's loader compiles with.
  var COMPILE_OPTIONS = { builtins: ["js-string"] };

  function compileResponse(response) {
    var type = response.headers ? String(response.headers.get("Content-Type") || "") : "";
    if (typeof WebAssembly.compileStreaming === "function" && type.indexOf("application/wasm") === 0) {
      return WebAssembly.compileStreaming(response, COMPILE_OPTIONS);
    }
    return response.arrayBuffer().then(function (bytes) {
      return WebAssembly.compile(bytes, COMPILE_OPTIONS);
    });
  }

  // <name>.<hash>.wasm -> <name>.wasm.<hash>.gz (build-pages-site.py, gaius-sw.js gzipSibling).
  function gzipSibling(href) {
    var url = new URL(href, root.location && root.location.href);
    var match = /^(.*\/)?([^/]+)\.([0-9a-f]{16,64})\.wasm$/.exec(url.pathname);
    if (!match) {
      return null;
    }
    url.pathname = (match[1] || "") + match[2] + ".wasm." + match[3] + ".gz";
    url.search = "";
    url.hash = "";
    return url.href;
  }

  async function inflatedResponse(response) {
    const bytes = new Uint8Array(await response.arrayBuffer());
    const body = bytes.byteLength >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b
      ? new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"))
      : new Blob([bytes]).stream();
    return new Response(body, { headers: { "Content-Type": "application/wasm" } });
  }

  function fetchModule(source) {
    return fetch(source).then(function (response) {
      var gzip = /\.gz$/.test(new URL(response.url || source, root.location && root.location.href).pathname);
      if (response.status === 404 && !gzip) {
        var sibling = gzipSibling(source);
        if (sibling !== null) {
          return fetchModule(sibling);
        }
      }
      if (!response.ok) {
        throw new Error("HTTP " + response.status + " while loading " + source);
      }
      return gzip ? inflatedResponse(response) : response;
    });
  }

  function compileSource(source) {
    if (source instanceof WebAssembly.Module) {
      return Promise.resolve(source);
    }
    if (typeof Response !== "undefined" && source instanceof Response) {
      return compileResponse(source);
    }
    if (typeof source === "string") {
      return fetchModule(source).then(compileResponse);
    }
    if (source instanceof ArrayBuffer || ArrayBuffer.isView(source)) {
      return WebAssembly.compile(source, COMPILE_OPTIONS);
    }
    return Promise.reject(new Error("singleplayer-server.wasm source is missing"));
  }

  function compile(source) {
    if (compiled !== null) {
      return compiled;
    }
    if (typeof WebAssembly !== "object" || typeof WebAssembly.Suspending !== "function"
        || typeof WebAssembly.promising !== "function") {
      compiled = Promise.reject(new Error("This browser has no JSPI (WebAssembly.Suspending/promising)"));
    } else {
      phase("compileStart");
      compiled = compileSource(source).then(function (module) {
        phase("compiled");
        return module;
      });
    }
    compiled.catch(function (error) {
      state.error = describe(error);
    });
    return compiled;
  }

  function threadFailed(error) {
    root.__gaiusThreadError = error;
    state.error = describe(error);
    console.error("Gaius: a Java thread of the integrated server failed", error);
    post({ type: "thread-error", detail: state.error, at: Date.now() });
  }

  // The JavaScript build's pump shim, on the JSPI run queue: requests coalesce while one is
  // queued; pumpIntegratedServerNetworkInput only schedules work on the server thread.
  function installPump(exports) {
    var queued = false;
    var failed = false;
    root.__gaiusStartIntegratedServerPump = function () {
      var stats = root.__gaiusNetworkStats;
      if (stats) {
        stats.integratedServerPumpRequests = (Number(stats.integratedServerPumpRequests) || 0) + 1;
      }
      if (queued) {
        if (stats) {
          stats.integratedServerPumpCoalesced = (Number(stats.integratedServerPumpCoalesced) || 0) + 1;
        }
        return;
      }
      if (failed) {
        return;
      }
      queued = true;
      scheduler.post(function () {
        queued = false;
        if (failed) {
          return false;
        }
        if (stats) {
          stats.integratedServerPumpStarts = (Number(stats.integratedServerPumpStarts) || 0) + 1;
        }
        try {
          exports.pumpIntegratedServerNetworkInput();
        } catch (error) {
          failed = true;
          root.__gaiusIntegratedServerPumpError = describe(error);
          root.__gaiusIntegratedServerPumpFailed = true;
          if (stats) {
            stats.integratedServerPumpFailures = (Number(stats.integratedServerPumpFailures) || 0) + 1;
          }
          post({ type: "network-pump-error", detail: root.__gaiusIntegratedServerPumpError,
            at: Date.now() });
          if (typeof root.__gaiusIntegratedServerPumpFailure === "function") {
            try {
              root.__gaiusIntegratedServerPumpFailure(root.__gaiusIntegratedServerPumpError);
            } catch (failureError) {
              post({ type: "runtime-error", detail: describe(failureError), at: Date.now() });
            }
          }
        }
        return false;
      });
    };
  }

  function publishExports(instance, exports) {
    var names = [];
    Object.keys(instance.exports).forEach(function (name) {
      if (name === "main" || !/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name)) {
        return;
      }
      if (!(instance.exports[name] instanceof WebAssembly.Global)) {
        return;
      }
      var value = exports[name];
      if (typeof value !== "function") {
        return;
      }
      root[name] = value;
      names.push(name);
    });
    names.sort();
    state.exports = names;
    return names;
  }

  function instantiate() {
    if (terminated) {
      return Promise.reject(new Error("GaiusWasmWorker has been terminated"));
    }
    if (compiled === null) {
      return Promise.reject(new Error("GaiusWasmWorker.compile() was not called"));
    }
    return compiled.then(function (module) {
      if (!root.TeaVM || !root.TeaVM.wasmGC || !root.GaiusJspi) {
        throw new Error("the WebAssembly GC runtime scripts are not loaded in this Worker");
      }
      phase("instantiateStart");
      return root.TeaVM.wasmGC.load(module, {
        // The linear-memory views of dev.gaius.browser.JsArrays (self.__gaiusLinear, used for
        // instance by the light kernel's job upload) are refreshed when memory.grow detaches them.
        memory: {
          onResize: function () {
            if (root.__gaiusLinear) {
              root.__gaiusLinear.refresh();
            }
          }
        },
        installImports: function (imports) {
          state.memory = imports.env ? imports.env.memory : null;
          // JsArrays' (ptr,len) uploads read the module's memory through this global, as on the
          // page (gaius-wasm-boot.js).
          root.__gaiusWasmMemory = state.memory;
          scheduler = root.GaiusJspi.createScheduler();
          root.__gaiusJspiScheduler = scheduler;
          jspi = root.GaiusJspi.installJspi(imports, scheduler, function () {
            return teavm.instance.exports;
          }, threadFailed);
          installGpuImports(imports);
          root.GaiusJspi.routeEventQueue(imports, scheduler);
        }
      });
    }).then(function (loaded) {
      teavm = loaded;
      root.__gaiusWasm = loaded;
      publishExports(loaded.instance, loaded.exports);
      installPump(loaded.exports);
      phase("instantiated");
      return info();
    }, function (error) {
      state.error = describe(error);
      throw error;
    });
  }

  function startMain(args) {
    if (terminated) {
      return Promise.reject(new Error("GaiusWasmWorker has been terminated"));
    }
    if (jspi === null) {
      throw new Error("GaiusWasmWorker.instantiate() did not complete");
    }
    phase("mainStart");
    scheduler.beginSlice();
    var settled = jspi.startMain(args || []);
    // A promising call runs synchronously up to the first suspension, like TeaVM's JS main.
    phase("mainReturned");
    return settled.then(function () {
      state.mainSettled = true;
      state.timings.mainFinished = now();
    }, function (error) {
      state.mainSettled = true;
      state.timings.mainFinished = now();
      state.error = describe(error);
      throw error;
    });
  }

  function info() {
    var memory = state.memory;
    return {
      phase: state.phase,
      timings: Object.assign({}, state.timings),
      exports: state.exports.slice(),
      memoryBytes: memory && memory.buffer ? memory.buffer.byteLength : 0,
      mainSettled: state.mainSettled,
      error: state.error,
      scheduler: scheduler ? Object.assign({ pending: scheduler.pending() }, scheduler.stats) : null
    };
  }

  // A failed prewarm can leave JSPI promises and MessageChannel turns alive after the page asks
  // for a fresh runtime. Close the continuation bridge exactly once before its globals are
  // replaced, then drop references to the old instance and compiled module.
  function terminate() {
    if (terminated) return false;
    terminated = true;
    try { if (jspi && typeof jspi.close === "function") jspi.close(); } catch (_) {}
    try { if (scheduler && typeof scheduler.close === "function") scheduler.close(); } catch (_) {}
    if (root.__gaiusJspiScheduler === scheduler) root.__gaiusJspiScheduler = undefined;
    if (root.__gaiusWasm === teavm) root.__gaiusWasm = undefined;
    root.__gaiusWasmMemory = undefined;
    root.__gaiusWasmSource = undefined;
    root.__gaiusWasmBytes = undefined;
    jspi = null;
    scheduler = null;
    teavm = null;
    compiled = null;
    state.memory = null;
    return true;
  }

  root.GaiusWasmWorker = {
    compile: compile,
    instantiate: instantiate,
    startMain: startMain,
    info: info,
    terminate: terminate,
  };
})(typeof self !== "undefined" ? self : globalThis);
