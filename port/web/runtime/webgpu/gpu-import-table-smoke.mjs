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

// Exercise the primitive upload boundary through actual Wasm imports. The mock
// GPU operations record the exact bytes without requiring a browser device.
const bytes = (...values) => values.flat();
const uleb = value => {
  const result = [];
  do { const low = value & 127; value >>>= 7; result.push(low | (value ? 128 : 0)); } while (value);
  return result;
};
const name = text => [...uleb(text.length), ...[...text].map(char => char.charCodeAt(0))];
const section = (id, body) => [id, ...uleb(body.length), ...body];
const type = (parameters, returns) => [0x60, parameters, ...Array(parameters).fill(0x7f), returns,
  ...Array(returns).fill(0x7f)];
const uploadModule = new WebAssembly.Module(new Uint8Array(bytes(
  [0, 97, 115, 109, 1, 0, 0, 0],
  section(1, bytes([2], type(4, 0), type(5, 1))),
  section(2, bytes([3], ...["writeBuffer", "upload", "writeOrdered"].map((entry, index) =>
    bytes(name("gaiusGpu"), name(entry), [0, index === 2 ? 1 : 0])))),
  section(7, bytes([3], ...["writeBuffer", "upload", "writeOrdered"].map((entry, index) =>
    bytes(name(entry), [0, index]))))
)));
const memory = new WebAssembly.Memory({initial: 1, maximum: 3});
context.__gaiusWasmMemory = memory;
const gpu = context.GaiusWebGpu;
const captured = [];
function buffer(descriptor) {
  return {bytes: new Uint8Array(descriptor.size),
    getMappedRange() { return this.bytes.buffer; }, unmap() {}, destroy() {}};
}
gpu.device = {features: new Set(), limits: {}, createBuffer: buffer, createCommandEncoder() {
  return {copyBufferToBuffer(source, from, target, to, size) {
    assert.equal(size % 4, 0, "GPU copy size is aligned");
    target.bytes.set(source.bytes.slice(from, from + size), to);
    captured.push(Array.from(target.bytes.slice(to, to + size)));
  }, finish() { return {}; }};
}};
gpu.queue = {writeBuffer(target, offset, data, from, size) {
  target.bytes.set(new Uint8Array(data, from, size), offset);
  captured.push(Array.from(target.bytes.slice(offset, offset + size)));
}, submit() {}, onSubmittedWorkDone() { return Promise.resolve(); }};
gpu.uploadMode = "map";
gpu.ringConfigure(256, 256, 1024);
const destination = gpu.createBuffer(64, 0x88, "import-smoke");
const uploadImports = new WebAssembly.Instance(uploadModule, table).exports;
new Uint8Array(memory.buffer).set([90, 11, 22, 33, 44, 91], 63);
uploadImports.writeBuffer(destination, 12, 64, 4);
uploadImports.upload(destination, 16, 65, 4);
assert.equal(uploadImports.writeOrdered(destination, 20, 66, 2, 4), 1);
assert.deepEqual(captured, [[11, 22, 33, 44], [22, 33, 44, 91], [33, 44, 0, 0]],
  "direct imports preserve the selected linear-memory windows");
let refreshes = 0;
context.__gaiusLinear = {i8: new Int8Array(memory.buffer), refresh() {
  refreshes++; this.i8 = new Int8Array(memory.buffer);
}};
memory.grow(1);
new Uint8Array(memory.buffer).set([1, 2, 3, 4], 65536);
uploadImports.writeBuffer(destination, 0, 65536, 4);
assert.deepEqual(captured.at(-1), [1, 2, 3, 4], "direct imports refresh views after memory.grow");
assert.equal(refreshes, 1);
assert.deepEqual(Array.from(gpu.debugObject(destination).bytes.slice(20, 24)), [33, 44, 0, 0],
  "ordered uploads preserve the padded destination size");

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
  assert.equal(linked.gaiusGpu.regionArenaEnabled(), false, `${file} boolean imports default to false`);
  assert.equal(linked.gaiusGpu.regionArenaNextRetireEpoch(), 0, `${file} integer imports default to zero`);
  assert.equal(linked.gaiusGpu.regionArenaGeneration(), 0, `${file} generation imports default to zero`);
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
  assert.equal(linked.gaiusGpu.regionArenaEnabled(), 7, `${file} forwards the arena switch import`);
  assert.equal(linked.gaiusGpu.regionArenaNextRetireEpoch(), 7, `${file} forwards the arena epoch import`);
  assert.equal(linked.gaiusGpu.regionArenaGeneration(), 7, `${file} forwards the arena generation import`);
  linked.gaiusGpu.draw(3, 1, 0, 0);
  assert.deepEqual(calls.map((call) => call[0]),
    ["createFence", "regionArenaEnabled", "regionArenaNextRetireEpoch", "regionArenaGeneration", "draw"], `${file} forwards each call once`);
  assert.deepEqual(calls[4][1], [3, 1, 0, 0], `${file} forwards the arguments`);
}
console.log(`gpu import table smoke passed: ${imports.length} imports`);
