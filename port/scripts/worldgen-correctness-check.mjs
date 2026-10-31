import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {gzipSync} from 'node:zlib';
import {loadProfiles, reviewedTable} from '../tools/kernelmirror/kernelmirror.mjs';
import {compareReports, hookCoverage, executionCoverage} from './worldgen-correctness.mjs';
import {correctnessHarness, firstBlockDifference, readSnapshot} from './worldgen-correctness-support.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
let checks = 0;
function check(name, action) {
  action();
  checks++;
  console.log('PASS ' + name);
}
function snapshot(palette, cells, shape = {x: -3, z: 7, minY: -64, height: 16}) {
  const parts = [];
  const integer = value => {
    const bytes = Buffer.alloc(4);
    bytes.writeInt32BE(value);
    parts.push(bytes);
  };
  for (const value of [0x47574331, shape.x, shape.z, shape.minY, shape.height, palette.length]) integer(value);
  for (const name of palette) {
    const bytes = Buffer.from(name);
    integer(bytes.length);
    parts.push(bytes);
  }
  for (const value of cells) integer(value);
  return gzipSync(Buffer.concat(parts));
}
const states = ['Block{minecraft:air}', 'Block{minecraft:stone}', 'Block{minecraft:water}[level=0]'];
const cells = new Int32Array(4096).fill(1);
const before = readSnapshot(snapshot(states, cells));
check('bounded block snapshot round trip', () => assert.equal(before.palette[before.cells[400]], states[1]));
check('palette order is not a block difference', () => {
  const after = readSnapshot(snapshot([states[1], states[0]], new Int32Array(4096)));
  assert.equal(firstBlockDifference(before, after), null);
});
check('first differing absolute coordinate and complete state', () => {
  const changed = cells.slice();
  changed[256 + 3 * 16 + 5] = 2;
  assert.deepEqual(firstBlockDifference(before, readSnapshot(snapshot(states, changed))),
    {x: -43, y: -63, z: 115, expected: states[1], actual: states[2]});
});
check('snapshot identity cannot be mixed', () => assert.throws(() => firstBlockDifference(before,
  {...before, x: 8}), /identity mismatch/));
check('truncated snapshot rejects', () => assert.throws(() => readSnapshot(snapshot(states, cells.slice(0, 4))),
  /Truncated/));
check('invalid palette index rejects', () => assert.throws(() => readSnapshot(snapshot(states,
  new Int32Array(4096).fill(3))), /palette index/));
check('unbounded snapshot shape rejects', () => assert.throws(() => readSnapshot(snapshot(states, cells,
  {x: 0, z: 0, minY: 0, height: 100000})), /shape/));
const hash = 'a'.repeat(64);
const report = {seed: '12345', minecraft: 'profile', mode: 'full', side: 1,
  chunks: [{x: 0, z: 0, status: 'minecraft:full', blocks: hash, biomes: hash}]};
check('zero-difference control', () => assert.deepEqual(compareReports(report, structuredClone(report), 1, '12345'), []));
check('empty evidence does not pass', () => assert.throws(() => compareReports({...report, chunks: []}, report,
  1, '12345'), /coverage/));
check('changed seed rejects', () => assert.throws(() => compareReports(report, {...report, seed: '1'},
  1, '12345'), /identity/));
check('changed profile rejects', () => assert.throws(() => compareReports(report, {...report, minecraft: 'other'},
  1, '12345'), /identity/));
check('missing status cannot pass', () => assert.throws(() => compareReports(report,
  {...report, chunks: [{...report.chunks[0], status: 'minecraft:features'}]}, 1, '12345'), /status/));
check('malformed hash cannot pass', () => assert.throws(() => compareReports(report,
  {...report, chunks: [{...report.chunks[0], blocks: ''}]}, 1, '12345'), /hash/));
check('block and biome differences remain separate', () => assert.deepEqual(compareReports(report,
  {...report, chunks: [{...report.chunks[0], biomes: 'b'.repeat(64)}]}, 1, '12345'),
  [{x: 0, z: 0, blocksDiffer: false, biomesDiffer: true}]));
