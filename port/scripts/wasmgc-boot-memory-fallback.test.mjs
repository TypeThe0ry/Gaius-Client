import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const script = await readFile(new URL("../web/runtime/wasmgc/gaius-wasm-boot.js", import.meta.url), "utf8");
const maxBytes = [1024, 768, 512, 384, 320].map(mib => mib * 1024 * 1024);

// Exercise the unmodified IIFE through its real startup promise. As in the boot smokes,
// omit native compilation so TeaVM receives the supplied source directly.
function boot({source = new Uint8Array(0), failures = [], search = ""} = {}) {
  const calls = [], delays = [];
  const result = {instance: {exports: {}}};
  let refreshes = 0;
  const window = {
    URLSearchParams, Response, performance,
    console: {warn() {}, error() {}},
    location: {search},
    WebAssembly: {Suspending() {}, promising() {}},
    GaiusJspi: {},
    __gaiusWasmSupportReady: Promise.resolve(true),
    __gaiusWasmSource: source,
    __gaiusLinear: {refresh() { refreshes++; }},
    setTimeout(callback, delay) {
      delays.push(delay);
      queueMicrotask(callback);
      return delays.length;
    },
    TeaVM: {wasmGC: {async load(actualSource, options) {
      const index = calls.length;
      calls.push({source: actualSource, options});
      // A streaming loader consumes the Response before instantiation can fail.
      if (actualSource instanceof Response) await actualSource.arrayBuffer();
      if (index < failures.length) throw failures[index];
      return result;
    }}},
  };
  window.window = window;
  vm.runInNewContext(script, window, {filename: "gaius-wasm-boot.js"});
  return {window, calls, delays, result, source, refreshes: () => refreshes};
}

function checkOptions(run, retries, {lazy = true, defer = true} = {}) {
  assert.equal(run.calls.length, retries + 1);
  assert.deepEqual(run.delays, Array(retries).fill(50));
  const original = run.calls[0].options;
  assert.equal(Object.hasOwn(original.memory, "maxSize"), false);
  assert.deepEqual(Object.keys(original).sort(),
    ["deferStartStringRegistrations", "installImports", "lazyJsBodies", "memory"]);
  for (const [index, call] of run.calls.entries()) {
    assert.equal(call.source, run.source, "every attempt reuses the same source");
    assert.equal(call.options.lazyJsBodies, lazy);
    assert.equal(call.options.deferStartStringRegistrations, defer);
    assert.equal(call.options.installImports, original.installImports);
    assert.equal(call.options.memory.onResize, original.memory.onResize);
    if (index > 0) {
      assert.notEqual(call.options, original);
      assert.notEqual(call.options.memory, original.memory);
      assert.equal(call.options.memory.maxSize, maxBytes[index - 1]);
    }
    call.options.memory.onResize();
  }
  assert.equal(run.refreshes(), retries + 1, "onResize still refreshes linear memory views");
}

test("first load succeeds without a memory maximum override", async () => {
  for (const disabled of [false, true]) {
    const run = boot({search: disabled ? "?gaiusLazyJsBody=0&gaiusDeferStrings=0" : ""});
    assert.equal(await run.window.__gaiusWasmReady, run.result);
    checkOptions(run, 0, {lazy: !disabled, defer: !disabled});
    assert.equal(run.window.__gaiusWasmMemoryFallback, undefined);
  }
});

test("memory allocation failures retry each decreasing maximum and publish telemetry", async () => {
  for (const source of [new Uint8Array(0), "classes.wasm",
    new WebAssembly.Module(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]))]) {
    for (let retries = 1; retries <= maxBytes.length; retries++) {
      const run = boot({source, failures: Array.from({length: retries},
        () => new RangeError("could not allocate memory"))});
      assert.equal(await run.window.__gaiusWasmReady, run.result);
      checkOptions(run, retries);
      assert.equal(run.window.__gaiusWasmMemoryFallback.attempts, retries);
      assert.equal(run.window.__gaiusWasmMemoryFallback.maxBytes, maxBytes[retries - 1]);
      assert.equal(run.window.__gaiusWasmError, undefined);
    }
  }
});

test("exhausting all memory maxima propagates the last error", async () => {
  const failures = Array.from({length: maxBytes.length + 1}, (_, index) =>
    new RangeError(`could not allocate memory: attempt ${index + 1}`));
  const run = boot({failures});
  await assert.rejects(run.window.__gaiusWasmReady, error => error === failures.at(-1));
  checkOptions(run, maxBytes.length);
  assert.equal(run.window.__gaiusWasmError, failures.at(-1));
  assert.equal(run.window.__gaiusWasmMemoryFallback.attempts, maxBytes.length);
  assert.equal(run.window.__gaiusWasmMemoryFallback.maxBytes, maxBytes.at(-1));
});

test("non-memory failures are never retried", async () => {
  for (const error of [new WebAssembly.LinkError("could not allocate memory"),
    new RangeError("invalid table size")]) {
    const run = boot({failures: [error]});
    await assert.rejects(run.window.__gaiusWasmReady, actual => actual === error);
    checkOptions(run, 0);
    assert.equal(run.window.__gaiusWasmMemoryFallback, undefined);
  }
});

test("a consumed Response source is not retried on memory allocation failure", async () => {
  const source = new Response(new Uint8Array([0, 97, 115, 109]));
  const error = new RangeError("could not allocate memory");
  const run = boot({source, failures: [error]});
  await assert.rejects(run.window.__gaiusWasmReady, actual => actual === error);
  assert.equal(source.bodyUsed, true);
  checkOptions(run, 0);
  assert.equal(run.window.__gaiusWasmMemoryFallback, undefined);
});
