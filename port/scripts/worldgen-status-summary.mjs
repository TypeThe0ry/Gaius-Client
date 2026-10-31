import {existsSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const output = resolve(process.argv[2] ?? '');
if (!process.argv[2]) throw new Error('Usage: node worldgen-status-summary.mjs <output>');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
if (output.toLowerCase().startsWith(root.toLowerCase())) throw new Error('Use a separate output directory');
const profiles = readdirSync(join(root, 'port/versions')).filter(name => name.endsWith('.json'))
  .map(name => JSON.parse(readFileSync(join(root, 'port/versions', name), 'utf8')))
  .filter(profile => profile.worldgenReference).sort((left, right) => right.kernelSemantics.code - left.kernelSemantics.code);
const runs = ['final-baseline', 'final-control-repeat', 'final-native', 'final-wasm-baseline', 'final-wasm-simd',
  'combined-native', 'combined-baseline', 'combined-simd', 'plain-baseline',
  'plain-combined-native', 'plain-combined-baseline', 'plain-combined-simd'];
const statuses = ['structure_starts', 'structure_references', 'biomes', 'noise', 'surface', 'carvers',
  'features_ore', 'features_tree', 'features_vegetation', 'features_other', 'initialize_light', 'light',
  'spawn', 'full', 'result_install', 'untracked'];
const seeds = ['12345', '987654321'];
const summaries = [];
for (const profile of profiles) {
  for (const run of runs) {
    const directory = join(output, run, profile.id);
    if (!existsSync(join(directory, 'provenance.json'))) continue;
    const provenance = JSON.parse(readFileSync(join(directory, 'provenance.json'), 'utf8'));
    const totals = {profile: profile.id, run, chunks: 0, wallNanos: 0, processCpuNanos: 0, shares: {}, jobs: {},
      features: {}, samples: [], ore: {accepted: 0, declined: 0, geometryHostNanos: 0, deltaHostNanos: 0,
        snapshotNanos: 0, installNanos: 0}, provenance};
    for (const seed of seeds) {
      const reportPath = join(directory, `status-${seed}.json`);
      if (!existsSync(reportPath)) throw new Error('Incomplete run: ' + reportPath);
      const report = JSON.parse(readFileSync(reportPath, 'utf8'));
      if (report.chunks !== 400 || report.untrackedCpuNanos < 0) throw new Error('Invalid status accounting');
      totals.chunks += report.chunks;
      totals.wallNanos += report.generationWallNanos;
      totals.processCpuNanos += report.processCpuNanos;
      for (const status of statuses) {
        const cpu = status === 'untracked' ? report.untrackedCpuNanos : report.statuses[status]?.selfCpuNanos ?? 0;
        totals.shares[status] = (totals.shares[status] ?? 0) + cpu;
      }
      for (const [kind, count] of Object.entries(report.kernelHost.jobs)) totals.jobs[kind] = (totals.jobs[kind] ?? 0) + count;
      for (const [name, cpu] of Object.entries(report.placedFeatureCpuNanos)) totals.features[name] = (totals.features[name] ?? 0) + cpu;
      if (report.ore) for (const key of Object.keys(totals.ore)) totals.ore[key] += report.ore[key];
      totals.samples.push({seed, wallSeconds: report.generationWallNanos / 1e9,
        acceptedChunkCount: report.ore?.acceptedChunkCount ?? null});
    }
    for (const status of statuses) totals.shares[status] /= totals.processCpuNanos;
    totals.chunksPerSecond = totals.chunks * 1e9 / totals.wallNanos;
    totals.features = Object.entries(totals.features).sort((left, right) => right[1] - left[1])
      .slice(0, 8).map(([name, cpuNanos]) => ({name, cpuNanos, processCpuShare: cpuNanos / totals.processCpuNanos}));
    summaries.push(totals);
  }
}
const differences = [];
for (const profile of profiles) {
  for (const run of runs.slice(1)) {
    for (const seed of seeds) {
      const path = variant => join(output, variant, profile.id, `chunks-${seed}.json`);
      const baselineRun = run.startsWith('plain-combined-') ? 'plain-baseline' : 'final-baseline';
      if (!existsSync(path(run)) || !existsSync(path(baselineRun))) continue;
      const baseline = JSON.parse(readFileSync(path(baselineRun), 'utf8'));
      const candidate = JSON.parse(readFileSync(path(run), 'utf8'));
      const changed = baseline.chunks.filter((chunk, index) => JSON.stringify(chunk) !== JSON.stringify(candidate.chunks[index]));
      differences.push({profile: profile.id, run, baselineRun, seed, changedChunks: changed.length,
        blocksEqual: baseline.aggregate.blocks === candidate.aggregate.blocks,
        biomesEqual: baseline.aggregate.biomes === candidate.aggregate.biomes,
        positions: changed.slice(0, 8).map(chunk => ({x: chunk.x, z: chunk.z}))});
    }
  }
}
const estimates = profiles.map(profile => {
  const baseline = summaries.find(run => run.profile === profile.id && run.run === 'final-baseline');
  if (!baseline) return null;
  const coverage = profile.worldgenReference.jvmHooks.length ? baseline.shares.biomes + baseline.shares.noise + baseline.shares.surface : 0;
  return {profile: profile.id, cpuCoverageProxy: coverage, oreCpuShareUpperBound: baseline.shares.features_ore,
    zeroCostOreUpperBound: 1 / (1 - baseline.shares.features_ore),
    zeroCostCurrentAndOreUpperBound: 1 / (1 - coverage - baseline.shares.features_ore),
    model: 'CPU-share Amdahl proxy only: assumes browser shares equal JVM shares, unlimited kernels, zero snapshot/transport/install cost'};
}).filter(Boolean);
const report = {schemaVersion: 1, summaries, differences, estimates,
  boundaries: ['JVM FULL requests include required neighbor generation; world load and warmup/hash are outside timings',
    'Thread CPU excludes nested work; background JIT/GC and uninstrumented code remain explicitly untracked',
    'Plain runs disable the ASM status agent, but retain bounded kernel host counters and timers',
    'combined-* runs are earlier diagnostics; plain-combined-* use the final section-lock-through-install implementation',
    'Node child CPU is not included in JVM process CPU; Node-backed shares are Java-only, not combined CPU shares',
    'Shared-machine load and instrumentation prevent treating single-run throughput ratios as browser acceptance',
    'Whole-chunk differences are diagnostics, not parity passes; even off/off can differ',
    'Browser Worker, TeaVM GC/JSPI and production ore integration are not exercised']};
writeFileSync(join(output, 'status-summary.json'), JSON.stringify(report, null, 2) + '\n');
const csv = ['profile,run,status,cpu_share_percent,chunks,wall_seconds,chunks_per_second'];
for (const run of summaries) for (const status of statuses) csv.push([run.profile, run.run, status,
  (100 * run.shares[status]).toFixed(3), run.chunks, (run.wallNanos / 1e9).toFixed(3), run.chunksPerSecond.toFixed(3)].join(','));
writeFileSync(join(output, 'status-shares.csv'), csv.join('\n') + '\n');
const lines = ['# Full-status JVM worldgen measurements', '',
  'Measured JVM requests and native/Node wasm backends, not a browser speed-up claim.', '',
  '| Profile | Run | Chunks/s | Relative to JVM off | Seeds (s) |', '| --- | --- | ---: | ---: | --- |'];
for (const run of summaries) {
  const baseline = summaries.find(candidate => candidate.profile === run.profile
    && candidate.run === (run.run.startsWith('plain-') ? 'plain-baseline' : 'final-baseline'));
  lines.push(`| ${run.profile} | ${run.run} | ${run.chunksPerSecond.toFixed(2)} | ${(run.chunksPerSecond / baseline.chunksPerSecond).toFixed(3)}x | ${run.samples.map(sample => sample.wallSeconds.toFixed(3)).join(' / ')} |`);
}
lines.push('', '## Exclusive CPU shares (% of JVM process CPU)', '',
  '| Status | ' + profiles.map(profile => profile.id).join(' | ') + ' |', '| --- | ' + profiles.map(() => '---:').join(' | ') + ' |');
for (const status of statuses) lines.push('| ' + status + ' | ' + profiles.map(profile => {
  const baseline = summaries.find(run => run.profile === profile.id && run.run === 'final-baseline');
  return baseline ? (100 * baseline.shares[status]).toFixed(2) : 'missing';
}).join(' | ') + ' |');
lines.push('', '## Boundaries', '', ...report.boundaries.map(boundary => '- ' + boundary), '',
  'Full run-level shares, job counts, hash mismatches and estimates: status-summary.json; flat shares: status-shares.csv.', '');
writeFileSync(join(output, 'status-summary.md'), lines.join('\n'));
console.log(summaries.length + ' complete 2-seed runs; ' + differences.length + ' hash comparisons; summary written');
