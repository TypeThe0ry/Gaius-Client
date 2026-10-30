#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

// A wake queued for activation A must not grant a permit to activation B after the
// numeric slot is reused. The scheduler is kept in a draining state so both wakes
// take the same queued path used by a monitor hand-off inside a run-queue drain.
const source = readFileSync(new URL("../../web/runtime/wasmgc/gaius-jspi.js", import.meta.url), "utf8");
function Suspending(fn) { return fn; }
function promising(fn) { return fn; }
const context = {
  console,
  performance: {now: () => 0},
  Date,
  MessageChannel: class {
    constructor() {
      this.port1 = {onmessage: null};
      this.port2 = {postMessage() {}};
    }
  },
  setTimeout,
  clearTimeout,
  WebAssembly: {...WebAssembly, Suspending, promising},
};
context.globalThis = context;
vm.runInNewContext(source, context, {filename: "gaius-jspi.js"});

const scheduler = {
  stats: {parks: 0},
  turn: {running: false},
  draining: () => true,
  beginSlice() {},
  post(fn) {
    queue.push(fn);
  },
};
const queue = [];
const imports = {};
context.GaiusJspi.installJspi(imports, scheduler, () => ({}), () => {});
const host = imports.gaiusJspi;

const first = host.park(7, 0);
host.unpark(7);                 // wake A, queued
queue.push(() => host.start(7)); // B reuses A's slot after A is released
host.unpark(7);                 // second wake for A, queued before A exits
queue.shift()();                // resolve A
await first;
host.release(7);
queue.shift()();                // start B and create its generation
queue.shift()();                // stale wake for A: must be dropped
queue.shift();                  // discard B's mocked activation entry

const bPark = host.park(7, 0);
assert.equal(typeof bPark?.then, "function", "stale wake granted B a permit");
host.unpark(7);                 // current B wake
queue.shift()();
assert.equal(await bPark, 1, "current generation did not wake");
console.log("JSPI_QUEUED_WAKE_GENERATION_TEST PASS");
