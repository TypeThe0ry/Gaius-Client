#!/usr/bin/env node
// Symbolize a server Worker CPU profile and rank weighted sampled self/inclusive time.
import {readFileSync, writeFileSync, mkdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {join, resolve} from 'node:path';
import {readSymbols} from './wasm-symbols.mjs';

const [profilePath, symbolsPath, modulePath, output] = process.argv.slice(2);
if (!output) throw new Error('usage: wasm-profile.mjs PROFILE SYMBOLS MODULE OUTPUT');
const profile = JSON.parse(readFileSync(profilePath, 'utf8'));
const symbols = readSymbols(readFileSync(symbolsPath));
const moduleSha256 = createHash('sha256').update(readFileSync(modulePath)).digest('hex');
if (moduleSha256 !== symbols.info.sha256) throw new Error('module / symbols sha256 mismatch');
mkdirSync(output, {recursive: true});
const byId = new Map(profile.nodes.map(n => [n.id, n]));
const parent = new Map();
for (const node of profile.nodes) for (const child of node.children || []) parent.set(child, node.id);
let wasmNodes = 0, resolvedNodes = 0;
for (const node of profile.nodes) {
  const m = /^wasm-function\[(\d+)\]$/.exec(node.callFrame.functionName);
  if (!m) continue;
  wasmNodes++;
  const name = symbols.nameOf(Number(m[1]));
  node.wasmFunctionIndex = Number(m[1]);
  if (name) { node.callFrame.functionName = name; resolvedNodes++; }
}
function group(name) {
  if (/\(idle\)/i.test(name)) return 'idle';
  if (/garbage collector|GC::|org\.teavm\.runtime\.(GC|WasmGC)|gc_collect/i.test(name)) return 'GC';
  if (/\.levelgen\.carver\.|CarvingMask|::applyCarvers/.test(name)) return 'carvers';
  if (/\.levelgen\.feature\.|\.levelgen\.placement\.|PlacedFeature|ConfiguredFeature|::applyBiomeDecoration/.test(name)) return 'features';
  if (/\.levelgen\.structure\.|StructureManager|StructureCheck|::createStructures|::createReferences/.test(name)) return 'structures';
  if (/SurfaceSystem|SurfaceRules|MaterialSystem|MaterialRuleContext|FindTopSurfaceFunction|::buildSurface/.test(name)) return 'surface';
  if (/\.level\.lighting\.|LightEngine|LightKernel|LightChunk|::lightChunk/.test(name)) return 'lighting';
  if (/\.level\.biome\.|BiomeResolver|BiomeManager|::createBiomes|::fillBiomesFromNoise/.test(name)) return 'biome lookup';
  if (/DensityFunction|NoiseChunk|NoiseRouter|BlendedNoise|NormalNoise|ImprovedNoise|SimplexNoise|PerlinNoise|Aquifer|OreVeinifier|NoiseBasedChunkGenerator|\.levelgen\.synth\./.test(name)) return 'noise/density';
  if (/\.network\.|\.nbt\.|ChunkSerializer|SerializableChunkData|PlayerChunkSender|FriendlyByteBuf|::writeChunk|::writeLight/.test(name)) return 'chunk serialization/packets';
  if (/org\.teavm\.|HostLoop|Scheduler|Dispatcher|CompletableFuture|EventLoop|Executor|MessagePort|MessageChannel|jspi|Suspending|promising|Thread::|postMessage|onmessage|runTask|run.*Thread/.test(name)) return 'JSPI/scheduler overhead';
  if (/^(java\.|it\.unimi\.|com\.google\.|com\.mojang\.)/.test(name)) return 'collections/serialization/general libraries';
  if (/^net\.minecraft\./.test(name)) return 'other Java game work';
  if (/^\(/.test(name)) return 'V8/native/program';
  return 'JS host/other';
}
const method = new Map(), classes = new Map(), groups = new Map();
function row(map, key) {
  if (!map.has(key)) map.set(key, {name: key, selfUs: 0, inclusiveUs: 0, attributedSelfUs: 0, selfSamples: 0});
  return map.get(key);
}
let sampledUs = 0;
for (let i = 0; i < profile.samples.length; i++) {
  const dt = Number(profile.timeDeltas?.[i]) || 0;
  const leaf = byId.get(profile.samples[i]);
  if (!leaf || dt < 0) continue;
  sampledUs += dt;
  const seenMethods = new Set(), seenClasses = new Set(), seenGroups = new Set();
  const leafName = leaf.callFrame.functionName;
  row(method, leafName).selfUs += dt; row(method, leafName).selfSamples++;
  row(classes, leafName.split('::')[0]).selfUs += dt;
  row(groups, group(leafName)).selfUs += dt;
  let attributed = null;
  for (let current = leaf; current; current = byId.get(parent.get(current.id))) {
    const name = current.callFrame.functionName;
    const cls = name.split('::')[0], g = group(name);
    seenMethods.add(name); seenClasses.add(cls); seenGroups.add(g);
    if (!attributed && /^(noise\/density|biome lookup|surface|carvers|features|structures|lighting|chunk serialization\/packets)$/.test(g)) attributed = g;
  }
  row(groups, attributed || group(leafName)).attributedSelfUs += dt;
  for (const key of seenMethods) row(method, key).inclusiveUs += dt;
  for (const key of seenClasses) row(classes, key).inclusiveUs += dt;
  for (const key of seenGroups) row(groups, key).inclusiveUs += dt;
}
for (const required of ['noise/density','biome lookup','surface','carvers','features','structures','lighting','chunk serialization/packets','GC','JSPI/scheduler overhead']) row(groups, required);
const format = map => [...map.values()].map(x => ({...x, selfMs: x.selfUs / 1000, inclusiveMs: x.inclusiveUs / 1000,
  attributedSelfMs: x.attributedSelfUs / 1000, selfPercent: 100*x.selfUs/sampledUs,
  inclusivePercent: 100*x.inclusiveUs/sampledUs, attributedSelfPercent: 100*x.attributedSelfUs/sampledUs})).sort((a,b)=>b.selfUs-a.selfUs);
const result = {moduleSha256, symbolsModule: symbols.info, profilePath: resolve(profilePath), symbolsPath: resolve(symbolsPath),
  durationMs: (profile.endTime-profile.startTime)/1000, sampledMs: sampledUs/1000,
  samples: profile.samples.length, maxSampleGapMs: (profile.timeDeltas || []).reduce((max, dt) => Math.max(max, Number(dt) || 0), 0)/1000,
  wasmNodes, resolvedNodes, methods: format(method), classes: format(classes), groups: format(groups)};
writeFileSync(join(output, 'profile-analysis.json'), JSON.stringify(result, null, 2));
writeFileSync(join(output, 'server.symbolized.cpuprofile'), JSON.stringify(profile));
const csv = rows => ['name,self_ms,inclusive_ms,self_percent,inclusive_percent,attributed_self_ms,attributed_self_percent',
  ...rows.map(x=>[JSON.stringify(x.name),x.selfMs,x.inclusiveMs,x.selfPercent,x.inclusivePercent,x.attributedSelfMs,x.attributedSelfPercent].join(','))].join('\n')+'\n';
for (const key of ['methods','classes','groups']) writeFileSync(join(output, `${key}.csv`), csv(result[key]));
console.log(JSON.stringify({...result, methods: result.methods.slice(0,30), classes: result.classes.slice(0,20)}, null, 2));
