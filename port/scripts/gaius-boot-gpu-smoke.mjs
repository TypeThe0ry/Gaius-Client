#!/usr/bin/env node
// Graphics backend selection of the launcher boot script (port/web/boot/gaius-boot.js, M1 G2) in
// a node:vm page with a fake DOM, fake WebGPU glue and fake translator module.
//
//   node port/scripts/gaius-boot-gpu-smoke.mjs
//
// Checks: the default stays on WebGL 2 without loading the WebGPU files; ?gpu=webgpu loads the
// glue and the job codec, compiles the translator (dev, site with its gzip copy, portable) and
// beforeMain() waits for GaiusWebGpu.init and installs the translator; every failure (no
// device, init past its 5 s budget, no translator) abandons WebGPU with its reason so the game
// starts on WebGL 2; the device is requested as soon as the glue loads; an unknown ?gpu= is
// reported; the fallback-test switches reach the glue. A page whose WebGPU front-end compiles GLSL
// itself (window.__gaiusWebGpuShaderToolchain, 26.2 and 1.21.11) also loads the shader toolchain
// for webgpu only (dev and site forms) and waits for it; a toolchain that cannot load abandons
// WebGPU, and a page that loads it for every backend (renderpearl) gets no second loader. The
// portable page tries WebGPU without ?gpu= and, like every page that does not, names the reason.

import {CANONICAL_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile, readdir} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../web/boot/gaius-boot.js", import.meta.url), "utf8");
const GLUE = "runtime/webgpu/gpu-glue.js";
const JOB = "runtime/webgpu/shaderxlat-job.js";
const POST = "runtime/webgpu/quality-post.js";
const TRANSLATOR = "runtime/webgpu/gaius-shaderxlat.wasm";

// A WebGPU glue double: init answers as told (one promise for every call, like the glue) and
// records what the boot asked for.
function fakeGlue({ready = true, hang = false, failure = "navigator.gpu is not available", adapter = null} = {}) {
  const calls = {init: [], abandon: [], install: [], taint: 0};
  const glue = {
    status: 0, failure: "", requested: false, translateError: "",
    init(canvas, options) {
      calls.init.push({canvas, options});
      if (this.initPromise) return this.initPromise;
      if (hang) return (this.initPromise = new Promise(() => {}));
      if (adapter) {
        return (this.initPromise = Promise.resolve().then(adapter).then((device) => {
          this.status = device ? 2 : -1;
          this.failure = device ? "" : "requestAdapter returned null";
          return !!device;
        }));
      }
      this.status = ready ? 2 : -1;
      if (!ready) this.failure = failure;
      return (this.initPromise = Promise.resolve(ready));
    },
    abandon(reason) {
      calls.abandon.push(reason);
      this.status = -1;
      this.failure = reason;
    },
    installTranslator(module, jobSource) {
      calls.install.push({module, jobSource});
      return module && module.kind === "translator";
    },
    taintCanvas() {
      calls.taint++;
      return true;
    },
  };
  return {glue, calls};
}

