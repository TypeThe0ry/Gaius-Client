#!/usr/bin/env node
// Start-function warm-up of the portable page's compile Worker (build-portable-html.py).
// Runs the generated Worker source and the page-side _workerCompile in vm contexts with stub
// Worker/document/WebAssembly objects and checks the protocol:
//   * the Module is posted (and resolved on the page) before any warm-up work, unchanged;
//   * the warm-up instantiates exactly that Module through TeaVM.wasmGC.load, adds inert stubs
//     only for the gaiusJspi/gaiusGpu imports and keeps every import the runtime provided;
//   * the page terminates the Worker when the warm-up answers, fails, times out or when
//     __gaiusStartWarmStop is called (gaius-wasm-boot.js, right before the page instantiates);
//   * ?gaiusStartWarm=0 terminates it right after the Module arrives, as before;
//   * a runtime copy the parser has not finished is not used before DOMContentLoaded.
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("./build-portable-html.py", import.meta.url), "utf8");
const boot = await readFile(new URL("../web/runtime/wasmgc/gaius-wasm-boot.js", import.meta.url), "utf8");
const block = source.match(/\n  const _warmMode = [\s\S]*?\n  \}\n(?=  const _earlyCompile)/);
assert.ok(block, "warm-up block between _warmMode and _earlyCompile");

