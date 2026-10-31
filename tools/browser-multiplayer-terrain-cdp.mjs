import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, mkdtemp, rm, stat, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { basename, dirname, resolve } from 'node:path';
import {
  analyzeTerrainPng,
  createTerrainVisualFixture,
  terrainVisualPass,
} from './terrain-visual-metrics.mjs';

const sleep = (milliseconds) => new Promise((done) => setTimeout(done, milliseconds));
import { expectedResourcePack } from './resource-pack-expectation.mjs';
const cdpResourcePackBufferBytes = 96 * 1024 * 1024;
const cdpTotalNetworkBufferBytes = 256 * 1024 * 1024;

function envInteger(name, fallback, minimum = 1) {
  const raw = process.env[name];
  if (raw == null || raw === '') return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < minimum) {
    throw new Error(`${name} must be an integer >= ${minimum}; received ${JSON.stringify(raw)}`);
  }
  return value;
}

async function hashFile(path) {
  const digest = createHash('sha256');
  let bytes = 0;
  for await (const chunk of createReadStream(path)) {
    bytes += chunk.length;
    digest.update(chunk);
  }
  return { bytes, sha256: digest.digest('hex') };
}

async function artifactIdentity(path) {
  const details = await stat(path);
  if (!details.isFile()) throw new Error(`ARTIFACT is not a file: ${path}`);
  const identity = await hashFile(path);
  return {
    path,
    name: basename(path),
    bytes: identity.bytes,
    sha256: identity.sha256,
    lastModifiedAt: details.mtime.toISOString(),
  };
}

async function freePort() {
  const server = createServer();
  await new Promise((done, fail) => {
    server.once('error', fail);
    server.listen(0, '127.0.0.1', done);
  });
  const port = server.address().port;
  await new Promise((done, fail) => server.close((error) => error ? fail(error) : done()));
  return port;
}

async function waitJson(url, timeoutMilliseconds = 20_000) {
  const deadline = Date.now() + timeoutMilliseconds;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { cache: 'no-store' });
      if (response.ok) return await response.json();
      lastError = new Error(`HTTP ${response.status}`);
    } catch (error) {
      lastError = error;
    }
    await sleep(100);
  }
  throw new Error(`timed out waiting for ${url}${lastError ? `: ${lastError.message}` : ''}`);
}

async function waitForExit(child, timeoutMilliseconds) {
  if (!child || child.exitCode != null || child.signalCode != null) return true;
  return await Promise.race([
    new Promise((done) => child.once('exit', () => done(true))),
    sleep(timeoutMilliseconds).then(() => false),
  ]);
}

async function removeChromeProfile(path) {
  let lastError = null;
  for (let attempt = 1; attempt <= 12; attempt++) {
    try {
      await rm(path, { recursive: true, force: true, maxRetries: 4, retryDelay: 100 });
      try {
        await stat(path);
      } catch (error) {
        if (error?.code === 'ENOENT') return { removed: true, attempts: attempt };
        throw error;
      }
      lastError = new Error(`profile still exists after rm: ${path}`);
    } catch (error) {
      lastError = error;
    }
    await sleep(250 * attempt);
  }
  return { removed: false, attempts: 12, error: String(lastError?.stack || lastError) };
}

function boundedAppend(array, value, limit) {
  if (array.length < limit) array.push(value);
}

function boundedTailAppend(array, value, limit) {
  array.push(value);
  if (array.length > limit) array.splice(0, array.length - limit);
}

function formatExceptionDetails(details) {
  const description = details?.exception?.description;
  const text = details?.text;
  const location = Number.isInteger(details?.lineNumber)
    ? ` at ${details.url || '<evaluation>'}:${details.lineNumber + 1}:${(details.columnNumber || 0) + 1}`
    : '';
  return `${description || text || 'Runtime.evaluate failed'}${location}`;
}

class Cdp {
  constructor(url, eventErrorSink = null) {
    this.socket = new WebSocket(url);
    this.sequence = 1;
    this.pending = new Map();
    this.listeners = new Map();
    this.eventErrorSink = eventErrorSink;
    this.closed = false;
  }

  async open(timeoutMilliseconds = 15_000) {
    await Promise.race([
      new Promise((done, fail) => {
        this.socket.onopen = done;
        this.socket.onerror = () => fail(new Error('CDP WebSocket failed to open'));
      }),
      sleep(timeoutMilliseconds).then(() => { throw new Error('CDP WebSocket open timeout'); }),
    ]);
    this.socket.onmessage = ({ data }) => this.#handleMessage(data);
    this.socket.onerror = () => this.#rejectPending(new Error('CDP WebSocket error'));
    this.socket.onclose = () => {
      this.closed = true;
      this.#rejectPending(new Error('CDP WebSocket closed'));
    };
  }

  #handleMessage(data) {
    let message;
    try {
      message = JSON.parse(data);
    } catch (error) {
      this.eventErrorSink?.(error);
      return;
    }
    if (message.id) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      clearTimeout(pending.timer);
      if (message.error) pending.reject(new Error(`${pending.method}: ${message.error.message}`));
      else pending.resolve(message.result || {});
      return;
    }
    for (const listener of this.listeners.get(message.method) || []) {
      try {
        listener(message.params || {});
      } catch (error) {
        this.eventErrorSink?.(error);
      }
    }
  }

  #rejectPending(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }

  send(method, params = {}, timeoutMilliseconds = 20_000) {
    if (this.closed || this.socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error(`CDP is not open for ${method}`));
    }
    const id = this.sequence++;
    return new Promise((resolvePromise, rejectPromise) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        rejectPromise(new Error(`${method} timed out after ${timeoutMilliseconds}ms`));
      }, timeoutMilliseconds);
      this.pending.set(id, { resolve: resolvePromise, reject: rejectPromise, timer, method });
      try {
        this.socket.send(JSON.stringify({ id, method, params }));
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(id);
        rejectPromise(error);
      }
    });
  }

  on(method, listener) {
    this.listeners.set(method, [...(this.listeners.get(method) || []), listener]);
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    this.#rejectPending(new Error('CDP client closed'));
    try { this.socket.close(); } catch {}
  }
}

async function evaluate(cdp, expression, report, label = 'evaluate') {
  const response = await cdp.send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (response.exceptionDetails) {
    const message = formatExceptionDetails(response.exceptionDetails);
    boundedAppend(report.logs.evaluateExceptions, {
      at: new Date().toISOString(),
      label,
      message,
      expression: expression.slice(0, 600),
    }, 100);
    throw new Error(`${label}: ${message}`);
  }
  return response.result?.value;
}

function isCdpCommandTimeout(error, method = 'Runtime.evaluate') {
  const message = String(error?.message || error || '');
  return message.startsWith(`${method} timed out after `) && /\d+ms$/.test(message);
}

async function evaluateWithRetry(cdp, expression, report, label, {
  attempts = 4,
  delayMilliseconds = 2_000,
} = {}) {
  let lastError = null;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    try {
      return await evaluate(cdp, expression, report, label);
    } catch (error) {
      if (!isCdpCommandTimeout(error) || attempt === attempts) throw error;
      lastError = error;
      boundedTailAppend(report.logs.evaluateTimeouts, {
        at: new Date().toISOString(), label, attempt, attempts,
        message: String(error.message || error), retrying: true,
      }, 100);
      await sleep(delayMilliseconds);
    }
  }
  throw lastError;
}

