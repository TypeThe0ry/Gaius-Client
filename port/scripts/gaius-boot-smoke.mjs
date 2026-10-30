#!/usr/bin/env node
// Launcher boot script (port/web/boot/gaius-boot.js) in a node:vm page with a fake DOM.
//
//   node port/scripts/gaius-boot-smoke.mjs
//
// Checks the site asset map (hashed client, hotpath, singleplayer URLs and the client preload),
// ordered module loading, the sound-pack merge into the decoded vanilla pack, the kernel port
// handed to the integrated server Worker with its scripts and switches, the kernel switch-off
// fallback and the dev, portable and file modes; the WasmGC capability gate, the file:// payload
// scripts and ?serverRuntime=wasm.

import {RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../web/boot/gaius-boot.js", import.meta.url), "utf8");
const launcherSource = await readFile(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");

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

const SITE = {
  id: "0011223344556677",
  profile: RENDERPEARL_PROFILE.id,
  assets: {
    "singleplayer-server-worker.js": "singleplayer-server-worker.eeeeeeeeeeeeeeee.js",
    "gaius-hotpath.wasm": "gaius-hotpath.ffffffffffffffff.wasm",
    "vanilla-assets.pack.gz": "vanilla-assets.pack.0000000000000000.gz",
    "kernels/kernel-policy.js": "kernels/kernel-policy.1111111111111111.js",
    "kernels/kernel-runtime.js": "kernels/kernel-runtime.2222222222222222.js",
    "kernels/mesh-job.js": "kernels/mesh-job.3333333333333333.js",
    "runtime/quality/gpu-caps.js": "runtime/quality/gpu-caps.4444444444444444.js",
    "classes.wasm": "classes.aaaaaaaaaaaaaaab.wasm",
    "gaius-wasm-boot.js": "gaius-wasm-boot.aaaaaaaaaaaaaaad.js",
    "gaius-wasm-gc-runtime.js": "gaius-wasm-gc-runtime.aaaaaaaaaaaaaaae.js",
    "gaius-jspi.js": "gaius-jspi.aaaaaaaaaaaaaaaf.js",
    "gaius-shader-toolchain.js": "gaius-shader-toolchain.aaaaaaaaaaaaaaba.js",
    "gaius-shaderc.js": "gaius-shaderc.aaaaaaaaaaaaaabb.js",
    "gaius-shaderc.wasm": "gaius-shaderc.aaaaaaaaaaaaaabc.wasm",
    "gaius-spvc.js": "gaius-spvc.aaaaaaaaaaaaaabd.js",
    "gaius-spvc.wasm": "gaius-spvc.aaaaaaaaaaaaaabe.wasm",
    "singleplayer-server.wasm": "singleplayer-server.bbbbbbbbbbbbbbb1.wasm",
    "gaius-wasm-worker.js": "gaius-wasm-worker.bbbbbbbbbbbbbbb2.js",
  },
  modules: ["kernels/mesh-job.js"],
  kernels: {},
  serviceWorker: "gaius-sw.js",
};

const SOUND_SITE = Object.assign({}, SITE, {
  assets: Object.assign({}, SITE.assets, {"vanilla-sounds.pack": "vanilla-sounds.aaaaaaaaaaaaaaaa.pack"}),
});

function pack(entries) {
  const encoder = new TextEncoder();
  const index = {};
  const bodies = [];
  let offset = 0;
  for (const [name, text] of entries) {
    const body = encoder.encode(text);
    index[name] = [offset, body.length];
    offset += body.length;
    bodies.push(body);
  }
  const indexBytes = encoder.encode(JSON.stringify(index));
  const out = new Uint8Array(12 + indexBytes.length + offset);
  out.set(encoder.encode("GAIUSVP1"), 0);
  new DataView(out.buffer).setUint32(8, indexBytes.length, true);
  out.set(indexBytes, 12);
  let at = 12 + indexBytes.length;
  for (const body of bodies) {
    out.set(body, at);
    at += body.length;
  }
  return {bytes: out, index, dataOffset: 12 + indexBytes.length};
}

function read(root, name) {
  const [offset, length] = root.index[name];
  const start = root.dataOffset + offset;
  return new TextDecoder().decode(root.bytes.subarray(start, start + length));
}

const checks = [];
const check = (name, fn) => checks.push([name, fn]);

check("site mode maps logical Wasm names and starts the Wasm client", async () => {
  const page = createPage({site: SITE});
  const boot = page.window.__gaiusBoot;
  assert.equal(boot.assetUrl("classes.js") ?? null, null, "the release has no JavaScript client asset");
  assert.equal(boot.assetUrl("missing.js") ?? null, null);
  assert.equal(page.window.__gaiusClassesUrl, "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/gaius-wasm-boot.aaaaaaaaaaaaaaad.js");
  assert.equal(page.window.__gaiusHotpathWasmUrl, "https://example.invalid/Gaius/26.3/gaius-hotpath.ffffffffffffffff.wasm");
  assert.equal(page.appended.some((node) => node.tagName === "link" && /classes\.js/.test(node.href || "")), false);
  // The launcher's generated block set dev URLs; applyAssetUrls replaces them afterwards.
  assert.equal(boot.applyAssetUrls(), true);
  assert.equal(page.window.__gaiusSingleplayerServerWasmUrl, "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/singleplayer-server.bbbbbbbbbbbbbbb1.wasm");
  assert.equal(page.window.__gaiusSingleplayerWorkerUrl, "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/singleplayer-server-worker.eeeeeeeeeeeeeeee.js");
});

check("modules load in order with async=false; missing ones never block", async () => {
  const page = createPage({site: SITE});
  const scripts = page.appended.filter((node) => node.tagName === "script");
  assert.deepEqual(scripts.slice(0, 4).map((node) => node.src), [
    "kernels/kernel-mirror.js",
    "kernels/kernel-policy.1111111111111111.js",
    "kernels/kernel-runtime.2222222222222222.js",
    "kernels/mesh-job.3333333333333333.js",
  ]);
  assert.ok(scripts.every((node) => node.async === false));
  assert.equal(scripts[4].src, "runtime/quality/gpu-caps.4444444444444444.js");
  assert.equal(scripts[5].src, "runtime/quality/quality-profile.js", "unmapped quality files fall back to their name");
  for (const node of scripts) (node.src.includes("quality-profile") ? node.onerror : node.onload)();
  let tiers = 0;
  page.window.GaiusQuality = {caps: {ensureTier: () => { tiers++; }}};
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(tiers, 1, "beforeMain picks the quality tier");
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "module-missing"));
});

check("document graphics teardown suppresses only its intentional context-loss reload", async () => {
  const page = createPage();
  let loseCalls = 0;
  const gl = {
    getExtension(name) {
      assert.equal(name, "WEBGL_lose_context");
      return {loseContext() {
        assert.equal(page.window.__gaiusDisableGpuContextAutoReload, true,
          "the intentional context-loss event could schedule a reload before navigation");
        loseCalls++;
      }};
    },
  };
  const cleared = new Map();
  page.window.__gaiusGlfwContext = {gl};
  page.window.__gaiusGL = {cache: cleared};

  assert.equal(page.window.__gaiusTerminateGraphics("pagehide"), true);
  assert.equal(page.window.__gaiusDisableGpuContextAutoReload, true,
    "intentional teardown context loss can race navigation with a GPU recovery reload");
  assert.equal(loseCalls, 1, "document teardown did not release its WebGL context");
  assert.equal(page.window.__gaiusGraphicsTerminated, true);
  assert.equal(page.window.__gaiusGL, null);
  assert.equal(page.window.__gaiusGlfwContext, null);
  assert.equal(page.window.__gaiusWebGL, null);
  assert.equal(cleared.size, 0);
  assert.equal(page.window.__gaiusTerminateGraphics("pagehide"), false,
    "graphics teardown ran more than once");
  assert.equal(loseCalls, 1);
});

check("the sound pack merges into the decoded core pack in one step", async () => {
  const page = createPage({site: SITE});
  const core = pack([["assets/minecraft/textures/block/stone.png", "stone"], ["assets/minecraft/sounds/random/click.ogg", "click"]]);
  const root = {bytes: core.bytes, index: Object.assign({}, core.index), dataOffset: core.dataOffset, resourceCount: 2};
  const sounds = pack([["assets/minecraft/sounds/mob/cow/say1.ogg", "moo"], ["assets/minecraft/sounds/music/menu/menu1.ogg", "music"]]);
  const added = page.window.__gaiusBoot.mergeSoundPack(root, sounds.bytes);
  assert.equal(added, 2);
  assert.equal(root.resourceCount, 4);
  assert.equal(read(root, "assets/minecraft/textures/block/stone.png"), "stone");
  assert.equal(read(root, "assets/minecraft/sounds/random/click.ogg"), "click");
  assert.equal(read(root, "assets/minecraft/sounds/mob/cow/say1.ogg"), "moo");
  assert.equal(read(root, "assets/minecraft/sounds/music/menu/menu1.ogg"), "music");
  assert.throws(() => page.window.__gaiusBoot.mergeSoundPack(root, new Uint8Array(16)), /GAIUSVP1/);
});

