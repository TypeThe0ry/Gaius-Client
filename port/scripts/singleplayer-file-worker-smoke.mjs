// The integrated server Worker of a page opened from disk (file://). Such a Worker can neither
// fetch nor import its sibling files: BrowserSingleplayerClient starts it from a blob loader
// that publishes the files the page read from its payloads as self.__gaiusWorkerFiles
// ({name: ArrayBuffer}) and then imports server-worker-bootstrap.js. This smoke runs the real
// bootstrap in a Worker whose importScripts only accepts blob: URLs (as Chrome does for a disk
// page's Worker) and checks that it takes
//   - the JavaScript server (start message serverScriptUrl file:///.../singleplayer-server.js?v=..),
//   - the WebAssembly GC server: singleplayer-server.wasm as bytes for GaiusWasmWorker.compile and
//     the runtime scripts listed by URL in wasmRuntimeScripts,
//   - the worldgen kernel scripts when the page's kernel port lists none,
// from those files, and that a portable page's serverWasmGzipData compiles as a streamed Response.
//
//   node port/scripts/singleplayer-file-worker-smoke.mjs
import {RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {gzipSync} from "node:zlib";
import {MessageChannel, Worker} from "node:worker_threads";

const bootstrap = await readFile(new URL("../web/singleplayer/server-worker-bootstrap.js", import.meta.url), "utf8");
const encoder = new TextEncoder();
const script = (source) => encoder.encode(source).buffer;

const harness = `
const {parentPort, workerData} = require("node:worker_threads");
const {resolveObjectURL} = require("node:buffer");
const vm = require("node:vm");
const {performance} = require("node:perf_hooks");
process.on("uncaughtException", (error) => parentPort.postMessage({type: "test-error", detail: String(error && error.stack || error)}));
process.on("unhandledRejection", (error) => parentPort.postMessage({type: "test-error", detail: String(error && error.stack || error)}));
globalThis.performance = performance;
globalThis.location = new URL("blob:null/5b1c0e43-0000-4000-8000-000000000000");
globalThis.postMessage = (message, transfer) => {
  try { parentPort.postMessage(message, transfer || []); } catch (error) { parentPort.postMessage({type: String(message && message.type)}); }
};
globalThis.close = () => parentPort.postMessage({type: "harness-close"});
const imported = [];
globalThis.importScripts = (url) => {
  const text = String(url);
  if (!text.startsWith("blob:")) {
    imported.push({url: text, refused: true});
    throw new Error("NetworkError: a Worker started from disk cannot import " + text);
  }
  const blob = resolveObjectURL(text);
  if (!blob) throw new Error("revoked blob " + text);
  // importScripts is synchronous; the harness reads the blob through its sync-capable copy.
  const source = globalThis.__harnessBlobText.get(text);
  if (source === undefined) throw new Error("unknown blob " + text);
  imported.push({url: "blob"});
  vm.runInThisContext(source, {filename: text});
};
// URL.createObjectURL of a Blob made from an ArrayBuffer or a string: remember its text so the
// synchronous importScripts above can run it.
globalThis.__harnessBlobText = new Map();
const NativeBlob = Blob;
globalThis.Blob = class extends NativeBlob {
  constructor(parts, options) {
    super(parts, options);
    this.__text = parts.map((part) => typeof part === "string" ? part
      : new TextDecoder().decode(part instanceof ArrayBuffer ? new Uint8Array(part) : part)).join("");
  }
};
const nativeCreate = URL.createObjectURL;
URL.createObjectURL = (blob) => {
  const url = nativeCreate(blob);
  if (blob && typeof blob.__text === "string") globalThis.__harnessBlobText.set(url, blob.__text);
  return url;
};
const listeners = [];
globalThis.addEventListener = (type, listener) => { if (type === "message") listeners.push(listener); };
globalThis.__gaiusWorkerFiles = workerData.files;
globalThis.indexedDB = {
  open() {
    const request = {};
    setTimeout(() => {
      request.result = {
        objectStoreNames: {contains: () => true},
        close() {},
        transaction() {
          const transaction = {};
          const store = {delete() {}, put() {}, get() {
            const request = {};
            queueMicrotask(() => { request.result = undefined; request.onsuccess?.(); });
            return request;
          }, openCursor() {
            const cursor = {};
            queueMicrotask(() => { cursor.result = null; cursor.onsuccess?.(); });
            return cursor;
          }};
          transaction.objectStore = () => store;
          transaction.abort = () => {};
          setTimeout(() => transaction.oncomplete?.(), 0);
          return transaction;
        },
      };
      request.onsuccess?.();
    }, 0);
    return request;
  },
};
${bootstrap}
parentPort.on("message", (message) => {
  if (message && message.type === "test-imports") {
    parentPort.postMessage({type: "test-imports-result", imported, compiled: globalThis.__harnessCompiled || null,
      kernelScripts: globalThis.__harnessKernelScripts || []});
    return;
  }
  const event = {data: message, ports: message && message.port ? [message.port] : []};
  globalThis.onmessage(event);
  for (const listener of listeners) listener(event);
});
`;

// Fakes of the runtime pieces the page hands over.
const jsServer = script(`
  globalThis.main = () => postMessage({type: "main-start-observed", runtime: "js"});
  globalThis.__gaiusStartIntegratedServerPump = () => {};
  globalThis.stopIntegratedServer = () => {};
  globalThis.isIntegratedServerStopped = () => true;
`);
const wasmRuntime = {
  "gaius-wasm-gc-runtime.js": script(`globalThis.TeaVM = {wasmGC: {}};`),
  "gaius-jspi.js": script(`globalThis.GaiusJspi = {};`),
  "gaius-wasm-worker.js": script(`
    globalThis.GaiusWasmWorker = {
      compile(source) {
        const describe = (value) => value instanceof ArrayBuffer ? {kind: "bytes", bytes: value.byteLength}
          : (typeof Response !== "undefined" && value instanceof Response) ? {kind: "response",
            type: value.headers.get("Content-Type")}
          : (typeof WebAssembly !== "undefined" && value instanceof WebAssembly.Module)
            ? {kind: "module"}
          : {kind: typeof value, value: String(value).slice(0, 200)};
        globalThis.__harnessCompiled = describe(source);
        if (typeof Response !== "undefined" && source instanceof Response) {
          return source.arrayBuffer().then((bytes) => { globalThis.__harnessCompiled.bytes = bytes.byteLength; });
        }
        return Promise.resolve();
      },
      instantiate() {
        globalThis.__gaiusStartIntegratedServerPump = () => {};
        globalThis.stopIntegratedServer = () => {};
        globalThis.isIntegratedServerStopped = () => true;
        return Promise.resolve({phase: "instantiated"});
      },
      startMain() {
        postMessage({type: "main-start-observed", runtime: "wasm"});
        return Promise.resolve();
      },
      info() { return {phase: "test"}; },
    };
  `),
};
const kernelScripts = {
  "kernels/worldgen-job.js": script(`(globalThis.__harnessKernelScripts ||= []).push("worldgen-job");`),
  "kernels/worldgen-kernel.js": script(`(globalThis.__harnessKernelScripts ||= []).push("worldgen-kernel");`),
};
const wasmModule = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]);
const DIST = "file:///D:/Gaius/dist/";

