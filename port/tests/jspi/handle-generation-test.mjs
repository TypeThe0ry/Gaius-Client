#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";
const source = readFileSync(new URL("../../web/runtime/wasmgc/gaius-jspi.js", import.meta.url), "utf8");
const timers = [];
function Suspending(fn) { return fn; }
function promising(fn) { return fn; }
const context = {console, performance: {now: () => 0}, Date,
  MessageChannel: class { constructor() { this.port1 = {onmessage: null}; this.port2 = {postMessage() {}}; } },
  setTimeout: (fn, delay) => { const t = {fn, delay, cancelled: false}; timers.push(t); return t; },
  clearTimeout: (t) => { if (t) t.cancelled = true; },
  WebAssembly: {...WebAssembly, Suspending, promising}};
context.globalThis = context;
vm.runInNewContext(source, context, {filename: "gaius-jspi.js"});
const scheduler = context.GaiusJspi.createScheduler();
const imports = {teavmJso: {}};
context.GaiusJspi.installJspi(imports, scheduler, () => ({}), () => {});
const first = imports.gaiusJspi.park(7, 10);
assert.equal(timers.length, 1, "first park arms a timer");
const oldTimer = timers[0];
imports.gaiusJspi.release(7);
// This callback belongs to the released generation. It must not create a permit for a future slot user.
oldTimer.fn();
imports.gaiusJspi.unpark(7);
const second = imports.gaiusJspi.park(7, 0);
assert.equal(typeof second.then, 'function', 'a stale callback must not leave a permit');
imports.gaiusJspi.unpark(7);
assert.equal(await second, 1, 'the current generation still wakes normally');
await first.catch(() => {});
console.log("JSPI_HANDLE_GENERATION_TEST PASS");


