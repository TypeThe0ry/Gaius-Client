#!/usr/bin/env node
// Regression guard for the Firefox WebGL2 startup path.  The quality probe owns a
// throw-away canvas; releasing it must not explicitly lose the WebGL context because
// Gecko can propagate that reset to the real game context created next.
//
//   node port/scripts/gpu-caps-firefox-smoke.mjs

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const source = await readFile(new URL("../web/runtime/quality/gpu-caps.js", import.meta.url), "utf8");
const probeStart = source.indexOf("function probeTemporaryContext");
const probeEnd = source.indexOf("function fromStoredRecord", probeStart);
assert.ok(probeStart >= 0 && probeEnd > probeStart, "temporary probe is present");
const probe = source.slice(probeStart, probeEnd);
// Ignore explanatory comments: the implementation may mention the API when documenting
// why it must not be called.
const implementation = probe
  .replace(/\/\*[\s\S]*?\*\//g, "")
  .replace(/(^|[^:])\/\/.*$/gm, "$1");

// The old implementation called WEBGL_lose_context.loseContext() in this finally
// block.  Keep this assertion deliberately specific so a future cleanup cannot
// reintroduce the Firefox reset while changing unrelated extension handling.
assert.doesNotMatch(implementation, /getExtension\(EXTENSIONS\.loseContext\)|\blose\.loseContext\s*\(/,
  "temporary probe must not force a WebGL context loss");
assert.match(probe, /canvas\.width\s*=\s*1[\s\S]*canvas\.height\s*=\s*1/,
  "temporary probe must release its backing store");
assert.match(probe, /typeof canvas\.remove === [\"']function[\"'][\s\S]*canvas\.remove\(\)/,
  "temporary probe should detach its throw-away canvas when supported");

console.log("gpu caps Firefox cleanup smoke passed");
