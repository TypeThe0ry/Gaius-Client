import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";

const shim = readFileSync(new URL("../web/runtime/wasmgc/gaius-wasm-boot.js", import.meta.url), "utf8");
const bytes = new Uint8Array([0,97,115,109,1,0,0,0,1,5,1,96,0,1,127,3,2,1,0,
  7,10,1,6,97,110,115,119,101,114,0,0,10,6,1,4,0,65,42,11]);
const turn = () => new Promise(resolve => setImmediate(resolve));

function fixture(source, {compiler = true, globals = {}} = {}) {
  let release;
  const calls = [];
  const support = new Promise(resolve => { release = resolve; });
  const wasm = {Module: WebAssembly.Module, Memory: WebAssembly.Memory, Suspending() {}, promising() {}};
  for (const name of ["compile", "compileStreaming"]) {
    if (compiler) wasm[name] = (...args) => {
      calls.push({name, options: JSON.parse(JSON.stringify(args[1]))});
      return WebAssembly[name](...args);
    };
  }
  const root = {WebAssembly: wasm, Response, Uint8Array, URLSearchParams, performance,
    location: {search: ""}, GaiusJspi: {}, console: {error() {}},
    __gaiusWasmSource: source, __gaiusWasmSupportReady: support,
    TeaVM: {wasmGC: {async load(module) {
      calls.push({name: "load", module});
      if (!(module instanceof WebAssembly.Module)) return {instance: {exports: {}}};
      return {instance: await WebAssembly.instantiate(module)};
    }}},
  };
  Object.assign(root, globals);
  root.window = root;
  vm.runInNewContext(shim, root);
  return {root, calls, release: () => release(true)};
}

for (const streamed of [false, true]) {
  test(`client compiles ${streamed ? "streamed response" : "bytes"} before support scripts and preserves exports`, async () => {
    const source = streamed ? new Response(bytes, {headers: {"Content-Type": "application/wasm"}}) : bytes;
    const f = fixture(source);
    await turn();
    assert.equal(f.calls.length, 1, "compilation starts while the support promise is unresolved");
    assert.equal(f.calls[0].name, streamed ? "compileStreaming" : "compile");
    assert.deepEqual(f.calls[0].options, {builtins: ["js-string"]});
    f.release();
    const loaded = await f.root.__gaiusWasmReady;
    assert.equal(loaded.instance.exports.answer(), 42);
    assert.equal(f.calls.filter(c => c.name.startsWith("compile")).length, 1);
    assert.ok(f.calls.find(c => c.name === "load").module instanceof WebAssembly.Module);
    assert.ok(f.root.__gaiusWasmCompileTimings.finished >= f.root.__gaiusWasmCompileTimings.started);
    const baseline = await WebAssembly.instantiate(bytes, {});
    assert.deepEqual(Object.keys(loaded.instance.exports), Object.keys(baseline.instance.exports));
    assert.equal(loaded.instance.exports.answer(), baseline.instance.exports.answer());
  });
}

test("a supplied compiled module is reused by identity", async () => {
  const module = await WebAssembly.compile(bytes);
  const f = fixture(module);
  f.release();
  await f.root.__gaiusWasmReady;
  assert.deepEqual(f.calls, [{name: "load", module}]);
});

test("invalid bytes reject the existing readiness and boot error promises", async () => {
  const f = fixture(new Uint8Array([1,2,3]));
  f.release();
  await assert.rejects(f.root.__gaiusWasmReady, WebAssembly.CompileError);
  assert.ok(f.root.__gaiusBootError instanceof WebAssembly.CompileError);
  assert.equal(f.calls.some(c => c.name === "load"), false);
});

test("URL sources and hosts without a compiler keep the TeaVM loader path", async () => {
  for (const [source, options] of [["classes.wasm", {}], [bytes, {compiler: false}]]) {
    const f = fixture(source, options);
    f.release();
    await f.root.__gaiusWasmReady;
    assert.deepEqual(f.calls, [{name: "load", module: source}]);
  }
});

