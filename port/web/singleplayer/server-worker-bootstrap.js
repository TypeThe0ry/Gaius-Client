"use strict";

const root = globalThis;
// Manual Chrome diagnostics may opt into the Mob AI pulse counter by adding
// `gaiusMobAiTelemetry=1` to the launcher URL.  Production pages leave this
// unset, so the checkpoint remains a cheap scheduler call with no telemetry
// object churn.
try {
  const diagnosticUrl = new URL(String(root.location?.href || ""), "http://gaius.invalid/");
  root.__gaiusMobAiTelemetry = root.__gaiusMobAiTelemetry === true ||
    diagnosticUrl.searchParams.get("gaiusMobAiTelemetry") === "1";
  root.__gaiusServerTickTelemetryEnabled =
    root.__gaiusServerTickTelemetryEnabled === true ||
    diagnosticUrl.searchParams.get("gaiusServerTickTelemetry") === "1";
  root.__gaiusSlowProbeTelemetryEnabled =
    root.__gaiusSlowProbeTelemetryEnabled === true;
} catch (_) {
  root.__gaiusMobAiTelemetry = false;
  root.__gaiusServerTickTelemetryEnabled = false;
  root.__gaiusSlowProbeTelemetryEnabled = false;
}
if (typeof Error === "function" && (!Error.stackTraceLimit || Error.stackTraceLimit < 100)) {
  Error.stackTraceLimit = 100;
}
const storageProfiles = Object.freeze({
  "1.21.11": Object.freeze({
    worldVersion: 4671,
    storageSchema: 2,
    storageDatabaseName: "gaius-fs-v2-1.21.11",
    storagePrefix: "gaius.fs.v2:1.21.11:",
    storageOpfsDirectory: "regions-v2-1.21.11",
  }),
  "26.2": Object.freeze({
    worldVersion: 4903,
    storageSchema: 2,
    storageDatabaseName: "gaius-fs-v2-26.2",
    storagePrefix: "gaius.fs.v2:26.2:",
    storageOpfsDirectory: "regions-v2-26.2",
  }),
  "26.3": Object.freeze({
    worldVersion: 5023,
    storageSchema: 2,
    storageDatabaseName: "gaius-fs-v2-26.3",
    storagePrefix: "gaius.fs.v2:26.3:",
    storageOpfsDirectory: "regions-v2-26.3",
  }),
});
const storeName = "files";
const defaultWorldgenSliceMillis = 8;
const defaultDistanceRampIntervalMillis = 750;
const defaultRegionCacheBudgetBytes = 32 * 1024 * 1024;
// Without OPFS (Chrome refuses it on file:// pages, i.e. every portable Gaius.html) the
// region cache is the only synchronous copy of each saved region, so it has to hold the
// whole world: 32 MiB stopped a portable world from opening again after ~15 minutes of
// exploring. An explicitly configured budget still applies unchanged.
const defaultIndexedDbFallbackRegionBudgetBytes = 256 * 1024 * 1024;
const minimumRegionCacheBudgetBytes = 64 * 1024;
const maximumRegionCacheBudgetBytes = 256 * 1024 * 1024;
const opfsRecordMagic = 0x47525331;
const opfsRecordVersion = 1;
const opfsRecordHeaderBytes = 24;
const opfsRecordLive = 1;
const opfsRecordDeleted = 2;
const opfsPatchRecordMagic = 0x47525332;
const opfsPatchRecordVersion = 2;
const opfsPatchRecordHeaderBytes = 48;
const opfsPatchRecordState = 3;
const opfsPatchCommitMagic = 0x434f4d54;
const maximumOpfsPatchRanges = 64;
const maximumOpfsPatchChainRecords = 64;
const maximumOpfsPatchChainBytes = 8 * 1024 * 1024;
const fileValues = Object.create(null);
const regionIndex = new Map();
// Pair commit sequences are an optional V2 metadata layer.  They are kept
// separate from the per-path generation clock so frozen readers continue to
// see the exact legacy sequence they validate.
const regionPairSequences = new Map();
let pairCommitSequenceClock = 0;
const regionCache = new Map();
const onDemandRegionLoads = new Map();
const regionCacheBudgetConfigured = root.__gaiusRegionCacheBudgetBytes != null &&
  Number.isFinite(Number(root.__gaiusRegionCacheBudgetBytes));
let regionCacheBudgetBytes = clampRegionCacheBudget(
  root.__gaiusRegionCacheBudgetBytes,
);
const storageStats = root.__gaiusStorageStats = {
  backend: "initializing",
  cacheBudgetBytes: regionCacheBudgetBytes,
  cacheBytes: 0,
  cachePeakBytes: 0,
  cacheEntries: 0,
  regionEntries: 0,
  dirtyEntries: 0,
  pinnedEntries: 0,
  flushingEntries: 0,
  evictions: 0,
  cacheHits: 0,
  cacheMisses: 0,
  rejectedWrites: 0,
  writeErrors: 0,
  migratedRegions: 0,
  opfsFileBytes: 0,
  opfsFullWrites: 0,
  opfsFullWriteBytes: 0,
  opfsPatchWrites: 0,
  opfsPatchPayloadBytes: 0,
  opfsPatchRanges: 0,
  opfsPatchCheckpoints: 0,
  opfsReconstructedRegions: 0,
  opfsFlushes: 0,
  opfsFlushMillis: 0,
  opfsMaxFlushMillis: 0,
  recoveredRegionConflicts: 0,
  opfsScanRecords: 0,
  opfsScanV1Records: 0,
  opfsScanV2Records: 0,
  flushTimeouts: 0,
  flushAbortRequests: 0,
  flushAbortTimeouts: 0,
};
const files = root.__gaiusPersistentFiles = createPersistentFileProxy();
let database;
const pendingChanges = new Map();
let regionCacheBytes = 0;
let regionCacheSequence = 0;
let flushInFlight;
let flushTimer;
let opfsAccessHandle;
let opfsAppendOffset = 0;
let opfsDirty = false;
let opfsCrcTable;
let persistentStorageClosed = false;
let storageGeneration = 0;
let storageStartupInFlight;
let storageClosePromise;
let storageHandlesClosed = false;
let activeFlushTransaction;
let flushAbortRequested = false;
let stopPromise;
let startupCancelResolve;
let startupCancelPromise;
let storageFlushFailureReported = false;
let runtimeStarted = false;
let stopRequested = false;
let stopping = false;
// A lifecycle save is queued on the Java server thread. Do not let a following
// stop halt that thread before the queued save has run; that loses the last
// block edit on a fast document reload.
let saveAllInFlight = false;
let stopAfterSaveAll = false;
let saveAllStopTimer;
let runtimeLeaseSaveChannel;
let runtimeLeaseSaveId = "";
let runtimeLeaseSaveWorkerId = "";
let runtimeLeaseSaveHeartbeat;
let startAccepted = false;
let activeSessionId = "";
// A title-screen/page idle pass may compile the server runtime and run the
// idempotent vanilla bootstrap before the real start handshake.  It never
// owns storage or WorldLoader state; those remain on the start path.
let prewarmAccepted = false;
let prewarmPromise;
let prewarmScript;
let prewarmRuntimeInstantiated = false;
let prewarmRuntimeFailed = false;
// An idle Worker's vanilla Main that runs up to Bootstrap and waits for the start handshake.
let parkedMainDispatched = false;
// The Java side reported that its main thread cannot park on this runtime: never ask again.
let parkedMainUnavailable = false;
let runtimeReleaseInProgress = false;

const gaiusPostMessage = root.postMessage.bind(root);
root.postMessage = (message, ...transfer) => {
  const type = message && message.type;
  if (type === "save-all-complete" || type === "save-all-failed") {
    saveAllInFlight = false;
    clearTimeout(saveAllStopTimer);
    saveAllStopTimer = undefined;
    const stop = stopAfterSaveAll;
    stopAfterSaveAll = false;
    if (stop) {
      // Let the report reach the host before starting the final stop sequence.
      queueMicrotask(() => requestStop());
    }
  }
  return gaiusPostMessage(message, ...transfer);
};
const lanServerConnections = createLanServerConnections({
  channels: () => root.__gaiusNettyBridge?.channels,
  open: brokerSessionId => typeof openLanServerConnection === "function" &&
    openLanServerConnection(brokerSessionId) === true,
  isStopped: () => stopRequested || stopping,
  notify: (event, sessionId, detail) => postMessage({
    type: "lan-connection", event, brokerSessionId: sessionId, detail,
  }),
});
let startupPort;
let pendingPortResolve;
let pendingPortReject;
let pendingPortTimer;
// The shipped integrated server is WebAssembly GC only.
let serverRuntime = "";

function clampRegionCacheBudget(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return defaultRegionCacheBudgetBytes;
  }
  return Math.max(
    minimumRegionCacheBudgetBytes,
    Math.min(maximumRegionCacheBudgetBytes, Math.floor(parsed)),
  );
}

function createPersistentFileProxy() {
  if (typeof Proxy !== "function") {
    return fileValues;
  }
  return new Proxy(fileValues, {
    get(target, property, receiver) {
      if (typeof property === "string" && regionIndex.has(property)) {
        const loaded = onDemandRegionLoads.get(property);
        if (loaded && loaded.value !== undefined) return loaded.value;
        const hydrated = root.__gaiusIndexedDbHydratedRegions;
        if (hydrated && hydrated.has(property)) return hydrated.get(property);
        return readCachedRegion(property);
      }
      return Reflect.get(target, property, receiver);
    },
    has(target, property) {
      return typeof property === "string" && regionIndex.has(property)
        ? true
        : Reflect.has(target, property);
    },
    ownKeys(target) {
      const keys = Reflect.ownKeys(target);
      for (const path of regionIndex.keys()) {
        if (!Object.prototype.hasOwnProperty.call(target, path)) {
          keys.push(path);
        }
      }
      return keys;
    },
    getOwnPropertyDescriptor(target, property) {
      if (typeof property === "string" && regionIndex.has(property) &&
          !Object.prototype.hasOwnProperty.call(target, property)) {
        return {configurable: true, enumerable: true};
      }
      return Reflect.getOwnPropertyDescriptor(target, property);
    },
  });
}

function monotonicMillis() {
  return typeof performance !== "undefined" && performance.now
    ? performance.now()
    : Date.now();
}

const networkTelemetryPriorityKeys = Object.freeze([
  "errors",
  "queuedBytes",
  "inboundQueuedBytes",
  "peakInboundQueuedBytes",
  "inboundSlices",
  "inboundSlicePumps",
  "maxInboundSliceQueue",
  "longestInboundSlicePumpMillis",
  "decodedSliceBacklog",
  "maxDecodedSliceBacklog",
  "decoderCumulationBytes",
  "maxDecoderCumulationBytes",
  "decodedPacketQueue",
  "maxDecodedPacketQueue",
  "decodedPacketDrainSignals",
  "queuedPacketHandleSamples",
  "maxQueuedPacketHandleMillis",
  "maxQueuedPacketHandleType",
  "inlineDecodedPackets",
  "sentFrames",
  "sentBytes",
  "receivedFrames",
  "receivedBytes",
  "pumpCalls",
  "pumpChunks",
  "pumpBytes",
  "peakPumpChunks",
  "peakPumpBytes",
  "peakPumpMillis",
  "longestPumpMillis",
  "eventLoopGapSamples",
  "eventLoopGapsOver500",
  "longestEventLoopGapMillis",
  "activeHighWatermarks",
  "highWatermarkDurationMillis",
  "longestHighWatermarkMillis",
  "activeHighWatermarkMillis",
  "localFlushes",
  "localFlushFrames",
  "localFlushBytes",
  "localReceivedFrames",
  "localReceivedBytes",
  "outboundTurns",
  "outboundTurnFrames",
  "outboundTurnBytes",
  "maxOutboundTurnFrames",
  "maxOutboundTurnBytes",
  "maxOutboundTurnMillis",
  "outboundYields",
  "outboundBackpressureDeferrals",
  "integratedServerPumpFailures",
  "integratedServerPumpRequests",
  "integratedServerPumpStarts",
  "integratedServerPumpRetrySchedules",
  "integratedServerPumpRetryExhaustions",
  "integratedServerTaskSignals",
  "integratedServerTaskUnparks",
  "integratedServerTaskCoalesced",
  "integratedServerTaskSchedules",
  "integratedServerTaskScheduleFailures",
  "integratedServerTaskRuns",
  "integratedServerTaskFollowups",
  "integratedServerTaskLifecycleDrops",
  "integratedServerTaskWrongThread",
  "integratedServerTaskBudgetExhaustions",
  "integratedServerTaskDeferredRetries",
  "integratedServerTaskRetryExhaustions",
  "integratedServerTaskStaleGenerations",
  "integratedServerTaskPermitMissing",
  "integratedServerTaskPumpBusy",
  "integratedServerTaskPending",
  "integratedServerInputPending",
]);

// A measurement window must not mutate the live network/storage state: the
// scheduler reads queue depths and pending flags from these objects while the
// benchmark is running.  Only known monotonic counters are baselined below;
// gauges, extrema, and configuration limits are always returned as raw live
// values so their meaning is not changed by subtraction.
const networkTelemetryCounterKeys = Object.freeze([
  "opened", "localOpened", "directAttempts", "directConnected",
  "directPluginCachedMisses", "relayAttempts", "relayFailovers",
  "relayPreflights", "relayPreflightSuccesses", "relayPreflightFailures",
  "relayPreflightCacheHits", "relayRegistryRequests", "relayRegistrySuccesses",
  "relayRegistryFailures", "relayRegistryCacheHits", "relayRegistryNodesLoaded",
  "relayRegistryRegistriesLoaded", "relayTargetActiveSelections",
  "relayTargetRecentSelections", "relayTargetLocalActiveSelections",
  "relayTargetLocalRecentSelections", "relayTargetLeaseAcquires",
  "relayTargetLeaseReleases", "relayNodeSuccesses", "relayNodeFailures",
  "relayTargetAttestationFailures", "closed", "sentFrames", "sentBytes",
  "receivedFrames", "receivedBytes", "inboundSlices", "inboundSlicePumps",
  "decodedSliceBacklogPauses", "decodedSliceBacklogResumes",
  "decodedPacketQueuePauses", "decodedPacketQueueResumes",
  "decodedPacketDrainSignals", "queuedPacketHandleSamples", "inlineDecodedPackets",
  "flowPauses", "flowResumes", "localFlushes",
  "localFlushFrames", "localFlushBytes", "localReceivedFrames",
  "localReceivedBytes", "localClaimWaits", "localClaimRetries",
  "localClaimTimeouts", "localDuplicateOpens", "localSupersededClaims",
  "outboundTurns", "outboundTurnFrames", "outboundTurnBytes", "outboundYields",
  "outboundBackpressureDeferrals", "controlQueueOverflows",
  "webSocketBackpressureWaits", "localMessagePortSends", "webSocketSends",
  "pumpCalls", "pumpChunks", "pumpBytes", "deferredPumps",
  "eventLoopGapSamples", "eventLoopGapsOver500", "errors",
  "integratedServerPumpFailures", "integratedServerPumpRequests",
  "integratedServerPumpStarts", "integratedServerPumpRetrySchedules",
  "integratedServerPumpRetryExhaustions", "integratedServerTaskSignals",
  "integratedServerTaskUnparks", "integratedServerTaskCoalesced",
  "integratedServerTaskSchedules", "integratedServerTaskScheduleFailures",
  "integratedServerTaskRuns", "integratedServerTaskFollowups",
  "integratedServerTaskLifecycleDrops", "integratedServerTaskWrongThread",
  "integratedServerTaskBudgetExhaustions", "integratedServerTaskDeferredRetries",
  "integratedServerTaskRetryExhaustions", "integratedServerTaskStaleGenerations",
  "integratedServerTaskPermitMissing", "integratedServerTaskPumpBusy",
]);
// Global pump counters are populated by BrowserWebSocketChannel.pumpAll* and
// live after the 72-key scalar network snapshot. Keep this list deliberately
// fixed and shallow so the aggregate cannot be dropped when the network stats
// object grows with additional transport diagnostics.
const globalPumpTelemetryKeys = Object.freeze([
  "pumpAllTurns",
  "pumpAllChannelsVisited",
  "pumpAllBudgetYields",
  "pumpAllMaxTurnMillis",
  "pumpAllMaxChannelsPerTurn",
  "pumpAllLastTurnMillis",
  "pumpAllLastChannelsVisited",
]);
const storageTelemetryCounterKeys = Object.freeze([
  "evictions", "cacheHits", "cacheMisses", "rejectedWrites", "writeErrors",
  "migratedRegions", "opfsFullWrites", "opfsFullWriteBytes", "opfsPatchWrites",
  "opfsPatchPayloadBytes", "opfsPatchRanges", "opfsPatchCheckpoints",
  "opfsReconstructedRegions", "opfsFlushes", "opfsFlushMillis",
  "opfsScanRecords", "opfsScanV1Records", "opfsScanV2Records",
  "flushTimeouts", "flushAbortRequests", "flushAbortTimeouts",
]);
let telemetryMeasurementInitialized = false;
let telemetryMeasurementId = "";
let telemetryWorkerResetAt = 0;
let telemetryNetworkBaseline;
let telemetryStorageBaseline;

function snapshotScalarTelemetry(value, priorityKeys = []) {
  const snapshot = Object.create(null);
  if (!value || typeof value !== "object") {
    return snapshot;
  }
  let copied = 0;
  const copy = (key) => {
    if (copied >= 72 || Object.prototype.hasOwnProperty.call(snapshot, key)) {
      return;
    }
    let current;
    try {
      current = value[key];
    } catch (_) {
      return;
    }
    if (typeof current === "number") {
      if (Number.isFinite(current)) {
        snapshot[key] = current;
        copied++;
      }
    } else if (typeof current === "boolean" || typeof current === "string" || current === null) {
      snapshot[key] = current;
      copied++;
    }
  };
  for (const key of priorityKeys) {
    copy(key);
  }
  for (const key of Object.keys(value)) {
    copy(key);
    if (copied >= 72) {
      break;
    }
  }
  return snapshot;
}

function snapshotGlobalPumpTelemetry(value) {
  const snapshot = Object.create(null);
  for (const key of globalPumpTelemetryKeys) {
    const current = value && typeof value === "object" ? value[key] : undefined;
    snapshot[key] = typeof current === "number" && Number.isFinite(current)
      ? current
      : null;
  }
  return snapshot;
}

// Worldgen diagnostics contain one intentionally nested object for the
// PlayerChunkSender state.  The generic scalar copier is deliberately
// shallow, so keep this side-band snapshot explicit; otherwise `chunkSender`
// is silently discarded before it reaches the page and a `selected == 0`
// sender stall is indistinguishable from an idle sender.
const chunkSenderTelemetryKeys = Object.freeze([
  "telemetryVersion", "pending", "selected", "unacknowledgedBatches",
  "playerChunkX", "playerChunkZ", "maxPending", "maxUnacknowledgedBatches",
  "entryCount", "selectedCount", "batchCount", "readySelectedChunks",
]);

function snapshotChunkSenderTelemetry(value) {
  const snapshot = Object.create(null);
  if (!value || typeof value !== "object") {
    return snapshot;
  }
  for (const key of chunkSenderTelemetryKeys) {
    const current = value[key];
    if (typeof current === "number" && Number.isFinite(current)) {
      snapshot[key] = current;
    } else if (typeof current === "boolean" || typeof current === "string" || current === null) {
      snapshot[key] = current;
    }
  }
  if (value.last && typeof value.last === "object") {
    const last = Object.create(null);
    for (const key of chunkSenderTelemetryKeys) {
      const current = value.last[key];
      if (typeof current === "number" && Number.isFinite(current)) {
        last[key] = current;
      } else if (typeof current === "boolean" || typeof current === "string" || current === null) {
        last[key] = current;
      }
    }
    snapshot.last = last;
  }
  return snapshot;
}

function snapshotWorldgenTelemetry(value) {
  const snapshot = snapshotScalarTelemetry(value, [
    "mobAiPulses",
    "mobAiMaxPulses",
    "trackingMoveStarts",
    "trackingMoveInFlightSkips",
    "trackingMoveCompletions",
    "trackingMovePending",
  ]);
  if (value && typeof value === "object" && value.chunkSender &&
      typeof value.chunkSender === "object") {
    snapshot.chunkSender = snapshotChunkSenderTelemetry(value.chunkSender);
  }
  // Worldgen dispatcher turn/hop counters are nested so they never displace the
  // flat slice fields from the capped scalar snapshot above.
  if (value && typeof value === "object" && value.dispatcher &&
      typeof value.dispatcher === "object") {
    snapshot.dispatcher = snapshotScalarTelemetry(value.dispatcher);
  }
  return snapshot;
}

