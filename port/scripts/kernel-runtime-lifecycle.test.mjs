// Runs the production boot script with deferred host resources; no game fixture is substituted.
import test from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";
import {RENDERPEARL_PROFILE} from "./verification-profiles.mjs";

const source = await readFile(process.env.GAIUS_KERNEL_LIFECYCLE_SOURCE
  || new URL("../web/boot/gaius-boot.js", import.meta.url), "utf8");

function createPage({search = "", site = null, portable = false, protocol = "https:", inertScripts = {}, storage = {},
  clock = null, globals = {}} = {}) {
  const appended = [];
  const posted = [];
  const workers = [];
  const intervals = [];
  const listeners = new Map();
  const element = (tag) => ({
    tagName: tag, attributes: {}, onload: null, onerror: null,
    setAttribute(name, value) { this.attributes[name] = value; },
  });
  const head = {attributes: {}, dataset: {}, setAttribute(name, value) { this.attributes[name] = value; }, appendChild(node) { appended.push(node); return node; }};
  class FakeWorker {
    constructor(url, options) {
      this.url = url;
      this.options = options;
      workers.push(this);
    }
    postMessage(message, transfer) {
      posted.push({message, transfer, worker: this});
    }
    terminate() {
      this.terminated = true;
    }
  }
  const window = {
    location: {protocol, search, href: `${protocol}//example.invalid/Gaius/${RENDERPEARL_PROFILE.id}/index.html${search}`},
    navigator: {hardwareConcurrency: 8},
    document: {
      readyState: "loading", head, documentElement: head, createElement: element, getElementById: () => null,
      // Only the portable page's inert server Worker scripts are looked up by selector.
      querySelector(selector) {
        const match = /^script\[type="text\/plain"\]\[data-gaius-(?:worker|wasm)-script="([^"]+)"\]$/.exec(selector);
        return match && inertScripts[match[1]] !== undefined ? {textContent: inertScripts[match[1]]} : null;
      },
    },
    localStorage: {getItem: (key) => (Object.prototype.hasOwnProperty.call(storage, key) ? storage[key] : null), setItem() {}},
    performance: {now: () => 1, mark() {}, getEntriesByType: () => []},
    setTimeout, clearTimeout,
    setInterval: clock ? (callback) => { intervals.push({callback, active: true}); return intervals.length; } : () => 0,
    clearInterval: clock ? (id) => { if (intervals[id - 1]) intervals[id - 1].active = false; } : () => {},
    addEventListener(type, callback) {
      const entries = listeners.get(type) || [];
      entries.push(callback);
      listeners.set(type, entries);
    },
    fetch: async () => { throw new Error("no network in this smoke"); },
    URL, URLSearchParams, TextDecoder, DataView, Uint8Array, JSON, Promise, Object, Array, Map,
    MessageChannel, Worker: FakeWorker, Blob, isSecureContext: true,
  };
  if (site) window.__gaiusSite = site;
  if (portable) window.__gaiusBootPortable = true;
  // Page globals set before the boot script (runtime configuration, WebAssembly, Blob, ...).
  Object.assign(window, globals);
  if (!Object.prototype.hasOwnProperty.call(globals, "WebAssembly")) {
    window.WebAssembly = {validate: () => true, Suspending() {}, promising() {}};
  }
  if (clock) {
    window.Date = class extends Date {
      static now() { return clock.now; }
    };
  }
  window.window = window;
  const context = vm.createContext(window);
  vm.runInContext(source, context, {filename: "gaius-boot.js"});
  return {window: context, appended, posted, FakeWorker,
    workers,
    runIntervals() { for (const timer of intervals) if (timer.active) timer.callback(); },
    dispatch(type) { for (const callback of listeners.get(type) || []) callback({type}); }};
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
}
const manifest = {scripts: [], kernels: {mesher: {
  kinds: [], memory: {}, variants: {baseline: {file: "mesher.wasm", sha256: "0".repeat(64)}},
}}};
function scriptsReady(page) {
  for (const node of [...page.appended]) if (typeof node.onload === "function") node.onload();
}
const nextTurn = () => new Promise(resolve => setImmediate(resolve));
function setup({ready = true, fetch, create} = {}) {
  const state = {created: 0, terminated: 0, timers: 0, cleared: 0, submitted: 0, fetches: 0};
  const runtime = {available: () => true,
    terminate() { state.terminated++; },
    submit() { state.submitted++; return Promise.resolve("submitted"); },
  };
  let page;
  page = createPage({globals: {
    __gaiusRuntimes: {wasm: {client: "classes.wasm"}},
    GaiusKernelPolicy: {},
    GaiusKernelRuntime: {create() {
      state.created++;
      return create ? create(page.window, runtime) : runtime;
    }},
    fetch: async (...args) => {
      if (!String(args[0]).endsWith("kernels/kernels.json")) {
        return {ok: false, status: 404, json: async () => null};
      }
      state.fetches++;
      return fetch ? fetch(...args) : {ok: true, json: async () => manifest};
    },
    setInterval() { return ++state.timers; },
    clearInterval() { state.cleared++; },
  }});
  if (ready) scriptsReady(page);
  state.initialTimers = state.timers;
  return {page, state, runtime};
}