function appendConsoleEntry(report, entry) {
  if (/\[GAIUS_THROWABLE\]|java\.lang\.OutOfMemoryError|Browser native memory budget exceeded/.test(entry.text || '')) {
    boundedAppend(report.logs.fatalRuntimeErrors ||= [], entry, 100);
  }
  // Resource-pack progress emits thousands of lines in a few milliseconds. Keep a bounded
  // sample of that flood while preserving the newest disconnect/error diagnostics.
  const progress = /Progress for pack \d+: \d+ bytes/.test(entry.text || '');
  if (progress) {
    report.logs.resourcePackProgressCount++;
    if (report.logs.resourcePackProgressCount % 256 !== 1) return;
  }
  boundedTailAppend(report.logs.console, entry, 1_000);
}

function snapshotShowsTerminalDisconnect(snapshot) {
  return Number(snapshot?.net?.closed) >= 1
    || String(snapshot?.screen || '').includes('DisconnectedScreen');
}

function finalFromTrustedSample(snapshot, screenshots) {
  if (!snapshot) return null;
  return {
    state: {
      screen: snapshot.screen,
      screenTitle: snapshot.title,
      screenSize: snapshot.size,
      screenWidgets: snapshot.widgets || [],
      level: snapshot.level,
      loadedChunkCount: snapshot.loadedChunkCount,
      player: snapshot.player,
      gameMode: snapshot.gameMode,
      overlay: snapshot.overlay,
    },
    events: snapshot.events || [],
    bridge: snapshot.bridge || [],
    bridgeError: snapshot.bridgeError || null,
    net: snapshot.net || null,
    bridgeStats: snapshot.bridgeStats || null,
    screenshots: [...screenshots],
    body: null,
    retainedFromTrustedSample: true,
  };
}

async function click(cdp, x, y, name, report) {
  for (const [type, button, buttons] of [
    ['mouseMoved', 'none', 0],
    ['mousePressed', 'left', 1],
    ['mouseReleased', 'left', 0],
  ]) {
    await cdp.send('Input.dispatchMouseEvent', {
      type, x, y, button, buttons, clickCount: 1,
    });
  }
  report.actions.push({ at: new Date().toISOString(), name, x, y, via: 'CDP Input.dispatchMouseEvent' });
  await sleep(500);
}

function dialogControlPoint(state, canvas, kind, label) {
  const widgets = (state.screenWidgets || []).filter(widget =>
    widget.visible !== false && widget.active !== false);
  const widget = kind === 'password'
    ? widgets.find(entry => /EditBox$/.test(String(entry.type || '')))
    : widgets.find(entry => /Button/.test(String(entry.type || ''))
      && String(entry.text || '').replace(/\u00a7./g, '').trim() === label);
  const size = state.screenSize;
  if (!widget || !size?.width || !size?.height || !canvas?.width || !canvas?.height) {
    throw new Error(`Missing measurable dialog control: ${kind} ${label || ''}`);
  }
  const x = canvas.left + (widget.x + widget.width / 2) * canvas.width / size.width;
  const y = canvas.top + (widget.y + widget.height / 2) * canvas.height / size.height;
  if (!Number.isFinite(x) || !Number.isFinite(y)
      || x < canvas.left || x >= canvas.left + canvas.width
      || y < canvas.top || y >= canvas.top + canvas.height) {
    throw new Error(`Dialog control is outside the canvas: ${kind}`);
  }
  return { x, y };
}

function fallbackLoginControlPoint(state, canvas, kind) {
  const size = state.screenSize;
  if (String(state.screenTitle || '').trim() !== '\u767b\u5f55'
      || !size?.width || !size?.height || !canvas?.width || !canvas?.height) {
    throw new Error(`Missing measurable dialog control: ${kind}`);
  }
  // MultiButtonDialogScreen exposes its ScrollableLayout but not nested controls.
  const logical = kind === 'password' ? { x: 210, y: 113 } : { x: 137, y: 143 };
  return {
    x: canvas.left + logical.x * canvas.width / size.width,
    y: canvas.top + logical.y * canvas.height / size.height,
  };
}

async function clickLoginControl(cdp, kind, name, report) {
  const layout = await evaluateWithRetry(cdp, `(() => ({
    state: window.__gaiusMinecraftState || {},
    canvas: document.querySelector('canvas')?.getBoundingClientRect().toJSON()
  }))()`, report, name);
  if (!String(layout.state.screen || '').includes('MultiButtonDialogScreen')) {
    throw new Error(`Login dialog changed before ${name}`);
  }
  report.actions.push({ at: new Date().toISOString(), name: `${name}-layout`,
    screen: layout.state.screen, title: layout.state.screenTitle,
    size: layout.state.screenSize, widgets: layout.state.screenWidgets,
    canvas: layout.canvas });
  const coordinateOverride = process.env[kind === 'password' ? 'LOGIN_FIELD_POINT' : 'LOGIN_BUTTON_POINT'];
  let point;
  if (coordinateOverride) {
    const values = coordinateOverride.split(',').map(Number);
    if (values.length !== 2 || !values.every(Number.isFinite)) {
      throw new Error('Login point override must be x,y');
    }
    point = { x: values[0], y: values[1] };
    report.actions.push({ at: new Date().toISOString(), name: `${name}-explicit-point`, ...point });
  } else {
    try {
      point = dialogControlPoint(layout.state, layout.canvas, kind, '\u767b\u5f55');
    } catch (error) {
      point = fallbackLoginControlPoint(layout.state, layout.canvas, kind);
      report.actions.push({
        at: new Date().toISOString(), name: `${name}-nested-layout-fallback`, ...point,
        reason: String(error?.message || error),
      });
    }
  }
  await click(cdp, point.x, point.y, name, report);
}

async function typeText(cdp, value, report) {
  for (const character of value) {
    const code = /[A-Za-z]/.test(character)
      ? `Key${character.toUpperCase()}`
      : /[0-9]/.test(character) ? `Digit${character}` : '';
    const virtualKeyCode = /[A-Za-z0-9]/.test(character)
      ? character.toUpperCase().charCodeAt(0)
      : 0;
    await cdp.send('Input.dispatchKeyEvent', {
      type: 'rawKeyDown', key: character, code,
      windowsVirtualKeyCode: virtualKeyCode, nativeVirtualKeyCode: virtualKeyCode,
    });
    await cdp.send('Input.dispatchKeyEvent', { type: 'char', text: character, key: character, code });
    await cdp.send('Input.dispatchKeyEvent', {
      type: 'keyUp', key: character, code,
      windowsVirtualKeyCode: virtualKeyCode, nativeVirtualKeyCode: virtualKeyCode,
    });
  }
  report.actions.push({
    at: new Date().toISOString(), name: 'typeText', length: value.length,
    via: 'CDP Input.dispatchKeyEvent',
  });
}

