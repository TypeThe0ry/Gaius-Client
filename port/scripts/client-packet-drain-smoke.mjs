#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {memberText, patcherSource as clientPatcherSource} from "./patcher-sources.mjs";
import {MessageChannel} from "node:worker_threads";
import vm from "node:vm";

const networkSource = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserClientNetwork.java",
  import.meta.url,
), "utf8");
const schedulerSource = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserPacketScheduler.java",
  import.meta.url,
), "utf8");
// A patcher step with its domain classes (port/tools/patchkit/README.md, "Domains").
const patcherSource = clientPatcherSource("MinecraftClientPatcher");
const recoverySource = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserMultiplayerRecovery.java",
  import.meta.url,
), "utf8");

function between(source, start, end) {
  const startOffset = source.indexOf(start);
  const endOffset = source.indexOf(end, startOffset + start.length);
  assert.ok(startOffset >= 0 && endOffset > startOffset,
    `could not extract ${start} .. ${end}`);
  return source.slice(startOffset, endOffset);
}

function jsBodyBefore(source, marker) {
  const markerOffset = source.indexOf(marker);
  const annotationOffset = source.lastIndexOf("@JSBody", markerOffset);
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  return source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}

// One PacketProcessor call at Minecraft's scheduled runTick boundary chooses one of three
// bounded modes. A transient claim race may select the ordinary bounded FIFO path, but it must
// still be one call on that execution path, never a second catch-up call in the same frame.
assert.match(schedulerSource, /MAX_PACKETS_PER_BATCH = 16/);
assert.match(schedulerSource, /CLIENT_PACKET_DRAIN_THRESHOLD = 64/);
assert.match(schedulerSource,
  /CLIENT_PACKET_DRAIN_TARGET_QUEUE =\s*CLIENT_PACKET_DRAIN_THRESHOLD - 1/);
