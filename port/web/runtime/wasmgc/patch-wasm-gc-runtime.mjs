#!/usr/bin/env node
// Patches TeaVM 0.15's WebAssembly GC loader (org/teavm/backend/wasm/wasm-gc-runtime.js) for
// Gaius (v0.4.0 design D5 and D13):
//
// 1. Scheduling.  TeaVM runs every thread start, yield, wake-up and EventQueue event through
//    teavmAsync.offer, which is a setTimeout per event.  Nested timers are clamped to 4 ms
//    (HTML timers, nesting level > 5), so a chain of zero-delay hops costs 4 ms each.  The
//    patched offer puts zero-delay events on a ready queue that one MessageChannel message
//    drains, several events per macrotask until a 4 ms slice is used; only delayed events use
//    setTimeout.  Queue ids are negative so kill() can tell them from timer ids.
//    globalThis.__gaiusWasmScheduler exposes counters.
// 2. Null weak references.  new WeakReference<>(null) (vanilla RecipeCache, for one) reaches
//    teavm.createWeakRef with a null referent, and new WeakRef(null) throws "WeakRef: invalid
//    target".  The patched import returns a shared sentinel whose deref() is undefined, which
//    deref turns into null as for a collected referent; null referents are not registered with
//    the reference queue (nothing can be collected).
// 3. Bytes-capable loading.  options.emscriptenModules entries may be
//    {wasmModule | wasmBytes, jsLoader} instead of {pathToJs, pathToWasm}, for file:// pages
//    that carry the module bytes inline.
// 4. Streamed sources.  load(src) also accepts a Response, compiled with compileStreaming as it
//    arrives: the dist opened from disk pipes its gzip+base64 payload through
//    DecompressionStream and a site page starts the module download before the loader script
//    runs.  No fetch is involved in the first (design D13); the portable page passes the
//    inflated bytes (load(bytes)).
// 5. Precompiled modules.  load(src) also accepts a WebAssembly.Module (compiled with the same
//    js-string builtins option): the singleplayer Worker compiles singleplayer-server.wasm
//    while its storage opens and instantiates it afterwards (gaius-wasm-worker.js).
// 6. Epoch clock.  System.currentTimeMillis uses Date.now() to read the same epoch milliseconds
//    without allocating a Date for every clock read.
//
// Every patch locates its anchor exactly and fails when the TeaVM text differs.
//
// usage: node patch-wasm-gc-runtime.mjs <wasm-gc-runtime.js> <output.js>
import { readFileSync, writeFileSync } from "node:fs";

const [input, output] = process.argv.slice(2);
if (!input || !output) {
  console.error("usage: patch-wasm-gc-runtime.mjs <wasm-gc-runtime.js> <output.js>");
  process.exit(2);
}

let text = readFileSync(input, "utf8").replace(/\r\n/g, "\n");

function replaceOnce(anchor, replacement, label) {
  const first = text.indexOf(anchor);
  if (first < 0 || text.indexOf(anchor, first + 1) >= 0) {
    throw new Error("wasm-gc-runtime.js: " + label + " anchor " + (first < 0 ? "not found" : "found twice"));
  }
  text = text.slice(0, first) + replacement + text.slice(first + anchor.length);
}

replaceOnce('      currentTimeMillis: () => (/* @__PURE__ */ new Date()).getTime(),',
  '      currentTimeMillis: () => Date.now(),', 'teavmDate.currentTimeMillis');