async function run(label, files, startFields, {kernelPort = false} = {}) {
  const worker = new Worker(harness, {eval: true, workerData: {files}});
  const events = [];
  let wake = null;
  worker.on("message", (message) => { events.push(message); if (wake) wake(); });
  worker.on("error", (error) => events.push({type: "test-error", detail: String(error && error.stack || error)}));
  const waitFor = async (type, millis = 5000) => {
    const deadline = Date.now() + millis;
    for (;;) {
      const failure = events.find((event) => event && (event.type === "test-error" || event.type === "bootstrap-crash"
        || event.type === "crash" || event.type === "runtime-error"));
      if (failure) throw new Error(`${label}: ${failure.type} ${failure.detail || ""}`);
      const found = events.find((event) => event && event.type === type);
      if (found) return found;
      if (Date.now() > deadline) {
        throw new Error(`${label}: timed out waiting for ${type}; saw ${events.map((event) => event.type).join(", ")}`);
      }
      await new Promise((resolve) => { const timer = setTimeout(resolve, 10); wake = () => { clearTimeout(timer); resolve(); }; });
    }
  };
  const channel = new MessageChannel();
  worker.postMessage(Object.assign({
    type: "start", sessionId: "6123456789abcdef0123456789abcdef", launchGeneration: "1", worldId: "file-world",
    newWorld: true, profileId: RENDERPEARL_PROFILE.id, worldVersion: 5023, storageSchema: 2, storageDatabaseName: "gaius-fs-v2-" + RENDERPEARL_PROFILE.id + "",
    storagePrefix: "gaius.fs.v2:" + RENDERPEARL_PROFILE.id + ":", storageOpfsDirectory: "regions-v2-" + RENDERPEARL_PROFILE.id + "", renderDistance: 8,
    simulationDistance: 6, port: channel.port2,
  }, startFields), [channel.port2]);
  if (kernelPort) {
    const kernel = new MessageChannel();
    worker.postMessage({type: "gaius-kernel-port", port: kernel.port2, kernelScripts: [], worldgenKernel: {},
      lightKernel: {enabled: true}}, [kernel.port2]);
  }
  const started = await waitFor("main-start-observed");
  await waitFor("runtime-ready");
  worker.postMessage({type: "test-imports"});
  const result = await waitFor("test-imports-result");
  await worker.terminate();
  channel.port1.close();
  return {started, result};
}

