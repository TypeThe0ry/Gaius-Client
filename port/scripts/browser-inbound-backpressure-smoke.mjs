#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {MessageChannel} from "node:worker_threads";
import vm from "node:vm";
import {patcherSource} from "./patcher-sources.mjs";

const sourcePath = new URL(
  "../overrides/libraries/netty-transport/src/main/java/" +
    "io/netty/channel/browser/BrowserWebSocketChannel.java",
  import.meta.url,
);
const source = await readFile(sourcePath, "utf8");
// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const clientPatcher = patcherSource("MinecraftClientPatcher");
const clientNetwork = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserClientNetwork.java",
  import.meta.url,
), "utf8");
const packetScheduler = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserPacketScheduler.java",
  import.meta.url,
), "utf8");
function jsBodyBefore(marker) {
  const markerOffset = source.indexOf(marker);
  const annotationOffset = source.lastIndexOf('@JSBody(script = """', markerOffset);
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  const body = source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
  if (marker === "private static native void initBridge();") {
    return "{\n" + body + "\n}\n{\n" +
      jsBodyBefore("private static native void initBridgeTail();") + "\n}";
  }
  return body;
}
function jsBodyBeforeIn(javaSource, marker) {
  const markerOffset = javaSource.indexOf(marker);
  const annotationOffset = javaSource.lastIndexOf("@JSBody", markerOffset);
  const scriptOffset = javaSource.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = javaSource.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  return javaSource.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}
assert.match(source, /maximumInboundSliceBytes = 4 \* 1024/);
assert.match(source, /decodedSliceHighWatermark = 256/);
assert.match(source, /decodedSliceLowWatermark = 64/);
assert.match(source, /inboundFrameHighWatermark = 1024/);
assert.match(source, /inboundFrameLowWatermark = 256/);
assert.match(source, /decoderCumulationPauseBytes = 12 \* 1024 \* 1024/);
assert.match(source, /maximumDecoderCumulationBytes = 16 \* 1024 \* 1024/);
assert.match(source, /MAX_CHUNKS_PER_PUMP = 64/);
assert.match(source, /MAX_BYTES_PER_PUMP = 256 \* 1024/);
assert.match(source, /MAX_MILLIS_PER_PUMP = 2\.0/);
assert.match(source, /inboundSliceScheduleWaitSamples/);
assert.match(source, /maxInboundSliceScheduleWaitMillis/);
assert.match(source, /inboundMessageChannelSchedules: 0/,
  "inbound continuation MessageChannel scheduling telemetry is missing");
assert.match(source, /inboundMessageChannelCallbacks: 0/,
  "inbound continuation MessageChannel callback telemetry is missing");
assert.match(source, /inboundContinuationMacrotasks: 0/,
  "inbound continuation macrotask telemetry is missing");
assert.match(source, /inboundMessageChannelFailures: 0/,
  "inbound continuation MessageChannel failure telemetry is missing");
assert.match(source, /inboundContinuationStaleCallbacks: 0/,
  "inbound continuation stale-callback telemetry is missing");
assert.match(source, /highWatermarkEventSequence: 0/);
assert.match(source, /highWatermarkEvents: \[\]/);
assert.match(source, /highWatermarkEventsDropped: 0/);
assert.match(source, /queuedPacketHandleSamples: 0/);
assert.match(source, /maxQueuedPacketHandleMillis: 0/);
assert.match(source, /slowQueuedPacketEventSequence: 0/);
assert.match(source, /slowQueuedPacketEvents: \[\]/);
assert.match(source, /maximumSlowQueuedPacketEvents = 64/);
assert.match(source, /slowQueuedPacketThresholdMillis = 50/);
assert.match(source, /public static boolean pumpAllAndReportProgress\(\)/,
  "the independent dispatcher cannot observe bounded transport progress");
assert.match(source, /return progressed \|\| budgetExhausted;/,
  "a budget-shortened global scan must advertise continuation before the ready-input check");
// pumpAllAndReportProgress already folds budgetExhausted into its boolean
// result. Keep this truth-table assertion beside the source contract so a
// future refactor cannot accidentally turn the Java callback into
// `budgetExhausted && hasPumpableInput` (which would drop ordinary progress).
const pumpContinuationHint = (sliceProgressed, budgetExhausted) =>
  sliceProgressed || budgetExhausted;
assert.equal(pumpContinuationHint(false, true), true,
  "budget-only global pump turns must advertise continuation");
assert.equal(pumpContinuationHint(false, false), false,
  "idle global pump turns must not advertise continuation");
assert.match(source, /public static boolean hasPumpableInput\(\)/,
  "the independent dispatcher cannot stop cleanly on exact-queue pressure");
assert.match(source,
  /hasPumpableInboundScheduled = function\(id\)[\s\S]*!state\.exactPacketQueuePaused[\s\S]*entry\.inboundHead < entry\.inbound\.length/,
  "pumpable input must mean ready and exact-queue-unpaused, not pending-only input");
assert.match(source,
  /wasPaused && !state\.exactPacketQueuePaused[\s\S]*decodedPacketQueueResumes\+\+;[\s\S]*signalInbound\(\);/,
  "exact-queue resume does not wake already-buffered raw transport input");
assert.match(clientNetwork,
  /private static final BrowserPumpCallback PUMP_CALLBACK = BrowserClientNetwork::pumpInbound;/,
  "the browser bridge does not cache its raw-transport Java callback");
assert.match(clientNetwork,
  /installInboundPump\(PUMP_CALLBACK\)/,
  "the browser bridge does not install exactly one cached raw-transport callback");
assert.match(clientNetwork,
  /private static native boolean installInboundPump\(BrowserPumpCallback callback\);/,
  "the browser bridge retained a second client PLAY callback parameter");
assert.match(clientNetwork, /new MessageChannel\(\)/,
  "the independent transport pump is not scheduled on a browser macrotask");
assert.match(clientNetwork, /finish\('watchdog'\)/,
  "the independent transport pump lacks a bounded MessageChannel watchdog");
assert.match(clientNetwork,
  /BrowserWebSocketChannel\.pumpAllAndReportProgress\(\)/,
  "the independent callback does not execute the existing bounded transport pump");
assert.match(clientNetwork,
  /continuationHint && BrowserWebSocketChannel\.hasPumpableInput\(\)/,
  "the independent callback can spin without progress or stop before ready input drains");
