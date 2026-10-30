import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { analyzeTerrainPng, createTerrainVisualFixture, decodePng, encodeRgbaPng,
  terrainVisualPass } from './terrain-visual-metrics.mjs';

const identity = (bytes) => ({ bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
const gate = (name, ok, detail = '') => ({ name, ok: Boolean(ok), detail: String(detail ?? '') });
const finite = (value, fallback = 0) => Number.isFinite(Number(value)) ? Number(value) : fallback;
const MIN_LOADED_CHUNKS = 4;
const MIN_LOADED_CHUNK_GROWTH = 2;
const MIN_MOVEMENT_LOADED_CHUNK_GROWTH = 1;
const MIN_NEW_CHUNK_EVENTS = 2;
const MIN_CHUNK_TRAVEL = 2;
const MIN_CHANGED_PIXEL_RATIO = 0.02;
const MIN_NORMALIZED_PIXEL_DIFFERENCE = 0.01;

function frameDifference(beforePng, afterPng) {
  const before = decodePng(beforePng); const after = decodePng(afterPng);
  if (before.width !== after.width || before.height !== after.height) {
    return { available: false, changedPixelRatio: 0, normalizedMeanAbsoluteDifference: 0 };
  }
  let changed = 0; let absolute = 0;
  const pixels = before.width * before.height;
  for (let offset = 0; offset < before.rgba.length; offset += 4) {
    const red = Math.abs(before.rgba[offset] - after.rgba[offset]);
    const green = Math.abs(before.rgba[offset + 1] - after.rgba[offset + 1]);
    const blue = Math.abs(before.rgba[offset + 2] - after.rgba[offset + 2]);
    absolute += red + green + blue;
    if (Math.max(red, green, blue) >= 12) changed++;
  }
  return { available: true, changedPixelRatio: pixels ? changed / pixels : 0,
    normalizedMeanAbsoluteDifference: pixels ? absolute / (pixels * 3 * 255) : 0,
    beforeSha256: identity(beforePng).sha256, afterSha256: identity(afterPng).sha256 };
}

function differencesMatch(declared, actual) {
  return declared?.available === true && actual?.available === true
    && declared.beforeSha256 === actual.beforeSha256
    && declared.afterSha256 === actual.afterSha256
    && Math.abs(finite(declared.changedPixelRatio) - finite(actual.changedPixelRatio)) <= 1e-9
    && Math.abs(finite(declared.normalizedMeanAbsoluteDifference)
      - finite(actual.normalizedMeanAbsoluteDifference)) <= 1e-9;
}

function sourceFailureEvents(runtime, evidence) {
  const records = [...(Array.isArray(runtime?.events) ? runtime.events : []),
    ...(Array.isArray(evidence?.consoleMessages) ? evidence.consoleMessages : [])];
  return records.filter((entry) => {
    const event = String(entry?.event || '').toLowerCase();
    const type = String(entry?.detail?.type || '').toLowerCase();
    const serialized = JSON.stringify(entry || {}).toLowerCase();
    return serialized.includes('network-pump-wrong-thread')
      || serialized.includes('network-pump-permit-missing')
      || serialized.includes('network-pump-retry-exhausted')
      || (event.startsWith('singleplayer:')
        && /(?:error|failure|failed|timeout|crash|terminated)/.test(event))
      || (event === 'singleplayer:worker'
        && /(?:^|[-_])(?:error|failure|failed|crash|terminated)(?:$|[-_])/.test(type));
  });
}

function chunkOf(player) {
  if (!player || !Number.isFinite(Number(player.x)) || !Number.isFinite(Number(player.z))) {
    return null;
  }
  return { x: Math.floor(Number(player.x) / 16), z: Math.floor(Number(player.z) / 16) };
}

function deriveTrajectory(terrain) {
  const samples = Array.isArray(terrain?.samples) ? terrain.samples : [];
  const initial = samples.find((entry) => entry?.phase === 'initial');
  const baseline = samples.filter((entry) => entry?.phase === 'baseline-wait').at(-1);
  const final = samples.filter((entry) => entry?.phase === 'final-wait').at(-1);
  const movementSamples = samples.filter((entry) => entry?.phase === 'movement');
  const maximumLoaded = samples.reduce((maximum, entry) =>
    Math.max(maximum, finite(entry?.loadedChunkCount)), 0);
  const start = terrain?.movement?.start || baseline?.player;
  const end = terrain?.movement?.end || final?.player;
  const startChunk = chunkOf(start); const endChunk = chunkOf(end);
  const chunkDistance = startChunk && endChunk
    ? Math.max(Math.abs(endChunk.x - startChunk.x), Math.abs(endChunk.z - startChunk.z)) : 0;
  const coordinateDistance = start && end ? Math.hypot(finite(end.x) - finite(start.x),
    finite(end.z) - finite(start.z)) : 0;
  return { samples: samples.length, movementSamples: movementSamples.length, initial, baseline, final,
    maximumLoaded, loadedDelta: maximumLoaded - finite(initial?.loadedChunkCount),
    movementLoadedDelta: maximumLoaded - finite(baseline?.loadedChunkCount),
    newChunkEventCount: finite(final?.chunkEventCount) - finite(baseline?.chunkEventCount),
    startChunk, endChunk, chunkDistance, coordinateDistance };
}

export async function validateSingleplayerEvidence(evidencePath, options = {}) {
  const path = resolve(evidencePath);
  const evidence = JSON.parse(await readFile(path, 'utf8'));
  const expectedProfile = options.profile || process.env.PROFILE || evidence.profile;
  const expectedArtifact = resolve(options.artifact || process.env.ARTIFACT || evidence.artifact);
  const runtime = evidence.singleRuntime;
  const terrain = runtime?.terrain;
  const rawFailures = sourceFailureEvents(runtime, evidence);
  const trajectory = deriveTrajectory(terrain);
  const checks = [
    gate('runner-success', evidence.completed === true && evidence.success === true,
      `${evidence.completed}/${evidence.success}`),
    gate('profile', evidence.profile === expectedProfile, `${evidence.profile}/${expectedProfile}`),
    gate('mode', ['single', 'both'].includes(evidence.mode), evidence.mode),
    gate('file-portable', runtime?.protocol === 'file:' && runtime?.portableBuild === true,
      `${runtime?.protocol}/${runtime?.portableBuild}`),
    gate('level', runtime?.level === true, runtime?.level),
    gate('wasm', runtime?.wasm?.ready === true && runtime?.wasm?.disabled !== true
      && runtime?.wasm?.error == null, JSON.stringify(runtime?.wasm)),
    gate('storage', runtime?.storage === 'ok' && runtime?.idb === 'ok',
      `${runtime?.storage}/${runtime?.idb}`),
    gate('terrain-declared', terrain?.ready === true
      && finite(terrain?.loadedChunkCount) >= MIN_LOADED_CHUNKS
      && finite(terrain?.maxLoadedChunkCount) >= MIN_LOADED_CHUNKS
      && finite(terrain?.loadedChunkDelta) >= MIN_LOADED_CHUNK_GROWTH
      && finite(terrain?.movementLoadedChunkDelta) >= MIN_MOVEMENT_LOADED_CHUNK_GROWTH
      && finite(terrain?.newChunkEventCount) >= MIN_NEW_CHUNK_EVENTS
      && terrainVisualPass(terrain?.baselineVisual) && terrainVisualPass(terrain?.visual),
    JSON.stringify({ ready: terrain?.ready, chunks: terrain?.loadedChunkCount,
      maximum: terrain?.maxLoadedChunkCount, loadedDelta: terrain?.loadedChunkDelta,
      movementLoadedDelta: terrain?.movementLoadedChunkDelta,
      newChunkEvents: terrain?.newChunkEventCount })),
    gate('movement-cdp', terrain?.movement?.inputMethod === 'cdp.Input.dispatchKeyEvent'
      && finite(terrain?.movement?.keyEvents) > 0, JSON.stringify(terrain?.movement)),
    gate('movement-crossed-chunks', finite(terrain?.movement?.chunkTravel) >= MIN_CHUNK_TRAVEL
      && finite(terrain?.movement?.coordinateTravel) >= MIN_CHUNK_TRAVEL * 16,
    JSON.stringify(terrain?.movement)),
    gate('stable-render-frames', finite(terrain?.stableVisualFrames) >= 2,
      terrain?.stableVisualFrames),
    gate('frame-difference-declared', terrain?.frameDifference?.available === true
      && terrain?.frameDifference?.beforeSha256 !== terrain?.frameDifference?.afterSha256
      && finite(terrain?.frameDifference?.changedPixelRatio) >= MIN_CHANGED_PIXEL_RATIO
      && finite(terrain?.frameDifference?.normalizedMeanAbsoluteDifference)
        >= MIN_NORMALIZED_PIXEL_DIFFERENCE, JSON.stringify(terrain?.frameDifference)),
    gate('worker-telemetry', terrain?.workerTelemetry
      && finite(terrain?.workerTelemetry?.received) > 0, JSON.stringify(terrain?.workerTelemetry)),
    gate('worker-failures-clean', Array.isArray(terrain?.failureEvents)
      && terrain.failureEvents.length === 0 && rawFailures.length === 0,
      JSON.stringify({ declared: terrain?.failureEvents, raw: rawFailures })),
    gate('trajectory-recomputed', trajectory.samples >= 4 && trajectory.movementSamples > 0
      && trajectory.maximumLoaded === finite(terrain?.maxLoadedChunkCount)
      && trajectory.loadedDelta === finite(terrain?.loadedChunkDelta)
      && trajectory.movementLoadedDelta === finite(terrain?.movementLoadedChunkDelta)
      && trajectory.newChunkEventCount === finite(terrain?.newChunkEventCount)
      && trajectory.chunkDistance === finite(terrain?.movement?.chunkTravel)
      && Math.abs(trajectory.coordinateDistance
        - finite(terrain?.movement?.coordinateTravel)) <= 1e-9,
    JSON.stringify(trajectory)),
    gate('runtime-exceptions-clean', (evidence.exceptions || []).length === 0,
      (evidence.exceptions || []).length),
    gate('sibling-file-requests-clean', (evidence.siblingFileRequests || []).length === 0,
      (evidence.siblingFileRequests || []).length),
    gate('base-ready', evidence.gates?.baseReady === true, evidence.gates?.baseReady),
    gate('cleanup', evidence.cleanup?.cdpClosed === true && evidence.cleanup?.chromeExited === true
      && evidence.cleanup?.profileRemoved === true, JSON.stringify(evidence.cleanup)),
    gate('artifact-unchanged', evidence.artifactIdentity?.unchanged === true,
      evidence.artifactIdentity?.unchanged),
  ];

  try {
    const artifactBytes = await readFile(expectedArtifact);
    const actual = identity(artifactBytes);
    checks.push(gate('artifact-path', resolve(evidence.artifact) === expectedArtifact,
      `${evidence.artifact}/${expectedArtifact}`));
    checks.push(gate('artifact-identity', actual.bytes === Number(evidence.artifactIdentity?.bytes)
      && actual.sha256 === evidence.artifactIdentity?.sha256, JSON.stringify(actual)));
  } catch (error) {
    checks.push(gate('artifact-identity', false, error?.message || error));
  }

  let screenshot = null;
  try {
    const declaredPath = String(terrain?.screenshotPath || '');
    const screenshotPath = isAbsolute(declaredPath) ? declaredPath : resolve(dirname(path), declaredPath);
    const png = await readFile(screenshotPath);
    const actual = identity(png);
    const visual = analyzeTerrainPng(png);
    const metricsMatch = ['sourceWidth', 'sourceHeight', 'sampleWidth', 'sampleHeight',
      'lowerTexturedTileCount', 'lowerTexturedRowCount', 'lowerTexturedColumnCount']
      .every((name) => Number(terrain?.visual?.[name]) === Number(visual[name]))
      && ['nonBlackRatio', 'luminanceStdDev', 'centralLuminanceStdDev',
        'centralDominantColorRatio', 'lowerLuminanceStdDev', 'lowerEdgeDensity']
        .every((name) => Math.abs(Number(terrain?.visual?.[name]) - Number(visual[name])) <= 1e-9);
    const baselineDeclaredPath = String(terrain?.baselineScreenshotPath || '');
    const baselineScreenshotPath = isAbsolute(baselineDeclaredPath)
      ? baselineDeclaredPath : resolve(dirname(path), baselineDeclaredPath);
    const baselinePng = await readFile(baselineScreenshotPath);
    const baselineActual = identity(baselinePng);
    const baselineVisual = analyzeTerrainPng(baselinePng);
    const difference = frameDifference(baselinePng, png);
    screenshot = { path: screenshotPath, ...actual, visual,
      baseline: { path: baselineScreenshotPath, ...baselineActual, visual: baselineVisual },
      difference };
    checks.push(gate('terrain-screenshot-identity', actual.bytes === Number(terrain?.identity?.bytes)
      && actual.sha256 === terrain?.identity?.sha256, JSON.stringify(actual)));
    checks.push(gate('terrain-visual-recomputed', terrainVisualPass(visual) && metricsMatch,
      JSON.stringify({ terrainVisualPass: terrainVisualPass(visual), metricsMatch })));
    checks.push(gate('terrain-baseline-screenshot-identity',
      baselineActual.bytes === Number(terrain?.baselineIdentity?.bytes)
      && baselineActual.sha256 === terrain?.baselineIdentity?.sha256,
    JSON.stringify(baselineActual)));
    checks.push(gate('terrain-baseline-visual-recomputed', terrainVisualPass(baselineVisual),
      JSON.stringify({ terrainVisualPass: terrainVisualPass(baselineVisual) })));
    checks.push(gate('terrain-frame-difference-recomputed', differencesMatch(
      terrain?.frameDifference, difference)
      && difference.changedPixelRatio >= MIN_CHANGED_PIXEL_RATIO
      && difference.normalizedMeanAbsoluteDifference >= MIN_NORMALIZED_PIXEL_DIFFERENCE,
    JSON.stringify(difference)));
  } catch (error) {
    checks.push(gate('terrain-screenshot-identity', false, error?.message || error));
  }

  const result = {
    schema: 'gaius.singleplayer-terrain-evidence-validation.v1', evidencePath: path,
    expected: { profile: expectedProfile, artifact: expectedArtifact }, screenshot, checks,
    success: checks.every((entry) => entry.ok), checkedAt: new Date().toISOString(),
  };
  if (!result.success && options.throwOnFailure !== false) {
    throw new Error(`SINGLEPLAYER TERRAIN EVIDENCE:\n${checks.filter((entry) => !entry.ok)
      .map((entry) => `${entry.name}: ${entry.detail}`).join('\n')}`);
  }
  return result;
}

async function selfTest() {
  for (const type of ['network-pump-wrong-thread', 'network-pump-permit-missing',
    'network-pump-retry-exhausted']) {
    assert.equal(sourceFailureEvents({events:[{event:'singleplayer:worker',detail:{type}}]},{}).length,1);
    assert.equal(sourceFailureEvents({}, {consoleMessages:[{text:type}]}).length,1);
  }
  assert.equal(sourceFailureEvents({events:[{event:'singleplayer:worker',
    detail:{type:'network-pump-busy'}}]},{}).length,0);
  const directory = await mkdtemp(join(tmpdir(), 'gaius-single-terrain-validator-'));
  try {
    const artifact = join(directory, 'Gaius.html');
    const screenshotPath = join(directory, 'terrain.png');
    const baselineScreenshotPath = join(directory, 'terrain-baseline.png');
    const evidencePath = join(directory, 'evidence.json');
    const artifactBytes = Buffer.from('compiled-gaius');
    const baselinePng = createTerrainVisualFixture();
    const decoded = decodePng(baselinePng);
    const shifted = Buffer.from(decoded.rgba);
    for (let offset = 0; offset < shifted.length; offset += 4) {
      shifted[offset] = (shifted[offset] + 37) & 0xff;
      shifted[offset + 1] = (shifted[offset + 1] + 19) & 0xff;
    }
    const png = encodeRgbaPng(decoded.width, decoded.height, shifted);
    const visual = analyzeTerrainPng(png);
    const baselineVisual = analyzeTerrainPng(baselinePng);
    const difference = frameDifference(baselinePng, png);
    await writeFile(artifact, artifactBytes); await writeFile(screenshotPath, png);
    await writeFile(baselineScreenshotPath, baselinePng);
    const evidence = {
      schemaVersion: 2, profile: '26.2', artifact, mode: 'single', completed: true, success: true,
      artifactIdentity: { ...identity(artifactBytes), unchanged: true }, exceptions: [],
      siblingFileRequests: [], gates: { baseReady: true },
      cleanup: { cdpClosed: true, chromeExited: true, profileRemoved: true },
      singleRuntime: { protocol: 'file:', portableBuild: true, level: true,
        wasm: { ready: true, disabled: false, error: null }, storage: 'ok', idb: 'ok',
        terrain: { ready: true, screenshotPath, identity: identity(png),
          baselineScreenshotPath, baselineIdentity: identity(baselinePng),
          initialLoadedChunkCount: 1, baselineLoadedChunkCount: 4, loadedChunkCount: 8,
          maxLoadedChunkCount: 8, loadedChunkDelta: 7, movementLoadedChunkDelta: 4,
          initialChunkEventCount: 1, baselineChunkEventCount: 4, chunkEventCount: 9,
          newChunkEventCount: 5, movement: { inputMethod: 'cdp.Input.dispatchKeyEvent',
            keyEvents: 20, chunkTravel: 3, coordinateTravel: 49 },
          stableVisualFrames: 2, workerTelemetry: { received: 4 }, failureEvents: [],
          baselineVisual, frameDifference: difference, visual,
          samples: [
            { phase: 'initial', loadedChunkCount: 1, chunkEventCount: 1,
              player: { x: 0, z: 0 } },
            { phase: 'baseline-wait', loadedChunkCount: 4, chunkEventCount: 4,
              player: { x: 0, z: 0 } },
            { phase: 'movement', loadedChunkCount: 6, chunkEventCount: 7,
              player: { x: 32, z: 0 } },
            { phase: 'final-wait', loadedChunkCount: 8, chunkEventCount: 9,
              player: { x: 49, z: 0 } },
          ] } },
    };
    await writeFile(evidencePath, `${JSON.stringify(evidence, null, 2)}\n`);
    assert.equal((await validateSingleplayerEvidence(evidencePath, { artifact, profile: '26.2' })).success, true);
    evidence.singleRuntime.terrain.movement.chunkTravel = 0;
    await writeFile(evidencePath, `${JSON.stringify(evidence, null, 2)}\n`);
    assert.equal((await validateSingleplayerEvidence(evidencePath,
      { artifact, profile: '26.2', throwOnFailure: false })).success, false);
    console.log('CHECK_SINGLEPLAYER_TERRAIN_EVIDENCE_STATIC_OK');
  } finally { await rm(directory, { recursive: true, force: true }); }
}

async function main() {
  if (process.argv.includes('--static-self-test')) return selfTest();
  const evidencePath = process.argv[2] || process.env.EVIDENCE;
  if (!evidencePath) throw new Error('usage: node tools/check-singleplayer-terrain-evidence.mjs <evidence.json>');
  console.log(JSON.stringify(await validateSingleplayerEvidence(evidencePath), null, 2));
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main().catch((error) => { console.error(error?.stack || error); process.exitCode = 1; });
}
