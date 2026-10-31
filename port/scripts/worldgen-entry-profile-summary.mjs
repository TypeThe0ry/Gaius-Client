import {readFileSync, writeFileSync} from 'node:fs';

const [input, output] = process.argv.slice(2);
if (!input || !output) throw new Error('Usage: worldgen-entry-profile-summary.mjs <symbolized.cpuprofile> <summary.json>');
const profile = JSON.parse(readFileSync(input, 'utf8'));
const nodes = new Map(profile.nodes.map(node => [node.id, node]));
const parents = new Map();
for (const node of nodes.values()) for (const child of node.children || []) parents.set(child, node.id);
const stages = [
  ['features', /generateFeatures|applyBiomeDecoration|placeFeatures|PlacedFeature|\.feature\./],
  ['carvers', /applyCarvers|generateCarvers|\.carver\./],
  ['surface', /buildSurface|generateSurface/],
  ['noise-density', /buildTerrain|fillFromNoise|generateNoise|NoiseChunk|DensityFunction|Aquifer|PerlinNoise|NormalNoise/],
  ['light', /\.lighting\.|LightEngine|initializeLight|lightChunk/],
  ['biomes', /generateBiomes|createBiomes|BiomeResolver|Climate\$RTree/],
];
const overhead = [
  ['cooperative', /HostLoop::managedBlock|BrowserWorldgenScheduler|BrowserWorldgenDeepCheckpoint/],
  ['jspi', /JspiHost|gaius-jspi|wasm-to-js|js-to-wasm/],
  ['message', /MessagePort|postMessage|server-worker-bootstrap/],
];
const totals = {raw: {}, capped: {}};
const inclusive = {};
let maxGapMs = 0;
for (let i = 0; i < profile.samples.length; i++) {
  const node = nodes.get(profile.samples[i]);
  const stack = [];
  for (let n = node; n; n = nodes.get(parents.get(n.id))) stack.push(n.callFrame);
  const leaf = node?.callFrame?.functionName || '';
  const names = stack.map(f => f.functionName).join('\n');
  const frames = stack.map(f => `${f.functionName} ${f.url}`).join('\n');
  const special = leaf === '(idle)' ? 'idle' : leaf === '(garbage collector)' ? 'gc' : null;
  // Disjoint attribution: runtime overhead takes precedence over the Java caller.
  const category = special || overhead.find(([, re]) => re.test(`${leaf} ${node?.callFrame?.url}`))?.[0]
    || stages.find(([, re]) => re.test(names))?.[0] || 'other';
  const ms = Number(profile.timeDeltas[i] || 0) / 1000;
  maxGapMs = Math.max(maxGapMs, ms);
  for (const [mode, weight] of [['raw', ms], ['capped', Math.min(10, ms)]]) {
    totals[mode][category] = (totals[mode][category] || 0) + weight;
  }
  for (const [name, re] of [...stages, ...overhead]) {
    if (re.test(frames)) inclusive[name] = (inclusive[name] || 0) + Math.min(10, ms);
  }
}
const report = {samples: profile.samples.length, durationMs: (profile.endTime - profile.startTime) / 1000,
  maxGapMs, totals, inclusiveCappedMs: inclusive,
  caveat: 'Sample intervals include descheduling. Capped weights limit each interval to 10 ms; inclusive categories overlap. Suspended stacks are absent and cannot measure wait wall time.'};
writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report));