// Slow-probe snapshots are requested explicitly and must stay small enough
// for a diagnostic message to cross the Worker boundary predictably. Reuse
// the scalar-only copier, then bound strings and the serialized object. This
// never walks nested values or evaluates data supplied by the runtime.
const diagnosticSnapshotMaxChars = 4096;
const diagnosticSnapshotMaxStringLength = 256;
const diagnosticSnapshotContextMaxStringLength = 1024;

function snapshotDiagnosticTelemetry(value, priorityKeys = []) {
  const snapshot = snapshotScalarTelemetry(value, priorityKeys);
  for (const key of Object.keys(snapshot)) {
    if (typeof snapshot[key] === "string") {
      const maxLength = ["maxSliceContext", "maxTaskContext", "chunkHolderProbeMaxContext"]
        .includes(key)
        ? diagnosticSnapshotContextMaxStringLength
        : diagnosticSnapshotMaxStringLength;
      snapshot[key] = snapshot[key].slice(0, maxLength);
    }
  }
  if (JSON.stringify(snapshot).length <= diagnosticSnapshotMaxChars) {
    return snapshot;
  }
  // Object.keys() preserves the priority-first insertion order produced by
  // snapshotScalarTelemetry. Drop the tail until the byte bound is met.
  const keys = Object.keys(snapshot);
  while (keys.length > 0 && JSON.stringify(snapshot).length > diagnosticSnapshotMaxChars) {
    delete snapshot[keys.pop()];
  }
  return snapshot;
}

function globalPumpTelemetrySource() {
  const bridge = root.__gaiusNettyBridge;
  const bridgeStats = bridge && bridge.stats;
  return bridgeStats && typeof bridgeStats === "object"
    ? bridgeStats
    : root.__gaiusNetworkStats;
}

function snapshotMeasurementTelemetry(value, baseline, priorityKeys, counterKeys) {
  const snapshot = snapshotScalarTelemetry(value, priorityKeys);
  const previous = baseline || Object.create(null);
  const counters = new Set(counterKeys || []);
  for (const key of Object.keys(snapshot)) {
    const current = snapshot[key];
    if (typeof current !== "number" || !counters.has(key)) {
      continue;
    }
    const before = Number(previous[key]);
    snapshot[key] = Number.isFinite(before)
      ? Math.max(0, current - before)
      : current;
  }
  return snapshot;
}

function createChunkPriorityTelemetryStats() {
  return {
    playerUpdates: 0,
    pops: 0,
    reorderedPops: 0,
    scannedCandidates: 0,
    maxCandidates: 0,
  };
}

function observeTelemetryMeasurement(value) {
  const measurementId = typeof value === "string"
    ? value
    : String(value == null ? "" : value);
  if (telemetryMeasurementInitialized && telemetryMeasurementId === measurementId) {
    return false;
  }
  telemetryMeasurementInitialized = true;
  telemetryMeasurementId = measurementId;
  root.__gaiusTelemetryMeasurementId = measurementId;
  telemetryWorkerResetAt = Math.max(Date.now(), telemetryWorkerResetAt + 1);
  root.__gaiusTelemetryWorkerResetAt = telemetryWorkerResetAt;

  // These two objects are telemetry-only.  Replacing them (rather than
  // clearing the network bridge) keeps the scheduler's live references and
  // queue/backpressure decisions intact while dropping startup samples.
  // Mob-AI pulses are deliberately carried across measurement windows: the
  // LivingEntity checkpoint is a high-frequency runtime signal and resetting
  // it here made a healthy hook look inactive in the final snapshot.  Other
  // worldgen counters remain windowed as before.
  const previousWorldgenStats = root.__gaiusWorldgenStats;
  const previousMobAiPulses = root.__gaiusMobAiTelemetry === true
    ? Number(previousWorldgenStats?.mobAiPulses) || 0
    : 0;
  const previousMobAiMaxPulses = root.__gaiusMobAiTelemetry === true
    ? Number(previousWorldgenStats?.mobAiMaxPulses) || 0
    : 0;
  root.__gaiusWorldgenStats = {};
  root.__gaiusServerTickTelemetry = {};
  if (previousMobAiPulses > 0) {
    root.__gaiusWorldgenStats.mobAiPulses = previousMobAiPulses;
  }
  if (previousMobAiMaxPulses > 0) {
    root.__gaiusWorldgenStats.mobAiMaxPulses = previousMobAiMaxPulses;
  }
  root.__gaiusChunkPriorityStats = createChunkPriorityTelemetryStats();

  // Network and storage objects contain scheduler-visible gauges.  Leave the
  // objects untouched and subtract this baseline only when making pong
  // snapshots so a new measurement window cannot contaminate cumulative
  // counters without changing runtime behavior.
  telemetryNetworkBaseline = snapshotScalarTelemetry(
    root.__gaiusNetworkStats,
    networkTelemetryPriorityKeys,
  );
  telemetryStorageBaseline = snapshotScalarTelemetry(storageStats);
  root.__gaiusTelemetryNetworkBaseline = telemetryNetworkBaseline;
  root.__gaiusTelemetryStorageBaseline = telemetryStorageBaseline;
  return true;
}

function clampWorldgenSlice(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 4 && parsed <= 50
    ? parsed
    : fallback;
}

function clampDistanceRampInterval(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 100 && parsed <= 2000
    ? Math.round(parsed)
    : fallback;
}

function markStartup(phase, startedAt) {
  const elapsedMillis = Math.max(0, monotonicMillis() - startedAt);
  postMessage({
    type: "startup-timing",
    phase,
    elapsedMillis,
    at: Date.now(),
  });
}

function releaseFailedRuntime() {
  if (runtimeReleaseInProgress || root.__gaiusServerRuntimeReleased === true) return false;
  runtimeReleaseInProgress = true;
  root.__gaiusServerRuntimeReleased = true;
  const wasm = root.GaiusWasmWorker;
  let tornDown = false;
  // The runtime finalizer owns JSPI continuations and the scheduler. Call exactly one lifecycle
  // API when it exists; the scheduler-only fallback is for older runtime assets.
  for (const name of ["terminate", "dispose", "close", "reset"]) {
    if (tornDown || !wasm || typeof wasm[name] !== "function") continue;
    try { wasm[name](); } catch (ignored) {}
    tornDown = true;
  }
  if (!tornDown) {
    const scheduler = root.__gaiusJspiScheduler;
    try { if (scheduler && typeof scheduler.close === "function") scheduler.close(); } catch (ignored) {}
  }
  root.__gaiusJspiScheduler = undefined;
  // Invalidate callbacks and exported entry points captured by late TeaVM continuations.
  for (const name of [
    "__gaiusStartIntegratedServerPump", "__gaiusIntegratedServerNetworkSignal",
    "__gaiusIntegratedServerPumpFailure", "__gaiusIntegratedServerPumpError",
    "__gaiusServerClientPort", "pumpIntegratedServerNetworkInput",
    "stopIntegratedServer", "isIntegratedServerStopped", "main",
  ]) root[name] = undefined;
  root.GaiusWasmWorker = undefined;
  root.TeaVM = undefined;
  root.GaiusJspi = undefined;
  root.__gaiusWasm = undefined;
  root.__gaiusWasmAsyncRuntime = undefined;
  return tornDown;
}

// Starts vanilla Main on the prewarmed runtime so it runs up to Bootstrap.validate and parks
// (BrowserIntegratedServerMain.resumeAfterBootstrap). Only an idle Worker asks for it, only before
// any start was accepted, and only when the Java side has the parking entry point; otherwise the
// start handshake dispatches Main itself as before. Returns whether Main was dispatched.
function dispatchParkedMain(message, script) {
  if (message.parkMain !== true || startAccepted || stopRequested || parkedMainDispatched ||
      parkedMainUnavailable) {
    return false;
  }
  if (typeof root.integratedServerParkSupported !== "function") return false;
  try {
    if (root.integratedServerParkSupported() !== true) return false;
  } catch (ignored) {
    return false;
  }
  root.__gaiusServerParkMain = true;
  root.__gaiusServerParkReleased = false;
  root.__gaiusServerParkedState = "booting";
  root.__gaiusServerParkedFailure = "";
  parkedMainDispatched = true;
  const failed = (error) => {
    if (root.__gaiusServerParkReleased === true) {
      postMessage({
        type: "runtime-error",
        detail: String(error && (error.stack || error.message) || error),
      });
      return;
    }
    root.__gaiusServerParkedState = "failed";
    root.__gaiusServerParkedFailure = String(error && (error.stack || error.message) || error);
  };
  try {
    root.GaiusWasmWorker.startMain([]).catch(failed);
  } catch (error) {
    failed(error);
  }
  // Both runtimes run main synchronously up to its first suspension. A Java main that cannot
  // suspend there returns at once without running vanilla Main ('unavailable'): keep the
  // compile-only prewarm, so the start handshake dispatches Main on this runtime itself.
  if (root.__gaiusServerParkedState === "unavailable") {
    parkedMainDispatched = false;
    parkedMainUnavailable = true;
    root.__gaiusServerParkMain = false;
    return false;
  }
  return true;
}

function parkedMainFailed() {
  return parkedMainDispatched && root.__gaiusServerParkedState === "failed";
}

async function prewarmServerRuntime(message) {
  if (prewarmPromise) return prewarmPromise;
  prewarmPromise = (async () => {
    if (startAccepted || stopRequested) return false;
    const requestedSessionId = typeof message.sessionId === "string"
      ? message.sessionId : "";
    const requestedProfile = typeof message.profileId === "string"
      ? message.profileId : "";
    const currentProfile = typeof root.__gaiusProfileId === "string"
      ? root.__gaiusProfileId : "";
    if (!requestedSessionId || requestedSessionId.length > 128 ||
        (currentProfile && requestedProfile !== currentProfile)) {
      throw new Error("invalid prewarm session/profile");
    }
    prewarmAccepted = true;
    activeSessionId = requestedSessionId;
    if (message.serverRuntime !== "wasm") {
      throw new Error("Gaius requires the WebAssembly GC integrated server");
    }
    serverRuntime = "wasm";
    const startedAt = monotonicMillis();
    const script = await prepareWasmServer(message, startedAt);
    prewarmScript = script;
    await root.GaiusWasmWorker.instantiate();
    prewarmRuntimeInstantiated = true;
    // Never call Bootstrap (or any other Java startup step) from here: an exported Bootstrap entry
    // point once left built-in holders such as max_health unbound when start reused the runtime.
    // An idle Worker may instead start vanilla Main itself (parkMain): the real main thread runs
    // Main.main from its first instruction through Bootstrap.validate in vanilla order and then
    // waits in BrowserIntegratedServerMain.resumeAfterBootstrap until the start handshake below
    // releases it. Storage, server.properties, the world, datapacks and PrepareSpawn stay after
    // that release, as on the regular start path.
    const bootstrap = dispatchParkedMain(message, script);
    postMessage({
      type: "server-prewarm-ready",
      sessionId: activeSessionId,
      bootstrap,
      worldLoader: false,
      datapacks: false,
      registryHash: null,
      datapackHash: null,
    });
    return true;
  })().catch((error) => {
    postMessage({
      type: "server-prewarm-failed",
      sessionId: activeSessionId,
      detail: String(error && (error.stack || error.message) || error),
    });
    // Prewarm runs no Java code, so a failure here means the runtime import, compile or
    // instantiate itself failed. Never reuse that runtime: release it and force start through
    // fresh asset preparation and a new import/instantiate path.
    prewarmAccepted = false;
    prewarmRuntimeFailed = true;
    releaseFailedRuntime();
    prewarmRuntimeInstantiated = false;
    prewarmScript = undefined;
    return false;
  });
  return prewarmPromise;
}

root.onmessage = async (event) => {
  const message = event.data;
  if (message && message.type === "ping") {
    answerPing(message);
    return;
  }
  // The portable launcher uses a Blob URL, which cannot safely carry a
  // diagnostic query parameter.  Accept the opt-in before the start handshake
  // so Mob-AI telemetry is available in both external-script and portable
  // Worker deployments without changing the normal release path.
  if (message && message.type === "diagnostic-config") {
    if (!startAccepted && message.gaiusDebug === true) {
      root.__gaiusDebug = true;
    }
    if (!startAccepted && Array.isArray(message.gaiusStructurePreloadIds)) {
      root.__gaiusStructurePreloadIds = message.gaiusStructurePreloadIds
        .filter(id => typeof id === "string" && id.length <= 256 &&
          /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(id))
        .slice(0, 64);
      const preloadBudget = Number(message.gaiusStructurePreloadBudgetMillis);
      root.__gaiusStructurePreloadBudgetMillis = Number.isFinite(preloadBudget) &&
        preloadBudget > 0 ? Math.min(preloadBudget, 30000) : 5000;
    }
    if (message.gaiusMobAiTelemetry === true) {
      root.__gaiusMobAiTelemetry = true;
    }
    if (message.gaiusServerTickTelemetry === true) {
      root.__gaiusServerTickTelemetryEnabled = true;
    }
    const slowProbeEnabled = typeof message.gaiusSlowProbeTelemetry === "boolean"
      ? message.gaiusSlowProbeTelemetry
      : message.slowProbeTelemetryEnabled;
    if (typeof slowProbeEnabled === "boolean") {
      root.__gaiusSlowProbeTelemetryEnabled = slowProbeEnabled;
    }
    return;
  }
  if (message && message.type === "stop") {
    if (saveAllInFlight) {
      stopAfterSaveAll = true;
      clearTimeout(saveAllStopTimer);
      saveAllStopTimer = setTimeout(() => {
        saveAllInFlight = false;
        stopAfterSaveAll = false;
        requestStop();
      }, 5000);
      return;
    }
    requestStop();
    return;
  }
  if (message && message.type === "park-main") {
    // The page reached the world-selection screens after a title-idle prewarm that only
    // compiled the runtime: let that runtime's vanilla Main run up to Bootstrap now.
    if (startAccepted || !prewarmPromise) return;
    const prewarmed = await prewarmPromise;
    if (prewarmed && !prewarmRuntimeFailed && prewarmScript) {
      dispatchParkedMain({parkMain: true}, prewarmScript);
    }
    return;
  }
  if (message && message.type === "prewarm") {
    if (startAccepted || prewarmAccepted) return;
    closeTransferredPort(message, event);
    await prewarmServerRuntime(message);
    return;
  }
  if (!message || message.type !== "start") {
    return;
  }
  if (startAccepted) {
    closeTransferredPort(message, event);
    postMessage({type: "start-duplicate-ignored", detail: activeSessionId});
    return;
  }
  const requestedSessionId = typeof message.sessionId === "string" ? message.sessionId : "";
  const requestedProfile = typeof message.profileId === "string" ? message.profileId : "";
  const currentProfile = typeof root.__gaiusProfileId === "string" ? root.__gaiusProfileId : "";
  if (!requestedSessionId || requestedSessionId.length > 128 ||
      (currentProfile && requestedProfile !== currentProfile)) {
    closeTransferredPort(message, event);
    postMessage({type: "runtime-start-failed", reason: "invalid-session-or-profile",
      detail: "session/profile validation failed"});
    return;
  }
  const launchGeneration = String(message.launchGeneration || "");
  if (!/^[1-9][0-9]*$/.test(launchGeneration)) {
    closeTransferredPort(message, event);
    postMessage({type: "start-invalid-generation", detail: activeSessionId});
    return;
  }
  if (!validateInitialGameRules(message.initialGameRules)) {
    closeTransferredPort(message, event);
    postMessage({type: "runtime-start-failed", reason: "invalid-initial-game-rules",
      detail: "initialGameRules must be bounded JSON"});
    return;
  }
  if (message.serverRuntime !== "wasm") {
    closeTransferredPort(message, event);
    postMessage({type: "runtime-start-failed", reason: "unsupported-server-runtime",
      detail: "WebAssembly GC integrated server is required"});
    return;
  }
  startAccepted = true;
  activeSessionId = requestedSessionId;
  serverRuntime = "wasm";
  root.onmessage = handleControlMessage;
  startupCancelPromise = new Promise((resolve) => {
    startupCancelResolve = resolve;
  });
  try {
    clearStorageConfiguration();
    configureStorage(message);
    if (stopRequested) {
      throw startupCancellationError();
    }
    const startupStarted = monotonicMillis();
    markStartup("start-received", startupStarted);
    const port = await waitForStartPort(message, event);
    startupPort = port;
    root.__gaiusServerClientPort = port;
    root.__gaiusServerSessionId = activeSessionId;
    root.__gaiusServerLaunchGeneration = launchGeneration;
    root.__gaiusServerWorldId = String(message.worldId || "");
    root.__gaiusSkinDescriptor = message.skinDescriptor &&
      typeof message.skinDescriptor === "object"
      ? {
          uuid: String(message.skinDescriptor.uuid || ""),
          username: String(message.skinDescriptor.username || ""),
          value: String(message.skinDescriptor.value || ""),
          signature: String(message.skinDescriptor.signature || "")
        }
      : null;
    root.__gaiusServerSeed = String(message.worldSeed || message.seed || "");
    // Publish the seed accepted by the integrated-server start handoff.  The
    // page records worker messages in __gaiusMinecraftEvents, allowing capture
    // harnesses to fail closed when the effective world seed is unknown.
    postMessage({type: "world-seed", seed: root.__gaiusServerSeed,
      sessionId: root.__gaiusServerSessionId});
    // The game rules of a freshly created world: the JSON text of vanilla's GameRules codec,
    // empty when vanilla passed none (BrowserGameRulesHandoff). Java reads it when it builds the
    // DedicatedServer, exactly where vanilla's integrated server receives its Optional<GameRules>.
    root.__gaiusServerInitialGameRules = message.initialGameRules || "";
    root.__gaiusServerViewDistance = clampDistance(message.renderDistance, 8);
    root.__gaiusServerSimulationDistance = clampDistance(message.simulationDistance, 6);
    const requestedWorldgenSlice = message.worldgenSliceMillis ??
      root.__gaiusWorldgenSliceMillis;
    root.__gaiusWorldgenSliceMillis = clampWorldgenSlice(
      requestedWorldgenSlice,
      defaultWorldgenSliceMillis,
    );
    const requestedDistanceRampInterval = message.distanceRampIntervalMillis ??
      root.__gaiusDistanceRampIntervalMillis;
    root.__gaiusDistanceRampIntervalMillis = clampDistanceRampInterval(
      requestedDistanceRampInterval,
      defaultDistanceRampIntervalMillis,
    );
    root.__gaiusBridgeUrl = message.bridgeUrl || undefined;
    root.__gaiusBridgeToken = message.bridgeToken || undefined;
    root.__gaiusLanRelay = normalizeLanRelay(message.lanRelay);
    if (!registerLocalPort(
      root.__gaiusServerSessionId,
      port,
      root.__gaiusServerLaunchGeneration,
    )) {
      throw new Error("Singleplayer local port registration requires a launch generation");
    }

    const storageStarted = monotonicMillis();
    const startupStorageGeneration = ++storageGeneration;
    const storageReady = installPersistentFileSystem(startupStorageGeneration).then((installed) => {
      if (installed && storageLifecycleIsActive(startupStorageGeneration)) {
        markStartup("storage-ready", storageStarted);
      }
      return installed;
    });
    storageStartupInFlight = storageReady;
    storageReady.then(
      () => {
        if (storageStartupInFlight === storageReady) storageStartupInFlight = undefined;
      },
      () => {
        if (storageStartupInFlight === storageReady) storageStartupInFlight = undefined;
      },
    );
    const prepareFreshAsset = () => prepareWasmServer(message, startupStarted);
    // Storage opens while the optional prewarm finishes. A failed prewarm must not reuse the
    // partially initialized runtime; prepare a fresh asset and import/instantiate it below.
    // A parked Main that already failed (for example in Bootstrap) has poisoned that runtime's
    // statics: release it and start a fresh runtime whose Main runs unparked, as without prewarm.
    const discardFailedParkedRuntime = () => {
      postMessage({type: "server-prewarm-failed", sessionId: activeSessionId,
        detail: "parked main failed: " + String(root.__gaiusServerParkedFailure || "")});
      prewarmRuntimeFailed = true;
      releaseFailedRuntime();
      prewarmRuntimeInstantiated = false;
      prewarmScript = undefined;
      parkedMainDispatched = false;
      root.__gaiusServerParkMain = false;
      return prepareFreshAsset();
    };
    const assetReady = prewarmPromise
      ? prewarmPromise.then((prewarmSucceeded) =>
        prewarmSucceeded && !prewarmRuntimeFailed && prewarmScript
          ? (parkedMainFailed() ? discardFailedParkedRuntime() : prewarmScript)
          : prepareFreshAsset())
      : prewarmScript && !prewarmRuntimeFailed
        ? Promise.resolve(prewarmScript) : prepareFreshAsset();
    // A stop must not remain hostage to a portable asset fetch that never
    // resolves. Promise.all is still observed so a late rejection cannot
    // become an unhandled rejection, but cancellation wins the bootstrap
    // race immediately.
    const startupReady = Promise.all([assetReady, storageReady]);
    startupReady.catch(() => {});
    assetReady.then((script) => {
      if (stopRequested && script && script.temporaryUrl) {
        try { URL.revokeObjectURL(script.temporaryUrl); } catch (ignored) {}
      }
    }, () => {});
    const [script, storageInstalled] = await Promise.race([
      startupReady,
      startupCancelPromise.then(() => { throw startupCancellationError(); }),
    ]);
    if (!storageInstalled || !storageLifecycleIsActive(startupStorageGeneration)) {
      throw new Error("Singleplayer launch storage was closed before runtime import");
    }
    if (stopRequested) {
      throw startupCancellationError();
    }
    postMessage({
      type: "storage-ready",
      detail: Object.keys(files).length + " files",
    });

    const importStarted = monotonicMillis();
    // The Wasm worker runtime exposes Java entry points and the input pump.
    if (!prewarmRuntimeInstantiated) {
      await Promise.race([
        root.GaiusWasmWorker.instantiate(),
        startupCancelPromise.then(() => { throw startupCancellationError(); }),
      ]);
    }
    markStartup("runtime-imported", importStarted);
    if (script.temporaryUrl) {
      URL.revokeObjectURL(script.temporaryUrl);
    }
    root.__gaiusIntegratedServerNetworkSignal =
      typeof signalIntegratedServerNetworkInput === "function"
        ? signalIntegratedServerNetworkInput
        : undefined;
    // The generated helper owns a separate TeaVM continuation and only schedules a TickTask;
    // packet decoding remains on the real server thread.
    if (typeof root.__gaiusStartIntegratedServerPump !== "function") {
      throw new Error("Singleplayer server input dispatcher is unavailable");
    }
    if (stopRequested) {
      throw startupCancellationError();
    }
    if (parkedMainDispatched) {
      // Vanilla Main already ran through Bootstrap.validate on this runtime's main thread and
      // waits in resumeAfterBootstrap; every input of the world start is published above.
      if (parkedMainFailed()) {
        throw new Error("Parked integrated server main failed: " +
          String(root.__gaiusServerParkedFailure || ""));
      }
      root.__gaiusServerParkReleased = true;
      markStartup("parked-main-released", startupStarted);
    } else {
      // The main thread's JSPI activation runs up to its first suspension, as main([]) does.
      root.GaiusWasmWorker.startMain([]).catch((error) => {
        postMessage({
          type: "runtime-error",
          detail: String(error && (error.stack || error.message) || error),
        });
      });
    }
    runtimeStarted = true;
    // TeaVM returns here on the first cooperative suspension while Java main continues.
    releaseStartupRegionPins();
    markStartup("main-dispatched", startupStarted);
    postMessage({type: "runtime-ready", detail: root.__gaiusServerWorldId});
    markStartup("runtime-ready", startupStarted);
    if (stopRequested) {
      void stopServer();
    }
  } catch (error) {
    if (stopRequested) {
      // requestStop() starts the same finalizer used after runtime startup.
      // Never claim a clean stop from this catch path: the finalizer is the
      // only code allowed to post stopped/close after storage has closed
      // safely.
      if (!runtimeStarted && !startupPort) {
        // A stop may have arrived before a start carrying a direct transferred
        // port was allowed through. It was never registered in that case, so
        // release it explicitly instead of leaking the transfer.
        closeTransferredPort(message, event);
      }
      const stopped = await (stopPromise || stopServer());
      if (!stopped) return;
      return;
    }
    const closed = await closePersistentStorage(false);
    if (!closed) {
      reportStorageFlushFailure(new Error(
        "Persistent storage was not safely closed after bootstrap failure",
      ));
    }
    releaseLocalSession("Singleplayer Worker bootstrap failed");
    postMessage({
      type: "runtime-start-failed",
      reason: "bootstrap-failed",
      detail: String(error && (error.stack || error.message) || error),
    });
    postMessage({
      type: "bootstrap-crash",
      detail: String(error && (error.stack || error.message) || error),
    });
    // The bootstrap entrypoint is invoked from a MessagePort callback in
    // browsers (and from an unawaited event shim in the lifecycle harness).
    // Reporting the crash is sufficient; rethrowing here creates an
    // unhandled-rejection event after teardown has already completed.
    return;
  }
};