check("sounds without a reload bridge fall back to eager loading instead of going silent", async () => {
  const core = pack([["assets/minecraft/sounds.json", "{}"]]);
  const root = {bytes: core.bytes, index: Object.assign({}, core.index), dataOffset: core.dataOffset, resourceCount: 1};
  const sounds = pack([["assets/minecraft/sounds/mob/cow/say1.ogg", "moo"]]);
  let release;
  const delayedSounds = new Promise((resolve) => { release = () => resolve(sounds.bytes); });
  const page = createPage({site: SOUND_SITE, search: "?gaiusSounds=deferred", globals: {
    __gaiusVanillaAssets: root,
    __gaiusVanillaAssetsReady: Promise.resolve(root),
    fetch: async () => ({ok: true, arrayBuffer: async () => delayedSounds}),
  }});
  for (const node of page.appended) if (node.onload) node.onload();
  assert.equal(page.window.__gaiusBoot.sounds().mode, "eager");
  let settled = false;
  const beforeMain = page.window.__gaiusBoot.beforeMain().then(() => { settled = true; });
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(settled, false, "eager sound loading must gate beforeMain until the pack is ready");
  release();
  await beforeMain;
  assert.equal(page.window.__gaiusBoot.sounds().merged, true);
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "sound-mode-fallback"));
});

check("deferred sounds call the provided SoundManager reload bridge once", async () => {
  const core = pack([["assets/minecraft/sounds.json", "{}"]]);
  const root = {bytes: core.bytes, index: Object.assign({}, core.index), dataOffset: core.dataOffset, resourceCount: 1};
  const sounds = pack([["assets/minecraft/sounds/mob/cow/say1.ogg", "moo"]]);
  let reloads = 0;
  const page = createPage({site: SOUND_SITE, search: "?gaiusSounds=deferred", globals: {
    __gaiusVanillaAssets: root,
    __gaiusVanillaAssetsReady: Promise.resolve(root),
    __gaiusSoundReloadHook: () => { reloads++; },
    fetch: async () => ({ok: true, arrayBuffer: async () => sounds.bytes}),
  }});
  page.window.__gaiusMinecraftState = {screen: "TitleScreen", running: true, overlay: null};
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(page.window.__gaiusBoot.sounds().mode, "deferred");
  assert.equal(page.window.__gaiusBoot.sounds().merged, true);
  assert.equal(reloads, 1);
});

check("deferred sound polling has a deadline and falls back to eager when title never appears", async () => {
  const core = pack([["assets/minecraft/sounds.json", "{}"]]);
  const root = {bytes: core.bytes, index: Object.assign({}, core.index), dataOffset: core.dataOffset, resourceCount: 1};
  const sounds = pack([["assets/minecraft/sounds/mob/cow/say1.ogg", "moo"]]);
  let fetches = 0;
  let release;
  const delayedSounds = new Promise((resolve) => { release = () => resolve(sounds.bytes); });
  const page = createPage({site: SOUND_SITE, search: "?gaiusSounds=deferred", globals: {
    __gaiusVanillaAssets: root,
    __gaiusVanillaAssetsReady: Promise.resolve(root),
    __gaiusSoundReloadHook: () => {},
    __gaiusSoundPollDeadlineMs: 30,
    fetch: async () => { fetches++; return {ok: true, arrayBuffer: async () => delayedSounds}; },
  }});
  await new Promise((resolve) => setTimeout(resolve, 70));
  assert.equal(page.window.__gaiusBoot.sounds().mode, "eager");
  assert.equal(fetches, 1, "deadline fallback must start one eager sound load");
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "sound-mode-fallback"
    && entry.detail === "title-timeout"));
  release();
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(page.window.__gaiusBoot.sounds().merged, true);
});

check("sound title polling stops on page teardown", async () => {
  const core = pack([["assets/minecraft/sounds.json", "{}"]]);
  const root = {bytes: core.bytes, index: Object.assign({}, core.index), dataOffset: core.dataOffset, resourceCount: 1};
  let fetches = 0;
  const page = createPage({site: SOUND_SITE, search: "?gaiusSounds=deferred", globals: {
    __gaiusVanillaAssets: root,
    __gaiusVanillaAssetsReady: Promise.resolve(root),
    __gaiusSoundReloadHook: () => {},
    __gaiusSoundPollDeadlineMs: 30,
    fetch: async () => { fetches++; throw new Error("poll should have stopped"); },
  }});
  page.dispatch("pagehide");
  await new Promise((resolve) => setTimeout(resolve, 70));
  assert.equal(fetches, 0, "page teardown must cancel title polling before the deadline");
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "sound-title-poll-stopped"
    && entry.detail === "pagehide"));
});

check("the integrated server Worker gets a kernel port after its start message", async () => {
  const page = createPage({site: SITE});
  const worker = new page.FakeWorker();
  const channel = new MessageChannel();
  worker.postMessage({type: "start", sessionId: "a".repeat(32), port: channel.port2}, [channel.port2]);
  assert.equal(page.posted.length, 2);
  assert.equal(page.posted[1].message.type, "gaius-kernel-port");
  assert.equal(page.posted[1].transfer[0], page.posted[1].message.port);
  worker.postMessage({type: "start", sessionId: "a".repeat(32), port: channel.port2});
  assert.equal(page.posted.length, 3, "one port per Worker");
  worker.postMessage({type: "distances"});
  assert.equal(page.posted.length, 4, "other messages pass through untouched");
  page.posted[1].message.port.close();
  channel.port1.close();
});

check("switched-off kernels give no runtime and no server port", async () => {
  const page = createPage({site: SITE, search: "?gaiusKernels=0"});
  assert.equal(page.window.__gaiusKernels.enabled(), false);
  assert.equal(await page.window.__gaiusKernels.runtime(), null);
  const worker = new page.FakeWorker();
  worker.postMessage({type: "start", port: {}});
  assert.equal(page.posted.length, 1);
  const missing = createPage({site: SITE});
  for (const node of missing.appended) if (node.onerror) node.onerror();
  assert.equal(await missing.window.__gaiusKernels.runtime(), null, "runtime scripts that fail to load: vanilla path");
  assert.ok(missing.window.__gaiusBoot.events.some((entry) => entry.event === "kernels-unavailable"));
});

check("dev and portable pages keep their own URLs", async () => {
  const dev = createPage();
  assert.equal(dev.window.__gaiusBoot.applyAssetUrls(), false);
  assert.equal(dev.window.__gaiusClassesUrl, undefined);
  const portable = createPage({portable: true, protocol: "file:"});
  assert.equal(portable.window.__gaiusBoot.applyAssetUrls(), false);
  assert.equal(portable.appended.length, 0, "a portable page inlines its modules");
});

check("portable idle prewarm constructs the Worker from its embedded Blob URL", async () => {
  const clock = {now: 1000};
  const page = createPage({portable: true, protocol: "file:", clock,
    globals: {
      __gaiusPortableBuild: true,
      __gaiusPortableRuntimes: {wasm: {server: "singleplayer-server.wasm"}},
      __gaiusFilePayloads: Object.fromEntries([
        "singleplayer-server-worker.js", "singleplayer-server.wasm",
        "gaius-wasm-gc-runtime.js", "gaius-jspi.js", "gaius-wasm-worker.js",
      ].map((name) => [name, {script: name, gzip: false}])),
      __gaiusPortablePayloadBytes: Object.fromEntries([
        ["singleplayer-server-worker.js", new TextEncoder().encode("self.onmessage = null;")],
        ["singleplayer-server.wasm", new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0])],
        ["gaius-wasm-gc-runtime.js", new TextEncoder().encode("/* runtime */")],
        ["gaius-jspi.js", new TextEncoder().encode("/* jspi */")],
        ["gaius-wasm-worker.js", new TextEncoder().encode("/* worker */")],
      ]),
      __gaiusMinecraftState: {screen: "TitleScreen", running: true, overlay: null},
      requestIdleCallback: (callback) => callback(),
    },
  });
  page.runIntervals();
  clock.now = 4000;
  page.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(page.workers.length, 1, "portable idle path did not create a Worker");
  assert.match(String(page.workers[0].url), /^blob:/,
    "portable idle path did not pass the constructed Blob URL to Worker");
  assert.notEqual(String(page.workers[0].url), "undefined");
  page.window.__gaiusCancelIdleSingleplayerWorker("smoke");
});

function portableIdlePage(workerPayload) {
  const clock = {now: 1000};
  const page = createPage({portable: true, protocol: "file:", clock,
    globals: {
      __gaiusPortableBuild: true,
      __gaiusPortableRuntimes: {wasm: {server: "singleplayer-server.wasm"}},
      __gaiusFilePayloads: Object.fromEntries([
        "singleplayer-server-worker.js", "singleplayer-server.wasm",
        "gaius-wasm-gc-runtime.js", "gaius-jspi.js", "gaius-wasm-worker.js",
      ].map((name) => [name, {script: name, gzip: false}])),
      __gaiusPortablePayloadBytes: Object.fromEntries([
        ["singleplayer-server-worker.js", workerPayload],
        ["singleplayer-server.wasm", new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0])],
        ["gaius-wasm-gc-runtime.js", new TextEncoder().encode("/* runtime */")],
        ["gaius-jspi.js", new TextEncoder().encode("/* jspi */")],
        ["gaius-wasm-worker.js", new TextEncoder().encode("/* worker */")],
      ]),
      __gaiusMinecraftState: {screen: "TitleScreen", running: true, overlay: null},
      requestIdleCallback: (callback) => callback(),
    },
  });
  page.runIntervals();
  clock.now = 4000;
  page.runIntervals();
  return page;
}

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return {promise, resolve};
}

