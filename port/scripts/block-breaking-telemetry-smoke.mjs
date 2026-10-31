import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserBlockBreakingTelemetry.java", import.meta.url), "utf8");
const context = vm.createContext({performance: {now: () => 1234}});
const methods = {};
for (const match of source.matchAll(/@JSBody\((?:params = "(\w+)", )?script = """([\s\S]*?)"""\)\s+private static native void (\w+)\([^)]*\);/g)) {
  methods[match[3]] = vm.runInContext(`(function(${match[1] || ""}){${match[2]}\n})`, context);
}
assert.equal(Object.keys(methods).length, 5);
for (const method of Object.values(methods)) method(4);
assert.equal(context.__gaiusBlockBreakingTelemetry, undefined,
  "disabled diagnostics must not allocate state");
context.__gaiusBlockBreakingTelemetryEnabled = true;
methods.recordDestroyProgressJs(4);
methods.recordExtractionJs();
methods.recordEmittedJs();
methods.recordSubmitPassJs();
let stats = context.__gaiusBlockBreakingTelemetry;
assert.equal(stats.lastStage, 4);
assert.equal(stats.destroyProgressCalls, 1);
assert.equal(stats.extractionCalls, 1);
assert.equal(stats.emittedStates, 1);
assert.equal(stats.submitPasses, 1);
assert.equal(stats.actualSubmitCalls, 0,
  "entering a render pass must not count a model submission");
methods.recordActualSubmitJs();
assert.equal(stats.actualSubmitCalls, 1);
methods.recordDestroyProgressJs(-1);
assert.equal(stats.lastStage, -1, "retain the progress-clear event");
assert.equal(stats.updatedAt, 1234);
const snapshot = JSON.stringify(stats);
context.__gaiusBlockBreakingTelemetryEnabled = false;
for (const method of Object.values(methods)) method(9);
assert.equal(JSON.stringify(stats), snapshot, "disabling must stop all counters");
context.__gaiusBlockBreakingTelemetryEnabled = true;
context.__gaiusBlockBreakingTelemetry = Object.freeze({});
for (const method of Object.values(methods)) assert.doesNotThrow(() => method(9));
console.log("BLOCK_BREAKING_TELEMETRY_OK");
