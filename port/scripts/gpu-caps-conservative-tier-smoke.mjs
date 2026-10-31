#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../web/runtime/quality/gpu-caps.js", import.meta.url), "utf8");
function createPage(search = "", initialStorage = {}) {
  const storage = new Map(Object.entries(initialStorage));
  const root = {
    GaiusQuality: {},
    navigator: {userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit Chrome/155"},
    location: {search},
    URLSearchParams,
    localStorage: {
      getItem: key => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, String(value)),
      removeItem: key => storage.delete(key),
    },
    console,
  };
  root.globalThis = root;
  vm.runInNewContext(source, root, {filename: "gpu-caps.js"});
  return root;
}

const root = createPage();
const caps = root.GaiusQuality.caps;
const classify = (renderer, vendor) => JSON.parse(JSON.stringify(caps.classifyRenderer(renderer, vendor)));
assert.deepEqual(classify("ANGLE (Apple, Apple M3 Pro)", "Apple"), {
  kind: "integrated", floor: 1, cap: 1, guess: 1,
});
assert.deepEqual(classify("ANGLE (Apple, Apple M4 Max)", "Apple"), {
  kind: "integrated", floor: 1, cap: 1, guess: 1,
});
assert.deepEqual(classify("ANGLE (NVIDIA, NVIDIA GeForce RTX 4060)", "NVIDIA"), {
  kind: "discrete", floor: 1, cap: 3, guess: 2,
});

const legacyRecord = createPage("", {
  "gaius.quality.gpuTier.v1": JSON.stringify({schema: 1, tier: "high", kind: "integrated", score: 9000}),
});
assert.equal(legacyRecord.GaiusQuality.caps.ensureTier().tier, "mid",
  "an old high-tier record must be classified again after the schema bump");

const override = createPage("?gaiusTier=ultra");
assert.equal(override.GaiusQuality.caps.ensureTier().tier, "ultra",
  "an explicit tier override must remain available after the schema bump");

console.log("gpu caps conservative integrated-tier smoke passed");