function createPage({search = "", site = null, portable = false, protocol = "https:", glue = null,
  fetchOk = true, inline = null, globals = {}} = {}) {
  const appended = [];
  const fetched = [];
  const element = (tag) => ({
    tagName: tag, attributes: {}, onload: null, onerror: null,
    setAttribute(name, value) { this.attributes[name] = value; },
  });
  const head = {attributes: {}, setAttribute(name, value) { this.attributes[name] = value; },
    appendChild(node) { appended.push(node); return node; }};
  const canvas = {id: "mc-canvas"};
  class FakeResponse {
    constructor(body, init) { this.body = body; this.ok = true; this.headers = new Map(Object.entries((init && init.headers) || {})); }
  }
  const window = {
    location: {protocol, search, href: `${protocol}//example.invalid/Gaius/${RENDERPEARL_PROFILE.id}/index.html${search}`},
    navigator: {hardwareConcurrency: 8},
    document: {
      readyState: "loading", head, documentElement: head, createElement: element,
      getElementById: (id) => (id === "mc-canvas" ? canvas : null),
      querySelector(selector) {
        const match = /^script\[data-gaius-boot-module="([^"]+)"\]$/.exec(selector);
        return match && inline && inline[match[1]] !== undefined ? {textContent: inline[match[1]]} : null;
      },
    },
    localStorage: {getItem: () => null, setItem() {}},
    performance: {now: () => 1, mark() {}, getEntriesByType: () => []},
    // Every wait of the boot script is cut to a few milliseconds.
    setTimeout: (fn, ms) => setTimeout(fn, Math.min(ms, 15)), clearTimeout, setInterval: () => 0, clearInterval() {},
    addEventListener() {},
    fetch: async (url) => {
      fetched.push(String(url));
      if (!fetchOk) return {ok: false, status: 404, body: null, headers: new Map()};
      return {ok: true, status: 200, body: {pipeThrough: (stream) => ({inflated: stream.format})},
        arrayBuffer: async () => new Uint8Array([0x1f, 0x8b, 0x08, 0x00]).buffer,
        headers: {get: () => "application/wasm"}};
    },
    Response: FakeResponse,
    Blob: class { constructor(parts) { this.parts = parts; } stream() {
      return {pipeThrough: (stream) => ({inflated: stream.format})};
    } },
    DecompressionStream: class { constructor(format) { this.format = format; } },
    WebAssembly: {
      validate: () => true,
      compileStreaming: async (response) => ({kind: "translator", from: response}),
      compile: async (bytes) => ({kind: "translator", from: bytes}),
      Suspending() {},
      promising() {},
    },
    URL, URLSearchParams, TextDecoder, DataView, Uint8Array, JSON, Promise, Object, Array, Map,
    MessageChannel, isSecureContext: true,
    __gaiusBootTimings: {classesLoaded: 1},
  };
  if (site) window.__gaiusSite = site;
  if (portable) window.__gaiusBootPortable = true;
  if (inline) window.__gaiusBootInline = Object.fromEntries(Object.keys(inline).map((name) => [name, true]));
  Object.assign(window, portable ? {
    __gaiusRuntimeDefault: "wasm",
    __gaiusPortableRuntimes: {wasm: {client: "classes.wasm"}},
  } : {
    __gaiusRuntimes: {wasm: {client: "classes.wasm"}},
    __gaiusRuntimeDefault: "wasm",
  }, globals);
  window.window = window;
  const context = vm.createContext(window);
  vm.runInContext(source, context, {filename: "gaius-boot.js"});
  // The glue and the job codec "load": the boot script waits for their tags.
  const loadWebGpu = () => {
    if (glue) context.GaiusWebGpu = glue;
    context.GaiusShaderXlatJob = {instantiateSync() { return {}; }};
    for (const node of appended) {
      const name = node.attributes["data-gaius-boot-module"];
      if ((name === GLUE || name === JOB || name === POST) && node.onload) node.onload();
    }
  };
  return {window: context, appended, fetched, canvas, loadWebGpu};
}

const modules = (page) => page.appended.map((node) => node.attributes["data-gaius-boot-module"]).filter(Boolean);
const checks = [];
const check = (name, fn) => checks.push([name, fn]);

check("the default renders with WebGL 2 and loads nothing of WebGPU", async () => {
  const {glue, calls} = fakeGlue();
  const page = createPage({glue});
  const selection = page.window.__gaiusGpuSelection;
  assert.equal(selection.requested, "webgl2");
  assert.equal(selection.source, "default");
  assert.equal(selection.selected, "webgl2");
  assert.ok(!modules(page).includes(GLUE), "the glue must not load for webgl2");
  page.loadWebGpu();
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(calls.init.length, 0);
  assert.equal(page.window.__gaiusBootTimings.gpu, "webgl2");
  assert.equal(page.window.__gaiusBoot.gpu, selection);
});

check("?gpu=webgpu waits for the device and installs the translator (dev)", async () => {
  const {glue, calls} = fakeGlue();
  const page = createPage({search: "?gpu=webgpu", glue});
  assert.deepEqual(modules(page).filter((name) => name.startsWith("runtime/webgpu/")), [GLUE, JOB, POST]);
  assert.deepEqual(page.fetched, ["https://example.invalid/Gaius/26.3/classes.wasm",
    "https://example.invalid/Gaius/26.3/" + TRANSLATOR],
    "translator compilation must start while the document is parsing");
  assert.equal(page.window.__gaiusGpuSelection.selected, "pending");
  page.loadWebGpu();
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(calls.init.length, 1, "the device is requested as soon as the glue loaded");
  assert.equal(glue.requested, true);
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(calls.init.length, 2, "beforeMain waits on the same init");
  assert.equal(calls.init[1].canvas, page.canvas, "init gets the game canvas");
  assert.equal(calls.init[0].options.inject, undefined);
  assert.equal(glue.requested, true);
  assert.deepEqual(page.fetched, ["https://example.invalid/Gaius/26.3/classes.wasm",
    "https://example.invalid/Gaius/26.3/" + TRANSLATOR]);
  assert.equal(calls.install.length, 1);
  assert.equal(calls.install[0].module.kind, "translator");
  assert.deepEqual({...calls.install[0].jobSource}, {jobUrl: "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/" + JOB});
  assert.equal(calls.abandon.length, 0);
  const selection = page.window.__gaiusGpuSelection;
  assert.equal(selection.selected, "webgpu");
  assert.equal(selection.fallback, false);
  assert.equal(typeof selection.initMs, "number");
  assert.equal(page.window.__gaiusBootTimings.gpu, "webgpu");
  assert.ok(page.window.__gaiusBoot.events.some((e) => e.event === "webgpu-ready"));
});

