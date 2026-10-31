import assert from "node:assert/strict";
import {test} from "node:test";
import {isShaderConsoleError, runtimeArgument, runtimePageUrl, runtimeSelectionMatches} from "./harness-common.mjs";

test("terrain coverage failures do not masquerade as shader errors", () => {
  assert.equal(isShaderConsoleError('100.8 CHECK FAIL terrainRendered {"pipeline":{"compile":0}}'), false);
  assert.equal(isShaderConsoleError('101.0 CHECK FAIL noShaderErrors {"bad":0}'), false);
  assert.equal(isShaderConsoleError('100.8 console.error Failed to compile shader'), true);
  assert.equal(isShaderConsoleError('100.8 log.warning worker invalid pipeline program'), true);
  assert.equal(isShaderConsoleError('100.8 console.info Shader loaded successfully'), false);
});

test("requested JS runtime reaches the page URL and explicit query wins", () => {
  const runtime = runtimeArgument([], {GAIUS_RUNTIME: "js"});
  assert.equal(new URL(runtimePageUrl("http://localhost/index.html", runtime, "gpu=webgl2")).searchParams.get("runtime"), "js");
  assert.equal(new URL(runtimePageUrl("http://localhost/index.html", runtime, "runtime=wasm")).searchParams.get("runtime"), "wasm");
});

test("a requested JS run cannot pass with wasm or missing telemetry", () => {
  assert.equal(runtimeSelectionMatches("js", "wasm"), false);
  assert.equal(runtimeSelectionMatches("js", null), false);
  assert.equal(runtimeSelectionMatches("js", "js"), true);
  assert.equal(runtimeSelectionMatches("wasm", "wasm"), true);
  assert.equal(runtimeSelectionMatches(null, "wasm"), true);
  assert.equal(runtimeSelectionMatches(null, null), false);
});