function inferProfile(artifactPath) {
  const match = artifactPath.match(/(?:^|[\\/])(1\.21\.11|26\.2)(?:[\\/]|$)/);
  return process.env.PROFILE || match?.[1] || 'unknown';
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

function responseHeader(headers, name) {
  const expected = String(name).toLowerCase();
  for (const [key, value] of Object.entries(headers || {})) {
    if (String(key).toLowerCase() === expected) return String(value);
  }
  return null;
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

function relayNodeSuccesses(bridgeStats, relay) {
  const normalizedRelay = normalizeRelay(relay);
  let successes = 0;
  for (const [nodeRelay, node] of Object.entries(bridgeStats?.relayNodes || {})) {
    if (normalizeRelay(nodeRelay) === normalizedRelay) {
      successes = Math.max(successes, Number(node?.successes) || 0);
    }
  }
  return successes;
}

function relayConnectionEvidence(finalSnapshot) {
  const bridgeStats = finalSnapshot?.bridgeStats || {};
  const phases = Array.isArray(bridgeStats.connectPhases) ? bridgeStats.connectPhases : [];
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
        relayNodeSuccesses: relayNodeSuccesses(bridgeStats, relay),
        at: event.at ?? null,
        elapsedMillis: event.elapsedMillis ?? null,
      };
    });
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

function observedEndpoints(finalSnapshot) {
  const bridgeStats = finalSnapshot?.bridgeStats || {};
  const phases = Array.isArray(bridgeStats.connectPhases) ? bridgeStats.connectPhases : [];
  const targets = [...new Set(phases.map((phase) => normalizeTarget(phase?.target)).filter(Boolean))];
  const relays = new Set(Object.keys(bridgeStats.relayNodes || {}).map(normalizeRelay).filter(Boolean));
  for (const phase of phases) {
    if (typeof phase?.detail === 'string' && /^wss?:\/\//i.test(phase.detail)) {
      relays.add(normalizeRelay(phase.detail));
    }
  }
  return { targets, relays: [...relays] };
}

function summarizeResourcePack(transactions, relay) {
  const entries = [...transactions.values()].map((entry) => ({ ...entry }));
  const successful = entries.filter((entry) => verifiedExpectedResourcePackTransaction(entry, relay));
  return {
    required: true,
    expected: {
      ...expectedResourcePack,
      proxyUrl: expectedResourcePackProxy(relay),
    },
    succeeded: successful.length > 0,
    successfulRequestIds: successful.map((entry) => entry.requestId),
    transactions: entries,
  };
}

function acceptanceGates(report) {
  const state = report.final?.state;
  const bridge = report.final?.bridgeStats;
  const observedChunkPeak = Math.max(
    Number(state?.loadedChunkCount) || 0,
    ...(Array.isArray(report.samples)
      ? report.samples.map((sample) => Number(sample?.loadedChunkCount) || 0)
      : []),
  );
  // Acceptance is always derived from the raw final bridge snapshot. The
  // convenience/compatibility fields written beside it are not trust roots.
  const observed = observedEndpoints(report.final);
  const connections = relayConnectionEvidence(report.final);
  const boundConnection = matchingRelayConnection(connections, report.expected.target, report.expected.relay);
  return {
    clientLevel: state?.level === 'net.minecraft.client.multiplayer.ClientLevel',
    // A server can legitimately move the player to a death/reconfiguration
    // screen after terrain rendered; retain the peak raw sample as evidence.
    chunksLoaded: observedChunkPeak > 0,
    relayConnected: Number(bridge?.connected) >= 1,
    relaySucceeded: Number(bridge?.relayNodeSuccesses) >= 1,
    relayAttestationClean: Number(bridge?.relayTargetAttestationFailures) === 0,
    relayErrorsClean: Number(bridge?.errors) === 0,
    runtimeExceptionsClean: report.logs.exceptions.length === 0,
    fatalRuntimeErrorsClean: (report.logs.fatalRuntimeErrors || []).length === 0,
    evaluateExceptionsClean: report.logs.evaluateExceptions.length === 0,
    eventHandlerErrorsClean: report.logs.cdpEventErrors.length === 0,
    networkErrorsClean: Number(report.final?.net?.errors) === 0,
    bridgeInitClean: !report.final?.bridgeError,
    resourcePackSucceeded: report.resourcePack?.succeeded === true,
    expectedTargetObserved: observed.targets.includes(normalizeTarget(report.expected.target)),
    expectedRelayObserved: observed.relays.includes(normalizeRelay(report.expected.relay)),
    relayConnectionBound: Boolean(boundConnection),
    terrainScreenshotPresent: report.screenshotIdentity.some((entry) =>
      /(?:terrain|world|game)/i.test(entry.label || entry.path)
      && Number(entry.bytes) > 0
      && /^[0-9a-f]{64}$/.test(entry.sha256)
      && terrainVisualPass(entry.visual)),
    chromeExited: report.cleanup.chromeExited === true,
    profileRemoved: report.cleanup.profileRemoved === true,
    artifactUnchanged: report.artifactIdentity.unchanged === true,
  };
}

async function captureScreenshot(cdp, report, screenshotDirectory, suffix, label, navigationStarted = null) {
  const path = resolve(screenshotDirectory, `join-terrain-${report.profile}-${suffix}-${label}.png`);
  const response = await cdp.send('Page.captureScreenshot', { format: 'png', fromSurface: true });
  if (!response.data) throw new Error(`Chrome returned an empty screenshot for ${label}`);
  const buffer = Buffer.from(response.data, 'base64');
  if (buffer.length === 0) throw new Error(`Chrome returned a zero-byte screenshot for ${label}`);
  const visual = analyzeTerrainPng(buffer);
  if (Number.isFinite(navigationStarted) && /^terrain-/.test(label)
      && terrainVisualPass(visual) && report.startupPerformance?.durationMillis == null) {
    const durationMillis = performance.now() - navigationStarted;
    report.startupPerformance = {
      start: 'CDP navigation to portable multiplayer artifact requested',
      end: 'first terrain screenshot passing visual checks',
      durationMillis, limitMillis: 15000,
      passed: durationMillis <= 15000 && report.profiling?.diagnosticOnly !== true,
      latencyAcceptanceEligible: report.profiling?.diagnosticOnly !== true,
      observation: 'wall-clock upper bound including startup, server dialogs and CDP screenshot overhead',
      screenshotPath: path,
    };
  }
  await writeFile(path, buffer);
  const sha256 = createHash('sha256').update(buffer).digest('hex');
  report.screenshots.push(path);
  report.screenshotIdentity.push({
    path, label, bytes: buffer.length, sha256, visual,
    terrainVisualPass: terrainVisualPass(visual),
  });
  return path;
}

async function stopChrome(chrome, cdp, report) {
  report.cleanup.browserCloseRequested = false;
  report.cleanup.termination = [];
  if (cdp && !cdp.closed) {
    try {
      report.cleanup.browserCloseRequested = true;
      await cdp.send('Browser.close', {}, 3_000);
    } catch (error) {
      report.cleanup.termination.push({ step: 'Browser.close', error: String(error?.message || error) });
    }
  }
  cdp?.close();
  if (await waitForExit(chrome, 5_000)) {
    report.cleanup.chromeExited = true;
    report.cleanup.termination.push({ step: 'Browser.close/wait', exited: true });
    return;
  }
  const termSent = chrome?.kill('SIGTERM') || false;
  report.cleanup.termination.push({ step: 'SIGTERM', sent: termSent });
  if (await waitForExit(chrome, 5_000)) {
    report.cleanup.chromeExited = true;
    return;
  }
  const killSent = chrome?.kill('SIGKILL') || false;
  report.cleanup.termination.push({ step: 'SIGKILL', sent: killSent });
  report.cleanup.chromeExited = await waitForExit(chrome, 5_000);
}

