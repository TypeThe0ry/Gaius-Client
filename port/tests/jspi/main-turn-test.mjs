#!/usr/bin/env node
// Regression test of the run queue (gaius-jspi.js createScheduler) for the activation of the main
// thread (installJspi startMain): main parks, the run queue resumes it (a drained wake, as when
// the integrated server's main waits in Thread.yield for its IndexedDB fallback storage) and main
// returns. The drain must see that turn end and run the work queued behind it (the server thread
// main started). Before the fix the drain waited for the finished turn microtask by microtask,
// forever, and the Worker never ran another task.
//
//   node port/tests/jspi/main-turn-test.mjs
//   node port/tests/jspi/main-turn-test.mjs --no-jspi  # exercise the missing-JSPI gate
//
// The module is hand-assembled: gaius.jspi.run(handle) calls gaiusJspi.park(handle, 0) once and
// returns. A spinning drain blocks the event loop of its process, so the check runs in a child
// process with a deadline.
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

const CHILD = "--main-turn-child";
const JSPI_FLAG = process.argv.includes("--no-jspi")
  ? "--no-experimental-wasm-jspi" : "--experimental-wasm-jspi";
const ALLOW_SKIP_REASON = (process.env.GAIUS_MAIN_TURN_ALLOW_SKIP_REASON || "").trim();

if (!process.argv.includes(CHILD)) {
  const child = spawnSync(process.execPath,
    [JSPI_FLAG, fileURLToPath(import.meta.url), CHILD],
    {encoding: "utf8", timeout: 20000});
  const output = `${child.stdout || ""}${child.stderr || ""}`.trim();
  if (child.error && child.error.code === "ETIMEDOUT") {
    console.error("MAIN_TURN_TEST FAIL: the run queue never yielded after main returned (timeout)");
    process.exit(1);
  }
  if (child.status === 0 && /MAIN_TURN_TEST_CHILD_SKIP/.test(output)) {
    if (ALLOW_SKIP_REASON.length === 0) {
      console.error(
        "MAIN_TURN_TEST FAIL: this Node has no JSPI (WebAssembly.Suspending/promising); " +
        "use Node 24 with --experimental-wasm-jspi, or set " +
        "GAIUS_MAIN_TURN_ALLOW_SKIP_REASON to an explicit reason for an allowed skip",
      );
      process.exit(1);
    }
    console.log(`MAIN_TURN_TEST SKIP (allowed): ${ALLOW_SKIP_REASON}`);
    process.exit(0);
  }
  if (child.status !== 0 || !/MAIN_TURN_TEST_CHILD_OK/.test(output)) {
    console.error(`MAIN_TURN_TEST FAIL (exit ${child.status}):\n${output}`);
    process.exit(1);
  }
  console.log("MAIN_TURN_TEST PASS");
  process.exit(0);
}

if (typeof WebAssembly.Suspending !== "function" || typeof WebAssembly.promising !== "function") {
  console.log("MAIN_TURN_TEST_CHILD_SKIP");
  process.exit(0);
}
const source = readFileSync(new URL("../../web/runtime/wasmgc/gaius-jspi.js", import.meta.url), "utf8");
vm.runInThisContext(source, {filename: "gaius-jspi.js"});

const ascii = (text) => [text.length, ...Buffer.from(text, "ascii")];
const section = (id, bytes) => [id, bytes.length, ...bytes];
const moduleBytes = new Uint8Array([
  0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
  // types: 0 = (i32, i32) -> i32 (park), 1 = (i32) -> () (run)
  ...section(1, [0x02, 0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7f, 0x60, 0x01, 0x7f, 0x00]),
  ...section(2, [0x01, ...ascii("gaiusJspi"), ...ascii("park"), 0x00, 0x00]),
  ...section(3, [0x01, 0x01]),
  ...section(7, [0x01, ...ascii("gaius.jspi.run"), 0x00, 0x01]),
  // run: drop(park(handle, 0))
  ...section(10, [0x01, 0x09, 0x00, 0x20, 0x00, 0x41, 0x00, 0x10, 0x00, 0x1a, 0x0b]),
]);

const errors = [];
const scheduler = globalThis.GaiusJspi.createScheduler();
const imports = {};
let instance = null;
const jspi = globalThis.GaiusJspi.installJspi(imports, scheduler, () => instance.exports,
  (error) => errors.push(error));
({instance} = await WebAssembly.instantiate(moduleBytes, imports));

let mainReturned = false;
const main = jspi.startMain([]).then(() => {
  mainReturned = true;
});
assert.equal(scheduler.stats.parks, 1, "main parks once");
assert.equal(mainReturned, false);

let queuedAfterMainRan = false;
// From a drained item, unpark main (the wake is queued) and queue more work behind it.
scheduler.post(() => {
  imports.gaiusJspi.unpark(0);
  scheduler.post(() => {
    queuedAfterMainRan = true;
    return false;
  });
  return false;
});

const deadline = Date.now() + 5000;
while (!queuedAfterMainRan && Date.now() < deadline) {
  await new Promise((done) => setTimeout(done, 10));
}
await main;
assert.equal(mainReturned, true, "main returned");
assert.equal(queuedAfterMainRan, true, "the run queue goes on after main returns");
assert.equal(scheduler.turn.running, false, "main's turn ended");
assert.deepEqual(errors, []);
console.log("MAIN_TURN_TEST_CHILD_OK");
// The run queue's MessageChannel keeps the process alive.
process.exit(0);
