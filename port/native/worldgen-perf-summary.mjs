import assert from 'node:assert/strict';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';

const [directory] = process.argv.slice(2);
assert(directory, 'usage: worldgen-perf-summary.mjs <output-directory>');
const output = resolve(directory);
const median = values => values.toSorted((left, right) => left - right)[Math.floor(values.length / 2)];
const groups = new Map();
for (const filename of (await readdir(output)).sort()) {
  const match = filename.match(/^final-(native|baseline|simd)-(before|after)-(.+)-(\d+)\.tsv$/);
  if (!match) continue;
  const lines = (await readFile(join(output, filename), 'utf8')).trim().split(/\r?\n/);
  assert.equal(lines.shift(), 'path\tstage\tround\tjobs\tmilliseconds\tms_per_chunk');
  for (const line of lines) {
    const [path, stage, round, jobs, milliseconds, elapsed] = line.split('\t');
    const key = [match[3], match[1], path, stage].join('/');
    if (!groups.has(key)) groups.set(key, {profile: match[3], runtime: match[1], path, stage, before: [], after: []});
    groups.get(key)[match[2]].push({pass: Number(match[4]), round: Number(round), jobs: Number(jobs), elapsed: Number(elapsed)});
    assert(Number.isFinite(Number(milliseconds)) && Number(elapsed) > 0 && Number(jobs) > 0);
  }
}
assert(groups.size > 0, 'no final measurements');
const results = [];
for (const group of groups.values()) {
  assert.equal(group.before.length, 21, 'expected three passes with seven rounds');
  assert.equal(group.after.length, 21);
  const before = median(group.before.map(row => row.elapsed));
  const after = median(group.after.map(row => row.elapsed));
  const reductions = [1, 2, 3].map(pass => {
    const previous = median(group.before.filter(row => row.pass === pass).map(row => row.elapsed));
    const next = median(group.after.filter(row => row.pass === pass).map(row => row.elapsed));
    return 100 * (1 - next / previous);
  });
  results.push({profile: group.profile, runtime: group.runtime, path: group.path, stage: group.stage,
    before, after, reductionPercent: 100 * (1 - after / before), speedup: before / after,
    jobsPerSecond: 1000 / after, passReductions: reductions, measuredJobs: group.before.reduce((sum, row) => sum + row.jobs, 0)});
}
let exactFrames = 0;
const profiles = [...new Set(results.map(row => row.profile))];
const sources = [];
for (const profile of profiles) {
  const beforeDirectory = join(output, 'final-before-jobs', profile);
  const afterDirectory = join(output, 'final-after-jobs', profile);
  for (const file of (await readdir(beforeDirectory)).filter(name => name.endsWith('.expected')).sort()) {
    const before = await readFile(join(beforeDirectory, file));
    assert.deepEqual(await readFile(join(afterDirectory, file)), before, profile + '/' + file);
    exactFrames++;
  }
  const ir = await readFile(join(output, 'reference', profile, 'overworld.gwir'));
  sources.push({profile, irBytes: ir.length, irSha256: createHash('sha256').update(ir).digest('hex')});
}
const artifacts = [];
for (const phase of ['support-before', 'after']) {
  for (const variant of ['baseline', 'simd']) {
    const path = join(output, phase + '-' + variant, 'wasm32-unknown-unknown', 'release', 'gaius_worldgen_wasm.wasm');
    const bytes = await readFile(path);
    artifacts.push({phase, variant, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex')});
  }
}
const pairedResults = [];
for (const filename of (await readdir(output)).sort()) {
  const match = filename.match(/^paired-(baseline|simd)-(.+)\.tsv$/);
  if (!match) continue;
  const lines = (await readFile(join(output, filename), 'utf8')).trim().split(/\r?\n/);
  assert.equal(lines.shift(), 'phase\tstage\tround\tjobs\tmilliseconds\tms_per_chunk');
  const stages = new Map();
  for (const line of lines) {
    const [phase, stage, round, jobs, milliseconds, elapsed] = line.split('\t');
    assert(['before', 'after'].includes(phase));
    assert(Number.isFinite(Number(milliseconds)) && Number(elapsed) > 0 && Number(jobs) > 0);
    if (!stages.has(stage)) stages.set(stage, new Map());
    if (!stages.get(stage).has(Number(round))) stages.get(stage).set(Number(round), {});
    const pair = stages.get(stage).get(Number(round));
    assert(!pair[phase], 'duplicate paired round');
    pair[phase] = {elapsed: Number(elapsed), jobs: Number(jobs)};
  }
  for (const [stage, pairs] of stages) {
    assert.equal(pairs.size, 21);
    const samples = [...pairs.values()];
    for (const pair of samples) assert(pair.before && pair.after && pair.before.jobs === pair.after.jobs);
    const before = median(samples.map(pair => pair.before.elapsed));
    const after = median(samples.map(pair => pair.after.elapsed));
    const reductions = samples.map(pair => 100 * (1 - pair.after.elapsed / pair.before.elapsed)).toSorted((left, right) => left - right);
    pairedResults.push({profile: match[2], runtime: match[1], path: 'paired-framed', stage, before, after,
      reductionPercent: 100 * (1 - after / before), speedup: before / after, jobsPerSecond: 1000 / after,
      pairedReductionPercent: median(reductions), pairedReductionQuartiles: [reductions[5], reductions[15]],
      fasterRounds: reductions.filter(value => value > 0).length, rounds: pairs.size,
      measuredJobs: samples.reduce((sum, pair) => sum + pair.before.jobs, 0)});
  }
}
await writeFile(join(output, 'summary.json'), JSON.stringify({exactFrames, sources, artifacts, results, pairedResults}, null, 2) + '\n');
console.log('exact before/after frames: ' + exactFrames);
for (const row of results.filter(row => row.stage === 'surface' && (row.path === 'core' || row.runtime !== 'native'))) {
  console.log([row.profile, row.runtime, row.path, row.before.toFixed(3), row.after.toFixed(3), row.reductionPercent.toFixed(1) + '%', row.passReductions.map(value => value.toFixed(1)).join(',')].join('\t'));
}
for (const row of pairedResults.filter(row => row.stage === 'surface')) {
  console.log([row.profile, row.runtime, row.path, row.before.toFixed(3), row.after.toFixed(3),
    row.pairedReductionPercent.toFixed(1) + '%', row.fasterRounds + '/' + row.rounds].join('\t'));
}