function validateInitialGameRules(value) {
  if (value === undefined || value === null || value === "") return true;
  if (typeof value !== "string") return false;
  const bytes = new TextEncoder().encode(value).length;
  if (bytes > 64 * 1024) return false;
  let depth = 0;
  let members = 0;
  let inString = false;
  let escaped = false;
  for (let index = 0; index < value.length; index++) {
    const ch = value[index];
    if (inString) { if (escaped) escaped = false; else if (ch === "\\") escaped = true; else if (ch === '"') inString = false; continue; }
    if (ch === '"') { inString = true; continue; }
    if (ch === "{" || ch === "[") { if (++depth > 32) return false; if (ch === "{") members++; }
    else if (ch === "}" || ch === "]") depth--;
    if (members > 512 || depth < 0) return false;
  }
  return !inString && depth === 0;
}

function isMessagePort(value) {
  return value && typeof value.postMessage === "function" &&
    typeof value.close === "function";
}

function transferredPort(message, event) {
  const direct = message && message.port;
  if (isMessagePort(direct)) return direct;
  const transferred = event && event.ports && event.ports[0];
  return isMessagePort(transferred) ? transferred : undefined;
}

function closeTransferredPort(message, event) {
  const port = transferredPort(message, event);
  if (!port) return;
  try { port.postMessage({type: "close"}); } catch (ignored) {}
  try { port.close(); } catch (ignored) {}
}

function startupCancellationError() {
  const error = new Error("Singleplayer launch was cancelled");
  error.code = "GAIUS_STARTUP_CANCELLED";
  return error;
}

function invalidateStorageLifecycle() {
  if (!persistentStorageClosed) {
    persistentStorageClosed = true;
    storageGeneration++;
  }
  // Generation guards are the authoritative write barrier. Clearing the
  // exported callbacks as well makes late TeaVM continuations fail closed
  // without touching pendingChanges that still need a shutdown flush.
  root.__gaiusFsPut = undefined;
  root.__gaiusFsPutBytes = undefined;
  root.__gaiusFsCanPatchBytes = undefined;
  root.__gaiusFsPatchBytes = undefined;
  root.__gaiusFsDelete = undefined;
  root.__gaiusFsFlush = undefined;
  root.__gaiusFsStorageSnapshot = undefined;
}

function rejectPendingStartPort(reason) {
  if (!pendingPortReject) return false;
  const reject = pendingPortReject;
  if (pendingPortTimer !== undefined) {
    clearTimeout(pendingPortTimer);
    pendingPortTimer = undefined;
  }
  pendingPortResolve = undefined;
  pendingPortReject = undefined;
  reject(reason || startupCancellationError());
  return true;
}

// Kept inside the bootstrap so site and portable workers carry the same controller.
// Each relay-backed Netty channel represents one guest connection. The relay closes
// both endpoints when that guest leaves; replenish the server endpoint for the same
// invitation without disturbing the startup MessagePort or an active guest.
function createLanServerConnections({channels, open, isStopped,
  notify = () => {}, now = () => Date.now(),
  setTimer = (fn, ms) => setTimeout(fn, ms), clearTimer = id => clearTimeout(id)}) {
  const sessions = new Map();
  const pollMillis = 250;
  const registrationMillis = 15000;
  let timer = null;
  let stopped = false;

  function stop() {
    stopped = true;
    if (timer !== null) clearTimer(timer);
    timer = null;
    sessions.clear();
  }

  function schedule() {
    if (stopped || isStopped()) { stop(); return; }
    if (timer === null && sessions.size) timer = setTimer(poll, pollMillis);
  }

  function attempt(session, initial) {
    const at = now();
    // Bootstrap.connect registers its JS transport asynchronously. Old closed
    // entries can remain in the bridge until Java retires them, so neither an
    // absent entry nor that old entry proves that this new open has failed yet.
    session.registrationUntil = at + registrationMillis;
    session.awaitingRegistration = true;
    let ok = false;
    let detail = "opener returned false";
    try { ok = open(session.id) === true; }
    catch (error) { detail = String(error?.message || error); }
    if (!ok) {
      session.awaitingRegistration = false;
      session.nextAttempt = at + session.backoff;
      session.backoff = Math.min(30000, session.backoff * 2);
      notify("open-failed", session.id, detail);
    } else if (!initial) {
      notify("rearmed", session.id, "server endpoint opened");
    }
    return ok;
  }

  function poll() {
    timer = null;
    if (stopped || isStopped()) { stop(); return; }
    const bridgeChannels = channels();
    // A missing bridge during initialization is covered by the registration
    // deadline, rather than silently disabling recovery after a failed open.
    const entries = bridgeChannels && typeof bridgeChannels.values === "function"
      ? [...bridgeChannels.values()] : [];
    const at = now();
    for (const session of sessions.values()) {
      const host = "lan-server-" + session.id + ".gaius-local";
      const live = entries.find(entry => entry && entry.host === host && !entry.closed);
      if (live) {
        session.awaitingRegistration = false;
        // Connecting is a live endpoint too: never create a duplicate while
        // waiting for a guest, preflight, or the relay's connection deadline.
        session.nextAttempt = at;
        if (live.connected) session.backoff = 1000;
        continue;
      }
      if (session.awaitingRegistration && at < session.registrationUntil) continue;
      if (session.awaitingRegistration) {
        session.awaitingRegistration = false;
        session.nextAttempt = at + session.backoff;
        session.backoff = Math.min(30000, session.backoff * 2);
        notify("registration-timeout", session.id, "server endpoint did not register");
      }
      if (at >= session.nextAttempt) attempt(session, false);
    }
    schedule();
  }

  function request(id) {
    if (stopped || isStopped()) { stop(); return false; }
    if (!/^[a-f0-9]{32}$/.test(String(id || ""))) return false;
    if (sessions.has(id)) return true;
    // Bound monitoring and retained invitations for this world.
    if (sessions.size >= 8) return false;
    const session = {id, backoff: 1000, nextAttempt: now(),
      awaitingRegistration: false, registrationUntil: 0};
    if (!attempt(session, true)) return false;
    sessions.set(id, session);
    schedule();
    return true;
  }

  return {request, stop};
}

function requestStop() {
  stopRequested = true;
  lanServerConnections.stop();
  if (!startAccepted) return;
  // Runtime shutdown must keep the generation/callbacks live while
  // stopIntegratedServer performs its final save. Startup cancellation is the
  // only path that may raise the storage barrier before teardown.
  if (!runtimeStarted) {
    invalidateStorageLifecycle();
    if (startupCancelResolve) {
      const resolve = startupCancelResolve;
      startupCancelResolve = undefined;
      resolve();
    }
    rejectPendingStartPort();
  }
  if (!stopPromise) {
    stopPromise = stopServer();
  }
}

// A Wasm pump failure means the runtime can no longer receive network input. Keep the
// previously published runtime-ready state from becoming a silent zombie: route the
// failure through the normal controlled stop path, which preserves the final-save and
// storage-close ordering.
root.__gaiusIntegratedServerPumpFailure = (detail) => {
  root.__gaiusControlledStop = true;
  postMessage({type: "controlled-stop", reason: "integrated-server-pump-failure",
    detail: String(detail || "integrated server network pump failed")});
  postMessage({type: "runtime-error", detail: String(detail || "integrated server network pump failed")});
  requestStop();
};

function waitForStartPort(message, event) {
  const port = transferredPort(message, event);
  if (stopRequested) {
    if (port) closeTransferredPort(message, event);
    return Promise.reject(startupCancellationError());
  }
  if (port) return Promise.resolve(port);
  postMessage({type: "port-waiting", detail: activeSessionId});
  return new Promise((resolve, reject) => {
    pendingPortResolve = (latePort) => {
      clearTimeout(pendingPortTimer);
      pendingPortTimer = undefined;
      pendingPortResolve = undefined;
      pendingPortReject = undefined;
      resolve(latePort);
    };
    pendingPortReject = reject;
    pendingPortTimer = setTimeout(() => {
      pendingPortTimer = undefined;
      pendingPortResolve = undefined;
      pendingPortReject = undefined;
      reject(new Error(
        "Singleplayer worker MessagePort did not arrive within 5000 ms for " +
          activeSessionId
      ));
    }, 5000);
  });
}

function registerLocalPort(sessionId, port, launchGeneration) {
  const generation = String(launchGeneration || "");
  if (!/^[1-9][0-9]*$/.test(generation)) return false;
  let extensible = false;
  try { extensible = Object.isExtensible(port); } catch {}
  if (!extensible) return false;
  const existingGeneration = String(port && port.__gaiusLaunchGeneration || "");
  if (existingGeneration && existingGeneration !== generation) return false;
  if (existingGeneration !== generation) {
    try { port.__gaiusLaunchGeneration = generation; } catch {}
  }
  if (String(port && port.__gaiusLaunchGeneration || "") !== generation) return false;
  const bridge = root.__gaiusNettyBridge;
  if (bridge && typeof bridge.registerLocalPort === "function") {
    return bridge.registerLocalPort(sessionId, port, generation) !== false;
  }
  const ports = root.__gaiusLocalServerPorts ||
    (root.__gaiusLocalServerPorts = new Map());
  const previous = typeof ports.get === "function" ? ports.get(sessionId) : ports[sessionId];
  if (previous && previous !== port) {
    try { previous.postMessage({type: "close"}); } catch (ignored) {}
    try { previous.close(); } catch (ignored) {}
  }
  if (typeof ports.set === "function") ports.set(sessionId, port);
  else ports[sessionId] = port;
  return true;
}

function releaseLocalSession(reason) {
  if (pendingPortTimer !== undefined) {
    clearTimeout(pendingPortTimer);
    pendingPortTimer = undefined;
  }
  pendingPortResolve = undefined;
  pendingPortReject = undefined;
  const sessionId = String(root.__gaiusServerSessionId || activeSessionId || "");
  const launchGeneration = String(root.__gaiusServerLaunchGeneration || "");
  const bridge = root.__gaiusNettyBridge;
  if (bridge && typeof bridge.failLocalSession === "function" && sessionId) {
    bridge.failLocalSession(sessionId, reason, launchGeneration);
  }
  const ports = root.__gaiusLocalServerPorts;
  const pendingPort = ports && typeof ports.get === "function"
    ? ports.get(sessionId)
    : ports && ports[sessionId];
  if (pendingPort) {
    try { pendingPort.postMessage({type: "close"}); } catch (ignored) {}
    try { pendingPort.close(); } catch (ignored) {}
    if (typeof ports.delete === "function") ports.delete(sessionId);
    else delete ports[sessionId];
  }
  if (startupPort && startupPort !== pendingPort) {
    try { startupPort.close(); } catch (ignored) {}
  }
  startupPort = undefined;
  root.__gaiusServerClientPort = undefined;
}

// The files a page opened from disk hands its Worker (BrowserSingleplayerClient starts that
// Worker from a blob loader, which publishes them as root.__gaiusWorkerFiles = {name:
// ArrayBuffer} before it imports this script): such a Worker can neither fetch nor import its
// file:// siblings. A file is found by its logical name (singleplayer-server.wasm,
// kernels/worldgen-job.js) or by the path of the sibling URL the page would have used.
function workerFile(nameOrUrl) {
  const files = root.__gaiusWorkerFiles;
  if (!files || typeof files !== "object" || !nameOrUrl) return null;
  const key = String(nameOrUrl);
  if (Object.prototype.hasOwnProperty.call(files, key)) return files[key];
  let path;
  try {
    path = decodeURIComponent(new URL(key, "file:///").pathname);
  } catch (ignored) {
    return null;
  }
  for (const name of Object.keys(files)) {
    if (path === "/" + name || path.endsWith("/" + name)) return files[name];
  }
  return null;
}

// importScripts() of a file the page handed over; false when there is no such file.
function importWorkerFile(nameOrUrl) {
  const bytes = workerFile(nameOrUrl);
  if (!bytes) return false;
  const url = URL.createObjectURL(new Blob([bytes], {type: "text/javascript"}));
  try {
    importScripts(url);
  } finally {
    URL.revokeObjectURL(url);
  }
  return true;
}

// The integrated server on WebAssembly GC (v0.4.0 WP10). The start message names the module
// and the runtime scripts this Worker imports: {serverRuntime: "wasm", serverWasmUrl,
// wasmRuntimeScripts: [{name, url} | {name, source}]} (gaius-boot.js serverRuntimeConfig), or
// carries the module itself as serverWasmBytes (or, from a portable page, its gzip bytes as
// serverWasmGzipData). A Worker started from disk finds the module and the scripts among the
// files the page handed over (workerFile). The module compiles while storage opens.
async function prepareWasmServer(message, startupStarted) {
  // A failed prewarm may have closed the previous scheduler/runtime. The next asset import is a
  // new lifecycle and must be eligible for its own guarded teardown if it also fails.
  runtimeReleaseInProgress = false;
  root.__gaiusServerRuntimeReleased = false;
  const scripts = Array.isArray(message.wasmRuntimeScripts) ? message.wasmRuntimeScripts : [];
  for (const entry of scripts) {
    if (!entry || typeof entry !== "object") continue;
    let temporaryUrl = null;
    try {
      if (typeof entry.source === "string") {
        temporaryUrl = URL.createObjectURL(new Blob([entry.source], {type: "text/javascript"}));
        importScripts(temporaryUrl);
      } else if (importWorkerFile(entry.name) || importWorkerFile(entry.url)) {
        // imported from the page's files
      } else if (entry.url) {
        importScripts(String(entry.url));
      }
    } finally {
      if (temporaryUrl) URL.revokeObjectURL(temporaryUrl);
    }
  }
  if (!root.GaiusWasmWorker || !root.TeaVM || !root.GaiusJspi) {
    throw new Error("The WebAssembly GC server runtime scripts did not load");
  }
  markStartup("runtime-scripts-imported", startupStarted);
  let source = message.serverWasmModule || message.serverWasmBytes ||
    workerFile(message.serverWasmUrl || "singleplayer-server.wasm") || null;
  if (!source && message.serverWasmGzipData) {
    // A portable page passes the module's gzip bytes: it inflates while it compiles.
    if (typeof DecompressionStream !== "function") {
      throw new Error("This browser cannot decompress the portable singleplayer server");
    }
    const inflated = new Blob([message.serverWasmGzipData]).stream()
      .pipeThrough(new DecompressionStream("gzip"));
    source = new Response(inflated, {headers: {"Content-Type": "application/wasm"}});
    markStartup("runtime-transferred", startupStarted);
  }
  if (!source && message.serverWasmUrl) source = String(message.serverWasmUrl);
  if (!source) {
    throw new Error("The start message names no singleplayer-server.wasm");
  }
  await root.GaiusWasmWorker.compile(source);
  markStartup("runtime-compiled", startupStarted);
  return {runtime: "wasm", url: null, temporaryUrl: null};
}

// {type: "ping", sequence?, echo?: ArrayBuffer} -> {type: "pong", ...}: answered before and after
// the start handshake. A started server answers through its own runtime (isIntegratedServerStopped),
// and a wasm server adds its module state; an echo buffer comes back transferred.
function answerPing(message) {
  const reply = {
    type: "pong",
    sequence: Number(message.sequence) || 0,
    sessionId: activeSessionId,
    runtime: serverRuntime || "none",
    started: startAccepted,
    runtimeStarted,
    stopping: stopRequested || stopping,
    controlledStop: root.__gaiusControlledStop === true,
    at: Date.now(),
  };
  if (root.GaiusWasmWorker && typeof root.GaiusWasmWorker.info === "function") {
    reply.wasm = root.GaiusWasmWorker.info();
  }
  if (runtimeStarted && typeof isIntegratedServerStopped === "function") {
    try {
      reply.serverStopped = isIntegratedServerStopped() === true;
    } catch (error) {
      reply.serverStoppedError = String(error && error.message || error);
    }
  }
  const transfer = [];
  if (message.echo instanceof ArrayBuffer) {
    reply.echo = message.echo;
    transfer.push(message.echo);
  }
  postMessage(reply, transfer);
}

function normalizeLanRelay(value) {
  const relay = String(value || '').trim();
  return /^(?:ws|wss|http|https):\/\/[^\s]{1,2000}$/u.test(relay) ? relay : '';
}

