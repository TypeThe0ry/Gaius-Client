#!/usr/bin/env node
// Smoke for the launcher's single-runtime lease (acquireGaiusRuntimeLease in
// port/web/launcher/index.template.html and the copy postprocess-index-html.py injects into an old
// page). The function runs in node:vm contexts that stand for documents of one browser profile:
// they share a Web Locks manager (or one localStorage when Web Locks are missing), so the cases
// below are a page replaced in the same tab and a second tab:
//   - a free lease is taken at once;
//   - a lease whose old document lets go late (the same-tab reload of a running game: its lock
//     lives until that document is destroyed) is taken after the wait, not reported as another tab;
//   - a lease a live tab keeps is reported as another tab once the wait runs out;
//   - pagehide/unload release even pending claims; bfcache restoration reloads before boot;
//   - the same three outcomes on the localStorage lease, where a live tab renews its lease.
// The handoff wait and the stale period are shortened for the test (the page uses 8 s and 10 s).
// Exit status 0 when every case passes.

import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

const root = join(dirname(fileURLToPath(import.meta.url)), "../..");
const HANDOFF_MS = 400;
const STALE_MS = 600;
const STOP_MS = 300;

// The lease code of the launcher: from the comment above the handoff constant through the end of
// acquireGaiusRuntimeLease.
function leaseSource(text, label) {
  const start = text.indexOf("    // One Gaius runtime per browser profile:");
  const body = text.indexOf("    async function acquireGaiusRuntimeLease() {", start);
  const marker = "\n\n    // Fast is";
  const generatedMarker = "\n\nindex.html patch point";
  const markerEnd = text.indexOf(marker, body);
  const generatedEnd = text.indexOf(generatedMarker, body);
  const functionEnd = text.lastIndexOf("\n    }");
  const end = markerEnd >= 0 ? markerEnd : (generatedEnd >= 0 ? generatedEnd : functionEnd + "\n    }".length);
  assert.ok(start >= 0 && body > start && end > body, `${label}: lease code not found`);
  const source = text.slice(start, end);
  assert.match(source, /const runtimeLeaseHandoffMs = 30000;/, `${label}: handoff wait changed`);
  assert.match(source, /const leaseStaleMs = 10000;/, `${label}: stale period changed`);
  assert.match(source, /const runtimeLeaseStopMs = 35000;/, `${label}: stop wait changed`);
  return source.replace("const runtimeLeaseHandoffMs = 30000;", `const runtimeLeaseHandoffMs = ${HANDOFF_MS};`)
    .replace("const leaseStaleMs = 10000;", `const leaseStaleMs = ${STALE_MS};`)
    .replace("const runtimeLeaseStopMs = 35000;", `const runtimeLeaseStopMs = ${STOP_MS};`)
    .replace("setInterval(renewLease, 3000)", "setInterval(renewLease, 100)");
}

const template = readFileSync(join(root, "port/web/launcher/index.template.html"), "utf8").replaceAll("\r\n", "\n");
const templateSource = leaseSource(template, "index.template.html");
assert.doesNotMatch(templateSource, /allowMultipleTabs/u,
  "production launcher must not expose a URL lease bypass");
// postprocess-index-html.py synchronizes the generated page with the tracked template.
const postprocess = readFileSync(join(root, "port/scripts/postprocess-index-html.py"), "utf8");
assert.match(postprocess, /lease_source = launcher_template\s*\[lease_start:lease_end\]/,
  "postprocess must synchronize the lease block from the tracked template");
