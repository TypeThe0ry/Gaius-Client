#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const template = await readFile(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");
const sourceStart = template.indexOf("    const runtimeLeaseHandoffMs = 30000;");
const sourceEnd = template.indexOf("\n    async function acquireGaiusRuntimeLease", sourceStart);
assert.ok(sourceStart >= 0 && sourceEnd > sourceStart, "lifecycle source not found");
const lifecycleSource = template.slice(sourceStart, sourceEnd);

function makeWorker(events) {
  const listeners = new Set();
  let resolveRefresh;
  const refresh = new Promise(resolve => { resolveRefresh = resolve; });
  const worker = {
    __gaiusWorldId: "world",
    __gaiusStorageRefresh: refresh,
    stops: 0,
    terminated: 0,
    terminate() { this.terminated++; events.push("terminated"); },
    addEventListener(type, listener) { if (type === "message") listeners.add(listener); },
    removeEventListener(type, listener) { listeners.delete(listener); },
    postMessage(message) {
      if (message.type === "save-all") {
        events.push("save-all");
        return;
      }
      if (message.type !== "stop") return;
      this.stops++;
      events.push("stop");
      setTimeout(() => {
        for (const listener of [...listeners]) listener({data: {type: "storage-flushing"}});
        events.push("world-saved");
        this.__gaiusStorageClosed = true;
        for (const listener of [...listeners]) listener({data: {type: "stopped"}});
        events.push("stopped");
        setTimeout(() => {
          events.push("refreshed");
          resolveRefresh();
        }, 5);
      }, 0);
    }
  };
  return worker;
}

function makePage() {
  const events = [];
  const listeners = new Map();
  const context = {
    events, console: {warn() {}, info() {}, error() {}}, Date, Promise, Math,
    setTimeout, clearTimeout, setInterval, clearInterval,
    window: null, document: {visibilityState: "visible", addEventListener(type, listener) {
      const key = `document:${type}`;
      if (!listeners.has(key)) listeners.set(key, []);
      listeners.get(key).push(listener);
    }},
    addEventListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(listener);
    },
    __gaiusTerminateKernels() { events.push("kernel-teardown"); }
  };
  context.window = context;
  const worker = makeWorker(events);
  context.__gaiusSingleplayerWorkers = new Map([["world", worker]]);
  context.__gaiusFsFlush = () => new Promise(resolve => setTimeout(() => {
    events.push("flushed");
    resolve();
  }, 5));
  vm.createContext(context);
  vm.runInContext(`${lifecycleSource}\nthis.installLifecycle = releaseGaiusRuntimeLeaseOnPagehide;\nthis.requestLifecycleSave = requestGaiusLifecycleSave;`, context);
  return {
    context,
    worker,
    dispatchDocument(type) {
      for (const listener of listeners.get(`document:${type}`) || []) listener({type});
    },
    pagehide(persisted = false) {
      for (const listener of listeners.get("pagehide") || []) listener({type: "pagehide", persisted});
    }
  };
}

{
  const page = makePage();
  page.context.__gaiusSingleplayerWorkers = new Map();
  let releases = 0;
  page.context.installLifecycle(() => { releases++; page.context.events.push("release"); }, () => {});
  page.pagehide(false);
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(releases, 1, "title pagehide must release the lease immediately");
  assert.equal(page.worker.stops, 0, "title pagehide must not stop a nonexistent world");
  assert.deepEqual(page.context.events, ["release", "kernel-teardown"],
    "title pagehide must release and tear down client kernels without a save barrier");
  console.log("ok title pagehide releases without a save timer");
}

{
  const page = makePage();
  page.context.document.visibilityState = "hidden";
  page.dispatchDocument("visibilitychange");
  assert.deepEqual(page.context.events, ["save-all"], "hidden transition must request save-all");
  assert.equal(page.worker.stops, 0, "backgrounding must keep the runtime lease");
  console.log("ok visibilitychange hidden saves without stopping the Worker");
}

{
  const page = makePage();
  let cancelled = 0;
  let releases = 0;
  page.context.installLifecycle(() => { releases++; page.context.events.push("release"); }, () => { cancelled++; });
  page.pagehide(false);
  page.pagehide(false);
  await new Promise(resolve => setTimeout(resolve, 80));
  assert.equal(cancelled, 1, "pagehide must cancel the claim exactly once");
  assert.equal(page.worker.stops, 1, "duplicate pagehide events must send one stop");
  assert.equal(page.worker.terminated, 1, "pagehide must terminate the integrated server after storage-closed");
  assert.equal(releases, 1, "lease must release exactly once");
  assert.deepEqual(page.context.events,
    ["save-all", "stop", "release", "world-saved", "terminated", "stopped", "flushed", "kernel-teardown", "refreshed"],
    "pagehide must request save/stop, release synchronously, then finish storage cleanup");
  console.log("ok pagehide saves, stops, flushes, and releases in order");
}

console.log("S11_LIFECYCLE_SAVE_SMOKE_OK");