function handleControlMessage(event) {
  const message = event.data;
  if (!message) {
    return;
  }
  if (message.type === "runtime-lease-save") {
    startRuntimeLeaseSaveHeartbeat(message.leaseId, message.channel, message.workerId);
    return;
  }
  if (message.type === "ping") {
    answerPing(message);
    return;
  }
  if (message.type === "diagnostic-config") {
    const slowProbeEnabled = typeof message.gaiusSlowProbeTelemetry === "boolean"
      ? message.gaiusSlowProbeTelemetry
      : message.slowProbeTelemetryEnabled;
    if (typeof slowProbeEnabled === "boolean") {
      root.__gaiusSlowProbeTelemetryEnabled = slowProbeEnabled;
    }
    return;
  }
  if (message.type === "lan-open") {
    const brokerSessionId = String(message.brokerSessionId || "");
    if (!/^[a-f0-9]{32}$/.test(brokerSessionId)) {
      postMessage({type: "lan-open-result", ok: false, reason: "invalid-session"});
      return;
    }
    root.__gaiusLanRelay = normalizeLanRelay(message.lanRelay);
    const descriptor = message.skinDescriptor;
    if (descriptor && typeof descriptor === "object") {
      const uuid = String(descriptor.uuid || "").replaceAll("-", "").toLowerCase();
      const username = String(descriptor.username || "");
      const value = String(descriptor.value || "");
      const signature = String(descriptor.signature || "");
      root.__gaiusLanSkinDescriptor = /^[0-9a-f]{32}$/.test(uuid) &&
        /^[A-Za-z0-9_]{1,16}$/.test(username) &&
        value.length >= 1 && value.length <= 16384 &&
        signature.length <= 16384
        ? {uuid, username, value, signature}
        : null;
    } else {
      root.__gaiusLanSkinDescriptor = null;
    }
    try {
      const ok = lanServerConnections.request(brokerSessionId);
      postMessage({type: "lan-open-result", ok, brokerSessionId});
    } catch (error) {
      postMessage({
        type: "lan-open-result",
        ok: false,
        brokerSessionId,
        reason: String(error && error.message || error),
      });
    }
    return;
  }
  if (message.type === "telemetry-ping") {
    const measurementId = typeof message.measurementId === "string"
      ? message.measurementId
      : String(message.measurementId == null ? "" : message.measurementId);
    const receivedAtEpoch = Date.now();
    // This is the Worker-local monotonic sample clock.  It travels with the
    // server counters so browser-side windows do not have to infer timing
    // from the page or from the cumulative tick-duration fields.
    const sampledAtMillis = performance.now();
    refreshStorageStats();
    observeTelemetryMeasurement(measurementId);
    postMessage({
      type: "telemetry-pong",
      sessionId: String(message.sessionId || activeSessionId || ""),
      sequence: Number(message.sequence) || 0,
      measurementId: telemetryMeasurementId,
      workerResetAt: telemetryWorkerResetAt,
      receivedAtEpoch,
      sentAtEpoch: Date.now(),
      chunkPriority: snapshotScalarTelemetry(root.__gaiusChunkPriorityStats),
      network: snapshotMeasurementTelemetry(
        root.__gaiusNetworkStats,
        telemetryNetworkBaseline,
        networkTelemetryPriorityKeys,
        networkTelemetryCounterKeys,
      ),
      // Keep the bounded global-pump aggregate outside the capped scalar
      // network object. These values are intentionally raw (not measurement
      // deltas): turns/yields are cumulative process diagnostics while the
      // max/last fields are gauges. Missing fields stay explicit nulls.
      globalPump: snapshotGlobalPumpTelemetry(globalPumpTelemetrySource()),
      serverDistance: snapshotScalarTelemetry(root.__gaiusServerDistanceTelemetry),
      worldgen: snapshotWorldgenTelemetry(root.__gaiusWorldgenStats),
      serverTick: root.__gaiusServerTickTelemetryEnabled === true &&
        root.__gaiusServerTickTelemetry
        ? {
          ...snapshotScalarTelemetry(root.__gaiusServerTickTelemetry, [
            "schemaVersion",
            "tickCount",
            "intervalCount",
            "completedTickCount",
            "intervalOver100MillisCount",
            "intervalOver500MillisCount",
            "lastTickIntervalMillis",
            "maxTickIntervalMillis",
            "totalTickIntervalMillis",
            "lastTickDurationMillis",
            "maxTickDurationMillis",
            "totalTickDurationMillis",
            "completedWaitPhaseCount",
            "lastWaitPhaseDurationMillis",
            "maxWaitPhaseDurationMillis",
            "totalWaitPhaseDurationMillis",
            "tickWorkOver500MillisCount",
            "waitPhaseOver500MillisCount",
          ]),
          sampledAtMillis,
        }
        : null,
      storage: snapshotMeasurementTelemetry(
        storageStats,
        telemetryStorageBaseline,
        [],
        storageTelemetryCounterKeys,
      ),
    });
    return;
  }
  if (message.type === "diagnostic-snapshot") {
    if (root.__gaiusSlowProbeTelemetryEnabled !== true) {
      postMessage({type: "diagnostic-snapshot", enabled: false});
      return;
    }
    postMessage({
      type: "diagnostic-snapshot",
      enabled: true,
      worldgenSchedulerMarker: snapshotDiagnosticTelemetry(
        root.__gaiusWorldgenSchedulerMarker,
        ["maxSliceContext", "maxTaskContext"],
      ),
      futurePumpTelemetry: snapshotDiagnosticTelemetry(
        root.__gaiusFuturePumpTelemetry,
      ),
      worldgenStats: snapshotDiagnosticTelemetry(root.__gaiusWorldgenStats, [
        "chunkHolderProbeMaxContext",
        "chunkHolderProbeMaxDurationMillis",
        "chunkHolderProbeSamples",
        "chunkHolderProbeFalseResults",
      ]),
      serverTickTelemetry: snapshotDiagnosticTelemetry(root.__gaiusServerTickTelemetry),
      structurePreload: snapshotDiagnosticTelemetry(root.__gaiusWorldgenStats?.structurePreload),
    });
    return;
  }
  if (message.type === "start" || message.type === "attach-port") {
    const sessionId = String(message.sessionId || activeSessionId || "");
    const port = transferredPort(message, event);
    if (pendingPortResolve && port && sessionId === activeSessionId) {
      const resolve = pendingPortResolve;
      postMessage({type: "port-attached", detail: activeSessionId});
      resolve(port);
    } else {
      closeTransferredPort(message, event);
      postMessage({type: "start-duplicate-ignored", detail: activeSessionId});
    }
    return;
  }
  if (message.type === "distances") {
    // A queued client preference update must not revive distance work after
    // shutdown has started. PlayerList setters rebroadcast and walk every
    // ServerLevel, so even a late no-op message is expensive in this Worker.
    if (stopRequested || stopping) return;
    root.__gaiusServerViewDistance = clampDistance(message.renderDistance, 8);
    root.__gaiusServerSimulationDistance = clampDistance(message.simulationDistance, 6);
    if (typeof setIntegratedServerDistances === "function") {
      setIntegratedServerDistances(
        root.__gaiusServerViewDistance,
        root.__gaiusServerSimulationDistance,
      );
    }
    return;
  }
  if (message.type === "save-all") {
    // Page lifecycle events cannot wait for a Worker response. Queue vanilla's save-all on the
    // integrated-server thread; the Java hook starts the existing storage flush protocol after
    // saveAllChunks returns, producing storage-flush-ack/storage-flush-error asynchronously.
    if (stopRequested || stopping) return;
    if (typeof requestIntegratedServerSave !== "function") {
      postMessage({
        type: "save-all-error",
        detail: "Integrated server save hook is unavailable",
      });
      return;
    }
    try {
      saveAllInFlight = true;
      requestIntegratedServerSave();
    } catch (error) {
      saveAllInFlight = false;
      postMessage({
        type: "save-all-error",
        detail: String(error && (error.stack || error.message) || error),
      });
    }
    return;
  }
  if (message.type !== "stop") return;
  requestStop();
}

function clampDistance(value, fallback) {
  const number = Number(value);
  return Math.max(2, Math.min(32, Number.isFinite(number) ? Math.floor(number) : fallback));
}

function startRuntimeLeaseSaveHeartbeat(leaseId, channelName, workerId) {
  if (typeof BroadcastChannel !== "function" || !leaseId) return;
  if (runtimeLeaseSaveHeartbeat) clearInterval(runtimeLeaseSaveHeartbeat);
  try { runtimeLeaseSaveChannel?.close(); } catch (_) {}
  try {
    runtimeLeaseSaveChannel = new BroadcastChannel(String(channelName || "gaius.runtimeLease.v1"));
  } catch (_) {
    runtimeLeaseSaveChannel = undefined;
    return;
  }
  runtimeLeaseSaveId = String(leaseId);
  runtimeLeaseSaveWorkerId = String(workerId || root.__gaiusServerWorldId || "worker");
  const heartbeat = () => {
    try {
      runtimeLeaseSaveChannel?.postMessage({
        type: "saving-heartbeat",
        id: runtimeLeaseSaveId,
        workerId: runtimeLeaseSaveWorkerId,
        at: Date.now(),
      });
    } catch (_) {}
  };
  heartbeat();
  runtimeLeaseSaveHeartbeat = setInterval(heartbeat, 1000);
}

function finishRuntimeLeaseSave() {
  if (!runtimeLeaseSaveId) return;
  try {
    runtimeLeaseSaveChannel?.postMessage({type: "saving-worker-complete", id: runtimeLeaseSaveId,
      workerId: runtimeLeaseSaveWorkerId});
  } catch (_) {}
  if (runtimeLeaseSaveHeartbeat) clearInterval(runtimeLeaseSaveHeartbeat);
  runtimeLeaseSaveHeartbeat = undefined;
  try { runtimeLeaseSaveChannel?.close(); } catch (_) {}
  runtimeLeaseSaveChannel = undefined;
  runtimeLeaseSaveId = "";
  runtimeLeaseSaveWorkerId = "";
}

function configureStorage(message) {
  const profileId = requiredStorageIdentifier(message && message.profileId, "profileId");
  const worldVersion = requiredStorageInteger(message && message.worldVersion, "worldVersion");
  const storageSchema = requiredStorageInteger(
    message && message.storageSchema,
    "storageSchema",
  );
  const storageDatabaseName = requiredStorageIdentifier(
    message && message.storageDatabaseName,
    "storageDatabaseName",
  );
  const storagePrefix = requiredStoragePrefix(message && message.storagePrefix);
  const storageOpfsDirectory = requiredStorageIdentifier(
    message && message.storageOpfsDirectory,
    "storageOpfsDirectory",
  );
  const expected = storageProfiles[profileId];
  if (!expected || worldVersion !== expected.worldVersion ||
      storageSchema !== expected.storageSchema ||
      storageDatabaseName !== expected.storageDatabaseName ||
      storagePrefix !== expected.storagePrefix ||
      storageOpfsDirectory !== expected.storageOpfsDirectory) {
    throw new Error(
      "Singleplayer storage configuration does not match profile " + profileId,
    );
  }
  root.__gaiusProfileId = profileId;
  root.__gaiusWorldVersion = worldVersion;
  root.__gaiusStorageSchema = storageSchema;
  root.__gaiusStorageDatabaseName = storageDatabaseName;
  root.__gaiusStoragePrefix = storagePrefix;
  root.__gaiusStorageOpfsDirectory = storageOpfsDirectory;
}

function clearStorageConfiguration() {
  root.__gaiusProfileId = undefined;
  root.__gaiusWorldVersion = undefined;
  root.__gaiusStorageSchema = undefined;
  root.__gaiusStorageDatabaseName = undefined;
  root.__gaiusStoragePrefix = undefined;
  root.__gaiusStorageOpfsDirectory = undefined;
}

function requiredStorageIdentifier(value, field) {
  const text = String(value == null ? "" : value).trim();
  if (!text || text.length > 128 || text === "." || text === ".." ||
      text.includes("/") || text.includes("\\") || text.includes("\u0000") ||
      text === "gaius-fs-v1" || text === "regions") {
    throw new Error("Singleplayer storage " + field + " is not configured safely");
  }
  return text;
}

function requiredStoragePrefix(value) {
  const text = String(value == null ? "" : value);
  if (!text || text.length > 256 || text.includes("\u0000") ||
      text === "gaius.fs.v1:") {
    throw new Error("Singleplayer storage prefix is not configured safely");
  }
  return text;
}

function requiredStorageInteger(value, field) {
  const number = Number(value);
  if (!Number.isSafeInteger(number) || number <= 0) {
    throw new Error("Singleplayer storage " + field + " is not configured safely");
  }
  return number;
}

function storageLifecycleIsActive(generation) {
  return !persistentStorageClosed && Number(generation) === storageGeneration;
}

function assertStorageLifecycleIsActive(generation) {
  if (!storageLifecycleIsActive(generation)) {
    throw new Error("Persistent storage lifecycle was closed");
  }
}

function configuredTimeout(rootValue, fallback, maximum) {
  const value = Number(rootValue);
  if (!Number.isFinite(value) || value <= 0) return fallback;
  return Math.min(maximum, Math.max(1, Math.floor(value)));
}

function storageFlushWatchdogMillis() {
  return configuredTimeout(root.__gaiusStorageFlushWatchdogMillis, 10000, 60000);
}

function storageFlushRetryWatchdogMillis() {
  const configured = Number(root.__gaiusStorageFlushRetryWatchdogMillis);
  if (Number.isFinite(configured) && configured > 0) {
    return Math.min(60000, Math.max(1, Math.floor(configured)));
  }
  // The retry is deliberately given a little more room than the first
  // watchdog: an abort/rollback can leave the backend briefly busy even
  // after its promise has settled.
  return Math.min(60000, Math.max(storageFlushWatchdogMillis(), 1000));
}

function storageFlushAbortSettleMillis() {
  return configuredTimeout(root.__gaiusStorageFlushAbortSettleMillis, 1000, 10000);
}

function storageStartupSettleMillis() {
  return configuredTimeout(root.__gaiusStorageStartupSettleMillis, 5000, 30000);
}

function runtimeStopWatchdogMillis() {
  return configuredTimeout(root.__gaiusRuntimeStopWatchdogMillis, 20000, 60000);
}

function storageCloseSettleMillis() {
  return configuredTimeout(root.__gaiusStorageCloseSettleMillis, 250, 5000);
}

function runtimeStopFailure(detail) {
  const error = new Error(detail);
  error.code = "GAIUS_RUNTIME_STOP_FAILED";
  return error;
}

function reportRuntimeStopFailure(error) {
  const detail = String(error && (error.stack || error.message) || error);
  postMessage({type: "runtime-stop-failed", detail, error: detail});
}

function stopServer() {
  if (stopPromise) return stopPromise;
  stopPromise = stopServerImpl().catch((error) => {
    if (error && error.code === "GAIUS_RUNTIME_STOP_FAILED") {
      reportRuntimeStopFailure(error);
    } else {
      reportStorageFlushFailure(error);
    }
    // A failed stop must remain retryable. Otherwise `stopping` and the rejected
    // promise permanently strand the Worker in a half-stopped state.
    stopping = false;
    stopPromise = undefined;
    return false;
  });
  return stopPromise;
}

async function stopServerImpl() {
  if (stopping) return false;
  stopping = true;
  postMessage({type: "stopping", detail: root.__gaiusServerWorldId});
  if (runtimeStarted) {
    // Once main() has been dispatched, both hooks are required to prove that
    // the Java server stopped. Closing storage without either proof can race
    // a final save, so fail closed before touching flush/close.
    if (typeof stopIntegratedServer !== "function") {
      throw runtimeStopFailure(
        "Integrated server stop hook is unavailable; storage shutdown refused",
      );
    }
    if (typeof isIntegratedServerStopped !== "function") {
      throw runtimeStopFailure(
        "Integrated server stopped-state hook is unavailable; storage shutdown refused",
      );
    }
    // The final save waits for in-flight chunk generation: cap the worldgen kernel's pending
    // jobs so each settles (result or Java fallback) well inside the stop watchdog.
    const worldgenDrain = drainWorldgenKernel();
    try {
      stopIntegratedServer();
    } catch (error) {
      throw runtimeStopFailure(
        "Integrated server stop hook failed: " +
          String(error && (error.stack || error.message) || error),
      );
    }
    const stopWatchdog = runtimeStopWatchdogMillis();
    const deadline = Date.now() + stopWatchdog;
    while (Date.now() < deadline) {
      let stopped;
      try {
        stopped = isIntegratedServerStopped();
      } catch (error) {
        throw runtimeStopFailure(
          "Integrated server stopped-state hook failed: " +
            String(error && (error.stack || error.message) || error),
        );
      }
      if (stopped) break;
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    let stopped;
    try {
      stopped = isIntegratedServerStopped();
    } catch (error) {
      throw runtimeStopFailure(
        "Integrated server stopped-state hook failed: " +
          String(error && (error.stack || error.message) || error),
      );
    }
    if (!stopped) {
      const detail = "Integrated server did not stop within " +
        stopWatchdog + " ms";
      postMessage({type: "server-stop-timeout", detail});
      throw runtimeStopFailure(detail + "; storage shutdown refused");
    }
    await worldgenDrain;
  }
  postMessage({type: "storage-flushing", detail: root.__gaiusServerWorldId});

  const flushed = await flushForShutdown();
  const closed = flushed && await closePersistentStorage(false);
  // This is deliberately checked again after closePersistentStorage.  No
  // caller may turn a deferred/failed close into a clean worker shutdown.
  if (!flushed || !closed || !storageHandlesClosed || !storageStateIsClean()) {
    reportStorageFlushFailure(new Error(
      "Persistent storage flush/close failed; worker remains open",
    ));
    return false;
  }

  // Chromium releases an OPFS SyncAccessHandle asynchronously after close().  Publish a
  // separate handoff barrier and give the storage implementation one macrotask window before
  // the page is allowed to terminate this Worker and open the same world again.
  postMessage({type: "storage-closed", detail: root.__gaiusServerWorldId});
  finishRuntimeLeaseSave();
  await new Promise((resolve) => setTimeout(resolve, storageCloseSettleMillis()));

  root.__gaiusIntegratedServerNetworkSignal = undefined;
  releaseLocalSession("Singleplayer Worker stopped");
  if (root.__gaiusChunkPriorityStats) {
    postMessage({
      type: "chunk-priority-stats",
      ...root.__gaiusChunkPriorityStats,
    });
  }
  postMessage({type: "stopped", detail: root.__gaiusServerWorldId});
  setTimeout(() => close(), 0);
  return true;
}

function storageStateIsClean() {
  refreshStorageStats();
  if (flushInFlight || activeFlushTransaction || pendingChanges.size > 0 || opfsDirty) {
    return false;
  }
  for (const entry of regionCache.values()) {
    if (entry.dirty || entry.flushing) return false;
  }
  return true;
}

async function flushForShutdown() {
  let lastError;
  // A watchdog abort is expected to requeue the batch.  Always give that
  // recovered batch one complete retry, but never retry while the aborted
  // transaction/promise is still unsettled.
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      await flushWithWatchdog(
        attempt === 0
          ? storageFlushWatchdogMillis()
          : storageFlushRetryWatchdogMillis(),
        "Persistent storage flush timed out",
      );
      if (storageStateIsClean()) return true;
    } catch (error) {
      lastError = error;
      // Keep the watchdog event even when the recovered batch succeeds on
      // retry; operators need to see why the first shutdown flush was
      // aborted.
      reportStorageError(error);
      if (flushInFlight || activeFlushTransaction) {
        reportStorageFlushFailure(error);
        return false;
      }
      if (attempt === 1) break;
      continue;
    }
    if (storageStateIsClean()) return true;
  }
  reportStorageFlushFailure(lastError || new Error(
    "Persistent storage still has pending changes after shutdown flush retry",
  ));
  return false;
}

