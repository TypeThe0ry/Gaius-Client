#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const source = await readFile(
  `${root}/port/src/main/java/dev/gaius/browser/BrowserResourceReloadScheduler.java`, "utf8");

assert.match(source, /public static Executor defer/,
  "resource reload scheduler source is present");
assert.match(source, /HostLoop\.startThread\(BrowserResourceReloadScheduler::runAfterYield\);/,
  "resource reload pump must enter a TeaVM thread activation");
assert.doesNotMatch(source,
  /scheduleMacrotask\(BrowserResourceReloadScheduler::runAfterYield\)/,
  "raw macrotask callbacks cannot suspend resource preparation");
assert.match(source, /if \(isWorkerRuntime\(\)\) \{\s*return delegate;/s,
  "integrated-server Worker must keep its direct executor");
assert.match(source, /MAX_SUBMISSIONS_PER_BATCH/,
  "foreground reload remains bounded");
assert.match(source, /pumpScheduled = false;\s*try \{/,
  "a parked preparation command must allow a successor pump");

console.log("browser resource reload scheduler smoke passed");
