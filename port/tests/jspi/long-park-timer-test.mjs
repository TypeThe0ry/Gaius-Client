#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("../../web/runtime/wasmgc/gaius-jspi.js", import.meta.url), "utf8");
const timers = [];
let now = 0;
function Suspending(fn) { return fn; }
function promising(fn) { return fn; }
const context = {
  console,
  performance: {now: () => now},
  Date: {now: () => now},
  MessageChannel: class {
    constructor() { this.port1 = {onmessage: null}; this.port2 = {postMessage() {}}; }
  },
  setTimeout: (fn, delay) => { const timer = {fn, delay, cancelled: false}; timers.push(timer); return timer; },
  clearTimeout: (timer) => { if (timer) timer.cancelled = true; },
  WebAssembly: {...WebAssembly, Suspending, promising}
};
context.globalThis = context;
vm.runInNewContext(source, context, {filename: "gaius-jspi.js"});

const scheduler = context.GaiusJspi.createScheduler();
const imports = {teavmJso: {}};
context.GaiusJspi.installJspi(imports, scheduler, () => ({}), () => {});
const timeout = 2 * 2147483647 + 10;
const parked = imports.gaiusJspi.park(3, timeout);
assert.equal(timers.length, 1, "the long park arms an initial timer");
assert.equal(timers[0].delay, 2147483647, "the initial timer stays within setTimeout's limit");

now = 2147483647;
timers[0].fn();
assert.equal(timers.length, 2, "a long park is re-armed after the first timer segment");
assert.equal(timers[1].delay, 2147483647, "the second timer covers the remaining long segment");

now = 2 * 2147483647;
timers[1].fn();
assert.equal(timers.length, 3, "the final timer segment is armed");
assert.equal(timers[2].delay, 10, "the final timer uses the exact remaining duration");
now += 10;
timers[2].fn();
assert.equal(await parked, 0, "the park resolves only at the full deadline");
console.log("JSPI_LONG_PARK_TIMER_TEST PASS");
