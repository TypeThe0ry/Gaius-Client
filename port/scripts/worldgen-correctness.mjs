import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {createWriteStream, existsSync, mkdirSync, readFileSync, writeFileSync} from 'node:fs';
import {dirname, isAbsolute, join, relative, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {loadProfiles} from '../tools/kernelmirror/kernelmirror.mjs';
import {firstBlockDifference, readSnapshot} from './worldgen-correctness-support.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const json = path => JSON.parse(readFileSync(path, 'utf8'));
const save = (path, value) => writeFileSync(path, JSON.stringify(value, null, 2) + '\n');

export function compareReports(expected, actual, side, seed) {
  if (expected.seed !== seed || actual.seed !== seed || expected.side !== side || actual.side !== side
      || expected.minecraft !== actual.minecraft || expected.mode !== actual.mode) {
    throw new Error('Chunk report identity mismatch');
  }
  if (expected.chunks.length !== side * side || actual.chunks.length !== side * side) {
    throw new Error('Incomplete target coverage');
  }
  const expectedRows = new Map(expected.chunks.map(chunk => [chunk.x + ',' + chunk.z, chunk]));
  const actualRows = new Map(actual.chunks.map(chunk => [chunk.x + ',' + chunk.z, chunk]));
  if (expectedRows.size !== side * side || actualRows.size !== side * side) throw new Error('Duplicate chunk');
  const originX = Math.min(...expected.chunks.map(chunk => chunk.x));
  const originZ = Math.min(...expected.chunks.map(chunk => chunk.z));
  const differences = [];
  for (const [key, before] of expectedRows) {
    const after = actualRows.get(key);
    if (!after || after.status !== before.status) throw new Error('Chunk set or status mismatch: ' + key);
    for (const chunk of [before, after]) {
      if (!Number.isSafeInteger(chunk.x) || !Number.isSafeInteger(chunk.z) || !chunk.status
          || chunk.x < originX || chunk.x >= originX + side || chunk.z < originZ || chunk.z >= originZ + side) {
        throw new Error('Invalid target square or status');
      }
      if (![chunk.blocks, chunk.biomes].every(hash => /^[a-f0-9]{64}$/.test(hash))) throw new Error('Invalid hash');
    }
    if (before.blocks !== after.blocks || before.biomes !== after.biomes) differences.push({x: before.x,
      z: before.z, blocksDiffer: before.blocks !== after.blocks, biomesDiffer: before.biomes !== after.biomes});
  }
  return differences;
}

export function hookCoverage(row, densityGraph) {
  if (!row.hooksAvailable || row.errors !== 0 || !['all', 'biomes', 'noise', 'terrain'].includes(row.hooks)) return false;
  const expected = row.hooks === 'biomes' ? ['402'] : row.hooks === 'noise' ? ['403']
    : densityGraph === 'sampler-f32' ? ['403'] : ['403', '404'];
  if (row.hooks === 'all') expected.push('402');
  if (!Object.entries(row.declines ?? {}).every(([kind, count]) => expected.includes(kind)
      && Number.isSafeInteger(count) && count >= 0 && count <= row.jobs?.[kind])) return false;
  if (!expected.every(kind => Number.isSafeInteger(row.jobs?.[kind]) && row.jobs[kind] > 0)) return false;
  return Object.entries(row.jobs).every(([kind, count]) => Number.isSafeInteger(count) && count >= 0
    && (!['402', '403', '404'].includes(kind) || expected.includes(kind)));
}

export function executionCoverage(row, profile) {
  if ((profile.worldgenReference.jvmHooks ?? []).length) return hookCoverage(row, profile.kernelSemantics.densityGraph);
  return row.hooksAvailable === false && row.errors === 0
    && Object.values(row.jobs ?? {}).every(count => count === 0)
    && Object.values(row.declines ?? {}).every(count => count === 0);
}

export function compareRuns(expectedDirectory, actualDirectory, profile, seeds, side) {
  const before = json(join(expectedDirectory, profile.id, 'provenance.json'));
  const after = json(join(actualDirectory, profile.id, 'provenance.json'));
  for (const provenance of [before, after]) {
    if (provenance.profile !== profile.id || provenance.side !== side
        || JSON.stringify(provenance.seeds) !== JSON.stringify(seeds)
        || provenance.hashTiming !== 'after-targets-and-halo' || !provenance.correctness
        || provenance.correctness.halo !== 2 || provenance.correctness.processorCount !== 1
        || provenance.correctness.backgroundThreads !== 1
        || provenance.correctness.statusOrder !== 'features-before-full') throw new Error('Unsettled or mismatched provenance');
  }
  if (before.jarSha256 !== after.jarSha256 || before.correctness.stage !== after.correctness.stage) {
    throw new Error('Jar or stage changed between runs');
  }
  if (before.backend !== 'off' || before.enabled || before.correctness.hooks !== 'off'
      || !/^[a-f0-9]{64}$/.test(before.jarSha256)) throw new Error('Expected run is not a plain vanilla control');
  return seeds.map(seed => {
    const expectedPath = join(expectedDirectory, profile.id);
    const actualPath = join(actualDirectory, profile.id);
    const differences = compareReports(json(join(expectedPath, `chunks-${seed}.json`)),
      json(join(actualPath, `chunks-${seed}.json`)), side, seed);
    for (const difference of differences) {
      if (!difference.blocksDiffer) continue;
      const name = difference.x + ',' + difference.z + '.blocks.gz';
      const expectedSnapshot = readSnapshot(readFileSync(join(expectedPath, 'snapshots-' + seed, name)));
      const actualSnapshot = readSnapshot(readFileSync(join(actualPath, 'snapshots-' + seed, name)));
      if (expectedSnapshot.x !== difference.x || expectedSnapshot.z !== difference.z) {
        throw new Error('Snapshot does not belong to differing chunk');
      }
      difference.firstBlock = firstBlockDifference(expectedSnapshot, actualSnapshot);
      if (!difference.firstBlock) throw new Error('Hash changed but block snapshots match');
    }
    const host = json(join(actualPath, `status-${seed}.json`)).kernelHost;
    return {profile: profile.id, seed, chunks: side * side,
      blockDifferences: differences.filter(chunk => chunk.blocksDiffer).length,
      biomeDifferences: differences.filter(chunk => chunk.biomesDiffer).length,
      hooksAvailable: after.correctness.hooksAvailable, hooks: after.correctness.hooks,
      backend: after.backend, jobs: host.jobs, declines: host.declines ?? {}, errors: host.errors, differences};
  });
}

async function run(output, name, profiles, seeds, side, backend, artifact, hooks) {
  const directory = join(output, name);
  if (existsSync(directory)) throw new Error('Use a fresh run directory: ' + directory);
  mkdirSync(directory, {recursive: true});
  for (const profile of profiles) {
    const log = createWriteStream(join(directory, profile.id + '.log'), {flags: 'wx'});
    const child = spawn(process.execPath, [join(root, 'port/scripts/worldgen-status-perf.mjs'), directory,
      profile.id, seeds.join(','), String(side), backend, artifact ?? '', 'plain'], {cwd: root,
      windowsHide: true, env: {...process.env, GAIUS_PERF_CORRECTNESS: '1', GAIUS_PERF_WARMUP_SIDE: '0',
        GAIUS_CORRECTNESS_HOOKS: hooks, GAIUS_CORRECTNESS_DIAGNOSTIC: '1'}});
    child.stdout.pipe(log, {end: false});
    child.stderr.pipe(log, {end: false});
    await new Promise((resolveRun, reject) => {
      child.on('error', reject);
      child.on('close', code => code === 0 ? resolveRun() : reject(new Error(name + ' failed: ' + code)));
    }).finally(() => log.end());
    console.log(`${name}: ${profile.id} complete`);
  }
  return directory;
}

async function main() {
  const [destination, mode = 'control', backend = 'native', artifact, selected,
    seedText = '12345,987654321', sideText = '20'] = process.argv.slice(2);
  if (!destination || !['control', 'bisect', 'gate'].includes(mode)) {
    throw new Error('Usage: node worldgen-correctness.mjs <output> <control|bisect|gate>'
      + ' [native|baseline] [artifact] [profile] [seeds] [side]');
  }
  const output = resolve(destination);
  const relativeRoot = relative(root, output);
  if (!relativeRoot || (!relativeRoot.startsWith('..') && !isAbsolute(relativeRoot))) {
    throw new Error('Use an external output directory');
  }
  const profiles = loadProfiles(root).filter(profile => profile.worldgenReference && (!selected || profile.id === selected));
  const seeds = seedText.split(',');
  const side = Number(sideText);
  const stage = process.env.GAIUS_CORRECTNESS_STAGE ?? 'full';
  for (const seed of seeds) {
    if (!/^-?\d+$/.test(seed) || BigInt(seed).toString() !== seed
        || BigInt(seed) < -(1n << 63n) || BigInt(seed) >= (1n << 63n)) throw new Error('Invalid Java long seed');
  }
  if (!profiles.length || !seeds.length || new Set(seeds).size !== seeds.length
      || !Number.isInteger(side) || side < 1 || side > 32) throw new Error('Invalid matrix');
  mkdirSync(output, {recursive: true});
  if (mode === 'control') {
    const first = await run(output, 'off-a', profiles, seeds, side, 'off', '', 'off');
    const second = await run(output, 'off-b', profiles, seeds, side, 'off', '', 'off');
    const rows = profiles.flatMap(profile => compareRuns(first, second, profile, seeds, side));
    const deterministic = rows.every(row => row.blockDifferences === 0 && row.biomeDifferences === 0);
    save(join(output, 'control.json'), {seeds, side, stage, profiles: profiles.map(profile => profile.id), deterministic, rows});
    if (!deterministic) throw new Error('OFF/OFF is not deterministic; do not bisect or promote');
    return;
  }
  const control = json(join(output, 'control.json'));
  if (!control.deterministic || JSON.stringify(control.seeds) !== JSON.stringify(seeds)
      || control.side !== side || control.stage !== stage
      || JSON.stringify(control.profiles) !== JSON.stringify(profiles.map(profile => profile.id))) {
    throw new Error('A matching zero-difference OFF/OFF control is required first');
  }
  if (!['native', 'baseline'].includes(backend) || !artifact || !existsSync(artifact)) {
    throw new Error('Supply a real offline native or baseline artifact');
  }
  const artifactSha256 = createHash('sha256').update(readFileSync(artifact)).digest('hex');
  const rows = [];
  for (const hooks of mode === 'bisect' ? ['biomes', 'noise', 'terrain', 'all'] : ['all']) {
    const applicable = mode === 'gate' ? profiles : profiles.filter(profile => (profile.worldgenReference.jvmHooks ?? []).length
      && (hooks !== 'noise' || profile.kernelSemantics.densityGraph !== 'sampler-f32'));
    if (!applicable.length) continue;
    const actual = await run(output, mode + '-' + backend + '-' + hooks, applicable, seeds, side, backend, artifact, hooks);
    rows.push(...applicable.flatMap(profile => compareRuns(join(output, 'off-a'), actual, profile, seeds, side)));
  }
  const unhookedProfiles = profiles.filter(profile => !(profile.worldgenReference.jvmHooks ?? []).length).map(profile => profile.id);
  const parityPassed = rows.length > 0 && rows.every(row => row.blockDifferences === 0 && row.biomeDifferences === 0);
  const exercised = rows.length > 0 && rows.every(row => executionCoverage(row,
    profiles.find(profile => profile.id === row.profile)));
  const completeProfileCoverage = profiles.every(profile => seeds.every(seed => rows.some(row => row.profile === profile.id && row.seed === seed)));
  const requiredMatrix = !selected && stage === 'full' && side === 20 && seedText === '12345,987654321';
  save(join(output, mode + '-' + backend + '.json'), {backend, artifactSha256, seeds, side, stage,
    parityPassed, exercised, completeProfileCoverage, requiredMatrix, unhookedProfiles,
    vanillaOnlyProfiles: unhookedProfiles,
    promotionAllowed: false, diagnosticOverride: true, rows});
  if (!parityPassed || !exercised) throw new Error('Hook parity or real-job coverage failed');
  if (mode === 'gate' && !completeProfileCoverage) throw new Error('Final gate lacks profile/seed cases');
  if (mode === 'gate' && !requiredMatrix) throw new Error('Final gate requires all profiles, both acceptance seeds and 400 full-status chunks');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { await main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
