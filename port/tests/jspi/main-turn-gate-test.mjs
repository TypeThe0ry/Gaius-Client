#!/usr/bin/env node
// Check the public gate with JSPI genuinely disabled in the runtime child.
//   node port/tests/jspi/main-turn-gate-test.mjs
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";

const script = fileURLToPath(new URL("main-turn-test.mjs", import.meta.url));
for (const reason of ["", "   ", "gate regression: JSPI deliberately disabled"]) {
  const child = spawnSync(process.execPath, [script, "--no-jspi"], {
    encoding: "utf8",
    timeout: 25000,
    env: {...process.env, GAIUS_MAIN_TURN_ALLOW_SKIP_REASON: reason},
  });
  assert.ifError(child.error);
  const output = `${child.stdout || ""}${child.stderr || ""}`;
  assert.equal(child.signal, null, output);
  assert.doesNotMatch(output, /MAIN_TURN_TEST PASS/, output);
  if (reason.trim()) {
    assert.equal(child.status, 0, output);
    assert.match(output, /MAIN_TURN_TEST SKIP \(allowed\): gate regression: JSPI deliberately disabled/);
  } else {
    assert.equal(child.status, 1, output);
    assert.match(output, /MAIN_TURN_TEST FAIL: this Node has no JSPI/);
    assert.doesNotMatch(output, /SKIP \(allowed\)/);
  }
}
console.log("MAIN_TURN_GATE_TEST PASS (missing JSPI fails closed; explicit skip requires a reason)");
