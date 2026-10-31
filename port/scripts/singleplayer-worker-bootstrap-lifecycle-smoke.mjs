import {CANONICAL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {MessageChannel, Worker} from "node:worker_threads";

const bootstrapPath = new URL("../web/singleplayer/server-worker-bootstrap.js", import.meta.url);
const bootstrap = await readFile(bootstrapPath, "utf8");
assert.ok(bootstrap.includes('message.type === "diagnostic-config"'),
  "Worker bootstrap lost the pre-start diagnostic config message");
assert.ok(bootstrap.includes("root.__gaiusMobAiTelemetry = true"),
  "Worker bootstrap does not enable Mob AI telemetry from diagnostic config");
assert.ok(bootstrap.includes("root.__gaiusDebug = true"),
  "Worker bootstrap does not enable debug diagnostics from diagnostic config");
for (const field of [
  "pumpAllTurns",
  "pumpAllChannelsVisited",
  "pumpAllBudgetYields",
  "pumpAllMaxTurnMillis",
  "pumpAllMaxChannelsPerTurn",
  "pumpAllLastTurnMillis",
  "pumpAllLastChannelsVisited",
]) {
  assert.ok(bootstrap.includes(field),
    `Worker bootstrap lost global-pump telemetry field: ${field}`);
}
assert.ok(bootstrap.includes("globalPump: snapshotGlobalPumpTelemetry"),
  "Worker heartbeat did not expose the fixed globalPump side-band");
assert.ok(bootstrap.includes('message.type === "diagnostic-snapshot"'),
  "Worker bootstrap lost the diagnostic snapshot message");
const sessionId = "5123456789abcdef0123456789abcdef";
const initialGameRulesText = '{"minecraft:random_tick_speed":7,"minecraft:advance_time":false}';
const storageConfig = Object.freeze({
  profileId: CANONICAL_PROFILE.id,
  worldVersion: 4903,
  storageSchema: 2,
  storageDatabaseName: "gaius-fs-v2-" + CANONICAL_PROFILE.id + "",
  storagePrefix: "gaius.fs.v2:" + CANONICAL_PROFILE.id + ":",
  storageOpfsDirectory: "regions-v2-" + CANONICAL_PROFILE.id + "",
});

const harness = `
const {parentPort, MessagePort, workerData} = require("node:worker_threads");
const {performance} = require("node:perf_hooks");
const testConfig = workerData || {};
process.on("uncaughtException", (error) => {
  parentPort.postMessage({type: "test-worker-error", detail: String(error)});
});
process.on("unhandledRejection", (error) => {
  parentPort.postMessage({type: "test-worker-error", detail: String(error)});
});
globalThis.MessagePort = MessagePort;
globalThis.performance = performance;
globalThis.__gaiusStorageFlushWatchdogMillis = testConfig.flushWatchdogMillis;
globalThis.__gaiusStorageFlushAbortSettleMillis = testConfig.flushAbortSettleMillis;
globalThis.__gaiusStorageStartupSettleMillis = testConfig.storageStartupSettleMillis;
globalThis.__gaiusRuntimeStopWatchdogMillis = testConfig.runtimeStopWatchdogMillis;
globalThis.location = new URL("file:///Downloads/singleplayer-server-worker.js");
globalThis.postMessage = (message, transfer) => parentPort.postMessage(message, transfer || []);
globalThis.close = () => parentPort.postMessage({type: "harness-close"});
globalThis.importScripts = () => {};
// This is the actual bootstrap launch boundary.  The event records the value
// seen by main(), rather than proving the diagnostic-config branch by source
// text alone.  The parent sends diagnostic-config before start below, so a
// true value here proves the pre-start dispatch order and the live handler.
globalThis.main = () => parentPort.postMessage({
  type: "main-start-observed",
  serverTickTelemetryEnabled: globalThis.__gaiusServerTickTelemetryEnabled === true,
  diagnosticsEnabled: globalThis.__gaiusDebug === true,
  structurePreloadIds: globalThis.__gaiusStructurePreloadIds || [],
  structurePreloadBudgetMillis: globalThis.__gaiusStructurePreloadBudgetMillis,
  // The game rules Java reads when it builds the DedicatedServer (BrowserIntegratedServerMain
  // .initialGameRules): they must be in place by the time main() runs.
  initialGameRules: globalThis.__gaiusServerInitialGameRules,
});
globalThis.__gaiusStartIntegratedServerPump = () => {};
// A parked prewarm: the Java side (BrowserIntegratedServerMain.main/resumeAfterBootstrap) runs
// vanilla Main through Bootstrap, then polls __gaiusServerParkReleased before it reads the world.
const observedMain = globalThis.main;
const installParkedMain = () => {
  globalThis.main = () => {
    const parked = globalThis.__gaiusServerParkMain === true;
    parentPort.postMessage({type: "test-main-call", parked});
    if (!parked) return observedMain();
    if (testConfig.parkedMainUnavailable) {
      // Java main in a frame that cannot suspend: it runs nothing and returns at once.
      globalThis.__gaiusServerParkedState = "unavailable";
      return;
    }
    parentPort.postMessage({
      type: "parked-main-bootstrap",
      worldId: String(globalThis.__gaiusServerWorldId || ""),
      clientPort: Boolean(globalThis.__gaiusServerClientPort),
      storage: typeof globalThis.__gaiusFsPut === "function",
    });
    if (testConfig.parkedMainFails) {
      globalThis.__gaiusServerParkedState = "failed";
      globalThis.__gaiusServerParkedFailure = "deterministic Bootstrap failure";
      return;
    }
    globalThis.__gaiusServerParkedState = "parked";
    const timer = setInterval(() => {
      if (globalThis.__gaiusServerParkReleased !== true) return;
      clearInterval(timer);
      globalThis.__gaiusServerParkedState = "resumed";
      parentPort.postMessage({
        type: "parked-main-resumed",
        worldId: String(globalThis.__gaiusServerWorldId || ""),
        initialGameRules: globalThis.__gaiusServerInitialGameRules,
        clientPort: Boolean(globalThis.__gaiusServerClientPort),
        storage: typeof globalThis.__gaiusFsPut === "function",
      });
    }, 2);
  };
};
if (testConfig.parkedMain) {
  globalThis.integratedServerParkSupported = () => true;
  installParkedMain();
  // A fresh runtime after a failed parked prewarm defines main() again when it is imported.
  globalThis.importScripts = () => {
    if (typeof globalThis.main !== "function") {
      globalThis.main = () => {
        parentPort.postMessage({type: "test-main-call",
          parked: globalThis.__gaiusServerParkMain === true});
        observedMain();
      };
      globalThis.__gaiusStartIntegratedServerPump = () => {};
    }
  };
}
globalThis.setIntegratedServerDistances = (viewDistance, simulationDistance) => {
  parentPort.postMessage({
    type: "test-distances-applied",
    viewDistance,
    simulationDistance,
  });
};
let runtimeStopped = false;
if (!testConfig.missingStopIntegratedServer) {
  globalThis.stopIntegratedServer = () => {
    if (testConfig.runtimeStopNeverStops) return;
    if (!testConfig.runtimeStopSave) {
      runtimeStopped = true;
      return;
    }
    setTimeout(() => {
      const putAccepted = Boolean(globalThis.__gaiusFsPut?.(
        "/gaius/saves/runtime-stop-save/level.dat",
        "final save from integrated server stop",
      ));
      const putBytesAccepted = Boolean(globalThis.__gaiusFsPutBytes?.(
        "/gaius/saves/runtime-stop-save/uid.dat",
        new Uint8Array([7, 8, 9]),
      ));
      const flush = typeof globalThis.__gaiusFsFlush === "function"
        ? globalThis.__gaiusFsFlush()
        : Promise.reject(new Error("final save flush callback missing"));
      Promise.resolve(flush).then(
        () => {
          runtimeStopped = true;
          parentPort.postMessage({
            type: "runtime-stop-save",
            putAccepted,
            putBytesAccepted,
          });
        },
        (error) => {
          parentPort.postMessage({
            type: "runtime-stop-save",
            putAccepted: false,
            putBytesAccepted: false,
            detail: String(error),
          });
        },
      );
    }, Math.max(0, Number(testConfig.runtimeStopDelayMillis) || 20));
  };
}
if (!testConfig.missingIsIntegratedServerStopped) {
  globalThis.isIntegratedServerStopped = () => runtimeStopped;
}
if (Number.isFinite(Number(testConfig.worldgenDrainDelayMillis))) {
  globalThis.GaiusWorldgenKernel = {
    drain(capMillis) {
      parentPort.postMessage({type: "worldgen-drain-started", capMillis});
      return new Promise((resolve) => setTimeout(() => {
        parentPort.postMessage({type: "worldgen-drain-complete"});
        resolve(true);
      }, Math.max(0, Number(testConfig.worldgenDrainDelayMillis))));
    },
  };
}
globalThis.__gaiusChunkPriorityStats = {
  queued: 7,
  longestTaskMillis: 12.5,
  active: true,
  phase: "terrain",
  invalidNumber: Infinity,
  nested: {mustNotCross: true},
  typed: new Uint8Array([1, 2, 3]),
};
globalThis.__gaiusNetworkStats = {
  receivedFrames: 3,
  longestInboundSlicePumpMillis: 8,
  queuedBytes: 128,
  peakInboundQueuedBytes: 1024,
  decodedPacketQueue: 2,
  maxDecodedPacketQueue: 3,
  decodedPacketDrainSignals: 7,
  queuedPacketHandleSamples: 6,
  maxQueuedPacketHandleMillis: 412.5,
  maxQueuedPacketHandleType:
    "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Pos",
  inlineDecodedPackets: 9,
  pumpCalls: 11,
  pumpChunks: 12,
  pumpBytes: 13,
  peakPumpMillis: 4,
  eventLoopGapSamples: 7,
  activeHighWatermarks: 1,
  highWatermarkDurationMillis: 10,
  connected: true,
  optional: null,
  nested: {mustNotCross: true},
};
globalThis.__gaiusWorldgenStats = {
  heartbeatCount: 19,
  longestHeartbeatDelayMillis: 14,
  sliceMillis: 16,
  healthy: true,
  nested: {mustNotCross: true},
};
for (let index = 0; index < 70; index++) {
  globalThis.__gaiusWorldgenStats["prefix" + index] = index;
}
globalThis.__gaiusWorldgenStats.chunkHolderProbeMaxDurationMillis = 3028;
globalThis.__gaiusWorldgenStats.chunkHolderProbeSamples = 80;
globalThis.__gaiusWorldgenStats.chunkHolderProbeMaxContext =
  JSON.stringify({status: "holder", x: 1, z: 2, needsGeneration: true,
    durationMillis: 3028, result: true});
globalThis.__gaiusWorldgenSchedulerMarker = {
  schemaVersion: 1,
  maxSliceContext: "ctx-" + "x".repeat(900),
  finite: 42,
  nonfinite: Infinity,
  nested: {mustNotCross: true},
  callable: () => "must-not-cross",
};
globalThis.__gaiusWorldgenSchedulerMarker.maxTaskContext =
  JSON.stringify({taskLabel: "ChunkGenerationTask.runUntilWait", taskScopeWallMillis: 3028});
globalThis.__gaiusFuturePumpTelemetry = {
  polls: 7,
  emptyYields: 2,
  nested: {mustNotCross: true},
  nonfinite: NaN,
  callable: function forbidden() {},
};
globalThis.__gaiusServerTickTelemetry = {
  schemaVersion: 1,
  tickCount: 11,
  nested: {mustNotCross: true},
  nonfinite: -Infinity,
};
globalThis.__gaiusServerDistanceTelemetry = {
  configuredViewDistance: 6,
  activeViewDistance: 2,
  acknowledgedChunkCount: 8,
  reason: "awaiting-active-radius-ack",
  nested: {mustNotCross: true},
  nonfinite: Infinity,
};
Object.defineProperty(globalThis.__gaiusWorldgenSchedulerMarker, "throws", {
  enumerable: true,
  get() { throw new Error("diagnostic getter probe"); },
});
if (testConfig.assetFailure) {
  globalThis.DecompressionStream = function() {};
  globalThis.fetch = () => Promise.reject(new Error("deterministic asset failure"));
}
globalThis.indexedDB = {
  open() {
    const request = {};
    parentPort.postMessage({type: "test-db-open"});
    setTimeout(() => {
      let transactionCount = 0;
      const database = {
        objectStoreNames: {contains: () => true},
        close() {
          parentPort.postMessage({type: "test-db-close"});
        },
        transaction() {
          const transaction = {};
          const transactionIndex = transactionCount++;
          const configuredDelays = Array.isArray(testConfig.transactionDelays)
            ? testConfig.transactionDelays
            : [];
          const configuredDelay = configuredDelays[transactionIndex];
          const transactionDelay = configuredDelay === undefined
            ? Number(testConfig.transactionDelayMillis) || 0
            : Number(configuredDelay) || 0;
          let finished = false;
          let completionTimer;
          const store = {
            delete() {},
            put() {},
            get() {
              const request = {};
              queueMicrotask(() => {
                request.result = undefined;
                request.onsuccess?.();
              });
              return request;
            },
            get() {
              const request = {};
              queueMicrotask(() => {
                request.result = undefined;
                request.onsuccess?.();
              });
              return request;
            },
            openCursor() {
              const cursorRequest = {};
              queueMicrotask(() => {
                cursorRequest.result = null;
                cursorRequest.onsuccess?.();
              });
              return cursorRequest;
            },
          };
          transaction.objectStore = () => store;
          const finish = () => {
            if (finished) return;
            finished = true;
            transaction.oncomplete?.();
          };
          completionTimer = setTimeout(
            finish,
            Math.max(0, transactionDelay),
          );
          transaction.abort = () => {
            parentPort.postMessage({type: "test-tx-abort"});
            if (finished) return;
            if (testConfig.abortNeverSettles) return;
            finished = true;
            clearTimeout(completionTimer);
            setTimeout(
              () => transaction.onabort?.(),
              Math.max(0, Number(testConfig.abortDelayMillis) || 0),
            );
          };
          return transaction;
        },
      };
      request.result = database;
      request.onsuccess?.();
    }, Math.max(0, Number(testConfig.openDelayMillis) || 0));
    return request;
  },
};
${bootstrap}
parentPort.on("message", (message) => {
  if (message?.type === "test-preload-config") {
    parentPort.postMessage({type: "test-preload-config-result",
      ids: globalThis.__gaiusStructurePreloadIds || [],
      budget: globalThis.__gaiusStructurePreloadBudgetMillis});
    return;
  }
  if (message?.type === "mutate-telemetry") {
    Object.assign(globalThis.__gaiusWorldgenStats, message.worldgen || {});
    Object.assign(globalThis.__gaiusChunkPriorityStats, message.chunkPriority || {});
    Object.assign(globalThis.__gaiusNetworkStats, message.network || {});
    Object.assign(globalThis.__gaiusStorageStats, message.storage || {});
    return;
  }
  if (message?.type === "test-write") {
    const accepted = globalThis.__gaiusFsPut?.(
      "/gaius/saves/flush-race/level.dat",
      "deterministic flush race",
    );
    parentPort.postMessage({type: "test-write-done", accepted: Boolean(accepted)});
    return;
  }
  if (message?.type === "test-late-fs-put") {
    const installed = typeof globalThis.__gaiusFsPut === "function";
    const accepted = installed && globalThis.__gaiusFsPut(
      "/gaius/saves/late-asset/level.dat",
      "must not install after asset failure",
    );
    parentPort.postMessage({
      type: "test-late-fs-result",
      installed,
      accepted: Boolean(accepted),
    });
    return;
  }
  globalThis.onmessage({data: message, ports: message && message.port ? [message.port] : []});
});
`;

const worker = new Worker(harness, {eval: true, workerData: {}});
const events = [];
let waiter;
worker.on("message", (message) => {
  events.push(message);
  waiter?.();
});

async function waitFor(type, timeoutMillis = 1000) {
  const deadline = Date.now() + timeoutMillis;
  while (true) {
    const event = events.find((message) => message && message.type === type);
    if (event) return event;
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${type}`);
    await new Promise((resolve) => {
      const timeout = setTimeout(resolve, 5);
      waiter = () => {
        clearTimeout(timeout);
        waiter = undefined;
        resolve();
      };
    });
  }
}

async function waitForTelemetry(sequence, timeoutMillis = 1000) {
  const deadline = Date.now() + timeoutMillis;
  while (true) {
    const event = events.find((message) =>
      message && message.type === "telemetry-pong" && message.sequence === sequence);
    if (event) return event;
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for telemetry ${sequence}`);
    await new Promise((resolve) => {
      const timeout = setTimeout(resolve, 5);
      waiter = () => {
        clearTimeout(timeout);
        waiter = undefined;
        resolve();
      };
    });
  }
}

