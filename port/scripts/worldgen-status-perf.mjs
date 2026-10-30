import {existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {delimiter, dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {sourceFile} from '../tools/sourcesets/sourcesets.mjs';
import {evaluateProfile} from '../tools/kernelmirror/kernelmirror.mjs';
import {correctnessHarness, waitJvmHeadroom} from './worldgen-correctness-support.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const [destination, selected, seedText = '12345,987654321', sideText = '20', backend = 'off', artifact,
  mode = 'status'] = process.argv.slice(2);
if (!destination) throw new Error('Usage: node worldgen-status-perf.mjs <output> [profile] [seeds] [side]'
  + ' [off|native|baseline|simd] [artifact] [status|ore-golden|ore-kernel|plain|ore-kernel-plain]');
const output = resolve(destination);
const normalizedOutput = output.toLowerCase();
const normalizedRoot = root.toLowerCase();
if (normalizedOutput === normalizedRoot || normalizedOutput.startsWith(normalizedRoot + '/')
    || normalizedOutput.startsWith(normalizedRoot + '\\')) {
  throw new Error('Use a separate output directory, never port/work');
}
const side = Number(sideText);
const warmupSide = Number(process.env.GAIUS_PERF_WARMUP_SIDE ?? '10');
if (!Number.isInteger(warmupSide) || warmupSide < 0 || warmupSide > 16) throw new Error('Invalid warmup side');
if (!Number.isInteger(side) || side < 1 || side > 32) throw new Error('Invalid side');
const seeds = seedText.split(',');
const correctness = process.env.GAIUS_PERF_CORRECTNESS === '1';
const correctnessHooks = process.env.GAIUS_CORRECTNESS_HOOKS ?? 'all';
const correctnessStage = process.env.GAIUS_CORRECTNESS_STAGE ?? 'full';
if (correctness && !['all', 'biomes', 'terrain', 'noise', 'off'].includes(correctnessHooks)) {
  throw new Error('Unknown correctness hook selection');
}
if (!['off', 'native', 'baseline', 'simd'].includes(backend)) throw new Error('Unknown backend');
if (backend !== 'off' && (!artifact || !existsSync(artifact))) throw new Error('Missing offline kernel artifact');
if (!['status', 'ore-golden', 'ore-kernel', 'plain', 'ore-kernel-plain'].includes(mode)) throw new Error('Unknown mode');
if (mode.startsWith('ore-') && backend === 'off') throw new Error('Ore requires a real kernel backend');
for (const seed of seeds) BigInt(seed);
const nativePath = path => path.replace(/^\/([a-z])\//i, (_, drive) => drive + ':/');
const sha = path => createHash('sha256').update(readFileSync(path)).digest('hex');
const tool = name => process.env.GAIUS_JAVA_HOME ? join(process.env.GAIUS_JAVA_HOME, 'bin', name) : name;
async function run(command, args, directory, log) {
  if (correctness) await waitJvmHeadroom(join(output, 'jvm-admission.jsonl'));
  const result = spawnSync(command, args, {cwd: directory, encoding: 'utf8', timeout: correctness ? 900000 : 300000,
    maxBuffer: 32 * 1024 * 1024, env: process.env});
  writeFileSync(log, (result.stdout ?? '') + (result.stderr ?? ''));
  if (result.status !== 0) throw new Error(`${command} failed (${result.status}): ${log}\n`
    + ((result.stdout ?? '') + (result.stderr ?? '')).slice(-1800));
}
mkdirSync(output, {recursive: true});
const manifests = readdirSync(join(root, 'port/versions')).filter(name => name.endsWith('.json'))
  .map(name => JSON.parse(readFileSync(join(root, 'port/versions', name), 'utf8')))
  .filter(profile => profile.worldgenReference && (!selected || profile.id === selected))
  .sort((left, right) => right.kernelSemantics.code - left.kernelSemantics.code || right.id.localeCompare(left.id));
if (!manifests.length) throw new Error('Unknown profile');
const cache = join(process.env.USERPROFILE ?? process.env.HOME, '.m2/repository/org/ow2/asm');
const asm = ['asm', 'asm-tree', 'asm-commons'].map(name => {
  const directories = readdirSync(join(cache, name)).sort((left, right) => right.localeCompare(left, undefined, {numeric: true}));
  const jar = directories.map(version => join(cache, name, version, `${name}-${version}.jar`)).find(existsSync);
  if (!jar) throw new Error(`Offline ASM cache missing: ${name}`);
  return jar;
});
for (const profile of manifests) {
  const directory = join(output, profile.id);
  const classes = join(directory, 'classes');
  const sources = join(directory, 'sources');
  mkdirSync(classes, {recursive: true});
  mkdirSync(sources, {recursive: true});
  const work = join(root, 'port/work', profile.id);
  const jar = join(work, 'client-named.jar');
  const text = readFileSync(join(work, 'classpath.txt'), 'utf8').trim();
  const libraries = text.split(text.startsWith('/') ? ':' : delimiter).filter(Boolean).map(nativePath)
    .filter(path => !/client-(named|original)\.jar$/.test(path));
  for (const path of [jar, ...libraries, ...asm]) if (!existsSync(path)) throw new Error(`Offline input missing: ${path}`);
  let source = readFileSync(join(root, 'port/scripts/worldgen-seed-parity/WorldgenSeedParity.java'), 'utf8');
  const replace = (previous, next) => {
    if (source.split(previous).length !== 2) throw new Error('Harness anchor mismatch: ' + previous);
    source = source.replace(previous, next);
  };
  replace('deleteRecursively(options.universe().resolve(worldName));',
    'if (Files.exists(options.universe().resolve(worldName))) throw new IllegalStateException("Use a fresh universe");');
  replace('false, !"full".equals(options.mode()));', 'false, true);');
  replace('                for (ChunkPos position : positions) {',
    '                for (int warmZ = 0; warmZ < ' + warmupSide + '; warmZ++) {\n'
    + '                    for (int warmX = 0; warmX < ' + warmupSide + '; warmX++) {\n'
    + '                        chunks.getChunk(ParityCompat.x(origin) + 1024 + warmX,\n'
    + '                                ParityCompat.z(origin) + 1024 + warmZ, ChunkStatus.FULL, true);\n'
    + '                    }\n'
    + '                }\n'
    + '                List<ChunkAccess> completed = new ArrayList<>();\n'
    + '                for (ChunkPos position : positions) {');
  replace('ChunkAccess chunk = chunks.getChunk(x, z, status, true);',
    'dev.gaius.wgperf.StatusProfiler.beginChunk();\n'
    + '                    ChunkAccess chunk;\n'
    + '                    try { chunk = chunks.getChunk(x, z, status, true); }\n'
    + '                    finally { dev.gaius.wgperf.StatusProfiler.endChunk(); }');
  replace('                    addChunk(chunkLines, blocksAggregate, biomesAggregate, x, z,\n'
    + '                            chunk.getPersistedStatus().getName(),',
    '                    completed.add(chunk);\n'
    + '                }\n'
    + '                for (int index = 0; index < positions.size(); index++) {\n'
    + '                    ChunkPos position = positions.get(index);\n'
    + '                    int x = ParityCompat.x(position);\n'
    + '                    int z = ParityCompat.z(position);\n'
    + '                    ChunkAccess chunk = completed.get(index);\n'
    + '                    addChunk(chunkLines, blocksAggregate, biomesAggregate, x, z,\n'
    + '                            chunk.getPersistedStatus().getName(),');
  replace('String blocks = HexFormat.of().formatHex(blocksAggregate.digest());',
    'try { dev.gaius.wgperf.StatusProfiler.write(System.getProperty("gaius.perf.report")); }\n'
    + '            catch (Exception error) { throw new IOException(error); }\n'
    + '            String blocks = HexFormat.of().formatHex(blocksAggregate.digest());');
  if (correctness) source = correctnessHarness(source);
  const harness = join(sources, 'WorldgenSeedParity.java');
  writeFileSync(harness, source.replaceAll('\r\n', '\n'));
  const classpath = [jar, ...libraries, ...asm].join(delimiter);
  const extra = [];
  const hooks = profile.worldgenReference.jvmHooks ?? [];
  if (correctness && correctnessHooks === 'noise' && profile.kernelSemantics.densityGraph === 'sampler-f32') {
    throw new Error('This profile combines noise and material rules; use terrain, not a false noise-only probe');
  }
  const enabled = backend !== 'off' && hooks.length !== 0 && mode !== 'ore-golden';
  if (enabled || mode.startsWith('ore-')) {
    if (correctness) await waitJvmHeadroom(join(output, 'jvm-admission.jsonl'));
    const gate = evaluateProfile(root, profile, {javaHome: process.env.GAIUS_JAVA_HOME ?? process.env.JAVA_HOME,
      outDir: join(directory, 'mirror')});
    writeFileSync(join(directory, 'mirror-table.json'), JSON.stringify(gate.table, null, 2) + '\n');
    const diagnosticOverride = correctness && process.env.GAIUS_CORRECTNESS_DIAGNOSTIC === '1'
      && gate.table.kernels.worldgen.reasons.every(reason => reason === 'PROFILE_DEFAULT_OFF');
    if (gate.error || (!gate.table.kernels.worldgen.enabled && !diagnosticOverride)) {
      throw new Error('Worldgen mirror gate failed');
    }
    if (mode.startsWith('ore-') && gate.table.kernels.ore.reasons.some(reason => reason !== 'PROFILE_DEFAULT_OFF')) {
      throw new Error('Experimental ore mirror gate failed');
    }
  }
  if (enabled) {
    for (const helper of [...hooks, 'dev/gaius/browser/kernel/worldgen/WorldgenIr.java',
      'net/minecraft/world/level/chunk/GaiusKernelSections.java']) {
      const resolved = sourceFile(profile, 'src', helper, {root, target: 'wasmgc'});
      if (!resolved) throw new Error('Missing JVM hook source ' + helper);
      const path = join(sources, helper);
      mkdirSync(dirname(path), {recursive: true});
      writeFileSync(path, resolved.text);
      extra.push(path);
    }
    extra.push(join(root, 'port/scripts/worldgen-status-perf/BrowserWorldgenKernel.java'),
      join(root, 'port/scripts/worldgen-status-perf/PatchKernel.java'),
      join(root, 'port/tools/src/main/java/dev/gaius/tools/patches/worldgen/WorldgenKernelPatches.java'));
  }
  if (correctness) extra.push(join(root, 'port/scripts/worldgen-status-perf/WorldgenCorrectness.java'));
  await run(tool('javac'), ['-J-Xmx3g', '-proc:none', '-cp', classpath, '-d', classes, harness,
    join(root, 'port/scripts/worldgen-seed-parity/LightFixtureWriter.java'),
    join(root, 'port/scripts/worldgen-seed-parity/compat', profile.id, 'ParityCompat.java'),
    join(root, 'port/scripts/worldgen-status-perf/StatusAgent.java'),
    join(root, 'port/scripts/worldgen-status-perf/StatusProfiler.java'),
    join(root, 'port/scripts/worldgen-status-perf/KernelHost.java'),
    join(root, 'port/scripts/worldgen-status-perf/OreShadow.java'), ...extra], directory, join(directory, 'compile.log'));
  if (enabled) await run(tool('java'), ['-Xmx3g', '-cp', [classes, classpath].join(delimiter),
    'dev.gaius.wgperf.PatchKernel', jar, classes], directory, join(directory, 'patch.log'));
  const manifest = join(directory, 'agent.mf');
  writeFileSync(manifest, 'Manifest-Version: 1.0\nPremain-Class: dev.gaius.wgperf.StatusAgent\n\n');
  const agent = join(directory, 'agent.jar');
  await run(tool('jar'), ['-J-Xmx3g', 'cfm', agent, manifest, '-C', classes, 'dev/gaius/wgperf'], directory, join(directory, 'jar.log'));
  writeFileSync(join(directory, 'provenance.json'), JSON.stringify({profile: profile.id, jarSha256: sha(jar),
    agentSha256: sha(agent), seeds, side, warmupSide, backend, enabled, mode,
    hashTiming: correctness ? 'after-targets-and-halo' : 'after-all-targets',
    correctness: correctness ? {hooks: correctnessHooks, stage: correctnessStage, halo: 2,
      backgroundThreads: 1, processorCount: 1, statusOrder: 'features-before-full',
      diagnosticOverride: process.env.GAIUS_CORRECTNESS_DIAGNOSTIC === '1',
      hooksAvailable: hooks.length !== 0} : null,
    vanillaSpawnSearch: false, artifactSha256: artifact ? sha(artifact) : null,
    asm: asm.map(path => ({path, sha256: sha(path)}))}, null, 2) + '\n');
  for (const seed of seeds) {
    const universe = join(directory, `universe-${seed}-${Date.now()}`);
    await run(tool('java'), ['-Xmx3g', '-XX:ActiveProcessorCount=' + (correctness ? '1' : '2'), '-XX:CICompilerCount=2',
      '-XX:+UseSerialGC', '-Dmax.bg.threads=1', '-javaagent:' + agent,
      '--enable-native-access=ALL-UNNAMED', '-Dgaius.perf.kernels=' + (enabled || mode.startsWith('ore-') ? backend : 'off'),
      '-Dgaius.perf.instrument=' + !mode.endsWith('plain'),
      '-Dgaius.perf.ore=' + (mode === 'ore-golden' ? 'golden' : mode.startsWith('ore-kernel') ? 'kernel' : 'off'),
      '-Dgaius.perf.oreFixtures=' + join(directory, 'ore-' + seed + '.bin'),
      '-Dgaius.perf.oreRules=' + (profile.worldgenReference.oreRules ?? []).join(','),
      '-Dgaius.perf.oreUnionRule=' + (profile.worldgenReference.oreUnionRule ?? ''),
      '-Dgaius.perf.artifact=' + (artifact ?? ''),
      '-Dgaius.perf.bridge=' + join(root, 'port/scripts/worldgen-status-perf/kernel-host.mjs'),
      '-Dgaius.perf.report=' + join(directory, `status-${seed}.json`),
      '-Dgaius.correctness.hooks=' + (correctness ? correctnessHooks : 'all'),
      '-Dgaius.correctness.stage=' + correctnessStage,
      '-Dgaius.correctness.halo=2',
      '-Dgaius.correctness.snapshots=' + join(directory, 'snapshots-' + seed),
      '-cp', [classes, classpath].join(delimiter), 'dev.gaius.parity.WorldgenSeedParity',
      '--seed', seed, '--mode', 'full', '--side', String(side), '--universe', universe,
      '--out', join(directory, `chunks-${seed}.json`)], directory, join(directory, `run-${seed}.log`));
    const report = JSON.parse(readFileSync(join(directory, `status-${seed}.json`), 'utf8'));
    if (report.chunks !== side * side || report.untrackedCpuNanos < 0) throw new Error('Invalid accounting');
    if (mode.startsWith('ore-') && (!report.ore.accepted || (side * side >= 300
        && report.ore.acceptedChunkCount < 300))) throw new Error('Insufficient nonempty ore chunk coverage');
    console.log(`${profile.id} seed=${seed} chunks=${report.chunks} wall=${(report.generationWallNanos / 1e9).toFixed(3)}s`
      + ` tracked=${(100 * report.trackedCpuNanos / report.processCpuNanos).toFixed(1)}%`);
    for (const [key, value] of Object.entries(report.statuses)) {
      console.log(`  ${key}: ${(100 * value.selfCpuNanos / report.processCpuNanos).toFixed(2)}% calls=${value.calls}`);
    }
  }
}