async function runStaticSelfTest() {
  const loginState = { screenSize: { width: 427, height: 242 }, screenWidgets: [
    { type: 'EditBox', x: 100, y: 80, width: 200, height: 20, active: true },
    { type: 'Button$Plain', text: '\u767b\u5f55', x: 80, y: 130, width: 100, height: 20 },
  ] };
  const canvas = { left: 7, top: 11, width: 854, height: 484 };
  assert.deepEqual(dialogControlPoint(loginState, canvas, 'password'), { x: 407, y: 191 });
  assert.deepEqual(dialogControlPoint(loginState, canvas, 'button', '\u767b\u5f55'), { x: 267, y: 291 });
  assert.throws(() => dialogControlPoint({ ...loginState, screenWidgets: [] }, canvas, 'password'));
  const nestedLoginState = {
    screenTitle: '\u767b\u5f55',
    screenSize: { width: 427, height: 240 },
    screenWidgets: [{ type: 'ScrollableLayout$Container', active: true, visible: true }],
  };
  const nestedCanvas = { left: 0, top: 0, width: 854, height: 480 };
  assert.deepEqual(fallbackLoginControlPoint(nestedLoginState, nestedCanvas, 'password'),
    { x: 420, y: 226 });
  assert.deepEqual(fallbackLoginControlPoint(nestedLoginState, nestedCanvas, 'button'),
    { x: 274, y: 286 });
  const terrainVisual = analyzeTerrainPng(createTerrainVisualFixture());
  const skyHudVisual = analyzeTerrainPng(createTerrainVisualFixture({ skyOnly: true }));
  assert.equal(terrainVisualPass(terrainVisual), true);
  assert.equal(terrainVisual.terrainVisualPass, true);
  assert.equal(terrainVisualPass(skyHudVisual), false);
  assert.equal(skyHudVisual.terrainVisualPass, false);
  assert.equal(resourcePackUrlMatchesExpected(
    expectedResourcePackProxy('WSS://RELAY.EXAMPLE/TUNNEL/'),
    'wss://relay.example/tunnel'), true);
  assert.equal(resourcePackUrlMatchesExpected(
    expectedResourcePackProxy('wss://wrong.example/tunnel'),
    'wss://relay.example/tunnel'), false);
  assert.equal(resourcePackUrlMatchesExpected(
    expectedResourcePack.fixedMirrorUrl,
    'wss://relay.example/tunnel'), Boolean(expectedResourcePack.fixedMirrorUrl));
  assert.equal(resourcePackUrlMatchesExpected(
    `${expectedResourcePack.fixedMirrorUrl}?cache-bust=1`,
    'wss://relay.example/tunnel'), false);
  const fixture = {
    expected: { target: 'example.test:25565', relay: 'wss://relay.example/tunnel' },
    artifactIdentity: { unchanged: true },
    screenshots: ['C:/tmp/join-terrain-26.2-123-terrain-1.png'],
    screenshotIdentity: [{
      path: 'C:/tmp/join-terrain-26.2-123-terrain-1.png',
      label: 'terrain-1',
      bytes: 1024,
      sha256: 'a'.repeat(64),
      terrainVisualPass: true,
      visual: terrainVisual,
    }],
    logs: { exceptions: [], evaluateExceptions: [], cdpEventErrors: [] },
    cleanup: { chromeExited: true, profileRemoved: true },
    resourcePack: { succeeded: true },
    final: {
      state: {
        level: 'net.minecraft.client.multiplayer.ClientLevel',
        loadedChunkCount: 4,
      },
      net: { connected: 1, errors: 0 },
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
      screenshots: ['C:/tmp/join-terrain-26.2-123-terrain-1.png'],
    },
  };
  fixture.observed = observedEndpoints(fixture.final);
  fixture.relayConnectionEvidence = relayConnectionEvidence(fixture.final);
  assert.deepEqual(fixture.observed, {
    targets: ['example.test:25565'], relays: ['wss://relay.example/tunnel'],
  });
  assert.deepEqual(fixture.relayConnectionEvidence.map((entry) => ({
    connectionId: entry.connectionId,
    target: entry.target,
    relay: entry.relay,
    phase: entry.phase,
    relayNodeSuccesses: entry.relayNodeSuccesses,
  })), [{
    connectionId: 7,
    target: 'example.test:25565',
    relay: 'wss://relay.example/tunnel',
    phase: 'relay-connected',
    relayNodeSuccesses: 1,
  }]);
  assert.ok(Object.values(acceptanceGates(fixture)).every(Boolean));
  const oomFixture = structuredClone(fixture);
  oomFixture.logs.console = [];
  appendConsoleEntry(oomFixture, { type: 'error', text: '[GAIUS_THROWABLE] java.lang.OutOfMemoryError' });
  assert.equal(acceptanceGates(oomFixture).fatalRuntimeErrorsClean, false);
  const splitConnection = structuredClone(fixture);
  splitConnection.final.bridgeStats.relayNodes = {
    'wss://wrong.example/tunnel': { successes: 1 },
    'wss://relay.example/tunnel': { successes: 1 },
  };
  splitConnection.final.bridgeStats.connectPhases = [
    { id: 7, target: 'example.test:25565', phase: 'relay-connected', detail: 'wss://wrong.example/tunnel' },
    { id: 8, target: 'wrong.example:25565', phase: 'relay-connected', detail: 'wss://relay.example/tunnel' },
  ];
  splitConnection.observed = observedEndpoints(splitConnection.final);
  splitConnection.relayConnectionEvidence = relayConnectionEvidence(splitConnection.final);
  assert.equal(splitConnection.observed.targets.includes('example.test:25565'), true);
  assert.equal(splitConnection.observed.relays.includes('wss://relay.example/tunnel'), true);
  assert.equal(acceptanceGates(splitConnection).relayConnectionBound, false);
  const phaseMissing = structuredClone(fixture);
  phaseMissing.final.bridgeStats.connectPhases[0].phase = 'relay-websocket-start';
  phaseMissing.relayConnectionEvidence = relayConnectionEvidence(phaseMissing.final);
  assert.equal(acceptanceGates(phaseMissing).relayConnectionBound, false);
  const zeroSuccess = structuredClone(fixture);
  zeroSuccess.final.bridgeStats.relayNodes['WSS://RELAY.EXAMPLE/TUNNEL/'].successes = 0;
  zeroSuccess.relayConnectionEvidence = relayConnectionEvidence(zeroSuccess.final);
  assert.equal(acceptanceGates(zeroSuccess).relayConnectionBound, false);
  const exactPackFixture = new Map([['pack', {
    requestId: 'pack',
    method: 'GET',
    url: expectedResourcePackProxy(fixture.expected.relay),
    status: 200,
    declaredContentLength: expectedResourcePack.bytes,
    loadingFinished: true,
    encodedDataLength: expectedResourcePack.bytes,
    loadingFailed: null,
    bodyVerification: { base64Encoded: true, ...expectedResourcePack },
  }]]);
  assert.equal(summarizeResourcePack(exactPackFixture, fixture.expected.relay).succeeded, true);
  exactPackFixture.get('pack').declaredContentLength = null;
  assert.equal(summarizeResourcePack(exactPackFixture, fixture.expected.relay).succeeded, true,
    'chunked responses still require independently verified body size and both hashes');
  exactPackFixture.get('pack').declaredContentLength = expectedResourcePack.bytes;
  exactPackFixture.get('pack').method = 'OPTIONS';
  assert.equal(summarizeResourcePack(exactPackFixture, fixture.expected.relay).succeeded, false);
  exactPackFixture.get('pack').method = 'GET';
  exactPackFixture.get('pack').bodyVerification.sha1 = '0'.repeat(40);
  assert.equal(summarizeResourcePack(exactPackFixture, fixture.expected.relay).succeeded, false);
  exactPackFixture.get('pack').bodyVerification.sha1 = expectedResourcePack.sha1;
  exactPackFixture.get('pack').declaredContentLength = expectedResourcePack.bytes - 1;
  assert.equal(summarizeResourcePack(exactPackFixture, fixture.expected.relay).succeeded, false);
  exactPackFixture.get('pack').declaredContentLength = expectedResourcePack.bytes;
  exactPackFixture.get('pack').url = expectedResourcePackProxy('wss://wrong.example/tunnel');
  assert.equal(summarizeResourcePack(exactPackFixture, fixture.expected.relay).succeeded, false);
  fixture.logs.evaluateExceptions.push({ message: 'synthetic failure' });
  assert.equal(acceptanceGates(fixture).evaluateExceptionsClean, false);
  const disconnectedSample = {
    screen: 'net.minecraft.client.gui.screens.DisconnectedScreen',
    title: 'Connection Lost',
    level: null,
    loadedChunkCount: null,
    events: [{ event: 'client.notifyPlayerLoaded' }],
    net: { connected: 1, closed: 1, errors: 0 },
    bridgeStats: fixture.final.bridgeStats,
  };
  assert.equal(snapshotShowsTerminalDisconnect(disconnectedSample), true);
  const retainedFinal = finalFromTrustedSample(disconnectedSample, fixture.screenshots);
  assert.equal(retainedFinal.retainedFromTrustedSample, true);
  assert.equal(retainedFinal.state.screenTitle, 'Connection Lost');
  assert.equal(retainedFinal.net.closed, 1);
  assert.equal(retainedFinal.bridgeStats.relayNodeSuccesses, 1);
  assert.deepEqual(retainedFinal.screenshots, fixture.screenshots);
  assert.equal(snapshotShowsTerminalDisconnect({ screen: 'TitleScreen', net: { closed: 0 } }), false);
  assert.match(formatExceptionDetails({ text: 'boom', lineNumber: 0, columnNumber: 2 }), /boom.*:1:3/);
  console.log('BROWSER_MULTIPLAYER_TERRAIN_CDP_STATIC_OK');
}