async function waitForWorkerEvent(events, type, timeoutMillis = 1000) {
  const deadline = Date.now() + timeoutMillis;
  while (true) {
    const event = events.find((message) => message && message.type === type);
    if (event) return event;
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${type}`);
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

function assertNoWorkerErrors(events, detail) {
  assert.equal(
    events.some((message) => message?.type === "test-worker-error"),
    false,
    detail,
  );
}

const invalidGenerationWorker = new Worker(harness, {eval: true, workerData: {}});
const invalidGenerationEvents = [];
invalidGenerationWorker.on("message", (message) => invalidGenerationEvents.push(message));
const invalidGenerationChannel = new MessageChannel();
invalidGenerationChannel.port2.start();
const invalidGenerationPortClosed = new Promise((resolve) => {
  invalidGenerationChannel.port2.once("message", resolve);
});
invalidGenerationWorker.postMessage({
  type: "start",
  sessionId: "invalid-generation-session",
  launchGeneration: "0",
  port: invalidGenerationChannel.port1,
}, [invalidGenerationChannel.port1]);
await waitForWorkerEvent(invalidGenerationEvents, "start-invalid-generation");
assert.deepEqual(await invalidGenerationPortClosed, {type: "close"},
  "invalid launch generation did not close its transferred MessagePort");
await new Promise((resolve) => setTimeout(resolve, 20));
assert.equal(invalidGenerationEvents.some((event) =>
  event?.type === "port-waiting" || event?.type === "test-db-open" ||
  event?.type === "runtime-ready"), false,
"invalid launch generation was accepted by the Worker bootstrap");
assertNoWorkerErrors(invalidGenerationEvents,
  "invalid launch generation raised a Worker harness error");
invalidGenerationChannel.port2.close();
await invalidGenerationWorker.terminate();

// Configuration sent before start must survive the bootstrap handshake.
worker.postMessage({
  type: "diagnostic-config",
  gaiusDebug: true,
  gaiusSlowProbeTelemetry: true,
  gaiusServerTickTelemetry: true,
  gaiusStructurePreloadIds: ["minecraft:village/plains/houses/plains_small_house_1",
    "INVALID", 42, ...Array.from({length: 70}, (_, i) => `test:template_${i}`)],
  gaiusStructurePreloadBudgetMillis: 999999,
});

worker.postMessage({
  type: "start",
  sessionId,
  launchGeneration: "1",
  worldId: "portable-lifecycle-world",
  newWorld: true,
  initialGameRules: initialGameRulesText,
  profileId: storageConfig.profileId,
  worldVersion: storageConfig.worldVersion,
  storageSchema: storageConfig.storageSchema,
  storageDatabaseName: storageConfig.storageDatabaseName,
  storagePrefix: storageConfig.storagePrefix,
  storageOpfsDirectory: storageConfig.storageOpfsDirectory,
  renderDistance: 6,
  simulationDistance: 4,
  serverScriptUrl: "file:///Downloads/singleplayer-server.js",
});
await waitFor("port-waiting");

const activeChannel = new MessageChannel();
const activeMessages = [];
activeChannel.port2.on("message", (message) => activeMessages.push(message));
activeChannel.port2.start();
worker.postMessage({type: "attach-port", sessionId, port: activeChannel.port1},
  [activeChannel.port1]);
await waitFor("port-attached");
const mainStartObserved = await waitFor("main-start-observed");
assert.equal(mainStartObserved.initialGameRules, initialGameRulesText,
  "bootstrap main() did not observe the game rules the page sent in the start message");
assert.equal(mainStartObserved.serverTickTelemetryEnabled, true,
  "bootstrap main() did not observe server tick telemetry enabled by pre-start diagnostic-config");
assert.equal(mainStartObserved.diagnosticsEnabled, true,
  "bootstrap main() did not observe debug diagnostics enabled by pre-start diagnostic-config");
assert.equal(mainStartObserved.structurePreloadIds.length, 64,
  "preload config must filter invalid identifiers and cap accepted IDs");
assert.equal(mainStartObserved.structurePreloadIds[0],
  "minecraft:village/plains/houses/plains_small_house_1");
assert.equal(mainStartObserved.structurePreloadIds[63], "test:template_62");
assert.equal(mainStartObserved.structurePreloadBudgetMillis, 30000);
worker.postMessage({type: "diagnostic-config", gaiusStructurePreloadIds: ["test:too_late"],
  gaiusStructurePreloadBudgetMillis: 1});
worker.postMessage({type: "test-preload-config"});
const retainedPreloadConfig = await waitFor("test-preload-config-result");
assert.deepEqual(retainedPreloadConfig.ids, mainStartObserved.structurePreloadIds,
  "late diagnostic messages must not change startup preload selection");
assert.equal(retainedPreloadConfig.budget, 30000);
await waitFor("runtime-ready");

async function requestDiagnosticSnapshot() {
  const before = events.length;
  worker.postMessage({type: "diagnostic-snapshot"});
  const deadline = Date.now() + 1000;
  while (true) {
    const event = events.slice(before).find((message) =>
      message?.type === "diagnostic-snapshot");
    if (event) return event;
    if (Date.now() >= deadline) throw new Error("Timed out waiting for diagnostic snapshot");
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

const preStartEnabledSnapshot = await requestDiagnosticSnapshot();
assert.equal(preStartEnabledSnapshot.enabled, true,
  "pre-start diagnostic config did not survive Worker startup");
for (const key of [
  "worldgenSchedulerMarker",
  "futurePumpTelemetry",
  "worldgenStats",
  "serverTickTelemetry",
]) {
  assert.ok(preStartEnabledSnapshot[key] && typeof preStartEnabledSnapshot[key] === "object",
    `enabled diagnostic snapshot omitted ${key}`);
  assert.equal(Object.values(preStartEnabledSnapshot[key]).some((value) =>
    value && typeof value === "object"), false,
  `enabled diagnostic snapshot crossed nested value in ${key}`);
  assert.equal(Object.values(preStartEnabledSnapshot[key]).some((value) =>
    typeof value === "number" && !Number.isFinite(value)), false,
  `enabled diagnostic snapshot crossed non-finite value in ${key}`);
}
assert.ok(preStartEnabledSnapshot.worldgenSchedulerMarker.maxSliceContext.length >= 512,
  "maxSliceContext was truncated below the diagnostic context minimum");
assert.ok(preStartEnabledSnapshot.worldgenSchedulerMarker.maxTaskContext,
  "maxTaskContext was dropped despite diagnostic priority");
assert.ok(preStartEnabledSnapshot.worldgenStats.chunkHolderProbeMaxContext,
  "chunkHolderProbeMaxContext was dropped despite diagnostic priority");
assert.equal(preStartEnabledSnapshot.worldgenStats.chunkHolderProbeMaxDurationMillis, 3028,
  "chunk holder maximum duration did not survive the diagnostic priority cap");
assert.ok(JSON.stringify(preStartEnabledSnapshot.worldgenSchedulerMarker).length <= 4096,
  "scheduler marker exceeded its bounded serialized length");
assert.ok(JSON.stringify(preStartEnabledSnapshot.futurePumpTelemetry).length <= 4096,
  "future pump telemetry exceeded its bounded serialized length");
assert.ok(JSON.stringify(preStartEnabledSnapshot.worldgenStats).length <= 4096,
  "worldgen stats exceeded its bounded serialized length");
assert.ok(JSON.stringify(preStartEnabledSnapshot.serverTickTelemetry).length <= 4096,
  "server tick telemetry exceeded its bounded serialized length");

worker.postMessage({type: "diagnostic-config", gaiusSlowProbeTelemetry: false});
const disabledDiagnosticSnapshot = await requestDiagnosticSnapshot();
assert.deepEqual(disabledDiagnosticSnapshot, {
  type: "diagnostic-snapshot",
  enabled: false,
}, "disabled diagnostic snapshot leaked details after shutdown");

worker.postMessage({type: "distances", renderDistance: 5, simulationDistance: 3});
const activeDistanceApply = await waitFor("test-distances-applied");
assert.deepEqual({...activeDistanceApply}, {
  type: "test-distances-applied",
  viewDistance: 5,
  simulationDistance: 3,
}, "active Worker rejected a distance update");

worker.postMessage({type: "telemetry-ping", sessionId, sequence: 17, measurementId: "measurement-a"});
const telemetryPong = await waitForTelemetry(17);
assert.equal(telemetryPong.sequence, 17, "heartbeat sequence was not preserved");
assert.equal(telemetryPong.measurementId, "measurement-a");
assert.ok(telemetryPong.workerResetAt > 0, "Worker did not expose its measurement reset time");
assert.deepEqual({...telemetryPong.globalPump}, {
  pumpAllTurns: null,
  pumpAllChannelsVisited: null,
  pumpAllBudgetYields: null,
  pumpAllMaxTurnMillis: null,
  pumpAllMaxChannelsPerTurn: null,
  pumpAllLastTurnMillis: null,
  pumpAllLastChannelsVisited: null,
}, "Worker heartbeat did not expose a fixed globalPump shape");
assert.deepEqual({...telemetryPong.serverDistance}, {
  configuredViewDistance: 6,
  activeViewDistance: 2,
  acknowledgedChunkCount: 8,
  reason: "awaiting-active-radius-ack",
}, "Worker distance snapshot must preserve gauges without resetting or copying nested values");
assert.deepEqual({...telemetryPong.chunkPriority}, {
  playerUpdates: 0,
  pops: 0,
  reorderedPops: 0,
  scannedCandidates: 0,
  maxCandidates: 0,
}, "new measurement did not reset chunk-priority telemetry");
assert.deepEqual({...telemetryPong.network}, {
  receivedFrames: 0,
  longestInboundSlicePumpMillis: 8,
  queuedBytes: 128,
  peakInboundQueuedBytes: 1024,
  decodedPacketQueue: 2,
  maxDecodedPacketQueue: 3,
  decodedPacketDrainSignals: 0,
  queuedPacketHandleSamples: 0,
  maxQueuedPacketHandleMillis: 412.5,
  maxQueuedPacketHandleType:
    "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Pos",
  inlineDecodedPackets: 0,
  pumpCalls: 0,
  pumpChunks: 0,
  pumpBytes: 0,
  peakPumpMillis: 4,
  eventLoopGapSamples: 0,
  activeHighWatermarks: 1,
  highWatermarkDurationMillis: 10,
  connected: true,
  optional: null,
}, "Worker network snapshot crossed non-scalar values");
assert.deepEqual({...telemetryPong.worldgen}, {},
  "new measurement did not reset worldgen telemetry");
assert.equal(telemetryPong.storage.backend, "indexeddb-worker-lru");
assert.equal(telemetryPong.storage.cacheBytes, 0);
assert.equal(telemetryPong.storage.cacheEntries, 0);
assert.equal(telemetryPong.storage.evictions, 0);

worker.postMessage({
  type: "mutate-telemetry",
  worldgen: {slices: 3, healthy: true},
  chunkPriority: {pops: 4, playerChunk: "2,3"},
  network: {
    receivedFrames: 5,
    longestInboundSlicePumpMillis: 12,
    queuedBytes: 256,
    decodedPacketDrainSignals: 11,
    queuedPacketHandleSamples: 10,
    maxQueuedPacketHandleMillis: 6188.6,
    maxQueuedPacketHandleType:
      "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$PosRot",
    inlineDecodedPackets: 12,
  },
  storage: {opfsFlushes: 2},
});
worker.postMessage({type: "telemetry-ping", sessionId, sequence: 18, measurementId: "measurement-a"});
const sameMeasurementPong = await waitForTelemetry(18);
assert.equal(sameMeasurementPong.workerResetAt, telemetryPong.workerResetAt,
  "same measurement ID reset the Worker more than once");
assert.equal(sameMeasurementPong.worldgen.slices, 3,
  "same measurement ID discarded worldgen telemetry");
assert.equal(sameMeasurementPong.chunkPriority.pops, 4,
  "same measurement ID discarded chunk-priority telemetry");
assert.equal(sameMeasurementPong.network.receivedFrames, 2,
  "network cumulative telemetry did not use a measurement baseline");
assert.equal(sameMeasurementPong.network.longestInboundSlicePumpMillis, 12,
  "network extrema were incorrectly baselined as counters");
assert.equal(sameMeasurementPong.network.queuedBytes, 256,
  "network queue gauge was reset instead of preserved");
assert.equal(sameMeasurementPong.network.decodedPacketDrainSignals, 4,
  "queued packet completions did not use the measurement baseline");
assert.equal(sameMeasurementPong.network.queuedPacketHandleSamples, 4,
  "queued packet handler samples did not use the measurement baseline");
assert.equal(sameMeasurementPong.network.maxQueuedPacketHandleMillis, 6188.6,
  "queued packet maximum handler duration was not preserved as an extremum");
assert.equal(sameMeasurementPong.network.maxQueuedPacketHandleType,
  "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$PosRot",
  "queued packet maximum handler type was not preserved");
assert.equal(sameMeasurementPong.network.inlineDecodedPackets, 3,
  "inline decoder count did not use the measurement baseline");
assert.equal(sameMeasurementPong.storage.opfsFlushes, 2,
  "storage cumulative telemetry did not use a measurement baseline");

worker.postMessage({type: "telemetry-ping", sessionId, sequence: 19, measurementId: "measurement-b"});
const newMeasurementPong = await waitForTelemetry(19);
assert.equal(newMeasurementPong.measurementId, "measurement-b");
assert.ok(newMeasurementPong.workerResetAt > sameMeasurementPong.workerResetAt,
  "new measurement ID did not reset the Worker");
assert.deepEqual({...newMeasurementPong.worldgen}, {},
  "new measurement retained startup worldgen telemetry");
assert.deepEqual({...newMeasurementPong.chunkPriority}, {
  playerUpdates: 0,
  pops: 0,
  reorderedPops: 0,
  scannedCandidates: 0,
  maxCandidates: 0,
}, "new measurement retained startup chunk-priority telemetry");
assert.equal(newMeasurementPong.network.receivedFrames, 0,
  "network cumulative baseline was not advanced for the new measurement");
assert.equal(newMeasurementPong.network.longestInboundSlicePumpMillis, 12,
  "network extrema changed during measurement reset");
assert.equal(newMeasurementPong.network.queuedBytes, 256,
  "network gauge changed during measurement reset");
assert.equal(newMeasurementPong.network.decodedPacketDrainSignals, 0,
  "new measurement retained queued packet completions");
assert.equal(newMeasurementPong.network.queuedPacketHandleSamples, 0,
  "new measurement retained queued packet handler samples");
assert.equal(newMeasurementPong.network.maxQueuedPacketHandleMillis, 6188.6,
  "new measurement reset the queued packet duration extremum");
assert.equal(newMeasurementPong.network.maxQueuedPacketHandleType,
  "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$PosRot",
  "new measurement reset the queued packet duration type");
assert.equal(newMeasurementPong.network.inlineDecodedPackets, 0,
  "new measurement retained inline decoder events");
assert.equal(newMeasurementPong.storage.opfsFlushes, 0,
  "storage cumulative baseline was not advanced for the new measurement");

worker.postMessage({
  type: "mutate-telemetry",
  worldgen: {slices: 1},
  chunkPriority: {pops: 1},
});
worker.postMessage({type: "telemetry-ping", sessionId, sequence: 20, measurementId: "measurement-b"});
const repeatedNewMeasurementPong = await waitForTelemetry(20);
assert.equal(repeatedNewMeasurementPong.workerResetAt, newMeasurementPong.workerResetAt,
  "repeated new measurement ping reset more than once");
assert.equal(repeatedNewMeasurementPong.worldgen.slices, 1);
assert.equal(repeatedNewMeasurementPong.chunkPriority.pops, 1);

// Deliberately put more than the generic 72 scalar slots ahead of pumpAll*.
// The network snapshot must stay capped, while the fixed side-band remains
// complete and numeric.
const capProbeNetwork = {};
for (let index = 0; index < 78; index++) {
  capProbeNetwork[`capProbe${index}`] = index;
}
Object.assign(capProbeNetwork, {
  pumpAllTurns: 77,
  pumpAllChannelsVisited: 123,
  pumpAllBudgetYields: 9,
  pumpAllMaxTurnMillis: 3.5,
  pumpAllMaxChannelsPerTurn: 8,
  pumpAllLastTurnMillis: 1.25,
  pumpAllLastChannelsVisited: 7,
});
worker.postMessage({type: "mutate-telemetry", network: capProbeNetwork});
worker.postMessage({type: "telemetry-ping", sessionId, sequence: 21, measurementId: "measurement-b"});
const cappedNetworkPong = await waitForTelemetry(21);
assert.equal(cappedNetworkPong.network.pumpAllTurns, undefined,
  "generic network snapshot leaked a capped global-pump field");
assert.deepEqual({...cappedNetworkPong.globalPump}, {
  pumpAllTurns: 77,
  pumpAllChannelsVisited: 123,
  pumpAllBudgetYields: 9,
  pumpAllMaxTurnMillis: 3.5,
  pumpAllMaxChannelsPerTurn: 8,
  pumpAllLastTurnMillis: 1.25,
  pumpAllLastChannelsVisited: 7,
}, "fixed globalPump side-band was truncated with network scalars");

const duplicateChannel = new MessageChannel();
const duplicateMessages = [];
duplicateChannel.port2.on("message", (message) => duplicateMessages.push(message));
duplicateChannel.port2.start();
worker.postMessage({
  type: "start",
  sessionId,
  launchGeneration: "1",
  worldId: "must-not-restart",
  profileId: storageConfig.profileId,
  worldVersion: storageConfig.worldVersion,
  storageSchema: storageConfig.storageSchema,
  storageDatabaseName: storageConfig.storageDatabaseName,
  storagePrefix: storageConfig.storagePrefix,
  storageOpfsDirectory: storageConfig.storageOpfsDirectory,
  port: duplicateChannel.port1,
}, [duplicateChannel.port1]);
await waitFor("start-duplicate-ignored");
await new Promise((resolve) => setTimeout(resolve, 10));
assert.deepEqual(duplicateMessages, [{type: "close"}],
  "duplicate start leaked its transferred MessagePort");

worker.postMessage({type: "stop"});
worker.postMessage({type: "distances", renderDistance: 6, simulationDistance: 4});
await waitFor("stopped");
await waitFor("harness-close");
await new Promise((resolve) => setTimeout(resolve, 10));
assert.ok(
  events.findIndex((message) => message?.type === "storage-flushing") <
    events.findIndex((message) => message?.type === "stopped"),
  "Worker acknowledged stop before its storage flush phase",
);
assert.deepEqual(activeMessages, [{type: "close"}],
  "Worker stop did not close the active local session port");
assert.equal(events.filter((message) => message?.type === "test-distances-applied").length, 1,
  "Worker applied a queued distance update after stop was requested");
assertNoWorkerErrors(events, "clean lifecycle emitted test-worker-error");

await worker.terminate();
activeChannel.port2.close();
duplicateChannel.port2.close();

// Runtime shutdown must leave the filesystem generation live until the
// integrated server's final save has run. Both text and byte writes must be
// accepted before the flush/close phase begins.
const runtimeStopSaveWorker = new Worker(harness, {
  eval: true,
  workerData: {runtimeStopSave: true},
});
const runtimeStopSaveEvents = [];
runtimeStopSaveWorker.on("message", (message) => runtimeStopSaveEvents.push(message));
runtimeStopSaveWorker.on("error", (error) => {
  runtimeStopSaveEvents.push({type: "test-worker-error", detail: String(error)});
});
const runtimeStopSaveChannel = new MessageChannel();
runtimeStopSaveWorker.postMessage({
  type: "start",
  sessionId: "runtime-stop-save-session",
  launchGeneration: "1",
  worldId: "runtime-stop-save-world",
  newWorld: true,
  profileId: storageConfig.profileId,
  worldVersion: storageConfig.worldVersion,
  storageSchema: storageConfig.storageSchema,
  storageDatabaseName: storageConfig.storageDatabaseName,
  storagePrefix: storageConfig.storagePrefix,
  storageOpfsDirectory: storageConfig.storageOpfsDirectory,
  renderDistance: 6,
  simulationDistance: 4,
  serverScriptUrl: "file:///Downloads/singleplayer-server.js",
  port: runtimeStopSaveChannel.port1,
}, [runtimeStopSaveChannel.port1]);
await waitForWorkerEvent(runtimeStopSaveEvents, "runtime-ready");
runtimeStopSaveWorker.postMessage({type: "stop"});
const runtimeStopSave = await waitForWorkerEvent(
  runtimeStopSaveEvents,
  "runtime-stop-save",
);
assert.equal(runtimeStopSave.putAccepted, true,
  "runtime stop invalidated text filesystem callback before final save");
assert.equal(runtimeStopSave.putBytesAccepted, true,
  "runtime stop invalidated byte filesystem callback before final save");
await waitForWorkerEvent(runtimeStopSaveEvents, "stopped");
await waitForWorkerEvent(runtimeStopSaveEvents, "harness-close");
const finalSaveIndex = runtimeStopSaveEvents.findIndex((message) =>
  message?.type === "runtime-stop-save");
const storageFlushingIndex = runtimeStopSaveEvents.findIndex((message) =>
  message?.type === "storage-flushing");
const runtimeDbCloseIndex = runtimeStopSaveEvents.findIndex((message) =>
  message?.type === "test-db-close");
const runtimeStoppedIndex = runtimeStopSaveEvents.findIndex((message) =>
  message?.type === "stopped");
assert.ok(finalSaveIndex >= 0 && finalSaveIndex < storageFlushingIndex,
  "runtime final save did not run before storage flush");
assert.ok(runtimeDbCloseIndex > storageFlushingIndex,
  "runtime database closed before final save flush phase");
assert.ok(runtimeStoppedIndex > runtimeDbCloseIndex,
  "runtime worker reported stopped before database close");
assertNoWorkerErrors(runtimeStopSaveEvents,
  "runtime final save emitted test-worker-error");
await runtimeStopSaveWorker.terminate();
runtimeStopSaveChannel.port2.close();

// Worldgen shutdown is part of the storage handoff.  A stop must not publish
// storage-flushing while a kernel drain is still settling Java-side futures.
const drainWorker = new Worker(harness, {
  eval: true,
  workerData: {worldgenDrainDelayMillis: 120},
});
const drainEvents = [];
drainWorker.on("message", (message) => drainEvents.push(message));
drainWorker.on("error", (error) => {
  drainEvents.push({type: "test-worker-error", detail: String(error)});
});
const drainChannel = new MessageChannel();
drainWorker.postMessage({
  type: "start",
  sessionId: "worldgen-drain-session",
  launchGeneration: "1",
  worldId: "worldgen-drain-world",
  newWorld: true,
  profileId: storageConfig.profileId,
  worldVersion: storageConfig.worldVersion,
  storageSchema: storageConfig.storageSchema,
  storageDatabaseName: storageConfig.storageDatabaseName,
  storagePrefix: storageConfig.storagePrefix,
  storageOpfsDirectory: storageConfig.storageOpfsDirectory,
  renderDistance: 6,
  simulationDistance: 4,
  serverScriptUrl: "file:///Downloads/singleplayer-server.js",
  port: drainChannel.port1,
}, [drainChannel.port1]);
await waitForWorkerEvent(drainEvents, "runtime-ready");
drainWorker.postMessage({type: "stop"});
await waitForWorkerEvent(drainEvents, "worldgen-drain-started");
await new Promise((resolve) => setTimeout(resolve, 30));
assert.equal(drainEvents.some((message) => message?.type === "storage-flushing"), false,
  "Worker entered storage flush before worldgen drain completed");
await waitForWorkerEvent(drainEvents, "worldgen-drain-complete");
await waitForWorkerEvent(drainEvents, "storage-flushing");
await waitForWorkerEvent(drainEvents, "stopped");
await waitForWorkerEvent(drainEvents, "harness-close");
assertNoWorkerErrors(drainEvents, "worldgen drain lifecycle emitted test-worker-error");
await drainWorker.terminate();
drainChannel.port2.close();

async function assertRuntimeStopFailure(workerData, label) {
  const failureWorker = new Worker(harness, {eval: true, workerData});
  const failureEvents = [];
  failureWorker.on("message", (message) => failureEvents.push(message));
  failureWorker.on("error", (error) => {
    failureEvents.push({type: "test-worker-error", detail: String(error)});
  });
  const failureChannel = new MessageChannel();
  failureWorker.postMessage({
    type: "start",
    sessionId: `${label}-session`,
    launchGeneration: "1",
    worldId: `${label}-world`,
    newWorld: true,
    profileId: storageConfig.profileId,
    worldVersion: storageConfig.worldVersion,
    storageSchema: storageConfig.storageSchema,
    storageDatabaseName: storageConfig.storageDatabaseName,
    storagePrefix: storageConfig.storagePrefix,
    storageOpfsDirectory: storageConfig.storageOpfsDirectory,
    renderDistance: 6,
    simulationDistance: 4,
    serverScriptUrl: "file:///Downloads/singleplayer-server.js",
    port: failureChannel.port1,
  }, [failureChannel.port1]);
  await waitForWorkerEvent(failureEvents, "runtime-ready");
  failureWorker.postMessage({type: "stop"});
  await waitForWorkerEvent(failureEvents, "runtime-stop-failed", 2000);
  await new Promise((resolve) => setTimeout(resolve, 100));
  assert.equal(failureEvents.some((message) => message?.type === "stopped"), false,
    `${label} incorrectly reported stopped`);
  assert.equal(failureEvents.some((message) => message?.type === "harness-close"), false,
    `${label} incorrectly closed the harness`);
  assert.equal(failureEvents.some((message) => message?.type === "storage-flushing"), false,
    `${label} flushed storage after runtime stop proof failed`);
  assert.equal(failureEvents.some((message) => message?.type === "test-db-close"), false,
    `${label} closed the database after runtime stop proof failed`);
  assertNoWorkerErrors(failureEvents, `${label} emitted test-worker-error`);
  await failureWorker.terminate();
  failureChannel.port2.close();
}

await assertRuntimeStopFailure(
  {missingStopIntegratedServer: true, runtimeStopWatchdogMillis: 50},
  "missing-stop-hook",
);
await assertRuntimeStopFailure(
  {missingIsIntegratedServerStopped: true, runtimeStopWatchdogMillis: 50},
  "missing-stopped-hook",
);
await assertRuntimeStopFailure(
  {runtimeStopNeverStops: true, runtimeStopWatchdogMillis: 75},
  "runtime-stop-timeout",
);

// A timed-out flush must first abort/settle its active transaction.  The
// backend handles are allowed to close only after the abort callback, never
// merely because the watchdog raced the original promise.
const flushRaceWorker = new Worker(harness, {
  eval: true,
  workerData: {
    transactionDelayMillis: 500,
    abortDelayMillis: 40,
    flushWatchdogMillis: 25,
    flushAbortSettleMillis: 300,
  },
});
const flushRaceEvents = [];
flushRaceWorker.on("message", (message) => flushRaceEvents.push(message));
flushRaceWorker.on("error", (error) => {
  flushRaceEvents.push({type: "test-worker-error", detail: String(error)});
});
const flushRaceChannel = new MessageChannel();
flushRaceWorker.postMessage({
  type: "start",
  sessionId: "flush-race-session",
  launchGeneration: "1",
  worldId: "flush-race-world",
  newWorld: true,
  profileId: storageConfig.profileId,
  worldVersion: storageConfig.worldVersion,
  storageSchema: storageConfig.storageSchema,
  storageDatabaseName: storageConfig.storageDatabaseName,
  storagePrefix: storageConfig.storagePrefix,
  storageOpfsDirectory: storageConfig.storageOpfsDirectory,
  renderDistance: 6,
  simulationDistance: 4,
  serverScriptUrl: "file:///Downloads/singleplayer-server.js",
  port: flushRaceChannel.port1,
}, [flushRaceChannel.port1]);
await waitForWorkerEvent(flushRaceEvents, "runtime-ready");
flushRaceWorker.postMessage({type: "test-write"});
const writeResult = await waitForWorkerEvent(flushRaceEvents, "test-write-done");
assert.equal(writeResult.accepted, true, "flush race write was not accepted");
await new Promise((resolve) => setTimeout(resolve, 60));
flushRaceWorker.postMessage({type: "stop"});
await waitForWorkerEvent(flushRaceEvents, "stopped", 2000);
await waitForWorkerEvent(flushRaceEvents, "harness-close", 2000);
const timeoutEvidence = flushRaceEvents.find((message) =>
  message?.type === "storage-write-error" &&
  String(message.detail || "").includes("Persistent storage flush timed out"));
assert.ok(timeoutEvidence, "flush watchdog timeout evidence was not preserved");
const abortIndex = flushRaceEvents.findIndex((message) => message?.type === "test-tx-abort");
const closeIndex = flushRaceEvents.findIndex((message) => message?.type === "test-db-close");
assert.ok(abortIndex >= 0, "flush watchdog did not request transaction abort");
assert.ok(closeIndex > abortIndex,
  "database closed before the in-flight flush transaction settled");
assertNoWorkerErrors(flushRaceEvents,
  "settled-abort retry emitted test-worker-error");
await flushRaceWorker.terminate();
flushRaceChannel.port2.close();

// If an IndexedDB abort callback never arrives, shutdown must leave the
// transaction and database alone. In particular, a watchdog race is not a
// license to emit stopped/close while persistence is still in flight.
const unsettledAbortWorker = new Worker(harness, {
  eval: true,
  workerData: {
    transactionDelayMillis: 500,
    abortNeverSettles: true,
    flushWatchdogMillis: 25,
    flushAbortSettleMillis: 50,
  },
});
const unsettledAbortEvents = [];
unsettledAbortWorker.on("message", (message) => unsettledAbortEvents.push(message));
unsettledAbortWorker.on("error", (error) => {
  unsettledAbortEvents.push({type: "test-worker-error", detail: String(error)});
});
const unsettledAbortChannel = new MessageChannel();
unsettledAbortWorker.postMessage({
  type: "start",
  sessionId: "unsettled-abort-session",
  launchGeneration: "1",
  worldId: "unsettled-abort-world",
  newWorld: true,
  profileId: storageConfig.profileId,
  worldVersion: storageConfig.worldVersion,
  storageSchema: storageConfig.storageSchema,
  storageDatabaseName: storageConfig.storageDatabaseName,
  storagePrefix: storageConfig.storagePrefix,
  storageOpfsDirectory: storageConfig.storageOpfsDirectory,
  renderDistance: 6,
  simulationDistance: 4,
  serverScriptUrl: "file:///Downloads/singleplayer-server.js",
  port: unsettledAbortChannel.port1,
}, [unsettledAbortChannel.port1]);
await waitForWorkerEvent(unsettledAbortEvents, "runtime-ready");
unsettledAbortWorker.postMessage({type: "test-write"});
await waitForWorkerEvent(unsettledAbortEvents, "test-write-done");
await new Promise((resolve) => setTimeout(resolve, 60));
unsettledAbortWorker.postMessage({type: "stop"});
await waitForWorkerEvent(unsettledAbortEvents, "storage-flush-failed", 2000);
await new Promise((resolve) => setTimeout(resolve, 100));
assert.equal(unsettledAbortEvents.some((message) => message?.type === "test-tx-abort"), true,
  "unsettled flush watchdog did not request transaction abort");
assert.equal(unsettledAbortEvents.some((message) => message?.type === "test-db-close"), false,
  "database closed while abort promise remained unsettled");
assert.equal(unsettledAbortEvents.some((message) => message?.type === "stopped"), false,
  "unsettled flush claimed a clean stop");
assert.equal(unsettledAbortEvents.some((message) => message?.type === "harness-close"), false,
  "unsettled flush closed the harness");
assertNoWorkerErrors(unsettledAbortEvents,
  "unsettled abort emitted test-worker-error");
await unsettledAbortWorker.terminate();
unsettledAbortChannel.port2.close();

// A stop received after the port is attached but before storage finishes must
// invalidate the generation immediately. The startup await then converges to
// false and the same safe finalizer can close the one late-opened database.
const startupStopWorker = new Worker(harness, {
  eval: true,
  workerData: {
    openDelayMillis: 120,
    storageStartupSettleMillis: 1000,
  },
});
const startupStopEvents = [];
startupStopWorker.on("message", (message) => startupStopEvents.push(message));
startupStopWorker.on("error", (error) => {
  startupStopEvents.push({type: "test-worker-error", detail: String(error)});
});
const startupStopChannel = new MessageChannel();
startupStopWorker.postMessage({
  type: "start",
  sessionId: "startup-stop-session",
  launchGeneration: "1",
  worldId: "startup-stop-world",
  newWorld: true,
  profileId: storageConfig.profileId,
  worldVersion: storageConfig.worldVersion,
  storageSchema: storageConfig.storageSchema,
  storageDatabaseName: storageConfig.storageDatabaseName,
  storagePrefix: storageConfig.storagePrefix,
  storageOpfsDirectory: storageConfig.storageOpfsDirectory,
  renderDistance: 6,
  simulationDistance: 4,
  serverScriptUrl: "file:///Downloads/singleplayer-server.js",
  port: startupStopChannel.port1,
}, [startupStopChannel.port1]);
await waitForWorkerEvent(startupStopEvents, "test-db-open");
startupStopWorker.postMessage({type: "stop"});
await waitForWorkerEvent(startupStopEvents, "stopped", 2000);
await waitForWorkerEvent(startupStopEvents, "harness-close", 2000);
assert.equal(startupStopEvents.some((message) => message?.type === "runtime-ready"), false,
  "stop during storage startup dispatched the runtime");
assert.equal(startupStopEvents.filter((message) => message?.type === "test-db-close").length, 1,
  "stop during startup did not close the late-opened database exactly once");
assertNoWorkerErrors(startupStopEvents,
  "startup stop emitted test-worker-error");
await startupStopWorker.terminate();
startupStopChannel.port2.close();

// If the portable server asset rejects while storage is still opening, the
// startup generation is cancelled and the late open is closed locally.  No
// storage callbacks may be installed after the bootstrap has already failed.
const assetRaceWorker = new Worker(harness, {
  eval: true,
  workerData: {
    assetFailure: true,
    openDelayMillis: 75,
    storageStartupSettleMillis: 1000,
  },
});
const assetRaceEvents = [];
assetRaceWorker.on("message", (message) => assetRaceEvents.push(message));
assetRaceWorker.on("error", (error) => {
  assetRaceEvents.push({type: "test-worker-error", detail: String(error)});
});
const assetRaceChannel = new MessageChannel();
assetRaceWorker.postMessage({
  type: "start",
  sessionId: "asset-race-session",
  launchGeneration: "1",
  worldId: "asset-race-world",
  newWorld: true,
  profileId: storageConfig.profileId,
  worldVersion: storageConfig.worldVersion,
  storageSchema: storageConfig.storageSchema,
  storageDatabaseName: storageConfig.storageDatabaseName,
  storagePrefix: storageConfig.storagePrefix,
  storageOpfsDirectory: storageConfig.storageOpfsDirectory,
  renderDistance: 6,
  simulationDistance: 4,
  serverScriptGzipUrl: "file:///Downloads/missing-server.js.gz",
  port: assetRaceChannel.port1,
}, [assetRaceChannel.port1]);
const assetCrash = await waitForWorkerEvent(assetRaceEvents, "bootstrap-crash", 2000);
assert.match(String(assetCrash.detail || ""), /deterministic asset failure/);
await new Promise((resolve) => setTimeout(resolve, 150));
assetRaceWorker.postMessage({type: "test-late-fs-put"});
const lateStorage = await waitForWorkerEvent(assetRaceEvents, "test-late-fs-result", 1000);
assert.equal(lateStorage.installed, false,
  "late storage startup installed filesystem callbacks after asset failure");
assert.equal(lateStorage.accepted, false,
  "late storage callback accepted a write after asset failure");
assert.equal(assetRaceEvents.some((message) => message?.type === "storage-ready"), false,
  "failed asset startup reported storage-ready");
assert.equal(assetRaceEvents.filter((message) => message?.type === "test-db-close").length, 1,
  "late IndexedDB open was not closed exactly once");
await assetRaceWorker.terminate();
assetRaceChannel.port2.close();

function parkedStartMessage(port) {
  return {
    type: "start",
    sessionId: "parked-start-session",
    launchGeneration: "1",
    worldId: "parked-world",
    newWorld: true,
    initialGameRules: initialGameRulesText,
    profileId: storageConfig.profileId,
    worldVersion: storageConfig.worldVersion,
    storageSchema: storageConfig.storageSchema,
    storageDatabaseName: storageConfig.storageDatabaseName,
    storagePrefix: storageConfig.storagePrefix,
    storageOpfsDirectory: storageConfig.storageOpfsDirectory,
    renderDistance: 6,
    simulationDistance: 4,
    serverScriptUrl: "file:///Downloads/singleplayer-server.js",
    port,
  };
}
const parkedPrewarm = {type: "prewarm", sessionId: "idle-parked", parkMain: true,
  profileId: storageConfig.profileId, serverScriptUrl: "file:///Downloads/singleplayer-server.js"};

// Parked prewarm: vanilla Main runs to Bootstrap before any world input exists, and continues
// exactly once when the start handshake has published the world, the port and storage.
{
  const parkedWorker = new Worker(harness, {eval: true, workerData: {parkedMain: true}});
  const parkedEvents = [];
  parkedWorker.on("message", (message) => parkedEvents.push(message));
  parkedWorker.postMessage(parkedPrewarm);
  const parked = await waitForWorkerEvent(parkedEvents, "parked-main-bootstrap");
  assert.equal(parked.worldId, "", "a parked prewarm ran Main with a world already named");
  assert.equal(parked.clientPort, false, "a parked prewarm ran Main with the client port");
  assert.equal(parked.storage, false, "a parked prewarm ran Main with storage installed");
  const ready = await waitForWorkerEvent(parkedEvents, "server-prewarm-ready");
  assert.equal(ready.bootstrap, true, "prewarm readiness must report the parked vanilla Main");
  await new Promise((resolve) => setTimeout(resolve, 40));
  assert.equal(parkedEvents.some((event) => event?.type === "parked-main-resumed"), false,
    "parked Main continued before the start handshake");
  const parkedChannel = new MessageChannel();
  parkedChannel.port2.start();
  parkedWorker.postMessage(parkedStartMessage(parkedChannel.port1), [parkedChannel.port1]);
  const resumed = await waitForWorkerEvent(parkedEvents, "parked-main-resumed", 2000);
  assert.equal(resumed.worldId, "parked-world");
  assert.equal(resumed.initialGameRules, initialGameRulesText);
  assert.equal(resumed.clientPort, true);
  assert.equal(resumed.storage, true, "parked Main resumed before storage was installed");
  await waitForWorkerEvent(parkedEvents, "runtime-ready");
  assert.deepEqual(parkedEvents.filter((event) => event?.type === "test-main-call")
    .map((event) => event.parked), [true], "start must reuse the parked Main, not call main() again");
  assertNoWorkerErrors(parkedEvents, "parked prewarm raised a Worker harness error");
  await parkedWorker.terminate();
  parkedChannel.port2.close();
}

// A parked Main that failed (for example in Bootstrap) is never reused: start releases that
// runtime and runs an unparked Main on a fresh one.
{
  const failedWorker = new Worker(harness, {eval: true,
    workerData: {parkedMain: true, parkedMainFails: true}});
  const failedEvents = [];
  failedWorker.on("message", (message) => failedEvents.push(message));
  failedWorker.postMessage(parkedPrewarm);
  await waitForWorkerEvent(failedEvents, "parked-main-bootstrap");
  await waitForWorkerEvent(failedEvents, "server-prewarm-ready");
  const failedChannel = new MessageChannel();
  failedChannel.port2.start();
  failedWorker.postMessage(parkedStartMessage(failedChannel.port1), [failedChannel.port1]);
  const discarded = await waitForWorkerEvent(failedEvents, "server-prewarm-failed", 2000);
  assert.match(String(discarded.detail), /parked main failed: deterministic Bootstrap failure/);
  const observed = await waitForWorkerEvent(failedEvents, "main-start-observed", 2000);
  assert.equal(observed.initialGameRules, initialGameRulesText);
  assert.deepEqual(failedEvents.filter((event) => event?.type === "test-main-call")
    .map((event) => event.parked), [true, false],
  "a failed parked Main must be followed by exactly one fresh unparked Main");
  assert.equal(failedEvents.some((event) => event?.type === "bootstrap-crash"), false);
  assertNoWorkerErrors(failedEvents, "parked failure fallback raised a Worker harness error");
  await failedWorker.terminate();
  failedChannel.port2.close();
}

// A Java main that cannot park reports 'unavailable' and runs nothing: the prewarm stays
// compile-only on that runtime and the start dispatches one unparked Main on it.
{
  const unavailableWorker = new Worker(harness, {eval: true,
    workerData: {parkedMain: true, parkedMainUnavailable: true}});
  const unavailableEvents = [];
  unavailableWorker.on("message", (message) => unavailableEvents.push(message));
  unavailableWorker.postMessage(parkedPrewarm);
  const ready = await waitForWorkerEvent(unavailableEvents, "server-prewarm-ready");
  assert.equal(ready.bootstrap, false, "an unavailable park must report a compile-only prewarm");
  unavailableWorker.postMessage({type: "park-main"});
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(unavailableEvents.some((event) => event?.type === "parked-main-bootstrap"), false);
  const unavailableChannel = new MessageChannel();
  unavailableChannel.port2.start();
  unavailableWorker.postMessage(parkedStartMessage(unavailableChannel.port1), [unavailableChannel.port1]);
  const observed = await waitForWorkerEvent(unavailableEvents, "main-start-observed", 2000);
  assert.equal(observed.initialGameRules, initialGameRulesText);
  assert.deepEqual(unavailableEvents.filter((event) => event?.type === "test-main-call")
    .map((event) => event.parked), [true, false],
  "an unavailable park must be followed by exactly one unparked Main on the same runtime");
  assert.equal(unavailableEvents.some((event) => event?.type === "server-prewarm-failed"), false,
    "an unavailable park must not discard the prewarmed runtime");
  assertNoWorkerErrors(unavailableEvents, "unavailable parked prewarm raised a Worker harness error");
  await unavailableWorker.terminate();
  unavailableChannel.port2.close();
}

// A runtime without the Java parking entry point keeps the compile-only prewarm.
{
  const legacyWorker = new Worker(harness, {eval: true, workerData: {}});
  const legacyEvents = [];
  legacyWorker.on("message", (message) => legacyEvents.push(message));
  legacyWorker.postMessage(parkedPrewarm);
  const ready = await waitForWorkerEvent(legacyEvents, "server-prewarm-ready");
  assert.equal(ready.bootstrap, false);
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(legacyEvents.some((event) => event?.type === "main-start-observed"), false,
    "an unsupported runtime dispatched Main during prewarm");
  const legacyChannel = new MessageChannel();
  legacyChannel.port2.start();
  legacyWorker.postMessage(parkedStartMessage(legacyChannel.port1), [legacyChannel.port1]);
  await waitForWorkerEvent(legacyEvents, "main-start-observed", 2000);
  assertNoWorkerErrors(legacyEvents, "unsupported parked prewarm raised a Worker harness error");
  await legacyWorker.terminate();
  legacyChannel.port2.close();
}

console.log(JSON.stringify({
  ok: true,
  parkedPrewarm: true,
  phases: events.map((message) => message && message.type).filter(Boolean),
  latePort: true,
  duplicateStart: true,
  cleanStop: true,
  portableWorkerUrl: true,
  scalarTelemetryPong: true,
  storageTelemetryPong: true,
  measurementWindowIsolation: true,
  measurementIdEcho: true,
  workerResetAtEcho: true,
  flushTimeoutAbortOrdering: true,
  assetFailureStorageCancellation: true,
}));
