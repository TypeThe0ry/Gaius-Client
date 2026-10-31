#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("../../web/runtime/wasmgc/gaius-jspi.js", import.meta.url), "utf8");
const activationError = new Error("activation exploded");
const observed = [];
const abandoned = [];

function Suspending(fn) { return fn; }
function promising(fn) { return fn; }
function MessageChannel() {
  this.port1 = {onmessage: null};
  this.port2 = {postMessage: () => queueMicrotask(() => this.port1.onmessage())};
}
const context = {
  console,
  performance: {now: () => 0},
  Date,
  MessageChannel,
  setTimeout,
  clearTimeout,
  queueMicrotask,
  WebAssembly: {...WebAssembly, Suspending, promising}
};
context.globalThis = context;
vm.runInNewContext(source, context, {filename: "gaius-jspi.js"});

const scheduler = context.GaiusJspi.createScheduler();
const imports = {teavmJso: {}};
const exports = {
  "gaius.jspi.run": () => Promise.reject(activationError),
  "gaius.jspi.abandon": (handle) => abandoned.push(handle)
};
context.GaiusJspi.installJspi(imports, scheduler, () => exports,
  (error) => observed.push(error));
imports.gaiusJspi.start(5);
await new Promise((resolve) => setTimeout(resolve, 0));

assert.deepEqual(observed, [activationError], "the activation rejection reaches the callback");
assert.equal(context.__gaiusJspiActivationError, activationError,
  "the latest activation rejection is available on the global runtime state");
assert.equal(scheduler.stats.activationFailures, 1, "the scheduler counts failed activations");
assert.deepEqual(abandoned, [5], "the failed activation still abandons its Java thread");
console.log("JSPI_ACTIVATION_ERROR_TEST PASS");