async function installPersistentFileSystem(generation = storageGeneration) {
  let openedDatabase;
  try {
    assertStorageLifecycleIsActive(generation);
    await acquireWorldOwnerLock(root.__gaiusServerWorldId, generation);
    assertStorageLifecycleIsActive(generation);
    openedDatabase = await openDatabase();
    if (!storageLifecycleIsActive(generation)) {
      try { openedDatabase.close(); } catch (ignored) {}
      openedDatabase = undefined;
      return false;
    }
    database = openedDatabase;
    openedDatabase = undefined;
    const opfsReady = await openOpfsRegionStore(root.__gaiusServerWorldId, generation);
    assertStorageLifecycleIsActive(generation);
    if (!opfsReady && await hasOpfsWorldMarker(root.__gaiusServerWorldId)) {
      throw new Error(
        "OPFS is temporarily unavailable for this world; close other tabs or retry before opening",
      );
    }
    if (opfsReady) {
      await writeOpfsWorldMarker(root.__gaiusServerWorldId);
      await resumeRecoveryJournal(root.__gaiusServerWorldId, generation);
    }
    if (!opfsReady && !regionCacheBudgetConfigured) {
      regionCacheBudgetBytes = clampRegionCacheBudget(defaultIndexedDbFallbackRegionBudgetBytes);
      storageStats.cacheBudgetBytes = regionCacheBudgetBytes;
    }
    await readWorldFiles(root.__gaiusServerWorldId, generation);
    assertStorageLifecycleIsActive(generation);
    root.__gaiusFsBackend = opfsReady
      ? "opfs-sync-worker"
      : "indexeddb-worker-lru";
    storageStats.backend = root.__gaiusFsBackend;
  } catch (error) {
    if (openedDatabase) {
      try { openedDatabase.close(); } catch (ignored) {}
      openedDatabase = undefined;
    }
    if (!storageLifecycleIsActive(generation)) {
      if (database && generation !== storageGeneration) {
        try { database.close(); } catch (ignored) {}
        database = undefined;
      }
      if (opfsAccessHandle && generation !== storageGeneration) {
        try { opfsAccessHandle.close(); } catch (ignored) {}
        opfsAccessHandle = undefined;
      }
      return false;
    }
    throw error;
  }

  if (!storageLifecycleIsActive(generation)) return false;
  root.__gaiusFsPut = (path, value) => {
    if (!storageLifecycleIsActive(generation)) return false;
    path = normalize(path);
    value = String(value || "");
    fileValues[path] = value;
    queueIndexedDbChange(path, value, false);
    scheduleFlush();
    return true;
  };
  root.__gaiusFsRequestRegion = (path, done) => {
    if (!storageLifecycleIsActive(generation)) {
      done(false, "Persistent storage is closed");
      return;
    }
    requestIndexedDbRegion(path, done);
  };
  root.__gaiusFsReleaseRegion = path => {
    const hydrated = root.__gaiusIndexedDbHydratedRegions;
    if (hydrated) hydrated.delete(normalize(path));
  };
  root.__gaiusFsPutBytes = (path, value) => {
    if (!storageLifecycleIsActive(generation)) return false;
    path = normalize(path);
    const bytes = toUint8Array(value);
    if (!bytes) return false;
    if (isRegionPath(path) && opfsAccessHandle) {
      try {
          const previous = regionIndex.get(path);
          if (Number(previous && previous.generation) >= 0xffffffff) {
            throw new Error("OPFS region generation exhausted; refusing a wrapping write");
          }
        appendOpfsRegion(path, bytes, false);
        // Java may reclaim the TeaVM copy as soon as this call returns true.
        flushOpfsSync();
        removeRegionCache(path);
        return true;
      } catch (error) {
        storageStats.writeErrors++;
        reportStorageError(error);
        return false;
      }
    }
    const copy = bytes.slice();
    if (isRegionPath(path)) {
      const version = nextChangeVersion();
      let commitGeneration;
      try {
        commitGeneration = allocateRegionCommitGeneration(regionIndex.get(path));
      } catch (error) {
        storageStats.writeErrors++;
        reportStorageError(error);
        return false;
      }
      if (!putRegionCache(path, copy, {
        dirty: true,
        pinned: false,
        flushing: false,
        version,
      })) {
        storageStats.rejectedWrites++;
        refreshStorageStats();
        postMessage({
          type: "storage-write-error",
          detail: "IndexedDB region cache budget exhausted for " + path,
        });
        return false;
      }
      regionIndex.set(path, {
        backend: "indexeddb",
        length: copy.byteLength,
        generation: commitGeneration,
        committedAt: Date.now(),
        pairCommitSequence: pairCommitSequenceForPath(path),
      });
      pendingChanges.set(path, {value: copy, version, region: true});
    } else {
      fileValues[path] = copy;
      queueIndexedDbChange(path, copy, false);
    }
    scheduleFlush(250);
    return true;
  };
  root.__gaiusFsCanPatchBytes = (path) => {
    if (!storageLifecycleIsActive(generation)) return false;
    path = normalize(path);
    const indexed = regionIndex.get(path);
    return !!opfsAccessHandle && isRegionPath(path) &&
      (!indexed || indexed.backend === "opfs");
  };
  root.__gaiusFsPatchBytes = (path, logicalSize, offsets, lengths, value) => {
    if (!storageLifecycleIsActive(generation)) return false;
    path = normalize(path);
    const indexed = regionIndex.get(path);
    if (!opfsAccessHandle || !isRegionPath(path) ||
        (indexed && indexed.backend !== "opfs")) return false;
    try {
      appendOpfsPatch(path, logicalSize, offsets, lengths, value);
      // One committed record may contain many ranges; flush exactly once after
      // the complete transaction (and any bounded checkpoint) has been appended.
      flushOpfsSync();
      removeRegionCache(path);
      return true;
    } catch (error) {
      storageStats.writeErrors++;
      reportStorageError(error);
      return false;
    }
  };
  root.__gaiusFsDelete = (path) => {
    if (!storageLifecycleIsActive(generation)) return false;
    path = normalize(path);
    if (isRegionPath(path)) {
      if (opfsAccessHandle && regionIndex.has(path)) {
        try {
          appendOpfsRegion(path, null, true);
          flushOpfsSync();
        } catch (error) {
          storageStats.writeErrors++;
          reportStorageError(error);
          return false;
        }
      }
      regionIndex.delete(path);
      removeRegionCache(path);
    } else {
      delete fileValues[path];
    }
    queueIndexedDbChange(path, null, isRegionPath(path));
    scheduleFlush();
    return true;
  };
  root.__gaiusFsFlush = () => {
    if (!storageLifecycleIsActive(generation)) {
      return Promise.reject(new Error("Persistent storage is closed"));
    }
    return flushPendingChanges();
  };
  root.__gaiusFsStorageSnapshot = () => ({...refreshStorageStats()});
  refreshStorageStats();
  return true;
}

function openDatabase() {
  return new Promise((resolve, reject) => {
    const databaseName = requiredStorageIdentifier(
      root.__gaiusStorageDatabaseName,
      "storageDatabaseName",
    );
    const schema = requiredStorageInteger(root.__gaiusStorageSchema, "storageSchema");
    const request = indexedDB.open(databaseName, schema);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(storeName)) {
        db.createObjectStore(storeName, {keyPath: "path"});
      }
    };
    request.onsuccess = () => {
      const opened = request.result;
      opened.onversionchange = () => opened.close();
      resolve(opened);
    };
    request.onerror = () => reject(request.error || new Error("IndexedDB open failed"));
    request.onblocked = () => reject(new Error("IndexedDB open blocked"));
  });
}

async function openOpfsRegionStore(worldId, generation = storageGeneration) {
  assertStorageLifecycleIsActive(generation);
  if (typeof Proxy !== "function" || typeof navigator === "undefined" ||
      !navigator.storage || typeof navigator.storage.getDirectory !== "function") {
    return false;
  }
  try {
    const storageRoot = await navigator.storage.getDirectory();
    assertStorageLifecycleIsActive(generation);
    const gaiusDirectory = await storageRoot.getDirectoryHandle("gaius", {create: true});
    assertStorageLifecycleIsActive(generation);
    const regionDirectory = await gaiusDirectory.getDirectoryHandle(
      requiredStorageIdentifier(
        root.__gaiusStorageOpfsDirectory,
        "storageOpfsDirectory",
      ),
      {create: true},
    );
    assertStorageLifecycleIsActive(generation);
    const fileHandle = await regionDirectory.getFileHandle(
      opfsContainerName(worldId),
      {create: true},
    );
    assertStorageLifecycleIsActive(generation);
    if (typeof fileHandle.createSyncAccessHandle !== "function") {
      return false;
    }
    const accessHandle = await acquireOpfsAccessHandle(fileHandle, generation);
    if (!storageLifecycleIsActive(generation)) {
      try { accessHandle.close(); } catch (ignored) {}
      return false;
    }
    opfsAccessHandle = accessHandle;
    scanOpfsRegionStore();
    return true;
  } catch (error) {
    if (opfsAccessHandle) {
      try { opfsAccessHandle.close(); } catch (ignored) {}
    }
    opfsAccessHandle = undefined;
    // Another context still holds this world's container. Its regions are only in OPFS, so an
    // IndexedDB session would not see them, regenerate those chunks, and the next OPFS session
    // would migrate the regenerated regions over the saved ones. Refuse to open instead.
    if (error && error.gaiusOpfsLocked === true) throw error;
    if (storageLifecycleIsActive(generation)) {
      postMessage({
        type: "storage-opfs-fallback",
        detail: String(error && (error.stack || error.message) || error),
      });
    }
    if (!storageLifecycleIsActive(generation)) return false;
    return false;
  }
}

function opfsWorldMarkerPath(worldId) {
  return "/gaius/storage/opfs-worlds/" + opfsContainerName(worldId);
}

async function hasOpfsWorldMarker(worldId) {
  const record = await readStoredRecord(opfsWorldMarkerPath(worldId));
  return !!record && record.value === "opfs-sync-worker-v1";
}

async function writeOpfsWorldMarker(worldId) {
  const transaction = database.transaction(storeName, "readwrite");
  transaction.objectStore(storeName).put({
    path: opfsWorldMarkerPath(worldId),
    value: "opfs-sync-worker-v1",
    updatedAt: Date.now(),
  });
  await transactionDone(transaction);
}

// World ownership. The Worker that opens a world holds the Web Lock
// "gaius-world-owner:<database>:<world>" for as long as its storage is open, and also
// "<owner>:closing" once it starts its final save. The browser drops a context's Web Locks when
// the context dies (tab closed, renderer killed, Worker terminated), so a session that ended
// without closing its storage never blocks the next one, while a live session is still found. This
// works for every backend, IndexedDB included: a page opened from disk never gets OPFS (and with it
// no exclusive container handle), so this lock is the only cross-tab guard for its worlds.
let worldOwnerLockRelease;
let worldOwnerClosingRelease;
let worldOwnerLockHeld = false;

function webLocksApi() {
  try {
    const locks = typeof navigator !== "undefined" ? navigator.locks : undefined;
    return locks && typeof locks.request === "function" && typeof locks.query === "function"
      ? locks : undefined;
  } catch (ignored) {
    return undefined;
  }
}

function worldOwnerLockName(worldId) {
  return "gaius-world-owner:" + String(root.__gaiusStorageDatabaseName || "") + ":" +
    opfsContainerName(worldId);
}

// How long a successor waits for a live previous session that is saving (its stop is bounded by
// the page's 35 s stop timeout), and for one that holds the world without closing it.
function worldOwnerClosingWaitMillis() {
  return configuredTimeout(root.__gaiusWorldOwnerClosingWaitMillis, 40000, 120000);
}

function worldOwnerBusyWaitMillis() {
  return configuredTimeout(root.__gaiusWorldOwnerBusyWaitMillis, 3000, 60000);
}

function webLockCallTimeoutMillis() {
  return configuredTimeout(root.__gaiusWebLockCallTimeoutMillis, 1000, 10000);
}

// Resolves to true (granted and held), false (held by another context) or null (no answer).
function requestHeldWebLock(locks, name, mode, assign) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (value) => {
      if (settled) return false;
      settled = true;
      resolve(value);
      return true;
    };
    const timer = setTimeout(() => finish(null), webLockCallTimeoutMillis());
    try {
      Promise.resolve(locks.request(name, {mode, ifAvailable: true}, (lock) => {
        clearTimeout(timer);
        if (!lock) {
          finish(false);
          return undefined;
        }
        // A grant that arrives after the caller gave up is released at once.
        if (settled) return undefined;
        return new Promise((release) => {
          assign(release);
          finish(true);
        });
      })).catch(() => {
        clearTimeout(timer);
        finish(null);
      });
    } catch (ignored) {
      clearTimeout(timer);
      finish(null);
    }
  });
}

