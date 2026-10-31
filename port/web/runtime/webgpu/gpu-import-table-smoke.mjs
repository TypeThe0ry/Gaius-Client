import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

// The WasmGC client links every gaiusGpu import at instantiation, so the glue table and the
// WebGL2-path link stubs in the boot page and the Worker must name exactly the Java imports.
const read = (relative) => readFileSync(new URL(relative, import.meta.url), "utf8");
const java = read("../../../gpu/webgpu-core/src/target-wasmgc/java/dev/gaius/gpu/webgpu/WebGpuGlue.java");
const imports = [...java.matchAll(/@Import\(module = "gaiusGpu", name = "(\w+)"\)/g)].map((m) => m[1]).sort();
assert.ok(imports.length > 0, "WebGpuGlue declares gaiusGpu imports");
assert.equal(new Set(imports).size, imports.length, "gaiusGpu import names are unique");

const context = vm.createContext({ console, performance });
vm.runInContext(read("gpu-glue.js"), context, { filename: "gpu-glue.js" });
const table = {};
context.GaiusWebGpu.installImports(table);
const glueKeys = Object.keys(table.gaiusGpu).sort();
assert.deepEqual(glueKeys, imports, "gpu-glue.js import table matches WebGpuGlue imports");
for (const name of glueKeys) {
  assert.equal(typeof table.gaiusGpu[name], "function", `gpu-glue.js import ${name} is callable`);
}

// The boot page and the Worker link forwarders before the glue is known. Evaluate their
// installGpuImports in isolation: the forwarders must name exactly the Java imports, return the
// import's default while no glue is loaded, and reach a glue that loads after instantiation.
function linkStubs(file, globalName) {
  const source = read(file);
  const start = source.indexOf("  function installGpuImports(imports) {");
  assert.ok(start >= 0, `${file} declares installGpuImports`);
  const end = source.indexOf("\n  }\n", start);
  assert.ok(end > start, `${file} installGpuImports ends`);
  const fn = source.slice(start, end + 4);
  const sandbox = vm.createContext({});
  vm.runInContext(`var ${globalName} = this;\n${fn}\nthis.installGpuImports = installGpuImports;`, sandbox,
    { filename: file });
  return sandbox;
}
for (const [file, globalName] of [["../wasmgc/gaius-wasm-boot.js", "g"], ["../wasmgc/gaius-wasm-worker.js", "root"]]) {
  const sandbox = linkStubs(file, globalName);
  const linked = {};
  sandbox.installGpuImports(linked);
  assert.deepEqual(Object.keys(linked.gaiusGpu).sort(), imports, `${file} link stubs match WebGpuGlue imports`);
  assert.equal(linked.gaiusGpu.createFence(), 0, `${file} stubs return the default without a glue`);
  assert.equal(linked.gaiusGpu.draw(3, 1, 0, 0), undefined, `${file} void stubs are inert without a glue`);
  const calls = [];
  sandbox.GaiusWebGpu = {
    importTable() {
      const late = {};
      for (const name of imports) late[name] = (...args) => { calls.push([name, args]); return 7; };
      return late;
    }
  };
  assert.equal(linked.gaiusGpu.createFence(), 7, `${file} stubs forward to a glue loaded after linking`);
  linked.gaiusGpu.draw(3, 1, 0, 0);
  assert.deepEqual(calls.map((call) => call[0]), ["createFence", "draw"], `${file} forwards each call once`);
  assert.deepEqual(calls[1][1], [3, 1, 0, 0], `${file} forwards the arguments`);
}
console.log(`gpu import table smoke passed: ${imports.length} imports`);
