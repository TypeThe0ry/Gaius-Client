#!/usr/bin/env node

import assert from "node:assert/strict";
import vm from "node:vm";
import {readFile} from "node:fs/promises";

const source = process.env.KERNEL_WORKER_SOURCE
  ? await readFile(process.env.KERNEL_WORKER_SOURCE, "utf8")
  : await readFile(new URL("../web/kernels/kernel-worker.js", import.meta.url), "utf8");
if (process.env.KERNEL_WORKER_RACE_ONLY) {
  assert.match(source, /if \(kernels\.get\(name\) !== state\) continue;/,
    "drain recovery must ignore failures from a replaced generation");
}
const messages = [];
let failNextInstantiate = false;
let instanceCount = 0;
const pendingInstantiations = [];

const moduleExports = () => [
  {name: "memory", kind: "memory"},
  {name: "alloc", kind: "function"},
  {name: "reset", kind: "function"},
  {name: "run_trap", kind: "function"},
];
const fakeWasm = {
  Module: {exports: moduleExports, imports: () => []},
  instantiate() {
    instanceCount++;
    if (pendingInstantiations.length > 0) return pendingInstantiations.shift().promise;
    if (failNextInstantiate) {
      failNextInstantiate = false;
      throw new Error("reinstantiate failed");
    }
    const buffer = new ArrayBuffer(65536);
    return {exports: {
      memory: {buffer},
      alloc: () => 64,
      reset() {},
      run_trap() { throw new Error("trap"); },
    }};
  },
  async compile() { return {}; },
};
const context = vm.createContext({
  WebAssembly: fakeWasm,
  TextDecoder,
  setTimeout,
  postMessage(message) { messages.push(message); },
  console,
});
vm.runInContext("globalThis.self = globalThis;", context);
vm.runInContext(source, context, {filename: "kernel-worker.js"});
const send = (data) => context.onmessage({data});
const waitFor = async (predicate) => {
  for (let i = 0; i < 100; i++) {
    const found = messages.find(predicate);
    if (found) return found;
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
  assert.fail("timed out waiting for worker message");
};

if (!process.env.KERNEL_WORKER_RACE_ONLY) {
send({type: "init", bytes: new Uint8Array([1, 2, 3]).buffer});
const invalid = await waitFor((m) => m.type === "init-error");
assert.match(invalid.message, /WebAssembly magic header/);
send({type: "load", kernel: "mesh", generation: 7, module: {}});
await waitFor((m) => m.type === "loaded");
send({type: "job", id: 1, kernel: "mesh", kind: "trap", payload: new ArrayBuffer(0)});
await waitFor((m) => m.type === "error" && m.code === "kernel-trap");
failNextInstantiate = true;
send({type: "job", id: 2, kernel: "mesh", kind: "trap", payload: new ArrayBuffer(0)});
const failure = await waitFor((m) => m.type === "load-error");
assert.equal(failure.generation, 7,
  "reinstantiation load-error must identify the generation so the runtime can clear its slot");
assert.equal(instanceCount, 2);
console.log("KERNEL_WORKER_RECOVERY_TEST PASS");
}

const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return {promise, resolve, reject};
};
messages.length = 0;
const first = deferred();
const second = deferred();
pendingInstantiations.push(first, second);
send({type: "load", kernel: "race", generation: 1, module: {}});
send({type: "unload", kernel: "race", generation: 1});
send({type: "load", kernel: "race", generation: 2, module: {}});
first.reject(new Error("old generation failed"));
second.resolve({exports: {memory: {buffer: new ArrayBuffer(65536)}, alloc: () => 64,
  reset() {}, run_trap() { throw new Error("trap"); }}});
const current = await waitFor((m) => m.type === "loaded" && m.kernel === "race");
assert.equal(current.generation, 2,
  "an old generation failure must not delete or invalidate the newer load");
console.log("KERNEL_WORKER_GENERATION_RACE_TEST PASS");