// The same ownership transfer BrowserSingleplayerClient performs when it consumes the idle Worker.
function adoptLikeStartPath(page) {
  const idle = page.window.__gaiusIdleSingleplayerWorker;
  assert.ok(idle && idle.__gaiusIdle === true && !idle.__gaiusTerminal && idle.__gaiusAdopted !== true,
    "no adoptable idle Worker");
  page.window.__gaiusIdleSingleplayerWorker = null;
  idle.__gaiusIdle = false;
  idle.__gaiusAdopted = true;
  if (idle.__gaiusIdleTimeout) {
    page.window.clearTimeout(idle.__gaiusIdleTimeout);
    idle.__gaiusIdleTimeout = 0;
  }
  return idle;
}

const bootstrapPosts = (page, worker) => page.posted.filter((entry) =>
  entry.worker === worker && entry.message && entry.message.type === "gaius-worker-bootstrap");

check("portable idle Worker adopted during payload decode is kept and bootstrapped once", async () => {
  const payload = deferred();
  const page = portableIdlePage(payload.promise);
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(page.workers.length, 1, "portable idle path did not create a Worker");
  // The start path takes the Worker over while the idle branch still awaits the payload.
  const adopted = adoptLikeStartPath(page);
  assert.ok(adopted.__gaiusBootstrapDelivery, "idle branch does not publish its bootstrap delivery");
  payload.resolve(new TextEncoder().encode("self.onmessage = null;"));
  assert.equal(await adopted.__gaiusBootstrapDelivery, true, "adopted Worker never got its bootstrap");
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.notEqual(adopted.terminated, true, "idle branch terminated the Worker the start path adopted");
  assert.notEqual(adopted.__gaiusTerminal, true);
  assert.equal(bootstrapPosts(page, adopted).length, 1, "bootstrap payload must be posted exactly once");
  assert.equal(page.window.__gaiusCancelIdleSingleplayerWorker("late"), false,
    "cancelling after adoption must not reach the adopted Worker");
  assert.notEqual(adopted.terminated, true);
  assert.equal(page.workers.length, 1, "start path must not need a second Worker");
});

check("portable idle Worker cancelled during payload decode is terminated without a bootstrap", async () => {
  const payload = deferred();
  const page = portableIdlePage(payload.promise);
  await new Promise((resolve) => setTimeout(resolve, 10));
  const idle = page.workers[0];
  assert.equal(page.window.__gaiusCancelIdleSingleplayerWorker("smoke"), true);
  payload.resolve(new TextEncoder().encode("self.onmessage = null;"));
  assert.equal(await idle.__gaiusBootstrapDelivery, false);
  assert.equal(idle.terminated, true);
  assert.equal(bootstrapPosts(page, idle).length, 0, "a cancelled Worker must not receive the payload");
});

function siteIdlePage(screen, {site = SITE, search = "", globals = {}} = {}) {
  const clock = {now: 1000};
  const state = {screen, running: true, overlay: null};
  const page = createPage({site, search, clock, globals: Object.assign({
    __gaiusMinecraftState: state,
    requestIdleCallback: () => { throw new Error("the world-selection warm-up must not wait for idle"); },
  }, globals)});
  page.window.__gaiusBoot.applyAssetUrls();
  return {page, state, clock};
}

const prewarmPosts = (page, worker) => page.posted.filter((entry) =>
  (!worker || entry.worker === worker) && entry.message && entry.message.type === "prewarm");

check("world-selection screens warm the server Worker at once and post a runtime prewarm", async () => {
  const {page} = siteIdlePage("net.minecraft.client.gui.screens.worldselection.CreateWorldScreen");
  // No title idle grace period: the first poll on Create World starts the warm-up.
  page.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 10));
  const servers = page.workers.filter((worker) => /singleplayer-server-worker/.test(String(worker.url)));
  assert.equal(servers.length, 1, "Create World did not create the idle server Worker");
  const idle = servers[0];
  assert.equal(page.window.__gaiusIdleSingleplayerWorker, idle);
  const posts = prewarmPosts(page, idle);
  assert.equal(posts.length, 1, "the idle Worker must get exactly one runtime prewarm");
  const message = posts[0].message;
  assert.match(String(message.sessionId), /^idle-/);
  assert.equal(message.port, undefined, "a prewarm must never transfer the client port");
  assert.equal(posts[0].transfer, undefined);
  // Further polls on the same screen keep that Worker instead of creating another one.
  page.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(page.workers.filter((worker) => /singleplayer-server-worker/.test(String(worker.url))).length, 1);
  assert.equal(prewarmPosts(page).length, 1);
  page.window.__gaiusCancelIdleSingleplayerWorker("smoke");
});

check("the world-selection prewarm parks vanilla Main; title idle and low memory do not", async () => {
  const {page: jsPage} = siteIdlePage("net.minecraft.client.gui.screens.worldselection.CreateWorldScreen");
  jsPage.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(prewarmPosts(jsPage)[0].message.parkMain, false,
    "the frozen JavaScript server must keep the compile-only prewarm");
  jsPage.window.__gaiusCancelIdleSingleplayerWorker("smoke");

  const {page} = siteIdlePage("net.minecraft.client.gui.screens.worldselection.CreateWorldScreen",
    {site: WASM_SERVER_SITE, search: "?runtime=wasm&serverRuntime=wasm", globals: {WebAssembly: fullWasm()}});
  page.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(prewarmPosts(page)[0].message.parkMain, true,
    "Create World must ask the idle Worker to run vanilla Main up to Bootstrap");
  page.window.__gaiusCancelIdleSingleplayerWorker("smoke");

  // A title-idle Worker only compiles; reaching the world list later parks its Main once.
  const title = siteIdlePage("TitleScreen", {
    site: WASM_SERVER_SITE,
    search: "?runtime=wasm&serverRuntime=wasm",
    globals: {WebAssembly: fullWasm()},
  });
  title.page.window.requestIdleCallback = (callback) => callback();
  title.page.runIntervals();
  title.clock.now = 5000;
  title.page.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 10));
  const idle = title.page.window.__gaiusIdleSingleplayerWorker;
  assert.ok(idle, "title idle did not create the idle Worker");
  assert.equal(prewarmPosts(title.page, idle)[0].message.parkMain, false,
    "the title screen alone must not run Main");
  title.state.screen = "net.minecraft.client.gui.screens.worldselection.SelectWorldScreen";
  title.page.runIntervals();
  title.page.runIntervals();
  const parks = title.page.posted.filter((entry) => entry.worker === idle &&
    entry.message && entry.message.type === "park-main");
  assert.equal(parks.length, 1, "the world list must park the idle Worker's Main exactly once");
  title.page.window.__gaiusCancelIdleSingleplayerWorker("smoke");

  for (const setup of [{memory: true}, {search: "?gaiusParkMain=0"}]) {
    const clock = {now: 1000};
    const low = createPage({site: SITE, clock, search: setup.search || "", globals: {
      __gaiusMinecraftState: {screen: "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen",
        running: true, overlay: null},
      __gaiusMemoryTier: setup.memory ? {lowMemory: true} : undefined,
    }});
    low.window.__gaiusBoot.applyAssetUrls();
    low.runIntervals();
    await new Promise((resolve) => setTimeout(resolve, 10));
    assert.equal(prewarmPosts(low)[0].message.parkMain, false,
      "low-memory pages and ?gaiusParkMain=0 keep the compile-only prewarm");
    low.window.__gaiusCancelIdleSingleplayerWorker("smoke");
  }
});

check("an adopted or launching Worker is not replaced while its world starts", async () => {
  const {page, state} = siteIdlePage("net.minecraft.client.gui.screens.worldselection.SelectWorldScreen");
  page.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 10));
  const adopted = adoptLikeStartPath(page);
  page.window.__gaiusSingleplayerWorkers = new Map([["session", adopted]]);
  page.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(page.workers.filter((worker) => /singleplayer-server-worker/.test(String(worker.url))).length, 1,
    "a second idle Worker raced the launch that adopted the first one");
  // Back on the world list after that world stopped: warm a fresh Worker for the next launch.
  adopted.__gaiusTerminal = true;
  state.screen = "net.minecraft.client.gui.screens.worldselection.SelectWorldScreen";
  page.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 10));
  const servers = page.workers.filter((worker) => /singleplayer-server-worker/.test(String(worker.url)));
  assert.equal(servers.length, 2, "returning to the world list did not warm a new Worker");
  assert.equal(prewarmPosts(page, servers[1]).length, 1);
  page.window.__gaiusCancelIdleSingleplayerWorker("smoke");
});

check("an idle Worker that fails to load is retried once per world-selection stay", async () => {
  const {page, state} = siteIdlePage("net.minecraft.client.gui.screens.worldselection.CreateWorldScreen");
  const servers = () => page.workers.filter((worker) => /singleplayer-server-worker/.test(String(worker.url)));
  const events = (name) => page.window.__gaiusBoot.events.filter((entry) => entry.event === name);
  const poll = async () => {
    page.runIntervals();
    await new Promise((resolve) => setTimeout(resolve, 10));
  };
  // A 404/CSP/offline Worker script reports through onerror; the poll replaces it once.
  await poll();
  assert.equal(servers().length, 1);
  servers()[0].onerror({message: "404"});
  await poll();
  assert.equal(servers().length, 2, "the first failure must be retried");
  servers()[1].onerror({message: "404"});
  for (let index = 0; index < 8; index++) await poll();
  assert.equal(servers().length, 2, "a Worker that keeps failing to load was recreated on every poll");
  assert.equal(page.window.__gaiusIdleSingleplayerWorker, null);
  assert.equal(events("singleplayer-worker-idle-failed").length, 2);
  assert.equal(events("singleplayer-worker-idle-suspended").length, 1);
  // Leaving the world-selection screens and coming back allows a fresh attempt.
  state.screen = "TitleScreen";
  await poll();
  state.screen = "net.minecraft.client.gui.screens.worldselection.SelectWorldScreen";
  await poll();
  assert.equal(servers().length, 3, "a new visit to the world list did not retry the warm-up");
  page.window.__gaiusCancelIdleSingleplayerWorker("smoke");
});

