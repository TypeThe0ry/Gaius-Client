#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const source = await readFile(
  `${root}/port/src/main/java/dev/gaius/browser/BrowserCooperativeExecutor.java`,
  "utf8",
);
// The JavaScript build's HostLoop (classlib overlay); the WebAssembly GC build replaces it from
// src/target-wasmgc with the same API.
const hostLoop = await readFile(
  `${root}/port/overrides/classlib/src/main/java/dev/gaius/browser/HostLoop.java`,
  "utf8",
);

assert.match(source, /import org\.teavm\.jso\.JSBody;/,
  "executor must be able to detect the integrated-server Worker");
assert.match(source, /if \(isWorkerRuntime\(\)\) \{\s*return delegate;/s,
  "integrated-server Worker must bypass the second cooperative queue");
assert.match(source,
  /@JSBody\(script = "return typeof WorkerGlobalScope[^\n]+/,
  "Worker runtime probe is missing");
assert.match(source, /return new BrowserCooperativeExecutor\(delegate\);/,
  "foreground callers must retain the cooperative executor");
assert.match(source, /HostLoop\.startThread\(this::runAfterYield\);/,
  "cooperative pump must enter a TeaVM native thread before suspendable work");
assert.match(hostLoop,
  /public static void startThread\(Runnable task\) \{\s*Platform\.startThread\(task::run\);\s*\}/,
  "the JavaScript HostLoop.startThread must delegate to TeaVM's Platform.startThread");
assert.doesNotMatch(source, /(?:Platform|HostLoop)\.schedule\(this::runAfterYield,\s*0\);/,
  "raw Platform.schedule callbacks cannot suspend resource-pack downloads");

console.log(JSON.stringify({
  ok: true,
  workerBypass: true,
  foregroundCooperative: true,
  nativeThreadPump: true,
}));
