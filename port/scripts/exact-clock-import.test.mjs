#!/usr/bin/env node
// Exact clock policy: refresh every read (N=1), zero cached staleness. A shared timestamp
// refreshed only at host turns or every N>1 reads cannot pass the within-slice test below.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source = readFileSync(new URL('../web/runtime/wasmgc/gaius-jspi.js', import.meta.url), 'utf8');
const java = readFileSync(new URL('../overrides/classlib/src/target-wasmgc/java/dev/gaius/browser/HostClock.java', import.meta.url), 'utf8');
assert.match(java, /@Import\(module = "gaiusClock", name = "nowMillis"\)\s+public static native double nowMillis\(\)/);
assert.match(java, /return \(long\) \(nowMillis\(\) \* 1_000_000\.0\)/);
function realm(withPerformance = true) {
  let time = 1.25, reads = 0, gets = 0;
  const timers = [];
  const performance = {now() { assert.equal(this, performance); reads++; return time; }};
  const context = {console, Date: {now: () => time},
    WebAssembly: {...WebAssembly, Suspending: function(fn) { return fn; }, promising: fn => fn},
    MessageChannel: class { constructor() { this.port1 = {}; this.port2 = {postMessage() {}}; } },
    setTimeout(fn, delay) { const timer = {fn, delay}; timers.push(timer); return timer; },
    clearTimeout() {}};
  if (withPerformance) Object.defineProperty(context, 'performance', {get() { gets++; return performance; }});
  vm.runInNewContext(source, context);
  const imports = {};
  context.GaiusJspi.installJspi(imports, context.GaiusJspi.createScheduler(), () => ({}), () => {});
  return {context, imports, timers, performance, advance(delta) { time += delta; },
    get time() { return time; }, get reads() { return reads; }, get gets() { return gets; }};
}
for (const available of [true, false]) {
  const r = realm(available), now = r.imports.gaiusClock.nowMillis;
  // Tiny hand-encoded wasm module: import () -> f64 and re-export it. This checks the
  // actual numeric Wasm/JS ABI without invoking a compiler or building game artifacts.
  const bytes = Uint8Array.from([
    0,97,115,109,1,0,0,0, 1,5,1,96,0,1,124,
    2,24,1,10,...Buffer.from('gaiusClock'),9,...Buffer.from('nowMillis'),0,0,
    7,7,1,3,...Buffer.from('now'),0,0
  ]);
  const wasmNow = new WebAssembly.Instance(new WebAssembly.Module(bytes), r.imports).exports.now;
  assert.equal(wasmNow(), r.time);
  let previous = now();
  // Same activation, no event-loop turn: even a very slow individual read must be fresh.
  for (const delta of [0, 0.001, 0.1, 10000, 0, 1]) {
    r.advance(delta);
    const actual = now();
    assert.equal(actual, r.time, 'zero cached staleness on every read');
    assert.ok(actual >= previous, 'monotonic host stays monotonic');
    previous = actual;
  }
  const parked = r.imports.gaiusJspi.park(1, 5);
  r.advance(5);
  r.timers.at(-1).fn();
  assert.equal(await parked, 0);
  assert.equal(now(), r.time, 'exact after park/sleep timeout');
  r.imports.gaiusJspi.unpark(2);
  assert.equal(await r.imports.gaiusJspi.park(2, 5), 1);
  r.advance(1);
  assert.equal(now(), r.time, 'exact after permit park without a host turn');
  if (available) {
    assert.equal(r.gets, 2, 'one object lookup each for scheduler and shared imported clock');
    assert.equal(r.reads, 11);
    r.performance.now = () => 123456;
    assert.equal(now(), 123456, 'method replacement remains visible');
    r.context.__gaiusClockSource = {now: () => 234567};
    assert.equal(now(), 234567, 'realm modules share the same source override');
    delete r.context.__gaiusClockSource;
    assert.equal(now(), 123456, 'deleting shared source reinitializes it');
  }
}
// Adversarial shared cell: JS cannot refresh while wasm occupies the thread. Even an N-read
// refresh cannot bound wall-time staleness when arbitrary work occurs between two reads.
{
  const cell = new WebAssembly.Global({value: 'f64', mutable: true}, 1);
  const actualTime = 1001;
  assert.notEqual(cell.value, actualTime, 'frame/turn-only shared cell is stale inside a slice');
  cell.value = actualTime;
  assert.equal(cell.value, actualTime, 'a JS refresh works only when JS actually runs');
}
console.log('EXACT_CLOCK_IMPORT_TEST PASS');
