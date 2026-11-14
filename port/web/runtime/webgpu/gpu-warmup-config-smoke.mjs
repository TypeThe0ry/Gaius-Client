#!/usr/bin/env node
// The glue's default for BrowserPipelineWarmup's eager (reload) set: on a WebGPU device without the wireframe fill mode the two
// pipelines vanilla refuses stay in the shader reload instead of the post-title warm-up (whose failure bookkeeping throws on the
// second failed pipeline of a different frame budget: "a.push is not a function" -> "Render Frame" crash).
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("gpu-glue.js", import.meta.url), "utf8");
const TITLE = ["panorama", "gui_textured", "gui", "gui_text", "mojang_logo", "animate_sprite_blit", "animate_sprite_interpolate"];

// Same lookup as BrowserPipelineWarmup.pageParam (URL first, then the config object).
function pageParam(context, name) {
  const o = context.__gaiusPipelineWarmupConfig;
  return o && o[name] != null ? String(o[name]) : null;
}

function load(preset) {
  const context = vm.createContext({ console, performance });
  if (preset) context.__gaiusPipelineWarmupConfig = preset;
  vm.runInContext(source, context, { filename: "gpu-glue.js" });
  return context;
}

function ready(context) {
  const gpu = context.GaiusWebGpu;
  gpu.status = 2;
  gpu.device = { features: new Set(), limits: {} };
  gpu.queue = {};
  gpu.lostReason = "";
  return gpu;
}

{
  // Before the device exists (or when WebGPU is not used) the Java default applies.
  const context = load();
  assert.equal(pageParam(context, "gaiusPipelineEager"), null);
  assert.equal(pageParam(context, "gaiusPipelineWarmup"), null, "no other key is touched");
  assert.equal(pageParam(context, "gaiusPipelineBudgetMs"), null);
}

{
  const context = load();
  ready(context);
  const eager = pageParam(context, "gaiusPipelineEager");
  assert.ok(eager, "a usable WebGPU device without the wireframe fill mode gets the extended set");
  const names = eager.split(",");
  assert.deepEqual(names.slice(0, TITLE.length), TITLE, "the title set comes first, in the Java order");
  assert.deepEqual(names.slice(TITLE.length), ["wireframe", "wireframe_multidraw"]);
  context.GaiusWebGpu.lostReason = "destroyed";
  assert.equal(pageParam(context, "gaiusPipelineEager"), null, "a lost device leaves the Java default");
}

{
  // A config object that already sets the key wins (measurement arms, the parity script).
  const context = load({ gaiusPipelineEager: "none" });
  ready(context);
  assert.equal(pageParam(context, "gaiusPipelineEager"), "none");
  const other = load({ gaiusPipelineBudgetMs: 3 });
  ready(other);
  assert.equal(pageParam(other, "gaiusPipelineBudgetMs"), "3", "other keys of an existing config stay");
  assert.ok(pageParam(other, "gaiusPipelineEager"));
}

{
  // A frozen config object must not break the glue.
  const context = load(Object.freeze({}));
  ready(context);
  assert.equal(pageParam(context, "gaiusPipelineEager"), null);
}

console.log("gpu-warmup-config-smoke ok");
