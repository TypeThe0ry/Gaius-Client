#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("../../web/runtime/wasmgc/gaius-jspi.js", import.meta.url), "utf8");
let postCount = 0;
function MessageChannel() {
  this.port1 = {onmessage: null};
  this.port2 = {postMessage: () => {
    if (postCount++ === 0) throw new Error("transient MessagePort failure");
    queueMicrotask(() => this.port1.onmessage());
  }};
}
const context = {console, performance: {now: () => Date.now()}, Date, MessageChannel,
  setTimeout, clearTimeout, queueMicrotask,
  WebAssembly: {...WebAssembly, Suspending(fn) { return fn; }, promising(fn) { return fn; }}};
context.globalThis = context;
vm.runInNewContext(source, context, {filename: "gaius-jspi.js"});

const scheduler = context.GaiusJspi.createScheduler();
let ran = 0;
scheduler.post(() => { ran++; });
await new Promise((resolve) => setTimeout(resolve, 20));

assert.equal(ran, 1, "queued work survives a failed MessageChannel post");
assert.equal(scheduler.stats.scheduleFailures, 1, "the failed post is observable");
console.log("JSPI_SCHEDULER_RECOVERY_TEST PASS");
