import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  analyzeTerrainPng,
  createTerrainVisualFixture,
  terrainVisualPass,
} from './terrain-visual-metrics.mjs';

import { expectedResourcePack } from './resource-pack-expectation.mjs';

async function hashFile(path) {
  const digest = createHash('sha256');
  let bytes = 0;
  for await (const chunk of createReadStream(path)) {
    bytes += chunk.length;
    digest.update(chunk);
  }
  return { bytes, sha256: digest.digest('hex') };
}

function fail(message) {
  throw new Error(`MULTIPLAYER TERRAIN EVIDENCE: ${message}`);
}

function gate(name, ok, detail) {
  return { name, ok: Boolean(ok), detail: String(detail ?? '') };
}

function resolveScreenshot(evidencePath, screenshotPath) {
  if (isAbsolute(screenshotPath)) return screenshotPath;
  const adjacent = resolve(dirname(evidencePath), screenshotPath);
  return adjacent;
}

function normalizeTarget(value) {
  const raw = String(value || '').trim();
  if (!raw) return '';
  try {
    const url = new URL(raw.includes('://') ? raw : `tcp://${raw}`);
    let hostname = url.hostname.toLowerCase();
    if (hostname.startsWith('[') && hostname.endsWith(']')) hostname = hostname.slice(1, -1);
    while (hostname.endsWith('.') && hostname.length > 1) hostname = hostname.slice(0, -1);
    const authority = hostname.includes(':') ? `[${hostname}]` : hostname;
    return url.port ? `${authority}:${url.port}` : authority;
  } catch {
    return raw.toLowerCase().replace(/\.+(?=:|$)/, '');
  }
}

function normalizeRelay(value) {
  const raw = String(value || '').trim();
  if (!raw) return '';
  try {
    const url = new URL(raw);
    url.protocol = url.protocol.toLowerCase();
    url.hostname = url.hostname.toLowerCase().replace(/\.+$/, '');
    url.pathname = (url.pathname.replace(/\/+$/, '') || '/').toLowerCase();
    url.hash = '';
    const normalized = url.href;
    return url.pathname === '/' && !url.search ? normalized.replace(/\/$/, '') : normalized;
  } catch {
    return raw.toLowerCase().replace(/\/+$/, '');
  }
}

function expectedResourcePackProxy(relay) {
  try {
    const url = new URL(String(relay || '').trim());
    if (url.protocol === 'ws:') url.protocol = 'http:';
    if (url.protocol === 'wss:') url.protocol = 'https:';
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return '';
    url.pathname = '/proxy/resource-pack';
    url.hash = '';
    url.search = '';
    url.searchParams.set('url', expectedResourcePack.originalUrl);
    url.searchParams.set('stream', '1');
    return url.href;
  } catch {
    return '';
  }
}

function resourcePackUrlMatchesExpected(value, relay) {
  try {
    const actual = new URL(String(value || ''));
    const fixedMirror = expectedResourcePack.fixedMirrorUrl
      ? new URL(expectedResourcePack.fixedMirrorUrl) : null;
    if (fixedMirror && actual.href === fixedMirror.href) return true;
    const expected = new URL(expectedResourcePackProxy(relay));
    const parameterNames = [...new Set(actual.searchParams.keys())].sort();
    return actual.protocol === expected.protocol
      && actual.hostname.toLowerCase() === expected.hostname.toLowerCase()
      && actual.port === expected.port
      && actual.pathname === expected.pathname
      && actual.username === ''
      && actual.password === ''
      && actual.hash === ''
      && actual.searchParams.getAll('url').length === 1
      && actual.searchParams.get('url') === expectedResourcePack.originalUrl
      && actual.searchParams.getAll('stream').length === 1
      && actual.searchParams.get('stream') === '1'
      && actual.searchParams.getAll('token').length <= 1
      && parameterNames.every((name) => ['stream', 'token', 'url'].includes(name));
  } catch {
    return false;
  }
}

