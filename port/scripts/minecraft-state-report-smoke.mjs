#!/usr/bin/env node
// Node smoke for the WebAssembly GC state report of BrowserOpenGL: reportMinecraftStateChangedJs
// (prefix + shared script, changed-field mask) gives the same window.__gaiusMinecraftState as
// reportMinecraftStateJs over a sequence of reports in which fields change, stay and become
// null. The Java side's mask logic is mirrored here.
//
//   node port/scripts/minecraft-state-report-smoke.mjs
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const gl = readFileSync(root + "/port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java", "utf8").replace(/\r\n/g, "\n");
function textBlock(name) {
  const at = gl.indexOf(`private static final String ${name} = """\n`);
  assert.notEqual(at, -1, name);
  const start = gl.indexOf('"""\n', at) + 4;
  const end = gl.indexOf('""";', start);
  const lines = gl.slice(start, end).split("\n");
  const indent = Math.min(...lines.filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length),
    gl.slice(gl.lastIndexOf("\n", end) + 1, end).length);
  return lines.map((l) => l.slice(indent)).join("\n");
}
const script = textBlock("MINECRAFT_STATE_SCRIPT");
const prefix = textBlock("MINECRAFT_STATE_CHANGED_PREFIX");
const names = ["playerClass", "playerMode", "selectedItem", "screen", "screenTitle", "screenWidgetsJson",
  "overlay", "level", "hitClass", "hitType", "hitBlockPos", "hitDirection", "hitBlockState", "hitEntity"];
const typed = new Function("v", ...names, script);
const changedBody = new Function("v", "changed", ...names.map((_, i) => "s" + i), prefix + script);
globalThis.window = globalThis;
globalThis.performance = {now: () => 1, memory: null};
const sent = new Array(14).fill(null);
const values = new Float64Array(19).fill(1);
const reports = [
  ["P", "survival", null, "a.TitleScreen", "Title", "[{\"type\":\"b\"}]", null, null, null, null, null, null, null, null],
  ["P", "survival", null, "a.TitleScreen", "Title", "[{\"type\":\"b\"}]", null, null, null, null, null, null, null, null],
  ["P", "creative", "stone", null, null, "[]", "o", "L", "H", "BLOCK", "1,2,3", "up", "S", null],
  ["P", "creative", "stone", null, null, "[]", "o", "L", "H", "BLOCK", "1,2,3", "up", "S", null],
  [null, null, null, "a.PauseScreen", "", "[]", null, null, null, null, null, null, null, "E"],
];
for (const fields of reports) {
  typed(values, ...fields);
  const expected = JSON.stringify({...globalThis.__gaiusMinecraftState, at: 0, clientDistance: null});
  const args = fields.slice();
  let changed = 0;
  for (let i = 0; i < 14; i++) {
    if (args[i] === sent[i]) {
      args[i] = null;
    } else {
      sent[i] = args[i];
      changed |= 1 << i;
    }
  }
  changedBody(values, changed, ...args);
  const actual = JSON.stringify({...globalThis.__gaiusMinecraftState, at: 0, clientDistance: null});
  assert.equal(actual, expected);
}
console.log(`state report smoke passed: ${reports.length} reports`);
