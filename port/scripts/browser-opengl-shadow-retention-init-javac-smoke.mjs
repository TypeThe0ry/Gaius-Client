#!/usr/bin/env node
// Compile the exact BrowserOpenGL.initialize() source body with a small runtime fixture and
// verify shadow retention initializes for the JavaScript target as well as WasmGC.
// node port/scripts/browser-opengl-shadow-retention-init-javac-smoke.mjs <output-dir>

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdir, readFile, writeFile} from "node:fs/promises";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const repository = fileURLToPath(new URL("../..", import.meta.url));
const output = resolve(process.argv[2] || "browser-opengl-shadow-retention-init-javac");
const source = await readFile(join(repository,
  "port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java"), "utf8");
const declaration = source.indexOf("public static void initialize() {");
assert.notEqual(declaration, -1, "BrowserOpenGL.initialize() exists");
const open = source.indexOf("{", declaration);
let depth = 0, close = -1;
for (let i = open; i < source.length; i++) {
  if (source[i] === "{") depth++;
  else if (source[i] === "}" && --depth === 0) { close = i + 1; break; }
}
assert.ok(close > open, "initializer body is balanced");
const initialize = source.slice(declaration, close)
  .replace("BrowserOpenGL::releaseAllMappedBuffers", "BrowserOpenGLShadowRetentionInitJavacSmoke::releaseAllMappedBuffers");
assert.ok(initialize.indexOf("initializeShadowRetentionJs();") > initialize.lastIndexOf("        }"),
  "shadow retention initialization is outside the linear-memory branch");

const fixture = `import java.util.*;
public final class BrowserOpenGLShadowRetentionInitJavacSmoke {
  static final List<String> calls = new ArrayList<>();
  static void mark(String name) { calls.add(name); }
  static final class JsArrays {
    static boolean LINEAR_MEMORY = false;
    static void installLinearViews() { mark("linearViews"); }
  }
  static final class GaiusProfileCapabilities {
    static final int PATCH_SET = 1;
    static final boolean TERRAIN_SECTION_UNIFORM_BLOCK = true;
  }
  static final class BrowserOpenGLPerf { static void initialize() { mark("perf"); } }
  static void releaseAllMappedBuffers() {}
  static void initializeJs() { mark("initializeJs"); }
  static void initializeDrawCompatibilityJs() { mark("drawCompatibility"); }
  static void initializeAttribTypeAdaptJs() { mark("attribTypeAdapt"); }
  static void initializeElementBufferStateJs() { mark("elementBufferState"); }
  static void initializePerformanceStateJs() { mark("performanceState"); }
  static void initializeGpuHotPathJs() { mark("gpuHotPath"); }
  static void initializeUniformValueCacheJs() { mark("uniformValueCache"); }
  static void initializeDrawStateCacheJs() { mark("drawStateCache"); }
  static void initializeVaoFastPathJs() { mark("vaoFastPath"); }
  static void initializeBaseInstanceDrawJs() { mark("baseInstanceDraw"); }
  static void initializeDrawFastPathJs() { mark("drawFastPath"); }
  static void initializeGpuFenceLifecycleJs(Runnable release) { mark("gpuFenceLifecycle"); }
  static void initializeShadowDecisionCache() { mark("shadowDecisionCache"); }
  static void initializeMisalignedBufferRefs() { mark("misalignedBufferRefs"); }
  static void initializeVaoBufferRefsJs() { mark("vaoBufferRefs"); }
  static void initializeTerrainBatchJs(int patch, boolean uniformBlock) { mark("terrainBatch"); }
  static void initializeLinearUploadsJs() { mark("linearUploads"); }
  static void initializeBufferLayerJs() { mark("bufferLayer"); }
  static void initializeTexelBufferCacheJs() { mark("texelBufferCache"); }
  static void initializeShadowRetentionJs() { mark("shadowRetention"); }
  static void initializeDivisorCacheJs() { mark("divisorCache"); }
  ${initialize}
  public static void main(String[] args) {
    initialize();
    if (!calls.contains("shadowRetention")) throw new AssertionError("shadow retention was not initialized");
    if (calls.contains("linearViews") || calls.contains("linearUploads") || calls.contains("bufferLayer")
        || calls.contains("texelBufferCache") || calls.contains("divisorCache") || calls.contains("perf")) {
      throw new AssertionError("linear-memory-only initialization ran for JavaScript");
    }
    if (calls.indexOf("shadowRetention") <= calls.indexOf("terrainBatch"))
      throw new AssertionError("shadow retention ran before base GL initialization");
    System.out.println("BrowserOpenGL.initialize() javac JavaScript-target retention PASS");
  }
}
`;
const sourceDir = join(output, "src");
const classesDir = join(output, "classes");
await mkdir(sourceDir, {recursive: true});
await mkdir(classesDir, {recursive: true});
const javaFile = join(sourceDir, "BrowserOpenGLShadowRetentionInitJavacSmoke.java");
await writeFile(javaFile, fixture, "utf8");
execFileSync("javac", ["--release", "21", "-proc:none", "-d", classesDir, javaFile], {stdio: "inherit"});
execFileSync("java", ["-cp", classesDir, "BrowserOpenGLShadowRetentionInitJavacSmoke"], {stdio: "inherit"});