test("terminal runtime never starts new initialization", async () => {
  const {page, state} = setup();
  assert.equal(page.window.__gaiusTerminateKernels("test"), true);
  assert.equal(await page.window.__gaiusKernels.runtime(), null);
  assert.equal(state.fetches, 0);
  assert.equal(state.created, 0);
  assert.equal(state.timers, state.initialTimers);
});

test("teardown during script loading prevents manifest fetch and publication", async () => {
  const {page, state} = setup({ready: false});
  const pending = page.window.__gaiusKernels.runtime();
  page.window.__gaiusTerminateKernels("test");
  scriptsReady(page);
  assert.equal(await pending, null);
  assert.equal(state.fetches, 0);
  assert.equal(state.created, 0);
  assert.equal(state.timers, state.initialTimers);
  assert.equal(page.window.__gaiusKernelRuntime, null);
});

test("teardown during fetch prevents runtime publication and viewer timer", async () => {
  const response = deferred();
  const {page, state} = setup({fetch: () => response.promise});
  const pending = page.window.__gaiusKernels.runtime();
  await nextTurn();
  assert.equal(state.fetches, 1);
  page.window.__gaiusTerminateKernels("test");
  response.resolve({ok: true, json: async () => manifest});
  assert.equal(await pending, null);
  assert.equal(state.created, 0);
  assert.equal(state.timers, state.initialTimers);
});

test("teardown during manifest decoding preserves a successor runtime", async () => {
  const decoded = deferred();
  const {page, state} = setup({fetch: async () => ({ok: true, json: () => decoded.promise})});
  const pending = page.window.__gaiusKernels.runtime();
  await nextTurn();
  page.window.__gaiusTerminateKernels("test");
  const successor = {};
  page.window.__gaiusKernelRuntime = successor;
  decoded.resolve(manifest);
  assert.equal(await pending, null);
  assert.equal(page.window.__gaiusKernelRuntime, successor);
  assert.equal(state.created, 0);
  assert.equal(state.timers, state.initialTimers);
});

test("factory reentrant teardown disposes the unpublished runtime", async () => {
  const {page, state} = setup({create(global, runtime) {
    global.__gaiusTerminateKernels("factory");
    return runtime;
  }});
  assert.equal(await page.window.__gaiusKernels.runtime(), null);
  assert.equal(state.created, 1);
  assert.equal(state.terminated, 1);
  assert.equal(state.timers, state.initialTimers);
  assert.equal(page.window.__gaiusKernelRuntime, null);
});

test("active callers share initialization and terminal callers cannot submit", async () => {
  const {page, state, runtime} = setup();
  const first = page.window.__gaiusKernels.runtime();
  assert.equal(page.window.__gaiusKernels.runtime(), first);
  assert.equal(await first, runtime);
  assert.equal(state.created, 1);
  assert.equal(state.timers, state.initialTimers + 1);
  assert.equal(page.window.__gaiusKernelRuntime, runtime);
  assert.equal(await page.window.__gaiusKernels.submit("mesh", {}), "submitted");
  assert.equal(page.window.__gaiusTerminateKernels("test"), true);
  assert.equal(page.window.__gaiusTerminateKernels("test"), false);
  assert.equal(state.terminated, 1);
  assert.equal(state.cleared, 1);
  assert.equal(await page.window.__gaiusKernels.runtime(), null);
  await assert.rejects(page.window.__gaiusKernels.submit("mesh", {}), /not available/);
  assert.equal(state.submitted, 1);
  assert.equal(state.created, 1);
  assert.deepEqual(Array.from(await page.window.__gaiusKernels.warm()), []);
});

test("factory failure remains nonpublishing and termination is idempotent", async () => {
  const {page, state} = setup({create() { throw new Error("factory failed"); }});
  assert.equal(await page.window.__gaiusKernels.runtime(), null);
  assert.equal(await page.window.__gaiusKernels.runtime(), null);
  assert.equal(state.created, 1);
  assert.equal(state.timers, state.initialTimers);
  assert.equal(page.window.__gaiusTerminateKernels("test"), true);
  assert.equal(page.window.__gaiusTerminateKernels("test"), false);
  assert.equal(state.terminated, 0);
});

test("unpublished cleanup failure never publishes or starts a timer", async () => {
  const {page, state} = setup({create(global, runtime) {
    global.__gaiusTerminateKernels("factory");
    runtime.terminate = () => { throw new Error("cleanup failed"); };
    return runtime;
  }});
  assert.equal(await page.window.__gaiusKernels.runtime(), null);
  assert.equal(state.timers, state.initialTimers);
  assert.equal(page.window.__gaiusKernelRuntime, null);
});