replaceOnce(`  function asyncImports(imports) {
    imports.teavmAsync = {
      offer(instance, fn, time) {
        const dt = Math.max(0, time - Date.now());
        return setTimeout(() => {
          fn(instance);
        }, dt);
      },
      kill(id) {
        clearTimeout(id);
      }
    };
  }`, `  function asyncImports(imports) {
    // Gaius: zero-delay events run from a MessageChannel ready queue (see the patch header).
    const sliceMillis = 4;
    const stats = {
      queued: 0,
      timers: 0,
      ran: 0,
      killed: 0,
      turns: 0,
      maxBatch: 0,
      fallback: false
    };
    globalThis.__gaiusWasmScheduler = stats;
    const ready = [];
    let head = 0;
    let nextId = 0;
    const cancelled = new Set();
    let channel = null;
    let posted = false;
    let closed = false;
    const timers = new Set();
    try {
      channel = new MessageChannel();
      channel.port1.onmessage = drain;
    } catch (e) {
      channel = null;
      stats.fallback = true;
    }
    function post() {
      if (closed || posted) {
        return;
      }
      posted = true;
      if (channel !== null) {
        channel.port2.postMessage(0);
      } else {
        let timer = 0;
        timer = setTimeout(() => {
          timers.delete(timer);
          if (!closed) drain();
        }, 0);
        timers.add(timer);
      }
    }
    function drain() {
      if (closed) {
        return;
      }
      posted = false;
      stats.turns++;
      const start = performance.now();
      let batch = 0;
      while (head < ready.length) {
        const event = ready[head];
        ready[head] = undefined;
        head++;
        if (cancelled.size > 0 && cancelled.delete(event.id)) {
          continue;
        }
        batch++;
        stats.ran++;
        try {
          event.fn(event.instance);
        } catch (e) {
          if (head < ready.length) {
            post();
          }
          throw e;
        }
        if (head < ready.length && performance.now() - start >= sliceMillis) {
          post();
          break;
        }
      }
      if (head >= ready.length) {
        ready.length = 0;
        head = 0;
      } else if (head > 1024) {
        ready.splice(0, head);
        head = 0;
      }
      if (batch > stats.maxBatch) {
        stats.maxBatch = batch;
      }
    }
    const api = {
      offer(instance, fn, time) {
        if (closed) {
          return 0;
        }
        const dt = Math.max(0, time - Date.now());
        if (dt > 0) {
          stats.timers++;
          let timer = 0;
          timer = setTimeout(() => {
            timers.delete(timer);
            if (!closed) fn(instance);
          }, dt);
          timers.add(timer);
          return timer;
        }
        stats.queued++;
        nextId = nextId <= -0x3fffffff ? -1 : nextId - 1;
        ready.push({ id: nextId, instance, fn });
        post();
        return nextId;
      },
      kill(id) {
        if (id < 0) {
          stats.killed++;
          cancelled.add(id);
        } else {
          clearTimeout(id);
          timers.delete(id);
        }
      },
      close() {
        if (closed) {
          return false;
        }
        closed = true;
        stats.closed = true;
        for (const timer of timers) clearTimeout(timer);
        timers.clear();
        ready.length = 0;
        head = 0;
        cancelled.clear();
        posted = false;
        try { if (channel) channel.port1.onmessage = null; } catch (_) {}
        try { if (channel) channel.port1.close(); } catch (_) {}
        try { if (channel) channel.port2.close(); } catch (_) {}
        return true;
      }
    };
    imports.teavmAsync = api;
    globalThis.__gaiusWasmAsyncRuntime = api;
  }`, "teavmAsync");

replaceOnce(`    return Promise.all(keysValues.map(async ([key, { pathToJs, pathToWasm }]) => {
      const [response, close] = await openPath(pathToWasm, isNodeJs);`, `    return Promise.all(keysValues.map(async ([key, spec]) => {
      if (typeof spec.jsLoader === "function") {
        // Gaius: bytes-capable entry for file:// pages.
        const wasmModule = spec.wasmModule || await WebAssembly.compile(spec.wasmBytes);
        return { name: key, jsLoader: spec.jsLoader, wasmModule };
      }
      const { pathToJs, pathToWasm } = spec;
      const [response, close] = await openPath(pathToWasm, isNodeJs);`, "loadEmscriptenModules");

replaceOnce(`  async function compileModule(src, isNodeJs) {
    if (typeof src !== "string") {
      return await WebAssembly.compile(src, { builtins: ["js-string"] });
    }`, `  async function compileModule(src, isNodeJs) {
    if (src instanceof WebAssembly.Module) {
      // Gaius: a precompiled module (see the patch header).
      return src;
    }
    if (typeof Response !== "undefined" && src instanceof Response) {
      // Gaius: a streamed source (see the patch header).
      return await WebAssembly.compileStreaming(src, { builtins: ["js-string"] });
    }
    if (typeof src !== "string") {
      return await WebAssembly.compile(src, { builtins: ["js-string"] });
    }`, "compileModule");

replaceOnce(`      createWeakRef(value, ref, queue) {
        if (queue !== null) {
          finalizationRegistry.register(value, { ref, queue });
        }
        return new WeakRef(value);
      },`, `      createWeakRef(value, ref, queue) {
        if (value === null || value === void 0) {
          // Gaius: a Java WeakReference to null (see the patch header).
          return nullWeakRef;
        }
        if (queue !== null) {
          finalizationRegistry.register(value, { ref, queue });
        }
        return new WeakRef(value);
      },`, "createWeakRef");
replaceOnce(`    imports.teavm = {
      createWeakRef(value, ref, queue) {`, `    const nullWeakRef = Object.freeze({ deref() { return void 0; } });
    imports.teavm = {
      createWeakRef(value, ref, queue) {`, "nullWeakRef");

text = "// TeaVM 0.15 WebAssembly GC runtime, patched by Gaius"
  + " (port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs).\n" + text;
writeFileSync(output, text);
console.log("patched " + input + " -> " + output);
