#!/usr/bin/env node
// Runs the JSPI semantic suite (port/tests/jspi/build.sh output) in Node with the production
// gaius-jspi.js and patched TeaVM loader, and fails unless every check that ran passed.
//
//   node port/tests/jspi/run-node.mjs <suite-dir> [--json out.json]
//
// Node needs --experimental-wasm-jspi (the runner re-executes itself with it). Two probes are
// skipped in Node (params.node): its MessagePort runs messages posted from a message handler
// back to back and starves timers, so a Thread.yield() loop never sees a timer there; Chrome
// (run-chrome.mjs) runs every check.
import {spawnSync} from "node:child_process";
import {existsSync, writeFileSync} from "node:fs";
import {createRequire} from "node:module";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const args = process.argv.slice(2);
const dir = args.find((arg) => !arg.startsWith("--") && args[args.indexOf(arg) - 1] !== "--json");
const jsonIndex = args.indexOf("--json");
const jsonOut = jsonIndex >= 0 ? args[jsonIndex + 1] : null;
if (!dir) {
  console.error("usage: run-node.mjs <suite-dir> [--json out.json]");
  process.exit(2);
}
if (typeof WebAssembly.Suspending !== "function") {
  const child = spawnSync(process.execPath, ["--experimental-wasm-jspi", fileURLToPath(import.meta.url), ...args],
    {stdio: "inherit"});
  process.exit(child.status ?? 1);
}
const suite = resolve(dir);
for (const name of ["classes.wasm", "gaius-wasm-gc-runtime.js", "gaius-jspi.js"]) {
  if (!existsSync(join(suite, name))) {
    console.error(`run-node: ${join(suite, name)} is missing (run port/tests/jspi/build.sh first)`);
    process.exit(2);
  }
}
const require = createRequire(import.meta.url);
require(join(suite, "gaius-wasm-gc-runtime.js"));
require(join(suite, "gaius-jspi.js"));
globalThis.__jspiSuiteParams = {node: 1};
const timeoutMs = 120_000;
const errors = [];
let jspi = null;
const teavm = await globalThis.TeaVM.wasmGC.load(join(suite, "classes.wasm"), {
  nodejs: true,
  installImports(imports) {
    const scheduler = globalThis.GaiusJspi.createScheduler();
    globalThis.__gaiusJspiScheduler = scheduler;
    jspi = globalThis.GaiusJspi.installJspi(imports, scheduler, () => teavm.instance.exports,
      (error) => console.log("THREAD ERROR", String(error && error.message || error)));
    globalThis.GaiusJspi.routeEventQueue(imports, scheduler);
  },
});
const started = Date.now();
jspi.startMain([]).catch((error) => errors.push("main failed: " + String(error && error.stack || error)));
while (!(globalThis.__jspiSuite && globalThis.__jspiSuite.done) && errors.length === 0
    && Date.now() - started < timeoutMs) {
  await new Promise((done) => setTimeout(done, 100));
}
const state = globalThis.__jspiSuite || {results: {}, log: []};
const results = state.results || {};
const checks = Object.entries(results).filter(([key]) => /^(semantic|probes|wp2)\./.test(key));
const failed = checks.filter(([, value]) => !/^(PASS|SKIP)/.test(String(value)));
const ok = state.done && errors.length === 0 && failed.length === 0;
const report = {runner: "node " + process.version, done: !!state.done, summary: state.summary || null,
  elapsedMs: Date.now() - started, failed: failed.map(([key, value]) => `${key} ${value}`), errors,
  results, stats: globalThis.__gaiusJspiScheduler ? globalThis.__gaiusJspiScheduler.stats : null,
  logTail: (state.log || []).slice(-20)};
if (jsonOut) {
  writeFileSync(jsonOut, JSON.stringify(report, null, 1) + "\n");
}
console.log(`JSPI_SUITE ${ok ? "PASS" : "FAIL"} node: ${state.summary || "not done"}`
  + (failed.length ? `; failed: ${report.failed.join("; ")}` : "")
  + (errors.length ? `; errors: ${errors.join("; ")}` : ""));
process.exit(ok ? 0 : 1);
