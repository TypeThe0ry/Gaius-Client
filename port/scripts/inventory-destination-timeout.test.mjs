import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("./minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");
const start = source.indexOf("    const destinationReadyEnd =");
const end = source.indexOf('    await command(cdp, "/gamemode creative");', start);
assert.ok(start >= 0 && end > start);
const loop = source.slice(start, end);

async function destination({scale = "1", readyAt = Infinity, worldBudget = 45000, state = {}} = {}) {
  let clock = 0;
  let reads = 0;
  const context = {
    Date: {now: () => clock},
    process: {env: {HARNESS_TIMEOUT_SCALE: scale}},
    cdp: {}, stateExpr: "state", report: {}, worldTimeout: worldBudget,
    log: async () => {},
    evaluate: async () => {
      reads++;
      return {level: "world", player: {y: 115}, dist: {effectiveRenderDistance: 8},
        loaded: clock >= readyAt ? 289 : 150, pipeline: {compile: 0, upload: 0}, ...state};
    },
    sleep: async ms => { clock += ms; },
  };
  try {
    await vm.runInNewContext("(async () => {" + loop + "})()", context);
    return {ready: true, clock, reads};
  } catch (error) { return {ready: false, clock, reads, error: String(error)}; }
}

test("contended destination can finish after the original deadline with scale three", async () => {
  const result = await destination({scale: "3", readyAt: 60000});
  assert.equal(result.ready, true, result.error);
  assert.equal(result.clock, 60000);
});

test("default deadline stays bounded and does not accept incomplete terrain", async () => {
  const result = await destination({readyAt: 60000});
  assert.equal(result.ready, false);
  assert.equal(result.clock, 45000);
  assert.match(result.error, /inventory destination terrain did not settle/);
});

test("destination uses the existing world budget when scale three is insufficient", async () => {
  const result = await destination({scale: "3", readyAt: 180000, worldBudget: 420000});
  assert.equal(result.ready, true, result.error);
  assert.equal(result.clock, 180000);
});

test("scale three still fails when terrain never becomes ready", async () => {
  const result = await destination({scale: "3"});
  assert.equal(result.ready, false);
  assert.equal(result.clock, 135000);
});

test("readiness keeps render distance, player height and queue requirements", async () => {
  for (const state of [{loaded: 288}, {player: {y: -101}}, {level: null},
    {pipeline: {compile: 1, upload: 0}}, {pipeline: {compile: 0, upload: 1}}]) {
    assert.equal((await destination({readyAt: 0, state})).ready, false);
  }
});

test("inventory camera capture waits for the newly visible compilation queue", async () => {
  const captureEnd = source.indexOf('    const terrain = await sample(cdp, "inventory-streaming", false);');
  const captureStart = source.lastIndexOf("    await sleep(5000);", captureEnd);
  assert.ok(captureStart >= 0 && captureEnd > captureStart);
  let clock = 0;
  const context = {
    cdp: {}, sleep: async ms => { clock += ms; }, terrainViewport: async () => {},
    waitForTerrainFrame: async () => { while (clock < 30000) clock += 250; },
  };
  await vm.runInNewContext("(async () => {" + source.slice(captureStart, captureEnd) + "})()", context);
  assert.ok(clock >= 30000, "capture must not sample while the view still has compilation backlog");
});