check("an open world never triggers the world-selection warm-up", async () => {
  const {page, state} = siteIdlePage("net.minecraft.client.gui.screens.worldselection.CreateWorldScreen");
  state.level = "net.minecraft.client.multiplayer.ClientLevel";
  page.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(page.workers.filter((worker) => /singleplayer-server-worker/.test(String(worker.url))).length, 0);
});

check("start path awaits the idle bootstrap delivery instead of re-sending it", async () => {
  const client = await readFile(new URL(
    "../src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java", import.meta.url), "utf8");
  const adoption = client.slice(client.indexOf("const idleWorker = globalThis.__gaiusIdleSingleplayerWorker"),
    client.indexOf("worker = new Worker(workerUrl, {name: 'Gaius Integrated Server'})"));
  assert.ok(adoption.length > 0, "client adoption block not found");
  assert.match(adoption, /worker\.__gaiusAdopted = true/, "client adoption does not mark ownership");
  assert.match(adoption, /idleBootstrapDelivery = worker\.__gaiusBootstrapDelivery/,
    "client adoption does not reuse the idle bootstrap delivery");
  assert.ok(adoption.indexOf("worker = idleWorker") < adoption.indexOf("loadFileWorkerFiles()"),
    "adoption must be decided before the file payload is decoded again");
  const deliver = client.slice(client.indexOf("const deliverFileWorkerFiles = function()"));
  assert.ok(deliver.indexOf("idleBootstrapDelivery") >= 0 &&
    deliver.indexOf("idleBootstrapDelivery") < deliver.indexOf("if (!fileWorkerFiles) return true;"),
    "file delivery must prefer the idle Worker's bootstrap delivery");
});

const WORLDGEN_SITE = Object.assign({}, SITE, {
  assets: Object.assign({}, SITE.assets, {
    "kernels/kernel-mirror.js": "kernels/kernel-mirror.0000000000000000.js",
    "kernels/worldgen-job.js": "kernels/worldgen-job.5555555555555555.js",
    "kernels/worldgen-kernel.js": "kernels/worldgen-kernel.6666666666666666.js",
    "kernels/kernel-worker.js": "kernels/kernel-worker.7777777777777777.js",
  }),
  kernels: {
    worldgen: {kinds: ["terrain"], variants: {simd: "kernels/worldgen.simd.8888888888888888.wasm", baseline: "kernels/worldgen.baseline.9999999999999999.wasm"}},
  },
});

// Values from the page's realm, compared structurally.
const plain = (value) => JSON.parse(JSON.stringify(value));

function startWorker(page) {
  const worker = new page.FakeWorker();
  const channel = new MessageChannel();
  worker.postMessage({type: "start", sessionId: "b".repeat(32), port: channel.port2}, [channel.port2]);
  return channel;
}

function closePorts(page, channel) {
  for (const entry of page.posted) if (entry.message.type === "gaius-kernel-port") entry.message.port.close();
  channel.port1.close();
}

check("the kernel port carries the server Worker's scripts and the page switches", async () => {
  const page = createPage({site: WORLDGEN_SITE, search: "?worldgenKernel=off&lightkernel=0"});
  page.window.__gaiusKernelSemantics = {schemaVersion: 1, profile: SITE.profile,
    kernels: {worldgen: {enabled: false}}};
  const channel = startWorker(page);
  const message = page.posted[1].message;
  assert.equal(message.type, "gaius-kernel-port");
  assert.deepEqual(plain(message.kernelScripts.map((entry) => entry.url)), [
    `https://example.invalid/Gaius/${SITE.profile}/kernels/kernel-mirror.0000000000000000.js`,
    "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen-job.5555555555555555.js",
    "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen-kernel.6666666666666666.js",
  ]);
  assert.deepEqual(plain(message.worldgenKernel), {enabled: false});
  assert.deepEqual(plain(message.kernelSemantics), plain(page.window.__gaiusKernelSemantics));
  assert.deepEqual(plain(message.lightKernel), {enabled: false});
  closePorts(page, channel);

  // A site without the worldgen scripts hands over nothing it cannot serve; the stored light
  // setting turns the light kernel off as well.
  const bare = createPage({site: SITE, storage: {"gaius.lightKernel": "off"}});
  const bareChannel = startWorker(bare);
  assert.deepEqual(plain(bare.posted[1].message.kernelScripts), []);
  assert.deepEqual(plain(bare.posted[1].message.worldgenKernel), {});
  assert.deepEqual(plain(bare.posted[1].message.lightKernel), {enabled: false});
  closePorts(bare, bareChannel);

  // A profile whose version file sets "worldgenKernelDefault": false (injected by the launcher)
  // stays on vanilla worldgen unless the page asks for the kernel explicitly.
  const vanilla = createPage({site: SITE});
  vanilla.window.__gaiusWorldgenKernelDefault = false;
  const vanillaChannel = startWorker(vanilla);
  assert.deepEqual(plain(vanilla.posted[1].message.worldgenKernel), {enabled: false});
  closePorts(vanilla, vanillaChannel);
  const asked = createPage({site: SITE, search: "?worldgenKernel=1"});
  asked.window.__gaiusWorldgenKernelDefault = false;
  const askedChannel = startWorker(asked);
  assert.deepEqual(plain(asked.posted[1].message.worldgenKernel), {enabled: true});
  closePorts(asked, askedChannel);

  const on = createPage({site: SITE, search: "?lightKernel=1"});
  const onChannel = startWorker(on);
  assert.deepEqual(plain(on.posted[1].message.lightKernel), {enabled: true});
  closePorts(on, onChannel);
});

check("dev, portable and file pages hand the server Worker what they can", async () => {
  const dev = createPage({protocol: "http:"});
  const devChannel = startWorker(dev);
  assert.deepEqual(plain(dev.posted[1].message.kernelScripts.map((entry) => entry.url)), [
    `http://example.invalid/Gaius/${SITE.profile}/kernels/kernel-mirror.js`,
    "http://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen-job.js",
    "http://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen-kernel.js",
  ]);
  closePorts(dev, devChannel);

  const portable = createPage({portable: true, protocol: "file:", inertScripts: {
    "kernels/worldgen-job.js": "\nself.GaiusWorldgenJob = {};\n",
    "kernels/worldgen-kernel.js": "\nself.GaiusWorldgenKernel = {};\n",
  }});
  const portableChannel = startWorker(portable);
  assert.deepEqual(plain(portable.posted[1].message.kernelScripts), [
    {name: "kernels/worldgen-job.js", source: "\nself.GaiusWorldgenJob = {};\n"},
    {name: "kernels/worldgen-kernel.js", source: "\nself.GaiusWorldgenKernel = {};\n"},
  ]);
  closePorts(portable, portableChannel);

  const file = createPage({protocol: "file:"});
  const fileChannel = startWorker(file);
  assert.deepEqual(plain(file.posted[1].message.kernelScripts), [], "a dist opened from disk has no kernels");
  closePorts(file, fileChannel);
  // The quality layer still loads by tag there; kernel modules do not.
  const sources = file.appended.filter((node) => node.tagName === "script").map((node) => node.src);
  assert.deepEqual(plain(sources), [
    "runtime/quality/gpu-caps.js", "runtime/quality/quality-profile.js", "runtime/quality/gl-pass.js",
    "runtime/quality/upscaler.js", "runtime/quality/post-chain.js", "runtime/quality/quality-runtime.js",
  ]);
});

check("?worldgenKernelHost=worker sends the port once the worldgen host configuration is known", async () => {
  const page = createPage({site: WORLDGEN_SITE, search: "?worldgenKernelHost=worker"});
  for (const node of page.appended) if (node.onload) node.onload();
  const channel = startWorker(page);
  assert.equal(page.posted.length, 1, "the port waits for the manifest");
  for (let i = 0; i < 50 && page.posted.length < 2; i++) await new Promise((resolve) => setTimeout(resolve, 5));
  const message = page.posted[1].message;
  assert.deepEqual(plain(message.kernelScripts.map((entry) => entry.name)), [
    "kernels/kernel-mirror.js",
    "kernels/kernel-policy.js", "kernels/kernel-runtime.js", "kernels/worldgen-job.js", "kernels/worldgen-kernel.js",
  ]);
  assert.equal(message.worldgenKernel.host, "worker");
  assert.equal(message.worldgenKernel.workerUrl, "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/kernel-worker.7777777777777777.js");
  assert.deepEqual(plain(message.worldgenKernel.kernels.worldgen.variants), {
    simd: {url: "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen.simd.8888888888888888.wasm"},
    baseline: {url: "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen.baseline.9999999999999999.wasm"},
  });
  assert.equal(page.posted[1].transfer[0], message.port);
  closePorts(page, channel);
});

check("a site page names its profile for the quality layer", async () => {
  const page = createPage({site: SITE});
  assert.equal(page.window.__gaiusProfileId, RENDERPEARL_PROFILE.id);
  const dev = createPage();
  assert.equal(dev.window.__gaiusProfileId, undefined);
});

// --- client runtime selection (design D14) ------------------------------------------------------

