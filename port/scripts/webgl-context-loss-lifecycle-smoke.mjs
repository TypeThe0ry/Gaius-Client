#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const sourcePath = fileURLToPath(new URL(
  "../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java",
  import.meta.url,
));
const source = await readFile(sourcePath, "utf8");

function jsBodyBefore(declaration) {
  const declarationOffset = source.indexOf(declaration);
  assert.notEqual(declarationOffset, -1, `missing declaration: ${declaration}`);
  const marker = 'script = """';
  const bodyStart = source.lastIndexOf(marker, declarationOffset);
  assert.notEqual(bodyStart, -1, `missing JSBody for: ${declaration}`);
  const scriptStart = bodyStart + marker.length;
  const scriptEnd = source.indexOf('""")', scriptStart);
  assert.ok(scriptEnd >= scriptStart && scriptEnd < declarationOffset,
    `unterminated JSBody for: ${declaration}`);
  return source.slice(scriptStart, scriptEnd);
}

function javaMethod(signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `missing Java method: ${signature}`);
  const bodyStart = source.indexOf("{", start + signature.length);
  let depth = 0;
  for (let index = bodyStart; index < source.length; index++) {
    if (source[index] === "{") depth++;
    if (source[index] === "}" && --depth === 0) {
      return source.slice(start, index + 1);
    }
  }
  assert.fail(`unterminated Java method: ${signature}`);
}

assert.ok(source.includes("import org.teavm.jso.JSFunctor;") &&
    source.includes("interface MappedBufferReleaseCallback extends JSObject"),
  "mapped-buffer cleanup is not passed as a TeaVM JS callback");
const releaseMethod = javaMethod(
  "private static void releaseAllMappedBuffers()",
);
for (const contract of [
  "new java.util.ArrayList<>(MAPPED_BUFFERS.values())",
  "MAPPED_BUFFERS.clear()",
  // v0.4: mapped staging returns to the size-class pool (or is freed past its cap).
  "releaseMappedStorage(mapped)",
  "noteMappedBufferCountJs(0)",
]) {
  assert.ok(source.slice(Math.max(0, source.indexOf(releaseMethod) - 80),
    source.indexOf(releaseMethod) + releaseMethod.length).includes(contract),
  `missing mapped-buffer cleanup contract: ${contract}`);
}
assert.ok(releaseMethod.indexOf("MAPPED_BUFFERS.clear()")
  < releaseMethod.indexOf("releaseMappedStorage(mapped)"),
"mapped buffers are not detached before native allocations are released");

const initializeLifecycle = new Function(
  "releaseMappedBuffers",
  jsBodyBefore("private static native void initializeGpuFenceLifecycleJs"),
);

function runLifecycleScenario({
  autoReloadDisabled = false,
  initiallyLost = false,
  nonExtensibleContext = false,
} = {}) {
  const canvasListeners = new Map();
  const timers = [];
  let releaseCalls = 0;
  let reloadCalls = 0;
  let drawCalls = 0;
  let quarantineCalls = 0;
  let quarantined = false;
  const gl = {
    MAX_CLIENT_WAIT_TIMEOUT_WEBGL: 0x9247,
    CONTEXT_LOST_WEBGL: 0x9242,
    canvas: {
      addEventListener(type, listener) {
        canvasListeners.set(type, listener);
      },
    },
    isContextLost() {
      return initiallyLost;
    },
    getParameter() {
      return 0;
    },
    getExtension(name) {
      if (name !== "WEBGL_lose_context") return null;
      return {
        loseContext() {
          quarantineCalls++;
          quarantined = true;
        },
      };
    },
    drawArrays() {
      if (!quarantined) drawCalls++;
    },
    getError() {
      return 0;
    },
  };
  if (nonExtensibleContext) Object.freeze(gl);
  const mockWindow = {
    __gaiusDisableGpuContextAutoReload: autoReloadDisabled,
    __gaiusGL: {next: 1, syncs: new Map()},
    __gaiusGLStats: {},
    __gaiusWebGL: gl,
    location: {
      reload() {
        reloadCalls++;
      },
    },
  };
  const previous = {
    setTimeout: globalThis.setTimeout,
    window: globalThis.window,
  };
  const releaseMappedBuffers = () => {
    releaseCalls++;
  };
  globalThis.setTimeout = callback => {
    timers.push(callback);
    return timers.length;
  };
  globalThis.window = mockWindow;
  try {
    initializeLifecycle(releaseMappedBuffers);
    return {
      canvasListeners,
      flushTimer() {
        timers.shift()?.();
      },
      gl,
      state: mockWindow.__gaiusGL,
      stats: mockWindow.__gaiusGLStats,
      get drawCalls() { return drawCalls; },
      get releaseCalls() { return releaseCalls; },
      get reloadCalls() { return reloadCalls; },
      get quarantineCalls() { return quarantineCalls; },
      get timerCount() { return timers.length; },
      restoreGlobals() {
        globalThis.setTimeout = previous.setTimeout;
        if (previous.window === undefined) delete globalThis.window;
        else globalThis.window = previous.window;
      },
    };
  } catch (error) {
    globalThis.setTimeout = previous.setTimeout;
    globalThis.window = previous.window;
    throw error;
  }
}