assert.match(clientNetwork,
  /while \(true\) \{[\s\S]*pumpAllAndReportProgress\(\)[\s\S]*HostLoop\.yieldToEventLoop\(0\)/,
  "one JSPI activation must drain successive bounded transport slices cooperatively");
assert.match(clientNetwork,
  /markInboundPumpActivationStarted\(\)[\s\S]*markInboundPumpActivationFinished\(\)/,
  "the browser scheduler must retain ownership while the asynchronous Java activation runs");
assert.match(packetScheduler,
  /CLIENT_PACKET_DRAIN_TARGET_QUEUE =\s*CLIENT_PACKET_DRAIN_THRESHOLD - 1/,
  "client pressure recovery no longer targets the exact queue depth 63");
assert.match(packetScheduler, /CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS = 256/,
  "client pressure recovery lost its bounded clock-failure ceiling");
assert.match(packetScheduler,
  /clientPacketDrainBatchTargetPackets = Math\.min\([\s\S]*CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS,[\s\S]*clientPacketDrainRequestedPackets/,
  "cheap packet pressure is still capped below the available two-millisecond budget");
assert.match(clientNetwork,
  /stopReason: drainStopReason,[\s\S]*targetQueue: target,[\s\S]*requestedPackets: requested,[\s\S]*batchTargetPackets: batchTarget,[\s\S]*remainingDebt: debt/,
  "bounded frame-boundary evidence lost drain-debt attribution");
const rawTransportMethodSource = clientNetwork.slice(
  clientNetwork.indexOf("private static boolean pumpInbound()"),
  clientNetwork.indexOf("public static void beginClientPacketFrame()"),
);
assert.ok(rawTransportMethodSource.length > 0,
  "the bounded raw-transport callback could not be extracted");
assert.doesNotMatch(rawTransportMethodSource, /processQueuedPackets/,
  "the raw transport macrotask synchronously entered the PLAY FIFO drain");
const clientPacketBoundaryMethodSource = clientNetwork.slice(
  clientNetwork.indexOf("public static void processClientPacketsAtScheduledFrameBoundary("),
  clientNetwork.indexOf('@JSBody(params = "callback"'),
);
assert.match(clientPacketBoundaryMethodSource,
  /boolean drainEnabled = isClientPacketFrameBoundaryDrainEnabled\(\)[\s\S]*!accountingValid \|\| queueBefore < 64 \|\| !drainEnabled/,
  "below-threshold PLAY work no longer preserves the one vanilla scheduled call");
assert.match(clientPacketBoundaryMethodSource,
  /tryBeginClientPacketDrain\(packetProcessor, pausedBefore\)[\s\S]*packetProcessor\.processQueuedPackets\(\)[\s\S]*finally[\s\S]*finishClientPacketDrain\(packetProcessor\)/,
  "pressure PLAY work is not owner-claimed/released at the scheduled runTick boundary");
assert.match(clientPacketBoundaryMethodSource,
  /boolean vanillaFallback[\s\S]*"threshold-race"\.equals\(claimSkipReason\)[\s\S]*"claim-race"\.equals\(claimSkipReason\)[\s\S]*if \(vanillaFallback\) \{[\s\S]*packetProcessor\.processQueuedPackets\(\);[\s\S]*\}/,
  "transient claim races must make one bounded ordinary FIFO pass");
assert.doesNotMatch(clientPacketBoundaryMethodSource,
  /vanillaFallback[\s\S]*"active-drain"|vanillaFallback[\s\S]*"handler-depth"/,
  "active-drain/handler-depth must not recurse through the PacketProcessor");
assert.equal(
  (clientPacketBoundaryMethodSource.match(/packetProcessor\.processQueuedPackets\(\)/g) || [])
    .length,
  3,
  "the scheduled wrapper must contain one call per vanilla, safe-race, and claimed branch",
);
assert.doesNotMatch(clientNetwork,
  /\bclientPacketDrainCallback\s*\(|\bscheduleClientPacketDrain\s*\(/,
  "a retired external client PLAY callback/scheduler remains reachable");

const clientNetworkInstallScript = jsBodyBeforeIn(
  clientNetwork,
  "private static native boolean installInboundPump(",
);
const inboundPumpHandlerSource = clientNetworkInstallScript.slice(
  clientNetworkInstallScript.indexOf("bridge.inboundPump = function(reason)"),
  clientNetworkInstallScript.indexOf("return true;",
    clientNetworkInstallScript.indexOf("bridge.inboundPump = function(reason)")),
);
assert.match(inboundPumpHandlerSource, /schedulePump\(String\(reason \|\| 'requested'\)\)/,
  "WebSocket delivery does not coalesce into the independent scheduler");
assert.doesNotMatch(inboundPumpHandlerSource, /callback\(\)/,
  "a WebSocket callback synchronously entered Java packet decoding");
const clientPacketDrainHandlerSource = clientNetworkInstallScript.slice(
  clientNetworkInstallScript.indexOf("bridge.clientPacketDrain = function()"),
  clientNetworkInstallScript.indexOf("bridge.invalidateClientPacketDrain = function(reason)"),
);
assert.match(clientPacketDrainHandlerSource,
  /const demand = clientPacketQueueDepth\(\) >= 64[\s\S]*if \(bridge\.clientPacketDrainDemand\) return false[\s\S]*clientPacketDrainDemandSignals\+\+/,
  "decoded PLAY queue pressure is not retained as one false-to-true demand edge");
assert.doesNotMatch(clientPacketDrainHandlerSource,
  /callback\(|MessageChannel|setTimeout|watchdog|postMessage|schedule[A-Z]/,
  "decoded-packet accounting can schedule or enter a PLAY handler outside runTick");
assert.match(clientPatcher,
  /browserPackets\.add\(beginClientPacketFrame\);[\s\S]*browserPackets\.add\(installClientNetwork\);[\s\S]*browserPackets\.add\(pumpClientChannels\)/,
  "Minecraft.runTick entry is not frame-accounting -> install -> raw transport pump");
assert.match(clientPatcher,
  /scheduledPacketBoundaries != 1[\s\S]*method\.instructions\.set\(scheduledPacketBoundary, frameBoundaryWrapper\)[\s\S]*wrapperCalls != 1 \|\| directPacketProcessorCalls != 0/,
  "Minecraft.runTick no longer replaces its one vanilla PacketProcessor call in place");

const bridgeScript = jsBodyBefore("private static native void initBridge();");
const outboundSchedulerScript = jsBodyBefore(
  "private static native void initOutboundScheduler();",
);
const schedulerScript = jsBodyBefore("private static native void initInboundScheduler();");
// Exercise the real source-level scheduleSlices implementation in a tiny deterministic VM.
// The full bridge workload below is intentionally timing-realistic and may legitimately finish
// every turn at the 256-slice high watermark before it needs a non-immediate continuation.  This
// model makes the MessageChannel branch and its generation guard deterministic without changing
// the production scheduler or relaxing any workload assertion.
const inboundContinuationSource = schedulerScript.slice(
  schedulerScript.indexOf("const maximumInboundContinuationStat"),
  schedulerScript.indexOf("function pumpSlices(entry)"),
);
assert.ok(inboundContinuationSource.length > 0,
  "inbound continuation source could not be isolated for the deterministic model");
const inboundContinuationContext = {
  Date,
  Math,
  MessageChannel,
  Number,
  performance: {now: () => 0},
  setTimeout,
  clearTimeout,
};
inboundContinuationContext.globalThis = inboundContinuationContext;
vm.runInNewContext(`
  const state = {exactPacketQueuePaused: false, stats: {
    inboundMessageChannelSchedules: 0,
    inboundMessageChannelCallbacks: 0,
    inboundContinuationMacrotasks: 0,
    inboundMessageChannelFailures: 0,
    inboundContinuationStaleCallbacks: 0,
    inboundSliceScheduleWaitSamples: 0,
    maxInboundSliceScheduleWaitMillis: 0,
    inboundImmediateSchedules: 0,
    inboundRafSchedules: 0,
    inboundTimerSchedules: 0,
    testPumps: 0,
  }};
  function now() { return performance.now(); }
  function workDepth(entry) { return entry.depth || 0; }
  function pumpSlices() { state.stats.testPumps++; }
  const decodedSliceHighWatermark = 256;
  const decodedSliceLowWatermark = 64;
  const inboundResumeBytes = 8 * 1024 * 1024;
  ${inboundContinuationSource}
  globalThis.__gaiusContinuationState = state;
  globalThis.__gaiusContinuationSchedule = scheduleSlices;
`, inboundContinuationContext);
const inboundContinuationEntry = {
  disposed: false,
  inboundSliceScheduled: false,
  pendingInboundHead: 0,
  pendingInbound: [{bytes: new Uint8Array([1]), offset: 0}],
  depth: 0,
  inboundSliceHandle: null,
  inboundSliceUsesRaf: false,
  inboundSliceScheduledAt: 0,
  inboundSliceSchedulerKind: "",
  inboundSliceGeneration: 0,
  inboundSliceMessageChannel: null,
  inboundSliceMessageCallback: null,
};
inboundContinuationContext.__gaiusContinuationSchedule(inboundContinuationEntry, false);
const inboundContinuationStats = inboundContinuationContext.__gaiusContinuationState.stats;
assert.equal(inboundContinuationStats.inboundMessageChannelSchedules, 1,
  "deterministic inbound continuation did not schedule one MessageChannel task");
assert.equal(inboundContinuationStats.inboundContinuationMacrotasks, 1,
  "deterministic inbound continuation lost macrotask accounting");
// MessageChannel callbacks and timer callbacks are separate macrotask queues in Node. A single
// zero-delay timer can run before the posted port message on a busy CI worker, which would turn a
// healthy scheduler into a flaky smoke failure. Wait for the one expected callback with a bounded
// deadline instead of changing the production scheduler or hiding a missing callback.
await new Promise((resolve, reject) => {
  const deadline = Date.now() + 1000;
  const waitForCallback = () => {
    if (inboundContinuationStats.inboundMessageChannelCallbacks >= 1) {
      resolve();
      return;
    }
    if (Date.now() >= deadline) {
      reject(new Error("deterministic inbound continuation callback timed out"));
      return;
    }
    setTimeout(waitForCallback, 1);
  };
  waitForCallback();
});
assert.equal(inboundContinuationStats.inboundMessageChannelCallbacks, 1,
  "deterministic inbound continuation callback did not run exactly once");
assert.equal(inboundContinuationStats.testPumps, 1,
  "deterministic inbound continuation did not invoke its pump exactly once");
const inboundContinuationHandler =
  inboundContinuationEntry.inboundSliceMessageChannel.port1.onmessage;
const inboundContinuationCallbacksBeforeStale =
  inboundContinuationStats.inboundMessageChannelCallbacks;
const inboundContinuationStaleBefore =
  inboundContinuationStats.inboundContinuationStaleCallbacks;
inboundContinuationHandler({data: inboundContinuationEntry.inboundSliceGeneration + 1});
assert.equal(inboundContinuationStats.testPumps, 1,
  "stale inbound continuation re-entered the deterministic pump");
assert.equal(inboundContinuationStats.inboundContinuationStaleCallbacks,
  inboundContinuationStaleBefore + 1,
  "stale inbound continuation generation was not rejected");
const inboundContinuationPostedCallbacks =
  inboundContinuationCallbacksBeforeStale;
const inboundContinuationStaleDispatches =
  inboundContinuationStats.inboundContinuationStaleCallbacks -
  inboundContinuationStaleBefore;
inboundContinuationEntry.inboundSliceMessageChannel.port1.close();
inboundContinuationEntry.inboundSliceMessageChannel.port2.close();
// TeaVM's @JSBody parser accepts explicit object keys here but rejects ES6
// property shorthand even though Node's runtime parser accepts it. Keep this
// regression gate next to the bounded high-watermark event schema.
assert.match(schedulerScript, /sequence: sequence,/);
assert.match(schedulerScript, /startedAtMillis: startedAtMillis,/);
assert.match(schedulerScript, /endedAtMillis: endedAtMillis,/);
assert.doesNotMatch(schedulerScript,
  /^\s*(?:sequence|startedAtMillis|endedAtMillis),\s*$/m);
const highWatermarkSource = schedulerScript.slice(
  schedulerScript.indexOf("function refreshHighWatermark()"),
  schedulerScript.indexOf("function hasActiveChannels()"),
);
assert.ok(highWatermarkSource.length > 0,
  "high-watermark accounting function was not extracted");
assert.match(highWatermarkSource, /activeHighWatermarkStartCount/,
  "high-watermark accounting lost its active-episode count");
assert.match(highWatermarkSource, /activeHighWatermarkStartSumMillis/,
  "high-watermark accounting lost its start-time accumulator");
assert.match(highWatermarkSource, /sampledAt \* activeCount - startSum/,
  "active high-watermark duration is not calculated from O(1) accumulators");
assert.doesNotMatch(highWatermarkSource, /state\.channels\.forEach/,
  "high-watermark refresh reverted to an O(N) channel scan");
assert.match(schedulerScript,
  /state\.activeHighWatermarkStartCount = Math\.min\([\s\S]*?\+ 1\s*\)/,
  "high-watermark start does not update the active-episode count");
assert.match(schedulerScript,
  /state\.activeHighWatermarkStartCount = Math\.max\([\s\S]*?- 1\s*\)/,
  "high-watermark finish does not update the active-episode count");
// Keep the algebra explicit: two concurrent episodes started at 100 and 130 have 170 ms of
// summed active duration at t=200, without needing to inspect either channel object.
const activeHighWatermarkMillis = (starts, sampledAt) =>
  Math.max(0, sampledAt * starts.length - starts.reduce((sum, start) => sum + start, 0));
assert.equal(activeHighWatermarkMillis([100, 130], 200), 170,
  "incremental high-watermark duration model is inconsistent");
assert.match(bridgeScript, /activeDecoderOwnerAmbiguous: false/,
  "decoder accounting lost its fail-closed owner ambiguity state");
assert.match(bridgeScript, /activeDecoderScopeIds: \[\]/,
  "decoder accounting lost its nested-scope owner stack");
assert.match(bridgeScript, /activeDecoderScopeBytes: \[\]/,
  "decoder accounting lost its nested-scope byte stack");
assert.match(schedulerScript, /function syncDecoderScopeOwner\(\)[\s\S]*?function pushDecoderScope\(/,
  "decoder scope owner state has no bounded synchronization helper");
assert.match(schedulerScript, /function finishDecoderScope\(entryId\)[\s\S]*?function discardDecoderScopes\(/,
  "decoder scope owner state has no finish/discard lifecycle helpers");
const decodedSliceOwnershipSource = schedulerScript.slice(
  schedulerScript.indexOf("state.recordDecodedSliceScheduled = function("),
  schedulerScript.indexOf("state.recordDecodedPacketQueueScheduled = function("),
);
assert.ok(decodedSliceOwnershipSource.length > 0,
  "decoded slice ownership function was not extracted");
assert.match(decodedSliceOwnershipSource,
  /activeDecoderScopeDepth > 0[\s\S]*activeDecoderOwnerAmbiguous = true/,
  "re-entrant decoder owners are not marked ambiguous");
assert.match(schedulerScript,
  /state\.recordInlineDecodedPacketScheduled = function\(\) \{\s*if \(state\.activeDecoderOwnerAmbiguous\) return;/,
  "inline packet accounting can release cumulation for an ambiguous owner");
const decodedQueueAccountingSource = schedulerScript.slice(
  schedulerScript.indexOf("state.recordDecodedPacketQueueScheduled = function("),
  schedulerScript.indexOf("state.recordInlineDecodedPacketScheduled = function("),
);
assert.ok(decodedQueueAccountingSource.length > 0,
  "decoded queue accounting function was not extracted");
assert.match(decodedQueueAccountingSource,
  /const exactQueuePauseChanged =\s*wasPaused !== state\.exactPacketQueuePaused;/,
  "decoded queue accounting lost its exact-pause transition edge");
assert.match(decodedQueueAccountingSource,
  /if \(exactQueuePauseChanged\) \{\s*state\.channels\.forEach\(function\(entry\)/,
  "exact queue transitions no longer fan out flow-control state to every channel");
assert.match(decodedQueueAccountingSource,
  /\} else if \(!processed && queueDepth > 0\) \{[\s\S]*?\n\s*const activeEntry = !state\.activeDecoderOwnerAmbiguous &&\s*state\.activeDecoderEntryId[\s\S]*?state\.channels\.get\(state\.activeDecoderEntryId\|0\)/,
  "stable queue accounting lost the active-decoder fast path");
assert.match(decodedQueueAccountingSource,
  /\} else if \(!processed && queueDepth > 0\) \{[\s\S]*?\n\s*const activeEntry = !state\.activeDecoderOwnerAmbiguous &&\s*state\.activeDecoderEntryId[\s\S]*?else \{[\s\S]*?state\.channels\.forEach\(function\(entry\)/,
  "missing or stale decoder owner does not fail closed to a channel fan-out");
assert.equal(
  (decodedQueueAccountingSource.match(/state\.channels\.forEach\(function\(entry\)/g) || []).length,
  2,
  "decoded queue updates must retain only edge fan-out plus fail-closed owner fallback",
);
// The source gate above protects the generated JS shape; this model keeps the intended
// fan-out semantics explicit: only an exact pause edge is global, while a queued packet's
// retained decoder cumulation needs one active-entry recheck. Missing/stale owners fall back to
// the old all-channel behavior because the Java queue callback does not carry a channel ID.
const decodedQueueFanout = ({pauseChanged, processed, activeDecoder, ownerAmbiguous}) => {
  if (pauseChanged || ownerAmbiguous) return "all";
  if (processed) return "none";
  return activeDecoder === "A" || activeDecoder === "B" ? "active" : "all";
};
assert.equal(decodedQueueFanout({pauseChanged: true, processed: false, activeDecoder: "A"}), "all");
assert.equal(decodedQueueFanout({pauseChanged: true, processed: true, activeDecoder: "missing"}), "all");
assert.equal(decodedQueueFanout({pauseChanged: false, processed: false, activeDecoder: "A"}), "active");
assert.equal(decodedQueueFanout({pauseChanged: false, processed: false, activeDecoder: "missing"}), "all");
assert.equal(decodedQueueFanout({pauseChanged: false, processed: false, activeDecoder: "stale"}), "all");
assert.equal(decodedQueueFanout({
  pauseChanged: false,
  processed: false,
  activeDecoder: "B",
  ownerAmbiguous: true,
}), "all");
assert.equal(decodedQueueFanout({pauseChanged: false, processed: true, activeDecoder: "A"}), "none");

// Two-channel interleaving model: an owner is O(1) only while its decoder scope is live. A stale
// or missing global active ID must conservatively revisit both channels so no pending input is
// stranded by the owner-less Java callback.
function modelInterleavedQueueFanout(events) {
  const scheduled = new Set();
  for (const event of events) {
    const fanout = decodedQueueFanout(event);
    if (fanout === "all") {
      for (const channel of ["A", "B"]) scheduled.add(channel);
    } else if (fanout === "active") {
      scheduled.add(event.activeDecoder);
    }
  }
  return [...scheduled].sort();
}
assert.deepEqual(modelInterleavedQueueFanout([
  {pauseChanged: false, processed: false, activeDecoder: "A"},
  {pauseChanged: false, processed: false, activeDecoder: "B"},
]), ["A", "B"]);
assert.deepEqual(modelInterleavedQueueFanout([
  {pauseChanged: false, processed: false, activeDecoder: "missing"},
]), ["A", "B"]);
assert.deepEqual(modelInterleavedQueueFanout([
  {pauseChanged: false, processed: false, activeDecoder: "stale"},
]), ["A", "B"]);
assert.deepEqual(modelInterleavedQueueFanout([
  {pauseChanged: false, processed: false, activeDecoder: "B", ownerAmbiguous: true},
]), ["A", "B"]);
function modelDecoderScopeLifecycle(actions) {
  const scopes = [];
  let ownerAmbiguous = false;
  for (const action of actions) {
    if (action.type === "begin") {
      if (scopes.length > 0 && scopes[scopes.length - 1] !== action.owner) {
        ownerAmbiguous = true;
      }
      scopes.push(action.owner);
    } else if (action.type === "finish") {
      let index = scopes.length - 1;
      if (index >= 0 && scopes[index] === action.owner) {
        scopes.pop();
      } else {
        ownerAmbiguous = true;
        index = scopes.lastIndexOf(action.owner);
        if (index >= 0) scopes.splice(index, 1);
      }
    } else if (action.type === "discard") {
      for (let index = scopes.length - 1; index >= 0; index--) {
        if (scopes[index] === action.owner) scopes.splice(index, 1);
      }
    }
    if (scopes.length === 0) ownerAmbiguous = false;
  }
  return {
    active: scopes.length > 0 ? scopes[scopes.length - 1] : 0,
    depth: scopes.length,
    ownerAmbiguous,
  };
}
assert.deepEqual(modelDecoderScopeLifecycle([
  {type: "begin", owner: "A"},
  {type: "begin", owner: "B"},
  {type: "finish", owner: "B"},
  {type: "finish", owner: "A"},
]), {active: 0, depth: 0, ownerAmbiguous: false});
assert.deepEqual(modelDecoderScopeLifecycle([
  {type: "begin", owner: "A"},
  {type: "begin", owner: "B"},
  {type: "finish", owner: "B"},
  {type: "discard", owner: "A"},
]), {active: 0, depth: 0, ownerAmbiguous: false});
assert.deepEqual(modelDecoderScopeLifecycle([
  {type: "begin", owner: "A"},
  {type: "begin", owner: "B"},
  {type: "finish", owner: "A"},
  {type: "finish", owner: "B"},
]), {active: 0, depth: 0, ownerAmbiguous: false});
assert.deepEqual(modelDecoderScopeLifecycle([
  {type: "begin", owner: "A"},
  {type: "begin", owner: "B"},
  {type: "discard", owner: "A"},
  {type: "finish", owner: "B"},
]), {active: 0, depth: 0, ownerAmbiguous: false});
assert.deepEqual(modelDecoderScopeLifecycle([
  {type: "begin", owner: "A"},
  {type: "begin", owner: "B"},
  {type: "finish", owner: "B"},
  {type: "discard", owner: "A"},
  {type: "begin", owner: "C"},
]), {active: "C", depth: 1, ownerAmbiguous: false});
assert.deepEqual(modelDecoderScopeLifecycle([
  {type: "begin", owner: "A"},
  {type: "begin", owner: "A"},
  {type: "finish", owner: "A"},
  {type: "finish", owner: "A"},
]), {active: 0, depth: 0, ownerAmbiguous: false});
const flowControlSource = schedulerScript.slice(
  schedulerScript.indexOf("function applyFlowControl(entry)"),
  schedulerScript.indexOf("function compactPending(entry)"),
);
assert.ok(flowControlSource.length > 0, "inbound flow-control function was not extracted");
assert.doesNotMatch(flowControlSource, /decoderCumulationBytes/,
  "partial packet cumulation must not self-deadlock the paused TCP source");
const scheduleSlicesSource = schedulerScript.slice(
  schedulerScript.indexOf("function scheduleSlices(entry, immediate)"),
  schedulerScript.indexOf("function pumpSlices(entry)"),
);
const pumpSlicesSource = schedulerScript.slice(
  schedulerScript.indexOf("function pumpSlices(entry)"),
  schedulerScript.indexOf("function releaseDecoderCumulation(entry"),
);
assert.match(schedulerScript,
  /function shouldBlockInboundSliceAdmission\(entry\)[\s\S]*state\.exactPacketQueuePaused \|\|[\s\S]*workDepth\(entry\) > decodedSliceLowWatermark/,
  "paused admission predicate does not distinguish exact/depth pauses from byte-only pressure");
assert.match(scheduleSlicesSource,
  /shouldBlockInboundSliceAdmission\(entry\)/,
  "paused slice admission can refill the 256-slice high watermark before low-water drain");
assert.match(pumpSlicesSource,
  /applyFlowControl\(entry\);\s*if \(shouldBlockInboundSliceAdmission\(entry\)\) return;[\s\S]*while \(/,
  "an already-scheduled slice callback can refill a paused high-watermark episode");
assert.match(scheduleSlicesSource,
  /immediate && typeof queueMicrotask === 'function'[\s\S]*queueMicrotask\(function\(\) \{ run\(generation\); \}\)/,
  "initial inbound delivery no longer keeps its microtask fast path");
assert.match(scheduleSlicesSource,
  /postInboundSliceMessage\(entry, generation, run\)[\s\S]*entry\.inboundSliceSchedulerKind = 'message-channel'/,
  "bounded inbound continuation is not using the MessageChannel path");
assert.match(scheduleSlicesSource,
  /requestAnimationFrame[\s\S]*incrementInboundContinuationStat\('inboundContinuationMacrotasks'\)/,
  "requestAnimationFrame fallback is missing generic macrotask accounting");
assert.match(scheduleSlicesSource,
  /setTimeout\(function\(\) \{[\s\S]*?run\(generation\);[\s\S]*?\}, 0\)[\s\S]*incrementInboundContinuationStat\('inboundContinuationMacrotasks'\)/,
  "timer fallback is missing generic macrotask accounting");
assert.match(scheduleSlicesSource,
  /const generationMatches = entry\.inboundSliceGeneration === generation[\s\S]*const deliveredGenerationMatches = Number\(deliveredGeneration\) === generation[\s\S]*!generationMatches[\s\S]*!deliveredGenerationMatches/,
  "inbound continuation callbacks lost their per-entry generation guard");
assert.match(scheduleSlicesSource,
  /!deliveredGenerationMatches[\s\S]*closeInboundSliceMessageChannel\(entry\)[\s\S]*entry\.inboundSliceGeneration = nextInboundSliceGeneration[\s\S]*scheduleSlices\(entry, false\)/,
  "active inbound generation mismatch does not fail closed and re-arm pending input");
assert.match(source,
  /state\.discardInbound = function\(entry\) \{[\s\S]*entry\.inboundSliceGeneration = nextInboundSliceGeneration\([\s\S]*closeInboundSliceMessageChannel\(entry\)/,
  "discardInbound does not invalidate and close a queued inbound continuation");
assert.match(source, /public static void recordInlineDecodedPacket\(\)/);
assert.match(clientPatcher,
  /BrowserWebSocketChannel"[\s\S]{0,160}"recordInlineDecodedPacket"[\s\S]{0,80}"\(\)V"/,
  "PacketUtils browser-inline branch does not retire completed decoder packets");
assert.match(clientPatcher,
  /PacketUtils inline decoder accounting CFG changed: hooks=/,
  "PacketUtils inline decoded-packet hook is not verified fail-closed");

// Deterministic Java-pump model for the multiplayer startup burst. The chunk cap is the first
// bound, but the 256 KiB and 2 ms budgets remain independent early exits.
function modelInboundPumpTurns(sliceCount, sliceBytes, perSliceMillis) {
  let remaining = sliceCount;
  const turns = [];
  while (remaining > 0) {
    let chunks = 0;
    let bytes = 0;
    let elapsed = 0;
    while (chunks < 64 && bytes < 256 * 1024) {
      if (chunks > 0 && elapsed >= 2) break;
      if (remaining <= 0) break;
      chunks++;
      bytes += sliceBytes;
      elapsed += perSliceMillis;
      remaining--;
    }
    turns.push({chunks, bytes, elapsed});
  }
  return turns;
}
const startupBurstModel = modelInboundPumpTurns(256, 4096, 0.01);
assert.deepEqual(startupBurstModel.map((turn) => turn.chunks), [64, 64, 64, 64],
  "256 tiny startup slices did not use four bounded turns");
assert.ok(startupBurstModel.every((turn) =>
  turn.chunks <= 64 && turn.bytes <= 256 * 1024 && turn.elapsed < 2),
"startup burst model bypassed a Java inbound pump budget");

function modelExactPacketQueueRecovery(depth, packetMillis) {
  const requestedPackets = Math.max(1, depth - 63);
  const batchTargetPackets = Math.min(256, requestedPackets);
  let packetsProcessed = 0;
  let elapsedMillis = 0;
  while (packetsProcessed < batchTargetPackets && depth > 0) {
    if (packetsProcessed > 0 && elapsedMillis >= 2) break;
    packetsProcessed++;
    elapsedMillis += packetMillis;
    depth--;
  }
  return {
    queueAfter: depth,
    packetsProcessed,
    elapsedMillis,
    remainingDebt: Math.max(0, depth - 63),
  };
}
const cheapExactQueueRecovery = modelExactPacketQueueRecovery(256, 0.01);
assert.equal(cheapExactQueueRecovery.packetsProcessed, 193);
assert.equal(cheapExactQueueRecovery.queueAfter, 63);
assert.equal(cheapExactQueueRecovery.remainingDebt, 0);
assert.ok(cheapExactQueueRecovery.elapsedMillis < 2 &&
  cheapExactQueueRecovery.elapsedMillis < 500,
"cheap exact-queue recovery did not clear in one bounded frame");
const timedExactQueueRecovery = modelExactPacketQueueRecovery(256, 0.25);
assert.equal(timedExactQueueRecovery.packetsProcessed, 8);
assert.ok(timedExactQueueRecovery.elapsedMillis >= 2 &&
  timedExactQueueRecovery.elapsedMillis < 50,
"expensive exact-queue recovery bypassed the two-millisecond boundary gate");

function modelPausedAdmissionDrain(depth, lowWatermark, chunksPerTurn) {
  const depths = [];
  while (depth > lowWatermark) {
    depth = Math.max(lowWatermark, depth - chunksPerTurn);
    depths.push(depth);
  }
  return depths;
}
const pausedAdmissionDrain = modelPausedAdmissionDrain(256, 64, 64);
assert.deepEqual(pausedAdmissionDrain,
  [192, 128, 64],
  "paused 256-slice episode did not drain monotonically to the exact low watermark");
assert.ok(pausedAdmissionDrain.every((depth, index) =>
  index === 0 || depth < pausedAdmissionDrain[index - 1]),
"paused admission model refilled an active high-watermark episode");
function modelAdmissionBlocked({paused, exactPacketQueuePaused, depth}) {
  return paused && (exactPacketQueuePaused || depth > 64);
}
assert.equal(modelAdmissionBlocked({paused: true, exactPacketQueuePaused: false, depth: 65}), true,
  "slice-depth pause admitted before the low watermark");
assert.equal(modelAdmissionBlocked({paused: true, exactPacketQueuePaused: false, depth: 64}), false,
  "byte-only pause self-deadlocked pending bytes at the low watermark");
assert.equal(modelAdmissionBlocked({paused: true, exactPacketQueuePaused: true, depth: 0}), true,
  "exact decoded-packet pause admitted more decoder work");

function modelInboundContinuationFairness(entryCount, continuationsPerEntry) {
  const entries = Array.from({length: entryCount}, (_, id) => ({
    id,
    remaining: continuationsPerEntry,
    scheduled: false,
    callbacks: 0,
  }));
  const ready = [];
  const order = [];
  const post = (entry) => {
    if (!entry.scheduled && entry.remaining > 0) {
      entry.scheduled = true;
      ready.push(entry);
    }
  };
  entries.forEach(post);
  const maximumDispatches = entryCount * continuationsPerEntry;
  while (ready.length > 0) {
    const entry = ready.shift();
    entry.scheduled = false;
    if (entry.remaining <= 0) continue;
    entry.remaining--;
    entry.callbacks++;
    order.push(entry.id);
    post(entry);
    assert.ok(order.length <= maximumDispatches,
      "inbound continuation fairness model exceeded its bounded dispatch count");
  }
  const lastAt = new Map();
  let maxServiceGap = 0;
  order.forEach((id, index) => {
    if (lastAt.has(id)) {
      maxServiceGap = Math.max(maxServiceGap, index - lastAt.get(id) - 1);
    }
    lastAt.set(id, index);
  });
  return {
    entryCount,
    continuationsPerEntry,
    order,
    callbacks: entries.map((entry) => entry.callbacks),
    maxServiceGap,
  };
}
const inboundContinuationFairnessModel =
  modelInboundContinuationFairness(16, 3);
assert.deepEqual(inboundContinuationFairnessModel.order.slice(0, 16),
  Array.from({length: 16}, (_, id) => id),
  "inbound continuation fairness model did not service each entry once per round");
assert.deepEqual(inboundContinuationFairnessModel.callbacks,
  Array.from({length: 16}, () => 3),
  "inbound continuation fairness model lost a callback");
assert.ok(inboundContinuationFairnessModel.maxServiceGap <= 15,
  "one inbound entry exceeded one fair 16-entry rotation");

function modelDiscardBeforeDelivery() {
  const nextGeneration = (value) => {
    let generation = ((value + 1) >>> 0);
    if (generation === 0) generation = 1;
    return generation;
  };
  const createEntry = (id) => ({
    id,
    generation: 0,
    scheduled: false,
    disposed: false,
    callback: null,
    staleCallbacks: 0,
    pumps: 0,
  });
  const schedule = (entry) => {
    const generation = entry.generation = nextGeneration(entry.generation);
    entry.scheduled = true;
    const callback = (deliveredGeneration) => {
      if (entry.disposed || !entry.scheduled ||
          entry.generation !== generation || deliveredGeneration !== generation) {
        entry.staleCallbacks++;
        return;
      }
      entry.scheduled = false;
      entry.callback = null;
      entry.pumps++;
    };
    entry.callback = callback;
    return {generation, callback};
  };
  const retired = createEntry(7);
  const retiredTask = schedule(retired);
  retired.generation = nextGeneration(retired.generation);
  retired.scheduled = false;
  retired.callback = null;
  retired.disposed = true;
  retiredTask.callback(retiredTask.generation);

  const fresh = createEntry(7);
  const freshTask = schedule(fresh);
  retiredTask.callback(retiredTask.generation);
  freshTask.callback(freshTask.generation);
  return {
    retiredStaleCallbacks: retired.staleCallbacks,
    retiredPumps: retired.pumps,
    freshStaleCallbacks: fresh.staleCallbacks,
    freshPumps: fresh.pumps,
  };
}
const inboundDiscardRaceModel = modelDiscardBeforeDelivery();
assert.equal(inboundDiscardRaceModel.retiredPumps, 0,
  "discarded inbound entry pumped after its queued callback was delivered");
assert.ok(inboundDiscardRaceModel.retiredStaleCallbacks >= 1,
  "discarded inbound entry did not record its stale callback");
assert.equal(inboundDiscardRaceModel.freshPumps, 1,
  "fresh entry with the same socket id was affected by the retired callback");
assert.equal(inboundDiscardRaceModel.freshStaleCallbacks, 0,
  "fresh inbound entry rejected its own current generation");

function modelActiveGenerationMismatchRearm() {
  let generation = 1;
  let scheduled = true;
  let rearmed = false;
  let pumps = 0;
  const expectedGeneration = generation;
  const callback = (deliveredGeneration) => {
    if (generation !== expectedGeneration || deliveredGeneration !== expectedGeneration) {
      if (scheduled && generation === expectedGeneration &&
          deliveredGeneration !== expectedGeneration) {
        scheduled = false;
        generation = (generation + 1) >>> 0;
        scheduled = true;
        rearmed = true;
      }
      return;
    }
    scheduled = false;
    pumps++;
  };
  callback(expectedGeneration + 1);
  return {generation, scheduled, rearmed, pumps};
}
const inboundActiveMismatchModel = modelActiveGenerationMismatchRearm();
assert.ok(inboundActiveMismatchModel.rearmed &&
  inboundActiveMismatchModel.scheduled,
  "active inbound generation mismatch left the continuation disarmed");
assert.equal(inboundActiveMismatchModel.pumps, 0,
  "active inbound generation mismatch entered the pump before rearming");

function createIndependentPumpHarness({
  readySlices,
  exactPacketQueuePaused = false,
  MessageChannelImpl = MessageChannel,
}) {
  const bridge = {
    stats: {},
    exactPacketQueuePaused,
  };
  const harnessContext = {
    Date,
    JSON,
    Math,
    MessageChannel: MessageChannelImpl,
    Number,
    String,
    clearTimeout,
    performance,
    setTimeout,
  };
  harnessContext.__gaiusNettyBridge = bridge;
  harnessContext.__gaiusNetworkStats = bridge.stats;
  const install = vm.runInNewContext(
    `(function(callback) {${clientNetworkInstallScript}})`,
    harnessContext,
  );
  let remaining = readySlices;
  const turns = [];
  const callback = () => {
    const processed = Math.min(64, remaining);
    remaining -= processed;
    turns.push(processed);
    return processed > 0 && remaining > 0;
  };
  assert.equal(install(callback), true,
    "independent browser transport pump did not install");
  return {
    bridge,
    context: harnessContext,
    remaining: () => remaining,
    turns,
  };
}

async function waitForIndependentPump(predicate, label, timeoutMillis = 2000) {
  const deadline = Date.now() + timeoutMillis;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
}

const independentPump = createIndependentPumpHarness({readySlices: 257});
for (let index = 0; index < 1000; index++) independentPump.bridge.inboundPump();
await waitForIndependentPump(
  () => independentPump.remaining() === 0 &&
    !independentPump.bridge.inboundPumpScheduler.pending,
  "257-slice independent pump drain",
);
assert.deepEqual(independentPump.turns, [64, 64, 64, 64, 1],
  "independent dispatcher did not preserve one bounded 64-slice batch per macrotask");
assert.equal(independentPump.bridge.stats.inboundPumpScheduled, 5,
  "progress-aware dispatcher did not self-reschedule exactly once per remaining batch");
assert.equal(independentPump.bridge.stats.inboundPumpCallbacks, 5);
assert.equal(independentPump.bridge.stats.inboundPumpRescheduled, 4);
assert.equal(independentPump.bridge.stats.inboundPumpCoalesced, 999,
  "bursty WebSocket signals were not coalesced behind one pending task");
assert.equal(independentPump.bridge.stats.inboundPumpJavaFailures, 0);
independentPump.bridge.inboundPumpScheduler.channel?.port1?.close?.();
independentPump.bridge.inboundPumpScheduler.channel?.port2?.close?.();

const exactPausedPump = createIndependentPumpHarness({
  readySlices: 32,
  exactPacketQueuePaused: true,
});
exactPausedPump.bridge.recordDecodedPacketQueue = function(depth, paused) {
  const wasPaused = this.exactPacketQueuePaused;
  this.stats.decodedPacketQueue = Math.max(0, Number(depth) || 0);
  this.exactPacketQueuePaused = !!paused;
  if (wasPaused && !this.exactPacketQueuePaused) {
    this.stats.decodedPacketQueueResumes =
      (this.stats.decodedPacketQueueResumes || 0) + 1;
    this.inboundPump();
  }
};
exactPausedPump.bridge.inboundPump();
await new Promise((resolve) => setTimeout(resolve, 10));
assert.equal(exactPausedPump.turns.length, 0,
  "exact packet pressure created a busy-looping transport task");
assert.equal(exactPausedPump.bridge.stats.inboundPumpBlockedByExactQueue, 1);
exactPausedPump.bridge.recordDecodedPacketQueue(64, false);
await waitForIndependentPump(() => exactPausedPump.remaining() === 0,
  "exact-queue resume wake");
assert.deepEqual(exactPausedPump.turns, [32],
  "exact-queue resume did not restart bounded raw transport progress");
assert.equal(exactPausedPump.bridge.stats.decodedPacketQueueResumes, 1,
  "exact-queue falling edge did not record exactly one resume wake");
const exactResumeScheduled = exactPausedPump.bridge.stats.inboundPumpScheduled;
exactPausedPump.bridge.recordDecodedPacketQueue(63, false);
assert.equal(exactPausedPump.bridge.stats.inboundPumpScheduled, exactResumeScheduled,
  "a non-edge decoded-queue update scheduled a duplicate transport wake");
exactPausedPump.bridge.inboundPumpScheduler.channel?.port1?.close?.();
exactPausedPump.bridge.inboundPumpScheduler.channel?.port2?.close?.();

let droppedMessageChannel;
class DroppedMessageChannel {
  constructor() {
    this.port1 = {onmessage: null, close() {}};
    this.port2 = {postMessage() {}, close() {}};
    droppedMessageChannel = this;
  }
}
const watchdogPump = createIndependentPumpHarness({
  readySlices: 1,
  MessageChannelImpl: DroppedMessageChannel,
});
watchdogPump.bridge.inboundPump();
await waitForIndependentPump(() => watchdogPump.remaining() === 0,
  "MessageChannel watchdog pump", 1000);
assert.equal(watchdogPump.bridge.stats.inboundPumpWatchdogCallbacks, 1,
  "a lost MessageChannel task was not rescued by the bounded watchdog");
assert.equal(watchdogPump.turns.length, 1,
  "watchdog and MessageChannel paths executed the Java callback more than once");
droppedMessageChannel.port1.onmessage({data: 1});
assert.equal(watchdogPump.turns.length, 1,
  "a late MessageChannel callback re-executed a completed pump generation");
assert.equal(watchdogPump.bridge.stats.inboundPumpStaleCallbacks, 1);

const sessionId = "5123456789abcdef0123456789abcdef";
const socketId = 91;
const frameBytes = 8 * 1024 * 1024;
const wireFrameBytes = 16 * 1024;
const decodedSliceBytes = 4 * 1024;
const flowControls = [];
const callbackDurations = [];

function delay(millis) {
  return new Promise((resolve) => setTimeout(resolve, millis));
}

async function waitFor(predicate, label, timeoutMillis = 5000) {
  const deadline = Date.now() + timeoutMillis;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
    await delay(1);
  }
}

const highWatermarkReasons = new Set([
  "inbound-slice-depth",
  "inbound-frame-depth",
  "inbound-bytes",
  "exact-packet-queue",
]);
function assertHighWatermarkEvent(event, label) {
  assert.ok(event && Number.isSafeInteger(event.sequence) && event.sequence > 0,
    `${label} sequence is not a positive monotonic integer`);
  assert.ok(Number.isInteger(event.channelId), `${label} channelId is not an integer`);
  assert.ok(highWatermarkReasons.has(event.reason), `${label} has an invalid reason`);
  for (const field of [
    "startedAtMillis",
    "endedAtMillis",
    "durationMillis",
    "startDepth",
    "endDepth",
    "startQueuedBytes",
    "endQueuedBytes",
    "startDecodedPacketQueue",
    "endDecodedPacketQueue",
    "startDecodedPacketDrainSignals",
    "endDecodedPacketDrainSignals",
    "startPumpCalls",
    "endPumpCalls",
    "startInboundSlicePumps",
    "endInboundSlicePumps",
    "startInboundPumpJavaCompleted",
    "endInboundPumpJavaCompleted",
  ]) {
    assert.ok(Number.isFinite(event[field]) && event[field] >= 0,
      `${label}.${field} is not a bounded non-negative number`);
  }
  assert.ok(event.endedAtMillis >= event.startedAtMillis,
    `${label} ended before it started`);
  assert.ok(Math.abs(
    event.durationMillis - (event.endedAtMillis - event.startedAtMillis),
  ) < 0.001, `${label} duration does not match its monotonic endpoints`);
  assert.equal(typeof event.startExactPacketQueuePaused, "boolean");
  assert.equal(typeof event.endExactPacketQueuePaused, "boolean");
}

function requestAnimationFrame(callback) {
  return setTimeout(() => {
    const startedAt = performance.now();
    callback(startedAt);
    callbackDurations.push(performance.now() - startedAt);
  }, 0);
}

const context = {
  AbortController,
  Array,
  ArrayBuffer,
  Boolean,
  Date,
  Int8Array,
  JSON,
  Map,
  MessageChannel,
  Math,
  Number,
  Object,
  Promise,
  RegExp,
  Set,
  String,
  TextDecoder,
  TextEncoder,
  URL,
  URLSearchParams,
  Uint8Array,
  clearTimeout,
  console,
  fetch: async () => {
    throw new Error("Local inbound smoke must not use a relay");
  },
  localStorage: {getItem: () => null},
  location: {
    href: "file:///Downloads/Gaius.html",
    hostname: "",
    protocol: "file:",
    search: "",
  },
  performance,
  queueMicrotask,
  cancelAnimationFrame: clearTimeout,
  requestAnimationFrame,
  setTimeout,
  WebSocket: class UnexpectedWebSocket {
    constructor() {
      throw new Error("Local inbound smoke must not open a WebSocket");
    }
  },
  __gaiusLocalServerPorts: new Map(),
};
context.globalThis = context;
context.window = context;
vm.runInNewContext(`(function() {${bridgeScript}\n})();`, context);
vm.runInNewContext(`(function() {${outboundSchedulerScript}\n})();`, context);
vm.runInNewContext(`(function() {${schedulerScript}\n})();`, context);

const bridge = context.__gaiusNettyBridge;
const stats = context.__gaiusNetworkStats;
const {port1, port2} = new MessageChannel();
const launchGeneration = "1";
port1.__gaiusLaunchGeneration = launchGeneration;
context.__gaiusSingleplayerWorkers = new Map([[sessionId, {
  __gaiusTerminal: false,
  __gaiusLaunchGeneration: launchGeneration,
  __gaiusClientPort: port1,
}]]);
port2.on("message", (message) => {
  if (message && message.type === "flow") flowControls.push(message);
});
context.__gaiusLocalServerPorts.set(sessionId, port1);
bridge.open(socketId, `client-${sessionId}.gaius-local`, 25565);

// A burst of tiny transport frames must remain flow-paused after the decoded-slice queue drains.
// Exercise the extracted bridge/scheduler functions directly so the frame high/low watermarks
// are checked against the actual pending+ready queue, rather than a model of the algorithm.
const backlogSessionId = "9123456789abcdef0123456789abcdef";
const backlogSocketId = 96;
const backlogFrameCount = 1100;
const backlogFlows = [];
const {port1: backlogPort1, port2: backlogPort2} = new MessageChannel();
backlogPort1.__gaiusLaunchGeneration = launchGeneration;
context.__gaiusSingleplayerWorkers.set(backlogSessionId, {
  __gaiusTerminal: false,
  __gaiusLaunchGeneration: launchGeneration,
  __gaiusClientPort: backlogPort1,
});
context.__gaiusLocalServerPorts.set(backlogSessionId, backlogPort1);
backlogPort2.on("message", (message) => {
  if (message && message.type === "flow") backlogFlows.push(message);
});
bridge.open(backlogSocketId, `client-${backlogSessionId}.gaius-local`, 25565);
const backlogEntry = bridge.channels.get(backlogSocketId);
for (let index = 0; index < backlogFrameCount; index++) {
  const frame = new Uint8Array([index & 0xff]);
  backlogPort2.postMessage(frame.buffer, [frame.buffer]);
}
await waitFor(() => stats.receivedFrames >= backlogFrameCount,
  "tiny-frame backlog admission");
await waitFor(() => backlogEntry.flowPaused === true,
  "tiny-frame frame-watermark pause");
const backlogQueuedFrames = () =>
  (backlogEntry.inbound.length - backlogEntry.inboundHead) +
  (backlogEntry.pendingInbound.length - backlogEntry.pendingInboundHead);
assert.ok(backlogQueuedFrames() >= 1024,
  "tiny-frame backlog did not retain the independent frame queue pressure");
let backlogDrained = 0;
let backlogChecksum = 0;
const backlogDrainDeadline = Date.now() + 5000;
while (backlogQueuedFrames() > 256) {
  assert.equal(backlogEntry.flowPaused, true,
    "pending frames resumed the producer before reaching the frame low watermark");
  const chunk = bridge.pollInbound(backlogSocketId);
  if (chunk) {
    assert.equal(chunk.byteLength, 1, "tiny-frame backlog changed frame boundaries");
    assert.equal(chunk[0] & 0xff, backlogDrained & 0xff,
      "tiny-frame backlog reordered payload bytes");
    backlogChecksum += chunk[0] & 0xff;
    backlogDrained++;
  } else {
    await delay(0);
  }
  assert.ok(Date.now() < backlogDrainDeadline, "tiny-frame backlog failed to drain");
}
assert.ok(backlogDrained > 0, "tiny-frame backlog did not expose ready slices");
assert.equal(backlogEntry.flowPaused, true,
  "frame-watermark flow resumed before queued frames reached its low watermark");
while (backlogQueuedFrames() > 0) {
  const chunk = bridge.pollInbound(backlogSocketId);
  if (chunk) {
    assert.equal(chunk.byteLength, 1, "tiny-frame backlog changed frame boundaries");
    assert.equal(chunk[0] & 0xff, backlogDrained & 0xff,
      "tiny-frame backlog reordered payload bytes");
    backlogChecksum += chunk[0] & 0xff;
    backlogDrained++;
  } else {
    await delay(0);
  }
  assert.ok(Date.now() < backlogDrainDeadline, "tiny-frame backlog final drain failed");
}
await waitFor(() => backlogEntry.flowPaused === false,
  "tiny-frame frame-watermark resume");
assert.equal(backlogQueuedFrames(), 0, "tiny-frame backlog lost queue drain accounting");
assert.equal(backlogDrained, backlogFrameCount, "tiny-frame backlog lost frames");
assert.equal(backlogChecksum,
  Array.from({length: backlogFrameCount}, (_unused, index) => index & 0xff)
    .reduce((sum, value) => sum + value, 0),
  "tiny-frame backlog reordered or lost payload bytes");
bridge.close(backlogSocketId);
backlogPort1.close();
backlogPort2.close();

const largeFrameStartCount = stats.receivedFrames;
for (let offset = 0; offset < frameBytes; offset += wireFrameBytes) {
  const fragment = new Uint8Array(wireFrameBytes);
  for (let index = 0; index < fragment.length; index++) {
    fragment[index] = ((offset + index) * 31 + 7) & 0xff;
  }
  port2.postMessage(fragment.buffer, [fragment.buffer]);
}

await waitFor(() => stats.receivedFrames === largeFrameStartCount + frameBytes / wireFrameBytes,
  "all 8 MiB protocol-frame fragments");
await waitFor(() => stats.maxInboundSliceQueue === 256, "256-slice high watermark");
await waitFor(() => flowControls.some((message) => message.paused === true),
  "transport pause control");
assert.equal(stats.peakInboundQueuedBytes, frameBytes,
  "8 MiB protocol frame was not accounted once");
assert.ok(stats.inboundQueuedBytes <= frameBytes,
  "transport queue grew beyond the received first frame");

let receivedBytes = 0;
let receivedSlices = 0;
let pumpTurns = 0;
let maxTurnChunks = 0;
let maxTurnBytes = 0;
const frameDeadline = Date.now() + 10_000;
while (receivedBytes < frameBytes) {
  assert.ok(Date.now() < frameDeadline,
    `8 MiB protocol frame stalled after ${receivedSlices} slices`);
  const turnStartedAt = performance.now();
  let turnChunks = 0;
  let turnBytes = 0;
  while (turnChunks < 64 && turnBytes < 256 * 1024) {
    if (turnChunks > 0 && performance.now() - turnStartedAt >= 2) break;
    const chunk = bridge.pollInbound(socketId);
    if (!chunk) break;
    assert.equal(chunk.byteLength, decodedSliceBytes,
      `protocol fragment was not ${decodedSliceBytes} bytes`);
    const firstAbsolute = receivedBytes;
    const middleAbsolute = receivedBytes + (chunk.byteLength >>> 1);
    const lastAbsolute = receivedBytes + chunk.byteLength - 1;
    assert.equal(chunk[0] & 0xff, (firstAbsolute * 31 + 7) & 0xff,
      `TCP byte order changed at offset ${firstAbsolute}`);
    assert.equal(chunk[chunk.byteLength >>> 1] & 0xff,
      (middleAbsolute * 31 + 7) & 0xff,
      `TCP byte order changed at offset ${middleAbsolute}`);
    assert.equal(chunk[chunk.byteLength - 1] & 0xff,
      (lastAbsolute * 31 + 7) & 0xff,
      `TCP byte order changed at offset ${lastAbsolute}`);
    bridge.recordDecodedSlice(socketId, chunk.byteLength);
    receivedBytes += chunk.byteLength;
    receivedSlices++;
    turnChunks++;
    turnBytes += chunk.byteLength;
    if (receivedBytes === frameBytes) {
      // This is one protocol packet. No decoded-packet credit exists before its final slice.
      bridge.recordDecodedPacketQueue(1, false, false);
    }
    bridge.finishDecodedSlice(socketId);
  }
  if (turnChunks > 0) {
    const turnMillis = Math.max(0, performance.now() - turnStartedAt);
    bridge.recordPump(socketId, turnChunks, turnBytes, turnMillis);
    pumpTurns++;
    maxTurnChunks = Math.max(maxTurnChunks, turnChunks);
    maxTurnBytes = Math.max(maxTurnBytes, turnBytes);
  }
  await delay(0);
}

assert.equal(receivedBytes, frameBytes, "8 MiB protocol frame lost bytes");
assert.equal(receivedSlices, 2048,
  "8 MiB protocol frame did not produce 2048 4 KiB slices");
assert.ok(pumpTurns >= 32, "8 MiB frame bypassed bounded 64-slice event-loop turns");
assert.ok(maxTurnChunks <= 64, "an inbound event-loop turn exceeded 64 slices");
assert.ok(maxTurnBytes <= 256 * 1024, "an inbound event-loop turn exceeded 256 KiB");
assert.equal(stats.maxDecodedSliceBacklog, 1,
  "raw-slice ownership survived beyond a synchronous decoder handoff");
assert.equal(stats.decodedSliceBacklog, 0,
  "raw-slice ownership remained after the decoder handoff returned");
assert.equal(stats.maxDecoderCumulationBytes, frameBytes,
  "decoder cumulation did not account the complete 8 MiB protocol frame");
assert.ok(stats.decoderCumulationBytes <= decodedSliceBytes,
  "decoded packet retained more than the conservative active-slice tail");
bridge.recordDecodedPacketQueue(0, false, true);
await waitFor(() => flowControls.some((message) => message.paused === false),
  "fragmented packet transport resume");
assert.equal(stats.decodedSliceBacklog, 0,
  "one fragmented packet retained raw-slice ownership");

const mergedPayload = new Uint8Array(4096);
const mergedExpectedFrames = stats.receivedFrames + 1;
port2.postMessage(mergedPayload.buffer, [mergedPayload.buffer]);
await waitFor(() => stats.receivedFrames === mergedExpectedFrames, "merged packet frame");
let mergedChunk;
await waitFor(() => (mergedChunk = bridge.pollInbound(socketId)) !== null,
  "merged packet slice");
assert.equal(mergedChunk.byteLength, 4096);
bridge.recordDecodedSlice(socketId, mergedChunk.byteLength);
bridge.recordDecodedPacketQueue(256, true, false);
bridge.finishDecodedSlice(socketId);
for (let depth = 255; depth >= 0; depth--) {
  bridge.recordDecodedPacketQueue(depth, depth > 64, true);
}
await waitFor(() => flowControls.filter((message) => message.paused === false).length >= 2,
  "merged packet transport resume");
assert.equal(stats.decodedSliceBacklog, 0,
  "coalesced packets created synthetic decoded-slice debt");
assert.equal(stats.maxDecodedPacketQueue, 256,
  "exact decoded-packet queue did not reach its high watermark");
assert.equal(stats.decodedPacketQueue, 0, "exact decoded-packet queue did not drain to zero");

// Regression for the real 26.2 multiplayer failure: the transport reached its 256-slice
// watermark, Java drained the raw slices, but an unfinished length-prefixed packet retained more
// than one 4 KiB slice. That tail needs additional TCP bytes, so it must not block the flow
// resume that allows those bytes to arrive.  Inline configuration packets retire cumulation at
// their exact PacketUtils boundary instead of depending on the queued PLAY packet scheduler.
const inlinePauseCount = stats.flowPauses;
const inlineResumeCount = stats.flowResumes;
const inlineFragments = 256;
const inlineFragmentBytes = 4096;
for (let index = 0; index < inlineFragments; index++) {
  const payload = new Uint8Array(inlineFragmentBytes);
  payload[0] = index & 0xff;
  port2.postMessage(payload.buffer, [payload.buffer]);
}
await waitFor(() => stats.flowPauses > inlinePauseCount,
  "inline configuration transport pause");
const startupRuntimeTurnChunks = [];
const startupRuntimeTurnBytes = [];
let inlineIndex = 0;
while (inlineIndex < inlineFragments) {
  const turnStartedAt = performance.now();
  let turnChunks = 0;
  let turnBytes = 0;
  while (turnChunks < 64 && turnBytes < 256 * 1024) {
    if (turnChunks > 0 && performance.now() - turnStartedAt >= 2) break;
    const chunk = bridge.pollInbound(socketId);
    if (!chunk) break;
    assert.equal(chunk.byteLength, inlineFragmentBytes);
    bridge.recordDecodedSlice(socketId, chunk.byteLength);
    if (inlineIndex === 0) bridge.recordInlineDecodedPacket();
    bridge.finishDecodedSlice(socketId);
    inlineIndex++;
    turnChunks++;
    turnBytes += chunk.byteLength;
  }
  if (turnChunks > 0) {
    bridge.recordPump(
      socketId,
      turnChunks,
      turnBytes,
      Math.max(0, performance.now() - turnStartedAt),
    );
    startupRuntimeTurnChunks.push(turnChunks);
    startupRuntimeTurnBytes.push(turnBytes);
  } else {
    await waitFor(() => bridge.hasPendingInbound(socketId),
      "next inline configuration startup slice");
  }
  await delay(0);
}
assert.equal(startupRuntimeTurnChunks.reduce((total, chunks) => total + chunks, 0), 256,
  "runtime startup pump lost tiny slices");
assert.ok(startupRuntimeTurnChunks.every((chunks) => chunks > 0 && chunks <= 64),
  "runtime startup pump exceeded its 64-chunk bound");
assert.ok(startupRuntimeTurnBytes.every((bytes) => bytes <= 256 * 1024),
  "runtime startup pump exceeded its 256 KiB bound");
assert.equal(Math.max(...startupRuntimeTurnChunks), 64,
  "runtime startup pump never exercised the bounded 64-chunk cap");
await waitFor(() => stats.flowResumes > inlineResumeCount,
  "inline partial-packet transport resume");
assert.ok(stats.decoderCumulationBytes > 64 * 1024,
  "inline regression did not retain a multi-slice partial packet");
assert.equal(bridge.channels.get(socketId).flowPaused, false,
  "transport remained paused while a partial packet awaited TCP bytes");

const completion = new Uint8Array([0x7f]);
const completionExpectedFrames = stats.receivedFrames + 1;
port2.postMessage(completion.buffer, [completion.buffer]);
await waitFor(() => stats.receivedFrames === completionExpectedFrames,
  "inline partial-packet completion frame");
let completionChunk;
await waitFor(() => (completionChunk = bridge.pollInbound(socketId)) !== null,
  "inline partial-packet completion slice");
bridge.recordDecodedSlice(socketId, completionChunk.byteLength);
bridge.recordInlineDecodedPacket();
bridge.finishDecodedSlice(socketId);
assert.equal(stats.decoderCumulationBytes, completionChunk.byteLength,
  "inline packet completion did not retire older decoder cumulation");
assert.equal(stats.inlineDecodedPackets, 2,
  "inline decoded-packet accounting did not observe both packet boundaries");

const quickCycles = 180;
const longDeadline = process.env.GAIUS_LONG_SMOKE === "1"
  ? Date.now() + 30 * 60 * 1000
  : 0;
const memorySamples = [];
let nextMemorySampleAt = Date.now();
let cycles = 0;
do {
  const payload = new Uint8Array(4096);
  payload[0] = cycles & 0xff;
  const expectedFrames = stats.receivedFrames + 1;
  port2.postMessage(payload.buffer, [payload.buffer]);
  await waitFor(() => stats.receivedFrames === expectedFrames, "sustained inbound frame");
  let chunk;
  await waitFor(() => (chunk = bridge.pollInbound(socketId)) !== null,
    "sustained inbound slice");
  assert.equal(chunk.byteLength, 4096);
  assert.equal(chunk[0] & 0xff, cycles & 0xff);
  bridge.recordDecodedSlice(socketId, chunk.byteLength);
  bridge.recordDecodedPacketQueue(1, false, false);
  bridge.finishDecodedSlice(socketId);
  bridge.recordDecodedPacketQueue(0, false, true);
  assert.equal(stats.inboundQueuedBytes, 0,
    "sustained transport cycle left queued bytes behind");
  assert.equal(stats.decodedPacketQueue, 0,
    "sustained transport cycle left decoded packets behind");
  if (longDeadline && Date.now() >= nextMemorySampleAt) {
    if (global.gc) global.gc();
    const usage = process.memoryUsage();
    memorySamples.push({
      at: Date.now(),
      heapUsed: usage.heapUsed,
      arrayBuffers: usage.arrayBuffers,
    });
    nextMemorySampleAt = Date.now() + 60 * 1000;
  }
  cycles++;
  if (longDeadline) await delay(10);
} while (longDeadline ? Date.now() < longDeadline : cycles < quickCycles);

await delay(120);
const entry = bridge.channels.get(socketId);
for (let index = 0; index < 65; index++) {
  bridge.recordDecodedPacketQueue(
    0, false, true, 50 + index, `test.QueuedPacket${index}`);
}
assert.equal(stats.queuedPacketHandleSamples, 65,
  "queued packet handler sample counter lost exact completions");
assert.equal(stats.maxQueuedPacketHandleMillis, 114,
  "queued packet handler maximum was not retained");
assert.equal(stats.maxQueuedPacketHandleType, "test.QueuedPacket64",
  "queued packet handler maximum lost its exact packet type");
assert.equal(stats.slowQueuedPacketEventSequence, 65,
  "slow queued packet sequence is not monotonic");
assert.equal(stats.slowQueuedPacketEvents.length, 64,
  "slow queued packet evidence ring is not bounded to 64");
assert.equal(stats.slowQueuedPacketEventsDropped, 1,
  "slow queued packet evidence did not report its dropped prefix");
assert.equal(stats.slowQueuedPacketEvents[0].sequence, 2,
  "slow queued packet evidence did not retain the newest bounded suffix");
assert.equal(entry.inboundBytes, 0);
assert.equal(entry.pendingInboundBytes, 0);
assert.equal(entry.inbound.length, 0,
  "drained slice array retained a large backing buffer");
assert.equal(entry.pendingInbound.length, 0,
  "drained raw-frame array retained a large backing buffer");
assert.equal(stats.inboundQueuedBytes, 0, "transport byte queue did not drain to zero");
assert.ok(stats.maxInboundSliceQueue <= 256, "slice queue exceeded its hard high watermark");
assert.ok(stats.maxDecodedSliceBacklog <= 256,
  "decoded-slice backlog exceeded its hard high watermark");
assert.ok(stats.maxDecoderCumulationBytes <= stats.decoderCumulationLimitBytes,
  "decoder cumulation exceeded its 16 MiB hard limit");
assert.ok(stats.maxDecodedPacketQueue <= 256,
  "exact decoded-packet queue exceeded its hard high watermark");
assert.ok(stats.decodedSliceBacklogPauses >= 1 &&
  stats.decodedSliceBacklogResumes >= 1,
  "decoded-slice backpressure did not complete a pause/resume cycle");
assert.ok(stats.decodedPacketDrainSignals >= 257,
  "actual PacketProcessor drain signals did not retire decoded-slice debt");
assert.equal(stats.decodedPacketQueuePauses, 1,
  "exact decoded-packet queue did not pause once at 256");
assert.equal(stats.decodedPacketQueueResumes, 1,
  "exact decoded-packet queue did not resume once at 64");
assert.equal(entry.flowPaused, false, "transport remained flow-paused after the large packet");
assert.equal(entry.decodeFlowPaused, false,
  "decoder flow state remained paused after the large packet");
assert.equal(bridge.exactPacketQueuePaused, false,
  "decoded-packet queue remained paused after drain");
assert.equal(stats.activeHighWatermarks, 0, "high watermark remained active after drain");
assert.ok(stats.highWatermarkDurationMillis > 0,
  "high watermark duration telemetry was not recorded");
assert.ok(stats.longestHighWatermarkMillis > 0,
  "longest high watermark telemetry was not recorded");
assert.ok(stats.longestHighWatermarkMillis < 500,
  `high-watermark episode lasted ${stats.longestHighWatermarkMillis.toFixed(1)} ms`);
assert.ok(stats.longestInboundSlicePumpMillis < 500,
  `single slice pump blocked for ${stats.longestInboundSlicePumpMillis.toFixed(1)} ms`);
assert.ok(stats.inboundImmediateSchedules > 0,
  "initial inbound slices did not use an immediate schedule");
assert.ok(inboundContinuationStats.inboundMessageChannelSchedules > 0,
  "bounded inbound continuation did not use a MessageChannel macrotask");
assert.equal(stats.inboundMessageChannelCallbacks,
  stats.inboundMessageChannelSchedules,
  "inbound MessageChannel callback count did not match scheduled continuations");
assert.equal(stats.inboundContinuationMacrotasks,
  stats.inboundMessageChannelSchedules + stats.inboundRafSchedules +
    stats.inboundTimerSchedules,
  "inbound continuation macrotask accounting drifted");
assert.equal(stats.inboundMessageChannelFailures, 0,
  "inbound MessageChannel unexpectedly fell back after a construction/post failure");
assert.equal(stats.inboundRafSchedules, 0,
  "requestAnimationFrame remained on the primary inbound continuation path");
assert.equal(stats.inboundTimerSchedules, 0,
  "MessageChannel-capable smoke unexpectedly used timer continuation");
const inboundContinuationSchedules =
  stats.inboundImmediateSchedules + stats.inboundMessageChannelSchedules +
  stats.inboundRafSchedules + stats.inboundTimerSchedules;
assert.ok(stats.inboundSliceScheduleWaitSamples <= inboundContinuationSchedules,
  "inbound schedule-wait telemetry exceeded callback schedules");
assert.ok(inboundContinuationStats.inboundContinuationStaleCallbacks >= 1,
  "inbound continuation generation guard was never exercised");
assert.ok(Number.isFinite(stats.maxInboundSliceScheduleWaitMillis) &&
  stats.maxInboundSliceScheduleWaitMillis < 500,
  `inbound slice scheduling waited ${stats.maxInboundSliceScheduleWaitMillis.toFixed(1)} ms`);
assert.ok(stats.longestEventLoopGapMillis < 500,
  `event loop gap reached ${stats.longestEventLoopGapMillis.toFixed(1)} ms`);
assert.equal(stats.eventLoopGapsOver500, 0, "observed an event loop gap >=500 ms");
assert.ok(Math.max(0, ...callbackDurations) < 500,
  "inbound continuation callback blocked for >=500 ms");
if (memorySamples.length >= 5) {
  const tail = memorySamples.slice(-5);
  const heapMonotonic = tail.every((sample, index) =>
    index === 0 || sample.heapUsed > tail[index - 1].heapUsed);
  const buffersMonotonic = tail.every((sample, index) =>
    index === 0 || sample.arrayBuffers > tail[index - 1].arrayBuffers);
  assert.ok(!heapMonotonic, "post-GC heap grew monotonically across the last five minutes");
  assert.ok(!buffersMonotonic,
    "post-GC ArrayBuffer memory grew monotonically across the last five minutes");
}

// Decoder cumulation is no longer a relay pause reason, so its independent 16 MiB guard must
// remain fail-closed.  Prime a second channel at the exact limit and deliver one more byte; the
// bridge must reject it before handing the slice to Java and must retire every owned buffer.
const overflowSessionId = "6123456789abcdef0123456789abcdef";
const overflowSocketId = 92;
const {port1: overflowPort1, port2: overflowPort2} = new MessageChannel();
overflowPort1.__gaiusLaunchGeneration = launchGeneration;
context.__gaiusSingleplayerWorkers.set(overflowSessionId, {
  __gaiusTerminal: false,
  __gaiusLaunchGeneration: launchGeneration,
  __gaiusClientPort: overflowPort1,
});
context.__gaiusLocalServerPorts.set(overflowSessionId, overflowPort1);
bridge.open(overflowSocketId, `client-${overflowSessionId}.gaius-local`, 25565);
const overflowEntry = bridge.channels.get(overflowSocketId);
// Exercise the actual bridge owner state, not only the pure model above. A nested B scope must
// force conservative fan-out, and disposing the abandoned A scope must unwind the ambiguity so a
// fresh C scope can use the O(1) path again. Duplicate/late finishes must not underflow backlog.
bridge.recordDecodedSlice(socketId, 128);
bridge.recordDecodedSlice(overflowSocketId, 256);
assert.equal(bridge.activeDecoderOwnerAmbiguous, true,
  "nested decoder scopes did not enter fail-closed ambiguity");
bridge.finishDecodedSlice(overflowSocketId);
assert.equal(bridge.activeDecoderScopeDepth, 1,
  "finishing the nested decoder scope lost the outer scope");
assert.equal(bridge.activeDecoderEntryId, socketId,
  "finishing the nested decoder scope did not restore the outer owner");
assert.equal(bridge.activeDecoderOwnerAmbiguous, true,
  "outer decoder scope incorrectly cleared owner ambiguity");
bridge.discardInbound(bridge.channels.get(socketId));
assert.equal(bridge.activeDecoderScopeDepth, 0,
  "discarding an abandoned decoder scope left stack depth live");
assert.equal(bridge.activeDecoderEntryId, 0,
  "discarding an abandoned decoder scope left a stale owner");
assert.equal(bridge.activeDecoderOwnerAmbiguous, false,
  "discarding an abandoned decoder scope left ambiguity latched");
bridge.recordDecodedSlice(overflowSocketId, 96);
bridge.recordDecodedSlice(overflowSocketId, 192);
assert.equal(bridge.activeDecoderOwnerAmbiguous, true,
  "same-owner nested decoder scopes were not treated conservatively");
bridge.finishDecodedSlice(overflowSocketId);
assert.equal(bridge.activeDecoderScopeDepth, 1,
  "same-owner nested finish lost its outer scope");
assert.equal(bridge.activeDecoderOwnerAmbiguous, true,
  "same-owner nested finish cleared ambiguity before the outer scope ended");
bridge.finishDecodedSlice(overflowSocketId);
assert.equal(bridge.activeDecoderScopeDepth, 0,
  "same-owner decoder scopes did not fully unwind");
assert.equal(bridge.activeDecoderOwnerAmbiguous, false,
  "same-owner decoder ambiguity remained latched after unwind");
bridge.recordDecodedSlice(overflowSocketId, 512);
assert.equal(bridge.activeDecoderOwnerAmbiguous, false,
  "fresh decoder scope could not recover O(1) ownership");
const overflowBacklogBeforeDuplicateFinish = overflowEntry.decodedSliceBacklog;
bridge.finishDecodedSlice(overflowSocketId);
bridge.finishDecodedSlice(overflowSocketId);
assert.equal(overflowEntry.decodedSliceBacklog,
  Math.max(0, overflowBacklogBeforeDuplicateFinish - 1),
  "duplicate decoder finish underflowed the per-channel backlog");
bridge.discardInbound(overflowEntry);
const decoderHardLimitBytes = 16 * 1024 * 1024;
overflowEntry.decoderCumulationBytes = decoderHardLimitBytes;
stats.decoderCumulationBytes += decoderHardLimitBytes;
const overflowExpectedFrames = stats.receivedFrames + 1;
const overflowByte = new Uint8Array([0x01]);
overflowPort2.postMessage(overflowByte.buffer, [overflowByte.buffer]);
await waitFor(() => stats.receivedFrames === overflowExpectedFrames,
  "decoder hard-limit overflow frame");
let overflowPoll;
await waitFor(() => {
  overflowPoll = bridge.pollInbound(overflowSocketId);
  return overflowEntry.closed;
}, "decoder hard-limit failure");
assert.equal(overflowPoll, null,
  "decoder hard-limit overflow escaped into the Java pipeline");
const overflowError = bridge.pollError(overflowSocketId);
assert.match(overflowError,
  /Browser decoder cumulation exceeded 16 MiB/);
await waitFor(() => overflowEntry.disposed,
  "decoder hard-limit finalization");
assert.equal(overflowEntry.disposed, true,
  "decoder hard-limit failure did not dispose the channel");
assert.equal(overflowEntry.decoderCumulationBytes, 0,
  "decoder hard-limit failure retained cumulation bytes");
bridge.close(overflowSocketId);
overflowPort2.close();

// A single large startup frame pauses on bytes before it is sliced.  Closing the channel while
// that episode is active must finalize the completed-event record with its start/end snapshots,
// rather than leaving an active watermark behind or losing the diagnostic.
const bytePauseSessionId = "7123456789abcdef0123456789abcdef";
const bytePauseSocketId = 93;
const {port1: bytePausePort1, port2: bytePausePort2} = new MessageChannel();
bytePausePort1.__gaiusLaunchGeneration = launchGeneration;
context.__gaiusSingleplayerWorkers.set(bytePauseSessionId, {
  __gaiusTerminal: false,
  __gaiusLaunchGeneration: launchGeneration,
  __gaiusClientPort: bytePausePort1,
});
context.__gaiusLocalServerPorts.set(bytePauseSessionId, bytePausePort1);
bridge.open(bytePauseSocketId, `client-${bytePauseSessionId}.gaius-local`, 25565);
const bytePauseExpectedFrames = stats.receivedFrames + 1;
const bytePausePayload = new Uint8Array(24 * 1024 * 1024);
bytePausePort2.postMessage(bytePausePayload.buffer, [bytePausePayload.buffer]);
await waitFor(() => stats.receivedFrames === bytePauseExpectedFrames,
  "24 MiB inbound-byte watermark frame");
const bytePauseEntry = bridge.channels.get(bytePauseSocketId);
assert.equal(bytePauseEntry.highWatermarkEpisode?.reason, "inbound-bytes",
  "large startup frame did not retain its active byte-watermark reason");
assert.equal(bytePauseEntry.highWatermarkEpisode?.channelId, bytePauseSocketId);
assert.equal(bytePauseEntry.highWatermarkEpisode?.startQueuedBytes, 24 * 1024 * 1024);
const bytePauseSequence = bytePauseEntry.highWatermarkEpisode.sequence;
bridge.close(bytePauseSocketId);
bytePausePort2.close();
assert.equal(bytePauseEntry.highWatermarkEpisode, null,
  "discard left a high-watermark episode active on the closed entry");
assert.equal(bytePauseEntry.flowPaused, false,
  "discard left the closed entry flow-paused");
const bytePauseEvent = stats.highWatermarkEvents.find(
  (event) => event.sequence === bytePauseSequence,
);
assertHighWatermarkEvent(bytePauseEvent, "discard-completed byte watermark");
assert.equal(bytePauseEvent.reason, "inbound-bytes");
assert.equal(bytePauseEvent.channelId, bytePauseSocketId);

// Frame-cap failures must retain only bounded queue/accounting state before discard.  The
// payload is never retained, and a late delivery after fail must not rebuild the queue.
const frameLimitSessionId = "8123456789abcdef0123456789abcdef";
const frameLimitSocketId = 95;
const {port1: frameLimitPort1, port2: frameLimitPort2} = new MessageChannel();
frameLimitPort1.__gaiusLaunchGeneration = launchGeneration;
context.__gaiusSingleplayerWorkers.set(frameLimitSessionId, {
  __gaiusTerminal: false,
  __gaiusLaunchGeneration: launchGeneration,
  __gaiusClientPort: frameLimitPort1,
});
context.__gaiusLocalServerPorts.set(frameLimitSessionId, frameLimitPort1);
bridge.open(frameLimitSocketId, `client-${frameLimitSessionId}.gaius-local`, 25565);
const frameLimitEntry = bridge.channels.get(frameLimitSocketId);
stats.lastInboundFailure = null;
for (let index = 0; index < 4097; index++) {
  bridge.deliverInbound(frameLimitEntry, new Uint8Array([index & 0xff]).buffer);
}
const frameLimitFailure = stats.lastInboundFailure;
assert.ok(frameLimitFailure, "frame-limit failure snapshot was not recorded");
assert.equal(frameLimitFailure.reason, "frame-limit");
assert.equal(frameLimitFailure.channelId, frameLimitSocketId);
assert.equal(frameLimitFailure.totalFrames, 4096,
  "frame-limit snapshot did not capture pre-cleanup queue depth");
assert.equal(frameLimitFailure.totalBytes, 4096,
  "frame-limit snapshot did not capture pre-cleanup queue bytes");
assert.equal(frameLimitFailure.inboundFrames + frameLimitFailure.pendingFrames,
  frameLimitFailure.totalFrames);
assert.equal(frameLimitFailure.inboundBytes + frameLimitFailure.pendingBytes,
  frameLimitFailure.totalBytes);
assert.equal(frameLimitEntry.inbound.length, 0,
  "frame-limit cleanup retained inbound frames");
assert.equal(frameLimitEntry.pendingInbound.length, 0,
  "frame-limit cleanup retained pending frames");
const retainedFrameLimitFailure = JSON.stringify(frameLimitFailure);
bridge.deliverInbound(frameLimitEntry, new Uint8Array([0]).buffer);
assert.equal(JSON.stringify(stats.lastInboundFailure), retainedFrameLimitFailure,
  "late delivery changed the retained frame-limit snapshot");
assert.equal(frameLimitEntry.inbound.length + frameLimitEntry.pendingInbound.length, 0,
  "late delivery rebuilt a failed inbound queue");
bridge.close(frameLimitSocketId);
frameLimitPort2.close();

bridge.close(socketId);
port2.close();
assert.equal(bridge.channels.size, 0, "closed channel remained registered");
assert.equal(bridge.gapProbeTimer, 0, "event-loop gap probe timer survived final close");
assert.equal(stats.activeHighWatermarks, 0,
  "closed channels left active high-watermark episodes");

const runtimeHighWatermarkEvents = Array.from(
  stats.highWatermarkEvents,
  (event) => ({...event}),
);
assert.ok(runtimeHighWatermarkEvents.length > 0 && runtimeHighWatermarkEvents.length <= 64,
  "runtime high-watermark completed-event ring is empty or unbounded");
runtimeHighWatermarkEvents.forEach((event, index) => {
  assertHighWatermarkEvent(event, `runtime high-watermark event ${index}`);
  if (index > 0) {
    assert.ok(event.sequence > runtimeHighWatermarkEvents[index - 1].sequence,
      "runtime high-watermark events are not sorted by their start sequence");
  }
});
const runtimeHighWatermarkReasons = Array.from(new Set(
  runtimeHighWatermarkEvents.map((event) => event.reason),
)).sort();
assert.deepEqual(runtimeHighWatermarkReasons, [
  "exact-packet-queue",
  "inbound-bytes",
  "inbound-frame-depth",
  "inbound-slice-depth",
], "runtime flow control did not exercise every exact high-watermark reason");

// Deterministic ring overflow contract: sequence is assigned while the episode is active, the
// completed ring retains the newest 64 starts in order, and the 65th completion increments the
// dropped count exactly once.
stats.highWatermarkEventSequence = 0;
stats.highWatermarkEvents = [];
stats.highWatermarkEventsDropped = 0;
const ringEntry = {
  id: 94,
  highWatermarkStartedAt: 0,
  highWatermarkEpisode: null,
};
const ringReasons = [
  "inbound-slice-depth",
  "inbound-bytes",
  "exact-packet-queue",
];
for (let index = 0; index < 65; index++) {
  bridge.startHighWatermark(
    ringEntry,
    ringReasons[index % ringReasons.length],
    index,
    index * 4096,
  );
  assert.equal(ringEntry.highWatermarkEpisode.sequence, index + 1,
    "high-watermark sequence was not allocated at episode start");
  bridge.finishHighWatermark(ringEntry, index + 1, (index + 1) * 4096);
}
assert.equal(stats.highWatermarkEventSequence, 65);
assert.equal(stats.highWatermarkEvents.length, 64,
  "completed high-watermark event ring exceeded its 64-entry bound");
assert.equal(stats.highWatermarkEventsDropped, 1,
  "65 completed high-watermark events did not drop exactly one oldest event");
assert.deepEqual(
  Array.from(stats.highWatermarkEvents, (event) => event.sequence),
  Array.from({length: 64}, (_unused, index) => index + 2),
  "completed high-watermark ring did not retain sequences 2..65 in order",
);
stats.highWatermarkEvents.forEach((event, index) => {
  assertHighWatermarkEvent(event, `bounded high-watermark event ${index}`);
  assert.equal(event.channelId, ringEntry.id);
  assert.equal(event.endDepth, event.startDepth + 1);
  assert.equal(event.endQueuedBytes, event.startQueuedBytes + 4096);
});
assert.equal(ringEntry.highWatermarkEpisode, null);
assert.equal(stats.activeHighWatermarks, 0);
await delay(20);
console.log(JSON.stringify({
  ok: true,
  firstFrameBytes: receivedBytes,
  firstFrameSlices: receivedSlices,
  firstFramePumpTurns: pumpTurns,
  maxFirstFrameTurnChunks: maxTurnChunks,
  maxFirstFrameTurnBytes: maxTurnBytes,
  inboundSlices: stats.inboundSlices,
  maxInboundSliceQueue: stats.maxInboundSliceQueue,
  maxDecodedSliceBacklog: stats.maxDecodedSliceBacklog,
  maxDecoderCumulationBytes: stats.maxDecoderCumulationBytes,
  maxDecodedPacketQueue: stats.maxDecodedPacketQueue,
  queuedPacketHandleSamples: stats.queuedPacketHandleSamples,
  maxQueuedPacketHandleMillis: stats.maxQueuedPacketHandleMillis,
  maxQueuedPacketHandleType: stats.maxQueuedPacketHandleType,
  slowQueuedPacketEventsRetained: stats.slowQueuedPacketEvents.length,
  slowQueuedPacketEventsDropped: stats.slowQueuedPacketEventsDropped,
  decodedSliceBacklogPauses: stats.decodedSliceBacklogPauses,
  decodedSliceBacklogResumes: stats.decodedSliceBacklogResumes,
  decodedPacketDrainSignals: stats.decodedPacketDrainSignals,
  decodedPacketQueuePauses: stats.decodedPacketQueuePauses,
  decodedPacketQueueResumes: stats.decodedPacketQueueResumes,
  inlineDecodedPackets: stats.inlineDecodedPackets,
  highWatermarkDurationMillis: Number(stats.highWatermarkDurationMillis.toFixed(3)),
  longestHighWatermarkMillis: Number(stats.longestHighWatermarkMillis.toFixed(3)),
  longestInboundSlicePumpMillis: Number(stats.longestInboundSlicePumpMillis.toFixed(3)),
  inboundImmediateSchedules: stats.inboundImmediateSchedules,
  inboundRafSchedules: stats.inboundRafSchedules,
  inboundTimerSchedules: stats.inboundTimerSchedules,
  inboundMessageChannelSchedules: stats.inboundMessageChannelSchedules,
  inboundMessageChannelCallbacks: stats.inboundMessageChannelCallbacks,
  inboundContinuationMacrotasks: stats.inboundContinuationMacrotasks,
  inboundMessageChannelFailures: stats.inboundMessageChannelFailures,
  inboundContinuationStaleCallbacks: stats.inboundContinuationStaleCallbacks,
  inboundContinuationModel: {
    schedules: inboundContinuationStats.inboundMessageChannelSchedules,
    callbacks: inboundContinuationPostedCallbacks,
    macrotasks: inboundContinuationStats.inboundContinuationMacrotasks,
    failures: inboundContinuationStats.inboundMessageChannelFailures,
    staleDispatches: inboundContinuationStaleDispatches,
    pumps: inboundContinuationStats.testPumps,
  },
  maxInboundSliceScheduleWaitMillis:
    Number(stats.maxInboundSliceScheduleWaitMillis.toFixed(3)),
  startupBurstModelTurns: startupBurstModel.map((turn) => turn.chunks),
  cheapExactQueueRecovery,
  timedExactQueueRecovery,
  startupRuntimeTurnChunks,
  runtimeHighWatermarkReasons,
  highWatermarkEventSequence: stats.highWatermarkEventSequence,
  highWatermarkEventsRetained: stats.highWatermarkEvents.length,
  highWatermarkEventsDropped: stats.highWatermarkEventsDropped,
  longestEventLoopGapMillis: Number(stats.longestEventLoopGapMillis.toFixed(3)),
  sustainedCycles: cycles,
  realThirtyMinuteRun: Boolean(longDeadline),
  memorySamples: memorySamples.length,
}));