function verifiedExpectedResourcePackTransaction(entry, relay) {
  const body = entry?.bodyVerification;
  return String(entry?.requestId || '').length > 0
    && entry?.method === 'GET'
    && resourcePackUrlMatchesExpected(entry.url, relay)
    && Number(entry.status) >= 200
    && Number(entry.status) < 300
    && entry.loadingFinished === true
    && Number(entry.encodedDataLength) > 0
    && !entry.loadingFailed
    && (entry.declaredContentLength == null
      || Number(entry.declaredContentLength) === expectedResourcePack.bytes)
    && body?.base64Encoded === true
    && Number(body?.bytes) === expectedResourcePack.bytes
    && body?.sha1 === expectedResourcePack.sha1
    && body?.sha256 === expectedResourcePack.sha256
    && !body?.error;
}

function relayNodeSuccesses(bridge, relay) {
  const normalizedRelay = normalizeRelay(relay);
  let successes = 0;
  for (const [nodeRelay, node] of Object.entries(bridge?.relayNodes || {})) {
    if (normalizeRelay(nodeRelay) === normalizedRelay) {
      successes = Math.max(successes, Number(node?.successes) || 0);
    }
  }
  return successes;
}

function recomputeRelayConnectionEvidence(bridge) {
  const phases = Array.isArray(bridge?.connectPhases) ? bridge.connectPhases : [];
  return phases.map((event, phaseIndex) => ({ event, phaseIndex }))
    .filter(({ event }) => event?.phase === 'relay-connected')
    .map(({ event, phaseIndex }) => {
      const relay = normalizeRelay(event.relay ?? event.detail);
      return {
        connectionId: event.connectionId ?? event.id ?? null,
        phaseIndex,
        target: normalizeTarget(event.target),
        relay,
        phase: 'relay-connected',
        relayNodeSuccesses: relayNodeSuccesses(bridge, relay),
        at: event.at ?? null,
        elapsedMillis: event.elapsedMillis ?? null,
      };
    });
}

function normalizeDeclaredRelayConnections(records) {
  if (!Array.isArray(records)) return [];
  return records.map((record) => ({
    connectionId: record?.connectionId ?? null,
    phaseIndex: Number.isInteger(record?.phaseIndex) ? record.phaseIndex : null,
    target: normalizeTarget(record?.target),
    relay: normalizeRelay(record?.relay),
    phase: String(record?.phase || ''),
    relayNodeSuccesses: Number(record?.relayNodeSuccesses) || 0,
    at: record?.at ?? null,
    elapsedMillis: record?.elapsedMillis ?? null,
  }));
}

function connectionProjection(record) {
  return {
    connectionId: record.connectionId,
    phaseIndex: record.phaseIndex,
    target: record.target,
    relay: record.relay,
    phase: record.phase,
    relayNodeSuccesses: record.relayNodeSuccesses,
  };
}

function matchingRelayConnection(records, expectedTarget, expectedRelay) {
  const target = normalizeTarget(expectedTarget);
  const relay = normalizeRelay(expectedRelay);
  return records.find((record) => record.connectionId !== null
    && record.phase === 'relay-connected'
    && record.target === target
    && record.relay === relay
    && Number(record.relayNodeSuccesses) >= 1) || null;
}

