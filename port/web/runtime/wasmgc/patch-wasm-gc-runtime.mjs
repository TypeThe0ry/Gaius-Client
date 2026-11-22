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
// 7. Lazy @JSBody functions.  TeaVM's start function creates every @JSBody function up front
//    (teavmJso.createFunctionN: one `new Function` per JSBody method, ~640 in the client, ~30 ms
//    of the start function in a Linux measurement).  createFunctionN now returns a function of
//    the same arity that compiles the body on its first call and then calls it with the same
//    receiver (the global object, as the bound function had) and the same leading
//    wrapCallFromJavaToJs argument; the returned function is the one per call site that TeaVM
//    stores, as before.  Differences: a body is parsed when it first runs (a syntax error, which
//    the eager form reported while instantiating, is reported at that call; the loaded module's
//    bodies are checked by load(..).jsBodies.materialize() in the tests) and JS stack traces show
//    one more frame.  options.lazyJsBodies === false keeps the eager form.
// 8. Deferred string weak references.  StringInternPool interns every string literal in the
//    start function, and each one gets a WeakRef and a FinalizationRegistry registration (~65k,
//    20-25 ms in a Linux measurement, mostly WeakRef creation).  With
//    options.deferStartStringRegistrations === true, createStringWeakRef returns, while the start
//    function runs, a DeferredStringRef that holds the string strongly and answers deref() like a
//    WeakRef; after instantiation, in idle slices (requestIdleCallback, 60 s timeout; setTimeout
//    where there is none) and in creation order, each one gets its WeakRef and its referent its
//    registration.  Until then a referent stays reachable a little longer, which neither WeakRef
//    nor FinalizationRegistry semantics can tell apart (no timing guarantee); the string
//    literals these are are never collected anyway.
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

// 7. Lazy @JSBody functions (see the header).
replaceOnce(`      imports.teavmJso["createFunction" + i] = new Function(
        "wrapCallFromJavaToJs",
        ...argumentList,
        "body",
        \`return new Function('wrapCallFromJavaToJs', \${argsAndBody}).bind(this, wrapCallFromJavaToJs);\`
      ).bind(null, wrapCallFromJavaToJs);`, `      // Gaius: lazy @JSBody functions (see the patch header).
      imports.teavmJso["createFunction" + i] = context.lazyJsBodies ? new Function(
        "wrapCallFromJavaToJs",
        "lazyJsBodies",
        ...argumentList,
        "body",
        \`const self = this;
let fn = null;
const make = () => fn = new Function('wrapCallFromJavaToJs', \${argsAndBody});
lazyJsBodies.push(() => fn !== null ? fn : make());
return function (\${args}) {
  return (fn !== null ? fn : make()).call(self, wrapCallFromJavaToJs\${args ? ", " + args : ""});
};\`
      ).bind(null, wrapCallFromJavaToJs, context.jsBodies) : new Function(
        "wrapCallFromJavaToJs",
        ...argumentList,
        "body",
        \`return new Function('wrapCallFromJavaToJs', \${argsAndBody}).bind(this, wrapCallFromJavaToJs);\`
      ).bind(null, wrapCallFromJavaToJs);`, "lazy createFunction");
replaceOnce(`  function defaults(imports, userExports, stringBuiltins) {
    const context = {
      exports: null,
      userExports,
      stackDeobfuscator: null
    };`, `  function defaults(imports, userExports, stringBuiltins, gaiusOptions) {
    const context = {
      exports: null,
      userExports,
      stackDeobfuscator: null,
      // Gaius: lazy @JSBody functions and deferred string registrations (see the patch header).
      lazyJsBodies: !(gaiusOptions && gaiusOptions.lazyJsBodies === false),
      jsBodies: [],
      deferredStrings: null,
      registerDeferredStrings: null
    };`, "defaults options");
replaceOnce(`      supplyStackDeobfuscator(deobfuscator) {
        context.stackDeobfuscator = deobfuscator;
      }
    };
  }`, `      supplyStackDeobfuscator(deobfuscator) {
        context.stackDeobfuscator = deobfuscator;
      },
      // Gaius: the start-function window of deferred string registrations.
      beginStart(deferStrings) {
        if (deferStrings) context.deferredStrings = [];
      },
      endStart() {
        const pending = context.deferredStrings;
        context.deferredStrings = null;
        if (pending !== null && pending.length > 0) context.registerDeferredStrings(pending);
      },
      jsBodies: {
        get count() { return context.jsBodies.length; },
        materialize() {
          const errors = [];
          for (const make of context.jsBodies) {
            try { make(); } catch (e) { errors.push(String(e)); }
          }
          return errors;
        }
      }
    };
  }`, "defaults result");
