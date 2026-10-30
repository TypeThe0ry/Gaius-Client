#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(process.env.WASM_WORKER_SOURCE ||
  new URL("../web/runtime/wasmgc/gaius-wasm-worker.js", import.meta.url), "utf8");
const start = source.indexOf("  function installPump(exports) {");
const end = source.indexOf("\n  function publishExports", start);
assert.ok(start >= 0 && end > start, "could not extract installPump");
const installPump = source.slice(start, end);
const context = vm.createContext({console, Date, Error, Number, String});
vm.runInContext(`
  const root = globalThis;
  const callbacks = [];
  const scheduler = {post(callback) { callbacks.push(callback);}};
  const stats = root.__gaiusNetworkStats = {};
  const post = () => {};
  const describe = (error) => String(error && error.message || error);
  ${installPump}
  let calls = 0;
  const exports = {pumpIntegratedServerNetworkInput() {
    calls++;
    if (calls === 1) root.__gaiusStartIntegratedServerPump();
    if (calls === 1) throw new Error("pump failed");
  }};
  installPump(exports);
  root.__gaiusStartIntegratedServerPump();
  callbacks.shift()();
  callbacks.shift()();
  root.__test = {callbacks, calls, failed: root.__gaiusIntegratedServerPumpFailed === true};
`, context);
const result = context.__test;
assert.equal(result.failed, true);
assert.equal(result.calls, 1,
  "a re-entrant callback already queued before failure must be dropped");
console.log("GAIUS_WASM_WORKER_PUMP_FAILURE_TEST PASS");