check("no WebGPU device: the reason is kept and the game starts on WebGL 2", async () => {
  const {glue, calls} = fakeGlue({ready: false});
  const page = createPage({search: "?gpu=webgpu", glue});
  page.loadWebGpu();
  await page.window.__gaiusBoot.beforeMain();
  const selection = page.window.__gaiusGpuSelection;
  assert.equal(selection.selected, "webgl2");
  assert.equal(selection.fallback, true);
  assert.equal(selection.reason, "navigator.gpu is not available");
  assert.equal(calls.install.length, 0);
  assert.equal(page.window.__gaiusBootTimings.gpuFallback, "navigator.gpu is not available");
  assert.ok(page.window.__gaiusBoot.events.some((e) => e.event === "gpu-fallback"));
});

check("an init past its budget is abandoned", async () => {
  const {glue, calls} = fakeGlue({hang: true});
  const page = createPage({search: "?gpu=webgpu", glue});
  page.loadWebGpu();
  await page.window.__gaiusBoot.beforeMain();
  assert.deepEqual(calls.abandon, ["init timed out after 5000 ms"]);
  assert.equal(page.window.__gaiusGpuSelection.selected, "webgl2");
  assert.equal(page.window.__gaiusGpuSelection.reason, "init timed out after 5000 ms");
});

check("a missing translator abandons WebGPU", async () => {
  const {glue, calls} = fakeGlue();
  const page = createPage({search: "?gpu=webgpu", glue, fetchOk: false});
  page.loadWebGpu();
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(calls.abandon.length, 1);
  assert.match(calls.abandon[0], /^translator unavailable/);
  assert.equal(page.window.__gaiusGpuSelection.selected, "webgl2");
  assert.ok(page.window.__gaiusBoot.events.some((e) => e.event === "gpu-translator-failed"));
});

check("a page without the glue falls back", async () => {
  const page = createPage({search: "?gpu=webgpu"});
  page.loadWebGpu();
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(page.window.__gaiusGpuSelection.selected, "webgl2");
  assert.equal(page.window.__gaiusGpuSelection.reason, "glue-missing");
});

check("an unknown ?gpu= is reported and the page default applies", async () => {
  const page = createPage({search: "?gpu=vulkan", globals: {__gaiusGpuDefault: "webgpu"}});
  const selection = page.window.__gaiusGpuSelection;
  assert.equal(selection.requested, "webgpu");
  assert.equal(selection.source, "page");
  assert.ok(page.window.__gaiusBoot.events.some((e) => e.event === "gpu-invalid" && e.detail === "vulkan"));
});

check("fallback-test switches reach the glue", async () => {
  const configure = fakeGlue();
  const page = createPage({search: "?gpu=webgpu&gpuInject=after-configure&gpuPipelines=eager&gpuAllowFallback=1",
    glue: configure.glue});
  page.loadWebGpu();
  await Promise.resolve();
  assert.equal(configure.calls.init.length, 0, "the after-configure test starts in beforeMain, with the canvas");
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(configure.calls.init.length, 1);
  assert.deepEqual({...configure.calls.init[0].options},
    {inject: "after-configure", pipelineMode: "eager", allowFallback: true});
  const eager = fakeGlue();
  const eagerPage = createPage({search: "?gpu=webgpu&gpuPipelines=eager", glue: eager.glue});
  eagerPage.loadWebGpu();
  await eagerPage.window.__gaiusBoot.beforeMain();
  assert.deepEqual({...eager.calls.init[0].options}, {inject: undefined, pipelineMode: "eager", allowFallback: false});

  const taint = fakeGlue();
  const glfw = createPage({search: "?gpuInject=taint-canvas", glue: taint.glue});
  assert.ok(modules(glfw).includes(GLUE), "the taint test needs the glue on a WebGL 2 page");
  glfw.loadWebGpu();
  await glfw.window.__gaiusBoot.beforeMain();
  assert.equal(taint.calls.taint, 1);
  assert.equal(taint.calls.init.length, 0, "taint-canvas alone does not start WebGPU");
  assert.equal(glfw.window.__gaiusGpuSelection.selected, "webgl2");
});

