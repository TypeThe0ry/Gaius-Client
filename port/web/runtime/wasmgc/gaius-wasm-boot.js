// Gaius WebAssembly GC client loader (v0.4.0).
//
// The launcher reaches this file only after its WebAssembly GC, exception-handling and JSPI
// capability gate. It loads the patched TeaVM runtime and gaius-jspi.js (unless the page already
// carries them), compiles and instantiates classes.wasm while the page prepares storage and
// assets, and defines the global main(args) that the launcher calls.
//
// Module source, first match wins:
//   window.__gaiusWasmSource   a value or a promise of one: a Response (compiled while it streams),
//                              bytes (an ArrayBuffer or a typed array: TeaVM load(bytes)) or a URL
//                              string. gaius-boot.js sets it for every distribution form: a fetch
//                              started at parse time (site, dist over http), the gzip+base64 file
//                              payload inflated with DecompressionStream (dist opened from disk),
//                              the module embedded in the portable page. The last two never fetch.
//   window.__gaiusWasmBytes, window.__gaiusWasmGzipBase64   (the M0 S1 page forms)
//   window.__gaiusWasmUrl || "classes.wasm"                streamed from that URL
//
// Telemetry: phase times (performance.now() milliseconds) go to window.__gaiusWasmTimings and,
// as wasm<Phase> keys, into the launcher's window.__gaiusBootTimings.
//
// Failures: a module that does not load or instantiate is window.__gaiusWasmError and is reported
// through the launcher as a terminal startup error.
//
// JSPI mode (gaius.teavm.jspi, design D3): gaius-jspi.js (window.__gaiusJspiUrl) provides the
// run queue and the gaiusJspi imports; every Java thread is a WebAssembly.promising activation
// and main(args) starts the main thread as one instead of calling TeaVM's main export. Every
// wasmgc build is a JSPI build: the coroutine (Fiber) mode was removed from the build.
(function () {
  "use strict";
  var g = window;
  var timings = g.__gaiusWasmTimings = { shimStart: performance.now() };

  function noteMemory() {
    if (!g.__gaiusWasmMemory) return;
    var root = g.__gaiusMemoryTelemetry || (g.__gaiusMemoryTelemetry = {});
    var bytes = g.__gaiusWasmMemory.buffer.byteLength;
    var memory = root.linearMemory;
    if (!memory) memory = root.linearMemory = {
      initialBytes: bytes, currentBytes: bytes, peakBytes: bytes, growthCount: 0, growths: []
    };
    if (bytes !== memory.currentBytes) {
      memory.growthCount++;
      if (memory.growths.length < 32) memory.growths.push({atMs: performance.now(),
        fromBytes: memory.currentBytes, toBytes: bytes});
      memory.currentBytes = bytes;
      memory.peakBytes = Math.max(memory.peakBytes, bytes);
    }
  }

  function phase(name) {
    var at = performance.now();
    timings[name] = at;
    var boot = g.__gaiusBootTimings;
    if (boot && typeof boot === "object") {
      boot["wasm" + name.charAt(0).toUpperCase() + name.slice(1)] = at;
    }
    return at;
  }

  function loadClassicScript(src) {
    return new Promise(function (resolve, reject) {
      var script = document.createElement("script");
      script.src = src;
      script.onload = function () { resolve(); };
      script.onerror = function () { reject(new Error("Failed to load " + src)); };
      document.head.appendChild(script);
    });
  }

  function base64ToBytes(text) {
    if (typeof Uint8Array.fromBase64 === "function") {
      return Uint8Array.fromBase64(text);
    }
    var binary = atob(text);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  }

  function gunzip(bytes) {
    var stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
    return new Response(stream).arrayBuffer();
  }

  function moduleSource() {
    if (g.__gaiusWasmSource !== undefined && g.__gaiusWasmSource !== null) {
      return Promise.resolve(g.__gaiusWasmSource).then(function (source) {
        // The loader holds the only reference from here on (the bytes can be large).
        g.__gaiusWasmSource = null;
        // Portable pages expose the compressed client payload for bootstrap and server handoff.
        // Once this source has been captured, the loader/Response owns the executable bytes;
        // release the second reference so compressed and executable copies are not both retained.
        try {
          if (g.__gaiusPortablePayloadBytes) delete g.__gaiusPortablePayloadBytes["classes.wasm"];
        } catch (_) {
          // A host may expose the payload table as read-only.
        }
        phase("sourceReady");
        return source;
      });
    }
    if (g.__gaiusWasmBytes) {
      return Promise.resolve(g.__gaiusWasmBytes);
    }
    if (typeof g.__gaiusWasmGzipBase64 === "string") {
      timings.decodeStart = performance.now();
      return gunzip(base64ToBytes(g.__gaiusWasmGzipBase64)).then(function (buffer) {
        phase("decoded");
        g.__gaiusWasmGzipBase64 = null;
        return buffer;
      });
    }
    return Promise.resolve(g.__gaiusWasmUrl || "classes.wasm");
  }

  var jspiMissing = !(typeof WebAssembly.Suspending === "function"
      && typeof WebAssembly.promising === "function");
  var jspi = null;
  var ready = null;
  var terminated = g.__gaiusWasmTerminated === true;
  function installGpuImports(imports) {
    if (g.GaiusWebGpu && typeof g.GaiusWebGpu.installImports === "function") {
      g.GaiusWebGpu.installImports(imports);
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
          var glue = g.GaiusWebGpu;
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
  // Site pages start these scripts during parsing in gaius-boot.js. Portable pages (no preload)
  // and failed preloads keep the original shim-owned fallback, while a successful preload is
  // awaited once. The fallback is keyed on the globals, not on the preload result, so a page
  // without a preload can never reach TeaVM.wasmGC.load before the runtime script ran.
  var supportReady = g.__gaiusWasmSupportReady || Promise.resolve(false);
  function fallbackSupportScripts() {
    var fallback = [];
    if (!(g.TeaVM && g.TeaVM.wasmGC)) {
      fallback.push(loadClassicScript(g.__gaiusWasmRuntimeUrl || "gaius-wasm-gc-runtime.js"));
    }
    if (!g.GaiusJspi) fallback.push(loadClassicScript(g.__gaiusJspiUrl || "gaius-jspi.js"));
    return Promise.all(fallback);
  }
  // Sites that ship gaius-png.wasm switch it on (build-pages-site.py); ?gaiusPng=0 keeps the Java
  // decoder for an A/B run, ?gaiusPng=1 asks for the module elsewhere.
  var pngSwitch = new URLSearchParams(g.location ? g.location.search : "").get("gaiusPng");
  var pngEnabled = pngSwitch !== "0" && (g.__gaiusPngKernelEnabled === true || pngSwitch === "1");
  if (pngEnabled) {
    g.__gaiusPngReady = (g.GaiusPngKernel ? Promise.resolve()
      : loadClassicScript(g.__gaiusPngScriptUrl || "png-kernel.js"))
      .then(function () {
        g.GaiusPngKernel.setEnabled(true);
        return g.GaiusPngKernel.initialize({
          bytes: g.__gaiusPngWasmBytes,
          module: g.__gaiusPngWasmModule,
          url: g.__gaiusPngWasmUrl || "gaius-png.wasm"
        });
      }).then(function (loaded) {
        if (!loaded) g.__gaiusPngError = g.GaiusPngKernel.lastError;
        return loaded;
      }).catch(function (error) {
        g.__gaiusPngError = String(error);
        return false;
      });
  }
  g.__gaiusWasmReady = (jspiMissing
    // The capability gate in gaius-boot.js handles unsupported browsers before this loader runs.
    ? Promise.reject(new Error("This browser has no JSPI (WebAssembly.Suspending/promising)"))
    : Promise.all([supportReady.then(function () {
      return fallbackSupportScripts();
    }), moduleSource()]))
    .then(function (loaded) {
      phase("runtimeLoaded");
      var source = loaded[1];
      return g.TeaVM.wasmGC.load(source, {
        // memory.grow detaches the cached linear-memory views of dev.gaius.browser.JsArrays
        // (window.__gaiusLinear); refresh them at once instead of at their next use.
        memory: {
          onResize: function () {
            if (g.__gaiusLinear) {
              g.__gaiusLinear.refresh();
            }
            noteMemory();
          }
        },
        installImports: function (imports) {
          // Called after compilation and import linking, right before instantiation.
          if (terminated) return;
          phase("compiled");
          g.__gaiusWasmMemory = imports.env ? imports.env.memory : null;
          noteMemory();
          var scheduler = g.GaiusJspi.createScheduler();
          g.__gaiusJspiScheduler = scheduler;
          jspi = g.GaiusJspi.installJspi(imports, scheduler,
            function () {
              var wasm = g.__gaiusWasm;
              return wasm && wasm.instance ? wasm.instance.exports : null;
            },
            function (e) {
              if (terminated) return;
              g.__gaiusThreadError = e;
              console.error("Gaius: a Java thread failed", e);
            });
          installGpuImports(imports);
          g.GaiusJspi.routeEventQueue(imports, scheduler);
        }
      });
    })
    .then(function (teavm) {
      if (terminated) return null;
      phase("instantiated");
      g.__gaiusWasm = teavm;
      ready = teavm;
      return teavm;
    });
  // gaius-boot.js beforeMain() awaits this before the launcher calls main(args), so main starts
  // synchronously and mainStart/mainReturned mean the same in both runtimes.
  g.__gaiusClientReady = g.__gaiusWasmReady;
  g.__gaiusWasmReady.catch(function (error) {
    g.__gaiusWasmError = error;
    if (!(g.__gaiusBoot && g.__gaiusBoot.handlesWasmFailure === true)) {
      g.__gaiusBootError = error;
    }
    console.error("Gaius: the WebAssembly GC client did not load", error);
  });

  // Reloads normally tear down the document before the browser can collect the Wasm instance.
  // Release the JSPI scheduler and its queued activation closures explicitly so a pagehide does
  // not retain the old linear memory through MessagePort/timer callbacks.
  g.__gaiusTerminateWasm = function () {
    if (terminated) return false;
    terminated = true;
    g.__gaiusWasmTerminated = true;
    try {
      if (g.__gaiusWasmEventQueue && typeof g.__gaiusWasmEventQueue.close === "function") {
        g.__gaiusWasmEventQueue.close();
      } else if (g.__gaiusWasmAsyncRuntime && typeof g.__gaiusWasmAsyncRuntime.close === "function") {
        g.__gaiusWasmAsyncRuntime.close();
      }
    } catch (_) {}
    g.__gaiusWasmEventQueue = null;
    g.__gaiusWasmAsyncRuntime = null;
    try { if (jspi && typeof jspi.close === "function") jspi.close(); } catch (_) {}
    try { if (g.__gaiusJspiScheduler && typeof g.__gaiusJspiScheduler.close === "function") g.__gaiusJspiScheduler.close(); } catch (_) {}
    g.__gaiusJspiScheduler = null;
    g.__gaiusWasmMemory = null;
    g.__gaiusWasmSource = null;
    g.__gaiusWasmBytes = null;
    g.__gaiusWasm = null;
    g.__gaiusWasmReady = null;
    g.__gaiusWasmError = null;
    g.__gaiusClientReady = null;
    ready = null;
    jspi = null;
    return true;
  };

  function runMain(args) {
    phase("mainStart");
    // The main activation runs until its first suspension, then from the run queue:
    // mainReturned marks that first suspension, as the main export's return does in the
    // JavaScript build.
    var mainSettled = jspi.startMain(args || []);
    phase("mainReturned");
    mainSettled.then(function () {
      phase("mainFinished");
    }, function (error) {
      phase("mainFinished");
      // Teardown rejects suspended JSPI work so the old document can release its
      // queues and Wasm memory. That rejection is an expected lifecycle event.
      if (terminated) return;
      g.__gaiusBootError = error;
      console.error("Gaius: the WebAssembly GC main thread failed", error);
    });
  }

  g.main = function (args) {
    if (terminated) return;
    if (ready !== null) {
      runMain(args);
      return;
    }
    g.__gaiusWasmReady.then(function () {
      if (terminated || !jspi) return;
      runMain(args);
    }).catch(function (error) {
      g.__gaiusBootError = error;
      console.error("Gaius: the WebAssembly GC client failed to start", error);
    });
  };
})();
