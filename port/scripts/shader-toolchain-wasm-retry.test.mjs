import assert from "node:assert/strict";
import {test} from "node:test";
import loader from "../wasm/shader-toolchain/loader/gaius-shader-toolchain.js";
const {__testCompileWasm} = loader;

const original = {fetch: globalThis.fetch, wasm: WebAssembly, setTimeout: globalThis.setTimeout};
const response = () => ({ok: true, headers: {get: () => "application/wasm"}, arrayBuffer: async () => new Uint8Array([0])});

test("shader toolchain retries streaming with reload and then buffers", async () => {
  const calls = []; let streamFailures = 3; let now = 0;
  globalThis.fetch = async (_url, options) => { calls.push(["fetch", options.cache]); return response(); };
  WebAssembly.compileStreaming = async () => { calls.push(["stream"]); if (streamFailures--) throw new Error("network abort"); return "streamed"; };
  WebAssembly.compile = async () => { calls.push(["compile"]); return "buffered"; };
  globalThis.setTimeout = (resolve, delay) => { now += delay; resolve(); return 0; };
  try {
    assert.equal(await __testCompileWasm("x"), "buffered");
    assert.deepEqual(calls, [["fetch", "force-cache"], ["stream"], ["fetch", "reload"], ["stream"], ["fetch", "reload"], ["stream"], ["fetch", "reload"], ["compile"]]);
    assert.equal(now, 1200);
  } finally { globalThis.fetch = original.fetch; WebAssembly.compileStreaming = original.wasm.compileStreaming; WebAssembly.compile = original.wasm.compile; globalThis.setTimeout = original.setTimeout; }
});

test("shader toolchain succeeds on the first streaming attempt", async () => {
  globalThis.fetch = async (_url, options) => { assert.equal(options.cache, "force-cache"); return response(); };
  WebAssembly.compileStreaming = async () => "streamed";
  try { assert.equal(await __testCompileWasm("x"), "streamed"); }
  finally { globalThis.fetch = original.fetch; WebAssembly.compileStreaming = original.wasm.compileStreaming; }
});