function moduleDatabase(initial, {unsupported = false} = {}) {
  let stored = initial;
  let closed = 0;
  const db = {close() { closed++; }, transaction() {
    const tx = {objectStore() { return {
      get() { const request = {}; setImmediate(() => { request.result = stored; request.onsuccess(); }); return request; },
      put(value) {
        if (unsupported) throw Object.assign(new Error("Module storage unavailable"), {name: "DataCloneError"});
        stored = value; setImmediate(() => tx.oncomplete());
      },
    }; }};
    return tx;
  }};
  return {globals: {__gaiusWasmModuleCacheKey: "fixture-hash:js-string", setTimeout, clearTimeout,
    indexedDB: {open() { const request = {}; setImmediate(() => { request.result = db; request.onsuccess(); }); return request; }}},
    stored: () => stored, closed: () => closed};
}

test("portable module storage skips source decoding and compilation on a supported warm visit", async () => {
  const cache = moduleDatabase(null);
  const first = fixture(bytes, {globals: cache.globals});
  first.release(); await first.root.__gaiusWasmReady; await turn();
  assert.equal(first.root.__gaiusWasmModuleCache.status, "stored");
  assert.ok(cache.stored() instanceof WebAssembly.Module);
  const warm = fixture(null, {globals: cache.globals});
  Object.defineProperty(warm.root, "__gaiusWasmSource", {get() { throw new Error("warm hit must not decode source"); }});
  warm.release();
  assert.equal((await warm.root.__gaiusWasmReady).instance.exports.answer(), 42);
  assert.equal(warm.calls.some(call => call.name.startsWith("compile")), false);
  assert.equal(warm.root.__gaiusWasmModuleCache.status, "hit");
  assert.equal(cache.closed(), 2);
});

test("portable module storage rejection preserves executable bytes and reports unsupported", async () => {
  const cache = moduleDatabase(null, {unsupported: true});
  const f = fixture(bytes, {globals: cache.globals});
  f.release();
  assert.equal((await f.root.__gaiusWasmReady).instance.exports.answer(), 42);
  assert.equal(f.root.__gaiusWasmModuleCache.status, "unsupported");
  assert.equal(cache.closed(), 1);
});

test("a browser that cannot store modules is remembered: the next visit skips the IndexedDB lookup", async () => {
  const marks = new Map();
  const localStorage = {getItem: key => marks.has(key) ? marks.get(key) : null, setItem: (key, value) => marks.set(key, value)};
  const navigator = {userAgent: "Fixture/1.0"};
  const cache = moduleDatabase(null, {unsupported: true});
  let opens = 0;
  const open = cache.globals.indexedDB.open;
  cache.globals.indexedDB.open = () => { opens++; return open(); };
  const globals = {...cache.globals, localStorage, navigator};
  const first = fixture(bytes, {globals});
  first.release();
  assert.equal((await first.root.__gaiusWasmReady).instance.exports.answer(), 42);
  assert.equal(first.root.__gaiusWasmModuleCache.status, "unsupported");
  assert.equal(opens, 1, "the first visit tries the cache");
  assert.equal(marks.get("gaius.wasmModuleCache.unsupported"), "Fixture/1.0");
  const second = fixture(bytes, {globals});
  second.release();
  assert.equal((await second.root.__gaiusWasmReady).instance.exports.answer(), 42);
  assert.equal(opens, 1, "the second visit does not open the database");
  assert.equal(second.root.__gaiusWasmModuleCache.status, "unsupported");
  assert.equal(second.root.__gaiusWasmModuleCache.skipped, true);
  // An updated browser tries again.
  const third = fixture(bytes, {globals: {...globals, navigator: {userAgent: "Fixture/2.0"}}});
  third.release();
  assert.equal((await third.root.__gaiusWasmReady).instance.exports.answer(), 42);
  assert.equal(opens, 2);
});
