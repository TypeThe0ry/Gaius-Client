import {closeSync, openSync, readFileSync, readSync, writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {performance} from 'node:perf_hooks';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const [modulePath, fixturePath, reportPath] = process.argv.slice(2);
if (!reportPath) throw new Error('Usage: node worldgen-ore-wasm-golden.mjs <wasm> <fixtures> <report>');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..').toLowerCase();
const normalizedReport = resolve(reportPath).toLowerCase();
if (normalizedReport === root || normalizedReport.startsWith(root + '/') || normalizedReport.startsWith(root + '\\')) {
  throw new Error('Use a separate output directory');
}
const moduleBytes = readFileSync(modulePath);
const instance = new WebAssembly.Instance(new WebAssembly.Module(moduleBytes), {});
const exports = instance.exports;
if (typeof exports.run_ore !== 'function') throw new Error('Missing ore export');
const input = openSync(fixturePath, 'r');
const digest = createHash('sha256');
let jobs = 0;
const phases = [{jobs: 0, computeMilliseconds: 0}, {jobs: 0, computeMilliseconds: 0}];
function read(bytes, allowEnd = false) {
  let offset = 0;
  while (offset < bytes.length) {
    const count = readSync(input, bytes, offset, bytes.length - offset, null);
    if (count === 0 && offset === 0 && allowEnd) return false;
    if (count === 0) throw new Error('Truncated ore fixture');
    offset += count;
  }
  digest.update(bytes);
  return true;
}
try {
  const header = Buffer.alloc(8);
  while (read(header, true)) {
    const inputLength = header.readUInt32LE(0);
    const outputLength = header.readUInt32LE(4);
    if (inputLength < 16 || outputLength < 20 || Math.max(inputLength, outputLength) > 16 * 1024 * 1024) {
      throw new Error('Invalid ore fixture length');
    }
    const job = Buffer.alloc(inputLength);
    const expected = Buffer.alloc(outputLength);
    read(job);
    read(expected);
    const phase = job.readUInt32LE(16);
    if (phase > 1) throw new Error('Invalid ore phase');
    const pointer = exports.alloc(job.length);
    let descriptor = 0;
    try {
      new Uint8Array(exports.memory.buffer, pointer, job.length).set(job);
      const start = performance.now();
      descriptor = exports.run_ore(pointer, job.length);
      phases[phase].computeMilliseconds += performance.now() - start;
      const view = new DataView(exports.memory.buffer);
      const status = view.getUint32(descriptor, true);
      const resultPointer = view.getUint32(descriptor + 4, true);
      const resultLength = view.getUint32(descriptor + 8, true);
      const actual = Buffer.from(exports.memory.buffer, resultPointer, resultLength);
      if (status !== 0 || !actual.equals(expected)) throw new Error('Ore golden mismatch at job ' + jobs);
      phases[phase].jobs++;
      jobs++;
    } finally {
      if (descriptor) exports.release(descriptor);
      exports.dealloc(pointer, job.length);
    }
  }
} finally {
  closeSync(input);
}
if (jobs === 0 || phases[0].jobs !== phases[1].jobs) throw new Error('Empty or incomplete ore golden');
const report = {jobs, cases: phases[0].jobs, phases, fixtureSha256: digest.digest('hex'),
  moduleSha256: createHash('sha256').update(moduleBytes).digest('hex'),
  measurement: 'Node wasm compute only; excludes JVM snapshot, IPC, worker scheduling and result installation'};
writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n');
console.log('ore wasm golden: ' + report.cases + ' exact ordered cases; ' + phases
  .map(phase => phase.computeMilliseconds.toFixed(3) + 'ms').join(' / '));