// node validates the GC and try_table probes but has no JSPI: the page's WebAssembly gets the
// real validate plus JSPI stand-ins, or validate stubs that refuse one probe.
const JSPI = {Suspending: function Suspending() {}, promising: function promising() {}};
const fullWasm = () => Object.assign({validate: (bytes) => WebAssembly.validate(bytes)}, JSPI);
const noJspiWasm = () => ({validate: (bytes) => WebAssembly.validate(bytes)});
// The GC probe is the one with the 0xFB (GC) prefix, the try_table probe the one with 0x1F.
const refusing = (opcode) => Object.assign({
  validate: (bytes) => (Array.prototype.includes.call(bytes, opcode) ? false : WebAssembly.validate(bytes)),
}, JSPI);
const WEB_APIS = {Blob, Response, DecompressionStream, ReadableStream, Uint8Array, ArrayBuffer};

const WASM_SITE = Object.assign({}, SITE, {
  assets: Object.assign({}, SITE.assets, {
    "classes.wasm": "classes.aaaaaaaaaaaaaaab.wasm",
    "classes.wasm.gz": "classes.wasm.aaaaaaaaaaaaaaac.gz",
    "gaius-wasm-boot.js": "gaius-wasm-boot.aaaaaaaaaaaaaaad.js",
    "gaius-wasm-gc-runtime.js": "gaius-wasm-gc-runtime.aaaaaaaaaaaaaaae.js",
    "gaius-jspi.js": "gaius-jspi.aaaaaaaaaaaaaaaf.js",
  }),
});
const WASM_SERVER_SITE = Object.assign({}, WASM_SITE, {
  assets: Object.assign({}, WASM_SITE.assets, {
    "singleplayer-server.wasm": "singleplayer-server.bbbbbbbbbbbbbbb1.wasm",
    "gaius-wasm-worker.js": "gaius-wasm-worker.bbbbbbbbbbbbbbb2.js",
  }),
});
const WASM_ONLY_SERVER_SITE = Object.assign({}, WASM_SERVER_SITE, {runtimePolicy: "wasm-only"});
const BASE = "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/";

check("the runtime defaults to WasmGC and records all required capabilities", async () => {
  const page = createPage({site: WASM_SITE, globals: {WebAssembly: fullWasm()}});
  const selection = page.window.__gaiusRuntimeSelection;
  assert.equal(selection, page.window.__gaiusBoot.runtime);
  assert.deepEqual(plain(selection.features), {webAssembly: true, gc: true, tryTable: true, jspi: true, decompressionStream: false});
  assert.equal(selection.requested, "wasm");
  assert.equal(selection.selected, "wasm");
  assert.equal(selection.reason, "wasm-only");
  assert.equal(selection.fallback, false);
  assert.equal(selection.form, "site");
  assert.equal(selection.wasmBuilt, true);
  assert.equal(page.window.__gaiusClassesUrl, BASE + "gaius-wasm-boot.aaaaaaaaaaaaaaad.js");
  assert.equal(typeof page.window.__gaiusWasmSource?.then, "function");
});

check("?runtime=wasm starts the WebAssembly GC client and its module download", async () => {
  const requests = [];
  const served = new Response(new Uint8Array([0, 97, 115, 109]), {headers: {"Content-Type": "application/wasm"}});
  const page = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(),
    fetch: async (url) => { requests.push(String(url)); return served; },
  }, WEB_APIS)});
  const selection = page.window.__gaiusRuntimeSelection;
  assert.equal(selection.selected, "wasm");
  assert.equal(selection.reason, "wasm-only");
  assert.equal(page.window.__gaiusClassesUrl, BASE + "gaius-wasm-boot.aaaaaaaaaaaaaaad.js");
  assert.equal(page.window.__gaiusWasmRuntimeUrl, BASE + "gaius-wasm-gc-runtime.aaaaaaaaaaaaaaae.js");
  assert.equal(page.window.__gaiusJspiUrl, BASE + "gaius-jspi.aaaaaaaaaaaaaaaf.js");
  assert.equal(page.appended.filter((node) => node.src === page.window.__gaiusClassesUrl).length, 1,
    "the Wasm boot shim is inserted during parsing exactly once");
  assert.equal(typeof page.window.__gaiusWasmBootReady?.then, "function");
  assert.ok(!page.appended.some((node) => node.tagName === "link"), "no classes.js preload");
  const source = await page.window.__gaiusWasmSource;
  assert.equal(requests[0], BASE + "classes.aaaaaaaaaaaaaaab.wasm");
  assert.equal(source, served, "the fetched Response itself streams into the compiler (code cache)");
  assert.equal(new Uint8Array(await source.arrayBuffer())[1], 97);
  // A host that serves .wasm with another type gets the body rewrapped as application/wasm.
  const untyped = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), fetch: async () => new Response(new Uint8Array([0, 97, 115, 109])),
  }, WEB_APIS)});
  const rewrapped = await untyped.window.__gaiusWasmSource;
  assert.equal(rewrapped.headers.get("Content-Type"), "application/wasm");
  assert.equal(new Uint8Array(await rewrapped.arrayBuffer())[2], 115);
  // beforeMain waits for the instantiated module and publishes the shared boot timings.
  for (const node of page.appended) if (node.onload) node.onload();
  page.window.__gaiusBootTimings = {pageStart: 0, classesLoaded: 5};
  page.window.__gaiusWasmTimings = {instantiated: 42};
  page.window.__gaiusClientReady = Promise.resolve({});
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(page.window.__gaiusBootTimings.runtime, "wasm");
  assert.equal(page.window.__gaiusBootTimings.clientReady, 42);
  assert.equal(page.window.__gaiusBootTimings.runtimeFallback, undefined);
  assert.equal(typeof page.window.__gaiusBootTimings.runtimeSelected, "number");
});

check("missing WasmGC, try_table or JSPI selects the terminal unsupported state", async () => {
  const quiet = {console: {warn() {}}};
  const noGc = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: Object.assign({WebAssembly: refusing(0xfb)}, quiet)});
  assert.equal(noGc.window.__gaiusRuntimeSelection.reason, "missing:gc");
  const noTryTable = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: Object.assign({WebAssembly: refusing(0x1f)}, quiet)});
  assert.equal(noTryTable.window.__gaiusRuntimeSelection.reason, "missing:try-table");
  assert.equal(noTryTable.window.__gaiusRuntimeSelection.requested, "wasm");
  const none = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: Object.assign({WebAssembly: undefined}, quiet)});
  assert.equal(none.window.__gaiusRuntimeSelection.reason, "missing:webassembly");
  const unbuilt = createPage({site: SITE, search: "?runtime=wasm", globals: Object.assign({WebAssembly: noJspiWasm()}, quiet)});
  assert.equal(unbuilt.window.__gaiusRuntimeSelection.reason, "missing:jspi");
  for (const page of [noGc, noTryTable, none, unbuilt]) {
    assert.equal(page.window.__gaiusRuntimeSelection.selected, "unsupported");
    assert.equal(page.window.__gaiusBoot.handlesWasmFailure, false);
    assert.equal(page.appended.length, 0, "unsupported browsers must not load support scripts");
  }
});

check("the page ignores runtime overrides and always selects WasmGC", async () => {
  const runtimes = {wasm: {client: "classes.wasm", server: "singleplayer-server.wasm"}};
  const fetchNothing = async () => new Response(new Uint8Array(0));
  const page = createPage({protocol: "http:", search: "?runtime=fastest", globals: {
    WebAssembly: fullWasm(), __gaiusRuntimes: runtimes, __gaiusRuntimeDefault: "wasm", fetch: fetchNothing,
  }});
  const selection = page.window.__gaiusRuntimeSelection;
  assert.equal(selection.form, "dev");
  assert.equal(selection.selected, "wasm");
  assert.equal(selection.source, "wasm-only");
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "runtime-invalid" && entry.detail === "fastest"));
  assert.equal(page.window.__gaiusClassesUrl, "http://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/gaius-wasm-boot.js");
  const override = createPage({protocol: "http:", search: "?runtime=js", globals: {
    WebAssembly: fullWasm(), __gaiusRuntimes: runtimes, __gaiusRuntimeDefault: "wasm",
  }});
  assert.equal(override.window.__gaiusRuntimeSelection.selected, "wasm");
  assert.equal(override.window.__gaiusRuntimeSelection.reason, "wasm-only");
});

check("a Wasm module failure is terminal and never starts a JavaScript client", async () => {
  const page = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: {
    WebAssembly: fullWasm(), fetch: async () => new Response(new Uint8Array(0)),
  }});
  for (const node of page.appended) if (node.onload) node.onload();
  page.window.__gaiusBootTimings = {};
  page.window.__gaiusClientReady = Promise.reject(new Error("CompileError: boom"));
  page.window.__gaiusClientReady.catch(() => {});
  await assert.rejects(() => page.window.__gaiusBoot.beforeMain(), /CompileError: boom/);
  assert.equal(page.appended.filter((node) => /classes\.js/.test(node.src || "")).length, 0);
});