export async function validateEvidence(evidencePath, options = {}) {
  const path = resolve(evidencePath);
  const evidence = JSON.parse(await readFile(path, 'utf8'));
  const expectedTarget = options.expectedTarget || process.env.TARGET || evidence.expected?.target;
  const expectedRelay = options.expectedRelay || process.env.RELAY || evidence.expected?.relay;
  const expectedArtifact = options.expectedArtifact || process.env.ARTIFACT || evidence.artifactIdentity?.path || evidence.artifact;
  const state = evidence.final?.state;
  const net = evidence.final?.net;
  const bridge = evidence.final?.bridgeStats;
  const observedTargets = evidence.observed?.targets || [];
  const observedRelays = evidence.observed?.relays || [];
  const normalizedObservedTargets = observedTargets.map(normalizeTarget).filter(Boolean);
  const normalizedObservedRelays = observedRelays.map(normalizeRelay).filter(Boolean);
  const recomputedRelayConnections = recomputeRelayConnectionEvidence(bridge);
  const declaredRelayConnections = normalizeDeclaredRelayConnections(evidence.relayConnectionEvidence);
  const relayConnectionDeclarationsMatch = JSON.stringify(
    declaredRelayConnections.map(connectionProjection),
  ) === JSON.stringify(recomputedRelayConnections.map(connectionProjection));
  const boundRelayConnection = matchingRelayConnection(
    recomputedRelayConnections, expectedTarget, expectedRelay,
  );
  const resourceTransactions = evidence.resourcePack?.transactions || [];
  const verifiedResourceTransactions = resourceTransactions.filter((entry) =>
    verifiedExpectedResourcePackTransaction(entry, expectedRelay));
  const resourceSuccess = verifiedResourceTransactions.length > 0;
  const declaredResourceRequestIds = Array.isArray(evidence.resourcePack?.successfulRequestIds)
    ? evidence.resourcePack.successfulRequestIds.map(String).sort()
    : [];
  const recomputedResourceRequestIds = verifiedResourceTransactions
    .map((entry) => String(entry.requestId)).sort();
  const resourceDeclarationsMatch = JSON.stringify(declaredResourceRequestIds)
    === JSON.stringify(recomputedResourceRequestIds);
  const observedChunkPeak = Math.max(
    Number(state?.loadedChunkCount) || 0,
    ...(Array.isArray(evidence.samples)
      ? evidence.samples.map((sample) => Number(sample?.loadedChunkCount) || 0)
      : []),
  );

  const checks = [
    gate('runner-schema-v3', evidence.schema === 'gaius.multiplayer-terrain-cdp-acceptance.v3',
      evidence.schema),
    gate('runner-success', evidence.success === true, evidence.success),
    gate('final-state-present', Boolean(state), state?.screen),
    gate('client-level', state?.level === 'net.minecraft.client.multiplayer.ClientLevel', state?.level),
    gate('chunks-loaded', observedChunkPeak > 0, observedChunkPeak),
    gate('final-net-present', Boolean(net), typeof net),
    gate('network-errors-clean', Number(net?.errors) === 0, net?.errors),
    gate('bridge-stats-present', Boolean(bridge), typeof bridge),
    gate('relay-connected', Number(bridge?.connected) >= 1, bridge?.connected),
    gate('relay-succeeded', Number(bridge?.relayNodeSuccesses) >= 1, bridge?.relayNodeSuccesses),
    gate('relay-attestation-clean', Number(bridge?.relayTargetAttestationFailures) === 0,
      bridge?.relayTargetAttestationFailures),
    gate('relay-errors-clean', Number(bridge?.errors) === 0, bridge?.errors),
    gate('bridge-init-clean', !evidence.final?.bridgeError, evidence.final?.bridgeError),
    gate('runtime-exceptions-clean', (evidence.logs?.exceptions || []).length === 0,
      (evidence.logs?.exceptions || []).length),
    gate('evaluate-exceptions-clean', (evidence.logs?.evaluateExceptions || []).length === 0,
      (evidence.logs?.evaluateExceptions || []).length),
    gate('cdp-event-errors-clean', (evidence.logs?.cdpEventErrors || []).length === 0,
      (evidence.logs?.cdpEventErrors || []).length),
    gate('resource-pack-exact-body-verified', evidence.resourcePack?.succeeded === true
      && resourceSuccess && resourceDeclarationsMatch,
    JSON.stringify({
      expected: { ...expectedResourcePack, proxyUrl: expectedResourcePackProxy(expectedRelay) },
      declaredResourceRequestIds,
      recomputedResourceRequestIds,
      transactions: resourceTransactions,
    })),
    gate('artifact-runner-unchanged', evidence.artifactIdentity?.unchanged === true,
      evidence.artifactIdentity?.unchanged),
    gate('chrome-exited', evidence.cleanup?.chromeExited === true, evidence.cleanup?.chromeExited),
    gate('chrome-profile-removed', evidence.cleanup?.profileRemoved === true, evidence.cleanup?.profileRemoved),
    gate('expected-target-recorded', Boolean(expectedTarget), expectedTarget),
    gate('expected-relay-recorded', Boolean(expectedRelay), expectedRelay),
    gate('expected-target-observed', Boolean(expectedTarget)
      && normalizedObservedTargets.includes(normalizeTarget(expectedTarget)),
      JSON.stringify(observedTargets)),
    gate('expected-relay-observed', Boolean(expectedRelay)
      && normalizedObservedRelays.includes(normalizeRelay(expectedRelay)),
      JSON.stringify(observedRelays)),
    gate('relay-connection-evidence-present', declaredRelayConnections.length > 0,
      JSON.stringify(declaredRelayConnections)),
    gate('relay-connection-evidence-recomputed', relayConnectionDeclarationsMatch,
      JSON.stringify({ declaredRelayConnections, recomputedRelayConnections })),
    gate('relay-connection-bound', Boolean(boundRelayConnection),
      JSON.stringify({ expectedTarget, expectedRelay, recomputedRelayConnections })),
    gate('screenshots-retained-final', Array.isArray(evidence.final?.screenshots)
      && evidence.final.screenshots.length > 0, evidence.final?.screenshots?.length),
  ];

  const screenshotIdentity = evidence.screenshotIdentity || [];
  const terrainScreenshots = screenshotIdentity.filter((entry) =>
    /(?:terrain|world|game)/i.test(entry.label || entry.path || ''));
  checks.push(gate('terrain-screenshot-identity', terrainScreenshots.length > 0,
    JSON.stringify(terrainScreenshots)));
  const verifiedScreenshots = [];
  for (const entry of terrainScreenshots) {
    const screenshotPath = resolveScreenshot(path, String(entry.path));
    try {
      const png = await readFile(screenshotPath);
      const actual = {
        bytes: png.length,
        sha256: createHash('sha256').update(png).digest('hex'),
      };
      const identityOk = actual.bytes > 0
        && actual.bytes === Number(entry.bytes)
        && actual.sha256 === String(entry.sha256).toLowerCase();
      checks.push(gate(`terrain-screenshot:${entry.label || entry.path}`, identityOk,
        `${screenshotPath} bytes=${actual.bytes} sha256=${actual.sha256}`));
      if (!identityOk) continue;
      const recomputedVisual = analyzeTerrainPng(png);
      const visualPass = terrainVisualPass(recomputedVisual);
      const declaredVisualPass = entry.terrainVisualPass === true;
      const declaredMetricsPass = terrainVisualPass(entry.visual);
      const declarationMatch = declaredVisualPass === visualPass
        && declaredMetricsPass === visualPass;
      const metricsMatch = [
        'sourceWidth', 'sourceHeight', 'sampleWidth', 'sampleHeight',
        'lowerTexturedTileCount', 'lowerTexturedRowCount', 'lowerTexturedColumnCount',
      ].every((name) => Number(entry.visual?.[name]) === Number(recomputedVisual[name]))
        && [
          'nonBlackRatio', 'luminanceStdDev', 'centralLuminanceStdDev',
          'centralDominantColorRatio', 'lowerLuminanceStdDev', 'lowerEdgeDensity',
        ].every((name) => Math.abs(Number(entry.visual?.[name]) - Number(recomputedVisual[name])) <= 1e-9);
      checks.push(gate(`terrain-visual-recomputed:${entry.label || entry.path}`,
        metricsMatch && declarationMatch,
        JSON.stringify({
          visualPass,
          declaredVisualPass,
          declaredMetricsPass,
          declarationMatch,
          metricsMatch,
          recomputedVisual,
        })));
      verifiedScreenshots.push({
        path: screenshotPath,
        ...actual,
        visual: recomputedVisual,
        terrainVisualPass: visualPass,
      });
    } catch (error) {
      checks.push(gate(`terrain-screenshot:${entry.label || entry.path}`, false,
        String(error?.message || error)));
    }
  }
  checks.push(gate('terrain-screenshot-visual', verifiedScreenshots.some((entry) =>
    entry.terrainVisualPass === true), JSON.stringify(verifiedScreenshots)));

  let artifact = null;
  if (expectedArtifact) {
    const artifactPath = resolve(expectedArtifact);
    try {
      const details = await stat(artifactPath);
      const actual = await hashFile(artifactPath);
      artifact = { path: artifactPath, ...actual };
      checks.push(gate('artifact-exists', details.isFile(), artifactPath));
      checks.push(gate('artifact-bytes-match', actual.bytes === Number(evidence.artifactIdentity?.bytes),
        `${actual.bytes}/${evidence.artifactIdentity?.bytes}`));
      checks.push(gate('artifact-sha256-match', actual.sha256 === evidence.artifactIdentity?.sha256,
        `${actual.sha256}/${evidence.artifactIdentity?.sha256}`));
    } catch (error) {
      checks.push(gate('artifact-exists', false, String(error?.message || error)));
    }
  } else {
    checks.push(gate('artifact-exists', false, 'artifact path is missing'));
  }

  const result = {
    schema: 'gaius.multiplayer-terrain-evidence-validation.v3',
    evidencePath: path,
    expected: { target: expectedTarget, relay: expectedRelay, artifact: expectedArtifact },
    artifact,
    verifiedScreenshots,
    checks,
    success: checks.every((entry) => entry.ok),
    checkedAt: new Date().toISOString(),
  };
  if (!result.success && options.throwOnFailure !== false) {
    const failed = checks.filter((entry) => !entry.ok)
      .map((entry) => `${entry.name}: ${entry.detail}`).join('\n');
    fail(failed);
  }
  return result;
}