check("site: the hashed translator, or its gzip copy when the host does not compress", async () => {
  const site = {
    id: "0011223344556677", profile: RENDERPEARL_PROFILE.id, modules: [], kernels: {}, serviceWorker: "gaius-sw.js",
    assets: {
      "classes.wasm": "classes.aaaaaaaaaaaaaaaa.wasm",
      "gaius-wasm-boot.js": "gaius-wasm-boot.aaaaaaaaaaaaaaad.js",
      "gaius-wasm-gc-runtime.js": "gaius-wasm-gc-runtime.aaaaaaaaaaaaaaae.js",
      "gaius-jspi.js": "gaius-jspi.aaaaaaaaaaaaaaaf.js",
      [GLUE]: "runtime/webgpu/gpu-glue.1111111111111111.js",
      [JOB]: "runtime/webgpu/shaderxlat-job.2222222222222222.js",
      [TRANSLATOR]: "runtime/webgpu/gaius-shaderxlat.3333333333333333.wasm",
      [TRANSLATOR + ".gz"]: "runtime/webgpu/gaius-shaderxlat.wasm.4444444444444444.gz",
    },
  };
  const plain = fakeGlue();
  const page = createPage({search: "?gpu=webgpu", site, glue: plain.glue});
  assert.ok(page.appended.some((node) => node.src === site.assets[GLUE]));
  page.loadWebGpu();
  await page.window.__gaiusBoot.beforeMain();
  assert.deepEqual(page.fetched, ["https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/" + site.assets["classes.wasm"],
    "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/" + site.assets[TRANSLATOR]]);
  assert.equal(plain.calls.install[0].jobSource.jobUrl, "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/" + site.assets[JOB]);

  const gzip = fakeGlue();
  const gzipPage = createPage({search: "?gpu=webgpu", site, glue: gzip.glue,
    globals: {localStorage: {getItem: (key) => (key === "gaius.site.gzip.v1" ? "1" : null), setItem() {}}}});
  gzipPage.loadWebGpu();
  await gzipPage.window.__gaiusBoot.beforeMain();
  assert.deepEqual(gzipPage.fetched, ["https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/" + site.assets["classes.wasm"],
    "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/" + site.assets[TRANSLATOR + ".gz"]]);
  assert.equal(gzipPage.window.__gaiusGpuSelection.selected, "webgpu");
});

check("portable: inline scripts and the embedded translator", async () => {
  const {glue, calls} = fakeGlue();
  let loads = 0;
  const page = createPage({search: "?gpu=webgpu", portable: true, glue,
    inline: {[GLUE]: "/* glue */", [JOB]: "/* job codec */"},
    globals: {__gaiusPortableWebGpu: {translator: {load: () => { loads++; return new Uint8Array([0, 97, 115, 109]); }}}}});
  assert.ok(!modules(page).includes(GLUE), "inline scripts are not fetched");
  page.loadWebGpu();
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(loads, 1);
  assert.equal(page.fetched.length, 0);
  assert.equal(calls.install[0].module.kind, "translator");
  assert.equal(calls.install[0].jobSource.jobSource, "/* job codec */");
  assert.equal(page.window.__gaiusGpuSelection.selected, "webgpu");
});

check("portable: a translator announced after boot parsed is not cached as unavailable", async () => {
  // integ4-t6: the boot script ran before the portable bootstrap published
  // window.__gaiusPortableWebGpu; the parse-time compile resolved null and prepareGpu reused it.
  const {glue, calls} = fakeGlue();
  let loads = 0;
  const page = createPage({search: "?gpu=webgpu", portable: true, glue,
    inline: {[GLUE]: "/* glue */", [JOB]: "/* job codec */"}});
  page.window.__gaiusPortableWebGpu = {translator: {load: () => { loads++; return new Uint8Array([0, 97, 115, 109]); }}};
  page.loadWebGpu();
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(loads, 1);
  assert.equal(page.fetched.length, 0);
  assert.equal(calls.install[0].module.kind, "translator");
  assert.equal(page.window.__gaiusGpuSelection.selected, "webgpu");
});

const TOOLCHAIN = "gaius-shader-toolchain.js";
const toolchainConfig = {__gaiusWebGpuShaderToolchain: {loader: TOOLCHAIN + "?v=0123456789abcdef", version: "0123456789abcdef"},
  __gaiusProfileId: CANONICAL_PROFILE.id};
const toolchainTags = (page) => page.appended.filter((node) => node.attributes["data-gaius-boot-module"] === TOOLCHAIN);
// The loader script "runs": it publishes its ready promise, as gaius-shader-toolchain.js does.
function loadToolchain(page, ready = Promise.resolve({shadercBegin() {}})) {
  for (const node of toolchainTags(page)) {
    page.window.__gaiusShaderToolchainReady = ready;
    ready.catch(() => {});
    node.onload();
  }
}