// The JavaScript server, named by the page's sibling URL with a cache query.
{
  const {started, result} = await run("js", {"singleplayer-server.js": jsServer}, {
    serverScriptUrl: DIST + "singleplayer-server.js?v=0123abcd",
    serverScriptGzipUrl: DIST + "singleplayer-server.js.gz?v=0123abcd",
  });
  assert.equal(started.runtime, "js");
  assert.ok(result.imported.length >= 1 && result.imported.every((entry) => entry.url === "blob"),
    "the disk Worker imported something other than a handed-over file: " + JSON.stringify(result.imported));
}

// The WebAssembly GC server: module bytes and runtime scripts named by sibling URLs, plus the
// worldgen kernel scripts the page's kernel port does not list.
{
  const files = Object.assign({"singleplayer-server.wasm": wasmModule.slice().buffer}, wasmRuntime, kernelScripts);
  const {started, result} = await run("wasm", files, {
    serverRuntime: "wasm",
    serverWasmUrl: DIST + "singleplayer-server.wasm",
    wasmRuntimeScripts: ["gaius-wasm-gc-runtime.js", "gaius-jspi.js", "gaius-wasm-worker.js"]
      .map((name) => ({name, url: DIST + name})),
  }, {kernelPort: true});
  assert.equal(started.runtime, "wasm");
  assert.deepEqual(result.compiled, {kind: "bytes", bytes: wasmModule.byteLength},
    "singleplayer-server.wasm did not reach GaiusWasmWorker.compile as the handed-over bytes");
  assert.ok(result.imported.every((entry) => entry.url === "blob"),
    "the disk Worker imported something other than a handed-over file: " + JSON.stringify(result.imported));
  assert.deepEqual(result.kernelScripts, ["worldgen-job", "worldgen-kernel"],
    "the worldgen kernel scripts were not imported from the handed-over files");
}

// A page-side prewarm may hand the Worker a structured-cloned WebAssembly.Module. The Worker
// must prefer that compiled module without requiring a second bytes payload.
{
  const compiledModule = await WebAssembly.compile(wasmModule);
  const {started, result} = await run("wasm-module", {}, {
    serverRuntime: "wasm",
    serverWasmModule: compiledModule,
    wasmRuntimeScripts: ["gaius-wasm-gc-runtime.js", "gaius-jspi.js", "gaius-wasm-worker.js"]
      .map((name) => ({name, source: new TextDecoder().decode(new Uint8Array(wasmRuntime[name]))})),
  });
  assert.equal(started.runtime, "wasm");
  assert.deepEqual(result.compiled, {kind: "module"},
    "the prewarmed WebAssembly.Module did not reach the server runtime");
}

// A portable page passes the module's gzip bytes (serverWasmGzipData) with inline script sources.
{
  const source = (name) => new TextDecoder().decode(new Uint8Array(wasmRuntime[name]));
  const gzip = gzipSync(wasmModule);
  const {started, result} = await run("portable-wasm", {}, {
    serverRuntime: "wasm",
    serverWasmGzipData: gzip.buffer.slice(gzip.byteOffset, gzip.byteOffset + gzip.byteLength),
    wasmRuntimeScripts: ["gaius-wasm-gc-runtime.js", "gaius-jspi.js", "gaius-wasm-worker.js"]
      .map((name) => ({name, source: source(name)})),
  });
  assert.equal(started.runtime, "wasm");
  assert.equal(result.compiled.kind, "response");
  assert.equal(result.compiled.type, "application/wasm");
  assert.equal(result.compiled.bytes, wasmModule.byteLength, "the gzip module did not inflate to the module");
}

console.log("singleplayer file Worker smoke passed (js and wasm servers from handed-over files, portable gzip module)");
