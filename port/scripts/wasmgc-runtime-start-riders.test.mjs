#!/usr/bin/env node
// Start-function riders of the patched TeaVM runtime (patch-wasm-gc-runtime.mjs 7 and 8), checked
// against a local stock runtime. Nothing is downloaded.
//   node wasmgc-runtime-start-riders.test.mjs <stock wasm-gc-runtime.js> <output-directory>
// (the stock file: unzip -p <overlay>/teavm-core-0.15.0-gaius.jar org/teavm/backend/wasm/wasm-gc-runtime.js)
//  7. lazy @JSBody functions answer exactly like the eager bound functions: result, receiver (sloppy
//     and strict bodies), arguments, wrapCallFromJavaToJs, length, thrown values, bindFunctionN,
//     callFunctionN; a body is compiled on its first call; jsBodies.materialize() compiles the rest
//     and reports syntax errors; lazyJsBodies: false is the eager form.
//  8. deferred string weak references: deref() answers the referent at once, registrations happen
//     after the start window, in creation order, with the same held values; the eager path is
//     unchanged outside the window or when the option is off; load() closes the window in finally.
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdirSync, readFileSync} from "node:fs";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

const [input, destination] = process.argv.slice(2);
if (!input || !destination) throw new Error("usage: wasmgc-runtime-start-riders.test.mjs <stock-runtime.js> <output-directory>");
mkdirSync(resolve(destination), {recursive: true});
const patched = join(resolve(destination), "gaius-wasm-gc-runtime.js");
const patcher = fileURLToPath(new URL("../web/runtime/wasmgc/patch-wasm-gc-runtime.mjs", import.meta.url));
if (!process.env.GAIUS_RIDERS_PATCHED) execFileSync(process.execPath, [patcher, resolve(input), patched], {stdio: "ignore"});
const source = readFileSync(process.env.GAIUS_RIDERS_PATCHED || patched, "utf8");

function runtime({idle = true} = {}) {
  const registered = [], idleQueue = [], timers = [];
  class FakeRegistry {
    constructor(callback) { this.callback = callback; }
    register(value, held) { registered.push([value, held]); }
  }
  const sandbox = {
    console, performance, WeakRef, URL, TextDecoder, TextEncoder, Promise, Error, SyntaxError,
    FinalizationRegistry: FakeRegistry, WebAssembly: {}, Symbol, Map, Set, Object, Array, Math, Date,
    setTimeout: (fn, ms) => { timers.push(fn); return timers.length; }, clearTimeout() {},
    MessageChannel: class { constructor() { this.port1 = {}; this.port2 = {postMessage() {}}; } },
  };
  if (idle) sandbox.requestIdleCallback = (fn, options) => { idleQueue.push({fn, options}); return idleQueue.length; };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(source, sandbox);
  return {sandbox, TeaVM: sandbox.TeaVM, registered, idleQueue, timers};
}

// ---- 7. lazy @JSBody functions
{
  const {TeaVM, sandbox} = runtime();
  const eagerImports = {}, lazyImports = {};
  TeaVM.wasmGC.defaults(eagerImports, {}, true, {lazyJsBodies: false});
  const lazyResult = TeaVM.wasmGC.defaults(lazyImports, {}, true, {});
  const E = eagerImports.teavmJso, L = lazyImports.teavmJso;
  const cases = [
    [0, [], "return this === globalThis;"],
    [0, [], "'use strict'; return this === globalThis;"],
    [2, ["a", "b"], "return a * 10 + b;"],
    [3, ["a", "b", "c"], "return [a, b, c, arguments.length].join(',');"],
    [1, ["x"], "return typeof wrapCallFromJavaToJs + ':' + x;"],
    [2, ["a", "b"], "return (this.__riderSink = a + b);"],
  ];
  for (const [n, names, body] of cases) {
    const e = E["createFunction" + n](...names, body), l = L["createFunction" + n](...names, body);
    assert.equal(l.length, e.length, `length of ${body}`);
    const args = Array.from({length: n}, (_, i) => i + 1);
    assert.deepEqual(l(...args), e(...args), `result of ${body}`);
    assert.deepEqual(L["callFunction" + n](l, ...args), E["callFunction" + n](e, ...args), `callFunction of ${body}`);
    if (n > 0) {
      const be = E["bindFunction" + (n - 1)](e, 7), bl = L["bindFunction" + (n - 1)](l, 7);
      assert.deepEqual(bl(...args.slice(1)), be(...args.slice(1)), `bindFunction of ${body}`);
    }
  }
  // Thrown values pass through unchanged.
  const marker = {marker: true};
  sandbox.__riderMarker = marker;
  const te = E.createFunction0("throw globalThis.__riderMarker;"), tl = L.createFunction0("throw globalThis.__riderMarker;");
  assert.throws(() => te(), (x) => x === marker);
  assert.throws(() => tl(), (x) => x === marker);
  // A syntax error: at creation in the eager form, at the first call in the lazy form.
  assert.throws(() => E.createFunction1("a", "return ("), (x) => x.name === "SyntaxError");
  const bad = L.createFunction1("a", "return (");
  assert.throws(() => bad(1), (x) => x.name === "SyntaxError");
  // materialize() compiles every body once more cheaply and reports the broken one.
  const before = lazyResult.jsBodies.count;
  assert.equal(before, cases.length + 2, "one pending body per lazy createFunction");
  const errors = lazyResult.jsBodies.materialize();
  assert.equal(errors.length, 1);
  assert.match(errors[0], /SyntaxError/);
  // lazyJsBodies: false creates bound functions, as TeaVM does.
  assert.match(String(E.createFunction0("return 1;")), /native code/);
  assert.doesNotMatch(String(L.createFunction0("return 1;")), /native code/);
}