assert.match(schedulerSource, /CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS = 256/);
assert.match(schedulerSource, /BATCH_BUDGET_NANOS = 2_000_000L/);
assert.match(schedulerSource,
  /clientPacketDrainActive && !workerServer[\s\S]*queuedPackets - CLIENT_PACKET_DRAIN_TARGET_QUEUE[\s\S]*Math\.min\([\s\S]*CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS,[\s\S]*clientPacketDrainRequestedPackets/,
  "pressure/critical modes no longer target queue 63 behind a 256-packet fail-safe");
assert.match(schedulerSource,
  /if \(packetsRemaining <= 0\)[\s\S]*"hard-cap"[\s\S]*"target"[\s\S]*System\.nanoTime\(\) >= deadlineNanos[\s\S]*"deadline"/,
  "count fail-safe and two-millisecond deadline stop reasons are not explicit");
assert.match(schedulerSource,
  /tryBeginClientPacketDrain\(boolean critical\)[\s\S]*clientPacketDrainActive[\s\S]*queuedPacketHandleDepth > 0[\s\S]*queuedPackets < CLIENT_PACKET_DRAIN_THRESHOLD[\s\S]*clientPacketDrainCritical = critical/,
  "the frame-boundary claim is not fail-closed or loses its exact critical mode");
assert.match(schedulerSource,
  /clientPacketDrainClaimSkipReason\(Object owner\)[\s\S]*"worker-server"[\s\S]*"null-owner"[\s\S]*"owner-conflict"[\s\S]*"retired-owner"[\s\S]*"active-drain"[\s\S]*"handler-depth"[\s\S]*"threshold-race"[\s\S]*"claim-race"/,
  "claim-skipped diagnostics lost a bounded owner/re-entry reason classifier");
assert.match(schedulerSource,
  /clientPacketDrainClaimSkipReason\(Object owner\)[\s\S]*packetProcessorConflictPoisoned[\s\S]*packetProcessorOwnerConflict[\s\S]*packetProcessorAccountingValid[\s\S]*"owner-conflict"/,
  "poisoned global accounting can be misreported as a threshold race");
assert.match(schedulerSource,
  /tryBeginClientPacketDrain\(boolean critical\)[\s\S]*clientPacketDrainEpoch == Long\.MAX_VALUE[\s\S]*clientPacketDrainEpoch \+ 1L[\s\S]*clientPacketDrainHandlerCompletions = 0/,
  "claimed drains do not receive one fresh completion-accounting epoch");
assert.match(schedulerSource,
  /interruptClientPacketDrain\(\)[\s\S]*if \(clientPacketDrainActive\)[\s\S]*clientPacketDrainStopReason = "interrupted"/,
  "handler failure cannot force an interrupted stop independent of remaining debt");
assert.match(schedulerSource,
  /finishClientPacketDrain\(\)[\s\S]*clientPacketDrainActive = false[\s\S]*clientPacketDrainCritical = false/,
  "the frame-boundary claim does not clear both mode fields");
assert.match(schedulerSource,
  /finishClientPacketDrain\(\)[\s\S]*clientPacketDrainRemainingDebt[\s\S]*"pending"\.equals\(clientPacketDrainStopReason\)[\s\S]*"empty"[\s\S]*"interrupted"/,
  "the completed batch does not distinguish queue exhaustion from interrupted debt");
assert.match(schedulerSource,
  /packetQueuePaused && queuedPackets <= PACKET_QUEUE_LOW_WATERMARK/,
  "the existing exact-queue resume watermark changed");
assert.match(schedulerSource,
  /beginClientFrame\(\)[\s\S]*flushClientFrameAccounting\(\)[\s\S]*clientFrameSequence[\s\S]*clientFrameAccountingActive = true/,
  "runTick packet accounting is not started before raw transport work");
assert.match(schedulerSource,
  /if \(clientFrameAccountingActive && !workerServer\)[\s\S]*if \(clientPacketDrainActive\)[\s\S]*clientFrameSafeDrainTurns\+\+[\s\S]*else if \(clientFrameVanillaDrainTurns < Integer\.MAX_VALUE\)[\s\S]*clientFrameVanillaDrainTurns\+\+/,
  "one PacketProcessor batch can be counted as both a pressure and vanilla frame turn");
assert.match(schedulerSource,
  /flushClientFrameAccounting\(\)[\s\S]*!clientFrameAccountingActive[\s\S]*clientFramePacketCount == 0[\s\S]*return;[\s\S]*recordClientPacketFrame/,
  "an empty runTick can allocate a false packet-work frame event");
assert.match(schedulerSource,
  /reset\(\)[\s\S]*BrowserClientNetwork\.invalidateClientPacketDrain\("packet-processor-reset"\)/,
  "PacketProcessor reset can leave passive frame-boundary demand armed");
assert.match(schedulerSource,
  /reset\(\)[\s\S]*preserveActiveDrainEvidence = clientPacketDrainActive[\s\S]*clientPacketDrainStopReason = "interrupted"[\s\S]*if \(!preserveActiveDrainEvidence\)[\s\S]*clientPacketDrainHandlerCompletions = 0/,
  "mid-drain reset can erase the claimed batch epoch/completion evidence");
const schedulerReset = between(schedulerSource, "public static void reset()", "\n    }\n}");
assert.match(schedulerReset,
  /if \(queuedPacketHandleDepth == 0\)[\s\S]*queuedPacketHandleDepth = 0[\s\S]*queuedPackets = 0/,
  "mid-handler reset does not preserve the active handle scope until packetProcessed finalizes it");
assert.doesNotMatch(schedulerReset,
  /!preserveActiveDrainEvidence\s*\|\|\s*queuedPacketHandleDepth\s*==\s*0/,
  "reset may not clear a live queued-handler scope merely because adaptive drain is inactive");
const ownerReset = between(schedulerSource,
  "private static void resetOwnerLedger(PacketProcessorLedger ledger)",
  "private static void completeOwnerPacket");
assert.match(ownerReset,
  /boolean preserveActiveDrainEvidence = ledger\.clientPacketDrainActive/,
  "owner reset does not snapshot the active drain before retiring its ledger");
assert.match(ownerReset,
  /if \(preserveActiveDrainEvidence\)[\s\S]*clientPacketDrainActive = ledger\.clientPacketDrainActive[\s\S]*clientPacketDrainOwner = owner[\s\S]*clientPacketDrainOwnerGeneration = generation[\s\S]*clientPacketDrainEpoch = ledger\.clientPacketDrainEpoch/,
  "in-handler owner reset drops the legacy drain mirror before the final owner unwind");
assert.match(ownerReset,
  /else \{[\s\S]*clientPacketDrainActive = false[\s\S]*clientPacketDrainOwner = null[\s\S]*clientPacketDrainOwnerGeneration = 0L/,
  "inactive owner reset does not clear the legacy drain mirror");
assert.match(between(schedulerSource,
  "public static void finishClientPacketDrain(Object owner)",
  "public static String clientPacketDrainStopReason()"),
  /finishOwnerClientPacketDrain\(ledger\)[\s\S]*if \(owner == clientPacketDrainOwner\)[\s\S]*clientPacketDrainActive = false[\s\S]*clientPacketDrainOwner = null/,
  "retired owner finish does not release the preserved legacy drain mirror");
const drainOwnerClaim = between(schedulerSource,
  "public static boolean tryBeginClientPacketDrain(Object owner, boolean critical)",
  "/** Marks an exceptional or reset-aborted active drain");
assert.match(schedulerSource,
  /private static Object clientPacketDrainOwner;[\s\S]*private static long clientPacketDrainOwnerGeneration;/,
  "active drain is missing its identity/generation owner ledger");
assert.match(drainOwnerClaim,
  /tryBeginClientPacketDrain\(critical\)[\s\S]*clientPacketDrainOwner = owner[\s\S]*clientPacketDrainOwnerGeneration = packetProcessorGeneration/,
  "an owner-aware drain does not capture the generation that claimed it");
const drainOwnerFinish = between(schedulerSource,
  "public static void finishClientPacketDrain(Object owner)",
  "public static String clientPacketDrainStopReason()");
assert.match(drainOwnerFinish,
  /owner != clientPacketDrainOwner[\s\S]*clientPacketDrainOwnerGeneration <= 0L/,
  "finish must reject a foreign or unbound drain owner");
assert.match(drainOwnerFinish,
  /packetProcessorGeneration == clientPacketDrainOwnerGeneration[\s\S]*isRetiredPacketProcessorOwner\(owner\)/,
  "finish must validate both the live generation and the post-reset retired owner");
const ownerPacketProcessed = between(schedulerSource,
  "public static void packetProcessed(Object owner)", "/** Mirrors PacketProcessor.close");
const ownerMismatchPacketProcessed = between(ownerPacketProcessed,
  "if (owner != packetProcessorOwner || !packetProcessorAccountingValid)",
  "if (queuedPacketHandleDepth == 0 && packetProcessorOwner == null");
assert.match(ownerMismatchPacketProcessed,
  /owner == clientPacketDrainOwner[\s\S]*clientPacketDrainActive[\s\S]*clientPacketDrainHandlerCompletions\+\+/,
  "a completed handler after close/reset is not counted against its exact drain owner");
const resetLifecycle = between(schedulerSource,
  "public static void reset()", "public static void reset(Object owner)");
assert.match(resetLifecycle,
  /if \(!preserveActiveDrainEvidence\)[\s\S]*clientPacketDrainOwner = null[\s\S]*clientPacketDrainOwnerGeneration = 0L/,
  "non-active reset cleanup does not retire the drain owner ledger");
const packetProcessorOwnerClaim = between(
  schedulerSource,
  "private static boolean claimPacketProcessorOwner(Object owner)",
  "private static int countRetiredPacketProcessorOwners()",
);
assert.match(packetProcessorOwnerClaim,
  /packetProcessorOwner == null[\s\S]*if \(clientPacketDrainActive\)[\s\S]*packetProcessorFallbackReason = "active-drain-owner-retiring"[\s\S]*return (?:false|null)/,
  "a reentrant owner may not claim the empty slot while a retired active drain awaits finish");

const packetProcessed = between(schedulerSource,
  "public static void packetProcessed()", "/** Mirrors PacketProcessor.close");
assert.match(packetProcessed,
  /completedHandleNanos >= 0L[\s\S]*clientPacketDrainActive && completedHandleNanos >= 0L[\s\S]*clientPacketDrainHandlerCompletions\+\+/,
  "batch completion evidence is not tied to a finalized outer handler scope");
for (const resetStatement of [
  "queuedPacketHandleDepth = 0",
  "clientPacketDrainActive = false",
  "clientPacketDrainCritical = false",
  "queuedPackets = 0",
  "packetQueuePaused = false",
]) {
  assert.ok(schedulerReset.includes(resetStatement),
    `PacketProcessor reset lost ${resetStatement}`);
}

const rawPump = between(networkSource,
  "private static boolean pumpInbound()", "public static void beginClientPacketFrame()");
assert.match(rawPump, /BrowserWebSocketChannel\.pumpAllAndReportProgress\(\)/);
assert.match(rawPump, /continuationHint && BrowserWebSocketChannel\.hasPumpableInput\(\)/);
assert.doesNotMatch(rawPump, /processQueuedPackets/,
  "raw transport callback synchronously handles ordinary PLAY packets");

const scheduledWrapper = between(networkSource,
  "public static void processClientPacketsAtScheduledFrameBoundary(", "@JSBody(params = \"callback\"");
assert.match(scheduledWrapper,
  /boolean accountingValid = BrowserPacketScheduler\.bindPacketProcessor\(packetProcessor\);[\s\S]*int queueBefore = BrowserPacketScheduler\.queuedPacketCount\(\);[\s\S]*boolean drainEnabled = isClientPacketFrameBoundaryDrainEnabled\(\);[\s\S]*if \(!accountingValid \|\| queueBefore < 64 \|\| !drainEnabled\)[\s\S]*if \(!accountingValid && queueBefore >= 64 && drainEnabled\)[\s\S]*recordClientPacketDrainJavaSkipped[\s\S]*clientPacketDrainClaimSkipReason\(packetProcessor\)[\s\S]*packetProcessor\.processQueuedPackets\(\);[\s\S]*return;/,
  "owner conflict or below-threshold mode must use one vanilla PacketProcessor call and retain bind-failure reason evidence");
assert.match(scheduledWrapper,
  /pausedBefore \? "critical" : "pressure"/,
  "the exact paused queue does not retain its critical telemetry mode");
assert.match(scheduledWrapper,
  /tryBeginClientPacketDrain\(packetProcessor, pausedBefore\)/,
  "pressure mode does not claim the single scheduled call with its owner");
assert.match(scheduledWrapper,
  /String claimSkipReason[\s\S]*clientPacketDrainClaimSkipReason\(packetProcessor\)[\s\S]*recordClientPacketDrainJavaSkipped\(claimSkipReason\)/,
  "claim-skipped path does not retain the precise owner/re-entry reason");
assert.match(scheduledWrapper,
  /boolean vanillaFallback[\s\S]*"threshold-race"\.equals\(claimSkipReason\)[\s\S]*"claim-race"\.equals\(claimSkipReason\)[\s\S]*if \(vanillaFallback\) \{[\s\S]*packetProcessor\.processQueuedPackets\(\);[\s\S]*\}/,
  "threshold/claim races must use one bounded ordinary FIFO fallback");
assert.doesNotMatch(scheduledWrapper,
  /vanillaFallback[\s\S]*"active-drain"|vanillaFallback[\s\S]*"handler-depth"/,
  "active-drain and handler-depth re-entry must not recurse through PacketProcessor");
assert.match(networkSource,
  /lastSkipReason:[\s\S]*clientPacketDrainLastSkipReason[\s\S]*skipReasons:[\s\S]*workerServer:[\s\S]*nullOwner:[\s\S]*claimRace:/,
  "client packet drain DOM report does not expose the latest bounded skip reason or all classifier buckets");
assert.match(networkSource,
  /clientPacketDrainSession = session;[\s\S]*clientPacketDrainLastSkipReason = 'none';/,
  "a new remote drain session can inherit a stale skip reason from the previous connection");
assert.match(scheduledWrapper,
  /catch \(RuntimeException \| Error failure\)[\s\S]*interruptClientPacketDrain\(packetProcessor\)[\s\S]*finally \{[\s\S]*clientPacketDrainEpoch\(\)[\s\S]*clientPacketDrainHandlerCompletions\(\)[\s\S]*finishClientPacketDrain\(packetProcessor\)/,
  "failure/finish does not capture exact epoch/completions before releasing the claim");
assert.equal((scheduledWrapper.match(/packetProcessor\.processQueuedPackets\(\)/g) || []).length, 3,
  "the wrapper must have one call per execution path: initial vanilla, safe race fallback, claimed drain");
assert.doesNotMatch(scheduledWrapper, /Minecraft\.getInstance|Platform\.schedule|MessageChannel|setTimeout/,
  "the scheduled wrapper moved away from its supplied vanilla PacketProcessor boundary");
assert.match(scheduledWrapper,
  /drainEpoch,[\s\S]*handlerCompletions,[\s\S]*clientPacketDrainStopReason\(\)[\s\S]*clientPacketDrainTargetQueue\(\)[\s\S]*clientPacketDrainRequestedPackets\(\)[\s\S]*clientPacketDrainBatchTargetPackets\(\)[\s\S]*clientPacketDrainRemainingDebt\(\)/,
  "frame-boundary evidence lost exact batch/stop/debt attribution");

const installMarker = "private static native boolean installInboundPump(";
const installScript = jsBodyBefore(networkSource, installMarker);
new vm.Script(`(function(callback) {${installScript}\n})`);
assert.equal((installScript.match(/\bconst\s+dropped\b/g) || []).length, 0,
  "TeaVM rejects repeated block-scoped `const dropped` declarations in one JSBody");
for (const trimVariable of [
  "retiredDrainEventsDropped",
  "boundaryDrainEventsDropped",
  "packetFrameEventsDropped",
]) {
  assert.equal((installScript.match(new RegExp(`\\bconst\\s+${trimVariable}\\b`, "g")) || []).length,
    1, `install JSBody lost its unique ${trimVariable} ring-trim declaration`);
}
assert.match(networkSource,
  /private static final BrowserPumpCallback PUMP_CALLBACK = BrowserClientNetwork::pumpInbound;/,
  "BrowserClientNetwork.install no longer caches the raw transport callback");
assert.match(networkSource,
  /installInboundPump\(PUMP_CALLBACK\)/,
  "BrowserClientNetwork.install no longer supplies exactly the cached raw transport callback");
assert.match(networkSource,
  /configureClientPacketDrain\(\);[\s\S]*installInboundPump\(PUMP_CALLBACK\)/,
  "client packet drain opt-in is not resolved before the raw pump is installed");
const drainOptInScript = jsBodyBefore(
  networkSource,
  "private static native void configureClientPacketDrain();");
new vm.Script(`(function() {${drainOptInScript}\n})`);
assert.match(drainOptInScript,
  /gaiusClientPacketDrain[\s\S]*URLSearchParams[\s\S]*__gaiusClientPacketDrainEnabled/,
  "client packet drain lost its explicit URL opt-in parser");
assert.match(drainOptInScript,
  /typeof globalThis\.__gaiusClientPacketDrainEnabled === 'boolean'/,
  "an embedding-provided drain boolean must remain authoritative");
const remoteDrainMethod = between(
  networkSource,
  "public static void enableClientPacketDrainForRemoteSession() {",
  "    }\n\n    /**");
assert.match(remoteDrainMethod,
  /configureClientPacketDrain\(\)[\s\S]*enableClientPacketDrainIfUnset\(\)/,
  "remote-session drain promotion must resolve explicit URL/global policy before defaulting");
const remoteEnableScript = jsBodyBefore(
  networkSource,
  "private static native void enableClientPacketDrainIfUnset();");
new vm.Script(`(function() {${remoteEnableScript}\n})`);
assert.match(remoteEnableScript,
  /typeof globalThis\.__gaiusClientPacketDrainEnabled === 'boolean'/,
  "remote-session promotion must not override an embedding-provided boolean");
assert.match(remoteEnableScript,
  /globalThis\.__gaiusClientPacketDrainEnabled = true/,
  "remote-session promotion does not enable the bounded drain when policy is unset");
function remoteDrainPolicyModel(search, existing) {
  const context = {
    URLSearchParams,
    location: {search},
  };
  context.globalThis = context;
  if (existing !== undefined) {
    context.__gaiusClientPacketDrainEnabled = existing;
  }
  vm.runInNewContext(`(function() {${drainOptInScript}\n})()`, context);
  vm.runInNewContext(`(function() {${remoteEnableScript}\n})()`, context);
  return context.__gaiusClientPacketDrainEnabled;
}
assert.equal(remoteDrainPolicyModel("?gaiusClientPacketDrain=0", undefined), false,
  "remote-session promotion overrode an explicit URL opt-out");
assert.equal(remoteDrainPolicyModel("?gaiusClientPacketDrain=1", undefined), true,
  "remote-session promotion failed to retain an explicit URL opt-in");
assert.equal(remoteDrainPolicyModel("", undefined), true,
  "remote-session promotion left the unset policy disabled");
assert.equal(remoteDrainPolicyModel("?gaiusClientPacketDrain=1", false), false,
  "remote-session promotion overrode an embedding-provided false policy");
const beginConnection = between(
  recoverySource,
  "public static void beginConnection(ServerData serverData) {",
  "    }\n\n    public static boolean maybeReconnect");
assert.match(beginConnection,
  /isRemoteServerAddress\(address\)[\s\S]*ServerAddress\.isValidAddress\(address\)[\s\S]*BrowserClientNetwork\.enableClientPacketDrainForRemoteSession\(\)/,
  "remote multiplayer connection does not promote the bounded client drain");
assert.ok(
  beginConnection.indexOf("BrowserClientNetwork.enableClientPacketDrainForRemoteSession()")
    < beginConnection.indexOf("beginConnectionAttempt(address)"),
  "drain promotion must happen before the connection attempt is recorded");
assert.match(networkSource,
  /private static native boolean installInboundPump\(BrowserPumpCallback callback\);/);
assert.doesNotMatch(networkSource,
  /\bclientPacketDrainCallback\s*\(|\bscheduleClientPacketDrain\s*\(/,
  "a retired external PLAY Java callback/scheduler remains in product source");
assert.doesNotMatch(networkSource, /scheduleClientPacketDrain\('continuation'\)/,
  "client PLAY work can still self-repost outside Minecraft.runTick");

// MessageChannel/timer/watchdog remain valid for raw inbound decode only. The passive client
// pressure bridge records a false->true demand edge and returns without scheduling Java.
const rawSchedulerScript = between(installScript,
  "function ensureMessageChannel()", "function clientPacketQueueDepth()");
assert.match(rawSchedulerScript, /new MessageChannel\(\)/,
  "the independent raw transport pump lost its reusable macrotask channel");
assert.match(rawSchedulerScript,
  /setTimeout\(function\(\) \{ finish\('watchdog'\); \}, 100\)/,
  "the raw transport pump lost its single-fire watchdog");
assert.match(rawSchedulerScript, /callback\(\)/,
  "the raw transport scheduler no longer invokes its only Java callback");
const reportBlock = between(installScript,
  "function writeReport(stage)", "function ensureMessageChannel()");
assert.match(reportBlock, /function writeReport\(stage\)/,
  "network diagnostics lost its isolated DOM writer");
assert.match(reportBlock,
  /function flushReport\(\)[\s\S]*scheduler\.reportPending = false[\s\S]*reportDirty/,
  "network diagnostics no longer exposes a synchronous latest-report flush");
assert.match(reportBlock,
  /scheduler\.reportPending[\s\S]*scheduler\.reportDirty[\s\S]*reportStage/,
  "network diagnostics has no bounded latest-only coalescing state");
assert.match(installScript,
  /bridge\.flushInboundPumpReport = function\(\) \{ return flushReport\(\); \};/,
  "legacy observers lost an explicit synchronous network-report flush");
assert.match(reportBlock,
  /inboundPumpDomReportCoalesced[\s\S]*writeReport\(normalizedStage\)/,
  "coalesced network reports still write one DOM payload per signal");
const clientBridgeHandler = between(installScript,
  "bridge.clientPacketDrain = function()", "bridge.invalidateClientPacketDrain = function(reason)");
assert.match(clientBridgeHandler,
  /const demand = clientPacketQueueDepth\(\) >= 64[\s\S]*if \(!demand\)[\s\S]*clientPacketDrainDemand = false/,
  "the passive browser bridge no longer records exact pressure demand");
assert.match(clientBridgeHandler,
  /if \(bridge\.clientPacketDrainDemand\) return false;[\s\S]*clientPacketDrainDemand = true[\s\S]*clientPacketDrainDemandSignals\+\+/,
  "repeated pressure requests can inflate demand evidence without a false->true edge");
assert.doesNotMatch(clientBridgeHandler,
  /callback\(|MessageChannel|setTimeout|watchdog|postMessage|schedule[A-Z]|Platform/,
  "the passive demand bridge can schedule or enter Java PLAY work");
assert.match(installScript,
  /bridge\.clientPacketDrainScheduler = null[\s\S]*bridge\.clientPacketDrainPending = false/,
  "install does not retire an old external client-drain scheduler");

// Execute the real bridge install body. Calling clientPacketDrain only mutates demand evidence;
// every legacy external scheduler/callback counter stays exactly zero.
let javaPumpCalls = 0;
let demandDomReports = 0;
const demandDomPayloads = [];
const stats = {decodedPacketQueue: 80};
const bridge = {stats};
const context = {
  console,
  MessageChannel,
  setTimeout,
  clearTimeout,
  performance,
  __gaiusNettyBridge: bridge,
  __gaiusNetworkStats: stats,
  __gaiusClientPacketDrainEnabled: true,
  document: {
    documentElement: {
      setAttribute(name, value) {
        if (name === "data-gaius-client-packet-drain") {
          demandDomReports++;
          demandDomPayloads.push(JSON.parse(value));
        }
      },
    },
  },
};
context.globalThis = context;
context.callback = () => {
  javaPumpCalls++;
  return false;
};
vm.runInNewContext(`(function(callback) {${installScript}\n})(callback)`, context);
assert.equal(typeof bridge.clientPacketDrain, "function");
assert.equal(bridge.clientPacketDrain(), false);
assert.equal(bridge.clientPacketDrainDemand, true);
assert.equal(stats.clientPacketDrainDemandSignals, 1);
assert.equal(demandDomReports, 1);
assert.ok(demandDomPayloads[0].generation > 0,
  "client packet drain DOM report lost the live scheduler generation");
assert.equal(demandDomPayloads[0].pending, false,
  "passive demand report incorrectly claims a callback is pending");
assert.equal(demandDomPayloads[0].running, false,
  "passive demand report incorrectly claims a callback is running");
assert.equal(bridge.clientPacketDrain(), false);
assert.equal(stats.clientPacketDrainDemandSignals, 1,
  "a sustained pressure window allocated a second demand edge");
assert.equal(demandDomReports, 1,
  "a sustained pressure window repeated its DOM JSON telemetry");
assert.equal(javaPumpCalls, 0, "passive demand unexpectedly invoked the raw Java pump");
for (const key of [
  "clientPacketDrainScheduled",
  "clientPacketDrainCallbacks",
  "clientPacketDrainMessageCallbacks",
  "clientPacketDrainTimerCallbacks",
  "clientPacketDrainWatchdogCallbacks",
  "clientPacketDrainJavaStarted",
  "clientPacketDrainJavaCompleted",
  "clientPacketDrainJavaFailures",
  "clientPacketDrainRescheduled",
]) {
  assert.equal(stats[key], 0, `${key} must remain zero in single-call product mode`);
}
stats.decodedPacketQueue = 63;
assert.equal(bridge.clientPacketDrain(), false);
assert.equal(bridge.clientPacketDrainDemand, false);
assert.equal(demandDomReports, 2);
stats.decodedPacketQueue = 64;
assert.equal(bridge.clientPacketDrain(), false);
assert.equal(stats.clientPacketDrainDemandSignals, 2,
  "a new false->true pressure episode did not allocate one demand signal");
assert.equal(demandDomReports, 3);
bridge.invalidateClientPacketDrain("smoke-reset");
assert.equal(bridge.clientPacketDrainDemand, false);
assert.equal(bridge.clientPacketDrainPending, false);
assert.equal(demandDomReports, 4);

// Raw network diagnostics are latest-only within one Java-pump scheduling window. The first
// report remains synchronous for existing observers; an explicit flush publishes the newest
// coalesced stage without touching the transport callback or scheduler pending state.
const diagnosticStats = {};
const diagnosticBridge = {stats: diagnosticStats};
const diagnosticWrites = [];
const diagnosticTimers = [];
let diagnosticTimerSequence = 0;
let diagnosticJavaCalls = 0;
const diagnosticContext = {
  console,
  MessageChannel: undefined,
  queueMicrotask: undefined,
  performance,
  setTimeout(callback) {
    diagnosticTimers.push(callback);
    diagnosticTimerSequence++;
    return diagnosticTimerSequence;
  },
  clearTimeout() {},
  __gaiusNettyBridge: diagnosticBridge,
  __gaiusNetworkStats: diagnosticStats,
  document: {
    documentElement: {
      setAttribute(name, value) {
        if (name === "data-gaius-network-pump") diagnosticWrites.push(JSON.parse(value));
      },
    },
  },
};
diagnosticContext.globalThis = diagnosticContext;
diagnosticContext.callback = () => {
  diagnosticJavaCalls++;
  return false;
};
vm.runInNewContext(`(function(callback) {${installScript}\n})(callback)`, diagnosticContext);
for (let index = 0; index < 5; index++) diagnosticBridge.inboundPump();
assert.equal(diagnosticStats.inboundPumpRequested, 5);
assert.equal(diagnosticStats.inboundPumpScheduled, 1,
  "diagnostic burst scheduled more than one raw transport callback");
assert.equal(diagnosticStats.inboundPumpCoalesced, 4);
assert.equal(diagnosticWrites.length, 1,
  "the first report must remain synchronously visible to compatibility observers");
assert.equal(diagnosticWrites[0].stage, "requested");
assert.equal(typeof diagnosticBridge.flushInboundPumpReport, "function");
assert.equal(diagnosticBridge.flushInboundPumpReport(), true);
assert.equal(diagnosticWrites.length, 2,
  "latest-only flush emitted more than one report for the coalesced burst");
assert.equal(diagnosticWrites.at(-1).stage, "coalesced");
assert.equal(diagnosticStats.inboundPumpDomReports, 2);
assert.equal(diagnosticStats.inboundPumpDomReportCoalesced, 4);
assert.equal(diagnosticStats.inboundPumpDomReportFlushes, 1);
assert.ok(diagnosticStats.inboundPumpDomReportBytes > 0);
assert.equal(diagnosticBridge.flushInboundPumpReport(), false,
  "an already-flushed diagnostics window emitted a duplicate payload");
assert.equal(diagnosticWrites.length, 2);
assert.equal(diagnosticJavaCalls, 0,
  "diagnostic reporting synchronously entered the raw Java callback");
assert.equal(diagnosticBridge.inboundPumpScheduler.pending !== null, true,
  "diagnostic flush altered the transport scheduler pending state");
assert.ok(diagnosticTimers.length >= 3,
  "raw watchdog/fallback plus diagnostics flush timers were not retained");

// Execute both bounded evidence rings. The oldest prefix is counted instead of silently
// overwritten, and an empty runTick never calls the frame recorder (guarded above in Java).
const boundaryRecordScript = jsBodyBefore(networkSource,
  "private static native void recordClientPacketFrameBoundaryDrain(");
new vm.Script(`(function(runTickSequence, drainEpoch, queueBefore, queueAfter,
  handlerCompletions, handleDepthBefore, handleDepthAfter, pausedBefore, pausedAfter,
  elapsedMillis, stopReason, targetQueue, requestedPackets, batchTargetPackets,
  remainingDebt, mode, outcome, failureType) {
  ${boundaryRecordScript}
})`);
assert.match(boundaryRecordScript, /const packets = bounded\(handlerCompletions\);/,
  "boundary packet totals are not sourced from exact handler completions");
assert.doesNotMatch(boundaryRecordScript,
  /const packets = Math\.max\(0, before - after\)/,
  "queue clear/reset can still be overcounted as handled packets");
const evidenceStats = {};
const evidenceBridge = {stats: evidenceStats, clientPacketDrainDemand: false};
const evidenceContext = {
  console,
  performance,
  __gaiusNettyBridge: evidenceBridge,
  __gaiusNetworkStats: evidenceStats,
};
evidenceContext.globalThis = evidenceContext;
const recordBoundary = vm.runInNewContext(
  `(function(runTickSequence, drainEpoch, queueBefore, queueAfter, handlerCompletions,
    handleDepthBefore, handleDepthAfter, pausedBefore, pausedAfter, elapsedMillis,
    stopReason, targetQueue, requestedPackets, batchTargetPackets, remainingDebt,
    mode, outcome, failureType) {
    ${boundaryRecordScript}
  })`,
  evidenceContext,
);
for (let index = 0; index < 65; index++) {
  recordBoundary(index + 1, index + 1, 256, 63, 193, 0, 0, true, false, 1.93,
    "target", 63, 193, 193, 0, "critical", "completed", null);
}
assert.equal(evidenceStats.frameBoundaryDrainEventSequence, 65);
assert.equal(evidenceStats.frameBoundaryDrainEvents.length, 64);
assert.equal(evidenceStats.frameBoundaryDrainEventsDropped, 1);
assert.equal(evidenceStats.frameBoundaryDrainEvents[0].sequence, 2);
assert.equal(evidenceStats.frameBoundaryDrainEvents.at(-1).sequence, 65);
assert.equal(evidenceStats.frameBoundaryDrainMaxPacketsPerTurn, 193);
assert.ok(evidenceStats.frameBoundaryDrainMaxPacketsPerTurn <= 256,
  "pressure drain exceeded its clock-failure packet ceiling");
assert.equal(evidenceStats.frameBoundaryDrainTargetStops, 65);
assert.equal(Number(evidenceStats.frameBoundaryDrainDeadlineStops) || 0, 0);
assert.equal(Number(evidenceStats.frameBoundaryDrainInterruptedStops) || 0, 0);
assert.equal(Number(evidenceStats.frameBoundaryDrainHardCapStops) || 0, 0);
assert.equal(evidenceStats.frameBoundaryDrainEvents.at(-1).runTickSequence, 65);
assert.deepEqual(
  Object.fromEntries(["drainEpoch", "handlerCompletions", "queueDepthReduction",
    "unattributedQueueReduction", "stopReason", "targetQueue", "requestedPackets",
    "batchTargetPackets", "remainingDebt"].map((key) =>
    [key, evidenceStats.frameBoundaryDrainEvents.at(-1)[key]])),
  {drainEpoch: 65, handlerCompletions: 193, queueDepthReduction: 193,
    unattributedQueueReduction: 0, stopReason: "target", targetQueue: 63, requestedPackets: 193,
    batchTargetPackets: 193, remainingDebt: 0},
  "bounded boundary evidence lost adaptive drain attribution");

// A q64 handler failure removes/finalizes one item and reaches debt zero, but must remain an
// interrupted batch rather than being mislabeled empty/target by its post-failure queue depth.
recordBoundary(66, 66, 64, 63, 1, 0, 0, false, false, 0.2,
  "interrupted", 63, 1, 1, 0, "pressure", "failure", "java.lang.IllegalStateException");
let lastBoundary = evidenceStats.frameBoundaryDrainEvents.at(-1);
assert.equal(lastBoundary.drainEpoch, 66);
assert.equal(lastBoundary.handlerCompletions, 1);
assert.equal(lastBoundary.packetsProcessed, 1);
assert.equal(lastBoundary.remainingDebt, 0);
assert.equal(lastBoundary.stopReason, "interrupted",
  "q64 handler failure was mislabeled successful after reaching queue depth 63");
assert.equal(lastBoundary.outcome, "failure");

// Mid-drain PacketProcessor.close clears the rest of q256. Only four finalized handlers count;
// the 252-item reset reduction remains explicit evidence and can never inflate throughput.
recordBoundary(67, 67, 256, 0, 4, 0, 0, true, false, 0.4,
  "interrupted", 63, 193, 193, 0, "critical", "completed", null);
lastBoundary = evidenceStats.frameBoundaryDrainEvents.at(-1);
assert.equal(lastBoundary.drainEpoch, 67);
assert.equal(lastBoundary.handlerCompletions, 4);
assert.equal(lastBoundary.packetsProcessed, 4);
assert.equal(lastBoundary.queueDepthReduction, 256);
assert.equal(lastBoundary.unattributedQueueReduction, 252);
assert.equal(lastBoundary.stopReason, "interrupted");
assert.equal(evidenceStats.frameBoundaryDrainLastHandlerCompletions, 4);
assert.equal(evidenceStats.frameBoundaryDrainLastQueueDepthReduction, 256);
assert.equal(evidenceStats.frameBoundaryDrainLastUnattributedQueueReduction, 252);
assert.equal(evidenceStats.frameBoundaryDrainMaxPacketsPerTurn, 193,
  "mid-drain reset was overcounted as a 256-packet handler turn");

// A safe claim-race fallback is still a claim skip (so the skip bucket increments), but it is
// explicitly not an adaptive claim.  Its ordinary FIFO work is reported as unattributed to the
// adaptive handler-completion ledger rather than being silently reported as zero queue progress.
const claimsBeforeVanillaFallback = evidenceStats.frameBoundaryDrainClaims;
recordBoundary(68, 0, 80, 64, 0, 0, 0, false, false, 0.15,
  "claim-skipped", 63, 17, 17, 1, "pressure", "vanilla-fallback", null);
lastBoundary = evidenceStats.frameBoundaryDrainEvents.at(-1);
assert.equal(evidenceStats.frameBoundaryDrainClaims, claimsBeforeVanillaFallback,
  "vanilla fallback was miscounted as an adaptive drain claim");
assert.equal(evidenceStats.frameBoundaryDrainSkippedClaim, 1,
  "vanilla fallback lost its failed adaptive-claim evidence");
assert.equal(evidenceStats.frameBoundaryDrainVanillaFallback, 1,
  "vanilla fallback did not increment its dedicated counter");
assert.equal(lastBoundary.outcome, "vanilla-fallback");
assert.equal(lastBoundary.vanillaFallback, true);
assert.equal(lastBoundary.queueDepthReduction, 16);
assert.equal(lastBoundary.unattributedQueueReduction, 16);

const frameRecordScript = jsBodyBefore(networkSource,
  "public static native void recordClientPacketFrame(");
new vm.Script(`(function(runTickSequence, safeDrainTurns, vanillaDrainTurns,
  packetsProcessed, handlerMillis) {${frameRecordScript}\n})`);
const recordFrame = vm.runInNewContext(
  `(function(runTickSequence, safeDrainTurns, vanillaDrainTurns,
    packetsProcessed, handlerMillis) {${frameRecordScript}\n})`,
  evidenceContext,
);
for (let index = 0; index < 65; index++) {
  recordFrame(index + 1, 1, 0, 193, 1.93);
}
assert.equal(evidenceStats.clientPacketFrameEventSequence, 65);
assert.equal(evidenceStats.clientPacketFrameEvents.length, 64);
assert.equal(evidenceStats.clientPacketFrameEventsDropped, 1);
assert.equal(evidenceStats.clientPacketFrameEvents[0].sequence, 2);
assert.equal(evidenceStats.maxClientPacketFrameSafeDrainTurns, 1);
assert.equal(evidenceStats.maxClientPacketFrameVanillaDrainTurns, 0);
assert.equal(evidenceStats.maxClientPacketFramePackets, 193);
assert.ok(evidenceStats.maxClientPacketFrameHandlerMillis < 50,
  "modeled client frame handler work crossed the 50ms safety gate");

// The patcher installs the epoch before install/raw pump and replaces, rather than supplements,
// the one original PacketProcessor virtual call.
const runTickPatch = between(patcherSource,
  'method.name.equals("runTick") && method.desc.equals("(Z)V")',
  'clientPacketFrameBoundaryHooked = true;');
assert.match(runTickPatch,
  /browserPackets\.add\(beginClientPacketFrame\);[\s\S]*browserPackets\.add\(installClientNetwork\);[\s\S]*browserPackets\.add\(pumpClientChannels\)/,
  "runTick entry is not beginClientPacketFrame -> install -> raw pump");
assert.match(runTickPatch,
  /scheduledPacketBoundaries != 1[\s\S]*processClientPacketsAtScheduledFrameBoundary[\s\S]*method\.instructions\.set\(scheduledPacketBoundary, frameBoundaryWrapper\)/,
  "the original scheduled PacketProcessor call is not uniquely replaced in place");
assert.match(runTickPatch,
  /wrapperCalls != 1 \|\| directPacketProcessorCalls != 0/,
  "the patcher does not fail closed against a second PLAY FIFO batch");

const packetProcessorPatch = memberText(patcherSource, "patchPacketProcessorBrowserSlice");
assert.ok(packetProcessorPatch, "the PacketProcessor patch method is missing");
assert.match(packetProcessorPatch,
  /"java\/util\/Queue", "poll", "\(\)Ljava\/lang\/Object;"/,
  "patched PacketProcessor no longer owns FIFO dequeue order through Queue.poll");

function scheduledFrameModel(initialDepth, {enabled = true, paused = false,
  handlerMillis = 0.01, failureAtHandler = 0, resetAtHandler = 0} = {}) {
  const fifo = Array.from({length: initialDepth}, (_, index) => index);
  const handled = [];
  const pressure = enabled && fifo.length >= 64;
  const mode = pressure ? (paused ? "critical" : "pressure") : "vanilla";
  const requestedPackets = pressure ? Math.max(1, fifo.length - 63) : 16;
  const batchLimit = pressure ? Math.min(256, requestedPackets) : 16;
  let elapsed = 0;
  let interrupted = false;
  while (handled.length < batchLimit && fifo.length > 0) {
    if (handled.length >= 1 && elapsed >= 2) break;
    const packet = fifo.shift();
    handled.push(packet);
    elapsed += handlerMillis;
    if (failureAtHandler === handled.length) {
      interrupted = true;
      break;
    }
    if (resetAtHandler === handled.length) {
      interrupted = true;
      fifo.length = 0;
      break;
    }
  }
  let stopReason = "normal-count";
  if (pressure) {
    if (interrupted) stopReason = "interrupted";
    else if (elapsed >= 2 && handled.length < batchLimit) stopReason = "deadline";
    else if (requestedPackets > 256 && handled.length === 256) stopReason = "hard-cap";
    else if (fifo.length <= 63) stopReason = "target";
    else stopReason = "interrupted";
  }
  return {
    mode,
    handled,
    remaining: fifo.length,
    calls: 1,
    elapsed,
    stopReason,
    targetQueue: pressure ? 63 : null,
    requestedPackets,
    remainingDebt: pressure ? Math.max(0, fifo.length - 63) : 0,
    handlerCompletions: handled.length,
    queueDepthReduction: initialDepth - fifo.length,
    unattributedQueueReduction: Math.max(
      0, initialDepth - fifo.length - handled.length),
  };
}

// A failed adaptive claim has two classes of outcome.  Transient queue/claim races are safe to
// service through the already-patched ordinary 16-packet FIFO path; active-drain and handler-depth
// are re-entrant and must leave the outer owner/handler state untouched.
function claimSkipFrameModel(reason, initialDepth = 80) {
  const safeRace = reason === "threshold-race" || reason === "claim-race";
  const reentrant = reason === "active-drain" || reason === "handler-depth";
  assert.ok(safeRace || reentrant, `unmodelled claim-skip reason: ${reason}`);
  if (reentrant) {
    return {
      reason,
      calls: 0,
      adaptiveClaimActive: reason === "active-drain",
      handled: [],
      remaining: initialDepth,
      handlerDepth: reason === "handler-depth" ? 1 : 0,
    };
  }
  const ordinary = scheduledFrameModel(initialDepth, {enabled: false});
  return {
    reason,
    calls: ordinary.calls,
    adaptiveClaimActive: false,
    handled: ordinary.handled,
    remaining: ordinary.remaining,
    handlerDepth: 0,
  };
}

for (const reason of ["threshold-race", "claim-race", "active-drain", "handler-depth"]) {
  const frame = claimSkipFrameModel(reason);
  assert.ok(frame.calls <= 1, `${reason} issued more than one PacketProcessor call`);
  assert.equal(new Set(frame.handled).size, frame.handled.length,
    `${reason} duplicated a FIFO packet`);
  if (reason === "threshold-race" || reason === "claim-race") {
    assert.equal(frame.calls, 1, `${reason} silently dropped the scheduled FIFO turn`);
    assert.deepEqual(frame.handled, Array.from({length: 16}, (_, index) => index),
      `${reason} changed ordinary FIFO order`);
    assert.equal(frame.adaptiveClaimActive, false,
      `${reason} incorrectly retained an adaptive drain claim`);
  } else {
    assert.equal(frame.calls, 0, `${reason} recursively entered PacketProcessor`);
    assert.equal(frame.remaining, 80, `${reason} mutated the outer FIFO state`);
  }
}

// A close/reset from inside a queued handler must preserve the handler guard even when the
// adaptive drain feature is disabled. Otherwise the still-running listener can re-enter the
// inline path and packetProcessed() loses its outer-scope completion accounting.
function queuedHandlerResetModel({drainActive = false, fixed = true} = {}) {
  let handleDepth = 0;
  let completionCount = 0;
  handleDepth++;
  const depthBeforeReset = handleDepth;
  const preserveActiveDrainEvidence = drainActive;
  if ((!fixed && !preserveActiveDrainEvidence) || handleDepth === 0) {
    handleDepth = 0;
  }
  const processingDuringHandler = handleDepth > 0;
  if (processingDuringHandler) {
    handleDepth--;
    if (handleDepth === 0) completionCount++;
  }
  return {drainActive, depthBeforeReset, processingDuringHandler, handleDepth,
    completionCount};
}

const legacyInactiveDrainReset = queuedHandlerResetModel({drainActive: false, fixed: false});
assert.equal(legacyInactiveDrainReset.processingDuringHandler, false,
  "regression model no longer demonstrates the pre-fix guard loss");
assert.equal(legacyInactiveDrainReset.completionCount, 0,
  "regression model unexpectedly retained completion after legacy reset");

const inactiveDrainReset = queuedHandlerResetModel({drainActive: false});
assert.equal(inactiveDrainReset.depthBeforeReset, 1);
assert.equal(inactiveDrainReset.processingDuringHandler, true,
  "default drain reset cleared the queued-handler guard while the handler was live");
assert.equal(inactiveDrainReset.handleDepth, 0);
assert.equal(inactiveDrainReset.completionCount, 1,
  "default drain reset lost the outer queued-handler completion");

const activeDrainReset = queuedHandlerResetModel({drainActive: true});
assert.equal(activeDrainReset.processingDuringHandler, true);
assert.equal(activeDrainReset.completionCount, 1);

// An owner can close its PacketProcessor from inside the active drain handler.  reset() clears the
// queue but deliberately preserves the outer drain claim until packetProcessed() and the wrapper's
// finally block unwind.  The owner/generation guard must release that exact retired claim without
// allowing a stale callback to finish a newer frame.
function activeDrainCloseModel() {
  const state = {
    packetProcessorOwner: null,
    packetProcessorGeneration: 0,
    clientPacketDrainActive: false,
    clientPacketDrainOwner: null,
    clientPacketDrainOwnerGeneration: 0,
    queuedPackets: 0,
    handlerOwner: null,
    handlerDepth: 0,
    handlerCompletions: 0,
    retiredOwners: new Set(),
    packetProcessorFallbackReason: "unbound",
  };
  const nextGeneration = generation => generation === Number.MAX_SAFE_INTEGER
    ? 1 : generation + 1;
  const bind = owner => {
    if (owner == null || state.retiredOwners.has(owner)) {
      return false;
    }
    if (state.packetProcessorOwner === null) {
      if (state.clientPacketDrainActive) {
        state.packetProcessorFallbackReason = "active-drain-owner-retiring";
        return false;
      }
      if (state.handlerDepth > 0 && state.handlerOwner !== owner) {
        state.packetProcessorFallbackReason = "owner-while-handler-active";
        return false;
      }
      state.packetProcessorOwner = owner;
      state.packetProcessorGeneration = nextGeneration(state.packetProcessorGeneration);
      state.packetProcessorFallbackReason = "bound";
      return true;
    }
    if (state.packetProcessorOwner !== owner) {
      state.packetProcessorFallbackReason = "packet-processor-owner-conflict";
      return false;
    }
    return true;
  };
  const claim = (owner, queuedPackets) => {
    if (!bind(owner) || state.clientPacketDrainActive || queuedPackets < 64) {
      return false;
    }
    state.queuedPackets = queuedPackets;
    state.clientPacketDrainActive = true;
    state.clientPacketDrainOwner = owner;
    state.clientPacketDrainOwnerGeneration = state.packetProcessorGeneration;
    state.handlerOwner = owner;
    state.handlerDepth = 1;
    state.handlerCompletions = 0;
    return true;
  };
  const reset = owner => {
    if (owner == null || state.packetProcessorOwner !== owner) {
      return false;
    }
    state.queuedPackets = 0;
    state.retiredOwners.add(owner);
    state.packetProcessorOwner = null;
    state.packetProcessorGeneration = nextGeneration(state.packetProcessorGeneration);
    // The active drain owner/generation survive until the handler and outer finally unwind.
    return true;
  };
  const packetProcessed = owner => {
    if (owner !== state.handlerOwner || state.handlerDepth <= 0) {
      return false;
    }
    state.handlerDepth--;
    state.handlerOwner = null;
    if (state.clientPacketDrainActive && owner === state.clientPacketDrainOwner) {
      state.handlerCompletions++;
    }
    // reset() already discarded the queued remainder; do not invent completions for it.
    if (state.queuedPackets > 0) {
      state.queuedPackets--;
    }
    return true;
  };
  const finish = owner => {
    if (owner == null || owner !== state.clientPacketDrainOwner
        || state.clientPacketDrainOwnerGeneration <= 0) {
      return false;
    }
    const savedGeneration = state.clientPacketDrainOwnerGeneration;
    const nextAfterSaved = nextGeneration(savedGeneration);
    const currentOwner = owner === state.packetProcessorOwner
      && state.packetProcessorGeneration === savedGeneration;
    const retiredOwnerAfterReset = state.packetProcessorOwner === null
      && state.packetProcessorGeneration === nextAfterSaved
      && state.retiredOwners.has(owner);
    if (!currentOwner && !retiredOwnerAfterReset) {
      return false;
    }
    state.clientPacketDrainActive = false;
    state.clientPacketDrainOwner = null;
    state.clientPacketDrainOwnerGeneration = 0;
    return true;
  };
  return {state, bind, claim, reset, packetProcessed, finish};
}

const closeDrain = activeDrainCloseModel();
const closeOwner = {};
const foreignOwner = {};
const newFrameOwner = {};
assert.equal(closeDrain.claim(closeOwner, 256), true,
  "active drain model failed to claim the initial owner");
assert.equal(closeDrain.reset(closeOwner), true,
  "active drain model failed to retire the owner from an in-handler reset");
assert.equal(closeDrain.state.queuedPackets, 0,
  "reset did not clear the queued remainder in the lifecycle model");
assert.equal(closeDrain.packetProcessed(closeOwner), true,
  "retired owner handler did not unwind after reset");
const reentrantBindResult = closeDrain.bind(newFrameOwner);
const reentrantBindRejected = reentrantBindResult === false;
const reentrantBindFallbackReason = closeDrain.state.packetProcessorFallbackReason;
assert.equal(reentrantBindResult, false,
  "new owner rebound while retired active drain was awaiting outer finish");
assert.equal(reentrantBindFallbackReason, "active-drain-owner-retiring",
  "reentrant bind did not record the active-drain retirement fallback");
assert.equal(closeDrain.finish(closeOwner), true,
  "retired owner could not release its exact active drain claim");
assert.equal(closeDrain.state.clientPacketDrainActive, false,
  "reset -> packetProcessed -> finish left the active drain stuck");
assert.equal(closeDrain.state.handlerCompletions, 1,
  "queue reset fabricated or lost the one handler completion");
assert.equal(closeDrain.state.queuedPackets, 0,
  "queue reset was incorrectly decremented as a packet completion");
const closeDrainHandlerCompletions = closeDrain.state.handlerCompletions;
const closeDrainQueueAfterReset = closeDrain.state.queuedPackets;
assert.equal(closeDrain.claim(newFrameOwner, 256), true,
  "a fresh owner could not claim a new frame after the old drain finished");
assert.equal(closeDrain.finish(foreignOwner), false,
  "a foreign owner finished a newer active drain");
assert.equal(closeDrain.state.clientPacketDrainActive, true,
  "foreign finish incorrectly cleared the newer active drain");
assert.equal(closeDrain.finish(closeOwner), false,
  "a stale retired owner finished a newer active drain");
assert.equal(closeDrain.state.clientPacketDrainActive, true,
  "stale finish incorrectly cleared the newer active drain");
assert.equal(closeDrain.finish(newFrameOwner), true,
  "the new frame owner could not finish its own generation");

const vanillaDisabled = scheduledFrameModel(256, {enabled: false});
assert.equal(vanillaDisabled.mode, "vanilla");
assert.equal(vanillaDisabled.calls, 1);
assert.equal(vanillaDisabled.handled.length, 16);
const vanillaBelow = scheduledFrameModel(63, {enabled: true});
assert.equal(vanillaBelow.mode, "vanilla");
assert.equal(vanillaBelow.handled.length, 16);
const pressure = scheduledFrameModel(256);
assert.equal(pressure.mode, "pressure");
assert.equal(pressure.handled.length, 193);
assert.deepEqual(pressure.handled, Array.from({length: 193}, (_, index) => index),
  "work-conserving pressure drain changed FIFO order");
assert.equal(pressure.remaining, 63);
assert.equal(pressure.stopReason, "target");
assert.ok(Math.abs(pressure.elapsed - 1.93) < 1e-9);
const pressureEdge = scheduledFrameModel(64);
assert.equal(pressureEdge.handled.length, 1);
assert.equal(pressureEdge.remaining, 63);
const critical = scheduledFrameModel(256, {paused: true});
assert.equal(critical.mode, "critical");
assert.equal(critical.handled.length, 193);
assert.equal(critical.stopReason, "target");
const timeBound = scheduledFrameModel(256, {handlerMillis: 0.25});
assert.equal(timeBound.handled.length, 8,
  "the count-ceiling fix bypassed the existing two-millisecond deadline");
assert.equal(timeBound.stopReason, "deadline");
assert.equal(timeBound.remaining, 248);
const firstPressureTurn = scheduledFrameModel(103);
assert.equal(firstPressureTurn.remaining, 63);
const afterIngress = scheduledFrameModel(firstPressureTurn.remaining + 53);
assert.equal(afterIngress.remaining, 63,
  "103 + 53 packet recurrence still grows under cheap-packet pressure");
assert.equal(afterIngress.handled.length, 53);
const hardCapped = scheduledFrameModel(400, {handlerMillis: 0.001});
assert.equal(hardCapped.handled.length, 256);
assert.equal(hardCapped.stopReason, "hard-cap");
assert.equal(hardCapped.remainingDebt, 81);
const oneSlowPacket = scheduledFrameModel(80, {handlerMillis: 3});
assert.equal(oneSlowPacket.handled.length, 1,
  "the 2ms budget must still guarantee one packet before yielding");
assert.equal(oneSlowPacket.stopReason, "deadline");
assert.ok(oneSlowPacket.elapsed < 50,
  "a single modeled queued handler crossed the unchanged 50ms safety gate");
const q64HandlerFailure = scheduledFrameModel(64, {failureAtHandler: 1});
assert.equal(q64HandlerFailure.handlerCompletions, 1);
assert.equal(q64HandlerFailure.remaining, 63);
assert.equal(q64HandlerFailure.remainingDebt, 0);
assert.equal(q64HandlerFailure.stopReason, "interrupted",
  "q64 handler failure was inferred as successful from debt zero");
const midDrainReset = scheduledFrameModel(256, {resetAtHandler: 4});
assert.equal(midDrainReset.handlerCompletions, 4);
assert.equal(midDrainReset.queueDepthReduction, 256);
assert.equal(midDrainReset.unattributedQueueReduction, 252);
assert.equal(midDrainReset.stopReason, "interrupted");
assert.notEqual(midDrainReset.handlerCompletions, midDrainReset.queueDepthReduction,
  "mid-drain reset still overstates handlers from queueBefore - queueAfter");

// Exercise the diagnostic classifier and its JS bucket bridge as a pure model. This keeps
// diagnostics side-effect free and makes every bounded reason observable without requiring a
// TeaVM build just to test precedence or counter accounting.
const claimClassifier = between(
  schedulerSource,
  "public static String clientPacketDrainClaimSkipReason(Object owner)",
  "/** Marks an exceptional or reset-aborted active drain",
);
assert.doesNotMatch(claimClassifier,
  /^\s+(?:packetProcessor|clientPacketDrain)\w*\s*=(?!=)/m,
  "claim-skip classifier must not mutate scheduler state");
const skipReasonScript = jsBodyBefore(
  networkSource,
  "private static native void recordClientPacketDrainJavaSkipped(String reason);",
);
new vm.Script(`(function(reason) {${skipReasonScript}\n})`);
const skipReasonCases = [
  ["worker-server", "workerServer"],
  ["null-owner", "nullOwner"],
  ["owner-conflict", "ownerConflict"],
  ["retired-owner", "retiredOwner"],
  ["active-drain", "activeDrain"],
  ["handler-depth", "handlerDepth"],
  ["threshold-race", "thresholdRace"],
  ["claim-race", "claimRace"],
  ["unknown-owner", "unknown"],
];
for (const [reason, bucket] of skipReasonCases) {
  const sandbox = {};
  sandbox.globalThis = sandbox;
  sandbox.__gaiusNetworkStats = {};
  vm.runInNewContext(`(function(reason) {${skipReasonScript}\n})(${JSON.stringify(reason)})`, sandbox);
  const stats = sandbox.__gaiusNetworkStats;
  assert.equal(stats.clientPacketDrainJavaSkipped, 1,
    `${reason} did not increment the total skipped counter`);
  assert.equal(stats.clientPacketDrainLastSkipReason, reason,
    `${reason} was not retained as the latest skip reason`);
  const bucketTotal = [
    "activeDrain", "handlerDepth", "ownerConflict", "thresholdRace", "retiredOwner",
    "workerServer", "nullOwner", "claimRace", "unknown",
  ].reduce((total, name) => total + (stats[
    `clientPacketDrainClaimSkipped${name[0].toUpperCase()}${name.slice(1)}`
  ]|0), 0);
  assert.equal(stats[
    `clientPacketDrainClaimSkipped${bucket[0].toUpperCase()}${bucket.slice(1)}`
  ]|0, 1, `${reason} mapped to the wrong diagnostic bucket`);
  assert.equal(bucketTotal, 1, `${reason} incremented more than one diagnostic bucket`);
}
const claimSkipReasonCases = skipReasonCases.length;

console.log(JSON.stringify({
  status: "client-packet-drain-smoke-passed",
  boundaryCallsPerFrame: 1,
  threshold: 64,
  pressureTargetPackets: pressure.handled.length,
  criticalTargetPackets: critical.handled.length,
  timeBoundPackets: timeBound.handled.length,
  hardMaximumPackets: hardCapped.handled.length,
  recurrenceAfterIngress: afterIngress.remaining,
  q64FailureHandlerCompletions: q64HandlerFailure.handlerCompletions,
  midDrainResetHandlerCompletions: midDrainReset.handlerCompletions,
  midDrainResetQueueDepthReduction: midDrainReset.queueDepthReduction,
  inactiveDrainResetPreservesHandler: inactiveDrainReset.processingDuringHandler,
  inactiveDrainResetCompletions: inactiveDrainReset.completionCount,
  activeDrainCloseResetRelease: true,
  activeDrainCloseHandlerCompletions: closeDrainHandlerCompletions,
  activeDrainCloseQueueAfterReset: closeDrainQueueAfterReset,
  reentrantBindRejected,
  reentrantBindFallbackReason,
  activeDrainCloseFreshFrameClaimed: true,
  activeDrainCloseForeignFinishRejected: true,
  activeDrainCloseStaleFinishRejected: true,
  vanillaMaximumPackets: vanillaDisabled.handled.length,
  budgetMillis: 2,
  passiveDemandSignals: stats.clientPacketDrainDemandSignals,
  diagnosticDomReports: diagnosticStats.inboundPumpDomReports,
  diagnosticDomReportsCoalesced: diagnosticStats.inboundPumpDomReportCoalesced,
  diagnosticDomReportFlushes: diagnosticStats.inboundPumpDomReportFlushes,
  diagnosticDomReportBytes: diagnosticStats.inboundPumpDomReportBytes,
  externalDrainJavaCalls: 0,
  rawPumpMessageChannelRetained: true,
  boundaryEventsRetained: evidenceStats.frameBoundaryDrainEvents.length,
  boundaryEventsDropped: evidenceStats.frameBoundaryDrainEventsDropped,
  frameEventsRetained: evidenceStats.clientPacketFrameEvents.length,
  frameEventsDropped: evidenceStats.clientPacketFrameEventsDropped,
  claimSkipFallbackModel: true,
  vanillaFallbackEvents: evidenceStats.frameBoundaryDrainVanillaFallback,
  claimSkipReasonCases,
  claimSkipReasonBucketsComplete: true,
  bindFailureReasonEvidence: true,
  sessionSkipReasonReset: true,
  remoteSessionDrainPromotion: true,
  explicitUrlOptOutPreserved: remoteDrainPolicyModel("?gaiusClientPacketDrain=0", undefined) === false,
  embeddingBooleanPreserved: remoteDrainPolicyModel("?gaiusClientPacketDrain=1", false) === false,
  localSingleplayerAddressExcludedByRecoveryGuard: true,
}));