async function runStaticSelfTest() {
  assert.equal(typeof validateEvidence, 'function');
  const streamedPack = {
    requestId: 'streamed-pack', method: 'GET', status: 200,
    url: expectedResourcePackProxy('wss://relay.example/tunnel'),
    loadingFinished: true, encodedDataLength: expectedResourcePack.bytes,
    declaredContentLength: null,
    bodyVerification: { base64Encoded: true, ...expectedResourcePack },
  };
  assert.equal(verifiedExpectedResourcePackTransaction(streamedPack,
    'wss://relay.example/tunnel'), true);
  for (const field of ['bytes', 'sha1', 'sha256']) {
    const damaged = structuredClone(streamedPack);
    damaged.bodyVerification[field] = field === 'bytes' ? 1 : '0';
    assert.equal(verifiedExpectedResourcePackTransaction(damaged,
      'wss://relay.example/tunnel'), false);
  }
  assert.match(createHash('sha256').update('gaius').digest('hex'), /^[0-9a-f]{64}$/);
  assert.equal(resourcePackUrlMatchesExpected(
    expectedResourcePack.fixedMirrorUrl,
    'wss://relay.example/tunnel'), Boolean(expectedResourcePack.fixedMirrorUrl));
  assert.equal(resourcePackUrlMatchesExpected(
    `${expectedResourcePack.fixedMirrorUrl}?cache-bust=1`,
    'wss://relay.example/tunnel'), false);
  assert.equal(resourcePackUrlMatchesExpected(
    expectedResourcePackProxy('wss://relay.example/tunnel'),
    'wss://relay.example/tunnel'), true);
  assert.equal(resourcePackUrlMatchesExpected(
    expectedResourcePackProxy('wss://wrong.example/tunnel'),
    'wss://relay.example/tunnel'), false);
  const directory = await mkdtemp(join(tmpdir(), 'gaius-evidence-validator-'));
  try {
    const artifact = join(directory, 'Gaius.html');
    const screenshot = join(directory, 'terrain-1.png');
    const earlyScreenshot = join(directory, 'terrain-early.png');
    const evidencePath = join(directory, 'evidence.json');
    await writeFile(artifact, 'compiled-gaius-fixture');
    const terrainPng = createTerrainVisualFixture();
    const terrainVisual = analyzeTerrainPng(terrainPng);
    assert.equal(terrainVisualPass(terrainVisual), true);
    await writeFile(screenshot, terrainPng);
    const artifactHash = await hashFile(artifact);
    const screenshotHash = await hashFile(screenshot);
    const target = 'example.test:25565';
    const relay = 'wss://relay.example/tunnel';
    const evidence = {
      schema: 'gaius.multiplayer-terrain-cdp-acceptance.v3',
      success: true,
      artifact,
      artifactIdentity: { path: artifact, ...artifactHash, unchanged: true },
      expected: { target, relay },
      observed: { targets: [target], relays: [relay] },
      logs: { exceptions: [], evaluateExceptions: [], cdpEventErrors: [] },
      resourcePack: {
        succeeded: true,
        successfulRequestIds: ['fixture-pack'],
        transactions: [{
          requestId: 'fixture-pack',
          url: expectedResourcePackProxy(relay),
          method: 'GET',
          status: 200,
          declaredContentLength: expectedResourcePack.bytes,
          loadingFinished: true,
          encodedDataLength: expectedResourcePack.bytes,
          loadingFailed: null,
          bodyVerification: { base64Encoded: true, ...expectedResourcePack },
        }],
      },
      cleanup: { chromeExited: true, profileRemoved: true },
      screenshots: [screenshot],
      screenshotIdentity: [{
        path: screenshot,
        label: 'terrain-1',
        ...screenshotHash,
        terrainVisualPass: true,
        visual: terrainVisual,
      }],
      final: {
        state: {
          screen: null,
          level: 'net.minecraft.client.multiplayer.ClientLevel',
          loadedChunkCount: 7,
        },
        net: { errors: 0 },
        bridgeError: null,
        bridgeStats: {
          connected: 1,
          relayNodeSuccesses: 1,
          relayTargetAttestationFailures: 0,
          errors: 0,
          relayNodes: { 'WSS://RELAY.EXAMPLE/TUNNEL/': { successes: 1 } },
          connectPhases: [{
            id: 7,
            target: 'EXAMPLE.TEST:25565',
            phase: 'relay-connected',
            detail: 'WSS://RELAY.EXAMPLE/TUNNEL/',
          }],
        },
        screenshots: [screenshot],
      },
    };
    evidence.relayConnectionEvidence = recomputeRelayConnectionEvidence(evidence.final.bridgeStats);
    await writeFile(evidencePath, `${JSON.stringify(evidence, null, 2)}\n`);
    const valid = await validateEvidence(evidencePath, {
      expectedTarget: target,
      expectedRelay: relay,
      expectedArtifact: artifact,
    });
    assert.equal(valid.success, true);
    assert.ok(valid.checks.length >= 25);
    assert.equal(valid.verifiedScreenshots.length, 1);

    // Aggregate target/relay matches must not be composable across different
    // connection ids. Neither atomic record binds both expected endpoints.
    const splitConnectionEvidence = structuredClone(evidence);
    splitConnectionEvidence.final.bridgeStats.relayNodes = {
      'wss://wrong.example/tunnel': { successes: 1 },
      'wss://relay.example/tunnel': { successes: 1 },
    };
    splitConnectionEvidence.final.bridgeStats.connectPhases = [
      { id: 7, target, phase: 'relay-connected', detail: 'wss://wrong.example/tunnel' },
      { id: 8, target: 'wrong.example:25565', phase: 'relay-connected', detail: relay },
    ];
    splitConnectionEvidence.observed = {
      targets: [target, 'wrong.example:25565'],
      relays: ['wss://wrong.example/tunnel', relay],
    };
    // Forge the runner-declared record as a perfect match. The validator must
    // still reject because its independent final.bridgeStats reconstruction
    // contains only the two mismatched connection ids above.
    splitConnectionEvidence.relayConnectionEvidence = [{
      connectionId: 999,
      phaseIndex: 0,
      target,
      relay,
      phase: 'relay-connected',
      relayNodeSuccesses: 1,
    }];
    await writeFile(evidencePath, `${JSON.stringify(splitConnectionEvidence, null, 2)}\n`);
    const splitRejected = await validateEvidence(evidencePath, {
      expectedTarget: target,
      expectedRelay: relay,
      expectedArtifact: artifact,
      throwOnFailure: false,
    });
    assert.equal(splitRejected.checks.find((entry) =>
      entry.name === 'expected-target-observed')?.ok, true);
    assert.equal(splitRejected.checks.find((entry) =>
      entry.name === 'expected-relay-observed')?.ok, true);
    assert.equal(splitRejected.checks.find((entry) =>
      entry.name === 'relay-connection-evidence-recomputed')?.ok, false);
    assert.equal(splitRejected.checks.find((entry) =>
      entry.name === 'relay-connection-bound')?.ok, false);
    assert.equal(splitRejected.success, false);

    const missingPhaseEvidence = structuredClone(evidence);
    missingPhaseEvidence.final.bridgeStats.connectPhases[0].phase = 'relay-websocket-start';
    missingPhaseEvidence.relayConnectionEvidence = recomputeRelayConnectionEvidence(
      missingPhaseEvidence.final.bridgeStats,
    );
    await writeFile(evidencePath, `${JSON.stringify(missingPhaseEvidence, null, 2)}\n`);
    const missingPhaseRejected = await validateEvidence(evidencePath, {
      expectedTarget: target,
      expectedRelay: relay,
      expectedArtifact: artifact,
      throwOnFailure: false,
    });
    assert.equal(missingPhaseRejected.checks.find((entry) =>
      entry.name === 'relay-connection-bound')?.ok, false);

    const zeroSuccessEvidence = structuredClone(evidence);
    zeroSuccessEvidence.final.bridgeStats.relayNodes['WSS://RELAY.EXAMPLE/TUNNEL/'].successes = 0;
    zeroSuccessEvidence.relayConnectionEvidence = recomputeRelayConnectionEvidence(
      zeroSuccessEvidence.final.bridgeStats,
    );
    await writeFile(evidencePath, `${JSON.stringify(zeroSuccessEvidence, null, 2)}\n`);
    const zeroSuccessRejected = await validateEvidence(evidencePath, {
      expectedTarget: target,
      expectedRelay: relay,
      expectedArtifact: artifact,
      throwOnFailure: false,
    });
    assert.equal(zeroSuccessRejected.checks.find((entry) =>
      entry.name === 'relay-connection-bound')?.ok, false);

    // A sky gradient plus colorful HUD/crosshair passes broad whole-frame
    // variance, but must fail the lower-scene multi-row texture/edge gate.
    const skyHudPng = createTerrainVisualFixture({ skyOnly: true });
    const skyHudVisual = analyzeTerrainPng(skyHudPng);
    assert.ok(skyHudVisual.colorBuckets >= 8);
    assert.equal(terrainVisualPass(skyHudVisual), false);
    await writeFile(earlyScreenshot, skyHudPng);
    const earlyScreenshotHash = await hashFile(earlyScreenshot);

    // The runner starts retaining frames as soon as terrain first appears.
    // An early frame may legitimately fail the visual threshold while a later
    // stable frame passes. Both declarations must match independently, and the
    // aggregate visual gate must accept the evidence because one frame passes.
    const mixedVisualEvidence = structuredClone(evidence);
    mixedVisualEvidence.screenshotIdentity = [{
      path: earlyScreenshot,
      label: 'terrain-early',
      ...earlyScreenshotHash,
      terrainVisualPass: false,
      visual: skyHudVisual,
    }, {
      path: screenshot,
      label: 'terrain-1',
      ...screenshotHash,
      terrainVisualPass: true,
      visual: terrainVisual,
    }];
    mixedVisualEvidence.final.screenshots = [earlyScreenshot, screenshot];
    await writeFile(evidencePath, `${JSON.stringify(mixedVisualEvidence, null, 2)}\n`);
    const mixedVisualAccepted = await validateEvidence(evidencePath, {
      expectedTarget: target,
      expectedRelay: relay,
      expectedArtifact: artifact,
    });
    assert.equal(mixedVisualAccepted.success, true);
    assert.equal(mixedVisualAccepted.verifiedScreenshots.length, 2);
    assert.equal(mixedVisualAccepted.checks.find((entry) =>
      entry.name === 'terrain-visual-recomputed:terrain-early')?.ok, true);
    assert.equal(mixedVisualAccepted.checks.find((entry) =>
      entry.name === 'terrain-screenshot-visual')?.ok, true);

    await writeFile(screenshot, skyHudPng);
    const skyHudHash = await hashFile(screenshot);
    evidence.screenshotIdentity[0] = {
      path: screenshot,
      label: 'terrain-1',
      ...skyHudHash,
      // Forge the declaration to prove the validator trusts decoded PNG data.
      terrainVisualPass: true,
      visual: terrainVisual,
    };
    await writeFile(evidencePath, `${JSON.stringify(evidence, null, 2)}\n`);
    const skyHudRejected = await validateEvidence(evidencePath, {
      expectedTarget: target,
      expectedRelay: relay,
      expectedArtifact: artifact,
      throwOnFailure: false,
    });
    assert.equal(skyHudRejected.success, false);
    assert.equal(skyHudRejected.checks.find((entry) =>
      entry.name === 'terrain-screenshot-visual')?.ok, false);

    await writeFile(screenshot, terrainPng);
    evidence.screenshotIdentity[0] = {
      path: screenshot,
      label: 'terrain-1',
      ...screenshotHash,
      terrainVisualPass: true,
      visual: terrainVisual,
    };

    evidence.resourcePack.transactions[0].loadingFinished = false;
    evidence.resourcePack.succeeded = false;
    await writeFile(evidencePath, `${JSON.stringify(evidence, null, 2)}\n`);
    const invalid = await validateEvidence(evidencePath, {
      expectedTarget: target,
      expectedRelay: relay,
      expectedArtifact: artifact,
      throwOnFailure: false,
    });
    assert.equal(invalid.success, false);
    assert.equal(invalid.checks.find((entry) =>
      entry.name === 'resource-pack-exact-body-verified')?.ok, false);

    const wrongPack = structuredClone(evidence);
    wrongPack.resourcePack.succeeded = true;
    wrongPack.resourcePack.transactions[0].loadingFinished = true;
    wrongPack.resourcePack.transactions[0].bodyVerification.sha1 = '0'.repeat(40);
    await writeFile(evidencePath, `${JSON.stringify(wrongPack, null, 2)}\n`);
    const wrongPackRejected = await validateEvidence(evidencePath, {
      expectedTarget: target,
      expectedRelay: relay,
      expectedArtifact: artifact,
      throwOnFailure: false,
    });
    assert.equal(wrongPackRejected.checks.find((entry) =>
      entry.name === 'resource-pack-exact-body-verified')?.ok, false);

    const preflightOnly = structuredClone(wrongPack);
    preflightOnly.resourcePack.transactions[0].bodyVerification.sha1 = expectedResourcePack.sha1;
    preflightOnly.resourcePack.transactions[0].method = 'OPTIONS';
    await writeFile(evidencePath, `${JSON.stringify(preflightOnly, null, 2)}\n`);
    const preflightRejected = await validateEvidence(evidencePath, {
      expectedTarget: target,
      expectedRelay: relay,
      expectedArtifact: artifact,
      throwOnFailure: false,
    });
    assert.equal(preflightRejected.checks.find((entry) =>
      entry.name === 'resource-pack-exact-body-verified')?.ok, false);

    const wrongLength = structuredClone(evidence);
    wrongLength.resourcePack.transactions[0].declaredContentLength = expectedResourcePack.bytes - 1;
    await writeFile(evidencePath, `${JSON.stringify(wrongLength, null, 2)}\n`);
    const wrongLengthRejected = await validateEvidence(evidencePath, {
      expectedTarget: target,
      expectedRelay: relay,
      expectedArtifact: artifact,
      throwOnFailure: false,
    });
    assert.equal(wrongLengthRejected.checks.find((entry) =>
      entry.name === 'resource-pack-exact-body-verified')?.ok, false);

    const wrongUrl = structuredClone(evidence);
    wrongUrl.resourcePack.transactions[0].url = expectedResourcePackProxy(
      'wss://wrong.example/tunnel');
    await writeFile(evidencePath, `${JSON.stringify(wrongUrl, null, 2)}\n`);
    const wrongUrlRejected = await validateEvidence(evidencePath, {
      expectedTarget: target,
      expectedRelay: relay,
      expectedArtifact: artifact,
      throwOnFailure: false,
    });
    assert.equal(wrongUrlRejected.checks.find((entry) =>
      entry.name === 'resource-pack-exact-body-verified')?.ok, false);
    console.log(`CHECK_MULTIPLAYER_TERRAIN_EVIDENCE_STATIC_OK checks=${valid.checks.length}`);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

async function main() {
  if (process.argv.includes('--static-self-test')) {
    await runStaticSelfTest();
    return;
  }
  const evidencePath = process.argv[2] || process.env.EVIDENCE;
  if (!evidencePath) {
    throw new Error('usage: node tools/check-multiplayer-terrain-evidence.mjs <evidence.json>');
  }
  const result = await validateEvidence(evidencePath);
  console.log(JSON.stringify(result, null, 2));
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main().catch((error) => {
    console.error(error?.stack || error);
    process.exitCode = 1;
  });
}