// ---- 8. deferred string weak references
for (const idle of [true, false]) {
  const {TeaVM, registered, idleQueue, timers} = runtime({idle});
  const imports = {};
  const result = TeaVM.wasmGC.defaults(imports, {}, true, {});
  const T = imports.teavm;
  const strings = Array.from({length: 2500}, (_, i) => ({s: "literal" + i}));
  const held = strings.map((_, i) => ({entry: i}));
  // Outside the window: registered at once, a real WeakRef.
  const direct = T.createStringWeakRef(strings[0], held[0]);
  assert.ok(direct instanceof WeakRef || Object.prototype.toString.call(direct) === "[object WeakRef]");
  assert.equal(registered.length, 1);
  registered.length = 0;
  // Option off: unchanged.
  result.beginStart(false);
  T.createStringWeakRef(strings[1], held[1]);
  result.endStart();
  assert.equal(registered.length, 1);
  registered.length = 0;
  // Window on.
  result.beginStart(true);
  const refs = strings.map((s, i) => T.createStringWeakRef(s, held[i]));
  assert.equal(registered.length, 0, "nothing registered inside the window");
  refs.forEach((r, i) => { assert.equal(r.deref(), strings[i]); assert.equal(T.stringDeref(r), strings[i]); });
  result.endStart();
  assert.equal(registered.length, 0, "registration waits for idle time");
  const after = T.createStringWeakRef(strings[0], held[0]);
  assert.equal(registered.length, 1, "after the window registrations are immediate again");
  assert.equal(after.deref(), strings[0]);
  registered.length = 0;
  let slices = 0;
  for (;;) {
    if (idle) {
      const job = idleQueue.shift();
      if (!job) break;
      assert.equal(job.options.timeout, 60000);
      // A deadline that is used up after the first check of each slice.
      let checks = 0;
      job.fn({timeRemaining: () => (checks++ ? 0 : 50), didTimeout: false});
    } else {
      const job = timers.shift();
      if (!job) break;
      job();
    }
    slices++;
  }
  assert.ok(idle ? slices >= 2 : slices >= 1, "registration runs in idle slices");
  assert.equal(registered.length, strings.length, "every deferred referent is registered");
  registered.forEach(([value, h], i) => { assert.equal(value, strings[i], "creation order"); assert.equal(h, held[i], "same held value"); });
  refs.forEach((r, i) => {
    assert.equal(r.deref(), strings[i], "deref after the conversion");
    assert.equal(r.value, undefined, "no strong reference after the conversion");
    assert.ok(Object.prototype.toString.call(r.weak) === "[object WeakRef]");
  });
}

// ---- load(): options reach defaults, the start window is closed in finally.
assert.match(source, /const defaultsResult = defaults\(importObj, userExports, await hasStringBuiltins\(\), options\);/);
assert.match(source, /defaultsResult\.beginStart\(options\.deferStartStringRegistrations === true\);\s*let instance;\s*try \{\s*instance = await WebAssembly\.instantiate\(module, importObj\);\s*\} finally \{\s*defaultsResult\.endStart\(\);\s*\}/);
assert.match(source, /jsBodies: defaultsResult\.jsBodies/);
console.log("wasmgc runtime start riders: lazy @JSBody functions and deferred string weak references pass");