check("a WasmGC GLSL front-end page loads the shader toolchain for WebGL2 and WebGPU", async () => {
  const gl = createPage({globals: toolchainConfig});
  assert.equal(toolchainTags(gl).length, 1, "WasmGC WebGL 2 needs the shader toolchain");
  const {glue, calls} = fakeGlue();
  const page = createPage({search: "?gpu=webgpu", glue, globals: toolchainConfig});
  const tags = toolchainTags(page);
  assert.equal(tags.length, 1, "the loader starts at parse time");
  assert.equal(tags[0].src, TOOLCHAIN + "?v=0123456789abcdef");
  assert.equal(tags[0].attributes["data-profile"], CANONICAL_PROFILE.id, "the loader's cache is named after the profile");
  page.loadWebGpu();
  let settled = false;
  const before = page.window.__gaiusBoot.beforeMain().then(() => { settled = true; });
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(settled, false, "beforeMain waits for the toolchain");
  loadToolchain(page);
  await before;
  assert.equal(calls.abandon.length, 0);
  assert.equal(page.window.__gaiusGpuSelection.selected, "webgpu");
  assert.ok(page.window.__gaiusBoot.events.some((e) => e.event === "webgpu-shader-toolchain" && e.detail === "loaded"));
});

check("a shader toolchain that cannot load abandons WebGPU", async () => {
  const missing = fakeGlue();
  const page = createPage({search: "?gpu=webgpu", glue: missing.glue, globals: toolchainConfig});
  page.loadWebGpu();
  toolchainTags(page)[0].onerror();
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(missing.calls.abandon.length, 1);
  assert.match(missing.calls.abandon[0], /^shader toolchain: could not load gaius-shader-toolchain\.js/);
  assert.equal(page.window.__gaiusGpuSelection.selected, "webgl2");

  const broken = fakeGlue();
  const brokenPage = createPage({search: "?gpu=webgpu", glue: broken.glue, globals: toolchainConfig});
  brokenPage.loadWebGpu();
  loadToolchain(brokenPage, Promise.reject(new Error("The Gaius shader compiler could not be loaded: trap")));
  await brokenPage.window.__gaiusBoot.beforeMain();
  assert.deepEqual(broken.calls.abandon, ["shader toolchain: The Gaius shader compiler could not be loaded: trap"]);

  const portable = fakeGlue();
  const portablePage = createPage({search: "?gpu=webgpu", portable: true, glue: portable.glue,
    inline: {[GLUE]: "/* glue */", [JOB]: "/* job codec */"},
    globals: {...toolchainConfig, __gaiusPortableWebGpu: {translator: {load: () => new Uint8Array([0, 97, 115, 109])}}}});
  portablePage.loadWebGpu();
  await portablePage.window.__gaiusBoot.beforeMain();
  assert.deepEqual(portable.calls.abandon, ["shader toolchain: this page form carries no shader toolchain"]);
});

check("a page that loads the toolchain itself gets no second loader", async () => {
  const {glue, calls} = fakeGlue();
  const page = createPage({search: "?gpu=webgpu", glue,
    globals: {...toolchainConfig, __gaiusShaderToolchainReady: new Promise(() => {})}});
  assert.equal(toolchainTags(page).length, 0);
  page.loadWebGpu();
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(calls.abandon.length, 0);
  assert.equal(page.window.__gaiusGpuSelection.selected, "webgpu");
});