async function queryWorldOwner(locks, name) {
  let timer;
  try {
    const snapshot = await Promise.race([
      Promise.resolve(locks.query()),
      new Promise((resolve) => { timer = setTimeout(() => resolve(null), webLockCallTimeoutMillis()); }),
    ]);
    if (!snapshot || !Array.isArray(snapshot.held)) return null;
    const held = snapshot.held.map((lock) => lock && lock.name);
    return {owner: held.includes(name), closing: held.includes(name + ":closing")};
  } catch (ignored) {
    return null;
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

// Returns true when this Worker now owns the world, or null when Web Locks are unavailable (the
// caller then relies on the storage backend's own exclusivity, as before). Throws a
// gaiusWorldInUse error when another live session keeps the world open.
async function acquireWorldOwnerLock(worldId, generation) {
  if (worldOwnerLockHeld) return true;
  const locks = webLocksApi();
  if (!locks) return null;
  const name = worldOwnerLockName(worldId);
  const started = Date.now();
  let waitingReported = false;
  for (;;) {
    const granted = await requestHeldWebLock(locks, name, "exclusive", (release) => {
      worldOwnerLockRelease = release;
    });
    if (granted === true) {
      if (!storageLifecycleIsActive(generation)) {
        releaseWorldOwnerLock();
        assertStorageLifecycleIsActive(generation);
      }
      worldOwnerLockHeld = true;
      return true;
    }
    if (granted === null) return null;
    assertStorageLifecycleIsActive(generation);
    const owner = await queryWorldOwner(locks, name);
    assertStorageLifecycleIsActive(generation);
    // No answer, a saving owner, or an owner that released the lock between the request and the
    // query (the next request then succeeds) all get the saving session's wait.
    const closing = !owner || owner.closing || !owner.owner;
    const limit = closing ? worldOwnerClosingWaitMillis() : worldOwnerBusyWaitMillis();
    if (Date.now() - started >= limit) {
      const error = new Error(closing
        ? "The previous session of this world is still saving. Wait a moment, then open the world again."
        : "This world is open in another Gaius tab or window. Leave the world there (Save and Quit to Title) or close that tab, then open it again.");
      error.gaiusWorldInUse = true;
      throw error;
    }
    if (!waitingReported && storageLifecycleIsActive(generation)) {
      waitingReported = true;
      postMessage({
        type: "storage-opfs-waiting",
        detail: "Waiting for the previous session to finish saving before opening this world",
      });
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
    assertStorageLifecycleIsActive(generation);
  }
}

// Marks a live owner as saving, so a successor waits for it instead of reporting the world busy.
function markWorldOwnerClosing() {
  if (!worldOwnerLockHeld || worldOwnerClosingRelease) return;
  const locks = webLocksApi();
  if (!locks) return;
  void requestHeldWebLock(locks, worldOwnerLockName(root.__gaiusServerWorldId) + ":closing",
    "shared", (release) => {
      if (worldOwnerLockHeld) worldOwnerClosingRelease = release;
      else release();
    });
}

function releaseWorldOwnerLock() {
  worldOwnerLockHeld = false;
  for (const release of [worldOwnerClosingRelease, worldOwnerLockRelease]) {
    if (typeof release !== "function") continue;
    try { release(); } catch (ignored) {}
  }
  worldOwnerClosingRelease = undefined;
  worldOwnerLockRelease = undefined;
}

function opfsLockWaitMillis() {
  return configuredTimeout(root.__gaiusOpfsLockWaitMillis, 5000, 60000);
}

// The container is held although no live Gaius session owns the world (this Worker holds the
// world owner lock): its previous holder ended without closing it and the browser has not
// released the handle yet. Give the browser longer than for a live handoff before failing.
function opfsStaleHandleWaitMillis() {
  return configuredTimeout(root.__gaiusOpfsStaleHandleWaitMillis, 30000, 120000);
}

function opfsLockProbeMillis() {
  // Chromium can leave createSyncAccessHandle() pending while a previous document
  // is being torn down. Bound one probe so startup can report a lock instead of
  // parking the Worker until the client-side 180 s readiness watchdog fires.
  return configuredTimeout(
    root.__gaiusOpfsLockProbeMillis,
    Math.min(1000, opfsLockWaitMillis()),
    10000,
  );
}

async function probeOpfsAccessHandle(fileHandle, timeoutMillis) {
  let abandoned = false;
  let timer;
  const pending = Promise.resolve().then(() => fileHandle.createSyncAccessHandle());
  // A browser may settle the abandoned request after the watchdog. Close the
  // late handle so a timed-out probe cannot leak the exclusive container lock.
  pending.then((handle) => {
    if (!abandoned || !handle || typeof handle.close !== "function") return;
    try { handle.close(); } catch (ignored) {}
  }, () => {});
  const timeoutError = new Error(
    "OPFS access-handle probe timed out after " + timeoutMillis + " ms",
  );
  timeoutError.gaiusOpfsProbeTimedOut = true;
  try {
    return await Promise.race([
      pending,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(timeoutError), timeoutMillis);
      }),
    ]);
  } catch (error) {
    if (error === timeoutError) abandoned = true;
    throw error;
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

// A SyncAccessHandle is exclusive. NoModificationAllowedError means another worker (a previous
// session of this world that is still shutting down, or another tab) holds the container: wait
// for it to be released, then fail closed rather than open the world without its OPFS regions.
async function acquireOpfsAccessHandle(fileHandle, generation) {
  const staleHolder = worldOwnerLockHeld;
  const deadline = Date.now() + (staleHolder ? opfsStaleHandleWaitMillis() : opfsLockWaitMillis());
  let waitingReported = false;
  for (;;) {
    try {
      const remaining = Math.max(1, deadline - Date.now());
      return await probeOpfsAccessHandle(
        fileHandle,
        Math.min(opfsLockProbeMillis(), remaining),
      );
    } catch (error) {
      const probeTimedOut = error && error.gaiusOpfsProbeTimedOut === true;
      if (!probeTimedOut && (!error || error.name !== "NoModificationAllowedError")) {
        throw error;
      }
      if (!waitingReported && storageLifecycleIsActive(generation)) {
        waitingReported = true;
        postMessage({
          type: "storage-opfs-waiting",
          detail: staleHolder
            ? "The previous session ended without closing this world; waiting for the browser to release its storage"
            : probeTimedOut
              ? "Waiting for the previous session to finish saving before opening this world (OPFS probe timed out)"
              : "Waiting for the previous session to finish saving before opening this world",
        });
      }
      if (Date.now() >= deadline) {
        const locked = new Error(staleHolder
          ? "World storage is still held by a Gaius session that has ended (OPFS container locked); " +
            "close every Gaius tab or restart the browser, then open the world again"
          : "World storage is in use by another Gaius session (OPFS container locked); " +
            "close the other tab or wait for the previous session to finish saving",
        );
        locked.gaiusOpfsLocked = true;
        throw locked;
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
    assertStorageLifecycleIsActive(generation);
  }
}

function opfsContainerName(worldId) {
  const value = String(worldId || "world");
  let hash = 0x811c9dc5;
  const bytes = new TextEncoder().encode(value);
  for (let index = 0; index < bytes.length; index++) {
    hash ^= bytes[index];
    hash = Math.imul(hash, 0x01000193);
  }
  const suffix = value.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 48) || "world";
  return suffix + "-" + (hash >>> 0).toString(16).padStart(8, "0") + ".regions";
}

function scanOpfsRegionStore() {
  const size = Number(opfsAccessHandle.getSize()) || 0;
  const prefix = new Uint8Array(8);
  const decoder = new TextDecoder();
  const worldPrefix = activeWorldPrefix();
  let offset = 0;
  while (offset + prefix.byteLength <= size) {
    if (readSync(opfsAccessHandle, prefix, offset) !== prefix.byteLength) break;
    const view = new DataView(prefix.buffer, prefix.byteOffset, prefix.byteLength);
    const magic = view.getUint32(0, true);
    const version = view.getUint32(4, true);
    let recordLength = 0;
    if (magic === opfsRecordMagic && version === opfsRecordVersion) {
      recordLength = scanOpfsV1Record(offset, size, decoder, worldPrefix);
    } else if (magic === opfsPatchRecordMagic && version === opfsPatchRecordVersion) {
      recordLength = scanOpfsV2Record(offset, size, decoder, worldPrefix);
    }
    if (recordLength <= 0) break;
    storageStats.opfsScanRecords++;
    offset += recordLength;
  }
  if (offset < size) {
    try {
      opfsAccessHandle.truncate(offset);
      storageStats.recoveredTailBytes = size - offset;
    } catch (error) {
      storageStats.writeErrors++;
      reportStorageError(error);
      offset = size;
    }
  }
  opfsAppendOffset = offset;
  storageStats.opfsFileBytes = offset;
  refreshStorageStats();
}

function scanOpfsV1Record(offset, size, decoder, worldPrefix) {
  if (offset + opfsRecordHeaderBytes > size) return 0;
  const header = new Uint8Array(opfsRecordHeaderBytes);
  if (readSync(opfsAccessHandle, header, offset) !== header.byteLength) return 0;
  const view = new DataView(header.buffer, header.byteOffset, header.byteLength);
  const state = view.getUint32(8, true);
  const pathLength = view.getUint32(12, true);
  const dataLength = view.getUint32(16, true);
  const recordLength = view.getUint32(20, true);
  if ((state !== opfsRecordLive && state !== opfsRecordDeleted) ||
      pathLength === 0 || pathLength > 4096 ||
      recordLength !== opfsRecordHeaderBytes + pathLength + dataLength ||
      offset + recordLength > size) {
    return 0;
  }
  const pathBytes = new Uint8Array(pathLength);
  if (readSync(opfsAccessHandle, pathBytes, offset + opfsRecordHeaderBytes) !== pathLength) {
    return 0;
  }
  const path = normalize(decoder.decode(pathBytes));
  if (path.startsWith("/gaius/storage/region-pair-seq/")) {
    const valueBytes = new Uint8Array(dataLength);
    if (readSync(opfsAccessHandle, valueBytes,
      offset + opfsRecordHeaderBytes + pathLength) !== dataLength) return 0;
    const sequence = Number(new TextDecoder().decode(valueBytes));
    if (Number.isSafeInteger(sequence) && sequence > 0) {
      regionPairSequences.set(path.slice("/gaius/storage/region-pair-seq/".length), sequence);
      pairCommitSequenceClock = Math.max(pairCommitSequenceClock, sequence);
    }
    storageStats.opfsScanV1Records++;
    return recordLength;
  }
  if (path.startsWith(worldPrefix) && isRegionPath(path)) {
    if (state === opfsRecordDeleted) {
      regionIndex.delete(path);
    } else {
      // V1 has no persisted generation, so its logical value is zero. Preserve
      // the per-path sequence separately for compatibility with the frozen reader.
      const sequence = nextOpfsRegionGeneration(regionIndex.get(path));
      regionIndex.set(path, fullOpfsRegionEntry(
        offset + opfsRecordHeaderBytes + pathLength,
        dataLength,
        0,
        sequence,
        path,
      ));
    }
  }
  storageStats.opfsScanV1Records++;
  return recordLength;
}

function scanOpfsV2Record(offset, size, decoder, worldPrefix) {
  if (offset + opfsPatchRecordHeaderBytes > size) return 0;
  const header = new Uint8Array(opfsPatchRecordHeaderBytes);
  if (readSync(opfsAccessHandle, header, offset) !== header.byteLength) return 0;
  const view = new DataView(header.buffer, header.byteOffset, header.byteLength);
  const state = view.getUint32(8, true);
  const pathLength = view.getUint32(12, true);
  const logicalSize = view.getUint32(16, true);
  const rangeCount = view.getUint32(20, true);
  const metadataLength = view.getUint32(24, true);
  const payloadLength = view.getUint32(28, true);
  const recordLength = view.getUint32(32, true);
  const generation = view.getUint32(36, true);
  const checksum = view.getUint32(40, true);
  const commitMagic = view.getUint32(44, true);
  if (state !== opfsPatchRecordState || commitMagic !== opfsPatchCommitMagic ||
      pathLength === 0 || pathLength > 4096 ||
      rangeCount > maximumOpfsPatchRanges || metadataLength !== rangeCount * 8 ||
      generation === 0 ||
      recordLength !== opfsPatchRecordHeaderBytes + pathLength + metadataLength + payloadLength ||
      offset + recordLength > size) {
    return 0;
  }

  const pathBytes = new Uint8Array(pathLength);
  const metadata = new Uint8Array(metadataLength);
  const payload = new Uint8Array(payloadLength);
  let cursor = offset + opfsPatchRecordHeaderBytes;
  if (readSync(opfsAccessHandle, pathBytes, cursor) !== pathLength) return 0;
  cursor += pathLength;
  if (readSync(opfsAccessHandle, metadata, cursor) !== metadataLength) return 0;
  cursor += metadataLength;
  if (readSync(opfsAccessHandle, payload, cursor) !== payloadLength) return 0;
  if (opfsPatchChecksum(pathBytes, metadata, payload, logicalSize, generation) !== checksum) {
    return 0;
  }

  const ranges = decodeOpfsPatchRanges(metadata, logicalSize, payloadLength);
  if (!ranges) return 0;
  const path = normalize(decoder.decode(pathBytes));
  if (path.startsWith(worldPrefix) && isRegionPath(path)) {
    const previous = regionIndex.get(path);
    if (generation !== nextOpfsRegionGeneration(previous)) return 0;
    regionIndex.set(path, patchedOpfsRegionEntry(
      previous,
      logicalSize,
      generation,
      cursor,
      payloadLength,
      ranges,
    ));
  }
  storageStats.opfsScanV2Records++;
  return recordLength;
}

function fullOpfsRegionEntry(
  dataOffset, dataLength, generation, opfsSequence = generation, path = "",
) {
  return {
    backend: "opfs",
    offset: dataOffset,
    length: dataLength,
    baseOffset: dataOffset,
    baseLength: dataLength,
    logicalSize: dataLength,
    generation,
    opfsSequence,
    pairCommitSequence: pairCommitSequenceForPath(path),
    patches: [],
    patchBytes: 0,
  };
}

function patchedOpfsRegionEntry(
  previous, logicalSize, generation, dataOffset, payloadLength, ranges, path = "",
) {
  const hasBase = previous && previous.backend === "opfs";
  const patches = hasBase && Array.isArray(previous.patches)
    ? previous.patches.slice()
    : [];
  patches.push({dataOffset, payloadLength, logicalSize, ranges});
  return {
    backend: "opfs",
    offset: hasBase ? previous.baseOffset : undefined,
    length: logicalSize,
    baseOffset: hasBase ? previous.baseOffset : undefined,
    baseLength: hasBase ? previous.baseLength : 0,
    logicalSize,
    generation,
    opfsSequence: generation,
    pairCommitSequence: pairCommitSequenceForPath(path),
    patches,
    patchBytes: (hasBase ? Number(previous.patchBytes) || 0 : 0) + payloadLength,
  };
}

function nextOpfsRegionGeneration(previous) {
  const previousGeneration = Number(previous &&
    (previous.opfsSequence ?? previous.generation)) || 0;
  if (previousGeneration >= 0xffffffff) {
    throw new Error("OPFS region generation exhausted; refusing a wrapping write");
  }
  return previousGeneration + 1;
}

function allocateRegionCommitGeneration(previous) {
  const previousGeneration = Number(previous && previous.generation) || 0;
  if (previousGeneration >= 0xffffffff) {
    throw new Error("IndexedDB region generation exhausted; refusing a wrapping write");
  }
  return previousGeneration + 1;
}

function regionRecordGeneration(record) {
  const generation = Number(record && record.generation);
  return Number.isSafeInteger(generation) && generation > 0 ? generation : 0;
}

function regionRecordPairSequence(record) {
  const sequence = Number(record && record.pairCommitSequence);
  return Number.isSafeInteger(sequence) && sequence > 0 ? sequence : 0;
}

function pairCommitSequenceForPath(path) {
  const group = regionGroupKey(normalize(path));
  let sequence = regionPairSequences.get(group);
  if (!Number.isSafeInteger(sequence) || sequence <= 0) {
    if (pairCommitSequenceClock >= Number.MAX_SAFE_INTEGER) {
      throw new Error("Region pair commit sequence exhausted; refusing a wrapping write");
    }
    sequence = ++pairCommitSequenceClock;
    regionPairSequences.set(group, sequence);
  }
  return sequence;
}

function opfsPairMetadataPath(path) {
  return "/gaius/storage/region-pair-seq/" + regionGroupKey(path);
}

function appendOpfsPairMetadata(path, sequence) {
  if (!opfsAccessHandle || !Number.isSafeInteger(sequence) || sequence <= 0) return;
  const metadataPath = opfsPairMetadataPath(path);
  const pathBytes = new TextEncoder().encode(metadataPath);
  const valueBytes = new TextEncoder().encode(String(sequence));
  const recordLength = opfsRecordHeaderBytes + pathBytes.byteLength + valueBytes.byteLength;
  const offset = opfsAppendOffset;
  writeSync(opfsAccessHandle, new Uint8Array(opfsRecordHeaderBytes), offset);
  writeSync(opfsAccessHandle, pathBytes, offset + opfsRecordHeaderBytes);
  writeSync(opfsAccessHandle, valueBytes, offset + opfsRecordHeaderBytes + pathBytes.byteLength);
  const header = new Uint8Array(opfsRecordHeaderBytes);
  const view = new DataView(header.buffer);
  view.setUint32(0, opfsRecordMagic, true);
  view.setUint32(4, opfsRecordVersion, true);
  view.setUint32(8, opfsRecordLive, true);
  view.setUint32(12, pathBytes.byteLength, true);
  view.setUint32(16, valueBytes.byteLength, true);
  view.setUint32(20, recordLength, true);
  writeSync(opfsAccessHandle, header, offset);
  opfsAppendOffset += recordLength;
  opfsDirty = true;
  storageStats.opfsFileBytes = opfsAppendOffset;
}

function regionRecordCommittedAt(record) {
  const committedAt = Number(record && (record.committedAt ?? record.updatedAt));
  return Number.isFinite(committedAt) && committedAt > 0 ? committedAt : 0;
}

function appendOpfsRegion(path, value, deleted) {
  if (!opfsAccessHandle) {
    throw new Error("OPFS region handle is unavailable");
  }
  const pathBytes = new TextEncoder().encode(path);
  const bytes = deleted ? new Uint8Array(0) : toUint8Array(value);
  if (!bytes) {
    throw new Error("Region payload is not a byte array: " + path);
  }
  // New whole-file records use the V2 journal format, which persists the same
  // generation metadata used by IndexedDB conflict recovery. Keep the legacy V1
  // encoding only for empty deletion tombstones.
  if (!deleted && bytes.byteLength > 0) {
    appendOpfsPatch(path, bytes.byteLength, [0], [bytes.byteLength], bytes, true);
    // The V2 journal is the durable encoding, while these counters retain their
    // public meaning: whole-file writes are not region patch transactions.
    storageStats.opfsPatchWrites--;
    storageStats.opfsPatchPayloadBytes -= bytes.byteLength;
    storageStats.opfsPatchRanges--;
    storageStats.opfsFullWrites++;
    storageStats.opfsFullWriteBytes += bytes.byteLength;
    refreshStorageStats();
    return;
  }
  const recordLength = opfsRecordHeaderBytes + pathBytes.byteLength + bytes.byteLength;
  const offset = opfsAppendOffset;
  const generation = nextOpfsRegionGeneration(regionIndex.get(path));
  writeSync(opfsAccessHandle, new Uint8Array(opfsRecordHeaderBytes), offset);
  writeSync(opfsAccessHandle, pathBytes, offset + opfsRecordHeaderBytes);
  if (bytes.byteLength > 0) {
    writeSync(
      opfsAccessHandle,
      bytes,
      offset + opfsRecordHeaderBytes + pathBytes.byteLength,
    );
  }
  const header = new Uint8Array(opfsRecordHeaderBytes);
  const view = new DataView(header.buffer);
  view.setUint32(0, opfsRecordMagic, true);
  view.setUint32(4, opfsRecordVersion, true);
  view.setUint32(8, deleted ? opfsRecordDeleted : opfsRecordLive, true);
  view.setUint32(12, pathBytes.byteLength, true);
  view.setUint32(16, bytes.byteLength, true);
  view.setUint32(20, recordLength, true);
  writeSync(opfsAccessHandle, header, offset);
  opfsAppendOffset += recordLength;
  opfsDirty = true;
  storageStats.opfsFileBytes = opfsAppendOffset;
  if (deleted) {
    regionIndex.delete(path);
  } else {
    regionIndex.set(path, fullOpfsRegionEntry(
      offset + opfsRecordHeaderBytes + pathBytes.byteLength,
      bytes.byteLength,
      generation,
      generation,
      path,
    ));
    storageStats.opfsFullWrites++;
    storageStats.opfsFullWriteBytes += bytes.byteLength;
  }
  refreshStorageStats();
}

function appendOpfsPatch(path, logicalSizeValue, offsets, lengths, value, wholeFile = false) {
  if (!opfsAccessHandle) {
    throw new Error("OPFS region handle is unavailable");
  }
  const logicalSize = Number(logicalSizeValue);
  const payload = toUint8Array(value);
  const rangeCount = Number(offsets && offsets.length);
  if (!Number.isInteger(logicalSize) || logicalSize < 0 || logicalSize > 0xffffffff ||
      !payload || !Number.isInteger(rangeCount) || rangeCount < 0 ||
      rangeCount > maximumOpfsPatchRanges || !lengths || lengths.length !== rangeCount) {
    throw new Error("Invalid OPFS region patch for " + path);
  }

  const metadata = new Uint8Array(rangeCount * 8);
  const metadataView = new DataView(metadata.buffer);
  let payloadLength = 0;
  let previousEnd = 0;
  for (let index = 0; index < rangeCount; index++) {
    const rangeOffset = Number(offsets[index]);
    const rangeLength = Number(lengths[index]);
    const rangeEnd = rangeOffset + rangeLength;
    if (!Number.isInteger(rangeOffset) || !Number.isInteger(rangeLength) ||
        rangeOffset < previousEnd || rangeLength <= 0 || rangeEnd > logicalSize) {
      throw new Error("Invalid OPFS patch range " + index + " for " + path);
    }
    metadataView.setUint32(index * 8, rangeOffset, true);
    metadataView.setUint32(index * 8 + 4, rangeLength, true);
    payloadLength += rangeLength;
    previousEnd = rangeEnd;
  }
  if (payloadLength !== payload.byteLength) {
    throw new Error("OPFS patch payload length mismatch for " + path);
  }

  let previous = regionIndex.get(path);
  if (!wholeFile && shouldCheckpointOpfsRegion(previous, payloadLength)) {
    checkpointOpfsRegion(path, previous);
    previous = regionIndex.get(path);
  }
  const generation = nextOpfsRegionGeneration(previous);
  const pathBytes = new TextEncoder().encode(path);
  if (pathBytes.byteLength === 0 || pathBytes.byteLength > 4096) {
    throw new Error("Invalid OPFS patch path length for " + path);
  }
  const recordLength = opfsPatchRecordHeaderBytes + pathBytes.byteLength +
    metadata.byteLength + payload.byteLength;
  if (recordLength > 0xffffffff) {
    throw new Error("OPFS patch record is too large for " + path);
  }

  const pairSequence = pairCommitSequenceForPath(path);
  // Keep the compatibility sidecar immediately before the V2 record so old
  // readers still finish on the committed V2 record at the journal tail.
  appendOpfsPairMetadata(path, pairSequence);
  const offset = opfsAppendOffset;
  let cursor = offset + opfsPatchRecordHeaderBytes;
  writeSync(opfsAccessHandle, new Uint8Array(opfsPatchRecordHeaderBytes), offset);
  writeSync(opfsAccessHandle, pathBytes, cursor);
  cursor += pathBytes.byteLength;
  if (metadata.byteLength > 0) {
    writeSync(opfsAccessHandle, metadata, cursor);
  }
  cursor += metadata.byteLength;
  if (payload.byteLength > 0) {
    writeSync(opfsAccessHandle, payload, cursor);
  }

  const header = new Uint8Array(opfsPatchRecordHeaderBytes);
  const view = new DataView(header.buffer);
  view.setUint32(0, opfsPatchRecordMagic, true);
  view.setUint32(4, opfsPatchRecordVersion, true);
  view.setUint32(8, opfsPatchRecordState, true);
  view.setUint32(12, pathBytes.byteLength, true);
  view.setUint32(16, logicalSize, true);
  view.setUint32(20, rangeCount, true);
  view.setUint32(24, metadata.byteLength, true);
  view.setUint32(28, payload.byteLength, true);
  view.setUint32(32, recordLength, true);
  view.setUint32(36, generation, true);
  view.setUint32(
    40,
    opfsPatchChecksum(pathBytes, metadata, payload, logicalSize, generation),
    true,
  );
  view.setUint32(44, opfsPatchCommitMagic, true);
  writeSync(opfsAccessHandle, header, offset);

  opfsAppendOffset += recordLength;
  opfsDirty = true;
  storageStats.opfsFileBytes = opfsAppendOffset;
  const ranges = decodeOpfsPatchRanges(metadata, logicalSize, payload.byteLength);
  if (!ranges) {
    throw new Error("Could not decode committed OPFS patch ranges for " + path);
  }
  regionIndex.set(path, patchedOpfsRegionEntry(
    wholeFile ? null : previous,
    logicalSize,
    generation,
    cursor,
    payload.byteLength,
    ranges,
      path,
  ));
  storageStats.opfsPatchWrites++;
  storageStats.opfsPatchPayloadBytes += payload.byteLength;
  storageStats.opfsPatchRanges += rangeCount;
  refreshStorageStats();
}

function decodeOpfsPatchRanges(metadata, logicalSize, payloadLength) {
  if (metadata.byteLength % 8 !== 0) return null;
  const ranges = [];
  const view = new DataView(metadata.buffer, metadata.byteOffset, metadata.byteLength);
  let previousEnd = 0;
  let payloadOffset = 0;
  for (let index = 0; index < metadata.byteLength / 8; index++) {
    const offset = view.getUint32(index * 8, true);
    const length = view.getUint32(index * 8 + 4, true);
    const end = offset + length;
    if (length === 0 || offset < previousEnd || end > logicalSize ||
        payloadOffset + length > payloadLength) {
      return null;
    }
    ranges.push({offset, length, payloadOffset});
    previousEnd = end;
    payloadOffset += length;
  }
  return payloadOffset === payloadLength ? ranges : null;
}

function shouldCheckpointOpfsRegion(indexed, incomingPayloadBytes) {
  if (!indexed || indexed.backend !== "opfs") return false;
  const patches = Array.isArray(indexed.patches) ? indexed.patches.length : 0;
  const patchBytes = Number(indexed.patchBytes) || 0;
  return patches >= maximumOpfsPatchChainRecords ||
    patchBytes + incomingPayloadBytes > maximumOpfsPatchChainBytes;
}

function checkpointOpfsRegion(path, indexed) {
  const bytes = materializeOpfsRegion(indexed);
  storageStats.opfsPatchCheckpoints++;
  appendOpfsRegion(path, bytes, false);
}

function materializeOpfsRegion(indexed) {
  const baseLength = Math.max(0, Number(indexed.baseLength) || 0);
  let bytes = new Uint8Array(baseLength);
  if (baseLength > 0) {
    if (readSync(opfsAccessHandle, bytes, indexed.baseOffset) !== baseLength) {
      throw new Error("Could not read complete OPFS region base");
    }
  }
  const patches = Array.isArray(indexed.patches) ? indexed.patches : [];
  for (const patch of patches) {
    if (bytes.byteLength !== patch.logicalSize) {
      const resized = new Uint8Array(patch.logicalSize);
      resized.set(bytes.subarray(0, Math.min(bytes.byteLength, resized.byteLength)));
      bytes = resized;
    }
    const payload = new Uint8Array(patch.payloadLength);
    if (readSync(opfsAccessHandle, payload, patch.dataOffset) !== payload.byteLength) {
      throw new Error("Could not read complete OPFS region patch");
    }
    for (const range of patch.ranges) {
      bytes.set(
        payload.subarray(range.payloadOffset, range.payloadOffset + range.length),
        range.offset,
      );
    }
  }
  const logicalSize = Math.max(0, Number(indexed.logicalSize ?? indexed.length) || 0);
  if (bytes.byteLength !== logicalSize) {
    const resized = new Uint8Array(logicalSize);
    resized.set(bytes.subarray(0, Math.min(bytes.byteLength, resized.byteLength)));
    bytes = resized;
  }
  storageStats.opfsReconstructedRegions++;
  return bytes;
}

function opfsPatchChecksum(pathBytes, metadata, payload, logicalSize, generation) {
  const scalar = new Uint8Array(8);
  const scalarView = new DataView(scalar.buffer);
  scalarView.setUint32(0, logicalSize, true);
  scalarView.setUint32(4, generation, true);
  let crc = 0xffffffff;
  crc = updateOpfsCrc32(crc, pathBytes);
  crc = updateOpfsCrc32(crc, scalar);
  crc = updateOpfsCrc32(crc, metadata);
  crc = updateOpfsCrc32(crc, payload);
  return (crc ^ 0xffffffff) >>> 0;
}

function updateOpfsCrc32(crc, bytes) {
  if (!opfsCrcTable) {
    opfsCrcTable = new Uint32Array(256);
    for (let index = 0; index < opfsCrcTable.length; index++) {
      let value = index;
      for (let bit = 0; bit < 8; bit++) {
        value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
      }
      opfsCrcTable[index] = value >>> 0;
    }
  }
  for (let index = 0; index < bytes.byteLength; index++) {
    crc = (crc >>> 8) ^ opfsCrcTable[(crc ^ bytes[index]) & 0xff];
  }
  return crc >>> 0;
}

function readSync(handle, output, at) {
  let offset = 0;
  while (offset < output.byteLength) {
    const read = Number(handle.read(output.subarray(offset), {at: at + offset})) || 0;
    if (read <= 0) break;
    offset += read;
  }
  return offset;
}

function writeSync(handle, input, at) {
  let offset = 0;
  while (offset < input.byteLength) {
    const written = Number(handle.write(input.subarray(offset), {at: at + offset})) || 0;
    if (written <= 0) {
      throw new Error("OPFS SyncAccessHandle wrote zero bytes");
    }
    offset += written;
  }
}

async function readWorldFiles(worldId, generation = storageGeneration) {
  assertStorageLifecycleIsActive(generation);
  const worldPrefix = "/gaius/saves/" + String(worldId || "") + "/";
  const paths = await listStoredPaths(worldPrefix);
  assertStorageLifecycleIsActive(generation);
  const migratedPaths = [];
  const skippedPaths = [];
  const regionRecords = [];
  for (const path of paths) {
    if (isRecoveryBackupPath(path)) continue;
    const entry = await readStoredRecord(path);
    assertStorageLifecycleIsActive(generation);
    if (!entry) continue;
    const value = await decodeStoredValue(path, entry.value);
    assertStorageLifecycleIsActive(generation);
    if (value === undefined) continue;
    if (isRegionPath(path)) {
      const bytes = toUint8Array(value);
      if (!bytes) {
        throw new Error("Stored region is not a byte array: " + path);
      }
      // The commit clock spans paths and backends. Observe every persisted IDB
      // generation before any subsequent OPFS or fallback write allocates one.
      if (opfsAccessHandle) {
        regionRecords.push({path, bytes, entry});
        continue;
      }
      const copy = bytes.slice();
      // Keep the hot compatibility window for synchronous VFS reads, but leave
      // older regions on IndexedDB when the Worker LRU reaches its bound.
      putRegionCache(path, copy, {
        dirty: false,
        pinned: false,
        flushing: false,
        version: 0,
      });
      regionIndex.set(path, {
        backend: "indexeddb",
        length: copy.byteLength,
        generation: regionRecordGeneration(entry),
        committedAt: regionRecordCommittedAt(entry),
      });
    } else {
      fileValues[path] = value;
    }
  }
  if (opfsAccessHandle) {
    const conflictGroups = new Set();
    for (const record of regionRecords) {
      const held = regionIndex.get(record.path);
      if (held && held.backend === "opfs" &&
          !byteArraysEqual(materializeOpfsRegion(held), record.bytes)) {
        conflictGroups.add(regionGroupKey(record.path));
      }
    }
    const conflicts = new Map();
    for (const record of regionRecords) {
      const held = regionIndex.get(record.path);
      if (!held || held.backend !== "opfs") {
        if (conflictGroups.has(regionGroupKey(record.path))) continue;
        appendOpfsRegion(record.path, record.bytes, false);
        migratedPaths.push(record.path);
        storageStats.migratedRegions++;
        continue;
      }
      const opfsBytes = materializeOpfsRegion(held);
      if (byteArraysEqual(opfsBytes, record.bytes)) {
        if (conflictGroups.has(regionGroupKey(record.path))) continue;
        migratedPaths.push(record.path);
        continue;
      }
      const group = regionGroupKey(record.path);
      if (!conflicts.has(group)) conflicts.set(group, []);
      conflicts.get(group).push(record);
    }
    for (const [group] of conflicts) {
      // A byte-identical partner is still part of the IndexedDB source for
      // whole-group recovery.  Otherwise an identical .mca/.mcc partner is
      // mistaken for an OPFS-only file when its sibling differs.
      const groupRecords = regionRecords.filter((record) =>
        regionGroupKey(record.path) === group);
      const idbGroupPaths = new Set(groupRecords.map((record) => record.path));
      const opfsOnly = [...regionIndex.keys()].filter((path) =>
        regionGroupKey(path) === group &&
        regionIndex.get(path)?.backend === "opfs" && !idbGroupPaths.has(path));
      if (opfsOnly.length > 0) {
        throw new Error(
          "Region recovery refused a mixed-source region; IndexedDB is missing paired files: " +
            opfsOnly.slice(0, 4).join(", "),
        );
      }
      await recoverRegionConflictGroup(worldId, groupRecords, generation);
      storageStats.recoveredRegionConflicts =
        (storageStats.recoveredRegionConflicts || 0) + 1;
    }
  }
  if (skippedPaths.length > 0) {
    postMessage({
      type: "storage-region-migration-skipped",
      detail: skippedPaths.length + " IndexedDB region(s) already held in OPFS: " +
        skippedPaths.slice(0, 4).join(", "),
    });
  }
  if (migratedPaths.length > 0) {
    assertStorageLifecycleIsActive(generation);
    flushOpfsSync();
    await deleteStoredPaths(migratedPaths);
    assertStorageLifecycleIsActive(generation);
  }
  refreshStorageStats();
}

function byteArraysEqual(left, right) {
  if (!left || !right || left.byteLength !== right.byteLength) return false;
  for (let index = 0; index < left.byteLength; index++) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

const RECOVERY_BACKUP_LIMIT = 2;

function isRecoveryBackupPath(path) {
  return String(path || "").includes("/.gaius-backups/");
}

function recoveryJournalPath(worldId) {
  return "/gaius/storage/recovery-journal/" + opfsContainerName(worldId);
}

function recoveryBackupPath(worldId, generation, originalPath) {
  const safe = String(originalPath || "region").replace(/[^A-Za-z0-9._-]/g, "_");
  return "/gaius/saves/" + String(worldId || "world") + "/.gaius-backups/" +
    String(generation) + "/" + safe + ".bak";
}

function regionGroupKey(path) {
  const name = String(path || "").split("/").pop() || "";
  let match = /^r\.(-?\d+)\.(-?\d+)\.mca$/.exec(name);
  if (match) return match[1] + "," + match[2];
  match = /^c\.(-?\d+)\.(-?\d+)\.mcc$/.exec(name);
  if (match) {
    const x = Math.floor(Number(match[1]) / 32);
    const z = Math.floor(Number(match[2]) / 32);
    return x + "," + z;
  }
  return String(path || "");
}

function regionHeaderIsSane(path, bytes) {
  if (!String(path).endsWith(".mca")) return bytes && bytes.byteLength > 0;
  if (!bytes || bytes.byteLength < 8192 || bytes.byteLength % 4096 !== 0) return false;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const sectors = bytes.byteLength / 4096;
  for (let index = 0; index < 1024; index++) {
    const location = view.getUint32(index * 4, false);
    const sector = location >>> 8;
    const count = location & 0xff;
    if (count === 0) continue;
    if (sector < 2 || sector + count > sectors) return false;
    const offset = sector * 4096;
    const length = view.getUint32(offset, false);
    if (length < 1 || length > count * 4096 - 4) return false;
    const compression = view.getUint8(offset + 4);
    const compressionId = compression & 0x7f;
    if (![1, 2, 3, 4].includes(compressionId)) return false;
    if ((compression & 0x80) !== 0 && length !== 1) return false;
  }
  return true;
}

async function writeRecoveryJournal(path, journal) {
  const transaction = database.transaction(storeName, "readwrite");
  transaction.objectStore(storeName).put({path, value: journal, updatedAt: Date.now()});
  await transactionDone(transaction);
}

function recoveryCheckpoint(step) {
  if (root.__gaiusRecoveryCrashStep === step) {
    root.__gaiusRecoveryCrashStep = undefined;
    throw new Error("Injected recovery crash at " + step);
  }
}

async function readRecoveryJournal(worldId) {
  const record = await readStoredRecord(recoveryJournalPath(worldId));
  return record && record.value && typeof record.value === "object" ? record.value : null;
}

async function recoverRegionConflictGroup(worldId, records, generation) {
  assertStorageLifecycleIsActive(generation);
  const group = regionGroupKey(records[0].path);
  const paths = records.map((record) => record.path);
  const sourcePaths = new Set(paths);
  // A pair-level sequence is the only value that can order .mca and .mcc as
  // one snapshot.  It is optional so old records remain readable; when either
  // side lacks a complete pair sequence, decide each path using only that
  // path's own generation and never compare counters from different paths.
  const isRegionPair = records.some((record) => record.path.endsWith(".mca")) &&
    records.some((record) => record.path.endsWith(".mcc"));
  const opfsPairSequences = isRegionPair ? records.map((record) =>
    regionRecordPairSequence(regionIndex.get(record.path))).filter(Boolean) : [];
  const idbPairSequences = isRegionPair ? records.map((record) =>
    regionRecordPairSequence(record.entry)).filter(Boolean) : [];
  const opfsPairSequence = opfsPairSequences.length === records.length &&
    new Set(opfsPairSequences).size === 1 ? opfsPairSequences[0] : 0;
  const idbPairSequence = idbPairSequences.length === records.length &&
    new Set(idbPairSequences).size === 1 ? idbPairSequences[0] : 0;
  const pairWinner = opfsPairSequence > 0 && idbPairSequence > 0
    ? (opfsPairSequence > idbPairSequence ? "opfs" : "indexeddb")
    : null;
  const decisions = records.map((record) => {
    const held = regionIndex.get(record.path);
    const opfsBytes = held && held.backend === "opfs"
      ? materializeOpfsRegion(held)
      : null;
    let winner = pairWinner;
    if (!winner) {
      const opfsGeneration = regionRecordGeneration(held);
      const idbGeneration = regionRecordGeneration(record.entry);
      winner = opfsGeneration > idbGeneration ? "opfs" : "indexeddb";
    }
    return {record, held, opfsBytes, winner};
  });
  const missingPairs = [...regionIndex.keys()].filter((path) =>
    regionGroupKey(path) === group && !sourcePaths.has(path));
  if (missingPairs.length > 0) {
    throw new Error(
      "Region recovery refused a mixed-source region; IndexedDB is missing paired files: " +
        missingPairs.slice(0, 4).join(", "),
    );
  }
  const journalPath = recoveryJournalPath(worldId);
  const backupPrefix = "/gaius/saves/" + String(worldId || "world") + "/.gaius-backups/";
  let journal = await readRecoveryJournal(worldId);
  assertStorageLifecycleIsActive(generation);
  if (!journal || journal.state === "done") {
    const stamp = Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 8);
    journal = {version: 2, state: "intent", worldId: String(worldId), group,
      paths, source: pairWinner ? "pair-sequence-ordered" : "path-generation-ordered",
      backupGeneration: stamp, backupDir: backupPrefix + stamp,
      createdAt: Date.now()};
    await writeRecoveryJournal(journalPath, journal);
    assertStorageLifecycleIsActive(generation);
  }
  // Older journals already recorded backupDir at intent time. Reuse it as well, and
  // refuse an unrelated pending operation rather than replacing its durable intent.
  const backupDir = String(journal.backupDir || "");
  const stamp = backupDir.startsWith(backupPrefix) ? backupDir.slice(backupPrefix.length) : "";
  if (journal.worldId !== String(worldId) || journal.group !== group ||
      !Array.isArray(journal.paths) || journal.paths.length !== paths.length ||
      !journal.paths.every((path) => sourcePaths.has(path)) || !stamp || stamp.includes("/") ||
      (journal.backupGeneration && journal.backupGeneration !== stamp)) {
    throw new Error("Unfinished region recovery has inconsistent backup intent");
  }
  // Reserve one of the two retained generations before writing any new backup.
  // Pin the pending intent even when its generation has no backup records yet.
  await pruneRecoveryBackups(worldId, generation, stamp);
  assertStorageLifecycleIsActive(generation);
  recoveryCheckpoint("intent");
  assertStorageLifecycleIsActive(generation);
  const backups = [];
  for (const decision of decisions) {
    const record = decision.record;
    const held = decision.held;
    if (decision.winner === "opfs") {
      const backupPath = recoveryBackupPath(worldId, stamp, record.path);
      backups.push({path: record.path, backupPath});
      journal.backups = backups;
      const existingBackup = await readStoredRecord(backupPath);
      assertStorageLifecycleIsActive(generation);
      if (!existingBackup) {
        const transaction = database.transaction(storeName, "readwrite");
        transaction.objectStore(storeName).put({path: backupPath, value: record.bytes.slice(),
          generation: regionRecordGeneration(record.entry),
          committedAt: regionRecordCommittedAt(record.entry),
          recoveryBackup: true, originalPath: record.path, updatedAt: Date.now()});
        await transactionDone(transaction);
      }
      continue;
    }
    if (!held || held.backend !== "opfs") continue;
    const backupPath = recoveryBackupPath(worldId, stamp, record.path);
    backups.push({path: record.path, backupPath});
    journal.backups = backups;
    // A previous attempt may already have replaced OPFS. Never overwrite its
    // original backup with the replacement bytes when resuming the same intent.
    const existingBackup = await readStoredRecord(backupPath);
    assertStorageLifecycleIsActive(generation);
    if (existingBackup) continue;
    const transaction = database.transaction(storeName, "readwrite");
    transaction.objectStore(storeName).put({path: backupPath, value: materializeOpfsRegion(held).slice(),
      recoveryBackup: true, originalPath: record.path, updatedAt: Date.now()});
    await transactionDone(transaction);
    assertStorageLifecycleIsActive(generation);
  }
  journal.state = "backed-up";
  await writeRecoveryJournal(journalPath, journal);
  assertStorageLifecycleIsActive(generation);
  recoveryCheckpoint("backup");
  assertStorageLifecycleIsActive(generation);
  for (const decision of decisions) {
    if (decision.winner === "indexeddb") {
      appendOpfsRegion(decision.record.path, decision.record.bytes, false);
    }
  }
  flushOpfsSync();
  recoveryCheckpoint("replace");
  assertStorageLifecycleIsActive(generation);
  for (const decision of decisions) {
    const actual = materializeOpfsRegion(regionIndex.get(decision.record.path));
    const expected = decision.winner === "opfs" ? decision.opfsBytes : decision.record.bytes;
    if (!byteArraysEqual(actual, expected) || !regionHeaderIsSane(decision.record.path, actual)) {
      throw new Error("Region recovery verification failed: " + decision.record.path);
    }
  }
  journal.state = "verified";
  await writeRecoveryJournal(journalPath, journal);
  assertStorageLifecycleIsActive(generation);
  recoveryCheckpoint("verify");
  assertStorageLifecycleIsActive(generation);
  const transaction = database.transaction(storeName, "readwrite");
  recoveryCheckpoint("dedupe");
  assertStorageLifecycleIsActive(generation);
  const store = transaction.objectStore(storeName);
  for (const record of records) store.delete(record.path);
  store.put({path: journalPath, value: {...journal, state: "done"}, updatedAt: Date.now()});
  await transactionDone(transaction);
  assertStorageLifecycleIsActive(generation);
  await pruneRecoveryBackups(worldId, generation, stamp);
  assertStorageLifecycleIsActive(generation);
  postMessage({type: "storage-region-recovered", paths, source: "indexeddb-whole-region", backupDir});
}

async function resumeRecoveryJournal(worldId, generation) {
  assertStorageLifecycleIsActive(generation);
  const journalPath = recoveryJournalPath(worldId);
  const journal = await readRecoveryJournal(worldId);
  assertStorageLifecycleIsActive(generation);
  if (!journal || journal.state === "done") return;
  const records = [];
  for (const path of journal.paths || []) {
    const entry = await readStoredRecord(path);
    assertStorageLifecycleIsActive(generation);
    const bytes = entry && toUint8Array(entry.value);
    if (bytes) records.push({path, bytes, entry});
  }
  if (records.length === 0) {
    const held = (journal.paths || []).every((path) => {
      const indexed = regionIndex.get(path);
      return indexed && indexed.backend === "opfs" && regionHeaderIsSane(path, materializeOpfsRegion(indexed));
    });
    if (held) {
      await writeRecoveryJournal(journalPath, {...journal, state: "done"});
      assertStorageLifecycleIsActive(generation);
      return;
    }
    throw new Error("Unfinished region recovery has no durable source copy");
  }
  await recoverRegionConflictGroup(worldId, records, generation);
}

async function pruneRecoveryBackups(worldId, generation = storageGeneration, keepGeneration = null) {
  assertStorageLifecycleIsActive(generation);
  const prefix = "/gaius/saves/" + String(worldId || "world") + "/.gaius-backups/";
  const paths = await listStoredPaths(prefix);
  assertStorageLifecycleIsActive(generation);
  const generations = new Map();
  for (const path of paths) {
    const tail = path.slice(prefix.length).split("/")[0];
    if (tail) {
      if (!generations.has(tail)) generations.set(tail, []);
      generations.get(tail).push(path);
    }
  }
  const ordered = [...generations.keys()].filter((key) => key !== keepGeneration).sort().reverse();
  const old = ordered.slice(RECOVERY_BACKUP_LIMIT - (keepGeneration ? 1 : 0))
    .flatMap((key) => generations.get(key));
  if (old.length) {
    assertStorageLifecycleIsActive(generation);
    await deleteStoredPaths(old);
    assertStorageLifecycleIsActive(generation);
  }
}

function listStoredPaths(prefix) {
  return new Promise((resolve, reject) => {
    const paths = [];
    const transaction = database.transaction(storeName, "readonly");
    const store = transaction.objectStore(storeName);
    const range = typeof IDBKeyRange !== "undefined"
      ? IDBKeyRange.bound(prefix, prefix + "\uffff")
      : undefined;
    const request = typeof store.openKeyCursor === "function"
      ? store.openKeyCursor(range)
      : store.openCursor(range);
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) {
        resolve(paths);
        return;
      }
      const rawPath = cursor.key !== undefined
        ? cursor.key
        : cursor.value && cursor.value.path;
      if (typeof rawPath === "string") {
        const path = normalize(rawPath);
        if (path.startsWith(prefix)) paths.push(path);
      }
      cursor.continue();
    };
    request.onerror = () => reject(request.error || new Error("IndexedDB key cursor failed"));
  });
}

function readStoredRecord(path) {
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(storeName, "readonly");
    const request = transaction.objectStore(storeName).get(path);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("IndexedDB read failed"));
  });
}

