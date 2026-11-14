import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";

const source = readFileSync(process.env.HARNESS_SOURCE || new URL("minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");
const start = source.indexOf('  if (process.env.MEMORY_PROFILE_ONLY === "1")');
const end = source.indexOf("  if (terrainObserve)", start);
assert.ok(start >= 0 && end > start, "exercise the production memory-only control flow");

async function run({snapshots = false, existing = false, enabled = true, complete = true} = {}) {
  let now = 0, samples = 0;
  const screenshots = [], stages = [];
  const report = existing ? {retainedHeapSnapshots: [{stage: "title"}]} : {};
  const context = vm.createContext({Date: {now: () => now}, process: {env: {MEMORY_PROFILE_ONLY: enabled ? "1" : "0"}},
    settleMs: 12000, cdp: {}, report,
    sample: async () => { samples++; return {}; }, terrainCompleteness: () => ({complete}),
    sleep: async ms => { now += ms; }, screenshot: async (_, name) => { screenshots.push(name); },
    retainedHeapSnapshots: snapshots ? {take: async stage => { stages.push(stage); return {stage}; }} : null,
  });
  const error = await vm.runInContext(`(async()=>{${source.slice(start, end)}})()`, context)
    .then(() => null, error => error.message);
  return {error, report, samples, screenshots, stages};
}

for (const snapshots of [false, true]) {
  for (const existing of [false, true]) {
    test(`memory-only mode supports snapshots=${snapshots}, prior samples=${existing}`, async () => {
      const result = await run({snapshots, existing});
      assert.equal(result.error, "CLEAN_THROUGHPUT_COMPLETE");
      assert.equal(result.report.memoryProfile.diagnosticOnly, true);
      assert.equal(result.report.memoryProfile.complete, true);
      assert.equal(result.samples, 4, "settled terrain is observed for the existing five-second window");
      assert.equal(result.screenshots.length, 1);
      assert.deepEqual(result.stages, snapshots ? ["rd8-settle"] : []);
      assert.equal(result.report.retainedHeapSnapshots?.length || 0, Number(existing) + Number(snapshots));
    });
  }
}

test("memory-only diagnostics retain an incomplete terrain result at the deadline", async () => {
  const result = await run({complete: false});
  assert.equal(result.error, "CLEAN_THROUGHPUT_COMPLETE");
  assert.equal(result.report.memoryProfile.complete, false);
  assert.equal(result.samples, 6);
});

test("default harness flow does not run the memory-only branch", async () => {
  const result = await run({enabled: false, snapshots: true});
  assert.equal(result.error, null);
  assert.equal(result.samples, 0);
  assert.equal(result.report.memoryProfile, undefined);
  assert.deepEqual(result.screenshots, []);
  assert.deepEqual(result.stages, []);
});