// gaius-wasm-boot.js stops the warm-up before anything else happens in installImports.
assert.match(boot, /installImports: function \(imports\) \{[\s\S]{0,200}if \(terminated\) return;[\s\S]{0,400}if \(typeof g\.__gaiusStartWarmStop === "function"\) g\.__gaiusStartWarmStop\(\);\s*phase\("compiled"\);/);

function pageContext({search = "", runtime = "/*runtime*/", runtimeDone = true, readyState = "interactive"} = {}) {
  const marks = [], timers = [], listeners = {}, workers = [];
  class FakeModule {}
  class FakeWorker {
    constructor(url) { this.url = url; this.sent = []; this.terminated = 0; workers.push(this); }
    postMessage(message) { this.sent.push(message); }
    terminate() { this.terminated++; }
  }
  const element = runtime === null ? null : {textContent: runtime, nextSibling: runtimeDone ? {} : null};
  const document = {
    readyState,
    querySelector: (selector) => selector.includes("gaius-wasm-gc-runtime.js") ? element : null,
    querySelectorAll: () => [{textContent: "AAAA", remove() { this.removed = true; }}],
    addEventListener: (type, fn) => { (listeners[type] ||= []).push(fn); },
  };
  const window = {__gaiusPayloadWait: () => Promise.resolve(true)};
  const context = vm.createContext({
    window, document, Worker: FakeWorker, Blob: class { constructor(parts) { this.parts = parts; } },
    URL: {createObjectURL: () => "blob:x", revokeObjectURL() {}}, URLSearchParams, location: {search},
    performance: {mark: (name) => marks.push(name)}, setTimeout: (fn, ms) => timers.push({fn, ms}),
    WebAssembly: {Module: FakeModule}, Promise, Error, String,
  });
  vm.runInContext(block[0] + "\nwindow.__compile = _workerCompile; window.__workerSource = _workerSource;", context);
  return {context, window, document, marks, timers, listeners, workers, FakeModule};
}

const flush = () => new Promise((done) => setImmediate(done));

// 1. Module first, then the warm-up; the warm-up answer terminates the Worker.
{
  const t = pageContext();
  const promise = t.window.__compile({bytes: 3, sha256: "s"});
  await flush();
  const worker = t.workers[0];
  assert.deepEqual(worker.sent.map(Object.keys), [["text", "bytes", "sha"]]);
  const module = new t.FakeModule();
  worker.onmessage({data: {module}});
  assert.equal(await promise, module, "the page receives the Worker's Module itself");
  assert.equal(typeof t.window.__gaiusStartWarmStop, "function");
  assert.deepEqual({...worker.sent[1]}, {warm: "/*runtime*/"}, "the runtime text is sent after the Module resolved");
  assert.ok(t.marks.includes("gaius:startWarmBegin"));
  assert.equal(worker.terminated, 0, "the Worker lives on while it warms");
  worker.onmessage({data: {warmed: 123}});
  assert.equal(worker.terminated, 1);
  assert.deepEqual({...t.window.__gaiusStartWarm}, {ms: 123});
  assert.equal(t.window.__gaiusStartWarmStop, null);
  assert.ok(t.marks.includes("gaius:startWarmed"));
}

// 2. The page instantiates first: the hook terminates the Worker once; later answers are ignored.
{
  const t = pageContext();
  const promise = t.window.__compile({bytes: 3, sha256: "s"});
  await flush();
  const worker = t.workers[0];
  worker.onmessage({data: {module: new t.FakeModule()}});
  await promise;
  t.window.__gaiusStartWarmStop();
  assert.equal(worker.terminated, 1);
  assert.equal(t.window.__gaiusStartWarmStop, null);
  assert.ok(t.marks.includes("gaius:startWarmStopped"));
  t.timers.forEach((timer) => timer.fn());
  worker.onmessage({data: {warmed: 1}});
  assert.equal(worker.terminated, 1, "stop is idempotent");
}

// 3. ?gaiusStartWarm=0: terminated right after the Module, no warm-up message, no hook.
{
  const t = pageContext({search: "?gaiusStartWarm=0"});
  const promise = t.window.__compile({bytes: 3, sha256: "s"});
  await flush();
  const worker = t.workers[0];
  worker.onmessage({data: {module: new t.FakeModule()}});
  await promise;
  assert.equal(worker.terminated, 1);
  assert.equal(worker.sent.length, 1);
  assert.equal(t.window.__gaiusStartWarmStop, null);
}

// 4. Compile failure still rejects (page fallback), and terminates.
{
  const t = pageContext();
  const promise = t.window.__compile({bytes: 3, sha256: "s"});
  await flush();
  t.workers[0].onmessage({data: {error: "sha256"}});
  await assert.rejects(promise, /sha256/);
  assert.equal(t.workers[0].terminated, 1);
}

// 5. Warm-up failure or timeout only stops the Worker; the Module was already resolved.
for (const how of ["warmError", "onerror", "timeout"]) {
  const t = pageContext();
  const promise = t.window.__compile({bytes: 3, sha256: "s"});
  await flush();
  const worker = t.workers[0];
  const module = new t.FakeModule();
  worker.onmessage({data: {module}});
  assert.equal(await promise, module);
  if (how === "warmError") worker.onmessage({data: {warmError: "x"}});
  else if (how === "onerror") worker.onerror({message: "boom"});
  else { assert.equal(t.timers[0].ms, 20000); t.timers[0].fn(); }
  assert.equal(worker.terminated, 1, how);
}

// 6. A runtime copy still being parsed waits for DOMContentLoaded; a missing one stops the Worker.
{
  const t = pageContext({runtimeDone: false, readyState: "loading"});
  const promise = t.window.__compile({bytes: 3, sha256: "s"});
  await flush();
  const worker = t.workers[0];
  worker.onmessage({data: {module: new t.FakeModule()}});
  await promise;
  assert.equal(worker.sent.length, 1, "no warm-up before the runtime copy is complete");
  t.document.readyState = "interactive";
  t.listeners.DOMContentLoaded[0]();
  assert.deepEqual({...worker.sent[1]}, {warm: "/*runtime*/"});
}
{
  const t = pageContext({runtime: null});
  const promise = t.window.__compile({bytes: 3, sha256: "s"});
  await flush();
  t.workers[0].onmessage({data: {module: new t.FakeModule()}});
  await promise;
  assert.equal(t.workers[0].terminated, 1);
}

// 7. Worker side: compile message posts the Module; warm message instantiates that Module.
{
  const posted = [], loads = [], imported = [];
  const module = {id: "module"};
  const worker = vm.createContext({
    postMessage: (m) => posted.push(m), performance: {now: () => 0}, Uint8Array, Response: class {},
    Blob: class { constructor(parts) { this.parts = parts; } stream() { return {pipeThrough: () => ({})}; } },
    DecompressionStream: class {}, atob: (s) => s, URL: {createObjectURL: (blob) => { imported.push(blob.parts[0]); return "blob:rt"; }},
    importScripts: (url) => { assert.equal(url, "blob:rt"); },
    crypto: {subtle: {digest: async () => new Uint8Array(32).buffer}},
    WebAssembly: {
      compileStreaming: async () => module,
      Module: {imports: () => [
        {module: "gaiusJspi", name: "park", kind: "function"}, {module: "gaiusGpu", name: "draw", kind: "function"},
        {module: "teavmJso", name: "createClass", kind: "function"}, {module: "teavmMath", name: "sin", kind: "function"}]},
    },
    TeaVM: {wasmGC: {load: async (m, options) => {
      const imports = {gaiusJspi: {park: "kept"}, teavmJso: {createClass: "runtime"}};
      options.installImports(imports);
      loads.push({m, imports});
      return {};
    }}},
    Error, String, self: {},
  });
  worker.self = worker;
  const t = pageContext();
  vm.runInContext(t.window.__workerSource, worker);
  Object.defineProperty(Uint8Array, "fromBase64", {value: () => new Uint8Array(3), configurable: true});
  await worker.onmessage({data: {text: "AAAA", bytes: 3, sha: "0".repeat(64)}});
  assert.equal(posted[0].module, module);
  await worker.onmessage({data: {warm: "/*runtime*/"}});
  assert.deepEqual(imported, ["/*runtime*/"]);
  assert.equal(loads.length, 1);
  assert.equal(loads[0].m, module, "the warm-up instantiates the posted Module");
  assert.equal(loads[0].imports.gaiusJspi.park, "kept", "existing imports are kept");
  assert.equal(typeof loads[0].imports.gaiusGpu.draw, "function");
  assert.equal(loads[0].imports.gaiusGpu.draw(), 0);
  assert.equal(loads[0].imports.teavmJso.createClass, "runtime", "only gaiusJspi/gaiusGpu get stubs");
  assert.equal(loads[0].imports.teavmMath, undefined, "no stubs for other modules");
  assert.deepEqual({...posted[1]}, {warmed: 0});
  worker.TeaVM.wasmGC.load = async () => { throw new Error("no"); };
  await worker.onmessage({data: {warm: "x"}});
  assert.deepEqual({...posted[2]}, {warmError: "no"});
  delete Uint8Array.fromBase64;
}
console.log("portable start warm-up checks passed");
