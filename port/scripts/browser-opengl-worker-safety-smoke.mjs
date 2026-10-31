import {readFile} from "node:fs/promises";
import vm from "node:vm";
import {resolve} from "node:path";

const sourcePath = resolve(
  "port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java"
);
const source = await readFile(sourcePath, "utf8");

function bodyBetween(marker, nextMarker, from = 0) {
  const start = source.indexOf(marker, from);
  if (start < 0) throw new Error(`missing marker: ${marker}`);
  const end = source.indexOf(nextMarker, start + marker.length);
  if (end < 0) throw new Error(`missing end marker for: ${marker}`);
  return source.slice(start, end);
}

const budgetStart = source.indexOf("private static long mappedPoolBudget()");
const budgetBody = bodyBetween(
  '@JSBody(script = """',
  'private static native int mappedPoolBudgetJs();',
  budgetStart
);
const telemetryStart = source.indexOf("private static native int mappedPoolBudgetJs();");
const telemetryBody = bodyBetween(
  '@JSBody(params = {"hits", "misses", "pooledBytes", "limitBytes"}',
  "private static native void noteMappedPoolJs",
  telemetryStart
);

if (!budgetBody.includes("const root = globalThis;")) {
  throw new Error("mappedPoolBudgetJs must resolve the runtime root through globalThis");
}
if (/\bwindow\s*\./.test(budgetBody)) {
  throw new Error("mappedPoolBudgetJs still references the page-only window global");
}
if (!telemetryBody.includes("const root=globalThis;")) {
  throw new Error("noteMappedPoolJs must resolve the runtime root through globalThis");
}
if (/\bwindow\s*\./.test(telemetryBody)) {
  throw new Error("noteMappedPoolJs still references the page-only window global");
}

const scriptOf = (body) => body.slice(body.indexOf('script = """') + 'script = """'.length, body.lastIndexOf('"""'));
const worker = vm.createContext({
  __gaiusWasmMemory: {},
  __gaiusMemoryTier: {lowMemory: false},
  location: {search: ""},
  URLSearchParams,
  Number,
  Math,
});
const budget = vm.runInContext(`(function(){${scriptOf(budgetBody)}})()`, worker);
if (budget !== 8 * 1024 * 1024) {
  throw new Error(`mappedPoolBudgetJs returned ${budget} in a Worker-like context`);
}
const note = scriptOf(telemetryBody);
vm.runInContext(`(function(hits, misses, pooledBytes, limitBytes){${note}})(3, 2, 4096, 8192)`, worker);
if (worker.__gaiusGLStats?.mappedPoolHits !== 3
    || worker.__gaiusGLStats?.mappedPoolMisses !== 2
    || worker.__gaiusGLStats?.mappedPoolIdleBytes !== 4096
    || worker.__gaiusGLStats?.mappedPoolLimitBytes !== 8192) {
  throw new Error("noteMappedPoolJs did not write Worker telemetry through globalThis");
}

console.log("browser OpenGL Worker safety smoke passed");