check("a dist opened from disk decodes its payload scripts without fetch", async () => {
  const module = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]);
  const shaderc = new Uint8Array([0, 97, 115, 109, 2]);
  const gzip = async (bytes) => new Uint8Array(await new Response(new Blob([bytes]).stream()
    .pipeThrough(new CompressionStream("gzip"))).arrayBuffer());
  const base64 = (bytes) => Buffer.from(bytes).toString("base64");
  const payloadData = {
    "classes.wasm": base64(await gzip(module)),
    "vanilla-assets.pack.gz": base64(await gzip(new TextEncoder().encode("GAIUSVP1"))),
    "gaius-shaderc.wasm": base64(await gzip(shaderc)),
    "gaius-spvc.wasm": base64(await gzip(shaderc)),
    "gaius-shaderc.js": base64(new TextEncoder().encode("self.GaiusShaderc = {};")),
    "gaius-spvc.js": base64(new TextEncoder().encode("self.GaiusSpvc = {};")),
    "gaius-hotpath.wasm": base64(await gzip(shaderc)),
  };
  const payloads = {};
  for (const name of Object.keys(payloadData)) {
    payloads[name] = {script: name + ".payload.js", bytes: 1,
      gzip: name.endsWith(".wasm") || name === "classes.wasm"};
  }
  const page = createPage({protocol: "file:", search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), atob, __gaiusFilePayloads: payloads,
    __gaiusRuntimes: {wasm: {client: "classes.wasm"}},
    fetch: async () => { throw new Error("fetch under file://"); },
  }, WEB_APIS)});
  const selection = page.window.__gaiusRuntimeSelection;
  assert.equal(selection.form, "file");
  assert.equal(selection.selected, "wasm");
  assert.equal(page.window.__gaiusClassesUrl, "file://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/gaius-wasm-boot.js");
  // Every payload script runs like the real one: it stores its base64 text, then loads.
  const scripts = page.appended.filter((node) => node.attributes["data-gaius-file-payload"]);
  assert.deepEqual(scripts.map((node) => node.src).sort(), Object.keys(payloads).map((name) => name + ".payload.js").sort());
  page.window.__gaiusFilePayloadData = {};
  for (const node of scripts) {
    page.window.__gaiusFilePayloadData[node.attributes["data-gaius-file-payload"]] = payloadData[node.attributes["data-gaius-file-payload"]];
    node.onload();
  }
  const source = await page.window.__gaiusWasmSource;
  assert.equal(source.headers.get("Content-Type"), "application/wasm");
  assert.deepEqual([...new Uint8Array(await source.arrayBuffer())], [...module]);
  const vanilla = await page.window.__gaiusVanillaAssetsCompressedPromise;
  assert.equal(vanilla[0], 0x1f, "the vanilla pack stays compressed for the launcher's decoder");
  await page.window.__gaiusPortableAssetsReady;
  const urls = page.window.__gaiusShaderToolchainUrls;
  assert.match(urls.shadercJs, /^blob:/);
  assert.match(urls.shadercWasm, /^blob:/);
  assert.match(page.window.__gaiusHotpathWasmUrl, /^blob:/);
  assert.deepEqual(Object.keys(page.window.__gaiusFilePayloadData), [], "decoded texts are released");
  // Without the module payload a disk page has no wasm build to start.
  const bare = createPage({protocol: "file:", search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), __gaiusRuntimes: {wasm: {client: "classes.wasm"}}, console: {warn() {}},
  }, WEB_APIS)});
  assert.equal(bare.window.__gaiusRuntimeSelection.reason, "missing:wasm-build");
});

check("a portable page with an embedded module selects wasm and decodes it itself", async () => {
  const page = createPage({portable: true, protocol: "file:", search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), __gaiusPortableRuntimes: {wasm: {client: "classes.wasm"}},
  }, WEB_APIS)});
  assert.equal(page.window.__gaiusRuntimeSelection.form, "portable");
  assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm");
  assert.equal(page.window.__gaiusClassesUrl, undefined, "the portable bootstrap sets the loader URL");
  assert.equal(page.window.__gaiusWasmSource, undefined);
  const jsOnly = createPage({portable: true, protocol: "file:", search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), console: {warn() {}},
  }, WEB_APIS)});
  assert.equal(jsOnly.window.__gaiusRuntimeSelection.reason, "missing:wasm-build");
});

check("a dual portable page keeps the Wasm client on its embedded payload", async () => {
  const wasmBytes = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]);
  const siblingRequests = [];
  const page = createPage({portable: true, protocol: "file:", search: "?runtime=wasm", site: SITE,
    globals: Object.assign({
      WebAssembly: fullWasm(),
      __gaiusPortableRuntimes: {wasm: {client: "classes.wasm", server: "singleplayer-server.wasm"}},
      __gaiusPortablePayloadBytes: {
        "classes.wasm": Promise.resolve(wasmBytes),
        "singleplayer-server.wasm": Promise.resolve(wasmBytes),
      },
      fetch: async (url) => {
        siblingRequests.push(String(url));
        throw new Error("dual portable attempted a sibling fetch: " + url);
      },
    }, WEB_APIS)});
  assert.equal(page.window.__gaiusRuntimeSelection.form, "portable");
  assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm");
  assert.equal(page.window.__gaiusClassesUrl, undefined);
  assert.deepEqual([...await page.window.__gaiusPortablePayloadBytes["classes.wasm"]], [...wasmBytes]);
  assert.deepEqual(siblingRequests, [], "portable Wasm selection must not call inflateUrl/fetch");
});

check("a dual portable descriptor is recognized before the body bootstrap runs", async () => {
  const page = createPage({protocol: "file:", search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(),
    __gaiusPortableRuntimes: {wasm: {client: "classes.wasm", server: "singleplayer-server.wasm"}},
    __gaiusFilePayloads: {"classes.wasm": {script: "classes.wasm.payload.js", bytes: 8, gzip: true}},
  }, WEB_APIS)});
  assert.equal(page.window.__gaiusRuntimeSelection.form, "portable");
  assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm");
  assert.equal(page.window.__gaiusRuntimeSelection.wasmBuilt, true);
});

check("?serverRuntime=wasm hands the server module to the singleplayer Worker", async () => {
  const page = createPage({site: WASM_SERVER_SITE, search: "?serverRuntime=wasm", globals: {WebAssembly: fullWasm()}});
  assert.equal(page.window.__gaiusBoot.serverRuntime, "wasm");
  const channel = startWorker(page);
  const start = page.posted[0].message;
  assert.equal(start.serverRuntime, "wasm");
  assert.equal(start.serverWasmUrl, BASE + "singleplayer-server.bbbbbbbbbbbbbbb1.wasm");
  assert.deepEqual(plain(start.wasmRuntimeScripts), [
    {name: "gaius-wasm-gc-runtime.js", url: BASE + "gaius-wasm-gc-runtime.aaaaaaaaaaaaaaae.js"},
    {name: "gaius-jspi.js", url: BASE + "gaius-jspi.aaaaaaaaaaaaaaaf.js"},
    {name: "gaius-wasm-worker.js", url: BASE + "gaius-wasm-worker.bbbbbbbbbbbbbbb2.js"},
  ]);
  assert.equal(start.port, channel.port2, "the transferred port stays in the message");
  closePorts(page, channel);
  const noServer = createPage({site: WASM_SITE, search: "?serverRuntime=wasm", globals: {WebAssembly: fullWasm()}});
  const noServerChannel = startWorker(noServer);
  assert.equal(noServer.posted[0].message.serverRuntime, "wasm");
  assert.equal(noServer.posted[0].message.serverWasmUrl,
    BASE + "singleplayer-server.bbbbbbbbbbbbbbb1.wasm");
  closePorts(noServer, noServerChannel);
});

check("wasm-only sites default the integrated server to Wasm", async () => {
  const page = createPage({site: WASM_ONLY_SERVER_SITE, globals: {WebAssembly: fullWasm()}});
  assert.equal(page.window.__gaiusBoot.serverRuntime, "wasm");
  const channel = startWorker(page);
  assert.equal(page.posted[0].message.serverRuntime, "wasm");
  assert.equal(page.posted[0].message.serverWasmUrl,
    BASE + "singleplayer-server.bbbbbbbbbbbbbbb1.wasm");
  closePorts(page, channel);
});

check("title boot prewarms and reuses the compiled server module", async () => {
  const compiledModule = {compiled: true};
  const wasm = Object.assign(fullWasm(), {
    compile: async () => compiledModule,
  });
  const clock = {now: 1000};
  const page = createPage({clock, site: WASM_SERVER_SITE, search: "?serverRuntime=wasm",
    globals: Object.assign({
      WebAssembly: wasm,
      fetch: async () => new Response(MODULE_BYTES, {headers: {"Content-Type": "application/wasm"}}),
      __gaiusMinecraftState: {screen: "TitleScreen", running: true, overlay: null},
    }, WEB_APIS)});
  page.runIntervals();
  clock.now = 4000;
  page.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 100));
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "server-wasm-prewarmed"),
    JSON.stringify({runtime: page.window.__gaiusBoot.serverRuntime, selection: page.window.__gaiusRuntimeSelection,
      events: page.window.__gaiusBoot.events}));
  const channel = startWorker(page);
  assert.equal(page.posted[0].message.serverWasmModule, compiledModule);
  closePorts(page, channel);
});

