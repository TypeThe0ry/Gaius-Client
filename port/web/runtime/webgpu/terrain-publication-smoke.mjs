import assert from "node:assert/strict";
import {readFileSync} from "node:fs";

const glue = readFileSync(new URL("../../../mc/shared/canonical/src/java/dev/gaius/browser/render/BrowserTerrainBatchGlue.java", import.meta.url), "utf8");
const batch = readFileSync(new URL("../../../src/main/java/dev/gaius/browser/render/BrowserTerrainBatch.java", import.meta.url), "utf8");
const capture = glue.slice(glue.indexOf("public static void captureSections"), glue.indexOf("/** Ties the captured"));
assert.match(batch, /public static void publishGpuIndirectTerrain\(\)\s*\{\s*BrowserGpuIndirectTerrain\.sectionCount\(\);/s,
  "canonical batch publication bridge is present");
const add = capture.indexOf("BrowserTerrainBatch.addSection");
const publish = capture.lastIndexOf("BrowserTerrainBatch.publishGpuIndirectTerrain");
assert.ok(add >= 0 && publish > add, "captureSections publishes after the complete section table");
assert.ok(capture.includes("if (gpuIndirect)"), "publication remains switch-gated");
console.log("terrain publication smoke passed");
