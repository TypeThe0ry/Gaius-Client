import test from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

test("quality post reuses uniform parameter vectors", async () => {
  const source = await readFile(new URL("../port/web/runtime/webgpu/quality-post.js", import.meta.url), "utf8");
  assert.match(source, /const rectScratch\s*=\s*\{[^}]*texel:\s*new Float32Array/s);
  assert.match(source, /const vecA\s*=\s*new Float32Array\(4\)/);
  assert.match(source, /function rectParamsWith\(res, base, a, b, c\)/);
  const hotSection = source.slice(source.indexOf("function runSsao"), source.indexOf("function hasDepthInputs"));
  assert.doesNotMatch(hotSection, /Object\.assign\(/);
  assert.doesNotMatch(hotSection, /rectParams\([^)]*\)\s*,\s*\{/);
});
