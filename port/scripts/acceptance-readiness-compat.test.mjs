import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";
import {VERSION_PROFILES} from "./verification-profiles.mjs";
import {buildHarnessEnv} from "./release-acceptance.mjs";
const source = readFileSync(new URL("./minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");
const pinned = source.slice(source.indexOf("async function waitForPinnedTerrain("), source.indexOf("// Pixel metrics"));
test("non-pinned delegates while pinned waits for a stable second", async () => {
  for (const expectedGpuPci of [null, "0000:23:00.0"]) {
    let clock = 0, fallback = 0;
    const context = {expectedGpuPci, Date: {now: () => clock},
      evaluate: async () => ({audit: {visible: 30}, pipeline: {compile: 0, upload: 0}}),
      terrainCompleteness: () => ({complete: true}), stateExpr: "state",
      sleep: async ms => {clock += ms;}, log: async () => {},
      waitForTerrainFrame: async () => {fallback++;}};
    await vm.runInNewContext(pinned + ";waitForPinnedTerrain({}, 'fixture')", context);
    assert.equal(fallback, expectedGpuPci ? 0 : 1);
    assert.equal(clock, expectedGpuPci ? 1000 : 0);
  }
});
test("click wrapper scales explicit and default deadlines exactly once", async () => {
  const start = source.indexOf("async function clickWidget(");
  const end = source.indexOf("async function ", start + 1);
  for (const ms of [undefined, 10000]) {
    let observed;
    const context = {process: {env: {HARNESS_TIMEOUT_SCALE: "3"}},
      findWidget: async (_cdp, _match, timeout) => {observed = timeout; return {x: 1, y: 2, text: "Done", type: "Button"};},
      clickAt: async () => {}, log: async () => {}, ms};
    await vm.runInNewContext(source.slice(start, end) + ";clickWidget({}, 'Done', ms)", context);
    assert.equal(observed, (ms ?? 30000) * 3);
  }
});
test("release runner preserves scale and original budgets", () => {
  const env = buildHarnessEnv({profile: VERSION_PROFILES[0].id, id: "portable-file"}, {out: "/evidence", seed: "20261014"}, {HARNESS_TIMEOUT_SCALE: "3"});
  assert.equal(env.HARNESS_TIMEOUT_SCALE, "3");
  assert.equal(env.WORLD_TIMEOUT_MS, "420000");
  assert.equal(env.SETTLE_S, "60");
});
test("pinned readiness resets stability when work returns and rejects persistent backlog", async () => {
  for (const persistent of [false, true]) {
    let clock = 0;
    const context = {expectedGpuPci: "0000:23:00.0", Date: {now: () => clock},
      evaluate: async () => ({audit: {visible: 30}, pipeline: {compile: persistent || clock === 500 ? 1 : 0, upload: 0}}),
      terrainCompleteness: () => ({complete: true}), stateExpr: "state",
      sleep: async ms => {clock += ms;}, log: async () => {},
      waitForTerrainFrame: async () => {throw new Error("must retain pinned protocol");}};
    const result = vm.runInNewContext(pinned + ";waitForPinnedTerrain({}, 'fixture')", context);
    if (persistent) {
      await assert.rejects(result, /Pinned terrain did not complete/);
      assert.equal(clock, 120000);
    } else {
      await result;
      assert.equal(clock, 2000);
    }
  }
});