const lifecycle = runLifecycleScenario();
try {
  let prevented = 0;
  lifecycle.canvasListeners.get("webglcontextlost")({
    preventDefault() { prevented++; },
  });
  assert.equal(prevented, 1);
  assert.equal(lifecycle.state.gpuContextLost, true);
  assert.equal(lifecycle.stats.gpuContextLost, true);
  assert.equal(lifecycle.stats.gpuContextRecovery, "reload-scheduled");
  assert.equal(lifecycle.releaseCalls, 1);
  assert.equal(lifecycle.timerCount, 1,
    "context loss did not immediately schedule its single reload");
  lifecycle.gl.drawArrays(4, 0, 3);
  assert.equal(lifecycle.drawCalls, 0,
    "a WebGL submission reached a context after loss");

  lifecycle.canvasListeners.get("webglcontextrestored")();
  lifecycle.canvasListeners.get("webglcontextrestored")();
  assert.equal(lifecycle.state.gpuContextLost, true,
    "restore incorrectly advertised the stale Java/WebGL mappings as usable");
  assert.equal(lifecycle.stats.gpuContextLost, true);
  assert.equal(lifecycle.stats.gpuContextRecovery, "reload-scheduled");
  assert.equal(lifecycle.timerCount, 1, "duplicate restore scheduled a second reload");
  lifecycle.gl.drawArrays(4, 0, 3);
  assert.equal(lifecycle.drawCalls, 0,
    "a WebGL submission reached the restored context before reload");
  assert.equal(lifecycle.stats.gpuBlockedCalls, 2);
  assert.ok(lifecycle.stats.gpuBlockedMethodCount >= 3);

  lifecycle.flushTimer();
  assert.equal(lifecycle.reloadCalls, 1);
  assert.equal(lifecycle.stats.gpuContextRecovery, "reloading");
  assert.equal(lifecycle.releaseCalls, 4,
    "mapped buffers were not released at loss, restore, and before reload");
} finally {
  lifecycle.restoreGlobals();
}

const manualReload = runLifecycleScenario({autoReloadDisabled: true});
try {
  manualReload.canvasListeners.get("webglcontextlost")({preventDefault() {}});
  manualReload.canvasListeners.get("webglcontextrestored")();
  assert.equal(manualReload.state.gpuContextLost, true);
  assert.equal(manualReload.stats.gpuContextRecovery, "reload-required");
  assert.equal(manualReload.timerCount, 0);
  manualReload.gl.drawArrays(4, 0, 3);
  assert.equal(manualReload.drawCalls, 0,
    "manual-reload mode allowed submissions through stale GL mappings");
} finally {
  manualReload.restoreGlobals();
}

const initiallyLost = runLifecycleScenario({initiallyLost: true});
try {
  assert.equal(initiallyLost.state.gpuContextLost, true);
  assert.equal(initiallyLost.stats.gpuContextRecovery, "reload-scheduled");
  assert.equal(initiallyLost.releaseCalls, 1);
  assert.equal(initiallyLost.timerCount, 1);
} finally {
  initiallyLost.restoreGlobals();
}

const guardedFallback = runLifecycleScenario({nonExtensibleContext: true});
try {
  guardedFallback.canvasListeners.get("webglcontextlost")({preventDefault() {}});
  guardedFallback.canvasListeners.get("webglcontextrestored")();
  assert.ok(guardedFallback.stats.gpuUnblockedMethodCount > 0,
    "non-extensible WebGL context did not exercise the fallback path");
  assert.equal(guardedFallback.stats.gpuSubmissionBlockFallback, "proxy");
  assert.equal(guardedFallback.stats.gpuSubmissionContextQuarantined, true);
  assert.equal(guardedFallback.quarantineCalls, 1);
  guardedFallback.gl.drawArrays(4, 0, 3);
  assert.equal(guardedFallback.drawCalls, 0,
    "captured non-extensible WebGL context remained usable after restore");
} finally {
  guardedFallback.restoreGlobals();
}

console.log("WebGL context loss lifecycle smoke passed");
