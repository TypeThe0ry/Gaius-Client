import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {namedSampleModule, splitSymbols} from './wasm-symbols.mjs';

test('symbolizes a soak profile larger than the JavaScript argument limit', () => {
  const dir = mkdtempSync(join(tmpdir(), 'soak-profile-test-'));
  try {
    const {named} = namedSampleModule(1);
    const {stripped, symbols} = splitSymbols(named, 'sample.wasm');
    writeFileSync(join(dir, 'module.wasm'), stripped);
    writeFileSync(join(dir, 'symbols.wasm'), symbols);
    const samples = Array(250000).fill(1), timeDeltas = Array(samples.length).fill(1000);
    timeDeltas[timeDeltas.length - 1] = 12345;
    writeFileSync(join(dir, 'input.cpuprofile'), JSON.stringify({
      nodes: [{id: 1, callFrame: {functionName: 'wasm-function[0]'}}],
      startTime: 0, endTime: 250011345, samples, timeDeltas,
    }));
    const result = spawnSync(process.execPath, [
      fileURLToPath(new URL('./wasm-profile.mjs', import.meta.url)),
      join(dir, 'input.cpuprofile'), join(dir, 'symbols.wasm'), join(dir, 'module.wasm'), join(dir, 'out'),
    ], {encoding: 'utf8', maxBuffer: 1024 * 1024});
    assert.equal(result.status, 0, result.stderr);
    const analysis = JSON.parse(readFileSync(join(dir, 'out/profile-analysis.json'), 'utf8'));
    assert.equal(analysis.samples, samples.length);
    assert.equal(analysis.maxSampleGapMs, 12.345);
    assert.equal(analysis.resolvedNodes, 1);
    assert.equal(analysis.methods[0].name, 'f0');
    assert.equal(analysis.sampledMs, 250011.345);
  } finally {
    rmSync(dir, {recursive: true, force: true});
  }
});
