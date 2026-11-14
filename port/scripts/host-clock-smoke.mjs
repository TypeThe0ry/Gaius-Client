#!/usr/bin/env node
// HostClock.nowMillis() is the clock of the integrated server's deadline checks. Its JavaScript
// body must return what the previous bodies did (performance.now(), Date.now() in a realm
// without it) while looking the `performance` object up once instead of three times per reading:
// in a Worker `performance` is an accessor of the global's prototype chain, and each reference
// runs a getter inside the browser.
//
//   node port/scripts/host-clock-smoke.mjs
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import path from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const source = await readFile(path.join(repositoryRoot,
  "port/overrides/classlib/src/main/java/dev/gaius/browser/HostClock.java"), "utf8");

function jsBody(javaSource, declaration) {
  const methodAt = javaSource.indexOf(declaration);
  assert.notEqual(methodAt, -1, `missing declaration ${declaration}`);
  const annotationAt = javaSource.lastIndexOf("@JSBody", methodAt);
  const marker = 'script = """';
  const start = javaSource.indexOf(marker, annotationAt) + marker.length;
  const end = javaSource.indexOf('""")', start);
  assert.ok(annotationAt >= 0 && start > marker.length && end > start && end < methodAt,
    `unterminated JSBody of ${declaration}`);
  return javaSource.slice(start, end);
}

const current = jsBody(source, "public static native double nowMillis();");
// The bodies this replaces (BrowserWorldgenScheduler.nowMillis and the same in the dispatcher's
// neighbours): three references to `performance` for one reading.
const previous = `
            return typeof performance !== 'undefined' && performance.now
              ? performance.now()
              : Date.now();
            `;

/** A realm whose `performance` is an accessor that counts, like a Worker's. */
function realm({withPerformance = true, withNow = true} = {}) {
  const state = {getterCalls: 0, time: 12.5, nowCalls: 0};
  const context = vm.createContext({Date: {now: () => 1_700_000_000_123}});
  context.globalThis = context;
  if (withPerformance) {
    const performance = {};
    if (withNow) {
      performance.now = function now() {
        assert.equal(this, performance, "now() must be called on the performance object");
        state.nowCalls++;
        return state.time;
      };
    }
    Object.defineProperty(context, "performance", {
      configurable: true,
      get() { state.getterCalls++; return performance; },
    });
    state.performance = performance;
  }
  return {context, state};
}

function reader(body, context) {
  return vm.runInContext(`(function () {${body}})`, context);
}

// Same values, one lookup.
{
  const {context, state} = realm();
  const now = reader(current, context);
  for (let index = 0; index < 1000; index++) {
    state.time = 12.5 + index / 4;
    assert.equal(now(), state.time);
  }
  assert.equal(state.nowCalls, 1000);
  assert.equal(state.getterCalls, 1, "the performance accessor must run once, not once per reading");
}
{
  const {context, state} = realm();
  const now = reader(previous, context);
  for (let index = 0; index < 1000; index++) now();
  assert.equal(state.getterCalls, 3000, "the previous body references performance three times a reading");
}
// A test that swaps performance.now after the first reading is still honoured (the object is kept,
// the method is looked up on it every time).
{
  const {context, state} = realm();
  const now = reader(current, context);
  assert.equal(now(), 12.5);
  state.performance.now = () => 99.25;
  assert.equal(now(), 99.25);
}
// Without performance, or without performance.now, the clock is Date.now().
for (const options of [{withPerformance: false}, {withNow: false}]) {
  const {context} = realm(options);
  const now = reader(current, context);
  assert.equal(now(), 1_700_000_000_123);
  assert.equal(now(), 1_700_000_000_123);
  const old = reader(previous, realm(options).context);
  assert.equal(old(), 1_700_000_000_123, "the fallback is the previous one");
}
// The kept object is shared through globalThis, so every module of a realm uses one lookup.
{
  const {context, state} = realm();
  reader(current, context)();
  reader(current, context)();
  assert.equal(state.getterCalls, 1);
  assert.ok(context.__gaiusClockSource, "the kept clock source is published for the other readers");
}
// The Java side: nanoTime() is TeaVM's System.nanoTime(), (long) (performance.now() * 1e6).
assert.match(source, /public static long nanoTime\(\) \{\s*return \(long\) \(nowMillis\(\) \* 1_000_000\.0\);\s*\}/,
  "HostClock.nanoTime must keep the value of TeaVM's System.nanoTime()");

// The JSPI run queue (gaius-jspi.js) reads the clock once per item it runs: one lookup of
// `performance` for all of them, and the same slice decisions as with the global.
{
  const jspi = await readFile(path.join(repositoryRoot, "port/web/runtime/wasmgc/gaius-jspi.js"), "utf8");
  function runQueue({withPerformance = true} = {}) {
    const state = {getterCalls: 0, time: 100};
    function MessageChannel() {
      this.port1 = {onmessage: null};
      this.port2 = {postMessage: () => {}};
    }
    const context = vm.createContext({
      console, Date: {now: () => 5_000}, MessageChannel, setTimeout, clearTimeout, queueMicrotask,
      WebAssembly: {...WebAssembly, Suspending: fn => fn, promising: fn => fn},
    });
    context.globalThis = context;
    if (withPerformance) {
      const performance = {now: () => state.time};
      Object.defineProperty(context, "performance", {
        configurable: true,
        get() { state.getterCalls++; return performance; },
      });
    }
    vm.runInContext(jspi, context, {filename: "gaius-jspi.js"});
    return {scheduler: context.GaiusJspi.createScheduler(), state};
  }
  const {scheduler, state} = runQueue();
  scheduler.freshSlice();                     // slice.end = 100 + 4
  assert.equal(scheduler.sliceExpired(), false);
  state.time = 103.9;
  assert.equal(scheduler.sliceExpired(), false);
  state.time = 104;
  assert.equal(scheduler.sliceExpired(), true, "the slice ends exactly as it did with the global");
  for (let index = 0; index < 1000; index++) scheduler.sliceExpired();
  assert.equal(state.getterCalls, 1, "the run queue must look performance up once");
  const fallback = runQueue({withPerformance: false}).scheduler;
  fallback.freshSlice();
  assert.equal(fallback.sliceExpired(), false, "without performance the run queue reads Date.now()");
}

// Informational: what the saved lookups are worth in this engine (the browser's accessor is
// costlier than Node's, so this understates it).
{
  const iterations = 2_000_000;
  const measure = body => {
    const {context} = realm();
    const now = reader(body, context);
    for (let index = 0; index < 100_000; index++) now();
    const started = process.hrtime.bigint();
    let sum = 0;
    for (let index = 0; index < iterations; index++) sum += now();
    const nanoseconds = Number(process.hrtime.bigint() - started) / iterations;
    assert.ok(Number.isFinite(sum));
    return nanoseconds;
  };
  const before = measure(previous);
  const after = measure(current);
  console.log(`HOST_CLOCK_OK getters/reading 3 -> 0 (vm stand-in: ${before.toFixed(0)} -> ${after.toFixed(0)} ns/reading)`);
}
