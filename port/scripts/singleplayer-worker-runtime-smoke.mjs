
import {serverTickWindow} from "./server-tick-window.mjs";
import fs from "node:fs";
import {createHash} from "node:crypto";
import {Session as InspectorSession} from "node:inspector";
import {PerformanceObserver, performance} from "node:perf_hooks";
import vm from "node:vm";
import {getHeapStatistics} from "node:v8";
import {inflateSync} from "node:zlib";
import {basename, isAbsolute} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import {
  FINAL_OUTPUT_WRITE_TIMEOUT_MS,
  effectiveExitCode,
  writeChunkAndDrain,
} from "./singleplayer-worker-runtime-output.mjs";
import {
  MessageChannel,
  MessagePort,
  Worker,
  isMainThread,
  parentPort,
  workerData,
} from "node:worker_threads";

// This diagnostic schema is independent from the 500 ms release gate.  The
// block-attribution fields (slowBlockId/reuse/drop) are part of v2 and must
// remain stable for existing evidence readers.
const SLOW_SAMPLE_SCHEMA_VERSION = 2;
const SLOW_SAMPLE_SCHEMA = "gaius.worker-event-loop-slow-sample.v2";
const SLOW_SAMPLE_THRESHOLD_MS = 250;
const MAX_SLOW_SAMPLES = 64;
// Keep the phase-attribution views balanced and bounded.  The independent
// global Top-64 ring below is the authoritative retention contract.
const MAX_SLOW_SAMPLES_PER_SCOPE = MAX_SLOW_SAMPLES / 2;
const MAX_SLOW_BLOCK_ID = 0x7fffffff;
const MAX_SLOW_SAMPLE_FIELDS_PER_GROUP = 32;
const SLOW_PROBE_TOP_K_EVICTED = Symbol("slow-probe-top-k-evicted");
const WORLDGEN_SLOW_SAMPLE_FIELDS = Object.freeze([
  "slices",
  "sliceElapsedMillis",
  "totalSliceElapsedMillis",
  "maxSliceElapsedMillis",
  "configuredBudgetMillis",
  "completedBudgetMillis",
  "budgetMillis",
  "minimumBudgetMillis",
  "budgetOverruns",
  "lastBudgetOverrunMillis",
  "maxBudgetOverrunMillis",
  "yieldDelayMillis",
  "maxYieldDelayMillis",
  "queueDepth",
  "maxQueueDepth",
  "progressPulses",
  "totalProgressPulses",
  "maxTurnPulses",
  "networkPreemptions",
  "deadlineYields",
  "hardCapYields",
  "checkpointYields",
  "checkpointOnlyYields",
  "checkpointOnlyYieldDelayMillis",
  "checkpointOnlyMaxYieldDelayMillis",
  "checkpointOnlyQueueDepth",
  "checkpointOnlyMaxQueueDepth",
  "distanceManagerBatches",
  "distanceManagerLoopPulses",
  "lastDistanceManagerUpdates",
  "maxDistanceManagerUpdates",
  "chunkBroadcastBatches",
]);
const NETWORK_SLOW_SAMPLE_FIELDS = Object.freeze([
  // This direct Worker snapshot is raw. Telemetry-pong measurement windows
  // baseline-difference counters such as eventLoopGapsOver500 separately;
  // neither value is used as the independent 250 ms slow-probe trigger.
  "inboundQueuedBytes",
  "decodedPacketQueue",
  "decodedSliceBacklog",
  "pumpCalls",
  "pumpChunks",
  "pumpBytes",
  "longestPumpMillis",
  "eventLoopGapSamples",
  "eventLoopGapsOver500",
  "longestEventLoopGapMillis",
  "integratedServerTaskPending",
  "integratedServerInputPending",
  "integratedServerPumpRequests",
  "integratedServerPumpStarts",
  "integratedServerTaskSchedules",
  "integratedServerTaskRuns",
  "integratedServerTaskFollowups",
  "integratedServerPumpFailures",
  "integratedServerTaskScheduleFailures",
  "integratedServerTaskLifecycleDrops",
  "integratedServerTaskBudgetExhaustions",
  "integratedServerTaskDeferredRetries",
  "integratedServerTaskRetryExhaustions",
  "integratedServerTaskStaleGenerations",
  "integratedServerTaskPermitMissing",
  "integratedServerTaskPumpBusy",
  "integratedServerDistanceMaxViewApplyMillis",
  "integratedServerDistanceMaxSimulationApplyMillis",
  "errors",
]);
const STORAGE_SLOW_SAMPLE_FIELDS = Object.freeze([
  "backend",
  "cacheBudgetBytes",
  "cacheBytes",
  "cachePeakBytes",
  "cacheEntries",
  "dirtyEntries",
  "pinnedEntries",
  "flushingEntries",
  "pendingEntries",
  "evictions",
  "cacheHits",
  "cacheMisses",
  "rejectedWrites",
  "writeErrors",
  "migratedRegions",
  "opfsFileBytes",
  "opfsFullWrites",
  "opfsFullWriteBytes",
  "opfsPatchWrites",
  "opfsPatchPayloadBytes",
  "opfsPatchRanges",
  "opfsPatchCheckpoints",
  "opfsReconstructedRegions",
  "opfsFlushes",
  "opfsFlushMillis",
  "opfsMaxFlushMillis",
  "opfsScanRecords",
  "opfsScanV1Records",
  "opfsScanV2Records",
  "flushTimeouts",
  "flushAbortRequests",
  "flushAbortTimeouts",
]);
const SCHEDULER_SLOW_SAMPLE_FIELDS = Object.freeze([
  "schemaVersion",
  "eventSequence",
  "lastEvent",
  "lastEventAtEpochMs",
  "taskWorkDepth",
  "reentrantTaskWorkDepth",
  "activeTaskScope",
  "normalTaskScopeActive",
  "yieldActive",
  "activeWorkMillis",
  "taskScopesStarted",
  "taskScopesEnded",
  "reentrantTaskScopesStarted",
  "reentrantTaskScopesEnded",
  "lastTaskStartedAtEpochMs",
  "lastTaskEndedAtEpochMs",
  "lastTaskActiveWorkMillis",
  "maxTaskActiveWorkMillis",
  "lastTaskScopeWallMillis",
  "maxTaskScopeWallMillis",
  "taskScopeUnderflows",
  "taskScopeInvalidEnds",
  "serverWorkTurnSequence",
  "serverWorkTurnActive",
  "lastServerWorkTurnStartedAtEpochMs",
  "lastServerWorkTurnEndedAtEpochMs",
  "lastServerWorkTurnWallMillis",
  "maxServerWorkTurnWallMillis",
  "currentTaskScopeId",
  "currentTaskLabel",
  "maxTaskContext",
  "maxSliceContext",
]);

let workerGcObserver;
const workerGcStats = {
  supported: typeof PerformanceObserver === "function",
  count: 0,
  durationMs: 0,
  maxDurationMs: 0,
  lastDurationMs: 0,
  lastKind: 0,
  lastAtEpochMs: 0,
};