check("portable gzip payload is inflated before server module prewarm", async () => {
  const compiledModule = {compiled: "portable"};
  const wasm = Object.assign(fullWasm(), {
    compile: async () => compiledModule,
    compileStreaming: async (source) => {
    assert.ok(source instanceof Response, "portable gzip must compile from an inflated Response");
    assert.equal(source.headers.get("Content-Type"), "application/wasm");
    assert.deepEqual([...new Uint8Array(await source.arrayBuffer())], [...MODULE_BYTES]);
      return compiledModule;
    },
  });
  const compressed = new Uint8Array(await new Response(new Blob([MODULE_BYTES]).stream()
    .pipeThrough(new CompressionStream("gzip"))).arrayBuffer());
  assert.deepEqual([...compressed.subarray(0, 2)], [0x1f, 0x8b]);
  const clock = {now: 1000};
  const page = createPage({clock, portable: true, protocol: "file:", search: "?runtime=wasm", inertScripts: {
      "gaius-wasm-gc-runtime.js": "/* runtime */",
      "gaius-jspi.js": "/* jspi */",
      "gaius-wasm-worker.js": "/* worker */",
    },
    globals: Object.assign({
      WebAssembly: wasm,
      __gaiusPortableRuntimes: {wasm: {client: "classes.wasm", server: "singleplayer-server.wasm"}},
      __gaiusMinecraftState: {screen: "TitleScreen", running: true, overlay: null},
      __gaiusPortablePayloadBytes: {"classes.wasm": MODULE_BYTES, "singleplayer-server.wasm": compressed},
      fetch: async () => { throw new Error("portable prewarm attempted sibling fetch"); },
      requestIdleCallback: (callback) => callback(),
    }, WEB_APIS)});
  page.runIntervals();
  clock.now = 4000;
  page.runIntervals();
  await new Promise((resolve) => setTimeout(resolve, 100));
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "server-wasm-prewarmed"),
    JSON.stringify({runtime: page.window.__gaiusBoot.serverRuntime, selection: page.window.__gaiusRuntimeSelection,
      events: page.window.__gaiusBoot.events}));
  const channel = startWorker(page);
  assert.equal(page.posted[0].message.serverWasmModule, compiledModule);
  closePorts(page, channel);
});

// A gzip-only site (build-pages-site.py, gaius-site-inflate-v1): the modules ship as .gz only and
// site.inflate names the URL the Service Worker serves them under.
const GZIP_ONLY_SITE = (() => {
  const assets = Object.assign({}, WASM_SERVER_SITE.assets, {
    "classes.wasm.gz": "classes.wasm.aaaaaaaaaaaaaaac.gz",
    "singleplayer-server.wasm.gz": "singleplayer-server.wasm.bbbbbbbbbbbbbbb3.gz",
  });
  delete assets["classes.wasm"];
  delete assets["singleplayer-server.wasm"];
  return Object.assign({}, WASM_SERVER_SITE, {assets, inflate: {
    "classes.wasm": "classes.aaaaaaaaaaaaaaac.wasm",
    "singleplayer-server.wasm": "singleplayer-server.bbbbbbbbbbbbbbb3.wasm",
  }});
})();
const MODULE_BYTES = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]);
const gzipResponse = async () => new Response(await new Response(new Blob([MODULE_BYTES]).stream()
  .pipeThrough(new CompressionStream("gzip"))).arrayBuffer(), {headers: {"Content-Type": "application/gzip"}});
const controlled = {hardwareConcurrency: 8, serviceWorker: {controller: {}}};

check("a gzip-only module comes from the Service Worker, or is inflated on the page without one", async () => {
  const requests = [];
  const page = createPage({site: GZIP_ONLY_SITE, search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), fetch: async (url) => { requests.push(String(url)); return gzipResponse(); },
  }, WEB_APIS)});
  assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm", "a .gz module counts as built");
  const inflatedOnPage = await page.window.__gaiusWasmSource;
  assert.deepEqual(requests, [BASE + "classes.wasm.aaaaaaaaaaaaaaac.gz"]);
  assert.equal(inflatedOnPage.headers.get("Content-Type"), "application/wasm");
  assert.deepEqual(new Uint8Array(await inflatedOnPage.arrayBuffer()), MODULE_BYTES);
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "wasm-inflate" && entry.detail === "page"));

  const served = new Response(MODULE_BYTES, {headers: {"Content-Type": "application/wasm"}});
  const swRequests = [];
  const underWorker = createPage({site: GZIP_ONLY_SITE, search: "?runtime=wasm", globals: Object.assign({
    navigator: controlled, WebAssembly: fullWasm(),
    fetch: async (url) => { swRequests.push(String(url)); return served; },
  }, WEB_APIS)});
  assert.equal(await underWorker.window.__gaiusWasmSource, served, "the worker's stored response streams into the compiler");
  assert.deepEqual(swRequests, [BASE + "classes.aaaaaaaaaaaaaaac.wasm"]);

  const fallbackRequests = [];
  const workerMissed = createPage({site: GZIP_ONLY_SITE, search: "?runtime=wasm", globals: Object.assign({
    navigator: controlled, WebAssembly: fullWasm(),
    fetch: async (url) => {
      fallbackRequests.push(String(url));
      return String(url).endsWith(".wasm") ? new Response("missing", {status: 404}) : gzipResponse();
    },
  }, WEB_APIS)});
  assert.deepEqual(new Uint8Array(await (await workerMissed.window.__gaiusWasmSource).arrayBuffer()), MODULE_BYTES);
  assert.deepEqual(fallbackRequests, [BASE + "classes.aaaaaaaaaaaaaaac.wasm", BASE + "classes.wasm.aaaaaaaaaaaaaaac.gz"]);
  assert.ok(workerMissed.window.__gaiusBoot.events.some((entry) => entry.event === "wasm-inflate-fallback"));
});

check("page fallback passes through transparently decoded Wasm with either header state", async () => {
  for (const headers of [
    {"Content-Type": "application/gzip"},
    {"Content-Type": "application/gzip", "Content-Encoding": "gzip"},
  ]) {
    const requests = [];
    const page = createPage({site: GZIP_ONLY_SITE, search: "?runtime=wasm", globals: Object.assign({
      WebAssembly: fullWasm(),
      fetch: async (url) => {
        requests.push(String(url));
        return new Response(MODULE_BYTES, {headers});
      },
    }, WEB_APIS)});
    const response = await page.window.__gaiusWasmSource;
    assert.deepEqual(new Uint8Array(await response.arrayBuffer()), MODULE_BYTES);
    assert.deepEqual(requests, [BASE + "classes.wasm.aaaaaaaaaaaaaaac.gz"]);
  }
});

check("an aborted page Wasm download rejects while it is still streaming", async () => {
  const page = createPage({site: GZIP_ONLY_SITE, search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(),
    fetch: async () => new Response(new ReadableStream({start(controller) {
      controller.enqueue(new Uint8Array([0x1f, 0x8b]));
      controller.error(new Error("aborted"));
    }}), {headers: {"Content-Type": "application/gzip"}}),
  }, WEB_APIS)});
  await assert.rejects(page.window.__gaiusWasmSource, /aborted/);
});

check("a gzip-only server module goes to the Worker by its inflated URL or as its .gz", async () => {
  const underWorker = createPage({site: GZIP_ONLY_SITE, search: "?serverRuntime=wasm",
    globals: {navigator: controlled, WebAssembly: fullWasm()}});
  const channel = startWorker(underWorker);
  assert.equal(underWorker.posted[0].message.serverRuntime, "wasm");
  assert.equal(underWorker.posted[0].message.serverWasmUrl, BASE + "singleplayer-server.bbbbbbbbbbbbbbb3.wasm");
  closePorts(underWorker, channel);
  const alone = createPage({site: GZIP_ONLY_SITE, search: "?serverRuntime=wasm", globals: {WebAssembly: fullWasm()}});
  const aloneChannel = startWorker(alone);
  assert.equal(alone.posted[0].message.serverWasmUrl, BASE + "singleplayer-server.wasm.bbbbbbbbbbbbbbb3.gz");
  closePorts(alone, aloneChannel);
});

check("?gaiusSw=0 unregisters only this site's own Service Worker", async () => {
  const unregistered = [];
  const registration = (scope) => ({scope, unregister: async () => { unregistered.push(scope); return true; }});
  const registrations = [registration(BASE), registration("https://example.invalid/Gaius/26.2/"),
    registration("https://example.invalid/OtherSite/")];
  const queried = [];
  const page = createPage({site: SITE, search: "?gaiusSw=0", globals: {
    document: {readyState: "complete", head: {appendChild: (node) => node}, documentElement: {appendChild: (node) => node},
      createElement: (tag) => ({tagName: tag, attributes: {}, setAttribute() {}}), getElementById: () => null,
      querySelector: () => null},
    navigator: {hardwareConcurrency: 8, serviceWorker: {
      controller: null,
      getRegistrations: async () => registrations,
      getRegistration: async (scope) => {
        queried.push(String(scope));
        return registrations[0];
      },
      register: async () => { throw new Error("must not register under ?gaiusSw=0"); },
    }},
  }});
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "service-worker"));
  assert.deepEqual(queried, ["./"]);
  assert.deepEqual(unregistered, [BASE], "sibling profiles and other sites on the origin keep their workers");
});

// The WebAssembly GC client shim (gaius-wasm-boot.js) and its runtime support scripts. A portable
// page has no __gaiusWasmSupportReady preload: integ4-t6 skipped the runtime script then and failed
// with "Cannot read properties of undefined (reading 'wasmGC')".
const wasmShim = await readFile(new URL("../web/runtime/wasmgc/gaius-wasm-boot.js", import.meta.url), "utf8");
async function runWasmShim({preload, preloaded = false} = {}) {
  const loaded = [];
  const counts = {loads: 0};
  const runtime = {wasmGC: {load: async () => { counts.loads++; return {instance: {exports: {}}}; }}};
  const sandbox = {Uint8Array, DataView, URLSearchParams, performance, console, setTimeout, clearTimeout,
    location: {search: ""}, __gaiusWasmSource: new Uint8Array(0),
    __gaiusWasmRuntimeUrl: "blob:runtime", __gaiusJspiUrl: "blob:jspi",
    WebAssembly: {Suspending() {}, promising() {}, Memory: WebAssembly.Memory},
    document: {createElement: () => ({}), head: {appendChild(script) {
      loaded.push(script.src);
      setImmediate(() => {
        if (script.src === "blob:runtime") sandbox.TeaVM = runtime;
        if (script.src === "blob:jspi") sandbox.GaiusJspi = {};
        script.onload();
      });
    }}},
  };
  if (preloaded) Object.assign(sandbox, {TeaVM: runtime, GaiusJspi: {}});
  if (preload !== undefined) sandbox.__gaiusWasmSupportReady = Promise.resolve(preload);
  sandbox.window = sandbox;
  const context = vm.createContext(sandbox);
  vm.runInContext(wasmShim, context, {filename: "gaius-wasm-boot.js"});
  await context.__gaiusWasmReady;
  return {loaded: loaded.sort(), loads: counts.loads};
}

