// Checks a built kernel module the way port/web/kernels/kernel-worker.js uses it:
// no imports, the expected exports, and every probe job answered byte for byte like
// the native build (job-N.bin / job-N.expected written by the wasm_probe example).
//
//   node wasm-check.mjs <module.wasm> <probe-dir> <export>...
import {readFileSync, readdirSync} from "node:fs";
import {join} from "node:path";

const [modulePath, probeDir, ...kernels] = process.argv.slice(2);
if (!modulePath || !probeDir || kernels.length === 0) {
  console.error("usage: node wasm-check.mjs <module.wasm> <probe-dir> <run_export>...");
  process.exit(2);
}

const fail = (message) => {
  console.error(`wasm check failed: ${message}`);
  process.exit(1);
};

const module = new WebAssembly.Module(readFileSync(modulePath));
const imports = WebAssembly.Module.imports(module);
if (imports.length > 0) fail(`unexpected imports ${imports.map((i) => `${i.module}.${i.name}`).join(", ")}`);
const exported = new Set(WebAssembly.Module.exports(module).map((e) => e.name));
for (const name of ["memory", "alloc", "dealloc", "release", "gaius_abi_version", ...kernels]) {
  if (!exported.has(name)) fail(`missing export ${name}`);
}

const {exports} = new WebAssembly.Instance(module, {});
if (exports.gaius_abi_version() !== 1) fail(`abi version ${exports.gaius_abi_version()}`);

const probes = readdirSync(probeDir).filter((name) => name.endsWith(".bin")).sort();
if (probes.length === 0) fail(`no probe jobs in ${probeDir}`);
for (const name of probes) {
  const job = readFileSync(join(probeDir, name));
  const expected = readFileSync(join(probeDir, name.replace(/\.bin$/, ".expected")));
  const ptr = exports.alloc(job.length) >>> 0;
  new Uint8Array(exports.memory.buffer, ptr, job.length).set(job);
  const desc = exports[kernels[0]](ptr, job.length) >>> 0;
  if (desc === 0) fail(`${name}: null descriptor`);
  const view = new DataView(exports.memory.buffer);
  const status = view.getUint32(desc, true);
  const dataPtr = view.getUint32(desc + 4, true);
  const dataLength = view.getUint32(desc + 8, true);
  const data = new Uint8Array(exports.memory.buffer, dataPtr, dataLength).slice();
  exports.release(desc);
  exports.dealloc(ptr, job.length);
  if (status !== 0) fail(`${name}: status ${status}: ${new TextDecoder().decode(data)}`);
  if (dataPtr % 8 !== 0) fail(`${name}: result data is not 8-byte aligned`);
  if (Buffer.compare(Buffer.from(data), expected) !== 0) fail(`${name}: result differs from the native build`);
}
console.log(`wasm check passed: ${probes.length} probe jobs bit-identical to native`);