const rootDirectory = fileURLToPath(new URL("../../", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const portConfig = JSON.parse(fs.readFileSync(rootDirectory + "port/config.json", "utf8"));
const versionProfileRelative = nativePath(
  process.env.GAIUS_VERSION_PROFILE_PATH || String(portConfig.versionProfile || ""),
);
const profileId = basename(versionProfileRelative.replaceAll("\\", "/"))
  .replace(/\.json$/, "");
if (!profileId) {
  throw new Error("no version profile: set GAIUS_VERSION_PROFILE_PATH or port/config.json versionProfile");
}
const versionProfilePath = isAbsolute(versionProfileRelative)
  ? versionProfileRelative
  : rootDirectory + "port/" + versionProfileRelative;
const distDirectory = nativePath(process.env.GAIUS_DIST_DIRECTORY ||
  rootDirectory + "port/web/dist/" + profileId);
const buildDirectory = nativePath(process.env.GAIUS_BUILD_ROOT ||
  rootDirectory + "port/target/" + profileId);
const bootstrapPath = nativePath(process.env.GAIUS_SMOKE_BOOTSTRAP_PATH ||
  distDirectory + "/singleplayer-server-worker.js");
if (!isAbsolute(versionProfileRelative)
    && !/^versions\/[A-Za-z0-9._-]+\.json$/.test(versionProfileRelative.replaceAll("\\", "/"))) {
  throw new Error("the active version profile path is invalid");
}
const activeVersionProfile = JSON.parse(fs.readFileSync(
  versionProfilePath,
  "utf8",
));
const storageProfileId = String(activeVersionProfile.id || "");
const worldVersion = Number(activeVersionProfile.worldVersion);
const profileStorage = activeVersionProfile.storage || {};
const storageConfig = Object.freeze({
  profileId: storageProfileId,
  worldVersion,
  storageSchema: Number(profileStorage.schema),
  storageDatabaseName: String(profileStorage.databaseName || ""),
  storagePrefix: String(profileStorage.prefix || ""),
  storageOpfsDirectory: String(profileStorage.opfsDirectory || ""),
});
if (!storageConfig.profileId || !Number.isSafeInteger(storageConfig.worldVersion) ||
    storageConfig.worldVersion <= 0 || storageConfig.storageSchema !== 2 ||
    storageConfig.storageDatabaseName !== `gaius-fs-v2-${storageConfig.profileId}` ||
    storageConfig.storagePrefix !== `gaius.fs.v2:${storageConfig.profileId}:` ||
    storageConfig.storageOpfsDirectory !== `regions-v2-${storageConfig.profileId}`) {
  throw new Error(
    `The active version profile has an invalid schema-2 storage namespace: ${
      JSON.stringify(activeVersionProfile.storage)}`,
  );
}
const activeProtocolVersion = Number(activeVersionProfile.protocolVersion);
if (!Number.isSafeInteger(activeProtocolVersion) || activeProtocolVersion < 0) {
  throw new Error("The active version profile has an invalid protocolVersion");
}
const playProtocols = {
  774: {
    itemEntityTypeId: 71,
    clientbound: {
      addEntity: 1,
      blockChangedAck: 4,
      blockUpdate: 8,
      disconnect: 32,
      keepAlive: 43,
      levelChunkWithLight: 44,
      login: 48,
      ping: 59,
      playerPosition: 70,
      setChunkCacheCenter: 92,
    },
    serverbound: {
      acceptTeleportation: 0,
      chatCommand: 6,
      chunkBatchReceived: 10,
      keepAlive: 27,
      movePlayerPos: 29,
      playerAction: 40,
      playerLoaded: 43,
      pong: 44,
    },
  },
  776: {
    itemEntityTypeId: 71,
    clientbound: {
      addEntity: 1,
      blockChangedAck: 4,
      blockUpdate: 8,
      disconnect: 32,
      keepAlive: 44,
      levelChunkWithLight: 45,
      login: 49,
      ping: 61,
      playerPosition: 72,
      setChunkCacheCenter: 94,
    },
    serverbound: {
      acceptTeleportation: 0,
      chatCommand: 7,
      chunkBatchReceived: 11,
      keepAlive: 28,
      movePlayerPos: 30,
      playerAction: 41,
      playerLoaded: 44,
      pong: 45,
    },
  },
  // Minecraft 26.3 (protocol 777), from the official 26.3 server datagen reports
  // (packets.json, registries.json entity_type); the same derivation reproduces every
  // 776 value above from the 26.2 reports.  Clientbound PLAY gained ADD_TRANSIENT_BLOCK
  // (37), POST_EFFECTS (83) and SWING_ANIMATION (123); serverbound ids used here are
  // unchanged; one entity type was inserted before minecraft:item.
  777: {
    itemEntityTypeId: 72,
    clientbound: {
      addEntity: 1,
      blockChangedAck: 4,
      blockUpdate: 8,
      disconnect: 32,
      keepAlive: 45,
      levelChunkWithLight: 46,
      login: 50,
      ping: 62,
      playerPosition: 73,
      setChunkCacheCenter: 96,
    },
    serverbound: {
      acceptTeleportation: 0,
      chatCommand: 7,
      chunkBatchReceived: 11,
      keepAlive: 28,
      movePlayerPos: 30,
      playerAction: 41,
      playerLoaded: 44,
      pong: 45,
    },
  },
};
const activePlayProtocol = playProtocols[activeProtocolVersion];
if (!activePlayProtocol) {
  throw new Error(`No PLAY packet table exists for protocol ${activeProtocolVersion}`);
}
const {clientbound: clientboundPlay, serverbound: serverboundPlay} = activePlayProtocol;
const itemEntityTypeId = activePlayProtocol.itemEntityTypeId;
const requiredNetworkTaskTelemetryFields = Object.freeze([
  "errors",
  "inboundQueuedBytes",
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

// The Worker build intentionally bypasses BrowserCooperativeExecutor.  The
// integrated-server network pump counters therefore remain zero in that mode;
// only a runtime that actually uses the cooperative pump can be required to
// demonstrate pump activity.  Keep this list limited to successful activity
// counters so a failure cannot make an otherwise direct-executor run look
// active.
const cooperativePumpActivityFields = Object.freeze([
  "integratedServerPumpRequests",
  "integratedServerPumpStarts",
  "integratedServerTaskSignals",
  "integratedServerTaskSchedules",
]);

function hasCooperativePumpActivity(stats) {
  if (stats === null || typeof stats !== "object") {
    return false;
  }
  return cooperativePumpActivityFields.some((field) => Number(stats[field]) > 0);
}

function resolveCooperativePumpMode(stats, expected = false) {
  const active = hasCooperativePumpActivity(stats);
  return {
    expected: Boolean(expected),
    active,
    requireActivity: Boolean(expected) || active,
  };
}

function taskRunSkewAllowance(options = {}) {
  // Telemetry counters are deltas from the first pong in a measurement window.
  // A task already pending at that baseline contributes a run without a
  // matching schedule in the window.  Only the one-bit pending gauge can
  // justify that one-run allowance; unknown/malformed values get no allowance.
  return options.initialIntegratedServerTaskPending === 1 ? 1 : 0;
}

function validateNetworkTaskTelemetry(stats, options = {}) {
  const missingFields = [];
  const nonFiniteFields = [];
  const nonIntegerFields = [];
  const negativeFields = [];
  const relationshipErrors = [];
  const healthErrors = [];
  const fieldValues = Object.create(null);
  const runSkewAllowance = taskRunSkewAllowance(options);
  const objectStats = stats !== null && typeof stats === "object" ? stats : null;
  for (const field of requiredNetworkTaskTelemetryFields) {
    if (!objectStats || !Object.prototype.hasOwnProperty.call(objectStats, field)) {
      missingFields.push(field);
      continue;
    }
    const value = objectStats[field];
    fieldValues[field] = value;
    if (typeof value !== "number" || !Number.isFinite(value)) {
      nonFiniteFields.push(field);
      continue;
    }
    if (!Number.isInteger(value)) {
      nonIntegerFields.push(field);
    }
    if (value < 0) {
      negativeFields.push(field);
    }
  }
  const fieldComplete = missingFields.length === 0 && nonFiniteFields.length === 0;
  if (fieldComplete && nonIntegerFields.length === 0 && negativeFields.length === 0) {
    if (fieldValues.integratedServerTaskPending !== 0 &&
        fieldValues.integratedServerTaskPending !== 1) {
      relationshipErrors.push("integratedServerTaskPending must be 0 or 1");
    }
    if (fieldValues.integratedServerInputPending !== 0 &&
        fieldValues.integratedServerInputPending !== 1) {
      relationshipErrors.push("integratedServerInputPending must be 0 or 1");
    }
    if (fieldValues.integratedServerPumpStarts >
        fieldValues.integratedServerPumpRequests +
          fieldValues.integratedServerPumpRetrySchedules) {
      relationshipErrors.push("integrated pump starts exceed requests plus retries");
    }
    if (fieldValues.integratedServerPumpFailures >
        fieldValues.integratedServerPumpStarts) {
      relationshipErrors.push("integrated pump failures exceed starts");
    }
    if (fieldValues.integratedServerPumpRetrySchedules >
        fieldValues.integratedServerPumpFailures) {
      relationshipErrors.push("integrated pump retries exceed failures");
    }
    if (fieldValues.integratedServerPumpRetryExhaustions >
        fieldValues.integratedServerPumpFailures) {
      relationshipErrors.push("integrated pump retry exhaustion exceeds failures");
    }
    if (fieldValues.integratedServerPumpRetrySchedules +
        fieldValues.integratedServerPumpRetryExhaustions !==
          fieldValues.integratedServerPumpFailures) {
      relationshipErrors.push("integrated pump failure accounting is incomplete");
    }
    if (fieldValues.integratedServerTaskUnparks !==
        fieldValues.integratedServerTaskSchedules +
          fieldValues.integratedServerTaskCoalesced) {
      relationshipErrors.push("task unparks do not match schedules plus coalesced signals");
    }
    if (fieldValues.integratedServerTaskCoalesced >
        fieldValues.integratedServerTaskSignals +
          fieldValues.integratedServerTaskFollowups +
          fieldValues.integratedServerTaskDeferredRetries) {
      relationshipErrors.push("task coalesced count exceeds all schedule attempts");
    }
    if (fieldValues.integratedServerTaskSchedules -
        fieldValues.integratedServerTaskFollowups >
          fieldValues.integratedServerTaskSignals +
            fieldValues.integratedServerTaskDeferredRetries) {
      relationshipErrors.push("burst task schedules exceed signals plus deferred retries");
    }
    if (fieldValues.integratedServerTaskRuns >
        fieldValues.integratedServerTaskSchedules + runSkewAllowance) {
      relationshipErrors.push("task runs exceed task schedules");
    }
    if (fieldValues.integratedServerTaskFollowups >
        fieldValues.integratedServerTaskSchedules) {
      relationshipErrors.push("task followups exceed task schedules");
    }
    if (fieldValues.integratedServerTaskDeferredRetries +
        fieldValues.integratedServerTaskRetryExhaustions !==
          fieldValues.integratedServerTaskBudgetExhaustions) {
      relationshipErrors.push("task budget exhaustion accounting is incomplete");
    }
    if (options.requireActivity && fieldValues.integratedServerTaskSchedules <= 0) {
      relationshipErrors.push("task schedules are zero");
    }
    if (options.requireActivity && fieldValues.integratedServerTaskRuns <= 0) {
      relationshipErrors.push("task runs are zero");
    }
    if (options.requireActivity && fieldValues.integratedServerPumpRequests <= 0) {
      relationshipErrors.push("integrated pump requests are zero");
    }
    if (options.requireActivity && fieldValues.integratedServerPumpStarts <= 0) {
      relationshipErrors.push("integrated pump starts are zero");
    }
    if (options.requireDrained && fieldValues.inboundQueuedBytes !== 0) {
      relationshipErrors.push("inbound queue is not drained");
    }
    if (options.requireDrained && fieldValues.integratedServerTaskPending !== 0) {
      relationshipErrors.push("integrated server task is still pending");
    }
    if (options.requireDrained && fieldValues.integratedServerInputPending !== 0) {
      relationshipErrors.push("integrated server input is still pending");
    }
    if (options.requireDrained && fieldValues.integratedServerTaskRuns <
        fieldValues.integratedServerTaskSchedules) {
      relationshipErrors.push("scheduled integrated server tasks did not all run");
    }
    if (options.requireHealthy && fieldValues.errors !== 0) {
      healthErrors.push("network errors are non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerPumpFailures !== 0) {
      healthErrors.push("integrated server pump failures are non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerPumpRetryExhaustions !== 0) {
      healthErrors.push("integrated server pump retry exhaustion is non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerTaskScheduleFailures !== 0) {
      healthErrors.push("integrated server task schedule failures are non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerTaskLifecycleDrops !== 0) {
      healthErrors.push("integrated server task lifecycle drops are non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerTaskWrongThread !== 0) {
      healthErrors.push("integrated server task wrong-thread runs are non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerTaskPermitMissing !== 0) {
      healthErrors.push("integrated server task permit-missing runs are non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerTaskRetryExhaustions !== 0) {
      healthErrors.push("integrated server task deferred retries exhausted");
    }
  }
  return {
    valid: fieldComplete && nonIntegerFields.length === 0 && negativeFields.length === 0 &&
      relationshipErrors.length === 0 && healthErrors.length === 0,
    fieldComplete,
    missingFields,
    nonFiniteFields,
    nonIntegerFields,
    negativeFields,
    taskRunSkewAllowance: runSkewAllowance,
    relationshipErrors,
    healthErrors,
  };
}

function copyObjectSnapshot(value) {
  return value !== null && typeof value === "object" ? {...value} : null;
}

const MAX_SERIALIZED_ERROR_DEPTH = 4;
const MAX_SERIALIZED_ERROR_TEXT = 1024;
const MAX_SERIALIZED_ERROR_ARGUMENTS = 8;

function safeErrorText(value) {
  try {
    return String(value).slice(0, MAX_SERIALIZED_ERROR_TEXT);
  } catch {
    return "<unstringifiable>";
  }
}

function isErrorLike(value) {
  try {
    return value instanceof Error || (value !== null && typeof value === "object" &&
      ("stack" in value || "cause" in value));
  } catch {
    return false;
  }
}

// Error fields such as message, stack, and cause are commonly non-enumerable.  Keep
// the diagnostic path bounded and cycle-safe while retaining the original failure gate.
function serializeError(value, depth = 0, seen = new WeakSet()) {
  if (value === null || value === undefined) return null;
  if (typeof value !== "object" && typeof value !== "function") {
    return String(value).slice(0, MAX_SERIALIZED_ERROR_TEXT);
  }
  if (seen.has(value)) return {type: "cycle"};
  if (depth >= MAX_SERIALIZED_ERROR_DEPTH) return {type: "depth-limit"};
  seen.add(value);
  const text = (field) => {
    try {
      const fieldValue = value[field];
      return fieldValue === undefined || fieldValue === null
        ? undefined : String(fieldValue).slice(0, MAX_SERIALIZED_ERROR_TEXT);
    } catch {
      return "<unreadable>";
    }
  };
  const result = {type: text("name") || "Error"};
  const message = text("message");
  const stack = text("stack");
  if (message !== undefined) result.message = message;
  if (stack !== undefined) result.stack = stack;
  try {
    if (value.cause !== undefined) {
      result.cause = serializeError(value.cause, depth + 1, seen);
    }
  } catch {
    result.cause = {type: "unreadable"};
  }
  return result;
}

function highResolutionEpochMillis() {
  return performance.timeOrigin + performance.now();
}

function roundedMillis(value) {
  const numeric = Number(value);
  return Number(Math.max(0, Number.isFinite(numeric) ? numeric : 0).toFixed(3));
}

function selectScalarTelemetry(source, fields) {
  if (source === null || typeof source !== "object") {
    return null;
  }
  const snapshot = {};
  for (const field of fields) {
    if (!Object.prototype.hasOwnProperty.call(source, field)) continue;
    const value = source[field];
    if (typeof value === "number") {
      if (Number.isFinite(value)) snapshot[field] = value;
    } else if (typeof value === "string" || typeof value === "boolean") {
      snapshot[field] = value;
    }
  }
  return snapshot;
}

function installWorkerGcObserver() {
  if (!workerGcStats.supported || workerGcObserver !== undefined) return;
  try {
    workerGcObserver = new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        const duration = Math.max(0, Number(entry.duration) || 0);
        workerGcStats.count++;
        workerGcStats.durationMs += duration;
        workerGcStats.maxDurationMs = Math.max(workerGcStats.maxDurationMs, duration);
        workerGcStats.lastDurationMs = duration;
        workerGcStats.lastKind = Number(entry.detail?.kind) || 0;
        workerGcStats.lastAtEpochMs = performance.timeOrigin + entry.startTime + duration;
      }
    });
    workerGcObserver.observe({entryTypes: ["gc"]});
    process.once("exit", () => {
      workerGcObserver?.disconnect();
      workerGcObserver = undefined;
    });
  } catch {
    workerGcStats.supported = false;
    workerGcObserver = undefined;
  }
}

function workerSlowProbeSnapshot() {
  const memory = process.memoryUsage();
  const heap = getHeapStatistics();
  return {
    worldgen: selectScalarTelemetry(
      globalThis.__gaiusWorldgenStats,
      WORLDGEN_SLOW_SAMPLE_FIELDS,
    ),
    network: selectScalarTelemetry(
      globalThis.__gaiusNetworkStats,
      NETWORK_SLOW_SAMPLE_FIELDS,
    ),
    storage: selectScalarTelemetry(
      globalThis.__gaiusStorageStats,
      STORAGE_SLOW_SAMPLE_FIELDS,
    ),
    scheduler: selectScalarTelemetry(
      globalThis.__gaiusWorldgenSchedulerMarker,
      SCHEDULER_SLOW_SAMPLE_FIELDS,
    ),
    gc: {
      supported: workerGcStats.supported,
      count: workerGcStats.count,
      durationMs: roundedMillis(workerGcStats.durationMs),
      maxDurationMs: roundedMillis(workerGcStats.maxDurationMs),
      lastDurationMs: roundedMillis(workerGcStats.lastDurationMs),
      lastKind: workerGcStats.lastKind,
      lastAtEpochMs: workerGcStats.lastAtEpochMs,
      rssBytes: memory.rss,
      heapUsedBytes: memory.heapUsed,
      heapTotalBytes: memory.heapTotal,
      externalBytes: memory.external,
      arrayBuffersBytes: memory.arrayBuffers,
      heapLimitBytes: heap.heap_size_limit,
    },
  };
}

function safeWorkerSlowProbeSnapshot() {
  try {
    return {snapshot: workerSlowProbeSnapshot(), error: null};
  } catch (error) {
    return {
      snapshot: null,
      error: String(error && (error.stack || error.message) || error).slice(0, 512),
    };
  }
}

function normalizeSlowBlockId(value) {
  const numeric = Number(value);
  return Number.isSafeInteger(numeric) && numeric > 0 && numeric <= MAX_SLOW_BLOCK_ID
    ? numeric
    : 0;
}

function normalizeSlowSnapshotDropReason(value) {
  return value === "block-cap" || value === "capture-error" || value === "snapshot-empty"
    ? value
    : null;
}

function selectSlowProbeSnapshot(value) {
  if (value === null || typeof value !== "object") {
    return null;
  }
  return {
    worldgen: selectScalarTelemetry(value.worldgen, WORLDGEN_SLOW_SAMPLE_FIELDS),
    network: selectScalarTelemetry(value.network, NETWORK_SLOW_SAMPLE_FIELDS),
    storage: selectScalarTelemetry(value.storage, STORAGE_SLOW_SAMPLE_FIELDS),
    scheduler: selectScalarTelemetry(value.scheduler, SCHEDULER_SLOW_SAMPLE_FIELDS),
    gc: selectScalarTelemetry(value.gc, [
      "supported",
      "count",
      "durationMs",
      "maxDurationMs",
      "lastDurationMs",
      "lastKind",
      "lastAtEpochMs",
      "rssBytes",
      "heapUsedBytes",
      "heapTotalBytes",
      "externalBytes",
      "arrayBuffersBytes",
      "heapLimitBytes",
    ]),
  };
}

// A block is a contiguous run of probes that arrived while the Worker was
// draining a backlog.  Snapshot accounting belongs to blocks, not probes:
// one block can therefore serve dozens of queued probes without repeatedly
// serializing the same diagnostic object.
function createSlowProbeBlockState() {
  let nextBlockId = 0;
  let activeBlock = null;
  let beforeCaptureCount = 0;
  let afterCaptureCount = 0;

  function beginBlock(afterProtocolReady) {
    nextBlockId = nextBlockId >= MAX_SLOW_BLOCK_ID ? 1 : nextBlockId + 1;
    activeBlock = {
      id: nextBlockId,
      afterProtocolReady: Boolean(afterProtocolReady),
      state: "pending",
    };
    return activeBlock;
  }

  function resetIfFast(backlogSignal) {
    if (!backlogSignal) activeBlock = null;
  }

  function ensureBlock(afterProtocolReady) {
    return activeBlock || beginBlock(afterProtocolReady);
  }

  function capture(block, snapshotFactory) {
    if (!block || typeof block !== "object") {
      return {
        snapshot: null,
        error: null,
        reused: false,
        dropReason: null,
      };
    }
    if (block.state === "captured") {
      return {
        snapshot: null,
        error: null,
        reused: true,
        dropReason: null,
      };
    }
    // A capped/error/empty block records its reason only on the rising edge;
    // later probes in that same block do not become repeated drops.
    if (block.state !== "pending") {
      return {
        snapshot: null,
        error: null,
        reused: false,
        dropReason: null,
      };
    }
    block.state = "attempted";
    if (block.afterProtocolReady) {
      if (afterCaptureCount >= MAX_SLOW_SAMPLES_PER_SCOPE) {
        block.state = "block-cap";
        return {
          snapshot: null,
          error: null,
          reused: false,
          dropReason: "block-cap",
        };
      }
      afterCaptureCount++;
    } else {
      if (beforeCaptureCount >= MAX_SLOW_SAMPLES_PER_SCOPE) {
        block.state = "block-cap";
        return {
          snapshot: null,
          error: null,
          reused: false,
          dropReason: "block-cap",
        };
      }
      beforeCaptureCount++;
    }
    let captured;
    try {
      captured = snapshotFactory();
    } catch (error) {
      captured = {
        snapshot: null,
        error: String(error && (error.stack || error.message) || error).slice(0, 512),
      };
    }
    if (captured && captured.snapshot !== null && captured.snapshot !== undefined) {
      block.state = "captured";
      return {
        snapshot: captured.snapshot,
        error: null,
        reused: false,
        dropReason: null,
      };
    }
    const error = captured && typeof captured.error === "string"
      ? captured.error.slice(0, 512)
      : null;
    if (error !== null) {
      block.state = "capture-error";
      return {
        snapshot: null,
        error,
        reused: false,
        dropReason: "capture-error",
      };
    }
    block.state = "snapshot-empty";
    return {
      snapshot: null,
      error: null,
      reused: false,
      dropReason: "snapshot-empty",
    };
  }

  return {
    beginBlock,
    resetIfFast,
    ensureBlock,
    capture,
    counts() {
      return {
        before: beforeCaptureCount,
        after: afterCaptureCount,
      };
    },
  };
}

function eventLoopProbeSample(probe, message, parentReceiveEpochMs,
    parentReceiveMonoMs, phaseAtReceive, protocolReadyAt,
    snapshotBlockMap = null, fallbackSnapshotBlockMap = null) {
  const anomalies = [];
  const workerStartEpochMs = Number(message.workerStartEpochMs);
  const workerEndEpochMs = Number(message.workerEndEpochMs);
  const workerStartMonoMs = Number(message.workerStartMonoMs);
  const workerEndMonoMs = Number(message.workerEndMonoMs);
  const workerInterProbeGapMs = Number(message.workerInterProbeGapMs);
  const timingValues = [
    workerStartEpochMs,
    workerEndEpochMs,
    workerStartMonoMs,
    workerEndMonoMs,
    workerInterProbeGapMs,
  ];
  if (message.slowSampleSchemaVersion !== SLOW_SAMPLE_SCHEMA_VERSION) {
    anomalies.push("schema-version-mismatch");
  }
  if (message.slowSampleSchema !== undefined &&
      message.slowSampleSchema !== SLOW_SAMPLE_SCHEMA) {
    anomalies.push("schema-name-mismatch");
  }
  if (message.slowSampleThresholdMismatch === true) {
    anomalies.push("threshold-mismatch");
  }
  if (message.probeId !== probe.probeId) anomalies.push("probe-id-mismatch");
  if (!timingValues.every(Number.isFinite)) anomalies.push("worker-timing-missing");
  if (workerStartEpochMs + 1 < probe.parentSendEpochMs) {
    anomalies.push("worker-start-before-parent-send");
  }
  if (workerEndEpochMs + 1 < workerStartEpochMs ||
      workerEndMonoMs + 0.001 < workerStartMonoMs) {
    anomalies.push("worker-end-before-worker-start");
  }
  if (parentReceiveEpochMs + 1 < workerEndEpochMs) {
    anomalies.push("parent-receive-before-worker-end");
  }
  if (parentReceiveMonoMs + 0.001 < probe.parentSendMonoMs) {
    anomalies.push("parent-receive-mono-before-parent-send");
  }
  if (workerInterProbeGapMs < -0.001) {
    anomalies.push("worker-inter-probe-gap-negative");
  }
  if (message.parentSendEpochMs !== probe.parentSendEpochMs ||
      message.parentSendMonoMs !== probe.parentSendMonoMs) {
    anomalies.push("parent-send-echo-mismatch");
  }
  if (message.phaseAtSend !== probe.phaseAtSend) {
    anomalies.push("phase-echo-mismatch");
  }

  const parentToWorkerMs = roundedMillis(workerStartEpochMs - probe.parentSendEpochMs);
  const workerHandlerMs = roundedMillis(workerEndMonoMs - workerStartMonoMs);
  const workerToParentMs = roundedMillis(parentReceiveEpochMs - workerEndEpochMs);
  const roundTripMs = roundedMillis(parentReceiveMonoMs - probe.parentSendMonoMs);
  const decompositionDriftMs = roundedMillis(Math.abs(roundTripMs -
    (parentToWorkerMs + workerHandlerMs + workerToParentMs)));
  if (decompositionDriftMs > 5) anomalies.push("cross-clock-decomposition-drift");

  const trigger = new Set(Array.isArray(message.slowTrigger) ? message.slowTrigger : []);
  if (parentToWorkerMs >= SLOW_SAMPLE_THRESHOLD_MS) trigger.add("parent-to-worker");
  if (workerHandlerMs >= SLOW_SAMPLE_THRESHOLD_MS) trigger.add("worker-handler");
  if (workerToParentMs >= SLOW_SAMPLE_THRESHOLD_MS) trigger.add("worker-to-parent");
  if (roundTripMs >= SLOW_SAMPLE_THRESHOLD_MS) trigger.add("round-trip");
  if (workerInterProbeGapMs >= SLOW_SAMPLE_THRESHOLD_MS) {
    trigger.add("worker-inter-probe-gap");
  }
  const slowBlockId = normalizeSlowBlockId(message.slowBlockId);
  const directSnapshot = selectSlowProbeSnapshot(message.slowSnapshot);
  const rawSnapshotReused = message.slowSnapshotReused === true;
  const reusedSnapshot = directSnapshot === null && rawSnapshotReused && slowBlockId > 0
    ? snapshotBlockMap?.get(slowBlockId) ?? fallbackSnapshotBlockMap?.get(slowBlockId) ?? null
    : null;
  const snapshot = directSnapshot ?? reusedSnapshot;
  const snapshotReused = rawSnapshotReused && snapshot !== null;
  const slowSnapshotDropReason = normalizeSlowSnapshotDropReason(
    message.slowSnapshotDropReason,
  );
  const snapshotDropped = snapshot === null && message.slowSnapshotDropped === true &&
    !rawSnapshotReused;
  return {
    schemaVersion: SLOW_SAMPLE_SCHEMA_VERSION,
    probeId: probe.probeId,
    slowBlockId,
    slowSnapshotReused: rawSnapshotReused,
    slowSnapshotDropReason,
    phaseAtSend: probe.phaseAtSend,
    phaseAtReceive,
    afterProtocolReady: protocolReadyAt > 0 &&
      probe.parentSendEpochMs >= protocolReadyAt,
    parentSendEpochMs: probe.parentSendEpochMs,
    parentSendMonoMs: probe.parentSendMonoMs,
    workerStartEpochMs,
    workerStartMonoMs,
    workerEndEpochMs,
    workerEndMonoMs,
    parentReceiveEpochMs,
    parentReceiveMonoMs,
    parentToWorkerMs,
    workerHandlerMs,
    workerToParentMs,
    roundTripMs,
    workerInterProbeGapMs: roundedMillis(workerInterProbeGapMs),
    decompositionDriftMs,
    trigger: [...trigger].sort(),
    clockAnomaly: anomalies.length > 0,
    clockAnomalies: anomalies,
    snapshotCaptured: snapshot !== null,
    snapshotReused,
    snapshotDropped,
    snapshotDropReason: slowSnapshotDropReason,
    snapshotError: typeof message.slowSnapshotError === "string"
      ? message.slowSnapshotError.slice(0, 512)
      : null,
    worldgen: snapshot?.worldgen ?? null,
    network: snapshot?.network ?? null,
    storage: snapshot?.storage ?? null,
    scheduler: snapshot?.scheduler ?? null,
    gc: snapshot?.gc ?? null,
  };
}

function compareSlowProbeSamples(left, right) {
  return right.roundTripMs - left.roundTripMs || left.probeId - right.probeId;
}

function retainSlowProbeSample(samples, sample, limit = MAX_SLOW_SAMPLES_PER_SCOPE) {
  if (sample.trigger.length === 0) return false;
  samples.push(sample);
  samples.sort(compareSlowProbeSamples);
  const evicted = samples.length > limit;
  if (evicted) samples.length = limit;
  sample[SLOW_PROBE_TOP_K_EVICTED] = evicted;
  return samples.includes(sample);
}

function combinedSlowProbeSamples(beforeProtocolReady, afterProtocolReady) {
  const byProbe = new Map();
  for (const sample of [...beforeProtocolReady, ...afterProtocolReady]) {
    byProbe.set(sample.probeId, sample);
  }
  return [...byProbe.values()].sort(compareSlowProbeSamples).slice(0, MAX_SLOW_SAMPLES);
}

function buildSlowProbeEvidenceSnapshot({
  slowProbeSamplesBeforeProtocolReady,
  slowProbeSamplesAfterProtocolReady,
  slowProbeSamplesGlobal = null,
  slowProbeCandidateCount,
  slowProbeTopKRetentionDroppedCount,
  slowProbeSnapshotBlockCapDroppedCount,
  slowProbeSnapshotBlocksBeforeProtocolReady,
  slowProbeSnapshotBlocksAfterProtocolReady,
  slowProbeSnapshotErrorCount,
  slowProbeClockAnomalyCount,
  slowProbeClockAnomalies,
}) {
  const slowProbeSamples = Array.isArray(slowProbeSamplesGlobal)
    ? slowProbeSamplesGlobal.slice().sort(compareSlowProbeSamples).slice(0, MAX_SLOW_SAMPLES)
    : combinedSlowProbeSamples(
      slowProbeSamplesBeforeProtocolReady,
      slowProbeSamplesAfterProtocolReady,
    );
  const phaseView = (samples) => Array.isArray(samples)
    ? samples.slice().sort(compareSlowProbeSamples).slice(0, MAX_SLOW_SAMPLES_PER_SCOPE)
    : [];
  // Count candidates lost by the authoritative global Top-64 contract.  The
  // before/after arrays remain attribution views and are not used to infer
  // global retention.
  const retentionDropped = Math.max(
    0,
    Number(slowProbeCandidateCount) - slowProbeSamples.length,
  );
  return {
    schemaVersion: SLOW_SAMPLE_SCHEMA_VERSION,
    schema: SLOW_SAMPLE_SCHEMA,
    slowSampleSchema: SLOW_SAMPLE_SCHEMA,
    slowSampleSchemaVersion: SLOW_SAMPLE_SCHEMA_VERSION,
    thresholdMs: SLOW_SAMPLE_THRESHOLD_MS,
    limit: MAX_SLOW_SAMPLES,
    perScopeLimit: MAX_SLOW_SAMPLES_PER_SCOPE,
    countTotal: slowProbeCandidateCount,
    countRetained: slowProbeSamples.length,
    dropped: retentionDropped,
    topKRetentionDropped: slowProbeTopKRetentionDroppedCount,
    retainedBeforeProtocolReady: slowProbeSamplesBeforeProtocolReady.length,
    retainedAfterProtocolReady: slowProbeSamplesAfterProtocolReady.length,
    retainedGlobalBeforeProtocolReady: slowProbeSamples.filter((sample) =>
      sample.afterProtocolReady !== true).length,
    retainedGlobalAfterProtocolReady: slowProbeSamples.filter((sample) =>
      sample.afterProtocolReady === true).length,
    retentionModel: "global-top-64-with-balanced-phase-views",
    snapshotDropped: slowProbeSnapshotBlockCapDroppedCount,
    snapshotBlockCapDropped: slowProbeSnapshotBlockCapDroppedCount,
    snapshotBlockCapDropCount: slowProbeSnapshotBlockCapDroppedCount,
    snapshotBlocksBeforeProtocolReady: slowProbeSnapshotBlocksBeforeProtocolReady.size,
    snapshotBlocksAfterProtocolReady: slowProbeSnapshotBlocksAfterProtocolReady.size,
    snapshotErrors: slowProbeSnapshotErrorCount,
    clockAnomalyCount: slowProbeClockAnomalyCount,
    clockAnomalies: slowProbeClockAnomalies.slice(),
    timingModel: {
      parentToWorker: "cross-thread epoch: worker-start - parent-send",
      workerHandler: "worker monotonic: worker-end - worker-start",
      workerToParent: "cross-thread epoch: parent-receive - worker-end",
      roundTrip: "parent monotonic: parent-receive - parent-send",
    },
    samples: slowProbeSamples,
    phaseViews: {
      beforeProtocolReady: phaseView(slowProbeSamplesBeforeProtocolReady),
      afterProtocolReady: phaseView(slowProbeSamplesAfterProtocolReady),
    },
  };
}

function buildWorkerEventLoopEvidenceSnapshot({
  eventLoopProbeLatenciesMs,
  eventLoopProbeSamples,
  slowProbeSamplesBeforeProtocolReady,
  slowProbeSamplesAfterProtocolReady,
  slowProbeSamplesGlobal = null,
  slowProbeCandidateCount,
  slowProbeTopKRetentionDroppedCount,
  slowProbeSnapshotBlockCapDroppedCount,
  slowProbeSnapshotBlocksBeforeProtocolReady,
  slowProbeSnapshotBlocksAfterProtocolReady,
  slowProbeSnapshotErrorCount,
  slowProbeClockAnomalyCount,
  slowProbeClockAnomalies,
  longestEventLoopProbe,
  longestGameplayEventLoopProbe,
  smokeStartedAt,
  serverCreatedAt,
  protocolReadyAt,
  cooperativePumpMode,
  currentWorkerPhase,
  pendingProbeCount,
}) {
  const sortedProbeLatencies = eventLoopProbeLatenciesMs
    .slice()
    .sort((left, right) => left - right);
  const phaseLatencies = summarizeProbePhases(eventLoopProbeSamples);
  const gameplayLatency = summarizeGameplayProbeLatencies(eventLoopProbeSamples);
  const protocolReady = protocolReadyAt > 0;
  const afterProtocolReadyGameplayLatency = protocolReady
    ? summarizeGameplayProbeLatencies(
      eventLoopProbeSamples.filter((sample) => sample.startedAt >= protocolReadyAt),
    )
    : summarizeGameplayProbeLatencies([]);
  const slowProbeEvidence = buildSlowProbeEvidenceSnapshot({
    slowProbeSamplesBeforeProtocolReady,
    slowProbeSamplesAfterProtocolReady,
    slowProbeSamplesGlobal,
    slowProbeCandidateCount,
    slowProbeTopKRetentionDroppedCount,
    slowProbeSnapshotBlockCapDroppedCount,
    slowProbeSnapshotBlocksBeforeProtocolReady,
    slowProbeSnapshotBlocksAfterProtocolReady,
    slowProbeSnapshotErrorCount,
    slowProbeClockAnomalyCount,
    slowProbeClockAnomalies,
  });
  // Worker direct-executor mode does not promise cooperative pump activity.
  // Its meaningful stall window starts after the client is protocol-ready;
  // startup/worldgen work before that point is intentionally staged.  When
  // the cooperative pump is expected or observed, retain the stricter full
  // gameplay window so regressions cannot hide behind protocol readiness.
  const stallValidation = !protocolReady
    ? gameplayLatency
    : cooperativePumpMode.requireActivity
      ? gameplayLatency
      : afterProtocolReadyGameplayLatency;
  const stallValidationScope = !protocolReady
    ? "before-protocol-ready"
    : cooperativePumpMode.requireActivity
      ? "all-gameplay"
      : "after-protocol-ready";
  const afterSmokeMillis = (timestamp) => timestamp > 0
    ? timestamp - smokeStartedAt
    : 0;
  const workerEventLoopLatency = {
    type: "worker-event-loop-latency",
    schemaVersion: 3,
    slowSampleSchema: SLOW_SAMPLE_SCHEMA,
    slowSampleSchemaVersion: SLOW_SAMPLE_SCHEMA_VERSION,
    samples: sortedProbeLatencies.length,
    p95Ms: percentile(sortedProbeLatencies, 0.95),
    p99Ms: percentile(sortedProbeLatencies, 0.99),
    maxMs: sortedProbeLatencies.at(-1) || 0,
    maxStartedAfterSmokeMs: afterSmokeMillis(longestEventLoopProbe.startedAt),
    maxCompletedAfterSmokeMs: afterSmokeMillis(longestEventLoopProbe.completedAt),
    longestGameplay: {
      ...longestGameplayEventLoopProbe,
      startedAfterSmokeMs: afterSmokeMillis(longestGameplayEventLoopProbe.startedAt),
      completedAfterSmokeMs: afterSmokeMillis(longestGameplayEventLoopProbe.completedAt),
    },
    afterServerCreated: summarizeProbeLatencies(eventLoopProbeSamples, serverCreatedAt),
    afterProtocolReady: summarizeProbeLatencies(eventLoopProbeSamples, protocolReadyAt),
    gameplay: gameplayLatency,
    stallValidation,
    stallValidationScope,
    byPhase: phaseLatencies,
    pending: pendingProbeCount,
    protocolReady,
    currentWorkerPhase,
    workerPhase: currentWorkerPhase,
    protocolReadyAt,
    slowProbeEvidence,
  };
  return {slowProbeEvidence, workerEventLoopLatency};
}

function buildWorldgenEventLoopStallEvent({
  workerEventLoopLatency,
  maximumGameplayStallMs,
  slowProbeEvidence,
}) {
  return {
    type: "worldgen-event-loop-stall",
    maximumGameplayStallMs,
    gameplayLatency: workerEventLoopLatency.stallValidation,
    allGameplayLatency: workerEventLoopLatency.gameplay,
    afterProtocolReadyGameplayLatency: workerEventLoopLatency.afterProtocolReady,
    stallValidationScope: workerEventLoopLatency.stallValidationScope,
    slowProbeEvidence,
  };
}

function runWorkerEventLoopEvidenceSelfSmoke() {
  const slowSample = {
    probeId: 3,
    roundTripMs: 6238,
    trigger: ["round-trip"],
  };
  const snapshot = buildWorkerEventLoopEvidenceSnapshot({
    eventLoopProbeLatenciesMs: [94, 565, 6238],
    eventLoopProbeSamples: [
      {probeId: 1, latencyMs: 94, startedAt: 1000, phase: "server-created"},
      {probeId: 2, latencyMs: 565, startedAt: 2000, phase: "distance-staged"},
      {probeId: 3, latencyMs: 6238, startedAt: 3000, phase: "distance-7/3"},
    ],
    slowProbeSamplesBeforeProtocolReady: [],
    slowProbeSamplesAfterProtocolReady: [slowSample],
    slowProbeCandidateCount: 1,
    slowProbeTopKRetentionDroppedCount: 0,
    slowProbeSnapshotBlockCapDroppedCount: 0,
    slowProbeSnapshotBlocksBeforeProtocolReady: new Map(),
    slowProbeSnapshotBlocksAfterProtocolReady: new Map([[1, {scheduler: {lastTaskScopeWallMillis: 6238}}]]),
    slowProbeSnapshotErrorCount: 0,
    slowProbeClockAnomalyCount: 0,
    slowProbeClockAnomalies: [],
    longestEventLoopProbe: {
      probeId: 3,
      latencyMs: 6238,
      startedAt: 3000,
      completedAt: 9238,
      phase: "distance-7/3",
    },
    longestGameplayEventLoopProbe: {
      probeId: 3,
      latencyMs: 6238,
      startedAt: 3000,
      completedAt: 9238,
      phase: "distance-7/3",
    },
    smokeStartedAt: 900,
    serverCreatedAt: 1000,
    protocolReadyAt: 1500,
    cooperativePumpMode: {requireActivity: false},
    currentWorkerPhase: "distance-7/3",
    pendingProbeCount: 2,
  });
  const latency = snapshot.workerEventLoopLatency;
  if (snapshot.slowProbeEvidence.schemaVersion !== SLOW_SAMPLE_SCHEMA_VERSION ||
      snapshot.slowProbeEvidence.countTotal !== 1 ||
      snapshot.slowProbeEvidence.countRetained !== 1 ||
      snapshot.slowProbeEvidence.samples[0].roundTripMs !== 6238 ||
      latency.maxMs !== 6238 || latency.byPhase["distance-7/3"].maxMs !== 6238 ||
      latency.currentWorkerPhase !== "distance-7/3" ||
      latency.workerPhase !== "distance-7/3" || latency.protocolReadyAt !== 1500 ||
      latency.protocolReady !== true || latency.stallValidationScope !== "after-protocol-ready") {
    throw new Error("timeout worker event-loop evidence self-smoke failed");
  }
  const beforeReady = buildWorkerEventLoopEvidenceSnapshot({
    eventLoopProbeLatenciesMs: [6238],
    eventLoopProbeSamples: [
      {probeId: 3, latencyMs: 6238, startedAt: 3000, phase: "distance-staged"},
    ],
    slowProbeSamplesBeforeProtocolReady: [slowSample],
    slowProbeSamplesAfterProtocolReady: [],
    slowProbeCandidateCount: 1,
    slowProbeTopKRetentionDroppedCount: 0,
    slowProbeSnapshotBlockCapDroppedCount: 0,
    slowProbeSnapshotBlocksBeforeProtocolReady: new Map([[1, {scheduler: {activeTaskScope: true}}]]),
    slowProbeSnapshotBlocksAfterProtocolReady: new Map(),
    slowProbeSnapshotErrorCount: 0,
    slowProbeClockAnomalyCount: 0,
    slowProbeClockAnomalies: [],
    longestEventLoopProbe: {
      probeId: 3,
      latencyMs: 6238,
      startedAt: 3000,
      completedAt: 9238,
      phase: "distance-staged",
    },
    longestGameplayEventLoopProbe: {
      probeId: 3,
      latencyMs: 6238,
      startedAt: 3000,
      completedAt: 9238,
      phase: "distance-staged",
    },
    smokeStartedAt: 900,
    serverCreatedAt: 1000,
    protocolReadyAt: 0,
    cooperativePumpMode: {requireActivity: false},
    currentWorkerPhase: "distance-staged",
    pendingProbeCount: 1,
  }).workerEventLoopLatency;
  if (beforeReady.protocolReady !== false || beforeReady.protocolReadyAt !== 0 ||
      beforeReady.afterProtocolReady.samples !== 0 ||
      beforeReady.afterProtocolReady.maxMs !== 0 ||
      beforeReady.stallValidationScope !== "before-protocol-ready" ||
      beforeReady.stallValidation.maxMs !== 6238) {
    throw new Error("timeout before-protocol-ready scope self-smoke failed");
  }
  const stallEvent = buildWorldgenEventLoopStallEvent({
    workerEventLoopLatency: latency,
    maximumGameplayStallMs: 500,
    slowProbeEvidence: snapshot.slowProbeEvidence,
  });
  if (stallEvent.type !== "worldgen-event-loop-stall" ||
      stallEvent.maximumGameplayStallMs !== 500 ||
      stallEvent.gameplayLatency !== latency.stallValidation ||
      stallEvent.allGameplayLatency !== latency.gameplay ||
      stallEvent.afterProtocolReadyGameplayLatency !== latency.afterProtocolReady ||
      stallEvent.stallValidationScope !== "after-protocol-ready" ||
      stallEvent.slowProbeEvidence !== snapshot.slowProbeEvidence) {
    throw new Error("worldgen event-loop stall event self-smoke failed");
  }
  return {
    ok: true,
    boundedSlowProbeEvidence: true,
    workerEventLoopAggregate: true,
    beforeProtocolReadyScope: true,
    currentWorkerPhase: true,
    protocolReadyAt: true,
    stallEvent: true,
  };
}

function runSlowProbeSelfSmoke() {
  if ([Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]
      .some((value) => roundedMillis(value) !== 0)) {
    throw new Error("slow probe non-finite timing clamp self-smoke failed");
  }
  for (const [name, fields] of Object.entries({
    worldgen: WORLDGEN_SLOW_SAMPLE_FIELDS,
    network: NETWORK_SLOW_SAMPLE_FIELDS,
    storage: STORAGE_SLOW_SAMPLE_FIELDS,
    scheduler: SCHEDULER_SLOW_SAMPLE_FIELDS,
  })) {
    if (fields.length > MAX_SLOW_SAMPLE_FIELDS_PER_GROUP) {
      throw new Error(`${name} slow-probe allowlist exceeded the fixed scalar limit`);
    }
  }
  const probe = {
    probeId: 7,
    phaseAtSend: "distance-6/3",
    parentSendEpochMs: 1000,
    parentSendMonoMs: 500,
  };
  const sample = eventLoopProbeSample(probe, {
    probeId: 7,
    slowSampleSchemaVersion: SLOW_SAMPLE_SCHEMA_VERSION,
    phaseAtSend: "distance-6/3",
    parentSendEpochMs: 1000,
    parentSendMonoMs: 500,
    workerStartEpochMs: 1300,
    workerStartMonoMs: 800,
    workerEndEpochMs: 1302,
    workerEndMonoMs: 802,
    workerInterProbeGapMs: 301,
    slowTrigger: ["parent-to-worker", "worker-inter-probe-gap"],
    slowBlockId: 1,
    slowSnapshotReused: false,
    slowSnapshotDropReason: null,
    slowSnapshot: {
      worldgen: {
        slices: 5,
        totalSliceElapsedMillis: 198.5,
        deadlineYields: 4,
        checkpointYields: 1,
        forbidden: 1,
      },
      network: {
        inboundQueuedBytes: 2,
        integratedServerDistanceMaxViewApplyMillis: 6238,
        integratedServerDistanceMaxSimulationApplyMillis: 565,
        forbidden: 1,
      },
      storage: {backend: "opfs", pendingEntries: 2, opfsFlushes: 3, forbidden: 1},
      scheduler: {
        activeTaskScope: true,
        taskWorkDepth: 1,
        lastTaskActiveWorkMillis: 94.1,
        lastTaskScopeWallMillis: 6220,
        serverWorkTurnActive: true,
        forbidden: 1,
      },
      gc: {supported: false, count: 0, forbidden: 1},
    },
  }, 1310, 810, "distance-6/3", 900);
  if (sample.parentToWorkerMs !== 300 || sample.workerHandlerMs !== 2 ||
      sample.workerToParentMs !== 8 || sample.roundTripMs !== 310 ||
      sample.slowBlockId !== 1 || sample.snapshotReused ||
      sample.worldgen?.forbidden !== undefined ||
      sample.network?.forbidden !== undefined || sample.gc?.forbidden !== undefined) {
    throw new Error("slow probe timing or scalar allowlist self-smoke failed");
  }
  if (sample.worldgen.totalSliceElapsedMillis !== 198.5 ||
      sample.worldgen.deadlineYields !== 4 || sample.worldgen.checkpointYields !== 1 ||
      sample.network?.integratedServerDistanceMaxViewApplyMillis !== 6238 ||
      sample.network?.integratedServerDistanceMaxSimulationApplyMillis !== 565 ||
      sample.storage?.opfsFlushes !== 3 || sample.storage?.forbidden !== undefined ||
      sample.storage?.backend !== "opfs" || sample.storage?.pendingEntries !== 2 ||
      sample.scheduler?.activeTaskScope !== true ||
      sample.scheduler?.lastTaskActiveWorkMillis !== 94.1 ||
      sample.scheduler?.lastTaskScopeWallMillis !== 6220 ||
      sample.scheduler?.serverWorkTurnActive !== true ||
      sample.scheduler?.forbidden !== undefined) {
    throw new Error("slow probe diagnostic snapshot self-smoke failed");
  }
  const segmented = eventLoopProbeSample(probe, {
    probeId: 7,
    slowSampleSchemaVersion: SLOW_SAMPLE_SCHEMA_VERSION,
    phaseAtSend: probe.phaseAtSend,
    parentSendEpochMs: probe.parentSendEpochMs,
    parentSendMonoMs: probe.parentSendMonoMs,
    workerStartEpochMs: 1010,
    workerStartMonoMs: 510,
    workerEndEpochMs: 1310,
    workerEndMonoMs: 810,
    workerInterProbeGapMs: 10,
    slowTrigger: [],
    slowBlockId: 0,
    slowSnapshotReused: false,
    slowSnapshot: null,
  }, 1610, 1110, probe.phaseAtSend, 900);
  for (const trigger of ["worker-handler", "worker-to-parent", "round-trip"]) {
    if (!segmented.trigger.includes(trigger)) {
      throw new Error(`slow probe did not retain the ${trigger} segment trigger`);
    }
  }
  const clockAnomaly = eventLoopProbeSample(probe, {
    probeId: probe.probeId,
    slowSampleSchemaVersion: SLOW_SAMPLE_SCHEMA_VERSION,
    phaseAtSend: probe.phaseAtSend,
    parentSendEpochMs: probe.parentSendEpochMs,
    parentSendMonoMs: probe.parentSendMonoMs,
    workerStartEpochMs: 900,
    workerStartMonoMs: 400,
    workerEndEpochMs: 899,
    workerEndMonoMs: 399,
    workerInterProbeGapMs: -3,
    slowTrigger: [],
    slowBlockId: 0,
    slowSnapshotReused: false,
    slowSnapshot: null,
  }, 800, 450, probe.phaseAtSend, 900);
  if (!clockAnomaly.clockAnomaly || clockAnomaly.parentToWorkerMs !== 0 ||
      clockAnomaly.workerHandlerMs !== 0 || clockAnomaly.workerToParentMs !== 0 ||
      clockAnomaly.roundTripMs !== 0 || clockAnomaly.workerInterProbeGapMs !== 0 ||
      !clockAnomaly.clockAnomalies.includes("worker-start-before-parent-send") ||
      !clockAnomaly.clockAnomalies.includes("parent-receive-mono-before-parent-send") ||
      !clockAnomaly.clockAnomalies.includes("worker-inter-probe-gap-negative")) {
    throw new Error("slow probe clock anomaly clamp self-smoke failed");
  }

  const fast = eventLoopProbeSample(probe, {
    probeId: 7,
    slowSampleSchemaVersion: SLOW_SAMPLE_SCHEMA_VERSION,
    phaseAtSend: probe.phaseAtSend,
    parentSendEpochMs: probe.parentSendEpochMs,
    parentSendMonoMs: probe.parentSendMonoMs,
    workerStartEpochMs: 1001,
    workerStartMonoMs: 501,
    workerEndEpochMs: 1002,
    workerEndMonoMs: 502,
    workerInterProbeGapMs: 100,
    slowTrigger: [],
    slowBlockId: 0,
    slowSnapshotReused: false,
    slowSnapshot: null,
  }, 1003, 503, probe.phaseAtSend, 900);
  if (fast.trigger.length !== 0 || fast.snapshotCaptured || fast.slowBlockId !== 0 ||
      fast.snapshotReused || retainSlowProbeSample([], fast)) {
    throw new Error("fast event-loop probe entered the bounded slow-sample ring");
  }

  const snapshotForSample = (value) => ({
    worldgen: value.worldgen,
    network: value.network,
    storage: value.storage,
    scheduler: value.scheduler,
    gc: value.gc,
  });
  const backlogState = createSlowProbeBlockState();
  const backlogSnapshotMap = new Map();
  const backlogSamples = [];
  let backlogCaptureCount = 0;
  const backlogSnapshot = {
    worldgen: {slices: 1, totalSliceElapsedMillis: 1},
    network: {inboundQueuedBytes: 1},
    storage: {backend: "opfs"},
    scheduler: {taskWorkDepth: 1},
    gc: {supported: false},
  };
  for (let index = 0; index < MAX_SLOW_SAMPLES; index++) {
    backlogState.resetIfFast(true);
    const block = backlogState.ensureBlock(false);
    const captured = backlogState.capture(block, () => {
      backlogCaptureCount++;
      return {snapshot: backlogSnapshot, error: null};
    });
    const backlogProbe = {
      probeId: 100 + index,
      phaseAtSend: "distance-backlog",
      parentSendEpochMs: 2000 + index,
      parentSendMonoMs: 2000 + index,
    };
    const backlogSample = eventLoopProbeSample(backlogProbe, {
      probeId: backlogProbe.probeId,
      slowSampleSchemaVersion: SLOW_SAMPLE_SCHEMA_VERSION,
      phaseAtSend: backlogProbe.phaseAtSend,
      parentSendEpochMs: backlogProbe.parentSendEpochMs,
      parentSendMonoMs: backlogProbe.parentSendMonoMs,
      workerStartEpochMs: backlogProbe.parentSendEpochMs + 300,
      workerStartMonoMs: backlogProbe.parentSendMonoMs + 300,
      workerEndEpochMs: backlogProbe.parentSendEpochMs + 301,
      workerEndMonoMs: backlogProbe.parentSendMonoMs + 301,
      workerInterProbeGapMs: 1,
      slowTrigger: ["parent-to-worker"],
      slowBlockId: block.id,
      slowSnapshotReused: captured.reused,
      slowSnapshotDropReason: captured.dropReason,
      slowSnapshotDropped: captured.dropReason !== null,
      slowSnapshot: captured.snapshot,
    }, backlogProbe.parentSendEpochMs + 302, backlogProbe.parentSendMonoMs + 302,
    backlogProbe.phaseAtSend, 0, backlogSnapshotMap);
    if (backlogSample.snapshotCaptured && !backlogSnapshotMap.has(block.id)) {
      backlogSnapshotMap.set(block.id, snapshotForSample(backlogSample));
    }
    backlogSamples.push(backlogSample);
  }
  const firstBacklog = backlogSamples[0];
  const lastBacklog = backlogSamples.at(-1);
  if (backlogCaptureCount !== 1 || backlogState.counts().before !== 1 ||
      firstBacklog.slowBlockId === 0 || firstBacklog.snapshotReused ||
      !firstBacklog.snapshotCaptured || !lastBacklog.snapshotCaptured ||
      !lastBacklog.snapshotReused || lastBacklog.snapshotDropped ||
      lastBacklog.slowSnapshotDropReason !== null) {
    throw new Error("slow backlog block reuse self-smoke failed");
  }

  backlogState.resetIfFast(false);
  const secondBlock = backlogState.ensureBlock(false);
  const secondCapture = backlogState.capture(secondBlock, () => {
    backlogCaptureCount++;
    return {snapshot: backlogSnapshot, error: null};
  });
  if (secondBlock.id === firstBacklog.slowBlockId || !secondCapture.snapshot ||
      secondCapture.reused || backlogCaptureCount !== 2) {
    throw new Error("slow backlog rising-edge block self-smoke failed");
  }

  const retained = [];
  let scopeTopKRetentionDropped = 0;
  for (let index = 0; index < MAX_SLOW_SAMPLES_PER_SCOPE + 1; index++) {
    const candidate = {
      ...lastBacklog,
      probeId: 500 + index,
      roundTripMs: index + 1,
    };
    retainSlowProbeSample(retained, candidate);
    if (candidate[SLOW_PROBE_TOP_K_EVICTED] === true) scopeTopKRetentionDropped++;
  }
  if (retained.length !== MAX_SLOW_SAMPLES_PER_SCOPE ||
      retained[0].roundTripMs !== MAX_SLOW_SAMPLES_PER_SCOPE + 1 ||
      retained.at(-1).roundTripMs !== 2 || scopeTopKRetentionDropped !== 1 ||
      !retained[0].snapshotCaptured || !retained[0].snapshotReused) {
    throw new Error("slow probe bounded top-K reuse self-smoke failed");
  }
  const globalRetained = [];
  let topKRetentionDropped = 0;
  for (let index = 0; index < MAX_SLOW_SAMPLES + 1; index++) {
    const candidate = {
      ...lastBacklog,
      probeId: 700 + index,
      roundTripMs: index + 1,
    };
    retainSlowProbeSample(globalRetained, candidate, MAX_SLOW_SAMPLES);
    if (candidate[SLOW_PROBE_TOP_K_EVICTED] === true) topKRetentionDropped++;
  }
  if (globalRetained.length !== MAX_SLOW_SAMPLES ||
      globalRetained[0].roundTripMs !== MAX_SLOW_SAMPLES + 1 ||
      globalRetained.at(-1).roundTripMs !== 2 || topKRetentionDropped !== 1 ||
      !globalRetained[0].snapshotCaptured || !globalRetained[0].snapshotReused) {
    throw new Error("slow probe global top-K reuse self-smoke failed");
  }
  const tieRetained = [];
  for (const [probeId, roundTripMs] of [[901, 100], [900, 100], [899, 101]]) {
    retainSlowProbeSample(tieRetained, {
      ...lastBacklog,
      probeId,
      roundTripMs,
    }, MAX_SLOW_SAMPLES);
  }
  if (tieRetained.map((sample) => sample.probeId).join(",") !== "899,900,901") {
    throw new Error("slow probe Top-64 tie ordering self-smoke failed");
  }
  const boundedEvidence = buildSlowProbeEvidenceSnapshot({
    slowProbeSamplesBeforeProtocolReady: [],
    slowProbeSamplesAfterProtocolReady: retained,
    slowProbeSamplesGlobal: globalRetained,
    slowProbeCandidateCount: MAX_SLOW_SAMPLES + 1,
    slowProbeTopKRetentionDroppedCount: topKRetentionDropped,
    slowProbeSnapshotBlockCapDroppedCount: 0,
    slowProbeSnapshotBlocksBeforeProtocolReady: new Map(),
    slowProbeSnapshotBlocksAfterProtocolReady: new Map(),
    slowProbeSnapshotErrorCount: 0,
    slowProbeClockAnomalyCount: 0,
    slowProbeClockAnomalies: [],
  });
  if (boundedEvidence.countTotal !== MAX_SLOW_SAMPLES + 1 ||
      boundedEvidence.countRetained !== MAX_SLOW_SAMPLES ||
      boundedEvidence.dropped !== 1 ||
      boundedEvidence.samples.length !== MAX_SLOW_SAMPLES ||
      boundedEvidence.samples[0].roundTripMs !== MAX_SLOW_SAMPLES + 1 ||
      boundedEvidence.samples.at(-1).roundTripMs !== 2 ||
      boundedEvidence.retentionModel !== "global-top-64-with-balanced-phase-views" ||
      boundedEvidence.retainedGlobalBeforeProtocolReady !== MAX_SLOW_SAMPLES ||
      boundedEvidence.retainedGlobalAfterProtocolReady !== 0) {
    throw new Error("slow probe total/dropped/top-64 evidence self-smoke failed");
  }
  const phaseViewBefore = Array.from({length: MAX_SLOW_SAMPLES_PER_SCOPE}, (_, index) => ({
    ...lastBacklog,
    probeId: 1000 + index,
    roundTripMs: MAX_SLOW_SAMPLES_PER_SCOPE - index,
    afterProtocolReady: false,
  }));
  const phaseViewAfter = Array.from({length: MAX_SLOW_SAMPLES_PER_SCOPE}, (_, index) => ({
    ...lastBacklog,
    probeId: 2000 + index,
    roundTripMs: MAX_SLOW_SAMPLES_PER_SCOPE - index + 100,
    afterProtocolReady: true,
  }));
  const phaseViewEvidence = buildSlowProbeEvidenceSnapshot({
    slowProbeSamplesBeforeProtocolReady: phaseViewBefore,
    slowProbeSamplesAfterProtocolReady: phaseViewAfter,
    slowProbeSamplesGlobal: globalRetained,
    slowProbeCandidateCount: phaseViewBefore.length + phaseViewAfter.length,
    slowProbeTopKRetentionDroppedCount: 0,
    slowProbeSnapshotBlockCapDroppedCount: 0,
    slowProbeSnapshotBlocksBeforeProtocolReady: new Map(),
    slowProbeSnapshotBlocksAfterProtocolReady: new Map(),
    slowProbeSnapshotErrorCount: 0,
    slowProbeClockAnomalyCount: 0,
    slowProbeClockAnomalies: [],
  });
  if (phaseViewEvidence.samples.length !== MAX_SLOW_SAMPLES ||
      phaseViewEvidence.phaseViews.beforeProtocolReady.length !== MAX_SLOW_SAMPLES_PER_SCOPE ||
      phaseViewEvidence.phaseViews.afterProtocolReady.length !== MAX_SLOW_SAMPLES_PER_SCOPE ||
      phaseViewEvidence.phaseViews.beforeProtocolReady[0].probeId !== 1000 ||
      phaseViewEvidence.phaseViews.afterProtocolReady[0].probeId !== 2000 ||
      phaseViewEvidence.phaseViews.afterProtocolReady.at(-1).probeId !== 2031) {
    throw new Error("slow probe phase-view retention self-smoke failed");
  }
  const emptyPhaseViewEvidence = buildSlowProbeEvidenceSnapshot({
    slowProbeSamplesBeforeProtocolReady: [],
    slowProbeSamplesAfterProtocolReady: [],
    slowProbeSamplesGlobal: [],
    slowProbeCandidateCount: 0,
    slowProbeTopKRetentionDroppedCount: 0,
    slowProbeSnapshotBlockCapDroppedCount: 0,
    slowProbeSnapshotBlocksBeforeProtocolReady: new Map(),
    slowProbeSnapshotBlocksAfterProtocolReady: new Map(),
    slowProbeSnapshotErrorCount: 0,
    slowProbeClockAnomalyCount: 0,
    slowProbeClockAnomalies: [],
  });
  if (emptyPhaseViewEvidence.phaseViews.beforeProtocolReady.length !== 0 ||
      emptyPhaseViewEvidence.phaseViews.afterProtocolReady.length !== 0) {
    throw new Error("empty slow probe phase-view self-smoke failed");
  }

  const capState = createSlowProbeBlockState();
  let capCaptureCount = 0;
  let blockCapDropped = 0;
  let repeatedBlockCapDropped = 0;
  for (let index = 0; index < MAX_SLOW_SAMPLES_PER_SCOPE + 1; index++) {
    capState.resetIfFast(false);
    const cappedBlock = capState.ensureBlock(false);
    const capped = capState.capture(cappedBlock, () => {
      capCaptureCount++;
      return {snapshot: backlogSnapshot, error: null};
    });
    if (capped.dropReason === "block-cap") blockCapDropped++;
    const repeated = capState.capture(cappedBlock, () => {
      capCaptureCount++;
      return {snapshot: backlogSnapshot, error: null};
    });
    if (repeated.dropReason === "block-cap") repeatedBlockCapDropped++;
  }
  if (capCaptureCount !== MAX_SLOW_SAMPLES_PER_SCOPE || blockCapDropped !== 1 ||
      repeatedBlockCapDropped !== 0 || capState.counts().before !== MAX_SLOW_SAMPLES_PER_SCOPE) {
    throw new Error("slow probe block-cap self-smoke failed");
  }

  return {
    ok: true,
    schema: SLOW_SAMPLE_SCHEMA,
    thresholdMs: SLOW_SAMPLE_THRESHOLD_MS,
    limit: MAX_SLOW_SAMPLES,
    perScopeLimit: MAX_SLOW_SAMPLES_PER_SCOPE,
    timingDecomposition: true,
    boundedTopK: true,
    scalarAllowlist: true,
    maxFieldsPerGroup: MAX_SLOW_SAMPLE_FIELDS_PER_GROUP,
    segmentedTriggers: true,
    fastProbeExcluded: true,
    backlogBlockReuse: true,
    sameBacklogCaptureCount: backlogCaptureCount - 1,
    risingEdgeBlock: true,
    topKRetentionDropped,
    scopeTopKRetentionDropped,
    totalCandidates: boundedEvidence.countTotal,
    retainedTopK: boundedEvidence.countRetained,
    droppedCandidates: boundedEvidence.dropped,
    retentionModel: boundedEvidence.retentionModel,
    blockCapDropped,
    repeatedBlockCapDropped,
  };
}

// Telemetry pongs are the only samples that cross the Worker boundary after
// the measurement window is reset.  Keep each object detached from the
// structured-clone payload, and keep the update itself pure so a stale or
// cross-session pong cannot repopulate the previous window's auxiliary stats.
function snapshotTelemetryPong(message) {
  return {
    chunkPriorityStats: copyObjectSnapshot(message.chunkPriority),
    networkStats: copyObjectSnapshot(message.network),
    worldgenStats: copyObjectSnapshot(message.worldgen),
    serverTickStats: copyObjectSnapshot(message.serverTick),
    storageStats: copyObjectSnapshot(message.storage),
  };
}

function updateLatestTelemetrySnapshots(latest, message, expectedSessionId) {
  if (message === null || typeof message !== "object" ||
      message.sessionId !== expectedSessionId) {
    return {...latest};
  }
  return {
    ...latest,
    ...snapshotTelemetryPong(message),
  };
}

function recentTelemetryAuxiliarySnapshot(samples, field, fallback) {
  for (let index = samples.length - 1; index >= 0; index--) {
    const sample = samples[index];
    if (sample && sample.available &&
        Object.prototype.hasOwnProperty.call(sample, field)) {
      return copyObjectSnapshot(sample[field]);
    }
  }
  return copyObjectSnapshot(fallback);
}


function runServerTickWindowSelfSmoke() {
  const base = {
    schemaVersion: 1, sampledAtMillis: 6000, intervalCount: 100, totalTickIntervalMillis: 5000,
    completedTickCount: 100, totalTickDurationMillis: 1200,
    completedWaitPhaseCount: 100, totalWaitPhaseDurationMillis: 3800,
  };
  const twenty = serverTickWindow(base, {
    ...base, sampledAtMillis: 11000, intervalCount: 200, totalTickIntervalMillis: 10000,
    completedTickCount: 200, totalTickDurationMillis: 2400,
    completedWaitPhaseCount: 200, totalWaitPhaseDurationMillis: 7600,
  });
  if (!twenty.available || twenty.observedTps !== 20 || twenty.elapsedMillis !== 5000 ||
      twenty.completedWaitPhaseCount !== 100 || twenty.totalWaitPhaseDurationMillis !== 3800) {
    throw new Error("20 TPS server tick window self-smoke failed");
  }
  const seven = serverTickWindow(base, {
    ...base, sampledAtMillis: 11000, intervalCount: 135, totalTickIntervalMillis: 10000,
    completedTickCount: 135, totalTickDurationMillis: 6500,
    completedWaitPhaseCount: 135, totalWaitPhaseDurationMillis: 7300,
  });
  if (!seven.available || Math.abs(seven.observedTps - 7) > 1e-9 ||
      seven.completedWaitPhaseCount !== 35) {
    throw new Error("7 TPS server tick window self-smoke failed");
  }
  if (serverTickWindow(null, base).reason !== "missing" ||
      serverTickWindow({...base, intervalCount: null}, base).reason !== "missing-counter" ||
      serverTickWindow({...base, intervalCount: "100"}, base).reason !== "missing-counter" ||
      serverTickWindow({...base, intervalCount: 300}, base).reason !== "counter-reset") {
    throw new Error("missing/reset server tick window self-smoke failed");
  }
  const stalled = serverTickWindow(base, {...base, sampledAtMillis: 11000});
  if (!stalled.available || stalled.observedTps !== 0 || stalled.elapsedMillis !== 5000) {
    throw new Error("unfinished long tick must remain in the wall-time TPS window");
  }
  const hitches = serverTickWindow({...base, intervalOver500MillisCount: 4,
    tickWorkOver500MillisCount: 2, waitPhaseOver500MillisCount: 3}, {
    ...base, sampledAtMillis: 11000, intervalCount: 200,
    intervalOver500MillisCount: 7, tickWorkOver500MillisCount: 3,
    waitPhaseOver500MillisCount: 1,
  });
  if (hitches.observedTps !== 20 || hitches.hitchCounts.intervalOver500MillisCount !== 3 ||
      hitches.hitchCounts.tickWorkOver500MillisCount !== 1 ||
      hitches.hitchCounts.waitPhaseOver500MillisCount !== null ||
      hitches.hitchCounts.intervalOver100MillisCount !== null) {
    throw new Error("average TPS must retain hitches and mark unavailable/reset counts null");
  }
  return {ok: true, twentyTps: true, sevenTps: true, missingUnavailable: true,
    resetRejected: true, hitchWindowDeltas: true};
}

function runTelemetrySnapshotSelfSmoke() {
  const oldWindow = {
    chunkPriorityStats: {window: "old"},
    networkStats: {window: "old"},
    worldgenStats: {window: "old"},
    serverTickStats: {window: "old"},
    storageStats: {window: "old"},
  };
  const stalePong = {
    sessionId: "stale-session",
    chunkPriority: {window: "stale"},
    network: {window: "stale"},
    worldgen: {window: "stale"},
    serverTick: {window: "stale"},
    storage: {window: "stale"},
  };
  const afterStale = updateLatestTelemetrySnapshots(
    oldWindow,
    stalePong,
    "active-session",
  );
  if (JSON.stringify(afterStale) !== JSON.stringify(oldWindow)) {
    throw new Error("stale telemetry pong polluted the latest snapshot");
  }
  const resetPong = {
    sessionId: "active-session",
    chunkPriority: {window: "reset"},
    network: {window: "reset"},
    worldgen: {window: "reset"},
    serverTick: {window: "reset"},
    storage: {window: "reset"},
  };
  const afterReset = updateLatestTelemetrySnapshots(
    afterStale,
    resetPong,
    "active-session",
  );
  for (const field of [
    "chunkPriorityStats",
    "networkStats",
    "worldgenStats",
    "serverTickStats",
    "storageStats",
  ]) {
    if (afterReset[field]?.window !== "reset") {
      throw new Error(`reset telemetry snapshot did not replace ${field}`);
    }
  }
  // Ensure the returned objects are detached before the Worker payload can be
  // reused or mutated by a later measurement window.
  resetPong.storage.window = "mutated-after-snapshot";
  if (afterReset.storageStats.window !== "reset") {
    throw new Error("telemetry snapshot retained a mutable pong reference");
  }
  return {
    ok: true,
    staleSessionIgnored: true,
    resetAuxiliarySnapshotsReplaced: true,
    snapshotObjectsDetached: true,
  };
}

function runNetworkValidationSelfSmoke() {
  const zeroStats = Object.fromEntries(
    requiredNetworkTaskTelemetryFields.map((field) => [field, 0]),
  );
  zeroStats.integratedServerTaskPending = 0;
  zeroStats.integratedServerInputPending = 0;
  const directMode = resolveCooperativePumpMode(zeroStats, false);
  if (directMode.requireActivity || directMode.active) {
    throw new Error("direct-executor self-smoke incorrectly requires pump activity");
  }
  const directValidation = validateNetworkTaskTelemetry(zeroStats, {
    requireActivity: directMode.requireActivity,
    requireDrained: true,
    requireHealthy: true,
  });
  if (!directValidation.valid) {
    throw new Error(
      `direct-executor self-smoke rejected zero activity: ${JSON.stringify(directValidation)}`,
    );
  }

  const activeStats = {...zeroStats,
    integratedServerPumpRequests: 1,
    integratedServerPumpStarts: 1,
    integratedServerTaskSignals: 1,
    integratedServerTaskUnparks: 1,
    integratedServerTaskSchedules: 1,
    integratedServerTaskRuns: 1,
  };
  const activeMode = resolveCooperativePumpMode(activeStats, false);
  if (!activeMode.active || !activeMode.requireActivity) {
    throw new Error("active cooperative-pump self-smoke did not require activity");
  }
  const activeValidation = validateNetworkTaskTelemetry(activeStats, {
    requireActivity: activeMode.requireActivity,
    requireDrained: true,
    requireHealthy: true,
  });
  if (!activeValidation.valid) {
    throw new Error(
      `active cooperative-pump self-smoke rejected valid activity: ${JSON.stringify(activeValidation)}`,
    );
  }

  const expectedMode = resolveCooperativePumpMode(zeroStats, true);
  if (!expectedMode.requireActivity || expectedMode.active) {
    throw new Error("expected cooperative-pump self-smoke did not require activity");
  }
  const expectedValidation = validateNetworkTaskTelemetry(zeroStats, {
    requireActivity: expectedMode.requireActivity,
    requireDrained: true,
    requireHealthy: true,
  });
  if (expectedValidation.valid ||
      !expectedValidation.relationshipErrors.includes("task schedules are zero") ||
      !expectedValidation.relationshipErrors.includes("integrated pump requests are zero")) {
    throw new Error(
      `expected cooperative-pump self-smoke failed to reject missing activity: ${JSON.stringify(expectedValidation)}`,
    );
  }

  const inFlightStats = {...zeroStats, integratedServerTaskRuns: 1};
  const inFlightValidation = validateNetworkTaskTelemetry(inFlightStats, {
    requireActivity: false,
    requireDrained: true,
    initialIntegratedServerTaskPending: 1,
    requireHealthy: true,
  });
  if (!inFlightValidation.valid || inFlightValidation.taskRunSkewAllowance !== 1) {
    throw new Error(
      `in-flight baseline self-smoke rejected the permitted run: ${JSON.stringify(inFlightValidation)}`,
    );
  }
  const overrunValidation = validateNetworkTaskTelemetry(
    {...inFlightStats, integratedServerTaskRuns: 2},
    {
      requireActivity: false,
      requireDrained: true,
      initialIntegratedServerTaskPending: 1,
      requireHealthy: true,
    },
  );
  if (overrunValidation.valid ||
      !overrunValidation.relationshipErrors.includes("task runs exceed task schedules")) {
    throw new Error("in-flight baseline self-smoke failed to reject a second run");
  }

  const failedDirectStats = {...zeroStats, errors: 1};
  const failedDirectValidation = validateNetworkTaskTelemetry(failedDirectStats, {
    requireActivity: false,
    requireDrained: true,
    requireHealthy: true,
  });
  if (failedDirectValidation.valid ||
      !failedDirectValidation.healthErrors.includes("network errors are non-zero")) {
    throw new Error("direct-executor self-smoke failed to reject a network error");
  }
  const queuedDirectStats = {...zeroStats, inboundQueuedBytes: 1};
  const queuedDirectValidation = validateNetworkTaskTelemetry(queuedDirectStats, {
    requireActivity: false,
    requireDrained: true,
    requireHealthy: true,
  });
  if (queuedDirectValidation.valid ||
      !queuedDirectValidation.relationshipErrors.includes("inbound queue is not drained")) {
    throw new Error("direct-executor self-smoke failed to reject queued input");
  }
  return {
    ok: true,
    directExecutorZeroActivity: true,
    activeCooperativePump: true,
    expectedCooperativePumpMissingActivity: true,
    inFlightRunAllowance: true,
    overrunStillRejected: true,
    directExecutorFailureStillRejected: true,
    directExecutorQueueStillRejected: true,
  };
}

// Keep the post-ready soak contract executable without starting a Worker.  In
// particular, a configured soak must not be satisfied by a CPU-profile timer
// or by the first readiness callback; the stop decision is only valid once the
// monotonic elapsed time reaches the requested duration.
function runPostReadySoakSelfSmoke() {
  const createModel = (configuredMs) => {
    let completionReadyAt = 0;
    let completedAt = 0;
    const markCompletionReady = (at) => {
      if (completionReadyAt !== 0) return;
      completionReadyAt = at;
      if (configuredMs === 0) completedAt = at;
    };
    const advance = (at) => {
      if (completionReadyAt === 0 || completedAt !== 0 ||
          at - completionReadyAt < configuredMs) {
        return;
      }
      completedAt = at;
    };
    return {
      markCompletionReady,
      advance,
      shouldStop: () => completedAt !== 0,
      elapsedMs: () => completedAt === 0 ? 0 : completedAt - completionReadyAt,
    };
  };

  const immediate = createModel(0);
  immediate.markCompletionReady(1000);
  if (!immediate.shouldStop() || immediate.elapsedMs() < 0) {
    throw new Error("post-ready soak zero-duration self-smoke did not complete immediately");
  }

  const delayed = createModel(15_000);
  delayed.markCompletionReady(1000);
  delayed.advance(15_999);
  if (delayed.shouldStop()) {
    throw new Error("post-ready soak self-smoke stopped before 15 seconds");
  }
  delayed.advance(16_000);
  if (!delayed.shouldStop() || delayed.elapsedMs() < 15_000) {
    throw new Error("post-ready soak self-smoke did not complete at its configured duration");
  }
  return {
    ok: true,
    zeroDurationCompletesImmediately: true,
    configuredDurationHonored: true,
    earlyStopRejected: true,
    elapsedMsAtCompletion: delayed.elapsedMs(),
  };
}

function runErrorSerializationSelfSmoke() {
  const cause = new Error("inner parser cause");
  const outer = new Error("structure load failed", {cause});
  const serialized = serializeError(outer);
  if (serialized.type !== "Error" || serialized.message !== "structure load failed" ||
      serialized.cause?.message !== "inner parser cause" ||
      typeof serialized.stack !== "string") {
    throw new Error("Error serialization lost non-enumerable fields or cause");
  }
  const cyclic = new Error("cyclic");
  Object.defineProperty(cyclic, "cause", {value: cyclic, enumerable: false});
  if (serializeError(cyclic).cause?.type !== "cycle") {
    throw new Error("Error serialization did not break a cyclic cause");
  }
  let deep = new Error("depth-0");
  for (let index = 1; index < MAX_SERIALIZED_ERROR_DEPTH + 3; index++) {
    deep = new Error(`depth-${index}`, {cause: deep});
  }
  const deepSerialized = serializeError(deep);
  let cursor = deepSerialized;
  for (let index = 0; index < MAX_SERIALIZED_ERROR_DEPTH - 1; index++) cursor = cursor.cause;
  if (cursor?.cause?.type !== "depth-limit") {
    throw new Error("Error serialization depth bound regressed");
  }
  const huge = new Error("x".repeat(MAX_SERIALIZED_ERROR_TEXT + 100));
  if (huge && serializeError(huge).message.length !== MAX_SERIALIZED_ERROR_TEXT) {
    throw new Error("Error serialization text bound regressed");
  }
  const hostile = new Proxy({}, {
    get() { throw new Error("hostile getter"); },
    has() { throw new Error("hostile has trap"); },
    getPrototypeOf() { throw new Error("hostile prototype trap"); },
  });
  if (isErrorLike(hostile) || serializeError(hostile) === null) {
    throw new Error("hostile error-like proxy escaped the bounded serializer");
  }
  return {ok: true, cause: true, cycleBounded: true, depthBounded: true, textBounded: true};
}

const runtimeSelfTest = isMainThread && process.env.GAIUS_SMOKE_SELF_TEST === "1";

if (runtimeSelfTest) {
  const {runProbeFixture} = await import("./singleplayer-worker-runtime-probe-fixture.mjs");
  const selfTestOutput = JSON.stringify({
    ...runNetworkValidationSelfSmoke(),
    telemetrySnapshots: runTelemetrySnapshotSelfSmoke(),
    serverTickWindow: runServerTickWindowSelfSmoke(),
    slowProbe: runSlowProbeSelfSmoke(),
    errorSerialization: runErrorSerializationSelfSmoke(),
    timeoutEvidence: runWorkerEventLoopEvidenceSelfSmoke(),
    postReadySoak: runPostReadySoakSelfSmoke(),
    miningProbe: runProbeFixture(),
  }) + "\n";
  try {
    await writeChunkAndDrain(process.stdout, selfTestOutput, {
      timeoutMs: FINAL_OUTPUT_WRITE_TIMEOUT_MS,
    });
    process.exitCode = 0;
  } catch (error) {
    try {
      process.stderr.write(`singleplayer-worker-runtime self-test output failed: ${
        error.stack || String(error)}\n`);
    } finally {
      process.exitCode = 1;
    }
  }
  process.exit(process.exitCode);
}

function networkDrainSignature(stats) {
  const validation = validateNetworkTaskTelemetry(stats);
  if (!validation.fieldComplete || validation.nonIntegerFields.length > 0 ||
      validation.negativeFields.length > 0) {
    return undefined;
  }
  return requiredNetworkTaskTelemetryFields.map((field) => stats[field]).join("/");
}

if (isMainThread && !runtimeSelfTest) {
  const smokeStartedAt = Date.now();
  const events = [];
  let finished = false;
  const skipMining = process.env.GAIUS_SMOKE_SKIP_MINING === "1";
  const serverTickTelemetryEnabled = process.env.GAIUS_SMOKE_SERVER_TICK_TELEMETRY === "1";
  const structurePreloadIds = process.env.GAIUS_SMOKE_STRUCTURE_PRELOAD_IDS
    ? JSON.parse(process.env.GAIUS_SMOKE_STRUCTURE_PRELOAD_IDS) : null;
  if (structurePreloadIds !== null && (!Array.isArray(structurePreloadIds) ||
      structurePreloadIds.some(id => typeof id !== "string"))) {
    throw new Error("GAIUS_SMOKE_STRUCTURE_PRELOAD_IDS must be a JSON string array");
  }
  const mobAiStress = process.env.GAIUS_SMOKE_MOB_AI_STRESS === "1";
  const stopAtFirstChunk = process.env.GAIUS_SMOKE_STOP_AT_FIRST_CHUNK === "1";
  const roamSteps = Number(process.env.GAIUS_SMOKE_ROAM_STEPS || "0");
  const roamStepBlocks = Number(process.env.GAIUS_SMOKE_ROAM_STEP_BLOCKS || "8");
  const roamTimeoutMs = Number(process.env.GAIUS_SMOKE_ROAM_TIMEOUT_MS || "30000");
  const roamSpectator = process.env.GAIUS_SMOKE_ROAM_SPECTATOR === "1";
  const requireBlockDrop = process.env.GAIUS_SMOKE_REQUIRE_BLOCK_DROP === "1";
  const jsonOnly = process.env.GAIUS_SMOKE_JSON_ONLY === "1";
  const traceEvents = process.env.GAIUS_SMOKE_TRACE_EVENTS === "1";
  const blockDropTimeoutMs = Number(
    process.env.GAIUS_SMOKE_BLOCK_DROP_TIMEOUT_MS || "5000",
  );
  const blockReboundWindowMs = Number(
    process.env.GAIUS_SMOKE_BLOCK_REBOUND_WINDOW_MS || "1000",
  );
  const blockActionHoldMs = Number(
    process.env.GAIUS_SMOKE_BLOCK_ACTION_HOLD_MS ||
      (requireBlockDrop ? "750" : "8000"),
  );
  const chunkBatchAckDelayMs = Number(
    process.env.GAIUS_SMOKE_CHUNK_BATCH_ACK_DELAY_MS || "250",
  );
  const chunkBatchDesiredRate = Number(
    process.env.GAIUS_SMOKE_CHUNK_BATCH_DESIRED_RATE || "10",
  );
  const cooperativePumpExpected = process.env.GAIUS_SMOKE_EXPECT_COOPERATIVE_PUMP === "1";
  const maximumGameplayStallMs = Number(
    process.env.GAIUS_SMOKE_MAX_GAMEPLAY_STALL_MS || "500",
  );
  const telemetryBarrierTimeoutMs = Number(
    process.env.GAIUS_SMOKE_TELEMETRY_BARRIER_TIMEOUT_MS || "1000",
  );
  const telemetryBarrierSampleCount = 2;
  const telemetryBarrierMaxAttempts = Number(
    process.env.GAIUS_SMOKE_TELEMETRY_BARRIER_MAX_ATTEMPTS || "20",
  );
  const telemetryBarrierSampleDelayMs = Number(
    process.env.GAIUS_SMOKE_TELEMETRY_BARRIER_SAMPLE_DELAY_MS || "25",
  );
  const distanceRampIntervalMillis = process.env.GAIUS_SMOKE_DISTANCE_RAMP_MS === undefined
    ? undefined
    : Number(process.env.GAIUS_SMOKE_DISTANCE_RAMP_MS);
  const configuredPostReadySoakMs = Number(
    process.env.GAIUS_SMOKE_POST_READY_SOAK_MS ?? "0",
  );
  const targetRenderDistance = Number(process.env.GAIUS_SMOKE_RENDER_DISTANCE || "7");
  const targetSimulationDistance = Number(process.env.GAIUS_SMOKE_SIMULATION_DISTANCE || "3");
  const cpuProfilePhase = process.env.GAIUS_SMOKE_CPU_PROFILE_PHASE || "";
  const cpuProfileDurationMs = Number(
    process.env.GAIUS_SMOKE_CPU_PROFILE_DURATION_MS || "15000",
  );
  const cpuProfilePath = nativePath(process.env.GAIUS_SMOKE_CPU_PROFILE_PATH) ||
    (buildDirectory + "/singleplayer-worker-" +
      cpuProfilePhase.replace(/[^a-z0-9._-]+/gi, "-") + ".cpuprofile");
  const coveragePhase = process.env.GAIUS_SMOKE_COVERAGE_PHASE || "";
  const coverageDurationMs = Number(
    process.env.GAIUS_SMOKE_COVERAGE_DURATION_MS || "10000",
  );
  const coveragePath = nativePath(process.env.GAIUS_SMOKE_COVERAGE_PATH) ||
    (buildDirectory + "/singleplayer-worker-" +
      coveragePhase.replace(/[^a-z0-9._-]+/gi, "-") + "-coverage.json");
  if (!Number.isFinite(maximumGameplayStallMs) || maximumGameplayStallMs <= 0) {
    throw new Error("GAIUS_SMOKE_MAX_GAMEPLAY_STALL_MS must be a positive number");
  }
  if (!Number.isFinite(telemetryBarrierTimeoutMs) || telemetryBarrierTimeoutMs <= 0) {
    throw new Error("GAIUS_SMOKE_TELEMETRY_BARRIER_TIMEOUT_MS must be a positive number");
  }
  if (!Number.isInteger(telemetryBarrierMaxAttempts) ||
      telemetryBarrierMaxAttempts < telemetryBarrierSampleCount) {
    throw new Error("GAIUS_SMOKE_TELEMETRY_BARRIER_MAX_ATTEMPTS must be at least 2");
  }
  if (!Number.isFinite(telemetryBarrierSampleDelayMs) ||
      telemetryBarrierSampleDelayMs < 0) {
    throw new Error("GAIUS_SMOKE_TELEMETRY_BARRIER_SAMPLE_DELAY_MS must be non-negative");
  }
  if (!Number.isFinite(blockDropTimeoutMs) || blockDropTimeoutMs <= 0) {
    throw new Error("GAIUS_SMOKE_BLOCK_DROP_TIMEOUT_MS must be a positive number");
  }
  if (!Number.isFinite(blockReboundWindowMs) || blockReboundWindowMs <= 0) {
    throw new Error("GAIUS_SMOKE_BLOCK_REBOUND_WINDOW_MS must be a positive number");
  }
  if (!Number.isFinite(roamTimeoutMs) || roamTimeoutMs <= 0) {
    throw new Error("GAIUS_SMOKE_ROAM_TIMEOUT_MS must be a positive number");
  }
  if (roamSpectator && !skipMining) {
    throw new Error("GAIUS_SMOKE_ROAM_SPECTATOR requires GAIUS_SMOKE_SKIP_MINING=1");
  }
  if (!Number.isFinite(blockActionHoldMs) || blockActionHoldMs <= 0) {
    throw new Error("GAIUS_SMOKE_BLOCK_ACTION_HOLD_MS must be a positive number");
  }
  if (!Number.isFinite(chunkBatchAckDelayMs) || chunkBatchAckDelayMs < 0) {
    throw new Error("GAIUS_SMOKE_CHUNK_BATCH_ACK_DELAY_MS must be zero or positive");
  }
  if (!Number.isFinite(chunkBatchDesiredRate) || chunkBatchDesiredRate <= 0) {
    throw new Error("GAIUS_SMOKE_CHUNK_BATCH_DESIRED_RATE must be a positive number");
  }
  if (distanceRampIntervalMillis !== undefined &&
      (!Number.isFinite(distanceRampIntervalMillis) ||
        distanceRampIntervalMillis < 100 || distanceRampIntervalMillis > 2000)) {
    throw new Error("GAIUS_SMOKE_DISTANCE_RAMP_MS must be between 100 and 2000");
  }
  if (!Number.isFinite(configuredPostReadySoakMs) ||
      configuredPostReadySoakMs < 0 || configuredPostReadySoakMs > 300000) {
    throw new Error(
      "GAIUS_SMOKE_POST_READY_SOAK_MS must be a finite number between 0 and 300000",
    );
  }
  if (!Number.isInteger(targetRenderDistance) || targetRenderDistance < 2 ||
      targetRenderDistance > 32 || !Number.isInteger(targetSimulationDistance) ||
      targetSimulationDistance < 2 || targetSimulationDistance > 32) {
    throw new Error("Smoke render and simulation distances must be integers between 2 and 32");
  }
  if (cpuProfilePhase &&
      (!Number.isFinite(cpuProfileDurationMs) || cpuProfileDurationMs <= 0)) {
    throw new Error("GAIUS_SMOKE_CPU_PROFILE_DURATION_MS must be a positive number");
  }
  if (coveragePhase &&
      (!Number.isFinite(coverageDurationMs) || coverageDurationMs <= 0)) {
    throw new Error("GAIUS_SMOKE_COVERAGE_DURATION_MS must be a positive number");
  }
  const configuredDistanceRampIntervalMillis = distanceRampIntervalMillis === undefined
    ? 750
    : Math.round(distanceRampIntervalMillis);
  const worker = new Worker(new URL(import.meta.url), {workerData: {runtime: true}});
  const {port1, port2} = new MessageChannel();
  const sessionId = "0123456789abcdef0123456789abcdef";
  const clientProfileId = "00000000000040008000000000000002";
  // Login installs the requested pair directly. ACKs remain accounting only; no synthetic
  // 1/1 staging or distance ramp is expected in the Worker event stream.
  const expectedDistances = `${targetRenderDistance}/${targetSimulationDistance}`;
  const expectedTransitions = [expectedDistances];
  const expectedDistanceRamp = [];
  const distanceRamp = [];
  const distanceTransitionTimeline = [];
  const protocol = createProtocolClient(port2, sessionId, clientProfileId, {
    skipMining,
    roamSteps,
    roamStepBlocks,
    roamTimeoutMs,
    roamSpectator,
    requireBlockDrop,
    blockDropTimeoutMs,
    blockReboundWindowMs,
    blockActionHoldMs,
    chunkBatchAckDelayMs,
    chunkBatchDesiredRate,
    onRoamPhase(detail) {
      const at = Date.now();
      const nextPhase = "roam-" + detail;
      const stopCpuProfile = cpuProfileActive &&
        (workerPhase === cpuProfilePhase ||
          (cpuProfilePhase === "roam" && nextPhase === "roam-complete"));
      if (stopCpuProfile) {
        worker.postMessage({type: "node-cpu-profile-stop"});
        cpuProfileActive = false;
      }
      workerPhase = nextPhase;
      const startCpuProfile = !cpuProfileActive &&
        (workerPhase === cpuProfilePhase ||
          (cpuProfilePhase === "roam" && /^roam-1\//.test(workerPhase)));
      if (startCpuProfile) {
        worker.postMessage({
          type: "node-cpu-profile-start",
          phase: cpuProfilePhase,
          path: cpuProfilePath,
        });
        cpuProfileActive = true;
      }
      events.push({type: "roam-phase", detail, at, afterSmokeMs: at - smokeStartedAt});
    },
  });
  let protocolReady = false;
  let distanceSyncReady = false;
  let configuredDistanceReady = false;
  let mobAiStressScheduled = false;
  const scheduleMobAiStress = () => {
    if (!mobAiStress || mobAiStressScheduled) return;
    mobAiStressScheduled = true;
    const mobCommands = [
      "time set day",
      "summon minecraft:cow ~2 ~ ~",
      "summon minecraft:pig ~4 ~ ~",
      "summon minecraft:sheep ~6 ~ ~",
      "summon minecraft:chicken ~8 ~ ~",
      "summon minecraft:cow ~2 ~ ~2",
      "summon minecraft:pig ~4 ~ ~2",
      "summon minecraft:sheep ~6 ~ ~2",
      "summon minecraft:chicken ~8 ~ ~2",
      "summon minecraft:cow ~2 ~ ~4",
      "summon minecraft:pig ~4 ~ ~4",
      "summon minecraft:sheep ~6 ~ ~4",
      "summon minecraft:chicken ~8 ~ ~4",
    ];
    for (const command of mobCommands) protocol.sendChatCommand(command);
    events.push({type: "mob-ai-stress-scheduled", commands: mobCommands});
  };
  let regionStorageWrites = 0;
  let nonEmptyRegionStorageWrites = 0;
  let eventLoopProbeId = 0;
  const eventLoopProbeStartedAt = new Map();
  const eventLoopProbeLatenciesMs = [];
  const eventLoopProbeSamples = [];
  const slowProbeSamplesBeforeProtocolReady = [];
  const slowProbeSamplesAfterProtocolReady = [];
  const slowProbeSamplesGlobal = [];
  const slowProbeSnapshotBlocksBeforeProtocolReady = new Map();
  const slowProbeSnapshotBlocksAfterProtocolReady = new Map();
  const slowProbeSnapshotBlocksById = new Map();
  const slowProbeClockAnomalies = [];
  let slowProbeCandidateCount = 0;
  let slowProbeTopKRetentionDroppedCount = 0;
  let slowProbeClockAnomalyCount = 0;
  let slowProbeSnapshotBlockCapDroppedCount = 0;
  let slowProbeSnapshotErrorCount = 0;
  let longestEventLoopProbe = {latencyMs: 0, startedAt: 0, completedAt: 0};
  let longestGameplayEventLoopProbe = {
    latencyMs: 0,
    startedAt: 0,
    completedAt: 0,
    phase: "",
  };
  let latestChunkPriorityStats = null;
  let latestNetworkStats = null;
  let latestNetworkStatsMeta = null;
  let latestWorldgenStats = null;
  let latestServerTickStats = null;
  let latestServerTickWindowEnd = null;
  let serverTickWindowStart = null;
  let latestStorageStats = null;
  let lastWorldgenTraceAt = 0;
  const snapshotFromSlowProbeSample = (sample) => ({
    worldgen: sample.worldgen,
    network: sample.network,
    storage: sample.storage,
    scheduler: sample.scheduler,
    gc: sample.gc,
  });
  const rememberSlowProbeSnapshot = (scopeMap, sample) => {
    const blockId = normalizeSlowBlockId(sample.slowBlockId);
    if (blockId === 0 || !sample.snapshotCaptured) return;
    const needScopeSnapshot = !scopeMap.has(blockId) &&
      scopeMap.size < MAX_SLOW_SAMPLES_PER_SCOPE;
    const needGlobalSnapshot = !slowProbeSnapshotBlocksById.has(blockId) &&
      slowProbeSnapshotBlocksById.size < MAX_SLOW_SAMPLES;
    if (!needScopeSnapshot && !needGlobalSnapshot) return;
    const snapshot = snapshotFromSlowProbeSample(sample);
    if (needScopeSnapshot) {
      scopeMap.set(blockId, snapshot);
    }
    if (needGlobalSnapshot) {
      slowProbeSnapshotBlocksById.set(blockId, snapshot);
    }
  };
  let workerExited = false;
  let stopFlowStarted = false;
  let stoppedReceived = false;
  let stoppedFinalizationStarted = false;
  let telemetrySequence = 0;
  const pendingTelemetryPongs = new Map();
  let preStopTelemetryBarrier = null;
  let postStopTelemetryBarrier = null;
  let serverCreatedAt = 0;
  let protocolReadyAt = 0;
  let completionReadyAt = 0;
  let postReadySoakStartedMonoMs = 0;
  let postReadySoakCompletedAt = 0;
  let postReadySoakCompletedMonoMs = 0;
  let postReadySoakTimer = 0;
  let workerPhase = "startup";
  let cpuProfileActive = false;
  let cpuProfileWritten = !cpuProfilePhase;
  let cpuProfileStopTimer = 0;
  const cpuProfileCompletion = deferred();
  if (!cpuProfilePhase) {
    cpuProfileCompletion.resolve();
  }
  let coverageActive = false;
  let coverageWritten = !coveragePhase;
  let coverageStopTimer = 0;
  const startTimedCpuProfile = (phase) => {
    if (cpuProfileActive || cpuProfileWritten || cpuProfilePhase !== phase) {
      return;
    }
    worker.postMessage({
      type: "node-cpu-profile-start",
      phase: cpuProfilePhase,
      path: cpuProfilePath,
    });
    cpuProfileActive = true;
    cpuProfileStopTimer = setTimeout(() => {
      cpuProfileStopTimer = 0;
      if (!cpuProfileActive) return;
      worker.postMessage({type: "node-cpu-profile-stop"});
      cpuProfileActive = false;
    }, cpuProfileDurationMs);
  };
  const startTimedCoverage = (phase) => {
    if (coverageActive || coverageWritten || coveragePhase !== phase) {
      return;
    }
    worker.postMessage({
      type: "node-coverage-start",
      phase: coveragePhase,
      path: coveragePath,
    });
    coverageActive = true;
    coverageStopTimer = setTimeout(() => {
      coverageStopTimer = 0;
      if (!coverageActive) return;
      worker.postMessage({type: "node-coverage-stop"});
      coverageActive = false;
    }, coverageDurationMs);
  };
  const eventLoopProbeInterval = setInterval(() => {
    const probeId = ++eventLoopProbeId;
    const parentSendEpochMs = highResolutionEpochMillis();
    const parentSendMonoMs = performance.now();
    const probe = {
      probeId,
      startedAt: parentSendEpochMs,
      phase: workerPhase,
      phaseAtSend: workerPhase,
      parentSendEpochMs,
      parentSendMonoMs,
    };
    eventLoopProbeStartedAt.set(probeId, probe);
    worker.postMessage({
      type: "node-event-loop-probe",
      probeId,
      phaseAtSend: probe.phaseAtSend,
      afterProtocolReady: protocolReadyAt > 0 && parentSendEpochMs >= protocolReadyAt,
      parentSendEpochMs,
      parentSendMonoMs,
      slowSampleSchemaVersion: SLOW_SAMPLE_SCHEMA_VERSION,
      slowSampleSchema: SLOW_SAMPLE_SCHEMA,
      slowSampleThresholdMs: SLOW_SAMPLE_THRESHOLD_MS,
    });
  }, 100);
  const requestTelemetryPong = (stage) => {
    if (finished || workerExited) {
      return Promise.resolve({
        available: false,
        source: "telemetry-pong",
        stage,
        reason: workerExited ? "worker-exited" : "smoke-finished",
      });
    }
    const sequence = ++telemetrySequence;
    const wait = deferred();
    const pending = {
      stage,
      resolve: wait.resolve,
      timer: 0,
      sentAt: Date.now(),
    };
    pendingTelemetryPongs.set(sequence, pending);
    const settle = (result) => {
      if (pendingTelemetryPongs.get(sequence) !== pending) {
        return;
      }
      pendingTelemetryPongs.delete(sequence);
      if (pending.timer) clearTimeout(pending.timer);
      wait.resolve(result);
    };
    pending.timer = setTimeout(() => {
      settle({
        available: false,
        source: "telemetry-pong",
        stage,
        sequence,
        reason: "timeout",
      });
    }, telemetryBarrierTimeoutMs);
    try {
      worker.postMessage({type: "telemetry-ping", sessionId, sequence});
    } catch (error) {
      settle({
        available: false,
        source: "telemetry-pong",
        stage,
        sequence,
        reason: String(error && (error.stack || error.message) || error),
      });
    }
    return wait.promise;
  };
  const requestNodeTelemetryPong = (stage) => {
    if (finished || workerExited) {
      return Promise.resolve({
        available: false,
        source: "node-event-loop-pong",
        stage,
        reason: workerExited ? "worker-exited" : "smoke-finished",
      });
    }
    const probeId = ++eventLoopProbeId;
    const wait = deferred();
    const parentSendEpochMs = highResolutionEpochMillis();
    const parentSendMonoMs = performance.now();
    const probe = {
      probeId,
      startedAt: parentSendEpochMs,
      phase: "telemetry-barrier-" + stage,
      phaseAtSend: "telemetry-barrier-" + stage,
      parentSendEpochMs,
      parentSendMonoMs,
      barrier: wait,
      barrierTimer: 0,
    };
    eventLoopProbeStartedAt.set(probeId, probe);
    const settle = (result) => {
      if (eventLoopProbeStartedAt.get(probeId) !== probe) {
        return;
      }
      eventLoopProbeStartedAt.delete(probeId);
      if (probe.barrierTimer) clearTimeout(probe.barrierTimer);
      wait.resolve(result);
    };
    probe.barrierTimer = setTimeout(() => {
      settle({
        available: false,
        source: "node-event-loop-pong",
        stage,
        probeId,
        reason: "timeout",
      });
    }, telemetryBarrierTimeoutMs);
    try {
      worker.postMessage({
        type: "node-event-loop-probe",
        probeId,
        phaseAtSend: probe.phaseAtSend,
        afterProtocolReady: protocolReadyAt > 0 && parentSendEpochMs >= protocolReadyAt,
        parentSendEpochMs,
        parentSendMonoMs,
        slowSampleSchemaVersion: SLOW_SAMPLE_SCHEMA_VERSION,
        slowSampleSchema: SLOW_SAMPLE_SCHEMA,
        slowSampleThresholdMs: SLOW_SAMPLE_THRESHOLD_MS,
      });
    } catch (error) {
      settle({
        available: false,
        source: "node-event-loop-pong",
        stage,
        probeId,
        reason: String(error && (error.stack || error.message) || error),
      });
    }
    return wait.promise;
  };
  const makeNetworkTelemetryBarrier = (
    stage,
    source,
    samples,
    directResponseCount,
    fallbackUsed,
    baselineNetworkStats = null,
  ) => {
    const baselineTaskPending = baselineNetworkStats !== null &&
      baselineNetworkStats.integratedServerTaskPending === 1;
    const boundarySample = samples.find((sample) =>
      sample.available && sample.networkStats !== null);
    const boundarySampleTaskPending = boundarySample !== undefined &&
      boundarySample.networkStats.integratedServerTaskPending === 1;
    const initialIntegratedServerTaskPending = baselineTaskPending ||
      boundarySampleTaskPending ? 1 : 0;
    const availableSamples = samples.filter((sample) => sample.available);
    const stableSamples = samples.slice(-telemetryBarrierSampleCount);
    const completeStableSamples = stableSamples.filter((sample) =>
      sample.available && validateNetworkTaskTelemetry(sample.networkStats).fieldComplete);
    const signatures = completeStableSamples
      .map((sample) => networkDrainSignature(sample.networkStats));
    const stable = completeStableSamples.length === telemetryBarrierSampleCount &&
      signatures.length === telemetryBarrierSampleCount &&
      signatures.every((signature) => signature !== undefined && signature === signatures[0]);
    const selectedSample = [...availableSamples].reverse().find((sample) =>
      sample.networkStats !== null);
    const selectedValidation = validateNetworkTaskTelemetry(
      selectedSample === undefined ? null : selectedSample.networkStats,
      {
        requireDrained: true,
        initialIntegratedServerTaskPending,
      },
    );
    const drained = selectedValidation.fieldComplete &&
      selectedValidation.nonIntegerFields.length === 0 &&
      selectedValidation.negativeFields.length === 0 &&
      selectedValidation.relationshipErrors.length === 0;
    return {
      stage,
      source,
      directResponseCount,
      fallbackUsed,
      initialIntegratedServerTaskPending,
      available: availableSamples.length > 0,
      stable,
      drained,
      attempts: samples.length,
      samples: samples.map((sample) => {
        const validation = validateNetworkTaskTelemetry(sample.networkStats);
        return {
          source: sample.source,
          stage: sample.stage,
          sequence: sample.sequence,
          probeId: sample.probeId,
          available: sample.available,
          reason: sample.reason,
          receivedAt: sample.receivedAt,
          fieldComplete: validation.fieldComplete,
          missingFields: validation.missingFields,
          nonFiniteFields: validation.nonFiniteFields,
          drainSignature: networkDrainSignature(sample.networkStats),
        };
      }),
      networkStats: selectedSample === undefined
        ? null
        : copyObjectSnapshot(selectedSample.networkStats),
      // Auxiliary telemetry is diagnostic only.  Keep stability and drain
      // decisions network-only, but return the newest detached snapshots so
      // reset-window callers cannot fall back to an older cached window.
      chunkPriorityStats: recentTelemetryAuxiliarySnapshot(
        samples,
        "chunkPriorityStats",
        latestChunkPriorityStats,
      ),
      worldgenStats: recentTelemetryAuxiliarySnapshot(
        samples,
        "worldgenStats",
        latestWorldgenStats,
      ),
      serverTickStats: recentTelemetryAuxiliarySnapshot(
        samples,
        "serverTickStats",
        latestServerTickStats,
      ),
      storageStats: recentTelemetryAuxiliarySnapshot(
        samples,
        "storageStats",
        latestStorageStats,
      ),
    };
  };
  const waitForTelemetrySample = () => new Promise((resolve) => {
    setTimeout(resolve, telemetryBarrierSampleDelayMs);
  });
  const requestNetworkTelemetryBarrier = async (stage) => {
    // Capture the raw cumulative gauge before the first telemetry pong resets
    // the worker's measurement baseline.  The pong counters are then deltas,
    // so this preserves whether one task was already in flight at the window
    // boundary without weakening ordinary counter relationships.  The first
    // pong's raw pending gauge is also retained because a task can be queued
    // between the last event-loop probe and the baseline reset.
    const baselineNetworkStats = copyObjectSnapshot(latestNetworkStats);
    const directSamples = [];
    for (let index = 0; index < telemetryBarrierMaxAttempts; index++) {
      const sample = await requestTelemetryPong(stage + "-direct-" + (index + 1));
      if (!sample.available) {
        break;
      }
      sample.receivedAt = Date.now();
      directSamples.push(sample);
      if (!validateNetworkTaskTelemetry(sample.networkStats).fieldComplete) {
        break;
      }
      const barrier = makeNetworkTelemetryBarrier(
        stage,
        "telemetry-pong",
        directSamples,
        directSamples.length,
        false,
        baselineNetworkStats,
      );
      if (barrier.stable && barrier.drained) {
        return barrier;
      }
      if (index + 1 < telemetryBarrierMaxAttempts) {
        await waitForTelemetrySample();
      }
    }
    const directComplete = directSamples.length > 0 &&
      directSamples.every((sample) =>
        validateNetworkTaskTelemetry(sample.networkStats).fieldComplete);
    if (directComplete) {
      return makeNetworkTelemetryBarrier(
        stage,
        "telemetry-pong",
        directSamples,
        directSamples.length,
        false,
        baselineNetworkStats,
      );
    }
    const nodeSamples = [];
    for (let index = 0; index < telemetryBarrierMaxAttempts; index++) {
      const sample = await requestNodeTelemetryPong(stage + "-node-" + (index + 1));
      if (!sample.available) {
        break;
      }
      sample.receivedAt = Date.now();
      nodeSamples.push(sample);
      const barrier = makeNetworkTelemetryBarrier(
        stage,
        "node-event-loop-pong",
        nodeSamples,
        directSamples.length,
        true,
        baselineNetworkStats,
      );
      if (barrier.stable && barrier.drained) {
        return barrier;
      }
      if (index + 1 < telemetryBarrierMaxAttempts) {
        await waitForTelemetrySample();
      }
    }
    if (nodeSamples.length > 0) {
      return makeNetworkTelemetryBarrier(
        stage,
        "node-event-loop-pong",
        nodeSamples,
        directSamples.length,
        true,
        baselineNetworkStats,
      );
    }
    return makeNetworkTelemetryBarrier(
      stage,
      "telemetry-pong",
      directSamples,
      directSamples.length,
      false,
      baselineNetworkStats,
    );
  };
  const postReadySoakEvidence = () => {
    const elapsedMs = completionReadyAt === 0
      ? 0
      : roundedMillis(
        (postReadySoakCompletedMonoMs || performance.now()) -
          postReadySoakStartedMonoMs,
      );
    return {
      configuredMs: configuredPostReadySoakMs,
      elapsedMs,
      protocolReadyAt,
      completionReadyAt,
      completedAt: postReadySoakCompletedAt,
      completed: postReadySoakCompletedAt !== 0,
    };
  };
  const completePostReadySoak = () => {
    if (completionReadyAt === 0 || postReadySoakCompletedAt !== 0) {
      return;
    }
    postReadySoakCompletedMonoMs = performance.now();
    postReadySoakCompletedAt = Date.now();
    events.push({
      type: "post-ready-soak-complete",
      ...postReadySoakEvidence(),
    });
  };
  const isCompletionReady = () => protocolReady &&
    (stopAtFirstChunk || (distanceSyncReady && configuredDistanceReady));
  const armPostReadySoak = () => {
    if (!isCompletionReady() || completionReadyAt !== 0 ||
        finished || stopFlowStarted) {
      return;
    }
    completionReadyAt = Date.now();
    postReadySoakStartedMonoMs = performance.now();
    events.push({
      type: "post-ready-soak-start",
      ...postReadySoakEvidence(),
    });
    if (configuredPostReadySoakMs === 0) {
      completePostReadySoak();
      return;
    }
    const waitForPostReadySoak = () => {
      postReadySoakTimer = 0;
      if (finished || stopFlowStarted) return;
      const remainingMs = configuredPostReadySoakMs -
        (performance.now() - postReadySoakStartedMonoMs);
      if (remainingMs > 0) {
        postReadySoakTimer = setTimeout(waitForPostReadySoak, remainingMs);
        return;
      }
      completePostReadySoak();
      maybeStop();
    };
    postReadySoakTimer = setTimeout(
      waitForPostReadySoak,
      configuredPostReadySoakMs,
    );
  };
  const finishCpuProfileBeforeShutdown = async () => {
    if (!cpuProfilePhase || cpuProfileWritten) {
      return;
    }
    if (cpuProfileActive) {
      if (cpuProfileStopTimer) {
        clearTimeout(cpuProfileStopTimer);
        cpuProfileStopTimer = 0;
      }
      worker.postMessage({type: "node-cpu-profile-stop"});
      cpuProfileActive = false;
    }
    let timeoutId;
    try {
      await Promise.race([
        cpuProfileCompletion.promise,
        new Promise((resolve, reject) => {
          timeoutId = setTimeout(
            () => reject(new Error("Timed out writing the Worker CPU profile")),
            5000,
          );
        }),
      ]);
    } finally {
      if (timeoutId) clearTimeout(timeoutId);
    }
  };
  const beginStop = async () => {
    clearInterval(eventLoopProbeInterval);
    workerPhase = "telemetry-pre-stop";
    preStopTelemetryBarrier = await requestNetworkTelemetryBarrier("pre-stop");
    if (preStopTelemetryBarrier.networkStats !== null) {
      latestNetworkStats = copyObjectSnapshot(preStopTelemetryBarrier.networkStats);
    }
    latestChunkPriorityStats = copyObjectSnapshot(
      preStopTelemetryBarrier.chunkPriorityStats,
    );
    latestWorldgenStats = copyObjectSnapshot(preStopTelemetryBarrier.worldgenStats);
    latestServerTickStats = copyObjectSnapshot(preStopTelemetryBarrier.serverTickStats);
    latestStorageStats = copyObjectSnapshot(preStopTelemetryBarrier.storageStats);
    await finishCpuProfileBeforeShutdown();
    if (finished) {
      return;
    }
    workerPhase = "stopping";
    protocol.closeTransport();
    worker.postMessage({type: "stop"});
  };
  const maybeStop = () => {
    if (finished || stopFlowStarted || !isCompletionReady()) {
      return;
    }
    armPostReadySoak();
    if (postReadySoakCompletedAt === 0) return;
    stopFlowStarted = true;
    if (traceEvents) {
      process.stderr.write(`[smoke-event] stop-requested phase=${workerPhase}\n`);
    }
    void beginStop().catch((error) => {
      events.push({
        type: "final-telemetry-barrier-error",
        detail: error.stack || String(error),
        postReadySoak: postReadySoakEvidence(),
      });
      clearTimeout(timeout);
      finish(1);
    });
  };
  let finishPromise;
  const finish = (code) => {
    if (finishPromise !== undefined) {
      return finishPromise;
    }
    finished = true;
    clearInterval(eventLoopProbeInterval);
    if (cpuProfileStopTimer) clearTimeout(cpuProfileStopTimer);
    if (coverageStopTimer) clearTimeout(coverageStopTimer);
    if (postReadySoakTimer) {
      clearTimeout(postReadySoakTimer);
      postReadySoakTimer = 0;
    }
    finishPromise = (async () => {
      let finalOutputFailed = false;
      try {
        const finalJson = JSON.stringify({events}, null, 2) + "\n";
        await writeChunkAndDrain(process.stdout, finalJson, {
          timeoutMs: FINAL_OUTPUT_WRITE_TIMEOUT_MS,
        });
      } catch (error) {
        finalOutputFailed = true;
        // Promote a nominal success when the final record failed; existing
        // nonzero smoke reasons remain unchanged.
        try {
          process.stderr.write(`singleplayer-worker-runtime final output failed: ${
            error.stack || String(error)}\n`);
        } catch {
          // stderr can itself be closed during an abnormal shutdown.
        }
      }
      const finalExitCode = effectiveExitCode(code, finalOutputFailed);
      let terminationTimer;
      try {
        await Promise.race([
          Promise.resolve().then(() => worker.terminate()),
          new Promise((resolve) => {
            terminationTimer = setTimeout(resolve, FINAL_OUTPUT_WRITE_TIMEOUT_MS);
          }),
        ]);
      } catch (error) {
        try {
          process.stderr.write(`singleplayer-worker-runtime worker termination failed: ${
            error.stack || String(error)}\n`);
        } catch {
          // stderr can itself be closed during an abnormal shutdown.
        }
      } finally {
        if (terminationTimer !== undefined) clearTimeout(terminationTimer);
      }
      process.exitCode = finalExitCode;
      process.exit(finalExitCode);
    })();
    return finishPromise;
  };
  const timeoutMs = Number(process.env.GAIUS_SMOKE_TIMEOUT_MS || "240000");
  const timeout = setTimeout(() => {
    const timeoutEventLoopEvidence = buildWorkerEventLoopEvidenceSnapshot({
      eventLoopProbeLatenciesMs,
      eventLoopProbeSamples,
      slowProbeSamplesBeforeProtocolReady,
      slowProbeSamplesAfterProtocolReady,
      slowProbeSamplesGlobal,
      slowProbeCandidateCount,
      slowProbeTopKRetentionDroppedCount,
      slowProbeSnapshotBlockCapDroppedCount,
      slowProbeSnapshotBlocksBeforeProtocolReady,
      slowProbeSnapshotBlocksAfterProtocolReady,
      slowProbeSnapshotErrorCount,
      slowProbeClockAnomalyCount,
      slowProbeClockAnomalies,
      longestEventLoopProbe,
      longestGameplayEventLoopProbe,
      smokeStartedAt,
      serverCreatedAt,
      protocolReadyAt,
      cooperativePumpMode: resolveCooperativePumpMode(
        latestNetworkStats,
        cooperativePumpExpected,
      ),
      currentWorkerPhase: workerPhase,
      pendingProbeCount: eventLoopProbeStartedAt.size,
    });
    // Preserve the existing typed event shape for consumers that locate the
    // event-loop aggregate by type, while keeping protocol-timeout as the
    // final event for result readers that use events[-1]/finalEventType.
    events.push(timeoutEventLoopEvidence.workerEventLoopLatency);
    events.push({
      type: "protocol-timeout",
      ...protocol.snapshot(),
      workerPhase,
      currentWorkerPhase: workerPhase,
      protocolReady: protocolReadyAt > 0,
      protocolReadyAt,
      postReadySoak: postReadySoakEvidence(),
      // A timed-out Worker may no longer answer a telemetry barrier. Preserve
      // the last received snapshots without delaying termination or presenting
      // them as freshly sampled at the timeout instant.
      auxiliaryTelemetrySource: "last-received-before-timeout",
      chunkPriorityStats: copyObjectSnapshot(latestChunkPriorityStats),
      networkStats: copyObjectSnapshot(latestNetworkStats),
      networkStatsMeta: copyObjectSnapshot(latestNetworkStatsMeta),
      worldgenStats: copyObjectSnapshot(latestWorldgenStats),
      storageStats: copyObjectSnapshot(latestStorageStats),
      ...(serverTickTelemetryEnabled ? {
        serverTickStats: latestServerTickStats,
        serverTickWindow: serverTickWindow(serverTickWindowStart, latestServerTickWindowEnd),
      } : {}),
      slowProbeEvidence: timeoutEventLoopEvidence.slowProbeEvidence,
      workerEventLoopLatency: timeoutEventLoopEvidence.workerEventLoopLatency,
    });
    finish(2);
  }, timeoutMs);
  const finalizeStopped = async (stoppedMessage) => {
    if (stoppedFinalizationStarted) {
      return;
    }
    stoppedFinalizationStarted = true;
    postStopTelemetryBarrier = await requestNetworkTelemetryBarrier("post-stopped");
    let finalNetworkStats = latestNetworkStats;
    let finalNetworkSource = "last-node-event-loop-pong";
    if (preStopTelemetryBarrier !== null &&
        preStopTelemetryBarrier.networkStats !== null) {
      finalNetworkStats = copyObjectSnapshot(preStopTelemetryBarrier.networkStats);
      finalNetworkSource = "pre-stop-barrier";
    } else if (postStopTelemetryBarrier.networkStats !== null) {
      finalNetworkStats = copyObjectSnapshot(postStopTelemetryBarrier.networkStats);
      finalNetworkSource = "post-stopped-barrier-pre-stop-unavailable";
    }
    const finalAuxiliaryBarrier = preStopTelemetryBarrier !== null
      ? preStopTelemetryBarrier
      : postStopTelemetryBarrier;
    if (finalAuxiliaryBarrier !== null) {
      latestChunkPriorityStats = copyObjectSnapshot(
        finalAuxiliaryBarrier.chunkPriorityStats,
      );
      latestWorldgenStats = copyObjectSnapshot(finalAuxiliaryBarrier.worldgenStats);
      latestStorageStats = copyObjectSnapshot(finalAuxiliaryBarrier.storageStats);
    }
    latestNetworkStats = finalNetworkStats === null
      ? null
      : copyObjectSnapshot(finalNetworkStats);
    const cooperativePumpMode = resolveCooperativePumpMode(
      finalNetworkStats,
      cooperativePumpExpected,
    );
    const selectedBarrier = preStopTelemetryBarrier !== null &&
        preStopTelemetryBarrier.networkStats !== null
      ? preStopTelemetryBarrier
      : postStopTelemetryBarrier;
    const networkValidation = validateNetworkTaskTelemetry(finalNetworkStats, {
      requireActivity: cooperativePumpMode.requireActivity,
      requireDrained: true,
      initialIntegratedServerTaskPending: selectedBarrier === null
        ? 0
        : selectedBarrier.initialIntegratedServerTaskPending,
      requireHealthy: true,
    });
    networkValidation.cooperativePumpMode = cooperativePumpMode;
    if (!selectedBarrier || !selectedBarrier.stable) {
      networkValidation.healthErrors.push("final network telemetry barrier was not stable");
      networkValidation.valid = false;
    }
    if (preStopTelemetryBarrier === null || !preStopTelemetryBarrier.stable) {
      networkValidation.healthErrors.push("pre-stop network telemetry barrier was not stable");
      networkValidation.valid = false;
    }
    const protocolFinalEvent = {
      type: "protocol-final",
      stoppedDetail: stoppedMessage.detail,
      distanceRampIntervalMillis: configuredDistanceRampIntervalMillis,
      distanceTransitionTimeline: distanceTransitionTimeline.slice(),
      postReadySoak: postReadySoakEvidence(),
      ...protocol.snapshot(),
      chunkPriorityStats: latestChunkPriorityStats,
      networkStats: finalNetworkStats,
      networkSource: finalNetworkSource,
      networkValidation,
      telemetryBarriers: {
        preStop: preStopTelemetryBarrier,
        postStopped: postStopTelemetryBarrier,
      },
      worldgenStats: latestWorldgenStats,
      ...(serverTickTelemetryEnabled ? {
        serverTickStats: latestServerTickStats,
        serverTickWindow: serverTickWindow(serverTickWindowStart, latestServerTickWindowEnd),
      } : {}),
      storageStats: latestStorageStats,
    };
    events.push(protocolFinalEvent);
    const workerEventLoopEvidence = buildWorkerEventLoopEvidenceSnapshot({
      eventLoopProbeLatenciesMs,
      eventLoopProbeSamples,
      slowProbeSamplesBeforeProtocolReady,
      slowProbeSamplesAfterProtocolReady,
      slowProbeSamplesGlobal,
      slowProbeCandidateCount,
      slowProbeTopKRetentionDroppedCount,
      slowProbeSnapshotBlockCapDroppedCount,
      slowProbeSnapshotBlocksBeforeProtocolReady,
      slowProbeSnapshotBlocksAfterProtocolReady,
      slowProbeSnapshotErrorCount,
      slowProbeClockAnomalyCount,
      slowProbeClockAnomalies,
      longestEventLoopProbe,
      longestGameplayEventLoopProbe,
      smokeStartedAt,
      serverCreatedAt,
      protocolReadyAt,
      cooperativePumpMode,
      currentWorkerPhase: workerPhase,
      pendingProbeCount: eventLoopProbeStartedAt.size,
    });
    const {slowProbeEvidence, workerEventLoopLatency} = workerEventLoopEvidence;
    protocolFinalEvent.slowProbeEvidence = slowProbeEvidence;
    events.push(workerEventLoopLatency);
    const {stallValidation} = workerEventLoopLatency;
    if (stallValidation.maxMs > maximumGameplayStallMs) {
      events.push(buildWorldgenEventLoopStallEvent({
        workerEventLoopLatency,
        maximumGameplayStallMs,
        slowProbeEvidence,
      }));
      clearTimeout(timeout);
      finish(1);
      return;
    }
    if (!networkValidation.valid) {
      events.push({
        type: "network-state-mismatch",
        networkStats: finalNetworkStats,
        networkSource: finalNetworkSource,
        networkValidation,
      });
      clearTimeout(timeout);
      finish(1);
      return;
    }
    if (!stopAtFirstChunk && !skipMining &&
        (regionStorageWrites === 0 || nonEmptyRegionStorageWrites !== regionStorageWrites)) {
      events.push({
        type: "region-storage-mismatch",
        regionStorageWrites,
        nonEmptyRegionStorageWrites,
      });
      clearTimeout(timeout);
      finish(1);
      return;
    }
    if (!cpuProfileWritten) {
      events.push({
        type: "cpu-profile-missing",
        phase: cpuProfilePhase,
        path: cpuProfilePath,
      });
      clearTimeout(timeout);
      finish(1);
      return;
    }
    clearTimeout(timeout);
    finish(0);
  };
  protocol.playReady.then(() => {
    protocolReady = true;
    protocolReadyAt = Date.now();
    const timing = protocol.snapshot();
    events.push({
      type: "protocol-ready",
      compressionThreshold: protocol.compressionThreshold,
      configurationPackets: protocol.configurationPackets,
      playPackets: protocol.playPackets,
      playLoginPackets: protocol.playLoginPackets,
      chunkPackets: protocol.chunkPackets,
      knownPackRequests: protocol.knownPackRequests,
      loginProfileId: protocol.loginProfileId,
      blockActionAcks: protocol.blockActionAcks,
      blockActionAckSequences: protocol.blockActionAckSequences,
      blockActionAckLatenciesMs: protocol.blockActionAckLatenciesMs,
      blockActionMaxAckLatencyMs: protocol.blockActionMaxAckLatencyMs,
      blockActionTarget: protocol.blockActionTarget,
      blockUpdates: protocol.blockUpdates,
      blockActionProbeCount: protocol.blockActionProbeCount,
      blockActionLatencyMs: protocol.blockActionLatencyMs,
      targetAirUpdates: protocol.targetAirUpdates,
      targetBlockStateId: protocol.targetBlockStateId,
      addEntityPackets: protocol.addEntityPackets,
      blockDropEntity: protocol.blockDropEntity,
      blockDropLatencyMs: protocol.blockDropLatencyMs,
      roamSteps: protocol.roamSteps,
      roamTimeline: protocol.roamTimeline,
      roamCorrections: protocol.roamCorrections,
      uniqueChunkPositions: timing.uniqueChunkPositions,
      loginToPlayMs: protocol.loginToPlayMs,
      loginToFirstChunkMs: protocol.loginToFirstChunkMs,
      playToFirstChunkMs: protocol.playToFirstChunkMs,
      loginToCompressionMs: timing.loginToCompressionMs,
      compressionToLoginFinishedMs: timing.compressionToLoginFinishedMs,
      loginFinishedToConfigurationFinishedMs:
        timing.loginFinishedToConfigurationFinishedMs,
      configurationFinishedToPlayMs: timing.configurationFinishedToPlayMs,
      configurationTimeline: timing.configurationTimeline,
      playTimeline: timing.playTimeline,
      skipMining,
      mobAiStress,
      requireBlockDrop,
      jsonOnly,
      stopAtFirstChunk,
    });
    maybeStop();
  }).catch((error) => {
    events.push({
      type: "protocol-error",
      detail: error.stack || String(error),
      chunkPriorityStats: latestChunkPriorityStats,
      networkStats: latestNetworkStats,
      networkStatsMeta: latestNetworkStatsMeta,
      worldgenStats: latestWorldgenStats,
      storageStats: latestStorageStats,
      distanceRampIntervalMillis: configuredDistanceRampIntervalMillis,
      ...protocol.snapshot(),
    });
    const profileWasActive = cpuProfileActive;
    if (profileWasActive) {
      worker.postMessage({type: "node-cpu-profile-stop"});
      cpuProfileActive = false;
    }
    clearTimeout(timeout);
    setTimeout(() => finish(1), profileWasActive ? 5000 : 2000);
  });
  worker.on("message", (message) => {
    if (traceEvents && message && message.type &&
        message.type !== "node-event-loop-pong" &&
        message.type !== "node-idb-put" &&
        message.type !== "server-startup-progress" &&
        message.type !== "startup-timing" &&
        message.type !== "telemetry-pong") {
      const detail = message.detail === undefined
        ? ""
        : ` detail=${String(message.detail).slice(0, 500)}`;
      process.stderr.write(`[smoke-event] ${message.type}${detail}\n`);
    }
    if (message && message.type === "telemetry-pong") {
      const sequence = Number(message.sequence);
      const pending = Number.isSafeInteger(sequence)
        ? pendingTelemetryPongs.get(sequence)
        : undefined;
      if (pending === undefined) {
        events.push({
          type: "telemetry-pong-unmatched",
          sequence: Number.isSafeInteger(sequence) ? sequence : null,
        });
        return;
      }
      pendingTelemetryPongs.delete(sequence);
      if (pending.timer) clearTimeout(pending.timer);
      const sessionMatches = message.sessionId === sessionId;
      const telemetrySnapshots = snapshotTelemetryPong(message);
      if (sessionMatches) {
        const latest = updateLatestTelemetrySnapshots(
          {
            chunkPriorityStats: latestChunkPriorityStats,
            networkStats: latestNetworkStats,
            worldgenStats: latestWorldgenStats,
            storageStats: latestStorageStats,
          },
          message,
          sessionId,
        );
        latestChunkPriorityStats = latest.chunkPriorityStats;
        latestNetworkStats = latest.networkStats;
        if (message.network !== null && message.network !== undefined) {
          latestNetworkStatsMeta = {
            source: "telemetry-pong",
            sequence,
            sentAt: pending.sentAt,
            receivedAt: Date.now(),
          };
        }
        latestWorldgenStats = latest.worldgenStats;
        latestServerTickStats = latest.serverTickStats;
        latestStorageStats = latest.storageStats;
      }
      pending.resolve({
        available: sessionMatches,
        source: "telemetry-pong",
        stage: pending.stage,
        sequence,
        receivedAt: Date.now(),
        reason: sessionMatches ? undefined : "session-mismatch",
        ...telemetrySnapshots,
      });
      return;
    }
    if (message && message.type === "node-event-loop-pong") {
      latestChunkPriorityStats = message.chunkPriorityStats !== null &&
        message.chunkPriorityStats !== undefined
        ? copyObjectSnapshot(message.chunkPriorityStats)
        : latestChunkPriorityStats;
      latestNetworkStats = message.networkStats !== null && message.networkStats !== undefined
        ? copyObjectSnapshot(message.networkStats)
        : latestNetworkStats;
      if (message.networkStats !== null && message.networkStats !== undefined) {
        const probe = eventLoopProbeStartedAt.get(message.probeId);
        latestNetworkStatsMeta = {
          source: "node-event-loop-pong",
          probeId: message.probeId,
          sentAt: probe?.parentSendEpochMs,
          receivedAt: Date.now(),
        };
      }
      latestWorldgenStats = message.worldgenStats !== null &&
        message.worldgenStats !== undefined
        ? copyObjectSnapshot(message.worldgenStats)
        : latestWorldgenStats;
      if (serverTickTelemetryEnabled && message.serverTickStats !== null &&
          message.serverTickStats !== undefined) {
        latestServerTickStats = copyObjectSnapshot(message.serverTickStats);
        latestServerTickWindowEnd = copyObjectSnapshot(message.serverTickStats);
        if (serverTickWindowStart === null && protocolReadyAt > 0 &&
            Number(message.workerStartEpochMs) >= protocolReadyAt) {
          serverTickWindowStart = copyObjectSnapshot(message.serverTickStats);
        }
      }
      latestStorageStats = message.storageStats !== null &&
        message.storageStats !== undefined
        ? copyObjectSnapshot(message.storageStats)
        : latestStorageStats;
      if (traceEvents && Date.now() - lastWorldgenTraceAt >= 5000) {
        lastWorldgenTraceAt = Date.now();
        process.stderr.write(
          `[smoke-worldgen] ${JSON.stringify({
            phase: workerPhase,
            chunkPriority: latestChunkPriorityStats,
            network: latestNetworkStats,
            worldgen: latestWorldgenStats,
          })}\n`,
        );
      }
      const probe = eventLoopProbeStartedAt.get(message.probeId);
      if (probe !== undefined) {
        eventLoopProbeStartedAt.delete(message.probeId);
        const parentReceiveEpochMs = highResolutionEpochMillis();
        const parentReceiveMonoMs = performance.now();
        const scopeSnapshotMap = protocolReadyAt > 0 &&
          probe.parentSendEpochMs >= protocolReadyAt
          ? slowProbeSnapshotBlocksAfterProtocolReady
          : slowProbeSnapshotBlocksBeforeProtocolReady;
        const slowSample = eventLoopProbeSample(
          probe,
          message,
          parentReceiveEpochMs,
          parentReceiveMonoMs,
          workerPhase,
          protocolReadyAt,
          scopeSnapshotMap,
          slowProbeSnapshotBlocksById,
        );
        const completedAt = parentReceiveEpochMs;
        const latencyMs = slowSample.roundTripMs;
        eventLoopProbeLatenciesMs.push(latencyMs);
        eventLoopProbeSamples.push({
          probeId: probe.probeId,
          latencyMs,
          startedAt: probe.startedAt,
          completedAt,
          phase: probe.phase,
          parentToWorkerMs: slowSample.parentToWorkerMs,
          workerHandlerMs: slowSample.workerHandlerMs,
          workerToParentMs: slowSample.workerToParentMs,
          workerInterProbeGapMs: slowSample.workerInterProbeGapMs,
          clockAnomaly: slowSample.clockAnomaly,
        });
        rememberSlowProbeSnapshot(scopeSnapshotMap, slowSample);
        if (slowSample.trigger.length > 0) {
          slowProbeCandidateCount++;
          const scopeSamples = slowSample.afterProtocolReady
            ? slowProbeSamplesAfterProtocolReady
            : slowProbeSamplesBeforeProtocolReady;
          retainSlowProbeSample(scopeSamples, slowSample);
          retainSlowProbeSample(slowProbeSamplesGlobal, slowSample, MAX_SLOW_SAMPLES);
          const globalRetentionEvicted = slowSample[SLOW_PROBE_TOP_K_EVICTED] === true;
          // The global ring is authoritative for `dropped`; the balanced
          // before/after arrays are only phase attribution views.
          if (globalRetentionEvicted) {
            slowProbeTopKRetentionDroppedCount++;
          }
        }
        if (slowSample.clockAnomaly &&
            slowProbeClockAnomalies.length < MAX_SLOW_SAMPLES) {
          slowProbeClockAnomalyCount++;
          slowProbeClockAnomalies.push({
            probeId: slowSample.probeId,
            anomalies: slowSample.clockAnomalies,
          });
        } else if (slowSample.clockAnomaly) {
          slowProbeClockAnomalyCount++;
        }
        if (slowSample.slowSnapshotDropReason === "block-cap") {
          slowProbeSnapshotBlockCapDroppedCount++;
        }
        if (slowSample.snapshotError !== null) slowProbeSnapshotErrorCount++;
        if (latencyMs > longestEventLoopProbe.latencyMs) {
          longestEventLoopProbe = {
            probeId: probe.probeId,
            latencyMs,
            startedAt: probe.startedAt,
            completedAt,
            phase: probe.phase,
          };
        }
        if (isGameplayProbePhase(probe.phase) &&
            latencyMs > longestGameplayEventLoopProbe.latencyMs) {
          longestGameplayEventLoopProbe = {
            probeId: probe.probeId,
            latencyMs,
            startedAt: probe.startedAt,
            completedAt,
            phase: probe.phase,
          };
        }
        if (probe.barrier !== undefined) {
          if (probe.barrierTimer) clearTimeout(probe.barrierTimer);
          probe.barrier.resolve({
            available: true,
            source: "node-event-loop-pong",
            stage: probe.phase.slice("telemetry-barrier-".length),
            probeId: message.probeId,
            receivedAt: completedAt,
            networkStats: message.networkStats !== null &&
              message.networkStats !== undefined
              ? copyObjectSnapshot(message.networkStats)
              : null,
            chunkPriorityStats: message.chunkPriorityStats !== null &&
              message.chunkPriorityStats !== undefined
              ? copyObjectSnapshot(message.chunkPriorityStats)
              : null,
            worldgenStats: message.worldgenStats !== null &&
              message.worldgenStats !== undefined
              ? copyObjectSnapshot(message.worldgenStats)
              : null,
            storageStats: message.storageStats !== null &&
              message.storageStats !== undefined
              ? copyObjectSnapshot(message.storageStats)
              : null,
          });
        }
      }
      return;
    }
    events.push(message);
    if (message && message.type === "node-console-error") {
      clearTimeout(timeout);
      finish(1);
    } else if (message && (message.type === "network-pump-error" ||
        message.type === "network-pump-schedule-error" ||
        message.type === "network-pump-retry-exhausted" ||
        message.type === "chunk-batch-ack-without-send")) {
      clearTimeout(timeout);
      finish(1);
    } else if (message && message.type === "node-cpu-profile-written") {
      cpuProfileWritten = true;
      cpuProfileCompletion.resolve();
      if (cpuProfileStopTimer) {
        clearTimeout(cpuProfileStopTimer);
        cpuProfileStopTimer = 0;
      }
    } else if (message && message.type === "node-coverage-written") {
      coverageWritten = true;
      if (coverageStopTimer) {
        clearTimeout(coverageStopTimer);
        coverageStopTimer = 0;
      }
    } else if (message && message.type === "node-idb-put" && message.path.endsWith(".mca")) {
      regionStorageWrites++;
      if (message.bytes > 0) nonEmptyRegionStorageWrites++;
    } else if (message && message.type === "server-created") {
      serverCreatedAt = Date.now();
      workerPhase = "server-created";
      startTimedCpuProfile(workerPhase);
      startTimedCoverage(workerPhase);
      protocol.startLogin();
    } else if (message && message.type === "server-listener-ready") {
      workerPhase = "server-listener-ready";
      distanceSyncReady = true;
      startTimedCpuProfile("distance-configured");
      startTimedCoverage("distance-configured");
      setTimeout(() => {
        worker.postMessage({
          type: "distances",
          renderDistance: targetRenderDistance,
          simulationDistance: targetSimulationDistance,
        });
      }, 1000);
    } else if (message && message.type === "server-distances-ramping") {
      workerPhase = "distance-" + message.detail;
      distanceRamp.push(message.detail);
      distanceTransitionTimeline.push({
        detail: message.detail,
        receivedAt: Date.now(),
        receivedAtMs: performance.now(),
        ackCountAtTransition: protocol.chunkBatchAckCount,
        chunkPacketCountAtTransition: protocol.chunkPackets,
      });
    } else if (message && message.type === "server-distances" &&
        message.detail === expectedDistances && !configuredDistanceReady) {
      workerPhase = "distance-" + message.detail;
      distanceTransitionTimeline.push({
        detail: message.detail,
        receivedAt: Date.now(),
        receivedAtMs: performance.now(),
        ackCountAtTransition: protocol.chunkBatchAckCount,
        chunkPacketCountAtTransition: protocol.chunkPackets,
      });
      if (JSON.stringify(distanceRamp) !== JSON.stringify(expectedDistanceRamp)) {
        events.push({type: "distance-ramp-mismatch", expected: expectedDistanceRamp, actual: distanceRamp});
        clearTimeout(timeout);
        finish(1);
        return;
      }
      const actualTransitions = distanceTransitionTimeline.map((entry) => entry.detail);
      if (JSON.stringify(actualTransitions) !== JSON.stringify(expectedTransitions) ||
          distanceRamp.length !== 0) {
        events.push({
          type: "distance-ramp-causality-mismatch",
          expectedTransitions,
          actualTransitions,
          expectedDistanceRamp,
          actualDistanceRamp: distanceRamp,
          distanceTransitionTimeline,
          chunkBatchAckTimeline: protocol.chunkBatchAckTimeline,
        });
        clearTimeout(timeout);
        finish(1);
        return;
      }
      configuredDistanceReady = true;
      scheduleMobAiStress();
      protocol.startRoam();
      maybeStop();
    } else if (message && message.type === "stopped" && isCompletionReady() &&
        postReadySoakCompletedAt !== 0 && !stoppedReceived) {
      stoppedReceived = true;
      clearInterval(eventLoopProbeInterval);
      void finalizeStopped(message).catch((error) => {
        events.push({
          type: "final-telemetry-barrier-error",
          detail: error.stack || String(error),
          postReadySoak: postReadySoakEvidence(),
        });
        clearTimeout(timeout);
        finish(1);
      });
    } else if (message && (message.type === "crash" || message.type === "bootstrap-crash")) {
      clearTimeout(timeout);
      finish(1);
    }
  });
  worker.on("error", (error) => {
    events.push({type: "worker-error", detail: error.stack || String(error)});
    clearTimeout(timeout);
    finish(1);
  });
  worker.on("exit", (code) => {
    workerExited = true;
    if (!finished && !stoppedReceived) {
      events.push({type: "worker-exited-early", code, ...protocol.snapshot()});
      clearTimeout(timeout);
      finish(code === 0 ? 1 : code);
    }
  });
  if (serverTickTelemetryEnabled) {
    worker.postMessage({type: "diagnostic-config", gaiusServerTickTelemetry: true});
  }
  if (structurePreloadIds !== null) {
    worker.postMessage({
      type: "diagnostic-config",
      gaiusStructurePreloadIds: structurePreloadIds,
      gaiusStructurePreloadBudgetMillis:
        Number(process.env.GAIUS_SMOKE_STRUCTURE_PRELOAD_BUDGET_MS),
    });
  }
  worker.postMessage({
    type: "start",
    worldId: "gaius-node-runtime-smoke",
    seed: process.env.GAIUS_SMOKE_SEED || "gaius-runtime-smoke-v1",
    sessionId,
    launchGeneration: "1",
    profileId: storageConfig.profileId,
    worldVersion: storageConfig.worldVersion,
    storageSchema: storageConfig.storageSchema,
    storageDatabaseName: storageConfig.storageDatabaseName,
    storagePrefix: storageConfig.storagePrefix,
    storageOpfsDirectory: storageConfig.storageOpfsDirectory,
    renderDistance: 8,
    simulationDistance: 5,
    distanceRampIntervalMillis: distanceRampIntervalMillis === undefined
      ? undefined
      : configuredDistanceRampIntervalMillis,
    port: port1,
  }, [port1]);
} else if (workerData && workerData.runtime) {
  if (process.env.GAIUS_SMOKE_JSON_ONLY === "1") {
    const writeDiagnostic = (...args) => {
      process.stderr.write(args.map((value) => String(value)).join(" ") + "\n");
    };
    console.log = writeDiagnostic;
    console.info = writeDiagnostic;
    console.warn = writeDiagnostic;
  }
  installWorkerGlobals();
  installWorkerGcObserver();
  const originalConsoleError = console.error.bind(console);
  console.error = (...args) => {
    const boundedArguments = args.slice(0, MAX_SERIALIZED_ERROR_ARGUMENTS);
    const errorArgument = boundedArguments.find(isErrorLike);
    parentPort.postMessage({
      type: "node-console-error",
      detail: boundedArguments.map(safeErrorText).join(" ")
        .slice(0, MAX_SERIALIZED_ERROR_TEXT * 4),
      error: serializeError(errorArgument),
      arguments: boundedArguments.map((value) => serializeError(value)),
      stack: new Error("console-error-stack").stack,
    });
    originalConsoleError(...args);
  };
  vm.runInThisContext(fs.readFileSync(bootstrapPath, "utf8"), {filename: bootstrapPath});
  let cpuProfileSession;
  let cpuProfileStarted;
  let cpuProfileMetadata;
  let coverageSession;
  let coverageStarted;
  let coverageMetadata;
  let previousWorkerProbeStartMonoMs = Number.NaN;
  const workerSlowProbeBlockState = createSlowProbeBlockState();
  parentPort.on("message", (data) => {
    if (data && data.type === "node-event-loop-probe") {
      const workerStartEpochMs = highResolutionEpochMillis();
      const workerStartMonoMs = performance.now();
      const workerInterProbeGapMs = Number.isFinite(previousWorkerProbeStartMonoMs)
        ? Math.max(0, workerStartMonoMs - previousWorkerProbeStartMonoMs)
        : 0;
      previousWorkerProbeStartMonoMs = workerStartMonoMs;
      const threshold = SLOW_SAMPLE_THRESHOLD_MS;
      const thresholdMismatch = Number(data.slowSampleThresholdMs) !== threshold;
      const parentToWorkerMs = Math.max(
        0,
        workerStartEpochMs - Number(data.parentSendEpochMs),
      );
      const slowTrigger = [];
      if (parentToWorkerMs >= threshold) slowTrigger.push("parent-to-worker");
      if (workerInterProbeGapMs >= threshold) {
        slowTrigger.push("worker-inter-probe-gap");
      }
      const backlogSignalAtStart = parentToWorkerMs >= threshold ||
        workerInterProbeGapMs >= threshold;
      workerSlowProbeBlockState.resetIfFast(backlogSignalAtStart);
      let slowBlock = backlogSignalAtStart
        ? workerSlowProbeBlockState.ensureBlock(data.afterProtocolReady === true)
        : null;
      let slowSnapshot = null;
      let slowSnapshotError = null;
      let slowSnapshotReused = false;
      let slowSnapshotDropReason = null;
      let slowSnapshotAttempted = false;
      const captureSlowSnapshot = () => {
        if (slowSnapshotAttempted) return;
        slowSnapshotAttempted = true;
        if (slowBlock === null) {
          slowBlock = workerSlowProbeBlockState.ensureBlock(data.afterProtocolReady === true);
        }
        const captured = workerSlowProbeBlockState.capture(
          slowBlock,
          safeWorkerSlowProbeSnapshot,
        );
        slowSnapshot = captured.snapshot;
        slowSnapshotError = captured.error;
        slowSnapshotReused = captured.reused;
        slowSnapshotDropReason = captured.dropReason;
      };
      if (slowTrigger.length > 0) captureSlowSnapshot();
      const chunkPriorityStats = globalThis.__gaiusChunkPriorityStats
        ? {...globalThis.__gaiusChunkPriorityStats}
        : null;
      const networkStats = globalThis.__gaiusNetworkStats
        ? {...globalThis.__gaiusNetworkStats}
        : null;
      const worldgenStats = globalThis.__gaiusWorldgenStats
        ? {...globalThis.__gaiusWorldgenStats}
        : null;
      const serverTickStats = globalThis.__gaiusServerTickTelemetryEnabled === true &&
        globalThis.__gaiusServerTickTelemetry
        ? {...globalThis.__gaiusServerTickTelemetry, sampledAtMillis: workerStartMonoMs}
        : null;
      const storageStats = globalThis.__gaiusStorageStats
        ? {...globalThis.__gaiusStorageStats}
        : null;
      let workerEndEpochMs = highResolutionEpochMillis();
      let workerEndMonoMs = performance.now();
      if (workerEndMonoMs - workerStartMonoMs >= threshold &&
          !slowTrigger.includes("worker-handler")) {
        slowTrigger.push("worker-handler");
        if (!slowSnapshotAttempted) {
          captureSlowSnapshot();
          workerEndEpochMs = highResolutionEpochMillis();
          workerEndMonoMs = performance.now();
        }
      }
      const slowSnapshotDropped = slowTrigger.length > 0 &&
        slowSnapshot === null && slowSnapshotError === null &&
        slowSnapshotDropReason !== null;
      parentPort.postMessage({
        type: "node-event-loop-pong",
        probeId: data.probeId,
        slowSampleSchemaVersion: data.slowSampleSchemaVersion,
        slowSampleSchema: data.slowSampleSchema,
        phaseAtSend: data.phaseAtSend,
        parentSendEpochMs: data.parentSendEpochMs,
        parentSendMonoMs: data.parentSendMonoMs,
        workerStartEpochMs,
        workerStartMonoMs,
        workerEndEpochMs,
        workerEndMonoMs,
        workerInterProbeGapMs,
        slowTrigger,
        slowBlockId: slowTrigger.length > 0 && slowBlock !== null ? slowBlock.id : 0,
        slowSnapshotReused,
        slowSnapshotDropReason,
        slowSampleThresholdMismatch: thresholdMismatch,
        slowSnapshot,
        slowSnapshotDropped,
        slowSnapshotError,
        chunkPriorityStats,
        networkStats,
        worldgenStats,
        serverTickStats,
        storageStats,
      });
      return;
    }
    if (data && data.type === "node-cpu-profile-start") {
      if (cpuProfileSession !== undefined) {
        parentPort.postMessage({
          type: "node-console-error",
          detail: "Worker CPU profile was started more than once",
        });
        return;
      }
      cpuProfileSession = new InspectorSession();
      cpuProfileSession.connect();
      cpuProfileMetadata = {phase: data.phase, path: data.path};
      cpuProfileStarted = inspectorPost(cpuProfileSession, "Profiler.enable")
        .then(async () => {
          cpuProfileMetadata.startBeforeWorkerMillis = performance.now();
          await inspectorPost(cpuProfileSession, "Profiler.start");
          cpuProfileMetadata.startAfterWorkerMillis = performance.now();
        });
      return;
    }
    if (data && data.type === "node-cpu-profile-stop") {
      void stopWorkerCpuProfile(
        cpuProfileSession,
        cpuProfileStarted,
        cpuProfileMetadata,
      );
      return;
    }
    if (data && data.type === "node-coverage-start") {
      if (coverageSession !== undefined) {
        parentPort.postMessage({
          type: "node-console-error",
          detail: "Worker precise coverage was started more than once",
        });
        return;
      }
      coverageSession = new InspectorSession();
      coverageSession.connect();
      coverageMetadata = {phase: data.phase, path: data.path};
      coverageStarted = inspectorPost(coverageSession, "Profiler.enable")
        .then(() => inspectorPost(coverageSession, "Profiler.startPreciseCoverage", {
          callCount: true,
          detailed: true,
          allowTriggeredUpdates: false,
        }));
      return;
    }
    if (data && data.type === "node-coverage-stop") {
      void stopWorkerCoverage(
        coverageSession,
        coverageStarted,
        coverageMetadata,
      );
      return;
    }
    globalThis.onmessage({data, ports: []});
  });
  parentPort.postMessage({type: "node-wrapper-ready"});
}

function inspectorPost(session, method, params) {
  return new Promise((resolve, reject) => {
    const callback = (error, result) => {
      if (error) reject(error);
      else resolve(result);
    };
    if (params === undefined) session.post(method, callback);
    else session.post(method, params, callback);
  });
}

async function stopWorkerCpuProfile(session, started, metadata) {
  try {
    if (!session || !started || !metadata?.path) {
      throw new Error("Worker CPU profile stop arrived before start");
    }
    await started;
    const stopBeforeWorkerMillis = performance.now();
    const result = await inspectorPost(session, "Profiler.stop");
    const stopAfterWorkerMillis = performance.now();
    fs.writeFileSync(metadata.path, JSON.stringify(result.profile));
    // The profiler and performance.now() use different origins. Command brackets
    // bound their offset; consumers must retain the uncertainty when matching a
    // sampled CPU interval to a worldgen probe's Worker timestamps.
    const timing = {
      schema: "gaius.worker-cpu-profile-clock.v1",
      profilePath: metadata.path,
      workerTimeOriginMillis: performance.timeOrigin,
      startBeforeWorkerMillis: metadata.startBeforeWorkerMillis,
      startAfterWorkerMillis: metadata.startAfterWorkerMillis,
      stopBeforeWorkerMillis,
      stopAfterWorkerMillis,
      profileStartMicros: result.profile.startTime,
      profileEndMicros: result.profile.endTime,
    };
    fs.writeFileSync(metadata.path + ".timing.json", JSON.stringify(timing, null, 2));
    parentPort.postMessage({
      type: "node-cpu-profile-written",
      phase: metadata.phase,
      path: metadata.path,
      nodes: result.profile.nodes.length,
      samples: result.profile.samples?.length || 0,
      startTime: result.profile.startTime,
      endTime: result.profile.endTime,
      timing,
    });
  } catch (error) {
    parentPort.postMessage({
      type: "node-console-error",
      detail: "Worker CPU profile failed: " + (error.stack || String(error)),
    });
  } finally {
    session?.disconnect();
  }
}

async function stopWorkerCoverage(session, started, metadata) {
  try {
    if (!session || !started || !metadata?.path) {
      throw new Error("Worker precise coverage stop arrived before start");
    }
    await started;
    const result = await inspectorPost(session, "Profiler.takePreciseCoverage");
    await inspectorPost(session, "Profiler.stopPreciseCoverage");
    fs.writeFileSync(metadata.path, JSON.stringify(result));
    parentPort.postMessage({
      type: "node-coverage-written",
      phase: metadata.phase,
      path: metadata.path,
      scripts: result.result.length,
    });
  } catch (error) {
    parentPort.postMessage({
      type: "node-console-error",
      detail: "Worker precise coverage failed: " + (error.stack || String(error)),
    });
  } finally {
    session?.disconnect();
  }
}

function createProtocolClient(port, sessionId, expectedProfileId, options = {}) {
  const ready = deferred();
  const host = "client-" + sessionId + ".gaius-local";
  let buffered = new Uint8Array(0);
  let packetWork = Promise.resolve();
  let remotePaused = false;
  let transportCloseRequested = false;
  let loginStarted = false;
  const pendingSends = [];
  const state = {
    compressionThreshold: undefined,
    phase: "login",
    loginFinished: false,
    configurationPackets: 0,
    configurationFinished: false,
    playPackets: 0,
    playLoginPackets: 0,
    chunkPackets: 0,
    chunkTimeline: [],
    knownPackRequests: 0,
    loginProfileId: undefined,
    playerLoadedSent: false,
    playerPosition: undefined,
    miningScheduled: false,
    miningCompleted: Boolean(options.skipMining),
    roamRequested: false,
    roamScheduled: false,
    roamCompleted: options.roamSteps <= 0,
    roamStep: 0,
    roamOriginX: undefined,
    roamTargetX: undefined,
    roamTargetChunkKey: undefined,
    roamLiftStep: 0,
    roamSteps: options.roamSteps || 0,
    roamStepBlocks: options.roamStepBlocks || 8,
    roamStepStartedAt: undefined,
    roamBaselineChunkPackets: 0,
    roamStepWaiting: false,
    roamTimeline: [],
    roamCorrections: [],
    roamSettleTimer: undefined,
    roamStepTimer: undefined,
    roamHeartbeatTimer: undefined,
    blockActionCandidates: [],
    blockActionProbedTargets: [],
    blockActionCandidateIndex: 0,
    blockActionCandidateConfirmed: false,
    blockActionSequence: 0,
    blockActionProbeCount: 0,
    blockActionProbeTimer: undefined,
    blockActionProbeDeadlineTimer: undefined,
    blockActionProbeDeadlineAt: undefined,
    blockActionProbeWaitReason: undefined,
    transportClosed: false,
    probeFailed: false,
    sendEnqueuedCount: 0,
    sendEnqueuedBytes: 0,
    sendFlushedCount: 0,
    sendFlushedBytes: 0,
    sendLastEnqueuedAt: undefined,
    sendLastFlushedAt: undefined,
    transportCloseRequestedAt: undefined,
    transportCloseReceivedAt: undefined,
    transportCloseReason: undefined,
    blockActionStopTimer: undefined,
    blockActionAckTimer: undefined,
    blockActionRetryTimer: undefined,
    loginStartedAt: undefined,
    compressionAt: undefined,
    loginFinishedAt: undefined,
    configurationStartedAt: undefined,
    configurationFinishedAt: undefined,
    playLoginAt: undefined,
    configurationTimeline: [],
    playTimeline: [],
    firstChunkAt: undefined,
    loginToPlayMs: undefined,
    loginToFirstChunkMs: undefined,
    playToFirstChunkMs: undefined,
    miningStartedAt: undefined,
    targetAirAt: undefined,
    targetStableAt: undefined,
    targetBlockStateId: undefined,
    blockActionStopSequence: undefined,
    blockActionAcks: 0,
    blockActionAckSequences: [],
    blockActionSentAt: new Map(),
    blockActionAckLatenciesMs: [],
    blockActionMaxAckLatencyMs: 0,
    blockActionTarget: undefined,
    blockUpdates: [],
    blockActionLatencyMs: undefined,
    targetAirUpdates: 0,
    addEntityPackets: 0,
    addedEntities: [],
    blockDropEntity: undefined,
    blockDropAt: undefined,
    blockDropLatencyMs: undefined,
    blockDropTimer: undefined,
    blockReboundTimer: undefined,
    persistenceMarkerScheduled: false,
    persistenceMarkerCompleted: false,
    chunkBatchAckSent: false,
    chunkBatchAckCount: 0,
    chunkBatchAckTimeline: [],
    lastAckedChunkPackets: 0,
    chunkBatchAckTimer: undefined,
    uniqueChunkPositions: new Set(),
    chunkDigests: new Map(),
    chunkCenters: [],
    receivedPacketIds: {
      login: [],
      configuration: [],
      play: [],
    },
    playReady: ready.promise,
    snapshot,
    startLogin,
    startRoam,
    sendChatCommand,
    closeTransport,
  };

  port.onmessage = (event) => {
    const message = event.data;
    if (isControlMessage(message)) {
      handleTransportControl(message);
      return;
    }
    if (!(message instanceof ArrayBuffer) && !ArrayBuffer.isView(message)) {
      ready.reject(new Error("Local server transport returned an unsupported message"));
      return;
    }
    const bytes = message instanceof ArrayBuffer
      ? new Uint8Array(message)
      : new Uint8Array(message.buffer, message.byteOffset, message.byteLength);
    buffered = concatenate(buffered, bytes);
    drainFrames();
  };
  port.onmessageerror = () => {
    ready.reject(new Error("Local server MessagePort could not decode a message"));
  };
  port.start();

  function handleTransportControl(message) {
    if (message.type === "flow") {
      remotePaused = Boolean(message.paused);
      if (!remotePaused) flushSends();
      return;
    }
    if (message.type !== "close") return;
    if (state.transportClosed) {
      if (transportCloseRequested && state.transportCloseReceivedAt === undefined) {
        state.transportCloseReceivedAt = Date.now();
        state.transportCloseReason = "local-close-ack";
      }
      return;
    }
    const closeWasRequested = transportCloseRequested;
    closeTransport(false);
    state.transportCloseReceivedAt = Date.now();
    state.transportCloseReason = closeWasRequested ? "local-close-ack" : "remote-close";
    if (!closeWasRequested) {
      ready.reject(new Error(state.chunkPackets === 0
        ? "Local server transport closed before PLAY chunk data arrived"
        : "Local server transport closed after PLAY chunk data arrived"));
    }
  }

  function startLogin() {
    if (loginStarted) {
      return;
    }
    loginStarted = true;
    state.loginStartedAt = Date.now();
    const handshake = concatenateMany([
      encodeVarInt(activeProtocolVersion),
      encodeString(host),
      new Uint8Array([0x63, 0xdd]),
      encodeVarInt(2),
    ]);
    const hello = concatenate(
      encodeString("GaiusSmoke"),
      hexBytes(expectedProfileId)
    );
    send(encodePacket(0, handshake));
    send(encodePacket(0, hello));
  }

  function closeTransport(sendClose = true) {
    if (state.transportClosed) {
      return;
    }
    if (sendClose) {
      transportCloseRequested = true;
      state.transportCloseRequestedAt = Date.now();
    }
    state.transportClosed = true;
    state.probeFailed = true;
    clearTimeout(state.roamHeartbeatTimer);
    clearTimeout(state.roamSettleTimer);
    clearTimeout(state.roamStepTimer);
    clearTimeout(state.blockActionProbeTimer);
    clearTimeout(state.blockActionProbeDeadlineTimer);
    clearTimeout(state.blockActionStopTimer);
    clearTimeout(state.blockActionAckTimer);
    clearTimeout(state.blockActionRetryTimer);
    clearTimeout(state.blockDropTimer);
    clearTimeout(state.blockReboundTimer);
    try {
      if (sendClose) port.postMessage({type: "close"});
    } finally { port.close(); }
  }

  function drainFrames() {
    try {
      while (true) {
        const outerLength = decodeVarInt(buffered, 0);
        if (outerLength === undefined) {
          return;
        }
        const frameStart = outerLength.bytesRead;
        const frameEnd = frameStart + outerLength.value;
        if (frameEnd > buffered.byteLength) {
          return;
        }
        const frame = buffered.slice(frameStart, frameEnd);
        buffered = buffered.slice(frameEnd);
        packetWork = packetWork.then(() => processFrame(frame));
        packetWork.catch((error) => ready.reject(error));
      }
    } catch (error) {
      ready.reject(error);
    }
  }

  function processFrame(frame) {
    let packet = frame;
    if (state.compressionThreshold !== undefined) {
      const dataLength = decodeVarInt(frame, 0);
      if (dataLength === undefined) {
        throw new Error("Compressed Minecraft frame omitted its data length");
      }
      const payload = frame.subarray(dataLength.bytesRead);
      packet = dataLength.value === 0 ? payload : new Uint8Array(inflateSync(payload));
      if (dataLength.value !== 0 && packet.byteLength !== dataLength.value) {
        throw new Error("Minecraft compressed frame length did not match");
      }
    }
    const packetId = decodeVarInt(packet, 0);
    if (packetId === undefined) {
      throw new Error("Minecraft frame omitted its packet id");
    }
    const phasePacketIds = state.receivedPacketIds[state.phase];
    if (phasePacketIds && phasePacketIds.length < 256) {
      phasePacketIds.push(packetId.value);
    }
    const payload = packet.subarray(packetId.bytesRead);
    if (state.phase === "login" && packetId.value === 0) {
      throw new Error("Official server rejected the smoke login");
    }
    if (state.phase === "login" && packetId.value === 3) {
      const threshold = decodeVarInt(packet, packetId.bytesRead);
      if (threshold === undefined || threshold.value < 0) {
        throw new Error("Official server sent an invalid compression threshold");
      }
      state.compressionThreshold = threshold.value;
      state.compressionAt ??= Date.now();
    } else if (state.phase === "login" && packetId.value === 2) {
      if (payload.byteLength < 16) {
        throw new Error("Official server login profile omitted its UUID");
      }
      state.loginProfileId = Buffer.from(payload.subarray(0, 16)).toString("hex");
      if (state.loginProfileId !== expectedProfileId) {
        throw new Error(
          `Integrated server changed the client profile UUID: ${state.loginProfileId}`
        );
      }
      state.loginFinished = true;
      state.loginFinishedAt ??= Date.now();
      state.phase = "configuration";
      send(encodePacket(3, new Uint8Array(0), state.compressionThreshold));
      send(encodePacket(0, encodeClientInformation(), state.compressionThreshold));
    } else if (state.phase === "configuration") {
      state.configurationStartedAt ??= Date.now();
      if (state.configurationTimeline.length < 64) {
        state.configurationTimeline.push({
          packetId: packetId.value,
          elapsedMs: elapsed(state.loginStartedAt, Date.now()),
        });
      }
      state.configurationPackets++;
      if (packetId.value === 2) {
        throw new Error("Official server disconnected during configuration");
      }
      if (packetId.value === 14) {
        state.knownPackRequests++;
        send(encodePacket(7, encodeVarInt(0), state.compressionThreshold));
      } else if (packetId.value === 3) {
        state.configurationFinished = true;
        state.configurationFinishedAt ??= Date.now();
        state.phase = "play";
        send(encodePacket(3, new Uint8Array(0), state.compressionThreshold));
      } else if (packetId.value === 4) {
        send(encodePacket(4, payload, state.compressionThreshold));
      } else if (packetId.value === 5) {
        send(encodePacket(5, payload, state.compressionThreshold));
      }
    } else if (state.phase === "play") {
      if (state.playTimeline.length < 64) {
        state.playTimeline.push({
          packetId: packetId.value,
          elapsedMs: elapsed(state.loginStartedAt, Date.now()),
        });
      }
      state.playPackets++;
      if (packetId.value === clientboundPlay.disconnect) {
        throw new Error("Official server disconnected after entering PLAY");
      }
      if (packetId.value === clientboundPlay.addEntity) {
        const entity = {...decodeAddEntity(payload), receivedAt: Date.now()};
        state.addEntityPackets++;
        if (state.addedEntities.length < 512) {
          state.addedEntities.push(entity);
        }
        maybeRecordBlockDrop(entity);
      } else if (packetId.value === clientboundPlay.keepAlive) {
        send(encodePacket(serverboundPlay.keepAlive, payload, state.compressionThreshold));
      } else if (packetId.value === clientboundPlay.ping) {
        send(encodePacket(serverboundPlay.pong, payload, state.compressionThreshold));
      } else if (packetId.value === clientboundPlay.login) {
        state.playLoginPackets++;
        state.playLoginAt ??= Date.now();
        state.loginToPlayMs ??= elapsed(state.loginStartedAt, state.playLoginAt);
        if (!state.playerLoadedSent) {
          state.playerLoadedSent = true;
          send(encodePacket(
            serverboundPlay.playerLoaded,
            new Uint8Array(0),
            state.compressionThreshold,
          ));
        }
      } else if (packetId.value === clientboundPlay.levelChunkWithLight) {
        state.chunkPackets++;
        const chunkPosition = decodeChunkPosition(payload);
        if (state.chunkTimeline.length < 256) {
          state.chunkTimeline.push({...chunkPosition, receivedAt: Date.now()});
        }
        const chunkKey = `${chunkPosition.x},${chunkPosition.z}`;
        state.uniqueChunkPositions.add(chunkKey);
        state.chunkDigests.set(
          chunkKey,
          createHash("sha256").update(payload).digest("hex"),
        );
        state.firstChunkAt ??= Date.now();
        state.loginToFirstChunkMs ??= elapsed(state.loginStartedAt, state.firstChunkAt);
        state.playToFirstChunkMs ??= elapsed(state.playLoginAt, state.firstChunkAt);
        maybeScheduleChunkBatchAck();
        maybeCompleteRoamStep(chunkPosition);
        maybeScheduleRoam();
        maybeScheduleMining();
        if (!options.requireBlockDrop && state.miningScheduled && !state.blockActionCandidateConfirmed &&
            state.blockActionProbeTimer === undefined &&
            state.blockActionProbeDeadlineTimer !== undefined) {
          probeNextBlock();
        }
      } else if (packetId.value === clientboundPlay.playerPosition) {
        const previousPosition = state.playerPosition;
        state.playerPosition = decodePlayerPosition(payload);
        if (state.roamScheduled && previousPosition) {
          state.roamCorrections.push({
            step: state.roamStep,
            from: previousPosition,
            to: state.playerPosition,
          });
        }
        maybeScheduleRoam();
        maybeScheduleMining();
        send(
          encodePacket(
            serverboundPlay.acceptTeleportation,
            encodeVarInt(state.playerPosition.teleportId),
            state.compressionThreshold
          )
        );
        if (state.roamScheduled && state.roamStep > 0) {
          // A real client resumes movement heartbeats after accepting a server correction.
          scheduleRoamHeartbeat();
        }
      } else if (packetId.value === clientboundPlay.setChunkCacheCenter) {
        const center = decodeChunkCacheCenter(payload);
        if (center && state.chunkCenters.length < 32) {
          state.chunkCenters.push({...center, receivedAt: Date.now()});
        }
      } else if (packetId.value === clientboundPlay.blockChangedAck) {
        const sequence = decodeVarInt(payload, 0);
        if (sequence === undefined) {
          throw new Error("Block-action acknowledgement omitted its sequence");
        }
        state.blockActionAckSequences.push(sequence.value);
        state.blockActionAcks = Math.max(state.blockActionAcks, sequence.value);
        const sentAt = state.blockActionSentAt.get(sequence.value);
        if (sentAt !== undefined) {
          const latency = Date.now() - sentAt;
          state.blockActionSentAt.delete(sequence.value);
          state.blockActionAckLatenciesMs.push(latency);
          state.blockActionMaxAckLatencyMs = Math.max(state.blockActionMaxAckLatencyMs, latency);
        }
        if (sequence.value === state.blockActionStopSequence) {
          clearTimeout(state.blockActionAckTimer);
          completeBlockAction();
        }
      } else if (packetId.value === clientboundPlay.blockUpdate) {
        const update = decodeBlockUpdate(payload);
        if (state.blockUpdates.length < 128) {
          state.blockUpdates.push({...update, receivedAt: Date.now()});
        }
        const matchesCurrentTarget = state.blockActionTarget &&
          sameBlockPos(update, state.blockActionTarget);
        if (matchesCurrentTarget) {
          if (update.stateId === 0) {
            state.targetAirUpdates++;
            if (state.targetAirAt === undefined) {
              state.targetAirAt = Date.now();
              state.blockReboundTimer = setTimeout(() => {
                state.targetStableAt = Date.now();
                completeBlockAction();
              }, options.blockReboundWindowMs);
            }
            if (state.blockDropAt !== undefined && state.blockDropLatencyMs === undefined) {
              state.blockDropLatencyMs = Math.max(0, state.blockDropAt - state.targetAirAt);
            }
            maybeRecordPriorBlockDrop();
            completeBlockAction();
          } else if (state.targetAirAt !== undefined) {
            ready.reject(new Error(
              `Broken block ${update.x},${update.y},${update.z} rebounded to state ` +
              `${update.stateId} within ${options.blockReboundWindowMs} ms`
            ));
          }
        }
        // Under world-generation load the authoritative update can arrive just after the
        // 750 ms probe timer advances. It still proves that a previously probed target is
        // solid and reachable, so do not discard it only because another probe is current.
        if (update.stateId !== 0 && !state.blockActionCandidateConfirmed &&
            state.blockActionProbedTargets.some((target) => sameBlockPos(update, target))) {
          startConfirmedBlockAction(update);
        }
      }
    }
  }

  function maybeScheduleMining() {
    if (state.transportClosed || state.probeFailed || state.miningScheduled || state.chunkPackets === 0) {
      return;
    }
    if (state.roamSteps > 0 && !state.roamCompleted) {
      return;
    }
    if (options.skipMining) {
      state.miningScheduled = true;
      state.miningCompleted = true;
      maybeResolveReady();
      return;
    }
    if (!state.playerPosition) {
      return;
    }
    state.miningScheduled = true;
    state.blockActionCandidates = createBlockCandidates(state.playerPosition);
    // Bound candidate discovery without truncating the initial 3 s delay and
    // all 16 probes at 750 ms each. Confirmed mining retains its ACK/drop gates.
    state.blockActionProbeDeadlineAt = Date.now() + 30000;
    state.blockActionProbeDeadlineTimer = setTimeout(() => {
      state.blockActionProbeDeadlineTimer = undefined;
      if (state.transportClosed || state.probeFailed || state.blockActionCandidateConfirmed || state.miningCompleted) return;
      rejectProbeDeadline();
    }, 30000);
    // Let world generation and network traffic go idle first. The old transport
    // lost this exact case because no later read event pulled the action packet.
    if (options.requireBlockDrop) {
      state.blockActionProbeTimer = setTimeout(() => {
        state.blockActionProbeTimer = undefined;
        prepareDeterministicDropProbe();
      }, 500);
    } else {
      state.blockActionProbeTimer = setTimeout(() => {
        state.blockActionProbeTimer = undefined;
        probeNextBlock();
      }, 3000);
    }
  }

  function prepareDeterministicDropProbe() {
    if (state.transportClosed || state.probeFailed || state.blockActionCandidateConfirmed) return;
    const baseX = Math.floor(state.playerPosition.x);
    const baseZ = Math.floor(state.playerPosition.z);
    state.blockActionCandidates = [[2, 0], [-2, 0], [0, 2], [0, -2]]
      .map(([x, z]) => ({x: baseX + x, y: Math.floor(state.playerPosition.y) + 1, z: baseZ + z}));
    const target = state.blockActionCandidates.find((candidate) => state.uniqueChunkPositions.has(
        chunkKeyForBlock(candidate.x, candidate.z)));
    if (!target) {
      state.blockActionProbeWaitReason = "deterministic-target-awaiting-chunk";
      const remaining = Math.max(0, (state.blockActionProbeDeadlineAt || 0) - Date.now());
      if (remaining === 0) {
        rejectProbeDeadline();
        return;
      }
      state.blockActionProbeTimer = setTimeout(() => {
        state.blockActionProbeTimer = undefined;
        prepareDeterministicDropProbe();
      }, Math.min(100, remaining));
      return;
    }
    state.blockActionCandidates = [target];
    state.blockActionProbeWaitReason = undefined;
    state.blockActionTarget = target;
    state.blockActionProbedTargets.push(target);
    state.blockActionProbeCount++;
    send(encodePacket(
      serverboundPlay.chatCommand,
      encodeString("item replace entity @s weapon.mainhand with minecraft:diamond_pickaxe"),
      state.compressionThreshold
    ));
    send(encodePacket(
      serverboundPlay.chatCommand,
      encodeString(`setblock ${target.x} ${target.y} ${target.z} minecraft:nether_bricks`),
      state.compressionThreshold
    ));
    // Chat commands are deliberately queued onto the server's main executor. During heavy
    // generation, wait for the resulting block update instead of allowing a later player-action
    // packet to overtake the command and turn this into a false mining failure.
    state.blockActionProbeTimer = setTimeout(() => {
      state.blockActionProbeTimer = undefined;
      if (!state.transportClosed && !state.probeFailed && !state.blockActionCandidateConfirmed) {
        failBlockProbe("prepared-block-unconfirmed", "Prepared probe block was not observed within 10 seconds");
      }
    }, 10000);
  }

  function startRoam() {
    state.roamRequested = true;
    maybeScheduleRoam();
  }

  function scheduleRoamHeartbeat() {
    clearTimeout(state.roamHeartbeatTimer);
    const sendHeartbeat = () => {
      if (!state.roamStepWaiting || state.roamCompleted || !state.playerPosition) {
        state.roamHeartbeatTimer = undefined;
        return;
      }
      send(
        encodePacket(
          serverboundPlay.movePlayerPos,
          encodeMovePlayerPosition(state.playerPosition),
          state.compressionThreshold
        )
      );
      state.roamHeartbeatTimer = setTimeout(sendHeartbeat, 250);
    };
    state.roamHeartbeatTimer = setTimeout(sendHeartbeat, 25);
  }

  function maybeScheduleRoam() {
    if (!state.roamRequested || state.roamScheduled || state.roamCompleted ||
        state.chunkPackets === 0 || !state.playerPosition) {
      return;
    }
    clearTimeout(state.roamSettleTimer);
    state.roamSettleTimer = setTimeout(() => {
      state.roamScheduled = true;
      liftPlayerForRoam();
    }, 750);
  }

  function liftPlayerForRoam() {
    if (state.roamLiftStep >= 3) {
      state.roamOriginX ??= state.playerPosition.x;
      setTimeout(sendNextRoamStep, 250);
      return;
    }
    state.roamLiftStep++;
    state.playerPosition = {
      ...state.playerPosition,
      y: state.playerPosition.y + 8,
    };
    send(
      encodePacket(
        serverboundPlay.movePlayerPos,
        encodeMovePlayerPosition(state.playerPosition),
        state.compressionThreshold
      )
    );
    setTimeout(liftPlayerForRoam, 100);
  }

  function sendNextRoamStep() {
    if (state.roamStep >= state.roamSteps) {
      state.roamCompleted = true;
      clearTimeout(state.roamHeartbeatTimer);
      state.roamHeartbeatTimer = undefined;
      options.onRoamPhase?.("complete");
      maybeScheduleMining();
      maybeResolveReady();
      return;
    }
    state.roamStep++;
    let nextX = state.playerPosition.x + state.roamStepBlocks;
    let targetKey = chunkKeyForBlock(nextX, state.playerPosition.z);
    for (let skipped = 0;
        skipped < 64 && state.uniqueChunkPositions.has(targetKey);
        skipped++) {
      nextX += state.roamStepBlocks;
      targetKey = chunkKeyForBlock(nextX, state.playerPosition.z);
    }
    if (state.uniqueChunkPositions.has(targetKey)) {
      ready.reject(new Error("Could not find an unloaded roam target within 64 steps"));
      return;
    }
    state.roamTargetX = nextX;
    state.roamTargetChunkKey = targetKey;
    state.roamBaselineChunkPackets = state.uniqueChunkPositions.size;
    state.roamStepStartedAt = Date.now();
    state.roamStepWaiting = true;
    options.onRoamPhase?.(`${state.roamStep}/${state.roamSteps}`);
    // A direct client movement jump is rejected by the vanilla server once the nearest
    // unloaded chunk is more than a few blocks away. Use the command path granted only
    // to this isolated smoke player so the test exercises chunk generation instead of
    // tripping the server's movement validation.
    if (state.roamStep === 1 && options.roamSpectator) {
      send(encodePacket(
        serverboundPlay.chatCommand,
        encodeString("gamemode spectator @s"),
        state.compressionThreshold
      ));
    }
    send(encodePacket(
      serverboundPlay.chatCommand,
      encodeString(
        `tp @s ${nextX} ${state.playerPosition.y} ${state.playerPosition.z}`
      ),
      state.compressionThreshold
    ));
    state.roamStepTimer = setTimeout(() => {
      clearTimeout(state.roamHeartbeatTimer);
      state.roamHeartbeatTimer = undefined;
      ready.reject(new Error(
        `Roam step ${state.roamStep} produced no new chunk packet within ` +
          `${options.roamTimeoutMs} ms`
      ));
    }, options.roamTimeoutMs);
  }

  function maybeCompleteRoamStep(chunkPosition) {
    if (!state.roamScheduled || state.roamCompleted || !state.roamStepWaiting ||
        `${chunkPosition.x},${chunkPosition.z}` !== state.roamTargetChunkKey) {
      return;
    }
    state.roamStepWaiting = false;
    clearTimeout(state.roamStepTimer);
    clearTimeout(state.roamHeartbeatTimer);
    state.roamHeartbeatTimer = undefined;
    state.roamTimeline.push({
      step: state.roamStep,
      blocks: Math.round(Math.abs(state.roamTargetX - state.roamOriginX)),
      targetChunk: state.roamTargetChunkKey,
      newChunkPositions: state.uniqueChunkPositions.size - state.roamBaselineChunkPackets,
      firstChunkMs: Date.now() - state.roamStepStartedAt,
    });
    setTimeout(sendNextRoamStep, 250);
  }

  function failBlockProbe(reason, message) {
    if (state.transportClosed || state.probeFailed || state.miningCompleted) return;
    state.probeFailed = true;
    state.blockActionProbeWaitReason = reason;
    for (const timer of ["blockActionProbeTimer", "blockActionProbeDeadlineTimer",
        "blockActionStopTimer", "blockActionAckTimer", "blockActionRetryTimer"]) {
      clearTimeout(state[timer]);
      state[timer] = undefined;
    }
    ready.reject(new Error(message));
  }

  function rejectProbeDeadline() {
    const covered = state.blockActionCandidates.some((candidate) =>
      state.uniqueChunkPositions.has(chunkKeyForBlock(candidate.x, candidate.z)));
    failBlockProbe(covered
      ? "probe-deadline-no-authoritative-confirmation" : "probe-deadline-no-loaded-candidate",
    covered
      ? "Loaded candidates produced no authoritative block confirmation before probe deadline"
      : "No loaded chunk candidate became available before probe deadline");
  }

  function probeNextBlock() {
    if (state.transportClosed || state.probeFailed || state.blockActionCandidateConfirmed || state.miningCompleted) {
      return;
    }
    const remaining = Math.max(0, (state.blockActionProbeDeadlineAt || 0) - Date.now());
    if (remaining === 0) {
      rejectProbeDeadline();
      return;
    }
    const target = state.blockActionCandidates.find((candidate) =>
      !state.blockActionProbedTargets.some((probed) => sameBlockPos(probed, candidate)) &&
      state.uniqueChunkPositions.has(chunkKeyForBlock(candidate.x, candidate.z)));
    if (!target) {
      const hasUnloaded = state.blockActionCandidates.some((candidate) =>
        !state.blockActionProbedTargets.some((probed) => sameBlockPos(probed, candidate)) &&
        !state.uniqueChunkPositions.has(chunkKeyForBlock(candidate.x, candidate.z)));
      if (hasUnloaded && remaining > 0) {
        state.blockActionProbeWaitReason = "awaiting-candidate-chunk";
        state.blockActionProbeTimer = setTimeout(() => {
          state.blockActionProbeTimer = undefined;
          probeNextBlock();
        }, Math.min(100, remaining));
        return;
      }
      failBlockProbe("all-loaded-candidates-tried", "No loaded candidate produced break progress");
      return;
    }
    state.blockActionCandidateIndex = state.blockActionCandidates.indexOf(target) + 1;
    state.blockActionTarget = target;
    state.blockActionProbeWaitReason = undefined;
    state.blockActionProbedTargets.push(state.blockActionTarget);
    state.blockActionProbeCount++;
    sendPlayerAction(0);
    state.blockActionProbeTimer = setTimeout(() => {
      state.blockActionProbeTimer = undefined;
      if (state.blockActionCandidateConfirmed) {
        return;
      }
      probeNextBlock();
    }, 750);
  }

  function startConfirmedBlockAction(target) {
    if (state.transportClosed || state.probeFailed || state.miningCompleted) return;
    state.blockActionCandidateConfirmed = true;
    state.blockActionTarget = {x: target.x, y: target.y, z: target.z};
    state.targetBlockStateId = target.stateId;
    state.blockActionProbeWaitReason = undefined;
    clearTimeout(state.blockActionProbeTimer);
    state.blockActionProbeTimer = undefined;
    state.miningStartedAt = Date.now();
    sendPlayerAction(0);
    state.blockActionStopTimer = setTimeout(() => {
      state.blockActionStopSequence = sendPlayerAction(2);
      clearTimeout(state.blockActionAckTimer);
      state.blockActionAckTimer = setTimeout(() => {
        ready.reject(new Error(
          `Block-action STOP sequence ${state.blockActionStopSequence} was not acknowledged`
        ));
      }, 5000);
      state.blockActionRetryTimer = setTimeout(() => {
        if (state.targetAirUpdates > 0) {
          return;
        }
        state.blockActionCandidateConfirmed = false;
        sendPlayerAction(1);
        setTimeout(probeNextBlock, 100);
      }, 4000);
    }, options.blockActionHoldMs);
  }

  function sendPlayerAction(action) {
    if (state.transportClosed || state.probeFailed) {
      return undefined;
    }
    const sequence = ++state.blockActionSequence;
    state.blockActionSentAt.set(sequence, Date.now());
    send(encodePacket(
      serverboundPlay.playerAction,
      concatenateMany([
        encodeVarInt(action),
        encodeBlockPos(state.blockActionTarget),
        new Uint8Array([1]),
        encodeVarInt(sequence),
      ]),
      state.compressionThreshold
    ));
    return sequence;
  }

  function completeBlockAction() {
    if (state.transportClosed || state.probeFailed || state.miningCompleted) return;
    if (state.targetAirUpdates < 1 || state.targetStableAt === undefined) {
      return;
    }
    if (state.blockActionStopSequence === undefined ||
        !state.blockActionAckSequences.includes(state.blockActionStopSequence)) {
      return;
    }
    if (options.requireBlockDrop && state.blockDropEntity === undefined) {
      if (state.blockDropTimer === undefined) {
        state.blockDropTimer = setTimeout(() => {
          ready.reject(new Error(
            `Broken block state ${state.targetBlockStateId} produced no nearby entity ` +
            `within ${options.blockDropTimeoutMs} ms`
          ));
        }, options.blockDropTimeoutMs);
      }
      return;
    }
    if (options.requireBlockDrop && !state.persistenceMarkerScheduled) {
      state.persistenceMarkerScheduled = true;
      // Keep the persistence marker in the same received chunk as the probe.
      const markerZ = state.blockActionTarget.z + ((state.blockActionTarget.z & 15) === 15 ? -1 : 1);
      send(encodePacket(
        serverboundPlay.chatCommand,
        encodeString(
          `setblock ${state.blockActionTarget.x} ${state.blockActionTarget.y} ` +
          `${markerZ} minecraft:gold_block`
        ),
        state.compressionThreshold
      ));
      setTimeout(() => {
        state.persistenceMarkerCompleted = true;
        completeBlockAction();
      }, 500);
      return;
    }
    if (options.requireBlockDrop && !state.persistenceMarkerCompleted) {
      return;
    }
    state.blockActionLatencyMs = Date.now() - state.miningStartedAt;
    state.miningCompleted = true;
    clearTimeout(state.blockActionProbeTimer);
    clearTimeout(state.blockActionProbeDeadlineTimer);
    clearTimeout(state.blockActionStopTimer);
    clearTimeout(state.blockActionAckTimer);
    clearTimeout(state.blockActionRetryTimer);
    clearTimeout(state.blockDropTimer);
    clearTimeout(state.blockReboundTimer);
    maybeResolveReady();
  }

  function maybeResolveReady() {
    if (!state.roamCompleted || !state.miningCompleted) {
      return;
    }
    if (state.roamTimeline.length !== state.roamSteps) {
      ready.reject(new Error(
        `Roam completed with ${state.roamTimeline.length}/${state.roamSteps} measured steps`
      ));
      return;
    }
    if (options.requireBlockDrop &&
        (state.blockActionProbeCount < 1 || state.targetAirUpdates < 1 ||
          state.targetStableAt === undefined ||
          !state.blockActionAckSequences.includes(state.blockActionStopSequence) ||
          state.blockDropEntity === undefined ||
          state.blockDropEntity.entityTypeId !== itemEntityTypeId ||
          state.blockDropLatencyMs === undefined)) {
      ready.reject(new Error("Block-drop smoke completed without a confirmed dropped entity"));
      return;
    }
    ready.resolve();
  }

  function maybeRecordPriorBlockDrop() {
    if (!state.blockActionTarget || state.targetAirAt === undefined) {
      return;
    }
    for (let index = state.addedEntities.length - 1; index >= 0; index--) {
      const entity = state.addedEntities[index];
      if (entity.receivedAt < state.miningStartedAt) {
        break;
      }
      if (entity.entityTypeId === itemEntityTypeId &&
          isNearBlock(entity, state.blockActionTarget)) {
        recordBlockDrop(entity);
        return;
      }
    }
  }

  function maybeRecordBlockDrop(entity) {
    if (state.blockDropEntity !== undefined || !state.blockActionTarget ||
        state.miningStartedAt === undefined || entity.receivedAt < state.miningStartedAt ||
        entity.entityTypeId !== itemEntityTypeId ||
        !isNearBlock(entity, state.blockActionTarget)) {
      return;
    }
    recordBlockDrop(entity);
    completeBlockAction();
  }

  function recordBlockDrop(entity) {
    if (state.blockDropEntity !== undefined) {
      return;
    }
    state.blockDropEntity = entity;
    state.blockDropAt = entity.receivedAt;
    if (state.targetAirAt !== undefined) {
      state.blockDropLatencyMs = Math.max(0, entity.receivedAt - state.targetAirAt);
    }
  }

  function maybeScheduleChunkBatchAck() {
    if (state.chunkBatchAckTimer !== undefined ||
        state.chunkPackets <= state.lastAckedChunkPackets) {
      return;
    }
    state.chunkBatchAckTimer = setTimeout(() => {
      state.chunkBatchAckTimer = undefined;
      if (state.chunkPackets <= state.lastAckedChunkPackets) {
        return;
      }
      const ackedChunkPackets = state.chunkPackets;
      state.lastAckedChunkPackets = ackedChunkPackets;
      send(encodePacket(
        serverboundPlay.chunkBatchReceived,
        encodeFloat(options.chunkBatchDesiredRate),
        state.compressionThreshold
      ), () => {
        state.chunkBatchAckSent = true;
        state.chunkBatchAckCount++;
        state.chunkBatchAckTimeline.push({
          ackIndex: state.chunkBatchAckCount,
          chunkPacketCount: ackedChunkPackets,
          sentAt: Date.now(),
          sentAtMs: performance.now(),
        });
      });
    }, options.chunkBatchAckDelayMs);
  }

  function send(bytes, onSent) {
    if (state.transportClosed) {
      return;
    }
    state.sendEnqueuedCount++;
    state.sendEnqueuedBytes += bytes.byteLength;
    state.sendLastEnqueuedAt = Date.now();
    pendingSends.push({bytes, onSent});
    flushSends();
  }

  function sendChatCommand(command) {
    const normalized = String(command || "").trim();
    if (!normalized) throw new Error("Cannot send an empty chat command");
    send(encodePacket(
      serverboundPlay.chatCommand,
      encodeString(normalized),
      state.compressionThreshold,
    ));
  }

  function snapshot() {
    return {
      phase: state.phase,
      compressionThreshold: state.compressionThreshold,
      configurationPackets: state.configurationPackets,
      configurationFinished: state.configurationFinished,
      playPackets: state.playPackets,
      playLoginPackets: state.playLoginPackets,
      chunkPackets: state.chunkPackets,
      chunkTimeline: state.chunkTimeline.slice(),
      knownPackRequests: state.knownPackRequests,
      loginProfileId: state.loginProfileId,
      playerPosition: state.playerPosition,
      blockActionAcks: state.blockActionAcks,
      blockActionAckSequences: state.blockActionAckSequences.slice(),
      blockActionAckLatenciesMs: state.blockActionAckLatenciesMs.slice(),
      blockActionMaxAckLatencyMs: state.blockActionMaxAckLatencyMs,
      blockActionTarget: state.blockActionTarget,
      blockUpdates: state.blockUpdates.slice(),
      blockActionProbeCount: state.blockActionProbeCount,
      blockActionProbeDeadlineAt: state.blockActionProbeDeadlineAt,
      blockActionProbeWaitReason: state.blockActionProbeWaitReason,
      blockActionProbeTargets: state.blockActionProbedTargets.slice(),
      blockActionCandidateCoverage: (() => {
        const loaded = state.blockActionCandidates.filter((candidate) =>
          state.uniqueChunkPositions.has(chunkKeyForBlock(candidate.x, candidate.z)));
        return {
          totalCandidates: state.blockActionCandidates.length,
          loadedCandidates: loaded.length,
          unloadedCandidates: state.blockActionCandidates.length - loaded.length,
          pendingUnloadedCandidates: state.blockActionCandidates.filter((candidate) =>
            !state.blockActionProbedTargets.some((probed) => sameBlockPos(probed, candidate)) &&
            !state.uniqueChunkPositions.has(chunkKeyForBlock(candidate.x, candidate.z))).length,
          loadedChunkKeys: [...state.uniqueChunkPositions].sort(),
        };
      })(),
      blockActionLatencyMs: state.blockActionLatencyMs,
      blockActionHoldMs: options.blockActionHoldMs,
      blockActionStopSequence: state.blockActionStopSequence,
      blockReboundWindowMs: options.blockReboundWindowMs,
      blockStable: state.targetStableAt !== undefined,
      miningCompleted: state.miningCompleted,
      targetAirUpdates: state.targetAirUpdates,
      targetBlockStateId: state.targetBlockStateId,
      addEntityPackets: state.addEntityPackets,
      blockDropEntity: state.blockDropEntity,
      blockDropLatencyMs: state.blockDropLatencyMs,
      persistenceMarkerCompleted: state.persistenceMarkerCompleted,
      roamSteps: state.roamSteps,
      roamCompleted: state.roamCompleted,
      roamTimeline: state.roamTimeline.slice(),
      roamCorrections: state.roamCorrections.slice(),
      uniqueChunkPositions: state.uniqueChunkPositions.size,
      chunkDigests: Object.fromEntries(
        [...state.chunkDigests].sort(([left], [right]) => left.localeCompare(right)),
      ),
      chunkCenters: state.chunkCenters.slice(),
      loginToPlayMs: state.loginToPlayMs,
      loginToFirstChunkMs: state.loginToFirstChunkMs,
      playToFirstChunkMs: state.playToFirstChunkMs,
      loginToCompressionMs: elapsed(state.loginStartedAt, state.compressionAt),
      compressionToLoginFinishedMs: elapsed(state.compressionAt, state.loginFinishedAt),
      loginFinishedToConfigurationFinishedMs:
        elapsed(state.loginFinishedAt, state.configurationFinishedAt),
      configurationFinishedToPlayMs:
        elapsed(state.configurationFinishedAt, state.playLoginAt),
      configurationTimeline: state.configurationTimeline.slice(),
      playTimeline: state.playTimeline.slice(),
      chunkBatchAckSent: state.chunkBatchAckSent,
      chunkBatchAckCount: state.chunkBatchAckCount,
      chunkBatchAckTimeline: state.chunkBatchAckTimeline.slice(),
      chunkBatchAckDelayMs: options.chunkBatchAckDelayMs,
      chunkBatchDesiredRate: options.chunkBatchDesiredRate,
      receivedPacketIds: state.receivedPacketIds,
      transport: {
        remotePaused,
        pendingSends: pendingSends.length,
        enqueuedCount: state.sendEnqueuedCount,
        enqueuedBytes: state.sendEnqueuedBytes,
        flushedCount: state.sendFlushedCount,
        flushedBytes: state.sendFlushedBytes,
        lastEnqueuedAt: state.sendLastEnqueuedAt,
        lastFlushedAt: state.sendLastFlushedAt,
        closeRequestedAt: state.transportCloseRequestedAt,
        closeReceivedAt: state.transportCloseReceivedAt,
        closeReason: state.transportCloseReason,
        closed: state.transportClosed,
      },
    };
  }

  function flushSends() {
    while (!state.transportClosed && !remotePaused && pendingSends.length > 0) {
      const {bytes, onSent} = pendingSends.shift();
      const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      port.postMessage(buffer, [buffer]);
      state.sendFlushedCount++;
      state.sendFlushedBytes += bytes.byteLength;
      state.sendLastFlushedAt = Date.now();
      onSent?.();
    }
  }

  return state;
}

function elapsed(start, end) {
  return start === undefined || end === undefined ? undefined : end - start;
}

function encodePacket(id, payload, compressionThreshold) {
  const packet = concatenate(encodeVarInt(id), payload);
  let body = packet;
  if (compressionThreshold !== undefined) {
    if (packet.byteLength >= compressionThreshold) {
      throw new Error("Smoke client only sends small uncompressed post-login packets");
    }
    body = concatenate(encodeVarInt(0), packet);
  }
  return concatenate(encodeVarInt(body.byteLength), body);
}

function encodeString(value) {
  const bytes = new TextEncoder().encode(value);
  return concatenate(encodeVarInt(bytes.byteLength), bytes);
}

function encodeClientInformation() {
  return concatenateMany([
    encodeString("en_us"),
    new Uint8Array([6]),
    encodeVarInt(0),
    new Uint8Array([1, 0x7f]),
    encodeVarInt(1),
    new Uint8Array([0, 1]),
    encodeVarInt(0),
  ]);
}

function encodeFloat(value) {
  const result = new Uint8Array(4);
  new DataView(result.buffer).setFloat32(0, value, false);
  return result;
}

function encodeMovePlayerPosition(position) {
  const result = new Uint8Array(25);
  const view = new DataView(result.buffer);
  view.setFloat64(0, position.x, false);
  view.setFloat64(8, position.y, false);
  view.setFloat64(16, position.z, false);
  result[24] = 0;
  return result;
}

function encodeBlockPos(position) {
  const packed = (BigInt(position.x) & 0x3ffffffn) << 38n |
    (BigInt(position.z) & 0x3ffffffn) << 12n |
    (BigInt(position.y) & 0xfffn);
  const result = new Uint8Array(8);
  new DataView(result.buffer).setBigUint64(0, packed, false);
  return result;
}

function decodePlayerPosition(payload) {
  const teleportId = decodeVarInt(payload, 0);
  if (teleportId === undefined || payload.byteLength < teleportId.bytesRead + 56) {
    throw new Error("Player-position packet was truncated");
  }
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  const offset = teleportId.bytesRead;
  return {
    teleportId: teleportId.value,
    x: view.getFloat64(offset, false),
    y: view.getFloat64(offset + 8, false),
    z: view.getFloat64(offset + 16, false),
  };
}

function decodeChunkPosition(payload) {
  if (payload.byteLength < 8) {
    throw new Error("Chunk packet omitted its chunk coordinates");
  }
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  return {x: view.getInt32(0, false), z: view.getInt32(4, false)};
}

function decodeChunkCacheCenter(payload) {
  const x = decodeVarInt(payload, 0);
  if (x === undefined) {
    return undefined;
  }
  const z = decodeVarInt(payload, x.bytesRead);
  return z === undefined ? undefined : {x: x.value, z: z.value};
}

function decodeAddEntity(payload) {
  const entityId = decodeVarInt(payload, 0);
  if (entityId === undefined) {
    throw new Error("Add-entity packet omitted its entity id");
  }
  let offset = entityId.bytesRead + 16;
  if (payload.byteLength < offset) {
    throw new Error("Add-entity packet omitted its UUID");
  }
  const entityType = decodeVarInt(payload, offset);
  if (entityType === undefined) {
    throw new Error("Add-entity packet omitted its entity type");
  }
  offset += entityType.bytesRead;
  if (payload.byteLength < offset + 24) {
    throw new Error("Add-entity packet omitted its position");
  }
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  return {
    entityId: entityId.value,
    entityTypeId: entityType.value,
    x: view.getFloat64(offset, false),
    y: view.getFloat64(offset + 8, false),
    z: view.getFloat64(offset + 16, false),
  };
}

function decodeBlockUpdate(payload) {
  if (payload.byteLength < 9) {
    throw new Error("Block-update packet was truncated");
  }
  const packed = new DataView(
    payload.buffer,
    payload.byteOffset,
    payload.byteLength
  ).getBigUint64(0, false);
  const state = decodeVarInt(payload, 8);
  if (state === undefined) {
    throw new Error("Block-update packet omitted its state id");
  }
  return {
    x: signedBits(packed >> 38n, 26n),
    y: signedBits(packed, 12n),
    z: signedBits(packed >> 12n, 26n),
    stateId: state.value,
  };
}

function createBlockCandidates(position) {
  const baseX = Math.floor(position.x);
  const baseY = Math.floor(position.y);
  const baseZ = Math.floor(position.z);
  // Never mine the stationary smoke player's supporting column. Falling into
  // the probe hole can kill the client and make chunk streaming look stalled.
  const offsets = [[1, 0], [-1, 0], [0, 1], [0, -1]];
  const candidates = [];
  for (let depth = 1; depth <= 4; depth++) {
    for (const [offsetX, offsetZ] of offsets) {
      candidates.push({
        x: baseX + offsetX,
        y: baseY - depth,
        z: baseZ + offsetZ,
      });
    }
  }
  return candidates;
}

function signedBits(value, bits) {
  const mask = (1n << bits) - 1n;
  const sign = 1n << (bits - 1n);
  const normalized = value & mask;
  return Number(normalized & sign ? normalized - (1n << bits) : normalized);
}

function sameBlockPos(first, second) {
  return first.x === second.x && first.y === second.y && first.z === second.z;
}

function chunkKeyForBlock(x, z) {
  return `${Math.floor(x) >> 4},${Math.floor(z) >> 4}`;
}

function isNearBlock(entity, block) {
  return Math.abs(entity.x - (block.x + 0.5)) <= 2.0 &&
    Math.abs(entity.y - (block.y + 0.5)) <= 2.0 &&
    Math.abs(entity.z - (block.z + 0.5)) <= 2.0;
}

function encodeVarInt(value) {
  const bytes = [];
  let remaining = value >>> 0;
  do {
    let next = remaining & 0x7f;
    remaining >>>= 7;
    if (remaining !== 0) {
      next |= 0x80;
    }
    bytes.push(next);
  } while (remaining !== 0);
  return new Uint8Array(bytes);
}

function decodeVarInt(bytes, offset) {
  let value = 0;
  for (let index = 0; index < 5; index++) {
    const position = offset + index;
    if (position >= bytes.byteLength) {
      return undefined;
    }
    const next = bytes[position];
    value |= (next & 0x7f) << (index * 7);
    if ((next & 0x80) === 0) {
      return {value, bytesRead: index + 1};
    }
  }
  throw new Error("Minecraft VarInt exceeded five bytes");
}

function concatenate(first, second) {
  const result = new Uint8Array(first.byteLength + second.byteLength);
  result.set(first, 0);
  result.set(second, first.byteLength);
  return result;
}

function concatenateMany(parts) {
  let result = new Uint8Array(0);
  for (const part of parts) {
    result = concatenate(result, part);
  }
  return result;
}

function hexBytes(value) {
  const result = new Uint8Array(value.length / 2);
  for (let index = 0; index < result.length; index++) {
    result[index] = Number.parseInt(value.slice(index * 2, index * 2 + 2), 16);
  }
  return result;
}

function isControlMessage(message) {
  return message && typeof message === "object" &&
    !(message instanceof ArrayBuffer) && !ArrayBuffer.isView(message);
}

function percentile(sortedValues, fraction) {
  if (sortedValues.length === 0) {
    return 0;
  }
  return sortedValues[Math.min(
    sortedValues.length - 1,
    Math.max(0, Math.ceil(sortedValues.length * fraction) - 1),
  )];
}

function summarizeProbeLatencies(samples, minimumStartedAt) {
  if (!minimumStartedAt) {
    return {samples: 0, p95Ms: 0, p99Ms: 0, maxMs: 0};
  }
  const latencies = samples
    .filter((sample) => sample.startedAt >= minimumStartedAt)
    .map((sample) => sample.latencyMs)
    .sort((left, right) => left - right);
  return {
    samples: latencies.length,
    p95Ms: percentile(latencies, 0.95),
    p99Ms: percentile(latencies, 0.99),
    maxMs: latencies.at(-1) || 0,
  };
}

function summarizeProbePhases(samples) {
  const phases = {};
  for (const sample of samples) {
    (phases[sample.phase] ||= []).push(sample.latencyMs);
  }
  for (const [phase, latencies] of Object.entries(phases)) {
    latencies.sort((left, right) => left - right);
    phases[phase] = {
      samples: latencies.length,
      p95Ms: percentile(latencies, 0.95),
      p99Ms: percentile(latencies, 0.99),
      maxMs: latencies.at(-1) || 0,
    };
  }
  return phases;
}

function summarizeGameplayProbeLatencies(samples) {
  const latencies = samples
    .filter((sample) => isGameplayProbePhase(sample.phase))
    .map((sample) => sample.latencyMs)
    .sort((left, right) => left - right);
  return {
    samples: latencies.length,
    p95Ms: percentile(latencies, 0.95),
    p99Ms: percentile(latencies, 0.99),
    maxMs: latencies.at(-1) || 0,
  };
}

function isGameplayProbePhase(phase) {
  return phase === "server-created" ||
    phase === "server-listener-ready" ||
    phase === "distance-staged" ||
    phase.startsWith("distance-") ||
    phase.startsWith("roam-");
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return {promise, resolve, reject};
}

function installWorkerGlobals() {
  Error.stackTraceLimit = 100;
  class NodeWorkerGlobalScope {}
  Object.setPrototypeOf(globalThis, NodeWorkerGlobalScope.prototype);
  globalThis.WorkerGlobalScope = NodeWorkerGlobalScope;
  globalThis.MessagePort = MessagePort;
  globalThis.self = globalThis;
  globalThis.__gaiusSlowProbeTelemetryEnabled = true;
  // Mob AI checkpoints stay silent in normal release runs.  The diagnostic
  // smoke can opt in so its telemetry-pong snapshots prove that the patched
  // Mob.serverAiStep stages are actually being reached without changing AI
  // ordering or the 500 ms stall gate.
  globalThis.__gaiusMobAiTelemetry = process.env.GAIUS_SMOKE_MOB_AI_TELEMETRY === "1";
  const worldgenSliceMillis = Number(process.env.GAIUS_SMOKE_WORLDGEN_SLICE_MS || "");
  if (Number.isFinite(worldgenSliceMillis) && worldgenSliceMillis > 0) {
    globalThis.__gaiusWorldgenSliceMillis = worldgenSliceMillis;
  }
  const distanceManagerUpdateBudget = Number(
    process.env.GAIUS_SMOKE_DISTANCE_MANAGER_UPDATE_BUDGET || "",
  );
  if (Number.isFinite(distanceManagerUpdateBudget) && distanceManagerUpdateBudget > 0) {
    globalThis.__gaiusDistanceManagerUpdateBudget = distanceManagerUpdateBudget;
  }
  globalThis.location = pathToFileURL(bootstrapPath);
  globalThis.location.search = "";
  globalThis.postMessage = (message, transfer) => parentPort.postMessage(message, transfer);
  globalThis.close = () => process.exit(0);
  globalThis.importScripts = (...urls) => {
    for (const url of urls) {
      const path = fileURLToPath(String(url));
      let source = fs.readFileSync(path, "utf8");
      if (process.env.GAIUS_SMOKE_MIN_SERVER_VIEW_DISTANCE === "2") {
        const minimumDistanceReturn =
          "return !(typeof WorkerGlobalScope !== 'undefined' && " +
          "globalThis instanceof WorkerGlobalScope ? 1 : 0) ? 2 : 1;";
        if (!source.includes(minimumDistanceReturn)) {
          throw new Error(
            "The diagnostic Worker has no replaceable minimum view-distance method",
          );
        }
        source = source.replace(
          minimumDistanceReturn,
          "return 2;",
        );
      }
      if (process.env.GAIUS_SMOKE_CAPTURE_JAVA_ERRORS === "1") {
        const loggerErrorStart =
          "osh_AbstractLogger_error = ($this, $format, $arg1, $arg2) => {";
        const instrumentedLoggerErrorStart = `${loggerErrorStart}\n` +
          "    if ($arg2 && $arg2.$jsException && $arg2.$jsException.stack) {\n" +
          "        globalThis.postMessage({\n" +
          "            type: 'node-java-error-stack',\n" +
          "            detail: String($arg2.$jsException.stack)\n" +
          "        });\n" +
          "    }";
        if (!source.includes(loggerErrorStart)) {
          throw new Error(
            "The diagnostic Worker has no instrumentable logger error method",
          );
        }
        source = source.replace(loggerErrorStart, instrumentedLoggerErrorStart);
      }
      if (process.env.GAIUS_SMOKE_UNBOUNDED_DISTANCE_MANAGER === "1") {
        const boundedDistanceManagerBudget =
          "return Math.max(8, Math.min(512, Math.floor(configured)));";
        if (!source.includes(boundedDistanceManagerBudget)) {
          throw new Error(
            "The diagnostic Worker has no replaceable distance-manager budget",
          );
        }
        source = source.replace(
          boundedDistanceManagerBudget,
          "return Math.max(8, Math.floor(configured));",
        );
      }
      if (process.env.GAIUS_SMOKE_DEFER_DISTANCE_MANAGER_FUTURES === "1") {
        const distanceUpdateTelemetry =
          "$rt_java.dgb_BrowserWorldgenScheduler_recordDistanceManagerUpdatesJs$js_body$_17(" +
          "$rt_java.dgb_BrowserWorldgenScheduler_lastDistanceManagerUpdateBudget, " +
          "jl_Math_max(0, $updates));";
        const deferAfterTelemetry = `${distanceUpdateTelemetry}\n` +
          "        if (!nmwll_LeveledPriorityQueue_isEmpty($chunk.$priorityQueue)) {\n" +
          "            return 1;\n" +
          "        }";
        if (!source.includes(distanceUpdateTelemetry)) {
          throw new Error(
            "The diagnostic Worker has no instrumentable distance-manager telemetry",
          );
        }
        source = source.replace(distanceUpdateTelemetry, deferAfterTelemetry);
      }
      vm.runInThisContext(source, {filename: path});
    }
  };
  globalThis.XMLHttpRequest = class NodeSmokeXmlHttpRequest {
    readyState = 0;
    status = 0;
    statusText = "";
    response = new ArrayBuffer(0);
    responseType = "";
    onreadystatechange = null;

    open() {
      this.readyState = 1;
    }

    setRequestHeader() {}

    getAllResponseHeaders() {
      return "";
    }

    send() {
      queueMicrotask(() => {
        parentPort.postMessage({type: "node-xhr-request"});
        this.status = 404;
        this.statusText = "Not Found";
        this.response = new ArrayBuffer(0);
        this.readyState = 4;
        this.onreadystatechange?.();
      });
    }
  };
  globalThis.indexedDB = createMemoryIndexedDb();
}

function createMemoryIndexedDb() {
  return {
    open() {
      const request = {};
      queueMicrotask(() => {
        const database = {
          objectStoreNames: {contains: () => false},
          createObjectStore() {},
          close() {},
          transaction() {
            const transaction = {
              objectStore() {
                return {
                  openCursor() {
                    const cursorRequest = {};
                    queueMicrotask(() => {
                      cursorRequest.result = null;
                      cursorRequest.onsuccess?.();
                    });
                    return cursorRequest;
                  },
                  put(record) {
                    const value = record && record.value;
                    const bytes = value && value.encoding === "gzip"
                      ? value.bytes
                      : value;
                    parentPort.postMessage({
                      type: "node-idb-put",
                      path: String(record && record.path || ""),
                      encoding: value && value.encoding || typeof value,
                      bytes: bytes && bytes.byteLength || 0,
                    });
                  },
                  delete() {},
                };
              },
            };
            queueMicrotask(() => transaction.oncomplete?.());
            return transaction;
          },
        };
        request.result = database;
        request.onupgradeneeded?.();
        request.onsuccess?.();
      });
      return request;
    },
  };
}