check("site: the hashed shader toolchain and its module URLs", async () => {
  const site = {
    id: "0011223344556677", profile: CANONICAL_PROFILE.id, modules: [], kernels: {}, serviceWorker: "gaius-sw.js",
    assets: {
      "classes.wasm": "classes.aaaaaaaaaaaaaaaa.wasm",
      "gaius-wasm-boot.js": "gaius-wasm-boot.aaaaaaaaaaaaaaad.js",
      "gaius-wasm-gc-runtime.js": "gaius-wasm-gc-runtime.aaaaaaaaaaaaaaae.js",
      "gaius-jspi.js": "gaius-jspi.aaaaaaaaaaaaaaaf.js",
      [GLUE]: "runtime/webgpu/gpu-glue.1111111111111111.js",
      [JOB]: "runtime/webgpu/shaderxlat-job.2222222222222222.js",
      [TRANSLATOR]: "runtime/webgpu/gaius-shaderxlat.3333333333333333.wasm",
      [TOOLCHAIN]: "gaius-shader-toolchain.5555555555555555.js",
      "gaius-shaderc.js": "gaius-shaderc.6666666666666666.js",
      "gaius-shaderc.wasm": "gaius-shaderc.7777777777777777.wasm",
      "gaius-spvc.js": "gaius-spvc.8888888888888888.js",
      "gaius-spvc.wasm": "gaius-spvc.9999999999999999.wasm",
    },
  };
  const {glue, calls} = fakeGlue();
  const page = createPage({search: "?gpu=webgpu", site, glue, globals: toolchainConfig});
  const tags = toolchainTags(page);
  assert.equal(tags.length, 1);
  assert.equal(tags[0].src, site.assets[TOOLCHAIN]);
  assert.deepEqual({...page.window.__gaiusShaderToolchainUrls}, {
    shadercJs: site.assets["gaius-shaderc.js"], shadercWasm: site.assets["gaius-shaderc.wasm"],
    spvcJs: site.assets["gaius-spvc.js"], spvcWasm: site.assets["gaius-spvc.wasm"], version: "0123456789abcdef"});
  page.loadWebGpu();
  loadToolchain(page);
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(calls.abandon.length, 0);
  assert.equal(page.window.__gaiusGpuSelection.selected, "webgpu");
});

const profileDirectory = new URL("../versions/", import.meta.url);
const pagesProfiles = [];
for (const filename of await readdir(profileDirectory)) {
  if (!filename.endsWith(".json")) continue;
  const profile = JSON.parse(await readFile(new URL(filename, profileDirectory), "utf8"));
  if (profile.pages && profile.pages.enabled) pagesProfiles.push(profile.id);
}
assert.ok(pagesProfiles.length > 0);

function automaticSite(profile) {
  const names = ["classes.wasm.gz", "singleplayer-server.wasm.gz",
    "gaius-wasm-boot.js", "gaius-jspi.js", "gaius-wasm-worker.js", "gaius-wasm-gc-runtime.js",
    GLUE, JOB, POST, TRANSLATOR];
  return {id: "0011223344556677", profile, runtimePolicy: "wasm-only", modules: [], kernels: {},
    assets: Object.fromEntries(names.map((name) => [name, name])),
    inflate: {"classes.wasm": "classes.aaaaaaaaaaaaaaaa.wasm",
      "singleplayer-server.wasm": "singleplayer-server.bbbbbbbbbbbbbbbb.wasm"}};
}

function automaticPage(profile, {gc = true, jspi = true, tryTable = true, gpu = true,
  adapter = "ready", search = "", decompression = true, built = true, policy = "auto"} = {}) {
  let adapterRequests = 0;
  const requestAdapter = async () => {
    adapterRequests++;
    if (adapter === "reject") throw new Error("adapter unavailable");
    return adapter === "null" ? null : {};
  };
  const {glue, calls} = fakeGlue({adapter: requestAdapter});
  const site = automaticSite(profile);
  if (!built) delete site.assets["classes.wasm.gz"];
  site.runtimePolicy = policy;
  const page = createPage({site, search, glue, globals: {
    navigator: {hardwareConcurrency: 8, gpu: gpu ? {requestAdapter} : undefined},
    WebAssembly: {validate: (bytes) => bytes.includes(0xfb) ? gc : tryTable,
      Suspending: jspi ? function Suspending() {} : undefined,
      promising: jspi ? function promising() {} : undefined,
      compileStreaming: async () => ({kind: "translator"}),
      compile: async () => ({kind: "translator"})},
    DecompressionStream: decompression ? class { constructor(format) { this.format = format; } } : undefined,
  }});
  page.loadWebGpu();
  return {...page, calls, adapterRequests: () => adapterRequests};
}