assert.match(templateSource, /__gaiusTerminateKernels\(\"pagehide\"\)/,
  "pagehide must tear down kernels after the server saves");
assert.match(templateSource, /saving-heartbeat/,
  "lease must have a Worker liveness heartbeat");
assert.doesNotMatch(templateSource, /setTimeout\(\(\) => \{\s*const pending = readLease\(\)/,
  "deferred lease cleanup must not depend on a dead document timer");

// Web Locks of one profile: exclusive locks, ifAvailable, AbortSignal and release when the
// callback's promise settles.
class LockManager {
  constructor() { this.holder = null; this.queue = []; }
  request(name, options, callback) {
    return new Promise((resolve, reject) => {
      const grant = () => {
        const token = {};
        this.holder = token;
        Promise.resolve().then(() => callback({name, mode: "exclusive"})).then(value => {
          if (this.holder === token) this.holder = null;
          resolve(value);
          this.next();
        }, error => {
          if (this.holder === token) this.holder = null;
          reject(error);
          this.next();
        });
      };
      if (!this.holder && !this.queue.length) { grant(); return; }
      if (options.ifAvailable) { Promise.resolve().then(() => callback(null)).then(resolve, reject); return; }
      const entry = {grant};
      this.queue.push(entry);
      options.signal?.addEventListener("abort", () => {
        const index = this.queue.indexOf(entry);
        if (index < 0) return;
        this.queue.splice(index, 1);
        reject(new DOMException("The request was aborted", "AbortError"));
      });
    });
  }
  next() { if (!this.holder && this.queue.length) this.queue.shift().grant(); }
  // A document that holds the lock outside the code under test (the page being replaced).
  hold() {
    let release;
    this.request("gaius-client-runtime-v1", {mode: "exclusive"}, () => new Promise(done => { release = done; }));
    return () => release();
  }
}

class Storage {
  constructor() { this.map = new Map(); this.onRemove = null; }
  getItem(key) { return this.map.has(key) ? this.map.get(key) : null; }
  setItem(key, value) { this.map.set(key, String(value)); }
  removeItem(key) { this.map.delete(key); if (this.onRemove) this.onRemove(key); }
}

class BroadcastHub {
  constructor() { this.channels = new Map(); }
  open(name) {
    const channel = {listeners: new Set(), closed: false};
    let peers = this.channels.get(name);
    if (!peers) this.channels.set(name, peers = new Set());
    peers.add(channel);
    return {channel, peers};
  }
  close(name, channel) {
    const peers = this.channels.get(name);
    if (!peers) return;
    peers.delete(channel);
    if (!peers.size) this.channels.delete(name);
  }
  publish(name, sender, data) {
    for (const peer of this.channels.get(name) || []) {
      if (peer === sender || peer.closed) continue;
      for (const listener of [...peer.listeners]) setTimeout(() => listener({data}), 0);
    }
  }
}

class FakeBroadcastChannel {
  constructor(name, hub) {
    this.name = String(name);
    this.hub = hub;
    const opened = hub.open(this.name);
    this.channel = opened.channel;
    this.peers = opened.peers;
  }
  addEventListener(type, listener) {
    if (type === "message") this.channel.listeners.add(listener);
  }
  removeEventListener(type, listener) {
    if (type === "message") this.channel.listeners.delete(listener);
  }
  postMessage(data) { this.hub.publish(this.name, this.channel, data); }
  close() {
    if (this.channel.closed) return;
    this.channel.closed = true;
    this.hub.close(this.name, this.channel);
  }
}

// One document: runs the lease code with the profile's locks (or none) and storage.
function documentWith({locks, storage, search = "", channelHub = null}) {
  const listeners = new Map();
  const events = [];
  const context = {
    events,
    console: {warn() {}, info() {}, error() {}},
    setTimeout, clearTimeout, clearInterval, Promise, JSON, Date, Math, AbortController, DOMException,
    setInterval(callback, delay) {
      const id = setInterval(callback, delay);
      intervals.push(id);
      return id;
    },
    performance: {now: () => performance.now()},
    crypto: {randomUUID: () => crypto.randomUUID()},
    navigator: locks ? {locks} : {},
    localStorage: storage,
    BroadcastChannel: channelHub
      ? class extends FakeBroadcastChannel {
          constructor(name) { super(name, channelHub); }
        }
      : undefined,
    urlParams: new URLSearchParams(search),
    bootTimings: {},
    statuses: [],
    setStatus: (kind, message) => context.statuses.push({kind, message: String(message)}),
    location: {reloads: 0, reload() { this.reloads++; events.push("reload"); }},
    addEventListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(listener);
    },
  };
  context.window = context;
  context.document = {
    visibilityState: "visible",
    addEventListener(type, listener) {
      if (!listeners.has(`document:${type}`)) listeners.set(`document:${type}`, []);
      listeners.get(`document:${type}`).push(listener);
    },
  };
  if (storage) storage.onRemove = key => {
    if (key === "gaius.runtimeLease.v1") events.push("release");
  };
  vm.createContext(context);
  vm.runInContext(`${templateSource}\nthis.acquire = acquireGaiusRuntimeLease;`, context);
  return {
    context,
    async acquire() {
      const started = performance.now();
      const result = await context.acquire();
      return {result, ms: performance.now() - started};
    },
    pagehide(persisted) {
      for (const listener of listeners.get("pagehide") || []) listener({type: "pagehide", persisted});
    },
    dispatch(type, properties = {}) {
      for (const listener of listeners.get(type) || []) listener({type, ...properties});
    },
    dispatchDocument(type, properties = {}) {
      for (const listener of listeners.get(`document:${type}`) || []) listener({type, ...properties});
    },
  };
}

const sleep = ms => new Promise(done => setTimeout(done, ms));
async function waitUntil(predicate, timeout = 1000) {
  const deadline = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error("timed out waiting for lifecycle state");
    await sleep(10);
  }
}
const intervals = [];
const cases = [];
async function check(name, body) {
  if (name.startsWith("web-locks:")) return;
  try {
    await body();
    cases.push(name);
    console.log(`ok ${name}`);
  } finally {
    for (const id of intervals.splice(0)) clearInterval(id);
  }
}

await check("web-locks: a free lease is taken at once", async () => {
  const locks = new LockManager();
  const page = documentWith({locks, storage: new Storage()});
  const {result, ms} = await page.acquire();
  assert.equal(result, true);
  assert.ok(ms < HANDOFF_MS / 2, `took ${ms} ms`);
  assert.equal(page.context.__gaiusRuntimeLeaseHeld, true);
  assert.equal(page.context.bootTimings.runtimeLeaseWaitStart, undefined);
  page.pagehide(false);
});

for (const [label, storage] of [
  ["missing localStorage", null],
  ["throwing localStorage getter", Object.defineProperty({}, "getItem", {get() { throw new Error("SecurityError"); }})],
  ["throwing localStorage writes", {
    getItem() { return null; },
    setItem() { throw new Error("SecurityError"); },
    removeItem() {},
  }],
]) {
  await check(`localStorage: ${label} fails closed with a retryable status`, async () => {
    const page = documentWith({locks: null, storage});
    const {result} = await page.acquire();
    assert.equal(result, false, "startup must not proceed without an exclusive lease");
    assert.notEqual(page.context.__gaiusRuntimeLeaseHeld, true);
    assert.ok(page.context.statuses.some(status =>
      status.kind === "error" && /localStorage.*unavailable|runtime lease.*retry/i.test(status.message)),
    "show a clear error explaining that storage must be enabled and startup retried");
  });
}

await check("web-locks: a lease released late by the replaced document is handed over", async () => {
  const locks = new LockManager();
  const releaseOld = locks.hold();
  setTimeout(releaseOld, HANDOFF_MS / 2);
  const page = documentWith({locks, storage: new Storage()});
  const {result, ms} = await page.acquire();
  assert.equal(result, true, "a same-tab reload must not report another tab");
  assert.ok(ms >= HANDOFF_MS / 2 - 20, `took ${ms} ms`);
  assert.equal(typeof page.context.bootTimings.runtimeLeaseWaitStart, "number");
  page.pagehide(false);
});

await check("web-locks: a lease a live tab keeps is reported as another tab", async () => {
  const locks = new LockManager();
  const live = documentWith({locks, storage: new Storage()});
  assert.equal((await live.acquire()).result, true);
  const second = documentWith({locks, storage: new Storage()});
  const {result, ms} = await second.acquire();
  assert.equal(result, false, "the multi-tab guard must hold");
  assert.ok(ms >= HANDOFF_MS - 20, `gave up after ${ms} ms`);
  assert.equal(locks.queue.length, 0, "the aborted request must leave the queue");
  assert.notEqual(second.context.__gaiusRuntimeLeaseHeld, true);
  // The live tab still holds it: a third page is refused as well.
  assert.equal((await documentWith({locks, storage: new Storage()}).acquire()).result, false);
  live.pagehide(false);
});

await check("web-locks: allowMultipleTabs cannot bypass a live lease", async () => {
  const locks = new LockManager();
  const live = documentWith({locks, storage: new Storage()});
  assert.equal((await live.acquire()).result, true);
  const second = documentWith({locks, storage: new Storage(), search: "allowMultipleTabs=1"});
  assert.equal((await second.acquire()).result, false);
  live.pagehide(false);
});

await check("web-locks: bfcache releases the lease and restoration reloads", async () => {
  const locks = new LockManager();
  const page = documentWith({locks, storage: new Storage()});
  assert.equal((await page.acquire()).result, true);
  page.pagehide(true);
  await sleep(10);
  assert.equal(locks.holder, null, "a cached page must not keep a frozen lock");
  page.dispatch("pageshow", {persisted: true});
  assert.equal(page.context.location.reloads, 1);
  page.pagehide(false);
  await sleep(10);
  assert.equal(locks.holder, null, "pagehide must release the lease");
  assert.equal(page.context.__gaiusRuntimeLeaseHeld, false);
  const next = documentWith({locks, storage: new Storage()});
  const {result, ms} = await next.acquire();
  assert.equal(result, true);
  assert.ok(ms < HANDOFF_MS / 2, `took ${ms} ms`);
  next.pagehide(false);
});

await check("localStorage: a free lease is taken, a released one is handed over", async () => {
  const storage = new Storage();
  const first = documentWith({locks: null, storage});
  assert.equal((await first.acquire()).result, true);
  assert.ok(storage.getItem("gaius.runtimeLease.v1"));
  setTimeout(() => first.pagehide(false), HANDOFF_MS / 2);
  const second = documentWith({locks: null, storage});
  const {result, ms} = await second.acquire();
  assert.equal(result, true);
  assert.ok(ms >= HANDOFF_MS / 2 - 20, `took ${ms} ms`);
  second.pagehide(false);
  assert.equal(storage.getItem("gaius.runtimeLease.v1"), null,
    "title page replacement must release without a saving marker");
});

await check("localStorage: a world replacement owns the saving lease until storage closes", async () => {
  const storage = new Storage();
  const page = documentWith({locks: null, storage});
  const listeners = new Set();
  const worker = {
    __gaiusWorldId: "world",
    addEventListener(type, listener) { if (type === "message") listeners.add(listener); },
    removeEventListener(type, listener) { listeners.delete(listener); },
    postMessage(message) {
      if (message.type !== "stop") return;
      setTimeout(() => {
        worker.__gaiusStorageClosed = true;
        for (const listener of [...listeners]) listener({data: {type: "storage-closed"}});
        for (const listener of [...listeners]) listener({data: {type: "stopped"}});
      }, 20);
    },
  };
  page.context.__gaiusSingleplayerWorkers = new Map([["world", worker]]);
  assert.equal((await page.acquire()).result, true);
  page.pagehide(false);
  const saving = JSON.parse(storage.getItem("gaius.runtimeLease.v1"));
  assert.equal(saving.saving, true);
  await waitUntil(() => storage.getItem("gaius.runtimeLease.v1") === null, 500);
});

await check("localStorage: Worker heartbeat holds a saving lease until completion", async () => {
  const storage = new Storage();
  const channelHub = new BroadcastHub();
  const page = documentWith({locks: null, storage, channelHub});
  const listeners = new Set();
  let workerChannel;
  let heartbeat;
  const worker = {
    __gaiusWorldId: "world",
    addEventListener(type, listener) { if (type === "message") listeners.add(listener); },
    removeEventListener(type, listener) { listeners.delete(listener); },
    postMessage(message) {
      if (message.type === "runtime-lease-save") {
        workerChannel = new FakeBroadcastChannel(message.channel, channelHub);
        const sendHeartbeat = () => workerChannel.postMessage({
          type: "saving-heartbeat", id: message.leaseId, workerId: message.workerId, at: Date.now()
        });
        sendHeartbeat();
        heartbeat = setInterval(sendHeartbeat, 80);
        intervals.push(heartbeat);
        return;
      }
      if (message.type !== "stop") return;
      setTimeout(() => {
        clearInterval(heartbeat);
        workerChannel?.postMessage({type: "saving-worker-complete", id: worker.leaseId, workerId: "world"});
        workerChannel?.close();
        for (const listener of [...listeners]) listener({data: {type: "storage-closed"}});
        for (const listener of [...listeners]) listener({data: {type: "stopped"}});
      }, 220);
    },
  };
  page.context.__gaiusSingleplayerWorkers = new Map([["world", worker]]);
  assert.equal((await page.acquire()).result, true);
  page.pagehide(false);
  const leaseId = JSON.parse(storage.getItem("gaius.runtimeLease.v1")).id;
  worker.leaseId = leaseId;
  const successor = documentWith({locks: null, storage, channelHub});
  let settled = false;
  const pending = successor.acquire().then(result => { settled = true; return result; });
  await sleep(140);
  assert.equal(settled, false, "successor must wait while the Worker heartbeat is active");
  const result = await pending;
  assert.equal(result.result, true, "successor must acquire after storage close");
  successor.pagehide(false);
});

await check("localStorage: all Worker completions are required before handoff", async () => {
  const storage = new Storage();
  const channelHub = new BroadcastHub();
  const page = documentWith({locks: null, storage, channelHub});
  const workers = new Map([["world-a", {__gaiusWorldId: "world-a", postMessage() {}}],
    ["world-b", {__gaiusWorldId: "world-b", postMessage() {}}]]);
  page.context.__gaiusSingleplayerWorkers = workers;
  assert.equal((await page.acquire()).result, true);
  const leaseId = JSON.parse(storage.getItem("gaius.runtimeLease.v1")).id;
  page.pagehide(false);
  const workerChannel = new FakeBroadcastChannel("gaius.runtimeLease.v1", channelHub);
  workerChannel.postMessage({type: "saving-worker-complete", id: leaseId, workerId: "world-a"});
  const successor = documentWith({locks: null, storage, channelHub});
  const pending = successor.acquire();
  await sleep(140);
  assert.equal(storage.getItem("gaius.runtimeLease.v1") !== null, true,
    "the first Worker must not release a multi-Worker save");
  assert.equal((await Promise.race([pending.then(() => true), sleep(20).then(() => false)])), false,
    "successor must wait for every Worker");
  workerChannel.postMessage({type: "saving-worker-complete", id: leaseId, workerId: "world-b"});
  await sleep(20);
  workerChannel.postMessage({type: "saving-complete", id: leaseId});
  assert.equal((await pending).result, true);
  workerChannel.close();
  successor.pagehide(false);
});

await check("localStorage: an active frozen-launcher Worker without world metadata still saves", async () => {
  const storage = new Storage();
  const page = documentWith({locks: null, storage});
  const listeners = new Set();
  const worker = {
    // T22's generated wrapper has no __gaiusWorldId; registry membership is the signal.
    addEventListener(type, listener) { if (type === "message") listeners.add(listener); },
    removeEventListener(type, listener) { listeners.delete(listener); },
    postMessage(message) {
      if (message.type !== "stop") return;
      setTimeout(() => {
        worker.__gaiusStorageClosed = true;
        for (const listener of [...listeners]) listener({data: {type: "storage-closed"}});
        for (const listener of [...listeners]) listener({data: {type: "stopped"}});
      }, 20);
    },
  };
  page.context.__gaiusSingleplayerWorkers = new Map([["world", worker]]);
  assert.equal((await page.acquire()).result, true);
  page.pagehide(false);
  assert.equal(JSON.parse(storage.getItem("gaius.runtimeLease.v1")).saving, true);
  await waitUntil(() => storage.getItem("gaius.runtimeLease.v1") === null, 500);
});

await check("localStorage: a lease left behind turns stale and is taken over", async () => {
  const storage = new Storage();
  storage.setItem("gaius.runtimeLease.v1", JSON.stringify({id: "gone", at: Date.now()}));
  const page = documentWith({locks: null, storage});
  const {result, ms} = await page.acquire();
  assert.equal(result, true);
  assert.ok(ms >= STALE_MS - 50 && ms < STALE_MS + HANDOFF_MS + 500, `took ${ms} ms`);
  page.pagehide(false);
});

await check("localStorage: a lease a live tab renews is reported as another tab", async () => {
  const storage = new Storage();
  const renew = () => storage.setItem("gaius.runtimeLease.v1", JSON.stringify({id: "live", at: Date.now()}));
  renew();
  intervals.push(setInterval(renew, 100));
  const page = documentWith({locks: null, storage});
  const {result, ms} = await page.acquire();
  assert.equal(result, false, "the multi-tab guard must hold");
  assert.ok(ms >= STALE_MS + HANDOFF_MS - 50, `gave up after ${ms} ms`);
  assert.ok(page.context.statuses.some((status) => status.kind === "waiting" &&
    /previous session/i.test(status.message)),
    "localStorage lease wait did not publish a visible waiting status");
  assert.ok(page.context.statuses.some((status) => status.kind === "error" &&
    /retry/i.test(status.message)),
    "localStorage lease timeout did not publish a retryable error status");
  assert.equal(JSON.parse(storage.getItem("gaius.runtimeLease.v1")).id, "live");
});

await check("web-locks: pagehide before an asynchronous grant cannot resurrect a claim", async () => {
  const locks = new LockManager();
  const page = documentWith({locks, storage: new Storage()});
  const pending = page.acquire();
  page.pagehide(false);
  assert.equal((await pending).result, false);
  await sleep(10);
  assert.equal(locks.holder, null);
  assert.notEqual(page.context.__gaiusRuntimeLeaseHeld, true);
});

await check("web-locks: pagehide aborts a pending handoff without releasing the live tab", async () => {
  const locks = new LockManager();
  const live = documentWith({locks, storage: new Storage()});
  assert.equal((await live.acquire()).result, true);
  const second = documentWith({locks, storage: new Storage()});
  const pending = second.acquire();
  await sleep(20);
  second.pagehide(false);
  assert.equal((await pending).result, false);
  assert.equal(locks.queue.length, 0);
  assert.equal(live.context.__gaiusRuntimeLeaseHeld, true);
  live.pagehide(false);
});

await check("localStorage: pagehide during claim confirmation cannot resurrect a lease", async () => {
  const storage = new Storage();
  const page = documentWith({locks: null, storage});
  const pending = page.acquire();
  page.pagehide(false);
  assert.equal((await pending).result, false);
  await waitUntil(() => storage.getItem("gaius.runtimeLease.v1") === null, STOP_MS + 200);
  assert.equal(storage.getItem("gaius.runtimeLease.v1"), null);
  assert.notEqual(page.context.__gaiusRuntimeLeaseHeld, true);
});

await check("localStorage: the actual heartbeat protects a live tab past the stale period", async () => {
  const storage = new Storage();
  const live = documentWith({locks: null, storage});
  assert.equal((await live.acquire()).result, true);
  const second = documentWith({locks: null, storage});
  assert.equal((await second.acquire()).result, false);
  assert.equal(live.context.__gaiusRuntimeLeaseHeld, true);
  live.pagehide(false);
});

await check("localStorage: concurrent claims admit only one runtime", async () => {
  const storage = new Storage();
  const pages = Array.from({length: 3}, () => documentWith({locks: null, storage}));
  const results = await Promise.all(pages.map(page => page.acquire()));
  assert.equal(results.filter(result => result.result).length, 1);
  for (const page of pages) page.pagehide(false);
  await waitUntil(() => storage.getItem("gaius.runtimeLease.v1") === null, STOP_MS + 200);
});

for (const notification of ["storage", "heartbeat"]) {
  await check(`localStorage: ${notification} stops an obsolete owner from overwriting its successor`, async () => {
    const storage = new Storage();
    const page = documentWith({locks: null, storage});
    assert.equal((await page.acquire()).result, true);
    const successor = JSON.stringify({id: "new-owner", at: Date.now()});
    storage.setItem("gaius.runtimeLease.v1", successor);
    if (notification === "storage") page.dispatch("storage", {key: "gaius.runtimeLease.v1"});
    else await sleep(120);
    assert.equal(storage.getItem("gaius.runtimeLease.v1"), successor);
    assert.equal(page.context.__gaiusRuntimeLeaseHeld, false);
    await sleep(10);
    assert.equal(page.context.location.reloads, 1);
    page.dispatch("unload");
    assert.equal(storage.getItem("gaius.runtimeLease.v1"), successor);
  });
}

await check("localStorage: failed lease cleanup still reloads an unprotected runtime", async () => {
  const storage = new Storage();
  const page = documentWith({locks: null, storage});
  assert.equal((await page.acquire()).result, true);
  storage.setItem = () => { throw new Error("storage revoked"); };
  storage.removeItem = () => { throw new Error("storage revoked"); };
  await sleep(120);
  assert.equal(page.context.__gaiusRuntimeLeaseHeld, false);
  assert.equal(page.context.location.reloads, 1);
});

// A fake singleplayer Worker: answers a stop with a storage flush and "stopped" after
// stopDelay ms (never when stopDelay is null).
function fakeWorker(events, stopDelay) {
  const listeners = new Set();
  const post = data => { for (const listener of [...listeners]) listener({data}); };
  return {
    __gaiusWorldId: "world",
    stops: 0,
    addEventListener(type, listener) { if (type === "message") listeners.add(listener); },
    removeEventListener(type, listener) { listeners.delete(listener); },
    postMessage(message) {
      if (!message) return;
      if (message.type === "save-all") {
        events.push("save-all");
        return;
      }
      if (message.type !== "stop") return;
      this.stops++;
      events.push("stop");
      if (this.kernelClosed) return;
      if (stopDelay === null) return;
      setTimeout(() => {
        post({type: "storage-flushing"});
        events.push("world-saved");
        this.__gaiusStorageClosed = true;
        post({type: "stopped"});
        events.push("stopped");
        if (this.resolveStorageRefresh) {
          setTimeout(() => {
            events.push("refreshed");
            this.resolveStorageRefresh();
          }, 5);
        }
      }, stopDelay);
    },
  };
}

await check("pagehide saves before stopping and releasing the lease", async () => {
  const storage = new Storage();
  const page = documentWith({locks: null, storage});
  const events = page.context.events;
  const worker = fakeWorker(events, 0);
  worker.__gaiusStorageRefresh = new Promise(resolve => { worker.resolveStorageRefresh = resolve; });
  page.context.__gaiusFsFlush = () => new Promise(resolve => {
    setTimeout(() => { events.push("flushed"); resolve(); }, 5);
  });
  page.context.__gaiusSingleplayerWorkers = new Map([["pagehide", worker]]);
  assert.equal((await page.acquire()).result, true);
  page.pagehide(false);
  const deferred = JSON.parse(storage.getItem("gaius.runtimeLease.v1"));
  assert.equal(deferred.saving, true);
  await waitUntil(() => storage.getItem("gaius.runtimeLease.v1") === null, STOP_MS + 200);
  assert.equal(worker.stops, 1);
  assert.equal(storage.getItem("gaius.runtimeLease.v1"), null);
  assert.deepEqual(events.slice(0, 3), ["save-all", "stop", "world-saved"]);
});

await check("pagehide tears kernels down after the server stop saves", async () => {
  const storage = new Storage();
  const page = documentWith({locks: null, storage});
  const events = page.context.events;
  const worker = fakeWorker(events, 0);
  worker.kernelClosed = false;
  page.context.__gaiusTerminateKernels = () => {
    worker.kernelClosed = true;
    events.push("kernel-teardown");
  };
  page.context.__gaiusSingleplayerWorkers = new Map([["kernel-race", worker]]);
  page.context.__gaiusFsFlush = () => Promise.resolve();
  assert.equal((await page.acquire()).result, true);
  page.pagehide(false);
  await sleep(30);
  assert.equal(worker.stops, 1);
  assert.equal(worker.kernelClosed, true, "pagehide left the kernel runtime alive after storage close");
  assert.deepEqual(events.slice(0, 5), ["save-all", "stop", "world-saved", "stopped", "kernel-teardown"]);
});

for (const [label, loseLease] of [
  ["a successor's claim", async (page, storage) => {
    storage.setItem("gaius.runtimeLease.v1", JSON.stringify({id: "new-owner", at: Date.now()}));
    page.dispatch("storage", {key: "gaius.runtimeLease.v1"});
  }],
  ["an unwritable lease", async (page, storage) => {
    storage.setItem = () => { throw new Error("quota exceeded"); };
  }],
]) {
  await check(`localStorage: ${label} saves the world and drains storage before the reload`, async () => {
    const storage = new Storage();
    const page = documentWith({locks: null, storage});
    const events = page.context.events;
    const worker = fakeWorker(events, 150);
    page.context.__gaiusSingleplayerWorkers = new Map([["session-1", worker]]);
    page.context.__gaiusFsFlush = () => new Promise(done => setTimeout(() => {
      events.push("flushed");
      done();
    }, 30));
    assert.equal((await page.acquire()).result, true);
    await loseLease(page, storage);
    await sleep(120);
    assert.equal(page.context.location.reloads, 0, "no reload while the world is still saving");
    assert.equal(worker.stops, 1);
    await sleep(250);
    const expected = label === "an unwritable lease"
      ? ["release", "stop", "flushed", "world-saved", "stopped", "reload"]
      : ["stop", "flushed", "world-saved", "stopped", "reload"];
    assert.deepEqual(events, expected);
    assert.equal(page.context.__gaiusRuntimeLeaseHeld, false);
  });
}

await check("localStorage: a Worker that never stops is given up after the stop wait", async () => {
  const storage = new Storage();
  const page = documentWith({locks: null, storage});
  const events = page.context.events;
  const silent = fakeWorker(events, null);
  const stopped = Object.assign(fakeWorker(events, 0), {__gaiusStopped: true, __gaiusStorageClosed: true});
  page.context.__gaiusSingleplayerWorkers = new Map([["a", silent], ["b", stopped]]);
  assert.equal((await page.acquire()).result, true);
  storage.setItem("gaius.runtimeLease.v1", JSON.stringify({id: "new-owner", at: Date.now()}));
  page.dispatch("storage", {key: "gaius.runtimeLease.v1"});
  page.dispatch("storage", {key: "gaius.runtimeLease.v1"});
  await sleep(STOP_MS - 100);
  assert.equal(page.context.location.reloads, 0);
  await sleep(250);
  assert.deepEqual(events, ["stop", "reload"], "one stop request, none to a stopped Worker, one reload");
});

await check("localStorage: mixed terminal and live Workers are all awaited", async () => {
  const storage = new Storage();
  const page = documentWith({locks: null, storage});
  const events = page.context.events;
  const terminalUnclosed = Object.assign(fakeWorker(events, 0), {
    __gaiusTerminal: true, __gaiusStorageClosed: false
  });
  const live = fakeWorker(events, 20);
  page.context.__gaiusSingleplayerWorkers = new Map([['terminal', terminalUnclosed], ['live', live]]);
  page.context.__gaiusFsFlush = () => Promise.resolve();
  assert.equal((await page.acquire()).result, true);
  storage.setItem('gaius.runtimeLease.v1', JSON.stringify({id: 'new-owner', at: Date.now()}));
  page.dispatch('storage', {key: 'gaius.runtimeLease.v1'});
  await sleep(100);
  assert.equal(live.stops, 1);
  assert.ok(page.context.location.reloads <= 1, 'mixed worker set must trigger at most one reload');
});

// Liveness locks: each document holds "gaius-runtime-alive:<lease id>" and the browser drops it
// when the document dies (tab closed, renderer killed). Locks are per name and per document here.
class NamedLocks {
  constructor() { this.held = new Map(); }
  forDocument(documentId) {
    const held = this.held;
    return {
      request(name, options, callback) {
        if (held.has(name)) return Promise.resolve().then(() => callback(null));
        held.set(name, documentId);
        return Promise.resolve().then(() => callback({name, mode: "exclusive"}))
          .finally(() => { if (held.get(name) === documentId) held.delete(name); });
      },
      query() {
        return Promise.resolve({held: [...held].map(([name, clientId]) => ({name, clientId, mode: "exclusive"})),
          pending: []});
      },
    };
  }
  kill(documentId) {
    for (const [name, owner] of [...this.held]) if (owner === documentId) this.held.delete(name);
  }
}

for (const saving of [false, true]) {
  await check(`localStorage: a ${saving ? "saving" : "running"} lease of a closed tab is taken over at once`, async () => {
    const storage = new Storage();
    const locks = new NamedLocks();
    const first = documentWith({locks: locks.forDocument("first"), storage});
    assert.equal((await first.acquire()).result, true);
    const lease = JSON.parse(storage.getItem("gaius.runtimeLease.v1"));
    assert.match(String(lease.alive || ""), /^gaius-runtime-alive:/, "the lease must name its liveness lock");
    if (saving) {
      // pagehide of a world page marks the lease as saving for the bounded stop window.
      first.context.__gaiusSingleplayerWorkers = new Map([["world", {__gaiusWorldId: "world", postMessage() {}}]]);
      first.pagehide(false);
      assert.equal(JSON.parse(storage.getItem("gaius.runtimeLease.v1")).saving, true);
    }
    for (const id of intervals.splice(0)) clearInterval(id);
    locks.kill("first");
    const second = documentWith({locks: locks.forDocument("second"), storage});
    const {result, ms} = await second.acquire();
    assert.equal(result, true);
    assert.ok(ms < Math.min(STALE_MS, STOP_MS) / 2, `waited ${ms} ms for a closed tab`);
    second.pagehide(false);
  });
}

await check("localStorage: a live tab with a liveness lock is still refused", async () => {
  const storage = new Storage();
  const locks = new NamedLocks();
  const live = documentWith({locks: locks.forDocument("live"), storage});
  assert.equal((await live.acquire()).result, true);
  const second = documentWith({locks: locks.forDocument("second"), storage});
  const {result} = await second.acquire();
  assert.equal(result, false, "the multi-tab guard must hold while the owner lives");
  live.pagehide(false);
});

await check("localStorage: a live tab that is saving is still waited for", async () => {
  const storage = new Storage();
  const locks = new NamedLocks();
  const page = documentWith({locks: locks.forDocument("page"), storage});
  page.context.__gaiusSingleplayerWorkers = new Map([["world", {__gaiusWorldId: "world", postMessage() {}}]]);
  assert.equal((await page.acquire()).result, true);
  page.pagehide(false);
  const successor = documentWith({locks: locks.forDocument("successor"), storage});
  const {result, ms} = await successor.acquire();
  assert.equal(result, true);
  assert.ok(ms >= STOP_MS - 50, `a live saving owner was cut short after ${ms} ms`);
  successor.pagehide(false);
});

let warmReplacements = 0;
for (const backend of ["web-locks", "localStorage"]) {
  await check(`${backend}: 70 warm replacements have no false denials`, async () => {
    const locks = backend === "web-locks" ? new LockManager() : null;
    const storage = new Storage();
    for (let index = 0; index < 70; index++) {
      const page = documentWith({locks, storage});
      assert.equal((await page.acquire()).result, true, `replacement ${index}`);
      page.pagehide(index % 7 === 0);
      if (locks) await sleep(50);
      else await waitUntil(() => storage.getItem("gaius.runtimeLease.v1") === null, STOP_MS + 200);
      if (locks) assert.equal(locks.holder, null);
      else assert.equal(storage.getItem("gaius.runtimeLease.v1"), null);
      warmReplacements++;
    }
  });
}

console.log(`RUNTIME_LEASE_SMOKE_OK ${cases.length} cases; ${warmReplacements} warm replacements, 0 false denials`);