check("wasm shim: a portable page without a support preload loads the runtime scripts itself", async () => {
  assert.deepEqual(await runWasmShim(), {loaded: ["blob:jspi", "blob:runtime"], loads: 1});
});

check("memory pacing asks for a UA memory measurement only at quiet moments, only when isolated", async () => {
  const pacingPage = (search, isolated) => {
    const timers = [];
    let calls = 0;
    const clock = {now: 1};
    const performance = {now: () => clock.now, mark() {}, getEntriesByType: () => [],
      measureUserAgentSpecificMemory: () => { calls++; return Promise.resolve({bytes: 1000 * calls, breakdown: []}); }};
    const page = createPage({site: SITE, search, globals: {crossOriginIsolated: isolated, performance,
      setTimeout: (callback, ms) => { timers.push({callback, ms}); return timers.length; }}});
    // Runs the latest pacing check after the clock moved on.
    const tick = async (ms) => {
      clock.now += ms;
      const checks = timers.filter((timer) => timer.callback.name === "check");
      checks[checks.length - 1].callback();
      await new Promise((resolve) => setImmediate(resolve));
    };
    return {page, timers, tick, calls: () => calls};
  };
  const {page, tick, calls} = pacingPage("", true);
  const game = page.window.__gaiusMinecraftState = {level: false, screen: "net.minecraft.client.gui.screens.TitleScreen", pause: false};
  await tick(1000);
  assert.equal(calls(), 1, "the title screen is a quiet moment");
  const telemetry = page.window.__gaiusMemoryTelemetry.uaMemory;
  assert.equal(telemetry.bytes, 1000);
  assert.deepEqual(Object.assign({}, telemetry.reasons), {menu: 1});
  game.level = true; game.screen = null;
  await tick(60000);
  assert.equal(calls(), 1, "a world frame never asks without a large backlog");
  game.pause = true; game.screen = "net.minecraft.client.gui.screens.PauseScreen";
  await tick(1000);
  assert.equal(calls(), 2, "the pause screen asks once 30 s passed since the last answer");
  await tick(1000);
  assert.equal(calls(), 2, "and not again within 30 s");
  game.pause = false; game.screen = "net.minecraft.client.gui.screens.LevelLoadingScreen";
  await tick(31000);
  assert.equal(calls(), 3, "loading screens are quiet too");
  game.screen = null;
  page.window.GaiusWebGpu = {stats: {passes: 2000000}};
  await tick(200000);
  assert.equal(calls(), 4, "a large render pass backlog asks in the world after the long gap");
  assert.equal(telemetry.reasons.backlog, 1);
  page.dispatch("pagehide");
  game.screen = "net.minecraft.client.gui.screens.PauseScreen";
  await tick(60000);
  assert.equal(calls(), 4, "pagehide stops the pacing");
  const custom = pacingPage("?gaiusGcPacing=2500", true);
  assert.equal(custom.timers.filter((timer) => timer.ms === 2500).length, 1, "a fixed pause asks on its own timer");
  const off = pacingPage("?gaiusGcPacing=0", true);
  assert.equal(off.page.window.__gaiusMemoryTelemetry?.uaMemory, undefined);
  const notIsolated = pacingPage("", false);
  assert.equal(notIsolated.page.window.__gaiusMemoryTelemetry?.uaMemory, undefined);
});

check("wasm shim: a failed preload falls back, a successful one loads nothing twice", async () => {
  assert.deepEqual(await runWasmShim({preload: false}), {loaded: ["blob:jspi", "blob:runtime"], loads: 1});
  assert.deepEqual(await runWasmShim({preload: true, preloaded: true}), {loaded: [], loads: 1});
  // A preload that reported success but left a global missing still gets that script.
  assert.deepEqual(await runWasmShim({preload: true}), {loaded: ["blob:jspi", "blob:runtime"], loads: 1});
});

let failed = 0;
check("unsupported capability gate shows a terminal WasmGC message state", async () => {
  for (const [inject, wasm] of [["no-wasmgc", refusing(0xfb)], ["no-jspi", noJspiWasm()]]) {
    const page = createPage({site: WASM_SITE, search: "?runtime=js", globals: {
      WebAssembly: wasm,
      __gaiusWebGpuShaderToolchain: {loader: "gaius-shader-toolchain.js?v=test", version: "test"},
      fetch: async () => { throw new Error("heavy fetch must not run"); },
    }});
    const selection = page.window.__gaiusRuntimeSelection;
    assert.equal(selection.selected, "unsupported");
    assert.match(selection.reason, /missing:(?:gc|jspi)/);
    assert.equal(page.window.__gaiusUnsupportedBrowser.messageKey, "unsupportedBrowserMessage");
    assert.equal(page.window.__gaiusBoot.handlesWasmFailure, false);
    assert.equal(page.appended.length, 0, `${inject} must not load support scripts`);
  }
});

check("WasmGC WebGL2 pages load their declared shader toolchain", async () => {
  const page = createPage({site: SITE, globals: {
    __gaiusWebGpuShaderToolchain: {loader: "gaius-shader-toolchain.js?v=test", version: "test"},
  }});
  const toolchainScript = page.appended.find((node) => node.attributes?.["data-gaius-boot-module"] === "gaius-shader-toolchain.js");
  assert.ok(toolchainScript, "WasmGC page did not request its shader toolchain");
  page.window.__gaiusShaderToolchainReady = Promise.resolve(true);
  toolchainScript.onload();
  await Promise.resolve();
  assert.equal(page.window.__gaiusShaderToolchainReady !== undefined, true);
});

check("unsupported launcher page has localized message and retry controls", async () => {
  for (const id of ["gaius-unsupported", "gaius-unsupported-title", "gaius-unsupported-message", "gaius-unsupported-retry"]) {
    assert.match(launcherSource, new RegExp(`id=["']${id}["']`));
  }
  for (const locale of ["en_us", "zh_cn", "es_es"]) {
    const start = launcherSource.indexOf(`${locale}: {`);
    assert.ok(start >= 0, `missing ${locale} launcher strings`);
    const end = launcherSource.indexOf("},", start);
    const strings = launcherSource.slice(start, end < 0 ? start + 2000 : end);
    assert.match(strings, /unsupportedTitle/);
    assert.match(strings, /unsupportedBrowserMessage/);
    assert.match(strings, /unsupportedRetry/);
  }
  assert.match(launcherSource, /if \(window\.__gaiusUnsupportedBrowser\) return;/);
});

const SMOKE_ALLOWLIST = new Set([
  "site mode maps logical Wasm names and starts the Wasm client",
  "the runtime defaults to WasmGC and records all required capabilities",
  "?runtime=wasm starts the WebAssembly GC client and its module download",
  "missing WasmGC, try_table or JSPI selects the terminal unsupported state",
  "the page ignores runtime overrides and always selects WasmGC",
  "a Wasm module failure is terminal and never starts a JavaScript client",
  "the sound pack merges into the decoded core pack in one step",
  "sounds without a reload bridge fall back to eager loading instead of going silent",
  "deferred sounds call the provided SoundManager reload bridge once",
  "the integrated server Worker gets a kernel port after its start message",
  "switched-off kernels give no runtime and no server port",
  "world-selection screens warm the server Worker at once and post a runtime prewarm",
  "the world-selection prewarm parks vanilla Main; title idle and low memory do not",
  "an adopted or launching Worker is not replaced while its world starts",
  "an idle Worker that fails to load is retried once per world-selection stay",
  "an open world never triggers the world-selection warm-up",
  "start path awaits the idle bootstrap delivery instead of re-sending it",
  "the kernel port carries the server Worker's scripts and the page switches",
  "?worldgenKernelHost=worker sends the port once the worldgen host configuration is known",
  "a site page names its profile for the quality layer",
  "a portable page with an embedded module selects wasm and decodes it itself",
  "a dual portable page keeps the Wasm client on its embedded payload",
  "a dual portable descriptor is recognized before the body bootstrap runs",
  "?serverRuntime=wasm hands the server module to the singleplayer Worker",
  "wasm-only sites default the integrated server to Wasm",
  "wasm shim: a failed preload falls back, a successful one loads nothing twice",
  "unsupported capability gate shows a terminal WasmGC message state",
  "unsupported launcher page has localized message and retry controls",
  "WasmGC WebGL2 pages load their declared shader toolchain",
]);
const selectedChecks = checks.filter(([name]) => SMOKE_ALLOWLIST.has(name));
for (const [name, fn] of selectedChecks) {
  try {
    await fn();
    console.log(`ok   ${name}`);
  } catch (error) {
    failed++;
    console.log(`FAIL ${name}`);
    console.log(error && error.stack || error);
  }
}
if (failed) {
    console.log(`${failed} of ${selectedChecks.length} boot checks failed`);
  process.exit(1);
}
console.log(`all ${selectedChecks.length} boot checks passed`);