check('duplicate chunks cannot establish coverage', () => {
  const duplicate = {...report, side: 2, chunks: Array(4).fill(report.chunks[0])};
  assert.throws(() => compareReports(duplicate, duplicate, 2, '12345'), /Duplicate/);
});
check('a scattered set is not a completed target square', () => {
  const scattered = {...report, side: 2, chunks: [0, 1, 2, 3].map(chunkX => ({...report.chunks[0], x: chunkX}))};
  assert.throws(() => compareReports(scattered, scattered, 2, '12345'), /target square/);
});
check('noninteger coordinates cannot pass', () => {
  const fractional = {...report, chunks: [{...report.chunks[0], x: 0.5}]};
  assert.throws(() => compareReports(fractional, fractional, 1, '12345'), /target square/);
});
const coverage = {hooksAvailable: true, hooks: 'all', errors: 0, jobs: {'402': 1, '403': 1, '404': 1}};
check('split terrain needs actual noise and surface jobs', () => {
  assert.equal(hookCoverage(coverage, 'router-f64'), true);
  assert.equal(hookCoverage({...coverage, jobs: {'402': 1, '403': 1}}, 'router-f64'), false);
});
check('combined terrain has no separate surface job', () => {
  assert.equal(hookCoverage({...coverage, jobs: {'402': 1, '403': 1}}, 'sampler-f32'), true);
});
check('isolated noise cannot silently run surface or biomes', () => {
  assert.equal(hookCoverage({...coverage, hooks: 'noise', jobs: {'403': 1}}, 'router-f64'), true);
  assert.equal(hookCoverage({...coverage, hooks: 'noise'}, 'router-f64'), false);
});
check('unhooked or errored runs cannot certify parity', () => {
  assert.equal(hookCoverage({...coverage, hooksAvailable: false}, 'router-f64'), false);
  assert.equal(hookCoverage({...coverage, errors: 1}, 'router-f64'), false);
  assert.equal(hookCoverage({...coverage, jobs: {}}, 'router-f64'), false);
  assert.equal(hookCoverage({...coverage, hooks: 'off'}, 'router-f64'), false);
});
check('declines are bounded attempts, never kernel errors or successes', () => {
  assert.equal(hookCoverage({...coverage, declines: {'402': coverage.jobs['402']}}, 'router-f64'), true);
  assert.equal(hookCoverage({...coverage, declines: {'402': coverage.jobs['402'] + 1}}, 'router-f64'), false);
  assert.equal(hookCoverage({...coverage, declines: {'405': 1}}, 'router-f64'), false);
});
check('a manifest without JVM seams proves vanilla-only parity, not kernel execution', () => {
  const profile = {worldgenReference: {jvmHooks: []}};
  const row = {...coverage, hooksAvailable: false, jobs: {}, declines: {}};
  assert.equal(executionCoverage(row, profile), true);
  assert.equal(executionCoverage({...row, jobs: {'403': 1}}, profile), false);
  assert.equal(executionCoverage({...row, errors: 1}, profile), false);
});
check('harness requires explicit unique anchors', () => {
  const source = 'ChunkStatus status = "terrain".equals(options.mode()) ? ParityCompat.TERRAIN : ChunkStatus.FULL;\n'
    + '                List<ChunkAccess> completed = new ArrayList<>();\n'
    + '                for (int index = 0; index < positions.size(); index++) {\n'
    + '                    ChunkAccess chunk = completed.get(index);';
  const transformed = correctnessHarness(source);
  assert.match(transformed, /prepareNeighbours\(chunks, positions, status\)/);
  assert.match(transformed, /finishNeighbours\(chunks, positions, status\)/);
  assert.match(transformed, /WorldgenCorrectness.snapshot\(chunk\)/);
  assert.ok(transformed.indexOf('prepareNeighbours(chunks, positions, status)')
    < transformed.indexOf('List<ChunkAccess> completed'));
  assert.ok(transformed.indexOf('finishNeighbours(chunks, positions, status)')
    < transformed.indexOf('WorldgenCorrectness.snapshot(chunk)'));
  assert.throws(() => correctnessHarness(transformed), /anchor mismatch/);
});
check('profile policy and retained mirror tables agree without a JVM', () => {
  for (const profile of loadProfiles(root)) {
    const lock = readFileSync(join(root, 'port/mc/profiles', profile.id, 'kernel-mirror.lock'));
    assert.equal(createHash('sha256').update(lock).digest('hex'), profile.kernelMirror.lockSha256);
    const expected = reviewedTable(root, profile);
    assert.deepEqual(JSON.parse(readFileSync(join(root, 'port/mc/profiles', profile.id, 'kernel-semantics.json'))), expected);
    if ((profile.worldgenReference?.jvmHooks ?? []).length) {
      const enabled = profile.kernelMirror.enabled.worldgen !== false;
      assert.equal(expected.kernels.worldgen.enabled, enabled);
      assert.deepEqual(expected.kernels.worldgen.reasons, enabled ? [] : ['PROFILE_DEFAULT_OFF']);
    }
  }
});
console.log(`${checks} correctness-tool checks passed; no JVM/full-chunk parity ran`);
