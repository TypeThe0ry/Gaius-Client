#!/usr/bin/env node
// Checks the production loader patch against a local TeaVM runtime. Nothing is downloaded.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync, readFileSync} from 'node:fs';
import {join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {runInNewContext} from 'node:vm';

const [input, destination] = process.argv.slice(2);
if (!input || !destination) throw new Error('Usage: wasmgc-date-import-check.mjs <stock-runtime.js> <output-directory>');
const output = resolve(destination);
mkdirSync(output, {recursive: true});
const patched = join(output, 'wasm-gc-runtime.js');
const patcher = fileURLToPath(new URL('../web/runtime/wasmgc/patch-wasm-gc-runtime.mjs', import.meta.url));
execFileSync(process.execPath, [patcher, resolve(input), patched], {stdio: 'inherit'});
const source = readFileSync(patched, 'utf8');
const start = source.indexOf('  function dateImports(imports) {');
assert.ok(start >= 0, 'dateImports exists');
const end = source.indexOf('\n  function ', start + 1);
assert.ok(end > start, 'dateImports ends before the next import group');
const dateSource = source.slice(start, end);
assert.match(dateSource, /currentTimeMillis: \(\) => Date\.now\(\)/);
let epochMillis = 0, allocations = 0, reads = 0;
class ClockDate extends Date {
  constructor(...args) {
    allocations++;
    super(...(args.length ? args : [epochMillis]));
  }
  static now() { reads++; return epochMillis; }
}
const imports = {};
runInNewContext(dateSource + '\ndateImports(imports);', {Date: ClockDate, imports});
for (const value of [0, -1, 1, -2208988800000, 1792022400123, 8640000000000000]) {
  epochMillis = value;
  assert.equal(imports.teavmDate.currentTimeMillis(), value, 'epoch milliseconds stay exact');
}
assert.equal(reads, 6, 'every call reads the current clock');
assert.equal(allocations, 0, 'epoch clock creates no Date objects');
assert.equal(imports.teavmDate.dateToString(0), new Date(0).toString(), 'calendar conversion stays intact');
console.log('WASMGC_DATE_IMPORT_OK epochCases=6 clockAllocations=0');