// Java's synchronous FileChannel is entered from a JSPI activation. Keep the
// IndexedDB operation asynchronous, then publish only the requested region into
// the bridge proxy before resuming that activation.
function requestIndexedDbRegion(path, done) {
  path = normalize(path);
  const hydrated = root.__gaiusIndexedDbHydratedRegions;
  if (hydrated && hydrated.has(path)) {
    done(true, "");
    return;
  }
  const cached = readCachedRegion(path);
  if (cached !== undefined) {
    done(true, "");
    return;
  }
  const pending = onDemandRegionLoads.get(path);
  if (pending) {
    pending.push(done);
    return;
  }
  onDemandRegionLoads.set(path, [done]);
  readStoredRecord(path).then(async entry => {
    if (!entry) throw new Error("IndexedDB region is missing: " + path);
    const value = await decodeStoredValue(path, entry.value);
    const bytes = toUint8Array(value);
    if (!bytes) throw new Error("Stored region is not a byte array: " + path);
    let target = root.__gaiusIndexedDbHydratedRegions;
    if (!target) {
      target = new Map();
      root.__gaiusIndexedDbHydratedRegions = target;
    }
    target.set(path, bytes.slice());
    const waiters = onDemandRegionLoads.get(path) || [];
    onDemandRegionLoads.delete(path);
    for (const waiter of waiters) waiter(true, "");
  }).catch(error => {
    const waiters = onDemandRegionLoads.get(path) || [];
    onDemandRegionLoads.delete(path);
    const detail = String(error && (error.stack || error.message) || error);
    for (const waiter of waiters) waiter(false, detail);
  });
}

function deleteStoredPaths(paths) {
  if (paths.length === 0) return Promise.resolve();
  const transaction = database.transaction(storeName, "readwrite");
  const store = transaction.objectStore(storeName);
  for (const path of paths) store.delete(path);
  return transactionDone(transaction);
}

function scheduleFlush(delay = 25) {
  if (flushTimer !== undefined) {
    return;
  }
  flushTimer = setTimeout(() => {
    flushTimer = undefined;
    void flushPendingChanges().catch(() => {});
  }, delay);
}

function flushPendingChanges() {
  if (flushTimer !== undefined) {
    clearTimeout(flushTimer);
    flushTimer = undefined;
  }
  if (flushInFlight) {
    return flushInFlight;
  }
  flushAbortRequested = false;
  const pending = drainPendingChanges();
  const tracked = pending.finally(() => {
    if (flushInFlight === tracked) flushInFlight = undefined;
    activeFlushTransaction = undefined;
    flushAbortRequested = false;
  });
  flushInFlight = tracked;
  return flushInFlight;
}

async function drainPendingChanges() {
  try {
    while (opfsDirty || pendingChanges.size > 0) {
      throwIfFlushAborted();
      flushOpfsSync();
      if (pendingChanges.size === 0) continue;
      const changes = new Map(pendingChanges);
      pendingChanges.clear();
      try {
        await writeBatch(changes);
        throwIfFlushAborted();
      } catch (error) {
        for (const [path, change] of changes) {
          if (!pendingChanges.has(path)) pendingChanges.set(path, change);
          const cacheEntry = regionCache.get(path);
          if (cacheEntry && cacheEntry.version === change.version) {
            cacheEntry.dirty = true;
            cacheEntry.flushing = false;
          }
        }
        throw error;
      }
    }
  } catch (error) {
    storageStats.writeErrors++;
    reportStorageError(error);
    throw error;
  } finally {
    refreshStorageStats();
  }
}

