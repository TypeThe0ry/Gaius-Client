import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("./minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");

async function poll({scale = "1", readyAt = Infinity, worldBudget = 45000, state = {}} = {}) {
  const start = source.indexOf("async function waitForTerrainFrame(");
  assert.ok(start >= 0, "terrain captures need bounded readiness polling");
  const end = source.indexOf("async function freePort(", start);
  let clock = 0;
  const context = {Date: {now: () => clock}, process: {env: {HARNESS_TIMEOUT_SCALE: scale}},
    stateExpr: "state", worldTimeout: worldBudget, evaluate: async () => ({level: "world", loaded: clock >= readyAt ? 289 : 287,
      dist: {effectiveRenderDistance: 8}, pipeline: {compile: 0, upload: 0},
      audit: {visible: 30, uncompiled: 0}, ...state}),
    sleep: async ms => { clock += ms; }, log: async () => {}};
  const wait = vm.runInNewContext(source.slice(start, end) + ";waitForTerrainFrame", context);
  try { await wait({}, "fixture"); return {ready: true, clock}; }
  catch (error) { return {ready: false, clock, error: String(error)}; }
}

test("terrain frame waits for chunks that arrive after the original deadline", async () => {
  assert.deepEqual(await poll({scale: "3", readyAt: 60000}), {ready: true, clock: 60000});
  const result = await poll({readyAt: 60000});
  assert.equal(result.ready, false);
  assert.equal(result.clock, 45000);
});

test("incomplete chunks, visible sections and queued work cannot satisfy readiness", async () => {
  for (const state of [{loaded: 288}, {audit: {visible: 30, uncompiled: 16}},
    {pipeline: {compile: 64, upload: 0}}, {pipeline: {compile: 0, upload: 1}}]) {
    const result = await poll({readyAt: 0, state});
    assert.equal(result.ready, false);
    assert.equal(result.clock, 45000);
  }
});

test("terrain frame respects the existing world budget under contention", async () => {
  assert.deepEqual(await poll({scale: "3", readyAt: 180000, worldBudget: 420000}),
    {ready: true, clock: 180000});
});

test("readiness keeps effective render distance and supports missing optional audits", async () => {
  assert.equal((await poll({readyAt: 0, state: {audit: null}})).ready, true);
  assert.equal((await poll({readyAt: 0, state: {dist: {effectiveRenderDistance: 9}}})).ready, false);
});

test("both terrain baseline images and the reloaded block view wait for readiness", () => {
  for (const marker of ['const topdown = await sample', 'const topdown2 = await sample', 'const h3 = await evaluate']) {
    const end = source.indexOf(marker);
    const start = source.lastIndexOf("await sleep(", end);
    assert.match(source.slice(start, end), /await waitFor(?:TerrainFrame|PinnedTerrain)/);
  }
});

test("reload block sampling waits for the requested camera pitch acknowledgement", async () => {
  const start = source.indexOf('  await waitFor(cdp, "Math.abs(Number(globalThis.__gaiusMinecraftState?.player?.pitch)-"');
  assert.ok(start >= 0);
  const end = source.indexOf("  await waitForTerrainFrame", start);
  const waitStart = source.indexOf("async function waitFor(");
  const waitEnd = source.indexOf("async function waitForTerrainFrame(", waitStart);
  let clock = 0;
  const context = {Date: {now: () => clock}, process: {env: {HARNESS_TIMEOUT_SCALE: "3"}},
    cdp: {}, aimPitch: 90, sleep: async ms => { clock += ms; },
    evaluate: async (_cdp, expression) => vm.runInNewContext(expression,
      {__gaiusMinecraftState: {player: {pitch: clock < 3000 ? 64.8 : 90}}})};
  await vm.runInNewContext(source.slice(waitStart, waitEnd) +
    ";(async ()=>{" + source.slice(start, end) + "})()", context);
  assert.equal(clock, 3000);
});
