#!/usr/bin/env node
// Regression check for the page bookkeeping of BrowserPipelineWarmup (no browser, no build): the @JSBody scripts of pagePublish and
// pageNote are run in a vm in the order the warm-up calls them. Two pipelines that fail in different frame budgets used to end in
// "a.push is not a function" (pageNote appended to w.failed, which pagePublish had replaced with the failure counter), which
// escapes BrowserRenderScheduler.beginFrame as a "Render Frame" crash.
//
//   node port/scripts/pipeline-warmup-note-smoke.mjs
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const source = readFileSync(process.argv[2] || new URL("../src/main/java/dev/gaius/browser/BrowserPipelineWarmup.java", import.meta.url), "utf8");
// The note lists were "failed" before the fix: an optional argument checks any version of the file.
const FAILED_LIST = /host\.note\("failed",|noteSafely\("failed",/.test(source) ? "failed" : "failedNames";

function jsBody(nativeName) {
  const at = source.indexOf(`native void ${nativeName}(`);
  assert.ok(at > 0, `${nativeName} not found`);
  const open = source.lastIndexOf('"""', at);
  const start = source.lastIndexOf('"""', open - 1);
  assert.ok(start > 0 && open > start, `text block of ${nativeName} not found`);
  const params = /params\s*=\s*(\{[^}]*\}|"[^"]*")/.exec(source.slice(Math.max(0, start - 400), start));
  const names = params ? [...params[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]) : [];
  const body = source.slice(start + 3, open).replace(/^\s*\n/, "");
  return {names, body};
}

function makeHost() {
  const context = vm.createContext({globalThis: null, performance: {now: () => 1234.5}});
  context.globalThis = context;
  const call = (nativeName, args) => {
    const {names, body} = jsBody(nativeName);
    vm.runInContext(`(function (${names.join(", ")}) {${body}})`, context)(...args);
  };
  return {
    context,
    publish: (failed, extra = {}) => call("pagePublish", [true, "deferred", 7, 185, extra.warmed ?? 0, failed, 0, true, false, 1.5, 0.5, 100]),
    note: (list, value) => call("pageNote", [list, value]),
  };
}

// The warm-up's own order: failure 1 in batch A (note, then the batch's publish), failure 2 in batch B (note, publish).
{
  const host = makeHost();
  host.publish(0);
  host.note(FAILED_LIST, "minecraft:pipeline/wireframe");
  host.publish(1);
  host.note(FAILED_LIST, "minecraft:pipeline/wireframe_multidraw");
  host.publish(2, {warmed: 183});
  const w = host.context.__gaiusPipelineWarmup;
  assert.equal(w.failed, 2, "failed stays the numeric counter");
  assert.deepEqual(JSON.parse(JSON.stringify(w.failedNames.map((x) => x[1]))),
    ["minecraft:pipeline/wireframe", "minecraft:pipeline/wireframe_multidraw"]);
  assert.equal(w.warmed, 183);
}

// Whatever a stale reader left under a counter key, a note replaces it by a list instead of throwing.
{
  const host = makeHost();
  host.publish(3);
  assert.doesNotThrow(() => host.note("failed", "x"));
  assert.ok(Array.isArray(host.context.__gaiusPipelineWarmup.failed));
}

// Misses keep working next to the counters.
{
  const host = makeHost();
  host.publish(0);
  host.note("misses", "minecraft:pipeline/gui");
  host.publish(0);
  const w = host.context.__gaiusPipelineWarmup;
  assert.equal(w.missCount, 0);
  assert.equal(w.misses.length, 1);
}

console.log("pipeline-warmup-note-smoke ok");
