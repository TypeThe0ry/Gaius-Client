#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const repository = new URL("../..", import.meta.url);
const sourcePath = new URL(
  "../overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserWebSocketChannel.java",
  import.meta.url,
);
const source = readFileSync(sourcePath, "utf8");
const networkSourcePath = new URL(
  "../src/main/java/dev/gaius/browser/BrowserClientNetwork.java",
  import.meta.url,
);
const networkSource = readFileSync(networkSourcePath, "utf8");

// This smoke is deliberately a small deterministic model. It proves the lifecycle contract
// without starting TeaVM, Chrome, or a network tunnel: a remote close must eventually retire a
// dead channel even when no subsequent Java tick arrives, while allowing one bounded decoder
// handoff to drain already-buffered bytes first.
assert.match(source, /const remoteCloseRetireGraceMillis = 5000;/);
assert.match(source, /const remoteCloseRetireRetryMillis = 16;/);
assert.match(source, /remoteClosedAt: 0,/);
assert.match(source, /retireClosedHandle: 0,/);
assert.match(source, /retireClosedPending: false,/);
assert.match(source, /state\.retireClosedEntry = function\(entry\)/);
assert.match(source, /state\.retireClosedEntry\(entry\);/);
assert.match(source, /function signalInbound\(reason\)/);
assert.match(source, /state\.inboundPump\(String\(reason \|\| 'requested'\)\)/);
assert.match(source, /const ownsCurrentEntry = state\.channels\.get\(entryId\) === entry/);
assert.match(source, /if \(ownsCurrentEntry\) \{\s*state\.channels\.delete\(entryId\);/s);
assert.match(source, /state\.inboundPump\('remote-close-retire'\)/);
assert.match(networkSource, /const closeWake = String\(reason \|\| ''\) === 'remote-close-retire';/);
assert.match(networkSource, /bridge\.exactPacketQueuePaused && !closeWake/);
assert.match(networkSource, /bridge\.inboundPump = function\(reason\)/);
assert.match(networkSource, /schedulePump\(String\(reason \|\| 'requested'\)\)/);

const failStart = source.indexOf("function fail(entry, message)");
const failEnd = source.indexOf("function sendControl(entry, message)", failStart);
assert.ok(failStart >= 0 && failEnd > failStart, "missing transport fail body");
const failBody = source.slice(failStart, failEnd);
assert.match(failBody, /entry\.closed = true/);
assert.match(failBody, /state\.retireClosedEntry\(entry\);/);
assert.ok(
  failBody.indexOf("entry.closed = true") < failBody.indexOf("entry.ws.close()"),
  "fail must close the logical entry before the browser close callback can re-enter",
);
assert.equal(
  failBody.includes("entry.disposed = true"),
  false,
  "fail must not mark the entry disposed before retireClosedEntry schedules its finalizer",
);

const onCloseStart = source.indexOf("ws.onclose = function(event)");
const onCloseEnd = source.indexOf("};", onCloseStart);
assert.ok(onCloseStart >= 0 && onCloseEnd > onCloseStart, "missing remote onclose body");
const onCloseBody = source.slice(onCloseStart, onCloseEnd);
assert.match(onCloseBody, /entry\.closed = true/);
assert.match(onCloseBody, /entry\.connected = false/);
assert.match(onCloseBody, /state\.retireClosedEntry\(entry\)/);
const staleCloseGuardOffset = onCloseBody.indexOf(
  "if (generation !== entry.webSocketGeneration || entry.closed) return;",
);
const staleCloseTimeoutClearOffset = onCloseBody.indexOf("clearCandidateTimeout(entry);");
assert.ok(staleCloseGuardOffset >= 0,
  "remote onclose lost its generation/closed guard");
assert.ok(staleCloseTimeoutClearOffset > staleCloseGuardOffset,
  "stale candidate onclose can clear the current candidate timeout before its generation guard");

// A failed candidate closes asynchronously. Its old onclose can therefore run after failover has
// installed a timeout for the next generation. The old callback must be a no-op; otherwise it
// clears the new timer and leaves a permanently CONNECTING entry with no eventual cleanup.
function candidateFailoverRaceModel({staleCloseClearsCurrentTimeout}) {
  let clock = 0;
  let sequence = 0;
  let generation = 0;
  let candidateTimeout = null;
  let closed = false;
  let finalized = false;
  let newTimeoutFired = false;
  const timers = [];

  function schedule(fn, delay = 0) {
    const task = {id: ++sequence, at: clock + delay, fn, canceled: false};
    timers.push(task);
    return task;
  }

  function clearCandidateTimeout() {
    if (!candidateTimeout) return;
    candidateTimeout.canceled = true;
    candidateTimeout = null;
  }

  function finalize() {
    finalized = true;
    candidateTimeout = null;
  }

  function failAndRetire() {
    closed = true;
    clearCandidateTimeout();
    schedule(finalize);
  }

  function onClose(candidateGeneration) {
    if (staleCloseClearsCurrentTimeout) clearCandidateTimeout();
    if (candidateGeneration !== generation || closed) return;
    clearCandidateTimeout();
    failAndRetire();
  }

  function openCandidate() {
    const candidateGeneration = ++generation;
    const timeout = schedule(() => {
      if (candidateTimeout === timeout) candidateTimeout = null;
      if (closed || candidateGeneration !== generation) return;
      if (candidateGeneration === 1) {
        // close() queues the old callback, while failover starts synchronously and arms gen 2.
        schedule(() => onClose(candidateGeneration));
        openCandidate();
      } else {
        newTimeoutFired = true;
        failAndRetire();
      }
    }, 10);
    candidateTimeout = timeout;
  }

  function runNext() {
    timers.sort((left, right) => left.at - right.at || left.id - right.id);
    const task = timers.shift();
    if (!task) return false;
    if (task.canceled) return true;
    clock = Math.max(clock, task.at);
    task.fn();
    return true;
  }

  openCandidate();
  while (!finalized && runNext()) {}
  return {newTimeoutFired, finalized, generation, clock};
}

const legacyCandidateRace = candidateFailoverRaceModel({staleCloseClearsCurrentTimeout: true});
assert.equal(legacyCandidateRace.newTimeoutFired, false,
  "candidate race model no longer demonstrates the pre-fix timer loss");
assert.equal(legacyCandidateRace.finalized, false,
  "legacy stale onclose unexpectedly reached bounded candidate cleanup");
const fixedCandidateRace = candidateFailoverRaceModel({staleCloseClearsCurrentTimeout: false});
assert.equal(fixedCandidateRace.generation, 2,
  "candidate timeout/failover did not advance to a fresh WebSocket generation");
assert.equal(fixedCandidateRace.newTimeoutFired, true,
  "new generation candidate timeout was cleared by stale old onclose");
assert.equal(fixedCandidateRace.finalized, true,
  "new generation timeout did not reach eventual bounded cleanup");

// A WebSocket may expose a Blob even though binaryType was requested as ArrayBuffer (for example
// through a relay/polyfill). arrayBuffer() resolves later, after candidate fallback can have
// incremented entry.webSocketGeneration. Both promise callbacks must re-check generation and
// closed state at execution time, not only the synchronous onmessage entry point.
// The Blob conversions of one connection are serialized (a Blob resolves later than the message
// order), so the chain re-checks generation, closed state and the socket identity at each step.
const blobChainStart = source.indexOf("entry.blobArrivalChain = previousBlobChain");
const blobChainEnd = source.indexOf("ws.onerror = function()", blobChainStart);
assert.ok(
  blobChainStart >= 0 && blobChainEnd > blobChainStart,
  "missing serialized Blob arrayBuffer promise chain",
);
const blobArrayBufferBody = source.slice(blobChainStart, blobChainEnd);
const blobGenerationGuard =
  /generation !== entry\.webSocketGeneration\s*\|\|\s*entry\.closed\s*\|\|\s*entry\.ws !== ws/gu;
assert.equal(
  blobArrayBufferBody.match(blobGenerationGuard).length,
  3,
  "Blob conversion, resolve and rejection callbacks must each carry the generation/closed guard",
);
const blobConversionOffset = blobArrayBufferBody.indexOf("return blob.arrayBuffer();");
const blobSuccessStart = blobArrayBufferBody.indexOf(".then(function(buffer)");
const blobSuccessGuardOffset = blobArrayBufferBody.search(blobGenerationGuard);
const blobSuccessGuardInResolve = blobArrayBufferBody.slice(blobSuccessStart).search(blobGenerationGuard);
const blobSuccessDeliveryOffset = blobArrayBufferBody.search(
  /deliverInbound\(entry,\s*buffer(?:,\s*arrivalToken|,\s*blobArrivalToken)?\)/u,
);
const blobRejectStart = blobArrayBufferBody.indexOf(".catch(function(error)");
const blobRejectGuardOffset = blobArrayBufferBody.slice(blobRejectStart).search(blobGenerationGuard);
const blobRejectFailureOffset = blobArrayBufferBody.slice(blobRejectStart).indexOf("fail(entry, error");
assert.ok(
  blobSuccessGuardOffset >= 0 && blobSuccessGuardOffset < blobConversionOffset,
  "a stale Blob can be converted before its generation guard",
);
assert.ok(
  blobSuccessStart >= 0 && blobSuccessGuardInResolve >= 0 &&
    blobSuccessStart + blobSuccessGuardInResolve < blobSuccessDeliveryOffset,
  "stale Blob resolve can deliver before its generation guard",
);
assert.ok(
  blobRejectStart >= 0 && blobRejectGuardOffset >= 0 && blobRejectGuardOffset < blobRejectFailureOffset,
  "stale Blob rejection can fail the current entry before its generation guard",
);


function delayedBlobGenerationRaceModel({guardCallbacks}) {
  const entry = {
    webSocketGeneration: 1,
    closed: false,
    inbound: [],
    failures: [],
  };
  const oldGeneration = entry.webSocketGeneration;
  const delayedCallbacks = [
    {kind: "resolve", generation: oldGeneration, value: "old-blob-buffer"},
    {kind: "reject", generation: oldGeneration, value: "old-blob-error"},
  ];

  // Candidate 1 failed over to candidate 2. The entry remains live, but its generation changed.
  entry.webSocketGeneration = 2;
  for (const callback of delayedCallbacks) {
    if (guardCallbacks && (
      callback.generation !== entry.webSocketGeneration || entry.closed
    )) {
      continue;
    }
    if (callback.kind === "resolve") {
      entry.inbound.push(callback.value);
    } else {
      entry.failures.push(callback.value);
    }
  }
  return {
    oldGeneration,
    currentGeneration: entry.webSocketGeneration,
    deliveredByOldGeneration: entry.inbound.length,
    failedByOldGeneration: entry.failures.length,
  };
}

const legacyBlobRace = delayedBlobGenerationRaceModel({guardCallbacks: false});
assert.equal(legacyBlobRace.oldGeneration, 1);
assert.equal(legacyBlobRace.currentGeneration, 2);
assert.equal(legacyBlobRace.deliveredByOldGeneration, 1,
  "Blob race model no longer demonstrates the pre-fix stale delivery");
assert.equal(legacyBlobRace.failedByOldGeneration, 1,
  "Blob race model no longer demonstrates the pre-fix stale failure");
const fixedBlobRace = delayedBlobGenerationRaceModel({guardCallbacks: true});
assert.equal(fixedBlobRace.oldGeneration, 1);
assert.equal(fixedBlobRace.currentGeneration, 2);
assert.equal(fixedBlobRace.deliveredByOldGeneration, 0,
  "stale Blob resolve delivered bytes to the replacement generation");
assert.equal(fixedBlobRace.failedByOldGeneration, 0,
  "stale Blob rejection failed the replacement generation");

const discardStart = source.lastIndexOf("state.discardInbound = function(entry)");
const discardEnd = source.indexOf("};", discardStart);
assert.ok(discardStart >= 0 && discardEnd > discardStart, "missing final discardInbound body");
const discardBody = source.slice(discardStart, discardEnd);
const retireStart = source.indexOf("state.retireClosedEntry = function(entry)");
const retireBodyTail = /state\.deliverInbound = function\(entry, buffer(?:, arrivalToken)?\)/u.exec(
  source.slice(retireStart),
);
const retireEnd = retireBodyTail === null
  ? -1 : retireStart + retireBodyTail.index;
assert.ok(retireStart >= 0 && retireEnd > retireStart, "missing retire implementation");
const retireBody = source.slice(retireStart, retireEnd);
const finalizeStart = source.indexOf("function finalizeRemoteCloseRetire(entry, forced)");
assert.ok(finalizeStart >= 0 && finalizeStart < retireStart, "missing retire finalizer");
const finalizeBody = source.slice(finalizeStart, retireEnd);
assert.match(finalizeBody, /state\.discardInbound\(entry\)/);
assert.match(finalizeBody, /state\.channels\.delete\((?:entry\.id\|0|entryId)\)/);
assert.match(finalizeBody, /state\.stopEventLoopGapProbeIfIdle\(\)/);
assert.match(discardBody, /entry\.inbound = \[\]/);
assert.match(discardBody, /entry\.pendingInbound = \[\]/);

const GRACE_MS = 5000;
const RETRY_MS = 16;

function createModel() {
  let clock = 0;
  let sequence = 0;
  const timers = [];
  const channels = new Map();
  const stats = {
    scheduled: 0,
    deferred: 0,
    forced: 0,
    finalized: 0,
    signals: 0,
  };

  function schedule(fn, delay) {
    const task = { id: ++sequence, at: clock + delay, fn, canceled: false };
    timers.push(task);
    return task;
  }

  function cancel(task) {
    if (task) task.canceled = true;
  }

  function hasWork(entry) {
    return !entry.disposed && (
      entry.inbound > 0 ||
      entry.pendingInbound > 0 ||
      entry.sliceScheduled ||
      entry.decodedBacklog > 0 ||
      entry.activeDecoder
    );
  }

  function finalize(entry, forced) {
    if (!entry || entry.disposed) return;
    cancel(entry.retireHandle);
    entry.retireHandle = null;
    entry.retirePending = false;
    entry.inbound = 0;
    entry.pendingInbound = 0;
    entry.sliceScheduled = false;
    entry.decodedBacklog = 0;
    entry.activeDecoder = false;
    entry.disposed = true;
    if (channels.get(entry.id) === entry) channels.delete(entry.id);
    stats.finalized++;
    if (forced) stats.forced++;
  }

  function retire(entry) {
    if (!entry || entry.disposed || entry.retirePending) return;
    const startedAt = Number(entry.remoteClosedAt) || clock;
    entry.remoteClosedAt = startedAt;
    stats.scheduled++;
    const retry = () => {
      entry.retirePending = false;
      entry.retireHandle = null;
      if (!entry || entry.disposed) return;
      const elapsed = Math.max(0, clock - startedAt);
      if (hasWork(entry) && elapsed < GRACE_MS) {
        stats.deferred++;
        stats.signals++;
        entry.retirePending = true;
        entry.retireHandle = schedule(retry, RETRY_MS);
        return;
      }
      finalize(entry, hasWork(entry) && elapsed >= GRACE_MS);
    };
    entry.retirePending = true;
    entry.retireHandle = schedule(retry, 0);
  }

  function runNext() {
    timers.sort((left, right) => left.at - right.at || left.id - right.id);
    const task = timers.shift();
    if (!task) return false;
    if (task.canceled) return true;
    clock = Math.max(clock, task.at);
    task.fn();
    return true;
  }

  function runUntilIdle(limit = 1000) {
    let steps = 0;
    while (steps++ < limit && runNext()) {}
    assert.ok(steps <= limit, "retire retry loop exceeded bounded timer limit");
    return steps - 1;
  }

  function open(id = 1) {
    const entry = {
      id,
      remoteClosedAt: 0,
      retireHandle: null,
      retirePending: false,
      inbound: 0,
      pendingInbound: 0,
      sliceScheduled: false,
      decodedBacklog: 0,
      activeDecoder: false,
      disposed: false,
    };
    channels.set(id, entry);
    return entry;
  }

  function closeExplicit(entry) {
    cancel(entry.retireHandle);
    entry.retireHandle = null;
    entry.retirePending = false;
    entry.disposed = true;
    if (channels.get(entry.id) === entry) channels.delete(entry.id);
  }

  return {
    channels,
    stats,
    get clock() {
      return clock;
    },
    open,
    retire,
    runNext,
    runUntilIdle,
    closeExplicit,
  };
}

// No buffered work: one zero-delay callback removes the dead entry and leaves no timer behind.
{
  const model = createModel();
  const entry = model.open();
  model.retire(entry);
  model.runUntilIdle();
  assert.equal(model.channels.size, 0);
  assert.equal(model.stats.scheduled, 1);
  assert.equal(model.stats.deferred, 0);
  assert.equal(model.stats.finalized, 1);
  assert.equal(model.stats.forced, 0);
}

// Buffered work gets one bounded signal/handoff. If the decoder drains before the retry, the
// channel is retired cleanly rather than being force-discarded.
{
  const model = createModel();
  const entry = model.open();
  entry.pendingInbound = 3;
  entry.sliceScheduled = true;
  model.retire(entry);
  assert.equal(model.runNext(), true);
  assert.equal(model.channels.size, 1);
  assert.equal(model.stats.deferred, 1);
  entry.pendingInbound = 0;
  entry.sliceScheduled = false;
  model.runUntilIdle();
  assert.equal(model.channels.size, 0);
  assert.equal(model.stats.finalized, 1);
  assert.equal(model.stats.forced, 0);
  assert.equal(model.stats.signals, 1);
}

// A missing Java tick cannot leak the channel forever: the grace timer force-cleans at most
// ceil(5000/16)+1 callbacks and records that it had to discard unfinished decoder work.
{
  const model = createModel();
  const entry = model.open();
  entry.activeDecoder = true;
  model.retire(entry);
  const callbacks = model.runUntilIdle();
  assert.equal(model.channels.size, 0);
  assert.equal(model.stats.finalized, 1);
  assert.equal(model.stats.forced, 1);
  assert.ok(callbacks <= Math.ceil(GRACE_MS / RETRY_MS) + 2);
  assert.ok(model.clock >= GRACE_MS);
}

// Repeated onclose notifications are idempotent, explicit close cancels the pending timer, and
// an old callback cannot delete a newly opened channel that reuses the same numeric id.
{
  const model = createModel();
  const oldEntry = model.open(7);
  oldEntry.pendingInbound = 1;
  model.retire(oldEntry);
  model.retire(oldEntry);
  assert.equal(model.stats.scheduled, 1);
  const replacement = model.open(7);
  oldEntry.disposed = true;
  model.runUntilIdle();
  assert.equal(model.channels.get(7), replacement);
  model.closeExplicit(replacement);
  assert.equal(model.channels.size, 0);
}

// The Java channel registry is separate from the JS bridge map.  A finalizer must emit exactly
// one close wake so a stale Java channel can take the existing bounded `pump()` close path even
// when no normal Minecraft tick follows.  The wake is the only exception to exact-queue pause;
// it closes transport state, never dispatches PLAY handlers or drains PacketProcessor.
function createJavaLifecycleModel() {
  let clock = 0;
  let sequence = 0;
  const timers = [];
  const bridgeEntries = new Map();
  const javaChannels = new Map();
  const stats = {
    ordinaryPumpBlocked: 0,
    closeWakeRequests: 0,
    closeWakeCallbacks: 0,
    javaChannelsClosedByWake: 0,
    playHandlersFromCloseWake: 0,
    packetProcessorDrainsFromCloseWake: 0,
  };
  let exactPacketQueuePaused = false;

  function schedule(fn, delay = 0) {
    const task = { id: ++sequence, at: clock + delay, fn, canceled: false };
    timers.push(task);
    return task;
  }

  function runNext() {
    timers.sort((left, right) => left.at - right.at || left.id - right.id);
    const task = timers.shift();
    if (!task) return false;
    if (task.canceled) return true;
    clock = Math.max(clock, task.at);
    task.fn();
    return true;
  }

  function hasPendingInbound(id) {
    const entry = bridgeEntries.get(id);
    return !!entry && !entry.disposed && entry.pendingInbound > 0;
  }

  function javaPump() {
    for (const channel of javaChannels.values()) {
      if (!channel.open) continue;
      const entry = bridgeEntries.get(channel.id);
      if ((!entry || entry.closed) && !hasPendingInbound(channel.id)) {
        channel.open = false;
        channel.active = false;
        stats.javaChannelsClosedByWake++;
      }
    }
  }

  function signal(reason = "requested") {
    const closeWake = String(reason) === "remote-close-retire";
    if (exactPacketQueuePaused && !closeWake) {
      stats.ordinaryPumpBlocked++;
      return false;
    }
    if (closeWake) stats.closeWakeRequests++;
    schedule(() => {
      if (closeWake) stats.closeWakeCallbacks++;
      javaPump();
    });
    return true;
  }

  function open(id, generation) {
    const entry = {
      id,
      generation,
      closed: false,
      disposed: false,
      pendingInbound: 0,
    };
    bridgeEntries.set(id, entry);
    // A numeric socket id can be replaced in the bridge; the current Java channel is the one
    // whose lifecycle must survive an old entry's delayed callback.
    javaChannels.set(id, {id, generation, open: true, active: true});
    return entry;
  }

  function finalize(entry) {
    if (!entry || entry.disposed) return;
    const ownsCurrentEntry = bridgeEntries.get(entry.id) === entry;
    entry.pendingInbound = 0;
    entry.disposed = true;
    if (ownsCurrentEntry) bridgeEntries.delete(entry.id);
    if (ownsCurrentEntry) signal("remote-close-retire");
  }

  function fail(entry) {
    if (!entry || entry.closed) return;
    entry.closed = true;
    finalize(entry);
  }

  return {
    bridgeEntries,
    javaChannels,
    stats,
    setExactPacketQueuePaused(value) {
      exactPacketQueuePaused = !!value;
    },
    open,
    fail,
    finalize,
    signal,
    runNext,
    get pendingTimers() {
      return timers.length;
    },
  };
}

// Transport error/fail must share the same identity-guarded retirement path as a clean
// WebSocket onclose.  This covers the common browser sequence where onerror marks the entry
// closed and the subsequent onclose is ignored, so no later Java tick is available to clean it.
{
  const model = createJavaLifecycleModel();
  const entry = model.open(25, 1);
  model.fail(entry);
  model.fail(entry);
  assert.equal(model.stats.closeWakeRequests, 1);
  assert.equal(model.runNext(), true);
  assert.equal(model.javaChannels.get(25).open, false);
  assert.equal(model.stats.javaChannelsClosedByWake, 1);
  assert.equal(model.pendingTimers, 0);
}

// A fail-triggered wake queued before a numeric-id replacement must observe the replacement and
// leave its Java channel open, exactly like the clean-close generation race.
{
  const model = createJavaLifecycleModel();
  const oldEntry = model.open(26, 1);
  model.fail(oldEntry);
  const replacement = model.open(26, 2);
  assert.equal(model.runNext(), true);
  assert.equal(model.bridgeEntries.get(26), replacement);
  assert.equal(model.javaChannels.get(26).generation, 2);
  assert.equal(model.javaChannels.get(26).open, true);
  assert.equal(model.stats.javaChannelsClosedByWake, 0);
}

// No following Java tick: the identity-owned finalizer emits one close wake and removes the
// Java channel from the registry through the existing closed(id)/hasPendingInbound(id) path.
{
  const model = createJavaLifecycleModel();
  const entry = model.open(21, 1);
  model.finalize(entry);
  assert.equal(model.stats.closeWakeRequests, 1);
  assert.equal(model.runNext(), true);
  assert.equal(model.stats.closeWakeCallbacks, 1);
  assert.equal(model.javaChannels.get(21).open, false);
  assert.equal(model.javaChannels.get(21).active, false);
  assert.equal(model.stats.javaChannelsClosedByWake, 1);
  assert.equal(model.stats.playHandlersFromCloseWake, 0);
  assert.equal(model.stats.packetProcessorDrainsFromCloseWake, 0);
  assert.equal(model.pendingTimers, 0);
}

// Exact-packet pause still blocks ordinary inbound transport wakes, but the close-only wake is
// admitted so a stale Java channel cannot survive forever behind a paused global queue.
{
  const model = createJavaLifecycleModel();
  model.setExactPacketQueuePaused(true);
  assert.equal(model.signal("requested"), false);
  const entry = model.open(22, 1);
  model.finalize(entry);
  assert.equal(model.stats.closeWakeRequests, 1);
  assert.equal(model.runNext(), true);
  assert.equal(model.javaChannels.get(22).open, false);
  assert.equal(model.stats.ordinaryPumpBlocked, 1);
}

// An old finalizer cannot delete or wake a replacement entry that reuses the bridge id.
{
  const model = createJavaLifecycleModel();
  const oldEntry = model.open(23, 1);
  oldEntry.closed = true;
  const replacement = model.open(23, 2);
  model.finalize(oldEntry);
  assert.equal(model.stats.closeWakeRequests, 0);
  assert.equal(model.bridgeEntries.get(23), replacement);
  assert.equal(model.javaChannels.get(23).generation, 2);
  assert.equal(model.javaChannels.get(23).open, true);
  assert.equal(model.pendingTimers, 0);
}

// If a close wake was queued just before a replacement arrived, the wake may run but it must
// observe the replacement as live and leave that Java channel open.
{
  const model = createJavaLifecycleModel();
  const oldEntry = model.open(24, 1);
  model.finalize(oldEntry);
  const replacement = model.open(24, 2);
  assert.equal(model.runNext(), true);
  assert.equal(model.stats.closeWakeCallbacks, 1);
  assert.equal(model.bridgeEntries.get(24), replacement);
  assert.equal(model.javaChannels.get(24).generation, 2);
  assert.equal(model.javaChannels.get(24).open, true);
  assert.equal(model.stats.javaChannelsClosedByWake, 0);
}

console.log(JSON.stringify({
  schema: "gaius.browser-remote-close-retire-smoke.v1",
  status: "pass",
  graceMillis: GRACE_MS,
  retryMillis: RETRY_MS,
  noNextTickCleanup: true,
  bufferedDrainBeforeRetire: true,
  forcedCleanupBounded: true,
  duplicateCloseIdempotent: true,
  replacementGenerationSafe: true,
  javaChannelRetireWake: true,
  exactPauseCloseWakeBypass: true,
  staleReplacementWakeSafe: true,
  blobGenerationGuard: true,
  staleBlobResolveDropped: true,
  staleBlobRejectDropped: true,
  failPathRetire: true,
  sourcePath: repository.pathname,
}, null, 2));