for (const profile of pagesProfiles) {
  for (const gc of [false, true]) for (const jspi of [false, true]) {
    for (const tryTable of [false, true]) for (const gpu of [false, true]) {
      check(`${profile} automatic gc=${gc} jspi=${jspi} eh=${tryTable} gpu=${gpu}`, async () => {
        const page = automaticPage(profile, {gc, jspi, tryTable, gpu});
        const wasm = gc && jspi && tryTable;
        assert.equal(page.window.__gaiusRuntimeSelection.selected, wasm ? "wasm" : "unsupported");
        if (wasm) {
          await page.window.__gaiusBoot.beforeMain();
          assert.equal(page.window.__gaiusGpuSelection.selected, gpu ? "webgpu" : "webgl2");
          assert.equal(page.window.__gaiusBoot.serverRuntime, "wasm");
          assert.equal(page.adapterRequests(), gpu ? 1 : 0);
        } else {
          assert.equal(page.window.__gaiusGpuSelection, undefined);
          assert.equal(page.adapterRequests(), 0);
          assert.equal(page.appended.length, 0);
          assert.equal(page.fetched.length, 0);
        }
      });
    }
  }
  for (const adapter of ["null", "reject"]) {
    check(`${profile} automatic adapter ${adapter} keeps wasm with WebGL 2`, async () => {
      const page = automaticPage(profile, {adapter});
      await page.window.__gaiusBoot.beforeMain();
      assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm");
      assert.equal(page.window.__gaiusGpuSelection.selected, "webgl2");
      assert.equal(page.adapterRequests(), 1);
    });
  }
  for (const missing of ["decompression", "built"]) {
    check(`${profile} automatic missing ${missing} is unsupported`, async () => {
      const page = automaticPage(profile, {[missing]: false});
      assert.equal(page.window.__gaiusRuntimeSelection.selected, "unsupported");
      assert.equal(page.window.__gaiusGpuSelection, undefined);
      assert.equal(page.appended.length, 0);
      assert.equal(page.fetched.length, 0);
    });
  }
  check(`${profile} a wasm-only site runs the Wasm server without being asked`, async () => {
    const page = automaticPage(profile, {policy: "wasm-only"});
    await page.window.__gaiusBoot.beforeMain();
    assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm");
    assert.equal(page.window.__gaiusBoot.serverRuntime, "wasm");
  });
  check(`${profile} a JavaScript runtime query cannot change the WasmGC release`, async () => {
    const page = automaticPage(profile, {search: "?runtime=js"});
    await page.window.__gaiusBoot.beforeMain();
    assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm");
    assert.equal(page.window.__gaiusGpuSelection.selected, "webgpu");
    assert.equal(page.window.__gaiusBoot.serverRuntime, "wasm");
  });
  check(`${profile} a late Wasm failure is terminal and never changes runtimes`, async () => {
    const page = automaticPage(profile);
    page.window.__gaiusClientReady = Promise.reject(new Error("module unavailable"));
    page.window.__gaiusClientReady.catch(() => {});
    await assert.rejects(page.window.__gaiusBoot.beforeMain(), /module unavailable/);
    assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm");
    assert.equal(page.window.__gaiusBoot.serverRuntime, "wasm");
    assert.equal(modules(page).filter((name) => /classes\.js|singleplayer-server\.js/.test(name)).length, 0);
  });
}

// The portable page (file://, no site descriptor) runs the WebAssembly GC client and inlines the
// WebGPU glue: without ?gpu= it tries WebGPU where the browser offers it, and when it does not it
// says why (owner report, 2026-10-23: a Mac opened the portable page on WebGL 2 with no reason).
function portablePage({gpu = true, wasm = true, search = "", translator = true} = {}) {
  let adapterRequests = 0;
  const requestAdapter = async () => { adapterRequests++; return {}; };
  const {glue, calls} = fakeGlue({adapter: requestAdapter});
  const logs = [];
  const page = createPage({search, portable: true, protocol: "file:", glue,
    inline: {[GLUE]: "/* glue */", [JOB]: "/* job codec */", [POST]: "/* post */"},
    globals: {
      __gaiusRuntimeDefault: "wasm", __gaiusPortableRuntimes: {wasm: {}},
      navigator: {hardwareConcurrency: 8, gpu: gpu ? {requestAdapter} : undefined},
      WebAssembly: {validate: () => wasm, Suspending: function Suspending() {}, promising: function promising() {},
        compileStreaming: async () => ({kind: "translator"}), compile: async () => ({kind: "translator"})},
      console: {info: (text) => logs.push(["info", text]), warn: (text) => logs.push(["warn", text]),
        log: (text) => logs.push(["log", text]), error() {}},
    }});
  if (translator) {
    page.window.__gaiusPortableWebGpu = {translator: {load: () => new Uint8Array([0, 97, 115, 109])}};
  }
  page.loadWebGpu();
  return {...page, calls, logs, adapterRequests: () => adapterRequests};
}
const graphicsLogs = (page) => page.logs.filter(([, text]) => String(text).startsWith("Gaius graphics: "));

check("portable: no ?gpu tries WebGPU where the browser offers it", async () => {
  const page = portablePage();
  const selection = page.window.__gaiusGpuSelection;
  assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm");
  assert.equal(selection.requested, "webgpu");
  assert.equal(selection.source, "portable");
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(selection.selected, "webgpu");
  assert.equal(page.adapterRequests(), 1);
  assert.equal(page.fetched.length, 0);
  assert.ok(graphicsLogs(page).some(([, text]) => /WebGPU ready/.test(text)), JSON.stringify(page.logs));
});