async function main() {
  if (process.argv.includes('--static-self-test')) {
    await runStaticSelfTest();
    return;
  }

  if (!process.env.ARTIFACT) {
    throw new Error('ARTIFACT is required and must point to a compiled Gaius.html');
  }

  const artifact = resolve(process.env.ARTIFACT);
  const profile = inferProfile(artifact);
  const target = process.env.TARGET;
  if (!target) throw new Error('TARGET is required; do not store a public multiplayer target in the repository');
  const relay = process.env.RELAY || 'wss://ellan.site/tunnel';
  const packChoice = String(process.env.PACK_CHOICE || 'Yes').trim();
  const acceptanceSeconds = envInteger('ACCEPTANCE_SECONDS', 180, 10);
  const chromeBinary = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
  const suffix = Date.now().toString().slice(-9);
  const username = `Terrain${suffix}`;
  const password = `Gaius${profile.replaceAll('.', '')}P${suffix}`;
  const output = resolve(process.env.OUTPUT || `artifacts/join-terrain-${profile}-${suffix}.json`);
  const screenshotDirectory = resolve(process.env.SCREENSHOT_DIR || dirname(output));
  await mkdir(dirname(output), { recursive: true });
  await mkdir(screenshotDirectory, { recursive: true });

  const initialArtifactIdentity = await artifactIdentity(artifact);
  const debugPort = await freePort();
  const profileDir = await mkdtemp(`${tmpdir()}/gaius-terrain-cdp-`);
  const report = {
    schema: 'gaius.multiplayer-terrain-cdp-acceptance.v3',
    runner: {
      path: resolve(process.argv[1]),
      node: process.version,
      platform: process.platform,
      architecture: process.arch,
      input: 'CDP Input.dispatchMouseEvent/Input.dispatchKeyEvent only',
    },
    profile,
    artifact,
    artifactIdentity: { ...initialArtifactIdentity, unchanged: null },
    expected: {
      target, relay, packChoice,
      resourcePack: { ...expectedResourcePack, proxyUrl: expectedResourcePackProxy(relay) },
    },
    // Compatibility fields retained for older evidence consumers.
    target,
    relay,
    username,
    passwordLength: password.length,
    acceptanceSeconds,
    startedAt: new Date().toISOString(),
    actions: [],
    transitions: [],
    samples: [],
    logs: {
      exceptions: [],
      fatalRuntimeErrors: [],
      evaluateExceptions: [],
      evaluateTimeouts: [],
      resourcePackProgressCount: 0,
      cdpEventErrors: [],
      screenshotErrors: [],
      failedResources: [],
      finishedResources: [],
      requests: [],
      responses: [],
      network: [],
      websocketFrames: [],
      console: [],
      chromeStdout: '',
      chromeStderr: '',
    },
    screenshots: [],
    screenshotIdentity: [],
    final: null,
    observed: { targets: [], relays: [] },
    relayConnectionEvidence: [],
    resourcePack: { required: true, succeeded: false, successfulRequestIds: [], transactions: [] },
    gates: {},
    cleanup: {
      profileDir,
      chromeExited: false,
      profileRemoved: false,
      browserCloseRequested: false,
      termination: [],
    },
    success: false,
  };

  const chrome = spawn(chromeBinary, [
    '--headless=new',
    `--remote-debugging-port=${debugPort}`,
    '--remote-allow-origins=*',
    `--user-data-dir=${profileDir}`,
    '--window-size=854,484',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-background-networking',
    '--disable-component-update',
    '--disable-domain-reliability',
    '--disable-features=Translate,MediaRouter',
    'about:blank',
  ], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });

  const appendChildOutput = (current, chunk) => (current + String(chunk)).slice(-64_000);
  chrome.stdout?.on('data', (chunk) => {
    report.logs.chromeStdout = appendChildOutput(report.logs.chromeStdout, chunk);
  });
  chrome.stderr?.on('data', (chunk) => {
    report.logs.chromeStderr = appendChildOutput(report.logs.chromeStderr, chunk);
  });
  chrome.on('error', (error) => {
    report.cleanup.spawnError = String(error?.stack || error);
  });
  chrome.on('exit', (code, signal) => {
    report.cleanup.exitCode = code;
    report.cleanup.signal = signal;
  });

  let cdp;
  let cpuProfilingStarted = false;
  const requestUrls = new Map();
  const resourcePackTransactions = new Map();
  const pendingResourcePackBodyVerifications = [];
  try {
    await Promise.race([
      waitJson(`http://127.0.0.1:${debugPort}/json/version`),
      new Promise((_, rejectPromise) => chrome.once('error', rejectPromise)),
    ]);
    const targets = await waitJson(`http://127.0.0.1:${debugPort}/json/list`);
    const page = targets.find((candidate) => candidate.type === 'page');
    if (!page?.webSocketDebuggerUrl) throw new Error('Chrome did not expose a page target');
    cdp = new Cdp(page.webSocketDebuggerUrl, (error) => {
      boundedAppend(report.logs.cdpEventErrors, String(error?.stack || error), 100);
    });
    await cdp.open();

    if (process.env.TRACE_WEBSOCKET === '1') {
      // Opt-in diagnostic capture, bounded to the initial handshake only.
      for (const direction of ['Sent', 'Received']) {
        cdp.on(`Network.webSocketFrame${direction}`, (event) => {
          boundedAppend(report.logs.websocketFrames, {
            direction, requestId: event.requestId, timestamp: event.timestamp,
            opcode: event.response.opcode,
            payload: event.response.payloadData.slice(0, 16384),
            truncated: event.response.payloadData.length > 16384,
          }, 64);
        });
      }
    }

    cdp.on('Runtime.exceptionThrown', (event) => boundedAppend(
      report.logs.exceptions,
      {
        at: new Date().toISOString(),
        message: formatExceptionDetails(event.exceptionDetails),
        details: event.exceptionDetails,
      },
      100,
    ));
    cdp.on('Runtime.consoleAPICalled', (event) => appendConsoleEntry(report, {
      at: new Date().toISOString(),
      type: event.type,
      text: (event.args || []).map((argument) => argument.value ?? argument.description ?? '').join(' ').slice(0, 2_000),
    }));
    cdp.on('Network.requestWillBeSent', (event) => {
      const url = String(event.request?.url || '');
      requestUrls.set(event.requestId, url);
      if (/\/proxy\/resource-pack(?:\?|$)/i.test(url)) {
        resourcePackTransactions.set(event.requestId, {
          requestId: event.requestId,
          url,
          method: event.request?.method || '',
          requestedAt: new Date().toISOString(),
          status: null,
          mimeType: null,
          loadingFinished: false,
          loadingFailed: null,
        });
      } else if (resourcePackUrlMatchesExpected(url, relay)) {
        resourcePackTransactions.set(event.requestId, {
          requestId: event.requestId,
          url,
          method: event.request?.method || '',
          requestedAt: new Date().toISOString(),
          status: null,
          mimeType: null,
          loadingFinished: false,
          loadingFailed: null,
        });
      }
      boundedAppend(report.logs.requests, {
        url, method: event.request?.method || '', type: event.type || '', documentURL: event.documentURL || '',
      }, 1_000);
      if (/proxy|resource|pack|ellan\.site/i.test(url)) boundedAppend(report.logs.network, {
        at: new Date().toISOString(), kind: 'request', id: event.requestId, url,
        method: event.request?.method, initiator: event.initiator?.type,
      }, 2_000);
    });
    cdp.on('Network.responseReceived', (event) => {
      const url = String(event.response?.url || requestUrls.get(event.requestId) || '');
      boundedAppend(report.logs.responses, {
        requestId: event.requestId, url, status: event.response?.status || 0,
        mimeType: event.response?.mimeType || '', type: event.type || '',
      }, 1_000);
      const resourcePack = resourcePackTransactions.get(event.requestId);
      if (resourcePack || /\/proxy\/resource-pack(?:\?|$)/i.test(url)
          || resourcePackUrlMatchesExpected(url, relay)) {
        const transaction = resourcePack || {
          requestId: event.requestId,
          url,
          method: '',
          requestedAt: null,
          loadingFinished: false,
          loadingFailed: null,
        };
        transaction.url = url;
        transaction.status = event.response?.status ?? null;
        transaction.mimeType = event.response?.mimeType || null;
        transaction.declaredContentLength = responseHeader(
          event.response?.headers, 'content-length');
        transaction.responseAt = new Date().toISOString();
        resourcePackTransactions.set(event.requestId, transaction);
      }
      if (/proxy|resource|pack|ellan\.site/i.test(url)) boundedAppend(report.logs.network, {
        at: new Date().toISOString(), kind: 'response', id: event.requestId, url,
        status: event.response?.status, mimeType: event.response?.mimeType,
        encodedDataLength: event.response?.encodedDataLength,
      }, 2_000);
    });
    cdp.on('Network.loadingFinished', (event) => {
      const url = requestUrls.get(event.requestId) || '';
      boundedAppend(report.logs.finishedResources, {
        at: new Date().toISOString(), requestId: event.requestId, url,
        encodedDataLength: event.encodedDataLength,
      }, 1_000);
      const resourcePack = resourcePackTransactions.get(event.requestId);
      if (resourcePack) {
        resourcePack.loadingFinished = true;
        resourcePack.finishedAt = new Date().toISOString();
        resourcePack.encodedDataLength = event.encodedDataLength ?? null;
        if (resourcePack.method === 'GET'
            && /\/proxy\/resource-pack(?:\?|$)/i.test(resourcePack.url)
            && resourcePack.bodyVerification === undefined
            && resourcePack.bodyVerificationPending !== true) {
          resourcePack.bodyVerificationPending = true;
          const verification = (async () => {
            try {
              const result = await cdp.send('Network.getResponseBody', {
                requestId: event.requestId,
              }, 120_000);
              if (result.base64Encoded !== true) {
                throw new Error('CDP returned the binary resource pack without base64 encoding');
              }
              const body = Buffer.from(result.body || '', 'base64');
              resourcePack.bodyVerification = {
                base64Encoded: true,
                bytes: body.length,
                sha1: createHash('sha1').update(body).digest('hex'),
                sha256: createHash('sha256').update(body).digest('hex'),
                verifiedAt: new Date().toISOString(),
              };
            } catch (error) {
              resourcePack.bodyVerification = {
                base64Encoded: false,
                bytes: null,
                sha1: null,
                sha256: null,
                error: String(error?.stack || error),
                verifiedAt: new Date().toISOString(),
              };
            } finally {
              delete resourcePack.bodyVerificationPending;
            }
          })();
          pendingResourcePackBodyVerifications.push(verification);
        }
      }
    });
    cdp.on('Network.loadingFailed', (event) => boundedAppend(report.logs.failedResources, {
      at: new Date().toISOString(),
      errorText: event.errorText,
      canceled: event.canceled,
      type: event.type,
      url: requestUrls.get(event.requestId) || null,
    }, 1_000));
    cdp.on('Network.loadingFailed', (event) => {
      const resourcePack = resourcePackTransactions.get(event.requestId);
      if (resourcePack) {
        resourcePack.loadingFailed = {
          at: new Date().toISOString(),
          errorText: event.errorText || 'loading failed',
          canceled: Boolean(event.canceled),
        };
      }
    });

    await Promise.all([
      cdp.send('Runtime.enable'),
      cdp.send('Page.enable'),
      cdp.send('Network.enable', {
        maxTotalBufferSize: cdpTotalNetworkBufferBytes,
        maxResourceBufferSize: cdpResourcePackBufferBytes,
      }),
    ]);

    const launchUrl = `file:///${artifact.replaceAll('\\', '/')}?server=${encodeURIComponent(target)}`
      + `&username=${encodeURIComponent(username)}&offlineDeveloperMode=1`
      + `&relay=${encodeURIComponent(relay)}&bridge=${encodeURIComponent(relay)}`
      + (process.env.DISABLE_RELAY_REGISTRIES === '1' ? '&relayRegistry=0' : '');
    report.launchUrl = launchUrl + (process.env.PROFILE_RELOAD === '1' ? '&diag=reload' : '');
    if (process.env.PROFILE_RELOAD === '1') {
      report.profiling = {diagnosticOnly: true, latencyAcceptanceEligible: false};
      await cdp.send('Profiler.enable');
      await cdp.send('Profiler.setSamplingInterval', {interval: 1000});
      await cdp.send('Profiler.start');
      cpuProfilingStarted = true;
    }
    const navigationStarted = performance.now();
    report.startupPerformance = { durationMillis: null, limitMillis: 15000, passed: false };
    await cdp.send('Page.navigate', { url: report.launchUrl });

    let lastScreenKey = '';
    const completedRuleScreens = new Set();
    let accountDone = false;
    let loginDone = false;
    let packDone = false;
    let warningBackDone = false;
    let terrainFirstSecond = null;
    let lastTrustedSnapshot = null;
    let pollingStoppedAfterDisconnectTimeout = false;

    for (let second = 1; second <= acceptanceSeconds; second++) {
      await sleep(1_000);
      let snapshot;
      try {
        snapshot = await evaluateWithRetry(cdp, `(() => {
        const state = window.__gaiusMinecraftState || {};
        return {
          screen: state.screen,
          title: state.screenTitle,
          size: state.screenSize,
          widgets: state.screenWidgets || [],
          level: state.level,
          loadedChunkCount: state.loadedChunkCount,
          player: state.player,
          gameMode: state.gameMode,
          overlay: state.overlay,
          events: (window.__gaiusMinecraftEvents || []).slice(-12),
          net: window.__gaiusNetworkStats || null,
          bridgeStats: window.__gaiusNettyBridge?.stats || null,
          resourceReloadTimings: window.__gaiusResourceReloadTimings || [],
          resourceReloadSections: window.__gaiusResourceReloadSections || [],
          glStats: window.__gaiusGLStats || null,
          chunkDrawTelemetry: window.__gaiusChunkDrawTelemetry || null,
          bridge: window.__gaiusNettyBridgeInitTrace || [],
          bridgeError: window.__gaiusNettyBridgeInitError || null
        };
        })()`, report, `state sample ${second}`, { attempts: 3, delayMilliseconds: 1_000 });
      } catch (error) {
        if (isCdpCommandTimeout(error)) {
          boundedTailAppend(report.logs.evaluateTimeouts, {
            at: new Date().toISOString(), label: `state sample ${second}`,
            attempt: 'exhausted', message: String(error.message || error), retrying: false,
          }, 100);
          if (snapshotShowsTerminalDisconnect(lastTrustedSnapshot)) {
            pollingStoppedAfterDisconnectTimeout = true;
            report.final = finalFromTrustedSample(lastTrustedSnapshot, report.screenshots);
            report.actions.push({
              at: new Date().toISOString(),
              name: 'retain-trusted-snapshot-after-disconnect-evaluate-timeout',
              second,
              via: 'last successful Runtime.evaluate state sample',
            });
            break;
          }
        }
        throw error;
      }

      lastTrustedSnapshot = snapshot;
      report.resourceReloadTimings = snapshot.resourceReloadTimings || [];
      report.resourceReloadSections = snapshot.resourceReloadSections || [];
      report.glStats = snapshot.glStats;
      report.chunkDrawTelemetry = snapshot.chunkDrawTelemetry;

      report.samples.push({
        second,
        screen: snapshot.screen,
        title: snapshot.title,
        level: snapshot.level,
        loadedChunkCount: snapshot.loadedChunkCount,
        player: snapshot.player,
        gameMode: snapshot.gameMode,
        events: snapshot.events,
        net: snapshot.net && {
          connected: snapshot.net.connected,
          closed: snapshot.net.closed,
          relayNodeSuccesses: snapshot.net.relayNodeSuccesses,
          relayTargetAttestationFailures: snapshot.net.relayTargetAttestationFailures,
          receivedFrames: snapshot.net.receivedFrames,
          receivedBytes: snapshot.net.receivedBytes,
          errors: snapshot.net.errors,
        },
      });

      const screenKey = `${snapshot.screen}|${snapshot.title}`;
      if (screenKey !== lastScreenKey) {
        lastScreenKey = screenKey;
        report.transitions.push({
          second, screen: snapshot.screen, title: snapshot.title, widgets: snapshot.widgets,
        });
        console.log('TRANSITION', second, snapshot.screen, JSON.stringify(snapshot.title),
          'level', snapshot.level, 'chunks', snapshot.loadedChunkCount);
      }

      const hasTerrain = snapshot.level === 'net.minecraft.client.multiplayer.ClientLevel'
        && Number(snapshot.loadedChunkCount) > 0;
      if (hasTerrain) {
        if (terrainFirstSecond == null) {
          terrainFirstSecond = second;
          report.terrainFirstSecond = second;
          console.log('TERRAIN', second, snapshot.loadedChunkCount, snapshot.player);
        }
        if (second <= terrainFirstSecond + 12) {
          try {
            await captureScreenshot(cdp, report, screenshotDirectory, suffix, `terrain-${second}`, navigationStarted);
          } catch (error) {
            boundedAppend(report.logs.screenshotErrors, {
              at: new Date().toISOString(), label: `terrain-${second}`, error: String(error?.stack || error),
            }, 100);
          }
        }
        if (report.resourcePack.succeeded && second >= terrainFirstSecond + 12) break;
      }

      const ruleTitle = String(snapshot.title || '');
      if (!completedRuleScreens.has(ruleTitle)
          && String(snapshot.screen || '').includes('MultiButtonDialogScreen')
          && /(服务器规则|守则)/.test(ruleTitle)) {
        completedRuleScreens.add(ruleTitle);
        const ruleScreenIndex = completedRuleScreens.size;
        try {
          await captureScreenshot(cdp, report, screenshotDirectory, suffix,
            `rules-${ruleScreenIndex}-top`);
        } catch {}
        for (let index = 0; index < 9; index++) {
          await cdp.send('Input.dispatchMouseEvent', {
            type: 'mouseMoved', x: 640, y: 300, button: 'none',
          });
          await cdp.send('Input.dispatchMouseEvent', {
            type: 'mouseWheel', x: 640, y: 300, deltaX: 0, deltaY: 900,
          });
          await sleep(300);
        }
        try {
          await captureScreenshot(cdp, report, screenshotDirectory, suffix,
            `rules-${ruleScreenIndex}-bottom`);
        } catch {}
        report.actions.push({
          at: new Date().toISOString(), name: 'scrollRulesBottom', count: 9,
          ruleScreenIndex, title: ruleTitle,
          via: 'CDP Input.dispatchMouseEvent',
        });
        await click(cdp, 286, 398, 'rulesCheckbox', report);
        await click(cdp, 428, 454, 'rulesAgree', report);
        continue;
      }

      if (!accountDone && String(snapshot.screen || '').includes('MultiButtonDialogScreen')
          && String(snapshot.title || '').includes('创建账号')) {
        accountDone = true;
        try { await captureScreenshot(cdp, report, screenshotDirectory, suffix, 'create-account'); } catch {}
        await click(cdp, 420, 280, 'passwordField', report);
        await typeText(cdp, password, report);
        await click(cdp, 420, 366, 'confirmPasswordField', report);
        await typeText(cdp, password, report);
        await click(cdp, 426, 426, 'registerButton', report);
        continue;
      }

      if (!loginDone && String(snapshot.screen || '').includes('MultiButtonDialogScreen')
          && String(snapshot.title || '').trim() === '登录') {
        loginDone = true;
        try { await captureScreenshot(cdp, report, screenshotDirectory, suffix, 'login'); } catch {}
        await clickLoginControl(cdp, 'password', 'loginPasswordField', report);
        await typeText(cdp, password, report);
        await clickLoginControl(cdp, 'button', 'loginButton', report);
        continue;
      }

      if (!packDone && String(snapshot.screen || '').includes('PackConfirmScreen')) {
        packDone = true;
        try { await captureScreenshot(cdp, report, screenshotDirectory, suffix, 'pack-confirm'); } catch {}
        const choice = packChoice.toLowerCase();
        const candidates = choice === 'no'
          ? ['No']
          : ['Download', 'Accept', 'Proceed', 'Yes', 'Continue', 'Done'];
        let chosen = null;
        for (const candidate of candidates) {
          const widget = snapshot.widgets.find((entry) =>
            String(entry.text || '').trim().toLowerCase() === candidate.toLowerCase()
            && entry.active !== false);
          if (!widget) continue;
          const scaleX = 854 / (snapshot.size?.width || 427);
          const scaleY = 484 / (snapshot.size?.height || 242);
          await click(cdp,
            (widget.x + widget.width / 2) * scaleX,
            (widget.y + widget.height / 2) * scaleY,
            `pack:${widget.text}`,
            report);
          chosen = widget.text;
          break;
        }
        if (!chosen) {
          report.actions.push({
            at: new Date().toISOString(), name: 'pack-confirm-no-candidate',
            choice: packChoice, widgets: snapshot.widgets,
          });
          console.log('PACK WIDGETS', JSON.stringify(snapshot.widgets));
        }
        continue;
      }

      if (!warningBackDone && String(snapshot.screen || '').includes('WarningScreen')) {
        warningBackDone = true;
        try { await captureScreenshot(cdp, report, screenshotDirectory, suffix, 'warning'); } catch {}
        const back = snapshot.widgets.find((entry) => String(entry.text || '').trim() === 'Back');
        if (back) {
          const scaleX = 854 / (snapshot.size?.width || 427);
          const scaleY = 484 / (snapshot.size?.height || 242);
          await click(cdp,
            (back.x + back.width / 2) * scaleX,
            (back.y + back.height / 2) * scaleY,
            'warningBack',
            report);
        }
      }
    }

    if (!pollingStoppedAfterDisconnectTimeout) {
      try {
        report.final = await evaluateWithRetry(cdp, `(() => ({
          state: window.__gaiusMinecraftState || null,
          events: window.__gaiusMinecraftEvents || [],
          bridge: window.__gaiusNettyBridgeInitTrace || [],
          bridgeError: window.__gaiusNettyBridgeInitError || null,
          net: window.__gaiusNetworkStats || null,
          bridgeStats: window.__gaiusNettyBridge?.stats || null,
          screenshots: ${JSON.stringify(report.screenshots)},
          body: document.body?.innerText || ''
        }))()`, report, 'final evidence snapshot', { attempts: 5, delayMilliseconds: 2_000 });
      } catch (error) {
        if (!isCdpCommandTimeout(error) || !snapshotShowsTerminalDisconnect(lastTrustedSnapshot)) throw error;
        boundedTailAppend(report.logs.evaluateTimeouts, {
          at: new Date().toISOString(), label: 'final evidence snapshot',
          attempt: 'exhausted-retained-trusted-snapshot',
          message: String(error.message || error), retrying: false,
        }, 100);
        report.final = finalFromTrustedSample(lastTrustedSnapshot, report.screenshots);
        report.actions.push({
          at: new Date().toISOString(),
          name: 'retain-trusted-snapshot-after-final-disconnect-evaluate-timeout',
          via: 'last successful Runtime.evaluate state sample',
        });
      }
    }
    // Keep final.screenshots authoritative even when screenshots were captured after the JS literal was built.
    report.final.screenshots = [...report.screenshots];
    report.observed = observedEndpoints(report.final);
    report.relayConnectionEvidence = relayConnectionEvidence(report.final);
  } catch (error) {
    report.error = String(error?.stack || error);
    console.error(report.error);
  } finally {
    await Promise.allSettled(pendingResourcePackBodyVerifications);
    if (cpuProfilingStarted) {
      try {
        const {profile: cpuProfile} = await cdp.send('Profiler.stop', {}, 10000);
        report.profiling.path = `${output}.cpuprofile`;
        await writeFile(report.profiling.path, JSON.stringify(cpuProfile));
        report.profiling.samples = cpuProfile.samples?.length || 0;
      } catch (error) { report.profiling.error = String(error?.message || error); }
    }
    await stopChrome(chrome, cdp, report);
    const profileCleanup = await removeChromeProfile(profileDir);
    report.cleanup.profileRemoved = profileCleanup.removed;
    report.cleanup.profileRemoveAttempts = profileCleanup.attempts;
    if (profileCleanup.error) report.cleanup.profileRemoveError = profileCleanup.error;

    try {
      const finalArtifactIdentity = await artifactIdentity(artifact);
      report.artifactIdentity.finalBytes = finalArtifactIdentity.bytes;
      report.artifactIdentity.finalSha256 = finalArtifactIdentity.sha256;
      report.artifactIdentity.unchanged = finalArtifactIdentity.bytes === report.artifactIdentity.bytes
        && finalArtifactIdentity.sha256 === report.artifactIdentity.sha256;
    } catch (error) {
      report.artifactIdentity.unchanged = false;
      report.artifactIdentity.finalIdentityError = String(error?.stack || error);
    }

    if (report.final) report.final.screenshots = [...report.screenshots];
    report.observed = observedEndpoints(report.final);
    report.relayConnectionEvidence = relayConnectionEvidence(report.final);
    report.resourcePack = summarizeResourcePack(resourcePackTransactions, relay);
    report.gates = acceptanceGates(report);
    report.success = Object.values(report.gates).every(Boolean);
    report.finishedAt = new Date().toISOString();
    await writeFile(output, `${JSON.stringify(report, null, 2)}\n`);
    console.log('RESULT', JSON.stringify({
      output,
      success: report.success,
      artifactIdentity: report.artifactIdentity,
      expected: report.expected,
      observed: report.observed,
      relayConnectionEvidence: report.relayConnectionEvidence,
      resourcePack: report.resourcePack,
      gates: report.gates,
      screen: report.final?.state?.screen,
      title: report.final?.state?.screenTitle,
      level: report.final?.state?.level,
      chunks: report.final?.state?.loadedChunkCount,
      relaySuccesses: report.final?.bridgeStats?.relayNodeSuccesses,
      attestationFailures: report.final?.bridgeStats?.relayTargetAttestationFailures,
      bridgeErrors: report.final?.bridgeStats?.errors,
      runtimeExceptions: report.logs.exceptions.length,
      evaluateExceptions: report.logs.evaluateExceptions.length,
      screenshots: report.screenshots,
      cleanup: report.cleanup,
    }, null, 2));
    if (!report.success) process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exitCode = 1;
});