async function writeBatch(changes) {
  if (!database) throw new Error("IndexedDB is closed");
  for (const [path, change] of changes) {
    if (!change.region) continue;
    const cacheEntry = regionCache.get(path);
    if (cacheEntry && cacheEntry.version === change.version) {
      cacheEntry.flushing = true;
    }
  }
  const transaction = database.transaction(storeName, "readwrite");
  activeFlushTransaction = transaction;
  try {
    const store = transaction.objectStore(storeName);
    for (const [path, change] of changes) {
      if (change.value === null) {
        store.delete(path);
      } else {
        const current = regionIndex.get(path);
        store.put({
          path,
          value: change.value,
          generation: Number(current && current.generation) || 0,
          pairCommitSequence: Number(current && current.pairCommitSequence) ||
            pairCommitSequenceForPath(path),
          committedAt: Number(current && current.committedAt) || Date.now(),
          updatedAt: Date.now(),
        });
      }
    }
    await transactionDone(transaction);
  } finally {
    if (activeFlushTransaction === transaction) activeFlushTransaction = undefined;
  }
  for (const [path, change] of changes) {
    if (!change.region) continue;
    const cacheEntry = regionCache.get(path);
    if (cacheEntry && cacheEntry.version === change.version) {
      cacheEntry.dirty = false;
      cacheEntry.flushing = false;
    }
  }
  trimRegionCache();
}

function throwIfFlushAborted() {
  if (flushAbortRequested) {
    throw new Error("Persistent storage flush was aborted during shutdown");
  }
}

function requestFlushAbort() {
  if (!flushInFlight) return false;
  if (flushAbortRequested) return true;
  flushAbortRequested = true;
  storageStats.flushAbortRequests++;
  const transaction = activeFlushTransaction;
  if (!transaction || typeof transaction.abort !== "function") return false;
  try {
    transaction.abort();
  } catch (error) {
    reportStorageError(error);
  }
  return true;
}

let changeVersion = 0;

function nextChangeVersion() {
  changeVersion = (changeVersion + 1) >>> 0;
  return changeVersion || ++changeVersion;
}

function queueIndexedDbChange(path, value, region) {
  pendingChanges.set(path, {
    value,
    version: nextChangeVersion(),
    region: !!region,
  });
}

function flushOpfsSync() {
  if (!opfsAccessHandle || !opfsDirty) return;
  const startedAt = monotonicMillis();
  try {
    opfsAccessHandle.flush();
    opfsDirty = false;
  } finally {
    const elapsed = Math.max(0, monotonicMillis() - startedAt);
    storageStats.opfsFlushes++;
    storageStats.opfsFlushMillis += elapsed;
    storageStats.opfsMaxFlushMillis = Math.max(storageStats.opfsMaxFlushMillis, elapsed);
  }
}

function readCachedRegion(path) {
  const cached = regionCache.get(path);
  if (cached) {
    cached.lastAccess = ++regionCacheSequence;
    storageStats.cacheHits++;
    return cached.value;
  }
  storageStats.cacheMisses++;
  const indexed = regionIndex.get(path);
  if (!indexed || indexed.backend !== "opfs" || !opfsAccessHandle) {
    refreshStorageStats();
    return undefined;
  }
  const bytes = materializeOpfsRegion(indexed);
  putRegionCache(path, bytes, {
    dirty: false,
    pinned: false,
    flushing: false,
    version: 0,
  });
  refreshStorageStats();
  return bytes;
}

function putRegionCache(path, value, state) {
  const bytes = toUint8Array(value);
  if (!bytes) return false;
  const previous = regionCache.get(path);
  const previousBytes = previous ? previous.byteLength : 0;
  evictRegionCache(Math.max(0, bytes.byteLength - previousBytes), path);
  if (regionCacheBytes - previousBytes + bytes.byteLength > regionCacheBudgetBytes) {
    return false;
  }
  if (previous) regionCacheBytes -= previous.byteLength;
  regionCache.set(path, {
    value: bytes,
    byteLength: bytes.byteLength,
    dirty: !!state.dirty,
    pinned: !!state.pinned,
    flushing: !!state.flushing,
    version: Number(state.version) || 0,
    lastAccess: ++regionCacheSequence,
  });
  regionCacheBytes += bytes.byteLength;
  storageStats.cachePeakBytes = Math.max(storageStats.cachePeakBytes, regionCacheBytes);
  refreshStorageStats();
  return true;
}

function removeRegionCache(path) {
  const entry = regionCache.get(path);
  if (!entry) return;
  regionCacheBytes -= entry.byteLength;
  regionCache.delete(path);
  refreshStorageStats();
}

function evictRegionCache(requiredBytes, excludedPath) {
  while (regionCacheBytes + requiredBytes > regionCacheBudgetBytes) {
    let candidatePath;
    let candidate;
    for (const [path, entry] of regionCache) {
      if (path === excludedPath || entry.dirty || entry.pinned || entry.flushing) continue;
      if (!candidate || entry.lastAccess < candidate.lastAccess) {
        candidatePath = path;
        candidate = entry;
      }
    }
    if (!candidate) break;
    regionCache.delete(candidatePath);
    regionCacheBytes -= candidate.byteLength;
    storageStats.evictions++;
  }
}

function trimRegionCache() {
  evictRegionCache(0);
  refreshStorageStats();
}

function releaseStartupRegionPins() {
  for (const entry of regionCache.values()) entry.pinned = false;
  trimRegionCache();
}

function refreshStorageStats() {
  let dirtyEntries = 0;
  let pinnedEntries = 0;
  let flushingEntries = 0;
  for (const entry of regionCache.values()) {
    if (entry.dirty) dirtyEntries++;
    if (entry.pinned) pinnedEntries++;
    if (entry.flushing) flushingEntries++;
  }
  storageStats.cacheBytes = regionCacheBytes;
  storageStats.cacheEntries = regionCache.size;
  storageStats.regionEntries = regionIndex.size;
  storageStats.dirtyEntries = dirtyEntries;
  storageStats.pinnedEntries = pinnedEntries;
  storageStats.flushingEntries = flushingEntries;
  storageStats.pendingEntries = pendingChanges.size;
  return storageStats;
}

function activeWorldPrefix() {
  return "/gaius/saves/" + String(root.__gaiusServerWorldId || "") + "/";
}

function reportStorageError(error) {
  postMessage({
    type: "storage-write-error",
    detail: String(error && (error.stack || error.message) || error),
  });
}

function reportStorageFlushFailure(error) {
  const detail = String(error && (error.stack || error.message) || error);
  if (!storageFlushFailureReported) {
    storageFlushFailureReported = true;
    postMessage({type: "storage-flush-failed", detail});
  }
  // Preserve the existing storage-write-error evidence for clients that only
  // consume that channel, while the explicit flush-failed event makes it
  // impossible to mistake this path for a clean stop.
  reportStorageError(error);
}

function withTimeout(promise, timeoutMillis, detail) {
  let timer;
  return Promise.race([
    Promise.resolve(promise),
    new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new Error(detail)), timeoutMillis);
    }),
  ]).finally(() => clearTimeout(timer));
}

function waitForPromiseSettlement(promise, timeoutMillis) {
  if (!promise) return Promise.resolve(true);
  // Always observe the original promise, even when the watchdog wins.  The
  // operation may still reject after the caller has moved on and must not
  // become an unhandled rejection.
  const observed = Promise.resolve(promise).then(
    () => true,
    () => true,
  );
  return withTimeout(observed, timeoutMillis, "Promise settlement timed out")
    .then(() => true, () => false);
}

async function flushWithWatchdog(timeoutMillis, detail) {
  const flush = flushPendingChanges();
  try {
    await withTimeout(flush, timeoutMillis, detail);
    return true;
  } catch (error) {
    if (String(error && error.message || error) !== String(detail)) {
      throw error;
    }
    storageStats.flushTimeouts++;
    requestFlushAbort();
    const settled = await waitForPromiseSettlement(
      flush,
      storageFlushAbortSettleMillis(),
    );
    if (!settled) {
      storageStats.flushAbortTimeouts++;
      reportStorageError(new Error(
        "Persistent storage flush abort did not settle within " +
          storageFlushAbortSettleMillis() + " ms",
      ));
    }
    throw error;
  }
}

function deferStorageCloseUntil(promise) {
  if (!promise) return;
  const retry = () => {
    if (!storageStartupInFlight && !flushInFlight && !activeFlushTransaction) {
      closeStorageHandles();
    }
  };
  // Observe the original promise even when the caller's startup watchdog
  // expired. This continuation is only a safety retry; it never posts a
  // clean stop on behalf of a timed-out shutdown.
  void Promise.resolve(promise).then(retry, retry);
}

function closeStorageHandles() {
  if (storageHandlesClosed) return true;
  // Closing either backend while a write is still running can invalidate the
  // transaction/SyncAccessHandle underneath the writer.  Leave ownership with
  // the settling promise and close only from a safe continuation.
  if (!storageStateIsClean()) return false;
  if (flushTimer !== undefined) {
    clearTimeout(flushTimer);
    flushTimer = undefined;
  }
  let failed = false;
  if (opfsAccessHandle) {
    try {
      opfsAccessHandle.close();
      opfsAccessHandle = undefined;
    } catch (error) {
      failed = true;
      reportStorageError(error);
    }
  }
  if (database) {
    try {
      database.close();
      database = undefined;
    } catch (error) {
      failed = true;
      reportStorageError(error);
    }
  }
  if (failed || opfsAccessHandle || database) return false;
  root.__gaiusFsPut = undefined;
  root.__gaiusFsPutBytes = undefined;
  root.__gaiusFsCanPatchBytes = undefined;
  root.__gaiusFsPatchBytes = undefined;
  root.__gaiusFsDelete = undefined;
  root.__gaiusFsFlush = undefined;
  root.__gaiusFsStorageSnapshot = undefined;
  storageHandlesClosed = true;
  // Every handle is closed and nothing is pending: the next session may own the world now.
  releaseWorldOwnerLock();
  return true;
}

async function closePersistentStorage(flush) {
  if (storageClosePromise) return storageClosePromise;
  invalidateStorageLifecycle();
  markWorldOwnerClosing();
  const startup = storageStartupInFlight;
  const operation = (async () => {
    if (startup) {
      const settled = await waitForPromiseSettlement(
        startup,
        storageStartupSettleMillis(),
      );
      if (!settled) {
        reportStorageError(new Error(
          "Persistent storage startup did not settle within " +
            storageStartupSettleMillis() + " ms",
        ));
        deferStorageCloseUntil(startup);
        return false;
      }
    }
    if (flush) {
      if (!await flushForShutdown()) return false;
    }
    if (!storageStateIsClean()) {
      reportStorageError(new Error(
        "Persistent storage close refused while changes remain pending",
      ));
      return false;
    }
    return closeStorageHandles();
  })();
  const wrapped = operation.then(
    (result) => {
      // A failed close is retryable once the original startup/flush promise
      // settles. Do not cache false forever and accidentally turn a later
      // safe close into a false result.
      if (storageClosePromise === wrapped && !result) {
        storageClosePromise = undefined;
      }
      return result;
    },
    (error) => {
      if (storageClosePromise === wrapped) storageClosePromise = undefined;
      reportStorageFlushFailure(error);
      return false;
    },
  );
  storageClosePromise = wrapped;
  return wrapped;
}

function isRegionPath(path) {
  path = String(path || "");
  return !isRecoveryBackupPath(path) &&
    (path.endsWith(".mca") || path.endsWith(".mcc"));
}

function toUint8Array(value) {
  if (value instanceof Uint8Array) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  if (value instanceof ArrayBuffer) {
    return new Uint8Array(value);
  }
  if (ArrayBuffer.isView(value)) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  return null;
}

function decodeBase64(value) {
  if (typeof Uint8Array.fromBase64 === "function") {
    return Uint8Array.fromBase64(value);
  }
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

async function decodeStoredValue(path, value) {
  if (isRegionPath(path) && typeof value === "string") {
    return decodeBase64(value);
  }
  if (value && value.encoding === "gzip") {
    const compressed = toUint8Array(value.bytes);
    if (!compressed || typeof DecompressionStream !== "function") {
      throw new Error("Compressed region storage is unavailable");
    }
    const stream = new Blob([compressed]).stream()
      .pipeThrough(new DecompressionStream("gzip"));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }
  return value;
}

function transactionDone(transaction) {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error || new Error("IndexedDB transaction failed"));
    transaction.onabort = () => reject(transaction.error || new Error("IndexedDB transaction aborted"));
  });
}

function normalize(path) {
  path = String(path || "/").replace(/\\/g, "/");
  // Collapse "." and redundant segments so write and read keys agree even when
  // Java-side Path.resolve introduces "./" (e.g. world_gen_settings.dat).
  const absolute = path.startsWith("/");
  const parts = path.split("/");
  const kept = [];
  for (let index = 0; index < parts.length; index++) {
    const part = parts[index];
    if (part === "" || part === ".") continue;
    if (part === "..") {
      if (kept.length > 0) kept.pop();
      continue;
    }
    kept.push(part);
  }
  return (absolute ? "/" : "") + kept.join("/");
}
// Worker heartbeat scalar snapshot contract: snapshotScalarTelemetry root.__gaiusChunkPriorityStats root.__gaiusNetworkStats copied >= 64 Number.isFinite(current) globalPumpTelemetryKeys snapshotGlobalPumpTelemetry globalPump: snapshotGlobalPumpTelemetry

// Kernel runtime client. Right after the start message the page posts
// {type: "gaius-kernel-port", port} (port/web/boot/gaius-boot.js): a MessagePort into the page's
// kernel runtime (port/web/kernels/kernel-runtime.js attachPort). World generation reaches it as
// root.__gaiusKernelClient: submit(kind, payload, {kernel, key, version, cx, cz, near,
// resultBytes}) resolves with the result ArrayBuffer, available(kernel) says whether to try at
// all, setViewer({x, y, z, yaw, vx?, vz?, viewDistance?}) feeds the movement prediction from the
// server's view of the player. Without a port (portable page without kernels, ?gaiusKernels=0)
// the client stays absent and every caller keeps the vanilla Java path. Code that needs the
// client as soon as it exists (a job codec's host object) pushes a callback onto
// root.__gaiusKernelClientListeners; it runs once per client.
// The same message may carry kernelScripts ([{name, url} | {name, source}]: the worldgen job codec
// and facade, plus kernel-policy.js/kernel-runtime.js when this worker hosts its own worldgen
// kernel workers), worldgenKernel (merged into root.__gaiusWorldgenKernelConfig, see
// port/web/kernels/worldgen-kernel.js) and lightKernel (merged into root.__gaiusLightKernelConfig:
// {enabled, maxInFlight?, timeoutMs?}, read by dev.gaius.browser.kernel.light.LightKernelHost);
// they load before the client exists.
// (Guarded: the lifecycle harnesses run this script with a minimal worker scope.)
if (typeof root.addEventListener === "function") root.addEventListener("message", (event) => {
  const message = event.data;
  if (!message || message.type !== "gaius-kernel-port" || !message.port) return;
  loadKernelHostScripts(message);
  if (root.__gaiusKernelClient) root.__gaiusKernelClient.close();
  const client = createKernelClient(message.port);
  root.__gaiusKernelClient = client;
  const listeners = Array.isArray(root.__gaiusKernelClientListeners) ? root.__gaiusKernelClientListeners : [];
  for (const listener of listeners) {
    try {
      listener(client);
    } catch (error) {
      console.warn("Kernel client listener failed", error);
    }
  }
});

const DEFAULT_KERNEL_HOST_SCRIPTS = ["kernels/kernel-mirror.js", "kernels/worldgen-job.js", "kernels/worldgen-kernel.js"];
const loadedKernelHostScripts = new Set();

// Imports the kernel scripts the page listed (or, from a dist layout, kernels/<name> next to this
// worker script). A script that fails to load only keeps its kernel off: Java checks
// globalThis.GaiusWorldgenKernel per chunk and keeps the vanilla path without it.
function loadKernelHostScripts(message) {
  root.__gaiusKernelSemantics = message.kernelSemantics || null;
  if (message.worldgenKernel && typeof message.worldgenKernel === "object") {
    root.__gaiusWorldgenKernelConfig = Object.assign({}, root.__gaiusWorldgenKernelConfig || {},
      message.worldgenKernel);
  }
  if (message.lightKernel && typeof message.lightKernel === "object") {
    root.__gaiusLightKernelConfig = Object.assign({}, root.__gaiusLightKernelConfig || {},
      message.lightKernel);
  }
  let entries = Array.isArray(message.kernelScripts) ? message.kernelScripts : defaultKernelHostScripts();
  if (entries.length === 0) {
    // A page opened from disk lists nothing it could load; it may have handed the scripts over.
    entries = DEFAULT_KERNEL_HOST_SCRIPTS.filter((name) => workerFile(name)).map((name) => ({name}));
  }
  for (const entry of entries) {
    if (!entry || typeof entry !== "object") continue;
    const name = String(entry.name || entry.url || "");
    if (!name || loadedKernelHostScripts.has(name)) continue;
    let temporaryUrl = null;
    try {
      if (typeof entry.source === "string") {
        temporaryUrl = URL.createObjectURL(new Blob([entry.source], {type: "text/javascript"}));
        importScripts(temporaryUrl);
      } else if (importWorkerFile(name)) {
        // imported from the page's files
      } else if (entry.url) {
        importScripts(String(entry.url));
      } else {
        continue;
      }
      loadedKernelHostScripts.add(name);
    } catch (error) {
      console.warn("Kernel script " + name + " did not load; its kernel stays off", error);
    } finally {
      if (temporaryUrl) URL.revokeObjectURL(temporaryUrl);
    }
  }
}

function defaultKernelHostScripts() {
  const href = String(root.location && root.location.href || "");
  // Only a plain dist layout serves kernels/<name> unhashed next to this script; a site build
  // (content-hashed worker name) and blob workers rely on the page's list.
  if (!/^https?:/i.test(href) || /\.[0-9a-f]{16,64}\.js(?:[?#]|$)/i.test(href)) return [];
  return DEFAULT_KERNEL_HOST_SCRIPTS.map((name) => ({name, url: new URL(name, href).href}));
}

async function drainWorldgenKernel() {
  const facade = root.GaiusWorldgenKernel;
  if (!facade || typeof facade.drain !== "function") return true;
  try {
    const capMillis = Math.max(1000, Math.min(20000, Math.floor(runtimeStopWatchdogMillis() / 3)));
    await facade.drain(capMillis);
    return true;
  } catch (error) {
    console.warn("Worldgen kernel drain failed", error);
    return false;
  }
}

function createKernelClient(port) {
  let nextId = 1;
  let status = {enabled: false, kernels: {}};
  const pending = new Map();
  const stats = {submitted: 0, completed: 0, failed: 0, unavailable: 0};
  port.onmessage = (event) => {
    const message = event.data;
    if (!message) return;
    if (message.type === "status") {
      status = message.status || status;
      return;
    }
    const entry = pending.get(message.id);
    if (!entry) return;
    pending.delete(message.id);
    if (message.type === "result") {
      stats.completed++;
      entry.resolve(message.result);
    } else {
      stats.failed++;
      const error = new Error(message.message || "kernel failed");
      error.code = message.code || "kernel-error";
      entry.reject(error);
    }
  };
  port.start();
  const available = (kernel) => status.enabled === true &&
    (kernel === undefined ? Object.keys(status.kernels) : [kernel]).some((name) =>
      !!(status.kernels[name] && status.kernels[name].available)
      && !!root.GaiusKernelMirror && root.GaiusKernelMirror.enabled(name));
  return {
    available,
    status: () => status,
    stats,
    submit(kind, payload, opts) {
      const options = opts || {};
      if (!root.GaiusKernelMirror || !root.GaiusKernelMirror.allowed(kind, options.kernel)) {
        const error = new Error("NEEDS_VANILLA: kernel mirror is disabled");
        error.code = "needs-vanilla";
        return Promise.reject(error);
      }
      if (!available(options.kernel)) {
        stats.unavailable++;
        const error = new Error("kernel runtime is not available");
        error.code = "runtime-disabled";
        return Promise.reject(error);
      }
      const buffer = payload instanceof ArrayBuffer ? payload
        : new Uint8Array(payload.buffer, payload.byteOffset, payload.byteLength).slice().buffer;
      const plain = {};
      for (const name of ["key", "version", "visible", "background", "near", "priorityClass",
        "priority", "distance", "cx", "cz", "resultBytes", "retry"]) {
        if (options[name] !== undefined) plain[name] = options[name];
      }
      const id = nextId++;
      stats.submitted++;
      return new Promise((resolve, reject) => {
        pending.set(id, {resolve, reject});
        port.postMessage({type: "submit", id, kernel: options.kernel || null, kind, payload: buffer,
          opts: plain}, [buffer]);
      });
    },
    cancel(key, kernel) {
      port.postMessage({type: "cancel", key, kernel: kernel || null});
    },
    setViewer(viewer) {
      port.postMessage({type: "viewer", viewer});
    },
    close() {
      try {
        port.postMessage({type: "close"});
        port.close();
      } catch (_) {
        // already closed
      }
      for (const entry of pending.values()) {
        const error = new Error("kernel client closed");
        error.code = "terminated";
        entry.reject(error);
      }
      pending.clear();
      status = {enabled: false, kernels: {}};
    },
  };
}