replaceOnce(`      createStringWeakRef(value, heldValue) {
        stringFinalizationRegistry.register(value, heldValue);
        return new WeakRef(value);
      },`, `      createStringWeakRef(value, heldValue) {
        if (context.deferredStrings !== null) {
          // Gaius: a strong reference until the idle conversion (see the patch header).
          const ref = new DeferredStringRef(value);
          context.deferredStrings.push(ref, heldValue);
          return ref;
        }
        stringFinalizationRegistry.register(value, heldValue);
        return new WeakRef(value);
      },`, "deferred createStringWeakRef");
replaceOnce(`  function coreImports(imports, context) {`, `  // Gaius: the reference createStringWeakRef returns during the start function (see the patch header):
  // strong until registerDeferredStrings gives it a WeakRef; deref() answers like a WeakRef.
  class DeferredStringRef {
    constructor(value) {
      this.value = value;
      this.weak = null;
    }
    deref() {
      return this.weak !== null ? this.weak.deref() : this.value;
    }
  }
  function coreImports(imports, context) {`, "DeferredStringRef");
replaceOnce(`    const stringFinalizationRegistry = new FinalizationRegistry((heldValue) => {
      const report = context.exports?.["teavm.reportGarbageCollectedString"];
      if (typeof report === "function") {
        report(heldValue);
      }
    });`, `    const stringFinalizationRegistry = new FinalizationRegistry((heldValue) => {
      const report = context.exports?.["teavm.reportGarbageCollectedString"];
      if (typeof report === "function") {
        report(heldValue);
      }
    });
    context.registerDeferredStrings = (pending) => {
      // Gaius: [ref, heldValue, ...] in idle slices, in the order the start function made them:
      // the WeakRef replaces the strong reference, then the referent is registered as before.
      let next = 0;
      const idle = typeof requestIdleCallback === "function";
      const slice = (deadline) => {
        const until = idle ? 0 : performance.now() + 4;
        while (next < pending.length) {
          const ref = pending[next], value = ref.value;
          ref.weak = new WeakRef(value);
          ref.value = void 0;
          stringFinalizationRegistry.register(value, pending[next + 1]);
          pending[next] = pending[next + 1] = void 0;
          next += 2;
          if ((next & 1023) === 0 && (idle ? deadline.timeRemaining() < 1 : performance.now() >= until)) break;
        }
        if (next < pending.length) schedule();
        else pending.length = 0;
      };
      const schedule = () => idle ? requestIdleCallback(slice, { timeout: 60000 }) : setTimeout(slice, 0);
      schedule();
    };`, "deferred string registration");
replaceOnce(`    const defaultsResult = defaults(importObj, userExports, await hasStringBuiltins());`,
  `    const defaultsResult = defaults(importObj, userExports, await hasStringBuiltins(), options);`, "load defaults options");
replaceOnce(`    const instance = await WebAssembly.instantiate(module, importObj);
    allocator.malloc = instance.exports["teavm.malloc"];`, `    // Gaius: deferred string registrations cover exactly the start function (see the patch header).
    defaultsResult.beginStart(options.deferStartStringRegistrations === true);
    let instance;
    try {
      instance = await WebAssembly.instantiate(module, importObj);
    } finally {
      defaultsResult.endStart();
    }
    allocator.malloc = instance.exports["teavm.malloc"];`, "load start window");
replaceOnce(`    const teavm = {
      exports: userExports,
      instance,
      module
    };`, `    const teavm = {
      exports: userExports,
      instance,
      module,
      // Gaius: lazy @JSBody functions (see the patch header).
      jsBodies: defaultsResult.jsBodies
    };`, "load result jsBodies");

text = "// TeaVM 0.15 WebAssembly GC runtime, patched by Gaius"
  + " (port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs).\n" + text;
writeFileSync(output, text);
console.log("patched " + input + " -> " + output);