check("portable: without navigator.gpu it stays on WebGL 2 and says why", async () => {
  const page = portablePage({gpu: false});
  const selection = page.window.__gaiusGpuSelection;
  assert.equal(selection.requested, "webgl2");
  assert.equal(selection.source, "portable");
  assert.equal(selection.reason, "webgpu unavailable: navigator.gpu is not available");
  assert.ok(!modules(page).includes(GLUE));
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(selection.selected, "webgl2");
  const lines = graphicsLogs(page);
  assert.equal(lines.length, 1);
  assert.equal(lines[0][0], "warn");
  assert.match(lines[0][1], /WebGPU not used: webgpu unavailable: navigator\.gpu is not available/);
  assert.ok(page.window.__gaiusBoot.events.some((e) => e.event === "gpu-unavailable"));
});

check("portable: a GLSL front-end page without its toolchain stays on WebGL 2 and says why", async () => {
  const page = portablePage({});
  // Rebuilt with the toolchain config of a blaze3d (26.2, 1.21.11) page.
  const {glue} = fakeGlue();
  const glsl = createPage({portable: true, protocol: "file:", glue,
    inline: {[GLUE]: "/* glue */", [JOB]: "/* job codec */", [POST]: "/* post */"},
    globals: {...toolchainConfig, __gaiusRuntimeDefault: "wasm", __gaiusPortableRuntimes: {wasm: {}},
      navigator: {hardwareConcurrency: 8, gpu: {requestAdapter: async () => ({})}},
      WebAssembly: {validate: () => true, Suspending: function Suspending() {}, promising: function promising() {}},
      console: {info() {}, warn() {}, error() {}}}});
  assert.equal(page.window.__gaiusGpuSelection.requested, "webgpu");
  assert.equal(glsl.window.__gaiusGpuSelection.requested, "webgl2");
  assert.equal(glsl.window.__gaiusGpuSelection.reason, "webgpu unavailable: this page form carries no shader toolchain");
});

check("portable: missing WasmGC is unsupported", async () => {
  const page = portablePage({wasm: false});
  assert.equal(page.window.__gaiusRuntimeSelection.selected, "unsupported");
  assert.equal(page.window.__gaiusGpuSelection, undefined);
  assert.equal(page.appended.length, 0);
  assert.equal(page.fetched.length, 0);
});

check("portable: ?gpu=webgl2 is honoured and logged as a choice", async () => {
  const page = portablePage({search: "?gpu=webgl2"});
  const selection = page.window.__gaiusGpuSelection;
  assert.equal(selection.requested, "webgl2");
  assert.equal(selection.source, "url");
  assert.equal(selection.reason, "url-webgl2");
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(page.adapterRequests(), 0);
  assert.equal(graphicsLogs(page)[0][0], "info");
});

check("portable: a device failure falls back with the glue's reason on the console", async () => {
  const {glue} = fakeGlue({ready: false, failure: "requestAdapter returned null"});
  const logs = [];
  const page = createPage({portable: true, protocol: "file:", glue,
    inline: {[GLUE]: "/* glue */", [JOB]: "/* job codec */", [POST]: "/* post */"},
    globals: {
      __gaiusRuntimeDefault: "wasm", __gaiusPortableRuntimes: {wasm: {}},
      navigator: {hardwareConcurrency: 8, gpu: {requestAdapter: async () => null}},
      WebAssembly: {validate: () => true, Suspending: function Suspending() {}, promising: function promising() {},
        compile: async () => ({kind: "translator"})},
      console: {info: (text) => logs.push(["info", text]), warn: (text) => logs.push(["warn", text]), error() {}},
    }});
  page.window.__gaiusPortableWebGpu = {translator: {load: () => new Uint8Array([0, 97, 115, 109])}};
  page.loadWebGpu();
  await page.window.__gaiusBoot.beforeMain();
  const selection = page.window.__gaiusGpuSelection;
  assert.equal(selection.selected, "webgl2");
  assert.equal(selection.fallback, true);
  assert.equal(selection.reason, "requestAdapter returned null");
  assert.ok(logs.some(([level, text]) => level === "warn"
    && text === "Gaius graphics: WebGPU failed, falling back to WebGL 2: requestAdapter returned null"), JSON.stringify(logs));
});

let failed = 0;
for (const [name, fn] of checks) {
  try {
    await fn();
    console.log(`ok   ${name}`);
  } catch (error) {
    failed++;
    console.log(`FAIL ${name}\n${error && error.stack || error}`);
  }
}
if (failed) {
  console.log(`${failed} of ${checks.length} boot GPU checks failed`);
  process.exit(1);
}
console.log(`all ${checks.length} boot GPU checks passed`);
