import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("./minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");

test("Linux block clicks preserve the measured canvas cursor position", async () => {
  const start = source.indexOf("async function dispatchDomButton(");
  const end = source.indexOf("async function findWidget(", start);
  assert.ok(start >= 0 && end > start);
  let expression = "";
  const dispatch = vm.runInNewContext(source.slice(start, end) + ";dispatchDomButton", {
    evaluate: async (_cdp, script) => { expression = script; return true; },
    JSON,
  });
  await dispatch({}, "left", 400, 300);
  assert.match(expression, /clientX:400,clientY:300/);
  assert.match(source, /dispatchDomButton\(cdp, "left", point\.x, point\.y\)/);
  assert.match(source, /dispatchDomButton\(cdp, "right", point\.x, point\.y\)/);
});

test("inventory terrain sampling waits for the teleported camera pitch", () => {
  const teleport = source.indexOf('await command(cdp, `/tp @s ${jx + 0.5} ${groundY + 40} ${jz + 0.5} 0 90`);');
  const frameReady = source.indexOf('await waitForTerrainFrame(cdp, "inventory camera terrain compilation");', teleport);
  const pitchReady = source.indexOf('await waitFor(cdp, "Math.abs(Number(globalThis.__gaiusMinecraftState?.player?.pitch)-90)<0.5"', teleport);
  assert.ok(teleport >= 0 && frameReady > teleport);
  assert.ok(pitchReady > teleport && pitchReady < frameReady,
    "do not judge the inventory terrain until the server camera pitch is observed");
});
